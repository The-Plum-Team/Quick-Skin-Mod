#!/usr/bin/env python3
"""Decide whether the current master generation lost its shared-source visual-review wake.

Packaged E2E's advisory ``notify-shared-review`` job sends the only explicit
``visual-review-requested`` wake for a canonical master generation, and visual-review.yml's
``workflow_run`` path is its second chance. Both are lost when GitHub cannot assign runners for
long enough. This read-only sweep answers one question for the executing master commit: should
the identical wake be sent again? It reads GitHub with the bounded GET helper only, never
downloads an artifact and never dispatches; a separate job holding only ``contents: write`` sends
the wake after rechecking the live head.

The answer is ``dispatch`` only when all of these hold, and the decision is otherwise a no-op:

* ``master`` still points at the executing commit (the caller also requires shared source mode);
* no Packaged E2E ``workflow_dispatch`` on ``master`` for that commit is still running;
* a completed one is canonical: its exact attempt's required gate succeeded and its own wake
  job's condition held (a ``workflow_dispatch`` on ``refs/heads/master`` without
  ``attest_run_id`` after a successful gate), whatever advisory tail failed afterwards;
* it completed less than ``MAX_AGE`` ago, well inside the seven-day retention of its packaged
  runtime evidence and the one-day retention of its feature selection and prepared capsules;
* no AI visual review run of this master generation is queued or running (a ``repository_dispatch``
  wake, or a ``workflow_run`` review whose run name names one of its source runs; a review that
  a pull request's Packaged E2E started also reports this commit and never defers the wake);
* no review capsule or report owned by the protected review workflows exists for any canonical
  generation of the commit (the same names the curator's coalescing checks); and
* fewer than ``MAX_WAKES`` review dispatches were already delivered for the commit, so a review
  that keeps failing visibly is not restarted every hour.

A wake is a hint, never evidence: visual-review.yml authenticates the source run, its required
gate and every artifact from scratch, and skips targets that already own a capsule or report.
"""
from __future__ import annotations

import argparse
import sys
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import feature_coverage_github as publisher
from visual_review_queue import QueueError, parse_artifact, valid_owner

coverage = publisher.coverage
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
REVIEW_WORKFLOW = ".github/workflows/visual-review.yml"
GATE_JOB = "Packaged E2E gate"
WAKE_JOB = "Wake shared-source visual review (advisory)"
ACTIVE = frozenset({"requested", "waiting", "pending", "queued", "in_progress"})
# The wake job ran or was cancelled waiting for a runner: GitHub evaluated its condition as true.
# "skipped" means a failed gate, an attestation run or another branch/event.
WAKE_EVALUATED = frozenset({"success", "failure", "cancelled", "timed_out"})
REVIEW_EVENTS = frozenset({"repository_dispatch", "workflow_run"})
OWNER_CONCLUSIONS = frozenset({"success", "failure"})
# Packaged runtime evidence is kept seven days, but the generation's e2e-feature-selection and the
# drain's prepared capsules only one; leave room for curation and review after the last wake.
MAX_AGE = timedelta(hours=20)
MAX_WAKES = 3
MAX_RUNS_PER_PAGE = 100
MAX_RUN_PAGES = 10


@dataclass(frozen=True)
class Decision:
    reason: str
    source_run_id: int | None = None

    @property
    def dispatch(self) -> bool:
        return self.reason == "dispatch"


class Api(publisher.Api):
    def runs(self, workflow: str, source_sha: str) -> list[dict[str, Any]]:
        """Every run of one protected workflow for one exact commit, bounded and identity-checked."""
        if workflow not in {SOURCE_WORKFLOW, REVIEW_WORKFLOW}:
            raise coverage.CoverageError("wake recovery reads only its exact workflows")
        if not isinstance(source_sha, str) or coverage.admission.SHA.fullmatch(source_sha) is None:
            raise coverage.CoverageError("wake recovery requires an exact commit")
        runs: list[dict[str, Any]] = []
        seen: set[int] = set()
        total = None
        for page in range(1, MAX_RUN_PAGES + 1):
            query = urllib.parse.urlencode({"head_sha": source_sha, "per_page": MAX_RUNS_PER_PAGE,
                                            "page": page})
            value = self.json(f"actions/workflows/{workflow.rsplit('/', 1)[1]}/runs?{query}")
            if (not isinstance(value, dict) or type(value.get("total_count")) is not int
                    or not 0 <= value["total_count"] <= MAX_RUNS_PER_PAGE * MAX_RUN_PAGES
                    or total is not None and value["total_count"] != total
                    or not isinstance(value.get("workflow_runs"), list)
                    or len(value["workflow_runs"]) > MAX_RUNS_PER_PAGE):
                raise coverage.CoverageError("workflow run inventory is incomplete or over budget")
            total = value["total_count"]
            for run in value["workflow_runs"]:
                validate_run(run, repository=self.repository, source_sha=source_sha, workflow=workflow)
                if run["id"] in seen:
                    raise coverage.CoverageError("workflow run inventory repeats an execution")
                seen.add(run["id"])
                runs.append(run)
            if len(runs) == total:
                return runs
            if len(value["workflow_runs"]) < MAX_RUNS_PER_PAGE or len(runs) > total:
                raise coverage.CoverageError("workflow run inventory is truncated")
        raise coverage.CoverageError("workflow run inventory pagination exceeds its limit")


def validate_run(run: Any, *, repository: str, source_sha: str, workflow: str) -> None:
    if (not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0
            or type(run.get("run_attempt")) is not int or not 1 <= run["run_attempt"] <= 100
            or run.get("path") != workflow or run.get("head_sha") != source_sha
            or not isinstance(run.get("head_branch"), str) or not isinstance(run.get("event"), str)
            or not isinstance(run.get("head_repository"), dict)
            or run["head_repository"].get("full_name") != repository
            or run.get("status") not in ACTIVE | {"completed"}):
        raise coverage.CoverageError("wake recovery read a foreign or malformed workflow run")


def review_title(source_run_id: int) -> str:
    """visual-review.yml's run name: the only run field that names a workflow_run review's source.

    GitHub reports every workflow_run review on the default branch head whatever its source, so a
    review that a pull request's Packaged E2E started carries the current master commit as well.
    """
    return f"AI visual review for source run {source_run_id}"


def generation_reviews(reviews: list[dict[str, Any]], sources: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The review runs of this master generation only.

    Every ``repository_dispatch`` review is a master generation wake (the producer's, or this
    recovery's); a ``workflow_run`` review counts only when its run name names one of the
    generation's own source runs, so a busy pull-request stream cannot defer the recovery.
    """
    titles = {review_title(run["id"]) for run in sources}
    return [run for run in reviews if run["head_branch"] == "master"
            and (run["event"] == "repository_dispatch"
                 or run["event"] == "workflow_run" and run.get("display_title") in titles)]


def _named(jobs: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    matches = [job for job in jobs if job.get("name") == name]
    if len(matches) > 1:
        raise coverage.CoverageError(f"source run repeats its job {name!r}")
    return matches[0] if matches else None


def canonical_generation(run: dict[str, Any], pages: Any) -> bool:
    """The exact rules of the producer's wake job, read from the run's own attempt."""
    if (run.get("event") != "workflow_dispatch" or run.get("head_branch") != "master"
            or not coverage.settled_source_run(run, gate_settled=True)):
        return False
    if not isinstance(pages, list) or not all(isinstance(page, dict) and isinstance(page.get("jobs"), list)
                                              for page in pages):
        raise coverage.CoverageError("source job inventory is malformed")
    jobs = [job for page in pages for job in page["jobs"]]
    if not all(isinstance(job, dict) for job in jobs):
        raise coverage.CoverageError("source job inventory is malformed")
    gate, wake = _named(jobs, GATE_JOB), _named(jobs, WAKE_JOB)
    return bool(gate is not None and gate.get("status") == "completed" and gate.get("conclusion") == "success"
                and wake is not None and wake.get("status") == "completed"
                and wake.get("conclusion") in WAKE_EVALUATED)


def review_names(source_run_id: int, source_sha: str) -> list[tuple[str, str, frozenset[str]]]:
    """Capsule and report names of one generation, with their protected owners.

    Per-target names come first (the shared curator's names, checked by its coalescing plan);
    the untargeted names visual-review.yml's own duplicate guard queries follow.
    """
    names = []
    for row in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]:
        key = row["bundle_key"]
        names.append((f"visual-review-input-{source_run_id}-{source_sha}--{key}", REVIEW_WORKFLOW,
                      REVIEW_EVENTS))
        names.append((f"visual-review-{source_run_id}--{key}", coverage.DRAIN_WORKFLOW,
                      coverage.DRAIN_EVENTS))
    return names + [(f"visual-review-input-{source_run_id}-{source_sha}", REVIEW_WORKFLOW, REVIEW_EVENTS),
                    (f"visual-review-input-{source_run_id}", REVIEW_WORKFLOW, REVIEW_EVENTS),
                    (f"visual-review-{source_run_id}", coverage.DRAIN_WORKFLOW, coverage.DRAIN_EVENTS)]


def review_exists(api: Api, source_run_id: int, source_sha: str) -> bool:
    """Whether a protected owner already queued or reviewed this generation (any target)."""
    for name, workflow, events in review_names(source_run_id, source_sha):
        for item in api.artifacts(name=name):
            try:
                artifact = parse_artifact(item)
            except QueueError as exc:
                raise coverage.CoverageError("review artifact metadata is malformed") from exc
            if artifact.expired or artifact.head_sha != source_sha or artifact.head_branch != "master":
                continue
            owner = api.run(artifact.run_id)
            if valid_owner(owner, repository=api.repository, artifact=artifact, workflow=workflow,
                           events=events, conclusions=OWNER_CONCLUSIONS, allow_in_progress=True):
                return True
    return False


def _timestamp(value: Any) -> datetime:
    if not isinstance(value, str):
        raise coverage.CoverageError("source run has no completion time")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise coverage.CoverageError("source run has a malformed completion time") from exc
    if parsed.tzinfo is None:
        raise coverage.CoverageError("source run completion time has no timezone")
    return parsed


def decide(api: Api, *, source_sha: str, now: datetime) -> Decision:
    if api.current_sha() != source_sha:
        return Decision("stale-generation")
    sources = [run for run in api.runs(SOURCE_WORKFLOW, source_sha)
               if run["event"] == "workflow_dispatch" and run["head_branch"] == "master"]
    if any(run["status"] != "completed" for run in sources):
        # A running generation owns its wake; it may still be waiting for a runner.
        return Decision("source-active")
    canonical = [run for run in sorted(sources, key=lambda item: item["id"], reverse=True)
                 if canonical_generation(run, api.jobs(run))]
    if not canonical:
        return Decision("no-canonical-generation")
    newest = canonical[0]
    if now - _timestamp(newest.get("updated_at")) > MAX_AGE:
        return Decision("generation-expired")
    reviews = generation_reviews(api.runs(REVIEW_WORKFLOW, source_sha), sources)
    if any(run["status"] != "completed" for run in reviews):
        # Never race a review that may already own this generation; the next sweep rechecks.
        return Decision("review-active")
    if any(review_exists(api, run["id"], source_sha) for run in canonical):
        return Decision("review-exists")
    if sum(run["event"] == "repository_dispatch" for run in reviews) >= MAX_WAKES:
        return Decision("wake-budget-exhausted")
    return Decision("dispatch", newest["id"])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        if coverage.admission.SHA.fullmatch(args.source_sha) is None:
            raise coverage.CoverageError("wake recovery requires an exact commit")
        decision = decide(Api(args.github_repository), source_sha=args.source_sha,
                          now=datetime.now(timezone.utc))
    except (coverage.CoverageError, OSError, ValueError) as exc:
        print(f"visual review wake recovery failed: {str(exc)[:300]}", file=sys.stderr)
        return 1
    lines = [f"dispatch={'true' if decision.dispatch else 'false'}", f"reason={decision.reason}"]
    if decision.dispatch:
        lines += [f"source_run_id={decision.source_run_id}", f"source_sha={args.source_sha}"]
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as stream:
            stream.write("".join(line + "\n" for line in lines))
    if decision.dispatch:
        print(f"Source run {decision.source_run_id} has no visual review; its wake will be re-sent.")
    else:
        print(f"No visual review wake needed: {decision.reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
