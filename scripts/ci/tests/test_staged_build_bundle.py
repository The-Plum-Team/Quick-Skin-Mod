from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import staged_build_bundle as bundle


class Api:
    def __init__(self, source):
        self.source = source
        self.run = {"id": 10, "event": source.event, "path": bundle.WORKFLOW,
                    "head_sha": source.head, "head_branch": source.branch,
                    "head_repository": {"full_name": source.head_repository},
                    "status": "completed", "conclusion": "success", "run_attempt": 1}
        self.runs = [self.run]
        self.job = {"name": "Build and verify", "status": "completed", "conclusion": "success"}
        self.artifact = {"id": 20, "name": bundle.ARTIFACT, "expired": False,
                         "size_in_bytes": 100, "digest": "sha256:" + "c" * 64}
        self.bundles = [self.artifact]
        self.pull = {"number": source.pull_request, "state": "open",
                     "head": {"sha": source.head, "ref": source.branch,
                              "repo": {"full_name": source.head_repository}},
                     "base": {"sha": source.base, "repo": {"full_name": source.repository}}}
        self.requests = []
        self.total = None
        self.after_artifacts = lambda: None

    def json(self, path):
        self.requests.append(path)
        if path.startswith("pulls/"):
            return self.pull
        return {"total_count": len(self.runs) if self.total is None else self.total,
                "workflow_runs": self.runs}

    def jobs(self, run):
        return [{"jobs": [self.job]}]

    def artifacts(self, *, run_id):
        self.after_artifacts()
        return self.bundles


class StagedBuildBundleTest(unittest.TestCase):
    def setUp(self):
        self.source = bundle.Source("owner/repo", "pull_request", "a" * 40, "feature/hud",
                                    "contributor/fork", "b" * 40, 12, "d" * 40)
        self.api = Api(self.source)

    def find(self, api=None, source=None, **kwargs):
        return bundle.find_bundle(api or self.api, source or self.source, wait_seconds=0, **kwargs)

    def test_pr_authenticates_head_and_bundle_but_keeps_the_distinct_tested_merge(self):
        result = self.find()
        self.assertEqual({"run_id": 10, "artifact_id": 20, "tested_commit": "b" * 40,
                          "digest": "sha256:" + "c" * 64}, result)
        self.assertEqual(2, self.api.requests.count("pulls/12"))
        self.assertIn("head_sha=" + "a" * 40, self.api.requests[1])

    def test_pr_waits_for_one_build_to_finish_without_a_second_compile(self):
        self.api.run.update(status="in_progress", conclusion=None)
        seconds = [0]
        def sleep(amount):
            seconds[0] += amount
            self.api.run.update(status="completed", conclusion="success")
        result = bundle.find_bundle(self.api, self.source, wait_seconds=60,
                                    sleep=sleep, now=lambda: seconds[0])
        self.assertEqual(20, result["artifact_id"])
        self.assertEqual(30, seconds[0])

    def test_missing_or_stalled_pr_build_fails_instead_of_silently_rebuilding(self):
        for runs in ([], [{**self.api.run, "status": "queued", "conclusion": None}]):
            with self.subTest(runs=runs):
                self.api.runs = runs
                with self.assertRaisesRegex(ValueError, "timed out"):
                    self.find()

    def test_failed_latest_build_cannot_fall_back_to_an_older_success(self):
        self.api.runs.append({**self.api.run, "id": 11, "conclusion": "failure"})
        with self.assertRaisesRegex(ValueError, "latest.*did not succeed"):
            self.find()

    def test_wrong_source_and_incomplete_or_duplicate_inventory_cannot_be_reused(self):
        for mutation in ({"head_sha": "f" * 40}, {"path": ".github/workflows/other.yml"},
                         {"event": "push"}, {"id": True}):
            with self.subTest(mutation=mutation):
                api = Api(self.source)
                api.run.update(mutation)
                with self.assertRaises(ValueError):
                    self.find(api)
        self.api.runs.append(copy.deepcopy(self.api.run))
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.find()
        self.api.runs.pop()
        self.api.total = 2
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.find()

    def test_foreign_branch_or_head_repository_never_supplies_the_pr_build(self):
        for mutation in ({"head_branch": "other"}, {"head_repository": {"full_name": "other/repo"}}):
            with self.subTest(mutation=mutation):
                api = Api(self.source)
                api.run.update(mutation)
                with self.assertRaises(ValueError):
                    self.find(api)

    def test_skipped_or_failed_required_gate_never_supplies_a_bundle(self):
        for conclusion in ("skipped", "cancelled", "failure"):
            with self.subTest(conclusion=conclusion):
                self.api.job["conclusion"] = conclusion
                with self.assertRaisesRegex(ValueError, "required gate"):
                    self.find()

    def test_unavailable_pr_bundle_is_a_failure(self):
        for bundles in ([], [{**self.api.artifact, "expired": True}]):
            with self.subTest(bundles=bundles):
                self.api.bundles = bundles
                with self.assertRaisesRegex(ValueError, "no available"):
                    self.find()

    def test_unknown_duplicate_or_oversized_artifact_cannot_be_reused(self):
        for mutation in ({"id": True}, {"digest": "sha256:no"}, {"expired": None},
                         {"size_in_bytes": 0}, {"size_in_bytes": bundle.MAX_BUNDLE_BYTES + 1}):
            with self.subTest(mutation=mutation):
                api = Api(self.source)
                api.artifact.update(mutation)
                with self.assertRaises(ValueError):
                    self.find(api)
        self.api.bundles.append(copy.deepcopy(self.api.artifact))
        with self.assertRaisesRegex(ValueError, "multiple"):
            self.find()

    def test_closed_or_advanced_pr_is_rejected_even_after_artifact_lookup(self):
        for field in ("head", "base"):
            with self.subTest(field=field):
                api = Api(self.source)
                api.after_artifacts = lambda: api.pull[field].update(sha="f" * 40)
                with self.assertRaisesRegex(ValueError, "advanced"):
                    self.find(api)
        self.api.pull["state"] = "closed"
        with self.assertRaisesRegex(ValueError, "closed"):
            self.find()

    def test_standalone_run_without_available_bundle_can_build_the_complete_matrix(self):
        source = bundle.Source("owner/repo", "push", "a" * 40, "master", "owner/repo", "a" * 40)
        api = Api(source)
        self.assertEqual(20, self.find(api, source)["artifact_id"])
        api.bundles = []
        self.assertIsNone(self.find(api, source))
        api.bundles = [{**api.artifact, "expired": True}]
        self.assertIsNone(self.find(api, source))
        api.runs = []
        self.assertIsNone(self.find(api, source))

    def test_environment_keeps_fork_identity_and_pr_merge_distinct(self):
        environment = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "b" * 40,
                       "GITHUB_EVENT_NAME": "pull_request"}
        event = {"number": 12, "pull_request": self.api.pull}
        self.assertEqual(self.source, bundle.source_from_environment(environment, event))


if __name__ == "__main__":
    unittest.main()
