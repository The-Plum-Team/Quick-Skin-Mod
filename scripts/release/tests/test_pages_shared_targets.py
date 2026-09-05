from __future__ import annotations

import hashlib
import json
import shutil
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import test_pages_site as fixtures  # noqa: E402
import test_pages_artifact_rotation as artifact_fixtures  # noqa: E402
from build_site import SiteBuildError, build  # noqa: E402
from evidence import (  # noqa: E402
    PublicEvidenceError,
    compact_bundle,
    load_matrix_inventory,
    prepare,
    validate_bundle,
)
from evidence_target import (  # noqa: E402
    DEFAULT_MATRIX,
    EvidenceTargetError,
    bundle_version,
    load_target,
    target_for_key,
    inventory,
    validate_handoffs,
)
from rotate_artifacts import (  # noqa: E402
    E2E_WORKFLOW, PAGES_WORKFLOW, RotationError, BranchGeneration,
    load_generations, rotate_branch, rotate_generations,
)
from select_artifact import resolve_evidence, select_source  # noqa: E402
import select_artifact  # noqa: E402
import compatibility_evidence  # noqa: E402
import mod_compatibility  # noqa: E402
from visual_review import (  # noqa: E402
    VisualEvidenceError, load_reference_frames, reference_identity,
)


MATRIX = json.loads(DEFAULT_MATRIX.read_bytes())
REPOSITORY = MATRIX["project"]["sources"].removeprefix("https://github.com/").rstrip("/")
SOURCE_SHA = "a" * 40


class SharedPagesTargetsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = fixtures.PagesSiteTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root

    def prepare_target(self, version: str, *, output: str = "shared") -> Path:
        return prepare(
            e2e_root=self.root / "combined",
            matrix_path=DEFAULT_MATRIX,
            catalog_path=ROOT / "e2e/scenario-contract.json",
            output_root=self.root / output,
            repository=REPOSITORY,
            source_run_id="42", source_branch="master", source_sha=SOURCE_SHA,
            source_created_at="2026-09-05T12:00:00Z",
            target_run_id="42", target_branch="master", target_sha=SOURCE_SHA,
            target_created_at="2026-09-05T12:00:00Z",
            minecraft_target=version,
        )

    def write_two_targets(self) -> None:
        for version, branch in (
            ("1.20.1", "forge-and-fabric-1.20.1"),
            ("1.21.1", "neoforge-and-fabric-1.21.1"),
        ):
            self.fixture.write_branch(branch, version)
            shutil.copytree(self.root / f"e2e-{version}", self.root / "combined", dirs_exist_ok=True)

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

    def test_target_selection_rejects_an_invalid_unselected_lane(self) -> None:
        matrix = json.loads(json.dumps(MATRIX))
        matrix["runtimes"][-1]["port"] = 1
        path = self.root / "release/release-matrix.json"
        path.parent.mkdir()
        path.write_text(json.dumps(matrix))
        with self.assertRaisesRegex(PublicEvidenceError, "invalid canonical release matrix"):
            load_matrix_inventory(path, "master", self.fixture.catalog.contract, minecraft_target="1.20.1")

    def test_two_targets_roundtrip_to_independent_compact_bundles_and_one_gallery(self) -> None:
        self.write_two_targets()
        keys = {"mc1.20.1", "mc1.21.1"}
        for version in ("1.20.1", "1.21.1"):
            bundle = self.prepare_target(version)
            manifest = validate_bundle(self.root / "shared", bundle.name, expected_target_sha=SOURCE_SHA)
            self.assertEqual(3, manifest["schema_version"])
            self.assertEqual("master", manifest["release"]["branch"])
            self.assertEqual(180, len(manifest["frames"]))
            self.assertEqual({version}, {row["version"] for row in manifest["lanes"]})
            compact_bundle(self.root / "shared", self.root / "compact", bundle.name, only_branch=False)
            compact = validate_bundle(self.root / "compact", bundle.name, expected_kind="compact")
            self.assertEqual(4, compact["schema_version"])
            self.assertEqual(manifest["release"], compact["release"])
            self.assertFalse(list((self.root / "compact" / bundle.name).rglob("*.png")))
        caches = [artifact_fixtures.artifact(
            index + 100, f"pages-cache-{key}--{SOURCE_SHA}", "2026-09-05T13:00:00Z",
            run_id=900, head_branch="master", head_sha="b" * 40,
        ) for index, key in enumerate(sorted(keys))]
        generations = load_generations(evidence_root=self.root / "compact", repository=REPOSITORY,
                                       pages_run_id=900, pages_run_sha="b" * 40, trigger_artifacts=caches)
        self.assertEqual(keys, {generation.key for generation in generations})
        self.assertEqual({"master"}, {generation.branch for generation in generations})
        output = self.root / "site"
        build(evidence_root=self.root / "compact", output=output, repository=REPOSITORY,
              require_compact=True, expected_branches=keys)
        gallery = json.loads((output / "e2e/gallery-data.json").read_bytes())
        self.assertEqual(360, len(gallery["frames"]))
        self.assertEqual({"1.20.1", "1.21.1"}, {row["version"] for row in gallery["releases"]})
        self.assertEqual({SOURCE_SHA}, {row["target_sha"] for row in gallery["releases"]})
        self.assertEqual({"master"}, {row["target_branch"] for row in gallery["releases"]})
        manifest_path = self.root / "compact/mc1.21.1/manifest.json"
        changed = json.loads(manifest_path.read_bytes())
        changed["provenance"]["coverage_sha"] = "b" * 40
        manifest_path.write_text(json.dumps(changed))
        with self.assertRaisesRegex(SiteBuildError, "different source commits"):
            build(evidence_root=self.root / "compact", output=self.root / "mixed-heads",
                  repository=REPOSITORY, require_compact=True, expected_branches=keys)

    def test_target_projection_skips_other_images_but_rejects_malformed_result_metadata(self) -> None:
        self.write_two_targets()
        for path in (self.root / "combined/profiles").glob("*1.21.1*/*/screenshots/*.png"):
            path.unlink()
        self.prepare_target("1.20.1")
        other_result = next((self.root / "combined/profiles").glob("*1.21.1*/result.json"))
        value = json.loads(other_result.read_bytes())
        value["profile"] = "profiles/elsewhere"
        other_result.write_text(json.dumps(value))
        with self.assertRaisesRegex(PublicEvidenceError, "profile identity mismatch"):
            self.prepare_target("1.20.1", output="invalid")

    def test_visual_reference_uses_the_target_key_and_real_source_branch(self) -> None:
        self.write_two_targets()
        bundle = self.prepare_target("1.20.1")
        identity = reference_identity(DEFAULT_MATRIX)
        self.assertEqual("master", identity["release_branch"])
        self.assertEqual("mc1.20.1", identity["bundle_key"])
        arguments = dict(branch=identity["release_branch"], artifact_node=identity["artifact_node"],
                         bundle_key=identity["bundle_key"])
        references = load_reference_frames(self.root / "shared", ROOT / "e2e/scenario-contract.json", **arguments)
        self.assertEqual(90, len(references))
        self.assertTrue(all(row["label"].startswith("fabric-1.20.1/") for row in references.values()))
        compact_bundle(self.root / "shared", self.root / "reference-compact", bundle.name)
        with self.assertRaisesRegex(VisualEvidenceError, "lossless raw PNG"):
            load_reference_frames(self.root / "reference-compact", ROOT / "e2e/scenario-contract.json",
                                  **arguments)
        path = bundle / "manifest.json"
        original = json.loads(path.read_bytes())
        for field, value in (("matrix_sha256", "0" * 64), ("version", "1.21.1"),
                             ("artifacts", original["release"]["artifacts"][:1]), ("branch", "mc1.20.1")):
            with self.subTest(field=field):
                changed = json.loads(json.dumps(original))
                changed["release"][field] = value
                path.write_text(json.dumps(changed))
                with self.assertRaises(VisualEvidenceError):
                    load_reference_frames(self.root / "shared", ROOT / "e2e/scenario-contract.json", **arguments)
        path.write_text(json.dumps(original))
        with self.assertRaisesRegex(VisualEvidenceError, "release identity"):
            load_reference_frames(self.root / "shared", ROOT / "e2e/scenario-contract.json",
                                  branch="mc1.20.1", artifact_node=identity["artifact_node"])

    def test_shared_bundle_rejects_wrong_matrix_target_and_branch_identities(self) -> None:
        self.write_two_targets()
        bundle = self.prepare_target("1.20.1")
        path = bundle / "manifest.json"
        original = json.loads(path.read_bytes())
        mutations = (
            ("matrix_sha256", "0" * 64, "matrix hash mismatch"),
            ("version", "1.21.1", "version does not match"),
            ("branch", "forge-and-fabric-1.20.1", "branch mismatch"),
            ("artifacts", original["release"]["artifacts"][:1], "exact matrix target"),
        )
        for key, value, error in mutations:
            with self.subTest(key=key):
                changed = json.loads(json.dumps(original))
                changed["release"][key] = value
                path.write_text(json.dumps(changed))
                with self.assertRaisesRegex(PublicEvidenceError, error):
                    validate_bundle(self.root / "shared", "mc1.20.1")
        changed = json.loads(json.dumps(original))
        changed["provenance"]["source"]["sha"] = "b" * 40
        path.write_text(json.dumps(changed))
        with self.assertRaisesRegex(PublicEvidenceError, "one tested source commit"):
            validate_bundle(self.root / "shared", "mc1.20.1")

    def test_optional_mod_plans_validate_every_target_and_bind_the_full_inventory(self) -> None:
        contract = mod_compatibility.load_contract()
        for version in {row["artifact_version"] for row in MATRIX["artifacts"]}:
            with self.subTest(version=version):
                plan = mod_compatibility.build_plan(DEFAULT_MATRIX, base_matrix_kind="native-anchors",
                                                     minecraft_target=version)
                plan.update(source_run_id=42, source_branch="master", source_sha=SOURCE_SHA,
                            target_branch="master", target_sha=SOURCE_SHA)
                identity, runnable, unavailable = compatibility_evidence.validate_plan(
                    plan, compatibility_run_id=43, contract=contract,
                    scenario_contract=self.fixture.catalog.contract,
                )
                self.assertEqual(f"mc{version}", identity["bundle_key"])
                self.assertEqual("master", identity["branch"])
                self.assertEqual(hashlib.sha256(DEFAULT_MATRIX.read_bytes()).hexdigest(),
                                 identity["matrix_sha256"])
                self.assertEqual(len(identity["loaders"]) * len(contract.mods), len(runnable) + len(unavailable))
                self.assertTrue(all(row["runtime_version"] == version for row in runnable.values()))
                plan["matrix_sha256"] = "0" * 64
                with self.assertRaisesRegex(compatibility_evidence.CompatibilityEvidenceError, "matrix hash"):
                    compatibility_evidence.validate_plan(plan, compatibility_run_id=43, contract=contract,
                                                         scenario_contract=self.fixture.catalog.contract)

    def test_shared_optional_mod_bundle_publishes_under_its_target_key(self) -> None:
        self.write_two_targets()
        bundle = self.prepare_target("1.20.1")
        compact_bundle(self.root / "shared", self.root / "compact", bundle.name)
        compatibility_root = self.fixture.write_compatibility_bundle("forge-and-fabric-1.20.1")
        old_bundle = compatibility_root / "forge-and-fabric-1.20.1"
        destination = compatibility_root / "mc1.20.1"
        old_bundle.rename(destination)
        path = destination / "manifest.json"
        value = json.loads(path.read_bytes())
        value["schema_version"] = compatibility_evidence.SHARED_SCHEMA_VERSION
        value["repository"] = REPOSITORY
        value["release"].update(branch="master", loaders=list(target_for_key("mc1.20.1").loaders),
                                matrix_sha256=hashlib.sha256(DEFAULT_MATRIX.read_bytes()).hexdigest())
        value["provenance"].update(source_sha=SOURCE_SHA, target_sha=SOURCE_SHA, coverage_sha=SOURCE_SHA)
        path.write_text(json.dumps(value))
        compatibility_evidence.validate_bundle(compatibility_root, "mc1.20.1",
                                                expected_coverage_sha=SOURCE_SHA)
        output = self.root / "compatibility-site"
        build(evidence_root=self.root / "compact", compatibility_root=compatibility_root, output=output,
              repository=REPOSITORY, require_compact=True, expected_branches={"mc1.20.1"})
        gallery = json.loads((output / "e2e/gallery-data.json").read_bytes())
        self.assertTrue(gallery["compatibility"]["lanes"])
        value["release"]["matrix_sha256"] = "0" * 64
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(compatibility_evidence.CompatibilityEvidenceError, "matrix hash"):
            compatibility_evidence.validate_bundle(compatibility_root, "mc1.20.1")


class SharedPagesSelectionTest(unittest.TestCase):
    def api(self, *, cache: bool = False, legacy_cache_name: bool = False):
        name = (
            "pages-cache-mc1.20.1" + ("" if legacy_cache_name else f"--{SOURCE_SHA}")
            if cache else "pages-e2e-mc1.20.1"
        )
        artifact = artifact_fixtures.artifact(
            7, name, "2026-09-05T12:00:00Z", run_id=42,
            head_branch="master", head_sha=SOURCE_SHA,
        )
        return artifact_fixtures.FakeApi(
            keep=artifact, inventories={name: [artifact]},
            runs={42: artifact_fixtures.run(
                42, workflow=PAGES_WORKFLOW if cache else E2E_WORKFLOW,
                event="workflow_dispatch", branch="master", sha=SOURCE_SHA,
            )}, branch_shas={"master": SOURCE_SHA},
        )

    def test_target_key_selects_only_its_exact_source_run_or_sha_named_cache(self) -> None:
        for cache in (False, True):
            with self.subTest(cache=cache):
                api = self.api(cache=cache)
                selected = select_source(api, repository=artifact_fixtures.REPOSITORY,
                                         branch="master", current_sha=SOURCE_SHA, bundle_key="mc1.20.1")
                self.assertEqual(7, selected.artifact_id)
                with self.assertRaises(RotationError):
                    select_source(api, repository=artifact_fixtures.REPOSITORY, branch="master",
                                  current_sha=SOURCE_SHA, bundle_key="mc1.21.1")
                with self.assertRaises(RotationError):
                    select_source(api, repository=artifact_fixtures.REPOSITORY, branch="master",
                                  current_sha="b" * 40, bundle_key="mc1.20.1")
        with self.assertRaises(RotationError):
            select_source(self.api(cache=True, legacy_cache_name=True),
                          repository=artifact_fixtures.REPOSITORY, branch="master",
                          current_sha=SOURCE_SHA, bundle_key="mc1.20.1")

    def test_shared_evidence_rejects_unauthenticated_continuation_and_wrong_owner(self) -> None:
        api = self.api()
        with self.assertRaisesRegex(RotationError, "protected coverage proof"):
            resolve_evidence(api, repository=artifact_fixtures.REPOSITORY, branch="master",
                             current_sha=SOURCE_SHA, bundle_key="mc1.20.1", allow_continuation=True)
        self.assertEqual(0, api.commit_requests)
        api.runs[42]["head_branch"] = "forge-and-fabric-1.20.1"
        with self.assertRaises(RotationError):
            select_source(api, repository=artifact_fixtures.REPOSITORY, branch="master",
                          current_sha=SOURCE_SHA, bundle_key="mc1.20.1", require_raw=True)

    def test_cli_uses_the_real_branch_for_current_head_lookup(self) -> None:
        with patch.object(select_artifact, "GitHubApi", return_value=self.api()), patch.dict(
            select_artifact.os.environ, {"GH_TOKEN": "local-fixture"}
        ):
            self.assertEqual(0, select_artifact.main([
                "--repository", artifact_fixtures.REPOSITORY, "--branch", "master",
                "--bundle-key", "mc1.20.1", "--probe", "--require-raw",
                "--expected-source-sha", SOURCE_SHA,
            ]))

    def test_rotation_waits_for_success_and_uses_source_refs_and_separate_target_keys(self) -> None:
        keys = ("mc1.20.1", "mc1.21.1")
        caches = [artifact_fixtures.artifact(
            i + 100, f"pages-cache-{key}--{SOURCE_SHA}", "2026-09-05T13:00:00Z",
            run_id=900, head_branch="master", head_sha="b" * 40,
        ) for i, key in enumerate(keys)]
        handoffs = [artifact_fixtures.artifact(
            i + 10, f"pages-e2e-{key}", "2026-09-05T12:00:00Z",
            run_id=42, head_branch="master", head_sha=SOURCE_SHA,
        ) for i, key in enumerate(keys)]
        api = artifact_fixtures.FakeApi(
            keep=caches[0], inventories={item.name: [item] for item in [*caches, *handoffs]},
            runs={
                900: artifact_fixtures.run(900, workflow=PAGES_WORKFLOW, event="workflow_dispatch",
                                          branch="master", sha="b" * 40),
                42: artifact_fixtures.run(42, workflow=E2E_WORKFLOW, event="workflow_dispatch",
                                         branch="master", sha=SOURCE_SHA),
            }, branch_shas={"master": SOURCE_SHA},
        )
        generations = [BranchGeneration(branch="master", target_sha=SOURCE_SHA, coverage_sha=SOURCE_SHA,
                                        target_run_id=42, keep=cache, bundle_key=key)
                       for key, cache in zip(keys, caches)]
        api.runs[900]["status"] = "in_progress"
        with self.assertRaises(RotationError):
            rotate_branch(api, generations[1], repository=artifact_fixtures.REPOSITORY,
                          pages_run_id=900, pages_run_sha="b" * 40, delete_delay_seconds=0)
        self.assertFalse(api.deleted)
        api.runs[900]["status"] = "completed"
        rotated, deferred = rotate_generations(
            api, generations, repository=artifact_fixtures.REPOSITORY, pages_run_id=900,
            pages_run_sha="b" * 40, delete_delay_seconds=0, preserve_handoff_branch=keys[0],
        )
        self.assertEqual({keys[0]: [], keys[1]: [handoffs[1].artifact_id]}, rotated)
        self.assertFalse(deferred)
        self.assertEqual([handoffs[1].artifact_id], api.deleted)

    def test_wake_admission_requires_the_complete_exact_target_inventory(self) -> None:
        artifacts = [{
            "id": index + 1, "name": f"pages-e2e-{row['bundle_key']}",
            "expired": False, "size_in_bytes": 100,
            "workflow_run": {"id": 42, "head_branch": "master", "head_sha": SOURCE_SHA},
        } for index, row in enumerate(inventory()["include"])]
        def validate(rows):
            validate_handoffs([{"artifacts": rows}], matrix_path=DEFAULT_MATRIX,
                              source_branch="master", source_sha=SOURCE_SHA, source_run_id=42)
        validate(artifacts)
        with self.assertRaisesRegex(EvidenceTargetError, "incomplete public target"):
            validate(artifacts[:-1])
        with self.assertRaisesRegex(EvidenceTargetError, "unexpected target or owner"):
            validate([*artifacts, artifacts[0]])
        for label in ("same-id", "wrong-sha", "wrong-branch", "wrong-run", "expired", "unknown-target"):
            changed = json.loads(json.dumps(artifacts))
            if label == "same-id": changed[-1]["id"] = changed[0]["id"]
            elif label == "wrong-sha": changed[-1]["workflow_run"]["head_sha"] = "b" * 40
            elif label == "wrong-branch": changed[-1]["workflow_run"]["head_branch"] = "mc1.20.1"
            elif label == "wrong-run": changed[-1]["workflow_run"]["id"] = 43
            elif label == "expired": changed[-1]["expired"] = True
            else: changed[-1]["name"] = "pages-e2e-mc99.1"
            with self.subTest(label=label), self.assertRaises(EvidenceTargetError):
                validate(changed)


if __name__ == "__main__":
    unittest.main()
