from __future__ import annotations

import io
import sys
import unittest
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from visual_similarity import (  # noqa: E402
    SimilarityError,
    analyze_png_payloads,
    normalize_regions,
)


SIZE = (1920, 1080)
REGIONS = [[0.25, 0.25, 0.75, 0.75]]


def png(image: Image.Image, *, compress_level: int = 9) -> bytes:
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=False, compress_level=compress_level)
    return output.getvalue()


class VisualSimilarityTest(unittest.TestCase):
    def test_exact_identity_uses_decoded_rgb_not_png_encoding(self) -> None:
        image = Image.new("RGB", SIZE, (12, 34, 56))

        analysis = analyze_png_payloads(
            png(image, compress_level=0),
            png(image, compress_level=9),
            REGIONS,
            SIZE,
        )

        self.assertEqual(
            analysis["candidate_semantic_sha256"],
            analysis["reference_semantic_sha256"],
        )
        self.assertEqual(0.0, analysis["semantic_changed_fraction"])
        self.assertEqual(0.0, analysis["perceptual_delta"])

    def test_only_authored_regions_drive_exact_reuse(self) -> None:
        candidate = Image.new("RGB", SIZE, (12, 34, 56))
        outside = candidate.copy()
        outside.putpixel((20, 20), (255, 0, 0))
        inside = candidate.copy()
        inside.putpixel((960, 540), (255, 0, 0))

        ignored = analyze_png_payloads(
            png(candidate), png(outside), REGIONS, SIZE
        )
        detected = analyze_png_payloads(
            png(candidate), png(inside), REGIONS, SIZE
        )

        self.assertEqual(
            ignored["candidate_semantic_sha256"],
            ignored["reference_semantic_sha256"],
        )
        self.assertNotEqual(
            detected["candidate_semantic_sha256"],
            detected["reference_semantic_sha256"],
        )
        self.assertGreater(detected["semantic_changed_fraction"], 0.0)

    def test_perceptual_similarity_is_bounded_but_never_exact(self) -> None:
        candidate = Image.new("RGB", SIZE, (40, 40, 40))
        reference = Image.new("RGB", SIZE, (41, 41, 41))

        analysis = analyze_png_payloads(
            png(candidate), png(reference), REGIONS, SIZE
        )

        self.assertNotEqual(
            analysis["candidate_semantic_sha256"],
            analysis["reference_semantic_sha256"],
        )
        self.assertEqual(0.0, analysis["semantic_changed_fraction"])
        self.assertGreater(analysis["perceptual_delta"], 0.0)
        self.assertLessEqual(analysis["perceptual_delta"], 1.0)

    def test_size_and_region_contract_fail_closed(self) -> None:
        payload = png(Image.new("RGB", (1280, 720), (0, 0, 0)))
        with self.assertRaisesRegex(SimilarityError, "exactly 1920x1080"):
            analyze_png_payloads(payload, None, REGIONS, SIZE)
        with self.assertRaisesRegex(SimilarityError, "non-empty rectangle"):
            normalize_regions([[0.5, 0.5, 0.5, 0.7]])


if __name__ == "__main__":
    unittest.main()
