#!/usr/bin/env python3
"""Build the advisory AI manifest from authoritative packaged-E2E result files.

Each frame is identified by artifact/scenario/role/step. Filenames are payload metadata,
never identity, so visually similar captures from different scenarios cannot collapse.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import shutil
import stat
import sys
import tempfile
from pathlib import Path


RELEASE_SCRIPTS = Path(__file__).resolve().parents[1] / "scripts" / "release"
sys.path.insert(0, str(RELEASE_SCRIPTS))

from matrix import MatrixError, load_matrix  # noqa: E402

from visual_evidence import (
    DEFAULT_CATALOG,
    REPO,
    VisualEvidenceError,
    canonicalize_png_snapshot,
    collect_evidence,
    load_catalog,
)
from visual_similarity import SimilarityError, analyze_png_payloads


MAX_REVIEW_FRAMES = 512
# Leave explicit headroom inside the 512 MiB handoff envelope for the
# manifest, proof, ZIP metadata, and directory entries.
MAX_REVIEW_IMAGE_BYTES = 480 * 1024 * 1024
MAX_REVIEW_IMAGE_PIXELS = 512 * 1024 * 1024
SAFE_DIRECTORY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PUBLIC_MANIFEST_FIELDS = (
    "path",
    "label",
    "capture_id",
    "kind",
    "expectation",
    "runtime_evidence",
    "image_size",
    "review_regions",
    "candidate_semantic_sha256",
)
PUBLIC_REFERENCE_FIELDS = (
    "reference_path",
    "reference_label",
    "reference_semantic_sha256",
    "semantic_changed_fraction",
    "perceptual_delta",
)
MAX_REFERENCE_MANIFEST_BYTES = 10 * 1024 * 1024
MAX_SINGLE_IMAGE_BYTES = 32 * 1024 * 1024
VISUAL_REFERENCE_VERSION = "1.20.1"
VISUAL_REFERENCE_LOADER = "fabric"
VISUAL_REFERENCE_PEER_LOADER = "forge"


def _reject_duplicate_reference_keys(
    pairs: list[tuple[str, object]],
) -> dict[str, object]:
    parsed: dict[str, object] = {}
    for key, value in pairs:
        if key in parsed:
            raise ValueError(f"duplicate JSON object key {key!r}")
        parsed[key] = value
    return parsed


def _reject_nonfinite_reference_constant(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _parse_finite_reference_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError(f"non-finite JSON number {value!r}")
    return parsed


def parse_combos(raw: str | None) -> set[tuple[str, str]] | None:
    if raw is None:
        return None
    combos: set[tuple[str, str]] = set()
    for item in raw.split(","):
        parts = [part.strip() for part in item.strip().split("/")]
        if len(parts) != 2 or not all(parts):
            raise VisualEvidenceError(
                f"invalid combo {item!r}; expected comma-separated <version>/<loader> values"
            )
        combos.add((parts[0], parts[1]))
    if not combos:
        raise VisualEvidenceError("combo filter must not be empty")
    return combos


def build_manifest(
    e2e_root: Path,
    catalog_path: Path,
    *,
    include_all: bool,
    combos: set[tuple[str, str]] | None,
    reference_frames: dict[str, dict[str, object]] | None = None,
) -> list[dict[str, object]]:
    catalog = load_catalog(catalog_path)
    _, frames, _ = collect_evidence(e2e_root, catalog)
    anchor_frames: dict[tuple[str, str], dict[str, object]] = {}
    for frame in frames:
        if (
            frame["version"] == VISUAL_REFERENCE_VERSION
            and frame["loader"]
            in {VISUAL_REFERENCE_LOADER, VISUAL_REFERENCE_PEER_LOADER}
        ):
            key = (frame["loader"], frame["capture_id"])
            if key in anchor_frames:
                raise VisualEvidenceError(
                    "visual reference source contains duplicate 1.20.1 loader captures: "
                    f"{frame['loader']}/{frame['capture_id']}"
                )
            anchor_frames[key] = frame
    available = {(frame["version"], frame["loader"]) for frame in frames}
    if combos is not None:
        unknown = combos - available
        if unknown:
            formatted = ", ".join(f"{version}/{loader}" for version, loader in sorted(unknown))
            raise VisualEvidenceError(f"combo filter matched no packaged evidence: {formatted}")
    manifest: list[dict[str, object]] = []
    for frame in frames:
        if not (include_all or frame["review_tier"] == "key"):
            continue
        if combos is not None and (frame["version"], frame["loader"]) not in combos:
            continue
        item: dict[str, object] = {
            "path": frame["source_path"],
            "label": frame["frame_id"],
            "capture_id": frame["capture_id"],
            "kind": frame["capture_id"],
            "expectation": frame["expectation"],
            "runtime_evidence": frame["runtime_evidence"],
            "_verified_file_sha256": frame["file_sha256"],
            "_verified_pixel_sha256": frame["pixel_validation"]["pixel_sha256"],
            "_verified_width": frame["width"],
            "_verified_height": frame["height"],
            "_review_regions": frame["review_regions"],
            "_expected_size": catalog.contract.screenshot_size,
        }
        if reference_frames is not None:
            published_reference = reference_frames.get(frame["capture_id"])
            if published_reference is None:
                raise VisualEvidenceError(
                    f"visual reference is missing capture {frame['capture_id']!r}"
                )
            reference = published_reference
            if (
                frame["version"] == VISUAL_REFERENCE_VERSION
                and frame["loader"]
                in {VISUAL_REFERENCE_LOADER, VISUAL_REFERENCE_PEER_LOADER}
            ):
                peer_loader = (
                    VISUAL_REFERENCE_PEER_LOADER
                    if frame["loader"] == VISUAL_REFERENCE_LOADER
                    else VISUAL_REFERENCE_LOADER
                )
                peer = anchor_frames.get((peer_loader, frame["capture_id"]))
                if peer is None:
                    raise VisualEvidenceError(
                        "Minecraft 1.20.1 visual review requires matching Fabric and "
                        f"Forge captures; missing {peer_loader}/{frame['capture_id']}"
                    )
                reference = {
                    "path": peer["source_path"],
                    "label": peer["frame_id"],
                    "file_sha256": peer["file_sha256"],
                    "pixel_sha256": peer["pixel_validation"]["pixel_sha256"],
                    "width": peer["width"],
                    "height": peer["height"],
                    "format": "PNG",
                }
            item.update(
                {
                    "reference_path": reference["path"],
                    "reference_label": reference["label"],
                    "_reference_verified_file_sha256": reference["file_sha256"],
                    "_reference_verified_pixel_sha256": reference["pixel_sha256"],
                    "_reference_verified_width": reference["width"],
                    "_reference_verified_height": reference["height"],
                    "_reference_verified_format": reference["format"],
                }
            )
        manifest.append(item)
    if not manifest:
        raise VisualEvidenceError("visual review manifest would be empty")
    return manifest


def public_manifest(manifest: list[dict[str, object]]) -> list[dict[str, object]]:
    """Discard curator-only snapshot identities before exposing review instructions."""

    public: list[dict[str, object]] = []
    for index, item in enumerate(manifest):
        fields = list(PUBLIC_MANIFEST_FIELDS)
        has_reference = any(field in item for field in PUBLIC_REFERENCE_FIELDS)
        if has_reference:
            fields.extend(PUBLIC_REFERENCE_FIELDS)
        string_fields = {
            "path",
            "label",
            "capture_id",
            "kind",
            "expectation",
            "runtime_evidence",
            "candidate_semantic_sha256",
            "reference_path",
            "reference_label",
            "reference_semantic_sha256",
        }
        if any(
            field not in item
            or (field in string_fields and not isinstance(item[field], str))
            for field in fields
        ):
            raise VisualEvidenceError(f"visual review manifest entry {index} is invalid")
        public.append({field: item[field] for field in fields})
    return public


def validate_semantic_anchor_manifest(
    manifest: list[dict[str, object]],
) -> list[dict[str, object]]:
    """Require complete, unpaired Fabric and Forge 1.20.1 semantic coverage."""

    expected_artifacts = {
        f"{VISUAL_REFERENCE_LOADER}-{VISUAL_REFERENCE_VERSION}",
        f"{VISUAL_REFERENCE_PEER_LOADER}-{VISUAL_REFERENCE_VERSION}",
    }
    captures: dict[str, set[str]] = {artifact: set() for artifact in expected_artifacts}
    for index, item in enumerate(manifest):
        if any(field in item for field in PUBLIC_REFERENCE_FIELDS):
            raise VisualEvidenceError(
                "semantic anchor certification cannot contain reference images"
            )
        label = item.get("label")
        capture_id = item.get("capture_id")
        if not isinstance(label, str) or not isinstance(capture_id, str):
            raise VisualEvidenceError(
                f"semantic anchor frame {index} has no canonical identity"
            )
        artifact = label.split("/", 1)[0]
        if artifact not in expected_artifacts:
            raise VisualEvidenceError(
                f"semantic anchor contains a non-anchor artifact: {artifact!r}"
            )
        if capture_id in captures[artifact]:
            raise VisualEvidenceError(
                f"semantic anchor duplicates {artifact}/{capture_id}"
            )
        captures[artifact].add(capture_id)
    if not all(captures.values()) or len(
        {frozenset(capture_ids) for capture_ids in captures.values()}
    ) != 1:
        raise VisualEvidenceError(
            "semantic anchor requires identical complete Fabric and Forge capture sets"
        )
    return manifest


def _reference_identity_from_matrix(matrix: dict[str, object]) -> dict[str, str]:
    version = matrix.get("unit_test_version")
    project = matrix.get("project")
    branch = project.get("release_branch") if isinstance(project, dict) else None
    artifacts = matrix.get("artifacts")
    if (
        not isinstance(version, str)
        or not SAFE_ID.fullmatch(version)
        or not isinstance(branch, str)
        or not branch
        or not isinstance(artifacts, list)
    ):
        raise VisualEvidenceError("release matrix has no valid visual reference identity")
    if version != VISUAL_REFERENCE_VERSION:
        raise VisualEvidenceError(
            "protected master must keep Minecraft 1.20.1 as the visual reference"
        )
    candidates = [
        artifact
        for artifact in artifacts
        if isinstance(artifact, dict)
        and artifact.get("artifact_version") == version
        and artifact.get("loader") == VISUAL_REFERENCE_LOADER
    ]
    if len(candidates) != 1:
        raise VisualEvidenceError(
            "protected visual reference requires exactly one Fabric artifact at "
            f"the unit-test version {version}"
        )
    artifact_node = candidates[0].get("artifact_node")
    if not isinstance(artifact_node, str) or not SAFE_ID.fullmatch(artifact_node):
        raise VisualEvidenceError("protected visual reference artifact is invalid")
    return {
        "release_branch": branch,
        "artifact_node": artifact_node,
        "version": version,
        "loader": VISUAL_REFERENCE_LOADER,
    }


def reference_identity(matrix_path: Path) -> dict[str, str]:
    """Derive the one protected visual anchor without introducing a version list."""

    try:
        matrix = load_matrix(matrix_path)
    except MatrixError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    return _reference_identity_from_matrix(matrix)


def reference_retention_days(matrix_path: Path) -> int:
    """Retain raw evidence only on the branch that owns the protected visual anchor."""

    try:
        matrix = load_matrix(matrix_path)
    except MatrixError as exc:
        raise VisualEvidenceError(str(exc)) from exc
    if matrix.get("unit_test_version") != VISUAL_REFERENCE_VERSION:
        return 1
    _reference_identity_from_matrix(matrix)
    return 90


def _read_reference_manifest(path: Path) -> dict[str, object]:
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or path.is_symlink()
            or metadata.st_size <= 0
            or metadata.st_size > MAX_REFERENCE_MANIFEST_BYTES
        ):
            raise ValueError("manifest is not a bounded regular file")
        payload = path.read_bytes()
        if len(payload) != metadata.st_size:
            raise ValueError("manifest changed while reading")
        parsed = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_reference_keys,
            parse_constant=_reject_nonfinite_reference_constant,
            parse_float=_parse_finite_reference_float,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise VisualEvidenceError(f"cannot read visual reference manifest: {exc}") from exc
    if not isinstance(parsed, dict):
        raise VisualEvidenceError("visual reference manifest must be an object")
    return parsed


def load_reference_frames(
    evidence_root: Path,
    catalog_path: Path,
    *,
    branch: str,
    artifact_node: str,
) -> dict[str, dict[str, object]]:
    """Load one already validated raw or compact Pages lane as the visual anchor."""

    try:
        root = evidence_root.resolve(strict=True)
        unresolved_bundle = root / branch
        if unresolved_bundle.is_symlink():
            raise OSError("reference bundle is a symbolic link")
        bundle = unresolved_bundle.resolve(strict=True)
    except OSError as exc:
        raise VisualEvidenceError(f"cannot resolve visual reference: {exc}") from exc
    if bundle.parent != root or not bundle.is_dir() or bundle.is_symlink():
        raise VisualEvidenceError("visual reference bundle escapes its evidence root")
    manifest = _read_reference_manifest(bundle / "manifest.json")
    catalog = load_catalog(catalog_path)
    schema_version = manifest.get("schema_version")
    if schema_version not in {1, 2}:
        raise VisualEvidenceError("visual reference must be raw or compact Pages evidence")
    if manifest.get("contract_sha256") != catalog.contract_sha256:
        raise VisualEvidenceError("visual reference uses a different scenario contract")
    release = manifest.get("release")
    if not isinstance(release, dict) or release.get("branch") != branch:
        raise VisualEvidenceError("visual reference release identity is invalid")
    artifacts = release.get("artifacts")
    matches = (
        [
            artifact
            for artifact in artifacts
            if isinstance(artifact, dict) and artifact.get("artifact_node") == artifact_node
        ]
        if isinstance(artifacts, list)
        else []
    )
    if len(matches) != 1:
        raise VisualEvidenceError("visual reference artifact is missing or duplicated")
    reference_version = matches[0].get("version")
    reference_loader = matches[0].get("loader")
    if (
        reference_version != VISUAL_REFERENCE_VERSION
        or reference_loader != VISUAL_REFERENCE_LOADER
    ):
        raise VisualEvidenceError("visual reference artifact metadata is invalid")

    raw_frames = manifest.get("frames")
    if not isinstance(raw_frames, list):
        raise VisualEvidenceError("visual reference frames must be an array")
    selected: dict[str, dict[str, object]] = {}
    for index, frame in enumerate(raw_frames):
        if not isinstance(frame, dict) or frame.get("artifact_node") != artifact_node:
            continue
        if (
            frame.get("version") != reference_version
            or frame.get("loader") != reference_loader
        ):
            raise VisualEvidenceError(
                f"visual reference frame {index} disagrees with its artifact"
            )
        capture_id = frame.get("capture_id")
        reference = catalog.by_id.get(capture_id) if isinstance(capture_id, str) else None
        if reference is None or any(
            frame.get(field) != reference[field]
            for field in (
                "scenario",
                "role",
                "step",
                "title",
                "expectation",
                "review_tier",
            )
        ):
            raise VisualEvidenceError(
                f"visual reference frame {index} disagrees with the scenario contract"
            )
        expected_label = (
            f"{artifact_node}/{reference['scenario']}/{reference['role']}/{reference['step']}"
        )
        if frame.get("frame_id") != expected_label or capture_id in selected:
            raise VisualEvidenceError(
                f"visual reference frame {index} has invalid or duplicate identity"
            )
        if schema_version == 1:
            asset = frame.get("asset")
            file_sha256 = frame.get("file_sha256")
            metrics = frame.get("pixel_validation")
            width = frame.get("width")
            height = frame.get("height")
            expected_asset = f"images/{file_sha256}.png"
            expected_format = "PNG"
        else:
            derivative = frame.get("derivative")
            if not isinstance(derivative, dict) or derivative.get("format") != "webp":
                raise VisualEvidenceError(
                    f"visual reference frame {index} has no WebP derivative"
                )
            asset = derivative.get("asset")
            file_sha256 = derivative.get("file_sha256")
            metrics = derivative.get("pixel_validation")
            width = derivative.get("width")
            height = derivative.get("height")
            expected_asset = f"images/{file_sha256}.webp"
            expected_format = "WEBP"
        if (
            not isinstance(asset, str)
            or not isinstance(file_sha256, str)
            or not SHA256.fullmatch(file_sha256)
            or asset != expected_asset
            or not isinstance(metrics, dict)
            or metrics.get("file_sha256") != file_sha256
            or not isinstance(metrics.get("pixel_sha256"), str)
            or not SHA256.fullmatch(metrics["pixel_sha256"])
            or isinstance(width, bool)
            or not isinstance(width, int)
            or isinstance(height, bool)
            or not isinstance(height, int)
            or (width, height) != catalog.contract.screenshot_size
            or metrics.get("width") != width
            or metrics.get("height") != height
        ):
            raise VisualEvidenceError(f"visual reference frame {index} derivative is invalid")
        unresolved_source = bundle / asset
        try:
            images = (bundle / "images").resolve(strict=True)
            if unresolved_source.is_symlink():
                raise OSError("reference asset is a symbolic link")
            source = unresolved_source.resolve(strict=True)
        except OSError as exc:
            raise VisualEvidenceError(
                f"cannot resolve visual reference frame {index}: {exc}"
            ) from exc
        if source.parent != images:
            raise VisualEvidenceError(f"visual reference frame {index} asset escapes its bundle")
        selected[capture_id] = {
            "path": str(source),
            "label": expected_label,
            "file_sha256": file_sha256,
            "pixel_sha256": metrics["pixel_sha256"],
            "width": width,
            "height": height,
            "format": expected_format,
            "version": reference_version,
            "loader": reference_loader,
        }
    if not selected:
        raise VisualEvidenceError("visual reference contains no frames for its anchor artifact")
    return selected


def _canonicalize_verified_reference(
    path: Path,
    *,
    expected_format: object,
    expected_file_sha256: object,
    expected_pixel_sha256: object,
    expected_dimensions: tuple[object, object],
) -> tuple[tuple[int, int], str, bytes]:
    """Revalidate one Pages image and return a metadata-free RGB PNG."""

    if (
        expected_format not in {"PNG", "WEBP"}
        or not isinstance(expected_file_sha256, str)
        or not SHA256.fullmatch(expected_file_sha256)
        or not isinstance(expected_pixel_sha256, str)
        or not SHA256.fullmatch(expected_pixel_sha256)
        or any(isinstance(value, bool) or not isinstance(value, int) for value in expected_dimensions)
    ):
        raise VisualEvidenceError("visual reference descriptor is invalid")
    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked decoder
        raise VisualEvidenceError("Pillow is required to curate visual references") from exc
    try:
        metadata = path.lstat()
        if (
            not stat.S_ISREG(metadata.st_mode)
            or path.is_symlink()
            or metadata.st_size <= 0
            or metadata.st_size > MAX_SINGLE_IMAGE_BYTES
        ):
            raise ValueError("reference image is not a bounded regular file")
        payload = path.read_bytes()
        if len(payload) != metadata.st_size:
            raise ValueError("reference image changed while reading")
        if hashlib.sha256(payload).hexdigest() != expected_file_sha256:
            raise ValueError("reference image digest changed")
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != expected_format or getattr(image, "n_frames", 1) != 1:
                raise ValueError("reference image format changed")
            image.load()
            rendered = image.convert("RGB")
            dimensions = rendered.size
            if dimensions != expected_dimensions:
                raise ValueError("reference image dimensions changed")
            if hashlib.sha256(rendered.tobytes()).hexdigest() != expected_pixel_sha256:
                raise ValueError("reference image pixels changed")
            output = io.BytesIO()
            rendered.save(output, format="PNG", optimize=False, compress_level=9)
    except VisualEvidenceError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise VisualEvidenceError(f"cannot canonicalize visual reference {path}: {exc}") from exc
    canonical = output.getvalue()
    return dimensions, hashlib.sha256(canonical).hexdigest(), canonical


def validate_expected_row(
    e2e_root: Path,
    catalog_path: Path,
    row: object,
) -> dict[str, object]:
    """Bind one artifact's complete evidence to one protected matrix row."""

    if not isinstance(row, dict):
        raise VisualEvidenceError("expected matrix row must be an object")
    row_id = row.get("id")
    artifact_node = row.get("artifact_node")
    runtime_version = row.get("runtime_version")
    loader = row.get("loader")
    raw_scenarios = row.get("scenarios")
    if any(
        not isinstance(value, str) or not SAFE_ID.fullmatch(value)
        for value in (row_id, artifact_node, runtime_version, loader)
    ) or loader not in {"fabric", "forge", "neoforge"}:
        raise VisualEvidenceError("expected matrix row has an invalid identity")
    if not isinstance(raw_scenarios, str):
        raise VisualEvidenceError("expected matrix row has no scenario coverage")
    scenarios = tuple(raw_scenarios.split(","))
    if (
        not scenarios
        or len(scenarios) != len(set(scenarios))
        or any(not SAFE_ID.fullmatch(scenario) for scenario in scenarios)
    ):
        raise VisualEvidenceError("expected matrix row has invalid scenario coverage")

    catalog = load_catalog(catalog_path)
    lanes, _frames, _comparisons = collect_evidence(e2e_root, catalog)
    observed = {
        (
            lane["artifact_node"],
            lane["version"],
            lane["loader"],
            lane["scenario"],
        )
        for lane in lanes
    }
    expected = {
        (artifact_node, runtime_version, loader, scenario) for scenario in scenarios
    }
    if observed != expected or len(lanes) != len(expected):
        raise VisualEvidenceError(
            f"artifact evidence disagrees with protected matrix row {row_id}: "
            f"missing={sorted(expected - observed)}, extra={sorted(observed - expected)}"
        )
    jar_digests = {lane["jar_sha256"] for lane in lanes}
    if len(jar_digests) != 1:
        raise VisualEvidenceError(
            f"artifact evidence uses multiple production JARs for matrix row {row_id}"
        )
    return {
        "schema_version": 1,
        "row_id": row_id,
        "artifact_node": artifact_node,
        "runtime_version": runtime_version,
        "loader": loader,
        "scenarios": list(scenarios),
        "lane_count": len(lanes),
        "jar_sha256": next(iter(jar_digests)),
    }


def curate_manifest(
    manifest: list[dict[str, object]], output_root: Path
) -> list[dict[str, object]]:
    """Atomically retain only reviewed PNGs and rewrite paths for a fresh runner."""

    if not manifest or len(manifest) > MAX_REVIEW_FRAMES:
        raise VisualEvidenceError(
            f"visual review frame count is outside 1..{MAX_REVIEW_FRAMES}"
        )
    destination = output_root.absolute()
    if not SAFE_DIRECTORY.fullmatch(destination.name):
        raise VisualEvidenceError("curated review output must have a portable directory name")
    if destination.exists() or destination.is_symlink():
        raise VisualEvidenceError(
            f"curated review output must not already exist: {destination}"
        )
    try:
        parent = destination.parent.resolve(strict=True)
        staging = Path(
            tempfile.mkdtemp(
                prefix=f".{destination.name}.curating-",
                dir=parent,
            )
        )
    except OSError as exc:
        raise VisualEvidenceError(f"cannot create curated review staging: {exc}") from exc

    curated: list[dict[str, object]] = []
    total_bytes = 0
    total_pixels = 0
    copied: dict[str, Path] = {}
    reference_snapshots: dict[str, tuple[tuple[int, int], str, bytes]] = {}
    try:
        images = staging / "images"
        images.mkdir(mode=0o700)

        def retain(
            digest: str,
            payload: bytes,
            source: Path,
        ) -> Path:
            nonlocal total_bytes
            asset = copied.get(digest)
            if asset is not None:
                return asset
            if len(copied) >= MAX_REVIEW_FRAMES:
                raise VisualEvidenceError(
                    f"curated visual review exceeds {MAX_REVIEW_FRAMES} distinct images"
                )

            total_bytes += len(payload)
            if total_bytes > MAX_REVIEW_IMAGE_BYTES:
                raise VisualEvidenceError(
                    "curated visual review exceeds its total image byte limit"
                )
            asset = images / f"{digest}.png"
            with asset.open("xb") as output_stream:
                output_stream.write(payload)
                output_stream.flush()
                os.fsync(output_stream.fileno())
            if asset.stat().st_size != len(payload):
                raise VisualEvidenceError(
                    f"review frame changed while curating: {source}"
                )
            os.chmod(asset, 0o644)
            copied[digest] = asset
            return asset

        def account_pixels(dimensions: tuple[int, int]) -> None:
            nonlocal total_pixels
            total_pixels += dimensions[0] * dimensions[1]
            if total_pixels > MAX_REVIEW_IMAGE_PIXELS:
                raise VisualEvidenceError(
                    "curated visual review exceeds its total pixel limit"
                )

        for index, item in enumerate(manifest):
            source_value = item.get("path")
            if not isinstance(source_value, str):
                raise VisualEvidenceError(f"review frame {index} has no source path")
            source = Path(source_value)
            (
                dimensions,
                source_digest,
                pixel_digest,
                digest,
                payload,
            ) = canonicalize_png_snapshot(source)
            expected_dimensions = (
                item.get("_verified_width"),
                item.get("_verified_height"),
            )
            expected_size = item.get("_expected_size")
            if (
                source_digest != item.get("_verified_file_sha256")
                or pixel_digest != item.get("_verified_pixel_sha256")
                or dimensions != expected_dimensions
                or not isinstance(expected_size, (list, tuple))
                or len(expected_size) != 2
                or any(
                    isinstance(value, bool) or not isinstance(value, int)
                    for value in expected_size
                )
                or dimensions != tuple(expected_size)
            ):
                raise VisualEvidenceError(
                    f"review frame changed after evidence validation: {source}"
                )

            reference_asset: Path | None = None
            reference_payload: bytes | None = None
            reference_value = item.get("reference_path")
            if reference_value is not None:
                if not isinstance(reference_value, str):
                    raise VisualEvidenceError(
                        f"review frame {index} has an invalid reference path"
                    )
                reference = Path(reference_value)
                reference_key = str(item.get("_reference_verified_file_sha256"))
                reference_snapshot = reference_snapshots.get(reference_key)
                if reference_snapshot is None:
                    reference_snapshot = _canonicalize_verified_reference(
                        reference,
                        expected_format=item.get("_reference_verified_format"),
                        expected_file_sha256=item.get(
                            "_reference_verified_file_sha256"
                        ),
                        expected_pixel_sha256=item.get(
                            "_reference_verified_pixel_sha256"
                        ),
                        expected_dimensions=(
                            item.get("_reference_verified_width"),
                            item.get("_reference_verified_height"),
                        ),
                    )
                    reference_snapshots[reference_key] = reference_snapshot
                reference_dimensions, reference_digest, reference_payload = (
                    reference_snapshot
                )
                if reference_dimensions != dimensions:
                    raise VisualEvidenceError(
                        "candidate and reference screenshots must both remain exactly "
                        f"{dimensions[0]}x{dimensions[1]}"
                    )
                account_pixels(reference_dimensions)
                reference_asset = retain(
                    reference_digest,
                    reference_payload,
                    reference,
                )

            account_pixels(dimensions)
            asset = retain(digest, payload, source)
            try:
                similarity = analyze_png_payloads(
                    payload,
                    reference_payload,
                    item.get("_review_regions"),
                    dimensions,
                )
            except SimilarityError as exc:
                raise VisualEvidenceError(
                    f"cannot analyze semantic regions for review frame {index}: {exc}"
                ) from exc
            rewritten = public_manifest([{**item, **similarity}])[0]
            rewritten["path"] = f"{destination.name}/images/{digest}.png"
            if reference_asset is not None:
                rewritten["reference_path"] = (
                    f"{destination.name}/images/{reference_asset.name}"
                )
            curated.append(rewritten)

        manifest_path = staging / "visual-review-manifest.json"
        with manifest_path.open("x", encoding="utf-8") as handle:
            json.dump(curated, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(manifest_path, 0o644)
        os.replace(staging, destination)
    except (OSError, VisualEvidenceError) as exc:
        shutil.rmtree(staging, ignore_errors=True)
        if isinstance(exc, VisualEvidenceError):
            raise
        raise VisualEvidenceError(f"cannot curate visual review: {exc}") from exc
    return curated


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--all", action="store_true", help="include every catalogued capture")
    parser.add_argument(
        "--semantic-anchor",
        action="store_true",
        help="require unpaired complete Fabric/Forge 1.20.1 semantic coverage",
    )
    parser.add_argument(
        "--combos",
        help="comma-separated <version>/<loader> filter (for example 1.20.1/fabric)",
    )
    parser.add_argument("--e2e-root", type=Path, default=REPO / "e2e-out")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument(
        "--matrix",
        type=Path,
        default=REPO / "release" / "release-matrix.json",
    )
    parser.add_argument(
        "--reference-identity",
        action="store_true",
        help="print the visual anchor derived from the protected release matrix",
    )
    parser.add_argument(
        "--reference-retention-days",
        action="store_true",
        help="print the raw-evidence retention for this branch matrix",
    )
    parser.add_argument(
        "--reference-evidence-root",
        type=Path,
        help="already validated raw or compact Pages evidence containing the 1.20.1 anchor",
    )
    parser.add_argument("--reference-branch")
    parser.add_argument("--reference-artifact-node")
    parser.add_argument(
        "--curate-output",
        type=Path,
        help="atomically copy only selected frames into this fresh directory",
    )
    parser.add_argument(
        "--validate-row-json",
        help="validate this artifact against one protected matrix row and exit",
    )
    args = parser.parse_args(argv)
    try:
        reference_arguments = (
            args.reference_evidence_root,
            args.reference_branch,
            args.reference_artifact_node,
        )
        has_reference = any(value is not None for value in reference_arguments)
        if has_reference and not all(value is not None for value in reference_arguments):
            raise VisualEvidenceError(
                "paired review requires --reference-evidence-root, --reference-branch, "
                "and --reference-artifact-node together"
            )
        if has_reference and not args.all:
            raise VisualEvidenceError(
                "paired cross-version review must use --all to cover every capture"
            )
        if args.semantic_anchor and (
            not args.all or has_reference or args.combos is not None
        ):
            raise VisualEvidenceError(
                "--semantic-anchor requires --all and cannot use a combo or reference"
            )
        if args.reference_identity:
            if (
                args.reference_retention_days
                or args.validate_row_json is not None
                or args.curate_output is not None
                or args.all
                or args.combos is not None
                or args.semantic_anchor
                or has_reference
            ):
                raise VisualEvidenceError(
                    "--reference-identity cannot be combined with evidence selection"
                )
            print(
                json.dumps(
                    reference_identity(args.matrix),
                    sort_keys=True,
                    separators=(",", ":"),
                )
            )
            return 0
        if args.reference_retention_days:
            if (
                args.validate_row_json is not None
                or args.curate_output is not None
                or args.all
                or args.combos is not None
                or args.semantic_anchor
                or has_reference
            ):
                raise VisualEvidenceError(
                    "--reference-retention-days cannot be combined with evidence selection"
                )
            print(reference_retention_days(args.matrix))
            return 0
        if args.validate_row_json is not None:
            if (
                args.curate_output is not None
                or args.all
                or args.combos is not None
                or args.semantic_anchor
                or has_reference
            ):
                raise VisualEvidenceError(
                    "--validate-row-json cannot be combined with manifest selection"
                )
            try:
                row = json.loads(args.validate_row_json)
            except json.JSONDecodeError as exc:
                raise VisualEvidenceError(f"invalid expected matrix row JSON: {exc}") from exc
            validated = validate_expected_row(args.e2e_root, args.catalog, row)
            print(json.dumps(validated, sort_keys=True, separators=(",", ":")))
            return 0
        reference_frames = None
        if has_reference:
            anchor = reference_identity(args.matrix)
            if (
                args.reference_branch != anchor["release_branch"]
                or args.reference_artifact_node != anchor["artifact_node"]
            ):
                raise VisualEvidenceError(
                    "paired review reference disagrees with protected master identity"
                )
            reference_frames = load_reference_frames(
                args.reference_evidence_root,
                args.catalog,
                branch=args.reference_branch,
                artifact_node=args.reference_artifact_node,
            )
        manifest = build_manifest(
            args.e2e_root,
            args.catalog,
            include_all=args.all,
            combos=parse_combos(args.combos),
            reference_frames=reference_frames,
        )
        if args.semantic_anchor:
            manifest = validate_semantic_anchor_manifest(manifest)
        if args.curate_output is not None:
            manifest = curate_manifest(manifest, args.curate_output)
        else:
            raise VisualEvidenceError(
                "review manifests must use --curate-output so 1920x1080 semantic "
                "fingerprints are validated before model admission"
            )
    except VisualEvidenceError as exc:
        parser.error(str(exc))
    json.dump(manifest, sys.stdout, indent=2, ensure_ascii=False)
    print()
    print(f"\n# {len(manifest)} screenshots", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
