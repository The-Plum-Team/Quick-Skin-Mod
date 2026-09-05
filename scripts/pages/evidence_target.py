"""Separate a Pages bundle's Minecraft target from its real Git source branch."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "release"))

from matrix import MatrixError, load_matrix, select_release_target  # noqa: E402
from version_branches import parse_version_branch  # noqa: E402


DEFAULT_MATRIX = REPO / "release/release-matrix.json"
MAX_MATRIX_BYTES = 5 * 1024 * 1024
TARGET_KEY = re.compile(r"^mc((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*))?)$")


class EvidenceTargetError(ValueError):
    pass


def bundle_version(key: str) -> str:
    """Validate a single directory/artifact key, including historical branch keys."""
    if not isinstance(key, str) or len(key) > 256:
        raise EvidenceTargetError("invalid evidence bundle key")
    shared = TARGET_KEY.fullmatch(key)
    if shared is not None:
        return shared.group(1)
    legacy = parse_version_branch(key)
    if legacy is None:
        raise EvidenceTargetError(f"invalid evidence bundle key {key!r}")
    return legacy.version


def manifest_key(manifest: dict[str, Any]) -> str:
    release = manifest["release"]
    key = (
        f"mc{release['version']}"
        if "matrix_sha256" in release
        else release["branch"]
    )
    bundle_version(key)
    return key


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceTargetError(f"duplicate JSON object key {key!r} in release matrix")
        result[key] = value
    return result


def _nonfinite(value: str) -> None:
    raise EvidenceTargetError(f"non-finite JSON number {value!r} in release matrix")


def _finite_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        _nonfinite(value)
    return result


@dataclass(frozen=True)
class EvidenceTarget:
    key: str
    branch: str
    version: str
    loaders: tuple[str, ...]
    matrix_sha256: str | None
    matrix: dict[str, Any]


def _load_matrix(matrix_path: Path) -> tuple[dict[str, Any], bytes]:
    try:
        with matrix_path.open("rb") as handle:
            payload = handle.read(MAX_MATRIX_BYTES + 1)
        if not payload or len(payload) > MAX_MATRIX_BYTES:
            raise EvidenceTargetError("release matrix exceeds its size limit")
        strict = json.loads(payload, object_pairs_hook=_unique_object,
                            parse_constant=_nonfinite, parse_float=_finite_float)
        matrix = load_matrix(matrix_path)
        with matrix_path.open("rb") as handle:
            unchanged = handle.read(MAX_MATRIX_BYTES + 1) == payload
        if strict != matrix or not unchanged:
            raise EvidenceTargetError("release matrix changed while it was being validated")
        return matrix, payload
    except (OSError, ValueError) as exc:
        if isinstance(exc, EvidenceTargetError):
            raise
        raise EvidenceTargetError(f"invalid canonical release matrix: {exc}") from exc


def _select_target(matrix: dict[str, Any], payload: bytes,
                   minecraft_target: str | None) -> EvidenceTarget:
    branch = matrix["project"]["release_branch"]
    if matrix["schema_version"] == 3:
        if minecraft_target is None:
            raise EvidenceTargetError("shared-source evidence requires an explicit Minecraft target")
        key = f"mc{minecraft_target}"
        bundle_version(key)
        try:
            selected = select_release_target(matrix, minecraft_target)
        except MatrixError as exc:
            raise EvidenceTargetError(str(exc)) from exc
        return EvidenceTarget(
            key, branch, minecraft_target,
            tuple(sorted({row["loader"] for row in selected["artifacts"]})),
            hashlib.sha256(payload).hexdigest(), selected,
        )
    if minecraft_target is not None:
        raise EvidenceTargetError("historical branch evidence does not accept a target override")
    parsed = parse_version_branch(branch)
    if parsed is None:
        raise EvidenceTargetError("historical evidence requires a release branch")
    return EvidenceTarget(branch, branch, parsed.version, parsed.loaders, None, matrix)


def load_target(
    matrix_path: Path,
    source_branch: str | None,
    *,
    minecraft_target: str | None = None,
) -> EvidenceTarget:
    """Validate the complete checkout-bound inventory before selecting a target view."""
    matrix, payload = _load_matrix(matrix_path)
    if source_branch is not None and matrix["project"]["release_branch"] != source_branch:
        raise EvidenceTargetError("release matrix disagrees with the evidence source branch")
    return _select_target(matrix, payload, minecraft_target)


def inventory(matrix_path: Path = DEFAULT_MATRIX) -> dict[str, list[dict[str, Any]]]:
    """Derive bounded producer/collector rows directly from the canonical matrix."""
    matrix, payload = _load_matrix(matrix_path)
    versions = sorted({row["artifact_version"] for row in matrix["artifacts"]},
                      key=lambda version: tuple(map(int, version.split("."))))
    if len(versions) > 64:
        raise EvidenceTargetError("public evidence inventory exceeds 64 targets")
    rows = []
    for version in versions:
        shared = matrix["schema_version"] == 3
        target = _select_target(matrix, payload, version if shared else None)
        rows.append({
            "bundle_key": target.key,
            "source_branch": target.branch,
            "minecraft_target": version if shared else "",
            "artifact_pattern": f"packaged-e2e-*--{version.replace('.', '_')}--pr-behavior",
            "raw_retention_days": 90 if version == matrix["unit_test_version"] else 1,
        })
    return {"include": rows}


def target_for_key(key: str, matrix_path: Path = DEFAULT_MATRIX) -> EvidenceTarget:
    """Resolve a new target key using the canonical shared-source matrix."""
    version = bundle_version(key)
    if TARGET_KEY.fullmatch(key) is None:
        raise EvidenceTargetError("a shared-source target key is required")
    return load_target(matrix_path, None, minecraft_target=version)



def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--kind", choices=("matrix", "keys", "source-branch"), default="matrix")
    args = parser.parse_args(argv)
    try:
        rows = inventory(args.matrix)
        if args.kind == "source-branch":
            branches = {row["source_branch"] for row in rows["include"]}
            if len(branches) != 1:
                raise EvidenceTargetError("evidence inventory requires one source branch")
            print(next(iter(branches)))
        else:
            result = [row["bundle_key"] for row in rows["include"]] if args.kind == "keys" else rows
            print(json.dumps(result, separators=(",", ":"), allow_nan=False))
        return 0
    except EvidenceTargetError as exc:
        parser.exit(2, f"public evidence target error: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
