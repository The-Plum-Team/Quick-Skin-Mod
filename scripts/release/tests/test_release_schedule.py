from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/release"))

from matrix import MatrixError, load_matrix
from release_schedule import PREPARATION_SLOTS, preparation_slot


class ReleaseScheduleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix = load_matrix(ROOT / "release/release-matrix.json")

    def test_all_targets_share_a_bounded_set_of_isolated_slots(self) -> None:
        targets = {row["artifact_version"] for row in self.matrix["artifacts"]}
        slots = [preparation_slot(self.matrix, target) for target in targets]
        self.assertEqual(set(slots), set(range(PREPARATION_SLOTS)))
        counts = [slots.count(slot) for slot in range(PREPARATION_SLOTS)]
        self.assertLessEqual(max(counts) - min(counts), 1)
        reordered = {**self.matrix, "artifacts": list(reversed(self.matrix["artifacts"]))}
        for target in targets:
            self.assertEqual(preparation_slot(self.matrix, target), preparation_slot(reordered, target))

    def test_unknown_targets_cannot_enter_a_preparation_queue(self) -> None:
        for target in ("", "unknown", "../26.1", "26.1\nslot=99"):
            with self.subTest(target=target), self.assertRaises(MatrixError):
                preparation_slot(self.matrix, target)

    def test_historical_layout_cannot_silently_adopt_shared_parallelism(self) -> None:
        with self.assertRaisesRegex(MatrixError, "shared-source"):
            preparation_slot({**self.matrix, "schema_version": 2}, "26.1")


if __name__ == "__main__":
    unittest.main()
