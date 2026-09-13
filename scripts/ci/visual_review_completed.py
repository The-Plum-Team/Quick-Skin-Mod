#!/usr/bin/env python3
"""Recover a fully validated report whose protected owner was cancelled during publication."""
from __future__ import annotations

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import tempfile

from feature_coverage_github import Api, _review_files, coverage
from visual_review_preparation import SHA, WORKFLOW
from visual_review_queue import REPORT_NAME, parse_artifact, valid_owner
from check_visual_review import load, validate


def cache_owner_complete(api: Api, owner_id: int, artifact_id: int, *, expected_metadata: dict) -> bool:
    """The exact successful immutable upload commits the union, not later job termination.

    The serialized writer can retire consumed shards after this point. Cancellation or failure
    in a later certification/cleanup step must not make that committed replacement disappear.
    """
    owner = api.run(owner_id)
    if (owner.get("head_branch") != "master" or owner.get("path") != WORKFLOW
            or owner.get("status") not in {"in_progress", "completed"}
            or not isinstance(owner.get("head_sha"), str) or SHA.fullmatch(owner["head_sha"]) is None
            or owner.get("event") not in coverage.DRAIN_EVENTS
            or (owner.get("head_repository") or {}).get("full_name") != api.repository):
        return False
    jobs = [job for page in api.jobs(owner) for job in page["jobs"]
            if job.get("name") == "Review one queued capsule"]
    if len(jobs) != 1 or jobs[0].get("status") not in {"in_progress", "completed"}:
        return False
    selected = []
    for name in ("Independently validate the normalized result", "Publish the protected exact-policy verdict cache",
                 "Upload the protected exact-policy verdict cache"):
        matching = [step for step in jobs[0].get("steps", []) if step.get("name") == name]
        if len(matching) != 1 or matching[0].get("status") != "completed" or matching[0].get("conclusion") != "success":
            return False
        selected.append(matching[0])
    artifact = api.artifact(artifact_id)
    if artifact != expected_metadata:
        return False
    artifact_owner = artifact.get("workflow_run") or {}
    if (not isinstance(artifact.get("name"), str)
            or re.fullmatch(r"visual-review-verdict-cache-[0-9a-f]{64}", artifact["name"]) is None
            or artifact.get("expired") is not False
            or type(artifact.get("size_in_bytes")) is not int
            or not 0 < artifact["size_in_bytes"] <= coverage.MAX_REPORT_ARCHIVE_BYTES
            or not isinstance(artifact.get("digest"), str)
            or re.fullmatch(r"sha256:[0-9a-f]{64}", artifact["digest"]) is None
            or artifact_owner.get("id") != owner_id or artifact_owner.get("head_sha") != owner["head_sha"]
            or artifact_owner.get("head_branch") != "master"):
        return False
    return _time(selected[-1]["started_at"]) <= _time(artifact["created_at"]) <= _time(selected[-1]["completed_at"])


def _time(value: str) -> datetime:
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("report publication timestamp lacks its timezone")
    return result


def recover(api: Api, *, capsule: Path, implementation_sha: str, review_key: str) -> bool:
    if not isinstance(implementation_sha, str) or SHA.fullmatch(implementation_sha) is None:
        raise ValueError("report recovery requires an exact protected implementation")
    proof_path = capsule / "curation-proof.json"
    manifest_path = capsule / "review-input/visual-review-manifest.json"
    manifest = load(manifest_path, "current prepared manifest")
    proof = load(proof_path, "current prepared proof")
    name = "visual-review-" + review_key
    if REPORT_NAME.fullmatch(name) is None:
        raise ValueError("report recovery requires an exact source/target key")
    candidates = api.artifacts(name=name)
    if len(candidates) > 8:
        raise ValueError("cancelled report recovery exceeds its owner bound")
    for candidate in sorted(candidates, key=lambda item: item["id"], reverse=True):
        parsed = parse_artifact(candidate)
        if parsed.expired:
            continue
        if parsed.size_in_bytes > coverage.MAX_REPORT_ARCHIVE_BYTES:
            raise ValueError("cancelled report exceeds its byte limit")
        owner_id = parsed.run_id
        owner = api.run(owner_id)
        if (parsed.head_sha != implementation_sha or not valid_owner(
                owner, repository=api.repository, artifact=parsed, workflow=WORKFLOW,
                events=coverage.DRAIN_EVENTS, conclusions=frozenset({"cancelled"}))):
            continue
        jobs = [job for page in api.jobs(owner) for job in page["jobs"]
                if job.get("name") == "Review one queued capsule"]
        if len(jobs) != 1:
            raise ValueError("cancelled report has an ambiguous reviewer")
        steps = jobs[0].get("steps", [])
        selected = []
        for step_name in ("Independently validate the normalized result", "Upload the source-bound normalized report"):
            matching = [step for step in steps if step.get("name") == step_name]
            if len(matching) != 1 or matching[0].get("conclusion") != "success" or matching[0].get("status") != "completed":
                break
            selected.append(matching[0])
        if len(selected) != 2:
            continue
        metadata = api.artifact(candidate["id"])
        if (metadata != candidate or metadata.get("name") != name
                or metadata["workflow_run"].get("head_sha") != implementation_sha
                or metadata["workflow_run"].get("head_branch") != "master"):
            raise ValueError("cancelled report metadata changed before recovery")
        # Reports predate attempt-bound names. Bind this immutable artifact to the successful
        # upload interval in the exact attempt instead of borrowing steps from a newer retry.
        uploaded_at = _time(metadata["created_at"])
        if not _time(selected[1]["started_at"]) <= uploaded_at <= _time(selected[1]["completed_at"]):
            continue
        with tempfile.TemporaryDirectory(prefix="visual-completed-") as temporary:
            files = _review_files(api, metadata, Path(temporary) / "report")
            previous_manifest = load(files.manifest, "cancelled manifest")
            previous_proof = load(files.proof, "cancelled proof")
            completion = load(files.report.parent / "visual-review-completion.json", "completed report state")
            expected = {"schema_version": 1, "state": "complete", "manifest_frames": len(previous_manifest),
                        "report_verdicts": len(previous_manifest)}
            if (completion != expected or any(type(completion.get(field)) is not int
                    for field in ("schema_version", "manifest_frames", "report_verdicts"))):
                raise ValueError("cancelled report is incomplete")
            verdicts = validate(previous_manifest, load(files.report, "cancelled normalized report"),
                                require_paired=previous_proof["review_mode"] == "reference-comparison")
            if not all(item["semantic_valid"] and not item["defect"] for item in verdicts):
                raise ValueError("cancelled report is not a complete clean result")
            if files.proof.read_bytes() != proof_path.read_bytes() or files.manifest.read_bytes() != manifest_path.read_bytes():
                # The report name does not contain the runtime attempt. A valid older cancelled
                # report must not block a newly authenticated capsule under the same source ID.
                # Never reuse its verdicts; continue to another exact candidate or fresh review.
                continue
            for filename, value in (("visual-review-report.staged.json", verdicts),
                                    ("visual-review-completion.json", expected)):
                with (capsule / filename).open("x") as stream:
                    json.dump(value, stream)
            with (capsule / "visual-review-telemetry.json").open("x") as stream:
                json.dump({"schema_version": 1, "state": "complete",
                           "model_attempts": {"retries": 0, "total": 0, "triage": 0,
                                              "triage_chunks": 0, "verify": 0, "verify_chunks": 0},
                           "review_plan": {"frames": len(manifest), "recovered": len(manifest)}}, stream)
            return True
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--cache-owner-run-id", type=int)
    parser.add_argument("--cache-artifact-id", type=int)
    parser.add_argument("--cache-metadata", type=Path)
    parser.add_argument("--capsule", type=Path)
    parser.add_argument("--implementation-sha")
    parser.add_argument("--review-key")
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if args.cache_owner_run_id is not None:
        if args.cache_artifact_id is None or args.cache_metadata is None:
            parser.error("cache commit authentication requires the exact artifact ID and inventory metadata")
        metadata = load(args.cache_metadata, "candidate cache inventory metadata")
        return 0 if cache_owner_complete(Api(args.repository), args.cache_owner_run_id,
            args.cache_artifact_id, expected_metadata=metadata) else 1
    if any(value is None for value in (args.capsule, args.implementation_sha, args.review_key, args.github_output)):
        parser.error("report recovery requires capsule, implementation, review key and output")
    recovered = recover(Api(args.repository), capsule=args.capsule,
                        implementation_sha=args.implementation_sha, review_key=args.review_key)
    with args.github_output.open("a") as stream:
        stream.write(f"recovered={str(recovered).lower()}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
