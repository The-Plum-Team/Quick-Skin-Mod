#!/usr/bin/env python3
"""Fail-closed classifier for changes that cannot affect ordinary visual review."""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


MAX_CHANGED_FILES = 100
POLICY_VERSION = 1
SCOPES = frozenset({"replicated-port", "source-pr"})
REPLICATED_SAFE_EXACT_PATHS = frozenset(
    {
        ".github/workflows/handle-version-port-result.yml",
        ".github/workflows/mod-compatibility-e2e.yml",
        ".github/workflows/mod-compatibility-review.yml",
        ".github/workflows/sync-version-branches.yml",
        ".github/workflows/visual-review.yml",
        ".github/workflows/visual-review-drain.yml",
        ".github/workflows/pages.yml",
        "scripts/ci/github_api_retry.sh",
        "scripts/ci/e2e_impact.py",
        "scripts/ci/mod_compatibility_review_queue.py",
        "scripts/ci/visual_nonimpact_certification.py",
        "scripts/ci/visual_review_queue.py",
        "scripts/pages/rotate_artifacts.py",
        "scripts/pages/select_artifact.py",
        "scripts/release/tests/test_pages_artifact_rotation.py",
        "scripts/ci/visual_review_impact.py",
    }
)
SOURCE_PR_SAFE_EXACT_PATHS = frozenset(
    {
        ".github/workflows/mod-compatibility-e2e.yml",
        ".github/workflows/mod-compatibility-review.yml",
        "scripts/ci/mod_compatibility_review_queue.py",
    }
)
SAFE_PREFIXES = ("docs/", "scripts/ci/tests/", "scripts/release/tests/")
FILE_STATUSES = frozenset({"added", "modified", "removed", "renamed"})


@dataclass(frozen=True)
class Classification:
    review_required: bool
    scope: str
    paths: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": POLICY_VERSION,
            "scope": self.scope,
            "review_required": self.review_required,
            "paths": list(self.paths),
        }


def _canonical_path(value: Any) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    if "\\" in value or any(
        ord(character) < 32 or ord(character) == 127 for character in value
    ):
        return None
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        return None
    if path.as_posix() != value:
        return None
    return value


def _safe_path(value: Any, *, scope: str) -> bool:
    path = _canonical_path(value)
    if path is None:
        return False
    if scope not in SCOPES:
        return False
    exact_paths = (
        REPLICATED_SAFE_EXACT_PATHS
        if scope == "replicated-port"
        else SOURCE_PR_SAFE_EXACT_PATHS
    )
    return path in exact_paths or any(
        path.startswith(prefix) for prefix in SAFE_PREFIXES
    )


def infrastructure_only(
    payload: Any, *, changed_files: int, scope: str = "replicated-port"
) -> bool:
    """Return true only for a complete bounded inventory of protected non-product paths."""

    if type(changed_files) is not int or not 1 <= changed_files <= MAX_CHANGED_FILES:
        return False
    if not isinstance(payload, list) or not payload:
        return False

    files: list[Any] = payload
    if len(files) != changed_files:
        return False

    seen: set[str] = set()
    for item in files:
        if not isinstance(item, dict) or item.get("status") not in FILE_STATUSES:
            return False
        filename = _canonical_path(item.get("filename"))
        if (
            filename is None
            or filename in seen
            or not _safe_path(filename, scope=scope)
        ):
            return False
        seen.add(filename)
        previous = item.get("previous_filename")
        if item["status"] == "renamed" and previous is None:
            return False
        if previous is not None and not _safe_path(previous, scope=scope):
            return False
    return True


def classify_paths(paths: list[str], *, scope: str) -> Classification:
    """Classify an exact Git diff where renames were expanded to delete/add paths."""

    if scope not in SCOPES:
        raise ValueError("visual review impact scope is invalid")
    canonical_values = [_canonical_path(path) for path in paths]
    if (
        not canonical_values
        or len(canonical_values) > MAX_CHANGED_FILES
        or any(path is None for path in canonical_values)
    ):
        return Classification(True, scope, tuple())
    normalized = tuple(sorted({path for path in canonical_values if path is not None}))
    return Classification(
        any(not _safe_path(path, scope=scope) for path in normalized),
        scope,
        normalized,
    )


def git_diff_paths(repository: Path, base: str, head: str) -> list[str]:
    if not base or not head:
        raise ValueError("both base and head are required")
    try:
        completed = subprocess.run(
            [
                "git",
                "diff",
                "--no-renames",
                "--name-only",
                "-z",
                "--diff-filter=ACDMRTUXB",
                base,
                head,
                "--",
            ],
            cwd=repository,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = getattr(exc, "stderr", b"").decode(
            "utf-8", errors="replace"
        ).strip()
        raise ValueError(f"cannot resolve exact visual diff: {detail or exc}") from exc
    raw = completed.stdout.split(b"\0")
    if raw and raw[-1] == b"":
        raw.pop()
    try:
        return [path.decode("utf-8", errors="strict") for path in raw]
    except UnicodeDecodeError as exc:
        raise ValueError("visual diff contains a non-UTF-8 path") from exc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--files", type=Path)
    source.add_argument("--repository", type=Path)
    parser.add_argument("--changed-files", type=int)
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--scope", choices=sorted(SCOPES), default="replicated-port")
    parser.add_argument("--manifest-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.files is not None:
        if args.changed_files is None or args.base is not None or args.head is not None:
            raise SystemExit("--files requires --changed-files and forbids --base/--head")
        try:
            payload = json.loads(args.files.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            payload = None
        skip = infrastructure_only(
            payload, changed_files=args.changed_files, scope=args.scope
        )
        paths = tuple(
            sorted(
                item["filename"]
                for item in payload
                if isinstance(payload, list)
                and isinstance(item, dict)
                and isinstance(item.get("filename"), str)
            )
        ) if isinstance(payload, list) else tuple()
        result = Classification(not skip, args.scope, paths if skip else tuple())
    else:
        if args.changed_files is not None or args.base is None or args.head is None:
            raise SystemExit("--repository requires --base and --head")
        try:
            result = classify_paths(
                git_diff_paths(args.repository.resolve(), args.base, args.head),
                scope=args.scope,
            )
        except (OSError, ValueError) as exc:
            print(f"Visual review impact classification failed: {exc}")
            return 2
    if args.manifest_output is not None:
        args.manifest_output.write_text(
            json.dumps(result.manifest(), sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    print("review" if result.review_required else "skip")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
