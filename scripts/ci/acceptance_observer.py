#!/usr/bin/env python3
"""Recoverable, read-only observation of explicitly registered GitHub run attempts.

The local inventory is informational: it grants no acceptance, merge or publication authority.
Only the coordinator contacts GitHub; every other reader consumes its atomic local snapshot.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
import os
import re
import signal
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any


MAX_RUNS = 100
MAX_BYTES = 2 * 1024 * 1024
MAX_RESPONSE_BYTES = 512 * 1024
MAX_ERRORS = 20
STATUSES = {"queued", "in_progress", "completed", "waiting", "requested", "pending"}
CONCLUSIONS = {"success", "failure", "neutral", "cancelled", "skipped", "timed_out",
               "action_required", "stale", "startup_failure"}
CATEGORIES = {"http", "transport", "timeout", "rate_limit", "cli_exit", "cli_unavailable",
              "invalid_response", "identity_mismatch", "interrupted"}
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
SHA = re.compile(r"[0-9a-f]{40}")


class ObserverError(ValueError):
    """A local configuration or snapshot cannot be safely used."""


def require(condition: bool, message: str = "invalid observer snapshot") -> None:
    if not condition:
        raise ObserverError(message)


def number(value: Any) -> bool:
    return type(value) in {int, float} and math.isfinite(value) and 0 <= value <= 10**12


def positive(value: Any) -> bool:
    return type(value) is int and 0 < value <= 10**18


def endpoint(repository: str, run: dict) -> str:
    return f"repos/{repository}/actions/runs/{run['id']}/attempts/{run['attempt']}"


def remote_state(run: dict) -> str:
    snapshot = run["snapshot"]
    if snapshot is None or snapshot["status"] != "completed":
        return "pending"
    return "success" if snapshot["conclusion"] == "success" else "remote_failure"


def validate(state: Any) -> dict:
    require(type(state) is dict and set(state) == {
        "schema_version", "repository", "interval", "timeout", "created_at", "heartbeat_at",
        "pause_until", "requests_started", "requests_completed", "active_request", "runs"})
    require(state["schema_version"] == 1 and type(state["repository"]) is str
            and REPOSITORY.fullmatch(state["repository"]) is not None)
    require(number(state["interval"]) and 1 <= state["interval"] <= 3600)
    require(number(state["timeout"]) and 0 < state["timeout"] <= state["interval"])
    for key in ("created_at", "heartbeat_at", "pause_until", "requests_started", "requests_completed"):
        require(number(state[key]))
    require(type(state["requests_started"]) is int and type(state["requests_completed"]) is int
            and state["requests_completed"] <= state["requests_started"])
    runs = state["runs"]
    require(type(runs) is list and 1 <= len(runs) <= MAX_RUNS)
    identities = set()
    endpoints = set()
    for run in runs:
        require(type(run) is dict and set(run) == {
            "id", "attempt", "sha", "snapshot", "error", "errors", "failures", "next_poll_at",
            "recovery_started_at", "recovery_latency_seconds"})
        require(positive(run["id"]) and positive(run["attempt"]) and run["attempt"] <= 1000
                and type(run["sha"]) is str and SHA.fullmatch(run["sha"]) is not None)
        identity = (run["id"], run["attempt"])
        require(identity not in identities)
        identities.add(identity)
        path = endpoint(state["repository"], run)
        endpoints.add(path)
        require(type(run["failures"]) is int and 0 <= run["failures"] <= 10**12)
        require(number(run["next_poll_at"]))
        for key in ("recovery_started_at", "recovery_latency_seconds"):
            require(run[key] is None or number(run[key]))
        snapshot = run["snapshot"]
        if snapshot is not None:
            require(type(snapshot) is dict and set(snapshot) == {"status", "conclusion", "observed_at"})
            require(type(snapshot["status"]) is str and snapshot["status"] in STATUSES)
            require(snapshot["conclusion"] is None or
                    (type(snapshot["conclusion"]) is str and snapshot["conclusion"] in CONCLUSIONS))
            require((snapshot["status"] == "completed") == (snapshot["conclusion"] is not None))
            require(number(snapshot["observed_at"]))
        require(type(run["errors"]) is list and len(run["errors"]) <= MAX_ERRORS)
        for error in run["errors"] + ([run["error"]] if run["error"] is not None else []):
            require(type(error) is dict and set(error) == {
                "endpoint", "category", "exit_status", "http_status", "observed_at"})
            require(error["endpoint"] == path and type(error["category"]) is str
                    and error["category"] in CATEGORIES and number(error["observed_at"]))
            require(error["exit_status"] is None or
                    (type(error["exit_status"]) is int and -(2**31) <= error["exit_status"] < 2**32))
            require(error["http_status"] is None or
                    (type(error["http_status"]) is int and 100 <= error["http_status"] <= 599))
    require(state["active_request"] is None or
            (type(state["active_request"]) is str and state["active_request"] in endpoints))
    return state


def unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def read_state(path: Path) -> dict:
    require(not path.is_symlink(), "observer state must not be a symbolic link")
    with path.open("rb") as stream:
        payload = stream.read(MAX_BYTES + 1)
    require(len(payload) <= MAX_BYTES, "observer snapshot exceeds its byte budget")
    return validate(json.loads(payload, object_pairs_hook=unique_object))


def write_state(path: Path, state: dict) -> None:
    payload = json.dumps(validate(state), sort_keys=True, separators=(",", ":")).encode() + b"\n"
    require(len(payload) <= MAX_BYTES, "observer snapshot exceeds its byte budget")
    require(not path.is_symlink(), "observer state must not be a symbolic link")
    # A fixed sibling staging name is safe while the OS lock is held and recovers after a crash.
    partial = path.with_name(path.name + ".partial")
    require(not partial.is_symlink(), "observer staging file must not be a symbolic link")
    descriptor = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(payload)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(partial, path)


@contextmanager
def coordinator_lock(path: Path):
    """The OS releases ownership even after SIGKILL; readers never acquire this lock."""
    lock = path.with_name(path.name + ".lock")
    require(not lock.is_symlink(), "observer lock must not be a symbolic link")
    with lock.open("a+b") as stream:
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            if lock.stat().st_size == 0:
                stream.write(b"0")
                stream.flush()
                stream.seek(0)
            try:
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise ObserverError("another coordinator owns this inventory") from None
        else:
            import fcntl
            try:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise ObserverError("another coordinator owns this inventory") from None
        try:
            yield
        finally:
            if os.name == "nt":
                stream.seek(0)
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def initialize(repository: str, specs: list[str], interval: float, timeout: float, now: float) -> dict:
    runs = []
    for spec in specs:
        match = re.fullmatch(r"([1-9][0-9]{0,17}):([1-9][0-9]{0,3}):([0-9a-f]{40})", spec)
        require(match is not None, "run identity must be RUN_ID:ATTEMPT:HEAD_SHA")
        runs.append({"id": int(match[1]), "attempt": int(match[2]), "sha": match[3],
                     "snapshot": None, "error": None, "errors": [], "failures": 0,
                     "next_poll_at": now, "recovery_started_at": None,
                     "recovery_latency_seconds": None})
    return validate({"schema_version": 1, "repository": repository, "interval": interval,
                     "timeout": timeout, "created_at": now, "heartbeat_at": now,
                     "pause_until": 0, "requests_started": 0, "requests_completed": 0,
                     "active_request": None, "runs": runs})


class ReadFailure(Exception):
    def __init__(self, category: str, exit_status: int | None = None,
                 http_status: int | None = None, retry_at: float = 0):
        self.category = category
        self.exit_status = exit_status
        self.http_status = http_status
        self.retry_at = retry_at
        self.headers: dict[str, str] = {}
        super().__init__(category)


def response_parts(output: str) -> tuple[int | None, dict, str]:
    headers = {}
    status = None
    body = output
    while body.startswith("HTTP/"):
        block = re.split(r"\r?\n\r?\n", body, maxsplit=1)
        if len(block) != 2:
            break
        lines = block[0].splitlines()
        match = re.fullmatch(r"HTTP/\S+ ([0-9]{3})(?: .*)?", lines[0])
        if match is None:
            break
        status = int(match[1])
        headers = {}
        for line in lines[1:]:
            name, separator, value = line.partition(":")
            if separator and name.lower() in {"retry-after", "x-ratelimit-remaining", "x-ratelimit-reset"}:
                headers[name.lower()] = value.strip()
        body = block[1]
    return status, headers, body


def rate_deadline(headers: dict, now: float) -> float:
    deadline = now
    retry_after = headers.get("retry-after", "")
    if re.fullmatch(r"[0-9]{1,12}", retry_after):
        deadline = max(deadline, now + int(retry_after))
    elif retry_after:
        try:
            deadline = max(deadline, parsedate_to_datetime(retry_after).timestamp())
        except (ValueError, TypeError, OverflowError):
            pass
    reset = headers.get("x-ratelimit-reset", "")
    if headers.get("x-ratelimit-remaining") == "0" and re.fullmatch(r"[0-9]{1,12}", reset):
        deadline = max(deadline, int(reset) + 1)
    return deadline


def bounded_command(command: list[str], timeout: float) -> subprocess.CompletedProcess:
    """Drain both pipes concurrently without ever retaining more than a shared byte budget."""
    process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=os.name != "nt")
    buffers = [bytearray(), bytearray()]
    guard = threading.Lock()
    stopped, overflow, broken = threading.Event(), threading.Event(), threading.Event()
    done = [threading.Event(), threading.Event()]

    def drain(stream, index):
        try:
            while not stopped.is_set():
                chunk = stream.read1(8192)
                if not chunk:
                    return
                with guard:
                    if sum(map(len, buffers)) + len(chunk) > MAX_RESPONSE_BYTES:
                        overflow.set()
                        return
                    buffers[index].extend(chunk)
        except OSError:
            broken.set()
        finally:
            stream.close()
            done[index].set()

    workers = [threading.Thread(target=drain, args=(stream, index), daemon=True)
               for index, stream in enumerate((process.stdout, process.stderr))]
    deadline = time.monotonic() + timeout
    for worker in workers:
        worker.start()
    try:
        while not all(event.is_set() for event in done) or process.poll() is None:
            if overflow.is_set():
                raise ReadFailure("invalid_response")
            if broken.is_set():
                raise ReadFailure("transport")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ReadFailure("timeout")
            stopped.wait(min(0.01, remaining))
        if overflow.is_set():
            raise ReadFailure("invalid_response")
        if broken.is_set():
            raise ReadFailure("transport")
        return subprocess.CompletedProcess(command, process.returncode, bytes(buffers[0]), bytes(buffers[1]))
    except ReadFailure as failure:
        # Even a stalled or oversized body can have already declared an HTTP cooldown.
        with guard:
            failure.http_status, failure.headers, _ = response_parts(
                bytes(buffers[0]).decode("utf-8", errors="replace"))
        failure.exit_status = process.poll()
        raise
    finally:
        stopped.set()
        if os.name != "nt":
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        elif process.poll() is None:
            process.kill()
        process.wait()
        for worker in workers:
            worker.join(timeout=0.2)


class GitHubReader:
    def read(self, path: str, timeout: float, now: float) -> tuple[dict, float]:
        try:
            # No shell, inherited hooks, request bodies, pagination, logs or artifacts.
            completed = bounded_command(
                ["gh", "api", "--hostname", "github.com", "--method", "GET", "--include", path],
                timeout)
        except ReadFailure as failure:
            failure.retry_at = rate_deadline(failure.headers, now)
            if failure.http_status == 429 or failure.headers.get("x-ratelimit-remaining") == "0":
                failure.category = "rate_limit"
                failure.retry_at = max(failure.retry_at, now + 60)
            raise
        except OSError:
            raise ReadFailure("cli_unavailable") from None
        output = completed.stdout.decode("utf-8", errors="replace")
        diagnostic = completed.stderr.decode("utf-8", errors="replace")
        status, headers, body = response_parts(output)
        if status is None:
            match = re.search(r"\bHTTP ([1-5][0-9]{2})\b", diagnostic)
            status = int(match[1]) if match else None
        retry_at = rate_deadline(headers, now)
        if completed.returncode != 0 or status != 200:
            text = (body + diagnostic).lower()
            if status == 429 or "rate limit" in text or headers.get("x-ratelimit-remaining") == "0":
                category = "rate_limit"
                retry_at = max(retry_at, now + 60)
            elif status is not None:
                category = "http"
            elif any(word in text for word in ("timeout", "timed out", "connection", "tls", "eof")):
                category = "transport"
            else:
                category = "cli_exit"
            raise ReadFailure(category, completed.returncode, status, retry_at)
        try:
            result = json.loads(body, object_pairs_hook=unique_object)
        except (ValueError, TypeError, RecursionError):
            raise ReadFailure("invalid_response", completed.returncode, status) from None
        return result, retry_at


def normalize_remote(value: Any, repository: str, run: dict, now: float) -> dict:
    if not isinstance(value, dict):
        raise ReadFailure("invalid_response", 0, 200)
    owner = value.get("repository")
    if (type(value.get("id")) is not int or value["id"] != run["id"]
            or type(value.get("run_attempt")) is not int or value["run_attempt"] != run["attempt"]
            or value.get("head_sha") != run["sha"] or not isinstance(owner, dict)
            or type(owner.get("full_name")) is not str
            or owner["full_name"].lower() != repository.lower()):
        raise ReadFailure("identity_mismatch", 0, 200)
    status, conclusion = value.get("status"), value.get("conclusion")
    if (type(status) is not str or status not in STATUSES
            or (conclusion is not None and (type(conclusion) is not str or conclusion not in CONCLUSIONS))
            or (status == "completed") != (conclusion is not None)):
        raise ReadFailure("invalid_response", 0, 200)
    return {"status": status, "conclusion": conclusion, "observed_at": now}


def record_failure(state: dict, run: dict, failure: ReadFailure, now: float) -> None:
    error = {"endpoint": endpoint(state["repository"], run), "category": failure.category,
             "exit_status": failure.exit_status, "http_status": failure.http_status, "observed_at": now}
    run["error"] = error
    run["errors"] = (run["errors"] + [error])[-MAX_ERRORS:]
    run["failures"] += 1
    if run["recovery_started_at"] is None:
        run["recovery_started_at"] = now
    # Three quick attempts at most, followed by ordinary polls. Every call is timeout-bounded.
    delay = min(state["interval"], 2 ** min(run["failures"], 2)) if run["failures"] < 3 else state["interval"]
    if failure.category == "rate_limit":
        # Secondary limits require progressively longer waits, shared by every run in the inventory.
        delay = max(delay, min(3600, 60 * 2 ** min(run["failures"] - 1, 6)))
        state["pause_until"] = max(state["pause_until"], failure.retry_at, now + delay)
    run["next_poll_at"] = max(now + delay, failure.retry_at)


class Coordinator:
    """Call only while holding coordinator_lock for this state's entire lifetime."""
    def __init__(self, path: Path, state: dict, reader=None, clock=time.time, monotonic=time.monotonic):
        self.path, self.state = path, state
        self.reader, self.clock = reader or GitHubReader(), clock
        self.monotonic = monotonic
        active = state["active_request"]
        if active is not None:
            run = next(run for run in state["runs"] if endpoint(state["repository"], run) == active)
            record_failure(state, run, ReadFailure("interrupted"), clock())
            state["active_request"] = None
        self.save()

    def save(self) -> None:
        self.state["heartbeat_at"] = self.clock()
        write_state(self.path, self.state)

    def poll(self, deadline: float | None = None) -> None:
        state = self.state
        self.save()
        # Reserve at most one interval for a complete sweep, so one slow sibling cannot hold
        # terminal observation of another behind N full request timeouts.
        pending_count = sum(remote_state(run) == "pending" for run in state["runs"])
        timeout = min(state["timeout"], state["interval"] / max(1, pending_count))
        for run in sorted(state["runs"], key=lambda item: item["next_poll_at"]):
            remaining = float("inf") if deadline is None else deadline - self.monotonic()
            if remaining <= 0:
                break
            now = self.clock()
            if (remote_state(run) != "pending" or now < run["next_poll_at"]
                    or now < state["pause_until"]):
                continue
            path = endpoint(state["repository"], run)
            state["active_request"] = path
            state["requests_started"] += 1
            self.save()  # A killed read remains visible, including its request budget.
            try:
                value, pause_until = self.reader.read(path, min(timeout, remaining), now)
                snapshot = normalize_remote(value, state["repository"], run, self.clock())
                state["pause_until"] = max(state["pause_until"], pause_until)
                run["snapshot"] = snapshot
                run["error"], run["failures"] = None, 0
                run["next_poll_at"] = self.clock() + state["interval"]
                if run["recovery_started_at"] is not None:
                    run["recovery_latency_seconds"] = max(0, self.clock() - run["recovery_started_at"])
                    run["recovery_started_at"] = None
            except ReadFailure as failure:
                record_failure(state, run, failure, self.clock())
            state["requests_completed"] += 1
            state["active_request"] = None
            self.save()


def status_view(state: dict, now: float) -> dict:
    result = copy.deepcopy(validate(state))
    age = now - state["heartbeat_at"]
    dead = age < 0 or age >= 2 * state["interval"]
    result["heartbeat_age_seconds"] = max(0, age)
    states = []
    for run in result["runs"]:
        remote = remote_state(run)
        run["remote_state"] = remote
        snapshot = run["snapshot"]
        last = snapshot["observed_at"] if snapshot else state["created_at"]
        run["snapshot_stale"] = remote == "pending" and (now < last or now - last >= 2 * state["interval"])
        run["state"] = remote if remote != "pending" else (
            "stale" if dead else "read_error" if run["error"] else "stale" if run["snapshot_stale"] else "pending")
        states.append(run["state"])
    result["state"] = next((value for value in ("remote_failure", "stale", "read_error", "pending")
                            if value in states), "success")
    result["terminal"] = all(remote_state(run) != "pending" for run in state["runs"])
    return result


def emit(state: dict) -> int:
    print(json.dumps(state, sort_keys=True), flush=True)
    return 0 if state["state"] == "success" else 1 if state["state"] == "remote_failure" else 2


def status_delay(state: dict, now: float) -> float:
    """Wake at the stale boundary even if this local reader started between heartbeats."""
    expiry = [state["heartbeat_at"] + 2 * state["interval"]]
    for run in state["runs"]:
        if remote_state(run) == "pending":
            last = run["snapshot"]["observed_at"] if run["snapshot"] else state["created_at"]
            expiry.append(last + 2 * state["interval"])
    return min([state["interval"]] + [value - now for value in expiry if value > now])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("init", help="register exact existing run identities locally; no API calls")
    create.add_argument("--repository", required=True)
    create.add_argument("--run", action="append", required=True, help="RUN_ID:ATTEMPT:HEAD_SHA")
    create.add_argument("--interval", type=float, default=30)
    create.add_argument("--timeout", type=float, default=10)
    for command in ("observe", "status"):
        sub = commands.add_parser(command, help="coordinate GitHub reads" if command == "observe" else "read local snapshot only")
        sub.add_argument("--watch", action="store_true")
        sub.add_argument("--max-seconds", type=float, default=3600)
    args = parser.parse_args(argv)
    path = args.state.absolute()
    if args.command == "init":
        state = initialize(args.repository, args.run, args.interval, args.timeout, time.time())
        with coordinator_lock(path):
            require(not path.exists(), "inventory already exists; resume observe or use a new state path")
            write_state(path, state)
        emit(status_view(state, time.time()))
        return 0
    require(number(args.max_seconds) and 0 < args.max_seconds <= 604800, "invalid observation time budget")
    deadline = time.monotonic() + args.max_seconds
    if args.command == "status":
        while True:
            state = read_state(path)
            observed_at = time.time()
            view = status_view(state, observed_at)
            code = emit(view)
            if not args.watch or view["terminal"] or time.monotonic() >= deadline:
                return code
            delay = status_delay(state, observed_at) - (time.time() - observed_at)
            time.sleep(max(0, min(delay, deadline - time.monotonic())))
    with coordinator_lock(path):
        coordinator = Coordinator(path, read_state(path))
        while True:
            coordinator.poll(deadline)
            view = status_view(coordinator.state, time.time())
            code = emit(view)
            if not args.watch or view["terminal"] or time.monotonic() >= deadline:
                return code
            # Heartbeat at least once per interval even during a provider-declared long pause.
            now = time.time()
            pending = [run["next_poll_at"] for run in coordinator.state["runs"] if remote_state(run) == "pending"]
            due = max(min(pending), coordinator.state["pause_until"])
            time.sleep(min(coordinator.state["interval"], max(0.1, due - now),
                           max(0, deadline - time.monotonic())))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('{"state":"interrupted"}', file=sys.stderr)
        raise SystemExit(130)
    except (ObserverError, OSError, ValueError, TypeError, KeyError, OverflowError, RecursionError):
        # Never format exceptions: CLI/provider data, filesystem paths and secrets stay private.
        print('{"state":"local_read_error"}', file=sys.stderr)
        raise SystemExit(3)
