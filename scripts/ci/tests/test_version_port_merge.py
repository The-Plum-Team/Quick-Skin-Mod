from __future__ import annotations

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
