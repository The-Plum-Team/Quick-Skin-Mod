#!/usr/bin/env python3
"""Derive E2E execution and capture obligations from declared module impact.

The path-based CLI is a local preview. It does not authenticate a Git diff or authorize
release/AI coverage; protected CI must supply and authenticate that input separately.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

from scenario_contract import ScenarioContract, default_contract

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY / "scripts" / "architecture"))
from module_graph import ModuleGraph, load_graph, repository_path, unique_object  # noqa: E402

MAX_SELECTION_BYTES = 1024 * 1024
MAX_CHANGED_PATHS = 10000
SOURCE_SUFFIX = re.compile(r"^src/(main|legacy[A-Za-z0-9_]+)/java/.+\.java$")


class SelectionError(ValueError):
    """Selection cannot establish its exact execution/capture obligations."""


@dataclass(frozen=True)
class RoleSelection:
    role: str
    targets: tuple[str, ...]
    steps: tuple[str, ...]
    captures: tuple[str, ...]


@dataclass(frozen=True)
class ScenarioSelection:
    scenario: str
    roles: tuple[RoleSelection, ...]


@dataclass(frozen=True)
class Selection:
    contract_sha256: str
    module_graph_sha256: str
    profile: str
    mode: str
    reason: str
    changed_paths: tuple[str, ...]
    direct_modules: tuple[str, ...]
    affected_modules: tuple[str, ...]
    affected_bindings: tuple[str, ...]
    runs: tuple[ScenarioSelection, ...]
    reference_captures: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {"schema_version": 1, "input_kind": "local-path-preview", **asdict(self)}

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.to_bytes()).hexdigest()

    def to_bytes(self) -> bytes:
        return (json.dumps(self.to_dict(), sort_keys=True, separators=(",", ":")) + "\n").encode()

    def role(self, scenario: str, role: str) -> RoleSelection:
        for run in self.runs:
            if run.scenario == scenario:
                for item in run.roles:
                    if item.role == role:
                        return item
        raise SelectionError(f"role is not selected: {scenario}/{role}")


def validate_coverage(contract: ScenarioContract, graph: ModuleGraph) -> None:
    bindings = {item.id for item in graph.bindings}
    for scenario in contract.scenarios:
        for role in scenario.roles:
            for step in role.steps:
                if set(step.modules) - graph.by_id.keys() or set(step.bindings) - bindings:
                    raise SelectionError(f"unknown module/binding coverage at {scenario.scenario}/{role.role}/{step.id}")


def select(contract: ScenarioContract, graph: ModuleGraph, paths: Iterable[str],
           profile: str = "pr") -> Selection:
    """Compute a deterministic local preview; unknown impact expands to the full profile."""
    validate_coverage(contract, graph)
    values = list(paths)
    if len(values) > MAX_CHANGED_PATHS:
        raise SelectionError("changed path input exceeds its limit")
    changed = tuple(sorted({repository_path(path) for path in values}))
    scenarios = [contract.scenario(item) for item in contract.scenarios_for_profile(profile)]
    if not scenarios:
        raise SelectionError(f"execution profile has no scenarios: {profile}")
    direct: set[str] = set()
    unknown: list[str] = []
    for path in changed:
        owners = [module for module in graph.modules if path.startswith(module.path + "/")]
        if len(owners) != 1:
            unknown.append(path)
            continue
        owner = owners[0]
        relative = path[len(owner.path) + 1:]
        if owner.kind == "minecraft-assembly" or not SOURCE_SUFFIX.fullmatch(relative):
            unknown.append(path)
        else:
            direct.add(owner.id)
    affected = graph.affected_modules(path for path in changed if path not in unknown) or ()
    # An assembly packages its dependencies; merely being rebuilt does not modify all its wiring.
    feature_impact = {item for item in affected if graph.by_id[item].kind != "minecraft-assembly"}
    bindings = graph.affected_bindings(affected)
    covered_modules = {module for scenario in scenarios for role in scenario.roles
                       for step in role.steps for module in step.modules}
    covered_bindings = {binding for scenario in scenarios for role in scenario.roles
                        for step in role.steps for binding in step.bindings}
    missing = sorted(feature_impact - covered_modules)
    missing_bindings = sorted(set(bindings) - covered_bindings)
    reason = "affected-module-coverage"
    if not changed:
        reason = "missing-change-input"
    elif unknown:
        reason = "unknown-path-or-policy-change"
    elif missing or missing_bindings:
        reason = "incomplete-module-or-binding-coverage"
    full = reason != "affected-module-coverage"
    runs: list[ScenarioSelection] = []
    references: set[str] = set()
    for scenario in scenarios:
        targets = {role.role: {step.id for step in role.steps if full
                              or feature_impact.intersection(step.modules)
                              or set(bindings).intersection(step.bindings)}
                   for role in scenario.roles}
        if not any(targets.values()):
            continue
        role_selections: list[RoleSelection] = []
        for role in scenario.roles:
            capture_steps = {step.id for step in role.steps if step.capture is not None}
            by_id = {step.id: step for step in role.steps}
            execute = set(role.step_ids) if full or scenario.execution_scope == "scenario" else set(targets[role.role])
            captures = capture_steps & targets[role.role]
            # Setup actions and screenshot consumers are separate edges. Executing an action
            # does not imply capturing it; comparison partners do require both real images.
            while True:
                before = (set(execute), set(captures))
                for step_id in tuple(execute):
                    execute.update(by_id[step_id].requires)
                    captures.update(by_id[step_id].requires_captures)
                for comparison in role.comparisons:
                    pair = {comparison.first_step, comparison.second_step}
                    if pair & captures:
                        captures.update(pair)
                execute.update(captures)
                if before == (execute, captures):
                    break
            if not execute:
                raise SelectionError("selected scenario omitted a coordinated client")
            for step_id in captures:
                capture = by_id[step_id].capture
                if capture and capture.compatibility_reference_capture_id:
                    references.add(capture.compatibility_reference_capture_id)
            role_selections.append(RoleSelection(
                role.role,
                tuple(step.id for step in role.steps if step.id in targets[role.role]),
                tuple(step.id for step in role.steps if step.id in execute),
                tuple(step.id for step in role.steps if step.id in captures),
            ))
        runs.append(ScenarioSelection(scenario.scenario, tuple(role_selections)))
    if not runs:
        raise SelectionError("impact produced no scenarios; coverage cannot authorize an empty run")
    return Selection(contract.sha256, graph.sha256, profile, "full" if full else "affected", reason,
                     changed, tuple(sorted(direct)), tuple(affected), tuple(bindings), tuple(runs),
                     tuple(sorted(references)))


def load_selection(path: Path, contract: ScenarioContract | None = None,
                   graph: ModuleGraph | None = None) -> Selection:
    """Recompute every obligation; never trust step/capture lists supplied by the caller."""
    with path.open("rb") as stream:
        raw = stream.read(MAX_SELECTION_BYTES + 1)
    if len(raw) > MAX_SELECTION_BYTES:
        raise SelectionError("selection exceeds its byte limit")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise SelectionError("selection must be bounded UTF-8 JSON") from exc
    if not isinstance(data, dict) or not isinstance(data.get("changed_paths"), list):
        raise SelectionError("selection must declare its changed paths")
    if not isinstance(data.get("profile"), str):
        raise SelectionError("selection must declare its execution profile")
    expected = select(contract or default_contract(), graph or load_graph(),
                      data["changed_paths"], data["profile"])
    normalized = json.loads(expected.to_bytes())
    if data != normalized or type(data.get("schema_version")) is not int:
        raise SelectionError("selection differs from the current graph/contract and recomputed obligations")
    return expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--changed-path", action="append", default=[])
    parser.add_argument("--profile", default="pr")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = select(default_contract(), load_graph(), args.changed_path, args.profile)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(result.to_bytes())
        print(json.dumps({"mode": result.mode, "reason": result.reason, "sha256": result.sha256,
                          "scenarios": [run.scenario for run in result.runs],
                          "captures": sum(len(role.captures) for run in result.runs for role in run.roles)}))
    except (OSError, ValueError) as exc:
        parser.exit(2, f"E2E selection failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
