#!/usr/bin/env python3
"""Validate and expose the checked-in Quick Skin release matrix.

The output for ``--kind`` is deliberately a compact, single-line GitHub Actions
matrix.  CI can safely assign it to a step output without maintaining a second
copy of the supported versions.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))

from scenario_contract import (  # noqa: E402
    ScenarioContract,
    ScenarioContractError,
    default_contract,
)


REQUIRED_RUNTIME_FIELDS = {
    "artifact_node",
    "runtime_version",
    "loader",
    "jar_sha256",
    "port",
    "java",
    "loader_version",
    "installer",
    "architectury",
    "scheduled_anchor",
    "pr_anchor",
}

KNOWN_LOADERS = {"fabric", "forge", "neoforge"}
KNOWN_COMPATIBILITY_PATCHES = {"neoforge-26.1-break-event-v1"}
LOADER_DISPLAY_NAMES = {
    "fabric": "Fabric",
    "forge": "Forge",
    "neoforge": "NeoForge",
}
VERSIONED_PROPERTY_PREFIXES = (
    "minecraft_version_",
    "java_version_",
    "architectury_api_version_",
    "fabric_loader_version_",
    "fabric_api_version_",
    "forge_version_",
    "neoforge_version_",
)


class MatrixError(ValueError):
    pass


LEGACY_E2E_POLICY_FIELDS = frozenset(
    {"scheduled_scenarios", "pr_scenarios"}
)


def normalize_legacy_e2e_policy(data: dict[str, Any]) -> dict[str, Any]:
    """Return a deep copy stripped only of matrix-owned legacy E2E policy."""

    if not isinstance(data, dict):
        raise MatrixError("release matrix root must be an object")
    normalized = copy.deepcopy(data)
    for field in LEGACY_E2E_POLICY_FIELDS:
        normalized.pop(field, None)
    runtimes = normalized.get("runtimes")
    if isinstance(runtimes, list):
        for runtime in runtimes:
            if isinstance(runtime, dict):
                runtime.pop("scenario", None)
    return normalized


def write_matrix_atomic(path: Path, data: dict[str, Any]) -> None:
    """Atomically replace one matrix with deterministic validated JSON."""

    destination = path.resolve()
    try:
        mode = stat.S_IMODE(destination.stat().st_mode)
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=destination.parent,
            prefix=f".{destination.name}.",
            suffix=".tmp",
            delete=False,
        ) as handle:
            temporary = Path(handle.name)
            json.dump(data, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, destination)
    except OSError as exc:
        if "temporary" in locals():
            temporary.unlink(missing_ok=True)
        raise MatrixError(f"cannot atomically write release matrix {path}: {exc}") from exc


def read_matrix_data(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise MatrixError(f"cannot read release matrix {path}: {exc}") from exc
    if not isinstance(data, dict):
        raise MatrixError("release matrix root must be an object")
    return data


def validate_matrix_file(path: Path, data: dict[str, Any]) -> None:
    validate_matrix(data)
    validate_build_properties(path, data)
    validate_source_roots(path, data)


def load_matrix(path: Path) -> dict[str, Any]:
    data = read_matrix_data(path)
    validate_matrix_file(path, data)
    return data


def read_properties(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise MatrixError(f"cannot read build properties {path}: {exc}") from exc
    properties: dict[str, str] = {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() in properties:
            raise MatrixError(f"duplicate Gradle property {key.strip()}")
        properties[key.strip()] = value.strip()
    return properties


def validate_build_property_values(
    data: dict[str, Any], properties: dict[str, str]
) -> None:
    runtimes = {row["artifact_node"]: row for row in data["runtimes"]}
    versions: dict[str, dict[str, Any]] = {}
    for artifact in data["artifacts"]:
        version = artifact["artifact_version"]
        version_info = versions.setdefault(version, {"java": artifact["java"]})
        if version_info["java"] != artifact["java"]:
            raise MatrixError(f"release lanes disagree on Java for Minecraft {version}")

        suffix = version.replace(".", "_")
        runtime = runtimes[artifact["artifact_node"]]
        expected_properties = {
            f"architectury_api_version_{suffix}": runtime["architectury"]["version"],
        }
        if artifact["loader"] == "fabric":
            expected_properties.update(
                {
                    f"fabric_loader_version_{suffix}": runtime["loader_version"],
                    f"fabric_api_version_{suffix}": runtime["fabric_api"],
                }
            )
        elif artifact["loader"] in {"forge", "neoforge"}:
            expected_properties[
                f"{artifact['loader']}_version_{suffix}"
            ] = runtime["loader_version"]
        else:
            raise MatrixError(f"unsupported artifact loader {artifact['loader']!r}")
        for key, expected in expected_properties.items():
            if properties.get(key) != str(expected):
                raise MatrixError(
                    f"Gradle property {key}={properties.get(key)!r} disagrees with matrix {expected!r}"
                )

    for version, version_info in versions.items():
        suffix = version.replace(".", "_")
        expected = {
            f"minecraft_version_{suffix}": version,
            f"java_version_{suffix}": str(version_info["java"]),
        }
        for key, value in expected.items():
            if properties.get(key) != value:
                raise MatrixError(
                    f"Gradle property {key}={properties.get(key)!r} disagrees with matrix {value!r}"
                )

    supported_suffixes = {version.replace(".", "_") for version in versions}
    for key in properties:
        for prefix in VERSIONED_PROPERTY_PREFIXES:
            if key.startswith(prefix) and key.removeprefix(prefix) not in supported_suffixes:
                raise MatrixError(
                    f"Gradle property {key} belongs to no supported Minecraft version"
                )
            if key.startswith(prefix):
                break


def validate_build_properties(matrix_path: Path, data: dict[str, Any]) -> None:
    properties = read_properties(matrix_path.resolve().parents[1] / "gradle.properties")
    validate_build_property_values(data, properties)


def load_matrix_snapshot(matrix_path: Path, properties_path: Path) -> dict[str, Any]:
    """Validate inert matrix/property bytes without requiring a checked-out source tree.

    Snapshot consumers must separately authenticate any source-tree contract they rely on.
    This mode keeps the complete matrix and Gradle-property validation while deliberately
    omitting the checkout-bound overlay inventory validation performed by ``load_matrix``.
    """

    data = read_matrix_data(matrix_path)
    validate_matrix(data)
    validate_build_property_values(data, read_properties(properties_path))
    return data


def validate_source_roots(matrix_path: Path, data: dict[str, Any]) -> None:
    """Fail on unreferenced live overlays or a reintroduced version-snapshot tree."""
    repository = matrix_path.resolve().parents[1]
    overlays = data["source_overlays"]
    for module, routes in overlays.items():
        source_root = repository / module / "src"
        actual = {
            path.name
            for path in source_root.glob("legacy*")
            if path.is_dir() and any(child.is_file() for child in path.rglob("*"))
        }
        expected = set(routes.values())
        if actual != expected:
            raise MatrixError(
                f"{module} overlay roots disagree with matrix: "
                f"expected {sorted(expected)}, found {sorted(actual)}"
            )
        for overlay in expected:
            path = source_root / overlay
            if not path.is_dir() or not any(child.is_file() for child in path.rglob("*")):
                raise MatrixError(f"matrix references missing {module} overlay root {overlay}")

        retired_snapshots = [
            path for path in source_root.glob("v*")
            if path.is_dir() and any(child.is_file() for child in path.rglob("*"))
        ]
        if retired_snapshots:
            raise MatrixError(
                f"retired {module} version snapshots remain: "
                f"{[path.name for path in retired_snapshots]}"
            )

        live_java_roots = [source_root / "main" / "java"] + [
            source_root / overlay / "java" for overlay in expected
        ]
        locations_by_class: dict[str, list[str]] = {}
        for java_root in live_java_roots:
            if not java_root.is_dir():
                continue
            for source in java_root.rglob("*.java"):
                relative = source.relative_to(java_root).as_posix()
                locations_by_class.setdefault(relative, []).append(
                    source.relative_to(repository).as_posix()
                )
        duplicated = {
            relative: locations
            for relative, locations in locations_by_class.items()
            if len(locations) > 2
        }
        if duplicated:
            details = "; ".join(
                f"{relative}: {locations}"
                for relative, locations in sorted(duplicated.items())
            )
            raise MatrixError(
                f"{module} live Java classes exceed the two-copy overlay limit: {details}"
            )


def validate_matrix(
    data: dict[str, Any],
    contract: ScenarioContract | None = None,
) -> None:
    try:
        scenario_contract = contract or default_contract()
    except ScenarioContractError as exc:
        raise MatrixError(f"invalid packaged E2E scenario contract: {exc}") from exc
    if data.get("schema_version") != 2:
        raise MatrixError("release matrix schema_version must be 2")
    legacy_fields = set(data) & LEGACY_E2E_POLICY_FIELDS
    if legacy_fields:
        raise MatrixError(
            "release matrix contains legacy E2E policy fields "
            f"{sorted(legacy_fields)}; normalize them before validation"
        )

    lane_count = data.get("lane_count")
    if isinstance(lane_count, bool) or not isinstance(lane_count, int) or lane_count <= 0:
        raise MatrixError("release matrix lane_count must be a positive integer")
    artifacts = data.get("artifacts")
    runtimes = data.get("runtimes")
    if not isinstance(artifacts, list) or len(artifacts) != lane_count:
        raise MatrixError(
            f"release matrix must contain lane_count={lane_count} artifacts"
        )
    if not isinstance(runtimes, list) or len(runtimes) != lane_count:
        raise MatrixError(
            f"release matrix must contain lane_count={lane_count} runtime rows"
        )
    project = data.get("project", {})
    if not isinstance(project, dict):
        raise MatrixError("release matrix project must be an object")
    for key in (
        "name",
        "mod_id",
        "description",
        "homepage",
        "sources",
        "issues",
        "license",
        "release_branch",
    ):
        if not isinstance(project.get(key), str) or not project[key].strip():
            raise MatrixError(f"project.{key} must be a non-empty string")
    if not isinstance(project.get("modrinth_id"), str) or not project["modrinth_id"].strip():
        raise MatrixError("project.modrinth_id must be a non-empty string")
    if (
        isinstance(project.get("curseforge_id"), bool)
        or not isinstance(project.get("curseforge_id"), int)
        or project["curseforge_id"] <= 0
    ):
        raise MatrixError("project.curseforge_id must be a positive integer")
    installers = data.get("installers", {})
    if not isinstance(installers, dict) or not installers:
        raise MatrixError("release matrix must lock runtime installers")
    for key, installer in installers.items():
        if not isinstance(installer, dict):
            raise MatrixError(f"installer {key} must be an object")
        if not str(installer.get("url", "")).startswith("https://"):
            raise MatrixError(f"installer {key} must use an HTTPS URL")
        if not re_full_sha256(installer.get("sha256")):
            raise MatrixError(f"installer {key} has invalid SHA-256")

    artifact_by_node: dict[str, dict[str, Any]] = {}
    loader_counts = {loader: 0 for loader in KNOWN_LOADERS}
    for artifact in artifacts:
        if not isinstance(artifact, dict):
            raise MatrixError("every artifact row must be an object")
        required = {
            "artifact_node",
            "artifact_version",
            "loader",
            "java",
            "no_remap",
            "metadata_range",
            "gradle_task",
            "harness_task",
            "jar",
            "harness_jar",
            "game_versions",
            "metadata",
        }
        missing = required - artifact.keys()
        if missing:
            raise MatrixError(
                f"artifact {artifact.get('artifact_node', '<unknown>')} missing {sorted(missing)}"
            )
        for key in (
            "artifact_node",
            "artifact_version",
            "loader",
            "metadata_range",
            "gradle_task",
            "harness_task",
            "jar",
            "harness_jar",
        ):
            if not isinstance(artifact[key], str) or not artifact[key]:
                raise MatrixError(f"artifact field {key} must be a non-empty string")
        if not isinstance(artifact["metadata"], dict):
            raise MatrixError(f"artifact {artifact['artifact_node']} metadata must be an object")
        node = artifact["artifact_node"]
        if node in artifact_by_node:
            raise MatrixError(f"duplicate artifact_node {node}")
        artifact_by_node[node] = artifact
        loader = artifact["loader"]
        if loader not in KNOWN_LOADERS:
            raise MatrixError(f"unsupported artifact loader {loader!r}")
        loader_counts[loader] += 1
        version = artifact["artifact_version"]
        if node != f"{loader}-{version}":
            raise MatrixError(f"artifact node {node} does not match {loader} {version}")
        task_prefix = f":{loader}:{version}:"
        no_remap = artifact["no_remap"]
        if not isinstance(no_remap, bool):
            raise MatrixError(f"artifact {node} no_remap must be a boolean")
        production_task = "shadowJar" if no_remap else "remapJar"
        harness_task = "e2eHarnessJar" if no_remap else "remapE2EHarnessJar"
        if artifact["gradle_task"] != f"{task_prefix}{production_task}":
            raise MatrixError(
                f"artifact {node} Gradle task must be {task_prefix}{production_task}"
            )
        if artifact["harness_task"] != f"{task_prefix}{harness_task}":
            raise MatrixError(
                f"artifact {node} harness task must be {task_prefix}{harness_task}"
            )
        java = artifact["java"]
        if isinstance(java, bool) or not isinstance(java, int) or java < 17:
            raise MatrixError(f"artifact {node} Java must be an integer of at least 17")
        for key in ("jar", "harness_jar"):
            value = artifact[key].replace("\\", "/")
            if value.startswith("/") or ".." in Path(value).parts:
                raise MatrixError(f"artifact {node} has unsafe {key}: {value}")
            if "/src/v" in f"/{value}" or ".migration-archive" in value:
                raise MatrixError(f"artifact {node} points into excluded source history")
            if f"/{version}/" not in f"/{value}" or f" - {version}-" not in Path(value).name:
                raise MatrixError(f"artifact {node} {key} does not encode its exact version")
        versions = artifact["game_versions"]
        if versions != [artifact["artifact_version"]]:
            raise MatrixError(f"artifact {node} must advertise only its exact build version")

    active_loaders = {loader for loader, count in loader_counts.items() if count}
    unit_test_version = data.get("unit_test_version")
    artifact_versions = {artifact["artifact_version"] for artifact in artifacts}
    if not isinstance(unit_test_version, str) or unit_test_version not in artifact_versions:
        raise MatrixError("unit_test_version must select a supported release version")

    release_branch = project["release_branch"]
    branch_match = re.fullmatch(
        r"((?:fabric|forge|neoforge)(?:-and-(?:fabric|forge|neoforge))+)-([0-9]+(?:\.[0-9]+)+)",
        release_branch,
    )
    if branch_match is None:
        raise MatrixError("project.release_branch must use the release-branch naming contract")
    branch_loaders = set(branch_match.group(1).split("-and-"))
    if branch_loaders != active_loaders:
        raise MatrixError("project.release_branch loaders disagree with active artifacts")
    if branch_match.group(2) not in artifact_versions:
        raise MatrixError("project.release_branch version is absent from active artifacts")

    source_overlays = data.get("source_overlays")
    expected_source_modules = {"common", *active_loaders}
    if not isinstance(source_overlays, dict) or set(source_overlays) != expected_source_modules:
        raise MatrixError(
            "source_overlays must define common and every active loader exactly once"
        )
    for module, routes in source_overlays.items():
        if not isinstance(routes, dict):
            raise MatrixError(f"source_overlays.{module} must be an object")
        allowed_versions = (
            artifact_versions
            if module == "common"
            else {
                artifact["artifact_version"]
                for artifact in artifacts
                if artifact["loader"] == module
            }
        )
        unknown = set(routes) - allowed_versions
        if unknown:
            raise MatrixError(
                f"source_overlays.{module} names unsupported versions {sorted(unknown)}"
            )
        values = list(routes.values())
        if not all(
            isinstance(value, str) and re.fullmatch(r"legacy[0-9A-Za-z_]+", value)
            for value in values
        ):
            raise MatrixError(f"source_overlays.{module} values must name legacy* roots")
        if len(values) != len(set(values)):
            raise MatrixError(f"source_overlays.{module} reuses an overlay root")

    version_policies: dict[str, tuple[int, bool]] = {}
    for artifact in artifacts:
        version = artifact["artifact_version"]
        policy = (artifact["java"], artifact["no_remap"])
        previous = version_policies.setdefault(version, policy)
        if previous != policy:
            raise MatrixError(
                f"release lanes disagree on Java/no_remap policy for Minecraft {version}"
            )

    seen_runtime_keys: set[tuple[str, str]] = set()
    runtime_nodes: set[str] = set()
    pr_anchor_loaders: set[str] = set()
    for runtime in runtimes:
        if not isinstance(runtime, dict):
            raise MatrixError("every runtime row must be an object")
        if "scenario" in runtime:
            raise MatrixError(
                "runtime rows must not own E2E scenario policy; normalize "
                "the legacy scenario field"
            )
        missing = REQUIRED_RUNTIME_FIELDS - runtime.keys()
        if missing:
            raise MatrixError(f"runtime row missing {sorted(missing)}: {runtime}")
        for key in ("artifact_node", "runtime_version", "loader", "loader_version", "installer"):
            if not isinstance(runtime[key], str) or not runtime[key]:
                raise MatrixError(f"runtime field {key} must be a non-empty string")
        node = runtime["artifact_node"]
        artifact = artifact_by_node.get(node)
        if artifact is None:
            raise MatrixError(f"runtime row refers to unknown artifact {node}")
        if runtime["loader"] != artifact["loader"]:
            raise MatrixError(f"runtime loader disagrees with artifact {node}")
        if runtime["runtime_version"] != artifact["artifact_version"]:
            raise MatrixError(f"runtime {node} must match its exact artifact version")
        if runtime["java"] != artifact["java"]:
            raise MatrixError(f"runtime Java disagrees with artifact {node}")
        if not isinstance(runtime["loader_version"], str) or not runtime["loader_version"]:
            raise MatrixError(f"runtime {node} must lock a loader version")
        if runtime["jar_sha256"] != "from:artifact-manifest":
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} must bind the build hash")
        if isinstance(runtime["port"], bool) or runtime["port"] != 0:
            raise MatrixError("checked-in runtime ports must be 0 (allocated per isolated profile)")
        if runtime["installer"] not in installers:
            raise MatrixError(f"runtime {node}/{runtime['runtime_version']} has no locked installer")
        expected_installer = (
            "fabric-1.1.0"
            if runtime["loader"] == "fabric"
            else f"{runtime['loader']}-{runtime['loader_version']}"
        )
        if runtime["installer"] != expected_installer:
            raise MatrixError(f"runtime {node} installer disagrees with its loader version")
        if runtime["loader"] == "fabric":
            if not isinstance(runtime.get("fabric_api"), str) or not runtime["fabric_api"]:
                raise MatrixError(f"Fabric runtime {node} must lock Fabric API")
        elif "fabric_api" in runtime:
            raise MatrixError(f"non-Fabric runtime {node} must not declare Fabric API")
        architectury = runtime.get("architectury", {})
        if (
            not isinstance(architectury, dict)
            or architectury.get("kind") != "maven"
            or not isinstance(architectury.get("version"), str)
            or not architectury["version"]
        ):
            raise MatrixError(f"runtime {node} must use a locked Maven Architectury version")
        compatibility_patch = runtime.get("compatibility_patch")
        if compatibility_patch is not None:
            if (
                not isinstance(compatibility_patch, str)
                or compatibility_patch not in KNOWN_COMPATIBILITY_PATCHES
            ):
                raise MatrixError(
                    f"runtime {node} uses unknown compatibility patch {compatibility_patch!r}"
                )
            if runtime["loader"] != "neoforge":
                raise MatrixError(
                    f"runtime {node} compatibility patches are supported only on NeoForge"
                )
        if runtime.get("scheduled_anchor") is not True:
            raise MatrixError(f"runtime {node} must be a scheduled native anchor")
        if not isinstance(runtime.get("pr_anchor"), bool):
            raise MatrixError(f"runtime {node} pr_anchor must be a boolean")
        if runtime["pr_anchor"]:
            pr_anchor_loaders.add(runtime["loader"])
        key = (node, runtime["runtime_version"])
        if key in seen_runtime_keys:
            raise MatrixError(f"duplicate runtime row {key}")
        seen_runtime_keys.add(key)
        runtime_nodes.add(node)

    if runtime_nodes != set(artifact_by_node):
        raise MatrixError("every supported artifact must have exactly one exact runtime row")
    if pr_anchor_loaders != active_loaders:
        raise MatrixError(
            "PR anchors must cover every active loader; "
            f"got {sorted(pr_anchor_loaders)}"
        )
    used_installers = {row["installer"] for row in runtimes}
    if set(installers) != used_installers:
        raise MatrixError("release matrix contains an installer unused by supported runtimes")

    runtime_by_node = {row["artifact_node"]: row for row in runtimes}
    metadata_files = {
        "fabric": "fabric.mod.json",
        "forge": "META-INF/mods.toml",
        "neoforge": "META-INF/neoforge.mods.toml",
    }
    for node, artifact in artifact_by_node.items():
        loader = artifact["loader"]
        version = artifact["artifact_version"]
        metadata = artifact["metadata"]
        if metadata.get("file") != metadata_files[loader]:
            raise MatrixError(f"artifact {node} has the wrong loader metadata file")
        if not isinstance(metadata.get("loader"), str) or not metadata["loader"]:
            raise MatrixError(f"artifact {node} metadata must declare its loader range")
        if loader == "fabric":
            if "loader_api" in metadata:
                raise MatrixError(f"Fabric artifact {node} must not declare FML loader_api")
            if "pack_format" in metadata or "server_data_pack_format" in metadata:
                raise MatrixError(f"Fabric artifact {node} must not declare FML pack formats")
        elif not isinstance(metadata.get("loader_api"), str) or not metadata["loader_api"]:
            raise MatrixError(f"FML artifact {node} metadata must declare loader_api")
        else:
            for key in ("pack_format", "server_data_pack_format"):
                value = metadata.get(key)
                if isinstance(value, bool) or not isinstance(value, int) or value < 1:
                    raise MatrixError(
                        f"FML artifact {node} metadata.{key} must be a positive integer"
                    )
        if "minecraft" in metadata:
            raise MatrixError(
                f"artifact {node} must declare its Minecraft range only in metadata_range"
            )
        validate_metadata_range(node, loader, version, artifact["metadata_range"])
        architectury_version = runtime_by_node[node]["architectury"]["version"]
        compatibility_patch = runtime_by_node[node].get("compatibility_patch")
        expected_architectury = (
            f"[{architectury_version}]"
            if compatibility_patch is not None
            else (
                f">={architectury_version}"
                if loader == "fabric"
                else f"[{architectury_version},)"
            )
        )
        if metadata.get("architectury") != expected_architectury:
            raise MatrixError(f"artifact {node} metadata disagrees with its tested Architectury")

    release_scenarios = scenario_contract.scenarios_for_profile("release")
    pr_scenarios = scenario_contract.scenarios_for_profile("pr")
    release_orchestrations = {
        scenario_contract.orchestration_for(scenario).mode
        for scenario in release_scenarios
    }
    pr_orchestrations = {
        scenario_contract.orchestration_for(scenario).mode
        for scenario in pr_scenarios
    }
    if pr_orchestrations != release_orchestrations:
        raise MatrixError(
            "PR execution profile must cover every release E2E orchestration mode"
        )


def re_full_sha256(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def validate_metadata_range(node: str, loader: str, version: str, value: Any) -> None:
    """Validate explicit loader metadata without deriving era-specific upper bounds."""
    if not isinstance(value, str) or not value:
        raise MatrixError(f"artifact {node} metadata_range must be a non-empty string")
    if loader == "fabric":
        if value != f"={version}":
            raise MatrixError(f"artifact {node} metadata_range must be ={version}")
        return

    if not value.startswith("[") or not value.endswith(")") or value.count(",") != 1:
        raise MatrixError(f"artifact {node} has malformed metadata_range {value!r}")
    lower, upper = value[1:-1].split(",", 1)
    if lower != version or not upper:
        raise MatrixError(
            f"artifact {node} metadata_range must start at its exact version {version}"
        )
    try:
        lower_parts = tuple(int(part) for part in lower.split("."))
        upper_parts = tuple(int(part) for part in upper.split("."))
    except ValueError as exc:
        raise MatrixError(f"artifact {node} metadata_range must use numeric versions") from exc
    width = max(len(lower_parts), len(upper_parts))
    padded_lower = lower_parts + (0,) * (width - len(lower_parts))
    padded_upper = upper_parts + (0,) * (width - len(upper_parts))
    if padded_upper <= padded_lower:
        raise MatrixError(f"artifact {node} metadata_range upper bound must exceed {version}")
    expected_upper = (
        lower_parts + (1,)
        if len(lower_parts) == 2
        else lower_parts[:-1] + (lower_parts[-1] + 1,)
    )
    if upper_parts != expected_upper:
        expected = ".".join(str(part) for part in expected_upper)
        raise MatrixError(
            f"artifact {node} metadata_range must end at the immediate patch successor {expected}"
        )


def read_mod_version_from_properties(
    properties_path: Path, data: dict[str, Any]
) -> str:
    key = data["project"]["mod_version_property"]
    properties = read_properties(properties_path)
    if key not in properties:
        raise MatrixError(f"{key} is missing from {properties_path}")
    return properties[key]


def read_mod_version(matrix_path: Path, data: dict[str, Any]) -> str:
    properties_path = matrix_path.resolve().parents[1] / "gradle.properties"
    return read_mod_version_from_properties(properties_path, data)


def release_id(data: dict[str, Any], mod_version: str) -> str:
    """Stable public identity: Minecraft era plus logical in-JAR mod version."""
    versions = sorted(
        {str(row["artifact_version"]) for row in data["artifacts"]},
        key=lambda value: tuple(int(part) for part in value.split(".")),
    )
    return f"mc{'+'.join(versions)}-v{mod_version}"


def gha_matrix(
    data: dict[str, Any],
    kind: str,
    mod_version: str,
    contract: ScenarioContract | None = None,
) -> dict[str, list[dict[str, Any]]]:
    scenario_contract = contract or default_contract()
    if kind in {"artifacts", "publications"}:
        include = []
        identity = release_id(data, mod_version)
        for artifact in data["artifacts"]:
            dependencies = ["architectury-api(required)"]
            if artifact["loader"] == "fabric":
                dependencies.append("fabric-api(required)")
            filename = Path(artifact["jar"].replace("{mod_version}", mod_version)).name
            expanded = {
                "id": artifact["artifact_node"],
                "artifact_node": artifact["artifact_node"],
                "file": f"build/release/files/{filename}",
                "name": (
                    f"Quick-Skin-{artifact['artifact_version']}-v{mod_version} "
                    f"[{LOADER_DISPLAY_NAMES[artifact['loader']]}]"
                ),
                "loader": artifact["loader"],
                "artifact_version": artifact["artifact_version"],
                "game_versions": "\n".join(artifact["game_versions"]),
                "dependencies": "\n".join(dependencies),
                "java": artifact["java"],
                "version": mod_version,
                "modrinth_id": data["project"]["modrinth_id"],
                "curseforge_id": data["project"]["curseforge_id"],
                "release_id": identity,
                "publication_id": f"{identity}-{artifact['artifact_node']}",
            }
            if kind == "artifacts":
                include.append(expanded)
            else:
                for marketplace in ("modrinth", "curseforge"):
                    include.append({
                        **expanded,
                        "id": f"{artifact['artifact_node']}--{marketplace}",
                        "marketplace": marketplace,
                    })
    elif kind in {"runtime", "native-anchors", "pr-anchors"}:
        rows = data["runtimes"]
        if kind == "native-anchors":
            rows = [row for row in rows if row.get("scheduled_anchor")]
        elif kind == "pr-anchors":
            rows = [row for row in rows if row.get("pr_anchor")]
        include = []
        for row in rows:
            expanded = dict(row)
            scope = {
                "runtime": "release-behavior",
                "native-anchors": "scheduled-behavior",
                "pr-anchors": "pr-behavior",
            }[kind]
            expanded["id"] = (
                f"{row['artifact_node']}--{row['runtime_version']}--{scope}"
                .replace(".", "_")
            )
            execution_profile = (
                "pr" if kind == "pr-anchors" else "release"
            )
            expanded["scenarios"] = ",".join(
                scenario_contract.scenarios_for_profile(execution_profile)
            )
            include.append(expanded)
    else:  # pragma: no cover - argparse prevents this
        raise MatrixError(f"unsupported matrix kind {kind}")
    return {"include": include}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--matrix",
        type=Path,
        default=Path("release/release-matrix.json"),
        help="checked-in release matrix",
    )
    parser.add_argument(
        "--kind",
        choices=("artifacts", "publications", "runtime", "native-anchors", "pr-anchors"),
        help="emit a compact GitHub Actions matrix",
    )
    parser.add_argument(
        "--normalize-e2e-policy",
        action="store_true",
        help="strip only legacy matrix-owned E2E scenario policy",
    )
    parser.add_argument(
        "--write",
        action="store_true",
        help="atomically write the normalized matrix back to --matrix",
    )
    parser.add_argument("--pretty", action="store_true", help="pretty-print output")
    args = parser.parse_args()

    try:
        if args.write and not args.normalize_e2e_policy:
            raise MatrixError("--write requires --normalize-e2e-policy")
        if args.normalize_e2e_policy:
            if args.kind is not None:
                raise MatrixError(
                    "--normalize-e2e-policy cannot be combined with --kind"
                )
            data = normalize_legacy_e2e_policy(
                read_matrix_data(args.matrix)
            )
            validate_matrix_file(args.matrix, data)
            if args.write:
                write_matrix_atomic(args.matrix, data)
            output: Any = None
        else:
            data = load_matrix(args.matrix)
            output = (
                gha_matrix(
                    data,
                    args.kind,
                    read_mod_version(args.matrix, data),
                )
                if args.kind
                else data
            )
    except MatrixError as exc:
        print(f"release matrix error: {exc}", file=sys.stderr)
        return 2

    if output is None:
        return 0
    if args.pretty:
        print(json.dumps(output, indent=2, sort_keys=True))
    else:
        print(json.dumps(output, separators=(",", ":"), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
