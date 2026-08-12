from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

from check_visual_review import ReviewError  # noqa: E402
from visual_review_cache import (  # noqa: E402
    cache_identity,
    cache_key,
    cached_verdicts,
    merge_cache,
    review_policy_sha256,
    validate_cache,
)


POLICY = "a" * 64
CANDIDATE = "b" * 64
REFERENCE = "c" * 64


def paired(
    label: str,
    *,
    candidate: str = CANDIDATE,
    reference: str = REFERENCE,
    expectation: str = "The cape must have its expected shape.",
) -> dict[str, str]:
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


class VisualReviewCacheTest(unittest.TestCase):
    def test_policy_changes_when_any_protected_input_changes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            files = []
            for index in range(8):
                path = root / f"policy-{index}.txt"
                path.write_text(f"value-{index}", encoding="utf-8")
                files.append(path)

            def policy() -> str:
                return review_policy_sha256(
                    runner=files[0],
                    checker=files[1],
                    cache_codec=files[2],
                    scenario_contract=files[3],
                    release_matrix=files[4],
                    provider_lock=files[5],
                    triage_prompt=files[6],
                    verify_prompt=files[7],
                    triage_model="claude-sonnet-5",
                    verify_model="claude-opus-5",
                    review_mode="reference-comparison",
                    triage_chunk_size=8,
                    verify_chunk_size=4,
                )

            first = policy()
            files[6].write_text("changed prompt", encoding="utf-8")
            second = policy()

        self.assertNotEqual(first, second)

    def test_cache_hits_require_exact_pixels_expectation_and_artifact(self) -> None:
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

        self.assertEqual(
            [same["label"]],
            list(cached_verdicts([same], cache, review_mode="reference-comparison")),
        )
        for candidate in (changed_pixels, changed_expectation, changed_artifact):
            with self.subTest(label=candidate["label"], path=candidate["path"]):
                self.assertEqual(
                    {},
                    cached_verdicts(
                        [candidate], cache, review_mode="reference-comparison"
                    ),
                )

    def test_anchor_semantic_mode_never_uses_or_updates_cache(self) -> None:
        source = paired("fabric-1.21.1/full/client_a/cape")
        cache = merge_cache(
            None,
            [source],
            [verdict(source["label"])],
            policy_sha256=POLICY,
            review_mode="reference-comparison",
        )

        self.assertEqual(
            {}, cached_verdicts([source], cache, review_mode="anchor-semantic")
        )
        with self.assertRaisesRegex(ReviewError, "only paired comparison"):
            merge_cache(
                None,
                [source],
                [verdict(source["label"])],
                policy_sha256=POLICY,
                review_mode="anchor-semantic",
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
            validate_cache({"schema_version": 1, "policy_sha256": POLICY, "entries": []}, "f" * 64)

    def test_key_is_label_independent_but_loader_specific(self) -> None:
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
        self.assertNotEqual(
            cache_key(cache_identity(first, "reference-comparison")),
            cache_key(cache_identity(forge, "reference-comparison")),
        )


if __name__ == "__main__":
    unittest.main()
