"""``feature_pages.compose_selected`` admits the runtime, the selection certificate and the exact
retained baseline before any baseline byte is downloaded or composed."""

from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import types
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))
sys.path.insert(0, str(ROOT / "scripts/ci/tests"))

import mod_base_path  # noqa: E402

mod_base_path.kit_root()

import feature_pages as pages  # noqa: E402
import feature_evidence  # noqa: E402
import feature_review  # noqa: E402

KEY = "mc1.20.1"
BASE, SOURCE = "a" * 40, "b" * 40


class PublicApi(pages.publisher.Api):
    def __init__(self):
        super().__init__("The-Plum-Team/Quick-Skin-Mod")
        self.current = SOURCE
        self.owner = {"id": 8000, "run_attempt": 1, "head_branch": "master", "head_sha": BASE,
                      "head_repository": {"full_name": self.repository}, "path": ".github/workflows/pages.yml",
                      "event": "workflow_dispatch", "status": "completed", "conclusion": "success"}
        build, deploy, refresh = pages.publisher.PUBLIC_BASELINE_JOBS
        self.jobs_record = [{"jobs": [{"name": name, "status": "completed", "conclusion": "success"}
                                      for name in (build, deploy, refresh.format(key=KEY))]}]
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", b"{}\n")
        self.raw = buffer.getvalue()
        self.record = {"id": 20000, "name": pages.publisher.public_baseline_name(KEY, BASE, 42), "expired": False,
                       "size_in_bytes": len(self.raw), "digest": "sha256:" + pages.coverage.digest(self.raw),
                       "workflow_run": {"id": 8000, "head_branch": "master", "head_sha": BASE}}
        self.downloads = []

    def current_sha(self):
        return self.current

    def run(self, identifier):
        if identifier != 8000:
            raise AssertionError("unexpected run")
        return self.owner

    def artifact(self, identifier):
        if identifier != 20000:
            raise AssertionError("unexpected artifact")
        return self.record

    def jobs(self, owner):
        if owner["id"] != 8000:
            raise AssertionError("unexpected job owner")
        return self.jobs_record

    def archive(self, metadata, *, maximum):
        self.downloads.append(metadata["id"])
        if len(self.raw) > maximum:
            raise ValueError("fixture exceeds archive bound")
        return pages.publisher.check_archive(self.raw, metadata)


class ComposeSelectedAdmissionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.api = PublicApi()
        metadata = pages.publisher.validate_public_owner(self.api.record, self.api.owner, self.api.jobs_record,
            github_repository=self.api.repository, source_sha=BASE, source_run_id=42, bundle_key=KEY)
        self.proof = {"baseline_source_run_id": 42, "public_baseline_artifacts": {KEY: metadata}}
        self.selection = types.SimpleNamespace(base_commit=BASE)
        self.manifest = {"key": KEY, "repository": self.api.repository,
                         "subject": {"branch": "master", "commit": SOURCE, "tree": "c" * 40}}
        self.extensions = {pages.FEATURE_SELECTION: {"admission": {}, "coverage": {}}}
        self.tested = 42

    def compose(self, name="composed", *, verify_error=None):
        scratch = self.root / f"{name}-scratch"
        scratch.mkdir()
        runtime = types.SimpleNamespace(reference=None)
        with patch.object(pages, "runtime_of", return_value=runtime) as runtime_of, \
                patch.object(feature_evidence, "read_selection") as read_selection, \
                patch.object(feature_review, "verify_selection", return_value=(self.selection, self.proof),
                             side_effect=verify_error) as verify, \
                patch.object(feature_evidence, "compose") as compose:
            result = pages.compose_selected(
                self.api, repository=ROOT, key=KEY, manifest=self.manifest, selected_root=self.root / "selected",
                output_root=self.root / name, extensions=self.extensions, complete_expectation={"key": KEY},
                composed_extensions={}, scratch=scratch)
        runtime_of.assert_called_once_with(self.api, self.manifest, self.extensions)
        read_selection.assert_called_once_with(self.extensions[pages.FEATURE_SELECTION])
        self.assertIs(runtime, verify.call_args.args[2])
        self.assertEqual({"base_commit": BASE, "baseline_run_id": 42, "baseline_tested_run_id": self.tested,
                          "key": KEY,
                          "baseline_artifact": {name: self.api.record[name] for name in ("id", "name", "digest")}},
                         {name: compose.call_args.kwargs[name]
                          for name in ("base_commit", "baseline_run_id", "baseline_tested_run_id", "key",
                                       "baseline_artifact")})
        return result

    def test_the_certified_baseline_is_authenticated_downloaded_once_and_composed(self):
        self.assertEqual({"id": 20000, "name": self.api.record["name"], "digest": self.api.record["digest"]},
                         self.compose())
        self.assertEqual([20000], self.api.downloads)

    def test_failed_selection_foreign_owner_or_expired_baseline_never_downloads(self):
        with self.assertRaisesRegex(ValueError, "unproven baseline"):
            self.compose("unproven", verify_error=ValueError("unproven baseline"))
        self.api.owner["event"] = "repository_dispatch"
        with self.assertRaisesRegex(ValueError, "successful protected Pages owner"):
            self.compose("foreign-owner")
        self.api.owner["event"] = "workflow_dispatch"
        self.api.record["expired"] = True
        with self.assertRaisesRegex(ValueError, "successful protected Pages owner"):
            self.compose("expired")
        self.assertEqual([], self.api.downloads)

    def test_certificate_substitution_archive_digest_and_source_moves_cannot_compose(self):
        self.proof["public_baseline_artifacts"] = {}
        with self.assertRaisesRegex(ValueError, "does not contain this target"):
            self.compose("missing")
        self.proof["public_baseline_artifacts"] = {KEY: {**self.api.record, "owner_run_id": 8000,
                                                          "digest": "sha256:" + "0" * 64}}
        self.proof["public_baseline_artifacts"][KEY].pop("expired")
        self.proof["public_baseline_artifacts"][KEY].pop("workflow_run")
        with self.assertRaisesRegex(ValueError, "differs from the complete coverage certificate"):
            self.compose("substituted")
        self.assertEqual([], self.api.downloads)
        self.setUp()
        self.api.raw = self.api.raw[:-1] + bytes([self.api.raw[-1] ^ 1])
        with self.assertRaisesRegex(ValueError, "differs from its authenticated metadata"):
            self.compose("digest")
        self.api.current = "f" * 40
        with self.assertRaisesRegex(ValueError, "advanced before"):
            self.compose("advanced")
        self.manifest["key"] = "mc26.3"
        self.api.current = SOURCE
        with self.assertRaisesRegex(ValueError, "another key"):
            self.compose("foreign-key")

    def test_a_reused_generation_composes_with_the_tested_run_its_retained_name_carries(self):
        # A generation that reused pull-request run 4400: mod-base names the archive by 4400.
        self.api.record["name"] = pages.publisher.public_baseline_name(KEY, BASE, 4400)
        self.proof["public_baseline_artifacts"][KEY]["name"] = self.api.record["name"]
        self.tested = 4400
        self.compose()
        self.assertEqual([20000], self.api.downloads)

    def test_a_certificate_naming_another_targets_or_commits_baseline_never_downloads(self):
        for name in (pages.publisher.public_baseline_name("mc26.3", BASE, 42),
                     pages.publisher.public_baseline_name(KEY, SOURCE, 42), "pages-full-baseline-mc1.20.1"):
            with self.subTest(name=name):
                self.proof["public_baseline_artifacts"][KEY] = {**self.proof["public_baseline_artifacts"][KEY],
                                                                "name": name}
                with self.assertRaisesRegex(ValueError, "another target's or commit's public baseline"):
                    self.compose(f"foreign-{len(name)}-{name[-2:]}")
        self.assertEqual([], self.api.downloads)


if __name__ == "__main__":
    unittest.main()
