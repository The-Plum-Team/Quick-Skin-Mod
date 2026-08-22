#!/usr/bin/env python3
"""Deterministic semantic-region fingerprints and perceptual routing metrics."""

from __future__ import annotations

import hashlib
import io
import json
import math
from pathlib import Path
from typing import Any


MAX_REGIONS = 8
PERCEPTUAL_SAMPLE_SIZE = (64, 64)


class SimilarityError(ValueError):
    """Raised when an image or authored semantic region is invalid."""


def normalize_regions(value: Any) -> tuple[tuple[float, float, float, float], ...]:
    """Validate a bounded ordered list of normalized non-empty rectangles."""

    if not isinstance(value, (list, tuple)) or not 1 <= len(value) <= MAX_REGIONS:
        raise SimilarityError(f"review_regions must contain 1..{MAX_REGIONS} rectangles")
    normalized: list[tuple[float, float, float, float]] = []
    for region_index, raw_region in enumerate(value):
        if not isinstance(raw_region, (list, tuple)) or len(raw_region) != 4:
            raise SimilarityError(
                f"review_regions[{region_index}] must contain four coordinates"
            )
        region: list[float] = []
        for coordinate_index, coordinate in enumerate(raw_region):
            if (
                isinstance(coordinate, bool)
                or not isinstance(coordinate, (int, float))
                or not math.isfinite(coordinate)
                or not 0.0 <= coordinate <= 1.0
            ):
                raise SimilarityError(
                    f"review_regions[{region_index}][{coordinate_index}] is invalid"
                )
            region.append(float(coordinate))
        normalized_region = tuple(region)
        if normalized_region[0] >= normalized_region[2] or normalized_region[1] >= normalized_region[3]:
            raise SimilarityError(
                f"review_regions[{region_index}] must be a non-empty rectangle"
            )
        normalized.append(normalized_region)  # type: ignore[arg-type]
    if len(set(normalized)) != len(normalized):
        raise SimilarityError("review_regions contains duplicate rectangles")
    return tuple(normalized)


def _pixel_boxes(
    regions: tuple[tuple[float, float, float, float], ...],
    size: tuple[int, int],
) -> tuple[tuple[int, int, int, int], ...]:
    width, height = size
    boxes = tuple(
        (
            int(region[0] * width),
            int(region[1] * height),
            int(region[2] * width),
            int(region[3] * height),
        )
        for region in regions
    )
    if any(left >= right or top >= bottom for left, top, right, bottom in boxes):
        raise SimilarityError(f"review region is empty at {width}x{height}")
    return boxes


def _decode_png(payload: bytes, expected_size: tuple[int, int], label: str) -> Any:
    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - locked E2E requirements install Pillow
        raise SimilarityError("Pillow is required for semantic image analysis") from exc
    try:
        with Image.open(io.BytesIO(payload)) as image:
            if image.format != "PNG" or getattr(image, "n_frames", 1) != 1:
                raise SimilarityError(f"{label} must be a static PNG")
            image.load()
            rgb = image.convert("RGB")
    except SimilarityError:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise SimilarityError(f"cannot decode {label}: {exc}") from exc
    if rgb.size != expected_size:
        raise SimilarityError(
            f"{label} must be exactly {expected_size[0]}x{expected_size[1]}, got {rgb.size}"
        )
    return rgb


def _semantic_fingerprint(
    image: Any,
    regions: tuple[tuple[float, float, float, float], ...],
    boxes: tuple[tuple[int, int, int, int], ...],
) -> str:
    digest = hashlib.sha256()
    digest.update(b"quick-skin-semantic-regions-v1\0")
    digest.update(
        json.dumps(regions, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    )
    for box in boxes:
        crop = image.crop(box)
        digest.update(crop.width.to_bytes(4, "big"))
        digest.update(crop.height.to_bytes(4, "big"))
        digest.update(crop.tobytes())
    return digest.hexdigest()


def analyze_png_payloads(
    candidate_payload: bytes,
    reference_payload: bytes | None,
    review_regions: Any,
    expected_size: tuple[int, int],
) -> dict[str, Any]:
    """Return exact relevant-pixel identities plus non-authoritative pair similarity.

    Exact semantic digests may drive reuse. The perceptual values are deliberately suitable only
    for model routing: a non-zero difference can never synthesize a passing verdict.
    """

    try:
        from PIL import Image, ImageChops
    except ImportError as exc:  # pragma: no cover - locked E2E requirements install Pillow
        raise SimilarityError("Pillow is required for semantic image analysis") from exc

    regions = normalize_regions(review_regions)
    candidate = _decode_png(candidate_payload, expected_size, "candidate image")
    boxes = _pixel_boxes(regions, expected_size)
    candidate_digest = _semantic_fingerprint(candidate, regions, boxes)
    result: dict[str, Any] = {
        "image_size": [expected_size[0], expected_size[1]],
        "review_regions": [list(region) for region in regions],
        "candidate_semantic_sha256": candidate_digest,
    }
    if reference_payload is None:
        return result

    reference = _decode_png(reference_payload, expected_size, "reference image")
    reference_digest = _semantic_fingerprint(reference, regions, boxes)
    changed_pixels = 0
    total_pixels = 0
    perceptual_sum = 0.0
    perceptual_weight = 0
    for box in boxes:
        candidate_crop = candidate.crop(box)
        reference_crop = reference.crop(box)
        difference = ImageChops.difference(candidate_crop, reference_crop)
        channel_masks = [
            channel.point(lambda value: 255 if value > 8 else 0)
            for channel in difference.split()
        ]
        changed_mask = ImageChops.lighter(
            ImageChops.lighter(channel_masks[0], channel_masks[1]),
            channel_masks[2],
        )
        histogram = changed_mask.histogram()
        pixels = candidate_crop.width * candidate_crop.height
        changed_pixels += histogram[255]
        total_pixels += pixels

        candidate_sample = candidate_crop.resize(
            PERCEPTUAL_SAMPLE_SIZE, Image.Resampling.BILINEAR
        ).convert("L")
        reference_sample = reference_crop.resize(
            PERCEPTUAL_SAMPLE_SIZE, Image.Resampling.BILINEAR
        ).convert("L")
        sample_histogram = ImageChops.difference(
            candidate_sample, reference_sample
        ).histogram()
        sample_pixels = PERCEPTUAL_SAMPLE_SIZE[0] * PERCEPTUAL_SAMPLE_SIZE[1]
        perceptual_sum += sum(
            value * count for value, count in enumerate(sample_histogram)
        ) / 255.0
        perceptual_weight += sample_pixels

    result.update(
        {
            "reference_semantic_sha256": reference_digest,
            "semantic_changed_fraction": round(changed_pixels / total_pixels, 8),
            "perceptual_delta": round(perceptual_sum / perceptual_weight, 8),
        }
    )
    return result


def analyze_png_paths(
    candidate: Path,
    reference: Path | None,
    review_regions: Any,
    expected_size: tuple[int, int],
) -> dict[str, Any]:
    """Path convenience wrapper used only after callers validate their file boundaries."""

    try:
        candidate_payload = candidate.read_bytes()
        reference_payload = reference.read_bytes() if reference is not None else None
    except OSError as exc:
        raise SimilarityError(f"cannot read semantic image input: {exc}") from exc
    return analyze_png_payloads(
        candidate_payload,
        reference_payload,
        review_regions,
        expected_size,
    )
