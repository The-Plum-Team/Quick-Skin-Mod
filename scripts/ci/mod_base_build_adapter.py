"""Quick Skin's protected adapter for the shared mod-base Build and packaged E2E (``BUILD_ADAPTER_API = 1``).

Everything the protected hooks know about Quick Skin. ``scripts/ci/mod_base_build_dispatch.py``
is the only caller; the kit runs it as ``<python> -I -B <checkout>/scripts/ci/mod_base_build_dispatch.py
--hook <name>`` (mod-base ``docs/BUILD-ADAPTER.md``).

The plan is derived from three candidate files, read as data:

* ``release/release-matrix.json`` (the authoritative release inventory),
* ``e2e/scenario-contract.json`` (the packaged scenario contract),
* ``gradle.properties`` (the mod version that names every staged JAR).

The parsers are Quick Skin's own: the protected copies of ``scripts/release/matrix.py`` and
``e2e/scenario_contract.py``, listed with their hashes in ``scripts/ci/mod-base-build.json`` like
every other module this file imports. A protected hook therefore never imports a candidate module:
the kit runs it from the protected default branch's copy of exactly those files. The matrix is
decoded strictly here (bounded, no duplicate key, no non-finite number) before the native
validation runs, which closes the ambiguity of the native ``json.loads`` reader.

The native shape this reproduces:

* one target per distinct ``artifact_version`` in numeric order (``scripts/release/build_matrix.py``
  ``build_plan``), with the JDK the matrix gives its artifacts;
* six outputs per target: the production and harness JAR of each artifact node under the names
  ``verify_release.py`` stages, plus the target's ``artifacts.json`` and CycloneDX SBOM, which the
  native stage writes without a target in their names and the shared Build keeps apart as
  ``targets/<target>/artifacts.json`` and ``targets/<target>/sbom/quick-skin.cdx.json``;
* one lane per ``matrix.py --kind pr-anchors`` row, named by its artifact node (the native row id
  holds ``--``, which a kit unit id may not), with one obligation per scenario of the PR profile.

Standard library and the protected native modules only, Python 3.11 or newer. Every JSON output is
canonical: sorted keys, compact separators, UTF-8, one final newline.
"""

from __future__ import annotations

import hashlib
import json
import sys
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
for _directory in (ROOT / "e2e", ROOT / "scripts" / "release"):
    if str(_directory) not in sys.path:
        sys.path.insert(0, str(_directory))

import matrix as release_matrix  # noqa: E402
import scenario_contract  # noqa: E402

BUILD_ADAPTER_API = 1
HOOKS = ("derive_plan", "policy", "build_target", "verify_target", "verify_build", "derive_runtime", "run_lane",
         "verify_runtime")
#: The name the protected Build config stages ``gradle.properties`` under for the protected hooks.
PROPERTIES_INPUT = "gradle-properties"
INVENTORY_PATH = "release/release-matrix.json"
CONTRACT_PATH = "e2e/scenario-contract.json"
PROPERTIES_PATH = "gradle.properties"
#: What every target stages for itself. The native stage writes both without a target in the name.
MANIFEST_NAME = "artifacts.json"
SBOM_NAME = "sbom/quick-skin.cdx.json"
#: The packaged-E2E execution profile a pull request runs (``matrix.py --kind pr-anchors``).
RUNTIME_KIND = "pr-anchors"
RUNTIME_PROFILE = "pr"
#: Bounds of the candidate files read as data. The kit bounds them as well; these keep a decode
#: from running away before it does.
MAX_INVENTORY_BYTES = 4 * 1024 * 1024
MAX_PROPERTIES_BYTES = 1024 * 1024
#: The kit's cap on a Quick Skin native report (mod-base ``MAX_CI_BUILD_REPORT_BYTES_BY_PROFILE``).
MAX_NATIVE_REPORT_BYTES = 4 * 1024 * 1024
MAX_SBOM_BYTES = 16 * 1024 * 1024
TARGET_CONTRACT = "quick-skin-target-v1"
LANE_CONTRACT = "quick-skin-lane-v1"


class AdapterError(Exception):
    """An input or an export does not satisfy Quick Skin's native contract."""


# -- Strict JSON ---------------------------------------------------------------------------------------


def _unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise AdapterError(f"duplicate JSON key {key!r}")
        result[key] = value
    return result


def _constant(name: str) -> Any:
    raise AdapterError(f"non-finite JSON number {name}")


def decode(data: bytes, label: str, *, max_bytes: int = MAX_INVENTORY_BYTES) -> Any:
    """Strict JSON: 1..``max_bytes`` bytes of UTF-8, no duplicate key, no NaN or infinity."""

    if type(data) is not bytes or not data or len(data) > max_bytes:
        raise AdapterError(f"{label} must hold 1..{max_bytes} bytes")
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=_unique, parse_constant=_constant)
    except (UnicodeDecodeError, ValueError, RecursionError) as error:
        raise AdapterError(f"{label} is not strict JSON: {error}") from error


def encode(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
            + "\n").encode("utf-8")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _digest(data: bytes, algorithm: str) -> str:
    return hashlib.new(algorithm, data).hexdigest()


def _object(value: Any, keys: tuple[str, ...], label: str) -> dict[str, Any]:
    if type(value) is not dict or sorted(value) != sorted(keys):
        raise AdapterError(f"{label} must be an object with exactly {sorted(keys)}")
    return value


def read_regular(path: Path, label: str, *, max_bytes: int) -> bytes:
    """The bytes of one regular file that is not a link, within ``max_bytes``."""

    if path.is_symlink() or not path.is_file():
        raise AdapterError(f"{label} is not a regular file")
    if path.stat().st_size > max_bytes:
        raise AdapterError(f"{label} exceeds {max_bytes} bytes")
    data = path.read_bytes()
    if len(data) > max_bytes:
        raise AdapterError(f"{label} exceeds {max_bytes} bytes")
    return data


# -- Native inputs -------------------------------------------------------------------------------------


def version_key(version: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError as error:
        raise AdapterError(f"non-numeric Minecraft version {version!r}") from error


class Mod:
    """The validated native inputs of one tested tree: the matrix document, the parsed scenario
    contract, the Gradle properties and the mod version, with the SHA-256 of the bytes read."""

    def __init__(self, *, data: dict[str, Any], contract: scenario_contract.ScenarioContract,
                 properties: dict[str, str], inventory_sha256: str, contract_sha256: str,
                 properties_sha256: str) -> None:
        self.data = data
        self.contract = contract
        self.properties = properties
        self.inventory_sha256 = inventory_sha256
        self.contract_sha256 = contract_sha256
        self.properties_sha256 = properties_sha256
        self.mod_version = properties[data["project"]["mod_version_property"]]

    @property
    def project(self) -> dict[str, Any]:
        return self.data["project"]


def load_mod(inventory: Path, contract: Path, properties: Path) -> Mod:
    """Read and validate the three native inputs from these files (the protected hook's
    ``validation-input/`` names, or the candidate checkout's own paths).

    The matrix is decoded strictly, then validated by the native ``validate_matrix`` against the
    contract given here (never a contract found next to the module) and by the native Gradle
    property check. Only a unified schema-3 matrix builds one target per Minecraft version."""

    raw_inventory = read_regular(inventory, "release matrix", max_bytes=MAX_INVENTORY_BYTES)
    raw_contract = read_regular(contract, "scenario contract", max_bytes=scenario_contract.MAX_CONTRACT_BYTES)
    raw_properties = read_regular(properties, "gradle.properties", max_bytes=MAX_PROPERTIES_BYTES)
    data = decode(raw_inventory, "release matrix")
    if type(data) is not dict:
        raise AdapterError("release matrix root must be an object")
    try:
        parsed_contract = scenario_contract.load_contract(contract)
    except scenario_contract.ScenarioContractError as error:
        raise AdapterError(f"invalid scenario contract: {error}") from error
    if parsed_contract.sha256 != sha256(raw_contract):
        raise AdapterError("the scenario contract changed while it was read")
    try:
        release_matrix.validate_matrix(data, parsed_contract)
        parsed_properties = release_matrix.read_properties(properties)
        release_matrix.validate_build_property_values(data, parsed_properties)
    except release_matrix.MatrixError as error:
        raise AdapterError(f"invalid release matrix: {error}") from error
    if data["schema_version"] != 3:
        raise AdapterError("the shared Build needs the unified schema-3 release matrix")
    key = data["project"].get("mod_version_property")
    if type(key) is not str or not parsed_properties.get(key):
        raise AdapterError("gradle.properties does not set the mod version the matrix names")
    if read_regular(properties, "gradle.properties", max_bytes=MAX_PROPERTIES_BYTES) != raw_properties:
        raise AdapterError("gradle.properties changed while it was read")
    return Mod(data=data, contract=parsed_contract, properties=parsed_properties,
               inventory_sha256=sha256(raw_inventory), contract_sha256=sha256(raw_contract),
               properties_sha256=sha256(raw_properties))


@contextmanager
def bound_contract(contract: scenario_contract.ScenarioContract) -> Iterator[None]:
    """Make the native matrix module use ``contract`` wherever it falls back to its default, for
    the duration of the block.

    ``select_release_target`` validates without passing a contract, and the default it would load
    is the file next to the module: in the protected copy that file does not exist, and in no hook
    is it necessarily the contract the plan was derived from."""

    original = release_matrix.default_contract
    release_matrix.default_contract = lambda: contract
    try:
        yield
    finally:
        release_matrix.default_contract = original


def targets(mod: Mod) -> list[str]:
    """The Minecraft targets in the native build order."""

    return sorted({row["artifact_version"] for row in mod.data["artifacts"]}, key=version_key)


def artifacts_of(mod: Mod, target: str) -> list[dict[str, Any]]:
    rows = [row for row in mod.data["artifacts"] if row["artifact_version"] == target]
    if not rows:
        raise AdapterError(f"the release matrix has no target {target!r}")
    return rows


def select_target(mod: Mod, target: str) -> dict[str, Any]:
    """The native per-target view (``matrix.select_release_target``), validated again."""

    try:
        with bound_contract(mod.contract):
            return release_matrix.select_release_target(mod.data, target)
    except release_matrix.MatrixError as error:
        raise AdapterError(f"invalid release target {target!r}: {error}") from error


def staged_name(template: str, mod_version: str) -> str:
    return Path(template.replace("{mod_version}", mod_version)).name


def target_path(target: str, name: str) -> str:
    """Where a file of a whole target is staged: every target writes an ``artifacts.json`` and an
    SBOM, and a path is unique in the whole plan."""

    return f"targets/{target}/{name}"


def target_outputs(mod: Mod, target: str) -> list[dict[str, Any]]:
    """The six files a target stages: the production and harness JAR of each artifact node, under
    the names ``verify_release.py`` stages them, then the target's manifest and SBOM."""

    outputs: list[dict[str, Any]] = []
    for row in artifacts_of(mod, target):
        outputs.append({"path": f"files/{staged_name(row['jar'], mod.mod_version)}",
                        "lane_id": row["artifact_node"], "role": "production"})
        outputs.append({"path": f"harness/{staged_name(row['harness_jar'], mod.mod_version)}",
                        "lane_id": row["artifact_node"], "role": "harness"})
    outputs.append({"path": target_path(target, MANIFEST_NAME), "lane_id": None, "role": "native-report"})
    outputs.append({"path": target_path(target, SBOM_NAME), "lane_id": None, "role": "sbom"})
    return outputs


def runtime_rows(mod: Mod) -> dict[str, dict[str, Any]]:
    """The native PR runtime rows (``matrix.py --kind pr-anchors``) by artifact node."""

    try:
        rows = release_matrix.gha_matrix(mod.data, RUNTIME_KIND, mod.mod_version, mod.contract)["include"]
    except release_matrix.MatrixError as error:
        raise AdapterError(f"cannot derive the runtime rows: {error}") from error
    result: dict[str, dict[str, Any]] = {}
    for row in rows:
        node = row["artifact_node"]
        if node in result:
            raise AdapterError(f"artifact node {node} has more than one PR runtime row")
        result[node] = row
    return result


def scenarios(mod: Mod) -> list[str]:
    return list(mod.contract.scenarios_for_profile(RUNTIME_PROFILE))


def obligation(scenario: str) -> str:
    return f"scenario/{scenario}"


def target_contract(mod: Mod, target: str) -> str:
    return sha256(encode({"contract": TARGET_CONTRACT, "mod_version": mod.mod_version, "target": target,
                          "release": select_target(mod, target)}))


def lane_contract(mod: Mod, row: dict[str, Any]) -> str:
    return sha256(encode({"contract": LANE_CONTRACT, "row": row, "scenario_contract_sha256": mod.contract_sha256,
                          "scenarios": scenarios(mod)}))


def derive_plan(mod: Mod) -> dict[str, Any]:
    """Targets and lanes in the kit's plan shape (mod-base ``docs/BUILD-ADAPTER.md``)."""

    rows = runtime_rows(mod)
    wanted = [obligation(scenario) for scenario in scenarios(mod)]
    if not wanted:
        raise AdapterError("the PR execution profile names no scenario")
    planned_targets, lanes = [], []
    for target in targets(mod):
        artifacts = artifacts_of(mod, target)
        javas = {row["java"] for row in artifacts}
        if len(javas) != 1:
            raise AdapterError(f"the artifacts of {target} disagree on their JDK")
        target_lanes = [row["artifact_node"] for row in artifacts if row["artifact_node"] in rows]
        if not target_lanes:
            raise AdapterError(f"target {target} has no PR runtime lane")
        for node in target_lanes:
            lanes.append({"id": node, "target_id": target, "native_contract_sha256": lane_contract(mod, rows[node]),
                          "obligations": list(wanted)})
        planned_targets.append({"id": target, "java": javas.pop(), "native_contract_sha256": target_contract(mod, target),
                                "outputs": target_outputs(mod, target)})
    if {lane["id"] for lane in lanes} != set(rows):
        raise AdapterError("a PR runtime row names no released artifact node")
    return {"targets": planned_targets, "lanes": lanes}


def lane_target(mod: Mod, lane: str) -> str:
    for row in mod.data["artifacts"]:
        if row["artifact_node"] == lane:
            return row["artifact_version"]
    raise AdapterError(f"the release matrix has no lane {lane!r}")


def runtime_values(mod: Mod, lane: str) -> dict[str, str]:
    """What ``run_lane`` receives: the native PR row of the lane, as ``matrix.py`` prints a row,
    and the scenarios that row names."""

    row = runtime_rows(mod).get(lane)
    if row is None:
        raise AdapterError(f"lane {lane!r} has no PR runtime row")
    if row["scenarios"] != ",".join(scenarios(mod)):
        raise AdapterError(f"the runtime row of {lane} names other scenarios than the PR profile")
    return {"E2E_ROW_JSON": json.dumps(row, separators=(",", ":"), sort_keys=True, ensure_ascii=True),
            "E2E_SCENARIOS": row["scenarios"]}


# -- Build verification --------------------------------------------------------------------------------

_MANIFEST_KEYS = ("schema_version", "matrix", "matrix_sha256", "lane_count", "mod_version", "git_commit", "release",
                  "artifacts", "sbom")
_RELEASE_KEYS = ("release_id", "tag", "branch", "mod_version", "minecraft_versions")
_RECORD_KEYS = ("filename", "bytes", "sha1", "sha256", "sha512", "artifact_node", "artifact_version", "loader",
                "game_versions", "path", "harness")
_HARNESS_KEYS = ("filename", "bytes", "sha256", "path")
_SBOM_RECORD_KEYS = ("format", "spec_version", "filename", "path", "bytes", "sha256")


def release_identity(mod: Mod, target: str) -> dict[str, Any]:
    """The release identity ``release_identity.derive(..., target=target)`` gives a target."""

    identity = release_matrix.release_id(mod.data, mod.mod_version, target=target)
    return {"release_id": identity, "tag": identity, "branch": mod.project["release_branch"],
            "mod_version": mod.mod_version, "minecraft_versions": [target]}


def _native_release_modules() -> tuple[Any, Any]:
    """The protected ``verify_release`` and ``generate_sbom`` modules, imported only by the hooks
    that verify JAR and SBOM content."""

    import generate_sbom
    import verify_release
    return verify_release, generate_sbom


def verify_target(mod: Mod, target: str, *, tested_sha: str, sealed: Path,
                  read: Callable[[str], bytes]) -> list[dict[str, Any]]:
    """Verify the sealed partition of ``target`` (``read(path) -> bytes`` below the sealed Build)
    and return the record of every planned file of it.

    The staged manifest is decoded strictly against a closed schema and must name this inventory,
    this mod version, the tested commit, the target's release identity and exactly the target's
    artifacts. Every JAR is hashed again and checked by the native ``verify_jar`` and
    ``verify_harness``; the SBOM must be the canonical CycloneDX document of exactly these JARs at
    the tested commit."""

    verify_release, generate_sbom = _native_release_modules()
    artifacts = artifacts_of(mod, target)
    raw = read(target_path(target, MANIFEST_NAME))
    if len(raw) > MAX_NATIVE_REPORT_BYTES:
        raise AdapterError(f"the manifest of {target} exceeds the native report cap")
    manifest = _object(decode(raw, f"manifest of {target}", max_bytes=MAX_NATIVE_REPORT_BYTES), _MANIFEST_KEYS,
                       f"manifest of {target}")
    expected = {"schema_version": 2, "matrix": INVENTORY_PATH, "matrix_sha256": mod.inventory_sha256,
                "lane_count": len(artifacts), "mod_version": mod.mod_version, "git_commit": tested_sha,
                "release": release_identity(mod, target)}
    for key, value in expected.items():
        if type(manifest[key]) is not type(value) or manifest[key] != value:
            raise AdapterError(f"the manifest of {target} has another {key}")
    _object(manifest["release"], _RELEASE_KEYS, f"release of {target}")
    records = manifest["artifacts"]
    if type(records) is not list or [record.get("artifact_node") if type(record) is dict else None
                                     for record in records] != [row["artifact_node"] for row in artifacts]:
        raise AdapterError(f"the manifest of {target} does not list exactly its artifacts in matrix order")
    files: list[dict[str, Any]] = []
    jar_hashes: dict[str, dict[str, Any]] = {}
    for record, row in zip(records, artifacts):
        node = row["artifact_node"]
        _object(record, _RECORD_KEYS, f"record of {node}")
        production = f"files/{staged_name(row['jar'], mod.mod_version)}"
        harness_path = f"harness/{staged_name(row['harness_jar'], mod.mod_version)}"
        if (record["artifact_version"], record["loader"], record["game_versions"], record["path"],
                record["filename"]) != (row["artifact_version"], row["loader"], row["game_versions"], production,
                                        production.split("/", 1)[1]):
            raise AdapterError(f"the record of {node} describes another artifact")
        data = read(production)
        observed = {"bytes": len(data), "sha1": _digest(data, "sha1"), "sha256": sha256(data),
                    "sha512": _digest(data, "sha512")}
        if {key: record[key] for key in observed} != observed:
            raise AdapterError(f"{production} differs from the manifest of {target}")
        harness = _object(record["harness"], _HARNESS_KEYS, f"harness record of {node}")
        harness_data = read(harness_path)
        if harness != {"filename": harness_path.split("/", 1)[1], "path": harness_path, "bytes": len(harness_data),
                       "sha256": sha256(harness_data)}:
            raise AdapterError(f"{harness_path} differs from the manifest of {target}")
        try:
            verify_release.verify_jar(sealed / production, row, mod.project, mod.mod_version)
            verify_release.verify_harness(sealed / harness_path, row)
        except verify_release.VerificationError as error:
            raise AdapterError(f"{node}: {error}") from error
        jar_hashes[node] = observed
        files.append({"path": production, "sha256": observed["sha256"], "size": len(data)})
        files.append({"path": harness_path, "sha256": sha256(harness_data), "size": len(harness_data)})
    sbom_record = _object(manifest["sbom"], _SBOM_RECORD_KEYS, f"SBOM record of {target}")
    sbom_raw = read(target_path(target, SBOM_NAME))
    if sbom_record != {"format": "CycloneDX", "spec_version": generate_sbom.CYCLONEDX_SPEC_VERSION,
                       "filename": SBOM_NAME.rsplit("/", 1)[1], "path": SBOM_NAME, "bytes": len(sbom_raw),
                       "sha256": sha256(sbom_raw)}:
        raise AdapterError(f"the SBOM of {target} differs from its manifest record")
    _verify_sbom(mod, target, tested_sha=tested_sha, raw=sbom_raw, jars=jar_hashes, generate_sbom=generate_sbom)
    files.append({"path": target_path(target, SBOM_NAME), "sha256": sha256(sbom_raw), "size": len(sbom_raw)})
    files.append({"path": target_path(target, MANIFEST_NAME), "sha256": sha256(raw), "size": len(raw)})
    return sorted(files, key=lambda record: record["path"])


def _properties(component: dict[str, Any], label: str) -> dict[str, str]:
    values = component.get("properties")
    if type(values) is not list:
        raise AdapterError(f"{label} has no properties")
    result: dict[str, str] = {}
    for item in values:
        item = _object(item, ("name", "value"), f"{label} property")
        if item["name"] in result:
            raise AdapterError(f"{label} repeats property {item['name']}")
        result[item["name"]] = item["value"]
    return result


def _verify_sbom(mod: Mod, target: str, *, tested_sha: str, raw: bytes, jars: dict[str, dict[str, Any]],
                 generate_sbom: Any) -> None:
    """The SBOM must be canonical CycloneDX (the native ``canonical_bytes``, which validates it),
    name the tested commit, the target's release and this inventory, and describe exactly the
    target's production JARs by their sealed hashes."""

    sbom = decode(raw, f"SBOM of {target}", max_bytes=MAX_SBOM_BYTES)
    if type(sbom) is not dict:
        raise AdapterError(f"the SBOM of {target} is not an object")
    try:
        canonical = generate_sbom.canonical_bytes(sbom)
    except generate_sbom.SbomError as error:
        raise AdapterError(f"the SBOM of {target} is invalid: {error}") from error
    if canonical != raw:
        raise AdapterError(f"the SBOM of {target} is not canonical")
    release = release_identity(mod, target)["release_id"]
    root = sbom["metadata"]["component"]
    expected_root = {"quickskin:git-commit": tested_sha, "quickskin:release-id": release,
                     "quickskin:release-matrix": INVENTORY_PATH, "quickskin:release-matrix-sha256": mod.inventory_sha256}
    properties = _properties(root, f"SBOM root of {target}")
    if {key: properties.get(key) for key in expected_root} != expected_root:
        raise AdapterError(f"the SBOM of {target} names another build")
    if root.get("bom-ref") != f"urn:quickskin:release:{release}:git:{tested_sha}" or root.get("version") != mod.mod_version:
        raise AdapterError(f"the SBOM root of {target} names another release")
    files = {component["bom-ref"]: component for component in sbom["components"] if component.get("type") == "file"}
    expected_refs = {f"urn:quickskin:artifact:{node}:sha256:{hashes['sha256']}": (node, hashes)
                     for node, hashes in jars.items()}
    if set(files) != set(expected_refs):
        raise AdapterError(f"the SBOM of {target} does not describe exactly the target's production JARs")
    for reference, (node, hashes) in expected_refs.items():
        component = files[reference]
        declared = {item.get("alg"): item.get("content") for item in component.get("hashes", [])}
        if declared != {"SHA-1": hashes["sha1"], "SHA-256": hashes["sha256"], "SHA-512": hashes["sha512"]}:
            raise AdapterError(f"the SBOM of {target} records other hashes for {node}")
        if _properties(component, f"SBOM component {node}").get("quickskin:artifact-node") != node:
            raise AdapterError(f"the SBOM component {reference} names another node")
    graph = {entry["ref"]: entry.get("dependsOn", []) for entry in sbom["dependencies"]}
    if sorted(graph.get(root["bom-ref"], [])) != sorted(expected_refs):
        raise AdapterError(f"the SBOM release of {target} does not depend on exactly its JARs")


# -- Runtime verification ------------------------------------------------------------------------------

#: The bounded evidence set the native lane uploads (``.github/actions/run-packaged-e2e/action.yml``),
#: below one profile directory: its ``result.json`` and every file below a directory of one of
#: these names (``client_a/screenshots/``, ``logs/``, ``server/crash-reports/``, ...).
PROFILE_TREES = ("logs", "e2e-report", "screenshots", "crash-reports")
LANE_FILES = ("summary.json", "resolved-matrix.json", "runtime-store.json")
_RESULT_REQUIRED = ("artifact_node", "runtime_version", "loader", "scenario", "contract_sha256", "jar_sha256", "port",
                    "status", "profile", "elapsed_s")
_RESOLVED_KEYS = ("artifact_node", "runtime_version", "loader", "scenario", "jar_sha256", "port")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def verify_run(mod: Mod, lane: str, obligations: list[str], *, production: bytes, files: set[str],
               read: Callable[[str], bytes]) -> dict[str, Any]:
    """Verify the sealed native results of one lane (``read(path below lanes/<lane>/) -> bytes``,
    ``files`` every path there) and return what the report records.

    Every planned scenario must have exactly one passing result, in plan order, for this lane's
    row, this scenario contract and the sealed production JAR; each result's ``result.json`` must
    repeat it, no crash report may exist, every screenshot must be a PNG, and every file must
    belong to the lane's summary or to one of its scenario profiles."""

    row = runtime_rows(mod).get(lane)
    if row is None:
        raise AdapterError(f"lane {lane!r} has no PR runtime row")
    wanted = [item.removeprefix("scenario/") for item in obligations]
    if [obligation(item) for item in wanted] != obligations or wanted != scenarios(mod):
        raise AdapterError(f"the obligations of {lane} are not this contract's PR scenarios")
    if not set(LANE_FILES) <= files:
        raise AdapterError(f"lane {lane} did not record {sorted(set(LANE_FILES) - files)}")
    summary = _object(decode(read("summary.json"), "summary.json", max_bytes=MAX_NATIVE_REPORT_BYTES),
                      ("results", "runtime_store"), "summary.json")
    results = summary["results"]
    if type(results) is not list or [result.get("scenario") if type(result) is dict else None
                                     for result in results] != wanted:
        raise AdapterError(f"lane {lane} did not run exactly its planned scenarios in order")
    production_sha256 = sha256(production)
    profiles: dict[str, str] = {}
    for result in results:
        if not set(_RESULT_REQUIRED) <= set(result) or not set(result) <= {*_RESULT_REQUIRED, "reports", "error"}:
            raise AdapterError(f"a result of {lane} has an unknown shape")
        scenario = result["scenario"]
        identity = (result["artifact_node"], result["runtime_version"], result["loader"])
        if identity != (row["artifact_node"], row["runtime_version"], row["loader"]):
            raise AdapterError(f"{scenario} of {lane} ran another runtime row")
        if result["status"] != "pass" or "error" in result or type(result.get("reports")) is not dict:
            raise AdapterError(f"{scenario} of {lane} did not pass: {str(result.get('error'))[:400]}")
        if result["contract_sha256"] != mod.contract.sha256:
            raise AdapterError(f"{scenario} of {lane} ran another scenario contract")
        if result["jar_sha256"] != production_sha256:
            raise AdapterError(f"{scenario} of {lane} did not run the sealed production JAR")
        profile = result["profile"]
        if (type(profile) is not str or not profile.startswith("profiles/") or "/" in profile[len("profiles/"):]
                or profile in profiles.values()):
            raise AdapterError(f"{scenario} of {lane} names an unexpected profile")
        recorded = decode(read(f"{profile}/result.json"), f"{profile}/result.json", max_bytes=MAX_NATIVE_REPORT_BYTES)
        if recorded != result:
            raise AdapterError(f"{profile}/result.json differs from the lane summary")
        profiles[scenario] = profile
    resolved = _object(decode(read("resolved-matrix.json"), "resolved-matrix.json", max_bytes=MAX_NATIVE_REPORT_BYTES),
                       ("rows",), "resolved-matrix.json")
    if resolved["rows"] != [{key: result[key] for key in _RESOLVED_KEYS} for result in results]:
        raise AdapterError(f"resolved-matrix.json of {lane} differs from its results")
    if decode(read("runtime-store.json"), "runtime-store.json", max_bytes=MAX_NATIVE_REPORT_BYTES) != summary["runtime_store"]:
        raise AdapterError(f"runtime-store.json of {lane} differs from its summary")
    accounted = set(LANE_FILES)
    screenshots = 0
    for path in sorted(files - accounted):
        parts = path.split("/")
        profile = "/".join(parts[:2])
        if profile not in profiles.values() or len(parts) < 3:
            raise AdapterError(f"lane {lane} left {path}, which no scenario profile accounts for")
        directories = parts[2:-1]
        if not directories and parts[2] == "result.json":
            continue
        if not any(directory in PROFILE_TREES for directory in directories):
            raise AdapterError(f"lane {lane} left {path} outside the native evidence trees")
        if "crash-reports" in directories:
            raise AdapterError(f"lane {lane} produced a crash report: {path}")
        if path.endswith(".png"):
            if read(path)[:8] != PNG_SIGNATURE:
                raise AdapterError(f"{path} is not a PNG")
            screenshots += 1
    return {"obligations": obligations, "production_sha256": production_sha256, "summary_sha256": sha256(read("summary.json")),
            "row_id": row["id"], "scenarios": {scenario: profiles[scenario] for scenario in wanted},
            "screenshots": screenshots, "files": sorted(files)}
