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
    def _run(self, fake_gh: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            gh = temp / "gh"
            gh.write_text("#!/usr/bin/env bash\n" + textwrap.dedent(fake_gh), encoding="utf-8")
            gh.chmod(0o755)
            environment = {
                **os.environ,
                "PATH": f"{temp}{os.pathsep}{os.environ.get('PATH', '')}",
                "RETRY_TEST_STATE": str(temp / "attempts"),
            }
            script = f"""
                sleep() {{ :; }}
                source {subprocess.list2cmdline([str(HELPER)])}
                github_api_retry actions/artifacts --jq .name
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
        self.assertIn("failed transiently", completed.stderr)

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


if __name__ == "__main__":
    unittest.main()
