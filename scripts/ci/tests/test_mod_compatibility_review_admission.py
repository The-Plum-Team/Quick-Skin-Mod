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
    def test_all_ten_lanes_remain_present_with_one_nested_call_each(self) -> None:
        result = admit(matrix(10))

        self.assertEqual(10, len(result["include"]))
        self.assertEqual(
            [f"lane-{index}" for index in range(10)],
            [lane["id"] for lane in result["include"]],
        )
        self.assertEqual(
            {1}, {lane["model_parallelism"] for lane in result["include"]}
        )
        self.assertEqual(
            list(range(0, 20, 2)),
            [lane["model_start_delay_seconds"] for lane in result["include"]],
        )

    def test_smaller_wave_uses_the_available_nested_budget(self) -> None:
        result = admit(matrix(3))

        self.assertEqual(
            {4}, {lane["model_parallelism"] for lane in result["include"]}
        )
        self.assertEqual(
            [0, 2, 4],
            [lane["model_start_delay_seconds"] for lane in result["include"]],
        )

    def test_ramp_is_bounded(self) -> None:
        result = admit(matrix(3), ramp_seconds=30)

        self.assertEqual(30, result["include"][-1]["model_start_delay_seconds"])

    def test_rejects_a_wave_larger_than_the_concurrent_budget(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(13))

    def test_rejects_empty_or_preannotated_matrices(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(0))
        with self.assertRaises(AdmissionError):
            admit({"include": [{"id": "lane", "model_parallelism": 1}]})

    def test_rejects_invalid_limits(self) -> None:
        with self.assertRaises(AdmissionError):
            admit(matrix(1), call_budget=33)
        with self.assertRaises(AdmissionError):
            admit(matrix(1), ramp_seconds=-1)


if __name__ == "__main__":
    unittest.main()
