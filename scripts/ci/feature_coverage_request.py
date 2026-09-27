#!/usr/bin/env python3
"""Coalesce advisory baseline wakes; never issue or admit a coverage certificate.

Protected producer jobs share the short request lock. Before any POST they check exact
immutable readiness metadata and existing collector ownership. The existing canonical
collector still authenticates runtime jobs, every review and public archive. A successful
old run alone never suppresses a request: duplicate suppression uses the canonical existing
certificate validator and fresh availability/source admission, without report assembly.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import time
import urllib.parse
from pathlib import Path
from typing import Any

import feature_coverage_github as publisher
from visual_review_queue import QueueError, parse_artifact

coverage = publisher.coverage
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
REQUEST_WORKFLOW = ".github/workflows/feature-coverage-request.yml"
ACTIVE = frozenset({"requested", "waiting", "pending", "queued", "in_progress"})
MAX_RUNS = 100


class Api(publisher.Api):
    def __init__(self, repository: str):
        super().__init__(repository)
        self.reads = 0
        self.mutations = 0

    def json(self, endpoint: str) -> Any:
        self.reads += 1
        return super().json(endpoint)

    def download(self, metadata: dict[str, Any], destination: Path, *,
                 maximum: int = coverage.MAX_REPORT_ARCHIVE_BYTES) -> None:
        self.reads += 1
        super().download(metadata, destination, maximum=maximum)

    def runs(self, workflow: str, source_sha: str) -> list[dict[str, Any]]:
        if workflow not in {publisher.WORKFLOW, SOURCE_WORKFLOW}:
            raise coverage.CoverageError("request discovery requires an exact workflow")
        query = urllib.parse.urlencode({"head_sha": source_sha, "per_page": MAX_RUNS})
        value = self.json(f"actions/workflows/{workflow.rsplit('/', 1)[1]}/runs?{query}")
        if (not isinstance(value, dict) or type(value.get("total_count")) is not int
                or not 0 <= value["total_count"] <= MAX_RUNS
                or not isinstance(value.get("workflow_runs"), list)
                or len(value["workflow_runs"]) != value["total_count"]):
            raise coverage.CoverageError("request workflow inventory is incomplete or over budget")
        seen = set()
        for run in value["workflow_runs"]:
            validate_run(run, repository=self.repository, source_sha=source_sha, workflows={workflow})
            if run["id"] in seen:
                raise coverage.CoverageError("request inventory repeats a workflow execution")
            seen.add(run["id"])
        return value["workflow_runs"]

    def dispatch(self, payload: dict[str, Any]) -> None:
        self.mutations += 1
        subprocess.run(["gh", "api", "--method", "POST", self.prefix + "dispatches", "--input", "-"],
                       input=coverage.admission.canonical(payload), stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=True, timeout=30)


def validate_run(run: Any, *, repository: str, source_sha: str, workflows: set[str]) -> None:
    if (not isinstance(run, dict) or type(run.get("id")) is not int or run["id"] <= 0
            or type(run.get("run_attempt")) is not int or not 1 <= run["run_attempt"] <= 100
            or run.get("path") not in workflows or run.get("head_sha") != source_sha
            or run.get("head_branch") != "master"
            or not isinstance(run.get("head_repository"), dict)
            or run["head_repository"].get("full_name") != repository
            or run.get("status") not in ACTIVE | {"completed"}):
        raise coverage.CoverageError("baseline request has a foreign or malformed protected execution")


def source_from_producer(api: publisher.Api, producer_id: int, source_sha: str, *,
                         owner: dict[str, Any] | None = None) -> int | None:
    """Resolve only a wake identity, allowing its own final request job to be in progress."""
    owner = api.run(producer_id) if owner is None else owner
    if owner.get("head_sha") != source_sha:
        return None
    validate_run(owner, repository=api.repository, source_sha=source_sha,
                 workflows={coverage.DRAIN_WORKFLOW, publisher.PAGES_WORKFLOW})
    if owner["id"] != producer_id:
        raise coverage.CoverageError("baseline request substituted its producer owner")
    pages = owner["path"] == publisher.PAGES_WORKFLOW
    if owner.get("event") not in (publisher.PAGES_EVENTS if pages else coverage.DRAIN_EVENTS):
        raise coverage.CoverageError("baseline request has a foreign producer event")
    records = api.artifacts(run_id=producer_id)
    sources, targets = set(), set()
    expected = {row["bundle_key"] for row in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]}
    for record in records:
        name = record["name"]
        if pages:
            parsed = publisher.parse_public_baseline_name(name)
            if parsed is None or parsed[1] != source_sha:
                continue
            key, source = parsed[0], parsed[2]  # the run that tested the retained pixels
        else:
            match = publisher.REPORT_NAME.fullmatch(name)
            if match is None or match.group("target") is None:
                continue
            key, source = match.group("target"), match.group("source")
        if key not in expected or key in targets:
            raise coverage.CoverageError("producer wake has a duplicate or foreign target")
        targets.add(key)
        sources.add(int(source))
    if pages and targets != expected or not pages and not targets:
        return None
    if len(sources) != 1 or not pages and len(targets) != 1:
        raise coverage.CoverageError("producer wake has an ambiguous runtime source")
    if pages:
        return publisher.generation_for_tested(api, source_sha, sources.pop())
    return sources.pop()


def active_tail(api: publisher.Api, producer_id: int | None, source_sha: str, *,
                owner: dict[str, Any] | None = None) -> int | None:
    """Only an authenticated running tail may need the collector's producer wait.

    Terminal recovery nominates the source, never the cancelled/failed trigger as evidence.
    The CLI shares its already authenticated owner snapshot within this invocation only.
    """
    if producer_id is None:
        if owner is not None:
            raise coverage.CoverageError("baseline request has an owner without a producer")
        return None
    coverage._positive_integer(producer_id, "request producer")
    owner = api.run(producer_id) if owner is None else owner
    validate_run(owner, repository=api.repository, source_sha=source_sha,
                 workflows={coverage.DRAIN_WORKFLOW, publisher.PAGES_WORKFLOW})
    events = publisher.PAGES_EVENTS if owner["path"] == publisher.PAGES_WORKFLOW else coverage.DRAIN_EVENTS
    if (owner["id"] != producer_id or owner.get("event") not in events
            or (owner["status"] == "in_progress" and owner.get("conclusion") is not None)
            or (owner["status"] == "completed" and owner.get("conclusion") not in
                {"success", "failure", "cancelled", "timed_out"})
            or owner["status"] not in {"in_progress", "completed"}):
        raise coverage.CoverageError("baseline request has an invalid producer tail or recovery owner")
    return producer_id if owner["status"] == "in_progress" else None


def title(source_id: int, attempt: int) -> str:
    return f"Complete baseline {source_id} attempt {attempt}"


def recover_source(api: Api, source_sha: str) -> int | None:
    candidates = sorted((run for run in api.runs(SOURCE_WORKFLOW, source_sha)
                         if run["event"] == "workflow_dispatch" and run["status"] == "completed"
                         and run.get("conclusion") == "success"), key=lambda run: run["id"], reverse=True)
    for source in candidates:
        # A newer selected recovery is not a complete baseline and must not hide the
        # previous complete current-head source. All inventories remain bounded; the
        # request independently rechecks its selected owner's current attempt/evidence.
        if not any(record["name"] == coverage.SELECTION_ARTIFACT_NAME
                   for record in api.artifacts(run_id=source["id"])):
            return source["id"]
    return None


def matching_collector(run: dict[str, Any], source_id: int, attempt: int) -> bool:
    return (run.get("event") in {"repository_dispatch", "workflow_dispatch"}
            and run.get("display_title") == title(source_id, attempt))


def metadata_ready(api: publisher.Api, source_sha: str, source_id: int,
                   producer_id: int | None = None, *, tested_run: int | None = None) -> bool:
    """Cheap scheduling hints only: no owner jobs, runtime archives, images or model input.

    ``tested_run`` names the run whose pixels the generation's retained public baselines hold
    (``feature_coverage_github.tested_run_id``; the generation itself by default)."""
    tested_run = source_id if tested_run is None else tested_run
    targets = coverage.inventory(coverage.DEFAULT_MATRIX)["include"]
    # Test one public archive first, then interleave target reviews/public archives. This stops
    # at the first absent review without rereading sixteen already available public records.
    checks = [(True, targets[0])]
    for index, target in enumerate(targets):
        checks.append((False, target))
        if index:
            checks.append((True, target))
    admitted_metadata = []
    for public, target in checks:
        key = target["bundle_key"]
        name = (publisher.public_baseline_name(key, source_sha, tested_run) if public
                else f"visual-review-{source_id}--{key}")
        candidates = api.artifacts(name=name)
        available = []
        for candidate in candidates:
            if public:
                record = {field: candidate.get(field) for field in ("id", "name", "digest", "size_in_bytes")}
                owner = candidate.get("workflow_run")
                if not isinstance(owner, dict):
                    raise coverage.CoverageError("readiness archive has no immutable owner")
                record["owner_run_id"] = owner.get("id")
                publisher.validate_public_record(record, bundle_key=key, source_sha=source_sha,
                                                 source_run_id=source_id, tested_run_id=tested_run)
                if (owner.get("head_sha") != source_sha or owner.get("head_branch") != "master"
                        or type(candidate.get("expired")) is not bool):
                    raise coverage.CoverageError("public readiness belongs to another generation")
                expired = candidate["expired"]
            else:
                parsed = parse_artifact(candidate)
                if (parsed.head_sha != source_sha or parsed.head_branch != "master"
                        or parsed.size_in_bytes > coverage.MAX_REPORT_ARCHIVE_BYTES):
                    raise coverage.CoverageError("review readiness belongs to another generation")
                expired = parsed.expired
            if not expired:
                available.append(candidate)
        if public:
            # Match the collector's exact newest-eight window before skipping expired
            # records. An older success outside that window cannot authorize a wake.
            available = [candidate for candidate in
                         sorted(candidates, key=lambda item: item["id"], reverse=True)[:8]
                         if not candidate["expired"]]
        if not available:
            return False
        if not public:
            selected = publisher.select_review_candidate(api, available, source_sha=source_sha,
                                                        source_run_id=source_id, bundle_key=key)
            if selected is None:
                return False
            available = [selected]
        admitted_metadata.append((public, available))
    # A report upload may precede model/cache and other tail completion. Do not start a
    # collector while another owner's request job is waiting for this same short lock.
    # Only this invocation's own tail may still run; the collector waits for that exact
    # trigger before doing complete admission. Recovery has no exception.
    owners = {}
    for public, candidates in admitted_metadata:
        settled = False
        for candidate in candidates:
            owner_id = candidate["workflow_run"]["id"]
            if owner_id not in owners:
                owners[owner_id] = api.run(owner_id)
            owner = owners[owner_id]
            workflow = publisher.PAGES_WORKFLOW if public else coverage.DRAIN_WORKFLOW
            validate_run(owner, repository=api.repository, source_sha=source_sha, workflows={workflow})
            events = publisher.PAGES_EVENTS if public else coverage.DRAIN_EVENTS
            if owner.get("event") not in events or owner["id"] != owner_id:
                raise coverage.CoverageError("readiness has a foreign protected owner")
            if public and owner["status"] == "completed" and owner.get("conclusion") != "success":
                # Canonical public admission rejects this newer terminal owner instead of
                # falling back to an older success. Do not start a collector it would reject.
                return False
            if ((owner["status"] == "completed" and owner.get("conclusion") in
                    ({"success"} if public else {"success", "failure"})) or
                    owner_id == producer_id and owner["status"] == "in_progress"):
                settled = True
                break
        if not settled:
            return False
    return True


def existing_available(api: publisher.Api, *, repository: Path, source: dict[str, Any],
                       source_sha: str, directory: Path) -> bool:
    """Reuse the canonical duplicate fast path, not a success flag or persistent read cache."""
    import ci_reuse
    existing = publisher._existing_baseline(api, repository=repository, source=source,
                                            source_sha=source_sha, directory=directory)
    if existing is None:
        return False
    runtime = ci_reuse.runtime_source(api, source["id"], source_sha)
    if (runtime.generation.get("run_attempt") != existing["source_run_attempt"]
            or runtime.graph != existing["source_job_graph"]):
        raise coverage.CoverageError("existing certificate no longer describes its runtime source")
    return True


def request(api: Api, *, repository: Path, source_sha: str, source_id: int,
            producer_id: int | None, directory: Path, sleep=time.sleep,
            producer_owner: dict[str, Any] | None = None) -> str:
    if api.current_sha() != source_sha:
        return "stale-generation"
    source = api.run(source_id)
    validate_run(source, repository=api.repository, source_sha=source_sha, workflows={SOURCE_WORKFLOW})
    if source.get("event") != "workflow_dispatch":
        return "not-complete-source"
    if source["status"] != "completed" or source.get("conclusion") != "success":
        return "source-not-successful"
    inventory = api.artifacts(run_id=source_id)
    if any(record["name"] == coverage.SELECTION_ARTIFACT_NAME for record in inventory):
        return "selected-source"
    tail_id = active_tail(api, producer_id, source_sha, owner=producer_owner)
    attempt = source["run_attempt"]
    collectors = api.runs(publisher.WORKFLOW, source_sha)
    if any(run["status"] in ACTIVE and matching_collector(run, source_id, attempt) for run in collectors):
        return "collector-active"
    if existing_available(api, repository=repository, source=source, source_sha=source_sha, directory=directory):
        return "certificate-available" if api.current_sha() == source_sha else "stale-generation"
    tested_run = publisher.tested_run_id(api, source_id, source=source, inventory=inventory)
    if not metadata_ready(api, source_sha, source_id, tail_id, tested_run=tested_run):
        return "evidence-incomplete"
    if api.current_sha() != source_sha or api.run(source_id).get("run_attempt") != attempt:
        return "source-advanced"
    payload = {"event_type": "feature-coverage-requested", "client_payload": {
        "source_repository": api.repository, "source_run_id": str(source_id),
        "source_run_attempt": str(attempt), "source_sha": source_sha,
        "producer_run_id": str(tail_id) if tail_id is not None else ""}}
    api.dispatch(payload)
    # Retain the short request lock until the POST is visible; another producer must not race
    # an eventually-consistent list result and start the same collector again.
    for _attempt in range(15):
        known_ids = {run["id"] for run in collectors}
        if any(run["id"] not in known_ids and matching_collector(run, source_id, attempt)
               for run in api.runs(publisher.WORKFLOW, source_sha)):
            return "collector-dispatched"
        sleep(2)
    raise coverage.CoverageError("collector dispatch was not observable within the bounded request lock")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    identity = parser.add_mutually_exclusive_group(required=True)
    identity.add_argument("--producer-run-id", type=int)
    identity.add_argument("--source-run-id", type=int)
    identity.add_argument("--recover", action="store_true")
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    args = parser.parse_args()
    api = Api(args.github_repository)
    try:
        if coverage.admission.SHA.fullmatch(args.source_sha) is None:
            raise coverage.CoverageError("request requires an exact source SHA")
        coverage.policy_fingerprint(args.repository, args.source_sha, verify_executing=True)
        if api.current_sha() != args.source_sha:
            print("baseline_request outcome=stale-generation")
            return 0
        producer_owner = None
        if args.recover:
            source_id = recover_source(api, args.source_sha)
        elif args.producer_run_id is not None:
            producer_owner = api.run(args.producer_run_id)
            source_id = source_from_producer(api, args.producer_run_id, args.source_sha,
                                            owner=producer_owner)
        else:
            source_id = args.source_run_id
        if source_id is None:
            print("baseline_request outcome=no-complete-source")
            return 0
        with tempfile.TemporaryDirectory(prefix="quickskin-baseline-request-") as temporary:
            outcome = request(api, repository=args.repository, source_sha=args.source_sha,
                              source_id=source_id, producer_id=args.producer_run_id, directory=Path(temporary),
                              producer_owner=producer_owner)
        print(f"baseline_request outcome={outcome} source_run={source_id}")
    except (OSError, ValueError, QueueError, subprocess.SubprocessError) as exc:
        parser.exit(2, f"Baseline request failed: {exc}\n")
    finally:
        print(f"baseline_request api_reads={api.reads} api_mutations={api.mutations}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
