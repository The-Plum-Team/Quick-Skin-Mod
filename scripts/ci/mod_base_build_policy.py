"""Quick Skin's ``policy`` hook of the shared mod-base Build: the three native policy jobs in one run.

The legacy ``build-gate.yml`` runs three parallel policy jobs. This hook runs the same checks in the
kit's disposable candidate account, from the tested checkout, without a token:

* "Validate repository policy": the central matrix, the README profile and the imported workflow
  guidance for the profile branch, the managed mod-base files (``template check``) and the Python
  syntax of ``e2e`` and ``scripts``. Its network pin verification (``mod_base_kit.py verify
  --network``) needs a token the account never has; the managed callers' guard job authenticates
  the pin before any kit code runs, and ``template check`` still pins every managed byte.
* "Validate release policy" and "Validate CI policy": the two unittest suites, each through the
  pinned kit's own runner (``out/mod-base-kit/tools/parallel_unittest.py --policy-profile
  quick-skin``), which fails closed on a discovery error, a failure, an unexpected success, a dead
  worker, zero tests or a unit that ran another number of tests than it discovered. The two
  suites run at the same time, as the native jobs do, each with a temporary directory of its own.

The profile branch is the canonical branch: the shared callers only test pull requests to it and
pushes to it, which is what the native job derives for those events. Bytecode goes to
``PYTHONPYCACHEPREFIX`` (below ``TMPDIR``), never into the checkout.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

import mod_base_build_adapter as adapter

KIT_OVERLAY = Path("out") / "mod-base-kit"
SUITES = ("scripts/release/tests", "scripts/ci/tests")


class PolicyError(adapter.AdapterError):
    """A policy check failed."""


def _log(message: str) -> None:
    print(f"quick-skin policy: {message}", flush=True)


def _check(command: list[str], *, cwd: Path, env: dict[str, str], label: str, quiet: bool = False) -> None:
    started = time.monotonic()
    result = subprocess.run(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL if quiet else None, check=False)
    _log(f"{label}: exit {result.returncode} after {time.monotonic() - started:.0f}s")
    if result.returncode != 0:
        raise PolicyError(f"{label} failed with exit status {result.returncode}")


def repository_checks(checkout: Path, env: dict[str, str], profile_branch: str) -> None:
    """The native "Validate repository policy" job, apart from the token-bound network check."""

    python = sys.executable
    _check([python, "scripts/release/matrix.py", "--matrix", adapter.INVENTORY_PATH], cwd=checkout, env=env,
           label="central matrix", quiet=True)
    _check([python, "scripts/release/branch_readme.py", "--matrix", adapter.INVENTORY_PATH, "--readme", "README.md",
            "--profile-branch", profile_branch, "--check"], cwd=checkout, env=env, label="README profile")
    _check([python, "scripts/release/workflow_guidance.py", "--matrix", adapter.INVENTORY_PATH, "--guidance",
            "docs/ai/WORKFLOW.md", "--profile-branch", profile_branch, "--check"], cwd=checkout, env=env,
           label="workflow guidance")
    _check([python, "scripts/ci/mod_base_kit.py", "run", "template", "check", "--repo", "."], cwd=checkout, env=env,
           label="managed mod-base files")
    _check([python, "-m", "compileall", "-q", "e2e", "scripts"], cwd=checkout, env=env, label="Python syntax")


def run_suites(checkout: Path, env: dict[str, str]) -> None:
    """Both policy suites through the pinned kit's fail-closed runner, concurrently."""

    kit = checkout / KIT_OVERLAY
    runner = kit / "tools" / "parallel_unittest.py"
    if not runner.is_file():
        raise PolicyError(f"the staged kit has no {runner.relative_to(checkout).as_posix()}")
    temporary = Path(env["TMPDIR"])
    processes = []
    for suite in SUITES:
        scratch = temporary / ("policy-" + suite.replace("/", "-"))
        scratch.mkdir(mode=0o700)
        suite_env = dict(env, TMPDIR=str(scratch), PYTHONPATH=str(kit / "src"))
        _log(f"start {suite}")
        processes.append((suite, time.monotonic(), subprocess.Popen(
            [sys.executable, str(runner), "--policy-profile", "quick-skin", "-p", "test_*.py", suite],
            cwd=checkout, env=suite_env, stdin=subprocess.DEVNULL)))
    failures = []
    for suite, started, process in processes:
        returncode = process.wait()
        _log(f"{suite}: exit {returncode} after {time.monotonic() - started:.0f}s")
        if returncode != 0:
            failures.append(suite)
    if failures:
        raise PolicyError(f"policy suites failed: {', '.join(failures)}")


def run(checkout: Path) -> None:
    checkout = checkout.resolve()
    # The native scripts import their siblings from their own directory, which PYTHONSAFEPATH
    # (set for the dispatcher's own interpreter) would take off sys.path.
    env = {name: value for name, value in os.environ.items() if name != "PYTHONSAFEPATH"}
    config = adapter.decode(adapter.read_regular(checkout / "scripts/ci/mod-base-build.json", "Build config",
                                                 max_bytes=1 << 20), "Build config")
    extra = {entry["name"]: entry["path"] for entry in config["plan_inputs"]}
    mod = adapter.load_mod(checkout / config["inventory"]["path"], checkout / config["scenario_contract"]["path"],
                           checkout / extra[adapter.PROPERTIES_INPUT])
    adapter.derive_plan(mod)
    started = time.monotonic()
    repository_checks(checkout, env, mod.project["release_branch"])
    run_suites(checkout, env)
    _log(f"every check passed in {time.monotonic() - started:.0f}s")
