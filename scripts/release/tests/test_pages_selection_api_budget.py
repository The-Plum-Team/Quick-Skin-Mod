from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import test_pages_artifact_rotation as fixtures
import select_compatibility_artifact as compatibility


class PagesSelectionApiBudgetTest(unittest.TestCase):
    def fixture(self, family: str, newest: str = "handoff"):
        key, sha = "mc1.20.1", fixtures.TARGET_SHA
        handoff_name = f"pages-{'mod-compatibility' if family == 'compatibility' else 'e2e'}-{key}"
        cache_name = f"pages-{'mod-compatibility-cache' if family == 'compatibility' else 'cache'}-{key}--{sha}"
        handoff = fixtures.artifact(30 if newest == "handoff" else 20, handoff_name,
            "2026-09-08T12:00:00Z" if newest == "handoff" else "2026-09-08T11:00:00Z",
            run_id=30 if newest == "handoff" else 20, head_branch="master", head_sha=sha)
        cache = fixtures.artifact(30 if newest == "cache" else 20, cache_name,
            "2026-09-08T12:00:00Z" if newest == "cache" else "2026-09-08T11:00:00Z",
            run_id=30 if newest == "cache" else 20, head_branch="master", head_sha=sha)
        handoff_workflow = (compatibility.COMPATIBILITY_REVIEW_WORKFLOW if family == "compatibility"
                            else fixtures.select_artifact.E2E_WORKFLOW)
        api = fixtures.FakeApi(keep=cache,
            inventories={handoff_name: [handoff], cache_name: [cache]},
            runs={item.run_id: fixtures.run(item.run_id, workflow=workflow,
                event="workflow_dispatch", branch="master", sha=sha)
                for item, workflow in ((handoff, handoff_workflow), (cache, compatibility.PAGES_WORKFLOW))},
            branch_shas={"master": sha})
        selector = compatibility.select_source if family == "compatibility" else fixtures.select_source
        arguments = dict(repository=fixtures.REPOSITORY, branch="master", bundle_key=key, current_sha=sha)
        return api, selector, arguments, handoff, cache

    def test_complete_exact_inventories_authenticate_only_the_global_newest_owner(self):
        for family in ("ordinary", "compatibility"):
            for newest in ("handoff", "cache"):
                with self.subTest(family=family, newest=newest):
                    api, selector, arguments, handoff, cache = self.fixture(family, newest)
                    del api.runs[20]  # Reading the irrelevant older owner is an error.
                    with patch.object(api, "list_artifacts", wraps=api.list_artifacts) as inventory, \
                            patch.object(api, "get_run", wraps=api.get_run) as owners:
                        selected = selector(api, **arguments)
                    self.assertEqual(30, selected.run_id)
                    self.assertEqual([handoff.name, cache.name], [call.args[0] for call in inventory.call_args_list])
                    self.assertEqual([30], [call.args[0] for call in owners.call_args_list])

    def test_invalid_newest_owner_falls_back_across_families(self):
        for family in ("ordinary", "compatibility"):
            with self.subTest(family=family):
                api, selector, arguments, _handoff, cache = self.fixture(family)
                api.runs[30]["path"] = ".github/workflows/build-gate.yml"
                with patch.object(api, "get_run", wraps=api.get_run) as owners:
                    self.assertEqual(cache, selector(api, **arguments))
                self.assertEqual([30, 20], [call.args[0] for call in owners.call_args_list])

    def test_unavailable_owner_stops_after_one_request_instead_of_probing_older_evidence(self):
        for family in ("ordinary", "compatibility"):
            for status in (403, 429):
                with self.subTest(family=family, status=status):
                    api, selector, arguments, _handoff, _cache = self.fixture(family)
                    with patch.object(api, "get_run", side_effect=fixtures.ApiError(status, "API unavailable")) as owners:
                        with self.assertRaises(fixtures.ApiError):
                            selector(api, **arguments)
                    self.assertEqual([30], [call.args[0] for call in owners.call_args_list])

    def test_second_inventory_failure_is_not_hidden_by_a_valid_handoff(self):
        for family in ("ordinary", "compatibility"):
            with self.subTest(family=family):
                api, selector, arguments, handoff, _cache = self.fixture(family)
                with patch.object(api, "list_artifacts", side_effect=[[handoff], fixtures.ApiError(403, "quota")]), \
                        patch.object(api, "get_run", wraps=api.get_run) as owners:
                    with self.assertRaises(fixtures.ApiError):
                        selector(api, **arguments)
                owners.assert_not_called()

    def test_lossless_selection_never_lists_or_authenticates_compact_cache(self):
        api, selector, arguments, handoff, _cache = self.fixture("ordinary", "cache")
        del api.runs[30]
        with patch.object(api, "list_artifacts", wraps=api.list_artifacts) as inventory:
            self.assertEqual(handoff, selector(api, require_raw=True, **arguments))
        self.assertEqual([handoff.name], [call.args[0] for call in inventory.call_args_list])

    def test_compatibility_cli_does_not_emit_absence_after_quota_failure(self):
        api, _selector, arguments, _handoff, _cache = self.fixture("compatibility")
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "github-output"
            with patch.object(compatibility, "GitHubApi", return_value=api), \
                    patch.object(compatibility.os, "environ", {"GH_TOKEN": "fixture"}), \
                    patch.object(api, "get_run", side_effect=fixtures.ApiError(403, "installation quota")):
                result = compatibility.main(["--repository", arguments["repository"], "--branch", "master",
                    "--bundle-key", arguments["bundle_key"], "--github-output", str(output)])
            self.assertEqual(2, result)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
