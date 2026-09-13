from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from publication_fixture import bundle
import rehearse_publication as rehearsal


class PublicationRehearsalTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.stage = Path(self.temporary.name)
        self.matrix, self.manifest = bundle(self.stage)

    def tearDown(self):
        self.temporary.cleanup()

    def test_complete_protocol_recovers_partial_uploads_without_mutating_the_bundle(self):
        before = {str(path.relative_to(self.stage)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in self.stage.rglob("*") if path.is_file()}
        result = rehearsal.rehearse(self.matrix, self.stage)
        self.assertEqual(result["marketplace_uploads"], 4)
        self.assertEqual(result["github_assets"], 5)
        self.assertEqual(result["duplicate_uploads"], 0)
        self.assertEqual(result["unindexed_upload"], "fenced")
        after = {str(path.relative_to(self.stage)): hashlib.sha256(path.read_bytes()).hexdigest()
                 for path in self.stage.rglob("*") if path.is_file()}
        self.assertEqual(after, before)

    def test_missing_attestation_serial_fails_before_any_transport(self):
        path = self.stage / self.manifest["sbom"]["path"]
        sbom = json.loads(path.read_bytes())
        del sbom["serialNumber"]
        path.write_text(json.dumps(sbom), encoding="utf-8")
        with patch.object(rehearsal.SimulatedGitHub, "command") as transport:
            with self.assertRaisesRegex(RuntimeError, "serialNumber"):
                rehearsal.rehearse(self.matrix, self.stage)
            transport.assert_not_called()

    def test_github_normalization_conflict_is_rejected_before_publication(self):
        original = rehearsal.SimulatedGitHub.command

        def corrupt(service, command, **kwargs):
            result = original(service, command, **kwargs)
            if command[:3] == ["gh", "release", "view"] and service.assets:
                key = next(iter(service.assets))
                service.assets[key] = b"different bytes"
            return result

        with patch.object(rehearsal.SimulatedGitHub, "command", new=corrupt):
            with self.assertRaisesRegex(RuntimeError, "different bytes"):
                rehearsal.rehearse(self.matrix, self.stage)

    def test_changed_marketplace_download_cannot_finalize(self):
        with patch.object(rehearsal.SimulatedMarketplaces, "download", return_value=b"different"):
            with self.assertRaisesRegex(RuntimeError, "different bytes"):
                rehearsal.rehearse(self.matrix, self.stage)


if __name__ == "__main__":
    unittest.main()
