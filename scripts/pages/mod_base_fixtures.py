"""Test-only fixtures of Quick Skin's mod-base adapter (``config.adapter.fixtures_path``).

mod-base's ``conformance`` loads this module in its simulation child and **never** in a Pages job.
Quick Skin's own tests import the same builders, so the packaged-output format they exercise is
exactly the one conformance proves:

* :func:`write_packaged_profile` writes one lane of Quick Skin's packaged E2E output, the
  ``profiles/<node>--<version>--<scenario>/result.json`` tree with one report per client role
  (every contract step as ``{name, status, message, screenshot}``), the recorded
  ``pixel_validation`` (screenshot metrics by step, comparisons by ``first->second``) measured by
  Quick Skin's own ``packaged_runtime`` and the installed production JAR records. Two images are
  enough for every contracted comparison: a capture that is the second step of a comparison takes
  the image its first step did not;
* :func:`synthesize` (the conformance hook) writes that output for every lane of an expectation;
* :func:`compatibility_bundle` writes a shared-source (schema 6) ``mod-compatibility`` native
  bundle for a key's real runnable/N/A plan (CPM 7, Ears 5 and every other integration 2 paired
  checkpoints), with derivatives encoded by ``compatibility_evidence``; :func:`family_bundle`
  (the conformance fixture) uses it with :data:`FAMILY_OUTCOMES`;
* :func:`delegated_extensions` (the conformance fixture) proves delegated reuse the way Quick Skin's
  producer does: through the kit's seeding API (``ctx.api``) it uploads the tested run's
  ``tested-source-e2e`` seal and the handoff run's ``reused-source-e2e`` descriptor, and returns
  that descriptor as the ``quick-skin.runtime_source`` reference the adapter's
  ``authenticate_extensions`` binds to the handoff's tested claim and downloads again;
* :func:`selected_extensions` (the conformance fixture) proves a selective generation the way Quick
  Skin's producer and ``feature_coverage_consumer`` do. The kit's ``selected`` head is one commit
  after the published baseline's, adding :data:`SELECTED_CHANGE` (a ``hud-preview`` source), so
  ``e2e_selection.admit`` of that real Git diff selects the HUD checkpoints. Through ``ctx.api`` it
  gives the handoff run the complete protected job graph, seeds a successful
  ``feature-coverage`` run and the ``healthy-e2e-baseline`` certificate it issued at the baseline
  commit (naming every matrix target's retained ``mb-baseline``: ``ctx.api.retained_baseline``),
  and returns the admission with exactly the coverage proof the consumer recomputes, which the
  adapter's ``compose`` reauthenticates in full before composing;
* :func:`fixture_png` is the deterministic 1920x1080 plaid the Pages tests used before mod-base.
"""

from __future__ import annotations

import hashlib
import io
import itertools
import json
import struct
import tempfile
import zipfile
import zlib
from collections.abc import Callable
from pathlib import Path
from typing import Any

import ci_reuse
import compatibility_evidence
import evidence_target
import mod_compatibility
import packaged_runtime
import scenario_contract

ROOT = Path(__file__).resolve().parents[2]
SCENARIO_CONTRACT = ROOT / "e2e" / "scenario-contract.json"
COMPATIBILITY_CONTRACT = ROOT / "e2e" / "mod-compatibility-contract.json"
MATRIX = ROOT / "release" / "release-matrix.json"
FAMILY_OUTCOMES = ("available", "superseded", "unavailable")
#: The run ids a synthetic compatibility wave records beside its producer.
BASE_RUN_ID = 71001
COMPATIBILITY_RUN_ID = 71002
REVIEW_RUN_ID = 71003
#: Image seeds: packaged captures alternate two images, compatibility pairs use two more.
PACKAGED_SEEDS = (0, 1)
PAIR_SEEDS = (2, 3)
RUNTIME_MESSAGE = "PASS {step}: the packaged {loader} client confirmed the expected state"
RUNTIME_SOURCE = "quick-skin.runtime_source"
#: The artifacts and files ``ci_reuse`` reads for a reused execution, and the synthetic pull request.
SEAL_ARTIFACT, SEAL_FILE = "tested-source-e2e", "tested-source.json"
DESCRIPTOR_ARTIFACT, DESCRIPTOR_FILE = "reused-source-e2e", "reused-source.json"
PULL_REQUEST = 1
FEATURE_SELECTION = "quick-skin.feature_selection"
#: The new file of the kit's ``selected`` head (mod-base ``SELECTED_CHANGE``): a ``hud-preview``
#: source, so the admission of the diff from the baseline's commit selects the HUD checkpoints only.
SELECTED_CHANGE = "modules/hud-preview/src/main/java/example/Feature.java"
#: The coverage certificate: its file and the synthetic ids of its review and source artifacts.
CERTIFICATE_FILE = "baseline.json"
CERTIFICATE_IDS = 910_001


def fixture_png(variant: int) -> bytes:
    """One of two deterministic 1920x1080 plaid PNGs (a 640x360 grid scaled 3x, RGB)."""

    base_width, base_height = 640, 360
    width, height = 1920, 1080
    rows: list[bytes] = []
    for y in range(base_height):
        row = bytearray()
        for x in range(base_width):
            if variant == 0:
                pixel = ((x // 40 * 17) % 256, (y // 30 * 23) % 256, ((x // 40 + y // 30) * 31) % 256)
            else:
                pixel = ((x // 40 * 17 + 83) % 256, (y // 30 * 23 + 47) % 256,
                         ((x // 40 + y // 30) * 31 + 131) % 256)
            row.extend(pixel * 3)
        encoded_row = b"\0" + bytes(row)
        rows.extend((encoded_row, encoded_row, encoded_row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        checksum = zlib.crc32(kind + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", checksum)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(b"".join(rows), 9))
            + chunk(b"IEND", b""))


def jar_sha256(artifact_node: str) -> str:
    return hashlib.sha256(f"jar:{artifact_node}".encode()).hexdigest()


class _Measured:
    """Quick Skin's own metrics of a few distinct images, measured once per image and region."""

    def __init__(self, directory: Path, images: tuple[bytes, bytes]) -> None:
        self.paths = []
        for index, data in enumerate(images):
            path = directory / f"variant-{index}.png"
            path.write_bytes(data)
            self.paths.append(path)
        self.metrics = [packaged_runtime.inspect_screenshot(path) for path in self.paths]
        self._comparisons: dict[tuple[int, int, float, Any], dict[str, Any]] = {}

    def comparison(self, first: int, second: int, minimum: float, region: Any) -> dict[str, Any]:
        key = (first, second, minimum, region)
        if key not in self._comparisons:
            self._comparisons[key] = packaged_runtime.compare_screenshots(
                self.paths[first], self.paths[second], minimum, region)
        return self._comparisons[key]


def write_packaged_profile(root: Path, *, contract: Any, artifact_node: str, version: str, loader: str,
                           scenario: str, measured: _Measured, images: tuple[bytes, bytes],
                           selection_sha256: str | None = None, elapsed_s: float = 2.5,
                           message: str = RUNTIME_MESSAGE) -> Path:
    """Write one packaged lane under ``root/profiles`` and return its profile directory."""

    profile_relative = Path("profiles") / f"{artifact_node}--{version}--{scenario}"
    profile = root / profile_relative
    jar = jar_sha256(artifact_node)
    reports: dict[str, Any] = {}
    roles = list(contract.expected_roles(scenario))
    for role in roles:
        role_contract = contract.role(scenario, role)
        first_step_of = {pair.second_step: pair.first_step for pair in role_contract.comparisons}
        variants: dict[str, int] = {}
        steps, metrics = [], {}
        screenshots = profile / role / "screenshots"
        screenshots.mkdir(parents=True, exist_ok=True)
        for step in role_contract.steps:
            screenshot = None
            if step.capture is not None:
                screenshot = f"{step.id}.png"
                first = first_step_of.get(step.id)
                variant = 1 - variants[first] if first is not None else 0
                variants[step.id] = variant
                (screenshots / screenshot).write_bytes(images[variant])
                metrics[step.id] = dict(measured.metrics[variant])
            steps.append({"name": step.id, "status": "pass", "screenshot": screenshot,
                          "message": message.format(step=step.id, loader=loader)})
        comparisons = {
            f"{pair.first_step}->{pair.second_step}": measured.comparison(
                variants[pair.first_step], variants[pair.second_step], pair.minimum_changed_fraction, pair.region)
            for pair in role_contract.comparisons
        }
        report = {"version": version, "role": role, "scenario": scenario, "contract_sha256": contract.sha256,
                  "status": "pass", "steps": steps,
                  "pixel_validation": {"screenshots": metrics, "comparisons": comparisons}}
        if selection_sha256 is not None:
            report["selection_sha256"] = selection_sha256
        reports[role] = report
    result = {"artifact_node": artifact_node, "runtime_version": version, "loader": loader, "scenario": scenario,
              "contract_sha256": contract.sha256, "jar_sha256": jar,
              "installed_quickskin": [{"path": f"{install_root}/mods/quick-skin.jar", "sha256": jar}
                                      for install_root in ["server", *roles]],
              "port": 25565, "status": "pass", "profile": profile_relative.as_posix(), "elapsed_s": elapsed_s,
              "reports": reports}
    if selection_sha256 is not None:
        result["selection_sha256"] = selection_sha256
    (profile / "result.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return profile


def write_packaged_lanes(root: Path, lanes: list[dict[str, Any]], *, images: tuple[bytes, bytes],
                         contract: Any | None = None, selection_sha256: str | None = None) -> None:
    """Write every ``{artifact_node, minecraft, loader, scenario}`` lane of an expectation."""

    contract = scenario_contract.load_contract(SCENARIO_CONTRACT) if contract is None else contract
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="quick-skin-fixture-images-") as scratch:
        measured = _Measured(Path(scratch), images)
        for index, lane in enumerate(lanes):
            write_packaged_profile(root, contract=contract, artifact_node=lane["artifact_node"],
                                   version=lane["minecraft"], loader=lane["loader"], scenario=lane["scenario"],
                                   measured=measured, images=images, selection_sha256=selection_sha256,
                                   elapsed_s=2.5 + index)


def synthesize(ctx: Any, target: dict[str, Any], expectation: dict[str, Any], out_root: str,
               image_factory: Callable[[int, int, int], bytes]) -> None:
    """Conformance: Quick Skin's packaged output for every lane of ``expectation``; a selective
    runtime reports exactly the steps of its selection (the contract projected onto it)."""

    width, height = expectation["image_policy"]["source_size"]
    images = tuple(image_factory(width, height, seed) for seed in PACKAGED_SEEDS)
    selection, contract = None, None
    if expectation["scope"]["kind"] == "selected":
        import feature_evidence
        from selection import project_contract

        detail = expectation["scope"]["detail"]
        selection = detail["selection_sha256"]
        contract = project_contract(scenario_contract.load_contract(SCENARIO_CONTRACT),
                                    feature_evidence.SelectionView.from_detail(detail))
    write_packaged_lanes(Path(out_root), expectation["lanes"], images=images, contract=contract,
                         selection_sha256=selection)


def _record_zip(filename: str, value: Any) -> bytes:
    """A one-record ZIP artifact, as ``actions/upload-artifact`` stores ``ci_reuse``'s records."""

    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(zipfile.ZipInfo(filename, date_time=(2026, 9, 1, 12, 0, 0)), ci_reuse.canonical(value))
    return stream.getvalue()


def delegated_extensions(ctx: Any, target: dict[str, Any], tested_run: dict[str, Any]) -> dict[str, Any]:
    """Conformance: the ``quick-skin.runtime_source`` reference of a handoff reusing ``tested_run``.

    ``ci_reuse`` records the reused execution as a ``quick-skin-tested-source`` seal, uploaded by
    the tested run, and the master generation's ``quick-skin-merged-source`` descriptor naming that
    seal, uploaded by the handoff run (``ctx.api.handoff_run``). Both are seeded here and the
    descriptor is returned. The adapter binds it to the handoff's tested claim, which names the
    tested run itself (mod-base v1.0.1): its branch and commit, as Quick Skin's producer records
    the pull request's branch and merge commit. So the seal's ``head_branch`` and ``tested_sha``
    are the tested run's (``conformance/reused-pull-request`` at the subject commit)."""

    handoff, subject = ctx.api.handoff_run, target["subject"]
    repository = ctx.api.repository
    source = {"schema_version": 1, "kind": "quick-skin-tested-source", "repository": repository,
              "workflow": ci_reuse.WORKFLOWS["e2e"], "run_id": tested_run["id"],
              "run_attempt": tested_run["run_attempt"], "head_sha": tested_run["head_sha"],
              "head_branch": tested_run["head_branch"], "head_repository": repository,
              "tested_sha": tested_run["head_sha"], "tree_sha": subject["tree"], "pull_request": PULL_REQUEST,
              "base_sha": subject["commit"]}
    ci_reuse.validate_seal(source, "e2e")
    seal = ctx.api.add_artifact(tested_run["id"], SEAL_ARTIFACT, _record_zip(SEAL_FILE, source))
    reference = {"schema_version": 1, "kind": "quick-skin-merged-source", "repository": repository,
                 "workflow": ci_reuse.WORKFLOWS["e2e"], "coverage_sha": subject["commit"], "source": source,
                 "seal_artifact": {"id": seal["id"], "name": seal["name"], "size_in_bytes": seal["size_in_bytes"],
                                   "digest": seal["digest"], "expired": False,
                                   "workflow_run": {"id": source["run_id"], "head_sha": source["head_sha"],
                                                    "head_branch": source["head_branch"]}}}
    ci_reuse.validate_reference(reference, "e2e")
    ctx.api.add_artifact(handoff["id"], DESCRIPTOR_ARTIFACT, _record_zip(DESCRIPTOR_FILE, reference))
    return {RUNTIME_SOURCE: reference}


def selected_extensions(ctx: Any, target: dict[str, Any], baseline: dict[str, Any]) -> dict[str, Any]:
    """Conformance: the ``quick-skin.feature_selection`` of a selective generation composed with
    ``baseline``, as Quick Skin's producer hands it off and ``feature_coverage_consumer`` proves it.

    The admission is ``e2e_selection.admit`` of the real Git diff from the baseline's commit to the
    selected head (the kit's ``selected`` head adds :data:`SELECTED_CHANGE`). Through ``ctx.api`` the
    handoff run gets the complete ``pr-anchors`` job graph a selective runtime must show, and a
    successful ``feature-coverage`` run at the baseline commit gets its issuer job and the
    ``healthy-e2e-baseline`` certificate: the complete coverage of that commit under the executing
    policy, naming the retained public baseline of every matrix target (``baseline`` for this key;
    ``ctx.api.retained_baseline`` for the others) and synthetic review and source artifact ids. The
    coverage proof is exactly what ``feature_coverage_consumer._from_artifact`` recomputes; the
    public baselines are not authenticated here. The adapter's ``compose`` refuses a forged
    ``baseline`` that a source run uploaded, because its owner is no successful Pages run with the
    required jobs. One that a Pages run uploaded outside its refresh job's retention step passes
    ``compose``, whose owner check reads job conclusions but not step windows, and the kit's R3
    refuses it."""

    import feature_coverage as coverage
    import feature_coverage_consumer as consumer
    import feature_coverage_github as publisher
    from e2e_job_graph import BUILD_JOB, GATE_JOB, POLICY_JOB

    repository, api, key = Path(ctx.repo_root), ctx.api, target["key"]
    head = target["subject"]["commit"]
    named = publisher.parse_public_baseline_name(baseline["name"])
    if named is None or named[0] != key:
        raise ValueError(f"{baseline['name']} is not a public baseline of {key}")
    base, source_run = named[1], named[2]
    selected = coverage.admission.admit(repository, base=base, head=head, policy=head)
    plan = selected.require_selection()
    handoff = api.handoff_run
    expected = coverage.expected_scenario_jobs_for(coverage.DEFAULT_MATRIX, "pr-anchors")
    api.add_jobs(handoff["id"], handoff["run_attempt"],
                 [{"name": name} for name in (POLICY_JOB, BUILD_JOB, GATE_JOB, *sorted(expected))])
    issuer = api.add_run({"path": publisher.WORKFLOW, "event": "workflow_dispatch", "head_branch": "master",
                          "head_sha": base, "display_title": "Feature coverage"})
    api.add_jobs(issuer["id"], issuer["run_attempt"], [{"name": consumer.ISSUER_JOB}])

    graph, contract = coverage.load_graph(), coverage.default_contract()
    matrix = coverage.load_matrix(coverage.DEFAULT_MATRIX)
    captures = sum(step.capture is not None for name in contract.scenarios_for_profile("pr")
                   for role in contract.scenario(name).roles for step in role.steps)
    identifiers = itertools.count(CERTIFICATE_IDS)
    targets, reviews, public = [], {}, {}
    for row in coverage.inventory(coverage.DEFAULT_MATRIX)["include"]:
        bundle_key = row["bundle_key"]
        nodes = sorted(item["artifact_node"]
                       for item in coverage.select_release_target(matrix, row["minecraft_target"])["artifacts"])
        digests = {field: hashlib.sha256(f"{field}:{bundle_key}".encode()).hexdigest()
                   for field in ("proof_sha256", "manifest_sha256", "report_sha256")}
        targets.append({"bundle_key": bundle_key, "artifact_nodes": nodes,
                        "source_artifact_ids": [next(identifiers) for _node in nodes],
                        "frame_count": len(nodes) * captures, **digests})
        reviews[bundle_key] = {"id": next(identifiers), "owner_run_id": next(identifiers),
                               "name": f"visual-review-{source_run}--{bundle_key}", "size_in_bytes": 1024,
                               "digest": "sha256:" + hashlib.sha256(f"review:{bundle_key}".encode()).hexdigest()}
        record = (api.get_json(f"/repos/{api.repository}/actions/artifacts/{baseline['id']}") if bundle_key == key
                  else api.retained_baseline(bundle_key))
        public[bundle_key] = {"id": record["id"], "owner_run_id": record["workflow_run"]["id"],
                              "name": record["name"], "digest": record["digest"],
                              "size_in_bytes": record["size_in_bytes"]}
    before = coverage.module_fingerprints(repository, base, graph)
    value = {"schema_version": 1, "kind": coverage.BASELINE_KIND, "profile": "pr", "coverage": "full",
             "source_sha": base, "source_run_id": source_run, "source_run_attempt": 1,
             "matrix_sha256": coverage.digest(coverage.DEFAULT_MATRIX.read_bytes()),
             "scenario_contract_sha256": contract.sha256, "module_graph_sha256": graph.sha256,
             "policy_sha256": coverage.policy_fingerprint(repository, head, verify_executing=True),
             "module_fingerprints": before, "targets": targets,
             "issuer": {"workflow": publisher.WORKFLOW, "run_id": issuer["id"], "sha": base},
             "source_job_graph": {"schema_version": 1, "runtime_policy": "full",
                                  "expected_scenario_jobs": sorted(expected),
                                  "observed_scenario_jobs": sorted(expected)},
             "review_artifacts": reviews, "public_artifacts": public}
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(zipfile.ZipInfo(CERTIFICATE_FILE, date_time=(2026, 9, 1, 12, 0, 0)),
                         coverage.admission.canonical(value))
    certificate = api.add_artifact(issuer["id"], coverage.BASELINE_ARTIFACT_NAME, stream.getvalue())
    after = coverage.module_fingerprints(repository, head)
    unaffected = sorted(set(before) - set(plan.affected_modules))
    if any(before[name] != after[name] for name in unaffected):
        raise ValueError("the selected change moved a module outside its selection")
    proof = {"schema_version": 1, "selective": True, "reason": selected.reason,
             "selection_sha256": selected.sha256,
             "baseline": {"id": certificate["id"], "owner_run_id": issuer["id"], "source_sha": base,
                          "name": certificate["name"], "digest": certificate["digest"],
                          "size_in_bytes": certificate["size_in_bytes"]},
             "baseline_sha256": coverage.digest(coverage.admission.canonical(value)),
             "public_baseline_artifacts": public, "baseline_source_run_id": source_run,
             "unchanged_module_fingerprints": {name: before[name] for name in unaffected}}
    return {FEATURE_SELECTION: {"admission": selected.to_dict(), "coverage": proof}}


def _pair_image(png: bytes, scratch: Path, bundle: Path, cache: dict[str, dict[str, Any]]) -> dict[str, Any]:
    """One pair side, encoded and measured exactly as ``compatibility_evidence.build_bundle`` does."""

    digest = hashlib.sha256(png).hexdigest()
    source = scratch / f"{digest}.png"
    if not source.exists():
        source.write_bytes(png)
    return compatibility_evidence._public_image(source, staged_bundle=bundle, temporary_root=scratch,
                                                derivatives=cache)


def compatibility_bundle(out_root: Path, *, key: str, repository: str, coverage_sha: str, target_sha: str,
                         publication_run: dict[str, Any] | None, publication_run_id: int,
                         images: tuple[bytes, bytes], contracts: dict[str, str] | None = None,
                         matrix_sha256: str | None = None) -> dict[str, Any]:
    """Write the shared-source ``mod-compatibility`` native bundle of ``key`` into ``out_root``.

    The runnable lanes and not-applicable rows are the real plan of the checked-out matrix and
    optional-mod lock; every lane pairs a clean reference with a modded candidate for each of its
    public checkpoints. ``contracts``/``matrix_sha256`` override the recorded identities (drift)."""

    scenarios = scenario_contract.load_contract(SCENARIO_CONTRACT)
    locked = mod_compatibility.load_contract(COMPATIBILITY_CONTRACT)
    target = evidence_target.target_for_key(key, MATRIX)
    runnable, not_applicable = compatibility_evidence._expected_plan(key, locked, matrix_path=MATRIX)
    (out_root / "images").mkdir(parents=True, exist_ok=True)
    lanes = []
    with tempfile.TemporaryDirectory(prefix="quick-skin-compatibility-fixture-") as temporary:
        scratch, cache = Path(temporary), {}
        if runnable:
            reference = _pair_image(images[0], scratch, out_root, cache)
            candidate = _pair_image(images[1], scratch, out_root, cache)
        for lane_id, lane in sorted(runnable.items()):
            frames = []
            for capture_id in compatibility_evidence._public_capture_ids(scenarios, lane.mod):
                capture = scenarios.capture_by_id(capture_id)
                frames.append({
                    "capture_id": capture_id, "reference_capture_id": capture.compatibility_reference_capture_id,
                    "title": capture.title, "expectation": f"{capture.expectation} {lane.mod.name} is installed.",
                    "runtime_evidence": f"PASS {capture_id}: {lane.mod.name} is active on {lane.artifact_node}",
                    "review_regions": [[0.0, 0.0, 1.0, 1.0]],
                    "candidate_semantic_sha256": candidate["source"]["pixel_validation"]["pixel_sha256"],
                    "reference_semantic_sha256": reference["source"]["pixel_validation"]["pixel_sha256"],
                    "semantic_changed_fraction": 0.0, "perceptual_delta": 0.5,
                    "semantic_valid": True, "matches_reference": True, "defect": False,
                    "candidate": json.loads(json.dumps(candidate)), "reference": json.loads(json.dumps(reference)),
                })
            digest = hashlib.sha256(f"{lane_id}:{len(frames)}".encode()).hexdigest()
            lanes.append({
                "lane_id": lane_id, "artifact_node": lane.artifact_node, "version": lane.runtime_version,
                "loader": lane.loader, "mod": lane.mod.id, "mod_name": lane.mod.name,
                "mod_version": lane.artifact.version_number, "mod_version_id": lane.artifact.version_id,
                "review_run_id": REVIEW_RUN_ID, "reviewed_frame_count": len(frames),
                "review_manifest_sha256": digest,
                "curation_proof_sha256": hashlib.sha256(b"proof" + digest.encode()).hexdigest(),
                "review_report_sha256": hashlib.sha256(b"report" + digest.encode()).hexdigest(), "frames": frames,
            })
    provenance = {"implementation_sha": target_sha, "base_run_id": BASE_RUN_ID, "source_sha": target_sha,
                  "target_sha": target_sha, "compatibility_run_id": COMPATIBILITY_RUN_ID,
                  "publication_run_id": publication_run_id, "coverage_sha": coverage_sha}
    if publication_run is not None:
        provenance["publication_run"] = dict(publication_run)
    manifest = {
        "schema_version": compatibility_evidence.SHARED_SCHEMA_VERSION, "kind": compatibility_evidence.KIND,
        "repository": repository,
        "contracts": contracts or {"scenario_sha256": scenarios.sha256, "compatibility_sha256": locked.sha256},
        "release": {"branch": target.branch, "version": target.version, "loaders": list(target.loaders),
                    "matrix_sha256": matrix_sha256 or target.matrix_sha256},
        "provenance": provenance, "lanes": lanes,
        "not_applicable": sorted(not_applicable.values(), key=lambda row: (row["version"], row["loader"], row["mod"])),
    }
    (out_root / compatibility_evidence.MANIFEST_NAME).write_text(
        json.dumps(manifest, indent=2, sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    return manifest


def family_bundle(ctx: Any, family: str, key: str, target: dict[str, Any], expectation: dict[str, Any],
                  producer: dict[str, Any], out_root: str, image_factory: Callable[[int, int, int], bytes],
                  outcome: str) -> None:
    """Conformance: the native bundle the compatibility publisher would hand to ``publish-family``.

    ``available`` is a clean wave of the subject; ``superseded`` binds another scenario contract
    (drift); ``unavailable`` names a tested commit outside the subject's lineage."""

    if family != "mod-compatibility" or outcome not in FAMILY_OUTCOMES:
        raise ValueError(f"no {family}/{outcome} fixture")
    commit = target["subject"]["commit"]
    width, height = expectation["image_policy"]["source_size"]
    run = {"event": producer["event"], "created_at": producer["created_at"]}
    if "display_title" in producer:
        run["display_title"] = producer["display_title"]
    scenarios = scenario_contract.load_contract(SCENARIO_CONTRACT)
    locked = mod_compatibility.load_contract(COMPATIBILITY_CONTRACT)
    compatibility_bundle(
        Path(out_root), key=key, repository=expectation["repository"], coverage_sha=commit,
        target_sha="0" * 40 if outcome == "unavailable" else commit, publication_run=run,
        publication_run_id=producer["run_id"],
        images=tuple(image_factory(width, height, seed) for seed in PAIR_SEEDS),
        contracts=({"scenario_sha256": "0" * 64, "compatibility_sha256": locked.sha256}
                   if outcome == "superseded" else {"scenario_sha256": scenarios.sha256,
                                                    "compatibility_sha256": locked.sha256}))
