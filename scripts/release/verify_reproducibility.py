#!/usr/bin/env python3
"""Compare a clean second Gradle build with the exact first-build release manifest."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from matrix import MatrixError, load_matrix, read_mod_version, select_release_target
from release_identity import ReleaseIdentityError, derive as derive_release_identity
from verify_release import VerificationError, resolve_template, sha256


class ReproducibilityError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReproducibilityError(message)


def compare_rebuild(
    repository: Path,
    matrix_path: Path,
    manifest: dict[str, Any],
    data: dict[str, Any] | None = None,
    *,
    target: str | None = None,
) -> list[dict[str, str]]:
    matrix = load_matrix(matrix_path) if data is None else data
    if target is not None:
        matrix = select_release_target(matrix, target)
    mod_version = read_mod_version(matrix_path, matrix)
    require(manifest.get("schema_version") == 2, "unsupported artifact manifest schema")
    if matrix.get("schema_version") == 3:
        identity = derive_release_identity(matrix_path, matrix, target=target)
        require(manifest.get("release") == identity.manifest(), "artifact manifest release scope disagrees")
        require(manifest.get("matrix_sha256") == sha256(matrix_path), "artifact manifest matrix bytes disagree")
    records = manifest.get("artifacts")
    require(isinstance(records, list), "artifact manifest has no artifact records")
    by_node = {
        str(record.get("artifact_node")): record
        for record in records
        if isinstance(record, dict)
    }
    require(
        len(by_node) == len(records) == matrix["lane_count"],
        "artifact manifest node inventory is incomplete",
    )

    comparisons: list[dict[str, str]] = []
    for artifact in matrix["artifacts"]:
        node = artifact["artifact_node"]
        record = by_node.get(node)
        require(record is not None, f"first build omitted {node}")
        harness_record = record.get("harness")
        require(isinstance(harness_record, dict), f"first build omitted {node} harness")
        paths = (
            (
                "production",
                repository / resolve_template(artifact["jar"], mod_version),
                record.get("sha256"),
            ),
            (
                "harness",
                repository / resolve_template(artifact["harness_jar"], mod_version),
                harness_record.get("sha256"),
            ),
        )
        for kind, path, expected in paths:
            require(path.is_file(), f"second build omitted {node} {kind}: {path}")
            require(
                isinstance(expected, str) and len(expected) == 64,
                f"first build has no SHA-256 for {node} {kind}",
            )
            actual = sha256(path)
            require(
                actual == expected,
                f"non-reproducible {node} {kind}: first={expected}, second={actual}",
            )
            comparisons.append(
                {
                    "artifact_node": node,
                    "kind": kind,
                    "sha256": actual,
                }
            )
    return comparisons


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--manifest", type=Path, default=Path("build/release/artifacts.json"))
    parser.add_argument("--target", help="compare one independently staged Minecraft release target")
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    matrix_path = args.matrix if args.matrix.is_absolute() else repository / args.matrix
    manifest_path = args.manifest if args.manifest.is_absolute() else repository / args.manifest
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        require(isinstance(manifest, dict), "artifact manifest root must be an object")
        comparisons = compare_rebuild(repository, matrix_path, manifest, target=args.target)
    except (
        json.JSONDecodeError,
        MatrixError,
        ReleaseIdentityError,
        OSError,
        ReproducibilityError,
        VerificationError,
    ) as exc:
        print(f"reproducibility error: {exc}", file=sys.stderr)
        return 2
    print(
        json.dumps(
            {"verified_outputs": comparisons},
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
