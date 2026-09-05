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

    def test_actual_pages_discovery_requires_complete_handoffs_without_version_branch_queries(self):
        script = step_script("pages.yml", "discover", "Discover matrix targets and validate the wake-up event")
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        rows = inventory()["include"]
        artifacts = [{"id": index + 1, "name": "pages-e2e-" + row["bundle_key"],
                      "expired": False, "size_in_bytes": 100,
                      "workflow_run": {"id": 42, "head_branch": "master", "head_sha": sha}}
                     for index, row in enumerate(rows)]
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            for command in ("python3",):
                executable = folder / command
                executable.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
                executable.chmod(0o755)
            fixture_api = folder / "fixture_api.py"
            fixture_api.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if not args or args[0] != "api": raise SystemExit("Only read-only API calls are allowed")
endpoint=next((value for value in args[1:] if value.startswith("repos/")), "")
with Path(os.environ["FIXTURE_CALLS"]).open("a") as output: output.write(endpoint+"\\n")
repo="repos/The-Plum-Team/Quick-Skin-Mod"
sha=os.environ["FIXTURE_SHA"]
path=endpoint.split("?",1)[0]
if path == repo+"/branches/master":
 data={"commit":{"sha":"b"*40 if os.environ["FIXTURE_CASE"] == "advanced" else sha}}
elif path == repo+"/actions/workflows/on-demand-e2e.yml": data={"id":77}
elif path == repo+"/actions/workflows/pages.yml": data={"id":88}
elif path == repo+"/actions/runs/42":
 data={"id":42,"workflow_id":77,"status":"completed","conclusion":"success",
       "event":"workflow_dispatch","head_branch":"master","head_sha":sha,
       "path":".github/workflows/on-demand-e2e.yml",
       "head_repository":{"full_name":"The-Plum-Team/Quick-Skin-Mod"}}
elif path == repo+"/actions/runs/42/artifacts":
 items=json.loads(Path(os.environ["FIXTURE_ARTIFACTS"]).read_text())
 if os.environ["FIXTURE_CASE"] == "missing": items=items[:-1]
 data=[{"artifacts":items}]
elif path == repo+"/actions/artifacts" and "name=pages-cache-mc" in endpoint:
 data=[{"artifacts":[]}]
elif path == repo+"/actions/workflows/77/runs": data={"total_count":0,"workflow_runs":[]}
else: raise SystemExit("Unexpected fixture endpoint: "+endpoint)
if "--jq" in args:
 query=args[args.index("--jq")+1]
 if query == ".commit.sha": print(data["commit"]["sha"])
 elif query == ".id": print(data["id"])
 else: raise SystemExit("Unexpected jq query")
else: print(json.dumps(data))
''')
            gh = folder / "gh"
            gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " +
                          shlex.quote(str(fixture_api)) + ' "$@"\n')
            gh.chmod(0o755)
            artifact_file = folder / "artifacts.json"
            artifact_file.write_text(json.dumps(artifacts))
            for case in ("manual", "complete", "missing", "advanced"):
                with self.subTest(case=case):
                    output, calls = folder / "output", folder / "calls"
                    output.write_text("")
                    calls.write_text("")
                    env = {"PATH": str(folder) + os.pathsep + "/opt/homebrew/bin" + os.pathsep + os.defpath,
                           "GITHUB_OUTPUT": str(output), "RUNNER_TEMP": str(folder),
                           "GITHUB_REF": "refs/heads/master", "GITHUB_SHA": sha,
                           "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod", "GH_TOKEN": "local-fixture",
                           "DISPATCH_OPERATION": "manual" if case in {"manual", "advanced"} else "deploy",
                           "DISPATCH_BRANCH": "master", "DISPATCH_RUN_ID": "42", "DISPATCH_SHA": sha,
                           "FIXTURE_CASE": case, "FIXTURE_SHA": sha, "FIXTURE_CALLS": str(calls),
                           "FIXTURE_ARTIFACTS": str(artifact_file)}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=30)
                    fields = dict(line.split("=", 1) for line in output.read_text().splitlines())
                    if case in {"manual", "complete"}:
                        self.assertEqual(0, result.returncode, result.stderr)
                        self.assertEqual("true", fields["eligible"])
                        self.assertEqual([row["bundle_key"] for row in rows], json.loads(fields["bundle_keys"]))
                        self.assertEqual("master", fields["source_branch"])
                        self.assertEqual(sha, fields["source_sha"])
                    else:
                        self.assertEqual("false", fields["eligible"])
                        self.assertEqual("[]", fields["bundle_keys"])
                        if case == "missing":
                            self.assertNotEqual(0, result.returncode)
                            self.assertIn("incomplete public target handoffs", result.stderr)
                    branch_calls = [call for call in calls.read_text().splitlines() if "/branches" in call]
                    self.assertTrue(branch_calls)
                    self.assertEqual({"repos/The-Plum-Team/Quick-Skin-Mod/branches/master"}, set(branch_calls))
