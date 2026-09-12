from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import github_release  # noqa: E402


class GitHubReleaseContractTest(unittest.TestCase):
    def fixture(self, root: Path) -> tuple[Path, Path, str, str]:
        stage = root / "build" / "release"
        file = stage / "files" / "Quick Skin.jar"
        file.parent.mkdir(parents=True)
        file.write_bytes(b"verified-jar")
        sbom = stage / "sbom" / "quick-skin.cdx.json"
        sbom.parent.mkdir()
        sbom.write_bytes(b'{"bomFormat":"CycloneDX","specVersion":"1.6"}\n')
        commit = "a" * 40
        tag = "mc1.20.1-v3.0.0"
        manifest = stage / "artifacts.json"
        manifest.write_text(json.dumps({
            "schema_version": 2,
            "lane_count": 1,
            "git_commit": commit,
            "release": {"tag": tag},
            "artifacts": [{
                "artifact_node": "fabric-1.20.1",
                "filename": file.name,
                "path": "files/Quick Skin.jar",
                "sha256": hashlib.sha256(file.read_bytes()).hexdigest(),
            }],
            "sbom": {
                "format": "CycloneDX",
                "spec_version": "1.6",
                "filename": sbom.name,
                "path": "sbom/quick-skin.cdx.json",
                "bytes": sbom.stat().st_size,
                "sha256": hashlib.sha256(sbom.read_bytes()).hexdigest(),
            },
        }), encoding="utf-8")
        return stage, manifest, tag, commit

    def test_contract_binds_tag_commit_paths_and_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            contract = github_release.load_contract(manifest, stage, tag, commit)
            self.assertEqual(contract.tag, tag)
            self.assertEqual(len(contract.assets), 3)
            checksums = github_release.write_checksums(contract, stage)
            text = checksums.read_text(encoding="utf-8")
            self.assertIn("Quick Skin.jar", text)
            self.assertIn("quick-skin.cdx.json", text)
            self.assertIn("artifacts.json", text)

    def test_contract_rejects_changed_asset_or_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            (stage / "files" / "Quick Skin.jar").write_bytes(b"changed")
            with self.assertRaises(github_release.GitHubReleaseError):
                github_release.load_contract(manifest, stage, tag, commit)
            with self.assertRaises(github_release.GitHubReleaseError):
                github_release.load_contract(manifest, stage, "wrong-tag", commit)

    def test_contract_rejects_shared_build_even_with_matching_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, _, commit = self.fixture(Path(temporary))
            data = json.loads(manifest.read_text(encoding="utf-8"))
            data["release"]["tag"] = "build-v3.0.0"
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(github_release.GitHubReleaseError, "cannot be published"):
                github_release.load_contract(manifest, stage, "build-v3.0.0", commit)

    def test_contract_rejects_missing_or_changed_sbom(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            sbom = stage / "sbom" / "quick-skin.cdx.json"
            sbom.write_bytes(b"changed")
            with self.assertRaisesRegex(github_release.GitHubReleaseError, "SBOM release asset bytes"):
                github_release.load_contract(manifest, stage, tag, commit)

            data = json.loads(manifest.read_text(encoding="utf-8"))
            del data["sbom"]
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaisesRegex(github_release.GitHubReleaseError, "no CycloneDX SBOM"):
                github_release.load_contract(manifest, stage, tag, commit)

    def test_stage_resumes_normalized_uploads_and_publishes_only_verified_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            contract = github_release.load_contract(manifest, stage, tag, commit)
            checksums = github_release.write_checksums(contract, stage)
            remote_files = {
                "Quick.Skin.jar": stage / "files" / "Quick Skin.jar",
                "quick-skin.cdx.json": stage / "sbom" / "quick-skin.cdx.json",
                "artifacts.json": manifest,
                "SHA256SUMS": checksums,
            }
            remote = [
                {"id": index, "name": name}
                for index, name in enumerate(remote_files, start=1)
            ]
            uploaded = remote[1:]
            downloads = {row["id"]: remote_files[row["name"]].read_bytes() for row in remote}
            uploads: list[str] = []
            edits: list[list[str]] = []
            draft = {"databaseId": 123, "tagName": tag, "isDraft": True}

            def api(command: list[str], **kwargs: object) -> subprocess.CompletedProcess:
                if command[:3] == ["gh", "release", "upload"]:
                    uploads.append(command[4])
                    self.assertEqual(Path(command[4]).name, "Quick Skin.jar")
                    uploaded.append(remote[0])
                    return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
                if command[:3] == ["gh", "release", "edit"]:
                    edits.append(command)
                    return subprocess.CompletedProcess(command, 0, stdout="", stderr="")
                self.assertEqual(command[:2], ["gh", "api"])
                self.assertIs(kwargs.get("text"), False)
                asset_id = int(command[-1].rsplit("/", 1)[1])
                return subprocess.CompletedProcess(command, 0, stdout=downloads[asset_id], stderr=b"")

            with mock.patch.object(github_release, "assert_tag_commit"), mock.patch.object(
                github_release, "release_view", return_value=draft
            ) as view, mock.patch.object(
                github_release, "release_assets", side_effect=lambda *args: list(uploaded)
            ), mock.patch.object(github_release, "run", side_effect=api):
                github_release.stage_release("owner/repo", contract, "release", manifest, checksums)
                github_release.stage_release("owner/repo", contract, "release", manifest, checksums)
                self.assertEqual(len(uploads), 1)
                self.assertEqual(edits, [])
                view.side_effect = [draft, {**draft, "isDraft": False}]
                github_release.publish_release("owner/repo", contract, checksums)
                self.assertEqual(len(edits), 1)
                self.assertIn("--draft=false", edits[0])

    def test_normalized_asset_with_different_bytes_still_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            contract = github_release.load_contract(manifest, stage, tag, commit)
            checksums = github_release.write_checksums(contract, stage)
            expected = github_release.expected_remote_assets(contract, checksums)
            with mock.patch.object(
                github_release, "release_assets", return_value=[{"id": 1, "name": "Quick.Skin.jar"}]
            ), mock.patch.object(
                github_release, "run", return_value=subprocess.CompletedProcess([], 0, stdout=b"other-jar")
            ):
                with self.assertRaisesRegex(github_release.GitHubReleaseError, "different bytes"):
                    github_release.verify_remote_assets("owner/repo", 123, expected, allow_missing=True)

    def test_normalization_collision_is_rejected_before_creating_a_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage, manifest, tag, commit = self.fixture(Path(temporary))
            contract = github_release.load_contract(manifest, stage, tag, commit)
            checksums = github_release.write_checksums(contract, stage)
            alias = stage / "Quick.Skin.jar"
            alias.write_bytes(b"another-artifact")
            conflicting = github_release.ReleaseContract(
                tag, commit, (*contract.assets, alias),
                {**contract.hashes, alias.name: github_release.sha256(alias)},
            )
            with mock.patch.object(github_release, "assert_tag_commit"), mock.patch.object(
                github_release, "release_view"
            ) as view, mock.patch.object(github_release, "run") as mutation:
                with self.assertRaisesRegex(github_release.GitHubReleaseError, "collide"):
                    github_release.stage_release("owner/repo", conflicting, "release", manifest, checksums)
                view.assert_not_called()
                mutation.assert_not_called()


class ReleaseViewAfterCreateTest(unittest.TestCase):
    def test_view_after_create_waits_out_eventual_consistency(self) -> None:
        release = {"tagName": "mc1.20.1-v3.0.0", "isDraft": True}
        sleeps: list[float] = []
        with mock.patch.object(
            github_release, "release_view", side_effect=[None, None, release]
        ) as view:
            value = github_release.release_view_after_create(
                "mc1.20.1-v3.0.0", sleep=sleeps.append
            )
        self.assertEqual(value, release)
        self.assertEqual(view.call_count, 3)
        self.assertEqual(sleeps, [github_release.RELEASE_VIEW_DELAY_SECONDS] * 2)

    def test_view_after_create_stays_bounded_when_never_visible(self) -> None:
        sleeps: list[float] = []
        with mock.patch.object(
            github_release, "release_view", return_value=None
        ) as view:
            value = github_release.release_view_after_create(
                "mc1.20.1-v3.0.0", sleep=sleeps.append
            )
        self.assertIsNone(value)
        self.assertEqual(view.call_count, github_release.RELEASE_VIEW_ATTEMPTS)
        self.assertEqual(len(sleeps), github_release.RELEASE_VIEW_ATTEMPTS - 1)


if __name__ == "__main__":
    unittest.main()
