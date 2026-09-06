from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import feature_coverage as coverage
import test_e2e_selection as git_fixtures
from e2e_job_graph import BUILD_JOB, GATE_JOB, POLICY_JOB, SCENARIO_SUFFIX
from matrix import gha_matrix, load_matrix, read_mod_version
from visual_review_targets import plan_targets


class FeatureCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.source = "a" * 40
        self.run_id = 55
        self.contract = coverage.default_contract()
        matrix = load_matrix(coverage.DEFAULT_MATRIX)
        self.rows = gha_matrix(matrix, "pr-anchors", read_mod_version(coverage.DEFAULT_MATRIX, matrix))["include"]
        self.artifacts = [{"id": index + 1, "name": f"packaged-e2e-{row['id']}", "size_in_bytes": 1024,
                           "digest": "sha256:" + "b" * 64, "expired": False,
                           "workflow_run": {"id": self.run_id, "head_branch": "master", "head_sha": self.source}}
                          for index, row in enumerate(self.rows)]
        self.targets = plan_targets(self.artifacts, source_run_id=self.run_id,
                                    source_branch="master", source_sha=self.source)["include"]

    def tearDown(self):
        self.temporary.cleanup()

    def files(self, target):
        folder = self.root / target["bundle_key"]
        folder.mkdir(exist_ok=True)
        paired = target["review_mode"] == "reference-comparison"
        names = {item["name"] for item in target["artifact_inventory"]}
        rows = [row for row in self.rows if f"packaged-e2e-{row['id']}" in names]
        manifest = []
        for row in rows:
            for name in self.contract.scenarios_for_profile("pr"):
                for role in self.contract.scenario(name).roles:
                    for step in role.steps:
                        capture = step.capture
                        if capture is None:
                            continue
                        item = {"path": "review-input/images/" + "a" * 64 + ".png",
                                "label": f"{row['artifact_node']}/{name}/{role.role}/{step.id}",
                                "capture_id": capture.capture_id, "kind": capture.capture_id,
                                "expectation": capture.expectation, "runtime_evidence": "The required assertion passed.",
                                "image_size": [1920, 1080], "candidate_semantic_sha256": "a" * 64,
                                "review_regions": [list(region) for region in self.contract.review_regions[capture.capture_id]]}
                        if paired:
                            item.update(reference_path="review-input/images/" + "b" * 64 + ".png",
                                        reference_label=f"fabric-1.20.1/{name}/{role.role}/{step.id}",
                                        reference_semantic_sha256="b" * 64,
                                        semantic_changed_fraction=0.01, perceptual_delta=0.01)
                        manifest.append(item)
        report = [{"label": item["label"], "visible": "The expected feature is visible.",
                   "semantic_valid": True, "matches_reference": True if paired else None,
                   "defect": False, "anomalies": []} for item in manifest]
        jobs = sorted(row["id"] + SCENARIO_SUFFIX for row in self.rows)
        proof = {"schema_version": 6, "bundle_key": target["bundle_key"], "matrix_sha256": target["matrix_sha256"],
                 "source_run_id": self.run_id, "source_branch": "master", "source_sha": self.source,
                 "master_source_sha": self.source, "implementation_sha": self.source, "matrix_kind": "pr-anchors",
                 "review_mode": target["review_mode"], "scenario_contract_sha256": self.contract.sha256,
                 "artifact_inventory": target["artifact_inventory"],
                 "compatibility_impact": {"schema_version": 1, "compatibility_required": True,
                                           "paths": [], "impact_paths": []},
                 "job_graph": {"schema_version": 1, "runtime_policy": "full", "expected_scenario_jobs": jobs,
                               "observed_scenario_jobs": jobs},
                 "visual_reference": {"branch": "master", "source_sha": self.source} if paired else None,
                 "frame_count": len(manifest), "image_count": 2 if paired else 1, "image_bytes": 1024}
        files = coverage.ReviewFiles(folder / "proof.json", folder / "manifest.json", folder / "report.json")
        self.write(files, proof, manifest, report)
        return files

    def write(self, files, proof, manifest, report):
        files.manifest.write_text(json.dumps(manifest) + "\n")
        proof["manifest_sha256"] = coverage.digest(files.manifest.read_bytes())
        files.proof.write_text(json.dumps(proof) + "\n")
        files.report.write_text(json.dumps(report) + "\n")

    def validate(self, files, target):
        return coverage.validate_clean_target(files, source_sha=self.source, source_run_id=self.run_id,
                                              bundle_key=target["bundle_key"])

    def test_every_real_target_requires_the_complete_authored_pr_capture_product(self):
        reviews = {}
        for target in self.targets:
            files = self.files(target)
            record = self.validate(files, target)
            captures = [item for item in self.contract.captures
                        if item.scenario in self.contract.scenarios_for_profile("pr")]
            self.assertEqual(len(captures) * 2, record["frame_count"])
            self.assertEqual(2, len(record["artifact_nodes"]))
            reviews[target["bundle_key"]] = files
        with patch.object(coverage, "policy_fingerprint", return_value="c" * 64), \
             patch.object(coverage, "module_fingerprints", return_value={"fixture": "d" * 64}):
            baseline = coverage.create_baseline(reviews, repository=ROOT, source_sha=self.source, source_run_id=self.run_id)
            self.assertEqual("full", baseline["coverage"])
            self.assertEqual("pr", baseline["profile"])
            self.assertEqual(len(self.targets), len(baseline["targets"]))
            reviews.pop(next(iter(reviews)))
            with self.assertRaises(coverage.CoverageError):
                coverage.create_baseline(reviews, repository=ROOT, source_sha=self.source, source_run_id=self.run_id)

    def test_partial_duplicate_foreign_and_changed_semantic_checkpoints_cannot_form_baselines(self):
        target = self.targets[0]
        files = self.files(target)
        original = [json.loads(path.read_bytes()) for path in (files.proof, files.manifest, files.report)]
        for mutation in ("missing", "duplicate", "foreign", "expectation", "region", "capture"):
            with self.subTest(mutation=mutation):
                proof, manifest, report = copy.deepcopy(original)
                if mutation == "missing": manifest.pop(); report.pop(); proof["frame_count"] -= 1
                elif mutation == "duplicate": manifest[-1] = manifest[0]; report[-1] = report[0]
                elif mutation == "foreign":
                    manifest[0]["label"] = "foreign-1.20.1/" + manifest[0]["label"].split("/", 1)[1]
                    report[0]["label"] = manifest[0]["label"]
                elif mutation == "expectation": manifest[0]["expectation"] = "Another expectation"
                elif mutation == "region": manifest[0]["review_regions"] = [[0, 0, 1, 1]]
                else: manifest[0]["capture_id"] = "full.client_a.foreign"
                self.write(files, proof, manifest, report)
                with self.assertRaises(ValueError): self.validate(files, target)

    def test_a_complete_report_with_any_invalid_or_defective_verdict_is_not_healthy(self):
        target = self.targets[1]
        files = self.files(target)
        original = [json.loads(path.read_bytes()) for path in (files.proof, files.manifest, files.report)]
        for field, value in (("semantic_valid", False), ("defect", True), ("matches_reference", False),
                             ("anomalies", ["visible defect"]), ("semantic_valid", 1), ("label", "foreign")):
            with self.subTest(field=field, value=value):
                proof, manifest, report = copy.deepcopy(original)
                report[0][field] = value
                self.write(files, proof, manifest, report)
                with self.assertRaises(ValueError): self.validate(files, target)

    def test_source_profile_selection_and_curation_proof_substitutions_are_rejected(self):
        target = self.targets[0]
        files = self.files(target)
        original = [json.loads(path.read_bytes()) for path in (files.proof, files.manifest, files.report)]
        for field, value in (("matrix_kind", "native-anchors"), ("selection_sha256", "d" * 64),
                             ("source_sha", "b" * 40), ("source_run_id", True), ("bundle_key", "mc1.21.8"),
                             ("matrix_sha256", "d" * 64), ("scenario_contract_sha256", "d" * 64),
                             ("frame_count", True), ("image_count", 42), ("image_bytes", True)):
            with self.subTest(field=field):
                proof, manifest, report = copy.deepcopy(original)
                proof[field] = value
                self.write(files, proof, manifest, report)
                with self.assertRaises(ValueError): self.validate(files, target)

    def test_review_reference_must_be_the_matrix_anchor_lane(self):
        target = self.targets[1]
        files = self.files(target)
        proof, manifest, report = [json.loads(path.read_bytes()) for path in (files.proof, files.manifest, files.report)]
        manifest[0]["reference_label"] = "fabric-1.21.8/" + manifest[0]["label"].split("/", 1)[1]
        self.write(files, proof, manifest, report)
        with self.assertRaises(coverage.CoverageError): self.validate(files, target)

    def test_only_an_exact_successful_master_dispatch_with_the_whole_job_graph_can_seed_coverage(self):
        run = {"id": self.run_id, "head_branch": "master", "head_sha": self.source,
               "head_repository": {"full_name": "The-Plum-Team/Quick-Skin-Mod"},
               "path": ".github/workflows/on-demand-e2e.yml", "event": "workflow_dispatch",
               "status": "completed", "conclusion": "success"}
        jobs = [{"jobs": [{"name": name, "status": "completed", "conclusion": "success"}
                           for name in (POLICY_JOB, BUILD_JOB, GATE_JOB,
                                        *(row["id"] + SCENARIO_SUFFIX for row in self.rows))]}]
        def validate(candidate, candidate_jobs=jobs):
            return coverage.validate_source_run(candidate, candidate_jobs,
                github_repository="The-Plum-Team/Quick-Skin-Mod", source_sha=self.source,
                source_run_id=self.run_id)
        self.assertEqual("full", validate(run)["runtime_policy"])
        for field, value in (("id", True), ("head_branch", "feature/example"), ("head_sha", "b" * 40),
                             ("event", "pull_request"), ("event", "schedule"), ("conclusion", "failure"),
                             ("status", "in_progress"), ("head_repository", {"full_name": "foreign/repository"})):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                validate({**run, field: value})
        partial = copy.deepcopy(jobs)
        partial[0]["jobs"].pop()
        with self.assertRaises(ValueError): validate(run, partial)

    def test_complete_same_run_review_can_seed_coverage_but_foreign_reference_cannot(self):
        target = self.targets[1]
        files = self.files(target)
        proof, manifest, report = [json.loads(path.read_bytes()) for path in (files.proof, files.manifest, files.report)]
        reference = next(item for item, row in zip(self.artifacts, self.rows, strict=True)
                         if row["artifact_node"] == "fabric-1.20.1")
        proof.update(schema_version=8, visual_reference={"evidence_kind": "packaged-full",
            "artifact": reference, "artifact_node": "fabric-1.20.1", "source_sha": self.source,
            "source_run_id": self.run_id})
        self.write(files, proof, manifest, report)
        self.assertEqual(180, self.validate(files, target)["frame_count"])
        proof["visual_reference"]["source_run_id"] += 1
        self.write(files, proof, manifest, report)
        with self.assertRaises(ValueError): self.validate(files, target)

    def test_report_metadata_binds_exact_target_and_protected_successful_review_job(self):
        target = self.targets[0]["bundle_key"]
        artifact = {"id": 42, "name": f"visual-review-{self.run_id}--{target}", "size_in_bytes": 1024,
                    "digest": "sha256:" + "b" * 64, "expired": False, "created_at": "2026-09-06T00:00:00Z",
                    "workflow_run": {"id": 66, "head_branch": "master", "head_sha": self.source}}
        owner = {"id": 66, "head_branch": "master", "head_sha": self.source,
                 "head_repository": {"full_name": "The-Plum-Team/Quick-Skin-Mod"},
                 "path": coverage.DRAIN_WORKFLOW, "event": "repository_dispatch",
                 "status": "completed", "conclusion": "success"}
        jobs = [{"jobs": [{"name": "Review one queued capsule", "status": "completed", "conclusion": "success"}]}]
        def validate(candidate=artifact, candidate_owner=owner, candidate_jobs=jobs):
            return coverage.validate_review_owner(candidate, candidate_owner, candidate_jobs,
                github_repository="The-Plum-Team/Quick-Skin-Mod", source_sha=self.source,
                source_run_id=self.run_id, bundle_key=target)
        self.assertEqual(42, validate()["id"])
        self.assertEqual(42, validate(candidate_owner={**owner, "conclusion": "failure"})["id"])
        for field, value in (("name", f"visual-review-{self.run_id}--mc1.21.8"), ("expired", True),
                             ("size_in_bytes", coverage.MAX_REPORT_ARCHIVE_BYTES + 1), ("id", True),
                             ("workflow_run", {"id": 66, "head_branch": "master", "head_sha": "c" * 40})):
            with self.subTest(field=field), self.assertRaises(ValueError): validate({**artifact, field: value})
        for field, value in (("id", True), ("event", "pull_request"), ("head_sha", "c" * 40),
                             ("path", ".github/workflows/on-demand-e2e.yml"), ("status", "in_progress"),
                             ("head_repository", ["foreign/repository"])):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(candidate_owner={**owner, field: value})
        for conclusion in ("skipped", "failure"):
            invalid = copy.deepcopy(jobs)
            invalid[0]["jobs"][0]["conclusion"] = conclusion
            with self.assertRaises(ValueError): validate(candidate_jobs=invalid)

    def git_objects(self):
        fixture = git_fixtures.E2ESelectionAdmissionTest()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        files = dict(fixture.files)
        for module in coverage.load_graph().modules:
            files[module.path + "/build.gradle.kts"] = ("100644", b"// fixture module\n")
        files["scripts/ci/feature_coverage.py"] = ("100644", (ROOT / "scripts/ci/feature_coverage.py").read_bytes())
        return fixture, files

    def test_fingerprints_preserve_independent_features_and_follow_runtime_api_providers(self):
        fixture, files = self.git_objects()
        base = fixture.commit(files)
        before = coverage.module_fingerprints(fixture.repository, base)
        changed = dict(files)
        changed[fixture.editor] = ("100644", b"class Editor { int zoom; }\n")
        after = coverage.module_fingerprints(fixture.repository, fixture.commit(changed, base))
        self.assertNotEqual(before["cape-editor"], after["cape-editor"])
        self.assertNotEqual(before["skin-menu"], after["skin-menu"])
        self.assertEqual(before["hud-preview"], after["hud-preview"])
        self.assertEqual(before["settings-ui"], after["settings-ui"])
        changed = dict(files)
        changed["modules/minecraft-adapter/src/main/java/Decoder.java"] = ("100644", b"class Decoder {}\n")
        provider = coverage.module_fingerprints(fixture.repository, fixture.commit(changed, base))
        self.assertNotEqual(before["cape-import"], provider["cape-import"])
        changed["modules/minecraft-adapter/src/main/java/Decoder.java"] = ("120000", b"/tmp/foreign")
        with self.assertRaises(coverage.CoverageError):
            coverage.module_fingerprints(fixture.repository, fixture.commit(changed, base))

    def test_policy_identity_binds_executing_validator_and_rejects_missing_module_owners(self):
        fixture, files = self.git_objects()
        base = fixture.commit(files)
        paths = (*coverage.admission.POLICY_PATHS, "scripts/ci/feature_coverage.py")
        with patch.object(coverage, "POLICY_PATHS", paths):
            before = coverage.policy_fingerprint(fixture.repository, base, verify_executing=True)
            changed = dict(files)
            mode, raw = changed["scripts/ci/feature_coverage.py"]
            changed["scripts/ci/feature_coverage.py"] = (mode, raw + b"\n")
            head = fixture.commit(changed, base)
            self.assertNotEqual(before, coverage.policy_fingerprint(fixture.repository, head))
            with self.assertRaises(coverage.CoverageError):
                coverage.policy_fingerprint(fixture.repository, head, verify_executing=True)
        files.pop("modules/hud-preview/build.gradle.kts")
        with self.assertRaises(coverage.CoverageError):
            coverage.module_fingerprints(fixture.repository, fixture.commit(files, base))

    def test_json_is_bounded_finite_unique_and_regular(self):
        path = self.root / "evidence.json"
        for raw in (b'{"value":1,"value":2}', b'{"value":NaN}', b'{"value":1e999}', b'[]' * 30):
            with self.subTest(raw=raw):
                path.write_bytes(raw)
                with self.assertRaises(coverage.CoverageError): coverage._read(path)
        path.write_text("[]")
        link = self.root / "linked.json"
        link.symlink_to(path)
        with self.assertRaises(coverage.CoverageError): coverage._read(link)
        with patch.object(coverage, "MAX_JSON_BYTES", 1), self.assertRaises(coverage.CoverageError):
            coverage._read(path)
