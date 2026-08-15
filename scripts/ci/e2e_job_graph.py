#!/usr/bin/env python3
"""Validate that a successful Packaged E2E run executed its exact protected job graph."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts" / "release"))

from matrix import (  # noqa: E402
    MatrixError,
    gha_matrix,
    load_matrix,
    load_matrix_snapshot,
    read_mod_version,
    read_mod_version_from_properties,
)


POLICY_JOB = "Classify packaged runtime impact"
BUILD_JOB = "Build immutable E2E input bundle"
GATE_JOB = "Packaged E2E gate"
SCENARIO_SUFFIX = " - contract scenarios"
# A matrix job whose matrix never expanded is reported once under its literal template
# name, so a non-runtime port observes this instead of zero scenario jobs.
UNEXPANDED_SCENARIO_JOB = "${{ matrix.id }}" + SCENARIO_SUFFIX
MAX_JOBS = 1000
SHA = re.compile(r"^[0-9a-f]{40}$")
BLOB_SHA256 = re.compile(r"^[0-9a-f]{64}$")
RELEASE_BRANCH = re.compile(
    r"^[a-z0-9]+(?:-and-[a-z0-9]+)*-[0-9]+(?:\.[0-9]+)+$"
)
DEFAULT_BOOTSTRAP_CONTRACT = REPO / "e2e" / "loader-bootstrap-contract.json"
HARNESS_CONVENTION_BINDING = (
    'apply(from = rootProject.file("gradle/e2e-harness-conventions.gradle.kts"))'
)
PROTECTED_CONTROLLER_PATHS = (
    ".github/actions/run-packaged-e2e",
    ".github/workflows/build-gate.yml",
    ".github/workflows/on-demand-e2e.yml",
    ".github/workflows/verify-gate-attestation.yml",
    "build.gradle.kts",
    "common/src/e2e",
    "e2e/check_visual_review.py",
    "e2e/visual_review_cache.py",
    "e2e/ci_summary.py",
    "e2e/dependency_integrity.py",
    "e2e/generate_contract_java.py",
    "e2e/loader-bootstrap-contract.json",
    "e2e/mod-compatibility-contract.json",
    "e2e/mod_compatibility.py",
    "e2e/mod_compatibility_visual.py",
    "e2e/options.txt.template",
    "e2e/orchestrator.py",
    "e2e/packaged_runtime.py",
    "e2e/requirements.txt",
    "e2e/run-e2e.sh",
    "e2e/runtime_store.py",
    "e2e/scenario-contract.json",
    "e2e/scenario_contract.py",
    "e2e/visual_evidence.py",
    "e2e/visual_review.py",
    "e2e/visual_review_prompt.md",
    "e2e/visual_review_runner.py",
    "e2e/visual_review_semantic_prompt.md",
    "e2e/visual_review_semantic_verify_prompt.md",
    "e2e/visual_review_verify_prompt.md",
    "e2e/visual_review_workflow.js",
    "gradle/archive-conventions.gradle.kts",
    "gradle/e2e-harness-conventions.gradle.kts",
    "gradle/fml-metadata-conventions.gradle.kts",
    "gradle/no-remap-shadow-conventions.gradle.kts",
    "gradle/release-matrix.settings.gradle.kts",
    "gradle/wrapper",
    "gradlew",
    "scripts/ci/e2e_impact.py",
    "scripts/ci/e2e_job_graph.py",
    "scripts/ci/visual_anchor_certification.py",
    "scripts/ci/version_port_conflicts.py",
    "scripts/ci/version_port_merge.py",
    "scripts/release/artifact_manifest.py",
    "scripts/release/generate_sbom.py",
    "scripts/release/matrix.py",
    "scripts/release/release_identity.py",
    "scripts/release/verify_reproducibility.py",
    "scripts/release/version_branches.py",
    "scripts/release/workflow_guidance.py",
    "settings.gradle.kts",
    "stonecutter.gradle.kts",
)
VERSION_SPECIFIC_CONTROLLER_PATHS = (
    # Resource-pack format follows the target Minecraft version.
    "common/src/e2e/resources/pack.mcmeta",
)
CONTROLLER_SKEW_EXIT_CODE = 78
MAX_ADVISORY_CONTROLLER_PATHS = 100
ADVISORY_CONTROLLER_PREFIXES = (
    ".github/actions/",
    ".github/workflows/",
    "e2e/",
    "scripts/ci/",
    "scripts/release/tests/",
)


class JobGraphError(ValueError):
    """Raised when a run did not execute the protected expected topology."""


class ControllerSkewError(JobGraphError):
    """Raised when evidence was produced by code that is not yet protected."""


@dataclass(frozen=True)
class BootstrapContract:
    loaders: dict[str, dict[str, str]]
    release_build_scripts: dict[str, dict[str, str]]


def _git(repository: Path, *arguments: str) -> bytes:
    try:
        completed = subprocess.run(
            ("git", *arguments),
            cwd=repository,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except OSError as exc:
        raise JobGraphError(f"cannot execute git: {exc}") from exc
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise JobGraphError(detail or f"git {' '.join(arguments)} failed")
    return completed.stdout


def _git_object_exists(repository: Path, object_name: str) -> bool:
    try:
        completed = subprocess.run(
            ("git", "cat-file", "-e", object_name),
            cwd=repository,
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        raise JobGraphError(f"cannot execute git: {exc}") from exc
    return completed.returncode == 0


def validate_controller_parity(
    repository: Path,
    *,
    protected_sha: str,
    head_sha: str,
    repository_head_sha: str | None = None,
    paths: tuple[str, ...] = PROTECTED_CONTROLLER_PATHS,
    version_specific_paths: tuple[str, ...] = (),
) -> tuple[str, ...]:
    """Require exact protected controllers while preserving declared version adapters.

    The repository may have either the evidence head or the protected policy head checked out;
    both commits must already exist in its object database.
    """

    expected_repository_head = repository_head_sha or head_sha
    if (
        not SHA.fullmatch(protected_sha)
        or not SHA.fullmatch(head_sha)
        or not SHA.fullmatch(expected_repository_head)
    ):
        raise JobGraphError("controller parity requires exact lowercase commit SHAs")
    if expected_repository_head not in {protected_sha, head_sha}:
        raise JobGraphError(
            "checked-out repository head must be the protected or evidence commit"
        )
    repository = repository.resolve()
    current = _git(repository, "rev-parse", "HEAD").decode("ascii").strip()
    if current != expected_repository_head:
        raise JobGraphError(
            f"checked-out head {current!r} does not match requested repository head "
            f"{expected_repository_head!r}"
        )
    if not paths or len(paths) != len(set(paths)):
        raise JobGraphError("protected controller paths must be non-empty and unique")
    if len(version_specific_paths) != len(set(version_specific_paths)):
        raise JobGraphError("version-specific controller paths must be unique")
    for path in (*paths, *version_specific_paths):
        if (
            not isinstance(path, str)
            or not path
            or path.startswith("/")
            or ".." in Path(path).parts
        ):
            raise JobGraphError(f"invalid protected controller path {path!r}")
        protected_exists = _git_object_exists(repository, f"{protected_sha}:{path}")
        head_exists = _git_object_exists(repository, f"{head_sha}:{path}")
        if protected_exists != head_exists:
            raise ControllerSkewError(
                f"port E2E controller presence differs from protected master: {path}"
            )
    outside_roots = [
        path
        for path in version_specific_paths
        if not any(path == root or path.startswith(f"{root}/") for root in paths)
    ]
    if outside_roots:
        raise JobGraphError(
            "version-specific controller paths fall outside protected roots: "
            + ", ".join(outside_roots)
        )
    changed_raw = _git(
        repository,
        "diff",
        "--no-ext-diff",
        "--name-only",
        "-z",
        protected_sha,
        head_sha,
        "--",
        *paths,
    )
    try:
        changed = tuple(
            sorted(
                item.decode("utf-8", errors="strict")
                for item in changed_raw.split(b"\0")
                if item
            )
        )
    except UnicodeDecodeError as exc:
        raise JobGraphError("protected controller diff contains a non-UTF-8 path") from exc
    unexpected = tuple(
        path for path in changed if path not in set(version_specific_paths)
    )
    if unexpected:
        raise ControllerSkewError(
            "port E2E controller differs from protected master: "
            + ", ".join(unexpected)
        )
    return paths


def validate_advisory_controller_skew(
    repository: Path,
    *,
    protected_sha: str,
    head_sha: str,
) -> tuple[str, ...]:
    """Allow a skipped advisory review only for a bounded automation-only pull request."""

    changed_raw = _git(
        repository,
        "diff",
        "--no-ext-diff",
        "--no-renames",
        "--name-only",
        "--diff-filter=ACDMRTUXB",
        "-z",
        protected_sha,
        head_sha,
        "--",
    )
    try:
        changed = tuple(
            sorted(
                item.decode("utf-8", errors="strict")
                for item in changed_raw.split(b"\0")
                if item
            )
        )
    except UnicodeDecodeError as exc:
        raise JobGraphError("advisory controller diff contains a non-UTF-8 path") from exc
    if not changed or len(changed) > MAX_ADVISORY_CONTROLLER_PATHS:
        raise JobGraphError("advisory controller diff is empty or exceeds its path bound")
    unsafe: list[str] = []
    for path in changed:
        parsed = PurePosixPath(path)
        canonical = (
            "\\" not in path
            and not any(ord(character) < 32 or ord(character) == 127 for character in path)
            and not parsed.is_absolute()
            and not any(part in {"", ".", ".."} for part in parsed.parts)
            and parsed.as_posix() == path
        )
        if not canonical or not any(
            path.startswith(prefix) for prefix in ADVISORY_CONTROLLER_PREFIXES
        ):
            unsafe.append(path)
    if unsafe:
        raise JobGraphError(
            "controller skew is mixed with non-automation paths: "
            + ", ".join(repr(path) for path in unsafe)
        )
    return changed


def expected_scenario_jobs(matrix_path: Path) -> tuple[str, ...]:
    return expected_scenario_jobs_for(matrix_path, "pr-anchors")


def expected_scenario_jobs_for(
    matrix_path: Path, matrix_kind: str
) -> tuple[str, ...]:
    data = load_matrix(matrix_path)
    return expected_scenario_jobs_from_data(
        data,
        matrix_kind,
        read_mod_version(matrix_path, data),
    )


def expected_scenario_jobs_from_data(
    data: dict[str, Any], matrix_kind: str, mod_version: str
) -> tuple[str, ...]:
    matrix = gha_matrix(
        data,
        matrix_kind,
        mod_version,
    )
    rows = matrix.get("include")
    if not isinstance(rows, list) or not rows:
        raise JobGraphError("protected PR anchor matrix is empty")
    names: list[str] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise JobGraphError(f"protected PR anchor {index} is not an object")
        identity = row.get("id")
        if not isinstance(identity, str) or not identity:
            raise JobGraphError(f"protected PR anchor {index} has no id")
        names.append(identity + SCENARIO_SUFFIX)
    if len(names) != len(set(names)):
        raise JobGraphError("protected PR anchor job names are duplicated")
    return tuple(sorted(names))


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key {key!r}")
        value[key] = item
    return value


def load_bootstrap_contract(path: Path) -> BootstrapContract:
    try:
        payload = path.read_bytes()
        if not payload or len(payload) > 64 * 1024:
            raise ValueError("bootstrap contract size is outside 1..65536 bytes")
        document = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise JobGraphError(f"invalid loader bootstrap contract {path}: {exc}") from exc
    if not isinstance(document, dict) or set(document) != {
        "schema_version",
        "loaders",
        "release_build_scripts",
    }:
        raise JobGraphError("loader bootstrap contract has an invalid root schema")
    if document.get("schema_version") != 2:
        raise JobGraphError("loader bootstrap contract schema version must be 2")
    loaders = document.get("loaders")
    if not isinstance(loaders, dict) or set(loaders) != {"fabric", "forge", "neoforge"}:
        raise JobGraphError("loader bootstrap contract must cover every supported loader")
    validated: dict[str, dict[str, str]] = {}
    for loader, record in loaders.items():
        if not isinstance(record, dict) or set(record) != {"files"}:
            raise JobGraphError(f"loader bootstrap record is invalid for {loader}")
        files = record.get("files")
        if not isinstance(files, dict) or not files:
            raise JobGraphError(f"loader bootstrap files are empty for {loader}")
        expected: dict[str, str] = {}
        for raw_path, digest in files.items():
            if (
                not isinstance(raw_path, str)
                or not raw_path.startswith(f"{loader}/src/e2e/")
                or "\\" in raw_path
                or ".." in Path(raw_path).parts
                or not isinstance(digest, str)
                or not BLOB_SHA256.fullmatch(digest)
            ):
                raise JobGraphError(
                    f"loader bootstrap file identity is invalid for {loader}: {raw_path!r}"
                )
            expected[raw_path] = digest
        validated[loader] = expected
    raw_build_scripts = document.get("release_build_scripts")
    if (
        not isinstance(raw_build_scripts, dict)
        or not raw_build_scripts
        or len(raw_build_scripts) > 64
    ):
        raise JobGraphError("release build-script contract must contain 1..64 branches")
    release_build_scripts: dict[str, dict[str, str]] = {}
    for branch, records in raw_build_scripts.items():
        if not isinstance(branch, str) or not RELEASE_BRANCH.fullmatch(branch):
            raise JobGraphError(f"invalid release branch in bootstrap contract: {branch!r}")
        if (
            not isinstance(records, dict)
            or not records
            or any(loader not in validated for loader in records)
            or any(
                not isinstance(digest, str) or not BLOB_SHA256.fullmatch(digest)
                for digest in records.values()
            )
        ):
            raise JobGraphError(
                f"invalid release build-script hashes for bootstrap contract branch {branch}"
            )
        release_build_scripts[branch] = dict(records)
    return BootstrapContract(validated, release_build_scripts)


def _loader_tree_entries(
    repository: Path, head_sha: str, loader: str
) -> dict[str, tuple[str, str]]:
    raw = _git(
        repository,
        "ls-tree",
        "-r",
        "-z",
        "--full-tree",
        head_sha,
        "--",
        f"{loader}/src/e2e",
    )
    entries: dict[str, tuple[str, str]] = {}
    for record in (item for item in raw.split(b"\0") if item):
        try:
            metadata, raw_path = record.split(b"\t", 1)
            mode, kind, object_id = metadata.decode("ascii").split(" ")
            path = raw_path.decode("utf-8")
        except (UnicodeDecodeError, ValueError) as exc:
            raise JobGraphError(f"malformed {loader} E2E bootstrap tree") from exc
        if path in entries:
            raise JobGraphError(f"duplicate {loader} E2E bootstrap path {path!r}")
        if mode != "100644" or kind != "blob" or not SHA.fullmatch(object_id):
            raise JobGraphError(
                f"{loader} E2E bootstrap must contain only regular non-executable blobs: {path}"
            )
        entries[path] = (object_id, mode)
    return entries


def validate_loader_bootstraps(
    repository: Path,
    *,
    head_sha: str,
    matrix_path: Path,
    contract_path: Path = DEFAULT_BOOTSTRAP_CONTRACT,
    matrix_data: dict[str, Any] | None = None,
) -> tuple[str, ...]:
    """Authenticate each active loader entrypoint, manifest, and final Gradle binding."""

    if not SHA.fullmatch(head_sha):
        raise JobGraphError("loader bootstrap validation requires an exact commit SHA")
    matrix = matrix_data if matrix_data is not None else load_matrix(matrix_path)
    artifacts = matrix.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        raise JobGraphError("release matrix has no artifacts for loader bootstrap validation")
    active = tuple(
        sorted(
            {
                artifact.get("loader")
                for artifact in artifacts
                if isinstance(artifact, dict)
                and isinstance(artifact.get("loader"), str)
            }
        )
    )
    if not active or any(loader not in {"fabric", "forge", "neoforge"} for loader in active):
        raise JobGraphError(f"release matrix has invalid active loaders: {active!r}")
    contract = load_bootstrap_contract(contract_path)
    project = matrix.get("project")
    release_branch = project.get("release_branch") if isinstance(project, dict) else None
    if not isinstance(release_branch, str) or not RELEASE_BRANCH.fullmatch(release_branch):
        raise JobGraphError("release matrix has no valid project.release_branch")
    expected_build_scripts = contract.release_build_scripts.get(release_branch)
    if expected_build_scripts is None or set(expected_build_scripts) != set(active):
        raise JobGraphError(
            f"bootstrap contract does not bind every active loader for {release_branch}"
        )
    repository = repository.resolve()
    for loader in active:
        expected = contract.loaders[loader]
        observed = _loader_tree_entries(repository, head_sha, loader)
        if set(observed) != set(expected):
            raise JobGraphError(
                f"{loader} E2E bootstrap inventory disagrees with protected contract: "
                f"missing={sorted(set(expected) - set(observed))}, "
                f"extra={sorted(set(observed) - set(expected))}"
            )
        for path, digest in expected.items():
            object_id, _mode = observed[path]
            actual = hashlib.sha256(
                _git(repository, "cat-file", "blob", object_id)
            ).hexdigest()
            if actual != digest:
                raise JobGraphError(
                    f"{loader} E2E bootstrap differs from protected contract: {path}"
                )
        build_script = _git(
            repository, "show", f"{head_sha}:{loader}/build.gradle.kts"
        )
        build_digest = hashlib.sha256(build_script).hexdigest()
        if build_digest != expected_build_scripts[loader]:
            raise JobGraphError(
                f"{loader} build script differs from the protected contract for "
                f"{release_branch}"
            )
        try:
            build_text = build_script.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise JobGraphError(f"{loader} build script is not UTF-8") from exc
        nonempty = [line.strip() for line in build_text.splitlines() if line.strip()]
        if (
            not nonempty
            or nonempty[-1] != HARNESS_CONVENTION_BINDING
            or nonempty.count(HARNESS_CONVENTION_BINDING) != 1
        ):
            raise JobGraphError(
                f"{loader} build script must end with the single protected E2E convention binding"
            )
    return active


def _jobs(payload: Any) -> list[dict[str, Any]]:
    pages = payload if isinstance(payload, list) else [payload]
    jobs: list[dict[str, Any]] = []
    for page_index, page in enumerate(pages):
        if not isinstance(page, dict) or not isinstance(page.get("jobs"), list):
            raise JobGraphError(f"jobs API page {page_index} is invalid")
        for job_index, job in enumerate(page["jobs"]):
            if not isinstance(job, dict):
                raise JobGraphError(
                    f"jobs API page {page_index} row {job_index} is invalid"
                )
            jobs.append(job)
            if len(jobs) > MAX_JOBS:
                raise JobGraphError("jobs API response exceeds its limit")
    if not jobs:
        raise JobGraphError("jobs API response is empty")
    return jobs


def _unique_named_job(jobs: list[dict[str, Any]], name: str) -> dict[str, Any]:
    selected = [job for job in jobs if job.get("name") == name]
    if len(selected) != 1:
        raise JobGraphError(f"expected exactly one {name!r} job, found {len(selected)}")
    return selected[0]


def _require_conclusion(job: dict[str, Any], name: str, conclusion: str) -> None:
    if job.get("status") != "completed" or job.get("conclusion") != conclusion:
        raise JobGraphError(
            f"job {name!r} must be completed/{conclusion}, got "
            f"{job.get('status')!r}/{job.get('conclusion')!r}"
        )


def validate_job_graph(
    payload: Any,
    *,
    policy: str,
    expected_scenarios: tuple[str, ...],
) -> dict[str, Any]:
    if policy not in {"full", "not-applicable"}:
        raise JobGraphError(f"unsupported runtime policy {policy!r}")
    if not expected_scenarios or len(expected_scenarios) != len(set(expected_scenarios)):
        raise JobGraphError("expected scenario job names must be non-empty and unique")

    jobs = _jobs(payload)
    policy_job = _unique_named_job(jobs, POLICY_JOB)
    build_job = _unique_named_job(jobs, BUILD_JOB)
    gate_job = _unique_named_job(jobs, GATE_JOB)
    _require_conclusion(policy_job, POLICY_JOB, "success")
    _require_conclusion(gate_job, GATE_JOB, "success")

    scenario_jobs = [
        job
        for job in jobs
        if isinstance(job.get("name"), str)
        and job["name"].endswith(SCENARIO_SUFFIX)
    ]
    observed_names = tuple(sorted(str(job["name"]) for job in scenario_jobs))
    if len(observed_names) != len(set(observed_names)):
        raise JobGraphError("packaged scenario job names are duplicated")

    if policy == "full":
        _require_conclusion(build_job, BUILD_JOB, "success")
        if observed_names != expected_scenarios:
            raise JobGraphError(
                "packaged scenario jobs disagree with the protected PR anchors: "
                f"observed={observed_names!r}, expected={expected_scenarios!r}"
            )
        for job in scenario_jobs:
            _require_conclusion(job, str(job["name"]), "success")
    else:
        _require_conclusion(build_job, BUILD_JOB, "skipped")
        # The unexpanded placeholder means the matrix produced no lane at all, which is
        # exactly what a non-runtime port must show; every observed job is still required
        # to be skipped below, so this admits no executed scenario.
        if observed_names not in {
            (),
            expected_scenarios,
            (UNEXPANDED_SCENARIO_JOB,),
        }:
            raise JobGraphError(
                "not-applicable run exposed a partial scenario matrix: "
                f"{observed_names!r}"
            )
        for job in scenario_jobs:
            _require_conclusion(job, str(job["name"]), "skipped")

    return {
        "schema_version": 1,
        "runtime_policy": policy,
        "expected_scenario_jobs": list(expected_scenarios),
        "observed_scenario_jobs": list(observed_names),
    }


def _read_json(path: Path) -> Any:
    try:
        raw = path.read_bytes()
        if not raw or len(raw) > 16 * 1024 * 1024:
            raise JobGraphError("jobs API JSON size is invalid")
        return json.loads(raw)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise JobGraphError(f"cannot read jobs API JSON {path}: {exc}") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument(
        "--matrix-properties",
        type=Path,
        help=(
            "separately authenticated Gradle properties for an inert matrix snapshot; "
            "omitting this option requires a complete checked-out source tree"
        ),
    )
    parser.add_argument("--jobs", type=Path, required=True)
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument(
        "--repository-head-sha",
        help="exact commit checked out in --repository (defaults to --head-sha)",
    )
    parser.add_argument("--protected-sha", required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument(
        "--matrix-kind",
        choices=("pr-anchors", "native-anchors"),
        default="pr-anchors",
    )
    parser.add_argument(
        "--bootstrap-contract",
        type=Path,
        default=DEFAULT_BOOTSTRAP_CONTRACT,
    )
    parser.add_argument("--runtime-policy", choices=("full", "not-applicable"), required=True)
    parser.add_argument("--allow-advisory-controller-skew", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.matrix_properties is None:
            matrix_data = load_matrix(args.matrix)
            mod_version = read_mod_version(args.matrix, matrix_data)
        else:
            matrix_data = load_matrix_snapshot(args.matrix, args.matrix_properties)
            mod_version = read_mod_version_from_properties(
                args.matrix_properties, matrix_data
            )
        active_loaders = validate_loader_bootstraps(
            args.repository,
            head_sha=args.head_sha,
            matrix_path=args.matrix,
            contract_path=args.bootstrap_contract,
            matrix_data=matrix_data,
        )
        expected = expected_scenario_jobs_from_data(
            matrix_data, args.matrix_kind, mod_version
        )
        validated = validate_job_graph(
            _read_json(args.jobs),
            policy=args.runtime_policy,
            expected_scenarios=expected,
        )
        # Validate the inert matrix, loader bootstrap, and observed job topology before
        # classifying a controller-only PR as advisory. A changed workflow cannot earn a green
        # skip merely by replacing the expected jobs with a fabricated successful gate.
        protected_paths = validate_controller_parity(
            args.repository,
            protected_sha=args.protected_sha,
            head_sha=args.head_sha,
            repository_head_sha=args.repository_head_sha,
            version_specific_paths=VERSION_SPECIFIC_CONTROLLER_PATHS,
        )
    except ControllerSkewError as exc:
        if not args.allow_advisory_controller_skew:
            print(f"Packaged E2E job graph validation failed: {exc}", file=sys.stderr)
            return 2
        try:
            validate_advisory_controller_skew(
                args.repository,
                protected_sha=args.protected_sha,
                head_sha=args.head_sha,
            )
        except JobGraphError as policy_exc:
            print(
                f"Packaged E2E job graph validation failed: {exc}; {policy_exc}",
                file=sys.stderr,
            )
            return 2
        print(f"Packaged E2E controller skew: {exc}", file=sys.stderr)
        return CONTROLLER_SKEW_EXIT_CODE
    except (JobGraphError, MatrixError) as exc:
        print(f"Packaged E2E job graph validation failed: {exc}", file=sys.stderr)
        return 2
    validated["protected_controller_paths"] = list(protected_paths)
    validated["version_specific_controller_paths"] = list(
        VERSION_SPECIFIC_CONTROLLER_PATHS
    )
    validated["active_loader_bootstraps"] = list(active_loaders)
    print(json.dumps(validated, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
