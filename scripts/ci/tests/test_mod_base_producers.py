"""Quick Skin's producer and consumer steps that call the pinned mod-base composites (SPEC §5.7).

The composites themselves are kit code with kit tests. These tests pin how Quick Skin calls them:
the exact inputs, the step and job names the publication admission reads as upload windows, the
token boundary (notifiers hold only ``actions: write`` and check nothing out; the family producer
no longer holds ``contents: write``), the fail-closed checks that remain in Quick Skin's own shell,
and the Build policy jobs that verify the pinned kit before any mod test imports it.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from test_mod_base_caller import ROOT, WORKFLOWS, job_permissions, pin
from test_workflow_security import job_block, step_script

sys.path.insert(0, str(ROOT / "scripts" / "ci"))
import e2e_job_graph  # noqa: E402

CONFIG = ROOT / "site" / "mod-base.json"
HANDOFF_JOB = "Prepare public evidence for ${{ matrix.bundle_key }} (advisory)"
HANDOFF_STEP = "Upload stable public evidence for this Minecraft target"
FAMILY_JOB = "Publish compact compatibility evidence"
FAMILY_STEP = "Upload the mod-base family handoff"
PREPARE_INPUTS = {
    "e2e-root": "e2e-out",
    "key": "${{ matrix.bundle_key }}",
    "subject-branch": "${{ github.ref_name }}",
    "subject-commit": "${{ github.sha }}",
    "subject-tree": "${{ steps.identity.outputs.target_tree }}",
    "tested-run-id": "${{ steps.identity.outputs.source_run_id }}",
    "tested-run-attempt": "${{ steps.identity.outputs.source_run_attempt }}",
    "tested-branch": "${{ steps.identity.outputs.source_branch }}",
    "tested-commit": "${{ steps.identity.outputs.source_sha }}",
    "tested-controller-branch": "master",
    "tested-controller-sha": "${{ steps.identity.outputs.source_sha }}",
    "extensions": "${{ steps.identity.outputs.extensions_path }}",
    "anchor": "auto",
}
DEPLOY_WAKE = {
    "operation": "deploy",
    "run-id": "${{ github.run_id }}",
    "sha": "${{ github.sha }}",
}
FAMILY_INPUTS = {
    "family": "mod-compatibility",
    "key": "${{ steps.collect.outputs.bundle_key }}",
    "bundle": "public-compatibility/${{ steps.collect.outputs.bundle_key }}",
    "coverage-sha": "${{ github.sha }}",
    "subject-branch": "master",
    "subject-commit": "${{ github.sha }}",
}
FAMILY_WAKE = {
    "operation": "family",
    "run-id": "${{ github.run_id }}",
    "sha": "${{ github.sha }}",
    "family": "mod-compatibility",
    "bundle-key": "${{ needs.publish-evidence.outputs.bundle_key }}",
    "artifact-id": "${{ needs.publish-evidence.outputs.artifact_id }}",
    "artifact-digest": "${{ needs.publish-evidence.outputs.artifact_digest }}",
    "coverage-sha": "${{ needs.publish-evidence.outputs.coverage_sha }}",
}


def steps(block: str) -> list[str]:
    """Every step of one job block, each from its ``- name:`` line to the next step."""

    body = block.split("\n    steps:\n", 1)[1]
    return [f"      - name:{part}" for part in re.split(r"(?m)^      - name:", body)[1:]]


def step(block: str, name: str) -> str:
    matches = [item for item in steps(block) if item.startswith(f"      - name: {name}\n")]
    if len(matches) != 1:
        raise AssertionError(f"expected one step named {name!r}, found {len(matches)}")
    return matches[0]


def step_names(block: str) -> list[str]:
    return [item.split("\n", 1)[0][len("      - name: "):] for item in steps(block)]


def composite_inputs(block: str) -> dict[str, str]:
    """The ``with:`` mapping of one step (8-space key, 10-space entries), in file order."""

    match = re.search(r"(?m)^        with:\n((?:          [a-z0-9-]+: .*\n)+)", block)
    if match is None:
        raise AssertionError("step has no with: mapping")
    pairs = re.findall(r"(?m)^          ([a-z0-9-]+): (.*)$", match.group(1))
    result = dict(pairs)
    if len(result) != len(pairs):
        raise AssertionError("duplicate with: key")
    return result


def composite(name: str) -> str:
    sha, version, _references = pin()
    return f"        uses: The-Plum-Team/mod-base/actions/{name}@{sha} # {version}\n"


def run_bash(script: str, environment: dict[str, str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["bash", "-c", script], cwd=cwd, env=environment, capture_output=True, text=True,
                          check=False)


class ModBaseProducersTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment = {
            # Real tools (Bash 5 and jq on the runner) stay reachable behind the stubs.
            "PATH": f"{self.bin}{os.pathsep}{os.environ.get('PATH', '')}",
            "GITHUB_OUTPUT": str(self.root / "outputs"),
            "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod",
            "RUNNER_TEMP": str(self.root),
        }

    def stub(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        path.chmod(0o755)

    def outputs(self) -> dict[str, str]:
        path = Path(self.environment["GITHUB_OUTPUT"])
        if not path.exists():
            return {}
        return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines())

    def test_ordinary_evidence_is_prepared_only_by_the_pinned_composite(self) -> None:
        job = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        self.assertIn(f"    name: {HANDOFF_JOB}\n", job)
        self.assertEqual({"actions": "read", "contents": "read"}, job_permissions(job))
        self.assertIn("continue-on-error: true", job)
        self.assertNotIn("actions/upload-artifact@", job)
        self.assertNotIn("scripts/pages/evidence.py", job)
        self.assertNotIn("pages-e2e-", job)
        self.assertNotIn("raw_retention_days", job)
        self.assertEqual([
            "Check out the final candidate tree without credentials",
            "Install Python",
            "Install the hash-locked evidence decoder",
            "Resolve exact public evidence provenance",
            "Fetch exact packaged behavior evidence",
            "Bind the packaged runtime selection to its protected admission",
            HANDOFF_STEP,
            "Confirm the exact handoff was published",
        ], step_names(job))
        handoff = step(job, HANDOFF_STEP)
        self.assertIn(composite("prepare-evidence"), handoff)
        self.assertIn("        id: handoff\n", handoff)
        self.assertIn("        if: steps.identity.outputs.publish == 'true'\n", handoff)
        self.assertEqual(PREPARE_INPUTS, composite_inputs(handoff))
        self.assertIn("persist-credentials: false", job)
        self.assertIn("--requirement scripts/pages/requirements.txt", job)
        identity = step(job, "Resolve exact public evidence provenance")
        self.assertIn('feature_pages.py --runtime-identity', identity)
        self.assertIn('--output "$RUNNER_TEMP/public-runtime" --github-output "$GITHUB_OUTPUT"', identity)

    def test_direct_identity_exports_the_attempt_tree_and_no_extensions(self) -> None:
        script = step_script("on-demand-e2e.yml", "prepare-pages-evidence",
                             "Resolve exact public evidence provenance")
        tree = "c" * 40
        self.stub("python3", 'case "$1" in\n'
                  '  scripts/pages/evidence_target.py) printf "master\\n" ;;\n'
                  '  scripts/release/release_sources.py) printf "branches\\n" ;;\n'
                  '  *) exit 91 ;;\nesac\n')
        self.stub("gh", '[[ "$1 $2" == "api repos/The-Plum-Team/Quick-Skin-Mod/actions/runs/77" ]] || exit 92\n'
                  'if [[ "${3:-}" == --jq ]]; then printf "2026-09-01T01:00:00Z\\n"; exit 0; fi\n'
                  'printf \'{"created_at":"2026-09-01T00:00:00Z","run_attempt":2}\\n\'\n')
        self.stub("git", f'[[ "$*" == "rev-parse {"b" * 40}^{{tree}}" ]] && printf "{tree}\\n"\n')
        environment = {**self.environment, "ATTEST_BRANCH": "", "ATTEST_RUN_ID": "", "ATTEST_SHA": "",
                       "ATTEST_TARGET_SHA": "", "SOURCE_BRANCH": "master", "GITHUB_REF_NAME": "master",
                       "GITHUB_RUN_ID": "77", "GITHUB_SHA": "b" * 40}
        completed = run_bash(script, environment, self.root)
        self.assertEqual(0, completed.returncode, completed.stderr)
        outputs = self.outputs()
        self.assertEqual("77", outputs["source_run_id"])
        self.assertEqual("2", outputs["source_run_attempt"])
        self.assertEqual(tree, outputs["target_tree"])
        self.assertEqual("", outputs["extensions_path"])
        self.assertEqual("b" * 40, outputs["source_sha"])

        self.stub("gh", 'printf \'{"created_at":"2026-09-01T00:00:00Z","run_attempt":null}\\n\'\n')
        Path(self.environment["GITHUB_OUTPUT"]).unlink()
        self.assertNotEqual(0, run_bash(script, environment, self.root).returncode)
        self.assertNotIn("publish", self.outputs())

    def test_packaged_selection_must_equal_the_protected_admission(self) -> None:
        script = step_script("on-demand-e2e.yml", "prepare-pages-evidence",
                             "Bind the packaged runtime selection to its protected admission")
        selection = self.root / "e2e-out" / "selection.json"
        selection.parent.mkdir()
        selection.write_text('{"selected": true}\n', encoding="utf-8")
        digest = hashlib.sha256(selection.read_bytes()).hexdigest()
        self.stub("sha256sum", 'python3 -c "import hashlib,sys; '
                  'print(hashlib.sha256(open(sys.argv[1],\'rb\').read()).hexdigest()+\'  \'+sys.argv[1])" "$1"\n')
        (self.bin / "python3").symlink_to(sys.executable)
        for enabled, expected, exit_zero in (("true", digest, True), ("false", "0" * 64, True),
                                             ("true", "0" * 64, False), ("true", "not-a-digest", False)):
            with self.subTest(enabled=enabled, expected=expected):
                completed = run_bash(script, {**self.environment, "SELECTION_ENABLED": enabled,
                                              "SELECTION_SHA256": expected}, self.root)
                self.assertEqual(exit_zero, completed.returncode == 0, completed.stderr)
                if not exit_zero:
                    self.assertIn("differs from protected admission", completed.stderr)
        selection.unlink()
        self.assertNotEqual(0, run_bash(script, {**self.environment, "SELECTION_ENABLED": "true",
                                                 "SELECTION_SHA256": digest}, self.root).returncode)

    def test_notifiers_hold_only_actions_write_and_check_nothing_out(self) -> None:
        for workflow, job, expected in (("on-demand-e2e.yml", "notify-pages", DEPLOY_WAKE),
                                        ("mod-compatibility-review.yml", "notify-family", FAMILY_WAKE)):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertEqual({"actions": "write"}, job_permissions(block))
                self.assertNotIn("actions/checkout@", block)
                self.assertNotIn("run: ", block)
                self.assertNotIn("repos/$GITHUB_REPOSITORY/dispatches", block)
                self.assertEqual(1, len(steps(block)))
                self.assertIn(composite("notify-pages"), block)
                self.assertEqual(expected, composite_inputs(steps(block)[0]))
        family = job_block("mod-compatibility-review.yml", "notify-family")
        self.assertIn("    name: Wake the protected project site for compatibility evidence\n", family)
        self.assertIn("    needs: publish-evidence\n", family)
        self.assertNotIn("if:", family)
        pages = job_block("on-demand-e2e.yml", "notify-pages")
        self.assertIn("needs.prepare-pages-evidence.outputs.published == 'true'", pages)
        self.assertIn("continue-on-error: true", pages)

    def test_compatibility_family_is_published_without_contents_write(self) -> None:
        job = job_block("mod-compatibility-review.yml", "publish-evidence")
        self.assertIn(f"    name: {FAMILY_JOB}\n", job)
        self.assertEqual({"actions": "read", "contents": "read"}, job_permissions(job))
        self.assertNotIn("contents: write", job)
        self.assertNotIn("actions/upload-artifact@", job)
        self.assertNotIn("pages-compatibility-evidence-ready", job)
        self.assertNotIn("repos/$GITHUB_REPOSITORY/dispatches", job)
        self.assertEqual([
            "Check out the protected compatibility publisher",
            "Install Python",
            "Install the hash-locked evidence decoder and encoder",
            "Authenticate, compact and bind the complete clean wave",
            "Bind the collected coverage to this protected head",
            FAMILY_STEP,
        ], step_names(job))
        handoff = step(job, FAMILY_STEP)
        self.assertIn(composite("publish-family"), handoff)
        self.assertIn("        id: handoff\n", handoff)
        self.assertEqual(FAMILY_INPUTS, composite_inputs(handoff))
        for output, source in (("artifact_id", "steps.handoff.outputs.artifact-id"),
                               ("artifact_digest", "steps.handoff.outputs.artifact-digest"),
                               ("bundle_key", "steps.collect.outputs.bundle_key"),
                               ("coverage_sha", "steps.coverage.outputs.coverage_sha")):
            self.assertIn(f"      {output}: ${{{{ {source} }}}}\n", job)
        self.assertIn("scripts/pages/collect_compatibility.py", job)
        self.assertIn("--output public-compatibility", job)

    def test_family_coverage_must_be_the_protected_head(self) -> None:
        script = step_script("mod-compatibility-review.yml", "publish-evidence",
                             "Bind the collected coverage to this protected head")
        head = "d" * 40
        bundle = self.root / "public-compatibility" / "mc1.20.1"
        bundle.mkdir(parents=True)
        (bundle / "manifest.json").write_text("{}\n", encoding="utf-8")
        environment = {**self.environment, "GITHUB_SHA": head, "BUNDLE_KEY": "mc1.20.1", "COVERAGE_SHA": head}
        completed = run_bash(script, environment, self.root)
        self.assertEqual(0, completed.returncode, completed.stderr)
        self.assertEqual({"coverage_sha": head}, self.outputs())
        for override in ({"COVERAGE_SHA": "e" * 40}, {"COVERAGE_SHA": ""}, {"BUNDLE_KEY": "mc--1"},
                         {"BUNDLE_KEY": "../mc1.20.1"}, {"BUNDLE_KEY": "mc26.3"}):
            with self.subTest(override=override):
                Path(self.environment["GITHUB_OUTPUT"]).unlink(missing_ok=True)
                self.assertNotEqual(0, run_bash(script, {**environment, **override}, self.root).returncode)
                self.assertEqual({}, self.outputs())
        linked = self.root / "public-compatibility" / "mc26.3"
        linked.mkdir()
        (linked / "manifest.json").symlink_to(bundle / "manifest.json")
        (self.root / "public-compatibility" / "mc26.2").symlink_to(bundle, target_is_directory=True)
        for key in ("mc26.3", "mc26.2"):
            with self.subTest(symlinked=key):
                self.assertNotEqual(0, run_bash(script, {**environment, "BUNDLE_KEY": key}, self.root).returncode)
                self.assertEqual({}, self.outputs())

    def test_build_policy_jobs_verify_the_pinned_kit_before_mod_tests(self) -> None:
        policy = job_block("build-gate.yml", "policy")
        names = step_names(policy)
        setup = step(policy, "Verify the pinned mod-base kit tree")
        self.assertIn(composite("setup"), setup)
        self.assertEqual({"install-imaging": '"false"'}, composite_inputs(setup))
        self.assertLess(names.index("Install Python"), names.index("Verify the pinned mod-base kit tree"))
        verify = step_script("build-gate.yml", "policy", "Verify the pinned mod-base kit and managed files")
        self.assertIn("python3 scripts/ci/mod_base_kit.py verify --network\n", verify)
        self.assertIn("env -u GH_TOKEN python3 scripts/ci/mod_base_kit.py run template check --repo .", verify)
        self.assertLess(names.index("Verify the pinned mod-base kit tree"),
                        names.index("Verify the pinned mod-base kit and managed files"))
        self.assertNotIn("node --check", policy)
        for job in ("policy-release", "policy-ci"):
            with self.subTest(job=job):
                block = job_block("build-gate.yml", job)
                names = step_names(block)
                self.assertIn(composite("setup"), step(block, "Verify the pinned mod-base kit tree"))
                self.assertNotIn("with:", step(block, "Verify the pinned mod-base kit tree"))
                self.assertLess(names.index("Install Python"), names.index("Verify the pinned mod-base kit tree"))
                self.assertLess(names.index("Verify the pinned mod-base kit tree"),
                                names.index("Install hash-locked Pages image dependency"))

    def test_config_upload_windows_name_the_real_producer_jobs_and_steps(self) -> None:
        config = json.loads(CONFIG.read_text(encoding="utf-8"))
        source = config["source"]
        self.assertEqual(".github/workflows/on-demand-e2e.yml", source["workflow"])
        self.assertEqual(HANDOFF_JOB.replace("${{ matrix.bundle_key }}", "{key}"), source["handoff_job"])
        self.assertEqual(HANDOFF_STEP, source["handoff_step"])
        self.assertIn("workflow_dispatch", source["events"]["canonical"])
        families = {family["id"]: family for family in config["families"]}
        producer = families["mod-compatibility"]["producer"]
        self.assertEqual(".github/workflows/mod-compatibility-review.yml", producer["workflow"])
        self.assertEqual(FAMILY_JOB, producer["job"])
        self.assertEqual(FAMILY_STEP, producer["step"])
        self.assertLessEqual({"repository_dispatch", "workflow_dispatch"}, set(producer["events"]))

    def test_publication_code_is_a_protected_port_controller(self) -> None:
        protected = set(e2e_job_graph.PROTECTED_CONTROLLER_PATHS)
        for path in ("scripts/pages/mod_base_adapter.py", "site/mod-base.json", "scripts/ci/mod_base_kit.py"):
            with self.subTest(path=path):
                self.assertIn(path, protected)
        for retired in ("scripts/pages/evidence.py", "scripts/pages/build_site.py",
                        "scripts/pages/select_artifact.py"):
            self.assertNotIn(retired, protected)
        self.assertFalse((WORKFLOWS / "rotate-pages-evidence.yml").exists())


if __name__ == "__main__":
    unittest.main()
