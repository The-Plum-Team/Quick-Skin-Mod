from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import matrix as release_matrix  # noqa: E402
import release_identity  # noqa: E402


class UnifiedReleaseIdentityTest(unittest.TestCase):
    def setUp(self):
        self.path = ROOT / "release/release-matrix.json"
        self.data = release_matrix.load_matrix(self.path)
        self.target = next(row["artifact_version"] for row in self.data["artifacts"]
                           if row["artifact_version"] != self.data["unit_test_version"])

    def test_one_source_branch_has_independent_target_publication_identities(self):
        bundle = release_identity.derive(self.path, self.data)
        self.assertEqual("build-v3.0.0", bundle.tag)
        self.assertEqual({row["artifact_version"] for row in self.data["artifacts"]},
                         set(bundle.minecraft_versions))
        selected = release_identity.derive(self.path, self.data, target=self.target)
        self.assertEqual(f"mc{self.target}-v3.0.0", selected.tag)
        self.assertEqual("master", selected.branch)
        self.assertEqual((self.target,), selected.minecraft_versions)
        rows = release_matrix.gha_matrix(self.data, "publications", "3.0.0")["include"]
        self.assertEqual(self.data["lane_count"] * 2, len(rows))
        for row in rows:
            self.assertEqual(f"mc{row['artifact_version']}-v3.0.0", row["release_id"])
            self.assertTrue(row["publication_id"].startswith(row["release_id"] + "-"))

    def test_a_build_bundle_cannot_be_published_as_a_target(self):
        for event, ref_type in (("push", "tag"), ("workflow_dispatch", "branch")):
            bundle = release_identity.derive(self.path, self.data)
            with self.subTest(event=event), self.assertRaisesRegex(release_identity.ReleaseIdentityError, "cannot be published"):
                release_identity.validate_ci_event(bundle, event_name=event, ref_type=ref_type,
                    ref_name=bundle.tag if ref_type == "tag" else "master", event_commit="a" * 40,
                    checkout_commit="a" * 40, release_branch_head="a" * 40)
        selected = release_identity.derive(self.path, self.data, target=self.target)
        release_identity.validate_ci_event(selected, event_name="push", ref_type="tag", ref_name=selected.tag,
            event_commit="a" * 40, checkout_commit="a" * 40, release_branch_head="a" * 40)

    def test_target_view_is_exact_and_does_not_mutate_the_authoritative_matrix(self):
        before = copy.deepcopy(self.data)
        selected = release_matrix.select_release_target(self.data, self.target)
        expected = [row for row in self.data["artifacts"] if row["artifact_version"] == self.target]
        self.assertEqual(len(expected), selected["lane_count"])
        self.assertEqual({"common", *(row["loader"] for row in expected)}, set(selected["source_overlays"]))
        self.assertEqual({self.target: self.data["source_overlays"]["common"][self.target]},
                         selected["source_overlays"]["common"])
        self.assertEqual({row["installer"] for row in selected["runtimes"]}, set(selected["installers"]))
        self.assertEqual(self.target, selected["unit_test_version"])
        selected["project"]["description"] = "independent view"
        self.assertEqual(before, self.data)
        with self.assertRaisesRegex(release_matrix.MatrixError, "unknown release target"):
            release_matrix.select_release_target(self.data, "unsupported")
        self.data["artifacts"][0]["java"] = 0
        with self.assertRaises(release_matrix.MatrixError):
            release_matrix.select_release_target(self.data, self.target)

    def test_schema3_requires_shared_source_and_allows_one_api_family_for_multiple_targets(self):
        self.data["source_overlays"]["common"][self.target] = next(iter(self.data["source_overlays"]["common"].values()))
        release_matrix.validate_matrix(self.data)
        for schema, branch in ((3, "fabric-and-forge-1.20.1"), (3.0, "master"), (True, "master")):
            with self.subTest(schema=schema, branch=branch), self.assertRaises(release_matrix.MatrixError):
                self.data["schema_version"] = schema
                self.data["project"]["release_branch"] = branch
                release_matrix.validate_matrix(self.data)


class ReleaseIdentityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.matrix_path = ROOT / "release" / "release-matrix.json"
        # Historical consumer compatibility is separate from the active support inventory.
        cls.legacy = json.loads((Path(__file__).parent / "fixtures/legacy-schema2-matrix.json").read_bytes())
        cls.identity = release_identity.derive(cls.matrix_path, cls.legacy)

    def test_identity_names_minecraft_era_and_logical_mod_version(self) -> None:
        self.assertEqual(self.identity.release_id, "mc1.20.1-v3.0.0")
        self.assertEqual(self.identity.tag, self.identity.release_id)
        self.assertEqual(self.identity.branch, "forge-and-fabric-1.20.1")

    def test_publication_matrix_is_artifact_times_marketplace(self) -> None:
        data = copy.deepcopy(self.legacy)
        matrix = release_matrix.gha_matrix(data, "publications", "3.0.0")
        rows = matrix["include"]
        self.assertEqual(len(rows), data["lane_count"] * 2)
        self.assertEqual(
            {(row["artifact_node"], row["marketplace"]) for row in rows},
            {
                (artifact["artifact_node"], marketplace)
                for artifact in data["artifacts"]
                for marketplace in ("modrinth", "curseforge")
            },
        )
        self.assertTrue(all(row["publication_id"].startswith(self.identity.release_id) for row in rows))

    def test_tag_and_manual_events_bind_exact_branch_head(self) -> None:
        commit = "a" * 40
        release_identity.validate_ci_event(
            self.identity,
            event_name="push",
            ref_type="tag",
            ref_name=self.identity.tag,
            event_commit=commit,
            checkout_commit=commit,
            release_branch_head=commit,
        )
        release_identity.validate_ci_event(
            self.identity,
            event_name="workflow_dispatch",
            ref_type="branch",
            ref_name=self.identity.branch,
            event_commit=commit,
            checkout_commit=commit,
            release_branch_head=commit,
        )

    def test_rejects_ambiguous_or_stale_release_sources(self) -> None:
        commit = "a" * 40
        cases = (
            {"event_name": "push", "ref_type": "tag", "ref_name": "v3.0.0"},
            {
                "event_name": "workflow_dispatch",
                "ref_type": "branch",
                "ref_name": "master",
            },
        )
        for overrides in cases:
            with self.subTest(overrides=overrides), self.assertRaises(
                release_identity.ReleaseIdentityError
            ):
                release_identity.validate_ci_event(
                    self.identity,
                    event_name=overrides["event_name"],
                    ref_type=overrides["ref_type"],
                    ref_name=overrides["ref_name"],
                    event_commit=commit,
                    checkout_commit=commit,
                    release_branch_head=commit,
                )
        with self.assertRaises(release_identity.ReleaseIdentityError):
            release_identity.validate_ci_event(
                self.identity,
                event_name="push",
                ref_type="tag",
                ref_name=self.identity.tag,
                event_commit=commit,
                checkout_commit=commit,
                release_branch_head="b" * 40,
            )

    def test_matrix_rejects_a_release_branch_for_other_loaders(self) -> None:
        data = copy.deepcopy(self.legacy)
        data["project"] = dict(data["project"])
        data["project"]["release_branch"] = "fabric-and-neoforge-1.20.1"
        with self.assertRaisesRegex(
            release_matrix.MatrixError,
            "loaders disagree",
        ):
            release_matrix.validate_matrix(data)

    def test_changelog_must_match_and_tag_publication_must_be_dated(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            changelog = Path(temporary) / "CHANGELOG.md"
            changelog.write_text(
                "# Changelog\n\n## 3.0.0 (unreleased)\n\n### Added\n\n- Feature.\n",
                encoding="utf-8",
            )
            self.assertEqual(
                release_identity.validate_changelog(changelog, "3.0.0"),
                "unreleased",
            )
            with self.assertRaisesRegex(
                release_identity.ReleaseIdentityError, "requires an ISO date"
            ):
                release_identity.validate_changelog(
                    changelog, "3.0.0", publication=True
                )
            changelog.write_text(
                "# Changelog\n\n## 3.0.0 (2026-08-02)\n\n### Added\n\n- Feature.\n",
                encoding="utf-8",
            )
            self.assertEqual(
                release_identity.validate_changelog(
                    changelog, "3.0.0", publication=True
                ),
                "2026-08-02",
            )

    def test_changelog_rejects_wrong_or_empty_latest_release(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            changelog = Path(temporary) / "CHANGELOG.md"
            for text, message in (
                ("# Changelog\n\n## 2.9.0\n\n### Fixed\n\n- Old.\n", "does not equal"),
                ("# Changelog\n\n## 3.0.0 (unreleased)\n", "section is empty"),
            ):
                changelog.write_text(text, encoding="utf-8")
                with self.subTest(message=message), self.assertRaisesRegex(
                    release_identity.ReleaseIdentityError, message
                ):
                    release_identity.validate_changelog(changelog, "3.0.0")


if __name__ == "__main__":
    unittest.main()
