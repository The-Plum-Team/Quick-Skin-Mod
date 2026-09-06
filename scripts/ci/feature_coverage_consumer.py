#!/usr/bin/env python3
"""Authenticate a complete baseline and derive cumulative feature coverage from Git objects.

Only the protected publisher's immutable artifact authorizes reuse. Reduced runs never replace
that complete baseline. Missing, stale, changed-policy or ambiguous evidence requires full E2E.
"""
from __future__ import annotations

import argparse
import json
import re
import tempfile
from pathlib import Path
from typing import Any

import feature_coverage as coverage
import feature_coverage_github as publisher
import ci_reuse
from bounded_zip import ExtractionLimits, extract_bounded_zip
from visual_review_queue import QueueError, parse_artifact, valid_owner

MAX_BASELINE_BYTES = 256 * 1024
MAX_ARCHIVE_BYTES = 512 * 1024
MAX_CANDIDATES = 8
ISSUER_EVENTS = frozenset({"workflow_run", "repository_dispatch", "workflow_dispatch"})
ISSUER_JOB = "Authenticate complete shared feature coverage"
BASELINE_KEYS = frozenset({"schema_version", "kind", "profile", "coverage", "source_sha",
    "source_run_id", "matrix_sha256", "scenario_contract_sha256", "module_graph_sha256",
    "policy_sha256", "module_fingerprints", "targets", "issuer", "source_run_attempt",
    "source_job_graph", "review_artifacts", "public_artifacts"})
TARGET_KEYS = frozenset({"bundle_key", "artifact_nodes", "source_artifact_ids", "frame_count",
                         "proof_sha256", "manifest_sha256", "report_sha256"})
REVIEW_KEYS = frozenset({"id", "owner_run_id", "name", "digest", "size_in_bytes"})


def _hash(value: Any) -> bool:
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def authenticate_execution(api: publisher.Api, repository: Path, *, run_id: int,
                           tested_sha: str, policy_sha: str, pull_number: int | None = None,
                           merged_reference: dict | None = None) -> None:
    """Bind a master run or GitHub's actual PR merge tree, including both authenticated parents."""
    coverage.admission._commit(repository, tested_sha)
    coverage.admission._commit(repository, policy_sha)
    if merged_reference is not None:
        source = ci_reuse.validate_reference(merged_reference, "e2e")
        ci_reuse.require((run_id, tested_sha, policy_sha, pull_number) == (
            source["run_id"], source["tested_sha"], source["base_sha"], source["pull_request"])
            and api.current_sha() == merged_reference["coverage_sha"],
            "selected source differs from the merged PR execution")
        ci_reuse.verify_reference(api, merged_reference, "e2e")
        coverage.policy_fingerprint(repository, policy_sha, verify_executing=True)
        return
    if api.current_sha() != policy_sha:
        raise coverage.CoverageError("selection policy is no longer current master")
    run = api.run(run_id)
    if (run.get("path") != ".github/workflows/on-demand-e2e.yml"
            or not isinstance(run.get("head_repository"), dict)
            or run["head_repository"].get("full_name") != api.repository):
        raise coverage.CoverageError("selection source has a foreign workflow or repository")
    if run.get("event") == "workflow_dispatch":
        if (pull_number is not None or run.get("head_branch") != "master"
                or run.get("head_sha") != tested_sha or tested_sha != policy_sha):
            raise coverage.CoverageError("selection requires the exact shared-source execution")
        return
    if run.get("event") != "pull_request":
        raise coverage.CoverageError("this execution requires the complete profile")
    coverage._positive_integer(pull_number, "pull request")
    associations = run.get("pull_requests")
    if (not isinstance(associations, list) or len(associations) != 1
            or not isinstance(associations[0], dict) or type(associations[0].get("number")) is not int
            or associations[0]["number"] != pull_number):
        raise coverage.CoverageError("selection source has an ambiguous pull request")
    pull = api.json(f"pulls/{pull_number}")
    if not isinstance(pull, dict):
        raise coverage.CoverageError("selection pull request is malformed")
    base, head = pull.get("base"), pull.get("head")
    if (type(pull.get("number")) is not int or pull["number"] != pull_number
            or pull.get("state") != "open" or pull.get("merge_commit_sha") != tested_sha
            or not isinstance(base, dict) or not isinstance(head, dict)
            or base.get("ref") != "master" or base.get("sha") != policy_sha
            or head.get("sha") != run.get("head_sha") or head.get("ref") != run.get("head_branch")
            or any(not isinstance(item.get("repo"), dict)
                   or item["repo"].get("full_name") != api.repository for item in (base, head))):
        raise coverage.CoverageError("selection pull request is stale, foreign or targets another policy")
    coverage.admission._commit(repository, head["sha"])
    parents = coverage.admission._git(repository, "rev-list", "--parents", "-n", "1", tested_sha,
                                       maximum=123)
    if parents != f"{tested_sha} {policy_sha} {head['sha']}\n".encode():
        raise coverage.CoverageError("tested PR tree does not have its authenticated base and head parents")


def validate_owner(artifact: Any, owner: Any, jobs: Any, *, github_repository: str) -> dict[str, Any]:
    parsed = parse_artifact(artifact)
    if (parsed.name != coverage.BASELINE_ARTIFACT_NAME or parsed.expired
            or parsed.size_in_bytes > MAX_ARCHIVE_BYTES or parsed.head_branch != "master"
            or not isinstance(owner, dict) or type(owner.get("id")) is not int
            or not isinstance(owner.get("head_repository"), dict)
            or not valid_owner(owner, repository=github_repository, artifact=parsed,
                               workflow=publisher.WORKFLOW, events=ISSUER_EVENTS,
                               conclusions=frozenset({"success"}))):
        raise coverage.CoverageError("baseline has a foreign, expired, oversized or unsuccessful issuer")
    job = coverage._unique_named_job(coverage._jobs(jobs), ISSUER_JOB)
    coverage._require_conclusion(job, ISSUER_JOB, "success")
    return {"id": parsed.artifact_id, "owner_run_id": parsed.run_id, "source_sha": parsed.head_sha,
            "name": parsed.name, "digest": parsed.digest, "size_in_bytes": parsed.size_in_bytes}


def validate_baseline(value: Any, metadata: dict[str, Any], *, repository: Path,
                      head: str, policy: str) -> dict[str, str]:
    """Recompute complete coverage and dependency identities under the executing policy."""
    if not isinstance(value, dict) or set(value) != BASELINE_KEYS:
        raise coverage.CoverageError("baseline has an incomplete or unknown certificate schema")
    source = metadata["source_sha"]
    expected_issuer = {"workflow": publisher.WORKFLOW, "run_id": metadata["owner_run_id"], "sha": source}
    if (type(value["schema_version"]) is not int or value["schema_version"] != 1
            or value["kind"] != coverage.BASELINE_KIND or value["profile"] != "pr"
            or value["coverage"] != "full" or value["source_sha"] != source
            or not isinstance(value["issuer"], dict) or value["issuer"] != expected_issuer
            or type(value["issuer"].get("run_id")) is not int):
        raise coverage.CoverageError("baseline is not a complete certificate from its authenticated issuer")
    run_id = coverage._positive_integer(value["source_run_id"], "baseline source run")
    if coverage._positive_integer(value["source_run_attempt"], "baseline source attempt") > 100:
        raise coverage.CoverageError("baseline source attempt exceeds its limit")
    graph = coverage.load_graph()
    contract = coverage.default_contract()
    policy_digest = coverage.policy_fingerprint(repository, policy, verify_executing=True)
    if (value["matrix_sha256"] != coverage.digest(coverage.DEFAULT_MATRIX.read_bytes())
            or value["scenario_contract_sha256"] != contract.sha256
            or value["module_graph_sha256"] != graph.sha256
            or value["policy_sha256"] != policy_digest
            or any(coverage.policy_fingerprint(repository, commit) != policy_digest for commit in (source, head))
            or coverage.admission._git(repository, "merge-base", "--is-ancestor", source, head,
                                        allow_false=True) is None):
        raise coverage.CoverageError("baseline ancestry, contracts or protected policy changed")
    fingerprints = coverage.module_fingerprints(repository, source, graph)
    if value["module_fingerprints"] != fingerprints:
        raise coverage.CoverageError("baseline module dependency fingerprints differ from their exact Git trees")
    expected_jobs = sorted(coverage.expected_scenario_jobs_for(coverage.DEFAULT_MATRIX, "pr-anchors"))
    job_graph = {"schema_version": 1, "runtime_policy": "full",
                 "expected_scenario_jobs": expected_jobs, "observed_scenario_jobs": expected_jobs}
    if (not isinstance(value["source_job_graph"], dict) or value["source_job_graph"] != job_graph
            or type(value["source_job_graph"].get("schema_version")) is not int):
        raise coverage.CoverageError("baseline does not certify the complete protected source job graph")
    targets = {row["bundle_key"]: row for row in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]}
    if (not isinstance(value["targets"], list) or len(value["targets"]) != len(targets)
            or not isinstance(value["review_artifacts"], dict) or set(value["review_artifacts"]) != set(targets)
            or not isinstance(value["public_artifacts"], dict) or set(value["public_artifacts"]) != set(targets)):
        raise coverage.CoverageError("baseline lacks the complete target and review inventory")
    matrix = coverage.load_matrix(coverage.DEFAULT_MATRIX)
    captures = sum(step.capture is not None for name in contract.scenarios_for_profile("pr")
                   for role in contract.scenario(name).roles for step in role.steps)
    seen_targets, source_ids, report_ids, owner_ids = set(), set(), set(), set()
    for target in value["targets"]:
        if not isinstance(target, dict) or set(target) != TARGET_KEYS:
            raise coverage.CoverageError("baseline target has an unknown coverage schema")
        key = target["bundle_key"]
        if not isinstance(key, str) or key not in targets or key in seen_targets:
            raise coverage.CoverageError("baseline has a duplicate or foreign target")
        seen_targets.add(key)
        lanes = sorted(row["artifact_node"] for row in coverage.select_release_target(
            matrix, targets[key]["minecraft_target"])["artifacts"])
        identifiers = target["source_artifact_ids"]
        if (target["artifact_nodes"] != lanes or type(target["frame_count"]) is not int
                or target["frame_count"] != len(lanes) * captures
                or not isinstance(identifiers, list) or len(identifiers) != len(lanes)
                or any(type(item) is not int or item <= 0 or item in source_ids for item in identifiers)
                or len(set(identifiers)) != len(identifiers)
                or not all(_hash(target[field]) for field in ("proof_sha256", "manifest_sha256", "report_sha256"))):
            raise coverage.CoverageError("baseline target has partial, duplicate or malformed coverage")
        source_ids.update(identifiers)
        review = value["review_artifacts"][key]
        if (not isinstance(review, dict) or set(review) != REVIEW_KEYS
                or review["name"] != f"visual-review-{run_id}--{key}"
                or not isinstance(review["digest"], str)
                or not review["digest"].startswith("sha256:") or not _hash(review["digest"][7:])
                or type(review["size_in_bytes"]) is not int
                or not 0 < review["size_in_bytes"] <= coverage.MAX_REPORT_ARCHIVE_BYTES
                or type(review["id"]) is not int or review["id"] <= 0 or review["id"] in report_ids
                or type(review["owner_run_id"]) is not int or review["owner_run_id"] <= 0
                or review["owner_run_id"] in owner_ids):
            raise coverage.CoverageError("baseline has malformed or duplicate review provenance")
        report_ids.add(review["id"])
        owner_ids.add(review["owner_run_id"])
    public_ids = set()
    for key, record in value["public_artifacts"].items():
        publisher.validate_public_record(record, bundle_key=key, source_sha=source, source_run_id=run_id)
        if record["id"] in public_ids | source_ids | report_ids | {metadata["id"]}:
            raise coverage.CoverageError("baseline reuses a public artifact identity")
        public_ids.add(record["id"])
    if source_ids.intersection(report_ids | {metadata["id"]}):
        raise coverage.CoverageError("baseline reuses an artifact identity for different evidence")
    return fingerprints


def read_baseline(api: publisher.Api, metadata: dict[str, Any], directory: Path) -> dict[str, Any]:
    directory.mkdir()
    archive = directory / "baseline.zip"
    api.download(metadata, archive, maximum=MAX_ARCHIVE_BYTES)
    extracted = directory / "contents"
    extract_bounded_zip(archive, extracted, ExtractionLimits(
        archive_bytes=MAX_ARCHIVE_BYTES, entries=2, total_bytes=MAX_BASELINE_BYTES,
        file_bytes=MAX_BASELINE_BYTES))
    if {path.relative_to(extracted).as_posix() for path in extracted.rglob("*")} != {"baseline.json"}:
        raise coverage.CoverageError("baseline archive must contain only its bounded certificate")
    value, _digest = coverage._read(extracted / "baseline.json")
    return value


def _from_artifact(api: publisher.Api, artifact: Any, *, repository: Path, head: str,
                   policy: str, directory: Path) -> tuple[Any, dict[str, Any]]:
    candidate = parse_artifact(artifact)
    owner = api.run(candidate.run_id)
    metadata = validate_owner(artifact, owner, api.jobs(owner), github_repository=api.repository)
    value = read_baseline(api, metadata, directory)
    before = validate_baseline(value, metadata, repository=repository, head=head, policy=policy)
    selected = coverage.admission.admit(repository, base=metadata["source_sha"], head=head, policy=policy)
    if not selected.enabled:
        return selected, {"schema_version": 1, "selective": False, "reason": selected.reason}
    after = coverage.module_fingerprints(repository, head)
    unaffected = sorted(set(before) - set(selected.require_selection().affected_modules))
    if any(before[key] != after[key] for key in unaffected):
        raise coverage.CoverageError("selection omitted a changed dependency fingerprint")
    public_owners = {}
    for key, record in value["public_artifacts"].items():
        if record["owner_run_id"] not in public_owners:
            owner = api.run(record["owner_run_id"])
            public_owners[record["owner_run_id"]] = (owner, api.jobs(owner))
        owner, jobs = public_owners[record["owner_run_id"]]
        actual = publisher.validate_public_owner(api.artifact(record["id"]), owner, jobs,
            github_repository=api.repository, source_sha=metadata["source_sha"],
            source_run_id=value["source_run_id"], bundle_key=key)
        if actual != record:
            raise coverage.CoverageError("complete public baseline expired or changed after certification")
    return selected, {"schema_version": 1, "selective": True, "reason": selected.reason,
        "selection_sha256": selected.sha256, "baseline": metadata,
        "baseline_sha256": coverage.digest(coverage.admission.canonical(value)),
        "public_baseline_artifacts": value["public_artifacts"], "baseline_source_run_id": value["source_run_id"],
        "unchanged_module_fingerprints": {key: before[key] for key in unaffected}}


def verify(api: publisher.Api, selection_path: Path, coverage_path: Path, *, repository: Path,
           head: str, policy: str, run_id: int, directory: Path,
           pull_number: int | None = None, merged_reference: dict | None = None) -> tuple[Any, dict[str, Any]]:
    """Reauthenticate exact published evidence; supplied identities never choose the tested source."""
    authenticate_execution(api, repository, run_id=run_id, tested_sha=head,
                           policy_sha=policy, pull_number=pull_number, merged_reference=merged_reference)
    source = api.run(run_id)
    if source.get("status") != "completed" or source.get("conclusion") != "success":
        raise coverage.CoverageError("selected evidence requires a successful completed source run")
    coverage.validate_job_graph(api.jobs(source), policy="full", expected_scenarios=
                                coverage.expected_scenario_jobs_for(coverage.DEFAULT_MATRIX, "pr-anchors"))
    supplied, _digest = coverage._read(coverage_path)
    metadata = supplied.get("baseline") if isinstance(supplied, dict) else None
    if not isinstance(metadata, dict):
        raise coverage.CoverageError("selected evidence has no complete baseline provenance")
    identifier = coverage._positive_integer(metadata.get("id"), "baseline artifact")
    selected, proof = _from_artifact(api, api.artifact(identifier), repository=repository,
                                     head=head, policy=policy, directory=directory)
    selected.require_selection()
    selection, _digest = coverage._read(selection_path)
    if (coverage.admission.canonical(supplied) != coverage.admission.canonical(proof)
            or coverage.admission.canonical(selection) != selected.to_bytes()):
        raise coverage.CoverageError("selected evidence differs from its independently authenticated coverage")
    authenticate_execution(api, repository, run_id=run_id, tested_sha=head,
                           policy_sha=policy, pull_number=pull_number, merged_reference=merged_reference)
    return selected, proof


def resolve(api: publisher.Api, *, repository: Path, head: str, policy: str,
            run_id: int, directory: Path, pull_number: int | None = None) -> tuple[Any, dict[str, Any]]:
    """Return a cumulative Git admission plus provenance, or an explicit complete-profile fallback."""
    fallback = coverage.admission.admit(repository, base=None, head=head, policy=policy)
    reason = "healthy-baseline-unavailable"
    try:
        authenticate_execution(api, repository, run_id=run_id, tested_sha=head,
                               policy_sha=policy, pull_number=pull_number)
        candidates = api.artifacts(name=coverage.BASELINE_ARTIFACT_NAME)
        parsed = sorted((parse_artifact(item) for item in candidates), key=lambda item: item.order, reverse=True)
        by_id = {item["id"]: item for item in candidates}
        if len(by_id) != len(parsed):
            raise coverage.CoverageError("baseline discovery contains duplicate artifact identities")
        for candidate in parsed[:MAX_CANDIDATES]:
            if candidate.expired:
                continue
            owner = api.run(candidate.run_id)
            if owner.get("status") != "completed":
                continue
            selected, proof = _from_artifact(api, by_id[candidate.artifact_id], repository=repository,
                head=head, policy=policy, directory=directory / str(candidate.artifact_id))
            authenticate_execution(api, repository, run_id=run_id, tested_sha=head,
                                   policy_sha=policy, pull_number=pull_number)
            return selected, proof
    except (OSError, ValueError, QueueError, publisher.subprocess.SubprocessError):
        reason = "healthy-baseline-or-execution-unproven"
    return fallback, {"schema_version": 1, "selective": False, "reason": reason}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--policy", required=True)
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--pull-number", type=int)
    parser.add_argument("--verify-selection", type=Path)
    parser.add_argument("--verify-coverage", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if (args.verify_selection is None) != (args.verify_coverage is None):
        parser.error("evidence verification requires both selection and coverage manifests")
    if args.output.exists():
        parser.error("coverage output must be a new directory")
    try:
        with tempfile.TemporaryDirectory(prefix="qsm-feature-coverage-") as temporary:
            api = publisher.Api(args.github_repository)
            arguments = {"repository": args.repository, "head": args.head, "policy": args.policy,
                         "run_id": args.run_id, "pull_number": args.pull_number}
            if args.verify_selection is not None:
                selected, proof = verify(api, args.verify_selection, args.verify_coverage,
                    directory=Path(temporary) / "verified", **arguments)
            else:
                selected, proof = resolve(api, directory=Path(temporary), **arguments)
        args.output.mkdir(parents=True)
        (args.output / "selection.json").write_bytes(selected.to_bytes())
        (args.output / "coverage.json").write_bytes(coverage.admission.canonical(proof))
        result = {"selective": selected.enabled, "reason": proof["reason"],
                  "base": selected.base_commit or "", "head": selected.head_commit,
                  "policy": selected.policy_commit, "selection_sha256": selected.sha256,
                  "scenarios": ",".join(run.scenario for run in selected.runs) if selected.enabled else ""}
        print(json.dumps(result, sort_keys=True))
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as stream:
                for key, value in result.items():
                    stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    except (OSError, ValueError) as exc:
        parser.exit(2, f"Feature coverage could not establish executing policy: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
