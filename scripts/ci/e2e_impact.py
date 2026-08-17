#!/usr/bin/env python3
"""Classify whether a version-port diff can safely skip packaged Minecraft.

The policy is deliberately an allowlist. Unknown, malformed, runtime-facing, build, matrix,
harness, oracle, and self-policy paths all require the full packaged E2E gate. Non-gate
workflow files are exact-listed as non-runtime: a change to a workflow that neither executes
the packaged gates nor supplies their composite action cannot alter packaged-runtime
evidence. The gate and controller surfaces — on-demand-e2e.yml, build-gate.yml,
verify-gate-attestation.yml, and everything under .github/actions/ — remain runtime-required.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Iterable


POLICY_VERSION = 1

ROOT_DOCUMENTS = frozenset(
    {
        "README.md",
        "CONTRIBUTING.md",
        "DEPENDENCY-SECURITY.md",
        "RELEASING.md",
        "VERSION-BRANCHES.md",
    }
)

EXACT_NON_RUNTIME_PATHS = frozenset(
    {
        ".github/pull_request_template.md",
        "e2e/README.md",
        "scripts/ci/ai_patch_policy.py",
        "scripts/ci/gradle_cache_policy.py",
        "scripts/ci/prune_actions_caches.py",
        "scripts/ci/github_api_retry.sh",
        "scripts/ci/mod_compatibility_review_queue.py",
        "scripts/ci/mod_compatibility_impact.py",
        "scripts/ci/visual_review_queue.py",
        "scripts/pages/build_site.py",
        "scripts/pages/rotate_artifacts.py",
        "scripts/pages/select_artifact.py",
        "scripts/release/branch_readme.py",
        "scripts/release/status_table.py",
        "scripts/release/version_branches.py",
    }
)

EXACT_NON_RUNTIME_WORKFLOWS = frozenset(
    {
        ".github/workflows/handle-version-port-result.yml",
        ".github/workflows/mod-compatibility-e2e.yml",
        ".github/workflows/mod-compatibility-review.yml",
        ".github/workflows/pages.yml",
        ".github/workflows/prune-actions-caches.yml",
        ".github/workflows/refresh-release-status.yml",
        ".github/workflows/release.yml",
        ".github/workflows/sync-version-branches.yml",
        ".github/workflows/visual-review.yml",
        ".github/workflows/visual-review-drain.yml",
    }
)

DOCUMENTATION_PREFIX = "docs/"

DOCUMENTATION_ASSET_PREFIX = "docs/assets/"

UNRESTRICTED_NON_RUNTIME_PREFIXES = ("site/",)

EXACT_NON_RUNTIME_TESTS = frozenset(
    {
        "scripts/ci/tests/test_ai_patch_policy.py",
        "scripts/ci/tests/test_gradle_cache_policy.py",
        "scripts/ci/tests/test_mod_compatibility_impact.py",
        "scripts/ci/tests/test_prune_actions_caches.py",
        "scripts/ci/tests/test_visual_review_impact.py",
        "scripts/ci/tests/test_visual_review_queue.py",
        "scripts/ci/tests/test_workflow_security.py",
        "scripts/release/tests/test_branch_readme.py",
        "scripts/release/tests/test_pages_artifact_rotation.py",
        "scripts/release/tests/test_pages_site.py",
        "scripts/release/tests/test_repository_guidance.py",
        "scripts/release/tests/test_status_table.py",
        "scripts/release/tests/test_version_branches.py",
    }
)


class ImpactError(ValueError):
    """Raised when a diff cannot be classified safely."""


@dataclass(frozen=True)
class Classification:
    runtime_required: bool
    paths: tuple[str, ...]
    runtime_paths: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": POLICY_VERSION,
            "runtime_required": self.runtime_required,
            "paths": list(self.paths),
            "runtime_paths": list(self.runtime_paths),
        }


def normalize_path(raw: str) -> str:
    if not isinstance(raw, str) or not raw or "\x00" in raw or "\n" in raw or "\r" in raw:
        raise ImpactError("diff paths must be non-empty single-line strings")
    if "\\" in raw:
        raise ImpactError(f"diff path must use repository separators: {raw!r}")
    path = PurePosixPath(raw)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise ImpactError(f"diff path is not repository-relative: {raw!r}")
    normalized = path.as_posix()
    if normalized != raw:
        raise ImpactError(f"diff path is not canonical: {raw!r}")
    return normalized


def is_non_runtime_path(path: str) -> bool:
    if path in ROOT_DOCUMENTS or path in EXACT_NON_RUNTIME_PATHS:
        return True
    if path in EXACT_NON_RUNTIME_TESTS or path in EXACT_NON_RUNTIME_WORKFLOWS:
        return True
    if path.startswith(DOCUMENTATION_PREFIX):
        return path.endswith(".md") or path.startswith(DOCUMENTATION_ASSET_PREFIX)
    return any(path.startswith(prefix) for prefix in UNRESTRICTED_NON_RUNTIME_PREFIXES)


def classify(paths: Iterable[str]) -> Classification:
    normalized = tuple(sorted({normalize_path(path) for path in paths}))
    if not normalized:
        raise ImpactError("an empty diff cannot skip packaged E2E")
    runtime_paths = tuple(path for path in normalized if not is_non_runtime_path(path))
    return Classification(bool(runtime_paths), normalized, runtime_paths)


def git_diff_paths(repository: Path, base: str, head: str) -> list[str]:
    if not base or not head:
        raise ImpactError("both --base and --head are required")
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
        detail = getattr(exc, "stderr", b"").decode("utf-8", errors="replace").strip()
        raise ImpactError(f"cannot resolve exact diff: {detail or exc}") from exc
    raw = completed.stdout.split(b"\0")
    if raw and raw[-1] == b"":
        raw.pop()
    try:
        return [path.decode("utf-8", errors="strict") for path in raw]
    except UnicodeDecodeError as exc:
        raise ImpactError("diff contains a non-UTF-8 path") from exc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--github-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = classify(git_diff_paths(args.repository.resolve(), args.base, args.head))
    except ImpactError as exc:
        print(f"E2E impact classification failed: {exc}")
        return 2
    manifest = result.manifest()
    print(json.dumps(manifest, sort_keys=True, separators=(",", ":")))
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"runtime_required={'true' if result.runtime_required else 'false'}\n")
            output.write("manifest=" + json.dumps(manifest, separators=(",", ":")) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
