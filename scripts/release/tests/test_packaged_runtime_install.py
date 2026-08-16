from __future__ import annotations

import hashlib
import json
import os
import sys
import tempfile
import types
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import packaged_runtime  # noqa: E402
import runtime_store  # noqa: E402


class PackagedRuntimeClientInstallTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = runtime_store.RuntimeStore(self.root / "cache")
        self.session = packaged_runtime.PackagedRuntimeSession(
            self.store, self.root / "scratch"
        )
        self.installer = self.root / "neoforge-installer.jar"
        self.installer.write_bytes(b"verified installer")
        self.installer_sha256 = hashlib.sha256(self.installer.read_bytes()).hexdigest()
        self.row: dict[str, object] = {
            "loader": "neoforge",
            "runtime_version": "1.21.4",
            "loader_version": "21.4.156",
            "installer": "neoforge-21.4.156",
            "java": 21,
        }
        self.matrix = {
            "installers": {
                "neoforge-21.4.156": {
                    "url": "https://example.invalid/neoforge-installer.jar",
                    "sha256": self.installer_sha256,
                }
            }
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def patched_launcher(self, install_minecraft_version: object) -> ExitStack:
        package = types.ModuleType("minecraft_launcher_lib")
        package.__path__ = []  # type: ignore[attr-defined]
        install = types.ModuleType("minecraft_launcher_lib.install")
        install.install_minecraft_version = install_minecraft_version  # type: ignore[attr-defined]
        package.install = install  # type: ignore[attr-defined]

        stack = ExitStack()
        stack.enter_context(
            mock.patch.dict(
                sys.modules,
                {
                    "minecraft_launcher_lib": package,
                    "minecraft_launcher_lib.install": install,
                },
            )
        )
        stack.enter_context(
            mock.patch.object(
                packaged_runtime.importlib.metadata,
                "version",
                return_value=packaged_runtime.LAUNCHER_LIBRARY_VERSION,
            )
        )
        stack.enter_context(
            mock.patch.object(
                packaged_runtime,
                "fetch_verified_blob",
                side_effect=self.fetch_installer,
            )
        )
        return stack

    def fetch_installer(
        self,
        store: runtime_store.RuntimeStore,
        *,
        url: str,
        filename: str,
        expected_sha256: str,
    ) -> Path:
        self.assertEqual("https://example.invalid/neoforge-installer.jar", url)
        self.assertEqual("neoforge-installer.jar", filename)
        self.assertEqual(self.installer_sha256, expected_sha256)
        return store.admit_blob(self.installer, expected_sha256)

    @staticmethod
    def write_loader_profile(staging: Path, version_id: str) -> Path:
        version_json = staging / "versions" / version_id / f"{version_id}.json"
        version_json.parent.mkdir(parents=True, exist_ok=True)
        version_json.write_text(
            json.dumps({"inheritsFrom": "1.21.4", "jar": version_id}),
            encoding="utf-8",
        )
        return version_json

    def recipe(self) -> runtime_store.RuntimeRecipe:
        with mock.patch.object(
            packaged_runtime.importlib.metadata,
            "version",
            return_value=packaged_runtime.LAUNCHER_LIBRARY_VERSION,
        ):
            return packaged_runtime.client_runtime_recipe(self.matrix, self.row)

    def test_partial_attempt_is_discarded_before_one_complete_tree_is_published(self) -> None:
        version_id = packaged_runtime.installed_version_id(self.row)
        attempts: list[Path] = []

        def install_vanilla(_version: str, target: str) -> None:
            attempts.append(Path(target))
            self.assertFalse(self.session.install_destination(self.recipe()).exists())

        def run_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)
            if len(attempts) == 1:
                raise packaged_runtime.RuntimeFailure("Read timed out")

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run_installer
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            directory, actual_version_id = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.session, "/fake/java"
            )

        self.assertEqual(version_id, actual_version_id)
        self.assertEqual(self.session.install_destination(self.recipe()), directory)
        self.assertEqual(2, checked.call_count)
        self.assertEqual(2, len(attempts))
        self.assertNotEqual(attempts[0], attempts[1])
        self.assertTrue(all(not attempt.exists() for attempt in attempts))
        version_json = directory / "versions" / version_id / f"{version_id}.json"
        self.assertNotIn("jar", json.loads(version_json.read_text(encoding="utf-8")))
        self.assertFalse(hasattr(packaged_runtime, "CLIENT_INSTALL_MARKER"))
        sleep.assert_called_once_with(5)
        self.assertEqual(1, self.store.metrics.misses)

    def test_all_attempts_fail_without_a_recipe_or_materialized_partial_tree(self) -> None:
        version_id = packaged_runtime.installed_version_id(self.row)
        attempts: list[Path] = []

        def install_vanilla(_version: str, target: str) -> None:
            attempts.append(Path(target))

        def fail_after_partial_profile(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)
            raise packaged_runtime.RuntimeFailure("Read timed out")

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=fail_after_partial_profile
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure,
                "client installation failed after 3 attempt.*Read timed out",
            ):
                packaged_runtime.prepare_client_install(
                    self.matrix, self.row, self.session, "/fake/java"
                )

        recipe = self.recipe()
        self.assertEqual(3, checked.call_count)
        self.assertTrue(all(not attempt.exists() for attempt in attempts))
        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        self.assertFalse(self.session.install_destination(recipe).exists())
        self.assertEqual([mock.call(5), mock.call(15)], sleep.call_args_list)

    def test_one_session_materializes_once_and_a_new_session_gets_a_store_hit(self) -> None:
        version_id = packaged_runtime.installed_version_id(self.row)

        def install_vanilla(_version: str, _target: str) -> None:
            return

        def run_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)

        with self.patched_launcher(install_vanilla), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run_installer
        ) as checked, mock.patch.object(
            self.store, "materialize", wraps=self.store.materialize
        ) as materialize:
            first = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.session, "/fake/java"
            )
            second = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.session, "/fake/java"
            )

        self.assertEqual(first, second)
        checked.assert_called_once()
        materialize.assert_called_once()
        self.assertEqual(1, self.store.metrics.misses)

        second_session = packaged_runtime.PackagedRuntimeSession(
            self.store, self.root / "second-scratch"
        )
        install_again = mock.Mock()
        with self.patched_launcher(install_again), mock.patch.object(
            packaged_runtime, "run_checked"
        ) as checked_again:
            reused = packaged_runtime.prepare_client_install(
                self.matrix, self.row, second_session, "/fake/java"
            )
        install_again.assert_not_called()
        checked_again.assert_not_called()
        self.assertNotEqual(first[0], reused[0])
        self.assertEqual(
            (first[0] / "versions" / version_id / f"{version_id}.json").read_bytes(),
            (reused[0] / "versions" / version_id / f"{version_id}.json").read_bytes(),
        )
        self.assertEqual(1, self.store.metrics.hits)

    def test_client_recipe_stays_leased_until_materialization_finishes(self) -> None:
        version_id = packaged_runtime.installed_version_id(self.row)
        competing_store = runtime_store.RuntimeStore(self.root / "cache")
        original_materialize = self.store.materialize
        gc_results: list[runtime_store.GcResult] = []

        def run_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)

        def materialize_during_gc(
            stored: runtime_store.StoredRuntime, destination: Path
        ) -> Path:
            gc_results.append(competing_store.gc(max_bytes=0))
            return original_materialize(stored, destination)

        with self.patched_launcher(mock.Mock()), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run_installer
        ), mock.patch.object(
            self.store, "materialize", side_effect=materialize_during_gc
        ):
            directory, actual_version = packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.session, "/fake/java"
            )

        self.assertEqual(version_id, actual_version)
        self.assertTrue(
            (directory / "versions" / version_id / f"{version_id}.json").is_file()
        )
        self.assertEqual(1, len(gc_results))
        self.assertEqual(0, gc_results[0].pruned)
        self.assertEqual(0, gc_results[0].pruned_blobs)
        after_release = competing_store.gc(max_bytes=0)
        self.assertEqual(1, after_release.pruned)
        self.assertGreaterEqual(after_release.pruned_blobs, 1)

    def test_corrupt_store_content_is_a_miss_and_rebuilt_without_manual_cache_delete(self) -> None:
        version_id = packaged_runtime.installed_version_id(self.row)

        def successful_installer(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            self.write_loader_profile(cwd, version_id)

        with self.patched_launcher(mock.Mock()), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=successful_installer
        ):
            packaged_runtime.prepare_client_install(
                self.matrix, self.row, self.session, "/fake/java"
            )

        recipe = self.recipe()
        stored = self.store.validate(recipe)
        profile_entry = next(
            entry for entry in stored.manifest.entries if entry.path.endswith(f"/{version_id}.json")
        )
        blob = self.store.path_for_blob(profile_entry.sha256)
        os.chmod(blob, 0o640)
        blob.write_bytes(b"corrupt")

        second_session = packaged_runtime.PackagedRuntimeSession(
            self.store, self.root / "repair-scratch"
        )
        with self.patched_launcher(mock.Mock()), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=successful_installer
        ) as rebuilt:
            directory, _version = packaged_runtime.prepare_client_install(
                self.matrix, self.row, second_session, "/fake/java"
            )

        rebuilt.assert_called_once()
        self.store.validate(recipe)
        self.assertTrue((directory / "versions" / version_id / f"{version_id}.json").is_file())
        self.assertGreaterEqual(self.store.metrics.misses, 2)

    def test_recipe_invalidates_for_every_runtime_and_tool_revision(self) -> None:
        with (
            mock.patch.object(
                packaged_runtime.importlib.metadata,
                "version",
                return_value=packaged_runtime.LAUNCHER_LIBRARY_VERSION,
            ),
            mock.patch.object(runtime_store.platform, "system", return_value="Linux"),
            mock.patch.object(runtime_store.platform, "machine", return_value="x86_64"),
        ):
            base = packaged_runtime.client_runtime_recipe(self.matrix, self.row)
            changed_java = packaged_runtime.client_runtime_recipe(
                self.matrix, {**self.row, "java": 17}
            )
            changed_minecraft = packaged_runtime.client_runtime_recipe(
                self.matrix, {**self.row, "runtime_version": "1.21.5"}
            )
            changed_loader = packaged_runtime.client_runtime_recipe(
                self.matrix, {**self.row, "loader_version": "21.4.157"}
            )
            with mock.patch.object(
                packaged_runtime,
                "PROFILE_NORMALIZER_REVISION",
                "normalize-inherited-profile-v2",
            ):
                changed_normalizer = packaged_runtime.client_runtime_recipe(
                    self.matrix, self.row
                )
            with mock.patch.object(
                packaged_runtime,
                "LAUNCHER_LIBRARY_REVISION",
                "minecraft-launcher-lib==8.0+patched",
            ):
                changed_launcher = packaged_runtime.client_runtime_recipe(
                    self.matrix, self.row
                )
        with (
            mock.patch.object(
                packaged_runtime.importlib.metadata,
                "version",
                return_value=packaged_runtime.LAUNCHER_LIBRARY_VERSION,
            ),
            mock.patch.object(runtime_store.platform, "system", return_value="Windows"),
            mock.patch.object(runtime_store.platform, "machine", return_value="aarch64"),
        ):
            changed_host = packaged_runtime.client_runtime_recipe(self.matrix, self.row)

        digests = {
            recipe.digest()
            for recipe in (
                base,
                changed_java,
                changed_minecraft,
                changed_loader,
                changed_normalizer,
                changed_launcher,
                changed_host,
            )
        }
        self.assertEqual(7, len(digests))
        self.assertEqual("minecraft-launcher-lib==8.0", base.launcher_library_revision)
        self.assertEqual("normalize-inherited-profile-v1", base.normalizer_revision)

    def test_launcher_library_version_is_fail_closed(self) -> None:
        with mock.patch.object(
            packaged_runtime.importlib.metadata, "version", return_value="8.1"
        ):
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "exactly 8.0"):
                packaged_runtime.client_runtime_recipe(self.matrix, self.row)

    def test_client_uses_the_servers_standard_offline_uuid(self) -> None:
        self.assertEqual(
            "10920508d5d83eed93d292f193afe7d7",
            packaged_runtime.offline_player_uuid("Alice"),
        )
        self.assertEqual(
            "faa5dca3c3d4354bae1bdde9e5a14b3b",
            packaged_runtime.offline_player_uuid("Bob"),
        )
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "no locked offline UUID for E2E username 'Mallory'",
        ):
            packaged_runtime.offline_player_uuid("Mallory")

        package = types.ModuleType("minecraft_launcher_lib")
        package.__path__ = []  # type: ignore[attr-defined]
        command = types.ModuleType("minecraft_launcher_lib.command")
        utils = types.ModuleType("minecraft_launcher_lib.utils")
        captured: dict[str, object] = {}

        def get_minecraft_command(
            version_id: str, install_dir: str, options: dict[str, object]
        ) -> list[str]:
            captured.update(
                version_id=version_id,
                install_dir=install_dir,
                options=options,
            )
            return ["java", "minecraft"]

        command.get_minecraft_command = get_minecraft_command  # type: ignore[attr-defined]
        utils.generate_test_options = lambda: {}  # type: ignore[attr-defined]
        package.command = command  # type: ignore[attr-defined]
        package.utils = utils  # type: ignore[attr-defined]

        with mock.patch.dict(
            sys.modules,
            {
                "minecraft_launcher_lib": package,
                "minecraft_launcher_lib.command": command,
                "minecraft_launcher_lib.utils": utils,
            },
        ):
            launched = packaged_runtime.client_command(
                self.root / "install",
                "fabric-loader-1.21.10",
                self.root / "game",
                {"runtime_version": "1.21.10"},
                "propagation",
                "client_a",
                "Alice",
                25565,
                "/fake/java",
            )
            clean_options = dict(captured["options"])  # type: ignore[arg-type]
            compatibility_launched = packaged_runtime.client_command(
                self.root / "install",
                "fabric-loader-1.21.10",
                self.root / "compatibility-game",
                {"runtime_version": "1.21.10"},
                "mod-compatibility",
                "client_a",
                "Alice",
                25565,
                "/fake/java",
                compatibility_mod="ears",
            )
            compatibility_options = dict(captured["options"])  # type: ignore[arg-type]
            compatibility_version_id = captured["version_id"]
            replaymod_launched = packaged_runtime.client_command(
                self.root / "install",
                "fabric-loader-1.20.1",
                self.root / "replaymod-game",
                {"runtime_version": "1.20.1"},
                "mod-compatibility",
                "client_a",
                "Alice",
                25566,
                "/fake/java",
                compatibility_mod="replaymod",
            )
            replaymod_options = dict(captured["options"])  # type: ignore[arg-type]

        self.assertEqual(["java", "minecraft"], launched)
        self.assertEqual("fabric-loader-1.21.10", compatibility_version_id)
        options = captured["options"]
        self.assertIsInstance(options, dict)
        assert isinstance(options, dict)
        self.assertEqual("Alice", options["username"])
        self.assertEqual(
            "10920508d5d83eed93d292f193afe7d7",
            options["uuid"],
        )
        self.assertIn("-Dmixin.debug.countInjections=true", clean_options["jvmArguments"])
        self.assertEqual(["java", "minecraft"], compatibility_launched)
        self.assertNotIn(
            "-Dmixin.debug.countInjections=true",
            compatibility_options["jvmArguments"],
        )
        self.assertIn(
            "-Dquickskin.e2e.compatibility=ears",
            compatibility_options["jvmArguments"],
        )
        self.assertEqual(["java", "minecraft"], replaymod_launched)
        self.assertEqual("127.0.0.1:25566", replaymod_options["quickPlayMultiplayer"])
        self.assertIn(
            "-Dquickskin.e2e.repairMissingConnectionRead=true",
            replaymod_options["jvmArguments"],
        )
        self.assertNotIn(
            "-Dquickskin.e2e.repairMissingConnectionRead=true",
            compatibility_options["jvmArguments"],
        )
        self.assertEqual(
            "127.0.0.1:25565", compatibility_options["quickPlayMultiplayer"]
        )

    def test_e2e_client_config_disables_ambient_own_skin_import(self) -> None:
        game_dir = self.root / "game"

        config_path = packaged_runtime.write_e2e_client_config(game_dir)

        self.assertEqual(
            game_dir / "config" / "quickskin-client.json",
            config_path,
        )
        self.assertEqual(
            {
                "activeCapeHash": "",
                "activeCpmModelHash": "",
                "activeSkinHash": "",
                "enablePlayerOwnSkinSystem": False,
                "playerOwnSkinHash": "",
            },
            json.loads(config_path.read_text(encoding="utf-8")),
        )

    def test_replaymod_compatibility_config_disables_unrelated_recording(self) -> None:
        game_dir = self.root / "replaymod-game"

        config_path = packaged_runtime.write_compatibility_client_config(
            game_dir, "replaymod"
        )

        self.assertEqual(game_dir / "config" / "replaymod.json", config_path)
        assert config_path is not None
        self.assertEqual(
            {"recording": {"recordServer": False}},
            json.loads(config_path.read_text(encoding="utf-8")),
        )
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure,
            "compatibility client config must start absent",
        ):
            packaged_runtime.write_compatibility_client_config(game_dir, "replaymod")

    def test_other_compatibility_mods_do_not_receive_foreign_config(self) -> None:
        game_dir = self.root / "ears-game"

        config_path = packaged_runtime.write_compatibility_client_config(
            game_dir, "ears"
        )

        self.assertIsNone(config_path)
        self.assertFalse(game_dir.exists())


class PackagedRuntimeDependencyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.store = runtime_store.RuntimeStore(self.root / "cache")
        self.api_bytes = b"fabric api exact bytes"
        self.arch_bytes = b"architectury exact bytes"
        self.api_sha = hashlib.sha256(self.api_bytes).hexdigest()
        self.arch_sha = hashlib.sha256(self.arch_bytes).hexdigest()
        self.row = {
            "loader": "fabric",
            "fabric_api": "0.92.6+1.20.1",
            "architectury": {"kind": "maven", "version": "9.2.14"},
        }
        self.metadata = self.root / "verification-metadata.xml"
        self.metadata.write_text(
            f"""<?xml version='1.0' encoding='UTF-8'?>
<verification-metadata xmlns='https://schema.gradle.org/dependency-verification'>
  <components>
    <component group='net.fabricmc.fabric-api' name='fabric-api' version='0.92.6+1.20.1'>
      <artifact name='fabric-api-0.92.6+1.20.1.jar'><sha256 value='{self.api_sha}'/></artifact>
    </component>
    <component group='dev.architectury' name='architectury-fabric' version='9.2.14'>
      <artifact name='architectury-fabric-9.2.14.jar'><sha256 value='{self.arch_sha}'/></artifact>
    </component>
  </components>
</verification-metadata>
""",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def download_exact(
        self, _url: str, destination: Path, expected_sha256: str
    ) -> Path:
        payload = self.api_bytes if destination.name.startswith("fabric-api-") else self.arch_bytes
        self.assertEqual(expected_sha256, hashlib.sha256(payload).hexdigest())
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(payload)
        return destination

    def test_dependencies_use_gradle_hashes_and_deduplicate_without_tofu(self) -> None:
        with mock.patch.object(
            packaged_runtime, "download", side_effect=self.download_exact
        ) as download:
            with packaged_runtime.runtime_dependencies(
                self.row, self.store, self.metadata
            ) as dependencies:
                self.assertEqual([self.api_sha, self.arch_sha], [item.sha256 for item in dependencies])
                self.assertTrue(all(item.path.is_file() for item in dependencies))
                self.assertEqual(2, len(list((self.store.leases_dir / "active").glob("*.json"))))
        self.assertEqual(2, download.call_count)

        with mock.patch.object(
            packaged_runtime,
            "download",
            side_effect=AssertionError("deduplicated blobs must not download again"),
        ) as second_download:
            with packaged_runtime.runtime_dependencies(
                self.row, self.store, self.metadata
            ) as dependencies:
                self.assertEqual(2, len(dependencies))
        second_download.assert_not_called()
        self.assertEqual([], list((self.store.leases_dir / "active").glob("*.json")))
        self.assertEqual(2, len(list((self.store.blobs_dir / "sha256").rglob("?" * 64))))

    def test_leased_dependencies_install_under_their_maven_jar_names(self) -> None:
        """Loaders only discover ``*.jar``; store blobs are named by digest."""

        mods = self.root / "game" / "mods"
        with mock.patch.object(
            packaged_runtime, "download", side_effect=self.download_exact
        ):
            with packaged_runtime.runtime_dependencies(
                self.row, self.store, self.metadata
            ) as dependencies:
                for dependency in dependencies:
                    self.assertEqual(
                        64,
                        len(dependency.path.name),
                        "store blob is expected to be content-addressed",
                    )
                    packaged_runtime.copy_verified(
                        dependency.path,
                        mods,
                        dependency.sha256,
                        name=dependency.filename,
                    )

        with mock.patch.object(
            packaged_runtime, "download", side_effect=self.download_exact
        ):
            with packaged_runtime.runtime_dependencies(
                self.row, self.store, self.metadata
            ) as leased:
                for dependency in leased:
                    with self.assertRaisesRegex(
                        packaged_runtime.RuntimeFailure, "content-addressed"
                    ):
                        packaged_runtime.copy_verified(
                            dependency.path, mods, dependency.sha256
                        )

        installed = sorted(path.name for path in mods.iterdir())
        self.assertTrue(
            all(name.endswith(".jar") for name in installed),
            f"loaders would ignore these mods: {installed}",
        )
        self.assertEqual(
            sorted(item.filename for item in dependencies), installed
        )

    def test_copy_verified_rejects_a_name_that_escapes_the_destination(self) -> None:
        source = self.root / "payload.jar"
        source.write_bytes(b"payload")
        digest = packaged_runtime.sha256(source)

        for unsafe in ("../escape.jar", "nested/escape.jar", ".."):
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "unsafe"):
                packaged_runtime.copy_verified(
                    source, self.root / "mods", digest, name=unsafe
                )

    def test_altered_dependency_is_rejected_before_store_admission(self) -> None:
        def altered_download(
            _url: str, destination: Path, _expected_sha256: str
        ) -> Path:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(b"altered dependency")
            return destination

        with mock.patch.object(
            packaged_runtime, "download", side_effect=altered_download
        ):
            with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "cannot admit"):
                with packaged_runtime.runtime_dependencies(
                    self.row, self.store, self.metadata
                ):
                    self.fail("altered dependency was yielded")
        self.assertEqual([], list((self.store.blobs_dir / "sha256").rglob("?" * 64)))

    def test_server_installer_runs_from_one_leased_blob_in_each_isolated_scenario(self) -> None:
        installer = self.root / "forge-installer.jar"
        installer.write_bytes(b"forge installer")
        installer_sha = hashlib.sha256(installer.read_bytes()).hexdigest()
        matrix = {
            "installers": {
                "forge": {
                    "url": "https://example.invalid/forge-installer.jar",
                    "sha256": installer_sha,
                }
            }
        }
        row = {
            "installer": "forge",
            "loader": "forge",
            "runtime_version": "1.20.1",
            "loader_version": "1.20.1-47.4.9",
        }
        fetched = 0

        def fetch(
            store: runtime_store.RuntimeStore,
            **_kwargs: object,
        ) -> Path:
            nonlocal fetched
            fetched += 1
            return store.admit_blob(installer, installer_sha)

        commands: list[tuple[list[str], Path]] = []

        def run(
            command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            commands.append((command, cwd))
            (cwd / "run.sh").write_text("#!/bin/sh\n", encoding="utf-8")

        with mock.patch.object(
            packaged_runtime, "fetch_verified_blob", side_effect=fetch
        ), mock.patch.object(packaged_runtime, "run_checked", side_effect=run):
            for name in ("scenario-a", "scenario-b"):
                server = self.root / name
                server.mkdir()
                packaged_runtime.prepare_server(
                    matrix, row, server, self.store, "/fake/java", server / "install.log"
                )

        self.assertEqual(1, fetched)
        self.assertEqual(2, len(commands))
        self.assertNotEqual(commands[0][1], commands[1][1])
        self.assertEqual(self.store.path_for_blob(installer_sha), Path(commands[0][0][2]))
        self.assertEqual(self.store.path_for_blob(installer_sha), Path(commands[1][0][2]))

    def test_forge_server_retry_discards_partial_install_before_publish(self) -> None:
        installer = self.root / "forge-installer.jar"
        installer.write_bytes(b"forge installer")
        installer_sha = hashlib.sha256(installer.read_bytes()).hexdigest()
        matrix = {
            "installers": {
                "forge": {
                    "url": "https://example.invalid/forge-installer.jar",
                    "sha256": installer_sha,
                }
            }
        }
        row = {
            "installer": "forge",
            "loader": "forge",
            "runtime_version": "1.20.1",
            "loader_version": "1.20.1-47.4.9",
        }
        attempts: list[Path] = []

        def fetch(store: runtime_store.RuntimeStore, **_kwargs: object) -> Path:
            return store.admit_blob(installer, installer_sha)

        def run(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            attempts.append(cwd)
            if len(attempts) == 1:
                (cwd / "partial-library.jar").write_bytes(b"partial")
                raise packaged_runtime.RuntimeFailure("Read timed out")
            (cwd / "run.sh").write_text("#!/bin/sh\n", encoding="utf-8")

        server = self.root / "forge-server"
        server.mkdir()
        with mock.patch.object(
            packaged_runtime, "fetch_verified_blob", side_effect=fetch
        ), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            command = packaged_runtime.prepare_server(
                matrix, row, server, self.store, "/fake/java", self.root / "install.log"
            )

        self.assertEqual(["bash", str(server / "run.sh"), "nogui"], command)
        self.assertEqual(2, len(attempts))
        self.assertNotEqual(attempts[0], attempts[1])
        self.assertFalse((server / "partial-library.jar").exists())
        self.assertTrue((server / "run.sh").is_file())
        self.assertEqual(False, checked.call_args_list[0].kwargs["append"])
        self.assertEqual(True, checked.call_args_list[1].kwargs["append"])
        sleep.assert_called_once_with(5)

    def test_forge_server_retry_fails_closed_without_partial_tree(self) -> None:
        installer = self.root / "forge-installer-failing.jar"
        installer.write_bytes(b"forge installer")
        installer_sha = hashlib.sha256(installer.read_bytes()).hexdigest()
        matrix = {
            "installers": {
                "forge": {
                    "url": "https://example.invalid/forge-installer.jar",
                    "sha256": installer_sha,
                }
            }
        }
        row = {
            "installer": "forge",
            "loader": "forge",
            "runtime_version": "1.20.1",
            "loader_version": "1.20.1-47.4.9",
        }

        def fetch(store: runtime_store.RuntimeStore, **_kwargs: object) -> Path:
            return store.admit_blob(installer, installer_sha)

        def fail(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            (cwd / "partial-library.jar").write_bytes(b"partial")
            raise packaged_runtime.RuntimeFailure("Read timed out")

        server = self.root / "forge-server-failing"
        server.mkdir()
        with mock.patch.object(
            packaged_runtime, "fetch_verified_blob", side_effect=fetch
        ), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=fail
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep"):
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure,
                "Forge server installation failed after 3 isolated attempts",
            ):
                packaged_runtime.prepare_server(
                    matrix,
                    row,
                    server,
                    self.store,
                    "/fake/java",
                    self.root / "failing-install.log",
                )

        self.assertEqual(3, checked.call_count)
        self.assertEqual([], list(server.iterdir()))
        self.assertEqual([], list(self.root.glob(".forge-server-attempt-*")))

    def test_neoforge_server_retry_discards_partial_install_before_publish(self) -> None:
        installer = self.root / "neoforge-installer.jar"
        installer.write_bytes(b"neoforge installer")
        installer_sha = hashlib.sha256(installer.read_bytes()).hexdigest()
        matrix = {
            "installers": {
                "neoforge": {
                    "url": "https://example.invalid/neoforge-installer.jar",
                    "sha256": installer_sha,
                }
            }
        }
        row = {
            "installer": "neoforge",
            "loader": "neoforge",
            "runtime_version": "1.21.7",
            "loader_version": "21.7.25-beta",
        }
        attempts: list[Path] = []

        def fetch(store: runtime_store.RuntimeStore, **_kwargs: object) -> Path:
            return store.admit_blob(installer, installer_sha)

        def run(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            attempts.append(cwd)
            if len(attempts) == 1:
                (cwd / "partial-library.jar").write_bytes(b"partial")
                raise packaged_runtime.RuntimeFailure("transient Maven failure")
            (cwd / "run.sh").write_text("#!/bin/sh\n", encoding="utf-8")

        server = self.root / "neoforge-server"
        server.mkdir()
        with mock.patch.object(
            packaged_runtime, "fetch_verified_blob", side_effect=fetch
        ), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=run
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            command = packaged_runtime.prepare_server(
                matrix, row, server, self.store, "/fake/java", self.root / "install.log"
            )

        self.assertEqual(["bash", str(server / "run.sh"), "nogui"], command)
        self.assertEqual(2, len(attempts))
        self.assertNotEqual(attempts[0], attempts[1])
        self.assertFalse((server / "partial-library.jar").exists())
        self.assertTrue((server / "run.sh").is_file())
        self.assertEqual(False, checked.call_args_list[0].kwargs["append"])
        self.assertEqual(True, checked.call_args_list[1].kwargs["append"])
        sleep.assert_called_once_with(5)

    def test_neoforge_server_retry_fails_closed_without_partial_tree(self) -> None:
        installer = self.root / "neoforge-installer-failing.jar"
        installer.write_bytes(b"neoforge installer")
        installer_sha = hashlib.sha256(installer.read_bytes()).hexdigest()
        matrix = {
            "installers": {
                "neoforge": {
                    "url": "https://example.invalid/neoforge-installer.jar",
                    "sha256": installer_sha,
                }
            }
        }
        row = {
            "installer": "neoforge",
            "loader": "neoforge",
            "runtime_version": "1.21.7",
            "loader_version": "21.7.25-beta",
        }

        def fetch(store: runtime_store.RuntimeStore, **_kwargs: object) -> Path:
            return store.admit_blob(installer, installer_sha)

        def fail(
            _command: list[str], cwd: Path, _log: Path, _env: dict[str, str], **_kwargs: object
        ) -> None:
            (cwd / "partial-library.jar").write_bytes(b"partial")
            raise packaged_runtime.RuntimeFailure("transient Maven failure")

        server = self.root / "neoforge-server-failing"
        server.mkdir()
        with mock.patch.object(
            packaged_runtime, "fetch_verified_blob", side_effect=fetch
        ), mock.patch.object(
            packaged_runtime, "run_checked", side_effect=fail
        ) as checked, mock.patch.object(packaged_runtime.time, "sleep"):
            with self.assertRaisesRegex(
                packaged_runtime.RuntimeFailure, "after 3 isolated attempts"
            ):
                packaged_runtime.prepare_server(
                    matrix,
                    row,
                    server,
                    self.store,
                    "/fake/java",
                    self.root / "failing-install.log",
                )

        self.assertEqual(3, checked.call_count)
        self.assertEqual([], list(server.iterdir()))
        self.assertEqual([], list(self.root.glob(".neoforge-server-attempt-*")))


class PackagedRuntimeSessionAndEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_environment_store_root_gc_and_summary_metrics_are_bounded(self) -> None:
        configured = self.root / "persistent-cache"
        env = {
            packaged_runtime.RUNTIME_STORE_ENV: str(configured),
            packaged_runtime.RUNTIME_STORE_MAX_AGE_ENV: "0",
            packaged_runtime.RUNTIME_STORE_MAX_BYTES_ENV: "0",
        }
        session = packaged_runtime.PackagedRuntimeSession.from_environment(
            self.root / "scratch", env
        )
        self.assertEqual(
            (configured / "RuntimeStore" / "v1").resolve(), session.store.root
        )
        blob_source = self.root / "blob.jar"
        blob_source.write_bytes(b"temporary dependency")
        digest = hashlib.sha256(blob_source.read_bytes()).hexdigest()
        session.store.admit_blob(blob_source, digest)
        before = session.metrics()
        self.assertEqual(
            {"hits", "misses", "pruned_entries", "pruned_bytes", "total_bytes"},
            set(before),
        )
        self.assertEqual(len(b"temporary dependency"), before["total_bytes"])
        after = session.gc()
        self.assertEqual(0, after["total_bytes"])
        self.assertEqual(len(b"temporary dependency"), after["pruned_bytes"])

        default = packaged_runtime.runtime_store_cache_root({})
        self.assertNotIn("e2e-out", default.parts)
        with self.assertRaises(packaged_runtime.RuntimeFailure):
            packaged_runtime.PackagedRuntimeSession.from_environment(
                self.root / "invalid", {packaged_runtime.RUNTIME_STORE_MAX_BYTES_ENV: "-1"}
            )

    def test_runtime_log_filter_keeps_only_the_exact_benign_kqueue_stack(self) -> None:
        log = self.root / "server.log"
        log.write_text(
            "\n".join(
                (
                    packaged_runtime.DEBUG_FILE_APPENDER_FAILURE,
                    packaged_runtime.KQUEUE_UNSUPPORTED_PLATFORM_CAUSE,
                    packaged_runtime.KQUEUE_NATIVE_INIT_FAILURE,
                )
            ),
            encoding="utf-8",
        )
        packaged_runtime.scan_runtime_logs([log])

        for label, content in (
            ("missing cause", packaged_runtime.KQUEUE_NATIVE_INIT_FAILURE),
            (
                "different linkage error",
                "\n".join(
                    (
                        packaged_runtime.DEBUG_FILE_APPENDER_FAILURE,
                        packaged_runtime.KQUEUE_UNSUPPORTED_PLATFORM_CAUSE,
                        "java.lang.NoSuchMethodError: still fatal",
                    )
                ),
            ),
        ):
            with self.subTest(label=label):
                log.write_text(content, encoding="utf-8")
                with self.assertRaisesRegex(
                    packaged_runtime.RuntimeFailure, "fatal runtime log evidence"
                ):
                    packaged_runtime.scan_runtime_logs([log])

    def test_forced_process_stop_waits_for_full_group_before_export(self) -> None:
        process = mock.Mock()
        process.pid = 4242
        process.poll.return_value = None

        with mock.patch.object(packaged_runtime.os, "name", "posix"), mock.patch.object(
            packaged_runtime.os, "killpg"
        ) as kill_group, mock.patch.object(
            packaged_runtime, "_posix_process_group_exists", return_value=True
        ), mock.patch.object(
            packaged_runtime,
            "_wait_for_posix_process_group_exit",
            side_effect=[False, True],
        ) as wait_for_group:
            packaged_runtime.stop_process(process)

        self.assertEqual(
            [
                mock.call(4242, packaged_runtime.signal.SIGTERM),
                mock.call(4242, packaged_runtime.signal.SIGKILL),
            ],
            kill_group.call_args_list,
        )
        self.assertEqual(
            [
                mock.call(
                    process,
                    4242,
                    packaged_runtime.PROCESS_GRACEFUL_STOP_SECONDS,
                ),
                mock.call(
                    process,
                    4242,
                    packaged_runtime.PROCESS_FORCE_STOP_SECONDS,
                ),
            ],
            wait_for_group.call_args_list,
        )

    def test_exited_forge_shell_does_not_hide_lingering_java_child(self) -> None:
        process = mock.Mock()
        process.pid = 4242
        process.poll.return_value = 0

        with mock.patch.object(packaged_runtime.os, "name", "posix"), mock.patch.object(
            packaged_runtime.os, "killpg"
        ) as kill_group, mock.patch.object(
            packaged_runtime, "_posix_process_group_exists", return_value=True
        ), mock.patch.object(
            packaged_runtime,
            "_wait_for_posix_process_group_exit",
            return_value=True,
        ) as wait_for_group:
            packaged_runtime.stop_process(process)

        kill_group.assert_called_once_with(4242, packaged_runtime.signal.SIGTERM)
        wait_for_group.assert_called_once_with(
            process,
            4242,
            packaged_runtime.PROCESS_GRACEFUL_STOP_SECONDS,
        )

    def test_process_group_wait_reaps_launcher_and_observes_descendants(self) -> None:
        process = mock.Mock()
        process.poll.side_effect = [None, 0]

        with mock.patch.object(
            packaged_runtime,
            "_posix_process_group_exists",
            side_effect=[True, False],
        ), mock.patch.object(packaged_runtime.time, "sleep") as sleep:
            exited = packaged_runtime._wait_for_posix_process_group_exit(
                process,
                4242,
                1,
            )

        self.assertTrue(exited)
        self.assertEqual(2, process.poll.call_count)
        sleep.assert_called_once_with(packaged_runtime.PROCESS_GROUP_POLL_SECONDS)

    def test_replaymod_login_stall_requests_one_live_thread_dump(self) -> None:
        log = self.root / "client.log"
        process = mock.Mock()
        process.poll.return_value = None
        server_log = self.root / "server.log"
        server = mock.Mock()
        server.poll.return_value = None
        state = packaged_runtime.ReplayModLoginDiagnostic()

        log.write_text("client starting\n", encoding="utf-8")
        packaged_runtime.maybe_capture_replaymod_login_stall(
            process, log, state, now=100.0
        )
        process.send_signal.assert_not_called()

        log.write_text("Connecting to 127.0.0.1, 25565\n", encoding="utf-8")
        packaged_runtime.maybe_capture_replaymod_login_stall(
            process, log, state, now=101.0
        )
        packaged_runtime.maybe_capture_replaymod_login_stall(
            process,
            log,
            state,
            now=101.0 + packaged_runtime.REPLAYMOD_LOGIN_STALL_SECONDS - 0.1,
        )
        process.send_signal.assert_not_called()

        with mock.patch.object(packaged_runtime.os, "name", "posix"):
            packaged_runtime.maybe_capture_replaymod_login_stall(
                process,
                log,
                state,
                server_process=server,
                server_log=server_log,
                now=101.0 + packaged_runtime.REPLAYMOD_LOGIN_STALL_SECONDS,
            )
            packaged_runtime.maybe_capture_replaymod_login_stall(
                process,
                log,
                state,
                server_process=server,
                server_log=server_log,
                now=200.0,
            )

        process.send_signal.assert_called_once_with(packaged_runtime.signal.SIGQUIT)
        server.send_signal.assert_called_once_with(packaged_runtime.signal.SIGQUIT)
        self.assertTrue(state.dump_requested)

    def test_replaymod_login_progress_suppresses_thread_dump(self) -> None:
        log = self.root / "client.log"
        log.write_text(
            "Connecting to 127.0.0.1, 25565\nMultiplayer Recording is disabled\n",
            encoding="utf-8",
        )
        process = mock.Mock()
        process.poll.return_value = None
        state = packaged_runtime.ReplayModLoginDiagnostic(connecting_seen_at=1.0)

        packaged_runtime.maybe_capture_replaymod_login_stall(
            process, log, state, now=100.0
        )

        process.send_signal.assert_not_called()
        self.assertTrue(state.login_progressed)
        self.assertFalse(state.dump_requested)

    def test_wait_for_marker_runs_live_diagnostic_callback(self) -> None:
        game_dir = self.root / "client_a"
        report = game_dir / "e2e-report"
        report.mkdir(parents=True)
        process = mock.Mock()
        process.poll.return_value = None
        polls = 0

        def on_poll() -> None:
            nonlocal polls
            polls += 1
            (report / "done.marker").write_text("pass", encoding="utf-8")

        with mock.patch.object(packaged_runtime.time, "sleep"):
            marker = packaged_runtime.wait_for_marker(
                process,
                game_dir,
                "client_a",
                timeout=1,
                on_poll=on_poll,
            )

        self.assertEqual("pass", marker)
        self.assertEqual(1, polls)

    def test_compatibility_marker_is_required_in_every_process_log(self) -> None:
        marker = packaged_runtime.COMPATIBILITY_LOG_MARKERS[
            "neoforge-26.1-break-event-v1"
        ]
        server = self.root / "server.log"
        client = self.root / "client_a.log"
        server.write_text(marker, encoding="utf-8")
        client.write_text(marker, encoding="utf-8")
        row = {"compatibility_patch": "neoforge-26.1-break-event-v1"}

        packaged_runtime.require_compatibility_marker([server, client], row)
        packaged_runtime.require_compatibility_marker([], {})

        client.write_text("marker missing", encoding="utf-8")
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure, "not observed in every process"
        ):
            packaged_runtime.require_compatibility_marker([server, client], row)
        with self.assertRaisesRegex(
            packaged_runtime.RuntimeFailure, "unknown runtime compatibility patch"
        ):
            packaged_runtime.require_compatibility_marker(
                [server], {"compatibility_patch": "unknown"}
            )

    def test_evidence_export_is_atomic_allowlisted_and_excludes_runtime_state(self) -> None:
        execution = self.root / "execution"
        (execution / "logs").mkdir(parents=True)
        (execution / "logs" / "server.log").write_text("server", encoding="utf-8")
        (execution / "client_a" / "e2e-report").mkdir(parents=True)
        (execution / "client_a" / "e2e-report" / "report.json").write_text(
            "{}", encoding="utf-8"
        )
        (execution / "client_a" / "e2e-report" / "done.marker").write_text(
            "pass", encoding="utf-8"
        )
        (execution / "client_a" / "screenshots").mkdir()
        (execution / "client_a" / "screenshots" / "capture.png").write_bytes(b"png")
        (execution / "server" / "crash-reports").mkdir(parents=True)
        (execution / "server" / "crash-reports" / "crash.txt").write_text(
            "crash", encoding="utf-8"
        )
        (execution / "server" / "mods").mkdir()
        (execution / "server" / "mods" / "secret.jar").write_bytes(b"never export")
        (execution / "server" / "world").mkdir()
        (execution / "server" / "world" / "level.dat").write_bytes(b"never export")
        evidence = self.root / "evidence" / "profiles" / "lane"
        result = {"status": "pass", "profile": "profiles/lane"}

        result_path = packaged_runtime.export_profile_evidence(execution, evidence, result)

        self.assertEqual(evidence / "result.json", result_path)
        self.assertTrue((evidence / "logs" / "server.log").is_file())
        self.assertTrue((evidence / "client_a" / "e2e-report" / "report.json").is_file())
        self.assertTrue((evidence / "client_a" / "screenshots" / "capture.png").is_file())
        self.assertTrue((evidence / "server" / "crash-reports" / "crash.txt").is_file())
        self.assertFalse((evidence / "server" / "mods").exists())
        self.assertFalse((evidence / "server" / "world").exists())
        self.assertEqual([], list((evidence.parent).glob(".*.exporting-*")))

    def test_evidence_export_rejects_unapproved_or_symlinked_allowed_content(self) -> None:
        execution = self.root / "execution"
        screenshots = execution / "client_a" / "screenshots"
        screenshots.mkdir(parents=True)
        (screenshots / "secret.txt").write_text("secret", encoding="utf-8")
        evidence = self.root / "evidence" / "profiles" / "lane"

        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "unapproved"):
            packaged_runtime.export_profile_evidence(execution, evidence, {"status": "fail"})
        self.assertFalse(evidence.exists())

        (screenshots / "secret.txt").unlink()
        target = self.root / "outside.png"
        target.write_bytes(b"outside")
        try:
            (screenshots / "capture.png").symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        with self.assertRaisesRegex(packaged_runtime.RuntimeFailure, "symbolic link"):
            packaged_runtime.export_profile_evidence(execution, evidence, {"status": "fail"})
        self.assertFalse(evidence.exists())


if __name__ == "__main__":
    unittest.main()
