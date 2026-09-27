from __future__ import annotations

import copy
import unittest
from unittest.mock import patch

import test_feature_coverage_github as fixtures

publisher = fixtures.publisher


class FeatureCoverageOwnerReuseTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.FeatureCoverageGitHubTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.api = self.fixture.api

    def test_sixteen_public_targets_share_one_successfully_authenticated_owner(self):
        with patch.object(self.api, "run", wraps=self.api.run) as runs, \
                patch.object(self.api, "jobs", wraps=self.api.jobs) as jobs, \
                patch.object(publisher, "validate_public_owner", wraps=publisher.validate_public_owner) as admission:
            result = self.fixture.prepare()
        count = len(self.fixture.fixture.targets)
        self.assertEqual(count, len(result["public_artifacts"]))
        self.assertEqual(1, sum(call.args[0] == 8000 for call in runs.call_args_list))
        self.assertEqual(1, sum(call.args[0]["id"] == 8000 for call in jobs.call_args_list))
        self.assertEqual(count, admission.call_count)
        self.assertEqual(count, len(self.api.downloaded))

    def test_distinct_public_owners_receive_separate_admission(self):
        owner = {**self.api.runs[8000], "id": 8001}
        self.api.runs[8001] = owner
        self.api.job_lists[8001] = copy.deepcopy(self.api.job_lists[8000])
        for page in self.api.job_lists[8001]:
            for job in page["jobs"]:
                job["run_id"] = 8001
        public = [records[0] for name, records in self.api.records.items() if name.startswith("mb-baseline--")]
        for record in public[len(public) // 2:]:
            record["workflow_run"]["id"] = 8001
        with patch.object(self.api, "run", wraps=self.api.run) as runs, \
                patch.object(self.api, "jobs", wraps=self.api.jobs) as jobs:
            self.assertIsNotNone(self.fixture.prepare())
        for identifier in (8000, 8001):
            self.assertEqual(1, sum(call.args[0] == identifier for call in runs.call_args_list))
            self.assertEqual(1, sum(call.args[0]["id"] == identifier for call in jobs.call_args_list))

    def test_reused_owner_still_rejects_a_later_artifacts_foreign_head(self):
        record = next(records[0] for name, records in reversed(self.api.records.items())
                      if name.startswith("mb-baseline--"))
        record["workflow_run"]["head_sha"] = "f" * 40
        with self.assertRaisesRegex(ValueError, "successful protected Pages owner"):
            self.fixture.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_each_targets_exact_retention_job_is_still_required(self):
        self.api.job_lists[8000][0]["jobs"][-1]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "retained successfully"):
            self.fixture.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_public_owner_api_failure_stops_admission(self):
        original = self.api.jobs

        def jobs(run):
            if run["id"] == 8000:
                raise publisher.coverage.CoverageError("public owner API unavailable")
            return original(run)

        with patch.object(self.api, "jobs", side_effect=jobs):
            with self.assertRaisesRegex(ValueError, "API unavailable"):
                self.fixture.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_another_invocation_reauthenticates_the_public_owner(self):
        self.assertIsNotNone(self.fixture.prepare())
        self.api.runs[8000]["conclusion"] = "failure"
        directory = self.fixture.fixture.root / "next-collector"
        directory.mkdir()
        with self.assertRaisesRegex(ValueError, "successful protected Pages owner"):
            publisher.prepare(self.api, repository=fixtures.ROOT,
                source_sha=self.fixture.fixture.source, source_run_id=self.fixture.fixture.run_id,
                issuer_run_id=9001, directory=directory)


if __name__ == "__main__":
    unittest.main()
