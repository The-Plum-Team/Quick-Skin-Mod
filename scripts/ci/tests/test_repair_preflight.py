from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import repair_preflight as preflight
import test_e2e_selection as selection_fixtures
import test_pages_runtime_fanin as pages_fixtures
import test_visual_review_guard as checkout_fixtures


class RepairPreflightTest(unittest.TestCase):
    def setUp(self):
        self.fixture = selection_fixtures.E2ESelectionAdmissionTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        files = dict(self.fixture.files)
        files[".github/workflows/visual-review-drain.yml"] = ("100644", b"# Checkout repair.\n")
        self.head = self.fixture.commit(files, self.fixture.base)

    def plan(self, **changes):
        arguments = dict(base=self.fixture.base, head=self.head, policy=self.fixture.base,
                         intent="repair", scope=["Repair the protected reviewer checkout."])
        return preflight.make_plan(self.fixture.repository, **{**arguments, **changes})

    def test_checkout_repair_detects_missing_full_recovery_before_gate(self):
        plan = self.plan(intent="complete-recovery")
        self.assertFalse(plan["compatibility"]["compatibility_required"])
        self.assertEqual(["complete-optional-coverage"], plan["missing_coverage"])
        self.assertIn(preflight.MARKER, plan["required_actions"][0])
        self.assertIn("authorized", plan["required_actions"][0])
        self.assertNotIn(preflight.MARKER, plan["paths"])
        preflight.verify_seal(plan)

    def test_routine_repair_does_not_request_a_recovery_marker(self):
        plan = self.plan()
        self.assertEqual([], plan["missing_coverage"])
        self.assertEqual([], plan["required_actions"])
        self.assertEqual(dict.fromkeys(preflight.WORK_KINDS, 0), plan["work_counts"])
        self.assertTrue(plan["live_reauthentication_required"])

    def test_authorized_marker_change_requests_the_canonical_wave(self):
        files = dict(self.fixture.files)
        files[preflight.MARKER] = ("100644", b'{"purpose":"authorized unfinished recovery"}\n')
        head = self.fixture.commit(files, self.fixture.base)
        plan = self.plan(head=head, intent="complete-recovery")
        self.assertTrue(plan["compatibility"]["compatibility_required"])
        self.assertIn("fresh optional wave", plan["required_actions"][0])

    def observation(self, status="available"):
        return {"status": status, "checked_at": "2026-09-13T08:00:00Z", "coverage_sha": self.head,
                "identities": [{"repository": "example/quick-skin", "source_sha": self.fixture.base,
                                "run_id": 55, "attempt": 1, "artifact_id": 300,
                                "digest": "sha256:" + "a" * 64}]}

    def test_recorded_reuse_preserves_identity_and_requires_live_reauthentication(self):
        evidence = self.observation()
        plan = self.plan(intent="complete-recovery", evidence=evidence)
        self.assertEqual(evidence, plan["optional_evidence_observation"])
        self.assertEqual([], plan["missing_coverage"])
        self.assertIn("canonical carry-forward", plan["required_actions"][0])
        self.assertTrue(plan["live_reauthentication_required"])

    def test_api_error_stays_distinct_from_absence(self):
        plan = self.plan(intent="complete-recovery", evidence=self.observation("read_error"))
        self.assertIn("optional-evidence-read-error", plan["missing_coverage"])
        self.assertEqual("read_error", plan["optional_evidence_observation"]["status"])

    def test_head_and_scope_changes_identify_which_prior_proof_is_invalid(self):
        original = self.plan()
        changed = self.plan(previous=original, head=self.fixture.head,
                            scope=["Change the HUD implementation."])
        invalid = changed["invalidated_proof"][0]
        self.assertEqual(original["head"], invalid["head"])
        self.assertEqual(original["tree"], invalid["tree"])
        self.assertIn("tree", invalid["changed_fields"])
        self.assertIn("scope", invalid["changed_fields"])
        self.assertEqual([], self.plan(previous=original)["invalidated_proof"])

    def test_malformed_duplicate_or_foreign_evidence_cannot_claim_reuse(self):
        for transform in (lambda value: value.update(coverage_sha="f" * 40),
                          lambda value: value.update(identities=[]),
                          lambda value: value["identities"].append(value["identities"][0]),
                          lambda value: value["identities"][0].update(attempt=True),
                          lambda value: value.update(checked_at="not a time")):
            evidence = self.observation()
            transform(evidence)
            with self.assertRaises(ValueError):
                self.plan(evidence=evidence)

    def test_changed_seal_is_rejected(self):
        plan = self.plan()
        plan["scope"].append("Unreviewed extra work.")
        with self.assertRaisesRegex(ValueError, "seal"):
            preflight.verify_seal(plan)

    def test_cli_rechecks_frozen_identity_after_the_focused_regressions(self):
        plan = self.plan()
        with patch.object(sys, "argv", ["preflight", "check", "--plan", "plan.json"]), \
                patch.object(preflight, "read_json", return_value=plan), \
                patch.object(preflight, "focused_checks", return_value={"successful": True}), \
                patch.object(preflight, "check_frozen", side_effect=[None, ValueError("HEAD changed")]) as checks, \
                patch("builtins.print"):
            self.assertEqual(1, preflight.main())
        self.assertEqual(2, checks.call_count)

    def test_real_checkout_rejects_head_change_and_uncommitted_scope(self):
        workspace = Path(self.fixture.temporary.name) / "checkout"
        self.fixture.git("clone", "--quiet", "--no-checkout", str(self.fixture.repository), str(workspace))
        self.fixture.git("-C", str(workspace), "checkout", "--quiet", self.head)
        plan = self.plan()
        preflight.check_frozen(plan, workspace)
        altered = {**plan["implementation_sha256"], "scripts/ci/repair_preflight.py": "f" * 64}
        with patch.object(preflight, "implementation_fingerprint", return_value=altered):
            with self.assertRaisesRegex(ValueError, "implementation_sha256"):
                preflight.check_frozen(plan, workspace)
        (workspace / "unfinished.txt").write_text("Uncommitted scope.\n")
        with self.assertRaisesRegex(ValueError, "worktree changed"):
            preflight.check_frozen(plan, workspace)
        (workspace / "unfinished.txt").unlink()
        self.fixture.git("-C", str(workspace), "checkout", "--quiet", self.fixture.base)
        with self.assertRaisesRegex(ValueError, "HEAD changed"):
            preflight.check_frozen(plan, workspace)


class RepairRegressionMutationTest(unittest.TestCase):
    def test_seeded_sparse_checkout_regression_fails_in_fresh_git(self):
        original = checkout_fixtures.job_block

        def sparse_bootstrap(workflow, job):
            block = original(workflow, job)
            return block.replace("          persist-credentials: false\n",
                "          persist-credentials: false\n"
                "          sparse-checkout: |\n"
                "            scripts/ci/bounded_zip.py\n"
                "          sparse-checkout-cone-mode: false\n", 1)

        test = checkout_fixtures.VisualReviewGuardTest(
            "test_both_checkouts_leave_the_exact_reviewers_requirements_available")
        result = unittest.TestResult()
        with patch.object(checkout_fixtures, "job_block", side_effect=sparse_bootstrap):
            test.run(result)
        self.assertEqual(1, result.testsRun)
        self.assertEqual([], result.errors)
        self.assertEqual(1, len(result.failures), "The historical sparse checkout must fail before CI.")
        self.assertIn("requirements", result.failures[0][1])

    def test_seeded_immutable_id_collision_rejects_timestamp_only_byte_changes(self):
        fixture = pages_fixtures.PagesRuntimeFaninTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        original = fixture.api.add_descriptor
        def reused_id(run_id, identifier, name, filename, value):
            return original(run_id, 300, name, filename, value)
        first = fixture.api.archives[300]
        with patch.object(fixture.api, "add_descriptor", side_effect=reused_id), \
                patch("zipfile.time.localtime", return_value=(2001, 1, 1, 0, 0, 0, 0, 1, -1)):
            fixture.api.wrapper(fixture.manifest["runtime_source"], identifier=31)
        self.assertNotEqual(first, fixture.api.archives[300])
        # The first immutable ID still advertises its original digest. The real consumer must
        # reject it even though both ZIP payloads contain semantically identical JSON.
        with self.assertRaisesRegex(ValueError, "archive differs from its authenticated metadata"):
            fixture.verify()


if __name__ == "__main__":
    unittest.main()
