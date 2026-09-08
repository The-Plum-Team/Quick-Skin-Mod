from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import feature_coverage_consumer as consumer
import test_e2e_selection as git_fixtures
import test_feature_coverage as coverage_fixtures
import test_feature_coverage_github as github_fixtures

coverage = consumer.coverage


class FeatureCoverageConsumerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.git_fixture = git_fixtures.E2ESelectionAdmissionTest()
        cls.git_fixture.setUp()
        cls.addClassCleanup(cls.git_fixture.tearDown)
        cls.files = dict(cls.git_fixture.files)
        for module in coverage.load_graph().modules:
            cls.files[module.path + "/src/main/java/example/Owned.java"] = ("100644", b"class Owned {}\n")
        cls.base = cls.git_fixture.commit(cls.files)
        cls.changed = dict(cls.files)
        cls.changed[cls.git_fixture.editor] = ("100644", b"class Editor { int zoom; }\n")
        cls.head = cls.git_fixture.commit(cls.changed, cls.base)

    def setUp(self):
        # Keep real Git authentication and all complete matrix/capture checks. The tiny object
        # fixture seals the seven selector files; production seals the whole controller tree.
        policy = patch.object(coverage, "POLICY_PATHS", coverage.admission.POLICY_PATHS)
        policy.start()
        self.addCleanup(policy.stop)
        self.fixture = coverage_fixtures.FeatureCoverageTest()
        self.fixture.setUp()
        self.fixture.source = self.base
        for artifact in self.fixture.artifacts:
            artifact["workflow_run"]["head_sha"] = self.base
        self.fixture.targets = coverage_fixtures.plan_targets(self.fixture.artifacts,
            source_run_id=self.fixture.run_id, source_branch="master", source_sha=self.base)["include"]
        self.addCleanup(self.fixture.tearDown)
        self.api = github_fixtures.FixtureApi(self.fixture)
        self.repository = self.git_fixture.repository
        directory = self.fixture.root / "publisher"
        directory.mkdir()
        self.baseline = consumer.publisher.prepare(self.api, repository=self.repository,
            source_sha=self.base, source_run_id=self.fixture.run_id, issuer_run_id=9000, directory=directory)
        self.api.downloaded.clear()
        source = self.api.runs[self.fixture.run_id]
        issuer = {**source, "id": 9000, "path": consumer.publisher.WORKFLOW, "event": "repository_dispatch"}
        self.api.runs[9000] = issuer
        self.api.job_lists[9000] = [{"jobs": [self.api.job(consumer.ISSUER_JOB, 90000, issuer)]}]
        self.api.runs[66] = {**source, "id": 66, "head_sha": self.head,
                             "status": "in_progress", "conclusion": None}
        self.api.live = [self.head]
        self.artifact = {"id": 50000, "name": coverage.BASELINE_ARTIFACT_NAME,
            "created_at": "2026-09-06T00:01:00Z", "expired": False,
            "workflow_run": {"id": 9000, "head_sha": self.base, "head_branch": "master"}}
        self.api.records[coverage.BASELINE_ARTIFACT_NAME] = [self.artifact]
        self.replace()
        self.invocations = 0

    def replace(self, value=None, extras=None):
        self.api.replace_archive(self.artifact, {
            "baseline.json": coverage.admission.canonical(self.baseline if value is None else value),
            **(extras or {})})

    def resolve(self, **kwargs):
        self.invocations += 1
        directory = self.fixture.root / f"consumer-{self.invocations}"
        directory.mkdir()
        return consumer.resolve(self.api, repository=self.repository, head=kwargs.pop("head", self.head),
            policy=kwargs.pop("policy", self.head), run_id=66, directory=directory, **kwargs)

    def test_complete_authenticated_baseline_selects_editor_and_preserves_unaffected_provenance(self):
        selected, proof = self.resolve()
        self.assertTrue(selected.enabled)
        self.assertEqual(self.base, selected.base_commit)
        self.assertEqual(self.head, selected.head_commit)
        self.assertEqual(2, sum(len(role.captures) for run in selected.runs for role in run.roles))
        self.assertEqual(50000, proof["baseline"]["id"])
        self.assertEqual(selected.sha256, proof["selection_sha256"])
        self.assertIn("cape-editor", proof["unchanged_module_fingerprints"])
        self.assertNotIn("hud-preview", proof["unchanged_module_fingerprints"])
        self.assertEqual([50000], self.api.downloaded)

    def test_cumulative_baseline_keeps_an_earlier_editor_change_when_the_tip_only_changes_hud(self):
        changed = dict(self.changed)
        changed["modules/hud-preview/src/main/java/example/Owned.java"] = ("100644", b"class Owned { int size; }\n")
        head = self.git_fixture.commit(changed, self.head)
        self.api.runs[66]["head_sha"] = head
        self.api.live = [head]
        selected, proof = self.resolve(head=head, policy=head)
        self.assertTrue(selected.enabled)
        self.assertEqual(self.base, selected.base_commit)
        self.assertEqual({"hud-preview"}, set(selected.require_selection().direct_modules))
        self.assertNotIn("hud-preview", proof["unchanged_module_fingerprints"])

    def test_missing_expired_foreign_and_failed_issuers_never_download_or_reduce(self):
        cases = (
            lambda: self.api.records.pop(coverage.BASELINE_ARTIFACT_NAME),
            lambda: self.artifact.update(expired=True),
            lambda: self.api.runs[9000].update(path=".github/workflows/untrusted.yml"),
            lambda: self.api.runs[9000].update(head_repository={"full_name": "foreign/repo"}),
            lambda: self.api.runs[9000].update(conclusion="failure"),
            lambda: self.api.job_lists[9000][0]["jobs"][0].update(conclusion="skipped"),
        )
        for change in cases:
            with self.subTest(change=change):
                records, runs, jobs = copy.deepcopy((self.api.records, self.api.runs, self.api.job_lists))
                change()
                selected, proof = self.resolve()
                self.assertFalse(selected.enabled)
                self.assertFalse(proof["selective"])
                self.assertEqual([], self.api.downloaded)
                self.api.records, self.api.runs, self.api.job_lists = records, runs, jobs
                self.artifact = self.api.records[coverage.BASELINE_ARTIFACT_NAME][0]

    def test_expired_or_replaced_public_baseline_forces_complete_runtime_coverage(self):
        record = next(items[0] for name, items in self.api.records.items()
                      if name.startswith("pages-full-baseline-"))
        original = copy.deepcopy(record)
        for change in ({"expired": True}, {"digest": "sha256:" + "0" * 64}):
            with self.subTest(change=change):
                record.update(change)
                selected, proof = self.resolve()
                self.assertFalse(selected.enabled)
                self.assertFalse(proof["selective"])
                self.assertEqual({50000}, set(self.api.downloaded))
                record.clear()
                record.update(original)

    def test_partial_native_forged_and_duplicate_coverage_cannot_be_reused(self):
        cases = (
            lambda value: value.update(coverage="selected"),
            lambda value: value.update(profile="native"),
            lambda value: value.pop("issuer"),
            lambda value: value["issuer"].update(run_id=True),
            lambda value: value.update(policy_sha256="0" * 64),
            lambda value: value["module_fingerprints"].update({"hud-preview": "0" * 64}),
            lambda value: value["targets"][0].update(frame_count=2),
            lambda value: value["targets"].pop(),
            lambda value: value["targets"].__setitem__(1, copy.deepcopy(value["targets"][0])),
            lambda value: value["targets"][0].update(source_artifact_ids=[True, 2]),
            lambda value: value["source_job_graph"]["observed_scenario_jobs"].pop(),
            lambda value: next(iter(value["review_artifacts"].values())).update(name="visual-review-999--mc1.20.1"),
        )
        for change in cases:
            with self.subTest(change=change):
                value = copy.deepcopy(self.baseline)
                change(value)
                self.replace(value)
                selected, proof = self.resolve()
                self.assertFalse(selected.enabled)
                self.assertFalse(proof["selective"])

    def test_archive_digest_extra_files_and_expanded_byte_limits_fail_closed(self):
        self.artifact["digest"] = "sha256:" + "0" * 64
        self.assertFalse(self.resolve()[0].enabled)
        self.replace(extras={"images/forbidden.png": b"not an image"})
        self.assertFalse(self.resolve()[0].enabled)
        self.api.replace_archive(self.artifact, {"baseline.json": b" " * (consumer.MAX_BASELINE_BYTES + 1)})
        self.assertFalse(self.resolve()[0].enabled)

    def test_stale_policy_and_source_advance_never_authorize_reduction(self):
        self.api.live = [self.base]
        self.assertFalse(self.resolve()[0].enabled)
        self.assertEqual([], self.api.downloaded)
        self.api.live = [self.head, self.base]
        self.assertFalse(self.resolve()[0].enabled)
        self.assertEqual([50000], self.api.downloaded)

    def test_nonancestor_baseline_and_changed_controller_require_full(self):
        unrelated = self.git_fixture.commit(self.changed)
        self.api.live = [unrelated]
        self.api.runs[66]["head_sha"] = unrelated
        self.assertFalse(self.resolve(head=unrelated, policy=unrelated)[0].enabled)
        changed = dict(self.changed)
        path = coverage.admission.POLICY_PATHS[0]
        changed[path] = ("100644", changed[path][1] + b"\n")
        head = self.git_fixture.commit(changed, self.head)
        self.api.live = [head]
        self.api.runs[66]["head_sha"] = head
        # This caller keeps the executing protected policy at the original current master.
        self.api.live = [self.base]
        with self.assertRaises(coverage.admission.AdmissionError):
            self.resolve(head=head, policy=head)

    def test_dependency_fingerprint_backstop_rejects_an_underreported_feature_selection(self):
        real = coverage.module_fingerprints
        def fingerprints(repository, commit, *args):
            result = real(repository, commit, *args)
            if commit == self.head: result["cape-editor"] = "0" * 64
            return result
        with patch.object(coverage, "module_fingerprints", side_effect=fingerprints):
            self.assertFalse(self.resolve()[0].enabled)

    def test_pr_merge_is_bound_to_exact_github_base_head_and_both_git_parents(self):
        merge = self.git_fixture.git("commit-tree", self.head + "^{tree}", "-p", self.base,
                                    "-p", self.head, input=b"temporary PR merge\n").strip().decode()
        run = self.api.runs[66]
        run.update(event="pull_request", head_branch="feature/editor", pull_requests=[{"number": 88}])
        pull = {"number": 88, "state": "open", "merge_commit_sha": merge,
                "base": {"sha": self.base, "ref": "master", "repo": {"full_name": self.api.repository}},
                "head": {"sha": self.head, "ref": "feature/editor", "repo": {"full_name": self.api.repository}}}
        self.api.live = [self.base]
        with patch.object(self.api, "json", return_value=pull):
            selected, proof = self.resolve(head=merge, policy=self.base, pull_number=88)
            self.assertTrue(selected.enabled)
            self.assertEqual(merge, selected.head_commit)
            self.assertNotEqual(run["head_sha"], selected.head_commit)
            pull["merge_commit_sha"] = self.head
            self.assertFalse(self.resolve(head=self.head, policy=self.base, pull_number=88)[0].enabled)
            pull["merge_commit_sha"] = merge
            pull["head"]["repo"]["full_name"] = "fork/repository"
            self.assertFalse(self.resolve(head=merge, policy=self.base, pull_number=88)[0].enabled)

    def test_evidence_revalidation_uses_immutable_baseline_identity_and_rejects_modified_selection(self):
        selected, proof = self.resolve()
        source = self.api.runs[66]
        source.update(status="completed", conclusion="success")
        self.api.job_lists[66] = [{"jobs": [self.api.job(job["name"], 60000 + index, source)
            for index, job in enumerate(self.api.job_lists[self.fixture.run_id][0]["jobs"])]}]
        selection_file = self.fixture.root / "selection.json"
        proof_file = self.fixture.root / "coverage.json"
        selection_file.write_bytes(selected.to_bytes())
        proof_file.write_bytes(coverage.admission.canonical(proof))
        with patch.object(self.api, "artifact", wraps=self.api.artifact) as get:
            verified, actual = consumer.verify(self.api, selection_file, proof_file,
                repository=self.repository, head=self.head, policy=self.head, run_id=66,
                directory=self.fixture.root / "verified")
            self.assertEqual(proof, actual)
            self.assertEqual(selected.sha256, verified.sha256)
            self.assertEqual({50000, *(item["id"] for item in self.baseline["public_artifacts"].values())},
                             {call.args[0] for call in get.call_args_list})
            proof["unchanged_module_fingerprints"]["hud-preview"] = "0" * 64
            proof_file.write_bytes(coverage.admission.canonical(proof))
            with self.assertRaises(coverage.CoverageError):
                consumer.verify(self.api, selection_file, proof_file, repository=self.repository,
                    head=self.head, policy=self.head, run_id=66, directory=self.fixture.root / "forged")
            proof_file.write_bytes(coverage.admission.canonical(actual))
            value = selected.to_dict()
            value["selection"]["runs"] = []
            selection_file.write_bytes(coverage.admission.canonical(value))
            with self.assertRaises(coverage.CoverageError):
                consumer.verify(self.api, selection_file, proof_file, repository=self.repository,
                    head=self.head, policy=self.head, run_id=66, directory=self.fixture.root / "omitted")
            self.api.runs[66]["conclusion"] = "failure"
            get.reset_mock()
            with self.assertRaises(coverage.CoverageError):
                consumer.verify(self.api, selection_file, proof_file, repository=self.repository,
                    head=self.head, policy=self.head, run_id=66, directory=self.fixture.root / "failed-source")
            get.assert_not_called()

    def test_baseline_owner_job_name_matches_the_workflow_and_read_requests_remain_exact(self):
        workflow = (ROOT / consumer.publisher.WORKFLOW).read_text()
        self.assertIn("name: " + consumer.ISSUER_JOB, workflow)
        api = consumer.publisher.Api(self.api.repository)
        response = {"total_count": 0, "artifacts": []}
        with patch.object(api, "json", return_value=response) as get:
            self.assertEqual([], api.artifacts(name=coverage.BASELINE_ARTIFACT_NAME))
            self.assertEqual("actions/artifacts?name=healthy-e2e-baseline&per_page=100", get.call_args.args[0])
            with self.assertRaises(coverage.CoverageError): api.artifacts(name="healthy-e2e-")
        with patch.object(api, "json", return_value={"id": 12}):
            self.assertEqual({"id": 12}, api.artifact(12))
            with self.assertRaises(coverage.CoverageError): api.artifact(13)


if __name__ == "__main__":
    unittest.main()
