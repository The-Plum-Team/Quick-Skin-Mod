#!/usr/bin/env python3
"""Load and validate the canonical packaged-E2E scenario contract."""

from __future__ import annotations

import hashlib
import json
import math
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Any, NoReturn


DEFAULT_CONTRACT = Path(__file__).with_name("scenario-contract.json")
SCHEMA_VERSION = 2
REQUIRED_SCREENSHOT_SIZE = (1920, 1080)
MAX_CONTRACT_BYTES = 1024 * 1024
IDENTIFIER = re.compile(r"^[a-z][a-z0-9_-]*$")
REVIEW_TIERS = frozenset({"all", "key"})
ORCHESTRATION_MODES = frozenset(
    {"single-client", "sequential-two-client", "concurrent-two-client"}
)
EXECUTION_PROFILES = frozenset(
    {
        "runtime-default",
        "pr",
        "release",
        "compatibility",
        "compatibility-remote",
    }
)
REQUIRED_EXECUTION_PROFILES = frozenset({"runtime-default", "pr", "release"})


class ScenarioContractError(ValueError):
    """Raised when the scenario contract is malformed or internally inconsistent."""


@dataclass(frozen=True)
class StartAfter:
    role: str
    server_log_marker: str
    timeout_seconds: int


@dataclass(frozen=True)
class Orchestration:
    mode: str
    role_order: tuple[str, ...] = ()
    start_after: StartAfter | None = None

    @property
    def two_clients(self) -> bool:
        return self.mode != "single-client"

    @property
    def server_log_marker(self) -> str | None:
        return self.start_after.server_log_marker if self.start_after else None

    @property
    def server_log_timeout_seconds(self) -> int | None:
        return self.start_after.timeout_seconds if self.start_after else None


@dataclass(frozen=True)
class ScreenshotComparison:
    first_step: str
    second_step: str
    minimum_changed_fraction: float
    region: tuple[float, float, float, float] | None = None


@dataclass(frozen=True)
class OpaqueStarsProbe:
    step: str
    region: tuple[float, float, float, float]
    maximum_mean_luma: float
    bright_luma: int
    maximum_bright_fraction: float
    kind: str = "opaque-stars-background"


@dataclass(frozen=True)
class RequiredGuiTextProbe:
    step: str
    label: str
    box: tuple[int, int, int, int]
    minimum_luma_exclusive: int
    minimum_pixels: int
    kind: str = "required-gui-text"


VisualProbe = OpaqueStarsProbe | RequiredGuiTextProbe


@dataclass(frozen=True)
class RoleContract:
    role: str
    steps: tuple[StepContract, ...]
    comparisons: tuple[ScreenshotComparison, ...]

    @property
    def step_ids(self) -> tuple[str, ...]:
        return tuple(step.id for step in self.steps)

    @property
    def probes(self) -> tuple[VisualProbe, ...]:
        return tuple(
            probe
            for step in self.steps
            if step.capture is not None
            for probe in step.capture.probes
        )


@dataclass(frozen=True)
class Scenario:
    scenario: str
    execution_profiles: tuple[str, ...]
    orchestration: Orchestration
    roles: tuple[RoleContract, ...]


@dataclass(frozen=True)
class Capture:
    scenario: str
    role: str
    step: str
    title: str
    review_tier: str
    expectation: str
    probes: tuple[VisualProbe, ...]
    compatibility_reference_capture_id: str | None = None

    @property
    def capture_id(self) -> str:
        return capture_id(self.scenario, self.role, self.step)


@dataclass(frozen=True)
class StepContract:
    id: str
    assertion_required: bool
    capture: Capture | None = None


class ScenarioContract:
    """Indexed view of a validated scenario-contract document."""

    __slots__ = (
        "schema_version",
        "screenshot_size",
        "gui_text_reference_size",
        "review_regions",
        "scenarios",
        "captures",
        "sha256",
        "_scenario_by_id",
        "_scenarios_by_profile",
        "_role_by_key",
        "_capture_by_key",
        "_capture_by_id",
    )

    def __init__(
        self,
        *,
        schema_version: int,
        screenshot_size: tuple[int, int],
        gui_text_reference_size: tuple[int, int],
        review_regions: dict[str, tuple[tuple[float, float, float, float], ...]],
        scenarios: tuple[Scenario, ...],
        sha256: str,
    ) -> None:
        self.schema_version = schema_version
        self.screenshot_size = screenshot_size
        self.gui_text_reference_size = gui_text_reference_size
        self.review_regions = dict(review_regions)
        self.scenarios = scenarios
        self.captures = tuple(
            step.capture
            for scenario in scenarios
            for role in scenario.roles
            for step in role.steps
            if step.capture is not None
        )
        self.sha256 = sha256
        self._scenario_by_id = {item.scenario: item for item in scenarios}
        self._scenarios_by_profile = {
            profile: tuple(
                scenario.scenario
                for scenario in scenarios
                if profile in scenario.execution_profiles
            )
            for profile in EXECUTION_PROFILES
        }
        self._role_by_key = {
            (scenario.scenario, role.role): role
            for scenario in scenarios
            for role in scenario.roles
        }
        self._capture_by_key = {
            (capture.scenario, capture.role, capture.step): capture
            for capture in self.captures
        }
        self._capture_by_id = {
            capture.capture_id: capture for capture in self.captures
        }

    @property
    def scenario_ids(self) -> tuple[str, ...]:
        return tuple(item.scenario for item in self.scenarios)

    @property
    def capture_ids(self) -> tuple[str, ...]:
        return tuple(item.capture_id for item in self.captures)

    def scenario(self, scenario: str) -> Scenario:
        try:
            return self._scenario_by_id[scenario]
        except KeyError as exc:
            raise ScenarioContractError(f"unknown E2E scenario {scenario!r}") from exc

    def expected_roles(self, scenario: str) -> tuple[str, ...]:
        return tuple(role.role for role in self.scenario(scenario).roles)

    def scenarios_for_profile(self, profile: str) -> tuple[str, ...]:
        if profile not in EXECUTION_PROFILES:
            raise ScenarioContractError(
                f"unknown E2E execution profile {profile!r}"
            )
        return self._scenarios_by_profile[profile]

    def role(self, scenario: str, role: str) -> RoleContract:
        self.scenario(scenario)
        try:
            return self._role_by_key[(scenario, role)]
        except KeyError as exc:
            raise ScenarioContractError(
                f"unknown E2E scenario role {scenario!r}/{role!r}"
            ) from exc

    def expected_steps(self, scenario: str, role: str) -> tuple[str, ...]:
        return self.role(scenario, role).step_ids

    def expected_capture_steps(self, scenario: str, role: str) -> tuple[str, ...]:
        selected = self.role(scenario, role)
        return tuple(
            step.id
            for step in selected.steps
            if step.capture is not None
        )

    def orchestration_for(self, scenario: str) -> Orchestration:
        return self.scenario(scenario).orchestration

    def comparisons_for(
        self, scenario: str, role: str
    ) -> tuple[ScreenshotComparison, ...]:
        return self.role(scenario, role).comparisons

    def probes_for(
        self, scenario: str, role: str, step: str | None = None
    ) -> tuple[VisualProbe, ...]:
        selected = self.role(scenario, role)
        if step is None:
            return selected.probes
        if step not in selected.step_ids:
            raise ScenarioContractError(
                f"unknown E2E scenario step {scenario!r}/{role!r}/{step!r}"
            )
        return tuple(probe for probe in selected.probes if probe.step == step)

    def capture(self, scenario: str, role: str, step: str) -> Capture:
        self.role(scenario, role)
        try:
            return self._capture_by_key[(scenario, role, step)]
        except KeyError as exc:
            raise ScenarioContractError(
                f"step has no E2E capture {scenario!r}/{role!r}/{step!r}"
            ) from exc

    def capture_by_id(self, value: str) -> Capture:
        try:
            return self._capture_by_id[value]
        except KeyError as exc:
            raise ScenarioContractError(f"unknown E2E capture {value!r}") from exc

    def review_regions_for(
        self, value: str
    ) -> tuple[tuple[float, float, float, float], ...]:
        self.capture_by_id(value)
        return self.review_regions[value]


def _reject_constant(value: str) -> NoReturn:
    raise ScenarioContractError(f"non-finite JSON number {value!r} is forbidden")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ScenarioContractError(f"duplicate JSON object key {key!r}")
        result[key] = value
    return result


def canonical_json_bytes(value: Any) -> bytes:
    """Serialize JSON data deterministically for structural comparisons in tests."""

    try:
        encoded = json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError) as exc:
        raise ScenarioContractError(f"contract is not canonical JSON data: {exc}") from exc
    return encoded.encode("utf-8")


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def capture_id(scenario: str, role: str, step: str) -> str:
    return ".".join(
        (
            _identifier(scenario, "capture scenario"),
            _identifier(role, "capture role"),
            _identifier(step, "capture step"),
        )
    )


def java_scenario_enum_name(scenario: str) -> str:
    """Return the stable Java enum identity for a validated scenario id."""

    return re.sub(r"[^A-Z0-9]+", "_", _identifier(scenario, "scenario").upper())


def _object(
    value: Any,
    label: str,
    required: frozenset[str],
    optional: frozenset[str] = frozenset(),
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ScenarioContractError(f"{label} must be an object")
    fields = set(value)
    missing = required - fields
    unknown = fields - required - optional
    if missing or unknown:
        raise ScenarioContractError(
            f"{label} fields mismatch: missing={sorted(missing)}, unknown={sorted(unknown)}"
        )
    return value


def _array(value: Any, label: str, *, nonempty: bool = False) -> list[Any]:
    if not isinstance(value, list) or (nonempty and not value):
        suffix = " non-empty" if nonempty else ""
        raise ScenarioContractError(f"{label} must be a{suffix} array")
    return value


def _text(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise ScenarioContractError(f"{label} must be a non-empty trimmed string")
    return value


def _identifier(value: Any, label: str) -> str:
    text = _text(value, label)
    if IDENTIFIER.fullmatch(text) is None:
        raise ScenarioContractError(f"{label} has unsafe identifier {text!r}")
    return text


def _integer(value: Any, label: str, *, minimum: int, maximum: int) -> int:
    if (
        isinstance(value, bool)
        or not isinstance(value, int)
        or value < minimum
        or value > maximum
    ):
        raise ScenarioContractError(
            f"{label} must be an integer in [{minimum}, {maximum}]"
        )
    return value


def _boolean(value: Any, label: str) -> bool:
    if not isinstance(value, bool):
        raise ScenarioContractError(f"{label} must be a boolean")
    return value


def _number(value: Any, label: str, *, minimum: float, maximum: float) -> float:
    if (
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(value)
        or value < minimum
        or value > maximum
    ):
        raise ScenarioContractError(
            f"{label} must be a finite number in [{minimum}, {maximum}]"
        )
    return float(value)


def _region(value: Any, label: str) -> tuple[float, float, float, float]:
    raw = _array(value, label)
    if len(raw) != 4:
        raise ScenarioContractError(f"{label} must contain four normalized coordinates")
    result = tuple(
        _number(item, f"{label}[{index}]", minimum=0.0, maximum=1.0)
        for index, item in enumerate(raw)
    )
    if result[0] >= result[2] or result[1] >= result[3]:
        raise ScenarioContractError(f"{label} must describe a non-empty rectangle")
    return result  # type: ignore[return-value]


def _orchestration(value: Any, label: str, roles: tuple[str, ...]) -> Orchestration:
    raw = _object(
        value,
        label,
        frozenset({"mode"}),
        frozenset({"role_order", "start_after"}),
    )
    mode = _text(raw["mode"], f"{label}.mode")
    if mode not in ORCHESTRATION_MODES:
        raise ScenarioContractError(f"{label}.mode is unsupported: {mode!r}")
    if mode == "single-client":
        _object(raw, label, frozenset({"mode"}))
        if roles != ("client_a",):
            raise ScenarioContractError(f"{label} single-client mode requires only client_a")
        return Orchestration(mode)
    if roles != ("client_a", "client_b"):
        raise ScenarioContractError(f"{label} {mode} mode requires client_a then client_b")
    if mode == "sequential-two-client":
        _object(raw, label, frozenset({"mode", "role_order"}))
        order_values = _array(raw["role_order"], f"{label}.role_order", nonempty=True)
        role_order = tuple(
            _identifier(item, f"{label}.role_order[{index}]")
            for index, item in enumerate(order_values)
        )
        if len(role_order) != len(roles) or set(role_order) != set(roles):
            raise ScenarioContractError(
                f"{label}.role_order must list each scenario role exactly once in launch order"
            )
        return Orchestration(mode, role_order=role_order)

    _object(raw, label, frozenset({"mode"}), frozenset({"start_after"}))
    if "start_after" not in raw:
        return Orchestration(mode)
    start_raw = _object(
        raw["start_after"],
        f"{label}.start_after",
        frozenset({"role", "server_log_marker", "timeout_seconds"}),
    )
    start_after = StartAfter(
        role=_identifier(start_raw["role"], f"{label}.start_after.role"),
        server_log_marker=_text(
            start_raw["server_log_marker"],
            f"{label}.start_after.server_log_marker",
        ),
        timeout_seconds=_integer(
            start_raw["timeout_seconds"],
            f"{label}.start_after.timeout_seconds",
            minimum=1,
            maximum=3600,
        ),
    )
    if start_after.role not in roles:
        raise ScenarioContractError(f"{label}.start_after.role is not a scenario role")
    return Orchestration(mode, start_after=start_after)


def _comparison(
    value: Any, label: str, steps: frozenset[str]
) -> ScreenshotComparison:
    raw = _object(
        value,
        label,
        frozenset({"first_step", "second_step", "minimum_changed_fraction"}),
        frozenset({"region"}),
    )
    first = _identifier(raw["first_step"], f"{label}.first_step")
    second = _identifier(raw["second_step"], f"{label}.second_step")
    if first not in steps or second not in steps:
        raise ScenarioContractError(f"{label} references an unknown role step")
    if first == second:
        raise ScenarioContractError(f"{label} must compare two different steps")
    minimum = _number(
        raw["minimum_changed_fraction"],
        f"{label}.minimum_changed_fraction",
        minimum=0.0000001,
        maximum=1.0,
    )
    region = _region(raw["region"], f"{label}.region") if "region" in raw else None
    return ScreenshotComparison(first, second, minimum, region)


def _probe(
    value: Any,
    label: str,
    step: str,
    reference_size: tuple[int, int],
) -> VisualProbe:
    raw = _object(
        value,
        label,
        frozenset({"kind"}),
        frozenset(
            {
                "region",
                "maximum_mean_luma",
                "bright_luma",
                "maximum_bright_fraction",
                "label",
                "box",
                "minimum_luma_exclusive",
                "minimum_pixels",
            }
        ),
    )
    kind = _text(raw["kind"], f"{label}.kind")
    if kind == "opaque-stars-background":
        _object(
            raw,
            label,
            frozenset(
                {
                    "kind",
                    "region",
                    "maximum_mean_luma",
                    "bright_luma",
                    "maximum_bright_fraction",
                }
            ),
        )
        return OpaqueStarsProbe(
            step,
            _region(raw["region"], f"{label}.region"),
            _number(
                raw["maximum_mean_luma"],
                f"{label}.maximum_mean_luma",
                minimum=0.0,
                maximum=255.0,
            ),
            _integer(raw["bright_luma"], f"{label}.bright_luma", minimum=0, maximum=255),
            _number(
                raw["maximum_bright_fraction"],
                f"{label}.maximum_bright_fraction",
                minimum=0.0,
                maximum=1.0,
            ),
        )
    if kind != "required-gui-text":
        raise ScenarioContractError(f"{label}.kind is unsupported: {kind!r}")
    _object(
        raw,
        label,
        frozenset(
            {
                "kind",
                "label",
                "box",
                "minimum_luma_exclusive",
                "minimum_pixels",
            }
        ),
    )
    box_raw = _array(raw["box"], f"{label}.box")
    if len(box_raw) != 4:
        raise ScenarioContractError(f"{label}.box must contain four pixel coordinates")
    width, height = reference_size
    box = tuple(
        _integer(
            item,
            f"{label}.box[{index}]",
            minimum=0,
            maximum=width if index % 2 == 0 else height,
        )
        for index, item in enumerate(box_raw)
    )
    if box[0] >= box[2] or box[1] >= box[3]:
        raise ScenarioContractError(f"{label}.box must describe a non-empty rectangle")
    minimum_pixels = _integer(
        raw["minimum_pixels"], f"{label}.minimum_pixels", minimum=1, maximum=width * height
    )
    if minimum_pixels > (box[2] - box[0]) * (box[3] - box[1]):
        raise ScenarioContractError(f"{label}.minimum_pixels exceeds the probe box area")
    return RequiredGuiTextProbe(
        step,
        _text(raw["label"], f"{label}.label"),
        box,  # type: ignore[arg-type]
        _integer(
            raw["minimum_luma_exclusive"],
            f"{label}.minimum_luma_exclusive",
            minimum=0,
            maximum=254,
        ),
        minimum_pixels,
    )


def _parse_contract(data: Any, *, raw_sha256: str) -> ScenarioContract:
    root = _object(
        data,
        "scenario contract",
        frozenset(
            {
                "schema_version",
                "screenshot_size",
                "gui_text_reference_size",
                "review_regions",
                "scenarios",
            }
        ),
    )
    schema = _integer(
        root["schema_version"], "scenario contract.schema_version", minimum=2, maximum=2
    )
    if schema != SCHEMA_VERSION:  # pragma: no cover - range check documents the invariant
        raise ScenarioContractError(f"unsupported scenario contract schema {schema}")

    screenshot_raw = _array(root["screenshot_size"], "scenario contract.screenshot_size")
    if len(screenshot_raw) != 2:
        raise ScenarioContractError("screenshot_size must contain width and height")
    screenshot_size = (
        _integer(screenshot_raw[0], "screenshot_size[0]", minimum=1, maximum=16384),
        _integer(screenshot_raw[1], "screenshot_size[1]", minimum=1, maximum=16384),
    )
    if screenshot_size != REQUIRED_SCREENSHOT_SIZE:
        raise ScenarioContractError(
            "packaged E2E and AI review screenshots must remain exactly "
            f"{REQUIRED_SCREENSHOT_SIZE[0]}x{REQUIRED_SCREENSHOT_SIZE[1]}"
        )

    reference_raw = _array(
        root["gui_text_reference_size"], "scenario contract.gui_text_reference_size"
    )
    if len(reference_raw) != 2:
        raise ScenarioContractError("gui_text_reference_size must contain width and height")
    reference_size = (
        _integer(reference_raw[0], "gui_text_reference_size[0]", minimum=1, maximum=16384),
        _integer(reference_raw[1], "gui_text_reference_size[1]", minimum=1, maximum=16384),
    )

    scenario_values = _array(root["scenarios"], "scenario contract.scenarios", nonempty=True)
    scenarios: list[Scenario] = []
    scenario_names: set[str] = set()
    scenario_enum_names: dict[str, str] = {}
    role_keys: set[tuple[str, str]] = set()
    for scenario_index, scenario_value in enumerate(scenario_values):
        scenario_label = f"scenario contract.scenarios[{scenario_index}]"
        scenario_raw = _object(
            scenario_value,
            scenario_label,
            frozenset(
                {
                    "scenario",
                    "execution_profiles",
                    "orchestration",
                    "roles",
                }
            ),
        )
        scenario_name = _identifier(scenario_raw["scenario"], f"{scenario_label}.scenario")
        if scenario_name in scenario_names:
            raise ScenarioContractError(f"duplicate E2E scenario {scenario_name!r}")
        scenario_names.add(scenario_name)
        enum_name = java_scenario_enum_name(scenario_name)
        if enum_name in scenario_enum_names:
            raise ScenarioContractError(
                "scenario Java enum name collision: "
                f"{scenario_enum_names[enum_name]!r} and {scenario_name!r} "
                f"both map to {enum_name!r}"
            )
        scenario_enum_names[enum_name] = scenario_name
        profile_values = _array(
            scenario_raw["execution_profiles"],
            f"{scenario_label}.execution_profiles",
            nonempty=True,
        )
        execution_profiles = tuple(
            _identifier(
                profile,
                f"{scenario_label}.execution_profiles[{profile_index}]",
            )
            for profile_index, profile in enumerate(profile_values)
        )
        if len(set(execution_profiles)) != len(execution_profiles):
            raise ScenarioContractError(
                f"{scenario_label}.execution_profiles contains duplicates"
            )
        unsupported_profiles = set(execution_profiles) - EXECUTION_PROFILES
        if unsupported_profiles:
            raise ScenarioContractError(
                f"{scenario_label}.execution_profiles contains unsupported "
                f"profiles {sorted(unsupported_profiles)}"
            )

        role_values = _array(scenario_raw["roles"], f"{scenario_label}.roles", nonempty=True)
        roles: list[RoleContract] = []
        role_names: list[str] = []
        for role_index, role_value in enumerate(role_values):
            role_label = f"{scenario_label}.roles[{role_index}]"
            role_raw = _object(
                role_value,
                role_label,
                frozenset({"role", "steps", "comparisons"}),
            )
            role_name = _identifier(role_raw["role"], f"{role_label}.role")
            key = (scenario_name, role_name)
            if key in role_keys:
                raise ScenarioContractError(f"duplicate E2E scenario role {key!r}")
            role_keys.add(key)
            role_names.append(role_name)

            step_values = _array(role_raw["steps"], f"{role_label}.steps", nonempty=True)
            steps: list[StepContract] = []
            step_names: set[str] = set()
            for step_index, step_value in enumerate(step_values):
                step_label = f"{role_label}.steps[{step_index}]"
                step_raw = _object(
                    step_value,
                    step_label,
                    frozenset({"id", "assertion_required"}),
                    frozenset({"capture"}),
                )
                step_name = _identifier(step_raw["id"], f"{step_label}.id")
                if step_name in step_names:
                    raise ScenarioContractError(f"{role_label}.steps contains duplicates")
                step_names.add(step_name)
                assertion_required = _boolean(
                    step_raw["assertion_required"],
                    f"{step_label}.assertion_required",
                )
                if not assertion_required:
                    raise ScenarioContractError(
                        f"{step_label}.assertion_required must be true"
                    )

                capture: Capture | None = None
                if "capture" in step_raw:
                    capture_raw = _object(
                        step_raw["capture"],
                        f"{step_label}.capture",
                        frozenset({"title", "review_tier", "expectation", "probes"}),
                        frozenset({"compatibility_reference_capture_id"}),
                    )
                    review_tier = _text(
                        capture_raw["review_tier"],
                        f"{step_label}.capture.review_tier",
                    )
                    if review_tier not in REVIEW_TIERS:
                        raise ScenarioContractError(
                            f"{step_label}.capture.review_tier is unsupported: "
                            f"{review_tier!r}"
                        )
                    probe_values = _array(
                        capture_raw["probes"],
                        f"{step_label}.capture.probes",
                    )
                    probes = tuple(
                        _probe(
                            probe_value,
                            f"{step_label}.capture.probes[{probe_index}]",
                            step_name,
                            reference_size,
                        )
                        for probe_index, probe_value in enumerate(probe_values)
                    )
                    probe_keys = [
                        (
                            probe.kind,
                            probe.label
                            if isinstance(probe, RequiredGuiTextProbe)
                            else "",
                        )
                        for probe in probes
                    ]
                    if len(set(probe_keys)) != len(probe_keys):
                        raise ScenarioContractError(
                            f"{step_label}.capture.probes contains duplicates"
                        )
                    capture = Capture(
                        scenario_name,
                        role_name,
                        step_name,
                        _text(
                            capture_raw["title"],
                            f"{step_label}.capture.title",
                        ),
                        review_tier,
                        _text(
                            capture_raw["expectation"],
                            f"{step_label}.capture.expectation",
                        ),
                        probes,
                        (
                            _text(
                                capture_raw["compatibility_reference_capture_id"],
                                f"{step_label}.capture.compatibility_reference_capture_id",
                            )
                            if "compatibility_reference_capture_id" in capture_raw
                            else None
                        ),
                    )
                steps.append(StepContract(step_name, assertion_required, capture))

            if len(step_names) != len(steps):
                raise ScenarioContractError(f"{role_label}.steps contains duplicates")
            step_set = frozenset(step_names)
            capture_steps = frozenset(
                step.id for step in steps if step.capture is not None
            )
            comparison_values = _array(
                role_raw["comparisons"], f"{role_label}.comparisons"
            )
            comparisons = tuple(
                _comparison(value, f"{role_label}.comparisons[{index}]", step_set)
                for index, value in enumerate(comparison_values)
            )
            pairs = [(item.first_step, item.second_step) for item in comparisons]
            if len(set(pairs)) != len(pairs):
                raise ScenarioContractError(f"{role_label}.comparisons contains duplicates")
            for comparison in comparisons:
                if (
                    comparison.first_step not in capture_steps
                    or comparison.second_step not in capture_steps
                ):
                    raise ScenarioContractError(
                        f"comparison in {(scenario_name, role_name)!r} "
                        "references a non-capture step"
                    )
            roles.append(RoleContract(role_name, tuple(steps), comparisons))

        if not any(
            step.capture is not None
            for role in roles
            for step in role.steps
        ):
            raise ScenarioContractError(
                f"E2E scenario {scenario_name!r} has no captures"
            )

        orchestration = _orchestration(
            scenario_raw["orchestration"],
            f"{scenario_label}.orchestration",
            tuple(role_names),
        )
        scenarios.append(
            Scenario(
                scenario_name,
                execution_profiles,
                orchestration,
                tuple(roles),
            )
        )

    capture_profiles = {
        step.capture.capture_id: scenario.execution_profiles
        for scenario in scenarios
        for role in scenario.roles
        for step in role.steps
        if step.capture is not None
    }
    raw_review_regions = root["review_regions"]
    if not isinstance(raw_review_regions, dict):
        raise ScenarioContractError("scenario contract.review_regions must be an object")
    expected_capture_ids = set(capture_profiles)
    actual_capture_ids = set(raw_review_regions)
    if actual_capture_ids != expected_capture_ids:
        raise ScenarioContractError(
            "scenario contract.review_regions must cover every capture exactly: "
            f"missing={sorted(expected_capture_ids - actual_capture_ids)}, "
            f"unknown={sorted(actual_capture_ids - expected_capture_ids)}"
        )
    review_regions: dict[
        str, tuple[tuple[float, float, float, float], ...]
    ] = {}
    for capture_name in capture_profiles:
        values = _array(
            raw_review_regions[capture_name],
            f"scenario contract.review_regions[{capture_name!r}]",
            nonempty=True,
        )
        if len(values) > 8:
            raise ScenarioContractError(
                f"scenario contract.review_regions[{capture_name!r}] exceeds eight regions"
            )
        regions = tuple(
            _region(
                value,
                f"scenario contract.review_regions[{capture_name!r}][{index}]",
            )
            for index, value in enumerate(values)
        )
        if len(set(regions)) != len(regions):
            raise ScenarioContractError(
                f"scenario contract.review_regions[{capture_name!r}] contains duplicates"
            )
        review_regions[capture_name] = regions
    for scenario in scenarios:
        is_compatibility = any(
            profile in {"compatibility", "compatibility-remote"}
            for profile in scenario.execution_profiles
        )
        for role in scenario.roles:
            for step in role.steps:
                if step.capture is None:
                    continue
                reference = step.capture.compatibility_reference_capture_id
                if is_compatibility and reference is None:
                    raise ScenarioContractError(
                        f"compatibility capture {step.capture.capture_id!r} has no base reference"
                    )
                if not is_compatibility and reference is not None:
                    raise ScenarioContractError(
                        f"non-compatibility capture {step.capture.capture_id!r} declares a compatibility reference"
                    )
                if reference is not None and reference not in capture_profiles:
                    raise ScenarioContractError(
                        f"compatibility capture {step.capture.capture_id!r} references unknown capture {reference!r}"
                    )
                if reference is not None and "release" not in capture_profiles[reference]:
                    raise ScenarioContractError(
                        f"compatibility capture {step.capture.capture_id!r} reference is not in the release profile"
                    )

    # Extension profiles are allowed to be absent from deliberately minimal contracts used by
    # consumers and tests. The three base packaged-runtime profiles remain mandatory everywhere.
    for profile in REQUIRED_EXECUTION_PROFILES:
        if not any(profile in scenario.execution_profiles for scenario in scenarios):
            raise ScenarioContractError(
                f"E2E execution profile {profile!r} has no scenarios"
            )
    runtime_defaults = [
        scenario.scenario
        for scenario in scenarios
        if "runtime-default" in scenario.execution_profiles
    ]
    if len(runtime_defaults) != 1:
        raise ScenarioContractError(
            "runtime-default execution profile must select exactly one scenario"
        )

    return ScenarioContract(
        schema_version=schema,
        screenshot_size=screenshot_size,
        gui_text_reference_size=reference_size,
        review_regions=review_regions,
        scenarios=tuple(scenarios),
        sha256=raw_sha256,
    )


def load_contract(path: Path = DEFAULT_CONTRACT) -> ScenarioContract:
    path = Path(path)
    try:
        if path.is_symlink():
            raise ScenarioContractError(f"scenario contract path must not be a symlink: {path}")
        size = path.stat().st_size
        if size <= 0 or size > MAX_CONTRACT_BYTES:
            raise ScenarioContractError(
                f"scenario contract size must be in [1, {MAX_CONTRACT_BYTES}], got {size}"
            )
        raw_bytes = path.read_bytes()
        if len(raw_bytes) != size:
            raise ScenarioContractError(
                f"scenario contract changed while being read: {path}"
            )
        text = raw_bytes.decode("utf-8")
    except ScenarioContractError:
        raise
    except (OSError, UnicodeDecodeError) as exc:
        raise ScenarioContractError(f"cannot read scenario contract {path}: {exc}") from exc
    try:
        data = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_constant,
        )
    except ScenarioContractError:
        raise
    except json.JSONDecodeError as exc:
        raise ScenarioContractError(f"invalid scenario contract JSON {path}: {exc}") from exc
    return _parse_contract(
        data,
        raw_sha256=hashlib.sha256(raw_bytes).hexdigest(),
    )


@lru_cache(maxsize=1)
def default_contract() -> ScenarioContract:
    return load_contract(DEFAULT_CONTRACT)


def expected_scenarios(contract: ScenarioContract | None = None) -> tuple[str, ...]:
    return (contract or default_contract()).scenario_ids


def expected_roles(
    scenario: str, contract: ScenarioContract | None = None
) -> tuple[str, ...]:
    return (contract or default_contract()).expected_roles(scenario)


def scenarios_for_profile(
    profile: str, contract: ScenarioContract | None = None
) -> tuple[str, ...]:
    return (contract or default_contract()).scenarios_for_profile(profile)


def expected_steps(
    scenario: str, role: str, contract: ScenarioContract | None = None
) -> tuple[str, ...]:
    return (contract or default_contract()).expected_steps(scenario, role)


def expected_capture_steps(
    scenario: str, role: str, contract: ScenarioContract | None = None
) -> tuple[str, ...]:
    return (contract or default_contract()).expected_capture_steps(scenario, role)


def orchestration_for(
    scenario: str, contract: ScenarioContract | None = None
) -> Orchestration:
    return (contract or default_contract()).orchestration_for(scenario)


def contract_sha256(path: Path = DEFAULT_CONTRACT) -> str:
    return load_contract(path).sha256


if __name__ == "__main__":
    print(contract_sha256())
