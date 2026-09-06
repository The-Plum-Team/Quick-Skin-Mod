#!/usr/bin/env python3
"""Resolve source branches and legacy port work from the authoritative matrix layout."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Iterable
from pathlib import Path
from typing import Any

from matrix import MatrixError, load_matrix, validate_matrix
from version_branches import discover_version_branches


def source_mode(data: dict[str, Any]) -> str:
    validate_matrix(data)
    return "shared" if data["schema_version"] == 3 else "version-branches"


def source_branches(data: dict[str, Any], names: Iterable[str] = ()) -> list[str]:
    if source_mode(data) == "shared":
        return [data["project"]["release_branch"]]
    return discover_version_branches(names, exclude={"master"})


def version_port_targets(
    data: dict[str, Any], names: Iterable[str] = (), *, requested: str | None = None
) -> list[str]:
    if source_mode(data) == "shared":
        # Existing historical refs or delayed/manual recovery events cannot restart porting.
        return []
    branches = source_branches(data, names)
    if requested:
        if requested not in branches:
            raise MatrixError("requested version-port target is absent from discovered source branches")
        return [requested]
    return branches


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--kind", choices=("mode", "branches", "version-ports"), default="mode")
    parser.add_argument("--target", help="one historical port target; shared source has no port work")
    args = parser.parse_args(argv)
    try:
        if args.target and args.kind != "version-ports":
            raise MatrixError("--target requires --kind version-ports")
        data = load_matrix(args.matrix)
        mode = source_mode(data)
        if args.kind == "mode":
            print(mode)
        else:
            names = sys.stdin if mode == "version-branches" else ()
            branches = (source_branches(data, names) if args.kind == "branches" else
                        version_port_targets(data, names, requested=args.target))
            print(json.dumps(branches, separators=(",", ":")))
    except (MatrixError, OSError, ValueError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
