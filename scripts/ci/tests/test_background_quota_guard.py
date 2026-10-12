"""Background quota guard and sweep schedules.

Background work (queue drains, recovery sweeps, review wakes) shares the repository's GitHub API
budget with the required gates. It calls ``github_api_background_admission`` before spending that
budget and settles successfully below the reserve, leaving its durable work queued; the gates and
release workflows never call it. The QS-owned sweep schedules stay on distinct minutes, away from
the managed gate-status and Pages schedules.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_workflow_security import WORKFLOWS, job_block, step_script


GUARD = "github_api_background_admission"
SHA = "a" * 40
REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
# workflow -> (job, step, label, first expensive call that the guard must precede)
GUARDED = {
    "visual-review-drain.yml": (
        "select", "Select the oldest authenticated non-cooling queue entry",
        "visual review drain", "python3 scripts/ci/visual_review_queue.py"),
    "visual-review-wake-recovery.yml": (
        "inspect", "Decide whether the current generation lost its review wake",
        "visual review wake recovery", "python3 scripts/ci/visual_review_wake.py"),
    "feature-coverage-request.yml": (
        "request", "Reconcile durable readiness without running a model or assembling reports",
        "feature coverage request recovery", "python3 scripts/ci/feature_coverage_request.py"),
    "mod-compatibility-review.yml": (
        "recover", "Dispatch the oldest authenticated pending source",
        "compatibility review recovery", "python3 scripts/ci/mod_compatibility_review_queue.py"),
    "mod-compatibility-e2e.yml": (
        "request-review", "Dispatch the exact settled compatibility source",
        "compatibility review wake", 'payload="$RUNNER_TEMP/mod-compatibility-review-request.json"'),
}
# Owned by the mod-base kit (byte-identical managed callers); their minutes are fixed here.
MANAGED_SCHEDULES = {"mod-base-gate-status.yml": {17}, "pages.yml": {43}}
SWEEPS = {
    "visual-review-drain.yml": {21, 51},
    "mod-compatibility-review.yml": {26, 56},
    "feature-coverage-request.yml": {34},
    "visual-review-wake-recovery.yml": {41},
    "target-ci-status.yml": {11},
}


def hourly_minutes(workflow: str) -> set[int]:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    minutes: set[int] = set()
    for expression in re.findall(r'(?m)^    - cron: "([^"]+)"$', text):
        minute, *rest = expression.split()
        if rest != ["*", "*", "*", "*"]:
            continue
        minutes.update(int(value) for value in minute.split(","))
    return minutes


class ScheduleSpreadTest(unittest.TestCase):
    def test_sweeps_use_distinct_minutes_away_from_the_managed_schedules(self) -> None:
        for workflow, expected in {**SWEEPS, **MANAGED_SCHEDULES}.items():
            with self.subTest(workflow=workflow):
                self.assertEqual(expected, hourly_minutes(workflow))
        owners: dict[int, str] = {}
        for workflow, minutes in {**SWEEPS, **MANAGED_SCHEDULES}.items():
            for minute in minutes:
                self.assertNotIn(minute, owners, f"{workflow} shares minute {minute} with {owners.get(minute)}")
                owners[minute] = workflow
        # release-verify.yml polls every five minutes from :03; no QS-owned sweep starts on its beat.
        self.assertIn('cron: "3-58/5 * * * *"', (WORKFLOWS / "release-verify.yml").read_text(encoding="utf-8"))
        self.assertTrue(all(minute % 5 != 3 for minutes in SWEEPS.values() for minute in minutes))


class GuardPlacementTest(unittest.TestCase):
    def test_every_background_entry_guards_before_spending_api_budget(self) -> None:
        for workflow, (job, step, label, expensive) in GUARDED.items():
            with self.subTest(workflow=workflow):
                script = step_script(workflow, job, step)
                call = f'{GUARD} "{label}" || admission_status=$?'
                self.assertEqual(1, script.count(call))
                self.assertLess(script.index("source scripts/ci/github_api_retry.sh"), script.index(call))
                self.assertLess(script.index(call), script.index(expensive))
                deferral = script[script.index(call):script.index(expensive)]
                self.assertIn('if [[ "$admission_status" -eq 75 ]]; then', deferral)
                self.assertIn("exit 0", deferral)
                self.assertIn('[[ "$admission_status" -eq 0 ]]', deferral)
                self.assertIn("GH_TOKEN: ${{ github.token }}", job_block(workflow, job))

    def test_gates_releases_and_publication_never_defer_on_quota(self) -> None:
        for path in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertEqual(path.name in GUARDED, GUARD in text)
                self.assertLessEqual(text.count(GUARD), 1)
        # A completed compatibility source is never queued for publication again, and only a
        # successful producer run's handoff is selectable, so the publication path stays unguarded.
        for job in ("request-publication", "publish-evidence", "notify-family", "gate", "enumerate"):
            self.assertNotIn(GUARD, job_block("mod-compatibility-review.yml", job))
        # The optional-mod wave request is emitted once, after its capsule is retired.
        self.assertNotIn(GUARD, job_block("mod-compatibility-e2e.yml", "admit"))
        self.assertNotIn(GUARD, job_block("visual-review-drain.yml", "release-mod-compatibility"))


@unittest.skipUnless(shutil.which("jq") and shutil.which("bash"), "the drain shell uses Bash and jq")
class DrainSelectionShellTest(unittest.TestCase):
    """Execute the checked-in drain selection with only its GitHub and Python boundary faked."""

    def run_select(self, *, admission: int = 0, queue: str = "eligible=true\nartifact_id=7\n",
                   active: str = "0", **environment: str) -> tuple[subprocess.CompletedProcess[str], dict[str, str], list[str]]:
        script = step_script("visual-review-drain.yml", "select",
                             "Select the oldest authenticated non-cooling queue entry")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            helper = root / "scripts" / "ci" / "github_api_retry.sh"
            helper.parent.mkdir(parents=True)
            helper.write_text(r"""
github_api_background_admission() {
  [[ "$1" == "visual review drain" ]] || return 95
  printf 'admission\n' >> "$FIXTURE_CALLS"
  return "$FIXTURE_ADMISSION"
}
github_api_retry() {
  [[ "$1" == "repos/$GITHUB_REPOSITORY/actions/workflows/visual-review-drain.yml/runs?per_page=50" &&
     "$2" == --jq && "$3" == *'.id != 900'* ]] || return 96
  printf 'active-runs\n' >> "$FIXTURE_CALLS"
  printf '%s\n' "$FIXTURE_ACTIVE"
}
python3() {
  [[ "$1" == scripts/ci/visual_review_queue.py ]] || return 97
  printf 'selector\n' >> "$FIXTURE_CALLS"
  while (( $# )); do
    if [[ "$1" == --github-output ]]; then printf '%s' "$FIXTURE_QUEUE" >> "$2"; fi
    shift
  done
}
sleep() { :; }
""", encoding="utf-8")
            runner = root / "runner"
            runner.mkdir()
            output = root / "output"
            calls = root / "calls"
            result = subprocess.run(["bash", "-c", "set -euo pipefail\n" + script], cwd=root,
                capture_output=True, text=True, timeout=15,
                env={"PATH": os.environ["PATH"], "RUNNER_TEMP": str(runner), "GITHUB_OUTPUT": str(output),
                     "GITHUB_REPOSITORY": REPOSITORY, "GITHUB_RUN_ID": "900",
                     "GITHUB_EVENT_NAME": "schedule", "REQUESTED_ARTIFACT_ID": "",
                     "REQUESTED_ARTIFACT_NAME": "", "REQUESTED_GENERATION_SHA": "",
                     "REQUESTED_RESUME_QUEUE": "", "FIXTURE_ADMISSION": str(admission),
                     "FIXTURE_QUEUE": queue, "FIXTURE_ACTIVE": active, "FIXTURE_CALLS": str(calls),
                     **environment})
            values = dict(line.split("=", 1) for line in output.read_text().splitlines()) if output.exists() else {}
            return result, values, calls.read_text().splitlines() if calls.exists() else []

    def direct(self, resume: str) -> dict[str, str]:
        name = f"visual-review-input-12-{SHA}--mc1.21.1"
        return {"GITHUB_EVENT_NAME": "repository_dispatch", "REQUESTED_ARTIFACT_ID": "7",
                "REQUESTED_ARTIFACT_NAME": name, "REQUESTED_GENERATION_SHA": SHA,
                "REQUESTED_RESUME_QUEUE": resume,
                "FIXTURE_QUEUE": f"eligible=true\nartifact_id=7\nartifact_name={name}\ngeneration_sha={SHA}\n"}

    def test_deferral_settles_without_reading_the_queue(self) -> None:
        for environment in ({}, self.direct("")):
            with self.subTest(direct=bool(environment)):
                result, values, calls = self.run_select(admission=75, **environment)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("false", values["eligible"])
                self.assertEqual("true" if environment else "false", values["direct"])
                self.assertEqual(["admission"], calls)
        result, values, calls = self.run_select(admission=2)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["admission"], calls)

    def test_sweep_marks_a_stranded_backlog_only_when_no_other_drain_is_active(self) -> None:
        for active, expected in (("0", "true"), ("1", "false"), ("", "false")):
            with self.subTest(active=active):
                result, values, calls = self.run_select(active=active)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("true", values["eligible"])
                self.assertEqual("false", values["direct"])
                self.assertEqual(expected, values["resume_queue"])
                self.assertEqual(["admission", "selector", "active-runs"], calls)
        result, values, calls = self.run_select(queue="eligible=false\n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("false", values["resume_queue"])
        self.assertEqual(["admission", "selector"], calls)

    def test_exact_wake_carries_only_a_boolean_resume_request(self) -> None:
        for resume, expected in (("", "false"), ("false", "false"), ("true", "true")):
            with self.subTest(resume=resume):
                result, values, calls = self.run_select(**self.direct(resume))
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("true", values["direct"])
                self.assertEqual(expected, values["resume_queue"])
                self.assertNotIn("active-runs", calls)
        result, _values, _calls = self.run_select(**self.direct("$(touch pwned)"))
        self.assertNotEqual(0, result.returncode)


class StrandedFanOutWiringTest(unittest.TestCase):
    def test_sweep_resume_request_reaches_only_a_capacity_ready_exact_wake(self) -> None:
        select = job_block("visual-review-drain.yml", "select")
        self.assertIn("resume_queue: ${{ steps.queue.outputs.resume_queue }}", select)
        self.assertIn("REQUESTED_RESUME_QUEUE: ${{ github.event.client_payload.resume_queue }}", select)
        dispatch = job_block("visual-review-drain.yml", "dispatch-selected")
        self.assertIn("RESUME_QUEUE: ${{ needs.select.outputs.resume_queue }}", dispatch)
        self.assertIn('[[ "$RESUME_QUEUE" == true || "$RESUME_QUEUE" == false ]]', dispatch)
        self.assertIn("generation_sha:$generation_sha,resume_queue:$resume_queue}}", dispatch)
        resume = job_block("visual-review-drain.yml", "resume-capacity-queue")
        self.assertIn("      - capacity-check\n", resume)
        condition = resume.split("    if: >-\n", 1)[1].split("    runs-on:", 1)[0]
        self.assertIn("needs.capacity-probe.outputs.fresh_ready == 'true'", condition)
        self.assertIn("needs.select.outputs.resume_queue == 'true' &&", condition)
        self.assertIn("needs.select.outputs.direct == 'true' &&", condition)
        self.assertIn("needs.capacity-check.outputs.ready == 'true'", condition)
        self.assertIn("needs.capacity-probe.outputs.ready == 'true'", condition)


if __name__ == "__main__":
    unittest.main()
