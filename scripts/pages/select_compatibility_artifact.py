#!/usr/bin/env python3
"""Select the newest authenticated compatibility handoff or rolling Pages cache."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "pages"))
sys.path.insert(0, str(REPO / "scripts" / "release"))

from rotate_artifacts import (  # noqa: E402
    PAGES_EVENTS,
    PAGES_WORKFLOW,
    REPOSITORY,
    Artifact,
    ArtifactApi,
    GitHubApi,
    RotationError,
    _validate_run,
)
from version_branches import parse_version_branch  # noqa: E402
from evidence_target import DEFAULT_MATRIX, EvidenceTargetError, target_for_key  # noqa: E402


COMPATIBILITY_REVIEW_WORKFLOW = ".github/workflows/mod-compatibility-review.yml"
COMPATIBILITY_REVIEW_EVENTS = frozenset(
    {"repository_dispatch", "schedule", "workflow_dispatch"}
)


def _newest_valid(
    api: ArtifactApi,
    artifacts: list[Artifact],
    *,
    repository: str,
    workflow: str,
    events: frozenset[str],
) -> Artifact | None:
    for artifact in sorted(artifacts, key=lambda item: item.order, reverse=True):
        try:
            _validate_run(
                api.get_run(artifact.run_id),
                repository=repository,
                workflow=workflow,
                branch="master",
                sha=artifact.head_sha,
                events=events,
                require_success=True,
            )
        except RotationError:
            continue
        return artifact
    return None


def select_source(
    api: ArtifactApi,
    *,
    repository: str,
    branch: str,
    bundle_key: str | None = None,
    current_sha: str | None = None,
) -> Artifact | None:
    if bundle_key is not None and current_sha is None:
        raise RotationError("shared compatibility evidence requires the exact source head")
    key = bundle_key if bundle_key is not None else branch
    handoff_name = f"pages-mod-compatibility-{key}"
    cache_name = f"pages-mod-compatibility-cache-{key}"
    if bundle_key is not None:
        cache_name += f"--{current_sha}"
    handoff = _newest_valid(
        api,
        [
            artifact
            for artifact in api.list_artifacts(handoff_name)
            if artifact.name == handoff_name
            and not artifact.expired
            and artifact.head_branch == "master"
            and (bundle_key is None or artifact.head_sha == current_sha)
        ],
        repository=repository,
        workflow=COMPATIBILITY_REVIEW_WORKFLOW,
        events=COMPATIBILITY_REVIEW_EVENTS,
    )
    cache = _newest_valid(
        api,
        [
            artifact
            for artifact in api.list_artifacts(cache_name)
            if artifact.name == cache_name
            and not artifact.expired
            and artifact.head_branch == "master"
        ],
        repository=repository,
        workflow=PAGES_WORKFLOW,
        events=PAGES_EVENTS,
    )
    candidates = [artifact for artifact in (handoff, cache) if artifact is not None]
    return max(candidates, key=lambda item: item.order) if candidates else None


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--bundle-key")
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--expected-source-sha")
    parser.add_argument("--github-output", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = args.repository.strip()
        if REPOSITORY.fullmatch(repository) is None:
            raise RotationError("repository must use the owner/name form")
        branch = args.branch.strip()
        if args.bundle_key is not None:
            target = target_for_key(args.bundle_key, args.matrix)
            if target.branch != branch:
                raise RotationError("bundle key disagrees with the canonical source branch")
        elif parse_version_branch(branch) is None:
            raise RotationError(f"not a release branch: {branch!r}")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise RotationError("GH_TOKEN is required")
        api = GitHubApi(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        current_sha = api.get_branch_sha(branch)
        if args.expected_source_sha is not None and current_sha != args.expected_source_sha:
            raise RotationError("source branch advanced after Pages discovery")
        selected = select_source(api, repository=repository, branch=branch,
                                 bundle_key=args.bundle_key, current_sha=current_sha)
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"available={'true' if selected is not None else 'false'}\n")
            output.write(f"sha={current_sha}\n")
            if selected is not None:
                output.write(f"artifact_id={selected.artifact_id}\n")
                output.write(f"name={selected.name}\n")
                output.write(f"run_id={selected.run_id}\n")
                output.write(f"size_in_bytes={selected.size_in_bytes}\n")
        return 0
    except (OSError, RotationError, EvidenceTargetError) as exc:
        print(f"compatibility evidence selection error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
