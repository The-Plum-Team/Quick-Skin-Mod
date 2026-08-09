from __future__ import annotations

import dataclasses
import hashlib
import io
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from typing import Any
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import reconcile_publication as reconciliation  # noqa: E402


class PublicationReconciliationTest(unittest.TestCase):
    def expected(self) -> reconciliation.ExpectedArtifact:
        return reconciliation.ExpectedArtifact(
            node="fabric-1.20.1",
            filename="Quick Skin - Fabric - 1.20.1-3.0.0.jar",
            path=Path("unused.jar"),
            bytes=123,
            sha1="1" * 40,
            sha256="2" * 64,
            sha512="3" * 128,
        )

    def test_modrinth_skips_only_an_exact_hash_and_identity_match(self) -> None:
        expected = self.expected()
        remote = {
            "id": "remote01",
            "project_id": "project1",
            "version_number": "mc1.20.1-v3.0.0-fabric-1.20.1",
            "files": [{
                "filename": expected.filename,
                "size": expected.bytes,
                "hashes": {"sha512": expected.sha512},
            }],
        }
        result = reconciliation.classify_modrinth(
            remote,
            [{"id": "remote01", "version_number": remote["version_number"]}],
            expected,
            "project1",
            remote["version_number"],
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "remote01")

    def test_modrinth_fails_closed_on_version_or_hash_collision(self) -> None:
        expected = self.expected()
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                None,
                [{"id": "other", "version_number": "release-id"}],
                expected,
                "project1",
                "release-id",
            )
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_modrinth(
                {
                    "id": "other",
                    "project_id": "another-project",
                    "version_number": "release-id",
                    "files": [],
                },
                [],
                expected,
                "project1",
                "release-id",
            )

    def expected_for(self, payload: bytes) -> reconciliation.ExpectedArtifact:
        return reconciliation.ExpectedArtifact(
            node="fabric-1.20.1",
            filename="Quick Skin - Fabric - 1.20.1-3.0.0.jar",
            path=Path("unused.jar"),
            bytes=len(payload),
            sha1=hashlib.sha1(payload).hexdigest(),
            sha256=hashlib.sha256(payload).hexdigest(),
            sha512=hashlib.sha512(payload).hexdigest(),
        )

    def approved(self, expected: reconciliation.ExpectedArtifact) -> dict[str, Any]:
        return {
            "id": 42,
            "projectId": 1323980,
            "fileName": expected.filename,
            "fileLength": expected.bytes,
            "status": reconciliation.CURSEFORGE_APPROVED_STATUS,
        }

    def refuse_download(self, url: str, limit: int) -> bytes:
        raise AssertionError(f"unexpected download of {url}")

    def test_curseforge_reconciles_by_downloading_the_published_bytes(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = self.expected_for(payload)
        requested: list[tuple[str, int]] = []

        def fetch(url: str, limit: int) -> bytes:
            requested.append((url, limit))
            return payload

        result = reconciliation.classify_curseforge(
            [self.approved(expected)], expected, 1323980, fetch
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "42")
        # The download is bounded by the staged size and addresses the sharded CDN path.
        self.assertEqual(len(requested), 1)
        self.assertEqual(requested[0][1], expected.bytes)
        self.assertTrue(requested[0][0].startswith(f"{reconciliation.CURSEFORGE_CDN}/0/42/"))

    def test_curseforge_fails_closed_when_published_bytes_differ(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = self.expected_for(payload)
        # Same declared length, different content: only hashing the real bytes catches this.
        imposter = b"x" * len(payload)
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge(
                [self.approved(expected)], expected, 1323980, lambda url, limit: imposter
            )

    def test_curseforge_fails_closed_when_only_the_sha256_diverges(self) -> None:
        payload = b"quick-skin-published-bytes"
        # Size and SHA-1 still agree, so only the strong digest can reject this.
        expected = dataclasses.replace(self.expected_for(payload), sha256="a" * 64)
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge(
                [self.approved(expected)], expected, 1323980, lambda url, limit: payload
            )

    def test_curseforge_fails_closed_when_only_the_sha1_diverges(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = dataclasses.replace(self.expected_for(payload), sha1="a" * 40)
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge(
                [self.approved(expected)], expected, 1323980, lambda url, limit: payload
            )

    def test_curseforge_accepts_uppercase_manifest_digests(self) -> None:
        payload = b"quick-skin-published-bytes"
        base = self.expected_for(payload)
        expected = dataclasses.replace(
            base, sha1=base.sha1.upper(), sha256=base.sha256.upper()
        )
        result = reconciliation.classify_curseforge(
            [self.approved(expected)], expected, 1323980, lambda url, limit: payload
        )
        self.assertFalse(result.publish)

    def test_curseforge_reports_an_unapproved_file_as_pending(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = self.expected_for(payload)
        settling = self.approved(expected)
        settling["status"] = 1
        # The distinct type is what lets --verify poll while pre-publication still fails closed.
        with self.assertRaises(reconciliation.PublicationPendingError):
            reconciliation.classify_curseforge(
                [settling], expected, 1323980, self.refuse_download
            )

    def test_curseforge_fails_closed_on_a_declared_size_mismatch(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = self.expected_for(payload)
        resized = self.approved(expected)
        resized["fileLength"] = expected.bytes + 1
        with self.assertRaises(reconciliation.ReconciliationError):
            reconciliation.classify_curseforge(
                [resized], expected, 1323980, self.refuse_download
            )

    def test_curseforge_ignores_a_file_owned_by_another_project(self) -> None:
        payload = b"quick-skin-published-bytes"
        expected = self.expected_for(payload)
        foreign = self.approved(expected)
        foreign["projectId"] = 999999
        self.assertTrue(
            reconciliation.classify_curseforge(
                [foreign], expected, 1323980, self.refuse_download
            ).publish
        )

    def test_curseforge_download_url_never_pads_the_shard_remainder(self) -> None:
        # A zero-padded remainder is rejected by the CDN with HTTP 403.
        self.assertEqual(
            reconciliation.curseforge_download_url(7214079, "Quick Skin.jar"),
            "https://mediafilez.forgecdn.net/files/7214/79/Quick%20Skin.jar",
        )

    def test_missing_marketplace_file_is_publishable(self) -> None:
        expected = self.expected()
        self.assertTrue(
            reconciliation.classify_modrinth(
                None, [], expected, "project1", "release-id"
            ).publish
        )
        self.assertTrue(
            reconciliation.classify_curseforge(
                [], expected, 1323980, self.refuse_download
            ).publish
        )


class CurseForgeListingTest(unittest.TestCase):
    def expected(self) -> reconciliation.ExpectedArtifact:
        return reconciliation.ExpectedArtifact(
            node="fabric-1.20.1",
            filename="absent.jar",
            path=Path("unused.jar"),
            bytes=1,
            sha1="1" * 40,
            sha256="2" * 64,
            sha512="3" * 128,
        )

    def page(self, start: int, count: int, total: int) -> dict[str, Any]:
        return {
            "data": [
                {
                    "id": start + offset,
                    "projectId": 7,
                    "fileName": f"other-{start + offset}.jar",
                    "fileLength": 1,
                    "status": reconciliation.CURSEFORGE_APPROVED_STATUS,
                }
                for offset in range(count)
            ],
            # The envelope echoes the requested page size rather than the served one.
            "pagination": {"index": 0, "pageSize": 100, "totalCount": total},
        }

    def test_listing_advances_by_page_ordinal_and_terminates(self) -> None:
        pages = [self.page(0, 50, 60), self.page(50, 10, 60)]
        seen: list[str] = []

        def fake_request_json(url: str, headers: dict[str, str], **kwargs: Any) -> Any:
            seen.append(url)
            return pages[len(seen) - 1]

        with mock.patch.object(reconciliation, "request_json", fake_request_json):
            result = reconciliation.inspect_curseforge(
                self.expected(), 7, lambda url, limit: b""
            )

        # Two pages, addressed by ordinal. An item offset would re-read the first page forever.
        self.assertEqual(len(seen), 2)
        self.assertIn("pageIndex=0", seen[0])
        self.assertIn("pageIndex=1", seen[1])
        self.assertNotIn("index=5", seen[1])
        self.assertTrue(result.publish)

    def test_listing_is_bounded_when_the_remote_never_terminates(self) -> None:
        endless = self.page(0, 50, 10_000_000)

        with mock.patch.object(
            reconciliation, "request_json", lambda url, headers, **kwargs: endless
        ):
            with self.assertRaises(reconciliation.ReconciliationError):
                reconciliation.inspect_curseforge(
                    self.expected(), 7, lambda url, limit: b""
                )

    def test_listing_bound_counts_requests_not_usable_rows(self) -> None:
        # A page whose rows are all unusable keeps the collected inventory empty, so a bound on
        # rows kept would never trip and the walk would issue requests forever.
        junk = {
            "data": [None] * 50,
            "pagination": {"index": 0, "pageSize": 50, "totalCount": 98},
        }
        calls: list[str] = []

        def fake_request_json(url: str, headers: dict[str, str], **kwargs: Any) -> Any:
            calls.append(url)
            return junk

        with mock.patch.object(reconciliation, "request_json", fake_request_json):
            with self.assertRaises(reconciliation.ReconciliationError):
                reconciliation.inspect_curseforge(
                    self.expected(), 7, lambda url, limit: b""
                )
        self.assertEqual(len(calls), reconciliation.MAX_CURSEFORGE_PAGES)


class PendingPublicationTest(unittest.TestCase):
    def test_verify_polls_through_the_marketplace_approval_window(self) -> None:
        steps: list[Any] = [
            reconciliation.PublicationPendingError("settling"),
            reconciliation.PublicationPendingError("settling"),
            reconciliation.Reconciliation(False, "42"),
        ]

        def inspector() -> reconciliation.Reconciliation:
            step = steps.pop(0)
            if isinstance(step, Exception):
                raise step
            return step

        sleeps: list[float] = []
        result = reconciliation.settle_publication(
            inspector, verify=True, attempts=6, delay_seconds=10.0, sleep=sleeps.append
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "42")
        self.assertEqual(sleeps, [10.0, 10.0])

    def test_pre_publication_fails_closed_on_a_settling_upload(self) -> None:
        def inspector() -> reconciliation.Reconciliation:
            raise reconciliation.PublicationPendingError("settling")

        # Publishing over an upload that is still being accepted is how a duplicate is created.
        with self.assertRaises(reconciliation.PublicationPendingError):
            reconciliation.settle_publication(
                inspector,
                verify=False,
                attempts=6,
                delay_seconds=10.0,
                sleep=lambda seconds: None,
            )


class DownloadTest(unittest.TestCase):
    def urlopen_from(self, responses: list[Any]) -> Any:
        stream = iter(responses)

        def urlopen(request: Any, timeout: float) -> Any:
            value = next(stream)
            if isinstance(value, Exception):
                raise value
            return value

        return urlopen

    def test_truncated_transfer_retries_instead_of_reporting_divergence(self) -> None:
        payload = b"a" * 64
        urlopen = self.urlopen_from([io.BytesIO(payload[:10]), io.BytesIO(payload)])
        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_bytes(
                "https://cdn/file.jar", len(payload), sleep=sleeps.append
            )
        self.assertEqual(value, payload)
        self.assertEqual(sleeps, [2.0])

    def test_mid_body_connection_reset_is_transient(self) -> None:
        payload = b"b" * 32

        class Resetting(io.BytesIO):
            def read(self, size: int = -1) -> bytes:
                raise ConnectionResetError("peer reset the connection")

        urlopen = self.urlopen_from([Resetting(), io.BytesIO(payload)])
        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_bytes(
                "https://cdn/file.jar", len(payload), sleep=sleeps.append
            )
        self.assertEqual(value, payload)
        self.assertEqual(sleeps, [2.0])

    def test_oversized_body_is_definitive_and_never_retried(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            return io.BytesIO(b"c" * 4096)

        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(
                reconciliation.ReconciliationError, "larger than the staged"
            ):
                reconciliation.request_bytes(
                    "https://cdn/file.jar", 10, sleep=lambda seconds: None
                )
        self.assertEqual(len(calls), 1)

    def test_cdn_client_rejection_never_retries(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://cdn", 403, "forbidden", None, None)

        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(reconciliation.ReconciliationError, "HTTP 403"):
                reconciliation.request_bytes(
                    "https://cdn/file.jar", 10, sleep=lambda seconds: None
                )
        self.assertEqual(len(calls), 1)


class RequestRetryTest(unittest.TestCase):
    def test_transient_failures_retry_until_success(self) -> None:
        attempts = iter([
            urllib.error.HTTPError("https://api", 502, "bad gateway", None, None),
            urllib.error.URLError(TimeoutError("timed out")),
            io.BytesIO(json.dumps({"ok": True}).encode("utf-8")),
        ])

        def urlopen(request: Any, timeout: float) -> Any:
            value = next(attempts)
            if isinstance(value, Exception):
                raise value
            return value

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(value, {"ok": True})
        self.assertEqual(sleeps, [2.0, 2.5])

    def test_client_rejection_never_retries(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 403, "forbidden", None, None)

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(reconciliation.ReconciliationError, "HTTP 403"):
                reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_allowed_not_found_returns_none_without_retry(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 404, "not found", None, None)

        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            value = reconciliation.request_json(
                "https://api", {}, allow_not_found=True, sleep=lambda _: None
            )
        self.assertIsNone(value)
        self.assertEqual(len(calls), 1)

    def test_persistent_transient_failure_stays_bounded(self) -> None:
        calls: list[int] = []

        def urlopen(request: Any, timeout: float) -> Any:
            calls.append(1)
            raise urllib.error.HTTPError("https://api", 503, "unavailable", None, None)

        sleeps: list[float] = []
        with mock.patch.object(reconciliation.urllib.request, "urlopen", urlopen):
            with self.assertRaisesRegex(
                reconciliation.ReconciliationError,
                f"after {reconciliation.REQUEST_ATTEMPTS} attempts",
            ):
                reconciliation.request_json("https://api", {}, sleep=sleeps.append)
        self.assertEqual(len(calls), reconciliation.REQUEST_ATTEMPTS)
        self.assertEqual(len(sleeps), reconciliation.REQUEST_ATTEMPTS - 1)


class VerifySettleTest(unittest.TestCase):
    def test_verify_polls_until_the_publication_is_observable(self) -> None:
        results = iter([
            reconciliation.Reconciliation(True, None),
            reconciliation.Reconciliation(True, None),
            reconciliation.Reconciliation(False, "remote01"),
        ])
        sleeps: list[float] = []
        result = reconciliation.settle_publication(
            lambda: next(results),
            verify=True,
            attempts=6,
            delay_seconds=10.0,
            sleep=sleeps.append,
        )
        self.assertFalse(result.publish)
        self.assertEqual(result.remote_id, "remote01")
        self.assertEqual(sleeps, [10.0, 10.0])

    def test_verify_fails_closed_when_never_observable(self) -> None:
        sleeps: list[float] = []
        with self.assertRaisesRegex(
            reconciliation.ReconciliationError, "not observable"
        ):
            reconciliation.settle_publication(
                lambda: reconciliation.Reconciliation(True, None),
                verify=True,
                attempts=3,
                delay_seconds=5.0,
                sleep=sleeps.append,
            )
        self.assertEqual(sleeps, [5.0, 5.0])

    def test_non_verify_inspects_exactly_once(self) -> None:
        calls: list[int] = []

        def inspector() -> reconciliation.Reconciliation:
            calls.append(1)
            return reconciliation.Reconciliation(True, None)

        result = reconciliation.settle_publication(
            inspector,
            verify=False,
            attempts=6,
            delay_seconds=10.0,
            sleep=lambda _: self.fail("the non-verify path must not wait"),
        )
        self.assertTrue(result.publish)
        self.assertEqual(len(calls), 1)


if __name__ == "__main__":
    unittest.main()
