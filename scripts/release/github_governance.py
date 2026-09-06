#!/usr/bin/env python3
"""Audit or converge Quick Skin's GitHub rulesets and release environment.

The apply operation is deliberately fail-closed. It refuses to activate branch
rules until every branch they cover already contains the workflow jobs those
rules require. It never deletes unknown rulesets or deployment policies.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping
from urllib.parse import quote

from matrix import MatrixError, validate_matrix


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / "release" / "github-governance.json"


class GovernanceError(RuntimeError):
    pass


class ApiError(GovernanceError):
    def __init__(self, endpoint: str, stderr: str):
        super().__init__(f"GitHub API failed for {endpoint}: {stderr.strip()}")
        self.endpoint = endpoint
        self.stderr = stderr

    @property
    def not_found(self) -> bool:
        return "HTTP 404" in self.stderr


@dataclass(frozen=True)
class Operation:
    resource: str
    action: str


class GitHubClient:
    def __init__(self, *, api_version: str):
        self.api_version = api_version

    def _run(
        self,
        endpoint: str,
        *,
        method: str = "GET",
        payload: Mapping[str, Any] | None = None,
        paginate: bool = False,
        raw: bool = False,
    ) -> str:
        command = [
            "gh",
            "api",
            "--method",
            method,
            "--header",
            f"X-GitHub-Api-Version: {self.api_version}",
        ]
        if raw:
            command.extend(["--header", "Accept: application/vnd.github.raw+json"])
        if paginate:
            command.extend(["--paginate", "--slurp"])
        if payload is not None:
            command.extend(["--input", "-"])
        command.append(endpoint)
        try:
            completed = subprocess.run(
                command,
                input=(json.dumps(payload) if payload is not None else None),
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=True,
            )
        except (OSError, subprocess.CalledProcessError) as exc:
            stderr = exc.stderr if isinstance(exc, subprocess.CalledProcessError) else str(exc)
            raise ApiError(endpoint, stderr or "unknown error") from exc
        return completed.stdout

    def json(
        self,
        endpoint: str,
        *,
        method: str = "GET",
        payload: Mapping[str, Any] | None = None,
        paginate: bool = False,
        optional: bool = False,
    ) -> Any:
        try:
            raw = self._run(
                endpoint,
                method=method,
                payload=payload,
                paginate=paginate,
            )
        except ApiError as exc:
            if optional and exc.not_found:
                return None
            raise
        if not raw.strip():
            return None
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise GovernanceError(f"GitHub returned invalid JSON for {endpoint}") from exc

    def text(self, endpoint: str, *, optional: bool = False) -> str | None:
        try:
            return self._run(endpoint, raw=True)
        except ApiError as exc:
            if optional and exc.not_found:
                return None
            raise


def load_config(path: Path) -> dict[str, Any]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GovernanceError(f"cannot read governance config {path}: {exc}") from exc
    required = {
        "schema",
        "api_version",
        "repository",
        "managed_rulesets",
        "release_tag_pattern",
        "required_checks",
        "release_environment",
        "readiness",
    }
    if not isinstance(data, dict) or type(data.get("schema")) is not int or data["schema"] not in (1, 2):
        raise GovernanceError("unsupported governance schema")
    required.add("release_branch_pattern" if data["schema"] == 1 else "source_matrix")
    missing = sorted(required - data.keys())
    if missing:
        raise GovernanceError(f"governance config is missing: {', '.join(missing)}")
    if data["schema"] == 2:
        if data["source_matrix"] != "release/release-matrix.json":
            raise GovernanceError("shared governance must read the authoritative source matrix")
        if set(data["managed_rulesets"]) != {"default_branch", "release_tags"}:
            raise GovernanceError("shared governance manages the default branch and target tags")
        if "release_source" not in data["readiness"]:
            raise GovernanceError("shared governance requires release-source readiness")
        expected = data["release_environment"]["deployment_policies"]
        if expected != [{"type": "tag", "name": data["release_tag_pattern"]}]:
            raise GovernanceError("shared publication deploys only through canonical target tags")
        retired = data["release_environment"].get("retired_deployment_policies", [])
        if retired != [{"type": "branch", "name": "*-and-*-*"}]:
            raise GovernanceError("only the historical release-branch deployment policy may be retired")
    if not data["required_checks"]:
        raise GovernanceError("at least one required check is mandatory")
    return data


def pull_request_rule() -> dict[str, Any]:
    return {
        "type": "pull_request",
        "parameters": {
            "allowed_merge_methods": ["merge", "squash", "rebase"],
            "dismiss_stale_reviews_on_push": False,
            "require_code_owner_review": False,
            "require_last_push_approval": False,
            "required_approving_review_count": 0,
            "required_review_thread_resolution": True,
        },
    }


def required_checks_rule(contexts: Iterable[str]) -> dict[str, Any]:
    return {
        "type": "required_status_checks",
        "parameters": {
            "do_not_enforce_on_create": True,
            "required_status_checks": [
                {"context": context} for context in contexts
            ],
            "strict_required_status_checks_policy": True,
        },
    }


def branch_ruleset(
    *, name: str, includes: list[str], required_checks: list[str]
) -> dict[str, Any]:
    return {
        "name": name,
        "target": "branch",
        "enforcement": "active",
        "bypass_actors": [],
        "conditions": {"ref_name": {"include": includes, "exclude": []}},
        "rules": [
            {"type": "deletion"},
            {"type": "non_fast_forward"},
            pull_request_rule(),
            required_checks_rule(required_checks),
        ],
    }


def desired_rulesets(config: Mapping[str, Any]) -> tuple[dict[str, Any], ...]:
    names = config["managed_rulesets"]
    checks = list(config["required_checks"])
    rulesets = [
        branch_ruleset(
            name=names["default_branch"],
            includes=["~DEFAULT_BRANCH"],
            required_checks=checks,
        ),
        {
            "name": names["release_tags"],
            "target": "tag",
            "enforcement": "active",
            "bypass_actors": [],
            "conditions": {
                "ref_name": {
                    "include": [f"refs/tags/{config['release_tag_pattern']}"],
                    "exclude": [],
                }
            },
            "rules": [
                {"type": "deletion"},
                {"type": "non_fast_forward"},
            ],
        },
    ]
    if config["schema"] == 1:
        rulesets.insert(1, branch_ruleset(
            name=names["release_branches"],
            includes=[f"refs/heads/{config['release_branch_pattern']}"],
            required_checks=checks,
        ))
    # Historical branch rulesets are neither updated nor deleted by shared-source governance.
    return tuple(rulesets)


def desired_environment(config: Mapping[str, Any]) -> dict[str, Any]:
    environment = config["release_environment"]
    reviewer = environment["reviewer"]
    return {
        "wait_timer": environment["wait_timer"],
        "prevent_self_review": environment["prevent_self_review"],
        "reviewers": [{"type": reviewer["type"], "id": reviewer["id"]}],
        "deployment_branch_policy": {
            "protected_branches": False,
            "custom_branch_policies": True,
        },
    }


def subset_matches(actual: Any, expected: Any) -> bool:
    if isinstance(expected, Mapping):
        return isinstance(actual, Mapping) and all(
            key in actual and subset_matches(actual[key], value)
            for key, value in expected.items()
        )
    if isinstance(expected, list):
        return isinstance(actual, list) and len(actual) == len(expected) and all(
            subset_matches(left, right) for left, right in zip(actual, expected)
        )
    return actual == expected


def environment_projection(remote: Mapping[str, Any]) -> dict[str, Any]:
    timer = 0
    prevent_self_review = False
    reviewers: list[dict[str, Any]] = []
    for rule in remote.get("protection_rules", []):
        if rule.get("type") == "wait_timer":
            timer = rule.get("wait_timer", 0)
        elif rule.get("type") == "required_reviewers":
            prevent_self_review = rule.get("prevent_self_review", False)
            for entry in rule.get("reviewers", []):
                reviewer = entry.get("reviewer", {})
                reviewers.append(
                    {"type": entry.get("type"), "id": reviewer.get("id")}
                )
    reviewers.sort(key=lambda item: (str(item["type"]), int(item["id"] or -1)))
    return {
        "wait_timer": timer,
        "prevent_self_review": prevent_self_review,
        "reviewers": reviewers,
        "deployment_branch_policy": remote.get("deployment_branch_policy"),
    }


def flatten_paginated(value: Any) -> list[Any]:
    if not isinstance(value, list):
        raise GovernanceError("paginated GitHub response was not a list")
    if value and all(isinstance(page, list) for page in value):
        return [item for page in value for item in page]
    return value


def require_tokens(source: str | None, tokens: Iterable[str], label: str) -> list[str]:
    if source is None:
        return [f"{label}: file is missing"]
    return [f"{label}: missing {token!r}" for token in tokens if token not in source]


def branch_names(client: GitHubClient, repository: str) -> list[str]:
    response = client.json(
        f"repos/{repository}/branches?per_page=100",
        paginate=True,
    )
    return sorted(str(item["name"]) for item in flatten_paginated(response))


def readiness_errors(
    client: GitHubClient, config: Mapping[str, Any]
) -> list[str]:
    repository = str(config["repository"])
    metadata = client.json(f"repos/{repository}")
    default = str(metadata["default_branch"])
    source_ref = default
    if config["schema"] == 2:
        head = client.json(f"repos/{repository}/branches/{quote(default, safe='')}")
        source_ref = head.get("commit", {}).get("sha") if isinstance(head, Mapping) else None
        if not isinstance(source_ref, str) or not re.fullmatch(r"[0-9a-f]{40}", source_ref):
            return ["shared-source default branch has no exact commit identity"]
        path = str(config["source_matrix"])
        raw = client.text(f"repos/{repository}/contents/{quote(path, safe='/')}?ref={source_ref}",
                          optional=True)
        try:
            data = json.loads(raw) if raw is not None else None
            if not isinstance(data, dict):
                raise GovernanceError("remote source matrix is absent or not an object")
            validate_matrix(data)
            if data["schema_version"] != 3 or data["project"]["release_branch"] != default:
                raise GovernanceError("remote matrix does not declare the default branch as shared source")
        except (MatrixError, GovernanceError, ValueError, TypeError) as exc:
            return [f"shared-source release matrix is not ready: {exc}"]
        releases = []
    else:
        releases = [name for name in branch_names(client, repository)
                    if fnmatch.fnmatchcase(name, str(config["release_branch_pattern"]))]
        if not releases:
            return ["no release branches match the configured pattern"]

    sources: dict[tuple[str, str], str | None] = {}
    errors: list[str] = []

    def validate(branch: str, requirements: Mapping[str, list[str]]) -> None:
        for path, tokens in requirements.items():
            key = (branch, path)
            if key not in sources:
                endpoint = (
                    f"repos/{repository}/contents/{quote(path, safe='/')}"
                    f"?ref={quote(source_ref if config['schema'] == 2 else branch, safe='')}"
                )
                sources[key] = client.text(endpoint, optional=True)
            errors.extend(
                require_tokens(sources[key], tokens, f"{branch}:{path}")
            )

    common = config["readiness"]["all_protected_branches"]
    validate(default, common)
    validate(default, config["readiness"]["default_branch"])
    if config["schema"] == 2:
        validate(default, config["readiness"]["release_source"])
    for branch in releases:
        validate(branch, common)
        validate(branch, config["readiness"]["release_branches"])
    return errors


def managed_remote_rulesets(
    client: GitHubClient, config: Mapping[str, Any]
) -> dict[str, Mapping[str, Any] | None]:
    repository = str(config["repository"])
    summaries = client.json(f"repos/{repository}/rulesets")
    by_name: dict[str, list[Mapping[str, Any]]] = {}
    for item in summaries:
        by_name.setdefault(str(item["name"]), []).append(item)
    result: dict[str, Mapping[str, Any] | None] = {}
    for desired in desired_rulesets(config):
        matches = by_name.get(desired["name"], [])
        if len(matches) > 1:
            raise GovernanceError(f"duplicate managed ruleset {desired['name']!r}")
        result[desired["name"]] = (
            client.json(f"repos/{repository}/rulesets/{matches[0]['id']}")
            if matches
            else None
        )
    return result


def remote_environment(
    client: GitHubClient, config: Mapping[str, Any]
) -> tuple[Mapping[str, Any] | None, list[Mapping[str, Any]]]:
    repository = str(config["repository"])
    name = quote(str(config["release_environment"]["name"]), safe="")
    environment = client.json(
        f"repos/{repository}/environments/{name}", optional=True
    )
    if environment is None:
        return None, []
    response = client.json(
        f"repos/{repository}/environments/{name}/deployment-branch-policies?per_page=100"
    )
    return environment, list(response.get("branch_policies", []))


def policy_identity(policy: Mapping[str, Any]) -> tuple[str, str | None]:
    return str(policy.get("name")), (
        str(policy["type"]) if policy.get("type") is not None else None
    )


def retired_policies(config: Mapping[str, Any], policies: list[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    allowed = {policy_identity(policy) for policy in
               config["release_environment"].get("retired_deployment_policies", [])}
    matches = [policy for policy in policies if policy_identity(policy) in allowed]
    identities = [policy_identity(policy) for policy in matches]
    ids = [policy.get("id") for policy in matches]
    if (len(set(identities)) != len(identities) or any(type(value) is not int or value <= 0 for value in ids)
            or len(set(ids)) != len(ids)):
        raise GovernanceError("retired deployment policy has an ambiguous or invalid identity")
    return matches


def plan(client: GitHubClient, config: Mapping[str, Any]) -> list[Operation]:
    repository = str(config["repository"])
    operations: list[Operation] = []
    immutable = client.json(f"repos/{repository}/immutable-releases")
    if not immutable.get("enabled", False):
        operations.append(Operation("immutable releases", "enable"))

    remote_rules = managed_remote_rulesets(client, config)
    for desired in desired_rulesets(config):
        remote = remote_rules[desired["name"]]
        if remote is None:
            operations.append(Operation(f"ruleset:{desired['name']}", "create"))
        elif not subset_matches(remote, desired):
            operations.append(Operation(f"ruleset:{desired['name']}", "update"))

    environment, policies = remote_environment(client, config)
    environment_name = str(config["release_environment"]["name"])
    if environment is None:
        operations.append(Operation(f"environment:{environment_name}", "create"))
    elif not subset_matches(environment_projection(environment), desired_environment(config)):
        operations.append(Operation(f"environment:{environment_name}", "update"))

    expected_policies = {
        policy_identity(item)
        for item in config["release_environment"]["deployment_policies"]
    }
    actual_policies = {policy_identity(item) for item in policies}
    retired = retired_policies(config, policies)
    unknown = actual_policies - expected_policies - {policy_identity(policy) for policy in retired}
    if unknown:
        formatted = ", ".join(f"{kind or '?'}:{name}" for name, kind in sorted(unknown))
        raise GovernanceError(
            "release environment contains unmanaged deployment policies: " + formatted
        )
    for name, kind in sorted(expected_policies - actual_policies):
        operations.append(
            Operation(f"environment-policy:{kind}:{name}", "create")
        )
    for policy in retired:
        name, kind = policy_identity(policy)
        operations.append(Operation(f"environment-policy:{kind}:{name}", "delete"))
    return operations


def apply(client: GitHubClient, config: Mapping[str, Any]) -> None:
    repository = str(config["repository"])
    pending = plan(client, config)
    if not pending:
        return
    unready = readiness_errors(client, config)
    if unready:
        raise GovernanceError(
            "refusing to activate governance before every protected branch is ready:\n- "
            + "\n- ".join(unready)
        )

    client.json(
        f"repos/{repository}/immutable-releases",
        method="PUT",
        payload={},
    )

    environment = config["release_environment"]
    environment_name = quote(str(environment["name"]), safe="")
    client.json(
        f"repos/{repository}/environments/{environment_name}",
        method="PUT",
        payload=desired_environment(config),
    )
    _, remote_policies = remote_environment(client, config)
    for policy in retired_policies(config, remote_policies):
        client.json(
            f"repos/{repository}/environments/{environment_name}/deployment-branch-policies/{policy['id']}",
            method="DELETE",
        )
    existing_policies = {policy_identity(item) for item in remote_policies}
    for policy in environment["deployment_policies"]:
        if policy_identity(policy) not in existing_policies:
            client.json(
                f"repos/{repository}/environments/{environment_name}/deployment-branch-policies",
                method="POST",
                payload={"name": policy["name"], "type": policy["type"]},
            )

    remote_rules = managed_remote_rulesets(client, config)
    for desired in desired_rulesets(config):
        remote = remote_rules[desired["name"]]
        if remote is None:
            client.json(
                f"repos/{repository}/rulesets", method="POST", payload=desired
            )
        elif not subset_matches(remote, desired):
            client.json(
                f"repos/{repository}/rulesets/{remote['id']}",
                method="PUT",
                payload=desired,
            )

    remaining = plan(client, config)
    if remaining:
        detail = ", ".join(f"{item.action} {item.resource}" for item in remaining)
        raise GovernanceError(f"post-apply governance drift remains: {detail}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("show", "audit", "readiness", "apply"))
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument(
        "--confirm",
        help="Required for apply; must exactly equal the configured owner/repository",
    )
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        if args.command == "show":
            print(
                json.dumps(
                    {
                        "rulesets": desired_rulesets(config),
                        "environment": desired_environment(config),
                    },
                    indent=2,
                    sort_keys=True,
                )
            )
            return 0

        client = GitHubClient(api_version=str(config["api_version"]))
        if args.command == "readiness":
            errors = readiness_errors(client, config)
            if errors:
                print("GitHub governance is not ready:\n- " + "\n- ".join(errors))
                return 3
            print("Every protected branch is ready for governance activation.")
            return 0

        if args.command == "audit":
            operations = plan(client, config)
            if operations:
                for operation in operations:
                    print(f"{operation.action}: {operation.resource}")
                return 3
            print("GitHub governance matches the declared state.")
            return 0

        if args.confirm != config["repository"]:
            raise GovernanceError(
                f"apply requires --confirm {config['repository']}"
            )
        apply(client, config)
        print("GitHub governance converged and passed the post-apply audit.")
        return 0
    except GovernanceError as exc:
        print(f"governance error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
