#!/usr/bin/env python3
"""Generate the deterministic CycloneDX SBOM for staged Quick Skin release JARs."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any
from urllib.parse import quote

from artifact_manifest import (
    ArtifactManifestError,
    SBOM_PATH,
    current_git_commit,
    load_artifact_manifest,
    validate_artifact_manifest,
)
from matrix import MatrixError, load_matrix, select_release_target
from release_identity import ReleaseIdentityError, derive as derive_release_identity


CYCLONEDX_SCHEMA = "http://cyclonedx.org/schema/bom-1.6.schema.json"
CYCLONEDX_SPEC_VERSION = "1.6"
SBOM_RELATIVE_PATH = Path(SBOM_PATH)
MAX_SBOM_BYTES = 16 * 1024 * 1024
SHA1 = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")
SHA512 = re.compile(r"[0-9a-f]{128}")
VERIFICATION_NAMESPACE = "https://schema.gradle.org/dependency-verification"


class SbomError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SbomError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_bounded(path: Path, limit: int, label: str) -> bytes:
    require(path.is_file() and not path.is_symlink(), f"{label} is missing or is not a regular file: {path}")
    require(path.stat().st_size <= limit, f"{label} exceeds {limit} bytes: {path}")
    with path.open("rb") as stream:
        value = stream.read(limit + 1)
    require(len(value) <= limit, f"{label} exceeds {limit} bytes: {path}")
    return value


def repository_relative(repository: Path, path: Path, label: str) -> str:
    root = repository.resolve()
    resolved = path.resolve()
    require(root == resolved or root in resolved.parents, f"{label} escapes repository: {path}")
    return resolved.relative_to(root).as_posix()


def parse_dependency_lock(path: Path) -> tuple[tuple[str, str, str], ...]:
    require(path.is_file() and not path.is_symlink(), f"missing dependency lock {path}")
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise SbomError(f"missing dependency lock {path}: {exc}") from exc

    coordinates: set[tuple[str, str, str]] = set()
    empty_marker = False
    for line_number, raw_line in enumerate(lines, 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        require("=" in line, f"malformed dependency lock line {path}:{line_number}")
        coordinate_text, configuration_text = line.split("=", 1)
        if coordinate_text == "empty":
            require(not configuration_text and not empty_marker, f"invalid empty marker in {path}")
            empty_marker = True
            continue
        coordinate = tuple(coordinate_text.split(":"))
        require(len(coordinate) == 3 and all(coordinate), f"invalid locked coordinate {coordinate_text!r}")
        configurations = tuple(part.strip() for part in configuration_text.split(",") if part.strip())
        require(
            configurations == ("shadowBundle",),
            f"locked dependency {coordinate_text} is not exclusively embedded by shadowBundle",
        )
        typed_coordinate = (coordinate[0], coordinate[1], coordinate[2])
        require(typed_coordinate not in coordinates, f"duplicate locked dependency {coordinate_text}")
        coordinates.add(typed_coordinate)
    require(empty_marker, f"dependency lock {path} has no Gradle empty marker")
    return tuple(sorted(coordinates))


def verification_checksums(
    path: Path,
    required_coordinates: set[tuple[str, str, str]],
) -> dict[tuple[str, str, str], str]:
    require(path.is_file() and not path.is_symlink(), f"verification metadata is missing: {path}")
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as exc:
        raise SbomError(f"cannot parse verification metadata {path}: {exc}") from exc
    require(
        root.tag == f"{{{VERIFICATION_NAMESPACE}}}verification-metadata",
        "unexpected Gradle verification metadata namespace",
    )
    namespace = {"v": VERIFICATION_NAMESPACE}
    components: dict[tuple[str, str, str], list[ElementTree.Element]] = {}
    for component in root.findall("v:components/v:component", namespace):
        coordinate = (
            component.attrib.get("group", ""),
            component.attrib.get("name", ""),
            component.attrib.get("version", ""),
        )
        components.setdefault(coordinate, []).append(component)

    result: dict[tuple[str, str, str], str] = {}
    for coordinate in sorted(required_coordinates):
        matches = components.get(coordinate, [])
        display = ":".join(coordinate)
        require(len(matches) == 1, f"verification metadata has no unique node for {display}")
        group, name, version = coordinate
        expected_jar = f"{name}-{version}.jar"
        artifacts = [
            artifact
            for artifact in matches[0].findall("v:artifact", namespace)
            if artifact.attrib.get("name") == expected_jar
        ]
        require(len(artifacts) == 1, f"verification metadata has no unique JAR for {display}")
        hashes = artifacts[0].findall("v:sha256", namespace)
        require(len(hashes) == 1, f"verification metadata has no unique SHA-256 for {display}")
        checksum = hashes[0].attrib.get("value", "")
        require(SHA256.fullmatch(checksum) is not None, f"invalid verification SHA-256 for {display}")
        result[coordinate] = checksum
    return result


def maven_purl(coordinate: tuple[str, str, str]) -> str:
    group, name, version = coordinate
    return (
        "pkg:maven/"
        f"{quote(group, safe='._-')}/{quote(name, safe='._-')}@{quote(version, safe='._-')}"
    )


def property_value(name: str, value: Any) -> dict[str, str]:
    return {"name": name, "value": str(value)}


def index_by_artifact_node(rows: Any, label: str) -> dict[str, dict[str, Any]]:
    require(isinstance(rows, list) and rows, f"{label} has no artifact nodes")
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        require(isinstance(row, dict), f"{label} contains a non-object artifact node")
        node = row.get("artifact_node")
        require(isinstance(node, str) and node, f"{label} contains a missing artifact node")
        require(node not in result, f"{label} contains duplicate artifact node {node}")
        result[node] = row
    return result


def validate_cyclonedx(sbom: dict[str, Any]) -> None:
    # This is deliberately the deterministic subset of CycloneDX that Quick Skin emits, not a
    # replacement for the complete upstream JSON Schema. Full offline validation should vendor a
    # pinned CycloneDX 1.6 schema plus its reviewed checksum; release jobs must never fetch a schema.
    require(sbom.get("$schema") == CYCLONEDX_SCHEMA, "wrong CycloneDX schema URI")
    require(sbom.get("bomFormat") == "CycloneDX", "wrong CycloneDX format")
    require(sbom.get("specVersion") == CYCLONEDX_SPEC_VERSION, "wrong CycloneDX spec version")
    require(sbom.get("version") == 1, "CycloneDX document version must be 1")
    metadata = sbom.get("metadata")
    require(isinstance(metadata, dict), "CycloneDX metadata is missing")
    root_component = metadata.get("component")
    require(isinstance(root_component, dict), "CycloneDX root component is missing")
    components = sbom.get("components")
    dependencies = sbom.get("dependencies")
    require(isinstance(components, list) and components, "CycloneDX components are missing")
    require(isinstance(dependencies, list) and dependencies, "CycloneDX dependency graph is missing")

    references: list[str] = []
    for component in [root_component, *components]:
        require(isinstance(component, dict), "CycloneDX component must be an object")
        reference = component.get("bom-ref")
        require(isinstance(reference, str) and reference, "CycloneDX component has no bom-ref")
        require(isinstance(component.get("type"), str), f"CycloneDX component {reference} has no type")
        require(isinstance(component.get("name"), str) and component["name"], f"component {reference} has no name")
        references.append(reference)
        for item in component.get("hashes", []):
            require(isinstance(item, dict), f"component {reference} has an invalid hash")
            algorithm = item.get("alg")
            pattern = {"SHA-1": SHA1, "SHA-256": SHA256, "SHA-512": SHA512}.get(algorithm)
            require(pattern is not None, f"component {reference} uses unsupported hash {algorithm!r}")
            require(pattern.fullmatch(str(item.get("content", ""))) is not None, f"component {reference} has invalid {algorithm}")
    require(len(references) == len(set(references)), "CycloneDX bom-ref values are not unique")

    reference_set = set(references)
    graph_references: set[str] = set()
    for dependency in dependencies:
        require(isinstance(dependency, dict), "CycloneDX dependency entry must be an object")
        reference = dependency.get("ref")
        require(reference in reference_set, f"CycloneDX dependency uses unknown ref {reference!r}")
        require(reference not in graph_references, f"duplicate CycloneDX dependency ref {reference}")
        graph_references.add(reference)
        depends_on = dependency.get("dependsOn", [])
        require(isinstance(depends_on, list), f"CycloneDX dependency {reference} has invalid dependsOn")
        require(len(depends_on) == len(set(depends_on)), f"CycloneDX dependency {reference} is duplicated")
        require(set(depends_on) <= reference_set, f"CycloneDX dependency {reference} points to an unknown ref")


def build_cyclonedx(
    repository: Path,
    matrix_path: Path,
    matrix: dict[str, Any],
    manifest: dict[str, Any],
    stage: Path,
    *,
    expected_mod_version: str | None = None,
    expected_commit: str | None = None,
    expected_release: dict[str, Any] | None = None,
) -> dict[str, Any]:
    repository = repository.resolve()
    matrix_path = matrix_path.resolve()
    expected_matrix_path = repository_relative(repository, matrix_path, "release matrix")
    try:
        record_by_node = validate_artifact_manifest(
            manifest,
            repository=repository,
            matrix_path=matrix_path,
            matrix=matrix,
            stage=stage,
            expected_mod_version=expected_mod_version,
            expected_commit=expected_commit,
            expected_release=expected_release,
            require_sbom=False,
        )
    except ArtifactManifestError as exc:
        raise SbomError(str(exc)) from exc

    mod_version = manifest["mod_version"]
    commit = manifest["git_commit"]
    release = manifest["release"]
    release_id = release["release_id"]

    matrix_by_node = index_by_artifact_node(matrix.get("artifacts"), "release matrix")

    locks: dict[str, tuple[Path, tuple[tuple[str, str, str], ...]]] = {}
    required_coordinates: set[tuple[str, str, str]] = set()
    for node in sorted(matrix_by_node):
        artifact = matrix_by_node[node]
        loader = artifact.get("loader")
        artifact_version = artifact.get("artifact_version")
        require(isinstance(loader, str) and loader, f"matrix node {node} has no loader")
        require(isinstance(artifact_version, str) and artifact_version, f"matrix node {node} has no version")
        lock_path = repository / "gradle" / "dependency-locks" / f"{loader}-{artifact_version}.lockfile"
        coordinates = parse_dependency_lock(lock_path)
        locks[node] = (lock_path, coordinates)
        required_coordinates.update(coordinates)

    verification_path = repository / "gradle" / "verification-metadata.xml"
    checksums = verification_checksums(verification_path, required_coordinates)
    library_components = []
    for coordinate in sorted(required_coordinates):
        group, name, version = coordinate
        purl = maven_purl(coordinate)
        library_components.append(
            {
                "type": "library",
                "bom-ref": purl,
                "group": group,
                "name": name,
                "version": version,
                "scope": "required",
                "hashes": [{"alg": "SHA-256", "content": checksums[coordinate]}],
                "purl": purl,
                "properties": [property_value("quickskin:embedded", "true")],
            }
        )

    artifact_components: list[dict[str, Any]] = []
    artifact_references: dict[str, str] = {}
    artifact_dependencies: dict[str, list[str]] = {}
    for node in sorted(matrix_by_node):
        artifact = matrix_by_node[node]
        record = record_by_node[node]
        expected_filename = Path(str(artifact.get("jar", "")).replace("{mod_version}", mod_version)).name
        expected_path = f"files/{expected_filename}"

        reference = f"urn:quickskin:artifact:{node}:sha256:{record['sha256']}"
        artifact_references[node] = reference
        lock_path, coordinates = locks[node]
        artifact_dependencies[reference] = sorted(maven_purl(coordinate) for coordinate in coordinates)
        artifact_components.append(
            {
                "type": "file",
                "bom-ref": reference,
                "name": expected_filename,
                "version": mod_version,
                "hashes": [
                    {"alg": "SHA-1", "content": record["sha1"]},
                    {"alg": "SHA-256", "content": record["sha256"]},
                    {"alg": "SHA-512", "content": record["sha512"]},
                ],
                "properties": [
                    property_value("quickskin:artifact-node", node),
                    property_value("quickskin:loader", artifact["loader"]),
                    property_value("quickskin:minecraft-versions", ",".join(artifact["game_versions"])),
                    property_value("quickskin:staged-path", expected_path),
                    property_value("quickskin:bytes", record["bytes"]),
                    property_value(
                        "quickskin:dependency-lock",
                        repository_relative(repository, lock_path, f"dependency lock for {node}"),
                    ),
                    property_value("quickskin:dependency-lock-sha256", sha256(lock_path)),
                ],
            }
        )

    release_reference = f"urn:quickskin:release:{release_id}:git:{commit}"
    root_component = {
        "type": "application",
        "bom-ref": release_reference,
        "name": matrix["project"]["name"],
        "version": mod_version,
        "description": matrix["project"]["description"],
        "properties": [
            property_value("quickskin:git-commit", commit),
            property_value("quickskin:release-id", release_id),
            property_value("quickskin:release-matrix", expected_matrix_path),
            property_value("quickskin:release-matrix-sha256", manifest["matrix_sha256"]),
            property_value(
                "quickskin:verification-metadata-sha256",
                sha256(verification_path),
            ),
        ],
    }
    dependencies = [
        {
            "ref": release_reference,
            "dependsOn": [artifact_references[node] for node in sorted(artifact_references)],
        },
        *[
            {"ref": reference, "dependsOn": artifact_dependencies[reference]}
            for reference in sorted(artifact_dependencies)
        ],
    ]
    sbom = {
        "$schema": CYCLONEDX_SCHEMA,
        "bomFormat": "CycloneDX",
        "specVersion": CYCLONEDX_SPEC_VERSION,
        "version": 1,
        "metadata": {"component": root_component},
        "components": sorted(
            [*artifact_components, *library_components],
            key=lambda component: component["bom-ref"],
        ),
        "dependencies": dependencies,
    }
    validate_cyclonedx(sbom)
    return sbom


def canonical_bytes(sbom: dict[str, Any]) -> bytes:
    validate_cyclonedx(sbom)
    return (json.dumps(sbom, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode(
        "utf-8"
    )


def build_cyclonedx_bytes(
    repository: Path,
    matrix_path: Path,
    matrix: dict[str, Any],
    manifest: dict[str, Any],
    stage: Path,
    *,
    expected_mod_version: str | None = None,
    expected_commit: str | None = None,
    expected_release: dict[str, Any] | None = None,
) -> bytes:
    return canonical_bytes(
        build_cyclonedx(
            repository,
            matrix_path,
            matrix,
            manifest,
            stage,
            expected_mod_version=expected_mod_version,
            expected_commit=expected_commit,
            expected_release=expected_release,
        )
    )


def stage_sbom(
    repository: Path,
    matrix_path: Path,
    stage: Path,
    matrix: dict[str, Any],
    manifest: dict[str, Any],
) -> dict[str, Any]:
    payload = build_cyclonedx_bytes(repository, matrix_path, matrix, manifest, stage)
    require(len(payload) <= MAX_SBOM_BYTES, "generated CycloneDX SBOM exceeds the attestation limit")
    destination = stage / SBOM_RELATIVE_PATH
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(payload)
    return {
        "format": "CycloneDX",
        "spec_version": CYCLONEDX_SPEC_VERSION,
        "filename": destination.name,
        "path": SBOM_RELATIVE_PATH.as_posix(),
        "bytes": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }


def verify_staged_sbom(
    repository: Path,
    matrix_path: Path,
    stage: Path,
    matrix: dict[str, Any],
    manifest: dict[str, Any],
) -> None:
    try:
        validate_artifact_manifest(
            manifest,
            repository=repository,
            matrix_path=matrix_path,
            matrix=matrix,
            stage=stage,
        )
    except ArtifactManifestError as exc:
        raise SbomError(str(exc)) from exc
    destination = (stage / SBOM_RELATIVE_PATH).resolve()
    payload = read_bounded(destination, MAX_SBOM_BYTES, "staged SBOM")
    expected = build_cyclonedx_bytes(repository, matrix_path, matrix, manifest, stage)
    require(payload == expected, "staged SBOM does not match matrix, manifest, locks, and checksums")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument("--manifest", type=Path, default=Path("build/release/artifacts.json"))
    parser.add_argument("--target", help="verify one Minecraft publication target")
    parser.add_argument("--output", type=Path, default=Path("build/release") / SBOM_RELATIVE_PATH)
    args = parser.parse_args()

    repository = Path(__file__).resolve().parents[2]
    matrix_path = args.matrix if args.matrix.is_absolute() else repository / args.matrix
    manifest_path = args.manifest if args.manifest.is_absolute() else repository / args.manifest
    output = args.output if args.output.is_absolute() else repository / args.output
    try:
        matrix = load_matrix(matrix_path)
        if args.target is not None:
            matrix = select_release_target(matrix, args.target)
        identity = derive_release_identity(matrix_path, matrix, target=args.target)
        commit = current_git_commit(repository)
        stage = manifest_path.parent.resolve()
        manifest = load_artifact_manifest(
            manifest_path,
            repository=repository,
            matrix_path=matrix_path,
            matrix=matrix,
            stage=stage,
            expected_mod_version=identity.mod_version,
            expected_commit=commit,
            expected_release=identity.manifest(),
            require_sbom=False,
        )
        # Validate the final SBOM record independently of the existing SBOM file so this command
        # can safely recreate a missing copy while still refusing a stale manifest identity.
        validate_artifact_manifest(
            manifest,
            repository=repository,
            matrix_path=matrix_path,
            matrix=matrix,
            stage=stage,
            expected_mod_version=identity.mod_version,
            expected_commit=commit,
            expected_release=identity.manifest(),
            require_sbom=True,
            verify_files=False,
        )
        payload = build_cyclonedx_bytes(
            repository,
            matrix_path,
            matrix,
            manifest,
            stage,
            expected_mod_version=identity.mod_version,
            expected_commit=commit,
            expected_release=identity.manifest(),
        )
        require(len(payload) <= MAX_SBOM_BYTES, "generated CycloneDX SBOM exceeds the attestation limit")
        record = manifest["sbom"]
        require(record["bytes"] == len(payload), "generated SBOM byte count disagrees with manifest")
        require(
            record["sha256"] == hashlib.sha256(payload).hexdigest(),
            "generated SBOM hash disagrees with manifest",
        )
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
    except (
        ArtifactManifestError,
        MatrixError,
        OSError,
        ReleaseIdentityError,
        SbomError,
        json.JSONDecodeError,
    ) as exc:
        print(f"SBOM generation failed: {exc}", file=sys.stderr)
        return 1
    print(f"Wrote deterministic CycloneDX {CYCLONEDX_SPEC_VERSION} SBOM to {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
