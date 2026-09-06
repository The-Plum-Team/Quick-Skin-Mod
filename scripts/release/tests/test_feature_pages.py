from __future__ import annotations

import copy
import hashlib
import json
import shutil
import sys
import unittest
from dataclasses import replace
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import test_pages_site as fixtures
import evidence
import feature_evidence as feature
from build_site import build
from visual_evidence import load_catalog


def merged_reference(tested_sha, covered_sha, run_id):
    """Inert public-format fixture; GitHub source authentication has separate transport tests."""
    source = {"schema_version": 1, "kind": "quick-skin-tested-source", "repository": "The-Plum-Team/Quick-Skin-Mod",
        "workflow": ".github/workflows/on-demand-e2e.yml", "run_id": run_id, "run_attempt": 1,
        "head_sha": "c" * 40, "head_branch": "feature/hud", "head_repository": "The-Plum-Team/Quick-Skin-Mod",
        "tested_sha": tested_sha, "tree_sha": "d" * 40, "pull_request": 7, "base_sha": "a" * 40}
    return {"schema_version": 1, "kind": "quick-skin-merged-source", "repository": source["repository"],
        "workflow": source["workflow"], "coverage_sha": covered_sha, "source": source,
        "seal_artifact": {"id": 999, "name": "tested-source-e2e", "size_in_bytes": 1024,
            "digest": "sha256:" + "e" * 64, "expired": False,
            "workflow_run": {"id": run_id, "head_sha": source["head_sha"], "head_branch": source["head_branch"]}}}


class FeaturePagesTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PagesSiteTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root.resolve()
        self.key = "mc1.20.1"
        self.fixture.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        self.baseline_sha, self.source_sha = "a" * 40, "b" * 40
        plan = feature.select(self.fixture.catalog.contract, feature.load_graph(),
            ["modules/hud-preview/src/main/java/example/Feature.java"], "pr")
        self.selection = feature.admission.Admission(self.baseline_sha, self.source_sha, self.source_sha,
            "c" * 40, "d" * 40, "d" * 40, "e" * 64, "f" * 64, True, "pr", plan.reason, plan)
        self.feature = {"admission": self.selection.to_dict(), "coverage": {
            "schema_version": 1, "selective": True, "selection_sha256": self.selection.sha256,
            "baseline": {"source_sha": self.baseline_sha}}}
        self.prepare("baseline", self.baseline_sha, "42")
        evidence.compact_bundle(self.root / "baseline", self.root / "base-compact", self.key)
        catalog = load_catalog(selection=self.selection)
        e2e = self.root / "e2e-1.20.1"
        for path in list(e2e.glob("profiles/*/result.json")):
            result = json.loads(path.read_bytes())
            if result["scenario"] not in catalog.contract.scenarios_for_profile("pr"):
                shutil.rmtree(path.parent)
                continue
            result["selection_sha256"] = self.selection.sha256
            result["jar_sha256"] = hashlib.sha256(("new:" + result["artifact_node"]).encode()).hexdigest()
            for installed in result["installed_quickskin"]:
                installed["sha256"] = result["jar_sha256"]
            for role, report in result["reports"].items():
                chosen = self.selection.role(result["scenario"], role)
                report["selection_sha256"] = self.selection.sha256
                report["steps"] = [step for step in report["steps"] if step["name"] in chosen.steps]
                for step in report["steps"]:
                    if step["name"] not in chosen.captures: step["screenshot"] = None
                metrics = report["pixel_validation"]
                metrics["screenshots"] = {name: item for name, item in metrics["screenshots"].items()
                                           if name in chosen.captures}
                metrics["comparisons"] = {name: item for name, item in metrics["comparisons"].items()
                                           if set(name.split("->")) <= set(chosen.captures)}
                for image in (path.parent / role / "screenshots").iterdir():
                    if image.stem not in chosen.captures: image.unlink()
            path.write_text(json.dumps(result))
        self.prepare("selected", self.source_sha, "55", self.feature)
        evidence.compact_bundle(self.root / "selected", self.root / "selected-compact", self.key,
                                 selection=self.selection)

    def prepare(self, output, source, run, selection=None, runtime_source=None):
        return evidence.prepare(e2e_root=self.root / "e2e-1.20.1", matrix_path=evidence.DEFAULT_MATRIX,
            catalog_path=evidence.DEFAULT_CATALOG, output_root=self.root / output,
            repository="The-Plum-Team/Quick-Skin-Mod", source_run_id=run,
            source_branch=runtime_source["source"]["head_branch"] if runtime_source else "master",
            source_sha=source, source_created_at="2026-09-06T02:00:00Z", target_run_id="66" if runtime_source else run,
            target_branch="master", target_sha=runtime_source["coverage_sha"] if runtime_source else source,
            target_created_at="2026-09-06T02:00:00Z", minecraft_target="1.20.1",
            feature_selection=selection, runtime_source=runtime_source)

    def test_reused_full_public_bundle_keeps_original_identity_through_compaction(self):
        shutil.rmtree(self.root / "evidence/forge-and-fabric-1.20.1")
        self.fixture.write_branch("forge-and-fabric-1.20.1", "1.20.1")
        reference = merged_reference(self.source_sha, "f" * 40, 55)
        raw = self.prepare("reused-full", self.source_sha, "55", runtime_source=reference)
        compact = evidence.compact_bundle(raw.parent, self.root / "reused-full-compact", self.key)
        value = evidence.validate_bundle(compact.parent, self.key, expected_kind="compact")
        self.assertEqual(reference, value["runtime_source"])
        self.assertEqual((self.source_sha, "55"),
                         (value["provenance"]["source"]["sha"], value["provenance"]["source"]["run_id"]))
        self.assertEqual(("f" * 40, "66"),
                         (value["provenance"]["target"]["sha"], value["provenance"]["target"]["run_id"]))
        value["provenance"]["source"]["sha"] = "f" * 40
        (compact / "manifest.json").write_text(json.dumps(value))
        with self.assertRaisesRegex(evidence.PublicEvidenceError, "original tested"):
            evidence.validate_bundle(compact.parent, self.key)

    def test_reused_selected_public_bundle_composes_without_relabelling_older_frames(self):
        self.selection = replace(self.selection, policy_commit=self.baseline_sha)
        self.feature["admission"] = self.selection.to_dict()
        self.feature["coverage"]["selection_sha256"] = self.selection.sha256
        for path in (self.root / "e2e-1.20.1").glob("profiles/*/result.json"):
            result = json.loads(path.read_bytes())
            result["selection_sha256"] = self.selection.sha256
            for report in result["reports"].values(): report["selection_sha256"] = self.selection.sha256
            path.write_text(json.dumps(result))
        reference = merged_reference(self.source_sha, "f" * 40, 55)
        raw = self.prepare("reused-selected", self.source_sha, "55", self.feature, reference)
        compact = evidence.compact_bundle(raw.parent, self.root / "reused-selected-compact", self.key,
                                          selection=self.selection)
        bundle = feature.compose(self.root / "base-compact", compact.parent, self.root / "reused-composed",
                                  self.key, selection=self.selection, coverage_sha="f" * 40)
        value = evidence.validate_bundle(bundle.parent, self.key, expected_kind="compact")
        self.assertEqual(reference, value["runtime_source"])
        selected = [frame for frame in value["frames"] if frame["evidence_epoch"] == "selected"]
        older = [frame for frame in value["frames"] if frame["evidence_epoch"] == "baseline"]
        self.assertEqual(4, len(selected))
        self.assertEqual({self.source_sha}, {frame["tested_provenance"]["source"]["sha"] for frame in selected})
        self.assertEqual({self.baseline_sha}, {frame["tested_provenance"]["source"]["sha"] for frame in older})
        self.assertEqual({"f" * 40}, {frame["coverage_sha"] for frame in value["frames"]})

    def compose(self, name="composed"):
        return feature.compose(self.root / "base-compact", self.root / "selected-compact",
                                self.root / name, self.key, selection=self.selection)

    def test_composition_keeps_full_coverage_and_original_frame_run_jar_and_pixels(self):
        bundle = self.compose()
        value = evidence.validate_bundle(bundle.parent, self.key, expected_kind="compact")
        self.assertEqual(180, len(value["frames"]))
        self.assertEqual(4, sum(frame["evidence_epoch"] == "selected" for frame in value["frames"]))
        self.assertEqual({self.baseline_sha, self.source_sha},
                         {frame["tested_provenance"]["target"]["sha"] for frame in value["frames"]})
        self.assertEqual({self.source_sha}, {frame["coverage_sha"] for frame in value["frames"]})
        self.assertFalse(list(bundle.rglob("*.png")))
        build(evidence_root=bundle.parent, output=self.root / "site", repository=value["repository"],
              require_compact=True, expected_branches={self.key})
        gallery = json.loads((self.root / "site/e2e/gallery-data.json").read_bytes())
        selected = [frame for frame in gallery["frames"] if frame["evidence_epoch"] == "selected"]
        previous = [frame for frame in gallery["frames"] if frame["evidence_epoch"] == "baseline"]
        self.assertEqual({self.source_sha}, {frame["target_sha"] for frame in selected})
        self.assertEqual({self.baseline_sha}, {frame["target_sha"] for frame in previous})
        self.assertEqual({"55"}, {frame["target_run_url"].rsplit("/", 1)[-1] for frame in selected})
        self.assertEqual({"42"}, {frame["target_run_url"].rsplit("/", 1)[-1] for frame in previous})
        lanes = {row["lane_id"]: row for row in gallery["lanes"]}
        for frame in gallery["frames"]:
            self.assertEqual(frame["jar_sha256"], lanes[frame["lane_id"]]["jar_sha256"])

    def test_partial_raw_and_compact_bundles_require_independent_admission(self):
        for root in ("selected", "selected-compact"):
            with self.subTest(root=root), self.assertRaisesRegex(evidence.PublicEvidenceError, "independently admitted"):
                evidence.validate_bundle(self.root / root, self.key)
        original = copy.deepcopy(self.feature)
        original["admission"]["selection"]["runs"][0]["roles"][0]["captures"] = []
        with self.assertRaises(ValueError): feature.read_selection(original)

    def test_composition_rejects_substituted_components_provenance_and_recursive_history(self):
        bundle = self.compose()
        manifest_path = bundle / "manifest.json"
        original = json.loads(manifest_path.read_bytes())
        for field, value in (("components", {"baseline_sha256": "0" * 64, "selected_sha256": "0" * 64}),
                             ("provenance", {**original["provenance"], "coverage_sha": "f" * 40}),
                             ("extra", True)):
            with self.subTest(field=field):
                manifest_path.write_text(json.dumps({**original, field: value}))
                with self.assertRaises(evidence.PublicEvidenceError): evidence.validate_bundle(bundle.parent, self.key)
        manifest_path.write_text(json.dumps(original))
        with self.assertRaises(evidence.PublicEvidenceError):
            feature.compose(bundle.parent, self.root / "selected-compact", self.root / "recursive", self.key,
                            selection=self.selection)
        image = next((bundle / "baseline").rglob("*.webp"))
        image.write_bytes(b"not an image")
        with self.assertRaises(evidence.PublicEvidenceError): evidence.validate_bundle(bundle.parent, self.key)


if __name__ == "__main__":
    unittest.main()
