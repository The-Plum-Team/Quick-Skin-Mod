#!/usr/bin/env python3
"""Create and verify an exact nonvisual anchor-continuation certificate."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
KIND = "quick-skin-visual-anchor-nonimpact"
VERDICT = "visual-review-not-required"
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
BRANCH = re.compile(r"^[A-Za-z0-9._/-]{1,255}$")
CERTIFICATE_KEYS = {
    "schema_version",
    "kind",
    "verdict",
    "master_source_sha",
    "anchor_branch",
    "anchor_source_branch",
    "anchor_base_sha",
    "anchor_source_sha",
    "anchor_target_sha",
    "build_run_id",
    "e2e_run_id",
    "impact_policy_sha256",
    "impact",
}
IMPACT_KEYS = {"schema_version", "scope", "review_required", "paths"}
MAX_PATHS = 100


class CertificationError(ValueError):
    """Raised when a nonvisual continuation is not exact and fail-closed."""


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA.fullmatch(value) is None:
        raise CertificationError(f"{label} is not a commit SHA")
    return value


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise CertificationError(f"{label} is not a SHA-256")
    return value


def _branch(value: Any, label: str) -> str:
    if (
        not isinstance(value, str)
        or BRANCH.fullmatch(value) is None
        or ".." in value
        or "//" in value
        or value.startswith("/")
        or value.endswith("/")
    ):
        raise CertificationError(f"{label} is invalid")
    return value


def _run_id(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CertificationError(f"{label} is invalid")
    return value


def _canonical_path(value: Any) -> str:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > 512
        or value.startswith("/")
        or "\\" in value
        or any(part in {"", ".", ".."} for part in value.split("/"))
        or any(ord(character) < 32 or ord(character) == 127 for character in value)
    ):
        raise CertificationError("impact path is invalid")
    return value


def normalize_impact(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != IMPACT_KEYS:
        raise CertificationError("impact manifest has an unexpected schema")
    if value.get("schema_version") != 1:
        raise CertificationError("impact manifest version is unsupported")
    if value.get("scope") != "replicated-port":
        raise CertificationError("impact manifest has the wrong scope")
    if value.get("review_required") is not False:
        raise CertificationError("impact manifest still requires visual review")
    raw_paths = value.get("paths")
    if (
        not isinstance(raw_paths, list)
        or not 1 <= len(raw_paths) <= MAX_PATHS
    ):
        raise CertificationError("impact manifest has invalid path coverage")
    paths = [_canonical_path(path) for path in raw_paths]
    if paths != sorted(set(paths)):
        raise CertificationError("impact paths are not sorted and unique")
    return {
        "schema_version": 1,
        "scope": "replicated-port",
        "review_required": False,
        "paths": paths,
    }


def validate_certificate(
    value: Any,
    *,
    expected_master_sha: str | None = None,
    expected_anchor_branch: str | None = None,
) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != CERTIFICATE_KEYS:
        raise CertificationError("certificate has an unexpected schema")
    if value.get("schema_version") != SCHEMA_VERSION:
        raise CertificationError("certificate version is unsupported")
    if value.get("kind") != KIND or value.get("verdict") != VERDICT:
        raise CertificationError("certificate kind or verdict is invalid")
    normalized = {
        "schema_version": SCHEMA_VERSION,
        "kind": KIND,
        "verdict": VERDICT,
        "master_source_sha": _sha(value.get("master_source_sha"), "master_source_sha"),
        "anchor_branch": _branch(value.get("anchor_branch"), "anchor_branch"),
        "anchor_source_branch": _branch(
            value.get("anchor_source_branch"), "anchor_source_branch"
        ),
        "anchor_base_sha": _sha(value.get("anchor_base_sha"), "anchor_base_sha"),
        "anchor_source_sha": _sha(
            value.get("anchor_source_sha"), "anchor_source_sha"
        ),
        "anchor_target_sha": _sha(
            value.get("anchor_target_sha"), "anchor_target_sha"
        ),
        "build_run_id": _run_id(value.get("build_run_id"), "build_run_id"),
        "e2e_run_id": _run_id(value.get("e2e_run_id"), "e2e_run_id"),
        "impact_policy_sha256": _sha256(
            value.get("impact_policy_sha256"), "impact_policy_sha256"
        ),
        "impact": normalize_impact(value.get("impact")),
    }
    if not normalized["anchor_source_branch"].startswith("automation/sync/"):
        raise CertificationError("anchor source is not an automatic synchronization branch")
    if expected_master_sha is not None and normalized["master_source_sha"] != _sha(
        expected_master_sha, "expected_master_sha"
    ):
        raise CertificationError("certificate belongs to another master commit")
    if (
        expected_anchor_branch is not None
        and normalized["anchor_branch"]
        != _branch(expected_anchor_branch, "expected_anchor_branch")
    ):
        raise CertificationError("certificate belongs to another anchor branch")
    return normalized


def create_certificate(**values: Any) -> dict[str, Any]:
    return validate_certificate(
        {
            "schema_version": SCHEMA_VERSION,
            "kind": KIND,
            "verdict": VERDICT,
            **values,
        }
    )


def _load(path: Path) -> Any:
    try:
        metadata = path.stat()
        if not path.is_file() or metadata.st_size <= 0 or metadata.st_size > 1048576:
            raise OSError("file is not bounded")
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CertificationError(f"cannot read certificate input: {exc}") from exc


def _write_new(path: Path, value: dict[str, Any]) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(value, handle, sort_keys=True, separators=(",", ":"))
        handle.write("\n")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--master-source-sha", required=True)
    create.add_argument("--anchor-branch", required=True)
    create.add_argument("--anchor-source-branch", required=True)
    create.add_argument("--anchor-base-sha", required=True)
    create.add_argument("--anchor-source-sha", required=True)
    create.add_argument("--anchor-target-sha", required=True)
    create.add_argument("--build-run-id", required=True, type=int)
    create.add_argument("--e2e-run-id", required=True, type=int)
    create.add_argument("--impact-policy-sha256", required=True)
    create.add_argument("--impact-manifest", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--certificate", required=True, type=Path)
    verify.add_argument("--expected-master-sha", required=True)
    verify.add_argument("--expected-anchor-branch", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "create":
            certificate = create_certificate(
                master_source_sha=args.master_source_sha,
                anchor_branch=args.anchor_branch,
                anchor_source_branch=args.anchor_source_branch,
                anchor_base_sha=args.anchor_base_sha,
                anchor_source_sha=args.anchor_source_sha,
                anchor_target_sha=args.anchor_target_sha,
                build_run_id=args.build_run_id,
                e2e_run_id=args.e2e_run_id,
                impact_policy_sha256=args.impact_policy_sha256,
                impact=normalize_impact(_load(args.impact_manifest)),
            )
            _write_new(args.output, certificate)
        else:
            certificate = validate_certificate(
                _load(args.certificate),
                expected_master_sha=args.expected_master_sha,
                expected_anchor_branch=args.expected_anchor_branch,
            )
            print(json.dumps(certificate, sort_keys=True, separators=(",", ":")))
    except (CertificationError, OSError) as exc:
        print(f"Visual nonimpact certification failed: {exc}")
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
