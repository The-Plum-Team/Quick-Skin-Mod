#!/usr/bin/env python3
"""Select the newest authenticated Pages evidence source for one release branch."""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "release"))

from rotate_artifacts import (  # noqa: E402
    E2E_WORKFLOW,
    PAGES_EVENTS,
    PAGES_WORKFLOW,
    REPOSITORY,
    Artifact,
    ArtifactApi,
    ApiError,
    GitHubApi,
    RotationError,
    _validate_run,
)
from version_branches import parse_version_branch  # noqa: E402


# Probe callers distinguish "no current-head evidence" (defer and wait for the next
# attestation wake) from a genuine selection error, which keeps the ordinary exit code 2.
PROBE_NO_EVIDENCE_EXIT = 3

# A release branch collects only a handful of synchronization commits between two packaged
# runs, so a short walk covers every realistic continuation while keeping the cost to one
# bounded commit page plus exact-name artifact lookups instead of an inventory scan.
MAX_CONTINUATION_COMMITS = 20


def _newest_valid(
    api: ArtifactApi,
    artifacts: list[Artifact],
    *,
    repository: str,
    workflow: str,
    branch: str,
    sha_from_artifact: bool,
    sha: str,
    events: frozenset[str],
) -> Artifact | None:
    for artifact in sorted(artifacts, key=lambda item: item.order, reverse=True):
        run = api.get_run(artifact.run_id)
        try:
            _validate_run(
                run,
                repository=repository,
                workflow=workflow,
                branch=branch,
                sha=artifact.head_sha if sha_from_artifact else sha,
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
    current_sha: str,
    require_raw: bool = False,
) -> Artifact:
    handoff_name = f"pages-e2e-{branch}"
    cache_name = f"pages-cache-{branch}--{current_sha}"
    legacy_name = f"pages-cache-{branch}"

    handoff = _newest_valid(
        api,
        [
            artifact
            for artifact in api.list_artifacts(handoff_name)
            if artifact.name == handoff_name
            and not artifact.expired
            and artifact.head_branch == branch
            and artifact.head_sha == current_sha
        ],
        repository=repository,
        workflow=E2E_WORKFLOW,
        branch=branch,
        sha_from_artifact=False,
        sha=current_sha,
        events=frozenset({"workflow_dispatch"}),
    )
    if require_raw:
        if handoff is None:
            raise RotationError(
                f"no authenticated lossless current-head evidence exists for {branch}"
            )
        return handoff
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
        branch="master",
        sha_from_artifact=True,
        sha=current_sha,
        events=PAGES_EVENTS,
    )
    exact = [candidate for candidate in (handoff, cache) if candidate is not None]
    if exact:
        return max(exact, key=lambda item: item.order)

    legacy = _newest_valid(
        api,
        [
            artifact
            for artifact in api.list_artifacts(legacy_name)
            if artifact.name == legacy_name
            and not artifact.expired
            and artifact.head_branch == "master"
        ],
        repository=repository,
        workflow=PAGES_WORKFLOW,
        branch="master",
        sha_from_artifact=True,
        sha=current_sha,
        events=PAGES_EVENTS,
    )
    if legacy is None:
        raise RotationError(f"no authenticated current evidence exists for {branch}")
    return legacy


@dataclass(frozen=True)
class Evidence:
    """One authenticated bundle plus the exact release-branch head it was written for."""

    artifact: Artifact
    sha: str


def resolve_evidence(
    api: ArtifactApi,
    *,
    repository: str,
    branch: str,
    current_sha: str,
    require_raw: bool = False,
    allow_continuation: bool = False,
) -> Evidence:
    """Select current-head evidence, or the newest earlier head still on this lineage.

    Exact current-head evidence always wins. A continuation only nominates an ancestor that
    owns an authenticated bundle; proving that the range between the two heads cannot change
    a pixel stays with the caller, which recomputes it from Git before publishing anything.
    The AI oracle path requires an exact lossless handoff and never continues.
    """

    try:
        return Evidence(
            select_source(
                api,
                repository=repository,
                branch=branch,
                current_sha=current_sha,
                require_raw=require_raw,
            ),
            current_sha,
        )
    except RotationError:
        if require_raw or not allow_continuation:
            raise
    commits = api.list_branch_commits(branch, MAX_CONTINUATION_COMMITS)
    if not commits or commits[0] != current_sha:
        # The branch moved while this selection was running. Refuse rather than nominate an
        # ancestor of a head that is already historical.
        raise RotationError(f"no authenticated current evidence exists for {branch}")
    for candidate in commits[1:]:
        try:
            selected = select_source(
                api,
                repository=repository,
                branch=branch,
                current_sha=candidate,
            )
        except RotationError:
            continue
        return Evidence(selected, candidate)
    raise RotationError(f"no authenticated current evidence exists for {branch}")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument(
        "--probe",
        action="store_true",
        help="answer via exit status whether authenticated current-head evidence exists",
    )
    parser.add_argument(
        "--require-raw",
        action="store_true",
        help="select only a lossless pages-e2e handoff and never a compact cache",
    )
    parser.add_argument(
        "--allow-continuation",
        action="store_true",
        help="nominate an earlier head whose evidence the caller may still carry forward",
    )
    args = parser.parse_args(argv)
    if not args.probe and args.github_output is None:
        parser.error("--github-output is required unless --probe is used")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = args.repository.strip()
        if not REPOSITORY.fullmatch(repository):
            raise RotationError("repository must use the owner/name form")
        branch = args.branch.strip()
        if parse_version_branch(branch) is None:
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
        if args.probe:
            # The probe authenticates exactly like a selection but downloads nothing and
            # reports a missing source as a distinct clean outcome for defer decisions.
            try:
                evidence = resolve_evidence(
                    api,
                    repository=repository,
                    branch=branch,
                    current_sha=current_sha,
                    require_raw=args.require_raw,
                    allow_continuation=args.allow_continuation,
                )
            except ApiError:
                # Infrastructure failure is not evidence absence. Keep it visible so the
                # protected caller retries instead of waiting for a wake that may never arrive.
                raise
            except RotationError as exc:
                print(f"Pages evidence probe: {exc}", file=sys.stderr)
                return PROBE_NO_EVIDENCE_EXIT
            print(
                f"Pages evidence probe: {evidence.artifact.name} covers {branch} "
                f"at {evidence.sha}"
            )
            return 0
        evidence = resolve_evidence(
            api,
            repository=repository,
            branch=branch,
            current_sha=current_sha,
            require_raw=args.require_raw,
            allow_continuation=args.allow_continuation,
        )
        selected = evidence.artifact
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"artifact_id={selected.artifact_id}\n")
            output.write(f"name={selected.name}\n")
            output.write(f"run_id={selected.run_id}\n")
            output.write(f"sha={evidence.sha}\n")
            output.write(f"head_sha={current_sha}\n")
            output.write(f"size_in_bytes={selected.size_in_bytes}\n")
        return 0
    except (OSError, RotationError) as exc:
        print(f"Pages evidence selection error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
