#!/usr/bin/env python3
"""Authenticate selective public evidence and compose it with its complete retained baseline."""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path
from typing import Any

import feature_coverage as coverage
import feature_coverage_consumer as consumer
import feature_coverage_github as publisher
from bounded_zip import ExtractionLimits, extract_bounded_zip

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pages"))
import evidence
import feature_evidence


def collect(api: publisher.Api, *, repository: Path, evidence_root: Path, output: Path,
            bundle_key: str, source_sha: str, artifact_run_id: int | None, scratch: Path) -> Path:
    # Read only bounded JSON before authenticating the source, baseline and retained public owner.
    manifest, _digest = coverage._read(evidence_root / bundle_key / "manifest.json")
    if (not isinstance(manifest, dict) or type(manifest.get("schema_version")) is not int
            or manifest["schema_version"] not in {evidence.SELECTED_RAW_SCHEMA_VERSION, evidence.COMPOSED_SCHEMA_VERSION}
            or manifest.get("repository") != api.repository):
        raise coverage.CoverageError("feature Pages collection requires a selected handoff or composed cache")
    provenance = manifest.get("provenance")
    if (not isinstance(provenance, dict) or set(provenance) != {"source", "target"}
            or not isinstance(provenance["source"], dict) or provenance["source"] != provenance["target"]
            or provenance["source"].get("sha") != source_sha
            or provenance["source"].get("branch") != "master"):
        raise coverage.CoverageError("selected public handoff substituted its current tested source")
    run_text = provenance["source"].get("run_id")
    if not isinstance(run_text, str) or not run_text.isascii() or not run_text.isdigit() or str(int(run_text)) != run_text:
        raise coverage.CoverageError("selected public source run must be an exact positive identifier")
    source_run_id = coverage._positive_integer(int(run_text), "selected public source run")
    if artifact_run_id is not None and artifact_run_id != source_run_id:
        raise coverage.CoverageError("selected handoff owner differs from its claimed runtime")
    feature = manifest.get("feature_selection")
    feature_evidence.read_selection(feature)
    selection_path, coverage_path = scratch / "selection.json", scratch / "coverage.json"
    selection_path.write_bytes(coverage.admission.canonical(feature["admission"]))
    coverage_path.write_bytes(coverage.admission.canonical(feature["coverage"]))
    selected, proof = consumer.verify(api, selection_path, coverage_path, repository=repository,
        head=source_sha, policy=source_sha, run_id=source_run_id, directory=scratch / "certificate")
    source = api.run(source_run_id)
    if source.get("created_at") != provenance["source"].get("created_at"):
        raise coverage.CoverageError("selected public source timestamp differs from its authenticated run")
    source_baseline_run = proof["baseline_source_run_id"]
    metadata = proof["public_baseline_artifacts"].get(bundle_key)
    if metadata is None:
        raise coverage.CoverageError("complete public baseline does not contain this target")
    artifact = api.artifact(metadata["id"])
    owner = api.run(metadata["owner_run_id"])
    authenticated = publisher.validate_public_owner(artifact, owner, api.jobs(owner),
        github_repository=api.repository, source_sha=selected.base_commit,
        source_run_id=source_baseline_run, bundle_key=bundle_key)
    if authenticated != metadata:
        raise coverage.CoverageError("retained public baseline differs from the complete coverage certificate")
    raw = publisher._get(api.prefix + f"actions/artifacts/{metadata['id']}/zip",
                         maximum=publisher.MAX_PUBLIC_ARCHIVE_BYTES)
    if len(raw) != metadata["size_in_bytes"] or "sha256:" + coverage.digest(raw) != metadata["digest"]:
        raise coverage.CoverageError("retained public baseline differs from its immutable archive digest")
    archive = scratch / "public-baseline.zip"
    archive.write_bytes(raw)
    baseline_root = scratch / "public-baseline"
    extract_bounded_zip(archive, baseline_root, ExtractionLimits(
        archive_bytes=publisher.MAX_PUBLIC_ARCHIVE_BYTES, entries=evidence.MAX_BUNDLE_ENTRIES + 1,
        total_bytes=256 * 1024 * 1024, file_bytes=max(evidence.MAX_IMAGE_BYTES, evidence.MAX_MANIFEST_BYTES)))
    baseline = evidence.validate_bundle(baseline_root, bundle_key, only_branch=True, expected_kind="compact",
        expected_repository=api.repository, expected_source_run_id=str(source_baseline_run),
        expected_target_run_id=str(source_baseline_run), expected_target_sha=selected.base_commit,
        expected_coverage_sha=selected.base_commit)
    if baseline["schema_version"] != evidence.SHARED_COMPACT_SCHEMA_VERSION:
        raise coverage.CoverageError("public baseline was not produced by complete runtime coverage")
    arguments = {"only_branch": True, "expected_repository": api.repository,
                 "expected_source_run_id": str(source_run_id), "expected_target_run_id": str(source_run_id),
                 "expected_target_sha": source_sha, "expected_coverage_sha": source_sha}
    staged_output = scratch / "candidate"
    if manifest["schema_version"] == evidence.COMPOSED_SCHEMA_VERSION:
        evidence.validate_bundle(evidence_root, bundle_key, expected_kind="compact", **arguments)
        digest = evidence.sha256_file(baseline_root / bundle_key / "manifest.json")
        if manifest["components"]["baseline_sha256"] != digest:
            raise coverage.CoverageError("composed cache substituted its independently authenticated baseline")
        # All source images in the nested component have already been checked against that manifest.
        result = evidence.compact_bundle(evidence_root, staged_output, bundle_key,
            expected_input_kind="compact", **arguments)
    else:
        compact_root = scratch / "selected-compact"
        evidence.compact_bundle(evidence_root, compact_root, bundle_key,
            expected_input_kind="raw", selection=selected, **arguments)
        result = feature_evidence.compose(baseline_root, compact_root, staged_output, bundle_key, selection=selected)
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("source advanced during selected public evidence collection")
    output.mkdir(parents=True, exist_ok=True)
    destination = output / bundle_key
    if destination.exists():
        raise coverage.CoverageError("selected public evidence output must be new")
    result.rename(destination)
    return destination


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--bundle-key", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--artifact-run-id", type=int)
    args = parser.parse_args()
    try:
        with tempfile.TemporaryDirectory(prefix="qsm-feature-pages-") as temporary:
            result = collect(publisher.Api(args.github_repository), repository=args.repository,
                evidence_root=args.evidence_root, output=args.output, bundle_key=args.bundle_key,
                source_sha=args.source_sha, artifact_run_id=args.artifact_run_id, scratch=Path(temporary))
        print(json.dumps({"bundle": str(result), "coverage_sha": args.source_sha}, sort_keys=True))
    except (OSError, ValueError, publisher.subprocess.SubprocessError) as exc:
        parser.exit(2, f"Selected public evidence admission failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
