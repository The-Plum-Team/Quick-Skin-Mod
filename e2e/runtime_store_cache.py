#!/usr/bin/env python3
"""Derive the transportable identity of the installed-server RuntimeStore.

A loader server install is the only packaged-runtime material that a job rebuilds from an
installer which downloads its own Maven libraries. Quick Skin pins every byte it fetches
itself, but those libraries belong to the installer, so repeating the install repeats an
unpinned third-party download. This module names the store material that may travel between
jobs and the exact key it travels under.

The key is a recipe identity, never a source commit: the same installed tree stays valid for
every commit that keeps the same matrix row and installer. Only Forge and NeoForge servers are
transported, because only their installers resolve libraries from a Maven repository; a Fabric
server install fetches the vanilla server through Mojang's hash-pinned manifest.

Restoring is always optional. The store validates a restored recipe record, its tree manifest
and every blob before use, so unusable material is a cache miss and a fresh install, never a
gate failure.
"""

from __future__ import annotations

import argparse
import json
import platform
import re
import sys
from pathlib import Path
from typing import Any, Sequence

REPOSITORY = Path(__file__).resolve().parents[1]
if str(REPOSITORY / "e2e") not in sys.path:
    sys.path.append(str(REPOSITORY / "e2e"))

from packaged_runtime import RuntimeFailure, server_runtime_recipe  # noqa: E402
from runtime_store import STORE_DIRECTORY, STORE_VERSION  # noqa: E402

CACHE_SCHEMA = 1
CACHE_KEY_PREFIX = f"runtime-store-v{CACHE_SCHEMA}"
CACHE_KEY = re.compile(
    rf"^runtime-store-v[1-9][0-9]*\|[a-z0-9_.-]+\|[a-z0-9_.-]+\|[0-9a-f]{{64}}$"
)
# Only these loader installers resolve their own libraries from a Maven repository.
TRANSPORTED_LOADERS = frozenset({"forge", "neoforge"})
# Immutable content-addressed material only. Leases, staging and quarantine are machine-local
# run state whose OS locks and device/inode identities are meaningless on another runner.
IMMUTABLE_SUBDIRECTORIES = ("blobs", "recipes", "trees")
HOST_TOKEN = re.compile(r"^[a-z0-9_.-]+$")


class CacheIdentityError(ValueError):
    """The cache identity of the installed-server store cannot be established."""


def _host_token(value: str, label: str) -> str:
    if not isinstance(value, str):
        raise CacheIdentityError(f"{label} must be a string")
    token = value.strip().lower()
    if HOST_TOKEN.fullmatch(token) is None:
        raise CacheIdentityError(f"{label} must match {HOST_TOKEN.pattern}: {value!r}")
    return token


def transported(row: Any) -> bool:
    """Whether this runtime row's installed server travels between jobs."""
    if not isinstance(row, dict):
        raise CacheIdentityError("runtime row must be an object")
    loader = row.get("loader")
    if not isinstance(loader, str) or not loader:
        raise CacheIdentityError("runtime row must name its loader")
    return loader in TRANSPORTED_LOADERS


def cache_key(
    matrix: dict[str, Any],
    row: dict[str, Any],
    *,
    os_name: str | None = None,
    architecture: str | None = None,
) -> str:
    """Return the exact Actions cache key for one runtime row's installed server."""
    resolved_os = _host_token(
        platform.system() if os_name is None else os_name, "operating system"
    )
    resolved_arch = _host_token(
        platform.machine() if architecture is None else architecture, "architecture"
    )
    try:
        recipe = server_runtime_recipe(
            matrix, row, os_name=resolved_os, architecture=resolved_arch
        )
    except RuntimeFailure as exc:
        raise CacheIdentityError(f"runtime row has no server recipe: {exc}") from exc
    key = f"{CACHE_KEY_PREFIX}|{resolved_os}|{resolved_arch}|{recipe.digest()}"
    if CACHE_KEY.fullmatch(key) is None:  # Defensive: the tokens above are already validated.
        raise CacheIdentityError(f"derived an invalid cache key: {key!r}")
    return key


def store_paths(root: Path) -> list[str]:
    """Return the immutable store directories that may travel, in a stable order."""
    base = Path(root) / STORE_DIRECTORY / STORE_VERSION
    return [str(base / name) for name in IMMUTABLE_SUBDIRECTORIES]


def every_key(
    matrix: dict[str, Any],
    *,
    os_name: str | None = None,
    architecture: str | None = None,
) -> list[str]:
    """Every cache key the current matrix can still restore, for retention decisions."""
    if str(REPOSITORY / "scripts" / "release") not in sys.path:
        sys.path.append(str(REPOSITORY / "scripts" / "release"))
    from matrix import gha_matrix  # noqa: WPS433 - protected matrix reader

    keys: set[str] = set()
    for kind in ("pr-anchors", "native-anchors", "runtime"):
        try:
            rows = gha_matrix(matrix, kind, "0.0.0")["include"]
        except Exception:  # noqa: BLE001 - an inactive kind contributes no key
            continue
        for row in rows:
            if not isinstance(row, dict) or "loader" not in row or not transported(row):
                continue
            keys.add(cache_key(matrix, row, os_name=os_name, architecture=architecture))
    if not keys:
        raise CacheIdentityError("the release matrix declares no transportable server runtime")
    return sorted(keys)


def _load_matrix(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise CacheIdentityError(f"cannot read release matrix: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise CacheIdentityError(f"invalid release matrix JSON: {exc}") from exc
    if not isinstance(payload, dict):
        raise CacheIdentityError("release matrix root must be a JSON object")
    return payload


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--row-json", help="One authoritative runtime matrix row as JSON")
    parser.add_argument("--store-root", type=Path, help="Installed-server store root")
    parser.add_argument("--list-keys", action="store_true")
    parser.add_argument("--os")
    parser.add_argument("--arch")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)

    try:
        matrix = _load_matrix(args.matrix)
        if args.list_keys:
            if args.row_json is not None:
                raise CacheIdentityError("--list-keys does not accept a runtime row")
            for key in every_key(matrix, os_name=args.os, architecture=args.arch):
                print(key)
            return 0
        if args.row_json is None:
            raise CacheIdentityError("one of --row-json or --list-keys is required")
        try:
            row = json.loads(args.row_json)
        except json.JSONDecodeError as exc:
            raise CacheIdentityError(f"invalid runtime row JSON: {exc}") from exc
        if not isinstance(row, dict):
            raise CacheIdentityError("runtime row must be an object")
        if not transported(row):
            key, paths = "", []
        else:
            key = cache_key(matrix, row, os_name=args.os, architecture=args.arch)
            paths = store_paths(args.store_root) if args.store_root is not None else []
        lines = [
            f"transported={'true' if key else 'false'}",
            f"key={key}",
        ]
        # A workflow output carrying several lines needs an explicit delimiter. The digest is
        # unique to this run and cannot appear inside a filesystem path we just derived.
        delimiter = f"paths-{key[-32:] or 'empty'}"
        lines.extend([f"paths<<{delimiter}", *paths, delimiter])
    except CacheIdentityError as exc:
        print(f"Runtime store cache identity error: {exc}", file=sys.stderr)
        return 2

    rendered = "\n".join(lines) + "\n"
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as stream:
            stream.write(rendered)
    else:
        sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
