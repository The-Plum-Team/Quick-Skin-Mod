#!/usr/bin/env python3
"""Reproduce and authenticate the protected version-port merge boundary.

The caller supplies an exact clean target commit (which must be ``HEAD``) and
an exact source commit.  This controller performs the real no-commit merge,
classifies the complete original unmerged index, applies only the reviewed
mechanical resolutions, and emits deterministic evidence for that mechanical
state.

``prepare`` leaves the resulting merge in place.  An optional alternate Git
index may then provide resolutions for *only* the classifier-approved AI
paths.  ``probe`` never accepts such an index and restores the repository to
its original clean target commit before emitting the same mechanical
evidence.  Every failure after merge start also restores the initially clean
checkout.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from ai_patch_policy import PolicyError, normalize_path
from version_port_conflicts import (
    DATAPACK_FUNCTION_MIGRATION_CONFLICTS,
    DATAPACK_FUNCTION_MIGRATION_TRIGGER,
    MAX_MATRIX_BYTES,
    ConflictClassification,
    ConflictClassificationError,
    TargetMatrixProfile,
    classify_conflicts,
    is_inactive_overlay_path,
    read_target_matrix_profile,
)


SCHEMA_VERSION = 1
MATRIX_PATH = "release/release-matrix.json"
REGULAR_MODES = frozenset({"100644", "100755"})
MAX_GIT_STDOUT_BYTES = 64 * 1024 * 1024
MAX_GIT_STDERR_BYTES = 256 * 1024
MAX_INDEX_BYTES = 64 * 1024 * 1024
MAX_INDEX_ENTRIES = 200_000
MAX_PROTECTED_BLOB_BYTES = 8 * 1024 * 1024
MAX_AI_BLOB_BYTES = 2 * 1024 * 1024
MAX_AI_BLOBS_BYTES = 2 * 1024 * 1024
BOT_NAME = "github-actions[bot]"
BOT_EMAIL = "41898282+github-actions[bot]@users.noreply.github.com"
CONFLICT_MARKERS = (b"<<<<<<< ", b"||||||| ", b">>>>>>> ")
DATAPACK_FUNCTION_RENAMES = (
    (
        DATAPACK_FUNCTION_MIGRATION_TRIGGER,
        "e2e/server-template/datapack/data/qs_e2e/function/load.mcfunction",
    ),
    (
        "e2e/server-template/datapack/data/qs_e2e/functions/tick.mcfunction",
        "e2e/server-template/datapack/data/qs_e2e/function/tick.mcfunction",
    ),
    (
        "e2e/server-template/datapack/data/minecraft/tags/functions/load.json",
        "e2e/server-template/datapack/data/minecraft/tags/function/load.json",
    ),
    (
        "e2e/server-template/datapack/data/minecraft/tags/functions/tick.json",
        "e2e/server-template/datapack/data/minecraft/tags/function/tick.json",
    ),
)
DATAPACK_PLURAL_PREFIXES = (
    "e2e/server-template/datapack/data/qs_e2e/functions/",
    "e2e/server-template/datapack/data/minecraft/tags/functions/",
)
NAMESPACED_GAME_RULES = {
    b"gamerule doWeatherCycle false\n": b"gamerule minecraft:advance_weather false\n",
    b"gamerule doDaylightCycle false\n": b"gamerule minecraft:advance_time false\n",
    b"gamerule doMobSpawning false\n": b"gamerule minecraft:spawn_mobs false\n",
    b"gamerule spawnRadius 0\n": b"gamerule minecraft:respawn_radius 0\n",
}
CPM_TRANSITION_POLICY_PATH = "scripts/release/tests/test_cpm_transition_policy.py"
CPM_TRANSITION_POLICY_SHA256 = (
    "6cebd1a4dad2b1be2e98ecf2a73487c0786fa4e77d14311e15ad21e7871a9ce2"
)
MIXIN_POLICY_PATH = "scripts/release/tests/test_mixin_policy.py"
MIXIN_POLICY_SOURCE_FIXTURE_PATH = (
    "scripts/ci/version_port_migrations/"
    "mixin-policy-with-mandatory-hand.py.fixture"
)
MIXIN_POLICY_SHA256 = (
    "ab2283c17662caeb8772ead0c180889a92a379cc376325a95150b34450c09d33"
)
COMMON_HAND_RENDERER_PATH = (
    "common/src/main/java/com/quickskin/mod/mixin/ItemInHandRendererMixin.java"
)
COMMON_HAND_RENDERER_SHA256 = (
    "b8ac3fc281be249207003971291277fc92d467a45be8be4f86dac8bb7fee641c"
)
COMMON_HAND_MULTIPLICITY_POLICY_MARKERS = (
    b"def test_immediate_hand_redirect_matches_vanilla_multiplicity",
    b'legacy_guard = annotation.index("//? if <1.21.2 {")',
    b'self.assertNotIn("require = 0", annotation)',
)
MIXIN_HAND_POLICY_LINE = (
    b'    "main:com/quickskin/mod/mixin/ItemInHandRendererMixin.java",\n'
)
MIXIN_CAPE_POLICY_LINE = b'    "main:com/quickskin/mod/mixin/CapeLayerMixin.java",\n'
MIXIN_HAND_OVERRIDE_MARKER = (
    b"    (\n"
    b'        "main:com/quickskin/mod/mixin/ItemInHandRendererMixin.java",\n'
    b'        "quickskin$redirectRenderHandBuffer",\n'
    b"    ): {1, 2},"
)
MIXIN_LEGACY_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. The ItemInHand source contains Stonecutter branches:\n"
    b"# pre-1.21.11 renderHand requests two buffers (arm + sleeve), while the new renderer submits one\n"
    b"# model part."
)
MIXIN_1_21_2_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. The ItemInHand source contains Stonecutter branches:\n"
    b"# renderHand requests two buffers through 1.21.1, one buffer from 1.21.2 through 1.21.10, and the\n"
    b"# later renderer submits one model part."
)
MIXIN_1_21_4_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. The ItemInHand source contains Stonecutter branches:\n"
    b"# renderHand requests two buffers through 1.21.3, one buffer from 1.21.4 through 1.21.10, and the\n"
    b"# later renderer submits one model part."
)
MIXIN_HISTORICAL_HAND_COMMENTS = (
    MIXIN_LEGACY_HAND_COMMENT,
    MIXIN_1_21_2_HAND_COMMENT,
    MIXIN_1_21_4_HAND_COMMENT,
)
MIXIN_CANONICAL_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. Before 1.21.2 renderHand requests two buffers (arm and\n"
    b"# sleeve); 1.21.2 through 1.21.10 make one immediate arm draw. The modern collector is deliberately\n"
    b"# not intercepted."
)
MIXIN_LEGACY_OPTIONAL_HAND_BLOCK = (
    b"                if (\n"
    b'                    handler_name == "quickskin$redirectRenderHandBuffer"\n'
    b'                    and "quickskin$redirectSubmitModelPart" not in text\n'
    b"                ):\n"
    b"                    expected_counts = expected_counts - {1}\n"
    b"\n"
)
MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK = (
    b"                if (\n"
    b'                    source_name == "neoforge:com/quickskin/mod/neoforge/'
    b'mixin/PlayerRendererMixin.java"\n'
    b'                    and handler_name == "quickskin$redirectRenderHandBuffer"\n'
    b'                    and "quickskin$redirectSubmitModelPart" not in text\n'
    b"                ):\n"
    b"                    expected_counts = expected_counts - {1}\n"
    b"\n"
)
NEOFORGE_PLAYER_RENDERER_PATH = (
    "neoforge/src/main/java/com/quickskin/mod/neoforge/mixin/PlayerRendererMixin.java"
)
NEOFORGE_PLAYER_RENDERER_INPUT_PATH = (
    "scripts/ci/version_port_migrations/"
    "neoforge-player-renderer-with-modern-collector.java.fixture"
)
NEOFORGE_PLAYER_RENDERER_RESULT_PATH = (
    "scripts/ci/version_port_migrations/"
    "neoforge-player-renderer-without-modern-collector.java.fixture"
)
NEOFORGE_PLAYER_RENDERER_BEFORE_SHA256 = (
    "cf93f0042e3bc277ab077ff31d30f363d207a8be588931efcf1f8936c6eb724b"
)
NEOFORGE_PLAYER_RENDERER_RESULT_SHA256 = (
    "5273c8bad6c77a5bcd0729fb04e5c3e2250c8490d4618507a04a84855b358ab7"
)
MODERN_HAND_COLLECTOR_MARKERS = (
    b"quickskin$redirectSubmitModelPart",
    b"SubmitNodeCollector;submitModelPart",
)
CPM_TRANSITION_POLICY_MARKERS = (
    b"def test_modern_first_person_collectors_remain_owned_by_model_mods",
    b'self.assertNotIn("quickskin$redirectSubmitModelPart", source)',
    b'self.assertNotIn("SubmitNodeCollector;submitModelPart", source)',
)


class VersionPortMergeError(ValueError):
    """Raised when a version-port merge cannot be reproduced safely."""


@dataclass(frozen=True, order=True)
class IndexEntry:
    path: str
    stage: int
    mode: str
    oid: str

    def object_payload(self) -> dict[str, str]:
        return {"mode": self.mode, "oid": self.oid}


@dataclass(frozen=True)
class IndexSnapshot:
    entries: tuple[IndexEntry, ...]
    sha256: str

    def payload(self) -> dict[str, Any]:
        return {"entry_count": len(self.entries), "sha256": self.sha256}


@dataclass(frozen=True)
class GitResult:
    returncode: int
    stdout: bytes
    stderr: bytes


def _literal_pathspec(path: str) -> str:
    return f":(top,literal){path}"


def _clean_git_environment(extra: Mapping[str, str] | None = None) -> dict[str, str]:
    environment = os.environ.copy()
    for name in tuple(environment):
        if name in {
            "GIT_DIR",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        } or name.startswith("GIT_CONFIG_"):
            environment.pop(name, None)
    environment.update(
        {
            "GIT_CONFIG_GLOBAL": os.devnull,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_SYSTEM": os.devnull,
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_PAGER": "cat",
            "LC_ALL": "C",
        }
    )
    if extra:
        environment.update(extra)
    return environment


def _run_git(
    repository: Path,
    *arguments: str,
    accepted: Iterable[int] = (0,),
    environment: Mapping[str, str] | None = None,
    stdout_limit: int = MAX_GIT_STDOUT_BYTES,
) -> GitResult:
    command = ("git", "-C", str(repository), *arguments)
    stdout_spool_limit = max(1, min(stdout_limit + 1, 1024 * 1024))
    stderr_spool_limit = min(MAX_GIT_STDERR_BYTES + 1, 1024 * 1024)
    try:
        with tempfile.SpooledTemporaryFile(
            max_size=stdout_spool_limit, mode="w+b"
        ) as stdout_file, tempfile.SpooledTemporaryFile(
            max_size=stderr_spool_limit, mode="w+b"
        ) as stderr_file:
            completed = subprocess.run(
                command,
                stdin=subprocess.DEVNULL,
                stdout=stdout_file,
                stderr=stderr_file,
                check=False,
                shell=False,
                env=_clean_git_environment(environment),
            )
            stdout_size = stdout_file.seek(0, os.SEEK_END)
            stderr_size = stderr_file.seek(0, os.SEEK_END)
            if stdout_size > stdout_limit:
                raise VersionPortMergeError(
                    f"Git output exceeds the {stdout_limit}-byte limit"
                )
            if stderr_size > MAX_GIT_STDERR_BYTES:
                raise VersionPortMergeError(
                    "Git error output exceeds the "
                    f"{MAX_GIT_STDERR_BYTES}-byte limit"
                )
            stdout_file.seek(0)
            stderr_file.seek(0)
            stdout = stdout_file.read(stdout_size)
            stderr = stderr_file.read(stderr_size)
    except OSError as exc:
        raise VersionPortMergeError(f"cannot execute Git: {exc}") from exc
    if len(stdout) > stdout_limit:
        raise VersionPortMergeError(
            f"Git output exceeds the {stdout_limit}-byte limit"
        )
    if len(stderr) > MAX_GIT_STDERR_BYTES:
        raise VersionPortMergeError(
            f"Git error output exceeds the {MAX_GIT_STDERR_BYTES}-byte limit"
        )
    accepted_codes = frozenset(accepted)
    if completed.returncode not in accepted_codes:
        detail = stderr.decode("utf-8", errors="replace").strip()
        rendered = " ".join(arguments)
        raise VersionPortMergeError(detail or f"git {rendered} failed")
    return GitResult(completed.returncode, stdout, stderr)


def _decode_ascii_line(payload: bytes, label: str) -> str:
    try:
        value = payload.decode("ascii").strip()
    except UnicodeDecodeError as exc:
        raise VersionPortMergeError(f"{label} is not ASCII") from exc
    if not value or "\n" in value or "\r" in value:
        raise VersionPortMergeError(f"{label} is malformed")
    return value


def _object_format(repository: Path) -> tuple[str, int]:
    value = _decode_ascii_line(
        _run_git(repository, "rev-parse", "--show-object-format").stdout,
        "Git object format",
    )
    lengths = {"sha1": 40, "sha256": 64}
    length = lengths.get(value)
    if length is None:
        raise VersionPortMergeError(f"unsupported Git object format {value!r}")
    return value, length


def _validate_oid(oid: str, oid_length: int, label: str) -> str:
    if not re.fullmatch(rf"[0-9a-f]{{{oid_length}}}", oid):
        raise VersionPortMergeError(f"{label} is not an exact object id")
    return oid


def _resolve_commit(
    repository: Path, value: str, oid_length: int, label: str
) -> str:
    _validate_oid(value, oid_length, label)
    result = _run_git(repository, "rev-parse", "--verify", f"{value}^{{commit}}")
    resolved = _decode_ascii_line(result.stdout, label)
    _validate_oid(resolved, oid_length, label)
    if resolved != value:
        raise VersionPortMergeError(f"{label} did not resolve to itself")
    return resolved


def _normalize_git_path(raw: bytes) -> str:
    try:
        decoded = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VersionPortMergeError("Git index contains a non-UTF-8 path") from exc
    try:
        return normalize_path(decoded)
    except PolicyError as exc:
        raise VersionPortMergeError(str(exc)) from exc


def _parse_index(payload: bytes, oid_length: int) -> IndexSnapshot:
    records = payload.split(b"\0")
    if records and records[-1] == b"":
        records.pop()
    if len(records) > MAX_INDEX_ENTRIES:
        raise VersionPortMergeError(
            f"Git index contains more than {MAX_INDEX_ENTRIES} entries"
        )

    entries: list[IndexEntry] = []
    identities: set[tuple[str, int]] = set()
    portable: dict[str, str] = {}
    for record in records:
        try:
            header, raw_path = record.split(b"\t", 1)
            raw_mode, raw_oid, raw_stage = header.split(b" ")
            mode = raw_mode.decode("ascii")
            oid = raw_oid.decode("ascii")
            stage_text = raw_stage.decode("ascii")
        except (UnicodeDecodeError, ValueError) as exc:
            raise VersionPortMergeError("malformed Git index entry") from exc
        if not re.fullmatch(r"[0-7]{6}", mode):
            raise VersionPortMergeError(f"invalid Git index mode {mode!r}")
        _validate_oid(oid, oid_length, "Git index blob")
        if stage_text not in {"0", "1", "2", "3"}:
            raise VersionPortMergeError(f"invalid Git index stage {stage_text!r}")
        path = _normalize_git_path(raw_path)
        collision_key = path.casefold()
        previous = portable.setdefault(collision_key, path)
        if previous != path:
            raise VersionPortMergeError(
                f"Git index contains case-colliding paths {previous!r}, {path!r}"
            )
        identity = (path, int(stage_text))
        if identity in identities:
            raise VersionPortMergeError(
                f"Git index repeats stage {stage_text} for {path!r}"
            )
        identities.add(identity)
        entries.append(IndexEntry(path, int(stage_text), mode, oid))

    return IndexSnapshot(
        entries=tuple(entries),
        sha256=hashlib.sha256(payload).hexdigest(),
    )


def _snapshot_index(
    repository: Path,
    oid_length: int,
    *,
    index_file: Path | None = None,
) -> IndexSnapshot:
    environment = None
    if index_file is not None:
        environment = {"GIT_INDEX_FILE": str(index_file)}
    payload = _run_git(
        repository,
        "ls-files",
        "--stage",
        "-z",
        environment=environment,
        stdout_limit=MAX_INDEX_BYTES,
    ).stdout
    return _parse_index(payload, oid_length)


def _entries_by_path(entries: Iterable[IndexEntry]) -> dict[str, tuple[IndexEntry, ...]]:
    grouped: dict[str, list[IndexEntry]] = {}
    for entry in entries:
        grouped.setdefault(entry.path, []).append(entry)
    return {
        path: tuple(sorted(path_entries, key=lambda entry: entry.stage))
        for path, path_entries in grouped.items()
    }


def _unmerged_paths(snapshot: IndexSnapshot) -> tuple[str, ...]:
    grouped = _entries_by_path(snapshot.entries)
    result: list[str] = []
    for path, entries in grouped.items():
        stages = {entry.stage for entry in entries}
        if stages == {0}:
            continue
        if 0 in stages or not stages.issubset({1, 2, 3}):
            raise VersionPortMergeError(f"malformed unmerged index state for {path!r}")
        result.append(path)
    return tuple(sorted(result))


def _stage_map(
    grouped: Mapping[str, tuple[IndexEntry, ...]], path: str
) -> dict[int, IndexEntry]:
    entries = grouped.get(path)
    if not entries:
        raise VersionPortMergeError(f"missing conflict index entries for {path!r}")
    result = {entry.stage: entry for entry in entries}
    if 0 in result:
        raise VersionPortMergeError(f"expected unmerged index entries for {path!r}")
    return result


def _tree_entry(
    repository: Path,
    commit: str,
    path: str,
    oid_length: int,
) -> IndexEntry | None:
    payload = _run_git(
        repository,
        "ls-tree",
        "-z",
        "--full-tree",
        commit,
        "--",
        _literal_pathspec(path),
        stdout_limit=4096,
    ).stdout
    if not payload:
        return None
    records = payload.split(b"\0")
    if records[-1] == b"":
        records.pop()
    if len(records) != 1:
        raise VersionPortMergeError(f"tree lookup for {path!r} was not exact")
    try:
        header, raw_path = records[0].split(b"\t", 1)
        raw_mode, raw_type, raw_oid = header.split(b" ")
        mode = raw_mode.decode("ascii")
        object_type = raw_type.decode("ascii")
        oid = raw_oid.decode("ascii")
    except (UnicodeDecodeError, ValueError) as exc:
        raise VersionPortMergeError(f"malformed tree entry for {path!r}") from exc
    if _normalize_git_path(raw_path) != path:
        raise VersionPortMergeError(f"tree lookup returned the wrong path for {path!r}")
    if object_type != "blob" or mode not in REGULAR_MODES:
        raise VersionPortMergeError(f"{path!r} is not a regular file in {commit}")
    _validate_oid(oid, oid_length, f"tree blob for {path!r}")
    return IndexEntry(path, 0, mode, oid)


def _read_blob(
    repository: Path,
    oid: str,
    *,
    limit: int,
    label: str,
) -> bytes:
    size_text = _decode_ascii_line(
        _run_git(repository, "cat-file", "-s", oid, stdout_limit=128).stdout,
        f"{label} size",
    )
    if not size_text.isdecimal():
        raise VersionPortMergeError(f"{label} has an invalid size")
    size = int(size_text)
    if size > limit:
        raise VersionPortMergeError(f"{label} exceeds the {limit}-byte limit")
    result = _run_git(
        repository,
        "cat-file",
        "blob",
        oid,
        stdout_limit=limit,
    ).stdout
    if len(result) != size:
        raise VersionPortMergeError(f"{label} size changed while reading")
    return result


def _validate_text_blob(payload: bytes, label: str, *, markers: bool) -> None:
    if b"\0" in payload:
        raise VersionPortMergeError(f"{label} must be text")
    try:
        payload.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VersionPortMergeError(f"{label} must be UTF-8") from exc
    if markers:
        for line in payload.splitlines():
            if line.startswith(CONFLICT_MARKERS):
                raise VersionPortMergeError(f"{label} contains conflict markers")


def _hash_blob_file(repository: Path, path: Path, oid_length: int) -> str:
    oid = _decode_ascii_line(
        _run_git(repository, "hash-object", "-w", "--", str(path)).stdout,
        "merged blob id",
    )
    return _validate_oid(oid, oid_length, "merged blob id")


def _install_index_entry(repository: Path, entry: IndexEntry) -> None:
    if entry.stage != 0 or entry.mode not in REGULAR_MODES:
        raise VersionPortMergeError(f"refusing unsafe stage-0 entry for {entry.path!r}")
    _run_git(
        repository,
        "update-index",
        "--add",
        "--cacheinfo",
        entry.mode,
        entry.oid,
        entry.path,
    )
    _run_git(
        repository,
        "checkout",
        "--force",
        "--",
        _literal_pathspec(entry.path),
    )


def _stages_payload(stages: Mapping[int, IndexEntry]) -> dict[str, Any]:
    def stage_payload(stage: int) -> dict[str, str] | None:
        entry = stages.get(stage)
        return entry.object_payload() if entry is not None else None

    return {
        "base": stage_payload(1),
        "target": stage_payload(2),
        "source": stage_payload(3),
    }


def _policy_set_span(payload: bytes, name: str) -> tuple[int, int]:
    marker = f"{name} = {{\n".encode("ascii")
    if payload.count(marker) != 1:
        raise VersionPortMergeError(f"mixin policy has an invalid {name} declaration")
    start = payload.index(marker) + len(marker)
    end = payload.find(b"\n}\n", start)
    if end < 0:
        raise VersionPortMergeError(f"mixin policy has an unterminated {name} declaration")
    return start, end + 1


def _migrate_mixin_hand_policy_payload(payload: bytes) -> bytes:
    _validate_text_blob(payload, "target mixin policy", markers=True)
    if len(payload) > MAX_PROTECTED_BLOB_BYTES:
        raise VersionPortMergeError("target mixin policy is too large")
    critical_start, critical_end = _policy_set_span(payload, "CRITICAL_MIXINS")
    degradable_start, degradable_end = _policy_set_span(
        payload, "DEGRADABLE_MIXINS"
    )
    critical_occurrences = payload[critical_start:critical_end].count(
        MIXIN_HAND_POLICY_LINE
    )
    degradable_occurrences = payload[degradable_start:degradable_end].count(
        MIXIN_HAND_POLICY_LINE
    )
    if critical_occurrences + degradable_occurrences != 1:
        raise VersionPortMergeError(
            "target mixin policy has an ambiguous common hand classification"
        )
    in_degradable = degradable_occurrences == 1
    if in_degradable:
        critical = payload[critical_start:critical_end]
        if critical.count(MIXIN_CAPE_POLICY_LINE) != 1:
            raise VersionPortMergeError(
                "target mixin policy lacks the audited critical cape insertion point"
            )
        hand_position = payload.index(
            MIXIN_HAND_POLICY_LINE, degradable_start, degradable_end
        )
        payload = (
            payload[:hand_position]
            + payload[hand_position + len(MIXIN_HAND_POLICY_LINE) :]
        )
        critical_start, critical_end = _policy_set_span(payload, "CRITICAL_MIXINS")
        cape_position = payload.index(
            MIXIN_CAPE_POLICY_LINE, critical_start, critical_end
        )
        insertion = cape_position + len(MIXIN_CAPE_POLICY_LINE)
        payload = payload[:insertion] + MIXIN_HAND_POLICY_LINE + payload[insertion:]

    historical_comments = tuple(
        comment
        for comment in MIXIN_HISTORICAL_HAND_COMMENTS
        if comment in payload
    )
    if len(historical_comments) > 1:
        raise VersionPortMergeError(
            "target mixin policy contains ambiguous historical hand comments"
        )
    if historical_comments:
        historical_comment = historical_comments[0]
        if payload.count(historical_comment) != 1:
            raise VersionPortMergeError(
                "target mixin policy repeats a historical hand comment"
            )
        payload = payload.replace(
            historical_comment,
            MIXIN_CANONICAL_HAND_COMMENT,
            1,
        )
    if payload.count(MIXIN_CANONICAL_HAND_COMMENT) != 1:
        raise VersionPortMergeError(
            "target mixin policy lacks the audited hand multiplicity comment"
        )

    optional_blocks = payload.count(MIXIN_LEGACY_OPTIONAL_HAND_BLOCK)
    scoped_blocks = payload.count(MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK)
    if (
        optional_blocks > 1
        or scoped_blocks > 1
        or (optional_blocks and scoped_blocks)
    ):
        raise VersionPortMergeError(
            "target mixin policy repeats the obsolete optional-hand rule"
        )
    if optional_blocks == 1:
        payload = payload.replace(
            MIXIN_LEGACY_OPTIONAL_HAND_BLOCK,
            MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK,
            1,
        )

    if payload.count(MIXIN_HAND_OVERRIDE_MARKER) != 1:
        raise VersionPortMergeError(
            "target mixin policy lacks the audited hand multiplicity override"
        )
    critical_start, critical_end = _policy_set_span(payload, "CRITICAL_MIXINS")
    degradable_start, degradable_end = _policy_set_span(
        payload, "DEGRADABLE_MIXINS"
    )
    if (
        MIXIN_HAND_POLICY_LINE not in payload[critical_start:critical_end]
        or MIXIN_HAND_POLICY_LINE in payload[degradable_start:degradable_end]
        or MIXIN_LEGACY_OPTIONAL_HAND_BLOCK in payload
        or payload.count(b"expected_counts = expected_counts - {1}")
        != payload.count(MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK)
    ):
        raise VersionPortMergeError(
            "target mixin policy did not reach the mandatory hand policy"
        )
    return payload


def _index_entry_from_payload(
    repository: Path,
    path: str,
    mode: str,
    payload: bytes,
    temporary: Path,
    label: str,
    oid_length: int,
) -> IndexEntry:
    if mode not in REGULAR_MODES:
        raise VersionPortMergeError(f"{label} mode is not regular")
    _validate_text_blob(payload, label, markers=True)
    if len(payload) > MAX_PROTECTED_BLOB_BYTES:
        raise VersionPortMergeError(f"{label} is too large")
    payload_file = temporary / "payload"
    payload_file.write_bytes(payload)
    payload_file.chmod(0o600)
    return IndexEntry(
        path,
        0,
        mode,
        _hash_blob_file(repository, payload_file, oid_length),
    )


def _resolve_source_path(
    repository: Path,
    path: str,
    stages: Mapping[int, IndexEntry],
    oid_length: int,
    merge_files: Path,
) -> tuple[IndexEntry, dict[str, Any]]:
    if set(stages) != {1, 2, 3}:
        raise VersionPortMergeError(
            f"source-preferred path {path!r} requires base, target, and source blobs"
        )
    for label, stage in (("base", 1), ("target", 2), ("source", 3)):
        if stages[stage].mode not in REGULAR_MODES:
            raise VersionPortMergeError(
                f"{label} mode for source-preferred path {path!r} is not regular"
            )

    inputs: dict[int, bytes] = {}
    for stage, label in ((1, "base"), (2, "target"), (3, "source")):
        payload = _read_blob(
            repository,
            stages[stage].oid,
            limit=MAX_PROTECTED_BLOB_BYTES,
            label=f"{label} blob for {path}",
        )
        _validate_text_blob(payload, f"{label} blob for {path}", markers=False)
        inputs[stage] = payload

    if (
        path == MIXIN_POLICY_PATH
        and hashlib.sha256(inputs[3]).hexdigest() == MIXIN_POLICY_SHA256
    ):
        result = _index_entry_from_payload(
            repository,
            path,
            stages[2].mode,
            _migrate_mixin_hand_policy_payload(inputs[2]),
            merge_files,
            "migrated target mixin policy",
            oid_length,
        )
        _install_index_entry(repository, result)
        return result, {
            "path": path,
            "policy": "migrate-mandatory-common-hand-policy",
            "stages": _stages_payload(stages),
            "result": result.object_payload(),
        }

    if (
        path == CPM_TRANSITION_POLICY_PATH
        and hashlib.sha256(inputs[3]).hexdigest() == CPM_TRANSITION_POLICY_SHA256
    ):
        result = IndexEntry(path, 0, stages[3].mode, stages[3].oid)
        _install_index_entry(repository, result)
        return result, {
            "path": path,
            "policy": "install-canonical-cpm-transition-policy",
            "stages": _stages_payload(stages),
            "result": result.object_payload(),
        }

    if (
        path == COMMON_HAND_RENDERER_PATH
        and hashlib.sha256(inputs[3]).hexdigest() == COMMON_HAND_RENDERER_SHA256
    ):
        result = IndexEntry(path, 0, stages[3].mode, stages[3].oid)
        _install_index_entry(repository, result)
        return result, {
            "path": path,
            "policy": "install-canonical-common-hand-renderer",
            "stages": _stages_payload(stages),
            "result": result.object_payload(),
        }

    base_file = merge_files / "base"
    target_file = merge_files / "target"
    source_file = merge_files / "source"
    base_file.write_bytes(inputs[1])
    target_file.write_bytes(inputs[2])
    source_file.write_bytes(inputs[3])
    _run_git(
        repository,
        "merge-file",
        "--theirs",
        str(target_file),
        str(base_file),
        str(source_file),
        stdout_limit=1024,
    )
    merged = target_file.read_bytes()
    if len(merged) > MAX_PROTECTED_BLOB_BYTES:
        raise VersionPortMergeError(
            f"merged source-preferred blob for {path!r} is too large"
        )
    _validate_text_blob(merged, f"merged blob for {path}", markers=True)
    merged_oid = _hash_blob_file(repository, target_file, oid_length)
    result = IndexEntry(path, 0, stages[3].mode, merged_oid)
    _install_index_entry(repository, result)
    return result, {
        "path": path,
        "policy": "source-preferred-three-way",
        "stages": _stages_payload(stages),
        "result": result.object_payload(),
    }


def _resolve_target_path(
    repository: Path,
    work_head: str,
    path: str,
    stages: Mapping[int, IndexEntry],
    oid_length: int,
) -> tuple[IndexEntry, dict[str, Any]]:
    result = _tree_entry(repository, work_head, path, oid_length)
    if result is None:
        raise VersionPortMergeError(
            f"target-retained path {path!r} is absent from the target commit"
        )
    stage_target = stages.get(2)
    if stage_target is None or stage_target.object_payload() != result.object_payload():
        raise VersionPortMergeError(
            f"target stage for {path!r} does not match the target commit"
        )
    _install_index_entry(repository, result)
    return result, {
        "path": path,
        "policy": "retain-target",
        "stages": _stages_payload(stages),
        "result": result.object_payload(),
    }


def _canonical_hand_policy_entries(
    repository: Path,
    source: str,
    oid_length: int,
) -> tuple[IndexEntry, IndexEntry, IndexEntry] | None:
    policy_entry = _tree_entry(
        repository, source, CPM_TRANSITION_POLICY_PATH, oid_length
    )
    if policy_entry is None:
        return None
    policy_payload = _read_blob(
        repository,
        policy_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source CPM transition policy",
    )
    policy_matches = tuple(
        marker in policy_payload
        for marker in COMMON_HAND_MULTIPLICITY_POLICY_MARKERS
    )
    if not any(policy_matches):
        return None
    if not all(policy_matches):
        raise VersionPortMergeError(
            "source CPM transition policy has incomplete hand multiplicity markers"
        )
    _validate_text_blob(policy_payload, "source CPM transition policy", markers=True)
    if hashlib.sha256(policy_payload).hexdigest() != CPM_TRANSITION_POLICY_SHA256:
        raise VersionPortMergeError(
            "source CPM transition policy is not the audited multiplicity policy"
        )

    mixin_policy_entry = _tree_entry(
        repository, source, MIXIN_POLICY_PATH, oid_length
    )
    if mixin_policy_entry is None:
        raise VersionPortMergeError(
            "source hand multiplicity policy lacks the mixin policy"
        )
    mixin_policy_payload = _read_blob(
        repository,
        mixin_policy_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source mixin policy",
    )
    _validate_text_blob(mixin_policy_payload, "source mixin policy", markers=True)
    if hashlib.sha256(mixin_policy_payload).hexdigest() != MIXIN_POLICY_SHA256:
        raise VersionPortMergeError(
            "source mixin policy is not the audited mandatory-hand policy"
        )
    if _migrate_mixin_hand_policy_payload(mixin_policy_payload) != mixin_policy_payload:
        raise VersionPortMergeError(
            "source mixin policy is not already in canonical hand-policy form"
        )

    common_entry = _tree_entry(
        repository, source, COMMON_HAND_RENDERER_PATH, oid_length
    )
    if common_entry is None:
        raise VersionPortMergeError(
            "source hand multiplicity policy lacks the common hand renderer"
        )
    common_payload = _read_blob(
        repository,
        common_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source common hand renderer",
    )
    _validate_text_blob(common_payload, "source common hand renderer", markers=True)
    if hashlib.sha256(common_payload).hexdigest() != COMMON_HAND_RENDERER_SHA256:
        raise VersionPortMergeError(
            "source common hand renderer is not the audited multiplicity implementation"
        )
    if any(marker in common_payload for marker in MODERN_HAND_COLLECTOR_MARKERS):
        raise VersionPortMergeError(
            "source common hand renderer intercepts the modern hand collector"
        )
    return common_entry, mixin_policy_entry, policy_entry


def _install_canonical_hand_policy(
    repository: Path,
    source: str,
    oid_length: int,
    temporary: Path,
) -> list[dict[str, Any]]:
    source_entries = _canonical_hand_policy_entries(
        repository, source, oid_length
    )
    if source_entries is None:
        return []
    source_entry, _, _ = source_entries

    indexed = _entries_by_path(_snapshot_index(repository, oid_length).entries)
    current_renderer = indexed.get(COMMON_HAND_RENDERER_PATH, ())
    if len(current_renderer) != 1 or current_renderer[0].stage != 0:
        raise VersionPortMergeError(
            "common hand renderer is not resolved before canonical installation"
        )
    resolutions: list[dict[str, Any]] = []
    current_entry = current_renderer[0]
    if current_entry.object_payload() != source_entry.object_payload():
        _install_index_entry(repository, source_entry)
        resolutions.append(
            {
                "path": COMMON_HAND_RENDERER_PATH,
                "policy": "install-canonical-common-hand-renderer",
                "source": source_entry.object_payload(),
                "target": current_entry.object_payload(),
                "result": source_entry.object_payload(),
            }
        )

    current_mixin_policy = indexed.get(MIXIN_POLICY_PATH, ())
    if len(current_mixin_policy) != 1 or current_mixin_policy[0].stage != 0:
        raise VersionPortMergeError(
            "mixin policy is not resolved before mandatory-hand migration"
        )
    current_mixin_entry = current_mixin_policy[0]
    current_mixin_payload = _read_blob(
        repository,
        current_mixin_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="merged target mixin policy",
    )
    migrated_mixin_payload = _migrate_mixin_hand_policy_payload(
        current_mixin_payload
    )
    if migrated_mixin_payload != current_mixin_payload:
        migration_directory = temporary / "mixin-policy-migration"
        migration_directory.mkdir(mode=0o700)
        migrated_mixin_entry = _index_entry_from_payload(
            repository,
            MIXIN_POLICY_PATH,
            current_mixin_entry.mode,
            migrated_mixin_payload,
            migration_directory,
            "migrated target mixin policy",
            oid_length,
        )
        _install_index_entry(repository, migrated_mixin_entry)
        resolutions.append(
            {
                "path": MIXIN_POLICY_PATH,
                "policy": "migrate-mandatory-common-hand-policy",
                "source": source_entries[1].object_payload(),
                "target": current_mixin_entry.object_payload(),
                "result": migrated_mixin_entry.object_payload(),
            }
        )

    current_transition_policy = indexed.get(CPM_TRANSITION_POLICY_PATH, ())
    if (
        len(current_transition_policy) != 1
        or current_transition_policy[0].stage != 0
    ):
        raise VersionPortMergeError(
            "CPM transition policy is not resolved before hand-policy validation"
        )
    transition_payload = _read_blob(
        repository,
        current_transition_policy[0].oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="merged CPM transition policy",
    )
    _validate_text_blob(transition_payload, "merged CPM transition policy", markers=True)
    if not all(
        marker in transition_payload
        for marker in COMMON_HAND_MULTIPLICITY_POLICY_MARKERS
    ):
        raise VersionPortMergeError(
            "merged CPM transition policy lost hand multiplicity coverage"
        )
    return resolutions


def _resolve_delete_path(
    repository: Path,
    work_head: str,
    path: str,
    stages: Mapping[int, IndexEntry],
    oid_length: int,
    policy: str,
) -> dict[str, Any]:
    if _tree_entry(repository, work_head, path, oid_length) is not None:
        raise VersionPortMergeError(
            f"mechanical deletion {path!r} still exists in the target commit"
        )
    if 2 in stages:
        raise VersionPortMergeError(
            f"mechanical deletion {path!r} unexpectedly has a target stage"
        )
    _run_git(
        repository,
        "rm",
        "--force",
        "--",
        _literal_pathspec(path),
    )
    return {
        "path": path,
        "policy": policy,
        "stages": _stages_payload(stages),
        "result": None,
    }


def _clear_datapack_migration_conflict(
    repository: Path,
    path: str,
    stages: Mapping[int, IndexEntry],
) -> dict[str, Any]:
    if not stages or not set(stages).issubset({1, 2, 3}):
        raise VersionPortMergeError(
            f"datapack migration conflict {path!r} has invalid stages"
        )
    _run_git(
        repository,
        "rm",
        "--force",
        "--",
        _literal_pathspec(path),
    )
    return {
        "path": path,
        "policy": "clear-renamed-datapack-conflict",
        "stages": _stages_payload(stages),
        "result": None,
    }


def _uses_namespaced_game_rules(runtime_version: str) -> bool:
    try:
        components = tuple(int(value) for value in runtime_version.split("."))
    except ValueError as exc:
        raise VersionPortMergeError(
            f"target runtime version {runtime_version!r} is invalid"
        ) from exc
    if len(components) == 3 and components[:2] == (1, 21):
        return components[2] >= 11
    if len(components) in {2, 3} and components[0] >= 26:
        return True
    raise VersionPortMergeError(
        "renamed datapack function layout has unsupported target runtime "
        f"{runtime_version!r}"
    )


def _rewrite_datapack_load_game_rules(
    payload: bytes, runtime_version: str
) -> bytes:
    _validate_text_blob(payload, "source datapack load function", markers=True)
    if not payload.endswith(b"\n") or b"\r" in payload:
        raise VersionPortMergeError(
            "source datapack load function must use final LF line endings"
        )
    lines = payload.splitlines(keepends=True)
    for legacy in NAMESPACED_GAME_RULES:
        if lines.count(legacy) != 1:
            raise VersionPortMergeError(
                "source datapack load function does not contain the exact "
                f"expected command {legacy.decode('ascii').strip()!r}"
            )
    if not _uses_namespaced_game_rules(runtime_version):
        return payload
    return b"".join(NAMESPACED_GAME_RULES.get(line, line) for line in lines)


def _migrate_datapack_function_layout(
    repository: Path,
    work_head: str,
    source: str,
    profile: TargetMatrixProfile,
    oid_length: int,
    temporary: Path,
) -> list[dict[str, Any]]:
    """Move the trusted 1.20 function pack into the singular 1.21+ layout.

    Minecraft 1.21 renamed both ``functions`` directories to ``function``.
    Git consequently sees the protected load function as modify/delete while
    independently adding new plural tick files.  Reproduce that one known
    migration without exposing E2E policy files to the conflict-solving model.
    """

    source_entries: dict[str, IndexEntry] = {}
    target_entries: dict[str, IndexEntry | None] = {}
    target_old_entries: dict[str, IndexEntry | None] = {}
    for old_path, new_path in DATAPACK_FUNCTION_RENAMES:
        source_entry = _tree_entry(repository, source, old_path, oid_length)
        if source_entry is None:
            raise VersionPortMergeError(
                f"datapack migration source path {old_path!r} is absent"
            )
        target_old_entry = _tree_entry(
            repository, work_head, old_path, oid_length
        )
        if (
            old_path != DATAPACK_FUNCTION_RENAMES[2][0]
            and target_old_entry is not None
        ):
            raise VersionPortMergeError(
                f"datapack migration target still contains plural path {old_path!r}"
            )
        source_entries[old_path] = source_entry
        target_old_entries[old_path] = target_old_entry
        target_entries[new_path] = _tree_entry(
            repository, work_head, new_path, oid_length
        )

    load_target = target_entries[DATAPACK_FUNCTION_RENAMES[0][1]]
    load_tag_target = target_entries[DATAPACK_FUNCTION_RENAMES[2][1]]
    if load_target is None or load_tag_target is None:
        raise VersionPortMergeError(
            "datapack migration target lacks its singular load function or tag"
        )
    load_tag_source = source_entries[DATAPACK_FUNCTION_RENAMES[2][0]]
    source_load_tag_payload = _read_blob(
        repository,
        load_tag_source.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source datapack load tag",
    )
    target_load_tag_payload = _read_blob(
        repository,
        load_tag_target.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="target datapack load tag",
    )
    if source_load_tag_payload != target_load_tag_payload:
        raise VersionPortMergeError(
            "datapack migration load tags are not exact equivalents"
        )
    old_load_tag_target = target_old_entries[DATAPACK_FUNCTION_RENAMES[2][0]]
    if old_load_tag_target is not None:
        old_target_payload = _read_blob(
            repository,
            old_load_tag_target.oid,
            limit=MAX_PROTECTED_BLOB_BYTES,
            label="target plural datapack load tag",
        )
        if old_target_payload != source_load_tag_payload:
            raise VersionPortMergeError(
                "target plural datapack load tag is not the exact source equivalent"
            )

    before = _snapshot_index(repository, oid_length)
    before_by_path = _entries_by_path(before.entries)
    expected_old_stage_zero = {
        DATAPACK_FUNCTION_RENAMES[0][0],
        DATAPACK_FUNCTION_RENAMES[1][0],
        DATAPACK_FUNCTION_RENAMES[2][0],
        DATAPACK_FUNCTION_RENAMES[3][0],
    }
    plural_paths = {
        entry.path
        for entry in before.entries
        if entry.stage == 0
        and any(entry.path.startswith(prefix) for prefix in DATAPACK_PLURAL_PREFIXES)
    }
    if not plural_paths.issubset(expected_old_stage_zero):
        raise VersionPortMergeError(
            "datapack migration found unexpected plural function paths: "
            f"{sorted(plural_paths)!r}"
        )
    for old_path in plural_paths:
        entries = before_by_path.get(old_path, ())
        source_entry = source_entries[old_path]
        if entries != (source_entry,):
            raise VersionPortMergeError(
                f"merged datapack path {old_path!r} is not the exact source blob"
            )

    results: list[dict[str, Any]] = []
    for ordinal, (old_path, new_path) in enumerate(DATAPACK_FUNCTION_RENAMES):
        source_entry = source_entries[old_path]
        target_entry = target_entries[new_path]
        if old_path == DATAPACK_FUNCTION_RENAMES[0][0]:
            source_payload = _read_blob(
                repository,
                source_entry.oid,
                limit=MAX_PROTECTED_BLOB_BYTES,
                label="source datapack load function",
            )
            result_payload = _rewrite_datapack_load_game_rules(
                source_payload, profile.runtime_version
            )
            result_file = temporary / f"migrated-datapack-{ordinal}"
            result_file.write_bytes(result_payload)
            result_oid = _hash_blob_file(repository, result_file, oid_length)
            assert target_entry is not None
            result_entry = IndexEntry(new_path, 0, target_entry.mode, result_oid)
            _install_index_entry(repository, result_entry)
        elif old_path == DATAPACK_FUNCTION_RENAMES[2][0]:
            assert target_entry is not None
            result_entry = target_entry
        else:
            result_entry = IndexEntry(
                new_path, 0, source_entry.mode, source_entry.oid
            )
            _install_index_entry(repository, result_entry)

        if old_path in plural_paths:
            _run_git(
                repository,
                "rm",
                "--force",
                "--",
                _literal_pathspec(old_path),
            )
        results.append(
            {
                "path": new_path,
                "policy": "migrate-datapack-function-layout",
                "source_path": old_path,
                "source": source_entry.object_payload(),
                "target": (
                    target_entry.object_payload() if target_entry is not None else None
                ),
                "result": result_entry.object_payload(),
            }
        )

    after = _snapshot_index(repository, oid_length)
    remaining_plural = sorted(
        {
            entry.path
            for entry in after.entries
            if any(
                entry.path.startswith(prefix) for prefix in DATAPACK_PLURAL_PREFIXES
            )
        }
    )
    if remaining_plural:
        raise VersionPortMergeError(
            f"datapack migration left plural paths {remaining_plural!r}"
        )
    return results


def _needs_datapack_function_migration(
    repository: Path,
    work_head: str,
    source: str,
    oid_length: int,
) -> bool:
    old_load, new_load = DATAPACK_FUNCTION_RENAMES[0]
    return (
        _tree_entry(repository, source, old_load, oid_length) is not None
        and _tree_entry(repository, work_head, old_load, oid_length) is None
        and _tree_entry(repository, work_head, new_load, oid_length) is not None
    )


def _runtime_uses_vanilla_translucent_hand_collector(
    runtime_version: str,
) -> bool:
    try:
        components = tuple(int(value) for value in runtime_version.split("."))
    except ValueError as exc:
        raise VersionPortMergeError(
            f"target runtime version {runtime_version!r} is invalid"
        ) from exc
    return components[0] >= 26 or (
        len(components) == 3
        and components[:2] == (1, 21)
        and components[2] >= 11
    )


def _neoforge_collector_migration_fixture(
    repository: Path,
    source: str,
    oid_length: int,
) -> IndexEntry | None:
    """Authenticate the source policy and its audited NeoForge result fixture."""

    if _tree_entry(
        repository, source, NEOFORGE_PLAYER_RENDERER_PATH, oid_length
    ) is not None:
        return None
    policy_entry = _tree_entry(
        repository, source, CPM_TRANSITION_POLICY_PATH, oid_length
    )
    common_entry = _tree_entry(repository, source, COMMON_HAND_RENDERER_PATH, oid_length)
    if policy_entry is None or common_entry is None:
        return None

    policy_payload = _read_blob(
        repository,
        policy_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source CPM transition policy",
    )
    policy_matches = tuple(
        marker in policy_payload for marker in CPM_TRANSITION_POLICY_MARKERS
    )
    if not any(policy_matches):
        return None
    if not all(policy_matches):
        raise VersionPortMergeError(
            "source CPM transition policy has incomplete collector ownership markers"
        )

    common_payload = _read_blob(
        repository,
        common_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source common hand renderer",
    )
    if any(marker in common_payload for marker in MODERN_HAND_COLLECTOR_MARKERS):
        raise VersionPortMergeError(
            "source common hand renderer contradicts the CPM collector ownership policy"
        )

    input_entry = _tree_entry(
        repository, source, NEOFORGE_PLAYER_RENDERER_INPUT_PATH, oid_length
    )
    result_entry = _tree_entry(
        repository, source, NEOFORGE_PLAYER_RENDERER_RESULT_PATH, oid_length
    )
    if input_entry is None or result_entry is None:
        raise VersionPortMergeError(
            "source CPM collector policy lacks its NeoForge migration fixtures"
        )
    input_payload = _read_blob(
        repository,
        input_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source NeoForge collector migration input fixture",
    )
    _validate_text_blob(
        input_payload,
        "source NeoForge collector migration input fixture",
        markers=True,
    )
    if hashlib.sha256(input_payload).hexdigest() != (
        NEOFORGE_PLAYER_RENDERER_BEFORE_SHA256
    ):
        raise VersionPortMergeError(
            "source NeoForge collector migration input fixture is not audited"
        )
    if not all(marker in input_payload for marker in MODERN_HAND_COLLECTOR_MARKERS):
        raise VersionPortMergeError(
            "source NeoForge collector migration input lacks the modern redirect"
        )
    result_payload = _read_blob(
        repository,
        result_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source NeoForge collector migration fixture",
    )
    _validate_text_blob(
        result_payload,
        "source NeoForge collector migration fixture",
        markers=True,
    )
    if hashlib.sha256(result_payload).hexdigest() != (
        NEOFORGE_PLAYER_RENDERER_RESULT_SHA256
    ):
        raise VersionPortMergeError(
            "source NeoForge collector migration fixture is not the audited result"
        )
    if any(marker in result_payload for marker in MODERN_HAND_COLLECTOR_MARKERS):
        raise VersionPortMergeError(
            "source NeoForge collector migration fixture still intercepts the modern collector"
        )
    return result_entry


def _migrate_neoforge_modern_hand_collector(
    repository: Path,
    work_head: str,
    result_fixture: IndexEntry,
    oid_length: int,
) -> list[dict[str, Any]]:
    """Replace one exact target-only NeoForge redirect with its audited safe form."""

    target_entry = _tree_entry(
        repository, work_head, NEOFORGE_PLAYER_RENDERER_PATH, oid_length
    )
    if target_entry is None:
        return []
    target_payload = _read_blob(
        repository,
        target_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="target NeoForge player renderer",
    )
    _validate_text_blob(
        target_payload, "target NeoForge player renderer", markers=True
    )
    if not any(marker in target_payload for marker in MODERN_HAND_COLLECTOR_MARKERS):
        return []
    if hashlib.sha256(target_payload).hexdigest() != (
        NEOFORGE_PLAYER_RENDERER_BEFORE_SHA256
    ):
        raise VersionPortMergeError(
            "target NeoForge modern hand collector does not match the audited migration input"
        )

    before = _snapshot_index(repository, oid_length)
    current_entries = _entries_by_path(before.entries).get(
        NEOFORGE_PLAYER_RENDERER_PATH, ()
    )
    if current_entries != (target_entry,):
        raise VersionPortMergeError(
            "merged NeoForge player renderer is not the exact target blob"
        )

    result_entry = IndexEntry(
        NEOFORGE_PLAYER_RENDERER_PATH,
        0,
        target_entry.mode,
        result_fixture.oid,
    )
    _install_index_entry(repository, result_entry)
    return [
        {
            "path": NEOFORGE_PLAYER_RENDERER_PATH,
            "policy": "migrate-neoforge-modern-hand-collector",
            "source_path": NEOFORGE_PLAYER_RENDERER_RESULT_PATH,
            "source": result_fixture.object_payload(),
            "target": target_entry.object_payload(),
            "result": result_entry.object_payload(),
        }
    ]


def _read_regular_file(path: Path, *, limit: int, label: str) -> bytes:
    flags = os.O_RDONLY
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb", closefd=True) as handle:
            metadata = os.fstat(handle.fileno())
            if not stat.S_ISREG(metadata.st_mode):
                raise VersionPortMergeError(f"{label} must be a regular file")
            if metadata.st_size > limit:
                raise VersionPortMergeError(f"{label} exceeds the {limit}-byte limit")
            payload = handle.read(limit + 1)
    except VersionPortMergeError:
        raise
    except OSError as exc:
        raise VersionPortMergeError(f"cannot read {label}: {exc}") from exc
    if len(payload) > limit:
        raise VersionPortMergeError(f"{label} exceeds the {limit}-byte limit")
    return payload


def _candidate_entry(
    repository: Path,
    candidate_index: Path,
    path: str,
    oid_length: int,
) -> IndexEntry | None:
    payload = _run_git(
        repository,
        "ls-files",
        "--stage",
        "-z",
        "--",
        _literal_pathspec(path),
        environment={"GIT_INDEX_FILE": str(candidate_index)},
        stdout_limit=4096,
    ).stdout
    snapshot = _parse_index(payload, oid_length)
    if not snapshot.entries:
        return None
    if len(snapshot.entries) != 1:
        raise VersionPortMergeError(
            f"candidate index has unmerged or duplicate entries for {path!r}"
        )
    entry = snapshot.entries[0]
    if entry.path != path or entry.stage != 0 or entry.mode not in REGULAR_MODES:
        raise VersionPortMergeError(
            f"candidate index entry for {path!r} is not a safe regular stage-0 blob"
        )
    return entry


def _authenticate_candidate_tree(
    repository: Path,
    candidate_index: Path,
    expected_tree: str,
    oid_length: int,
) -> None:
    _validate_oid(expected_tree, oid_length, "candidate tree")
    snapshot = _snapshot_index(
        repository,
        oid_length,
        index_file=candidate_index,
    )
    unmerged = _unmerged_paths(snapshot)
    if unmerged:
        raise VersionPortMergeError(
            f"candidate index remains unmerged at {list(unmerged)!r}"
        )
    actual_tree = _decode_ascii_line(
        _run_git(
            repository,
            "write-tree",
            environment={"GIT_INDEX_FILE": str(candidate_index)},
            stdout_limit=128,
        ).stdout,
        "candidate tree",
    )
    _validate_oid(actual_tree, oid_length, "candidate tree")
    if actual_tree != expected_tree:
        raise VersionPortMergeError(
            "candidate index tree does not equal the authenticated candidate tree"
        )


def _inject_candidate_paths(
    repository: Path,
    candidate_index: Path,
    ai_paths: tuple[str, ...],
    mechanical: IndexSnapshot,
    oid_length: int,
) -> None:
    selected: dict[str, IndexEntry | None] = {}
    total_bytes = 0
    for path in ai_paths:
        entry = _candidate_entry(repository, candidate_index, path, oid_length)
        selected[path] = entry
        if entry is None:
            continue
        blob = _read_blob(
            repository,
            entry.oid,
            limit=MAX_AI_BLOB_BYTES,
            label=f"candidate blob for {path}",
        )
        total_bytes += len(blob)
        if total_bytes > MAX_AI_BLOBS_BYTES:
            raise VersionPortMergeError(
                f"candidate AI blobs exceed the {MAX_AI_BLOBS_BYTES}-byte limit"
            )
        _validate_text_blob(blob, f"candidate blob for {path}", markers=True)

    for path in ai_paths:
        entry = selected[path]
        if entry is None:
            _run_git(
                repository,
                "rm",
                "--force",
                "--",
                _literal_pathspec(path),
            )
            # A conflicted worktree file can remain untracked after its index
            # stages are removed.  Delete only that exact literal path.
            _run_git(
                repository,
                "clean",
                "--force",
                "--",
                _literal_pathspec(path),
            )
            if (repository / path).exists() or (repository / path).is_symlink():
                raise VersionPortMergeError(
                    f"candidate deletion did not remove exact path {path!r}"
                )
        else:
            _install_index_entry(repository, entry)

    prepared = _snapshot_index(repository, oid_length)
    mechanical_by_path = _entries_by_path(mechanical.entries)
    prepared_by_path = _entries_by_path(prepared.entries)
    ai_set = frozenset(ai_paths)
    all_paths = set(mechanical_by_path) | set(prepared_by_path)
    for path in all_paths:
        if path in ai_set:
            expected_entry = selected[path]
            expected = () if expected_entry is None else (expected_entry,)
            if prepared_by_path.get(path, ()) != expected:
                raise VersionPortMergeError(
                    f"candidate injection produced the wrong entry for {path!r}"
                )
        elif prepared_by_path.get(path, ()) != mechanical_by_path.get(path, ()):
            raise VersionPortMergeError(
                f"candidate injection changed unapproved path {path!r}"
            )
    if _unmerged_paths(prepared):
        raise VersionPortMergeError("candidate index did not resolve every AI conflict")


def _target_matrix(
    repository: Path,
    work_head: str,
    oid_length: int,
    temporary: Path,
) -> tuple[IndexEntry, TargetMatrixProfile]:
    entry = _tree_entry(repository, work_head, MATRIX_PATH, oid_length)
    if entry is None:
        raise VersionPortMergeError("target commit has no release matrix")
    payload = _read_blob(
        repository,
        entry.oid,
        limit=MAX_MATRIX_BYTES,
        label="target release matrix",
    )
    matrix_file = temporary / "target-release-matrix.json"
    matrix_file.write_bytes(payload)
    try:
        profile = read_target_matrix_profile(matrix_file)
    except ConflictClassificationError as exc:
        raise VersionPortMergeError(str(exc)) from exc
    return entry, profile


def _merge_bases(
    repository: Path, work_head: str, source: str, oid_length: int
) -> tuple[str, ...]:
    output = _run_git(repository, "merge-base", "--all", work_head, source).stdout
    try:
        values = output.decode("ascii").splitlines()
    except UnicodeDecodeError as exc:
        raise VersionPortMergeError("merge-base output is not ASCII") from exc
    if not values:
        raise VersionPortMergeError("target and source have no merge base")
    return tuple(
        sorted(_validate_oid(value, oid_length, "merge base") for value in values)
    )


def _is_ancestor(repository: Path, ancestor: str, descendant: str) -> bool:
    result = _run_git(
        repository,
        "merge-base",
        "--is-ancestor",
        ancestor,
        descendant,
        accepted=(0, 1),
        stdout_limit=0,
    )
    return result.returncode == 0


def _assert_initial_state(repository: Path, work_head: str, oid_length: int) -> None:
    root = Path(
        _decode_ascii_line(
            _run_git(repository, "rev-parse", "--show-toplevel").stdout,
            "repository root",
        )
    ).resolve()
    if root != repository:
        raise VersionPortMergeError("--repository must name the Git worktree root")
    head = _decode_ascii_line(_run_git(repository, "rev-parse", "HEAD").stdout, "HEAD")
    _validate_oid(head, oid_length, "HEAD")
    if head != work_head:
        raise VersionPortMergeError("HEAD does not equal the exact work-head commit")
    if _run_git(
        repository,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
    ).stdout:
        raise VersionPortMergeError("version-port checkout must start completely clean")
    merge_head = _run_git(
        repository,
        "rev-parse",
        "-q",
        "--verify",
        "MERGE_HEAD",
        accepted=(0, 1),
        stdout_limit=128,
    )
    if merge_head.returncode == 0:
        raise VersionPortMergeError("version-port checkout already has a merge in progress")


def _restore_clean(
    repository: Path, work_head: str, hooks_directory: Path, oid_length: int
) -> None:
    common = (
        "-c",
        f"core.hooksPath={hooks_directory}",
        "-c",
        f"user.name={BOT_NAME}",
        "-c",
        f"user.email={BOT_EMAIL}",
    )
    aborted = _run_git(
        repository,
        *common,
        "merge",
        "--abort",
        accepted=(0, 1, 128),
    )
    head = _decode_ascii_line(_run_git(repository, "rev-parse", "HEAD").stdout, "HEAD")
    dirty = _run_git(
        repository,
        "status",
        "--porcelain=v1",
        "-z",
        "--untracked-files=all",
    ).stdout
    if aborted.returncode != 0 or head != work_head or dirty:
        _run_git(repository, *common, "reset", "--hard", work_head)
    _assert_initial_state(repository, work_head, oid_length)


def reproduce_merge(
    repository: Path,
    work_head: str,
    source: str,
    *,
    mode: str,
    candidate_index: Path | None = None,
    candidate_tree: str | None = None,
) -> dict[str, Any]:
    """Run one authenticated version-port merge and return stable evidence."""

    if mode not in {"prepare", "probe"}:
        raise VersionPortMergeError("mode must be 'prepare' or 'probe'")
    repository = repository.resolve()
    _, oid_length = _object_format(repository)
    work_head = _resolve_commit(repository, work_head, oid_length, "work-head")
    source = _resolve_commit(repository, source, oid_length, "source")
    if source == work_head:
        raise VersionPortMergeError("source and work-head commits must differ")
    _assert_initial_state(repository, work_head, oid_length)
    if _is_ancestor(repository, source, work_head):
        raise VersionPortMergeError(
            "source is already an ancestor of work-head; no version port is needed"
        )

    candidate_payload: bytes | None = None
    if candidate_index is not None:
        if mode != "prepare":
            raise VersionPortMergeError("--candidate-index is allowed only in prepare mode")
        if candidate_tree is None:
            raise VersionPortMergeError(
                "--candidate-tree is required with --candidate-index"
            )
        _validate_oid(candidate_tree, oid_length, "candidate tree")
        candidate_original = candidate_index.absolute()
        try:
            candidate_metadata = candidate_original.lstat()
        except OSError as exc:
            raise VersionPortMergeError(f"cannot inspect candidate index: {exc}") from exc
        if not stat.S_ISREG(candidate_metadata.st_mode):
            raise VersionPortMergeError("candidate index must be a regular non-symlink file")
        candidate_payload = _read_regular_file(
            candidate_original,
            limit=MAX_INDEX_BYTES,
            label="candidate index",
        )
        candidate_path = candidate_original.resolve(strict=True)
        try:
            candidate_path.relative_to(repository)
        except ValueError:
            pass
        else:
            raise VersionPortMergeError("candidate index must be outside the worktree")
    elif candidate_tree is not None:
        raise VersionPortMergeError(
            "--candidate-tree requires --candidate-index"
        )

    merge_started = False
    completed_successfully = False
    with tempfile.TemporaryDirectory(prefix="version-port-merge-") as raw_temporary:
        temporary = Path(raw_temporary)
        hooks_directory = temporary / "empty-hooks"
        hooks_directory.mkdir(mode=0o700)
        matrix_entry, target_profile = _target_matrix(
            repository, work_head, oid_length, temporary
        )
        active_loaders = target_profile.active_loaders
        active_overlay_roots = target_profile.active_overlay_roots
        bases = _merge_bases(repository, work_head, source, oid_length)
        candidate_copy: Path | None = None
        if candidate_payload is not None:
            candidate_copy = temporary / "candidate.index"
            candidate_copy.write_bytes(candidate_payload)
            candidate_copy.chmod(0o600)
            assert candidate_tree is not None
            _authenticate_candidate_tree(
                repository,
                candidate_copy,
                candidate_tree,
                oid_length,
            )

        merge_arguments = (
            "-c",
            f"core.hooksPath={hooks_directory}",
            "-c",
            f"user.name={BOT_NAME}",
            "-c",
            f"user.email={BOT_EMAIL}",
            "-c",
            "commit.gpgSign=false",
            "-c",
            "merge.autoStash=false",
            "merge",
            "--no-ff",
            "--no-commit",
            "--no-edit",
            source,
        )
        try:
            merge_started = True
            merge_result = _run_git(
                repository,
                *merge_arguments,
                accepted=(0, 1),
                stdout_limit=1024 * 1024,
            )
            merge_head_output = _run_git(
                repository,
                "rev-parse",
                "-q",
                "--verify",
                "MERGE_HEAD",
                accepted=(0, 1),
                stdout_limit=128,
            )
            if merge_head_output.returncode != 0:
                raise VersionPortMergeError(
                    "Git merge did not leave an authenticated no-commit merge state"
                )
            merge_head = _decode_ascii_line(
                merge_head_output.stdout, "MERGE_HEAD"
            )
            _validate_oid(merge_head, oid_length, "MERGE_HEAD")
            if merge_head != source:
                raise VersionPortMergeError("MERGE_HEAD does not equal the source commit")
            original = _snapshot_index(repository, oid_length)
            conflicts = _unmerged_paths(original)
            if merge_result.returncode == 1 and not conflicts:
                raise VersionPortMergeError(
                    "Git merge failed without a reproducible unmerged index"
                )
            if merge_result.returncode == 0 and conflicts:
                raise VersionPortMergeError(
                    "Git merge reported success with unmerged index entries"
                )

            grouped = _entries_by_path(original.entries)
            protected_resolutions: list[dict[str, Any]] = []
            classification: ConflictClassification | None = None
            if conflicts:
                try:
                    classification = classify_conflicts(
                        conflicts,
                        active_loaders,
                        active_overlay_roots,
                    )
                except ConflictClassificationError as exc:
                    raise VersionPortMergeError(str(exc)) from exc

                source_root = temporary / "source-merges"
                source_root.mkdir(mode=0o700)
                for ordinal, path in enumerate(classification.source_paths):
                    path_directory = source_root / str(ordinal)
                    path_directory.mkdir(mode=0o700)
                    _, resolution = _resolve_source_path(
                        repository,
                        path,
                        _stage_map(grouped, path),
                        oid_length,
                        path_directory,
                    )
                    protected_resolutions.append(resolution)
                for path in classification.target_paths:
                    _, resolution = _resolve_target_path(
                        repository,
                        work_head,
                        path,
                        _stage_map(grouped, path),
                        oid_length,
                    )
                    protected_resolutions.append(resolution)
                for path in classification.delete_paths:
                    if path in DATAPACK_FUNCTION_MIGRATION_CONFLICTS:
                        protected_resolutions.append(
                            _clear_datapack_migration_conflict(
                                repository,
                                path,
                                _stage_map(grouped, path),
                            )
                        )
                        continue
                    policy = (
                        "delete-inactive-overlay"
                        if is_inactive_overlay_path(path, active_overlay_roots)
                        else "delete-inactive-loader"
                    )
                    protected_resolutions.append(
                        _resolve_delete_path(
                            repository,
                            work_head,
                            path,
                            _stage_map(grouped, path),
                            oid_length,
                            policy,
                        )
                    )

            protected_resolutions.extend(
                _install_canonical_hand_policy(
                    repository,
                    source,
                    oid_length,
                    temporary,
                )
            )

            if _needs_datapack_function_migration(
                repository, work_head, source, oid_length
            ):
                protected_resolutions.extend(
                    _migrate_datapack_function_layout(
                        repository,
                        work_head,
                        source,
                        target_profile,
                        oid_length,
                        temporary,
                    )
                )

            if (
                "neoforge" in active_loaders
                and _runtime_uses_vanilla_translucent_hand_collector(
                    target_profile.runtime_version
                )
            ):
                result_fixture = _neoforge_collector_migration_fixture(
                    repository, source, oid_length
                )
                if result_fixture is not None:
                    protected_resolutions.extend(
                        _migrate_neoforge_modern_hand_collector(
                            repository,
                            work_head,
                            result_fixture,
                            oid_length,
                        )
                    )

            mechanical = _snapshot_index(repository, oid_length)
            remaining = _unmerged_paths(mechanical)
            ai_paths = classification.ai_paths if classification is not None else ()
            if remaining != ai_paths:
                raise VersionPortMergeError(
                    "mechanical resolution did not leave exactly the approved AI paths"
                )
            evidence: dict[str, Any] = {
                "schema_version": SCHEMA_VERSION,
                "work_head_sha": work_head,
                "source_sha": source,
                "merge_bases": list(bases),
                "conflicted": bool(conflicts),
                "conflicts": list(conflicts),
                "ai_conflicts": list(ai_paths),
                "target_matrix": {
                    "mode": matrix_entry.mode,
                    "oid": matrix_entry.oid,
                    "active_loaders": sorted(active_loaders),
                },
                "protected_resolutions": sorted(
                    protected_resolutions, key=lambda item: item["path"]
                ),
                "mechanical_index": mechanical.payload(),
            }

            if candidate_copy is not None:
                if not ai_paths:
                    raise VersionPortMergeError(
                        "candidate index supplied for a merge with no AI conflicts"
                    )
                _inject_candidate_paths(
                    repository,
                    candidate_copy,
                    ai_paths,
                    mechanical,
                    oid_length,
                )

            if mode == "probe":
                _restore_clean(repository, work_head, hooks_directory, oid_length)
            completed_successfully = True
            return evidence
        finally:
            if merge_started and not completed_successfully:
                try:
                    _restore_clean(repository, work_head, hooks_directory, oid_length)
                except VersionPortMergeError as cleanup_error:
                    raise VersionPortMergeError(
                        f"version-port merge failed and cleanup also failed: {cleanup_error}"
                    ) from cleanup_error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path("."))
    parser.add_argument("--work-head", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--mode", choices=("prepare", "probe"), required=True)
    parser.add_argument("--candidate-index", type=Path)
    parser.add_argument("--candidate-tree")
    args = parser.parse_args(argv)
    try:
        evidence = reproduce_merge(
            args.repository,
            args.work_head,
            args.source,
            mode=args.mode,
            candidate_index=args.candidate_index,
            candidate_tree=args.candidate_tree,
        )
    except (OSError, VersionPortMergeError) as exc:
        print(f"version-port merge error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(evidence, separators=(",", ":"), ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
