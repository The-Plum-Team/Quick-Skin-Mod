from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "e2e"))

import runtime_store_cache as cache


MATRIX = {
    "installers": {
        "neoforge-21.1.77": {
            "url": "https://example.invalid/neoforge.jar",
            "sha256": "a" * 64,
        },
        "forge-1.20.1": {
            "url": "https://example.invalid/forge.jar",
            "sha256": "b" * 64,
        },
    }
}
NEOFORGE_ROW = {
    "installer": "neoforge-21.1.77",
    "java": 21,
    "loader": "neoforge",
    "runtime_version": "1.21.1",
    "loader_version": "21.1.77",
}
FABRIC_ROW = {
    "installer": "neoforge-21.1.77",
    "java": 17,
    "loader": "fabric",
    "runtime_version": "1.20.1",
    "loader_version": "0.17.3",
}


class RuntimeStoreCacheIdentityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def key(self, **changes: object) -> str:
        row = {**NEOFORGE_ROW, **changes}
        return cache.cache_key(MATRIX, row, os_name="linux", architecture="x86_64")

    def test_key_is_stable_and_covers_every_installation_input(self) -> None:
        original = self.key()
        self.assertIsNotNone(cache.CACHE_KEY.fullmatch(original))
        self.assertEqual(original, self.key())
        for changes in (
            {"java": 17},
            {"loader": "forge", "installer": "forge-1.20.1"},
            {"runtime_version": "1.21.2"},
            {"loader_version": "21.1.78"},
            {"installer": "forge-1.20.1"},
        ):
            with self.subTest(**changes):
                self.assertNotEqual(original, self.key(**changes))
        self.assertNotEqual(
            original,
            cache.cache_key(MATRIX, NEOFORGE_ROW, os_name="windows", architecture="x86_64"),
        )
        self.assertNotEqual(
            original,
            cache.cache_key(MATRIX, NEOFORGE_ROW, os_name="linux", architecture="aarch64"),
        )

    def test_key_carries_no_source_identity(self) -> None:
        # A commit never enters the key: the same installed tree stays valid across commits
        # that keep the same matrix row and installer.
        digest = self.key().rsplit("|", 1)[1]
        self.assertEqual(64, len(digest))
        self.assertTrue(self.key().startswith("runtime-store-v1|linux|x86_64|"))

    def test_only_maven_resolving_loaders_travel(self) -> None:
        self.assertTrue(cache.transported(NEOFORGE_ROW))
        self.assertTrue(cache.transported({**NEOFORGE_ROW, "loader": "forge"}))
        # Fabric fetches the vanilla server through Mojang's hash-pinned manifest.
        self.assertFalse(cache.transported(FABRIC_ROW))

    def test_only_immutable_material_travels(self) -> None:
        paths = cache.store_paths(self.root / "server-store")
        self.assertEqual(
            [
                str(self.root / "server-store" / "RuntimeStore" / "v1" / name)
                for name in ("blobs", "recipes", "trees")
            ],
            paths,
        )
        for machine_local in ("leases", "tmp", "trash"):
            self.assertFalse(any(path.endswith(f"/{machine_local}") for path in paths))

    def test_malformed_row_or_host_fails_closed(self) -> None:
        for bad in ([], "row", None, {}, {"loader": ""}, {"loader": 3}):
            with self.subTest(row=bad), self.assertRaises(cache.CacheIdentityError):
                cache.transported(bad)
        for host in ("", "  ", "linux/x86", "linux x86", "Linux|x86"):
            with self.subTest(host=host), self.assertRaises(cache.CacheIdentityError):
                cache.cache_key(MATRIX, NEOFORGE_ROW, os_name=host, architecture="x86_64")
        with self.assertRaises(cache.CacheIdentityError):
            cache.cache_key({"installers": {}}, NEOFORGE_ROW, os_name="linux", architecture="x86_64")

    def test_every_key_covers_the_real_matrix_without_fabric(self) -> None:
        matrix = json.loads((ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8"))
        keys = cache.every_key(matrix, os_name="linux", architecture="x86_64")
        self.assertEqual(sorted(set(keys)), keys)
        self.assertTrue(keys)
        for key in keys:
            self.assertIsNotNone(cache.CACHE_KEY.fullmatch(key))
        loaders = {artifact["loader"] for artifact in matrix["artifacts"]}
        expected = len(
            {
                (artifact["artifact_version"], artifact["loader"])
                for artifact in matrix["artifacts"]
                if artifact["loader"] in cache.TRANSPORTED_LOADERS
            }
        )
        self.assertEqual(expected, len(keys))
        self.assertIn("fabric", loaders)  # Fabric rows exist and deliberately contribute no key.

    def test_cli_emits_a_delimited_multiline_workflow_output(self) -> None:
        output = self.root / "github-output"
        code = cache.main(
            [
                "--matrix",
                str(ROOT / "release" / "release-matrix.json"),
                "--row-json",
                json.dumps(NEOFORGE_ROW),
                "--store-root",
                str(self.root / "server-store"),
                "--os",
                "linux",
                "--arch",
                "x86_64",
                "--github-output",
                str(output),
            ]
        )
        self.assertEqual(0, code)
        lines = output.read_text(encoding="utf-8").splitlines()
        self.assertEqual("transported=true", lines[0])
        self.assertTrue(lines[1].startswith("key=runtime-store-v1|linux|x86_64|"))
        delimiter = lines[2].split("<<", 1)[1]
        self.assertEqual(delimiter, lines[-1])
        self.assertEqual(3, len(lines[3:-1]))

    def test_cli_reports_an_untravelled_row_and_fails_closed_on_bad_input(self) -> None:
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            code = cache.main(
                [
                    "--matrix",
                    str(ROOT / "release" / "release-matrix.json"),
                    "--row-json",
                    json.dumps(FABRIC_ROW),
                ]
            )
        self.assertEqual(0, code)
        self.assertIn("transported=false", stdout.getvalue())
        self.assertIn("key=\n", stdout.getvalue())

        stderr = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(stderr):
            code = cache.main(
                [
                    "--matrix",
                    str(ROOT / "release" / "release-matrix.json"),
                    "--row-json",
                    "not json",
                ]
            )
        self.assertEqual(2, code)
        self.assertIn("Runtime store cache identity error", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
