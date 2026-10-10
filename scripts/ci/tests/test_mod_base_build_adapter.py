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
* ``derive_runtime`` hands each lane exactly its native runtime row and PR scenarios;
* ``verify_runtime``'s reader accepts the native evidence shape and refuses failed, missing,
  crashed, foreign or unaccounted results;
* the candidate hooks, ``policy`` included, leave nothing untracked in the checkout and export only
  the native upload set.

Regenerating the hashes after an edit of a listed file: recompute the SHA-256 of each file with LF
line endings (the bytes Git stores) and write it into ``adapter.files``; ``test_listed_hashes_are_the_files``
names every stale entry.
"""

from __future__ import annotations

import hashlib
import json
import os
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
#: The legacy required checks; a shared context must never equal one, in any case.
LEGACY_CONTEXTS = ("Build and verify", "Packaged E2E gate")
#: The slowest native durations (Build 37435530842, E2E 37435530757): a 6m02s target, an 18m33s
#: policy job and a 13m07s lane. The shared hooks must allow at least twice as much.
NATIVE_SECONDS = {"target_seconds": 362, "policy_seconds": 1113, "runtime_seconds": 787}

for directory in (ROOT / "scripts" / "ci", ROOT / "scripts" / "release", ROOT / "e2e"):
    if str(directory) not in sys.path:
        sys.path.insert(0, str(directory))

import build_matrix  # noqa: E402
import matrix as release_matrix  # noqa: E402
import mod_base_build_adapter as adapter  # noqa: E402
import mod_base_build_candidate as candidate  # noqa: E402
import mod_base_build_policy as policy  # noqa: E402


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

    def run(self, hook: str, *, unit: str | None = None) -> subprocess.CompletedProcess[bytes]:
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
                          [{"name": adapter.PROPERTIES_INPUT, "path": adapter.PROPERTIES_PATH}]))
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
                     "a._native_release_modules(); import mod_base_build_dispatch; "
                     "print(json.dumps(sorted(m.__file__ for m in list(sys.modules.values()) "
                     "if (getattr(m, '__file__', None) or '').startswith(sys.argv[2]))))")
            process = subprocess.run([sys.executable, "-I", "-B", "-c", probe, str(controller / "scripts" / "ci"),
                                      str(controller)], cwd=controller, capture_output=True, text=True, check=False)
            self.assertEqual(process.returncode, 0, process.stderr)
            loaded = {Path(path).relative_to(controller).as_posix() for path in json.loads(process.stdout)}
        listed = {item["path"] for item in config_document()["adapter"]["files"]}
        self.assertLessEqual(loaded, listed)
        self.assertIn("scripts/release/matrix.py", loaded)

    def test_shared_contexts_never_equal_the_legacy_required_checks(self) -> None:
        contexts = config_document()["contexts"]
        legacy = {name.casefold() for name in LEGACY_CONTEXTS}
        for context in contexts.values():
            self.assertNotIn(context.casefold(), legacy)
            self.assertNotIn(f"{context} (shadow)".casefold(), legacy)

    def test_hook_timeouts_leave_room_over_the_native_durations(self) -> None:
        timeouts = config_document()["timeouts"]
        for key, native in NATIVE_SECONDS.items():
            self.assertGreaterEqual(timeouts[key], 2 * native, key)

    def test_the_activation_manifest_names_this_config(self) -> None:
        activation = self.kit.parse_activation(ACTIVATION_PATH.read_bytes())
        self.assertEqual((activation["repository"], activation["profile"]), (REPOSITORY, "quick-skin"))
        self.assertIn(activation["mode"], ("disabled", "shadow"))


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
        self.assertEqual(self.plan["plan_inputs"], [{"name": adapter.PROPERTIES_INPUT, "sha256": hashlib.sha256(
            git_bytes(ROOT / adapter.PROPERTIES_PATH)).hexdigest()}])

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
