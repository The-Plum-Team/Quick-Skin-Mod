from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import types
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import e2e_selection as admission


class E2ESelectionAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.repository = Path(self.temporary.name) / "objects.git"
        self.repository.mkdir()
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_AUTHOR_NAME="Selection Test", GIT_AUTHOR_EMAIL="selection@example.invalid",
                        GIT_COMMITTER_NAME="Selection Test", GIT_COMMITTER_EMAIL="selection@example.invalid",
                        GIT_AUTHOR_DATE="2001-01-01T00:00:00Z", GIT_COMMITTER_DATE="2001-01-01T00:00:00Z")
        self.git("init", "--bare", "--quiet")
        self.files = {name: ("100644", (ROOT / name).read_bytes()) for name in admission.POLICY_PATHS}
        self.editor = "modules/cape-editor/src/main/java/example/Editor.java"
        self.files[self.editor] = ("100644", b"class Editor {}\n")
        self.base = self.commit(self.files)
        changed = dict(self.files)
        changed[self.editor] = ("100644", b"class Editor { int zoom; }\n")
        self.head = self.commit(changed, self.base)

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *args, input=None):
        return subprocess.run(["git", *args], cwd=self.repository, env=self.env, input=input,
                              capture_output=True, check=True).stdout

    def commit(self, files, parent=None):
        hierarchy = {}
        for path, (mode, contents) in files.items():
            node = hierarchy
            parts = path.split("/")
            for part in parts[:-1]: node = node.setdefault(part, {})
            blob = self.git("hash-object", "-w", "--stdin", input=contents).strip().decode()
            node[parts[-1]] = (mode, blob)
        def tree(node):
            entries = []
            for name, value in sorted(node.items()):
                if isinstance(value, dict): mode, kind, object_id = "040000", "tree", tree(value)
                else: mode, object_id = value; kind = "blob"
                entries.append(f"{mode} {kind} {object_id}\t{name}".encode() + b"\0")
            return self.git("mktree", "-z", input=b"".join(entries)).strip().decode()
        arguments = ["commit-tree", tree(hierarchy)]
        if parent: arguments += ["-p", parent]
        return self.git(*arguments, input=b"temporary fixture\n").strip().decode()

    def admit(self, **kwargs):
        return admission.admit(self.repository, base=kwargs.get("base", self.base),
                               head=kwargs.get("head", self.head), policy=kwargs.get("policy", self.base))

    def test_exact_objects_bind_the_selection_and_all_policy_bytes(self):
        result = self.admit()
        self.assertTrue(result.enabled)
        self.assertEqual((self.base, self.head), (result.base_commit, result.head_commit))
        self.assertTrue(result.diff_complete)
        self.assertEqual((self.editor,), result.require_selection().changed_paths)
        self.assertEqual({"cape-editor", "cape-menu", "common", "skin-menu"},
                         set(result.require_selection().affected_modules))
        self.assertEqual(64, len(result.policy_sha256))
        self.assertEqual(64, len(result.diff_sha256))
        other = dict(self.files)
        other[self.editor] = ("100644", b"class Editor { int offset; }\n")
        next_head = self.commit(other, self.base)
        self.assertNotEqual(result.sha256, self.admit(head=next_head).sha256)

    def test_both_sides_of_cross_module_moves_and_deletions_are_classified(self):
        moved = dict(self.files)
        del moved[self.editor]
        destination = "modules/skin-menu/src/main/java/example/Editor.java"
        moved[destination] = self.files[self.editor]
        result = self.admit(head=self.commit(moved, self.base))
        self.assertTrue(result.enabled)
        self.assertEqual({self.editor, destination}, set(result.require_selection().changed_paths))
        self.assertEqual({"cape-editor", "skin-menu"}, set(result.require_selection().direct_modules))

    def test_missing_nonancestor_and_empty_baselines_keep_complete_coverage(self):
        for base in (None, "0" * 40, self.head):
            with self.subTest(base=base):
                self.assertFalse(self.admit(base=base, head=self.base).enabled)
        self.assertFalse(self.admit(head=self.base).enabled)
        unrelated = dict(self.files)
        unrelated["unrelated.txt"] = ("100644", b"independent history")
        self.assertEqual("baseline-is-not-an-ancestor", self.admit(base=self.commit(unrelated)).reason)

    def test_policy_graph_contract_and_matrix_changes_force_full(self):
        for name in admission.POLICY_PATHS:
            with self.subTest(path=name):
                changed = dict(self.files)
                mode, raw = changed[name]
                changed[name] = (mode, raw + b"\n")
                result = self.admit(head=self.commit(changed, self.base))
                self.assertFalse(result.enabled)
                self.assertEqual("selection-policy-changed", result.reason)

    def test_old_baseline_policy_cannot_hide_a_migration_in_the_diff(self):
        old = dict(self.files)
        del old["architecture/modules.json"]
        old_base = self.commit(old)
        new_head = self.commit(self.files, old_base)
        result = self.admit(base=old_base, head=new_head)
        self.assertFalse(result.enabled)

    def test_unknown_and_symbolic_source_paths_force_full(self):
        for path, mode, raw in (("unknown.txt", "100644", b"unknown"),
                                (self.editor, "120000", b"../../outside"),
                                (self.editor, "100755", b"executable source")):
            changed = dict(self.files)
            changed[path] = (mode, raw)
            with self.subTest(path=path, mode=mode):
                self.assertFalse(self.admit(head=self.commit(changed, self.base)).enabled)

    def test_candidate_policy_cannot_impersonate_the_executing_selector(self):
        changed = dict(self.files)
        changed["e2e/selection.py"] = ("100644", b"# ignore all changes\n")
        candidate = self.commit(changed, self.base)
        with self.assertRaisesRegex(admission.AdmissionError, "executing selector"):
            self.admit(policy=candidate)

    def test_replace_refs_and_checkout_files_cannot_change_the_diff(self):
        changed = dict(self.files)
        changed["unknown.txt"] = ("100644", b"different tree")
        replacement = self.commit(changed, self.base)
        self.git("replace", self.head, replacement)
        (self.repository / "architecture").mkdir()
        (self.repository / "architecture/modules.json").write_text("untrusted working tree")
        self.assertTrue(self.admit().enabled)

    def test_git_config_cannot_execute_external_diff_or_fsmonitor(self):
        marker = self.repository / "executed.marker"
        payload = self.repository / "payload.sh"
        payload.write_text("#!/bin/sh\ntouch '" + str(marker) + "'\n")
        payload.chmod(0o755)
        self.git("config", "diff.external", str(payload))
        self.git("config", "core.fsmonitor", str(payload))
        with patch.dict(os.environ, {"GIT_EXTERNAL_DIFF": str(payload), "GIT_DIR": "/absent"}):
            self.assertTrue(self.admit().enabled)
        self.assertFalse(marker.exists())

    def test_serialized_scope_and_provenance_are_independently_recomputed(self):
        result = self.admit()
        path = self.repository / "selection.json"
        path.write_bytes(result.to_bytes())
        self.assertEqual(result, admission.verify(path, self.repository,
                         base=self.base, head=self.head, policy=self.base))
        for field, value in (("head_commit", self.base), ("base_commit", None),
                             ("diff_complete", 1), ("policy_sha256", "a" * 64),
                             ("diff_sha256", "a" * 64), ("schema_version", True)):
            data = json.loads(result.to_bytes())
            data[field] = value
            path.write_text(json.dumps(data))
            with self.subTest(field=field), self.assertRaises(admission.AdmissionError):
                admission.verify(path, self.repository, base=self.base, head=self.head, policy=self.base)
        data = json.loads(result.to_bytes())
        data["selection"]["runs"][0]["roles"][0]["captures"].pop()
        path.write_text(json.dumps(data))
        with self.assertRaises(admission.AdmissionError):
            admission.verify(path, self.repository, base=self.base, head=self.head, policy=self.base)

    def test_bounded_or_malformed_diffs_require_full_coverage(self):
        actual = admission._git
        def altered(repository, *arguments, **kwargs):
            if arguments[0] == "diff": return b"truncated"
            return actual(repository, *arguments, **kwargs)
        with patch.object(admission, "_git", side_effect=altered):
            result = self.admit()
        self.assertFalse(result.enabled)
        self.assertFalse(result.diff_complete)
        with patch.object(admission, "MAX_CHANGED_PATHS", 0):
            self.assertFalse(self.admit().enabled)

    def test_runtime_reverifies_the_checkout_and_uses_git_identity_through_launch_and_report(self):
        import orchestrator
        import packaged_runtime

        selected = self.admit()
        path = self.repository / "selection.json"
        path.write_bytes(selected.to_bytes())
        args = Namespace(selection=None, selection_admission=path, selection_base=self.base,
                         selection_policy=self.base, scenarios="full,feature-navigation",
                         compatibility_mod=None, row_json="{}")
        with patch.object(orchestrator, "REPO", self.repository):
            args.selection_plan = orchestrator.resolve_selection(args, self.head)
            self.assertEqual(selected, args.selection_plan)
            self.assertEqual(["full", "feature-navigation"], orchestrator.scenarios_for({}, {}, args))
            with self.assertRaises(admission.AdmissionError):
                orchestrator.resolve_selection(args, self.base)
            args.scenarios = "phase0-smoke"
            with self.assertRaisesRegex(ValueError, "exact obligations"):
                orchestrator.scenarios_for({}, {}, args)

        package = types.ModuleType("minecraft_launcher_lib")
        package.__path__ = []
        command = types.ModuleType("minecraft_launcher_lib.command")
        utils = types.ModuleType("minecraft_launcher_lib.utils")
        command.get_minecraft_command = lambda _version, _install, options: options["jvmArguments"]
        utils.generate_test_options = lambda: {}
        package.command, package.utils = command, utils
        row = {"runtime_version": "1.20.1"}
        with patch.dict(sys.modules, {"minecraft_launcher_lib": package,
                                      "minecraft_launcher_lib.command": command,
                                      "minecraft_launcher_lib.utils": utils}):
            launched = packaged_runtime.client_command(self.repository, "fixture", self.repository,
                row, "feature-navigation", "client_a", "Alice", 25565, "java", selection=selected)
        self.assertIn("-Dquickskin.e2e.selection=" + selected.sha256, launched)
        self.assertNotEqual(selected.sha256, selected.require_selection().sha256)
        chosen = selected.role("feature-navigation", "client_a")
        self.assertIn("-Dquickskin.e2e.steps=" + ",".join(chosen.steps), launched)
        self.assertIn("-Dquickskin.e2e.captures=" + ",".join(chosen.captures), launched)
        report = {"version": "1.20.1", "role": "client_a", "scenario": "feature-navigation",
                  "contract_sha256": selected.contract_sha256, "selection_sha256": selected.sha256,
                  "status": "pass", "steps": [{"name": step, "status": "pass", "message": "checked",
                      "screenshot": step + ".png"} for step in chosen.steps]}
        report_path = self.repository / "e2e-report/report.json"
        report_path.parent.mkdir()
        report_path.write_text(json.dumps(report))
        with patch.object(packaged_runtime, "inspect_screenshot_for_step", return_value={}) as inspect:
            packaged_runtime.validate_report(self.repository, row, "feature-navigation", "client_a", selected)
            self.assertEqual(3, inspect.call_count)
        report["selection_sha256"] = selected.require_selection().sha256
        report_path.write_text(json.dumps(report))
        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "selection identity mismatch"):
            packaged_runtime.validate_report(self.repository, row, "feature-navigation", "client_a", selected)

        full = self.admit(base=None)
        path.write_bytes(full.to_bytes())
        args.selection_base = None
        with patch.object(orchestrator, "REPO", self.repository):
            with self.assertRaisesRegex(ValueError, "every scenario"):
                orchestrator.resolve_selection(args, self.head)
            args.scenarios = None
            self.assertIsNone(orchestrator.resolve_selection(args, self.head))
        self.assertEqual(",".join(orchestrator.SCENARIO_CONTRACT.scenarios_for_profile("pr")), args.scenarios)


if __name__ == "__main__":
    unittest.main()
