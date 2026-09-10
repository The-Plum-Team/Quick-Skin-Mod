#!/usr/bin/env python3
"""Render bounded, inert per-target CI snapshots without network access."""
from __future__ import annotations

import html
import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any


MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024
MAX_TARGETS = 100
MAX_JOBS = 1000
STATES = {
    "success": ("Passed", "238636"),
    "failure": ("Failed", "cf222e"),
    "running": ("Running", "0969da"),
    "pending": ("Pending", "9a6700"),
    "unknown": ("Unknown", "57606a"),
}
RUN_STATUSES = frozenset({"queued", "in_progress", "completed", "waiting", "requested", "pending"})
SHA = re.compile(r"[0-9a-f]{40}\Z")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\Z")
VERSION = re.compile(r"[0-9]+(?:\.[0-9]+){0,3}\Z")
OBSERVED_AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z\Z")
SNAPSHOT_KEYS = {"schema_version", "repository", "coverage_sha", "observed_at", "targets"}
TARGET_KEYS = {"version", "loaders", "java", "build", "e2e"}
GATE_KEYS = {"state", "reason", "generation", "execution", "tested_sha", "reused", "expected_jobs", "jobs"}
RUN_KEYS = {"id", "attempt", "sha", "status", "conclusion", "url"}
JOB_KEYS = {"id", "name", "status", "conclusion", "url"}


class RenderError(ValueError):
    """The proposed snapshot or output location is not safe to render."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RenderError(message)


def object_keys(value: Any, expected: set[str], label: str) -> None:
    require(isinstance(value, dict) and set(value) == expected, f"invalid {label} fields")


def plain(value: Any, *, maximum: int = 256) -> bool:
    return isinstance(value, str) and 0 < len(value) <= maximum and value.isprintable()


def positive(value: Any) -> bool:
    return type(value) is int and value > 0


def valid_sha(value: Any) -> bool:
    return isinstance(value, str) and SHA.fullmatch(value) is not None


def validate_status(value: dict[str, Any]) -> None:
    require(isinstance(value["status"], str) and value["status"] in RUN_STATUSES,
            "invalid workflow/job status")
    require(value["conclusion"] is None or plain(value["conclusion"], maximum=64),
            "invalid workflow/job conclusion")


def validate_run(value: Any, repository: str) -> None:
    if value is None:
        return
    object_keys(value, RUN_KEYS, "run")
    require(positive(value["id"]) and positive(value["attempt"]) and value["attempt"] <= 100,
            "invalid run identity")
    require(valid_sha(value["sha"]), "invalid run SHA")
    validate_status(value)
    expected = f"https://github.com/{repository}/actions/runs/{value['id']}/attempts/{value['attempt']}"
    require(value["url"] == expected, "run URL differs from its repository, ID or attempt")


def validate_gate(value: Any, repository: str, coverage_sha: str) -> None:
    object_keys(value, GATE_KEYS, "gate")
    require(isinstance(value["state"], str) and value["state"] in STATES, "invalid target state")
    require(plain(value["reason"]), "invalid target reason")
    require(type(value["reused"]) is bool, "invalid reuse flag")
    require(value["tested_sha"] is None or valid_sha(value["tested_sha"]), "invalid tested SHA")
    for key in ("generation", "execution"):
        validate_run(value[key], repository)
    if value["generation"] is not None:
        require(value["generation"]["sha"] == coverage_sha, "generation covers another SHA")
    if value["reused"]:
        require(value["generation"] is not None and value["execution"] is not None
                and value["tested_sha"] is not None, "reuse lacks its original execution")

    expected = value["expected_jobs"]
    require(isinstance(expected, list) and len(expected) <= MAX_JOBS
            and all(plain(name) for name in expected) and len(expected) == len(set(expected)),
            "invalid expected job inventory")
    jobs = value["jobs"]
    require(isinstance(jobs, list) and len(jobs) <= MAX_JOBS, "invalid observed job inventory")
    run_ids = {value[key]["id"] for key in ("generation", "execution") if value[key] is not None}
    ids: set[int] = set()
    names: set[str] = set()
    for job in jobs:
        object_keys(job, JOB_KEYS, "job")
        require(positive(job["id"]) and job["id"] not in ids and plain(job["name"])
                and job["name"] not in names, "invalid or duplicated job identity")
        validate_status(job)
        urls = {f"https://github.com/{repository}/actions/runs/{run_id}/job/{job['id']}"
                for run_id in run_ids}
        require(isinstance(job["url"], str) and job["url"] in urls,
                "job URL differs from its authenticated execution")
        ids.add(job["id"])
        names.add(job["name"])
    if value["state"] == "success":
        require(value["generation"] is not None and value["execution"] is not None
                and value["tested_sha"] is not None and bool(expected), "success lacks required evidence")
        by_name = {job["name"]: job for job in jobs}
        require(all(name in by_name and by_name[name]["status"] == "completed"
                    and by_name[name]["conclusion"] == "success" for name in expected),
                "success includes missing or unsuccessful required jobs")


def validate_snapshot(snapshot: Any) -> None:
    object_keys(snapshot, SNAPSHOT_KEYS, "snapshot")
    require(type(snapshot["schema_version"]) is int and snapshot["schema_version"] == 1,
            "unsupported snapshot schema")
    repository = snapshot["repository"]
    require(isinstance(repository, str) and len(repository) <= 256
            and REPOSITORY.fullmatch(repository) is not None
            and all(part not in {".", ".."} for part in repository.split("/")),
            "invalid repository")
    require(valid_sha(snapshot["coverage_sha"]), "invalid coverage SHA")
    observed = snapshot["observed_at"]
    require(isinstance(observed, str) and OBSERVED_AT.fullmatch(observed) is not None,
            "observation time must be UTC ISO8601 ending in Z")
    try:
        datetime.fromisoformat(observed.replace("Z", "+00:00"))
    except ValueError as exc:
        raise RenderError("invalid observation time") from exc
    targets = snapshot["targets"]
    require(isinstance(targets, list) and 0 < len(targets) <= MAX_TARGETS, "invalid target inventory")
    seen: set[str] = set()
    for target in targets:
        object_keys(target, TARGET_KEYS, "target")
        version = target["version"]
        require(isinstance(version, str) and len(version) <= 32 and VERSION.fullmatch(version) is not None
                and version not in seen, "invalid or duplicated target version")
        seen.add(version)
        loaders = target["loaders"]
        require(isinstance(loaders, list) and 0 < len(loaders) <= 3
                and all(isinstance(loader, str) and loader in {"fabric", "forge", "neoforge"}
                        for loader in loaders) and len(loaders) == len(set(loaders)), "invalid target loaders")
        java = target["java"]
        require(isinstance(java, list) and 0 < len(java) <= 8
                and all(positive(item) and item <= 1000 for item in java)
                and len(java) == len(set(java)), "invalid Java versions")
        for kind in ("build", "e2e"):
            validate_gate(target[kind], repository, snapshot["coverage_sha"])
    try:
        encoded = json.dumps(snapshot, allow_nan=False, ensure_ascii=False).encode("utf-8")
    except (ValueError, TypeError, UnicodeError, RecursionError) as exc:
        raise RenderError("snapshot is not bounded finite JSON") from exc
    require(len(encoded) <= MAX_SNAPSHOT_BYTES, "snapshot exceeds its byte limit")


def markdown(value: str) -> str:
    escaped = re.sub(r"([\\`*_{}\[\]()#+.!|-])", r"\\\1", value)
    return html.escape(escaped, quote=True)


def badge(kind: str, gate: dict[str, Any], snapshot: dict[str, Any]) -> bytes:
    label = "Build" if kind == "build" else "E2E"
    state, color = STATES[gate["state"]]
    message = f"{state} · {snapshot['coverage_sha'][:12]}"
    title = html.escape(f"{label}: {state}; covered commit {snapshot['coverage_sha']}; "
                        f"observed {snapshot['observed_at']}", quote=True)
    left = 48 if kind == "build" else 40
    right = len(message) * 7 + 16
    width = left + right
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="24" '
            f'viewBox="0 0 {width} 24" role="img" aria-label="{title}">\n'
            f'  <title>{title}</title>\n'
            f'  <rect width="{width}" height="24" rx="4" fill="#{color}"/>\n'
            f'  <path d="M4 0H{left}V24H4Q0 24 0 20V4Q0 0 4 0" fill="#24292f"/>\n'
            '  <g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" '
            'font-size="11">\n'
            f'    <text x="{left / 2:g}" y="16">{label}</text>\n'
            f'    <text x="{left + right / 2:g}" y="16">{html.escape(message)}</text>\n'
            '  </g>\n</svg>\n').encode("utf-8")


def run_link(run: dict[str, Any] | None) -> str:
    if run is None:
        return "Not available for this snapshot."
    conclusion = markdown(run["conclusion"] or "not concluded")
    return (f"[Run {run['id']}, attempt {run['attempt']}]({run['url']}) — "
            f"{markdown(run['status'])} / {conclusion}; commit `{run['sha']}`.")


def target_details(target: dict[str, Any], snapshot: dict[str, Any]) -> bytes:
    repository, sha = snapshot["repository"], snapshot["coverage_sha"]
    lines = [f"# Minecraft {target['version']}", "",
             f"Covered commit: [`{sha}`](https://github.com/{repository}/commit/{sha}).",
             f"Observed: {snapshot['observed_at']} (UTC).", "",
             "Target results include the required target jobs and shared prerequisites. "
             "The overall workflow can fail on another target. "
             "These snapshots update on CI transitions; cached images may appear later.", ""]
    for kind, label in (("build", "Build"), ("e2e", "E2E")):
        gate = target[kind]
        lines.extend([f"## {label}", "", f"**Target status: {STATES[gate['state']][0]}.**",
                      "", markdown(gate["reason"]), "",
                      "Generation workflow (overall status): " + run_link(gate["generation"]), ""])
        if gate["reused"]:
            lines.append("Execution: authenticated reuse of the original PR; no new target execution "
                         "in the generation workflow.")
        else:
            lines.append("Execution: this generation's observed workflow.")
        lines.extend([run_link(gate["execution"]), "",
                      f"Tested commit: `{gate['tested_sha']}`." if gate["tested_sha"]
                      else "Tested commit: not established.", "",
                      "| Required job | Status | Conclusion |", "|---|---|---|"])
        by_name = {job["name"]: job for job in gate["jobs"]}
        for name in gate["expected_jobs"]:
            job = by_name.get(name)
            if job is None:
                lines.append(f"| {markdown(name)} | Not observed | — |")
            else:
                lines.append(f"| [{markdown(name)}]({job['url']}) | {markdown(job['status'])} | "
                             f"{markdown(job['conclusion'] or 'not concluded')} |")
        if not gate["expected_jobs"]:
            lines.append("| Not established | Unknown | — |")
        lines.append("")
    return ("\n".join(lines) + "\n").encode("utf-8")


def render_files(snapshot: Any) -> dict[str, bytes]:
    """Return the exact inert publication tree after independent validation."""
    validate_snapshot(snapshot)
    encoded = (json.dumps(snapshot, sort_keys=True, indent=2, ensure_ascii=False, allow_nan=False)
               + "\n").encode("utf-8")
    require(len(encoded) <= MAX_SNAPSHOT_BYTES, "formatted snapshot exceeds its byte limit")
    files = {"status.json": encoded}
    lines = ["# CI status snapshots", "",
             f"Repository: [{snapshot['repository']}](https://github.com/{snapshot['repository']}).",
             f"Covered commit: `{snapshot['coverage_sha']}`.",
             f"Observed: {snapshot['observed_at']} (UTC).", "",
             "Each row summarizes target jobs and shared prerequisites. "
             "Open a target for the separate overall workflow result and original execution provenance. "
             "Snapshots update on CI transitions; caching may delay displayed badges. "
             "A badge is a status summary, not an acceptance certificate.", "",
             "| Minecraft | Build | E2E |", "|---|---|---|"]
    targets = sorted(snapshot["targets"], key=lambda item: tuple(map(int, item["version"].split("."))),
                     reverse=True)
    for target in targets:
        version = target["version"]
        files[f"targets/{version}.md"] = target_details(target, snapshot)
        for kind in ("build", "e2e"):
            files[f"badges/{version}/{kind}.svg"] = badge(kind, target[kind], snapshot)
        lines.append(f"| [{version}](targets/{version}.md) | "
                     f"[![Build](badges/{version}/build.svg)](targets/{version}.md#build) | "
                     f"[![E2E](badges/{version}/e2e.svg)](targets/{version}.md#e2e) |")
    files["README.md"] = ("\n".join(lines) + "\n").encode("utf-8")
    return files


def write_site(snapshot: Any, directory: str | Path) -> None:
    """Write a new snapshot into an absent or empty directory, never over existing files."""
    files = render_files(snapshot)
    destination = Path(directory)
    require(not destination.is_symlink(), "output directory cannot be a symbolic link")
    require(not destination.exists() or destination.is_dir() and not any(destination.iterdir()),
            "output directory must be absent or empty")
    destination.mkdir(parents=True, exist_ok=True)
    for relative, content in files.items():
        path = destination / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as stream:
            stream.write(content)
