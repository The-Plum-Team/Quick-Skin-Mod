from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from mod_compatibility_review_queue import AdmissionError, admit  # noqa: E402


def matrix(count: int) -> dict[str, list[dict[str, str]]]:
    return {"include": [{"id": f"lane-{index}"} for index in range(count)]}


class ModCompatibilityReviewAdmissionTest(unittest.TestCase):
    def test_all_lanes_share_one_source_wide_call_budget(self) -> None:
        result = admit(matrix(10))

        self.assertEqual(10, len(result["include"]))
        self.assertEqual(
            [f"lane-{index}" for index in range(10)],
            [lane["id"] for lane in result["include"]],
        )
        self.assertEqual(12, result["model_parallelism"])
        self.assertEqual(2, result["model_call_spacing_seconds"])
        self.assertFalse(any("model_parallelism" in lane for lane in result["include"]))

    def test_custom_source_wide_limits_are_preserved(self) -> None:
        result = admit(matrix(3), call_budget=7, call_spacing_seconds=5)

        self.assertEqual(7, result["model_parallelism"])
        self.assertEqual(5, result["model_call_spacing_seconds"])

    def test_call_spacing_is_bounded(self) -> None:
        result = admit(matrix(3), call_spacing_seconds=30)

        self.assertEqual(30, result["model_call_spacing_seconds"])

    def test_lane_count_no_longer_consumes_the_call_budget(self) -> None:
        self.assertEqual(13, len(admit(matrix(13))["include"]))

    def test_rejects_a_wave_larger_than_the_batch_bound(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(65))

    def test_rejects_empty_or_preannotated_matrices(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(0))
        with self.assertRaises(AdmissionError):
            admit({"include": [{"id": "lane", "model_parallelism": 1}]})

    def test_rejects_invalid_limits(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(1), call_budget=33)
        with self.assertRaises(AdmissionError):
            admit(matrix(1), call_spacing_seconds=-1)


if __name__ == "__main__":
    unittest.main()
