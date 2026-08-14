#!/usr/bin/env python3
"""Classify version-port merge conflicts without inspecting or mutating Git state.

The synchronization workflow supplies the original unmerged path list and a
trusted snapshot of the target release matrix.  This module turns those inputs
into a small, deterministic plan.  Protected conflicts are handled only when
their exact path has a predefined mechanical policy; every other protected
path fails closed before an AI job can see it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import stat
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Sequence

from ai_patch_policy import MAX_PATHS, PolicyError, is_ai_protected, normalize_path


SCHEMA_VERSION = 1
MAX_PATHS_FILE_BYTES = 256 * 1024
MAX_MATRIX_BYTES = 4 * 1024 * 1024

SOURCE_PATHS = frozenset(
    {
        "docs/ai/WORKFLOW.md",
        "e2e/README.md",
        "e2e/packaged_runtime.py",
    }
)
TARGET_PATHS = frozenset({"release/release-matrix.json"})
KNOWN_LOADERS = frozenset({"fabric", "forge", "neoforge"})
LOADER_BUILD_PATHS = {
    f"{loader}/build.gradle.kts": loader for loader in sorted(KNOWN_LOADERS)
}


class ConflictClassificationError(ValueError):
    """Raised when the original conflict set cannot be handled safely."""


@dataclass(frozen=True)
class ConflictClassification:
    source_paths: tuple[str, ...]
    target_paths: tuple[str, ...]
    delete_paths: tuple[str, ...]
    ai_paths: tuple[str, ...]

    def to_payload(self) -> dict[str, Any]:
        """Return the exact public schema in its stable serialization order."""

        return {
            "schema_version": SCHEMA_VERSION,
            "source_paths": list(self.source_paths),
            "target_paths": list(self.target_paths),
            "delete_paths": list(self.delete_paths),
            "ai_paths": list(self.ai_paths),
        }


@dataclass(frozen=True)
class TargetMatrixProfile:
    active_loaders: frozenset[str]
    active_overlay_roots: frozenset[str]


def _read_bounded_regular_utf8(path: Path, *, limit: int, label: str) -> str:
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            metadata = os.fstat(handle.fileno())
            if not stat.S_ISREG(metadata.st_mode):
                raise ConflictClassificationError(f"{label} must be a regular file")
            if metadata.st_size > limit:
                raise ConflictClassificationError(
                    f"{label} exceeds the {limit}-byte limit"
                )
            payload = handle.read(limit + 1)
    except ConflictClassificationError:
        raise
    except OSError as exc:
        raise ConflictClassificationError(f"cannot read {label}: {exc}") from exc
    if len(payload) > limit:
        raise ConflictClassificationError(f"{label} exceeds the {limit}-byte limit")
    try:
        return payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ConflictClassificationError(f"{label} must be UTF-8") from exc


def read_conflict_paths(path: Path) -> tuple[str, ...]:
    """Read and normalize one LF-delimited original-conflict path list."""

    text = _read_bounded_regular_utf8(
        path, limit=MAX_PATHS_FILE_BYTES, label="conflict paths file"
    )
    raw_paths = text.split("\n")
    if raw_paths and raw_paths[-1] == "":
        raw_paths.pop()
    if not raw_paths:
        raise ConflictClassificationError(
            "conflict paths file must contain at least one path"
        )

    normalized: list[str] = []
    portable: dict[str, str] = {}
    for raw_path in raw_paths:
        try:
            candidate = normalize_path(raw_path)
        except PolicyError as exc:
            raise ConflictClassificationError(str(exc)) from exc
        collision_key = candidate.casefold()
        previous = portable.get(collision_key)
        if previous is not None:
            if previous == candidate:
                raise ConflictClassificationError(
                    f"duplicate conflict path {candidate!r}"
                )
            raise ConflictClassificationError(
                "case-colliding conflict paths: "
                f"{previous!r}, {candidate!r}"
            )
        portable[collision_key] = candidate
        normalized.append(candidate)
    return tuple(sorted(normalized))


def _reject_duplicate_json_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def _reject_nonfinite_json(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _read_matrix(matrix_path: Path) -> dict[str, Any]:
    text = _read_bounded_regular_utf8(
        matrix_path, limit=MAX_MATRIX_BYTES, label="release matrix"
    )
    try:
        matrix = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_json_keys,
            parse_constant=_reject_nonfinite_json,
        )
    except (json.JSONDecodeError, ValueError) as exc:
        raise ConflictClassificationError(f"invalid release matrix JSON: {exc}") from exc
    if not isinstance(matrix, dict) or matrix.get("schema_version") != 2:
        raise ConflictClassificationError(
            "release matrix must be a schema_version 2 object"
        )
    return matrix


def _active_loaders(matrix: dict[str, Any]) -> frozenset[str]:
    artifacts = matrix.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise ConflictClassificationError(
            "release matrix artifacts must be a non-empty array"
        )

    active: set[str] = set()
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            raise ConflictClassificationError(
                f"release matrix artifact {index} must be an object"
            )
        loader = artifact.get("loader")
        if not isinstance(loader, str) or loader not in KNOWN_LOADERS:
            raise ConflictClassificationError(
                f"release matrix artifact {index} has unknown loader {loader!r}"
            )
        active.add(loader)
    return frozenset(active)


def read_active_loaders(matrix_path: Path) -> frozenset[str]:
    """Read exactly the loader identities needed by the pure classifier."""

    return _active_loaders(_read_matrix(matrix_path))


def read_target_matrix_profile(matrix_path: Path) -> TargetMatrixProfile:
    """Read the target loaders and exact matrix-owned live overlay roots."""

    matrix = _read_matrix(matrix_path)
    active_loaders = _active_loaders(matrix)
    source_overlays = matrix.get("source_overlays")
    expected_modules = {"common", *active_loaders}
    if not isinstance(source_overlays, dict) or set(source_overlays) != expected_modules:
        raise ConflictClassificationError(
            "release matrix source_overlays must define common and every active loader"
        )

    roots: set[str] = set()
    for module, routes in source_overlays.items():
        if not isinstance(routes, dict):
            raise ConflictClassificationError(
                f"release matrix source_overlays.{module} must be an object"
            )
        module_roots: set[str] = set()
        for version, overlay in routes.items():
            if not isinstance(version, str) or not version:
                raise ConflictClassificationError(
                    f"release matrix source_overlays.{module} has an invalid version"
                )
            if not isinstance(overlay, str) or not re.fullmatch(
                r"legacy[0-9A-Za-z_]+", overlay
            ):
                raise ConflictClassificationError(
                    f"release matrix source_overlays.{module}.{version} "
                    "must name a legacy* root"
                )
            if overlay in module_roots:
                raise ConflictClassificationError(
                    f"release matrix source_overlays.{module} reuses an overlay root"
                )
            module_roots.add(overlay)
            roots.add(f"{module}/src/{overlay}")
    return TargetMatrixProfile(active_loaders, frozenset(roots))


def _overlay_root(path: str) -> str | None:
    match = re.fullmatch(
        rf"(?P<module>common|{'|'.join(sorted(KNOWN_LOADERS))})/src/"
        r"(?P<overlay>legacy[0-9A-Za-z_]+)/.+",
        path,
    )
    if match is None:
        return None
    return f"{match.group('module')}/src/{match.group('overlay')}"


def is_inactive_overlay_path(
    path: str, active_overlay_roots: Iterable[str]
) -> bool:
    root = _overlay_root(path)
    return root is not None and root not in frozenset(active_overlay_roots)


def classify_conflicts(
    paths: Iterable[str],
    active_loaders: Iterable[str],
    active_overlay_roots: Iterable[str],
) -> ConflictClassification:
    """Classify normalized original conflicts into exact mechanical policies."""

    active = frozenset(active_loaders)
    if not active or not active.issubset(KNOWN_LOADERS):
        raise ConflictClassificationError(
            f"active loaders must be a non-empty subset of {sorted(KNOWN_LOADERS)}"
        )
    overlay_roots = frozenset(active_overlay_roots)
    for root in overlay_roots:
        if not isinstance(root, str):
            raise ConflictClassificationError(
                f"active overlay root {root!r} is not owned by the target matrix"
            )
        match = re.fullmatch(
            rf"(?P<module>common|{'|'.join(sorted(KNOWN_LOADERS))})/src/"
            r"legacy[0-9A-Za-z_]+",
            root,
        )
        if match is None or (
            match.group("module") != "common" and match.group("module") not in active
        ):
            raise ConflictClassificationError(
                f"active overlay root {root!r} is not owned by the target matrix"
            )

    source_paths: list[str] = []
    target_paths: list[str] = []
    delete_paths: list[str] = []
    ai_paths: list[str] = []
    seen: set[str] = set()
    portable: dict[str, str] = {}

    for raw_path in paths:
        try:
            path = normalize_path(raw_path)
        except PolicyError as exc:
            raise ConflictClassificationError(str(exc)) from exc
        collision_key = path.casefold()
        previous = portable.get(collision_key)
        if path in seen:
            raise ConflictClassificationError(f"duplicate conflict path {path!r}")
        if previous is not None:
            raise ConflictClassificationError(
                f"case-colliding conflict paths: {previous!r}, {path!r}"
            )
        seen.add(path)
        portable[collision_key] = path

        if path in SOURCE_PATHS:
            source_paths.append(path)
            continue
        if path in TARGET_PATHS:
            target_paths.append(path)
            continue
        loader = LOADER_BUILD_PATHS.get(path)
        if loader is not None:
            if loader in active:
                raise ConflictClassificationError(
                    f"cannot delete active-loader build conflict {path!r}"
                )
            delete_paths.append(path)
            continue
        if is_inactive_overlay_path(path, overlay_roots):
            delete_paths.append(path)
            continue
        if is_ai_protected(path):
            raise ConflictClassificationError(
                f"unknown protected version-port conflict {path!r}"
            )
        ai_paths.append(path)

    if not seen:
        raise ConflictClassificationError("conflict set must not be empty")
    if len(ai_paths) > MAX_PATHS["conflict"]:
        raise ConflictClassificationError(
            "AI conflict set contains "
            f"{len(ai_paths)} paths; limit is {MAX_PATHS['conflict']}"
        )
    return ConflictClassification(
        source_paths=tuple(sorted(source_paths)),
        target_paths=tuple(sorted(target_paths)),
        delete_paths=tuple(sorted(delete_paths)),
        ai_paths=tuple(sorted(ai_paths)),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--paths-file", type=Path, required=True)
    parser.add_argument("--matrix", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        paths = read_conflict_paths(args.paths_file)
        profile = read_target_matrix_profile(args.matrix)
        result = classify_conflicts(
            paths,
            profile.active_loaders,
            profile.active_overlay_roots,
        )
    except ConflictClassificationError as exc:
        print(f"version-port conflict classification error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result.to_payload(), separators=(",", ":"), ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
