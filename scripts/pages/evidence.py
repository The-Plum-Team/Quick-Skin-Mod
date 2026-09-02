#!/usr/bin/env python3
"""Prepare raw E2E handoffs and atomically validate/compact public Pages evidence."""

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
from datetime import datetime
from itertools import islice
from pathlib import Path
from typing import Any, Callable, Iterable


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))
sys.path.insert(0, str(REPO / "scripts" / "release"))

from packaged_runtime import (  # noqa: E402
    MAX_EVIDENCE_SCREENSHOT_BYTES,
    RuntimeFailure,
    compare_screenshots,
    inspect_screenshot,
)
from matrix import MatrixError, load_matrix  # noqa: E402
from scenario_contract import ScenarioContract  # noqa: E402
from version_branches import parse_version_branch  # noqa: E402
from visual_evidence import (  # noqa: E402
    DEFAULT_CATALOG,
    MAX_RUNTIME_EVIDENCE_LENGTH,
    SAFE_ID,
    SHA256,
    VisualEvidenceError,
    collect_evidence,
    load_catalog,
    parse_finite_json_float,
    reject_symlinks,
    sha256_file,
    validate_comparison_metrics,
    validate_screenshot_metrics,
)


RAW_SCHEMA_VERSION = 1
COMPACT_SCHEMA_VERSION = 2
SCHEMA_VERSIONS = frozenset({RAW_SCHEMA_VERSION, COMPACT_SCHEMA_VERSION})
COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
BRANCH = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]*$")
MAX_FRAMES = 1000
MAX_MANIFEST_BYTES = 10 * 1024 * 1024
MAX_MATRIX_BYTES = 5 * 1024 * 1024
MAX_IMAGE_BYTES = MAX_EVIDENCE_SCREENSHOT_BYTES
MAX_TOTAL_IMAGE_BYTES = 1024 * 1024 * 1024
MAX_IMAGE_PIXELS = 20_000_000
MAX_IMAGE_ENTRIES = MAX_FRAMES
MAX_BUNDLE_ENTRIES = MAX_IMAGE_ENTRIES + 2
MANIFEST_FIELDS = frozenset(
    {
        "schema_version",
        "contract_sha256",
        "repository",
        "release",
        "provenance",
        "lanes",
        "frames",
        "comparisons",
    }
)
RELEASE_FIELDS = frozenset({"branch", "version", "artifacts", "scenarios"})
LANE_FIELDS = frozenset(
    {
        "lane_id",
        "artifact_node",
        "version",
        "loader",
        "scenario",
        "jar_sha256",
        "status",
        "roles",
        "elapsed_s",
    }
)
SOURCE_FRAME_FIELDS = frozenset(
    {
        "frame_id",
        "capture_id",
        "capture_order",
        "title",
        "expectation",
        "review_tier",
        "artifact_node",
        "version",
        "loader",
        "scenario",
        "role",
        "step",
        "file_sha256",
        "width",
        "height",
        "pixel_validation",
    }
)
# `runtime_evidence` is published by current producers but stays optional while release branches
# still carry bundles created before it existed. A bundle produced by this revision always has it;
# an older cache validates without it and simply publishes no assertion. Requiring it outright
# would reject every pre-existing `pages-cache-*` and stall the whole site behind unrelated ports.
# Once every release branch has re-run its packaged suite, fold this into SOURCE_FRAME_FIELDS.
OPTIONAL_FRAME_FIELDS = frozenset({"runtime_evidence"})
RAW_FRAME_FIELDS = SOURCE_FRAME_FIELDS | {"asset"}
COMPACT_FRAME_FIELDS = SOURCE_FRAME_FIELDS | {"derivative"}
DERIVATIVE_FIELDS = frozenset(
    {"asset", "format", "file_sha256", "width", "height", "pixel_validation"}
)
COMPARISON_FIELDS = frozenset(
    {
        "comparison_id",
        "artifact_node",
        "version",
        "loader",
        "scenario",
        "role",
        "first_frame_id",
        "second_frame_id",
        "pixel_validation",
    }
)
COMPACT_COMPARISON_FIELDS = COMPARISON_FIELDS | {"derivative_pixel_validation"}


class PublicEvidenceError(ValueError):
    pass


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON object key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _read_json(path: Path, label: str, *, maximum_bytes: int) -> Any:
    try:
        with path.open("rb") as handle:
            payload = handle.read(maximum_bytes + 1)
        if not payload or len(payload) > maximum_bytes:
            raise ValueError(
                f"file must contain between 1 and {maximum_bytes} bytes"
            )
        return json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite_constant,
            parse_float=parse_finite_json_float,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise PublicEvidenceError(f"cannot read {label} {path}: {exc}") from exc


def _bounded_entries(
    entries: Iterable[Path],
    *,
    maximum: int,
    label: str,
) -> list[Path]:
    try:
        bounded = list(islice(entries, maximum + 1))
    except OSError as exc:
        raise PublicEvidenceError(f"cannot inspect {label}: {exc}") from exc
    if len(bounded) > maximum:
        raise PublicEvidenceError(f"{label} exceeds its {maximum}-entry limit")
    return bounded


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PublicEvidenceError(f"{label} must be a non-empty string")
    return value.strip()


def _sha(value: Any, label: str) -> str:
    text = _text(value, label)
    if not COMMIT_SHA.fullmatch(text):
        raise PublicEvidenceError(f"{label} must be a lowercase 40-character commit SHA")
    return text


def _run_id(value: Any, label: str) -> str:
    text = str(value)
    if not text.isdigit() or int(text) <= 0:
        raise PublicEvidenceError(f"{label} must be a positive Actions run ID")
    return text


def _runtime_evidence(value: Any, label: str) -> str:
    """Accept only the producer's bounded, printable passed-assertion message."""

    if (
        not isinstance(value, str)
        or not value.strip()
        or len(value) > MAX_RUNTIME_EVIDENCE_LENGTH
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise PublicEvidenceError(
            f"{label} must be bounded printable single-line assertion evidence"
        )
    return value


def _timestamp(value: Any, label: str) -> str:
    text = _text(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PublicEvidenceError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None:
        raise PublicEvidenceError(f"{label} must include a timezone")
    return text


def _thumbnail_dimensions(width: int, height: int) -> tuple[int, int]:
    """Return Pillow's aspect-preserving result for the locked 1600x900 derivative."""

    maximum_width, maximum_height = 1600, 900
    if maximum_width >= width and maximum_height >= height:
        return (width, height)

    def closest(number: float, key: Callable[[int], float]) -> int:
        return max(min(math.floor(number), math.ceil(number), key=key), 1)

    aspect = width / height
    if maximum_width / maximum_height >= aspect:
        maximum_width = closest(
            maximum_height * aspect,
            lambda candidate: abs(aspect - candidate / maximum_height),
        )
    else:
        maximum_height = closest(
            maximum_width / aspect,
            lambda candidate: 0
            if candidate == 0
            else abs(aspect - maximum_width / candidate),
        )
    return (maximum_width, maximum_height)


def _branch(value: Any, label: str, *, release: bool = False) -> str:
    text = _text(value, label)
    if not BRANCH.fullmatch(text) or ".." in text or text.endswith("/"):
        raise PublicEvidenceError(f"{label} is not a safe branch name: {text!r}")
    if release and parse_version_branch(text) is None:
        raise PublicEvidenceError(f"{label} is not a release branch: {text!r}")
    return text


def load_matrix_inventory(
    path: Path,
    target_branch: str,
    contract: ScenarioContract,
) -> dict[str, Any]:
    strict_matrix = _read_json(
        path,
        "release matrix",
        maximum_bytes=MAX_MATRIX_BYTES,
    )
    try:
        matrix = load_matrix(path)
    except MatrixError as exc:
        raise PublicEvidenceError(f"invalid canonical release matrix: {exc}") from exc
    if matrix != strict_matrix:
        raise PublicEvidenceError("release matrix changed while it was being validated")
    project = matrix.get("project")
    if not isinstance(project, dict) or project.get("release_branch") != target_branch:
        raise PublicEvidenceError(
            "release matrix project.release_branch must equal the public target branch"
        )
    artifacts = matrix.get("artifacts")
    runtimes = matrix.get("runtimes")
    if not isinstance(artifacts, list) or not artifacts:
        raise PublicEvidenceError("release matrix artifacts must be non-empty")
    if not isinstance(runtimes, list) or not runtimes:
        raise PublicEvidenceError("release matrix runtimes must be non-empty")
    scenarios = list(contract.scenarios_for_profile("pr"))

    artifact_rows: list[dict[str, str]] = []
    artifact_ids: set[str] = set()
    for index, artifact in enumerate(artifacts):
        if not isinstance(artifact, dict):
            raise PublicEvidenceError(f"release matrix artifact {index} must be an object")
        node = _text(artifact.get("artifact_node"), f"artifact {index}.artifact_node")
        version = _text(artifact.get("artifact_version"), f"artifact {index}.artifact_version")
        loader = _text(artifact.get("loader"), f"artifact {index}.loader")
        if node in artifact_ids:
            raise PublicEvidenceError(f"duplicate release artifact {node!r}")
        artifact_ids.add(node)
        artifact_rows.append({"artifact_node": node, "version": version, "loader": loader})

    runtime_rows: list[dict[str, str]] = []
    runtime_ids: set[str] = set()
    for index, runtime in enumerate(runtimes):
        if not isinstance(runtime, dict):
            raise PublicEvidenceError(f"release matrix runtime {index} must be an object")
        row = {
            "artifact_node": _text(
                runtime.get("artifact_node"), f"runtime {index}.artifact_node"
            ),
            "version": _text(
                runtime.get("runtime_version"), f"runtime {index}.runtime_version"
            ),
            "loader": _text(runtime.get("loader"), f"runtime {index}.loader"),
        }
        if row["artifact_node"] in runtime_ids:
            raise PublicEvidenceError(f"duplicate runtime row {row['artifact_node']!r}")
        runtime_ids.add(row["artifact_node"])
        runtime_rows.append(row)
    if artifact_ids != runtime_ids:
        raise PublicEvidenceError("release matrix artifacts and runtimes disagree")
    by_artifact = {row["artifact_node"]: row for row in artifact_rows}
    for runtime in runtime_rows:
        artifact = by_artifact[runtime["artifact_node"]]
        if (runtime["version"], runtime["loader"]) != (
            artifact["version"],
            artifact["loader"],
        ):
            raise PublicEvidenceError(
                f"artifact/runtime identity mismatch for {runtime['artifact_node']}"
            )
    versions = {row["version"] for row in runtime_rows}
    parsed = parse_version_branch(target_branch)
    runtime_loaders = [row["loader"] for row in runtime_rows]
    loaders = set(runtime_loaders)
    if (
        parsed is None
        or versions != {parsed.version}
        or loaders != set(parsed.loaders)
        or len(runtime_loaders) != len(loaders)
    ):
        raise PublicEvidenceError(
            "target branch identity and runtime inventory disagree: "
            f"{target_branch}, versions={sorted(versions)}, loaders={sorted(loaders)}"
        )
    return {
        "version": parsed.version,
        "artifacts": sorted(artifact_rows, key=lambda row: (row["loader"], row["artifact_node"])),
        "runtimes": sorted(runtime_rows, key=lambda row: (row["loader"], row["artifact_node"])),
        "scenarios": list(scenarios),
    }


def prepare(
    *,
    e2e_root: Path,
    matrix_path: Path,
    catalog_path: Path,
    output_root: Path,
    repository: str,
    source_run_id: str,
    source_branch: str,
    source_sha: str,
    source_created_at: str,
    target_run_id: str,
    target_branch: str,
    target_sha: str,
    target_created_at: str,
) -> Path:
    if not REPOSITORY.fullmatch(repository):
        raise PublicEvidenceError(f"invalid owner/repository identity {repository!r}")
    source_run_id = _run_id(source_run_id, "source_run_id")
    target_run_id = _run_id(target_run_id, "target_run_id")
    source_branch = _branch(source_branch, "source_branch")
    target_branch = _branch(target_branch, "target_branch", release=True)
    source_sha = _sha(source_sha, "source_sha")
    target_sha = _sha(target_sha, "target_sha")
    source_created_at = _timestamp(source_created_at, "source_created_at")
    target_created_at = _timestamp(target_created_at, "target_created_at")
    catalog = load_catalog(catalog_path)
    inventory = load_matrix_inventory(
        matrix_path,
        target_branch,
        catalog.contract,
    )
    try:
        lanes, frames, comparisons = collect_evidence(e2e_root, catalog)
    except VisualEvidenceError as exc:
        raise PublicEvidenceError(str(exc)) from exc

    expected_results = {
        (runtime["artifact_node"], runtime["version"], runtime["loader"], scenario)
        for runtime in inventory["runtimes"]
        for scenario in inventory["scenarios"]
    }
    actual_results = {
        (lane["artifact_node"], lane["version"], lane["loader"], lane["scenario"])
        for lane in lanes
    }
    if actual_results != expected_results:
        raise PublicEvidenceError(
            "packaged evidence does not cover the exact matrix/scenario product: "
            f"missing={sorted(expected_results - actual_results)}, "
            f"extra={sorted(actual_results - expected_results)}"
        )
    for lane in lanes:
        if not any(frame["frame_id"].startswith(lane["lane_id"] + "/") for frame in frames):
            raise PublicEvidenceError(f"packaged lane has no catalogued frames: {lane['lane_id']}")

    bundle = output_root.resolve() / target_branch
    if bundle.exists():
        raise PublicEvidenceError(f"refusing to replace existing public evidence bundle {bundle}")
    images = bundle / "images"
    images.mkdir(parents=True)
    public_frames: list[dict[str, Any]] = []
    for frame in frames:
        source = Path(frame["source_path"])
        asset = f"images/{frame['file_sha256']}.png"
        destination = bundle / asset
        if destination.exists():
            if sha256_file(destination) != frame["file_sha256"]:
                raise PublicEvidenceError(f"public image digest collision at {destination}")
        else:
            shutil.copyfile(source, destination)
        public = {key: frame[key] for key in SOURCE_FRAME_FIELDS}
        public.update(
            {key: frame[key] for key in OPTIONAL_FRAME_FIELDS if key in frame}
        )
        public["asset"] = asset
        public_frames.append(public)

    manifest = {
        "schema_version": RAW_SCHEMA_VERSION,
        "contract_sha256": catalog.contract_sha256,
        "repository": repository,
        "release": {
            "branch": target_branch,
            "version": inventory["version"],
            "artifacts": inventory["artifacts"],
            "scenarios": inventory["scenarios"],
        },
        "provenance": {
            "source": {
                "run_id": source_run_id,
                "run_url": f"https://github.com/{repository}/actions/runs/{source_run_id}",
                "branch": source_branch,
                "sha": source_sha,
                "created_at": source_created_at,
            },
            "target": {
                "run_id": target_run_id,
                "run_url": f"https://github.com/{repository}/actions/runs/{target_run_id}",
                "branch": target_branch,
                "sha": target_sha,
                "created_at": target_created_at,
            },
        },
        "lanes": lanes,
        "frames": public_frames,
        "comparisons": comparisons,
    }
    (bundle / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    validate_bundle(
        output_root.resolve(),
        target_branch,
        expected_repository=repository,
        expected_source_run_id=source_run_id,
        expected_target_run_id=target_run_id,
        expected_target_sha=target_sha,
        catalog_path=catalog_path,
    )
    return bundle


def bundle_coverage_sha(manifest: dict[str, Any]) -> str:
    """Return the release-branch head this bundle covers.

    Evidence written before the coverage field existed covers exactly the head its
    packaged run tested, so its target SHA remains the answer.
    """

    provenance = manifest["provenance"]
    covered = provenance.get("coverage_sha")
    return covered if isinstance(covered, str) else provenance["target"]["sha"]


def validate_bundle(
    evidence_root: Path,
    branch: str,
    *,
    only_branch: bool = False,
    expected_kind: str | None = None,
    expected_repository: str | None = None,
    expected_source_run_id: str | None = None,
    expected_target_run_id: str | None = None,
    expected_target_sha: str | None = None,
    expected_coverage_sha: str | None = None,
    catalog_path: Path = DEFAULT_CATALOG,
) -> dict[str, Any]:
    branch = _branch(branch, "branch", release=True)
    root = evidence_root.resolve()
    if not root.is_dir():
        raise PublicEvidenceError(f"evidence root does not exist: {root}")
    if only_branch:
        try:
            actual_root_entries = {path.name for path in root.iterdir()}
        except OSError as exc:
            raise PublicEvidenceError(f"cannot inspect evidence root {root}: {exc}") from exc
        if actual_root_entries != {branch}:
            raise PublicEvidenceError(
                "single-branch evidence root mismatch: "
                f"missing={sorted({branch} - actual_root_entries)}, "
                f"extra={sorted(actual_root_entries - {branch})}"
            )
    raw_bundle = root / branch
    try:
        reject_symlinks(raw_bundle, root, "evidence bundle")
    except VisualEvidenceError as exc:
        raise PublicEvidenceError(str(exc)) from exc
    bundle = raw_bundle.resolve()
    if bundle.parent != root or not bundle.is_dir():
        raise PublicEvidenceError(f"missing or escaping evidence bundle for {branch}")
    try:
        reject_symlinks(bundle / "manifest.json", bundle, "evidence manifest")
    except VisualEvidenceError as exc:
        raise PublicEvidenceError(str(exc)) from exc
    manifest_path = bundle / "manifest.json"
    try:
        manifest_size = manifest_path.stat().st_size
    except OSError as exc:
        raise PublicEvidenceError(f"cannot stat public evidence manifest: {exc}") from exc
    if manifest_size <= 0 or manifest_size > MAX_MANIFEST_BYTES:
        raise PublicEvidenceError("public evidence manifest exceeds its size limit")
    manifest = _read_json(
        manifest_path,
        "public evidence manifest",
        maximum_bytes=MAX_MANIFEST_BYTES,
    )
    if not isinstance(manifest, dict) or set(manifest) != MANIFEST_FIELDS:
        raise PublicEvidenceError("public evidence manifest fields are invalid")
    schema_version = manifest.get("schema_version")
    if type(schema_version) is not int or schema_version not in SCHEMA_VERSIONS:
        raise PublicEvidenceError(
            f"public evidence schema_version must be one of {sorted(SCHEMA_VERSIONS)}"
        )
    bundle_kind = "raw" if schema_version == RAW_SCHEMA_VERSION else "compact"
    if expected_kind is not None and expected_kind not in {"raw", "compact"}:
        raise PublicEvidenceError(f"unsupported expected evidence kind {expected_kind!r}")
    if expected_kind is not None and bundle_kind != expected_kind:
        raise PublicEvidenceError(
            f"public evidence kind mismatch: {bundle_kind!r} != {expected_kind!r}"
        )
    repository = _text(manifest.get("repository"), "manifest.repository")
    if not REPOSITORY.fullmatch(repository):
        raise PublicEvidenceError("manifest.repository is invalid")
    if expected_repository is not None and repository != expected_repository:
        raise PublicEvidenceError(
            f"evidence repository mismatch: {repository!r} != {expected_repository!r}"
        )
    release = manifest.get("release")
    if (
        not isinstance(release, dict)
        or set(release) != RELEASE_FIELDS
        or release.get("branch") != branch
    ):
        raise PublicEvidenceError("evidence release branch mismatch")
    parsed = parse_version_branch(branch)
    if parsed is None or release.get("version") != parsed.version:
        raise PublicEvidenceError("evidence release version does not match its branch")
    scenarios = release.get("scenarios")
    artifacts = release.get("artifacts")
    if (
        not isinstance(scenarios, list)
        or not scenarios
        or any(not isinstance(item, str) or not item for item in scenarios)
        or len(set(scenarios)) != len(scenarios)
    ):
        raise PublicEvidenceError("evidence release scenarios are invalid")
    if not isinstance(artifacts, list) or not artifacts:
        raise PublicEvidenceError("evidence release artifacts are invalid")
    artifact_ids: set[str] = set()
    artifact_by_node: dict[str, dict[str, str]] = {}
    for artifact in artifacts:
        if not isinstance(artifact, dict) or set(artifact) != {
            "artifact_node",
            "version",
            "loader",
        }:
            raise PublicEvidenceError("evidence release artifact has invalid fields")
        node = _text(artifact["artifact_node"], "release artifact_node")
        version = _text(artifact["version"], "release artifact version")
        loader = _text(artifact["loader"], "release artifact loader")
        if (
            node in artifact_ids
            or not SAFE_ID.fullmatch(node)
            or version != parsed.version
            or loader not in {"fabric", "forge", "neoforge"}
        ):
            raise PublicEvidenceError("evidence release artifacts contain duplicates or wrong versions")
        artifact_ids.add(node)
        artifact_by_node[node] = {
            "artifact_node": node,
            "version": version,
            "loader": loader,
        }
    artifact_loaders = [artifact["loader"] for artifact in artifact_by_node.values()]
    if (
        set(parsed.loaders) != set(artifact_loaders)
        or len(artifact_loaders) != len(set(artifact_loaders))
    ):
        raise PublicEvidenceError("evidence loaders do not match the release branch name")

    catalog = load_catalog(catalog_path)
    if manifest.get("contract_sha256") != catalog.contract_sha256:
        raise PublicEvidenceError(
            "public evidence scenario contract hash does not match the protected contract"
        )
    contract_scenarios = list(catalog.contract.scenarios_for_profile("pr"))
    if scenarios != contract_scenarios:
        raise PublicEvidenceError(
            "evidence scenarios disagree with the exact protected public profile: "
            f"{scenarios!r} != {contract_scenarios!r}"
        )

    provenance = manifest.get("provenance")
    # A non-visual synchronization port advances a release branch without re-running
    # packaged Minecraft, so validated evidence may legitimately outlive the head that
    # produced it. The optional coverage_sha records how far that proof was carried.
    # It stays optional until every release branch has republished, so an older rolling
    # cache keeps validating; absence means the packaged target head is also the coverage.
    if not isinstance(provenance, dict) or set(provenance) not in (
        {"source", "target"},
        {"source", "target", "coverage_sha"},
    ):
        raise PublicEvidenceError("evidence provenance is invalid")
    for name in ("source", "target"):
        record = provenance[name]
        if not isinstance(record, dict) or set(record) != {
            "run_id",
            "run_url",
            "branch",
            "sha",
            "created_at",
        }:
            raise PublicEvidenceError(f"evidence provenance.{name} fields are invalid")
        run_id = _run_id(record["run_id"], f"provenance.{name}.run_id")
        expected_url = f"https://github.com/{repository}/actions/runs/{run_id}"
        if record["run_url"] != expected_url:
            raise PublicEvidenceError(f"evidence provenance.{name}.run_url is invalid")
        _branch(record["branch"], f"provenance.{name}.branch", release=name == "target")
        _sha(record["sha"], f"provenance.{name}.sha")
        _timestamp(record["created_at"], f"provenance.{name}.created_at")
        expected_run_id = (
            expected_source_run_id if name == "source" else expected_target_run_id
        )
        if expected_run_id is not None and run_id != _run_id(
            expected_run_id, f"expected_{name}_run_id"
        ):
            raise PublicEvidenceError(f"evidence provenance.{name}.run_id mismatch")
    target = provenance["target"]
    if target["branch"] != branch:
        raise PublicEvidenceError("evidence target branch mismatch")
    if expected_target_sha is not None and target["sha"] != expected_target_sha:
        raise PublicEvidenceError(
            f"evidence target SHA mismatch: {target['sha']} != {expected_target_sha}"
        )
    if "coverage_sha" in provenance:
        _sha(provenance["coverage_sha"], "provenance.coverage_sha")
    covered = bundle_coverage_sha(manifest)
    if expected_coverage_sha is not None and covered != expected_coverage_sha:
        raise PublicEvidenceError(
            f"evidence coverage SHA mismatch: {covered} != {expected_coverage_sha}"
        )

    lanes = manifest.get("lanes")
    frames = manifest.get("frames")
    comparisons = manifest.get("comparisons")
    if not isinstance(lanes, list) or not lanes:
        raise PublicEvidenceError("evidence lanes must be non-empty")
    if not isinstance(frames, list) or not frames or len(frames) > MAX_FRAMES:
        raise PublicEvidenceError("evidence frames must be non-empty and within the public limit")
    if not isinstance(comparisons, list):
        raise PublicEvidenceError("evidence comparisons must be an array")

    expected_lane_ids = {
        f"{artifact['artifact_node']}/{scenario}"
        for artifact in artifacts
        for scenario in scenarios
    }
    lane_ids: set[str] = set()
    lane_by_id: dict[str, dict[str, Any]] = {}
    jar_sha256_by_artifact: dict[str, str] = {}
    for lane in lanes:
        if (
            not isinstance(lane, dict)
            or set(lane) != LANE_FIELDS
            or lane.get("status") != "pass"
        ):
            raise PublicEvidenceError("public evidence contains an invalid or non-pass lane")
        lane_id = _text(lane.get("lane_id"), "lane.lane_id")
        if lane_id in lane_ids:
            raise PublicEvidenceError(f"duplicate public evidence lane {lane_id!r}")
        artifact_node = _text(lane.get("artifact_node"), "lane.artifact_node")
        scenario = _text(lane.get("scenario"), "lane.scenario")
        artifact = artifact_by_node.get(artifact_node)
        if (
            artifact is None
            or scenario not in scenarios
            or lane_id != f"{artifact_node}/{scenario}"
            or lane.get("version") != artifact["version"]
            or lane.get("loader") != artifact["loader"]
        ):
            raise PublicEvidenceError(f"public evidence lane identity mismatch: {lane_id!r}")
        jar_sha256 = lane.get("jar_sha256")
        if not isinstance(jar_sha256, str) or not SHA256.fullmatch(jar_sha256):
            raise PublicEvidenceError(f"public evidence lane has invalid JAR digest: {lane_id!r}")
        previous_jar_sha256 = jar_sha256_by_artifact.setdefault(artifact_node, jar_sha256)
        if previous_jar_sha256 != jar_sha256:
            raise PublicEvidenceError(
                f"public evidence scenarios used different JARs for {artifact_node!r}"
            )
        expected_roles = sorted(
            {
                capture["role"]
                for capture in catalog.captures
                if capture["scenario"] == scenario
            }
        )
        if lane.get("roles") != expected_roles:
            raise PublicEvidenceError(f"public evidence lane has invalid role coverage: {lane_id!r}")
        elapsed = lane.get("elapsed_s")
        if (
            isinstance(elapsed, bool)
            or not isinstance(elapsed, (int, float))
            or not math.isfinite(elapsed)
            or elapsed < 0
        ):
            raise PublicEvidenceError(f"public evidence lane has invalid elapsed time: {lane_id!r}")
        lane_ids.add(lane_id)
        lane_by_id[lane_id] = lane
    if lane_ids != expected_lane_ids:
        raise PublicEvidenceError(
            f"public evidence lanes disagree with release inventory: "
            f"missing={sorted(expected_lane_ids - lane_ids)}, "
            f"extra={sorted(lane_ids - expected_lane_ids)}"
        )

    expected_frame_ids = {
        f"{artifact['artifact_node']}/{capture['scenario']}/{capture['role']}/{capture['step']}"
        for artifact in artifacts
        for capture in catalog.captures
        if capture["scenario"] in scenarios
    }
    if len(expected_frame_ids) > MAX_FRAMES:
        raise PublicEvidenceError("protected visual catalog exceeds the public frame limit")
    frame_ids: set[str] = set()
    frame_by_id: dict[str, dict[str, Any]] = {}
    asset_path_by_frame_id: dict[str, Path] = {}
    expected_assets: set[str] = set()
    validated_assets: dict[str, dict[str, Any]] = {}
    total_bytes = 0
    frames_per_lane = {lane_id: 0 for lane_id in lane_ids}
    for frame in frames:
        expected_frame_fields = (
            RAW_FRAME_FIELDS if schema_version == RAW_SCHEMA_VERSION else COMPACT_FRAME_FIELDS
        )
        if (
            not isinstance(frame, dict)
            or set(frame) - OPTIONAL_FRAME_FIELDS != expected_frame_fields
        ):
            raise PublicEvidenceError("public frame is invalid or leaks a source path")
        frame_id = _text(frame.get("frame_id"), "frame.frame_id")
        if frame_id in frame_ids:
            raise PublicEvidenceError(f"duplicate public frame {frame_id!r}")
        frame_ids.add(frame_id)
        artifact_node = _text(frame.get("artifact_node"), "frame.artifact_node")
        scenario = _text(frame.get("scenario"), "frame.scenario")
        role = _text(frame.get("role"), "frame.role")
        step = _text(frame.get("step"), "frame.step")
        lane_id = f"{artifact_node}/{scenario}"
        canonical_frame_id = f"{artifact_node}/{scenario}/{role}/{step}"
        if lane_id not in frames_per_lane or frame_id != canonical_frame_id:
            raise PublicEvidenceError(f"public frame has invalid lane identity {frame_id!r}")
        lane = lane_by_id[lane_id]
        if any(
            frame.get(field) != lane[field]
            for field in ("artifact_node", "version", "loader", "scenario")
        ):
            raise PublicEvidenceError(f"public frame metadata disagrees with its lane: {frame_id}")
        frames_per_lane[lane_id] += 1
        key = (scenario, role, step)
        capture = catalog.by_key.get(key)
        if capture is None or any(
            frame.get(field) != capture[field]
            for field in ("capture_id", "title", "expectation", "review_tier")
        ):
            raise PublicEvidenceError(f"public frame disagrees with visual catalog: {frame_id}")
        if "runtime_evidence" in frame:
            _runtime_evidence(
                frame["runtime_evidence"], f"public frame runtime evidence for {frame_id}"
            )
        capture_order = frame.get("capture_order")
        if (
            isinstance(capture_order, bool)
            or not isinstance(capture_order, int)
            or capture_order != catalog.captures.index(capture)
        ):
            raise PublicEvidenceError(f"public frame has invalid catalog order: {frame_id}")
        file_sha256 = frame.get("file_sha256")
        if not isinstance(file_sha256, str) or not SHA256.fullmatch(file_sha256):
            raise PublicEvidenceError(f"public frame has invalid digest: {frame_id}")
        try:
            source_pixel_validation = validate_screenshot_metrics(
                frame.get("pixel_validation"), f"public frame metrics for {frame_id}"
            )
        except VisualEvidenceError as exc:
            raise PublicEvidenceError(str(exc)) from exc
        if (
            source_pixel_validation != frame.get("pixel_validation")
            or source_pixel_validation["file_sha256"] != file_sha256
            or source_pixel_validation["width"] != frame.get("width")
            or source_pixel_validation["height"] != frame.get("height")
        ):
            raise PublicEvidenceError(f"public frame pixel metadata mismatch: {frame_id}")
        source_dimensions = (frame.get("width"), frame.get("height"))
        if (
            source_dimensions[0] < 640
            or source_dimensions[1] < 360
            or source_dimensions[0] * source_dimensions[1] > MAX_IMAGE_PIXELS
        ):
            raise PublicEvidenceError(
                f"public frame source dimensions are implausible: {frame_id}"
            )

        if schema_version == RAW_SCHEMA_VERSION:
            asset = frame.get("asset")
            expected_metrics = source_pixel_validation
            expected_format = "PNG"
            if asset != f"images/{file_sha256}.png":
                raise PublicEvidenceError(f"public frame has invalid asset path: {frame_id}")
        else:
            derivative = frame.get("derivative")
            if not isinstance(derivative, dict) or set(derivative) != DERIVATIVE_FIELDS:
                raise PublicEvidenceError(f"public frame derivative is invalid: {frame_id}")
            derivative_sha256 = derivative.get("file_sha256")
            asset = derivative.get("asset")
            if (
                derivative.get("format") != "webp"
                or not isinstance(derivative_sha256, str)
                or not SHA256.fullmatch(derivative_sha256)
                or asset != f"images/{derivative_sha256}.webp"
            ):
                raise PublicEvidenceError(
                    f"public frame derivative identity is invalid: {frame_id}"
                )
            try:
                expected_metrics = validate_screenshot_metrics(
                    derivative.get("pixel_validation"),
                    f"public frame derivative metrics for {frame_id}",
                )
            except VisualEvidenceError as exc:
                raise PublicEvidenceError(str(exc)) from exc
            derivative_dimensions = (derivative.get("width"), derivative.get("height"))
            if (
                expected_metrics != derivative.get("pixel_validation")
                or expected_metrics["file_sha256"] != derivative_sha256
                or (expected_metrics["width"], expected_metrics["height"])
                != derivative_dimensions
                or derivative_dimensions
                != _thumbnail_dimensions(*source_dimensions)
                or derivative_dimensions[0] * derivative_dimensions[1] > MAX_IMAGE_PIXELS
            ):
                raise PublicEvidenceError(
                    f"public frame derivative metadata is invalid: {frame_id}"
                )
            expected_format = "WEBP"

        expected_assets.add(asset)
        actual_metrics = validated_assets.get(asset)
        if actual_metrics is None:
            raw_image = bundle / asset
            try:
                reject_symlinks(raw_image, bundle, "public frame asset")
            except VisualEvidenceError as exc:
                raise PublicEvidenceError(str(exc)) from exc
            image = raw_image.resolve()
            images_root = (bundle / "images").resolve()
            if image.parent != images_root or not image.is_file():
                raise PublicEvidenceError(f"public frame asset escapes or is missing: {asset}")
            size = image.stat().st_size
            if size <= 0 or size > MAX_IMAGE_BYTES:
                raise PublicEvidenceError(f"public frame asset exceeds its size limit: {asset}")
            if total_bytes + size > MAX_TOTAL_IMAGE_BYTES:
                raise PublicEvidenceError(
                    "public evidence exceeds the total image byte limit"
                )
            total_bytes += size
            if sha256_file(image) != expected_metrics["file_sha256"]:
                raise PublicEvidenceError(f"public frame asset digest mismatch: {asset}")
            try:
                actual_metrics = inspect_screenshot(
                    image, expected_format=expected_format
                )
            except RuntimeFailure as exc:
                raise PublicEvidenceError(str(exc)) from exc
            validated_assets[asset] = actual_metrics
        if actual_metrics != expected_metrics:
            raise PublicEvidenceError(f"public frame asset pixel metadata mismatch: {asset}")
        frame_by_id[frame_id] = frame
        asset_path_by_frame_id[frame_id] = (bundle / asset).resolve()
    if frame_ids != expected_frame_ids:
        raise PublicEvidenceError(
            "public evidence frames disagree with the protected visual catalog: "
            f"missing={sorted(expected_frame_ids - frame_ids)}, "
            f"extra={sorted(frame_ids - expected_frame_ids)}"
        )
    image_entries = _bounded_entries(
        (bundle / "images").iterdir(),
        maximum=MAX_IMAGE_ENTRIES,
        label="public evidence image directory",
    )
    actual_assets = {
        path.relative_to(bundle).as_posix()
        for path in image_entries
        if path.is_file()
    }
    if actual_assets != expected_assets:
        raise PublicEvidenceError(
            f"public evidence image inventory mismatch: "
            f"missing={sorted(expected_assets - actual_assets)}, "
            f"extra={sorted(actual_assets - expected_assets)}"
        )
    expected_tree = {"manifest.json", "images", *expected_assets}
    actual_tree: set[str] = set()
    bundle_entries = _bounded_entries(
        bundle.rglob("*"),
        maximum=MAX_BUNDLE_ENTRIES,
        label="public evidence bundle",
    )
    for path in bundle_entries:
        relative = path.relative_to(bundle).as_posix()
        if path.is_symlink():
            raise PublicEvidenceError(f"public evidence contains a symlink: {relative}")
        actual_tree.add(relative)
    if actual_tree != expected_tree:
        raise PublicEvidenceError(
            "public evidence bundle contains unexpected or missing entries: "
            f"missing={sorted(expected_tree - actual_tree)}, "
            f"extra={sorted(actual_tree - expected_tree)}"
        )

    expected_comparisons: dict[str, dict[str, Any]] = {}
    for artifact in artifacts:
        artifact_node = artifact["artifact_node"]
        for scenario_contract in catalog.contract.scenarios:
            scenario = scenario_contract.scenario
            if scenario not in scenarios:
                continue
            for role_contract in scenario_contract.roles:
                role = role_contract.role
                for comparison in role_contract.comparisons:
                    first_step = comparison.first_step
                    second_step = comparison.second_step
                    comparison_id = (
                        f"{artifact_node}/{scenario}/{role}/"
                        f"{first_step}->{second_step}"
                    )
                    expected_comparisons[comparison_id] = {
                        "first_frame_id": (
                            f"{artifact_node}/{scenario}/{role}/{first_step}"
                        ),
                        "second_frame_id": (
                            f"{artifact_node}/{scenario}/{role}/{second_step}"
                        ),
                        "required_changed_fraction": (
                            comparison.minimum_changed_fraction
                        ),
                        "region": (
                            list(comparison.region)
                            if comparison.region is not None
                            else None
                        ),
                    }

    comparison_ids: set[str] = set()
    for comparison in comparisons:
        expected_comparison_fields = (
            COMPARISON_FIELDS
            if schema_version == RAW_SCHEMA_VERSION
            else COMPACT_COMPARISON_FIELDS
        )
        if (
            not isinstance(comparison, dict)
            or set(comparison) != expected_comparison_fields
        ):
            raise PublicEvidenceError("public comparison must be an object")
        comparison_id = _text(comparison.get("comparison_id"), "comparison.comparison_id")
        if comparison_id in comparison_ids:
            raise PublicEvidenceError(f"duplicate public comparison {comparison_id!r}")
        comparison_ids.add(comparison_id)
        expected = expected_comparisons.get(comparison_id)
        if expected is None:
            raise PublicEvidenceError(f"public comparison is not in the protected contract: {comparison_id}")
        first = comparison.get("first_frame_id")
        second = comparison.get("second_frame_id")
        if (
            first == second
            or first not in frame_ids
            or second not in frame_ids
            or first != expected["first_frame_id"]
            or second != expected["second_frame_id"]
        ):
            raise PublicEvidenceError(f"public comparison has invalid endpoints: {comparison_id}")
        first_frame = frame_by_id[first]
        second_frame = frame_by_id[second]
        if any(
            first_frame[field] != second_frame[field]
            for field in ("artifact_node", "version", "loader", "scenario", "role")
        ) or any(
            comparison.get(field) != first_frame[field]
            for field in ("artifact_node", "version", "loader", "scenario", "role")
        ):
            raise PublicEvidenceError(f"public comparison identity mismatch: {comparison_id}")
        canonical_id = (
            f"{first_frame['artifact_node']}/{first_frame['scenario']}/{first_frame['role']}/"
            f"{first_frame['step']}->{second_frame['step']}"
        )
        if comparison_id != canonical_id:
            raise PublicEvidenceError(f"public comparison has a fabricated identity: {comparison_id}")
        try:
            metrics = validate_comparison_metrics(
                comparison.get("pixel_validation"),
                f"public comparison metrics for {comparison_id}",
            )
        except VisualEvidenceError as exc:
            raise PublicEvidenceError(str(exc)) from exc
        if metrics != comparison.get("pixel_validation"):
            raise PublicEvidenceError(f"public comparison metrics are not canonical: {comparison_id}")
        if metrics["required_changed_fraction"] != expected["required_changed_fraction"]:
            raise PublicEvidenceError(f"public comparison threshold drifted: {comparison_id}")
        if metrics.get("region") != expected["region"]:
            raise PublicEvidenceError(f"public comparison region drifted: {comparison_id}")
        recorded_asset_metrics = metrics
        if schema_version == COMPACT_SCHEMA_VERSION:
            try:
                recorded_asset_metrics = validate_comparison_metrics(
                    comparison.get("derivative_pixel_validation"),
                    f"public derivative comparison metrics for {comparison_id}",
                )
            except VisualEvidenceError as exc:
                raise PublicEvidenceError(str(exc)) from exc
            if recorded_asset_metrics != comparison.get("derivative_pixel_validation"):
                raise PublicEvidenceError(
                    f"public derivative comparison metrics are not canonical: {comparison_id}"
                )
            if (
                recorded_asset_metrics["required_changed_fraction"]
                != expected["required_changed_fraction"]
                or recorded_asset_metrics.get("region") != expected["region"]
            ):
                raise PublicEvidenceError(
                    f"public derivative comparison contract drifted: {comparison_id}"
                )
        region = recorded_asset_metrics.get("region")
        try:
            actual_asset_metrics = compare_screenshots(
                asset_path_by_frame_id[first],
                asset_path_by_frame_id[second],
                recorded_asset_metrics["required_changed_fraction"],
                tuple(region) if region is not None else None,
            )
        except RuntimeFailure as exc:
            raise PublicEvidenceError(str(exc)) from exc
        if actual_asset_metrics != recorded_asset_metrics:
            raise PublicEvidenceError(
                f"public comparison asset metrics mismatch: {comparison_id}"
            )
    if comparison_ids != set(expected_comparisons):
        raise PublicEvidenceError(
            "public comparisons disagree with the protected runtime contract: "
            f"missing={sorted(set(expected_comparisons) - comparison_ids)}, "
            f"extra={sorted(comparison_ids - set(expected_comparisons))}"
        )
    return manifest


def _snapshot_verified_image(
    source: Path,
    destination: Path,
    *,
    expected_sha256: str,
    boundary: Path,
) -> None:
    """Copy one untrusted image through a verified descriptor into owned staging."""

    try:
        reject_symlinks(source, boundary, "compact source image")
    except VisualEvidenceError as exc:
        raise PublicEvidenceError(str(exc)) from exc
    try:
        source_stat = source.lstat()
    except OSError as exc:
        raise PublicEvidenceError(f"cannot stat compact source image {source}: {exc}") from exc
    if stat.S_ISLNK(source_stat.st_mode) or not stat.S_ISREG(source_stat.st_mode):
        raise PublicEvidenceError(f"compact source image is not a regular file: {source}")
    if source_stat.st_size <= 0 or source_stat.st_size > MAX_IMAGE_BYTES:
        raise PublicEvidenceError(f"compact source image exceeds its size limit: {source}")

    descriptor = -1
    try:
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(source, flags)
        opened_stat = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_stat.st_mode)
            or opened_stat.st_dev != source_stat.st_dev
            or opened_stat.st_ino != source_stat.st_ino
            or opened_stat.st_size != source_stat.st_size
        ):
            raise PublicEvidenceError(
                f"compact source image changed while it was being opened: {source}"
            )
        digest = hashlib.sha256()
        copied = 0
        input_stream = os.fdopen(descriptor, "rb")
        descriptor = -1
        with input_stream, destination.open("xb") as output:
            for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                copied += len(chunk)
                if copied > MAX_IMAGE_BYTES:
                    raise PublicEvidenceError(
                        f"compact source image grew beyond its size limit: {source}"
                    )
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if copied != source_stat.st_size:
            raise PublicEvidenceError(
                f"compact source image changed while it was being copied: {source}"
            )
        if digest.hexdigest() != expected_sha256:
            raise PublicEvidenceError(
                f"compact source image digest changed after validation: {source}"
            )
        os.chmod(destination, 0o644)
    except PublicEvidenceError:
        destination.unlink(missing_ok=True)
        raise
    except OSError as exc:
        destination.unlink(missing_ok=True)
        raise PublicEvidenceError(f"cannot snapshot compact source image {source}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _encode_webp(source: Path, destination: Path) -> None:
    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - protected Pages installs the lockfile
        raise PublicEvidenceError("Pillow is required to compact Pages evidence") from exc
    try:
        Image.MAX_IMAGE_PIXELS = 20_000_000
        with Image.open(source) as image:
            if image.format != "PNG":
                raise PublicEvidenceError(f"compact source is not a PNG: {source}")
            image.load()
            rendered = image.convert("RGB")
            rendered.thumbnail((1600, 900), Image.Resampling.LANCZOS)
            rendered.save(destination, "WEBP", quality=82, method=6, exact=True)
    except PublicEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise PublicEvidenceError(f"cannot compact public screenshot {source}: {exc}") from exc


def compact_bundle(
    evidence_root: Path,
    output_root: Path,
    branch: str,
    *,
    only_branch: bool = True,
    expected_input_kind: str | None = None,
    expected_repository: str | None = None,
    expected_source_run_id: str | None = None,
    expected_target_run_id: str | None = None,
    expected_target_sha: str | None = None,
    catalog_path: Path = DEFAULT_CATALOG,
) -> Path:
    """Atomically copy or convert one validated bundle into the compact cache schema."""

    branch = _branch(branch, "branch", release=True)
    input_root = evidence_root.resolve()
    manifest = validate_bundle(
        input_root,
        branch,
        only_branch=only_branch,
        expected_kind=expected_input_kind,
        expected_repository=expected_repository,
        expected_source_run_id=expected_source_run_id,
        expected_target_run_id=expected_target_run_id,
        expected_target_sha=expected_target_sha,
        catalog_path=catalog_path,
    )
    destination_root = output_root.resolve()
    if destination_root.exists() and not destination_root.is_dir():
        raise PublicEvidenceError(
            f"compact evidence output is not a directory: {destination_root}"
        )
    destination_root.mkdir(parents=True, exist_ok=True)
    destination = destination_root / branch
    if destination.exists():
        raise PublicEvidenceError(
            f"refusing to replace existing compact evidence bundle {destination}"
        )

    temporary_root = Path(
        tempfile.mkdtemp(prefix=f".{branch}.compact-", dir=destination_root)
    )
    staged_bundle = temporary_root / branch
    try:
        if manifest["schema_version"] == COMPACT_SCHEMA_VERSION:
            shutil.copytree(input_root / branch, staged_bundle)
        else:
            images = staged_bundle / "images"
            images.mkdir(parents=True)
            derivatives: dict[str, dict[str, Any]] = {}
            derivative_path_by_frame: dict[str, Path] = {}
            compact_frames: list[dict[str, Any]] = []
            for frame in manifest["frames"]:
                source_asset = frame["asset"]
                derivative = derivatives.get(source_asset)
                if derivative is None:
                    source = input_root / branch / source_asset
                    rendering = images / f".{frame['file_sha256']}.rendering.webp"
                    snapshot = temporary_root / f".{frame['file_sha256']}.source.png"
                    _snapshot_verified_image(
                        source,
                        snapshot,
                        expected_sha256=frame["file_sha256"],
                        boundary=input_root / branch,
                    )
                    try:
                        _encode_webp(snapshot, rendering)
                    finally:
                        snapshot.unlink(missing_ok=True)
                    try:
                        derivative_metrics = inspect_screenshot(
                            rendering, expected_format="WEBP"
                        )
                    except RuntimeFailure as exc:
                        raise PublicEvidenceError(str(exc)) from exc
                    derivative_sha256 = derivative_metrics["file_sha256"]
                    asset = f"images/{derivative_sha256}.webp"
                    final_image = staged_bundle / asset
                    if final_image.exists():
                        if sha256_file(final_image) != derivative_sha256:
                            raise PublicEvidenceError(
                                f"compact derivative digest collision at {final_image}"
                            )
                        rendering.unlink()
                    else:
                        os.replace(rendering, final_image)
                    derivative = {
                        "asset": asset,
                        "format": "webp",
                        "file_sha256": derivative_sha256,
                        "width": derivative_metrics["width"],
                        "height": derivative_metrics["height"],
                        "pixel_validation": derivative_metrics,
                    }
                    derivatives[source_asset] = derivative
                compact_frame = {key: frame[key] for key in SOURCE_FRAME_FIELDS}
                compact_frame.update(
                    {key: frame[key] for key in OPTIONAL_FRAME_FIELDS if key in frame}
                )
                compact_frame["derivative"] = derivative
                compact_frames.append(compact_frame)
                derivative_path_by_frame[frame["frame_id"]] = (
                    staged_bundle / derivative["asset"]
                )

            compact_comparisons: list[dict[str, Any]] = []
            for comparison in manifest["comparisons"]:
                source_metrics = comparison["pixel_validation"]
                region = source_metrics.get("region")
                try:
                    derivative_metrics = compare_screenshots(
                        derivative_path_by_frame[comparison["first_frame_id"]],
                        derivative_path_by_frame[comparison["second_frame_id"]],
                        source_metrics["required_changed_fraction"],
                        tuple(region) if region is not None else None,
                    )
                except RuntimeFailure as exc:
                    raise PublicEvidenceError(str(exc)) from exc
                compact_comparisons.append(
                    {
                        **comparison,
                        "derivative_pixel_validation": derivative_metrics,
                    }
                )

            compact_manifest = {
                **manifest,
                "schema_version": COMPACT_SCHEMA_VERSION,
                "frames": compact_frames,
                "comparisons": compact_comparisons,
            }
            (staged_bundle / "manifest.json").write_text(
                json.dumps(
                    compact_manifest, indent=2, sort_keys=True, allow_nan=False
                )
                + "\n",
                encoding="utf-8",
            )

        validate_bundle(
            temporary_root,
            branch,
            only_branch=True,
            expected_kind="compact",
            expected_repository=expected_repository,
            expected_source_run_id=expected_source_run_id,
            expected_target_run_id=expected_target_run_id,
            expected_target_sha=expected_target_sha,
            catalog_path=catalog_path,
        )
        os.replace(staged_bundle, destination)
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)
    return destination


def carry_forward(
    *,
    evidence_root: Path,
    output_root: Path,
    branch: str,
    coverage_sha: str,
    expected_repository: str | None = None,
    catalog_path: Path = DEFAULT_CATALOG,
) -> Path:
    """Rebind one validated bundle to a protected non-visual descendant head.

    Only the coverage head changes. The packaged provenance keeps naming the exact run and
    commit that produced these pixels, so the published record still says where the
    screenshots came from and never claims a run tested a head it did not.
    """

    branch = _branch(branch, "branch", release=True)
    coverage_sha = _sha(coverage_sha, "coverage_sha")
    manifest = validate_bundle(
        evidence_root,
        branch,
        only_branch=True,
        expected_repository=expected_repository,
        catalog_path=catalog_path,
    )
    if output_root.is_symlink():
        raise PublicEvidenceError("evidence output root cannot be a symlink")
    destination_root = output_root.resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    destination = destination_root / branch
    if destination.exists() or destination.is_symlink():
        raise PublicEvidenceError(f"refusing to replace {destination}")
    temporary = Path(tempfile.mkdtemp(prefix=f".{branch}.carry-", dir=destination_root))
    staged = temporary / branch
    try:
        shutil.copytree(evidence_root.resolve() / branch, staged)
        carried = json.loads(json.dumps(manifest))
        carried["provenance"]["coverage_sha"] = coverage_sha
        (staged / "manifest.json").write_text(
            json.dumps(carried, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        validate_bundle(
            temporary,
            branch,
            only_branch=True,
            expected_repository=expected_repository,
            expected_coverage_sha=coverage_sha,
            catalog_path=catalog_path,
        )
        os.replace(staged, destination)
        return destination
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    prepare_parser = subparsers.add_parser("prepare")
    prepare_parser.add_argument("--e2e-root", type=Path, required=True)
    prepare_parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    prepare_parser.add_argument(
        "--contract",
        "--catalog",
        dest="catalog",
        type=Path,
        default=DEFAULT_CATALOG,
    )
    prepare_parser.add_argument("--output", type=Path, required=True)
    prepare_parser.add_argument("--repository", required=True)
    prepare_parser.add_argument("--source-run-id", required=True)
    prepare_parser.add_argument("--source-branch", required=True)
    prepare_parser.add_argument("--source-sha", required=True)
    prepare_parser.add_argument("--source-created-at", required=True)
    prepare_parser.add_argument("--target-run-id", required=True)
    prepare_parser.add_argument("--target-branch", required=True)
    prepare_parser.add_argument("--target-sha", required=True)
    prepare_parser.add_argument("--target-created-at", required=True)

    compact_parser = subparsers.add_parser("compact")
    compact_parser.add_argument("--evidence-root", type=Path, required=True)
    compact_parser.add_argument("--output", type=Path, required=True)
    compact_parser.add_argument("--branch", required=True)
    compact_parser.add_argument("--allow-sibling-branches", action="store_true")
    compact_parser.add_argument("--input-kind", choices=("raw", "compact"))
    compact_parser.add_argument("--repository")
    compact_parser.add_argument("--source-run-id")
    compact_parser.add_argument("--target-run-id")
    compact_parser.add_argument("--target-sha")
    compact_parser.add_argument(
        "--contract",
        "--catalog",
        dest="catalog",
        type=Path,
        default=DEFAULT_CATALOG,
    )

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--evidence-root", type=Path, required=True)
    validate_parser.add_argument("--branch", required=True)
    validate_parser.add_argument("--only-branch", action="store_true")
    validate_parser.add_argument("--repository")
    validate_parser.add_argument("--source-run-id")
    validate_parser.add_argument("--target-run-id")
    validate_parser.add_argument("--target-sha")
    validate_parser.add_argument("--coverage-sha")
    validate_parser.add_argument("--kind", choices=("raw", "compact"))
    validate_parser.add_argument(
        "--contract",
        "--catalog",
        dest="catalog",
        type=Path,
        default=DEFAULT_CATALOG,
    )

    carry_parser = subparsers.add_parser("carry-forward")
    carry_parser.add_argument("--evidence-root", type=Path, required=True)
    carry_parser.add_argument("--output", type=Path, required=True)
    carry_parser.add_argument("--branch", required=True)
    carry_parser.add_argument("--coverage-sha", required=True)
    carry_parser.add_argument("--repository")
    carry_parser.add_argument(
        "--contract",
        "--catalog",
        dest="catalog",
        type=Path,
        default=DEFAULT_CATALOG,
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "prepare":
            bundle = prepare(
                e2e_root=args.e2e_root,
                matrix_path=args.matrix,
                catalog_path=args.catalog,
                output_root=args.output,
                repository=args.repository,
                source_run_id=args.source_run_id,
                source_branch=args.source_branch,
                source_sha=args.source_sha,
                source_created_at=args.source_created_at,
                target_run_id=args.target_run_id,
                target_branch=args.target_branch,
                target_sha=args.target_sha,
                target_created_at=args.target_created_at,
            )
            print(bundle)
        elif args.command == "compact":
            bundle = compact_bundle(
                args.evidence_root,
                args.output,
                args.branch,
                only_branch=not args.allow_sibling_branches,
                expected_input_kind=args.input_kind,
                expected_repository=args.repository,
                expected_source_run_id=args.source_run_id,
                expected_target_run_id=args.target_run_id,
                expected_target_sha=args.target_sha,
                catalog_path=args.catalog,
            )
            print(bundle)
        elif args.command == "carry-forward":
            bundle = carry_forward(
                evidence_root=args.evidence_root,
                output_root=args.output,
                branch=args.branch,
                coverage_sha=args.coverage_sha,
                expected_repository=args.repository,
                catalog_path=args.catalog,
            )
            print(bundle)
        else:
            validate_bundle(
                args.evidence_root,
                args.branch,
                only_branch=args.only_branch,
                expected_kind=args.kind,
                expected_repository=args.repository,
                expected_source_run_id=args.source_run_id,
                expected_target_run_id=args.target_run_id,
                expected_target_sha=args.target_sha,
                expected_coverage_sha=args.coverage_sha,
                catalog_path=args.catalog,
            )
            print(f"validated public E2E evidence for {args.branch}")
        return 0
    except (PublicEvidenceError, VisualEvidenceError) as exc:
        print(f"public evidence error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
