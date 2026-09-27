"""Feature-selected public evidence: the recomputed selection and the composed mod-base bundle.

A selective E2E generation re-tests only the scenarios its authenticated Git admission requires.
Its mod-base handoff carries that admission in the ``quick-skin.feature_selection`` extension and
is ``selected`` evidence (``scope.detail`` is :func:`selection_detail`, the capture obligations
the admission recomputes). The adapter's ``compose`` hook completes it with the retained complete
baseline of the admission's base commit: :func:`compose` works per frame. It takes every
re-captured frame from the selected generation and every other frame from the baseline, marks each
with its ``epoch`` and the ``tested`` run its pixels came from, keeps each re-tested lane's record
from the selected generation (a lane whose checkpoints were only partly re-captured also records
the baseline execution of its older frames as ``baseline_run``) and every other lane's from the
baseline, and embeds the complete expectation. Compositions never consume compositions; the
GitHub authentication of the selection, runtime and baseline belongs to
``scripts/ci/feature_pages.py``, and mod-base re-verifies the result (R3) before it is published.

The kit (``mod_base``) is imported lazily inside :func:`compose`, so selection readers outside the
Pages path never need it.
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass, fields
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/architecture"))
sys.path.insert(0, str(ROOT / "e2e"))

import e2e_selection as admission  # noqa: E402
from module_graph import load_graph  # noqa: E402
from scenario_contract import DEFAULT_CONTRACT, load_contract  # noqa: E402
from selection import RoleSelection, ScenarioSelection, select  # noqa: E402

FEATURE_SELECTION = "quick-skin.feature_selection"
RUNTIME_SOURCE = "quick-skin.runtime_source"
PROFILE = "pr"
SHA1 = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
DETAIL_FIELDS = frozenset({"selection_sha256", "contract_sha256", "profile", "runs", "reference_captures"})
#: The baseline lane fields a partially re-captured composed lane keeps as ``baseline_run``.
BASELINE_RUN_FIELDS = frozenset({"profile", "status", "elapsed_s", "jars"})
MAX_SELECTION_BYTES = 1024 * 1024
MAX_DETAIL_ITEMS = 1024


class FeatureEvidenceError(ValueError):
    """Selected or composed public evidence cannot be admitted."""


def _commit(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA1.fullmatch(value) is None:
        raise FeatureEvidenceError(f"{label} must be a lowercase commit SHA")
    return value


def read_selection(feature: Any, *, catalog_path: Path = DEFAULT_CONTRACT) -> admission.Admission:
    """Recompute the capture scope; this is structural validation, not GitHub/Git authentication.

    ``feature`` is ``{admission, coverage}``: the protected E2E admission of a selective runtime
    and its coverage record. The selection is recomputed from the admitted changed paths with the
    executing contract and module graph and must be byte-equal to the admission."""

    try:
        raw = admission.canonical(feature)
    except (TypeError, ValueError) as exc:
        raise FeatureEvidenceError("public feature evidence is not finite JSON") from exc
    if len(raw) > MAX_SELECTION_BYTES:
        raise FeatureEvidenceError("public feature evidence exceeds its byte limit")
    feature = json.loads(raw)
    if not isinstance(feature, dict) or set(feature) != {"admission", "coverage"}:
        raise FeatureEvidenceError("public feature evidence has an invalid admission envelope")
    value, coverage = feature["admission"], feature["coverage"]
    names = {field.name for field in fields(admission.Admission)}
    if (not isinstance(value, dict) or set(value) != names | {"schema_version", "input_kind"}
            or type(value.get("schema_version")) is not int or value["schema_version"] != 1
            or value["input_kind"] != "git-diff" or value["diff_complete"] is not True
            or value["profile"] != PROFILE or not isinstance(value["selection"], dict)):
        raise FeatureEvidenceError("public feature selection lacks a complete Git admission")
    for field in ("base_commit", "head_commit", "policy_commit", "base_tree", "head_tree", "policy_tree"):
        _commit(value[field], f"selection.{field}")
    for field in ("policy_sha256", "diff_sha256"):
        if not isinstance(value[field], str) or not SHA256.fullmatch(value[field]):
            raise FeatureEvidenceError("public selection has an invalid policy or diff digest")
    paths = value["selection"].get("changed_paths")
    if not isinstance(paths, list):
        raise FeatureEvidenceError("public selection must declare its changed paths")
    selected = select(load_contract(catalog_path), load_graph(), paths, PROFILE)
    expected = admission.Admission(**{key: value[key] for key in names - {"selection"}}, selection=selected)
    if (selected.mode != "affected" or expected.reason != selected.reason
            or expected.to_bytes() != admission.canonical(value)
            or not isinstance(coverage, dict) or type(coverage.get("schema_version")) is not int
            or coverage["schema_version"] != 1 or coverage.get("selective") is not True
            or coverage.get("selection_sha256") != expected.sha256
            or not isinstance(coverage.get("baseline"), dict)
            or coverage["baseline"].get("source_sha") != expected.base_commit):
        raise FeatureEvidenceError("public selection differs from its recomputed obligations or baseline")
    return expected


def selection_detail(chosen: Any) -> dict[str, Any]:
    """The ``scope.detail`` of selected evidence: exactly the capture obligations of ``chosen``
    (a verified admission or a :class:`SelectionView`) plus the selection identity every packaged
    result of that runtime carries. It is bounded by the contract, never by the changed paths."""

    return {
        "selection_sha256": chosen.sha256,
        "contract_sha256": chosen.contract_sha256,
        "profile": chosen.profile,
        "runs": [{"scenario": run.scenario,
                  "roles": [{"role": role.role, "targets": list(role.targets), "steps": list(role.steps),
                             "captures": list(role.captures)} for role in run.roles]}
                 for run in chosen.runs],
        "reference_captures": list(chosen.reference_captures),
    }


def _identifiers(value: Any, label: str) -> tuple[str, ...]:
    if (not isinstance(value, list) or len(value) > MAX_DETAIL_ITEMS
            or any(not isinstance(item, str) or IDENTIFIER.fullmatch(item) is None for item in value)
            or len(set(value)) != len(value)):
        raise FeatureEvidenceError(f"selected scope {label} is malformed")
    return tuple(value)


@dataclass(frozen=True)
class SelectionView:
    """The ``SelectionPlan`` of a validated ``scope.detail`` (``e2e/selection.py`` protocol)."""

    sha256: str
    contract_sha256: str
    profile: str
    runs: tuple[ScenarioSelection, ...]
    reference_captures: tuple[str, ...]

    @classmethod
    def from_detail(cls, detail: Any) -> "SelectionView":
        if not isinstance(detail, dict) or set(detail) != DETAIL_FIELDS:
            raise FeatureEvidenceError("selected scope detail has an unknown shape")
        for field in ("selection_sha256", "contract_sha256"):
            if not isinstance(detail[field], str) or SHA256.fullmatch(detail[field]) is None:
                raise FeatureEvidenceError(f"selected scope {field} is not a SHA-256")
        if detail["profile"] != PROFILE or not isinstance(detail["runs"], list) or not detail["runs"]:
            raise FeatureEvidenceError("selected scope names no pull-request profile scenario")
        runs = []
        for run in detail["runs"]:
            if not isinstance(run, dict) or set(run) != {"scenario", "roles"} or not isinstance(run["roles"], list):
                raise FeatureEvidenceError("selected scope has a malformed scenario")
            roles = []
            for role in run["roles"]:
                if not isinstance(role, dict) or set(role) != {"role", "targets", "steps", "captures"}:
                    raise FeatureEvidenceError("selected scope has a malformed role")
                roles.append(RoleSelection(_identifiers([role["role"]], "role")[0],
                                           _identifiers(role["targets"], "targets"),
                                           _identifiers(role["steps"], "steps"),
                                           _identifiers(role["captures"], "captures")))
            runs.append(ScenarioSelection(_identifiers([run["scenario"]], "scenario")[0], tuple(roles)))
        return cls(detail["selection_sha256"], detail["contract_sha256"], PROFILE, tuple(runs),
                   _identifiers(detail["reference_captures"], "reference captures"))

    def role(self, scenario: str, role: str) -> RoleSelection:
        for run in self.runs:
            if run.scenario == scenario:
                for item in run.roles:
                    if item.role == role:
                        return item
        raise FeatureEvidenceError(f"role is not selected: {scenario}/{role}")


def _read(root: Path, relative: str, maximum: int) -> bytes:
    from mod_base.io.tree import read_child_file

    return read_child_file(root, relative, max_bytes=maximum)


def _document(root: Path, relative: str, maximum: int) -> tuple[dict[str, Any], bytes]:
    from mod_base.model.canonical import canonical_json, strict_loads

    raw = _read(root, relative, maximum)
    value = strict_loads(raw, label=relative, max_bytes=maximum)
    if not isinstance(value, dict) or canonical_json(value) != raw:
        raise FeatureEvidenceError(f"{relative} is not a canonical JSON object")
    return value, raw


def require_baseline_generation(baseline: dict[str, Any], *, branch: str, base_commit: str, baseline_run_id: int,
                                baseline_tested_run_id: int) -> None:
    """``baseline`` (a retained complete compact manifest) is the generation the coverage
    certificate names: it covers exactly ``base_commit`` on ``branch``, was published from a
    handoff, and its pixels were tested by ``baseline_tested_run_id`` (the run its retained name
    ``mb-baseline--<key>--<commit>--<tested run>`` carries).

    Its handoff is the certificate's generation ``baseline_run_id``, except for delegated reuse:
    two master generations of one head that reused the same merged pull-request execution publish
    the same tested pixels under one retained name, which mod-base retains once (from the first
    publication), while the certificate may name the other generation."""

    provenance = baseline["provenance"]
    handoff, tested = provenance["handoff"]["run_id"], provenance["tested"]["run_id"]
    if (baseline["subject"]["commit"] != base_commit or provenance["coverage_sha"] != base_commit
            or baseline["subject"]["branch"] != branch or baseline["source_artifact"]["kind"] != "handoff"
            or tested != baseline_tested_run_id
            or handoff != baseline_run_id and provenance["reuse"] != "delegated"):
        raise FeatureEvidenceError("the baseline is not the complete generation the selection was admitted against")


def compose(*, selected_root: Path, baseline_root: Path, output_root: Path, key: str,
            complete_expectation: dict[str, Any], composed_extensions: dict[str, Any],
            baseline_artifact: dict[str, Any], base_commit: str, baseline_run_id: int,
            baseline_tested_run_id: int) -> None:
    """Write the ``composed`` bundle of ``key`` into the existing empty ``output_root``.

    ``selected_root`` is mod-base's own compaction of the selected handoff (``scope.kind:
    "selected"``, its selection draft embedded); ``baseline_root`` is the extracted retained
    complete generation ``baseline_artifact`` (``{id, name, digest}``), which must cover exactly
    ``base_commit`` and be the complete runtime ``baseline_run_id`` the coverage certificate
    names, tested by ``baseline_tested_run_id`` (:func:`require_baseline_generation`).
    ``complete_expectation`` is the adapter's complete expectation of the
    subject, re-derived from ``composed_extensions`` (the selected extensions without the feature
    selection). Every frame the selection re-captured comes from the selected generation, every
    other frame from the baseline, each with the ``epoch`` and ``tested`` run (and that epoch's
    lane JAR) its pixels came from. A lane the selection re-tested is the selected generation's
    record; when the selection re-captured only some of its checkpoints (``hud-preview``
    re-captures 2 of the 63 ``full`` ones), it also carries ``baseline_run``: the baseline lane's
    ``profile``, ``status``, ``elapsed_s`` and ``jars`` its older frames were tested with. Every
    other lane is the baseline's record, and a comparison comes from the generation that holds its
    frames (it never spans two generations, because a selection always captures both partners).
    Nothing is relabelled, and mod-base re-verifies the result (R3)."""

    from mod_base.model import limits
    from mod_base.model.canonical import canonical_json, sha256_hex
    from mod_base.model.documents import compact_identity_sha256, validate_compact

    _commit(base_commit, "baseline commit")
    selected, _ = _document(selected_root, "manifest.json", limits.MAX_MANIFEST_BYTES)
    draft, draft_raw = _document(selected_root, "selection.json", limits.MAX_SELECTION_BYTES)
    baseline, baseline_raw = _document(baseline_root, "manifest.json", limits.MAX_MANIFEST_BYTES)
    baseline_selection, _ = _document(baseline_root, "selection.json", limits.MAX_SELECTION_BYTES)
    baseline_expectation, _ = _document(baseline_root, "expectation.json", limits.MAX_EXPECTATION_BYTES)
    validate_compact(baseline, expectation=baseline_expectation)
    if (selected.get("kind") != "mod-base.evidence.compact" or selected["scope"]["kind"] != "selected"
            or selected["key"] != key or baseline["key"] != key
            or baseline["scope"]["kind"] != "complete" or baseline["repository"] != selected["repository"]):
        raise FeatureEvidenceError("composition needs a selected compaction and a complete baseline of one key")
    require_baseline_generation(baseline, branch=selected["subject"]["branch"], base_commit=base_commit,
                                baseline_run_id=baseline_run_id, baseline_tested_run_id=baseline_tested_run_id)
    if complete_expectation["scope"]["kind"] != "complete" or complete_expectation["key"] != key:
        raise FeatureEvidenceError("composition embeds only the complete expectation of its key")
    selected_lanes = {lane["lane_id"]: lane for lane in selected["lanes"]}
    baseline_lanes = {lane["lane_id"]: lane for lane in baseline["lanes"]}
    selected_frames = {frame["frame_id"]: frame for frame in selected["frames"]}
    baseline_frames = {frame["frame_id"]: frame for frame in baseline["frames"]}
    selected_comparisons = {item["comparison_id"]: item for item in selected["comparisons"]}
    baseline_comparisons = {item["comparison_id"]: item for item in baseline["comparisons"]}
    sources = {"selected": (selected_root, draft, selected_lanes, selected_frames, selected_comparisons),
               "baseline": (baseline_root, baseline_selection, baseline_lanes, baseline_frames, baseline_comparisons)}

    def epoch_of(frame_id: str) -> str:
        return "selected" if frame_id in selected_frames else "baseline"

    frames, images = [], {}
    for capture in complete_expectation["captures"]:
        epoch = epoch_of(capture["frame_id"])
        root, selection, epoch_lanes, epoch_frames, _ = sources[epoch]
        if capture["frame_id"] not in epoch_frames or capture["lane_id"] not in epoch_lanes:
            raise FeatureEvidenceError(f"no generation holds frame {capture['frame_id']}")
        frame = {name: value for name, value in epoch_frames[capture["frame_id"]].items()
                 if name not in {"epoch", "tested"}}
        frame["epoch"] = epoch
        frame["tested"] = {**selection["source"]["tested_run"],
                           "jar_sha256": epoch_lanes[capture["lane_id"]]["jars"]["production_sha256"]}
        path = frame["derivative"]["path"]
        data = _read(root, path, limits.MAX_DERIVATIVE_BYTES)
        if sha256_hex(data) != frame["derivative"]["sha256"] or images.setdefault(path, data) != data:
            raise FeatureEvidenceError(f"derivative {path} differs from its frame record")
        frames.append(frame)
    baseline_held = {frame["lane_id"] for frame in frames if frame["epoch"] == "baseline"}
    lanes = []
    for wanted in complete_expectation["lanes"]:
        lane_id = wanted["lane_id"]
        pool = selected_lanes if lane_id in selected_lanes else baseline_lanes
        if lane_id not in pool:
            raise FeatureEvidenceError(f"no generation holds lane {lane_id}")
        lane = dict(pool[lane_id])
        if pool is selected_lanes and lane_id in baseline_held:
            # A partially re-captured lane: its record is the selected execution, and its baseline
            # frames keep the baseline execution they came from.
            lane["baseline_run"] = {name: value for name, value in baseline_lanes[lane_id].items()
                                    if name in BASELINE_RUN_FIELDS}
        lanes.append(lane)
    comparisons = []
    for wanted in complete_expectation["comparisons"]:
        epoch = epoch_of(wanted["first_frame_id"])
        if epoch_of(wanted["second_frame_id"]) != epoch or wanted["comparison_id"] not in sources[epoch][4]:
            raise FeatureEvidenceError(f"comparison {wanted['comparison_id']} spans generations or is missing")
        comparisons.append(sources[epoch][4][wanted["comparison_id"]])
    expectation_bytes = canonical_json(complete_expectation)
    files = {"expectation.json": expectation_bytes, "selection.json": draft_raw, **images}
    extensions_record = None
    if composed_extensions:
        extensions_bytes = canonical_json(composed_extensions)
        files["extensions.json"] = extensions_bytes
        extensions_record = {"path": "extensions.json", "sha256": sha256_hex(extensions_bytes),
                             "size": len(extensions_bytes), "names": sorted(composed_extensions)}
    composed = {
        **selected,
        "scope": {"kind": "composed", "components": {
            "baseline": {"artifact_id": baseline_artifact["id"], "name": baseline_artifact["name"],
                         "digest": baseline_artifact["digest"], "manifest_sha256": sha256_hex(baseline_raw)},
            "selected_manifest_sha256": compact_identity_sha256(selected)}},
        "expectation": {"path": "expectation.json", "sha256": sha256_hex(expectation_bytes),
                        "size": len(expectation_bytes)},
        "extensions": extensions_record,
        "selection": {"path": "selection.json", "sha256": sha256_hex(draft_raw), "size": len(draft_raw)},
        "lanes": lanes, "frames": frames, "comparisons": comparisons,
        "files": sorted(({"path": path, "sha256": sha256_hex(data), "size": len(data)}
                         for path, data in files.items()), key=lambda item: item["path"]),
    }
    files["manifest.json"] = canonical_json(composed)
    for path, data in sorted(files.items()):
        destination = output_root.joinpath(*path.split("/"))
        destination.parent.mkdir(mode=0o700, exist_ok=True)
        with destination.open("xb") as stream:
            stream.write(data)


def selection_digest(detail: dict[str, Any]) -> str:
    """``scope.detail_sha256``: the SHA-256 of the canonical detail (``mod_base`` rule)."""

    from mod_base.model.canonical import canonical_sha256

    return canonical_sha256(detail)
