from __future__ import annotations

import importlib.util
import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
MODULE_PATH = ROOT / "scripts" / "ci" / "e2e_job_graph.py"
SPEC = importlib.util.spec_from_file_location("e2e_job_graph", MODULE_PATH)
assert SPEC and SPEC.loader
graph = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = graph
SPEC.loader.exec_module(graph)


def job_block(workflow: str, job: str) -> str:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text
    )
    if match is None:
        raise AssertionError(f"missing job {job} in {workflow}")
    return match.group(0)


def job_display_name(workflow: str, job: str) -> str:
    match = re.search(r"(?m)^    name: (.+)$", job_block(workflow, job))
    if match is None:
        raise AssertionError(f"job {job} in {workflow} has no display name")
    return match.group(1)


def ready_display_name(name: str) -> str:
    """A gate named for draft deferral reports its fallback context in every non-draft run."""
    match = re.fullmatch(
        r"\$\{\{ github\.event\.pull_request\.draft && github\.event\.pull_request\.base\.ref == "
        r"'master' && '[^']+' \|\| '([^']+)' \}\}", name)
    return match.group(1) if match else name


class E2EJobNamesTest(unittest.TestCase):
    """The E2E job display names are pinned as literals by the protected evaluator and by the
    workflows that authenticate Packaged E2E runs from the jobs API. A one-sided rename would
    either fail closed noisily or, worse, silently disable advisory reviews, so every consumer
    must byte-match the constants in scripts/ci/e2e_job_graph.py."""

    def test_on_demand_e2e_job_names_byte_match_the_evaluator_constants(self) -> None:
        self.assertEqual(
            graph.POLICY_JOB, job_display_name("on-demand-e2e.yml", "runtime-policy")
        )
        self.assertEqual(graph.BUILD_JOB, job_display_name("on-demand-e2e.yml", "build"))
        self.assertEqual(
            graph.GATE_JOB,
            ready_display_name(job_display_name("on-demand-e2e.yml", "required-gate")),
        )
        self.assertEqual(
            "${{ matrix.id }}" + graph.SCENARIO_SUFFIX,
            job_display_name("on-demand-e2e.yml", "e2e"),
        )
        self.assertEqual(
            "${{ matrix.id }}" + graph.SCENARIO_SUFFIX, graph.UNEXPANDED_SCENARIO_JOB
        )

    def test_port_handler_pins_the_exact_evaluator_literals(self) -> None:
        handler = (WORKFLOWS / "handle-version-port-result.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn(f'select(.name == "{graph.POLICY_JOB}")', handler)
        self.assertIn(f'select(.name == "{graph.BUILD_JOB}")', handler)
        self.assertIn(f'endswith("{graph.SCENARIO_SUFFIX}")', handler)
        self.assertIn(f'"{graph.GATE_JOB}"', handler)

    def test_visual_review_pins_the_exact_evaluator_literals(self) -> None:
        visual = (WORKFLOWS / "visual-review.yml").read_text(encoding="utf-8")
        self.assertIn(f'.name == "{graph.GATE_JOB}"', visual)
        self.assertIn(f'endswith("{graph.SCENARIO_SUFFIX}")', visual)
        self.assertIn(f'sub("{graph.SCENARIO_SUFFIX}$"; "")', visual)
        # The attestation fallback matches the caller job name prefix because the attest job
        # calls a reusable workflow, whose jobs report as "<caller name> / <inner name>".
        attestation = job_display_name("on-demand-e2e.yml", "attest")
        self.assertIn(f'startswith("{attestation}")', visual)


if __name__ == "__main__":
    unittest.main()
