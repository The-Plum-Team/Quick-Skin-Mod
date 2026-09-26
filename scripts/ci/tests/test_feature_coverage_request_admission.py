from __future__ import annotations

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import feature_coverage_request as scheduler
from scripts.ci.tests.test_feature_coverage_request import Api as RequestFixture
from scripts.ci.tests.test_workflow_security import job_block, step_script

SHA = "a" * 40
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
# The producer tail of each workflow: pages.yml keeps it in mod-base's mod-local extension region.
PRODUCER_JOBS = {"visual-review-drain.yml": "request-feature-coverage", "pages.yml": "ext-feature-coverage"}


class RequestInventoryAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.api = scheduler.Api(REPOSITORY)
        self.run = {**RequestFixture().source, "path": scheduler.publisher.WORKFLOW,
                    "event": "repository_dispatch", "display_title": scheduler.title(10, 1)}

    def runs(self, value):
        with patch.object(scheduler.publisher, "_get", return_value=json.dumps(value).encode()) as transport:
            result = self.api.runs(scheduler.publisher.WORKFLOW, SHA)
        transport.assert_called_once_with(
            f"repos/{REPOSITORY}/actions/workflows/feature-coverage.yml/runs?head_sha={SHA}&per_page=100",
            maximum=scheduler.publisher.MAX_API_BYTES)
        return result

    def test_exact_bounded_first_page_is_complete_and_counts_one_get(self):
        records = [{**self.run, "id": index + 1} for index in range(100)]
        self.assertEqual(records, self.runs({"total_count": 100, "workflow_runs": records}))
        self.assertEqual(1, self.api.reads)
        self.assertEqual(0, self.api.mutations)

    def test_oversized_truncated_noninteger_and_nonobject_inventories_fail_closed(self):
        cases = (None, [], {}, {"total_count": True, "workflow_runs": [self.run]},
                 {"total_count": -1, "workflow_runs": []},
                 {"total_count": 101, "workflow_runs": [self.run] * 101},
                 {"total_count": 2, "workflow_runs": [self.run]},
                 {"total_count": 0, "workflow_runs": [self.run]},
                 {"total_count": 1, "workflow_runs": {}},
                 {"total_count": 1, "workflow_runs": [None]})
        for record in cases:
            with self.subTest(record=record), self.assertRaises(ValueError):
                self.runs(record)
        self.assertEqual(0, self.api.mutations)

    def test_foreign_run_head_repository_path_attempt_and_duplicate_ids_are_rejected(self):
        for field, value in (("id", True), ("id", 0), ("run_attempt", True),
                ("run_attempt", 0), ("run_attempt", 101), ("head_sha", "b" * 40),
                ("head_branch", "feature/untrusted"), ("head_repository", None),
                ("head_repository", {"full_name": "foreign/repository"}),
                ("path", scheduler.SOURCE_WORKFLOW), ("status", "unknown")):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.runs({"total_count": 1, "workflow_runs": [{**self.run, field: value}]})
        with self.assertRaisesRegex(ValueError, "repeats"):
            self.runs({"total_count": 2, "workflow_runs": [self.run, copy.deepcopy(self.run)]})
        self.assertEqual(0, self.api.mutations)

    def test_unknown_workflow_is_rejected_before_transport(self):
        with patch.object(scheduler.publisher, "_get") as transport, self.assertRaises(ValueError):
            self.api.runs(".github/workflows/untrusted.yml", SHA)
        transport.assert_not_called()
        self.assertEqual(0, self.api.reads)

    def test_duplicate_json_keys_and_nonfinite_values_never_reach_admission(self):
        for raw in (b'{"total_count":0,"total_count":1,"workflow_runs":[]}',
                    b'{"total_count":NaN,"workflow_runs":[]}',
                    b'{"total_count":1e999,"workflow_runs":[]}'):
            with self.subTest(raw=raw), patch.object(scheduler.publisher, "_get", return_value=raw), \
                    self.assertRaises(ValueError):
                self.api.runs(scheduler.publisher.WORKFLOW, SHA)
        self.assertEqual(0, self.api.mutations)

    def test_dispatch_is_one_bounded_argument_vector_and_canonical_stdin_not_shell(self):
        payload = {"event_type": "feature-coverage-requested", "client_payload": {
            "source_repository": REPOSITORY, "source_run_id": "10", "source_run_attempt": "1",
            "source_sha": SHA, "producer_run_id": '1; $(touch never-execute) "quoted"\nnext'}}
        with patch.object(scheduler.subprocess, "run") as process:
            self.api.dispatch(payload)
        process.assert_called_once_with(
            ["gh", "api", "--method", "POST", f"repos/{REPOSITORY}/dispatches", "--input", "-"],
            input=scheduler.coverage.admission.canonical(payload), stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, check=True, timeout=30)
        self.assertEqual(1, self.api.mutations)
        self.assertEqual(0, self.api.reads)

    def test_dispatch_failure_and_timeout_propagate_without_retry_or_payload_output(self):
        payload = {"event_type": "feature-coverage-requested", "client_payload": {"source_run_id": "10"}}
        for failure in (subprocess.CalledProcessError(1, "gh"), subprocess.TimeoutExpired("gh", 30)):
            with self.subTest(failure=failure), patch.object(scheduler.subprocess, "run", side_effect=failure) as process, \
                    contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaises(type(failure)):
                self.api.dispatch(payload)
            self.assertEqual(1, process.call_count)
            self.assertEqual("", output.getvalue())


class ProducerShellAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.calls = self.folder / "arguments"
        self.environment = {"PATH": os.defpath, "GITHUB_SHA": SHA, "GITHUB_REF": "refs/heads/master",
            "GITHUB_RUN_ID": "100", "GITHUB_REPOSITORY": REPOSITORY, "FIXTURE_HEAD": SHA,
            "FIXTURE_LAYOUT": "shared", "FIXTURE_REQUEST_EXIT": "0", "FIXTURE_CALLS": str(self.calls),
            "PRODUCER_RUN_ID": "", "SOURCE_RUN_ID": ""}
        # Execute the actual checked-in shell. Stub only its external git/Python boundary;
        # no network-capable executable, runtime or provider is invoked by these fixtures.
        self.boundary = '''
git() {
  [[ "$*" == "rev-parse HEAD" ]] || return 91
  printf '%s\\n' "$FIXTURE_HEAD"
}
python3() {
  if [[ "$1" == scripts/release/release_sources.py ]]; then
    [[ "$*" == "scripts/release/release_sources.py --kind mode" ]] || return 92
    printf '%s\\n' "$FIXTURE_LAYOUT"
  elif [[ "$1" == scripts/ci/feature_coverage_request.py ]]; then
    printf '%s\\0' "$@" >> "$FIXTURE_CALLS"
    return "$FIXTURE_REQUEST_EXIT"
  else
    return 93
  fi
}
gh() { return 94; }
'''

    def shell(self, workflow, *, recovery=False, overrides=None):
        self.calls.unlink(missing_ok=True)
        name = ("Reconcile durable readiness without running a model or assembling reports" if recovery
                else "Request a collector only for complete unscheduled readiness")
        script = step_script(workflow, "request" if recovery else PRODUCER_JOBS[workflow], name)
        result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", self.boundary + script],
            cwd=self.folder, env={**self.environment, **(overrides or {})}, text=True,
            capture_output=True, timeout=10)
        arguments = self.calls.read_bytes().decode().rstrip("\0").split("\0") if self.calls.exists() else []
        return result, arguments

    def test_both_actual_producer_tails_call_only_the_exact_protected_gate(self):
        expected = ["scripts/ci/feature_coverage_request.py", "--producer-run-id", "100",
                    "--github-repository", REPOSITORY, "--source-sha", SHA]
        for workflow in ("visual-review-drain.yml", "pages.yml"):
            with self.subTest(workflow=workflow):
                result, arguments = self.shell(workflow)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(expected, arguments)
                block = job_block(workflow, PRODUCER_JOBS[workflow])
                self.assertIn("quick-skin-feature-baseline-request-${{ github.sha }}", block)
                self.assertIn("queue: max", block)
                self.assertIn("actions: read", block)
                self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", block)
                self.assertNotIn("gh api --method POST", block)

    def test_review_request_waits_for_capacity_resume_sibling_before_its_owner_can_settle(self):
        block = job_block("visual-review-drain.yml", "request-feature-coverage")
        dependencies = block.split("    needs:\n", 1)[1].split("    if:", 1)[0]
        for name in ("select", "review", "resume-capacity-queue", "cleanup",
                     "release-mod-compatibility", "release-anchor"):
            self.assertIn("      - " + name + "\n", dependencies)
        resume = job_block("visual-review-drain.yml", "resume-capacity-queue")
        self.assertNotIn("request-feature-coverage", resume)

    def test_wrong_checkout_ref_and_hostile_producer_ids_stop_before_python_gate(self):
        hostile = f"100; touch {self.folder / 'executed'}"
        cases = ({"GITHUB_REF": "refs/heads/feature"}, {"FIXTURE_HEAD": "b" * 40},
                 *({"GITHUB_RUN_ID": value} for value in ("", "0", "-1", "01", "1 2", hostile, "$(exit 0)")))
        for workflow in ("visual-review-drain.yml", "pages.yml"):
            for overrides in cases:
                with self.subTest(workflow=workflow, overrides=overrides):
                    result, arguments = self.shell(workflow, overrides=overrides)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual([], arguments)
        self.assertFalse((self.folder / "executed").exists())

    def test_historical_layout_skips_and_readiness_failure_stays_visible(self):
        for workflow in ("visual-review-drain.yml", "pages.yml", "feature-coverage-request.yml"):
            with self.subTest(workflow=workflow):
                recovery = workflow == "feature-coverage-request.yml"
                result, arguments = self.shell(workflow, recovery=recovery, overrides={"FIXTURE_LAYOUT": "version-branches"})
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([], arguments)
                result, arguments = self.shell(workflow, recovery=recovery, overrides={"FIXTURE_REQUEST_EXIT": "2"})
                self.assertEqual(2, result.returncode)
                self.assertTrue(arguments)

    def test_recovery_routes_exact_workflow_owner_manual_source_or_bounded_discovery(self):
        cases = (({}, ["--recover"]), ({"SOURCE_RUN_ID": "55"}, ["--source-run-id", "55"]),
                 ({"SOURCE_RUN_ID": "55", "PRODUCER_RUN_ID": "77"}, ["--producer-run-id", "77"]))
        for overrides, identity in cases:
            with self.subTest(overrides=overrides):
                result, arguments = self.shell("feature-coverage-request.yml", recovery=True, overrides=overrides)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(["scripts/ci/feature_coverage_request.py", *identity,
                    "--github-repository", REPOSITORY, "--source-sha", SHA], arguments)

    def test_recovery_input_is_one_literal_argument_and_never_shell_source(self):
        hostile = f"55; touch {self.folder / 'executed'}; $(exit 3)"
        result, arguments = self.shell("feature-coverage-request.yml", recovery=True,
                                       overrides={"SOURCE_RUN_ID": hostile})
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(hostile, arguments[2])
        self.assertFalse((self.folder / "executed").exists())
        with patch.object(sys, "argv", ["feature_coverage_request.py", "--source-run-id", hostile,
                "--github-repository", REPOSITORY, "--source-sha", SHA]), \
                patch.object(scheduler, "Api") as api, contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as failure:
            scheduler.main()
        self.assertEqual(2, failure.exception.code)
        api.assert_not_called()

    def test_recovery_rejects_wrong_ref_and_checkout_before_any_readiness_scan(self):
        for overrides in ({"GITHUB_REF": "refs/pull/1/merge"}, {"FIXTURE_HEAD": "b" * 40}):
            with self.subTest(overrides=overrides):
                result, arguments = self.shell("feature-coverage-request.yml", recovery=True, overrides=overrides)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual([], arguments)


class CancelledReportAdmissionTest(unittest.TestCase):
    def test_only_terminal_protected_cancellation_can_be_ignored_among_reports(self):
        api = RequestFixture()
        name = next(name for name in api.records if name.startswith("visual-review-"))
        key = name.split("--")[1]
        original = api.records[name][0]
        cancelled = copy.deepcopy(original)
        cancelled["id"] = 9900
        cancelled["workflow_run"]["id"] = 999
        owner = {**api.owners[100], "id": 999, "status": "completed", "conclusion": "cancelled"}
        api.owners[999] = owner
        select = lambda: scheduler.publisher.select_review_candidate(api, [cancelled, original],
            source_sha=SHA, source_run_id=10, bundle_key=key)
        self.assertEqual(original, select())
        for field, value in (("head_sha", "b" * 40), ("head_branch", "feature"),
                ("path", scheduler.SOURCE_WORKFLOW), ("head_repository", {"full_name": "foreign/repository"}),
                ("event", "pull_request"), ("id", True), ("id", 998)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                api.owners[999] = {**owner, field: value}
                select()
        for changes in ({"status": "in_progress"}, {"conclusion": "failure"}, {"conclusion": "success"}):
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, "ambiguous"):
                api.owners[999] = {**owner, **changes}
                select()

    def test_one_report_does_not_add_owner_reads_and_two_cancelled_reports_do_not_certify(self):
        api = RequestFixture()
        name = next(name for name in api.records if name.startswith("visual-review-"))
        key = name.split("--")[1]
        original = api.records[name][0]
        self.assertEqual(original, scheduler.publisher.select_review_candidate(api, [original],
            source_sha=SHA, source_run_id=10, bundle_key=key))
        self.assertEqual([], api.queries)
        api.owners[100].update(status="completed", conclusion="cancelled")
        duplicate = {**original, "id": 9999}
        self.assertIsNone(scheduler.publisher.select_review_candidate(api, [original, duplicate],
            source_sha=SHA, source_run_id=10, bundle_key=key))


class TerminalProducerRecoveryTest(unittest.TestCase):
    class Api(RequestFixture):
        @property
        def reads(self):
            return len(self.queries)

        @property
        def mutations(self):
            return len(self.payloads)

        def artifacts(self, *, run_id=None, name=None):
            if run_id is None or run_id == self.source["id"]:
                return super().artifacts(run_id=run_id, name=name)
            self.queries.append(("artifacts", run_id, name))
            return copy.deepcopy([record for records in self.records.values() for record in records
                                  if record["workflow_run"]["id"] == run_id])

    def setUp(self):
        self.api = self.Api()
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.existing = patch.object(scheduler, "existing_available", return_value=False).start()
        self.addCleanup(patch.stopall)

    def request(self, producer=100, *, owner=None):
        source_id = scheduler.source_from_producer(self.api, producer, SHA, owner=owner)
        self.assertEqual(10, source_id)
        return scheduler.request(self.api, repository=ROOT, source_sha=SHA, source_id=source_id,
            producer_id=producer, producer_owner=owner, directory=self.directory, sleep=lambda _: None)

    def test_own_running_tail_keeps_its_exact_trigger_and_terminal_success_does_not(self):
        self.api.owners[100].update(status="in_progress", conclusion=None)
        self.assertEqual("collector-dispatched", self.request())
        self.assertEqual("100", self.api.payloads[-1]["client_payload"]["producer_run_id"])
        self.api.collectors = []
        self.api.owners[100].update(status="completed", conclusion="success")
        self.assertEqual("collector-dispatched", self.request())
        self.assertEqual("", self.api.payloads[-1]["client_payload"]["producer_run_id"])

    def test_terminal_recovery_never_exempts_a_still_running_replacement(self):
        name = next(name for name in self.api.records if name.startswith("visual-review-"))
        old = self.api.records[name][0]
        replacement = copy.deepcopy(old)
        replacement.update(id=9900)
        replacement["workflow_run"]["id"] = 999
        self.api.records[name].append(replacement)
        self.api.owners[100].update(status="completed", conclusion="cancelled")
        self.api.owners[999] = {**self.api.owners[100], "id": 999,
                              "status": "in_progress", "conclusion": None}
        self.assertEqual("evidence-incomplete", self.request())
        self.assertEqual([], self.api.payloads)
        self.api.owners[999].update(status="completed", conclusion="success")
        self.assertEqual("collector-dispatched", self.request())
        self.assertEqual({"source_repository": REPOSITORY, "source_run_id": "10",
            "source_run_attempt": "1", "source_sha": SHA, "producer_run_id": ""},
            self.api.payloads[-1]["client_payload"])

    def test_older_failed_cancelled_or_timed_out_pages_wake_uses_newer_successful_owner(self):
        for conclusion in ("failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                self.api = self.Api()
                self.api.owners[999] = {**self.api.owners[50], "id": 999, "conclusion": conclusion}
                for name, records in self.api.records.items():
                    if name.startswith(scheduler.publisher.PUBLIC_BASELINE_PREFIX):
                        old = copy.deepcopy(records[0])
                        old["id"] -= 500
                        old["workflow_run"]["id"] = 999
                        records.append(old)
                self.assertEqual("collector-dispatched", self.request(999))
                self.assertEqual("", self.api.payloads[-1]["client_payload"]["producer_run_id"])

    def test_newer_failed_cancelled_or_timed_out_pages_owner_blocks_older_success(self):
        for conclusion in ("failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                self.api = self.Api()
                self.api.owners[999] = {**self.api.owners[50], "id": 999, "conclusion": conclusion}
                for name, records in self.api.records.items():
                    if name.startswith(scheduler.publisher.PUBLIC_BASELINE_PREFIX):
                        newer = copy.deepcopy(records[0])
                        newer["id"] += 9000
                        newer["workflow_run"]["id"] = 999
                        records.append(newer)
                self.assertEqual("evidence-incomplete", self.request(999))
                self.assertEqual([], self.api.payloads)

    def test_public_candidate_window_counts_expired_newer_records_before_filtering(self):
        name = next(name for name in self.api.records if name.startswith(scheduler.publisher.PUBLIC_BASELINE_PREFIX))
        original = self.api.records[name][0]
        self.api.records[name].extend({**original, "id": original["id"] + 10000 + index,
                                      "expired": True} for index in range(8))
        self.assertEqual("evidence-incomplete", self.request())
        self.assertEqual([], self.api.payloads)

    def test_own_active_pages_tail_is_not_hidden_by_its_older_failed_publication(self):
        self.api.owners[50].update(status="in_progress", conclusion=None)
        self.api.owners[999] = {**self.api.owners[50], "id": 999,
                              "status": "completed", "conclusion": "failure"}
        for name, records in self.api.records.items():
            if name.startswith(scheduler.publisher.PUBLIC_BASELINE_PREFIX):
                older = copy.deepcopy(records[0])
                older["id"] -= 500
                older["workflow_run"]["id"] = 999
                records.append(older)
        self.assertEqual("collector-dispatched", self.request(50))
        self.assertEqual("50", self.api.payloads[-1]["client_payload"]["producer_run_id"])

    def test_missing_or_cancelled_only_report_cannot_schedule_a_collector(self):
        self.api.owners[100].update(status="completed", conclusion="cancelled")
        self.assertEqual("evidence-incomplete", self.request())
        self.assertEqual([], self.api.payloads)

    def test_foreign_malformed_and_nonterminal_recovery_owners_fail_closed(self):
        original = self.api.owners[100]
        for changes in ({"id": 999}, {"id": True}, {"head_branch": "feature"},
                {"head_repository": {"full_name": "foreign/repository"}},
                {"path": scheduler.SOURCE_WORKFLOW}, {"event": "pull_request"},
                {"run_attempt": True}, {"status": "queued", "conclusion": None},
                {"status": "in_progress", "conclusion": "cancelled"},
                {"status": "completed", "conclusion": None}, {"conclusion": "unknown"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.api.owners[100] = {**original, **changes}
                self.request()
        self.assertEqual([], self.api.payloads)

    def test_cli_reuses_only_its_existing_invocation_local_owner_read(self):
        prefix = scheduler.publisher.PUBLIC_BASELINE_PREFIX
        self.api.records.pop(next(name for name in self.api.records if name.startswith(prefix)))
        arguments = ["feature_coverage_request.py", "--producer-run-id", "100",
                     "--github-repository", REPOSITORY, "--source-sha", SHA]
        with patch.object(sys, "argv", arguments), patch.object(scheduler, "Api", return_value=self.api), \
                patch.object(scheduler.coverage, "policy_fingerprint"), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, scheduler.main())
        self.assertIn("outcome=evidence-incomplete", output.getvalue())
        self.assertEqual(1, self.api.queries.count(("run", 100)))
        self.assertEqual([], self.api.payloads)


class CollectorAttemptAdmissionTest(unittest.TestCase):
    def test_recovery_skips_newer_selected_source_without_hiding_complete_current_head(self):
        api = Mock()
        source = RequestFixture().source
        api.runs.return_value = [{**source, "id": identifier} for identifier in (10, 11, 12)]
        api.artifacts.side_effect = lambda *, run_id: (
            [{"name": scheduler.coverage.SELECTION_ARTIFACT_NAME}] if run_id != 10 else [])
        self.assertEqual(10, scheduler.recover_source(api, SHA))
        self.assertEqual([12, 11, 10], [call.kwargs["run_id"] for call in api.artifacts.call_args_list])

    def test_recovery_uses_newest_complete_source_and_does_not_hide_inventory_errors(self):
        api = Mock()
        source = RequestFixture().source
        api.runs.return_value = [{**source, "id": 10}, {**source, "id": 12}]
        api.artifacts.return_value = []
        self.assertEqual(12, scheduler.recover_source(api, SHA))
        api.artifacts.assert_called_once_with(run_id=12)
        api.artifacts.side_effect = ValueError("incomplete inventory")
        with self.assertRaisesRegex(ValueError, "incomplete inventory"):
            scheduler.recover_source(api, SHA)

    def test_recovery_with_only_selected_or_failed_sources_does_not_request_full_certificate(self):
        api = Mock()
        source = RequestFixture().source
        api.runs.return_value = [{**source, "id": 10}, {**source, "id": 12, "conclusion": "failure"}]
        api.artifacts.return_value = [{"name": scheduler.coverage.SELECTION_ARTIFACT_NAME}]
        self.assertIsNone(scheduler.recover_source(api, SHA))
        api.artifacts.assert_called_once_with(run_id=10)

    def test_source_attempt_advancing_during_preparation_never_emits_another_attempt(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "baseline.json"
            api = Mock()
            api.current_sha.return_value = SHA
            api.run.return_value = {"run_attempt": 2}
            arguments = ["feature_coverage_github.py", "--source-run-id", "10",
                "--expected-source-run-id", "10", "--expected-source-attempt", "1",
                "--github-repository", REPOSITORY, "--source-sha", SHA,
                "--issuer-run-id", "99", "--output", str(output)]
            with patch.object(sys, "argv", arguments), patch.object(scheduler.publisher, "Api", return_value=api), \
                    patch.object(scheduler.coverage, "policy_fingerprint"), \
                    patch.object(scheduler.publisher, "prepare", wraps=scheduler.publisher.prepare) as prepare, \
                    contextlib.redirect_stdout(io.StringIO()):
                scheduler.publisher.main()
            self.assertEqual(1, prepare.call_args.kwargs["expected_source_attempt"])
            api.artifacts.assert_not_called()
            self.assertFalse(output.exists(), "an attempt-1 request must not publish an attempt-2 result")


if __name__ == "__main__":
    unittest.main()
