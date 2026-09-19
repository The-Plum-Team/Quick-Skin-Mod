from __future__ import annotations

import json
import math
import sys
import unittest
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/pages"))
sys.path.insert(0, str(ROOT / "scripts/ci"))
import publication_progress as progress
from test_workflow_security import job_block, step_script

SHA = "a" * 40
REPOSITORY = "owner/repo"
KEYS = {row["bundle_key"] for row in progress.inventory()["include"]}


def utc(value):
    return datetime.fromtimestamp(value, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class GitHubFixture:
    """Count actual controller GET calls, without credentials, ZIPs or remote execution."""
    def __init__(self, published=(), ready=(), ordinary_at=None, keys=None):
        keys = KEYS if keys is None else keys
        self.runs, self.jobs, self.artifacts = {}, {}, {}
        self.calls = []
        self.heads = [SHA, SHA]
        if published is not None:
            owner = self.owner(1000, progress.PAGES, 2000)
            for key in sorted(keys):
                self.job(owner, f"Collect {key}", "Select the target artifact for the exact source commit", 1900)
                self.job(owner, f"Collect compatibility {key}",
                         "Select the newest authenticated compatibility generation", 1900)
                job = self.job(owner, f"Refresh evidence cache for {key}",
                               "Roll the protected evidence cache forward", 2000)
                self.artifact(owner, f"pages-cache-{key}--{SHA}", job, 2000)
                job = self.job(owner, f"Refresh compatibility cache for {key}",
                               "Roll the protected compatibility cache forward", 2000)
                if key in published:
                    self.artifact(owner, f"pages-mod-compatibility-cache-{key}--{SHA}", job, 2000)
            self.job(owner, "Build atomic static site", "Render", 1900)
            self.job(owner, "Deploy GitHub Pages", "Deploy", 1950)
        if published is None or ordinary_at is not None:
            at = ordinary_at if ordinary_at is not None else 1000
            owner = self.owner(900, progress.E2E, at)
            for key in sorted(keys):
                job = self.job(owner, "Prepare public evidence for " + key + " (advisory)",
                               "Upload stable public evidence for this Minecraft target", at)
                self.artifact(owner, f"pages-e2e-{key}", job, at)
        for index, key in enumerate(sorted(ready)):
            owner = self.owner(2000 + index, progress.COMPATIBILITY, 3000)
            job = self.job(owner, "Publish compact compatibility evidence",
                           "Upload the compact public compatibility handoff", 3000)
            self.artifact(owner, f"pages-mod-compatibility-{key}", job, 3000)

    def owner(self, identifier, workflow, at, conclusion="success"):
        run = {"id": identifier, "run_attempt": 1, "path": workflow, "head_branch": "master",
               "head_sha": SHA, "head_repository": {"full_name": REPOSITORY},
               "event": "workflow_dispatch", "status": "completed", "conclusion": conclusion,
               "created_at": utc(at - 50), "updated_at": utc(at + 10)}
        self.runs[identifier], self.jobs[identifier] = run, []
        return run

    def job(self, owner, name, step, at):
        job = {"id": owner["id"] * 100 + len(self.jobs[owner["id"]]) + 1,
               "run_id": owner["id"], "run_attempt": owner["run_attempt"], "head_sha": SHA,
               "name": name, "status": "completed", "conclusion": "success",
               "started_at": utc(at - 10), "completed_at": utc(at + 5), "steps": [
                   {"name": step, "status": "completed", "conclusion": "success",
                    "started_at": utc(at - 5), "completed_at": utc(at + 5)}]}
        self.jobs[owner["id"]].append(job)
        return job

    def artifact(self, owner, name, job, at):
        identifier = len(self.artifacts) + 100
        artifact = {"id": identifier, "name": name, "expired": False, "size_in_bytes": 20,
                    "digest": "sha256:" + "b" * 64, "created_at": utc(at),
                    "workflow_run": {"id": owner["id"], "head_sha": SHA, "head_branch": "master"}}
        self.artifacts[identifier] = artifact
        return artifact

    def get(self, endpoint, *, maximum):
        self.calls.append(endpoint)
        path = endpoint.removeprefix(f"repos/{REPOSITORY}/")
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        pieces = parsed.path.split("/")
        if path == "branches/master":
            value = {"commit": {"sha": self.heads.pop(0)}}
        elif parsed.path == "actions/artifacts":
            name = query["name"][0]
            rows = [row for row in self.artifacts.values() if row["name"] == name]
            value = {"total_count": len(rows), "artifacts": rows}
        elif pieces[:2] == ["actions", "artifacts"]:
            value = self.artifacts[int(pieces[2])]
        elif pieces[:2] == ["actions", "workflows"]:
            rows = [row for row in self.runs.values() if Path(row["path"]).name == pieces[2]
                    and row["conclusion"] == query["status"][0]]
            value = {"total_count": len(rows), "workflow_runs": rows}
        elif len(pieces) == 3 and pieces[:2] == ["actions", "runs"]:
            value = self.runs[int(pieces[2])]
        elif pieces[-1] == "artifacts":
            rows = [row for row in self.artifacts.values() if row["workflow_run"]["id"] == int(pieces[2])]
            value = {"total_count": len(rows), "artifacts": rows}
        elif pieces[-1] == "jobs":
            rows = self.jobs[int(pieces[2])]
            value = {"total_count": len(rows), "jobs": rows}
        else:
            raise AssertionError(endpoint)
        return json.dumps(value).encode()

    def plan(self, now=6000, **kwargs):
        with mock.patch("feature_coverage_github._get", side_effect=self.get):
            return progress.plan(progress.ProgressApi(REPOSITORY), sha=SHA,
                                 matrix=progress.DEFAULT_MATRIX, now=now, **kwargs)

    def place_on_replay_clock(self, *, ready, initial, published_at, selected_at):
        for identifier, run in self.runs.items():
            if run["path"] == progress.PAGES:
                delta = selected_at - 1890  # Selection starts five seconds after enqueue.
            elif run["path"] == progress.E2E:
                delta = initial - 1010
            else:
                artifact = next(row for row in self.artifacts.values()
                                if row["workflow_run"]["id"] == identifier)
                key = artifact["name"].removeprefix("pages-mod-compatibility-")
                delta = ready[key] - 3010
            for field in ("created_at", "updated_at"):
                run[field] = utc(progress.timestamp(run[field]) + delta)
            if run["path"] == progress.PAGES:
                run["created_at"], run["updated_at"] = utc(selected_at - 1), utc(published_at)
            for job in self.jobs[identifier]:
                for record in [job, *job["steps"]]:
                    for field in ("started_at", "completed_at"):
                        record[field] = utc(progress.timestamp(record[field]) + delta)
            for artifact in self.artifacts.values():
                if artifact["workflow_run"]["id"] == identifier:
                    artifact["created_at"] = utc(progress.timestamp(artifact["created_at"]) + delta)


class PublicationPolicyTest(unittest.TestCase):
    def decision(self, published=(), ready=(), at=5000, published_at=2000, **kwargs):
        return progress.decide(expected=KEYS, published={key: 2000 for key in published},
            ready={key: (2000 if key in published else 3000) for key in set(ready) | set(published)},
            ordinary_ready=True, published_at=published_at, now=at, **kwargs)

    def test_initial_half_and_immediate_final_are_separate_milestones(self):
        first = self.decision(published_at=None)
        self.assertEqual("initial-ordinary", first.reason)
        half = set(sorted(KEYS)[:math.ceil(len(KEYS) / 2)])
        self.assertEqual("coalescing", self.decision(ready=half, at=3599).reason)
        self.assertEqual("half-coverage", self.decision(ready=half, at=3600).reason)
        self.assertEqual("final-complete", self.decision(published=half, ready=KEYS, at=3000).reason)

    def test_stalled_partial_deadline_and_scheduler_interval_are_deterministic(self):
        ready = {next(iter(KEYS))}
        deadline = 3000 + progress.PARTIAL_DEADLINE_SECONDS
        self.assertFalse(self.decision(ready=ready, at=deadline - 1).eligible)
        for offset in (0, progress.RECOVERY_INTERVAL_SECONDS):
            self.assertEqual("partial-deadline", self.decision(ready=ready, at=deadline + offset).reason)

    def test_final_during_build_queues_as_soon_as_publisher_is_available(self):
        active = self.decision(ready=KEYS, publisher_available=False)
        self.assertEqual("publisher-active", active.reason)
        self.assertEqual(5000, active.next_check_at)
        self.assertEqual("final-complete", self.decision(ready=KEYS, at=5001).reason)

    def test_cancelled_publish_does_not_advance_coverage_and_retry_is_bounded(self):
        for failures in range(progress.MAX_FAILED_PUBLICATIONS):
            self.assertTrue(self.decision(ready=KEYS, failed_publications=failures).eligible)
        self.assertEqual("publication-recovery-budget-exhausted", self.decision(
            ready=KEYS, failed_publications=progress.MAX_FAILED_PUBLICATIONS).reason)

    def test_foreign_future_or_regressing_progress_is_rejected(self):
        for fields in ({"ready": {"foreign": 2}}, {"ready": {next(iter(KEYS)): 6001}},
                       {"published": {next(iter(KEYS)): 2}}, {"now": float("nan")}):
            arguments = dict(expected=KEYS, published={}, ready={}, ordinary_ready=True,
                             published_at=2000, now=6000)
            arguments.update(fields)
            with self.assertRaises(progress.CoverageError):
                progress.decide(**arguments)

    def test_reference_readiness_replay_has_three_builds_and_separate_fanout_counts(self):
        data = json.loads((Path(__file__).parent / "fixtures/pages-progress-reference.json").read_text())
        self.assertEqual(34, len(data["wakes"]))
        self.assertEqual(16, len(data["readiness"]))
        # Slots are anonymous and the policy depends on cardinality, so replay the saved sixteen
        # against sixteen current targets rather than every target added to the matrix since.
        keys = set(sorted(KEYS)[:len(data["readiness"])])
        inventory = progress.inventory

        def recorded_inventory(*args, **kwargs):
            value = inventory(*args, **kwargs)
            return {**value, "include": [row for row in value["include"] if row["bundle_key"] in keys]}

        for module in (progress, sys.modules["evidence_target"]):
            patcher = mock.patch.object(module, "inventory", side_effect=recorded_inventory)
            patcher.start()
            self.addCleanup(patcher.stop)
        initial = progress.timestamp(data["wakes"][1]["created_at"])
        events = [(initial, "ordinary", None)]
        events.extend((progress.timestamp(row["ready_at"]), "ready", key)
                      for row, key in zip(data["readiness"], sorted(keys)))
        events.extend((progress.timestamp(row["created_at"]), "wake", None) for row in data["wakes"])
        final_at = max(time for time, _, _ in events)
        first_recovery = int(initial) // 3600 * 3600 + 43 * 60
        events.extend((at, "recovery", None) for at in range(first_recovery,
            int(final_at) + progress.RECOVERY_INTERVAL_SECONDS, progress.RECOVERY_INTERVAL_SECONDS))
        ready, published, published_at, active = {}, {}, None, None
        builds, checks, final_enqueued = [], 0, None
        scheduler_gets = preferred_collector_gets = 0
        selected_at = None
        events.sort()
        while events:
            at, kind, key = events.pop(0)
            if kind == "ready":
                ready[key] = at
            if kind == "complete":
                published, published_at = active, at
                active = None
            if at < initial or active is not None:
                continue
            checks += 1
            decision = progress.decide(expected=keys, published=published, ready=ready,
                ordinary_ready=True, published_at=published_at, now=at)
            fixture = GitHubFixture(published=published if published_at is not None else None, ready=ready,
                                    keys=keys)
            fixture.place_on_replay_clock(ready=ready, initial=initial,
                                         published_at=published_at, selected_at=selected_at)
            authenticated = fixture.plan(now=at)
            self.assertEqual(decision.eligible, authenticated["eligible"])
            self.assertEqual(decision.reason, authenticated["reason"])
            scheduler_gets += len(fixture.calls)
            if decision.eligible:
                builds.append({"at": at, "reason": decision.reason, "compatibility": len(ready)})
                if len(ready) == len(keys):
                    final_enqueued = at
                # Conservatively use the observed initial publication's complete elapsed time
                # (17m45s), not just its Build-job duration, for every replay publication.
                active = dict(ready)
                selected_at = at
                for handoff in [*authenticated["handoffs"], *authenticated["ordinary_handoffs"]]:
                    fixture.heads = [SHA, SHA]
                    before = len(fixture.calls)
                    with mock.patch("feature_coverage_github._get", side_effect=fixture.get):
                        ordinary = handoff in authenticated["ordinary_handoffs"]
                        progress.preferred_handoff(progress.ProgressApi(REPOSITORY),
                            identifier=handoff["artifact_id"],
                            name=("pages-e2e-" if ordinary else "pages-mod-compatibility-") + handoff["key"],
                            sha=SHA, ordinary=ordinary)
                    preferred_collector_gets += len(fixture.calls) - before
                events.append((at + 1065, "complete", None))
                events.sort()
        self.assertEqual(["initial-ordinary", "half-coverage", "final-complete"], [row["reason"] for row in builds])
        self.assertLessEqual(len(builds), 5)
        self.assertEqual(3 * len(keys) * 4, 192)
        self.assertEqual(544, data["observed_executed_collect_refresh_jobs"])
        self.assertEqual(8 * len(keys) * 4 + len(keys) * 2,
                         data["observed_executed_collect_refresh_jobs"])
        self.assertEqual(progress.timestamp(data["readiness"][-1]["ready_at"]), final_enqueued)
        self.assertGreater(checks, len(builds))
        print(json.dumps({"pages_reference_replay": {"recorded_wakes": len(data["wakes"]),
            "controller_snapshots": checks, "full_builds": len(builds),
            "collect_refresh_jobs": len(builds) * len(keys) * 4,
            "scheduler_fixture_GETs": scheduler_gets,
            "preferred_collector_fixture_GETs": preferred_collector_gets,
            "original_collector_runtime_GETs": "not reconstructed", "live_generation": False}}, sort_keys=True))


class PublicationAuthenticationTest(unittest.TestCase):
    def test_actual_bounded_api_operations_are_counted_separately_from_build_jobs(self):
        half = set(sorted(KEYS)[:math.ceil(len(KEYS) / 2)])
        fixture = GitHubFixture(ready=half)
        result = fixture.plan()
        self.assertTrue(result["eligible"])
        counts = result["api_operations"]
        # One inventory read per target plus the Pages and ordinary owners; one owner and job read
        # per ready compatibility handoff plus the ordinary owner (16 targets: 18/9/9/8, 49 calls).
        self.assertEqual({"current-head": 2, "artifact-inventory": len(KEYS) + 2, "owner-run": len(half) + 1,
                          "exact-attempt-jobs": len(half) + 1, "exact-artifact": len(half),
                          "workflow-runs": 3}, counts)
        self.assertEqual(len(KEYS) + 3 * len(half) + 9, len(fixture.calls))
        self.assertEqual(len(fixture.calls), sum(counts.values()))
        self.assertEqual(len(half), len({row["artifact_id"] for row in result["handoffs"]}))

    def test_complete_successful_owner_ends_recovery_before_compatibility_inventory(self):
        fixture = GitHubFixture(published=KEYS)
        result = fixture.plan()
        self.assertFalse(result["eligible"])
        self.assertEqual("complete", result["reason"])
        self.assertEqual(8, len(fixture.calls))
        self.assertFalse(any("name=pages-mod-compatibility-" in call for call in fixture.calls))

    def test_lost_wake_is_recovered_from_durable_handoffs_without_dispatch_payload(self):
        result = GitHubFixture(ready=KEYS).plan()
        self.assertEqual("final-complete", result["reason"])
        self.assertEqual(len(KEYS), len(result["handoffs"]))

    def test_same_head_replacement_wake_reopens_an_already_complete_publication(self):
        fixture = GitHubFixture(published=KEYS, ready=KEYS)
        result = fixture.plan(check_complete=True)
        self.assertEqual("final-complete", result["reason"])
        self.assertEqual(len(KEYS), len(result["handoffs"]))

    def test_lost_same_head_replacement_wake_is_recovered_automatically(self):
        fixture = GitHubFixture(published=KEYS, ready=KEYS)
        result = fixture.plan()
        self.assertEqual("final-complete", result["reason"])
        self.assertEqual(len(KEYS), len(result["handoffs"]))

    def test_lost_same_head_ordinary_attempt_replaces_every_raw_handoff_automatically(self):
        fixture = GitHubFixture(published=KEYS, ordinary_at=3000)
        result = fixture.plan()
        self.assertEqual("ordinary-replacement", result["reason"])
        self.assertEqual(len(KEYS), len(result["ordinary_handoffs"]))
        raw_anchor = next(row["bundle_key"] for row in progress.inventory()["include"]
                          if row["raw_retention_days"] == 90)
        selected = next(row for row in result["ordinary_handoffs"] if row["key"] == raw_anchor)
        fixture.heads = [SHA, SHA]
        with mock.patch("feature_coverage_github._get", side_effect=fixture.get):
            metadata = progress.preferred_handoff(progress.ProgressApi(REPOSITORY),
                identifier=selected["artifact_id"], name="pages-e2e-" + raw_anchor, sha=SHA, ordinary=True)
        self.assertEqual(900, metadata["workflow_run"]["id"])

    def test_partial_or_old_ordinary_replacement_does_not_supersede_a_complete_site(self):
        fixture = GitHubFixture(published=KEYS, ordinary_at=1000)
        self.assertEqual("complete", fixture.plan()["reason"])
        fixture = GitHubFixture(published=KEYS, ordinary_at=3000)
        identifier = next(row["id"] for row in fixture.artifacts.values() if row["workflow_run"]["id"] == 900)
        del fixture.artifacts[identifier]
        self.assertEqual("complete", fixture.plan()["reason"])

    def test_owner_finishing_during_build_is_not_hidden_by_a_later_cache_upload(self):
        fixture = GitHubFixture(published=KEYS, ready={next(iter(KEYS))})
        run = fixture.runs[2000]
        run["created_at"], run["updated_at"] = utc(1800), utc(1950)
        fixture.jobs[2000][0]["steps"][0].update(started_at=utc(1800), completed_at=utc(1950))
        artifact = next(row for row in fixture.artifacts.values() if row["workflow_run"]["id"] == 2000)
        artifact["created_at"] = utc(1850)
        self.assertEqual("final-complete", fixture.plan()["reason"])
        # The nominated ID, not the later-created stale cache, is independently consumed.
        fixture.heads = [SHA, SHA]
        with mock.patch("feature_coverage_github._get", side_effect=fixture.get):
            selected = progress.preferred_handoff(progress.ProgressApi(REPOSITORY),
                identifier=artifact["id"], name=artifact["name"], sha=SHA)
        self.assertEqual(artifact["id"], selected["id"])

    def test_preferred_id_is_not_permission_to_borrow_a_foreign_target_or_attempt(self):
        for mutation in ("target", "attempt", "expired", "head"):
            fixture = GitHubFixture(ready={next(iter(KEYS))})
            artifact = next(row for row in fixture.artifacts.values() if row["workflow_run"]["id"] == 2000)
            expected_name = artifact["name"]
            if mutation == "target":
                expected_name = "pages-mod-compatibility-foreign"
            elif mutation == "attempt":
                fixture.runs[2000]["run_attempt"] = 2
            elif mutation == "expired":
                artifact["expired"] = True
            else:
                fixture.heads = [SHA, "b" * 40]
            with mock.patch("feature_coverage_github._get", side_effect=fixture.get):
                with self.assertRaises(progress.CoverageError):
                    progress.preferred_handoff(progress.ProgressApi(REPOSITORY),
                        identifier=artifact["id"], name=expected_name, sha=SHA)

    def test_three_real_failed_publication_owners_stop_automatic_retry_but_failed_wakes_do_not(self):
        for publication in (False, True):
            fixture = GitHubFixture(ready=KEYS)
            for identifier in range(4000, 4003):
                run = fixture.owner(identifier, progress.PAGES, 4000, conclusion="failure")
                fixture.job(run, "Build atomic static site" if publication else "Authenticate wake",
                            "Fixture", 4000)
            result = fixture.plan()
            self.assertEqual(not publication, result["eligible"])
            self.assertEqual(3 if publication else 0, result["failed_publications"])

    def test_duplicate_and_reversed_arrivals_produce_the_same_progress(self):
        first, second = GitHubFixture(ready=KEYS), GitHubFixture(ready=KEYS)
        second.artifacts = dict(reversed(list(second.artifacts.items())))
        left, right = first.plan(), second.plan()
        self.assertEqual(left, right)

    def test_stale_and_foreign_handoffs_never_increase_current_progress(self):
        for field, value in (("head_sha", "b" * 40), ("head_branch", "other")):
            fixture = GitHubFixture(ready=KEYS)
            for artifact in fixture.artifacts.values():
                if artifact["name"].startswith("pages-mod-compatibility-"):
                    artifact["workflow_run"][field] = value
            result = fixture.plan()
            self.assertFalse(result["eligible"])
            self.assertEqual("unchanged", result["reason"])

    def test_source_race_stops_the_whole_snapshot(self):
        for heads in (["b" * 40], [SHA, "b" * 40]):
            fixture = GitHubFixture(ready=KEYS)
            fixture.heads = heads
            self.assertEqual("source-advanced", fixture.plan()["reason"])

    def test_exact_attempt_job_inventory_cannot_borrow_a_previous_attempt(self):
        fixture = GitHubFixture(ready={next(iter(KEYS))})
        fixture.runs[2000]["run_attempt"] = 2
        with self.assertRaisesRegex(progress.CoverageError, "foreign execution"):
            fixture.plan()

    def test_handoff_must_belong_to_upload_window_not_just_same_run(self):
        fixture = GitHubFixture(ready=KEYS)
        for artifact in fixture.artifacts.values():
            if artifact["name"].startswith("pages-mod-compatibility-"):
                artifact["created_at"] = utc(100)
        self.assertEqual("unchanged", fixture.plan()["reason"])

    def test_foreign_owner_and_expired_digestless_handoff_fail_closed(self):
        for mutation in ("repository", "digest", "expired"):
            fixture = GitHubFixture(ready=KEYS)
            for run in fixture.runs.values():
                if mutation == "repository" and run["path"] == progress.COMPATIBILITY:
                    run["head_repository"]["full_name"] = "foreign/repo"
            for artifact in fixture.artifacts.values():
                if artifact["name"].startswith("pages-mod-compatibility-"):
                    if mutation == "digest":
                        artifact["digest"] = ""
                    elif mutation == "expired":
                        artifact["expired"] = True
            self.assertEqual("unchanged", fixture.plan()["reason"])

    def test_no_initial_build_until_all_ordinary_handoffs_are_present(self):
        fixture = GitHubFixture(published=None)
        artifact_id = next(iter(fixture.artifacts))
        del fixture.artifacts[artifact_id]
        self.assertEqual("ordinary-handoffs-pending", fixture.plan()["reason"])
        self.assertEqual("initial-ordinary", GitHubFixture(published=None).plan()["reason"])

    def test_api_error_is_not_absence_and_budget_is_a_hard_stop(self):
        with mock.patch("feature_coverage_github._get", side_effect=progress.CoverageError("transport")):
            with self.assertRaisesRegex(progress.CoverageError, "transport"):
                progress.plan(progress.ProgressApi(REPOSITORY), sha=SHA,
                              matrix=progress.DEFAULT_MATRIX, now=6000)
        api = progress.ProgressApi(REPOSITORY)
        api.operations["test"] = progress.MAX_REQUESTS
        with self.assertRaisesRegex(progress.CoverageError, "GET budget"):
            api.current_sha()

    def test_workflow_admission_preserves_existing_collectors_and_actual_owner_rotation(self):
        discover = job_block("pages.yml", "discover")
        script = step_script("pages.yml", "discover", "Admit an authenticated coverage milestone before full publication")
        self.assertIn("steps.progress.outputs.eligible", discover)
        self.assertIn("publication_progress.py", script)
        self.assertNotIn("sleep ", script)
        self.assertNotIn("gh workflow run", script)
        self.assertIn("feature_pages.py --verify-runtime-tree", job_block("pages.yml", "build"))
        self.assertIn('--pages-run-id "${{ steps.owner.outputs.pages_run_id }}"', job_block("pages.yml", "rotate"))
        self.assertIn('cron: "43 * * * *"', (ROOT / ".github/workflows/pages.yml").read_text())
        wake = step_script("pages.yml", "wake-compatibility", "Dispatch one protected all-target deployment")
        self.assertLess(wake.index('"$PUBLICATION_SHA" != "$GITHUB_SHA"'),
                        wake.index("gh workflow run pages.yml"))
        self.assertIn('"$COVERAGE_SHA" != "$GITHUB_SHA"', wake)
        self.assertIn("artifact-ids: ${{ steps.artifact.outputs.artifact_id }}",
                      job_block("pages.yml", "collect-compatibility"))
        self.assertIn("artifact-ids: ${{ steps.artifact.outputs.artifact_id }}",
                      job_block("pages.yml", "collect"))
        self.assertIn("steps.inventory.outputs.cached == 'true'", discover)

    def test_publication_controller_uses_existing_nonimpact_scope_without_relaxing_unknown_paths(self):
        from mod_compatibility_impact import classify_paths
        self.assertFalse(classify_paths(["scripts/pages/publication_progress.py",
            "scripts/pages/select_compatibility_artifact.py", ".github/workflows/pages.yml",
            "scripts/ci/tests/test_pages_publication_progress.py"]).compatibility_required)
        self.assertTrue(classify_paths(["scripts/ci/unknown_publication_admission.py"]).compatibility_required)


if __name__ == "__main__":
    unittest.main()
