#!/usr/bin/env python3
"""Run bounded two-stage Claude review over an authenticated screenshot capsule."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import signal
import subprocess
import sys
import threading
import time
from collections import OrderedDict
from pathlib import Path
from typing import Any, Callable

from check_visual_review import (
    MAX_JSON_BYTES,
    ReviewError,
    extract_structured_report,
    load,
    model_error_category,
    report_schema,
    triage_schema,
    validate,
    validate_input,
    validate_manifest,
    validate_triage,
    write_normalized_report,
)
from visual_review_cache import cached_verdicts, load_cache


DEFAULT_TRIAGE_CHUNK_SIZE = 8
DEFAULT_VERIFY_CHUNK_SIZE = 4
MAX_CHUNK_SIZE = 8
DEFAULT_MODEL_ATTEMPTS = 3
MAX_MODEL_ATTEMPTS = 4
DEFAULT_CALL_SPACING_SECONDS = 0.0
DEFAULT_RETRY_DELAYS = (30.0, 60.0, 120.0)
MAX_MODEL_SECONDS = 15 * 60
DEFAULT_MAX_PARALLEL_CALLS = 16
MAX_PARALLEL_CALLS = 32
TRANSIENT_MODEL_CATEGORIES = frozenset(
    {
        "cli_or_api",
        "invalid_structured_output",
        "overloaded",
        "quota_or_rate_limit",
        "structured_output_retries_exhausted",
        "timeout",
    }
)
SYNTHETIC_IDENTICAL_VISIBLE = (
    "Every authored semantic-region pixel is identical to the certified reference."
)
SYNTHETIC_REPRESENTED_VISIBLE = (
    "Authored semantic-region pixels are exact-equivalent to an AI-reviewed representative."
)
SYNTHETIC_COMPARISON_CLEAN_VISIBLE = (
    "Candidate is semantically valid and matches the certified 1.20.1 reference."
)
SYNTHETIC_SEMANTIC_CLEAN_VISIBLE = (
    "Candidate independently satisfies its checkpoint expectation."
)
AMBIGUOUS_PERCEPTUAL_DELTA = 0.01
AMBIGUOUS_CHANGED_FRACTION = 0.02


class RunnerError(RuntimeError):
    def __init__(self, category: str, stage: str, *, transient: bool) -> None:
        super().__init__(f"visual review failed at {stage} ({category})")
        self.category = category
        self.stage = stage
        self.transient = transient


class ReviewCancelled(RuntimeError):
    """Raised inside workers after another Opus chunk confirms a defect."""


ReviewProvider = Callable[
    [str, int, list[dict[str, Any]], dict[str, Any]], list[dict[str, Any]]
]


def _chunks(items: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
    if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= MAX_CHUNK_SIZE:
        raise ReviewError(f"review chunk size must be between 1 and {MAX_CHUNK_SIZE}")
    return [items[index : index + size] for index in range(0, len(items), size)]


def _checkpoint_chunks(
    items: list[dict[str, Any]], size: int
) -> list[list[dict[str, Any]]]:
    """Pack loader siblings together without exceeding the provider chunk bound."""

    _chunks([], size)
    grouped: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()
    for item in items:
        grouped.setdefault(item["capture_id"], []).append(item)
    chunks: list[list[dict[str, Any]]] = []
    current: list[dict[str, Any]] = []
    for siblings in grouped.values():
        for offset in range(0, len(siblings), size):
            bounded = siblings[offset : offset + size]
            if current and len(current) + len(bounded) > size:
                chunks.append(current)
                current = []
            current.extend(bounded)
    if current:
        chunks.append(current)
    return chunks


def build_review_plan(
    manifest: Any,
    *,
    triage_chunk_size: int = DEFAULT_TRIAGE_CHUNK_SIZE,
    cached_labels: frozenset[str] = frozenset(),
    review_identical: bool = False,
) -> dict[str, Any]:
    """Split exact semantic matches and group exact-equivalent paired representatives."""

    entries, _labels = validate_manifest(manifest)
    paired = "reference_path" in entries[0]
    # A semantic-only anchor has no reference and every frame reaches the model. Once that anchor
    # is certified, exact authored-region matches can inherit both its semantics and pixels.
    identical = [
        item
        for item in entries
        if paired
        and not review_identical
        and item["candidate_semantic_sha256"] == item["reference_semantic_sha256"]
    ]
    pending = [
        item
        for item in entries
        if (
            not paired
            or review_identical
            or item["candidate_semantic_sha256"] != item["reference_semantic_sha256"]
        )
        and item["label"] not in cached_labels
    ]
    semantic: list[dict[str, Any]] = []
    represented_by_label: dict[str, str] = {}
    equivalence_groups: OrderedDict[str, list[dict[str, Any]]] = OrderedDict()
    for item in pending:
        if not paired or review_identical:
            semantic.append(item)
            continue
        equivalence_key = json.dumps(
            {
                "capture_id": item["capture_id"],
                "expectation": item["expectation"],
                "runtime_evidence": item["runtime_evidence"],
                "review_regions": item["review_regions"],
                "candidate_semantic_sha256": item["candidate_semantic_sha256"],
                "reference_semantic_sha256": item["reference_semantic_sha256"],
            },
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        equivalence_groups.setdefault(equivalence_key, []).append(item)
    for group in equivalence_groups.values():
        representative = group[0]
        semantic.append(representative)
        for follower in group[1:]:
            represented_by_label[follower["label"]] = representative["label"]
    return {
        "paired": paired,
        "identical": identical,
        "semantic": semantic,
        "represented_by_label": represented_by_label,
        "triage_chunks": (
            _checkpoint_chunks(semantic, triage_chunk_size) if semantic else []
        ),
    }


def requires_perceptual_verification(item: dict[str, Any]) -> bool:
    """Route near-but-nonexact pairs to Opus; never turn similarity into a pass."""

    if "reference_path" not in item:
        return False
    if item["candidate_semantic_sha256"] == item["reference_semantic_sha256"]:
        return False
    return (
        item["perceptual_delta"] <= AMBIGUOUS_PERCEPTUAL_DELTA
        or item["semantic_changed_fraction"] <= AMBIGUOUS_CHANGED_FRACTION
    )


def execute_review(
    manifest: Any,
    provider: ReviewProvider,
    *,
    triage_chunk_size: int = DEFAULT_TRIAGE_CHUNK_SIZE,
    verify_chunk_size: int = DEFAULT_VERIFY_CHUNK_SIZE,
    max_parallel_calls: int = DEFAULT_MAX_PARALLEL_CALLS,
    cache_hits: dict[str, dict[str, Any]] | None = None,
    review_identical: bool = False,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Pipeline concurrent triage and verification, stopping on one confirmed defect.

    The provider must be safe for concurrent calls when ``max_parallel_calls`` is above one.
    """

    entries, labels = validate_manifest(manifest)
    paired = "reference_path" in entries[0]
    if (
        isinstance(max_parallel_calls, bool)
        or not isinstance(max_parallel_calls, int)
        or not 1 <= max_parallel_calls <= MAX_PARALLEL_CALLS
    ):
        raise ReviewError(
            f"parallel model calls must be between 1 and {MAX_PARALLEL_CALLS}"
        )
    normalized_cache_hits: dict[str, dict[str, Any]] = {}
    if cache_hits:
        for label, verdict in cache_hits.items():
            if label not in labels:
                raise ReviewError(f"cache contains unknown manifest label {label!r}")
            item = entries[labels.index(label)]
            normalized_cache_hits[label] = validate(
                [item], [verdict], require_paired=paired
            )[0]
    if review_identical and normalized_cache_hits:
        raise ReviewError(
            "review-identical requires a fresh semantic verdict for every manifest entry"
        )
    plan = build_review_plan(
        entries,
        triage_chunk_size=triage_chunk_size,
        cached_labels=frozenset(normalized_cache_hits),
        review_identical=review_identical,
    )
    final_by_label: dict[str, dict[str, Any]] = dict(normalized_cache_hits)
    for item in plan["identical"]:
        final_by_label[item["label"]] = {
            "label": item["label"],
            "visible": SYNTHETIC_IDENTICAL_VISIBLE,
            "semantic_valid": True,
            "matches_reference": True,
            "anomalies": [],
            "defect": False,
        }

    cached_defects = [
        normalized_cache_hits[label]
        for label in labels
        if label in normalized_cache_hits
        and normalized_cache_hits[label]["defect"]
    ]
    if cached_defects:
        return cached_defects, {
            "frames": len(entries),
            "paired": int(paired),
            "identical": len(plan["identical"]),
            "cached": len(normalized_cache_hits),
            "represented": len(plan["represented_by_label"]),
            "triaged": 0,
            "triage_chunks": 0,
            "escalated": 0,
            "verify_chunks": 0,
            "reviewed": len(cached_defects),
            "stopped_early": 1,
        }

    triage_by_label: dict[str, dict[str, Any]] = {}
    escalated_count = 0
    verify_chunks_count = 0
    confirmed_defects: list[dict[str, Any]] = []
    executor: concurrent.futures.ThreadPoolExecutor | None = None
    pending: dict[
        concurrent.futures.Future[list[dict[str, Any]]],
        tuple[str, list[dict[str, Any]]],
    ] = {}
    verify_index = 0

    def submit_triage(index: int, chunk: list[dict[str, Any]]) -> None:
        labels_for_chunk = [item["label"] for item in chunk]
        if executor is None:
            raise ReviewError("review executor is unavailable")
        future = executor.submit(
            provider,
            "triage",
            index,
            chunk,
            triage_schema(labels_for_chunk),
        )
        pending[future] = ("triage", chunk)

    def submit_verify(chunk: list[dict[str, Any]]) -> None:
        nonlocal verify_index, verify_chunks_count
        validation_chunk = [
            {key: value for key, value in item.items() if key != "first_review"}
            for item in chunk
        ]
        if executor is None:
            raise ReviewError("review executor is unavailable")
        future = executor.submit(
            provider,
            "verify",
            verify_index,
            chunk,
            report_schema(
                len(validation_chunk),
                labels=[item["label"] for item in validation_chunk],
                paired=paired,
            ),
        )
        verify_index += 1
        verify_chunks_count += 1
        pending[future] = ("verify", chunk)

    stopped_early = False
    try:
        if plan["triage_chunks"]:
            executor = concurrent.futures.ThreadPoolExecutor(
                max_workers=max_parallel_calls,
                thread_name_prefix="visual-review",
            )
        for chunk_index, chunk in enumerate(plan["triage_chunks"]):
            submit_triage(chunk_index, chunk)
        while pending and not stopped_early:
            completed, _waiting = concurrent.futures.wait(
                tuple(pending),
                return_when=concurrent.futures.FIRST_COMPLETED,
            )
            for future in completed:
                stage, chunk = pending.pop(future)
                raw = future.result()
                if stage == "triage":
                    normalized_triage = validate_triage(
                        chunk, raw, require_paired=paired
                    )
                    escalated: list[dict[str, Any]] = []
                    for item, triage in zip(chunk, normalized_triage, strict=True):
                        triage_by_label[item["label"]] = triage
                        if (
                            triage["decision"] == "clean"
                            and triage["confidence"] == "high"
                            and not requires_perceptual_verification(item)
                        ):
                            final_by_label[item["label"]] = {
                                "label": item["label"],
                                "visible": (
                                    SYNTHETIC_COMPARISON_CLEAN_VISIBLE
                                    if paired
                                    else SYNTHETIC_SEMANTIC_CLEAN_VISIBLE
                                ),
                                "semantic_valid": True,
                                "matches_reference": True if paired else None,
                                "anomalies": [],
                                "defect": False,
                            }
                        else:
                            escalated.append({**item, "first_review": triage})
                    escalated_count += len(escalated)
                    for verify_chunk in _checkpoint_chunks(
                        escalated, verify_chunk_size
                    ):
                        submit_verify(verify_chunk)
                    continue

                validation_chunk = [
                    {key: value for key, value in item.items() if key != "first_review"}
                    for item in chunk
                ]
                verdicts = validate(
                    validation_chunk, raw, require_paired=paired
                )
                defects = [verdict for verdict in verdicts if verdict["defect"]]
                if defects:
                    confirmed_defects.extend(defects)
                    stopped_early = True
                    cancel = getattr(provider, "cancel", None)
                    if callable(cancel):
                        cancel()
                    for queued in pending:
                        queued.cancel()
                    break
                for verdict in verdicts:
                    final_by_label[verdict["label"]] = verdict
    except BaseException:
        cancel = getattr(provider, "cancel", None)
        if callable(cancel):
            cancel()
        for queued in pending:
            queued.cancel()
        raise
    finally:
        if executor is not None:
            executor.shutdown(wait=True, cancel_futures=True)

    if stopped_early:
        ordered_defects = [
            verdict
            for label in labels
            for verdict in confirmed_defects
            if verdict["label"] == label
        ]
        return ordered_defects, {
            "frames": len(entries),
            "paired": int(paired),
            "identical": len(plan["identical"]),
            "cached": len(normalized_cache_hits),
            "represented": len(plan["represented_by_label"]),
            "triaged": len(triage_by_label),
            "triage_chunks": len(plan["triage_chunks"]),
            "escalated": escalated_count,
            "verify_chunks": verify_chunks_count,
            "reviewed": len(ordered_defects),
            "stopped_early": 1,
        }

    for follower_label, representative_label in plan["represented_by_label"].items():
        representative = final_by_label.get(representative_label)
        if representative is None:
            raise ReviewError(
                "review runner did not produce representative verdict "
                f"{representative_label!r} for {follower_label!r}"
            )
        final_by_label[follower_label] = {
            **representative,
            "label": follower_label,
            "visible": SYNTHETIC_REPRESENTED_VISIBLE,
        }

    missing = [label for label in labels if label not in final_by_label]
    if missing:
        raise ReviewError(f"review runner did not produce every manifest label: {missing}")
    final = validate(
        entries,
        [final_by_label[label] for label in labels],
        require_paired=paired,
    )
    return final, {
        "frames": len(entries),
        "paired": int(paired),
        "identical": len(plan["identical"]),
        "cached": len(normalized_cache_hits),
        "represented": len(plan["represented_by_label"]),
        "triaged": len(plan["semantic"]),
        "triage_chunks": len(plan["triage_chunks"]),
        "escalated": escalated_count,
        "verify_chunks": verify_chunks_count,
        "reviewed": len(final),
        "stopped_early": 0,
    }


def _write_json_new(path: Path, value: Any, *, mode: int = 0o600) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(path, mode)


def _write_failure(path: Path | None, error: RunnerError) -> None:
    if path is None or path.exists() or path.is_symlink():
        return
    _write_json_new(
        path,
        {
            "schema_version": 1,
            "category": error.category,
            "stage": error.stage,
            "transient": error.transient,
        },
    )


class ClaudeProvider:
    """Invoke the pinned CLI with one exact manifest and a read-only tool surface."""

    def __init__(
        self,
        *,
        capsule: Path,
        work_root: Path,
        claude: Path,
        triage_prompt: str,
        verify_prompt: str,
        triage_model: str,
        verify_model: str,
        paired: bool,
        attempts: int,
        call_spacing_seconds: float,
    ) -> None:
        self.capsule = capsule
        self.work_root = work_root
        self.claude = claude
        self.prompts = {"triage": triage_prompt, "verify": verify_prompt}
        self.models = {"triage": triage_model, "verify": verify_model}
        self.paired = paired
        self.attempts = attempts
        self.call_spacing_seconds = call_spacing_seconds
        self.last_call_started: float | None = None
        self._pace_lock = threading.Lock()
        self._artifact_lock = threading.Lock()
        self._process_lock = threading.Lock()
        self._processes: set[subprocess.Popen[bytes]] = set()
        self._cancelled = threading.Event()

    def cancel(self) -> None:
        self._cancelled.set()
        with self._process_lock:
            processes = tuple(self._processes)
        for process in processes:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except (OSError, ProcessLookupError):
                continue

    def _pace(self) -> None:
        if self.call_spacing_seconds <= 0:
            return
        while True:
            if self._cancelled.is_set():
                raise ReviewCancelled()
            with self._pace_lock:
                now = time.monotonic()
                remaining = (
                    0.0
                    if self.last_call_started is None
                    else self.call_spacing_seconds - (now - self.last_call_started)
                )
                if remaining <= 0:
                    self.last_call_started = now
                    return
            if self._cancelled.wait(min(remaining, 1.0)):
                raise ReviewCancelled()

    def __call__(
        self,
        stage: str,
        chunk_index: int,
        manifest: list[dict[str, Any]],
        schema: dict[str, Any],
    ) -> list[dict[str, Any]]:
        if self._cancelled.is_set():
            raise ReviewCancelled()
        if stage not in self.prompts:
            raise RunnerError("internal", "provider", transient=False)
        chunk_name = f"{stage}-{chunk_index:03d}"
        manifest_path = self.work_root / "chunks" / f"{chunk_name}.json"
        with self._artifact_lock:
            _write_json_new(manifest_path, manifest)
        manifest_relative = manifest_path.relative_to(self.capsule).as_posix()
        image_instruction = (
            "open the candidate and reference images for every entry"
            if self.paired
            else "open the candidate image for every entry"
        )
        prompt = (
            self.prompts[stage]
            + "\n\nThe exact manifest for this bounded pass is `./"
            + manifest_relative
            + "`. Read that manifest first, then "
            + image_instruction
            + "."
        )
        schema_json = json.dumps(
            schema,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        last_error = RunnerError("cli_or_api", stage, transient=True)
        for attempt in range(1, self.attempts + 1):
            if self._cancelled.is_set():
                raise ReviewCancelled()
            self._pace()
            output_path = self.work_root / "private" / f"{chunk_name}-{attempt}.json"
            stderr_path = self.work_root / "private" / f"{chunk_name}-{attempt}.stderr"
            with self._artifact_lock:
                output_path.parent.mkdir(parents=True, exist_ok=True)
            command = [
                str(self.claude),
                "--print",
                prompt,
                "--model",
                self.models[stage],
                "--output-format",
                "json",
                "--json-schema",
                schema_json,
                "--safe-mode",
                "--no-session-persistence",
                "--max-turns",
                "40",
                "--tools",
                "Read",
                "--allowedTools",
                f"Read(./{manifest_relative})",
                "Read(./review-input/images/**)",
                "--permission-mode",
                "dontAsk",
            ]
            try:
                with output_path.open("xb") as stdout, stderr_path.open("xb") as stderr:
                    process = subprocess.Popen(
                        command,
                        cwd=self.capsule,
                        stdin=subprocess.DEVNULL,
                        stdout=stdout,
                        stderr=stderr,
                        start_new_session=True,
                    )
                    with self._process_lock:
                        if self._cancelled.is_set():
                            try:
                                os.killpg(process.pid, signal.SIGKILL)
                            except (OSError, ProcessLookupError):
                                pass
                        else:
                            self._processes.add(process)
                    try:
                        returncode = process.wait(timeout=MAX_MODEL_SECONDS)
                    finally:
                        with self._process_lock:
                            self._processes.discard(process)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=30)
                except (OSError, ProcessLookupError, subprocess.TimeoutExpired):
                    pass
                last_error = RunnerError("timeout", stage, transient=True)
            except OSError as exc:
                raise RunnerError("cli_unavailable", stage, transient=False) from exc
            else:
                if self._cancelled.is_set():
                    raise ReviewCancelled()
                try:
                    envelope = load(
                        output_path,
                        "private model result",
                        maximum_bytes=MAX_JSON_BYTES,
                    )
                except ReviewError:
                    envelope = None
                category = (
                    model_error_category(envelope)
                    if isinstance(envelope, dict)
                    else "cli_or_api"
                )
                if returncode == 0 and isinstance(envelope, dict):
                    try:
                        structured = extract_structured_report(envelope)
                    except ReviewError:
                        last_error = RunnerError(
                            category,
                            stage,
                            transient=category in TRANSIENT_MODEL_CATEGORIES,
                        )
                    else:
                        try:
                            if not isinstance(structured, list):
                                raise ReviewError(
                                    "model structured output must be an array"
                                )
                            if stage == "triage":
                                normalized = validate_triage(
                                    manifest,
                                    structured,
                                    require_paired=self.paired,
                                )
                            else:
                                validation_manifest = [
                                    {
                                        key: value
                                        for key, value in item.items()
                                        if key != "first_review"
                                    }
                                    for item in manifest
                                ]
                                normalized = validate(
                                    validation_manifest,
                                    structured,
                                    require_paired=self.paired,
                                )
                        except ReviewError:
                            # Schema-valid output can still violate semantic invariants such as
                            # exact label coverage or clean/anomaly coherence. Retry it as a model
                            # response, while keeping the provider-authored bytes private.
                            last_error = RunnerError(
                                "invalid_structured_output",
                                stage,
                                transient=True,
                            )
                        else:
                            return normalized
                else:
                    last_error = RunnerError(
                        category,
                        stage,
                        transient=category in TRANSIENT_MODEL_CATEGORIES,
                    )
            if not last_error.transient or attempt == self.attempts:
                raise last_error
            if self._cancelled.wait(
                DEFAULT_RETRY_DELAYS[min(attempt - 1, len(DEFAULT_RETRY_DELAYS) - 1)]
            ):
                raise ReviewCancelled()
        raise last_error


def _bounded_prompt(path: Path, label: str) -> str:
    try:
        payload = path.read_bytes()
    except OSError as exc:
        raise RunnerError("configuration", label, transient=False) from exc
    if not payload or len(payload) > 64 * 1024:
        raise RunnerError("configuration", label, transient=False)
    try:
        return payload.decode("utf-8")
    except UnicodeError as exc:
        raise RunnerError("configuration", label, transient=False) from exc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capsule", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--claude", type=Path, required=True)
    parser.add_argument("--triage-prompt", type=Path, required=True)
    parser.add_argument("--verify-prompt", type=Path, required=True)
    parser.add_argument("--triage-model", required=True)
    parser.add_argument("--verify-model", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--failure-report", type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--cache-policy-sha256")
    parser.add_argument("--completion-state", type=Path, required=True)
    parser.add_argument(
        "--review-mode",
        required=True,
        choices=("anchor-semantic", "reference-comparison"),
    )
    parser.add_argument(
        "--review-identical",
        action="store_true",
        help="send exact authored-region matches through review instead of inheriting a pass",
    )
    parser.add_argument("--triage-chunk-size", type=int, default=DEFAULT_TRIAGE_CHUNK_SIZE)
    parser.add_argument("--verify-chunk-size", type=int, default=DEFAULT_VERIFY_CHUNK_SIZE)
    parser.add_argument(
        "--max-parallel-calls",
        type=int,
        default=DEFAULT_MAX_PARALLEL_CALLS,
    )
    parser.add_argument("--model-attempts", type=int, default=DEFAULT_MODEL_ATTEMPTS)
    parser.add_argument(
        "--call-spacing-seconds",
        type=float,
        default=DEFAULT_CALL_SPACING_SECONDS,
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    failure_path = args.failure_report.absolute() if args.failure_report else None
    try:
        if not 1 <= args.model_attempts <= MAX_MODEL_ATTEMPTS:
            raise RunnerError("configuration", "arguments", transient=False)
        if not 0 <= args.call_spacing_seconds <= 120:
            raise RunnerError("configuration", "arguments", transient=False)
        if not 1 <= args.max_parallel_calls <= MAX_PARALLEL_CALLS:
            raise RunnerError("configuration", "arguments", transient=False)
        if (args.cache is None) != (args.cache_policy_sha256 is None):
            raise RunnerError("configuration", "cache", transient=False)
        capsule = args.capsule.resolve(strict=True)
        if not capsule.is_dir() or capsule.is_symlink():
            raise RunnerError("invalid_capsule", "input", transient=False)
        manifest_path = args.manifest.resolve(strict=True)
        input_root = args.input_root.resolve(strict=True)
        if manifest_path.parent != input_root or input_root.parent != capsule:
            raise RunnerError("invalid_capsule", "input", transient=False)
        manifest = load(manifest_path, "review manifest")
        entries, _labels = validate_manifest(manifest)
        paired = "reference_path" in entries[0]
        if paired != (args.review_mode == "reference-comparison"):
            raise RunnerError("invalid_capsule", "review_mode", transient=False)
        validate_input(manifest, input_root, require_paired=paired)
        cache_hits: dict[str, dict[str, Any]] = {}
        if args.cache is not None:
            cache = load_cache(
                args.cache.resolve(strict=True), args.cache_policy_sha256
            )
            cache_hits = cached_verdicts(
                manifest, cache, review_mode=args.review_mode
            )
        work_root = capsule / "review-work"
        if work_root.exists() or work_root.is_symlink():
            raise RunnerError("invalid_capsule", "work", transient=False)
        work_root.mkdir(mode=0o700)
        provider = ClaudeProvider(
            capsule=capsule,
            work_root=work_root,
            claude=args.claude.resolve(strict=True),
            triage_prompt=_bounded_prompt(args.triage_prompt, "triage_prompt"),
            verify_prompt=_bounded_prompt(args.verify_prompt, "verify_prompt"),
            triage_model=args.triage_model,
            verify_model=args.verify_model,
            paired=paired,
            attempts=args.model_attempts,
            call_spacing_seconds=args.call_spacing_seconds,
        )
        verdicts, stats = execute_review(
            manifest,
            provider,
            triage_chunk_size=args.triage_chunk_size,
            verify_chunk_size=args.verify_chunk_size,
            max_parallel_calls=args.max_parallel_calls,
            cache_hits=cache_hits,
            review_identical=args.review_identical,
        )
        write_normalized_report(args.output, verdicts)
        _write_json_new(
            args.completion_state.absolute(),
            {
                "schema_version": 1,
                "state": "blocking-partial" if stats["stopped_early"] else "complete",
                "manifest_frames": stats["frames"],
                "report_verdicts": len(verdicts),
            },
        )
        print(
            "Visual review plan: "
            + ", ".join(f"{key}={value}" for key, value in stats.items())
        )
        return 0
    except RunnerError as exc:
        _write_failure(failure_path, exc)
        print(str(exc), file=sys.stderr)
        return 2
    except (OSError, ReviewError, ValueError) as exc:
        error = RunnerError("protected_validation", "runner", transient=False)
        _write_failure(failure_path, error)
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
