from __future__ import annotations

import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import pr_batch as batch


REPOSITORY = "owner/repo"
ALLOWED = ["alpha.txt", "beta.txt", "notes", "shared.txt"]


class FakeGitHub:
    """Enough of the pulls, issues and refs API to drive a batch without a network."""

    def __init__(self, master: str):
        self.repository = REPOSITORY
        self.master = master
        self.pulls: dict[int, dict] = {}
        self.refs: dict[str, str] = {}
        self.commits: dict[int, list[dict]] = {}
        self.calls: list[tuple[str, str, dict | None]] = []

    def add(self, number, sha, ref, *, draft=True, title=None, head_repo=REPOSITORY):
        self.pulls[number] = {
            "number": number, "state": "open", "merged": False, "merged_at": None, "draft": draft,
            "title": title or f"fix: change {ref}", "body": "",
            "head": {"sha": sha, "ref": ref, "repo": {"full_name": head_repo}},
            "base": {"ref": "master", "repo": {"full_name": REPOSITORY}}}
        self.refs[ref] = sha

    def writes(self, method=None):
        return [call for call in self.calls if call[0] != "GET" and method in (None, call[0])]

    def request(self, method, endpoint, body=None):
        self.calls.append((method, endpoint, copy.deepcopy(body)))
        number = re.fullmatch(r"pulls/(\d+)", endpoint)
        if method == "GET" and endpoint == "branches/master":
            return {"commit": {"sha": self.master}}
        if method == "GET" and endpoint.startswith("pulls?"):
            return [copy.deepcopy(self.pulls[key]) for key in sorted(self.pulls)
                    if self.pulls[key]["state"] == "open"]
        if method == "GET" and number:
            return copy.deepcopy(self.pulls[int(number.group(1))])
        listed = re.fullmatch(r"pulls/(\d+)/commits\?per_page=100", endpoint)
        if method == "GET" and listed:
            return copy.deepcopy(self.commits[int(listed.group(1))])
        if method == "PATCH" and number:
            self.pulls[int(number.group(1))].update(body)
            return {}
        if method == "POST" and endpoint == "pulls":
            created = max(self.pulls, default=0) + 100
            self.pulls[created] = {
                "number": created, "state": "open", "merged": False, "merged_at": None,
                "draft": body["draft"], "title": body["title"], "body": body["body"],
                "head": {"sha": "0" * 40, "ref": body["head"], "repo": {"full_name": REPOSITORY}},
                "base": {"ref": body["base"], "repo": {"full_name": REPOSITORY}}}
            return {"number": created, "html_url": f"https://github.com/{REPOSITORY}/pull/{created}"}
        if method == "POST" and re.fullmatch(r"issues/\d+/comments", endpoint):
            return {"id": len(self.calls)}
        if method == "GET" and endpoint.startswith("git/ref/heads/"):
            return {"object": {"sha": self.refs[endpoint.removeprefix("git/ref/heads/")]}}
        if method == "DELETE" and endpoint.startswith("git/refs/heads/"):
            del self.refs[endpoint.removeprefix("git/refs/heads/")]
            return None
        raise AssertionError(f"unexpected GitHub request {method} {endpoint}")


class PullRequestBatchTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        empty = self.root / "gitconfig"
        empty.write_text("", encoding="utf-8")
        # The fixture must not inherit a developer's signing, hooks or default-branch settings.
        environment = patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(empty), "GIT_CONFIG_NOSYSTEM": "1"})
        environment.start()
        self.addCleanup(environment.stop)
        self.remote = self.root / "remote.git"
        self.git(self.root, "init", "--quiet", "--bare", "--initial-branch=master", str(self.remote))
        self.seed = self.root / "seed"
        self.git(self.root, "init", "--quiet", "--initial-branch=master", str(self.seed))
        self.configure(self.seed)
        self.master = self.commit({"shared.txt": "one\ntwo\nthree\n",
                                   batch.BATCH_PATHS: json.dumps(ALLOWED)}, "chore: seed")
        self.git(self.seed, "push", "--quiet", str(self.remote), "master")
        self.heads = {
            1: self.branch(1, "fix/alpha", {"alpha.txt": "alpha\n"}),
            2: self.branch(2, "feat/beta", {"beta.txt": "beta\n", "shared.txt": "one\ntwo\nthree\nfour\n"}),
            3: self.branch(3, "fix/gamma", {"shared.txt": "one\ntwo\nthree\nFOUR\n"}),
        }
        self.local = self.root / "local"
        self.git(self.root, "clone", "--quiet", str(self.remote), str(self.local))
        self.configure(self.local)
        self.api = FakeGitHub(self.master)
        for number, ref in ((1, "fix/alpha"), (2, "feat/beta"), (3, "fix/gamma")):
            self.api.add(number, self.heads[number], ref)

    @staticmethod
    def git(directory, *args, stdin=None):
        return subprocess.run(["git", "-C", str(directory), *args], input=stdin or "", check=True,
                              capture_output=True, text=True).stdout.strip()

    def configure(self, directory):
        for key, value in (("user.name", "Batch Tester"), ("user.email", "tester@example.invalid"),
                           ("commit.gpgsign", "false")):
            self.git(directory, "config", key, value)

    def commit(self, files, message):
        for name, text in files.items():
            (self.seed / name).parent.mkdir(parents=True, exist_ok=True)
            (self.seed / name).write_text(text, encoding="utf-8")
        self.git(self.seed, "add", "--all")
        self.git(self.seed, "commit", "--quiet", "--message", message)
        return self.git(self.seed, "rev-parse", "HEAD")

    def branch(self, number, ref, files):
        self.git(self.seed, "switch", "--quiet", "--create", ref, "master")
        head = self.commit(files, f"work for {ref}")
        self.git(self.seed, "push", "--quiet", str(self.remote), f"{ref}:refs/heads/{ref}",
                 f"{ref}:refs/pull/{number}/head")
        self.git(self.seed, "switch", "--quiet", "master")
        return head

    def remote_ref(self, ref):
        return self.git(self.remote, "for-each-ref", "--format=%(objectname)", ref)

    def prepare(self, numbers, **options):
        options.setdefault("name", "tested")
        return batch.prepare(self.api, self.local, "origin", numbers, **options)

    def test_prepare_squashes_each_pull_in_order_and_opens_one_batch(self):
        result = self.prepare([2, 1])
        head = self.remote_ref("refs/heads/batch/tested")
        self.assertEqual(result["head_sha"], head)
        self.assertEqual([2, 1], result["pulls"])
        self.assertEqual(result["commits"][0], self.git(self.remote, "rev-parse", f"{head}^"))
        self.assertEqual(self.master, self.git(self.remote, "rev-parse", f"{result['commits'][0]}^"))
        self.assertEqual(["fix: change fix/alpha (#1)", "fix: change feat/beta (#2)"],
                         self.git(self.remote, "log", "--format=%s", "--max-count=2", head).splitlines())
        message = self.git(self.remote, "log", "-1", "--format=%B", result["commits"][0])
        self.assertIn(f"Squashed from {self.heads[2]} for batch/tested:", message)
        self.assertIn("work for feat/beta", message)
        self.assertEqual("alpha", self.git(self.remote, "show", f"{head}:alpha.txt"))

        created = [body for method, endpoint, body in self.api.writes("POST") if endpoint == "pulls"]
        self.assertEqual(1, len(created))
        self.assertEqual({"head": "batch/tested", "base": "master", "draft": False,
                          "title": "chore: batch #2, #1"},
                         {key: created[0][key] for key in ("head", "base", "draft", "title")})
        marker = batch.batch_marker(created[0]["body"], "batch/tested")
        self.assertEqual([{"number": 2, "head_sha": self.heads[2], "commit": result["commits"][0]},
                          {"number": 1, "head_sha": self.heads[1], "commit": result["commits"][1]}], marker)
        comments = [endpoint for method, endpoint, _ in self.api.writes("POST") if endpoint != "pulls"]
        self.assertEqual(["issues/2/comments", "issues/1/comments"], comments)
        # The caller's checkout keeps its branch and has no leftover temporary worktree.
        self.assertEqual(1, len(self.git(self.local, "worktree", "list", "--porcelain").split("\n\n")))
        self.assertEqual("master", self.git(self.local, "branch", "--show-current"))

    def test_conflicting_entry_names_the_pull_and_files_and_publishes_nothing(self):
        with self.assertRaisesRegex(batch.BatchError, r"#3 conflicts .*shared\.txt"):
            self.prepare([2, 3])
        self.assertEqual("", self.remote_ref("refs/heads/batch/tested"))
        self.assertEqual([], self.api.writes())
        self.assertEqual(1, len(self.git(self.local, "worktree", "list", "--porcelain").split("\n\n")))

    def test_dry_run_squashes_locally_without_pushing_or_writing_to_github(self):
        result = self.prepare([1, 2], dry_run=True)
        self.assertEqual(2, len(result["commits"]))
        self.assertNotIn("number", result)
        self.assertEqual("", self.remote_ref("refs/heads/batch/tested"))
        self.assertEqual([], self.api.writes())

    def test_an_existing_batch_branch_is_never_overwritten(self):
        self.git(self.seed, "push", "--quiet", str(self.remote), "master:refs/heads/batch/tested")
        with self.assertRaisesRegex(batch.BatchError, "could not create batch/tested"):
            self.prepare([1])
        self.assertEqual(self.master, self.remote_ref("refs/heads/batch/tested"))
        self.assertEqual([], self.api.writes())

    def test_forks_closed_or_foreign_pulls_and_moved_heads_are_refused(self):
        mutations = {
            "fork": lambda pull: pull["head"]["repo"].update(full_name="someone/fork"),
            "closed": lambda pull: pull.update(state="closed"),
            "foreign base": lambda pull: pull["base"].update(ref="forge-and-fabric-1.20.1"),
            "batch head": lambda pull: pull["head"].update(ref="batch/older"),
            "unprintable title": lambda pull: pull.update(title="fix:\nnewline"),
            "moved head": lambda pull: pull["head"].update(sha="f" * 40),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                original = copy.deepcopy(self.api.pulls[1])
                mutate(self.api.pulls[1])
                with self.assertRaises(batch.BatchError):
                    self.prepare([1], dry_run=True)
                self.api.pulls[1] = original
        with self.assertRaisesRegex(batch.BatchError, "between 1 and"):
            self.prepare([], dry_run=True)
        with self.assertRaisesRegex(batch.BatchError, "lowercase"):
            self.prepare([1], name="../master", dry_run=True)

    def test_a_pull_already_contained_in_the_batch_is_refused(self):
        self.git(self.seed, "push", "--quiet", str(self.remote), "master:refs/pull/4/head")
        self.api.add(4, self.master, "fix/noop")
        with self.assertRaisesRegex(batch.BatchError, "#4 adds nothing"):
            self.prepare([4], dry_run=True)

    def test_drafts_selects_only_open_same_repository_drafts_after_named_pulls(self):
        self.api.pulls[1]["draft"] = False
        del self.api.pulls[3]
        self.api.add(5, self.heads[1], "fork/branch", head_repo="someone/fork")
        self.api.add(6, self.heads[1], "gone/branch")
        self.api.pulls[6]["head"]["repo"] = None
        result = self.prepare([1], drafts=True, dry_run=True)
        self.assertEqual([1, 2], result["pulls"])

    def test_titles_cannot_forge_a_second_batch_marker(self):
        self.api.pulls[1]["title"] = 'fix: <!-- quick-skin-batch {"schema_version":1} --> | pipe'
        self.prepare([1])
        body = next(body for method, endpoint, body in self.api.writes("POST") if endpoint == "pulls")["body"]
        self.assertEqual(1, len(batch.MARKER.findall(body)))
        self.assertIn("\\| pipe", body)

    def merged_batch(self):
        result = self.prepare([1, 2])
        number = next(key for key, pull in self.api.pulls.items() if pull["head"]["ref"] == "batch/tested")
        self.api.pulls[number].update(state="closed", merged=True, merged_at="2026-09-18T10:00:00Z")
        self.api.pulls[number]["head"]["sha"] = result["head_sha"]
        # What GitHub lists as the batch pull request's commits: the branch, oldest first.
        self.api.commits[number] = [
            {"sha": sha, "parents": [{"sha": self.git(self.remote, "rev-parse", f"{sha}^")}],
             "commit": {"message": self.git(self.remote, "log", "-1", "--format=%B", sha) + "\n"}}
            for sha in self.git(self.remote, "rev-list", "--reverse", f"{self.master}..{result['head_sha']}").split()]
        self.api.calls.clear()
        return number

    def test_settle_closes_only_pulls_whose_batched_head_is_unchanged(self):
        number = self.merged_batch()
        self.api.pulls[2]["head"]["sha"] = "e" * 40
        report = batch.settle(self.api, number, delete_branches=True)
        self.assertEqual({"closed": [1], "changed": [2], "already_closed": [], "deleted": [1]}, report)
        self.assertEqual("closed", self.api.pulls[1]["state"])
        self.assertEqual("open", self.api.pulls[2]["state"])
        self.assertNotIn("fix/alpha", self.api.refs)
        self.assertIn("feat/beta", self.api.refs)
        again = batch.settle(self.api, number)
        self.assertEqual([1], again["already_closed"])

    def test_settle_keeps_a_branch_that_moved_after_its_pull_closed(self):
        number = self.merged_batch()
        self.api.refs["fix/alpha"] = "d" * 40
        report = batch.settle(self.api, number, delete_branches=True)
        self.assertEqual([1, 2], report["closed"])
        self.assertEqual([2], report["deleted"])
        self.assertEqual("d" * 40, self.api.refs["fix/alpha"])

    def test_settle_refuses_unmerged_foreign_or_tampered_batches(self):
        number = self.merged_batch()
        pull = self.api.pulls[number]
        body = pull["body"]
        marker = json.loads(batch.MARKER.search(body).group(1))
        cases = {
            "unmerged": {"merged": False},
            "foreign head": {"head": {**pull["head"], "ref": "fix/alpha"}},
            "no marker": {"body": "## Summary\n"},
            "two markers": {"body": body + body},
            "other branch": {"body": body.replace('"branch":"batch/tested"', '"branch":"batch/other"')},
            "extra key": {"body": body.replace('"schema_version":1', '"schema_version":1,"extra":true')},
            "repeated pull": {"body": batch.MARKER.sub(
                "<!-- quick-skin-batch " + json.dumps(
                    {**marker, "pulls": marker["pulls"][:1] * 2}, separators=(",", ":")) + " -->", body)},
        }
        for label, change in cases.items():
            with self.subTest(label=label):
                original = copy.deepcopy(pull)
                pull.update(change)
                with self.assertRaises(batch.BatchError):
                    batch.settle(self.api, number)
                self.api.pulls[number] = pull = original
        self.assertEqual([], self.api.writes())

    def test_settle_believes_the_marker_only_where_the_merged_commits_agree(self):
        number = self.merged_batch()
        pull = self.api.pulls[number]
        marker = json.loads(batch.MARKER.search(pull["body"]).group(1))
        first, second = marker["pulls"]

        def body(pulls, base=None):
            text = json.dumps({**marker, "base_sha": base or marker["base_sha"], "pulls": pulls},
                              separators=(",", ":"))
            return batch.MARKER.sub(lambda _: f"<!-- quick-skin-batch {text} -->", pull["body"])

        commits = self.api.commits[number]
        cases = {
            # An edited marker would close another pull request, or one at a head that never merged.
            "other pull": ({"body": body([{**first, "number": 3, "head_sha": self.heads[3]}, second])}, None),
            "other head": ({"body": body([{**first, "head_sha": self.heads[3]}, second])}, None),
            "swapped order": ({"body": body([{**second, "commit": first["commit"]},
                                             {**first, "commit": second["commit"]}])}, None),
            "shorter marker": ({"body": body([first])}, None),
            "other base": ({"body": body([first, second], base="a" * 40)}, None),
            "extra commit": ({}, commits + [{"sha": "b" * 40, "parents": [{"sha": second["commit"]}],
                                             "commit": {"message": "x"}}]),
            "head not merged": ({"head": {**pull["head"], "sha": first["commit"]}}, None),
        }
        for label, (change, listed) in cases.items():
            with self.subTest(label=label):
                original = copy.deepcopy(pull)
                pull.update(change)
                if listed is not None:
                    self.api.commits[number] = listed
                with self.assertRaises(batch.BatchError):
                    batch.settle(self.api, number)
                self.api.pulls[number] = pull = original
                self.api.commits[number] = commits
        self.assertEqual([], self.api.writes())
        self.assertEqual([1, 2], batch.settle(self.api, number)["closed"])

    def test_prepare_refuses_a_member_that_changes_a_path_outside_the_list_on_master(self):
        cases = {
            "managed caller": (".github/workflows/mod-base-build.yml", "x\n"),
            "kit config": ("site/mod-base.json", "{}\n"),
            "the list itself": (batch.BATCH_PATHS, json.dumps(ALLOWED + ["zeta.txt"])),
            "unlisted new path": ("zeta.txt", "zeta\n"),
        }
        for number, (label, (path, text)) in enumerate(cases.items(), start=10):
            with self.subTest(label=label):
                head = self.branch(number, f"fix/case-{number}", {"alpha.txt": "alpha\n", path: text})
                self.api.add(number, head, f"fix/case-{number}")
                with self.assertRaisesRegex(batch.BatchError, rf"#{number} changes paths a batch may not carry"):
                    self.prepare([1, number])
                self.assertEqual("", self.remote_ref("refs/heads/batch/tested"))
                self.assertEqual([], self.api.writes())
        # Below an allowed directory everything is allowed, also a path added later.
        head = self.branch(20, "notes/new", {"notes/guide/new.md": "new\n"})
        self.api.add(20, head, "notes/new")
        self.assertEqual([20], self.prepare([20], dry_run=True)["pulls"])

    def test_prepare_needs_a_valid_list_on_master_and_never_takes_one_from_a_member(self):
        self.git(self.seed, "rm", "--quiet", batch.BATCH_PATHS)
        self.git(self.seed, "commit", "--quiet", "--message", "chore: drop the list")
        self.git(self.seed, "push", "--quiet", str(self.remote), "master")
        self.api.master = self.git(self.seed, "rev-parse", "HEAD")
        with self.assertRaisesRegex(batch.BatchError, "has no scripts/ci/mod-base-batch-paths.json"):
            self.prepare([1], dry_run=True)
        for value in ([], ["b", "a"], ["a", "a"], ["/a"], ["a/"], ["a/../b"], [".github"], [".github/x"],
                      ["scripts"], ["site/x.json"], ["docs/.Git/x"], "alpha.txt", [1]):
            with self.subTest(value=value):
                with self.assertRaises(batch.BatchError):
                    batch.validate_allowed_paths(value)
        self.assertEqual(frozenset({"common", "e2e/run-e2e.sh"}),
                         batch.validate_allowed_paths(["common", "e2e/run-e2e.sh"]))

    def test_the_policy_admits_whole_directories_without_a_protected_path(self):
        files = [".github/workflows/a.yml", "README.md", "common/src/A.java", "common/src/b/B.java",
                 "e2e/scenario_contract.py", "e2e/orchestrator.py", "scripts/ci/pr_batch.py",
                 "scripts/ci/tests/test_x.py", "site/mod-base.json", "gradle/wrapper/gradle-wrapper.jar"]
        self.assertEqual(["README.md", "common", "e2e/orchestrator.py", "scripts/ci/tests"],
                         batch.batch_paths_policy(files))

    def test_the_committed_list_is_the_policy_of_the_tracked_tree(self):
        files = batch.tracked_files(batch.ROOT)
        self.assertIn(batch.BATCH_PATHS, files)
        committed = json.loads((batch.ROOT / batch.BATCH_PATHS).read_text(encoding="utf-8"))
        self.assertEqual(batch.batch_paths_policy(files), committed,
                         "run python3 scripts/ci/pr_batch.py paths --write")
        allowed = batch.validate_allowed_paths(committed)
        for path in ("common", "fabric", "forge", "neoforge", "modules", "docs/ci"):
            self.assertIn(path, allowed)
        for path in files:
            if any(prefix in allowed for prefix in batch.ancestors(path)):
                self.assertFalse(batch.is_protected(path), path)
        for path in (".github/workflows/mod-base-build.yml", "site/mod-base.json", "release/release-matrix.json",
                     "scripts/ci/mod-base-build.json", "gradle/verification-metadata.xml", "gradle.properties"):
            self.assertTrue(batch.is_protected(path), path)

    def test_remote_is_resolved_only_from_an_exact_repository_url(self):
        for remote, url in (("origin", "https://github.com/Owner/Repo.git"),
                            ("mine", "git@github.com:someone/repo.git"),
                            ("evil", "https://github.com.example/owner/repo.git")):
            self.git(self.local, "remote", "add" if remote != "origin" else "set-url", remote, url)
        self.assertEqual("origin", batch.remote_for(self.local, "owner/repo"))
        self.git(self.local, "remote", "add", "second", "ssh://git@github.com/owner/repo")
        with self.assertRaisesRegex(batch.BatchError, "one Git remote"):
            batch.remote_for(self.local, "owner/repo")


if __name__ == "__main__":
    unittest.main()
