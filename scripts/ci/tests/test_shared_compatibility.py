from __future__ import annotations

import copy
import io
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import shared_compatibility as shared
import test_feature_coverage as coverage_fixtures
from test_feature_coverage_github import FixtureApi


class SharedCompatibilityTest(unittest.TestCase):
    def setUp(self):
        self.fixture = coverage_fixtures.FeatureCoverageTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.api = FixtureApi(self.fixture)
        self.target = self.fixture.targets[1]
        self.record = self.api.records[f"visual-review-55--{self.target['bundle_key']}"][0]
        for artifact in self.fixture.artifacts:
            self.api.records[artifact["name"]] = [artifact]
        self.api.records["e2e-input-bundle"] = [{"id": 999, "name": "e2e-input-bundle", "expired": False,
            "size_in_bytes": 1024, "digest": "sha256:" + "e" * 64,
            "workflow_run": {"id": 55, "head_branch": "master", "head_sha": self.fixture.source}}]
        with zipfile.ZipFile(io.BytesIO(self.api.archives[self.record["id"]])) as archive:
            self.contents = {name: archive.read(name) for name in archive.namelist()}
        proof = json.loads(self.contents["curation-proof.json"])
        reference = next(item for item, row in zip(self.fixture.artifacts, self.fixture.rows, strict=True)
                         if row["artifact_node"] == "fabric-1.20.1")
        proof.update(schema_version=8, visual_reference={"evidence_kind": "packaged-full",
            "artifact": reference, "artifact_node": "fabric-1.20.1", "source_sha": self.fixture.source,
            "source_run_id": 55})
        self.contents["curation-proof.json"] = json.dumps(proof).encode()
        self.contents["visual-review-completion.json"] = json.dumps({"schema_version": 1, "state": "complete",
            "manifest_frames": proof["frame_count"], "report_verdicts": proof["frame_count"]}).encode()
        self.replace_archive()
        self.invocations = 0
        patcher = patch.object(shared.coverage, "policy_fingerprint", return_value="d" * 64)
        patcher.start(); self.addCleanup(patcher.stop)

    def replace_archive(self):
        self.api.replace_archive(self.record, self.contents)
        self.payload = {"source_run_id": "55", "source_branch": "master", "source_sha": self.fixture.source,
            "target_branch": "master", "target_sha": self.fixture.source,
            "review_artifact_id": str(self.record["id"]), "review_artifact_name": self.record["name"],
            "review_artifact_run_id": str(self.record["workflow_run"]["id"]),
            "review_artifact_digest": self.record["digest"], "review_artifact_size": str(self.record["size_in_bytes"])}

    def admit(self):
        self.invocations += 1
        folder = self.fixture.root / f"admit-{self.invocations}"
        folder.mkdir()
        return shared.admit(self.api, self.payload, repository=ROOT,
                            policy_sha=self.fixture.source, directory=folder)

    def test_exact_target_wave_uses_ten_dispatch_fields_and_downloads_only_normalized_json(self):
        result = self.admit()
        self.assertEqual(10, len(self.payload))  # GitHub's repository_dispatch payload property limit.
        self.assertEqual(self.target["bundle_key"], result["bundle_key"])
        self.assertEqual("1.21.1", result["minecraft_target"])
        self.assertEqual(self.fixture.source, result["target_sha"])
        self.assertEqual([self.record["id"]], self.api.downloaded)

    def test_reused_generation_exposes_original_bundle_identity_without_relabelling_the_review(self):
        from test_feature_pages import merged_reference
        reference = merged_reference("d" * 40, self.fixture.source, 54)
        original = {**self.api.runs[55], "id": 54, "head_sha": "c" * 40, "head_branch": "feature/hud"}
        inventory = copy.deepcopy(self.fixture.artifacts + self.api.records["e2e-input-bundle"])
        for item in inventory:
            item["workflow_run"] = {key: original[key] for key in ("id", "head_sha", "head_branch")}
        runtime = shared.review.ci_reuse.RuntimeSource(self.api.runs[55], original, inventory, {}, reference)
        by_id = {item["id"]: item for item in inventory}
        proof = json.loads(self.contents["curation-proof.json"])
        proof["runtime_source"] = reference
        proof["artifact_inventory"] = [by_id[item["id"]] for item in proof["artifact_inventory"]]
        proof["visual_reference"].update(source_sha="d" * 40, source_run_id=54,
            artifact=by_id[proof["visual_reference"]["artifact"]["id"]])
        self.contents["curation-proof.json"] = json.dumps(proof).encode()
        self.replace_archive()
        with patch.object(shared.review, "authenticate_full", return_value=runtime):
            result = self.admit()
        self.assertEqual((54, "d" * 40), (result["runtime_run_id"], result["runtime_sha"]))
        self.assertEqual((55, self.fixture.source), (result["source_run_id"], result["source_sha"]))
        self.assertEqual(reference, json.loads(result["runtime_reference"]))

    def test_incomplete_source_and_partial_generation_cannot_start_mod_downloads(self):
        self.api.job_lists[55][0]["jobs"][-1]["conclusion"] = "failure"
        with self.assertRaises(ValueError): self.admit()
        self.assertEqual([], self.api.downloaded)
        self.api.job_lists[55][0]["jobs"][-1]["conclusion"] = "success"
        self.api.records[shared.coverage.SELECTION_ARTIFACT_NAME] = [{
            "id": 99000, "name": shared.coverage.SELECTION_ARTIFACT_NAME, "workflow_run": {"id": 55}}]
        with self.assertRaisesRegex(ValueError, "complete base runtime"): self.admit()
        self.assertEqual([], self.api.downloaded)

    def test_in_progress_reviewer_defers_without_reading_report_bytes(self):
        self.api.runs[self.record["workflow_run"]["id"]]["status"] = "in_progress"
        self.assertIsNone(self.admit())
        self.assertEqual([], self.api.downloaded)

    def test_request_cannot_select_another_owner_target_digest_or_source(self):
        original = copy.deepcopy(self.payload)
        for key, value in (("target_sha", "b" * 40), ("target_branch", "fabric-and-forge-1.20.1"),
                           ("review_artifact_name", "visual-review-55--mc999.0"),
                           ("review_artifact_digest", "sha256:" + "f" * 64),
                           ("review_artifact_run_id", "1002"), ("review_artifact_id", True)):
            with self.subTest(key=key):
                self.payload = {**original, key: value}
                with self.assertRaises(ValueError): self.admit()
        self.assertEqual([], self.api.downloaded)

    def test_partial_or_defective_normalized_review_cannot_authorize_compatibility(self):
        report = json.loads(self.contents["visual-review-report.json"])
        report[0]["defect"] = True
        self.contents["visual-review-report.json"] = json.dumps(report).encode()
        self.replace_archive()
        with self.assertRaises(ValueError): self.admit()

    def test_plan_recomputes_all_targets_and_rejects_omitted_lanes_and_foreign_versions(self):
        for row in shared.coverage.inventory(shared.coverage.DEFAULT_MATRIX)["include"]:
            plan = shared.build_plan(shared.coverage.DEFAULT_MATRIX, base_matrix_kind="pr-anchors",
                                     minecraft_target=row["minecraft_target"])
            plan.update({key: (55 if key == "source_run_id" else self.payload[key]) for key in shared.SOURCE_FIELDS})
            shared.validate_plan(plan, self.fixture.source)
        plan = shared.build_plan(shared.coverage.DEFAULT_MATRIX, base_matrix_kind="native-anchors",
                                 minecraft_target="1.20.1")
        plan.update({key: (55 if key == "source_run_id" else self.payload[key]) for key in shared.SOURCE_FIELDS})
        shared.validate_plan(plan, self.fixture.source)
        for mutate in (lambda value: value["runnable"].pop(),
                       lambda value: value.update(minecraft_target="1.21.1"),
                       lambda value: value.update(matrix_sha256="a" * 64)):
            invalid = copy.deepcopy(plan)
            mutate(invalid)
            with self.assertRaises(ValueError): shared.validate_plan(invalid, self.fixture.source)


if __name__ == "__main__":
    unittest.main()
