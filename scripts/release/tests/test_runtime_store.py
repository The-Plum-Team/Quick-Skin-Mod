from __future__ import annotations

import dataclasses
import gc
import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import runtime_store  # noqa: E402


class FakeClock:
    def __init__(self, value: float = 0.0) -> None:
        self.value = value

    def __call__(self) -> float:
        return self.value

    def set(self, value: float) -> None:
        self.value = value


class RuntimeStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.clock = FakeClock()
        self.store = runtime_store.RuntimeStore(self.root / "cache", clock=self.clock)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def recipe(**changes: object) -> runtime_store.RuntimeRecipe:
        values: dict[str, object] = {
            "os_name": "linux",
            "architecture": "x86_64",
            "java_major": 21,
            "minecraft_version": "1.21.4",
            "loader": "neoforge",
            "loader_version": "21.4.156",
            "installer_sha256": "f" * 64,
            "launcher_library_revision": "minecraft-launcher-lib-8.0",
            "normalizer_revision": "quickskin-profile-normalizer-v2",
            "role": "client",
        }
        values.update(changes)
        return runtime_store.RuntimeRecipe(**values)  # type: ignore[arg-type]

    def source_tree(
        self,
        name: str,
        files: dict[str, bytes],
        *,
        executable: set[str] | None = None,
    ) -> Path:
        root = self.root / name
        root.mkdir()
        executable = executable or set()
        for relative, content in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            os.chmod(path, 0o755 if relative in executable else 0o640)
        return root

    def blob_files(self) -> list[Path]:
        return sorted(
            path
            for path in (self.store.blobs_dir / "sha256").rglob("*")
            if path.is_file()
        )

    def wait_for_child_file(
        self, path: Path, process: subprocess.Popen[str], *, timeout: float = 10
    ) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if path.is_file():
                return
            return_code = process.poll()
            if return_code is not None:
                stdout, stderr = process.communicate()
                self.fail(
                    f"child exited {return_code} before creating {path.name}: "
                    f"stdout={stdout!r}, stderr={stderr!r}"
                )
            time.sleep(0.01)
        self.fail(f"child did not create {path.name} within {timeout}s")

    def test_layout_is_versioned_and_run_workspaces_are_always_new(self) -> None:
        self.assertEqual(
            self.root / "cache" / "RuntimeStore" / "v1",
            self.store.root,
        )
        for name in ("blobs", "trees", "recipes", "leases", "tmp", "trash"):
            self.assertTrue((self.store.root / name).is_dir(), name)
        self.assertTrue((self.store.root / "blobs" / "sha256").is_dir())

        parent = self.root / "runs"
        first = runtime_store.RunWorkspace.create(parent)
        (first.path / "stale.txt").write_text("old run", encoding="utf-8")
        second = runtime_store.RunWorkspace.create(parent)
        self.assertNotEqual(first.path, second.path)
        self.assertEqual([], list(second.path.iterdir()))
        first.cleanup()
        second.cleanup()
        self.assertFalse(first.path.exists())
        self.assertFalse(second.path.exists())

    def test_recipe_digest_covers_every_installation_input(self) -> None:
        recipe = self.recipe()
        mutations = {
            "os_name": "windows",
            "architecture": "aarch64",
            "java_major": 17,
            "minecraft_version": "1.21.5",
            "loader": "fabric",
            "loader_version": "0.17.3",
            "installer_sha256": "e" * 64,
            "launcher_library_revision": "minecraft-launcher-lib-8.1",
            "normalizer_revision": "quickskin-profile-normalizer-v3",
            "role": "server",
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                changed = dataclasses.replace(recipe, **{field: value})
                self.assertNotEqual(recipe.digest(), changed.digest())

        payload = recipe.payload()
        self.assertEqual(2, payload["schema"])
        changed_schema = dict(payload)
        changed_schema["schema"] = 3
        self.assertNotEqual(
            recipe.digest(),
            hashlib.sha256(runtime_store.canonical_json_bytes(changed_schema)).hexdigest(),
        )

    def test_recipe_role_is_restricted_to_the_reviewed_namespaces(self) -> None:
        for role in ("", "  ", "servers", "CLIENT", "harness"):
            with self.subTest(role=role), self.assertRaises(runtime_store.RuntimeStoreError):
                self.recipe(role=role)
        self.assertEqual({"client", "server"}, set(runtime_store.RECIPE_ROLES))

    def test_workspace_promotion_preserves_siblings_and_rolls_back_to_last_good(self) -> None:
        parent = self.root / "evidence"
        parent.mkdir()
        sibling = parent / "unrelated.txt"
        sibling.write_text("do not touch", encoding="utf-8")
        sibling_inode = sibling.stat().st_ino
        current = parent / "current"

        first = runtime_store.RunWorkspace.create(parent, prefix="staging-")
        (first.path / "result.json").write_text("first", encoding="utf-8")
        initial = first.promote_to(current)
        self.assertFalse(initial.replaced)
        self.assertFalse(initial.recovered_interrupted)
        self.assertEqual("first", (current / "result.json").read_text(encoding="utf-8"))

        second = runtime_store.RunWorkspace.create(parent, prefix="staging-")
        second_staging = second.path
        (second.path / "result.json").write_text("broken", encoding="utf-8")
        real_rename = os.rename

        def fail_new_current(source: os.PathLike[str], destination: os.PathLike[str]) -> None:
            if Path(source) == second_staging and Path(destination) == current:
                raise OSError("injected promotion failure")
            real_rename(source, destination)

        with mock.patch.object(runtime_store.os, "rename", side_effect=fail_new_current):
            with self.assertRaisesRegex(OSError, "injected promotion failure"):
                second.promote_to(current)

        self.assertEqual("first", (current / "result.json").read_text(encoding="utf-8"))
        self.assertTrue(second_staging.is_dir())
        self.assertFalse((parent / ".current.last-good").exists())
        second.cleanup()

        interrupted_last_good = parent / ".current.last-good"
        os.rename(current, interrupted_last_good)
        self.assertFalse(current.exists())
        third = runtime_store.RunWorkspace.create(parent, prefix="staging-")
        (third.path / "result.json").write_text("third", encoding="utf-8")
        promoted = third.promote_to(current)
        self.assertTrue(promoted.replaced)
        self.assertTrue(promoted.recovered_interrupted)
        self.assertEqual("third", (current / "result.json").read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())
        self.assertEqual("do not touch", sibling.read_text(encoding="utf-8"))
        self.assertEqual(sibling_inode, sibling.stat().st_ino)

    def test_workspace_refuses_to_replace_an_unowned_current_snapshot(self) -> None:
        parent = self.root / "foreign-evidence"
        current = parent / "current"
        current.mkdir(parents=True)
        foreign = current / "result.json"
        foreign.write_text("foreign", encoding="utf-8")
        workspace = runtime_store.RunWorkspace.create(parent, prefix="staging-")
        (workspace.path / "result.json").write_text("new", encoding="utf-8")

        with self.assertRaisesRegex(runtime_store.InvalidRuntimeTreeError, "ownership marker"):
            workspace.promote_to(current)

        self.assertEqual("foreign", foreign.read_text(encoding="utf-8"))
        self.assertTrue(workspace.path.is_dir())
        workspace.cleanup()

    def test_workspace_cleanup_and_promotion_reject_a_replacement_directory(self) -> None:
        parent = self.root / "workspace-identity"
        workspace = runtime_store.RunWorkspace.create(parent, prefix="owned-")
        original = parent / "detached-original"
        os.rename(workspace.path, original)
        workspace.path.mkdir()
        foreign = workspace.path / "foreign.txt"
        foreign.write_text("must survive", encoding="utf-8")

        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "replaced by another directory"
        ):
            workspace.cleanup()
        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "replaced by another directory"
        ):
            workspace.promote_to(parent / "current")

        self.assertEqual("must survive", foreign.read_text(encoding="utf-8"))
        foreign.unlink()
        workspace.path.rmdir()
        os.rename(original, workspace.path)
        workspace.cleanup()

    def test_workspace_cleanup_retires_name_before_best_effort_delete(self) -> None:
        parent = self.root / "cleanup-quarantine"
        workspace = runtime_store.RunWorkspace.create(parent, prefix="owned-")
        original = workspace.path
        (original / "result.json").write_text("owned", encoding="utf-8")

        with mock.patch.object(
            runtime_store, "_remove_tree", side_effect=PermissionError("read-only")
        ):
            workspace.cleanup()

        self.assertFalse(original.exists())
        quarantines = list(parent.glob(f".{original.name}.retired-*"))
        self.assertEqual(1, len(quarantines))
        self.assertEqual(
            "owned", (quarantines[0] / "result.json").read_text(encoding="utf-8")
        )

    def test_partial_last_good_cleanup_does_not_poison_third_promotion(self) -> None:
        parent = self.root / "retirement-retry"
        current = parent / "current"
        first = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (first.path / "result.json").write_text("first", encoding="utf-8")
        first.promote_to(current)

        second = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (second.path / "result.json").write_text("second", encoding="utf-8")
        real_remove = runtime_store.RunWorkspace._remove_snapshot_quarantine
        injected = False

        def fail_after_partial_cleanup(
            path: Path, expected: os.stat_result, generation: str
        ) -> None:
            nonlocal injected
            if path.name.startswith(".current.retired-") and not injected:
                injected = True
                marker = path / ".quickskin-run-workspace.json"
                (path / "result.json").unlink()
                self.assertTrue(marker.is_file())
                raise PermissionError("injected partial recursive delete")
            real_remove(path, expected, generation)

        with mock.patch.object(
            runtime_store.RunWorkspace,
            "_remove_snapshot_quarantine",
            side_effect=fail_after_partial_cleanup,
        ):
            promoted = second.promote_to(current)

        self.assertTrue(promoted.replaced)
        self.assertTrue(injected)
        self.assertEqual("second", (current / "result.json").read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())
        self.assertEqual(1, len(list(parent.glob(".current.retired-*"))))

        third = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (third.path / "result.json").write_text("third", encoding="utf-8")
        third.promote_to(current)

        self.assertEqual("third", (current / "result.json").read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())
        self.assertEqual([], list(parent.glob(".current.retired-*")))

    def test_workspace_rolls_back_when_post_rename_validation_fails(self) -> None:
        parent = self.root / "validation-rollback"
        current = parent / "current"
        first = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (first.path / "result.json").write_text("first", encoding="utf-8")
        first.promote_to(current)

        second = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (second.path / "result.json").write_text("second", encoding="utf-8")
        real_generation = runtime_store.RunWorkspace._owned_snapshot_generation
        injected = False

        def fail_new_snapshot_once(path: Path) -> str:
            nonlocal injected
            result = path / "result.json"
            if (
                path == current
                and result.is_file()
                and result.read_text(encoding="utf-8") == "second"
                and not injected
            ):
                injected = True
                raise runtime_store.RuntimeStoreError("injected post-rename validation")
            return real_generation(path)

        with mock.patch.object(
            runtime_store.RunWorkspace,
            "_owned_snapshot_generation",
            side_effect=fail_new_snapshot_once,
        ):
            with self.assertRaisesRegex(
                runtime_store.RuntimeStoreError, "injected post-rename validation"
            ):
                second.promote_to(current)

        self.assertTrue(injected)
        self.assertEqual("first", (current / "result.json").read_text(encoding="utf-8"))
        self.assertEqual("second", (second.path / "result.json").read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())
        second.cleanup()

        third = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (third.path / "result.json").write_text("third", encoding="utf-8")
        third.promote_to(current)
        self.assertEqual("third", (current / "result.json").read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())

    def test_workspace_marker_has_a_bounded_secure_read(self) -> None:
        parent = self.root / "bounded-marker"
        current = parent / "current"
        first = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (first.path / "result.json").write_text("first", encoding="utf-8")
        first.promote_to(current)
        marker = current / ".quickskin-run-workspace.json"
        runtime_store._make_writable(marker)
        marker.write_bytes(b"x" * (runtime_store._MAX_WORKSPACE_MARKER_BYTES + 1))

        second = runtime_store.RunWorkspace.create(parent, prefix="candidate-")
        (second.path / "result.json").write_text("second", encoding="utf-8")
        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "ownership marker"
        ):
            second.promote_to(current)

        self.assertEqual("first", (current / "result.json").read_text(encoding="utf-8"))
        second.cleanup()

    def test_concurrent_processes_serialize_promotion_for_one_target(self) -> None:
        parent = self.root / "concurrent-promotion"
        parent.mkdir()
        sibling = parent / "unrelated.txt"
        sibling.write_text("preserve", encoding="utf-8")
        start = parent / "start"
        script = "\n".join(
            (
                "import json, sys, time",
                "from pathlib import Path",
                "sys.path.insert(0, sys.argv[1])",
                "from runtime_store import RunWorkspace",
                "parent, label = Path(sys.argv[2]), sys.argv[3]",
                "ready, start, done = map(Path, sys.argv[4:7])",
                "workspace = RunWorkspace.create(parent, prefix=f'.candidate-{label}-')",
                "(workspace.path / 'result.json').write_text(label, encoding='utf-8')",
                "ready.write_text('ready', encoding='utf-8')",
                "deadline = time.monotonic() + 10",
                "while not start.is_file():",
                "    if time.monotonic() >= deadline: raise TimeoutError('start timeout')",
                "    time.sleep(0.01)",
                "result = workspace.promote_to(parent / 'current')",
                "done.write_text(json.dumps({'replaced': result.replaced}), encoding='utf-8')",
            )
        )
        processes: list[subprocess.Popen[str]] = []
        ready_paths: list[Path] = []
        done_paths: list[Path] = []
        try:
            for label in ("a", "b"):
                ready = parent / f"{label}.ready"
                done = parent / f"{label}.done"
                ready_paths.append(ready)
                done_paths.append(done)
                processes.append(
                    subprocess.Popen(
                        [
                            sys.executable,
                            "-c",
                            script,
                            str(ROOT / "e2e"),
                            str(parent),
                            label,
                            str(ready),
                            str(start),
                            str(done),
                        ],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                    )
                )
            for ready, process in zip(ready_paths, processes, strict=True):
                self.wait_for_child_file(ready, process)
            start.write_text("go", encoding="utf-8")
            for process in processes:
                stdout, stderr = process.communicate(timeout=15)
                self.assertEqual(0, process.returncode, (stdout, stderr))
        finally:
            for process in processes:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)

        outcomes = [json.loads(path.read_text(encoding="utf-8")) for path in done_paths]
        self.assertEqual([False, True], sorted(item["replaced"] for item in outcomes))
        self.assertIn(
            (parent / "current" / "result.json").read_text(encoding="utf-8"),
            {"a", "b"},
        )
        self.assertEqual("preserve", sibling.read_text(encoding="utf-8"))
        self.assertFalse((parent / ".current.last-good").exists())
        self.assertEqual([], list(parent.glob(".candidate-*")))

    def test_publish_deduplicates_blobs_and_records_canonical_tree_metadata(self) -> None:
        source = self.source_tree(
            "source",
            {
                "bin/launch.sh": b"same bytes",
                "libraries/copy.jar": b"same bytes",
                "versions/profile.json": b'{"id":"test"}\n',
            },
            executable={"bin/launch.sh"},
        )
        recipe = self.recipe()

        stored = self.store.publish(recipe, source)

        self.assertEqual(recipe.digest(), stored.recipe_digest)
        self.assertEqual(stored.tree_digest, stored.manifest.digest())
        self.assertEqual(2, len(self.blob_files()))
        manifest_payload = json.loads(stored.manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(["files", "schema"], sorted(manifest_payload))
        entries = manifest_payload["files"]
        self.assertEqual(
            sorted(entry["path"] for entry in entries),
            [entry["path"] for entry in entries],
        )
        self.assertEqual(
            {"mode", "path", "sha256", "size"},
            set(entries[0]),
        )
        by_path = {entry.path: entry for entry in stored.manifest.entries}
        self.assertEqual(by_path["bin/launch.sh"].sha256, by_path["libraries/copy.jar"].sha256)
        self.assertEqual(0o755, by_path["bin/launch.sh"].mode)
        self.assertEqual(0o640, by_path["libraries/copy.jar"].mode)

        self.assertIsNotNone(self.store.lookup(recipe))
        self.assertIsNone(self.store.lookup(self.recipe(loader_version="21.4.157")))
        self.assertEqual(1, self.store.metrics.hits)
        self.assertEqual(1, self.store.metrics.misses)

    def test_materialization_is_verified_and_does_not_alias_mutable_store_bytes(self) -> None:
        source = self.source_tree(
            "source",
            {"bin/start": b"immutable", "config/options.txt": b"options"},
            executable={"bin/start"},
        )
        stored = self.store.publish(self.recipe(), source)

        first = self.store.materialize(stored, self.root / "run-a" / "client")
        self.assertEqual(b"immutable", (first / "bin/start").read_bytes())
        self.assertEqual(0o755, stat.S_IMODE((first / "bin/start").stat().st_mode))
        (first / "bin/start").write_bytes(b"Minecraft changed this run")

        second = self.store.materialize(stored, self.root / "run-b" / "client")
        self.assertEqual(b"immutable", (second / "bin/start").read_bytes())
        self.store.validate(stored)
        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "already exists"
        ):
            self.store.materialize(stored, second)

    def test_admit_blob_is_read_only_deduplicated_and_repairs_verified_bytes(
        self,
    ) -> None:
        source = self.root / "installer.jar"
        source.write_bytes(b"verified installer")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()

        admitted = self.store.admit_blob(source, digest)
        first_inode = admitted.stat().st_ino
        self.assertEqual(b"verified installer", admitted.read_bytes())
        self.assertEqual(0, stat.S_IMODE(admitted.stat().st_mode) & 0o222)

        duplicate = self.root / "installer-copy.jar"
        duplicate.write_bytes(source.read_bytes())
        self.assertEqual(admitted, self.store.admit_blob(duplicate, digest))
        self.assertEqual(first_inode, admitted.stat().st_ino)
        self.assertEqual(1, len(self.blob_files()))

        wrong = self.root / "wrong.jar"
        wrong.write_bytes(b"untrusted")
        with self.assertRaisesRegex(runtime_store.InvalidRuntimeTreeError, "SHA-256 mismatch"):
            self.store.admit_blob(wrong, digest)
        self.assertEqual(b"verified installer", admitted.read_bytes())

        os.chmod(admitted, 0o640)
        admitted.write_bytes(b"tampered store")
        repaired = self.store.admit_blob(source, digest)
        self.assertEqual(b"verified installer", repaired.read_bytes())
        self.assertEqual(0, stat.S_IMODE(repaired.stat().st_mode) & 0o222)

    def test_blob_lease_protects_an_unattached_dependency_from_gc(self) -> None:
        source = self.root / "dependency.jar"
        source.write_bytes(b"dependency")
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        admitted = self.store.admit_blob(source, digest)

        with self.store.lease_blob(digest) as leased:
            self.assertEqual(admitted, leased)
            result = self.store.gc(max_bytes=0)
            self.assertEqual(0, result.pruned_blobs)
            self.assertTrue(admitted.is_file())
            self.assertEqual(1, len(list((self.store.leases_dir / "active").glob("*.json"))))
            self.assertEqual(1, len(list((self.store.leases_dir / "active").glob("*.lock"))))

        self.assertEqual([], list((self.store.leases_dir / "active").iterdir()))
        result = self.store.gc(max_bytes=0)
        self.assertEqual(1, result.pruned_blobs)
        self.assertFalse(admitted.exists())

    def test_process_lease_survives_live_gc_and_is_reaped_after_os_exit(self) -> None:
        source = self.source_tree(
            "crash-runtime", {"profile.json": b"leased across process crash"}
        )
        recipe = self.recipe()
        stored = self.store.publish(recipe, source)
        blob = self.store.path_for_blob(stored.manifest.entries[0].sha256)
        ready = self.root / "lease.ready"
        crash = self.root / "lease.crash"
        script = "\n".join(
            (
                "import os, sys, time",
                "from pathlib import Path",
                "sys.path.insert(0, sys.argv[1])",
                "from runtime_store import RuntimeStore",
                "cache, recipe = Path(sys.argv[2]), sys.argv[3]",
                "ready, crash = Path(sys.argv[4]), Path(sys.argv[5])",
                "with RuntimeStore(cache).lease(recipe):",
                "    ready.write_text('locked', encoding='utf-8')",
                "    deadline = time.monotonic() + 15",
                "    while not crash.is_file():",
                "        if time.monotonic() >= deadline:",
                "            raise TimeoutError('crash signal timeout')",
                "        time.sleep(0.01)",
                "    os._exit(23)",
            )
        )
        process = subprocess.Popen(
            [
                sys.executable,
                "-c",
                script,
                str(ROOT / "e2e"),
                str(self.root / "cache"),
                recipe.digest(),
                str(ready),
                str(crash),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            self.wait_for_child_file(ready, process)
            live_result = self.store.gc(max_bytes=0)
            self.assertEqual(0, live_result.pruned)
            self.assertEqual(0, live_result.pruned_blobs)
            self.assertTrue(self.store.path_for_recipe(recipe).is_file())
            self.assertTrue(blob.is_file())
            self.assertEqual(1, len(list((self.store.leases_dir / "active").glob("*.json"))))
            self.assertEqual(1, len(list((self.store.leases_dir / "active").glob("*.lock"))))

            crash.write_text("exit without finally", encoding="utf-8")
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(23, process.returncode, (stdout, stderr))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)

        self.assertEqual(2, len(list((self.store.leases_dir / "active").iterdir())))
        orphan_result = self.store.gc(max_bytes=0)
        self.assertEqual(1, orphan_result.pruned)
        self.assertEqual(1, orphan_result.pruned_trees)
        self.assertEqual(1, orphan_result.pruned_blobs)
        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        self.assertFalse(blob.exists())
        self.assertEqual([], list((self.store.leases_dir / "active").iterdir()))

    def test_live_builder_staging_survives_gc_and_crash_orphan_is_reaped(self) -> None:
        recipe = self.recipe()
        ready = self.root / "builder.ready"
        crash = self.root / "builder.crash"
        script = "\n".join(
            (
                "import json, os, sys, time",
                "from pathlib import Path",
                "sys.path.insert(0, sys.argv[1])",
                "from runtime_store import RuntimeRecipe, RuntimeStore",
                "cache = Path(sys.argv[2])",
                "recipe = RuntimeRecipe.from_payload(json.loads(sys.argv[3]))",
                "ready, crash = Path(sys.argv[4]), Path(sys.argv[5])",
                "def build(target):",
                "    (target / 'partial.txt').write_text('partial', encoding='utf-8')",
                "    ready.write_text(str(target), encoding='utf-8')",
                "    deadline = time.monotonic() + 15",
                "    while not crash.is_file():",
                "        if time.monotonic() >= deadline:",
                "            raise TimeoutError('crash signal timeout')",
                "        time.sleep(0.01)",
                "    os._exit(24)",
                "with RuntimeStore(cache).get_or_create_lease(recipe, build):",
                "    raise AssertionError('builder unexpectedly returned')",
            )
        )
        process = subprocess.Popen(
            [
                sys.executable,
                "-c",
                script,
                str(ROOT / "e2e"),
                str(self.root / "cache"),
                json.dumps(recipe.payload(), separators=(",", ":"), sort_keys=True),
                str(ready),
                str(crash),
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        try:
            self.wait_for_child_file(ready, process)
            staging = Path(ready.read_text(encoding="utf-8"))
            self.assertRegex(
                staging.name,
                rf"^build-{recipe.digest()}-[A-Za-z0-9_]+$",
            )
            self.assertTrue((staging / "partial.txt").is_file())

            live_result = self.store.gc(max_bytes=0)
            self.assertEqual(0, live_result.pruned)
            self.assertTrue(staging.is_dir())

            crash.write_text("exit without finally", encoding="utf-8")
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(24, process.returncode, (stdout, stderr))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)

        self.assertTrue(staging.is_dir())
        orphan_result = self.store.gc(max_bytes=0)
        self.assertEqual(0, orphan_result.pruned)
        self.assertFalse(staging.exists())
        self.assertEqual([], list(self.store.tmp_dir.glob("build-*")))
        self.assertEqual([], list(self.store.trash_dir.iterdir()))

    def test_file_lock_rejects_symlink_without_relying_on_o_nofollow(self) -> None:
        external = self.root / "external-lock-target"
        external.write_text("unchanged", encoding="utf-8")
        lock_path = self.store.leases_dir / "locks" / "hostile.lock"
        try:
            lock_path.symlink_to(external)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")

        with mock.patch.object(runtime_store.os, "O_NOFOLLOW", 0, create=True):
            with self.assertRaisesRegex(runtime_store.StoreCorruptionError, "lock path"):
                with runtime_store._FileLock(lock_path, 0):
                    self.fail("unsafe lock unexpectedly acquired")

        self.assertEqual("unchanged", external.read_text(encoding="utf-8"))

    def test_json_read_rejects_path_swap_after_lstat_without_o_nofollow(self) -> None:
        record = self.root / "record.json"
        external = self.root / "external-record.json"
        record.write_text('{"source":"owned"}\n', encoding="utf-8")
        external.write_text('{"source":"external"}\n', encoding="utf-8")
        try:
            probe = self.root / "symlink-probe"
            probe.symlink_to(external)
            probe.unlink()
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        real_open = runtime_store.os.open
        swapped = False

        def swap_before_open(
            path: os.PathLike[str] | str,
            flags: int,
            mode: int = 0o777,
            *,
            dir_fd: int | None = None,
        ) -> int:
            nonlocal swapped
            if Path(path) == record and not swapped:
                swapped = True
                record.unlink()
                record.symlink_to(external)
            if dir_fd is None:
                return real_open(path, flags, mode)
            return real_open(path, flags, mode, dir_fd=dir_fd)

        with (
            mock.patch.object(runtime_store.os, "O_NOFOLLOW", 0, create=True),
            mock.patch.object(runtime_store.os, "open", side_effect=swap_before_open),
        ):
            with self.assertRaisesRegex(
                runtime_store.StoreCorruptionError, "changed while it was opened"
            ):
                self.store._read_json(record, 1024, "test record")

        self.assertTrue(swapped)
        self.assertEqual('{"source":"external"}\n', external.read_text(encoding="utf-8"))

    def test_process_local_lock_registry_does_not_retain_uuid_locks(self) -> None:
        baseline = len(runtime_store._LOCAL_LOCKS)
        for index in range(256):
            lock_path = self.store.leases_dir / "locks" / f"transient-{index}.lock"
            with runtime_store._FileLock(lock_path, 0):
                pass
        gc.collect()

        self.assertLessEqual(len(runtime_store._LOCAL_LOCKS), baseline + 2)

    def test_get_or_create_lease_closes_the_gc_gap_before_materialization(self) -> None:
        other = runtime_store.RuntimeStore(self.root / "cache", clock=self.clock)
        recipe = self.recipe()
        ready_for_gc = threading.Barrier(2)
        gc_finished = threading.Barrier(2)
        destination = self.root / "materialized-after-gc"
        errors: list[BaseException] = []

        def builder(target: Path) -> None:
            (target / "profile.json").write_bytes(b"complete runtime")

        def build_then_materialize() -> None:
            try:
                with self.store.get_or_create_lease(recipe, builder) as stored:
                    ready_for_gc.wait(timeout=5)
                    gc_finished.wait(timeout=5)
                    self.store.materialize(stored, destination)
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

        worker = threading.Thread(target=build_then_materialize)
        worker.start()
        ready_for_gc.wait(timeout=5)
        try:
            during_gap = other.gc(max_bytes=0)
        finally:
            gc_finished.wait(timeout=5)
            worker.join(10)

        self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(0, during_gap.pruned)
        self.assertEqual(0, during_gap.pruned_blobs)
        self.assertEqual(
            b"complete runtime", (destination / "profile.json").read_bytes()
        )
        after_release = other.gc(max_bytes=0)
        self.assertEqual(1, after_release.pruned)
        self.assertEqual(1, after_release.pruned_blobs)

    def test_combined_build_and_materialize_api_keeps_gc_lease(self) -> None:
        other = runtime_store.RuntimeStore(self.root / "cache", clock=self.clock)
        recipe = self.recipe()
        entered_materialize = threading.Barrier(2)
        gc_finished = threading.Barrier(2)
        destination = self.root / "combined-materialization"
        errors: list[BaseException] = []
        real_materialize = self.store.materialize

        def builder(target: Path) -> None:
            (target / "profile.json").write_bytes(b"combined runtime")

        def paused_materialize(
            stored: runtime_store.StoredRuntime, target: Path
        ) -> Path:
            entered_materialize.wait(timeout=5)
            gc_finished.wait(timeout=5)
            return real_materialize(stored, target)

        def worker_call() -> None:
            try:
                with mock.patch.object(
                    self.store, "materialize", side_effect=paused_materialize
                ):
                    self.store.materialize_get_or_create(recipe, builder, destination)
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

        worker = threading.Thread(target=worker_call)
        worker.start()
        entered_materialize.wait(timeout=5)
        try:
            during_materialize = other.gc(max_bytes=0)
        finally:
            gc_finished.wait(timeout=5)
            worker.join(10)

        self.assertFalse(worker.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(0, during_materialize.pruned)
        self.assertEqual(b"combined runtime", (destination / "profile.json").read_bytes())

    def test_get_or_create_warns_that_returned_metadata_is_not_a_lease(self) -> None:
        recipe = self.recipe()

        def builder(target: Path) -> None:
            (target / "profile.json").write_bytes(b"runtime")

        with self.assertWarnsRegex(DeprecationWarning, "without a GC lease"):
            stored = self.store.get_or_create(recipe, builder)
        self.assertEqual(recipe.digest(), stored.recipe_digest)

    def test_same_recipe_is_built_once_across_store_instances(self) -> None:
        other = runtime_store.RuntimeStore(self.root / "cache", clock=self.clock)
        recipe = self.recipe()
        first_builder_entered = threading.Event()
        release_builder = threading.Event()
        second_call_started = threading.Event()
        unexpected_second_builder = threading.Event()
        calls = 0
        calls_lock = threading.Lock()
        results: list[runtime_store.StoredRuntime] = []
        errors: list[BaseException] = []

        def builder(target: Path) -> None:
            nonlocal calls
            with calls_lock:
                calls += 1
                if calls > 1:
                    unexpected_second_builder.set()
            first_builder_entered.set()
            if not release_builder.wait(3):
                raise AssertionError("test did not release builder")
            (target / "profile.json").write_bytes(b"complete")

        def first_call() -> None:
            try:
                with self.store.get_or_create_lease(recipe, builder) as stored:
                    results.append(stored)
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

        def second_call() -> None:
            second_call_started.set()
            try:
                with other.get_or_create_lease(recipe, builder) as stored:
                    results.append(stored)
            except BaseException as exc:  # pragma: no cover - reported below
                errors.append(exc)

        first = threading.Thread(target=first_call)
        second = threading.Thread(target=second_call)
        first.start()
        self.assertTrue(first_builder_entered.wait(3))
        second.start()
        self.assertTrue(second_call_started.wait(3))
        self.assertFalse(unexpected_second_builder.wait(0.1))
        release_builder.set()
        first.join(3)
        second.join(3)

        self.assertFalse(first.is_alive())
        self.assertFalse(second.is_alive())
        self.assertEqual([], errors)
        self.assertEqual(1, calls)
        self.assertEqual(2, len(results))
        self.assertEqual(results[0].tree_digest, results[1].tree_digest)
        self.assertEqual(1, self.store.metrics.misses)
        self.assertEqual(1, other.metrics.hits)

    def test_failed_builder_leaves_no_recipe_or_reusable_staging_tree(self) -> None:
        recipe = self.recipe()

        def fail(target: Path) -> None:
            (target / "partial.txt").write_text("partial", encoding="utf-8")
            raise RuntimeError("installer failed")

        with self.assertRaisesRegex(RuntimeError, "installer failed"):
            with self.store.get_or_create_lease(recipe, fail):
                self.fail("failed builder unexpectedly returned")

        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        self.assertEqual([], list(self.store.tmp_dir.glob(f"build-{recipe.digest()}-*")))
        self.assertIsNone(self.store.lookup(recipe))

    def test_contained_symlink_is_materialized_as_a_regular_file(self) -> None:
        """Mojang's bundled Java runtimes ship internal relative links."""

        source = self.source_tree("source", {"legal/java.base/LICENSE": b"data"})
        link = source / "legal" / "java.compiler"
        link.mkdir(parents=True, exist_ok=True)
        try:
            (link / "LICENSE").symlink_to(Path("..") / "java.base" / "LICENSE")
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        recipe = self.recipe()

        stored = self.store.publish(recipe, source)

        entries = {entry.path: entry for entry in stored.manifest.entries}
        self.assertIn("legal/java.compiler/LICENSE", entries)
        self.assertEqual(
            entries["legal/java.base/LICENSE"].sha256,
            entries["legal/java.compiler/LICENSE"].sha256,
            "identical link content must deduplicate onto one blob",
        )

        destination = self.store.materialize(stored, self.root / "run" / "client")
        restored = destination / "legal" / "java.compiler" / "LICENSE"
        self.assertFalse(restored.is_symlink(), "store must never restore a link")
        self.assertEqual(b"data", restored.read_bytes())

    def test_escaping_symlink_is_rejected_without_publishing_a_recipe(self) -> None:
        outside = self.root / "outside.txt"
        outside.write_bytes(b"secret")
        source = self.source_tree("source", {"real.txt": b"data"})
        try:
            (source / "escape.txt").symlink_to(outside)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        recipe = self.recipe()

        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "escaping symbolic link"
        ):
            self.store.publish(recipe, source)

        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        with self.assertRaises(runtime_store.StoreCorruptionError):
            runtime_store.TreeEntry("../escape", 1, "0" * 64, 0o644)

    def test_escaping_symlink_containment_is_component_wise_not_lexical(self) -> None:
        """A sibling whose name merely extends the root is still outside it."""

        sibling = self.root / "source-evil"
        sibling.mkdir()
        (sibling / "id_rsa").write_bytes(b"PRIVATE KEY MATERIAL")
        source = self.source_tree("source", {"real.txt": b"data"})
        try:
            (source / "leak.txt").symlink_to(Path("..") / "source-evil" / "id_rsa")
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        recipe = self.recipe()

        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "escaping symbolic link"
        ):
            self.store.publish(recipe, source)

        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        self.assertEqual([], self.blob_files())

    def test_symlink_to_a_directory_is_rejected(self) -> None:
        source = self.source_tree("source", {"real/file.txt": b"data"})
        try:
            (source / "linkdir").symlink_to(source / "real")
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")
        recipe = self.recipe()

        with self.assertRaisesRegex(
            runtime_store.InvalidRuntimeTreeError, "does not resolve to a regular file"
        ):
            self.store.publish(recipe, source)

        self.assertFalse(self.store.path_for_recipe(recipe).exists())

    def test_corrupt_blob_is_a_miss_and_can_be_repaired_atomically(self) -> None:
        source = self.source_tree("source", {"profile.json": b"valid profile"})
        recipe = self.recipe()
        stored = self.store.publish(recipe, source)
        blob = self.store.path_for_blob(stored.manifest.entries[0].sha256)
        os.chmod(blob, 0o640)
        blob.write_bytes(b"corrupt")

        self.assertIsNone(self.store.lookup(recipe))
        with self.assertRaisesRegex(runtime_store.StoreCorruptionError, "blob"):
            self.store.validate(recipe)

        repaired = self.store.publish(recipe, source)
        self.assertEqual(stored.tree_digest, repaired.tree_digest)
        self.store.validate(repaired)
        self.assertEqual(b"valid profile", blob.read_bytes())

    def test_runtime_lease_prevents_age_and_size_pruning(self) -> None:
        source = self.source_tree("source", {"profile.json": b"profile"})
        recipe = self.recipe()
        stored = self.store.publish(recipe, source)
        self.clock.set(100)

        with self.store.lease(recipe):
            result = self.store.gc(max_age=0, max_bytes=0)
            self.assertEqual(0, result.pruned)
            self.assertTrue(self.store.path_for_recipe(recipe).is_file())
            self.assertTrue(self.store.path_for_tree(stored.tree_digest).is_file())
            self.assertTrue(self.store.path_for_blob(stored.manifest.entries[0].sha256).is_file())

        self.clock.set(101)
        result = self.store.gc(max_age=1, max_bytes=0)
        self.assertEqual(1, result.pruned)
        self.assertEqual(1, result.pruned_trees)
        self.assertEqual(1, result.pruned_blobs)
        self.assertFalse(self.store.path_for_recipe(recipe).exists())
        self.assertEqual(1, self.store.metrics.pruned)
        self.assertEqual(len(b"profile"), self.store.metrics.pruned_bytes)

    def test_gc_retries_read_only_quarantined_content_on_the_next_run(self) -> None:
        source = self.source_tree("readonly-source", {"profile.json": b"profile"})
        recipe = self.recipe()
        self.store.publish(recipe, source)

        with mock.patch.object(
            runtime_store,
            "_make_writable",
            side_effect=PermissionError("injected Windows-style read-only failure"),
        ):
            result = self.store.gc(max_bytes=0)

        self.assertEqual(1, result.pruned)
        self.assertEqual(1, result.pruned_trees)
        self.assertEqual(1, result.pruned_blobs)
        self.assertEqual(3, len(list(self.store.trash_dir.iterdir())))

        retry = self.store.gc(max_bytes=0)
        self.assertEqual(0, retry.pruned)
        self.assertEqual([], list(self.store.trash_dir.iterdir()))

    def test_max_bytes_uses_lru_access_and_preserves_shared_blobs(self) -> None:
        first_source = self.source_tree(
            "first", {"common.bin": b"same", "first.bin": b"a"}
        )
        second_source = self.source_tree(
            "second", {"common.bin": b"same", "second.bin": b"bb"}
        )
        first_recipe = self.recipe(loader_version="21.4.100")
        second_recipe = self.recipe(loader_version="21.4.200")
        first = self.store.publish(first_recipe, first_source)
        self.clock.set(1)
        second = self.store.publish(second_recipe, second_source)
        self.clock.set(2)
        self.assertIsNotNone(self.store.lookup(second_recipe))

        common_digest = next(
            entry.sha256 for entry in first.manifest.entries if entry.path == "common.bin"
        )
        result = self.store.gc(max_bytes=6)
        self.assertEqual(1, result.pruned)
        self.assertFalse(self.store.path_for_recipe(first_recipe).exists())
        self.assertTrue(self.store.path_for_recipe(second_recipe).is_file())
        self.assertTrue(self.store.path_for_blob(common_digest).is_file())
        self.assertEqual(6, result.retained_bytes)

        result = self.store.gc(max_bytes=0)
        self.assertEqual(1, result.pruned)
        self.assertEqual(0, result.retained_bytes)
        self.assertFalse(self.store.path_for_tree(second.tree_digest).exists())
        self.assertFalse(self.store.path_for_blob(common_digest).exists())

    def test_corrupt_lease_stops_gc_before_it_touches_recipes_or_lease(self) -> None:
        source = self.source_tree("source", {"profile.json": b"profile"})
        recipe = self.recipe()
        stored = self.store.publish(recipe, source)
        corrupt_lease = self.store.leases_dir / "active" / "bad.json"
        corrupt_lease.write_text("{not-json\n", encoding="utf-8")

        with self.assertRaises(runtime_store.StoreCorruptionError):
            self.store.gc(max_age=0, max_bytes=0)

        self.assertTrue(corrupt_lease.is_file())
        self.assertTrue(self.store.path_for_recipe(recipe).is_file())
        self.assertTrue(self.store.path_for_tree(stored.tree_digest).is_file())
        self.assertTrue(self.store.path_for_blob(stored.manifest.entries[0].sha256).is_file())


if __name__ == "__main__":
    unittest.main()
