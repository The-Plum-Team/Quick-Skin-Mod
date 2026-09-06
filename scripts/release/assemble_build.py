#!/usr/bin/env python3
"""Reverify every isolated target build before assembling the complete CI bundle."""

from __future__ import annotations

import argparse
import os
import shutil
from pathlib import Path

import artifact_manifest
import build_matrix
import matrix as release_matrix
import verify_release
from release_identity import derive as derive_release_identity


ROOT = Path(__file__).resolve().parents[2]
TARGET_PREFIX = "compiled-target-"


def assemble(repo: Path, inputs: Path, stage: Path) -> dict:
    repo = repo.resolve()
    matrix_path = repo / "release/release-matrix.json"
    data = release_matrix.load_matrix(matrix_path)
    plan = build_matrix.build_plan(data)
    mod_version = release_matrix.read_mod_version(matrix_path, data)
    commit = verify_release.git_commit(repo)
    verify_release.require(bool(commit), "assembly requires an exact Git checkout")
    if os.environ.get("GITHUB_SHA"):
        verify_release.require(commit == os.environ["GITHUB_SHA"], "assembly checkout differs from GITHUB_SHA")
    verify_release.require(inputs.is_dir() and inputs.resolve() == inputs,
                           "target bundle directory is missing or linked")
    stage = verify_release.safe_stage_dir(repo, stage)
    verify_release.require(not inputs.is_relative_to(stage) and not stage.is_relative_to(inputs),
                           "input and output bundles must be disjoint")
    expected = {TARGET_PREFIX + item["target"] for item in plan}
    verify_release.require({item.name for item in inputs.iterdir()} == expected,
                           "target bundle inventory differs from the complete matrix")

    # Validate the complete partition before copying anything into Gradle output paths.
    copies = []
    for item in plan:
        target = item["target"] if data["schema_version"] == 3 else None
        selected = release_matrix.select_release_target(data, target) if target else data
        directory = inputs / (TARGET_PREFIX + item["target"])
        verify_release.require(directory.is_dir() and directory.resolve() == directory,
                               "target bundle directory is missing or linked")
        manifest_path = directory / "artifacts.json"
        manifest = artifact_manifest.load_artifact_manifest(
            manifest_path, repository=repo, matrix_path=matrix_path, matrix=selected,
            stage=directory, expected_mod_version=mod_version, expected_commit=commit,
            expected_release=derive_release_identity(matrix_path, selected, target=target).manifest(),
        )
        verify_release.verify_staged_manifest(
            repo, directory, manifest_path, manifest, selected, matrix_path, mod_version, commit,
            target=target,
        )
        records = {record["artifact_node"]: record for record in manifest["artifacts"]}
        for artifact in selected["artifacts"]:
            record = records[artifact["artifact_node"]]
            for kind, source_record in (("jar", record), ("harness_jar", record["harness"])):
                destination = repo / verify_release.resolve_template(artifact[kind], mod_version)
                verify_release.require(destination.resolve() == destination and destination.is_relative_to(repo),
                                       "matrix build destination is outside the checkout or linked")
                copies.append((directory / source_record["path"], destination, source_record["sha256"]))
    verify_release.require(len(copies) == 2 * data["lane_count"]
                           and len({destination for _, destination, _ in copies}) == len(copies),
                           "target bundles overlap or omit production/harness outputs")
    for source, destination, digest in copies:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        verify_release.require(verify_release.sha256(destination) == digest,
                               "target bytes changed while assembling the matrix")

    manifest_path = stage / "artifacts.json"
    result = verify_release.build_manifest(repo, matrix_path, stage, manifest_path, mod_version, data)
    build_matrix.write_report(manifest_path, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, default=Path("build/compiled-targets"))
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    args = parser.parse_args()
    try:
        result = assemble(ROOT, ROOT / args.inputs, ROOT / args.stage)
    except (OSError, ValueError, RuntimeError) as exc:
        parser.exit(1, f"Matrix assembly failed: {exc}\n")
    print(f"Reverified and assembled all {result['lane_count']} production/harness pairs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
