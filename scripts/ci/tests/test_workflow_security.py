from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
WORKFLOWS = ROOT / ".github" / "workflows"
COMPOSITE_ACTIONS = ROOT / ".github" / "actions"
UPLOAD_ARTIFACT_USE = re.compile(
    r"^\s+(?:-\s+)?uses:\s+actions/upload-artifact@\S+"
)


def workflow_paths() -> list[Path]:
    return sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))


def job_block(workflow: str, job: str) -> str:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\n(.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)", text
    )
    if match is None:
        raise AssertionError(f"missing job {job} in {workflow}")
    return match.group(0)


def upload_artifact_steps() -> list[tuple[str, str, str]]:
    """Return every named workflow step that uploads an Actions artifact."""

    uploads: list[tuple[str, str, str]] = []
    for workflow in workflow_paths():
        lines = workflow.read_text(encoding="utf-8").splitlines()
        for uses_index, line in enumerate(lines):
            if UPLOAD_ARTIFACT_USE.match(line) is None:
                continue
            uses_indent = len(line) - len(line.lstrip())
            step_index: int | None = None
            step_name: str | None = None
            for candidate_index in range(uses_index, -1, -1):
                candidate = lines[candidate_index]
                candidate_indent = len(candidate) - len(candidate.lstrip())
                if candidate_indent == uses_indent - 2 and candidate.lstrip().startswith(
                    "- name:"
                ):
                    step_index = candidate_index
                    step_name = candidate.lstrip()[len("- name:") :].strip()
                    break
                if candidate_indent < uses_indent - 2 and candidate.strip():
                    break
            if step_index is None or not step_name:
                raise AssertionError(
                    f"upload-artifact step at {workflow.name}:{uses_index + 1} "
                    "must have a non-empty name"
                )

            step_indent = uses_indent - 2
            end_index = len(lines)
            for candidate_index in range(uses_index + 1, len(lines)):
                candidate = lines[candidate_index]
                candidate_indent = len(candidate) - len(candidate.lstrip())
                if candidate_indent == step_indent and candidate.lstrip().startswith("- "):
                    end_index = candidate_index
                    break
                if candidate.strip() and candidate_indent < step_indent:
                    end_index = candidate_index
                    break
            uploads.append(
                (workflow.name, step_name, "\n".join(lines[step_index:end_index]))
            )
    return uploads


class WorkflowSecurityTest(unittest.TestCase):
    def test_secret_bearing_ai_steps_have_a_closed_tool_and_path_surface(self) -> None:
        secret_steps = 0
        for workflow in workflow_paths():
            text = workflow.read_text(encoding="utf-8")
            for match in re.finditer(r"(?m)^      - name: ", text):
                next_step = text.find("\n      - name: ", match.end())
                block = text[match.start() : next_step if next_step >= 0 else len(text)]
                if "CLAUDE_CODE_OAUTH_TOKEN:" not in block:
                    continue
                secret_steps += 1
                with self.subTest(workflow=workflow.name, step=block.splitlines()[0]):
                    self.assertIn('CLAUDE_CODE_SKIP_PROMPT_HISTORY: "1"', block)
                    self.assertIn("--safe-mode", block)
                    self.assertIn("--no-session-persistence", block)
                    self.assertIn("--permission-mode dontAsk", block)
                    self.assertNotIn("--permission-mode acceptEdits", block)
                    self.assertNotRegex(block, r'--tools "[^"]*Bash')
                    self.assertNotIn("--allowedTools Read", block)
                    if workflow.name == "visual-review.yml":
                        self.assertIn('--tools "Read"', block)
                        self.assertNotIn('"Read(./**)"', block)
                        self.assertNotIn("e2e-out", block)
                        self.assertIn(
                            '"Read(./review-input/visual-review-manifest.json)"',
                            block,
                        )
                        self.assertIn('"Read(./review-input/images/**)"', block)
                        self.assertIn("> visual-review-report.raw.json", block)
                        self.assertIn("unset GH_TOKEN GITHUB_TOKEN", block)
                        self.assertNotIn("Edit", block)
                        self.assertNotIn('"Write(', block)
                    else:
                        self.assertRegex(block, r'--tools "Read(?:,Edit)?,Write"')
                        self.assertIn('"Read(./**)"', block)
                        if ",Edit," in block:
                            self.assertIn('"Edit(./**)"', block)
                        self.assertIn('"Write(', block)
        self.assertEqual(secret_steps, 3)

    def test_external_actions_are_pinned_to_full_commit_shas(self) -> None:
        definitions = [
            *WORKFLOWS.glob("*.yml"),
            *COMPOSITE_ACTIONS.glob("*/action.yml"),
        ]
        for workflow in definitions:
            for line_number, line in enumerate(
                workflow.read_text(encoding="utf-8").splitlines(), start=1
            ):
                match = re.match(r"\s*(?:-\s+)?uses:\s+(\S+)", line)
                if match is None or match.group(1).startswith("./"):
                    continue
                with self.subTest(workflow=workflow.name, line=line_number):
                    self.assertRegex(match.group(1), r"^[^@]+@[0-9a-f]{40}$")

    def test_packaged_runtime_composite_is_shared_and_bounded(self) -> None:
        action = (
            COMPOSITE_ACTIONS / "run-packaged-e2e" / "action.yml"
        ).read_text(encoding="utf-8")
        on_demand = job_block("on-demand-e2e.yml", "e2e")
        release = job_block("release.yml", "runtime-behavior")

        self.assertIn("uses: ./.github/actions/run-packaged-e2e", on_demand)
        self.assertIn("bundle-name: e2e-input-bundle", on_demand)
        self.assertIn("evidence-name: packaged-e2e-${{ matrix.id }}", on_demand)
        self.assertIn("uses: ./.github/actions/run-packaged-e2e", release)
        self.assertIn(
            "bundle-name: release-${{ needs.build.outputs.release_id }}", release
        )
        self.assertIn("evidence-name: release-behavior-${{ matrix.id }}", release)
        self.assertIn("QUICKSKIN_E2E_RUNTIME_STORE", action)
        self.assertIn("e2e/ci_summary.py", action)
        self.assertIn("e2e-out/current/profiles/**/result.json", action)
        self.assertIn("e2e-out/current/summary.json", action)
        self.assertNotIn("e2e-out/profiles/**", action)
        self.assertNotIn("e2e-out/runs/**", action)
        self.assertIn("if-no-files-found: error", action)
        self.assertIn("name: ${{ inputs.evidence-name }}", action)
        self.assertIn("retention-days: 1", action)

    def test_pr_and_nightly_e2e_select_matrix_owned_coverage(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        self.assertIn('cron: "17 3 * * *"', workflow)
        self.assertIn("github.event_name == 'schedule'", workflow)
        self.assertIn("'native-anchors' || 'pr-anchors'", workflow)
        self.assertIn('--kind "$MATRIX_KIND"', workflow)

    def test_upload_artifact_retention_is_bounded_with_two_named_exceptions(
        self,
    ) -> None:
        uploads = upload_artifact_steps()
        raw_upload_count = sum(
            sum(
                UPLOAD_ARTIFACT_USE.match(line) is not None
                for line in workflow.read_text(encoding="utf-8").splitlines()
            )
            for workflow in workflow_paths()
        )
        self.assertEqual(len(uploads), raw_upload_count)

        long_lived = {
            (
                "pages.yml",
                "Roll the protected evidence cache forward",
                "${{ steps.cache.outputs.name }}",
            ),
            (
                "release.yml",
                "Upload immutable release bundle",
                "release-${{ steps.release.outputs.release_id }}",
            ),
        }
        observed_long_lived: set[tuple[str, str, str]] = set()
        for workflow, step_name, block in uploads:
            uses_line = next(
                line
                for line in block.splitlines()
                if "actions/upload-artifact@" in line
            )
            input_indent = " " * (len(uses_line) - len(uses_line.lstrip()) + 2)
            artifact_names = re.findall(
                rf"(?m)^{re.escape(input_indent)}name:[ \t]*(.+?)[ \t]*$", block
            )
            retention_values = re.findall(
                rf"(?m)^{re.escape(input_indent)}retention-days:[ \t]*(.+?)[ \t]*$",
                block,
            )
            with self.subTest(workflow=workflow, step=step_name):
                self.assertEqual(len(artifact_names), 1)
                self.assertEqual(len(retention_values), 1)
                identity = (workflow, step_name, artifact_names[0])
                expected_retention = "90" if identity in long_lived else "1"
                self.assertEqual(retention_values[0], expected_retention)
                if retention_values[0] == "90":
                    observed_long_lived.add(identity)
        self.assertEqual(observed_long_lived, long_lived)

    def test_gradle_cache_writes_are_limited_to_protected_master_builds(self) -> None:
        build = job_block("build-gate.yml", "build")
        e2e = job_block("on-demand-e2e.yml", "build")
        release = job_block("release.yml", "build")
        policy = (ROOT / "scripts" / "ci" / "gradle_cache_policy.py").read_text(
            encoding="utf-8"
        )

        setup_count = sum(
            workflow.read_text(encoding="utf-8").count("gradle/actions/setup-gradle@")
            for workflow in WORKFLOWS.glob("*.yml")
        )
        self.assertEqual(setup_count, 3)
        self.assertIn("scripts/ci/gradle_cache_policy.py", build)
        self.assertIn("--matrix release/release-matrix.json", build)
        self.assertIn('--event-name "$GITHUB_EVENT_NAME"', build)
        self.assertIn('--ref-name "$GITHUB_REF_NAME"', build)
        self.assertIn("REF_PROTECTED: ${{ github.ref_protected }}", build)
        self.assertIn("REF_TYPE: ${{ github.ref_type }}", build)
        self.assertIn('--ref-type "$REF_TYPE"', build)
        self.assertIn('--ref-protected "$REF_PROTECTED"', build)
        self.assertIn(
            "cache-read-only: ${{ steps.gradle-cache.outputs.read_only }}", build
        )
        self.assertIn("cache-cleanup: on-success", build)
        for workflow, block in (("on-demand-e2e.yml", e2e), ("release.yml", release)):
            with self.subTest(workflow=workflow):
                self.assertEqual(block.count("gradle/actions/setup-gradle@"), 1)
                self.assertEqual(block.count("cache-read-only: true"), 1)
                self.assertNotIn("gradle_cache_policy.py", block)

        self.assertIn('WRITER_EVENTS = frozenset({"push", "workflow_dispatch"})', policy)
        self.assertIn("event_name not in WRITER_EVENTS", policy)
        self.assertIn('or ref_type != "branch"', policy)
        self.assertIn("or not ref_protected", policy)
        self.assertIn('return ref_name != "master"', policy)

    def test_visual_review_is_advisory_and_not_a_port_gate(self) -> None:
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        visual_workflow = (WORKFLOWS / "visual-review.yml").read_text(
            encoding="utf-8"
        )
        visual_prompt = (ROOT / "e2e" / "visual_review_prompt.md").read_text(
            encoding="utf-8"
        )
        authenticate = job_block("visual-review.yml", "authenticate")
        curate = job_block("visual-review.yml", "curate")
        visual = job_block("visual-review.yml", "review")
        cleanup = job_block("visual-review.yml", "cleanup-curated-input")
        pages = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        notify = job_block("on-demand-e2e.yml", "notify-version-port")
        self.assertNotRegex(e2e, r"(?m)^  visual-review:")
        self.assertIn("continue-on-error: true", pages)
        self.assertIn("- required-gate", notify)
        self.assertIn("- prepare-pages-evidence", notify)
        self.assertIn("visual-review-requested", notify)
        self.assertIn("continue-on-error: true", notify)
        self.assertIn("contents: write", notify)
        self.assertIn("startsWith(github.ref_name, 'automation/sync/')", notify)
        self.assertNotIn("VISUAL_RESULT", notify)
        self.assertNotIn("visual-review.yml", notify)
        self.assertIn("permissions: {}", visual_workflow)
        self.assertIn("visual-review-requested", visual_workflow)
        self.assertIn("workflows:\n      - Packaged E2E", visual_workflow)
        self.assertIn("open BOTH the candidate and its 1.20.1 reference", visual_prompt)
        self.assertIn("becoming softer or blurred", visual_prompt)
        self.assertIn("only the Minecraft world behind an overlay", visual_prompt)
        self.assertEqual(
            {"authenticate", "curate", "review", "cleanup-curated-input"},
            set(re.findall(r"(?m)^  ([a-z0-9-]+):\n", visual_workflow)),
        )
        self.assertIn("source_run_id", authenticate)
        self.assertIn("source_sha", authenticate)
        self.assertIn('name == "Packaged E2E gate"', authenticate)
        self.assertIn('endswith(" - contract scenarios")', authenticate)
        self.assertIn("actions/runs/$source_run_id/artifacts", authenticate)
        self.assertIn('startswith("packaged-e2e-")', authenticate)
        self.assertIn('[[ "$packaged_count" -eq 0 ]]', authenticate)
        self.assertIn('[[ "$packaged_count" -eq "$scenario_job_count" ]]', authenticate)
        self.assertIn("artifact_inventory", authenticate)
        self.assertIn('implementation_sha="$GITHUB_SHA"', authenticate)
        self.assertNotIn("branches/master", authenticate)
        self.assertIn(".workflow_run.id == $run_id", authenticate)
        self.assertIn('.path == ".github/workflows/on-demand-e2e.yml"', authenticate)
        self.assertIn(".head_repository.full_name == $repository", authenticate)
        for block in (curate, visual):
            self.assertIn(
                "ref: ${{ needs.authenticate.outputs.implementation_sha }}", block
            )
            self.assertIn("persist-credentials: false", block)
            self.assertIn("actions: read", block)
            self.assertIn("contents: read", block)
            self.assertNotIn("contents: write", block)
        self.assertIn("artifact_inventory", curate)
        self.assertIn("actions/artifacts/$artifact_id", curate)
        self.assertIn("scripts/ci/bounded_zip.py", curate)
        self.assertIn("scripts/ci/e2e_job_graph.py", curate)
        self.assertNotIn("path: source", curate)
        self.assertNotIn("git -C source", curate)
        self.assertIn('git fetch --no-tags origin "$SOURCE_SHA"', curate)
        self.assertIn(
            'git show "$SOURCE_SHA:e2e/scenario-contract.json"', curate
        )
        self.assertIn(
            'git show "$SOURCE_SHA:release/release-matrix.json"', curate
        )
        self.assertIn(
            'git show "$SOURCE_SHA:gradle.properties" > "$source_properties"',
            curate,
        )
        self.assertEqual(
            2, curate.count('--matrix-properties "$source_properties"')
        )
        self.assertIn('--repository-head-sha "$IMPLEMENTATION_SHA"', curate)
        self.assertIn("--reference-identity", curate)
        self.assertIn("scripts/pages/select_artifact.py", curate)
        self.assertNotIn(
            "Resolve the authenticated 1.20.1 visual reference", visual_workflow
        )
        self.assertNotIn("steps.reference.outputs", curate)
        self.assertLess(
            curate.index("scripts/ci/e2e_job_graph.py"),
            curate.index("--reference-identity"),
        )
        self.assertIn("actions/artifacts/$REFERENCE_ARTIFACT_ID", curate)
        self.assertIn("scripts/pages/evidence.py compact", curate)
        self.assertIn("scripts/pages/evidence.py validate", curate)
        self.assertIn("--reference-evidence-root", curate)
        self.assertIn("--reference-artifact-node", curate)
        self.assertIn("--all", curate)
        self.assertIn("--validate-row-json", curate)
        self.assertIn("--curate-output", curate)
        self.assertIn("e2e/check_visual_review.py", curate)
        self.assertEqual(1, curate.count("--validate-input-only"))
        self.assertEqual(1, curate.count("--require-paired"))
        self.assertIn("curation-proof.json", curate)
        self.assertIn("Upload only the curated review input", curate)
        self.assertIn("if-no-files-found: error", curate)
        self.assertIn("retention-days: 1", curate)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", curate)
        self.assertNotIn("claude-code", curate)
        self.assertIn("- curate", visual)
        self.assertIn("actions/artifacts/$ARTIFACT_ID", visual)
        self.assertIn("scripts/ci/bounded_zip.py", visual)
        self.assertIn("--max-entries 520", visual)
        self.assertEqual(2, visual.count("--validate-input-only"))
        self.assertEqual(3, visual.count("--require-paired"))
        self.assertIn("visual-review-capsule", visual)
        self.assertNotIn("actions/download-artifact@", visual_workflow)
        self.assertNotIn("merge-multiple", visual)
        self.assertNotIn("packaged-e2e-", visual)
        self.assertNotIn("e2e-out", visual)
        self.assertIn("CLAUDE_CODE_OAUTH_TOKEN", visual)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", notify)
        self.assertIn("visual-review-manifest.sha256", visual)
        self.assertIn("visual-review-checker.py", visual)
        self.assertIn("visual_reference", visual)
        self.assertIn("visual-review-model-${{ needs.authenticate.outputs.source_run_id }}", visual)
        self.assertNotIn("group: visual-review-model\n", visual)
        self.assertIn("sha256sum --check", visual)
        self.assertIn('git -C "$GITHUB_WORKSPACE" diff --exit-code', visual)
        self.assertIn('python3 "$RUNNER_TEMP/visual-review-checker.py"', visual)
        self.assertIn("--normalized-report visual-review-report.json", visual)
        upload_report = visual[visual.index("- name: Upload the source-bound review report") :]
        self.assertNotIn("visual-review-report.raw.json", upload_report)
        self.assertIn("visual-review-report.json", upload_report)
        self.assertIn("actions: write", cleanup)
        self.assertNotIn("contents:", cleanup)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", cleanup)
        self.assertNotIn("actions/checkout@", cleanup)
        self.assertIn("gh api --method DELETE", cleanup)
        self.assertIn("actions/artifacts/$ARTIFACT_ID", cleanup)

    def test_pages_fan_in_uses_protected_code_and_exact_release_heads(self) -> None:
        workflow = (WORKFLOWS / "pages.yml").read_text(encoding="utf-8")
        wake = job_block("pages.yml", "wake")
        discover = job_block("pages.yml", "discover")
        collect = job_block("pages.yml", "collect")
        build = job_block("pages.yml", "build")
        deploy = job_block("pages.yml", "deploy")
        refresh = job_block("pages.yml", "refresh-cache")
        selector = (ROOT / "scripts" / "pages" / "select_artifact.py").read_text(
            encoding="utf-8"
        )

        self.assertNotIn("pull_request_target", workflow)
        self.assertIn("permissions: {}", workflow)
        self.assertIn("pages-evidence-ready", workflow)
        self.assertIn("quick-skin-pages-wake", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("actions: write", wake)
        self.assertIn("contents: read", wake)
        self.assertIn("on-demand-e2e.yml", wake)
        self.assertIn("pages-e2e-$EVIDENCE_BRANCH", wake)
        self.assertIn("ref: ${{ steps.request.outputs.implementation_sha }}", wake)
        self.assertIn("persist-credentials: false", wake)
        self.assertIn("gh workflow run pages.yml --ref master", wake)
        self.assertIn("ref: master", discover)
        self.assertIn("implementation_sha:", discover)
        self.assertIn("git rev-parse HEAD", discover)
        for block in (collect, build):
            self.assertIn(
                "ref: ${{ needs.discover.outputs.implementation_sha }}",
                block,
            )
        for block in (discover, collect, build):
            self.assertIn("persist-credentials: false", block)
            self.assertNotIn("id-token: write", block)
            self.assertNotIn("pages: write", block)
        self.assertIn("artifact.head_sha == current_sha", selector)
        self.assertIn('artifact.head_branch == "master"', selector)
        self.assertNotIn("workflows:\n      - Packaged E2E", workflow)
        self.assertNotIn("github.event.workflow_run", workflow)
        self.assertNotIn("TRIGGER_RUN_ID", discover)
        self.assertNotIn("TRIGGER_SHA", discover)
        self.assertIn("pages-evidence-ready", workflow)
        self.assertIn('.event == "workflow_dispatch"', discover)
        self.assertIn('.path == ".github/workflows/on-demand-e2e.yml"', discover)
        self.assertIn("DISPATCH_OPERATION", discover)
        self.assertIn("pages-cache-$branch--$current_sha", discover)
        self.assertIn("Every release head already belongs", discover)
        self.assertIn("github.ref == 'refs/heads/master'", discover)
        self.assertIn("scripts/pages/evidence.py validate", collect)
        self.assertIn("--only-branch", collect)
        self.assertIn("source_run_id", collect)
        self.assertIn("target_run_id", collect)
        self.assertIn("digest-mismatch: error", collect)
        self.assertIn("needs:\n      - discover\n      - collect", build)
        self.assertIn("needs.discover.outputs.branches", build)
        self.assertIn("--expected-branches-json", build)
        self.assertIn("Recheck every branch immediately before rendering", build)
        self.assertIn("name: github-pages", deploy)
        self.assertIn("pages: write", deploy)
        self.assertIn("id-token: write", deploy)
        self.assertNotIn("actions/checkout@", deploy)
        self.assertNotRegex(deploy, r"(?m)^\s+run:")
        self.assertIn('cron: "43 4 1 * *"', workflow)
        self.assertIn("- deploy", refresh)
        self.assertIn("scripts/pages/select_artifact.py", collect)
        self.assertIn('cache_name = f"pages-cache-{branch}--{current_sha}"', selector)
        self.assertIn('legacy_name = f"pages-cache-{branch}"', selector)
        self.assertIn("max(exact, key=lambda item: item.order)", selector)
        self.assertIn("if exact:", selector)
        self.assertIn("^[0-9a-f]{40}$", refresh)
        self.assertIn("name=pages-cache-%s--%s", refresh)
        self.assertIn("name: ${{ steps.cache.outputs.name }}", refresh)
        self.assertIn("actions/checkout@", refresh)
        self.assertIn(
            "ref: ${{ needs.discover.outputs.implementation_sha }}", refresh
        )
        self.assertIn("persist-credentials: false", refresh)
        self.assertIn("scripts/pages/evidence.py validate", refresh)
        self.assertIn("--kind compact", refresh)
        self.assertNotIn("id-token: write", refresh)
        self.assertNotIn("pages: write", refresh)
        self.assertIn("api.list_artifacts(handoff_name)", selector)
        self.assertIn("api.list_artifacts(cache_name)", selector)
        self.assertIn("--require-hashes", build)
        self.assertIn("scripts/pages/requirements.txt", build)

    def test_pages_evidence_rotation_is_post_success_bounded_and_exact(self) -> None:
        workflow = (WORKFLOWS / "pages.yml").read_text(encoding="utf-8")
        request = job_block("pages.yml", "request-rotation")
        rotate = job_block("pages.yml", "rotate")
        handoff = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        rotator = (ROOT / "scripts" / "pages" / "rotate_artifacts.py").read_text(
            encoding="utf-8"
        )

        self.assertFalse((WORKFLOWS / "rotate-pages-evidence.yml").exists())
        self.assertIn("permissions: {}", workflow)
        self.assertIn("quick-skin-pages-evidence-rotation", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertNotIn("continue-on-error", request)
        self.assertIn("actions: write", request)
        self.assertIn("gh workflow run pages.yml --ref master", request)
        self.assertIn("-f operation=rotate", request)
        self.assertIn('pages_run_id="$GITHUB_RUN_ID"', request)
        self.assertIn('pages_run_sha="$GITHUB_SHA"', request)

        self.assertIn("github.event_name == 'workflow_dispatch'", rotate)
        self.assertIn("github.ref == 'refs/heads/master'", rotate)
        self.assertIn("inputs.operation == 'rotate'", rotate)
        self.assertIn("actions: write", rotate)
        self.assertIn("contents: read", rotate)
        self.assertNotIn("pages: write", rotate)
        self.assertNotIn("id-token: write", rotate)
        self.assertNotIn("continue-on-error", rotate)
        authenticate = rotate.index("Authenticate the successful cache-owning Pages run")
        checkout = rotate.index("Check out the exact protected rotation implementation")
        self.assertLess(authenticate, checkout)
        self.assertIn('.status == "completed" and .conclusion == "success"', rotate)
        self.assertIn('.path == ".github/workflows/pages.yml"', rotate)
        self.assertIn('.head_branch == "master"', rotate)
        self.assertIn(".head_repository.full_name == $repository", rotate)
        self.assertIn(".workflow_id == $workflow_id", rotate)
        self.assertIn('"repos/$GITHUB_REPOSITORY/branches/master" --jq .commit.sha', rotate)
        self.assertIn("ref: ${{ steps.owner.outputs.implementation_sha }}", rotate)
        self.assertIn("persist-credentials: false", rotate)
        self.assertIn("pattern: pages-cache-*", rotate)
        self.assertIn("run-id: ${{ steps.owner.outputs.pages_run_id }}", rotate)
        self.assertIn("digest-mismatch: error", rotate)
        self.assertIn("scripts/pages/rotate_artifacts.py", rotate)
        self.assertIn("--pages-run-id", rotate)
        self.assertIn("--pages-run-sha", rotate)
        self.assertIn("steps.owner.outputs.pages_run_sha", rotate)

        self.assertIn("actions: read", handoff)
        self.assertNotIn("actions: write", handoff)
        self.assertIn("pages-e2e-${{ github.ref_name }}", handoff)
        self.assertIn("retention-days: 1", handoff)
        self.assertIn('expected_names = {"github-pages"}', rotator)
        self.assertIn(
            'f"collected-pages-{generation.branch}" for generation in generations',
            rotator,
        )
        self.assertIn("for artifact in (*old_caches, *handoffs):", rotator)
        self.assertIn("retire_pages_run_transients(", rotator)
        self.assertIn("api.get_artifact(artifact.artifact_id)", rotator)
        self.assertIn("api.delete_artifact(artifact.artifact_id)", rotator)

    def test_bounded_actions_caches_are_pruned_by_exact_id_from_protected_code(
        self,
    ) -> None:
        workflow = (WORKFLOWS / "prune-actions-caches.yml").read_text(
            encoding="utf-8"
        )
        prune = job_block("prune-actions-caches.yml", "prune")
        implementation = (
            ROOT / "scripts" / "ci" / "prune_actions_caches.py"
        ).read_text(encoding="utf-8")

        self.assertIn("permissions: {}", workflow)
        self.assertIn("schedule:", workflow)
        self.assertRegex(workflow, r'cron: "\d+ \d+ \* \* \*"')
        self.assertIn("github.event_name == 'schedule'", prune)
        self.assertIn("actions: write", prune)
        self.assertIn("contents: read", prune)
        self.assertIn("[[ \"$GITHUB_REF\" == refs/heads/master ]]", prune)
        self.assertIn(".default_branch == \"master\"", prune)
        self.assertIn('"repos/$GITHUB_REPOSITORY/branches/master" --jq .commit.sha', prune)
        self.assertIn("ref: ${{ steps.trusted.outputs.implementation_sha }}", prune)
        self.assertIn("persist-credentials: false", prune)
        self.assertIn("scripts/ci/prune_actions_caches.py", prune)
        self.assertIn("--expected-default-branch master", prune)
        self.assertIn("--apply", prune)
        self.assertNotIn("release-matrix", prune)

        self.assertIn('BRANCH_REF_PREFIX = "refs/heads/"', implementation)
        self.assertIn("cache.branch not in existing_branches", implementation)
        self.assertIn("cache.branch not in active_run_branches", implementation)
        self.assertIn("candidates, deferred = _bounded_batch(", implementation)
        self.assertIn("current = api.get_cache(cache)", implementation)
        self.assertIn("if api.has_any_active_run():", implementation)
        self.assertIn("if api.branch_exists(branch):", implementation)
        self.assertIn("api.has_successful_build(branch, sha)", implementation)
        self.assertIn("replacement_current = api.get_cache(replacement)", implementation)
        self.assertIn('"superseded-gradle-home"', implementation)
        self.assertIn('"protected_generation_ids"', implementation)
        self.assertIn("api.delete_cache(cache.cache_id)", implementation)
        self.assertIn('f"/actions/caches/{cache_id}"', implementation)

    def test_pages_actions_use_reviewed_immutable_versions(self) -> None:
        workflow = (WORKFLOWS / "pages.yml").read_text(encoding="utf-8")
        self.assertIn(
            "actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9",
            workflow,
        )
        self.assertIn(
            "actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128",
            workflow,
        )

    def test_packaged_e2e_exposes_one_stable_required_context(self) -> None:
        required = job_block("on-demand-e2e.yml", "required-gate")
        self.assertIn("name: Packaged E2E gate", required)
        self.assertIn("always()", required)
        self.assertIn("needs.runtime-policy.result", required)
        self.assertIn("needs.runtime-policy.outputs.effective", required)
        self.assertIn("needs.build.result", required)
        self.assertIn("needs.e2e.result", required)
        self.assertIn("inputs.attest_run_id == ''", required)

    def test_not_applicable_e2e_is_internal_exact_and_fail_closed(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        policy = job_block("on-demand-e2e.yml", "runtime-policy")
        build = job_block("on-demand-e2e.yml", "build")
        e2e = job_block("on-demand-e2e.yml", "e2e")
        required = job_block("on-demand-e2e.yml", "required-gate")
        pages = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        notify = job_block("on-demand-e2e.yml", "notify-version-port")

        self.assertIn("runtime_policy:", workflow)
        self.assertIn("default: full", workflow)
        self.assertIn('[[ "$REQUESTED_POLICY" == not-applicable ]]', policy)
        self.assertIn('[[ "$GITHUB_EVENT_NAME" == workflow_dispatch ]]', policy)
        self.assertIn('[[ "$GITHUB_REF_NAME" == automation/sync/* ]]', policy)
        self.assertIn("automated-version-sync", policy)
        self.assertIn("isCrossRepository", policy)
        self.assertIn("branches/master", policy)
        self.assertIn('git show "$protected_sha:scripts/ci/e2e_impact.py"', policy)
        self.assertIn("version_branches.py", policy)
        self.assertIn('[[ "${#parents[@]}" == 3 ]]', policy)
        self.assertIn(
            'git merge-base --is-ancestor "${parents[2]}" "$protected_sha"', policy
        )
        self.assertIn('if [[ "$chain_commit" == "$base_sha" ]]; then', policy)
        self.assertIn('[[ "$chain_complete" == true ]]', policy)
        self.assertIn('--base "$base_sha"', policy)
        self.assertIn('--head "$GITHUB_SHA"', policy)
        self.assertIn('[[ "$runtime_required" == false ]]', policy)
        self.assertIn(".runtime_paths == []", policy)
        self.assertIn("continue-on-error: true", policy)
        self.assertIn("Resolve failures to the full runtime policy", policy)
        self.assertIn("CANDIDATE_OUTCOME", policy)
        self.assertIn("running full Packaged E2E", policy)

        for block in (build, e2e):
            self.assertIn("needs.runtime-policy.outputs.effective == 'full'", block)
        self.assertIn('[[ "$BUILD_RESULT" == skipped ]]', required)
        self.assertIn('[[ "$E2E_RESULT" == skipped ]]', required)
        self.assertIn("not applicable", required)
        self.assertIn("inputs.runtime_policy == 'full'", pages)
        self.assertIn("--arg runtime_policy", notify)
        self.assertIn("runtime_policy:$runtime_policy", notify)
        self.assertIn("needs.runtime-policy.outputs.effective == 'full'", notify)

    def test_version_sync_classifies_and_rechecks_the_exact_port_commit(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        publish = job_block("sync-version-branches.yml", "publish")

        self.assertIn('commit-tree "$tree" -p "$WORK_HEAD_SHA" -p "$SOURCE_SHA"', propose)
        self.assertIn("-c user.name='github-actions[bot]'", propose)
        self.assertIn(
            "-c user.email='41898282+github-actions[bot]@users.noreply.github.com'",
            propose,
        )
        self.assertIn(
            '"$RUNNER_TEMP/version-port-controller/scripts/ci/e2e_impact.py"', propose
        )
        self.assertIn('--base "$TARGET_HEAD_SHA"', propose)
        self.assertIn('--head "$candidate_commit"', propose)
        self.assertIn('if [[ "$runtime_required" == false ]]; then', propose)
        self.assertIn("runtime_policy:$runtime_policy", propose)
        self.assertIn("runtime_manifest:$runtime_manifest", propose)
        self.assertIn("--arg tree \"$tree\"", propose)
        self.assertIn("tree:$tree", propose)

        self.assertIn(".runtime_manifest.schema_version == 1", publish)
        self.assertIn('test("^automation/sync/[A-Za-z0-9._/-]+$")', publish)
        self.assertIn('startswith("automation/sync/" + $target + "/")', publish)
        self.assertIn("(.tree | sha)", publish)
        self.assertIn('[[ "$tree" == "$EXPECTED_TREE" ]]', publish)
        self.assertIn("EXPECTED_RUNTIME_MANIFEST", publish)
        self.assertIn("../controller/scripts/ci/e2e_impact.py", publish)
        self.assertIn('--base "$TARGET_HEAD_SHA"', publish)
        self.assertIn('--head "$commit"', publish)
        self.assertIn('if [[ "$runtime_required" == false ]]; then', publish)
        self.assertIn('[[ "$runtime_policy" == "$EXPECTED_RUNTIME_POLICY" ]]', publish)
        self.assertIn('-f runtime_policy="$RUNTIME_POLICY"', publish)

    def test_version_port_handler_revalidates_policy_and_run_shape(self) -> None:
        inspect = job_block("handle-version-port-result.yml", "inspect")
        repair = job_block("handle-version-port-result.yml", "apply-repair")
        merge = job_block("handle-version-port-result.yml", "merge")

        self.assertIn("EXPECTED_RUNTIME_POLICY", inspect)
        self.assertIn('[[ "$GATE_RUN_ID" =~ ^[1-9][0-9]*$ ]]', inspect)
        self.assertIn('[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]', inspect)
        self.assertIn("^automation/sync/[A-Za-z0-9._/-]+$", inspect)
        self.assertIn('"$EXPECTED_CONCLUSION" == failure', inspect)
        self.assertIn("Classify packaged runtime impact", inspect)
        self.assertIn("Build immutable E2E input bundle", inspect)
        self.assertIn('endswith(" - contract scenarios")', inspect)
        self.assertIn("gh api --paginate --slurp", inspect)
        self.assertIn("[.[].jobs[]", inspect)
        self.assertIn("-f runtime_policy=full", repair)

        self.assertIn("branches/master", merge)
        self.assertIn('git show "$protected_sha:scripts/ci/e2e_impact.py"', merge)
        self.assertIn('--base "$base_sha"', merge)
        self.assertIn('--head "$head_sha"', merge)
        self.assertIn("NOTIFYING_RUNTIME_POLICY", merge)
        self.assertIn("scripts/ci/e2e_job_graph.py", merge)
        self.assertIn('--runtime-policy "$runtime_policy"', merge)
        self.assertIn('--protected-sha "$protected_sha"', merge)
        self.assertIn('--head-sha "$head_sha"', merge)
        self.assertIn('git show "$protected_sha:scripts/release/matrix.py"', merge)
        self.assertIn(
            'git show "$protected_sha:e2e/loader-bootstrap-contract.json"', merge
        )
        self.assertIn(
            '--bootstrap-contract "$controller/e2e/loader-bootstrap-contract.json"',
            merge,
        )
        self.assertIn("gh api --paginate --slurp", merge)
        self.assertNotIn("observed_policy", merge)
        self.assertIn("Packaged E2E not applicable (non-runtime port)", merge)
        self.assertIn("Verified exact-head Packaged E2E", merge)
        self.assertIn('-f runtime_policy="$runtime_policy"', merge)
        revalidate = merge.index('python3 "$controller/scripts/ci/e2e_job_graph.py"')
        publish = merge.index("publish_required_status()")
        self.assertLess(revalidate, publish)

    def test_release_status_refresh_uses_a_pr_instead_of_master_push(self) -> None:
        workflow = (WORKFLOWS / "refresh-release-status.yml").read_text(encoding="utf-8")
        build = (WORKFLOWS / "build-gate.yml").read_text(encoding="utf-8")
        self.assertIn("AUTOMATION_BRANCH: automation/refresh-release-status", workflow)
        self.assertIn("gh pr create", workflow)
        self.assertIn('HEAD:refs/heads/$AUTOMATION_BRANCH', workflow)
        self.assertIn('gh workflow run build-gate.yml --ref "$AUTOMATION_BRANCH"', workflow)
        self.assertIn('gh workflow run on-demand-e2e.yml --ref "$AUTOMATION_BRANCH"', workflow)
        self.assertIn("scripts/release/branch_readme.py", workflow)
        self.assertIn("--profile-branch master", workflow)
        self.assertNotIn("git push origin HEAD:master", workflow)
        for prefix in ("fabric-and-neoforge-*", "forge-and-fabric-*"):
            self.assertIn(prefix, build)
        for prefix in ("fabric-and-neoforge-", "forge-and-fabric-"):
            self.assertIn(f"startsWith(github.event.ref, '{prefix}')", workflow)
        self.assertNotIn("github.event.ref_type == 'branch'\n", workflow)

    def test_release_test_jobs_install_locked_pages_dependency(self) -> None:
        for workflow, job in (
            ("build-gate.yml", "build"),
            ("refresh-release-status.yml", "refresh"),
            ("sync-version-branches.yml", "validate"),
            ("handle-version-port-result.yml", "validate-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                install = block.index("scripts/pages/requirements.txt")
                tests = block.index("scripts/release/tests")
                self.assertIn("--only-binary=:all:", block)
                self.assertIn("--require-hashes", block)
                self.assertLess(install, tests)
        sync_publish = job_block("sync-version-branches.yml", "validate")
        self.assertIn(
            "--requirement controller/scripts/pages/requirements.txt",
            sync_publish,
        )

    def test_python_compilation_covers_the_entire_tooling_tree(self) -> None:
        for workflow, job in (
            ("build-gate.yml", "build"),
            ("refresh-release-status.yml", "refresh"),
            ("sync-version-branches.yml", "validate"),
            ("handle-version-port-result.yml", "validate-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertRegex(
                    block,
                    r"python3? -m compileall -q e2e scripts",
                )
                self.assertNotIn("-m py_compile", block)

        for guide in (ROOT / "CONTRIBUTING.md", ROOT / "docs" / "ai" / "WORKFLOW.md"):
            with self.subTest(guide=guide.relative_to(ROOT)):
                text = guide.read_text(encoding="utf-8")
                self.assertIn("python -m compileall -q e2e scripts", text)
                self.assertNotIn("-m py_compile", text)

    def test_build_gate_checks_the_actual_branch_readme_profile(self) -> None:
        build = job_block("build-gate.yml", "build")

        self.assertIn("Validate branch-specific README profile", build)
        self.assertIn("BASE_REF: ${{ github.base_ref }}", build)
        self.assertIn("REF_NAME: ${{ github.ref_name }}", build)
        self.assertIn("scripts/release/branch_readme.py", build)
        self.assertIn('--profile-branch "$profile_branch"', build)
        self.assertIn("--check", build)
        self.assertIn("scripts/release/workflow_guidance.py", build)
        self.assertIn("--guidance docs/ai/WORKFLOW.md", build)
        self.assertIn("node --check site/assets/site.js", build)
        self.assertIn("node --check site/assets/gallery.js", build)

    def test_ai_jobs_are_read_only_patch_producers(self) -> None:
        for workflow, job in (
            ("sync-version-branches.yml", "propose"),
            ("handle-version-port-result.yml", "propose-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertIn("contents: read", block)
                self.assertNotIn("contents: write", block)
                self.assertIn("persist-credentials: false", block)
                self.assertIn("ai_patch_policy.py", block)
                self.assertIn("actions/upload-artifact@", block)
        repair = job_block("handle-version-port-result.yml", "propose-repair")
        self.assertIn("branches/master", repair)
        self.assertIn("$RUNNER_TEMP/repair-controller/ai_patch_policy.py", repair)
        self.assertIn("scripts/ci/bounded_zip.py", repair)
        self.assertNotIn("python3 scripts/ci/ai_patch_policy.py staged", repair)

    def test_version_port_proposer_executes_only_protected_controller_scripts(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        controller = "$RUNNER_TEMP/version-port-controller"
        self.assertIn('git archive "$source_sha" | tar -x -C "$controller"', propose)
        for script in (
            "scripts/release/version_branches.py",
            "scripts/release/matrix.py",
            "scripts/release/branch_readme.py",
            "scripts/release/e2e_readme.py",
            "scripts/release/workflow_guidance.py",
            "scripts/ci/ai_patch_policy.py",
            "scripts/ci/e2e_impact.py",
            "scripts/ci/version_port_merge.py",
        ):
            with self.subTest(script=script):
                protected_path = (
                    f'$controller/{script}'
                    if script
                    in {
                        "scripts/release/version_branches.py",
                        "scripts/ci/version_port_merge.py",
                    }
                    else f'{controller}/{script}'
                )
                self.assertIn(protected_path, propose)
                self.assertNotRegex(
                    propose,
                    rf"python3 (?!\"?\$RUNNER_TEMP/version-port-controller/){re.escape(script)}",
                )

    def test_failed_repair_evidence_is_identity_bound_and_bounded(self) -> None:
        repair = job_block("handle-version-port-result.yml", "propose-repair")
        self.assertIn("head -c 2097152", repair)
        self.assertIn(".total_count", repair)
        self.assertIn(". <= 100", repair)
        self.assertIn("$items | length <= 8", repair)
        self.assertIn(".size_in_bytes <= 67108864", repair)
        self.assertIn("^sha256:[0-9a-f]{64}$", repair)
        self.assertIn(".workflow_run.id == $run_id", repair)
        self.assertIn("<= 268435456", repair)
        self.assertIn("actions/artifacts/$artifact_id/zip", repair)
        self.assertIn('stat -c %s "$archive"', repair)
        self.assertIn('sha256sum "$archive"', repair)
        self.assertIn("repair-controller/bounded_zip.py", repair)
        self.assertIn("extracted_bytes <= 536870912", repair)
        self.assertIn("extracted_entries <= 512", repair)
        self.assertNotIn("gh run download", repair)

    def test_repair_attempt_authenticates_the_exact_automation_pr(self) -> None:
        inspect = job_block("handle-version-port-result.yml", "inspect")
        reserve = inspect[inspect.index("Reserve the single repair attempt") :]
        for required in (
            "EXPECTED_SHA",
            "--limit 2",
            "headRefOid",
            "baseRefOid",
            "isCrossRepository",
            "automated-version-sync",
            "protected-version-branches.py",
            "--exclude master --target",
            '[[ "$remote_base_sha" == "$base_sha" ]]',
        ):
            with self.subTest(required=required):
                self.assertIn(required, reserve)

    def test_packaged_runtime_dependency_closure_is_hash_locked(self) -> None:
        action = (
            COMPOSITE_ACTIONS / "run-packaged-e2e" / "action.yml"
        ).read_text(encoding="utf-8")
        requirements = (ROOT / "e2e" / "requirements.txt").read_text(
            encoding="utf-8"
        )
        self.assertIn("--only-binary=:all:", action)
        self.assertIn("--require-hashes", action)
        self.assertIn("e2e/requirements.txt", action)
        self.assertEqual(requirements.count("=="), 7)
        self.assertGreaterEqual(requirements.count("--hash=sha256:"), 7)

    def test_read_only_port_uses_the_protected_merge_controller(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        controller = '$controller/scripts/ci/version_port_merge.py'

        self.assertIn(controller, propose)
        self.assertIn('--work-head "$work_head_sha"', propose)
        self.assertIn('--source "$source_sha"', propose)
        self.assertIn("--mode prepare", propose)
        self.assertIn("version-port-merge-evidence.json", propose)
        self.assertNotIn("git config user.", propose)
        self.assertNotIn("git merge --no-ff --no-commit", propose)

    def test_version_sync_partitions_protected_conflicts_before_ai(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        validate = job_block("sync-version-branches.yml", "validate")
        publish = job_block("sync-version-branches.yml", "publish")

        compare_ai_conflicts = (
            'diff -u "$RUNNER_TEMP/conflicted-paths.txt" '
            '"$RUNNER_TEMP/current-conflicts.txt"'
        )
        enforce_worktree = (
            'version-port-controller/scripts/ci/ai_patch_policy.py" worktree'
        )
        self.assertIn("scripts/ci/version_port_merge.py", propose)
        self.assertIn(".protected_resolutions", propose)
        self.assertIn(".mechanical_index.sha256", propose)
        self.assertIn("steps.merge.outputs.ai_conflicted == 'true'", propose)
        self.assertIn("steps.merge.outputs.ai_conflicts", propose)
        self.assertIn(compare_ai_conflicts, propose)
        self.assertIn(enforce_worktree, propose)
        self.assertIn(
            '--allowed-paths "$RUNNER_TEMP/conflicted-paths.txt"',
            propose,
        )
        self.assertLess(
            propose.index(compare_ai_conflicts), propose.index(enforce_worktree)
        )
        for block in (validate, publish):
            self.assertIn("scripts/ci/version_port_merge.py", block)
            self.assertIn(".ai_conflicts", block)
            self.assertIn(".merge_evidence", block)
            self.assertIn("recomputed-merge-evidence.json", block)
            self.assertIn('--candidate-index "$candidate_index"', block)
            self.assertIn('--candidate-tree "$candidate_tree"', block)
            self.assertIn("--mode conflict --paths-file", block)
        self.assertEqual(validate.count("--mode conflict"), 1)
        self.assertEqual(publish.count("--mode conflict"), 1)

    def test_version_sync_reconstructs_untrusted_patches_in_an_alternate_index(self) -> None:
        validate = job_block("sync-version-branches.yml", "validate")
        publish = job_block("sync-version-branches.yml", "publish")

        for block in (validate, publish):
            with self.subTest(job=block.splitlines()[0].strip()):
                self.assertIn('GIT_INDEX_FILE="$candidate_index" git read-tree', block)
                self.assertIn('GIT_INDEX_FILE="$candidate_index" git apply --cached', block)
                self.assertNotIn("git apply --index", block)
                self.assertIn(
                    'candidate_tree="$(GIT_INDEX_FILE="$candidate_index" git write-tree)"',
                    block,
                )
                self.assertIn('[[ "$candidate_tree" == "$EXPECTED_TREE" ]]', block)
                self.assertIn('--candidate-index "$candidate_index"', block)
                self.assertIn('--candidate-tree "$candidate_tree"', block)
                self.assertIn("scripts/ci/ai_patch_policy.py staged --mode port", block)
        self.assertIn('[[ "$validated_tree" == "$candidate_tree" ]]', validate)
        self.assertIn('[[ "$validated_tree" == "$EXPECTED_TREE" ]]', validate)
        self.assertIn('[[ "$tree" == "$candidate_tree" ]]', publish)
        self.assertIn('[[ "$tree" == "$EXPECTED_TREE" ]]', publish)

    def test_version_sync_renders_and_revalidates_target_readme(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        publish = job_block("sync-version-branches.yml", "publish")

        self.assertIn("--normalize-e2e-policy", propose)
        self.assertIn("--write", propose)
        self.assertLess(
            propose.index("--normalize-e2e-policy"),
            propose.index("scripts/release/branch_readme.py"),
        )
        self.assertIn("scripts/release/e2e_readme.py", propose)
        self.assertIn("scripts/release/workflow_guidance.py", propose)
        self.assertIn("--contract e2e/scenario-contract.json", propose)
        self.assertIn("--readme e2e/README.md", propose)
        self.assertIn(
            "git add -- README.md docs/ai/WORKFLOW.md e2e/README.md",
            propose,
        )
        self.assertEqual(propose.count("scripts/release/e2e_readme.py"), 2)
        self.assertIn("--write", propose)
        self.assertIn("--check", propose)
        self.assertIn("scripts/release/branch_readme.py", propose)
        self.assertIn('--profile-branch "$TARGET_BRANCH"', propose)
        self.assertIn("--bootstrap", propose)
        self.assertIn("git add -- README.md", propose)
        self.assertIn("scripts/release/branch_readme.py", publish)
        self.assertIn("../controller/scripts/release/matrix.py", publish)
        self.assertIn("../controller/scripts/release/e2e_readme.py", publish)
        self.assertIn("../controller/scripts/release/workflow_guidance.py", publish)
        self.assertIn("scripts/release/e2e_readme.py \\", publish)
        self.assertIn("--contract e2e/scenario-contract.json", publish)
        self.assertIn("--readme e2e/README.md", publish)
        self.assertIn("--normalize-e2e-policy", publish)
        self.assertIn("--write > /dev/null", publish)
        self.assertIn(
            "git add -- README.md docs/ai/WORKFLOW.md e2e/README.md", publish
        )
        self.assertIn('--profile-branch "$TARGET_BRANCH"', publish)
        self.assertLess(
            publish.index('GIT_INDEX_FILE="$candidate_index" git apply --cached'),
            publish.index("--normalize-e2e-policy"),
        )
        self.assertLess(
            publish.index("--normalize-e2e-policy"),
            publish.index("scripts/release/branch_readme.py"),
        )

    def test_port_publisher_requires_a_complete_proposal(self) -> None:
        publish = job_block("sync-version-branches.yml", "publish")
        self.assertIn("needs.propose.result != 'cancelled'", publish)
        self.assertIn("needs.validate.result != 'cancelled'", publish)
        self.assertIn("Require this target's own validate leg", publish)
        self.assertIn('[[ "$validate_result" == success ]]', publish)
        self.assertIn("Download the immutable validated proposal", publish)

    def test_version_sync_accepts_only_master_as_its_source(self) -> None:
        discover = job_block("sync-version-branches.yml", "discover")
        self.assertIn('[[ "$SOURCE_REF" == refs/heads/master ]]', discover)

    def test_version_port_merge_revalidates_the_exact_pr(self) -> None:
        merge = job_block("handle-version-port-result.yml", "merge")
        for required in (
            "headRefOid",
            "baseRefOid",
            "automated-version-sync",
            'git merge-base --is-ancestor "$base_sha" "$head_sha"',
            '--match-head-commit "$head_sha"',
            "--limit 100",
            'git merge-base --is-ancestor "$target_sha" FETCH_HEAD',
        ):
            with self.subTest(required=required):
                self.assertIn(required, merge)

    def test_e2e_bundle_reuse_authenticates_the_exact_head_build_gate_run(self) -> None:
        gate = job_block("build-gate.yml", "build")
        build = job_block("on-demand-e2e.yml", "build")

        self.assertIn("name: staged-release-bundle", gate)
        self.assertIn("retention-days: 1", gate)
        self.assertIn("head_sha=$GITHUB_SHA", build)
        self.assertIn('.path == ".github/workflows/build-gate.yml"', build)
        self.assertIn(".head_repository.full_name == $repository", build)
        self.assertIn("--verify-staged", build)
        self.assertIn("steps.reuse.outputs.reused != 'true'", build)

    def test_version_port_merge_bridges_verified_runs_to_required_statuses(self) -> None:
        merge = job_block("handle-version-port-result.yml", "merge")
        governance = json.loads(
            (ROOT / "release" / "github-governance.json").read_text(encoding="utf-8")
        )

        self.assertIn("statuses: write", merge)
        self.assertIn('repos/$GITHUB_REPOSITORY/statuses/$head_sha', merge)
        self.assertIn("$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$run_id", merge)
        for context in governance["required_checks"]:
            with self.subTest(context=context):
                self.assertIn(f'"{context}"', merge)

        revalidation = merge.index('git merge-base --is-ancestor "$base_sha" "$head_sha"')
        publish = merge.index("publish_required_status()")
        merge_pr = merge.index('gh pr merge "$pr_number"')
        self.assertLess(revalidation, publish)
        self.assertLess(publish, merge_pr)

    def test_credentialed_writers_do_not_receive_claude_credentials(self) -> None:
        for workflow, job in (
            ("sync-version-branches.yml", "publish"),
            ("handle-version-port-result.yml", "apply-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertIn("contents: write", block)
                self.assertIn("ai_patch_policy.py", block)
                self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", block)
                self.assertNotIn("node_modules/.bin/claude", block)

    def test_version_port_validation_never_persists_writer_credentials(self) -> None:
        sync_validate = job_block("sync-version-branches.yml", "validate")
        sync_writer = job_block("sync-version-branches.yml", "publish")
        repair_validate = job_block("handle-version-port-result.yml", "validate-repair")
        repair_writer = job_block("handle-version-port-result.yml", "apply-repair")

        for workflow, block in (
            ("sync-version-branches.yml", sync_validate),
            ("handle-version-port-result.yml", repair_validate),
        ):
            with self.subTest(workflow=workflow):
                self.assertIn("contents: read", block)
                self.assertNotIn("contents: write", block)
                self.assertIn("persist-credentials: false", block)
                self.assertNotIn("persist-credentials: true", block)
                self.assertIn("scripts/release/tests", block)
                self.assertNotIn("gh auth setup-git", block)
                self.assertNotIn("git push", block)
                if "GH_TOKEN:" in block:
                    self.assertLess(
                        block.index("GH_TOKEN:"), block.index("scripts/release/tests")
                    )
                    self.assertNotIn(
                        "GH_TOKEN:", block[block.index("scripts/release/tests") :]
                    )

        for workflow, block in (
            ("sync-version-branches.yml", sync_writer),
            ("handle-version-port-result.yml", repair_writer),
        ):
            with self.subTest(workflow=workflow, boundary="writer"):
                self.assertIn("contents: write", block)
                self.assertIn("persist-credentials: false", block)
                self.assertNotIn("persist-credentials: true", block)
                self.assertIn("gh auth setup-git", block)
                self.assertIn("git push --no-verify", block)
                self.assertNotIn("scripts/release/tests", block)
                self.assertNotIn("unittest", block)
                self.assertNotIn("compileall", block)
                self.assertNotIn("setup-python", block)

        self.assertIn("../controller/scripts/ci/ai_patch_policy.py", sync_validate)
        self.assertIn('git diff --exit-code', sync_validate)
        self.assertIn('[[ "$validated_tree" == "$candidate_tree" ]]', sync_validate)
        self.assertIn('[[ "$validated_tree" == "$EXPECTED_TREE" ]]', sync_validate)
        self.assertIn('[[ "$tree" == "$EXPECTED_TREE" ]]', sync_writer)
        self.assertIn("commit-tree", sync_writer)
        self.assertIn("-c user.name='github-actions[bot]'", sync_writer)
        self.assertIn(
            "-c user.email='41898282+github-actions[bot]@users.noreply.github.com'",
            sync_writer,
        )
        self.assertIn("$RUNNER_TEMP/protected-ai-patch-policy.py", repair_validate)
        self.assertIn("protected-pages-requirements.txt", repair_validate)
        self.assertIn('[[ "$(git write-tree)" == "$validated_tree" ]]', repair_validate)
        self.assertIn("$RUNNER_TEMP/writer-ai-patch-policy.py", repair_writer)
        self.assertIn("git commit-tree", repair_writer)
        self.assertNotIn("git commit -m", repair_writer)

    def test_claude_install_is_exact_and_integrity_locked(self) -> None:
        package = json.loads(
            (ROOT / ".github" / "claude" / "package.json").read_text(encoding="utf-8")
        )
        lock = json.loads(
            (ROOT / ".github" / "claude" / "package-lock.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(package["dependencies"]["@anthropic-ai/claude-code"], "2.1.220")
        locked = lock["packages"]["node_modules/@anthropic-ai/claude-code"]
        self.assertEqual(locked["version"], "2.1.220")
        self.assertTrue(locked["integrity"].startswith("sha512-"))
        for workflow in WORKFLOWS.glob("*.yml"):
            self.assertNotIn("npm install -g @anthropic-ai/claude-code", workflow.read_text())

        for workflow, job in (
            ("sync-version-branches.yml", "propose"),
            ("handle-version-port-result.yml", "propose-repair"),
        ):
            with self.subTest(workflow=workflow, job=job):
                block = job_block(workflow, job)
                self.assertIn(":.github/claude/package.json", block)
                self.assertIn(":.github/claude/package-lock.json", block)
                self.assertIn("npm ci --ignore-scripts", block)
                self.assertIn(
                    "node node_modules/@anthropic-ai/claude-code/install.cjs",
                    block,
                )
                self.assertIn("node_modules/.bin/claude --version", block)
                self.assertNotIn("cp .github/claude/package.json", block)
        sync = job_block("sync-version-branches.yml", "propose")
        repair = job_block("handle-version-port-result.yml", "propose-repair")
        visual = job_block("visual-review.yml", "review")
        self.assertIn("TRUSTED_SHA: ${{ github.sha }}", sync)
        self.assertIn("branches/master", repair)
        self.assertIn("ref: ${{ needs.authenticate.outputs.implementation_sha }}", visual)
        self.assertIn("npm ci --ignore-scripts", visual)
        self.assertIn(
            "node node_modules/@anthropic-ai/claude-code/install.cjs", visual
        )
        self.assertIn("node_modules/.bin/claude --version", visual)

    def test_marketplace_jobs_receive_only_the_selected_secret(self) -> None:
        workflow = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        self.assertEqual(
            workflow.count(
                "MODRINTH_TOKEN: ${{ matrix.marketplace == 'modrinth' "
                "&& secrets.MODRINTH_TOKEN || '' }}"
            ),
            2,
        )
        # CurseForge reconciliation reads only unauthenticated first-party endpoints, so the
        # upload step is the single place that may see the token at all.
        self.assertNotIn("CURSEFORGE_TOKEN: ", workflow)
        self.assertEqual(
            workflow.count("curseforge-token: ${{ secrets.CURSEFORGE_TOKEN }}"), 1
        )

    def test_curseforge_upload_is_never_retried_inside_the_action(self) -> None:
        workflow = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")

        def publish_step(marketplace: str) -> str:
            tail = workflow.split(
                f"Publish exact verified artifact to {marketplace}", 1
            )[1]
            return tail.split("- name: ", 1)[0]

        # CurseForge approves asynchronously and deduplicates only against an approved file, so a
        # retried upload can publish a second live copy that no pre-publish gate can prevent.
        self.assertIn("retry-attempts: 1", publish_step("CurseForge"))
        # Modrinth rejects a duplicate synchronously, so its retries stay safe.
        self.assertIn("retry-attempts: 3", publish_step("Modrinth"))


if __name__ == "__main__":
    unittest.main()
