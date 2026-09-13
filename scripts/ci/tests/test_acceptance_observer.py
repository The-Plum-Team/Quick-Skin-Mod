from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


CI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI))
import acceptance_observer as observer


REPOSITORY = "example/acceptance"
SHA = "a" * 40
SPEC = f"123:2:{SHA}"


def remote(**changes) -> dict:
    return {"id": 123, "run_attempt": 2, "head_sha": SHA,
            "repository": {"full_name": REPOSITORY}, "status": "in_progress",
            "conclusion": None, **changes}


class Clock:
    def __init__(self):
        self.now = 1800000000.0

    def __call__(self):
        return self.now


class Reader:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def read(self, path, timeout, now):
        self.requests.append(path)
        value = self.responses.pop(0)
        if isinstance(value, Exception):
            raise value
        return value, 0


class AcceptanceObserverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "state.json"
        self.clock = Clock()
        self.state = observer.initialize(REPOSITORY, [SPEC], 10, 1, self.clock())

    def coordinator(self, reader):
        return observer.Coordinator(self.path, self.state, reader, self.clock)

    def view(self):
        return observer.status_view(observer.read_state(self.path), self.clock())

    def test_nonzero_exit_preserves_snapshot_and_recovers_with_bounded_request_count(self):
        reader = Reader(remote(), observer.ReadFailure("http", 1, 503),
                        remote(status="completed", conclusion="success"))
        with observer.coordinator_lock(self.path):
            coordinator = self.coordinator(reader)
            coordinator.poll()
            last = observer.read_state(self.path)["runs"][0]["snapshot"]
            self.clock.now += 10
            coordinator.poll()
            failed = self.view()
            self.assertEqual("read_error", failed["state"])
            self.assertEqual(last, failed["runs"][0]["snapshot"])
            self.assertEqual(503, failed["runs"][0]["error"]["http_status"])
            coordinator.poll()
            self.assertEqual(2, len(reader.requests))
            self.clock.now += 2
            coordinator.poll()
            self.assertEqual("success", self.view()["state"])
            self.assertEqual(2, self.view()["runs"][0]["recovery_latency_seconds"])
            self.clock.now += 1000
            coordinator.poll()
        self.assertEqual(3, self.view()["requests_started"])
        self.assertEqual(3, self.view()["requests_completed"])
        self.assertEqual([f"repos/{REPOSITORY}/actions/runs/123/attempts/2"] * 3, reader.requests)

    def test_dead_observer_is_stale_at_two_intervals_without_any_remote_reads(self):
        with observer.coordinator_lock(self.path):
            self.coordinator(Reader(remote())).poll()
        self.clock.now += 19.999
        self.assertEqual("pending", self.view()["state"])
        self.clock.now += 0.001
        self.assertEqual("stale", self.view()["state"])
        self.assertEqual("pending", self.view()["runs"][0]["remote_state"])
        self.assertTrue(self.view()["runs"][0]["snapshot_stale"])

    def test_local_watch_wakes_at_stale_boundary_between_regular_polls(self):
        with observer.coordinator_lock(self.path):
            self.coordinator(Reader(remote())).poll()
        self.clock.now += 19.5
        self.assertEqual(0.5, observer.status_delay(self.state, self.clock()))
        self.clock.now += observer.status_delay(self.state, self.clock())
        self.assertEqual("stale", self.view()["state"])

    def test_terminal_failure_is_distinct_from_api_failure_and_never_polled_again(self):
        for conclusion in observer.CONCLUSIONS - {"success"}:
            with self.subTest(conclusion=conclusion):
                self.state = observer.initialize(REPOSITORY, [SPEC], 10, 1, self.clock())
                reader = Reader(remote(status="completed", conclusion=conclusion))
                with observer.coordinator_lock(self.path):
                    coordinator = self.coordinator(reader)
                    coordinator.poll()
                    self.clock.now += 100
                    coordinator.poll()
                self.assertEqual("remote_failure", self.view()["state"])
                self.assertTrue(self.view()["terminal"])
                self.assertEqual(1, len(reader.requests))

    def test_changed_head_attempt_run_or_repository_never_replaces_original_snapshot(self):
        for change in ({"head_sha": "b" * 40}, {"run_attempt": 3}, {"id": 124},
                       {"repository": {"full_name": "other/repo"}},
                       {"repository": {"full_name": []}}):
            with self.subTest(change=change):
                self.state = observer.initialize(REPOSITORY, [SPEC], 10, 1, self.clock())
                reader = Reader(remote(), remote(status="completed", conclusion="success", **change))
                with observer.coordinator_lock(self.path):
                    coordinator = self.coordinator(reader)
                    coordinator.poll()
                    self.clock.now += 10
                    coordinator.poll()
                view = self.view()
                self.assertEqual("read_error", view["state"])
                self.assertEqual("identity_mismatch", view["runs"][0]["error"]["category"])
                self.assertEqual("in_progress", view["runs"][0]["snapshot"]["status"])
                self.assertEqual(2, view["runs"][0]["attempt"])
                self.assertFalse(view["terminal"])

    def test_restart_after_remote_completion_reuses_exact_persisted_inventory(self):
        with observer.coordinator_lock(self.path):
            self.coordinator(Reader(remote())).poll()
        self.clock.now += 20
        self.assertEqual("stale", self.view()["state"])
        self.state = observer.read_state(self.path)
        reader = Reader(remote(status="completed", conclusion="success"))
        with observer.coordinator_lock(self.path):
            self.coordinator(reader).poll()
        self.assertEqual("success", self.view()["state"])
        self.assertEqual(2, self.view()["requests_started"])
        self.assertEqual(1, len(reader.requests))

    def test_rate_limit_stops_every_run_until_reset_and_persists_across_restart(self):
        self.state = observer.initialize(REPOSITORY, [SPEC, f"124:1:{SHA}"], 10, 1, self.clock())
        reset = self.clock() + 600
        reader = Reader(observer.ReadFailure("rate_limit", 1, 403, reset),
                        remote(id=124, run_attempt=1), remote())
        with observer.coordinator_lock(self.path):
            self.coordinator(reader).poll()
        self.assertEqual(1, len(reader.requests))
        self.state = observer.read_state(self.path)
        with observer.coordinator_lock(self.path):
            coordinator = self.coordinator(reader)
            self.clock.now += 599
            coordinator.poll()
            self.assertEqual(1, len(reader.requests))
            self.assertEqual("read_error", self.view()["runs"][0]["state"])
            self.clock.now += 1
            coordinator.poll()
        self.assertEqual(3, len(reader.requests))
        self.assertEqual("pending", self.view()["state"])

    def test_failed_reads_and_error_history_are_bounded(self):
        reader = Reader(*[observer.ReadFailure("timeout") for _ in range(30)])
        with observer.coordinator_lock(self.path):
            coordinator = self.coordinator(reader)
            for count in range(30):
                coordinator.poll()
                run = self.state["runs"][0]
                expected = 2 if count == 0 else 4 if count == 1 else 10
                self.assertEqual(expected, run["next_poll_at"] - self.clock())
                self.clock.now = run["next_poll_at"]
        self.assertEqual(20, len(self.view()["runs"][0]["errors"]))
        self.assertEqual("read_error", self.view()["state"])
        self.assertIsNone(self.view()["runs"][0]["snapshot"])

    def test_invalid_remote_response_and_local_state_fail_closed(self):
        for value in ({}, [], remote(status="completed"), remote(conclusion="success"),
                      remote(status="unrecognized"), remote(conclusion=[])):
            with self.subTest(value=value), self.assertRaises(observer.ReadFailure):
                observer.normalize_remote(value, REPOSITORY, self.state["runs"][0], self.clock())
        for value in ("{}", '{"schema_version":1,"schema_version":1}', "not json"):
            self.path.write_text(value)
            with self.assertRaises(ValueError):
                observer.read_state(self.path)
        with self.assertRaises(observer.ObserverError):
            observer.initialize(REPOSITORY, [SPEC] * 2, 10, 1, self.clock())
        with self.assertRaises(observer.ObserverError):
            observer.initialize(REPOSITORY, [SPEC], 10, 11, self.clock())

    def test_atomic_replace_failure_preserves_previous_snapshot(self):
        observer.write_state(self.path, self.state)
        previous = self.path.read_bytes()
        self.state["heartbeat_at"] += 10
        with mock.patch.object(observer.os, "replace", side_effect=OSError("private diagnostic")):
            with self.assertRaises(OSError):
                observer.write_state(self.path, self.state)
        self.assertEqual(previous, self.path.read_bytes())
        observer.write_state(self.path, self.state)
        self.assertEqual(self.state, observer.read_state(self.path))
        self.assertFalse(self.path.with_suffix(".json.partial").exists())

    def test_actual_cli_failure_timeout_and_headers_never_persist_raw_output(self):
        executable = Path(self.temporary.name) / "gh"
        environment = {"PATH": self.temporary.name + os.pathsep + os.defpath}
        prefix = f"#!{sys.executable}\nimport sys, time\n"
        cases = [
            ("print('private detail (HTTP 503)', file=sys.stderr); sys.exit(7)", "http", 7, 503),
            ("print('private detail', file=sys.stderr); sys.exit(9)", "cli_exit", 9, None),
            ("print('private detail', flush=True); time.sleep(5)", "timeout", None, None),
            ("print('HTTP/2.0 429 Too Many Requests\\nRetry-After: 120\\n\\nprivate detail'); sys.exit(1)",
             "rate_limit", 1, 429),
            ("print('HTTP/2.0 429 Too Many Requests\\nRetry-After: 120\\n\\n', flush=True); time.sleep(5)",
             "rate_limit", None, 429),
        ]
        for program, category, code, status in cases:
            executable.write_text(prefix + program + "\n")
            executable.chmod(0o700)
            self.state = observer.initialize(REPOSITORY, [SPEC], 10, 1, self.clock())
            with mock.patch.dict(os.environ, environment, clear=True), observer.coordinator_lock(self.path):
                self.coordinator(observer.GitHubReader()).poll()
            view = self.view()
            error = view["runs"][0]["error"]
            self.assertEqual((category, code, status), (error["category"], error["exit_status"], error["http_status"]))
            self.assertNotIn("private detail", self.path.read_text())
            if category == "rate_limit":
                self.assertEqual(self.clock() + 120, view["pause_until"])

    def test_http_headers_honor_reset_and_retry_after_dates(self):
        status, headers, body = observer.response_parts(
            "HTTP/2.0 403 Forbidden\r\nX-Ratelimit-Remaining: 0\r\n"
            "X-Ratelimit-Reset: 1800000500\r\nLocation: private\r\n\r\n{}")
        self.assertEqual(403, status)
        self.assertNotIn("location", headers)
        self.assertEqual("{}", body)
        self.assertEqual(1800000501, observer.rate_deadline(headers, self.clock()))
        self.assertGreater(observer.rate_deadline({"retry-after": "Thu, 15 Jan 2027 08:30:00 GMT"},
                                                self.clock()), self.clock())

    def test_native_exit_codes_and_deeply_nested_json_stay_sanitized(self):
        reader = observer.GitHubReader()
        path = observer.endpoint(REPOSITORY, self.state["runs"][0])
        for code in (-1073741510, 3221225477):
            result = subprocess.CompletedProcess([], code, b"", b"private detail")
            with mock.patch.object(observer, "bounded_command", return_value=result):
                with self.assertRaises(observer.ReadFailure) as failure:
                    reader.read(path, 1, self.clock())
            observer.record_failure(self.state, self.state["runs"][0], failure.exception, self.clock())
            observer.validate(self.state)
            self.assertEqual(code, self.state["runs"][0]["error"]["exit_status"])
        result = subprocess.CompletedProcess([], 0, b"HTTP/2.0 200 OK\n\n" + b"[" * 2000 + b"]" * 2000, b"")
        with mock.patch.object(observer, "bounded_command", return_value=result):
            with self.assertRaises(observer.ReadFailure) as failure:
                reader.read(path, 1, self.clock())
        self.assertEqual("invalid_response", failure.exception.category)

    def test_cli_success_uses_only_exact_get_and_projects_allowlisted_fields(self):
        executable = Path(self.temporary.name) / "gh"
        expected = ["api", "--hostname", "github.com", "--method", "GET", "--include",
                    f"repos/{REPOSITORY}/actions/runs/123/attempts/2"]
        response = remote(status="completed", conclusion="success", arbitrary="private detail")
        executable.write_text(f"#!{sys.executable}\nimport sys\n"
                              f"assert sys.argv[1:] == {expected!r}\n"
                              f"print('HTTP/2.0 200 OK\\n\\n' + {json.dumps(response)!r})\n")
        executable.chmod(0o700)
        environment = {"PATH": self.temporary.name + os.pathsep + os.defpath}
        with mock.patch.dict(os.environ, environment, clear=True), observer.coordinator_lock(self.path):
            self.coordinator(observer.GitHubReader()).poll()
        self.assertEqual("success", self.view()["state"])
        self.assertNotIn("private detail", self.path.read_text())
        self.assertNotIn("arbitrary", self.path.read_text())

    def test_json_depth_has_an_explicit_limit_before_any_decoder_allocation(self):
        for opening, closing in (("[", "]"), ('{"child":[', "]}")):
            levels = observer.MAX_JSON_DEPTH // len(closing)
            accepted = opening * levels + "0" + closing * levels
            self.assertIsNotNone(observer.parse_json(accepted))
            excessive = opening + accepted + closing
            with mock.patch.object(observer.json, "loads") as decoder:
                with self.assertRaisesRegex(observer.ObserverError, "nesting budget"):
                    observer.parse_json(excessive)
                decoder.assert_not_called()
            self.path.write_text(excessive)
            with mock.patch.object(observer.json, "loads") as decoder:
                with self.assertRaisesRegex(observer.ObserverError, "nesting budget"):
                    observer.read_state(self.path)
                decoder.assert_not_called()

    def test_json_depth_ignores_escaped_string_content_and_preserves_grammar_checks(self):
        value = {"quoted": '[{\\"' * 2000, "backslash": "\\", "child": ["]}"]}
        self.assertEqual(value, observer.parse_json(json.dumps(value)))
        for malformed in ('{"id":1,"id":2}', "[}", "]", '{"x":"unterminated'):
            with self.subTest(malformed=malformed), self.assertRaises(ValueError):
                observer.parse_json(malformed)

    def test_excessive_remote_extension_depth_preserves_snapshot_and_sanitizes_failure(self):
        with observer.coordinator_lock(self.path):
            self.coordinator(Reader(remote())).poll()
            previous = self.view()["runs"][0]["snapshot"]
            self.clock.now += 10
            nested = "[" * observer.MAX_JSON_DEPTH + '"private nested detail"' + "]" * observer.MAX_JSON_DEPTH
            body = json.dumps(remote())[:-1] + ',"extension":' + nested + "}"
            result = subprocess.CompletedProcess([], 0, ("HTTP/2.0 200 OK\n\n" + body).encode(), b"")
            with mock.patch.object(observer, "bounded_command", return_value=result):
                self.coordinator(observer.GitHubReader()).poll()
        view = self.view()
        self.assertEqual("read_error", view["state"])
        self.assertEqual(previous, view["runs"][0]["snapshot"])
        self.assertEqual("invalid_response", view["runs"][0]["error"]["category"])
        self.assertEqual(2, view["requests_started"])
        self.assertEqual(2, view["requests_completed"])
        self.assertNotIn("private nested detail", self.path.read_text())

    def test_slow_sibling_timeouts_share_one_interval_budget(self):
        self.state = observer.initialize(REPOSITORY, [SPEC, f"124:1:{SHA}"], 10, 10, self.clock())
        calls = []
        clock = self.clock

        class SlowReader:
            def read(self, path, timeout, now):
                calls.append(timeout)
                clock.now += timeout
                raise observer.ReadFailure("timeout")

        started = self.clock()
        with observer.coordinator_lock(self.path):
            self.coordinator(SlowReader()).poll()
        self.assertEqual([5, 5], calls)
        self.assertEqual(10, self.clock() - started)
        self.assertEqual("read_error", self.view()["state"])

    def test_watch_deadline_bounds_sweep_and_restart_prioritizes_unread_siblings(self):
        self.state = observer.initialize(REPOSITORY, [SPEC, f"124:1:{SHA}"], 10, 10, self.clock())
        calls = []
        clock = self.clock

        class SlowReader:
            def read(self, path, timeout, now):
                calls.append((path, timeout))
                clock.now += timeout
                raise observer.ReadFailure("timeout")

        with observer.coordinator_lock(self.path):
            coordinator = observer.Coordinator(self.path, self.state, SlowReader(), self.clock, self.clock)
            coordinator.poll(self.clock() + 0.5)
            self.assertEqual(1, len(calls))
            self.assertEqual(0.5, calls[0][1])
            coordinator.poll(self.clock() + 0.5)
            self.assertEqual(2, len(calls))
            self.assertIn("/runs/124/attempts/1", calls[1][0])

    def test_pipe_budget_bounds_stdout_and_stderr_and_reaps_the_process(self):
        for stream in ("stdout", "stderr"):
            with self.subTest(stream=stream):
                command = [sys.executable, "-c", "import sys, time; "
                           f"sys.{stream}.write('x' * 2000000); sys.{stream}.flush(); time.sleep(10)"]
                started = time.monotonic()
                with self.assertRaises(observer.ReadFailure) as failure:
                    observer.bounded_command(command, 2)
                self.assertEqual("invalid_response", failure.exception.category)
                self.assertLess(time.monotonic() - started, 2)

    @unittest.skipIf(os.name == "nt", "POSIX process-kill fault injection")
    def test_killed_coordinator_releases_lock_and_recovers_an_inflight_read(self):
        observer.write_state(self.path, self.state)
        worker = "\n".join([
            "import os, signal, sys, time",
            f"sys.path.insert(0, {str(CI)!r})",
            "import acceptance_observer as o",
            "from pathlib import Path",
            "path = Path(sys.argv[1])",
            "class InterruptedReader:",
            "    def read(self, *args):",
            "        os.kill(os.getpid(), signal.SIGKILL)",
            "with o.coordinator_lock(path):",
            "    state = o.read_state(path)",
            "    o.Coordinator(path, state, InterruptedReader(), lambda: state['created_at']).poll()",
        ])
        start = time.monotonic()
        completed = subprocess.run([sys.executable, "-c", worker, str(self.path)],
                                   capture_output=True, timeout=5)
        self.assertLess(completed.returncode, 0)
        self.state = observer.read_state(self.path)
        self.assertIsNotNone(self.state["active_request"])
        self.clock.now = self.state["heartbeat_at"] + 20
        self.assertEqual("stale", self.view()["state"])
        with observer.coordinator_lock(self.path):
            coordinator = self.coordinator(Reader(remote(status="completed", conclusion="success")))
            self.assertEqual("interrupted", self.view()["runs"][0]["error"]["category"])
            self.clock.now += 2
            coordinator.poll()
        view = self.view()
        self.assertEqual("success", view["state"])
        self.assertEqual(2, view["requests_started"])
        self.assertEqual(1, view["requests_completed"])
        self.assertEqual(2, view["runs"][0]["recovery_latency_seconds"])
        wall_seconds = time.monotonic() - start
        self.assertLess(wall_seconds, 5)
        print(json.dumps({"fault_injection": "killed_inflight_read_then_remote_completion",
                          "requests_started": view["requests_started"],
                          "requests_completed": view["requests_completed"],
                          "recovery_latency_seconds": view["runs"][0]["recovery_latency_seconds"],
                          "wall_clock_seconds": round(wall_seconds, 6)}))

    def test_second_coordinator_is_refused_and_status_needs_no_gh(self):
        observer.write_state(self.path, self.state)
        environment = {"PATH": os.defpath}
        command = [sys.executable, str(CI / "acceptance_observer.py"), "--state", str(self.path)]
        with observer.coordinator_lock(self.path):
            completed = subprocess.run(command + ["observe"], capture_output=True, text=True,
                                       env=environment, timeout=5)
            self.assertEqual(3, completed.returncode)
            self.assertEqual('{"state":"local_read_error"}\n', completed.stderr)
            completed = subprocess.run(command + ["status"], capture_output=True, text=True,
                                       env=environment, timeout=5)
            self.assertEqual(0, json.loads(completed.stdout)["requests_started"])
        self.assertEqual(0, observer.read_state(self.path)["requests_started"])


if __name__ == "__main__":
    unittest.main()
