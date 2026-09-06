from __future__ import annotations

import io
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import feature_review as review
import test_visual_evidence as image_fixtures
from scenario_contract import load_contract


class FixtureApi(review.publisher.Api):
    def __init__(self, records, archives, source_sha):
        super().__init__("The-Plum-Team/Quick-Skin-Mod")
        self.records, self.archives, self.source_sha = records, archives, source_sha
        self.downloaded = []

    def current_sha(self):
        return self.source_sha

    def artifacts(self, *, run_id=None, name=None):
        if run_id != 55 or name is not None: raise AssertionError("unexpected artifact query")
        return self.records

    def json(self, endpoint):
        raise AssertionError("unexpected external API boundary " + endpoint)

    def get(self, endpoint, *, maximum):
        prefix = self.prefix + "actions/artifacts/"
        if not endpoint.startswith(prefix) or not endpoint.endswith("/zip"):
            raise AssertionError("unexpected external download " + endpoint)
        identifier = int(endpoint[len(prefix):-4])
        self.downloaded.append(identifier)
        raw = self.archives[identifier]
        if len(raw) > maximum: raise review.coverage.CoverageError("fixture archive exceeded its read budget")
        return raw


class FeatureReviewTest(unittest.TestCase):
    def setUp(self):
        self.fixture = image_fixtures.VisualEvidenceTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root.resolve()
        self.fixture.write_catalog([("phase0-smoke", "client_a", "baseline"),
                                    ("phase0-smoke", "client_a", "unselected")])
        contract = load_contract(self.fixture.catalog_path)
        admission = review.coverage.admission
        plan = admission.Selection(contract.sha256, review.coverage.load_graph().sha256, "pr", "affected",
            "affected-module-coverage", ("modules/hud-preview/src/main/java/example/Feature.java",),
            ("hud-preview",), ("hud-preview",), (),
            (admission.ScenarioSelection("phase0-smoke", (admission.RoleSelection("client_a",
                ("baseline",), ("baseline",), ("baseline",)),)),), ())
        self.source = "b" * 40
        self.selected = admission.Admission("a" * 40, self.source, self.source, "c" * 40,
            "d" * 40, "d" * 40, "e" * 64, "f" * 64, True, "pr", plan.reason, plan)
        self.coverage = {"schema_version": 1, "selective": True, "reason": plan.reason,
                         "selection_sha256": self.selected.sha256, "baseline": {"id": 9000}}
        matrix, _digest, rows = review.targets._inventory(review.coverage.DEFAULT_MATRIX, "pr-anchors")
        self.targets = review.coverage.inventory(review.coverage.DEFAULT_MATRIX)["include"]
        self.records = [{"id": index + 1, "name": "packaged-e2e-" + row["id"], "size_in_bytes": 1024,
            "digest": "sha256:" + "a" * 64, "expired": False,
            "workflow_run": {"id": 55, "head_branch": "master", "head_sha": self.source}}
            for index, row in enumerate(rows)]
        self.archives = {}
        versions = {item["minecraft_target"] for item in self.targets[:2]}
        for index, (row, record) in enumerate(zip(rows, self.records, strict=True)):
            if row["runtime_version"] not in versions: continue
            encoded = io.BytesIO()
            Image.new("RGB", (1920, 1080), (12 + index * 10, 34, 56)).save(encoded, format="PNG")
            path = self.fixture.write_result("phase0-smoke", artifact_node=row["artifact_node"],
                runtime_version=row["runtime_version"], loader=row["loader"], image_payload=encoded.getvalue())
            result = json.loads(path.read_text())
            result["selection_sha256"] = self.selected.sha256
            result["reports"]["client_a"]["selection_sha256"] = self.selected.sha256
            path.write_text(json.dumps(result))
            files = {item.relative_to(self.fixture.e2e_root).as_posix(): item.read_bytes()
                     for item in path.parent.rglob("*") if item.is_file()}
            files.update({"selection.json": self.selected.to_bytes(),
                          "coverage.json": admission.canonical(self.coverage)})
            self.archive(record, files)
        self.selection_record = {"id": 5000, "name": review.SELECTION_ARTIFACT, "expired": False,
            "workflow_run": {"id": 55, "head_branch": "master", "head_sha": self.source}}
        self.records.append(self.selection_record)
        self.archive(self.selection_record, {"selection.json": self.selected.to_bytes(),
                                            "coverage.json": admission.canonical(self.coverage)})
        self.api = FixtureApi(self.records, self.archives, self.source)
        self.impact = {"schema_version": 1, "compatibility_required": True, "paths": [], "impact_paths": []}
        self.invocations = 0
        # The consumer's own tests authenticate real Git objects and complete baseline reports.
        # Here that boundary is pinned while real archive, row, image and capsule validators run.
        for replacement in (patch.object(review.consumer, "authenticate_execution"),
                            patch.object(review, "DEFAULT_CATALOG", self.fixture.catalog_path),
                            patch.object(review.coverage, "default_contract", return_value=contract),
                            patch.object(review.publisher, "_get", side_effect=self.api.get)):
            replacement.start()
            self.addCleanup(replacement.stop)
        verifier = patch.object(review.consumer, "verify", return_value=(self.selected, self.coverage))
        self.verifier = verifier.start()
        self.addCleanup(verifier.stop)

    def archive(self, record, files):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, raw in files.items(): archive.writestr(name, raw)
        raw = output.getvalue()
        self.archives[record["id"]] = raw
        record.update(size_in_bytes=len(raw), digest="sha256:" + review.coverage.digest(raw))

    def curate(self, target_index=0):
        self.invocations += 1
        output, scratch = self.root / f"capsule-{self.invocations}", self.root / f"scratch-{self.invocations}"
        output.mkdir(); scratch.mkdir()
        proof = review.curate(self.api, repository=ROOT, source_sha=self.source, source_run_id=55,
            bundle_key=self.targets[target_index]["bundle_key"], compatibility_impact=self.impact,
            output=output, scratch=scratch)
        return proof, output

    def test_semantic_capsule_contains_only_selected_loader_frames_and_cannot_certify_full_coverage(self):
        proof, output = self.curate()
        manifest = json.loads((output / "review-input/visual-review-manifest.json").read_text())
        self.assertEqual(7, proof["schema_version"])
        self.assertEqual("anchor-semantic", proof["review_mode"])
        self.assertEqual(2, proof["frame_count"])
        self.assertIsNone(proof["visual_reference"])
        self.assertTrue(all(item["capture_id"] == "phase0-smoke.client_a.baseline" for item in manifest))
        images = list((output / "review-input/images").iterdir())
        self.assertEqual(sum(path.stat().st_size for path in images), proof["image_bytes"])
        self.assertGreater(proof["image_bytes"], proof["frame_count"])
        self.assertEqual(3, len(self.api.downloaded))  # Admission plus the two target lanes.
        self.assertEqual(self.source, self.verifier.call_args.kwargs["head"])
        self.assertEqual(self.source, self.verifier.call_args.kwargs["policy"])
        self.assertEqual(55, self.verifier.call_args.kwargs["run_id"])
        report = output / "report.json"
        report.write_text("[]\n")
        with self.assertRaises(review.coverage.CoverageError):
            review.coverage.validate_clean_target(review.coverage.ReviewFiles(output / "curation-proof.json",
                output / "review-input/visual-review-manifest.json", report), source_sha=self.source,
                source_run_id=55, bundle_key=self.targets[0]["bundle_key"])
        with self.assertRaises(review.targets.ReviewTargetError):
            review.targets.validate_target_proof(proof)

    def test_paired_capsule_uses_one_same_run_selected_fabric_reference_and_reauthenticates(self):
        proof, output = self.curate(1)
        self.assertEqual("reference-comparison", proof["review_mode"])
        self.assertEqual("packaged-selection", proof["visual_reference"]["evidence_kind"])
        self.assertEqual(2, proof["frame_count"])
        self.assertEqual(4, len(self.api.downloaded))
        manifest_path = output / "review-input/visual-review-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        self.assertTrue(all(item["reference_label"].startswith("fabric-1.20.1/") for item in manifest))
        scratch = self.root / "verify"
        scratch.mkdir()
        selected = review.verify(self.api, output / "curation-proof.json", manifest_path, repository=ROOT,
            source_sha=self.source, source_run_id=55, bundle_key=self.targets[1]["bundle_key"], scratch=scratch)
        self.assertEqual(self.selected.sha256, selected.sha256)
        proof["visual_reference"]["source_run_id"] = 56
        (output / "curation-proof.json").write_text(json.dumps(proof))
        scratch = self.root / "foreign-reference"
        scratch.mkdir()
        with self.assertRaises(review.coverage.CoverageError):
            review.verify(self.api, output / "curation-proof.json", manifest_path, repository=ROOT,
                source_sha=self.source, source_run_id=55, bundle_key=self.targets[1]["bundle_key"], scratch=scratch)

    def test_complete_runtime_without_selection_keeps_the_existing_curator_without_downloads(self):
        self.records.remove(self.selection_record)
        proof, output = self.curate()
        self.assertIsNone(proof)
        self.assertEqual([], self.api.downloaded)
        self.assertEqual([], list(output.iterdir()))
        self.verifier.assert_not_called()

    def test_baseline_admission_failure_stops_before_any_raw_image_download(self):
        self.verifier.side_effect = review.coverage.CoverageError("unproven baseline")
        with self.assertRaises(review.coverage.CoverageError): self.curate()
        self.assertEqual([5000], self.api.downloaded)

    def test_foreign_lane_metadata_and_substituted_lane_admission_are_rejected(self):
        self.records[0]["workflow_run"]["head_sha"] = "0" * 40
        with self.assertRaises(review.targets.ReviewTargetError): self.curate()
        self.assertEqual([5000], self.api.downloaded)
        self.records[0]["workflow_run"]["head_sha"] = self.source
        record = self.records[0]
        with zipfile.ZipFile(io.BytesIO(self.archives[record["id"]])) as archive:
            files = {name: archive.read(name) for name in archive.namelist()}
        files["selection.json"] = b"{}\n"
        self.archive(record, files)
        with self.assertRaises(review.coverage.CoverageError): self.curate()

    def test_selection_archive_cannot_smuggle_extra_files_or_override_the_scope(self):
        self.archive(self.selection_record, {"selection.json": self.selected.to_bytes(),
            "coverage.json": review.coverage.admission.canonical(self.coverage), "unexpected.py": b"print('unsafe')\n"})
        with self.assertRaises(ValueError): self.curate()
        self.verifier.assert_not_called()

    def test_capture_omission_and_source_advance_cannot_leave_a_publishable_proof(self):
        proof, output = self.curate()
        manifest = json.loads((output / "review-input/visual-review-manifest.json").read_text())
        with self.assertRaises(review.coverage.CoverageError):
            review.validate_selected_manifest(proof, manifest[:-1], self.selected)
        self.api.source_sha = "0" * 40
        with self.assertRaises(review.coverage.CoverageError): self.curate()
        self.assertFalse((self.root / "capsule-2/curation-proof.json").exists())


if __name__ == "__main__":
    unittest.main()
