#!/usr/bin/env python3
"""Derive and validate one immutable release identity from the authoritative matrix."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from matrix import MatrixError, load_matrix, read_mod_version, release_id as matrix_release_id, select_release_target


class ReleaseIdentityError(RuntimeError):
    pass


@dataclass(frozen=True)
class ReleaseIdentity:
    release_id: str
    tag: str
    branch: str
    mod_version: str
    minecraft_versions: tuple[str, ...]

    def manifest(self) -> dict[str, Any]:
        result = asdict(self)
        result["minecraft_versions"] = list(self.minecraft_versions)
        return result


def version_key(value: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in value.split("."))
    except ValueError as exc:
        raise ReleaseIdentityError(f"non-numeric Minecraft version {value!r}") from exc


def validate_changelog(
    path: Path, mod_version: str, *, publication: bool = False
) -> str:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise ReleaseIdentityError(f"cannot read changelog {path}") from exc
    heading_pattern = re.compile(r"^##\s+(\S+)(?:\s+\(([^)]+)\))?\s*$")
    current_index = -1
    marker = ""
    for index, line in enumerate(lines):
        match = heading_pattern.fullmatch(line)
        if match:
            current_index = index
            heading_version = match.group(1)
            marker = (match.group(2) or "").strip()
            if heading_version != mod_version:
                raise ReleaseIdentityError(
                    f"latest changelog version {heading_version!r} does not equal {mod_version!r}"
                )
            break
    if current_index < 0:
        raise ReleaseIdentityError("changelog has no release heading")
    end = next(
        (index for index in range(current_index + 1, len(lines)) if lines[index].startswith("## ")),
        len(lines),
    )
    section = lines[current_index + 1:end]
    if not any(line.startswith("### ") for line in section) or not any(
        line.startswith("- ") for line in section
    ):
        raise ReleaseIdentityError("current changelog release section is empty")
    if publication and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", marker):
        raise ReleaseIdentityError(
            "tag publication requires an ISO date in the current changelog heading"
        )
    return marker


def derive(matrix_path: Path, data: dict[str, Any] | None = None, *, target: str | None = None) -> ReleaseIdentity:
    loaded = load_matrix(matrix_path) if data is None else data
    if target is not None:
        loaded = select_release_target(loaded, target)
    mod_version = read_mod_version(matrix_path, loaded)
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", mod_version):
        raise ReleaseIdentityError(f"unsafe mod_version {mod_version!r}")
    versions = tuple(sorted(
        {str(row["artifact_version"]) for row in loaded["artifacts"]},
        key=version_key,
    ))
    if not versions:
        raise ReleaseIdentityError("release matrix has no Minecraft versions")
    release_id = matrix_release_id(loaded, mod_version, target=target)
    return ReleaseIdentity(
        release_id=release_id,
        tag=release_id,
        branch=str(loaded["project"]["release_branch"]),
        mod_version=mod_version,
        minecraft_versions=versions,
    )


def git_commit(repository: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=repository,
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ReleaseIdentityError("cannot resolve the checked-out commit") from exc


def validate_ci_event(
    identity: ReleaseIdentity,
    *,
    event_name: str,
    ref_type: str,
    ref_name: str,
    event_commit: str,
    checkout_commit: str,
    release_branch_head: str,
) -> None:
    if identity.release_id.startswith("build-"):
        raise ReleaseIdentityError("a unified build bundle cannot be published; select one Minecraft target")
    if not re.fullmatch(r"[0-9a-f]{40}", event_commit):
        raise ReleaseIdentityError("GITHUB_SHA must be a full lowercase commit ID")
    if checkout_commit != event_commit:
        raise ReleaseIdentityError("checked-out commit does not equal GITHUB_SHA")
    if release_branch_head != event_commit:
        raise ReleaseIdentityError(
            f"release commit is not the exact head of {identity.branch}"
        )
    if event_name == "push":
        if ref_type != "tag" or ref_name != identity.tag:
            raise ReleaseIdentityError(
                f"release tag must be exactly {identity.tag}"
            )
    elif event_name == "workflow_dispatch":
        if ref_type != "branch" or ref_name != identity.branch:
            raise ReleaseIdentityError(
                f"manual releases must run from {identity.branch}"
            )
    else:
        raise ReleaseIdentityError(f"unsupported release event {event_name!r}")


def write_github_output(path: Path, identity: ReleaseIdentity) -> None:
    with path.open("a", encoding="utf-8") as output:
        for key, value in (
            ("release_id", identity.release_id),
            ("tag", identity.tag),
            ("branch", identity.branch),
            ("version", identity.mod_version),
            ("minecraft_versions", ",".join(identity.minecraft_versions)),
        ):
            output.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--validate-ci", action="store_true")
    parser.add_argument("--release-branch-head")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--target", help="one Minecraft version published from the unified matrix")
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[2]
    matrix_path = args.matrix if args.matrix.is_absolute() else repository / args.matrix
    try:
        identity = derive(matrix_path, target=args.target)
        changelog = repository / "CHANGELOG.md"
        validate_changelog(changelog, identity.mod_version)
        if args.validate_ci:
            validate_ci_event(
                identity,
                event_name=os.environ.get("GITHUB_EVENT_NAME", ""),
                ref_type=os.environ.get("GITHUB_REF_TYPE", ""),
                ref_name=os.environ.get("GITHUB_REF_NAME", ""),
                event_commit=os.environ.get("GITHUB_SHA", ""),
                checkout_commit=git_commit(repository),
                release_branch_head=args.release_branch_head or "",
            )
            if os.environ.get("GITHUB_EVENT_NAME", "") == "push":
                validate_changelog(
                    changelog, identity.mod_version, publication=True
                )
        if args.github_output:
            write_github_output(args.github_output, identity)
        print(json.dumps(identity.manifest(), separators=(",", ":"), sort_keys=True))
    except (MatrixError, OSError, ReleaseIdentityError) as exc:
        print(f"release identity error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
