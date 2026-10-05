from __future__ import annotations

import contextlib
import copy
import io
import re
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import visual_review_wake as wake

REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
SHA = "a" * 40
NOW = datetime(2026, 10, 5, 21, 0, tzinfo=timezone.utc)
WORKFLOWS = ROOT / ".github" / "workflows"


def job_names(workflow: str) -> dict[str, str]:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    jobs = text.split("\njobs:\n", 1)[1]
    return dict(re.findall(r"(?m)^  ([a-z0-9-]+):\n(?:    #.*\n)*    name: (.+)$", jobs))


def make_run(identifier, *, path=wake.SOURCE_WORKFLOW, event="workflow_dispatch", branch="master",
             status="completed", conclusion="success", updated="2026-10-05T19:45:08Z"):
    return {"id": identifier, "run_attempt": 1, "path": path, "event": event, "head_sha": SHA,
            "head_branch": branch, "head_repository": {"full_name": REPOSITORY}, "status": status,
            "conclusion": conclusion, "updated_at": updated}


class FixtureApi:
    repository = REPOSITORY

    def __init__(self):
        self.current = SHA
        self.jobs_by_run: dict[int, list[dict]] = {}
        self.records: dict[str, list[dict]] = {}
        self.owners: dict[int, dict] = {}
        self.reviews: list[dict] = []
        self.queries: list = []
        self.sources = [self.source(37361031586, conclusion="failure", wake_job="cancelled")]

    def source(self, identifier, *, conclusion="success", gate="success", wake_job="cancelled", **fields):
        jobs = [{"name": "Classify packaged runtime impact", "status": "completed", "conclusion": "success"}]
        if gate is not None:
            jobs.append({"name": wake.GATE_JOB, "status": "completed", "conclusion": gate})
        if wake_job is not None:
            jobs.append({"name": wake.WAKE_JOB, "status": "completed", "conclusion": wake_job})
        self.jobs_by_run[identifier] = [{"jobs": jobs}]
        return make_run(identifier, conclusion=conclusion, **fields)

    def capsule(self, source_run_id, key="mc1.21.4", *, owner_id=900, expired=False, report=False,
                owner_fields=None):
        name = (f"visual-review-{source_run_id}--{key}" if report
                else f"visual-review-input-{source_run_id}-{SHA}--{key}")
        self.records.setdefault(name, []).append({
            "id": 5000 + len(self.records), "name": name, "size_in_bytes": 1024,
            "digest": "sha256:" + "b" * 64, "expired": expired, "created_at": "2026-10-05T20:00:00Z",
            "workflow_run": {"id": owner_id, "head_branch": "master", "head_sha": SHA}})
        path = wake.coverage.DRAIN_WORKFLOW if report else wake.REVIEW_WORKFLOW
        self.owners[owner_id] = {**make_run(owner_id, path=path, event="repository_dispatch"),
                                 **(owner_fields or {})}

    def current_sha(self):
        self.queries.append("head")
        return self.current

    def runs(self, workflow, source_sha):
        self.queries.append(("runs", workflow, source_sha))
        return copy.deepcopy(self.sources if workflow == wake.SOURCE_WORKFLOW else self.reviews)

    def jobs(self, run):
        self.queries.append(("jobs", run["id"], run["run_attempt"]))
        return copy.deepcopy(self.jobs_by_run[run["id"]])

    def artifacts(self, *, run_id=None, name=None):
        self.queries.append(("artifacts", name))
        return copy.deepcopy(self.records.get(name, []))

    def run(self, identifier):
        self.queries.append(("run", identifier))
        return copy.deepcopy(self.owners[identifier])


class WakeRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.api = FixtureApi()

    def decide(self, now=NOW):
        return wake.decide(self.api, source_sha=SHA, now=now)

    def test_a_lost_wake_after_a_successful_required_gate_is_re_sent_for_the_newest_generation(self):
        # The 2026-10-05 incident: the wake job never received a runner and its timeout made the
        # run conclude failure although the required gate succeeded.
        decision = self.decide()
        self.assertEqual(("dispatch", 37361031586), (decision.reason, decision.source_run_id))
        older = self.api.source(37361031000, conclusion="success", wake_job="success")
        self.api.sources.append(older)
        self.assertEqual(37361031586, self.decide().source_run_id)

    def test_the_exact_attempt_must_prove_the_gate_and_the_wake_condition(self):
        cases = {
            "failed-gate": dict(conclusion="failure", gate="failure", wake_job="skipped"),
            "cancelled-gate": dict(conclusion="cancelled", gate="cancelled", wake_job="skipped"),
            "missing-gate": dict(conclusion="failure", gate=None, wake_job="cancelled"),
            "attestation": dict(conclusion="success", gate=None, wake_job="skipped"),
            "attest-input": dict(conclusion="success", gate="success", wake_job="skipped"),
            "missing-wake": dict(conclusion="failure", gate="success", wake_job=None),
            "unsettled": dict(conclusion="startup_failure", gate="success", wake_job="cancelled"),
        }
        for case, fields in cases.items():
            with self.subTest(case=case):
                self.api.sources = [self.api.source(77, **fields)]
                self.assertEqual("no-canonical-generation", self.decide().reason)
        self.api.sources = [self.api.source(77, conclusion="failure"),
                            {**self.api.source(78), "head_branch": "topic"},
                            {**self.api.source(79), "event": "pull_request"}]
        self.assertEqual(("dispatch", 77), (self.decide().reason, self.decide().source_run_id))
        self.api.sources = [{**self.api.source(78), "head_branch": "topic"}]
        self.assertEqual("no-canonical-generation", self.decide().reason)

    def test_a_repeated_job_name_is_malformed_evidence(self):
        self.api.jobs_by_run[37361031586][0]["jobs"].append(
            {"name": "Packaged E2E gate", "status": "completed", "conclusion": "success"})
        with self.assertRaisesRegex(wake.coverage.CoverageError, "repeats its job"):
            self.decide()

    def test_stale_or_running_generations_are_left_to_their_own_wake(self):
        self.api.current = "b" * 40
        self.assertEqual("stale-generation", self.decide().reason)
        self.assertEqual(["head"], self.api.queries)
        self.api.current = SHA
        for status in ("queued", "in_progress", "waiting"):
            with self.subTest(status=status):
                self.api.sources = [self.api.source(37361031586, conclusion="failure"),
                                    {**self.api.source(37361039999), "status": status, "conclusion": None}]
                self.assertEqual("source-active", self.decide().reason)

    def test_evidence_too_old_to_curate_is_not_woken(self):
        self.assertEqual("generation-expired", self.decide(NOW + timedelta(hours=20)).reason)
        self.assertEqual("dispatch", self.decide(NOW + timedelta(hours=1)).reason)

    def test_a_queued_or_running_review_is_never_duplicated(self):
        for event in ("repository_dispatch", "workflow_run"):
            for status in ("queued", "in_progress", "requested", "pending"):
                with self.subTest(event=event, status=status):
                    self.api.reviews = [make_run(600, path=wake.REVIEW_WORKFLOW, event=event,
                                                 status=status, conclusion=None)]
                    self.assertEqual("review-active", self.decide().reason)
        # Reviews of another branch cannot own a protected master generation.
        self.api.reviews = [make_run(600, path=wake.REVIEW_WORKFLOW, event="repository_dispatch",
                                     branch="topic", status="queued", conclusion=None)]
        self.assertEqual("dispatch", self.decide().reason)

    def test_an_authenticated_capsule_or_report_of_any_canonical_generation_suppresses_the_wake(self):
        for report in (False, True):
            for owner_status in ("completed", "in_progress"):
                with self.subTest(report=report, owner_status=owner_status):
                    self.api.records, self.api.owners = {}, {}
                    conclusion = "failure" if owner_status == "completed" else None
                    self.api.capsule(37361031586, "mc26.3", report=report,
                                     owner_fields={"status": owner_status, "conclusion": conclusion})
                    self.assertEqual("review-exists", self.decide().reason)
        self.api.records, self.api.owners = {}, {}
        self.api.sources.append(self.api.source(37361030000, conclusion="success", wake_job="success"))
        self.api.capsule(37361030000)
        self.assertEqual("review-exists", self.decide().reason)

    def test_expired_foreign_or_unfinished_artifacts_never_suppress_the_wake(self):
        foreign = {
            "expired": dict(expired=True),
            "cancelled-owner": dict(owner_fields={"conclusion": "cancelled"}),
            "pull-request-owner": dict(owner_fields={"event": "pull_request"}),
            "producer-owner": dict(owner_fields={"path": wake.SOURCE_WORKFLOW}),
            "topic-owner": dict(owner_fields={"head_branch": "topic"}),
            "foreign-repository": dict(owner_fields={"head_repository": {"full_name": "fork/Quick-Skin-Mod"}}),
            "report-from-curator": dict(report=True, owner_fields={"path": wake.REVIEW_WORKFLOW}),
        }
        for case, fields in foreign.items():
            with self.subTest(case=case):
                self.api.records, self.api.owners = {}, {}
                self.api.capsule(37361031586, **fields)
                self.assertEqual("dispatch", self.decide().reason)

    def test_the_retry_budget_bounds_repeated_failed_review_runs(self):
        reviews = [make_run(600 + index, path=wake.REVIEW_WORKFLOW, event="repository_dispatch",
                            conclusion="failure") for index in range(wake.MAX_WAKES)]
        skipped = [make_run(700 + index, path=wake.REVIEW_WORKFLOW, event="workflow_run",
                            conclusion="skipped") for index in range(10)]
        self.api.reviews = reviews[:-1] + skipped
        self.assertEqual("dispatch", self.decide().reason)
        self.api.reviews = reviews + skipped
        self.assertEqual("wake-budget-exhausted", self.decide().reason)

    def test_the_cheapest_suppression_is_checked_first(self):
        self.api.capsule(37361031586, "mc1.20.1")
        self.assertEqual("review-exists", self.decide().reason)
        artifact_queries = [query for query in self.api.queries if query[0] == "artifacts"]
        self.assertEqual([("artifacts", f"visual-review-input-37361031586-{SHA}--mc1.20.1")],
                         artifact_queries)

    def test_every_target_capsule_and_report_name_is_checked_before_a_wake(self):
        self.assertEqual(f"visual-review-input-55-{SHA}--mc1.20.1", wake.review_names(55, SHA)[0][0])
        names = [name for name, _workflow, _events in wake.review_names(37361031586, SHA)]
        keys = [row["bundle_key"] for row in
                wake.coverage.inventory(wake.coverage.DEFAULT_MATRIX)["include"]]
        self.assertEqual(len(keys) * 2 + 3, len(names))
        for key in keys:
            self.assertIn(f"visual-review-input-37361031586-{SHA}--{key}", names)
            self.assertIn(f"visual-review-37361031586--{key}", names)
        self.assertEqual([f"visual-review-input-37361031586-{SHA}", "visual-review-input-37361031586",
                          "visual-review-37361031586"], names[-3:])
        self.assertEqual("dispatch", self.decide().reason)
        queried = [query[1] for query in self.api.queries if query[0] == "artifacts"]
        self.assertEqual(names, queried)


class WakeRecoveryApiTest(unittest.TestCase):
    def api(self, pages):
        api = wake.Api(REPOSITORY)
        responses = iter(pages)
        endpoints = []

        def json_response(endpoint):
            endpoints.append(endpoint)
            return copy.deepcopy(next(responses))
        api.json = json_response  # type: ignore[method-assign]
        return api, endpoints

    @staticmethod
    def listed(identifier, **fields):
        return {**make_run(identifier), **fields}

    def test_run_inventory_is_paginated_bounded_and_identity_checked(self):
        first = [self.listed(index) for index in range(1, 101)]
        api, endpoints = self.api([{"total_count": 101, "workflow_runs": first},
                                   {"total_count": 101, "workflow_runs": [self.listed(101)]}])
        self.assertEqual(101, len(api.runs(wake.SOURCE_WORKFLOW, SHA)))
        self.assertTrue(all(endpoint.startswith("actions/workflows/on-demand-e2e.yml/runs?head_sha=" + SHA)
                            for endpoint in endpoints))
        for case, pages in {
            "over-budget": [{"total_count": 1001, "workflow_runs": []}],
            "truncated": [{"total_count": 3, "workflow_runs": [self.listed(1)]}],
            "duplicate": [{"total_count": 2, "workflow_runs": [self.listed(1), self.listed(1)]}],
            "foreign-sha": [{"total_count": 1, "workflow_runs": [self.listed(1, head_sha="b" * 40)]}],
            "foreign-path": [{"total_count": 1, "workflow_runs": [self.listed(1, path=wake.REVIEW_WORKFLOW)]}],
            "foreign-repository": [{"total_count": 1, "workflow_runs": [
                self.listed(1, head_repository={"full_name": "fork/Quick-Skin-Mod"})]}],
            "unknown-status": [{"total_count": 1, "workflow_runs": [self.listed(1, status="weird")]}],
            "changing-total": [{"total_count": 101, "workflow_runs": first},
                               {"total_count": 102, "workflow_runs": [self.listed(101)]}],
        }.items():
            with self.subTest(case=case):
                api, _endpoints = self.api(pages)
                with self.assertRaises(wake.coverage.CoverageError):
                    api.runs(wake.SOURCE_WORKFLOW, SHA)
        api, _endpoints = self.api([])
        with self.assertRaises(wake.coverage.CoverageError):
            api.runs(".github/workflows/pages.yml", SHA)

    def test_main_writes_only_validated_outputs_and_fails_visibly_on_api_errors(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()),                 contextlib.redirect_stderr(io.StringIO()) as errors:
            output = Path(temporary) / "output"
            with patch.object(wake, "decide", return_value=wake.Decision("dispatch", 37361031586)):
                self.assertEqual(0, wake.main(["--github-repository", REPOSITORY, "--source-sha", SHA,
                                               "--github-output", str(output)]))
            self.assertEqual(["dispatch=true", "reason=dispatch", "source_run_id=37361031586",
                              f"source_sha={SHA}"], output.read_text().splitlines())
            output.unlink()
            with patch.object(wake, "decide", return_value=wake.Decision("review-exists")):
                self.assertEqual(0, wake.main(["--github-repository", REPOSITORY, "--source-sha", SHA,
                                               "--github-output", str(output)]))
            self.assertEqual(["dispatch=false", "reason=review-exists"], output.read_text().splitlines())
            output.unlink()
            failure = wake.coverage.CoverageError("bounded GitHub GET failed or timed out")
            with patch.object(wake, "decide", side_effect=failure):
                self.assertEqual(1, wake.main(["--github-repository", REPOSITORY, "--source-sha", SHA,
                                               "--github-output", str(output)]))
            self.assertFalse(output.exists())
            self.assertEqual(1, wake.main(["--github-repository", REPOSITORY, "--source-sha", "A" * 40,
                                           "--github-output", str(output)]))
            self.assertFalse(output.exists())
        self.assertEqual(2, len(errors.getvalue().splitlines()))


class WakeRecoveryWorkflowTest(unittest.TestCase):
    def test_recovery_reads_with_least_privilege_and_wakes_with_the_producer_payload(self):
        workflow = (WORKFLOWS / "visual-review-wake-recovery.yml").read_text(encoding="utf-8")
        header, jobs = workflow.split("\njobs:\n", 1)
        triggers = header.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual('  schedule:\n    - cron: "41 * * * *"\n  workflow_dispatch:\n', triggers)
        self.assertIn("\npermissions: {}\n", header)
        self.assertIn("cancel-in-progress: false", header)
        self.assertEqual({"inspect", "wake"}, set(re.findall(r"(?m)^  ([a-z0-9-]+):\n", jobs)))
        inspect, write = jobs.split("\n  wake:\n", 1)
        self.assertIn("    permissions:\n      actions: read\n      contents: read\n", inspect)
        self.assertNotIn("write", inspect)
        self.assertIn("scripts/ci/visual_review_wake.py", inspect)
        self.assertIn("release_sources.py --kind mode", inspect)
        self.assertIn("if: github.ref == 'refs/heads/master'", inspect)
        self.assertIn("    permissions:\n      contents: write\n    steps:", write)
        self.assertNotIn("actions:", write)
        self.assertIn("if: needs.inspect.outputs.dispatch == 'true'", write)
        self.assertNotIn("visual_review_wake.py", write)
        self.assertIn('"$SOURCE_SHA" != "$GITHUB_SHA"', write)
        self.assertIn("source scripts/ci/github_api_retry.sh", write)
        self.assertIn('branches/master" --jq .commit.sha', write)
        self.assertNotIn("gh api", workflow)
        self.assertNotIn("${{ github.event", workflow)
        self.assertNotIn("${{ inputs.", workflow)
        self.assertEqual(2, workflow.count("persist-credentials: false"))
        producer = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        payload = re.compile(r"jq -n --arg repository .*?> \"\$payload\"", re.S)
        producer_payload = payload.search(producer.split("\n  notify-shared-review:\n", 1)[1]).group(0)
        recovery_payload = payload.search(write).group(0)
        self.assertEqual(producer_payload.replace("$GITHUB_RUN_ID", "$SOURCE_RUN_ID")
                         .replace("$GITHUB_SHA", "$SOURCE_SHA"), recovery_payload)

    def test_recovery_mirrors_the_exact_producer_job_names(self):
        names = job_names("on-demand-e2e.yml")
        self.assertEqual(wake.WAKE_JOB, names["notify-shared-review"])
        # The required context keeps its literal name for every non-draft run.
        self.assertIn(f"|| '{wake.GATE_JOB}' }}}}", names["required-gate"])
        producer = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        notify = producer.split("\n  notify-shared-review:\n", 1)[1].split("\n  notify-version-port:\n", 1)[0]
        # The recovery's canonical rule is the wake job's own evaluated condition.
        for clause in ("needs.required-gate.result == 'success'", "github.event_name == 'workflow_dispatch'",
                       "inputs.attest_run_id == ''", "github.ref == 'refs/heads/master'"):
            self.assertIn(clause, notify)
        self.assertIn("timeout-minutes: 60", notify)
        self.assertIn("continue-on-error: true", notify)
        self.assertEqual(wake.REVIEW_WORKFLOW, ".github/workflows/visual-review.yml")
        self.assertEqual(wake.SOURCE_WORKFLOW, ".github/workflows/on-demand-e2e.yml")


if __name__ == "__main__":
    unittest.main()
