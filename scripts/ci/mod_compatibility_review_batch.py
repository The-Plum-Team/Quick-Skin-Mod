#!/usr/bin/env python3
"""Assemble and split one source-wide optional-mod visual-review batch."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))

from check_visual_review import (  # noqa: E402
    MAX_JSON_BYTES,
    MAX_REVIEW_FRAMES,
    MAX_REVIEW_IMAGE_BYTES,
    MAX_REVIEW_TOTAL_BYTES,
    ReviewError,
    load,
    validate,
    validate_input,
    validate_manifest,
    write_normalized_report,
)


MAX_LANES = 64
MAX_BATCH_METADATA_BYTES = 4 * 1024 * 1024
SAFE_LANE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,255}$")
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
LANE_FIELDS = frozenset(
    {
        "artifact_digest",
        "artifact_id",
        "artifact_name",
        "artifact_node",
        "artifact_size",
        "base_evidence_name",
        "id",
        "implementation_sha",
        "loader",
        "mod",
        "mod_name",
        "runtime_version",
        "source_run_id",
        "source_sha",
        "target_branch",
        "target_sha",
    }
)
PROOF_FIELDS = frozenset(
    {
        "artifact_inventory",
        "artifact_node",
        "compatibility_contract_sha256",
        "compatibility_run_id",
        "frame_count",
        "implementation_sha",
        "kind",
        "loader",
        "manifest_sha256",
        "mod",
        "mod_name",
        "mod_version",
        "mod_version_id",
        "runtime_version",
        "scenario_contract_sha256",
        "schema_version",
        "source_run_id",
        "source_sha",
        "target_branch",
        "target_sha",
    }
)
BATCH_FIELDS = frozenset(
    {
        "implementation_sha",
        "input_image_references",
        "lane_count",
        "lanes",
        "matrix_sha256",
        "schema_version",
        "source_run_id",
        "total_frames",
        "unique_images",
    }
)
BATCH_LANE_FIELDS = frozenset(
    {
        "frame_count",
        "id",
        "labels",
        "manifest_sha256",
        "proof_sha256",
    }
)


class BatchError(RuntimeError):
    """Raised when a source-wide review batch is malformed or incomplete."""


def _sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def _regular_payload(path: Path, label: str, *, maximum_bytes: int) -> bytes:
    descriptor = -1
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or path.is_symlink()
            or not 1 <= metadata.st_size <= maximum_bytes
        ):
            raise BatchError(f"{label} is not a bounded regular file")
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
            or opened.st_size != metadata.st_size
        ):
            raise BatchError(f"{label} changed while opening")
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            descriptor = -1
            payload = handle.read(maximum_bytes + 1)
        if len(payload) != metadata.st_size:
            raise BatchError(f"{label} changed while reading")
        return payload
    except OSError as exc:
        raise BatchError(f"cannot read {label}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _load_json(path: Path, label: str, *, maximum_bytes: int = MAX_JSON_BYTES) -> Any:
    try:
        return load(path, label, maximum_bytes=maximum_bytes)
    except ReviewError as exc:
        raise BatchError(str(exc)) from exc


def _write_json_new(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as handle:
            json.dump(
                value,
                handle,
                ensure_ascii=True,
                separators=(",", ":"),
                sort_keys=True,
            )
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
    except OSError as exc:
        raise BatchError(f"cannot write generated batch file {path}: {exc}") from exc
    os.chmod(path, 0o644)


def _new_directory(path: Path, label: str) -> None:
    if path.exists() or path.is_symlink():
        raise BatchError(f"refusing to replace existing {label}: {path}")
    try:
        path.mkdir(parents=True)
    except OSError as exc:
        raise BatchError(f"cannot create {label}: {exc}") from exc


def _positive_int(value: Any, label: str, *, maximum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise BatchError(f"{label} must be a positive integer")
    if maximum is not None and value > maximum:
        raise BatchError(f"{label} exceeds {maximum}")
    return value


def _matrix_lanes(matrix: Any) -> list[dict[str, Any]]:
    if not isinstance(matrix, dict) or set(matrix) != {"include"}:
        raise BatchError("review matrix must contain only include")
    lanes = matrix["include"]
    if not isinstance(lanes, list) or not 1 <= len(lanes) <= MAX_LANES:
        raise BatchError(f"review matrix must contain between 1 and {MAX_LANES} lanes")
    normalized: list[dict[str, Any]] = []
    lane_ids: set[str] = set()
    common_source_run_id: int | None = None
    common_implementation_sha: str | None = None
    for index, lane in enumerate(lanes):
        if not isinstance(lane, dict) or set(lane) != LANE_FIELDS:
            raise BatchError(f"review matrix lane {index} has an unexpected schema")
        lane_id = lane.get("id")
        if not isinstance(lane_id, str) or SAFE_LANE.fullmatch(lane_id) is None:
            raise BatchError(f"review matrix lane {index} has an invalid id")
        if lane_id in lane_ids:
            raise BatchError(f"review matrix duplicates lane {lane_id!r}")
        lane_ids.add(lane_id)
        source_run_id = _positive_int(lane.get("source_run_id"), "source_run_id")
        implementation_sha = lane.get("implementation_sha")
        if (
            not isinstance(implementation_sha, str)
            or SHA.fullmatch(implementation_sha) is None
        ):
            raise BatchError(f"review matrix lane {lane_id} has an invalid implementation")
        if common_source_run_id is None:
            common_source_run_id = source_run_id
            common_implementation_sha = implementation_sha
        elif (
            source_run_id != common_source_run_id
            or implementation_sha != common_implementation_sha
        ):
            raise BatchError("review matrix mixes source identities")
        normalized.append(lane)
    return normalized


def _validate_proof(
    proof: Any,
    *,
    lane: dict[str, Any],
    manifest_payload: bytes,
    frame_count: int,
) -> None:
    lane_id = lane["id"]
    if not isinstance(proof, dict) or set(proof) != PROOF_FIELDS:
        raise BatchError(f"lane {lane_id} curation proof has an unexpected schema")
    expected = {
        "artifact_node": lane["artifact_node"],
        "compatibility_run_id": lane["source_run_id"],
        "frame_count": frame_count,
        "implementation_sha": lane["implementation_sha"],
        "kind": "quick-skin-mod-compatibility-review-input",
        "loader": lane["loader"],
        "manifest_sha256": _sha256_bytes(manifest_payload),
        "mod": lane["mod"],
        "runtime_version": lane["runtime_version"],
        "schema_version": 1,
        "source_sha": lane["source_sha"],
        "target_branch": lane["target_branch"],
        "target_sha": lane["target_sha"],
    }
    if any(proof.get(key) != value for key, value in expected.items()):
        raise BatchError(f"lane {lane_id} curation proof drifted from its matrix")
    for key in ("scenario_contract_sha256", "compatibility_contract_sha256"):
        value = proof.get(key)
        if not isinstance(value, str) or SHA256.fullmatch(value) is None:
            raise BatchError(f"lane {lane_id} curation proof has an invalid {key}")


def _copy_regular(source: Path, destination: Path, label: str) -> None:
    payload = _regular_payload(source, label, maximum_bytes=MAX_BATCH_METADATA_BYTES)
    _write_payload_new(destination, payload, label)


def _write_payload_new(destination: Path, payload: bytes, label: str) -> None:
    try:
        with destination.open("xb") as output:
            output.write(payload)
            output.flush()
            os.fsync(output.fileno())
    except OSError as exc:
        raise BatchError(f"cannot copy {label}: {exc}") from exc
    os.chmod(destination, 0o644)


def assemble(matrix_path: Path, lanes_root: Path, output_root: Path) -> dict[str, Any]:
    matrix_payload = _regular_payload(
        matrix_path, "pending review matrix", maximum_bytes=MAX_BATCH_METADATA_BYTES
    )
    matrix = _load_json(
        matrix_path, "pending review matrix", maximum_bytes=MAX_BATCH_METADATA_BYTES
    )
    lanes = _matrix_lanes(matrix)
    try:
        lanes_metadata = lanes_root.lstat()
    except OSError as exc:
        raise BatchError(f"cannot inspect extracted lane root: {exc}") from exc
    if not stat.S_ISDIR(lanes_metadata.st_mode) or lanes_root.is_symlink():
        raise BatchError("extracted lane root must be a real directory")
    observed_lane_ids = {item.name for item in lanes_root.iterdir()}
    expected_lane_ids = {lane["id"] for lane in lanes}
    if observed_lane_ids != expected_lane_ids:
        raise BatchError("extracted lane inventory disagrees with the pending matrix")

    _new_directory(output_root, "review batch")
    output_lanes = output_root / "lanes"
    output_images = output_root / "review-input" / "images"
    output_lanes.mkdir()
    output_images.mkdir(parents=True)
    combined_manifest: list[dict[str, Any]] = []
    batch_lanes: list[dict[str, Any]] = []
    referenced_images = 0
    unique_image_count = 0
    unique_image_bytes = 0
    try:
        for lane in lanes:
            lane_id = lane["id"]
            source_lane = lanes_root / lane_id
            if source_lane.is_symlink() or not source_lane.is_dir():
                raise BatchError(f"lane {lane_id} capsule root is invalid")
            proof_path = source_lane / "curation-proof.json"
            input_root = source_lane / "review-input"
            manifest_path = input_root / "visual-review-manifest.json"
            manifest_payload = _regular_payload(
                manifest_path,
                f"lane {lane_id} manifest",
                maximum_bytes=MAX_JSON_BYTES,
            )
            manifest = _load_json(manifest_path, f"lane {lane_id} manifest")
            try:
                entries, labels = validate_manifest(manifest, require_paired=True)
                validate_input(entries, input_root, require_paired=True)
            except ReviewError as exc:
                raise BatchError(f"lane {lane_id} capsule is invalid: {exc}") from exc
            proof_payload = _regular_payload(
                proof_path,
                f"lane {lane_id} curation proof",
                maximum_bytes=MAX_BATCH_METADATA_BYTES,
            )
            proof = _load_json(
                proof_path,
                f"lane {lane_id} curation proof",
                maximum_bytes=MAX_BATCH_METADATA_BYTES,
            )
            _validate_proof(
                proof,
                lane=lane,
                manifest_payload=manifest_payload,
                frame_count=len(entries),
            )
            if len(combined_manifest) + len(entries) > MAX_REVIEW_FRAMES:
                raise BatchError("source-wide review batch exceeds the frame bound")
            lane_output = output_lanes / lane_id
            lane_output.mkdir()
            _copy_regular(
                proof_path,
                lane_output / "curation-proof.json",
                f"lane {lane_id} curation proof",
            )
            _copy_regular(
                manifest_path,
                lane_output / "visual-review-manifest.json",
                f"lane {lane_id} manifest",
            )
            for entry in entries:
                for field in ("path", "reference_path"):
                    image_name = Path(entry[field]).name
                    source_image = input_root / "images" / image_name
                    destination_image = output_images / image_name
                    source_payload = _regular_payload(
                        source_image,
                        f"lane {lane_id} image {image_name}",
                        maximum_bytes=MAX_REVIEW_IMAGE_BYTES,
                    )
                    referenced_images += 1
                    if destination_image.exists():
                        if destination_image.is_symlink() or (
                            _regular_payload(
                                destination_image,
                                f"shared image {image_name}",
                                maximum_bytes=MAX_REVIEW_IMAGE_BYTES,
                            )
                            != source_payload
                        ):
                            raise BatchError(f"content-addressed image collision: {image_name}")
                    else:
                        if unique_image_count >= MAX_REVIEW_FRAMES:
                            raise BatchError(
                                "source-wide review batch exceeds the image-count bound"
                            )
                        unique_image_bytes += len(source_payload)
                        if unique_image_bytes > MAX_REVIEW_TOTAL_BYTES:
                            raise BatchError(
                                "source-wide review batch exceeds the image-byte bound"
                            )
                        _write_payload_new(
                            destination_image,
                            source_payload,
                            f"lane {lane_id} image {image_name}",
                        )
                        unique_image_count += 1
            combined_manifest.extend(entries)
            batch_lanes.append(
                {
                    "frame_count": len(entries),
                    "id": lane_id,
                    "labels": labels,
                    "manifest_sha256": _sha256_bytes(manifest_payload),
                    "proof_sha256": _sha256_bytes(proof_payload),
                }
            )
        aggregate_manifest_path = (
            output_root / "review-input" / "visual-review-manifest.json"
        )
        _write_json_new(aggregate_manifest_path, combined_manifest)
        _regular_payload(
            aggregate_manifest_path,
            "aggregate review manifest",
            maximum_bytes=MAX_JSON_BYTES,
        )
        try:
            validate_input(
                combined_manifest,
                output_root / "review-input",
                require_paired=True,
            )
        except ReviewError as exc:
            raise BatchError(f"assembled review batch is invalid: {exc}") from exc
        batch = {
            "implementation_sha": lanes[0]["implementation_sha"],
            "input_image_references": referenced_images,
            "lane_count": len(lanes),
            "lanes": batch_lanes,
            "matrix_sha256": _sha256_bytes(matrix_payload),
            "schema_version": 1,
            "source_run_id": lanes[0]["source_run_id"],
            "total_frames": len(combined_manifest),
            "unique_images": unique_image_count,
        }
        _write_json_new(output_root / "batch.json", batch)
        return batch
    except BaseException:
        shutil.rmtree(output_root, ignore_errors=True)
        raise


def validate_batch(batch_root: Path, *, require_images: bool) -> dict[str, Any]:
    if batch_root.is_symlink() or not batch_root.is_dir():
        raise BatchError("review batch root must be a real directory")
    batch = _load_json(
        batch_root / "batch.json",
        "review batch metadata",
        maximum_bytes=MAX_BATCH_METADATA_BYTES,
    )
    if not isinstance(batch, dict) or set(batch) != BATCH_FIELDS:
        raise BatchError("review batch metadata has an unexpected schema")
    if batch.get("schema_version") != 1:
        raise BatchError("review batch metadata has an unsupported schema")
    source_run_id = _positive_int(batch.get("source_run_id"), "batch source_run_id")
    implementation_sha = batch.get("implementation_sha")
    if not isinstance(implementation_sha, str) or SHA.fullmatch(implementation_sha) is None:
        raise BatchError("review batch implementation SHA is invalid")
    lane_count = _positive_int(batch.get("lane_count"), "batch lane_count", maximum=MAX_LANES)
    total_frames = _positive_int(
        batch.get("total_frames"), "batch total_frames", maximum=MAX_REVIEW_FRAMES
    )
    unique_images = _positive_int(
        batch.get("unique_images"), "batch unique_images", maximum=MAX_REVIEW_FRAMES
    )
    input_image_references = _positive_int(
        batch.get("input_image_references"),
        "batch input_image_references",
        maximum=MAX_REVIEW_FRAMES * 2,
    )
    matrix_sha256 = batch.get("matrix_sha256")
    if not isinstance(matrix_sha256, str) or SHA256.fullmatch(matrix_sha256) is None:
        raise BatchError("review batch matrix digest is invalid")
    lanes = batch.get("lanes")
    if not isinstance(lanes, list) or len(lanes) != lane_count:
        raise BatchError("review batch lane inventory is incomplete")
    lanes_root = batch_root / "lanes"
    if lanes_root.is_symlink() or not lanes_root.is_dir():
        raise BatchError("review batch lane metadata root is invalid")
    combined: list[dict[str, Any]] = []
    observed_lane_ids: set[str] = set()
    for index, lane in enumerate(lanes):
        if not isinstance(lane, dict) or set(lane) != BATCH_LANE_FIELDS:
            raise BatchError(f"review batch lane {index} has an unexpected schema")
        lane_id = lane.get("id")
        if (
            not isinstance(lane_id, str)
            or SAFE_LANE.fullmatch(lane_id) is None
            or lane_id in observed_lane_ids
        ):
            raise BatchError(f"review batch lane {index} has an invalid id")
        observed_lane_ids.add(lane_id)
        lane_root = lanes_root / lane_id
        if lane_root.is_symlink() or not lane_root.is_dir():
            raise BatchError(f"review batch lane {lane_id} root is invalid")
        if {item.name for item in lane_root.iterdir()} != {
            "curation-proof.json",
            "visual-review-manifest.json",
        }:
            raise BatchError(f"review batch lane {lane_id} metadata inventory drifted")
        manifest_path = lane_root / "visual-review-manifest.json"
        proof_path = lane_root / "curation-proof.json"
        manifest_payload = _regular_payload(
            manifest_path,
            f"review batch lane {lane_id} manifest",
            maximum_bytes=MAX_JSON_BYTES,
        )
        proof_payload = _regular_payload(
            proof_path,
            f"review batch lane {lane_id} proof",
            maximum_bytes=MAX_BATCH_METADATA_BYTES,
        )
        if lane.get("manifest_sha256") != _sha256_bytes(manifest_payload) or lane.get(
            "proof_sha256"
        ) != _sha256_bytes(proof_payload):
            raise BatchError(f"review batch lane {lane_id} metadata digest drifted")
        manifest = _load_json(manifest_path, f"review batch lane {lane_id} manifest")
        try:
            entries, labels = validate_manifest(manifest, require_paired=True)
        except ReviewError as exc:
            raise BatchError(f"review batch lane {lane_id} manifest is invalid: {exc}") from exc
        if lane.get("frame_count") != len(entries) or lane.get("labels") != labels:
            raise BatchError(f"review batch lane {lane_id} frame identity drifted")
        combined.extend(entries)
    if {item.name for item in lanes_root.iterdir()} != observed_lane_ids:
        raise BatchError("review batch contains an unexpected lane directory")
    aggregate_manifest_path = batch_root / "review-input" / "visual-review-manifest.json"
    aggregate_manifest = _load_json(aggregate_manifest_path, "aggregate review manifest")
    if aggregate_manifest != combined or len(combined) != total_frames:
        raise BatchError("aggregate review manifest disagrees with its lane manifests")
    expected_images = {
        Path(entry[field]).name
        for entry in aggregate_manifest
        for field in ("path", "reference_path")
    }
    if len(expected_images) != unique_images:
        raise BatchError("review batch unique-image count disagrees with its manifest")
    try:
        validate_manifest(aggregate_manifest, require_paired=True)
        if require_images:
            validate_input(
                aggregate_manifest,
                batch_root / "review-input",
                require_paired=True,
            )
    except ReviewError as exc:
        raise BatchError(f"aggregate review input is invalid: {exc}") from exc
    if input_image_references != total_frames * 2:
        raise BatchError("review batch image-reference count is invalid")
    if require_images:
        images = batch_root / "review-input" / "images"
        if len(list(images.iterdir())) != unique_images:
            raise BatchError("review batch unique-image count drifted")
    return {
        **batch,
        "source_run_id": source_run_id,
        "implementation_sha": implementation_sha,
    }


def _validate_completion(completion: Any, *, frame_count: int, verdict_count: int) -> None:
    required = {"manifest_frames", "report_verdicts", "schema_version", "state"}
    if not isinstance(completion, dict) or set(completion) != required:
        raise BatchError("aggregate visual-review completion has an unexpected schema")
    if (
        completion.get("schema_version") != 1
        or completion.get("state") != "complete"
        or completion.get("manifest_frames") != frame_count
        or completion.get("report_verdicts") != verdict_count
    ):
        raise BatchError("aggregate visual review is not complete")


def split_lane(
    batch_root: Path,
    report_path: Path,
    completion_path: Path,
    lane_id: str,
    output_root: Path,
) -> int:
    batch = validate_batch(batch_root, require_images=False)
    if SAFE_LANE.fullmatch(lane_id) is None:
        raise BatchError("requested lane id is invalid")
    lane = next((item for item in batch["lanes"] if item["id"] == lane_id), None)
    if lane is None:
        raise BatchError(f"requested lane {lane_id!r} is absent from the batch")
    aggregate_manifest = _load_json(
        batch_root / "review-input" / "visual-review-manifest.json",
        "aggregate review manifest",
    )
    report = _load_json(report_path, "aggregate normalized review report")
    try:
        verdicts = validate(aggregate_manifest, report, require_paired=True)
    except ReviewError as exc:
        raise BatchError(f"aggregate normalized review report is invalid: {exc}") from exc
    completion = _load_json(
        completion_path,
        "aggregate visual-review completion",
        maximum_bytes=MAX_BATCH_METADATA_BYTES,
    )
    _validate_completion(
        completion,
        frame_count=batch["total_frames"],
        verdict_count=len(verdicts),
    )
    labels = lane["labels"]
    verdict_by_label = {verdict["label"]: verdict for verdict in verdicts}
    try:
        lane_verdicts = [verdict_by_label[label] for label in labels]
    except KeyError as exc:
        raise BatchError(f"aggregate report omits lane {lane_id} verdict {exc}") from exc
    if any(verdict["defect"] for verdict in lane_verdicts):
        raise BatchError(f"cannot split defective lane {lane_id}")
    _new_directory(output_root, f"lane {lane_id} review result")
    try:
        source_lane = batch_root / "lanes" / lane_id
        _copy_regular(
            source_lane / "curation-proof.json",
            output_root / "curation-proof.json",
            f"lane {lane_id} curation proof",
        )
        output_input = output_root / "review-input"
        output_input.mkdir()
        _copy_regular(
            source_lane / "visual-review-manifest.json",
            output_input / "visual-review-manifest.json",
            f"lane {lane_id} manifest",
        )
        write_normalized_report(output_root / "visual-review-report.json", lane_verdicts)
        _write_json_new(
            output_root / "visual-review-completion.json",
            {
                "manifest_frames": len(labels),
                "report_verdicts": len(lane_verdicts),
                "schema_version": 1,
                "state": "complete",
            },
        )
        lane_manifest = _load_json(
            output_input / "visual-review-manifest.json", f"lane {lane_id} manifest"
        )
        lane_report = _load_json(
            output_root / "visual-review-report.json", f"lane {lane_id} report"
        )
        try:
            validate(lane_manifest, lane_report, require_paired=True)
        except ReviewError as exc:
            raise BatchError(f"split lane {lane_id} report is invalid: {exc}") from exc
    except BaseException:
        shutil.rmtree(output_root, ignore_errors=True)
        raise
    return len(lane_verdicts)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="operation", required=True)
    assemble_parser = subparsers.add_parser("assemble")
    assemble_parser.add_argument("--matrix", type=Path, required=True)
    assemble_parser.add_argument("--lanes-root", type=Path, required=True)
    assemble_parser.add_argument("--output", type=Path, required=True)
    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--batch", type=Path, required=True)
    validate_parser.add_argument("--require-images", action="store_true")
    split_parser = subparsers.add_parser("split")
    split_parser.add_argument("--batch", type=Path, required=True)
    split_parser.add_argument("--report", type=Path, required=True)
    split_parser.add_argument("--completion", type=Path, required=True)
    split_parser.add_argument("--lane", required=True)
    split_parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.operation == "assemble":
            batch = assemble(args.matrix, args.lanes_root, args.output)
            print(
                "Compatibility review batch: "
                f"lanes={batch['lane_count']}, frames={batch['total_frames']}, "
                f"image_references={batch['input_image_references']}, "
                f"unique_images={batch['unique_images']}"
            )
        elif args.operation == "validate":
            batch = validate_batch(args.batch, require_images=args.require_images)
            print(
                "Validated compatibility review batch: "
                f"lanes={batch['lane_count']}, frames={batch['total_frames']}"
            )
        else:
            verdict_count = split_lane(
                args.batch,
                args.report,
                args.completion,
                args.lane,
                args.output,
            )
            print(f"Split compatibility lane {args.lane}: verdicts={verdict_count}")
        return 0
    except (BatchError, OSError, ReviewError, ValueError) as exc:
        print(f"Compatibility review batch error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
