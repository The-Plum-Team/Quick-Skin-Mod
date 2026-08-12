from __future__ import annotations

import sys
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from check_visual_review import ReviewError, triage_schema, validate_triage  # noqa: E402
from visual_review_runner import (  # noqa: E402
    SYNTHETIC_COMPARISON_CLEAN_VISIBLE,
    SYNTHETIC_IDENTICAL_VISIBLE,
    SYNTHETIC_SEMANTIC_CLEAN_VISIBLE,
    build_review_plan,
    execute_review,
)


def paired(
    label: str,
    candidate: str,
    reference: str,
    *,
    reference_artifact: str = "fabric-1.20.1",
) -> dict[str, str]:
    _artifact, scenario, role, step = label.split("/")
    return {
        "path": candidate,
        "reference_path": reference,
        "reference_label": f"{reference_artifact}/{scenario}/{role}/{step}",
        "label": label,
        "capture_id": f"{scenario}.{role}.{step}",
        "kind": f"{scenario}.{role}.{step}",
        "expectation": f"Expected {step}",
    }


def unpaired(label: str, candidate: str) -> dict[str, str]:
    _artifact, scenario, role, step = label.split("/")
    return {
        "path": candidate,
        "label": label,
        "capture_id": f"{scenario}.{role}.{step}",
        "kind": f"{scenario}.{role}.{step}",
        "expectation": f"Expected {step}",
    }


class VisualReviewRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = [
            paired(
                "fabric-1.20.1/full/client_a/identical",
                "review-input/images/a.png",
                "review-input/images/a.png",
            ),
            paired(
                "fabric-1.21.1/full/client_a/clean",
                "review-input/images/b.png",
                "review-input/images/a.png",
            ),
            paired(
                "neoforge-1.21.1/full/client_a/blurred",
                "review-input/images/c.png",
                "review-input/images/a.png",
            ),
        ]

    def test_plan_skips_only_byte_identical_pairs_and_bounds_chunks(self) -> None:
        plan = build_review_plan(self.manifest, triage_chunk_size=1)

        self.assertEqual([self.manifest[0]], plan["identical"])
        self.assertEqual(self.manifest[1:], plan["semantic"])
        self.assertEqual([[self.manifest[1]], [self.manifest[2]]], plan["triage_chunks"])
        with self.assertRaises(ReviewError):
            build_review_plan(self.manifest, triage_chunk_size=9)

    def test_unpaired_anchor_always_receives_semantic_review(self) -> None:
        anchor = unpaired(
            "fabric-1.20.1/full/client_a/anchor",
            "review-input/images/a.png",
        )
        calls: list[list[str]] = []

        def provider(
            stage: str,
            _chunk_index: int,
            chunk: list[dict[str, Any]],
            _schema: dict[str, Any],
        ) -> list[dict[str, Any]]:
            self.assertEqual("triage", stage)
            calls.append([item["label"] for item in chunk])
            return [
                {
                    "label": anchor["label"],
                    "decision": "clean",
                    "confidence": "high",
                    "anomalies": [],
                }
            ]

        plan = build_review_plan([anchor])
        verdicts, stats = execute_review([anchor], provider)

        self.assertEqual([], plan["identical"])
        self.assertEqual([anchor], plan["semantic"])
        self.assertEqual([[anchor["label"]]], calls)
        self.assertEqual(SYNTHETIC_SEMANTIC_CLEAN_VISIBLE, verdicts[0]["visible"])
        self.assertTrue(verdicts[0]["semantic_valid"])
        self.assertIsNone(verdicts[0]["matches_reference"])
        self.assertEqual(0, stats["identical"])
        self.assertEqual(1, stats["triaged"])

    def test_two_stage_review_escalates_only_non_high_clean_results(self) -> None:
        calls: list[tuple[str, list[str]]] = []

        def provider(
            stage: str,
            _chunk_index: int,
            chunk: list[dict[str, Any]],
            _schema: dict[str, Any],
        ) -> list[dict[str, Any]]:
            labels = [item["label"] for item in chunk]
            calls.append((stage, labels))
            if stage == "triage":
                return [
                    {
                        "label": labels[0],
                        "decision": "clean",
                        "confidence": "high",
                        "anomalies": [],
                    },
                    {
                        "label": labels[1],
                        "decision": "needs_review",
                        "confidence": "high",
                        "anomalies": ["Custom panel is softer than the reference."],
                    },
                ]
            self.assertEqual("needs_review", chunk[0]["first_review"]["decision"])
            return [
                {
                    "label": labels[0],
                    "visible": "The candidate panel is visibly blurred.",
                    "semantic_valid": False,
                    "matches_reference": False,
                    "anomalies": ["Custom panel is softer than the reference."],
                    "defect": True,
                }
            ]

        verdicts, stats = execute_review(self.manifest, provider)

        self.assertEqual(
            [item["label"] for item in self.manifest],
            [item["label"] for item in verdicts],
        )
        self.assertEqual(SYNTHETIC_IDENTICAL_VISIBLE, verdicts[0]["visible"])
        self.assertEqual(SYNTHETIC_COMPARISON_CLEAN_VISIBLE, verdicts[1]["visible"])
        self.assertTrue(verdicts[2]["defect"])
        self.assertEqual(
            [
                ("triage", [self.manifest[1]["label"], self.manifest[2]["label"]]),
                ("verify", [self.manifest[2]["label"]]),
            ],
            calls,
        )
        self.assertEqual(
            {
                "frames": 3,
                "paired": 1,
                "identical": 1,
                "triaged": 2,
                "triage_chunks": 1,
                "escalated": 1,
                "verify_chunks": 1,
            },
            stats,
        )

    def test_triage_schema_and_validator_keep_provider_constraints_small(self) -> None:
        labels = [self.manifest[1]["label"], self.manifest[2]["label"]]
        schema = triage_schema(labels)
        item = schema["properties"]["reviews"]["items"]

        self.assertEqual(labels, item["properties"]["label"]["enum"])
        self.assertNotIn("minItems", schema["properties"]["reviews"])
        with self.assertRaisesRegex(ReviewError, "cannot describe an anomaly"):
            validate_triage(
                self.manifest[1:2],
                [
                    {
                        "label": labels[0],
                        "decision": "clean",
                        "confidence": "high",
                        "anomalies": ["contradiction"],
                    }
                ],
                require_paired=True,
            )


if __name__ == "__main__":
    unittest.main()
