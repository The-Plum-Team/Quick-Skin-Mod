from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from check_visual_review import ReviewError  # noqa: E402
from visual_review_cache import (  # noqa: E402
    cache_identity,
    cache_key,
    cached_verdicts,
    combine_caches,
    merge_cache,
    review_policy_sha256,
    validate_cache,
)


POLICY = "a" * 64
CANDIDATE = "b" * 64
REFERENCE = "c" * 64
ANCHOR_IMAGE = "e" * 64


def paired(
    label: str,
    *,
    candidate: str = CANDIDATE,
    reference: str = REFERENCE,
    expectation: str = "The cape must have its expected shape.",
) -> dict[str, Any]:
    _artifact, scenario, role, step = label.split("/")
    capture_id = f"{scenario}.{role}.{step}"
    return {
        "path": f"review-input/images/{candidate}.png",
        "reference_path": f"review-input/images/{reference}.png",
        "reference_label": f"fabric-1.20.1/{scenario}/{role}/{step}",
        "label": label,
        "capture_id": capture_id,
        "kind": capture_id,
        "expectation": expectation,
        "runtime_evidence": "renderer-facing assertion passed",
        "image_size": [1920, 1080],
        "review_regions": [[0.25, 0.25, 0.75, 0.75]],
        "candidate_semantic_sha256": candidate,
        "reference_semantic_sha256": reference,
        "semantic_changed_fraction": 0.0 if candidate == reference else 0.2,
        "perceptual_delta": 0.0 if candidate == reference else 0.2,
    }


def unpaired(
    label: str,
    *,
    candidate: str = CANDIDATE,
    image: str = ANCHOR_IMAGE,
    expectation: str = "The cape must have its expected shape.",
) -> dict[str, Any]:
    _artifact, scenario, role, step = label.split("/")
    capture_id = f"{scenario}.{role}.{step}"
    return {
        "path": f"review-input/images/{image}.png",
        "label": label,
        "capture_id": capture_id,
        "kind": capture_id,
        "expectation": expectation,
        "runtime_evidence": "renderer-facing assertion passed",
        "image_size": [1920, 1080],
        "review_regions": [[0.25, 0.25, 0.75, 0.75]],
        "candidate_semantic_sha256": candidate,
    }


def verdict(label: str, *, defect: bool = False) -> dict[str, object]:
    return {
        "label": label,
        "visible": "The expected cape is visible.",
        "semantic_valid": not defect,
        "matches_reference": not defect,
        "anomalies": ["The cape shape is wrong."] if defect else [],
        "defect": defect,
    }


def anchor_verdict(label: str, *, defect: bool = False) -> dict[str, object]:
    return {
        "label": label,
        "visible": "The expected cape is visible.",
        "semantic_valid": not defect,
        "matches_reference": None,
        "anomalies": ["The cape shape is wrong."] if defect else [],
        "defect": defect,
    }


class VisualReviewCacheTest(unittest.TestCase):
    def test_immutable_shards_combine_newest_first(self) -> None:
        shared = paired("fabric-1.21.1/full/client_a/cape")
        older_only = paired(
            "forge-1.21.1/full/client_a/cape", candidate="d" * 64
        )
        older = merge_cache(
            None,
            [shared, older_only],
            [verdict(shared["label"]), verdict(older_only["label"])],
            policy_sha256=POLICY,
            review_mode="reference-comparison",
        )
        newer = merge_cache(
            None,
            [shared],
            [verdict(shared["label"], defect=True)],
            policy_sha256=POLICY,
            review_mode="reference-comparison",
        )

        combined = combine_caches([newer, older], policy_sha256=POLICY)
        hits = cached_verdicts(
            [shared, older_only], combined, review_mode="reference-comparison"
        )

        self.assertTrue(hits[shared["label"]]["defect"])
        self.assertFalse(hits[older_only["label"]]["defect"])
        self.assertEqual(2, len(combined["entries"]))

    def test_policy_changes_when_any_protected_input_changes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = []
            for index in range(9):
                path = root / f"policy-{index}.txt"
                path.write_text(f"value-{index}", encoding="utf-8")
                files.append(path)

            def policy() -> str:
                return review_policy_sha256(
                    runner=files[0],
                    checker=files[1],
                    cache_codec=files[2],
                    similarity_codec=files[3],
                    scenario_contract=files[4],
                    release_matrix=files[5],
                    provider_lock=files[6],
                    triage_prompt=files[7],
                    verify_prompt=files[8],
                    triage_model="claude-haiku-4-5",
                    verify_model="claude-opus-5",
                    review_mode="reference-comparison",
                    triage_chunk_size=8,
                    verify_chunk_size=4,
                )

            first = policy()
            files[3].write_text("changed similarity", encoding="utf-8")
            second = policy()

        self.assertNotEqual(first, second)

    def test_cache_hits_require_exact_regions_expectation_and_runtime_evidence(self) -> None:
        source = paired("fabric-1.21.1/full/client_a/cape")
        cache = merge_cache(
            None,
            [source],
            [verdict(source["label"])],
            policy_sha256=POLICY,
            review_mode="reference-comparison",
        )
        same = paired("fabric-1.21.1/full/client_a/cape")
        changed_pixels = paired(
            "fabric-1.21.1/full/client_a/cape", candidate="d" * 64
        )
        changed_expectation = paired(
            "fabric-1.21.1/full/client_a/cape", expectation="Different contract"
        )
        changed_artifact = paired("neoforge-1.21.1/full/client_a/cape")
        changed_runtime_evidence = {
            **same,
            "runtime_evidence": "different renderer-facing assertion",
        }

        self.assertEqual(
            [same["label"]],
            list(cached_verdicts([same], cache, review_mode="reference-comparison")),
        )
        self.assertEqual(
            [changed_artifact["label"]],
            list(
                cached_verdicts(
                    [changed_artifact], cache, review_mode="reference-comparison"
                )
            ),
        )
        for candidate in (
            changed_pixels,
            changed_expectation,
            changed_runtime_evidence,
        ):
            with self.subTest(label=candidate["label"], path=candidate["path"]):
                self.assertEqual(
                    {},
                    cached_verdicts(
                        [candidate], cache, review_mode="reference-comparison"
                    ),
                )

    def test_anchor_cache_requires_exact_lane_pixels_and_semantics(self) -> None:
        source = unpaired("fabric-1.20.1/full/client_a/cape")
        cache = merge_cache(
            None,
            [source],
            [anchor_verdict(source["label"])],
            policy_sha256=POLICY,
            review_mode="anchor-semantic",
        )

        self.assertEqual(
            [source["label"]],
            list(cached_verdicts([source], cache, review_mode="anchor-semantic")),
        )
        changed_lane = unpaired("forge-1.20.1/full/client_a/cape")
        changed_full_image = unpaired(
            source["label"], image="f" * 64
        )
        changed_semantic_region = unpaired(
            source["label"], candidate="d" * 64
        )
        changed_expectation = unpaired(
            source["label"], expectation="A different authored expectation."
        )
        changed_runtime_evidence = {
            **source,
            "runtime_evidence": "a different renderer-facing assertion",
        }
        for candidate in (
            changed_lane,
            changed_full_image,
            changed_semantic_region,
            changed_expectation,
            changed_runtime_evidence,
        ):
            with self.subTest(label=candidate["label"], path=candidate["path"]):
                self.assertEqual(
                    {},
                    cached_verdicts(
                        [candidate], cache, review_mode="anchor-semantic"
                    ),
                )

    def test_cache_rejects_a_manifest_from_another_review_mode(self) -> None:
        source = unpaired("fabric-1.20.1/full/client_a/cape")
        with self.assertRaisesRegex(ReviewError, "does not match the manifest"):
            merge_cache(
                None,
                [source],
                [anchor_verdict(source["label"])],
                policy_sha256=POLICY,
                review_mode="reference-comparison",
            )

    def test_tampered_cache_key_and_policy_fail_closed(self) -> None:
        source = paired("fabric-1.21.1/full/client_a/cape")
        cache = merge_cache(
            None,
            [source],
            [verdict(source["label"], defect=True)],
            policy_sha256=POLICY,
            review_mode="reference-comparison",
        )
        cache["entries"][0]["key"] = "0" * 64

        with self.assertRaisesRegex(ReviewError, "invalid or duplicate key"):
            validate_cache(cache, POLICY)
        with self.assertRaisesRegex(ReviewError, "different review policy"):
            validate_cache(
                {"schema_version": 3, "policy_sha256": POLICY, "entries": []},
                "f" * 64,
            )

    def test_key_is_label_and_loader_independent_for_identical_semantics(self) -> None:
        first = paired("fabric-1.21.1/full/client_a/cape")
        same_artifact_new_label = {
            **first,
            "label": "fabric-1.21.1/full/client_a/cape",
        }
        forge = paired("forge-1.21.1/full/client_a/cape")

        self.assertEqual(
            cache_key(cache_identity(first, "reference-comparison")),
            cache_key(cache_identity(same_artifact_new_label, "reference-comparison")),
        )
        self.assertEqual(
            cache_key(cache_identity(first, "reference-comparison")),
            cache_key(cache_identity(forge, "reference-comparison")),
        )


if __name__ == "__main__":
    unittest.main()
