"""The ``mc<version>`` public evidence targets of the shared-source release matrix.

``evidence_target`` stays Quick Skin's single derivation of target keys: the E2E producer matrix,
the mod-base adapter's ``targets`` hook, visual review and feature coverage all read it. Handoff
admission and retention belong to mod-base (``test_mod_base_adapter.py`` and the kit's own
suites); the optional-mod plans and native bundles below are keyed by the same targets.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import compatibility_evidence  # noqa: E402
import mod_base_fixtures  # noqa: E402
import mod_compatibility  # noqa: E402
from evidence_target import (  # noqa: E402
    DEFAULT_MATRIX,
    EvidenceTargetError,
    bundle_version,
    inventory,
    load_target,
    target_for_key,
)
from scenario_contract import load_contract  # noqa: E402
from test_feature_pages import merged_reference  # noqa: E402


MATRIX = json.loads(DEFAULT_MATRIX.read_bytes())
REPOSITORY = MATRIX["project"]["sources"].removeprefix("https://github.com/").rstrip("/")
SOURCE_SHA = "a" * 40


class SharedTargetsTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_every_matrix_target_has_one_independent_key_and_full_matrix_digest(self) -> None:
        versions = {row["artifact_version"] for row in MATRIX["artifacts"]}
        digest = hashlib.sha256(DEFAULT_MATRIX.read_bytes()).hexdigest()
        covered_nodes: set[str] = set()
        for version in versions:
            with self.subTest(version=version):
                target = target_for_key(f"mc{version}")
                self.assertEqual("master", target.branch)
                self.assertEqual(version, target.version)
                self.assertEqual(digest, target.matrix_sha256)
                nodes = {row["artifact_node"] for row in target.matrix["artifacts"]}
                self.assertFalse(nodes & covered_nodes)
                covered_nodes |= nodes
        self.assertEqual({row["artifact_node"] for row in MATRIX["artifacts"]}, covered_nodes)
        for invalid in ("master", "mc1.021.1", "mc1.21.1/other", "../mc1.21.1", "mc1.21.1--bad"):
            with self.subTest(invalid=invalid), self.assertRaises(EvidenceTargetError):
                bundle_version(invalid)
        with self.assertRaisesRegex(EvidenceTargetError, "explicit Minecraft target"):
            load_target(DEFAULT_MATRIX, "master")
        with self.assertRaisesRegex(EvidenceTargetError, "unknown release target"):
            target_for_key("mc99.1")
        with self.assertRaisesRegex(EvidenceTargetError, "source branch"):
            load_target(DEFAULT_MATRIX, "forge-and-fabric-1.20.1", minecraft_target="1.20.1")

    def test_inventory_rows_name_only_target_identities(self) -> None:
        rows = inventory()["include"]
        versions = sorted({row["artifact_version"] for row in MATRIX["artifacts"]},
                          key=lambda version: tuple(map(int, version.split("."))))
        self.assertEqual(17, len(rows))
        self.assertEqual([f"mc{version}" for version in versions], [row["bundle_key"] for row in rows])
        for row, version in zip(rows, versions):
            self.assertEqual({"bundle_key": f"mc{version}", "source_branch": "master", "minecraft_target": version,
                              "artifact_pattern": f"packaged-e2e-*--{version.replace('.', '_')}--pr-behavior"}, row)

    def test_cli_prints_the_matrix_keys_and_single_source_branch(self) -> None:
        def run(*arguments: str) -> str:
            return subprocess.run([sys.executable, "scripts/pages/evidence_target.py", *arguments], cwd=ROOT,
                                  check=True, capture_output=True, text=True).stdout.strip()

        self.assertEqual(inventory(), json.loads(run("--kind", "matrix")))
        self.assertEqual([row["bundle_key"] for row in inventory()["include"]], json.loads(run("--kind", "keys")))
        self.assertEqual("master", run("--kind", "source-branch"))
        retired = subprocess.run([sys.executable, "scripts/pages/evidence_target.py", "--validate-handoffs", "x"],
                                 cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(2, retired.returncode)

    def test_target_selection_rejects_an_invalid_unselected_lane(self) -> None:
        matrix = json.loads(json.dumps(MATRIX))
        matrix["runtimes"][-1]["port"] = 1
        path = self.root / "release/release-matrix.json"
        path.parent.mkdir()
        path.write_text(json.dumps(matrix))
        with self.assertRaisesRegex(EvidenceTargetError, "invalid canonical release matrix"):
            load_target(path, "master", minecraft_target="1.20.1")

    def test_optional_mod_plans_validate_every_target_and_bind_the_full_inventory(self) -> None:
        contract = mod_compatibility.load_contract()
        scenarios = load_contract()
        for version in {row["artifact_version"] for row in MATRIX["artifacts"]}:
            with self.subTest(version=version):
                plan = mod_compatibility.build_plan(DEFAULT_MATRIX, base_matrix_kind="native-anchors",
                                                     minecraft_target=version)
                plan.update(source_run_id=42, source_branch="master", source_sha=SOURCE_SHA,
                            target_branch="master", target_sha=SOURCE_SHA)
                identity, runnable, unavailable = compatibility_evidence.validate_plan(
                    plan, compatibility_run_id=43, contract=contract, scenario_contract=scenarios)
                self.assertEqual(f"mc{version}", identity["bundle_key"])
                self.assertEqual("master", identity["branch"])
                self.assertEqual(hashlib.sha256(DEFAULT_MATRIX.read_bytes()).hexdigest(), identity["matrix_sha256"])
                self.assertEqual(len(identity["loaders"]) * len(contract.mods), len(runnable) + len(unavailable))
                self.assertTrue(all(row["runtime_version"] == version for row in runnable.values()))
                plan["matrix_sha256"] = "0" * 64
                with self.assertRaisesRegex(compatibility_evidence.CompatibilityEvidenceError, "matrix hash"):
                    compatibility_evidence.validate_plan(plan, compatibility_run_id=43, contract=contract,
                                                         scenario_contract=scenarios)

    def test_shared_optional_mod_bundle_is_keyed_by_its_target_and_carried_only_by_coverage(self) -> None:
        root = self.root / "compatibility"
        bundle = root / "mc1.20.1"
        bundle.mkdir(parents=True)
        value = mod_base_fixtures.compatibility_bundle(
            bundle, key="mc1.20.1", repository=REPOSITORY, coverage_sha=SOURCE_SHA, target_sha=SOURCE_SHA,
            publication_run=None, publication_run_id=44,
            images=(mod_base_fixtures.fixture_png(0), mod_base_fixtures.fixture_png(1)))
        value["provenance"]["runtime_source"] = merged_reference("b" * 40, SOURCE_SHA, 41)
        path = bundle / "manifest.json"
        path.write_text(json.dumps(value))
        compatibility_evidence.validate_bundle(root, "mc1.20.1", expected_coverage_sha=SOURCE_SHA)
        carried = compatibility_evidence.carry_forward(
            evidence_root=root, output_root=self.root / "carried", branch="mc1.20.1", coverage_sha="c" * 40,
            expected_repository=REPOSITORY, scenario_contract_path=ROOT / "e2e/scenario-contract.json",
            compatibility_contract_path=ROOT / "e2e/mod-compatibility-contract.json")
        carried_provenance = json.loads((carried / "manifest.json").read_bytes())["provenance"]
        self.assertEqual(value["provenance"]["runtime_source"], carried_provenance["runtime_source"])
        self.assertEqual((SOURCE_SHA, "c" * 40), (carried_provenance["source_sha"], carried_provenance["coverage_sha"]))
        for field, foreign in (("coverage_sha", "f" * 40), ("repository", "foreign/repository")):
            reference = value["provenance"]["runtime_source"]
            original = reference[field]
            reference[field] = foreign
            path.write_text(json.dumps(value))
            with self.subTest(field=field), self.assertRaises(compatibility_evidence.CompatibilityEvidenceError):
                compatibility_evidence.validate_bundle(root, "mc1.20.1")
            reference[field] = original
        value["release"]["matrix_sha256"] = "0" * 64
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(compatibility_evidence.CompatibilityContractDriftError,
                                    "superseded release matrix"):
            compatibility_evidence.validate_bundle(root, "mc1.20.1")


if __name__ == "__main__":
    unittest.main()
