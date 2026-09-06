#!/usr/bin/env python3
"""Partition authenticated packaged evidence into bounded reviews for matrix targets.

The caller authenticates the source run and complete protected job graph first. This module
never downloads or decodes evidence and never executes source-checkout code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts/release"))
sys.path.insert(0, str(REPO / "scripts/pages"))

from evidence_target import DEFAULT_MATRIX, MAX_TARGETS, bundle_version  # noqa: E402
from matrix import gha_matrix, load_matrix, read_mod_version  # noqa: E402
from e2e_job_graph import SCENARIO_SUFFIX  # noqa: E402

MAX_JSON_BYTES = 5 * 1024 * 1024
MAX_TARGET_ARTIFACTS = 8
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_TARGET_BYTES = 256 * 1024 * 1024
SHA = re.compile(r"[0-9a-f]{40}\Z")
DIGEST = re.compile(r"sha256:[0-9a-f]{64}\Z")
BRANCH = re.compile(r"[A-Za-z0-9._/-]{1,256}\Z")


class ReviewTargetError(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReviewTargetError("duplicate review metadata key")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise ReviewTargetError("non-finite review metadata number")


def _finite_float(value: str) -> float:
    result = float(value)
    if not math.isfinite(result):
        _reject_nonfinite(value)
    return result


def read_json(path: Path) -> Any:
    with path.open("rb") as stream:
        payload = stream.read(MAX_JSON_BYTES + 1)
    if not payload or len(payload) > MAX_JSON_BYTES:
        raise ReviewTargetError("review metadata exceeds its byte limit")
    return json.loads(payload, object_pairs_hook=_unique_object, parse_constant=_reject_nonfinite,
                      parse_float=_finite_float)


def _inventory(matrix_path: Path, matrix_kind: str) -> tuple[dict[str, Any], str, list[dict[str, Any]]]:
    if matrix_kind not in {"pr-anchors", "native-anchors"}:
        raise ReviewTargetError("unsupported protected review profile")
    with matrix_path.open("rb") as stream:
        raw = stream.read(MAX_JSON_BYTES + 1)
    if not raw or len(raw) > MAX_JSON_BYTES:
        raise ReviewTargetError("review matrix exceeds its byte limit")
    strict = json.loads(raw, object_pairs_hook=_unique_object, parse_constant=_reject_nonfinite,
                        parse_float=_finite_float)
    data = load_matrix(matrix_path)
    with matrix_path.open("rb") as stream:
        if stream.read(MAX_JSON_BYTES + 1) != raw or strict != data:
            raise ReviewTargetError("review matrix changed during validation")
    rows = gha_matrix(data, matrix_kind, read_mod_version(matrix_path, data))["include"]
    if not rows or len(rows) > MAX_TARGETS * MAX_TARGET_ARTIFACTS:
        raise ReviewTargetError("review matrix has an empty or oversized lane inventory")
    return data, hashlib.sha256(raw).hexdigest(), rows


def _validate_artifacts(artifacts: Any, expected: set[str], *, source_run_id: int,
                        source_branch: str, source_sha: str) -> dict[str, dict[str, Any]]:
    if (type(source_run_id) is not int or source_run_id <= 0
            or not isinstance(source_branch, str) or BRANCH.fullmatch(source_branch) is None
            or not isinstance(source_sha, str) or SHA.fullmatch(source_sha) is None):
        raise ReviewTargetError("invalid review source identity")
    if not isinstance(artifacts, list) or len(artifacts) != len(expected):
        raise ReviewTargetError("review artifacts do not cover the exact matrix lanes")
    names: dict[str, dict[str, Any]] = {}
    identifiers: set[int] = set()
    for item in artifacts:
        if not isinstance(item, dict):
            raise ReviewTargetError("invalid review artifact record")
        name, identifier, size = item.get("name"), item.get("id"), item.get("size_in_bytes")
        run = item.get("workflow_run")
        if (not isinstance(name, str) or name not in expected or name in names
                or type(identifier) is not int or identifier <= 0 or identifier in identifiers
                or type(size) is not int or not 0 < size <= MAX_ARTIFACT_BYTES
                or not isinstance(item.get("digest"), str) or DIGEST.fullmatch(item["digest"]) is None
                or item.get("expired") is not False or not isinstance(run, dict)
                or type(run.get("id")) is not int or run["id"] != source_run_id
                or run.get("head_branch") != source_branch or run.get("head_sha") != source_sha):
            raise ReviewTargetError("review artifact has an unexpected identity, size or owner")
        names[name] = item
        identifiers.add(identifier)
    return names


def _targets(data: dict[str, Any], rows: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    targets: dict[str, list[dict[str, Any]]] = {}
    artifact_versions = {row["artifact_node"]: row["artifact_version"] for row in data["artifacts"]}
    for row in rows:
        key = (f"mc{artifact_versions[row['artifact_node']]}" if data["schema_version"] == 3
               else data["project"]["release_branch"])
        bundle_version(key)
        targets.setdefault(key, []).append(row)
    if len(targets) > MAX_TARGETS or any(len(lanes) > MAX_TARGET_ARTIFACTS for lanes in targets.values()):
        raise ReviewTargetError("review target inventory exceeds its limit")
    return targets


def _review_mode(data: dict[str, Any], rows: list[dict[str, Any]]) -> str:
    return ("anchor-semantic" if len(rows) == 2
            and {row["runtime_version"] for row in rows} == {data["unit_test_version"]}
            and {row["loader"] for row in rows} == {"fabric", "forge"}
            else "reference-comparison")


def plan_targets(artifacts: Any, *, source_run_id: int, source_branch: str, source_sha: str,
                 matrix_path: Path = DEFAULT_MATRIX, matrix_kind: str = "pr-anchors") -> dict[str, Any]:
    data, digest, rows = _inventory(matrix_path, matrix_kind)
    if data["schema_version"] == 3 and source_branch != data["project"]["release_branch"]:
        raise ReviewTargetError("shared reviews require the real matrix source branch")
    by_name = _validate_artifacts(artifacts, {f"packaged-e2e-{row['id']}" for row in rows},
                                 source_run_id=source_run_id, source_branch=source_branch, source_sha=source_sha)
    include = []
    for key, lanes in _targets(data, rows).items():
        selected = [by_name[f"packaged-e2e-{row['id']}"] for row in sorted(lanes, key=lambda row: row["id"])]
        if sum(item["size_in_bytes"] for item in selected) > MAX_TARGET_BYTES:
            raise ReviewTargetError("review target exceeds its compressed evidence budget")
        include.append({"bundle_key": key,
                        "minecraft_target": bundle_version(key) if data["schema_version"] == 3 else "",
                        "matrix_sha256": digest, "review_mode": _review_mode(data, lanes),
                        "artifact_inventory": selected})
    return {"include": sorted(include, key=lambda row: tuple(map(int, bundle_version(row["bundle_key"]).split("."))))}


def validate_target_proof(proof: Any, *, matrix_path: Path = DEFAULT_MATRIX, selection: Any = None) -> None:
    """Recompute a shared capsule's target scope independently before model admission.

    Generic capsule hashes, counts, owner-run authentication and normalized review validation
    remain the drainer's responsibility. This binds its new scope fields to the complete matrix.
    """
    if isinstance(proof, dict) and type(proof.get("schema_version")) is int and proof["schema_version"] == 7:
        feature = proof.get("feature_selection")
        if (selection is None or not isinstance(feature, dict) or set(feature) != {"admission", "coverage"}
                or not isinstance(feature["coverage"], dict)
                or feature["coverage"].get("selection_sha256") != selection.sha256
                or feature["coverage"].get("selective") is not True
                or (json.dumps(feature["admission"], sort_keys=True, separators=(",", ":"), allow_nan=False)
                    + "\n").encode() != selection.to_bytes()):
            raise ReviewTargetError("selected proof requires independently authenticated feature coverage")
        complete_scope = {**proof, "schema_version": 6}
        del complete_scope["feature_selection"]
        validate_target_proof(complete_scope, matrix_path=matrix_path)
        return
    if not isinstance(proof, dict) or type(proof.get("schema_version")) is not int or proof["schema_version"] != 6:
        raise ReviewTargetError("a shared target curation proof is required")
    data, digest, rows = _inventory(matrix_path, proof.get("matrix_kind"))
    if data["schema_version"] != 3 or proof.get("matrix_sha256") != digest:
        raise ReviewTargetError("curation proof disagrees with the full protected matrix")
    key = proof.get("bundle_key")
    targets = _targets(data, rows)
    if not isinstance(key, str) or key not in targets:
        raise ReviewTargetError("curation proof names an unknown target")
    if (proof.get("source_branch") != data["project"]["release_branch"]
            or proof.get("source_sha") != proof.get("implementation_sha")
            or proof.get("master_source_sha") != proof.get("source_sha")
            or proof.get("review_mode") != _review_mode(data, targets[key])):
        raise ReviewTargetError("curation proof has a stale source or incorrect target review mode")
    selected = _validate_artifacts(proof.get("artifact_inventory"),
        {f"packaged-e2e-{row['id']}" for row in targets[key]}, source_run_id=proof.get("source_run_id"),
        source_branch=proof.get("source_branch"), source_sha=proof.get("source_sha"))
    if sum(item["size_in_bytes"] for item in selected.values()) > MAX_TARGET_BYTES:
        raise ReviewTargetError("review target exceeds its compressed evidence budget")
    expected = sorted(row["id"] + SCENARIO_SUFFIX for row in rows)
    graph = proof.get("job_graph")
    if not isinstance(graph, dict) or type(graph.get("schema_version")) is not int or graph != {
                 "schema_version": 1, "runtime_policy": "full",
                 "expected_scenario_jobs": expected, "observed_scenario_jobs": expected}:
        raise ReviewTargetError("curation proof requires the complete protected job graph")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument("--matrix-kind", choices=("pr-anchors", "native-anchors"), default="pr-anchors")
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--source-run-id", type=int)
    parser.add_argument("--source-branch")
    parser.add_argument("--source-sha")
    parser.add_argument("--validate-proof", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.validate_proof is not None:
            if any(value is not None for value in (args.artifacts, args.source_run_id, args.source_branch, args.source_sha)):
                raise ReviewTargetError("proof validation cannot override source identity")
            validate_target_proof(read_json(args.validate_proof), matrix_path=args.matrix)
            print("validated protected target review scope")
        else:
            if args.artifacts is None:
                raise ReviewTargetError("review artifact metadata is required")
            result = plan_targets(read_json(args.artifacts), source_run_id=args.source_run_id,
                                  source_branch=args.source_branch, source_sha=args.source_sha,
                                  matrix_path=args.matrix, matrix_kind=args.matrix_kind)
            print(json.dumps(result, sort_keys=True, separators=(",", ":"), allow_nan=False))
        return 0
    except (OSError, ValueError) as exc:
        print(f"Visual target admission failed: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
