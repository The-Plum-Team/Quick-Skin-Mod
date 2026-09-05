#!/usr/bin/env python3
"""Validate complete shared-source coverage before it can become a reusable baseline.

These are secretless validation primitives, not GitHub admission. A protected caller must
authenticate the source job graph and every report's immutable artifact/owner run before
calling them. Partial or native-only runs never create a complete PR-profile baseline.
"""
from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import e2e_selection as admission
from e2e_job_graph import (PROTECTED_CONTROLLER_PATHS, _jobs, _require_conclusion,
                          _unique_named_job, expected_scenario_jobs_for, validate_job_graph)
from visual_anchor_certification import PROOF_KEYS, _validate_compatibility_impact
from visual_review_targets import DEFAULT_MATRIX, validate_target_proof
from check_visual_review import MAX_JSON_BYTES, MAX_REVIEW_TOTAL_BYTES, validate
from module_graph import ModuleGraph, load_graph, repository_path, unique_object
from scenario_contract import default_contract
from evidence_target import inventory
from matrix import load_matrix, select_release_target
from visual_review_queue import (DRAIN_EVENTS, DRAIN_WORKFLOW, REPOSITORY, QueueError,
                                 parse_artifact, valid_owner)

POLICY_PATHS = tuple(sorted(set(PROTECTED_CONTROLLER_PATHS) | {
    ".github/workflows/visual-review.yml", ".github/workflows/visual-review-drain.yml",
    "scripts/ci/feature_coverage.py", "scripts/ci/visual_review_targets.py",
}))
BASELINE_KIND = "quick-skin-complete-feature-baseline"
MAX_REPORT_ARCHIVE_BYTES = 4 * 1024 * 1024


class CoverageError(ValueError):
    """Full coverage or unchanged dependency provenance could not be established."""


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _read(path: Path) -> tuple[Any, str]:
    if path.is_symlink() or path.resolve() != path.absolute():
        raise CoverageError("coverage JSON must not traverse symbolic links")
    with path.open("rb") as stream:
        raw = stream.read(MAX_JSON_BYTES + 1)
    if not raw or len(raw) > MAX_JSON_BYTES:
        raise CoverageError("coverage JSON exceeds its byte limit")
    try:
        value = json.loads(raw, object_pairs_hook=unique_object)
        admission.canonical(value)  # Reject non-finite constants and overflowing floats.
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise CoverageError("coverage evidence must contain bounded finite JSON") from exc
    return value, digest(raw)


def _positive_integer(value: Any, label: str) -> int:
    if type(value) is not int or value <= 0:
        raise CoverageError(f"{label} must be a positive integer")
    return value


@dataclass(frozen=True)
class ReviewFiles:
    proof: Path
    manifest: Path
    report: Path


def validate_source_run(run: Any, jobs: Any, *, github_repository: str, source_sha: str,
                        source_run_id: int, matrix_path: Path = DEFAULT_MATRIX) -> dict[str, Any]:
    """Validate API records fetched independently for the requested complete source run."""
    if (not isinstance(github_repository, str) or REPOSITORY.fullmatch(github_repository) is None
            or not isinstance(source_sha, str) or admission.SHA.fullmatch(source_sha) is None
            or not isinstance(run, dict) or type(run.get("id")) is not int
            or run["id"] != _positive_integer(source_run_id, "source run")
            or run.get("head_sha") != source_sha or run.get("head_branch") != "master"
            or run.get("path") != ".github/workflows/on-demand-e2e.yml"
            or run.get("event") != "workflow_dispatch"
            or run.get("status") != "completed" or run.get("conclusion") != "success"
            or not isinstance(run.get("head_repository"), dict)
            or run["head_repository"].get("full_name") != github_repository):
        raise CoverageError("a baseline requires the exact successful shared-source PR-profile run")
    return validate_job_graph(jobs, policy="full", expected_scenarios=expected_scenario_jobs_for(
        matrix_path, "pr-anchors"))


def validate_review_owner(artifact: Any, owner: Any, jobs: Any, *, github_repository: str,
                          source_sha: str, source_run_id: int, bundle_key: str,
                          matrix_path: Path = DEFAULT_MATRIX) -> dict[str, Any]:
    """Guard a normalized report before bounded download and extraction by immutable ID.

    A cleanup failure may follow a successful review. Require that exact review job to pass,
    then independently validate its normalized bytes with validate_clean_target.
    """
    try:
        parsed = parse_artifact(artifact)
    except QueueError as exc:
        raise CoverageError("baseline report metadata is malformed") from exc
    if (not isinstance(github_repository, str) or REPOSITORY.fullmatch(github_repository) is None
            or not isinstance(bundle_key, str)
            or bundle_key not in {row["bundle_key"] for row in inventory(matrix_path)["include"]}
            or parsed.name != f"visual-review-{_positive_integer(source_run_id, 'source run')}--{bundle_key}"
            or parsed.expired or parsed.size_in_bytes > MAX_REPORT_ARCHIVE_BYTES
            or parsed.head_sha != source_sha or parsed.head_branch != "master"
            or not isinstance(owner, dict) or type(owner.get("id")) is not int
            or not isinstance(owner.get("head_repository"), dict)
            or not valid_owner(owner, repository=github_repository, artifact=parsed,
                               workflow=DRAIN_WORKFLOW, events=DRAIN_EVENTS,
                               conclusions=frozenset({"success", "failure"}))):
        raise CoverageError("baseline review has a foreign, expired, oversized or unfinished owner")
    review = _unique_named_job(_jobs(jobs), "Review one queued capsule")
    _require_conclusion(review, "Review one queued capsule", "success")
    return {"id": parsed.artifact_id, "owner_run_id": parsed.run_id, "name": parsed.name,
            "digest": parsed.digest, "size_in_bytes": parsed.size_in_bytes}


def validate_clean_target(files: ReviewFiles, *, source_sha: str, source_run_id: int,
                          bundle_key: str, matrix_path: Path = DEFAULT_MATRIX) -> dict[str, Any]:
    """Require every authored PR capture and its clean, exact normalized verdict."""
    proof, proof_digest = _read(files.proof)
    manifest, manifest_digest = _read(files.manifest)
    report, report_digest = _read(files.report)
    if not isinstance(proof, dict) or set(proof) != PROOF_KEYS | {"bundle_key", "matrix_sha256"}:
        raise CoverageError("only a complete shared-target curation proof can supply a baseline")
    validate_target_proof(proof, matrix_path=matrix_path)
    impact = proof["compatibility_impact"]
    if not isinstance(impact, dict) or type(impact.get("schema_version")) is not int:
        raise CoverageError("compatibility impact must use an integer schema version")
    # A first shared generation has no prior diff. Its protected curator conservatively
    # requests compatibility testing with an empty inventory; that is not a skip decision.
    unknown_impact = {"schema_version": 1, "compatibility_required": True, "paths": [], "impact_paths": []}
    if (not isinstance(impact, dict) or impact != unknown_impact
            or type(impact.get("schema_version")) is not int
            or type(impact.get("compatibility_required")) is not bool):
        _validate_compatibility_impact(impact)
    contract = default_contract()
    if (not isinstance(source_sha, str) or admission.SHA.fullmatch(source_sha) is None
            or proof["source_sha"] != source_sha
            or proof["source_run_id"] != _positive_integer(source_run_id, "source run")
            or proof["bundle_key"] != bundle_key or proof["matrix_kind"] != "pr-anchors"
            or proof["scenario_contract_sha256"] != contract.sha256
            or proof["manifest_sha256"] != manifest_digest):
        raise CoverageError("baseline review has another source, target, profile, contract or manifest")
    targets = {row["bundle_key"]: row for row in inventory(matrix_path)["include"]}
    target = targets[bundle_key]
    complete_matrix = load_matrix(matrix_path)
    matrix = select_release_target(complete_matrix, target["minecraft_target"])
    artifacts = sorted(row["artifact_node"] for row in matrix["artifacts"])
    expected = {}
    for artifact in artifacts:
        for name in contract.scenarios_for_profile("pr"):
            scenario = contract.scenario(name)
            for role in scenario.roles:
                for step in role.steps:
                    if step.capture is not None:
                        expected[f"{artifact}/{name}/{role.role}/{step.id}"] = step.capture
    paired = proof["review_mode"] == "reference-comparison"
    verdicts = validate(manifest, report, require_paired=paired)
    if (len(manifest) != len(expected) or {item["label"] for item in manifest} != set(expected)
            or _positive_integer(proof["frame_count"], "frame count") != len(expected)):
        raise CoverageError("baseline is missing exact complete loader/scenario capture coverage")
    if paired != (proof["visual_reference"] is not None):
        raise CoverageError("baseline review reference disagrees with its semantic mode")
    images = set()
    for item in manifest:
        capture = expected[item["label"]]
        if (item["capture_id"] != capture.capture_id or item["expectation"] != capture.expectation
                or item["review_regions"] != [list(region) for region in contract.review_regions[capture.capture_id]]
                or paired != ("reference_path" in item)):
            raise CoverageError("baseline manifest disagrees with an authored semantic checkpoint")
        images.add(item["path"])
        if paired:
            reference_label = f"fabric-{complete_matrix['unit_test_version']}/" + item["label"].split("/", 1)[1]
            if item["reference_label"] != reference_label:
                raise CoverageError("baseline review references a foreign semantic anchor lane")
            images.add(item["reference_path"])
    if (_positive_integer(proof["image_count"], "image count") != len(images)
            or _positive_integer(proof["image_bytes"], "image bytes") > MAX_REVIEW_TOTAL_BYTES):
        raise CoverageError("baseline image inventory disagrees with its bounded curation proof")
    if any(not item["semantic_valid"] or item["defect"] or item["anomalies"]
           or item["matches_reference"] is not (True if paired else None) for item in verdicts):
        raise CoverageError("baseline requires a completely clean semantic review for every capture")
    return {"bundle_key": bundle_key, "artifact_nodes": artifacts,
            "source_artifact_ids": sorted(item["id"] for item in proof["artifact_inventory"]),
            "frame_count": len(expected), "proof_sha256": proof_digest,
            "manifest_sha256": manifest_digest, "report_sha256": report_digest}


def _entries(repository: Path, commit: str, paths: tuple[str, ...]) -> dict[str, tuple[str, str]]:
    admission._commit(repository, commit)
    raw = admission._git(repository, "ls-tree", "-r", "-z", commit, "--", *paths)
    if not raw or not raw.endswith(b"\0"):
        raise CoverageError("coverage source inventory is absent or truncated")
    entries = {}
    for entry in raw[:-1].split(b"\0"):
        try:
            metadata, encoded = entry.split(b"\t", 1)
            mode, kind, oid = metadata.decode("ascii").split(" ")
            path = repository_path(encoded.decode("utf-8"))
        except (ValueError, UnicodeError) as exc:
            raise CoverageError("coverage source inventory is malformed") from exc
        if (mode not in {"100644", "100755"} or kind != "blob" or path in entries
                or admission.SHA.fullmatch(oid) is None
                or not any(path == prefix or path.startswith(prefix + "/") for prefix in paths)):
            raise CoverageError("coverage source inventory has a foreign or non-regular entry")
        entries[path] = (mode, oid)
    if any(not any(path == prefix or path.startswith(prefix + "/") for path in entries) for prefix in paths):
        raise CoverageError("coverage source inventory is missing a declared owner")
    return entries


def policy_fingerprint(repository: Path, commit: str, *, verify_executing: bool = False) -> str:
    """Bind the issuer, selectors, harness, validators and build/controller policy together."""
    entries = _entries(repository, commit, POLICY_PATHS)
    if verify_executing:
        for name, (_mode, oid) in entries.items():
            path = admission.REPO / name
            if path.is_symlink() or path.resolve() != path:
                raise CoverageError("executing coverage policy traverses a symbolic link")
            with path.open("rb") as stream:
                raw = stream.read(admission.MAX_POLICY_BYTES + 1)
            if len(raw) > admission.MAX_POLICY_BYTES:
                raise CoverageError("executing coverage policy exceeds its byte limit")
            actual = hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest()
            if actual != oid:
                raise CoverageError("executing coverage policy differs from its supplied commit")
    return digest(admission.canonical(entries))


def module_fingerprints(repository: Path, commit: str, graph: ModuleGraph | None = None) -> dict[str, str]:
    """Hash complete module trees plus compile/runtime dependencies and API providers.

    Wiring contributes its own composition source, without recursively treating an assembly's
    unrelated dependencies as dependencies of every feature it wires.
    """
    graph = load_graph() if graph is None else graph
    entries = _entries(repository, commit, tuple(module.path for module in graph.modules))
    own = {module.id: digest(admission.canonical({path: entry for path, entry in entries.items()
                                                 if path.startswith(module.path + "/")}))
           for module in graph.modules}
    fingerprints = {}
    for module in graph.modules:
        closure = {module.id}
        wiring = set()
        while True:
            before = set(closure)
            for current in tuple(closure):
                closure.update(graph.by_id[current].dependencies)
            for binding in graph.bindings:
                if binding.impact == "propagate" and closure.intersection(binding.consumers):
                    closure.update((binding.api, *binding.providers))
                    wiring.add(binding.composition)
            if before == closure:
                break
        fingerprints[module.id] = digest(admission.canonical({
            "graph_sha256": graph.sha256, "modules": {key: own[key] for key in sorted(closure)},
            "wiring": {key: own[key] for key in sorted(wiring)},
        }))
    return fingerprints


def create_baseline(reviews: dict[str, ReviewFiles], *, repository: Path, source_sha: str,
                    source_run_id: int, matrix_path: Path = DEFAULT_MATRIX) -> dict[str, Any]:
    """Produce a full baseline payload only after caller-owned GitHub authentication.

    The workflow must authenticate the complete successful source graph and the protected
    report owners. This payload alone, including a locally created payload, authorizes no skip.
    """
    expected = {row["bundle_key"] for row in inventory(matrix_path)["include"]}
    if set(reviews) != expected:
        raise CoverageError("baseline needs one clean report for every matrix target")
    policy_sha256 = policy_fingerprint(repository, source_sha, verify_executing=True)
    targets = [validate_clean_target(reviews[key], source_sha=source_sha, source_run_id=source_run_id,
                                     bundle_key=key, matrix_path=matrix_path) for key in sorted(expected)]
    raw_ids = [identifier for target in targets for identifier in target["source_artifact_ids"]]
    if len(raw_ids) != len(set(raw_ids)):
        raise CoverageError("baseline targets duplicate source artifact identities")
    return {"schema_version": 1, "kind": BASELINE_KIND, "profile": "pr", "coverage": "full",
            "source_sha": source_sha, "source_run_id": source_run_id,
            "matrix_sha256": digest(matrix_path.read_bytes()),
            "scenario_contract_sha256": default_contract().sha256,
            "module_graph_sha256": load_graph().sha256, "policy_sha256": policy_sha256,
            "module_fingerprints": module_fingerprints(repository, source_sha), "targets": targets}
