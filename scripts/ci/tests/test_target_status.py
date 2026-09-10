from __future__ import annotations

import copy
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import target_status as status
from matrix import load_matrix, read_mod_version
from target_status_render import validate_snapshot
from test_ci_reuse import FixtureApi

SHA = "a" * 40
REPOSITORY = "owner/repo"


class Transport:
    def __init__(self):
        data = load_matrix(status.DEFAULT_MATRIX)
        self.targets = status.targets(data, read_mod_version(status.DEFAULT_MATRIX, data))
        self.responses = {"branches/master": {"commit": {"sha": SHA}}}
        self.reads = []
        for kind, identifier in (("build", 10), ("e2e", 20)):
            run = {"id": identifier, "run_attempt": 1, "head_sha": SHA, "head_branch": "master",
                   "head_repository": {"full_name": REPOSITORY}, "status": "completed",
                   "conclusion": "success", "path": status.reuse.WORKFLOWS[kind],
                   "event": "push" if kind == "build" else "workflow_dispatch"}
            self.responses[status.run_endpoint(kind, SHA)] = {"total_count": 1, "workflow_runs": [run]}
            self.responses[f"actions/runs/{identifier}"] = run
            self.responses[f"actions/runs/{identifier}/artifacts?per_page=100"] = {"total_count": 0, "artifacts": []}
            names = sorted({name for row in self.targets for name in row[kind]})
            jobs = [{"id": index + 1, "run_id": identifier, "run_attempt": 1, "head_sha": SHA,
                     "name": name, "status": "completed", "conclusion": "success"}
                    for index, name in enumerate(names)]
            self.responses[self.jobs_endpoint(kind)] = {"total_count": len(jobs), "jobs": jobs}

    def jobs_endpoint(self, kind):
        identifier = 10 if kind == "build" else 20
        return f"actions/runs/{identifier}/attempts/1/jobs?per_page=100&page=1"

    def jobs(self, kind):
        return self.responses[self.jobs_endpoint(kind)]["jobs"]

    def get(self, endpoint):
        self.reads.append(endpoint)
        value = self.responses[endpoint]
        if isinstance(value, Exception):
            raise value
        if callable(value):
            value = value(self.reads.count(endpoint))
        return copy.deepcopy(value)

    def collect(self):
        with patch.object(status.Api, "json", side_effect=self.get), patch("sys.stderr", new_callable=io.StringIO):
            return status.collect(status.StatusApi(REPOSITORY), SHA)


class TargetStatusTest(unittest.TestCase):
    def setUp(self):
        self.transport = Transport()

    def states(self, result, kind):
        return {row["version"]: row[kind]["state"] for row in result["targets"]}

    def test_all_targets_share_one_job_inventory_per_gate(self):
        result = self.transport.collect()
        validate_snapshot(result)
        self.assertEqual(16, len(result["targets"]))
        self.assertEqual({"success"}, set(self.states(result, "build").values()))
        self.assertEqual({"success"}, set(self.states(result, "e2e").values()))
        for kind in ("build", "e2e"):
            self.assertEqual(1, self.transport.reads.count(self.transport.jobs_endpoint(kind)))
        self.assertLessEqual(len(self.transport.reads), 16)

    def test_build_target_failure_does_not_fail_sibling_targets(self):
        selected = self.transport.targets[0]
        for job in self.transport.jobs("build"):
            if job["name"] == selected["build"][-1]:
                job["conclusion"] = "failure"
        self.transport.responses["actions/runs/10"]["conclusion"] = "failure"
        states = self.states(self.transport.collect(), "build")
        self.assertEqual("failure", states.pop(selected["version"]))
        self.assertEqual({"success"}, set(states.values()))

    def test_every_loader_scenario_is_required_and_failure_is_target_local(self):
        selected = self.transport.targets[0]
        self.assertGreaterEqual(len(selected["e2e"]) - len(status.E2E_SHARED), 2)
        for job in self.transport.jobs("e2e"):
            if job["name"] == selected["e2e"][-1]:
                job["conclusion"] = "timed_out"
        result = self.transport.collect()
        states = self.states(result, "e2e")
        self.assertEqual("failure", states.pop(selected["version"]))
        self.assertEqual({"success"}, set(states.values()))

    def test_missing_loader_after_completion_is_unknown(self):
        selected = self.transport.targets[0]
        page = self.transport.responses[self.transport.jobs_endpoint("e2e")]
        page["jobs"] = [job for job in page["jobs"] if job["name"] != selected["e2e"][-1]]
        page["total_count"] -= 1
        self.assertEqual("unknown", self.states(self.transport.collect(), "e2e")[selected["version"]])

    def test_shared_prerequisite_failure_affects_all_targets(self):
        for job in self.transport.jobs("build"):
            if job["name"] == "Validate repository policy":
                job["conclusion"] = "failure"
        self.assertEqual({"failure"}, set(self.states(self.transport.collect(), "build").values()))

    def test_full_runtime_does_not_require_optional_feature_selection(self):
        page = self.transport.responses[self.transport.jobs_endpoint("e2e")]
        page["jobs"].append({"id": 1000, "run_id": 20, "run_attempt": 1, "head_sha": SHA,
                             "name": "Select affected feature coverage", "status": "completed",
                             "conclusion": "skipped"})
        page["total_count"] += 1
        self.assertEqual({"success"}, set(self.states(self.transport.collect(), "e2e").values()))

    def test_descriptor_reads_are_counted_and_cannot_download_bundles(self):
        api = status.StatusApi(REPOSITORY)
        with patch.object(status.Api, "download") as download:
            api.download({}, Path("unused"), maximum=status.reuse.MAX_DESCRIPTOR_ARCHIVE)
            self.assertEqual(1, api.requests)
            with self.assertRaises(status.StatusError):
                api.download({}, Path("unused"), maximum=status.reuse.MAX_BUNDLE_BYTES)
            api.requests = 100
            with self.assertRaises(status.StatusError):
                api.download({}, Path("unused"), maximum=status.reuse.MAX_DESCRIPTOR_ARCHIVE)
            self.assertEqual(1, download.call_count)

    def test_zero_jobs_is_pending_for_new_run_but_unknown_for_completed_run(self):
        self.transport.responses[self.transport.jobs_endpoint("build")] = {"total_count": 0, "jobs": []}
        self.assertEqual({"unknown"}, set(self.states(self.transport.collect(), "build").values()))
        self.transport.responses["actions/runs/10"].update(status="queued", conclusion=None)
        self.assertEqual({"pending"}, set(self.states(self.transport.collect(), "build").values()))

    def test_no_run_for_new_generation_is_pending(self):
        self.transport.responses[status.run_endpoint("build", SHA)] = {"total_count": 0, "workflow_runs": []}
        result = self.transport.collect()
        self.assertEqual({"pending"}, set(self.states(result, "build").values()))
        self.assertIsNone(result["targets"][0]["build"]["generation"])

    def test_skipped_cancelled_and_neutral_never_turn_green(self):
        selected = self.transport.targets[0]
        job = next(job for job in self.transport.jobs("build") if job["name"] == selected["build"][-1])
        for conclusion in ("skipped", "cancelled", "neutral", "action_required"):
            with self.subTest(conclusion=conclusion):
                job["conclusion"] = conclusion
                self.assertEqual("unknown", self.states(self.transport.collect(), "build")[selected["version"]])

    def test_running_job_is_running(self):
        selected = self.transport.targets[0]
        job = next(job for job in self.transport.jobs("build") if job["name"] == selected["build"][-1])
        job.update(status="in_progress", conclusion=None)
        self.transport.responses["actions/runs/10"].update(status="in_progress", conclusion=None)
        self.assertEqual("running", self.states(self.transport.collect(), "build")[selected["version"]])

    def test_newest_pending_run_supersedes_previous_success(self):
        new = {**self.transport.responses["actions/runs/10"], "id": 11, "status": "queued", "conclusion": None}
        inventory = self.transport.responses[status.run_endpoint("build", SHA)]
        inventory["workflow_runs"].append(new)
        inventory["total_count"] = 2
        self.transport.responses["actions/runs/11"] = new
        self.transport.responses["actions/runs/11/attempts/1/jobs?per_page=100&page=1"] = {"total_count": 0, "jobs": []}
        self.transport.responses["actions/runs/11/artifacts?per_page=100"] = {"total_count": 0, "artifacts": []}
        result = self.transport.collect()
        self.assertEqual({"pending"}, set(self.states(result, "build").values()))
        self.assertEqual(11, result["targets"][0]["build"]["generation"]["id"])

    def test_malformed_or_incomplete_inventory_only_grays_affected_gate(self):
        endpoint = self.transport.jobs_endpoint("build")
        initial = copy.deepcopy(self.transport.responses[endpoint])
        for mutation in ("truncated", "duplicate", "foreign", "attempt", "duplicate_name"):
            with self.subTest(mutation=mutation):
                value = copy.deepcopy(initial)
                if mutation == "truncated": value["total_count"] += 1
                if mutation == "duplicate": value["jobs"][0]["id"] = value["jobs"][1]["id"]
                if mutation == "foreign": value["jobs"][0]["head_sha"] = "b" * 40
                if mutation == "attempt": value["jobs"][0]["run_attempt"] = 2
                if mutation == "duplicate_name": value["jobs"][0]["name"] = value["jobs"][1]["name"]
                self.transport.responses[endpoint] = value
                result = self.transport.collect()
                self.assertEqual({"unknown"}, set(self.states(result, "build").values()))
                self.assertEqual({"success"}, set(self.states(result, "e2e").values()))

    def test_api_failure_never_retains_previous_green(self):
        self.assertEqual({"success"}, set(self.states(self.transport.collect(), "build").values()))
        self.transport.responses[self.transport.jobs_endpoint("build")] = ValueError("unavailable")
        self.assertEqual({"unknown"}, set(self.states(self.transport.collect(), "build").values()))

    def test_source_attempt_change_during_observation_is_unknown(self):
        initial = copy.deepcopy(self.transport.responses["actions/runs/10"])
        self.transport.responses["actions/runs/10"] = lambda count: {**initial, "run_attempt": 1 if count == 1 else 2}
        self.assertEqual({"unknown"}, set(self.states(self.transport.collect(), "build").values()))

    def test_new_run_during_observation_is_unknown(self):
        endpoint = status.run_endpoint("build", SHA)
        initial = copy.deepcopy(self.transport.responses[endpoint])
        new = {**initial["workflow_runs"][0], "id": 11}
        self.transport.responses[endpoint] = lambda count: initial if count == 1 else {
            "total_count": 2, "workflow_runs": [initial["workflow_runs"][0], new]}
        self.assertEqual({"unknown"}, set(self.states(self.transport.collect(), "build").values()))

    def test_new_attempt_visible_only_in_fresh_inventory_invalidates_old_success(self):
        endpoint = status.run_endpoint("build", SHA)
        initial = copy.deepcopy(self.transport.responses[endpoint])
        newer = copy.deepcopy(initial)
        newer["workflow_runs"][0]["run_attempt"] = 2
        self.transport.responses[endpoint] = lambda count: initial if count == 1 else newer
        self.assertEqual({"unknown"}, set(self.states(self.transport.collect(), "build").values()))

    def test_master_advance_aborts_snapshot(self):
        self.transport.responses["branches/master"] = lambda count: {"commit": {"sha": SHA if count == 1 else "b" * 40}}
        with self.assertRaisesRegex(status.StatusError, "advanced"):
            self.transport.collect()


class ReusedTargetStatusTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.api = FixtureApi(Path(temporary.name).resolve())
        for kind, identifier in (("build", 10), ("e2e", 20)):
            # Extend the older reuse fixture with direct-run prerequisites used by badges.
            shared = status.BUILD_SHARED if kind == "build" else status.E2E_SHARED
            jobs = self.api.job_lists[identifier][0]["jobs"]
            names = {job["name"] for job in jobs}
            for name in shared:
                if name not in names:
                    jobs.append(self.api.job(self.api.runs[identifier], name, len(jobs)))
        self.references = {kind: status.reuse.find_reference(self.api, self.api.covered, kind)[0]
                           for kind in ("build", "e2e")}
        self.api.wrapper(self.references["e2e"], 30)
        run = {**self.api.runs[10], "id": 40, "head_sha": self.api.covered, "head_branch": "master", "event": "push"}
        self.api.runs[40], self.api.inventories[40] = run, []
        self.api.job_lists[40] = [{"jobs": [self.api.job(run, name, index) for index, name in enumerate(
            [status.BUILD_SHARED[0], "Build and verify"])]}]
        self.api.add_descriptor(40, 400, "reused-source-build", "reused-source.json", self.references["build"])
        original_json = self.api.json

        def json(endpoint):
            if endpoint.startswith("actions/workflows/"):
                identifier = 40 if "build-gate.yml" in endpoint else 30
                return {"total_count": 1, "workflow_runs": [copy.deepcopy(self.api.runs[identifier])]}
            if endpoint.startswith("actions/runs/"):
                return copy.deepcopy(self.api.runs[int(endpoint.rsplit("/", 1)[1])])
            return original_json(endpoint)

        self.api.json = json
        self.api.fresh_json = json
        self.api.downloaded.clear()

    def collect(self):
        with patch("sys.stderr", new_callable=io.StringIO):
            return status.collect(self.api, self.api.covered)

    def test_authenticate_original_once_per_gate_and_keep_distinct_tested_sha(self):
        with patch.object(status.reuse, "verify_reference", wraps=status.reuse.verify_reference) as verify:
            result = self.collect()
        validate_snapshot(result)
        self.assertEqual(2, verify.call_count)
        for row in result["targets"]:
            for kind in ("build", "e2e"):
                self.assertEqual("success", row[kind]["state"])
                self.assertTrue(row[kind]["reused"])
                self.assertEqual(self.api.tested, row[kind]["tested_sha"])
                self.assertNotEqual(row[kind]["tested_sha"], result["coverage_sha"])
                self.assertEqual(self.api.head, row[kind]["execution"]["sha"])
        self.assertEqual([400, 110, 300, 120], self.api.downloaded)

    def test_missing_expired_or_mixed_reference_never_turns_green(self):
        initial = copy.deepcopy(self.api.inventories[30])
        for mutation in ("missing", "expired", "mixed"):
            with self.subTest(mutation=mutation):
                self.api.inventories[30] = copy.deepcopy(initial)
                if mutation == "missing": self.api.inventories[30] = []
                if mutation == "expired": self.api.inventories[30][0]["expired"] = True
                if mutation == "mixed": self.api.add_artifact(30, 900, "packaged-e2e-unexpected")
                result = self.collect()
                self.assertEqual({"unknown"}, {row["e2e"]["state"] for row in result["targets"]})

    def test_wrapper_must_pass_its_own_gate_before_reusing_source(self):
        self.api.runs[30].update(status="in_progress", conclusion=None)
        for job in self.api.job_lists[30][0]["jobs"]:
            if job["name"] == "Packaged E2E gate":
                job.update(status="in_progress", conclusion=None)
        result = self.collect()
        self.assertEqual({"running"}, {row["e2e"]["state"] for row in result["targets"]})
        self.assertEqual([400, 110], self.api.downloaded)

    def test_tampered_original_tree_grays_reused_evidence(self):
        self.api.commits[self.api.covered]["tree"]["sha"] = "f" * 40
        result = self.collect()
        self.assertEqual({"unknown"}, {row["e2e"]["state"] for row in result["targets"]})


if __name__ == "__main__":
    unittest.main()
