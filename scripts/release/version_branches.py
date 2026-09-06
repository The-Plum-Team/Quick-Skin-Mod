#!/usr/bin/env python3
"""Recognize historical release-branch names without maintaining a version inventory.

The release matrix inside each branch remains authoritative for its Minecraft
lanes. This parser remains for historical evidence and branch-layout recovery. Shared-source
consumers use release_sources.py and the complete matrix; old refs do not declare active targets.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Iterable
from dataclasses import dataclass


VERSION_BRANCH = re.compile(
    r"^(?P<loaders>[a-z0-9]+(?:-and-[a-z0-9]+)*)-"
    r"(?P<version>[0-9]+(?:\.[0-9]+)+)$"
)


@dataclass(frozen=True)
class VersionBranch:
    name: str
    loaders: tuple[str, ...]
    version: str

    @property
    def version_key(self) -> tuple[int, ...]:
        return tuple(int(part) for part in self.version.split("."))


def parse_version_branch(name: str) -> VersionBranch | None:
    match = VERSION_BRANCH.fullmatch(name)
    if match is None:
        return None
    return VersionBranch(
        name=name,
        loaders=tuple(match.group("loaders").split("-and-")),
        version=match.group("version"),
    )


def is_version_branch(name: str) -> bool:
    return parse_version_branch(name) is not None


def discover_version_branches(
    names: Iterable[str], *, exclude: Iterable[str] = ()
) -> list[str]:
    excluded = set(exclude)
    return sorted(
        {
            name.strip()
            for name in names
            if name.strip()
            and name.strip() not in excluded
            and is_version_branch(name.strip())
        }
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--exclude",
        action="append",
        default=[],
        help="branch to omit; may be supplied more than once",
    )
    parser.add_argument(
        "--target",
        help="validate and emit one explicitly requested release branch",
    )
    args = parser.parse_args(argv)

    if args.target:
        if not is_version_branch(args.target) or args.target in args.exclude:
            parser.error(f"not a version branch: {args.target}")
        branches = [args.target]
    else:
        branches = discover_version_branches(sys.stdin, exclude=args.exclude)
    print(json.dumps(branches, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
