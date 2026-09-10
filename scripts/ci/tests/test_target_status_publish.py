from __future__ import annotations

import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import target_status_publish as publisher
from target_status_render import render_files, write_site

REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
NOW = dt.datetime(2026, 9, 10, 10, 30, tzinfo=dt.timezone.utc)


def git(directory: Path, *arguments: str, data: bytes | None = None) -> bytes:
    return subprocess.check_output(["git", "-C", str(directory), *arguments], input=data,
                                   stderr=subprocess.DEVNULL, env=publisher.git_environment())


def workflow_job(name: str) -> str:
    text = (ROOT / ".github/workflows/on-demand-e2e.yml").read_text()
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", text)
    assert match is not None
    return match.group()


class TargetStatusPublishTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.remote = self.root / "remote.git"
        self.remote.mkdir()
        git(self.remote, "init", "--bare", "--template=")
        tree = git(self.remote, "mktree", data=b"").strip().decode()
        self.master = git(self.remote, "commit-tree", tree, "-m", "protected source").strip().decode()
        git(self.remote, "update-ref", publisher.MASTER, self.master)
        self.counter = 0

    def snapshot(self, *, reason="Target prerequisites passed", age=20):
        run = {"id": 12, "attempt": 1, "sha": self.master, "status": "completed",
               "conclusion": "success",
               "url": f"https://github.com/{REPOSITORY}/actions/runs/12/attempts/1"}
        job = {"id": 123, "name": "compile / Compile Minecraft 1.20.1", "status": "completed",
               "conclusion": "success",
               "url": f"https://github.com/{REPOSITORY}/actions/runs/12/job/123"}
        gate = {"state": "success", "reason": reason, "generation": run, "execution": copy.deepcopy(run),
                "tested_sha": self.master, "reused": False,
                "expected_jobs": [job["name"]], "jobs": [job]}
        return {"schema_version": 1, "repository": REPOSITORY, "coverage_sha": self.master,
                "observed_at": (NOW - dt.timedelta(seconds=age)).isoformat().replace("+00:00", "Z"),
                "targets": [{"version": "1.20.1", "loaders": ["fabric", "forge"], "java": [17],
                             "build": gate, "e2e": copy.deepcopy(gate)}]}

    def site(self, snapshot=None):
        self.counter += 1
        directory = self.root / f"site-{self.counter}"
        write_site(snapshot or self.snapshot(), directory)
        return directory

    def client(self, cls=publisher.Git):
        self.counter += 1
        return cls(self.root / f"writer-{self.counter}.git", str(self.remote))

    def publish(self, *, snapshot=None, client=None, directory=None):
        return publisher.publish(directory or self.site(snapshot), repository=REPOSITORY,
                                 expected_sha=self.master, git=client or self.client(), now=NOW)

    def status_head(self):
        return git(self.remote, "rev-parse", publisher.BRANCH).strip().decode()

    def winner(self, parent=None):
        tree = git(self.remote, "mktree", data=b"").strip().decode()
        arguments = ("-p", parent) if parent else ()
        result = git(self.remote, "commit-tree", tree, *arguments, "-m", "concurrent writer").strip().decode()
        git(self.remote, "update-ref", publisher.BRANCH, result)
        return result

    def test_first_publication_is_parentless_and_contains_only_exact_generated_files(self):
        source = self.snapshot()
        result = self.publish(snapshot=source)
        self.assertEqual("published", result["state"])
        head = self.status_head()
        self.assertEqual(head, result["status_commit"])
        self.assertEqual([head], git(self.remote, "rev-list", "--parents", "-1", head).decode().split())
        names = git(self.remote, "ls-tree", "-rz", "--name-only", head).decode().strip("\0").split("\0")
        self.assertEqual(set(render_files(source)), set(names))
        for name, expected in render_files(source).items():
            self.assertEqual(expected, git(self.remote, "show", f"{head}:{name}"))
        self.assertEqual(self.master, git(self.remote, "rev-parse", publisher.MASTER).strip().decode())
        self.assertEqual(hashlib.sha256(git(self.remote, "show", f"{head}:status.json")).hexdigest(),
                         result["snapshot_sha256"])

    def test_changed_status_is_fast_forward_but_timestamp_only_is_a_true_noop(self):
        first = self.publish(snapshot=self.snapshot(age=40))
        later = self.publish(snapshot=self.snapshot(age=20))
        self.assertEqual("unchanged", later["state"])
        self.assertEqual(first["status_commit"], later["status_commit"])
        self.assertEqual(first["snapshot_sha256"], later["snapshot_sha256"])
        current = json.loads(git(self.remote, "show", f"{self.status_head()}:status.json"))
        self.assertEqual(self.snapshot(age=40)["observed_at"], current["observed_at"])
        newer = self.publish(snapshot=self.snapshot(reason="New target observation", age=10))
        self.assertEqual("published", newer["state"])
        self.assertEqual([newer["status_commit"], first["status_commit"]],
                         git(self.remote, "rev-list", "--parents", "-1", self.status_head()).decode().split())

    def test_valid_snapshot_does_not_hide_corrupted_published_svg(self):
        client = self.client()
        old = self.snapshot(age=40)
        files = render_files(old)
        files["badges/1.20.1/build.svg"] = b"corrupted"
        parent = client.commit(files, None, self.master)
        client.push(parent, None)
        result = self.publish(snapshot=self.snapshot(age=20))
        self.assertEqual("published", result["state"])
        self.assertEqual(render_files(self.snapshot(age=20))["badges/1.20.1/build.svg"],
                         git(self.remote, "show", f"{self.status_head()}:badges/1.20.1/build.svg"))

    def test_old_same_generation_observation_cannot_replace_a_newer_one(self):
        first = self.publish(snapshot=self.snapshot(age=10))
        with self.assertRaisesRegex(publisher.PublishError, "newer status observation"):
            self.publish(snapshot=self.snapshot(reason="Old observation", age=20))
        self.assertEqual(first["status_commit"], self.status_head())

    def test_master_advance_before_final_guard_stops_publication(self):
        client = self.client()
        original = client.heads
        count = 0

        def heads():
            nonlocal count
            count += 1
            result = original()
            if count == 2:
                result[publisher.MASTER] = "f" * 40
            return result

        client.heads = heads
        with mock.patch.object(client, "push", wraps=client.push) as push:
            with self.assertRaisesRegex(publisher.PublishError, "master advanced"):
                self.publish(client=client)
            push.assert_not_called()

    def test_status_cas_loser_never_overwrites_winner_or_retries(self):
        for existing in (False, True):
            with self.subTest(existing=existing):
                if existing:
                    parent = self.status_head()
                else:
                    parent = None
                client = self.client()
                original_push = client.push
                winners = []

                def push(commit, old):
                    self.assertEqual(parent, old)
                    winners.append(self.winner(parent))
                    original_push(commit, old)

                with mock.patch.object(client, "push", side_effect=push) as action:
                    with self.assertRaisesRegex(publisher.PublishError, "not retried"):
                        self.publish(client=client)
                self.assertEqual(1, action.call_count)
                self.assertEqual(winners[0], self.status_head())

    def test_status_change_at_final_guard_stops_before_push(self):
        first = self.publish()
        client = self.client()
        original = client.heads
        count = 0

        def heads():
            nonlocal count
            count += 1
            if count == 2:
                self.winner(first["status_commit"])
            return original()

        client.heads = heads
        with mock.patch.object(client, "push", wraps=client.push) as push:
            with self.assertRaisesRegex(publisher.PublishError, "status branch changed"):
                self.publish(client=client)
            push.assert_not_called()

    def test_transport_failure_is_not_treated_as_absent_branch(self):
        client = self.client()
        client.remote = str(self.root / "absent.git")
        with self.assertRaisesRegex(publisher.PublishError, "Git operation failed"):
            self.publish(client=client)
        with self.assertRaises(subprocess.CalledProcessError):
            self.status_head()

    def test_inventory_rejects_extras_missing_files_tamper_symlinks_and_oversize(self):
        def mutate(directory, mode):
            if mode == "extra":
                (directory / "evil.py").write_text("raise SystemExit('untrusted')")
            elif mode == "missing":
                (directory / "README.md").unlink()
            elif mode == "tamper":
                (directory / "README.md").write_text("different")
            elif mode == "symlink":
                (directory / "README.md").unlink()
                (directory / "README.md").symlink_to(directory / "status.json")
            elif mode == "directory-link":
                (directory / "unexpected").symlink_to(self.root, target_is_directory=True)
            elif mode == "oversize":
                with (directory / "README.md").open("wb") as stream:
                    stream.truncate(publisher.MAX_FILE_BYTES + 1)

        for mode in ("extra", "missing", "tamper", "symlink", "directory-link", "oversize"):
            with self.subTest(mode=mode):
                directory = self.site()
                mutate(directory, mode)
                client = mock.Mock()
                with self.assertRaises((ValueError, OSError)):
                    self.publish(directory=directory, client=client)
                client.heads.assert_not_called()

    def test_fifo_is_rejected_without_waiting_for_a_writer(self):
        fifo = self.root / "status.json"
        os.mkfifo(fifo)
        script = "import sys; from pathlib import Path; import target_status_publish as p; p.read_regular(Path(sys.argv[1]))"
        environment = dict(os.environ, PYTHONPATH=str(ROOT / "scripts/ci"))
        result = subprocess.run([sys.executable, "-c", script, str(fifo)], env=environment,
                                capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not a bounded regular file", result.stderr)

    def test_identity_and_time_guards_run_before_remote_access(self):
        for field, value in (("repository", "another/repository"), ("coverage_sha", "a" * 40),
                             ("observed_at", "2020-01-01T00:00:00Z"),
                             ("observed_at", "2099-01-01T00:00:00Z")):
            with self.subTest(field=field, value=value):
                data = self.snapshot()
                data[field] = value
                directory = self.site()
                (directory / "status.json").write_text(json.dumps(data))
                client = mock.Mock()
                with self.assertRaises(ValueError):
                    self.publish(directory=directory, client=client)
                client.heads.assert_not_called()

    def test_git_does_not_inherit_repository_hooks_or_credential_configuration(self):
        with mock.patch.dict(os.environ, {"GIT_DIR": "/wrong/repo", "GIT_INDEX_FILE": "/wrong/index",
                                         "GIT_CONFIG_COUNT": "17", "GIT_CONFIG_KEY_9": "evil",
                                         "GIT_CONFIG_VALUE_9": "secret", "GH_TOKEN": "sensitive"}):
            environment = publisher.git_environment("private-token")
        self.assertNotIn("GIT_DIR", environment)
        self.assertNotIn("GIT_INDEX_FILE", environment)
        self.assertNotIn("GIT_CONFIG_KEY_9", environment)
        self.assertNotIn("GH_TOKEN", environment)
        self.assertEqual("1", environment["GIT_CONFIG_COUNT"])
        self.assertEqual("http.https://github.com/.extraheader", environment["GIT_CONFIG_KEY_0"])


class TargetStatusWorkflowTest(unittest.TestCase):
    def test_status_workflow_is_protected_small_and_has_no_gate_or_pages_dispatch(self):
        text = (ROOT / ".github/workflows/target-ci-status.yml").read_text()
        self.assertIn("types: [requested, in_progress, completed]", text)
        self.assertIn("ref: refs/heads/master", text)
        self.assertIn("persist-credentials: false", text)
        self.assertIn("cancel-in-progress: false", text)
        self.assertIn("actions: read", text)
        self.assertIn("contents: write", text)
        self.assertIn("pull-requests: read", text)
        self.assertIn('[[ "$current_sha" == "$source_sha" ]]', text)
        for unwanted in ("gradlew", "unittest", "pytest", "gh workflow run", "--method POST",
                         "download-artifact", "upload-artifact", "needs:", "client_payload.source_sha"):
            self.assertNotIn(unwanted, text)

    def test_explicit_e2e_wakes_are_advisory_and_not_dependencies_of_required_jobs(self):
        text = (ROOT / ".github/workflows/on-demand-e2e.yml").read_text()
        for name, needs in (("notify-target-status-start", "runtime-policy"),
                            ("notify-target-status-complete", "required-gate")):
            block = workflow_job(name)
            self.assertIn(f"needs: {needs}", block)
            self.assertIn("always()", block)
            self.assertIn("continue-on-error: true", block)
            self.assertIn("inputs.attest_run_id == ''", block)
            self.assertIn("github.ref == 'refs/heads/master'", block)
            self.assertNotIn("checkout", block)
            self.assertNotIn("needs." + needs + ".result == 'success'", block)
            self.assertEqual(1, text.count(name))

    def test_e2e_wake_script_drops_stale_heads_and_preserves_dispatch_failure(self):
        for name in ("notify-target-status-start", "notify-target-status-complete"):
            block = workflow_job(name)
            script = block.split("        run: |\n", 1)[1]
            script = "\n".join(line[10:] if line.startswith("          ") else line
                               for line in script.splitlines())
            with tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                fake = directory / "gh"
                fake.write_text('#!/bin/sh\ncase "$*" in *"--method POST"*) '
                                'printf "dispatch\\n" >> "$TEST_CALLS"; exit "$TEST_POST_EXIT";; '
                                '*) printf "%s\\n" "$TEST_MASTER";; esac\n')
                fake.chmod(0o755)
                for label, master, post_exit, expected_calls, status in (
                        ("current", "a" * 40, "0", 1, 0),
                        ("stale", "b" * 40, "0", 0, 0),
                        ("transport", "a" * 40, "7", 1, 7)):
                    with self.subTest(job=name, case=label):
                        calls = directory / f"calls-{label}"
                        environment = dict(os.environ, PATH=str(directory) + os.pathsep + os.environ["PATH"],
                                           GITHUB_EVENT_NAME="workflow_dispatch", GITHUB_REF="refs/heads/master",
                                           GITHUB_SHA="a" * 40, GITHUB_RUN_ID="123", GITHUB_REPOSITORY=REPOSITORY,
                                           RUNNER_TEMP=str(directory), TEST_MASTER=master,
                                           TEST_POST_EXIT=post_exit, TEST_CALLS=str(calls))
                        for key in tuple(environment):
                            if key in {"BASH_ENV", "ENV"} or key.startswith("BASH_FUNC_"):
                                environment.pop(key)
                        result = subprocess.run(["bash", "-c", script], env=environment,
                                                capture_output=True, text=True, timeout=10)
                        self.assertEqual(status, result.returncode, result.stderr)
                        self.assertEqual(expected_calls, len(calls.read_text().splitlines()) if calls.exists() else 0)
                        if expected_calls:
                            payload = json.loads(next(directory.glob("target-status-*.json")).read_text())
                            self.assertEqual("target-ci-status", payload["event_type"])
                            self.assertEqual("a" * 40, payload["client_payload"]["source_sha"])


if __name__ == "__main__":
    unittest.main()
