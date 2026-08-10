#!/usr/bin/env python3
"""Validate and summarize the advisory AI visual-review report."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from pathlib import PurePosixPath
from typing import Any


VERDICT_KEYS = {"label", "matches", "visible", "anomalies", "defect"}
MANIFEST_KEYS = {"path", "label", "capture_id", "kind", "expectation"}
PAIRED_MANIFEST_KEYS = MANIFEST_KEYS | {"reference_path", "reference_label"}
SHA256_PNG = re.compile(r"^(?P<digest>[0-9a-f]{64})\.png$")
MAX_REVIEW_FRAMES = 512
MAX_REVIEW_IMAGE_BYTES = 32 * 1024 * 1024
MAX_REVIEW_TOTAL_BYTES = 512 * 1024 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_LABEL_LENGTH = 512
MAX_PATH_LENGTH = 512
MAX_CAPTURE_ID_LENGTH = 128
MAX_EXPECTATION_LENGTH = 4096
MAX_VISIBLE_LENGTH = 2048
MAX_ANOMALY_LENGTH = 1024
MAX_ANOMALIES = 16


class ReviewError(ValueError):
    pass


def report_schema(
    verdict_count: int, *, labels: list[str] | None = None
) -> dict[str, Any]:
    """Return the bounded draft-07 schema used for model structured output."""

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
    label_schema: dict[str, Any] = {
        "type": "string",
        "minLength": 1,
        "maxLength": MAX_LABEL_LENGTH,
    }
    if labels is not None:
        label_schema["enum"] = labels
    verdict = {
        "type": "object",
        "additionalProperties": False,
        "required": sorted(VERDICT_KEYS),
        "properties": {
            "label": label_schema,
            "visible": {
                "type": "string",
                "minLength": 1,
                "maxLength": MAX_VISIBLE_LENGTH,
            },
            "matches": {"type": "boolean"},
            "anomalies": {
                "type": "array",
                "maxItems": MAX_ANOMALIES,
                "items": {
                    "type": "string",
                    "minLength": 1,
                    "maxLength": MAX_ANOMALY_LENGTH,
                },
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
                "minItems": verdict_count,
                "maxItems": verdict_count,
                "items": verdict,
            }
        },
    }


def extract_structured_report(envelope: Any) -> Any:
    """Extract only a validated structured result from the Claude JSON envelope."""

    if (
        not isinstance(envelope, dict)
        or envelope.get("type") != "result"
        or envelope.get("subtype") != "success"
    ):
        raise ReviewError("model output is not a successful structured result envelope")
    if "is_error" in envelope and envelope["is_error"] is not False:
        raise ReviewError("model structured result envelope reports an error")
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
        schemas.add(keys)
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
        label_parts = label.split("/")
        if len(label_parts) != 4 or ".".join(label_parts[1:]) != capture_id:
            raise ReviewError(f"manifest entry {index}.label disagrees with capture_id")
        if set(item) == PAIRED_MANIFEST_KEYS:
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
            reference_parts = reference_label.split("/")
            if (
                len(reference_parts) != 4
                or ".".join(reference_parts[1:]) != capture_id
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


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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

    observed_images: set[str] = set()
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
            actual_digest = _sha256(image)
        except OSError as exc:
            raise ReviewError(f"cannot hash curated image {image}: {exc}") from exc
        if actual_digest != match.group("digest"):
            raise ReviewError(f"curated image digest disagrees with its name: {image}")
        observed_images.add(image.name)
    if observed_images != expected_images:
        raise ReviewError(
            "curated image inventory disagrees with the manifest: "
            f"missing={sorted(expected_images - observed_images)}, "
            f"extra={sorted(observed_images - expected_images)}"
        )
    return len(entries)


def validate(
    manifest: Any, report: Any, *, require_paired: bool = False
) -> list[dict[str, Any]]:
    _entries, labels = validate_manifest(manifest, require_paired=require_paired)

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
        if not isinstance(verdict["matches"], bool) or not isinstance(verdict["defect"], bool):
            raise ReviewError(f"report verdict {index} matches/defect must be booleans")
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
        if verdict["matches"] == verdict["defect"]:
            raise ReviewError(
                f"report verdict {index} must set exactly one of matches or defect"
            )
        if verdict["defect"] and not normalized_anomalies:
            raise ReviewError(f"defect verdict {index} must describe at least one anomaly")
        if label in verdicts:
            raise ReviewError(f"review report contains duplicate label {label!r}")
        verdicts[label] = {
            "label": label,
            "visible": visible,
            "matches": verdict["matches"],
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


def markdown_text(value: Any) -> str:
    text = " ".join(str(value).split())
    for character in ("\\", "`", "*", "_", "[", "]", "<", ">"):
        text = text.replace(character, "\\" + character)
    return text


def render(verdicts: list[dict[str, Any]]) -> tuple[str, bool]:
    defects = [verdict for verdict in verdicts if verdict["defect"]]
    lines = [
        "## Advisory visual review: defects reported" if defects else "## Advisory visual review: passed",
        "",
        f"Reviewed {len(verdicts)} of {len(verdicts)} frames · "
        f"{len(verdicts) - len(defects)} clean · {len(defects)} defect(s)",
    ]
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
            "This AI review is advisory. The packaged runtime and pixel invariants remain the required gate.",
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
    args = parser.parse_args(argv)
    try:
        manifest = load(Path(args.manifest), "review manifest")
        if args.print_output_schema:
            entries, labels = validate_manifest(
                manifest, require_paired=args.require_paired
            )
            print(
                json.dumps(
                    report_schema(len(entries), labels=labels),
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
    summary, has_defects = render(verdicts)
    emit(summary)
    return 1 if has_defects else 0


if __name__ == "__main__":
    raise SystemExit(main())
