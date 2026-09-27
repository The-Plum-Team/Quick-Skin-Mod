"""Quick Skin's mod-base adapter against the real release matrix, scenario contract and lock.

Every hook runs through the kit's own in-process dispatch (``host_child.run_hook``), which applies
the production argument and result schemas; the kit entry points that re-verify hook output
(``prepare``, ``compact``, ``compose`` with R3, ``family collect`` with R4/R5) run with the same
dispatch substituted for the isolated child.
"""

from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import types
import unittest
import zipfile
from contextlib import ExitStack
from dataclasses import replace
from pathlib import Path
from typing import Any
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci" / "tests"))

import mod_base_path  # noqa: E402

KIT = mod_base_path.kit_root()
for entry in ("scripts/architecture", "scripts/ci", "scripts/release", "e2e", "scripts/pages"):
    sys.path.insert(1, str(ROOT / entry))

import mod_base  # noqa: E402
from mod_base.adapter import host, host_child  # noqa: E402
from mod_base.adapter.api import Context  # noqa: E402
from mod_base.adapter.protocol import HookFailed  # noqa: E402
from mod_base.config import load_config, parse_config  # noqa: E402
from mod_base.evidence.compact import compact_bundle  # noqa: E402
from mod_base.evidence.compose import compose_selected  # noqa: E402
from mod_base.evidence.prepare import prepare_handoff  # noqa: E402
from mod_base.github.fake import FakeGitHub  # noqa: E402
from mod_base.imaging.metrics import SizePolicy, inspect_png, inspect_webp  # noqa: E402
from mod_base.imaging.png import pattern_png  # noqa: E402
from mod_base.model import grammar  # noqa: E402
from mod_base.model.canonical import canonical_json, sha256_hex  # noqa: E402
from mod_base.model.documents import validate_compact, validate_expectation  # noqa: E402
from mod_base.runtime import Invocation, snapshot_environment  # noqa: E402

import collect_compatibility  # noqa: E402
import compatibility_evidence  # noqa: E402
import e2e_selection  # noqa: E402
import feature_evidence  # noqa: E402
import feature_pages  # noqa: E402
import feature_review  # noqa: E402
import mod_base_fixtures  # noqa: E402
import scenario_contract  # noqa: E402
from module_graph import load_graph  # noqa: E402
from mod_compatibility_impact import Classification, ImpactError  # noqa: E402
from selection import RoleSelection, ScenarioSelection, project_contract, select  # noqa: E402


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
FAMILY = "mod-compatibility"
KEY = "mc1.20.1"
NEWEST = "mc26.3"
MATRIX = ROOT / "release" / "release-matrix.json"
SOURCE_WORKFLOW = ".github/workflows/on-demand-e2e.yml"
PRODUCER_WORKFLOW = ".github/workflows/mod-compatibility-review.yml"
PAGES_REF = f"{REPOSITORY}/.github/workflows/pages.yml@refs/heads/master"
PRODUCER_RUN_ID = 300
CREATED = "2026-09-20T10:11:12Z"
PUBLICATION_RUN = {"event": "repository_dispatch", "created_at": CREATED,
                   "display_title": "AI mod compatibility review"}
CONFIG = load_config(ROOT)
ADAPTER = host_child.load_adapter(ROOT / "scripts" / "pages" / "mod_base_adapter.py")
CONTRACT = scenario_contract.load_contract()
#: The retired Pages stylesheet's :root palette, which the config's dark theme keeps.
RETIRED_PALETTE = {"bg": "#0d120f", "surface": "#141b16", "surface_raised": "#1a241d", "surface_soft": "#202c24",
                   "text": "#f3f7f3", "muted": "#a9b7ac", "line": "#304137", "accent": "#77e39b",
                   "accent_strong": "#40c973", "highlight": "#e6c875", "danger": "#ff9b91",
                   "image_well": "#080b09"}


def git(*arguments: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *arguments], check=True, capture_output=True,
                          text=True).stdout.strip()


HEAD = git("rev-parse", "HEAD")
TREE = git("rev-parse", "HEAD^{tree}")
SUBJECT = {"branch": "master", "commit": HEAD, "tree": TREE}
KIT_SHA = hashlib.sha1(b"quick-skin adapter test kit").hexdigest()  # noqa: S324 - a synthetic commit id


def context(tmpdir: Path, *, implementation_sha: str = HEAD, config: Any = CONFIG, api: Any = None) -> Context:
    tmpdir.mkdir(parents=True, exist_ok=True)
    return Context(repo_root=ROOT, config=config, tmpdir=tmpdir, implementation_sha=implementation_sha, api=api)


def claim(run_id: int, commit: str = HEAD, *, workflow: str = SOURCE_WORKFLOW) -> dict[str, Any]:
    return {"run_id": run_id, "run_attempt": 1, "workflow_path": workflow, "branch": "master", "commit": commit,
            "controller_branch": "master", "controller_sha": commit}


def invocation(*, sha: str = HEAD, run_id: int = 42, job: str | None = None, workflow_ref: str | None = None,
               config: Any = CONFIG) -> Invocation:
    environ = {"GITHUB_REPOSITORY": REPOSITORY, "GITHUB_SHA": sha, "GITHUB_RUN_ID": str(run_id),
               "GITHUB_RUN_ATTEMPT": "1", "GITHUB_REF": "refs/heads/master", "GITHUB_REF_NAME": "master",
               "GITHUB_EVENT_NAME": "workflow_dispatch", "MOD_BASE_KIT_SHA": KIT_SHA}
    if job is not None:
        environ["GITHUB_JOB"] = job
    if workflow_ref is not None:
        environ["GITHUB_WORKFLOW_REF"] = workflow_ref
    return Invocation(repo_root=ROOT, config=config, kit_root=KIT, environ=snapshot_environment(environ))


class InProcessHost:
    """``mod_base.adapter.host.call`` without the child process (placement checks kept)."""

    def __init__(self, scratch: Path, api: Any = None) -> None:
        self.scratch, self.api, self.calls = scratch, api, []

    def __call__(self, invoked: Invocation, hook: str, arguments: dict[str, Any], *, network: bool = False) -> Any:
        host.check_placement(invoked, hook, network=network)
        self.calls.append(hook)
        tmpdir = Path(tempfile.mkdtemp(dir=self.scratch))
        ctx = context(tmpdir, implementation_sha=invoked.implementation_sha, config=invoked.config,
                      api=self.api if network else None)
        return host_child.run_hook(ctx, ADAPTER, hook, arguments)


def run_hook(hook: str, arguments: dict[str, Any], *, scratch: Path, api: Any = None, **options: Any) -> Any:
    return host_child.run_hook(context(Path(tempfile.mkdtemp(dir=scratch)), api=api, **options), ADAPTER, hook,
                               arguments)


def contract_shape(contract: Any) -> list[Any]:
    """The scenario/role/step/capture/comparison identity of a (projected) contract view."""

    return [(scenario.scenario, [(role.role, [step.id for step in role.steps],
                                  [step.id for step in role.steps if step.capture is not None],
                                  [(pair.first_step, pair.second_step) for pair in role.comparisons])
                                 for role in scenario.roles]) for scenario in contract.scenarios]


def target_of(key: str, scratch: Path) -> dict[str, Any]:
    return next(item for item in run_hook("targets", {"branches": None}, scratch=scratch) if item["key"] == key)


def expectation_of(key: str, scratch: Path, extensions: dict[str, Any] | None = None) -> dict[str, Any]:
    return run_hook("expectation", {"target": target_of(key, scratch), "tested_run": None,
                                    "extensions": extensions or {}}, scratch=scratch)


def hud_admission(base: str = HEAD, head: str = HEAD, policy: str = HEAD) -> e2e_selection.Admission:
    plan = select(CONTRACT, load_graph(), ["modules/hud-preview/src/main/java/example/Feature.java"], "pr")
    return e2e_selection.Admission(base, head, policy, TREE, TREE, TREE, "e" * 64, "f" * 64, True, "pr",
                                   plan.reason, plan)


def feature_extension(admission: e2e_selection.Admission) -> dict[str, Any]:
    return {"admission": admission.to_dict(), "coverage": {
        "schema_version": 1, "selective": True, "selection_sha256": admission.sha256,
        "baseline": {"source_sha": admission.base_commit}}}


def merged_reference(tested_sha: str, covered_sha: str, run_id: int) -> dict[str, Any]:
    source = {"schema_version": 1, "kind": "quick-skin-tested-source", "repository": REPOSITORY,
              "workflow": SOURCE_WORKFLOW, "run_id": run_id, "run_attempt": 1, "head_sha": "c" * 40,
              "head_branch": "feature/hud", "head_repository": REPOSITORY, "tested_sha": tested_sha,
              "tree_sha": TREE, "pull_request": 7, "base_sha": "a" * 40}
    return {"schema_version": 1, "kind": "quick-skin-merged-source", "repository": REPOSITORY,
            "workflow": SOURCE_WORKFLOW, "coverage_sha": covered_sha, "source": source,
            "seal_artifact": {"id": 999, "name": "tested-source-e2e", "size_in_bytes": 1024,
                              "digest": "sha256:" + "e" * 64, "expired": False,
                              "workflow_run": {"id": run_id, "head_sha": source["head_sha"],
                                               "head_branch": source["head_branch"]}}}


class Scratch(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.scratch = Path(temporary.name).resolve()


class ConfigTest(unittest.TestCase):
    def test_config_keeps_the_retired_site_branding_copy_and_theme(self) -> None:
        matrix = json.loads(MATRIX.read_bytes())
        project = CONFIG.project
        self.assertEqual(("Quick Skin", "Change your look. Stay in the game.", "Minecraft appearance mod"),
                         (project["name"], project["tagline"], project["eyebrow"]))
        self.assertEqual({"from_matrix": "project.description"}, project["description"])
        self.assertEqual(matrix["project"]["license"], project["license_label"])
        self.assertEqual({"path": "icon.png", "rendering": "pixelated"}, project["icon"])
        self.assertEqual({"id": "modrinth", "title": "Modrinth", "description": "Install releases and follow updates.",
                          "url": matrix["project"]["homepage"]}, project["links"][0])
        self.assertEqual("https://www.curseforge.com/minecraft/mc-mods/quick-skin", project["links"][1]["url"])
        self.assertEqual(RETIRED_PALETTE, CONFIG.theme["dark"])
        self.assertIsNone(CONFIG.theme["light"])
        self.assertEqual({"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}, CONFIG.labels["loaders"])
        # The retired gallery's friendlyTier texts; the kit renders them as "<label> (<tier>)".
        self.assertEqual({"key": "included in every advisory AI review",
                          "all": "included when the advisory review covers every capture"}, CONFIG.labels["tiers"])
        self.assertEqual({capture.review_tier for capture in CONTRACT.captures}, set(CONFIG.labels["tiers"]))
        self.assertEqual(set(CONTRACT.scenario_ids), set(CONFIG.labels["scenarios"]))
        self.assertEqual("Minecraft", CONFIG.labels["release_prefix"])

    def test_config_binds_the_adapter_images_and_compatibility_family(self) -> None:
        self.assertEqual("scripts/pages/mod_base_adapter.py", CONFIG.adapter["path"])
        self.assertEqual("scripts/pages/mod_base_fixtures.py", CONFIG.adapter["fixtures_path"])
        self.assertEqual(list(CONTRACT.screenshot_size), CONFIG.images["source_size"])
        self.assertEqual(([1600, 900], 82, 6), (CONFIG.images["derivative_box"], CONFIG.images["webp_quality"],
                                                CONFIG.images["webp_method"]))
        family = CONFIG.family(FAMILY)
        self.assertEqual(compatibility_evidence.PROJECTION_IMAGE_POLICY, family["image_policy"])
        self.assertEqual(sorted(collect_compatibility.REVIEW_EVENTS), family["producer"]["events"])
        self.assertEqual(collect_compatibility.REVIEW_WORKFLOW, family["producer"]["workflow"])
        self.assertTrue(family["carry_forward"])


class TargetsHookTest(Scratch):
    def test_every_matrix_target_is_listed_newest_first_at_the_protected_head(self) -> None:
        targets = run_hook("targets", {"branches": None}, scratch=self.scratch)
        matrix = json.loads(MATRIX.read_bytes())
        versions = sorted({row["artifact_version"] for row in matrix["artifacts"]},
                          key=lambda version: tuple(map(int, version.split("."))), reverse=True)
        self.assertEqual(17, len(targets))
        self.assertEqual([f"mc{version}" for version in versions], [target["key"] for target in targets])
        self.assertEqual(NEWEST, targets[0]["key"])
        self.assertEqual([f"Minecraft {version}" for version in versions], [target["label"] for target in targets])
        expected_hashes = {"matrix_sha256": hashlib.sha256(MATRIX.read_bytes()).hexdigest(),
                           "contract_sha256": CONTRACT.sha256}
        for target in targets:
            self.assertEqual(SUBJECT, target["subject"])
            self.assertEqual(expected_hashes, {name: target[name] for name in expected_hashes})

    def test_enrolled_branches_and_a_checkout_that_differs_from_the_head_fail_closed(self) -> None:
        class DriftedContext(Context):
            def read_blob(self, commit: str, path: str, max_bytes: int) -> bytes:
                return b"{}\n"

        with self.assertRaisesRegex(HookFailed, "default-branch mode"):
            run_hook("targets", {"branches": [{"name": "master", "commit": HEAD, "tree": TREE}]}, scratch=self.scratch)
        drifted = DriftedContext(repo_root=ROOT, config=CONFIG, tmpdir=self.scratch, implementation_sha=HEAD)
        with self.assertRaisesRegex(HookFailed, "differs from the protected head"):
            host_child.run_hook(drifted, ADAPTER, "targets", {"branches": None})


class ExpectationHookTest(Scratch):
    def test_real_contract_expectations_for_every_key(self) -> None:
        frames = 0
        for target in run_hook("targets", {"branches": None}, scratch=self.scratch):
            with self.subTest(key=target["key"]):
                value = run_hook("expectation", {"target": target, "tested_run": {"event": "pull_request",
                                                                                  "branch": "feature/x"},
                                                 "extensions": {}}, scratch=self.scratch)
                validate_expectation(value, image_policy=CONFIG.image_policy())
                self.assertEqual((14, 180, 76),
                                 (len(value["lanes"]), len(value["captures"]), len(value["comparisons"])))
                self.assertEqual({"kind": "complete"}, value["scope"])
                self.assertEqual(REPOSITORY, value["repository"])
                nodes = sorted({lane["artifact_node"] for lane in value["lanes"]})
                if target["key"] == KEY:
                    self.assertEqual({"artifact_nodes": ["fabric-1.20.1", "forge-1.20.1"]}, value["anchor"])
                    self.assertEqual(nodes, value["anchor"]["artifact_nodes"])
                else:
                    self.assertIsNone(value["anchor"])
                if target["key"] in (KEY, NEWEST):
                    frames += len(value["captures"])
        self.assertEqual(360, frames)

    def test_capture_identity_and_order_follow_the_contract(self) -> None:
        value = expectation_of(KEY, self.scratch)
        fabric = [capture["capture_id"] for capture in value["captures"]
                  if capture["lane_id"].startswith("fabric-1.20.1/")]
        self.assertLess(fabric.index("full.client_a.animated_cape_apply"),
                        fabric.index("full.client_a.animated_cape_advance"))
        self.assertLess(fabric.index("propagation-live.client_b.observe_before"),
                        fabric.index("propagation-live.client_b.await_live_change"))
        capture = next(item for item in value["captures"] if item["capture_id"] == "full.client_a.animated_cape_apply")
        self.assertEqual("fabric-1.20.1/full/client_a/animated_cape_apply", capture["frame_id"])
        self.assertEqual("fabric-1.20.1/full", capture["lane_id"])
        comparison = value["comparisons"][0]
        self.assertRegex(comparison["comparison_id"], r"^fabric-1\.20\.1/[a-z0-9-]+/client_[ab]/[a-z0-9_]+:[a-z0-9_]+$")
        self.assertEqual({17}, {lane["java"] for lane in value["lanes"]})

    def test_feature_selection_narrows_to_its_recomputed_scope_without_an_anchor(self) -> None:
        admission = hud_admission()
        value = expectation_of(KEY, self.scratch, {feature_evidence.FEATURE_SELECTION: feature_extension(admission)})
        validate_expectation(value, image_policy=CONFIG.image_policy())
        detail = feature_evidence.selection_detail(admission)
        self.assertEqual({"kind": "selected", "detail": detail,
                          "detail_sha256": feature_evidence.selection_digest(detail)}, value["scope"])
        projected = project_contract(CONTRACT, admission)
        expected = sum(step.capture is not None for scenario in projected.scenarios for role in scenario.roles
                       for step in role.steps) * 2
        self.assertEqual(expected, len(value["captures"]))
        self.assertLess(len(value["captures"]), 180)
        self.assertIsNone(value["anchor"])
        complete = expectation_of(KEY, self.scratch)
        orders = {item["frame_id"]: item["capture_order"] for item in complete["captures"]}
        for capture in value["captures"]:
            self.assertEqual(orders[capture["frame_id"]], capture["capture_order"])
        view = feature_evidence.SelectionView.from_detail(detail)
        self.assertEqual(contract_shape(project_contract(CONTRACT, admission)),
                         contract_shape(project_contract(CONTRACT, view)))
        tampered = feature_extension(admission)
        tampered["admission"]["selection"]["runs"][0]["roles"][0]["captures"] = []
        with self.assertRaisesRegex(HookFailed, "cannot be recomputed"):
            expectation_of(KEY, self.scratch, {feature_evidence.FEATURE_SELECTION: tampered})


class CollectHookTest(Scratch):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        root = Path(cls.temporary.name).resolve()
        cls.expectation = expectation_of(KEY, root)
        cls.other = expectation_of(NEWEST, root)
        cls.e2e = root / "e2e"
        images = tuple(pattern_png(1920, 1080, seed) for seed in (0, 1))
        mod_base_fixtures.write_packaged_lanes(cls.e2e, cls.expectation["lanes"] + cls.other["lanes"][:1],
                                               images=images)
        cls.images = images

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def collect(self, root: Path, expectation: dict[str, Any] | None = None) -> dict[str, Any]:
        expectation = self.expectation if expectation is None else expectation
        return run_hook("collect", {"runtime_root": str(root), "target": target_of(expectation["key"], self.scratch),
                                    "expectation": expectation}, scratch=self.scratch)

    def test_collect_is_a_pure_function_of_the_declared_runtime_subset(self) -> None:
        first = self.collect(self.e2e)
        self.assertEqual(14 + 180, len(first["runtime_files"]))
        self.assertTrue(all(name.startswith("profiles/fabric-1.20.1--") or name.startswith("profiles/forge-1.20.1--")
                            for name in first["runtime_files"]))
        copied = self.scratch / "runtime"
        for name in first["runtime_files"]:
            (copied / name).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.e2e / name, copied / name)
        self.assertEqual(canonical_json(first), canonical_json(self.collect(copied)))
        metrics = [inspect_png(image, SizePolicy.exact(1920, 1080)) for image in self.images]
        self.assertTrue(all(frame["reported_pixel"] in metrics for frame in first["frames"]))
        self.assertEqual([lane["lane_id"] for lane in self.expectation["lanes"]],
                         [lane["lane_id"] for lane in first["lanes"]])
        self.assertEqual({"pr"}, {lane["profile"] for lane in first["lanes"]})
        self.assertEqual(76, len(first["comparisons"]))

    def test_missing_forged_or_foreign_runtime_evidence_fails_closed(self) -> None:
        copied = self.scratch / "tampered"
        shutil.copytree(self.e2e, copied)
        result = next(copied.glob("profiles/fabric-1.20.1--1.20.1--full/result.json"))
        value = json.loads(result.read_bytes())
        value["contract_sha256"] = "0" * 64
        result.write_text(json.dumps(value))
        with self.assertRaisesRegex(HookFailed, "packaged evidence is invalid"):
            self.collect(copied)
        shutil.rmtree(copied)
        shutil.copytree(self.e2e, copied)
        next(copied.glob("profiles/forge-1.20.1--1.20.1--session/*/screenshots/*.png")).unlink()
        with self.assertRaisesRegex(HookFailed, "packaged evidence is invalid"):
            self.collect(copied)
        with self.assertRaisesRegex(HookFailed, "packaged evidence is invalid|differ"):
            self.collect(self.e2e, self.other)

    def rewrite(self, pattern: str, change: Any) -> Path:
        copied = Path(tempfile.mkdtemp(dir=self.scratch)) / "e2e"
        shutil.copytree(self.e2e, copied)
        result = next(copied.glob(pattern))
        value = json.loads(result.read_bytes())
        change(value)
        result.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")
        return copied

    def test_scenarios_of_one_artifact_node_must_share_one_production_jar(self) -> None:
        drifted = "f" * 64

        def other_jar(value: dict[str, Any]) -> None:
            value["jar_sha256"] = drifted
            for record in value["installed_quickskin"]:
                record["sha256"] = drifted

        with self.assertRaisesRegex(HookFailed, "fabric-1.20.1 used different production JARs"):
            self.collect(self.rewrite("profiles/fabric-1.20.1--1.20.1--session/result.json", other_jar))

    def test_a_lane_relabelled_as_another_loader_or_version_is_refused(self) -> None:
        def relabel(loader: str, version: str) -> Any:
            def change(value: dict[str, Any]) -> None:
                value.update(loader=loader, runtime_version=version)
                for report in value["reports"].values():
                    report["version"] = version
            return change

        for loader, version in (("forge", "1.20.1"), ("fabric", "1.20.2")):
            with self.subTest(loader=loader, version=version):
                copied = self.rewrite("profiles/fabric-1.20.1--1.20.1--full/result.json", relabel(loader, version))
                with self.assertRaisesRegex(HookFailed, "fabric-1.20.1/full ran another Minecraft version, loader"):
                    self.collect(copied)


class AuthenticateExtensionsTest(Scratch):
    def setUp(self) -> None:
        super().setUp()
        self.reference = merged_reference("b" * 40, HEAD, 4400)
        self.api = FakeGitHub(repository=REPOSITORY)
        self.api.set_branch("master", HEAD, TREE)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
            bundle.writestr("reused-source.json", canonical_json(self.reference))
        self.api.add_artifact({"id": 700, "name": "reused-source-e2e", "created_at": CREATED,
                               "workflow_run": {"id": 42, "head_branch": "master", "head_sha": HEAD}},
                              archive.getvalue())
        self.manifest = {"repository": REPOSITORY, "subject": SUBJECT, "scope": {"kind": "complete"},
                         "provenance": {"handoff": claim(42),
                                        "tested": {**claim(4400, "b" * 40), "branch": "feature/hud"},
                                        "reuse": "delegated", "coverage_sha": HEAD}}

    def authenticate(self, manifest: dict[str, Any], extensions: dict[str, Any]) -> Any:
        return run_hook("authenticate_extensions", {"manifest": manifest, "extensions": extensions},
                        scratch=self.scratch, api=self.api)

    def test_runtime_source_is_bound_to_its_tested_claim_and_generation_descriptor(self) -> None:
        result = self.authenticate(self.manifest, {feature_pages.RUNTIME_SOURCE: self.reference})
        self.assertEqual({"verified": [feature_pages.RUNTIME_SOURCE], "reuse_verified": True}, result)
        cases = {
            "tested commit": lambda manifest, reference: manifest["provenance"]["tested"].update(commit="c" * 40),
            "tested branch": lambda manifest, reference: manifest["provenance"]["tested"].update(branch="master"),
            "reuse": lambda manifest, reference: manifest["provenance"].update(reuse="none"),
            "coverage": lambda manifest, reference: reference.update(coverage_sha="d" * 40),
            "generation": lambda manifest, reference: manifest["provenance"]["handoff"].update(run_id=43),
            "repository": lambda manifest, reference: manifest.update(repository="fork/Quick-Skin-Mod"),
        }
        for name, mutate in cases.items():
            with self.subTest(case=name):
                manifest, reference = copy.deepcopy(self.manifest), copy.deepcopy(self.reference)
                mutate(manifest, reference)
                with self.assertRaises(HookFailed):
                    self.authenticate(manifest, {feature_pages.RUNTIME_SOURCE: reference})

    def test_undeclared_reuse_or_a_changed_descriptor_fails_closed(self) -> None:
        with self.assertRaisesRegex(HookFailed, "only a runtime_source"):
            self.authenticate(self.manifest, {feature_pages.FEATURE_SELECTION: feature_extension(hud_admission())})
        forged = copy.deepcopy(self.reference)
        forged["source"]["pull_request"] = 8
        with self.assertRaisesRegex(HookFailed, "differs from its generation's reuse descriptor"):
            self.authenticate(self.manifest, {feature_pages.RUNTIME_SOURCE: forged})

    def test_feature_selection_is_bound_to_the_selected_scope_and_tested_source(self) -> None:
        admission = hud_admission()
        detail = feature_evidence.selection_detail(admission)
        manifest = {**self.manifest,
                    "scope": {"kind": "selected", "detail_sha256": feature_evidence.selection_digest(detail)},
                    "provenance": {"handoff": claim(42), "tested": claim(42), "reuse": "none", "coverage_sha": HEAD}}
        extensions = {feature_pages.FEATURE_SELECTION: feature_extension(admission)}
        self.assertEqual({"verified": [feature_pages.FEATURE_SELECTION], "reuse_verified": False},
                         self.authenticate(manifest, extensions))
        for changes in ({"scope": {"kind": "selected", "detail_sha256": "0" * 64}}, {"scope": {"kind": "complete"}}):
            with self.subTest(changes=changes), self.assertRaisesRegex(HookFailed, "selected scope"):
                self.authenticate({**manifest, **changes}, extensions)
        foreign = feature_extension(hud_admission(head="c" * 40))
        with self.assertRaisesRegex(HookFailed, "selected scope"):
            self.authenticate(manifest, {feature_pages.FEATURE_SELECTION: foreign})


class VerifyPublicationTest(Scratch):
    def test_fresh_generations_pass_and_a_moved_head_vetoes(self) -> None:
        api = FakeGitHub(repository=REPOSITORY)
        api.set_branch("master", HEAD, TREE)
        api.add_run({"id": 42, "run_attempt": 1, "path": SOURCE_WORKFLOW, "event": "workflow_dispatch",
                     "status": "completed", "conclusion": "success", "head_branch": "master", "head_sha": HEAD,
                     "head_repository": {"full_name": REPOSITORY}, "created_at": CREATED})
        api.add_response(f"/repos/{REPOSITORY}/actions/workflows/on-demand-e2e.yml",
                         {"id": 101, "path": SOURCE_WORKFLOW, "state": "active"})
        draft = {"kind": "mod-base.promotion", "schema_version": 1, "repository": REPOSITORY,
                 "implementation": {"branch": "master", "sha": HEAD, "workflow_ref": PAGES_REF, "run_id": 9000,
                                    "run_attempt": 1},
                 "kit": {"repository": mod_base.KIT_REPOSITORY, "sha": KIT_SHA, "version": mod_base.__version__},
                 "heads": {"master": HEAD},
                 "bundles": [{"key": KEY, "collected_artifact_id": 1, "collected_digest": "sha256:" + "a" * 64,
                              "manifest_sha256": "b" * 64, "coverage_sha": HEAD, "selected_artifact_id": 2}],
                 "families": []}
        self.assertIsNone(run_hook("verify_publication", {"promotion_draft": draft}, scratch=self.scratch, api=api))
        api.set_branch("master", "f" * 40, TREE)
        with self.assertRaisesRegex(HookFailed, "source advanced"):
            run_hook("verify_publication", {"promotion_draft": draft}, scratch=self.scratch, api=api)


def compact_draft(manifest: dict[str, Any], manifest_raw: bytes, *, artifact_id: int, run_id: int) -> dict[str, Any]:
    record = {**claim(run_id), "event": "workflow_dispatch", "created_at": CREATED, "conclusion": "success",
              "head_sha": HEAD}
    return {"kind": "mod-base.selection", "schema_version": 1, "repository": REPOSITORY, "key": KEY,
            "kit": {"repository": mod_base.KIT_REPOSITORY, "sha": KIT_SHA, "version": mod_base.__version__},
            "implementation": {"branch": "master", "sha": HEAD, "workflow_ref": PAGES_REF, "run_id": 9000,
                               "run_attempt": 1},
            "subject": SUBJECT, "coverage_sha": HEAD,
            "selected_artifact": {"kind": "handoff", "id": artifact_id, "name": grammar.handoff_name(KEY, 1),
                                  "digest": "sha256:" + "c" * 64, "size": 1024, "run_id": run_id, "run_attempt": 1,
                                  "workflow_path": SOURCE_WORKFLOW, "created_at": CREATED},
            "source": {"handoff_run": record, "tested_run": record, "reuse": "none",
                       "kit_binding": {"source": "workflow_file", "sha": KIT_SHA}},
            "expectation_sha256": manifest["expectation"]["sha256"], "source_manifest_sha256": sha256_hex(manifest_raw),
            "extensions_verified": manifest["extensions"]["names"] if manifest["extensions"] else []}


def zip_directory(root: Path) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            archive.writestr(path.relative_to(root).as_posix(), path.read_bytes())
    return stream.getvalue()


WHOLE_SCENARIO_PATH = "modules/hud-preview/src/main/java/example/WholeScenario.java"
REAL_SELECT = feature_evidence.select


def whole_scenario_select(contract: Any, graph: Any, paths: Any, profile: str) -> Any:
    """The hud-preview selection, re-capturing every capture of each scenario it runs.

    Today's only selective module (``hud-preview``) re-captures 2 of the 63 ``full`` checkpoints,
    so its composed lanes mix both epochs (``test_partially_recaptured_lane_passes_r3``); this
    whole-scenario variant exercises the other shape, lanes wholly from the selected generation."""

    paths = list(paths)
    if paths != [WHOLE_SCENARIO_PATH]:
        return REAL_SELECT(contract, graph, paths, profile)
    plan = REAL_SELECT(contract, graph, ["modules/hud-preview/src/main/java/example/Feature.java"], profile)
    runs = tuple(ScenarioSelection(run.scenario, tuple(
        RoleSelection(role.role, role.targets, contract.role(run.scenario, role.role).step_ids,
                      tuple(step.id for step in contract.role(run.scenario, role.role).steps
                            if step.capture is not None))
        for role in run.roles)) for run in plan.runs)
    return replace(plan, changed_paths=(WHOLE_SCENARIO_PATH,), runs=runs)


class ComposedEvidenceTest(unittest.TestCase):
    """Selected generations composed with their retained complete baseline, re-verified by R3."""

    BASELINE_RUN, WHOLE_RUN, PARTIAL_RUN, PAGES_RUN, BASELINE_ARTIFACT = 42, 55, 56, 8000, 20000

    @classmethod
    def setUpClass(cls) -> None:
        cls.select = patch.object(feature_evidence, "select", side_effect=whole_scenario_select)
        cls.select.start()
        cls.addClassCleanup(cls.select.stop)
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name).resolve()
        (cls.root / "hooks").mkdir()
        whole = whole_scenario_select(CONTRACT, load_graph(), [WHOLE_SCENARIO_PATH], "pr")
        cls.admissions = {
            cls.WHOLE_RUN: e2e_selection.Admission(HEAD, HEAD, HEAD, TREE, TREE, TREE, "e" * 64, "f" * 64, True,
                                                   "pr", whole.reason, whole),
            cls.PARTIAL_RUN: hud_admission(),
        }
        images = tuple(pattern_png(1920, 1080, seed) for seed in (0, 1))
        complete = expectation_of(KEY, cls.root / "hooks")
        mod_base_fixtures.write_packaged_lanes(cls.root / f"e2e-{cls.BASELINE_RUN}", complete["lanes"], images=images)
        runs = [(cls.BASELINE_RUN, None)]
        for run_id, admission in cls.admissions.items():
            extensions = {feature_evidence.FEATURE_SELECTION: feature_extension(admission)}
            selected = expectation_of(KEY, cls.root / "hooks", extensions)
            mod_base_fixtures.write_packaged_lanes(cls.root / f"e2e-{run_id}", selected["lanes"], images=images[::-1],
                                                   contract=project_contract(CONTRACT, admission),
                                                   selection_sha256=admission.sha256)
            (cls.root / f"extensions-{run_id}.json").write_bytes(canonical_json(extensions))
            runs.append((run_id, cls.root / f"extensions-{run_id}.json"))
        cls.api = FakeGitHub(repository=REPOSITORY)
        cls.api.set_branch("master", HEAD, TREE)
        cls.host = InProcessHost(cls.root / "hooks", cls.api)
        with patch.object(host, "call", side_effect=cls.host):
            for run_id, extensions in runs:
                prepare_handoff(invocation(run_id=run_id, job="prepare-pages-evidence"),
                                e2e_root=cls.root / f"e2e-{run_id}", key=KEY, output=cls.root / f"handoff-{run_id}",
                                subject=SUBJECT, tested=claim(run_id), handoff=claim(run_id),
                                extensions_path=extensions)
            baseline_raw = (cls.root / f"handoff-{cls.BASELINE_RUN}" / "manifest.json").read_bytes()
            draft = compact_draft(json.loads(baseline_raw), baseline_raw, artifact_id=6001, run_id=cls.BASELINE_RUN)
            (cls.root / "baseline-draft.json").write_bytes(canonical_json(draft))
            collector = invocation(run_id=9000, job="collect", workflow_ref=PAGES_REF)
            compact_bundle(collector, key=KEY, input_dir=cls.root / f"handoff-{cls.BASELINE_RUN}",
                           selection_path=cls.root / "baseline-draft.json", output=cls.root / "baseline-compact")
        cls.baseline = cls._retain_baseline()

    @classmethod
    def _retain_baseline(cls) -> dict[str, Any]:
        """Seed the baseline archive exactly as a successful Pages refresh job retains it."""

        archive = zip_directory(cls.root / "baseline-compact")
        owner = {"id": cls.PAGES_RUN, "run_attempt": 1, "path": ".github/workflows/pages.yml",
                 "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
                 "head_branch": "master", "head_sha": HEAD, "head_repository": {"full_name": REPOSITORY},
                 "created_at": "2026-09-20T11:00:00Z"}
        cls.api.add_run(owner)
        window = {"started_at": "2026-09-20T11:10:00Z", "completed_at": "2026-09-20T11:20:00Z"}
        step = {"name": "Retain the complete compact generation for feature evidence reuse", "number": 3,
                "status": "completed", "conclusion": "success", **window}
        jobs = [{"name": name, "status": "completed", "conclusion": "success", "head_sha": HEAD, **window,
                 "steps": [step] if name.startswith("Finalize") else []}
                for name in ("Publish / Build atomic static site", "Deploy GitHub Pages",
                             f"Finalize / Refresh evidence cache for {KEY}")]
        cls.api.add_jobs(cls.PAGES_RUN, 1, jobs)
        record = {"id": cls.BASELINE_ARTIFACT, "name": grammar.baseline_name(KEY, HEAD, cls.BASELINE_RUN),
                  "created_at": "2026-09-20T11:15:00Z",
                  "workflow_run": {"id": cls.PAGES_RUN, "head_branch": "master", "head_sha": HEAD}}
        cls.api.add_artifact(record, archive)
        return {"id": record["id"], "owner_run_id": cls.PAGES_RUN, "name": record["name"],
                "digest": "sha256:" + hashlib.sha256(archive).hexdigest(), "size_in_bytes": len(archive)}

    def compose(self, output: str, *, run_id: int | None = None, proof: dict[str, Any] | None = None) -> dict[str, Any]:
        run_id = self.WHOLE_RUN if run_id is None else run_id
        raw = (self.root / f"handoff-{run_id}" / "manifest.json").read_bytes()
        draft = compact_draft(json.loads(raw), raw, artifact_id=6000 + run_id, run_id=run_id)
        draft_path = self.root / f"{output}-draft.json"
        draft_path.write_bytes(canonical_json(draft))
        proof = {"baseline_source_run_id": self.BASELINE_RUN, "public_baseline_artifacts": {KEY: self.baseline}} \
            if proof is None else proof
        runtime = types.SimpleNamespace(reference=None, execution={"id": run_id})
        with ExitStack() as stack:
            stack.enter_context(patch.object(host, "call", side_effect=self.host))
            stack.enter_context(patch.object(feature_pages, "runtime_of", return_value=runtime))
            verify = stack.enter_context(patch.object(feature_review, "verify_selection",
                                                      return_value=(self.admissions[run_id], proof)))
            manifest = compose_selected(invocation(run_id=9000, job="collect", workflow_ref=PAGES_REF), api=self.api,
                                        key=KEY, selected_dir=self.root / f"handoff-{run_id}",
                                        selection_path=draft_path, output=self.root / output)
            self.assertIs(runtime, verify.call_args.args[2])
        return manifest

    def test_composition_passes_r3_and_keeps_every_frames_tested_run(self) -> None:
        manifest = self.compose("composed")
        validate_compact(manifest)
        self.assertEqual("composed", manifest["scope"]["kind"])
        self.assertEqual(self.baseline["name"], manifest["scope"]["components"]["baseline"]["name"])
        self.assertEqual(180, len(manifest["frames"]))
        selected = [frame for frame in manifest["frames"] if frame["epoch"] == "selected"]
        older = [frame for frame in manifest["frames"] if frame["epoch"] == "baseline"]
        self.assertEqual({"full"}, {frame["scenario"] for frame in selected})
        self.assertEqual(126, len(selected))
        self.assertEqual({self.WHOLE_RUN}, {frame["tested"]["run_id"] for frame in selected})
        self.assertEqual({self.BASELINE_RUN}, {frame["tested"]["run_id"] for frame in older})
        baseline = {frame["frame_id"]: frame for frame in json.loads(
            (self.root / "baseline-compact" / "manifest.json").read_bytes())["frames"]}
        for frame in selected:
            self.assertNotEqual(baseline[frame["frame_id"]]["derivative"]["sha256"], frame["derivative"]["sha256"])
        for frame in older:
            self.assertEqual(baseline[frame["frame_id"]]["derivative"], frame["derivative"])
        self.assertIsNone(manifest["extensions"])
        embedded = json.loads((self.root / "composed" / "expectation.json").read_bytes())
        self.assertEqual({"kind": "complete"}, embedded["scope"])
        self.assertEqual(json.loads(canonical_json(expectation_of(KEY, self.root / "hooks"))), embedded)
        self.assertIn("compose", self.host.calls)

    def test_partially_recaptured_lane_passes_r3(self) -> None:
        """Quick Skin's real selection re-captures individual checkpoints (hud-preview: 2 of the 63
        ``full`` captures). Each such lane is the selected execution's record and keeps the
        baseline execution of its older frames as ``baseline_run``; every frame keeps the run and
        JAR its pixels came from, and R3 re-verified both epochs."""

        manifest = self.compose("partial", run_id=self.PARTIAL_RUN)
        validate_compact(manifest)
        self.assertEqual(4, sum(frame["epoch"] == "selected" for frame in manifest["frames"]))
        baseline_lanes = {lane["lane_id"]: lane for lane in json.loads(
            (self.root / "baseline-compact" / "manifest.json").read_bytes())["lanes"]}
        selected_lanes = {lane["lane_id"]: lane for lane in json.loads(
            (self.root / f"handoff-{self.PARTIAL_RUN}" / "manifest.json").read_bytes())["lanes"]}
        epochs: dict[str, set[str]] = {}
        for frame in manifest["frames"]:
            epochs.setdefault(frame["lane_id"], set()).add(frame["epoch"])
        mixed = {lane_id for lane_id, found in epochs.items() if found == {"baseline", "selected"}}
        self.assertEqual(2, len(mixed))
        self.assertEqual(mixed, set(selected_lanes))
        lanes = {lane["lane_id"]: lane for lane in manifest["lanes"]}
        for lane_id, lane in lanes.items():
            if lane_id in mixed:
                self.assertEqual({name: value for name, value in baseline_lanes[lane_id].items()
                                  if name in feature_evidence.BASELINE_RUN_FIELDS}, lane["baseline_run"])
                self.assertEqual(selected_lanes[lane_id], {name: value for name, value in lane.items()
                                                           if name != "baseline_run"})
            else:
                self.assertEqual(baseline_lanes[lane_id], lane)
        for frame in manifest["frames"]:
            lane = lanes[frame["lane_id"]]
            run = lane["baseline_run"] if frame["epoch"] == "baseline" and "baseline_run" in lane else lane
            expected_run = self.PARTIAL_RUN if frame["epoch"] == "selected" else self.BASELINE_RUN
            self.assertEqual((expected_run, run["jars"]["production_sha256"]),
                             (frame["tested"]["run_id"], frame["tested"]["jar_sha256"]))

    def test_the_producer_hands_off_every_selection_r3_composes(self) -> None:
        """The producer hands off every verified selection (``feature_pages.runtime_identity``), so
        R3 must compose a whole-scenario re-capture and the real per-checkpoint one alike: each
        lane is baseline, selected or mixed exactly as its admission re-captured it, and only a
        mixed lane carries ``baseline_run``."""

        lane_frames: dict[str, set[str]] = {}
        for capture in expectation_of(KEY, self.root / "hooks")["captures"]:
            lane_frames.setdefault(capture["lane_id"], set()).add(capture["frame_id"])
        shapes = {}
        for run_id, output in ((self.WHOLE_RUN, "handed-off-whole"), (self.PARTIAL_RUN, "handed-off-partial")):
            with self.subTest(run=run_id):
                extensions = {feature_evidence.FEATURE_SELECTION: feature_extension(self.admissions[run_id])}
                recaptured = {capture["frame_id"] for capture in
                              expectation_of(KEY, self.root / "hooks", extensions)["captures"]}
                expected = {lane_id: "baseline" if not frames & recaptured
                            else "selected" if frames <= recaptured else "mixed"
                            for lane_id, frames in lane_frames.items()}
                manifest = self.compose(output, run_id=run_id)
                validate_compact(manifest)
                found: dict[str, set[str]] = {}
                for frame in manifest["frames"]:
                    found.setdefault(frame["lane_id"], set()).add(frame["epoch"])
                self.assertEqual(expected, {lane_id: "mixed" if len(epochs) == 2 else next(iter(epochs))
                                            for lane_id, epochs in found.items()})
                self.assertEqual({lane_id for lane_id, shape in expected.items() if shape == "mixed"},
                                 {lane["lane_id"] for lane in manifest["lanes"] if "baseline_run" in lane})
                shapes[run_id] = sorted(set(expected.values()))
        self.assertEqual({self.WHOLE_RUN: ["baseline", "selected"], self.PARTIAL_RUN: ["baseline", "mixed"]}, shapes)

    def test_a_baseline_the_certificate_does_not_name_is_refused(self) -> None:
        foreign = {"baseline_source_run_id": 41, "public_baseline_artifacts": {KEY: self.baseline}}
        with self.assertRaisesRegex(HookFailed, "complete generation the selection was admitted against"):
            self.compose("foreign-run", proof=foreign)
        missing = {"baseline_source_run_id": self.BASELINE_RUN, "public_baseline_artifacts": {}}
        with self.assertRaisesRegex(HookFailed, "does not contain this target"):
            self.compose("missing-target", proof=missing)
        substituted = {**self.baseline, "digest": "sha256:" + "0" * 64}
        with self.assertRaisesRegex(HookFailed, "differs from the complete coverage certificate"):
            self.compose("substituted", proof={"baseline_source_run_id": self.BASELINE_RUN,
                                               "public_baseline_artifacts": {KEY: substituted}})


class FamilyValidateTest(Scratch):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory()
        cls.images = tuple(pattern_png(1920, 1080, seed) for seed in (2, 3))

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temporary.cleanup()

    def native(self, *, coverage: str, target_sha: str | None = None, publication_run: Any = PUBLICATION_RUN,
               mutate: Any = None, key: str = KEY) -> Path:
        root = Path(tempfile.mkdtemp(dir=self.scratch))
        manifest = mod_base_fixtures.compatibility_bundle(
            root, key=key, repository=REPOSITORY, coverage_sha=coverage, target_sha=target_sha or coverage,
            publication_run=publication_run, publication_run_id=PRODUCER_RUN_ID, images=self.images)
        if mutate is not None:
            mutate(manifest)
            (root / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        return root

    def handoff(self, native: Path, *, commit: str, key: str = KEY) -> Path:
        """A family handoff: the envelope beside a verbatim native copy (synthetic commits allowed)."""

        output = Path(tempfile.mkdtemp(dir=self.scratch))
        records = []
        for path in sorted(item for item in native.rglob("*") if item.is_file()):
            relative = path.relative_to(native).as_posix()
            data = path.read_bytes()
            (output / relative).parent.mkdir(parents=True, exist_ok=True)
            (output / relative).write_bytes(data)
            records.append({"path": relative, "sha256": sha256_hex(data), "size": len(data)})
        manifest_raw = (native / "manifest.json").read_bytes()
        manifest = json.loads(manifest_raw)
        envelope = {
            "kind": "mod-base.family.envelope", "schema_version": 1, "repository": REPOSITORY, "family": FAMILY,
            "key": key, "kit": {"repository": mod_base.KIT_REPOSITORY, "sha": KIT_SHA, "version": mod_base.__version__},
            "subject": {"branch": "master", "commit": commit, "tree": TREE if commit == HEAD else "0" * 40},
            "coverage_sha": commit, "producer": claim(PRODUCER_RUN_ID, commit, workflow=PRODUCER_WORKFLOW),
            "native": {"manifest_path": "manifest.json", "manifest_sha256": sha256_hex(manifest_raw),
                       "kind": manifest["kind"], "schema_version": manifest["schema_version"]},
            "files": records,
        }
        (output / "envelope.json").write_bytes(canonical_json(envelope))
        return output

    def hook(self, bundle_dir: Path, expected: str, config: Any = CONFIG, key: str = KEY) -> tuple[Any, Path]:
        output = Path(tempfile.mkdtemp(dir=self.scratch))
        result = run_hook("family_validate", {"family": FAMILY, "key": key, "bundle_dir": str(bundle_dir),
                                              "expected_coverage_sha": expected, "output_dir": str(output)},
                          scratch=self.scratch, implementation_sha=expected, config=config)
        return result, output

    def assert_producer(self, projection: dict[str, Any], commit: str) -> None:
        self.assertEqual({**claim(PRODUCER_RUN_ID, commit, workflow=PRODUCER_WORKFLOW), **PUBLICATION_RUN,
                          "conclusion": "success", "head_sha": commit}, projection["provenance"]["producer"])

    def test_projection_maps_the_real_plan_counts_links_and_kit_metrics(self) -> None:
        result, output = self.hook(self.handoff(self.native(coverage=HEAD), commit=HEAD), HEAD)
        self.assertEqual({"status": "available", "reason": "complete clean compatibility wave",
                          "projection_path": "paired.json"}, result)
        projection = json.loads((output / "paired.json").read_bytes())
        self.assert_producer(projection, HEAD)
        self.assertEqual(SUBJECT["commit"], projection["subject"]["commit"])
        counts = {lane["variant"]["id"]: len(lane["pairs"]) for lane in projection["lanes"]}
        self.assertEqual({"cpm": 7, "ears": 5, "customnpcs": 2, "essential": 2, "replaymod": 2, "skin-layers-3d": 2},
                         counts)
        self.assertEqual({lane["review"]["reviewed_frame_count"] for lane in projection["lanes"]}, {7, 5, 2})
        self.assertEqual(11, len(projection["lanes"]))
        self.assertEqual([("forge-1.20.1", "replaymod")],
                         [(row["artifact_node"], row["variant_id"]) for row in projection["not_applicable"]])
        self.assertEqual(["Clean reference run", "Compatibility runtime run", "Complete AI review", "Publication run"],
                         [link["label"] for link in projection["provenance"]["links"]])
        self.assertEqual({"mod-compatibility-contract", "release-matrix", "scenario-contract"},
                         set(projection["contracts"]))
        written = sorted(path.relative_to(output).as_posix() for path in output.rglob("*") if path.is_file())
        self.assertEqual(3, len(written))
        for pair in (pair for lane in projection["lanes"] for pair in lane["pairs"]):
            self.assertEqual({"runtime_passed": True, "semantic_valid": True, "matches_reference": True,
                              "defect": False}, pair["verdict"])
            for side in ("reference", "candidate"):
                image = pair[side]["image"]
                data = (output / image["path"]).read_bytes()
                self.assertEqual(canonical_json(inspect_webp(data, SizePolicy.exact(1280, 720))),
                                 canonical_json(image["pixel"]))
                self.assertIsInstance(pair[side]["source"]["pixel"]["dark_fraction"], float)

    def test_an_image_free_key_projects_its_not_applicable_rows(self) -> None:
        native = self.native(coverage=HEAD, key=NEWEST)
        result, output = self.hook(self.handoff(native, commit=HEAD, key=NEWEST), HEAD, key=NEWEST)
        self.assertEqual("available", result["status"])
        projection = json.loads((output / "paired.json").read_bytes())
        self.assertEqual([], projection["lanes"])
        self.assertEqual(12, len(projection["not_applicable"]))

    def test_carry_forward_names_the_envelope_coverage(self) -> None:
        coverage, expected = "c" * 40, HEAD
        with ExitStack() as stack:
            stack.enter_context(patch.object(ADAPTER, "_is_ancestor", return_value=True))
            stack.enter_context(patch("mod_compatibility_impact.git_diff_paths", return_value=["docs/ai/WORKFLOW.md"]))
            result, output = self.hook(self.handoff(self.native(coverage=coverage), commit=coverage), expected)
        self.assertEqual(coverage, result["carried_from"])
        self.assertRegex(result["impact_paths_sha256"], r"^[0-9a-f]{64}$")
        projection = json.loads((output / "paired.json").read_bytes())
        self.assertEqual(expected, projection["coverage_sha"])
        self.assertEqual(coverage, projection["subject"]["commit"])
        self.assert_producer(projection, coverage)

    def test_refused_carry_forward_or_broken_lineage_is_unavailable_without_output(self) -> None:
        coverage, expected = "c" * 40, HEAD
        disabled = json.loads(json.dumps(CONFIG.data))
        disabled["families"][0]["carry_forward"] = False
        impacting = Classification(True, ("gradle.properties",), ("gradle.properties",))
        cases = {
            "impacting": ({"mod_compatibility_impact.classify_paths": impacting}, CONFIG, "compatibility-impacting"),
            "unclassifiable": ({"mod_compatibility_impact.git_diff_paths": ImpactError("oversized")}, CONFIG,
                               "cannot be classified"),
            "disabled": ({}, parse_config(json.dumps(disabled).encode()), "does not carry evidence forward"),
        }
        for name, (patches, config, reason) in cases.items():
            with self.subTest(case=name), ExitStack() as stack:
                stack.enter_context(patch.object(ADAPTER, "_is_ancestor", return_value=True))
                stack.enter_context(patch("mod_compatibility_impact.git_diff_paths", return_value=["docs/README.md"]))
                for target, value in patches.items():
                    option = {"side_effect": value} if isinstance(value, Exception) else {"return_value": value}
                    stack.enter_context(patch(target, **option))
                result, output = self.hook(self.handoff(self.native(coverage=coverage), commit=coverage), expected,
                                           config)
                self.assertEqual("unavailable", result["status"])
                self.assertIn(reason, result["reason"])
                self.assertEqual([], list(output.iterdir()))
        result, output = self.hook(self.handoff(self.native(coverage=coverage), commit=coverage), expected)
        self.assertEqual("unavailable", result["status"])
        self.assertIn("lineage of", result["reason"])
        result, _ = self.hook(self.handoff(self.native(coverage=HEAD, target_sha="0" * 40), commit=HEAD), HEAD)
        self.assertEqual("unavailable", result["status"])
        self.assertIn("tested commit", result["reason"])

    def test_contract_or_matrix_drift_is_superseded(self) -> None:
        mutations = {
            "scenario": lambda manifest: manifest["contracts"].update(scenario_sha256="0" * 64),
            "compatibility": lambda manifest: manifest["contracts"].update(compatibility_sha256="0" * 64),
            "matrix": lambda manifest: manifest["release"].update(matrix_sha256="0" * 64),
        }
        for name, mutate in mutations.items():
            with self.subTest(drift=name):
                result, output = self.hook(self.handoff(self.native(coverage=HEAD, mutate=mutate), commit=HEAD), HEAD)
                self.assertEqual("superseded", result["status"])
                self.assertEqual([], list(output.iterdir()))

    def test_a_bundle_without_its_publication_run_is_unavailable(self) -> None:
        result, output = self.hook(self.handoff(self.native(coverage=HEAD, publication_run=None), commit=HEAD), HEAD)
        self.assertEqual("unavailable", result["status"])
        self.assertIn("publication run", result["reason"])
        self.assertEqual([], list(output.iterdir()))

    def test_generation_mismatches_and_tampering_fail_closed(self) -> None:
        cases = {
            "publication run": lambda manifest: manifest["provenance"].update(publication_run_id=301),
            "coverage": lambda manifest: manifest["provenance"].update(coverage_sha="d" * 40),
            "event": lambda manifest: manifest["provenance"]["publication_run"].update(event="push"),
        }
        for name, mutate in cases.items():
            with self.subTest(case=name), self.assertRaises(HookFailed):
                self.hook(self.handoff(self.native(coverage=HEAD, mutate=mutate), commit=HEAD), HEAD)
        handoff = self.handoff(self.native(coverage=HEAD), commit=HEAD)
        with self.assertRaisesRegex(HookFailed, "protected head"):
            host_child.run_hook(context(Path(tempfile.mkdtemp(dir=self.scratch)), implementation_sha="f" * 40),
                                ADAPTER, "family_validate",
                                {"family": FAMILY, "key": KEY, "bundle_dir": str(handoff),
                                 "expected_coverage_sha": HEAD, "output_dir": str(self.scratch)})
        (handoff / "manifest.json").write_bytes((handoff / "manifest.json").read_bytes() + b" ")
        with self.assertRaisesRegex(HookFailed, "differs from its envelope record"):
            self.hook(handoff, HEAD)

    def test_projection_refuses_more_review_runs_than_links_and_another_image_policy(self) -> None:
        manifest = json.loads((self.native(coverage=HEAD) / "manifest.json").read_bytes())
        manifest["lanes"] = [dict(lane, review_run_id=4000 + index)
                             for index, lane in enumerate((manifest["lanes"] * 2)[:14])]
        arguments = {"bundle": self.scratch, "family": FAMILY, "key": KEY, "subject": {}, "coverage_sha": HEAD,
                     "producer": {}, "inspect_derivative": None, "write_image": None}
        with self.assertRaisesRegex(compatibility_evidence.CompatibilityEvidenceError, "14 review runs"):
            compatibility_evidence.project_paired(
                manifest, image_policy=compatibility_evidence.PROJECTION_IMAGE_POLICY, **arguments)
        with self.assertRaisesRegex(compatibility_evidence.CompatibilityEvidenceError, "image policy"):
            compatibility_evidence.project_paired(
                manifest, image_policy={**compatibility_evidence.PROJECTION_IMAGE_POLICY, "webp_quality": 82},
                **arguments)


class PublicationRunTest(unittest.TestCase):
    def run_record(self, **changes: Any) -> dict[str, Any]:
        return {"id": PRODUCER_RUN_ID, "status": "in_progress", "conclusion": None, "event": "repository_dispatch",
                "path": PRODUCER_WORKFLOW, "head_branch": "master", "head_sha": HEAD,
                "head_repository": {"full_name": REPOSITORY}, "created_at": PUBLICATION_RUN["created_at"],
                "display_title": PUBLICATION_RUN["display_title"], **changes}

    def publication_run(self, run: dict[str, Any]) -> dict[str, Any]:
        api = types.SimpleNamespace(get_run=lambda run_id: copy.deepcopy(run))
        return collect_compatibility._publication_run(api, repository=REPOSITORY, publication_run_id=PRODUCER_RUN_ID,
                                                      implementation_sha=HEAD)

    def test_the_producer_records_its_own_live_run(self) -> None:
        self.assertEqual(PUBLICATION_RUN, self.publication_run(self.run_record()))
        untitled = self.publication_run(self.run_record(display_title=None))
        self.assertEqual({"event": "repository_dispatch", "created_at": PUBLICATION_RUN["created_at"]}, untitled)
        for changes in ({"status": "completed"}, {"id": 301}, {"path": ".github/workflows/pages.yml"},
                        {"event": "push"}, {"head_branch": "feature"}, {"head_sha": "f" * 40},
                        {"head_repository": {"full_name": "fork/Quick-Skin-Mod"}}, {"head_repository": None},
                        {"created_at": "2026-02-30T00:00:00Z"}, {"display_title": "line\nbreak"}):
            with self.subTest(changes=changes), self.assertRaises(collect_compatibility.CollectionError):
                self.publication_run(self.run_record(**changes))

    def test_the_recorded_run_is_strictly_validated(self) -> None:
        validate = compatibility_evidence.validate_publication_run
        self.assertEqual(PUBLICATION_RUN, validate(dict(PUBLICATION_RUN)))
        for value in ({"event": "push"}, {**PUBLICATION_RUN, "run_id": 1}, {**PUBLICATION_RUN, "event": "Push"},
                      {**PUBLICATION_RUN, "created_at": "2026-09-20 10:11:12"},
                      {**PUBLICATION_RUN, "display_title": " "}, {**PUBLICATION_RUN, "display_title": "x" * 501},
                      {**PUBLICATION_RUN, "display_title": "\ud800"}, []):
            with self.subTest(value=value), self.assertRaises(compatibility_evidence.CompatibilityEvidenceError):
                validate(value)


class ModBasePathTest(unittest.TestCase):
    def test_kit_imports_never_write_bytecode_here_or_in_children(self) -> None:
        self.assertEqual(KIT, mod_base_path.kit_root())
        self.assertTrue(sys.dont_write_bytecode)
        self.assertEqual("1", os.environ.get("PYTHONDONTWRITEBYTECODE"))
        self.assertIn(str(KIT / "src"), sys.path)
        environment = mod_base_path.child_environment({"PATH": os.environ.get("PATH", "")},
                                                      python_path=[ROOT / "scripts" / "pages"])
        self.assertEqual("1", environment["PYTHONDONTWRITEBYTECODE"])
        self.assertEqual(os.pathsep.join([str(KIT / "src"), str(ROOT / "scripts" / "pages")]),
                         environment["PYTHONPATH"])
        completed = subprocess.run(
            [sys.executable, "-c", "import sys, mod_base; print(sys.dont_write_bytecode, mod_base.__file__)"],
            env=environment, capture_output=True, text=True, check=True)
        self.assertEqual(f"True {KIT / 'src' / 'mod_base' / '__init__.py'}", completed.stdout.strip())

    def test_an_unavailable_or_foreign_kit_raises_instead_of_skipping(self) -> None:
        with patch.object(mod_base_path, "_resolved", None), \
                patch.object(mod_base_path, "_bootstrap", side_effect=FileNotFoundError("no bootstrap")):
            with self.assertRaisesRegex(RuntimeError, "unavailable: no bootstrap"):
                mod_base_path.kit_root()
        foreign = types.ModuleType("mod_base")
        foreign.__file__ = str(ROOT / "elsewhere" / "mod_base" / "__init__.py")
        with patch.dict(sys.modules, {"mod_base": foreign}):
            with self.assertRaisesRegex(RuntimeError, "outside the pinned kit"):
                mod_base_path.kit_root()


if __name__ == "__main__":
    unittest.main()
