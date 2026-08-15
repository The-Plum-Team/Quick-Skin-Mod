#!/usr/bin/env python3
"""Explicitly refresh the immutable Modrinth compatibility artifact lock.

This maintenance command is the only place that selects a newest upstream version. Runtime and CI
consume only the resulting URL, size, SHA-256, and SHA-512 fields and never call the Modrinth API.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
import subprocess
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from mod_compatibility import (
    ALLOWED_DOWNLOAD_HOSTS,
    DEFAULT_CONTRACT,
    MAX_CONTRACT_BYTES,
    MAX_DOWNLOAD_BYTES,
    CompatibilityContractError,
    load_contract,
)


API_MAX_BYTES = 16 * 1024 * 1024
MAX_PARALLEL_DOWNLOADS = 8
USER_AGENT = "The-Plum-Team/Quick-Skin-Mod compatibility-lock-maintainer/1"


def _request_json(url: str) -> Any:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        final = urllib.parse.urlsplit(response.geturl())
        if final.scheme != "https" or final.hostname != "api.modrinth.com":
            raise CompatibilityContractError("Modrinth API redirected outside api.modrinth.com")
        raw = response.read(API_MAX_BYTES + 1)
    if not raw or len(raw) > API_MAX_BYTES:
        raise CompatibilityContractError("Modrinth API response exceeded its byte limit")
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise CompatibilityContractError(f"invalid Modrinth API response: {exc}") from exc


def _version_tuple(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in value.split("."))


def discover_versions(repository: Path) -> list[str]:
    command = [
        "git",
        "for-each-ref",
        "--format=%(refname:strip=3)",
        "refs/remotes/origin",
    ]
    try:
        branches = subprocess.run(
            command,
            cwd=repository,
            check=True,
            capture_output=True,
            text=True,
            timeout=30,
        ).stdout.splitlines()
    except (OSError, subprocess.SubprocessError) as exc:
        raise CompatibilityContractError(f"cannot discover origin version branches: {exc}") from exc
    versions: set[str] = set()
    for branch in branches:
        prefix, separator, version = branch.rpartition("-")
        if separator and "-and-" in prefix:
            try:
                _version_tuple(version)
            except ValueError:
                continue
            versions.add(version)
    if not versions:
        raise CompatibilityContractError("origin contains no release-version branches")
    return sorted(versions, key=_version_tuple)


def _load_authored_contract(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CompatibilityContractError(f"cannot read authored compatibility lock: {exc}") from exc
    load_contract(path)
    return data


def _allowed_lane(mod: dict[str, Any], version: str, loader: str) -> bool:
    if loader not in mod["loaders"]:
        return False
    if any(
        item["runtime_version"] == version and item["loader"] == loader
        for item in mod["excluded_lanes"]
    ):
        return False
    supported = mod["supported_game_versions"]
    return supported is None or version in supported


def _primary_file(version: dict[str, Any]) -> dict[str, Any]:
    files = version.get("files")
    if not isinstance(files, list) or not files:
        raise CompatibilityContractError(
            f"Modrinth version {version.get('id')!r} has no files"
        )
    primary = [item for item in files if isinstance(item, dict) and item.get("primary") is True]
    if len(primary) == 1:
        selected = primary[0]
    elif len(files) == 1 and isinstance(files[0], dict):
        selected = files[0]
    else:
        raise CompatibilityContractError(
            f"Modrinth version {version.get('id')!r} has ambiguous primary files"
        )
    required = {"filename", "url", "size", "hashes"}
    if not required <= set(selected):
        raise CompatibilityContractError("Modrinth primary file metadata is incomplete")
    return selected


def _required_dependencies(version: dict[str, Any]) -> set[str]:
    dependencies = version.get("dependencies")
    if not isinstance(dependencies, list):
        raise CompatibilityContractError("Modrinth version dependencies are malformed")
    required: set[str] = set()
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            raise CompatibilityContractError("Modrinth dependency entry is malformed")
        if dependency.get("dependency_type") != "required":
            continue
        project_id = dependency.get("project_id")
        if not isinstance(project_id, str):
            raise CompatibilityContractError("required file-only dependencies are unsupported")
        required.add(project_id)
    return required


def select_artifacts(
    mod: dict[str, Any],
    versions: list[str],
    upstream: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    selected_by_lane: dict[tuple[str, str], dict[str, Any]] = {}
    allowed_types = set(mod["allowed_version_types"])
    for game_version in versions:
        for loader in ("fabric", "forge", "neoforge"):
            if not _allowed_lane(mod, game_version, loader):
                continue
            candidates = [
                item
                for item in upstream
                if isinstance(item, dict)
                and item.get("version_type") in allowed_types
                and game_version in item.get("game_versions", [])
                and loader in item.get("loaders", [])
            ]
            if not candidates:
                continue
            candidates.sort(key=lambda item: (item.get("date_published", ""), item.get("id", "")))
            selected_by_lane[(game_version, loader)] = candidates[-1]

    grouped: dict[tuple[str, str], dict[str, Any]] = {}
    for (game_version, loader), version in selected_by_lane.items():
        version_id = version.get("id")
        if not isinstance(version_id, str):
            raise CompatibilityContractError("Modrinth version has no stable id")
        required = _required_dependencies(version)
        unknown = required - set(mod["provided_dependencies"])
        if unknown:
            raise CompatibilityContractError(
                f"{mod['id']} {version_id} needs unlocked projects {sorted(unknown)}"
            )
        selected_file = _primary_file(version)
        key = (version_id, loader)
        previous = grouped.setdefault(
            key,
            {
                "version_id": version_id,
                "version_number": version["version_number"],
                "version_type": version["version_type"],
                "published_at": version["date_published"],
                "loader": loader,
                "game_versions": [],
                "_file": selected_file,
            },
        )
        if previous["_file"]["url"] != selected_file["url"]:
            raise CompatibilityContractError(
                f"Modrinth version {version_id} maps one loader to multiple primary files"
            )
        previous["game_versions"].append(game_version)
    return sorted(
        grouped.values(),
        key=lambda item: (
            min(_version_tuple(value) for value in item["game_versions"]),
            item["loader"],
            item["version_id"],
        ),
    )


def _download_and_hash(item: dict[str, Any], cache: Path) -> dict[str, Any]:
    source = item["_file"]
    url = source["url"]
    parsed = urllib.parse.urlsplit(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS
        or parsed.query
        or parsed.fragment
    ):
        raise CompatibilityContractError(f"unsafe Modrinth CDN URL: {url!r}")
    filename = source["filename"]
    size = source["size"]
    hashes = source["hashes"]
    if (
        not isinstance(filename, str)
        or Path(filename).name != filename
        or not filename.lower().endswith(".jar")
        or isinstance(size, bool)
        or not isinstance(size, int)
        or not 1 <= size <= MAX_DOWNLOAD_BYTES
        or not isinstance(hashes, dict)
        or not isinstance(hashes.get("sha512"), str)
    ):
        raise CompatibilityContractError("unsafe Modrinth primary file metadata")
    sha512 = hashes["sha512"]
    cached = cache / f"{sha512}.jar"
    cache.mkdir(parents=True, exist_ok=True)
    digest256 = hashlib.sha256()
    digest512 = hashlib.sha512()
    if cached.is_file() and cached.stat().st_size == size:
        with cached.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest256.update(block)
                digest512.update(block)
    else:
        cached.unlink(missing_ok=True)
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        descriptor, temporary_name = tempfile.mkstemp(dir=cache, prefix="download-", suffix=".jar")
        temporary = Path(temporary_name)
        total = 0
        try:
            with os.fdopen(descriptor, "wb") as output, urllib.request.urlopen(
                request, timeout=300
            ) as response:
                final = urllib.parse.urlsplit(response.geturl())
                if final.scheme != "https" or final.hostname not in ALLOWED_DOWNLOAD_HOSTS:
                    raise CompatibilityContractError("CDN download redirected outside Modrinth")
                for block in iter(lambda: response.read(1024 * 1024), b""):
                    total += len(block)
                    if total > size or total > MAX_DOWNLOAD_BYTES:
                        raise CompatibilityContractError("CDN download exceeded its published size")
                    digest256.update(block)
                    digest512.update(block)
                    output.write(block)
                output.flush()
                os.fsync(output.fileno())
            if total != size:
                raise CompatibilityContractError(
                    f"CDN size mismatch for {filename}: {total} != {size}"
                )
            os.replace(temporary, cached)
        finally:
            temporary.unlink(missing_ok=True)
    if digest512.hexdigest() != sha512:
        cached.unlink(missing_ok=True)
        raise CompatibilityContractError(f"Modrinth SHA-512 mismatch for {filename}")
    return {
        "filename": filename,
        "url": url,
        "size": size,
        "sha256": digest256.hexdigest(),
        "sha512": sha512,
    }


def refresh(
    path: Path,
    *,
    versions: list[str],
    cache: Path,
    lock_date: str,
) -> dict[str, Any]:
    data = _load_authored_contract(path)
    pending: list[dict[str, Any]] = []
    for mod in data["mods"]:
        endpoint = f"{data['artifact_source']['api_base']}/project/{mod['project_id']}/version"
        upstream = _request_json(endpoint)
        if not isinstance(upstream, list):
            raise CompatibilityContractError(f"Modrinth versions for {mod['id']} are malformed")
        selected = select_artifacts(mod, versions, upstream)
        mod["artifacts"] = selected
        pending.extend(selected)

    unique_files: dict[str, dict[str, Any]] = {}
    for artifact in pending:
        unique_files.setdefault(artifact["_file"]["url"], artifact)
    hashed: dict[str, dict[str, Any]] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=MAX_PARALLEL_DOWNLOADS) as executor:
        futures = {
            executor.submit(_download_and_hash, artifact, cache): url
            for url, artifact in unique_files.items()
        }
        for future in concurrent.futures.as_completed(futures):
            hashed[futures[future]] = future.result()
    for artifact in pending:
        source = artifact.pop("_file")
        artifact["game_versions"].sort(key=_version_tuple)
        artifact["files"] = [hashed[source["url"]]]
    data["lock_revision"] = lock_date
    return data


def write_atomic(path: Path, data: dict[str, Any]) -> None:
    payload = (json.dumps(data, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if len(payload) > MAX_CONTRACT_BYTES:
        raise CompatibilityContractError("refreshed compatibility lock exceeds its byte limit")
    mode = path.stat().st_mode & 0o777
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    load_contract(path)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "qsm-mod-lock")
    parser.add_argument("--version", action="append", default=[])
    parser.add_argument("--lock-date", default=dt.datetime.now(dt.timezone.utc).date().isoformat())
    args = parser.parse_args()
    try:
        versions = sorted(set(args.version or discover_versions(args.repository)), key=_version_tuple)
        updated = refresh(
            args.contract,
            versions=versions,
            cache=args.cache,
            lock_date=args.lock_date,
        )
        write_atomic(args.contract, updated)
        print(
            f"Locked {sum(len(mod['artifacts']) for mod in updated['mods'])} "
            f"compatibility artifacts across {len(versions)} Minecraft versions"
        )
        return 0
    except (CompatibilityContractError, OSError, ValueError) as exc:
        parser.exit(2, f"compatibility lock refresh failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
