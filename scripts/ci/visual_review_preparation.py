#!/usr/bin/env python3
"""Authenticate a same-run secretless preparation before the serialized reviewer uses it."""
from __future__ import annotations

import argparse
import hashlib
import re
import tempfile
from pathlib import Path
from typing import Any

from bounded_zip import ExtractionLimits, extract_bounded_zip
from feature_coverage_github import Api, _get

MAX_CAPSULE_BYTES = 512 * 1024 * 1024
MAX_PREPARED_BYTES = MAX_CAPSULE_BYTES + 1024 * 1024
WORKFLOW = ".github/workflows/visual-review-drain.yml"
PREPARE_JOB = "Prepare one queued capsule without model credentials"
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^[0-9a-f]{64}$")


def _positive(value: Any) -> int:
    if type(value) is not int or value <= 0:
        raise ValueError("preparation has an invalid integer identity")
    return value


def archive_digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def restore(api: Api, *, run_id: int, run_attempt: int, workflow_sha: str,
            artifact_id: int, artifact_digest: str, capsule_id: int, capsule_digest: str,
            capsule_size: int, output: Path) -> None:
    for value in (run_id, run_attempt, artifact_id, capsule_id, capsule_size):
        _positive(value)
    if (not SHA.fullmatch(workflow_sha) or not DIGEST.fullmatch(artifact_digest)
            or not DIGEST.fullmatch(capsule_digest) or capsule_size > MAX_CAPSULE_BYTES):
        raise ValueError("preparation has an invalid digest or byte limit")
    if output.exists() or output.is_symlink():
        raise ValueError("preparation output already exists")
    owner = api.run(run_id)
    if (owner.get("run_attempt") != run_attempt or owner.get("head_sha") != workflow_sha
            or owner.get("head_branch") != "master" or owner.get("path") != WORKFLOW
            or owner.get("status") != "in_progress" or owner.get("conclusion") is not None
            or owner.get("event") not in {"repository_dispatch", "schedule", "workflow_dispatch"}
            or (owner.get("head_repository") or {}).get("full_name") != api.repository):
        raise ValueError("preparation belongs to another protected execution")
    jobs = [job for page in api.jobs(owner) for job in page["jobs"] if job.get("name") == PREPARE_JOB]
    if (len(jobs) != 1 or jobs[0].get("status") != "completed"
            or jobs[0].get("conclusion") != "success"):
        raise ValueError("secretless preparation did not succeed in this exact attempt")
    metadata = api.artifact(artifact_id)
    expected_name = f"visual-review-prepared-{run_id}-{run_attempt}-{capsule_id}"
    artifact_owner = metadata.get("workflow_run") or {}
    size = _positive(metadata.get("size_in_bytes"))
    if (metadata.get("name") != expected_name or metadata.get("expired") is not False
            or size > MAX_PREPARED_BYTES or metadata.get("digest") != "sha256:" + artifact_digest
            or artifact_owner.get("id") != run_id or artifact_owner.get("head_sha") != workflow_sha
            or artifact_owner.get("head_branch") != "master"):
        raise ValueError("prepared artifact differs from its authenticated handoff")
    with tempfile.TemporaryDirectory(prefix="visual-prepared-", dir=output.parent) as temporary:
        root = Path(temporary)
        wrapper = root / "prepared.zip"
        raw = api.prepared_bytes(artifact_id, maximum=MAX_PREPARED_BYTES)
        if len(raw) != size or hashlib.sha256(raw).hexdigest() != artifact_digest:
            raise ValueError("prepared archive differs from its immutable metadata")
        wrapper.write_bytes(raw)
        del raw
        extracted = root / "wrapper"
        extract_bounded_zip(wrapper, extracted, ExtractionLimits(
            archive_bytes=MAX_PREPARED_BYTES, entries=1, total_bytes=MAX_CAPSULE_BYTES,
            file_bytes=MAX_CAPSULE_BYTES, compression_ratio=200))
        if {path.name for path in extracted.iterdir()} != {"prepared-visual-review.zip"}:
            raise ValueError("preparation has a foreign file inventory")
        capsule = extracted / "prepared-visual-review.zip"
        if capsule.stat().st_size != capsule_size or archive_digest(capsule) != capsule_digest:
            raise ValueError("preparation changed the original immutable capsule")
        extract_bounded_zip(capsule, output, ExtractionLimits(
            archive_bytes=MAX_CAPSULE_BYTES, entries=520, total_bytes=MAX_CAPSULE_BYTES,
            file_bytes=32 * 1024 * 1024, compression_ratio=200))
        if {path.name for path in output.iterdir()} != {"curation-proof.json", "review-input"}:
            raise ValueError("prepared capsule has a foreign file inventory")


class PreparationApi(Api):
    def prepared_bytes(self, artifact_id: int, *, maximum: int) -> bytes:
        return _get(self.prefix + f"actions/artifacts/{artifact_id}/zip", maximum=maximum)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    for name in ("run-id", "run-attempt", "artifact-id", "capsule-id", "capsule-size"):
        parser.add_argument("--" + name, type=int, required=True)
    for name in ("workflow-sha", "artifact-digest", "capsule-digest"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = vars(parser.parse_args())
    repository = args.pop("repository")
    restore(PreparationApi(repository), **args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
