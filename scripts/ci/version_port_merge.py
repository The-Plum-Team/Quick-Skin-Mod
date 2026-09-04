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
VERIFICATION_METADATA_PATH = "gradle/verification-metadata.xml"
VERIFICATION_COMPONENT_PATTERN = re.compile(
    rb'      <component group="(?P<group>[^"<>\r\n]+)" '
    rb'name="(?P<name>[^"<>\r\n]+)" '
    rb'version="(?P<version>[^"<>\r\n]+)">\n'
    rb'.*?^      </component>\n',
    re.DOTALL | re.MULTILINE,
)
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
    "24201865fc492cb32844d5d9e73c4422a5bb03d13dc4f036e18024468f5b257c"
)
DEPENDENCY_SECURITY_POLICY_PATH = (
    "scripts/release/tests/test_dependency_security.py"
)
DEPENDENCY_SECURITY_POLICY_SHA256 = (
    "3943a99277b6954cf69787c5e90a64959c07facf2fdee1d94051e8cf9852bef0"
)
DEPENDENCY_SECURITY_EXPECTED_ANCHOR = b"        expected = {\n"
DEPENDENCY_SECURITY_STONECUTTER_SETUP = (
    b'        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")\n'
    b"        stonecutter_declaration = re.search(\n"
    b'            r\'id\\("dev[.]kikugie[.]stonecutter"\\) version "([^\"]+)"\',\n'
    b"            settings,\n"
    b"        )\n"
    b"        self.assertIsNotNone(stonecutter_declaration)\n"
    b"        assert stonecutter_declaration is not None\n"
    b"        stonecutter_version = stonecutter_declaration.group(1)\n"
    b"\n"
)
DEPENDENCY_SECURITY_FIXED_STONECUTTER_LINE = (
    b'            ("dev.kikugie", "stonecutter", "0.9.7"),\n'
)
DEPENDENCY_SECURITY_DYNAMIC_STONECUTTER_LINE = (
    b'            ("dev.kikugie", "stonecutter", stonecutter_version),\n'
)
DEPENDENCY_SECURITY_ASSERTION_ANCHOR = (
    b"        self.assertEqual(expected - coordinates, set())\n"
)
DEPENDENCY_SECURITY_STONECUTTER_ASSERTION = (
    b"        self.assertEqual(\n"
    b"            {\n"
    b"                coordinate\n"
    b"                for coordinate in coordinates\n"
    b"                if coordinate[:2]\n"
    b"                in {\n"
    b'                    ("dev.kikugie", "stonecutter"),\n'
    b"                    (\n"
    b'                        "dev.kikugie.stonecutter",\n'
    b'                        "dev.kikugie.stonecutter.gradle.plugin",\n'
    b"                    ),\n"
    b"                }\n"
    b"            },\n"
    b"            {\n"
    b'                ("dev.kikugie", "stonecutter", stonecutter_version),\n'
    b"                (\n"
    b'                    "dev.kikugie.stonecutter",\n'
    b'                    "dev.kikugie.stonecutter.gradle.plugin",\n'
    b"                    stonecutter_version,\n"
    b"                ),\n"
    b"            },\n"
    b"        )\n"
)
MIXIN_POLICY_PATH = "scripts/release/tests/test_mixin_policy.py"
MIXIN_POLICY_SOURCE_FIXTURE_PATH = (
    "scripts/ci/version_port_migrations/"
    "mixin-policy-with-mandatory-hand.py.fixture"
)
MIXIN_POLICY_SHA256 = (
    "392c43cf5100d1546b3f0be776d687e5e9b001e3830e984097670be3bf38922b"
)
COMMON_HAND_RENDERER_PATH = (
    "common/src/main/java/com/quickskin/mod/mixin/ItemInHandRendererMixin.java"
)
COMMON_HAND_RENDERER_SHA256 = (
    "55fffbeb1f1b948cbe826f87610c18eaddab784dfea1bbc3b276f9c6fd638e33"
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
MIXIN_NEOFORGE_HAND_OVERRIDE_MARKER = (
    b"    (\n"
    b'        "neoforge:com/quickskin/mod/neoforge/mixin/'
    b'PlayerRendererMixin.java",\n'
    b'        "quickskin$redirectRenderHandBuffer",\n'
    b"    ): {1, 2},"
)
MIXIN_COUNT_SUBTEST_MARKER = (
    b"                with self.subTest("
    b"source=source_name, handler=handler_name):\n"
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
MIXIN_1_21_11_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. Before 1.21.2 renderHand requests two buffers (arm and\n"
    b"# sleeve); 1.21.2 through 1.21.10 make one immediate arm draw. The modern collector is deliberately\n"
    b"# not intercepted."
)
MIXIN_HISTORICAL_HAND_COMMENTS = (
    MIXIN_LEGACY_HAND_COMMENT,
    MIXIN_1_21_2_HAND_COMMENT,
    MIXIN_1_21_4_HAND_COMMENT,
    MIXIN_1_21_11_HAND_COMMENT,
)
MIXIN_CANONICAL_HAND_COMMENT = (
    b"# Audited vanilla bytecode multiplicities. Before 1.21.2 renderHand requests two buffers (arm and\n"
    b"# sleeve); 1.21.2 through 1.21.8 make one immediate arm draw. The collector used from 1.21.9 onward\n"
    b"# is deliberately not intercepted."
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
NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS = tuple(
    "neoforge/src/legacy1_21_"
    f"{patch}/java/com/quickskin/mod/neoforge/mixin/PlayerRendererMixin.java"
    for patch in range(2, 6)
)
NEOFORGE_PLAYER_RENDERER_TARGET_PATHS = (
    NEOFORGE_PLAYER_RENDERER_PATH,
    *NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS,
)
NEOFORGE_LEGACY_PLAYER_RENDERER_FIXTURE_PATH = (
    "scripts/ci/version_port_migrations/"
    "neoforge-player-renderer-1.21.5-legacy-adapter.java.fixture"
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
    "8a654cc7cfea1b2bbb170509bd042e2630c59ea1464c62f6cc7c97b1855eb274"
)
NEOFORGE_PLAYER_RENDERER_1_21_9_INPUT_PATH = (
    "scripts/ci/version_port_migrations/"
    "neoforge-player-renderer-1.21.9-with-modern-collector.java.fixture"
)
NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_PATH = (
    "scripts/ci/version_port_migrations/"
    "neoforge-player-renderer-1.21.9-without-modern-collector.java.fixture"
)
NEOFORGE_PLAYER_RENDERER_1_21_9_BEFORE_SHA256 = (
    "a07ddda07a90b36a57d564e4e666e9077174b15b277d1d3ab942aba6c94e0b14"
)
NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_SHA256 = (
    "6f79cce52b54db149f6b88463ffdef27d4cb91902bc7084014a85a1d463210fe"
)
NEOFORGE_PLAYER_RENDERER_MIGRATION_FIXTURES = (
    (
        NEOFORGE_PLAYER_RENDERER_1_21_9_INPUT_PATH,
        NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_PATH,
        NEOFORGE_PLAYER_RENDERER_1_21_9_BEFORE_SHA256,
        NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_SHA256,
    ),
    (
        NEOFORGE_PLAYER_RENDERER_INPUT_PATH,
        NEOFORGE_PLAYER_RENDERER_RESULT_PATH,
        NEOFORGE_PLAYER_RENDERER_BEFORE_SHA256,
        NEOFORGE_PLAYER_RENDERER_RESULT_SHA256,
    ),
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
NEOFORGE_CPM_HAND_SCOPE_POLICY_MARKERS = (
    b"def test_neoforge_immediate_hand_redirect_preserves_cpm_render_type",
    b"self.assertIn(guard, neoforge_source)",
)
NEOFORGE_CPM_HAND_DEFER_LINE = (
    b"        if (CPMCompatIntegration.shouldDeferToCPM()) "
    b"return instance.getBuffer(renderType);\n"
)
NEOFORGE_CPM_HAND_ACTIVE_LINE = (
    b"        if (CPMCompatIntegration.isCPMActivelyRendering()) "
    b"return instance.getBuffer(renderType);\n"
)
NEOFORGE_CPM_HAND_PRESERVE_BLOCK = (
    b"        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()) {\n"
    b"            return instance.getBuffer(renderType);\n"
    b"        }\n"
)
NEOFORGE_CPM_HAND_ADAPTER_LEGACY_PREFIX = (
    b"        if (CPMCompatIntegration.shouldDeferToCPM()\n"
    b"                || CPMCompatIntegration.isCPMActivelyRendering()\n"
)
NEOFORGE_CPM_HAND_ADAPTER_PRESERVE_PREFIX = (
    b"        if (CPMCompatIntegration.shouldPreserveFirstPersonHandRenderType()\n"
)
# Exact target-only NeoForge source variants currently present across the supported release
# branches. The narrow rewrite below changes only their immediate-buffer CPM guard; modern
# SubmitNodeCollector branches remain byte-for-byte untouched.
NEOFORGE_CPM_HAND_SCOPE_MIGRATIONS = {
    "a8aa912b9c4f35b275e04db6a52a4ca3b1899dae3aa4266a50dc9f3b294bcc81": (
        "1cd53e93b83ebc01c563359c90365c21d6c31e3e48797d5fbec67122f5236f76",
        2,
    ),
    "13b7f97c0211dd87f6e3a58e92dcf036cc448b436aaf3bdd2630317d9fd12e28": (
        "a4b4213699f3f4a239f7c5b42292f3d6f510859fee91756b5eb0aa00de0690a7",
        1,
    ),
    "1812986d8a18c9878eb1e573f70c70fcf222b5e4aff4c89e14b9f2624b12fadd": (
        NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_SHA256,
        1,
    ),
    "5273c8bad6c77a5bcd0729fb04e5c3e2250c8490d4618507a04a84855b358ab7": (
        NEOFORGE_PLAYER_RENDERER_RESULT_SHA256,
        1,
    ),
    NEOFORGE_PLAYER_RENDERER_BEFORE_SHA256: (
        "960a0e0d9a8b11593d66ece9993bb908b9903895386365b77322d7093737e4c6",
        1,
    ),
    NEOFORGE_PLAYER_RENDERER_1_21_9_BEFORE_SHA256: (
        "7d84d90d9dc24a463bf843d478d878256c5313e1f4d406d2d0ce3000e0fb2c75",
        1,
    ),
}
NEOFORGE_CPM_HAND_SCOPE_RESULTS = {
    result_sha256: replacement_count
    for result_sha256, replacement_count in NEOFORGE_CPM_HAND_SCOPE_MIGRATIONS.values()
}
NEOFORGE_CPM_HAND_ADAPTER_MIGRATIONS = {
    NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS[0]: (
        "791457b94e8f05bdbe0c920517634c0a6b8a8e9ec92724d76bd8e6bd3f0a791e",
        "6097dce15a79da374d358e457b7a9eb75206b294b100a415a9a03d9cdbac5462",
    ),
    NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS[1]: (
        "04bbb032ac9365e9dfcdfb2c4eaf4c2f37736893ff52bef6ae00af6fc8bf027f",
        "0c004bd5e761a5a77a4a539ed07713174002dfd426ca6136c711a62180eecfb5",
    ),
    NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS[2]: (
        "16ae5f64c9e71351733bc46b8d7925d1ba1ef4833d2e4a1b55fe669e4064a536",
        "749c8de6e99b1c1312ec9960d6e1ea96cbd8e20fe6354e1a174f8475a3f2fd89",
    ),
    NEOFORGE_LEGACY_PLAYER_RENDERER_PATHS[3]: (
        "d7e355848c4b87a4086e35d0085795c91637581adee4bd6ea01ebdaa4d7bf6b5",
        "19785378f03084cd9f391c02584d5efb8d168a7eeb4243b4a59a829f94ce2157",
    ),
}


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


def _migrate_dependency_security_stonecutter(payload: bytes) -> bytes:
    _validate_text_blob(payload, "target dependency-security policy", markers=True)
    if len(payload) > MAX_PROTECTED_BLOB_BYTES:
        raise VersionPortMergeError("target dependency-security policy is too large")
    required_counts = (
        (DEPENDENCY_SECURITY_EXPECTED_ANCHOR, 1, "expected-set anchor"),
        (DEPENDENCY_SECURITY_FIXED_STONECUTTER_LINE, 1, "fixed Stonecutter entry"),
        (DEPENDENCY_SECURITY_ASSERTION_ANCHOR, 1, "expected-set assertion"),
        (DEPENDENCY_SECURITY_STONECUTTER_SETUP, 0, "dynamic Stonecutter setup"),
        (
            DEPENDENCY_SECURITY_DYNAMIC_STONECUTTER_LINE,
            0,
            "dynamic Stonecutter entry",
        ),
        (
            DEPENDENCY_SECURITY_STONECUTTER_ASSERTION,
            0,
            "Stonecutter inventory assertion",
        ),
    )
    for marker, expected, label in required_counts:
        if payload.count(marker) != expected:
            raise VersionPortMergeError(
                f"target dependency-security policy has an invalid {label}"
            )

    payload = payload.replace(
        DEPENDENCY_SECURITY_EXPECTED_ANCHOR,
        DEPENDENCY_SECURITY_STONECUTTER_SETUP
        + DEPENDENCY_SECURITY_EXPECTED_ANCHOR,
        1,
    )
    payload = payload.replace(
        DEPENDENCY_SECURITY_FIXED_STONECUTTER_LINE,
        DEPENDENCY_SECURITY_DYNAMIC_STONECUTTER_LINE,
        1,
    )
    payload = payload.replace(
        DEPENDENCY_SECURITY_ASSERTION_ANCHOR,
        DEPENDENCY_SECURITY_ASSERTION_ANCHOR
        + DEPENDENCY_SECURITY_STONECUTTER_ASSERTION,
        1,
    )
    migrated_counts = (
        (DEPENDENCY_SECURITY_STONECUTTER_SETUP, 1),
        (DEPENDENCY_SECURITY_DYNAMIC_STONECUTTER_LINE, 2),
        (DEPENDENCY_SECURITY_STONECUTTER_ASSERTION, 1),
    )
    for marker, expected in migrated_counts:
        if payload.count(marker) != expected:
            raise VersionPortMergeError(
                "dependency-security policy did not reach the dynamic Stonecutter policy"
            )
    if DEPENDENCY_SECURITY_FIXED_STONECUTTER_LINE in payload:
        raise VersionPortMergeError(
            "dependency-security policy retained the fixed Stonecutter version"
        )
    return payload


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
    neoforge_hand_overrides = payload.count(MIXIN_NEOFORGE_HAND_OVERRIDE_MARKER)
    if (
        optional_blocks > 1
        or scoped_blocks > 1
        or (optional_blocks and scoped_blocks)
    ):
        raise VersionPortMergeError(
            "target mixin policy repeats the obsolete optional-hand rule"
        )
    if neoforge_hand_overrides > 1:
        raise VersionPortMergeError(
            "target mixin policy repeats the NeoForge hand override"
        )
    if optional_blocks == 1:
        payload = payload.replace(
            MIXIN_LEGACY_OPTIONAL_HAND_BLOCK,
            MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK,
            1,
        )
    elif scoped_blocks == 0 and neoforge_hand_overrides == 1:
        if payload.count(MIXIN_COUNT_SUBTEST_MARKER) != 1:
            raise VersionPortMergeError(
                "target mixin policy lacks the audited count-check insertion point"
            )
        insertion = payload.index(MIXIN_COUNT_SUBTEST_MARKER)
        payload = (
            payload[:insertion]
            + MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK
            + payload[insertion:]
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


def _split_verification_metadata(
    payload: bytes, label: str
) -> tuple[bytes, dict[tuple[str, str, str], bytes], bytes]:
    _validate_text_blob(payload, label, markers=True)
    if b"\r" in payload:
        raise VersionPortMergeError(f"{label} must use LF line endings")
    opening = b"   <components>\n"
    closing = b"   </components>\n"
    if payload.count(opening) != 1 or payload.count(closing) != 1:
        raise VersionPortMergeError(f"{label} has an invalid components section")
    body_start = payload.index(opening) + len(opening)
    body_end = payload.index(closing, body_start)
    prefix = payload[:body_start]
    body = payload[body_start:body_end]
    suffix = payload[body_end:]
    if suffix != closing + b"</verification-metadata>\n":
        raise VersionPortMergeError(f"{label} has an invalid document suffix")

    components: dict[tuple[str, str, str], bytes] = {}
    position = 0
    while position < len(body):
        match = VERIFICATION_COMPONENT_PATTERN.match(body, position)
        if match is None:
            raise VersionPortMergeError(
                f"{label} has content outside a canonical component block"
            )
        block = match.group(0)
        if block.count(b"<component ") != 1 or block.count(b"</component>") != 1:
            raise VersionPortMergeError(f"{label} has a nested component block")
        key = (
            match.group("group").decode("utf-8"),
            match.group("name").decode("utf-8"),
            match.group("version").decode("utf-8"),
        )
        if key in components:
            raise VersionPortMergeError(
                f"{label} repeats verification component {':'.join(key)!r}"
            )
        components[key] = block
        position = match.end()
    if not components:
        raise VersionPortMergeError(f"{label} has no verification components")
    if tuple(components) != tuple(sorted(components)):
        raise VersionPortMergeError(
            f"{label} verification components are not strictly sorted"
        )
    return prefix, components, suffix


def _merge_verification_metadata_payloads(
    base: bytes, target: bytes, source: bytes
) -> bytes:
    base_prefix, base_components, base_suffix = _split_verification_metadata(
        base, "base verification metadata"
    )
    target_prefix, target_components, target_suffix = _split_verification_metadata(
        target, "target verification metadata"
    )
    source_prefix, source_components, source_suffix = _split_verification_metadata(
        source, "source verification metadata"
    )
    if target_prefix == source_prefix or base_prefix == source_prefix:
        merged_prefix = target_prefix
    elif base_prefix == target_prefix:
        merged_prefix = source_prefix
    else:
        raise VersionPortMergeError(
            "verification metadata configuration changed incompatibly"
        )
    if target_suffix == source_suffix or base_suffix == source_suffix:
        merged_suffix = target_suffix
    elif base_suffix == target_suffix:
        merged_suffix = source_suffix
    else:
        raise VersionPortMergeError(
            "verification metadata document suffix changed incompatibly"
        )

    merged: dict[tuple[str, str, str], bytes] = {}
    keys = sorted(
        set(base_components) | set(target_components) | set(source_components)
    )
    for key in keys:
        base_block = base_components.get(key)
        target_block = target_components.get(key)
        source_block = source_components.get(key)
        if target_block == source_block:
            chosen = target_block
        elif base_block == target_block:
            chosen = source_block
        elif base_block == source_block:
            chosen = target_block
        elif (
            base_block is not None
            and target_block is None
            and source_block is not None
        ):
            # The release branch no longer resolves this dependency. Keep its deletion instead of
            # importing new hashes for a component that is absent from that branch's graph.
            chosen = None
        else:
            raise VersionPortMergeError(
                "verification component changed incompatibly: " + ":".join(key)
            )
        if chosen is not None:
            merged[key] = chosen

    result = (
        merged_prefix
        + b"".join(merged[key] for key in sorted(merged))
        + merged_suffix
    )
    _split_verification_metadata(result, "merged verification metadata")
    return result


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

    if path == DEPENDENCY_SECURITY_POLICY_PATH:
        if hashlib.sha256(inputs[3]).hexdigest() != DEPENDENCY_SECURITY_POLICY_SHA256:
            raise VersionPortMergeError(
                "source dependency-security policy is not the audited Stonecutter policy"
            )
        migrated = _migrate_dependency_security_stonecutter(inputs[2])
        result = _index_entry_from_payload(
            repository,
            path,
            stages[2].mode,
            migrated,
            merge_files,
            "migrated target dependency-security policy",
            oid_length,
        )
        _install_index_entry(repository, result)
        return result, {
            "path": path,
            "policy": "migrate-dynamic-stonecutter-security-policy",
            "stages": _stages_payload(stages),
            "result": result.object_payload(),
        }

    if path == VERIFICATION_METADATA_PATH:
        merged = _merge_verification_metadata_payloads(
            inputs[1], inputs[2], inputs[3]
        )
        result = _index_entry_from_payload(
            repository,
            path,
            stages[2].mode,
            merged,
            merge_files,
            "merged verification metadata",
            oid_length,
        )
        _install_index_entry(repository, result)
        return result, {
            "path": path,
            "policy": "merge-gradle-verification-metadata",
            "stages": _stages_payload(stages),
            "result": result.object_payload(),
        }

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
        and components[2] >= 9
    )


def _source_requests_neoforge_cpm_hand_scope(
    repository: Path,
    source: str,
    oid_length: int,
) -> bool:
    """Return whether the source carries the complete audited NeoForge CPM hand policy."""

    policy_entry = _tree_entry(
        repository, source, CPM_TRANSITION_POLICY_PATH, oid_length
    )
    if policy_entry is None:
        return False
    policy_payload = _read_blob(
        repository,
        policy_entry.oid,
        limit=MAX_PROTECTED_BLOB_BYTES,
        label="source CPM transition policy",
    )
    policy_matches = tuple(
        marker in policy_payload
        for marker in NEOFORGE_CPM_HAND_SCOPE_POLICY_MARKERS
    )
    if not any(policy_matches):
        return False
    if not all(policy_matches):
        raise VersionPortMergeError(
            "source CPM transition policy has incomplete NeoForge hand-scope markers"
        )
    _validate_text_blob(policy_payload, "source CPM transition policy", markers=True)
    return True


def _neoforge_collector_migration_fixtures(
    repository: Path,
    source: str,
    oid_length: int,
) -> dict[str, tuple[IndexEntry, str]] | None:
    """Authenticate the source policy and every audited NeoForge migration pair."""

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

    results: dict[str, tuple[IndexEntry, str]] = {}
    for input_path, result_path, before_sha256, result_sha256 in (
        NEOFORGE_PLAYER_RENDERER_MIGRATION_FIXTURES
    ):
        input_entry = _tree_entry(repository, source, input_path, oid_length)
        result_entry = _tree_entry(repository, source, result_path, oid_length)
        if input_entry is None or result_entry is None:
            raise VersionPortMergeError(
                "source CPM collector policy lacks its NeoForge migration fixtures"
            )
        input_payload = _read_blob(
            repository,
            input_entry.oid,
            limit=MAX_PROTECTED_BLOB_BYTES,
            label=f"source NeoForge collector migration input {input_path}",
        )
        _validate_text_blob(
            input_payload,
            f"source NeoForge collector migration input {input_path}",
            markers=True,
        )
        if hashlib.sha256(input_payload).hexdigest() != before_sha256:
            raise VersionPortMergeError(
                "source NeoForge collector migration input fixture is not audited"
            )
        if not all(
            marker in input_payload for marker in MODERN_HAND_COLLECTOR_MARKERS
        ):
            raise VersionPortMergeError(
                "source NeoForge collector migration input lacks the modern redirect"
            )

        result_payload = _read_blob(
            repository,
            result_entry.oid,
            limit=MAX_PROTECTED_BLOB_BYTES,
            label=f"source NeoForge collector migration result {result_path}",
        )
        _validate_text_blob(
            result_payload,
            f"source NeoForge collector migration result {result_path}",
            markers=True,
        )
        if hashlib.sha256(result_payload).hexdigest() != result_sha256:
            raise VersionPortMergeError(
                "source NeoForge collector migration fixture is not the audited result"
            )
        if any(
            marker in result_payload for marker in MODERN_HAND_COLLECTOR_MARKERS
        ):
            raise VersionPortMergeError(
                "source NeoForge collector migration fixture still intercepts the modern collector"
            )
        if before_sha256 in results:
            raise VersionPortMergeError(
                "source NeoForge collector migrations repeat an audited input"
            )
        results[before_sha256] = (result_entry, result_path)
    return results


def _migrate_neoforge_cpm_hand_scope(
    repository: Path,
    oid_length: int,
    temporary: Path,
) -> list[dict[str, Any]]:
    """Install the CPM-preserving guard in exact target-only NeoForge renderers."""

    indexed = _entries_by_path(_snapshot_index(repository, oid_length).entries)
    results: list[dict[str, Any]] = []
    for path in NEOFORGE_PLAYER_RENDERER_TARGET_PATHS:
        current_entries = indexed.get(path, ())
        if not current_entries:
            continue
        if len(current_entries) != 1 or current_entries[0].stage != 0:
            raise VersionPortMergeError(
                f"NeoForge player renderer {path!r} is not resolved before CPM "
                "hand-scope migration"
            )
        current_entry = current_entries[0]
        current_payload = _read_blob(
            repository,
            current_entry.oid,
            limit=MAX_PROTECTED_BLOB_BYTES,
            label=f"merged NeoForge player renderer {path}",
        )
        _validate_text_blob(
            current_payload, f"merged NeoForge player renderer {path}", markers=True
        )
        current_sha256 = hashlib.sha256(current_payload).hexdigest()

        if path != NEOFORGE_PLAYER_RENDERER_PATH:
            before_sha256, expected_sha256 = NEOFORGE_CPM_HAND_ADAPTER_MIGRATIONS[path]
            if current_sha256 == expected_sha256:
                if (
                    current_payload.count(NEOFORGE_CPM_HAND_ADAPTER_PRESERVE_PREFIX)
                    != 1
                    or NEOFORGE_CPM_HAND_ADAPTER_LEGACY_PREFIX in current_payload
                ):
                    raise VersionPortMergeError(
                        "audited NeoForge legacy adapter result has inconsistent markers"
                    )
                continue
            if current_sha256 != before_sha256:
                raise VersionPortMergeError(
                    f"NeoForge legacy adapter {path!r} does not match an audited "
                    "target source"
                )
            if (
                current_payload.count(NEOFORGE_CPM_HAND_ADAPTER_LEGACY_PREFIX) != 1
                or NEOFORGE_CPM_HAND_ADAPTER_PRESERVE_PREFIX in current_payload
            ):
                raise VersionPortMergeError(
                    "audited NeoForge legacy adapter has inconsistent CPM guards"
                )
            migrated_payload = current_payload.replace(
                NEOFORGE_CPM_HAND_ADAPTER_LEGACY_PREFIX,
                NEOFORGE_CPM_HAND_ADAPTER_PRESERVE_PREFIX,
            )
            if (
                hashlib.sha256(migrated_payload).hexdigest() != expected_sha256
                or migrated_payload.count(NEOFORGE_CPM_HAND_ADAPTER_PRESERVE_PREFIX)
                != 1
                or NEOFORGE_CPM_HAND_ADAPTER_LEGACY_PREFIX in migrated_payload
            ):
                raise VersionPortMergeError(
                    "NeoForge legacy adapter migration did not produce its audited result"
                )
        else:
            settled_count = NEOFORGE_CPM_HAND_SCOPE_RESULTS.get(current_sha256)
            if settled_count is not None:
                if (
                    current_payload.count(NEOFORGE_CPM_HAND_PRESERVE_BLOCK)
                    != settled_count
                    or NEOFORGE_CPM_HAND_DEFER_LINE in current_payload
                    or NEOFORGE_CPM_HAND_ACTIVE_LINE in current_payload
                ):
                    raise VersionPortMergeError(
                        "audited NeoForge CPM hand-scope result has inconsistent markers"
                    )
                continue

            migration = NEOFORGE_CPM_HAND_SCOPE_MIGRATIONS.get(current_sha256)
            if migration is None:
                if (
                    NEOFORGE_CPM_HAND_DEFER_LINE in current_payload
                    or NEOFORGE_CPM_HAND_ACTIVE_LINE in current_payload
                ):
                    raise VersionPortMergeError(
                        "NeoForge CPM hand scope does not match an audited target source"
                    )
                continue

            expected_sha256, replacement_count = migration
            if (
                current_payload.count(NEOFORGE_CPM_HAND_DEFER_LINE)
                != replacement_count
                or current_payload.count(NEOFORGE_CPM_HAND_ACTIVE_LINE)
                != replacement_count
                or NEOFORGE_CPM_HAND_PRESERVE_BLOCK in current_payload
            ):
                raise VersionPortMergeError(
                    "audited NeoForge CPM hand source has inconsistent legacy guards"
                )
            migrated_payload = current_payload.replace(
                NEOFORGE_CPM_HAND_DEFER_LINE + b"\n", b""
            )
            migrated_payload = migrated_payload.replace(
                NEOFORGE_CPM_HAND_DEFER_LINE, b""
            )
            migrated_payload = migrated_payload.replace(
                NEOFORGE_CPM_HAND_ACTIVE_LINE,
                NEOFORGE_CPM_HAND_PRESERVE_BLOCK,
            )
            if (
                hashlib.sha256(migrated_payload).hexdigest() != expected_sha256
                or migrated_payload.count(NEOFORGE_CPM_HAND_PRESERVE_BLOCK)
                != replacement_count
                or NEOFORGE_CPM_HAND_DEFER_LINE in migrated_payload
                or NEOFORGE_CPM_HAND_ACTIVE_LINE in migrated_payload
            ):
                raise VersionPortMergeError(
                    "NeoForge CPM hand-scope migration did not produce its audited result"
                )

        result_entry = _index_entry_from_payload(
            repository,
            path,
            current_entry.mode,
            migrated_payload,
            temporary,
            f"migrated NeoForge CPM hand renderer {path}",
            oid_length,
        )
        _install_index_entry(repository, result_entry)
        results.append(
            {
                "path": path,
                "policy": "migrate-neoforge-cpm-hand-scope",
                "target": current_entry.object_payload(),
                "result": result_entry.object_payload(),
            }
        )
    return results


def _migrate_neoforge_modern_hand_collector(
    repository: Path,
    work_head: str,
    result_fixtures: Mapping[str, tuple[IndexEntry, str]],
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
    target_sha256 = hashlib.sha256(target_payload).hexdigest()
    selected_fixture = result_fixtures.get(target_sha256)
    if selected_fixture is None:
        raise VersionPortMergeError(
            "target NeoForge modern hand collector does not match the audited migration input"
        )
    result_fixture, result_path = selected_fixture

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
            "source_path": result_path,
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

            if "neoforge" in active_loaders:
                if _runtime_uses_vanilla_translucent_hand_collector(
                    target_profile.runtime_version
                ):
                    result_fixtures = _neoforge_collector_migration_fixtures(
                        repository, source, oid_length
                    )
                    if result_fixtures is not None:
                        protected_resolutions.extend(
                            _migrate_neoforge_modern_hand_collector(
                                repository,
                                work_head,
                                result_fixtures,
                                oid_length,
                            )
                        )

                if _source_requests_neoforge_cpm_hand_scope(
                    repository, source, oid_length
                ):
                    protected_resolutions.extend(
                        _migrate_neoforge_cpm_hand_scope(
                            repository,
                            oid_length,
                            temporary,
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
