from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts" / "ci" / "e2e_impact.py"
SPEC = importlib.util.spec_from_file_location("e2e_impact", MODULE_PATH)
assert SPEC and SPEC.loader
impact = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = impact
SPEC.loader.exec_module(impact)


class E2EImpactTest(unittest.TestCase):
    def test_documentation_pages_and_separate_policy_can_skip_minecraft(self) -> None:
        result = impact.classify(
            [
                "docs/ai/PROJECT.md",
                "site/app.js",
                "scripts/ci/ai_patch_policy.py",
                "scripts/ci/github_api_retry.sh",
                "scripts/ci/mod_compatibility_review_batch.py",
                "scripts/ci/mod_compatibility_review_queue.py",
                "scripts/ci/visual_review_queue.py",
                "scripts/pages/collect_compatibility.py",
                "scripts/pages/compatibility_evidence.py",
                "scripts/pages/select_compatibility_artifact.py",
                "scripts/ci/tests/test_ai_patch_policy.py",
                "scripts/ci/tests/test_visual_review_queue.py",
                "scripts/release/tests/test_pages_compatibility.py",
            ]
        )
        self.assertFalse(result.runtime_required)
        self.assertEqual(result.runtime_paths, ())

    def test_runtime_contract_build_and_gate_workflow_changes_fail_closed(self) -> None:
        for path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            "common/src/e2e/java/com/quickskin/mod/e2e/E2EHarness.java",
            "e2e/scenario-contract.json",
            "e2e/loader-bootstrap-contract.json",
            "e2e/visual_evidence.py",
            "release/release-matrix.json",
            "gradle/e2e-harness-conventions.gradle.kts",
            ".github/workflows/on-demand-e2e.yml",
            ".github/workflows/build-gate.yml",
            ".github/workflows/verify-gate-attestation.yml",
            ".github/actions/run-packaged-e2e/action.yml",
            "ORACLE-RETIREMENT.md",
            "e2e/visual_review_prompt.md",
            "e2e/visual_review_semantic_prompt.md",
            "scripts/ci/e2e_impact.py",
            "scripts/ci/visual_anchor_certification.py",
        ):
            with self.subTest(path=path):
                result = impact.classify([path])
                self.assertTrue(result.runtime_required)
                self.assertEqual(result.runtime_paths, (path,))

    def test_non_gate_workflows_can_skip_minecraft_alone_and_with_documentation(self) -> None:
        non_gate_workflows = (
            ".github/workflows/handle-version-port-result.yml",
            ".github/workflows/mod-compatibility-e2e.yml",
            ".github/workflows/mod-compatibility-review.yml",
            ".github/workflows/pages.yml",
            ".github/workflows/prune-actions-caches.yml",
            ".github/workflows/refresh-release-status.yml",
            ".github/workflows/release.yml",
            ".github/workflows/sync-version-branches.yml",
            ".github/workflows/visual-review.yml",
            ".github/workflows/visual-review-drain.yml",
        )
        for path in non_gate_workflows:
            with self.subTest(path=path):
                result = impact.classify([path])
                self.assertFalse(result.runtime_required)
                self.assertEqual(result.runtime_paths, ())
        combined = impact.classify([*non_gate_workflows, "docs/ai/PROJECT.md", "README.md"])
        self.assertFalse(combined.runtime_required)
        self.assertEqual(combined.runtime_paths, ())

    def test_non_gate_workflow_mixed_with_runtime_or_gate_paths_fails_closed(self) -> None:
        for runtime_path in (
            "common/src/main/java/com/quickskin/mod/QuickSkin.java",
            ".github/workflows/on-demand-e2e.yml",
            ".github/actions/run-packaged-e2e/action.yml",
        ):
            with self.subTest(runtime_path=runtime_path):
                result = impact.classify(
                    [".github/workflows/sync-version-branches.yml", runtime_path]
                )
                self.assertTrue(result.runtime_required)
                self.assertEqual(result.runtime_paths, (runtime_path,))

    def test_documentation_prefix_restriction_survives_prefix_refactor(self) -> None:
        for path, non_runtime in (
            ("docs/ai/PROJECT.md", True),
            ("docs/assets/architecture.png", True),
            ("docs/tools/generate.py", False),
            ("site/app.js", True),
            ("site/assets/style.css", True),
        ):
            with self.subTest(path=path):
                result = impact.classify([path])
                self.assertEqual(result.runtime_required, not non_runtime)

    def test_mixed_diff_requires_runtime_and_records_exact_reason(self) -> None:
        result = impact.classify(["README.md", "fabric/src/main/java/Entrypoint.java"])
        self.assertTrue(result.runtime_required)
        self.assertEqual(result.runtime_paths, ("fabric/src/main/java/Entrypoint.java",))

    def test_empty_or_malformed_paths_never_become_not_applicable(self) -> None:
        with self.assertRaises(impact.ImpactError):
            impact.classify([])
        for path in ("../README.md", "/README.md", "docs\\README.md", "docs/x\nREADME.md"):
            with self.subTest(path=path), self.assertRaises(impact.ImpactError):
                impact.classify([path])

    def test_exact_git_diff_handles_spaces_without_shell_parsing(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self._git(repo, "init", "-q")
            self._git(repo, "config", "user.email", "e2e@example.invalid")
            self._git(repo, "config", "user.name", "E2E")
            docs = repo / "docs" / "release notes.md"
            docs.parent.mkdir()
            docs.write_text("one\n", encoding="utf-8")
            self._git(repo, "add", "docs/release notes.md")
            self._git(repo, "commit", "-qm", "first")
            base = self._git(repo, "rev-parse", "HEAD").strip()
            docs.write_text("two\n", encoding="utf-8")
            self._git(repo, "commit", "-qam", "second")
            head = self._git(repo, "rev-parse", "HEAD").strip()
            self.assertEqual(
                impact.git_diff_paths(repo, base, head), ["docs/release notes.md"]
            )

    def test_rename_across_allowlist_boundary_classifies_both_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repo = Path(temporary)
            self._git(repo, "init", "-q")
            self._git(repo, "config", "user.email", "e2e@example.invalid")
            self._git(repo, "config", "user.name", "E2E")
            runtime = repo / "common" / "src" / "main" / "java" / "Runtime.java"
            runtime.parent.mkdir(parents=True)
            runtime.write_text("runtime\n", encoding="utf-8")
            self._git(repo, "add", ".")
            self._git(repo, "commit", "-qm", "runtime")
            base = self._git(repo, "rev-parse", "HEAD").strip()

            documentation = repo / "docs" / "Runtime.md"
            documentation.parent.mkdir()
            self._git(repo, "mv", str(runtime.relative_to(repo)), str(documentation.relative_to(repo)))
            self._git(repo, "commit", "-qm", "move runtime into docs")
            head = self._git(repo, "rev-parse", "HEAD").strip()

            paths = impact.git_diff_paths(repo, base, head)
            self.assertEqual(
                {"common/src/main/java/Runtime.java", "docs/Runtime.md"},
                set(paths),
            )
            classification = impact.classify(paths)
            self.assertTrue(classification.runtime_required)
            self.assertEqual(
                ("common/src/main/java/Runtime.java",),
                classification.runtime_paths,
            )

    @staticmethod
    def _git(repo: Path, *args: str) -> str:
        import subprocess

        return subprocess.check_output(["git", *args], cwd=repo, text=True)


if __name__ == "__main__":
    unittest.main()
