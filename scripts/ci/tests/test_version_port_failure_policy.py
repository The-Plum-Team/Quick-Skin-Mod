from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from version_port_failure_policy import classify_failure_log  # noqa: E402


class VersionPortFailurePolicyTest(unittest.TestCase):
    def test_dependency_verification_is_not_source_repairable(self) -> None:
        result = classify_failure_log(
            "Dependency verification failed for configuration ':runtimeClasspath'"
        )

        self.assertEqual("supply-chain", result.disposition)
        self.assertEqual(("gradle-dependency-verification",), result.signals)

    def test_observed_minecraft_download_outage_is_transient(self) -> None:
        result = classify_failure_log(
            "resources.download.minecraft.net: Failed to establish a new connection: "
            "[Errno 101] Network is unreachable"
        )

        self.assertEqual("transient-infrastructure", result.disposition)
        self.assertEqual(("network-unreachable",), result.signals)

    def test_observed_forge_maven_reset_is_transient(self) -> None:
        result = classify_failure_log(
            "Could not GET forge installer: Connection reset by peer"
        )

        self.assertEqual("transient-infrastructure", result.disposition)
        self.assertEqual(("connection-reset",), result.signals)

    def test_dns_proxy_rate_limit_and_runner_failures_are_transient(self) -> None:
        samples = (
            "curl: (6) Could not resolve host: maven.minecraftforge.net",
            "urllib.error.HTTPError: HTTP Error 503: Service Unavailable",
            "remote API returned 429 Too Many Requests",
            "write failed: No space left on device",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertEqual(
                    "transient-infrastructure",
                    classify_failure_log(sample).disposition,
                )

    def test_gameplay_timeout_and_connection_refusal_remain_repairable(self) -> None:
        samples = (
            "AssertionError: first-person hand did not become visible",
            "Timed out waiting for screenshot checkpoint",
            "Connection refused while contacting the local game server",
            "Process completed with exit code 1.",
            "",
        )
        for sample in samples:
            with self.subTest(sample=sample):
                self.assertEqual(
                    "repairable", classify_failure_log(sample).disposition
                )

    def test_supply_chain_rejection_takes_precedence_over_network_noise(self) -> None:
        result = classify_failure_log(
            "Connection reset by peer\n"
            "Dependency verification failed for configuration ':compileClasspath'"
        )

        self.assertEqual("supply-chain", result.disposition)


if __name__ == "__main__":
    unittest.main()
