#!/usr/bin/env python3
"""Stage and publish an exact, recoverable GitHub Release without destructive rollback."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


RELEASE_VIEW_ATTEMPTS = 5
RELEASE_VIEW_DELAY_SECONDS = 3.0


class GitHubReleaseError(RuntimeError):
    pass


@dataclass(frozen=True)
class ReleaseContract:
    tag: str
    commit: str
    assets: tuple[Path, ...]
    hashes: dict[str, str]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_contract(manifest_path: Path, stage: Path, expected_tag: str, commit: str) -> ReleaseContract:
    if expected_tag.startswith("build-"):
        raise GitHubReleaseError("a shared build bundle cannot be published; select a Minecraft target")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GitHubReleaseError(f"cannot read release manifest: {exc}") from exc
    if manifest.get("schema_version") != 2:
        raise GitHubReleaseError("GitHub publication requires artifact manifest schema 2")
    release = manifest.get("release", {})
    if release.get("tag") != expected_tag:
        raise GitHubReleaseError("workflow tag disagrees with artifact manifest")
    if manifest.get("git_commit") != commit:
        raise GitHubReleaseError("workflow commit disagrees with artifact manifest")

    root = stage.resolve()
    assets: list[Path] = []
    hashes: dict[str, str] = {}
    records = manifest.get("artifacts")
    lane_count = manifest.get("lane_count")
    if (
        isinstance(lane_count, bool)
        or not isinstance(lane_count, int)
        or lane_count <= 0
        or not isinstance(records, list)
        or len(records) != lane_count
    ):
        raise GitHubReleaseError("artifact manifest has an invalid production node inventory")
    nodes = [record.get("artifact_node") for record in records if isinstance(record, dict)]
    if len(nodes) != lane_count or any(not isinstance(node, str) or not node for node in nodes):
        raise GitHubReleaseError("artifact manifest contains a missing production node")
    if len(nodes) != len(set(nodes)):
        raise GitHubReleaseError("artifact manifest contains duplicate production nodes")

    for record in records:
        relative = record.get("path")
        if not isinstance(relative, str) or not relative:
            raise GitHubReleaseError("artifact manifest contains an invalid path")
        asset = (root / relative).resolve()
        if root not in asset.parents or not asset.is_file():
            raise GitHubReleaseError("release asset escapes the stage or is missing")
        name = str(record.get("filename", ""))
        expected_hash = str(record.get("sha256", ""))
        if asset.name != name or sha256(asset) != expected_hash:
            raise GitHubReleaseError(f"release asset bytes disagree with manifest: {name}")
        if name in hashes:
            raise GitHubReleaseError(f"duplicate release asset name: {name}")
        assets.append(asset)
        hashes[name] = expected_hash

    sbom = manifest.get("sbom")
    if not isinstance(sbom, dict):
        raise GitHubReleaseError("artifact manifest has no CycloneDX SBOM record")
    if sbom.get("format") != "CycloneDX" or sbom.get("spec_version") != "1.6":
        raise GitHubReleaseError("artifact manifest has an unsupported SBOM identity")
    relative = sbom.get("path")
    name = sbom.get("filename")
    expected_hash = sbom.get("sha256")
    if relative != "sbom/quick-skin.cdx.json" or name != "quick-skin.cdx.json":
        raise GitHubReleaseError("artifact manifest contains an invalid SBOM path")
    sbom_asset = (root / relative).resolve()
    if root not in sbom_asset.parents or not sbom_asset.is_file() or sbom_asset.is_symlink():
        raise GitHubReleaseError("SBOM release asset escapes the stage or is missing")
    if sbom.get("bytes") != sbom_asset.stat().st_size or sha256(sbom_asset) != expected_hash:
        raise GitHubReleaseError("SBOM release asset bytes disagree with manifest")
    if name in hashes:
        raise GitHubReleaseError(f"duplicate release asset name: {name}")
    assets.append(sbom_asset)
    hashes[name] = expected_hash

    manifest_hash = sha256(manifest_path)
    if manifest_path.name in hashes:
        raise GitHubReleaseError(f"duplicate release asset name: {manifest_path.name}")
    assets.append(manifest_path)
    hashes[manifest_path.name] = manifest_hash
    return ReleaseContract(expected_tag, commit, tuple(assets), hashes)


def write_checksums(contract: ReleaseContract, destination: Path) -> Path:
    checksums = destination / "SHA256SUMS"
    checksums.write_text(
        "".join(f"{digest}  {name}\n" for name, digest in sorted(contract.hashes.items())),
        encoding="utf-8",
    )
    return checksums


def run(command: list[str], *, text: bool = True, check: bool = True) -> subprocess.CompletedProcess[Any]:
    return subprocess.run(
        command,
        check=check,
        text=text,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def release_view(tag: str) -> dict[str, Any] | None:
    result = run(
        ["gh", "release", "view", tag, "--json", "databaseId,isDraft,tagName,url"],
        check=False,
    )
    if result.returncode != 0:
        if "release not found" in result.stderr.lower() or "not found" in result.stderr.lower():
            return None
        raise GitHubReleaseError(f"cannot inspect GitHub Release: {result.stderr.strip()}")
    try:
        value = json.loads(result.stdout)
    except json.JSONDecodeError as exc:
        raise GitHubReleaseError("GitHub CLI returned invalid release JSON") from exc
    return value


def release_view_after_create(
    tag: str, *, sleep: Callable[[float], None] = time.sleep
) -> dict[str, Any] | None:
    # A release created moments ago may not be readable yet; poll briefly before failing.
    for attempt in range(RELEASE_VIEW_ATTEMPTS):
        release = release_view(tag)
        if release is not None:
            return release
        if attempt + 1 < RELEASE_VIEW_ATTEMPTS:
            print(
                f"created release {tag} is not yet visible "
                f"(attempt {attempt + 1}/{RELEASE_VIEW_ATTEMPTS}); waiting",
                file=sys.stderr,
            )
            sleep(RELEASE_VIEW_DELAY_SECONDS)
    return None


def release_assets(repository: str, release_id: int) -> list[dict[str, Any]]:
    result = run([
        "gh", "api", "--paginate", "--slurp",
        f"repos/{repository}/releases/{release_id}/assets",
    ])
    try:
        pages = json.loads(result.stdout)
        return [asset for page in pages for asset in page]
    except (json.JSONDecodeError, TypeError) as exc:
        raise GitHubReleaseError("GitHub CLI returned invalid release-asset JSON") from exc


def verify_remote_assets(
    repository: str,
    release_id: int,
    expected: dict[str, tuple[Path, str]],
    *,
    allow_missing: bool,
) -> set[str]:
    remote = release_assets(repository, release_id)
    names = [str(asset.get("name", "")) for asset in remote]
    if len(names) != len(set(names)):
        raise GitHubReleaseError("GitHub Release contains duplicate asset names")
    extras = set(names) - set(expected)
    if extras:
        raise GitHubReleaseError(f"GitHub Release contains unexpected assets: {sorted(extras)}")
    missing = set(expected) - set(names)
    if missing and not allow_missing:
        raise GitHubReleaseError(f"GitHub Release is missing assets: {sorted(missing)}")

    by_name = {str(asset["name"]): asset for asset in remote}
    for name in set(expected) - missing:
        asset_id = by_name[name].get("id")
        if not isinstance(asset_id, int):
            raise GitHubReleaseError(f"GitHub asset {name} has no stable ID")
        downloaded = run([
            "gh", "api", "-H", "Accept: application/octet-stream",
            f"repos/{repository}/releases/assets/{asset_id}",
        ], text=False).stdout
        digest = hashlib.sha256(downloaded).hexdigest()
        if digest != expected[name][1]:
            raise GitHubReleaseError(f"GitHub asset {name} has different bytes")
    return missing


def assert_tag_commit(tag: str, commit: str) -> None:
    result = run(["git", "rev-parse", f"{tag}^{{commit}}"])
    if result.stdout.strip() != commit:
        raise GitHubReleaseError("release tag does not resolve to the artifact commit")


def stage_release(
    repository: str,
    contract: ReleaseContract,
    title: str,
    notes: Path,
    checksums: Path,
) -> None:
    assert_tag_commit(contract.tag, contract.commit)
    release = release_view(contract.tag)
    if release is None:
        run([
            "gh", "release", "create", contract.tag,
            "--repo", repository,
            "--draft",
            "--verify-tag",
            "--target", contract.commit,
            "--title", title,
            "--notes-file", str(notes),
        ])
        release = release_view_after_create(contract.tag)
    if release is None or release.get("tagName") != contract.tag:
        raise GitHubReleaseError("unable to create the canonical draft release")

    expected = {
        asset.name: (asset, contract.hashes[asset.name])
        for asset in contract.assets
    }
    expected[checksums.name] = (checksums, sha256(checksums))
    release_id = int(release["databaseId"])
    missing = verify_remote_assets(
        repository, release_id, expected, allow_missing=bool(release.get("isDraft"))
    )
    if missing and not release.get("isDraft"):
        raise GitHubReleaseError("a published release cannot be repaired in place")
    for name in sorted(missing):
        run([
            "gh", "release", "upload", contract.tag, str(expected[name][0]),
            "--repo", repository,
        ])
    verify_remote_assets(repository, release_id, expected, allow_missing=False)


def publish_release(
    repository: str,
    contract: ReleaseContract,
    checksums: Path,
) -> None:
    assert_tag_commit(contract.tag, contract.commit)
    release = release_view(contract.tag)
    if release is None:
        raise GitHubReleaseError("draft release has not been staged")
    expected = {
        asset.name: (asset, contract.hashes[asset.name])
        for asset in contract.assets
    }
    expected[checksums.name] = (checksums, sha256(checksums))
    verify_remote_assets(
        repository, int(release["databaseId"]), expected, allow_missing=False
    )
    if release.get("isDraft"):
        run([
            "gh", "release", "edit", contract.tag,
            "--repo", repository,
            "--draft=false",
            "--latest",
        ])
    final = release_view(contract.tag)
    if final is None or final.get("isDraft"):
        raise GitHubReleaseError("GitHub Release did not become published")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("stage", "publish"))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--notes", type=Path, default=Path("CHANGELOG.md"))
    parser.add_argument("--manifest", type=Path, default=Path("build/release/artifacts.json"))
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    args = parser.parse_args()

    repository_root = Path(__file__).resolve().parents[2]
    stage = args.stage if args.stage.is_absolute() else repository_root / args.stage
    manifest = args.manifest if args.manifest.is_absolute() else repository_root / args.manifest
    notes = args.notes if args.notes.is_absolute() else repository_root / args.notes
    try:
        contract = load_contract(manifest, stage, args.tag, args.commit)
        if not notes.is_file():
            raise GitHubReleaseError(f"release notes are missing: {notes}")
        with tempfile.TemporaryDirectory(prefix="quickskin-release-") as temporary:
            checksums = write_checksums(contract, Path(temporary))
            if args.command == "stage":
                stage_release(args.repository, contract, args.title, notes, checksums)
            else:
                publish_release(args.repository, contract, checksums)
    except (GitHubReleaseError, OSError, subprocess.CalledProcessError, ValueError) as exc:
        print(f"GitHub Release failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
