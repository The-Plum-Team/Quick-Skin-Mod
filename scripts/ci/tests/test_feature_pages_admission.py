from __future__ import annotations

import copy
import io
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/release/tests"))

import feature_pages as pages
import test_feature_pages as image_fixtures


class PublicApi(pages.publisher.Api):
    def __init__(self, fixture):
        super().__init__("The-Plum-Team/Quick-Skin-Mod")
        self.current = fixture.source_sha
        self.source = {"id": 55, "created_at": "2026-09-06T02:00:00Z", "head_branch": "master",
            "head_sha": fixture.source_sha, "head_repository": {"full_name": self.repository},
            "path": ".github/workflows/on-demand-e2e.yml", "event": "workflow_dispatch",
            "status": "completed", "conclusion": "success"}
        names = [pages.ci_reuse.POLICY_JOB, pages.ci_reuse.BUILD_JOB, pages.ci_reuse.GATE_JOB,
                 *pages.ci_reuse.expected_scenario_jobs_for(pages.coverage.DEFAULT_MATRIX, "pr-anchors")]
        self.runtime_jobs = [{"jobs": [{"name": name, "status": "completed", "conclusion": "success"}
                                      for name in names]}]
        self.owner = {"id": 8000, "head_branch": "master", "head_sha": fixture.baseline_sha,
            "head_repository": {"full_name": self.repository}, "path": ".github/workflows/pages.yml",
            "event": "workflow_dispatch", "status": "completed", "conclusion": "success"}
        self.record = {"id": 20000, "name": pages.publisher.public_baseline_name(fixture.key, fixture.baseline_sha, 42),
            "expired": False, "workflow_run": {"id": 8000, "head_branch": "master", "head_sha": fixture.baseline_sha}}
        self.jobs_record = [{"jobs": [{"name": name, "status": "completed", "conclusion": "success"}
            for name in ("Build atomic static site", "Deploy GitHub Pages", f"Refresh evidence cache for {fixture.key}")]}]
        buffer = io.BytesIO()
        root = fixture.root / "base-compact"
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in root.rglob("*"):
                if path.is_file(): archive.writestr(path.relative_to(root).as_posix(), path.read_bytes())
        self.raw = buffer.getvalue()
        self.record.update(size_in_bytes=len(self.raw), digest="sha256:" + pages.coverage.digest(self.raw))
        self.downloads = []

    def run(self, identifier):
        if identifier == 55: return self.source
        if identifier == 8000: return self.owner
        raise AssertionError("unexpected run")

    def artifact(self, identifier):
        if identifier != 20000: raise AssertionError("unexpected artifact")
        return self.record

    def jobs(self, owner):
        if owner["id"] == 55: return self.runtime_jobs
        if owner["id"] != 8000: raise AssertionError("unexpected job owner")
        return self.jobs_record

    def artifacts(self, *, run_id=None, name=None):
        if run_id != 55 or name is not None: raise AssertionError("unexpected artifact inventory")
        return []  # Admission and the retained baseline are exercised below; no reused-source wrapper.

    def current_sha(self):
        return self.current

    def get(self, endpoint, *, maximum):
        if endpoint != self.prefix + "actions/artifacts/20000/zip": raise AssertionError("unexpected download")
        self.downloads.append(endpoint)
        if len(self.raw) > maximum: raise ValueError("fixture exceeds archive bound")
        return self.raw


class FeaturePagesAdmissionTest(unittest.TestCase):
    def setUp(self):
        self.fixture = image_fixtures.FeaturePagesTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.api = PublicApi(self.fixture)
        metadata = pages.publisher.validate_public_owner(self.api.record, self.api.owner, self.api.jobs_record,
            github_repository=self.api.repository, source_sha=self.fixture.baseline_sha, source_run_id=42,
            bundle_key=self.fixture.key)
        self.proof = {"baseline_source_run_id": 42, "public_baseline_artifacts": {self.fixture.key: metadata}}

    def collect(self, source="selected", output="published", owner=55, verify_error=None):
        scratch = self.fixture.root / (output + "-scratch")
        scratch.mkdir()
        with patch.object(pages.consumer, "verify", return_value=(self.fixture.selection, self.proof),
                          side_effect=verify_error) as admission, patch.object(pages.publisher, "_get", side_effect=self.api.get):
            result = pages.collect(self.api, repository=ROOT, evidence_root=self.fixture.root / source,
                output=self.fixture.root / output, bundle_key=self.fixture.key, source_sha=self.fixture.source_sha,
                artifact_run_id=owner, scratch=scratch)
            self.assertEqual(self.fixture.source_sha, admission.call_args.kwargs["head"])
            self.assertEqual(self.fixture.source_sha, admission.call_args.kwargs["policy"])
            self.assertEqual(55, admission.call_args.kwargs["run_id"])
        return result

    def test_current_raw_and_composed_cache_reauthenticate_the_exact_baseline_before_reuse(self):
        output = self.collect()
        value = pages.evidence.validate_bundle(output.parent, output.name, expected_kind="compact")
        self.assertEqual(180, len(value["frames"]))
        self.assertEqual(1, len(self.api.downloads))
        cached = self.collect("published", "revalidated", owner=None)
        self.assertEqual((output / "manifest.json").read_bytes(), (cached / "manifest.json").read_bytes())
        self.assertEqual(2, len(self.api.downloads))

    def test_failed_execution_baseline_or_handoff_ownership_never_decodes_public_images(self):
        with self.assertRaises(ValueError): self.collect(verify_error=ValueError("unproven baseline"))
        self.assertEqual([], self.api.downloads)
        with self.assertRaises(ValueError): self.collect(output="foreign-owner", owner=56)
        self.assertEqual([], self.api.downloads)
        self.api.record["expired"] = True
        with self.assertRaises(ValueError): self.collect(output="expired-baseline")
        self.assertEqual([], self.api.downloads)

    def test_digest_metadata_capture_and_source_substitutions_cannot_publish(self):
        self.api.current = "c" * 40
        with self.assertRaisesRegex(ValueError, "advanced"): self.collect(output="source-advanced")
        self.assertFalse((self.fixture.root / "source-advanced" / self.fixture.key).exists())
        self.api.current = self.fixture.source_sha
        self.api.downloads.clear()
        original = copy.deepcopy(self.api.record)
        self.api.record["size_in_bytes"] += 1
        with self.assertRaises(ValueError): self.collect(output="metadata")
        self.assertEqual([], self.api.downloads)
        self.api.record = original
        raw = self.api.raw
        self.api.raw = raw[:-1] + bytes([raw[-1] ^ 1])
        with self.assertRaises(ValueError): self.collect(output="digest")
        self.api.raw = raw
        path = self.fixture.root / "selected" / self.fixture.key / "manifest.json"
        manifest = json.loads(path.read_bytes())
        manifest["frames"].pop()
        path.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError): self.collect(output="partial")
        self.assertFalse((self.fixture.root / "partial" / self.fixture.key).exists())


if __name__ == "__main__":
    unittest.main()
