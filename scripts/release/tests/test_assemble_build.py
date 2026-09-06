from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/release"))
import assemble_build
import build_matrix
import matrix as release_matrix
import verify_release


class AssembleBuildTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name).resolve()
        self.commit = "a" * 40
        data = release_matrix.load_matrix(ROOT / "release/release-matrix.json")
        self.versions = [item["target"] for item in build_matrix.build_plan(data)]
        self.data = data
        self.matrix_path = self.repo / "release/release-matrix.json"
        self.matrix_path.parent.mkdir()
        self.matrix_path.write_text(json.dumps(data) + "\n")
        shutil.copy2(ROOT / "gradle.properties", self.repo / "gradle.properties")
        locks = self.repo / "gradle/dependency-locks"
        locks.mkdir(parents=True)
        for row in data["artifacts"]:
            name = f"{row['loader']}-{row['artifact_version']}.lockfile"
            shutil.copy2(ROOT / "gradle/dependency-locks" / name, locks / name)
        shutil.copy2(ROOT / "gradle/verification-metadata.xml", self.repo / "gradle/verification-metadata.xml")
        self.mod_version = release_matrix.read_mod_version(self.matrix_path, data)
        self.inputs = self.repo / "build/compiled-targets"
        self.stage = self.repo / "build/release"
        self.outputs = []
        for row in data["artifacts"]:
            for kind in ("jar", "harness_jar"):
                path = self.repo / row[kind].replace("{mod_version}", self.mod_version)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(f"{row['artifact_node']}:{kind}".encode())
                self.outputs.append(path)
        # Source-root validation has its own matrix tests; this fixture supplies the validated
        # inventory while retaining the real manifest, artifact-hash, target and SBOM validators.
        for mock in (patch.object(release_matrix, "load_matrix", return_value=self.data),
                     patch.object(verify_release, "git_commit", return_value=self.commit),
                     patch.object(verify_release, "verify_jar", side_effect=self.jar_metadata),
                     patch.object(verify_release, "verify_harness", side_effect=self.harness_metadata),
                     patch.dict(os.environ, {"GITHUB_SHA": self.commit})):
            mock.start()
            self.addCleanup(mock.stop)
        for version in self.versions:
            directory = self.inputs / (assemble_build.TARGET_PREFIX + version)
            manifest_path = directory / "artifacts.json"
            manifest = verify_release.build_manifest(self.repo, self.matrix_path, directory,
                manifest_path, self.mod_version, self.data, target=version)
            build_matrix.write_report(manifest_path, manifest)
        for path in self.outputs:
            path.unlink()

    @staticmethod
    def jar_metadata(path, *_args):
        return {"filename": path.name, "bytes": path.stat().st_size,
                **{kind: verify_release.file_digest(path, kind) for kind in ("sha1", "sha256", "sha512")}}

    @staticmethod
    def harness_metadata(path, *_args):
        return {"filename": path.name, "bytes": path.stat().st_size,
                "sha256": verify_release.sha256(path)}

    def assemble(self):
        return assemble_build.assemble(self.repo, self.inputs, self.stage)

    def manifest(self, index=0):
        return self.inputs / (assemble_build.TARGET_PREFIX + self.versions[index]) / "artifacts.json"

    def assert_rejected_before_output_copy(self):
        with self.assertRaises((ValueError, RuntimeError)):
            self.assemble()
        self.assertFalse(any(path.exists() for path in self.outputs))
        self.assertFalse((self.stage / "artifacts.json").exists())

    def test_complete_target_partition_reconstructs_one_full_bundle_and_sbom(self):
        result = self.assemble()
        self.assertEqual(self.data["lane_count"], result["lane_count"])
        self.assertEqual(self.commit, result["git_commit"])
        self.assertEqual(self.versions, result["release"]["minecraft_versions"])
        self.assertTrue(result["release"]["release_id"].startswith("build-v"))
        self.assertTrue(all(path.is_file() for path in self.outputs))
        verify_release.verify_staged_manifest(self.repo, self.stage, self.stage / "artifacts.json",
            result, self.data, self.matrix_path, self.mod_version, self.commit)

    def test_missing_target_cannot_certify_the_complete_matrix(self):
        shutil.rmtree(self.manifest(1).parent)
        self.assert_rejected_before_output_copy()

    def test_extra_or_duplicate_target_directory_is_rejected(self):
        shutil.copytree(self.manifest().parent, self.inputs / "compiled-target-extra")
        self.assert_rejected_before_output_copy()

    def test_target_cannot_supply_another_target_manifest(self):
        shutil.copy2(self.manifest(), self.manifest(1))
        self.assert_rejected_before_output_copy()

    def test_different_commit_or_matrix_is_rejected_before_any_copy(self):
        path = self.manifest(1)
        original = path.read_text()
        for field in ("git_commit", "matrix_sha256"):
            with self.subTest(field=field):
                data = json.loads(original)
                data[field] = "f" * len(data[field])
                path.write_text(json.dumps(data))
                self.assert_rejected_before_output_copy()
        path.write_text(original)

    def test_tampered_jar_or_harness_is_rejected_before_copying_other_valid_targets(self):
        manifest = json.loads(self.manifest(1).read_text())
        for record in (manifest["artifacts"][0], manifest["artifacts"][0]["harness"]):
            with self.subTest(path=record["path"]):
                path = self.manifest(1).parent / record["path"]
                original = path.read_bytes()
                path.write_bytes(original + b"tampered")
                self.assert_rejected_before_output_copy()
                path.write_bytes(original)

    def test_linked_bundle_or_destination_cannot_redirect_assembly(self):
        path = self.manifest(1).parent
        saved = self.repo / "saved-target"
        path.rename(saved)
        path.symlink_to(saved, target_is_directory=True)
        self.assert_rejected_before_output_copy()
        path.unlink()
        saved.rename(path)
        outside = self.repo / "outside.jar"
        outside.write_bytes(b"keep")
        self.outputs[-1].symlink_to(outside)
        with self.assertRaisesRegex(RuntimeError, "linked"):
            self.assemble()
        self.assertEqual(b"keep", outside.read_bytes())


if __name__ == "__main__":
    unittest.main()
