#!/usr/bin/env python3
"""Curate and reauthenticate shared-source captures against their own runtime generation.

Review mode describes semantic versus paired judgment. The separate schema-7 feature selection
describes partial coverage; it cannot issue a complete anchor or healthy-baseline certificate.
Schema 8 carries complete coverage with a same-run reference, independent of Pages retention.
"""
from __future__ import annotations

import argparse
import json
import shutil
import tempfile
from pathlib import Path
from typing import Any

import feature_coverage as coverage
import feature_coverage_consumer as consumer
import feature_coverage_github as publisher
import visual_review_targets as targets
from bounded_zip import ExtractionLimits, extract_bounded_zip
from check_visual_review import validate_input
from selection import project_contract
from visual_review import (DEFAULT_CATALOG, build_manifest, collect_evidence, curate_manifest,
                           load_catalog, validate_expected_row)

SELECTION_ARTIFACT = coverage.SELECTION_ARTIFACT_NAME
MAX_SELECTION_ARCHIVE = 512 * 1024
MAX_RAW_BYTES = 512 * 1024 * 1024
MAX_RAW_ENTRIES = 768
FEATURE_FIELDS = frozenset({"admission", "coverage"})


def _source_artifact(artifact: Any, *, name: str, source_sha: str, source_run_id: int) -> dict[str, Any]:
    return targets._validate_artifacts([artifact], {name}, source_run_id=source_run_id,
                                       source_branch="master", source_sha=source_sha)[name]


def _selection_files(api: publisher.Api, artifact: Any, *, source_sha: str,
                     source_run_id: int, directory: Path) -> tuple[Path, Path]:
    metadata = _source_artifact(artifact, name=SELECTION_ARTIFACT, source_sha=source_sha,
                                source_run_id=source_run_id)
    if metadata["size_in_bytes"] > MAX_SELECTION_ARCHIVE:
        raise coverage.CoverageError("selected runtime admission exceeds its archive limit")
    directory.mkdir()
    archive = directory / "selection.zip"
    api.download(metadata, archive, maximum=MAX_SELECTION_ARCHIVE)
    extracted = directory / "contents"
    extract_bounded_zip(archive, extracted, ExtractionLimits(archive_bytes=MAX_SELECTION_ARCHIVE,
        entries=2, total_bytes=MAX_SELECTION_ARCHIVE, file_bytes=consumer.MAX_BASELINE_BYTES))
    if {path.relative_to(extracted).as_posix() for path in extracted.rglob("*")} != {"selection.json", "coverage.json"}:
        raise coverage.CoverageError("selected runtime archive has an unexpected file inventory")
    return extracted / "selection.json", extracted / "coverage.json"


def expected_captures(bundle_key: str, selected: Any, matrix_kind: str = "pr-anchors") -> tuple[dict[str, Any], list[str]]:
    matrix = coverage.load_matrix(coverage.DEFAULT_MATRIX)
    target = coverage.select_release_target(matrix, targets.bundle_version(bundle_key))
    nodes = sorted(row["artifact_node"] for row in target["artifacts"])
    contract = coverage.default_contract()
    scenarios = (project_contract(contract, selected).scenarios if selected is not None else
                 [contract.scenario(name) for name in contract.scenarios_for_profile(
                     "release" if matrix_kind == "native-anchors" else "pr")])
    captures = {f"{node}/{scenario.scenario}/{role.role}/{step.id}": step.capture
                for node in nodes for scenario in scenarios for role in scenario.roles
                for step in role.steps if step.capture is not None}
    return captures, nodes


def validate_selected_manifest(proof: dict[str, Any], manifest: Any, selected: Any) -> None:
    """Check the exact selected capture product before any normalized report can be considered."""
    expected, _nodes = expected_captures(proof["bundle_key"], selected, proof["matrix_kind"])
    if (not isinstance(manifest, list) or len(manifest) != len(expected)
            or any(not isinstance(item, dict) or not isinstance(item.get("label"), str) for item in manifest)
            or {item["label"] for item in manifest} != set(expected)
            or type(proof.get("frame_count")) is not int or proof["frame_count"] != len(expected)):
        raise coverage.CoverageError("selected capsule is missing exact target/loader/capture obligations")
    contract = coverage.default_contract()
    paired = proof["review_mode"] == "reference-comparison"
    reference_node = "fabric-" + coverage.load_matrix(coverage.DEFAULT_MATRIX)["unit_test_version"]
    for item in manifest:
        capture = expected[item["label"]]
        if (item.get("capture_id") != capture.capture_id or item.get("expectation") != capture.expectation
                or item.get("review_regions") != [list(region) for region in contract.review_regions[capture.capture_id]]
                or paired != ("reference_path" in item)
                or paired and item.get("reference_label") != reference_node + "/" + item["label"].split("/", 1)[1]):
            raise coverage.CoverageError("selected capsule altered an authored capture or its same-run reference")


def _raw_artifact(api: publisher.Api, metadata: dict[str, Any], directory: Path, *,
                  remaining_bytes: int, remaining_entries: int) -> tuple[Path, int, int]:
    directory.mkdir()
    raw = publisher._get(api.prefix + f"actions/artifacts/{metadata['id']}/zip",
                         maximum=targets.MAX_ARTIFACT_BYTES)
    if len(raw) != metadata["size_in_bytes"] or "sha256:" + coverage.digest(raw) != metadata["digest"]:
        raise coverage.CoverageError("packaged selected evidence differs from its immutable artifact metadata")
    archive = directory / "runtime.zip"
    archive.write_bytes(raw)
    extracted = directory / "contents"
    result = extract_bounded_zip(archive, extracted, ExtractionLimits(archive_bytes=targets.MAX_ARTIFACT_BYTES,
        entries=remaining_entries, total_bytes=remaining_bytes, file_bytes=targets.MAX_ARTIFACT_BYTES))
    return extracted, result["bytes"], result["entries"]


def _reference_frames(root: Path, selected: Any, artifact_node: str) -> dict[str, dict[str, Any]]:
    _lanes, frames, _comparisons = collect_evidence(root, load_catalog(DEFAULT_CATALOG, selection=selected))
    references = {}
    for frame in frames:
        if frame["artifact_node"] != artifact_node or frame["capture_id"] in references:
            raise coverage.CoverageError("selected reference contains a foreign or duplicate anchor frame")
        references[frame["capture_id"]] = {"path": frame["source_path"], "label": frame["frame_id"],
            "file_sha256": frame["file_sha256"], "pixel_sha256": frame["pixel_validation"]["pixel_sha256"],
            "width": frame["width"], "height": frame["height"], "format": "PNG"}
    return references


def _feature_paths(feature: Any, directory: Path) -> tuple[Path, Path]:
    if not isinstance(feature, dict) or set(feature) != FEATURE_FIELDS:
        raise coverage.CoverageError("selected curation proof lacks its complete authenticated admission")
    directory.mkdir()
    admission_path, coverage_path = directory / "selection.json", directory / "coverage.json"
    admission_path.write_bytes(coverage.admission.canonical(feature["admission"]))
    coverage_path.write_bytes(coverage.admission.canonical(feature["coverage"]))
    return admission_path, coverage_path


def _artifact_record(item: dict[str, Any]) -> dict[str, Any]:
    return {**{key: item[key] for key in ("id", "name", "size_in_bytes", "digest", "expired")},
            "workflow_run": {key: item["workflow_run"][key] for key in ("id", "head_branch", "head_sha")}}


def authenticate_full(api: publisher.Api, repository: Path, *, source_sha: str,
                      source_run_id: int, matrix_kind: str) -> None:
    """Admit the current protected policy and every runtime job before fetching any image."""
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("review source is no longer current master")
    coverage.policy_fingerprint(repository, source_sha, verify_executing=True)
    source = api.run(source_run_id)
    coverage.validate_source_run(source, api.jobs(source), github_repository=api.repository,
        source_sha=source_sha, source_run_id=source_run_id, matrix_kind=matrix_kind)


def reference_record(artifact: dict[str, Any], *, node: str, source_sha: str,
                     source_run_id: int, selected: Any) -> dict[str, Any]:
    result = {"evidence_kind": "packaged-full" if selected is None else "packaged-selection",
              "artifact": _artifact_record(artifact), "artifact_node": node,
              "source_sha": source_sha, "source_run_id": source_run_id}
    if selected is not None:
        result["selection_sha256"] = selected.sha256
    return result


def curate(api: publisher.Api, *, repository: Path, source_sha: str, source_run_id: int,
           bundle_key: str, compatibility_impact: Any, output: Path, scratch: Path,
           matrix_kind: str = "pr-anchors") -> dict[str, Any]:
    """Download only the target and, for paired review, its same-run Fabric reference."""
    authenticate_full(api, repository, source_sha=source_sha, source_run_id=source_run_id,
                       matrix_kind=matrix_kind)
    inventory = api.artifacts(run_id=source_run_id)
    admissions = [item for item in inventory if item["name"] == SELECTION_ARTIFACT]
    if len(admissions) > 1 or admissions and matrix_kind != "pr-anchors":
        raise coverage.CoverageError("shared runtime has ambiguous feature selection artifacts")
    selected, authenticated = None, None
    if admissions:
        paths = _selection_files(api, admissions[0], source_sha=source_sha, source_run_id=source_run_id,
                                 directory=scratch / "admission")
        selected, authenticated = consumer.verify(api, *paths, repository=repository,
            head=source_sha, policy=source_sha, run_id=source_run_id, directory=scratch / "baseline")
    coverage.validate_compatibility_impact(compatibility_impact)
    packaged = [item for item in inventory if item["name"].startswith("packaged-e2e-")]
    plan = targets.plan_targets(packaged, source_run_id=source_run_id,
                                source_branch="master", source_sha=source_sha, matrix_kind=matrix_kind)
    target = next((item for item in plan["include"] if item["bundle_key"] == bundle_key), None)
    if target is None:
        raise coverage.CoverageError("selected curation names a foreign matrix target")
    data, _matrix_digest, rows = targets._inventory(coverage.DEFAULT_MATRIX, matrix_kind)
    by_name = {"packaged-e2e-" + row["id"]: row for row in rows}
    required = list(target["artifact_inventory"])
    paired = target["review_mode"] == "reference-comparison"
    reference = None
    reference_node = "fabric-" + data["unit_test_version"]
    if paired:
        reference_names = {name for name, row in by_name.items() if row["artifact_node"] == reference_node}
        references = [item for item in packaged if item["name"] in reference_names]
        if len(references) != 1:
            raise coverage.CoverageError("selected review has no unique same-run Fabric reference")
        reference = references[0]
        required.append(reference)
    if (len(required) > targets.MAX_TARGET_ARTIFACTS
            or sum(item["size_in_bytes"] for item in required) > targets.MAX_TARGET_BYTES):
        raise coverage.CoverageError("selected target and reference exceed the aggregate artifact budget")
    raw_roots = {}
    remaining_bytes, remaining_entries = MAX_RAW_BYTES, MAX_RAW_ENTRIES
    for item in required:
        root, size, entries = _raw_artifact(api, item, scratch / str(item["id"]),
            remaining_bytes=remaining_bytes, remaining_entries=remaining_entries)
        remaining_bytes -= size
        remaining_entries -= entries
        row = by_name[item["name"]]
        if selected is not None:
            supplied_selection, _digest = coverage._read(root / "selection.json")
            supplied_coverage, _digest = coverage._read(root / "coverage.json")
            if (coverage.admission.canonical(supplied_selection) != selected.to_bytes()
                    or coverage.admission.canonical(supplied_coverage) != coverage.admission.canonical(authenticated)):
                raise coverage.CoverageError("runtime lane substituted its protected feature admission")
            row = {**row, "scenarios": ",".join(run.scenario for run in selected.runs)}
        elif any((root / name).exists() for name in ("selection.json", "coverage.json")):
            raise coverage.CoverageError("complete runtime lane carries partial feature coverage")
        validate_expected_row(root, DEFAULT_CATALOG, row, selection=selected)
        raw_roots[item["id"]] = root
    combined = scratch / "target"
    profiles = combined / "profiles"
    profiles.mkdir(parents=True)
    for item in target["artifact_inventory"]:
        for profile in (raw_roots[item["id"]] / "profiles").iterdir():
            if not profile.is_dir() or profile.is_symlink() or (profiles / profile.name).exists():
                raise coverage.CoverageError("selected runtime profiles have duplicate or unsafe ownership")
            shutil.move(str(profile), str(profiles / profile.name))
    reference_frames = (_reference_frames(raw_roots[reference["id"]], selected, reference_node)
                        if reference is not None else None)
    manifest = build_manifest(combined, DEFAULT_CATALOG, include_all=True, combos=None,
                              reference_frames=reference_frames, selection=selected)
    review_root = output / "review-input"
    proof_path = output / "curation-proof.json"
    if review_root.exists() or proof_path.exists():
        raise coverage.CoverageError("selected curation output must be new")
    manifest = curate_manifest(manifest, review_root)
    validate_input(manifest, review_root, require_paired=paired)
    images = list((review_root / "images").iterdir())
    image_bytes = sum(path.stat().st_size for path in images)
    manifest_path = review_root / "visual-review-manifest.json"
    expected_jobs = sorted(coverage.expected_scenario_jobs_for(coverage.DEFAULT_MATRIX, matrix_kind))
    proof = {"schema_version": 7 if selected is not None else 8,
        "bundle_key": bundle_key, "matrix_sha256": target["matrix_sha256"],
        "source_run_id": source_run_id, "source_branch": "master", "source_sha": source_sha,
        "master_source_sha": source_sha, "implementation_sha": source_sha, "matrix_kind": matrix_kind,
        "review_mode": target["review_mode"], "compatibility_impact": compatibility_impact,
        "scenario_contract_sha256": coverage.default_contract().sha256,
        "artifact_inventory": [_artifact_record(item) for item in target["artifact_inventory"]],
        "job_graph": {"schema_version": 1, "runtime_policy": "full",
                      "expected_scenario_jobs": expected_jobs, "observed_scenario_jobs": expected_jobs},
        "visual_reference": (reference_record(reference, node=reference_node,
            source_sha=source_sha, source_run_id=source_run_id, selected=selected) if reference is not None else None),
        "manifest_sha256": coverage.digest(manifest_path.read_bytes()), "frame_count": len(manifest),
        "image_count": len(images), "image_bytes": image_bytes}
    if selected is not None:
        proof["feature_selection"] = {"admission": selected.to_dict(), "coverage": authenticated}
    targets.validate_target_proof(proof, selection=selected)
    validate_selected_manifest(proof, manifest, selected)
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("shared source advanced during selected image curation")
    proof_path.write_bytes(coverage.admission.canonical(proof))
    return proof


def verify(api: publisher.Api, proof_path: Path, manifest_path: Path, *, repository: Path,
           source_sha: str, source_run_id: int, bundle_key: str, scratch: Path) -> Any:
    """Reauthenticate the complete baseline, source graph and selected capsule before model access."""
    proof, _digest = coverage._read(proof_path)
    manifest, manifest_digest = coverage._read(manifest_path)
    schema = proof.get("schema_version") if isinstance(proof, dict) else None
    fields = {"feature_selection"} if schema == 7 else set()
    if (not isinstance(proof, dict)
            or set(proof) != coverage.PROOF_KEYS | {"bundle_key", "matrix_sha256"} | fields
            or type(schema) is not int or schema not in {7, 8}
            or proof.get("source_sha") != source_sha or proof.get("source_run_id") != source_run_id
            or type(proof.get("source_run_id")) is not int or proof.get("bundle_key") != bundle_key
            or proof.get("manifest_sha256") != manifest_digest):
        raise coverage.CoverageError("selected curation proof has a foreign source, scope or manifest")
    selected = None
    if schema == 7:
        paths = _feature_paths(proof["feature_selection"], scratch / "feature")
        selected, _authenticated = consumer.verify(api, *paths, repository=repository,
            head=source_sha, policy=source_sha, run_id=source_run_id, directory=scratch / "baseline")
    else:
        authenticate_full(api, repository, source_sha=source_sha, source_run_id=source_run_id,
                           matrix_kind=proof["matrix_kind"])
    targets.validate_target_proof(proof, selection=selected)
    coverage.validate_compatibility_impact(proof["compatibility_impact"])
    validate_selected_manifest(proof, manifest, selected)
    source_artifacts = api.artifacts(run_id=source_run_id)
    if schema == 8 and any(item["name"] == SELECTION_ARTIFACT for item in source_artifacts):
        raise coverage.CoverageError("complete capsule cannot certify a selected runtime")
    packaged = [item for item in source_artifacts if item["name"].startswith("packaged-e2e-")]
    plan = targets.plan_targets(packaged, source_run_id=source_run_id,
                                source_branch="master", source_sha=source_sha, matrix_kind=proof["matrix_kind"])
    target = next(item for item in plan["include"] if item["bundle_key"] == bundle_key)
    if proof["artifact_inventory"] != [_artifact_record(item) for item in target["artifact_inventory"]]:
        raise coverage.CoverageError("selected capsule substituted its immutable source artifacts")
    if proof["review_mode"] == "reference-comparison":
        matrix, _digest, rows = targets._inventory(coverage.DEFAULT_MATRIX, proof["matrix_kind"])
        node = "fabric-" + matrix["unit_test_version"]
        names = {"packaged-e2e-" + row["id"] for row in rows if row["artifact_node"] == node}
        references = [item for item in packaged if item["name"] in names]
        if len(references) != 1 or proof["visual_reference"] != reference_record(references[0], node=node,
            source_sha=source_sha, source_run_id=source_run_id, selected=selected):
            raise coverage.CoverageError("selected capsule has a foreign same-run reference")
    elif proof["visual_reference"] is not None:
        raise coverage.CoverageError("semantic selected review cannot carry a comparison reference")
    validate_input(manifest, manifest_path.parent,
                    require_paired=proof["review_mode"] == "reference-comparison")
    images = list((manifest_path.parent / "images").iterdir())
    image_bytes = sum(path.stat().st_size for path in images)
    if (type(proof["image_count"]) is not int or proof["image_count"] != len(images)
            or type(proof["image_bytes"]) is not int or proof["image_bytes"] != image_bytes):
        raise coverage.CoverageError("selected capsule image budget differs from its actual normalized files")
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("selected review is no longer the current shared generation")
    return selected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--bundle-key", required=True)
    parser.add_argument("--matrix-kind", choices=("pr-anchors", "native-anchors"), default="pr-anchors")
    parser.add_argument("--compatibility-impact", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-proof", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    if args.verify_proof is not None:
        if args.manifest is None or args.output is not None or args.compatibility_impact is not None:
            parser.error("selected proof verification requires only its manifest")
    elif args.output is None or args.compatibility_impact is None or args.manifest is not None:
        parser.error("selected curation requires an output root and protected compatibility impact")
    try:
        api = publisher.Api(args.github_repository)
        with tempfile.TemporaryDirectory(prefix="qsm-selected-review-") as temporary:
            arguments = {"repository": args.repository, "source_sha": args.source_sha,
                         "source_run_id": args.source_run_id, "bundle_key": args.bundle_key,
                         "scratch": Path(temporary).resolve()}
            if args.verify_proof is not None:
                selected = verify(api, args.verify_proof, args.manifest, **arguments)
                result = {"selected": selected is not None,
                          "selection_sha256": selected.sha256 if selected is not None else ""}
            else:
                impact, _digest = coverage._read(args.compatibility_impact)
                proof = curate(api, compatibility_impact=impact, output=args.output,
                                matrix_kind=args.matrix_kind, **arguments)
                result = {"curated": True, "selected": proof["schema_version"] == 7,
                          "review_mode": proof["review_mode"], "generation_sha": proof["source_sha"],
                          "frame_count": proof["frame_count"]}
        print(json.dumps(result, sort_keys=True))
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as stream:
                for key, value in result.items():
                    stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    except (OSError, ValueError, publisher.subprocess.SubprocessError) as exc:
        parser.exit(2, f"Selected review admission failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
