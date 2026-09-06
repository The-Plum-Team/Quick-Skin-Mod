from __future__ import annotations

import copy
import io
import json
import os
import shlex
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/ci"))

import feature_coverage_github as publisher
import test_feature_coverage as fixtures
from e2e_job_graph import BUILD_JOB, GATE_JOB, POLICY_JOB, SCENARIO_SUFFIX
from test_workflow_security import job_block, step_script


class FixtureApi(publisher.Api):
    def __init__(self, fixture):
        super().__init__("The-Plum-Team/Quick-Skin-Mod")
        self.fixture = fixture
        self.live = [fixture.source, fixture.source]
        self.downloaded = []
        self.queries = []
        source = {"id": fixture.run_id, "head_branch": "master", "head_sha": fixture.source,
                  "head_repository": {"full_name": self.repository}, "run_attempt": 1,
                  "path": ".github/workflows/on-demand-e2e.yml", "event": "workflow_dispatch",
                  "status": "completed", "conclusion": "success"}
        self.runs = {fixture.run_id: source}
        names = [POLICY_JOB, BUILD_JOB, GATE_JOB, *(row["id"] + SCENARIO_SUFFIX for row in fixture.rows)]
        self.job_lists = {fixture.run_id: [{"jobs": [self.job(name, index + 1, source) for index, name in enumerate(names)]}]}
        self.records = {}
        self.archives = {}
        for index, target in enumerate(fixture.targets):
            files = fixture.files(target)
            owner_id = 1000 + index
            owner = {**source, "id": owner_id, "path": publisher.coverage.DRAIN_WORKFLOW,
                     "event": "repository_dispatch"}
            self.runs[owner_id] = owner
            self.job_lists[owner_id] = [{"jobs": [self.job("Review one queued capsule", 2000 + index, owner)]}]
            identifier = 10000 + index
            name = f"visual-review-{fixture.run_id}--{target['bundle_key']}"
            record = {"id": identifier, "name": name, "created_at": "2026-09-06T00:00:00Z", "expired": False,
                      "workflow_run": {"id": owner_id, "head_sha": fixture.source, "head_branch": "master"}}
            contents = {"curation-proof.json": files.proof.read_bytes(),
                        "review-input/visual-review-manifest.json": files.manifest.read_bytes(),
                        "visual-review-report.json": files.report.read_bytes(),
                        "visual-review-completion.json": b'{"review_complete":true}'}
            self.records[name] = [record]
            self.replace_archive(record, contents)

    def job(self, name, identifier, owner):
        return {"id": identifier, "run_id": owner["id"], "run_attempt": 1, "head_sha": owner["head_sha"],
                "name": name, "status": "completed", "conclusion": "success"}

    def replace_archive(self, record, contents):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, raw in contents.items(): archive.writestr(name, raw)
        self.archives[record["id"]] = buffer.getvalue()
        record.update(size_in_bytes=len(buffer.getvalue()), digest="sha256:" + publisher.coverage.digest(buffer.getvalue()))

    def current_sha(self):
        return self.live.pop(0) if len(self.live) > 1 else self.live[0]

    def run(self, identifier):
        return self.runs[identifier]

    def jobs(self, run):
        return self.job_lists[run["id"]]

    def artifacts(self, *, run_id=None, name=None):
        if name is not None:
            self.queries.append(name)
            return self.records.get(name, [])
        return [item for records in self.records.values() for item in records if item["workflow_run"]["id"] == run_id]

    def download(self, metadata, destination, **kwargs):
        self.downloaded.append(metadata["id"])
        with patch.object(publisher, "_get", return_value=self.archives[metadata["id"]]):
            super().download(metadata, destination, **kwargs)


class FeatureCoverageGitHubTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.FeatureCoverageTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.api = FixtureApi(self.fixture)

    def prepare(self):
        directory = self.fixture.root / "collector"
        directory.mkdir()
        with patch.object(publisher.coverage, "policy_fingerprint", return_value="c" * 64), \
             patch.object(publisher.coverage, "module_fingerprints", return_value={"fixture": "d" * 64}):
            return publisher.prepare(self.api, repository=ROOT, source_sha=self.fixture.source,
                                     source_run_id=self.fixture.run_id, issuer_run_id=9000, directory=directory)

    def test_complete_source_and_clean_reviews_produce_a_bound_baseline_without_images(self):
        result = self.prepare()
        self.assertEqual("full", result["coverage"])
        self.assertEqual({"workflow": publisher.WORKFLOW, "run_id": 9000, "sha": self.fixture.source}, result["issuer"])
        self.assertEqual(len(self.fixture.targets), len(result["review_artifacts"]))
        self.assertEqual(len(self.fixture.targets), len(self.api.downloaded))
        self.assertEqual(1, result["source_run_attempt"])
        self.assertEqual([], list((self.fixture.root / "collector").rglob("*.png")))
        self.assertLess(len(publisher.coverage.admission.canonical(result)), 64 * 1024)

    def test_missing_target_review_defers_before_downloading_any_report(self):
        self.api.records.pop(next(reversed(self.api.records)))
        self.assertIsNone(self.prepare())
        self.assertEqual([], self.api.downloaded)

    def test_foreign_review_owner_is_rejected_before_any_report_download(self):
        self.api.runs[1000]["path"] = ".github/workflows/build-gate.yml"
        with self.assertRaises(ValueError): self.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_duplicate_reports_cannot_override_an_earlier_verdict(self):
        records = next(iter(self.api.records.values()))
        records.append({**records[0], "id": 20000})
        with self.assertRaises(ValueError): self.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_partial_runtime_job_graph_cannot_seed_a_baseline(self):
        self.api.job_lists[self.fixture.run_id][0]["jobs"].pop()
        with self.assertRaises(ValueError): self.prepare()
        self.assertEqual([], self.api.downloaded)

    def test_source_advance_discards_even_an_otherwise_complete_result(self):
        self.api.live[-1] = "b" * 40
        self.assertIsNone(self.prepare())
        self.assertEqual(len(self.fixture.targets), len(self.api.downloaded))

    def test_archive_digest_and_exact_json_only_inventory_are_enforced(self):
        record = next(iter(self.api.records.values()))[0]
        self.api.archives[record["id"]] += b"tampered"
        with self.assertRaises(ValueError): self.prepare()

    def test_validly_hashed_archive_cannot_add_a_raw_image_or_extra_file(self):
        record = next(iter(self.api.records.values()))[0]
        with zipfile.ZipFile(io.BytesIO(self.api.archives[record["id"]])) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        contents["images/untrusted.png"] = b"candidate-controlled payload"
        self.api.replace_archive(record, contents)
        with self.assertRaises(ValueError): self.prepare()

    def test_trigger_owner_and_explicit_wake_settling_are_authenticated(self):
        self.assertEqual(self.fixture.run_id, publisher.source_from_trigger(self.api, 1000, self.fixture.source))
        self.assertIsNone(publisher.source_from_trigger(self.api, 1000, "b" * 40))
        complete = copy.deepcopy(self.api.runs[1000])
        running = {**complete, "status": "in_progress", "conclusion": None}
        with patch.object(self.api, "run", side_effect=[running, complete]), patch.object(publisher.time, "sleep") as sleep:
            self.assertEqual(self.fixture.run_id, publisher.source_from_trigger(self.api, 1000, self.fixture.source))
            sleep.assert_called_once_with(2)
        self.api.runs[1000]["path"] = ".github/workflows/build-gate.yml"
        with self.assertRaises(ValueError): publisher.source_from_trigger(self.api, 1000, self.fixture.source)


class FeatureCoverageApiTest(unittest.TestCase):
    def setUp(self):
        self.api = publisher.Api("The-Plum-Team/Quick-Skin-Mod")

    def test_api_json_rejects_duplicate_keys_and_nonfinite_data(self):
        for raw in (b'{"id":1,"id":2}', b'{"id":NaN}', b'{"id":1e999}'):
            with self.subTest(raw=raw), patch.object(publisher, "_get", return_value=raw), self.assertRaises(ValueError):
                self.api.json("actions/runs/55")

    def test_inventory_bounds_and_exact_attempt_job_identity(self):
        run = {"id": 55, "run_attempt": 1, "head_sha": "a" * 40}
        original = {"total_count": 1, "jobs": [{"id": 77, "run_id": 55, "run_attempt": 1, "head_sha": "a" * 40}]}
        with patch.object(self.api, "json", return_value=original):
            self.assertEqual([original], self.api.jobs(run))
        for field, value in (("id", True), ("run_id", 56), ("run_attempt", 2), ("head_sha", "b" * 40)):
            invalid = copy.deepcopy(original)
            invalid["jobs"][0][field] = value
            with self.subTest(field=field), patch.object(self.api, "json", return_value=invalid), self.assertRaises(ValueError):
                self.api.jobs(run)
        for record in ({"total_count": 101, "artifacts": []}, {"total_count": True, "artifacts": [{}]},
                       {"total_count": 1, "artifacts": []}, {"total_count": 1, "artifacts": [{"name": "foreign"}]}):
            with self.subTest(record=record), patch.object(self.api, "json", return_value=record), self.assertRaises(ValueError):
                self.api.artifacts(name="visual-review-55--mc1.20.1")

    def test_cli_transport_is_read_only_and_bounds_stdout_before_parsing(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            program = folder / "fixture.py"
            program.write_text('import sys\nassert sys.argv[1:]==["api","--method","GET","repos/example/project/test"]\nsys.stdout.buffer.write(b"x"*1024)\n')
            gh = folder / "gh"
            gh.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(program)) + ' "$@"\n')
            gh.chmod(0o755)
            with patch.dict(os.environ, {"PATH": str(folder) + os.pathsep + os.defpath}, clear=True):
                with self.assertRaises(ValueError): publisher._get("repos/example/project/test", maximum=32)
                self.assertEqual(b"x" * 1024, publisher._get("repos/example/project/test", maximum=1024))

    def test_actual_publisher_shell_requires_protected_checkout_and_same_repository_wake(self):
        script = step_script("feature-coverage.yml", "certify", "Authenticate the existing source and all normalized reviews")
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            program = folder / "fixture.py"
            program.write_text('''import json,os,sys
from pathlib import Path
args=sys.argv[1:]
if args==["scripts/release/release_sources.py","--kind","mode"]: print("shared")
elif args and args[0]=="scripts/ci/feature_coverage_github.py":
 Path(os.environ["FIXTURE_CALLED"]).write_text(json.dumps(args))
else: raise SystemExit("Unexpected protected command")
''')
            python = folder / "python3"
            python.write_text("#!/bin/sh\nexec " + shlex.quote(sys.executable) + " " + shlex.quote(str(program)) + ' "$@"\n')
            python.chmod(0o755)
            for case in ("dispatch", "manual", "stale", "foreign", "wrong-ref"):
                with self.subTest(case=case):
                    called = folder / "called.json"
                    called.unlink(missing_ok=True)
                    env = {"PATH": str(folder) + os.pathsep + os.defpath,
                           "GITHUB_SHA": "b" * 40 if case == "stale" else sha,
                           "GITHUB_REF": "refs/heads/feature" if case == "wrong-ref" else "refs/heads/master",
                           "GITHUB_EVENT_NAME": "workflow_dispatch" if case == "manual" else "repository_dispatch",
                           "GITHUB_REPOSITORY": self.api.repository, "GITHUB_RUN_ID": "99",
                           "REQUEST_REPOSITORY": "foreign/repository" if case == "foreign" else self.api.repository,
                           "REQUESTED_SOURCE_RUN": "55", "TRIGGER_RUN_ID": "66", "RUNNER_TEMP": str(folder),
                           "GITHUB_OUTPUT": str(folder / "output"), "FIXTURE_CALLED": str(called)}
                    result = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c", script],
                                            cwd=ROOT, env=env, text=True, capture_output=True, timeout=10)
                    self.assertEqual(case in {"dispatch", "manual"}, result.returncode == 0, result.stderr[:1000])
                    self.assertEqual(case in {"dispatch", "manual"}, called.exists())
                    if called.exists():
                        args = json.loads(called.read_bytes())
                        self.assertEqual(["--source-run-id", "55"] if case == "manual" else ["--trigger-run-id", "66"], args[1:3])
                        self.assertEqual(sha, args[args.index("--source-sha") + 1])
        block = job_block("feature-coverage.yml", "certify")
        self.assertIn("actions: read", block)
        self.assertIn("contents: read", block)
        self.assertNotIn("contents: write", block)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", block)
        self.assertIn("steps.current.outputs.current == 'true'", block)
