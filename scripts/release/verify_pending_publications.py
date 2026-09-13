#!/usr/bin/env python3
"""Check pending drafts without upload credentials, then finalize under release approval."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from publication_state import (ROOT, SHA, Api, bind, check_all, decode, read_release, ready, report,
                               require, save)
from bounded_zip import ExtractionLimits, extract_bounded_zip
from feature_coverage_github import _get
from github_release import load_contract, publish_release, write_checksums
from matrix import gha_matrix, load_matrix_snapshot, select_release_target
from recover_sbom_release import successful_job, validate_inventory
from release_identity import derive
from verify_release import verify_staged_manifest

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_PENDING = 32
WORKFLOWS = {".github/workflows/release.yml": "push",
             ".github/workflows/release-recovery.yml": "workflow_dispatch"}


def git(*arguments: str) -> str:
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True,
                                   stderr=subprocess.DEVNULL, timeout=60).strip()


def pending_tags(api: Api) -> list[str]:
    tags: list[str] = []
    seen: set[int] = set()
    for page in range(1, 11):
        releases = api.json(f"releases?per_page=100&page={page}")
        require(isinstance(releases, list) and len(releases) <= 100, "invalid release inventory")
        for release in releases:
            require(isinstance(release, dict) and type(release.get("id")) is int
                    and release["id"] not in seen, "duplicate or malformed release inventory")
            seen.add(release["id"])
            if release.get("draft") is not True:
                continue
            state = decode(release.get("body") or "")
            if state is not None:
                require(release.get("tag_name") == state["tag"], "draft and publication ledger differ")
                tags.append(state["tag"])
                require(len(tags) <= MAX_PENDING, "pending release inventory exceeds its limit")
        if len(releases) < 100:
            require(len(tags) == len(set(tags)), "duplicate pending release tag")
            return sorted(tags)
    raise ValueError("release inventory exceeds its pagination limit")


def latest_jobs(api: Api, run: dict[str, Any]) -> list[dict[str, Any]]:
    # GitHub's latest filter includes reused jobs after a failed-jobs-only rerun.
    record = api.json(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
    jobs = record.get("jobs") if isinstance(record, dict) else None
    require(isinstance(jobs, list) and type(record.get("total_count")) is int
            and 0 < record["total_count"] == len(jobs) <= 100, "incomplete release job inventory")
    ids: set[int] = set()
    names: set[str] = set()
    for job in jobs:
        require(isinstance(job, dict) and type(job.get("id")) is int and job["id"] > 0
                and job["id"] not in ids and isinstance(job.get("name"), str)
                and job["name"] not in names and job.get("run_id") == run["id"]
                and job.get("head_sha") == run["head_sha"]
                and type(job.get("run_attempt")) is int
                and 0 < job["run_attempt"] <= run["run_attempt"], "foreign or duplicate release job")
        ids.add(job["id"])
        names.add(job["name"])
    return jobs


def authenticate_run(api: Api, state: dict[str, Any], run: dict[str, Any],
                     matrix: dict[str, Any], version: str) -> None:
    path = run.get("path")
    require(path in WORKFLOWS and run.get("event") == WORKFLOWS[path]
            and run.get("id") == state["run_id"] and run.get("head_sha") == state["producer_sha"]
            and run.get("repository", {}).get("full_name") == api.repository
            and run.get("head_repository", {}).get("full_name") == api.repository
            and run.get("status") == "completed" and type(run.get("run_attempt")) is int
            and 0 < run["run_attempt"] <= 100, "untrusted publication producer")
    normal = path == ".github/workflows/release.yml"
    require(run.get("head_branch") == (state["tag"] if normal else "master"),
            "publication producer has the wrong ref")
    require(not normal or state["source_sha"] == state["producer_sha"],
            "tag publication changed its source identity")
    jobs = latest_jobs(api, run)
    stage = successful_job(jobs, "Stage exact GitHub Release assets")
    steps = {step.get("name"): step.get("conclusion") for step in stage.get("steps", [])}
    require(steps.get("Persist resumable publication identity") == "success",
            "producer did not approve and persist this publication")
    if normal:
        build = successful_job(jobs, "Build immutable release bundle")
        steps = {step.get("name"): step.get("conclusion") for step in build.get("steps", [])}
        for name in ("Validate requested release identity", "Stage and verify all production artifacts",
                     "Prove first and second build bytes are identical", "Rehearse publication and interrupted recovery"):
            require(steps.get(name) == "success", f"producer lacks {name}")
        expected = {f"{row['id']} - packaged release behavior"
                    for row in gha_matrix(matrix, "runtime", version)["include"]}
        actual = {job["name"] for job in jobs if job["name"].endswith(" - packaged release behavior")}
        require(actual == expected, "producer has an incomplete release runtime inventory")
        for name in sorted(expected):
            successful_job(jobs, name)
    else:
        job = successful_job(jobs, "Authenticate and repair the original release SBOM")
        steps = {step.get("name"): step.get("conclusion") for step in job.get("steps", [])}
        require(steps.get("Authenticate original source, full release E2E, archive and tag provenance") == "success",
                "recovery lacks authenticated original release evidence")


def authenticate_artifact(state: dict[str, Any], run: dict[str, Any],
                          artifact: dict[str, Any]) -> None:
    prefix = "release-" if run["path"] == ".github/workflows/release.yml" else "release-recovery-"
    require(artifact.get("id") == state["artifact_id"]
            and artifact.get("name") == prefix + state["tag"]
            and artifact.get("digest") == state["artifact_digest"]
            and artifact.get("expired") is False
            and artifact.get("workflow_run", {}).get("id") == state["run_id"]
            and artifact["workflow_run"].get("head_sha") == state["producer_sha"]
            and type(artifact.get("size_in_bytes")) is int
            and 0 < artifact["size_in_bytes"] <= MAX_ARCHIVE_BYTES,
            "pending release archive differs, expired, or exceeds its limit")


def download_archive(api: Api, artifact: dict[str, Any], destination: Path) -> None:
    # Release bundles have their own 64 MiB bound; the shared report downloader is capped at 4 MiB.
    raw = _get(api.prefix + f"actions/artifacts/{artifact['id']}/zip", maximum=MAX_ARCHIVE_BYTES)
    require(len(raw) == artifact["size_in_bytes"]
            and "sha256:" + hashlib.sha256(raw).hexdigest() == artifact["digest"],
            "downloaded release archive differs from its authenticated metadata")
    with destination.open("xb") as stream:
        stream.write(raw)


def snapshot_inputs(state: dict[str, Any], destination: Path) -> tuple[dict[str, Any], Any]:
    # Read old files as inert data. Never check out or execute code from a queued release.
    def export(path: str) -> None:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        result = subprocess.run(["git", "show", f"{state['producer_sha']}:{path}"], cwd=ROOT,
                                check=True, capture_output=True, timeout=60)
        require(len(result.stdout) <= 8 * 1024 * 1024, "release input exceeds its limit")
        target.write_bytes(result.stdout)

    for path in ("release/release-matrix.json", "gradle.properties", "gradle/verification-metadata.xml"):
        export(path)
    matrix_path = destination / "release/release-matrix.json"
    matrix = load_matrix_snapshot(matrix_path, destination / "gradle.properties")
    require(matrix["schema_version"] == 3 and matrix["project"]["release_branch"] == "master",
            "pending release is not from the shared protected source")
    identity = derive(matrix_path, matrix, target=state["target"])
    require(identity.tag == state["tag"], "pending release is not a canonical source identity")
    selected = select_release_target(matrix, state["target"])
    for row in selected["artifacts"]:
        export(f"gradle/dependency-locks/{row['artifact_node']}.lockfile")
    return selected, identity


def authenticate_tag(api: Api, state: dict[str, Any], policy_sha: str) -> None:
    require(git("merge-base", state["producer_sha"], policy_sha) == state["producer_sha"]
            and git("merge-base", state["source_sha"], state["producer_sha"]) == state["source_sha"],
            "publication source is outside protected master history")
    ref = api.json(f"git/ref/tags/{state['tag']}")
    require(ref.get("ref") == f"refs/tags/{state['tag']}", "remote release tag differs")
    obj = ref.get("object", {})
    for _ in range(4):
        if obj.get("type") == "commit":
            break
        require(obj.get("type") == "tag" and isinstance(obj.get("sha"), str)
                and SHA.fullmatch(obj["sha"]) is not None, "invalid release tag object")
        tag = api.json(f"git/tags/{obj['sha']}")
        require(tag.get("sha") == obj["sha"], "annotated tag identity differs")
        obj = tag.get("object", {})
    require(obj.get("type") == "commit" and obj.get("sha") == state["source_sha"]
            and git("rev-parse", f"refs/tags/{state['tag']}^{{commit}}") == state["source_sha"],
            "immutable release tag differs from the original source")


def verify(api: Api, tag: str, directory: Path, policy_sha: str, *, finalize: bool) -> bool:
    release = read_release(api, tag)
    state = decode(release.get("body") or "")
    require(state is not None, "draft has no publication ledger")
    if not release["draft"]:
        require(ready(state), "published release has an incomplete ledger")
        return False
    run = api.run(state["run_id"])
    if run.get("status") != "completed":
        print(f"{tag}: original publication workflow is still running")
        return False
    authenticate_tag(api, state, policy_sha)
    snapshot = directory / "source-data"
    matrix, identity = snapshot_inputs(state, snapshot)
    authenticate_run(api, state, run, matrix, identity.mod_version)
    artifact = api.artifact(state["artifact_id"])
    authenticate_artifact(state, run, artifact)
    archive = directory / "release.zip"
    download_archive(api, artifact, archive)
    stage = directory / "stage"
    extract_bounded_zip(archive, stage, ExtractionLimits(
        archive_bytes=MAX_ARCHIVE_BYTES, entries=64, total_bytes=MAX_ARCHIVE_BYTES,
        file_bytes=32 * 1024 * 1024))
    manifest_path = stage / "artifacts.json"
    bind(state, matrix, manifest_path)
    manifest = json.loads(manifest_path.read_bytes())
    validate_inventory(stage, manifest)
    verify_staged_manifest(snapshot, stage, manifest_path, manifest, matrix,
                           snapshot / "release/release-matrix.json", identity.mod_version,
                           state["source_sha"], target=state["target"])
    checked = check_all(state, matrix, manifest_path, stage)
    summary = report(checked)
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
            output.write(summary + "\n")
    if finalize and ready(checked):
        require(api.current_sha() == policy_sha, "protected verifier implementation advanced; retry")
        contract = load_contract(manifest_path, stage, tag, state["source_sha"])
        checksums = write_checksums(contract, directory)
        save(api, release, checked)
        publish_release(api.repository, contract, checksums)
    return ready(checked)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag")
    parser.add_argument("--finalize", action="store_true")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        require(not args.finalize or args.tag is not None, "finalization needs one exact tag")
        require(os.environ.get("GITHUB_REF") == "refs/heads/master"
                and os.environ.get("GITHUB_EVENT_NAME") in {"schedule", "workflow_run", "workflow_dispatch"},
                "verification must run from protected master")
        api = Api(os.environ["GITHUB_REPOSITORY"])
        policy_sha = os.environ["GITHUB_SHA"]
        require(git("rev-parse", "HEAD") == policy_sha == api.current_sha(),
                "verifier must execute the current protected master implementation")
        tags = [args.tag] if args.tag else pending_tags(api)
        complete: list[dict[str, str]] = []
        failures: list[str] = []
        for tag in tags:
            try:
                with tempfile.TemporaryDirectory(prefix="pending-release-") as temporary:
                    if verify(api, tag, Path(temporary), policy_sha, finalize=args.finalize):
                        complete.append({"tag": tag})
            except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
                # One broken draft must not prevent checking other independent targets.
                failures.append(f"{tag}: {exc}")
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write("matrix=" + json.dumps({"include": complete}, separators=(",", ":")) + "\n")
                output.write(f"count={len(complete)}\n")
        require(not failures, "\n".join(failures))
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"pending publication verification failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
