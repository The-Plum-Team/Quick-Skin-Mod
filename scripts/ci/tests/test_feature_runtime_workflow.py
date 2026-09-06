from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from test_workflow_security import COMPOSITE_ACTIONS, job_block, step_script


def action_script(name):
    text = (COMPOSITE_ACTIONS / "run-packaged-e2e/action.yml").read_text()
    step = text.split("    - name: " + name + "\n", 1)[1].split("\n    - name:", 1)[0]
    return textwrap.dedent(step.split("      run: |\n", 1)[1])


class FeatureRuntimeWorkflowTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        binary = self.root / "bin"
        binary.mkdir()
        self.environment = {"PATH": str(binary) + ":/usr/bin:/bin", "RUNNER_TEMP": str(self.root),
            "GITHUB_WORKSPACE": str(self.root), "GITHUB_OUTPUT": str(self.root / "outputs"),
            "GITHUB_STEP_SUMMARY": str(self.root / "summary"), "GITHUB_RUN_ID": "66",
            "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod", "RECORD": str(self.root / "calls")}
        self.binary("xvfb-run", "import json,os,sys\nopen(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n")
        self.binary("sha256sum", "import hashlib,sys\nprint(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest()+'  '+sys.argv[1])\n")
        self.binary("git", "import os,sys\nprint(os.environ['POLICY_SHA'] if sys.argv[2]=='protected-policy' else os.environ['TESTED_SHA'])\n")
        self.selection = self.root / "feature-selection/selection.json"
        self.selection.parent.mkdir()
        self.selection.write_text(json.dumps({"selection": {"runs": [
            {"scenario": "full"}, {"scenario": "feature-navigation"}]}}) + "\n")

    def binary(self, name, script):
        path = self.root / "bin" / name
        path.write_text("#!" + sys.executable + "\n" + script)
        path.chmod(0o755)

    def run_script(self, script, environment):
        (self.root / "outputs").write_text("")
        (self.root / "calls").write_text("")
        result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
            cwd=self.root, env={**self.environment, **environment}, text=True, capture_output=True)
        outputs = dict(line.split("=", 1) for line in (self.root / "outputs").read_text().splitlines())
        calls = [json.loads(line) for line in (self.root / "calls").read_text().splitlines()]
        return result, outputs, calls

    def runtime_environment(self):
        return {"E2E_ROW_JSON": "{}", "E2E_SCENARIOS": "phase0-smoke,full,session",
            "E2E_SELECTION_BASE": "a" * 40, "E2E_SELECTION_POLICY": "b" * 40,
            "E2E_SELECTION_SHA256": hashlib.sha256(self.selection.read_bytes()).hexdigest(),
            "RELEASE_TARGET": "", "E2E_COMPATIBILITY_MOD": "", "QUICKSKIN_E2E_CPM_MODEL_PATH": ""}

    def test_runtime_receives_exact_selected_scenarios_and_independent_git_provenance(self):
        result, outputs, calls = self.run_script(action_script("Run contract-declared packaged scenarios"),
                                                  self.runtime_environment())
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertEqual({}, outputs)
        self.assertEqual(1, len(calls))
        arguments = calls[0]
        self.assertEqual("full,feature-navigation", arguments[arguments.index("--scenarios") + 1])
        self.assertEqual("a" * 40, arguments[arguments.index("--selection-base") + 1])
        self.assertEqual("b" * 40, arguments[arguments.index("--selection-policy") + 1])
        self.assertEqual(str(self.selection), arguments[arguments.index("--selection-admission") + 1])

    def test_complete_runtime_keeps_its_authored_scenarios_without_selection_arguments(self):
        environment = self.runtime_environment()
        environment.update(E2E_SELECTION_BASE="", E2E_SELECTION_POLICY="", E2E_SELECTION_SHA256="")
        result, _, calls = self.run_script(action_script("Run contract-declared packaged scenarios"), environment)
        self.assertEqual(0, result.returncode, result.stderr[:300])
        arguments = calls[0]
        self.assertEqual(environment["E2E_SCENARIOS"], arguments[arguments.index("--scenarios") + 1])
        self.assertNotIn("--selection-admission", arguments)
        self.assertNotIn("", arguments)

    def test_tampered_incomplete_empty_or_compatibility_selection_never_launches_minecraft(self):
        for changes in ({"E2E_SELECTION_SHA256": "0" * 64}, {"E2E_SELECTION_BASE": ""},
                        {"E2E_SELECTION_POLICY": "b" * 40 + "\ninjected=true"},
                        {"E2E_SELECTION_SHA256": ""}, {"RELEASE_TARGET": "1.21.5"},
                        {"E2E_COMPATIBILITY_MOD": "cpm"}):
            with self.subTest(changes=changes):
                result, _, calls = self.run_script(action_script("Run contract-declared packaged scenarios"),
                    {**self.runtime_environment(), **changes})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], calls)
        self.selection.write_text('{"selection":{"runs":[]}}\n')
        result, _, calls = self.run_script(action_script("Run contract-declared packaged scenarios"),
                                          self.runtime_environment())
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_selector_or_upload_failure_cannot_publish_partial_runtime_arguments(self):
        script = step_script("on-demand-e2e.yml", "feature-policy", "Resolve unavailable selection to complete captures")
        environment = {"SELECTION_OUTCOME": "success", "UPLOAD_OUTCOME": "success", "SELECTIVE": "true",
            "SELECTION_BASE": "a" * 40, "SELECTION_POLICY": "b" * 40, "SELECTION_SHA256": "c" * 64}
        result, outputs, _ = self.run_script(script, environment)
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertEqual("true", outputs["selective"])
        for changes in ({"SELECTION_OUTCOME": "failure"}, {"UPLOAD_OUTCOME": "failure"},
                        {"UPLOAD_OUTCOME": "skipped"}, {"SELECTIVE": "false"},
                        {"SELECTION_SHA256": "invalid"}, {"SELECTION_BASE": "bad\nhead=bad"}):
            with self.subTest(changes=changes):
                result, outputs, _ = self.run_script(script, {**environment, **changes})
                self.assertEqual(0, result.returncode, result.stderr[:300])
                self.assertEqual({"selective": "false", "base": "", "policy": "", "selection_sha256": ""}, outputs)

    def test_first_migration_uses_full_coverage_and_only_protected_code_can_run(self):
        script = step_script("on-demand-e2e.yml", "feature-policy",
                             "Authenticate complete baseline and cumulative feature impact")
        environment = {"TESTED_SHA": "a" * 40, "POLICY_SHA": "b" * 40, "PULL_NUMBER": "88"}
        result, outputs, calls = self.run_script(script, environment)
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertEqual(({}, []), (outputs, calls))
        protected = self.root / "protected-policy/scripts/ci/feature_coverage_consumer.py"
        protected.parent.mkdir(parents=True)
        protected.write_text("import json,os,sys\nopen(os.environ['RECORD'],'a').write(json.dumps(sys.argv)+'\\n')\n")
        matrix = self.root / "protected-policy/release/release-matrix.json"
        matrix.parent.mkdir()
        matrix.write_text('{"schema_version":3}\n')
        self.binary("python3", "import os,sys\nos.execv(" + repr(sys.executable) + ", [" + repr(sys.executable) + ", *sys.argv[1:]])\n")
        result, _, calls = self.run_script(script, environment)
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertEqual(1, len(calls))
        self.assertEqual("protected-policy/scripts/ci/feature_coverage_consumer.py", calls[0][0])
        self.assertEqual("88", calls[0][calls[0].index("--pull-number") + 1])
        self.assertEqual(str(self.root / "candidate-history"), calls[0][calls[0].index("--repository") + 1])
        self.assertEqual("a" * 40, calls[0][calls[0].index("--head") + 1])
        self.assertEqual("b" * 40, calls[0][calls[0].index("--policy") + 1])
        for changes in ({"POLICY_SHA": "not-a-commit"}, {"PULL_NUMBER": "88\n--unsafe"}):
            result, _, calls = self.run_script(script, {**environment, **changes})
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], calls)

    def test_workflow_preserves_complete_lane_gate_and_explicit_full_recovery(self):
        policy = job_block("on-demand-e2e.yml", "feature-policy")
        runtime = job_block("on-demand-e2e.yml", "e2e")
        self.assertIn("inputs.capture_coverage != 'full'", policy)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", policy)
        self.assertIn("github.ref == 'refs/heads/master'", policy)
        self.assertIn("inputs.attest_run_id == ''", policy)
        self.assertEqual(2, policy.count("persist-credentials: false"))
        self.assertIn("actions: read", policy)
        self.assertNotIn("write", policy)
        self.assertIn("always() && needs.runtime-policy.result == 'success'", runtime)
        self.assertIn("needs.build.result == 'success'", runtime)
        self.assertIn("needs.feature-policy.outputs.selection_sha256", runtime)
        self.assertIn("&& '0' || '1'", runtime)
        action = (COMPOSITE_ACTIONS / "run-packaged-e2e/action.yml").read_text()
        self.assertIn("name: e2e-feature-selection", action)
        self.assertIn("e2e-out/current/selection.json", action)
        self.assertIn("e2e-out/current/coverage.json", action)

    def test_selected_curation_routes_before_full_reference_resolution_and_revalidates_before_model_access(self):
        script = step_script("visual-review.yml", "curate", "Validate and curate exact packaged evidence")
        start = script.index('if [[ -n "$TARGET_MINECRAFT_VERSION" ]]; then',
                             script.index('mv "$RUNNER_TEMP/target-e2e-matrix.json"'))
        end = script.index("# Resolve the reference only after", start)
        route = script[start:end] + '\nprintf "full\\n" >> "$RUNNER_TEMP/continued"\n'
        self.binary("python3", "import json,os,sys\nfrom pathlib import Path\n"
            "open(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n"
            "selected=os.environ['FIXTURE_SELECTED']=='true'\n"
            "out=Path(sys.argv[sys.argv.index('--github-output')+1])\n"
            "out.write_text('selected='+str(selected).lower()+'\\nreview_mode=anchor-semantic\\ngeneration_sha='+os.environ['IMPLEMENTATION_SHA']+'\\n')\n"
            "print(json.dumps({'curated':True,'selected':selected}))\n")
        environment = {"TARGET_MINECRAFT_VERSION": "1.20.1", "TARGET_BUNDLE_KEY": "mc1.20.1",
            "matrix_kind": "pr-anchors", "IMPLEMENTATION_SHA": "b" * 40,
            "SOURCE_SHA": "c" * 40, "SOURCE_RUN_ID": "55", "FIXTURE_SELECTED": "true",
            "COMPATIBILITY_IMPACT": '{"schema_version":1,"compatibility_required":true,"paths":[],"impact_paths":[]}'}
        for changes, expected_calls, continued in (({}, 1, False), ({"FIXTURE_SELECTED": "false"}, 1, False),
                ({"TARGET_MINECRAFT_VERSION": ""}, 0, True), ({"matrix_kind": "native-anchors", "FIXTURE_SELECTED": "false"}, 1, False)):
            with self.subTest(changes=changes):
                (self.root / "continued").write_text("")
                result, outputs, calls = self.run_script(route, {**environment, **changes})
                self.assertEqual(0, result.returncode, result.stderr[:300])
                self.assertEqual(expected_calls, len(calls))
                self.assertEqual(continued, bool((self.root / "continued").read_text()))
                if calls:
                    self.assertEqual("scripts/ci/feature_review.py", calls[0][0])
                    self.assertEqual("b" * 40, calls[0][calls[0].index("--source-sha") + 1])
                    self.assertEqual("55", calls[0][calls[0].index("--source-run-id") + 1])
                if not continued:
                    self.assertEqual(changes.get("FIXTURE_SELECTED", "true"), outputs["selected"])
                    self.assertEqual("b" * 40, outputs["generation_sha"])
        drain = job_block("visual-review-drain.yml", "review")
        self.assertIn('--verify-proof "$proof" --manifest "$manifest"', drain)
        self.assertLess(drain.index("python3 scripts/ci/feature_review.py"),
                        drain.index("test -n \"$CLAUDE_CODE_OAUTH_TOKEN\""))
        self.assertIn('$proof.schema_version == 7 then ["feature_selection"]', drain)
        self.assertIn('"$proof_schema" != 7 && "$proof_schema" != 8', drain)


    def test_complete_shared_review_dispatches_one_target_with_the_ten_field_github_limit(self):
        script = step_script("visual-review-drain.yml", "release-mod-compatibility",
                             "Authenticate the merge, normalized report, and exact release tree")
        start = script.index('if [[ "$SOURCE_BRANCH" == master ]]; then')
        end = script.index('[[ "$SOURCE_BRANCH" =~ ^automation/sync/', start)
        route = "set -euo pipefail\n" + script[start:end]
        self.binary("github_api_retry", "import json,os,sys\n"
            "open(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n"
            "if '/branches/master' in sys.argv[1]: print(os.environ['LIVE_SHA'])\n"
            "elif '/artifacts/' in sys.argv[1]: print(os.environ['REPORT_METADATA'])\n"
            "else: print('{}')\n")
        record = {"id": 8000, "name": "visual-review-55--mc1.21.1", "expired": False,
            "size_in_bytes": 1024, "digest": "sha256:" + "a" * 64,
            "workflow_run": {"id": 66, "head_branch": "master", "head_sha": "b" * 40}}
        environment = {"SOURCE_BRANCH": "master", "SOURCE_RUN_ID": "55", "SOURCE_SHA": "b" * 40,
            "GITHUB_SHA": "b" * 40, "LIVE_SHA": "b" * 40, "REVIEW_ARTIFACT_ID": "8000",
            "REPORT_METADATA": json.dumps(record)}
        result, _, calls = self.run_script(route, environment)
        self.assertEqual(0, result.returncode, result.stderr[:500])
        self.assertEqual(3, len(calls))
        payload = json.loads((self.root / "shared-mod-compatibility-dispatch.json").read_text())
        self.assertEqual("mod-compatibility-requested", payload["event_type"])
        self.assertEqual(10, len(payload["client_payload"]))
        self.assertEqual(record["name"], payload["client_payload"]["review_artifact_name"])
        self.assertEqual("master", payload["client_payload"]["target_branch"])
        result, _, calls = self.run_script(route, {**environment, "LIVE_SHA": "c" * 40})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(calls))
        self.assertNotIn("--method", calls[0])
        result, _, calls = self.run_script(route, {**environment,
            "REPORT_METADATA": json.dumps({**record, "size_in_bytes": 4194305})})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(2, len(calls))
        self.assertFalse(any("--method" in call for call in calls))

    def test_shared_optional_wave_enters_protected_admission_and_recomputes_the_target_plan(self):
        script = step_script("mod-compatibility-e2e.yml", "admit",
                             "Authenticate the normalized report and exact merged tree")
        start = script.index('if [[ "$SOURCE_BRANCH" == master || "$TARGET_BRANCH" == master ]]; then')
        end = script.index('[[ "$SOURCE_RUN_ID" =~', start)
        self.binary("python3", "import json,os,sys\n"
            "open(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n")
        result, _, calls = self.run_script("set -euo pipefail\n" + script[start:end], {
            "SOURCE_BRANCH": "master", "TARGET_BRANCH": "master", "GITHUB_SHA": "a" * 40,
            "GITHUB_EVENT_PATH": str(self.root / "event.json")})
        self.assertEqual(0, result.returncode, result.stderr[:500])
        self.assertEqual(1, len(calls))
        self.assertEqual("scripts/ci/shared_compatibility.py", calls[0][0])
        self.assertEqual("a" * 40, calls[0][calls[0].index("--policy-sha") + 1])
        self.binary("python3", "raise SystemExit(2)\n")
        result, _, _ = self.run_script("set -euo pipefail\n" + script[start:end], {
            "SOURCE_BRANCH": "master", "TARGET_BRANCH": "master", "GITHUB_SHA": "a" * 40,
            "GITHUB_EVENT_PATH": str(self.root / "event.json")})
        self.assertNotEqual(0, result.returncode)
        prepare = job_block("mod-compatibility-e2e.yml", "prepare")
        self.assertIn('target_arguments+=(--minecraft-target "$MINECRAFT_TARGET")', prepare)
        self.assertIn('--validate-plan "$RUNNER_TEMP/mod-compatibility-plan.json"', prepare)
        reviewer = job_block("mod-compatibility-review.yml", "enumerate")
        self.assertIn('--validate-plan "$plan"', reviewer)
        self.assertLess(reviewer.index('--validate-plan "$plan"'), reviewer.index('pending_count='))

    def test_public_producer_uses_real_selector_outputs_and_independent_admission_inputs(self):
        producer = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        policy = job_block("on-demand-e2e.yml", "feature-policy")
        declared = set(re.findall(r"^      ([a-z_0-9]+): \$\{\{ steps.result.outputs", policy, re.MULTILINE))
        self.assertTrue(set(re.findall(r"needs.feature-policy.outputs.([a-z_0-9]+)", producer)) <= declared)
        self.assertIn("needs.pages-inventory.result == 'success'", producer)
        script = step_script("on-demand-e2e.yml", "prepare-pages-evidence", "Prepare the curated SHA-bound evidence bundle")
        e2e = self.root / "e2e-out"
        e2e.mkdir()
        (e2e / "selection.json").write_bytes(self.selection.read_bytes())
        (e2e / "coverage.json").write_text("{}")
        self.binary("python3", "import json,os,sys\nopen(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n")
        environment = {"MINECRAFT_TARGET": "1.20.1", "SOURCE_RUN_ID": "55", "SOURCE_BRANCH": "master",
            "SOURCE_SHA": "b" * 40, "SOURCE_CREATED_AT": "2026-09-06T02:00:00Z", "TARGET_SHA": "b" * 40,
            "TARGET_CREATED_AT": "2026-09-06T02:00:00Z", "GITHUB_REF_NAME": "master",
            "SELECTION_ENABLED": "true", "SELECTION_BASE": "a" * 40, "SELECTION_POLICY": "b" * 40,
            "SELECTION_SHA256": hashlib.sha256(self.selection.read_bytes()).hexdigest()}
        result, _outputs, calls = self.run_script(script, environment)
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertEqual(1, len(calls))
        for argument, value in (("--selection-base", "a" * 40), ("--selection-policy", "b" * 40),
                                ("--source-sha", "b" * 40), ("--selection-admission", "e2e-out/selection.json")):
            self.assertEqual(value, calls[0][calls[0].index(argument) + 1])
        result, _outputs, calls = self.run_script(script, {**environment, "SELECTION_ENABLED": "false"})
        self.assertEqual(0, result.returncode, result.stderr[:300])
        self.assertNotIn("--selection-admission", calls[0])
        result, _outputs, calls = self.run_script(script, {**environment, "SELECTION_SHA256": "0" * 64})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_pages_routes_selected_handoffs_and_caches_through_reauthentication_before_promotion(self):
        script = step_script("pages.yml", "collect", "Compose authenticated feature evidence with its complete baseline")
        self.binary("python3", "import json,os,sys\nfrom pathlib import Path\n"
            "open(os.environ['RECORD'],'a').write(json.dumps(sys.argv[1:])+'\\n')\n"
            "root=Path('composed-evidence/mc1.20.1');root.mkdir(parents=True)\n"
            "(root/'manifest.json').write_text('{}')\n")
        for schema, artifact, expected_calls in ((3, "pages-e2e-mc1.20.1", 0),
                (5, "pages-e2e-mc1.20.1", 1), (7, "pages-cache-mc1.20.1--" + "b" * 40, 1)):
            with self.subTest(schema=schema):
                for name in ("selected-evidence", "source-feature-evidence", "composed-evidence"):
                    path = self.root / name
                    if path.exists(): shutil.rmtree(path)
                root = self.root / "selected-evidence/mc1.20.1"
                root.mkdir(parents=True)
                (root / "manifest.json").write_text(json.dumps({"schema_version": schema}))
                result, _outputs, calls = self.run_script(script, {"BUNDLE_KEY": "mc1.20.1",
                    "ARTIFACT_NAME": artifact, "OWNER_RUN_ID": "55", "SOURCE_SHA": "b" * 40})
                self.assertEqual(0, result.returncode, result.stderr[:300])
                self.assertEqual(expected_calls, len(calls))
                if calls:
                    self.assertEqual("b" * 40, calls[0][calls[0].index("--source-sha") + 1])
                    self.assertEqual(schema == 5, "--artifact-run-id" in calls[0])
                    self.assertTrue((self.root / "source-feature-evidence/mc1.20.1/manifest.json").is_file())


if __name__ == "__main__":
    unittest.main()
