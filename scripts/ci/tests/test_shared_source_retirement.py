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
    def test_shared_runtime_wakes_visual_review_only_for_the_current_protected_commit(self):
        script = step_script("on-demand-e2e.yml", "notify-shared-review",
                             "Wake visual review for the completed shared generation")
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            python = folder / "python3"
            python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
            python.chmod(0o755)
            fixture = folder / "api.py"
            fixture.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]
endpoint=next((arg for arg in args if arg.startswith("repos/")), "")
if args[0] != "api": raise SystemExit("Unexpected command")
if endpoint.endswith("/branches/master"):
 print(os.environ["FIXTURE_LIVE_SHA"])
elif endpoint.endswith("/dispatches") and "POST" in args:
 payload=Path(args[args.index("--input")+1]).read_text()
 Path(os.environ["FIXTURE_SENT"]).write_text(payload)
else: raise SystemExit("Unexpected API endpoint")
''')
            gh = folder / "gh"
            gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(fixture)) + ' "$@"\n')
            gh.chmod(0o755)
            for case in ("current", "advanced", "checkout-advanced", "wrong-event", "wrong-ref", "wrong-id"):
                with self.subTest(case=case):
                    sent = folder / "sent.json"
                    sent.unlink(missing_ok=True)
                    env = {"PATH": str(folder) + os.pathsep + "/opt/homebrew/bin" + os.pathsep + os.defpath,
                           "GITHUB_EVENT_NAME": "push" if case == "wrong-event" else "workflow_dispatch",
                           "GITHUB_REF": "refs/heads/topic" if case == "wrong-ref" else "refs/heads/master",
                           "GITHUB_SHA": "b" * 40 if case == "checkout-advanced" else sha,
                           "GITHUB_RUN_ID": "true" if case == "wrong-id" else "55",
                           "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod", "RUNNER_TEMP": str(folder),
                           "FIXTURE_LIVE_SHA": "b" * 40 if case == "advanced" else sha,
                           "FIXTURE_SENT": str(sent), "GH_TOKEN": "local-fixture"}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
                    self.assertEqual(case == "current", sent.exists(), result.stderr[:1000])
                    self.assertEqual(case in {"current", "advanced", "checkout-advanced"}, result.returncode == 0)
                    if sent.exists():
                        self.assertEqual({"event_type": "visual-review-requested", "client_payload": {
                            "source_repository": "The-Plum-Team/Quick-Skin-Mod", "source_run_id": "55",
                            "source_sha": sha, "source_branch": "master"}}, json.loads(sent.read_bytes()))

    def test_visual_consumer_rejects_a_stale_or_foreign_shared_dispatch_before_curation(self):
        script = step_script("visual-review.yml", "authenticate", "Resolve the exact trusted source run")
        start = script.index('if [[ "$GITHUB_EVENT_NAME" == repository_dispatch ]]; then',
                             script.index('source_run_attempt='))
        excerpt = 'set -euo pipefail\n' + script[start:script.index('# A PR targeting master', start)]
        sha = "a" * 40
        for branch, source, event in (("master", sha, "workflow_dispatch"), ("master", "b" * 40, "workflow_dispatch"),
                                      ("feature/example", sha, "workflow_dispatch"), ("master", sha, "pull_request")):
            with self.subTest(branch=branch, source=source, event=event):
                env = {"PATH": "/opt/homebrew/bin" + os.pathsep + os.defpath,
                       "GITHUB_EVENT_NAME": "repository_dispatch", "GITHUB_SHA": sha,
                       "source_run": json.dumps({"event": event}), "source_branch": branch, "source_sha": source}
                result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", excerpt],
                                        cwd=ROOT, env=env, text=True, capture_output=True, timeout=10)
                self.assertEqual(branch == "master" and source == sha and event == "workflow_dispatch",
                                 result.returncode == 0, result.stderr[:1000])

    def test_large_shared_pr_defers_before_the_bounded_release_diff_reader(self):
        authenticate = step_script("visual-review.yml", "authenticate", "Resolve the exact trusted source run")
        start = authenticate.index('source_pr="$(github_api_retry')
        stop = authenticate.index('changed_files="$(jq -r .changed_files', start)
        script = 'set -euo pipefail\ngithub_api_retry() { cat "$FIXTURE_PR"; }\n' + authenticate[start:stop]
        original = {"number": 1925, "state": "open", "merged": False,
                    "head": {"ref": "refactor/example", "sha": "a" * 40,
                             "repo": {"full_name": "The-Plum-Team/Quick-Skin-Mod"}},
                    "base": {"ref": "master", "repo": {"full_name": "The-Plum-Team/Quick-Skin-Mod"}}}
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary) / "pr.json"
            for base, count in (("master", 1), ("master", 492), ("master", 0),
                                ("master", True), ("master", 1.5), ("master", None),
                                ("forge-and-fabric-1.20.1", 100), ("forge-and-fabric-1.20.1", 101)):
                with self.subTest(base=base, count=count):
                    data = {**original, "changed_files": count,
                            "base": {**original["base"], "ref": base}}
                    fixture.write_text(json.dumps(data))
                    env = {"PATH": "/opt/homebrew/bin" + os.pathsep + os.defpath,
                           "FIXTURE_PR": str(fixture), "source_pr_number": "1925",
                           "source_branch": "refactor/example", "source_sha": "a" * 40,
                           "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod"}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=10)
                    valid = type(count) is int and count > 0 and (base == "master" or count <= 100)
                    self.assertEqual(valid, result.returncode == 0, result.stderr[:1500])
                    self.assertEqual(valid and base == "master", "Deferring PR" in result.stdout)

    def test_shared_build_requests_one_runtime_run_without_reopening_version_ports(self):
        script = step_script("build-gate.yml", "request-shared-e2e", "Request one current shared-source runtime generation")
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            python = folder / "python3"
            python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
            python.chmod(0o755)
            fixture = folder / "api.py"
            fixture.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]; case=os.environ["FIXTURE_CASE"]; sha=os.environ["FIXTURE_SHA"]
calls=Path(os.environ["FIXTURE_CALLS"])
previous=calls.read_text().splitlines()
with calls.open("a") as output: output.write(json.dumps(args)+"\\n")
if args == ["workflow","run","on-demand-e2e.yml","--ref","master"]: raise SystemExit(0)
if not args or args[0] != "api": raise SystemExit("Unexpected fixture mutation")
endpoint=next((arg for arg in args if arg.startswith("repos/")), "")
if endpoint.endswith("/branches/master"):
 branch_reads=sum("/branches/master" in line for line in previous)
 print("b"*40 if case == "advanced" or (case == "advanced-late" and branch_reads) else sha)
elif "/actions/workflows/on-demand-e2e.yml/runs?" in endpoint:
 if "head_sha="+sha not in endpoint: raise SystemExit("Missing exact source query")
 run={"id":42,"head_sha":sha,"head_branch":"master",
      "head_repository":{"full_name":"The-Plum-Team/Quick-Skin-Mod"},
      "path":".github/workflows/on-demand-e2e.yml","event":"workflow_dispatch",
      "status":"completed","conclusion":"success"}
 if case == "active": run.update(status="in_progress",conclusion=None)
 if case == "failed": run["conclusion"]="failure"
 runs=[] if case in {"empty","advanced-late","too-many","incomplete"} else [run]
 total=101 if case == "too-many" else 1 if case == "incomplete" else len(runs)
 print(json.dumps({"total_count":total,"workflow_runs":runs}))
else: raise SystemExit("Unexpected fixture API endpoint")
''')
            gh = folder / "gh"
            gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(fixture)) + ' "$@"\n')
            gh.chmod(0o755)
            for case in ("empty", "active", "success", "failed", "advanced", "advanced-late", "too-many", "incomplete", "wrong-event"):
                with self.subTest(case=case):
                    calls = folder / "calls"
                    calls.write_text("")
                    env = {"PATH": str(folder) + os.pathsep + "/opt/homebrew/bin" + os.pathsep + os.defpath,
                           "GITHUB_REF": "refs/heads/master", "GITHUB_SHA": sha,
                           "GITHUB_EVENT_NAME": "workflow_dispatch" if case == "wrong-event" else "push",
                           "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod", "GH_TOKEN": "local-fixture",
                           "FIXTURE_CASE": case, "FIXTURE_SHA": sha, "FIXTURE_CALLS": str(calls)}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=20)
                    dispatched = [json.loads(line) for line in calls.read_text().splitlines()
                                  if json.loads(line)[:2] == ["workflow", "run"]]
                    self.assertEqual(1 if case in {"empty", "failed"} else 0, len(dispatched), result.stderr[:1500])
                    self.assertEqual(case not in {"too-many", "incomplete", "wrong-event"}, result.returncode == 0,
                                     result.stderr[:1500])

    def test_shared_runtime_reuses_only_the_exact_successful_master_push_build(self):
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            fixture = folder / "api.py"
            fixture.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]; sha=os.environ["FIXTURE_SHA"]
endpoint=next((arg for arg in args if arg.startswith("repos/")), "")
if args[:3] != ["api","--method","GET"]:
 raise SystemExit("Expected a read-only Build API query")
if "/actions/workflows/build-gate.yml/runs?" in endpoint:
 if "event=push" not in endpoint or "head_sha="+sha not in endpoint:
  raise SystemExit("Unexpected build-reuse source")
 print(json.dumps({"total_count":1,"workflow_runs":[json.loads(Path(os.environ["FIXTURE_RUN"]).read_text())]}))
elif endpoint.endswith("/actions/runs/42/attempts/1/jobs?per_page=100&page=1"):
 print(json.dumps({"total_count":1,"jobs":[{"id":43,"run_id":42,"run_attempt":1,"head_sha":sha,
  "name":"Build and verify","status":"completed","conclusion":"success"}]}))
elif endpoint.endswith("/actions/runs/42/artifacts?per_page=100"):
 print(json.dumps({"total_count":1,"artifacts":[{"id":44,"name":"staged-release-bundle",
  "expired":False,"size_in_bytes":100,"digest":"sha256:"+"c"*64}]}))
else: raise SystemExit("Unexpected Build API query")
''')
            gh = folder / "gh"
            gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(fixture)) + ' "$@"\n')
            gh.chmod(0o755)
            event = folder / "event.json"
            event.write_text("{}\n")
            original = {"id": 42, "run_attempt": 1, "status": "completed", "conclusion": "success", "event": "push",
                        "path": ".github/workflows/build-gate.yml", "head_branch": "master", "head_sha": sha,
                        "head_repository": {"full_name": "The-Plum-Team/Quick-Skin-Mod"}}
            for field, value in ((None, None), ("event", "pull_request"), ("head_sha", "b" * 40),
                                 ("head_branch", "feature/elsewhere"), ("conclusion", "failure"), ("id", True)):
                with self.subTest(field=field):
                    run = dict(original)
                    if field is not None: run[field] = value
                    fixture_run, output = folder / "run.json", folder / "output"
                    fixture_run.write_text(json.dumps(run)); output.write_text("")
                    env = {"PATH": str(folder) + os.pathsep + os.defpath,
                           "GITHUB_REF_NAME": "master", "GITHUB_SHA": sha, "GITHUB_EVENT_NAME": "workflow_dispatch",
                           "GITHUB_EVENT_PATH": str(event), "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod",
                           "GITHUB_OUTPUT": str(output), "FIXTURE_SHA": sha, "FIXTURE_RUN": str(fixture_run)}
                    result = subprocess.run([sys.executable, str(ROOT / "scripts/ci/staged_build_bundle.py"),
                                             "--wait-seconds", "0"], cwd=ROOT, env=env,
                                            text=True, capture_output=True, timeout=20)
                    self.assertEqual(field in {None, "head_branch"}, result.returncode == 0, result.stderr[:1500])
                    expected = ("reused=true\nrun_id=42\nartifact_id=44\n" if field is None
                                else "reused=false\n" if field == "head_branch" else "")
                    self.assertEqual(expected, output.read_text())

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
