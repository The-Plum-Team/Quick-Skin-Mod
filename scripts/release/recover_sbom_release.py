#!/usr/bin/env python3
"""Recover only a missing SBOM serial number while retaining a tested immutable release."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

from artifact_manifest import load_artifact_manifest
from bounded_zip import ExtractionLimits, extract_bounded_zip
from feature_coverage_github import Api, _get
from generate_sbom import SBOM_RELATIVE_PATH, build_cyclonedx, canonical_bytes, stage_sbom
from matrix import gha_matrix, load_matrix, select_release_target
from release_identity import derive, validate_changelog
from verify_release import verify_staged_manifest

WORKFLOW = ".github/workflows/release.yml"
SHA = re.compile(r"[0-9a-f]{40}")
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
# Every other tracked path must still equal the original tagged source. This includes the
# complete production/build/harness inputs, scenario contract and original release workflow.
METADATA_REPAIR_PATHS = frozenset({
    "RELEASING.md",
    "docs/ai/WORKFLOW.md",
    "release/github-governance.json",
    ".github/workflows/release-recovery.yml",
    "scripts/release/generate_sbom.py",
    "scripts/release/github_governance.py",
    "scripts/release/recover_sbom_release.py",
    "scripts/release/tests/test_sbom.py",
    "scripts/release/tests/test_github_governance.py",
    "scripts/release/tests/test_recover_sbom_release.py",
    "scripts/ci/tests/test_workflow_security.py",
})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def git(*arguments: str) -> str:
    return subprocess.check_output(
        ["git", *arguments], cwd=ROOT, text=True, stderr=subprocess.DEVNULL,
    ).strip()


def check_source_diff(paths: list[str]) -> None:
    require(bool(paths) and len(paths) == len(set(paths)), "empty or duplicate recovery diff")
    require(set(paths) <= METADATA_REPAIR_PATHS,
            "recovery cannot cross changes outside the SBOM metadata repair")
    require("scripts/release/generate_sbom.py" in paths, "source has no SBOM generator repair")


def authenticate_identity(api: Api, tag: str, policy_sha: str) -> tuple[Any, dict[str, Any], str]:
    require(isinstance(policy_sha, str) and SHA.fullmatch(policy_sha) is not None,
            "invalid protected implementation SHA")
    require(git("rev-parse", "HEAD") == policy_sha == api.current_sha(),
            "recovery must execute the exact current protected master implementation")
    require(not git("status", "--porcelain", "--untracked-files=no"), "tracked source is dirty")
    matrix_path = ROOT / "release" / "release-matrix.json"
    complete = load_matrix(matrix_path)
    require(complete["schema_version"] == 3 and complete["project"]["release_branch"] == "master",
            "recovery requires the shared protected source")
    identities = [derive(matrix_path, complete, target=row["artifact_version"])
                  for row in complete["artifacts"]]
    matches = {identity.minecraft_versions[0]: identity for identity in identities if identity.tag == tag}
    require(len(matches) == 1, "recovery tag is not a current canonical target identity")
    identity = next(iter(matches.values()))
    validate_changelog(ROOT / "CHANGELOG.md", identity.mod_version, publication=True)
    record = api.json(f"git/ref/tags/{identity.tag}")
    require(record.get("ref") == f"refs/tags/{identity.tag}", "remote tag identity differs")
    obj = record.get("object", {})
    for _ in range(4):
        require(isinstance(obj.get("sha"), str) and SHA.fullmatch(obj["sha"]) is not None,
                "malformed remote tag object")
        if obj.get("type") == "commit":
            break
        require(obj.get("type") == "tag", "release tag does not identify a commit")
        tagged = api.json(f"git/tags/{obj['sha']}")
        require(tagged.get("sha") == obj["sha"], "annotated tag object differs")
        obj = tagged.get("object", {})
    require(obj.get("type") == "commit", "tag nesting exceeds the recovery limit")
    source_sha = obj["sha"]
    require(git("rev-parse", f"refs/tags/{identity.tag}^{{commit}}") == source_sha,
            "local and remote immutable tag differ")
    require(git("merge-base", source_sha, policy_sha) == source_sha,
            "tagged source is not an ancestor of the protected implementation")
    check_source_diff(git("diff", "--name-only", "--no-renames", source_sha, policy_sha).splitlines())
    selected = select_release_target(complete, identity.minecraft_versions[0])
    return identity, selected, source_sha


def successful_job(jobs: list[dict[str, Any]], name: str) -> dict[str, Any]:
    matches = [job for job in jobs if job.get("name") == name]
    require(len(matches) == 1 and matches[0].get("status") == "completed"
            and matches[0].get("conclusion") == "success", f"missing successful job: {name}")
    return matches[0]


def authenticate_rehearsal(api: Api, run_id: int, artifact_id: int, identity: Any,
                           matrix: dict[str, Any], source_sha: str) -> dict[str, Any]:
    run = api.run(run_id)
    require(run.get("path") == WORKFLOW and run.get("event") == "workflow_dispatch"
            and run.get("head_branch") == identity.branch and run.get("head_sha") == source_sha
            and run.get("repository", {}).get("full_name") == api.repository
            and run.get("head_repository", {}).get("full_name") == api.repository
            and run.get("status") == "completed" and run.get("conclusion") == "success",
            "source must be a successful release rehearsal of the exact tagged commit")
    jobs = [job for page in api.jobs(run) for job in page["jobs"]]
    build = successful_job(jobs, "Build immutable release bundle")
    steps = {step.get("name"): step for step in build.get("steps", [])}
    for name in ("Validate requested release identity", "Stage and verify all production artifacts",
                 "Prove first and second build bytes are identical"):
        require(steps.get(name, {}).get("conclusion") == "success", f"rehearsal lacks {name}")
    expected_runtime = {f"{row['id']} - packaged release behavior"
                        for row in gha_matrix(matrix, "runtime", identity.mod_version)["include"]}
    actual_runtime = {job["name"] for job in jobs if job.get("name", "").endswith(" - packaged release behavior")}
    require(actual_runtime == expected_runtime, "rehearsal runtime inventory differs")
    for name in sorted(expected_runtime):
        successful_job(jobs, name)
    for name in ("Stage exact GitHub Release assets", "Publish immutable GitHub Release"):
        matches = [job for job in jobs if job.get("name") == name]
        require(len(matches) == 1 and matches[0].get("conclusion") == "skipped",
                "source rehearsal attempted publication")
    artifact = api.artifact(artifact_id)
    owner = artifact.get("workflow_run", {})
    require(artifact.get("name") == f"release-{identity.release_id}"
            and artifact.get("expired") is False
            and owner.get("id") == run_id and owner.get("head_sha") == source_sha
            and type(artifact.get("size_in_bytes")) is int
            and 0 < artifact["size_in_bytes"] <= MAX_ARCHIVE_BYTES
            and isinstance(artifact.get("digest"), str)
            and re.fullmatch(r"sha256:[0-9a-f]{64}", artifact["digest"]) is not None,
            "release archive has a foreign identity, expired or invalid bytes")
    inventory = [item for item in api.artifacts(run_id=run_id)
                 if item.get("name") == artifact["name"]]
    require(len(inventory) == 1 and inventory[0].get("id") == artifact_id,
            "source has an ambiguous release archive")
    return artifact


def authenticate_required_gates(api: Api, source_sha: str) -> None:
    for workflow, gate in (("build-gate.yml", "Build and verify"),
                           ("on-demand-e2e.yml", "Packaged E2E gate")):
        record = api.json(f"actions/workflows/{workflow}/runs?head_sha={source_sha}&per_page=100")
        runs = record.get("workflow_runs")
        require(isinstance(runs, list) and type(record.get("total_count")) is int
                and len(runs) == record["total_count"] <= 100, "incomplete source gate inventory")
        candidates = [run for run in runs if run.get("head_sha") == source_sha
                      and run.get("event") in {"push", "workflow_dispatch"}
                      and run.get("head_branch") == "master"
                      and run.get("path") == f".github/workflows/{workflow}"
                      and run.get("head_repository", {}).get("full_name") == api.repository]
        require(bool(candidates), f"tagged source has no protected {gate}")
        run = max(candidates, key=lambda item: item["id"])
        require(run.get("status") == "completed" and run.get("conclusion") == "success",
                f"tagged source's latest {gate} did not pass")
        successful_job([job for page in api.jobs(run) for job in page["jobs"]], gate)


def validate_inventory(stage: Path, manifest: dict[str, Any]) -> None:
    expected = {"artifacts.json", SBOM_RELATIVE_PATH.as_posix()}
    for record in manifest["artifacts"]:
        expected.update((record["path"], record["harness"]["path"]))
    actual = set()
    for path in stage.rglob("*"):
        require(not path.is_symlink(), "release stage contains a symbolic link")
        if path.is_file():
            actual.add(path.relative_to(stage).as_posix())
    require(actual == expected, "release stage contains missing or unexpected files")


def repair_document(original: bytes, corrected: dict[str, Any]) -> bytes:
    without_serial = {key: value for key, value in corrected.items() if key != "serialNumber"}
    original_expected = (json.dumps(without_serial, ensure_ascii=False, separators=(",", ":"),
                                    sort_keys=True) + "\n").encode("utf-8")
    require(original == original_expected, "repair would change more than a missing serialNumber")
    return canonical_bytes(corrected)


def verify_provenance(api: Api, stage: Path, manifest: dict[str, Any], tag: str, source_sha: str) -> None:
    for record in manifest["artifacts"]:
        raw = subprocess.check_output([
            "gh", "attestation", "verify", str(stage / record["path"]), "--repo", api.repository,
            "--source-digest", source_sha, "--source-ref", f"refs/tags/{tag}",
            "--signer-workflow", f"{api.repository}/{WORKFLOW}", "--signer-digest", source_sha,
            "--deny-self-hosted-runners", "--format", "json",
            "--jq", "[.[] | .verificationResult.signature.certificate | {buildTrigger}]",
        ], stderr=subprocess.DEVNULL, timeout=120)
        require(0 < len(raw) <= 4 * 1024 * 1024, "invalid attestation result size")
        results = json.loads(raw)
        require(isinstance(results, list) and bool(results), "no verified production provenance")
        require(any(item.get("buildTrigger") == "push" for item in results),
                "production provenance did not originate from the canonical tag push")


def verify_stage(stage: Path, identity: Any, matrix: dict[str, Any], source_sha: str) -> dict[str, Any]:
    matrix_path = ROOT / "release" / "release-matrix.json"
    manifest_path = stage / "artifacts.json"
    manifest = load_artifact_manifest(
        manifest_path, repository=ROOT, matrix_path=matrix_path, matrix=matrix, stage=stage,
        expected_mod_version=identity.mod_version, expected_commit=source_sha,
        expected_release=identity.manifest(),
    )
    validate_inventory(stage, manifest)
    verify_staged_manifest(ROOT, stage, manifest_path, manifest, matrix, matrix_path,
                           identity.mod_version, source_sha, target=identity.minecraft_versions[0])
    return manifest


def prepare(api: Api, stage: Path, run_id: int, artifact_id: int, identity: Any,
            matrix: dict[str, Any], source_sha: str) -> str:
    artifact = authenticate_rehearsal(api, run_id, artifact_id, identity, matrix, source_sha)
    authenticate_required_gates(api, source_sha)
    raw = _get(api.prefix + f"actions/artifacts/{artifact_id}/zip", maximum=MAX_ARCHIVE_BYTES)
    require(len(raw) == artifact["size_in_bytes"]
            and "sha256:" + hashlib.sha256(raw).hexdigest() == artifact["digest"],
            "downloaded release archive differs from its authenticated metadata")
    stage.parent.mkdir(parents=True, exist_ok=True)
    archive = stage.parent / "recovery-source.zip"
    with archive.open("xb") as stream:
        stream.write(raw)
    extract_bounded_zip(archive, stage, ExtractionLimits(
        archive_bytes=MAX_ARCHIVE_BYTES, entries=64, total_bytes=MAX_ARCHIVE_BYTES,
        file_bytes=32 * 1024 * 1024,
    ))
    matrix_path = ROOT / "release" / "release-matrix.json"
    manifest_path = stage / "artifacts.json"
    manifest = load_artifact_manifest(
        manifest_path, repository=ROOT, matrix_path=matrix_path, matrix=matrix, stage=stage,
        expected_mod_version=identity.mod_version, expected_commit=source_sha,
        expected_release=identity.manifest(),
    )
    validate_inventory(stage, manifest)
    corrected = build_cyclonedx(ROOT, matrix_path, matrix, manifest, stage,
                                expected_commit=source_sha, expected_release=identity.manifest())
    expected = repair_document((stage / SBOM_RELATIVE_PATH).read_bytes(), corrected)
    verify_provenance(api, stage, manifest, identity.tag, source_sha)
    manifest["sbom"] = stage_sbom(ROOT, matrix_path, stage, matrix, manifest)
    require((stage / SBOM_RELATIVE_PATH).read_bytes() == expected, "SBOM regeneration differs")
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    verify_stage(stage, identity, matrix, source_sha)
    return hashlib.sha256(manifest_path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "verify"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source-run-id", type=int)
    parser.add_argument("--source-artifact-id", type=int)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    args = parser.parse_args()
    try:
        require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
                and os.environ.get("GITHUB_REF") == "refs/heads/master",
                "recovery requires an explicit dispatch from protected master")
        api = Api(os.environ["GITHUB_REPOSITORY"])
        identity, matrix, source_sha = authenticate_identity(api, args.tag, os.environ["GITHUB_SHA"])
        stage = (ROOT / args.stage).resolve()
        require(stage == ROOT / "build" / "release", "unexpected recovery stage")
        if args.command == "prepare":
            require(type(args.source_run_id) is int and args.source_run_id > 0
                    and type(args.source_artifact_id) is int and args.source_artifact_id > 0,
                    "recovery needs exact source run and artifact IDs")
            digest = prepare(api, stage, args.source_run_id, args.source_artifact_id,
                             identity, matrix, source_sha)
        else:
            require(isinstance(args.manifest_sha256, str)
                    and re.fullmatch(r"[0-9a-f]{64}", args.manifest_sha256) is not None,
                    "missing authenticated recovery manifest hash")
            digest = hashlib.sha256((stage / "artifacts.json").read_bytes()).hexdigest()
            require(digest == args.manifest_sha256, "recovered manifest differs from the prepared bundle")
            verify_stage(stage, identity, matrix, source_sha)
        require(api.current_sha() == os.environ["GITHUB_SHA"], "master advanced during recovery validation")
        output = {
            "tag": identity.tag, "release_id": identity.release_id,
            "target": identity.minecraft_versions[0], "source_sha": source_sha,
            "manifest_sha256": digest,
            "publications": json.dumps(gha_matrix(matrix, "publications", identity.mod_version),
                                       separators=(",", ":")),
        }
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
                for key, value in output.items():
                    stream.write(f"{key}={value}\n")
        print(json.dumps(output, sort_keys=True))
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"SBOM release recovery failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
