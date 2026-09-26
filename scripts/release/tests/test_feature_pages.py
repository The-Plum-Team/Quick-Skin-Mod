"""Feature-selected public evidence: the recomputed selection, its scope detail and the producer's
runtime identity. The composed bundle itself is re-verified by mod-base's R3 in
``test_mod_base_adapter.py`` (``ComposedEvidenceTest``)."""

from __future__ import annotations

import copy
import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from dataclasses import replace
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/ci/tests"))

import mod_base_path  # noqa: E402

mod_base_path.kit_root()

import e2e_selection  # noqa: E402
import feature_evidence as feature  # noqa: E402
import feature_pages as pages  # noqa: E402
import ci_reuse  # noqa: E402
from scenario_contract import load_contract  # noqa: E402
from selection import project_contract  # noqa: E402
from test_ci_reuse import FixtureApi  # noqa: E402

HUD_SOURCE = "modules/hud-preview/src/main/java/example/Feature.java"


class SelectiveFixtureApi(FixtureApi):
    """``FixtureApi`` whose commits carry the executing selection policy, so the protected Git
    admission is recomputed for real; its base and pull-request head differ only in one
    ``hud-preview`` source file (the only selective module today)."""

    def commit(self, message, *parents):
        for name in e2e_selection.POLICY_PATHS:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        (self.root / "runtime.txt").write_text("runtime\n")
        source = self.root / HUD_SOURCE
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text("feature\n" if parents else "base\n")
        self.git("add", "-A")
        return super().commit(message, *parents)

    def add_selection(self, run_id, identifier, admission):
        """The ``e2e-feature-selection`` artifact a selective execution uploads."""

        coverage = {"schema_version": 1, "selective": True, "selection_sha256": admission.sha256,
                    "baseline": {"source_sha": admission.base_commit}}
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("selection.json", admission.to_bytes())
            archive.writestr("coverage.json", e2e_selection.canonical(coverage))
        artifact = self.add_artifact(run_id, identifier, "e2e-feature-selection")
        self.archives[identifier] = buffer.getvalue()
        artifact.update(size_in_bytes=len(buffer.getvalue()),
                        digest="sha256:" + hashlib.sha256(buffer.getvalue()).hexdigest())


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


def contract_shape(contract):
    """The scenario/role/step/capture/comparison identity of a (projected) contract view."""

    return [(scenario.scenario, [(role.role, [step.id for step in role.steps],
                                  [step.id for step in role.steps if step.capture is not None],
                                  [(pair.first_step, pair.second_step) for pair in role.comparisons])
                                 for role in scenario.roles]) for scenario in contract.scenarios]


class FeatureSelectionTest(unittest.TestCase):
    def setUp(self):
        self.contract = load_contract()
        self.baseline_sha, self.source_sha = "a" * 40, "b" * 40
        plan = feature.select(self.contract, feature.load_graph(),
            ["modules/hud-preview/src/main/java/example/Feature.java"], "pr")
        self.selection = feature.admission.Admission(self.baseline_sha, self.source_sha, self.source_sha,
            "c" * 40, "d" * 40, "d" * 40, "e" * 64, "f" * 64, True, "pr", plan.reason, plan)
        self.feature = {"admission": self.selection.to_dict(), "coverage": {
            "schema_version": 1, "selective": True, "selection_sha256": self.selection.sha256,
            "baseline": {"source_sha": self.baseline_sha}}}

    def test_selection_is_recomputed_from_the_admitted_changed_paths(self):
        chosen = feature.read_selection(self.feature)
        self.assertEqual(self.selection.to_bytes(), chosen.to_bytes())
        self.assertEqual("affected", chosen.require_selection().mode)

    def test_forged_obligations_coverage_or_admission_kind_are_refused(self):
        mutations = (
            lambda value: value["admission"]["selection"]["runs"][0]["roles"][0].update(captures=[]),
            lambda value: value["coverage"].update(selection_sha256="0" * 64),
            lambda value: value["coverage"]["baseline"].update(source_sha=self.source_sha),
            lambda value: value["admission"].update(input_kind="local-path-preview"),
            lambda value: value["admission"].update(diff_complete=False),
            lambda value: value.update(extra=True),
        )
        for mutate in mutations:
            value = copy.deepcopy(self.feature)
            mutate(value)
            with self.subTest(value=value), self.assertRaises(ValueError):
                feature.read_selection(value)

    def test_scope_detail_is_bounded_by_the_contract_and_reprojects_exactly(self):
        detail = feature.selection_detail(self.selection)
        self.assertEqual({"selection_sha256", "contract_sha256", "profile", "runs", "reference_captures"}, set(detail))
        self.assertEqual(self.selection.sha256, detail["selection_sha256"])
        paths = tuple(f"docs/{index}.md" for index in range(5000))
        wide = replace(self.selection, selection=replace(self.selection.selection, changed_paths=paths))
        self.assertEqual(json.dumps(feature.selection_detail(wide)["runs"]), json.dumps(detail["runs"]))
        self.assertLess(len(json.dumps(detail)), 64 * 1024)
        view = feature.SelectionView.from_detail(detail)
        self.assertEqual(contract_shape(project_contract(self.contract, self.selection)),
                         contract_shape(project_contract(self.contract, view)))
        for run in view.runs:
            for role in run.roles:
                self.assertEqual(self.selection.role(run.scenario, role.role), view.role(run.scenario, role.role))
        for mutate in (lambda value: value.update(profile="release"), lambda value: value.update(runs=[]),
                       lambda value: value["runs"][0]["roles"][0].update(steps=["../x"]),
                       lambda value: value.update(selection_sha256="A" * 64), lambda value: value.update(extra=1)):
            broken = copy.deepcopy(detail)
            mutate(broken)
            with self.subTest(detail=broken), self.assertRaises(feature.FeatureEvidenceError):
                feature.SelectionView.from_detail(broken)


class BaselineGenerationTest(unittest.TestCase):
    """The retained baseline must be the generation the coverage certificate names."""

    BASE = "a" * 40

    def manifest(self, *, handoff=42, tested=42, reuse="none", commit=BASE, kind="handoff"):
        return {"subject": {"branch": "master", "commit": commit}, "source_artifact": {"kind": kind},
                "provenance": {"handoff": {"run_id": handoff}, "tested": {"run_id": tested}, "reuse": reuse,
                               "coverage_sha": commit}}

    def check(self, manifest, *, generation=42, tested=42):
        feature.require_baseline_generation(manifest, branch="master", base_commit=self.BASE,
                                            baseline_run_id=generation, baseline_tested_run_id=tested)

    def test_the_certified_generation_or_a_sibling_reusing_the_same_execution_is_accepted(self):
        self.check(self.manifest())
        # Two master generations of one head (30 and 31) reused pull-request run 20: mod-base retained
        # mb-baseline--<key>--<head>--20 once, from generation 30, and the certificate names 31.
        self.check(self.manifest(handoff=30, tested=20, reuse="delegated"), generation=31, tested=20)
        self.check(self.manifest(handoff=31, tested=20, reuse="delegated"), generation=31, tested=20)

    def test_another_generation_tested_run_commit_or_source_is_refused(self):
        cases = {
            "fresh sibling": (self.manifest(handoff=41, tested=41), {}),
            "fresh other generation": (self.manifest(), {"generation": 41}),
            "other tested run": (self.manifest(handoff=30, tested=20, reuse="delegated"),
                                 {"generation": 31, "tested": 21}),
            "attested sibling": (self.manifest(handoff=30, tested=20, reuse="attested"),
                                 {"generation": 31, "tested": 20}),
            "other commit": (self.manifest(commit="b" * 40), {}),
            "republished cache": (self.manifest(kind="cache"), {}),
        }
        for name, (manifest, options) in cases.items():
            with self.subTest(case=name), self.assertRaisesRegex(feature.FeatureEvidenceError, "admitted against"):
                self.check(manifest, **options)
        branch = self.manifest()
        branch["subject"]["branch"] = "release"
        with self.assertRaisesRegex(feature.FeatureEvidenceError, "admitted against"):
            self.check(branch)


class RuntimeIdentityTest(unittest.TestCase):
    FIXTURE = FixtureApi

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.api = self.FIXTURE(Path(temporary.name).resolve())

    def identity(self, run_id):
        directory = self.api.root / "public"
        return pages.runtime_identity(self.api, repository=self.api.root, source_sha=self.api.covered,
                                      run_id=run_id, directory=directory), directory

    def fresh_generation(self, identifier=31):
        run = {**self.api.runs[20], "id": identifier, "event": "workflow_dispatch", "head_branch": "master",
               "head_sha": self.api.covered}
        self.api.runs[identifier], self.api.inventories[identifier] = run, []
        names = [ci_reuse.POLICY_JOB, ci_reuse.BUILD_JOB, ci_reuse.GATE_JOB,
                 *ci_reuse.expected_scenario_jobs_for(ci_reuse.DEFAULT_MATRIX, "pr-anchors")]
        self.api.job_lists[identifier] = [{"jobs": [self.api.job(run, name, index)
                                                    for index, name in enumerate(names)]}]
        return run

    def test_reused_runtime_writes_its_reference_as_the_delegated_extension(self):
        reference, _ = ci_reuse.find_reference(self.api, self.api.covered, "e2e")
        self.api.wrapper(reference)
        result, directory = self.identity(30)
        self.assertEqual(("20", "1", self.api.tested, "feature/hud", "true", "false"),
                         tuple(result[key] for key in ("source_run_id", "source_run_attempt", "source_sha",
                                                       "source_branch", "reused", "selective")))
        self.assertEqual(self.api.git("rev-parse", f"{self.api.covered}^{{tree}}"), result["target_tree"])
        self.assertEqual(str(directory / "extensions.json"), result["extensions_path"])
        self.assertEqual({pages.RUNTIME_SOURCE: reference}, json.loads((directory / "extensions.json").read_bytes()))

    def test_a_fresh_runtime_has_no_extension(self):
        self.fresh_generation()
        result, directory = self.identity(31)
        self.assertEqual(("31", "false", ""), (result["source_run_id"], result["reused"], result["extensions_path"]))
        self.assertFalse((directory / "extensions.json").exists())


class SelectiveRuntimeIdentityTest(RuntimeIdentityTest):
    """Selective generations: the admission is recomputed from real Git objects, and every
    verified selection is handed off, however few checkpoints it re-captures (mod-base composes
    it per frame; ``test_mod_base_adapter.ComposedEvidenceTest``)."""

    FIXTURE = SelectiveFixtureApi

    def fresh_selection(self):
        self.fresh_generation()
        admission = e2e_selection.admit(self.api.root, base=self.api.base, head=self.api.covered,
                                        policy=self.api.covered)
        self.api.add_selection(31, 410, admission)
        return 31, self.api.covered

    def reused_selection(self):
        admission = e2e_selection.admit(self.api.root, base=self.api.base, head=self.api.tested, policy=self.api.base)
        self.api.add_selection(20, 400, admission)
        reference, _ = ci_reuse.find_reference(self.api, self.api.covered, "e2e")
        self.api.wrapper(reference)
        return 30, self.api.base

    def test_a_forged_admission_fails_closed_before_any_hand_off(self):
        run_id, _policy = self.fresh_selection()
        forged = e2e_selection.admit(self.api.root, base=self.api.base, head=self.api.covered, policy=self.api.covered)
        forged = replace(forged, diff_sha256="0" * 64)
        self.api.inventories[run_id] = [item for item in self.api.inventories[run_id]
                                        if item["name"] != "e2e-feature-selection"]
        self.api.add_selection(run_id, 411, forged)
        with self.assertRaisesRegex(ValueError, "independently recomputed Git evidence"):
            self.identity(run_id)

    def test_a_per_checkpoint_selection_writes_the_extension_the_adapter_recomputes(self):
        from test_mod_base_adapter import KEY, expectation_of

        contract = load_contract()
        for name, prepare in (("fresh", self.fresh_selection), ("reused", self.reused_selection)):
            with self.subTest(runtime=name):
                self.setUp()
                run_id, policy = prepare()
                result, directory = self.identity(run_id)
                self.assertEqual(("true", "true", str(directory / "extensions.json")),
                                 (result["publish"], result["selective"], result["extensions_path"]))
                self.assertEqual(self.api.base, result["selection_base"])
                self.assertNotIn("publish_reason", result)
                extensions = json.loads((directory / "extensions.json").read_bytes())
                self.assertEqual({pages.FEATURE_SELECTION} | ({pages.RUNTIME_SOURCE} if name == "reused" else set()),
                                 set(extensions))
                chosen = feature.read_selection(extensions[pages.FEATURE_SELECTION])
                self.assertEqual((result["selection_sha256"], policy, self.api.base),
                                 (chosen.sha256, chosen.policy_commit, chosen.base_commit))
                # The real hud-preview selection re-captures only some checkpoints of its lanes.
                for scenario in project_contract(contract, chosen).scenarios:
                    for role in scenario.roles:
                        captured = [step for step in role.steps if step.capture is not None]
                        authored = [step for step in contract.role(scenario.scenario, role.role).steps
                                    if step.capture is not None]
                        self.assertLess(len(captured), len(authored))
                # The handoff mod-base prepares from this runtime: its scope is the recomputed
                # detail, its tested claim the original execution and its subject the master head.
                detail = feature.selection_detail(chosen)
                manifest = {"subject": {"commit": self.api.covered},
                            "scope": {"kind": "selected", "detail_sha256": feature.selection_digest(detail)},
                            "provenance": {"tested": {"commit": result["source_sha"]}}}
                pages.verify_selection_extension(manifest, extensions, chosen)
                with self.assertRaisesRegex(ValueError, "not the manifest's selected scope"):
                    pages.verify_selection_extension({**manifest, "provenance": {"tested": {"commit": "f" * 40}}},
                                                     extensions, chosen)
                scratch = directory / "hooks"
                scratch.mkdir()
                expectation = expectation_of(KEY, scratch, {pages.FEATURE_SELECTION: extensions[pages.FEATURE_SELECTION]})
                self.assertEqual({"kind": "selected", "detail": detail,
                                  "detail_sha256": feature.selection_digest(detail)}, expectation["scope"])


if __name__ == "__main__":
    unittest.main()
