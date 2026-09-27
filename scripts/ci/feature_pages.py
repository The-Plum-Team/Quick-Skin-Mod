#!/usr/bin/env python3
"""Authenticate reused and selective public evidence for Quick Skin's mod-base adapter.

The E2E producer's advisory ``prepare-pages-evidence`` job calls ``--runtime-identity``: it
resolves the original runtime whose packaged bytes the handoff publishes (a merged pull request's
execution when the master generation reused it) and writes the ``extensions.json`` mod-base
carries with the handoff: ``quick-skin.runtime_source`` (the authenticated merged-source
reference) and, for a selective generation, ``quick-skin.feature_selection`` (the protected E2E
admission and its coverage record). Every verified selection is handed off, including one that
re-captures only some checkpoints of a lane: mod-base composes it per frame (R3), and such a lane
records the baseline execution of its older frames as ``baseline_run``.

The adapter (``scripts/pages/mod_base_adapter.py``) uses the library functions in Pages jobs:

* :func:`verify_reuse_extension` (``authenticate_extensions``) binds a ``runtime_source``
  reference to the handoff's tested claim and to its generation's own reuse descriptor;
* :func:`verify_selection_extension` binds a feature selection to the selected scope and to the
  exact tested and policy commits;
* :func:`compose_selected` (``compose``) authenticates the runtime, the selection's complete
  coverage certificate and the retained ``mb-baseline`` generation before composing;
* :func:`verify_runtime_tree` (``verify_publication``) reauthenticates, once each, the reused
  runtime generations whose evidence the publication actually publishes, before mod-base renders
  the site.

The kit (``mod_base``) is imported lazily, so the E2E producer path never needs it.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

import feature_coverage as coverage
import feature_coverage_github as publisher
import ci_reuse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pages"))

RUNTIME_SOURCE = "quick-skin.runtime_source"
FEATURE_SELECTION = "quick-skin.feature_selection"
REUSED_SOURCE_ARTIFACT = "reused-source-e2e"
SOURCE_WORKFLOW = ci_reuse.WORKFLOWS["e2e"]
CANONICAL_BRANCH = "master"
EXTENSIONS_NAME = "extensions.json"
MAX_GENERATIONS = 100
# mod-base artifact names (mod_base.model.grammar is the single source; scripts/ci/tests/
# test_mod_base_names.py pins these prefixes to it).
HANDOFF_PREFIX = "mb-handoff--"
COLLECTED_PREFIX = "mb-collected--"
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _claim(manifest: Any, name: str) -> dict[str, Any]:
    provenance = manifest.get("provenance") if isinstance(manifest, dict) else None
    claim = provenance.get(name) if isinstance(provenance, dict) else None
    if not isinstance(claim, dict):
        raise coverage.CoverageError(f"public evidence has no {name} claim")
    return claim


def verify_reuse_extension(api: publisher.Api, manifest: dict[str, Any], reference: Any) -> dict[str, Any]:
    """Bind a ``runtime_source`` reference to a mod-base manifest and its generation.

    The reference must be the exact inert merged-source record of the manifest's tested claim
    (run, attempt, tested merge commit, pull-request branch) covering the manifest's subject,
    and it must be byte-for-byte the ``reused-source-e2e`` descriptor its handoff generation
    uploaded. The original execution itself is reauthenticated by :func:`verify_runtime_tree`
    before publication (once per generation). Returns the reference's tested-source seal."""

    try:
        source = ci_reuse.validate_reference(reference, "e2e")
    except ci_reuse.ReuseError as exc:
        raise coverage.CoverageError(f"invalid runtime_source reference: {exc}") from exc
    tested, handoff = _claim(manifest, "tested"), _claim(manifest, "handoff")
    subject = manifest.get("subject")
    provenance = manifest["provenance"]
    if (manifest.get("repository") != api.repository or reference["repository"] != api.repository
            or not isinstance(subject, dict) or subject.get("branch") != CANONICAL_BRANCH
            or reference["coverage_sha"] != subject.get("commit")
            or provenance.get("coverage_sha") != subject.get("commit")
            or provenance.get("reuse") != "delegated"):
        raise coverage.CoverageError("runtime_source covers another repository, branch or generation")
    expected_tested = {"run_id": source["run_id"], "run_attempt": source["run_attempt"],
                       "workflow_path": SOURCE_WORKFLOW, "branch": source["head_branch"],
                       "commit": source["tested_sha"], "controller_branch": CANONICAL_BRANCH,
                       "controller_sha": source["tested_sha"]}
    expected_handoff = {"workflow_path": SOURCE_WORKFLOW, "branch": CANONICAL_BRANCH, "commit": subject["commit"],
                        "controller_branch": CANONICAL_BRANCH, "controller_sha": subject["commit"]}
    if (tested != expected_tested
            or any(handoff.get(field) != value for field, value in expected_handoff.items())):
        raise coverage.CoverageError("public evidence substituted its original tested run or generation")
    generation = {"id": handoff["run_id"], "head_sha": handoff["commit"], "head_branch": handoff["branch"]}
    artifact = ci_reuse.validate_artifact(
        ci_reuse.one_artifact(api.artifacts(run_id=handoff["run_id"]), REUSED_SOURCE_ARTIFACT),
        name=REUSED_SOURCE_ARTIFACT, run=generation, maximum=ci_reuse.MAX_DESCRIPTOR_ARCHIVE)
    if ci_reuse.descriptor(api, artifact, "reused-source.json") != reference:
        raise coverage.CoverageError("runtime_source differs from its generation's reuse descriptor")
    return source


def verify_selection_extension(manifest: dict[str, Any], extensions: dict[str, Any], chosen: Any) -> None:
    """Bind a recomputed feature selection (``chosen``) to its selected manifest.

    The manifest's scope must be exactly the selection's capture obligations, the admission must
    have been computed for the tested commit, and its policy must be the protected base of that
    runtime (the pull request's base for reused evidence, the subject itself otherwise)."""

    import feature_evidence

    tested = _claim(manifest, "tested")
    subject = manifest["subject"]
    policy = subject["commit"]
    if RUNTIME_SOURCE in extensions:
        policy = ci_reuse.validate_reference(extensions[RUNTIME_SOURCE], "e2e")["base_sha"]
    detail = feature_evidence.selection_detail(chosen)
    if (manifest["scope"] != {"kind": "selected", "detail_sha256": feature_evidence.selection_digest(detail)}
            or chosen.head_commit != tested["commit"] or chosen.policy_commit != policy):
        raise coverage.CoverageError("the feature selection is not the manifest's selected scope and tested source")


def runtime_of(api: publisher.Api, manifest: dict[str, Any], extensions: dict[str, Any]) -> ci_reuse.RuntimeSource:
    """Reauthenticate the complete runtime a mod-base manifest publishes: its handoff generation
    (``ci_reuse.runtime_source``, including a reused original execution) bound to its claims."""

    tested, handoff = _claim(manifest, "tested"), _claim(manifest, "handoff")
    source_sha = manifest["subject"]["commit"]
    runtime = ci_reuse.runtime_source(api, handoff["run_id"], source_sha)
    execution = runtime.execution
    if (runtime.reference != extensions.get(RUNTIME_SOURCE)
            or runtime.generation.get("run_attempt") != handoff["run_attempt"]
            or execution["id"] != tested["run_id"] or execution.get("run_attempt") != tested["run_attempt"]
            or runtime.tested_sha != tested["commit"] or execution.get("head_branch") != tested["branch"]):
        raise coverage.CoverageError("public provenance differs from its authenticated runtime")
    return runtime


def _published_bundles(promotion: Any, source_sha: str) -> list[dict[str, Any]]:
    """The promotion draft's bundles, each covering exactly ``source_sha`` with exact artifact ids."""

    bundles = promotion.get("bundles") if isinstance(promotion, dict) else None
    if (not isinstance(bundles, list) or not bundles
            or any(not isinstance(bundle, dict) or bundle.get("coverage_sha") != source_sha for bundle in bundles)):
        raise coverage.CoverageError("the publication does not cover exactly the protected head")
    for bundle in bundles:
        for field in ("selected_artifact_id", "collected_artifact_id"):
            coverage._positive_integer(bundle.get(field), f"published {field}")
        if (not isinstance(bundle.get("key"), str) or not isinstance(bundle.get("collected_digest"), str)
                or not isinstance(bundle.get("manifest_sha256"), str)
                or SHA256.fullmatch(bundle["manifest_sha256"]) is None):
            raise coverage.CoverageError("the publication names a malformed bundle")
    return bundles


def _head_generations(api: publisher.Api, source_sha: str) -> tuple[int, dict[int, list[dict[str, Any]]]]:
    """``(runs, successful)``: how many ``workflow_dispatch`` generations of the source workflow
    this repository has on master at ``source_sha`` (in any state), and the successful ones (the
    only runs mod-base admits as a handoff run) with their artifact inventories."""

    record = api.json(f"actions/workflows/{Path(SOURCE_WORKFLOW).name}/runs?"
                      f"event=workflow_dispatch&head_sha={source_sha}&per_page={MAX_GENERATIONS}")
    runs = record.get("workflow_runs") if isinstance(record, dict) else None
    if (not isinstance(runs, list) or type(record.get("total_count")) is not int
            or record["total_count"] != len(runs) or len(runs) > MAX_GENERATIONS):
        raise coverage.CoverageError("the generation inventory of the published head is incomplete")
    candidates, generations = 0, {}
    for run in runs:
        if (not isinstance(run, dict) or run.get("head_sha") != source_sha or run.get("path") != SOURCE_WORKFLOW
                or run.get("head_branch") != CANONICAL_BRANCH
                or not isinstance(run.get("head_repository"), dict)
                or run["head_repository"].get("full_name") != api.repository):
            continue
        candidates += 1
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            continue
        identifier = coverage._positive_integer(run.get("id"), "generation run")
        generations[identifier] = api.artifacts(run_id=identifier)
    return candidates, generations


def _collected_generation(api: publisher.Api, bundle: dict[str, Any], *, source_sha: str, scratch: Path) -> int:
    """The handoff generation a cache-sourced bundle republishes, read from the exact collected
    artifact the draft names: its ``manifest.json`` must hash to the draft's ``manifest_sha256``."""

    import shutil

    from mod_base.io.bounded_zip import LIMITS_BY_KIND, artifact_limit, extract
    from mod_base.io.tree import read_child_file
    from mod_base.model import limits
    from mod_base.model.canonical import sha256_hex, strict_loads

    key = bundle["key"]
    metadata = api.artifact(bundle["collected_artifact_id"])
    owner = metadata.get("workflow_run")
    if (metadata.get("name") != f"{COLLECTED_PREFIX}{key}" or metadata.get("digest") != bundle["collected_digest"]
            or metadata.get("expired") is not False or not isinstance(owner, dict)
            or owner.get("head_sha") != source_sha or owner.get("head_branch") != CANONICAL_BRANCH
            or type(metadata.get("size_in_bytes")) is not int
            or not 0 < metadata["size_in_bytes"] <= artifact_limit("collected")):
        raise coverage.CoverageError(f"the published bundle of {key} is not its collected artifact")
    raw = api.archive(metadata, maximum=metadata["size_in_bytes"])
    directory = scratch / f"collected-{metadata['id']}"
    try:
        extract(raw, directory, LIMITS_BY_KIND["collected"])
        manifest_raw = read_child_file(directory, "manifest.json", max_bytes=limits.MAX_MANIFEST_BYTES)
    finally:
        shutil.rmtree(directory, ignore_errors=True)
    if sha256_hex(manifest_raw) != bundle["manifest_sha256"]:
        raise coverage.CoverageError(f"the collected manifest of {key} is not the one the publication names")
    manifest = strict_loads(manifest_raw, label="manifest.json", max_bytes=limits.MAX_MANIFEST_BYTES)
    provenance = manifest.get("provenance") if isinstance(manifest, dict) else None
    source = manifest.get("source_artifact") if isinstance(manifest, dict) else None
    handoff = provenance.get("handoff") if isinstance(provenance, dict) else None
    if (manifest.get("key") != key or manifest.get("repository") != api.repository
            or not isinstance(source, dict) or source.get("id") != bundle["selected_artifact_id"]
            or not isinstance(handoff, dict) or provenance.get("coverage_sha") != source_sha):
        raise coverage.CoverageError(f"the collected manifest of {key} is another publication's")
    return coverage._positive_integer(handoff.get("run_id"), "published handoff run")


def verify_runtime_tree(api: publisher.Api, promotion: dict[str, Any], *, source_sha: str,
                        scratch: Path) -> dict[str, int]:
    """Reauthenticate every reused runtime generation the publication publishes, before render.

    Every bundle of the promotion draft covers ``source_sha``, the protected head, and mod-base
    has already authenticated its handoff run as a successful ``workflow_dispatch`` generation of
    the source workflow on master at that head, and bound each ``runtime_source`` to that
    generation (:func:`verify_reuse_extension`). This final gate lists those generations once and
    resolves the one each bundle publishes: the owner of its selected handoff, or, for a bundle
    republished from a cache while the head has several generations, the handoff run recorded in
    its exact collected manifest (a head with a single generation run, in any state, needs no
    download: every bundle is that generation's). Only the published generations that reused a
    pull request's runtime are reauthenticated (``ci_reuse.runtime_source``: the generation's own
    jobs, the original run, seal, merged pull request, tested tree and packaged artifacts),
    bracketed by live head checks, so an unpublished generation of the same head (an older merge
    generation whose original evidence expired, beside a fresh recovery run) never vetoes.
    Nothing is cached across publications."""

    bundles = _published_bundles(promotion, source_sha)
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("source advanced before public runtime verification")
    candidates, generations = _head_generations(api, source_sha)
    if not generations:
        raise coverage.CoverageError("the published head has no successful source generation")
    owners = {item["id"]: (run_id, item.get("name")) for run_id, inventory in generations.items()
              for item in inventory if isinstance(item, dict) and type(item.get("id")) is int}
    published: set[int] = set()
    collected = 0
    for bundle in bundles:
        owner = owners.get(bundle["selected_artifact_id"])
        if owner is not None:
            generation, name = owner
            if not isinstance(name, str) or not name.startswith(f"{HANDOFF_PREFIX}{bundle['key']}--a"):
                raise coverage.CoverageError(f"the published bundle of {bundle['key']} selected another artifact "
                                             "of its generation")
        elif candidates == 1:
            generation = next(iter(generations))  # the head's only generation, in any state
        else:
            generation = _collected_generation(api, bundle, source_sha=source_sha, scratch=scratch)
            collected += 1
            if generation not in generations:
                raise coverage.CoverageError(f"the published bundle of {bundle['key']} names no successful "
                                             "generation of the head")
        published.add(generation)
    verified = 0
    for generation in sorted(published):
        if not any(item.get("name") == REUSED_SOURCE_ARTIFACT for item in generations[generation]):
            continue  # A fresh execution is its own tested run, authenticated by mod-base.
        ci_reuse.runtime_source(api, generation, source_sha)
        verified += 1
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("source advanced during public runtime verification")
    return {"bundles": len(bundles), "generations": len(published), "collected_manifests": collected,
            "runtime_sources": verified}


def _git_tree(repository: Path, commit: str) -> str:
    completed = subprocess.run(["git", "rev-parse", "--verify", f"{commit}^{{tree}}"], cwd=repository,
                               stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60, check=False)
    tree = completed.stdout.strip()
    if completed.returncode != 0 or coverage.admission.SHA.fullmatch(tree) is None:
        raise coverage.CoverageError("the protected head's tree is not available locally")
    return tree


def runtime_identity(api: publisher.Api, *, repository: Path, source_sha: str, run_id: int,
                     directory: Path) -> dict[str, str]:
    """Resolve original bytes before the source run's advisory Pages preparation downloads them.

    Writes ``extensions.json`` into ``directory`` when the handoff needs an extension (a reused
    runtime's ``runtime_source`` reference, a selective runtime's ``feature_selection``) and
    reports its path as ``extensions_path`` (empty otherwise).

    A selective runtime's admission is verified from real Git objects before anything is handed
    off; every verified selection is then published, however few checkpoints it re-captures
    (the adapter's ``compose`` completes it with the retained complete baseline per frame)."""
    import feature_review as review
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("shared source advanced before public preparation")
    source = ci_reuse.runtime_source(api, run_id, source_sha, allow_in_progress=True)
    directory.mkdir(parents=True, exist_ok=True)
    result = {"publish": "true", "source_run_id": str(source.execution["id"]),
        "source_run_attempt": str(coverage._positive_integer(source.execution.get("run_attempt"), "source attempt")),
        "source_branch": source.execution["head_branch"], "source_sha": source.tested_sha,
        "source_created_at": source.execution["created_at"], "target_sha": source_sha,
        "target_created_at": source.generation["created_at"], "target_tree": _git_tree(repository, source_sha),
        "reused": "true" if source.reference is not None else "false", "selective": "false"}
    extensions: dict[str, Any] = {}
    if source.reference is not None:
        (directory / "runtime-source.json").write_bytes(ci_reuse.canonical(source.reference))
        ci_reuse.fetch_source_objects(repository, source.reference)
        extensions[RUNTIME_SOURCE] = source.reference
    selections = [item for item in source.artifacts if item["name"] == coverage.SELECTION_ARTIFACT_NAME]
    if len(selections) > 1:
        raise coverage.CoverageError("runtime has ambiguous feature selection")
    if selections:
        admission_path, coverage_path = review._selection_files(api, selections[0],
            source_sha=source.execution["head_sha"], source_run_id=source.execution["id"],
            source_branch=source.execution["head_branch"], directory=directory / "selection")
        supplied, _digest = coverage._read(admission_path)
        policy = source.reference["source"]["base_sha"] if source.reference else source_sha
        chosen = coverage.admission.verify(admission_path, repository, base=supplied.get("base_commit"),
                                            head=source.tested_sha, policy=policy)
        chosen.require_selection()
        selection_coverage, _digest = coverage._read(coverage_path)
        extensions[FEATURE_SELECTION] = {"admission": chosen.to_dict(), "coverage": selection_coverage}
        result.update(selective="true", selection_base=chosen.base_commit,
                      selection_policy=chosen.policy_commit, selection_sha256=chosen.sha256)
    result["extensions_path"] = ""
    if extensions:
        path = directory / EXTENSIONS_NAME
        path.write_bytes(coverage.admission.canonical(extensions))
        result["extensions_path"] = str(path)
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("shared source advanced during public preparation")
    return result


def compose_selected(api: publisher.Api, *, repository: Path, key: str, manifest: dict[str, Any], selected_root: Path,
                     output_root: Path, extensions: dict[str, Any], complete_expectation: dict[str, Any],
                     composed_extensions: dict[str, Any], scratch: Path) -> dict[str, Any]:
    """Authenticate selected evidence and compose it with its complete retained baseline.

    ``selected_root`` is mod-base's compaction of the selected handoff and ``manifest`` its
    validated ``manifest.json``; ``extensions`` its extension objects. The runtime, the selection
    (``feature_review.verify_selection``: the healthy-baseline certificate, its execution and Git
    admission) and the retained public baseline named by that certificate
    (``validate_public_owner``, exact archive digest) are all reauthenticated here before the
    bytes are composed. Returns the baseline artifact ``{id, name, digest}``."""
    import feature_evidence
    import feature_review as review
    from mod_base.io.bounded_zip import LIMITS_BY_KIND, extract

    if manifest.get("key") != key or manifest.get("repository") != api.repository:
        raise coverage.CoverageError("selected evidence belongs to another key or repository")
    source_sha = manifest["subject"]["commit"]
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("source advanced before selected public evidence collection")
    runtime = runtime_of(api, manifest, extensions)
    feature = extensions.get(FEATURE_SELECTION)
    feature_evidence.read_selection(feature)
    selection_path, coverage_path = scratch / "selection.json", scratch / "coverage.json"
    selection_path.write_bytes(coverage.admission.canonical(feature["admission"]))
    coverage_path.write_bytes(coverage.admission.canonical(feature["coverage"]))
    selected, proof = review.verify_selection(api, (selection_path, coverage_path), runtime,
                                              repository=repository, directory=scratch / "certificate")
    metadata = proof["public_baseline_artifacts"].get(key)
    if metadata is None:
        raise coverage.CoverageError("complete public baseline does not contain this target")
    baseline_run = proof["baseline_source_run_id"]
    # The certificate's retained name carries the run that tested the baseline's pixels (its
    # issuer bound it to that generation's authenticated execution).
    named = publisher.parse_public_baseline_name(metadata.get("name"))
    if named is None or named[:2] != (key, selected.base_commit):
        raise coverage.CoverageError("the certificate names another target's or commit's public baseline")
    tested = named[2]
    owner = api.run(metadata["owner_run_id"])
    authenticated = publisher.validate_public_owner(api.artifact(metadata["id"]), owner, api.jobs(owner),
        github_repository=api.repository, source_sha=selected.base_commit, source_run_id=baseline_run,
        bundle_key=key, tested_run_id=tested)
    if authenticated != metadata:
        raise coverage.CoverageError("retained public baseline differs from the complete coverage certificate")
    archive = api.archive(metadata, maximum=publisher.MAX_PUBLIC_ARCHIVE_BYTES)
    baseline_root = scratch / "public-baseline"
    extract(archive, baseline_root, LIMITS_BY_KIND["baseline"])
    feature_evidence.compose(selected_root=selected_root, baseline_root=baseline_root, output_root=output_root,
                             key=key, complete_expectation=complete_expectation,
                             composed_extensions=composed_extensions,
                             baseline_artifact={name: metadata[name] for name in ("id", "name", "digest")},
                             base_commit=selected.base_commit, baseline_run_id=baseline_run,
                             baseline_tested_run_id=tested)
    if api.current_sha() != source_sha:
        raise coverage.CoverageError("source advanced during selected public evidence collection")
    return {name: metadata[name] for name in ("id", "name", "digest")}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-repository", required=True)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--runtime-identity", action="store_true", required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-output", type=Path, required=True)
    args = parser.parse_args()
    try:
        api = publisher.Api(args.github_repository)
        result = runtime_identity(api, repository=args.repository, source_sha=args.source_sha,
                                  run_id=args.run_id, directory=args.output)
        with args.github_output.open("a", encoding="utf-8") as stream:
            for key, value in result.items():
                stream.write(f"{key}={value}\n")
        print(json.dumps(result, sort_keys=True))
    except (OSError, ValueError, subprocess.SubprocessError) as exc:
        parser.exit(2, f"Public runtime identity failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
