#!/usr/bin/env python3
"""Render the branch-specific README profile from the checked-in release matrix.

The profile explains the compatibility delta owned by the current branch without
maintaining another version inventory. ``master`` is rendered as an integration
branch; every release branch is rendered from that branch's own matrix.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote

import matrix as release_matrix


START_MARKER = "<!-- branch-profile:start -->"
END_MARKER = "<!-- branch-profile:end -->"
LEGACY_HEADER = "# Quick Skin\n\n"
LEGACY_END = "\n## Verified releases\n"
SHARED_STATUS_END = "\n## Release status\n"
GITHUB_SOURCES = re.compile(
    r"^https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/?$"
)


class BranchReadmeError(ValueError):
    pass


@dataclass(frozen=True)
class BranchFacts:
    release_branch: str
    version: str
    loaders: tuple[str, ...]
    loader_names: tuple[str, ...]
    java: int
    runtime_pins: tuple[str, ...]
    overlay_paths: tuple[str, ...]
    canonical_paths: tuple[str, ...]


def _mapping(value: object, *, name: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise BranchReadmeError(f"{name} must be an object")
    return value


def _non_empty_string(value: object, *, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise BranchReadmeError(f"{name} must be a non-empty string")
    return value


def extract_branch_facts(data: Mapping[str, Any]) -> BranchFacts:
    project = _mapping(data.get("project"), name="project")
    release_branch = _non_empty_string(
        project.get("release_branch"), name="project.release_branch"
    )
    artifacts_value = data.get("artifacts")
    runtimes_value = data.get("runtimes")
    if not isinstance(artifacts_value, list) or not artifacts_value:
        raise BranchReadmeError("matrix must contain release artifacts")
    if not isinstance(runtimes_value, list) or not runtimes_value:
        raise BranchReadmeError("matrix must contain locked runtimes")

    artifacts = [
        _mapping(artifact, name="artifact") for artifact in artifacts_value
    ]
    versions = {
        _non_empty_string(
            artifact.get("artifact_version"), name="artifact.artifact_version"
        )
        for artifact in artifacts
    }
    if len(versions) != 1:
        raise BranchReadmeError(
            f"branch README requires one Minecraft version, found {sorted(versions)!r}"
        )
    version = next(iter(versions))

    loaders = tuple(
        dict.fromkeys(
            _non_empty_string(artifact.get("loader"), name="artifact.loader")
            for artifact in artifacts
        )
    )
    unknown_loaders = set(loaders) - release_matrix.KNOWN_LOADERS
    if unknown_loaders:
        raise BranchReadmeError(f"unsupported loaders: {sorted(unknown_loaders)!r}")
    loader_names = tuple(
        release_matrix.LOADER_DISPLAY_NAMES[loader] for loader in loaders
    )

    java_versions = {artifact.get("java") for artifact in artifacts}
    if len(java_versions) != 1:
        raise BranchReadmeError(
            f"release artifacts disagree on Java: {java_versions!r}"
        )
    java = next(iter(java_versions))
    if isinstance(java, bool) or not isinstance(java, int):
        raise BranchReadmeError("artifact Java must be an integer")

    runtimes: dict[str, Mapping[str, Any]] = {}
    for runtime_value in runtimes_value:
        runtime = _mapping(runtime_value, name="runtime")
        node = _non_empty_string(
            runtime.get("artifact_node"), name="runtime.artifact_node"
        )
        if node in runtimes:
            raise BranchReadmeError(f"duplicate runtime for {node}")
        runtimes[node] = runtime

    runtime_pins: list[str] = []
    for artifact in artifacts:
        node = _non_empty_string(
            artifact.get("artifact_node"), name="artifact.artifact_node"
        )
        loader = _non_empty_string(artifact.get("loader"), name="artifact.loader")
        runtime = runtimes.get(node)
        if runtime is None:
            raise BranchReadmeError(f"artifact {node} has no locked runtime")
        loader_version = _non_empty_string(
            runtime.get("loader_version"), name=f"runtime {node}.loader_version"
        )
        loader_label = (
            "Fabric Loader"
            if loader == "fabric"
            else release_matrix.LOADER_DISPLAY_NAMES[loader]
        )
        pieces = [f"{loader_label} `{loader_version}`"]
        if loader == "fabric":
            pieces.append(
                "Fabric API `"
                + _non_empty_string(
                    runtime.get("fabric_api"), name=f"runtime {node}.fabric_api"
                )
                + "`"
            )
        architectury = _mapping(
            runtime.get("architectury"), name=f"runtime {node}.architectury"
        )
        pieces.append(
            "Architectury API `"
            + _non_empty_string(
                architectury.get("version"),
                name=f"runtime {node}.architectury.version",
            )
            + "`"
        )
        compatibility_patch = runtime.get("compatibility_patch")
        if compatibility_patch is not None:
            pieces.append(
                "compatibility patch `"
                + _non_empty_string(
                    compatibility_patch,
                    name=f"runtime {node}.compatibility_patch",
                )
                + "`"
            )
        runtime_pins.append(", ".join(pieces))

    overlays = _mapping(data.get("source_overlays"), name="source_overlays")
    overlay_paths: list[str] = []
    canonical_paths: list[str] = []
    for module in ("common", *loaders):
        routes = _mapping(overlays.get(module), name=f"source_overlays.{module}")
        overlay = routes.get(version)
        if overlay is None:
            canonical_paths.append(f"`{module}/src/main`")
        else:
            overlay_name = _non_empty_string(
                overlay, name=f"source_overlays.{module}.{version}"
            )
            overlay_paths.append(f"`{module}/src/{overlay_name}`")

    return BranchFacts(
        release_branch=release_branch,
        version=version,
        loaders=loaders,
        loader_names=loader_names,
        java=java,
        runtime_pins=tuple(runtime_pins),
        overlay_paths=tuple(overlay_paths),
        canonical_paths=tuple(canonical_paths),
    )


def github_repository(project: Mapping[str, Any]) -> str:
    sources = _non_empty_string(project.get("sources"), name="project.sources")
    match = GITHUB_SOURCES.fullmatch(sources)
    if match is None:
        raise BranchReadmeError(
            "project.sources must be a canonical GitHub repository URL"
        )
    return match.group(1)


def workflow_badge(repository: str, branch: str, label: str) -> str:
    encoded_branch = quote(branch, safe="")
    encoded_query = quote(f"branch:{branch}", safe="")
    workflow = "build-gate.yml"
    image = (
        f"https://github.com/{repository}/actions/workflows/{workflow}/badge.svg"
        f"?branch={encoded_branch}"
    )
    target = (
        f"https://github.com/{repository}/actions/workflows/{workflow}"
        f"?query={encoded_query}"
    )
    return f"[![{label}]({image})]({target})"


def _profile_rows(facts: BranchFacts, *, column: str) -> list[str]:
    overlays = "<br>".join(facts.overlay_paths) if facts.overlay_paths else "None"
    canonical = (
        "<br>".join(facts.canonical_paths)
        if facts.canonical_paths
        else "None"
    )
    return [
        f"| Compatibility concern | {column} |",
        "|---|---|",
        f"| Minecraft | Exactly `{facts.version}` |",
        f"| Loaders | {' + '.join(facts.loader_names)} |",
        f"| Artifact Java | `{facts.java}` |",
        "| Packaged E2E runtime pins | " + "<br>".join(facts.runtime_pins) + " |",
        f"| Version-specific overlay roots | {overlays} |",
        f"| Modules without a version-specific overlay | {canonical} |",
        "| Gradle/Stonecutter launcher | JDK 21 or newer |",
    ]


def render_branch_profile(
    data: Mapping[str, Any], *, profile_branch: str
) -> str:
    if data.get("schema_version") == 3:
        return render_unified_profile(data, profile_branch=profile_branch)
    project = _mapping(data.get("project"), name="project")
    facts = extract_branch_facts(data)
    if profile_branch != "master" and profile_branch != facts.release_branch:
        raise BranchReadmeError(
            f"profile branch {profile_branch!r} does not match matrix release branch "
            f"{facts.release_branch!r}"
        )

    repository = github_repository(project)
    badge_label = (
        "Master build gate"
        if profile_branch == "master"
        else f"Build {facts.version}"
    )
    modrinth_id = _non_empty_string(
        project.get("modrinth_id"), name="project.modrinth_id"
    )
    curseforge_id = project.get("curseforge_id")
    if isinstance(curseforge_id, bool) or not isinstance(curseforge_id, int):
        raise BranchReadmeError("project.curseforge_id must be an integer")

    lines = [
        START_MARKER,
        "<!-- Generated by scripts/release/branch_readme.py from this branch's matrix. -->",
        workflow_badge(repository, profile_branch, badge_label),
        "",
        "Quick Skin is a client-and-server Minecraft mod for changing skins and capes in-game. "
        "It supports local and network-synchronized appearances, HD textures, animated capes, "
        "and optional integrations without requiring players to leave the game.",
        "",
        f"- [Modrinth](https://modrinth.com/mod/quick-skin) (`{modrinth_id}`)",
        "- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/quick-skin) "
        f"(`{curseforge_id}`)",
        "",
    ]

    if profile_branch == "master":
        lines.extend(
            [
                "## About `master`",
                "",
                "`master` is the shared integration branch, not a publishable Minecraft release "
                "branch. Its checked-in matrix is the compatibility baseline used to build and "
                "validate shared changes before tested synchronization pull requests adapt them "
                "to every release branch.",
                "",
                *_profile_rows(facts, column="Current integration baseline"),
                "",
                "The runtime values above are exact packaged-E2E test anchors, not minimum "
                "dependency claims. Installable dependency ranges remain authoritative in the "
                "release matrix and generated JAR metadata.",
                "",
                f"The baseline currently mirrors `{facts.release_branch}`. Installable artifacts, "
                "exact compatibility, and final Build/E2E evidence belong to the version branches "
                "listed under [Verified releases](#verified-releases).",
            ]
        )
    else:
        lines.extend(
            [
                "## About this version branch",
                "",
                f"`{facts.release_branch}` is the independently buildable release branch for "
                f"Minecraft `{facts.version}` on {' + '.join(facts.loader_names)}. Compared with "
                "the other release branches, its compatibility delta is:",
                "",
                *_profile_rows(facts, column="This release branch"),
                "",
                "The runtime values above are exact packaged-E2E test anchors, not minimum "
                "dependency claims. Installable dependency ranges remain authoritative in the "
                "release matrix and generated JAR metadata.",
                "",
                "These are the branch facts expected to vary across Minecraft releases. Shared "
                "feature behavior remains aligned with `master` and reaches this branch through "
                "tested synchronization pull requests.",
            ]
        )

    lines.append(END_MARKER)
    return "\n".join(lines)


def render_unified_profile(data: Mapping[str, Any], *, profile_branch: str) -> str:
    release_matrix.validate_matrix(dict(data))
    if profile_branch != data["project"]["release_branch"]:
        raise BranchReadmeError("profile branch does not match the unified source branch")
    repository = github_repository(data["project"])
    versions = sorted({row["artifact_version"] for row in data["artifacts"]},
                      key=lambda value: tuple(int(part) for part in value.split(".")))
    lines = [
        START_MARKER,
        "<!-- Generated by scripts/release/branch_readme.py from the release matrix. -->",
        workflow_badge(repository, profile_branch, "Master build gate"),
        "",
        "Quick Skin is a client-and-server Minecraft mod for changing skins and capes in-game. "
        "It supports synchronized appearances, HD textures, animated capes and optional integrations.",
        "",
        "[Modrinth](https://modrinth.com/mod/quick-skin) · "
        "[CurseForge](https://www.curseforge.com/minecraft/mc-mods/quick-skin)",
        "",
        "## Shared multi-version source",
        "",
        "Features and compatibility adapters compile as separate Gradle modules. The selected "
        "modules are assembled into one production JAR per Minecraft and loader target. "
        "`architecture/modules.json` owns module dependencies; `release/release-matrix.json` "
        "owns the targets currently configured in this checkout:",
        "",
        "| Minecraft | Loaders | Java |",
        "|---|---|---:|",
    ]
    for version in versions:
        facts = extract_branch_facts(release_matrix.select_release_target(dict(data), version))
        lines.append(f"| `{version}` | {' + '.join(facts.loader_names)} | `{facts.java}` |")
    lines.extend([
        "",
        "All targets build from the same source revision. Publication identity remains per "
        "Minecraft version (`mc<version>-v<mod_version>`); the complete build bundle is not "
        "a publishable release. Dependency ranges remain in the matrix and generated JAR metadata.",
        "",
        "The [migration plan](docs/architecture/MODULAR-REWORK.md) records remaining API, selective "
        "CI, governance and evidence work. The status table derives target release identities "
        "from this same matrix.",
        END_MARKER,
    ])
    return "\n".join(lines)


def replace_profile_section(
    readme: str, section: str, *, bootstrap: bool = False
) -> str:
    start_count = readme.count(START_MARKER)
    end_count = readme.count(END_MARKER)
    if start_count == 1 and end_count == 1:
        start = readme.index(START_MARKER)
        try:
            end = readme.index(END_MARKER, start) + len(END_MARKER)
        except ValueError as exc:
            raise BranchReadmeError(
                "README branch-profile markers are out of order"
            ) from exc
        owns_header = (
            readme.startswith(LEGACY_HEADER)
            and start == len(LEGACY_HEADER)
            and sum(readme.count(boundary) for boundary in (LEGACY_END, SHARED_STATUS_END)) == 1
            and any(readme[end:].startswith("\n" + boundary)
                    for boundary in (LEGACY_END, SHARED_STATUS_END))
        )
        if not owns_header:
            if bootstrap:
                return _bootstrap_profile(readme, section)
            raise BranchReadmeError(
                "README branch profile must occupy the entire generated header"
            )
        return f"{readme[:start]}{section}{readme[end:]}"
    if start_count or end_count:
        raise BranchReadmeError(
            "README must contain exactly one branch-profile marker pair"
        )
    if not bootstrap:
        raise BranchReadmeError(
            "README must contain exactly one branch-profile marker pair"
        )
    return _bootstrap_profile(readme, section)


def _bootstrap_profile(readme: str, section: str) -> str:
    if not readme.startswith(LEGACY_HEADER) or readme.count(LEGACY_END) != 1:
        raise BranchReadmeError(
            "README legacy header cannot be migrated deterministically"
        )
    legacy_end = readme.index(LEGACY_END)
    return f"{LEGACY_HEADER}{section}\n{readme[legacy_end:]}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--matrix", type=Path, default=Path("release/release-matrix.json")
    )
    parser.add_argument("--readme", type=Path, default=Path("README.md"))
    parser.add_argument(
        "--profile-branch",
        required=True,
        help="master or the exact project.release_branch from the active matrix",
    )
    parser.add_argument(
        "--bootstrap",
        action="store_true",
        help="replace the pre-marker README header during the one-time rollout",
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)

    try:
        data = release_matrix.load_matrix(args.matrix)
        section = render_branch_profile(data, profile_branch=args.profile_branch)
        original = args.readme.read_text(encoding="utf-8")
        updated = replace_profile_section(
            original, section, bootstrap=args.bootstrap
        )
    except (OSError, BranchReadmeError, release_matrix.MatrixError) as exc:
        parser.error(str(exc))

    if args.check:
        if updated != original:
            print(f"{args.readme} branch profile is stale", file=sys.stderr)
            return 1
    elif args.write:
        if updated != original:
            args.readme.write_text(updated, encoding="utf-8")
    else:
        sys.stdout.write(updated)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
