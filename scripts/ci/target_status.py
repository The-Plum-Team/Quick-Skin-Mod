#!/usr/bin/env python3
"""Collect advisory per-target CI snapshots from the current protected generation.

This is a bounded reader, not an acceptance gate. One inventory per workflow supplies
every target; original PR evidence is reused only through the existing CI verifier.
"""
from __future__ import annotations

import argparse
import copy
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import ci_reuse as reuse
from e2e_job_graph import SCENARIO_SUFFIX, expected_scenario_jobs_from_data
from feature_coverage_github import Api
from matrix import gha_matrix, load_matrix, read_mod_version
from target_status_render import write_site

DEFAULT_MATRIX = Path(__file__).resolve().parents[2] / "release/release-matrix.json"
STATUSES = {"queued", "in_progress", "completed", "waiting", "requested", "pending"}
CONCLUSIONS = {"success", "failure", "neutral", "cancelled", "skipped", "timed_out",
               "action_required", "stale", "startup_failure"}
BUILD_SHARED = ["Resolve tested build reuse", "Validate repository policy",
                "compile / Plan every supported build target"]
E2E_SHARED = ["Classify packaged runtime impact", "Resolve the exact source Build bundle",
              "Build immutable E2E input bundle"]
ERRORS = (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError)


class StatusError(ValueError):
    """A snapshot cannot be tied to a single protected CI generation."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise StatusError(message)


class StatusApi(Api):
    """Cache only within this observation, with explicit fresh identity reads."""

    def __init__(self, repository: str):
        super().__init__(repository)
        self.cache: dict[str, Any] = {}
        self.requests = 0

    def json(self, endpoint: str) -> Any:
        if endpoint not in self.cache:
            self.requests += 1
            require(self.requests <= 100, "status observation exceeded its API budget")
            self.cache[endpoint] = super().json(endpoint)
        return copy.deepcopy(self.cache[endpoint])

    def fresh_json(self, endpoint: str) -> Any:
        self.cache.pop(endpoint, None)
        return self.json(endpoint)

    def current_sha(self) -> str:
        self.cache.pop("branches/master", None)
        return super().current_sha()

    def download(self, metadata: dict[str, Any], destination: Path, *, maximum: int) -> None:
        self.requests += 1
        require(self.requests <= 100, "status observation exceeded its API budget")
        require(maximum <= reuse.MAX_DESCRIPTOR_ARCHIVE, "status reader only downloads tiny source records")
        super().download(metadata, destination, maximum=maximum)

    def jobs(self, run: dict[str, Any]) -> list[dict[str, Any]]:
        endpoint = (f"actions/runs/{run['id']}/attempts/{run['run_attempt']}"
                    "/jobs?per_page=100&page=1")
        first = self.json(endpoint)
        if (isinstance(first, dict) and type(first.get("total_count")) is int
                and first["total_count"] == 0 and first.get("jobs") == []):
            return [first]  # A newly requested run legitimately has no jobs yet.
        return super().jobs(run)


def execution_fields(record: dict[str, Any]) -> None:
    require(record.get("status") in STATUSES and record.get("conclusion") in CONCLUSIONS | {None},
            "unknown execution state")
    require((record["status"] == "completed") == (record.get("conclusion") is not None),
            "inconsistent execution state")


def run_record(run: dict[str, Any], repository: str) -> dict[str, Any]:
    require(isinstance(run, dict) and reuse.positive(run.get("id"))
            and reuse.positive(run.get("run_attempt")) and run["run_attempt"] <= 100
            and reuse.sha(run.get("head_sha")), "invalid run identity")
    execution_fields(run)
    return {"id": run["id"], "attempt": run["run_attempt"], "sha": run["head_sha"],
            "status": run["status"], "conclusion": run.get("conclusion"),
            "url": f"https://github.com/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}"}


def job_records(pages: list[dict], run: dict, repository: str) -> list[dict]:
    result, identifiers, names = [], set(), set()
    require(isinstance(pages, list) and 1 <= len(pages) <= 10, "invalid job pages")
    for page in pages:
        require(isinstance(page, dict) and isinstance(page.get("jobs"), list), "invalid job page")
        for job in page["jobs"]:
            require(isinstance(job, dict) and reuse.positive(job.get("id"))
                    and job["id"] not in identifiers and type(job.get("run_id")) is int
                    and job["run_id"] == run["id"] and type(job.get("run_attempt")) is int
                    and job["run_attempt"] == run["run_attempt"] and job.get("head_sha") == run["head_sha"]
                    and isinstance(job.get("name"), str) and 0 < len(job["name"]) <= 256
                    and all(character.isprintable() for character in job["name"])
                    and job["name"] not in names, "duplicate, malformed or foreign job")
            execution_fields(job)
            identifiers.add(job["id"])
            names.add(job["name"])
            result.append({"id": job["id"], "name": job["name"], "status": job["status"],
                           "conclusion": job.get("conclusion"),
                           "url": f"https://github.com/{repository}/actions/runs/{run['id']}/job/{job['id']}"})
    require(len(result) <= 1000, "too many jobs")
    return result


def targets(data: dict, mod_version: str) -> list[dict]:
    require(data["schema_version"] == 3, "target status requires the shared-source matrix")
    artifacts = {row["artifact_node"]: row for row in data["artifacts"]}
    scenarios: dict[str, list[str]] = {}
    rows = gha_matrix(data, "pr-anchors", mod_version)["include"]
    for row in rows:
        version = artifacts[row["artifact_node"]]["artifact_version"]
        scenarios.setdefault(version, []).append(row["id"] + SCENARIO_SUFFIX)
    require({name for names in scenarios.values() for name in names}
            == set(expected_scenario_jobs_from_data(data, "pr-anchors", mod_version)),
            "target scenarios differ from the canonical runtime graph")
    result = []
    versions = {row["artifact_version"] for row in artifacts.values()}
    for version in sorted(versions, key=lambda value: tuple(map(int, value.split("."))), reverse=True):
        lanes = [row for row in artifacts.values() if row["artifact_version"] == version]
        require(scenarios.get(version) is not None, "target has no runtime scenarios")
        result.append({"version": version, "loaders": list(dict.fromkeys(row["loader"] for row in lanes)),
                       "java": sorted({row["java"] for row in lanes}),
                       "build": [*BUILD_SHARED, f"compile / Compile Minecraft {version}"],
                       "e2e": [*E2E_SHARED, *sorted(scenarios[version])]})
    return result


def project(expected: list[str], jobs: list[dict], run: dict) -> tuple[str, str]:
    selected = {job["name"]: job for job in jobs if job["name"] in expected}
    if any(job["conclusion"] in {"failure", "timed_out", "startup_failure"} for job in selected.values()):
        return "failure", "A required target job or shared prerequisite failed."
    if any(job["status"] == "in_progress" for job in selected.values()):
        return "running", "Required target jobs or shared prerequisites are running."
    if any(job["status"] != "completed" for job in selected.values()):
        return "pending", "Required target jobs or shared prerequisites are queued."
    if len(selected) == len(expected) and all(job["conclusion"] == "success" for job in selected.values()):
        return "success", "All target jobs and shared prerequisites passed."
    if any(job["conclusion"] != "success" for job in selected.values()):
        return "unknown", "Required jobs were skipped, cancelled or did not report a passing result."
    if run["status"] != "completed":
        return "pending", "Waiting for the complete set of required target jobs."
    return "unknown", "The completed run has no complete target result."


def gate(expected: list[str], state: str, reason: str, *, generation: dict | None = None,
         execution: dict | None = None, tested_sha: str | None = None,
         reused: bool = False, jobs: list[dict] | None = None) -> dict:
    return {"state": state, "reason": reason, "generation": generation, "execution": execution,
            "tested_sha": tested_sha, "reused": reused, "expected_jobs": expected,
            "jobs": [job for job in jobs or [] if job["name"] in expected]}


def run_endpoint(kind: str, sha: str) -> str:
    query = urlencode({"branch": "master", "head_sha": sha, "per_page": 100})
    return f"actions/workflows/{Path(reuse.WORKFLOWS[kind]).name}/runs?{query}"


def run_inventory(value: Any, kind: str, sha: str, repository: str) -> list[dict]:
    require(isinstance(value, dict) and type(value.get("total_count")) is int
            and isinstance(value.get("workflow_runs"), list)
            and 0 <= value["total_count"] <= 100 and value["total_count"] == len(value["workflow_runs"]),
            "incomplete workflow run inventory")
    seen = set()
    for run in value["workflow_runs"]:
        run_record(run, repository)
        require(run["id"] not in seen and run.get("head_sha") == sha
                and run.get("path") == reuse.WORKFLOWS[kind] and run.get("head_branch") == "master"
                and run.get("event") in ({"push", "workflow_dispatch"} if kind == "build" else {"workflow_dispatch"})
                and isinstance(run.get("head_repository"), dict)
                and run["head_repository"].get("full_name") == repository,
                "foreign workflow run inventory")
        seen.add(run["id"])
    return value["workflow_runs"]


def collect_gate(api: StatusApi, kind: str, sha: str, target_rows: list[dict], matrix_path: Path) -> list[dict]:
    endpoint = run_endpoint(kind, sha)
    runs = run_inventory(api.json(endpoint), kind, sha, api.repository)
    if not runs:
        return [gate(row[kind], "pending", "No workflow execution has been observed for this commit.")
                for row in target_rows]
    listed = max(runs, key=lambda run: run["id"])
    run = api.run(listed["id"])
    run_inventory({"total_count": 1, "workflow_runs": [run]}, kind, sha, api.repository)
    require(run["run_attempt"] == listed["run_attempt"], "run attempt changed during collection")
    generation = run_record(run, api.repository)
    execution, tested_sha, reused = generation, sha, False
    jobs = job_records(api.jobs(run), run, api.repository)
    inventory = api.artifacts(run_id=run["id"])
    references = [item for item in inventory if item["name"] == f"reused-source-{kind}"]
    wrapper_state = None
    original = None
    if references:
        require(len(references) == 1, "ambiguous source reference")
        wrapper_names = ([BUILD_SHARED[0], "Build and verify"] if kind == "build"
                         else [E2E_SHARED[0], "Packaged E2E gate"])
        wrapper_state = project(wrapper_names, jobs, generation)
        if wrapper_state[0] == "success":
            skipped = lambda name: (name == "Validate repository policy" or name.startswith("compile /")) if kind == "build" else (
                name in [*E2E_SHARED[1:], "Select affected feature coverage"] or name.endswith(SCENARIO_SUFFIX))
            require(all(job["conclusion"] == "skipped" for job in jobs if skipped(job["name"]))
                    and not any(item["name"] == "staged-release-bundle" if kind == "build" else (
                        item["name"].startswith("packaged-e2e-") or item["name"] in {
                            "e2e-feature-selection", "e2e-input-bundle"}) for item in inventory),
                    "reference wrapper mixed original and fresh execution")
            artifact = reuse.validate_artifact(references[0], name=f"reused-source-{kind}", run=run,
                                               maximum=reuse.MAX_DESCRIPTOR_ARCHIVE)
            reference = reuse.descriptor(api, artifact, "reused-source.json")
            require(isinstance(reference, dict) and reference.get("coverage_sha") == sha,
                    "source reference covers a different commit")
            original, _artifacts, _graph = reuse.verify_reference(api, reference, kind, matrix_path=matrix_path)
            execution = run_record(original, api.repository)
            tested_sha, reused = reference["source"]["tested_sha"], True
            jobs = job_records(api.jobs(original), original, api.repository)
            wrapper_state = None
    results = []
    for row in target_rows:
        state, reason = wrapper_state or project(row[kind], jobs, execution)
        if wrapper_state:
            reason = "Source reuse has not passed its current-generation gate. " + reason
        results.append(gate(row[kind], state, reason, generation=generation, execution=execution,
                            tested_sha=tested_sha, reused=reused, jobs=jobs))
    # Latest means the newest execution, not the newest successful execution. Recheck
    # both run selection and attempts after reading jobs/reference evidence.
    fresh = run_inventory(api.fresh_json(endpoint), kind, sha, api.repository)
    require(fresh and run_record(max(fresh, key=lambda item: item["id"]), api.repository) == generation,
            "the latest run or its state changed during collection")
    for source in [run, *([original] if original else [])]:
        current = api.fresh_json(f"actions/runs/{source['id']}")
        require(run_record(current, api.repository) == run_record(source, api.repository),
                "source execution changed during collection")
    return results


def collect(api: StatusApi, sha: str, *, matrix_path: Path = DEFAULT_MATRIX) -> dict:
    require(reuse.sha(sha), "invalid protected SHA")
    require(api.current_sha() == sha, "protected master differs from the checked-out source")
    data = load_matrix(matrix_path)
    target_rows = targets(data, read_mod_version(matrix_path, data))
    result = {"schema_version": 1, "repository": api.repository, "coverage_sha": sha,
              "observed_at": "", "targets": [{key: row[key] for key in ("version", "loaders", "java")}
                                             for row in target_rows]}
    for kind in reuse.WORKFLOWS:
        try:
            values = collect_gate(api, kind, sha, target_rows, matrix_path)
        except ERRORS as exc:
            print(f"Target {kind} status is unknown: {type(exc).__name__}: {str(exc)[:256]}", file=sys.stderr)
            values = [gate(row[kind], "unknown", "GitHub evidence is unavailable, ambiguous or changed during observation.")
                      for row in target_rows]
        for row, value in zip(result["targets"], values, strict=True):
            row[kind] = value
    require(api.current_sha() == sha, "protected master advanced during collection")
    result["observed_at"] = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("collect",))
    parser.add_argument("--repository", required=True)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        api = StatusApi(args.repository)
        snapshot = collect(api, args.expected_sha, matrix_path=args.matrix)
        write_site(snapshot, args.output)
        print(f"Collected {len(snapshot['targets'])} targets with {api.requests} bounded API reads.")
    except ERRORS as exc:
        parser.exit(1, f"Target CI observation was not published: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
