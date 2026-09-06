#!/usr/bin/env python3
"""Reuse a merged PR's successful Build and runtime without changing tested provenance.

A small source record is emitted by the actual PR gate. A protected master execution may
reference it only after independently checking the original run, jobs, artifacts, merged PR
and identical Git tree. References are one hop: an attestation never attests another one.
Missing/expired evidence permits fresh work; malformed proof or an unavailable API stops
admission instead of silently launching an expensive replacement.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from bounded_zip import ExtractionLimits, extract_bounded_zip
from e2e_job_graph import (BUILD_JOB, GATE_JOB, POLICY_JOB, SCENARIO_SUFFIX,
                          expected_scenario_jobs_for, validate_job_graph)

WORKFLOWS = {"build": ".github/workflows/build-gate.yml",
             "e2e": ".github/workflows/on-demand-e2e.yml"}
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
MAX_DESCRIPTOR_BYTES = 128 * 1024
MAX_DESCRIPTOR_ARCHIVE = 256 * 1024
MAX_BUNDLE_BYTES = 512 * 1024 * 1024
DEFAULT_MATRIX = Path(__file__).resolve().parents[2] / "release/release-matrix.json"
SEAL_KEYS = frozenset({"schema_version", "kind", "repository", "workflow", "run_id",
    "run_attempt", "head_sha", "head_branch", "head_repository", "tested_sha", "tree_sha",
    "pull_request", "base_sha"})
REFERENCE_KEYS = frozenset({"schema_version", "kind", "repository", "workflow",
                          "coverage_sha", "source", "seal_artifact"})
ARTIFACT_KEYS = frozenset({"id", "name", "size_in_bytes", "digest", "expired", "workflow_run"})


class ReuseError(ValueError):
    """An alleged reusable execution could not be authenticated."""


class UnavailableEvidence(ReuseError):
    """The original evidence is absent or expired; fresh execution is necessary."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReuseError(message)


def positive(value: Any) -> bool:
    return type(value) is int and value > 0


def sha(value: Any) -> bool:
    return isinstance(value, str) and SHA.fullmatch(value) is not None


def canonical(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate source record key")
        result[key] = value
    return result


def read_json(path: Path, *, maximum: int = MAX_DESCRIPTOR_BYTES) -> Any:
    require(not path.is_symlink(), "source record must not be a symbolic link")
    with path.open("rb") as stream:
        raw = stream.read(maximum + 1)
    require(0 < len(raw) <= maximum, "source record exceeds its byte limit")
    value = json.loads(raw, object_pairs_hook=unique_object)
    canonical(value)
    return value


def validate_seal(value: Any, kind: str) -> dict[str, Any]:
    require(kind in WORKFLOWS and isinstance(value, dict) and set(value) == SEAL_KEYS,
            "unknown tested-source record")
    require(type(value["schema_version"]) is int and value["schema_version"] == 1
            and value["kind"] == "quick-skin-tested-source" and value["workflow"] == WORKFLOWS[kind]
            and all(sha(value[key]) for key in ("head_sha", "tested_sha", "tree_sha", "base_sha"))
            and all(positive(value[key]) for key in ("run_id", "run_attempt", "pull_request"))
            and value["run_attempt"] <= 100
            and all(isinstance(value[key], str) and REPOSITORY.fullmatch(value[key])
                    for key in ("repository", "head_repository"))
            and isinstance(value["head_branch"], str) and 0 < len(value["head_branch"]) <= 256
            and all(character.isprintable() for character in value["head_branch"]),
            "malformed tested-source identity")
    return value


def artifact_record(value: Any) -> dict[str, Any]:
    require(isinstance(value, dict) and isinstance(value.get("workflow_run"), dict),
            "artifact has no source run")
    return {**{key: value.get(key) for key in ARTIFACT_KEYS - {"workflow_run"}},
            "workflow_run": {key: value["workflow_run"].get(key) for key in ("id", "head_sha", "head_branch")}}


def validate_artifact(value: Any, *, name: str, run: dict[str, Any], maximum: int) -> dict[str, Any]:
    record = artifact_record(value)
    require(positive(record["id"]) and record["name"] == name
            and type(record["size_in_bytes"]) is int and 0 < record["size_in_bytes"] <= maximum
            and isinstance(record["digest"], str) and DIGEST.fullmatch(record["digest"]) is not None
            and type(record["expired"]) is bool
            and record["workflow_run"] == {key: run.get(key) for key in ("id", "head_sha", "head_branch")}
            and type(record["workflow_run"]["id"]) is int,
            "artifact differs from its exact source run or limits")
    if record["expired"]:
        raise UnavailableEvidence("original source artifact expired")
    return record


def validate_reference(value: Any, kind: str) -> dict[str, Any]:
    """Check the inert record shape. API reauthentication is mandatory before using it."""
    require(isinstance(value, dict) and set(value) == REFERENCE_KEYS,
            "unknown merged-source reference")
    source = validate_seal(value["source"], kind)
    require(type(value["schema_version"]) is int and value["schema_version"] == 1
            and value["kind"] == "quick-skin-merged-source" and sha(value["coverage_sha"])
            and value["repository"] == source["repository"] and value["workflow"] == WORKFLOWS[kind],
            "merged-source reference has another repository, workflow or coverage commit")
    require(isinstance(value["seal_artifact"], dict) and set(value["seal_artifact"]) == ARTIFACT_KEYS,
            "merged-source reference has an unknown artifact schema")
    validate_artifact(value["seal_artifact"], name=f"tested-source-{kind}",
                      run={"id": source["run_id"], **source}, maximum=MAX_DESCRIPTOR_ARCHIVE)
    return source


def descriptor(api: Any, artifact: dict[str, Any], filename: str) -> Any:
    with tempfile.TemporaryDirectory(prefix="qsm-ci-source-") as temporary:
        directory = Path(temporary)
        archive = directory / "record.zip"
        api.download(artifact, archive, maximum=MAX_DESCRIPTOR_ARCHIVE)
        extracted = directory / "record"
        extract_bounded_zip(archive, extracted, ExtractionLimits(archive_bytes=MAX_DESCRIPTOR_ARCHIVE,
            entries=1, total_bytes=MAX_DESCRIPTOR_BYTES, file_bytes=MAX_DESCRIPTOR_BYTES))
        require({path.relative_to(extracted).as_posix() for path in extracted.rglob("*")} == {filename},
                "source archive has an unexpected file inventory")
        return read_json(extracted / filename)


def one_artifact(inventory: list[dict[str, Any]], name: str) -> dict[str, Any]:
    matches = [item for item in inventory if item.get("name") == name]
    if not matches:
        raise UnavailableEvidence("original source artifact is unavailable")
    require(len(matches) == 1, "ambiguous original source artifact")
    return matches[0]


def successful_jobs(pages: Any, names: set[str]) -> list[dict[str, Any]]:
    require(isinstance(pages, list) and all(isinstance(page, dict) and isinstance(page.get("jobs"), list)
                                         for page in pages), "incomplete source job inventory")
    jobs = [job for page in pages for job in page["jobs"]]
    for name in names:
        matches = [job for job in jobs if isinstance(job, dict) and job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("status") == "completed"
                and matches[0].get("conclusion") == "success", "required source job did not pass: " + name)
    return jobs


def verify_reference(api: Any, value: Any, kind: str, *, matrix_path: Path = DEFAULT_MATRIX) -> tuple[dict, list, dict | None]:
    """Reauthenticate original proof and jobs. Never substitute wrapper jobs for runtime tests."""
    source = validate_reference(value, kind)
    require(value["repository"] == api.repository, "source reference belongs to another repository")
    run = api.run(source["run_id"])
    require(run.get("path") == WORKFLOWS[kind] and run.get("event") == "pull_request"
            and run.get("status") == "completed" and run.get("conclusion") == "success"
            and run.get("head_sha") == source["head_sha"] and run.get("head_branch") == source["head_branch"]
            and type(run.get("run_attempt")) is int and run["run_attempt"] == source["run_attempt"]
            and run.get("head_repository", {}).get("full_name") == source["head_repository"],
            "original PR execution is stale, foreign or unsuccessful")
    artifact = validate_artifact(api.artifact(value["seal_artifact"]["id"]), name=f"tested-source-{kind}",
                                 run=run, maximum=MAX_DESCRIPTOR_ARCHIVE)
    require(artifact == value["seal_artifact"] and descriptor(api, artifact, "tested-source.json") == source,
            "original tested-source record changed")
    pull = api.json(f"pulls/{source['pull_request']}")
    require(isinstance(pull, dict) and type(pull.get("number")) is int
            and pull["number"] == source["pull_request"] and pull.get("state") == "closed"
            and pull.get("merged") is True and pull.get("merge_commit_sha") == value["coverage_sha"]
            and pull.get("head", {}).get("sha") == source["head_sha"]
            and pull.get("head", {}).get("ref") == source["head_branch"]
            and pull.get("head", {}).get("repo", {}).get("full_name") == source["head_repository"]
            and pull.get("base", {}).get("ref") == "master"
            and pull.get("base", {}).get("repo", {}).get("full_name") == api.repository,
            "source PR did not merge into this exact protected generation")
    tested = api.json(f"git/commits/{source['tested_sha']}")
    covered = api.json(f"git/commits/{value['coverage_sha']}")
    require(isinstance(tested, dict) and tested.get("sha") == source["tested_sha"]
            and tested.get("tree", {}).get("sha") == source["tree_sha"]
            and isinstance(tested.get("parents"), list)
            and [item.get("sha") for item in tested["parents"] if isinstance(item, dict)]
                == [source["base_sha"], source["head_sha"]], "PR record has another tested merge")
    require(isinstance(covered, dict) and covered.get("sha") == value["coverage_sha"]
            and covered.get("tree", {}).get("sha") == source["tree_sha"], "merged Git tree differs from the tested tree")
    inventory = api.artifacts(run_id=source["run_id"])
    require(not any(item.get("name") == f"reused-source-{kind}" for item in inventory),
            "source references may not form a chain")
    pages = api.jobs(run)
    graph = None
    if kind == "build":
        from matrix import load_matrix
        versions = {item["artifact_version"] for item in load_matrix(matrix_path)["artifacts"]}
        successful_jobs(pages, {"Build and verify", "Validate repository policy",
            "compile / Plan every supported build target", "compile / Reverify the complete compiled matrix",
            *(f"compile / Compile Minecraft {version}" for version in versions)})
        validate_artifact(one_artifact(inventory, "staged-release-bundle"), name="staged-release-bundle",
                          run=run, maximum=MAX_BUNDLE_BYTES)
    else:
        from visual_review_targets import _inventory, _validate_artifacts
        validate_artifact(one_artifact(inventory, "e2e-input-bundle"), name="e2e-input-bundle",
                          run=run, maximum=MAX_BUNDLE_BYTES)
        graph = validate_job_graph(pages, policy="full",
                                    expected_scenarios=expected_scenario_jobs_for(matrix_path, "pr-anchors"))
        _data, _digest, rows = _inventory(matrix_path, "pr-anchors")
        packaged = [item for item in inventory if item.get("name", "").startswith("packaged-e2e-")]
        if any(item.get("expired") is True for item in packaged) or len(packaged) < len(rows):
            raise UnavailableEvidence("original runtime evidence is missing or expired")
        _validate_artifacts(packaged, {"packaged-e2e-" + row["id"] for row in rows},
            source_run_id=run["id"], source_branch=source["head_branch"], source_sha=source["head_sha"])
        selections = [item for item in inventory if item.get("name") == "e2e-feature-selection"]
        require(len(selections) <= 1, "runtime has multiple feature admissions")
        if selections:
            validate_artifact(selections[0], name="e2e-feature-selection", run=run, maximum=512 * 1024)
    return run, inventory, graph


def find_reference(api: Any, coverage_sha: str, kind: str) -> tuple[dict | None, str]:
    require(sha(coverage_sha) and kind in WORKFLOWS, "invalid reuse request")
    require(api.current_sha() == coverage_sha, "protected source advanced before reuse admission")
    pulls = api.json(f"commits/{coverage_sha}/pulls?per_page=20")
    require(isinstance(pulls, list) and len(pulls) < 20, "ambiguous merge-to-PR inventory")
    matches = [pull for pull in pulls if isinstance(pull, dict)
               and pull.get("merge_commit_sha") == coverage_sha and pull.get("merged_at")
               and pull.get("base", {}).get("ref") == "master"]
    if not matches:
        return None, "no-merged-pr-evidence"
    require(len(matches) == 1, "multiple PRs claim this exact merge")
    pull = matches[0]
    head = pull.get("head", {}).get("sha")
    require(sha(head), "merged PR has an invalid head")
    query = urllib.parse.urlencode({"event": "pull_request", "head_sha": head, "per_page": 100})
    record = api.json(f"actions/workflows/{Path(WORKFLOWS[kind]).name}/runs?{query}")
    require(isinstance(record, dict) and type(record.get("total_count")) is int
            and isinstance(record.get("workflow_runs"), list)
            and record["total_count"] == len(record["workflow_runs"]) and 0 <= record["total_count"] <= 100,
            "incomplete exact-source run inventory")
    runs = record["workflow_runs"]
    require(all(isinstance(run, dict) and positive(run.get("id")) and run.get("head_sha") == head
                and run.get("event") == "pull_request" and run.get("path") == WORKFLOWS[kind] for run in runs)
            and len({run["id"] for run in runs}) == len(runs), "foreign or duplicate source run")
    runs = [run for run in runs if run.get("head_branch") == pull["head"]["ref"]
            and run.get("head_repository", {}).get("full_name") == pull["head"]["repo"]["full_name"]]
    if not runs:
        return None, "no-original-pr-run"
    run = max(runs, key=lambda item: item["id"])
    require(run.get("status") == "completed", "latest PR validation is still pending; do not duplicate it")
    if run.get("conclusion") != "success":
        return None, "latest-pr-run-did-not-pass"
    try:
        seal_artifact = validate_artifact(one_artifact(api.artifacts(run_id=run["id"]), f"tested-source-{kind}"),
            name=f"tested-source-{kind}", run=run, maximum=MAX_DESCRIPTOR_ARCHIVE)
        seal = validate_seal(descriptor(api, seal_artifact, "tested-source.json"), kind)
        covered = api.json(f"git/commits/{coverage_sha}")
        require(isinstance(covered, dict) and covered.get("sha") == coverage_sha
                and sha(covered.get("tree", {}).get("sha")), "invalid protected commit")
        if seal["tree_sha"] != covered["tree"]["sha"]:
            return None, "merged-tree-changed"
        reference = {"schema_version": 1, "kind": "quick-skin-merged-source", "repository": api.repository,
                     "workflow": WORKFLOWS[kind], "coverage_sha": coverage_sha,
                     "source": seal, "seal_artifact": seal_artifact}
        verify_reference(api, reference, kind)
    except UnavailableEvidence:
        return None, "original-evidence-unavailable"
    require(api.current_sha() == coverage_sha, "protected source advanced during reuse admission")
    return reference, "identical-tested-tree"


@dataclass(frozen=True)
class RuntimeSource:
    generation: dict[str, Any]
    execution: dict[str, Any]
    artifacts: list[dict[str, Any]]
    graph: dict[str, Any]
    reference: dict[str, Any] | None = None

    @property
    def tested_sha(self) -> str:
        return self.reference["source"]["tested_sha"] if self.reference else self.execution["head_sha"]


def runtime_source(api: Any, run_id: int, coverage_sha: str, *, matrix_kind: str = "pr-anchors",
                   allow_in_progress: bool = False) -> RuntimeSource:
    import feature_coverage as coverage
    run = api.run(run_id)
    inventory = api.artifacts(run_id=run_id)
    references = [item for item in inventory if item["name"] == "reused-source-e2e"]
    if not references:
        graph = coverage.validate_source_run(run, api.jobs(run), github_repository=api.repository,
            source_sha=coverage_sha, source_run_id=run_id, matrix_kind=matrix_kind,
            allow_in_progress=allow_in_progress)
        return RuntimeSource(run, run, inventory, graph)
    require(len(references) == 1 and matrix_kind == "pr-anchors", "ambiguous reused runtime profile")
    require(run.get("event") == "workflow_dispatch" and run.get("head_branch") == "master"
            and run.get("head_sha") == coverage_sha and run.get("path") == WORKFLOWS["e2e"]
            and (run.get("status") == "completed" and run.get("conclusion") == "success"
                 or allow_in_progress and run.get("status") in {"queued", "in_progress"}
                    and run.get("conclusion") is None)
            and run.get("head_repository", {}).get("full_name") == api.repository,
            "reused runtime has a foreign or unsuccessful generation")
    # GitHub can report queued while advisory matrix jobs wait behind a passing gate.
    jobs = successful_jobs(api.jobs(run), {POLICY_JOB, GATE_JOB})
    require(all(job.get("conclusion") == "skipped" for job in jobs
                if job.get("name") == BUILD_JOB or job.get("name", "").endswith(SCENARIO_SUFFIX))
            and not any(item["name"].startswith("packaged-e2e-") or item["name"] == "e2e-feature-selection"
                        for item in inventory), "reused runtime mixed original and new execution")
    artifact = validate_artifact(references[0], name="reused-source-e2e", run=run,
                                 maximum=MAX_DESCRIPTOR_ARCHIVE)
    reference = descriptor(api, artifact, "reused-source.json")
    require(isinstance(reference, dict) and reference.get("coverage_sha") == coverage_sha,
            "runtime reference covers another generation")
    execution, artifacts, graph = verify_reference(api, reference, "e2e")
    return RuntimeSource(run, execution, artifacts, graph, reference)


def fetch_source_objects(repository: Path, reference: dict) -> None:
    """Fetch authenticated commit objects as data, without checking out or executing PR code."""
    source = validate_reference(reference, "e2e")
    for commit in (source["tested_sha"], source["base_sha"], source["head_sha"]):
        present = subprocess.run(["git", "cat-file", "-e", commit + "^{commit}"], cwd=repository,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if present.returncode != 0:
            subprocess.run(["git", "fetch", "--no-tags", "origin", commit], cwd=repository,
                           stdin=subprocess.DEVNULL, check=True, timeout=120)


def seal_environment(environment: dict[str, str], event: dict[str, Any], kind: str, repository: Path) -> dict:
    require(environment["GITHUB_EVENT_NAME"] == "pull_request", "only an actual PR execution can issue a source record")
    pull = event["pull_request"]
    tested = environment["GITHUB_SHA"]
    require(sha(tested), "invalid tested commit")
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    require(actual == tested, "source record checkout differs from the actual tested commit")
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=repository, text=True).strip()
    headers = subprocess.check_output(["git", "cat-file", "commit", "HEAD"], cwd=repository).split(b"\n\n", 1)[0]
    parents = [line.removeprefix(b"parent ").decode("ascii") for line in headers.splitlines()
               if line.startswith(b"parent ")]
    require(parents == [pull["base"]["sha"], pull["head"]["sha"]], "PR checkout is not its actual merge tree")
    return validate_seal({"schema_version": 1, "kind": "quick-skin-tested-source",
        "repository": environment["GITHUB_REPOSITORY"], "workflow": WORKFLOWS[kind],
        "run_id": int(environment["GITHUB_RUN_ID"]), "run_attempt": int(environment["GITHUB_RUN_ATTEMPT"]),
        "head_sha": pull["head"]["sha"], "head_branch": pull["head"]["ref"],
        "head_repository": pull["head"]["repo"]["full_name"], "tested_sha": tested, "tree_sha": tree,
        "pull_request": event["number"], "base_sha": pull["base"]["sha"]}, kind)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("seal", "resolve", "verify"))
    parser.add_argument("--kind", choices=tuple(WORKFLOWS), required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reference", type=Path)
    args = parser.parse_args()
    try:
        environment = dict(os.environ)
        if args.operation == "seal":
            event = read_json(Path(environment["GITHUB_EVENT_PATH"]), maximum=1024 * 1024)
            result = seal_environment(environment, event, args.kind, Path.cwd())
        else:
            from feature_coverage_github import Api
            api = Api(environment["GITHUB_REPOSITORY"])
            if args.operation == "verify":
                result = read_json(args.reference)
                require(result.get("coverage_sha") == environment["GITHUB_SHA"], "reference covers another checkout")
                verify_reference(api, result, args.kind)
                require(api.current_sha() == environment["GITHUB_SHA"], "protected source advanced")
            else:
                eligible = (environment.get("GITHUB_REF") == "refs/heads/master"
                    and environment.get("GITHUB_EVENT_NAME") == ("push" if args.kind == "build" else "workflow_dispatch"))
                result, reason = (find_reference(api, environment["GITHUB_SHA"], args.kind) if eligible
                                  else (None, "original-execution-required"))
                if result is not None and args.kind == "build":
                    # The post-merge pipeline adopts a complete validation generation. If its
                    # runtime evidence expired, produce one current bundle for the required run.
                    runtime, runtime_reason = find_reference(api, environment["GITHUB_SHA"], "e2e")
                    if runtime is None:
                        result, reason = None, "runtime-" + runtime_reason
                with Path(environment["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
                    output.write(f"reused={'true' if result else 'false'}\nreason={reason}\n")
                    if result:
                        output.write(f"source_run_id={result['source']['run_id']}\n")
                print(f"CI source decision: {reason}.", flush=True)
        if result is not None and args.output is not None:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("xb") as output:
                output.write(canonical(result))
    except (KeyError, TypeError, OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"CI reuse admission failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
