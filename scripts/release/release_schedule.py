#!/usr/bin/env python3
"""Assign matrix-derived release preparation to bounded, isolated runner slots."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

from matrix import MatrixError, load_matrix, select_release_target
from release_identity import ReleaseIdentityError, resolve_event_target, version_key

ROOT = Path(__file__).resolve().parents[2]
PREPARATION_SLOTS = 4


def preparation_slot(matrix: dict[str, Any], target: str) -> int:
    if matrix.get("schema_version") != 3:
        raise MatrixError("parallel release preparation requires the shared-source matrix")
    select_release_target(matrix, target)
    targets = sorted({row["artifact_version"] for row in matrix["artifacts"]}, key=version_key)
    return targets.index(target) % PREPARATION_SLOTS


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", default="")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        path = ROOT / "release/release-matrix.json"
        matrix = load_matrix(path)
        target = resolve_event_target(
            path, data=matrix, event_name=os.environ.get("GITHUB_EVENT_NAME", ""),
            ref_type=os.environ.get("GITHUB_REF_TYPE", ""),
            ref_name=os.environ.get("GITHUB_REF_NAME", ""), requested_target=args.target or None,
        )
        if target is None:
            raise MatrixError("release preparation requires an explicit matrix target")
        result = {"target": target, "slot": preparation_slot(matrix, target)}
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key, value in result.items():
                    output.write(f"{key}={value}\n")
        print(json.dumps(result, sort_keys=True))
        return 0
    except (MatrixError, ReleaseIdentityError, OSError, ValueError) as exc:
        parser.exit(1, f"release scheduling failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
