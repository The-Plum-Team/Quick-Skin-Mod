from __future__ import annotations

import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import recover_sbom_release as recovery
import test_sbom
from matrix import gha_matrix, load_matrix, select_release_target
from release_identity import derive


class SbomRecoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        path = ROOT / "release" / "release-matrix.json"
        self.identity = derive(path, target="26.1")
        self.matrix = select_release_target(load_matrix(path), "26.1")
        self.source = "a" * 40
        self.policy = "b" * 40
        self.api = Mock(repository="owner/repository")
        self.api.current_sha.return_value = self.policy
        self.run = {
            "id": 123, "path": recovery.WORKFLOW, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": self.source,
            "repository": {"full_name": self.api.repository},
            "head_repository": {"full_name": self.api.repository},
            "status": "completed", "conclusion": "success",
        }
        self.jobs = [{
            "name": "Build immutable release bundle", "status": "completed", "conclusion": "success",
            "steps": [{"name": name, "conclusion": "success"} for name in (
                "Validate requested release identity", "Stage and verify all production artifacts",
                "Prove first and second build bytes are identical")],
        }]
        for row in gha_matrix(self.matrix, "runtime", self.identity.mod_version)["include"]:
            self.jobs.append({"name": f"{row['id']} - packaged release behavior",
                              "status": "completed", "conclusion": "success"})
        for name in ("Stage exact GitHub Release assets", "Publish immutable GitHub Release"):
            self.jobs.append({"name": name, "status": "completed", "conclusion": "skipped"})
        self.artifact = {
            "id": 456, "name": f"release-{self.identity.release_id}", "expired": False,
            "workflow_run": {"id": 123, "head_sha": self.source},
            "size_in_bytes": 1024, "digest": "sha256:" + "c" * 64,
        }

    def admit(self) -> dict:
        self.api.run.return_value = self.run
        self.api.jobs.return_value = [{"jobs": self.jobs}]
        self.api.artifact.return_value = self.artifact
        self.api.artifacts.return_value = [self.artifact]
        return recovery.authenticate_rehearsal(
            self.api, 123, 456, self.identity, self.matrix, self.source,
        )

    def test_accepts_the_complete_same_source_rehearsal(self) -> None:
        self.assertEqual(self.admit(), self.artifact)

    def test_rejects_foreign_failed_or_publishing_source(self) -> None:
        original = copy.deepcopy(self.run)
        for field, value in (("head_sha", "d" * 40), ("event", "push"),
                             ("head_branch", "untrusted"), ("conclusion", "failure"),
                             ("head_repository", {"full_name": "attacker/fork"})):
            with self.subTest(field=field):
                self.run = {**original, field: value}
                with self.assertRaisesRegex(ValueError, "successful release rehearsal"):
                    self.admit()

    def test_rejects_missing_failed_or_duplicate_runtime_lane(self) -> None:
        original = copy.deepcopy(self.jobs)
        cases = [original[:1] + original[2:], original + [original[1]], copy.deepcopy(original)]
        cases[-1][1]["conclusion"] = "failure"
        for jobs in cases:
            with self.subTest(jobs=jobs):
                self.jobs = jobs
                with self.assertRaises(ValueError):
                    self.admit()

    def test_requires_reproducible_build_and_no_rehearsal_publication(self) -> None:
        self.jobs[0]["steps"][-1]["conclusion"] = "skipped"
        with self.assertRaisesRegex(ValueError, "rehearsal lacks"):
            self.admit()
        self.jobs[0]["steps"][-1]["conclusion"] = "success"
        self.jobs[-1]["conclusion"] = "success"
        with self.assertRaisesRegex(ValueError, "attempted publication"):
            self.admit()

    def test_rejects_changed_expired_or_oversized_archive_identity(self) -> None:
        original = copy.deepcopy(self.artifact)
        for field, value in (("expired", True), ("size_in_bytes", recovery.MAX_ARCHIVE_BYTES + 1),
                             ("size_in_bytes", True), ("digest", "sha256:wrong"),
                             ("name", "staged-release-bundle"),
                             ("workflow_run", {"id": 987, "head_sha": self.source})):
            with self.subTest(field=field, value=value):
                self.artifact = {**original, field: value}
                with self.assertRaisesRegex(ValueError, "release archive"):
                    self.admit()

    def test_rejects_production_version_build_or_workflow_changes(self) -> None:
        safe = ["scripts/release/generate_sbom.py", "RELEASING.md"]
        recovery.check_source_diff(safe)
        for path in ("gradle.properties", "release/release-matrix.json", "CHANGELOG.md",
                     "modules/skin-menu/src/main/java/Screen.java", "fabric/build.gradle.kts",
                     ".github/workflows/release.yml", "e2e/scenario-contract.json"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "outside"):
                recovery.check_source_diff([*safe, path])
        with self.assertRaises(ValueError):
            recovery.check_source_diff([])

    def test_only_adds_the_missing_serial_number_to_canonical_original_bytes(self) -> None:
        fixture = test_sbom.CycloneDxSbomTest()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        corrected = json.loads(fixture.build())
        original = copy.deepcopy(corrected)
        original.pop("serialNumber")
        payload = (json.dumps(original, ensure_ascii=False, sort_keys=True,
                              separators=(",", ":")) + "\n").encode()
        self.assertEqual(json.loads(recovery.repair_document(payload, corrected)), corrected)
        for corrupt in (payload.replace(b'"CycloneDX"', b'"Other"'),
                        payload + b" ", fixture.build()):
            with self.subTest(payload=corrupt[:40]), self.assertRaisesRegex(ValueError, "more than"):
                recovery.repair_document(corrupt, corrected)

    def test_authenticates_current_protected_policy_and_both_tag_copies(self) -> None:
        def git(*args: str) -> str:
            if args == ("rev-parse", "HEAD"):
                return self.policy
            if args[0] == "status":
                return ""
            if args[0] in {"rev-parse", "merge-base"}:
                return self.source
            if args[0] == "diff":
                return "scripts/release/generate_sbom.py\nRELEASING.md"
            raise AssertionError(args)

        self.api.json.return_value = {"ref": f"refs/tags/{self.identity.tag}",
                                      "object": {"type": "commit", "sha": self.source}}
        with patch.object(recovery, "git", side_effect=git):
            identity, _, source = recovery.authenticate_identity(self.api, self.identity.tag, self.policy)
            self.assertEqual((identity.tag, source), (self.identity.tag, self.source))
            self.api.current_sha.return_value = "e" * 40
            with self.assertRaisesRegex(ValueError, "exact current protected"):
                recovery.authenticate_identity(self.api, self.identity.tag, self.policy)
            self.api.current_sha.return_value = self.policy
            self.api.json.return_value["object"]["sha"] = "d" * 40
            with self.assertRaisesRegex(ValueError, "local and remote"):
                recovery.authenticate_identity(self.api, self.identity.tag, self.policy)


if __name__ == "__main__":
    unittest.main()
