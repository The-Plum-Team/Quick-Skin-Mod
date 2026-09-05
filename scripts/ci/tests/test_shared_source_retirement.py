from __future__ import annotations

import os
import fnmatch
import json
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import job_block, step_script

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))

from evidence_target import DEFAULT_MATRIX, inventory  # noqa: E402
from matrix import gha_matrix, load_matrix, read_mod_version  # noqa: E402


class SharedSourceRetirementTest(unittest.TestCase):
    def test_sync_exits_before_git_or_github_even_for_delayed_and_manual_targets(self):
        script = step_script("sync-version-branches.yml", "discover", "Resolve targets from GitHub")
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for tool in ("git", "gh"):
                command = folder / tool
                command.write_text("#!/bin/sh\necho 'legacy controller reached an external tool' >&2\nexit 91\n")
                command.chmod(0o755)
            python = folder / "python3"
            python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
            python.chmod(0o755)
            for event, requested in (("push", ""), ("repository_dispatch", ""),
                                     ("workflow_dispatch", "forge-and-fabric-1.20.1")):
                with self.subTest(event=event):
                    output = folder / "output"
                    summary = folder / "summary"
                    output.write_text("")
                    summary.write_text("")
                    # A minimal environment prevents BASH_ENV/ENV hooks from replacing fixtures.
                    env = {"PATH": str(folder) + os.pathsep + os.defpath,
                           "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary),
                           "GITHUB_EVENT_NAME": event, "REQUESTED_TARGET": requested}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual("value=[]\n", output.read_text())
                    self.assertIn("version-branch porting is retired", summary.read_text())

    def test_delayed_port_results_require_protected_layout_before_candidate_inspection(self):
        layout = job_block("handle-version-port-result.yml", "source-layout")
        inspect = job_block("handle-version-port-result.yml", "inspect")
        self.assertIn("ref: ${{ github.sha }}", layout)
        self.assertIn("persist-credentials: false", layout)
        self.assertNotIn("client_payload.head_sha", layout)
        self.assertIn("python3 scripts/release/release_sources.py --kind mode", layout)
        self.assertIn("needs: source-layout", inspect)
        self.assertIn("needs.source-layout.outputs.mode == 'version-branches' &&", inspect)


class SharedPagesProducerTest(unittest.TestCase):
    def test_matrix_rows_partition_the_real_packaged_artifact_names(self):
        data = load_matrix(DEFAULT_MATRIX)
        names = {"packaged-e2e-" + row["id"] for row in
                 gha_matrix(data, "pr-anchors", read_mod_version(DEFAULT_MATRIX, data))["include"]}
        covered = set()
        rows = inventory()["include"]
        for row in rows:
            selected = {name for name in names if fnmatch.fnmatchcase(name, row["artifact_pattern"])}
            self.assertTrue(selected)
            self.assertFalse(selected & covered)
            covered |= selected
            self.assertEqual("master", row["source_branch"])
        self.assertEqual(names, covered)
        self.assertEqual({"mc" + row["artifact_version"] for row in data["artifacts"]},
                         {row["bundle_key"] for row in rows})
        self.assertEqual([data["unit_test_version"]],
                         [row["minecraft_target"] for row in rows if row["raw_retention_days"] == 90])

    def test_actual_inventory_shell_requires_the_real_source_branch(self):
        script = step_script("on-demand-e2e.yml", "pages-inventory",
                             "Derive every public target from the validated release matrix")
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            python = folder / "python3"
            python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
            python.chmod(0o755)
            for branch in ("master", "mc1.20.1", "feature/test", "forge-and-fabric-1.20.1"):
                with self.subTest(branch=branch):
                    output = folder / "output"
                    output.write_text("")
                    env = {"PATH": str(folder) + os.pathsep + os.defpath,
                           "GITHUB_OUTPUT": str(output), "GITHUB_REF_NAME": branch}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
                    if branch == "master":
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual(inventory(), json.loads(output.read_text().removeprefix("targets=")))
                    else:
                        self.assertNotEqual(0, result.returncode)
                        self.assertEqual("", output.read_text())
