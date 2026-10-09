"""Quick Skin's Build dispatcher, run by the shared mod-base workflows as
``<python> -I -B <checkout>/scripts/ci/mod_base_build_dispatch.py --hook <name>``.

The kit runs it in a disposable account whose ``HOME`` lies directly below the worker root
(mod-base ``docs/BUILD-ADAPTER.md``):

* a protected hook (``derive_plan``, ``derive_runtime``, ``verify_target``, ``verify_build``,
  ``verify_runtime``) runs from the protected copy of the adapter. It reads
  ``<root>/validation-input/`` and the sealed exports (``<root>/sealed-build/``,
  ``<root>/sealed-runtime/``) and writes ``$HOME/validation/``;
* a candidate hook (``policy``, ``build_target``, ``run_lane``) runs from the tested checkout, its
  working directory, and writes ``$HOME/export/`` (``scripts/ci/mod_base_build_candidate.py`` and
  ``scripts/ci/mod_base_build_policy.py`` hold what it does).

``MB_TARGET_ID`` or ``MB_LANE_ID`` names the unit; ``MB_TESTED_SHA`` names what is tested. Every
output is a new private file: nothing is overwritten. A protected hook writes its report only when
every check holds.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

# ``-I`` keeps the script's directory off ``sys.path``; the adapter modules live next to this file.
sys.path.insert(0, str(Path(__file__).resolve().parent))

import mod_base_build_adapter as adapter  # noqa: E402

UNIT_NAMES = {"build_target": "MB_TARGET_ID", "verify_target": "MB_TARGET_ID", "derive_runtime": "MB_LANE_ID",
              "run_lane": "MB_LANE_ID", "verify_runtime": "MB_LANE_ID"}
CANDIDATE_HOOKS = ("policy", "build_target", "run_lane")
KIT_FILES = ("ci-envelope.json", "ci-runtime-envelope.json")
INPUT_NAMES = ("inventory", "scenario-contract", adapter.PROPERTIES_INPUT)
MAX_PLAN_BYTES = 4 * 1024 * 1024


class Output:
    """The hook's output directory: files are created, never replaced."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.written: list[Path] = []

    def write(self, relative: str, data: bytes) -> None:
        path = self.root.joinpath(*relative.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "xb") as stream:
            stream.write(data)
        self.written.append(path)


def _read(root: Path, relative: str, *, max_bytes: int = 1 << 30) -> bytes:
    return adapter.read_regular(root.joinpath(*relative.split("/")), relative, max_bytes=max_bytes)


def _files(root: Path) -> set[str]:
    """Every file below ``root`` by relative path; a link or special file is a rejection."""

    found = set()
    for path in sorted(root.rglob("*")) if root.is_dir() else ():
        if path.is_symlink() or not (path.is_dir() or path.is_file()):
            raise adapter.AdapterError(f"{path.name} is not a regular file or directory")
        if path.is_file():
            found.add(path.relative_to(root).as_posix())
    return found


def _protected_inputs(root: Path) -> tuple[adapter.Mod, dict[str, bytes]]:
    """The native inputs a protected hook was given in ``validation-input/``, validated, with
    their bytes by name."""

    inputs = root / "validation-input"
    mod = adapter.load_mod(inputs / "inventory", inputs / "scenario-contract", inputs / adapter.PROPERTIES_INPUT)
    raw = {name: _read(inputs, name) for name in INPUT_NAMES}
    if (adapter.sha256(raw["inventory"]), adapter.sha256(raw["scenario-contract"]),
            adapter.sha256(raw[adapter.PROPERTIES_INPUT])) != (mod.inventory_sha256, mod.contract_sha256,
                                                               mod.properties_sha256):
        raise adapter.AdapterError("validation-input changed while it was read")
    return mod, raw


def _plan(root: Path, mod: adapter.Mod) -> dict[str, Any]:
    """The protected plan, which must bind every candidate file this hook was given and hold
    exactly the units this adapter derives from them."""

    plan = adapter.decode(_read(root / "validation-input", "ci-plan.json", max_bytes=MAX_PLAN_BYTES), "plan",
                          max_bytes=MAX_PLAN_BYTES)
    identity = plan["identity"]
    if (identity["inventory_sha256"], identity["scenario_sha256"]) != (mod.inventory_sha256, mod.contract_sha256):
        raise adapter.AdapterError("the plan was not derived from this matrix and scenario contract")
    if plan["plan_inputs"] != [{"name": adapter.PROPERTIES_INPUT, "sha256": mod.properties_sha256}]:
        raise adapter.AdapterError("the plan was not derived from this gradle.properties")
    derived = adapter.derive_plan(mod)
    if {"targets": plan["targets"], "lanes": plan["lanes"]} != derived:
        raise adapter.AdapterError("the plan differs from what this adapter derives from its inputs")
    return plan


def _unit(plan: dict[str, Any], kind: str, unit: str) -> dict[str, Any]:
    for entry in plan[kind]:
        if entry["id"] == unit:
            return entry
    raise adapter.AdapterError(f"the plan has no {kind[:-1]} {unit!r}")


def _verify_targets(root: Path, output: Output, hook: str, unit: str | None) -> None:
    mod, _ = _protected_inputs(root)
    plan = _plan(root, mod)
    sealed = root / "sealed-build"
    planned = {entry["path"] for target in plan["targets"] for entry in target["outputs"]}
    present = _files(sealed) - set(KIT_FILES)
    selected = plan["targets"] if unit is None else [_unit(plan, "targets", unit)]
    expected = planned if unit is None else {entry["path"] for entry in selected[0]["outputs"]}
    if present != expected:
        raise adapter.AdapterError("the sealed Build does not hold exactly the planned outputs")
    tested_sha = plan["identity"]["tested_sha"]
    for entry in selected:
        files = adapter.verify_target(mod, entry["id"], tested_sha=tested_sha, sealed=sealed,
                                      read=lambda path: _read(sealed, path))
        if [record["path"] for record in files] != sorted(item["path"] for item in entry["outputs"]):
            raise adapter.AdapterError(f"target {entry['id']} verified other files than it planned")
        output.write(f"{entry['id']}.json", adapter.encode({
            "schema_version": 1, "hook": hook, "unit": entry["id"], "tested_sha": tested_sha,
            "native_contract_sha256": entry["native_contract_sha256"], "files": files}))


def _verify_runtime(root: Path, output: Output, lane: str) -> None:
    mod, _ = _protected_inputs(root)
    plan = _plan(root, mod)
    entry = _unit(plan, "lanes", lane)
    production = [item["path"] for item in _unit(plan, "targets", entry["target_id"])["outputs"]
                  if item["lane_id"] == lane and item["role"] == "production"]
    if len(production) != 1:
        raise adapter.AdapterError(f"lane {lane} has no single production JAR in the plan")
    runtime = root / "sealed-runtime"
    present = _files(runtime) - set(KIT_FILES)
    prefix = f"lanes/{lane}/"
    if not present or any(not path.startswith(prefix) for path in present):
        raise adapter.AdapterError(f"lane {lane} left results outside {prefix}")
    results = runtime / "lanes" / lane
    summary = adapter.verify_run(mod, lane, entry["obligations"],
                                 production=_read(root / "sealed-build", production[0]),
                                 files={path[len(prefix):] for path in present},
                                 read=lambda path: _read(results, path))
    output.write(f"{lane}.json", adapter.encode({"schema_version": 1, "hook": "verify_runtime", "unit": lane,
                                               "tested_sha": plan["identity"]["tested_sha"],
                                               "native_contract_sha256": entry["native_contract_sha256"], **summary}))


def _hook(hook: str, unit: str | None, root: Path, output: Output) -> None:
    if hook == "derive_plan":
        mod, _ = _protected_inputs(root)
        output.write("plan.json", adapter.encode(adapter.derive_plan(mod)))
    elif hook == "derive_runtime":
        mod, _ = _protected_inputs(root)
        _unit(_plan(root, mod), "lanes", unit)
        output.write("runtime.json", adapter.encode({"values": adapter.runtime_values(mod, unit)}))
    elif hook in ("verify_target", "verify_build"):
        _verify_targets(root, output, hook, unit)
    elif hook == "verify_runtime":
        _verify_runtime(root, output, unit)
    elif hook == "policy":
        import mod_base_build_policy
        mod_base_build_policy.run(Path.cwd())
    elif hook == "build_target":
        import mod_base_build_candidate
        mod_base_build_candidate.build_target(Path.cwd(), unit, output.root)
    else:
        import mod_base_build_candidate
        mod_base_build_candidate.run_lane(Path.cwd(), unit, output.root)


def main(arguments: list[str]) -> int:
    if len(arguments) != 2 or arguments[0] != "--hook" or arguments[1] not in adapter.HOOKS:
        print("usage: mod_base_build_dispatch.py --hook <name>", flush=True)
        return 2
    hook = arguments[1]
    os.umask(0o077)
    home = Path(os.environ["HOME"])
    unit = os.environ.get(UNIT_NAMES[hook]) if hook in UNIT_NAMES else None
    label = hook if unit is None else f"{hook} {unit}"
    output = Output(home / ("export" if hook in CANDIDATE_HOOKS else "validation"))
    try:
        if hook in UNIT_NAMES and not unit:
            raise adapter.AdapterError(f"{UNIT_NAMES[hook]} is required")
        _hook(hook, unit, home.parent, output)
    except (adapter.AdapterError, KeyError, TypeError, AttributeError, ValueError, OSError) as error:
        print(f"quick-skin {label} rejected: {type(error).__name__}: {str(error)[:2000]}", flush=True)
        return 1
    print(f"quick-skin {label}: ok", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
