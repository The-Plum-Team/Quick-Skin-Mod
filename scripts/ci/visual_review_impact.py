#!/usr/bin/env python3
"""Fail-open classifier for replicated visual-review infrastructure ports."""

from __future__ import annotations

import argparse
import json
from pathlib import Path, PurePosixPath
from typing import Any


MAX_CHANGED_FILES = 100
SAFE_EXACT_PATHS = frozenset(
    {
        ".github/workflows/visual-review.yml",
        ".github/workflows/visual-review-drain.yml",
        "scripts/ci/visual_review_impact.py",
    }
)
SAFE_PREFIXES = ("docs/", "scripts/ci/tests/")
FILE_STATUSES = frozenset({"added", "modified", "removed", "renamed"})


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


def _safe_path(value: Any) -> bool:
    path = _canonical_path(value)
    if path is None:
        return False
    return path in SAFE_EXACT_PATHS or any(
        path.startswith(prefix) for prefix in SAFE_PREFIXES
    )


def infrastructure_only(payload: Any, *, changed_files: int) -> bool:
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
        if filename is None or filename in seen or not _safe_path(filename):
            return False
        seen.add(filename)
        previous = item.get("previous_filename")
        if item["status"] == "renamed" and previous is None:
            return False
        if previous is not None and not _safe_path(previous):
            return False
    return True


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--files", required=True, type=Path)
    parser.add_argument("--changed-files", required=True, type=int)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        payload = json.loads(args.files.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        payload = None
    result = infrastructure_only(payload, changed_files=args.changed_files)
    print("skip" if result else "review")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
