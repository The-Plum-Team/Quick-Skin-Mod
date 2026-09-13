#!/usr/bin/env python3
"""Bound Pages fan-out by authenticated, current-generation coverage progress.

This is only a cost-admission controller. It never certifies pixels, substitutes for the
collectors/runtime fan-in, publishes a site, deletes an artifact or dispatches another run.
The successful Pages owner's existing immutable caches are the durable progress ledger.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/pages"))

from feature_coverage_github import Api  # noqa: E402
from feature_coverage import CoverageError  # noqa: E402
from evidence_target import DEFAULT_MATRIX, EvidenceTargetError, inventory, validate_handoffs  # noqa: E402

COALESCE_SECONDS = 10 * 60
PARTIAL_DEADLINE_SECONDS = 45 * 60
RECOVERY_INTERVAL_SECONDS = 60 * 60
MAX_REQUESTS = 160
MAX_CANDIDATES = 8
MAX_FAILED_PUBLICATIONS = 3
PAGES = ".github/workflows/pages.yml"
COMPATIBILITY = ".github/workflows/mod-compatibility-review.yml"
E2E = ".github/workflows/on-demand-e2e.yml"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")


def timestamp(value: Any) -> float:
    if not isinstance(value, str) or not re.fullmatch(
            r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z", value):
        raise CoverageError("progress metadata has an invalid UTC timestamp")
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    except ValueError as exc:
        raise CoverageError("progress metadata has an invalid UTC timestamp") from exc


def positive(value: Any) -> int:
    if type(value) is not int or value <= 0:
        raise CoverageError("progress metadata has an invalid immutable identity")
    return value


@dataclass(frozen=True)
class Decision:
    eligible: bool
    reason: str
    ready: int
    published: int
    next_check_at: float | None = None


def decide(*, expected: set[str], published: dict[str, float], ready: dict[str, float],
           ordinary_ready: bool, published_at: float | None, now: float,
           publisher_available: bool = True, failed_publications: int = 0,
           ordinary_changed: bool = False) -> Decision:
    """Pure deterministic policy; callers supply independently authenticated metadata."""
    if (not expected or len(expected) > 64 or not set(published) <= expected
            or not set(ready) <= expected or not set(published) <= set(ready)
            or not math.isfinite(now) or now < 0
            or type(failed_publications) is not int or failed_publications < 0
            or any(not math.isfinite(value) or value < 0 or value > now
                   for value in [*published.values(), *ready.values()])
            or published_at is not None and (not math.isfinite(published_at)
                                            or not 0 <= published_at <= now)):
        raise CoverageError("invalid authenticated publication progress")
    base = dict(ready=len(ready), published=len(published))
    if not ordinary_ready:
        return Decision(False, "ordinary-handoffs-pending", **base)
    dirty = {key: value for key, value in ready.items()
             if key not in published or value > published[key]}
    if published_at is not None and not dirty and not ordinary_changed:
        return Decision(False, "complete" if set(published) == expected else "unchanged", **base)
    if not publisher_available:
        return Decision(False, "publisher-active", **base, next_check_at=now)
    if failed_publications >= MAX_FAILED_PUBLICATIONS:
        return Decision(False, "publication-recovery-budget-exhausted", **base)
    if published_at is None:
        return Decision(True, "initial-ordinary", **base)
    if ordinary_changed:
        return Decision(True, "ordinary-replacement", **base)
    if set(ready) == expected:
        return Decision(True, "final-complete", **base)
    oldest = min(dirty.values())
    deadline = oldest + PARTIAL_DEADLINE_SECONDS
    milestone = len(published) < math.ceil(len(expected) / 2) <= len(ready)
    coalesced = oldest + COALESCE_SECONDS
    if now >= deadline:
        return Decision(True, "partial-deadline", **base)
    if milestone and now >= coalesced:
        return Decision(True, "half-coverage", **base)
    return Decision(False, "coalescing", **base,
                    next_check_at=min(deadline, coalesced) if milestone else deadline)


class ProgressApi(Api):
    """One bounded read-only snapshot, with operation counts instead of quota arithmetic."""
    def __init__(self, repository: str):
        super().__init__(repository)
        self.operations: dict[str, int] = {}

    def json(self, endpoint: str) -> Any:
        if sum(self.operations.values()) >= MAX_REQUESTS:
            raise CoverageError("publication progress exhausted its bounded GET budget")
        category = ("current-head" if endpoint == "branches/master" else
                    "exact-artifact" if re.fullmatch(r"actions/artifacts/\d+", endpoint) else
                    "artifact-inventory" if "artifacts?" in endpoint else
                    "exact-attempt-jobs" if "/jobs?" in endpoint else
                    "workflow-runs" if "/workflows/" in endpoint else "owner-run")
        self.operations[category] = self.operations.get(category, 0) + 1
        return super().json(endpoint)

    def named(self, name: str) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode({"name": name, "per_page": 100})
        result = self.json("actions/artifacts?" + query)
        rows = bounded_rows(result, "artifacts")
        if any(row.get("name") != name for row in rows):
            raise CoverageError("progress lookup returned a foreign artifact name")
        return rows

    def workflow_runs(self, workflow: str, sha: str, status: str) -> list[dict[str, Any]]:
        query = urllib.parse.urlencode({"head_sha": sha, "status": status, "per_page": 100})
        return bounded_rows(self.json(f"actions/workflows/{Path(workflow).name}/runs?{query}"),
                            "workflow_runs")


def bounded_rows(record: Any, field: str) -> list[dict[str, Any]]:
    if (not isinstance(record, dict) or type(record.get("total_count")) is not int
            or not 0 <= record["total_count"] <= 100 or not isinstance(record.get(field), list)
            or record["total_count"] != len(record[field])
            or any(not isinstance(row, dict) for row in record[field])):
        raise CoverageError("publication progress inventory is malformed, truncated or oversized")
    identifiers = [positive(row.get("id")) for row in record[field]]
    if len(set(identifiers)) != len(identifiers):
        raise CoverageError("publication progress inventory contains duplicate identities")
    return record[field]


def owner_valid(run: dict[str, Any], *, repository: str, sha: str, workflow: str,
                success: bool = True) -> bool:
    positive(run.get("id"))
    positive(run.get("run_attempt"))
    return bool(run.get("head_sha") == sha and run.get("head_branch") == "master"
                and run.get("path") == workflow and run.get("status") == "completed"
                and (not success or run.get("conclusion") == "success")
                and run.get("event") in {"workflow_dispatch", "repository_dispatch", "schedule"}
                and isinstance(run.get("head_repository"), dict)
                and run["head_repository"].get("full_name") == repository)


def flat_jobs(api: Api, run: dict[str, Any]) -> list[dict[str, Any]]:
    return [job for page in api.jobs(run) for job in page["jobs"]]


def successful(jobs: list[dict[str, Any]], name: str) -> dict[str, Any] | None:
    matches = [job for job in jobs if job.get("name") == name]
    if len(matches) != 1:
        return None
    job = matches[0]
    return job if job.get("status") == "completed" and job.get("conclusion") == "success" else None


def selection_boundary(job: dict[str, Any], *, ordinary: bool = False) -> float:
    name = ("Select the target artifact for the exact source commit" if ordinary else
            "Select the newest authenticated compatibility generation")
    steps = [step for step in job.get("steps", [])
             if step.get("name") == name]
    if (len(steps) != 1 or steps[0].get("status") != "completed"
            or steps[0].get("conclusion") != "success"):
        raise CoverageError("published compatibility lacks its exact selection boundary")
    return timestamp(steps[0].get("started_at"))


def artifact_valid(artifact: dict[str, Any], run: dict[str, Any], *, name: str,
                   job: dict[str, Any] | None, upload_step: str) -> bool:
    """Bind every immutable artifact to the successful upload in the exact current attempt."""
    positive(artifact.get("id"))
    owner = artifact.get("workflow_run")
    if (artifact.get("name") != name or artifact.get("expired") is not False
            or type(artifact.get("size_in_bytes")) is not int
            or not 0 < artifact["size_in_bytes"] <= 1024 * 1024 * 1024
            or not isinstance(artifact.get("digest"), str)
            or DIGEST.fullmatch(artifact["digest"]) is None
            or not isinstance(owner, dict) or owner.get("id") != run["id"]
            or owner.get("head_sha") != run["head_sha"] or owner.get("head_branch") != "master"
            or job is None):
        return False
    steps = [step for step in job.get("steps", []) if step.get("name") == upload_step]
    if len(steps) != 1 or steps[0].get("status") != "completed" or steps[0].get("conclusion") != "success":
        return False
    created = timestamp(artifact.get("created_at"))
    return timestamp(steps[0].get("started_at")) <= created <= timestamp(steps[0].get("completed_at"))


def published_snapshot(api: ProgressApi, *, sha: str, keys: set[str], anchor: str
                       ) -> tuple[dict[str, float], float | None, int | None, float | None]:
    """Reuse only the newest exact successful atomic owner, never a union of sibling runs."""
    name = f"pages-cache-{anchor}--{sha}"
    candidates = sorted(api.named(name), key=lambda row: positive(row.get("id")), reverse=True)
    for candidate in candidates[:MAX_CANDIDATES]:
        if candidate.get("expired") is not False:
            continue
        owner = candidate.get("workflow_run")
        if not isinstance(owner, dict) or owner.get("head_sha") != sha or owner.get("head_branch") != "master":
            continue
        run = api.run(positive(owner.get("id")))
        if not owner_valid(run, repository=api.repository, sha=sha, workflow=PAGES):
            continue
        jobs = flat_jobs(api, run)
        required = {"Build atomic static site", "Deploy GitHub Pages"}
        required.update(f"Collect {key}" for key in keys)
        required.update(f"Refresh evidence cache for {key}" for key in keys)
        required.update(f"Collect compatibility {key}" for key in keys)
        required.update(f"Refresh compatibility cache for {key}" for key in keys)
        if any(successful(jobs, required_name) is None for required_name in required):
            continue
        artifacts = api.artifacts(run_id=run["id"])
        exact = {row["name"]: row for row in artifacts}
        if len(exact) != len(artifacts):
            raise CoverageError("successful Pages owner contains duplicate artifact names")
        ordinary = []
        for key in keys:
            cache = exact.get(f"pages-cache-{key}--{sha}")
            ordinary.append(cache is not None and artifact_valid(cache, run,
                name=f"pages-cache-{key}--{sha}", job=successful(jobs, f"Refresh evidence cache for {key}"),
                upload_step="Roll the protected evidence cache forward"))
        if not all(ordinary) or exact.get(name, {}).get("id") != candidate["id"]:
            continue
        published = {}
        for key in keys:
            cache = exact.get(f"pages-mod-compatibility-cache-{key}--{sha}")
            if cache is not None:
                if not artifact_valid(cache, run, name=f"pages-mod-compatibility-cache-{key}--{sha}",
                        job=successful(jobs, f"Refresh compatibility cache for {key}"),
                        upload_step="Roll the protected compatibility cache forward"):
                    raise CoverageError("successful Pages compatibility cache has another attempt")
                # An upload timestamp cannot prove what an earlier collector observed. An
                # owner completing during collection may have been missed even though its
                # handoff predates this cache. Re-publish conservatively from that boundary.
                published[key] = selection_boundary(successful(jobs, f"Collect compatibility {key}"))
        ordinary_boundary = min(selection_boundary(successful(jobs, f"Collect {key}"), ordinary=True)
                                for key in keys)
        return published, timestamp(run.get("updated_at")), run["id"], ordinary_boundary
    return {}, None, None, None


def ordinary_snapshot(api: ProgressApi, *, sha: str, matrix: Path, keys: set[str],
                      consumed_before: float | None) -> tuple[list[dict[str, Any]], float | None]:
    for candidate in api.workflow_runs(E2E, sha, "success")[:MAX_CANDIDATES]:
        if consumed_before is not None and timestamp(candidate.get("updated_at")) < consumed_before:
            continue
        run = api.run(positive(candidate.get("id")))
        if not owner_valid(run, repository=api.repository, sha=sha, workflow=E2E):
            continue
        artifacts = api.artifacts(run_id=run["id"])
        handoffs = [row for row in artifacts if row["name"].startswith("pages-e2e-")]
        if len(handoffs) != len(keys):
            continue
        validate_handoffs([{"artifacts": handoffs}], matrix_path=matrix,
                          source_branch="master", source_sha=sha, source_run_id=run["id"])
        jobs = flat_jobs(api, run)
        if all(artifact_valid(artifact, run, name=artifact["name"],
                         job=successful(jobs, "Prepare public evidence for " + artifact["name"].removeprefix("pages-e2e-") + " (advisory)"),
                         upload_step="Upload stable public evidence for this Minecraft target")
               for artifact in handoffs):
            return [{"key": artifact["name"].removeprefix("pages-e2e-"), "artifact_id": artifact["id"],
                     "digest": artifact["digest"], "run_id": run["id"], "run_attempt": run["run_attempt"]}
                    for artifact in handoffs], timestamp(run["updated_at"])
    return [], None


def ready_snapshot(api: ProgressApi, *, sha: str, keys: set[str], published: dict[str, float]
                   ) -> tuple[dict[str, float], list[dict[str, Any]]]:
    ready = dict(published)
    identities = []
    owners: dict[int, tuple[dict[str, Any], list[dict[str, Any]]]] = {}
    for key in sorted(keys):
        name = f"pages-mod-compatibility-{key}"
        for artifact in sorted(api.named(name), key=lambda row: positive(row.get("id")), reverse=True)[:MAX_CANDIDATES]:
            if artifact.get("expired") is not False:
                continue
            owner = artifact.get("workflow_run")
            if not isinstance(owner, dict) or owner.get("head_sha") != sha or owner.get("head_branch") != "master":
                continue
            run_id = positive(owner.get("id"))
            if run_id not in owners:
                run = api.run(run_id)
                if not owner_valid(run, repository=api.repository, sha=sha, workflow=COMPATIBILITY):
                    continue
                owners[run_id] = run, flat_jobs(api, run)
            run, jobs = owners[run_id]
            if not artifact_valid(artifact, run, name=name,
                    job=successful(jobs, "Publish compact compatibility evidence"),
                    upload_step="Upload the compact public compatibility handoff"):
                continue
            # The metadata inventory is already immutable-ID addressed; recheck that exact ID
            # after owner admission so a stale/rerun/deleted nomination cannot suppress work.
            if api.artifact(artifact["id"]) != artifact:
                raise CoverageError("compatibility handoff changed during progress authentication")
            created = max(timestamp(artifact["created_at"]), timestamp(run.get("updated_at")))
            ready[key] = max(ready.get(key, 0), created)
            identities.append({"key": key, "artifact_id": artifact["id"], "digest": artifact["digest"],
                               "run_id": run_id, "run_attempt": run["run_attempt"]})
            break
    return ready, identities


def preferred_handoff(api: ProgressApi, *, identifier: int, name: str, sha: str,
                      ordinary: bool = False) -> dict[str, Any]:
    """A scheduler ID is a nomination, not evidence; the collector independently admits it."""
    positive(identifier)
    if api.current_sha() != sha:
        raise CoverageError("source advanced before preferred compatibility collection")
    artifact = api.artifact(identifier)
    owner = artifact.get("workflow_run")
    if not isinstance(owner, dict):
        raise CoverageError("preferred compatibility handoff has no owner")
    run = api.run(positive(owner.get("id")))
    workflow = E2E if ordinary else COMPATIBILITY
    if not owner_valid(run, repository=api.repository, sha=sha, workflow=workflow):
        raise CoverageError("preferred compatibility handoff has another source or owner")
    job_name = ("Prepare public evidence for " + name.removeprefix("pages-e2e-") + " (advisory)"
                if ordinary else "Publish compact compatibility evidence")
    upload = ("Upload stable public evidence for this Minecraft target" if ordinary else
              "Upload the compact public compatibility handoff")
    if not artifact_valid(artifact, run, name=name,
            job=successful(flat_jobs(api, run), job_name), upload_step=upload):
        raise CoverageError("preferred compatibility handoff has another immutable upload/attempt")
    if api.artifact(identifier) != artifact or api.current_sha() != sha:
        raise CoverageError("preferred compatibility handoff or source changed during admission")
    return artifact


def failed_publications(api: ProgressApi, *, sha: str, since: float) -> int:
    count = 0
    for status in ("failure", "cancelled"):
        candidates = api.workflow_runs(PAGES, sha, status)
        for candidate in candidates[:MAX_CANDIDATES]:
            if timestamp(candidate.get("created_at")) < since:
                continue
            run = api.run(positive(candidate.get("id")))
            if not owner_valid(run, repository=api.repository, sha=sha, workflow=PAGES, success=False):
                continue
            jobs = flat_jobs(api, run)
            # Failed/foreign wake authentication and rotation are never publication attempts.
            if any(job.get("name") == "Build atomic static site" and job.get("started_at")
                   and job.get("conclusion") != "skipped" for job in jobs):
                count += 1
                if count >= MAX_FAILED_PUBLICATIONS:
                    return count
    return count


def plan(api: ProgressApi, *, sha: str, matrix: Path, now: float,
         check_complete: bool = False) -> dict[str, Any]:
    if SHA.fullmatch(sha) is None:
        raise CoverageError("publication progress requires an exact source SHA")
    rows = inventory(matrix)["include"]
    if {row["source_branch"] for row in rows} != {"master"}:
        raise CoverageError("coverage progress requires the shared source matrix")
    keys = {row["bundle_key"] for row in rows}
    anchors = [row["bundle_key"] for row in rows if row["raw_retention_days"] == 90]
    if len(anchors) != 1:
        raise CoverageError("publication progress requires one matrix-derived raw anchor")
    if api.current_sha() != sha:
        return {"eligible": False, "reason": "source-advanced", "source_sha": sha}
    published, published_at, owner, ordinary_boundary = published_snapshot(api, sha=sha, keys=keys, anchor=anchors[0])
    if set(published) == keys and not check_complete:
        # A single bounded current-source owner query detects a lost same-head replacement wake,
        # including a producer that began before collection but completed during the build.
        owners = api.workflow_runs(COMPATIBILITY, sha, "success")
        check_complete = any(owner_valid(run, repository=api.repository, sha=sha, workflow=COMPATIBILITY)
                             and timestamp(run.get("updated_at")) >= min(published.values())
                             for run in owners)
    if set(published) == keys and not check_complete:
        # No per-target handoff scans or self-dispatch survives an unchanged complete publication.
        ready, identities = dict(published), []
    else:
        ready, identities = ready_snapshot(api, sha=sha, keys=keys, published=published)
    ordinary_handoffs, ordinary_at = ordinary_snapshot(api, sha=sha, matrix=matrix, keys=keys,
                                                      consumed_before=ordinary_boundary)
    ordinary = published_at is not None or bool(ordinary_handoffs)
    ordinary_changed = published_at is not None and bool(ordinary_handoffs)
    result = decide(expected=keys, published=published, ready=ready, ordinary_ready=ordinary,
                    published_at=published_at, now=now, ordinary_changed=ordinary_changed)
    failed = 0
    if result.eligible:
        since = max([published_at or 0, ordinary_at or 0, *ready.values()])
        failed = failed_publications(api, sha=sha, since=since)
        result = decide(expected=keys, published=published, ready=ready, ordinary_ready=ordinary,
                        published_at=published_at, now=now, failed_publications=failed,
                        ordinary_changed=ordinary_changed)
    if api.current_sha() != sha:
        return {"eligible": False, "reason": "source-advanced", "source_sha": sha}
    return {"schema_version": 1, "source_sha": sha, "observed_at": now,
            **result.__dict__, "expected": len(keys), "published_owner": owner,
            "handoffs": identities, "failed_publications": failed,
            "ordinary_handoffs": ordinary_handoffs,
            "api_operations": dict(api.operations), "api_operation_limit": MAX_REQUESTS,
            "coalesce_seconds": COALESCE_SECONDS, "partial_deadline_seconds": PARTIAL_DEADLINE_SECONDS,
            "recovery_interval_seconds": RECOVERY_INTERVAL_SECONDS}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--github-output", type=Path, required=True)
    parser.add_argument("--check-complete", action="store_true")
    args = parser.parse_args()
    api = ProgressApi(args.repository)
    try:
        result = plan(api, sha=args.source_sha, matrix=args.matrix,
                      now=datetime.now(timezone.utc).timestamp(), check_complete=args.check_complete)
        print(json.dumps(result, sort_keys=True, separators=(",", ":")))
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"eligible={str(result['eligible']).lower()}\n")
            output.write(f"reason={result['reason']}\n")
            handoffs = {row["key"]: str(row["artifact_id"]) for row in result.get("handoffs", [])}
            output.write("compatibility_handoffs=" + json.dumps(handoffs, sort_keys=True,
                                                                separators=(",", ":")) + "\n")
            ordinary = {row["key"]: str(row["artifact_id"]) for row in result.get("ordinary_handoffs", [])}
            output.write("ordinary_handoffs=" + json.dumps(ordinary, sort_keys=True,
                                                          separators=(",", ":")) + "\n")
        return 0
    except (OSError, CoverageError, EvidenceTargetError) as exc:
        print(f"publication progress failed closed: {exc}; GET operations: {api.operations}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
