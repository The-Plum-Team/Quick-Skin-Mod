#!/usr/bin/env python3
"""Run bounded two-stage Claude review over an authenticated screenshot capsule."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
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


DEFAULT_TRIAGE_CHUNK_SIZE = 8
DEFAULT_VERIFY_CHUNK_SIZE = 4
MAX_CHUNK_SIZE = 8
DEFAULT_MODEL_ATTEMPTS = 3
MAX_MODEL_ATTEMPTS = 4
DEFAULT_CALL_SPACING_SECONDS = 10.0
DEFAULT_RETRY_DELAYS = (30.0, 60.0, 120.0)
MAX_MODEL_SECONDS = 15 * 60
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
    "Candidate pixels are identical to the authenticated 1.20.1 reference."
)
SYNTHETIC_CLEAN_VISIBLE = (
    "Candidate matches the authenticated 1.20.1 reference and checkpoint expectation."
)


class RunnerError(RuntimeError):
    def __init__(self, category: str, stage: str, *, transient: bool) -> None:
        super().__init__(f"visual review failed at {stage} ({category})")
        self.category = category
        self.stage = stage
        self.transient = transient


ReviewProvider = Callable[
    [str, int, list[dict[str, Any]], dict[str, Any]], list[dict[str, Any]]
]


def _chunks(items: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
    if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= MAX_CHUNK_SIZE:
        raise ReviewError(f"review chunk size must be between 1 and {MAX_CHUNK_SIZE}")
    return [items[index : index + size] for index in range(0, len(items), size)]


def build_review_plan(
    manifest: Any, *, triage_chunk_size: int = DEFAULT_TRIAGE_CHUNK_SIZE
) -> dict[str, Any]:
    """Split byte-identical pairs from bounded semantic-review chunks."""

    entries, _labels = validate_manifest(manifest, require_paired=True)
    identical = [item for item in entries if item["path"] == item["reference_path"]]
    semantic = [item for item in entries if item["path"] != item["reference_path"]]
    return {
        "identical": identical,
        "semantic": semantic,
        "triage_chunks": _chunks(semantic, triage_chunk_size) if semantic else [],
    }


def execute_review(
    manifest: Any,
    provider: ReviewProvider,
    *,
    triage_chunk_size: int = DEFAULT_TRIAGE_CHUNK_SIZE,
    verify_chunk_size: int = DEFAULT_VERIFY_CHUNK_SIZE,
) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Run compact triage, escalate uncertain findings, and restore exact order."""

    entries, labels = validate_manifest(manifest, require_paired=True)
    plan = build_review_plan(entries, triage_chunk_size=triage_chunk_size)
    final_by_label: dict[str, dict[str, Any]] = {}
    for item in plan["identical"]:
        final_by_label[item["label"]] = {
            "label": item["label"],
            "visible": SYNTHETIC_IDENTICAL_VISIBLE,
            "matches": True,
            "anomalies": [],
            "defect": False,
        }

    triage_by_label: dict[str, dict[str, Any]] = {}
    for chunk_index, chunk in enumerate(plan["triage_chunks"]):
        chunk_labels = [item["label"] for item in chunk]
        raw_triage = provider(
            "triage",
            chunk_index,
            chunk,
            triage_schema(chunk_labels),
        )
        for verdict in validate_triage(chunk, raw_triage, require_paired=True):
            triage_by_label[verdict["label"]] = verdict

    escalated: list[dict[str, Any]] = []
    for item in plan["semantic"]:
        triage = triage_by_label[item["label"]]
        if triage["decision"] == "clean" and triage["confidence"] == "high":
            final_by_label[item["label"]] = {
                "label": item["label"],
                "visible": SYNTHETIC_CLEAN_VISIBLE,
                "matches": True,
                "anomalies": [],
                "defect": False,
            }
            continue
        escalated.append({**item, "first_review": triage})

    for chunk_index, enriched_chunk in enumerate(_chunks(escalated, verify_chunk_size) if escalated else []):
        validation_chunk = [
            {key: value for key, value in item.items() if key != "first_review"}
            for item in enriched_chunk
        ]
        raw_verdicts = provider(
            "verify",
            chunk_index,
            enriched_chunk,
            report_schema(
                len(validation_chunk),
                labels=[item["label"] for item in validation_chunk],
            ),
        )
        for verdict in validate(
            validation_chunk, raw_verdicts, require_paired=True
        ):
            final_by_label[verdict["label"]] = verdict

    missing = [label for label in labels if label not in final_by_label]
    if missing:
        raise ReviewError(f"review runner did not produce every manifest label: {missing}")
    final = validate(
        entries,
        [final_by_label[label] for label in labels],
        require_paired=True,
    )
    return final, {
        "pairs": len(entries),
        "identical": len(plan["identical"]),
        "triaged": len(plan["semantic"]),
        "triage_chunks": len(plan["triage_chunks"]),
        "escalated": len(escalated),
        "verify_chunks": len(_chunks(escalated, verify_chunk_size)) if escalated else 0,
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
        attempts: int,
        call_spacing_seconds: float,
    ) -> None:
        self.capsule = capsule
        self.work_root = work_root
        self.claude = claude
        self.prompts = {"triage": triage_prompt, "verify": verify_prompt}
        self.models = {"triage": triage_model, "verify": verify_model}
        self.attempts = attempts
        self.call_spacing_seconds = call_spacing_seconds
        self.last_call_started: float | None = None

    def _pace(self) -> None:
        if self.last_call_started is not None:
            remaining = self.call_spacing_seconds - (
                time.monotonic() - self.last_call_started
            )
            if remaining > 0:
                time.sleep(remaining)
        self.last_call_started = time.monotonic()

    def __call__(
        self,
        stage: str,
        chunk_index: int,
        manifest: list[dict[str, Any]],
        schema: dict[str, Any],
    ) -> list[dict[str, Any]]:
        if stage not in self.prompts:
            raise RunnerError("internal", "provider", transient=False)
        chunk_name = f"{stage}-{chunk_index:03d}"
        manifest_path = self.work_root / "chunks" / f"{chunk_name}.json"
        _write_json_new(manifest_path, manifest)
        manifest_relative = manifest_path.relative_to(self.capsule).as_posix()
        prompt = (
            self.prompts[stage]
            + "\n\nThe exact manifest for this bounded pass is `./"
            + manifest_relative
            + "`. Read that manifest first, then open both labelled images for every entry."
        )
        schema_json = json.dumps(
            schema,
            ensure_ascii=True,
            separators=(",", ":"),
            sort_keys=True,
        )
        last_error = RunnerError("cli_or_api", stage, transient=True)
        for attempt in range(1, self.attempts + 1):
            self._pace()
            output_path = self.work_root / "private" / f"{chunk_name}-{attempt}.json"
            stderr_path = self.work_root / "private" / f"{chunk_name}-{attempt}.stderr"
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
                    completed = subprocess.run(
                        command,
                        cwd=self.capsule,
                        stdin=subprocess.DEVNULL,
                        stdout=stdout,
                        stderr=stderr,
                        check=False,
                        timeout=MAX_MODEL_SECONDS,
                    )
            except subprocess.TimeoutExpired:
                last_error = RunnerError("timeout", stage, transient=True)
            except OSError as exc:
                raise RunnerError("cli_unavailable", stage, transient=False) from exc
            else:
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
                if completed.returncode == 0 and isinstance(envelope, dict):
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
                                    require_paired=True,
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
                                    require_paired=True,
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
            time.sleep(DEFAULT_RETRY_DELAYS[min(attempt - 1, len(DEFAULT_RETRY_DELAYS) - 1)])
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
    parser.add_argument("--triage-chunk-size", type=int, default=DEFAULT_TRIAGE_CHUNK_SIZE)
    parser.add_argument("--verify-chunk-size", type=int, default=DEFAULT_VERIFY_CHUNK_SIZE)
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
        capsule = args.capsule.resolve(strict=True)
        if not capsule.is_dir() or capsule.is_symlink():
            raise RunnerError("invalid_capsule", "input", transient=False)
        manifest_path = args.manifest.resolve(strict=True)
        input_root = args.input_root.resolve(strict=True)
        if manifest_path.parent != input_root or input_root.parent != capsule:
            raise RunnerError("invalid_capsule", "input", transient=False)
        manifest = load(manifest_path, "review manifest")
        validate_input(manifest, input_root, require_paired=True)
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
            attempts=args.model_attempts,
            call_spacing_seconds=args.call_spacing_seconds,
        )
        verdicts, stats = execute_review(
            manifest,
            provider,
            triage_chunk_size=args.triage_chunk_size,
            verify_chunk_size=args.verify_chunk_size,
        )
        write_normalized_report(args.output, verdicts)
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
