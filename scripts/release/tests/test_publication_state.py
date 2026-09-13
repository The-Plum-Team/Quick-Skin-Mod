from __future__ import annotations

import copy
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from publication_fixture import bundle
import publication_state as ledger
from reconcile_publication import PublicationPendingError, Reconciliation, ReconciliationError
from rehearse_publication import SimulatedGitHub
from github_release import load_contract


class PublicationStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.stage = Path(self.temporary.name)
        self.matrix, self.manifest = bundle(self.stage)
        self.path = self.stage / "artifacts.json"
        self.state = ledger.new_state(self.path, self.matrix, producer_sha="a" * 40, run_id=123,
                                      artifact={"id": 456, "digest": "sha256:" + "b" * 64})
        self.row_id = next(iter(self.state["rows"]))

    def tearDown(self):
        self.temporary.cleanup()

    def test_durable_round_trip_preserves_notes_and_all_partial_states(self):
        for status in ("unstarted", "uploading", "pending", "verified"):
            with self.subTest(status=status):
                state = copy.deepcopy(self.state)
                state["rows"][self.row_id] = {"state": status, "remote_id": "123" if status == "verified" else None}
                body = ledger.encode("Human release notes\n", state)
                self.assertTrue(body.startswith("Human release notes\n\n<!--"))
                self.assertEqual(ledger.decode(body), state)
                self.assertEqual(ledger.encode(body, state), body)

    def test_absence_after_crash_cannot_authorize_a_second_upload(self):
        state = self.state["rows"][self.row_id]
        state, upload = ledger.begin(ledger.observe(state, lambda: Reconciliation(True, None)))
        self.assertTrue(upload)
        self.state["rows"][self.row_id] = state
        restored = ledger.decode(ledger.encode("", self.state))["rows"][self.row_id]
        restored = ledger.observe(restored, lambda: Reconciliation(True, None))
        self.assertEqual(ledger.begin(restored), (restored, False))
        pending = ledger.accept(restored)
        self.assertEqual(pending["state"], "pending")
        self.assertFalse(ledger.begin(pending)[1])

    def test_indexed_unapproved_file_from_older_run_also_fences_upload(self):
        inspector = Mock(side_effect=PublicationPendingError("moderation"))
        current = ledger.observe({"state": "unstarted", "remote_id": None}, inspector)
        self.assertEqual(current["state"], "pending")
        self.assertFalse(ledger.begin(current)[1])
        inspector.assert_called_once()

    def test_conflict_disappearance_or_remote_id_change_never_becomes_pending(self):
        verified = {"state": "verified", "remote_id": "123"}
        for outcome in (Reconciliation(True, None), Reconciliation(False, "124"),
                        PublicationPendingError("unapproved"), ReconciliationError("different bytes")):
            with self.subTest(outcome=outcome), self.assertRaises((ValueError, ReconciliationError)):
                ledger.observe(verified, Mock(side_effect=outcome) if isinstance(outcome, Exception)
                               else lambda: outcome)

    def test_codec_rejects_duplicate_fields_markers_injections_and_oversized_state(self):
        valid = ledger.encode("", self.state)
        for body in (valid + valid, valid.replace('"schema_version":1', '"schema_version":1,"schema_version":1'),
                     valid.replace(":v1", ":v2"), valid + "untrusted suffix", "x" * 60001):
            with self.subTest(body=body[:60]), self.assertRaises(ValueError):
                ledger.decode(body)
        for key, value in (("run_id", True), ("artifact_id", 0), ("source_sha", "x" * 40),
                           ("tag", "mc26.1-v3.0.0\nready=true"), ("target", "26.2"),
                           ("artifact_digest", "b" * 64), ("rows", {})):
            state = {**self.state, key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                ledger.validate(state)

    def test_bundle_binding_rejects_retargeting_missing_rows_and_changed_bytes(self):
        ledger.bind(self.state, self.matrix, self.path)
        for key, value in (("source_sha", "b" * 40), ("manifest_sha256", "0" * 64),
                           ("rows", {self.row_id: self.state["rows"][self.row_id]})):
            with self.subTest(key=key), self.assertRaises(ValueError):
                ledger.bind({**self.state, key: value}, self.matrix, self.path)

    def test_stale_body_or_published_release_cannot_be_mutated(self):
        release = {"id": 1, "tag_name": self.state["tag"], "draft": True, "body": "notes"}
        api = Mock()
        api.json.return_value = {**release, "body": "concurrent edit"}
        with patch.object(ledger.subprocess, "run") as mutation:
            with self.assertRaisesRegex(ValueError, "release body changed"):
                ledger.save(api, release, self.state)
            with self.assertRaisesRegex(ValueError, "published release"):
                ledger.save(api, {**release, "draft": False}, self.state)
            mutation.assert_not_called()

    def test_unconfirmed_body_write_cannot_authorize_upload(self):
        release = {"id": 1, "tag_name": self.state["tag"], "draft": True, "body": "notes"}
        api = Mock(prefix="repos/owner/repo/")
        api.json.return_value = release
        with patch.object(ledger.subprocess, "run") as mutation:
            with self.assertRaisesRegex(ValueError, "write was not confirmed"):
                ledger.save(api, release, self.state)
            mutation.assert_called_once()

    def test_accepted_upload_is_durable_even_when_the_visibility_api_fails(self):
        self.state["rows"][self.row_id] = {"state": "uploading", "remote_id": None}
        contract = load_contract(self.path, self.stage, self.state["tag"], self.state["source_sha"])
        service = SimulatedGitHub(contract)
        service.release = {"id": 1, "tag_name": self.state["tag"], "draft": True,
                           "body": ledger.encode("Notes", self.state)}
        arguments = ["publication_state.py", "accept", "--tag", self.state["tag"],
                     "--row", self.row_id, "--stage", str(self.stage)]
        with patch.object(sys, "argv", arguments), patch.dict(os.environ, {"GITHUB_REPOSITORY": service.repository}), \
                patch.object(ledger, "Api", return_value=service), \
                patch.object(ledger.subprocess, "run", side_effect=service.command), \
                patch.object(ledger, "inspector_for", return_value=Mock(side_effect=ReconciliationError("outage"))):
            with self.assertRaises(SystemExit):
                ledger.main()
        restored = ledger.decode(service.release["body"])["rows"][self.row_id]
        self.assertEqual(restored["state"], "pending")
        self.assertFalse(ledger.begin(restored)[1])

    def test_new_preparation_refreshes_evidence_without_resetting_existing_upload_intent(self):
        self.state["rows"][self.row_id] = {"state": "uploading", "remote_id": None}
        contract = load_contract(self.path, self.stage, self.state["tag"], self.state["source_sha"])
        service = SimulatedGitHub(contract)
        service.release = {"id": 1, "tag_name": self.state["tag"], "draft": True,
                           "body": ledger.encode("Notes", self.state)}
        service.artifact = Mock(return_value={"id": 789, "name": "release-" + self.state["tag"],
                               "digest": "sha256:" + "c" * 64, "expired": False,
                               "workflow_run": {"id": 321, "head_sha": "a" * 40}})
        arguments = ["publication_state.py", "register", "--tag", self.state["tag"],
                     "--artifact-id", "789", "--stage", str(self.stage)]
        environment = {"GITHUB_REPOSITORY": service.repository, "GITHUB_RUN_ID": "321", "GITHUB_SHA": "a" * 40}
        with patch.object(sys, "argv", arguments), patch.dict(os.environ, environment), \
                patch.object(ledger, "Api", return_value=service), \
                patch.object(ledger.subprocess, "run", side_effect=service.command):
            self.assertEqual(ledger.main(), 0)
        restored = ledger.decode(service.release["body"])
        self.assertEqual(restored["artifact_id"], 789)
        self.assertEqual(restored["run_id"], 321)
        self.assertEqual(restored["rows"], self.state["rows"])


if __name__ == "__main__":
    unittest.main()
