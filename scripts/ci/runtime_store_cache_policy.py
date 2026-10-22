#!/usr/bin/env python3
"""Decide whether a packaged-runtime job may write the installed-server cache.

The installed server tree is content-addressed and revalidated on every read, so a corrupt or
substituted entry degrades to a cache miss and a fresh install. It is still executable material
that a later job launches, and an Actions cache written from a ``master`` context is restored by
every later run of the repository. Only a job that builds and runs exclusively protected-``master``
code may therefore write: the job must declare itself a writer (``--writer-job true``, passed only
by jobs whose runtime bundle was built from protected ``master`` in the same trusted context), and
the run must be a manual or Build-gate dispatch on protected ``master``. Every other context
restores read-only. That includes a ``repository_dispatch`` on ``master``: the optional-mod wave
launches the JARs that the original ``pull_request`` run built when its merge reused that run, so
code not fully reviewed could otherwise write a ``master`` cache entry. Pull requests, tags,
ephemeral branches and unknown input are read-only.

The policy is fail-closed. Anything that is not an exact approved writer prints ``true``
(read-only), and malformed required input exits non-zero instead of guessing.
"""

from __future__ import annotations

import argparse
import sys
from typing import Sequence

# A protected `master` generation reaches packaged runtime from its own Build through an explicit
# dispatch: the Build gate dispatches the post-merge suite, and a maintainer may dispatch it or a
# release rehearsal. A `push` never starts packaged runtime, and a `repository_dispatch` (the
# optional-mod wave) may run JARs a pull request built, so neither grants write capability.
WRITER_EVENTS = frozenset({"workflow_dispatch"})
WRITER_BRANCH = "master"
READ_ONLY_REF_PREFIXES = (
    "automation/",
    "codex/",
    "dependabot/",
    "refs/pull/",
    "refs/tags/",
)


class PolicyError(ValueError):
    """Raised when required policy input cannot be trusted."""


def _require_non_empty(value: str, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PolicyError(f"{label} must be a non-empty string")
    return value


def is_read_only(
    *,
    event_name: str,
    ref_name: str,
    ref_type: str,
    ref_protected: bool,
    writer_job: bool,
) -> bool:
    """Return ``True`` unless the exact input is an approved cache writer."""
    _require_non_empty(event_name, "event name")
    _require_non_empty(ref_name, "ref name")
    _require_non_empty(ref_type, "ref type")
    if not isinstance(ref_protected, bool):
        raise PolicyError("ref protected must be a boolean")
    if not isinstance(writer_job, bool):
        raise PolicyError("writer job must be a boolean")

    if not writer_job or event_name not in WRITER_EVENTS or ref_type != "branch" or not ref_protected:
        return True
    if ref_name.startswith(READ_ONLY_REF_PREFIXES):
        return True
    return ref_name != WRITER_BRANCH


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-name", required=True)
    parser.add_argument("--ref-name", required=True)
    parser.add_argument("--ref-type", required=True)
    parser.add_argument("--ref-protected", choices=("true", "false"), required=True)
    parser.add_argument("--writer-job", choices=("true", "false"), required=True)
    args = parser.parse_args(argv)

    try:
        read_only = is_read_only(
            event_name=args.event_name,
            ref_name=args.ref_name,
            ref_type=args.ref_type,
            ref_protected=args.ref_protected == "true",
            writer_job=args.writer_job == "true",
        )
    except PolicyError as exc:
        print(f"Runtime store cache policy error: {exc}", file=sys.stderr)
        return 2

    print("true" if read_only else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
