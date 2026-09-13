from __future__ import annotations

import ast
import copy
import re
import unittest

from test_workflow_security import job_block


def request_condition() -> str:
    block = job_block("feature-coverage-request.yml", "request")
    match = re.search(r"(?m)^    if: >-\n((?:      .+\n)+)", block)
    if match is None:
        raise AssertionError("recovery must filter events at job admission before runner allocation")
    return " ".join(line.strip() for line in match.group(1).splitlines())


def evaluate_condition(expression: str, github: dict) -> bool:
    """Evaluate only this condition's boolean/string subset, never executable expressions."""
    def reference(match: re.Match) -> str:
        value = github
        for key in match.group(0).split(".")[1:]:
            value = value.get(key, "") if isinstance(value, dict) else ""
        if not isinstance(value, str):
            raise AssertionError("filter fixtures support only string context values")
        return repr(value)

    source = re.sub(r"\bgithub(?:\.[A-Za-z_][A-Za-z_0-9]*)+", reference, expression)
    tree = ast.parse(source.replace("&&", " and ").replace("||", " or "), mode="eval")

    def evaluate(node):
        if isinstance(node, ast.Expression):
            return evaluate(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.BoolOp):
            values = [bool(evaluate(value)) for value in node.values]
            if isinstance(node.op, ast.And):
                return all(values)
            if isinstance(node.op, ast.Or):
                return any(values)
        if isinstance(node, ast.Compare) and len(node.ops) == len(node.comparators) == 1:
            left, right = evaluate(node.left), evaluate(node.comparators[0])
            if not isinstance(left, str) or not isinstance(right, str):
                raise AssertionError("filter comparisons must retain exact string identities")
            equal = left.casefold() == right.casefold()
            if isinstance(node.ops[0], ast.Eq):
                return equal
            if isinstance(node.ops[0], ast.NotEq):
                return not equal
        raise AssertionError(f"unsupported job-filter expression: {ast.dump(node)}")

    return bool(evaluate(tree))


class RecoveryJobFilterTest(unittest.TestCase):
    def setUp(self):
        self.expression = request_condition()
        self.github = {
            "ref": "refs/heads/master", "sha": "a" * 40,
            "repository": "The-Plum-Team/Quick-Skin-Mod", "event_name": "workflow_run",
            "event": {"workflow_run": {
                "head_repository": {"full_name": "The-Plum-Team/Quick-Skin-Mod"},
                "head_branch": "master", "head_sha": "a" * 40,
                "path": ".github/workflows/visual-review-drain.yml",
                "event": "repository_dispatch", "conclusion": "failure"}},
        }

    def admitted(self, github=None):
        return evaluate_condition(self.expression, self.github if github is None else github)

    def test_current_exact_review_completion_keeps_only_failed_terminal_recovery(self):
        for conclusion in ("failure", "cancelled", "timed_out"):
            with self.subTest(conclusion=conclusion):
                self.github["event"]["workflow_run"]["conclusion"] = conclusion
                self.assertTrue(self.admitted())

    def test_generic_review_sweeps_never_allocate_a_recovery_runner(self):
        for event in ("schedule", "workflow_dispatch"):
            with self.subTest(event=event):
                self.github["event"]["workflow_run"]["event"] = event
                self.assertFalse(self.admitted())

    def test_pages_publication_fallback_keeps_schedule_manual_and_workflow_run(self):
        self.github["event"]["workflow_run"]["path"] = ".github/workflows/pages.yml"
        for event in ("schedule", "workflow_dispatch", "workflow_run"):
            for conclusion in ("failure", "cancelled", "timed_out"):
                with self.subTest(event=event, conclusion=conclusion):
                    self.github["event"]["workflow_run"].update(event=event, conclusion=conclusion)
                    self.assertTrue(self.admitted())

    def test_pages_repository_wakes_never_allocate_a_recovery_runner(self):
        self.github["event"]["workflow_run"]["path"] = ".github/workflows/pages.yml"
        for conclusion in ("success", "failure", "cancelled"):
            with self.subTest(conclusion=conclusion):
                self.github["event"]["workflow_run"]["conclusion"] = conclusion
                self.assertFalse(self.admitted())

    def test_successful_producers_and_idle_sweeps_do_not_replace_avoided_collector_starts(self):
        for workflow, events in (
                ("visual-review-drain.yml", ("repository_dispatch", "schedule", "workflow_dispatch")),
                ("pages.yml", ("schedule", "workflow_dispatch", "repository_dispatch", "workflow_run"))):
            for event in events:
                for conclusion in ("success", "skipped", "neutral", "action_required", ""):
                    with self.subTest(workflow=workflow, event=event, conclusion=conclusion):
                        self.github["event"]["workflow_run"].update(
                            path=f".github/workflows/{workflow}", event=event, conclusion=conclusion)
                        self.assertFalse(self.admitted())

    def test_hourly_and_explicit_manual_recovery_do_not_require_a_producer_payload(self):
        for event in ("schedule", "workflow_dispatch"):
            with self.subTest(event=event):
                self.github.update(event_name=event, event={})
                self.assertTrue(self.admitted())

    def test_nonmaster_recovery_ref_is_rejected_for_every_supported_trigger(self):
        for event in ("schedule", "workflow_dispatch", "workflow_run"):
            for ref in ("refs/heads/topic", "refs/pull/1/merge", "refs/tags/v1"):
                with self.subTest(event=event, ref=ref):
                    self.github.update(event_name=event, ref=ref)
                    self.assertFalse(self.admitted())

    def test_foreign_repository_branch_sha_and_workflow_cannot_pass_completion_filter(self):
        cases = (
            ("head_repository", {"full_name": "foreign/Quick-Skin-Mod"}),
            ("head_branch", "topic"), ("head_sha", "b" * 40),
            ("path", ".github/workflows/feature-coverage-request.yml"),
            ("path", ".github/workflows/feature-coverage.yml"),
            ("path", ".github/workflows/untrusted.yml"),
        )
        for key, value in cases:
            with self.subTest(key=key, value=value):
                github = copy.deepcopy(self.github)
                github["event"]["workflow_run"][key] = value
                self.assertFalse(self.admitted(github))

    def test_incomplete_completion_identity_fails_closed_without_weakening_script_admission(self):
        for key in ("head_repository", "head_branch", "head_sha", "path", "event", "conclusion"):
            with self.subTest(key=key):
                github = copy.deepcopy(self.github)
                github["event"]["workflow_run"].pop(key)
                self.assertFalse(self.admitted(github))
        block = job_block("feature-coverage-request.yml", "request")
        self.assertIn("feature_coverage_request.py", block)
        self.assertIn('--source-sha "$GITHUB_SHA"', block)
        self.assertIn("persist-credentials: false", block)

    def test_evaluator_rejects_executable_or_new_unreviewed_expression_forms(self):
        for expression in ("always()", "github.ref.startswith('refs')", "1 < 2"):
            with self.subTest(expression=expression), self.assertRaises(AssertionError):
                evaluate_condition(expression, self.github)

    def test_review_request_tail_waits_for_independent_capacity_resume_before_owner_settlement(self):
        block = job_block("visual-review-drain.yml", "request-feature-coverage")
        match = re.search(r"(?m)^    needs:\n((?:      - [a-z-]+\n)+)", block)
        self.assertIsNotNone(match)
        dependencies = {line.strip()[2:] for line in match.group(1).splitlines()}
        self.assertTrue({"review", "resume-capacity-queue", "cleanup",
                         "release-mod-compatibility", "release-anchor"}.issubset(dependencies))
        self.assertIn("always()", block)


if __name__ == "__main__":
    unittest.main()
