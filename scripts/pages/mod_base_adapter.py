"""Quick Skin's mod-base adapter (``ADAPTER_API = 1``, mod-base ``docs/ADAPTER.md``).

The kit loads this module in an isolated ``env -i`` child whose ``PYTHONPATH`` is the kit ``src``
followed by ``config.adapter.python_path`` (``site/mod-base.json``) and re-verifies every result
(R1-R6) before a byte is published. The hooks wrap Quick Skin's own modules and copy none of them:

* ``targets``: one ``mc<version>`` key per release-matrix target (``evidence_target.inventory``),
  newest Minecraft version first (the order the landing page and gallery show). Every subject is
  the protected head (``default-branch`` mode), whose committed release matrix and scenario
  contract must equal the checked-out bytes the Quick Skin readers parse.
* ``expectation``: the ``pr`` profile of ``e2e/scenario-contract.json`` for the target's artifact
  nodes (``scenario_contract``, ``evidence_target.target_for_key``): lanes ``<node>/<scenario>``,
  frames ``<node>/<scenario>/<role>/<step>`` and comparisons ``.../<role>/<first>:<second>``. A
  ``quick-skin.feature_selection`` extension narrows it to the recomputed selection
  (``feature_evidence.read_selection``, ``selection.project_contract``): ``scope.kind: "selected"``
  whose detail is the selection's capture obligations. The complete ``mc<unit_test_version>``
  expectation declares its Fabric and Forge nodes as the lossless anchor (ADR 0004 continuity).
* ``collect``: ``visual_evidence.collect_evidence`` over the packaged ``profiles/**/result.json``
  (``installed_quickskin``, report steps and every recorded pixel check stay Quick Skin
  validation); the recorded ``pixel_validation`` metrics are the ``reported`` values mod-base
  cross-checks against its own measurement. Every lane must be the target's exact artifact node,
  Minecraft version, loader and scenario, and every scenario of one artifact node must have run
  one production JAR.
* ``authenticate_extensions``: ``runtime_source`` is bound inertly to the manifest's tested claim
  (``ci_reuse.validate_reference``) and to the handoff generation's own reuse descriptor; the
  feature selection is recomputed and bound to the selected scope and tested commit.
* ``compose``: ``feature_pages.compose_selected`` authenticates the original runtime, the
  selection's coverage certificate and the retained ``mb-baseline`` generation before
  ``feature_evidence.compose`` completes the selected evidence.
* ``verify_publication``: ``feature_pages.verify_runtime_tree`` reauthenticates, once each, the
  reused runtime generations whose evidence the draft publishes, before mod-base renders the site.
* ``family_validate``: the ``mod-compatibility`` family, whose native bundle is a shared-source
  (schema 6) ``compatibility_evidence`` bundle, projected with
  ``compatibility_evidence.project_paired`` (see :func:`family_validate`).
* ``anchor_selection``: the expectation's declared anchor.

``expected_source_jobs`` asserts no graph (``source.require_job_graph`` is false: Quick Skin's
source authentication keeps ``created_at``, the event, the workflow path and the reuse proof).
"""

from __future__ import annotations

import os
import shutil
import subprocess
import urllib.parse
from pathlib import Path
from typing import Any

from mod_base.imaging.metrics import SizePolicy, inspect_webp
from mod_base.io.atomic_directory import write_new
from mod_base.io.tree import read_child_file
from mod_base.model import limits
from mod_base.model.canonical import canonical_json, canonical_sha256, sha256_hex, strict_loads
from mod_base.model.documents import validate_family_envelope, validate_family_paired

ADAPTER_API = 1

MATRIX_PATH = "release/release-matrix.json"
SCENARIO_CONTRACT_PATH = "e2e/scenario-contract.json"
COMPATIBILITY_CONTRACT_PATH = "e2e/mod-compatibility-contract.json"
PROFILE = "pr"
RUNTIME_SOURCE = "quick-skin.runtime_source"
FEATURE_SELECTION = "quick-skin.feature_selection"
COMPATIBILITY_FAMILY = "mod-compatibility"
ENVELOPE_NAME = "envelope.json"
PROJECTION_NAME = "paired.json"
NATIVE_DIRECTORY = "native"
MAX_MATRIX_BYTES = 5 * 1024 * 1024
MAX_CONTRACT_BYTES = 1024 * 1024
GIT_TIMEOUT_SECONDS = 120
GITHUB_PREFIX = "https://github.com/"
_DIRECTORY_FLAGS = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)


class AdapterError(RuntimeError):
    """A Quick Skin input the kit must not publish: the hook fails closed (exit 2)."""


# -- Git on inert objects --------------------------------------------------------------------------


def _git(ctx: Any, *arguments: str) -> subprocess.CompletedProcess[bytes]:
    """One read-only git call on the mod checkout: no hooks, replace refs, lazy fetch, prompts,
    optional locks, or system and global configuration."""

    executable = shutil.which("git", path="/usr/bin:/bin:/usr/local/bin") or shutil.which("git")
    if executable is None:
        raise AdapterError("git is not available to read inert objects")
    environment = {
        "PATH": "/usr/bin:/bin:/usr/local/bin",
        "HOME": str(ctx.tmpdir),
        "LANG": "C",
        "LC_ALL": "C",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_TERMINAL_PROMPT": "0",
        "GIT_NO_REPLACE_OBJECTS": "1",
        "GIT_NO_LAZY_FETCH": "1",
        "GIT_OPTIONAL_LOCKS": "0",
    }
    try:
        return subprocess.run(
            [executable, "--no-replace-objects", "-C", str(ctx.repo_root), *arguments],
            env=environment, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=GIT_TIMEOUT_SECONDS, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AdapterError(f"git {arguments[0]} failed: {exc}") from exc


def _commit_tree(ctx: Any, commit: str) -> str:
    completed = _git(ctx, "rev-parse", "--verify", "--end-of-options", f"{commit}^{{tree}}")
    tree = completed.stdout.decode("ascii", "replace").strip()
    if completed.returncode != 0 or len(tree) != 40 or any(char not in "0123456789abcdef" for char in tree):
        raise AdapterError(f"the tree of {commit} is not in the local object store")
    return tree


def _is_ancestor(ctx: Any, ancestor: str, descendant: str) -> bool:
    """``ancestor`` is ``descendant`` or one of its ancestors; a commit missing from the local
    object store proves nothing and answers ``False``."""

    if ancestor == descendant:
        return True
    for commit in (ancestor, descendant):
        if _git(ctx, "cat-file", "-e", f"{commit}^{{commit}}").returncode != 0:
            return False
    completed = _git(ctx, "merge-base", "--is-ancestor", ancestor, descendant)
    if completed.returncode not in (0, 1):
        raise AdapterError(f"git merge-base failed for {ancestor}..{descendant}")
    return completed.returncode == 0


def _committed_file(ctx: Any, commit: str, relative: str, maximum: int) -> bytes:
    """``relative`` at ``commit``, which must equal the checked-out file the QS readers parse."""

    committed = ctx.read_blob(commit, relative, maximum)
    if read_child_file(Path(ctx.repo_root), relative, max_bytes=maximum) != committed:
        raise AdapterError(f"the checked-out {relative} differs from the protected head {commit}")
    return committed


# -- targets -----------------------------------------------------------------------------------------


def _version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def targets(ctx: Any, branches: list[dict[str, Any]] | None) -> list[dict[str, Any]]:
    """Every release-matrix target as ``mc<version>``, newest Minecraft version first."""

    import evidence_target

    if branches is not None:
        raise AdapterError("Quick Skin publishes in default-branch mode and enrolls no branches")
    commit = ctx.implementation_sha
    root = Path(ctx.repo_root)
    matrix = _committed_file(ctx, commit, MATRIX_PATH, MAX_MATRIX_BYTES)
    contract = _committed_file(ctx, commit, SCENARIO_CONTRACT_PATH, MAX_CONTRACT_BYTES)
    rows = evidence_target.inventory(root / MATRIX_PATH)["include"]
    if read_child_file(root, MATRIX_PATH, max_bytes=MAX_MATRIX_BYTES) != matrix:
        raise AdapterError(f"{MATRIX_PATH} changed while its targets were derived")
    subject = {"branch": ctx.config.canonical_branch, "commit": commit, "tree": _commit_tree(ctx, commit)}
    prefix = ctx.config.labels["release_prefix"]
    result = []
    for row in rows:
        version = row["minecraft_target"]
        if not version or row["bundle_key"] != f"mc{version}" or row["source_branch"] != subject["branch"]:
            raise AdapterError(f"matrix target {row['bundle_key']!r} is not a shared-source target of "
                               f"{subject['branch']}")
        result.append({"key": row["bundle_key"], "label": f"{prefix} {version}", "subject": dict(subject),
                       "matrix_sha256": sha256_hex(matrix), "contract_sha256": sha256_hex(contract)})
    return sorted(result, key=lambda target: _version_key(target["key"][2:]), reverse=True)


# -- expectation ---------------------------------------------------------------------------------


def _repository(matrix: dict[str, Any]) -> str:
    sources = matrix["project"]["sources"]
    if not isinstance(sources, str) or not sources.startswith(GITHUB_PREFIX):
        raise AdapterError("the release matrix names no GitHub source repository")
    repository = sources[len(GITHUB_PREFIX):].rstrip("/")
    if repository.count("/") != 1:
        raise AdapterError("the release matrix source URL is not a repository URL")
    return repository


def _checked_contract(ctx: Any, target: dict[str, Any]) -> tuple[Any, dict[str, Any]]:
    """The scenario contract and the complete release matrix of ``target``'s subject, both bound
    to the committed bytes and to the hashes the target names."""

    import scenario_contract

    commit = target["subject"]["commit"]
    root = Path(ctx.repo_root)
    matrix_bytes = _committed_file(ctx, commit, MATRIX_PATH, MAX_MATRIX_BYTES)
    contract_bytes = _committed_file(ctx, commit, SCENARIO_CONTRACT_PATH, MAX_CONTRACT_BYTES)
    if sha256_hex(matrix_bytes) != target["matrix_sha256"] or sha256_hex(contract_bytes) != target["contract_sha256"]:
        raise AdapterError("the release matrix or scenario contract differs from the target's hashes")
    contract = scenario_contract.load_contract(root / SCENARIO_CONTRACT_PATH)
    if contract.sha256 != target["contract_sha256"]:
        raise AdapterError("the loaded scenario contract is not the committed one")
    if list(contract.screenshot_size) != ctx.config.images["source_size"]:
        raise AdapterError("the contract screenshot size differs from config.images.source_size")
    return contract, strict_loads(matrix_bytes, label=MATRIX_PATH, max_bytes=MAX_MATRIX_BYTES)


def _selection(ctx: Any, extensions: dict[str, Any]) -> Any:
    """The recomputed feature selection of ``extensions``, or ``None`` for complete evidence."""

    import feature_evidence

    if FEATURE_SELECTION not in extensions:
        return None
    try:
        return feature_evidence.read_selection(extensions[FEATURE_SELECTION],
                                               catalog_path=Path(ctx.repo_root) / SCENARIO_CONTRACT_PATH)
    except (feature_evidence.FeatureEvidenceError, ValueError) as exc:
        raise AdapterError(f"the feature selection cannot be recomputed: {exc}") from exc


def expectation(ctx: Any, target: dict[str, Any], tested_run: dict[str, Any] | None,
                extensions: dict[str, Any]) -> dict[str, Any]:
    """The ``pr``-profile expectation of ``target`` (see the module docstring). ``tested_run`` never
    changes it: every Quick Skin projection is the pull-request profile."""

    import evidence_target
    import feature_evidence
    from selection import project_contract

    contract, matrix = _checked_contract(ctx, target)
    try:
        evidence = evidence_target.target_for_key(target["key"], Path(ctx.repo_root) / MATRIX_PATH)
    except evidence_target.EvidenceTargetError as exc:
        raise AdapterError(str(exc)) from exc
    if evidence.branch != target["subject"]["branch"] or evidence.matrix_sha256 != target["matrix_sha256"]:
        raise AdapterError("the target's matrix view names another branch or matrix")
    chosen = _selection(ctx, extensions)
    scope: dict[str, Any] = {"kind": "complete"}
    view = contract
    if chosen is not None:
        view = project_contract(contract, chosen)
        detail = feature_evidence.selection_detail(chosen)
        scope = {"kind": "selected", "detail": detail, "detail_sha256": canonical_sha256(detail)}
        scenarios = [scenario.scenario for scenario in view.scenarios]
    else:
        scenarios = list(contract.scenarios_for_profile(PROFILE))
    capture_order = {capture.capture_id: index for index, capture in enumerate(contract.captures)}
    lanes, captures, comparisons = [], [], []
    artifacts = sorted(evidence.matrix["artifacts"], key=lambda row: (row["loader"], row["artifact_node"]))
    for artifact in artifacts:
        node = artifact["artifact_node"]
        for name in scenarios:
            scenario = view.scenario(name)
            lane_id = f"{node}/{name}"
            lanes.append({"lane_id": lane_id, "artifact_node": node, "minecraft": artifact["artifact_version"],
                          "loader": artifact["loader"], "java": artifact["java"], "scenario": name,
                          "roles": [role.role for role in scenario.roles]})
            for role in scenario.roles:
                for step in role.steps:
                    if step.capture is None:
                        continue
                    captures.append({
                        "frame_id": f"{lane_id}/{role.role}/{step.id}", "capture_id": step.capture.capture_id,
                        "capture_order": capture_order[step.capture.capture_id], "lane_id": lane_id,
                        "role": role.role, "step": step.id, "title": step.capture.title,
                        "expectation": step.capture.expectation, "review_tier": step.capture.review_tier,
                    })
                for pair in role.comparisons:
                    record = {
                        "comparison_id": f"{lane_id}/{role.role}/{pair.first_step}:{pair.second_step}",
                        "lane_id": lane_id, "role": role.role,
                        "first_frame_id": f"{lane_id}/{role.role}/{pair.first_step}",
                        "second_frame_id": f"{lane_id}/{role.role}/{pair.second_step}",
                        "minimum_changed_fraction": pair.minimum_changed_fraction,
                    }
                    if pair.region is not None:
                        record["region"] = list(pair.region)
                    comparisons.append(record)
    anchor = None
    if chosen is None and evidence.version == matrix["unit_test_version"]:
        anchor = {"artifact_nodes": sorted(row["artifact_node"] for row in artifacts)}
    return {
        "kind": "mod-base.evidence.expectation", "schema_version": 1, "repository": _repository(matrix),
        "key": target["key"], "label": target["label"], "subject": dict(target["subject"]),
        "matrix_sha256": target["matrix_sha256"], "contract_sha256": target["contract_sha256"],
        "contract_path": SCENARIO_CONTRACT_PATH, "profile": PROFILE, "scope": scope,
        "image_policy": ctx.config.image_policy(), "scenarios": [{"id": name} for name in scenarios],
        "lanes": lanes, "captures": captures, "comparisons": comparisons, "anchor": anchor,
    }


# -- collect ----------------------------------------------------------------------------------------


def _catalog(ctx: Any, expectation: dict[str, Any]) -> Any:
    """Quick Skin's validation catalog of ``expectation``: the checked-out contract, projected onto
    the selected scope when the expectation is ``selected``."""

    import feature_evidence
    from visual_evidence import load_catalog

    contract_path = Path(ctx.repo_root) / SCENARIO_CONTRACT_PATH
    selection = None
    if expectation["scope"]["kind"] == "selected":
        detail = expectation["scope"]["detail"]
        if canonical_sha256(detail) != expectation["scope"]["detail_sha256"]:
            raise AdapterError("the selected scope detail differs from its digest")
        selection = feature_evidence.SelectionView.from_detail(detail)
    catalog = load_catalog(contract_path, selection=selection)
    if catalog.contract_sha256 != expectation["contract_sha256"]:
        raise AdapterError("the checked-out scenario contract is not the expectation's contract")
    return catalog


def collect(ctx: Any, runtime_root: str, target: dict[str, Any], expectation: dict[str, Any]) -> dict[str, Any]:
    """The packaged frames of ``expectation`` in ``runtime_root`` (Quick Skin's own validation)."""

    from visual_evidence import VisualEvidenceError, collect_evidence

    root = Path(runtime_root)
    catalog = _catalog(ctx, expectation)
    nodes = frozenset(lane["artifact_node"] for lane in expectation["lanes"])
    try:
        lanes, frames, comparisons = collect_evidence(root, catalog, artifact_nodes=nodes)
    except VisualEvidenceError as exc:
        raise AdapterError(f"packaged evidence is invalid: {exc}") from exc
    resolved = root.resolve()
    frames_by_id = {frame["frame_id"]: frame for frame in frames}
    lanes_by_id = {lane["lane_id"]: lane for lane in lanes}
    comparisons_by_id = {item["comparison_id"]: item for item in comparisons}
    if set(lanes_by_id) != {lane["lane_id"] for lane in expectation["lanes"]}:
        raise AdapterError("the packaged lanes differ from the expectation's lanes")
    if set(frames_by_id) != {capture["frame_id"] for capture in expectation["captures"]}:
        raise AdapterError("the packaged frames differ from the expectation's captures")
    files: set[str] = set()
    result_frames = []
    profiles: dict[str, set[str]] = {}
    for capture in expectation["captures"]:
        frame = frames_by_id[capture["frame_id"]]
        if (frame["capture_id"], frame["role"], frame["step"]) != (capture["capture_id"], capture["role"],
                                                                  capture["step"]):
            raise AdapterError(f"packaged frame {capture['frame_id']} has another capture identity")
        source = Path(frame["source_path"]).relative_to(resolved).as_posix()
        files.add(source)
        profiles.setdefault(capture["lane_id"], set()).add(source.rsplit("/", 3)[0])
        result_frames.append({"frame_id": capture["frame_id"], "source_path": source,
                              "runtime_evidence": frame["runtime_evidence"],
                              "reported_pixel": frame["pixel_validation"]})
    result_lanes = []
    jars: dict[str, str] = {}
    for wanted in expectation["lanes"]:
        lane = lanes_by_id[wanted["lane_id"]]
        # The exact (artifact node, Minecraft version, loader, scenario) product of the target,
        # and one production JAR per artifact node across all of its scenarios.
        if ((lane["artifact_node"], lane["version"], lane["loader"], lane["scenario"])
                != (wanted["artifact_node"], wanted["minecraft"], wanted["loader"], wanted["scenario"])
                or sorted(lane["roles"]) != sorted(wanted["roles"])):
            raise AdapterError(f"packaged lane {wanted['lane_id']} ran another Minecraft version, loader, "
                               "scenario or role set")
        if jars.setdefault(wanted["artifact_node"], lane["jar_sha256"]) != lane["jar_sha256"]:
            raise AdapterError(f"the packaged scenarios of {wanted['artifact_node']} used different production JARs")
        directories = profiles.get(wanted["lane_id"], set())
        if len(directories) != 1:
            raise AdapterError(f"lane {wanted['lane_id']} has no single packaged profile")
        files.add(f"{next(iter(directories))}/result.json")
        result_lanes.append({"lane_id": wanted["lane_id"], "java": wanted["java"], "profile": PROFILE,
                             "status": "pass", "elapsed_s": float(lane["elapsed_s"]),
                             "jars": {"production_sha256": lane["jar_sha256"]}})
    result_comparisons = []
    for wanted in expectation["comparisons"]:
        first, second = wanted["comparison_id"].rsplit("/", 1)[1].split(":")
        packaged = comparisons_by_id.get(f"{wanted['lane_id']}/{wanted['role']}/{first}->{second}")
        if packaged is None:
            raise AdapterError(f"packaged comparison {wanted['comparison_id']} is missing")
        result_comparisons.append({"comparison_id": wanted["comparison_id"], "reported": packaged["pixel_validation"]})
    return {"runtime_files": sorted(files), "lanes": result_lanes, "frames": result_frames,
            "comparisons": result_comparisons}


def expected_source_jobs(ctx: Any, expectation: dict[str, Any], tested_run: dict[str, Any]) -> None:
    """Quick Skin asserts no source job graph (``source.require_job_graph`` is false)."""

    return None


def anchor_selection(ctx: Any, expectation: dict[str, Any]) -> dict[str, Any] | None:
    """The complete ``mc<unit_test_version>`` Fabric and Forge nodes (the expectation's anchor)."""

    return expectation["anchor"]


# -- network hooks ------------------------------------------------------------------------------------


def _kit_api(ctx: Any) -> Any:
    """Quick Skin's GitHub client over the kit's read-only, budgeted ``ctx.api``."""

    import feature_coverage_github as publisher

    if ctx.api is None:
        raise AdapterError("this hook needs the read-only GitHub API")

    class KitApi(publisher.Api):
        def __init__(self) -> None:
            super().__init__(ctx.api.repository)

        def json(self, endpoint: str) -> Any:
            parts = urllib.parse.urlsplit(endpoint)
            params = dict(urllib.parse.parse_qsl(parts.query, keep_blank_values=True, strict_parsing=bool(parts.query)))
            value = ctx.api.get_json(f"/{self.prefix}{parts.path}", params=params or None)
            publisher.coverage.admission.canonical(value)
            return value

        def archive(self, metadata: dict[str, Any], *, maximum: int) -> bytes:
            raw = ctx.api.download(f"/{self.prefix}actions/artifacts/{metadata['id']}/zip", max_bytes=maximum)
            return publisher.check_archive(raw, metadata)

    return KitApi()


def authenticate_extensions(ctx: Any, manifest: dict[str, Any], extensions: dict[str, Any]) -> dict[str, Any]:
    """Verify every declared Quick Skin extension (R6); raise to fail closed."""

    import feature_pages

    unknown = set(extensions) - {RUNTIME_SOURCE, FEATURE_SELECTION}
    if unknown:
        raise AdapterError(f"unknown extensions {sorted(unknown)}")
    try:
        if RUNTIME_SOURCE in extensions:
            feature_pages.verify_reuse_extension(_kit_api(ctx), manifest, extensions[RUNTIME_SOURCE])
        elif manifest["provenance"]["reuse"] != "none":
            raise AdapterError("only a runtime_source reference can prove Quick Skin runtime reuse")
        if FEATURE_SELECTION in extensions:
            chosen = _selection(ctx, extensions)
            feature_pages.verify_selection_extension(manifest, extensions, chosen)
    except ValueError as exc:
        raise AdapterError(str(exc)) from exc
    return {"verified": sorted(extensions), "reuse_verified": RUNTIME_SOURCE in extensions}


def _document(root: Path, relative: str, maximum: int) -> dict[str, Any]:
    raw = read_child_file(root, relative, max_bytes=maximum)
    value = strict_loads(raw, label=relative, max_bytes=maximum)
    if not isinstance(value, dict) or canonical_json(value) != raw:
        raise AdapterError(f"{relative} is not a canonical JSON object")
    return value


def compose(ctx: Any, key: str, selected_compact_dir: str, output_dir: str) -> dict[str, Any]:
    """Complete selected evidence with its authenticated retained baseline (``feature_pages``)."""

    import feature_pages

    selected = Path(selected_compact_dir)
    manifest = _document(selected, "manifest.json", limits.MAX_MANIFEST_BYTES)
    extensions = (_document(selected, "extensions.json", limits.MAX_EXTENSIONS_BYTES)
                  if manifest.get("extensions") is not None else {})
    if FEATURE_SELECTION not in extensions or manifest["scope"]["kind"] != "selected":
        raise AdapterError("only feature-selected evidence is composed")
    composed_extensions = {name: value for name, value in extensions.items() if name != FEATURE_SELECTION}
    embedded = _document(selected, "expectation.json", limits.MAX_EXPECTATION_BYTES)
    target = {name: embedded[name] for name in ("key", "label", "subject", "matrix_sha256", "contract_sha256")}
    complete = expectation(ctx, target, None, composed_extensions)
    try:
        baseline = feature_pages.compose_selected(
            _kit_api(ctx), repository=Path(ctx.repo_root), key=key, manifest=manifest, selected_root=selected,
            output_root=Path(output_dir), extensions=extensions, complete_expectation=complete,
            composed_extensions=composed_extensions, scratch=Path(ctx.tmpdir))
    except ValueError as exc:
        raise AdapterError(str(exc)) from exc
    return {"baseline_artifact": baseline}


def verify_publication(ctx: Any, promotion_draft: dict[str, Any]) -> None:
    """Reauthenticate the reused runtime generations the draft publishes before render."""

    import feature_pages

    try:
        feature_pages.verify_runtime_tree(_kit_api(ctx), promotion_draft, source_sha=ctx.implementation_sha,
                                          scratch=Path(ctx.tmpdir))
    except ValueError as exc:
        raise AdapterError(str(exc)) from exc


# -- family_validate ----------------------------------------------------------------------------------


def _reason(text: str) -> str:
    """A kit reason: one line of at most 200 characters."""

    line = " ".join(text.split())
    return line if len(line) <= limits.MAX_REASON_LENGTH else line[: limits.MAX_REASON_LENGTH - 1] + "…"


def _absent(status: str, reason: str) -> dict[str, Any]:
    return {"status": status, "reason": _reason(reason)}


def _read_envelope(source: Path, configured: dict[str, Any], *, family: str, key: str) -> dict[str, Any]:
    import compatibility_evidence

    raw = read_child_file(source, ENVELOPE_NAME, max_bytes=limits.MAX_ENVELOPE_BYTES)
    envelope = validate_family_envelope(strict_loads(raw, label=ENVELOPE_NAME, max_bytes=limits.MAX_ENVELOPE_BYTES),
                                        max_total_bytes=configured["handoff_max_bytes"])
    native = envelope["native"]
    if envelope["family"] != family or envelope["key"] != key:
        raise AdapterError(f"the envelope wraps {envelope['family']}/{envelope['key']}, not {family}/{key}")
    if (native["kind"], native["schema_version"]) != (compatibility_evidence.KIND,
                                                      compatibility_evidence.SHARED_SCHEMA_VERSION):
        raise AdapterError(f"the native bundle is {native['kind']} v{native['schema_version']}, not a shared-source "
                           "compatibility bundle")
    return envelope


def _stage_native(source: Path, envelope: dict[str, Any], destination: Path) -> None:
    """Copy exactly the envelope inventory into the new private ``destination`` (plus the
    ``images`` directory an image-free generation still declares); ``source`` is only read, each
    file without following a symlink and bound to its recorded size and hash."""

    destination.parent.mkdir(mode=0o700)
    destination.mkdir(mode=0o700)
    (destination / "images").mkdir(mode=0o700)
    descriptor = os.open(destination, _DIRECTORY_FLAGS)
    try:
        for record in envelope["files"]:
            data = read_child_file(source, record["path"], max_bytes=limits.MAX_SOURCE_PNG_BYTES)
            if len(data) != record["size"] or sha256_hex(data) != record["sha256"]:
                raise AdapterError(f"native file {record['path']} differs from its envelope record")
            write_new(descriptor, record["path"], data)
    finally:
        os.close(descriptor)


def _producer_record(envelope: dict[str, Any], publication_run: dict[str, Any]) -> dict[str, Any]:
    """The producer ``RunRecord``: the envelope's claim plus the publication run's recorded API facts."""

    claim = envelope["producer"]
    record = {**claim, "event": publication_run["event"], "created_at": publication_run["created_at"],
              "conclusion": "success", "head_sha": claim["commit"]}
    if "display_title" in publication_run:
        record["display_title"] = publication_run["display_title"]
    return record


def _carry_forward(ctx: Any, configured: dict[str, Any], coverage: str,
                   expected: str) -> tuple[str | None, dict[str, Any]]:
    """``(refusal, facts)``: a reason not to carry ``coverage`` to ``expected``, or ``None`` and the
    result fields of an admitted carry-forward (``carried_from``, ``impact_paths_sha256``)."""

    from mod_compatibility_impact import ImpactError, classify_paths, git_diff_paths

    if not configured["carry_forward"]:
        return f"family {configured['id']} does not carry evidence forward", {}
    if not _is_ancestor(ctx, coverage, expected):
        return f"the evidence for {coverage} does not belong to the lineage of {expected}", {}
    try:
        classification = classify_paths(git_diff_paths(Path(ctx.repo_root), coverage, expected))
    except ImpactError as exc:
        return f"the diff {coverage}..{expected} cannot be classified: {exc}", {}
    if classification.compatibility_required:
        return (f"compatibility-impacting changes since {coverage} supersede the evidence "
                f"({len(classification.impact_paths)} paths)"), {}
    return None, {"carried_from": coverage, "impact_paths_sha256": canonical_sha256(classification.manifest())}


def _inspect_derivative(data: bytes, width: int, height: int) -> dict[str, Any]:
    return inspect_webp(data, SizePolicy.exact(width, height))


def family_validate(ctx: Any, family: str, key: str, bundle_dir: str, expected_coverage_sha: str,
                    output_dir: str) -> dict[str, Any]:
    """Validate one ``mod-compatibility`` generation and project it onto ``mod-base.family.paired``.

    The envelope inventory is copied into a private directory (``bundle_dir`` is only read),
    validated with ``compatibility_evidence.validate_bundle`` against the checked-out contracts
    and matrix, and bound to its envelope (coverage, repository, branch and publication run).
    Contract or matrix drift is ``superseded``; a broken tested lineage, a refused carry-forward
    (disabled, non-ancestor, unclassifiable or compatibility-impacting diff,
    ``mod_compatibility_impact``) or a bundle without its recorded publication run is
    ``unavailable``. An available generation writes exactly ``paired.json`` and
    ``images/<sha256>.webp``; its subject and producer claim come from the envelope, the producer
    ``RunRecord`` adds the publication run's recorded API facts, every derivative carries the
    kit's own ``inspect_webp`` metrics, and ``carried_from`` is the envelope coverage exactly when
    it differs from ``expected_coverage_sha``."""

    import compatibility_evidence

    if family != COMPATIBILITY_FAMILY:
        raise AdapterError(f"Quick Skin defines no family {family!r}")
    configured = ctx.config.family(family)
    if expected_coverage_sha != ctx.implementation_sha:
        raise AdapterError("default-branch family coverage must be the protected head this job checked out")
    root = Path(ctx.repo_root)
    source = Path(bundle_dir)
    output = Path(output_dir)
    if any(output.iterdir()):
        raise AdapterError("the projection output directory must start empty")
    envelope = _read_envelope(source, configured, family=family, key=key)
    native_root = Path(ctx.tmpdir) / NATIVE_DIRECTORY
    bundle = native_root / key
    _stage_native(source, envelope, bundle)
    try:
        manifest = compatibility_evidence.validate_bundle(
            native_root, key, expected_repository=envelope["repository"], only_branch=True,
            scenario_contract_path=root / SCENARIO_CONTRACT_PATH,
            compatibility_contract_path=root / COMPATIBILITY_CONTRACT_PATH, matrix_path=root / MATRIX_PATH)
    except compatibility_evidence.CompatibilityContractDriftError as exc:
        return _absent("superseded", f"compatibility evidence binds a superseded contract: {exc}")
    provenance = manifest["provenance"]
    coverage = envelope["coverage_sha"]
    if (provenance["coverage_sha"] != coverage or provenance["publication_run_id"] != envelope["producer"]["run_id"]
            or manifest["release"]["branch"] != envelope["subject"]["branch"]):
        raise AdapterError("the native compatibility manifest is not its envelope's generation (coverage, "
                           "publication run or branch differs)")
    publication_run = provenance.get("publication_run")
    if publication_run is None:
        return _absent("unavailable", "the compatibility bundle records no publication run for its producer")
    if publication_run["event"] not in configured["producer"]["events"]:
        raise AdapterError(f"the publication run event {publication_run['event']!r} is not a configured producer "
                           f"event of {family}")
    if not _is_ancestor(ctx, provenance["target_sha"], coverage):
        return _absent("unavailable", f"the tested commit {provenance['target_sha']} is not in the lineage of the "
                                      f"evidence coverage {coverage}")
    result: dict[str, Any] = {"status": "available", "reason": "complete clean compatibility wave",
                              "projection_path": PROJECTION_NAME}
    if coverage != expected_coverage_sha:
        refusal, carried = _carry_forward(ctx, configured, coverage, expected_coverage_sha)
        if refusal is not None:
            return _absent("unavailable", refusal)
        result.update(carried, reason=f"complete clean compatibility wave carried forward from {coverage}")
    descriptor = os.open(output, _DIRECTORY_FLAGS)
    try:
        projection = compatibility_evidence.project_paired(
            manifest, bundle=bundle, family=family, key=key, subject=envelope["subject"],
            coverage_sha=expected_coverage_sha, producer=_producer_record(envelope, publication_run),
            image_policy=configured["image_policy"], inspect_derivative=_inspect_derivative,
            write_image=lambda relative, data: write_new(descriptor, relative, data))
        validate_family_paired(projection, image_policy=configured["image_policy"])
        write_new(descriptor, PROJECTION_NAME, canonical_json(projection))
    finally:
        os.close(descriptor)
    return result
