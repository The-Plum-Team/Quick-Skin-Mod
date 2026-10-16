"""Quick Skin's protected Build adapter for the shared mod-base Build and packaged E2E.

``scripts/ci/mod-base-build.json`` names the adapter, its dispatcher and policy entry point and every
module they import, with hashes. These tests run the adapter the way the kit does and compare what it
derives with what the legacy workflows derive from the same files:

* the Build config is valid for the pinned kit, its hashes are the files (as Git stores them) and its
  contexts can never be mistaken for the legacy required checks;
* ``derive_plan`` runs from a protected copy that holds the config and exactly the listed files, with
  the kit's worker argv, and the kit's own planning (``mod_base.build_ci.planning.build_plan``) turns
  its output into a plan whose targets, lanes and outputs are the native ones:
  ``scripts/release/build_matrix.py`` targets, ``matrix.py --kind pr-anchors`` lanes and the names
  ``verify_release.py`` stages;
* ``derive_runtime`` hands each lane exactly its native runtime row and PR scenarios, or the
  scenarios its narrowed runtime scope names;
* ``derive_scope`` (``runtime_selection``) narrows a pull request only as the reviewed native
  policy allows: non-runtime paths keep the smoke scenario, a feature module's Java source keeps the
  scenarios the native selector proves plus the smoke scenario, and anything else runs every
  scenario;
* ``verify_runtime``'s reader accepts the native evidence shape and refuses failed, missing,
  crashed, foreign or unaccounted results;
* ``verify_target`` and ``verify_build`` accept what the native producer stages
  (``verify_release.build_manifest`` over every target) and refuse a changed file or an unknown
  key, and every native shape the adapter copies (result fields, manifest and record keys, the
  lane upload set) equals its source, so a native change fails here rather than only in a shared
  run;
* the candidate hooks, ``policy`` included, leave nothing untracked in the checkout and export only
  the native upload set.

Regenerating the hashes after an edit of a listed file: recompute the SHA-256 of each file with LF
line endings (the bytes Git stores) and write it into ``adapter.files``; ``test_listed_hashes_are_the_files``
names every stale entry.
"""

from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any
from unittest import mock

import mod_base_path

ROOT = Path(__file__).resolve().parents[3]
CONFIG_PATH = ROOT / "scripts" / "ci" / "mod-base-build.json"
ACTIVATION_PATH = ROOT / "site" / "mod-base-build-activation.json"
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
#: The required checks of the default branch (release/github-governance.json). In ``shadow`` a
#: shared context must never equal one, in any case; from the bridge on it is one.
LEGACY_CONTEXTS = ("Build and verify", "Packaged E2E gate")
GOVERNANCE_PATH = ROOT / "release" / "github-governance.json"
#: The managed modes (``rollback_from`` for a ``reviewed-rollback``) in which the gate App may
#: publish the required names themselves (no ` (shadow)`).
REQUIRED_NAME_MODES = ("shared-build-and-e2e",)
#: Every state Quick Skin's manifest may hold: the adopted modes and the kit's rollback route out
#: of them (``shared-build-and-e2e`` -> ``reviewed-rollback`` -> ``disabled``).
ADOPTED_MODES = ("disabled", "shadow", *REQUIRED_NAME_MODES, "reviewed-rollback")


def context_refusal(activation: dict[str, Any], contexts: dict[str, str], required: list[str]) -> str | None:
    """Why these Build config ``contexts`` do not fit the activation, or ``None``.

    The contexts are either exactly the required names in the governance order, which only the
    Q5 bridge and later publish, or both distinct from every required name in any case and with
    or without ` (shadow)`. So restoring the shadow names undoes the bridge in any mode, a
    ``reviewed-rollback`` may keep or drop them, and no half-switched or lookalike pair exists."""

    if activation["mode"] not in ADOPTED_MODES:
        return f"Quick Skin never adopts the mode {activation['mode']}"
    managed = activation["rollback_from"] if activation["mode"] == "reviewed-rollback" else activation["mode"]
    names = [contexts["build"], contexts["packaged"]]
    if names == required:
        return None if managed in REQUIRED_NAME_MODES else f"{managed} may not publish the required names"
    legacy = {name.casefold() for name in required}
    clashing = [name for name in names
                if name.casefold() in legacy or f"{name} (shadow)".casefold() in legacy]
    return f"contexts clash with a required check: {clashing}" if clashing else None
#: The slowest native durations (Build 37435530842, E2E 37435530757): a 6m02s target, an 18m33s
#: policy job and a 13m07s lane. The shared hooks must allow at least twice as much.
NATIVE_SECONDS = {"target_seconds": 362, "policy_seconds": 1113, "runtime_seconds": 787}

for directory in (ROOT / "scripts" / "ci", ROOT / "scripts" / "release", ROOT / "e2e",
                  ROOT / "scripts" / "architecture"):
    if str(directory) not in sys.path:
        sys.path.insert(0, str(directory))

import build_matrix  # noqa: E402
import matrix as release_matrix  # noqa: E402
import mod_base_build_adapter as adapter  # noqa: E402
import mod_base_build_candidate as candidate  # noqa: E402
import mod_base_build_dispatch as dispatch  # noqa: E402
import mod_base_build_policy as policy  # noqa: E402
import module_graph  # noqa: E402
import selection  # noqa: E402
import verify_release  # noqa: E402
import visual_evidence  # noqa: E402

ACTION_PATH = ROOT / ".github" / "actions" / "run-packaged-e2e" / "action.yml"
#: The tested commit of every test plan (``Worker.subject``).
TESTED_SHA = "e" * 40


def git_bytes(path: Path) -> bytes:
    """The bytes Git stores for a text file of this checkout: a Windows ``core.autocrlf``
    checkout holds CRLF copies of the native modules, the protected checkout never does."""

    return path.read_bytes().replace(b"\r\n", b"\n")


def config_document() -> dict[str, Any]:
    return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))


def protected_copy(destination: Path) -> Path:
    """What the kit hands the validator as ``controller/``: the config and exactly its files."""

    document = config_document()
    for relative in ["scripts/ci/mod-base-build.json", *(item["path"] for item in document["adapter"]["files"])]:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git_bytes(ROOT / relative))
    return destination


def kit() -> Any:
    mod_base_path.kit_root()
    import mod_base
    from mod_base.build_ci import adapter as kit_adapter, planning
    from mod_base.build_ci.activation import parse_activation
    from mod_base.build_ci.config import load_build_config
    from mod_base.build_ci.protocol import BUILD_GRAPH_VERSION
    from mod_base.model import grammar
    from mod_base.model.canonical import canonical_json
    from mod_base.workflow import CI_CALLER_WORKFLOWS

    class Kit:
        pass

    found = Kit()
    for name, value in dict(mod_base=mod_base, adapter=kit_adapter, planning=planning,
                            parse_activation=parse_activation, load_build_config=load_build_config,
                            graph_version=BUILD_GRAPH_VERSION, grammar=grammar, canonical_json=canonical_json,
                            callers=CI_CALLER_WORKFLOWS).items():
        setattr(found, name, value)
    return found


def native_mod() -> adapter.Mod:
    """The adapter's view of this checkout's three plan inputs, read as Git stores them."""

    with tempfile.TemporaryDirectory() as directory:
        paths = []
        for relative in (adapter.INVENTORY_PATH, adapter.CONTRACT_PATH, adapter.PROPERTIES_PATH):
            path = Path(directory) / Path(relative).name
            path.write_bytes(git_bytes(ROOT / relative))
            paths.append(path)
        return adapter.load_mod(*paths)


class Worker:
    """A worker root laid out like the kit's (``validation-input/``, ``controller/``,
    ``validator-home/``) in which protected hooks run as plain processes with the kit's argv."""

    def __init__(self, directory: Path, kit_module: Any, *, inventory: bytes | None = None) -> None:
        self.kit = kit_module
        self.root = directory
        self.controller = protected_copy(directory / "controller")
        self.inputs = directory / "validation-input"
        self.home = directory / "validator-home"
        (self.home / "tmp").mkdir(parents=True)
        self.inputs.mkdir()
        self.config = kit_module.load_build_config(self.controller, repository=REPOSITORY)
        for name, path in kit_module.adapter.plan_sources(self.config.data).items():
            data = git_bytes(ROOT / path)
            if name == kit_module.adapter.INVENTORY_INPUT and inventory is not None:
                data = inventory
            (self.inputs / name).write_bytes(data)

    def subject(self) -> dict[str, Any]:
        kit_module = self.kit
        return {
            "repository": REPOSITORY, "source_repository": REPOSITORY, "pr_number": 7, "head_sha": "a" * 40,
            "head_branch": "feature/shared-build", "base_sha": "c" * 40, "base_branch": "master",
            "controller_sha": "c" * 40, "controller_workflow": kit_module.callers["build"],
            "controller_ref": kit_module.grammar.workflow_ref(REPOSITORY, kit_module.callers["build"], "master"),
            "kit": {"repository": kit_module.mod_base.KIT_REPOSITORY, "sha": "3" * 40,
                    "version": kit_module.mod_base.__version__, "tree_digest": "sha256:" + "4" * 64},
            "tested_sha": "e" * 40, "tested_tree": "f" * 40, "tested_parents": ["c" * 40, "a" * 40],
            "graph_version": kit_module.graph_version,
        }

    def run(self, hook: str, *, unit: str | None = None,
            extra: dict[str, str] | None = None) -> subprocess.CompletedProcess[bytes]:
        dispatcher = self.config.data["adapter"]["dispatcher"]
        if os.name == "posix":
            command = self.kit.adapter.hook_command(hook, python=sys.executable, checkout=str(self.controller),
                                                    dispatcher=dispatcher)
        else:  # the kit's argv builder takes POSIX paths only; this is the same argv
            command = [sys.executable, "-I", "-B", str(self.controller / dispatcher), "--hook", hook]
        names = {"build_target": "MB_TARGET_ID", "verify_target": "MB_TARGET_ID", "derive_runtime": "MB_LANE_ID",
                 "run_lane": "MB_LANE_ID", "verify_runtime": "MB_LANE_ID"}
        environment = {"HOME": str(self.home), "TMPDIR": str(self.home / "tmp"), "LANG": "C.UTF-8",
                       "PYTHONSAFEPATH": "1", "PYTHONNOUSERSITE": "1", "PYTHONDONTWRITEBYTECODE": "1",
                       "MB_TESTED_SHA": "e" * 40, "MB_TESTED_TREE": "f" * 40, "MB_REPOSITORY": REPOSITORY}
        for name in ("PATH", "SYSTEMROOT"):
            if name in os.environ:
                environment[name] = os.environ[name]
        if unit is not None:
            environment[names[hook]] = unit
        environment.update(extra or {})
        return subprocess.run(command, cwd=self.controller, env=environment, stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300, check=False)

    def output(self, name: str) -> bytes:
        path = self.home / "validation" / name
        data = path.read_bytes()
        shutil.rmtree(self.home / "validation")
        return data

    def plan(self) -> tuple[dict[str, Any], bytes]:
        process = self.run("derive_plan")
        if process.returncode != 0:
            raise AssertionError(process.stdout.decode("utf-8", "replace"))
        derived = self.output(self.kit.adapter.PLAN_OUTPUT)
        sources = {name: (self.inputs / name).read_bytes() for name in self.kit.adapter.plan_sources(self.config.data)}
        plan = self.kit.planning.build_plan(
            subject=self.subject(), config=self.config, inventory=sources.pop(self.kit.adapter.INVENTORY_INPUT),
            scenario_contract=sources.pop(self.kit.adapter.SCENARIO_INPUT), plan_inputs=sources, derived=derived)
        (self.inputs / self.kit.adapter.PLAN_INPUT).write_bytes(self.kit.canonical_json(plan))
        return plan, derived


class BuildConfigTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.kit = kit()

    def test_the_config_is_valid_for_the_pinned_kit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = self.kit.load_build_config(protected_copy(Path(directory)), repository=REPOSITORY)
        data = config.data
        self.assertEqual((data["repository"], data["profile"]), (REPOSITORY, "quick-skin"))
        self.assertEqual((data["inventory"]["path"], data["scenario_contract"]["path"], data["plan_inputs"]),
                         (adapter.INVENTORY_PATH, adapter.CONTRACT_PATH,
                          [{"name": adapter.PROPERTIES_INPUT, "path": adapter.PROPERTIES_PATH},
                           {"name": adapter.LOCK_INPUT, "path": adapter.LOCK_PATH},
                           {"name": adapter.GRAPH_INPUT, "path": adapter.GRAPH_PATH}]))
        self.assertEqual(tuple(item["name"] for item in data["plan_inputs"]), adapter.EXTRA_INPUTS)
        self.assertEqual(data["bundle"]["path"], "build/release")
        self.assertEqual(data["runtime"], {"system_profile": "xvfb-mesa"})

    def test_listed_hashes_are_the_files(self) -> None:
        stale = [item["path"] for item in config_document()["adapter"]["files"]
                 if hashlib.sha256(git_bytes(ROOT / item["path"])).hexdigest() != item["sha256"]]
        self.assertEqual(stale, [], "update these adapter.files hashes in scripts/ci/mod-base-build.json")

    def test_every_module_a_protected_hook_imports_is_listed(self) -> None:
        """The validator's copy holds only the listed files: importing the adapter and the native
        verifiers there must work, so no protected hook can reach an unlisted or candidate module."""

        with tempfile.TemporaryDirectory() as directory:
            controller = protected_copy(Path(directory))
            probe = ("import json, sys; sys.path.insert(0, sys.argv[1]); import mod_base_build_adapter as a; "
                     "a._native_release_modules(); a._native_selection_modules(); "
                     "import mod_base_build_dispatch; "
                     "print(json.dumps(sorted(m.__file__ for m in list(sys.modules.values()) "
                     "if (getattr(m, '__file__', None) or '').startswith(sys.argv[2]))))")
            process = subprocess.run([sys.executable, "-I", "-B", "-c", probe, str(controller / "scripts" / "ci"),
                                      str(controller)], cwd=controller, capture_output=True, text=True, check=False)
            self.assertEqual(process.returncode, 0, process.stderr)
            loaded = {Path(path).relative_to(controller).as_posix() for path in json.loads(process.stdout)}
        listed = {item["path"] for item in config_document()["adapter"]["files"]}
        self.assertLessEqual(loaded, listed)
        self.assertIn("scripts/release/matrix.py", loaded)
        self.assertLessEqual({"e2e/selection.py", "scripts/architecture/module_graph.py", "scripts/ci/e2e_impact.py"},
                             loaded)
        # The selector gets the lock's references from the adapter and never imports the native
        # lock module (or the visual metrics it imports), so neither is policy.
        self.assertEqual(loaded & {"e2e/mod_compatibility.py", "e2e/visual_similarity.py"}, set())
        self.assertEqual(listed & {"e2e/mod_compatibility.py", "e2e/visual_similarity.py"}, set())

    def test_the_config_enables_the_changed_paths_selection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = self.kit.load_build_config(protected_copy(Path(directory)), repository=REPOSITORY)
        self.assertEqual(config.data["runtime_selection"], {"input": adapter.SCOPE_INPUT_FORMAT})
        self.assertIn("derive_scope", self.kit.adapter.OPTIONAL_HOOKS)
        self.assertIn("derive_scope", adapter.HOOKS)
        listed = {item["path"] for item in config_document()["adapter"]["files"]}
        # The documents the selection reads are plan inputs, not policy: the kit binds them in the
        # plan, and a pull request that changes one is a selection input the kit runs in full.
        self.assertEqual(listed & {adapter.GRAPH_PATH, adapter.LOCK_PATH}, set())
        from mod_base.build_ci.scope import selection_inputs
        self.assertLessEqual({adapter.GRAPH_PATH, adapter.LOCK_PATH, "e2e/selection.py", "scripts/ci/e2e_impact.py",
                              "scripts/architecture/module_graph.py"}, selection_inputs(config.data))

    def test_the_contexts_follow_the_activation_mode(self) -> None:
        """Q4 shadow publishes distinct names, so no required check can be met by them. The Q5
        bridge (``shared-build-and-e2e`` while the native gates keep their names) publishes the
        required names exactly, in the governance order, so the owner can switch their source."""

        contexts = config_document()["contexts"]
        required = json.loads(GOVERNANCE_PATH.read_text(encoding="utf-8"))["required_checks"]
        self.assertEqual(tuple(required), LEGACY_CONTEXTS)
        activation = self.kit.parse_activation(ACTIVATION_PATH.read_bytes())
        self.assertIsNone(context_refusal(activation, contexts, required))

    def test_every_rollback_of_the_bridge_fits_the_context_rule(self) -> None:
        """The bridge's rollbacks (OPERATIONS.md of the kit; the kit has no
        ``shared-build-and-e2e -> shadow``) must pass this suite, or the only way back is a
        transition the kit refuses: restoring the distinct names at the same mode, then
        ``reviewed-rollback`` and ``disabled``. A half-switched pair, a lookalike or the required
        names outside the bridge stay refused."""

        required = list(LEGACY_CONTEXTS)
        shadow = {"build": "Shared Build and verify", "packaged": "Shared Packaged E2E gate"}
        bridge = {"build": required[0], "packaged": required[1]}

        def state(mode: str, rollback_from: str | None = None) -> dict[str, Any]:
            return self.kit.parse_activation(json.dumps({
                "kind": "mod-base.ci.activation", "schema_version": 1, "repository": REPOSITORY,
                "profile": "quick-skin", "mode": mode, "rollback_from": rollback_from}).encode())

        for activation, contexts in ((state("shadow"), shadow), (state("shared-build-and-e2e"), bridge),
                                     (state("shared-build-and-e2e"), shadow),
                                     (state("reviewed-rollback", "shared-build-and-e2e"), bridge),
                                     (state("reviewed-rollback", "shared-build-and-e2e"), shadow),
                                     (state("reviewed-rollback", "shadow"), shadow), (state("disabled"), shadow)):
            with self.subTest(mode=activation["mode"], contexts=contexts):
                self.assertIsNone(context_refusal(activation, contexts, required))
        for activation, contexts in ((state("shadow"), bridge), (state("disabled"), bridge),
                                     (state("reviewed-rollback", "shadow"), bridge),
                                     (state("shared-build-and-e2e"), {**shadow, "build": required[0]}),
                                     (state("shared-build-and-e2e"), {**bridge, "packaged": "packaged e2e GATE"}),
                                     (state("shared-build-and-e2e"), {"build": required[1], "packaged": required[0]}),
                                     (state("shared-build"), shadow)):
            with self.subTest(mode=activation["mode"], contexts=contexts):
                self.assertIsNotNone(context_refusal(activation, contexts, required))

    def test_hook_timeouts_leave_room_over_the_native_durations(self) -> None:
        timeouts = config_document()["timeouts"]
        for key, native in NATIVE_SECONDS.items():
            self.assertGreaterEqual(timeouts[key], 2 * native, key)

    def test_the_workflow_guide_holds_the_mode_change_procedure(self) -> None:
        """mod-base OPERATIONS.md: the reviewed transition check is the mod's own procedure."""

        guide = " ".join((ROOT / "docs" / "ai" / "WORKFLOW.md").read_text(encoding="utf-8").split())
        for command in ("template activation --repo .", "template sync --repo . --write", "template check --repo .",
                        "template transition --repo . --base <base>"):
            self.assertIn(f"`python scripts/ci/mod_base_kit.py run {command}`", guide)
        for name in ("mod-base-gate", "MOD_BASE_GATE_APP_PRIVATE_KEY", "pull_request_target"):
            self.assertIn(f"`{name}`", guide)

    def test_the_activation_manifest_names_this_config(self) -> None:
        activation = self.kit.parse_activation(ACTIVATION_PATH.read_bytes())
        self.assertEqual((activation["repository"], activation["profile"]), (REPOSITORY, "quick-skin"))
        self.assertIn(activation["mode"], ADOPTED_MODES)


class PlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.kit = kit()
        cls.directory = tempfile.TemporaryDirectory()
        cls.worker = Worker(Path(cls.directory.name), cls.kit)
        cls.plan, cls.derived = cls.worker.plan()
        cls.mod = native_mod()
        cls.data = release_matrix.load_matrix(ROOT / adapter.INVENTORY_PATH)
        cls.mod_version = release_matrix.read_mod_version(ROOT / adapter.INVENTORY_PATH, cls.data)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def test_targets_are_the_native_build_matrix(self) -> None:
        native = build_matrix.build_plan(self.data)
        self.assertEqual([target["id"] for target in self.plan["targets"]], [item["target"] for item in native])
        for target, item in zip(self.plan["targets"], native):
            production = [output["lane_id"] for output in target["outputs"] if output["role"] == "production"]
            self.assertEqual(production, item["artifact_nodes"])
            self.assertEqual({target["java"]}, {row["java"] for row in self.data["artifacts"]
                                                if row["artifact_version"] == target["id"]})
        self.assertEqual(len(self.plan["targets"]), len({row["artifact_version"] for row in self.data["artifacts"]}))

    def test_lanes_are_the_native_pr_runtime_rows(self) -> None:
        rows = release_matrix.gha_matrix(self.data, "pr-anchors", self.mod_version)["include"]
        self.assertEqual(sorted(lane["id"] for lane in self.plan["lanes"]), sorted(row["artifact_node"] for row in rows))
        self.assertEqual(len(self.plan["lanes"]), self.data["lane_count"])
        nodes = {row["artifact_node"]: row["artifact_version"] for row in self.data["artifacts"]}
        scenarios = rows[0]["scenarios"].split(",")
        for lane in self.plan["lanes"]:
            self.assertEqual(lane["target_id"], nodes[lane["id"]])
            self.assertEqual(lane["obligations"], [f"scenario/{scenario}" for scenario in scenarios])
            self.assertNotIn("--", lane["id"])

    def test_outputs_are_the_names_verify_release_stages(self) -> None:
        total = 0
        for target in self.plan["targets"]:
            expected = []
            for row in (row for row in self.data["artifacts"] if row["artifact_version"] == target["id"]):
                for key, directory, role in (("jar", "files", "production"), ("harness_jar", "harness", "harness")):
                    name = Path(row[key].replace("{mod_version}", self.mod_version)).name
                    expected.append({"path": f"{directory}/{name}", "lane_id": row["artifact_node"], "role": role})
            expected.append({"path": f"targets/{target['id']}/artifacts.json", "lane_id": None,
                             "role": "native-report"})
            expected.append({"path": f"targets/{target['id']}/sbom/quick-skin.cdx.json", "lane_id": None,
                             "role": "sbom"})
            self.assertEqual(target["outputs"], expected)
            total += len(expected)
        self.assertEqual(total, 2 * self.data["lane_count"] + 2 * len(self.plan["targets"]))

    def test_the_plan_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            again, derived = Worker(Path(directory), self.kit).plan()
        self.assertEqual(derived, self.derived)
        self.assertEqual(again, self.plan)
        self.assertEqual(adapter.encode(adapter.derive_plan(self.mod)), self.derived)

    def test_the_plan_binds_every_candidate_file(self) -> None:
        identity = self.plan["identity"]
        self.assertEqual(identity["inventory_sha256"], hashlib.sha256(git_bytes(ROOT / adapter.INVENTORY_PATH)).hexdigest())
        self.assertEqual(identity["scenario_sha256"], hashlib.sha256(git_bytes(ROOT / adapter.CONTRACT_PATH)).hexdigest())
        self.assertEqual(self.plan["plan_inputs"], [
            {"name": name, "sha256": hashlib.sha256(git_bytes(ROOT / path)).hexdigest()}
            for name, path in ((adapter.PROPERTIES_INPUT, adapter.PROPERTIES_PATH), (adapter.LOCK_INPUT, adapter.LOCK_PATH),
                               (adapter.GRAPH_INPUT, adapter.GRAPH_PATH))])

    def test_runtime_values_are_the_native_rows(self) -> None:
        rows = {row["artifact_node"]: row
                for row in release_matrix.gha_matrix(self.data, "pr-anchors", self.mod_version)["include"]}
        for lane in self.plan["lanes"]:
            values = adapter.runtime_values(self.mod, lane["id"])
            self.assertEqual(json.loads(values["E2E_ROW_JSON"]), rows[lane["id"]])
            self.assertEqual(values["E2E_SCENARIOS"], rows[lane["id"]]["scenarios"])
        for lane in (self.plan["lanes"][0]["id"], self.plan["lanes"][-1]["id"]):
            process = self.worker.run("derive_runtime", unit=lane)
            self.assertEqual(process.returncode, 0, process.stdout)
            values = self.kit.adapter.parse_runtime_values(self.worker.output(self.kit.adapter.RUNTIME_OUTPUT))
            self.assertEqual(values, adapter.runtime_values(self.mod, lane))

    def test_an_ambiguous_matrix_is_refused(self) -> None:
        raw = git_bytes(ROOT / adapter.INVENTORY_PATH)
        duplicated = raw.replace(b'"lane_count":', b'"lane_count": 1, "lane_count":', 1)
        self.assertNotEqual(duplicated, raw)
        with tempfile.TemporaryDirectory() as directory:
            process = Worker(Path(directory), self.kit, inventory=duplicated).run("derive_plan")
        self.assertEqual(process.returncode, 1)
        self.assertIn(b"duplicate JSON key", process.stdout)


PRODUCTION_NAME = "Quick Skin - Fabric - 1.20.1-3.1.0.jar"


def _evidence(mod: adapter.Mod, lane: str, production: bytes) -> dict[str, bytes]:
    """A lane's results in the native layout ``orchestrator.py`` writes, every scenario passing."""

    row = adapter.runtime_rows(mod)[lane]
    digest = hashlib.sha256(production).hexdigest()
    results, files = [], {}
    for scenario in adapter.scenarios(mod):
        profile = f"profiles/{lane}--{row['runtime_version']}--{scenario}"
        roles = mod.contract.expected_roles(scenario)
        result = {"artifact_node": lane, "runtime_version": row["runtime_version"], "loader": row["loader"],
                  "scenario": scenario, "contract_sha256": mod.contract.sha256, "jar_sha256": digest,
                  "installed_quickskin": [{"path": f"{root}/mods/{PRODUCTION_NAME}", "sha256": digest}
                                          for root in ("server", *roles)],
                  "port": 25565, "status": "pass", "profile": profile, "elapsed_s": 12.5,
                  "reports": {role: {"role": role, "scenario": scenario, "status": "pass",
                                     "contract_sha256": mod.contract.sha256, "steps": []} for role in roles}}
        results.append(result)
        files[f"{profile}/result.json"] = (json.dumps(result, indent=2) + "\n").encode()
        files[f"{profile}/logs/server.log"] = b"[Server thread/INFO]: Done\n"
        files[f"{profile}/client_a/e2e-report/done.marker"] = b""
        files[f"{profile}/client_a/screenshots/step-1.png"] = adapter.PNG_SIGNATURE + b"pixels"
    store = {"entries": 1}
    files["summary.json"] = (json.dumps({"results": results, "runtime_store": store}, indent=2) + "\n").encode()
    files["resolved-matrix.json"] = json.dumps({"rows": [{key: result[key] for key in adapter._RESOLVED_KEYS}
                                                         for result in results]}).encode()
    files["runtime-store.json"] = json.dumps(store).encode()
    return files


class RuntimeVerificationTests(unittest.TestCase):
    LANE = "fabric-1.20.1"
    PRODUCTION = b"the sealed production JAR"

    @classmethod
    def setUpClass(cls) -> None:
        cls.mod = native_mod()
        cls.obligations = [adapter.obligation(scenario) for scenario in adapter.scenarios(cls.mod)]

    def verify(self, files: dict[str, bytes], *, production: bytes = PRODUCTION) -> dict[str, Any]:
        return adapter.verify_run(self.mod, self.LANE, self.obligations, production=production,
                                  production_name=PRODUCTION_NAME, files=set(files), read=lambda path: files[path])

    def test_native_passing_results_are_accepted(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        report = self.verify(files)
        self.assertEqual(report["obligations"], self.obligations)
        self.assertEqual(report["screenshots"], len(self.obligations))
        self.assertEqual(report["files"], sorted(files))

    def refuse(self, files: dict[str, bytes], message: str, **kwargs: Any) -> None:
        with self.assertRaisesRegex(adapter.AdapterError, message):
            self.verify(files, **kwargs)

    def _summary(self, files: dict[str, bytes], change: Any) -> dict[str, bytes]:
        summary = json.loads(files["summary.json"])
        change(summary)
        changed = dict(files, **{"summary.json": json.dumps(summary).encode()})
        for result in summary["results"]:
            changed[f"{result['profile']}/result.json"] = json.dumps(result).encode()
        return changed

    def test_a_failed_scenario_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        self.refuse(self._summary(files, lambda summary: summary["results"][2].update(status="fail", error="x")),
                    "did not pass")

    def test_a_missing_scenario_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        self.refuse(self._summary(files, lambda summary: summary["results"].pop()), "planned scenarios")

    def test_another_production_jar_is_refused(self) -> None:
        self.refuse(_evidence(self.mod, self.LANE, self.PRODUCTION), "sealed production JAR", production=b"other")

    def test_a_client_without_the_sealed_jar_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        self.refuse(self._summary(files, lambda summary: summary["results"][1]["installed_quickskin"].pop()),
                    "did not install exactly the sealed JAR")

    def test_a_failed_client_report_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        self.refuse(self._summary(files, lambda summary: summary["results"][0]["reports"]["client_a"].update(
            status="fail")), "not a passing report")

    def test_a_crash_report_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        profile = json.loads(files["summary.json"])["results"][0]["profile"]
        files[f"{profile}/server/crash-reports/crash.txt"] = b"crash"
        self.refuse(files, "crash report")

    def test_an_unaccounted_file_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        files["profiles/unknown/logs/x.log"] = b""
        self.refuse(files, "accounts for")

    def test_a_result_that_differs_from_the_summary_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        profile = json.loads(files["summary.json"])["results"][0]["profile"]
        result = json.loads(files[f"{profile}/result.json"])
        result["elapsed_s"] = 1
        files[f"{profile}/result.json"] = json.dumps(result).encode()
        self.refuse(files, "differs from the lane summary")

    def test_a_duplicate_key_is_refused(self) -> None:
        files = _evidence(self.mod, self.LANE, self.PRODUCTION)
        files["summary.json"] = b'{"results": [], "results": []}'
        self.refuse(files, "duplicate JSON key")


def _narrowed(files: dict[str, bytes], keep: list[str]) -> dict[str, bytes]:
    """``files`` (a lane's complete evidence) with only the results of the scenarios in ``keep``."""

    summary = json.loads(files["summary.json"])
    results = [result for result in summary["results"] if result["scenario"] in keep]
    profiles = {result["profile"] for result in results}
    narrowed = {path: data for path, data in files.items()
                if path.startswith("profiles/") and "/".join(path.split("/")[:2]) in profiles}
    narrowed["summary.json"] = (json.dumps({"results": results, "runtime_store": summary["runtime_store"]},
                                           indent=2) + "\n").encode()
    narrowed["resolved-matrix.json"] = json.dumps({"rows": [{key: result[key] for key in adapter._RESOLVED_KEYS}
                                                            for result in results]}).encode()
    narrowed["runtime-store.json"] = files["runtime-store.json"]
    return narrowed


class RuntimeScopeTests(unittest.TestCase):
    """``derive_scope`` from the protected copy, with the kit's input line, and the narrowed
    ``derive_runtime`` and ``verify_runtime``."""

    SMOKE = ["scenario/phase0-smoke"]
    HUD = "modules/hud-preview/src/main/java/io/github/quickskin/hud/HudPreview.java"

    @classmethod
    def setUpClass(cls) -> None:
        cls.kit = kit()
        cls.directory = tempfile.TemporaryDirectory()
        cls.worker = Worker(Path(cls.directory.name), cls.kit)
        cls.plan, _ = cls.worker.plan()
        cls.mod = native_mod()
        cls.every = [adapter.obligation(scenario) for scenario in adapter.scenarios(cls.mod)]

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def scope(self, paths: list[str]) -> tuple[list[list[str]], str]:
        """The scenarios of every lane ``derive_scope`` selects for ``paths``, read by the kit's own
        reader, and the hook's log."""

        value = self.kit.canonical_json({"format": adapter.SCOPE_INPUT_FORMAT, "base_sha": "c" * 40,
                                         "base_tree": "d" * 40, "tested_tree": "f" * 40,
                                         "changed_paths": sorted(paths), "baseline": {}}).decode().rstrip("\n")
        process = self.worker.run("derive_scope", extra=self.kit.adapter.hook_values("derive_scope", scope=value))
        self.assertEqual(process.returncode, 0, process.stdout)
        document = self.kit.adapter.parse_derived_scope(self.worker.output(self.kit.adapter.SCOPE_OUTPUT))
        self.assertEqual([lane["id"] for lane in document["lanes"]], [lane["id"] for lane in self.plan["lanes"]])
        for lane, planned in zip(document["lanes"], self.plan["lanes"]):
            self.assertEqual(lane["scenarios"], [name for name in planned["obligations"] if name in lane["scenarios"]])
        return [lane["scenarios"] for lane in document["lanes"]], process.stdout.decode("utf-8", "replace")

    def assert_every_lane(self, paths: list[str], expected: list[str], reason: str) -> None:
        selected, log = self.scope(paths)
        self.assertEqual(selected, [expected] * len(self.plan["lanes"]))
        self.assertIn(f"quick-skin derive_scope: {reason}", log)

    def test_non_runtime_changes_keep_only_the_smoke_scenario(self) -> None:
        self.assert_every_lane(["docs/ai/WORKFLOW.md", "README.md", "site/mod-base.json"], self.SMOKE,
                               "non-runtime-only")

    def test_a_feature_module_source_runs_the_scenarios_the_native_selector_proves(self) -> None:
        native = selection.select(self.mod.contract, module_graph.load_graph(ROOT), [self.HUD], "pr")
        self.assertEqual((native.mode, [run.scenario for run in native.runs]), ("affected", ["full"]))
        self.assert_every_lane([self.HUD, "docs/README.md"], ["scenario/phase0-smoke", "scenario/full"],
                               "affected-module-coverage")

    def test_shared_code_runs_every_scenario(self) -> None:
        for path in ("modules/content-core/src/main/java/io/github/quickskin/core/Thing.java",
                     "common/src/main/java/io/github/quickskin/Common.java"):
            with self.subTest(path=path):
                self.assert_every_lane([path], self.every, "shared-module")

    def test_unmapped_paths_run_every_scenario(self) -> None:
        for paths, reason in (
                ([".github/workflows/build-gate.yml"], "unowned-path"),
                (["e2e/orchestrator.py", "docs/ai/WORKFLOW.md"], "unowned-path"),
                (["gradle/libs.versions.toml"], "unowned-path"),
                (["docs/diagram.png"], "unowned-path"),
                (["modules/hud-preview/src/main/resources/assets/quickskin/lang/en_us.json"],
                 "unknown-path-or-policy-change"),
                (["modules/hud-preview/build.gradle"], "unknown-path-or-policy-change")):
            with self.subTest(paths=paths):
                self.assert_every_lane(paths, self.every, reason)

    def test_a_change_that_reaches_compatibility_evidence_runs_every_scenario(self) -> None:
        self.assert_every_lane(["modules/skin-menu/src/main/java/io/github/quickskin/menu/SkinMenu.java"], self.every,
                               "compatibility-coverage")

    def test_mixed_shared_and_feature_changes_run_every_scenario(self) -> None:
        self.assert_every_lane([self.HUD, "modules/image-core/src/main/java/io/github/quickskin/image/Png.java"],
                               self.every, "shared-module")

    GRAPH = git_bytes(ROOT / adapter.GRAPH_PATH)
    LOCK = git_bytes(ROOT / adapter.LOCK_PATH)

    def select(self, paths: Any, *, graph: bytes | None = None, lock: bytes | None = None) -> tuple[Any, str]:
        return adapter.scope_selection(self.mod, paths, graph=self.GRAPH if graph is None else graph,
                                       lock=self.LOCK if lock is None else lock)

    def test_a_missing_or_malformed_change_list_runs_every_scenario(self) -> None:
        self.assertEqual(self.select([]), (None, "missing-change-input"))
        self.assertEqual(self.select(None), (None, "missing-change-input"))
        self.assertEqual(self.select(["docs/../README.md"]), (None, "selection-unavailable: ImpactError"))

    def test_an_empty_or_malformed_graph_runs_every_scenario(self) -> None:
        for graph in (b"", b"{}", b"x" * (adapter.MAX_GRAPH_BYTES + 1)):
            with self.subTest(graph=graph[:8]):
                self.assertEqual(self.select([self.HUD], graph=graph)[0], None)
                self.assertTrue(self.select([self.HUD], graph=graph)[1].startswith("selection-unavailable: "))
                self.assertEqual(self.select(["README.md"], graph=graph), (["phase0-smoke"], "non-runtime-only"))

    def test_the_lock_references_are_the_native_ones(self) -> None:
        """The adapter's narrow lock reader gives the selector exactly what the native one reads
        from the fully validated lock, for the native schema version."""

        import mod_compatibility
        self.assertEqual(adapter.LOCK_SCHEMA_VERSION, mod_compatibility.SCHEMA_VERSION)
        self.assertEqual(adapter.MAX_LOCK_BYTES, mod_compatibility.MAX_CONTRACT_BYTES)
        self.assertEqual(adapter.CAPTURE_ID_PATTERN, mod_compatibility.CAPTURE_ID.pattern.strip("^$"))
        self.assertEqual(tuple(sorted(vars(mod_compatibility.CompatibilityReferenceCaptures(
            baseline_with_mod="a.b.c", apply_local_skin_with_mod="a.b.d")))), adapter.LOCK_REFERENCE_KEYS)
        self.assertEqual(adapter.lock_reference_captures(self.LOCK), selection._lock_reference_captures())
        self.assertTrue(adapter.lock_reference_captures(self.LOCK))
        native = selection.select(self.mod.contract, module_graph.load_graph(ROOT), [self.HUD], "pr")
        given = selection.select(self.mod.contract, module_graph.load_graph(ROOT), [self.HUD], "pr",
                                 lock_references=adapter.lock_reference_captures(self.LOCK))
        self.assertEqual(given, native)

    def test_a_lock_it_cannot_read_runs_every_scenario(self) -> None:
        data = json.loads(self.LOCK)
        changed = []
        for edit in (lambda d: d.update(schema_version=adapter.LOCK_SCHEMA_VERSION + 1),
                     lambda d: d.update(mods=[]),
                     lambda d: d["mods"][0].pop("reference_captures"),
                     lambda d: d["mods"][0].update(reference_captures={"baseline_with_mod": "a.b.c"}),
                     lambda d: d["mods"][0].update(reference_captures={key: "A.B" for key in adapter.LOCK_REFERENCE_KEYS}),
                     lambda d: d["mods"][0].update(reference_captures={**{key: "a.b.c" for key in adapter.LOCK_REFERENCE_KEYS},
                                                                       "third": "a.b.c"})):
            document = json.loads(json.dumps(data))
            edit(document)
            changed.append(json.dumps(document).encode())
        changed += [b"", self.LOCK[:-2], b'{"schema_version": 8, "schema_version": 8}']
        for lock in changed:
            with self.subTest(lock=lock[:60]):
                with self.assertRaises(adapter.AdapterError):
                    adapter.lock_reference_captures(lock)
                self.assertEqual(self.select([self.HUD], lock=lock), (None, "selection-unavailable: AdapterError"))

    def test_a_lock_reference_reaches_the_selection(self) -> None:
        """A capture the lock substitutes is compatibility evidence: once the lock pairs a mod with
        a HUD capture, a HUD change runs every scenario."""

        self.assertEqual(self.select([self.HUD]), (["phase0-smoke", "full"], "affected-module-coverage"))
        data = json.loads(self.LOCK)
        data["mods"][0]["reference_captures"] = {key: "full.client_a.hud_preview_overlay"
                                                 for key in adapter.LOCK_REFERENCE_KEYS}
        self.assertEqual(self.select([self.HUD], lock=json.dumps(data).encode()), (None, "compatibility-coverage"))

    def test_another_input_format_is_refused(self) -> None:
        value = self.kit.canonical_json({"format": "changed-paths-v2", "changed_paths": ["README.md"]})
        process = self.worker.run("derive_scope", extra={"MB_SCOPE_INPUT": value.decode().rstrip("\n")})
        self.assertEqual(process.returncode, 1, process.stdout)
        self.assertIn(b"changed-paths-v1 format", process.stdout)

    def lane_value(self, lane: str, scenarios: list[str]) -> str:
        return json.dumps({"lane_id": lane, "scenarios": scenarios, "scope_sha256": "5" * 64})

    def test_a_narrowed_lane_receives_only_its_scenarios(self) -> None:
        lane = self.plan["lanes"][0]["id"]
        value = self.lane_value(lane, ["scenario/phase0-smoke", "scenario/full"])
        process = self.worker.run("derive_runtime", unit=lane,
                                  extra=self.kit.adapter.hook_values("derive_runtime", unit_id=lane, scope=value))
        self.assertEqual(process.returncode, 0, process.stdout)
        values = self.kit.adapter.parse_runtime_values(self.worker.output(self.kit.adapter.RUNTIME_OUTPUT))
        self.assertEqual(values["E2E_SCENARIOS"], "phase0-smoke,full")
        self.assertEqual(values["E2E_ROW_JSON"], adapter.runtime_values(self.mod, lane)["E2E_ROW_JSON"])

    def test_a_scope_that_does_not_narrow_this_lane_is_refused(self) -> None:
        lane, other = self.plan["lanes"][0]["id"], self.plan["lanes"][1]["id"]
        for value in (self.lane_value(other, self.SMOKE), self.lane_value(lane, []),
                      self.lane_value(lane, ["scenario/full", "scenario/phase0-smoke"]),
                      self.lane_value(lane, ["scenario/phase0-smoke", "scenario/phase0-smoke"]),
                      self.lane_value(lane, ["scenario/unknown"])):
            with self.subTest(value=value):
                with self.assertRaisesRegex(adapter.AdapterError, "does not narrow lane"):
                    adapter.lane_scope(value, lane, self.plan["lanes"][0]["obligations"])
                process = self.worker.run("derive_runtime", unit=lane, extra={"MB_RUNTIME_SCOPE": value})
                self.assertEqual(process.returncode, 1, process.stdout)

    def test_a_narrowed_lane_must_prove_exactly_its_scenarios(self) -> None:
        lane, production = RuntimeVerificationTests.LANE, RuntimeVerificationTests.PRODUCTION
        complete = _evidence(self.mod, lane, production)
        narrowed = ["scenario/phase0-smoke", "scenario/full"]
        files = _narrowed(complete, ["phase0-smoke", "full"])

        def verify(obligations: list[str], evidence: dict[str, bytes]) -> dict[str, Any]:
            return adapter.verify_run(self.mod, lane, obligations, production=production,
                                      production_name=PRODUCTION_NAME, files=set(evidence),
                                      read=lambda path: evidence[path])

        report = verify(narrowed, files)
        self.assertEqual((report["obligations"], sorted(report["scenarios"])), (narrowed, ["full", "phase0-smoke"]))
        for obligations, evidence in ((narrowed, complete), (self.every, files),
                                      (narrowed, _narrowed(complete, ["phase0-smoke"]))):
            with self.subTest(obligations=obligations, scenarios=len(evidence)):
                with self.assertRaisesRegex(adapter.AdapterError, "planned scenarios"):
                    verify(obligations, evidence)
        for obligations in ([], ["scenario/full", "scenario/phase0-smoke"], ["scenario/unknown"], ["full"]):
            with self.subTest(obligations=obligations):
                with self.assertRaisesRegex(adapter.AdapterError, "not this contract's PR scenarios"):
                    verify(obligations, files)

class CandidateScopeTests(unittest.TestCase):
    """``run_lane`` (candidate code) runs the PR profile or a selection of it, never anything else;
    the protected ``verify_runtime`` then proves the selection its runtime scope names."""

    def test_run_lane_takes_only_a_selection_of_the_pr_profile(self) -> None:
        mod, lane = native_mod(), RuntimeVerificationTests.LANE
        values = adapter.runtime_values(mod, lane)

        class Reached(Exception):
            pass

        def attempt(scenarios: str) -> None:
            environment = {"E2E_ROW_JSON": values["E2E_ROW_JSON"], "E2E_SCENARIOS": scenarios}
            with mock.patch.dict(os.environ, environment), mock.patch.object(candidate, "_config", return_value={}), \
                    mock.patch.object(candidate, "_mod", return_value=mod), \
                    mock.patch.object(candidate.adapter, "lane_target", side_effect=Reached):
                candidate.run_lane(ROOT, lane, ROOT / "unused-export")

        for accepted in (values["E2E_SCENARIOS"], "phase0-smoke", "phase0-smoke,full"):
            with self.subTest(accepted=accepted), self.assertRaises(Reached):
                attempt(accepted)
        for refused in ("full,phase0-smoke", "phase0-smoke,phase0-smoke", "unknown", "phase0-smoke,"):
            with self.subTest(refused=refused), self.assertRaisesRegex(candidate.CandidateError,
                                                                       "do not describe lane"):
                attempt(refused)

class CandidateHygieneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}

    def git(self, *arguments: str) -> None:
        subprocess.run(["git", *arguments], cwd=self.root, env=self.env, check=True, capture_output=True)

    def write(self, relative: str, data: bytes = b"x") -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def test_cleaning_keeps_tracked_sources_the_kit_overlay_and_the_bundle(self) -> None:
        for relative in ("gradle.properties", "fabric/src/main/A.java", "build.gradle.kts"):
            self.write(relative)
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "t")
        for relative in ("build/reports/problems.html", "build/release/files/a.jar", "out/mod-base-kit/src/x.py",
                         "out/other.txt", "fabric/versions/1.20.1/build/libs/a.jar", ".gradle/x", "e2e-out/y",
                         "fabric/src/main/B.java"):
            self.write(relative)
        removed = candidate.clean_checkout(self.root, self.env, keep=("out/mod-base-kit", "build/release"))
        remaining = sorted(path.relative_to(self.root).as_posix() for path in self.root.rglob("*")
                           if path.is_file() and ".git" not in path.relative_to(self.root).parts)
        self.assertEqual(remaining, ["build.gradle.kts", "build/release/files/a.jar", "fabric/src/main/A.java",
                                     "gradle.properties", "out/mod-base-kit/src/x.py"])
        self.assertIn("fabric/versions/", removed)
        self.assertIn("fabric/src/main/B.java", removed)

    def test_the_lane_export_is_the_native_upload_set(self) -> None:
        current = self.root / "current"
        for relative in ("summary.json", "resolved-matrix.json", "runtime-store.json",
                         "profiles/p/result.json", "profiles/p/logs/server.log", "profiles/p/client_a/e2e-report/report.json",
                         "profiles/p/client_a/screenshots/s.png", "profiles/p/client_a/options.txt",
                         "profiles/p/server/world/level.dat", "stray.txt"):
            path = current / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(adapter.PNG_SIGNATURE if relative.endswith(".png") else b"{}")
        export = self.root / "export"
        count, _ = candidate.export_evidence(current, export, "fabric-1.20.1")
        exported = sorted(path.relative_to(export / "lanes" / "fabric-1.20.1").as_posix()
                          for path in export.rglob("*") if path.is_file())
        self.assertEqual(exported, ["profiles/p/client_a/e2e-report/report.json", "profiles/p/client_a/screenshots/s.png",
                                    "profiles/p/logs/server.log", "profiles/p/result.json", "resolved-matrix.json",
                                    "runtime-store.json", "summary.json"])
        self.assertEqual(count, len(exported))

    def test_an_empty_json_report_cannot_be_exported(self) -> None:
        current = self.root / "current"
        current.mkdir()
        (current / "summary.json").write_bytes(b"")
        with self.assertRaisesRegex(adapter.AdapterError, "outside its role's bounds"):
            candidate.export_evidence(current, self.root / "export", "fabric-1.20.1")


def _verified_jar(path: Path, *_arguments: Any) -> dict[str, Any]:
    data = path.read_bytes()
    return {"filename": path.name, "bytes": len(data), "sha1": hashlib.sha1(data).hexdigest(),
            "sha256": hashlib.sha256(data).hexdigest(), "sha512": hashlib.sha512(data).hexdigest()}


def _verified_harness(path: Path, *_arguments: Any) -> dict[str, Any]:
    data = path.read_bytes()
    return {"filename": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def jar_stand_ins() -> tuple[Any, Any]:
    """``verify_jar`` and ``verify_harness`` open real Minecraft JARs (class files, loader metadata,
    access wideners); synthetic bytes take these stand-ins, as the native round-trip test does.
    ``test_the_jar_verifier_stand_ins_return_the_native_shapes`` ties their results to the native
    source, and the rig proof ran the real ones over a real target."""

    return (mock.patch.object(verify_release, "verify_jar", side_effect=_verified_jar),
            mock.patch.object(verify_release, "verify_harness", side_effect=_verified_harness))


def native_stage(repository: Path, sealed: Path, targets: list[str]) -> dict[str, dict[str, Any]]:
    """Stage each target with the native producer, ``verify_release.build_manifest`` (what
    ``verify_release.py --target T`` runs), from synthetic JARs in a copy of this checkout's
    matrix, properties, lockfiles and verification metadata, and lay the stages out in ``sealed``
    as the shared Build seals them. Returns the native manifests by target."""

    for relative in (adapter.INVENTORY_PATH, adapter.PROPERTIES_PATH, "gradle/verification-metadata.xml",
                     *(path.relative_to(ROOT).as_posix()
                       for path in (ROOT / "gradle" / "dependency-locks").glob("*.lockfile"))):
        path = repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(git_bytes(ROOT / relative))
    matrix_path = repository / adapter.INVENTORY_PATH
    # The checkout's own matrix (the same bytes): loading validates its source roots in place.
    data = release_matrix.load_matrix(ROOT / adapter.INVENTORY_PATH)
    mod_version = release_matrix.read_mod_version(matrix_path, data)
    for row in data["artifacts"]:
        for key in ("jar", "harness_jar"):
            path = repository / row[key].replace("{mod_version}", mod_version)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(f"{row['artifact_node']} {key}".encode())
    manifests = {}
    stage = repository / "build" / "release"
    jar, harness = jar_stand_ins()
    with jar, harness, mock.patch.object(verify_release, "git_commit", return_value=TESTED_SHA):
        for target in targets:
            if stage.exists():
                shutil.rmtree(stage)
            manifest = verify_release.build_manifest(repository, matrix_path, stage, stage / adapter.MANIFEST_NAME,
                                                     mod_version, data, target=target)
            (stage / adapter.MANIFEST_NAME).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
            for path in sorted(stage.rglob("*")):
                if not path.is_file():
                    continue
                relative = path.relative_to(stage).as_posix()
                if relative in (adapter.MANIFEST_NAME, adapter.SBOM_NAME):
                    relative = adapter.target_path(target, relative)
                destination = sealed / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(path.read_bytes())
            manifests[target] = manifest
    return manifests


class NativeShapeParityTests(unittest.TestCase):
    """The adapter copies native shapes it cannot import (the protected copy holds only the listed
    files): each copy is held to its source here, and the protected verifiers run over what the
    native producer stages."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.kit = kit()
        cls.directory = tempfile.TemporaryDirectory()
        root = Path(cls.directory.name)
        cls.worker = Worker(root / "worker", cls.kit)
        cls.plan, _ = cls.worker.plan()
        cls.mod = native_mod()
        cls.sealed = cls.worker.root / "sealed-build"
        cls.manifests = native_stage(root / "repository", cls.sealed, [target["id"] for target in cls.plan["targets"]])

    @classmethod
    def tearDownClass(cls) -> None:
        cls.directory.cleanup()

    def test_result_fields_are_the_native_result_fields(self) -> None:
        self.assertEqual(len(set(adapter.RESULT_FIELDS)), len(adapter.RESULT_FIELDS))
        self.assertEqual(frozenset(adapter.RESULT_FIELDS), visual_evidence.RESULT_FIELDS)

    def test_the_lane_evidence_lists_are_the_native_upload_globs(self) -> None:
        action = ACTION_PATH.read_text(encoding="utf-8").replace("\r\n", "\n")
        step = action.split("- name: Upload bounded packaged evidence\n", 1)[1].split("\n    - name:", 1)[0]
        globs = re.findall(r"^ +e2e-out/current/(\S+)$", step, re.MULTILINE)
        top = tuple(glob for glob in globs if "/" not in glob)
        trees = tuple(match.group(1) for match in (re.fullmatch(r"profiles/\*\*/([^/*]+)/\*\*", glob)
                                                   for glob in globs) if match)
        self.assertEqual(len(globs), len(top) + len(trees) + 1)
        self.assertIn("profiles/**/result.json", globs)
        self.assertEqual(candidate.EVIDENCE_FILES, top)
        self.assertEqual(candidate.EVIDENCE_TREES, trees)
        self.assertEqual(adapter.PROFILE_TREES, trees)
        # A shared lane runs no feature selection, so its export has no selection or coverage file,
        # and the protected reader refuses one as unaccounted.
        self.assertLessEqual(set(adapter.LANE_FILES), set(top))
        self.assertEqual(set(top) - set(adapter.LANE_FILES), {"selection.json", "coverage.json"})

    def test_the_jar_verifier_stand_ins_return_the_native_shapes(self) -> None:
        tree = ast.parse(git_bytes(ROOT / "scripts" / "release" / "verify_release.py").decode("utf-8"))
        functions = {node.name: node for node in tree.body if isinstance(node, ast.FunctionDef)}
        with tempfile.TemporaryDirectory() as directory:
            sample = Path(directory) / "sample.jar"
            sample.write_bytes(b"jar")
            for name, stand_in in (("verify_jar", _verified_jar), ("verify_harness", _verified_harness)):
                returns = [node for node in ast.walk(functions[name]) if isinstance(node, ast.Return)]
                self.assertEqual(len(returns), 1, name)
                self.assertIsInstance(returns[0].value, ast.Dict, name)
                native = {key.value for key in returns[0].value.keys if isinstance(key, ast.Constant)}
                self.assertEqual(len(native), len(returns[0].value.keys), name)
                self.assertEqual(set(stand_in(sample)), native, name)

    def test_the_manifest_keys_are_the_native_producers(self) -> None:
        self.assertEqual(len(self.manifests), len(self.plan["targets"]))
        for target, manifest in self.manifests.items():
            with self.subTest(target=target):
                self.assertEqual(set(manifest), set(adapter._MANIFEST_KEYS))
                self.assertEqual(set(manifest["release"]), set(adapter._RELEASE_KEYS))
                self.assertEqual(set(manifest["sbom"]), set(adapter._SBOM_RECORD_KEYS))
                for record in manifest["artifacts"]:
                    self.assertEqual(set(record), set(adapter._RECORD_KEYS))
                    self.assertEqual(set(record["harness"]), set(adapter._HARNESS_KEYS))
                self.assertEqual(manifest["release"], adapter.release_identity(self.mod, target))

    def run_hook(self, hook: str, unit: str | None) -> dict[str, Any]:
        with tempfile.TemporaryDirectory() as directory:
            output = dispatch.Output(Path(directory))
            jar, harness = jar_stand_ins()
            with jar, harness:
                dispatch._hook(hook, unit, self.worker.root, output)
            return {path.name: json.loads(path.read_bytes()) for path in output.written}

    def test_verify_build_accepts_the_native_stage_of_every_target(self) -> None:
        reports = self.run_hook("verify_build", None)
        self.assertEqual(sorted(reports), sorted(f"{target['id']}.json" for target in self.plan["targets"]))
        for target in self.plan["targets"]:
            report = reports[f"{target['id']}.json"]
            self.assertEqual((report["hook"], report["unit"], report["tested_sha"]),
                             ("verify_build", target["id"], TESTED_SHA))
            self.assertEqual([item["path"] for item in report["files"]],
                             sorted(output["path"] for output in target["outputs"]))
            for item in report["files"]:
                self.assertEqual(item["sha256"],
                                 hashlib.sha256((self.sealed / item["path"]).read_bytes()).hexdigest())

    def test_verify_target_refuses_a_partition_with_other_files(self) -> None:
        # verify_target is handed the sealed partition of one target; the whole Build is not one.
        with self.assertRaisesRegex(adapter.AdapterError, "exactly the planned outputs"):
            self.run_hook("verify_target", self.plan["targets"][0]["id"])

    def verify(self, target: str, change: dict[str, bytes]) -> list[dict[str, Any]]:
        jar, harness = jar_stand_ins()
        with jar, harness:
            return adapter.verify_target(self.mod, target, tested_sha=TESTED_SHA, sealed=self.sealed,
                                         read=lambda path: change.get(path) or (self.sealed / path).read_bytes())

    def test_verify_target_accepts_one_native_partition(self) -> None:
        target = self.plan["targets"][-1]
        files = self.verify(target["id"], {})
        self.assertEqual([item["path"] for item in files], sorted(output["path"] for output in target["outputs"]))

    def test_verify_target_refuses_a_changed_jar_an_unknown_key_and_another_commit(self) -> None:
        target = self.plan["targets"][0]["id"]
        production = next(output["path"] for output in self.plan["targets"][0]["outputs"]
                          if output["role"] == "production")
        manifest_path = adapter.target_path(target, adapter.MANIFEST_NAME)
        manifest = json.loads((self.sealed / manifest_path).read_bytes())
        with self.assertRaisesRegex(adapter.AdapterError, "differs from the manifest"):
            self.verify(target, {production: b"another production JAR"})
        with self.assertRaisesRegex(adapter.AdapterError, "must be an object with exactly"):
            self.verify(target, {manifest_path: json.dumps({**manifest, "provenance": {}}).encode()})
        with self.assertRaisesRegex(adapter.AdapterError, "another git_commit"):
            self.verify(target, {manifest_path: json.dumps({**manifest, "git_commit": "d" * 40}).encode()})


class PolicyHygieneTests(unittest.TestCase):
    """The kit seals the policy checkout like the others (``verify-candidate-source``): nothing
    untracked outside ``out/mod-base-kit`` and the bundle may remain, though tests start Python
    children without the hook's bytecode settings."""

    #: A suite whose child process writes bytecode next to the sources, as many Quick Skin tests
    #: do: they build a child environment of their own, without PYTHONPYCACHEPREFIX or
    #: PYTHONDONTWRITEBYTECODE.
    SUITE = (
        "import os, subprocess, sys, unittest\n"
        "from pathlib import Path\n"
        "HERE = Path(__file__).resolve().parent\n"
        "class ChildTest(unittest.TestCase):\n"
        "    def test_child_writes_bytecode(self):\n"
        "        env = {k: v for k, v in os.environ.items()\n"
        "               if k not in ('PYTHONDONTWRITEBYTECODE', 'PYTHONPYCACHEPREFIX')}\n"
        "        subprocess.run([sys.executable, '-c', 'import helper'], cwd=HERE, env=env, check=True)\n"
        "        self.assertTrue((HERE / '__pycache__').is_dir())\n"
    )
    #: The kit runner's contract as the hook uses it: the suite is the last argument, and the exit
    #: status is zero only when every discovered test passed.
    RUNNER = (
        "import sys, unittest\n"
        "suite = sys.argv[-1]\n"
        "result = unittest.TextTestRunner().run(unittest.defaultTestLoader.discover(suite, top_level_dir=suite))\n"
        "sys.exit(0 if result.wasSuccessful() and result.testsRun else 1)\n"
    )

    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.root = base / "checkout"
        (base / "tmp").mkdir()
        self.env = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
                    "TMPDIR": str(base / "tmp")}

    def git(self, *arguments: str) -> str:
        return subprocess.run(["git", *arguments], cwd=self.root, env=self.env, check=True,
                              capture_output=True, text=True).stdout

    def checkout(self, extra: str = "") -> None:
        for relative, text in (("checks/test_child.py", self.SUITE + extra), ("checks/helper.py", "VALUE = 1\n"),
                               ("README.md", "tracked\n")):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(text.encode("utf-8"))
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "t")
        runner = self.root / policy.KIT_OVERLAY / "tools" / "parallel_unittest.py"
        runner.parent.mkdir(parents=True)
        runner.write_bytes(self.RUNNER.encode("utf-8"))
        (self.root / "build" / "release").mkdir(parents=True)
        (self.root / "build" / "release" / "staged.jar").write_bytes(b"staged")

    def run_checks(self) -> None:
        with mock.patch.object(policy, "repository_checks"), mock.patch.object(policy, "SUITES", ("checks",)):
            policy.run_checks(self.root, self.env, profile_branch="master",
                              keep=(policy.KIT_OVERLAY.as_posix(), "build/release"))

    def untracked(self) -> list[str]:
        status = self.git("status", "--porcelain", "--ignored", "--untracked-files=all")
        return sorted(line[3:] for line in status.splitlines())

    def test_the_policy_hook_leaves_only_the_generated_roots(self) -> None:
        self.checkout()
        self.run_checks()
        self.assertFalse((self.root / "checks" / "__pycache__").exists())
        self.assertEqual(self.untracked(), ["build/release/staged.jar",
                                            f"{policy.KIT_OVERLAY.as_posix()}/tools/parallel_unittest.py"])

    def test_a_failed_check_still_leaves_the_checkout_clean(self) -> None:
        self.checkout("    def test_fails(self):\n        self.fail('policy failure')\n")
        with self.assertRaisesRegex(policy.PolicyError, "policy suites failed"):
            self.run_checks()
        self.assertFalse((self.root / "checks" / "__pycache__").exists())
        self.assertEqual(len(self.untracked()), 2)

    def test_a_check_that_changes_a_tracked_file_fails(self) -> None:
        self.checkout("    def test_edits(self):\n        (HERE.parent / 'README.md').write_text('changed')\n")
        with self.assertRaisesRegex(adapter.AdapterError, "tracked sources unchanged"):
            self.run_checks()


if __name__ == "__main__":
    unittest.main()
