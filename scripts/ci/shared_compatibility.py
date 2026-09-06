#!/usr/bin/env python3
"""Admit a per-target optional-mod wave from a complete shared-source visual review."""
from __future__ import annotations

import argparse
import json
import re
import tempfile
import time
from pathlib import Path
from typing import Any

import feature_review as review
from mod_compatibility import build_plan

coverage = review.coverage
publisher = review.publisher
SOURCE_FIELDS = frozenset({"source_run_id", "source_branch", "source_sha", "target_branch", "target_sha"})
REQUEST_FIELDS = SOURCE_FIELDS | {"review_artifact_id", "review_artifact_name", "review_artifact_run_id",
                                  "review_artifact_size", "review_artifact_digest"}


def request_identity(payload: Any, policy_sha: str) -> dict[str, Any]:
    if (not isinstance(payload, dict) or set(payload) != REQUEST_FIELDS
            or any(not isinstance(value, str) for value in payload.values())
            or coverage.admission.SHA.fullmatch(policy_sha) is None
            or payload["source_branch"] != "master" or payload["target_branch"] != "master"
            or payload["source_sha"] != policy_sha or payload["target_sha"] != policy_sha):
        raise coverage.CoverageError("optional-mod request must name the exact current shared source")
    result = dict(payload)
    for key in ("source_run_id", "review_artifact_id", "review_artifact_run_id", "review_artifact_size"):
        if re.fullmatch(r"[1-9][0-9]{0,19}", payload[key]) is None:
            raise coverage.CoverageError("optional-mod request has an invalid immutable identity")
        result[key] = int(payload[key])
    prefix = f"visual-review-{result['source_run_id']}--"
    if not payload["review_artifact_name"].startswith(prefix):
        raise coverage.CoverageError("optional-mod request names another normalized source review")
    key = payload["review_artifact_name"][len(prefix):]
    if key not in {item["bundle_key"] for item in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]}:
        raise coverage.CoverageError("optional-mod request names a foreign matrix target")
    if (result["review_artifact_size"] > coverage.MAX_REPORT_ARCHIVE_BYTES
            or re.fullmatch(r"sha256:[0-9a-f]{64}", payload["review_artifact_digest"]) is None):
        raise coverage.CoverageError("optional-mod report exceeds its metadata limits")
    return {**result, "bundle_key": key, "minecraft_target": review.targets.bundle_version(key)}


def admit(api: publisher.Api, payload: Any, *, repository: Path, policy_sha: str,
          directory: Path) -> dict[str, Any] | None:
    request = request_identity(payload, policy_sha)
    source = api.run(request["source_run_id"])
    kind = "native-anchors" if source.get("event") == "schedule" else "pr-anchors"
    runtime = review.authenticate_full(api, repository, source_sha=policy_sha,
        source_run_id=request["source_run_id"], matrix_kind=kind)
    artifact = api.artifact(request["review_artifact_id"])
    if any(artifact.get(key) != request["review_artifact_" + field] for key, field in (
            ("id", "id"), ("name", "name"), ("digest", "digest"), ("size_in_bytes", "size"))):
        raise coverage.CoverageError("optional-mod request substituted its normalized review metadata")
    owner = api.run(request["review_artifact_run_id"])
    if owner.get("status") != "completed":
        return None  # The dispatching review workflow may still be finishing its final job.
    metadata = coverage.validate_review_owner(artifact, owner, api.jobs(owner),
        github_repository=api.repository, source_sha=policy_sha,
        source_run_id=request["source_run_id"], bundle_key=request["bundle_key"])
    if metadata["owner_run_id"] != request["review_artifact_run_id"]:
        raise coverage.CoverageError("optional-mod report belongs to another protected reviewer")
    artifacts = runtime.artifacts
    if any(item["name"] == coverage.SELECTION_ARTIFACT_NAME for item in artifacts):
        raise coverage.CoverageError("optional-mod wave requires a complete base runtime generation")
    plan = review.plan_source(runtime, kind)
    target = next(item for item in plan["include"] if item["bundle_key"] == request["bundle_key"])
    files = publisher._review_files(api, metadata, directory / "report")
    record = coverage.validate_clean_target(files, source_sha=policy_sha, source_run_id=request["source_run_id"],
        bundle_key=request["bundle_key"], matrix_kind=kind)
    proof, _digest = coverage._read(files.proof)
    if (proof["schema_version"] != 8 or proof.get("runtime_source") != runtime.reference
            or proof["compatibility_impact"]["compatibility_required"] is not True
            or proof["artifact_inventory"] != [review._artifact_record(item) for item in target["artifact_inventory"]]):
        raise coverage.CoverageError("optional-mod wave needs the exact complete runtime review and affected product")
    if proof["visual_reference"] is not None:
        reference = proof["visual_reference"]["artifact"]
        matches = [item for item in artifacts if item.get("id") == reference["id"]]
        if len(matches) != 1 or review._artifact_record(matches[0]) != reference:
            raise coverage.CoverageError("optional-mod base review substituted its same-run reference")
    completion, _digest = coverage._read(files.proof.parent / "visual-review-completion.json")
    if (completion != {"schema_version": 1, "state": "complete", "manifest_frames": record["frame_count"],
                       "report_verdicts": record["frame_count"]}
            or any(type(completion.get(key)) is not int for key in ("schema_version", "manifest_frames", "report_verdicts"))):
        raise coverage.CoverageError("optional-mod normalized review is incomplete")
    bundles = [item for item in artifacts if item["name"] == "e2e-input-bundle"]
    if (len(bundles) != 1 or bundles[0].get("expired") is not False
            or bundles[0].get("workflow_run", {}).get("id") != runtime.execution["id"]
            or bundles[0].get("workflow_run", {}).get("head_sha") != runtime.execution["head_sha"]
            or bundles[0].get("workflow_run", {}).get("head_branch") != runtime.execution["head_branch"]
            or type(bundles[0].get("size_in_bytes")) is not int
            or not 0 < bundles[0]["size_in_bytes"] <= 512 * 1024 * 1024
            or not isinstance(bundles[0].get("digest"), str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", bundles[0]["digest"]) is None):
        raise coverage.CoverageError("optional-mod wave has no unique bounded production input bundle")
    if api.current_sha() != policy_sha:
        raise coverage.CoverageError("optional-mod source advanced during admission")
    return {**{key: request[key] for key in SOURCE_FIELDS}, "base_matrix_kind": kind,
            "source_run_attempt": coverage._positive_integer(source.get("run_attempt"), "source attempt"),
            "runtime_run_id": runtime.execution["id"], "runtime_sha": runtime.tested_sha,
            "runtime_run_attempt": runtime.execution["run_attempt"],
            "runtime_reference": coverage.admission.canonical(runtime.reference).decode().strip(),
            "minecraft_target": request["minecraft_target"], "bundle_key": request["bundle_key"]}


def validate_plan(plan: Any, policy_sha: str) -> None:
    """Recompute every runnable and N/A row from the executing protected matrix and mod lock."""
    if (not isinstance(plan, dict) or type(plan.get("schema_version")) is not int or plan["schema_version"] != 2
            or plan.get("source_branch") != "master" or plan.get("target_branch") != "master"
            or plan.get("source_sha") != policy_sha or plan.get("target_sha") != policy_sha
            or coverage.admission.SHA.fullmatch(policy_sha) is None
            or type(plan.get("source_run_id")) is not int or plan["source_run_id"] <= 0
            or plan.get("base_matrix_kind") not in {"pr-anchors", "native-anchors"}
            or not isinstance(plan.get("minecraft_target"), str)):
        raise coverage.CoverageError("optional-mod plan has a foreign shared-source identity")
    expected = build_plan(coverage.DEFAULT_MATRIX, base_matrix_kind=plan["base_matrix_kind"],
                          minecraft_target=plan["minecraft_target"])
    expected.update({key: plan[key] for key in SOURCE_FIELDS})
    if "runtime_source" in plan:
        review.ci_reuse.validate_reference(plan["runtime_source"], "e2e")
        if plan["runtime_source"]["coverage_sha"] != policy_sha or plan["base_matrix_kind"] != "pr-anchors":
            raise coverage.CoverageError("optional-mod plan substituted its original runtime")
        expected["runtime_source"] = plan["runtime_source"]
    if coverage.admission.canonical(plan) != coverage.admission.canonical(expected):
        raise coverage.CoverageError("optional-mod plan differs from its protected target, mod lock or complete lane inventory")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository")
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--policy-sha", required=True)
    parser.add_argument("--event", type=Path)
    parser.add_argument("--validate-plan", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if (args.event is None) == (args.validate_plan is None):
        parser.error("provide one request event or plan to validate")
    try:
        if args.validate_plan is not None:
            plan, _digest = coverage._read(args.validate_plan)
            validate_plan(plan, args.policy_sha)
            return 0
        event, _digest = coverage._read(args.event)
        if not isinstance(event, dict):
            raise coverage.CoverageError("optional-mod dispatch event must be an object")
        api = publisher.Api(args.github_repository)
        for attempt in range(120):
            with tempfile.TemporaryDirectory(prefix="qsm-shared-compatibility-") as temporary:
                result = admit(api, event.get("client_payload"), repository=args.repository, policy_sha=args.policy_sha,
                               directory=Path(temporary).resolve())
            if result is not None:
                break
            if attempt == 119:
                raise coverage.CoverageError("protected review owner did not complete within its admission budget")
            time.sleep(5)
        print(json.dumps(result, sort_keys=True))
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as stream:
                for key, value in result.items():
                    stream.write(f"{key}={value}\n")
    except (OSError, ValueError, publisher.subprocess.SubprocessError) as exc:
        parser.exit(2, f"Shared compatibility admission failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
