from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts" / "ci" / "e2e_job_graph.py"
SPEC = importlib.util.spec_from_file_location("e2e_job_graph", MODULE_PATH)
assert SPEC and SPEC.loader
graph = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = graph
SPEC.loader.exec_module(graph)


class E2EJobGraphTest(unittest.TestCase):
    expected = (
        "fabric-1_20_1--1_20_1--pr-behavior - contract scenarios",
        "forge-1_20_1--1_20_1--pr-behavior - contract scenarios",
    )

    def test_protected_controller_covers_both_required_gate_implementations(self) -> None:
        protected = set(graph.PROTECTED_CONTROLLER_PATHS)
        self.assertIn(".github/workflows/build-gate.yml", protected)
        self.assertIn(".github/workflows/on-demand-e2e.yml", protected)
        self.assertIn(".github/actions/run-packaged-e2e", protected)
        self.assertIn("common/src/e2e", protected)
        self.assertIn("e2e/loader-bootstrap-contract.json", protected)
        self.assertIn("e2e/scenario-contract.json", protected)
        self.assertIn("e2e/visual_review_runner.py", protected)
        self.assertIn("e2e/visual_review_cache.py", protected)
        self.assertIn("e2e/visual_review_semantic_prompt.md", protected)
        self.assertIn("scripts/ci/visual_anchor_certification.py", protected)
        self.assertIn("scripts/release/artifact_manifest.py", protected)
        self.assertIn("scripts/release/matrix.py", protected)
        self.assertIn("scripts/release/version_branches.py", protected)
        self.assertIn(".github/workflows/verify-gate-attestation.yml", protected)
        self.assertIn("build.gradle.kts", protected)
        self.assertIn("settings.gradle.kts", protected)
        self.assertIn("stonecutter.gradle.kts", protected)
        self.assertIn("gradlew", protected)
        self.assertIn("gradle/wrapper", protected)
        self.assertEqual(
            {"common/src/e2e/resources/pack.mcmeta"},
            set(graph.VERSION_SPECIFIC_CONTROLLER_PATHS),
        )
        self.assertIn("e2e/packaged_runtime.py", protected)
        self.assertEqual(
            {"fabric", "forge", "neoforge"},
            set(graph.load_bootstrap_contract(graph.DEFAULT_BOOTSTRAP_CONTRACT).loaders),
        )

    @staticmethod
    def job(name: str, conclusion: str = "success") -> dict[str, str]:
        return {"name": name, "status": "completed", "conclusion": conclusion}

    def payload(self, scenarios: tuple[str, ...] | None = None) -> dict[str, object]:
        selected = self.expected if scenarios is None else scenarios
        return {
            "jobs": [
                self.job(graph.POLICY_JOB),
                self.job(graph.BUILD_JOB),
                *(self.job(name) for name in selected),
                self.job(graph.GATE_JOB),
            ]
        }

    def test_full_requires_every_exact_anchor_and_success(self) -> None:
        validated = graph.validate_job_graph(
            self.payload(), policy="full", expected_scenarios=self.expected
        )
        self.assertEqual(list(self.expected), validated["observed_scenario_jobs"])

        cases = {
            "missing": self.expected[:1],
            "extra": self.expected + ("fabricated - contract scenarios",),
            "duplicate": self.expected + self.expected[:1],
        }
        for label, jobs in cases.items():
            with self.subTest(label=label), self.assertRaises(graph.JobGraphError):
                graph.validate_job_graph(
                    self.payload(jobs), policy="full", expected_scenarios=self.expected
                )

        failed = self.payload()
        failed["jobs"][2]["conclusion"] = "failure"  # type: ignore[index]
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                failed, policy="full", expected_scenarios=self.expected
            )

    def test_not_applicable_allows_no_matrix_or_the_exact_skipped_matrix(self) -> None:
        empty = self.payload(())
        empty["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        graph.validate_job_graph(
            empty, policy="not-applicable", expected_scenarios=self.expected
        )

        skipped = self.payload()
        skipped["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        for job in skipped["jobs"][2:-1]:  # type: ignore[index]
            job["conclusion"] = "skipped"
        graph.validate_job_graph(
            skipped, policy="not-applicable", expected_scenarios=self.expected
        )

        partial = self.payload(self.expected[:1])
        partial["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        partial["jobs"][2]["conclusion"] = "skipped"  # type: ignore[index]
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                partial, policy="not-applicable", expected_scenarios=self.expected
            )

    def test_not_applicable_accepts_the_unexpanded_matrix_placeholder(self) -> None:
        """A non-runtime port reports the literal template name, not zero jobs."""

        placeholder = self.payload((graph.UNEXPANDED_SCENARIO_JOB,))
        placeholder["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        placeholder["jobs"][2]["conclusion"] = "skipped"  # type: ignore[index]
        graph.validate_job_graph(
            placeholder, policy="not-applicable", expected_scenarios=self.expected
        )

        # It still may not have executed, and it is not a licence to run a lane.
        executed = self.payload((graph.UNEXPANDED_SCENARIO_JOB,))
        executed["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        executed["jobs"][2]["conclusion"] = "success"  # type: ignore[index]
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                executed, policy="not-applicable", expected_scenarios=self.expected
            )

        # The placeholder is never acceptable when real lanes were required.
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                self.payload((graph.UNEXPANDED_SCENARIO_JOB,)),
                policy="full",
                expected_scenarios=self.expected,
            )

        # Mixing the placeholder with a concrete lane is still a partial matrix.
        mixed = self.payload((graph.UNEXPANDED_SCENARIO_JOB, self.expected[0]))
        mixed["jobs"][1]["conclusion"] = "skipped"  # type: ignore[index]
        for job in mixed["jobs"][2:-1]:  # type: ignore[index]
            job["conclusion"] = "skipped"
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                mixed, policy="not-applicable", expected_scenarios=self.expected
            )

    def test_rejects_missing_or_duplicated_control_jobs(self) -> None:
        missing = self.payload()
        missing["jobs"] = [  # type: ignore[index]
            job for job in missing["jobs"] if job["name"] != graph.GATE_JOB  # type: ignore[index]
        ]
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                missing, policy="full", expected_scenarios=self.expected
            )

        duplicated = self.payload()
        duplicated["jobs"].append(self.job(graph.GATE_JOB))  # type: ignore[union-attr]
        with self.assertRaises(graph.JobGraphError):
            graph.validate_job_graph(
                duplicated, policy="full", expected_scenarios=self.expected
            )

    def test_snapshot_matrix_cli_does_not_require_a_source_checkout(self) -> None:
        checked_out_matrix_path = ROOT / "release/release-matrix.json"
        checked_out_matrix = graph.load_matrix(checked_out_matrix_path)
        expected_scenarios = graph.expected_scenario_jobs_for(
            checked_out_matrix_path, "pr-anchors"
        )
        expected_loaders = sorted(
            {artifact["loader"] for artifact in checked_out_matrix["artifacts"]}
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            matrix_path = root / "source-release-matrix.json"
            properties_path = root / "source-gradle.properties"
            jobs_path = root / "source-jobs.json"
            matrix_path.write_bytes(checked_out_matrix_path.read_bytes())
            properties_path.write_bytes((ROOT / "gradle.properties").read_bytes())
            jobs_path.write_text(
                json.dumps(self.payload(expected_scenarios)), encoding="utf-8"
            )
            head_sha = subprocess.run(
                ("git", "rev-parse", "HEAD"),
                cwd=ROOT,
                check=True,
                stdout=subprocess.PIPE,
                text=True,
            ).stdout.strip()
            output = io.StringIO()

            with redirect_stdout(output):
                result = graph.main(
                    [
                        "--matrix",
                        str(matrix_path),
                        "--matrix-properties",
                        str(properties_path),
                        "--jobs",
                        str(jobs_path),
                        "--repository",
                        str(ROOT),
                        "--repository-head-sha",
                        head_sha,
                        "--protected-sha",
                        head_sha,
                        "--head-sha",
                        head_sha,
                        "--bootstrap-contract",
                        str(graph.DEFAULT_BOOTSTRAP_CONTRACT),
                        "--matrix-kind",
                        "pr-anchors",
                        "--runtime-policy",
                        "full",
                    ]
                )

            self.assertEqual(0, result)
            validated = json.loads(output.getvalue())
            self.assertEqual(
                list(expected_scenarios), validated["observed_scenario_jobs"]
            )
            self.assertEqual(
                expected_loaders, validated["active_loader_bootstraps"]
            )

    def test_controller_parity_is_bound_to_the_exact_checkout_and_protected_blob(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)

            def git(*arguments: str) -> str:
                completed = subprocess.run(
                    ("git", *arguments),
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    text=True,
                )
                return completed.stdout.strip()

            git("init", "--quiet")
            git("config", "user.name", "E2E test")
            git("config", "user.email", "e2e@example.invalid")
            controller = repository / "controller.txt"
            variant = repository / "variant.txt"
            unrelated = repository / "README.md"
            controller.write_text("protected\n", encoding="utf-8")
            variant.write_text("version one\n", encoding="utf-8")
            unrelated.write_text("one\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "protected")
            protected_sha = git("rev-parse", "HEAD")

            unrelated.write_text("two\n", encoding="utf-8")
            variant.write_text("version two\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "unrelated")
            unrelated_sha = git("rev-parse", "HEAD")
            self.assertEqual(
                ("controller.txt", "variant.txt"),
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=unrelated_sha,
                    paths=("controller.txt", "variant.txt"),
                    version_specific_paths=("variant.txt",),
                ),
            )

            # A privileged protected checkout may compare an authenticated source commit that is
            # present only in the object database; source files never need to be checked out.
            git("checkout", "--quiet", protected_sha)
            self.assertEqual(
                ("controller.txt", "variant.txt"),
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=unrelated_sha,
                    repository_head_sha=protected_sha,
                    paths=("controller.txt", "variant.txt"),
                    version_specific_paths=("variant.txt",),
                ),
            )
            git("checkout", "--quiet", unrelated_sha)

            controller.write_text("weakened\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "weaken controller")
            weakened_sha = git("rev-parse", "HEAD")
            with self.assertRaisesRegex(graph.ControllerSkewError, "controller.txt"):
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=weakened_sha,
                    paths=("controller.txt", "variant.txt"),
                    version_specific_paths=("variant.txt",),
                )

            with self.assertRaisesRegex(graph.JobGraphError, "checked-out head"):
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=unrelated_sha,
                    paths=("controller.txt", "variant.txt"),
                    version_specific_paths=("variant.txt",),
                )

            with self.assertRaisesRegex(graph.JobGraphError, "protected or evidence"):
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=unrelated_sha,
                    repository_head_sha=weakened_sha,
                    paths=("controller.txt", "variant.txt"),
                    version_specific_paths=("variant.txt",),
                )

            with self.assertRaisesRegex(graph.JobGraphError, "outside protected roots"):
                graph.validate_controller_parity(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=weakened_sha,
                    paths=("controller.txt",),
                    version_specific_paths=("variant.txt",),
                )

    def test_controller_skew_has_a_distinct_cli_exit_code(self) -> None:
        with (
            mock.patch.object(graph, "load_matrix", return_value={}),
            mock.patch.object(graph, "read_mod_version", return_value="1.20.1"),
            mock.patch.object(
                graph, "validate_loader_bootstraps", return_value=("fabric", "forge")
            ),
            mock.patch.object(
                graph, "expected_scenario_jobs_from_data", return_value=self.expected
            ),
            mock.patch.object(graph, "_read_json", return_value={"jobs": []}),
            mock.patch.object(graph, "validate_job_graph", return_value={}),
            mock.patch.object(
                graph,
                "validate_controller_parity",
                side_effect=graph.ControllerSkewError("controller changed"),
            ),
            mock.patch.object(
                graph,
                "validate_advisory_controller_skew",
                return_value=(".github/workflows/e2e.yml",),
            ) as advisory,
        ):
            error = io.StringIO()
            with redirect_stdout(io.StringIO()), mock.patch("sys.stderr", error):
                result = graph.main(
                    [
                        "--matrix",
                        "unused-matrix.json",
                        "--jobs",
                        "unused-jobs.json",
                        "--repository",
                        str(ROOT),
                        "--protected-sha",
                        "0" * 40,
                        "--head-sha",
                        "1" * 40,
                        "--runtime-policy",
                        "full",
                        "--allow-advisory-controller-skew",
                    ]
                )

        self.assertEqual(78, graph.CONTROLLER_SKEW_EXIT_CODE)
        self.assertEqual(graph.CONTROLLER_SKEW_EXIT_CODE, result)
        self.assertIn("Packaged E2E controller skew", error.getvalue())
        advisory.assert_called_once()

    def test_advisory_controller_skip_rejects_a_mixed_product_diff(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)

            def git(*arguments: str) -> str:
                return subprocess.run(
                    ("git", *arguments),
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    text=True,
                ).stdout.strip()

            git("init", "--quiet")
            git("config", "user.name", "E2E test")
            git("config", "user.email", "e2e@example.invalid")
            workflow = repository / ".github/workflows/e2e.yml"
            policy = repository / "scripts/ci/policy.py"
            e2e_contract = repository / "e2e/contract.json"
            release_test = repository / "scripts/release/tests/test_policy.py"
            product = repository / "common/src/main/java/QuickSkin.java"
            for path in (workflow, policy, e2e_contract, release_test, product):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("one\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "base")
            protected_sha = git("rev-parse", "HEAD")

            workflow.write_text("two\n", encoding="utf-8")
            policy.write_text("two\n", encoding="utf-8")
            e2e_contract.write_text("two\n", encoding="utf-8")
            release_test.write_text("two\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "ci only")
            ci_sha = git("rev-parse", "HEAD")
            self.assertEqual(
                (
                    ".github/workflows/e2e.yml",
                    "e2e/contract.json",
                    "scripts/ci/policy.py",
                    "scripts/release/tests/test_policy.py",
                ),
                graph.validate_advisory_controller_skew(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=ci_sha,
                ),
            )

            product.write_text("two\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "mixed product")
            mixed_sha = git("rev-parse", "HEAD")
            with self.assertRaisesRegex(graph.JobGraphError, "QuickSkin.java"):
                graph.validate_advisory_controller_skew(
                    repository,
                    protected_sha=protected_sha,
                    head_sha=mixed_sha,
                )

    def test_active_loader_bootstrap_and_final_gradle_binding_are_exact(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            repository = Path(temporary)

            def git(*arguments: str) -> str:
                completed = subprocess.run(
                    ("git", *arguments),
                    cwd=repository,
                    check=True,
                    stdout=subprocess.PIPE,
                    text=True,
                )
                return completed.stdout.strip()

            git("init", "--quiet")
            git("config", "user.name", "E2E test")
            git("config", "user.email", "e2e@example.invalid")
            source = repository / "fabric/src/e2e/java/Bootstrap.java"
            manifest = repository / "fabric/src/e2e/resources/fabric.mod.json"
            build = repository / "fabric/build.gradle.kts"
            source.parent.mkdir(parents=True)
            manifest.parent.mkdir(parents=True)
            source.write_text("class Bootstrap {}\n", encoding="utf-8")
            manifest.write_text("{}\n", encoding="utf-8")
            build.write_text(graph.HARNESS_CONVENTION_BINDING + "\n", encoding="utf-8")
            git("add", ".")
            git("commit", "--quiet", "-m", "bootstrap")
            valid_sha = git("rev-parse", "HEAD")
            contract = repository / "bootstrap.json"
            contract.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "loaders": {
                            "fabric": {
                                "files": {
                                    "fabric/src/e2e/java/Bootstrap.java": hashlib.sha256(
                                        source.read_bytes()
                                    ).hexdigest(),
                                    "fabric/src/e2e/resources/fabric.mod.json": hashlib.sha256(
                                        manifest.read_bytes()
                                    ).hexdigest(),
                                }
                            },
                            "forge": {
                                "files": {
                                    "forge/src/e2e/placeholder": "0" * 64,
                                }
                            },
                            "neoforge": {
                                "files": {
                                    "neoforge/src/e2e/placeholder": "0" * 64,
                                }
                            },
                        },
                        "release_build_scripts": {
                            "fabric-1.0": {
                                "fabric": hashlib.sha256(build.read_bytes()).hexdigest(),
                            }
                        },
                    }
                ),
                encoding="utf-8",
            )
            matrix = {
                "project": {"release_branch": "fabric-1.0"},
                "artifacts": [{"loader": "fabric"}],
            }
            with mock.patch.object(graph, "load_matrix", return_value=matrix):
                self.assertEqual(
                    ("fabric",),
                    graph.validate_loader_bootstraps(
                        repository,
                        head_sha=valid_sha,
                        matrix_path=repository / "unused.json",
                        contract_path=contract,
                    ),
                )

                source.write_text("class Weakened {}\n", encoding="utf-8")
                git("add", ".")
                git("commit", "--quiet", "-m", "weaken source")
                weakened_sha = git("rev-parse", "HEAD")
                with self.assertRaisesRegex(graph.JobGraphError, "differs"):
                    graph.validate_loader_bootstraps(
                        repository,
                        head_sha=weakened_sha,
                        matrix_path=repository / "unused.json",
                        contract_path=contract,
                    )

                source.write_text("class Bootstrap {}\n", encoding="utf-8")
                build.write_text(
                    'tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach { '
                    'from("untrusted") }\n'
                    + graph.HARNESS_CONVENTION_BINDING
                    + "\n",
                    encoding="utf-8",
                )
                git("add", ".")
                git("commit", "--quiet", "-m", "weaken binding")
                binding_sha = git("rev-parse", "HEAD")
                with self.assertRaisesRegex(graph.JobGraphError, "build script differs"):
                    graph.validate_loader_bootstraps(
                        repository,
                        head_sha=binding_sha,
                        matrix_path=repository / "unused.json",
                        contract_path=contract,
                    )


if __name__ == "__main__":
    unittest.main()
