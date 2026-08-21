#!/usr/bin/env python3
"""Create and verify a fail-closed semantic certification for the 1.20.1 anchor.

The certificate is intentionally small. It binds a clean, unpaired Fabric/Forge review to the
exact synchronization source, tested anchor candidate, merged anchor head, protected review
implementation, and scenario contract. GitHub workflows independently authenticate those Git and
Actions identities before creating or consuming it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))

from check_visual_review import ReviewError, load, validate  # noqa: E402


SCHEMA_VERSION = 1
CERTIFICATE_KIND = "quick-skin-visual-anchor-certification"
ANCHOR_VERSION = "1.20.1"
ANCHOR_LOADERS = ("fabric", "forge")
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
RELEASE_BRANCH = re.compile(
    r"^[a-z0-9]+(?:-and-[a-z0-9]+)*-[0-9]+(?:\.[0-9]+)+$"
)
SYNC_BRANCH = re.compile(r"^automation/sync/[A-Za-z0-9._/-]+$")
PROOF_KEYS = {
    "schema_version",
    "source_run_id",
    "source_branch",
    "source_sha",
    "master_source_sha",
    "implementation_sha",
    "matrix_kind",
    "review_mode",
    "compatibility_impact",
    "scenario_contract_sha256",
    "artifact_inventory",
    "job_graph",
    "visual_reference",
    "manifest_sha256",
    "frame_count",
    "image_count",
    "image_bytes",
}
COMPATIBILITY_IMPACT_KEYS = {
    "schema_version",
    "compatibility_required",
    "paths",
    "impact_paths",
}
CERTIFICATE_KEYS = {
    "schema_version",
    "kind",
    "verdict",
    "version",
    "loaders",
    "anchor_branch",
    "source_branch",
    "source_run_id",
    "master_source_sha",
    "anchor_source_sha",
    "anchor_target_sha",
    "implementation_sha",
    "scenario_contract_sha256",
    "proof_sha256",
    "manifest_sha256",
    "report_sha256",
    "frame_count",
    "capture_count",
}


class CertificationError(ValueError):
    """Raised when semantic anchor evidence cannot be certified."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
    except OSError as exc:
        raise CertificationError(f"cannot hash {path}: {exc}") from exc
    return digest.hexdigest()


def _require_sha(value: Any, field: str) -> str:
    if not isinstance(value, str) or SHA.fullmatch(value) is None:
        raise CertificationError(f"{field} must be a lowercase commit SHA")
    return value


def _require_sha256(value: Any, field: str) -> str:
    if not isinstance(value, str) or SHA256.fullmatch(value) is None:
        raise CertificationError(f"{field} must be a lowercase SHA-256")
    return value


def _require_positive_integer(value: Any, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CertificationError(f"{field} must be a positive integer")
    return value


def _validate_compatibility_impact(value: Any) -> None:
    if not isinstance(value, dict) or set(value) != COMPATIBILITY_IMPACT_KEYS:
        raise CertificationError("compatibility impact has an unexpected schema")
    if value.get("schema_version") != 1:
        raise CertificationError("compatibility impact schema version is unsupported")
    compatibility_required = value.get("compatibility_required")
    if not isinstance(compatibility_required, bool):
        raise CertificationError("compatibility impact decision must be a boolean")
    paths = value.get("paths")
    impact_paths = value.get("impact_paths")
    for field, entries in (("paths", paths), ("impact_paths", impact_paths)):
        if (
            not isinstance(entries, list)
            or len(entries) > 200
            or any(
                not isinstance(entry, str) or not entry or len(entry) > 512
                for entry in entries
            )
            or entries != sorted(set(entries))
        ):
            raise CertificationError(f"compatibility impact {field} is invalid")
    if any(path not in paths for path in impact_paths):
        raise CertificationError("compatibility impact paths are not a subset")
    if compatibility_required != bool(impact_paths):
        raise CertificationError("compatibility impact decision is incoherent")


def _anchor_capture_sets(manifest: Any) -> dict[str, set[str]]:
    if not isinstance(manifest, list) or not manifest:
        raise CertificationError("semantic anchor manifest must be a non-empty array")
    expected = {f"{loader}-{ANCHOR_VERSION}" for loader in ANCHOR_LOADERS}
    captures = {artifact: set() for artifact in expected}
    for index, item in enumerate(manifest):
        if not isinstance(item, dict):
            raise CertificationError(f"semantic anchor manifest entry {index} is invalid")
        if "reference_path" in item or "reference_label" in item:
            raise CertificationError("semantic anchor manifest must not contain a reference")
        label = item.get("label")
        capture_id = item.get("capture_id")
        if not isinstance(label, str) or not isinstance(capture_id, str):
            raise CertificationError(f"semantic anchor entry {index} has no identity")
        artifact = label.split("/", 1)[0]
        if artifact not in expected:
            raise CertificationError(
                f"semantic anchor contains non-anchor artifact {artifact!r}"
            )
        if capture_id in captures[artifact]:
            raise CertificationError(
                f"semantic anchor duplicates {artifact}/{capture_id}"
            )
        captures[artifact].add(capture_id)
    if not all(captures.values()) or len(
        {frozenset(capture_ids) for capture_ids in captures.values()}
    ) != 1:
        raise CertificationError(
            "semantic anchor requires identical complete Fabric and Forge capture sets"
        )
    return captures


def create_certificate(
    *,
    proof: Any,
    manifest: Any,
    report: Any,
    proof_path: Path,
    manifest_path: Path,
    report_path: Path,
    anchor_branch: str,
    anchor_target_sha: str,
) -> dict[str, Any]:
    if not isinstance(proof, dict) or set(proof) != PROOF_KEYS:
        raise CertificationError("curation proof has an unexpected schema")
    if proof.get("schema_version") != 5:
        raise CertificationError("curation proof schema version is not certifiable")
    if proof.get("review_mode") != "anchor-semantic":
        raise CertificationError("only an anchor-semantic review can be certified")
    if proof.get("visual_reference") is not None:
        raise CertificationError("semantic anchor proof must not contain a visual reference")
    _validate_compatibility_impact(proof.get("compatibility_impact"))
    source_branch = proof.get("source_branch")
    if not isinstance(source_branch, str) or SYNC_BRANCH.fullmatch(source_branch) is None:
        raise CertificationError("certification requires an automation synchronization source")
    if not isinstance(anchor_branch, str) or RELEASE_BRANCH.fullmatch(anchor_branch) is None:
        raise CertificationError("anchor branch does not satisfy the release naming contract")

    source_run_id = _require_positive_integer(proof.get("source_run_id"), "source_run_id")
    source_sha = _require_sha(proof.get("source_sha"), "source_sha")
    master_source_sha = _require_sha(
        proof.get("master_source_sha"), "master_source_sha"
    )
    implementation_sha = _require_sha(
        proof.get("implementation_sha"), "implementation_sha"
    )
    target_sha = _require_sha(anchor_target_sha, "anchor_target_sha")
    scenario_contract_sha256 = _require_sha256(
        proof.get("scenario_contract_sha256"), "scenario_contract_sha256"
    )

    manifest_sha256 = _sha256(manifest_path)
    if manifest_sha256 != proof.get("manifest_sha256"):
        raise CertificationError("manifest digest disagrees with the curation proof")
    captures = _anchor_capture_sets(manifest)
    if proof.get("frame_count") != len(manifest):
        raise CertificationError("manifest frame count disagrees with the curation proof")
    try:
        verdicts = validate(manifest, report, require_paired=False)
    except ReviewError as exc:
        raise CertificationError(f"normalized review report is invalid: {exc}") from exc
    if any(
        not verdict["semantic_valid"]
        or verdict["matches_reference"] is not None
        or verdict["defect"]
        for verdict in verdicts
    ):
        raise CertificationError("semantic anchor report is not completely clean")

    capture_count = len(next(iter(captures.values())))
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": CERTIFICATE_KIND,
        "verdict": "certified",
        "version": ANCHOR_VERSION,
        "loaders": list(ANCHOR_LOADERS),
        "anchor_branch": anchor_branch,
        "source_branch": source_branch,
        "source_run_id": source_run_id,
        "master_source_sha": master_source_sha,
        "anchor_source_sha": source_sha,
        "anchor_target_sha": target_sha,
        "implementation_sha": implementation_sha,
        "scenario_contract_sha256": scenario_contract_sha256,
        "proof_sha256": _sha256(proof_path),
        "manifest_sha256": manifest_sha256,
        "report_sha256": _sha256(report_path),
        "frame_count": len(verdicts),
        "capture_count": capture_count,
    }


def validate_certificate(
    certificate: Any,
    *,
    expected_master_sha: str | None = None,
    expected_anchor_branch: str | None = None,
) -> dict[str, Any]:
    if not isinstance(certificate, dict) or set(certificate) != CERTIFICATE_KEYS:
        raise CertificationError("visual anchor certificate has an unexpected schema")
    if certificate.get("schema_version") != SCHEMA_VERSION:
        raise CertificationError("visual anchor certificate schema version is unsupported")
    if certificate.get("kind") != CERTIFICATE_KIND:
        raise CertificationError("visual anchor certificate kind is invalid")
    if certificate.get("verdict") != "certified":
        raise CertificationError("visual anchor certificate is not green")
    if certificate.get("version") != ANCHOR_VERSION:
        raise CertificationError("visual anchor certificate has the wrong version")
    if certificate.get("loaders") != list(ANCHOR_LOADERS):
        raise CertificationError("visual anchor certificate has the wrong loaders")

    anchor_branch = certificate.get("anchor_branch")
    source_branch = certificate.get("source_branch")
    if not isinstance(anchor_branch, str) or RELEASE_BRANCH.fullmatch(anchor_branch) is None:
        raise CertificationError("visual anchor certificate branch is invalid")
    if not isinstance(source_branch, str) or SYNC_BRANCH.fullmatch(source_branch) is None:
        raise CertificationError("visual anchor certificate source branch is invalid")
    for field in (
        "master_source_sha",
        "anchor_source_sha",
        "anchor_target_sha",
        "implementation_sha",
    ):
        _require_sha(certificate.get(field), field)
    for field in (
        "scenario_contract_sha256",
        "proof_sha256",
        "manifest_sha256",
        "report_sha256",
    ):
        _require_sha256(certificate.get(field), field)
    for field in ("source_run_id", "frame_count", "capture_count"):
        _require_positive_integer(certificate.get(field), field)
    if certificate["frame_count"] != certificate["capture_count"] * len(ANCHOR_LOADERS):
        raise CertificationError("visual anchor certificate coverage is incomplete")
    if expected_master_sha is not None and certificate["master_source_sha"] != _require_sha(
        expected_master_sha, "expected_master_sha"
    ):
        raise CertificationError("visual anchor certificate belongs to another master commit")
    if (
        expected_anchor_branch is not None
        and certificate["anchor_branch"] != expected_anchor_branch
    ):
        raise CertificationError("visual anchor certificate belongs to another anchor branch")
    return certificate


def _write_json_new(path: Path, value: dict[str, Any]) -> None:
    destination = path.absolute()
    if destination.exists() or destination.is_symlink():
        raise CertificationError(f"certificate destination already exists: {destination}")
    temporary: Path | None = None
    try:
        parent = destination.parent.resolve(strict=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{destination.name}.", suffix=".tmp", dir=parent
        )
        temporary = Path(temporary_name)
        with os.fdopen(descriptor, "w", encoding="utf-8", closefd=True) as handle:
            json.dump(value, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, destination)
    except OSError as exc:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        raise CertificationError(f"cannot write visual anchor certificate: {exc}") from exc


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--proof", type=Path, required=True)
    create.add_argument("--manifest", type=Path, required=True)
    create.add_argument("--report", type=Path, required=True)
    create.add_argument("--anchor-branch", required=True)
    create.add_argument("--anchor-target-sha", required=True)
    create.add_argument("--output", type=Path, required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--certificate", type=Path, required=True)
    verify.add_argument("--expected-master-sha", required=True)
    verify.add_argument("--expected-anchor-branch", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "create":
            proof = load(args.proof, "curation proof")
            manifest = load(args.manifest, "review manifest")
            report = load(args.report, "normalized review report")
            certificate = create_certificate(
                proof=proof,
                manifest=manifest,
                report=report,
                proof_path=args.proof,
                manifest_path=args.manifest,
                report_path=args.report,
                anchor_branch=args.anchor_branch,
                anchor_target_sha=args.anchor_target_sha,
            )
            _write_json_new(args.output, certificate)
        else:
            certificate = validate_certificate(
                load(args.certificate, "visual anchor certificate"),
                expected_master_sha=args.expected_master_sha,
                expected_anchor_branch=args.expected_anchor_branch,
            )
        print(json.dumps(certificate, sort_keys=True, separators=(",", ":")))
        return 0
    except (CertificationError, ReviewError) as exc:
        print(f"Visual anchor certification failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
