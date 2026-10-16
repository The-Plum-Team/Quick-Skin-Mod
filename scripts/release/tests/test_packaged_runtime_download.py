from __future__ import annotations

import hashlib
import io
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime
from packaged_runtime import RuntimeFailure, download

URL = "https://maven.example.invalid/group/artifact/1.0/artifact-1.0.jar"
PAYLOAD = b"runtime dependency bytes"
DIGEST = hashlib.sha256(PAYLOAD).hexdigest()


class _Response(io.BytesIO):
    def __enter__(self) -> _Response:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


def _http_error(code: int) -> urllib.error.HTTPError:
    return urllib.error.HTTPError(URL, code, "error", {}, None)


class DownloadRetryTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.destination = Path(temporary.name) / "artifact-1.0.jar"
        sleep = mock.patch.object(packaged_runtime.time, "sleep")
        self.sleep = sleep.start()
        self.addCleanup(sleep.stop)

    def _download(self, *answers: object) -> mock.Mock:
        opener = mock.Mock(side_effect=list(answers))
        with mock.patch.object(packaged_runtime.urllib.request, "urlopen", opener):
            with redirect_stderr(io.StringIO()):
                download(URL, self.destination, DIGEST)
        return opener

    def test_a_server_error_or_reset_is_retried_until_the_verified_bytes_arrive(self) -> None:
        opener = self._download(
            _http_error(500),
            ConnectionResetError(104, "Connection reset by peer"),
            _Response(PAYLOAD),
        )
        self.assertEqual(opener.call_count, 3)
        self.assertEqual(self.destination.read_bytes(), PAYLOAD)
        self.assertEqual(self.sleep.call_count, 2)

    def test_a_missing_artifact_fails_at_once(self) -> None:
        with self.assertRaisesRegex(RuntimeFailure, "HTTP Error 404"):
            self._download(_http_error(404), _Response(PAYLOAD))
        self.assertFalse(self.destination.exists())
        self.sleep.assert_not_called()

    def test_the_last_transient_failure_is_reported_after_every_attempt(self) -> None:
        answers = [_http_error(503)] * packaged_runtime.DOWNLOAD_ATTEMPTS
        with self.assertRaisesRegex(RuntimeFailure, "failed to download .*HTTP Error 503"):
            self._download(*answers)
        self.assertFalse(self.destination.exists())
        self.assertFalse(self.destination.with_suffix(".jar.part").exists())

    def test_retried_bytes_must_still_match_the_pinned_digest(self) -> None:
        with self.assertRaisesRegex(RuntimeFailure, "SHA-256 mismatch"):
            self._download(urllib.error.URLError("timed out"), _Response(b"other bytes"))


if __name__ == "__main__":
    unittest.main()
