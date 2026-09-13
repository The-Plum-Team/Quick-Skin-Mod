from __future__ import annotations

import copy
import hashlib
import shutil
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from publication_fixture import bundle
import publication_state as ledger
import verify_pending_publications as pending
from matrix import gha_matrix, select_release_target


class PendingPublicationsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.stage = Path(self.temporary.name)
        matrix, manifest = bundle(self.stage)
        self.matrix = select_release_target(matrix, "26.1")
        self.version = manifest["mod_version"]
        self.state = ledger.new_state(self.stage / "artifacts.json", matrix, producer_sha="a" * 40,
                                      run_id=123, artifact={"id": 456, "digest": "sha256:" + "b" * 64})
        self.api = Mock(repository="owner/repo")
        self.run = {"id": 123, "path": ".github/workflows/release.yml", "event": "push",
                    "head_branch": self.state["tag"], "head_sha": "a" * 40,
                    "repository": {"full_name": "owner/repo"}, "head_repository": {"full_name": "owner/repo"},
                    "status": "completed", "run_attempt": 2, "conclusion": "failure"}
        steps = ("Validate requested release identity", "Stage and verify all production artifacts",
                 "Prove first and second build bytes are identical", "Rehearse publication and interrupted recovery")
        self.jobs = [self.job("Build immutable release bundle", steps),
                     self.job("Stage exact GitHub Release assets", ("Persist resumable publication identity",))]
        self.jobs.extend(self.job(f"{row['id']} - packaged release behavior")
                         for row in gha_matrix(self.matrix, "runtime", self.version)["include"])
        for i, job in enumerate(self.jobs, start=1):
            job["id"] = i
        self.api.json.side_effect = lambda endpoint: {"total_count": len(self.jobs), "jobs": self.jobs}
        self.artifact = {"id": 456, "name": "release-" + self.state["tag"], "digest": self.state["artifact_digest"],
                         "expired": False, "size_in_bytes": 1000,
                         "workflow_run": {"id": 123, "head_sha": "a" * 40}}

    def tearDown(self):
        self.temporary.cleanup()

    def job(self, name, steps=()):
        return {"id": 1, "run_id": 123, "run_attempt": 1, "head_sha": "a" * 40,
                "name": name, "status": "completed", "conclusion": "success",
                "steps": [{"name": name, "conclusion": "success"} for name in steps]}

    def test_failed_upload_can_resume_using_successful_reused_preparation_jobs(self):
        pending.authenticate_run(self.api, self.state, self.run, self.matrix, self.version)
        self.assertIn("filter=latest", self.api.json.call_args.args[0])
        pending.authenticate_artifact(self.state, self.run, self.artifact)

    def test_pr_dispatch_fork_and_foreign_source_cannot_authorize_publication(self):
        for key, value in (("event", "pull_request"), ("event", "workflow_dispatch"),
                           ("path", ".github/workflows/build-matrix.yml"), ("head_sha", "b" * 40),
                           ("head_branch", "master"), ("head_repository", {"full_name": "fork/repo"}),
                           ("repository", {"full_name": "other/repo"}), ("status", "in_progress")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                pending.authenticate_run(self.api, self.state, {**self.run, key: value}, self.matrix, self.version)

    def test_missing_runtime_rehearsal_or_approved_ledger_step_fails_closed(self):
        for index in range(len(self.jobs)):
            jobs = copy.deepcopy(self.jobs)
            jobs[index]["conclusion"] = "skipped"
            with self.subTest(index=index), patch.object(pending, "latest_jobs", return_value=jobs):
                with self.assertRaises(ValueError):
                    pending.authenticate_run(self.api, self.state, self.run, self.matrix, self.version)
        for index in (0, 1):
            jobs = copy.deepcopy(self.jobs)
            jobs[index]["steps"].pop()
            with patch.object(pending, "latest_jobs", return_value=jobs), self.assertRaises(ValueError):
                pending.authenticate_run(self.api, self.state, self.run, self.matrix, self.version)

    def test_truncated_duplicate_foreign_or_future_jobs_fail_closed(self):
        samples = [{"total_count": 1001, "jobs": self.jobs},
                   {"total_count": len(self.jobs) + 1, "jobs": self.jobs},
                   {"total_count": 2, "jobs": [self.jobs[0], self.jobs[0]]}]
        for key, value in (("run_id", 999), ("run_attempt", 3), ("head_sha", "b" * 40)):
            samples.append({"total_count": 1, "jobs": [{**self.jobs[0], key: value}]})
        for record in samples:
            with patch.object(self.api, "json", return_value=record), self.assertRaises(ValueError):
                pending.latest_jobs(self.api, self.run)

    def test_expired_replaced_oversized_or_foreign_archive_cannot_be_used(self):
        for key, value in (("expired", True), ("id", 789), ("digest", "sha256:" + "c" * 64),
                           ("name", "release-other"), ("size_in_bytes", True),
                           ("size_in_bytes", pending.MAX_ARCHIVE_BYTES + 1),
                           ("workflow_run", {"id": 999, "head_sha": "a" * 40})):
            with self.subTest(key=key), self.assertRaises(ValueError):
                pending.authenticate_artifact(self.state, self.run, {**self.artifact, key: value})

    def test_release_download_has_its_own_bound_and_verifies_size_and_digest_before_writing(self):
        raw = b"x" * (5 * 1024 * 1024)
        artifact = {**self.artifact, "size_in_bytes": len(raw),
                    "digest": "sha256:" + hashlib.sha256(raw).hexdigest()}
        self.api.prefix = "repos/owner/repo/"
        output = self.stage / "release.zip"
        with patch.object(pending, "_get", return_value=raw) as get:
            pending.download_archive(self.api, artifact, output)
            get.assert_called_once_with("repos/owner/repo/actions/artifacts/456/zip", maximum=64 * 1024 * 1024)
        self.assertEqual(output.read_bytes(), raw)
        for changed in ({**artifact, "size_in_bytes": len(raw) + 1},
                        {**artifact, "digest": "sha256:" + "0" * 64}):
            with patch.object(pending, "_get", return_value=raw), self.assertRaises(ValueError):
                pending.download_archive(self.api, changed, self.stage / "untrusted.zip")
        self.assertFalse((self.stage / "untrusted.zip").exists())

    def test_discovery_ignores_historical_releases_and_retains_only_recorded_drafts(self):
        body = ledger.encode("Notes", self.state)
        releases = [{"id": 1, "draft": False, "body": body, "tag_name": self.state["tag"]},
                    {"id": 2, "draft": True, "body": "legacy draft", "tag_name": "legacy"},
                    {"id": 3, "draft": True, "body": body, "tag_name": self.state["tag"]}]
        with patch.object(self.api, "json", return_value=releases):
            self.assertEqual(pending.pending_tags(self.api), [self.state["tag"]])
        releases[-1]["tag_name"] = "mc26.2-v3.0.0"
        with patch.object(self.api, "json", return_value=releases), self.assertRaises(ValueError):
            pending.pending_tags(self.api)

    def test_active_source_does_not_download_or_mutate_a_release(self):
        release = {"id": 1, "tag_name": self.state["tag"], "draft": True,
                   "body": ledger.encode("", self.state)}
        self.api.run.return_value = {**self.run, "status": "in_progress"}
        with patch.object(pending, "read_release", return_value=release), patch.object(pending, "save") as write:
            self.assertFalse(pending.verify(self.api, self.state["tag"], self.stage, "a" * 40, finalize=True))
            self.api.download.assert_not_called()
            write.assert_not_called()

    def test_recovery_requires_original_authenticated_evidence(self):
        run = {**self.run, "path": ".github/workflows/release-recovery.yml",
               "event": "workflow_dispatch", "head_branch": "master"}
        jobs = [self.jobs[1], self.job("Authenticate and repair the original release SBOM",
                 ("Authenticate original source, full release E2E, archive and tag provenance",))]
        with patch.object(pending, "latest_jobs", return_value=jobs):
            pending.authenticate_run(self.api, self.state, run, self.matrix, self.version)
            jobs[-1]["steps"] = []
            with self.assertRaises(ValueError):
                pending.authenticate_run(self.api, self.state, run, self.matrix, self.version)

    def test_remote_moved_tag_is_rejected_even_if_the_local_tag_matches(self):
        self.api.json.side_effect = None
        self.api.json.return_value = {"ref": "refs/tags/" + self.state["tag"],
                                      "object": {"type": "commit", "sha": "b" * 40}}
        with patch.object(pending, "git", return_value="a" * 40), self.assertRaisesRegex(ValueError, "immutable release tag"):
            pending.authenticate_tag(self.api, self.state, "a" * 40)

    def test_observation_cannot_publish_and_finalization_rechecks_current_policy(self):
        release = {"id": 1, "tag_name": self.state["tag"], "draft": True,
                   "body": ledger.encode("", self.state)}
        self.api.run.return_value = self.run
        self.api.artifact.return_value = self.artifact
        complete = copy.deepcopy(self.state)
        for i, row in enumerate(complete["rows"].values(), start=1):
            row.update(state="verified", remote_id=str(i))
        for finalize, checked, policy, expected_writes in (
            (False, complete, "a" * 40, 0), (True, self.state, "a" * 40, 0),
            (True, complete, "b" * 40, 0), (True, complete, "a" * 40, 1),
        ):
            self.api.current_sha.return_value = policy
            with tempfile.TemporaryDirectory() as temporary, \
                    patch.object(pending, "read_release", return_value=release), \
                    patch.object(pending, "authenticate_tag"), \
                    patch.object(pending, "snapshot_inputs", return_value=(self.matrix, SimpleNamespace(mod_version=self.version))), \
                    patch.object(pending, "download_archive"), \
                    patch.object(pending, "extract_bounded_zip", side_effect=lambda archive, destination, limits:
                                 shutil.copytree(self.stage, destination)), \
                    patch.object(pending, "verify_staged_manifest") as staged, \
                    patch.object(pending, "check_all", return_value=checked), \
                    patch.object(pending, "save") as save, patch.object(pending, "publish_release") as publish:
                if finalize and ledger.ready(checked) and policy != "a" * 40:
                    with self.assertRaisesRegex(ValueError, "implementation advanced"):
                        pending.verify(self.api, self.state["tag"], Path(temporary), "a" * 40, finalize=finalize)
                else:
                    self.assertEqual(pending.verify(self.api, self.state["tag"], Path(temporary), "a" * 40,
                                                    finalize=finalize), ledger.ready(checked))
                staged.assert_called_once()
                self.assertEqual(save.call_count, expected_writes)
                self.assertEqual(publish.call_count, expected_writes)
                if expected_writes:
                    self.assertEqual(publish.call_args.args[1].commit, self.state["source_sha"])


if __name__ == "__main__":
    unittest.main()
