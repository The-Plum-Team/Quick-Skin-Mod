from __future__ import annotations

import copy
import hashlib
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import compatibility_evidence as evidence
import collect_compatibility as collector
import mod_compatibility
from test_feature_pages import merged_reference


class CompatibilityRuntimeProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.source = "a" * 40
        self.reference = merged_reference("b" * 40, self.source, 54)
        self.contract = evidence.load_compatibility_contract()
        self.scenarios = evidence.load_scenario_contract()
        self.plan = mod_compatibility.build_plan(evidence.DEFAULT_MATRIX,
            base_matrix_kind="pr-anchors", minecraft_target="1.21.6")
        self.plan.update(source_run_id=55, source_branch="master", source_sha=self.source,
            target_branch="master", target_sha=self.source, runtime_source=self.reference)

    def validate_plan(self, plan=None):
        return evidence.validate_plan(self.plan if plan is None else plan, compatibility_run_id=56,
            contract=self.contract, scenario_contract=self.scenarios)

    def test_shared_plan_preserves_the_original_runtime_and_covered_generation(self):
        identity, rows, unavailable = self.validate_plan()
        self.assertEqual(self.reference, identity["runtime_source"])
        self.assertEqual((55, self.source), (identity["base_run_id"], identity["source_sha"]))
        self.assertEqual(54, identity["runtime_source"]["source"]["run_id"])
        self.assertEqual(len(identity["loaders"]) * len(self.contract.mods), len(rows) + len(unavailable))

    def test_shared_plan_rejects_foreign_malformed_or_non_pr_reuse(self):
        for mutate in (
            lambda p: p["runtime_source"].update(coverage_sha="f" * 40),
            lambda p: p["runtime_source"]["source"].update(run_id=True),
            lambda p: p.update(runtime_source=None),
            lambda p: p.update(base_matrix_kind="native-anchors"),
            lambda p: p.update(unrecognized_runtime={}),
            lambda p: p.update(schema_version=1),
        ):
            plan = copy.deepcopy(self.plan)
            mutate(plan)
            with self.subTest(plan=plan):
                with self.assertRaises(evidence.CompatibilityEvidenceError):
                    self.validate_plan(plan)

    def proof(self, row, manifest):
        base = {"id": 101, "name": "packaged-e2e-" + row["artifact_node"].replace(".", "_")
            + "--1_21_6--pr-behavior", "run_id": 54, "size_in_bytes": 123, "digest": "sha256:" + "d" * 64}
        return {"schema_version": 1, "kind": "quick-skin-mod-compatibility-review-input",
            "source_run_id": 55, "source_sha": self.source, "target_branch": "master",
            "target_sha": self.source, "compatibility_run_id": 56, "implementation_sha": self.source,
            "artifact_node": row["artifact_node"], "runtime_version": row["runtime_version"],
            "loader": row["loader"], "mod": row["compatibility_mod"], "mod_name": row["compatibility_name"],
            "mod_version": row["compatibility_version"], "mod_version_id": row["compatibility_version_id"],
            "scenario_contract_sha256": self.scenarios.sha256,
            "compatibility_contract_sha256": self.contract.sha256,
            "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(), "frame_count": 2,
            "artifact_inventory": {"base": base, "candidate": {**base, "id": 102, "run_id": 56,
                "name": "mod-compatibility-e2e-candidate"}}, "runtime_source": self.reference}

    def test_curation_binds_both_original_base_and_current_candidate_owners(self):
        identity, rows, _ = self.validate_plan()
        row = next(iter(rows.values()))
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            manifest.write_text("[]\n")
            proof = self.proof(row, manifest)
            arguments = dict(plan_row=row, identity=identity, implementation_sha=self.source,
                scenario_contract=self.scenarios, compatibility_contract=self.contract, manifest_path=manifest)
            self.assertEqual(proof, evidence.validate_curation_proof(proof, **arguments))
            for mutate in (
                lambda p: p.pop("runtime_source"),
                lambda p: p["runtime_source"].update(coverage_sha="f" * 40),
                lambda p: p["artifact_inventory"]["base"].update(run_id=55),
                lambda p: p["artifact_inventory"]["candidate"].update(run_id=54),
            ):
                changed = copy.deepcopy(proof)
                mutate(changed)
                with self.assertRaises(evidence.CompatibilityEvidenceError):
                    evidence.validate_curation_proof(changed, **arguments)

    def test_collection_reauthenticates_the_complete_base_and_rejects_removed_reference(self):
        api = SimpleNamespace(repository=self.reference["repository"])
        runtime = SimpleNamespace(reference=self.reference)
        with patch.object(collector.ci_reuse, "runtime_source", return_value=runtime) as authenticate:
            self.assertIs(runtime, collector._authenticate_base_runtime(api, self.plan))
            self.assertEqual((55, self.source), authenticate.call_args.args[1:])
            self.assertEqual("pr-anchors", authenticate.call_args.kwargs["matrix_kind"])
            changed = copy.deepcopy(self.plan)
            changed.pop("runtime_source")
            with self.assertRaisesRegex(collector.CollectionError, "authenticated base runtime"):
                collector._authenticate_base_runtime(api, changed)

    def test_collection_rejects_another_lane_or_substituted_base_artifact(self):
        _, rows, _ = self.validate_plan()
        row = next(iter(rows.values()))
        with tempfile.TemporaryDirectory() as temporary:
            manifest = Path(temporary) / "manifest.json"
            manifest.write_text("[]\n")
            proof = self.proof(row, manifest)
            base = proof["artifact_inventory"]["base"]
            runtime = SimpleNamespace(reference=self.reference, execution={"id": 54}, artifacts=[base])
            collector._validate_base_artifact(proof, row, runtime)
            for field, value in (("id", 103), ("digest", "sha256:" + "e" * 64), ("run_id", 55)):
                changed = copy.deepcopy(proof)
                changed["artifact_inventory"]["base"][field] = value
                with self.assertRaises(collector.CollectionError):
                    collector._validate_base_artifact(changed, row, runtime)
            with self.assertRaises(collector.CollectionError):
                collector._validate_base_artifact(proof, {**row, "runtime_version": "1.21.5"}, runtime)

    def test_runtime_descriptor_download_keeps_the_digest_and_size_limits(self):
        payload = b"authenticated descriptor"
        api = SimpleNamespace(repository=self.reference["repository"], _request=Mock())
        client = collector._RuntimeSourceClient(api)
        metadata = {"id": 101, "size_in_bytes": len(payload), "digest": "sha256:" + hashlib.sha256(payload).hexdigest()}
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "descriptor.zip"
            api._request.side_effect = lambda _path, **kw: kw["destination"].write_bytes(payload)
            client.download(metadata, destination, maximum=100)
            self.assertEqual(payload, destination.read_bytes())
            destination.unlink()
            with self.assertRaisesRegex(collector.CollectionError, "digest"):
                client.download({**metadata, "digest": "sha256:" + "e" * 64}, destination, maximum=100)
            self.assertFalse(destination.exists())
            api._request.reset_mock()
            with self.assertRaisesRegex(collector.CollectionError, "byte limit"):
                client.download(metadata, destination, maximum=1)
            api._request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
