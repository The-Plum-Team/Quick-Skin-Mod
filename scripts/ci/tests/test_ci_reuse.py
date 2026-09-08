from __future__ import annotations

import copy
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import ci_reuse as reuse
import feature_coverage_github as publisher
import feature_review as review
from matrix import load_matrix
from visual_review_targets import _inventory


class FixtureApi(publisher.Api):
    """GitHub transport fixture; source archives, job graphs and Git trees are real inputs."""
    def __init__(self, root, repository="owner/repo"):
        super().__init__(repository)
        self.root, self.downloaded, self.queries = root, [], []
        self.runs, self.inventories, self.job_lists, self.archives, self.commits = {}, {}, {}, {}, {}
        self.git("init", "-q")
        (root / "runtime.txt").write_text("base\n")
        self.git("add", "runtime.txt")
        self.base = self.commit("base")
        (root / "runtime.txt").write_text("feature\n")
        self.git("add", "runtime.txt")
        self.head = self.commit("feature", self.base)
        self.tested = self.commit("tested PR merge", self.base, self.head)
        self.covered = self.commit("final merged commit", self.base, self.head)
        self.git("update-ref", "HEAD", self.tested)
        self.tree = self.git("rev-parse", "HEAD^{tree}")
        self.live = self.covered
        self.pull = {"number": 7, "state": "closed", "merged": True,
            "merged_at": "2026-09-06T00:00:00Z", "merge_commit_sha": self.covered,
            "head": {"sha": self.head, "ref": "feature/hud", "repo": {"full_name": self.repository}},
            "base": {"sha": self.base, "ref": "master", "repo": {"full_name": self.repository}}}
        self.seals = {}
        for kind, identifier in (("build", 10), ("e2e", 20)):
            run = {"id": identifier, "event": "pull_request", "path": reuse.WORKFLOWS[kind],
                "head_sha": self.head, "head_branch": "feature/hud", "run_attempt": 1,
                "head_repository": {"full_name": self.repository}, "status": "completed", "conclusion": "success",
                "created_at": "2026-09-05T23:00:00Z", "pull_requests": []}
            self.runs[identifier] = run
            self.inventories[identifier] = []
            seal = {"schema_version": 1, "kind": "quick-skin-tested-source", "repository": self.repository,
                "workflow": reuse.WORKFLOWS[kind], "run_id": identifier, "run_attempt": 1,
                "head_sha": self.head, "head_branch": "feature/hud", "head_repository": self.repository,
                "tested_sha": self.tested, "tree_sha": self.tree, "pull_request": 7, "base_sha": self.base}
            self.seals[kind] = seal
            self.add_descriptor(identifier, 100 + identifier, "tested-source-" + kind, "tested-source.json", seal)
            if kind == "build":
                names = ["Build and verify", "Validate repository policy", "compile / Plan every supported build target",
                         "compile / Reverify the complete compiled matrix"]
                names += ["compile / Compile Minecraft " + version for version in
                          sorted({row["artifact_version"] for row in load_matrix(reuse.DEFAULT_MATRIX)["artifacts"]})]
                self.add_artifact(identifier, 200, "staged-release-bundle")
            else:
                self.add_artifact(identifier, 201, "e2e-input-bundle")
                names = [reuse.POLICY_JOB, reuse.BUILD_JOB, reuse.GATE_JOB,
                         *reuse.expected_scenario_jobs_for(reuse.DEFAULT_MATRIX, "pr-anchors")]
                _matrix, _digest, rows = _inventory(reuse.DEFAULT_MATRIX, "pr-anchors")
                for index, row in enumerate(rows):
                    self.add_artifact(identifier, 1000 + index, "packaged-e2e-" + row["id"])
            self.job_lists[identifier] = [{"jobs": [self.job(run, name, index) for index, name in enumerate(names)]}]

    def git(self, *arguments):
        environment = {**os.environ, "GIT_AUTHOR_NAME": "Fixture", "GIT_COMMITTER_NAME": "Fixture",
            "GIT_AUTHOR_EMAIL": "fixture@example.invalid", "GIT_COMMITTER_EMAIL": "fixture@example.invalid"}
        return subprocess.check_output(["git", *arguments], cwd=self.root, env=environment,
                                       stderr=subprocess.PIPE, text=True).strip()

    def commit(self, message, *parents):
        tree = self.git("write-tree")
        arguments = ["commit-tree", tree, "-m", message]
        for parent in parents: arguments.extend(("-p", parent))
        commit = self.git(*arguments)
        self.commits[commit] = {"sha": commit, "tree": {"sha": tree}, "parents": [{"sha": p} for p in parents]}
        return commit

    def job(self, run, name, identifier, conclusion="success"):
        return {"id": identifier + 1, "run_id": run["id"], "run_attempt": run["run_attempt"],
                "head_sha": run["head_sha"], "name": name, "status": "completed", "conclusion": conclusion}

    def add_artifact(self, run_id, identifier, name):
        run = self.runs[run_id]
        artifact = {"id": identifier, "name": name, "expired": False, "size_in_bytes": 1000,
            "digest": "sha256:" + "a" * 64, "created_at": "2026-09-06T00:00:00Z",
            "workflow_run": {key: run[key] for key in ("id", "head_sha", "head_branch")}}
        self.inventories[run_id].append(artifact)
        return artifact

    def add_descriptor(self, run_id, identifier, name, filename, value):
        artifact = self.add_artifact(run_id, identifier, name)
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(filename, reuse.canonical(value))
        self.archives[identifier] = output.getvalue()
        artifact.update(size_in_bytes=len(output.getvalue()), digest="sha256:" + hashlib.sha256(output.getvalue()).hexdigest())
        return artifact

    def json(self, endpoint):
        self.queries.append(endpoint)
        if endpoint == f"commits/{self.covered}/pulls?per_page=20": return [self.pull]
        if endpoint == "pulls/7": return self.pull
        if endpoint.startswith("git/commits/"): return self.commits[endpoint.rsplit("/", 1)[1]]
        if endpoint.startswith("actions/workflows/"):
            kind = "build" if "build-gate.yml" in endpoint else "e2e"
            identifier = 10 if kind == "build" else 20
            return {"total_count": 1, "workflow_runs": [self.runs[identifier]]}
        raise AssertionError("unexpected GitHub endpoint " + endpoint)

    def current_sha(self): return self.live
    def run(self, identifier): return self.runs[identifier]
    def jobs(self, run): return self.job_lists[run["id"]]
    def artifacts(self, *, run_id=None, name=None):
        if run_id is not None: return self.inventories[run_id]
        return [item for items in self.inventories.values() for item in items if item["name"] == name]
    def artifact(self, identifier):
        matches = [item for items in self.inventories.values() for item in items if item["id"] == identifier]
        if len(matches) != 1: raise ValueError("unknown or duplicate artifact")
        return matches[0]
    def download(self, metadata, destination, **kwargs):
        self.downloaded.append(metadata["id"])
        with patch.object(publisher, "_get", return_value=self.archives[metadata["id"]]):
            super().download(metadata, destination, **kwargs)

    def wrapper(self, reference, identifier=30):
        run = {**self.runs[20], "id": identifier, "head_sha": self.covered, "head_branch": "master",
               "event": "workflow_dispatch", "created_at": "2026-09-06T00:00:00Z"}
        self.runs[identifier], self.inventories[identifier] = run, []
        self.job_lists[identifier] = [{"jobs": [self.job(run, reuse.POLICY_JOB, 1), self.job(run, reuse.GATE_JOB, 2),
            self.job(run, reuse.BUILD_JOB, 3, "skipped"),
            self.job(run, "${{ matrix.id }}" + reuse.SCENARIO_SUFFIX, 4, "skipped")]}]
        self.add_descriptor(identifier, identifier * 10, "reused-source-e2e", "reused-source.json", reference)


class CiReuseTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.api = FixtureApi(Path(temporary.name).resolve())

    def find(self, kind="e2e"):
        return reuse.find_reference(self.api, self.api.covered, kind)

    def test_same_tree_distinct_commits_reuse_both_complete_gates_without_downloading_binaries_or_images(self):
        self.assertNotEqual(self.api.tested, self.api.covered)
        for kind in ("build", "e2e"):
            reference, reason = self.find(kind)
            self.assertEqual("identical-tested-tree", reason)
            self.assertEqual(self.api.tested, reference["source"]["tested_sha"])
            self.assertEqual(self.api.covered, reference["coverage_sha"])
        self.assertEqual({110, 120}, set(self.api.downloaded))

    def test_changed_merged_tree_requires_new_work(self):
        self.api.commits[self.api.covered]["tree"]["sha"] = "f" * 40
        self.assertEqual((None, "merged-tree-changed"), self.find())

    def test_failed_latest_run_cannot_reuse_prior_success(self):
        for result in ("failure", "cancelled", "skipped"):
            with self.subTest(result=result):
                self.api.runs[20]["conclusion"] = result
                self.assertEqual((None, "latest-pr-run-did-not-pass"), self.find())
        self.assertEqual([], self.api.downloaded)

    def test_one_missing_or_unsuccessful_original_lane_blocks_the_entire_attestation(self):
        jobs = self.api.job_lists[20][0]["jobs"]
        original = copy.deepcopy(jobs[-1])
        for result in ("failure", "cancelled", "skipped"):
            with self.subTest(result=result):
                jobs[-1]["conclusion"] = result
                with self.assertRaises(ValueError): self.find()
        jobs[-1] = original
        jobs.pop()
        with self.assertRaises(ValueError): self.find()

    def test_expired_seal_capture_or_selected_admission_requires_new_work(self):
        for name in ("tested-source-e2e", self.api.inventories[20][1]["name"], "e2e-feature-selection"):
            with self.subTest(name=name):
                if name == "e2e-feature-selection": self.api.add_artifact(20, 400, name)
                artifact = next(item for item in self.api.inventories[20] if item["name"] == name)
                artifact["expired"] = True
                self.assertEqual((None, "original-evidence-unavailable"), self.find())
                artifact["expired"] = False

    def test_api_failure_stops_instead_of_starting_an_expensive_replacement(self):
        with patch.object(self.api, "json", side_effect=ValueError("API unavailable")):
            with self.assertRaisesRegex(ValueError, "API unavailable"): self.find()
        self.assertEqual([], self.api.downloaded)

    def test_pending_pr_validation_stops_without_a_parallel_replacement(self):
        self.api.runs[20].update(status="in_progress", conclusion=None)
        with self.assertRaisesRegex(reuse.ReuseError, "still pending"): self.find()
        self.assertEqual([], self.api.downloaded)

    def test_public_provenance_preserves_original_commit_run_and_timestamp(self):
        import feature_pages as pages
        reference, _ = self.find()
        self.api.wrapper(reference)
        identity = pages.runtime_identity(self.api, repository=self.api.root, source_sha=self.api.covered,
                                          run_id=30, directory=self.api.root / "public")
        self.assertEqual(("20", self.api.tested, "feature/hud", "true"),
            tuple(identity[key] for key in ("source_run_id", "source_sha", "source_branch", "reused")))
        source = reuse.runtime_source(self.api, 30, self.api.covered)
        provenance = {key: {"run_id": str(run["id"]), "branch": run["head_branch"], "sha": commit,
            "run_url": f"https://github.com/{self.api.repository}/actions/runs/{run['id']}",
            "created_at": run["created_at"]}
            for key, run, commit in (("source", source.execution, source.tested_sha),
                                     ("target", source.generation, self.api.covered))}
        manifest = {"provenance": provenance, "runtime_source": reference}
        pages.verify_runtime_provenance(self.api, manifest, self.api.covered)
        for value in (self.api.covered, self.api.head):
            manifest["provenance"]["source"]["sha"] = value
            with self.assertRaisesRegex(ValueError, "provenance differs"):
                pages.verify_runtime_provenance(self.api, manifest, self.api.covered)

    def test_selected_consumer_authenticates_original_pr_after_merge(self):
        import feature_coverage_consumer as consumer
        reference, _ = self.find()
        with patch.object(consumer.coverage, "policy_fingerprint", return_value="a" * 64) as policy:
            consumer.authenticate_execution(self.api, self.api.root, run_id=20, tested_sha=self.api.tested,
                policy_sha=self.api.base, pull_number=7, merged_reference=reference)
            policy.assert_called_once_with(self.api.root, self.api.base, verify_executing=True)
            with self.assertRaises(ValueError):
                consumer.authenticate_execution(self.api, self.api.root, run_id=20, tested_sha=self.api.covered,
                    policy_sha=self.api.base, pull_number=7, merged_reference=reference)

    def test_foreign_closed_or_wrongly_merged_pr_cannot_supply_evidence(self):
        reference, _ = self.find()
        for mutation in ({"merged": False}, {"state": "open"}, {"merge_commit_sha": "f" * 40}):
            with self.subTest(mutation=mutation):
                before = copy.deepcopy(self.api.pull)
                self.api.pull.update(mutation)
                with self.assertRaises(reuse.ReuseError): reuse.verify_reference(self.api, reference, "e2e")
                self.api.pull = before

    def test_changed_attempt_or_tested_parents_cannot_reuse_an_old_record(self):
        reference, _ = self.find()
        self.api.runs[20]["run_attempt"] = 2
        with self.assertRaisesRegex(reuse.ReuseError, "stale"): reuse.verify_reference(self.api, reference, "e2e")
        self.api.runs[20]["run_attempt"] = 1
        self.api.commits[self.api.tested]["parents"].reverse()
        with self.assertRaisesRegex(reuse.ReuseError, "tested merge"): reuse.verify_reference(self.api, reference, "e2e")

    def test_reference_cannot_substitute_the_tested_commit_or_original_artifact(self):
        original, _ = self.find()
        for field, value in (("tested_sha", self.api.covered), ("run_id", True), ("head_sha", "f" * 40)):
            with self.subTest(field=field):
                reference = copy.deepcopy(original)
                reference["source"][field] = value
                with self.assertRaises(ValueError): reuse.verify_reference(self.api, reference, "e2e")
        original["seal_artifact"]["digest"] = "sha256:" + "f" * 64
        with self.assertRaises(ValueError): reuse.verify_reference(self.api, original, "e2e")

    def test_wrapper_keeps_original_artifacts_commit_and_all_32_executed_jobs(self):
        reference, _ = self.find()
        self.api.wrapper(reference)
        source = reuse.runtime_source(self.api, 30, self.api.covered)
        self.assertEqual(20, source.execution["id"])
        self.assertEqual(30, source.generation["id"])
        self.assertEqual(self.api.tested, source.tested_sha)
        self.assertEqual(32, len(source.graph["observed_scenario_jobs"]))
        self.assertTrue(all(item["workflow_run"]["id"] == 20 for item in source.artifacts))
        self.assertEqual(reference, source.reference)
        self.assertEqual({120, 300}, set(self.api.downloaded))

    def test_wrapper_cannot_mix_reused_and_new_runtime_results(self):
        reference, _ = self.find()
        self.api.wrapper(reference)
        self.api.job_lists[30][0]["jobs"][-1]["conclusion"] = "success"
        with self.assertRaisesRegex(reuse.ReuseError, "mixed"): reuse.runtime_source(self.api, 30, self.api.covered)

    def test_advisory_pages_accepts_queued_wrapper_only_after_required_gates_pass(self):
        reference, _ = self.find()
        self.api.wrapper(reference)
        for status in ("queued", "in_progress"):
            with self.subTest(status=status):
                self.api.runs[30].update(status=status, conclusion=None)
                source = reuse.runtime_source(self.api, 30, self.api.covered, allow_in_progress=True)
                self.assertEqual(20, source.execution["id"])
                with self.assertRaises(reuse.ReuseError):
                    reuse.runtime_source(self.api, 30, self.api.covered)
        self.api.job_lists[30][0]["jobs"][1].update(status="queued", conclusion=None)
        with self.assertRaisesRegex(reuse.ReuseError, "required source job did not pass"):
            reuse.runtime_source(self.api, 30, self.api.covered, allow_in_progress=True)

    def test_source_references_never_chain(self):
        self.api.add_artifact(20, 999, "reused-source-e2e")
        with self.assertRaisesRegex(reuse.ReuseError, "chain"): self.find()

    def test_original_seal_records_actual_merge_even_in_a_shallow_checkout(self):
        (self.api.root / ".git/shallow").write_text(self.api.tested + "\n")
        environment = {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_SHA": self.api.tested,
            "GITHUB_REPOSITORY": self.api.repository, "GITHUB_RUN_ID": "20", "GITHUB_RUN_ATTEMPT": "1"}
        seal = reuse.seal_environment(environment, {"number": 7, "pull_request": self.api.pull}, "e2e", self.api.root)
        self.assertEqual(self.api.seals["e2e"], seal)

    def test_repeated_producer_wakes_skip_only_targets_already_queued_or_reviewed(self):
        reference, _ = self.find()
        self.api.wrapper(reference)
        source = reuse.runtime_source(self.api, 30, self.api.covered)
        targets = review.plan_source(source)["include"]
        for index, (prefix, workflow, event) in enumerate((
            (f"visual-review-input-30-{self.api.covered}", ".github/workflows/visual-review.yml", "workflow_run"),
            ("visual-review-30", ".github/workflows/visual-review-drain.yml", "repository_dispatch"),
        )):
            owner_id = 600 + index
            self.api.runs[owner_id] = {**self.api.runs[30], "id": owner_id, "path": workflow, "event": event}
            self.api.inventories[owner_id] = []
            self.api.add_artifact(owner_id, 700 + index, prefix + "--" + targets[index]["bundle_key"])
        pending = review.pending_plan(self.api, source, "pr-anchors")["include"]
        self.assertEqual([target["bundle_key"] for target in targets[2:]], [target["bundle_key"] for target in pending])


if __name__ == "__main__":
    unittest.main()
