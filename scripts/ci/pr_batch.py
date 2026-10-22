#!/usr/bin/env python3
"""Validate several pull requests with one Build and Packaged E2E run through a batch PR.

`prepare` squashes each open same-repository PR to master, in order, onto current master in a
temporary worktree, pushes the result as `batch/<name>` and opens one ordinary PR for it. Draft
PRs start no Build or Minecraft work, so a set of drafts costs one complete gate instead of one
per PR. `settle` closes the included PRs after that batch PR has merged, but only those whose
head is still the exact commit that was batched. See ADR 0009 and docs/ci/PR-BATCHES.md.

A batch carries ordinary changes only (ADR 0014). `prepare` reads the allowed-path list
`scripts/ci/mod-base-batch-paths.json` from the `master` commit it builds on, never from a member,
and refuses a member whose own patch touches any other path: the control plane of the shared
pipeline, kit bumps, the release matrix, governance, and the Gradle wrapper and dependency policy
take their own pull request. The list has the format of mod-base's `ci batch-prepare
--allowed-paths`. `paths` derives it from the tracked tree and `BATCH_PROTECTED_PATHS`.
`settle` believes the batch marker only where the merged pull request's own commits say the same.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[2]
GOVERNANCE = ROOT / "release" / "github-governance.json"
BASE = "master"
PREFIX = "batch/"
MAX_PULLS = 50
MAX_TITLE = 200
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
SCHEMA_VERSION = 1
SHA = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
NAME = re.compile(r"[a-z0-9][a-z0-9._-]{0,62}")
MARKER = re.compile(r"<!-- quick-skin-batch (\{[^\n]*\}) -->")
BATCH_PATHS = "scripts/ci/mod-base-batch-paths.json"
MAX_BATCH_PATHS = 4096
# Paths no batch may change, each with everything below it. A change to any of them takes its own
# reviewed pull request (ADR 0014): the shared pipeline's managed callers, adapter and policy
# files that `mod-base-build.json` pins, the kit pin and config, the activation manifest, the
# release matrix and governance, the Gradle wrapper and dependency policy, the plan input
# `gradle.properties`, the Pages adapter of `site/mod-base.json`, and this tool with its own list.
BATCH_PROTECTED_PATHS = (
    ".gitattributes",
    ".github",
    "docs/ai/shared",
    "e2e/scenario-contract.json",
    "e2e/scenario_contract.py",
    "gradle.properties",
    "gradle/dependency-locks",
    "gradle/repository-policy.gradle.kts",
    "gradle/verification-metadata.xml",
    "gradle/wrapper",
    "gradlew",
    "gradlew.bat",
    "release",
    "scripts/ci/mod-base-batch-paths.json",
    "scripts/ci/mod-base-build.json",
    "scripts/ci/mod_base_build_adapter.py",
    "scripts/ci/mod_base_build_candidate.py",
    "scripts/ci/mod_base_build_dispatch.py",
    "scripts/ci/mod_base_build_policy.py",
    "scripts/ci/mod_base_kit.py",
    "scripts/ci/pr_batch.py",
    "scripts/pages/mod_base_adapter.py",
    "scripts/release/artifact_manifest.py",
    "scripts/release/generate_sbom.py",
    "scripts/release/github_governance.py",
    "scripts/release/matrix.py",
    "scripts/release/release_identity.py",
    "scripts/release/verify_release.py",
    "site",
)
# What a member's patch may give a path: a regular or executable file or a symbolic link, or none.
PATCH_MODES = {"000000", "100644", "100755", "120000"}


class BatchError(ValueError):
    """The requested batch cannot be prepared or settled without guessing."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise BatchError(message)


class GitHub:
    """The bounded `gh api` subset used here; tests substitute an in-memory fake."""

    def __init__(self, repository: str):
        require(REPOSITORY.fullmatch(repository) is not None, "invalid repository")
        self.repository = repository

    def request(self, method: str, endpoint: str, body: dict[str, Any] | None = None) -> Any:
        command = ["gh", "api", "--method", method, f"repos/{self.repository}/{endpoint}"]
        if body is not None:
            command += ["--input", "-"]
        result = subprocess.run(command, input=json.dumps(body) if body is not None else "",
                                capture_output=True, text=True, timeout=120)
        require(result.returncode == 0,
                f"GitHub {method} {endpoint} failed: {result.stderr.strip()[:300]}")
        require(len(result.stdout) <= MAX_RESPONSE_BYTES, "GitHub response exceeds its limit")
        return json.loads(result.stdout) if result.stdout.strip() else None


def git(repository: Path, *args: str, stdin: str | None = None, check: bool = True
        ) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(["git", "-C", str(repository), *args], input=stdin if stdin is not None else "",
                            capture_output=True, text=True)
    require(not check or result.returncode == 0,
            f"git {args[0]} failed: {(result.stderr or result.stdout).strip()[:500]}")
    return result


def default_repository() -> str:
    repository = json.loads(GOVERNANCE.read_text(encoding="utf-8"))["repository"]
    require(isinstance(repository, str) and REPOSITORY.fullmatch(repository) is not None,
            "governance names no valid repository")
    return repository


def remote_for(repository: Path, name: str) -> str:
    """Return the Git remote that points at exactly this GitHub repository."""
    matches = []
    for line in git(repository, "remote", "-v").stdout.splitlines():
        remote, url, *_ = line.split()
        path = re.sub(r"^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)", "", url)
        if path != url and path.removesuffix(".git").lower() == name.lower():
            matches.append(remote)
    matches = sorted(set(matches))
    require(len(matches) == 1, f"expected one Git remote for {name}, found {matches or 'none'}; pass --remote")
    return matches[0]


def is_repo_path(path: Any) -> bool:
    """A canonical repository-relative path: no empty, `.`, `..` or `.git` component."""
    return (isinstance(path, str) and 0 < len(path) <= 4096 and path.isprintable() and "\\" not in path
            and all(part not in ("", ".", "..") and part.lower() != ".git" for part in path.split("/")))


def ancestors(path: str) -> list[str]:
    """The path and every directory above it, shortest first."""
    parts = path.split("/")
    return ["/".join(parts[:count]) for count in range(1, len(parts) + 1)]


def is_protected(path: str) -> bool:
    return any(prefix in BATCH_PROTECTED_PATHS for prefix in ancestors(path))


def batch_paths_policy(files: Iterable[str]) -> list[str]:
    """The allowed-path list for a tree: everything but `BATCH_PROTECTED_PATHS`, as few entries as
    possible. A directory that holds no protected path is one entry, which admits whatever is
    later added below it; a new path anywhere else needs the list regenerated first."""
    tracked = sorted(set(files))
    require(all(is_repo_path(path) for path in tracked), "the tree has a path outside the path grammar")
    allowed: set[str] = set()
    for path in tracked:
        if is_protected(path):
            continue
        # The shortest ancestor that neither is nor holds a protected path admits this file.
        for prefix in ancestors(path):
            if not any(protected == prefix or protected.startswith(prefix + "/")
                       for protected in BATCH_PROTECTED_PATHS):
                allowed.add(prefix)
                break
    return sorted(allowed)


def validate_allowed_paths(value: Any) -> frozenset[str]:
    """Check a list read from `master` the way `ci batch-prepare --allowed-paths` does, and refuse
    any entry that is, holds or lies inside a protected path."""
    require(isinstance(value, list) and 0 < len(value) <= MAX_BATCH_PATHS,
            f"{BATCH_PATHS} must be a non-empty JSON array of at most {MAX_BATCH_PATHS} paths")
    previous = ""
    for path in value:
        require(is_repo_path(path) and path > previous,
                f"{BATCH_PATHS} must hold canonical repository paths in strictly ascending order")
        require(not is_protected(path) and not any(protected.startswith(path + "/")
                                                   for protected in BATCH_PROTECTED_PATHS),
                f"{BATCH_PATHS} allows the protected path {path}")
        previous = path
    return frozenset(value)


def allowed_paths_at(checkout: Path, commit: str) -> frozenset[str]:
    shown = git(checkout, "show", f"{commit}:{BATCH_PATHS}", check=False)
    require(shown.returncode == 0, f"{BASE} at {commit[:12]} has no {BATCH_PATHS}; it cannot be batched")
    try:
        value = json.loads(shown.stdout)
    except json.JSONDecodeError as exc:
        raise BatchError(f"{BATCH_PATHS} is not JSON: {exc}") from exc
    return validate_allowed_paths(value)


def member_paths(checkout: Path, pull: "Pull", fork_point: str) -> list[str]:
    """Every path of the member's own patch, from its merge base with `master` to its head."""
    raw = git(checkout, "diff", "--raw", "-z", "--no-renames", "--no-abbrev", "--no-ext-diff",
              fork_point, pull.head_sha).stdout
    fields = raw.split("\0")
    paths = []
    for index in range(0, len(fields) - 1, 2):
        header, path = fields[index], fields[index + 1]
        modes = header.lstrip(":").split(" ")[:2]
        require(len(modes) == 2 and is_repo_path(path), f"#{pull.number} changes an unreadable path")
        require("160000" not in modes, f"#{pull.number} changes a submodule entry: {path}")
        require(set(modes) <= PATCH_MODES, f"#{pull.number} changes an entry of an unsupported mode: {path}")
        paths.append(path)
    return paths


def require_ordinary(checkout: Path, pull: "Pull", fork_point: str, allowed: frozenset[str]) -> None:
    refused = [path for path in member_paths(checkout, pull, fork_point)
               if not any(prefix in allowed for prefix in ancestors(path))]
    require(not refused, f"#{pull.number} changes paths a batch may not carry; mark it ready on its own: "
                         + ", ".join(refused[:20]))


@dataclass(frozen=True)
class Pull:
    number: int
    title: str
    head_sha: str
    head_ref: str
    draft: bool


def validate_pull(record: Any, repository: str, number: int) -> Pull:
    require(isinstance(record, dict) and record.get("number") == number, f"#{number} is not a pull request")
    head, base = record.get("head") or {}, record.get("base") or {}
    require(record.get("state") == "open" and record.get("merged_at") is None, f"#{number} is not open")
    require(base.get("ref") == BASE and (base.get("repo") or {}).get("full_name") == repository,
            f"#{number} does not target {repository}:{BASE}")
    # Fork code must never enter a same-repository branch, whose PR runs are trusted.
    require((head.get("repo") or {}).get("full_name") == repository,
            f"#{number} comes from a fork; review and handle it individually")
    require(isinstance(head.get("sha"), str) and SHA.fullmatch(head["sha"]) is not None,
            f"#{number} has no valid head commit")
    ref = head.get("ref")
    require(isinstance(ref, str) and ref and not ref.startswith(PREFIX) and ref != BASE,
            f"#{number} is itself a batch or base branch")
    title = record.get("title")
    require(isinstance(title, str) and 0 < len(title) <= MAX_TITLE and title.isprintable(),
            f"#{number} needs a printable title of at most {MAX_TITLE} characters")
    return Pull(number, title, head["sha"], ref, record.get("draft") is True)


def select_pulls(api: GitHub, numbers: Iterable[int], drafts: bool) -> list[Pull]:
    wanted = list(dict.fromkeys(numbers))
    if drafts:
        listing = api.request("GET", f"pulls?state=open&base={BASE}&sort=created&direction=asc&per_page=100")
        require(isinstance(listing, list) and len(listing) < 100,
                "too many open pull requests to select drafts; name them explicitly")
        wanted += [item["number"] for item in listing if isinstance(item, dict) and item.get("draft") is True
                   and ((item.get("head") or {}).get("repo") or {}).get("full_name") == api.repository
                   and item.get("number") not in wanted]
    require(0 < len(wanted) <= MAX_PULLS, f"a batch needs between 1 and {MAX_PULLS} pull requests")
    require(all(type(number) is int and number > 0 for number in wanted), "invalid pull request number")
    return [validate_pull(api.request("GET", f"pulls/{number}"), api.repository, number) for number in wanted]


def commit_message(pull: Pull, commits: list[str], branch: str) -> str:
    listed = commits[:50] + ([f"- ... {len(commits) - 50} more"] if len(commits) > 50 else [])
    return f"{pull.title} (#{pull.number})\n\nSquashed from {pull.head_sha} for {branch}:\n" + "\n".join(listed) + "\n"


def squash(checkout: Path, pull: Pull, base_sha: str, branch: str, allowed: frozenset[str]) -> str:
    bases = git(checkout, "merge-base", "--all", base_sha, pull.head_sha, check=False).stdout.split()
    require(len(bases) == 1, f"#{pull.number} needs exactly one merge base with {BASE}; rebase it")
    require_ordinary(checkout, pull, bases[0], allowed)
    merge = git(checkout, "merge", "--squash", pull.head_sha, check=False)
    conflicts = sorted({line.split("\t", 1)[1] for line in
                        git(checkout, "ls-files", "--unmerged").stdout.splitlines() if "\t" in line})
    require(not conflicts, f"#{pull.number} conflicts with master or an earlier batch entry: "
                           + ", ".join(conflicts[:20]))
    require(merge.returncode == 0, f"#{pull.number} could not be squashed: {merge.stderr.strip()[:300]}")
    require(git(checkout, "diff-index", "--cached", "--quiet", "HEAD", check=False).returncode == 1,
            f"#{pull.number} adds nothing on top of master and earlier batch entries")
    commits = git(checkout, "log", "--no-decorate", "--format=- %h %s", f"{bases[0]}..{pull.head_sha}").stdout
    author = git(checkout, "log", "-1", "--format=%an <%ae>", pull.head_sha).stdout.strip()
    git(checkout, "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "--no-verify",
        f"--author={author}", "--file=-", stdin=commit_message(pull, commits.splitlines(), branch))
    return git(checkout, "rev-parse", "HEAD").stdout.strip()


def cell(text: str) -> str:
    return text.replace("\\", "\\\\").replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")


def batch_body(pulls: list[Pull], commits: list[str], base_sha: str, branch: str) -> str:
    rows = "\n".join(f"| #{pull.number} | {cell(pull.title)} | `{pull.head_sha[:12]}` | `{commit[:12]}` |"
                     for pull, commit in zip(pulls, commits))
    marker = json.dumps({"schema_version": SCHEMA_VERSION, "base_sha": base_sha, "branch": branch,
                         "pulls": [{"number": pull.number, "head_sha": pull.head_sha, "commit": commit}
                                   for pull, commit in zip(pulls, commits)]}, sort_keys=True, separators=(",", ":"))
    listed = ", ".join(f"#{pull.number}" for pull in pulls)
    return (
        "## Summary\n\n"
        f"Batch of {len(pulls)} pull request(s), validated together by this PR's Build and Packaged E2E "
        f"gates. Each entry is one squashed commit on `{branch}`, so `git bisect` over the batch isolates "
        "a failure.\n\n"
        "| PR | Title | Batched head | Batch commit |\n|---|---|---|---|\n"
        f"{rows}\n\n"
        "After this PR merges, close the included pull requests with "
        "`python3 scripts/ci/pr_batch.py settle <this PR number>`.\n\n"
        "## Scope\n\n"
        f"- Base branch: `{BASE}` at `{base_sha[:12]}`\n"
        f"- Included pull requests: {listed}\n"
        "- Scope, validation notes and AI assistance are recorded in each included pull request.\n\n"
        "## Validation\n\n"
        "The required Build and Packaged E2E gates of this pull request validate the combined tree.\n\n"
        f"<!-- quick-skin-batch {marker} -->\n"
    )


def batch_title(pulls: list[Pull]) -> str:
    listed = f"chore: batch {', '.join(f'#{pull.number}' for pull in pulls)}"
    return listed if len(listed) <= 100 else f"chore: batch {len(pulls)} pull requests"


def prepare(api: GitHub, repository: Path, remote: str, numbers: Iterable[int], *, drafts: bool = False,
            name: str | None = None, dry_run: bool = False, comment: bool = True,
            now: datetime | None = None) -> dict[str, Any]:
    name = name or (now or datetime.now(timezone.utc)).strftime("%Y%m%d-%H%M%S")
    require(NAME.fullmatch(name) is not None, "batch names use lowercase letters, digits, '.', '_' and '-'")
    branch = PREFIX + name
    pulls = select_pulls(api, numbers, drafts)
    base = api.request("GET", f"branches/{BASE}")
    base_sha = (base or {}).get("commit", {}).get("sha") if isinstance(base, dict) else None
    require(isinstance(base_sha, str) and SHA.fullmatch(base_sha) is not None, f"{BASE} has no valid head")
    fetched = git(repository, "fetch", "--no-tags", "--quiet", remote, f"refs/heads/{BASE}",
                  *(f"refs/pull/{pull.number}/head" for pull in pulls), check=False)
    require(fetched.returncode == 0, f"git fetch failed: {fetched.stderr.strip()[:500]}")
    for label, sha in ((BASE, base_sha), *((f"#{pull.number}", pull.head_sha) for pull in pulls)):
        require(git(repository, "cat-file", "-e", f"{sha}^{{commit}}", check=False).returncode == 0,
                f"{label} moved while the batch was being prepared; run it again")

    commits: list[str] = []
    try:
        with tempfile.TemporaryDirectory(prefix="quick-skin-batch-") as temporary:
            checkout = Path(temporary) / "checkout"
            git(repository, "worktree", "add", "--quiet", "--detach", str(checkout), base_sha)
            try:
                # The list comes from the master commit the batch is built on, never from a member.
                allowed = allowed_paths_at(checkout, base_sha)
                for pull in pulls:
                    commits.append(squash(checkout, pull, base_sha, branch, allowed))
            finally:
                # Only this tool's own temporary checkout is reset; the caller's worktree is untouched.
                git(checkout, "reset", "--quiet", "--hard", check=False)
                git(repository, "worktree", "remove", str(checkout), check=False)
    finally:
        git(repository, "worktree", "prune", check=False)

    result: dict[str, Any] = {"branch": branch, "base_sha": base_sha, "head_sha": commits[-1],
                              "pulls": [pull.number for pull in pulls], "commits": commits}
    if dry_run:
        return result
    # An empty lease requires the batch branch to be new; an existing name is never overwritten.
    pushed = git(repository, "push", "--quiet", "--no-verify", f"--force-with-lease=refs/heads/{branch}:",
                 remote, f"{commits[-1]}:refs/heads/{branch}", check=False)
    require(pushed.returncode == 0, f"could not create {branch}: {pushed.stderr.strip()[:500]}")
    created = api.request("POST", "pulls", {"title": batch_title(pulls), "head": branch, "base": BASE,
                                             "body": batch_body(pulls, commits, base_sha, branch), "draft": False})
    require(isinstance(created, dict) and type(created.get("number")) is int, "GitHub did not create the batch PR")
    result.update(number=created["number"], url=created.get("html_url"))
    if comment:
        for pull, commit in zip(pulls, commits):
            api.request("POST", f"issues/{pull.number}/comments", {"body": (
                f"Included in batch #{created['number']} as `{commit[:12]}` (head `{pull.head_sha[:12]}`). "
                "New commits here are not part of that batch.")})
    return result


def batch_marker(body: Any, branch: str) -> list[dict[str, Any]]:
    return read_marker(body, branch)[1]


def read_marker(body: Any, branch: str) -> tuple[str, list[dict[str, Any]]]:
    """The base and the entries of the one well-formed batch marker for `branch`."""
    found = MARKER.findall(body) if isinstance(body, str) else []
    require(len(found) == 1, "the batch PR body has no single batch marker")
    marker = json.loads(found[0])
    require(isinstance(marker, dict) and set(marker) == {"schema_version", "base_sha", "branch", "pulls"}
            and marker["schema_version"] == SCHEMA_VERSION and marker["branch"] == branch
            and isinstance(marker["base_sha"], str) and SHA.fullmatch(marker["base_sha"]) is not None,
            "the batch marker is malformed or names another branch")
    pulls = marker["pulls"]
    require(isinstance(pulls, list) and 0 < len(pulls) <= MAX_PULLS, "the batch marker lists no pull requests")
    for entry in pulls:
        require(isinstance(entry, dict) and set(entry) == {"number", "head_sha", "commit"}
                and type(entry["number"]) is int and entry["number"] > 0
                and all(isinstance(entry[key], str) and SHA.fullmatch(entry[key]) is not None
                        for key in ("head_sha", "commit")), "the batch marker has a malformed entry")
    require(len({entry["number"] for entry in pulls}) == len(pulls), "the batch marker repeats a pull request")
    return marker["base_sha"], pulls


def require_marker_commits(api: GitHub, number: int, record: dict[str, Any], base_sha: str, branch: str,
                           entries: list[dict[str, Any]]) -> None:
    """Bind the marker, which anyone who can edit the body can edit, to the commits that merged.

    The batch pull request's commits must be exactly the marker's, in order, on the marker's base,
    ending at the merged head, and each commit's own message must name its pull request and the
    batched head. A commit id covers its message and parent, so an edited marker that names
    another pull request or head no longer matches."""
    listed = api.request("GET", f"pulls/{number}/commits?per_page=100")
    require(isinstance(listed, list) and len(listed) == len(entries),
            f"batch #{number} has other commits than its batch marker lists")
    require((record.get("head") or {}).get("sha") == entries[-1]["commit"],
            f"batch #{number} did not merge the last commit of its batch marker")
    parent = base_sha
    for entry, commit in zip(entries, listed):
        detail = commit.get("commit") if isinstance(commit, dict) else None
        message = detail.get("message") if isinstance(detail, dict) else None
        parents = [item.get("sha") for item in commit.get("parents") or [] if isinstance(item, dict)] \
            if isinstance(commit, dict) else []
        require(commit.get("sha") == entry["commit"] and parents == [parent] and isinstance(message, str)
                and message.split("\n", 1)[0].endswith(f" (#{entry['number']})")
                and f"\n\nSquashed from {entry['head_sha']} for {branch}:\n" in message,
                f"batch #{number}: commit {str(entry['commit'])[:12]} does not carry #{entry['number']} "
                "as its batch marker says")
        parent = entry["commit"]


def settle(api: GitHub, number: int, *, delete_branches: bool = False) -> dict[str, list[int]]:
    record = api.request("GET", f"pulls/{number}")
    require(isinstance(record, dict) and record.get("number") == number, f"#{number} is not a pull request")
    head, base = record.get("head") or {}, record.get("base") or {}
    branch = head.get("ref")
    require(isinstance(branch, str) and branch.startswith(PREFIX)
            and (head.get("repo") or {}).get("full_name") == api.repository
            and base.get("ref") == BASE and (base.get("repo") or {}).get("full_name") == api.repository,
            f"#{number} is not a batch pull request to {BASE}")
    require(record.get("merged") is True and record.get("merged_at"), f"batch #{number} has not merged")
    base_sha, entries = read_marker(record.get("body"), branch)
    require_marker_commits(api, number, record, base_sha, branch, entries)
    report: dict[str, list[int]] = {"closed": [], "changed": [], "already_closed": [], "deleted": []}
    for entry in entries:
        pull = api.request("GET", f"pulls/{entry['number']}")
        require(isinstance(pull, dict) and pull.get("number") == entry["number"],
                f"#{entry['number']} is not a pull request")
        if pull.get("state") != "open":
            report["already_closed"].append(entry["number"])
            continue
        pull_head = pull.get("head") or {}
        if pull_head.get("sha") != entry["head_sha"]:
            # Commits pushed after batching never reached master; the PR stays open for them.
            report["changed"].append(entry["number"])
            continue
        api.request("POST", f"issues/{entry['number']}/comments", {"body": (
            f"Merged through batch #{number} (batch commit `{entry['commit'][:12]}`).")})
        api.request("PATCH", f"pulls/{entry['number']}", {"state": "closed"})
        report["closed"].append(entry["number"])
        ref = pull_head.get("ref")
        if (delete_branches and (pull_head.get("repo") or {}).get("full_name") == api.repository
                and isinstance(ref, str) and ref and ref != BASE and not ref.startswith(PREFIX)):
            quoted = urllib.parse.quote(ref, safe="/")
            current = api.request("GET", f"git/ref/heads/{quoted}")
            if isinstance(current, dict) and (current.get("object") or {}).get("sha") == entry["head_sha"]:
                api.request("DELETE", f"git/refs/heads/{quoted}")
                report["deleted"].append(entry["number"])
    return report


def render_batch_paths(paths: list[str]) -> str:
    return json.dumps(paths, indent=2) + "\n"


def tracked_files(repository: Path) -> list[str]:
    return [path for path in git(repository, "ls-files", "-z").stdout.split("\0") if path]


def paths_command(*, write: bool) -> int:
    try:
        expected = render_batch_paths(batch_paths_policy(tracked_files(ROOT)))
        validate_allowed_paths(json.loads(expected))
    except (BatchError, OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"pr_batch: {exc}", file=sys.stderr)
        return 1
    target = ROOT / BATCH_PATHS
    if write:
        target.write_text(expected, encoding="utf-8", newline="\n")
        return 0
    current = target.read_text(encoding="utf-8") if target.is_file() else ""
    if current.replace("\r\n", "\n") != expected:
        print(f"pr_batch: {BATCH_PATHS} is stale; run python3 scripts/ci/pr_batch.py paths --write",
              file=sys.stderr)
        return 1
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repository", default=None, help="GitHub owner/name (default: governance repository)")
    commands = parser.add_subparsers(dest="command", required=True)
    make = commands.add_parser("prepare", help="squash pull requests into one new batch PR")
    make.add_argument("pulls", nargs="*", type=int, help="pull request numbers, in merge order")
    make.add_argument("--drafts", action="store_true", help="also include every open same-repository draft")
    make.add_argument("--name", help="batch branch suffix (default: UTC timestamp)")
    make.add_argument("--remote", help="Git remote of the repository (default: the one pointing at it)")
    make.add_argument("--dry-run", action="store_true", help="squash locally only; push and open nothing")
    make.add_argument("--no-comment", action="store_true", help="do not comment on the included PRs")
    close = commands.add_parser("settle", help="close the PRs included in a merged batch PR")
    close.add_argument("batch", type=int, help="the merged batch PR number")
    close.add_argument("--delete-branches", action="store_true",
                       help="also delete each closed PR's branch while it still points at the batched head")
    listing = commands.add_parser("paths", help=f"check {BATCH_PATHS} against the tracked tree")
    listing.add_argument("--write", action="store_true", help="rewrite the list instead of checking it")
    args = parser.parse_args(argv)
    if args.command == "paths":
        return paths_command(write=args.write)
    try:
        api = GitHub(args.repository or default_repository())
        if args.command == "prepare":
            repository = Path(git(Path.cwd(), "rev-parse", "--show-toplevel").stdout.strip())
            result = prepare(api, repository, args.remote or remote_for(repository, api.repository), args.pulls,
                             drafts=args.drafts, name=args.name, dry_run=args.dry_run,
                             comment=not args.no_comment)
        else:
            result = settle(api, args.batch, delete_branches=args.delete_branches)
    except (BatchError, OSError, ValueError, subprocess.SubprocessError) as exc:
        print(f"pr_batch: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
