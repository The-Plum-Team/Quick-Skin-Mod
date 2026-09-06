#!/usr/bin/env python3
"""Find one successful Build bundle for this exact source; PRs wait instead of rebuilding."""

from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.parse
from dataclasses import dataclass
from pathlib import Path

from feature_coverage_github import Api


WORKFLOW = ".github/workflows/build-gate.yml"
ARTIFACT = "staged-release-bundle"
SHA = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
ACTIVE = frozenset({"queued", "in_progress", "requested", "waiting", "pending"})
MAX_BUNDLE_BYTES = 512 * 1024 * 1024


def require(value: bool, message: str) -> None:
    if not value:
        raise ValueError(message)


@dataclass(frozen=True)
class Source:
    repository: str
    event: str
    head: str
    branch: str
    head_repository: str
    tested_commit: str
    pull_request: int | None = None
    base: str | None = None

    def __post_init__(self) -> None:
        require(bool(REPOSITORY.fullmatch(self.repository)
                     and REPOSITORY.fullmatch(self.head_repository)), "invalid source repository")
        require(bool(SHA.fullmatch(self.head) and SHA.fullmatch(self.tested_commit)), "invalid source commit")
        require(self.event in {"pull_request", "push", "workflow_dispatch"}, "invalid Build event")
        require(bool(self.branch) and all(character.isprintable() for character in self.branch),
                "invalid source branch")
        if self.event == "pull_request":
            require(type(self.pull_request) is int and self.pull_request > 0
                    and isinstance(self.base, str) and bool(SHA.fullmatch(self.base)), "invalid PR source")
        else:
            require(self.pull_request is None and self.base is None and self.head == self.tested_commit,
                    "non-PR source must name its exact tested head")


def current_pull_request(api: Api, source: Source) -> None:
    if source.pull_request is None:
        return
    record = api.json(f"pulls/{source.pull_request}")
    require(isinstance(record, dict) and record.get("number") == source.pull_request
            and record.get("state") == "open", "PR closed or has another identity")
    head, base = record.get("head", {}), record.get("base", {})
    require(head.get("sha") == source.head and head.get("ref") == source.branch
            and head.get("repo", {}).get("full_name") == source.head_repository
            and base.get("sha") == source.base and base.get("repo", {}).get("full_name") == source.repository,
            "PR source or base advanced while waiting for its Build")


def find_bundle(api: Api, source: Source, *, wait_seconds: int,
                sleep=time.sleep, now=time.monotonic) -> dict | None:
    require(type(wait_seconds) is int and 0 <= wait_seconds <= 5400, "invalid Build wait limit")
    current_pull_request(api, source)
    deadline = now() + wait_seconds
    query = urllib.parse.urlencode({"event": source.event, "head_sha": source.head, "per_page": 100})
    while True:
        inventory = api.json("actions/workflows/build-gate.yml/runs?" + query)
        require(isinstance(inventory, dict) and type(inventory.get("total_count")) is int
                and isinstance(inventory.get("workflow_runs"), list)
                and inventory["total_count"] == len(inventory["workflow_runs"])
                and 0 <= inventory["total_count"] <= 100, "incomplete Build run inventory")
        runs = inventory["workflow_runs"]
        for run in runs:
            require(isinstance(run, dict) and type(run.get("id")) is int and run["id"] > 0
                    and run.get("path") == WORKFLOW and run.get("event") == source.event
                    and run.get("head_sha") == source.head, "foreign Build run in exact-source inventory")
        require(len({run["id"] for run in runs}) == len(runs), "duplicate Build run")
        runs = [run for run in runs if run.get("head_branch") == source.branch
                and run.get("head_repository", {}).get("full_name") == source.head_repository]
        run = max(runs, key=lambda item: item["id"]) if runs else None
        if run is not None and run.get("status") == "completed":
            require(run.get("conclusion") == "success", "the latest exact-source Build did not succeed")
            jobs = [job for page in api.jobs(run) for job in page["jobs"] if job.get("name") == "Build and verify"]
            require(len(jobs) == 1 and jobs[0].get("status") == "completed"
                    and jobs[0].get("conclusion") == "success", "Build has no successful complete required gate")
            artifacts = [item for item in api.artifacts(run_id=run["id"]) if item["name"] == ARTIFACT]
            if not artifacts or (len(artifacts) == 1 and artifacts[0].get("expired") is True):
                require(source.pull_request is None, "successful PR Build has no available staged bundle")
                return None
            require(len(artifacts) == 1, "Build published multiple staged bundles")
            artifact = artifacts[0]
            require(type(artifact.get("id")) is int and artifact["id"] > 0
                    and artifact.get("expired") is False
                    and type(artifact.get("size_in_bytes")) is int
                    and 0 < artifact["size_in_bytes"] <= MAX_BUNDLE_BYTES
                    and isinstance(artifact.get("digest"), str)
                    and re.fullmatch(r"sha256:[0-9a-f]{64}", artifact["digest"]) is not None,
                    "Build bundle metadata is malformed or too large")
            current_pull_request(api, source)
            return {"run_id": run["id"], "artifact_id": artifact["id"],
                    "tested_commit": source.tested_commit, "digest": artifact["digest"]}
        require(run is None or run.get("status") in ACTIVE, "unknown Build state")
        if source.pull_request is None and run is None:
            return None
        if now() >= deadline:
            require(source.pull_request is None, "timed out waiting for the PR's exact-source Build")
            return None
        sleep(min(30, max(0, deadline - now())))


def source_from_environment(environment: dict[str, str], event: dict) -> Source:
    repository = environment["GITHUB_REPOSITORY"]
    tested_commit = environment["GITHUB_SHA"]
    if environment["GITHUB_EVENT_NAME"] == "pull_request":
        pull = event["pull_request"]
        return Source(repository, "pull_request", pull["head"]["sha"], pull["head"]["ref"],
                      pull["head"]["repo"]["full_name"], tested_commit, event["number"], pull["base"]["sha"])
    branch = environment["GITHUB_REF_NAME"]
    return Source(repository, "push" if branch == "master" else "workflow_dispatch",
                  tested_commit, branch, repository, tested_commit)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wait-seconds", type=int, default=5400)
    args = parser.parse_args()
    try:
        event_path = Path(os.environ["GITHUB_EVENT_PATH"])
        require(event_path.stat().st_size <= 8 * 1024 * 1024, "GitHub event exceeds its limit")
        event = json.loads(event_path.read_bytes())
        source = source_from_environment(dict(os.environ), event)
        result = find_bundle(Api(source.repository), source, wait_seconds=args.wait_seconds)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
            output.write(f"reused={'true' if result else 'false'}\n")
            if result:
                output.write(f"run_id={result['run_id']}\nartifact_id={result['artifact_id']}\n")
        print(f"Authenticated Build bundle from run {result['run_id']}." if result
              else "No available Build bundle; compile the complete matrix on isolated runners.", flush=True)
        return 0
    except (KeyError, TypeError, OSError, ValueError) as exc:
        parser.exit(1, f"Build bundle admission failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
