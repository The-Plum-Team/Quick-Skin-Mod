#!/usr/bin/env python3
"""Validate and maintain an exact-policy cache of protected visual verdicts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import tempfile
from collections import OrderedDict
from pathlib import Path, PurePosixPath
from typing import Any

from check_visual_review import (
    MAX_ANOMALIES,
    MAX_ANOMALY_LENGTH,
    MAX_CAPTURE_ID_LENGTH,
    MAX_JSON_BYTES,
    MAX_VISIBLE_LENGTH,
    ReviewError,
    load,
    validate,
    validate_manifest,
)


CACHE_SCHEMA_VERSION = 1
POLICY_SCHEMA_VERSION = 1
MAX_CACHE_ENTRIES = 2048
MAX_POLICY_FILE_BYTES = 1024 * 1024
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA256_PNG = re.compile(r"^(?P<digest>[0-9a-f]{64})\.png$")
CACHE_KEYS = {"schema_version", "policy_sha256", "entries"}
ENTRY_KEYS = {"key", "identity", "verdict"}
IDENTITY_KEYS = {
    "review_mode",
    "artifact_node",
    "candidate_sha256",
    "reference_sha256",
    "capture_id",
    "expectation_sha256",
}
CACHED_VERDICT_KEYS = {
    "semantic_valid",
    "matches_reference",
    "visible",
    "anomalies",
    "defect",
}


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _regular_file_digest(path: Path, label: str) -> str:
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_size <= 0
            or metadata.st_size > MAX_POLICY_FILE_BYTES
        ):
            raise OSError("file is not a bounded regular file")
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        try:
            opened = os.fstat(descriptor)
            if (
                opened.st_dev != metadata.st_dev
                or opened.st_ino != metadata.st_ino
                or opened.st_size != metadata.st_size
            ):
                raise OSError("file changed while opening")
            with os.fdopen(descriptor, "rb", closefd=True) as handle:
                descriptor = -1
                payload = handle.read(MAX_POLICY_FILE_BYTES + 1)
        finally:
            if descriptor >= 0:
                os.close(descriptor)
        if len(payload) != metadata.st_size:
            raise OSError("file changed while reading")
    except OSError as exc:
        raise ReviewError(f"cannot fingerprint the {label}: {exc}") from exc
    return _sha256(payload)


def review_policy_sha256(
    *,
    runner: Path,
    checker: Path,
    cache_codec: Path,
    scenario_contract: Path,
    release_matrix: Path,
    provider_lock: Path,
    triage_prompt: Path,
    verify_prompt: Path,
    triage_model: str,
    verify_model: str,
    review_mode: str,
    triage_chunk_size: int,
    verify_chunk_size: int,
) -> str:
    """Bind reusable verdicts to every input capable of changing their meaning."""

    if review_mode not in {"anchor-semantic", "reference-comparison"}:
        raise ReviewError("cache policy has an invalid review mode")
    if not all(
        isinstance(value, str)
        and value
        and len(value) <= 128
        and all(32 <= ord(character) < 127 for character in value)
        for value in (triage_model, verify_model)
    ):
        raise ReviewError("cache policy model identifiers are invalid")
    if any(
        isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= 8
        for value in (triage_chunk_size, verify_chunk_size)
    ):
        raise ReviewError("cache policy chunk sizes are invalid")
    policy = {
        "schema_version": POLICY_SCHEMA_VERSION,
        "review_mode": review_mode,
        "triage_model": triage_model,
        "verify_model": verify_model,
        "triage_chunk_size": triage_chunk_size,
        "verify_chunk_size": verify_chunk_size,
        "runner_sha256": _regular_file_digest(runner, "review runner"),
        "checker_sha256": _regular_file_digest(checker, "review checker"),
        "cache_codec_sha256": _regular_file_digest(cache_codec, "cache codec"),
        "scenario_contract_sha256": _regular_file_digest(
            scenario_contract, "scenario contract"
        ),
        "release_matrix_sha256": _regular_file_digest(
            release_matrix, "release matrix"
        ),
        "provider_lock_sha256": _regular_file_digest(
            provider_lock, "provider lockfile"
        ),
        "triage_prompt_sha256": _regular_file_digest(
            triage_prompt, "triage prompt"
        ),
        "verify_prompt_sha256": _regular_file_digest(
            verify_prompt, "verification prompt"
        ),
    }
    return _sha256(_canonical_json(policy))


def _bounded_text(value: Any, label: str, maximum: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ReviewError(f"{label} must be a non-empty bounded string")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise ReviewError(f"{label} contains forbidden control characters")
    return value.strip()


def _image_digest(path: Any, label: str) -> str:
    if not isinstance(path, str):
        raise ReviewError(f"{label} is invalid")
    match = SHA256_PNG.fullmatch(PurePosixPath(path).name)
    if match is None:
        raise ReviewError(f"{label} is not content-addressed")
    return match.group("digest")


def cache_identity(item: dict[str, Any], review_mode: str) -> dict[str, str]:
    """Return the label-independent semantic and pixel identity of one frame."""

    if review_mode != "reference-comparison" or "reference_path" not in item:
        raise ReviewError("only paired comparison verdicts are cacheable")
    capture_id = _bounded_text(
        item.get("capture_id"), "cache capture_id", MAX_CAPTURE_ID_LENGTH
    )
    label = _bounded_text(item.get("label"), "cache label", 512)
    if "/" not in label:
        raise ReviewError("cache label has no artifact identity")
    artifact_node = _bounded_text(
        label.split("/", 1)[0], "cache artifact node", MAX_CAPTURE_ID_LENGTH
    )
    expectation = item.get("expectation")
    if not isinstance(expectation, str):
        raise ReviewError("cache expectation is invalid")
    return {
        "review_mode": review_mode,
        "artifact_node": artifact_node,
        "candidate_sha256": _image_digest(item.get("path"), "candidate path"),
        "reference_sha256": _image_digest(
            item.get("reference_path"), "reference path"
        ),
        "capture_id": capture_id,
        "expectation_sha256": _sha256(expectation.encode("utf-8")),
    }


def cache_key(identity: dict[str, str]) -> str:
    return _sha256(_canonical_json(identity))


def _normalize_identity(value: Any, index: int) -> dict[str, str]:
    if not isinstance(value, dict) or set(value) != IDENTITY_KEYS:
        raise ReviewError(f"cache entry {index}.identity has an invalid schema")
    if value.get("review_mode") != "reference-comparison":
        raise ReviewError(f"cache entry {index} is not a paired comparison")
    for key in (
        "candidate_sha256",
        "reference_sha256",
        "expectation_sha256",
    ):
        if not isinstance(value.get(key), str) or SHA256.fullmatch(value[key]) is None:
            raise ReviewError(f"cache entry {index}.{key} is invalid")
    capture_id = _bounded_text(
        value.get("capture_id"),
        f"cache entry {index}.capture_id",
        MAX_CAPTURE_ID_LENGTH,
    )
    artifact_node = _bounded_text(
        value.get("artifact_node"),
        f"cache entry {index}.artifact_node",
        MAX_CAPTURE_ID_LENGTH,
    )
    return {**value, "artifact_node": artifact_node, "capture_id": capture_id}


def _normalize_cached_verdict(value: Any, index: int) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != CACHED_VERDICT_KEYS:
        raise ReviewError(f"cache entry {index}.verdict has an invalid schema")
    semantic_valid = value.get("semantic_valid")
    matches_reference = value.get("matches_reference")
    defect = value.get("defect")
    if not isinstance(semantic_valid, bool) or not isinstance(defect, bool):
        raise ReviewError(f"cache entry {index} has invalid verdict booleans")
    if not isinstance(matches_reference, bool):
        raise ReviewError(f"cache entry {index} must judge its reference")
    visible = _bounded_text(
        value.get("visible"), f"cache entry {index}.visible", MAX_VISIBLE_LENGTH
    )
    anomalies = value.get("anomalies")
    if not isinstance(anomalies, list) or len(anomalies) > MAX_ANOMALIES:
        raise ReviewError(f"cache entry {index}.anomalies is invalid")
    normalized_anomalies = [
        _bounded_text(
            anomaly,
            f"cache entry {index}.anomalies[{anomaly_index}]",
            MAX_ANOMALY_LENGTH,
        )
        for anomaly_index, anomaly in enumerate(anomalies)
    ]
    expected_defect = not semantic_valid or not matches_reference
    if defect != expected_defect:
        raise ReviewError(f"cache entry {index} has an incoherent defect verdict")
    if defect and not normalized_anomalies:
        raise ReviewError(f"cache entry {index} defect has no anomaly")
    return {
        "visible": visible,
        "semantic_valid": semantic_valid,
        "matches_reference": matches_reference,
        "anomalies": normalized_anomalies,
        "defect": defect,
    }


def validate_cache(value: Any, expected_policy: str) -> dict[str, Any]:
    if SHA256.fullmatch(expected_policy) is None:
        raise ReviewError("expected cache policy digest is invalid")
    if not isinstance(value, dict) or set(value) != CACHE_KEYS:
        raise ReviewError("visual review cache has an invalid schema")
    if value.get("schema_version") != CACHE_SCHEMA_VERSION:
        raise ReviewError("visual review cache has an unsupported schema")
    if value.get("policy_sha256") != expected_policy:
        raise ReviewError("visual review cache belongs to a different review policy")
    raw_entries = value.get("entries")
    if not isinstance(raw_entries, list) or len(raw_entries) > MAX_CACHE_ENTRIES:
        raise ReviewError("visual review cache exceeds its entry bound")
    normalized_entries: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, entry in enumerate(raw_entries):
        if not isinstance(entry, dict) or set(entry) != ENTRY_KEYS:
            raise ReviewError(f"cache entry {index} has an invalid schema")
        identity = _normalize_identity(entry.get("identity"), index)
        key = entry.get("key")
        if (
            not isinstance(key, str)
            or SHA256.fullmatch(key) is None
            or key != cache_key(identity)
            or key in seen
        ):
            raise ReviewError(f"cache entry {index} has an invalid or duplicate key")
        seen.add(key)
        normalized_entries.append(
            {
                "key": key,
                "identity": identity,
                "verdict": _normalize_cached_verdict(entry.get("verdict"), index),
            }
        )
    return {
        "schema_version": CACHE_SCHEMA_VERSION,
        "policy_sha256": expected_policy,
        "entries": normalized_entries,
    }


def load_cache(path: Path, expected_policy: str) -> dict[str, Any]:
    return validate_cache(
        load(path, "visual review cache", maximum_bytes=MAX_JSON_BYTES),
        expected_policy,
    )


def cached_verdicts(
    manifest: Any, cache: dict[str, Any] | None, *, review_mode: str
) -> dict[str, dict[str, Any]]:
    """Return current-label verdicts whose entire semantic/pixel key is unchanged."""

    entries, _labels = validate_manifest(manifest)
    if cache is None or review_mode != "reference-comparison":
        return {}
    by_key = {entry["key"]: entry for entry in cache["entries"]}
    hits: dict[str, dict[str, Any]] = {}
    for item in entries:
        if item["path"] == item.get("reference_path"):
            continue
        identity = cache_identity(item, review_mode)
        cached = by_key.get(cache_key(identity))
        if cached is None:
            continue
        verdict = {"label": item["label"], **cached["verdict"]}
        hits[item["label"]] = validate(
            [item], [verdict], require_paired=True
        )[0]
    return hits


def combine_caches(
    caches: list[dict[str, Any]], *, policy_sha256: str
) -> dict[str, Any]:
    """Combine newest-first immutable shards without weakening exact-policy validation."""

    ordered: OrderedDict[str, dict[str, Any]] = OrderedDict()
    for cache in reversed(caches):
        normalized = validate_cache(cache, policy_sha256)
        for entry in normalized["entries"]:
            # Callers supply newest shards first. Iterate the other way so moving a duplicate to
            # the end retains the newest verdict and preserves oldest-first eviction semantics.
            ordered.pop(entry["key"], None)
            ordered[entry["key"]] = entry
    while len(ordered) > MAX_CACHE_ENTRIES:
        ordered.popitem(last=False)
    return validate_cache(
        {
            "schema_version": CACHE_SCHEMA_VERSION,
            "policy_sha256": policy_sha256,
            "entries": list(ordered.values()),
        },
        policy_sha256,
    )


def merge_cache(
    existing: dict[str, Any] | None,
    manifest: Any,
    verdicts: Any,
    *,
    policy_sha256: str,
    review_mode: str,
) -> dict[str, Any]:
    """Append current normalized paired verdicts and retain a bounded recent set."""

    entries, labels = validate_manifest(manifest)
    if review_mode != "reference-comparison" or "reference_path" not in entries[0]:
        raise ReviewError("only paired comparison reports can update the verdict cache")
    if not isinstance(verdicts, list):
        raise ReviewError("cache update verdicts must be an array")
    manifest_by_label = dict(zip(labels, entries, strict=True))
    ordered: OrderedDict[str, dict[str, Any]] = OrderedDict()
    if existing is not None:
        normalized_existing = validate_cache(existing, policy_sha256)
        for entry in normalized_existing["entries"]:
            ordered[entry["key"]] = entry
    for index, verdict in enumerate(verdicts):
        label = verdict.get("label") if isinstance(verdict, dict) else None
        item = manifest_by_label.get(label) if isinstance(label, str) else None
        if item is None:
            raise ReviewError(f"cache update verdict {index} has an unknown label")
        normalized = validate([item], [verdict], require_paired=True)[0]
        if item["path"] == item["reference_path"]:
            continue
        identity = cache_identity(item, review_mode)
        key = cache_key(identity)
        ordered.pop(key, None)
        ordered[key] = {
            "key": key,
            "identity": identity,
            "verdict": {
                field: normalized[field] for field in sorted(CACHED_VERDICT_KEYS)
            },
        }
    while len(ordered) > MAX_CACHE_ENTRIES:
        ordered.popitem(last=False)
    return validate_cache(
        {
            "schema_version": CACHE_SCHEMA_VERSION,
            "policy_sha256": policy_sha256,
            "entries": list(ordered.values()),
        },
        policy_sha256,
    )


def write_cache(path: Path, value: dict[str, Any]) -> None:
    payload = json.dumps(value, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    if not payload or len(payload) > MAX_JSON_BYTES:
        raise ReviewError("visual review cache exceeds its byte bound")
    destination = path.absolute()
    if destination.exists() or destination.is_symlink():
        raise ReviewError(f"cache destination already exists: {destination}")
    try:
        parent = destination.parent.resolve(strict=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{destination.name}.", suffix=".tmp", dir=parent
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, destination)
    except OSError as exc:
        if "temporary" in locals():
            temporary.unlink(missing_ok=True)
        raise ReviewError(f"cannot write visual review cache: {exc}") from exc


def _policy_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--runner", type=Path, required=True)
    parser.add_argument("--checker", type=Path, required=True)
    parser.add_argument("--cache-codec", type=Path, required=True)
    parser.add_argument("--scenario-contract", type=Path, required=True)
    parser.add_argument("--release-matrix", type=Path, required=True)
    parser.add_argument("--provider-lock", type=Path, required=True)
    parser.add_argument("--triage-prompt", type=Path, required=True)
    parser.add_argument("--verify-prompt", type=Path, required=True)
    parser.add_argument("--triage-model", required=True)
    parser.add_argument("--verify-model", required=True)
    parser.add_argument(
        "--review-mode",
        required=True,
        choices=("anchor-semantic", "reference-comparison"),
    )
    parser.add_argument("--triage-chunk-size", type=int, required=True)
    parser.add_argument("--verify-chunk-size", type=int, required=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    policy_parser = commands.add_parser("policy")
    _policy_arguments(policy_parser)
    validate_parser = commands.add_parser("validate")
    validate_parser.add_argument("--cache", type=Path, required=True)
    validate_parser.add_argument("--policy-sha256", required=True)
    validate_parser.add_argument("--normalized-output", type=Path)
    combine_parser = commands.add_parser("combine")
    combine_parser.add_argument(
        "--cache", type=Path, action="append", required=True
    )
    combine_parser.add_argument("--policy-sha256", required=True)
    combine_parser.add_argument("--output", type=Path, required=True)
    update_parser = commands.add_parser("update")
    update_parser.add_argument("--existing-cache", type=Path)
    update_parser.add_argument("--manifest", type=Path, required=True)
    update_parser.add_argument("--report", type=Path, required=True)
    update_parser.add_argument("--policy-sha256", required=True)
    update_parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "policy":
            print(
                review_policy_sha256(
                    runner=args.runner,
                    checker=args.checker,
                    cache_codec=args.cache_codec,
                    scenario_contract=args.scenario_contract,
                    release_matrix=args.release_matrix,
                    provider_lock=args.provider_lock,
                    triage_prompt=args.triage_prompt,
                    verify_prompt=args.verify_prompt,
                    triage_model=args.triage_model,
                    verify_model=args.verify_model,
                    review_mode=args.review_mode,
                    triage_chunk_size=args.triage_chunk_size,
                    verify_chunk_size=args.verify_chunk_size,
                )
            )
            return 0
        if args.command == "validate":
            cache = load_cache(args.cache, args.policy_sha256)
            if args.normalized_output is not None:
                write_cache(args.normalized_output, cache)
            print(f"Validated {len(cache['entries'])} exact-policy visual verdicts")
            return 0
        if args.command == "combine":
            combined = combine_caches(
                [load_cache(path, args.policy_sha256) for path in args.cache],
                policy_sha256=args.policy_sha256,
            )
            write_cache(args.output, combined)
            print(f"Combined {len(combined['entries'])} exact-policy visual verdicts")
            return 0
        existing = (
            load_cache(args.existing_cache, args.policy_sha256)
            if args.existing_cache is not None
            else None
        )
        updated = merge_cache(
            existing,
            load(args.manifest, "cache update manifest"),
            load(args.report, "cache update report"),
            policy_sha256=args.policy_sha256,
            review_mode="reference-comparison",
        )
        write_cache(args.output, updated)
        print(f"Published {len(updated['entries'])} exact-policy visual verdicts")
        return 0
    except ReviewError as exc:
        parser.error(str(exc))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
