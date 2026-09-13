from __future__ import annotations

import copy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import feature_coverage_request as scheduler


class Api:
    repository = "The-Plum-Team/Quick-Skin-Mod"
    sha = "a" * 40

    def __init__(self):
        self.current = self.sha
        self.source = {"id": 10, "run_attempt": 1, "path": scheduler.SOURCE_WORKFLOW,
                       "event": "workflow_dispatch", "head_sha": self.sha, "head_branch": "master",
                       "head_repository": {"full_name": self.repository}, "status": "completed",
                       "conclusion": "success"}
        self.collectors = []
        self.payloads = []
        self.queries = []
        self.records = {}
        self.source_records = []
        self.visible = True
        self.owners = {}
        for index, row in enumerate(scheduler.coverage.inventory(scheduler.coverage.DEFAULT_MATRIX)["include"]):
            key = row["bundle_key"]
            for public in (True, False):
                name = (scheduler.publisher.public_baseline_name(key, self.sha, 10) if public
                        else f"visual-review-10--{key}")
                self.records[name] = [{"id": 1000 + index * 2 + int(public), "name": name,
                    "expired": False, "size_in_bytes": 1024, "digest": "sha256:" + "b" * 64,
                    "created_at": "2026-09-09T00:00:00Z",
                    "workflow_run": {"id": 50 if public else 100 + index, "head_sha": self.sha,
                                     "head_branch": "master"}}]
                owner_id = 50 if public else 100 + index
                self.owners[owner_id] = {**self.source, "id": owner_id,
                    "path": scheduler.publisher.PAGES_WORKFLOW if public else scheduler.coverage.DRAIN_WORKFLOW,
                    "event": "workflow_dispatch" if public else "repository_dispatch"}

    def current_sha(self):
        self.queries.append("head")
        return self.current

    def run(self, identifier):
        self.queries.append(("run", identifier))
        return copy.deepcopy(self.source if identifier == self.source["id"] else self.owners[identifier])

    def artifacts(self, *, run_id=None, name=None):
        self.queries.append(("artifacts", run_id, name))
        return copy.deepcopy(self.source_records if run_id is not None else self.records.get(name, []))

    def runs(self, workflow, source_sha):
        self.queries.append(("runs", workflow, source_sha))
        return copy.deepcopy(self.collectors)

    def dispatch(self, payload):
        self.payloads.append(payload)
        if self.visible:
            self.collectors = [{**self.source, "id": 500 + len(self.payloads), "path": scheduler.publisher.WORKFLOW,
                "event": "repository_dispatch", "status": "queued", "conclusion": None,
                "display_title": scheduler.title(10, self.source["run_attempt"])}]


class RequestTests(unittest.TestCase):
    def setUp(self):
        self.api = Api()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.existing = patch.object(scheduler, "existing_available", return_value=False).start()
        self.addCleanup(patch.stopall)

    def request(self):
        return scheduler.request(self.api, repository=ROOT, source_sha=self.api.sha, source_id=10,
                                 producer_id=100, directory=Path(self.directory.name), sleep=lambda _: None)

    def test_sixteen_review_and_eight_pages_burst_starts_one_collector(self):
        results = [self.request() for _ in range(24)]
        self.assertEqual(results, ["collector-dispatched"] + ["collector-active"] * 23)
        self.assertEqual(len(self.api.payloads), 1)
        self.assertEqual(self.api.payloads[0]["client_payload"]["source_run_attempt"], "1")
        self.assertEqual(self.existing.call_count, 1)

    def test_pages_last_and_reviews_last_both_dispatch_when_complete(self):
        for public in (True, False):
            with self.subTest(public=public):
                self.api = Api()
                name = next(name for name in self.api.records if name.startswith("pages-") == public)
                record = self.api.records.pop(name)
                self.assertEqual(self.request(), "evidence-incomplete")
                self.assertEqual(self.api.payloads, [])
                self.api.records[name] = record
                self.assertEqual(self.request(), "collector-dispatched")

    def test_public_first_gap_does_not_enumerate_review_owners_or_download(self):
        self.api.records = {name: records for name, records in self.api.records.items()
                            if not name.startswith("pages-")}
        self.assertEqual(self.request(), "evidence-incomplete")
        inventories = [entry for entry in self.api.queries if isinstance(entry, tuple)
                       and entry[0] == "artifacts" and entry[2] is not None]
        self.assertEqual(len(inventories), 1)

    def test_available_certificate_suppresses_finished_collector_duplicates(self):
        self.existing.return_value = True
        self.assertEqual(self.request(), "certificate-available")
        self.assertEqual(self.api.payloads, [])
        self.assertFalse(any(isinstance(item, tuple) and item[0] == "artifacts" and item[2]
                             for item in self.api.queries))

    def test_withdrawn_certificate_and_failed_collector_reopen_one_request(self):
        self.assertEqual(self.request(), "collector-dispatched")
        self.api.collectors[0].update(status="completed", conclusion="failure")
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(self.request(), "collector-active")
        self.assertEqual(len(self.api.payloads), 2)

    def test_lost_notification_recovery_is_same_idempotent_request(self):
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(self.request(), "collector-active")
        self.assertEqual(len(self.api.payloads), 1)

    def test_api_error_never_means_absence(self):
        self.api.artifacts = lambda **_: (_ for _ in ()).throw(scheduler.coverage.CoverageError("API failure"))
        with self.assertRaisesRegex(scheduler.coverage.CoverageError, "API failure"):
            self.request()
        self.assertEqual(self.api.payloads, [])

    def test_expired_public_archive_cannot_hide_behind_readiness(self):
        name = next(name for name in self.api.records if name.startswith("pages-"))
        self.api.records[name][0]["expired"] = True
        self.assertEqual(self.request(), "evidence-incomplete")
        self.api.records[name][0]["expired"] = False
        self.assertEqual(self.request(), "collector-dispatched")

    def test_foreign_artifact_and_ambiguous_review_fail_closed(self):
        for mutation in ("foreign", "duplicate"):
            with self.subTest(mutation=mutation):
                self.api = Api()
                name = next(name for name in self.api.records if name.startswith("visual-review-"))
                if mutation == "foreign":
                    self.api.records[name][0]["workflow_run"]["head_sha"] = "c" * 40
                else:
                    self.api.records[name].append(copy.deepcopy(self.api.records[name][0]))
                with self.assertRaises(scheduler.coverage.CoverageError):
                    self.request()
                self.assertEqual(self.api.payloads, [])

    def test_current_head_or_attempt_change_prevents_dispatch(self):
        original = self.api.current_sha
        calls = 0

        def advancing():
            nonlocal calls
            calls += 1
            return original() if calls == 1 else "c" * 40

        self.api.current_sha = advancing
        self.assertEqual(self.request(), "source-advanced")
        self.assertEqual(self.api.payloads, [])
        self.api = Api()
        original_run = self.api.run
        calls = 0

        def advancing_attempt(identifier):
            nonlocal calls
            calls += 1
            record = original_run(identifier)
            if calls > 1:
                record["run_attempt"] = 2
            return record

        self.api.run = advancing_attempt
        self.assertEqual(self.request(), "source-advanced")
        self.assertEqual(self.api.payloads, [])

    def test_dispatch_visibility_is_bounded_and_failure_remains_visible(self):
        self.api.visible = False
        with self.assertRaisesRegex(scheduler.coverage.CoverageError, "not observable"):
            self.request()
        self.assertEqual(len(self.api.payloads), 1)
        inventories = [entry for entry in self.api.queries if isinstance(entry, tuple) and entry[0] == "runs"]
        self.assertEqual(len(inventories), 16)

    def test_collector_that_finishes_before_visibility_read_is_still_observed(self):
        dispatch = self.api.dispatch

        def completed(payload):
            dispatch(payload)
            self.api.collectors[0].update(status="completed", conclusion="success")

        self.api.dispatch = completed
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(len(self.api.payloads), 1)

    def test_last_owner_tail_reopens_one_followup_after_early_collector_noop(self):
        # Upload can precede terminal owner status. An earlier collector cannot certify it;
        # the last producer tail/completion recovery takes the same short lock afterwards.
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(self.request(), "collector-active")
        self.api.collectors[0].update(status="completed", conclusion="success")
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(self.request(), "collector-active")
        self.assertEqual(len(self.api.payloads), 2)

    def test_pending_owner_burst_dispatches_only_from_last_tail(self):
        for owner in self.api.owners.values():
            owner.update(status="in_progress", conclusion=None)
        self.api.owners[50].update(status="completed", conclusion="success")
        results = []
        for producer in range(100, 116):
            result = scheduler.request(self.api, repository=ROOT, source_sha=self.api.sha, source_id=10,
                producer_id=producer, directory=Path(self.directory.name), sleep=lambda _: None)
            results.append(result)
            self.api.owners[producer].update(status="completed", conclusion="success")
        self.assertEqual(results, ["evidence-incomplete"] * 15 + ["collector-dispatched"])
        self.assertEqual(len(self.api.payloads), 1)

    def test_recovery_requires_even_the_last_owner_terminal(self):
        self.api.owners[100].update(status="in_progress", conclusion=None)
        self.assertFalse(scheduler.metadata_ready(self.api, self.api.sha, 10))
        self.assertTrue(scheduler.metadata_ready(self.api, self.api.sha, 10, producer_id=100))
        self.api.owners[100].update(status="completed", conclusion="success")
        self.assertTrue(scheduler.metadata_ready(self.api, self.api.sha, 10))

    def test_saved_twenty_four_staggered_requests_start_one_collector(self):
        import json
        fixture = json.loads((ROOT / "scripts/ci/tests/fixtures/baseline-readiness-2026-09-09.json").read_text())
        records = self.api.records
        self.api.records = {}
        outcomes = []
        for event in fixture["events"]:
            if event["kind"] == "pages":
                self.api.records.update({name: value for name, value in records.items() if name.startswith("pages-")})
            else:
                name = f"visual-review-10--{event['target']}"
                self.api.records[name] = records[name]
            if event["at"] >= fixture["issuer_completed_at"]:
                self.api.collectors[0].update(status="completed", conclusion="success")
                self.existing.return_value = True
            outcomes.append(self.request())
        self.assertEqual(outcomes.count("evidence-incomplete"), 20)
        self.assertEqual(outcomes.count("collector-dispatched"), 1)
        self.assertEqual(outcomes.count("certificate-available"), 3)
        self.assertEqual(len(self.api.payloads), 1)
        self.assertEqual(sum(event["kind"] == "review" for event in fixture["events"]), 16)
        self.assertEqual(sum(event["kind"] == "pages" for event in fixture["events"]), 8)

    def test_selected_or_failed_source_never_dispatches(self):
        self.api.source_records = [{"name": scheduler.coverage.SELECTION_ARTIFACT_NAME}]
        self.assertEqual(self.request(), "selected-source")
        self.api.source_records = []
        self.api.source["conclusion"] = "failure"
        self.assertEqual(self.request(), "source-not-successful")
        self.assertEqual(self.api.payloads, [])

    def test_workflows_share_only_short_request_lock_and_keep_canonical_collector(self):
        for filename in ("pages.yml", "visual-review-drain.yml"):
            workflow = (ROOT / ".github/workflows" / filename).read_text()
            tail = workflow.split("  request-feature-coverage:\n", 1)[1].split("\n  ", 1)[0]
            self.assertIn("request-feature-coverage", workflow)
            self.assertIn("quick-skin-feature-baseline-request-${{ github.sha }}", workflow)
            self.assertIn("feature_coverage_request.py --producer-run-id", workflow)
        collector = (ROOT / ".github/workflows/feature-coverage.yml").read_text()
        self.assertIn("scripts/ci/feature_coverage_github.py", collector)
        self.assertNotIn("quick-skin-feature-baseline-request-", collector)
        recovery = (ROOT / ".github/workflows/feature-coverage-request.yml").read_text()
        self.assertIn("workflow_run:", recovery)
        self.assertIn("schedule:", recovery)
        self.assertIn("workflow_dispatch:", recovery)


class CanonicalRequestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import test_feature_coverage_consumer as fixtures
        cls.fixtures = fixtures
        fixtures.FeatureCoverageConsumerTest.setUpClass()
        cls.addClassCleanup(fixtures.FeatureCoverageConsumerTest.doClassCleanups)

    def setUp(self):
        self.fixture = self.fixtures.FeatureCoverageConsumerTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.fixture.api.live = [self.fixture.base]
        inner = self.fixture.api

        class Adapter:
            repository = inner.repository

            def __init__(self):
                self.collectors = []
                self.payloads = []

            def __getattr__(self, name):
                return getattr(inner, name)

            def runs(self, workflow, source_sha):
                return self.collectors

            def dispatch(self, payload):
                self.payloads.append(payload)
                self.collectors = [{**inner.runs[9000], "id": 9100, "status": "queued", "conclusion": None,
                                    "display_title": scheduler.title(55, 1)}]

        self.api = Adapter()

    def request(self):
        directory = self.fixture.fixture.root / "request"
        directory.mkdir()
        return scheduler.request(self.api, repository=self.fixture.repository, source_sha=self.fixture.base,
                                 source_id=55, producer_id=None, directory=directory, sleep=lambda _: None)

    def test_real_git_certificate_zip_runtime_and_public_validation_precede_suppression(self):
        self.assertEqual(self.request(), "certificate-available")
        self.assertEqual(self.api.payloads, [])
        self.assertEqual(self.fixture.api.downloaded, [self.fixture.artifact["id"]])

    def test_withdrawn_public_archive_is_not_hidden_by_previously_valid_certificate(self):
        name = next(iter(self.fixture.baseline["public_artifacts"].values()))["name"]
        self.fixture.api.records.pop(name)
        self.assertEqual(self.request(), "evidence-incomplete")
        self.assertEqual(self.api.payloads, [])

    def test_changed_certificate_policy_reopens_request_without_assembling_reports_in_producer(self):
        value = copy.deepcopy(self.fixture.baseline)
        value["policy_sha256"] = "f" * 64
        self.fixture.replace(value)
        self.assertEqual(self.request(), "collector-dispatched")
        self.assertEqual(len(self.api.payloads), 1)
        self.assertEqual(self.fixture.api.downloaded, [self.fixture.artifact["id"]])


if __name__ == "__main__":
    unittest.main()
