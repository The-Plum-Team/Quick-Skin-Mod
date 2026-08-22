from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from check_visual_review import (  # noqa: E402
    ReviewError,
    render,
    triage_schema,
    validate_blocking_partial,
    validate_triage,
)
from visual_review_runner import (  # noqa: E402
    SYNTHETIC_COMPARISON_CLEAN_VISIBLE,
    SYNTHETIC_IDENTICAL_VISIBLE,
    SYNTHETIC_REPRESENTED_VISIBLE,
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
) -> dict[str, Any]:
    _artifact, scenario, role, step = label.split("/")
    candidate_semantic = hashlib.sha256(candidate.encode()).hexdigest()
    reference_semantic = hashlib.sha256(reference.encode()).hexdigest()
    exact = candidate_semantic == reference_semantic
    return {
        "path": candidate,
        "reference_path": reference,
        "reference_label": f"{reference_artifact}/{scenario}/{role}/{step}",
        "label": label,
        "capture_id": f"{scenario}.{role}.{step}",
        "kind": f"{scenario}.{role}.{step}",
        "expectation": f"Expected {step}",
        "runtime_evidence": f"assertion passed for {step}",
        "image_size": [1920, 1080],
        "review_regions": [[0.25, 0.25, 0.75, 0.75]],
        "candidate_semantic_sha256": candidate_semantic,
        "reference_semantic_sha256": reference_semantic,
        "semantic_changed_fraction": 0.0 if exact else 0.25,
        "perceptual_delta": 0.0 if exact else 0.25,
    }


def unpaired(label: str, candidate: str) -> dict[str, Any]:
    _artifact, scenario, role, step = label.split("/")
    return {
        "path": candidate,
        "label": label,
        "capture_id": f"{scenario}.{role}.{step}",
        "kind": f"{scenario}.{role}.{step}",
        "expectation": f"Expected {step}",
        "runtime_evidence": f"assertion passed for {step}",
        "image_size": [1920, 1080],
        "review_regions": [[0.25, 0.25, 0.75, 0.75]],
        "candidate_semantic_sha256": hashlib.sha256(candidate.encode()).hexdigest(),
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

    def test_minimal_protected_reviewer_bundle_starts_without_contract_loader(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            bundle = Path(temporary)
            for name in (
                "check_visual_review.py",
                "visual_similarity.py",
                "visual_review_cache.py",
                "visual_review_runner.py",
            ):
                shutil.copy2(ROOT / "e2e" / name, bundle / name)

            completed = subprocess.run(
                [sys.executable, str(bundle / "visual_review_runner.py"), "--help"],
                cwd=bundle,
                check=False,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )

        self.assertEqual(0, completed.returncode, completed.stderr)

    def test_plan_skips_only_exact_semantic_regions_and_bounds_chunks(self) -> None:
        plan = build_review_plan(self.manifest, triage_chunk_size=1)

        self.assertEqual([self.manifest[0]], plan["identical"])
        self.assertEqual(self.manifest[1:], plan["semantic"])
        self.assertEqual([[self.manifest[1]], [self.manifest[2]]], plan["triage_chunks"])
        with self.assertRaises(ReviewError):
            build_review_plan(self.manifest, triage_chunk_size=9)

    def test_exact_regions_skip_ai_even_when_png_files_differ(self) -> None:
        exact = {
            **self.manifest[1],
            "reference_semantic_sha256": self.manifest[1][
                "candidate_semantic_sha256"
            ],
            "semantic_changed_fraction": 0.0,
            "perceptual_delta": 0.0,
        }

        plan = build_review_plan([exact])

        self.assertNotEqual(exact["path"], exact["reference_path"])
        self.assertEqual([exact], plan["identical"])
        self.assertEqual([], plan["semantic"])

    def test_exact_equivalent_versions_share_one_ai_representative(self) -> None:
        representative = self.manifest[1]
        follower = {
            **paired(
                "neoforge-1.21.1/full/client_a/clean",
                "review-input/images/d.png",
                "review-input/images/e.png",
            ),
            "candidate_semantic_sha256": representative[
                "candidate_semantic_sha256"
            ],
            "reference_semantic_sha256": representative[
                "reference_semantic_sha256"
            ],
            "semantic_changed_fraction": representative[
                "semantic_changed_fraction"
            ],
            "perceptual_delta": representative["perceptual_delta"],
        }
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
                    "label": representative["label"],
                    "decision": "clean",
                    "confidence": "high",
                    "anomalies": [],
                }
            ]

        verdicts, stats = execute_review([representative, follower], provider)

        self.assertEqual([[representative["label"]]], calls)
        self.assertEqual(1, stats["triaged"])
        self.assertEqual(1, stats["represented"])
        self.assertEqual(SYNTHETIC_REPRESENTED_VISIBLE, verdicts[1]["visible"])
        self.assertFalse(any(verdict["defect"] for verdict in verdicts))

    def test_compatibility_mode_reviews_even_byte_identical_pairs(self) -> None:
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
                    "label": item["label"],
                    "decision": "clean",
                    "confidence": "high",
                    "anomalies": [],
                }
                for item in chunk
            ]

        plan = build_review_plan(self.manifest, review_identical=True)
        verdicts, stats = execute_review(
            self.manifest,
            provider,
            review_identical=True,
        )

        self.assertEqual([], plan["identical"])
        self.assertEqual(self.manifest, plan["semantic"])
        self.assertIn(self.manifest[0]["label"], {label for call in calls for label in call})
        self.assertEqual(0, stats["identical"])
        self.assertEqual(3, stats["triaged"])
        self.assertEqual(3, len(verdicts))

        cached = {
            self.manifest[0]["label"]: {
                "label": self.manifest[0]["label"],
                "visible": "The candidate is semantically correct.",
                "semantic_valid": True,
                "matches_reference": True,
                "anomalies": [],
                "defect": False,
            }
        }
        with self.assertRaisesRegex(ReviewError, "fresh semantic verdict"):
            execute_review(
                self.manifest,
                provider,
                cache_hits=cached,
                review_identical=True,
            )

    def test_plan_keeps_loader_siblings_in_the_same_chunk(self) -> None:
        interleaved = [
            unpaired("fabric-1.20.1/full/client_a/first", "review-input/images/a.png"),
            unpaired("fabric-1.20.1/full/client_a/second", "review-input/images/b.png"),
            unpaired("forge-1.20.1/full/client_a/first", "review-input/images/c.png"),
            unpaired("forge-1.20.1/full/client_a/second", "review-input/images/d.png"),
        ]

        chunks = build_review_plan(interleaved, triage_chunk_size=2)["triage_chunks"]

        self.assertEqual(
            [
                [interleaved[0]["label"], interleaved[2]["label"]],
                [interleaved[1]["label"], interleaved[3]["label"]],
            ],
            [[item["label"] for item in chunk] for chunk in chunks],
        )

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
        self.assertEqual(0, stats["cached"])

    def test_independent_triage_chunks_run_concurrently(self) -> None:
        manifest = [
            unpaired(
                f"fabric-1.20.1/full/client_a/frame_{index}",
                f"review-input/images/{index}.png",
            )
            for index in range(2)
        ]
        rendezvous = threading.Barrier(2, timeout=2)
        active = 0
        maximum_active = 0
        lock = threading.Lock()

        def provider(
            stage: str,
            _chunk_index: int,
            chunk: list[dict[str, Any]],
            _schema: dict[str, Any],
        ) -> list[dict[str, Any]]:
            nonlocal active, maximum_active
            self.assertEqual("triage", stage)
            with lock:
                active += 1
                maximum_active = max(maximum_active, active)
            rendezvous.wait()
            with lock:
                active -= 1
            return [
                {
                    "label": chunk[0]["label"],
                    "decision": "clean",
                    "confidence": "high",
                    "anomalies": [],
                }
            ]

        verdicts, stats = execute_review(
            manifest, provider, triage_chunk_size=1, max_parallel_calls=2
        )

        self.assertEqual(2, maximum_active)
        self.assertEqual(2, len(verdicts))
        self.assertEqual(0, stats["stopped_early"])

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

        self.assertEqual([self.manifest[2]["label"]], [item["label"] for item in verdicts])
        self.assertTrue(verdicts[0]["defect"])
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
                "cached": 0,
                "represented": 0,
                "triaged": 2,
                "triage_chunks": 1,
                "escalated": 1,
                "verify_chunks": 1,
                "reviewed": 1,
                "stopped_early": 1,
            },
            stats,
        )

    def test_low_confidence_clean_result_is_verified(self) -> None:
        manifest = [self.manifest[1]]
        calls: list[str] = []

        def provider(
            stage: str,
            _chunk_index: int,
            chunk: list[dict[str, Any]],
            _schema: dict[str, Any],
        ) -> list[dict[str, Any]]:
            calls.append(stage)
            label = chunk[0]["label"]
            if stage == "triage":
                return [
                    {
                        "label": label,
                        "decision": "clean",
                        "confidence": "medium",
                        "anomalies": [],
                    }
                ]
            self.assertEqual("medium", chunk[0]["first_review"]["confidence"])
            return [
                {
                    "label": label,
                    "visible": "The candidate remains semantically correct.",
                    "semantic_valid": True,
                    "matches_reference": True,
                    "anomalies": [],
                    "defect": False,
                }
            ]

        verdicts, stats = execute_review(manifest, provider)

        self.assertEqual(["triage", "verify"], calls)
        self.assertFalse(verdicts[0]["defect"])
        self.assertEqual(1, stats["escalated"])
        self.assertEqual(1, stats["verify_chunks"])

    def test_high_confidence_haiku_clears_a_near_nonexact_pair(self) -> None:
        manifest = [
            {
                **self.manifest[1],
                "semantic_changed_fraction": 0.005,
                "perceptual_delta": 0.2,
            }
        ]
        calls: list[str] = []

        def provider(
            stage: str,
            _chunk_index: int,
            chunk: list[dict[str, Any]],
            _schema: dict[str, Any],
        ) -> list[dict[str, Any]]:
            calls.append(stage)
            label = chunk[0]["label"]
            if stage == "triage":
                return [
                    {
                        "label": label,
                        "decision": "clean",
                        "confidence": "high",
                        "anomalies": [],
                    }
                ]
            self.fail("high-confidence clean Haiku triage must not call Opus")

        verdicts, stats = execute_review(manifest, provider)

        self.assertEqual(["triage"], calls)
        self.assertEqual(0, stats["escalated"])
        self.assertEqual(0, stats["verify_chunks"])
        self.assertFalse(verdicts[0]["defect"])

    def test_cached_defect_blocks_without_calling_a_model(self) -> None:
        cached = {
            self.manifest[2]["label"]: {
                "label": self.manifest[2]["label"],
                "visible": "The cape is visibly square.",
                "semantic_valid": False,
                "matches_reference": False,
                "anomalies": ["The expected elytra shape is missing."],
                "defect": True,
            }
        }

        def provider(*_args: Any, **_kwargs: Any) -> list[dict[str, Any]]:
            self.fail("a cached blocking defect must stop before provider calls")

        verdicts, stats = execute_review(
            self.manifest, provider, cache_hits=cached
        )

        self.assertEqual([self.manifest[2]["label"]], [item["label"] for item in verdicts])
        self.assertEqual(1, stats["cached"])
        self.assertEqual(1, stats["stopped_early"])

    def test_partial_reports_accept_only_confirmed_defects_and_summarize_scope(self) -> None:
        defect = {
            "label": self.manifest[2]["label"],
            "visible": "The candidate is visibly blurred.",
            "semantic_valid": False,
            "matches_reference": False,
            "anomalies": ["The custom panel is blurred."],
            "defect": True,
        }

        normalized = validate_blocking_partial(
            self.manifest, [defect], require_paired=True
        )
        summary, has_defects = render(normalized, total_frames=len(self.manifest))

        self.assertTrue(has_defects)
        self.assertIn("Reviewed 1 of 3 frames", summary)
        self.assertIn("cannot certify or release anything", summary)
        clean = {
            **defect,
            "semantic_valid": True,
            "matches_reference": True,
            "anomalies": [],
            "defect": False,
        }
        with self.assertRaisesRegex(ReviewError, "cannot contain a clean verdict"):
            validate_blocking_partial(self.manifest, [clean], require_paired=True)

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
