from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import target_status_render as renderer  # noqa: E402


REPOSITORY = "The-Plum-Team/Quick-Skin-Mod"
COVERED = "a" * 40
ORIGINAL = "b" * 40
TESTED = "c" * 40


def run(identifier: int, sha: str = COVERED, attempt: int = 1) -> dict:
    return {"id": identifier, "attempt": attempt, "sha": sha,
            "status": "completed", "conclusion": "success",
            "url": f"https://github.com/{REPOSITORY}/actions/runs/{identifier}/attempts/{attempt}"}


def snapshot() -> dict:
    gate = {"state": "success", "reason": "Required target jobs and shared policy passed.",
            "generation": run(10), "execution": run(10), "tested_sha": COVERED, "reused": False,
            "expected_jobs": ["Compile Minecraft 1.20.1", "Validate repository policy"],
            "jobs": [{"id": identifier, "name": name, "status": "completed", "conclusion": "success",
                      "url": f"https://github.com/{REPOSITORY}/actions/runs/10/job/{identifier}"}
                     for identifier, name in [(100, "Compile Minecraft 1.20.1"),
                                               (101, "Validate repository policy")]]}
    return {"schema_version": 1, "repository": REPOSITORY, "coverage_sha": COVERED,
            "observed_at": "2026-09-10T10:20:30.123456Z",
            "targets": [{"version": "1.20.1", "loaders": ["fabric", "forge"], "java": [17],
                         "build": gate, "e2e": copy.deepcopy(gate)}]}


class TargetStatusRenderTest(unittest.TestCase):
    def test_exact_tree_is_deterministic_and_write_site_preserves_snapshot(self):
        data = snapshot()
        original = copy.deepcopy(data)
        files = renderer.render_files(data)
        self.assertEqual(set(files), {"status.json", "README.md", "targets/1.20.1.md",
                                     "badges/1.20.1/build.svg", "badges/1.20.1/e2e.svg"})
        self.assertEqual(json.loads(files["status.json"]), data)
        self.assertEqual(renderer.render_files(data), files)
        self.assertEqual(data, original)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "snapshot"
            renderer.write_site(data, output)
            actual = {path.relative_to(output).as_posix(): path.read_bytes()
                      for path in output.rglob("*") if path.is_file()}
            self.assertEqual(actual, files)

    def test_all_states_have_visible_commit_and_inert_accessible_svg(self):
        for state, (label, _color) in renderer.STATES.items():
            with self.subTest(state=state):
                data = snapshot()
                data["targets"][0]["build"]["state"] = state
                raw = renderer.render_files(data)["badges/1.20.1/build.svg"]
                element = ET.fromstring(raw)
                visible = " ".join(item.text or "" for item in element.iter()
                                   if item.tag.endswith("}text"))
                self.assertIn(label, visible)
                self.assertIn(COVERED[:12], visible)
                self.assertIn(COVERED, element.attrib["aria-label"])
                for item in element.iter():
                    self.assertNotIn(item.tag.split("}")[-1], {"script", "style", "image", "foreignObject"})
                    self.assertFalse(any(key.endswith("href") or key.startswith("on") for key in item.attrib))

    def test_reuse_details_bind_original_attempt_and_distinct_tested_commit(self):
        data = snapshot()
        gate = data["targets"][0]["e2e"]
        gate.update(execution=run(20, ORIGINAL, 2), tested_sha=TESTED, reused=True)
        for job in gate["jobs"]:
            job["url"] = job["url"].replace("/runs/10/", "/runs/20/")
        details = renderer.render_files(data)["targets/1.20.1.md"].decode()
        self.assertIn("## E2E", details)
        self.assertIn("authenticated reuse of the original PR", details)
        self.assertIn("/runs/20/attempts/2", details)
        self.assertIn("/runs/20/job/100", details)
        self.assertIn(f"Tested commit: `{TESTED}`", details)
        self.assertIn(ORIGINAL, details)
        self.assertIn(COVERED, details)
        self.assertIn(data["observed_at"], details)

    def test_target_pass_is_separate_from_overall_workflow_failure(self):
        data = snapshot()
        for kind in ("build", "e2e"):
            for key in ("generation", "execution"):
                data["targets"][0][kind][key]["conclusion"] = "failure"
        files = renderer.render_files(data)
        details = files["targets/1.20.1.md"].decode()
        self.assertIn("Target status: Passed", details)
        self.assertIn("Generation workflow (overall status)", details)
        self.assertIn("completed / failure", details)

    def test_unmaterialized_pending_jobs_are_not_presented_as_passed(self):
        data = snapshot()
        gate = data["targets"][0]["build"]
        gate.update(state="pending", jobs=[], execution=None, tested_sha=None)
        gate["generation"].update(status="queued", conclusion=None)
        files = renderer.render_files(data)
        self.assertIn(b"Pending", files["badges/1.20.1/build.svg"])
        self.assertIn(b"Not observed", files["targets/1.20.1.md"])
        self.assertIn(b"Tested commit: not established", files["targets/1.20.1.md"])

    def test_metadata_cannot_inject_markdown_or_html(self):
        data = snapshot()
        gate = data["targets"][0]["build"]
        payload = '<script>alert(1)</script> [click](https://evil.example) `code` | ![image](x)'
        gate["reason"] = payload
        gate["expected_jobs"][0] = payload
        gate["jobs"][0]["name"] = payload
        details = renderer.render_files(data)["targets/1.20.1.md"].decode()
        self.assertNotIn("<script>", details)
        self.assertNotIn("[click](https://evil.example)", details)
        self.assertNotIn("![image](x)", details)
        self.assertIn("&lt;script&gt;", details)
        self.assertIn(r"\|", details)

    def test_foreign_urls_and_changed_execution_identity_are_rejected(self):
        for mutation in (
            lambda gate: gate["generation"].update(url="https://evil.example/"),
            lambda gate: gate["execution"].update(attempt=2),
            lambda gate: gate["generation"].update(sha=ORIGINAL),
            lambda gate: gate["jobs"][0].update(url=f"https://github.com/{REPOSITORY}/actions/runs/99/job/100"),
            lambda gate: gate["jobs"][0].update(url={"url": "bad"}),
            lambda gate: gate.update(execution=None, reused=True),
        ):
            data = snapshot()
            mutation(data["targets"][0]["build"])
            with self.subTest(snapshot=data), self.assertRaises(renderer.RenderError):
                renderer.render_files(data)

    def test_missing_skipped_failed_and_duplicate_jobs_cannot_produce_green(self):
        for mutation in (
            lambda gate: gate.update(jobs=[]),
            lambda gate: gate.update(expected_jobs=[]),
            lambda gate: gate["jobs"][0].update(conclusion="skipped"),
            lambda gate: gate["jobs"][0].update(conclusion="failure"),
            lambda gate: gate["jobs"].append(copy.deepcopy(gate["jobs"][0])),
            lambda gate: gate["expected_jobs"].append(gate["expected_jobs"][0]),
        ):
            data = snapshot()
            mutation(data["targets"][0]["build"])
            with self.subTest(snapshot=data), self.assertRaises(renderer.RenderError):
                renderer.render_files(data)

    def test_bad_paths_unknown_fields_bounds_and_invalid_times_fail_before_writes(self):
        mutations = (
            lambda data: data["targets"][0].update(version="../../outside"),
            lambda data: data["targets"].append(copy.deepcopy(data["targets"][0])),
            lambda data: data.update(untrusted="extra"),
            lambda data: data.update(schema_version=True),
            lambda data: data.update(observed_at="2026-02-30T10:20:30Z"),
            lambda data: data.update(observed_at="2026-09-10T10:20:30+02:00"),
            lambda data: data["targets"][0]["build"].update(reason="x" * 257),
            lambda data: data["targets"][0]["build"]["generation"].update(id=True),
            lambda data: data["targets"][0]["build"]["generation"].update(attempt=101),
            lambda data: data["targets"][0].update(java=[True]),
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            for mutation in mutations:
                data = snapshot()
                mutation(data)
                with self.subTest(snapshot=data), self.assertRaises(renderer.RenderError):
                    renderer.write_site(data, output)
                self.assertFalse(output.exists())

    def test_existing_files_and_symlink_output_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            protected = root / "protected"
            protected.mkdir()
            original = protected / "README.md"
            original.write_text("keep me")
            linked = root / "linked"
            linked.symlink_to(protected, target_is_directory=True)
            for destination in (protected, linked):
                with self.subTest(destination=destination), self.assertRaises(renderer.RenderError):
                    renderer.write_site(snapshot(), destination)
            self.assertEqual(original.read_text(), "keep me")
            self.assertEqual(list(protected.iterdir()), [original])


if __name__ == "__main__":
    unittest.main()
