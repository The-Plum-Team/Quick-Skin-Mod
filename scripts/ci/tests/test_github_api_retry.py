from __future__ import annotations

import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


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
            environment = {
                **os.environ,
                "PATH": f"{temp}{os.pathsep}{os.environ.get('PATH', '')}",
                "RETRY_TEST_STATE": str(temp / "attempts"),
                **(environment_overrides or {}),
            }
            script = f"""
                sleep() {{ :; }}
                source {subprocess.list2cmdline([str(HELPER)])}
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
