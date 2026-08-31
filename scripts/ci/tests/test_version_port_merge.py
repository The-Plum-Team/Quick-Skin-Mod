from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import version_port_merge  # noqa: E402


class VersionPortMergeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.repository = self.root / "repository"
        self.repository.mkdir()
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Test Author")
        self.git("config", "user.email", "author@example.invalid")

        self.write("e2e/README.md", self.readme())
        self.write(
            "release/release-matrix.json",
            self.matrix("base", common_overlay="legacy1_20_1"),
        )
        self.write(
            "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json",
            "base overlay\n",
        )
        self.write("forge/build.gradle.kts", "base forge\n")
        self.write("src/Conflict.txt", "base choice\n")
        self.write("src/[literal]*?.txt", "base metachar choice\n")
        self.write("src/literal-decoy.txt", "decoy must survive\n")
        self.write("safe.txt", "base safe\n")
        self.git("add", "--all")
        self.git("commit", "-m", "base")
        base = self.sha("HEAD")
        self.base = base

        self.git("switch", "--create", "source", base)
        self.write(
            "e2e/README.md",
            self.readme(changes={30: "source conflict", 55: "source-only hunk"}),
        )
        self.write(
            "release/release-matrix.json",
            self.matrix("source", common_overlay="legacy1_20_1"),
        )
        self.write(
            "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json",
            "source overlay\n",
        )
        self.write("forge/build.gradle.kts", "source forge\n")
        self.write("src/Conflict.txt", "source choice\n")
        self.write("src/[literal]*?.txt", "source metachar choice\n")
        self.write("safe.txt", "source safe\n")
        self.git("add", "--all")
        self.git("commit", "-m", "source changes")
        self.source = self.sha("HEAD")

        self.git("switch", "--create", "target", base)
        self.write(
            "e2e/README.md",
            self.readme(changes={5: "target-only hunk", 30: "target conflict"}),
        )
        self.write("release/release-matrix.json", self.matrix("target"))
        self.write("src/Conflict.txt", "target choice\n")
        self.write("src/[literal]*?.txt", "target metachar choice\n")
        self.git("rm", "forge/build.gradle.kts")
        self.git(
            "rm",
            "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json",
        )
        self.git("add", "--all")
        self.git("commit", "-m", "target changes")
        self.target = self.sha("HEAD")

        # The merge controller must not depend on ambient author identity.
        self.git("config", "--unset-all", "user.name")
        self.git("config", "--unset-all", "user.email")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def git(
        self,
        *arguments: str,
        environment: dict[str, str] | None = None,
        input_bytes: bytes | None = None,
    ) -> bytes:
        env = os.environ.copy()
        if environment:
            env.update(environment)
        completed = subprocess.run(
            ("git", "-C", str(self.repository), *arguments),
            input=input_bytes,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            env=env,
        )
        if completed.returncode != 0:
            self.fail(
                f"git {' '.join(arguments)} failed: "
                f"{completed.stderr.decode(errors='replace')}"
            )
        return completed.stdout

    def sha(self, revision: str) -> str:
        return self.git("rev-parse", revision).decode("ascii").strip()

    def write(self, relative: str, content: str) -> None:
        path = self.repository / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    @staticmethod
    def readme(changes: dict[int, str] | None = None) -> str:
        changes = changes or {}
        return "".join(
            f"{changes.get(index, f'line {index}')}\n" for index in range(1, 61)
        )

    @staticmethod
    def matrix(
        description: str,
        loaders: tuple[str, ...] = ("fabric", "neoforge"),
        common_overlay: str | None = None,
        runtime_version: str = "1.20.1",
    ) -> str:
        common_routes = (
            {"1.20.1": common_overlay} if common_overlay is not None else {}
        )
        return json.dumps(
            {
                "schema_version": 2,
                "description": description,
                "artifacts": [
                    {"artifact_node": f"{loader}-test", "loader": loader}
                    for loader in loaders
                ],
                "source_overlays": {
                    "common": common_routes,
                    **{loader: {} for loader in loaders},
                },
                "runtimes": [
                    {"loader": loader, "runtime_version": runtime_version}
                    for loader in loaders
                ],
            },
            indent=2,
        ) + "\n"

    def commit(self, message: str) -> None:
        self.git(
            "-c",
            "user.name=Test Author",
            "-c",
            "user.email=author@example.invalid",
            "commit",
            "-m",
            message,
        )

    @staticmethod
    def legacy_mixin_hand_policy() -> str:
        payload = (
            ROOT / version_port_merge.MIXIN_POLICY_SOURCE_FIXTURE_PATH
        ).read_text(encoding="utf-8")
        cape = version_port_merge.MIXIN_CAPE_POLICY_LINE.decode("utf-8")
        hand = version_port_merge.MIXIN_HAND_POLICY_LINE.decode("utf-8")
        payload = payload.replace(cape + hand, cape, 1)
        payload = payload.replace(
            "DEGRADABLE_MIXINS = {\n",
            "DEGRADABLE_MIXINS = {\n" + hand,
            1,
        )
        payload = payload.replace(
            version_port_merge.MIXIN_CANONICAL_HAND_COMMENT.decode("utf-8"),
            version_port_merge.MIXIN_LEGACY_HAND_COMMENT.decode("utf-8"),
            1,
        )
        subtest = (
            "                with self.subTest("
            "source=source_name, handler=handler_name):\n"
        )
        payload = payload.replace(
            subtest,
            version_port_merge.MIXIN_LEGACY_OPTIONAL_HAND_BLOCK.decode("utf-8")
            + subtest,
            1,
        )
        return payload

    def assert_clean_at(self, expected_head: str) -> None:
        self.assertEqual(self.sha("HEAD"), expected_head)
        self.assertEqual(
            self.git("status", "--porcelain=v1", "--untracked-files=all"), b""
        )
        merge_head = subprocess.run(
            (
                "git",
                "-C",
                str(self.repository),
                "rev-parse",
                "-q",
                "--verify",
                "MERGE_HEAD",
            ),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        self.assertNotEqual(merge_head.returncode, 0)

    def assert_clean_target(self) -> None:
        self.assert_clean_at(self.target)

    def prepare_neoforge_collector_fixture_branches(
        self,
        *,
        runtime_version: str,
        target_payload: str | None = None,
    ) -> tuple[str, str, str, str]:
        components = tuple(int(value) for value in runtime_version.split("."))
        if (
            len(components) == 3
            and components[:2] == (1, 21)
            and 9 <= components[2] < 11
        ):
            input_path = (
                version_port_merge.NEOFORGE_PLAYER_RENDERER_1_21_9_INPUT_PATH
            )
            result_path = (
                version_port_merge.NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_PATH
            )
        else:
            input_path = version_port_merge.NEOFORGE_PLAYER_RENDERER_INPUT_PATH
            result_path = version_port_merge.NEOFORGE_PLAYER_RENDERER_RESULT_PATH
        input_payload = (
            ROOT / input_path
        ).read_text(encoding="utf-8")
        result_payload = (
            ROOT / result_path
        ).read_text(encoding="utf-8")
        target_payload = target_payload or input_payload

        self.git("switch", "--create", "collector-source", self.base)
        self.write(
            version_port_merge.CPM_TRANSITION_POLICY_PATH,
            "def test_modern_first_person_collectors_remain_owned_by_model_mods():\n"
            '    self.assertNotIn("quickskin$redirectSubmitModelPart", source)\n'
            '    self.assertNotIn("SubmitNodeCollector;submitModelPart", source)\n',
        )
        self.write(
            version_port_merge.COMMON_HAND_RENDERER_PATH,
            "class ItemInHandRendererMixin {\n"
            "    void quickskin$redirectRenderHandBuffer() {}\n"
            "}\n",
        )
        for fixture_paths in version_port_merge.NEOFORGE_PLAYER_RENDERER_MIGRATION_FIXTURES:
            for fixture_path in fixture_paths[:2]:
                self.write(
                    fixture_path,
                    (ROOT / fixture_path).read_text(encoding="utf-8"),
                )
        self.git("add", "--all")
        self.commit("add CPM collector ownership policy")
        source = self.sha("HEAD")

        self.git("switch", "--create", "collector-target", self.base)
        self.write(
            "release/release-matrix.json",
            self.matrix(
                f"collector target {runtime_version}",
                runtime_version=runtime_version,
            ),
        )
        self.write(version_port_merge.NEOFORGE_PLAYER_RENDERER_PATH, target_payload)
        self.git("add", "--all")
        self.commit(f"add NeoForge renderer for {runtime_version}")
        target = self.sha("HEAD")
        return source, target, input_payload, result_payload

    def prepare_common_hand_renderer_branches(
        self,
        *,
        drift_source: bool = False,
        drift_mixin_policy: bool = False,
    ) -> tuple[str, str, str]:
        canonical_payload = (
            ROOT / version_port_merge.COMMON_HAND_RENDERER_PATH
        ).read_text(encoding="utf-8")
        canonical_mixin_policy = (
            ROOT / version_port_merge.MIXIN_POLICY_SOURCE_FIXTURE_PATH
        ).read_text(encoding="utf-8")
        legacy_mixin_policy = self.legacy_mixin_hand_policy()
        source_payload = canonical_payload
        if drift_source:
            source_payload += "// unaudited drift\n"
        if drift_mixin_policy:
            canonical_mixin_policy += "# unaudited drift\n"

        self.git("switch", "--create", "hand-base", self.base)
        self.write(
            version_port_merge.COMMON_HAND_RENDERER_PATH,
            "class ItemInHandRendererMixin {\n"
            "    // Shared legacy implementation.\n"
            "}\n",
        )
        self.write(version_port_merge.MIXIN_POLICY_PATH, legacy_mixin_policy)
        self.git("add", "--all")
        self.commit("add shared hand renderer")
        hand_base = self.sha("HEAD")

        self.git("switch", "--create", "hand-source", hand_base)
        self.write(
            version_port_merge.CPM_TRANSITION_POLICY_PATH,
            (
                ROOT / version_port_merge.CPM_TRANSITION_POLICY_PATH
            ).read_text(encoding="utf-8"),
        )
        self.write(version_port_merge.COMMON_HAND_RENDERER_PATH, source_payload)
        self.write(version_port_merge.MIXIN_POLICY_PATH, canonical_mixin_policy)
        self.git("add", "--all")
        self.commit("make hand multiplicity canonical")
        source = self.sha("HEAD")

        self.git("switch", "--create", "hand-target", hand_base)
        self.write(
            "release/release-matrix.json",
            self.matrix("hand target", runtime_version="1.21.8"),
        )
        self.write(
            version_port_merge.COMMON_HAND_RENDERER_PATH,
            "class ItemInHandRendererMixin {\n"
            "    // Target retained require=0 and expect=2.\n"
            "}\n",
        )
        target_mixin_policy = legacy_mixin_policy.replace(
            version_port_merge.MIXIN_CAPE_POLICY_LINE.decode("utf-8"),
            version_port_merge.MIXIN_CAPE_POLICY_LINE.decode("utf-8")
            + '    "neoforge:target-only-loader-policy.java",\n',
            1,
        )
        self.write(version_port_merge.MIXIN_POLICY_PATH, target_mixin_policy)
        self.git("add", "--all")
        self.commit("diverge hand renderer")
        target = self.sha("HEAD")
        return source, target, canonical_payload

    def unmerged_paths(self) -> tuple[str, ...]:
        output = self.git("diff", "--name-only", "-z", "--diff-filter=U")
        return tuple(sorted(value.decode("utf-8") for value in output.split(b"\0") if value))

    def make_candidate_index(
        self,
        *,
        ai_content: bytes | None,
        protected_content: bytes | None = None,
        mode: str = "100644",
        metachar_content: bytes | None | object = ...,
    ) -> Path:
        candidate = self.root / f"candidate-{len(list(self.root.glob('candidate-*')))}.index"
        environment = {"GIT_INDEX_FILE": str(candidate)}
        self.git("read-tree", self.target, environment=environment)
        if ai_content is None:
            self.git(
                "update-index",
                "--force-remove",
                "--",
                "src/Conflict.txt",
                environment=environment,
            )
        else:
            oid = self.git("hash-object", "-w", "--stdin", input_bytes=ai_content)
            self.git(
                "update-index",
                "--add",
                "--cacheinfo",
                mode,
                oid.decode("ascii").strip(),
                "src/Conflict.txt",
                environment=environment,
            )
        if protected_content is not None:
            oid = self.git(
                "hash-object", "-w", "--stdin", input_bytes=protected_content
            )
            self.git(
                "update-index",
                "--add",
                "--cacheinfo",
                "100644",
                oid.decode("ascii").strip(),
                "e2e/README.md",
                environment=environment,
            )
        if metachar_content is not ...:
            if metachar_content is None:
                self.git(
                    "update-index",
                    "--force-remove",
                    "--",
                    "src/[literal]*?.txt",
                    environment=environment,
                )
            else:
                assert isinstance(metachar_content, bytes)
                oid = self.git(
                    "hash-object", "-w", "--stdin", input_bytes=metachar_content
                )
                self.git(
                    "update-index",
                    "--add",
                    "--cacheinfo",
                    "100644",
                    oid.decode("ascii").strip(),
                    "src/[literal]*?.txt",
                    environment=environment,
                )
        return candidate

    def index_tree(self, candidate: Path) -> str:
        return self.git(
            "write-tree",
            environment={"GIT_INDEX_FILE": str(candidate)},
        ).decode("ascii").strip()

    def test_probe_is_deterministic_and_always_restores_clean_target(self) -> None:
        first = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="probe",
        )
        self.assert_clean_target()
        second = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="probe",
        )
        self.assert_clean_target()

        self.assertEqual(first, second)
        self.assertEqual(first["schema_version"], 1)
        self.assertTrue(first["conflicted"])
        self.assertEqual(
            first["conflicts"],
            [
                "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json",
                "e2e/README.md",
                "forge/build.gradle.kts",
                "release/release-matrix.json",
                "src/Conflict.txt",
                "src/[literal]*?.txt",
            ],
        )
        self.assertEqual(
            first["ai_conflicts"],
            ["src/Conflict.txt", "src/[literal]*?.txt"],
        )
        self.assertEqual(
            [item["policy"] for item in first["protected_resolutions"]],
            [
                "delete-inactive-overlay",
                "source-preferred-three-way",
                "delete-inactive-loader",
                "retain-target",
            ],
        )
        self.assertRegex(first["mechanical_index"]["sha256"], r"^[0-9a-f]{64}$")

    def test_clean_merge_probe_removes_merge_head_and_restores_target(self) -> None:
        self.git("switch", "--create", "clean-source", self.target)
        self.write("clean-source.txt", "clean source change\n")
        self.git("add", "--all")
        self.commit("clean source")
        clean_source = self.sha("HEAD")
        self.git("switch", "target")

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            clean_source,
            mode="probe",
        )

        self.assertFalse(evidence["conflicted"])
        self.assertEqual(evidence["conflicts"], [])
        self.assertEqual(evidence["ai_conflicts"], [])
        self.assertEqual(evidence["protected_resolutions"], [])
        self.assertFalse((self.repository / "clean-source.txt").exists())
        self.assert_clean_target()

    def test_common_hand_renderer_uses_the_audited_source_across_versions(
        self,
    ) -> None:
        source, target, canonical_payload = (
            self.prepare_common_hand_renderer_branches()
        )

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            target,
            source,
            mode="prepare",
        )

        self.assertEqual(evidence["ai_conflicts"], [])
        self.assertIn(
            version_port_merge.COMMON_HAND_RENDERER_PATH,
            evidence["conflicts"],
        )
        self.assertIn(
            version_port_merge.MIXIN_POLICY_PATH,
            evidence["conflicts"],
        )
        migrated = [
            item
            for item in evidence["protected_resolutions"]
            if item["policy"] == "install-canonical-common-hand-renderer"
        ]
        self.assertEqual(len(migrated), 1)
        self.assertEqual(
            migrated[0]["path"], version_port_merge.COMMON_HAND_RENDERER_PATH
        )
        self.assertEqual(
            (
                self.repository / version_port_merge.COMMON_HAND_RENDERER_PATH
            ).read_text(encoding="utf-8"),
            canonical_payload,
        )
        mixin_policy = (
            self.repository / version_port_merge.MIXIN_POLICY_PATH
        ).read_text(encoding="utf-8")
        self.assertIn("neoforge:target-only-loader-policy.java", mixin_policy)
        critical_start, critical_end = version_port_merge._policy_set_span(
            mixin_policy.encode("utf-8"), "CRITICAL_MIXINS"
        )
        degradable_start, degradable_end = version_port_merge._policy_set_span(
            mixin_policy.encode("utf-8"), "DEGRADABLE_MIXINS"
        )
        encoded_policy = mixin_policy.encode("utf-8")
        self.assertIn(
            version_port_merge.MIXIN_HAND_POLICY_LINE,
            encoded_policy[critical_start:critical_end],
        )
        self.assertNotIn(
            version_port_merge.MIXIN_HAND_POLICY_LINE,
            encoded_policy[degradable_start:degradable_end],
        )
        self.assertNotIn(
            version_port_merge.MIXIN_LEGACY_OPTIONAL_HAND_BLOCK.decode("utf-8"),
            mixin_policy,
        )
        self.assertEqual(
            mixin_policy.count(
                version_port_merge.MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK.decode(
                    "utf-8"
                )
            ),
            1,
        )
        self.assertIn(
            "migrate-mandatory-common-hand-policy",
            [item["policy"] for item in evidence["protected_resolutions"]],
        )
        self.git("merge", "--abort")
        self.assert_clean_at(target)

    def test_unaudited_common_hand_renderer_fails_closed(self) -> None:
        source, target, _ = self.prepare_common_hand_renderer_branches(
            drift_source=True
        )

        with self.assertRaisesRegex(
            version_port_merge.VersionPortMergeError,
            "not the audited multiplicity implementation",
        ):
            version_port_merge.reproduce_merge(
                self.repository,
                target,
                source,
                mode="prepare",
            )
        self.assert_clean_at(target)

    def test_unaudited_mixin_hand_policy_fails_closed(self) -> None:
        source, target, _ = self.prepare_common_hand_renderer_branches(
            drift_mixin_policy=True
        )

        with self.assertRaisesRegex(
            version_port_merge.VersionPortMergeError,
            "not the audited mandatory-hand policy",
        ):
            version_port_merge.reproduce_merge(
                self.repository,
                target,
                source,
                mode="prepare",
            )
        self.assert_clean_at(target)

    def test_historical_hand_comments_normalize_to_the_audited_threshold(self) -> None:
        canonical = (ROOT / version_port_merge.MIXIN_POLICY_PATH).read_bytes()
        for historical in version_port_merge.MIXIN_HISTORICAL_HAND_COMMENTS:
            with self.subTest(comment=historical):
                legacy = canonical.replace(
                    version_port_merge.MIXIN_CANONICAL_HAND_COMMENT,
                    historical,
                    1,
                )
                self.assertEqual(
                    version_port_merge._migrate_mixin_hand_policy_payload(legacy),
                    canonical,
                )

    def test_scoped_neoforge_hand_count_migration_is_idempotent(self) -> None:
        legacy = self.legacy_mixin_hand_policy().encode("utf-8")

        migrated = version_port_merge._migrate_mixin_hand_policy_payload(legacy)

        self.assertNotIn(
            version_port_merge.MIXIN_LEGACY_OPTIONAL_HAND_BLOCK, migrated
        )
        self.assertEqual(
            migrated.count(
                version_port_merge.MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK
            ),
            1,
        )
        self.assertEqual(
            version_port_merge._migrate_mixin_hand_policy_payload(migrated),
            migrated,
        )

    def test_missing_scoped_neoforge_hand_count_is_restored(self) -> None:
        inherited = (
            ROOT / version_port_merge.MIXIN_POLICY_SOURCE_FIXTURE_PATH
        ).read_bytes()
        inherited = inherited.replace(
            version_port_merge.MIXIN_HAND_OVERRIDE_MARKER,
            version_port_merge.MIXIN_HAND_OVERRIDE_MARKER
            + b"\n"
            + version_port_merge.MIXIN_NEOFORGE_HAND_OVERRIDE_MARKER,
            1,
        )

        self.assertNotIn(
            version_port_merge.MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK,
            inherited,
        )

        migrated = version_port_merge._migrate_mixin_hand_policy_payload(inherited)

        self.assertEqual(
            migrated.count(
                version_port_merge.MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK
            ),
            1,
        )
        self.assertLess(
            migrated.index(version_port_merge.MIXIN_NEOFORGE_LEGACY_HAND_COUNT_BLOCK),
            migrated.index(version_port_merge.MIXIN_COUNT_SUBTEST_MARKER),
        )
        self.assertEqual(
            version_port_merge._migrate_mixin_hand_policy_payload(migrated),
            migrated,
        )

    def test_mixin_policy_source_fixture_is_the_authenticated_policy(self) -> None:
        fixture = (
            ROOT / version_port_merge.MIXIN_POLICY_SOURCE_FIXTURE_PATH
        ).read_bytes()

        self.assertEqual(
            hashlib.sha256(fixture).hexdigest(),
            version_port_merge.MIXIN_POLICY_SHA256,
        )
        self.assertEqual(
            version_port_merge._migrate_mixin_hand_policy_payload(fixture),
            fixture,
        )

    def test_modern_neoforge_collector_is_replaced_by_audited_fixture(self) -> None:
        source, target, _, result_payload = (
            self.prepare_neoforge_collector_fixture_branches(
                runtime_version="1.21.11"
            )
        )

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            target,
            source,
            mode="prepare",
        )

        self.assertEqual(evidence["ai_conflicts"], [])
        migrated = [
            item
            for item in evidence["protected_resolutions"]
            if item["policy"] == "migrate-neoforge-modern-hand-collector"
        ]
        self.assertEqual(len(migrated), 1)
        self.assertEqual(
            migrated[0]["path"], version_port_merge.NEOFORGE_PLAYER_RENDERER_PATH
        )
        self.assertEqual(
            migrated[0]["source_path"],
            version_port_merge.NEOFORGE_PLAYER_RENDERER_RESULT_PATH,
        )
        self.assertEqual(
            (
                self.repository / version_port_merge.NEOFORGE_PLAYER_RENDERER_PATH
            ).read_text(encoding="utf-8"),
            result_payload,
        )
        self.git("merge", "--abort")
        self.assert_clean_at(target)

    def test_1_21_9_neoforge_collector_is_replaced_by_audited_fixture(
        self,
    ) -> None:
        source, target, _, result_payload = (
            self.prepare_neoforge_collector_fixture_branches(
                runtime_version="1.21.10"
            )
        )

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            target,
            source,
            mode="prepare",
        )

        migrated = [
            item
            for item in evidence["protected_resolutions"]
            if item["policy"] == "migrate-neoforge-modern-hand-collector"
        ]
        self.assertEqual(len(migrated), 1)
        self.assertEqual(
            migrated[0]["source_path"],
            version_port_merge.NEOFORGE_PLAYER_RENDERER_1_21_9_RESULT_PATH,
        )
        self.assertEqual(
            (
                self.repository / version_port_merge.NEOFORGE_PLAYER_RENDERER_PATH
            ).read_text(encoding="utf-8"),
            result_payload,
        )
        self.git("merge", "--abort")
        self.assert_clean_at(target)

    def test_neoforge_collector_migration_preserves_pre_1_21_9_redirect(
        self,
    ) -> None:
        source, target, input_payload, _ = (
            self.prepare_neoforge_collector_fixture_branches(
                runtime_version="1.21.8"
            )
        )

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            target,
            source,
            mode="prepare",
        )

        self.assertEqual(evidence["protected_resolutions"], [])
        self.assertEqual(
            (
                self.repository / version_port_merge.NEOFORGE_PLAYER_RENDERER_PATH
            ).read_text(encoding="utf-8"),
            input_payload,
        )
        self.git("merge", "--abort")
        self.assert_clean_at(target)

    def test_unknown_modern_neoforge_collector_fails_closed(self) -> None:
        input_payload = (
            ROOT / version_port_merge.NEOFORGE_PLAYER_RENDERER_INPUT_PATH
        ).read_text(encoding="utf-8")
        source, target, _, _ = self.prepare_neoforge_collector_fixture_branches(
            runtime_version="26.1.2",
            target_payload=input_payload.replace(
                "NeoForge-specific mixin",
                "Unexpected NeoForge mixin",
                1,
            ),
        )

        with self.assertRaisesRegex(
            version_port_merge.VersionPortMergeError,
            "does not match the audited migration input",
        ):
            version_port_merge.reproduce_merge(
                self.repository,
                target,
                source,
                mode="prepare",
            )
        self.assert_clean_at(target)

    def test_renamed_datapack_layout_is_migrated_with_versioned_game_rules(
        self,
    ) -> None:
        plural_load = (
            "e2e/server-template/datapack/data/qs_e2e/functions/load.mcfunction"
        )
        singular_load = (
            "e2e/server-template/datapack/data/qs_e2e/function/load.mcfunction"
        )
        plural_tick = (
            "e2e/server-template/datapack/data/qs_e2e/functions/tick.mcfunction"
        )
        singular_tick = (
            "e2e/server-template/datapack/data/qs_e2e/function/tick.mcfunction"
        )
        plural_load_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/functions/load.json"
        )
        singular_load_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/function/load.json"
        )
        plural_tick_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/functions/tick.json"
        )
        singular_tick_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/function/tick.json"
        )
        base_load = (
            "# Starts screenshots in clear daylight.\n"
            "weather clear\n"
            "time set day\n"
        )
        source_load = (
            "# Starts screenshots in a fixed location and daylight.\n"
            "weather clear\n"
            "gamerule doWeatherCycle false\n"
            "gamerule doDaylightCycle false\n"
            "gamerule doMobSpawning false\n"
            "gamerule spawnRadius 0\n"
            "team add qs_e2e\n"
            "team modify qs_e2e collisionRule never\n"
            "time set day\n"
        )
        target_load = (
            "# Uses the singular function directory.\n"
            "# Cross-version placeholder before deterministic migration.\n"
            "weather clear\n"
            "time set day\n"
        )
        load_tag = '{"values":["qs_e2e:load"]}\n'
        tick_function = (
            "team join qs_e2e @a[team=!qs_e2e]\n"
            "execute as @e[type=!minecraft:player,tag=!qs_e2e_keep] at @s "
            "run tp @s ~ -1024 ~\n"
        )
        tick_tag = '{"values":["qs_e2e:tick"]}\n'

        self.git("switch", "--create", "datapack-base", self.base)
        self.write(plural_load, base_load)
        self.write(plural_load_tag, load_tag)
        self.git("add", "--all")
        self.commit("add base datapack")
        datapack_base = self.sha("HEAD")

        self.git("switch", "--create", "datapack-source", datapack_base)
        self.write(plural_load, source_load)
        self.write(plural_tick, tick_function)
        self.write(plural_tick_tag, tick_tag)
        self.git("add", "--all")
        self.commit("make source datapack deterministic")
        datapack_source = self.sha("HEAD")

        cases = (
            (
                "1.21.10",
                (
                    "gamerule doWeatherCycle false",
                    "gamerule doDaylightCycle false",
                    "gamerule doMobSpawning false",
                    "gamerule spawnRadius 0",
                ),
            ),
            (
                "1.21.11",
                (
                    "gamerule minecraft:advance_weather false",
                    "gamerule minecraft:advance_time false",
                    "gamerule minecraft:spawn_mobs false",
                    "gamerule minecraft:respawn_radius 0",
                ),
            ),
            (
                "26.1",
                (
                    "gamerule minecraft:advance_weather false",
                    "gamerule minecraft:advance_time false",
                    "gamerule minecraft:spawn_mobs false",
                    "gamerule minecraft:respawn_radius 0",
                ),
            ),
        )
        for runtime_version, expected_rules in cases:
            with self.subTest(runtime_version=runtime_version):
                branch_suffix = runtime_version.replace(".", "-")
                self.git(
                    "switch",
                    "--create",
                    f"datapack-target-{branch_suffix}",
                    datapack_base,
                )
                self.git("rm", plural_load, plural_load_tag)
                self.write(singular_load, target_load)
                self.write(singular_load_tag, load_tag)
                self.write(
                    "release/release-matrix.json",
                    self.matrix(
                        f"target {runtime_version}",
                        runtime_version=runtime_version,
                    ),
                )
                self.git("add", "--all")
                self.commit(f"rename datapack for {runtime_version}")
                datapack_target = self.sha("HEAD")

                evidence = version_port_merge.reproduce_merge(
                    self.repository,
                    datapack_target,
                    datapack_source,
                    mode="prepare",
                )
                self.assertEqual(evidence["ai_conflicts"], [])
                self.assertIn(plural_load, evidence["conflicts"])
                self.assertIn(
                    "clear-renamed-datapack-conflict",
                    [item["policy"] for item in evidence["protected_resolutions"]],
                )
                migrated = [
                    item
                    for item in evidence["protected_resolutions"]
                    if item["policy"] == "migrate-datapack-function-layout"
                ]
                self.assertEqual(len(migrated), 4)
                self.assertEqual(
                    (self.repository / singular_load).read_text(encoding="utf-8"),
                    source_load
                    if runtime_version == "1.21.10"
                    else source_load
                    .replace(
                        "gamerule doWeatherCycle false",
                        "gamerule minecraft:advance_weather false",
                    )
                    .replace(
                        "gamerule doDaylightCycle false",
                        "gamerule minecraft:advance_time false",
                    )
                    .replace(
                        "gamerule doMobSpawning false",
                        "gamerule minecraft:spawn_mobs false",
                    )
                    .replace(
                        "gamerule spawnRadius 0",
                        "gamerule minecraft:respawn_radius 0",
                    ),
                )
                for rule in expected_rules:
                    self.assertIn(
                        rule,
                        (self.repository / singular_load).read_text(encoding="utf-8"),
                    )
                self.assertEqual(
                    (self.repository / singular_tick).read_text(encoding="utf-8"),
                    tick_function,
                )
                self.assertEqual(
                    (self.repository / singular_tick_tag).read_text(encoding="utf-8"),
                    tick_tag,
                )
                self.assertEqual(
                    (self.repository / singular_load_tag).read_text(encoding="utf-8"),
                    load_tag,
                )
                for old_path in (
                    plural_load,
                    plural_tick,
                    plural_load_tag,
                    plural_tick_tag,
                ):
                    self.assertFalse((self.repository / old_path).exists())
                self.git("merge", "--abort")
                self.assert_clean_at(datapack_target)

    def test_renamed_datapack_load_conflict_at_singular_path_is_migrated(
        self,
    ) -> None:
        plural_load = (
            "e2e/server-template/datapack/data/qs_e2e/functions/load.mcfunction"
        )
        singular_load = (
            "e2e/server-template/datapack/data/qs_e2e/function/load.mcfunction"
        )
        plural_tick = (
            "e2e/server-template/datapack/data/qs_e2e/functions/tick.mcfunction"
        )
        singular_tick = (
            "e2e/server-template/datapack/data/qs_e2e/function/tick.mcfunction"
        )
        plural_load_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/functions/load.json"
        )
        singular_load_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/function/load.json"
        )
        plural_tick_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/functions/tick.json"
        )
        singular_tick_tag = (
            "e2e/server-template/datapack/data/minecraft/tags/function/tick.json"
        )
        base_load = (
            "# Runs once on world load (minecraft:load tag). Starts screenshots in a "
            "fixed location and daylight.\n"
            "weather clear\n"
            "gamerule doWeatherCycle false\n"
            "gamerule doDaylightCycle false\n"
            "gamerule spawnRadius 0\n"
            "team add qs_e2e\n"
            "team modify qs_e2e collisionRule never\n"
            "time set day\n"
        )
        source_load = base_load.replace(
            "gamerule spawnRadius 0\n",
            "gamerule doMobSpawning false\n"
            "gamerule spawnRadius 0\n",
        )
        target_load = (
            base_load.replace(
                "gamerule doWeatherCycle false",
                "gamerule minecraft:advance_weather false",
            )
            .replace(
                "gamerule doDaylightCycle false",
                "gamerule minecraft:advance_time false",
            )
            .replace(
                "gamerule spawnRadius 0",
                "gamerule minecraft:respawn_radius 0",
            )
        )
        load_tag = '{"values":["qs_e2e:load"]}\n'
        tick_function = "team join qs_e2e @a[team=!qs_e2e]\n"
        tick_tag = '{"values":["qs_e2e:tick"]}\n'

        self.git("switch", "--create", "singular-conflict-base", self.base)
        self.write(plural_load, base_load)
        self.write(plural_load_tag, load_tag)
        self.git("add", "--all")
        self.commit("add singular-conflict datapack base")
        datapack_base = self.sha("HEAD")

        self.git("switch", "--create", "singular-conflict-source", datapack_base)
        self.write(plural_load, source_load)
        self.write(plural_tick, tick_function)
        self.write(plural_tick_tag, tick_tag)
        self.git("add", "--all")
        self.commit("make singular-conflict source deterministic")
        datapack_source = self.sha("HEAD")

        self.git("switch", "--create", "singular-conflict-target", datapack_base)
        self.git("rm", plural_load, plural_load_tag)
        self.write(singular_load, target_load)
        self.write(singular_load_tag, load_tag)
        self.write(
            "release/release-matrix.json",
            self.matrix("target 1.21.11", runtime_version="1.21.11"),
        )
        self.git("add", "--all")
        self.commit("rename singular-conflict target datapack")
        datapack_target = self.sha("HEAD")

        evidence = version_port_merge.reproduce_merge(
            self.repository,
            datapack_target,
            datapack_source,
            mode="prepare",
        )

        self.assertEqual(evidence["ai_conflicts"], [])
        self.assertIn(singular_load, evidence["conflicts"])
        singular_resolutions = [
            item
            for item in evidence["protected_resolutions"]
            if item["path"] == singular_load
        ]
        self.assertEqual(
            [item["policy"] for item in singular_resolutions],
            [
                "clear-renamed-datapack-conflict",
                "migrate-datapack-function-layout",
            ],
        )
        self.assertIsNone(singular_resolutions[0]["result"])
        migrated_load = singular_resolutions[1]
        self.assertEqual(migrated_load["source_path"], plural_load)
        self.assertEqual(
            (self.repository / singular_load).read_text(encoding="utf-8"),
            source_load.replace(
                "gamerule doWeatherCycle false",
                "gamerule minecraft:advance_weather false",
            )
            .replace(
                "gamerule doDaylightCycle false",
                "gamerule minecraft:advance_time false",
            )
            .replace(
                "gamerule doMobSpawning false",
                "gamerule minecraft:spawn_mobs false",
            )
            .replace(
                "gamerule spawnRadius 0",
                "gamerule minecraft:respawn_radius 0",
            ),
        )
        self.assertEqual(
            (self.repository / singular_tick).read_text(encoding="utf-8"),
            tick_function,
        )
        self.assertEqual(
            (self.repository / singular_tick_tag).read_text(encoding="utf-8"),
            tick_tag,
        )
        self.git("merge", "--abort")
        self.assert_clean_at(datapack_target)

    def test_source_ancestor_is_rejected_without_leaving_merge_state(self) -> None:
        with self.assertRaisesRegex(
            version_port_merge.VersionPortMergeError,
            "already an ancestor",
        ):
            version_port_merge.reproduce_merge(
                self.repository,
                self.target,
                self.base,
                mode="prepare",
            )
        self.assert_clean_target()

    def test_source_preference_resolves_a_real_overlap_and_preserves_both_hunks(
        self,
    ) -> None:
        evidence = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="prepare",
        )
        self.assertEqual(
            self.unmerged_paths(),
            ("src/Conflict.txt", "src/[literal]*?.txt"),
        )
        readme = (self.repository / "e2e/README.md").read_text(encoding="utf-8")
        self.assertIn("target-only hunk\n", readme)
        self.assertIn("source conflict\n", readme)
        self.assertIn("source-only hunk\n", readme)
        self.assertNotIn("target conflict\n", readme)
        self.assertNotIn("<<<<<<<", readme)
        self.assertEqual(
            json.loads(
                (self.repository / "release/release-matrix.json").read_text(
                    encoding="utf-8"
                )
            )["description"],
            "target",
        )
        self.assertFalse((self.repository / "forge/build.gradle.kts").exists())
        self.assertFalse(
            (
                self.repository
                / "common/src/legacy1_20_1/resources/quickskin-ears.mixins.json"
            ).exists()
        )
        self.assertEqual(
            (self.repository / "safe.txt").read_text(encoding="utf-8"),
            "source safe\n",
        )
        self.assertEqual(
            evidence["ai_conflicts"],
            ["src/Conflict.txt", "src/[literal]*?.txt"],
        )
        source_resolution = next(
            item
            for item in evidence["protected_resolutions"]
            if item["path"] == "e2e/README.md"
        )
        input_oids = {
            source_resolution["stages"][name]["oid"]
            for name in ("base", "target", "source")
        }
        self.assertEqual(len(input_oids), 3)
        self.assertNotIn(source_resolution["result"]["oid"], input_oids)
        self.git("merge", "--abort")
        self.assert_clean_target()

    def test_candidate_index_resolves_only_ai_paths_and_keeps_same_evidence(self) -> None:
        probe = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="probe",
        )
        candidate = self.make_candidate_index(
            ai_content=b"candidate choice\n",
            protected_content=b"malicious protected replacement\n",
        )
        prepared = version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="prepare",
            candidate_index=candidate,
            candidate_tree=self.index_tree(candidate),
        )
        self.assertEqual(prepared, probe)
        self.assertEqual(self.unmerged_paths(), ())
        self.assertEqual(
            (self.repository / "src/Conflict.txt").read_text(encoding="utf-8"),
            "candidate choice\n",
        )
        readme = (self.repository / "e2e/README.md").read_text(encoding="utf-8")
        self.assertIn("source conflict\n", readme)
        self.assertNotIn("malicious protected replacement", readme)
        self.git("merge", "--abort")
        self.assert_clean_target()

    def test_candidate_index_may_delete_an_ai_conflict(self) -> None:
        candidate = self.make_candidate_index(ai_content=None)
        version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="prepare",
            candidate_index=candidate,
            candidate_tree=self.index_tree(candidate),
        )
        self.assertEqual(self.unmerged_paths(), ())
        self.assertFalse((self.repository / "src/Conflict.txt").exists())
        self.git("merge", "--abort")
        self.assert_clean_target()

    def test_metacharacter_ai_path_is_deleted_literally(self) -> None:
        candidate = self.make_candidate_index(
            ai_content=b"candidate choice\n",
            metachar_content=None,
        )
        version_port_merge.reproduce_merge(
            self.repository,
            self.target,
            self.source,
            mode="prepare",
            candidate_index=candidate,
            candidate_tree=self.index_tree(candidate),
        )
        self.assertEqual(self.unmerged_paths(), ())
        self.assertFalse((self.repository / "src/[literal]*?.txt").exists())
        self.assertEqual(
            (self.repository / "src/literal-decoy.txt").read_text(encoding="utf-8"),
            "decoy must survive\n",
        )
        self.git("merge", "--abort")
        self.assert_clean_target()

    def test_candidate_index_symlink_is_rejected_before_merge(self) -> None:
        candidate = self.make_candidate_index(ai_content=b"candidate choice\n")
        candidate_tree = self.index_tree(candidate)
        candidate_link = self.root / "candidate-link.index"
        candidate_link.symlink_to(candidate)
        with self.assertRaisesRegex(
            version_port_merge.VersionPortMergeError,
            "non-symlink",
        ):
            version_port_merge.reproduce_merge(
                self.repository,
                self.target,
                self.source,
                mode="prepare",
                candidate_index=candidate_link,
                candidate_tree=candidate_tree,
            )
        self.assert_clean_target()

    def test_candidate_tree_rejects_extra_and_missing_entries(self) -> None:
        for mutation in ("extra", "missing"):
            with self.subTest(mutation=mutation):
                candidate = self.make_candidate_index(ai_content=b"candidate choice\n")
                expected_tree = self.index_tree(candidate)
                environment = {"GIT_INDEX_FILE": str(candidate)}
                if mutation == "extra":
                    oid = self.git(
                        "hash-object",
                        "-w",
                        "--stdin",
                        input_bytes=b"unexpected candidate entry\n",
                    ).decode("ascii").strip()
                    self.git(
                        "update-index",
                        "--add",
                        "--cacheinfo",
                        "100644",
                        oid,
                        "unexpected-candidate.txt",
                        environment=environment,
                    )
                else:
                    self.git(
                        "update-index",
                        "--force-remove",
                        "--",
                        "safe.txt",
                        environment=environment,
                    )
                with self.assertRaisesRegex(
                    version_port_merge.VersionPortMergeError,
                    "does not equal",
                ):
                    version_port_merge.reproduce_merge(
                        self.repository,
                        self.target,
                        self.source,
                        mode="prepare",
                        candidate_index=candidate,
                        candidate_tree=expected_tree,
                    )
                self.assert_clean_target()

    def test_protected_conflict_failures_restore_the_exact_target(self) -> None:
        with self.subTest(policy="active loader"):
            self.git("switch", "--create", "active-loader-target", self.target)
            self.write(
                "release/release-matrix.json",
                self.matrix("active forge", ("fabric", "forge")),
            )
            self.git("add", "--all")
            self.commit("activate forge in target")
            active_target = self.sha("HEAD")
            with self.assertRaisesRegex(
                version_port_merge.VersionPortMergeError,
                "active-loader",
            ):
                version_port_merge.reproduce_merge(
                    self.repository,
                    active_target,
                    self.source,
                    mode="probe",
                )
            self.assert_clean_at(active_target)

        self.git("switch", "target")
        self.git("switch", "--create", "unknown-protected-source", self.source)
        self.write("docs/ai/PROJECT.md", "source protected conflict\n")
        self.git("add", "--all")
        self.commit("add source protected path")
        protected_source = self.sha("HEAD")
        self.git("switch", "--create", "unknown-protected-target", self.target)
        self.write("docs/ai/PROJECT.md", "target protected conflict\n")
        self.git("add", "--all")
        self.commit("add target protected path")
        protected_target = self.sha("HEAD")
        with self.subTest(policy="unknown protected"):
            with self.assertRaisesRegex(
                version_port_merge.VersionPortMergeError,
                "unknown protected",
            ):
                version_port_merge.reproduce_merge(
                    self.repository,
                    protected_target,
                    protected_source,
                    mode="prepare",
                )
            self.assert_clean_at(protected_target)

    def test_unsafe_candidate_fails_closed_and_restores_checkout(self) -> None:
        cases = (
            (b"<<<<<<< candidate\n=======\n>>>>>>> source\n", "100644"),
            (b"candidate symlink target\n", "120000"),
            (b"\xff\n", "100644"),
        )
        for content, mode in cases:
            with self.subTest(mode=mode, content=content):
                candidate = self.make_candidate_index(ai_content=content, mode=mode)
                with self.assertRaises(version_port_merge.VersionPortMergeError):
                    version_port_merge.reproduce_merge(
                        self.repository,
                        self.target,
                        self.source,
                        mode="prepare",
                        candidate_index=candidate,
                        candidate_tree=self.index_tree(candidate),
                    )
                self.assert_clean_target()


if __name__ == "__main__":
    unittest.main()
