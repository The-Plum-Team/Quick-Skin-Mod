#!/usr/bin/env python3
"""Read validated packaged-E2E frames without inferring identity from filenames."""

from __future__ import annotations

import hashlib
import io
import json
import math
import os
import re
import stat
import struct
from dataclasses import dataclass
from itertools import islice
from pathlib import Path, PurePosixPath
from typing import Any

from scenario_contract import (
    DEFAULT_CONTRACT,
    ScenarioContract,
    ScenarioContractError,
    load_contract,
)
from mod_compatibility import (
    DEFAULT_CONTRACT as DEFAULT_COMPATIBILITY_CONTRACT,
    CompatibilityContractError,
    CompatibilityLane,
    load_contract as load_compatibility_contract,
    resolve_lane as resolve_compatibility_lane,
)

REPO = Path(__file__).resolve().parent.parent
# Compatibility alias for existing --catalog callers. The only accepted source is now the
# canonical scenario contract.
DEFAULT_CATALOG = DEFAULT_CONTRACT
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SCREENSHOT_METRIC_FIELDS = frozenset(
    {
        "width",
        "height",
        "file_sha256",
        "pixel_sha256",
        "luma_entropy",
        "meaningful_colors",
        "dark_fraction",
        "light_fraction",
    }
)
COMPARISON_METRIC_FIELDS = frozenset(
    {"changed_fraction", "rms_difference", "required_changed_fraction", "region"}
)
RESULT_FIELDS = frozenset(
    {
        "artifact_node",
        "runtime_version",
        "loader",
        "scenario",
        "contract_sha256",
        "jar_sha256",
        "installed_quickskin",
        "port",
        "status",
        "profile",
        "elapsed_s",
        "reports",
    }
)
COMPATIBILITY_RESULT_FIELDS = frozenset(
    {"compatibility", "installed_compatibility"}
)
REPORT_FIELDS = frozenset(
    {
        "version",
        "role",
        "scenario",
        "contract_sha256",
        "status",
        "steps",
        "pixel_validation",
    }
)
STEP_FIELDS = frozenset({"name", "status", "message", "screenshot"})
PIXEL_VALIDATION_FIELDS = frozenset({"screenshots", "comparisons"})
MAX_RESULT_FILES = 256
MAX_RESULT_BYTES = 4 * 1024 * 1024
MAX_RUNTIME_EVIDENCE_LENGTH = 4096
MAX_EVIDENCE_SCREENSHOT_BYTES = 32 * 1024 * 1024
MAX_EVIDENCE_IMAGE_PIXELS = 20_000_000
MIN_REVIEW_IMAGE_WIDTH = 640
MIN_REVIEW_IMAGE_HEIGHT = 360


class VisualEvidenceError(ValueError):
    """Raised when public visual evidence cannot be proven from packaged results."""


@dataclass(frozen=True)
class Catalog:
    captures: tuple[dict[str, str], ...]
    by_id: dict[str, dict[str, str]]
    by_key: dict[tuple[str, str, str], dict[str, str]]
    contract: ScenarioContract

    @property
    def contract_sha256(self) -> str:
        return self.contract.sha256


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON object key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def parse_finite_json_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError(f"non-finite JSON number {value!r}")
    return parsed


def _read_json(
    path: Path,
    label: str,
    *,
    maximum_bytes: int = MAX_RESULT_BYTES,
) -> Any:
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
        raise VisualEvidenceError(f"cannot read {label} {path}: {exc}") from exc


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise VisualEvidenceError(f"{label} must be a non-empty string")
    return value.strip()


def load_catalog(path: Path = DEFAULT_CATALOG) -> Catalog:
    try:
        contract = load_contract(path)
    except ScenarioContractError as exc:
        raise VisualEvidenceError(f"invalid scenario contract {path}: {exc}") from exc
    captures = tuple(
        {
            "capture_id": item.capture_id,
            "scenario": item.scenario,
            "role": item.role,
            "step": item.step,
            "title": item.title,
            "review_tier": item.review_tier,
            "expectation": item.expectation,
        }
        for item in contract.captures
    )
    by_id = {capture["capture_id"]: capture for capture in captures}
    by_key = {
        (capture["scenario"], capture["role"], capture["step"]): capture
        for capture in captures
    }
    return Catalog(captures, by_id, by_key, contract)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        raise VisualEvidenceError(f"cannot hash screenshot {path}: {exc}") from exc
    return digest.hexdigest()


def png_dimensions(path: Path) -> tuple[int, int]:
    try:
        with path.open("rb") as handle:
            signature = handle.read(8)
            length = handle.read(4)
            chunk = handle.read(4)
            dimensions = handle.read(8)
    except OSError as exc:
        raise VisualEvidenceError(f"cannot read screenshot {path}: {exc}") from exc
    if signature != PNG_SIGNATURE or len(length) != 4 or chunk != b"IHDR":
        raise VisualEvidenceError(f"screenshot is not a PNG with an IHDR header: {path}")
    if struct.unpack(">I", length)[0] != 13 or len(dimensions) != 8:
        raise VisualEvidenceError(f"screenshot has an invalid PNG IHDR header: {path}")
    width, height = struct.unpack(">II", dimensions)
    if width <= 0 or height <= 0:
        raise VisualEvidenceError(f"screenshot has invalid dimensions {width}x{height}: {path}")
    return width, height


def read_png_snapshot(path: Path) -> tuple[tuple[int, int], str, str, bytes]:
    """Read one bounded regular PNG once, fully decode it, and retain those exact bytes."""

    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked image dependency
        raise VisualEvidenceError(
            "Pillow is required to validate packaged screenshots"
        ) from exc
    descriptor = -1
    try:
        metadata = path.lstat()
        if not stat.S_ISREG(metadata.st_mode):
            raise VisualEvidenceError(f"screenshot is not a regular file: {path}")
        if metadata.st_size <= 0 or metadata.st_size > MAX_EVIDENCE_SCREENSHOT_BYTES:
            raise VisualEvidenceError(
                f"screenshot size is outside 1..{MAX_EVIDENCE_SCREENSHOT_BYTES} bytes: {path}"
            )
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened.st_mode)
            or opened.st_dev != metadata.st_dev
            or opened.st_ino != metadata.st_ino
            or opened.st_size != metadata.st_size
        ):
            raise VisualEvidenceError(f"screenshot changed while opening: {path}")
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            descriptor = -1
            payload = handle.read(MAX_EVIDENCE_SCREENSHOT_BYTES + 1)
        if len(payload) != metadata.st_size:
            raise VisualEvidenceError(f"screenshot changed while reading: {path}")

        Image.MAX_IMAGE_PIXELS = MAX_EVIDENCE_IMAGE_PIXELS
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != "PNG":
                raise VisualEvidenceError(f"screenshot is not a PNG: {path}")
            if getattr(image, "n_frames", 1) != 1:
                raise VisualEvidenceError(f"screenshot must be a static PNG: {path}")
            width, height = image.size
            if (
                width <= 0
                or height <= 0
                or width * height > MAX_EVIDENCE_IMAGE_PIXELS
            ):
                raise VisualEvidenceError(
                    f"screenshot dimensions are outside the pixel limit: {path} "
                    f"({width}x{height})"
                )
            image.load()
            pixel_sha256 = hashlib.sha256(image.convert("RGB").tobytes()).hexdigest()
        return (
            (width, height),
            hashlib.sha256(payload).hexdigest(),
            pixel_sha256,
            payload,
        )
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise VisualEvidenceError(f"cannot fully decode screenshot {path}: {exc}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def validate_png_snapshot(path: Path) -> tuple[tuple[int, int], str, str]:
    """Read and fully decode one bounded PNG, returning its trusted identity."""

    dimensions, file_sha256, pixel_sha256, _payload = read_png_snapshot(path)
    return dimensions, file_sha256, pixel_sha256


def canonicalize_png_snapshot(
    path: Path,
) -> tuple[tuple[int, int], str, str, str, bytes]:
    """Decode one source PNG and return a metadata-free canonical RGB PNG snapshot."""

    dimensions, source_sha256, pixel_sha256, payload = read_png_snapshot(path)
    width, height = dimensions
    if width < MIN_REVIEW_IMAGE_WIDTH or height < MIN_REVIEW_IMAGE_HEIGHT:
        raise VisualEvidenceError(
            f"review screenshot is smaller than {MIN_REVIEW_IMAGE_WIDTH}x"
            f"{MIN_REVIEW_IMAGE_HEIGHT}: {path} ({width}x{height})"
        )
    try:
        from PIL import Image, UnidentifiedImageError

        with Image.open(io.BytesIO(payload)) as source:
            source.load()
            rgb = source.convert("RGB")
        canonical_buffer = io.BytesIO()
        Image.frombytes("RGB", dimensions, rgb.tobytes()).save(
            canonical_buffer,
            format="PNG",
            optimize=False,
            compress_level=9,
        )
        canonical = canonical_buffer.getvalue()
        if not canonical or len(canonical) > MAX_EVIDENCE_SCREENSHOT_BYTES:
            raise VisualEvidenceError(
                f"canonical review screenshot exceeds its byte limit: {path}"
            )
        with Image.open(io.BytesIO(canonical)) as served:
            if served.format != "PNG" or served.mode != "RGB" or served.size != dimensions:
                raise VisualEvidenceError(
                    f"canonical review screenshot identity changed: {path}"
                )
            served.load()
            served_pixel_sha256 = hashlib.sha256(served.tobytes()).hexdigest()
        if served_pixel_sha256 != pixel_sha256:
            raise VisualEvidenceError(
                f"canonical review screenshot pixels changed: {path}"
            )
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise VisualEvidenceError(
            f"cannot canonicalize review screenshot {path}: {exc}"
        ) from exc
    return (
        dimensions,
        source_sha256,
        pixel_sha256,
        hashlib.sha256(canonical).hexdigest(),
        canonical,
    )


def reject_symlinks(path: Path, boundary: Path, label: str) -> None:
    """Reject a symlink in any existing path component below a lexical boundary."""

    raw_boundary = boundary.absolute()
    raw_path = path.absolute()
    try:
        relative = raw_path.relative_to(raw_boundary)
    except ValueError as exc:
        raise VisualEvidenceError(f"{label} escapes {raw_boundary}: {raw_path}") from exc
    current = raw_boundary
    if current.is_symlink():
        raise VisualEvidenceError(f"{label} boundary is a symlink: {current}")
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise VisualEvidenceError(f"{label} contains a symlink: {current}")


def _safe_screenshot(profile: Path, role: str, filename: Any) -> Path:
    name = _nonempty_string(filename, "report screenshot")
    if Path(name).name != name or not name.lower().endswith(".png"):
        raise VisualEvidenceError(f"unsafe screenshot filename {name!r}")
    raw_screenshots = profile / role / "screenshots"
    raw_screenshot = raw_screenshots / name
    reject_symlinks(raw_screenshot, profile, "screenshot path")
    screenshots = raw_screenshots.resolve()
    screenshot = raw_screenshot.resolve()
    if screenshot.parent != screenshots or not screenshot.is_file():
        raise VisualEvidenceError(f"missing or escaping screenshot {screenshot}")
    return screenshot


def _copy_json_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise VisualEvidenceError(f"{label} must be an object")
    try:
        copied = json.loads(json.dumps(value, allow_nan=False))
    except (TypeError, ValueError) as exc:
        raise VisualEvidenceError(f"{label} is not finite JSON data: {exc}") from exc
    if not isinstance(copied, dict):  # pragma: no cover - guarded above
        raise VisualEvidenceError(f"{label} must be an object")
    return copied


def _finite_number(value: Any, label: str, *, minimum: float, maximum: float) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < minimum
        or value > maximum
    ):
        raise VisualEvidenceError(f"{label} must be a finite number in [{minimum}, {maximum}]")
    return value


def validate_screenshot_metrics(value: Any, label: str) -> dict[str, Any]:
    """Return the exact public screenshot metric schema, rejecting arbitrary payloads."""

    metrics = _copy_json_object(value, label)
    if set(metrics) != SCREENSHOT_METRIC_FIELDS:
        raise VisualEvidenceError(
            f"{label} must contain exactly {sorted(SCREENSHOT_METRIC_FIELDS)}"
        )
    width = metrics["width"]
    height = metrics["height"]
    meaningful_colors = metrics["meaningful_colors"]
    if isinstance(width, bool) or not isinstance(width, int) or width <= 0:
        raise VisualEvidenceError(f"{label}.width must be a positive integer")
    if isinstance(height, bool) or not isinstance(height, int) or height <= 0:
        raise VisualEvidenceError(f"{label}.height must be a positive integer")
    if (
        isinstance(meaningful_colors, bool)
        or not isinstance(meaningful_colors, int)
        or not 0 <= meaningful_colors <= 32
    ):
        raise VisualEvidenceError(f"{label}.meaningful_colors must be an integer in [0, 32]")
    for field in ("file_sha256", "pixel_sha256"):
        if not isinstance(metrics[field], str) or not SHA256.fullmatch(metrics[field]):
            raise VisualEvidenceError(f"{label}.{field} must be a lowercase SHA-256 digest")
    _finite_number(metrics["luma_entropy"], f"{label}.luma_entropy", minimum=0, maximum=8)
    _finite_number(metrics["dark_fraction"], f"{label}.dark_fraction", minimum=0, maximum=1)
    _finite_number(metrics["light_fraction"], f"{label}.light_fraction", minimum=0, maximum=1)
    return {field: metrics[field] for field in sorted(SCREENSHOT_METRIC_FIELDS)}


def validate_comparison_metrics(value: Any, label: str) -> dict[str, Any]:
    """Return the exact public comparison schema, rejecting arbitrary payloads."""

    metrics = _copy_json_object(value, label)
    fields = set(metrics)
    required = COMPARISON_METRIC_FIELDS - {"region"}
    if not required <= fields or not fields <= COMPARISON_METRIC_FIELDS:
        raise VisualEvidenceError(
            f"{label} fields must be {sorted(required)} with optional region"
        )
    changed = _finite_number(
        metrics["changed_fraction"], f"{label}.changed_fraction", minimum=0, maximum=1
    )
    required_change = _finite_number(
        metrics["required_changed_fraction"],
        f"{label}.required_changed_fraction",
        minimum=0,
        maximum=1,
    )
    _finite_number(
        metrics["rms_difference"], f"{label}.rms_difference", minimum=0, maximum=255
    )
    if changed < required_change:
        raise VisualEvidenceError(f"{label} did not meet its required changed fraction")
    if "region" in metrics:
        region = metrics["region"]
        if not isinstance(region, list) or len(region) != 4:
            raise VisualEvidenceError(f"{label}.region must contain four fractions")
        for index, coordinate in enumerate(region):
            _finite_number(
                coordinate, f"{label}.region[{index}]", minimum=0, maximum=1
            )
        if region[0] >= region[2] or region[1] >= region[3]:
            raise VisualEvidenceError(f"{label}.region must describe a non-empty rectangle")
    return {field: metrics[field] for field in sorted(fields)}


def validate_installed_quickskin(
    value: Any,
    *,
    expected_roles: set[str],
    jar_sha256: str,
    label: str,
) -> tuple[dict[str, str], ...]:
    """Validate the exact production JAR copies recorded by the packaged runner."""

    if not isinstance(value, list) or len(value) != len(expected_roles) + 1:
        raise VisualEvidenceError(
            f"{label} must contain exactly the server and every scenario client"
        )
    expected_roots = {"server", *expected_roles}
    observed_roots: set[str] = set()
    observed_paths: set[str] = set()
    installed: list[dict[str, str]] = []
    for index, item in enumerate(value):
        entry_label = f"{label}[{index}]"
        if not isinstance(item, dict) or set(item) != {"path", "sha256"}:
            raise VisualEvidenceError(
                f"{entry_label} must contain exactly path and sha256"
            )
        raw_path = item.get("path")
        if (
            not isinstance(raw_path, str)
            or not raw_path
            or "\\" in raw_path
            or any(ord(character) < 32 for character in raw_path)
        ):
            raise VisualEvidenceError(f"{entry_label}.path is unsafe")
        path = PurePosixPath(raw_path)
        if (
            path.is_absolute()
            or raw_path != path.as_posix()
            or any(part in {"", ".", ".."} for part in path.parts)
            or len(path.parts) != 3
            or path.parts[1] != "mods"
            or not path.parts[2].lower().endswith(".jar")
        ):
            raise VisualEvidenceError(f"{entry_label}.path is unsafe")
        normalized = path.as_posix()
        collision_key = normalized.casefold()
        if collision_key in observed_paths:
            raise VisualEvidenceError(f"{label} contains a duplicate path {normalized!r}")
        observed_paths.add(collision_key)
        root = path.parts[0]
        if root not in expected_roots or root in observed_roots:
            raise VisualEvidenceError(f"{label} has invalid or duplicate install root {root!r}")
        observed_roots.add(root)
        digest = item.get("sha256")
        if not isinstance(digest, str) or not SHA256.fullmatch(digest):
            raise VisualEvidenceError(f"{entry_label}.sha256 must be a lowercase SHA-256")
        if digest != jar_sha256:
            raise VisualEvidenceError(f"{entry_label}.sha256 disagrees with result.jar_sha256")
        installed.append({"path": normalized, "sha256": digest})
    if observed_roots != expected_roots:
        raise VisualEvidenceError(
            f"{label} install roots disagree: {sorted(observed_roots)} != {sorted(expected_roots)}"
        )
    return tuple(installed)


def validate_installed_compatibility(
    value: Any,
    *,
    expected_roles: set[str],
    lane: CompatibilityLane,
    label: str,
) -> tuple[dict[str, str], ...]:
    """Validate every exact optional-mod copy and its declared install side."""

    expected_roots = set(expected_roles)
    if lane.mod.install_on == "client-and-server":
        expected_roots.add("server")
    expected_files = {
        item.filename: item.sha256 for item in lane.artifact.files
    }
    expected_pairs = {
        (root, filename)
        for root in expected_roots
        for filename in expected_files
    }
    if not isinstance(value, list) or len(value) != len(expected_pairs):
        raise VisualEvidenceError(
            f"{label} must contain every declared compatibility file/install root"
        )
    observed: set[tuple[str, str]] = set()
    installed: list[dict[str, str]] = []
    for index, item in enumerate(value):
        entry_label = f"{label}[{index}]"
        if not isinstance(item, dict) or set(item) != {"path", "sha256"}:
            raise VisualEvidenceError(
                f"{entry_label} must contain exactly path and sha256"
            )
        raw_path = item.get("path")
        if not isinstance(raw_path, str) or "\\" in raw_path:
            raise VisualEvidenceError(f"{entry_label}.path is unsafe")
        path = PurePosixPath(raw_path)
        if (
            path.is_absolute()
            or raw_path != path.as_posix()
            or len(path.parts) != 3
            or path.parts[1] != "mods"
            or any(part in {"", ".", ".."} for part in path.parts)
        ):
            raise VisualEvidenceError(f"{entry_label}.path is unsafe")
        pair = (path.parts[0], path.parts[2])
        if pair not in expected_pairs or pair in observed:
            raise VisualEvidenceError(f"{entry_label}.path is unexpected or duplicated")
        observed.add(pair)
        digest = item.get("sha256")
        if digest != expected_files[pair[1]]:
            raise VisualEvidenceError(f"{entry_label}.sha256 disagrees with the lock")
        installed.append({"path": path.as_posix(), "sha256": digest})
    if observed != expected_pairs:
        raise VisualEvidenceError(f"{label} install coverage is incomplete")
    return tuple(installed)


def collect_evidence(
    output_root: Path,
    catalog: Catalog,
    *,
    compatibility_id: str | None = None,
    compatibility_contract_path: Path = DEFAULT_COMPATIBILITY_CONTRACT,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """Return lanes, frames and directed pixel comparisons from successful result files."""

    root = output_root.resolve()
    profiles = root / "profiles"
    if not profiles.is_dir():
        raise VisualEvidenceError(f"missing packaged E2E profiles directory: {profiles}")
    result_paths = list(
        islice(profiles.glob("*/result.json"), MAX_RESULT_FILES + 1)
    )
    if not result_paths:
        raise VisualEvidenceError(f"no packaged E2E result.json files below {profiles}")
    if len(result_paths) > MAX_RESULT_FILES:
        raise VisualEvidenceError(
            f"packaged E2E evidence exceeds {MAX_RESULT_FILES} result files"
        )
    result_paths.sort()

    lanes: list[dict[str, Any]] = []
    frames: list[dict[str, Any]] = []
    comparisons: list[dict[str, Any]] = []
    capture_order = {
        capture["capture_id"]: index for index, capture in enumerate(catalog.captures)
    }
    lane_ids: set[str] = set()
    frame_ids: set[str] = set()
    comparison_ids: set[str] = set()

    compatibility_contract = (
        load_compatibility_contract(compatibility_contract_path)
        if compatibility_id is not None
        else None
    )
    for result_path in result_paths:
        reject_symlinks(result_path, profiles, "packaged result path")
        result = _read_json(result_path, "packaged E2E result")
        expected_result_fields = (
            RESULT_FIELDS | COMPATIBILITY_RESULT_FIELDS
            if compatibility_id is not None
            else RESULT_FIELDS
        )
        if not isinstance(result, dict) or set(result) != expected_result_fields:
            raise VisualEvidenceError(
                "packaged result must contain exactly "
                f"{sorted(expected_result_fields)}: {result_path}"
            )
        artifact_node = _nonempty_string(result.get("artifact_node"), "result.artifact_node")
        version = _nonempty_string(result.get("runtime_version"), "result.runtime_version")
        loader = _nonempty_string(result.get("loader"), "result.loader")
        scenario = _nonempty_string(result.get("scenario"), "result.scenario")
        for value, label in (
            (artifact_node, "artifact_node"),
            (version, "runtime_version"),
            (loader, "loader"),
            (scenario, "scenario"),
        ):
            if not SAFE_ID.fullmatch(value):
                raise VisualEvidenceError(f"result has unsafe {label} {value!r}")
        if loader not in {"fabric", "forge", "neoforge"}:
            raise VisualEvidenceError(f"result has unsupported loader {loader!r}")
        if result.get("status") != "pass":
            raise VisualEvidenceError(f"public evidence cannot include non-pass result {result_path}")
        if result.get("contract_sha256") != catalog.contract_sha256:
            raise VisualEvidenceError(
                f"packaged result scenario contract hash mismatch in {result_path}"
            )
        try:
            scenario_contract = catalog.contract.scenario(scenario)
        except ScenarioContractError as exc:
            raise VisualEvidenceError(
                f"packaged result uses unknown scenario {scenario!r}"
            ) from exc
        jar_sha256 = _nonempty_string(result.get("jar_sha256"), "result.jar_sha256")
        if not SHA256.fullmatch(jar_sha256):
            raise VisualEvidenceError(f"result has invalid jar_sha256 in {result_path}")
        port = result.get("port")
        if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
            raise VisualEvidenceError(f"result has invalid port in {result_path}")

        raw_profile = result_path.parent
        profile = raw_profile.resolve()
        expected_profile = profile.relative_to(root).as_posix()
        if result.get("profile") != expected_profile:
            raise VisualEvidenceError(
                f"result profile identity mismatch: {result.get('profile')!r} != {expected_profile!r}"
            )
        compatibility_lane: CompatibilityLane | None = None
        if compatibility_id is not None:
            if compatibility_contract is None:  # pragma: no cover - guarded above
                raise VisualEvidenceError("compatibility contract was not loaded")
            try:
                compatibility_lane = resolve_compatibility_lane(
                    compatibility_contract,
                    mod_id=compatibility_id,
                    artifact_node=artifact_node,
                    runtime_version=version,
                    loader=loader,
                )
            except CompatibilityContractError as exc:
                raise VisualEvidenceError(str(exc)) from exc
            if result.get("compatibility") != compatibility_lane.public_identity():
                raise VisualEvidenceError(
                    f"packaged compatibility identity disagrees with its lock in {result_path}"
                )
        compatibility_segment = (
            f"/{compatibility_id}" if compatibility_id is not None else ""
        )
        lane_id = f"{artifact_node}{compatibility_segment}/{scenario}"
        if lane_id in lane_ids:
            raise VisualEvidenceError(f"duplicate packaged result lane {lane_id!r}")
        lane_ids.add(lane_id)

        reports = result.get("reports")
        if not isinstance(reports, dict) or not reports:
            raise VisualEvidenceError(f"result reports must be a non-empty object: {result_path}")
        expected_roles = {role.role for role in scenario_contract.roles}
        if set(reports) != expected_roles:
            raise VisualEvidenceError(
                f"catalog/report role coverage mismatch for {lane_id}: "
                f"missing={sorted(expected_roles - set(reports))}, "
                f"extra={sorted(set(reports) - expected_roles)}"
            )
        validate_installed_quickskin(
            result.get("installed_quickskin"),
            expected_roles=expected_roles,
            jar_sha256=jar_sha256,
            label=f"result.installed_quickskin for {lane_id}",
        )
        if compatibility_lane is not None:
            validate_installed_compatibility(
                result.get("installed_compatibility"),
                expected_roles=expected_roles,
                lane=compatibility_lane,
                label=f"result.installed_compatibility for {lane_id}",
            )
        lane_frame_ids: dict[tuple[str, str], str] = {}
        for role in sorted(reports):
            if role not in {"client_a", "client_b"}:
                raise VisualEvidenceError(f"unsupported report role {role!r} in {result_path}")
            report = reports[role]
            if not isinstance(report, dict) or set(report) != REPORT_FIELDS:
                raise VisualEvidenceError(
                    f"report {role} must contain exactly {sorted(REPORT_FIELDS)} "
                    f"in {result_path}"
                )
            if (
                report.get("version") != version
                or report.get("scenario") != scenario
                or report.get("role") != role
                or report.get("status") != "pass"
            ):
                raise VisualEvidenceError(f"report identity/status mismatch for {lane_id}/{role}")
            if report.get("contract_sha256") != catalog.contract_sha256:
                raise VisualEvidenceError(
                    f"report scenario contract hash mismatch for {lane_id}/{role}"
                )
            try:
                role_contract = catalog.contract.role(scenario, role)
            except ScenarioContractError as exc:  # pragma: no cover - roles checked above
                raise VisualEvidenceError(
                    f"unknown report role contract for {lane_id}/{role}"
                ) from exc
            steps = report.get("steps")
            if (
                not isinstance(steps, list)
                or any(not isinstance(step, dict) for step in steps)
                or any(set(step) != STEP_FIELDS for step in steps)
                or [step.get("name") for step in steps] != list(role_contract.step_ids)
            ):
                raise VisualEvidenceError(
                    f"report steps disagree with scenario contract for {lane_id}/{role}"
                )
            for expected_step, step_record in zip(
                role_contract.steps, steps, strict=True
            ):
                if step_record.get("status") != "pass":
                    raise VisualEvidenceError(
                        f"successful report contains a non-pass step: "
                        f"{lane_id}/{role}/{expected_step.id}"
                    )
                runtime_evidence = step_record.get("message")
                if (
                    not isinstance(runtime_evidence, str)
                    or not runtime_evidence.strip()
                    or len(runtime_evidence) > MAX_RUNTIME_EVIDENCE_LENGTH
                    or any(
                        ord(character) < 32 or ord(character) == 127
                        for character in runtime_evidence
                    )
                ):
                    raise VisualEvidenceError(
                        f"report step message must be non-empty, bounded printable evidence: "
                        f"{lane_id}/{role}/{expected_step.id}"
                    )
                if (
                    expected_step.assertion_required
                    and step_record.get("status") != "pass"
                ):
                    raise VisualEvidenceError(
                        f"required assertion did not pass: "
                        f"{lane_id}/{role}/{expected_step.id}"
                    )
                screenshot = step_record.get("screenshot")
                if expected_step.capture is not None:
                    if not isinstance(screenshot, str) or not screenshot:
                        raise VisualEvidenceError(
                            f"required screenshot is missing: "
                            f"{lane_id}/{role}/{expected_step.id}"
                        )
                elif screenshot is not None:
                    raise VisualEvidenceError(
                        f"non-capture step produced a screenshot: "
                        f"{lane_id}/{role}/{expected_step.id}"
                    )
            pixel_validation = report.get("pixel_validation")
            if (
                not isinstance(pixel_validation, dict)
                or set(pixel_validation) != PIXEL_VALIDATION_FIELDS
            ):
                raise VisualEvidenceError(f"missing pixel validation for {lane_id}/{role}")
            screenshot_metrics = pixel_validation.get("screenshots")
            if not isinstance(screenshot_metrics, dict):
                raise VisualEvidenceError(f"missing screenshot metrics for {lane_id}/{role}")
            expected_capture_steps = {
                step.id for step in role_contract.steps if step.capture is not None
            }
            if set(screenshot_metrics) != expected_capture_steps:
                raise VisualEvidenceError(
                    f"screenshot metrics disagree with scenario contract for {lane_id}/{role}"
                )

            reported_steps: set[str] = set()
            for step_index, step_record in enumerate(steps):
                if not isinstance(step_record, dict):
                    raise VisualEvidenceError(
                        f"report step {step_index} must be an object for {lane_id}/{role}"
                    )
                screenshot_name = step_record.get("screenshot")
                if not screenshot_name:
                    continue
                step = _nonempty_string(step_record.get("name"), "report step name")
                if step_record.get("status") != "pass":
                    raise VisualEvidenceError(f"screenshot step did not pass: {lane_id}/{role}/{step}")
                if step in reported_steps:
                    raise VisualEvidenceError(f"duplicate screenshot step: {lane_id}/{role}/{step}")
                reported_steps.add(step)
                capture = catalog.by_key.get((scenario, role, step))
                if capture is None:
                    raise VisualEvidenceError(
                        f"uncatalogued screenshot step: {scenario}/{role}/{step}"
                    )
                screenshot = _safe_screenshot(profile, role, screenshot_name)
                metrics = validate_screenshot_metrics(
                    screenshot_metrics.get(step), f"pixel metrics for {lane_id}/{role}/{step}"
                )
                width = metrics.get("width")
                height = metrics.get("height")
                file_sha256 = metrics.get("file_sha256")
                if (
                    isinstance(width, bool)
                    or not isinstance(width, int)
                    or width <= 0
                    or isinstance(height, bool)
                    or not isinstance(height, int)
                    or height <= 0
                    or not isinstance(file_sha256, str)
                    or not SHA256.fullmatch(file_sha256)
                ):
                    raise VisualEvidenceError(
                        f"invalid screenshot metrics for {lane_id}/{role}/{step}"
                    )
                (
                    actual_dimensions,
                    actual_sha256,
                    actual_pixel_sha256,
                ) = validate_png_snapshot(screenshot)
                if actual_dimensions != (width, height):
                    raise VisualEvidenceError(
                        f"screenshot dimensions disagree for {lane_id}/{role}/{step}: "
                        f"{actual_dimensions} != {(width, height)}"
                    )
                if actual_sha256 != file_sha256:
                    raise VisualEvidenceError(
                        f"screenshot digest disagrees for {lane_id}/{role}/{step}"
                    )
                if actual_pixel_sha256 != metrics["pixel_sha256"]:
                    raise VisualEvidenceError(
                        f"screenshot pixel digest disagrees for {lane_id}/{role}/{step}"
                    )
                frame_id = (
                    f"{artifact_node}{compatibility_segment}/{scenario}/{role}/{step}"
                )
                if frame_id in frame_ids:
                    raise VisualEvidenceError(f"duplicate visual frame {frame_id!r}")
                frame_ids.add(frame_id)
                lane_frame_ids[(role, step)] = frame_id
                frames.append(
                    {
                        "frame_id": frame_id,
                        "capture_id": capture["capture_id"],
                        "capture_order": capture_order[capture["capture_id"]],
                        "title": capture["title"],
                        "expectation": capture["expectation"],
                        "runtime_evidence": step_record["message"].strip(),
                        "review_tier": capture["review_tier"],
                        "artifact_node": artifact_node,
                        "version": version,
                        "loader": loader,
                        "scenario": scenario,
                        "role": role,
                        "step": step,
                        "filename": screenshot.name,
                        "source_path": str(screenshot),
                        "file_sha256": file_sha256,
                        "width": width,
                        "height": height,
                        "pixel_validation": metrics,
                    }
                )

            if reported_steps != expected_capture_steps:
                raise VisualEvidenceError(
                    f"catalog/report coverage mismatch for {lane_id}/{role}: "
                    f"missing={sorted(expected_capture_steps - reported_steps)}, "
                    f"extra={sorted(reported_steps - expected_capture_steps)}"
                )

            raw_comparisons = pixel_validation.get("comparisons")
            if not isinstance(raw_comparisons, dict):
                raise VisualEvidenceError(f"missing comparisons object for {lane_id}/{role}")
            expected_comparisons = {
                f"{item.first_step}->{item.second_step}": item
                for item in role_contract.comparisons
            }
            if set(raw_comparisons) != set(expected_comparisons):
                raise VisualEvidenceError(
                    f"comparisons disagree with scenario contract for {lane_id}/{role}"
                )
            for pair, comparison_contract in expected_comparisons.items():
                raw_metrics = raw_comparisons[pair]
                first_step = comparison_contract.first_step
                second_step = comparison_contract.second_step
                first_id = lane_frame_ids.get((role, first_step))
                second_id = lane_frame_ids.get((role, second_step))
                if first_id is None or second_id is None or first_id == second_id:
                    raise VisualEvidenceError(
                        f"comparison endpoints are not catalogued frames: {lane_id}/{role}/{pair}"
                    )
                comparison_id = (
                    f"{artifact_node}{compatibility_segment}/{scenario}/{role}/{pair}"
                )
                if comparison_id in comparison_ids:
                    raise VisualEvidenceError(f"duplicate visual comparison {comparison_id!r}")
                comparison_ids.add(comparison_id)
                metrics = validate_comparison_metrics(
                    raw_metrics, f"comparison metrics for {comparison_id}"
                )
                expected_region = (
                    list(comparison_contract.region)
                    if comparison_contract.region is not None
                    else None
                )
                if (
                    metrics["required_changed_fraction"]
                    != comparison_contract.minimum_changed_fraction
                    or metrics.get("region") != expected_region
                ):
                    raise VisualEvidenceError(
                        f"comparison threshold/region drifted for {comparison_id}"
                    )
                comparisons.append(
                    {
                        "comparison_id": comparison_id,
                        "artifact_node": artifact_node,
                        "version": version,
                        "loader": loader,
                        "scenario": scenario,
                        "role": role,
                        "first_frame_id": first_id,
                        "second_frame_id": second_id,
                        "pixel_validation": metrics,
                    }
                )

        elapsed = result.get("elapsed_s")
        if (
            isinstance(elapsed, bool)
            or not isinstance(elapsed, (int, float))
            or not math.isfinite(elapsed)
            or elapsed < 0
        ):
            raise VisualEvidenceError(f"result elapsed_s is invalid for {lane_id}")
        lanes.append(
            {
                "lane_id": lane_id,
                "artifact_node": artifact_node,
                "version": version,
                "loader": loader,
                "scenario": scenario,
                "jar_sha256": jar_sha256,
                "status": "pass",
                "roles": sorted(reports),
                "elapsed_s": elapsed,
                **(
                    {"compatibility_mod": compatibility_id}
                    if compatibility_id is not None
                    else {}
                ),
            }
        )

    lanes.sort(key=lambda item: (item["version"], item["loader"], item["scenario"]))
    frames.sort(
        key=lambda item: (
            item["version"],
            item["loader"],
            item["capture_order"],
        )
    )
    comparisons.sort(key=lambda item: item["comparison_id"])
    return lanes, frames, comparisons
