#!/usr/bin/env python3
"""Publish bounded generated status data without checking out the publication branch."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import threading
from typing import Any

from target_status_render import render_files

BRANCH = "refs/heads/automation/ci-status"
MASTER = "refs/heads/master"
SHA = re.compile(r"[0-9a-f]{40}")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
MAX_FILES = 302
MAX_AGE_SECONDS = 300


class PublishError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublishError(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        require(key not in result, "snapshot has duplicate JSON keys")
        result[key] = value
    return result


def parse_snapshot(raw: bytes) -> dict[str, Any]:
    require(0 < len(raw) <= MAX_FILE_BYTES, "snapshot exceeds its byte bound")
    try:
        value = json.loads(raw, object_pairs_hook=unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(
                               PublishError("snapshot contains a non-finite value")))
        require(isinstance(value, dict), "snapshot is not an object")
        return value
    except (UnicodeError, ValueError, RecursionError) as exc:
        raise PublishError("snapshot is not bounded canonical JSON") from exc


def observed_at(snapshot: dict[str, Any]) -> dt.datetime:
    value = snapshot.get("observed_at")
    require(isinstance(value, str) and value.endswith("Z"), "snapshot observation is not UTC")
    try:
        parsed = dt.datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as exc:
        raise PublishError("snapshot observation is malformed") from exc
    require(parsed.utcoffset() == dt.timedelta(), "snapshot observation is not UTC")
    return parsed


def logical_snapshot(snapshot: dict[str, Any]) -> bytes:
    return json.dumps({key: value for key, value in snapshot.items() if key != "observed_at"},
                      sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def read_regular(path: Path) -> bytes:
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        require(stat.S_ISREG(info.st_mode) and 0 < info.st_size <= MAX_FILE_BYTES,
                "status input is not a bounded regular file")
        raw = source.read(MAX_FILE_BYTES + 1)
    require(len(raw) == info.st_size, "status input changed while reading")
    return raw


def validated_site(directory: Path, *, repository: str, expected_sha: str,
                   now: dt.datetime) -> tuple[dict[str, Any], dict[str, bytes]]:
    require(REPOSITORY.fullmatch(repository) is not None, "invalid repository")
    require(SHA.fullmatch(expected_sha) is not None, "invalid coverage SHA")
    require(not directory.is_symlink() and directory.is_dir(), "status root is not a directory")
    snapshot = parse_snapshot(read_regular(directory / "status.json"))
    require(snapshot.get("repository") == repository and snapshot.get("coverage_sha") == expected_sha,
            "status belongs to another repository or coverage SHA")
    age = (now - observed_at(snapshot)).total_seconds()
    require(0 <= age <= MAX_AGE_SECONDS, "status observation is stale or in the future")
    # Rendering is the protected schema/path/markup validator. Publish exactly these bytes;
    # never accept extra local files or trust a previously generated SVG as executable input.
    expected = render_files(snapshot)
    require(0 < len(expected) <= MAX_FILES, "status file count exceeds its bound")
    found: dict[str, bytes] = {}
    directories = {"."}
    for name in expected:
        directories.update(str(parent) for parent in Path(name).parents)
    for base, children, files in os.walk(directory, followlinks=False):
        relative = Path(base).relative_to(directory)
        require(str(relative) in directories, "status contains an unexpected directory")
        for child in children:
            path = Path(base) / child
            require(not path.is_symlink(), "status contains a linked directory")
        for child in files:
            path = Path(base) / child
            name = path.relative_to(directory).as_posix()
            require(name in expected and len(found) < MAX_FILES,
                    "status contains an unexpected file")
            raw = read_regular(path)
            require(raw == expected[name], "status differs from the protected renderer")
            found[name] = raw
    require(found.keys() == expected.keys(), "status file inventory is incomplete")
    require(sum(map(len, found.values())) <= MAX_TOTAL_BYTES, "status tree exceeds its byte bound")
    return snapshot, found


def git_environment(token: str | None = None) -> dict[str, str]:
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith("GIT_") and key not in {"GH_TOKEN", "GITHUB_TOKEN", "BASH_ENV", "ENV"}}
    environment.update({
        "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull,
        "GIT_CONFIG_NOSYSTEM": "1", "GIT_TERMINAL_PROMPT": "0", "GIT_PAGER": "cat",
        "GIT_AUTHOR_NAME": "github-actions[bot]", "GIT_COMMITTER_NAME": "github-actions[bot]",
        "GIT_AUTHOR_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com",
        "GIT_COMMITTER_EMAIL": "41898282+github-actions[bot]@users.noreply.github.com",
        "LC_ALL": "C",
    })
    if token:
        authorization = base64.b64encode(("x-access-token:" + token).encode()).decode()
        environment.update({"GIT_CONFIG_COUNT": "1",
                            "GIT_CONFIG_KEY_0": "http.https://github.com/.extraheader",
                            "GIT_CONFIG_VALUE_0": "AUTHORIZATION: basic " + authorization})
    return environment


class Git:
    def __init__(self, directory: Path, remote: str, *, token: str | None = None):
        self.directory = directory
        self.remote = remote
        self.environment = git_environment(token)
        self.run("init", "--bare", "--template=", str(directory))

    def run(self, *arguments: str, data: bytes | None = None,
            maximum: int = MAX_FILE_BYTES) -> bytes:
        command = ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "commit.gpgSign=false"]
        if self.directory.exists():
            command.extend(["-C", str(self.directory)])
        process = subprocess.Popen([*command, *arguments], stdin=subprocess.PIPE if data is not None
                                   else subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.DEVNULL, env=self.environment)
        timer = threading.Timer(60, process.kill)
        timer.daemon = True
        timer.start()
        try:
            if data is not None:
                assert process.stdin is not None
                process.stdin.write(data)
                process.stdin.close()
            assert process.stdout is not None
            result = process.stdout.read(maximum + 1)
            require(len(result) <= maximum, "Git response exceeds its byte bound")
            require(process.wait(timeout=5) == 0, "Git operation failed; publication was not retried")
            return result
        except (BrokenPipeError, subprocess.TimeoutExpired) as exc:
            raise PublishError("Git operation failed or timed out") from exc
        finally:
            timer.cancel()
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            if process.stdout:
                process.stdout.close()

    def heads(self) -> dict[str, str]:
        raw = self.run("ls-remote", "--refs", self.remote, MASTER, BRANCH, maximum=1024)
        heads: dict[str, str] = {}
        for line in raw.decode("ascii").splitlines():
            fields = line.split("\t")
            require(len(fields) == 2 and SHA.fullmatch(fields[0]) is not None,
                    "remote ref inventory is malformed")
            sha, name = fields
            require(name in {MASTER, BRANCH} and name not in heads,
                    "remote ref inventory is ambiguous")
            heads[name] = sha
        require(MASTER in heads, "protected master is absent from the remote inventory")
        return heads

    def previous(self, parent: str) -> tuple[dict[str, Any] | None, dict[str, str]]:
        self.run("fetch", "--no-tags", "--depth=1", self.remote, parent, maximum=1024)
        require(self.run("rev-parse", "FETCH_HEAD").strip().decode() == parent,
                "fetched status owner changed")
        entries = self.run("ls-tree", "-rz", "--full-tree", parent, maximum=65536)
        blobs: dict[str, str] = {}
        for entry in entries.split(b"\0"):
            if not entry:
                continue
            metadata, name = entry.split(b"\t", 1)
            mode, kind, oid = metadata.decode("ascii").split()
            require(mode == "100644" and kind == "blob" and SHA.fullmatch(oid) is not None,
                    "previous status contains a non-regular entry")
            path = name.decode("utf-8")
            require(path not in blobs and len(blobs) < MAX_FILES,
                    "previous status file inventory exceeds its bound")
            blobs[path] = oid
        if "status.json" not in blobs:
            return None, blobs
        size = int(self.run("cat-file", "-s", blobs["status.json"], maximum=32).strip())
        require(0 < size <= MAX_FILE_BYTES, "previous snapshot exceeds its byte bound")
        raw = self.run("cat-file", "blob", blobs["status.json"])
        try:
            return parse_snapshot(raw), blobs
        except PublishError:
            return None, blobs

    def commit(self, files: dict[str, bytes], parent: str | None, coverage_sha: str) -> str:
        self.run("read-tree", "--empty")
        entries = []
        for path, raw in sorted(files.items()):
            oid = self.run("hash-object", "-w", "--stdin", data=raw).strip().decode()
            entries.append(f"100644 {oid}\t{path}\0".encode())
        self.run("update-index", "-z", "--index-info", data=b"".join(entries))
        tree = self.run("write-tree").strip().decode()
        parents = ("-p", parent) if parent else ()
        commit = self.run("commit-tree", tree, *parents,
                          data=f"ci: update target status for {coverage_sha[:12]}\n".encode()).strip().decode()
        require(SHA.fullmatch(commit) is not None, "Git returned an invalid status commit")
        if parent:
            self.run("merge-base", "--is-ancestor", parent, commit)
        return commit

    def push(self, commit: str, parent: str | None) -> None:
        # The candidate is a child of exactly parent (or an initial parentless commit).
        # The explicit lease also protects branch creation and rejects a concurrent writer.
        self.run("push", "--porcelain", f"--force-with-lease={BRANCH}:{parent or ''}",
                 self.remote, f"{commit}:{BRANCH}", maximum=2048)


def blob_hash(raw: bytes) -> str:
    return hashlib.sha1(f"blob {len(raw)}\0".encode() + raw).hexdigest()


def publish(directory: Path, *, repository: str, expected_sha: str, git: Git,
            now: dt.datetime | None = None) -> dict[str, Any]:
    snapshot, files = validated_site(directory, repository=repository, expected_sha=expected_sha,
                                     now=now or dt.datetime.now(dt.timezone.utc))
    initial = git.heads()
    require(initial[MASTER] == expected_sha, "master advanced before status publication")
    parent = initial.get(BRANCH)
    unchanged = False
    effective_files = files
    if parent:
        previous, blobs = git.previous(parent)
        if previous is not None:
            try:
                rendered = render_files(previous)
                require(previous.get("repository") == repository, "previous status has another repository")
                if previous.get("coverage_sha") == expected_sha:
                    require(observed_at(previous) <= observed_at(snapshot),
                            "a newer status observation is already published")
                unchanged = (logical_snapshot(previous) == logical_snapshot(snapshot)
                             and blobs == {name: blob_hash(raw) for name, raw in rendered.items()})
                if unchanged:
                    effective_files = rendered
            except PublishError:
                raise
            except (ValueError, TypeError, KeyError):
                # Invalid old data never authorizes a no-op. A fresh validated tree repairs it.
                unchanged = False
    commit = parent if unchanged else git.commit(files, parent, expected_sha)
    final = git.heads()
    require(final[MASTER] == expected_sha, "master advanced before status publication")
    require(final.get(BRANCH) == parent, "status branch changed; refusing to overwrite another writer")
    if not unchanged:
        assert commit is not None
        git.push(commit, parent)
    return {"state": "unchanged" if unchanged else "published", "coverage_sha": expected_sha,
            "status_commit": commit, "previous_commit": parent,
            "file_count": len(files),
            "snapshot_sha256": hashlib.sha256(effective_files["status.json"]).hexdigest()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--expected-sha", required=True)
    args = parser.parse_args(argv)
    try:
        require(REPOSITORY.fullmatch(args.repository) is not None, "invalid repository")
        token = os.environ.get("GH_TOKEN")
        require(bool(token), "GH_TOKEN is required for protected status publication")
        with tempfile.TemporaryDirectory(prefix="quick-skin-status-") as temporary:
            git = Git(Path(temporary) / "status.git", f"https://github.com/{args.repository}.git", token=token)
            result = publish(args.directory, repository=args.repository, expected_sha=args.expected_sha, git=git)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (PublishError, OSError, ValueError) as exc:
        print("Target status publication stopped: " + str(exc))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
