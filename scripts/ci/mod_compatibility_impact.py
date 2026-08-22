#!/usr/bin/env python3
"""Fail-closed classifier for diffs that need optional-mod compatibility E2E.

The allowlist contains only review, publication, documentation, and test-policy paths
that cannot change the packaged game or an optional-mod integration. Product, build,
runtime-harness, compatibility-policy, malformed, renamed-from-unknown, and otherwise
unknown paths require the compatibility wave.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


MAX_CHANGED_FILES = 100
POLICY_VERSION = 1
FILE_STATUSES = frozenset({"added", "modified", "removed", "renamed"})

SAFE_EXACT_PATHS = frozenset(
    {
        ".github/pull_request_template.md",
        ".github/workflows/handle-version-port-result.yml",
        ".github/workflows/pages.yml",
        ".github/workflows/prune-actions-caches.yml",
        ".github/workflows/refresh-release-status.yml",
        ".github/workflows/release.yml",
        ".github/workflows/sync-version-branches.yml",
        ".github/workflows/visual-review-drain.yml",
        ".github/workflows/visual-review.yml",
        "CONTRIBUTING.md",
        "DEPENDENCY-SECURITY.md",
        "README.md",
        "RELEASING.md",
        "VERSION-BRANCHES.md",
        "e2e/README.md",
        "e2e/check_visual_review.py",
        "e2e/visual_review.py",
        "e2e/visual_review_cache.py",
        "e2e/visual_similarity.py",
        "e2e/visual_review_prompt.md",
        "e2e/visual_review_runner.py",
        "e2e/visual_review_semantic_prompt.md",
        "e2e/visual_review_semantic_verify_prompt.md",
        "e2e/visual_review_verify_prompt.md",
        "e2e/visual_review_workflow.js",
        "scripts/ci/bounded_zip.py",
        "scripts/ci/claude_capacity_gate.py",
        "scripts/ci/claude_capacity_probe.py",
        "scripts/ci/e2e_impact.py",
        "scripts/ci/e2e_job_graph.py",
        "scripts/ci/github_api_retry.sh",
        "scripts/ci/mod_compatibility_impact.py",
        "scripts/ci/visual_anchor_certification.py",
        "scripts/ci/visual_nonimpact_certification.py",
        "scripts/ci/visual_review_impact.py",
        "scripts/ci/visual_review_queue.py",
    }
)
SAFE_PREFIXES = (
    "docs/",
    "scripts/ci/tests/",
    "scripts/pages/",
    "scripts/release/tests/",
    "site/",
)


class ImpactError(ValueError):
    """Raised when an exact diff cannot be read safely."""


@dataclass(frozen=True)
class Classification:
    compatibility_required: bool
    paths: tuple[str, ...]
    impact_paths: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": POLICY_VERSION,
            "compatibility_required": self.compatibility_required,
            "paths": list(self.paths),
            "impact_paths": list(self.impact_paths),
        }


def normalize_path(raw: Any) -> str:
    if (
        not isinstance(raw, str)
        or not raw
        or "\x00" in raw
        or "\n" in raw
        or "\r" in raw
        or "\\" in raw
    ):
        raise ImpactError("diff paths must be non-empty canonical strings")
    path = PurePosixPath(raw)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ImpactError(f"diff path is not repository-relative: {raw!r}")
    if path.as_posix() != raw:
        raise ImpactError(f"diff path is not canonical: {raw!r}")
    return raw


def is_safe_path(path: str) -> bool:
    return path in SAFE_EXACT_PATHS or any(
        path.startswith(prefix) for prefix in SAFE_PREFIXES
    )


def classify_paths(paths: Iterable[str]) -> Classification:
    normalized = tuple(sorted({normalize_path(path) for path in paths}))
    if not normalized or len(normalized) > MAX_CHANGED_FILES * 2:
        raise ImpactError("an empty or oversized diff cannot skip compatibility E2E")
    impact_paths = tuple(path for path in normalized if not is_safe_path(path))
    return Classification(bool(impact_paths), normalized, impact_paths)


def classify_inventory(payload: Any, *, changed_files: int) -> Classification:
    if type(changed_files) is not int or not 1 <= changed_files <= MAX_CHANGED_FILES:
        raise ImpactError("changed_files is outside the protected bound")
    if not isinstance(payload, list) or len(payload) != changed_files:
        raise ImpactError("file inventory is incomplete")
    paths: list[str] = []
    seen_filenames: set[str] = set()
    for item in payload:
        if not isinstance(item, dict) or item.get("status") not in FILE_STATUSES:
            raise ImpactError("file inventory entry is malformed")
        filename = normalize_path(item.get("filename"))
        if filename in seen_filenames:
            raise ImpactError("file inventory contains duplicate filenames")
        seen_filenames.add(filename)
        paths.append(filename)
        previous = item.get("previous_filename")
        if item["status"] == "renamed" and previous is None:
            raise ImpactError("renamed entry has no previous filename")
        if previous is not None:
            paths.append(normalize_path(previous))
    return classify_paths(paths)


def git_diff_paths(repository: Path, base: str, head: str) -> list[str]:
    if not base or not head:
        raise ImpactError("both base and head are required")
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
        raise ImpactError(
            f"cannot resolve exact compatibility diff: {detail or exc}"
        ) from exc
    raw = completed.stdout.split(b"\0")
    if raw and raw[-1] == b"":
        raw.pop()
    try:
        return [path.decode("utf-8", errors="strict") for path in raw]
    except UnicodeDecodeError as exc:
        raise ImpactError("compatibility diff contains a non-UTF-8 path") from exc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--files", type=Path)
    source.add_argument("--repository", type=Path)
    parser.add_argument("--changed-files", type=int)
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--manifest-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.files is not None:
            if (
                args.changed_files is None
                or args.base is not None
                or args.head is not None
            ):
                raise ImpactError(
                    "--files requires --changed-files and forbids --base/--head"
                )
            payload = json.loads(args.files.read_text(encoding="utf-8"))
            result = classify_inventory(payload, changed_files=args.changed_files)
        else:
            if args.changed_files is not None or args.base is None or args.head is None:
                raise ImpactError("--repository requires --base and --head")
            result = classify_paths(
                git_diff_paths(args.repository.resolve(), args.base, args.head)
            )
    except (ImpactError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print(f"Mod compatibility impact classification failed: {exc}")
        return 2
    if args.manifest_output is not None:
        args.manifest_output.write_text(
            json.dumps(result.manifest(), sort_keys=True, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    print("required" if result.compatibility_required else "skip")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
