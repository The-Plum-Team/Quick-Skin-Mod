#!/usr/bin/env python3
"""Curate one optional-mod lane against the clean same-version packaged baseline."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any

from mod_compatibility import (
    DEFAULT_CONTRACT as DEFAULT_COMPATIBILITY_CONTRACT,
    CompatibilityContractError,
    CompatibilityMod,
    load_contract as load_compatibility_contract,
    resolve_reference_capture_id,
    resolve_lane,
)
from scenario_contract import (
    DEFAULT_CONTRACT as DEFAULT_SCENARIO_CONTRACT,
    ScenarioContract,
)
from visual_evidence import VisualEvidenceError, collect_evidence, load_catalog
from visual_review import curate_manifest


SHA = re.compile(r"^[0-9a-f]{40}$")
SAFE_BRANCH = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,255}$")
SAFE_ARTIFACT_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$")
ARTIFACT_RECORD_FIELDS = {
    "id",
    "name",
    "size_in_bytes",
    "digest",
    "run_id",
}
MOD_COMPATIBILITY_BASELINE_CAPTURE = (
    "mod-compatibility.client_a.baseline_with_mod"
)
MOD_COMPATIBILITY_APPLIED_CAPTURE = (
    "mod-compatibility.client_a.apply_local_skin_with_mod"
)
MOD_COMPATIBILITY_REMOTE_BASELINE_CAPTURE = (
    "mod-compatibility-remote.client_b.observe_remote_baseline"
)
MOD_COMPATIBILITY_REMOTE_APPLIED_CAPTURE = (
    "mod-compatibility-remote.client_b.observe_remote_applied"
)
MOD_COMPATIBILITY_LATE_JOIN_CAPTURE = (
    "mod-compatibility-late-join.client_b.observe_late_join_state"
)


class CompatibilityVisualError(ValueError):
    pass


def _late_join_evidence(
    compatibility_mod: CompatibilityMod,
) -> tuple[str, tuple[tuple[float, float, float, float], ...]]:
    multiplayer = compatibility_mod.multiplayer
    if multiplayer is None:
        raise CompatibilityVisualError(
            f"{compatibility_mod.id} has no multiplayer late-join contract"
        )
    if compatibility_mod.id == "cpm":
        return (
            multiplayer.evidence.baseline_with_mod,
            multiplayer.review_regions.baseline_with_mod,
        )
    if compatibility_mod.id == "ears":
        return (
            multiplayer.evidence.apply_local_skin_with_mod,
            multiplayer.review_regions.apply_local_skin_with_mod,
        )
    raise CompatibilityVisualError(
        f"{compatibility_mod.id} has no authored late-join state"
    )


def _compatibility_expectation(
    compatibility_mod: CompatibilityMod,
    frame: dict[str, Any],
) -> str:
    capture_id = frame["capture_id"]
    if capture_id == MOD_COMPATIBILITY_BASELINE_CAPTURE:
        return compatibility_mod.evidence.baseline_with_mod
    if capture_id == MOD_COMPATIBILITY_APPLIED_CAPTURE:
        return compatibility_mod.evidence.apply_local_skin_with_mod
    if capture_id == MOD_COMPATIBILITY_REMOTE_BASELINE_CAPTURE:
        if compatibility_mod.multiplayer is None:
            raise CompatibilityVisualError(
                f"{compatibility_mod.id} has no multiplayer evidence contract"
            )
        return compatibility_mod.multiplayer.evidence.baseline_with_mod
    if capture_id == MOD_COMPATIBILITY_REMOTE_APPLIED_CAPTURE:
        if compatibility_mod.multiplayer is None:
            raise CompatibilityVisualError(
                f"{compatibility_mod.id} has no multiplayer evidence contract"
            )
        return compatibility_mod.multiplayer.evidence.apply_local_skin_with_mod
    if capture_id == MOD_COMPATIBILITY_LATE_JOIN_CAPTURE:
        state_expectation, _regions = _late_join_evidence(compatibility_mod)
        return frame["expectation"] + " " + state_expectation
    return frame["expectation"]


def _compatibility_review_regions(
    compatibility_mod: CompatibilityMod,
    frame: dict[str, Any],
) -> tuple[tuple[float, float, float, float], ...]:
    capture_id = frame["capture_id"]
    if capture_id == MOD_COMPATIBILITY_BASELINE_CAPTURE:
        return compatibility_mod.review_regions.baseline_with_mod
    if capture_id == MOD_COMPATIBILITY_APPLIED_CAPTURE:
        return compatibility_mod.review_regions.apply_local_skin_with_mod
    if capture_id == MOD_COMPATIBILITY_REMOTE_BASELINE_CAPTURE:
        if compatibility_mod.multiplayer is None:
            raise CompatibilityVisualError(
                f"{compatibility_mod.id} has no multiplayer review-region contract"
            )
        return compatibility_mod.multiplayer.review_regions.baseline_with_mod
    if capture_id == MOD_COMPATIBILITY_REMOTE_APPLIED_CAPTURE:
        if compatibility_mod.multiplayer is None:
            raise CompatibilityVisualError(
                f"{compatibility_mod.id} has no multiplayer review-region contract"
            )
        return compatibility_mod.multiplayer.review_regions.apply_local_skin_with_mod
    if capture_id == MOD_COMPATIBILITY_LATE_JOIN_CAPTURE:
        _expectation, regions = _late_join_evidence(compatibility_mod)
        return regions
    return tuple(tuple(region) for region in frame["review_regions"])


def _compatibility_reference_capture(
    compatibility_mod: CompatibilityMod,
    candidate_capture_id: str,
    default_reference_capture_id: str,
) -> str:
    return resolve_reference_capture_id(
        compatibility_mod,
        candidate_capture_id,
        default_reference_capture_id,
    )


def _select_compatibility_frames(
    candidate_frames: list[dict[str, Any]],
    *,
    scenario_contract: ScenarioContract,
    compatibility_mod: CompatibilityMod,
) -> list[dict[str, Any]]:
    compatibility_scenarios = set(
        scenario_contract.scenarios_for_profile("compatibility")
    )
    if compatibility_mod.multiplayer is not None:
        compatibility_scenarios.update(
            scenario_contract.scenarios_for_profile("compatibility-remote")
        )
    compatibility_scenarios = frozenset(compatibility_scenarios)
    expected_capture_ids = tuple(
        capture.capture_id
        for capture in scenario_contract.captures
        if capture.scenario in compatibility_scenarios
    )
    if not expected_capture_ids:
        raise CompatibilityVisualError(
            "compatibility profile must declare at least one capture"
        )
    expected_capture_set = frozenset(expected_capture_ids)
    selected = [
        frame
        for frame in candidate_frames
        if isinstance(frame.get("capture_id"), str)
        and frame["capture_id"] in expected_capture_set
    ]
    if tuple(frame["capture_id"] for frame in selected) != expected_capture_ids:
        raise CompatibilityVisualError(
            "candidate compatibility capture coverage is incomplete or out of order"
        )
    return selected


def _artifact_record(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != ARTIFACT_RECORD_FIELDS:
        raise CompatibilityVisualError(
            f"{label} must contain exactly {sorted(ARTIFACT_RECORD_FIELDS)}"
        )
    artifact_id = value["id"]
    run_id = value["run_id"]
    size = value["size_in_bytes"]
    name = value["name"]
    digest = value["digest"]
    if (
        isinstance(artifact_id, bool)
        or not isinstance(artifact_id, int)
        or artifact_id <= 0
        or isinstance(run_id, bool)
        or not isinstance(run_id, int)
        or run_id <= 0
        or isinstance(size, bool)
        or not isinstance(size, int)
        or not 1 <= size <= 512 * 1024 * 1024
        or not isinstance(name, str)
        or SAFE_ARTIFACT_NAME.fullmatch(name) is None
        or not isinstance(digest, str)
        or re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None
    ):
        raise CompatibilityVisualError(f"{label} contains invalid artifact metadata")
    return dict(value)


def _load_inventory(path: Path) -> dict[str, dict[str, Any]]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CompatibilityVisualError(f"cannot read artifact inventory: {exc}") from exc
    if not isinstance(value, dict) or set(value) != {"base", "candidate"}:
        raise CompatibilityVisualError("artifact inventory must contain base and candidate")
    return {
        "base": _artifact_record(value["base"], "artifact_inventory.base"),
        "candidate": _artifact_record(
            value["candidate"], "artifact_inventory.candidate"
        ),
    }


def _write_json_new(path: Path, value: Any) -> None:
    if path.exists() or path.is_symlink():
        raise CompatibilityVisualError(f"proof output must be fresh: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent, prefix=f".{path.name}.", suffix=".tmp"
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(value, output, indent=2, ensure_ascii=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def curate(
    *,
    candidate_root: Path,
    base_root: Path,
    scenario_contract: Path,
    compatibility_contract: Path,
    artifact_node: str,
    mod_id: str,
    output: Path,
    proof: Path,
    inventory: dict[str, dict[str, Any]],
    source_run_id: int,
    source_sha: str,
    target_branch: str,
    target_sha: str,
    compatibility_run_id: int,
    implementation_sha: str,
) -> list[dict[str, object]]:
    if inventory["base"]["run_id"] != source_run_id:
        raise CompatibilityVisualError("base artifact run disagrees with source_run_id")
    if inventory["candidate"]["run_id"] != compatibility_run_id:
        raise CompatibilityVisualError(
            "candidate artifact run disagrees with compatibility_run_id"
        )
    if inventory["base"]["id"] == inventory["candidate"]["id"]:
        raise CompatibilityVisualError("base and candidate artifacts must be distinct")
    catalog = load_catalog(scenario_contract)
    contract = load_compatibility_contract(compatibility_contract)
    candidate_lanes, candidate_frames, _candidate_comparisons = collect_evidence(
        candidate_root,
        catalog,
        compatibility_id=mod_id,
        compatibility_contract_path=compatibility_contract,
    )
    base_lanes, base_frames, _base_comparisons = collect_evidence(base_root, catalog)
    if not candidate_lanes or not base_lanes:
        raise CompatibilityVisualError("compatibility curation received empty evidence")
    candidate_identities = {
        (lane["artifact_node"], lane["version"], lane["loader"])
        for lane in candidate_lanes
    }
    if len(candidate_identities) != 1:
        raise CompatibilityVisualError("candidate evidence contains multiple runtime lanes")
    selected_artifact, version, loader = next(iter(candidate_identities))
    if selected_artifact != artifact_node:
        raise CompatibilityVisualError("candidate artifact disagrees with the requested lane")
    base_identities = {
        (item["artifact_node"], item["version"], item["loader"])
        for item in base_lanes
    }
    if base_identities != {(artifact_node, version, loader)}:
        raise CompatibilityVisualError(
            "base evidence is not exactly the same version/loader runtime lane"
        )
    lane = resolve_lane(
        contract,
        mod_id=mod_id,
        artifact_node=artifact_node,
        runtime_version=version,
        loader=loader,
    )
    expected_candidate_scenarios = {
        *catalog.contract.scenarios_for_profile("release"),
        *catalog.contract.scenarios_for_profile("compatibility"),
    }
    if lane.mod.multiplayer is not None:
        expected_candidate_scenarios.update(
            catalog.contract.scenarios_for_profile("compatibility-remote")
        )
    observed_candidate_scenarios = {item["scenario"] for item in candidate_lanes}
    if observed_candidate_scenarios != expected_candidate_scenarios:
        raise CompatibilityVisualError(
            "candidate scenario coverage is not the complete release plus compatibility suite"
        )
    expected_base_scenarios = set(catalog.contract.scenarios_for_profile("release"))
    observed_base_scenarios = {
        item["scenario"]
        for item in base_lanes
        if item["artifact_node"] == artifact_node
    }
    if observed_base_scenarios != expected_base_scenarios:
        raise CompatibilityVisualError(
            "base scenario coverage is not the complete release suite"
        )
    compatibility_frames = _select_compatibility_frames(
        candidate_frames,
        scenario_contract=catalog.contract,
        compatibility_mod=lane.mod,
    )
    base_by_capture: dict[str, dict[str, Any]] = {}
    for frame in base_frames:
        if frame["artifact_node"] != artifact_node:
            continue
        capture_id = frame["capture_id"]
        if capture_id in base_by_capture:
            raise CompatibilityVisualError(
                f"base evidence duplicates capture {capture_id!r}"
            )
        base_by_capture[capture_id] = frame

    private_manifest: list[dict[str, object]] = []
    for frame in compatibility_frames:
        capture = catalog.contract.capture_by_id(frame["capture_id"])
        reference_capture = _compatibility_reference_capture(
            lane.mod,
            frame["capture_id"],
            capture.compatibility_reference_capture_id or capture.capture_id,
        )
        reference = base_by_capture.get(reference_capture)
        if reference is None:
            raise CompatibilityVisualError(
                f"base evidence is missing compatibility reference {reference_capture!r}"
            )
        if (reference["version"], reference["loader"]) != (version, loader):
            raise CompatibilityVisualError("compatibility reference is not the same runtime lane")
        expectation = _compatibility_expectation(lane.mod, frame)
        review_regions = _compatibility_review_regions(lane.mod, frame)
        private_manifest.append(
            {
                "path": frame["source_path"],
                "label": frame["frame_id"],
                "capture_id": frame["capture_id"],
                "kind": frame["capture_id"],
                "expectation": (
                    f"Compatibility mod: {lane.mod.name} {lane.artifact.version_number}. "
                    + expectation
                ),
                "runtime_evidence": frame["runtime_evidence"],
                "_verified_file_sha256": frame["file_sha256"],
                "_verified_pixel_sha256": frame["pixel_validation"]["pixel_sha256"],
                "_verified_width": frame["width"],
                "_verified_height": frame["height"],
                "_review_regions": review_regions,
                "_expected_size": catalog.contract.screenshot_size,
                "reference_path": reference["source_path"],
                "reference_label": reference["frame_id"],
                "_reference_verified_file_sha256": reference["file_sha256"],
                "_reference_verified_pixel_sha256": reference["pixel_validation"][
                    "pixel_sha256"
                ],
                "_reference_verified_width": reference["width"],
                "_reference_verified_height": reference["height"],
                "_reference_verified_format": "PNG",
            }
        )
    curated = curate_manifest(private_manifest, output)
    manifest_path = output / "visual-review-manifest.json"
    manifest_sha256 = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    _write_json_new(
        proof,
        {
            "schema_version": 1,
            "kind": "quick-skin-mod-compatibility-review-input",
            "source_run_id": source_run_id,
            "source_sha": source_sha,
            "target_branch": target_branch,
            "target_sha": target_sha,
            "compatibility_run_id": compatibility_run_id,
            "implementation_sha": implementation_sha,
            "artifact_node": artifact_node,
            "runtime_version": version,
            "loader": loader,
            "mod": mod_id,
            "mod_name": lane.mod.name,
            "mod_version": lane.artifact.version_number,
            "mod_version_id": lane.artifact.version_id,
            "scenario_contract_sha256": catalog.contract_sha256,
            "compatibility_contract_sha256": contract.sha256,
            "manifest_sha256": manifest_sha256,
            "frame_count": len(curated),
            "artifact_inventory": inventory,
        },
    )
    return curated


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--base-root", type=Path, required=True)
    parser.add_argument("--scenario-contract", type=Path, default=DEFAULT_SCENARIO_CONTRACT)
    parser.add_argument(
        "--compatibility-contract", type=Path, default=DEFAULT_COMPATIBILITY_CONTRACT
    )
    parser.add_argument("--artifact-node", required=True)
    parser.add_argument("--mod", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--proof", type=Path, required=True)
    parser.add_argument("--artifact-inventory", type=Path, required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--target-branch", required=True)
    parser.add_argument("--target-sha", required=True)
    parser.add_argument("--compatibility-run-id", type=int, required=True)
    parser.add_argument("--implementation-sha", required=True)
    args = parser.parse_args()
    try:
        if (
            args.source_run_id <= 0
            or args.compatibility_run_id <= 0
            or SHA.fullmatch(args.source_sha) is None
            or SHA.fullmatch(args.target_sha) is None
            or SHA.fullmatch(args.implementation_sha) is None
            or SAFE_BRANCH.fullmatch(args.target_branch) is None
        ):
            raise CompatibilityVisualError("invalid compatibility provenance arguments")
        curated = curate(
            candidate_root=args.candidate_root,
            base_root=args.base_root,
            scenario_contract=args.scenario_contract,
            compatibility_contract=args.compatibility_contract,
            artifact_node=args.artifact_node,
            mod_id=args.mod,
            output=args.output,
            proof=args.proof,
            inventory=_load_inventory(args.artifact_inventory),
            source_run_id=args.source_run_id,
            source_sha=args.source_sha,
            target_branch=args.target_branch,
            target_sha=args.target_sha,
            compatibility_run_id=args.compatibility_run_id,
            implementation_sha=args.implementation_sha,
        )
        print(f"Curated {len(curated)} same-version compatibility frame pairs")
        return 0
    except (
        CompatibilityContractError,
        CompatibilityVisualError,
        VisualEvidenceError,
        OSError,
        ValueError,
    ) as exc:
        parser.exit(2, f"mod compatibility visual curation failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
