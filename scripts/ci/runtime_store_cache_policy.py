#!/usr/bin/env python3
"""Decide whether a packaged-runtime job may write the installed-server cache.

The installed server tree is content-addressed and revalidated on every read, so a corrupt or
substituted entry degrades to a cache miss and a fresh install. It is still executable material
that a later job launches, so writing it is restricted the way Gradle's home cache already is:
only a protected ``master`` generation may publish an entry, and every other context restores
read-only. Pull requests, tags, ephemeral branches and unknown input are read-only.

The policy is fail-closed. Anything that is not an exact approved writer prints ``true``
(read-only), and malformed required input exits non-zero instead of guessing.
"""

from __future__ import annotations

import argparse
import sys
from typing import Sequence

# A protected `master` generation reaches packaged runtime through an explicit dispatch: the
# Build gate dispatches the post-merge suite, and the compatibility producer is repository
# dispatched. A `push` never starts packaged runtime, so it grants no write capability.
WRITER_EVENTS = frozenset({"workflow_dispatch", "repository_dispatch"})
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
) -> bool:
    """Return ``True`` unless the exact input is an approved cache writer."""
    _require_non_empty(event_name, "event name")
    _require_non_empty(ref_name, "ref name")
    _require_non_empty(ref_type, "ref type")
    if not isinstance(ref_protected, bool):
        raise PolicyError("ref protected must be a boolean")

    if event_name not in WRITER_EVENTS or ref_type != "branch" or not ref_protected:
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
    args = parser.parse_args(argv)

    try:
        read_only = is_read_only(
            event_name=args.event_name,
            ref_name=args.ref_name,
            ref_type=args.ref_type,
            ref_protected=args.ref_protected == "true",
        )
    except PolicyError as exc:
        print(f"Runtime store cache policy error: {exc}", file=sys.stderr)
        return 2

    print("true" if read_only else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
