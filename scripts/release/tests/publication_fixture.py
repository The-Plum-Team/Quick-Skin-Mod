"""Small inert payloads with the real matrix, SBOM and publication identities."""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/release"))

from generate_sbom import stage_sbom
from matrix import load_matrix, select_release_target
from release_identity import derive


def bundle(stage: Path):
    matrix_path = ROOT / "release/release-matrix.json"
    matrix = load_matrix(matrix_path)
    identity = derive(matrix_path, matrix, target="26.1")
    selected = select_release_target(matrix, "26.1")
    manifest = {"schema_version": 2, "matrix": "release/release-matrix.json",
                "matrix_sha256": hashlib.sha256(matrix_path.read_bytes()).hexdigest(),
                "lane_count": selected["lane_count"], "mod_version": identity.mod_version,
                "git_commit": "a" * 40, "release": identity.manifest(), "artifacts": []}
    for row in selected["artifacts"]:
        filename = Path(row["jar"].format(mod_version=identity.mod_version)).name
        harness_name = Path(row["harness_jar"]).name
        payload = row["artifact_node"].encode()
        production = stage / "files" / filename
        harness = stage / "harness" / harness_name
        production.parent.mkdir(parents=True, exist_ok=True)
        harness.parent.mkdir(parents=True, exist_ok=True)
        production.write_bytes(payload)
        harness.write_bytes(b"harness-" + payload)
        record = {key: row[key] for key in ("artifact_node", "artifact_version", "loader", "game_versions")}
        record.update({"filename": filename, "path": f"files/{filename}", "bytes": len(payload),
                       **{algorithm: hashlib.new(algorithm, payload).hexdigest()
                          for algorithm in ("sha1", "sha256", "sha512")},
                       "harness": {"filename": harness_name, "path": f"harness/{harness_name}",
                                   "bytes": harness.stat().st_size,
                                   "sha256": hashlib.sha256(harness.read_bytes()).hexdigest()}})
        manifest["artifacts"].append(record)
    manifest["sbom"] = stage_sbom(ROOT, matrix_path, stage, selected, manifest)
    (stage / "artifacts.json").write_text(json.dumps(manifest, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return matrix, manifest
