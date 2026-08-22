#!/usr/bin/env python3
"""Generate the typed Java view of the canonical packaged-E2E contract."""

from __future__ import annotations

import argparse
import os
import tempfile
from pathlib import Path
from typing import Sequence

from scenario_contract import (
    DEFAULT_CONTRACT,
    ScenarioContract,
    ScenarioContractError,
    java_scenario_enum_name,
    load_contract,
)


def _java_string(value: str) -> str:
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )
    return f'"{escaped}"'


def render_java(contract: ScenarioContract) -> str:
    """Render the existing Java contract API from a fully validated contract."""

    lines = [
        "package com.quickskin.mod.e2e.generated;",
        "",
        "import java.util.EnumMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "import java.util.Set;",
        "",
        "/** Generated from e2e/scenario-contract.json; never edit generated output. */",
        "public final class ScenarioContract {",
        f"    public static final String SHA256 = {_java_string(contract.sha256)};",
        f"    public static final int SCREENSHOT_WIDTH = {contract.screenshot_size[0]};",
        f"    public static final int SCREENSHOT_HEIGHT = {contract.screenshot_size[1]};",
        "",
        "    public enum ScenarioId {",
    ]
    for index, scenario in enumerate(contract.scenarios):
        delimiter = ";" if index == len(contract.scenarios) - 1 else ","
        lines.append(
            "        "
            f"{java_scenario_enum_name(scenario.scenario)}"
            f"({_java_string(scenario.scenario)}){delimiter}"
        )
    lines.extend(
        [
            "        private final String externalId;",
            "        ScenarioId(String externalId) { this.externalId = externalId; }",
            "        public String externalId() { return externalId; }",
            "        public static ScenarioId fromExternal(String value) {",
            "            for (ScenarioId id : values()) "
            "if (id.externalId.equals(value)) return id;",
            '            throw new IllegalArgumentException("unknown E2E scenario: " + value);',
            "        }",
            "    }",
            "",
            "    public record StepSpec(String id, boolean assertionRequired, "
            "boolean captureRequired) {}",
            "    public record RoleSpec(List<StepSpec> steps) {",
            "        public RoleSpec { steps = List.copyOf(steps); }",
            "    }",
            "",
            "    private static final Map<ScenarioId, Map<String, RoleSpec>> ROLES;",
            "    static {",
            "        EnumMap<ScenarioId, Map<String, RoleSpec>> scenarios = "
            "new EnumMap<>(ScenarioId.class);",
        ]
    )
    for scenario in contract.scenarios:
        enum_name = java_scenario_enum_name(scenario.scenario)
        lines.append(
            f"        scenarios.put(ScenarioId.{enum_name}, Map.ofEntries("
        )
        for role_index, role in enumerate(scenario.roles):
            lines.append(
                f"            Map.entry({_java_string(role.role)}, new RoleSpec(List.of("
            )
            for step_index, step in enumerate(role.steps):
                delimiter = "" if step_index == len(role.steps) - 1 else ","
                assertion_required = str(step.assertion_required).lower()
                capture_required = str(step.capture is not None).lower()
                lines.append(
                    "                new StepSpec("
                    f"{_java_string(step.id)}, {assertion_required}, {capture_required})"
                    f"{delimiter}"
                )
            role_delimiter = "" if role_index == len(scenario.roles) - 1 else ","
            lines.append(f"            ))){role_delimiter}")
        lines.append("        ));")
    lines.extend(
        [
            "        ROLES = Map.copyOf(scenarios);",
            "    }",
            "",
            "    public static RoleSpec role(ScenarioId scenario, String role) {",
            "        RoleSpec spec = ROLES.getOrDefault(scenario, Map.of()).get(role);",
            "        if (spec == null) throw new IllegalArgumentException(",
            '                "unknown E2E scenario role: " + scenario.externalId() + "/" + role);',
            "        return spec;",
            "    }",
            "    public static Set<String> roles(ScenarioId scenario) {",
            "        return ROLES.getOrDefault(scenario, Map.of()).keySet();",
            "    }",
            "    private ScenarioContract() {}",
            "}",
        ]
    )
    return "\n".join(lines) + "\n"


def _atomic_write(output: Path, content: bytes) -> bool:
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    if not output.is_symlink() and output.is_file():
        try:
            if output.read_bytes() == content:
                return False
        except OSError:
            pass

    descriptor, temporary_name = tempfile.mkstemp(
        dir=output.parent,
        prefix=f".{output.name}.",
        suffix=".tmp",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, output)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
    return True


def generate_java(contract_path: Path, output_path: Path) -> bool:
    """Validate *contract_path* with the canonical parser, then atomically emit Java."""

    contract = load_contract(contract_path)
    return _atomic_write(output_path, render_java(contract).encode("utf-8"))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    parser.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    try:
        generate_java(args.contract, args.output)
    except (OSError, ScenarioContractError) as exc:
        parser.exit(2, f"scenario contract Java generation failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
