from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts" / "ci" / "visual_anchor_certification.py"
SPEC = importlib.util.spec_from_file_location("visual_anchor_certification", MODULE_PATH)
assert SPEC and SPEC.loader
certification = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = certification
SPEC.loader.exec_module(certification)


class VisualAnchorCertificationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        image = "review-input/images/" + "a" * 64 + ".png"
        self.manifest = [
            {
                "path": image,
                "label": "fabric-1.20.1/full/client_a/baseline",
                "capture_id": "full.client_a.baseline",
                "kind": "full.client_a.baseline",
                "expectation": "Expected baseline",
            },
            {
                "path": image,
                "label": "forge-1.20.1/full/client_a/baseline",
                "capture_id": "full.client_a.baseline",
                "kind": "full.client_a.baseline",
                "expectation": "Expected baseline",
            },
        ]
        self.report = [
            {
                "label": item["label"],
                "visible": "The expected baseline is visible.",
                "semantic_valid": True,
                "matches_reference": None,
                "anomalies": [],
                "defect": False,
            }
            for item in self.manifest
        ]
        self.paths = {
            "proof": self.root / "curation-proof.json",
            "manifest": self.root / "visual-review-manifest.json",
            "report": self.root / "visual-review-report.json",
        }
        self._write("manifest", self.manifest)
        self._write("report", self.report)
        manifest_sha = certification._sha256(self.paths["manifest"])
        self.proof = {
            "schema_version": 4,
            "source_run_id": 123,
            "source_branch": "automation/sync/forge-and-fabric-1.20.1/1-1",
            "source_sha": "2" * 40,
            "master_source_sha": "1" * 40,
            "implementation_sha": "3" * 40,
            "matrix_kind": "pr-anchors",
            "review_mode": "anchor-semantic",
            "scenario_contract_sha256": "4" * 64,
            "artifact_inventory": [{"id": 1}],
            "job_graph": {"runtime_policy": "full"},
            "visual_reference": None,
            "manifest_sha256": manifest_sha,
            "frame_count": 2,
            "image_count": 1,
            "image_bytes": 100,
        }
        self._write("proof", self.proof)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write(self, name: str, value: object) -> None:
        self.paths[name].write_text(json.dumps(value) + "\n", encoding="utf-8")

    def create(self, **overrides: object) -> dict[str, object]:
        arguments = {
            "proof": self.proof,
            "manifest": self.manifest,
            "report": self.report,
            "proof_path": self.paths["proof"],
            "manifest_path": self.paths["manifest"],
            "report_path": self.paths["report"],
            "anchor_branch": "forge-and-fabric-1.20.1",
            "anchor_target_sha": "5" * 40,
        }
        arguments.update(overrides)
        return certification.create_certificate(**arguments)

    def test_clean_complete_unpaired_anchor_produces_strict_certificate(self) -> None:
        certificate = self.create()

        self.assertEqual("certified", certificate["verdict"])
        self.assertEqual(2, certificate["frame_count"])
        self.assertEqual(1, certificate["capture_count"])
        self.assertEqual(
            certificate,
            certification.validate_certificate(
                certificate,
                expected_master_sha="1" * 40,
                expected_anchor_branch="forge-and-fabric-1.20.1",
            ),
        )

    def test_shared_semantic_defect_cannot_become_a_certificate(self) -> None:
        report = [dict(item) for item in self.report]
        report[0].update(
            semantic_valid=False,
            defect=True,
            anomalies=["Cape geometry is visibly wrong."],
        )
        self._write("report", report)

        with self.assertRaisesRegex(certification.CertificationError, "not completely clean"):
            self.create(report=report)

    def test_reference_or_incomplete_loader_coverage_fails_closed(self) -> None:
        referenced = [
            {
                **self.manifest[0],
                "reference_path": self.manifest[0]["path"],
                "reference_label": self.manifest[1]["label"],
            },
            self.manifest[1],
        ]
        with self.assertRaisesRegex(certification.CertificationError, "must not contain"):
            self.create(manifest=referenced)
        with self.assertRaisesRegex(certification.CertificationError, "identical complete"):
            self.create(manifest=self.manifest[:1], report=self.report[:1])

    def test_certificate_is_bound_to_exact_master_and_anchor_branch(self) -> None:
        certificate = self.create()
        with self.assertRaisesRegex(certification.CertificationError, "another master"):
            certification.validate_certificate(
                certificate,
                expected_master_sha="9" * 40,
                expected_anchor_branch="forge-and-fabric-1.20.1",
            )
        with self.assertRaisesRegex(certification.CertificationError, "another anchor"):
            certification.validate_certificate(
                certificate,
                expected_master_sha="1" * 40,
                expected_anchor_branch="fabric-and-neoforge-1.21.1",
            )


if __name__ == "__main__":
    unittest.main()
