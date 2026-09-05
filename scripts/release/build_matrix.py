#!/usr/bin/env python3
"""Build the complete matrix with one bounded, serial Gradle process per target.

This command builds production JARs, packaged harnesses, and unit tests. It does
not launch Minecraft or certify staged artifacts; verify_release.py remains the
packaging gate. A --target result is explicitly partial.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Callable

import matrix as release_matrix


ROOT = Path(__file__).resolve().parents[2]


def build_plan(
    data: dict[str, Any], *, target: str | None = None,
    clean: bool = False, rerun_tasks: bool = False,
) -> list[dict[str, Any]]:
    # Authenticate the complete inventory before selecting even one target.
    release_matrix.validate_matrix(data)
    selected = release_matrix.select_release_target(data, target) if target is not None else data
    versions = sorted(
        {row["artifact_version"] for row in selected["artifacts"]},
        key=lambda version: tuple(int(part) for part in version.split(".")),
    )
    plans = []
    for version in versions:
        arguments = ["--no-daemon", "--no-parallel", "--stacktrace", "--console=plain"]
        if rerun_tasks:
            arguments.append("--rerun-tasks")
        if data["schema_version"] == 3:
            arguments.append(f"-PquickskinTarget={version}")
        if clean:
            arguments.append("clean")
        arguments.extend(
            ("buildTargetLanes", "buildTargetE2EHarnesses") if data["schema_version"] == 3
            else ("buildAllLanes", "buildAllE2EHarnesses")
        )
        plans.append({
            "target": version,
            "artifact_nodes": [row["artifact_node"] for row in selected["artifacts"]
                               if row["artifact_version"] == version],
            "arguments": arguments,
        })
    return plans


def write_report(path: Path, report: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                     prefix=".matrix-build-", delete=False) as stream:
        temporary = Path(stream.name)
        json.dump(report, stream, indent=2)
        stream.write("\n")
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def output_hashes(root: Path, data: dict[str, Any], version: str) -> list[dict[str, str]]:
    mod_version = release_matrix.read_mod_version(root / "release/release-matrix.json", data)
    records = []
    for row in data["artifacts"]:
        if row["artifact_version"] != version:
            continue
        for kind in ("jar", "harness_jar"):
            relative = row[kind].replace("{mod_version}", mod_version)
            path = root / relative
            if not path.is_file() or path.is_symlink() or path.resolve() != path:
                raise OSError(f"missing or linked {kind} after successful build: {relative}")
            digest = hashlib.sha256()
            with path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            records.append({"artifact_node": row["artifact_node"], "kind": kind,
                            "path": relative, "sha256": digest.hexdigest()})
    return records


def execute_build(
    root: Path, *, target: str | None, clean: bool, rerun_tasks: bool, report_path: Path,
    runner: Callable[..., Any] = subprocess.run,
) -> int:
    matrix_path = root / "release/release-matrix.json"
    # Invalidate any previous success even when validation or process startup fails.
    report: dict[str, Any] = {"schema_version": 1, "status": "running",
                              "scope": "target" if target is not None else "full",
                              "target": target, "results": []}
    write_report(report_path, report)
    try:
        matrix_bytes = matrix_path.read_bytes()
        data = release_matrix.load_matrix(matrix_path)
        if matrix_path.read_bytes() != matrix_bytes:
            raise OSError("release matrix changed during validation")
        plan = build_plan(data, target=target, clean=clean, rerun_tasks=rerun_tasks)
        report.update(matrix_sha256=hashlib.sha256(matrix_bytes).hexdigest(), plan=plan)
        write_report(report_path, report)
        wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
        for index, item in enumerate(plan, 1):
            if matrix_path.read_bytes() != matrix_bytes:
                raise OSError("release matrix changed during the build")
            print(f"Building target {item['target']} ({index}/{len(plan)})", flush=True)
            started = time.monotonic()
            # Each --no-daemon invocation exits before the next begins. Never nest
            # Gradle or run multiple Architectury transforms in the same process.
            result = runner([str(wrapper), *item["arguments"]], cwd=root, check=False)
            record = {"target": item["target"], "returncode": result.returncode,
                      "seconds": round(time.monotonic() - started, 3)}
            report["results"].append(record)
            if result.returncode != 0:
                report["status"] = "failed"
                write_report(report_path, report)
                return 1
            record["outputs"] = output_hashes(root, data, item["target"])
            write_report(report_path, report)
        if matrix_path.read_bytes() != matrix_bytes:
            raise OSError("release matrix changed during the build")
        # Later clean tasks must not invalidate artifacts built by earlier targets.
        for result in report["results"]:
            if output_hashes(root, data, result["target"]) != result["outputs"]:
                raise OSError(f"outputs changed after building target {result['target']}")
        report["status"] = "success"
        write_report(report_path, report)
        print(f"Built {len(plan)} targets; scope={report['scope']}; report={report_path}", flush=True)
        return 0
    except (OSError, release_matrix.MatrixError, KeyboardInterrupt) as exc:
        report.update(status="failed", error=str(exc) or "interrupted")
        write_report(report_path, report)
        print(f"Matrix build failed: {report['error']}", file=sys.stderr, flush=True)
        return 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", help="Build one target, explicitly recorded as a partial result")
    parser.add_argument("--clean", action="store_true", help="Clean each target before building it")
    parser.add_argument("--rerun-tasks", action="store_true", help="Rebuild every target for reproducibility")
    parser.add_argument("--report", type=Path, default=Path("build/matrix-build/results.json"))
    parser.add_argument("--plan", action="store_true", help="Print the validated plan without building")
    args = parser.parse_args(argv)
    if args.plan:
        try:
            data = release_matrix.load_matrix(ROOT / "release/release-matrix.json")
            print(json.dumps(build_plan(data, target=args.target, clean=args.clean,
                                        rerun_tasks=args.rerun_tasks), indent=2))
            return 0
        except (OSError, release_matrix.MatrixError) as exc:
            parser.error(str(exc))
    report_path = args.report.resolve()
    if not report_path.is_relative_to((ROOT / "build").resolve()):
        parser.error("--report must be inside this checkout's build directory")
    return execute_build(ROOT, target=args.target, clean=args.clean,
                         rerun_tasks=args.rerun_tasks, report_path=report_path)


if __name__ == "__main__":
    raise SystemExit(main())
