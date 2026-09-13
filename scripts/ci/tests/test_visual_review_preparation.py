from __future__ import annotations

import copy
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
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
        self.args = dict(run_id=10, run_attempt=1, implementation_sha=SHA, artifact_id=20,
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

    def recover(self):
        return recover(self.api, capsule=self.capsule, implementation_sha=SHA, review_key="100--mc1.21.1")

    def replace_archive(self):
        self.api.raw = archive(self.files)
        self.api.metadata.update(size_in_bytes=len(self.api.raw), digest="sha256:" + hashlib.sha256(self.api.raw).hexdigest())

    def test_cancellation_during_cache_publication_recovers_without_provider(self):
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
