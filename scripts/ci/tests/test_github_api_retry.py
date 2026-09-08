from __future__ import annotations

import json
import os
import shlex
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
HELPER = ROOT / "scripts" / "ci" / "github_api_retry.sh"


class GitHubApiRetryTest(unittest.TestCase):
    def _run(
        self,
        fake_gh: str,
        *,
        invocation: str = "github_api_retry actions/artifacts --jq .name",
        environment_overrides: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            gh = temp / "gh"
            gh.write_text("#!/usr/bin/env bash\n" + textwrap.dedent(fake_gh), encoding="utf-8")
            gh.chmod(0o755)
            if jq := shutil.which("jq"):
                (temp / "jq").symlink_to(jq)
            environment = {
                # A developer shell can inject a real gh function through BASH_ENV,
                # overriding the fixture executable even with a prepended PATH.
                # Tests need only system utilities and their owned fake CLI.
                "PATH": f"{temp}{os.pathsep}{os.defpath}",
                "GH_CONFIG_DIR": str(temp / "gh-config"),
                "RETRY_TEST_STATE": str(temp / "attempts"),
                **(environment_overrides or {}),
            }
            script = f"""
                set -euo pipefail
                sleep() {{ :; }}
                source {shlex.quote(str(HELPER))}
                {invocation}
            """
            return subprocess.run(
                ["bash", "-c", script],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    @unittest.skipUnless(shutil.which("jq"), "budget telemetry uses the workflow's jq")
    def test_budget_snapshot_uses_callers_credential_and_prints_only_numeric_counters(self) -> None:
        completed = self._run('''
            [[ "$1 $2" == "api rate_limit" && "$GH_TOKEN" == "fixture-token" ]] || exit 98
            [[ ! -e "$RETRY_TEST_STATE" ]] || exit 99
            touch "$RETRY_TEST_STATE"
            printf '%s\\n' '{"limit":1000,"used":100,"remaining":900,"reset":1800000000,"token":"must-not-print","Location":"signed-secret"}'
        ''', invocation="github_api_budget_snapshot", environment_overrides={"GH_TOKEN": "fixture-token"})
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual("", completed.stdout)
        prefix = "GitHub REST core budget: "
        self.assertTrue(completed.stderr.startswith(prefix), completed.stderr)
        self.assertEqual({"limit": 1000, "used": 100, "remaining": 900, "reset": 1800000000},
                         json.loads(completed.stderr.removeprefix(prefix)))
        self.assertNotIn("secret", completed.stderr)
        self.assertNotIn("token", completed.stderr)

    @unittest.skipUnless(shutil.which("jq"), "budget telemetry uses the workflow's jq")
    def test_unavailable_or_malformed_budget_never_leaks_diagnostics_or_changes_admission(self) -> None:
        for body in ('exit 1', 'printf \'signed-secret\\n\'',
                     'printf \'{"limit":1000,"used":true,"remaining":900,"reset":1800000000}\\n\''):
            with self.subTest(body=body):
                completed = self._run('printf \'sensitive diagnostics\\n\' >&2\n' + body,
                    invocation="github_api_budget_snapshot; printf 'actual-admission-still-required\\n'")
                self.assertEqual(0, completed.returncode)
                self.assertEqual("actual-admission-still-required\n", completed.stdout)
                self.assertEqual("GitHub REST core budget telemetry unavailable.\n", completed.stderr)

    def test_inherited_shell_hooks_and_credentials_cannot_replace_fake_cli(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            hook = Path(temporary) / "inherited-shell-hook"
            hook.write_text("gh() { printf 'unexpected real CLI hook\\n'; return 97; }\n")
            with mock.patch.dict(os.environ, {
                "BASH_ENV": str(hook),
                "ENV": str(hook),
                "GH_TOKEN": "must-not-reach-fixture",
                "GITHUB_TOKEN": "must-not-reach-fixture",
            }):
                completed = self._run("""
                    [[ -z "${GH_TOKEN:-}${GITHUB_TOKEN:-}${BASH_ENV:-}${ENV:-}" ]] || exit 98
                    printf 'isolated-fixture\\n'
                """)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "isolated-fixture\n")

    def test_retries_rate_limit_without_polluting_response_stdout(self) -> None:
        completed = self._run(
            """
            if [[ "$*" == *"rate_limit"* ]]; then
              printf '1\n'
              exit 0
            fi
            attempts=0
            [[ -f "$RETRY_TEST_STATE" ]] && attempts="$(<"$RETRY_TEST_STATE")"
            attempts=$((attempts + 1))
            printf '%s' "$attempts" > "$RETRY_TEST_STATE"
            if (( attempts == 1 )); then
              printf 'gh: API rate limit exceeded for installation (HTTP 403)\n' >&2
              exit 1
            fi
            printf 'authenticated-response\n'
            """
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "authenticated-response\n")
        self.assertIn("waiting 1s for its declared reset", completed.stderr)

    def test_does_not_retry_or_hide_non_transient_error(self) -> None:
        completed = self._run(
            """
            printf 'gh: Not Found (HTTP 404)\n' >&2
            exit 1
            """
        )

        self.assertEqual(completed.returncode, 1)
        self.assertEqual(completed.stdout, "")
        self.assertEqual(completed.stderr, "gh: Not Found (HTTP 404)\n")

    def test_critical_callers_can_extend_the_bounded_retry_window(self) -> None:
        completed = self._run(
            """
            if [[ "$*" == *"rate_limit"* ]]; then
              printf '1\n'
              exit 0
            fi
            attempts=0
            [[ -f "$RETRY_TEST_STATE" ]] && attempts="$(<"$RETRY_TEST_STATE")"
            attempts=$((attempts + 1))
            printf '%s' "$attempts" > "$RETRY_TEST_STATE"
            if (( attempts < 6 )); then
              printf 'gh: API rate limit exceeded for installation (HTTP 403)\n' >&2
              exit 1
            fi
            printf 'recovered-response\n'
            """,
            environment_overrides={
                "GITHUB_API_RETRY_ATTEMPTS": "6",
                "GITHUB_API_RETRY_MAX_DELAY_SECONDS": "1",
            },
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "recovered-response\n")
        self.assertEqual(completed.stderr.count("waiting 1s for its declared reset"), 5)

    def test_rejects_unbounded_retry_configuration(self) -> None:
        completed = self._run(
            "printf 'must not run\n'\n",
            environment_overrides={"GITHUB_API_RETRY_ATTEMPTS": "31"},
        )

        self.assertEqual(completed.returncode, 2)
        self.assertEqual(completed.stdout, "")
        self.assertEqual(completed.stderr, "invalid GitHub API retry bounds\n")

    def test_primary_limit_is_not_polled_before_a_distant_reset(self) -> None:
        completed = self._run(
            """
            if [[ "$*" == *"rate_limit"* ]]; then
              printf '9999999999\n'
              exit 0
            fi
            printf 'gh: API rate limit exceeded for installation (HTTP 403)\n' >&2
            exit 1
            """,
            environment_overrides={"GITHUB_API_RETRY_MAX_WAIT_SECONDS": "1"},
        )

        self.assertEqual(completed.returncode, 1)
        self.assertEqual(completed.stdout, "")
        self.assertIn("beyond this caller maximum", completed.stderr)
        self.assertIn("API rate limit exceeded", completed.stderr)

    def test_generic_cli_wrapper_retries_the_label_command(self) -> None:
        completed = self._run(
            """
            if [[ "$*" == *"rate_limit"* ]]; then
              printf '1\n'
              exit 0
            fi
            attempts=0
            [[ -f "$RETRY_TEST_STATE" ]] && attempts="$(<"$RETRY_TEST_STATE")"
            attempts=$((attempts + 1))
            printf '%s' "$attempts" > "$RETRY_TEST_STATE"
            if (( attempts == 1 )); then
              printf 'gh: secondary rate limit (HTTP 403)\n' >&2
              exit 1
            fi
            printf 'label-ready\n'
            """,
            invocation="github_cli_retry gh label create automated-version-sync",
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "label-ready\n")

    def test_binary_download_retries_without_publishing_failed_bytes(self) -> None:
        completed = self._run(
            """
            if [[ "$*" == *"rate_limit"* ]]; then
              printf '1\n'
              exit 0
            fi
            attempts=0
            [[ -f "$RETRY_TEST_STATE" ]] && attempts="$(<"$RETRY_TEST_STATE")"
            attempts=$((attempts + 1))
            printf '%s' "$attempts" > "$RETRY_TEST_STATE"
            if (( attempts == 1 )); then
              printf 'incomplete-bytes'
              printf 'gh: API rate limit exceeded for installation (HTTP 403)\n' >&2
              exit 1
            fi
            printf 'complete-binary-payload'
            """,
            invocation=(
                'destination="${RETRY_TEST_STATE}.zip"; '
                'github_api_retry_to_file "$destination" actions/artifacts/1/zip; '
                'printf "download=%s\\n" "$(<"$destination")"'
            ),
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout, "download=complete-binary-payload\n")
        self.assertIn("waiting 1s for its declared reset", completed.stderr)


if __name__ == "__main__":
    unittest.main()
