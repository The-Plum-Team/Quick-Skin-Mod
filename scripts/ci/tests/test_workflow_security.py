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
                    if workflow.name == "visual-review-drain.yml":
                        runner = (
                            ROOT / "e2e" / "visual_review_runner.py"
                        ).read_text(encoding="utf-8")
                        self.assertIn("visual_review_runner.py", block)
                        self.assertIn("unset GH_TOKEN GITHUB_TOKEN", block)
                        self.assertNotIn("Edit", block)
                        self.assertNotIn('"Write(', block)
                        self.assertIn('"--safe-mode"', runner)
                        self.assertIn('"--no-session-persistence"', runner)
                        self.assertIn('"--permission-mode"', runner)
                        self.assertIn('"dontAsk"', runner)
                        self.assertIn('"--tools"', runner)
                        self.assertIn('"Read"', runner)
                        self.assertIn("Read(./review-input/images/**)", runner)
                        self.assertNotIn('"Bash"', runner)
                        continue
                    self.assertIn("--safe-mode", block)
                    self.assertIn("--no-session-persistence", block)
                    self.assertIn("--permission-mode dontAsk", block)
                    self.assertNotIn("--permission-mode acceptEdits", block)
                    self.assertNotRegex(block, r'--tools "[^"]*Bash')
                    self.assertNotIn("--allowedTools Read", block)
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

    def test_upload_artifact_retention_is_bounded_with_named_exceptions(
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

        retention_overrides = {
            (
                "pages.yml",
                "Roll the protected evidence cache forward",
                "${{ steps.cache.outputs.name }}",
            ): "90",
            (
                "release.yml",
                "Upload immutable release bundle",
                "release-${{ steps.release.outputs.release_id }}",
            ): "90",
            (
                "on-demand-e2e.yml",
                "Upload stable public evidence for this release branch",
                "pages-e2e-${{ github.ref_name }}",
            ): "${{ steps.identity.outputs.reference_retention_days }}",
            (
                "visual-review.yml",
                "Upload only the curated review input",
                "${{ steps.identity.outputs.artifact_name }}",
            ): "7",
            (
                "visual-review-drain.yml",
                "Upload the exact semantic anchor certificate",
                "${{ steps.certify.outputs.artifact_name }}",
            ): "7",
            (
                "visual-review-drain.yml",
                "Upload the protected exact-policy verdict cache",
                "${{ steps.publish-verdict-cache.outputs.artifact_name }}",
            ): "7",
            (
                "visual-review-drain.yml",
                "Upload the sanitized generation block marker",
                "${{ steps.wave-block.outputs.artifact_name }}",
            ): "7",
        }
        observed_overrides: set[tuple[str, str, str]] = set()
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
                expected_retention = retention_overrides.get(identity, "1")
                self.assertEqual(retention_values[0], expected_retention)
                if identity in retention_overrides:
                    observed_overrides.add(identity)
        self.assertEqual(observed_overrides, set(retention_overrides))

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

    def test_visual_review_is_queued_bounded_and_certifies_the_anchor(self) -> None:
        e2e = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        prepare_workflow = (WORKFLOWS / "visual-review.yml").read_text(
            encoding="utf-8"
        )
        drain_workflow = (WORKFLOWS / "visual-review-drain.yml").read_text(
            encoding="utf-8"
        )
        triage_prompt = (ROOT / "e2e" / "visual_review_prompt.md").read_text(
            encoding="utf-8"
        )
        verify_prompt = (
            ROOT / "e2e" / "visual_review_verify_prompt.md"
        ).read_text(encoding="utf-8")
        semantic_prompt = (
            ROOT / "e2e" / "visual_review_semantic_prompt.md"
        ).read_text(encoding="utf-8")
        semantic_verify_prompt = (
            ROOT / "e2e" / "visual_review_semantic_verify_prompt.md"
        ).read_text(encoding="utf-8")
        runner = (ROOT / "e2e" / "visual_review_runner.py").read_text(
            encoding="utf-8"
        )
        authenticate = job_block("visual-review.yml", "authenticate")
        curate = job_block("visual-review.yml", "curate")
        request = job_block("visual-review.yml", "request-drain")
        select = job_block("visual-review-drain.yml", "select")
        dispatch_selected = job_block(
            "visual-review-drain.yml", "dispatch-selected"
        )
        review = job_block("visual-review-drain.yml", "review")
        cleanup = job_block("visual-review-drain.yml", "cleanup")
        release_anchor = job_block("visual-review-drain.yml", "release-anchor")
        continuation = job_block("visual-review-drain.yml", "continue")
        pages = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        notify = job_block("on-demand-e2e.yml", "notify-version-port")

        self.assertNotRegex(e2e, r"(?m)^  visual-review:")
        self.assertIn("continue-on-error: true", pages)
        self.assertIn("visual-review-requested", notify)
        self.assertIn("continue-on-error: true", notify)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", notify)
        self.assertIn("- required-gate", notify)
        self.assertIn("- prepare-pages-evidence", notify)
        self.assertIn("permissions: {}", prepare_workflow)
        self.assertIn("workflows:\n      - Packaged E2E", prepare_workflow)
        prepare_jobs = prepare_workflow.split("\njobs:\n", 1)[1]
        drain_header = drain_workflow.split("\njobs:\n", 1)[0]
        drain_jobs = drain_workflow.split("\njobs:\n", 1)[1]
        self.assertEqual(
            {"authenticate", "curate", "request-drain"},
            set(re.findall(r"(?m)^  ([a-z0-9-]+):\n", prepare_jobs)),
        )
        self.assertEqual(
            {
                "select",
                "dispatch-selected",
                "review",
                "cleanup",
                "release-anchor",
                "continue",
            },
            set(re.findall(r"(?m)^  ([a-z0-9-]+):\n", drain_jobs)),
        )
        self.assertIn("visual-review-drain-requested", prepare_workflow)
        self.assertIn("visual-review-drain-requested", drain_workflow)
        self.assertIn('cron: "17,47 * * * *"', drain_workflow)
        self.assertIn("quick-skin-visual-review-${{", drain_header)
        self.assertIn("github.event.client_payload.artifact_id", drain_header)
        self.assertIn("'queue-sweep'", drain_header)
        self.assertIn("cancel-in-progress: false", drain_header)
        self.assertNotIn("concurrency:", review)
        self.assertIn("scripts/ci/visual_review_queue.py", select)
        self.assertIn("--requested-artifact-id", select)
        self.assertIn("artifact_id=$REQUESTED_ARTIFACT_ID", select)
        self.assertIn("artifact_name=$REQUESTED_ARTIFACT_NAME", select)
        self.assertIn("generation_sha=$REQUESTED_GENERATION_SHA", select)
        self.assertIn("needs.select.outputs.direct == 'false'", dispatch_selected)
        self.assertIn("visual-review-drain-requested", dispatch_selected)
        self.assertIn("artifact_id:$artifact_id", dispatch_selected)
        self.assertIn("artifact_name:$artifact_name", dispatch_selected)
        self.assertIn("generation_sha:$generation_sha", dispatch_selected)
        self.assertIn("contents: write", dispatch_selected)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", dispatch_selected)
        self.assertNotIn("actions/checkout@", dispatch_selected)
        self.assertIn("needs.select.outputs.direct == 'true'", review)

        self.assertIn('name == "Packaged E2E gate"', authenticate)
        self.assertIn('endswith(" - contract scenarios")', authenticate)
        self.assertIn("pull-requests: read", authenticate)
        self.assertIn('commits/$source_sha/pulls', authenticate)
        self.assertIn('pulls/$source_pr_number/files?per_page=100', authenticate)
        self.assertIn('.user.login == "github-actions[bot]"', authenticate)
        self.assertIn('.changed_files', authenticate)
        self.assertIn('scripts/ci/visual_review_impact.py', authenticate)
        self.assertIn('source_pr_base="$(jq -er .base.ref', authenticate)
        self.assertIn('source_pr_merged="$(jq -er', authenticate)
        self.assertIn('Deferring semantic anchor review until PR', authenticate)
        self.assertIn('&& "$source_pr_base" != "$anchor_branch"', authenticate)
        self.assertIn('ref: ${{ github.sha }}', authenticate)
        self.assertIn('persist-credentials: false', authenticate)
        self.assertIn('Ignoring infrastructure-only visual review sync PR', authenticate)
        self.assertIn("visual-review-input-$source_run_id", authenticate)
        self.assertIn('startswith($input_name + "-")', authenticate)
        self.assertIn("visual-review-$source_run_id", authenticate)
        self.assertIn("visual-review-drain.yml", authenticate)
        self.assertNotIn("implementation_sha", authenticate)
        self.assertNotIn("branches/master", authenticate)
        self.assertIn("actions/runs/$source_run_id/artifacts", authenticate)
        self.assertIn("artifact_inventory", authenticate)

        self.assertIn("git fetch --no-tags origin \"$SOURCE_SHA\"", curate)
        self.assertIn("actions/artifacts/$artifact_id", curate)
        self.assertIn("scripts/ci/bounded_zip.py", curate)
        self.assertIn("scripts/ci/e2e_job_graph.py", curate)
        self.assertNotIn("path: source", curate)
        self.assertNotIn("git -C source", curate)
        self.assertIn('git show "$SOURCE_SHA:e2e/scenario-contract.json"', curate)
        self.assertIn('git show "$SOURCE_SHA:release/release-matrix.json"', curate)
        self.assertIn("--reference-identity", curate)
        self.assertIn("scripts/pages/select_artifact.py", curate)
        self.assertIn("--require-raw", curate)
        self.assertIn("for attempt in {1..90}", curate)
        self.assertIn(
            'git show "${candidate_reference_sha}:e2e/scenario-contract.json"',
            curate,
        )
        self.assertIn(
            'reference_contract_sha256" == "$master_contract_sha256', curate
        )
        self.assertIn("visual reference did not reach protected", curate)
        self.assertIn("sleep 5", curate)
        self.assertIn("--kind raw", curate)
        self.assertNotIn("scripts/pages/evidence.py compact", curate)
        self.assertIn("--reference-evidence-root \"$reference_selected\"", curate)
        self.assertIn("--semantic-anchor", curate)
        self.assertIn("review_mode=anchor-semantic", curate)
        self.assertIn("visual_reference=null", curate)
        self.assertIn("--all", curate)
        self.assertIn("--validate-row-json", curate)
        self.assertIn("--curate-output", curate)
        self.assertIn("evidence_kind:\"raw-png\"", curate)
        self.assertIn("schema_version:4", curate)
        self.assertIn("retention-days: 7", curate)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", curate)
        self.assertNotIn("claude-code", curate)
        self.assertIn("ref: ${{ github.sha }}", curate)
        self.assertIn("IMPLEMENTATION_SHA: ${{ github.sha }}", curate)
        self.assertIn('[[ "$IMPLEMENTATION_SHA" == "$GITHUB_SHA" ]]', curate)
        self.assertNotIn("needs.authenticate.outputs.implementation_sha", prepare_workflow)
        self.assertIn("persist-credentials: false", curate)
        self.assertIn("contents: write", request)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", request)

        self.assertIn("actions/artifacts/$ARTIFACT_ID", review)
        self.assertIn("actions: write", review)
        self.assertIn("scripts/ci/bounded_zip.py", review)
        self.assertIn("--max-entries 520", review)
        self.assertIn("visual-review-capsule", review)
        self.assertIn("ref: ${{ needs.select.outputs.implementation_sha }}", review)
        self.assertIn("persist-credentials: false", review)
        self.assertNotIn("actions/download-artifact@", review)
        self.assertIn("schema_version == 4", review)
        self.assertIn('evidence_kind == "raw-png"', review)
        self.assertIn("CLAUDE_CODE_OAUTH_TOKEN", review)
        self.assertIn("visual_review_runner.py", review)
        self.assertIn("--review-mode \"$review_mode\"", review)
        self.assertIn("visual_review_semantic_prompt.md", review)
        self.assertIn("visual_review_semantic_verify_prompt.md", review)
        self.assertNotRegex(
            drain_workflow,
            r"(?m)^\s*'[^'\n]*\\$",
            "a backslash inside a multiline single-quoted jq filter is literal",
        )
        self.assertIn("--triage-model claude-sonnet-5", review)
        self.assertIn("--verify-model claude-opus-5", review)
        self.assertIn("--triage-chunk-size 8", review)
        self.assertIn("--verify-chunk-size 4", review)
        self.assertIn("--max-parallel-calls 32", review)
        self.assertIn("--call-spacing-seconds 0", review)
        self.assertIn("--model-attempts 3", review)
        self.assertIn("visual_review_cache.py", review)
        self.assertIn("--completion-state visual-review-completion.json", review)
        self.assertIn("--allow-blocking-partial", review)
        self.assertIn("visual-review-verdict-cache-$policy_sha256", review)
        self.assertIn("visual_review_cache.py\" combine", review)
        self.assertIn("artifact_ids=", review)
        self.assertIn("Retire the consumed exact-policy verdict cache shards", review)
        self.assertIn("--max-entries 1", review)
        self.assertIn("steps.verdict-cache-artifact.outputs.artifact-id", review)
        self.assertIn("Retire superseded caches for obsolete review policies", review)
        self.assertIn(
            "visual-review-wave-block-$GENERATION_SHA", review
        )
        self.assertIn("Upload the sanitized generation block marker", review)
        self.assertIn("Cancel sibling drains after the durable block exists", review)
        self.assertIn("actions/runs/$sibling_id/cancel", review)
        self.assertIn("steps.wave-block-artifact.outputs.artifact-id", review)
        self.assertIn("visual-review-failure.json", review)
        self.assertIn("visual-review-attempt-${{ needs.select.outputs.source_run_id }}", review)
        self.assertIn("cooling=true", review)
        self.assertNotIn("visual-review-report.raw.json", drain_workflow)
        self.assertNotIn("e2e-out", review)
        self.assertIn("git -C \"$GITHUB_WORKSPACE\" diff --exit-code", review)
        self.assertIn("--normalized-report visual-review-report.json", review)
        self.assertIn("visual-review-report.json", review)
        self.assertIn("scripts/ci/visual_anchor_certification.py", review)
        self.assertIn("visual-anchor-certification-$master_source_sha", review)
        self.assertIn('commits/$anchor_source_sha/pulls', review)
        self.assertIn('.user.login == "github-actions[bot]"', review)
        self.assertIn('[[ "${source_commit[2]}" == "$master_source_sha" ]]', review)
        self.assertIn("steps.check.outcome == 'success'", review)
        self.assertNotIn("review-work", review[review.index("Upload the source-bound"):])
        self.assertIn("actions: write", cleanup)
        self.assertNotIn("contents:", cleanup)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", cleanup)
        self.assertNotIn("actions/checkout@", cleanup)
        self.assertIn("gh api --method DELETE", cleanup)
        self.assertIn("visual-review-metadata", cleanup)
        self.assertIn("visual-review-delete", cleanup)
        self.assertEqual(cleanup.count("(HTTP 404)"), 2)
        self.assertIn("contents: write", release_anchor)
        self.assertIn("actions: read", release_anchor)
        self.assertIn("visual-anchor-certified", release_anchor)
        self.assertIn("actions/artifacts/$ARTIFACT_ID", release_anchor)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", release_anchor)
        self.assertNotIn("actions/checkout@", release_anchor)
        self.assertIn("contents: write", continuation)
        self.assertIn("needs.review.outputs.wave_blocked != 'true'", continuation)

        self.assertIn("lossless Minecraft 1.20.1", triage_prompt)
        self.assertIn("becoming softer or blurred", triage_prompt)
        self.assertIn("independent second-pass", verify_prompt)
        for prompt in (triage_prompt, verify_prompt):
            self.assertIn("any intact Vanilla default", prompt)
            self.assertIn("This exception never applies when the expectation names", prompt)
            self.assertIn("a custom skin or cape", prompt)
        self.assertIn("deliberately no reference image", semantic_prompt)
        self.assertIn("Do not compare loaders", semantic_prompt)
        self.assertIn("matches_reference=null", semantic_verify_prompt)
        self.assertIn("DEFAULT_TRIAGE_CHUNK_SIZE = 8", runner)
        self.assertIn("DEFAULT_VERIFY_CHUNK_SIZE = 4", runner)
        self.assertIn("DEFAULT_MAX_PARALLEL_CALLS = 16", runner)
        self.assertIn("ThreadPoolExecutor", runner)
        self.assertIn("path\"] == item[\"reference_path", runner)
        self.assertIn("TRIAGE_CONFIDENCE", (
            ROOT / "e2e" / "check_visual_review.py"
        ).read_text(encoding="utf-8"))

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
        self.assertIn(
            "retention-days: ${{ steps.identity.outputs.reference_retention_days }}",
            handoff,
        )
        self.assertIn("--reference-retention-days", handoff)
        self.assertIn("--preserve-raw-branch", rotate)
        self.assertIn('expected_names = {"github-pages"}', rotator)
        self.assertIn(
            'f"collected-pages-{generation.branch}" for generation in generations',
            rotator,
        )
        self.assertIn("for artifact in (*old_caches, *handoffs):", rotator)
        self.assertIn("select_old_handoffs(", rotator)
        self.assertIn("lossless visual reference changed", rotator)
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
        self.assertIn('&& "$TARGET_BRANCH" != "$anchor_branch"', propose)
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
        self.assertIn('&& "$TARGET_BRANCH" != "$anchor_branch"', publish)
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
        self.assertIn('git show "$protected_sha:release/release-matrix.json"', merge)
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
        self.assertIn('&& "$target_branch" != "$anchor_branch"', merge)
        self.assertIn('merged-anchor-visual-review.json', merge)
        self.assertIn('event_type:"visual-review-requested"', merge)
        self.assertIn('for attempt in {1..5}', merge)
        anchor_wake = merge.index('merged-anchor-visual-review.json')
        self.assertLess(merge.index('gh pr merge "$pr_number"'), anchor_wake)
        self.assertLess(merge.index('gh workflow run on-demand-e2e.yml'), anchor_wake)
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
        self.assertIn("jobs?filter=latest&per_page=100", publish)
        self.assertIn("for attempt in {1..12}", publish)
        self.assertIn("sleep 5", publish)
        self.assertIn("validate_result=api-error", publish)
        self.assertIn('[[ "$validate_result" == success ]]', publish)
        self.assertIn("Download the immutable validated proposal", publish)

    def test_version_sync_accepts_only_master_as_its_source(self) -> None:
        discover = job_block("sync-version-branches.yml", "discover")
        workflow = (WORKFLOWS / "sync-version-branches.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('[[ "$SOURCE_REF" == refs/heads/master ]]', discover)
        self.assertIn("visual-anchor-certified", workflow)
        self.assertNotIn("PUSH_BEFORE", discover)
        self.assertNotIn("scripts/ci/e2e_impact.py", discover)
        self.assertGreaterEqual(discover.count('--target "$anchor_branch"'), 3)
        self.assertIn('--exclude "$anchor_branch"', discover)
        self.assertIn("scripts/ci/visual_anchor_certification.py verify", discover)
        self.assertIn("actions/artifacts/$PAYLOAD_ARTIFACT_ID", discover)
        self.assertIn('.path == ".github/workflows/visual-review-drain.yml"', discover)
        self.assertIn('[[ "${source_commit[2]}" == "$GITHUB_SHA" ]]', discover)
        self.assertIn('branches/$anchor_branch', discover)

    def test_automatic_generation_forces_full_anchor_before_fanout(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        validate = job_block("sync-version-branches.yml", "validate")
        publish = job_block("sync-version-branches.yml", "publish")
        merge = job_block("handle-version-port-result.yml", "merge")

        for block in (propose, validate, publish):
            with self.subTest(boundary=block.splitlines()[0]):
                self.assertIn('["fabric", "forge"]', block)
                self.assertIn('&& "$TARGET_BRANCH" != "$anchor_branch"', block)
                self.assertIn("runtime_policy=full", block)
        self.assertIn('["fabric", "forge"]', merge)
        self.assertIn('&& "$target_branch" != "$anchor_branch"', merge)

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
        visual = job_block("visual-review-drain.yml", "review")
        self.assertIn("TRUSTED_SHA: ${{ github.sha }}", sync)
        self.assertIn("branches/master", repair)
        self.assertIn("ref: ${{ needs.select.outputs.implementation_sha }}", visual)
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
