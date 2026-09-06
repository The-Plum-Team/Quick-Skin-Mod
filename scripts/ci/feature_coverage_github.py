#!/usr/bin/env python3
"""Assemble a complete feature baseline from authenticated, already reviewed GitHub evidence.

All GitHub operations here are GET requests. The protected workflow uploads the small resulting
certificate only after a final live-head check. No Minecraft process or model call runs here.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Any

import feature_coverage as coverage
from bounded_zip import ExtractionLimits, extract_bounded_zip
from visual_review_queue import REPORT_NAME, REPOSITORY

WORKFLOW = ".github/workflows/feature-coverage.yml"
MAX_API_BYTES = 8 * 1024 * 1024
MAX_INVENTORY = 100
REPORT_FILES = frozenset({"curation-proof.json", "review-input/visual-review-manifest.json",
                          "visual-review-report.json", "visual-review-completion.json"})


def _get(endpoint: str, *, maximum: int) -> bytes:
    process = subprocess.Popen(["gh", "api", "--method", "GET", endpoint],
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    timer = threading.Timer(60, process.kill)
    timer.daemon = True
    timer.start()
    try:
        assert process.stdout is not None
        raw = process.stdout.read(maximum + 1)
        if len(raw) > maximum:
            process.kill()
            raise coverage.CoverageError("GitHub response exceeds its byte limit")
        if process.wait(timeout=5) != 0:
            raise coverage.CoverageError("bounded GitHub GET failed or timed out")
        return raw
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        if process.stdout is not None:
            process.stdout.close()


class Api:
    def __init__(self, repository: str):
        if not isinstance(repository, str) or REPOSITORY.fullmatch(repository) is None:
            raise coverage.CoverageError("invalid GitHub repository")
        self.repository = repository
        self.prefix = f"repos/{repository}/"

    def json(self, endpoint: str) -> Any:
        raw = _get(self.prefix + endpoint, maximum=MAX_API_BYTES)
        try:
            value = json.loads(raw, object_pairs_hook=coverage.unique_object)
            coverage.admission.canonical(value)
        except (ValueError, UnicodeError, RecursionError) as exc:
            raise coverage.CoverageError("GitHub returned malformed or non-finite JSON") from exc
        return value

    def current_sha(self) -> str:
        record = self.json("branches/master")
        commit = record.get("commit") if isinstance(record, dict) else None
        sha = commit.get("sha") if isinstance(commit, dict) else None
        if not isinstance(sha, str) or coverage.admission.SHA.fullmatch(sha) is None:
            raise coverage.CoverageError("GitHub did not return a canonical shared-source commit")
        return sha

    def run(self, identifier: int) -> dict[str, Any]:
        coverage._positive_integer(identifier, "workflow run")
        record = self.json(f"actions/runs/{identifier}")
        if not isinstance(record, dict) or type(record.get("id")) is not int or record["id"] != identifier:
            raise coverage.CoverageError("GitHub run response has another identity")
        return record

    def artifact(self, identifier: int) -> dict[str, Any]:
        coverage._positive_integer(identifier, "artifact")
        record = self.json(f"actions/artifacts/{identifier}")
        if not isinstance(record, dict) or type(record.get("id")) is not int or record["id"] != identifier:
            raise coverage.CoverageError("GitHub artifact response has another immutable identity")
        return record

    def jobs(self, run: dict[str, Any]) -> list[dict[str, Any]]:
        identifier = coverage._positive_integer(run.get("id"), "workflow run")
        attempt = coverage._positive_integer(run.get("run_attempt"), "run attempt")
        if attempt > 100:
            raise coverage.CoverageError("workflow attempt exceeds its limit")
        pages, seen = [], set()
        total = None
        for page in range(1, 11):
            record = self.json(f"actions/runs/{identifier}/attempts/{attempt}/jobs?per_page=100&page={page}")
            if (not isinstance(record, dict) or type(record.get("total_count")) is not int
                    or not 0 < record["total_count"] <= 1000 or not isinstance(record.get("jobs"), list)
                    or not 0 < len(record["jobs"]) <= 100
                    or total is not None and total != record["total_count"]):
                raise coverage.CoverageError("job inventory is incomplete or exceeds its limit")
            total = record["total_count"]
            for job in record["jobs"]:
                if (not isinstance(job, dict) or type(job.get("id")) is not int or job["id"] <= 0
                        or job["id"] in seen or type(job.get("run_id")) is not int or job["run_id"] != identifier
                        or type(job.get("run_attempt")) is not int or job["run_attempt"] != attempt
                        or job.get("head_sha") != run.get("head_sha")):
                    raise coverage.CoverageError("job inventory contains a duplicate or foreign execution")
                seen.add(job["id"])
            pages.append(record)
            if len(seen) == total:
                return pages
            if len(record["jobs"]) < 100 or len(seen) > total:
                raise coverage.CoverageError("job inventory is truncated")
        raise coverage.CoverageError("job inventory pagination exceeds its limit")

    def artifacts(self, *, run_id: int | None = None, name: str | None = None) -> list[dict[str, Any]]:
        if (run_id is None) == (name is None):
            raise coverage.CoverageError("artifact query requires one exact owner or name")
        if run_id is not None:
            coverage._positive_integer(run_id, "artifact owner run")
            endpoint = f"actions/runs/{run_id}/artifacts?per_page={MAX_INVENTORY}"
        else:
            if (not isinstance(name, str)
                    or name != coverage.BASELINE_ARTIFACT_NAME and REPORT_NAME.fullmatch(name) is None):
                raise coverage.CoverageError("artifact query requires an exact report or baseline name")
            endpoint = "actions/artifacts?" + urllib.parse.urlencode({"name": name, "per_page": MAX_INVENTORY})
        record = self.json(endpoint)
        if (not isinstance(record, dict) or type(record.get("total_count")) is not int
                or not isinstance(record.get("artifacts"), list)
                or not 0 <= record["total_count"] <= MAX_INVENTORY
                or record["total_count"] != len(record["artifacts"])):
            raise coverage.CoverageError("artifact inventory is incomplete or exceeds its limit")
        for artifact in record["artifacts"]:
            if (not isinstance(artifact, dict) or not isinstance(artifact.get("name"), str)
                    or name is not None and artifact["name"] != name):
                raise coverage.CoverageError("artifact query contains a foreign name")
        return record["artifacts"]

    def download(self, metadata: dict[str, Any], destination: Path, *,
                 maximum: int = coverage.MAX_REPORT_ARCHIVE_BYTES) -> None:
        if type(maximum) is not int or not 0 < maximum <= coverage.MAX_REPORT_ARCHIVE_BYTES:
            raise coverage.CoverageError("artifact download has an invalid byte limit")
        raw = _get(self.prefix + f"actions/artifacts/{metadata['id']}/zip",
                   maximum=maximum)
        if len(raw) != metadata["size_in_bytes"] or "sha256:" + coverage.digest(raw) != metadata["digest"]:
            raise coverage.CoverageError("downloaded report archive differs from its authenticated metadata")
        with destination.open("xb") as stream:
            stream.write(raw)


def _review_files(api: Api, metadata: dict[str, Any], directory: Path) -> coverage.ReviewFiles:
    directory.mkdir()
    archive = directory / "report.zip"
    api.download(metadata, archive)
    extracted = directory / "report"
    extract_bounded_zip(archive, extracted, ExtractionLimits(
        archive_bytes=coverage.MAX_REPORT_ARCHIVE_BYTES, entries=8,
        total_bytes=len(REPORT_FILES) * coverage.MAX_JSON_BYTES,
        file_bytes=coverage.MAX_JSON_BYTES))
    actual = {path.relative_to(extracted).as_posix() for path in extracted.rglob("*") if path.is_file()}
    if actual != REPORT_FILES:
        raise coverage.CoverageError("normalized report archive has an unexpected file inventory")
    return coverage.ReviewFiles(extracted / "curation-proof.json",
                                extracted / "review-input/visual-review-manifest.json",
                                extracted / "visual-review-report.json")


def source_from_trigger(api: Api, trigger_run_id: int, source_sha: str) -> int | None:
    owner = api.run(trigger_run_id)
    if owner.get("head_sha") != source_sha:
        return None
    if (owner.get("path") != coverage.DRAIN_WORKFLOW or owner.get("head_branch") != "master"
            or owner.get("event") not in coverage.DRAIN_EVENTS
            or not isinstance(owner.get("head_repository"), dict)
            or owner["head_repository"].get("full_name") != api.repository):
        raise coverage.CoverageError("feature baseline wake has a foreign protected reviewer")
    # The explicit wake is sent at the reviewer's tail; its final cleanup may still be settling.
    for _attempt in range(30):
        if owner.get("status") == "completed":
            break
        time.sleep(2)
        owner = api.run(trigger_run_id)
    if owner.get("status") != "completed":
        return None
    reports = [item for item in api.artifacts(run_id=trigger_run_id) if REPORT_NAME.fullmatch(item["name"])]
    if not reports:
        return None
    if len(reports) != 1:
        raise coverage.CoverageError("completed reviewer has ambiguous normalized report identities")
    report = reports[0]
    match = REPORT_NAME.fullmatch(report["name"])
    assert match is not None
    key = match.group("target")
    if key is None:  # Historical branch reports cannot seed a shared feature baseline.
        return None
    source_run_id = int(match.group("source"))
    coverage.validate_review_owner(report, owner, api.jobs(owner), github_repository=api.repository,
                                   source_sha=source_sha, source_run_id=source_run_id, bundle_key=key)
    return source_run_id


def prepare(api: Api, *, repository: Path, source_sha: str, source_run_id: int,
            issuer_run_id: int, directory: Path) -> dict[str, Any] | None:
    coverage._positive_integer(issuer_run_id, "baseline issuer run")
    if api.current_sha() != source_sha:
        return None
    source = api.run(source_run_id)
    graph = coverage.validate_source_run(source, api.jobs(source), github_repository=api.repository,
                                         source_sha=source_sha, source_run_id=source_run_id)
    if any(item["name"] == coverage.SELECTION_ARTIFACT_NAME for item in api.artifacts(run_id=source_run_id)):
        return None  # Partial generations retain their earlier complete baseline.
    metadata = {}
    for target in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]:
        key = target["bundle_key"]
        candidates = api.artifacts(name=f"visual-review-{source_run_id}--{key}")
        if not candidates:
            return None
        if len(candidates) != 1:
            raise coverage.CoverageError("baseline target has ambiguous normalized review reports")
        candidate = candidates[0]
        owner_record = candidate.get("workflow_run")
        if not isinstance(owner_record, dict):
            raise coverage.CoverageError("normalized report has no owner")
        owner = api.run(coverage._positive_integer(owner_record.get("id"), "review owner"))
        if owner.get("status") != "completed":
            return None
        metadata[key] = coverage.validate_review_owner(candidate, owner, api.jobs(owner),
            github_repository=api.repository, source_sha=source_sha, source_run_id=source_run_id, bundle_key=key)
    # Only after complete ownership admission may report archives enter the secretless workspace.
    reviews = {key: _review_files(api, metadata[key], directory / key) for key in sorted(metadata)}
    baseline = coverage.create_baseline(reviews, repository=repository, source_sha=source_sha,
                                        source_run_id=source_run_id)
    if api.current_sha() != source_sha:
        return None
    return {**baseline, "issuer": {"workflow": WORKFLOW, "run_id": issuer_run_id, "sha": source_sha},
            "source_run_attempt": source["run_attempt"], "source_job_graph": graph,
            "review_artifacts": metadata}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--trigger-run-id", type=int)
    source.add_argument("--source-run-id", type=int)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--issuer-run-id", type=int, required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        if args.github_output:
            with args.github_output.open("a") as stream:
                stream.write("ready=false\n")
        if args.output.exists() or args.output.is_symlink():
            raise coverage.CoverageError("baseline output must be new")
        coverage.policy_fingerprint(args.repository, args.source_sha, verify_executing=True)
        api = Api(args.github_repository)
        source_run_id = args.source_run_id
        if args.trigger_run_id is not None:
            source_run_id = source_from_trigger(api, args.trigger_run_id, args.source_sha)
        if source_run_id is None:
            print("No current shared-source normalized review to certify.")
            return 0
        with tempfile.TemporaryDirectory(prefix="quickskin-feature-baseline-") as temporary:
            result = prepare(api, repository=args.repository, source_sha=args.source_sha,
                             source_run_id=source_run_id, issuer_run_id=args.issuer_run_id,
                             directory=Path(temporary).resolve())
        if result is None:
            print("Complete current-source clean review coverage is not available yet.")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as stream:
            stream.write(coverage.admission.canonical(result))
        if args.github_output:
            with args.github_output.open("a") as stream:
                stream.write(f"ready=true\nartifact_name={coverage.BASELINE_ARTIFACT_NAME}\n")
        print(f"Validated complete feature baseline for {len(result['targets'])} targets.")
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.exit(2, f"Feature baseline validation failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
