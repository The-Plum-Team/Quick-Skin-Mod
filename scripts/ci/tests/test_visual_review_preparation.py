from __future__ import annotations

import copy
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "e2e"))

from visual_review_preparation import PREPARE_JOB, WORKFLOW, restore
from visual_review_completed import cache_owner_complete, recover
from visual_review_cache import cached_verdicts, combine_caches, merge_cache, validate_cache
from visual_review_runner import execute_review
from scripts.release.tests.test_visual_review_cache import paired, verdict, POLICY
from scripts.ci.tests.test_workflow_security import job_block, step_script
from evidence_target import inventory

SHA = "a" * 40


def archive(files):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as output:
        for name, value in files.items():
            output.writestr(name, value)
    return stream.getvalue()


class ApiFixture:
    repository = "example/repository"

    def __init__(self, raw, name, *, status="in_progress", conclusion=None):
        self.raw = raw
        self.owner = {"id": 10, "run_attempt": 1, "head_sha": SHA, "head_branch": "master",
                      "path": WORKFLOW, "event": "repository_dispatch", "status": status,
                      "conclusion": conclusion, "head_repository": {"full_name": self.repository}}
        self.metadata = {"id": 20, "name": name, "expired": False, "size_in_bytes": len(raw),
                         "digest": "sha256:" + hashlib.sha256(raw).hexdigest(),
                         "created_at": "2026-09-13T10:00:01Z",
                         "workflow_run": {"id": 10, "head_sha": SHA, "head_branch": "master"}}
        self.job_list = [{"name": PREPARE_JOB, "status": "completed", "conclusion": "success"}]
        self.downloads = 0

    def run(self, identifier):
        assert identifier == 10
        return self.owner

    def jobs(self, owner):
        return [{"jobs": self.job_list}]

    def artifact(self, identifier):
        assert identifier == 20
        return self.metadata

    def artifacts(self, *, name):
        assert name == self.metadata["name"]
        return [copy.deepcopy(self.metadata)]

    def prepared_bytes(self, identifier, *, maximum):
        assert identifier == 20 and len(self.raw) <= maximum
        self.downloads += 1
        return self.raw

    def download(self, metadata, destination):
        self.downloads += 1
        if len(self.raw) != metadata["size_in_bytes"] or "sha256:" + hashlib.sha256(self.raw).hexdigest() != metadata["digest"]:
            raise ValueError("archive digest mismatch")
        destination.write_bytes(self.raw)


class VisualReviewPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.inner = archive({"curation-proof.json": "{}", "review-input/visual-review-manifest.json": "[]"})
        self.outer = archive({"prepared-visual-review.zip": self.inner})
        self.api = ApiFixture(self.outer, "visual-review-prepared-10-1-30")
        self.args = dict(run_id=10, run_attempt=1, workflow_sha=SHA, artifact_id=20,
                         artifact_digest=hashlib.sha256(self.outer).hexdigest(), capsule_id=30,
                         capsule_digest=hashlib.sha256(self.inner).hexdigest(), capsule_size=len(self.inner),
                         output=self.folder / "restored")

    def test_exact_preparation_preserves_original_capsule_bytes(self):
        restore(self.api, **self.args)
        self.assertEqual("{}", (self.args["output"] / "curation-proof.json").read_text())
        self.assertEqual(1, self.api.downloads)

    def test_foreign_head_attempt_slot_and_failed_preparation_never_download(self):
        for key, value in (("head_sha", "b" * 40), ("run_attempt", 2), ("path", "other")):
            with self.subTest(key=key):
                original = copy.deepcopy(self.api.owner)
                self.api.owner[key] = value
                with self.assertRaises(ValueError):
                    restore(self.api, **self.args)
                self.api.owner = original
        for status in ("failure", "cancelled", "in_progress"):
            self.api.job_list[0]["conclusion"] = status
            with self.assertRaises(ValueError):
                restore(self.api, **self.args)
        self.assertEqual(0, self.api.downloads)

    def test_prepared_wrapper_and_original_capsule_digests_are_independent(self):
        self.api.raw += b"changed"
        with self.assertRaisesRegex(ValueError, "immutable metadata"):
            restore(self.api, **self.args)
        self.api.raw = self.outer
        with self.assertRaisesRegex(ValueError, "original immutable capsule"):
            restore(self.api, **{**self.args, "capsule_digest": "f" * 64})
        self.assertFalse(self.args["output"].exists())

    def test_prepared_archive_rejects_traversal_extra_files_and_existing_destination(self):
        for files in ({"../escape": b"x"}, {"other.zip": self.inner},
                      {"prepared-visual-review.zip": self.inner, "extra": b"x"}):
            raw = archive(files)
            api = ApiFixture(raw, "visual-review-prepared-10-1-30")
            with self.assertRaises(ValueError):
                restore(api, **{**self.args, "artifact_digest": hashlib.sha256(raw).hexdigest()})
        self.args["output"].mkdir()
        with self.assertRaisesRegex(ValueError, "already exists"):
            restore(self.api, **self.args)
        self.assertFalse((self.folder / "escape").exists())

    def test_actual_workflow_keeps_two_secretless_slots_and_one_aggregate_model_owner(self):
        prepare = job_block("visual-review-drain.yml", "prepare")
        review = job_block("visual-review-drain.yml", "review")
        slot = step_script("visual-review-drain.yml", "select", "Bound secretless preparation to two slots")
        self.assertIn("% 2", slot)
        self.assertIn("queue: max", prepare)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", prepare)
        self.assertNotIn("visual_review_runner.py", prepare)
        self.assertIn("group: quick-skin-visual-review-model\n", review)
        self.assertIn("--max-parallel-calls 32", review)
        self.assertIn("needs.prepare.result == 'success'", review)
        self.assertLess(review.index("Restore the exact authenticated preparation"), review.index("Restore only the authenticated exact-policy verdict cache"))
        self.assertLess(review.index("Upload the source-bound normalized report"), review.index("Publish the protected exact-policy verdict cache"))
        self.assertLess(review.index("Upload the protected exact-policy verdict cache"), review.index("Retire the consumed exact-policy verdict cache shards"))
        retirement = review.split("      - name: Retire the consumed exact-policy verdict cache shards", 1)[1].split("      - name:", 1)[0]
        self.assertIn("steps.verdict-cache-artifact.outputs.artifact-id != ''", retirement)
        self.assertNotIn("--method DELETE", prepare)
        model = step_script("visual-review-drain.yml", "review", "Review bounded chunks with selective escalation")
        self.assertLess(model.index('if [[ "$RECOVERED" == true ]]'), model.index('test -n "$CLAUDE_CODE_OAUTH_TOKEN"'))

    def test_preparation_authenticates_protected_history_before_dependencies_or_capsules(self):
        prepare = job_block("visual-review-drain.yml", "prepare")
        checkout = prepare.split("      - name: Check out the protected preparation policy\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("ref: ${{ github.sha }}", checkout)
        self.assertNotIn("needs.select.outputs.implementation_sha", checkout)
        self.assertIn("persist-credentials: false", checkout)
        self.assertEqual(1, prepare.count("uses: actions/checkout@"))
        guard = "Authenticate the selected historical preparation policy"
        for later in ("Install Python", "Install hash-locked image decoder", "Fetch and verify the exact curated capsule"):
            self.assertLess(prepare.index(guard), prepare.index(later))
        self.assertNotIn("if: always()", prepare.split("      - name: " + guard, 1)[1])
        script = step_script("visual-review-drain.yml", "prepare", guard)
        self.assertIn('[[ "$(git rev-parse HEAD)" == "$GITHUB_SHA" ]]', script)
        self.assertIn('--is-ancestor "$IMPLEMENTATION_SHA" "$GITHUB_SHA" || exit 1', script)
        self.assertIn('checkout --detach "$IMPLEMENTATION_SHA"', script)
        self.assertLess(script.index(".head_repository.full_name == $repository"), script.index("checkout --detach"))
        self.assertNotIn("git fetch", script)

    def policy_git(self, repository, *args):
        return subprocess.check_output(
            ["git", "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
             "-c", "core.hooksPath=" + os.devnull, *args], cwd=repository,
            env={**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"},
            stderr=subprocess.DEVNULL, text=True, timeout=10,
        ).strip()

    def policy_history(self, *, controls=False):
        repository = self.folder / "policy"
        repository.mkdir()
        self.policy_git(repository, "init", "-q")
        (repository / "scripts/ci").mkdir(parents=True)
        shutil.copy2(ROOT / "scripts/ci/github_api_retry.sh", repository / "scripts/ci/github_api_retry.sh")
        (repository / "policy.txt").write_text("historical protected policy\n")
        self.policy_git(repository, "add", ".")
        self.policy_git(repository, "commit", "-qm", "historical")
        historical = self.policy_git(repository, "rev-parse", "HEAD")
        if controls:
            for name in ("scripts", "e2e", "release", "architecture"):
                shutil.copytree(ROOT / name, repository / name, dirs_exist_ok=True,
                                ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        (repository / "policy.txt").write_text("current protected workflow\n")
        self.policy_git(repository, "add", ".")
        self.policy_git(repository, "commit", "-qm", "current")
        current = self.policy_git(repository, "rev-parse", "HEAD")
        self.policy_git(repository, "checkout", "-q", "--detach", historical)
        (repository / "policy.txt").write_text("unrelated sibling policy\n")
        self.policy_git(repository, "commit", "-qam", "sibling")
        sibling = self.policy_git(repository, "rev-parse", "HEAD")
        self.policy_git(repository, "checkout", "-q", "--detach", current)
        return repository, historical, current, sibling

    def run_policy_guard(self, repository, implementation, workflow_sha, *, changes=None, metadata_changes=None, owner_changes=None):
        script = step_script("visual-review-drain.yml", "prepare", "Authenticate the selected historical preparation policy")
        runtime = self.folder / "runtime"
        runtime.mkdir(exist_ok=True)
        metadata = {"id": 30, "name": f"visual-review-input-100-{workflow_sha}", "size_in_bytes": 100,
                    "expired": False, "digest": "sha256:" + "a" * 64,
                    "workflow_run": {"id": 10, "head_sha": implementation, "head_branch": "master"}}
        owner = {"id": 10, "status": "completed", "conclusion": "success", "event": "repository_dispatch",
                 "head_branch": "master", "head_sha": implementation, "path": ".github/workflows/visual-review.yml",
                 "head_repository": {"full_name": "example/repository"}}
        metadata.update(metadata_changes or {})
        owner.update(owner_changes or {})
        mock = '''gh() {
          printf '%s\\n' "$*" >> "$RUNNER_TEMP/api-reads"
          case "$*" in
            *actions/artifacts/30*) printf '%s\\n' "$FIXTURE_METADATA" ;;
            *actions/runs/10*) printf '%s\\n' "$FIXTURE_OWNER" ;;
            *) return 97 ;;
          esac
        }
        '''
        env = {**os.environ, "GITHUB_SHA": workflow_sha, "GITHUB_REPOSITORY": "example/repository",
               "RUNNER_TEMP": str(runtime), "GITHUB_RUN_ID": "20", "GH_TOKEN": "fixture",
               "ARTIFACT_ID": "30", "ARTIFACT_RUN_ID": "10", "SOURCE_RUN_ID": "100",
               "ARTIFACT_NAME": metadata["name"], "ARTIFACT_SIZE": "100", "ARTIFACT_DIGEST": "a" * 64,
               "IMPLEMENTATION_SHA": implementation, "GENERATION_SHA": workflow_sha, "BUNDLE_KEY": "",
               "FIXTURE_METADATA": json.dumps(metadata), "FIXTURE_OWNER": json.dumps(owner), **(changes or {})}
        return subprocess.run(["bash", "-c", mock + script + "\nprintf 'preparation-admitted\\n'\n"],
                              cwd=repository, env=env, capture_output=True, text=True, timeout=10)

    def test_preparation_guard_accepts_authenticated_ancestor_but_not_newer_or_sibling_policy(self):
        repository, historical, current, sibling = self.policy_history()
        for implementation, workflow_sha, accepted in ((historical, current, True), (current, current, True),
                                                       (current, historical, False), (sibling, current, False)):
            with self.subTest(implementation=implementation, workflow_sha=workflow_sha):
                self.policy_git(repository, "checkout", "-q", "--detach", workflow_sha)
                result = self.run_policy_guard(repository, implementation, workflow_sha)
                self.assertEqual(accepted, result.returncode == 0, result.stderr)
                self.assertEqual(accepted, "preparation-admitted" in result.stdout)
                self.assertEqual(implementation if accepted else workflow_sha,
                                 self.policy_git(repository, "rev-parse", "HEAD"))

    def test_preparation_guard_rejects_hostile_selector_values_before_api_or_checkout(self):
        repository, historical, current, _sibling = self.policy_history()
        cases = {"ARTIFACT_ID": "0", "ARTIFACT_RUN_ID": "../10", "SOURCE_RUN_ID": "-1",
                 "ARTIFACT_SIZE": "99999999999999999999999", "ARTIFACT_DIGEST": "wrong",
                 "IMPLEMENTATION_SHA": "$(exit 77)", "GENERATION_SHA": "refs/heads/master",
                 "ARTIFACT_NAME": "visual-review-input-foreign", "BUNDLE_KEY": "../mc1.20.1"}
        for name, value in cases.items():
            with self.subTest(name=name):
                result = self.run_policy_guard(repository, historical, current, changes={name: value})
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.folder / "runtime/api-reads").exists())
                self.assertEqual(current, self.policy_git(repository, "rev-parse", "HEAD"))

    def test_preparation_guard_rejects_foreign_immutable_artifact_or_protected_owner(self):
        repository, historical, current, _sibling = self.policy_history()
        for changes in ({"id": 31}, {"digest": "sha256:" + "b" * 64}, {"expired": True},
                        {"size_in_bytes": 101}, {"workflow_run": {"id": 11, "head_sha": historical, "head_branch": "master"}}):
            with self.subTest(metadata=changes):
                self.assertNotEqual(0, self.run_policy_guard(repository, historical, current, metadata_changes=changes).returncode)
                self.assertEqual(current, self.policy_git(repository, "rev-parse", "HEAD"))
        for changes in ({"head_sha": current}, {"head_branch": "topic"}, {"event": "pull_request"},
                        {"path": ".github/workflows/other.yml"}, {"status": "in_progress"},
                        {"conclusion": "cancelled"}, {"head_repository": {"full_name": "foreign/repository"}}):
            with self.subTest(owner=changes):
                self.assertNotEqual(0, self.run_policy_guard(repository, historical, current, owner_changes=changes).returncode)
                self.assertEqual(current, self.policy_git(repository, "rev-parse", "HEAD"))

    def test_current_control_helpers_survive_an_exact_old_checkout_without_those_files(self):
        repository, historical, current, _sibling = self.policy_history(controls=True)
        runtime = self.folder / "control-runtime"
        runtime.mkdir()
        script = step_script("visual-review-drain.yml", "review", "Retain this workflow's protected drain control helpers")
        result = subprocess.run(["bash", "-c", script], cwd=repository,
                                env={**os.environ, "GITHUB_SHA": current, "RUNNER_TEMP": str(runtime)},
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.policy_git(repository, "checkout", "-q", "--detach", historical)
        self.assertFalse((repository / "scripts/ci/visual_review_preparation.py").exists())
        self.assertFalse((repository / "scripts/ci/visual_review_completed.py").exists())
        for helper in ("visual_review_preparation.py", "visual_review_completed.py"):
            retained = runtime / "protected-drain-control/scripts/ci" / helper
            self.assertEqual((ROOT / "scripts/ci" / helper).read_bytes(), retained.read_bytes())
            help_result = subprocess.run([sys.executable, str(retained), "--help"], cwd=repository,
                                         env={key: value for key, value in os.environ.items() if key != "PYTHONPATH"},
                                         capture_output=True, text=True, timeout=10)
            self.assertEqual(0, help_result.returncode, help_result.stderr)
        review = job_block("visual-review-drain.yml", "review")
        self.assertLess(review.index("Retain this workflow's protected drain control helpers"),
                        review.index("Check out the exact protected reviewer"))
        self.assertEqual(2, review.count('$RUNNER_TEMP/protected-drain-control/scripts/ci/visual_review_completed.py'))
        self.assertIn('--workflow-sha "$GITHUB_SHA"', review)

    def test_wrapper_workflow_identity_is_separate_from_original_curator_policy(self):
        historical = "b" * 40
        inner = archive({"curation-proof.json": json.dumps({"implementation_sha": historical}),
                         "review-input/visual-review-manifest.json": "[]"})
        outer = archive({"prepared-visual-review.zip": inner})
        api = ApiFixture(outer, "visual-review-prepared-10-1-30")
        args = {**self.args, "artifact_digest": hashlib.sha256(outer).hexdigest(),
                "capsule_digest": hashlib.sha256(inner).hexdigest(), "capsule_size": len(inner)}
        restore(api, **args)
        self.assertEqual(historical, json.loads((args["output"] / "curation-proof.json").read_text())["implementation_sha"])
        self.assertEqual(1, api.downloads)


class CompletedReviewRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.capsule = Path(self.temporary.name)
        (self.capsule / "review-input").mkdir()
        self.manifest = [paired("fabric-1.21.1/scenario/client/frame")]
        self.proof = {"review_mode": "reference-comparison", "implementation_sha": SHA}
        self.completion = {"schema_version": 1, "state": "complete", "manifest_frames": 1, "report_verdicts": 1}
        self.files = {"curation-proof.json": json.dumps(self.proof),
                      "review-input/visual-review-manifest.json": json.dumps(self.manifest),
                      "visual-review-report.json": json.dumps([verdict(self.manifest[0]["label"])]),
                      "visual-review-completion.json": json.dumps(self.completion)}
        for name in ("curation-proof.json", "review-input/visual-review-manifest.json"):
            (self.capsule / name).write_text(self.files[name])
        self.api = ApiFixture(archive(self.files), "visual-review-100--mc1.21.1", status="completed", conclusion="cancelled")
        steps = [{"name": name, "status": "completed", "conclusion": "success",
                  "started_at": "2026-09-13T10:00:00Z", "completed_at": "2026-09-13T10:00:02Z"}
                 for name in ("Independently validate the normalized result", "Upload the source-bound normalized report")]
        self.api.job_list = [{"name": "Review one queued capsule", "status": "completed", "conclusion": "cancelled", "steps": steps}]

    def recover(self, *, workflow_sha=SHA):
        return recover(self.api, capsule=self.capsule, implementation_sha=SHA,
                       workflow_sha=workflow_sha, review_key="100--mc1.21.1")

    def replace_archive(self):
        self.api.raw = archive(self.files)
        self.api.metadata.update(size_in_bytes=len(self.api.raw), digest="sha256:" + hashlib.sha256(self.api.raw).hexdigest())

    def test_cancellation_during_cache_publication_recovers_without_provider(self):
        # Execute the real later cleanup first: its successful no-op must preserve the input
        # even if the owner is cancelled after leaving the model/cache job and cleanup itself.
        cleanup = step_script("visual-review-drain.yml", "cleanup",
                              "Reauthenticate and delete only the selected queue artifact")
        completed = subprocess.run(["bash", "-c", "gh() { exit 97; }\n" + cleanup],
            cwd=self.capsule, env={"PATH": os.defpath, "FRESH_REVIEW_COMPLETE": "true"},
            capture_output=True, text=True, timeout=10)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertTrue((self.capsule / "curation-proof.json").is_file())
        self.assertTrue(self.recover())
        result = json.loads((self.capsule / "visual-review-report.staged.json").read_text())
        cache = merge_cache(None, self.manifest, result, policy_sha256=POLICY, review_mode="reference-comparison")
        def unavailable(*args, **kwargs):
            raise AssertionError("provider capacity must not be needed for admitted completed results")
        recovered, counts = execute_review(self.manifest, unavailable,
            cache_hits=cached_verdicts(self.manifest, cache, review_mode="reference-comparison"))
        self.assertEqual(result, recovered)
        self.assertEqual(1, counts["cached"])

    def test_real_model_step_recovers_with_no_provider_token_or_executable(self):
        self.assertTrue(self.recover())
        output = self.capsule / "step-output"
        script = step_script("visual-review-drain.yml", "review", "Review bounded chunks with selective escalation")
        completed = subprocess.run(["bash", "-c", script], cwd=self.capsule,
            env={"PATH": os.environ["PATH"], "RECOVERED": "true", "GITHUB_OUTPUT": str(output)},
            capture_output=True, text=True, timeout=10)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("model_status=0\n", output.read_text())
        self.assertIn("model calls=0", completed.stdout)

    def test_current_workflow_report_for_historical_capsule_recovers_without_provider(self):
        workflow_sha = "b" * 40
        self.api.owner["head_sha"] = workflow_sha
        self.api.metadata["workflow_run"]["head_sha"] = workflow_sha
        self.assertTrue(self.recover(workflow_sha=workflow_sha))
        self.assertEqual(1, self.api.downloads)
        self.assertEqual(SHA, json.loads((self.capsule / "curation-proof.json").read_text())["implementation_sha"])
        telemetry = json.loads((self.capsule / "visual-review-telemetry.json").read_text())
        self.assertEqual(0, telemetry["model_attempts"]["total"])
        script = step_script("visual-review-drain.yml", "review", "Review bounded chunks with selective escalation")
        output = self.capsule / "step-output"
        completed = subprocess.run(["bash", "-c", script], cwd=self.capsule,
            env={"PATH": os.environ["PATH"], "RECOVERED": "true", "GITHUB_OUTPUT": str(output)},
            capture_output=True, text=True, timeout=10)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("model_status=0\n", output.read_text())
        self.assertIn("model calls=0", completed.stdout)
        recovery = step_script("visual-review-drain.yml", "review", "Recover a complete result after publication cancellation")
        self.assertIn('--workflow-sha "$GITHUB_SHA"', recovery)

    def test_current_workflow_recovery_keeps_legacy_owner_but_rejects_third_head(self):
        workflow_sha = "b" * 40
        self.api.owner["head_sha"] = "c" * 40
        self.api.metadata["workflow_run"]["head_sha"] = "c" * 40
        self.assertFalse(self.recover(workflow_sha=workflow_sha))
        self.assertEqual(0, self.api.downloads)
        self.api.owner["head_sha"] = SHA
        self.api.metadata["workflow_run"]["head_sha"] = SHA
        self.assertTrue(self.recover(workflow_sha=workflow_sha))

    def test_current_workflow_owner_does_not_authorize_changed_proof_or_manifest(self):
        workflow_sha = "b" * 40
        self.api.owner["head_sha"] = workflow_sha
        self.api.metadata["workflow_run"]["head_sha"] = workflow_sha
        original = copy.deepcopy(self.files)
        for name, value in (("curation-proof.json", json.dumps({**self.proof, "source_run_id": 999})),
                            ("review-input/visual-review-manifest.json", json.dumps(self.manifest, indent=2))):
            with self.subTest(name=name):
                self.files = {**original, name: value}
                self.replace_archive()
                self.assertFalse(self.recover(workflow_sha=workflow_sha))
                self.assertFalse((self.capsule / "visual-review-report.staged.json").exists())

    def test_recovery_rejects_malformed_workflow_identity_before_download(self):
        for workflow_sha in (None, 42, "refs/heads/master", "B" * 40, "b" * 39):
            with self.subTest(workflow_sha=workflow_sha):
                with self.assertRaisesRegex(ValueError, "workflow identities"):
                    self.recover(workflow_sha=workflow_sha)
        self.assertEqual(0, self.api.downloads)

    def test_cancelled_upload_or_later_attempt_does_not_admit_completed_state(self):
        self.api.job_list[0]["steps"][1]["conclusion"] = "cancelled"
        self.assertFalse(self.recover())
        self.api.job_list[0]["steps"][1]["conclusion"] = "success"
        self.api.metadata["created_at"] = "2026-09-13T09:00:00Z"
        self.assertFalse(self.recover())
        self.assertEqual(0, self.api.downloads)

    def test_foreign_policy_is_not_reused_and_valid_old_capsule_does_not_block_new_attempt(self):
        self.api.owner["head_sha"] = "b" * 40
        self.assertFalse(self.recover())
        self.api.owner["head_sha"] = SHA
        self.files["curation-proof.json"] = json.dumps({**self.proof, "source_run_id": 999})
        self.replace_archive()
        self.assertFalse(self.recover())
        self.assertFalse((self.capsule / "visual-review-report.staged.json").exists())
        self.files["visual-review-completion.json"] = json.dumps({**self.completion, "state": "blocking-partial"})
        self.replace_archive()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.recover()
        self.files["curation-proof.json"] = json.dumps(self.proof)
        self.files["visual-review-completion.json"] = json.dumps({**self.completion, "state": "blocking-partial"})
        self.replace_archive()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.recover()
        self.files["visual-review-completion.json"] = json.dumps({**self.completion, "schema_version": True})
        self.replace_archive()
        with self.assertRaisesRegex(ValueError, "incomplete"):
            self.recover()

    def committed_cache(self, cache, *, conclusion="success"):
        api = ApiFixture(archive({"visual-review-verdict-cache.json": json.dumps(cache)}),
            "visual-review-verdict-cache-" + POLICY, status="completed", conclusion=conclusion)
        api.job_list = [{"name": "Review one queued capsule", "status": "completed", "conclusion": conclusion,
            "steps": [{"name": name, "status": "completed", "conclusion": "success",
                       "started_at": "2026-09-13T10:00:00Z", "completed_at": "2026-09-13T10:00:02Z"}
                      for name in ("Independently validate the normalized result",
                          "Publish the protected exact-policy verdict cache", "Upload the protected exact-policy verdict cache",
                          "Retire the consumed exact-policy verdict cache shards")]}]
        return api

    def test_successful_immutable_cache_commit_survives_unrelated_tail_and_job_state(self):
        api = self.committed_cache({})
        expected = copy.deepcopy(api.metadata)
        api.owner.update(status="in_progress", conclusion=None)
        self.assertTrue(cache_owner_complete(api, 10, 20, expected_metadata=expected))
        api.job_list[0].update(status="in_progress", conclusion=None)
        self.assertTrue(cache_owner_complete(api, 10, 20, expected_metadata=expected))
        api.job_list[0]["steps"][2].update(status="in_progress", conclusion=None)
        self.assertFalse(cache_owner_complete(api, 10, 20, expected_metadata=expected))

    def test_union_survives_cancel_or_failure_after_predecessor_retirement(self):
        previous = [paired("fabric-1.21.2/scenario/client/previous", candidate="d" * 64)]
        old_cache = merge_cache(None, previous, [verdict(previous[0]["label"])],
                               policy_sha256=POLICY, review_mode="reference-comparison")
        current_only = merge_cache(None, self.manifest, [verdict(self.manifest[0]["label"])],
                                   policy_sha256=POLICY, review_mode="reference-comparison")
        committed_union = merge_cache(old_cache, self.manifest, [verdict(self.manifest[0]["label"])],
                                      policy_sha256=POLICY, review_mode="reference-comparison")
        self.assertEqual({}, cached_verdicts(previous, current_only, review_mode="reference-comparison"))
        for conclusion in ("cancelled", "failure", "timed_out"):
            with self.subTest(conclusion=conclusion):
                api = self.committed_cache(committed_union, conclusion=conclusion)
                # The previous shard is gone. Only this immutable A+B replacement remains;
                # recovering the current normalized B report alone cannot recover key A.
                self.assertTrue(cache_owner_complete(api, 10, 20, expected_metadata=copy.deepcopy(api.metadata)))
                with zipfile.ZipFile(io.BytesIO(api.raw)) as output:
                    restored = validate_cache(json.loads(output.read("visual-review-verdict-cache.json")), POLICY)
                def unavailable(*args, **kwargs):
                    raise AssertionError("retirement must not force repeated inference for an earlier key")
                result, counts = execute_review(previous, unavailable,
                    cache_hits=cached_verdicts(previous, restored, review_mode="reference-comparison"))
                self.assertEqual(1, counts["cached"])
                self.assertEqual(2, len(restored["entries"]))
                self.assertEqual(previous[0]["label"], result[0]["label"])

    def test_cache_commit_rejects_failed_steps_other_attempt_and_changed_inventory(self):
        api = self.committed_cache({})
        original = copy.deepcopy(api.metadata)
        for index in range(3):
            for conclusion in ("cancelled", "failure", None):
                with self.subTest(index=index, conclusion=conclusion):
                    api.job_list[0]["steps"][index]["conclusion"] = conclusion
                    self.assertFalse(cache_owner_complete(api, 10, 20, expected_metadata=original))
            api.job_list[0]["steps"][index]["conclusion"] = "success"
        api.metadata["created_at"] = "2026-09-13T09:00:00Z"
        self.assertFalse(cache_owner_complete(api, 10, 20, expected_metadata=copy.deepcopy(api.metadata)))
        api.metadata = copy.deepcopy(original)
        for field, value in (("digest", "sha256:" + "e" * 64), ("size_in_bytes", original["size_in_bytes"] + 1),
                             ("expired", True), ("name", "foreign-cache")):
            with self.subTest(field=field):
                api.metadata = {**original, field: value}
                self.assertFalse(cache_owner_complete(api, 10, 20, expected_metadata=original))

    def test_shared_and_distinct_workers_keep_exact_union_and_no_shared_key_reinference(self):
        first = self.manifest
        shared = [paired("neoforge-1.21.1/scenario/client/frame")]
        distinct = [paired("fabric-1.21.2/scenario/client/other", candidate="d" * 64)]
        cache_a = merge_cache(None, first, [verdict(first[0]["label"])], policy_sha256=POLICY, review_mode="reference-comparison")
        hits = cached_verdicts(shared, cache_a, review_mode="reference-comparison")
        self.assertEqual({shared[0]["label"]}, set(hits))
        def unavailable(*args, **kwargs):
            raise AssertionError("shared worker cannot repeat the admitted inference")
        result, counts = execute_review(shared, unavailable, cache_hits=hits)
        cache_b = merge_cache(cache_a, shared, result, policy_sha256=POLICY, review_mode="reference-comparison")
        cache_c = merge_cache(cache_b, distinct, [verdict(distinct[0]["label"])], policy_sha256=POLICY, review_mode="reference-comparison")
        union = combine_caches([cache_c, cache_a], policy_sha256=POLICY)
        self.assertEqual(2, len(union["entries"]))
        self.assertEqual(1, counts["cached"])
        with self.assertRaises(ValueError):
            validate_cache(union, "e" * 64)
        malformed = copy.deepcopy(union)
        malformed["entries"][0]["key"] = "f" * 64
        with self.assertRaises(ValueError):
            validate_cache(malformed, POLICY)


class CompletedInputRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        self.script = step_script("visual-review-drain.yml", "cleanup",
                                  "Reauthenticate and delete only the selected queue artifact")
        self.metadata = {"id": 77, "name": f"visual-review-input-55-{SHA}--mc1.20.1",
                         "digest": "sha256:" + "b" * 64, "expired": False,
                         "workflow_run": {"id": 88}}

    def cleanup(self, *, fresh="false", reviewed="false", missing=False):
        jq = shutil.which("jq")
        self.assertIsNotNone(jq)
        requests = self.folder / "requests"
        requests.write_text("")
        # Intercept every gh invocation, including DELETE; no request can escape this shell.
        mock = '''gh() {
          printf '%s\\n' "$*" >> "$REQUESTS"
          if [[ "$*" == "api repos/example/quick-skin/actions/artifacts/77" ]]; then
            if [[ "$MISSING" == true ]]; then printf '(HTTP 404)\\n' >&2; return 1; fi
            printf '%s\\n' "$METADATA"
          elif [[ "$*" == "api --method DELETE repos/example/quick-skin/actions/artifacts/77" ]]; then
            return 0
          else return 98; fi
        }
'''
        result = subprocess.run(["bash", "-c", mock + self.script], cwd=self.folder,
            env={"PATH": os.pathsep.join((str(Path(jq).parent), os.defpath)),
                 "RUNNER_TEMP": str(self.folder), "GITHUB_REPOSITORY": "example/quick-skin",
                 "ARTIFACT_ID": "77", "ARTIFACT_RUN_ID": "88", "ARTIFACT_DIGEST": "b" * 64,
                 "ARTIFACT_NAME": f"visual-review-input-55-{SHA}--mc1.20.1",
                 "FRESH_REVIEW_COMPLETE": fresh, "ALREADY_REVIEWED": reviewed,
                 "REQUESTS": str(requests), "METADATA": json.dumps(self.metadata),
                 "MISSING": str(missing).lower()},
            capture_output=True, text=True, timeout=10)
        return result, requests.read_text().splitlines()

    def test_fresh_or_other_owner_completion_retains_input_with_success_and_zero_api_calls(self):
        # already_reviewed includes another in-progress owner: neither flag may delete an input.
        for fresh, reviewed in (("true", "false"), ("false", "true"), ("true", "true")):
            with self.subTest(fresh=fresh, reviewed=reviewed):
                result, calls = self.cleanup(fresh=fresh, reviewed=reviewed)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([], calls)
        job = job_block("visual-review-drain.yml", "cleanup")
        self.assertIn("ALREADY_REVIEWED: ${{ needs.review.outputs.already_reviewed }}", job)
        self.assertIn("FRESH_REVIEW_COMPLETE: ${{ needs.review.outputs.review_complete }}", job)
        self.assertIn("needs.review.outputs.review_complete == 'true'", job)
        self.assertIn("needs.review.outputs.already_reviewed == 'true'", job)
        self.assertIn("needs.cleanup.result == 'success'",
                      job_block("visual-review-drain.yml", "release-mod-compatibility"))

    def test_terminal_invalid_and_missing_cleanup_keep_exact_identity_behavior(self):
        result, calls = self.cleanup()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["api repos/example/quick-skin/actions/artifacts/77",
                          "api --method DELETE repos/example/quick-skin/actions/artifacts/77"], calls)
        result, calls = self.cleanup(missing=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["api repos/example/quick-skin/actions/artifacts/77"], calls)

    def test_terminal_invalid_cleanup_never_deletes_changed_immutable_identity(self):
        for key, value in (("id", 78), ("name", "visual-review-input-999"),
                           ("digest", "sha256:" + "c" * 64), ("expired", True),
                           ("workflow_run", {"id": 89})):
            with self.subTest(key=key):
                original = copy.deepcopy(self.metadata)
                self.metadata[key] = value
                result, calls = self.cleanup()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(["api repos/example/quick-skin/actions/artifacts/77"], calls)
                self.metadata = original


def replay_reference(rows, *, handoff_seconds=0, slots=2):
    """Counterfactual only: preserve historical model/cache order and all non-preparation work.

    Two round-robin preparation slots model overlap, not a GitHub queue-time guarantee. The
    single-slot sensitivity also covers both artifact-ID parities landing on the same slot.
    Extra per-review serial handoff/reauthentication time is explicit, never hidden as zero.
    """
    timestamp = lambda value: datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
    ordered = sorted(rows, key=lambda row: row["started_at"])
    origin = timestamp(ordered[0]["started_at"])
    preparation = [0.0] * slots
    serial = 0.0
    prepared = []
    for index, row in enumerate(ordered):
        stage = next(step for step in row["steps"] if step["name"] == "Fetch and verify the exact curated capsule")
        duration = timestamp(stage["completed_at"]) - timestamp(stage["started_at"])
        slot = index % slots
        preparation[slot] = max(preparation[slot], timestamp(row["created_at"]) - origin) + duration
        prepared.append(preparation[slot])
        original = timestamp(row["completed_at"]) - timestamp(row["started_at"])
        serial = max(serial, preparation[slot]) + original - duration + handoff_seconds
    return {"seconds": serial, "prepared_at": prepared, "slots": slots,
            "handoff_seconds_per_review": handoff_seconds}


class HistoricalStageReplayTest(unittest.TestCase):
    def test_all_targets_and_actual_provider_counters_are_preserved(self):
        reference = json.loads((Path(__file__).parent / "fixtures/visual-review-stage-reference.json").read_text())
        rows = reference["rows"]
        self.assertEqual({row["bundle_key"] for row in inventory()["include"]}, {row["bundle"] for row in rows})
        self.assertEqual(16, len(rows))
        self.assertEqual(16, len({row["job_id"] for row in rows}))
        self.assertEqual(2880, sum(row["plan"]["frames"] for row in rows))
        self.assertEqual(685, sum(row["plan"]["cached"] for row in rows))
        self.assertEqual(173, sum(row["plan"]["represented"] for row in rows))
        self.assertEqual(2022, sum(row["plan"]["triaged"] for row in rows))
        self.assertEqual(278, sum(row["model_attempts"]["total"] for row in rows))
        self.assertEqual(1, sum(row["model_attempts"]["retries"] for row in rows))
        reference_copy = copy.deepcopy(rows)
        optimistic = replay_reference(rows)
        conservative = replay_reference(rows, slots=1, handoff_seconds=10)
        self.assertLess(optimistic["seconds"], conservative["seconds"])
        self.assertLess(conservative["seconds"], 4729)
        self.assertEqual(rows, reference_copy)


if __name__ == "__main__":
    unittest.main()
