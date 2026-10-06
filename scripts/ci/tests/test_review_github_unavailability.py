"""GitHub API unavailability must retain an unreviewed queue input instead of retiring it.

Run 37411958425 attempt 1 hit a GitHub installation quota exhaustion before any model call. Its
cancelled-report recovery raised ``GitHubGetUnavailable``, the classifier fell back to a
non-transient ``protected_validation`` marker and the cleanup job tried to delete the input. These
regressions execute the real workflow excerpts and protected scripts with offline fixtures.
"""
from __future__ import annotations

from contextlib import redirect_stderr, redirect_stdout
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "e2e"))

import feature_coverage_github as publisher
import visual_review_completed as completed
import visual_review_preparation as preparation
from test_workflow_security import job_block, step_script

DRAIN = "visual-review-drain.yml"
COMPATIBILITY = "mod-compatibility-review.yml"
GUARD = "Revalidate the artifact-scoped queue entry"
RESTORE = "Restore the exact authenticated preparation"
RECOVERY = "Recover a complete result after publication cancellation"
CLASSIFY = "Classify a failed attempt without provider text"
ADMISSION = "Revalidate current master before batch or model admission"
COMPATIBILITY_CLASSIFY = "Classify a failed batch without provider text"
QUOTA_GATE = "Detect a quota rejection without reading provider output"
HELPER = ROOT / "scripts/ci/github_api_retry.sh"
GENERATION = "a" * 40
SIGNAL_ENV = "GITHUB_API_RETRY_UNAVAILABLE_SIGNAL"
TRANSIENT = {"category": "github_transport_unavailable", "schema_version": 1, "transient": True}
TERMINAL = {"schema_version": 1, "category": "protected_validation", "stage": "drain", "transient": False}

# Offline GitHub CLI. Each FIXTURE_* value is a JSON body or one of the failure classes below.
GH_FIXTURE = r'''
sleep() { :; }
fixture_response() {
  local name="FIXTURE_$1"
  case "${!name}" in
    UNAVAILABLE) printf 'gh: HTTP 502: Bad Gateway (https://api.github.com/private)\n' >&2; return 1 ;;
    QUOTA) printf 'gh: API rate limit exceeded for installation ID 1 (HTTP 403)\n' >&2; return 1 ;;
    MISSING) printf 'gh: Not Found (HTTP 404)\n' >&2; return 1 ;;
    *) printf '%s\n' "${!name}" ;;
  esac
}
gh() {
  local argument route=''
  [[ "$1" == api ]] || return 97
  for argument in "$@"; do
    case "$argument" in repos/*|rate_limit) route="$argument" ;; esac
  done
  printf '%s\n' "$route" >> "$RUNNER_TEMP/requests"
  case "$route" in
    rate_limit) printf '%s\n' "$FIXTURE_RESET" ;;
    *"/branches/master") fixture_response MASTER ;;
    *"artifacts?name=visual-review-55--mc1.21.6&"*) fixture_response REPORTS ;;
    *"artifacts?name="*) printf '[{"artifacts":[]}]\n' ;;
    *"/actions/artifacts/77") fixture_response METADATA ;;
    *"/actions/runs/88") fixture_response OWNER ;;
    *"/actions/runs/99") fixture_response REPORT_OWNER ;;
    *) printf 'unexpected route %s\n' "$route" >&2; return 97 ;;
  esac
}
'''

# Runs the real retained protected script from the control directory with an offline GET.
PYTHON_FIXTURE = r'''
import json, os, runpy, sys
from pathlib import Path
root = Path(os.environ["FIXTURE_ROOT"])
sys.path[:0] = [str(root / "scripts/ci"), str(root / "e2e")]
script = Path(sys.argv[1])
control = Path(os.environ["RUNNER_TEMP"]) / "protected-drain-control/scripts/ci"
if script.parent.resolve() != control.resolve():
    raise SystemExit(98)
mode = os.environ["FIXTURE_PYTHON_MODE"]
if mode == "skip":
    raise SystemExit(0)
import feature_coverage_github as publisher
def offline_get(endpoint, *, maximum):
    if mode == "unavailable":
        raise publisher.GitHubGetUnavailable("private-response")
    if mode == "empty":
        return json.dumps({"total_count": 0, "artifacts": []}).encode()
    if mode == "malformed":
        return json.dumps({"total_count": 1, "artifacts": []}).encode()
    if mode == "foreign":
        return json.dumps({"id": 10, "run_attempt": 1, "head_sha": "f" * 40}).encode()
    raise SystemExit(99)
publisher._get = offline_get
sys.argv = [str(root / "scripts/ci" / script.name), *sys.argv[2:]]
runpy.run_path(sys.argv[0], run_name="__main__")
'''


def tool_path() -> str:
    bash, jq = shutil.which("bash"), shutil.which("jq")
    if bash is None or jq is None:
        raise AssertionError("workflow excerpts require bash and jq")
    return os.pathsep.join((str(Path(jq).parent), str(Path(bash).parent), os.defpath))


def base_environment(runner_temp: Path) -> dict[str, str]:
    environment = {"PATH": tool_path(), "RUNNER_TEMP": runner_temp.as_posix(),
                   "GITHUB_OUTPUT": (runner_temp / "output").as_posix(),
                   "GITHUB_REPOSITORY": "example/quick-skin", "GITHUB_RUN_ID": "10",
                   "GITHUB_RUN_ATTEMPT": "1", "GITHUB_SHA": GENERATION}
    # Windows Python cannot start without its system root; CI runners ignore it.
    for name in ("SYSTEMROOT", "SystemRoot"):
        if name in os.environ:
            environment[name] = os.environ[name]
    return environment


def step_env(workflow: str, job: str, step: str) -> dict[str, str]:
    """Return a step's declared env block with every expression left unresolved."""
    block = job_block(workflow, job)
    start = block.index(f"      - name: {step}\n")
    body = block[start:block.index("        run: |\n", start)]
    if "        env:\n" not in body:
        return {}
    values = {}
    for line in body.split("        env:\n", 1)[1].splitlines():
        if not line.startswith("          ") or ":" not in line:
            break
        name, value = line.strip().split(":", 1)
        values[name] = value.strip().strip('"')
    return values


def resolved_env(workflow: str, job: str, step: str, runner_temp: Path, provided: dict[str, str]) -> dict[str, str]:
    environment = {}
    for name, value in step_env(workflow, job, step).items():
        if value.startswith("${{ runner.temp }}"):
            environment[name] = runner_temp.as_posix() + value.removeprefix("${{ runner.temp }}")
        elif "${{" in value:
            if name not in provided:
                raise AssertionError(f"{step} declares {name}; the excerpt test must provide it")
            environment[name] = provided[name]
        else:
            environment[name] = value
    return environment


def run_bash(script: str, environment: dict[str, str], *, cwd: Path) -> subprocess.CompletedProcess[str]:
    # A script file keeps long excerpts byte-exact; Windows command-line quoting can split them.
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "excerpt.sh"
        path.write_text(script, encoding="utf-8", newline="\n")
        return subprocess.run([shutil.which("bash") or "bash", path.as_posix()], cwd=cwd, env=environment,
                              capture_output=True, text=True, timeout=60)


def outputs(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if "=" in line)


def classify(runner_temp: Path, expressions: dict[str, str]) -> tuple[dict, dict[str, str]]:
    script = step_script(DRAIN, "review", CLASSIFY)
    values = {"steps.check.outputs.review_complete": "", "steps.guard.outcome": "success",
              "steps.capsule.outcome": "success", "steps.capsule.outputs.proof_transport_unavailable": "",
              "steps.capsule.outputs.github_transport_unavailable": "", "steps.recovered.outcome": "success",
              "steps.recovered.outputs.github_transport_unavailable": "", **expressions}
    for expression, value in values.items():
        script = script.replace("${{ " + expression + " }}", value)
    if "${{" in script:
        raise AssertionError("classifier excerpt has an unresolved expression")
    output = runner_temp / "classifier-output"
    output.unlink(missing_ok=True)
    environment = {**base_environment(runner_temp), "GITHUB_OUTPUT": output.as_posix()}
    result = run_bash("gh() { return 97; }\n" + script, environment, cwd=runner_temp)
    if result.returncode != 0:
        raise AssertionError(result.stderr)
    failure = runner_temp / "visual-review-capsule/visual-review-failure.json"
    return json.loads(failure.read_text()), outputs(output)


def cleanup_admitted(classification: dict) -> bool:
    block = job_block(DRAIN, "cleanup")
    condition = block.split("    if: >-\n", 1)[1].split("    runs-on:", 1)[0].strip()
    if not condition.startswith("always() &&"):
        raise AssertionError("cleanup condition changed shape")
    condition = condition.removeprefix("always() &&")
    for expression, value in {"needs.select.outputs.eligible": "true", "needs.review.outputs.review_complete": "false",
                              "needs.review.outputs.already_reviewed": "false",
                              "needs.review.outputs.capsule_missing": "false",
                              "needs.review.outputs.failure_category": classification["category"],
                              "needs.review.outputs.failure_transient": str(classification["transient"]).lower()}.items():
        condition = condition.replace(expression, "'" + value + "'")
    if "needs." in condition:
        raise AssertionError("cleanup condition has an unresolved need")
    result = subprocess.run([shutil.which("bash") or "bash", "-c", "[[ " + condition + " ]]"],
                            capture_output=True, text=True, timeout=10)
    if result.returncode not in (0, 1):
        raise AssertionError(result.stderr)
    return result.returncode == 0


class Workspace(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.runner_temp = Path(temporary.name)

    def assert_transient(self, stage: str, classification: dict, step_outputs: dict[str, str]):
        self.assertEqual({**TRANSIENT, "stage": stage}, classification)
        self.assertEqual({"failure_category": "github_transport_unavailable", "failure_transient": "true"}, step_outputs)
        self.assertFalse(cleanup_admitted(classification))

    def assert_terminal(self, classification: dict, step_outputs: dict[str, str]):
        self.assertEqual(TERMINAL, classification)
        self.assertEqual("false", step_outputs["failure_transient"])
        self.assertTrue(cleanup_admitted(classification))


class RetryHelperSignalTest(Workspace):
    def helper(self, invocation: str, *, signal=True, **fixtures):
        environment = {**base_environment(self.runner_temp), "FIXTURE_RESET": "1", "FIXTURE_MASTER": GENERATION,
                       **{f"FIXTURE_{name}": value for name, value in fixtures.items()}}
        if signal:
            environment[SIGNAL_ENV] = self.signal.as_posix()
        script = "set -euo pipefail\n" + GH_FIXTURE + f"source '{HELPER.as_posix()}'\n" + invocation
        return run_bash(script, environment, cwd=self.runner_temp)

    @property
    def signal(self) -> Path:
        return self.runner_temp / "unavailable"

    def test_exhausted_retryable_responses_record_an_empty_signal(self):
        for invocation in ('github_api_retry repos/example/quick-skin/branches/master',
                           'github_api_retry_to_file "$RUNNER_TEMP/archive" repos/example/quick-skin/branches/master'):
            for mode in ("UNAVAILABLE", "QUOTA"):
                with self.subTest(invocation=invocation, mode=mode):
                    self.signal.unlink(missing_ok=True)
                    (self.runner_temp / "requests").unlink(missing_ok=True)
                    result = self.helper(invocation, MASTER=mode)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual("", result.stdout)
                    self.assertEqual(b"", self.signal.read_bytes())
                    self.assertFalse((self.runner_temp / "archive").exists())
                    routes = (self.runner_temp / "requests").read_text().splitlines()
                    self.assertEqual(4, routes.count("repos/example/quick-skin/branches/master"))

    def test_rate_limit_beyond_the_caller_wait_records_the_signal_without_polling(self):
        for invocation in ('github_api_retry repos/example/quick-skin/branches/master',
                           'github_api_retry_to_file "$RUNNER_TEMP/archive" repos/example/quick-skin/branches/master'):
            with self.subTest(invocation=invocation):
                self.signal.unlink(missing_ok=True)
                (self.runner_temp / "requests").unlink(missing_ok=True)
                result = self.helper(invocation, MASTER="QUOTA", RESET="9999999999")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("beyond this caller maximum", result.stderr)
                self.assertTrue(self.signal.is_file())
                routes = (self.runner_temp / "requests").read_text().splitlines()
                self.assertEqual(1, routes.count("repos/example/quick-skin/branches/master"))

    def test_success_and_terminal_failures_clear_a_previous_signal(self):
        for invocation in ('github_api_retry repos/example/quick-skin/branches/master',
                           'github_api_retry_to_file "$RUNNER_TEMP/archive" repos/example/quick-skin/branches/master'):
            for mode, succeeds in ((GENERATION, True), ("MISSING", False)):
                with self.subTest(invocation=invocation, mode=mode):
                    self.signal.write_text("")
                    result = self.helper(invocation, MASTER=mode)
                    self.assertEqual(succeeds, result.returncode == 0, result.stderr)
                    self.assertFalse(self.signal.exists())

    def test_only_the_latest_call_decides_the_signal(self):
        tolerated = ('github_api_retry repos/example/quick-skin/branches/master || true\n'
                     'test -f "$GITHUB_API_RETRY_UNAVAILABLE_SIGNAL"\n'
                     'FIXTURE_MASTER=' + GENERATION + '\n'
                     'github_api_retry repos/example/quick-skin/branches/master\n')
        result = self.helper(tolerated, MASTER="UNAVAILABLE")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.signal.exists())

    def test_signal_is_opt_in_and_records_no_response_text(self):
        before = sorted(path.name for path in self.runner_temp.iterdir())
        result = self.helper('github_api_retry repos/example/quick-skin/branches/master', signal=False, MASTER="UNAVAILABLE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before + ["requests"], sorted(path.name for path in self.runner_temp.iterdir()))


class QueueGuardUnavailableTest(Workspace):
    def guard(self, **fixtures):
        name = f"visual-review-input-55-{GENERATION}--mc1.21.6"
        metadata = {"id": 77, "name": name, "digest": "sha256:" + "b" * 64, "size_in_bytes": 1024,
                    "expired": False, "workflow_run": {"id": 88, "head_branch": "master", "head_sha": GENERATION}}
        owner = {"id": 88, "status": "completed", "conclusion": "success", "event": "repository_dispatch",
                 "head_branch": "master", "head_sha": GENERATION, "path": ".github/workflows/visual-review.yml",
                 "head_repository": {"full_name": "example/quick-skin"}}
        values = {"REPORTS": json.dumps([{"artifacts": []}]), "METADATA": json.dumps(metadata),
                  "OWNER": json.dumps(owner), "REPORT_OWNER": "MISSING", "MASTER": "MISSING", "RESET": "1",
                  **fixtures}
        provided = {"ARTIFACT_DIGEST": "b" * 64, "ARTIFACT_ID": "77", "ARTIFACT_NAME": name, "ARTIFACT_RUN_ID": "88",
                    "ARTIFACT_SIZE": "1024", "BUNDLE_KEY": "mc1.21.6", "GENERATION_SHA": GENERATION,
                    "GH_TOKEN": "fixture", "IMPLEMENTATION_SHA": GENERATION, "REVIEW_KEY": "55--mc1.21.6",
                    "SOURCE_RUN_ID": "55", "PREPARED_MISSING": "false"}
        environment = {**base_environment(self.runner_temp),
                       **resolved_env(DRAIN, "review", GUARD, self.runner_temp, provided),
                       **{f"FIXTURE_{key}": value for key, value in values.items()}}
        result = run_bash(GH_FIXTURE + step_script(DRAIN, "review", GUARD), environment, cwd=self.runner_temp)
        return result, outputs(self.runner_temp / "output")

    def test_exhausted_guard_reads_retain_the_unreviewed_capsule(self):
        for mode in ("UNAVAILABLE", "QUOTA"):
            with self.subTest(mode=mode):
                result, step_outputs = self.guard(REPORTS=mode, RESET="9999999999")
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("true", step_outputs["reviewable"])
                self.assertTrue((self.runner_temp / "github-api-unavailable-guard").is_file())
                self.assert_transient("queue_guard", *classify(self.runner_temp, {"steps.guard.outcome": "failure"}))
                shutil.rmtree(self.runner_temp / "visual-review-capsule")

    def test_identity_failure_after_a_tolerated_outage_stays_terminal(self):
        reports = json.dumps([{"artifacts": [{"name": "visual-review-55--mc1.21.6", "workflow_run": {"id": 99},
                                              "expired": False}]}])
        changed = {"id": 77, "name": "foreign", "digest": "sha256:" + "c" * 64}
        result, _ = self.guard(REPORTS=reports, REPORT_OWNER="UNAVAILABLE", METADATA=json.dumps(changed))
        self.assertNotEqual(0, result.returncode)
        routes = (self.runner_temp / "requests").read_text().splitlines()
        self.assertEqual(4, sum(route.endswith("/actions/runs/99") for route in routes))
        self.assertFalse((self.runner_temp / "github-api-unavailable-guard").exists())
        self.assert_terminal(*classify(self.runner_temp, {"steps.guard.outcome": "failure"}))

    def test_missing_capsule_and_successful_guard_leave_no_signal(self):
        result, _ = self.guard(METADATA="MISSING")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.runner_temp / "github-api-unavailable-guard").exists())
        result, step_outputs = self.guard()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("true", step_outputs["reviewable"])
        self.assertFalse((self.runner_temp / "github-api-unavailable-guard").exists())


class ReviewerStepUnavailableTest(Workspace):
    def setUp(self):
        super().setUp()
        control = self.runner_temp / "protected-drain-control/scripts/ci"
        control.mkdir(parents=True)
        shutil.copy2(HELPER, control / HELPER.name)
        (self.runner_temp / "fixture_python.py").write_text(PYTHON_FIXTURE)
        # An exact implementation checkout without the current helper or protected scripts.
        self.checkout = self.runner_temp / "implementation"
        self.checkout.mkdir()

    def run_step(self, step: str, *, mode: str, master: str, provided: dict[str, str]):
        environment = {**base_environment(self.runner_temp),
                       **resolved_env(DRAIN, "review", step, self.runner_temp, provided),
                       "FIXTURE_ROOT": ROOT.as_posix(), "FIXTURE_PYTHON_MODE": mode, "FIXTURE_MASTER": master,
                       "FIXTURE_RESET": "1", "FIXTURE_REPORTS": "MISSING", "FIXTURE_METADATA": "MISSING",
                       "FIXTURE_OWNER": "MISSING", "FIXTURE_REPORT_OWNER": "MISSING"}
        python = Path(sys.executable).as_posix()
        stub = f"python3() {{ '{python}' \"$RUNNER_TEMP/fixture_python.py\" \"$@\"; }}\n"
        (self.runner_temp / "output").unlink(missing_ok=True)
        result = run_bash(GH_FIXTURE + stub + step_script(DRAIN, "review", step), environment, cwd=self.checkout)
        return result, outputs(self.runner_temp / "output")

    def restore(self, *, mode: str, master: str):
        provided = {"GH_TOKEN": "fixture", "PREPARED_ARTIFACT_ID": "30", "PREPARED_ARTIFACT_DIGEST": "c" * 64,
                    "PREPARED_MISSING": "false", "ARTIFACT_ID": "77", "ARTIFACT_DIGEST": "b" * 64,
                    "ARTIFACT_SIZE": "1024", "IMPLEMENTATION_SHA": GENERATION, "GENERATION_SHA": GENERATION,
                    "SOURCE_RUN_ID": "55", "BUNDLE_KEY": "mc1.21.6"}
        return self.run_step(RESTORE, mode=mode, master=master, provided=provided)

    def recovery(self, *, mode: str, master: str):
        capsule = self.runner_temp / "visual-review-capsule"
        (capsule / "review-input").mkdir(parents=True, exist_ok=True)
        (capsule / "curation-proof.json").write_text("{}")
        (capsule / "review-input/visual-review-manifest.json").write_text("[]")
        provided = {"GH_TOKEN": "fixture", "IMPLEMENTATION_SHA": GENERATION, "GENERATION_SHA": GENERATION,
                    "REVIEW_KEY": "55--mc1.21.6"}
        return self.run_step(RECOVERY, mode=mode, master=master, provided=provided)

    def test_restore_typed_python_unavailability_retains_the_capsule(self):
        result, step_outputs = self.restore(mode="unavailable", master=GENERATION)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertNotIn("private-", result.stderr)
        self.assertEqual({"github_transport_unavailable": "true"}, step_outputs)
        self.assert_transient("capsule_restore", *classify(self.runner_temp, {
            "steps.capsule.outcome": "failure",
            "steps.capsule.outputs.github_transport_unavailable": step_outputs["github_transport_unavailable"]}))

    def test_restore_exhausted_master_reauthentication_retains_the_capsule(self):
        result, step_outputs = self.restore(mode="skip", master="UNAVAILABLE")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({"missing": "false"}, step_outputs)
        self.assertTrue((self.runner_temp / "github-api-unavailable-capsule").is_file())
        self.assert_transient("capsule_restore", *classify(self.runner_temp, {"steps.capsule.outcome": "failure"}))

    def test_restore_integrity_and_freshness_failures_stay_terminal(self):
        for mode, master in (("foreign", GENERATION), ("skip", "f" * 40)):
            with self.subTest(mode=mode, master=master):
                result, step_outputs = self.restore(mode=mode, master=master)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("github_transport_unavailable", step_outputs)
                self.assertFalse((self.runner_temp / "github-api-unavailable-capsule").exists())
                self.assert_terminal(*classify(self.runner_temp, {
                    "steps.capsule.outcome": "failure",
                    "steps.capsule.outputs.github_transport_unavailable": step_outputs.get(
                        "github_transport_unavailable", "")}))
                (self.runner_temp / "visual-review-capsule/visual-review-failure.json").unlink()

    def test_report_recovery_unavailability_retains_the_capsule(self):
        # The live incident: recovery raised GitHubGetUnavailable before any model call.
        result, step_outputs = self.recovery(mode="unavailable", master=GENERATION)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertNotIn("private-", result.stderr)
        self.assertEqual({"github_transport_unavailable": "true"}, step_outputs)
        self.assert_transient("report_recovery", *classify(self.runner_temp, {
            "steps.recovered.outcome": "failure",
            "steps.recovered.outputs.github_transport_unavailable": step_outputs["github_transport_unavailable"]}))

    def test_recovery_exhausted_master_reauthentication_retains_the_capsule(self):
        result, step_outputs = self.recovery(mode="empty", master="QUOTA")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual({"recovered": "false"}, step_outputs)
        self.assertTrue((self.runner_temp / "github-api-unavailable-recovered").is_file())
        self.assert_transient("report_recovery", *classify(self.runner_temp, {"steps.recovered.outcome": "failure"}))

    def test_recovery_validation_and_freshness_failures_stay_terminal(self):
        for mode, master in (("malformed", GENERATION), ("empty", "f" * 40)):
            with self.subTest(mode=mode, master=master):
                result, step_outputs = self.recovery(mode=mode, master=master)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn("github_transport_unavailable", step_outputs)
                self.assertFalse((self.runner_temp / "github-api-unavailable-recovered").exists())
                self.assert_terminal(*classify(self.runner_temp, {"steps.recovered.outcome": "failure"}))
                (self.runner_temp / "visual-review-capsule/visual-review-failure.json").unlink()


class ProtectedScriptSignalTest(Workspace):
    def invoke(self, module, arguments: list[str], error: BaseException | None, response: bytes | None = None):
        output = self.runner_temp / "output"
        stdout, stderr = io.StringIO(), io.StringIO()
        side_effect = error if error is not None else (lambda *_args, **_kwargs: response)
        with patch.object(sys, "argv", [module.__file__, *arguments]), \
             patch.object(publisher, "_get", side_effect=side_effect), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            try:
                status = module.main()
            except SystemExit as stopped:
                status = stopped.code
        return status, stderr.getvalue(), output.read_text() if output.exists() else ""

    def recovery_arguments(self):
        capsule = self.runner_temp / "capsule"
        (capsule / "review-input").mkdir(parents=True, exist_ok=True)
        (capsule / "curation-proof.json").write_text("{}")
        (capsule / "review-input/visual-review-manifest.json").write_text("[]")
        return ["--repository", "example/quick-skin", "--capsule", str(capsule), "--implementation-sha", GENERATION,
                "--workflow-sha", GENERATION, "--review-key", "55--mc1.21.6",
                "--github-output", str(self.runner_temp / "output")]

    def preparation_arguments(self, *, output=True):
        arguments = ["--repository", "example/quick-skin", "--run-id", "10", "--run-attempt", "1",
                     "--artifact-id", "30", "--capsule-id", "77", "--capsule-size", "1024",
                     "--workflow-sha", GENERATION, "--artifact-digest", "c" * 64, "--capsule-digest", "b" * 64,
                     "--output", str(self.runner_temp / "prepared")]
        return arguments + (["--github-output", str(self.runner_temp / "output")] if output else [])

    def test_typed_unavailability_emits_only_the_fixed_signal(self):
        for module, arguments in ((completed, self.recovery_arguments()), (preparation, self.preparation_arguments())):
            with self.subTest(module=module.__name__):
                (self.runner_temp / "output").unlink(missing_ok=True)
                status, stderr, written = self.invoke(module, arguments, publisher.GitHubGetUnavailable("private-text"))
                self.assertEqual(2, status)
                self.assertNotIn("private-", stderr)
                self.assertEqual("github_transport_unavailable=true\n", written)

    def test_integrity_failures_never_emit_the_signal(self):
        cases = ((completed, self.recovery_arguments(), b'{"total_count":1,"artifacts":[]}'),
                 (preparation, self.preparation_arguments(), json.dumps({"id": 10, "head_sha": "f" * 40}).encode()))
        for module, arguments, response in cases:
            with self.subTest(module=module.__name__):
                (self.runner_temp / "output").unlink(missing_ok=True)
                with self.assertRaises(ValueError) as caught:
                    self.invoke(module, arguments, None, response)
                self.assertNotIsInstance(caught.exception, publisher.GitHubGetUnavailable)
                self.assertFalse((self.runner_temp / "output").exists())

    def test_preparation_without_output_still_fails_closed(self):
        status, _stderr, written = self.invoke(preparation, self.preparation_arguments(output=False),
                                               publisher.GitHubGetUnavailable("private-text"))
        self.assertEqual((2, ""), (status, written))


class ClassifierScopeTest(Workspace):
    def test_signals_from_steps_that_did_not_fail_are_ignored(self):
        for name in ("guard", "capsule", "recovered"):
            (self.runner_temp / f"github-api-unavailable-{name}").write_text("")
        classification, step_outputs = classify(self.runner_temp, {
            "steps.capsule.outputs.github_transport_unavailable": "true",
            "steps.recovered.outputs.github_transport_unavailable": "true"})
        self.assert_terminal(classification, step_outputs)

    def test_signals_require_exact_true_and_their_own_failed_step(self):
        for expressions in ({"steps.recovered.outcome": "failure", "steps.recovered.outputs.github_transport_unavailable": "TRUE"},
                            {"steps.capsule.outcome": "failure", "steps.recovered.outputs.github_transport_unavailable": "true"},
                            {"steps.recovered.outcome": "failure", "steps.capsule.outputs.github_transport_unavailable": "true"},
                            {"steps.guard.outcome": "failure"}):
            with self.subTest(expressions=expressions):
                with tempfile.TemporaryDirectory() as temporary:
                    folder = Path(temporary)
                    (folder / "github-api-unavailable-capsule").write_text("")
                    if "steps.capsule.outcome" in expressions:
                        (folder / "github-api-unavailable-capsule").unlink()
                    self.assertEqual(TERMINAL, classify(folder, expressions)[0])

    def test_runner_marker_and_completed_review_are_preserved(self):
        (self.runner_temp / "github-api-unavailable-guard").write_text("")
        capsule = self.runner_temp / "visual-review-capsule"
        capsule.mkdir()
        marker = {"schema_version": 1, "category": "quota_or_rate_limit", "stage": "triage", "transient": True}
        (capsule / "visual-review-failure.json").write_text(json.dumps(marker))
        self.assertEqual(marker, classify(self.runner_temp, {"steps.guard.outcome": "failure"})[0])
        script = step_script(DRAIN, "review", CLASSIFY)
        self.assertLess(script.index("review_complete }}\" == true"), script.index("github-api-unavailable-guard"))

    def test_github_unavailability_never_opens_the_claude_circuit(self):
        review = job_block(DRAIN, "review")
        circuit = review.split("      - name: Open the shared Claude circuit after a quota rejection\n", 1)[1]
        self.assertIn("steps.classify.outputs.failure_category ==\n          'quota_or_rate_limit'", circuit)
        self.assertNotEqual("quota_or_rate_limit", TRANSIENT["category"])


class CompatibilityAdmissionTest(Workspace):
    def admission(self, master: str):
        environment = {**base_environment(self.runner_temp),
                       **resolved_env(COMPATIBILITY, "review", ADMISSION, self.runner_temp,
                                      {"GH_TOKEN": "fixture", "SOURCE_IMPLEMENTATION_SHA": GENERATION}),
                       "FIXTURE_MASTER": master, "FIXTURE_RESET": "9999999999"}
        return run_bash(GH_FIXTURE + step_script(COMPATIBILITY, "review", ADMISSION), environment, cwd=ROOT)

    def classify_batch(self, outcome: str):
        script = step_script(COMPATIBILITY, "review", COMPATIBILITY_CLASSIFY).replace(
            "${{ steps.admission.outcome }}", outcome)
        self.assertNotIn("${{", script)
        result = run_bash(script, base_environment(self.runner_temp), cwd=self.runner_temp)
        self.assertEqual(0, result.returncode, result.stderr)
        marker = self.runner_temp / "mod-compatibility-failure-batch.json"
        classification = json.loads(marker.read_text())
        attempts = self.runner_temp / "compatibility-attempts"
        shutil.rmtree(attempts, ignore_errors=True)
        attempts.mkdir()
        shutil.copy2(marker, attempts / marker.name)
        gate_output = self.runner_temp / "gate-output"
        gate = run_bash(step_script(COMPATIBILITY, "gate", QUOTA_GATE),
                        {**base_environment(self.runner_temp), "GITHUB_OUTPUT": gate_output.as_posix()},
                        cwd=self.runner_temp)
        self.assertEqual(0, gate.returncode, gate.stderr)
        return classification, outputs(gate_output)

    def test_exhausted_admission_read_is_transient_github_unavailability(self):
        for master in ("UNAVAILABLE", "QUOTA"):
            with self.subTest(master=master):
                result = self.admission(master)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue((self.runner_temp / "github-api-unavailable-admission").is_file())
                classification, gate = self.classify_batch("failure")
                self.assertEqual({**TRANSIENT, "stage": "compatibility_admission"}, classification)
                self.assertEqual({"quota_rejected": "false"}, gate)
                shutil.rmtree(self.runner_temp / "mod-compatibility-review-batch")
                (self.runner_temp / "github-api-unavailable-admission").unlink()

    def test_advanced_master_and_unsignalled_failures_stay_terminal(self):
        result = self.admission("f" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.runner_temp / "github-api-unavailable-admission").exists())
        terminal = {"schema_version": 1, "category": "protected_validation", "stage": "compatibility",
                    "transient": False}
        self.assertEqual(terminal, self.classify_batch("failure")[0])
        shutil.rmtree(self.runner_temp / "mod-compatibility-review-batch")
        (self.runner_temp / "github-api-unavailable-admission").write_text("")
        self.assertEqual(terminal, self.classify_batch("success")[0])


class SignalWiringTest(unittest.TestCase):
    def test_each_signalling_step_writes_exactly_the_path_its_classifier_reads(self):
        drain_classifier = step_script(DRAIN, "review", CLASSIFY)
        for step, name in ((GUARD, "guard"), (RESTORE, "capsule"), (RECOVERY, "recovered")):
            with self.subTest(step=step):
                self.assertEqual("${{ runner.temp }}/github-api-unavailable-" + name,
                                 step_env(DRAIN, "review", step)[SIGNAL_ENV])
                self.assertIn(f'-f "$RUNNER_TEMP/github-api-unavailable-{name}"', drain_classifier)
        self.assertEqual("${{ runner.temp }}/github-api-unavailable-admission",
                         step_env(COMPATIBILITY, "review", ADMISSION)[SIGNAL_ENV])
        self.assertIn('-f "$RUNNER_TEMP/github-api-unavailable-admission"',
                      step_script(COMPATIBILITY, "review", COMPATIBILITY_CLASSIFY))

    def test_reviewer_steps_source_the_retained_protected_helper(self):
        for step in (RESTORE, RECOVERY):
            with self.subTest(step=step):
                script = step_script(DRAIN, "review", step)
                self.assertIn('source "$RUNNER_TEMP/protected-drain-control/scripts/ci/github_api_retry.sh"', script)
                self.assertNotIn("source scripts/ci/github_api_retry.sh", script)
        restore = " ".join(step_script(DRAIN, "review", RESTORE).replace("\\\n", "").split())
        self.assertIn('--output "$RUNNER_TEMP/visual-review-capsule" --github-output "$GITHUB_OUTPUT"', restore)

    def test_cleanup_still_deletes_only_nontransient_failures(self):
        self.assertFalse(cleanup_admitted({**TRANSIENT, "stage": "report_recovery"}))
        self.assertTrue(cleanup_admitted(TERMINAL))
        self.assertIn("needs.review.outputs.failure_transient == 'false'", job_block(DRAIN, "cleanup"))


if __name__ == "__main__":
    unittest.main()
