#!/usr/bin/env python3
"""Validate, resolve, and materialize immutable optional-mod E2E inputs."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import shutil
import ssl
import sys
import tempfile
import time
import urllib.parse
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Iterable

from scenario_contract import (
    DEFAULT_CONTRACT as DEFAULT_SCENARIO_CONTRACT,
    ScenarioContractError,
    load_contract as load_scenario_contract,
)
from visual_similarity import SimilarityError, normalize_regions


REPO = Path(__file__).resolve().parent.parent
RELEASE_SCRIPTS = REPO / "scripts" / "release"
sys.path.insert(0, str(RELEASE_SCRIPTS))

from matrix import gha_matrix, load_matrix, read_mod_version  # noqa: E402


DEFAULT_CONTRACT = Path(__file__).with_name("mod-compatibility-contract.json")
SCHEMA_VERSION = 6
MAX_CONTRACT_BYTES = 2 * 1024 * 1024
MAX_DOWNLOAD_BYTES = 128 * 1024 * 1024
MAX_FILES_PER_ARTIFACT = 4
MAX_ARTIFACTS = 256
IDENTIFIER = re.compile(r"^[a-z][a-z0-9-]{1,63}$")
SAFE_VALUE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._+ -]{0,255}$")
VERSION = re.compile(r"^[0-9]+(?:\.[0-9]+)+$")
LOCK_DATE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
PUBLISHED_AT = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?Z$"
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SHA512 = re.compile(r"^[0-9a-f]{128}$")
MODRINTH_ID = re.compile(r"^[A-Za-z0-9]{8}$")
CAPTURE_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{1,191}$")
LOADERS = frozenset({"fabric", "forge", "neoforge"})
INSTALL_SIDES = frozenset({"client", "client-and-server"})
VERSION_TYPES = frozenset({"release", "beta"})
ALLOWED_DOWNLOAD_HOSTS = frozenset({"cdn.modrinth.com"})
BASE_MATRIX_KINDS = frozenset({"runtime", "native-anchors", "pr-anchors"})


class CompatibilityContractError(ValueError):
    """Raised when the optional-mod lock or requested lane is invalid."""


@dataclass(frozen=True)
class LockedFile:
    filename: str
    url: str
    size: int
    sha256: str
    sha512: str


@dataclass(frozen=True)
class LockedArtifact:
    version_id: str
    version_number: str
    version_type: str
    published_at: str
    loader: str
    game_versions: tuple[str, ...]
    files: tuple[LockedFile, ...]


@dataclass(frozen=True)
class ExcludedLane:
    runtime_version: str
    loader: str
    reason: str


@dataclass(frozen=True)
class CompatibilityEvidence:
    baseline_with_mod: str
    apply_local_skin_with_mod: str


@dataclass(frozen=True)
class CompatibilityReviewRegions:
    baseline_with_mod: tuple[tuple[float, float, float, float], ...]
    apply_local_skin_with_mod: tuple[tuple[float, float, float, float], ...]


@dataclass(frozen=True)
class CompatibilityReferenceCaptures:
    baseline_with_mod: str
    apply_local_skin_with_mod: str


@dataclass(frozen=True)
class CompatibilityMultiplayer:
    evidence: CompatibilityEvidence
    review_regions: CompatibilityReviewRegions


@dataclass(frozen=True)
class CompatibilityMod:
    id: str
    name: str
    project_id: str
    install_on: str
    loaders: tuple[str, ...]
    allowed_version_types: tuple[str, ...]
    provided_dependencies: tuple[str, ...]
    evidence: CompatibilityEvidence
    review_regions: CompatibilityReviewRegions
    multiplayer: CompatibilityMultiplayer | None
    supported_game_versions: tuple[str, ...] | None
    excluded_lanes: tuple[ExcludedLane, ...]
    artifacts: tuple[LockedArtifact, ...]
    reference_captures: CompatibilityReferenceCaptures | None = None


@dataclass(frozen=True)
class CompatibilityContract:
    path: Path
    sha256: str
    lock_revision: str
    mods: tuple[CompatibilityMod, ...]

    def mod(self, mod_id: str) -> CompatibilityMod:
        matches = [item for item in self.mods if item.id == mod_id]
        if len(matches) != 1:
            raise CompatibilityContractError(f"unknown compatibility mod {mod_id!r}")
        return matches[0]


@dataclass(frozen=True)
class CompatibilityLane:
    contract_sha256: str
    mod: CompatibilityMod
    artifact: LockedArtifact
    artifact_node: str
    runtime_version: str
    loader: str

    @property
    def id(self) -> str:
        return (
            f"{self.artifact_node}--{self.mod.id}--mod-compatibility"
            .replace(".", "_")
        )

    def public_identity(self) -> dict[str, Any]:
        return {
            "contract_sha256": self.contract_sha256,
            "id": self.mod.id,
            "name": self.mod.name,
            "project_id": self.mod.project_id,
            "install_on": self.mod.install_on,
            "version_id": self.artifact.version_id,
            "version_number": self.artifact.version_number,
            "version_type": self.artifact.version_type,
            "published_at": self.artifact.published_at,
            "loader": self.loader,
            "runtime_version": self.runtime_version,
            "files": [
                {
                    "filename": item.filename,
                    "url": item.url,
                    "size": item.size,
                    "sha256": item.sha256,
                    "sha512": item.sha512,
                }
                for item in self.artifact.files
            ],
        }


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def _reject_nonfinite(value: str) -> None:
    raise ValueError(f"non-finite JSON number {value!r}")


def _parse_float(value: str) -> float:
    parsed = float(value)
    if not math.isfinite(parsed):
        raise ValueError(f"non-finite JSON number {value!r}")
    return parsed


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        actual = sorted(value) if isinstance(value, dict) else type(value).__name__
        raise CompatibilityContractError(
            f"{label} keys disagree: expected {sorted(expected)}, found {actual}"
        )
    return value


def _string(value: Any, label: str, pattern: re.Pattern[str] = SAFE_VALUE) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise CompatibilityContractError(f"{label} is invalid: {value!r}")
    return value


def _evidence_text(value: Any, label: str) -> str:
    if (
        not isinstance(value, str)
        or not 1 <= len(value) <= 2048
        or value != value.strip()
        or any(ord(character) < 32 or ord(character) > 126 for character in value)
    ):
        raise CompatibilityContractError(
            f"{label} must be bounded printable ASCII text"
        )
    return value


def _string_list(
    value: Any,
    label: str,
    *,
    pattern: re.Pattern[str] = SAFE_VALUE,
    allow_empty: bool = False,
) -> tuple[str, ...]:
    if (
        not isinstance(value, list)
        or (not value and not allow_empty)
        or any(not isinstance(item, str) or pattern.fullmatch(item) is None for item in value)
        or len(value) != len(set(value))
    ):
        raise CompatibilityContractError(f"{label} must be a unique string list")
    return tuple(value)


def _validate_url(value: Any, label: str) -> str:
    if not isinstance(value, str) or len(value) > 2048:
        raise CompatibilityContractError(f"{label} must be a bounded HTTPS URL")
    parsed = urllib.parse.urlsplit(value)
    if (
        parsed.scheme != "https"
        or parsed.hostname not in ALLOWED_DOWNLOAD_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in (None, 443)
        or not parsed.path.startswith("/data/")
        or parsed.query
        or parsed.fragment
    ):
        raise CompatibilityContractError(f"{label} is not an allowed Modrinth CDN URL")
    return value


def _validate_file(value: Any, label: str) -> LockedFile:
    item = _exact_keys(value, {"filename", "url", "size", "sha256", "sha512"}, label)
    filename = _string(item["filename"], f"{label}.filename")
    if Path(filename).name != filename or not filename.lower().endswith(".jar"):
        raise CompatibilityContractError(f"{label}.filename must be one safe JAR basename")
    url = _validate_url(item["url"], f"{label}.url")
    size = item["size"]
    if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= MAX_DOWNLOAD_BYTES:
        raise CompatibilityContractError(f"{label}.size is outside the download limit")
    return LockedFile(
        filename=filename,
        url=url,
        size=size,
        sha256=_string(item["sha256"], f"{label}.sha256", SHA256),
        sha512=_string(item["sha512"], f"{label}.sha512", SHA512),
    )


def _validate_artifact(value: Any, label: str) -> LockedArtifact:
    item = _exact_keys(
        value,
        {
            "version_id",
            "version_number",
            "version_type",
            "published_at",
            "loader",
            "game_versions",
            "files",
        },
        label,
    )
    loader = _string(item["loader"], f"{label}.loader", IDENTIFIER)
    if loader not in LOADERS:
        raise CompatibilityContractError(f"{label}.loader is unsupported")
    version_type = _string(item["version_type"], f"{label}.version_type", IDENTIFIER)
    if version_type not in VERSION_TYPES:
        raise CompatibilityContractError(f"{label}.version_type is unsupported")
    files = item["files"]
    if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES_PER_ARTIFACT:
        raise CompatibilityContractError(f"{label}.files is empty or too large")
    locked_files = tuple(
        _validate_file(entry, f"{label}.files[{index}]")
        for index, entry in enumerate(files)
    )
    if len({entry.filename.casefold() for entry in locked_files}) != len(locked_files):
        raise CompatibilityContractError(f"{label}.files contains colliding names")
    return LockedArtifact(
        version_id=_string(item["version_id"], f"{label}.version_id", MODRINTH_ID),
        version_number=_string(item["version_number"], f"{label}.version_number"),
        version_type=version_type,
        published_at=_string(
            item["published_at"], f"{label}.published_at", PUBLISHED_AT
        ),
        loader=loader,
        game_versions=_string_list(
            item["game_versions"], f"{label}.game_versions", pattern=VERSION
        ),
        files=locked_files,
    )


def _validate_mod(value: Any, label: str) -> CompatibilityMod:
    item = _exact_keys(
        value,
        {
            "id",
            "name",
            "project_id",
            "install_on",
            "loaders",
            "allowed_version_types",
            "provided_dependencies",
            "evidence",
            "review_regions",
            "reference_captures",
            "multiplayer",
            "supported_game_versions",
            "excluded_lanes",
            "artifacts",
        },
        label,
    )
    mod_id = _string(item["id"], f"{label}.id", IDENTIFIER)
    install_on = _string(item["install_on"], f"{label}.install_on", IDENTIFIER)
    if install_on not in INSTALL_SIDES:
        raise CompatibilityContractError(f"{label}.install_on is unsupported")
    loaders = _string_list(item["loaders"], f"{label}.loaders", pattern=IDENTIFIER)
    if not set(loaders) <= LOADERS:
        raise CompatibilityContractError(f"{label}.loaders contains an unsupported loader")
    version_types = _string_list(
        item["allowed_version_types"],
        f"{label}.allowed_version_types",
        pattern=IDENTIFIER,
    )
    if not set(version_types) <= VERSION_TYPES:
        raise CompatibilityContractError(f"{label}.allowed_version_types is unsupported")
    evidence_value = _exact_keys(
        item["evidence"],
        {"baseline_with_mod", "apply_local_skin_with_mod"},
        f"{label}.evidence",
    )
    evidence = CompatibilityEvidence(
        baseline_with_mod=_evidence_text(
            evidence_value["baseline_with_mod"],
            f"{label}.evidence.baseline_with_mod",
        ),
        apply_local_skin_with_mod=_evidence_text(
            evidence_value["apply_local_skin_with_mod"],
            f"{label}.evidence.apply_local_skin_with_mod",
        ),
    )
    review_regions_value = _exact_keys(
        item["review_regions"],
        {"baseline_with_mod", "apply_local_skin_with_mod"},
        f"{label}.review_regions",
    )
    try:
        review_regions = CompatibilityReviewRegions(
            baseline_with_mod=normalize_regions(
                review_regions_value["baseline_with_mod"]
            ),
            apply_local_skin_with_mod=normalize_regions(
                review_regions_value["apply_local_skin_with_mod"]
            ),
        )
    except SimilarityError as exc:
        raise CompatibilityContractError(
            f"{label}.review_regions is invalid: {exc}"
        ) from exc
    reference_captures_value = item["reference_captures"]
    reference_captures: CompatibilityReferenceCaptures | None
    if reference_captures_value is None:
        reference_captures = None
    else:
        reference_captures_item = _exact_keys(
            reference_captures_value,
            {"baseline_with_mod", "apply_local_skin_with_mod"},
            f"{label}.reference_captures",
        )
        reference_captures = CompatibilityReferenceCaptures(
            baseline_with_mod=_string(
                reference_captures_item["baseline_with_mod"],
                f"{label}.reference_captures.baseline_with_mod",
                CAPTURE_ID,
            ),
            apply_local_skin_with_mod=_string(
                reference_captures_item["apply_local_skin_with_mod"],
                f"{label}.reference_captures.apply_local_skin_with_mod",
                CAPTURE_ID,
            ),
        )
    multiplayer_value = item["multiplayer"]
    multiplayer: CompatibilityMultiplayer | None
    if multiplayer_value is None:
        multiplayer = None
    else:
        multiplayer_item = _exact_keys(
            multiplayer_value,
            {"evidence", "review_regions"},
            f"{label}.multiplayer",
        )
        multiplayer_evidence_value = _exact_keys(
            multiplayer_item["evidence"],
            {"baseline_with_mod", "apply_local_skin_with_mod"},
            f"{label}.multiplayer.evidence",
        )
        multiplayer_review_regions_value = _exact_keys(
            multiplayer_item["review_regions"],
            {"baseline_with_mod", "apply_local_skin_with_mod"},
            f"{label}.multiplayer.review_regions",
        )
        try:
            multiplayer = CompatibilityMultiplayer(
                evidence=CompatibilityEvidence(
                    baseline_with_mod=_evidence_text(
                        multiplayer_evidence_value["baseline_with_mod"],
                        f"{label}.multiplayer.evidence.baseline_with_mod",
                    ),
                    apply_local_skin_with_mod=_evidence_text(
                        multiplayer_evidence_value["apply_local_skin_with_mod"],
                        f"{label}.multiplayer.evidence.apply_local_skin_with_mod",
                    ),
                ),
                review_regions=CompatibilityReviewRegions(
                    baseline_with_mod=normalize_regions(
                        multiplayer_review_regions_value["baseline_with_mod"]
                    ),
                    apply_local_skin_with_mod=normalize_regions(
                        multiplayer_review_regions_value["apply_local_skin_with_mod"]
                    ),
                ),
            )
        except SimilarityError as exc:
            raise CompatibilityContractError(
                f"{label}.multiplayer.review_regions is invalid: {exc}"
            ) from exc
    supported = item["supported_game_versions"]
    supported_versions = (
        None
        if supported is None
        else _string_list(supported, f"{label}.supported_game_versions", pattern=VERSION)
    )
    excluded_value = item["excluded_lanes"]
    if not isinstance(excluded_value, list) or len(excluded_value) > 64:
        raise CompatibilityContractError(f"{label}.excluded_lanes must be a bounded list")
    excluded_lanes: list[ExcludedLane] = []
    excluded_keys: set[tuple[str, str]] = set()
    for index, raw_exclusion in enumerate(excluded_value):
        exclusion_label = f"{label}.excluded_lanes[{index}]"
        exclusion = _exact_keys(
            raw_exclusion, {"runtime_version", "loader", "reason"}, exclusion_label
        )
        runtime_version = _string(
            exclusion["runtime_version"], f"{exclusion_label}.runtime_version", VERSION
        )
        loader = _string(exclusion["loader"], f"{exclusion_label}.loader", IDENTIFIER)
        if loader not in loaders:
            raise CompatibilityContractError(
                f"{exclusion_label}.loader is outside the mod's supported loaders"
            )
        key = (runtime_version, loader)
        if key in excluded_keys:
            raise CompatibilityContractError(f"{label}.excluded_lanes contains duplicates")
        excluded_keys.add(key)
        excluded_lanes.append(
            ExcludedLane(
                runtime_version=runtime_version,
                loader=loader,
                reason=_string(exclusion["reason"], f"{exclusion_label}.reason"),
            )
        )
    artifacts_value = item["artifacts"]
    if not isinstance(artifacts_value, list) or len(artifacts_value) > MAX_ARTIFACTS:
        raise CompatibilityContractError(f"{label}.artifacts must be a bounded list")
    artifacts = tuple(
        _validate_artifact(entry, f"{label}.artifacts[{index}]")
        for index, entry in enumerate(artifacts_value)
    )
    lane_owners: dict[tuple[str, str], str] = {}
    for artifact in artifacts:
        if artifact.loader not in loaders:
            raise CompatibilityContractError(
                f"{label} locks {artifact.loader} outside its supported loaders"
            )
        if artifact.version_type not in version_types:
            raise CompatibilityContractError(
                f"{label} locks disallowed version type {artifact.version_type}"
            )
        for locked_file in artifact.files:
            path_parts = urllib.parse.urlsplit(locked_file.url).path.split("/")
            if (
                len(path_parts) != 6
                or path_parts[:2] != ["", "data"]
                or path_parts[2] != item["project_id"]
                or path_parts[3] != "versions"
                or path_parts[4] != artifact.version_id
                or urllib.parse.unquote(path_parts[5]) != locked_file.filename
            ):
                raise CompatibilityContractError(
                    f"{label} file URL disagrees with its project/version/filename identity"
                )
        for game_version in artifact.game_versions:
            key = (game_version, artifact.loader)
            if key in excluded_keys:
                raise CompatibilityContractError(
                    f"{label} locks an artifact for excluded lane "
                    f"{game_version}/{artifact.loader}"
                )
            if key in lane_owners:
                raise CompatibilityContractError(
                    f"{label} locks multiple artifacts for {game_version}/{artifact.loader}"
                )
            lane_owners[key] = artifact.version_id
            if supported_versions is not None and game_version not in supported_versions:
                raise CompatibilityContractError(
                    f"{label} locks unsupported Quick Skin version {game_version}"
                )
    return CompatibilityMod(
        id=mod_id,
        name=_string(item["name"], f"{label}.name"),
        project_id=_string(item["project_id"], f"{label}.project_id", MODRINTH_ID),
        install_on=install_on,
        loaders=loaders,
        allowed_version_types=version_types,
        provided_dependencies=_string_list(
            item["provided_dependencies"],
            f"{label}.provided_dependencies",
            pattern=MODRINTH_ID,
            allow_empty=True,
        ),
        evidence=evidence,
        review_regions=review_regions,
        multiplayer=multiplayer,
        supported_game_versions=supported_versions,
        excluded_lanes=tuple(excluded_lanes),
        artifacts=artifacts,
        reference_captures=reference_captures,
    )


def load_contract(path: Path = DEFAULT_CONTRACT) -> CompatibilityContract:
    try:
        raw = path.read_bytes()
        if not raw or len(raw) > MAX_CONTRACT_BYTES:
            raise ValueError(f"contract must contain 1..{MAX_CONTRACT_BYTES} bytes")
        data = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonfinite,
            parse_float=_parse_float,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise CompatibilityContractError(f"cannot read compatibility contract {path}: {exc}") from exc
    root = _exact_keys(
        data, {"schema_version", "lock_revision", "artifact_source", "mods"}, "contract"
    )
    if root["schema_version"] != SCHEMA_VERSION:
        raise CompatibilityContractError(
            f"compatibility contract schema_version must be {SCHEMA_VERSION}"
        )
    source = _exact_keys(
        root["artifact_source"], {"kind", "api_base", "selection_policy"}, "artifact_source"
    )
    if source != {
        "kind": "modrinth-v2",
        "api_base": "https://api.modrinth.com/v2",
        "selection_policy": "newest-compatible-published-version-with-authored-exclusions-v2",
    }:
        raise CompatibilityContractError("compatibility artifact source policy is unsupported")
    mods_value = root["mods"]
    if not isinstance(mods_value, list) or not mods_value:
        raise CompatibilityContractError("compatibility contract must declare at least one mod")
    mods = tuple(
        _validate_mod(item, f"mods[{index}]") for index, item in enumerate(mods_value)
    )
    if len({mod.id for mod in mods}) != len(mods):
        raise CompatibilityContractError("compatibility contract contains duplicate mod ids")
    if len({mod.project_id for mod in mods}) != len(mods):
        raise CompatibilityContractError("compatibility contract contains duplicate projects")
    return CompatibilityContract(
        path=path,
        sha256=hashlib.sha256(raw).hexdigest(),
        lock_revision=_string(root["lock_revision"], "lock_revision", LOCK_DATE),
        mods=mods,
    )


def resolve_lane(
    contract: CompatibilityContract,
    *,
    mod_id: str,
    artifact_node: str,
    runtime_version: str,
    loader: str,
) -> CompatibilityLane:
    mod = contract.mod(mod_id)
    if loader not in mod.loaders:
        raise CompatibilityContractError(
            f"{mod.id} is not a Quick Skin integration for loader {loader}"
        )
    if mod.supported_game_versions is not None and runtime_version not in mod.supported_game_versions:
        raise CompatibilityContractError(
            f"{mod.id} is not implemented by Quick Skin for Minecraft {runtime_version}"
        )
    excluded = next(
        (
            item
            for item in mod.excluded_lanes
            if item.runtime_version == runtime_version and item.loader == loader
        ),
        None,
    )
    if excluded is not None:
        raise CompatibilityContractError(
            f"{mod.id} excludes {runtime_version}/{loader}: {excluded.reason}"
        )
    matches = [
        artifact
        for artifact in mod.artifacts
        if artifact.loader == loader and runtime_version in artifact.game_versions
    ]
    if len(matches) != 1:
        raise CompatibilityContractError(
            f"{mod.id} has {len(matches)} locked artifacts for {runtime_version}/{loader}"
        )
    return CompatibilityLane(
        contract.sha256, mod, matches[0], artifact_node, runtime_version, loader
    )


def build_plan(
    matrix_path: Path,
    contract_path: Path = DEFAULT_CONTRACT,
    *,
    base_matrix_kind: str = "runtime",
    scenario_contract_path: Path = DEFAULT_SCENARIO_CONTRACT,
) -> dict[str, Any]:
    if base_matrix_kind not in BASE_MATRIX_KINDS:
        raise CompatibilityContractError(
            f"unsupported base matrix kind {base_matrix_kind!r}"
        )
    matrix = load_matrix(matrix_path)
    contract = load_contract(contract_path)
    try:
        scenario_contract = load_scenario_contract(scenario_contract_path)
    except (ScenarioContractError, OSError) as exc:
        raise CompatibilityContractError(
            f"cannot load scenario contract for compatibility planning: {exc}"
        ) from exc
    local_scenarios = scenario_contract.scenarios_for_profile("compatibility")
    remote_scenarios = scenario_contract.scenarios_for_profile(
        "compatibility-remote"
    )
    if not local_scenarios or not remote_scenarios:
        raise CompatibilityContractError(
            "scenario contract must declare local and remote compatibility profiles"
        )
    base_rows = gha_matrix(
        matrix,
        base_matrix_kind,
        read_mod_version(matrix_path, matrix),
    )["include"]
    runnable: list[dict[str, Any]] = []
    not_applicable: list[dict[str, str]] = []
    for row in base_rows:
        for mod in contract.mods:
            reason: str | None = None
            if row["loader"] not in mod.loaders:
                reason = f"Quick Skin does not implement {mod.name} for {row['loader']}"
            else:
                excluded = next(
                    (
                        item
                        for item in mod.excluded_lanes
                        if item.runtime_version == row["runtime_version"]
                        and item.loader == row["loader"]
                    ),
                    None,
                )
                if excluded is not None:
                    reason = excluded.reason
            if reason is None and (
                mod.supported_game_versions is not None
                and row["runtime_version"] not in mod.supported_game_versions
            ):
                reason = (
                    f"Quick Skin does not implement {mod.name} for Minecraft "
                    f"{row['runtime_version']}"
                )
            elif reason is None:
                matches = [
                    artifact
                    for artifact in mod.artifacts
                    if artifact.loader == row["loader"]
                    and row["runtime_version"] in artifact.game_versions
                ]
                if not matches:
                    reason = (
                        f"Upstream publishes no locked compatible {mod.name} artifact for "
                        f"Minecraft {row['runtime_version']} / {row['loader']}"
                    )
                elif len(matches) != 1:
                    raise CompatibilityContractError(
                        f"ambiguous lock for {mod.id}/{row['runtime_version']}/{row['loader']}"
                    )
            if reason is not None:
                not_applicable.append(
                    {
                        "artifact_node": row["artifact_node"],
                        "runtime_version": row["runtime_version"],
                        "loader": row["loader"],
                        "mod": mod.id,
                        "name": mod.name,
                        "status": "not-applicable",
                        "reason": reason,
                    }
                )
                continue
            lane = resolve_lane(
                contract,
                mod_id=mod.id,
                artifact_node=row["artifact_node"],
                runtime_version=row["runtime_version"],
                loader=row["loader"],
            )
            runnable.append(
                {
                    **row,
                    "base_evidence_name": f"packaged-e2e-{row['id']}",
                    "id": lane.id,
                    "compatibility_mod": mod.id,
                    "compatibility_name": mod.name,
                    "compatibility_version": lane.artifact.version_number,
                    "compatibility_version_id": lane.artifact.version_id,
                    "compatibility_contract_sha256": contract.sha256,
                    "scenarios": ",".join(
                        (
                            *local_scenarios,
                            *(remote_scenarios if mod.multiplayer else ()),
                            row["scenarios"],
                        )
                    ),
                }
            )
    return {
        "schema_version": 1,
        "release_branch": matrix["project"]["release_branch"],
        "base_matrix_kind": base_matrix_kind,
        "compatibility_contract_sha256": contract.sha256,
        "lock_revision": contract.lock_revision,
        "runnable": runnable,
        "not_applicable": not_applicable,
    }


def _copy_stream(
    source: BinaryIO,
    destination: BinaryIO,
    *,
    expected_size: int,
) -> tuple[str, str, int]:
    sha256 = hashlib.sha256()
    sha512 = hashlib.sha512()
    total = 0
    while True:
        block = source.read(1024 * 1024)
        if not block:
            break
        total += len(block)
        if total > expected_size or total > MAX_DOWNLOAD_BYTES:
            raise CompatibilityContractError("compatibility download exceeded its locked size")
        sha256.update(block)
        sha512.update(block)
        destination.write(block)
    return sha256.hexdigest(), sha512.hexdigest(), total


def materialize_lane(lane: CompatibilityLane, destination: Path) -> tuple[Path, ...]:
    """Download one exact lane into a fresh directory and verify both locked hashes."""

    if destination.exists() or destination.is_symlink():
        raise CompatibilityContractError(
            f"compatibility download destination must be fresh: {destination}"
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}.", dir=destination.parent))
    outputs: list[Path] = []
    context = ssl.create_default_context()
    try:
        for locked in lane.artifact.files:
            target = staging / locked.filename
            request = urllib.request.Request(
                locked.url,
                headers={"User-Agent": "The-Plum-Team/Quick-Skin-Mod compatibility-e2e/1"},
            )
            last_error: Exception | None = None
            for attempt in range(1, 4):
                try:
                    with urllib.request.urlopen(request, timeout=120, context=context) as response:
                        _validate_url(response.geturl(), "redirected compatibility URL")
                        content_length = response.headers.get("Content-Length")
                        if content_length is not None and int(content_length) != locked.size:
                            raise CompatibilityContractError(
                                f"content length disagrees for {locked.filename}"
                            )
                        with target.open("xb") as output:
                            digest256, digest512, size = _copy_stream(
                                response, output, expected_size=locked.size
                            )
                            output.flush()
                            os.fsync(output.fileno())
                    if (
                        size != locked.size
                        or digest256 != locked.sha256
                        or digest512 != locked.sha512
                    ):
                        raise CompatibilityContractError(
                            f"locked hash/size mismatch for {locked.filename}"
                        )
                    os.chmod(target, 0o444)
                    outputs.append(target)
                    last_error = None
                    break
                except (OSError, ValueError, urllib.error.URLError, CompatibilityContractError) as exc:
                    target.unlink(missing_ok=True)
                    last_error = exc
                    if attempt < 3:
                        time.sleep(attempt * 2)
            if last_error is not None:
                raise CompatibilityContractError(
                    f"could not materialize {locked.filename}: {last_error}"
                ) from last_error
        os.replace(staging, destination)
        return tuple(destination / path.name for path in outputs)
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument(
        "--scenario-contract", type=Path, default=DEFAULT_SCENARIO_CONTRACT
    )
    parser.add_argument("--matrix", type=Path, default=REPO / "release/release-matrix.json")
    parser.add_argument(
        "--base-matrix-kind",
        choices=sorted(BASE_MATRIX_KINDS),
        default="runtime",
        help="matrix profile that produced the clean packaged evidence",
    )
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--validate", action="store_true")
    actions.add_argument("--plan", action="store_true")
    actions.add_argument("--github-matrix", action="store_true")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        if args.validate:
            contract = load_contract(args.contract)
            print(contract.sha256)
            return 0
        plan = build_plan(
            args.matrix,
            args.contract,
            base_matrix_kind=args.base_matrix_kind,
            scenario_contract_path=args.scenario_contract,
        )
        output: Any = {"include": plan["runnable"]} if args.github_matrix else plan
        json.dump(
            output,
            sys.stdout,
            indent=None if args.github_matrix else 2,
            separators=(",", ":") if args.github_matrix else None,
            ensure_ascii=False,
        )
        print()
        return 0
    except (CompatibilityContractError, OSError, ValueError) as exc:
        parser.exit(2, f"mod compatibility configuration failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
