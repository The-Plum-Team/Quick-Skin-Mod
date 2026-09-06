#!/usr/bin/env python3
"""Render matrix-owned verification commands in the imported AI workflow guide."""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any

import matrix as release_matrix


COMMON_TEST_TASK = re.compile(r":common:[0-9]+(?:\.[0-9]+)+:test")
TARGET_ARGUMENT = r"(?:-PquickskinTarget=[0-9]+(?:\.[0-9]+)+[ \t]+)?"
COMMON_TEST_COMMAND = re.compile(TARGET_ARGUMENT + COMMON_TEST_TASK.pattern)
STABLE_TEST_COMMAND = re.compile(TARGET_ARGUMENT + r"\btestStableLane\b")
EXPECTED_TASK_OCCURRENCES = 2


class WorkflowGuidanceError(ValueError):
    """Raised when branch-owned guidance cannot be rendered exactly."""


def _mapping(value: object, *, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise WorkflowGuidanceError(f"{name} must be an object")
    return value


def _text(value: object, *, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise WorkflowGuidanceError(f"{name} must be a non-empty string")
    return value


def branch_version(
    data: Mapping[str, Any], *, profile_branch: str
) -> str:
    project = _mapping(data.get("project"), name="project")
    release_branch = _text(
        project.get("release_branch"), name="project.release_branch"
    )
    if profile_branch != "master" and profile_branch != release_branch:
        raise WorkflowGuidanceError(
            f"profile branch {profile_branch!r} does not match matrix release branch "
            f"{release_branch!r}"
        )
    artifacts = data.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise WorkflowGuidanceError("matrix must contain release artifacts")
    versions = {
        _text(
            _mapping(artifact, name="artifact").get("artifact_version"),
            name="artifact.artifact_version",
        )
        for artifact in artifacts
    }
    if data.get("schema_version") == 3:
        release_matrix.validate_matrix(dict(data))
        return _text(data.get("unit_test_version"), name="unit_test_version")
    if len(versions) != 1:
        raise WorkflowGuidanceError(
            f"workflow guidance requires one Minecraft version, found {sorted(versions)!r}"
        )
    return next(iter(versions))


def render_guidance(
    guidance: str,
    data: Mapping[str, Any],
    *,
    profile_branch: str,
) -> str:
    version = branch_version(data, profile_branch=profile_branch)
    matches = COMMON_TEST_TASK.findall(guidance)
    expected = 1 if "scripts/release/build_matrix.py" in guidance else EXPECTED_TASK_OCCURRENCES
    if len(matches) != expected or len(set(matches)) != 1:
        raise WorkflowGuidanceError(
            "workflow guide must contain exactly "
            + ("one common test task anchor" if expected == 1 else "two identical common test task anchors")
        )
    target = f"-PquickskinTarget={version} " if data.get("schema_version") == 3 else ""
    rendered = COMMON_TEST_COMMAND.sub(f"{target}:common:{version}:test", guidance)
    return STABLE_TEST_COMMAND.sub(f"{target}testStableLane", rendered)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix", type=Path, default=Path("release/release-matrix.json")
    )
    parser.add_argument(
        "--guidance", type=Path, default=Path("docs/ai/WORKFLOW.md")
    )
    parser.add_argument(
        "--profile-branch",
        required=True,
        help="master or the exact project.release_branch from the active matrix",
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)

    try:
        data = release_matrix.load_matrix(args.matrix)
        original = args.guidance.read_text(encoding="utf-8")
        rendered = render_guidance(
            original,
            data,
            profile_branch=args.profile_branch,
        )
    except (OSError, WorkflowGuidanceError, release_matrix.MatrixError) as exc:
        parser.error(str(exc))

    if args.check:
        if rendered != original:
            print(f"{args.guidance} workflow guidance is stale", file=sys.stderr)
            return 1
    elif rendered != original:
        args.guidance.write_text(rendered, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
