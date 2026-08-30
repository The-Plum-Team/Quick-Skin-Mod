from __future__ import annotations

import hashlib
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import cpm_fixture_secret  # noqa: E402


class CpmFixtureSecretTest(unittest.TestCase):
    def setUp(self) -> None:
        self.payload = b"\x53" + bytes(range(256)) * 32
        self.sha256 = hashlib.sha256(self.payload).hexdigest()

    def test_four_secret_chunks_round_trip_exact_bytes(self) -> None:
        chunks = cpm_fixture_secret.encode_payload(
            self.payload,
            expected_size=len(self.payload),
            expected_sha256=self.sha256,
        )

        self.assertEqual(len(chunks), 4)
        self.assertTrue(all(0 < len(chunk) <= 40_000 for chunk in chunks))
        self.assertEqual(
            cpm_fixture_secret.decode_payload(
                chunks,
                expected_size=len(self.payload),
                expected_sha256=self.sha256,
            ),
            self.payload,
        )

    def test_decode_rejects_missing_oversized_and_tampered_chunks(self) -> None:
        chunks = list(
            cpm_fixture_secret.encode_payload(
                self.payload,
                expected_size=len(self.payload),
                expected_sha256=self.sha256,
            )
        )
        cases = (
            chunks[:3],
            ["a" * 40_001, *chunks[1:]],
            ["!" + chunks[0][1:], *chunks[1:]],
        )
        for candidate in cases:
            with self.subTest(candidate_length=len(candidate)):
                with self.assertRaises(cpm_fixture_secret.FixtureSecretError):
                    cpm_fixture_secret.decode_payload(
                        candidate,
                        expected_size=len(self.payload),
                        expected_sha256=self.sha256,
                    )

    def test_materialization_fails_closed_when_any_secret_is_missing(self) -> None:
        environment = {
            name: "value" for name in cpm_fixture_secret.SECRET_NAMES[:-1]
        }
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "fixture.bin"
            with self.assertRaisesRegex(
                cpm_fixture_secret.FixtureSecretError,
                cpm_fixture_secret.SECRET_NAMES[-1],
            ):
                cpm_fixture_secret.materialize_fixture(output, environment)
            self.assertFalse(output.exists())

    def test_materialization_writes_an_atomic_owner_only_file(self) -> None:
        environment = {name: "value" for name in cpm_fixture_secret.SECRET_NAMES}
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "fixture.bin"
            with mock.patch.object(
                cpm_fixture_secret,
                "decode_payload",
                return_value=self.payload,
            ):
                cpm_fixture_secret.materialize_fixture(output, environment)

            self.assertEqual(output.read_bytes(), self.payload)
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
