from __future__ import annotations

import sys
import copy
import json
import unittest
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "release"))

import github_governance  # noqa: E402


class LocalGovernanceRemote:
    """Stateful local API fixture; no subprocesses or network access."""
    def __init__(self, config):
        self.config = config
        self.prefix = "repos/" + config["repository"]
        self.calls = []
        self.source_sha = "a" * 40
        self.source_matrix = (ROOT / config["source_matrix"]).read_text()
        self.rulesets = {index: {**rule, "id": index} for index, rule in
                         enumerate(github_governance.desired_rulesets(config), 1)}
        self.rulesets[99] = {"id": 99, "name": "Quick Skin release branches", "historical": True}
        self.policies = [{"id": 10, "type": "tag", "name": "mc*-v*"},
                         {"id": 11, "type": "branch", "name": "*-and-*-*"}]

    def json(self, endpoint, *, method="GET", payload=None, **kwargs):
        self.calls.append((method, endpoint))
        if method == "DELETE":
            expected = self.prefix + "/environments/release/deployment-branch-policies/11"
            if endpoint != expected:
                raise AssertionError("unexpected deletion: " + endpoint)
            self.policies = [policy for policy in self.policies if policy["id"] != 11]
            return None
        if method == "PUT" and endpoint in (self.prefix + "/immutable-releases",
                                              self.prefix + "/environments/release"):
            return {}
        if method != "GET":
            raise AssertionError((method, endpoint))
        if endpoint == self.prefix:
            return {"default_branch": "master"}
        if endpoint == self.prefix + "/branches/master":
            return {"commit": {"sha": self.source_sha}}
        if endpoint == self.prefix + "/immutable-releases":
            return {"enabled": True}
        if endpoint == self.prefix + "/rulesets":
            return list(self.rulesets.values())
        if endpoint.startswith(self.prefix + "/rulesets/"):
            return self.rulesets[int(endpoint.rsplit("/", 1)[1])]
        if endpoint == self.prefix + "/environments/release":
            desired = github_governance.desired_environment(self.config)
            return {"deployment_branch_policy": desired["deployment_branch_policy"],
                    "protection_rules": [{"type": "required_reviewers", "prevent_self_review": False,
                                          "reviewers": [{"type": "User", "reviewer": {"id": 105746531}}]}]}
        if endpoint == self.prefix + "/environments/release/deployment-branch-policies?per_page=100":
            return {"branch_policies": self.policies}
        raise AssertionError("unexpected API operation: " + endpoint)

    def text(self, endpoint, **kwargs):
        self.calls.append(("GET", endpoint))
        prefix = self.prefix + "/contents/"
        if not endpoint.startswith(prefix):
            raise AssertionError(endpoint)
        parsed = urlsplit(endpoint)
        if parse_qs(parsed.query) != {"ref": [self.source_sha]}:
            raise AssertionError("readiness did not pin its source commit: " + endpoint)
        path = unquote(parsed.path[len(prefix):])
        return self.source_matrix if path == self.config["source_matrix"] else (ROOT / path).read_text()


class GitHubGovernanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = github_governance.load_config(
            ROOT / "release" / "github-governance.json"
        )

    def test_branch_rules_have_no_bypass_and_require_stable_strict_checks(self) -> None:
        default, tags = github_governance.desired_rulesets(self.config)
        for ruleset in (default, tags):
            self.assertEqual(ruleset["enforcement"], "active")
            self.assertEqual(ruleset["bypass_actors"], [])
            self.assertIn({"type": "deletion"}, ruleset["rules"])
            self.assertIn({"type": "non_fast_forward"}, ruleset["rules"])

        for ruleset in (default,):
            checks = next(
                rule for rule in ruleset["rules"]
                if rule["type"] == "required_status_checks"
            )["parameters"]
            self.assertTrue(checks["strict_required_status_checks_policy"])
            self.assertEqual(
                [item["context"] for item in checks["required_status_checks"]],
                ["Build and verify", "Packaged E2E gate"],
            )
            pull_request = next(
                rule for rule in ruleset["rules"]
                if rule["type"] == "pull_request"
            )
            self.assertEqual(
                pull_request["parameters"]["required_approving_review_count"], 0
            )
            self.assertTrue(
                pull_request["parameters"]["required_review_thread_resolution"]
            )

        self.assertEqual(
            tags["conditions"]["ref_name"]["include"], ["refs/tags/mc*-v*"]
        )

    def test_release_environment_has_human_review_and_narrow_sources(self) -> None:
        environment = github_governance.desired_environment(self.config)
        self.assertEqual(
            environment["reviewers"], [{"type": "User", "id": 105746531}]
        )
        self.assertFalse(environment["prevent_self_review"])
        self.assertEqual(
            {
                github_governance.policy_identity(item)
                for item in self.config["release_environment"]["deployment_policies"]
            },
            {("mc*-v*", "tag")},
        )

    def test_historical_governance_can_still_be_loaded_without_retiring_its_policies(self):
        legacy = github_governance.load_config(Path(__file__).parent / "fixtures/legacy-governance-schema1.json")
        self.assertEqual(3, len(github_governance.desired_rulesets(legacy)))
        self.assertEqual([], github_governance.retired_policies(legacy,
            [{"id": 11, "type": "branch", "name": "*-and-*-*"}]))

    def test_shared_readiness_uses_one_exact_source_commit_without_branch_discovery(self):
        remote = LocalGovernanceRemote(self.config)
        self.assertEqual([], github_governance.readiness_errors(remote, self.config))
        self.assertFalse(any("/branches?" in endpoint for _, endpoint in remote.calls))
        self.assertTrue(any("?ref=" + remote.source_sha in endpoint for _, endpoint in remote.calls))
        for raw in (None, "[]", "{}", '{"schema_version":2}', "invalid JSON"):
            remote.source_matrix = raw
            with self.subTest(raw=raw):
                self.assertTrue(github_governance.readiness_errors(remote, self.config))

    def test_governance_retires_only_the_declared_environment_policy_and_preserves_old_rules(self):
        remote = LocalGovernanceRemote(self.config)
        original_rules = copy.deepcopy(remote.rulesets)
        self.assertEqual([github_governance.Operation("environment-policy:branch:*-and-*-*", "delete")],
                         github_governance.plan(remote, self.config))
        github_governance.apply(remote, self.config)
        self.assertEqual([], github_governance.plan(remote, self.config))
        self.assertEqual(original_rules, remote.rulesets)
        self.assertEqual([{"id": 10, "type": "tag", "name": "mc*-v*"}], remote.policies)
        self.assertEqual(1, sum(method == "DELETE" for method, _ in remote.calls))
        self.assertNotIn(("GET", remote.prefix + "/rulesets/99"), remote.calls)

    def test_unmanaged_or_ambiguous_deployment_policies_fail_before_writes(self):
        variants = [
            [{"id": 12, "type": "branch", "name": "unexpected"}],
            [{"id": 12, "type": "branch", "name": "*-and-*-*"}],
        ]
        for extra in variants:
            remote = LocalGovernanceRemote(self.config)
            remote.policies.extend(extra)
            with self.subTest(extra=extra), self.assertRaises(github_governance.GovernanceError):
                github_governance.apply(remote, self.config)
            self.assertTrue(all(method == "GET" for method, _ in remote.calls))
        for policy_id in (None, True, 0, "11"):
            with self.subTest(policy_id=policy_id), self.assertRaises(github_governance.GovernanceError):
                github_governance.retired_policies(self.config,
                    [{"id": policy_id, "type": "branch", "name": "*-and-*-*"}])

    def test_semantic_comparison_ignores_api_metadata_but_rejects_drift(self) -> None:
        expected = {"name": "managed", "rules": [{"type": "deletion"}]}
        actual = {
            "id": 42,
            "name": "managed",
            "rules": [{"type": "deletion", "unexpected_metadata": "safe"}],
        }
        self.assertTrue(github_governance.subset_matches(actual, expected))
        actual["rules"] = [{"type": "non_fast_forward"}]
        self.assertFalse(github_governance.subset_matches(actual, expected))

    def test_readiness_tokens_fail_closed(self) -> None:
        self.assertEqual(
            github_governance.require_tokens(
                "pull_request:\n  job:\n    name: Build and verify\n",
                ("pull_request:", "name: Build and verify"),
                "master:build-gate",
            ),
            [],
        )
        self.assertEqual(
            github_governance.require_tokens(
                None, ("required",), "release:workflow"
            ),
            ["release:workflow: file is missing"],
        )
        self.assertEqual(
            github_governance.require_tokens(
                "pull_request:\n", ("Packaged E2E gate",), "release:e2e"
            ),
            ["release:e2e: missing 'Packaged E2E gate'"],
        )


if __name__ == "__main__":
    unittest.main()
