from __future__ import annotations

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from mod_compatibility_review_batch import (  # noqa: E402
    BatchError,
    assemble,
    split_lane,
    validate_batch,
)
from visual_review_runner import build_review_plan  # noqa: E402
from visual_similarity import analyze_png_payloads  # noqa: E402


SOURCE_RUN_ID = 123
IMPLEMENTATION_SHA = "a" * 40
SOURCE_SHA = "b" * 40
TARGET_SHA = "c" * 40
REGIONS = [[0.25, 0.25, 0.75, 0.75]]


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
        + "\n",
        encoding="utf-8",
    )


def matrix_lane(lane_id: str, artifact_node: str, mod: str) -> dict[str, Any]:
    return {
        "artifact_digest": "sha256:" + "d" * 64,
        "artifact_id": 10,
        "artifact_name": f"mod-compatibility-review-input-{SOURCE_RUN_ID}-{lane_id}",
        "artifact_node": artifact_node,
        "artifact_size": 1024,
        "base_evidence_name": f"packaged-e2e-{artifact_node}--scheduled-behavior",
        "id": lane_id,
        "implementation_sha": IMPLEMENTATION_SHA,
        "loader": artifact_node.split("-")[0],
        "mod": mod,
        "mod_name": "Test Mod",
        "runtime_version": "1.20.1",
        "source_run_id": SOURCE_RUN_ID,
        "source_sha": SOURCE_SHA,
        "target_branch": "forge-and-fabric-1.20.1",
        "target_sha": TARGET_SHA,
    }


def write_png(path: Path, color: tuple[int, int, int]) -> tuple[str, bytes]:
    from PIL import Image

    temporary = path.parent / f"{path.name}.temporary.png"
    Image.new("RGB", (1920, 1080), color).save(temporary)
    payload = temporary.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    final = path.parent / f"{digest}.png"
    temporary.rename(final)
    return final.name, payload


def create_capsule(root: Path, lane: dict[str, Any]) -> list[dict[str, Any]]:
    input_root = root / "review-input"
    images = input_root / "images"
    images.mkdir(parents=True)
    candidate_name, candidate_payload = write_png(images / "candidate", (120, 30, 60))
    reference_name, reference_payload = write_png(images / "reference", (20, 40, 80))
    semantic = analyze_png_payloads(
        candidate_payload,
        reference_payload,
        REGIONS,
        (1920, 1080),
    )
    capture_id = "mod-compatibility.client_a.apply_local_skin_with_mod"
    manifest = [
        {
            "capture_id": capture_id,
            "candidate_semantic_sha256": semantic["candidate_semantic_sha256"],
            "expectation": "The optional integration remains visually correct.",
            "image_size": [1920, 1080],
            "kind": capture_id,
            "label": (
                f"{lane['artifact_node']}/{lane['mod']}/mod-compatibility/"
                "client_a/apply_local_skin_with_mod"
            ),
            "path": f"review-input/images/{candidate_name}",
            "perceptual_delta": semantic["perceptual_delta"],
            "reference_label": f"{lane['artifact_node']}/full/client_a/skin_menu_screen",
            "reference_path": f"review-input/images/{reference_name}",
            "reference_semantic_sha256": semantic["reference_semantic_sha256"],
            "review_regions": REGIONS,
            "runtime_evidence": "The deterministic compatibility assertion passed.",
            "semantic_changed_fraction": semantic["semantic_changed_fraction"],
        }
    ]
    manifest_path = input_root / "visual-review-manifest.json"
    write_json(manifest_path, manifest)
    proof = {
        "artifact_inventory": {"base": {}, "candidate": {}},
        "artifact_node": lane["artifact_node"],
        "compatibility_contract_sha256": "e" * 64,
        "compatibility_run_id": SOURCE_RUN_ID,
        "frame_count": 1,
        "implementation_sha": IMPLEMENTATION_SHA,
        "kind": "quick-skin-mod-compatibility-review-input",
        "loader": lane["loader"],
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "mod": lane["mod"],
        "mod_name": "Test Mod",
        "mod_version": "1.0.0",
        "mod_version_id": "locked-version",
        "runtime_version": "1.20.1",
        "scenario_contract_sha256": "f" * 64,
        "schema_version": 1,
        "source_run_id": 99,
        "source_sha": SOURCE_SHA,
        "target_branch": "forge-and-fabric-1.20.1",
        "target_sha": TARGET_SHA,
    }
    write_json(root / "curation-proof.json", proof)
    return manifest


class ModCompatibilityReviewBatchTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.lanes = [
            matrix_lane("fabric-lane", "fabric-1.20.1", "skin-layers-3d"),
            matrix_lane("forge-lane", "forge-1.20.1", "skin-layers-3d"),
        ]
        self.matrix_path = self.root / "matrix.json"
        write_json(self.matrix_path, {"include": self.lanes})
        self.lanes_root = self.root / "lanes"
        for lane in self.lanes:
            create_capsule(self.lanes_root / lane["id"], lane)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_batch_deduplicates_images_and_cross_lane_semantic_review(self) -> None:
        batch_root = self.root / "batch"

        batch = assemble(self.matrix_path, self.lanes_root, batch_root)
        validated = validate_batch(batch_root, require_images=True)
        manifest = json.loads(
            (batch_root / "review-input/visual-review-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        plan = build_review_plan(manifest)

        self.assertEqual(2, batch["lane_count"])
        self.assertEqual(2, batch["total_frames"])
        self.assertEqual(4, batch["input_image_references"])
        self.assertEqual(2, batch["unique_images"])
        self.assertEqual(batch, validated)
        self.assertEqual(1, len(plan["semantic"]))
        self.assertEqual(1, len(plan["represented_by_label"]))
        self.assertEqual(1, len(plan["triage_chunks"]))

    def test_single_frame_may_retain_two_unique_images(self) -> None:
        lane = self.lanes[0]
        matrix_path = self.root / "single-matrix.json"
        lanes_root = self.root / "single-lane"
        write_json(matrix_path, {"include": [lane]})
        create_capsule(lanes_root / lane["id"], lane)
        batch_root = self.root / "single-batch"

        batch = assemble(matrix_path, lanes_root, batch_root)

        self.assertEqual(1, batch["total_frames"])
        self.assertEqual(2, batch["unique_images"])
        self.assertEqual(batch, validate_batch(batch_root, require_images=True))

    def test_split_restores_complete_independent_lane_artifact(self) -> None:
        batch_root = self.root / "batch"
        assemble(self.matrix_path, self.lanes_root, batch_root)
        manifest = json.loads(
            (batch_root / "review-input/visual-review-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        report = [
            {
                "anomalies": [],
                "defect": False,
                "label": item["label"],
                "matches_reference": True,
                "semantic_valid": True,
                "visible": "The candidate remains visually correct.",
            }
            for item in manifest
        ]
        report_path = self.root / "visual-review-report.json"
        completion_path = self.root / "visual-review-completion.json"
        write_json(report_path, report)
        write_json(
            completion_path,
            {
                "manifest_frames": 2,
                "report_verdicts": 2,
                "schema_version": 1,
                "state": "complete",
            },
        )
        output = self.root / "split"

        verdict_count = split_lane(
            batch_root,
            report_path,
            completion_path,
            "forge-lane",
            output,
        )

        self.assertEqual(1, verdict_count)
        self.assertEqual(
            self.lanes_root.joinpath(
                "forge-lane/review-input/visual-review-manifest.json"
            ).read_bytes(),
            (output / "review-input/visual-review-manifest.json").read_bytes(),
        )
        split_report = json.loads(
            (output / "visual-review-report.json").read_text(encoding="utf-8")
        )
        self.assertEqual([manifest[1]["label"]], [item["label"] for item in split_report])

    def test_batch_rejects_proof_drift_before_aggregation(self) -> None:
        proof_path = self.lanes_root / "forge-lane/curation-proof.json"
        proof = json.loads(proof_path.read_text(encoding="utf-8"))
        proof["implementation_sha"] = "0" * 40
        write_json(proof_path, proof)

        with self.assertRaisesRegex(BatchError, "curation proof drifted"):
            assemble(self.matrix_path, self.lanes_root, self.root / "batch")

    def test_batch_applies_the_global_image_byte_bound_during_assembly(self) -> None:
        with patch("mod_compatibility_review_batch.MAX_REVIEW_TOTAL_BYTES", 1):
            with self.assertRaisesRegex(BatchError, "image-byte bound"):
                assemble(self.matrix_path, self.lanes_root, self.root / "batch")


if __name__ == "__main__":
    unittest.main()
