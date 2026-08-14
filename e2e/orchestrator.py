#!/usr/bin/env python3
"""Run each exact Quick Skin release artifact in its isolated production runtime."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts" / "release"))

from artifact_manifest import (  # noqa: E402
    ArtifactManifestError,
    current_git_commit,
    load_artifact_manifest,
)
from matrix import MatrixError, load_matrix  # noqa: E402
from mod_compatibility import (  # noqa: E402
    DEFAULT_CONTRACT as DEFAULT_COMPATIBILITY_CONTRACT,
    CompatibilityContractError,
    CompatibilityLane,
    load_contract as load_compatibility_contract,
    materialize_lane,
    resolve_lane as resolve_compatibility_lane,
)
from packaged_runtime import (  # noqa: E402
    PackagedRuntimeSession,
    RuntimeFailure,
    run_packaged_row,
)
from release_identity import ReleaseIdentityError, derive as derive_release_identity  # noqa: E402
from runtime_store import RunWorkspace, RuntimeStoreError, WorkspacePromotion  # noqa: E402
from scenario_contract import default_contract  # noqa: E402


SCENARIO_CONTRACT = default_contract()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, default=Path("release/release-matrix.json"))
    parser.add_argument(
        "--artifacts-manifest", type=Path, default=Path("build/release/artifacts.json")
    )
    parser.add_argument(
        "--row-json",
        help="one GitHub matrix row as JSON; only its locked identity fields are trusted",
    )
    parser.add_argument("--artifact-node", help="restrict to one artifact node")
    parser.add_argument("--runtime-version", help="restrict to one runtime version")
    parser.add_argument("--loader", choices=("fabric", "forge", "neoforge"))
    parser.add_argument(
        "--scenarios",
        help="comma-separated scenario selection emitted from the scenario contract",
    )
    parser.add_argument("--compatibility-mod", help="run with one lock-selected optional mod")
    parser.add_argument(
        "--compatibility-contract",
        type=Path,
        default=DEFAULT_COMPATIBILITY_CONTRACT,
    )
    parser.add_argument("--output-root", type=Path, default=Path("e2e-out"))
    parser.add_argument("--packaged", action="store_true", help="required acknowledgement")
    parser.add_argument("--list", action="store_true", help="print resolved logical rows and exit")
    return parser.parse_args()


def absolute(path: Path) -> Path:
    return path.resolve() if path.is_absolute() else (REPO / path).resolve()


def select_rows(data: dict[str, Any], args: argparse.Namespace) -> list[dict[str, Any]]:
    rows = list(data["runtimes"])
    if args.row_json:
        try:
            requested = json.loads(args.row_json)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid --row-json: {exc}") from exc
        if not isinstance(requested, dict):
            raise ValueError("--row-json must contain one JSON object")
        identity = (
            requested.get("artifact_node"),
            requested.get("runtime_version"),
            requested.get("loader"),
        )
        rows = [
            row
            for row in rows
            if (row["artifact_node"], row["runtime_version"], row["loader"]) == identity
        ]
        if len(rows) != 1:
            raise ValueError(f"--row-json does not identify exactly one locked runtime row: {identity}")
    if args.artifact_node:
        rows = [row for row in rows if row["artifact_node"] == args.artifact_node]
    if args.runtime_version:
        rows = [row for row in rows if row["runtime_version"] == args.runtime_version]
    if args.loader:
        rows = [row for row in rows if row["loader"] == args.loader]
    if not rows:
        raise ValueError("runtime selection is empty")
    return rows


def scenarios_for(data: dict[str, Any], row: dict[str, Any], args: argparse.Namespace) -> list[str]:
    scenarios = (
        [value.strip() for value in args.scenarios.split(",") if value.strip()]
        if args.scenarios
        else list(
            SCENARIO_CONTRACT.scenarios_for_profile("runtime-default")
        )
    )
    known = set(SCENARIO_CONTRACT.scenario_ids)
    unknown = [scenario for scenario in scenarios if scenario not in known]
    if unknown:
        raise ValueError(f"unknown E2E scenarios: {unknown}; known: {sorted(known)}")
    return scenarios


def read_manifest(
    path: Path,
    data: dict[str, Any],
    matrix_path: Path,
    repository: Path,
    expected_mod_version: str,
    expected_commit: str,
    expected_release: dict[str, Any],
) -> dict[str, Any]:
    try:
        return load_artifact_manifest(
            path,
            repository=repository,
            matrix_path=matrix_path,
            matrix=data,
            stage=path.parent,
            expected_mod_version=expected_mod_version,
            expected_commit=expected_commit,
            expected_release=expected_release,
        )
    except ArtifactManifestError as exc:
        raise ValueError(str(exc)) from exc


def manifest_hash(manifest: dict[str, Any] | None, node: str) -> str:
    if manifest is None:
        return "from:artifact-manifest"
    records = [record for record in manifest["artifacts"] if record.get("artifact_node") == node]
    if len(records) != 1:
        raise ValueError(f"artifact manifest has {len(records)} records for {node}")
    return records[0]["sha256"]


def print_rows(
    data: dict[str, Any], rows: list[dict[str, Any]], args: argparse.Namespace, manifest: dict[str, Any] | None
) -> None:
    resolved: list[dict[str, Any]] = []
    for row in rows:
        for scenario in scenarios_for(data, row, args):
            resolved.append(
                {
                    "artifact_node": row["artifact_node"],
                    "runtime_version": row["runtime_version"],
                    "loader": row["loader"],
                    "scenario": scenario,
                    "jar_sha256": manifest_hash(manifest, row["artifact_node"]),
                    "port": 0,
                    "architectury_kind": row["architectury"]["kind"],
                }
            )
    print(json.dumps({"include": resolved}, indent=2))


def execute_packaged_rows(
    data: dict[str, Any],
    rows: list[dict[str, Any]],
    args: argparse.Namespace,
    manifest: dict[str, Any],
    manifest_path: Path,
    output_root: Path,
) -> tuple[list[dict[str, Any]], WorkspacePromotion]:
    """Run in disposable scratch space and atomically publish one evidence snapshot."""

    results: list[dict[str, Any]] = []
    with RunWorkspace.create(output_root, prefix=".evidence-run-") as evidence:
        with tempfile.TemporaryDirectory(
            prefix="quick-skin-e2e-scratch-parent-"
        ) as scratch_parent, RunWorkspace.create(
            Path(scratch_parent), prefix=".runtime-run-"
        ) as scratch:
            runtime_session = PackagedRuntimeSession.from_environment(scratch.path)
            compatibility_lane: CompatibilityLane | None = None
            compatibility_files: tuple[Path, ...] = ()
            compatibility_mod = getattr(args, "compatibility_mod", None)
            if compatibility_mod:
                if len(rows) != 1:
                    raise ValueError("compatibility execution requires exactly one runtime row")
                compatibility_contract = load_compatibility_contract(
                    absolute(getattr(args, "compatibility_contract", DEFAULT_COMPATIBILITY_CONTRACT))
                )
                selected_row = rows[0]
                compatibility_lane = resolve_compatibility_lane(
                    compatibility_contract,
                    mod_id=compatibility_mod,
                    artifact_node=selected_row["artifact_node"],
                    runtime_version=selected_row["runtime_version"],
                    loader=selected_row["loader"],
                )
                compatibility_files = materialize_lane(
                    compatibility_lane,
                    scratch.path / "compatibility-mod",
                )
            for row in rows:
                for scenario in scenarios_for(data, row, args):
                    print(
                        f">>> {row['artifact_node']} artifact on {row['runtime_version']} "
                        f"{row['loader']} / {scenario}",
                        flush=True,
                    )
                    compatibility_arguments = (
                        {
                            "compatibility_lane": compatibility_lane,
                            "compatibility_files": compatibility_files,
                        }
                        if compatibility_lane is not None
                        else {}
                    )
                    result = run_packaged_row(
                        REPO,
                        data,
                        row,
                        scenario,
                        manifest,
                        manifest_path,
                        evidence.path,
                        runtime_session,
                        **compatibility_arguments,
                    )
                    results.append(result)
                    print(
                        f"<<< {result['status'].upper()} ({result['elapsed_s']}s)"
                        + (f": {result['error']}" if result.get("error") else ""),
                        flush=True,
                    )
                    if (
                        compatibility_lane is not None
                        and scenario == "mod-compatibility"
                        and result["status"] != "pass"
                    ):
                        print(
                            "Compatibility activation failed; skipping the base suite for this lane.",
                            flush=True,
                        )
                        break

            runtime_store_metrics = runtime_session.gc()

        resolved = [
            {
                key: result[key]
                for key in (
                    "artifact_node",
                    "runtime_version",
                    "loader",
                    "scenario",
                    "jar_sha256",
                    "port",
                )
            }
            | (
                {"compatibility_mod": result["compatibility"]["id"]}
                if "compatibility" in result
                else {}
            )
            for result in results
        ]
        (evidence.path / "resolved-matrix.json").write_text(
            json.dumps({"rows": resolved}, indent=2) + "\n", encoding="utf-8"
        )
        (evidence.path / "summary.json").write_text(
            json.dumps(
                {"results": results, "runtime_store": runtime_store_metrics},
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        (evidence.path / "runtime-store.json").write_text(
            json.dumps(runtime_store_metrics, indent=2) + "\n",
            encoding="utf-8",
        )
        promotion = evidence.promote_to(output_root / "current")

    return results, promotion


def main() -> int:
    args = parse_args()
    matrix_path = absolute(args.matrix)
    manifest_path = absolute(args.artifacts_manifest)
    output_root = absolute(args.output_root)
    try:
        data = load_matrix(matrix_path)
        identity = derive_release_identity(matrix_path, data)
        commit = current_git_commit(REPO)
        rows = select_rows(data, args)
        manifest = (
            read_manifest(
                manifest_path,
                data,
                matrix_path,
                REPO,
                identity.mod_version,
                commit,
                identity.manifest(),
            )
            if manifest_path.exists()
            else None
        )
        if args.list:
            print_rows(data, rows, args, manifest)
            return 0
        if not args.packaged:
            raise ValueError(
                "the development-run launcher was retired; pass --packaged to run fan-in jars"
            )
        if manifest is None:
            raise ValueError(f"packaged execution requires {manifest_path}")

        results, promotion = execute_packaged_rows(
            data,
            rows,
            args,
            manifest,
            manifest_path,
            output_root,
        )
        passed = sum(result["status"] == "pass" for result in results)
        action = "replaced" if promotion.replaced else "created"
        recovery = " after recovering last-good" if promotion.recovered_interrupted else ""
        print(
            f"{passed}/{len(results)} packaged runtime rows passed; "
            f"{action} {promotion.current}{recovery}"
        )
        return 0 if passed == len(results) else 1
    except (
        ArtifactManifestError,
        CompatibilityContractError,
        MatrixError,
        ReleaseIdentityError,
        RuntimeFailure,
        RuntimeStoreError,
        ValueError,
        OSError,
    ) as exc:
        print(f"E2E configuration failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
