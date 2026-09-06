"""Validate two-generation public evidence without relabeling earlier tested frames.

The GitHub collector owns external authentication. This module recomputes the local selection,
validates both bounded image bundles, and retains a complete baseline plus one cumulative update.
Compositions cannot recursively consume compositions or certify a new complete baseline.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import tempfile
from dataclasses import fields
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import e2e_selection as admission
import evidence
from module_graph import load_graph
from scenario_contract import load_contract
from selection import select

COMPOSED_FIELDS = (evidence.MANIFEST_FIELDS - {"lanes", "frames", "comparisons"}
                   | {"feature_selection", "components"})
COMPONENT_FIELDS = frozenset({"baseline_sha256", "selected_sha256"})


def read_selection(feature: Any, *, catalog_path: Path = evidence.DEFAULT_CATALOG) -> admission.Admission:
    """Recompute the capture scope; this is structural validation, not GitHub/Git authentication."""
    feature = json.loads(admission.canonical(feature))
    if not isinstance(feature, dict) or set(feature) != {"admission", "coverage"}:
        raise evidence.PublicEvidenceError("public feature evidence has an invalid admission envelope")
    value, coverage = feature["admission"], feature["coverage"]
    names = {field.name for field in fields(admission.Admission)}
    if (not isinstance(value, dict) or set(value) != names | {"schema_version", "input_kind"}
            or type(value.get("schema_version")) is not int or value["schema_version"] != 1
            or value["input_kind"] != "git-diff" or value["diff_complete"] is not True
            or value["profile"] != "pr" or not isinstance(value["selection"], dict)):
        raise evidence.PublicEvidenceError("public feature selection lacks a complete Git admission")
    for field in ("base_commit", "head_commit", "policy_commit", "base_tree", "head_tree", "policy_tree"):
        evidence._sha(value[field], f"selection.{field}")
    for field in ("policy_sha256", "diff_sha256"):
        if not isinstance(value[field], str) or not evidence.SHA256.fullmatch(value[field]):
            raise evidence.PublicEvidenceError("public selection has an invalid policy or diff digest")
    paths = value["selection"].get("changed_paths")
    if not isinstance(paths, list):
        raise evidence.PublicEvidenceError("public selection must declare its changed paths")
    selected = select(load_contract(catalog_path), load_graph(), paths, "pr")
    expected = admission.Admission(**{key: value[key] for key in names - {"selection"}}, selection=selected)
    if (selected.mode != "affected" or expected.reason != selected.reason
            or expected.to_bytes() != admission.canonical(value)
            or not isinstance(coverage, dict) or type(coverage.get("schema_version")) is not int
            or coverage["schema_version"] != 1 or coverage.get("selective") is not True
            or coverage.get("selection_sha256") != expected.sha256
            or not isinstance(coverage.get("baseline"), dict)
            or coverage["baseline"].get("source_sha") != expected.base_commit):
        raise evidence.PublicEvidenceError("public selection differs from its recomputed obligations or baseline")
    return expected


def _component(root: Path, key: str, epoch: str, digest: str, schema: int) -> None:
    path = root / epoch / key / "manifest.json"
    if path.is_symlink() or path.resolve() != path.absolute():
        raise evidence.PublicEvidenceError("public evidence component must not traverse symbolic links")
    with path.open("rb") as stream:
        raw = stream.read(evidence.MAX_MANIFEST_BYTES + 1)
    if not raw or len(raw) > evidence.MAX_MANIFEST_BYTES:
        raise evidence.PublicEvidenceError("public evidence component exceeds its manifest byte limit")
    value = json.loads(raw, object_pairs_hook=evidence._reject_duplicate_keys,
                       parse_constant=evidence._reject_nonfinite_constant,
                       parse_float=evidence.parse_finite_json_float)
    if (not isinstance(value, dict) or type(value.get("schema_version")) is not int
            or value["schema_version"] != schema or hashlib.sha256(raw).hexdigest() != digest):
        raise evidence.PublicEvidenceError("public evidence component has a foreign digest or generation kind")


def validate_composed(evidence_root: Path, key: str, manifest: dict[str, Any], *,
                      expected_kind: str | None = None, expected_repository: str | None = None,
                      expected_source_run_id: str | None = None, expected_target_run_id: str | None = None,
                      expected_target_sha: str | None = None, expected_coverage_sha: str | None = None,
                      catalog_path: Path = evidence.DEFAULT_CATALOG,
                      matrix_path: Path = evidence.DEFAULT_MATRIX) -> dict[str, Any]:
    origin_fields = {"runtime_source"} if "runtime_source" in manifest else set()
    if set(manifest) != COMPOSED_FIELDS | origin_fields or expected_kind not in (None, "compact"):
        raise evidence.PublicEvidenceError("composed public evidence requires its exact compact schema")
    components = manifest["components"]
    if (not isinstance(components, dict) or set(components) != COMPONENT_FIELDS
            or any(not isinstance(value, str) or not evidence.SHA256.fullmatch(value)
                   for value in components.values())):
        raise evidence.PublicEvidenceError("composed public evidence has an invalid component inventory")
    bundle = evidence_root.resolve() / key
    if {path.name for path in bundle.iterdir()} != {"manifest.json", "baseline", "selected"}:
        raise evidence.PublicEvidenceError("composed public evidence contains unexpected root entries")
    chosen = read_selection(manifest["feature_selection"], catalog_path=catalog_path)
    _component(bundle, key, "baseline", components["baseline_sha256"], evidence.SHARED_COMPACT_SCHEMA_VERSION)
    _component(bundle, key, "selected", components["selected_sha256"], evidence.SELECTED_COMPACT_SCHEMA_VERSION)
    baseline = evidence.validate_bundle(bundle / "baseline", key, only_branch=True, expected_kind="compact",
        expected_repository=expected_repository, expected_target_sha=chosen.base_commit,
        expected_coverage_sha=chosen.base_commit, catalog_path=catalog_path, matrix_path=matrix_path)
    update = evidence.validate_bundle(bundle / "selected", key, only_branch=True, expected_kind="compact",
        expected_repository=expected_repository, expected_source_run_id=expected_source_run_id,
        expected_target_run_id=expected_target_run_id, expected_target_sha=expected_target_sha,
        expected_coverage_sha=expected_coverage_sha, catalog_path=catalog_path, matrix_path=matrix_path,
        selection=chosen)
    if (any(manifest[field] != baseline[field] for field in ("release", "repository", "contract_sha256"))
            or any(manifest[field] != update[field] for field in ("provenance", "feature_selection", "repository", "contract_sha256"))
            or manifest.get("runtime_source") != update.get("runtime_source")):
        raise evidence.PublicEvidenceError("composed public evidence substituted its tested provenance or inventory")
    entries = evidence._bounded_entries(bundle.rglob("*"), maximum=2 * evidence.MAX_BUNDLE_ENTRIES + 8,
                                        label="composed public evidence")
    total = 0
    for path in entries:
        if path.is_symlink():
            raise evidence.PublicEvidenceError("composed public evidence contains a symlink")
        if path.is_file():
            total += path.stat().st_size
    if total > evidence.MAX_TOTAL_IMAGE_BYTES + 3 * evidence.MAX_MANIFEST_BYTES:
        raise evidence.PublicEvidenceError("composed public evidence exceeds its aggregate byte budget")
    return _view(manifest, baseline, update, key)


def _view(manifest: dict[str, Any], baseline: dict[str, Any], update: dict[str, Any], key: str) -> dict[str, Any]:
    updates = {frame["frame_id"]: frame for frame in update["frames"]}
    if not set(updates) <= {frame["frame_id"] for frame in baseline["frames"]}:
        raise evidence.PublicEvidenceError("selected update contains a frame outside the complete baseline")
    epochs = {"baseline": baseline, "selected": update}
    epoch_lanes = {epoch: {row["lane_id"]: row for row in value["lanes"]} for epoch, value in epochs.items()}
    frames, lanes = [], {}
    for original in baseline["frames"]:
        identifier = original["frame_id"]
        epoch = "selected" if identifier in updates else "baseline"
        frame = updates.get(identifier, original)
        source = epochs[epoch]
        original_lane = f"{frame['artifact_node']}/{frame['scenario']}"
        lane_id = original_lane + "/" + epoch
        lane = epoch_lanes[epoch][original_lane]
        lanes[lane_id] = {**lane, "lane_id": lane_id, "tested_provenance": source["provenance"]}
        frames.append({**frame, "capture_order": original["capture_order"], "lane_id": lane_id,
            "jar_sha256": lane["jar_sha256"], "tested_provenance": source["provenance"],
            "coverage_sha": manifest["provenance"]["target"]["sha"], "evidence_epoch": epoch,
            "derivative": {**frame["derivative"],
                           "asset": f"{epoch}/{key}/" + frame["derivative"]["asset"]}})
    updated_comparisons = {pair["comparison_id"]: pair for pair in update["comparisons"]}
    comparisons = []
    for pair in baseline["comparisons"]:
        first, second = pair["first_frame_id"] in updates, pair["second_frame_id"] in updates
        if first != second or first != (pair["comparison_id"] in updated_comparisons):
            raise evidence.PublicEvidenceError("public comparison crosses tested generations")
        comparisons.append(updated_comparisons.get(pair["comparison_id"], pair))
    return {**manifest, "frames": frames, "lanes": list(lanes.values()), "comparisons": comparisons}


def compose(baseline_root: Path, selected_root: Path, output_root: Path, key: str, *,
            selection: admission.Admission, catalog_path: Path = evidence.DEFAULT_CATALOG,
            matrix_path: Path = evidence.DEFAULT_MATRIX, coverage_sha: str | None = None) -> Path:
    """Atomically retain one complete compact baseline and the current cumulative selection."""
    baseline = evidence.validate_bundle(baseline_root, key, only_branch=True, expected_kind="compact",
        expected_target_sha=selection.base_commit, expected_coverage_sha=selection.base_commit,
        catalog_path=catalog_path, matrix_path=matrix_path)
    covered = coverage_sha or selection.head_commit
    update = evidence.validate_bundle(selected_root, key, only_branch=True, expected_kind="compact",
        expected_target_sha=covered, expected_coverage_sha=covered,
        catalog_path=catalog_path, matrix_path=matrix_path, selection=selection)
    if baseline["schema_version"] != evidence.SHARED_COMPACT_SCHEMA_VERSION:
        raise evidence.PublicEvidenceError("feature composition requires an original complete baseline")
    output_root.mkdir(parents=True, exist_ok=True)
    destination = output_root / key
    if destination.exists():
        raise evidence.PublicEvidenceError("feature composition output must be new")
    with tempfile.TemporaryDirectory(prefix=".feature-pages-", dir=output_root) as temporary:
        root = Path(temporary)
        bundle = root / key
        shutil.copytree(baseline_root / key, bundle / "baseline" / key)
        shutil.copytree(selected_root / key, bundle / "selected" / key)
        manifest = {"schema_version": evidence.COMPOSED_SCHEMA_VERSION,
            **{field: baseline[field] for field in ("release", "repository", "contract_sha256")},
            "provenance": update["provenance"], "feature_selection": update["feature_selection"],
            "components": {epoch + "_sha256": evidence.sha256_file(bundle / epoch / key / "manifest.json")
                           for epoch in ("baseline", "selected")}}
        if "runtime_source" in update:
            manifest["runtime_source"] = update["runtime_source"]
        (bundle / "manifest.json").write_bytes(admission.canonical(manifest))
        evidence.validate_bundle(root, key, only_branch=True, expected_kind="compact",
            expected_target_sha=covered, catalog_path=catalog_path, matrix_path=matrix_path)
        bundle.rename(destination)
    return destination
