#!/usr/bin/env python3
"""Freeze an offline infrastructure-repair plan; never authorize CI evidence or dispatch work."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import unittest
from datetime import datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/ci"))
import e2e_selection as selection
import mod_compatibility_impact as compatibility

MARKER = "e2e/full-validation-baseline.json"
FOCUSED_TESTS = (
    "test_visual_review_guard.VisualReviewGuardTest."
    "test_both_checkouts_leave_the_exact_reviewers_requirements_available",
    "test_pages_runtime_fanin.PagesRuntimeFaninTest."
    "test_distinct_runtime_generations_do_not_share_admission_by_sha_alone",
    "test_repair_preflight.RepairRegressionMutationTest",
)
WORK_KINDS = ("focused_checks", "full_pr_matrices", "post_merge_reused_work", "fresh_recovery_waves")
MAX_BYTES = 2 * 1024 * 1024
IMPLEMENTATION_PATHS = (
    "scripts/ci/repair_preflight.py", "scripts/ci/mod_compatibility_impact.py",
    "scripts/ci/tests/test_repair_preflight.py", "scripts/ci/tests/test_visual_review_guard.py",
    "scripts/ci/tests/test_pages_runtime_fanin.py", "scripts/ci/tests/test_ci_reuse.py",
    "scripts/ci/ci_reuse.py", "scripts/ci/feature_pages.py", "scripts/ci/feature_coverage_github.py",
    ".github/workflows/visual-review-drain.yml",
)


def digest(value: Any) -> str:
    return hashlib.sha256(selection.canonical(value)).hexdigest()


def implementation_fingerprint() -> dict[str, str]:
    result = {}
    for name in IMPLEMENTATION_PATHS:
        path = ROOT / name
        if path.is_symlink() or path.resolve() != path:
            raise ValueError("preflight implementation must use regular contained files")
        with path.open("rb") as stream:
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError("preflight implementation exceeds its byte limit")
        result[name] = hashlib.sha256(raw).hexdigest()
    return result


def read_json(path: Path) -> dict[str, Any]:
    with path.open("rb") as stream:
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("preflight input exceeds its byte limit")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("preflight input contains duplicate keys")
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=unique,
                       parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))
    if not isinstance(value, dict):
        raise ValueError("preflight input must be an object")
    return value


def evidence_observation(value: dict[str, Any] | None, head: str) -> dict[str, Any]:
    """Keep operator observations separate from the canonical live evidence verifier."""
    if value is None:
        return {"status": "unavailable", "coverage_sha": head, "identities": [], "checked_at": None}
    if set(value) != {"status", "coverage_sha", "identities", "checked_at"}:
        raise ValueError("optional evidence observation has an unknown schema")
    if value["status"] not in {"available", "unavailable", "read_error"} or value["coverage_sha"] != head:
        raise ValueError("optional evidence observation has a foreign head or status")
    if (not isinstance(value["checked_at"], str) or len(value["checked_at"]) > 40
            or not isinstance(value["identities"], list) or len(value["identities"]) > 256):
        raise ValueError("optional evidence observation is not bounded")
    observed = datetime.fromisoformat(value["checked_at"].replace("Z", "+00:00"))
    if observed.tzinfo is None:
        raise ValueError("optional evidence observation needs an explicit timezone")
    seen = set()
    for item in value["identities"]:
        if (not isinstance(item, dict)
                or set(item) != {"repository", "source_sha", "run_id", "attempt", "artifact_id", "digest"}
                or any(type(item[key]) is not int or item[key] <= 0
                       for key in ("run_id", "attempt", "artifact_id"))
                or not isinstance(item["source_sha"], str) or not selection.SHA.fullmatch(item["source_sha"])
                or not isinstance(item["repository"], str)
                or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", item["repository"])
                or not isinstance(item["digest"], str)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", item["digest"])):
            raise ValueError("optional evidence identity is incomplete")
        identity = (item["repository"], item["artifact_id"])
        if identity in seen:
            raise ValueError("optional evidence repeats an immutable artifact identity")
        seen.add(identity)
    if value["status"] == "available" and not seen:
        raise ValueError("available optional evidence needs exact reusable identities")
    return value


def make_plan(repository: Path, *, base: str, head: str, policy: str,
              intent: str, scope: list[str], evidence: dict[str, Any] | None = None,
              previous: dict[str, Any] | None = None) -> dict[str, Any]:
    if intent not in {"repair", "complete-recovery"}:
        raise ValueError("unknown requested end state")
    if not scope or len(scope) > 32 or any(not isinstance(item, str) or not item.strip()
            or len(item) > 500 or any(ord(char) < 32 for char in item) for item in scope):
        raise ValueError("record a bounded, explicit implementation scope")
    admission = selection.admit(repository, base=base, head=head, policy=policy)
    if not admission.diff_complete:
        raise ValueError("cannot freeze an unproven base/head diff")
    raw = selection._git(repository, "diff", "--name-only", "--no-renames", "--no-ext-diff",
                         "--no-textconv", "-z", base, head, "--")
    paths = [compatibility.normalize_path(path.decode("utf-8")) for path in raw.split(b"\0") if path]
    if not paths:
        raise ValueError("repair plan requires a non-empty diff")
    impact = compatibility.classify_paths(paths).manifest()
    observation = evidence_observation(evidence, head)
    missing = []
    actions = []
    if observation["status"] == "read_error":
        missing.append("optional-evidence-read-error")
        actions.append("Recover the evidence read; API failure cannot establish absence or reuse.")
    if intent == "complete-recovery" and observation["status"] != "available":
        missing.append("complete-optional-coverage")
        if not impact["compatibility_required"]:
            actions.append("Include an explicitly authorized refresh of " + MARKER
                           + " in this repair, then regenerate the plan before the full gate.")
        else:
            actions.append("The canonical diff requests a fresh optional wave after ordinary acceptance.")
    if observation["status"] == "available":
        actions.append("Reauthenticate the recorded optional evidence with the canonical carry-forward"
                       + " verifier, including current public availability, before relying on reuse.")
    plan = {
        "schema_version": 1, "kind": "infrastructure-repair-plan", "intent": intent,
        "scope": scope, "base": base, "head": head, "tree": admission.head_tree,
        "base_tree": admission.base_tree, "policy": policy, "paths": paths,
        "implementation_sha256": implementation_fingerprint(),
        "selection": admission.to_dict(), "compatibility": impact,
        "optional_evidence_observation": observation,
        "missing_coverage": missing, "required_actions": actions,
        "optional_followups": [],
        "required_gates": ["Build", "Packaged E2E", "protected post-merge acceptance for the classified scope"],
        "live_reauthentication_required": True,
        "focused_tests": list(FOCUSED_TESTS),
        "work_counts": dict.fromkeys(WORK_KINDS, 0),
        "invalidated_proof": [],
    }
    if previous is not None:
        verify_seal(previous)
        changed = [key for key in ("base", "head", "tree", "policy", "scope", "paths",
                   "selection", "compatibility", "intent", "optional_evidence_observation",
                   "implementation_sha256")
                   if previous[key] != plan[key]]
        if changed:
            plan["invalidated_proof"] = [{"plan_sha256": previous["plan_sha256"],
                "head": previous["head"], "tree": previous["tree"], "changed_fields": changed,
                "reason": "Previous proof remains bound to its recorded tree and acceptance scope;"
                          + " reauthenticate permitted reuse or run new gates for this plan."}]
    plan["plan_sha256"] = digest(plan)
    return plan


def verify_seal(plan: dict[str, Any]) -> None:
    if (plan.get("schema_version") != 1 or plan.get("kind") != "infrastructure-repair-plan"
            or plan.get("plan_sha256") != digest({key: value for key, value in plan.items()
                                                 if key != "plan_sha256"})):
        raise ValueError("repair plan seal is missing or changed")


def check_frozen(plan: dict[str, Any], repository: Path) -> None:
    verify_seal(plan)
    if selection._git(repository, "rev-parse", "HEAD").decode().strip() != plan["head"]:
        raise ValueError("HEAD changed; regenerate the acceptance plan and record invalidated proof")
    if selection._git(repository, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("worktree changed; an exact-head gate cannot validate uncommitted scope")
    current = make_plan(repository, base=plan["base"], head=plan["head"], policy=plan["policy"],
                        intent=plan["intent"], scope=plan["scope"],
                        evidence=plan["optional_evidence_observation"]
                        if plan["optional_evidence_observation"]["checked_at"] else None)
    for key in ("tree", "paths", "selection", "compatibility", "missing_coverage", "required_actions",
                "implementation_sha256"):
        if current[key] != plan[key]:
            raise ValueError("acceptance plan no longer matches the executing policy: " + key)
    if "optional-evidence-read-error" in plan["missing_coverage"]:
        raise ValueError("recover the evidence read before freezing full acceptance")
    if (plan["intent"] == "complete-recovery" and "complete-optional-coverage" in plan["missing_coverage"]
            and not plan["compatibility"]["compatibility_required"]):
        raise ValueError("complete recovery needs its authorized marker action before the gate")


def focused_checks() -> dict[str, Any]:
    """Run real Git and ZIP regressions locally, including seeded historical failures."""
    sys.path.insert(0, str(ROOT))
    sys.path.insert(0, str(ROOT / "scripts/ci/tests"))
    suite = unittest.defaultTestLoader.loadTestsFromNames(FOCUSED_TESTS)
    result = unittest.TextTestRunner(stream=io.StringIO(), verbosity=0).run(suite)
    # Report identifiers and counts only; test exception output can contain arbitrary fixture data.
    return {"successful": result.wasSuccessful(), "tests_run": result.testsRun,
            "failures": [test.id() for test, _ in result.failures],
            "errors": [test.id() for test, _ in result.errors],
            "work_counts": {**dict.fromkeys(WORK_KINDS, 0), "focused_checks": result.testsRun}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("plan")
    create.add_argument("--repository", type=Path, default=ROOT)
    for name in ("base", "head", "policy"):
        create.add_argument("--" + name, required=True)
    create.add_argument("--intent", choices=("repair", "complete-recovery"), required=True)
    create.add_argument("--scope", action="append", required=True)
    create.add_argument("--optional-evidence", type=Path)
    create.add_argument("--previous", type=Path)
    verify = commands.add_parser("check")
    verify.add_argument("--repository", type=Path, default=ROOT)
    verify.add_argument("--plan", type=Path, required=True)
    commands.add_parser("focused")
    args = parser.parse_args()
    try:
        if args.command == "focused":
            result = focused_checks()
            print(json.dumps(result, sort_keys=True, indent=2))
            return 0 if result["successful"] else 1
        if args.command == "check":
            plan = read_json(args.plan)
            check_frozen(plan, args.repository)
            result = focused_checks()
            check_frozen(plan, args.repository)
            result.update(plan_consistent=True, live_reauthentication_required=True,
                          plan_sha256=plan["plan_sha256"], head=plan["head"], tree=plan["tree"],
                          required_actions=plan["required_actions"], required_gates=plan["required_gates"])
            print(json.dumps(result, sort_keys=True, indent=2))
            return 0 if result["successful"] else 1
        plan = make_plan(args.repository, base=args.base, head=args.head, policy=args.policy,
                         intent=args.intent, scope=args.scope,
                         evidence=read_json(args.optional_evidence) if args.optional_evidence else None,
                         previous=read_json(args.previous) if args.previous else None)
        print(json.dumps(plan, sort_keys=True, indent=2))
        return 0
    except (ValueError, OSError, KeyError, TypeError):
        print("Repair preflight failed: invalid or changed plan, Git identity, policy, or evidence observation."
              + " Regenerate the plan and resolve its required actions before the matrix.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
