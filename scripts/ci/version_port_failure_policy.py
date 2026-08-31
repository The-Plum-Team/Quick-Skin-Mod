#!/usr/bin/env python3
"""Classify trusted version-port gate failures before any AI repair is started.

The policy is intentionally conservative: only explicit supply-chain rejections and
well-known external infrastructure failures bypass source repair. Everything unknown
remains repairable so a genuine implementation failure keeps the existing bounded
repair path.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path


POLICY_VERSION = 1
MAX_LOG_BYTES = 8 * 1024 * 1024
SUPPLY_CHAIN_MARKER = "dependency verification failed for configuration"
TRANSIENT_INFRASTRUCTURE_MARKERS = {
    "bad-gateway": (
        "http error 502: bad gateway",
        "http error 503: service unavailable",
        "http error 504: gateway timeout",
        "tunnel connection failed: 502 bad gateway",
    ),
    "connection-reset": (
        "connection reset by peer",
        "econnreset",
    ),
    "dns-failure": (
        "temporary failure in name resolution",
        "could not resolve host",
        "name or service not known",
        "nodename nor servname provided, or not known",
        "eai_again",
    ),
    "network-unreachable": (
        "network is unreachable",
        "failed to establish a new connection",
    ),
    "runner-storage": ("no space left on device",),
    "service-rate-limit": (
        "api rate limit exceeded",
        "429 too many requests",
    ),
    "tls-timeout": ("tls handshake timeout",),
}


class FailurePolicyError(ValueError):
    """Raised when failure evidence cannot be read safely."""


@dataclass(frozen=True)
class Classification:
    disposition: str
    signals: tuple[str, ...]

    def manifest(self) -> dict[str, object]:
        return {
            "schema_version": POLICY_VERSION,
            "disposition": self.disposition,
            "signals": list(self.signals),
        }


def classify_failure_log(log: str) -> Classification:
    """Return a fail-closed disposition from bounded, untrusted gate log text."""

    normalized = log.casefold()
    if SUPPLY_CHAIN_MARKER in normalized:
        return Classification("supply-chain", ("gradle-dependency-verification",))

    signals = tuple(
        signal
        for signal, markers in TRANSIENT_INFRASTRUCTURE_MARKERS.items()
        if any(marker in normalized for marker in markers)
    )
    if signals:
        return Classification("transient-infrastructure", signals)
    return Classification("repairable", ("source-or-unknown",))


def read_failure_log(path: Path) -> str:
    try:
        payload = path.read_bytes()
    except OSError as exc:
        raise FailurePolicyError(f"cannot read failure log: {exc}") from exc
    if len(payload) > MAX_LOG_BYTES:
        raise FailurePolicyError(
            f"failure log exceeds the {MAX_LOG_BYTES}-byte policy bound"
        )
    return payload.decode("utf-8", errors="replace")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        result = classify_failure_log(read_failure_log(args.log))
    except FailurePolicyError as exc:
        print(f"Version-port failure classification failed: {exc}")
        return 2
    print(json.dumps(result.manifest(), sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
