#!/usr/bin/env python3
"""Build and validate compact public evidence for optional-mod compatibility E2E."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import stat
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))
sys.path.insert(0, str(REPO / "scripts" / "release"))

from check_visual_review import (  # noqa: E402
    ReviewError,
    load as load_review_json,
    validate as validate_review,
    validate_compatibility_references,
    validate_input,
)
from mod_compatibility import (  # noqa: E402
    CompatibilityContract,
    CompatibilityContractError,
    load_contract as load_compatibility_contract,
    resolve_lane,
)
from packaged_runtime import RuntimeFailure, inspect_screenshot  # noqa: E402
from scenario_contract import (  # noqa: E402
    ScenarioContract,
    ScenarioContractError,
    load_contract as load_scenario_contract,
)
from version_branches import parse_version_branch  # noqa: E402
from visual_evidence import (  # noqa: E402
    VisualEvidenceError,
    parse_finite_json_float,
    validate_screenshot_metrics,
)


SCHEMA_VERSION = 2
LEGACY_SCHEMA_VERSION = 1
KIND = "quick-skin-public-mod-compatibility"
MANIFEST_NAME = "manifest.json"
SOURCE_SCENARIO = "mod-compatibility"
PUBLIC_CAPTURE_IDS = (
    "mod-compatibility.client_a.baseline_with_mod",
    "mod-compatibility.client_a.apply_local_skin_with_mod",
)
PUBLIC_IMAGE_SIZE = (1280, 720)
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_IMAGE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_IMAGE_BYTES = 512 * 1024 * 1024
MAX_LANES = 64
MAX_NOT_APPLICABLE = 64
MAX_IMAGES = MAX_LANES * 4
MAX_TEXT = 4096
SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SAFE_ID = re.compile(r"^[a-z0-9][a-z0-9_.-]{0,255}$")
LOADER = frozenset({"fabric", "forge", "neoforge"})

MANIFEST_FIELDS = frozenset(
    {
        "schema_version",
        "kind",
        "repository",
        "contracts",
        "release",
        "provenance",
        "lanes",
        "not_applicable",
    }
)
CONTRACT_FIELDS = frozenset({"scenario_sha256", "compatibility_sha256"})
RELEASE_FIELDS = frozenset({"branch", "version", "loaders"})
PROVENANCE_FIELDS = frozenset(
    {
        "implementation_sha",
        "base_run_id",
        "source_sha",
        "target_sha",
        "compatibility_run_id",
        "publication_run_id",
        "coverage_sha",
    }
)
LANE_FIELDS = frozenset(
    {
        "lane_id",
        "artifact_node",
        "version",
        "loader",
        "mod",
        "mod_name",
        "mod_version",
        "mod_version_id",
        "review_run_id",
        "reviewed_frame_count",
        "review_manifest_sha256",
        "curation_proof_sha256",
        "review_report_sha256",
        "frames",
    }
)
FRAME_FIELDS = frozenset(
    {
        "capture_id",
        "reference_capture_id",
        "title",
        "expectation",
        "runtime_evidence",
        "review_regions",
        "candidate_semantic_sha256",
        "reference_semantic_sha256",
        "semantic_changed_fraction",
        "perceptual_delta",
        "semantic_valid",
        "matches_reference",
        "defect",
        "candidate",
        "reference",
    }
)
IMAGE_FIELDS = frozenset({"source", "derivative"})
SOURCE_IMAGE_FIELDS = frozenset(
    {"file_sha256", "width", "height", "pixel_validation"}
)
DERIVATIVE_FIELDS = frozenset(
    {"asset", "format", "file_sha256", "width", "height", "pixel_validation"}
)
NOT_APPLICABLE_FIELDS = frozenset(
    {"artifact_node", "version", "loader", "mod", "mod_name", "reason"}
)
PROOF_FIELDS = frozenset(
    {
        "schema_version",
        "kind",
        "source_run_id",
        "source_sha",
        "target_branch",
        "target_sha",
        "compatibility_run_id",
        "implementation_sha",
        "artifact_node",
        "runtime_version",
        "loader",
        "mod",
        "mod_name",
        "mod_version",
        "mod_version_id",
        "scenario_contract_sha256",
        "compatibility_contract_sha256",
        "manifest_sha256",
        "frame_count",
        "artifact_inventory",
    }
)
PROOF_ARTIFACT_FIELDS = frozenset(
    {"id", "name", "size_in_bytes", "digest", "run_id"}
)
COMPLETION_FIELDS = frozenset(
    {"schema_version", "kind", "source_run_id", "lane", "report_sha256"}
)


class CompatibilityEvidenceError(ValueError):
    """Raised when compatibility evidence cannot be published safely."""


class CompatibilityContractDriftError(CompatibilityEvidenceError):
    """Raised when a selected bundle binds superseded contracts."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def read_json(path: Path, label: str, *, maximum_bytes: int = MAX_MANIFEST_BYTES) -> Any:
    descriptor = -1
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or path.is_symlink()
            or metadata.st_size <= 0
            or metadata.st_size > maximum_bytes
        ):
            raise ValueError(
                f"file must be regular and contain between 1 and {maximum_bytes} bytes"
            )
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
            or opened.st_size != metadata.st_size
        ):
            raise ValueError("file changed while opening")
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            descriptor = -1
            payload = handle.read(maximum_bytes + 1)
        if len(payload) != metadata.st_size:
            raise ValueError("file changed while reading")
        return json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite,
            parse_float=parse_finite_json_float,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise CompatibilityEvidenceError(f"cannot read {label} {path}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise CompatibilityEvidenceError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _exact_object(value: Any, fields: frozenset[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        found = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise CompatibilityEvidenceError(
            f"{label} fields disagree: expected={sorted(fields)}, found={found}"
        )
    return value


def _text(value: Any, label: str, *, maximum: int = MAX_TEXT) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or value != value.strip()
        or len(value) > maximum
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise CompatibilityEvidenceError(f"{label} must be bounded printable text")
    return value


def _safe_id(value: Any, label: str) -> str:
    text = _text(value, label, maximum=256)
    if SAFE_ID.fullmatch(text) is None:
        raise CompatibilityEvidenceError(f"{label} is not a safe identifier")
    return text


def _sha256(value: Any, label: str) -> str:
    text = _text(value, label, maximum=64)
    if SHA256.fullmatch(text) is None:
        raise CompatibilityEvidenceError(f"{label} must be a lowercase SHA-256")
    return text


def _commit(value: Any, label: str) -> str:
    text = _text(value, label, maximum=40)
    if COMMIT_SHA.fullmatch(text) is None:
        raise CompatibilityEvidenceError(f"{label} must be a lowercase commit SHA")
    return text


def _positive_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CompatibilityEvidenceError(f"{label} must be a positive integer")
    return value


def _finite_fraction(value: Any, label: str) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or not 0 <= value <= 1
    ):
        raise CompatibilityEvidenceError(f"{label} must be a finite fraction")
    return float(value)


def _loader(value: Any, label: str) -> str:
    text = _text(value, label, maximum=16)
    if text not in LOADER:
        raise CompatibilityEvidenceError(f"{label} is not a supported loader")
    return text


def _branch(value: Any, label: str) -> tuple[str, str, tuple[str, ...]]:
    text = _text(value, label, maximum=256)
    parsed = parse_version_branch(text)
    if parsed is None:
        raise CompatibilityEvidenceError(f"{label} is not a release branch")
    return text, parsed.version, parsed.loaders


def _expected_plan(
    branch: str,
    contract: CompatibilityContract,
) -> tuple[dict[str, Any], dict[tuple[str, str], dict[str, str]]]:
    _, version, loaders = _branch(branch, "release branch")
    runnable: dict[str, Any] = {}
    not_applicable: dict[tuple[str, str], dict[str, str]] = {}
    for loader in loaders:
        artifact_node = f"{loader}-{version}"
        for mod in contract.mods:
            reason: str | None = None
            if loader not in mod.loaders:
                reason = f"Quick Skin does not implement {mod.name} for {loader}"
            if reason is None:
                excluded = next(
                    (
                        item
                        for item in mod.excluded_lanes
                        if item.runtime_version == version and item.loader == loader
                    ),
                    None,
                )
                if excluded is not None:
                    reason = excluded.reason
            if reason is None and (
                mod.supported_game_versions is not None
                and version not in mod.supported_game_versions
            ):
                reason = (
                    f"Quick Skin does not implement {mod.name} for Minecraft {version}"
                )
            matches = []
            if reason is None:
                matches = [
                    artifact
                    for artifact in mod.artifacts
                    if artifact.loader == loader and version in artifact.game_versions
                ]
                if not matches:
                    reason = (
                        f"Upstream publishes no locked compatible {mod.name} artifact for "
                        f"Minecraft {version} / {loader}"
                    )
                elif len(matches) != 1:
                    raise CompatibilityEvidenceError(
                        f"compatibility contract is ambiguous for {mod.id}/{version}/{loader}"
                    )
            if reason is not None:
                not_applicable[(artifact_node, mod.id)] = {
                    "artifact_node": artifact_node,
                    "version": version,
                    "loader": loader,
                    "mod": mod.id,
                    "mod_name": mod.name,
                    "reason": reason,
                }
                continue
            try:
                lane = resolve_lane(
                    contract,
                    mod_id=mod.id,
                    artifact_node=artifact_node,
                    runtime_version=version,
                    loader=loader,
                )
            except CompatibilityContractError as exc:
                raise CompatibilityEvidenceError(str(exc)) from exc
            runnable[lane.id] = lane
    return runnable, not_applicable


def validate_plan(
    plan: Any,
    *,
    compatibility_run_id: int,
    contract: CompatibilityContract,
) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, str]]]:
    if not isinstance(plan, dict):
        raise CompatibilityEvidenceError("compatibility plan must be an object")
    required = {
        "schema_version",
        "release_branch",
        "base_matrix_kind",
        "compatibility_contract_sha256",
        "lock_revision",
        "runnable",
        "not_applicable",
        "source_run_id",
        "source_branch",
        "source_sha",
        "target_branch",
        "target_sha",
    }
    if set(plan) != required:
        raise CompatibilityEvidenceError("compatibility plan fields are invalid")
    if plan["schema_version"] != 1:
        raise CompatibilityEvidenceError("compatibility plan schema is unsupported")
    target_branch, version, _loaders = _branch(
        plan.get("target_branch"), "plan.target_branch"
    )
    if plan.get("release_branch") != target_branch:
        raise CompatibilityEvidenceError("compatibility plan release branch mismatch")
    source_branch = _text(plan.get("source_branch"), "plan.source_branch", maximum=256)
    if plan.get("base_matrix_kind") not in {"pr-anchors", "native-anchors"}:
        raise CompatibilityEvidenceError("compatibility plan base matrix kind is invalid")
    _positive_int(plan.get("source_run_id"), "plan.source_run_id")
    _commit(plan.get("source_sha"), "plan.source_sha")
    _commit(plan.get("target_sha"), "plan.target_sha")
    if plan.get("compatibility_contract_sha256") != contract.sha256:
        raise CompatibilityEvidenceError("compatibility plan contract hash drifted")
    if plan.get("lock_revision") != contract.lock_revision:
        raise CompatibilityEvidenceError("compatibility plan lock revision drifted")

    expected_runnable, expected_na = _expected_plan(target_branch, contract)
    runnable = plan.get("runnable")
    not_applicable = plan.get("not_applicable")
    if (
        not isinstance(runnable, list)
        or len(runnable) > MAX_LANES
        or not isinstance(not_applicable, list)
        or len(not_applicable) > MAX_NOT_APPLICABLE
    ):
        raise CompatibilityEvidenceError("compatibility plan inventory is invalid")

    selected: dict[str, dict[str, Any]] = {}
    for index, row in enumerate(runnable):
        if not isinstance(row, dict):
            raise CompatibilityEvidenceError(f"plan runnable row {index} is invalid")
        lane_id = _safe_id(row.get("id"), f"plan.runnable[{index}].id")
        expected = expected_runnable.get(lane_id)
        if expected is None or lane_id in selected:
            raise CompatibilityEvidenceError(f"unexpected or duplicate runnable lane {lane_id}")
        required_values = {
            "artifact_node": expected.artifact_node,
            "runtime_version": expected.runtime_version,
            "loader": expected.loader,
            "compatibility_mod": expected.mod.id,
            "compatibility_name": expected.mod.name,
            "compatibility_version": expected.artifact.version_number,
            "compatibility_version_id": expected.artifact.version_id,
            "compatibility_contract_sha256": contract.sha256,
        }
        if any(row.get(key) != value for key, value in required_values.items()):
            raise CompatibilityEvidenceError(f"runnable lane identity drifted: {lane_id}")
        scenarios = row.get("scenarios")
        if not isinstance(scenarios, str) or scenarios.split(",")[0] != SOURCE_SCENARIO:
            raise CompatibilityEvidenceError(f"runnable lane lacks compatibility profile: {lane_id}")
        selected[lane_id] = row
    if set(selected) != set(expected_runnable):
        raise CompatibilityEvidenceError(
            "runnable compatibility inventory is incomplete: "
            f"missing={sorted(set(expected_runnable) - set(selected))}, "
            f"extra={sorted(set(selected) - set(expected_runnable))}"
        )

    normalized_na: dict[tuple[str, str], dict[str, str]] = {}
    for index, row in enumerate(not_applicable):
        if not isinstance(row, dict) or set(row) != {
            "artifact_node",
            "runtime_version",
            "loader",
            "mod",
            "name",
            "status",
            "reason",
        }:
            raise CompatibilityEvidenceError(f"plan N/A row {index} is invalid")
        key = (row.get("artifact_node"), row.get("mod"))
        expected = expected_na.get(key) if all(isinstance(item, str) for item in key) else None
        normalized = {
            "artifact_node": row.get("artifact_node"),
            "version": row.get("runtime_version"),
            "loader": row.get("loader"),
            "mod": row.get("mod"),
            "mod_name": row.get("name"),
            "reason": row.get("reason"),
        }
        if (
            expected is None
            or key in normalized_na
            or row.get("status") != "not-applicable"
            or normalized != expected
        ):
            raise CompatibilityEvidenceError(f"N/A compatibility identity drifted: {key}")
        normalized_na[key] = normalized
    if normalized_na != expected_na:
        raise CompatibilityEvidenceError("N/A compatibility inventory is incomplete")

    identity = {
        "branch": target_branch,
        "version": version,
        "source_branch": source_branch,
        "base_run_id": plan["source_run_id"],
        "source_sha": plan["source_sha"],
        "target_sha": plan["target_sha"],
        "compatibility_run_id": compatibility_run_id,
    }
    return identity, selected, sorted(normalized_na.values(), key=lambda item: (
        item["version"], item["loader"], item["mod"]
    ))


def validate_curation_proof(
    proof: Any,
    *,
    plan_row: dict[str, Any],
    identity: dict[str, Any],
    implementation_sha: str,
    scenario_contract: ScenarioContract,
    compatibility_contract: CompatibilityContract,
    manifest_path: Path,
) -> dict[str, Any]:
    proof = _exact_object(proof, PROOF_FIELDS, "curation proof")
    if proof.get("schema_version") != 1 or proof.get("kind") != (
        "quick-skin-mod-compatibility-review-input"
    ):
        raise CompatibilityEvidenceError("curation proof identity is invalid")
    expected = {
        "source_run_id": identity["base_run_id"],
        "source_sha": identity["source_sha"],
        "target_branch": identity["branch"],
        "target_sha": identity["target_sha"],
        "compatibility_run_id": identity["compatibility_run_id"],
        "implementation_sha": implementation_sha,
        "artifact_node": plan_row["artifact_node"],
        "runtime_version": plan_row["runtime_version"],
        "loader": plan_row["loader"],
        "mod": plan_row["compatibility_mod"],
        "mod_name": plan_row["compatibility_name"],
        "mod_version": plan_row["compatibility_version"],
        "mod_version_id": plan_row["compatibility_version_id"],
        "scenario_contract_sha256": scenario_contract.sha256,
        "compatibility_contract_sha256": compatibility_contract.sha256,
    }
    if any(proof.get(key) != value for key, value in expected.items()):
        raise CompatibilityEvidenceError(
            f"curation proof disagrees with lane {plan_row['id']}"
        )
    frame_count = _positive_int(proof.get("frame_count"), "curation proof frame_count")
    if frame_count > 512:
        raise CompatibilityEvidenceError("curation proof frame count is too large")
    manifest_sha256 = _sha256(
        proof.get("manifest_sha256"), "curation proof manifest_sha256"
    )
    if manifest_sha256 != sha256_file(manifest_path):
        raise CompatibilityEvidenceError("curation proof manifest digest mismatch")
    inventory = proof.get("artifact_inventory")
    if not isinstance(inventory, dict) or set(inventory) != {"base", "candidate"}:
        raise CompatibilityEvidenceError("curation proof artifact inventory is invalid")
    for kind, record in inventory.items():
        record = _exact_object(record, PROOF_ARTIFACT_FIELDS, f"proof artifact {kind}")
        _positive_int(record.get("id"), f"proof artifact {kind}.id")
        _positive_int(record.get("run_id"), f"proof artifact {kind}.run_id")
        _positive_int(record.get("size_in_bytes"), f"proof artifact {kind}.size")
        digest = _text(record.get("digest"), f"proof artifact {kind}.digest", maximum=71)
        if re.fullmatch(r"sha256:[0-9a-f]{64}", digest) is None:
            raise CompatibilityEvidenceError(f"proof artifact {kind} digest is invalid")
        _text(record.get("name"), f"proof artifact {kind}.name", maximum=512)
    return proof


def _validate_completion(
    completion: Any,
    *,
    lane_id: str,
    compatibility_run_id: int,
    report_path: Path,
) -> None:
    completion = _exact_object(completion, COMPLETION_FIELDS, "lane completion")
    if (
        completion.get("schema_version") != 1
        or completion.get("kind") != "quick-skin-mod-compatibility-lane-complete"
        or completion.get("source_run_id") != compatibility_run_id
        or completion.get("lane") != lane_id
        or completion.get("report_sha256") != sha256_file(report_path)
    ):
        raise CompatibilityEvidenceError(f"lane completion mismatch for {lane_id}")


def _snapshot_png(source: Path, destination: Path, expected_digest: str) -> None:
    try:
        metadata = source.lstat()
    except OSError as exc:
        raise CompatibilityEvidenceError(f"cannot inspect source image {source}: {exc}") from exc
    if (
        not stat.S_ISREG(metadata.st_mode)
        or source.is_symlink()
        or metadata.st_size <= 0
        or metadata.st_size > MAX_IMAGE_BYTES
    ):
        raise CompatibilityEvidenceError(f"source image is invalid: {source}")
    descriptor = -1
    try:
        descriptor = os.open(source, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
            or opened.st_size != metadata.st_size
        ):
            raise CompatibilityEvidenceError(f"source image changed while opening: {source}")
        digest = hashlib.sha256()
        with os.fdopen(descriptor, "rb", closefd=True) as input_stream, destination.open(
            "xb"
        ) as output_stream:
            descriptor = -1
            copied = 0
            for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                copied += len(chunk)
                if copied > MAX_IMAGE_BYTES:
                    raise CompatibilityEvidenceError(f"source image grew: {source}")
                digest.update(chunk)
                output_stream.write(chunk)
            output_stream.flush()
            os.fsync(output_stream.fileno())
        if copied != metadata.st_size or digest.hexdigest() != expected_digest:
            raise CompatibilityEvidenceError(f"source image digest changed: {source}")
    except CompatibilityEvidenceError:
        destination.unlink(missing_ok=True)
        raise
    except OSError as exc:
        destination.unlink(missing_ok=True)
        raise CompatibilityEvidenceError(f"cannot snapshot source image {source}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _encode_webp(source: Path, destination: Path) -> None:
    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - protected jobs install the lockfile
        raise CompatibilityEvidenceError(
            "Pillow is required to compact compatibility evidence"
        ) from exc
    try:
        Image.MAX_IMAGE_PIXELS = 20_000_000
        with Image.open(source) as image:
            if image.format != "PNG" or image.size != (1920, 1080):
                raise CompatibilityEvidenceError(
                    f"compatibility source is not a canonical 1920x1080 PNG: {source}"
                )
            image.load()
            rendered = image.convert("RGB")
            rendered.thumbnail(PUBLIC_IMAGE_SIZE, Image.Resampling.LANCZOS)
            if rendered.size != PUBLIC_IMAGE_SIZE:
                raise CompatibilityEvidenceError(
                    f"compatibility derivative size drifted: {rendered.size}"
                )
            rendered.save(destination, "WEBP", quality=80, method=6, exact=True)
    except CompatibilityEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise CompatibilityEvidenceError(
            f"cannot encode compatibility image {source}: {exc}"
        ) from exc


def _public_image(
    source: Path,
    *,
    staged_bundle: Path,
    temporary_root: Path,
    derivatives: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    source_digest = source.stem
    if source.suffix != ".png" or SHA256.fullmatch(source_digest) is None:
        raise CompatibilityEvidenceError(f"review image is not content-addressed: {source}")
    cached = derivatives.get(source_digest)
    if cached is not None:
        return cached
    try:
        source_metrics = inspect_screenshot(source, expected_format="PNG")
    except RuntimeFailure as exc:
        raise CompatibilityEvidenceError(str(exc)) from exc
    if (
        source_metrics["file_sha256"] != source_digest
        or (source_metrics["width"], source_metrics["height"]) != (1920, 1080)
    ):
        raise CompatibilityEvidenceError(f"review image metadata mismatch: {source}")
    snapshot = temporary_root / f".{source_digest}.source.png"
    rendering = temporary_root / f".{source_digest}.rendering.webp"
    _snapshot_png(source, snapshot, source_digest)
    try:
        _encode_webp(snapshot, rendering)
    finally:
        snapshot.unlink(missing_ok=True)
    try:
        derivative_metrics = inspect_screenshot(rendering, expected_format="WEBP")
    except RuntimeFailure as exc:
        rendering.unlink(missing_ok=True)
        raise CompatibilityEvidenceError(str(exc)) from exc
    derivative_digest = derivative_metrics["file_sha256"]
    asset = f"images/{derivative_digest}.webp"
    destination = staged_bundle / asset
    if destination.exists():
        if sha256_file(destination) != derivative_digest:
            raise CompatibilityEvidenceError(f"derivative digest collision: {destination}")
        rendering.unlink()
    else:
        os.replace(rendering, destination)
    record = {
        "source": {
            "file_sha256": source_digest,
            "width": source_metrics["width"],
            "height": source_metrics["height"],
            "pixel_validation": source_metrics,
        },
        "derivative": {
            "asset": asset,
            "format": "webp",
            "file_sha256": derivative_digest,
            "width": derivative_metrics["width"],
            "height": derivative_metrics["height"],
            "pixel_validation": derivative_metrics,
        },
    }
    derivatives[source_digest] = record
    return record


def _manifest_image_path(input_root: Path, raw_path: Any, label: str) -> Path:
    path_text = _text(raw_path, label, maximum=512)
    path = PurePosixPath(path_text)
    if (
        len(path.parts) != 3
        or path.parts[:2] != (input_root.name, "images")
        or path.as_posix() != path_text
        or re.fullmatch(r"[0-9a-f]{64}[.]png", path.name) is None
    ):
        raise CompatibilityEvidenceError(f"{label} escapes the review image root")
    return input_root / "images" / path.name


def build_bundle(
    *,
    plan_path: Path,
    lanes_root: Path,
    output_root: Path,
    repository: str,
    compatibility_run_id: int,
    implementation_sha: str,
    publication_run_id: int,
    scenario_contract_path: Path,
    compatibility_contract_path: Path,
) -> Path:
    """Validate every clean lane and publish only compatibility-specific image pairs."""

    if REPOSITORY.fullmatch(repository) is None:
        raise CompatibilityEvidenceError("repository must use the owner/name form")
    compatibility_run_id = _positive_int(
        compatibility_run_id, "compatibility_run_id"
    )
    publication_run_id = _positive_int(publication_run_id, "publication_run_id")
    implementation_sha = _commit(implementation_sha, "implementation_sha")
    try:
        scenario_contract = load_scenario_contract(scenario_contract_path)
        compatibility_contract = load_compatibility_contract(
            compatibility_contract_path
        )
    except (ScenarioContractError, CompatibilityContractError, OSError) as exc:
        raise CompatibilityEvidenceError(f"cannot load compatibility contracts: {exc}") from exc
    plan = read_json(plan_path, "compatibility plan")
    identity, plan_rows, not_applicable = validate_plan(
        plan,
        compatibility_run_id=compatibility_run_id,
        contract=compatibility_contract,
    )
    if lanes_root.is_symlink():
        raise CompatibilityEvidenceError("lane evidence root cannot be a symlink")
    root = lanes_root.resolve()
    if not root.is_dir() or root.is_symlink():
        raise CompatibilityEvidenceError("lane evidence root must be a real directory")
    expected_lane_directories = set(plan_rows)
    root_entries = list(root.iterdir())
    if any(path.is_symlink() or not path.is_dir() for path in root_entries):
        raise CompatibilityEvidenceError(
            "lane evidence root may contain only real lane directories"
        )
    actual_lane_directories = {path.name for path in root_entries}
    if actual_lane_directories != expected_lane_directories:
        raise CompatibilityEvidenceError(
            "lane evidence inventory mismatch: "
            f"missing={sorted(expected_lane_directories - actual_lane_directories)}, "
            f"extra={sorted(actual_lane_directories - expected_lane_directories)}"
        )

    if output_root.is_symlink():
        raise CompatibilityEvidenceError("compatibility output root cannot be a symlink")
    destination_root = output_root.resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    destination = destination_root / identity["branch"]
    if destination.exists() or destination.is_symlink():
        raise CompatibilityEvidenceError(
            f"refusing to replace compatibility bundle {destination}"
        )
    temporary_root = Path(
        tempfile.mkdtemp(prefix=f".{identity['branch']}.compatibility-", dir=destination_root)
    )
    staged_bundle = temporary_root / identity["branch"]
    (staged_bundle / "images").mkdir(parents=True)
    derivatives: dict[str, dict[str, Any]] = {}
    public_lanes: list[dict[str, Any]] = []
    try:
        expected_capture_ids = [
            capture.capture_id
            for capture in scenario_contract.captures
            if capture.scenario == SOURCE_SCENARIO
        ]
        if expected_capture_ids != list(PUBLIC_CAPTURE_IDS):
            raise CompatibilityEvidenceError(
                "public compatibility checkpoint contract drifted"
            )
        for lane_id in sorted(plan_rows):
            plan_row = plan_rows[lane_id]
            lane_root = root / lane_id
            capsule = lane_root / "capsule"
            report_root = lane_root / "report"
            completion_path = lane_root / "completion" / "mod-compatibility-lane-complete.json"
            input_root = capsule / "review-input"
            manifest_path = input_root / "visual-review-manifest.json"
            proof_path = capsule / "curation-proof.json"
            report_path = report_root / "visual-review-report.json"
            report_manifest_path = report_root / "review-input" / "visual-review-manifest.json"
            report_proof_path = report_root / "curation-proof.json"
            report_completion_path = report_root / "visual-review-completion.json"
            metadata_path = lane_root / "metadata.json"
            required_files = (
                manifest_path,
                proof_path,
                report_path,
                report_manifest_path,
                report_proof_path,
                report_completion_path,
                completion_path,
                metadata_path,
            )
            if any(not path.is_file() or path.is_symlink() for path in required_files):
                raise CompatibilityEvidenceError(f"lane {lane_id} is incomplete")
            if manifest_path.read_bytes() != report_manifest_path.read_bytes():
                raise CompatibilityEvidenceError(f"lane {lane_id} report manifest drifted")
            if proof_path.read_bytes() != report_proof_path.read_bytes():
                raise CompatibilityEvidenceError(f"lane {lane_id} report proof drifted")

            proof = validate_curation_proof(
                read_json(proof_path, "curation proof"),
                plan_row=plan_row,
                identity=identity,
                implementation_sha=implementation_sha,
                scenario_contract=scenario_contract,
                compatibility_contract=compatibility_contract,
                manifest_path=manifest_path,
            )
            try:
                manifest = load_review_json(manifest_path, "review manifest")
                validate_compatibility_references(
                    manifest,
                    scenario_contract=scenario_contract_path,
                    artifact_node=plan_row["artifact_node"],
                    mod_id=plan_row["compatibility_mod"],
                )
                validated_frame_count = validate_input(
                    manifest, input_root, require_paired=True
                )
                report = load_review_json(report_path, "review report")
                verdicts = validate_review(manifest, report, require_paired=True)
            except ReviewError as exc:
                raise CompatibilityEvidenceError(str(exc)) from exc
            if validated_frame_count != proof["frame_count"]:
                raise CompatibilityEvidenceError(f"lane {lane_id} frame count drifted")
            if validated_frame_count != len(expected_capture_ids):
                raise CompatibilityEvidenceError(
                    f"lane {lane_id} does not cover the exact compatibility checkpoints"
                )
            completion_state = read_json(
                report_completion_path, "visual review completion", maximum_bytes=1024 * 1024
            )
            if (
                not isinstance(completion_state, dict)
                or set(completion_state)
                != {"schema_version", "state", "manifest_frames", "report_verdicts"}
                or completion_state.get("schema_version") != 1
                or completion_state.get("state") != "complete"
                or completion_state.get("manifest_frames") != validated_frame_count
                or completion_state.get("report_verdicts") != validated_frame_count
            ):
                raise CompatibilityEvidenceError(
                    f"lane {lane_id} visual review is not complete"
                )
            if any(
                verdict["semantic_valid"] is not True
                or verdict["matches_reference"] is not True
                or verdict["defect"] is not False
                or verdict["anomalies"]
                for verdict in verdicts
            ):
                raise CompatibilityEvidenceError(f"lane {lane_id} is not clean")
            _validate_completion(
                read_json(completion_path, "lane completion", maximum_bytes=1024 * 1024),
                lane_id=lane_id,
                compatibility_run_id=compatibility_run_id,
                report_path=report_path,
            )
            metadata = read_json(metadata_path, "lane publication metadata", maximum_bytes=1024 * 1024)
            if not isinstance(metadata, dict) or set(metadata) != {"review_run_id"}:
                raise CompatibilityEvidenceError(f"lane {lane_id} metadata is invalid")
            review_run_id = _positive_int(
                metadata.get("review_run_id"), f"lane {lane_id} review_run_id"
            )
            verdict_by_label = {verdict["label"]: verdict for verdict in verdicts}
            if [entry["capture_id"] for entry in manifest] != expected_capture_ids:
                raise CompatibilityEvidenceError(
                    f"lane {lane_id} compatibility capture product is incomplete"
                )
            public_frames: list[dict[str, Any]] = []
            for entry in manifest:
                verdict = verdict_by_label[entry["label"]]
                capture = scenario_contract.capture_by_id(entry["capture_id"])
                reference_capture_id = capture.compatibility_reference_capture_id
                if reference_capture_id is None:
                    raise CompatibilityEvidenceError(
                        f"compatibility capture lacks reference: {entry['capture_id']}"
                    )
                candidate_path = _manifest_image_path(
                    input_root, entry["path"], f"{lane_id} candidate path"
                )
                reference_path = _manifest_image_path(
                    input_root, entry["reference_path"], f"{lane_id} reference path"
                )
                public_frames.append(
                    {
                        "capture_id": entry["capture_id"],
                        "reference_capture_id": reference_capture_id,
                        "title": capture.title,
                        "expectation": entry["expectation"],
                        "runtime_evidence": entry["runtime_evidence"],
                        "review_regions": entry["review_regions"],
                        "candidate_semantic_sha256": entry[
                            "candidate_semantic_sha256"
                        ],
                        "reference_semantic_sha256": entry[
                            "reference_semantic_sha256"
                        ],
                        "semantic_changed_fraction": entry[
                            "semantic_changed_fraction"
                        ],
                        "perceptual_delta": entry["perceptual_delta"],
                        "semantic_valid": verdict["semantic_valid"],
                        "matches_reference": verdict["matches_reference"],
                        "defect": verdict["defect"],
                        "candidate": _public_image(
                            candidate_path,
                            staged_bundle=staged_bundle,
                            temporary_root=temporary_root,
                            derivatives=derivatives,
                        ),
                        "reference": _public_image(
                            reference_path,
                            staged_bundle=staged_bundle,
                            temporary_root=temporary_root,
                            derivatives=derivatives,
                        ),
                    }
                )
            public_lanes.append(
                {
                    "lane_id": lane_id,
                    "artifact_node": plan_row["artifact_node"],
                    "version": plan_row["runtime_version"],
                    "loader": plan_row["loader"],
                    "mod": plan_row["compatibility_mod"],
                    "mod_name": plan_row["compatibility_name"],
                    "mod_version": plan_row["compatibility_version"],
                    "mod_version_id": plan_row["compatibility_version_id"],
                    "review_run_id": review_run_id,
                    "reviewed_frame_count": validated_frame_count,
                    "review_manifest_sha256": sha256_file(manifest_path),
                    "curation_proof_sha256": sha256_file(proof_path),
                    "review_report_sha256": sha256_file(report_path),
                    "frames": public_frames,
                }
            )

        manifest = {
            "schema_version": SCHEMA_VERSION,
            "kind": KIND,
            "repository": repository,
            "contracts": {
                "scenario_sha256": scenario_contract.sha256,
                "compatibility_sha256": compatibility_contract.sha256,
            },
            "release": {
                "branch": identity["branch"],
                "version": identity["version"],
                "loaders": list(parse_version_branch(identity["branch"]).loaders),
            },
            "provenance": {
                "implementation_sha": implementation_sha,
                "base_run_id": identity["base_run_id"],
                "source_sha": identity["source_sha"],
                "target_sha": identity["target_sha"],
                "compatibility_run_id": compatibility_run_id,
                "publication_run_id": publication_run_id,
                "coverage_sha": identity["target_sha"],
            },
            "lanes": public_lanes,
            "not_applicable": not_applicable,
        }
        (staged_bundle / MANIFEST_NAME).write_text(
            json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        validate_bundle(
            temporary_root,
            identity["branch"],
            expected_repository=repository,
            expected_compatibility_run_id=compatibility_run_id,
            expected_coverage_sha=identity["target_sha"],
            scenario_contract_path=scenario_contract_path,
            compatibility_contract_path=compatibility_contract_path,
        )
        os.replace(staged_bundle, destination)
        return destination
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


def _validate_image_record(
    value: Any,
    *,
    bundle: Path,
    label: str,
    expected_assets: set[str],
    validated_assets: dict[str, dict[str, Any]],
) -> None:
    value = _exact_object(value, IMAGE_FIELDS, label)
    source = _exact_object(value.get("source"), SOURCE_IMAGE_FIELDS, f"{label}.source")
    derivative = _exact_object(
        value.get("derivative"), DERIVATIVE_FIELDS, f"{label}.derivative"
    )
    try:
        source_metrics = validate_screenshot_metrics(
            source.get("pixel_validation"), f"{label}.source.pixel_validation"
        )
        derivative_metrics = validate_screenshot_metrics(
            derivative.get("pixel_validation"), f"{label}.derivative.pixel_validation"
        )
    except VisualEvidenceError as exc:
        raise CompatibilityEvidenceError(str(exc)) from exc
    source_digest = _sha256(source.get("file_sha256"), f"{label}.source.file_sha256")
    if (
        source_metrics != source.get("pixel_validation")
        or source_metrics["file_sha256"] != source_digest
        or source.get("width") != 1920
        or source.get("height") != 1080
        or (source_metrics["width"], source_metrics["height"]) != (1920, 1080)
    ):
        raise CompatibilityEvidenceError(f"{label} source metadata is invalid")
    derivative_digest = _sha256(
        derivative.get("file_sha256"), f"{label}.derivative.file_sha256"
    )
    asset = derivative.get("asset")
    if (
        derivative.get("format") != "webp"
        or asset != f"images/{derivative_digest}.webp"
        or derivative.get("width") != PUBLIC_IMAGE_SIZE[0]
        or derivative.get("height") != PUBLIC_IMAGE_SIZE[1]
        or (derivative_metrics["width"], derivative_metrics["height"])
        != PUBLIC_IMAGE_SIZE
        or derivative_metrics["file_sha256"] != derivative_digest
        or derivative_metrics != derivative.get("pixel_validation")
    ):
        raise CompatibilityEvidenceError(f"{label} derivative metadata is invalid")
    expected_assets.add(asset)
    existing = validated_assets.get(asset)
    if existing is not None:
        if existing != derivative_metrics:
            raise CompatibilityEvidenceError(f"{label} reuses an asset inconsistently")
        return
    image = bundle / asset
    try:
        metadata = image.lstat()
    except OSError as exc:
        raise CompatibilityEvidenceError(f"cannot inspect {label} asset: {exc}") from exc
    if (
        not stat.S_ISREG(metadata.st_mode)
        or image.is_symlink()
        or metadata.st_size <= 0
        or metadata.st_size > MAX_IMAGE_BYTES
        or image.parent.resolve() != (bundle / "images").resolve()
        or sha256_file(image) != derivative_digest
    ):
        raise CompatibilityEvidenceError(f"{label} asset is invalid")
    try:
        actual_metrics = inspect_screenshot(image, expected_format="WEBP")
    except RuntimeFailure as exc:
        raise CompatibilityEvidenceError(str(exc)) from exc
    if actual_metrics != derivative_metrics:
        raise CompatibilityEvidenceError(f"{label} asset metrics drifted")
    validated_assets[asset] = actual_metrics


def validate_bundle(
    evidence_root: Path,
    branch: str,
    *,
    expected_repository: str | None = None,
    expected_compatibility_run_id: int | None = None,
    expected_coverage_sha: str | None = None,
    only_branch: bool = False,
    scenario_contract_path: Path = REPO / "e2e" / "scenario-contract.json",
    compatibility_contract_path: Path = REPO / "e2e" / "mod-compatibility-contract.json",
) -> dict[str, Any]:
    branch, version, loaders = _branch(branch, "branch")
    if evidence_root.is_symlink():
        raise CompatibilityEvidenceError("compatibility evidence root cannot be a symlink")
    root = evidence_root.resolve()
    bundle = root / branch
    if not bundle.is_dir() or bundle.is_symlink():
        raise CompatibilityEvidenceError(f"compatibility bundle is missing: {bundle}")
    if only_branch:
        entries = list(root.iterdir())
        if len(entries) != 1 or entries[0] != bundle:
            raise CompatibilityEvidenceError(
                "compatibility root must contain exactly the selected release branch"
            )
    manifest = read_json(bundle / MANIFEST_NAME, "compatibility manifest")
    manifest = _exact_object(manifest, MANIFEST_FIELDS, "compatibility manifest")
    schema_version = manifest.get("schema_version")
    if (
        type(schema_version) is not int
        or schema_version not in {LEGACY_SCHEMA_VERSION, SCHEMA_VERSION}
        or manifest.get("kind") != KIND
    ):
        raise CompatibilityEvidenceError("compatibility manifest identity is invalid")
    repository = _text(manifest.get("repository"), "manifest.repository", maximum=256)
    if REPOSITORY.fullmatch(repository) is None:
        raise CompatibilityEvidenceError("compatibility repository is invalid")
    if expected_repository is not None and repository != expected_repository:
        raise CompatibilityEvidenceError("compatibility repository mismatch")
    try:
        scenario_contract = load_scenario_contract(scenario_contract_path)
        compatibility_contract = load_compatibility_contract(
            compatibility_contract_path
        )
    except (ScenarioContractError, CompatibilityContractError, OSError) as exc:
        raise CompatibilityEvidenceError(f"cannot load compatibility contracts: {exc}") from exc
    contracts = _exact_object(
        manifest.get("contracts"), CONTRACT_FIELDS, "manifest.contracts"
    )
    _sha256(contracts.get("scenario_sha256"), "manifest.contracts.scenario_sha256")
    _sha256(
        contracts.get("compatibility_sha256"),
        "manifest.contracts.compatibility_sha256",
    )
    if contracts != {
        "scenario_sha256": scenario_contract.sha256,
        "compatibility_sha256": compatibility_contract.sha256,
    }:
        raise CompatibilityContractDriftError(
            "compatibility contract identity drifted"
        )
    release = _exact_object(manifest.get("release"), RELEASE_FIELDS, "manifest.release")
    if (
        release.get("branch") != branch
        or release.get("version") != version
        or release.get("loaders") != list(loaders)
    ):
        raise CompatibilityEvidenceError("compatibility release identity mismatch")
    provenance = _exact_object(
        manifest.get("provenance"), PROVENANCE_FIELDS, "manifest.provenance"
    )
    for field in ("implementation_sha", "source_sha", "target_sha", "coverage_sha"):
        _commit(provenance.get(field), f"provenance.{field}")
    for field in ("base_run_id", "compatibility_run_id", "publication_run_id"):
        _positive_int(provenance.get(field), f"provenance.{field}")
    if expected_compatibility_run_id is not None and provenance.get(
        "compatibility_run_id"
    ) != expected_compatibility_run_id:
        raise CompatibilityEvidenceError("compatibility source run mismatch")
    if expected_coverage_sha is not None and provenance.get(
        "coverage_sha"
    ) != expected_coverage_sha:
        raise CompatibilityEvidenceError("compatibility coverage SHA mismatch")

    expected_runnable, expected_na = _expected_plan(branch, compatibility_contract)
    lanes = manifest.get("lanes")
    not_applicable = manifest.get("not_applicable")
    if (
        not isinstance(lanes, list)
        or (not lanes and bool(expected_runnable))
        or len(lanes) > MAX_LANES
        or not isinstance(not_applicable, list)
        or len(not_applicable) > MAX_NOT_APPLICABLE
    ):
        raise CompatibilityEvidenceError("compatibility inventory is invalid")
    expected_capture_ids = [
        capture.capture_id
        for capture in scenario_contract.captures
        if capture.scenario == SOURCE_SCENARIO
    ]
    if expected_capture_ids != list(PUBLIC_CAPTURE_IDS):
        raise CompatibilityEvidenceError(
            "public compatibility checkpoint contract drifted"
        )
    lane_ids: set[str] = set()
    expected_assets: set[str] = set()
    validated_assets: dict[str, dict[str, Any]] = {}
    for lane_index, lane in enumerate(lanes):
        lane = _exact_object(lane, LANE_FIELDS, f"lane {lane_index}")
        lane_id = _safe_id(lane.get("lane_id"), f"lane {lane_index}.lane_id")
        expected = expected_runnable.get(lane_id)
        if expected is None or lane_id in lane_ids:
            raise CompatibilityEvidenceError(f"unexpected or duplicate lane {lane_id}")
        lane_ids.add(lane_id)
        expected_identity = {
            "artifact_node": expected.artifact_node,
            "version": expected.runtime_version,
            "loader": expected.loader,
            "mod": expected.mod.id,
            "mod_name": expected.mod.name,
            "mod_version": expected.artifact.version_number,
            "mod_version_id": expected.artifact.version_id,
        }
        if any(lane.get(key) != value for key, value in expected_identity.items()):
            raise CompatibilityEvidenceError(f"compatibility lane identity drifted: {lane_id}")
        _positive_int(lane.get("review_run_id"), f"lane {lane_id}.review_run_id")
        for field in (
            "review_manifest_sha256",
            "curation_proof_sha256",
            "review_report_sha256",
        ):
            _sha256(lane.get(field), f"lane {lane_id}.{field}")
        reviewed = _positive_int(
            lane.get("reviewed_frame_count"), f"lane {lane_id}.reviewed_frame_count"
        )
        expected_reviewed = (
            len(expected_capture_ids)
            if schema_version == SCHEMA_VERSION
            else len(scenario_contract.captures)
        )
        if reviewed != expected_reviewed:
            raise CompatibilityEvidenceError(f"lane {lane_id} review count is invalid")
        frames = lane.get("frames")
        if not isinstance(frames, list) or [
            frame.get("capture_id") if isinstance(frame, dict) else None for frame in frames
        ] != expected_capture_ids:
            raise CompatibilityEvidenceError(f"lane {lane_id} frame product is invalid")
        for frame_index, frame in enumerate(frames):
            frame = _exact_object(
                frame, FRAME_FIELDS, f"lane {lane_id} frame {frame_index}"
            )
            capture = scenario_contract.capture_by_id(frame["capture_id"])
            if (
                capture.scenario != SOURCE_SCENARIO
                or frame.get("reference_capture_id")
                != capture.compatibility_reference_capture_id
                or frame.get("title") != capture.title
                or frame.get("expectation") == ""
            ):
                raise CompatibilityEvidenceError(
                    f"lane {lane_id} frame contract drifted: {frame.get('capture_id')}"
                )
            _text(frame.get("expectation"), f"lane {lane_id} expectation")
            _text(frame.get("runtime_evidence"), f"lane {lane_id} runtime evidence")
            regions = frame.get("review_regions")
            if (
                not isinstance(regions, list)
                or not regions
                or len(regions) > 16
                or any(
                    not isinstance(region, list)
                    or len(region) != 4
                    or any(
                        isinstance(value, bool)
                        or not isinstance(value, (int, float))
                        or not math.isfinite(value)
                        or not 0 <= value <= 1
                        for value in region
                    )
                    for region in regions
                )
            ):
                raise CompatibilityEvidenceError(f"lane {lane_id} review regions are invalid")
            _sha256(
                frame.get("candidate_semantic_sha256"),
                f"lane {lane_id} candidate semantic digest",
            )
            _sha256(
                frame.get("reference_semantic_sha256"),
                f"lane {lane_id} reference semantic digest",
            )
            _finite_fraction(
                frame.get("semantic_changed_fraction"),
                f"lane {lane_id} semantic changed fraction",
            )
            _finite_fraction(
                frame.get("perceptual_delta"), f"lane {lane_id} perceptual delta"
            )
            if (
                frame.get("semantic_valid") is not True
                or frame.get("matches_reference") is not True
                or frame.get("defect") is not False
            ):
                raise CompatibilityEvidenceError(f"lane {lane_id} contains a failed verdict")
            for side in ("candidate", "reference"):
                _validate_image_record(
                    frame.get(side),
                    bundle=bundle,
                    label=f"lane {lane_id} frame {frame_index}.{side}",
                    expected_assets=expected_assets,
                    validated_assets=validated_assets,
                )
    if lane_ids != set(expected_runnable):
        raise CompatibilityEvidenceError("compatibility lane inventory is incomplete")

    normalized_na: dict[tuple[str, str], dict[str, str]] = {}
    for index, row in enumerate(not_applicable):
        row = _exact_object(row, NOT_APPLICABLE_FIELDS, f"not_applicable {index}")
        key = (row.get("artifact_node"), row.get("mod"))
        expected = expected_na.get(key) if all(isinstance(item, str) for item in key) else None
        if expected is None or key in normalized_na or row != expected:
            raise CompatibilityEvidenceError(f"N/A compatibility row drifted: {key}")
        normalized_na[key] = row
    if normalized_na != expected_na:
        raise CompatibilityEvidenceError("N/A compatibility rows are incomplete")

    images = bundle / "images"
    try:
        image_entries = list(images.iterdir())
    except OSError as exc:
        raise CompatibilityEvidenceError(f"cannot inspect compatibility images: {exc}") from exc
    actual_assets = {
        path.relative_to(bundle).as_posix()
        for path in image_entries
        if path.is_file() and not path.is_symlink()
    }
    if (
        len(image_entries) > MAX_IMAGES
        or actual_assets != expected_assets
        or any(path.is_symlink() or not path.is_file() for path in image_entries)
    ):
        raise CompatibilityEvidenceError(
            "compatibility image inventory mismatch: "
            f"missing={sorted(expected_assets - actual_assets)}, "
            f"extra={sorted(actual_assets - expected_assets)}"
        )
    total_bytes = sum(path.stat().st_size for path in image_entries)
    if total_bytes > MAX_TOTAL_IMAGE_BYTES:
        raise CompatibilityEvidenceError("compatibility images exceed their total limit")
    expected_tree = {MANIFEST_NAME, "images", *expected_assets}
    actual_tree: set[str] = set()
    for path in bundle.rglob("*"):
        if path.is_symlink():
            raise CompatibilityEvidenceError("compatibility bundle contains a symlink")
        actual_tree.add(path.relative_to(bundle).as_posix())
    if actual_tree != expected_tree:
        raise CompatibilityEvidenceError(
            "compatibility bundle tree mismatch: "
            f"missing={sorted(expected_tree - actual_tree)}, "
            f"extra={sorted(actual_tree - expected_tree)}"
        )
    return manifest


def carry_forward(
    *,
    evidence_root: Path,
    output_root: Path,
    branch: str,
    coverage_sha: str,
    expected_repository: str,
    scenario_contract_path: Path,
    compatibility_contract_path: Path,
) -> Path:
    """Rebind validated evidence to a protected non-impacting descendant head."""

    coverage_sha = _commit(coverage_sha, "coverage_sha")
    manifest = validate_bundle(
        evidence_root,
        branch,
        expected_repository=expected_repository,
        scenario_contract_path=scenario_contract_path,
        compatibility_contract_path=compatibility_contract_path,
    )
    if output_root.is_symlink():
        raise CompatibilityEvidenceError("compatibility output root cannot be a symlink")
    destination_root = output_root.resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    destination = destination_root / branch
    if destination.exists() or destination.is_symlink():
        raise CompatibilityEvidenceError(f"refusing to replace {destination}")
    temporary = Path(tempfile.mkdtemp(prefix=f".{branch}.carry-", dir=destination_root))
    staged = temporary / branch
    try:
        shutil.copytree(evidence_root.resolve() / branch, staged)
        carried = json.loads(json.dumps(manifest))
        carried["provenance"]["coverage_sha"] = coverage_sha
        (staged / MANIFEST_NAME).write_text(
            json.dumps(carried, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        validate_bundle(
            temporary,
            branch,
            only_branch=True,
            expected_repository=expected_repository,
            expected_coverage_sha=coverage_sha,
            scenario_contract_path=scenario_contract_path,
            compatibility_contract_path=compatibility_contract_path,
        )
        os.replace(staged, destination)
        return destination
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    build_parser = subparsers.add_parser("build")
    build_parser.add_argument("--plan", type=Path, required=True)
    build_parser.add_argument("--lanes-root", type=Path, required=True)
    build_parser.add_argument("--output", type=Path, required=True)
    build_parser.add_argument("--repository", required=True)
    build_parser.add_argument("--compatibility-run-id", type=int, required=True)
    build_parser.add_argument("--implementation-sha", required=True)
    build_parser.add_argument("--publication-run-id", type=int, required=True)
    build_parser.add_argument(
        "--scenario-contract", type=Path, default=REPO / "e2e/scenario-contract.json"
    )
    build_parser.add_argument(
        "--compatibility-contract",
        type=Path,
        default=REPO / "e2e/mod-compatibility-contract.json",
    )

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--evidence-root", type=Path, required=True)
    validate_parser.add_argument("--branch", required=True)
    validate_parser.add_argument("--repository")
    validate_parser.add_argument("--compatibility-run-id", type=int)
    validate_parser.add_argument("--coverage-sha")
    validate_parser.add_argument("--only-branch", action="store_true")
    validate_parser.add_argument(
        "--scenario-contract", type=Path, default=REPO / "e2e/scenario-contract.json"
    )
    validate_parser.add_argument(
        "--compatibility-contract",
        type=Path,
        default=REPO / "e2e/mod-compatibility-contract.json",
    )

    carry_parser = subparsers.add_parser("carry-forward")
    carry_parser.add_argument("--evidence-root", type=Path, required=True)
    carry_parser.add_argument("--output", type=Path, required=True)
    carry_parser.add_argument("--branch", required=True)
    carry_parser.add_argument("--coverage-sha", required=True)
    carry_parser.add_argument("--repository", required=True)
    carry_parser.add_argument(
        "--scenario-contract", type=Path, default=REPO / "e2e/scenario-contract.json"
    )
    carry_parser.add_argument(
        "--compatibility-contract",
        type=Path,
        default=REPO / "e2e/mod-compatibility-contract.json",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "build":
            result = build_bundle(
                plan_path=args.plan,
                lanes_root=args.lanes_root,
                output_root=args.output,
                repository=args.repository,
                compatibility_run_id=args.compatibility_run_id,
                implementation_sha=args.implementation_sha,
                publication_run_id=args.publication_run_id,
                scenario_contract_path=args.scenario_contract,
                compatibility_contract_path=args.compatibility_contract,
            )
        elif args.command == "carry-forward":
            result = carry_forward(
                evidence_root=args.evidence_root,
                output_root=args.output,
                branch=args.branch,
                coverage_sha=args.coverage_sha,
                expected_repository=args.repository,
                scenario_contract_path=args.scenario_contract,
                compatibility_contract_path=args.compatibility_contract,
            )
        else:
            validate_bundle(
                args.evidence_root,
                args.branch,
                only_branch=args.only_branch,
                expected_repository=args.repository,
                expected_compatibility_run_id=args.compatibility_run_id,
                expected_coverage_sha=args.coverage_sha,
                scenario_contract_path=args.scenario_contract,
                compatibility_contract_path=args.compatibility_contract,
            )
            result = f"validated compatibility evidence for {args.branch}"
        print(result)
        return 0
    except CompatibilityContractDriftError as exc:
        print(f"compatibility evidence unavailable: {exc}", file=sys.stderr)
        return 3
    except (
        CompatibilityEvidenceError,
        CompatibilityContractError,
        ReviewError,
        ScenarioContractError,
        VisualEvidenceError,
    ) as exc:
        print(f"compatibility evidence error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
