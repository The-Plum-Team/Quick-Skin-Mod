#!/usr/bin/env python3
"""Validate and summarize the advisory AI visual-review report."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any

from visual_similarity import SimilarityError, analyze_png_payloads, normalize_regions

VERDICT_KEYS = {
    "label",
    "semantic_valid",
    "matches_reference",
    "visible",
    "anomalies",
    "defect",
}
TRIAGE_KEYS = {"label", "decision", "confidence", "anomalies"}
TRIAGE_DECISIONS = frozenset({"clean", "needs_review"})
TRIAGE_CONFIDENCE = frozenset({"high", "medium", "low"})
MANIFEST_KEYS = {
    "path",
    "label",
    "capture_id",
    "kind",
    "expectation",
    "runtime_evidence",
    "image_size",
    "review_regions",
    "candidate_semantic_sha256",
}
PAIRED_MANIFEST_KEYS = MANIFEST_KEYS | {
    "reference_path",
    "reference_label",
    "reference_semantic_sha256",
    "semantic_changed_fraction",
    "perceptual_delta",
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA256_PNG = re.compile(r"^(?P<digest>[0-9a-f]{64})\.png$")
MAX_REVIEW_FRAMES = 512
MAX_REVIEW_IMAGE_BYTES = 32 * 1024 * 1024
MAX_REVIEW_TOTAL_BYTES = 512 * 1024 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_LABEL_LENGTH = 512
MAX_PATH_LENGTH = 512
MAX_CAPTURE_ID_LENGTH = 128
MAX_EXPECTATION_LENGTH = 4096
MAX_RUNTIME_EVIDENCE_LENGTH = 4096
MAX_VISIBLE_LENGTH = 2048
MAX_ANOMALY_LENGTH = 1024
MAX_ANOMALIES = 16
SAFE_MODEL_DETAIL = re.compile(r"^[a-z0-9_-]{1,64}$")
REVIEW_IMAGE_SIZE = (1920, 1080)


class ReviewError(ValueError):
    pass


def report_schema(
    verdict_count: int,
    *,
    labels: list[str] | None = None,
    paired: bool | None = None,
) -> dict[str, Any]:
    """Return the provider-compatible structure used for model output."""

    if (
        isinstance(verdict_count, bool)
        or not isinstance(verdict_count, int)
        or not 1 <= verdict_count <= MAX_REVIEW_FRAMES
    ):
        raise ReviewError(
            f"structured review schema must contain between 1 and {MAX_REVIEW_FRAMES} verdicts"
        )
    if labels is not None:
        if not isinstance(labels, list) or len(labels) != verdict_count:
            raise ReviewError("structured review schema labels must be exact and unique")
        if any(
            not isinstance(label, str)
            or not label.strip()
            or len(label) > MAX_LABEL_LENGTH
            or any(ord(character) < 32 or ord(character) == 127 for character in label)
            for label in labels
        ) or len(set(labels)) != verdict_count:
            raise ReviewError("structured review schema labels must be exact and unique")
    label_schema: dict[str, Any] = {"type": "string"}
    if labels is not None:
        label_schema["enum"] = labels
    reference_schema: dict[str, Any]
    if paired is True:
        reference_schema = {"type": "boolean"}
    elif paired is False:
        reference_schema = {"type": "null"}
    else:
        reference_schema = {"type": ["boolean", "null"]}
    verdict = {
        "type": "object",
        "additionalProperties": False,
        "required": sorted(VERDICT_KEYS),
        "properties": {
            "label": label_schema,
            "visible": {"type": "string"},
            "semantic_valid": {"type": "boolean"},
            "matches_reference": reference_schema,
            "anomalies": {
                "type": "array",
                "items": {"type": "string"},
            },
            "defect": {"type": "boolean"},
        },
    }
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["reviews"],
        "properties": {
            "reviews": {
                "type": "array",
                "items": verdict,
            }
        },
    }


def triage_schema(labels: list[str]) -> dict[str, Any]:
    """Return the compact provider-compatible schema used by the first pass."""

    report_schema(len(labels), labels=labels)
    verdict = {
        "type": "object",
        "additionalProperties": False,
        "required": sorted(TRIAGE_KEYS),
        "properties": {
            "label": {"type": "string", "enum": labels},
            "decision": {
                "type": "string",
                "enum": sorted(TRIAGE_DECISIONS),
            },
            "confidence": {
                "type": "string",
                "enum": sorted(TRIAGE_CONFIDENCE),
            },
            "anomalies": {
                "type": "array",
                "items": {"type": "string"},
            },
        },
    }
    return {
        "type": "object",
        "additionalProperties": False,
        "required": ["reviews"],
        "properties": {
            "reviews": {
                "type": "array",
                "items": verdict,
            }
        },
    }


def model_error_category(envelope: dict[str, Any]) -> str:
    """Classify a provider envelope without returning provider-authored text."""

    subtype = envelope.get("subtype")
    if subtype == "error_max_structured_output_retries":
        return "structured_output_retries_exhausted"
    api_status = envelope.get("api_error_status")
    if api_status == 429:
        return "quota_or_rate_limit"
    if api_status in {500, 502, 503, 504, 529}:
        return "overloaded"
    result = envelope.get("result")
    if not isinstance(result, str) or len(result) > MAX_EXPECTATION_LENGTH:
        return "cli_or_api"
    message = result.casefold()
    if any(
        marker in message
        for marker in ("not logged in", "authentication", "oauth", "unauthorized")
    ):
        return "authentication"
    if any(
        marker in message
        for marker in ("rate limit", "usage limit", "limit reached", "quota")
    ):
        return "quota_or_rate_limit"
    if "schema" in message and any(
        marker in message for marker in ("invalid", "unsupported", "complex")
    ):
        return "schema_rejected"
    if "overloaded" in message:
        return "overloaded"
    return "cli_or_api"


def safe_model_error_details(envelope: dict[str, Any]) -> str:
    """Return bounded diagnostics that never echo an untrusted provider response."""

    details = [f"category={model_error_category(envelope)}"]
    terminal_reason = envelope.get("terminal_reason")
    if isinstance(terminal_reason, str) and SAFE_MODEL_DETAIL.fullmatch(
        terminal_reason
    ):
        details.append(f"terminal_reason={terminal_reason}")
    api_status = envelope.get("api_error_status")
    if (
        isinstance(api_status, int)
        and not isinstance(api_status, bool)
        and 100 <= api_status <= 599
    ):
        details.append(f"api_status={api_status}")
    return ", ".join(details)


def extract_structured_report(envelope: Any) -> Any:
    """Extract only a validated structured result from the Claude JSON envelope."""

    if not isinstance(envelope, dict) or envelope.get("type") != "result":
        raise ReviewError("model output is not a successful structured result envelope")
    if envelope.get("subtype") != "success" or envelope.get("is_error") is not False:
        raise ReviewError(
            "model structured result failed "
            f"({safe_model_error_details(envelope)})"
        )
    structured_output = envelope.get("structured_output")
    if not isinstance(structured_output, dict) or set(structured_output) != {"reviews"}:
        raise ReviewError("model result must contain only structured_output.reviews")
    return structured_output["reviews"]


def emit(summary: str) -> None:
    """Append to the Actions summary when available and always echo to stdout."""
    print(summary)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(summary + "\n")


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite(value: str) -> Any:
    raise ValueError(f"non-finite JSON number {value}")


def load(path: Path, what: str, *, maximum_bytes: int = MAX_JSON_BYTES) -> Any:
    descriptor = -1
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
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
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise ReviewError(f"the {what} at {path} is not valid JSON: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _text(value: Any, label: str, *, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ReviewError(
            f"{label} must be a non-empty string of at most {maximum} characters"
        )
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ReviewError(f"{label} contains forbidden control characters")
    return value.strip()


def _label_capture_id(label: str, *, scope_segments: frozenset[int]) -> str | None:
    """Return the capture suffix from one bounded canonical frame label."""

    parts = label.split("/")
    if len(parts) - 3 not in scope_segments or not all(parts):
        return None
    return ".".join(parts[-3:])


def validate_compatibility_references(
    manifest: Any,
    *,
    scenario_contract: Path,
    artifact_node: str,
    mod_id: str,
) -> None:
    """Bind optional-mod candidate/reference identities to the exact source contract."""

    try:
        from scenario_contract import ScenarioContractError, load_contract
    except ImportError as exc:
        raise ReviewError(
            "compatibility scenario contract loader is unavailable"
        ) from exc
    entries, _labels = validate_manifest(manifest, require_paired=True)
    try:
        contract = load_contract(scenario_contract)
    except (OSError, ScenarioContractError, ValueError) as exc:
        raise ReviewError(f"compatibility scenario contract is invalid: {exc}") from exc
    if not artifact_node or "/" in artifact_node or not mod_id or "/" in mod_id:
        raise ReviewError("compatibility review scope is invalid")
    for index, item in enumerate(entries):
        candidate_parts = item["label"].split("/")
        if candidate_parts[:2] != [artifact_node, mod_id] or len(candidate_parts) != 5:
            raise ReviewError(
                f"manifest entry {index}.label disagrees with the compatibility scope"
            )
        try:
            capture = contract.capture_by_id(item["capture_id"])
        except ScenarioContractError as exc:
            raise ReviewError(
                f"manifest entry {index}.capture_id is absent from the source contract"
            ) from exc
        expected_reference = (
            capture.compatibility_reference_capture_id or capture.capture_id
        )
        reference_parts = item["reference_label"].split("/")
        reference_capture_id = _label_capture_id(
            item["reference_label"], scope_segments=frozenset({1})
        )
        if (
            reference_parts[0] != artifact_node
            or reference_capture_id != expected_reference
        ):
            raise ReviewError(
                f"manifest entry {index}.reference_label disagrees with the source contract"
            )


def _validate_semantic_metadata(
    item: dict[str, Any], index: int, *, paired: bool
) -> None:
    image_size = item.get("image_size")
    if (
        not isinstance(image_size, list)
        or image_size != list(REVIEW_IMAGE_SIZE)
        or any(isinstance(value, bool) for value in image_size)
    ):
        raise ReviewError(
            f"manifest entry {index}.image_size must be exactly "
            f"{REVIEW_IMAGE_SIZE[0]}x{REVIEW_IMAGE_SIZE[1]}"
        )
    try:
        normalized_regions = normalize_regions(item.get("review_regions"))
    except SimilarityError as exc:
        raise ReviewError(f"manifest entry {index} has invalid review_regions: {exc}") from exc
    if item["review_regions"] != [list(region) for region in normalized_regions]:
        raise ReviewError(
            f"manifest entry {index}.review_regions must use canonical finite numbers"
        )
    candidate_digest = item.get("candidate_semantic_sha256")
    if not isinstance(candidate_digest, str) or SHA256.fullmatch(candidate_digest) is None:
        raise ReviewError(
            f"manifest entry {index}.candidate_semantic_sha256 is invalid"
        )
    if not paired:
        return
    reference_digest = item.get("reference_semantic_sha256")
    if not isinstance(reference_digest, str) or SHA256.fullmatch(reference_digest) is None:
        raise ReviewError(
            f"manifest entry {index}.reference_semantic_sha256 is invalid"
        )
    metrics: dict[str, float] = {}
    for field in ("semantic_changed_fraction", "perceptual_delta"):
        value = item.get(field)
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
            or not 0.0 <= value <= 1.0
        ):
            raise ReviewError(f"manifest entry {index}.{field} is invalid")
        metrics[field] = float(value)
    if candidate_digest == reference_digest and any(metrics.values()):
        raise ReviewError(
            f"manifest entry {index} exact semantic match has non-zero differences"
        )


def validate_manifest(
    manifest: Any, *, require_paired: bool = False
) -> tuple[list[dict[str, Any]], list[str]]:
    if (
        not isinstance(manifest, list)
        or not manifest
        or len(manifest) > MAX_REVIEW_FRAMES
    ):
        raise ReviewError(
            f"review manifest must contain between 1 and {MAX_REVIEW_FRAMES} entries"
        )
    labels: list[str] = []
    schemas: set[frozenset[str]] = set()
    for index, item in enumerate(manifest):
        keys = frozenset(item) if isinstance(item, dict) else frozenset()
        if not isinstance(item, dict) or keys not in {
            frozenset(MANIFEST_KEYS),
            frozenset(PAIRED_MANIFEST_KEYS),
        }:
            raise ReviewError(
                f"manifest entry {index} must use the single or paired review schema"
            )
        paired_entry = keys == frozenset(PAIRED_MANIFEST_KEYS)
        schemas.add(keys)
        _validate_semantic_metadata(item, index, paired=paired_entry)
        label = _text(
            item.get("label"),
            f"manifest entry {index}.label",
            maximum=MAX_LABEL_LENGTH,
        )
        labels.append(label)
        _text(
            item.get("path"),
            f"manifest entry {index}.path",
            maximum=MAX_PATH_LENGTH,
        )
        capture_id = _text(
            item.get("capture_id"),
            f"manifest entry {index}.capture_id",
            maximum=MAX_CAPTURE_ID_LENGTH,
        )
        if _text(
            item.get("kind"),
            f"manifest entry {index}.kind",
            maximum=MAX_CAPTURE_ID_LENGTH,
        ) != capture_id:
            raise ReviewError(f"manifest entry {index}.kind must equal capture_id")
        _text(
            item.get("expectation"),
            f"manifest entry {index}.expectation",
            maximum=MAX_EXPECTATION_LENGTH,
        )
        _text(
            item.get("runtime_evidence"),
            f"manifest entry {index}.runtime_evidence",
            maximum=MAX_RUNTIME_EVIDENCE_LENGTH,
        )
        label_capture_id = _label_capture_id(
            label, scope_segments=frozenset({1, 2} if paired_entry else {1})
        )
        if label_capture_id != capture_id:
            raise ReviewError(f"manifest entry {index}.label disagrees with capture_id")
        if paired_entry:
            reference_label = _text(
                item.get("reference_label"),
                f"manifest entry {index}.reference_label",
                maximum=MAX_LABEL_LENGTH,
            )
            _text(
                item.get("reference_path"),
                f"manifest entry {index}.reference_path",
                maximum=MAX_PATH_LENGTH,
            )
            reference_capture_id = _label_capture_id(
                reference_label, scope_segments=frozenset({1})
            )
            candidate_parts = label.split("/")
            reference_parts = reference_label.split("/")
            if (
                reference_capture_id is None
                or (
                    len(candidate_parts) == 4
                    and reference_capture_id != capture_id
                )
                or (
                    len(candidate_parts) == 5
                    and reference_parts[0] != candidate_parts[0]
                )
            ):
                raise ReviewError(
                    f"manifest entry {index}.reference_label disagrees with capture_id"
                )
    if len(set(labels)) != len(labels):
        raise ReviewError("review manifest contains duplicate labels")
    if len(schemas) != 1:
        raise ReviewError("review manifest cannot mix single and paired entries")
    if require_paired and schemas != {frozenset(PAIRED_MANIFEST_KEYS)}:
        raise ReviewError("review manifest must pair every candidate with a reference")
    return manifest, labels


def _read_image_payload(path: Path, metadata: os.stat_result) -> bytes:
    descriptor = -1
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
            or opened.st_size != metadata.st_size
        ):
            raise OSError("file changed while opening")
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            descriptor = -1
            payload = handle.read(MAX_REVIEW_IMAGE_BYTES + 1)
        if len(payload) != metadata.st_size:
            raise OSError("file changed while reading")
        return payload
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def validate_input(
    manifest: Any, input_root: Path, *, require_paired: bool = False
) -> int:
    """Validate the exact bounded, content-addressed handoff before revealing a secret."""

    entries, _labels = validate_manifest(manifest, require_paired=require_paired)
    try:
        root_metadata = input_root.lstat()
    except OSError as exc:
        raise ReviewError(f"cannot inspect review input root {input_root}: {exc}") from exc
    if not stat.S_ISDIR(root_metadata.st_mode) or input_root.is_symlink():
        raise ReviewError("review input root must be a real directory")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", input_root.name):
        raise ReviewError("review input root must have a portable directory name")

    manifest_path = input_root / "visual-review-manifest.json"
    images = input_root / "images"
    try:
        manifest_metadata = manifest_path.lstat()
        images_metadata = images.lstat()
        top_level = {item.name for item in input_root.iterdir()}
    except OSError as exc:
        raise ReviewError(f"cannot inspect curated review input: {exc}") from exc
    if top_level != {"visual-review-manifest.json", "images"}:
        raise ReviewError("curated review input has unexpected top-level entries")
    if not stat.S_ISREG(manifest_metadata.st_mode) or manifest_path.is_symlink():
        raise ReviewError("curated review manifest must be a regular file")
    if not stat.S_ISDIR(images_metadata.st_mode) or images.is_symlink():
        raise ReviewError("curated review images must be a real directory")

    expected_images: set[str] = set()
    resolved_paths: dict[tuple[int, str], Path] = {}
    for index, item in enumerate(entries):
        path_fields = ("path", "reference_path") if "reference_path" in item else ("path",)
        for field in path_fields:
            raw_path = item[field]
            path = PurePosixPath(raw_path)
            if (
                len(path.parts) != 3
                or path.parts[:2] != (input_root.name, "images")
                or raw_path != path.as_posix()
            ):
                raise ReviewError(
                    f"manifest entry {index}.{field} escapes the curated image root"
                )
            match = SHA256_PNG.fullmatch(path.name)
            if match is None:
                raise ReviewError(
                    f"manifest entry {index}.{field} is not content-addressed"
                )
            expected_images.add(path.name)
            resolved_paths[(index, field)] = images / path.name

    observed_images: set[str] = set()
    image_payloads: dict[str, bytes] = {}
    total_bytes = 0
    try:
        image_paths = list(images.iterdir())
    except OSError as exc:
        raise ReviewError(f"cannot list curated review images: {exc}") from exc
    if not image_paths or len(image_paths) > MAX_REVIEW_FRAMES:
        raise ReviewError(
            f"curated review must contain between 1 and {MAX_REVIEW_FRAMES} images"
        )
    for image in image_paths:
        match = SHA256_PNG.fullmatch(image.name)
        try:
            metadata = image.lstat()
        except OSError as exc:
            raise ReviewError(f"cannot inspect curated image {image}: {exc}") from exc
        if (
            match is None
            or not stat.S_ISREG(metadata.st_mode)
            or image.is_symlink()
            or metadata.st_size <= 0
            or metadata.st_size > MAX_REVIEW_IMAGE_BYTES
        ):
            raise ReviewError(f"curated image is invalid: {image}")
        total_bytes += metadata.st_size
        if total_bytes > MAX_REVIEW_TOTAL_BYTES:
            raise ReviewError("curated review images exceed the total byte limit")
        try:
            payload = _read_image_payload(image, metadata)
        except OSError as exc:
            raise ReviewError(f"cannot read curated image {image}: {exc}") from exc
        actual_digest = hashlib.sha256(payload).hexdigest()
        if actual_digest != match.group("digest"):
            raise ReviewError(f"curated image digest disagrees with its name: {image}")
        observed_images.add(image.name)
        image_payloads[image.name] = payload
    if observed_images != expected_images:
        raise ReviewError(
            "curated image inventory disagrees with the manifest: "
            f"missing={sorted(expected_images - observed_images)}, "
            f"extra={sorted(observed_images - expected_images)}"
        )
    analyses: dict[tuple[str, str | None, str], dict[str, Any]] = {}
    for index, item in enumerate(entries):
        candidate = resolved_paths[(index, "path")]
        reference = resolved_paths.get((index, "reference_path"))
        regions_key = json.dumps(
            item["review_regions"],
            ensure_ascii=True,
            separators=(",", ":"),
        )
        analysis_key = (
            candidate.name,
            reference.name if reference is not None else None,
            regions_key,
        )
        analysis = analyses.get(analysis_key)
        if analysis is None:
            try:
                analysis = analyze_png_payloads(
                    image_payloads[candidate.name],
                    image_payloads[reference.name] if reference is not None else None,
                    item["review_regions"],
                    REVIEW_IMAGE_SIZE,
                )
            except SimilarityError as exc:
                raise ReviewError(
                    f"manifest entry {index} semantic image analysis failed: {exc}"
                ) from exc
            analyses[analysis_key] = analysis
        semantic_fields = {
            "image_size",
            "review_regions",
            "candidate_semantic_sha256",
        }
        if reference is not None:
            semantic_fields.update(
                {
                    "reference_semantic_sha256",
                    "semantic_changed_fraction",
                    "perceptual_delta",
                }
            )
        if any(item.get(field) != analysis.get(field) for field in semantic_fields):
            raise ReviewError(
                f"manifest entry {index} semantic fingerprints disagree with its images"
            )
    return len(entries)


def validate(
    manifest: Any, report: Any, *, require_paired: bool = False
) -> list[dict[str, Any]]:
    entries, labels = validate_manifest(manifest, require_paired=require_paired)
    paired = "reference_path" in entries[0]

    if (
        not isinstance(report, list)
        or not report
        or len(report) > MAX_REVIEW_FRAMES
    ):
        raise ReviewError(
            f"review report must contain between 1 and {MAX_REVIEW_FRAMES} verdicts"
        )
    verdicts: dict[str, dict[str, Any]] = {}
    for index, verdict in enumerate(report):
        if not isinstance(verdict, dict) or set(verdict) != VERDICT_KEYS:
            raise ReviewError(
                f"report verdict {index} must contain exactly {sorted(VERDICT_KEYS)}"
            )
        label = _text(
            verdict["label"],
            f"report verdict {index}.label",
            maximum=MAX_LABEL_LENGTH,
        )
        visible = _text(
            verdict["visible"],
            f"report verdict {index}.visible",
            maximum=MAX_VISIBLE_LENGTH,
        )
        if (
            not isinstance(verdict["semantic_valid"], bool)
            or not isinstance(verdict["defect"], bool)
        ):
            raise ReviewError(
                f"report verdict {index} semantic_valid/defect must be booleans"
            )
        matches_reference = verdict["matches_reference"]
        if paired:
            if not isinstance(matches_reference, bool):
                raise ReviewError(
                    f"paired report verdict {index} must judge matches_reference"
                )
        elif matches_reference is not None:
            raise ReviewError(
                f"semantic-only report verdict {index} cannot judge a reference"
            )
        anomalies = verdict["anomalies"]
        if not isinstance(anomalies, list) or len(anomalies) > MAX_ANOMALIES:
            raise ReviewError(f"report verdict {index}.anomalies must be an array of strings")
        normalized_anomalies = [
            _text(
                item,
                f"report verdict {index}.anomalies[{anomaly_index}]",
                maximum=MAX_ANOMALY_LENGTH,
            )
            for anomaly_index, item in enumerate(anomalies)
        ]
        expected_defect = (not verdict["semantic_valid"]) or (
            paired and matches_reference is False
        )
        if verdict["defect"] != expected_defect:
            raise ReviewError(
                f"report verdict {index} defect disagrees with its semantic/reference result"
            )
        if verdict["defect"] and not normalized_anomalies:
            raise ReviewError(f"defect verdict {index} must describe at least one anomaly")
        if label in verdicts:
            raise ReviewError(f"review report contains duplicate label {label!r}")
        verdicts[label] = {
            "label": label,
            "visible": visible,
            "semantic_valid": verdict["semantic_valid"],
            "matches_reference": matches_reference,
            "anomalies": normalized_anomalies,
            "defect": verdict["defect"],
        }

    expected = set(labels)
    reviewed = set(verdicts)
    missing = sorted(expected - reviewed)
    extra = sorted(reviewed - expected)
    if missing or extra:
        raise ReviewError(f"review label mismatch: missing={missing}, extra={extra}")
    return [verdicts[label] for label in labels]


def validate_blocking_partial(
    manifest: Any, report: Any, *, require_paired: bool = False
) -> list[dict[str, Any]]:
    """Validate a fail-closed partial report containing only confirmed defects."""

    entries, labels = validate_manifest(manifest, require_paired=require_paired)
    paired = "reference_path" in entries[0]
    if not isinstance(report, list) or not report or len(report) > len(entries):
        raise ReviewError("blocking partial report must contain confirmed defects")
    allowed = set(labels)
    normalized: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, verdict in enumerate(report):
        label = verdict.get("label") if isinstance(verdict, dict) else None
        if not isinstance(label, str) or label not in allowed or label in seen:
            raise ReviewError(f"blocking partial verdict {index} has an invalid label")
        item = entries[labels.index(label)]
        checked = validate([item], [verdict], require_paired=paired)[0]
        if not checked["defect"]:
            raise ReviewError("blocking partial report cannot contain a clean verdict")
        seen.add(label)
        normalized.append(checked)
    order = {label: index for index, label in enumerate(labels)}
    return sorted(normalized, key=lambda verdict: order[verdict["label"]])


def validate_triage(
    manifest: Any, report: Any, *, require_paired: bool = False
) -> list[dict[str, Any]]:
    """Validate compact first-pass decisions and restore manifest ordering."""

    _entries, labels = validate_manifest(manifest, require_paired=require_paired)
    if (
        not isinstance(report, list)
        or not report
        or len(report) > MAX_REVIEW_FRAMES
    ):
        raise ReviewError(
            f"review triage must contain between 1 and {MAX_REVIEW_FRAMES} verdicts"
        )
    verdicts: dict[str, dict[str, Any]] = {}
    for index, verdict in enumerate(report):
        if not isinstance(verdict, dict) or set(verdict) != TRIAGE_KEYS:
            raise ReviewError(
                f"triage verdict {index} must contain exactly {sorted(TRIAGE_KEYS)}"
            )
        label = _text(
            verdict["label"],
            f"triage verdict {index}.label",
            maximum=MAX_LABEL_LENGTH,
        )
        decision = verdict["decision"]
        confidence = verdict["confidence"]
        if decision not in TRIAGE_DECISIONS:
            raise ReviewError(f"triage verdict {index}.decision is invalid")
        if confidence not in TRIAGE_CONFIDENCE:
            raise ReviewError(f"triage verdict {index}.confidence is invalid")
        anomalies = verdict["anomalies"]
        if not isinstance(anomalies, list) or len(anomalies) > MAX_ANOMALIES:
            raise ReviewError(
                f"triage verdict {index}.anomalies must be an array of strings"
            )
        normalized_anomalies = [
            _text(
                item,
                f"triage verdict {index}.anomalies[{anomaly_index}]",
                maximum=MAX_ANOMALY_LENGTH,
            )
            for anomaly_index, item in enumerate(anomalies)
        ]
        if decision == "clean" and normalized_anomalies:
            raise ReviewError(
                f"clean triage verdict {index} cannot describe an anomaly"
            )
        if decision == "needs_review" and not normalized_anomalies:
            raise ReviewError(
                f"needs_review triage verdict {index} must describe its concern"
            )
        if label in verdicts:
            raise ReviewError(f"review triage contains duplicate label {label!r}")
        verdicts[label] = {
            "label": label,
            "decision": decision,
            "confidence": confidence,
            "anomalies": normalized_anomalies,
        }

    expected = set(labels)
    reviewed = set(verdicts)
    missing = sorted(expected - reviewed)
    extra = sorted(reviewed - expected)
    if missing or extra:
        raise ReviewError(f"triage label mismatch: missing={missing}, extra={extra}")
    return [verdicts[label] for label in labels]


def markdown_text(value: Any) -> str:
    text = " ".join(str(value).split())
    for character in ("\\", "`", "*", "_", "[", "]", "<", ">"):
        text = text.replace(character, "\\" + character)
    return text


def render(
    verdicts: list[dict[str, Any]], *, total_frames: int | None = None
) -> tuple[str, bool]:
    defects = [verdict for verdict in verdicts if verdict["defect"]]
    total = len(verdicts) if total_frames is None else total_frames
    partial = total != len(verdicts)
    lines = [
        "## Advisory visual review: defects reported" if defects else "## Advisory visual review: passed",
        "",
        f"Reviewed {len(verdicts)} of {total} frames · "
        f"{len(verdicts) - len(defects)} clean · {len(defects)} defect(s)",
    ]
    if partial:
        lines.extend(
            (
                "",
                "Opus confirmed a blocking defect, so outstanding parallel reviews were "
                "cancelled and this partial report cannot certify or release anything.",
            )
        )
    if defects:
        lines.extend(("", "### Reported defects"))
        for verdict in defects:
            lines.extend(
                (
                    "",
                    f"**{markdown_text(verdict['label'])}**",
                    f"- Seen: {markdown_text(verdict['visible'])}",
                )
            )
            lines.extend(f"- {markdown_text(item)}" for item in verdict["anomalies"])
    else:
        lines.extend(("", "Every reviewed frame matched its catalogued expectation."))
    lines.extend(
        (
            "",
            "Semantic validity is independent from reference similarity; a reference match can "
            "never hide a semantic defect.",
        )
    )
    return "\n".join(lines), bool(defects)


def write_normalized_report(path: Path, verdicts: list[dict[str, Any]]) -> None:
    """Atomically publish only the bounded, schema-normalized model output."""

    destination = path.absolute()
    if destination.exists() or destination.is_symlink():
        raise ReviewError(f"normalized report destination already exists: {destination}")
    try:
        parent = destination.parent.resolve(strict=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{destination.name}.",
            suffix=".tmp",
            dir=parent,
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "w", encoding="utf-8", closefd=True) as handle:
            json.dump(verdicts, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    except OSError as exc:
        if "temporary" in locals():
            temporary.unlink(missing_ok=True)
        raise ReviewError(f"cannot write normalized review report: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", default="visual-review-report.json")
    parser.add_argument("--manifest", default="visual-review-manifest.json")
    parser.add_argument("--input-root", default="review-input")
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--validate-input-only", action="store_true")
    actions.add_argument("--print-output-schema", action="store_true")
    parser.add_argument("--require-paired", action="store_true")
    parser.add_argument("--structured-output-envelope", action="store_true")
    parser.add_argument("--normalized-report")
    parser.add_argument("--allow-blocking-partial", action="store_true")
    parser.add_argument("--compatibility-scenario-contract", type=Path)
    parser.add_argument("--compatibility-artifact-node")
    parser.add_argument("--compatibility-mod")
    args = parser.parse_args(argv)
    try:
        manifest = load(Path(args.manifest), "review manifest")
        compatibility_arguments = (
            args.compatibility_scenario_contract,
            args.compatibility_artifact_node,
            args.compatibility_mod,
        )
        if any(value is not None for value in compatibility_arguments):
            if not all(value is not None for value in compatibility_arguments):
                raise ReviewError(
                    "compatibility validation requires its contract, artifact node and mod"
                )
            validate_compatibility_references(
                manifest,
                scenario_contract=args.compatibility_scenario_contract,
                artifact_node=args.compatibility_artifact_node,
                mod_id=args.compatibility_mod,
            )
        if args.print_output_schema:
            entries, labels = validate_manifest(
                manifest, require_paired=args.require_paired
            )
            print(
                json.dumps(
                    report_schema(
                        len(entries),
                        labels=labels,
                        paired="reference_path" in entries[0],
                    ),
                    ensure_ascii=True,
                    separators=(",", ":"),
                    sort_keys=True,
                )
            )
            return 0
        if args.validate_input_only:
            count = validate_input(
                manifest,
                Path(args.input_root),
                require_paired=args.require_paired,
            )
            print(f"Validated {count} curated visual review frames")
            return 0
        report = load(Path(args.report), "review report")
        if args.structured_output_envelope:
            report = extract_structured_report(report)
        if args.allow_blocking_partial:
            verdicts = validate_blocking_partial(
                manifest, report, require_paired=args.require_paired
            )
        else:
            verdicts = validate(
                manifest,
                report,
                require_paired=args.require_paired,
            )
        if args.normalized_report is not None:
            write_normalized_report(Path(args.normalized_report), verdicts)
    except ReviewError as exc:
        emit(f"## Advisory visual review: invalid\n\n{markdown_text(exc)}")
        return 1
    summary, has_defects = render(
        verdicts,
        total_frames=len(manifest) if args.allow_blocking_partial else None,
    )
    emit(summary)
    return 1 if has_defects else 0


if __name__ == "__main__":
    raise SystemExit(main())
