from __future__ import annotations

import copy
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

from visual_review_targets import (  # noqa: E402
    DEFAULT_MATRIX, MAX_ARTIFACT_BYTES, ReviewTargetError, plan_targets, read_json,
    validate_target_proof,
)
from matrix import gha_matrix, load_matrix, read_mod_version  # noqa: E402
from e2e_job_graph import SCENARIO_SUFFIX  # noqa: E402
from scripts.ci.tests.test_workflow_security import step_script  # noqa: E402

SOURCE_SHA = "a" * 40
RUN_ID = 55


class VisualReviewTargetsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = load_matrix(DEFAULT_MATRIX)
        self.rows = gha_matrix(self.matrix, "pr-anchors", read_mod_version(DEFAULT_MATRIX, self.matrix))["include"]
        self.artifacts = self.records(self.rows)

    def records(self, rows: list[dict]) -> list[dict]:
        return [{"id": index + 1, "name": f"packaged-e2e-{row['id']}", "size_in_bytes": 1024,
                 "digest": "sha256:" + "b" * 64, "expired": False,
                 "workflow_run": {"id": RUN_ID, "head_branch": "master", "head_sha": SOURCE_SHA}}
                for index, row in enumerate(rows)]

    def plan(self, artifacts=None, **arguments):
        return plan_targets(self.artifacts if artifacts is None else artifacts,
                            source_run_id=RUN_ID, source_branch=arguments.pop("source_branch", "master"),
                            source_sha=SOURCE_SHA, **arguments)

    def proof(self, target: dict) -> dict:
        jobs = sorted(row["id"] + SCENARIO_SUFFIX for row in self.rows)
        return {"schema_version": 6, "bundle_key": target["bundle_key"],
                "matrix_sha256": target["matrix_sha256"], "matrix_kind": "pr-anchors",
                "review_mode": target["review_mode"], "source_branch": "master", "source_sha": SOURCE_SHA,
                "implementation_sha": SOURCE_SHA, "master_source_sha": SOURCE_SHA, "source_run_id": RUN_ID,
                "artifact_inventory": target["artifact_inventory"],
                "job_graph": {"schema_version": 1, "runtime_policy": "full",
                              "expected_scenario_jobs": jobs, "observed_scenario_jobs": jobs}}

    def run_shell(self, script: str, folder: Path, arguments: dict[str, str]):
        tools = folder / "tools"
        tools.mkdir(exist_ok=True)
        python = tools / "python3"
        python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + ' "$@"\n')
        python.chmod(0o755)
        jq = shutil.which("jq")
        self.assertIsNotNone(jq)
        env = {"PATH": os.pathsep.join((str(tools), str(Path(jq).parent), os.defpath)),
               "RUNNER_TEMP": str(folder), **arguments}
        return subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", "set -euo pipefail\n" + script],
                              cwd=ROOT, env=env, text=True, capture_output=True, timeout=30)

    def test_actual_curator_shell_recomputes_one_target_before_downloading_images(self) -> None:
        script = step_script("visual-review.yml", "curate", "Validate and curate exact packaged evidence")
        excerpt = script[script.index("expected_artifacts="):script.index(
            'if [[ -n "$TARGET_MINECRAFT_VERSION" ]]; then', script.index("expected_artifacts="))]
        target = self.plan()["include"][0]
        arguments = {"ARTIFACT_INVENTORY": json.dumps(target["artifact_inventory"]),
                     "COMPLETE_ARTIFACT_INVENTORY": json.dumps(self.artifacts),
                     "TARGET_BUNDLE_KEY": target["bundle_key"],
                     "TARGET_MINECRAFT_VERSION": target["minecraft_target"],
                     "TARGET_MATRIX_SHA256": target["matrix_sha256"],
                     "SOURCE_SHA": SOURCE_SHA, "SOURCE_RUN_ID": str(RUN_ID),
                     "source_run": json.dumps({"head_branch": "master"}), "matrix_kind": "pr-anchors"}
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            matrix_path = folder / "expected-e2e-matrix.json"
            matrix_path.write_text(json.dumps({"include": self.rows}))
            result = self.run_shell(excerpt, folder, arguments)
            self.assertEqual(0, result.returncode, result.stderr[:1500])
            selected = json.loads(matrix_path.read_bytes())["include"]
            self.assertEqual({"fabric-1.20.1", "forge-1.20.1"}, {row["artifact_node"] for row in selected})
            for field, value in (("TARGET_BUNDLE_KEY", "mc1.21.8"),
                                 ("TARGET_MATRIX_SHA256", "0" * 64),
                                 ("COMPLETE_ARTIFACT_INVENTORY", json.dumps(self.artifacts[:-1]))):
                with self.subTest(field=field):
                    matrix_path.write_text(json.dumps({"include": self.rows}))
                    result = self.run_shell(excerpt, folder, {**arguments, field: value})
                    self.assertNotEqual(0, result.returncode)

    def test_actual_drainer_shell_binds_the_proof_to_its_queue_target(self) -> None:
        script = step_script("visual-review-drain.yml", "review", "Fetch and verify the exact curated capsule")
        start = script.index('jq -e \\\n  --arg implementation_sha "$IMPLEMENTATION_SHA" \\\n  --arg manifest_sha256')
        excerpt = script[start:script.index("validation_arguments=(", start)]
        target = self.plan()["include"][0]
        proof = {**self.proof(target), "manifest_sha256": "c" * 64,
                 "scenario_contract_sha256": hashlib.sha256((ROOT / "e2e/scenario-contract.json").read_bytes()).hexdigest(),
                 "frame_count": 180, "image_count": 2, "image_bytes": 512, "visual_reference": None,
                 "compatibility_impact": {"schema_version": 1, "compatibility_required": True,
                                           "paths": [], "impact_paths": []}}
        arguments = {"IMPLEMENTATION_SHA": SOURCE_SHA, "BUNDLE_KEY": target["bundle_key"],
                     "SOURCE_RUN_ID": str(RUN_ID), **{key: str(proof[key]) for key in
                        ("source_branch", "source_sha", "manifest_sha256", "review_mode",
                         "scenario_contract_sha256", "frame_count", "image_count", "image_bytes")}}
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            path = folder / "proof.json"
            path.write_text(json.dumps(proof))
            arguments["proof"] = str(path)
            result = self.run_shell(excerpt, folder, arguments)
            self.assertEqual(0, result.returncode, result.stderr[:1500])
            for field, value in (("bundle_key", "mc1.21.8"), ("matrix_sha256", "0" * 64),
                                 ("source_run_id", True), ("extra_field", "unexpected")):
                with self.subTest(field=field):
                    path.write_text(json.dumps({**proof, field: value}))
                    result = self.run_shell(excerpt, folder, arguments)
                    self.assertNotEqual(0, result.returncode)

    def test_complete_inventory_partitions_every_artifact_once_and_binds_full_matrix(self) -> None:
        rows = self.plan()["include"]
        versions = {row["artifact_version"] for row in self.matrix["artifacts"]}
        self.assertEqual({f"mc{version}" for version in versions}, {row["bundle_key"] for row in rows})
        self.assertEqual(versions, {row["minecraft_target"] for row in rows})
        self.assertEqual([row["id"] for row in self.artifacts], sorted(
            item["id"] for row in rows for item in row["artifact_inventory"]))
        digest = hashlib.sha256(DEFAULT_MATRIX.read_bytes()).hexdigest()
        self.assertEqual({digest}, {row["matrix_sha256"] for row in rows})
        self.assertEqual([f"mc{self.matrix['unit_test_version']}"],
                         [row["bundle_key"] for row in rows if row["review_mode"] == "anchor-semantic"])
        for row in rows:
            self.assertEqual(2, len(row["artifact_inventory"]))
            validate_target_proof(self.proof(row))

    def test_scheduled_profile_uses_its_own_exact_lane_names(self) -> None:
        rows = gha_matrix(self.matrix, "native-anchors", read_mod_version(DEFAULT_MATRIX, self.matrix))["include"]
        scheduled = self.plan(self.records(rows), matrix_kind="native-anchors")
        self.assertEqual(len({row["runtime_version"] for row in rows}), len(scheduled["include"]))
        with self.assertRaisesRegex(ReviewTargetError, "unexpected identity"):
            self.plan(matrix_kind="native-anchors")

    def test_missing_extra_duplicate_and_wrong_owner_artifacts_are_rejected(self) -> None:
        for artifacts in (self.artifacts[:-1], self.artifacts + [self.artifacts[0]],
                          self.artifacts[:-1] + [self.artifacts[0]]):
            with self.subTest(count=len(artifacts)), self.assertRaises(ReviewTargetError):
                self.plan(artifacts)
        mutations = (("id", True), ("id", 0), ("size_in_bytes", MAX_ARTIFACT_BYTES + 1),
                     ("size_in_bytes", 1.5), ("digest", "sha256:wrong"), ("expired", True))
        for field, value in mutations:
            with self.subTest(field=field, value=value):
                artifacts = copy.deepcopy(self.artifacts)
                artifacts[0][field] = value
                with self.assertRaises(ReviewTargetError):
                    self.plan(artifacts)
        for field, value in (("id", RUN_ID + 1), ("id", True), ("head_branch", "mc1.20.1"),
                             ("head_sha", "c" * 40)):
            with self.subTest(owner_field=field):
                artifacts = copy.deepcopy(self.artifacts)
                artifacts[0]["workflow_run"][field] = value
                with self.assertRaises(ReviewTargetError):
                    self.plan(artifacts)
        with self.assertRaisesRegex(ReviewTargetError, "real matrix source branch"):
            self.plan(source_branch="mc1.20.1")

    def test_proof_cannot_substitute_a_sibling_partial_graph_or_stale_policy(self) -> None:
        targets = self.plan()["include"]
        original = self.proof(targets[0])
        mutations = (("schema_version", True), ("bundle_key", targets[1]["bundle_key"]),
                     ("matrix_sha256", "0" * 64), ("source_sha", "b" * 40),
                     ("source_branch", "mc1.20.1"), ("review_mode", "reference-comparison"),
                     ("artifact_inventory", targets[1]["artifact_inventory"]))
        for field, value in mutations:
            with self.subTest(field=field):
                proof = copy.deepcopy(original)
                proof[field] = value
                with self.assertRaises(ReviewTargetError):
                    validate_target_proof(proof)
        proof = copy.deepcopy(original)
        proof["job_graph"]["observed_scenario_jobs"] = proof["job_graph"]["expected_scenario_jobs"][:2]
        with self.assertRaisesRegex(ReviewTargetError, "complete protected job graph"):
            validate_target_proof(proof)
        proof = copy.deepcopy(original)
        proof["job_graph"]["schema_version"] = True
        with self.assertRaises(ReviewTargetError):
            validate_target_proof(proof)

    def test_metadata_reader_rejects_duplicate_and_nonfinite_json(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "metadata.json"
            for payload in ('{"id":1,"id":2}', '{"id":NaN}', '{"id":1e9999}'):
                with self.subTest(payload=payload):
                    path.write_text(payload)
                    with self.assertRaises(ReviewTargetError):
                        read_json(path)


if __name__ == "__main__":
    unittest.main()
