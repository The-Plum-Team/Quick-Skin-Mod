from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
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


def step_script(workflow: str, job: str, step: str) -> str:
    block = job_block(workflow, job)
    step_marker = f"      - name: {step}\n"
    step_start = block.index(step_marker)
    run_marker = "        run: |\n"
    script_start = block.index(run_marker, step_start) + len(run_marker)
    script_lines: list[str] = []
    for line in block[script_start:].splitlines():
        if line.startswith("          "):
            script_lines.append(line[10:])
        elif not line:
            script_lines.append(line)
        else:
            break
    return "\n".join(script_lines)


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
    def test_confirmed_visual_defects_stop_shared_generations_without_blocking_prs(self):
        script = step_script("visual-review-drain.yml", "review",
                             "Create a sanitized generation block after a confirmed defect")
        generation, other = "a" * 40, "b" * 40
        shared = {"schema_version": 8, "source_branch": "master", "source_sha": generation,
                  "master_source_sha": generation, "implementation_sha": generation, "source_run_id": 123}
        cases = (
            ("shared full", {}, "blocking-partial", [{"defect": True}], True, False),
            ("shared selected", {"schema_version": 7}, "blocking-partial", [{"defect": True}], True, False),
            ("legacy wave", {"schema_version": 5, "source_branch": "automation/sync/example/123"},
             "blocking-partial", [{"defect": True}], True, False),
            ("advisory PR", {"source_branch": "feature/example"}, "blocking-partial", [{"defect": True}], False, False),
            ("complete review", {}, "complete", [{"defect": False}], False, False),
            ("wrong shared schema", {"schema_version": 5}, "blocking-partial", [{"defect": True}], False, True),
            ("wrong source", {"source_sha": other}, "blocking-partial", [{"defect": True}], False, True),
            ("wrong implementation", {"implementation_sha": other}, "blocking-partial", [{"defect": True}], False, True),
            ("wrong generation", {"master_source_sha": other}, "blocking-partial", [{"defect": True}], False, True),
            ("wrong run", {"source_run_id": 124}, "blocking-partial", [{"defect": True}], False, True),
            ("unconfirmed report", {}, "blocking-partial", [{"defect": False}], False, True),
        )
        for label, change, completion, report, blocked, rejected in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "curation-proof.json").write_text(json.dumps(shared | change), encoding="utf-8")
                (root / "visual-review-completion.json").write_text(json.dumps({"state": completion}), encoding="utf-8")
                (root / "visual-review-report.json").write_text(json.dumps(report), encoding="utf-8")
                output = root / "outputs"
                result = subprocess.run(["bash", "-c", script], cwd=root, capture_output=True, text=True,
                                        env=os.environ | {"GITHUB_OUTPUT": str(output),
                                                          "GENERATION_SHA": generation,
                                                          "IMPLEMENTATION_SHA": generation,
                                                          "SOURCE_RUN_ID": "123"}, timeout=15)
                self.assertEqual(rejected, result.returncode != 0, result.stdout + result.stderr)
                marker = root / "visual-review-wave-block.json"
                self.assertEqual(blocked, marker.exists(), result.stdout + result.stderr)
                self.assertEqual(blocked, "blocked=true" in output.read_text(encoding="utf-8"))
                if blocked:
                    document = json.loads(marker.read_text(encoding="utf-8"))
                    self.assertEqual(generation, document["generation_sha"])
                    self.assertEqual(123, document["source_run_id"])

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
                    if "claude_capacity_probe.py" in block:
                        probe = (
                            ROOT / "scripts" / "ci" / "claude_capacity_probe.py"
                        ).read_text(encoding="utf-8")
                        self.assertIn("unset GH_TOKEN GITHUB_TOKEN", block)
                        self.assertIn('"--safe-mode"', probe)
                        self.assertIn('"--no-session-persistence"', probe)
                        self.assertIn('"--permission-mode"', probe)
                        self.assertIn('"dontAsk"', probe)
                        self.assertIn('"--tools"', probe)
                        self.assertIn('"--disallowedTools"', probe)
                        self.assertNotIn('"Read"', probe)
                        self.assertNotIn('"Bash"', probe)
                        continue
                    if workflow.name in {
                        "visual-review-drain.yml",
                        "mod-compatibility-review.yml",
                    }:
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
                        self.assertIn("Read(./{model_images_relative}/**)", runner)
                        self.assertNotIn("Read(./review-input/images/**)", runner)
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
        self.assertEqual(secret_steps, 6)

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
        self.assertIn("source-sha: ${{ github.sha }}", on_demand)
        self.assertIn("evidence-name: packaged-e2e-${{ matrix.id }}", on_demand)
        self.assertIn("uses: ./.github/actions/run-packaged-e2e", release)
        self.assertIn(
            "bundle-name: release-${{ needs.build.outputs.release_id }}", release
        )
        self.assertIn("source-sha: ${{ github.sha }}", release)
        self.assertIn("evidence-name: release-behavior-${{ matrix.id }}", release)
        self.assertIn("source-sha:\n", action)
        self.assertIn("required: true", action)
        self.assertIn("EXPECTED_SOURCE_SHA: ${{ inputs.source-sha }}", action)
        self.assertIn('[[ "$(git rev-parse HEAD)" == "$EXPECTED_SOURCE_SHA" ]]', action)
        self.assertIn('GITHUB_SHA="$EXPECTED_SOURCE_SHA"', action)
        self.assertIn("QUICKSKIN_E2E_RUNTIME_STORE", action)
        self.assertIn("QUICKSKIN_E2E_SERVER_STORE", action)
        self.assertIn("xvfb-run --auto-servernum", action)
        self.assertNotIn("-noreset", action)
        self.assertIn("e2e/ci_summary.py", action)
        self.assertIn("e2e-out/current/profiles/**/result.json", action)
        self.assertIn("e2e-out/current/summary.json", action)
        self.assertNotIn("e2e-out/profiles/**", action)
        self.assertNotIn("e2e-out/runs/**", action)
        self.assertIn("if-no-files-found: error", action)
        self.assertIn("name: ${{ inputs.evidence-name }}", action)
        self.assertIn('default: "1"', action)
        self.assertIn(
            "retention-days: ${{ inputs.evidence-retention-days }}",
            action,
        )
        self.assertIn(
            "evidence-retention-days: '7'",
            on_demand,
        )

    def test_installed_server_cache_is_exact_read_only_and_immutable_only(self) -> None:
        action = (
            COMPOSITE_ACTIONS / "run-packaged-e2e" / "action.yml"
        ).read_text(encoding="utf-8")

        # The transported material is the installed loader server, keyed by its recipe digest.
        self.assertIn("e2e/runtime_store_cache.py", action)
        self.assertIn("scripts/ci/runtime_store_cache_policy.py", action)
        # An exact key with no prefix fallback: a near miss must never satisfy an exact lookup.
        self.assertNotIn("restore-keys", action)
        for step in ("actions/cache/restore@", "actions/cache/save@"):
            self.assertIn(step, action)
            pinned = action.split(step, 1)[1][:40]
            self.assertRegex(pinned, r"^[0-9a-f]{40}$")
        # Publishing is restricted to a protected generation the policy approves.
        self.assertIn("steps.server-store-policy.outputs.read-only == 'false'", action)
        self.assertIn("steps.server-store.outputs.transported == 'true'", action)
        # Machine-local run state must never travel: its OS locks and device/inode identities
        # are meaningless on another runner.
        sys.path.insert(0, str(ROOT / "e2e"))
        import runtime_store_cache as identity

        self.assertEqual(("blobs", "recipes", "trees"), identity.IMMUTABLE_SUBDIRECTORIES)
        for machine_local in ("leases", "tmp", "trash"):
            self.assertNotIn(machine_local, identity.IMMUTABLE_SUBDIRECTORIES)

    def test_packaged_e2e_has_no_unattended_schedule_and_selects_matrix_owned_coverage(self) -> None:
        workflow = (WORKFLOWS / "on-demand-e2e.yml").read_text(encoding="utf-8")
        # Complete coverage comes from the protected post-merge dispatch and explicit manual
        # runs; an unattended nightly would repeat 32 Minecraft lanes for evidence nobody consumes.
        self.assertNotIn("schedule:", workflow.split("jobs:", 1)[0])
        self.assertNotIn('cron: "17 3 * * *"', workflow)
        self.assertIn("MATRIX_KIND: pr-anchors", workflow)
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
                "feature-coverage.yml",
                "Publish the complete feature baseline",
                "${{ steps.baseline.outputs.artifact_name }}",
            ): "90",
            (
                "release.yml",
                "Upload immutable release bundle",
                "release-${{ steps.release.outputs.release_id }}",
            ): "90",
            (
                "release-recovery.yml",
                "Upload the exact recovered release bundle",
                "release-recovery-${{ steps.recovery.outputs.release_id }}",
            ): "90",
            (
                "visual-review.yml",
                "Upload only the curated review input",
                "${{ steps.identity.outputs.artifact_name }}",
            ): "7",
            (
                "visual-review-drain.yml",
                "Upload the source-bound normalized report",
                "visual-review-${{ needs.select.outputs.review_key }}",
            ): "7",
            (
                "visual-review-drain.yml",
                "Upload the exact semantic anchor certificate",
                "${{ steps.certify.outputs.artifact_name }}",
            ): "90",
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
            (
                "on-demand-e2e.yml",
                "Upload immutable E2E input bundle",
                "e2e-input-bundle",
            ): "7",
            ("build-gate.yml", "Upload the staged release bundle for exact-head reuse", "staged-release-bundle"): "7",
            ("build-gate.yml", "Preserve original build provenance without copying its binaries", "reused-source-build"): "90",
            ("build-gate.yml", "Retain the immutable tested-build record", "tested-source-build"): "90",
            ("on-demand-e2e.yml", "Retain the original runtime reference without copying its captures", "reused-source-e2e"): "90",
            ("on-demand-e2e.yml", "Retain the immutable tested-runtime record", "tested-source-e2e"): "90",
            (
                "mod-compatibility-e2e.yml",
                "Upload the authenticated compatibility plan",
                "mod-compatibility-plan",
            ): "7",
            (
                "mod-compatibility-e2e.yml",
                "Re-publish the exact source bundle for parallel consumers",
                "${{ steps.plan.outputs.bundle_name }}",
            ): "7",
            (
                "mod-compatibility-e2e.yml",
                "Upload the bounded credential-free review capsule",
                "mod-compatibility-review-input-${{ github.run_id }}-"
                "${{ matrix.id }}-${{ github.run_attempt }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the exact normalized lane report",
                "mod-compatibility-review-${{ matrix.source_run_id }}-${{ matrix.id }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the protected compatibility verdict cache",
                "${{ steps.publish-verdict-cache.outputs.artifact_name }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the sanitized source-wide model-call telemetry",
                "mod-compatibility-review-telemetry-${{ needs.enumerate.outputs.source_run_id }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the durable clean lane marker",
                "mod-compatibility-lane-complete-${{ matrix.source_run_id }}-${{ matrix.id }}--${{ matrix.artifact_id }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the durable confirmed-defect marker",
                "mod-compatibility-wave-block-${{ needs.enumerate.outputs.source_run_id }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the durable source completion marker",
                "mod-compatibility-review-complete-${{ needs.enumerate.outputs.source_run_id }}",
            ): "7",
            (
                "mod-compatibility-review.yml",
                "Upload the durable source attempt settlement marker",
                "mod-compatibility-review-settled-${{ needs.enumerate.outputs.source_run_id }}-${{ needs.enumerate.outputs.source_run_attempt }}",
            ): "7",
            (
                "handle-version-port-result.yml",
                "Upload the authenticated nonvisual anchor continuation",
                "${{ steps.merge.outputs.artifact_name }}",
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
        build = job_block("build-matrix.yml", "target")
        e2e = job_block("on-demand-e2e.yml", "compile")
        release = job_block("release.yml", "build")
        policy = (ROOT / "scripts" / "ci" / "gradle_cache_policy.py").read_text(
            encoding="utf-8"
        )

        setup_count = sum(
            workflow.read_text(encoding="utf-8").count("gradle/actions/setup-gradle@")
            for workflow in WORKFLOWS.glob("*.yml")
        )
        self.assertEqual(setup_count, 2)
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
        self.assertIn("uses: ./.github/workflows/build-matrix.yml", e2e)
        self.assertNotIn("cache-writer: true", e2e)
        compiler = (WORKFLOWS / "build-matrix.yml").read_text()
        self.assertIn("default: false", compiler)
        self.assertIn('if [[ "$CACHE_WRITER" == true ]]', build)
        self.assertIn("cache-writer: true", job_block("build-gate.yml", "compile"))
        self.assertEqual(release.count("gradle/actions/setup-gradle@"), 1)
        self.assertEqual(release.count("cache-read-only: true"), 1)
        self.assertNotIn("gradle_cache_policy.py", release)

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
        queue = (ROOT / "scripts" / "ci" / "visual_review_queue.py").read_text(
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
        capacity_check = job_block("visual-review-drain.yml", "capacity-check")
        capacity_probe = job_block("visual-review-drain.yml", "capacity-probe")
        capacity_resume = job_block(
            "visual-review-drain.yml", "resume-capacity-queue"
        )
        review = job_block("visual-review-drain.yml", "review")
        preparation = job_block("visual-review-drain.yml", "prepare")
        cleanup = job_block("visual-review-drain.yml", "cleanup")
        release_anchor = job_block("visual-review-drain.yml", "release-anchor")
        release_compatibility = job_block(
            "visual-review-drain.yml", "release-mod-compatibility"
        )
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
                "capacity-check",
                "capacity-probe",
                "resume-capacity-queue",
                "prepare",
                "review",
                "request-feature-coverage",
                "cleanup",
                "release-mod-compatibility",
                "release-anchor",
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
        self.assertIn("concurrency:", review)
        self.assertIn(
            "group: quick-skin-visual-review-model\n",
            review,
        )
        self.assertIn("cancel-in-progress: false", review)
        # GitHub's default concurrency queue cancels all but the newest pending job.
        # Preserve different capsules while serializing the shared verdict cache.
        self.assertRegex(review, r"(?m)^      queue: max$")
        self.assertNotIn("queue: max", drain_header)
        self.assertNotIn("concurrency:", capacity_check)
        self.assertIn("scripts/ci/claude_capacity_gate.py", capacity_check)
        self.assertIn("needs.select.outputs.direct == 'true'", capacity_check)
        self.assertIn("durable capsule will be retried", capacity_check)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", capacity_check)
        self.assertIn("quick-skin-claude-capacity-probe", capacity_probe)
        self.assertIn("cancel-in-progress: false", capacity_probe)
        self.assertIn("scripts/ci/claude_capacity_gate.py", capacity_probe)
        self.assertIn("scripts/ci/claude_capacity_probe.py", capacity_probe)
        self.assertIn("CLAUDE_CODE_OAUTH_TOKEN", capacity_probe)
        capacity_probe_script = (
            ROOT / "scripts" / "ci" / "claude_capacity_probe.py"
        ).read_text(encoding="utf-8")
        self.assertIn('"--tools"', capacity_probe_script)
        self.assertIn('"--disallowedTools"', capacity_probe_script)
        self.assertIn("claude-capacity-marker.json", capacity_probe)
        self.assertIn("the durable capsule remains queued", capacity_probe)
        self.assertIn("scripts/ci/visual_review_queue.py", capacity_resume)
        self.assertIn("--list-pending-json", capacity_resume)
        self.assertIn('length <= 256', capacity_resume)
        self.assertIn("CURRENT_ARTIFACT_ID", capacity_resume)
        self.assertIn("visual-review-drain-requested", capacity_resume)
        self.assertIn("contents: write", capacity_resume)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", capacity_resume)
        self.assertIn("source scripts/ci/github_api_retry.sh", capacity_resume)
        self.assertIn('selector_status" -eq 75', capacity_resume)
        self.assertIn("capacity fan-out deferred", capacity_resume)
        self.assertIn("scripts/ci/visual_review_queue.py", select)
        self.assertIn("--requested-artifact-id", select)
        self.assertIn('selector_status" -eq 75', select)
        self.assertIn("durable queue selection deferred", select)
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
        self.assertIn("exact queue wake deferred", dispatch_selected)
        self.assertIn("needs.select.outputs.direct == 'true'", review)
        self.assertIn("needs.capacity-check.outputs.ready == 'true'", review)
        self.assertIn("needs.capacity-probe.outputs.ready == 'true'", review)
        self.assertIn("protected_gh_api_retry()", review)
        self.assertIn("GitHub API review guard attempt", review)
        self.assertIn("gh api rate_limit --jq .resources.core.reset", review)
        self.assertIn("protected_gh_api_retry --paginate --slurp", review)
        self.assertIn("mod-compatibility-requested", release_compatibility)
        self.assertIn("needs.review.outputs.compatibility_eligible == 'true'", release_compatibility)
        self.assertIn("automated-version-sync", release_compatibility)
        self.assertIn("has no automatic release identity", review)
        self.assertIn("contents: write", release_compatibility)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", release_compatibility)
        self.assertIn("source scripts/ci/github_api_retry.sh", release_compatibility)
        self.assertIn("github_api_retry --method POST", release_compatibility)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', release_compatibility)
        self.assertIn("for attempt in {1..180}", release_compatibility)

        self.assertIn('name == "Packaged E2E gate"', authenticate)
        self.assertIn('endswith(" - contract scenarios")', authenticate)
        self.assertIn("timeout-minutes: 75", authenticate)
        self.assertIn("source scripts/ci/github_api_retry.sh", authenticate)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', authenticate)
        self.assertIn("github_api_retry --paginate --slurp", authenticate)
        self.assertNotIn("gh api", authenticate)
        self.assertIn("pull-requests: read", authenticate)
        self.assertIn('commits/$source_sha/pulls', authenticate)
        self.assertIn('pulls/$source_pr_number/files?per_page=100', authenticate)
        self.assertIn("for attempt in {1..30}", authenticate)
        self.assertIn("Source PR association did not settle", authenticate)
        self.assertIn("Automation PR association did not settle", authenticate)
        self.assertIn("Ambiguous automation PR association", authenticate)
        self.assertIn('.user.login == "github-actions[bot]"', authenticate)
        self.assertIn('.changed_files', authenticate)
        self.assertIn('scripts/ci/visual_review_impact.py', authenticate)
        self.assertIn('source_pr_base="$(jq -er .base.ref', authenticate)
        self.assertIn('source_pr_merged="$(jq -er', authenticate)
        self.assertIn('Deferring semantic anchor review until PR', authenticate)
        self.assertIn('--scope source-pr', authenticate)
        self.assertIn('visual_scope=replicated-port', authenticate)
        self.assertIn('visual_scope=post-anchor-port', authenticate)
        self.assertIn('--scope "$visual_scope"', authenticate)
        self.assertIn('"$source_pr_base" == master', authenticate)
        self.assertIn('protected post-merge anchor generation', authenticate)
        self.assertIn('scripts/ci/mod_compatibility_impact.py', authenticate)
        self.assertIn('compatibility_impact=', authenticate)
        # The shared generation classifies its exact first-parent diff after planning and before
        # the manifest is published; a paged inventory keeps the fail-closed default.
        self.assertIn('compare/$parent_sha...$source_sha?per_page=100', authenticate)
        self.assertIn('--files "$generation_files_path"', authenticate)
        self.assertIn('"$generation_changed_files" =~ ^[1-9][0-9]?$', authenticate)
        self.assertLess(authenticate.index('feature_review.py --plan'),
                        authenticate.index('compare/$parent_sha...$source_sha'))
        self.assertLess(authenticate.index('compare/$parent_sha...$source_sha'),
                        authenticate.rindex("printf 'compatibility_impact=%s"))
        self.assertIn('.event == "repository_dispatch"', authenticate)
        self.assertIn('.path == ".github/workflows/sync-version-branches.yml"', authenticate)
        self.assertIn('outside ordinary visual review', authenticate)
        self.assertIn('ref: ${{ github.sha }}', authenticate)
        self.assertIn('persist-credentials: false', authenticate)
        self.assertIn('Ignoring %s-only visual review sync PR', authenticate)
        self.assertIn("visual-review-input-$source_run_id", authenticate)
        self.assertIn('generation_input_name="$input_name-$GITHUB_SHA"', authenticate)
        self.assertIn(
            "actions/artifacts?name=$artifact_query_name&per_page=100",
            authenticate,
        )
        self.assertNotIn("actions/artifacts?per_page=100", authenticate)
        self.assertIn("visual-review-$source_run_id", authenticate)
        self.assertIn("visual-review-drain.yml", authenticate)
        self.assertNotIn("implementation_sha", authenticate)
        self.assertNotIn("branches/master", authenticate)
        self.assertIn("actions/runs/$source_run_id/artifacts", authenticate)
        self.assertIn("artifact_inventory", authenticate)
        self.assertIn('source_run_attempt="$(jq -er', authenticate)
        self.assertIn('raw_artifact_inventory="$(jq -c', authenticate)
        self.assertIn("scenario_job_count * source_run_attempt", authenticate)
        self.assertIn("group_by(.name)", authenticate)
        self.assertIn("length <= $source_run_attempt", authenticate)
        self.assertIn("map(sort_by(.id) | .[-1])", authenticate)
        self.assertLess(
            authenticate.index("length <= $source_run_attempt"),
            authenticate.index("map(sort_by(.id) | .[-1])"),
        )

        self.assertIn("git fetch --no-tags origin \"$SOURCE_SHA\"", curate)
        self.assertIn("timeout-minutes: 90", curate)
        self.assertIn("source scripts/ci/github_api_retry.sh", curate)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', curate)
        self.assertIn("github_api_retry_to_file", curate)
        self.assertNotIn("gh api", curate)
        self.assertIn("actions/artifacts/$artifact_id", curate)
        self.assertIn("scripts/ci/bounded_zip.py", curate)
        self.assertIn("scripts/ci/e2e_job_graph.py", curate)
        self.assertIn('if [[ "$job_graph_status" -eq 78 ]]', curate)
        self.assertIn(
            '"$(jq -r .event <<< "$source_run")" == pull_request', curate
        )
        self.assertIn("--allow-advisory-controller-skew", curate)
        self.assertIn("protected post-merge generation", curate)
        self.assertIn('generation_sha" != "$IMPLEMENTATION_SHA', curate)
        self.assertIn("superseded visual-review generation", curate)
        self.assertIn("before any image decode or model wake", curate)
        self.assertIn(
            "steps.evidence.outputs.review_skipped == 'false'", prepare_workflow
        )
        self.assertNotIn("continue-on-error", curate)
        self.assertNotIn("path: source", curate)
        self.assertNotIn("git -C source", curate)
        self.assertIn('git show "$SOURCE_SHA:e2e/scenario-contract.json"', curate)
        self.assertIn('git show "$SOURCE_SHA:release/release-matrix.json"', curate)
        self.assertIn("--reference-identity", curate)
        # Historical schema-2 reference comparison consumed the retired pages-e2e raw handoffs.
        # It fails closed before any raw artifact is fetched; shared curation exits earlier.
        retired = (
            "Historical schema-2 reference comparison is retired (ADR 0010); "
            "recover from tag pre-mod-base-gallery."
        )
        self.assertIn(retired, curate)
        self.assertLess(curate.index(retired), curate.index('raw_root="$RUNNER_TEMP/raw-review-evidence"'))
        self.assertLess(curate.index("feature_review.py"), curate.index(retired))
        self.assertIn(retired, preparation)
        self.assertNotIn("scripts/pages/select_artifact.py", curate)
        self.assertNotIn("scripts/pages/evidence.py", curate)
        self.assertNotIn("--require-raw", curate)
        self.assertNotIn('"pages-e2e-$', curate)
        self.assertNotIn('"pages-e2e-$', preparation)
        self.assertIn("github_api_retry_to_file", review)
        self.assertIn("capsule_missing: ${{ steps.capsule.outputs.missing }}", review)
        self.assertIn("id: capsule", review)
        self.assertIn("curated-review-download", preparation)
        self.assertIn("printf 'missing=true\\n'", review)
        self.assertIn("no model session started", review)
        self.assertEqual(
            review.count("steps.capsule.outputs.missing != 'true'"), 7
        )
        self.assertIn(
            'source "$GITHUB_WORKSPACE/scripts/ci/github_api_retry.sh"', review
        )
        self.assertNotIn("--kind raw", curate)
        self.assertIn("--semantic-anchor", curate)
        self.assertIn("review_mode=anchor-semantic", curate)
        self.assertIn("visual_reference=null", curate)
        self.assertIn("--all", curate)
        self.assertIn("--validate-row-json", curate)
        self.assertIn("--curate-output", curate)
        self.assertIn("evidence_kind:\"raw-png\"", curate)
        self.assertIn("schema_version:5", curate)
        self.assertIn("compatibility_impact:$compatibility_impact", curate)
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
        self.assertIn("durable visual-review wake deferred", request)
        self.assertEqual(request.count("gh api --method POST"), 1)

        self.assertIn("actions/artifacts/$ARTIFACT_ID", review)
        self.assertIn("actions: write", review)
        self.assertIn("scripts/ci/bounded_zip.py", review)
        self.assertIn("--max-entries 520", preparation)
        self.assertIn("visual-review-capsule", review)
        self.assertIn("ref: ${{ needs.select.outputs.implementation_sha }}", review)
        self.assertIn("fetch-depth: 0", review)
        self.assertIn("persist-credentials: false", review)
        self.assertNotIn("actions/download-artifact@", review)
        self.assertIn("schema_version == 5", preparation)
        self.assertIn(".compatibility_impact", review)
        self.assertIn("cannot affect product/mod compatibility", review)
        # The historical raw-png reference is no longer revalidated: that path fails closed.
        self.assertNotIn('evidence_kind == "raw-png"', preparation)
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
        self.assertIn("--model claude-haiku-4-5", capacity_probe)
        self.assertIn("--triage-model claude-opus-5-5", review)
        self.assertIn("--verify-model claude-opus-5-5", review)
        self.assertNotIn("claude-sonnet-5", review)
        self.assertIn("--triage-chunk-size 8", review)
        self.assertIn("--verify-chunk-size 4", review)
        self.assertIn("--max-parallel-calls 32", review)
        self.assertIn("--call-spacing-seconds 0", review)
        self.assertIn("--model-attempts 3", review)
        self.assertIn("Install hash-locked image decoder", review)
        self.assertIn("--only-binary=:all: --require-hashes", review)
        self.assertIn("--requirement scripts/pages/requirements.txt", review)
        self.assertIn("visual_review_cache.py", review)
        self.assertIn("visual_similarity.py", review)
        self.assertIn("--similarity-codec", review)
        self.assertIn("--completion-state visual-review-completion.json", review)
        self.assertIn("--allow-blocking-partial", review)
        self.assertIn("visual-review-verdict-cache-$policy_sha256", review)
        self.assertIn("visual_review_cache.py\" combine", review)
        self.assertNotIn(
            'if [[ "$review_mode" != reference-comparison ]]', review
        )
        self.assertGreaterEqual(review.count("visual_review_semantic_prompt.md"), 2)
        self.assertIn('--review-mode "$review_mode"', review)
        self.assertIn('merge-base --is-ancestor \\', review)
        self.assertIn("producer_digest()", review)
        self.assertIn('reviewer_checkout && $0 == "          fetch-depth: 0"', review)
        self.assertIn("owner_producer=.*rev-parse", review)
        self.assertIn('current_producer=.*producer_digest', review)
        self.assertIn("comparison_count != 1", review)
        self.assertIn(
            '$producer_sha:.github/workflows/visual-review-drain.yml', review
        )
        self.assertIn('"$owner_producer" != "$current_producer"', review)
        self.assertNotIn('.head_sha == $workflow_sha', review)
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
        self.assertIn("visual-review-attempt-${{ needs.select.outputs.review_key }}", review)
        self.assertIn("claude-capacity-pause", review)
        self.assertIn("cooling=true", review)
        self.assertNotIn("visual-review-report.raw.json", drain_workflow)
        self.assertNotIn("e2e-out", review)
        self.assertIn("git -C \"$GITHUB_WORKSPACE\" diff --exit-code", review)
        self.assertIn("--normalized-report visual-review-report.json", review)
        self.assertIn("visual-review-report.json", review)
        self.assertIn("scripts/ci/visual_anchor_certification.py", review)
        self.assertIn("visual-anchor-certification-$master_source_sha", review)
        self.assertIn("associated=\"$(github_api_retry", review)
        self.assertIn("visual-anchor-certification-{generation}", queue)
        self.assertIn("generation in certified", queue)
        self.assertIn('api.get_branch_sha("master")', queue)
        self.assertIn('pull.get("state") == "open"', queue)
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
        self.assertIn("needs.review.outputs.capsule_missing == 'true'", cleanup)
        self.assertIn("API rate limit exceeded", cleanup)
        self.assertIn("queue cleanup deferred", cleanup)
        self.assertIn("authenticated marker must outlive", drain_workflow)
        self.assertIn("contents: write", release_anchor)
        self.assertIn("actions: read", release_anchor)
        self.assertIn("visual-anchor-certified", release_anchor)
        self.assertIn("actions/artifacts/$ARTIFACT_ID", release_anchor)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", release_anchor)
        self.assertNotIn("actions/checkout@", release_anchor)
        self.assertIn("for attempt in {1..4}", release_anchor)
        self.assertIn("gh api rate_limit --jq .resources.core.reset", release_anchor)
        self.assertIn("github_api_retry --method POST", release_anchor)
        # Completed/deleted capsules must not wake themselves again. Exact curator
        # wakes, capacity recovery and the scheduled sweep own retry admission.
        self.assertNotIn("Wake the next queued review", drain_workflow)
        self.assertNotIn("visual-review-continuation", drain_workflow)

        self.assertIn("lossless Minecraft 1.20.1", triage_prompt)
        self.assertIn("becoming softer or blurred", triage_prompt)
        self.assertIn("independent second-pass", verify_prompt)
        for prompt in (triage_prompt, verify_prompt):
            self.assertIn("any intact Vanilla default", prompt)
            self.assertIn("This exception never applies when the expectation names", prompt)
            self.assertIn("a custom skin or cape", prompt)
            self.assertIn("Source: 128x64", prompt)
            self.assertIn("Output: 64x32", prompt)
            self.assertIn("checkerboard", prompt.lower())
        self.assertIn("deliberately no reference image", semantic_prompt)
        self.assertIn("Do not compare loaders", semantic_prompt)
        self.assertIn("matches_reference=null", semantic_verify_prompt)
        for prompt in (semantic_prompt, semantic_verify_prompt):
            self.assertIn("Noor", prompt)
            self.assertIn("Makena", prompt)
            self.assertIn("red top", prompt)
            self.assertIn("yellow or orange top", prompt)
        for prompt in (
            triage_prompt,
            verify_prompt,
            semantic_prompt,
            semantic_verify_prompt,
        ):
            normalized_prompt = " ".join(prompt.split())
            self.assertIn("1280x720", normalized_prompt)
            self.assertIn("Elytra hides cape", normalized_prompt)
            self.assertIn("separated, tapered Elytra", normalized_prompt)
            self.assertIn("opaque full-atlas rectangle", normalized_prompt)
            self.assertIn("Vanilla elytra after cape removal", normalized_prompt)
        self.assertIn("DEFAULT_TRIAGE_CHUNK_SIZE = 8", runner)
        self.assertIn("DEFAULT_VERIFY_CHUNK_SIZE = 4", runner)
        self.assertIn("DEFAULT_MAX_PARALLEL_CALLS = 16", runner)
        self.assertIn("MODEL_IMAGE_SIZE = (1280, 720)", runner)
        self.assertIn("Image.Resampling.LANCZOS", runner)
        self.assertIn('f"Read(./{model_images_relative}/**)"', runner)
        self.assertNotIn('"Read(./review-input/images/**)"', runner)
        self.assertIn("ThreadPoolExecutor", runner)
        self.assertIn(
            'item["candidate_semantic_sha256"] == item["reference_semantic_sha256"]',
            runner,
        )
        self.assertIn('triage["decision"] == "clean"', runner)
        self.assertIn('triage["confidence"] == "high"', runner)
        self.assertNotIn("requires_perceptual_verification", runner)
        self.assertIn(
            "too ambiguous to clear confidently", " ".join(triage_prompt.split())
        )
        self.assertIn("TRIAGE_CONFIDENCE", (
            ROOT / "e2e" / "check_visual_review.py"
        ).read_text(encoding="utf-8"))

    def test_mod_compatibility_wave_is_locked_parallel_and_credential_separated(self) -> None:
        execution_workflow = (
            WORKFLOWS / "mod-compatibility-e2e.yml"
        ).read_text(encoding="utf-8")
        review_workflow = (
            WORKFLOWS / "mod-compatibility-review.yml"
        ).read_text(encoding="utf-8")
        compatibility_runtime = (
            ROOT / "e2e" / "mod_compatibility.py"
        ).read_text(encoding="utf-8")
        updater = (
            ROOT / "e2e" / "update_mod_compatibility_lock.py"
        ).read_text(encoding="utf-8")
        contract = json.loads(
            (ROOT / "e2e" / "mod-compatibility-contract.json").read_text(
                encoding="utf-8"
            )
        )
        compatibility_triage_prompt = (
            ROOT / "e2e" / "mod_compatibility_review_prompt.md"
        ).read_text(encoding="utf-8")
        compatibility_verify_prompt = (
            ROOT / "e2e" / "mod_compatibility_review_verify_prompt.md"
        ).read_text(encoding="utf-8")

        admit = job_block("mod-compatibility-e2e.yml", "admit")
        base_review_curate = job_block("visual-review.yml", "curate")
        prepare = job_block("mod-compatibility-e2e.yml", "prepare")
        runtime = job_block("mod-compatibility-e2e.yml", "compatibility-e2e")
        execution_gate = job_block("mod-compatibility-e2e.yml", "gate")
        request_review = job_block("mod-compatibility-e2e.yml", "request-review")
        recover_review = job_block("mod-compatibility-review.yml", "recover")
        enumerate_review = job_block("mod-compatibility-review.yml", "enumerate")
        capacity_check = job_block(
            "mod-compatibility-review.yml", "capacity-check"
        )
        capacity_probe = job_block(
            "mod-compatibility-review.yml", "capacity-probe"
        )
        prepare_review = job_block(
            "mod-compatibility-review.yml", "prepare-review"
        )
        review = job_block("mod-compatibility-review.yml", "review")
        publish_lanes = job_block(
            "mod-compatibility-review.yml", "publish-lanes"
        )
        review_gate = job_block("mod-compatibility-review.yml", "gate")
        review_continue = job_block("mod-compatibility-review.yml", "continue")
        plan_step_start = prepare.index(
            "- name: Reverify the source-bound bundle and resolve every applicable lane"
        )
        plan_step_end = prepare.find("\n      - name:", plan_step_start + 1)
        plan_step = prepare[
            plan_step_start : plan_step_end if plan_step_end >= 0 else len(prepare)
        ]
        publish_cache_start = review.index(
            "- name: Publish the protected compatibility verdict cache"
        )
        publish_cache_end = review.find("\n      - name:", publish_cache_start + 1)
        if publish_cache_end < 0:
            publish_cache_end = len(review)
        publish_cache = review[publish_cache_start:publish_cache_end]

        self.assertIn("types:\n      - mod-compatibility-requested", execution_workflow)
        self.assertIn("permissions: {}", execution_workflow)
        self.assertIn("permissions: {}", review_workflow)
        self.assertIn("visual-review-report.json", admit)
        self.assertIn(
            'manifest="$review_root/review-input/visual-review-manifest.json"',
            admit,
        )
        self.assertNotIn(
            'manifest="$review_root/visual-review-manifest.json"',
            admit,
        )
        self.assertIn("${{ runner.temp }}/review-input/", base_review_curate)
        self.assertIn(
            "${{ runner.temp }}/curation-proof.json",
            base_review_curate,
        )
        self.assertIn(".semantic_valid == true", admit)
        self.assertIn(".defect == false", admit)
        self.assertIn('git rev-parse "$SOURCE_SHA^{tree}"', admit)
        self.assertIn("automated-version-sync", admit)
        self.assertIn("e2e-input-bundle", admit)
        for workflow in (execution_workflow, review_workflow):
            self.assertNotIn(
                '.size_in_bytes | type == "number" and .size_in_bytes',
                workflow,
            )
        self.assertEqual(
            execution_workflow.count(
                '((.size_in_bytes | type) == "number" and .size_in_bytes'
            ),
            2,
        )
        self.assertEqual(
            review_workflow.count(
                '((.size_in_bytes | type) == "number" and .size_in_bytes'
            ),
            1,
        )
        self.assertIn("e2e/mod_compatibility.py --plan", prepare)
        self.assertIn('--base-matrix-kind "$BASE_MATRIX_KIND"', prepare)
        self.assertIn("base_matrix_kind=native-anchors", admit)
        self.assertIn("base_matrix_kind=pr-anchors", admit)
        self.assertIn(".base_matrix_kind == $base_matrix_kind", prepare)
        self.assertIn('.base_matrix_kind == "pr-anchors"', enumerate_review)
        self.assertIn(
            ".source_branch == .target_branch and",
            enumerate_review,
        )
        self.assertIn(".source_sha == .target_sha", enumerate_review)
        self.assertIn("source_artifacts=", prepare)
        self.assertIn("source_run_attempt", admit)
        self.assertIn("SOURCE_RUN_ATTEMPT", prepare)
        self.assertIn("sort_by(.id) | .[-1]", prepare)
        self.assertIn("([.artifacts[].id] | unique | length)", prepare)
        self.assertIn("GH_TOKEN: ${{ github.token }}", plan_step)
        self.assertLess(
            plan_step.index("GH_TOKEN:"), plan_step.index("github_api_retry")
        )
        self.assertIn("source scripts/ci/github_api_retry.sh", admit)
        self.assertIn("github_api_retry_to_file", admit)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', admit)
        self.assertIn("source scripts/ci/github_api_retry.sh", prepare)
        self.assertIn("source scripts/ci/github_api_retry.sh", runtime)
        self.assertIn("github_api_retry_to_file", prepare)
        self.assertIn("compatibility-baselines/inventory.json", execution_workflow)
        self.assertIn("Extract the centrally authenticated same-version baseline", runtime)
        self.assertIn("scripts/ci/bounded_zip.py", runtime)
        self.assertNotIn("actions/download-artifact@", execution_workflow)
        self.assertNotIn(
            'repos/$GITHUB_REPOSITORY/actions/runs/$SOURCE_RUN_ID/artifacts',
            runtime,
        )
        self.assertIn("[.runnable[].base_evidence_name] | unique", prepare)
        self.assertIn(".source_branch == .target_branch", enumerate_review)
        self.assertIn('[[ "$(git rev-parse HEAD)" == "$RUNTIME_SHA" ]]', prepare)
        self.assertIn('GITHUB_SHA="$RUNTIME_SHA"', prepare)
        self.assertIn('ci_reuse.py verify --kind e2e', prepare)
        self.assertIn("not_applicable", prepare)
        self.assertIn(".release_branch == $target_branch", prepare)
        self.assertIn("all(.runnable[];", prepare)
        self.assertIn("strategy:\n      fail-fast: false", runtime)
        self.assertNotIn("max-parallel", runtime)
        self.assertIn("compatibility-mod: ${{ matrix.compatibility_mod }}", runtime)
        self.assertIn("if: matrix.compatibility_mod == 'cpm'", runtime)
        self.assertIn("cpm_fixture_secret.py materialize", runtime)
        self.assertEqual(
            runtime.count("secrets.QSM_E2E_CPM_FIXTURE_GZIP_B64_"),
            4,
        )
        self.assertIn("cpm-model-path:", runtime)
        self.assertIn("source-sha: ${{ needs.admit.outputs.runtime_sha }}", runtime)
        self.assertIn("same-version baseline", runtime)
        self.assertIn("--candidate-root e2e-out/current", runtime)
        self.assertIn(
            "mod-compatibility-review-input-${{ github.run_id }}-"
            "${{ matrix.id }}-${{ github.run_attempt }}",
            runtime,
        )
        self.assertIn(
            "mod-compatibility-e2e-${{ matrix.id }}-${{ github.run_attempt }}",
            runtime,
        )
        self.assertIn("source_run_attempt", enumerate_review)
        self.assertIn(
            '[[ "$candidate_attempt" == "$capsule_attempt" ]]', prepare_review
        )
        self.assertNotRegex(execution_workflow, r"(?m)^  curate:$")
        self.assertNotIn("CURATE_RESULT", execution_gate)

        self.assertIn(
            "types:\n      - mod-compatibility-review-requested", review_workflow
        )
        self.assertIn("mod-compatibility-review-sweep-requested", review_workflow)
        self.assertIn('cron: "17,47 * * * *"', review_workflow)
        self.assertIn("workflow_dispatch:", review_workflow)
        self.assertNotIn("workflow_run:", review_workflow)
        self.assertIn("needs.gate.result == 'failure'", request_review)
        self.assertIn("contents: write", request_review)
        self.assertIn("ref: ${{ github.sha }}", request_review)
        self.assertIn("persist-credentials: false", request_review)
        self.assertIn("source scripts/ci/github_api_retry.sh", request_review)
        self.assertIn("mod-compatibility-review-requested", request_review)
        self.assertIn("source_repository:$source_repository", request_review)
        self.assertIn("source_run_id:$source_run_id", request_review)
        self.assertIn("source_sha:$source_sha", request_review)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', request_review)
        self.assertIn("branches/master", request_review)
        self.assertIn('[[ "$current_master" != "$GITHUB_SHA" ]]', request_review)
        self.assertIn("skipping its AI wake", request_review)
        self.assertIn("repos/$GITHUB_REPOSITORY/dispatches", request_review)
        self.assertLess(
            request_review.index("branches/master"),
            request_review.index("repos/$GITHUB_REPOSITORY/dispatches"),
        )
        self.assertIn("github.event.client_payload.source_run_id", review_workflow)
        self.assertIn("mod_compatibility_review_queue.py", recover_review)
        self.assertIn("eligible=true", recover_review)
        self.assertIn("contents: write", recover_review)
        self.assertIn("repos/$GITHUB_REPOSITORY/dispatches", recover_review)
        self.assertIn("mod-compatibility-review-requested", recover_review)
        self.assertIn('[[ "$SOURCE_REPOSITORY" == "$GITHUB_REPOSITORY" ]]', enumerate_review)
        self.assertIn(
            "github.event.client_payload.source_sha == github.sha",
            enumerate_review,
        )
        self.assertIn("for attempt in {1..120}", enumerate_review)
        self.assertIn('[[ "$implementation_sha" == "$SOURCE_SHA" ]]', enumerate_review)
        self.assertIn('[[ "$implementation_sha" == "$GITHUB_SHA" ]]', enumerate_review)
        self.assertIn('[[ "$current_master" == "$GITHUB_SHA" ]]', enumerate_review)
        self.assertIn('(.conclusion == "success" or .conclusion == "failure")', enumerate_review)
        self.assertIn(
            '(.source_run_id | type == "number" and . > 0)', enumerate_review
        )
        self.assertNotIn(".source_run_id == $source_run_id", enumerate_review)
        self.assertIn('--argjson compatibility_run_id "$SOURCE_RUN_ID"', enumerate_review)
        self.assertIn(
            ".workflow_run.id == $compatibility_run_id", enumerate_review
        )
        self.assertIn(". as $plan |", enumerate_review)
        self.assertNotIn("source_sha:$.source_sha", enumerate_review)
        self.assertIn('if ($matches|length) == 0 then empty', enumerate_review)
        self.assertIn('error("duplicate review capsule', enumerate_review)
        self.assertIn("mod-compatibility-lane-complete-$SOURCE_RUN_ID", enumerate_review)
        self.assertIn("timeout-minutes: 75", enumerate_review)
        self.assertGreaterEqual(
            enumerate_review.count("source scripts/ci/github_api_retry.sh"), 2
        )
        self.assertIn(
            'GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', enumerate_review
        )
        self.assertNotIn("gh api", enumerate_review)
        self.assertIn("claude_capacity_gate.py", capacity_check)
        self.assertIn("claude_capacity_gate.py", capacity_probe)
        self.assertIn("claude_capacity_probe.py", capacity_probe)
        self.assertIn("group: quick-skin-claude-capacity-probe", capacity_probe)
        self.assertIn(
            "fresh_ready: ${{ steps.resolve.outputs.fresh_ready }}", capacity_probe
        )
        self.assertIn("name: ${{ steps.probe.outputs.marker_name }}", capacity_probe)
        self.assertIn("mod_compatibility_review_queue.py", enumerate_review)
        self.assertIn("ref: ${{ github.sha }}", prepare_review)
        self.assertIn("ref: ${{ github.sha }}", review)
        self.assertIn("ref: ${{ github.sha }}", publish_lanes)
        self.assertIn(
            "Revalidate current master before capsule aggregation", prepare_review
        )
        self.assertIn(
            "Revalidate current master before batch or model admission", review
        )
        self.assertIn(
            '[[ "$SOURCE_IMPLEMENTATION_SHA" == "$GITHUB_SHA" ]]',
            prepare_review,
        )
        self.assertIn(
            '[[ "$SOURCE_IMPLEMENTATION_SHA" == "$GITHUB_SHA" ]]', review
        )
        self.assertIn('[[ "$current_master" == "$GITHUB_SHA" ]]', prepare_review)
        self.assertIn('[[ "$current_master" == "$GITHUB_SHA" ]]', review)
        self.assertLess(
            prepare_review.index(
                "Revalidate current master before capsule aggregation"
            ),
            prepare_review.index("Fetch and authenticate every curated capsule"),
        )
        self.assertLess(
            review.index(
                "Revalidate current master before batch or model admission"
            ),
            review.index("Download only the authenticated source-wide review batch"),
        )
        self.assertNotIn("strategy:", review.split("\n    steps:", 1)[0])
        self.assertIn("strategy:\n      fail-fast: false", publish_lanes)
        self.assertNotIn("max-parallel", review.split("\n    steps:", 1)[0])
        self.assertIn("needs.enumerate.outputs.pending_count != '0'", prepare_review)
        self.assertIn("needs.capacity-probe.outputs.fresh_ready == 'true'", prepare_review)
        self.assertIn("needs.prepare-review.result == 'success'", review)
        self.assertNotIn("needs.capacity-check.outputs.ready == 'true'", review)
        self.assertIn(
            '--max-parallel-calls "${{ needs.enumerate.outputs.model_parallelism }}"',
            review,
        )
        self.assertIn(
            '"${{ needs.enumerate.outputs.model_call_spacing_seconds }}"',
            review,
        )
        self.assertNotIn("--max-parallel-calls 32", review)
        self.assertNotIn("--review-identical", review)
        self.assertIn(
            "scripts/ci/mod_compatibility_review_batch.py assemble",
            prepare_review,
        )
        self.assertIn(
            "scripts/ci/mod_compatibility_review_batch.py validate", review
        )
        self.assertIn(
            "scripts/ci/mod_compatibility_review_batch.py split", publish_lanes
        )
        self.assertIn("Restore authenticated compatibility verdict cache shards", review)
        self.assertIn("--cache visual-review-verdict-cache.input.json", review)
        self.assertIn("--cache-policy-sha256", review)
        self.assertIn("e2e/mod-compatibility-contract.json", review)
        self.assertIn("release/release-matrix.json", review)
        for prompt in (compatibility_triage_prompt, compatibility_verify_prompt):
            normalized_prompt = " ".join(prompt.split()).lower()
            self.assertIn("one-pixel checkerboards", normalized_prompt)
            self.assertIn("not a requirement for solid colour blocks", normalized_prompt)
        self.assertIn(
            'global_cache_name="mod-compatibility-verdict-cache-$policy_sha256"',
            review,
        )
        self.assertNotIn('cache_names+=("$global_cache_name-$lane_id")', review)
        self.assertIn('merge-base --is-ancestor \\', review)
        self.assertIn(
            '$cache_owner_sha:.github/workflows/mod-compatibility-review.yml',
            review,
        )
        self.assertIn("Publish the protected compatibility verdict cache", review)
        self.assertIn("--review-mode reference-comparison", publish_cache)
        self.assertIn("Upload the protected compatibility verdict cache", review)
        self.assertIn("Retire consumed compatibility verdict cache shards", review)
        self.assertIn("steps.verdict-cache-artifact.outputs.artifact-id", review)
        self.assertIn("--max-entries 1", review)
        self.assertIn("git show", enumerate_review)
        self.assertIn(
            "$plan_source_sha:e2e/mod-compatibility-contract.json",
            enumerate_review,
        )
        self.assertIn(
            'source_contract="$RUNNER_TEMP/source-scenario-contract.json"',
            prepare_review,
        )
        self.assertIn(
            '--compatibility-scenario-contract "$source_contract"',
            prepare_review,
        )
        self.assertIn(
            '--compatibility-artifact-node "$artifact_node"', prepare_review
        )
        self.assertIn('--compatibility-mod "$mod"', prepare_review)
        self.assertIn("--model claude-haiku-4-5", capacity_probe)
        self.assertIn("--triage-model claude-opus-5-5", review)
        self.assertIn("--verify-model claude-opus-5-5", review)
        self.assertNotIn("claude-sonnet-5", review)
        self.assertIn("Install hash-locked image decoder", prepare_review)
        self.assertIn("Install hash-locked image decoder", review)
        self.assertIn("--only-binary=:all: --require-hashes", prepare_review)
        self.assertIn("--requirement scripts/pages/requirements.txt", review)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", prepare_review)
        self.assertNotIn("Install Node", prepare_review)
        self.assertIn("--telemetry-report visual-review-telemetry.json", review)
        self.assertIn("Validate only sanitized model-call telemetry", review)
        self.assertIn("mod-compatibility-review-telemetry-", review)
        self.assertIn("mod-compatibility-wave-block", review)
        self.assertIn(
            "name: mod-compatibility-wave-block-${{ needs.enumerate.outputs.source_run_id }}",
            review,
        )
        self.assertIn(
            "mod-compatibility-attempt-${{ needs.enumerate.outputs.source_run_id }}-batch",
            review,
        )
        self.assertIn(
            "mod-compatibility-review-${{ matrix.source_run_id }}-${{ matrix.id }}",
            publish_lanes,
        )
        self.assertIn(
            "mod-compatibility-lane-complete-${{ matrix.source_run_id }}-${{ matrix.id }}",
            publish_lanes,
        )
        self.assertGreaterEqual(
            prepare_review.count("source scripts/ci/github_api_retry.sh"), 2
        )
        self.assertGreaterEqual(
            review.count("source scripts/ci/github_api_retry.sh"), 2
        )
        self.assertIn("github_api_retry_to_file", prepare_review)
        self.assertIn("github_api_retry_to_file", review)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', prepare_review)
        self.assertNotIn("gh api", prepare_review)
        self.assertNotIn("gh api", review)
        self.assertIn("REVIEW_RESULT", review_gate)
        self.assertIn("PREPARE_RESULT", review_gate)
        self.assertIn("PUBLISH_RESULT", review_gate)
        self.assertIn("PROBE_FRESH_READY", review_gate)
        self.assertIn("CAPACITY_STATE", review_gate)
        self.assertIn("claude-capacity-pause", review_gate)
        self.assertIn("quota_or_rate_limit", review_gate)
        self.assertIn("COMPLETED_COUNT", review_gate)
        self.assertIn("PENDING_COUNT", review_gate)
        self.assertIn(
            "mod-compatibility-review-complete-${{ needs.enumerate.outputs.source_run_id }}",
            review_gate,
        )
        self.assertIn("mod-compatibility-review-sweep-requested", review_continue)
        self.assertIn("needs.gate.outputs.settled == 'true'", review_continue)
        self.assertNotIn("base-evidence", review)
        self.assertNotIn("candidate-evidence", review)
        self.assertEqual(prepare_review.count("actions/download-artifact@"), 0)
        self.assertEqual(review.count("actions/download-artifact@"), 1)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", publish_lanes)

        self.assertIn("locked.url", compatibility_runtime)
        self.assertIn("locked.sha256", compatibility_runtime)
        self.assertIn("locked.sha512", compatibility_runtime)
        self.assertNotIn("/project/", compatibility_runtime)
        self.assertIn("/project/", updater)
        self.assertEqual(
            {
                "cpm",
                "ears",
                "skin-layers-3d",
                "customnpcs",
                "essential",
                "replaymod",
            },
            {item["id"] for item in contract["mods"]},
        )
        self.assertNotIn("player-armor-stands", execution_workflow.lower())
        self.assertNotIn("player-armor-stands", review_workflow.lower())
        self.assertEqual(7, contract["schema_version"])
        self.assertEqual(
            ["compatibility-cpm"],
            next(
                item["additional_execution_profiles"]
                for item in contract["mods"]
                if item["id"] == "cpm"
            ),
        )
        self.assertTrue(
            all(
                not item["additional_execution_profiles"]
                for item in contract["mods"]
                if item["id"] != "cpm"
            )
        )
        self.assertEqual(
            [
                {
                    "runtime_version": "1.21.9",
                    "loader": "neoforge",
                    "reason": (
                        "3D Skin Layers upstream does not support Minecraft 1.21.9 "
                        "on NeoForge - use 1.21.10"
                    ),
                }
            ],
            next(
                item["excluded_lanes"]
                for item in contract["mods"]
                if item["id"] == "skin-layers-3d"
            ),
        )

    def test_mod_compatibility_review_keeps_base_and_wave_run_ids_distinct(
        self,
    ) -> None:
        enumerate_review = job_block("mod-compatibility-review.yml", "enumerate")
        match = re.search(
            r'matrix="\$\(jq -c \\\n'
            r'\s+--argjson artifacts "\$artifacts" \\\n'
            r'\s+--argjson compatibility_run_id "\$SOURCE_RUN_ID" \\\n'
            r'\s+--argjson compatibility_run_attempt "\$SOURCE_RUN_ATTEMPT" \\\n'
            r'\s+--arg implementation_sha "\$IMPLEMENTATION_SHA" \\\n'
            r"\s+'(?P<program>.*?)' \"\$plan\"\)\"",
            enumerate_review,
            re.DOTALL,
        )
        self.assertIsNotNone(match)
        program = match.group("program")
        base_run_id = 123
        compatibility_run_id = 456
        contract_sha = "c" * 64
        lane_id = "neoforge-26_2--cpm--mod-compatibility"
        plan = {
            "runnable": [
                {
                    "id": lane_id,
                    "artifact_node": "neoforge-26.2",
                    "runtime_version": "26.2",
                    "loader": "neoforge",
                    "compatibility_mod": "cpm",
                    "compatibility_name": "Customizable Player Models",
                    "base_evidence_name": "packaged-e2e-neoforge-26_2--pr-behavior",
                    "compatibility_contract_sha256": contract_sha,
                }
            ],
            "source_run_id": base_run_id,
            "source_sha": "a" * 40,
            "target_branch": "fabric-and-neoforge-26.2",
            "target_sha": "b" * 40,
        }
        first_artifact = {
            "id": 789,
            "name": (
                f"mod-compatibility-review-input-{compatibility_run_id}-{lane_id}-1"
            ),
            "expired": False,
            "workflow_run": {"id": compatibility_run_id},
            "digest": "sha256:" + "d" * 64,
            "size_in_bytes": 1024,
        }
        latest_artifact = {
            **first_artifact,
            "id": 790,
            "name": (
                f"mod-compatibility-review-input-{compatibility_run_id}-{lane_id}-2"
            ),
        }
        future_artifact = {
            **first_artifact,
            "id": 791,
            "name": (
                f"mod-compatibility-review-input-{compatibility_run_id}-{lane_id}-3"
            ),
        }

        selected = subprocess.run(
            [
                "jq",
                "-c",
                "--argjson",
                "artifacts",
                json.dumps(
                    {"artifacts": [future_artifact, first_artifact, latest_artifact]}
                ),
                "--argjson",
                "compatibility_run_id",
                str(compatibility_run_id),
                "--argjson",
                "compatibility_run_attempt",
                "2",
                "--arg",
                "implementation_sha",
                "e" * 40,
                program,
            ],
            input=json.dumps(plan),
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertEqual(selected.returncode, 0, selected.stderr)
        matrix = json.loads(selected.stdout)
        self.assertEqual(len(matrix["include"]), 1)
        self.assertEqual(matrix["include"][0]["source_run_id"], compatibility_run_id)
        self.assertNotEqual(matrix["include"][0]["source_run_id"], base_run_id)
        self.assertEqual(matrix["include"][0]["artifact_id"], latest_artifact["id"])

        duplicate_artifact = {**latest_artifact, "id": 792}
        duplicate = subprocess.run(
            [
                "jq",
                "-c",
                "--argjson",
                "artifacts",
                json.dumps({"artifacts": [latest_artifact, duplicate_artifact]}),
                "--argjson",
                "compatibility_run_id",
                str(compatibility_run_id),
                "--argjson",
                "compatibility_run_attempt",
                "2",
                "--arg",
                "implementation_sha",
                "e" * 40,
                program,
            ],
            input=json.dumps(plan),
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn("duplicate review capsule attempt", duplicate.stderr)

    def test_mod_compatibility_artifact_inventory_filter_executes(self) -> None:
        prepare = job_block("mod-compatibility-e2e.yml", "prepare")
        inventory_block = prepare[prepare.index("baseline_artifacts=") :]
        match = re.search(
            r'--argjson source_run_attempt "\$RUNTIME_RUN_ATTEMPT" \\\n\s+'
            r'--argjson source_run_id "\$RUNTIME_RUN_ID" \\\n\s+\'(?P<program>.*?)\' \\\n\s+"\$RUNNER_TEMP/mod-compatibility-plan\.json"',
            inventory_block,
            re.DOTALL,
        )
        self.assertIsNotNone(match)
        program = match.group("program")
        self.assertNotIn("all($names[] as $name;", program)

        plan = {
            "runnable": [
                {"base_evidence_name": "packaged-e2e-fabric--pr-behavior"},
                {"base_evidence_name": "packaged-e2e-fabric--pr-behavior"},
            ]
        }
        artifact = {
            "id": 456,
            "name": "packaged-e2e-fabric--pr-behavior",
            "expired": False,
            "workflow_run": {"id": 123},
            "digest": "sha256:" + "a" * 64,
            "size_in_bytes": 1024,
        }

        accepted = subprocess.run(
            [
                "jq",
                "-e",
                "--argjson",
                "artifacts",
                json.dumps({"artifacts": [artifact]}),
                "--argjson",
                "source_run_attempt",
                "1",
                "--argjson",
                "source_run_id",
                "123",
                program,
            ],
            input=json.dumps(plan),
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertEqual(accepted.returncode, 0, accepted.stderr)

        rerun_artifact = {
            **artifact,
            "id": 789,
            "digest": "sha256:" + "b" * 64,
        }
        rerun = subprocess.run(
            [
                "jq",
                "-e",
                "--argjson",
                "artifacts",
                json.dumps({"artifacts": [artifact, rerun_artifact]}),
                "--argjson",
                "source_run_attempt",
                "2",
                "--argjson",
                "source_run_id",
                "123",
                program,
            ],
            input=json.dumps(plan),
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertEqual(rerun.returncode, 0, rerun.stderr)
        self.assertEqual(json.loads(rerun.stdout)[0]["id"], rerun_artifact["id"])

        duplicate = subprocess.run(
            [
                "jq",
                "-e",
                "--argjson",
                "artifacts",
                json.dumps({"artifacts": [artifact, artifact]}),
                "--argjson",
                "source_run_attempt",
                "1",
                "--argjson",
                "source_run_id",
                "123",
                program,
            ],
            input=json.dumps(plan),
            capture_output=True,
            check=False,
            text=True,
        )
        self.assertNotEqual(duplicate.returncode, 0, duplicate.stderr)

    def test_pages_fan_in_uses_protected_code_and_exact_release_heads(self) -> None:
        # Admission, collection and rendering are mod-base code at the pinned commit. The caller
        # binds that commit from GitHub's own run record and executes it only from the protected
        # default-branch head; every producer merely wakes it.
        import test_mod_base_caller as caller

        workflow = caller.caller_text()
        managed, _begin, _body = caller.caller_regions()
        verify = caller.caller_job("verify-kit")
        publish = caller.caller_job("publish")
        sha, version, _references = caller.pin()

        self.assertEqual(caller.rendered_managed_region(), managed)
        self.assertIn("permissions: {}", workflow)
        for forbidden in (*caller.FORBIDDEN_CALLER_TEXT, "pages-evidence-ready",
                          "pages-compatibility-evidence-ready", "workflows:\n      - Packaged E2E"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, workflow)
        self.assertNotIn("scripts/pages/", workflow)
        self.assertEqual({"actions": "read", "contents": "read"}, caller.job_permissions(verify))
        self.assertNotIn("actions/checkout@", verify)
        self.assertIn('[[ "$GITHUB_REF" == "refs/heads/$default_branch" ]]', verify)
        self.assertIn('.path == ".github/workflows/pages.yml" and .head_sha == $sha', verify)
        self.assertIn('startswith("the-plum-team/mod-base/.github/workflows/")', verify)
        self.assertIn('[[ "$declared" == "$kit_sha" ]]', verify)
        self.assertIn("repos/The-Plum-Team/mod-base/compare/$kit_sha...main", verify)
        self.assertIn("needs: verify-kit", publish)
        self.assertIn(f"uses: The-Plum-Team/mod-base/.github/workflows/publish.yml@{sha} # {version}", publish)
        self.assertIn("kit-sha: ${{ needs.verify-kit.outputs.kit_sha }}", publish)
        self.assertIn("operation: ${{ github.event_name == 'schedule' && 'recovery' || inputs.operation }}",
                      publish)
        self.assertIn("if: inputs.operation != 'rotate'", publish)
        self.assertNotIn("secrets:", workflow)

    def test_pages_render_recheck_requires_the_exact_shared_source_commit(self) -> None:
        # The kit build rechecks every source head before and after rendering; the caller-owned
        # deploy repeats that recheck immediately before minting its Pages token.
        script = step_script(
            "pages.yml", "deploy", "Recheck every published source head immediately before deployment"
        )
        head = "b" * 40
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            fake_gh = temp / "gh"
            fake_gh.write_text(
                textwrap.dedent(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    printf '%s\n' "$*" >> "$API_CALLS"
                    case "$2" in
                      repos/The-Plum-Team/Quick-Skin-Mod) printf 'master\n' ;;
                      repos/The-Plum-Team/Quick-Skin-Mod/branches/master) printf '%s\n' "$CURRENT_SHA" ;;
                      *) printf 'unexpected API call: %s\n' "$2" >&2; exit 90 ;;
                    esac
                    """
                ).lstrip(),
                encoding="utf-8",
            )
            fake_gh.chmod(0o755)
            environment = os.environ.copy()
            environment.pop("BASH_ENV", None)
            environment.pop("ENV", None)
            environment.update(
                {
                    "API_CALLS": str(temp / "api-calls"),
                    "CURRENT_SHA": head,
                    "GITHUB_REF": "refs/heads/master",
                    "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod",
                    "GITHUB_SHA": head,
                    "HEADS": json.dumps({"master": head}),
                    "PATH": f"{temp}{os.pathsep}{environment.get('PATH', '')}",
                }
            )

            def run(**overrides: str) -> subprocess.CompletedProcess[str]:
                return subprocess.run(
                    ["bash", "-c", script],
                    cwd=temp,
                    env={**environment, **overrides},
                    capture_output=True,
                    check=False,
                    text=True,
                )

            accepted = run()
            self.assertEqual(accepted.returncode, 0, accepted.stderr)
            calls = (temp / "api-calls").read_text(encoding="utf-8").splitlines()
            self.assertIn("api repos/The-Plum-Team/Quick-Skin-Mod/branches/master --jq .commit.sha", calls)

            moved = run(CURRENT_SHA="a" * 40)
            self.assertNotEqual(moved.returncode, 0)
            self.assertIn("advanced before deployment; retaining the deployed site", moved.stderr)
            for overrides in (
                {"HEADS": json.dumps({"master": "a" * 40})},
                {"HEADS": json.dumps({"../master": head, "master": head})},
                {"HEADS": "{}"},
                {"GITHUB_REF": "refs/heads/automation/sync/mc1.20.1"},
            ):
                with self.subTest(overrides=overrides):
                    self.assertNotEqual(run(**overrides).returncode, 0)

    def test_pages_collector_rejects_unproved_shared_coverage(self) -> None:
        # Selected feature evidence reaches the site only through the kit's composition, which
        # calls Quick Skin's adapter; the caller forwards regex-validated identifiers and never a
        # coverage decision, and a family's coverage can only be its producer's exact head.
        import test_mod_base_caller as caller

        workflow = caller.caller_text()
        publish = caller.caller_job("publish")
        for identifier in ("run_id", "sha", "family", "bundle_key", "artifact_id", "artifact_digest",
                           "coverage_sha"):
            with self.subTest(identifier=identifier):
                self.assertIn(f"${{{{ inputs.{identifier} }}}}", publish)
                self.assertIn(f"      {identifier}: {{description: Internal", workflow)
        self.assertNotIn("--allow-continuation", workflow)
        self.assertNotIn("carry-forward", workflow)
        adapter = (ROOT / "scripts" / "pages" / "mod_base_adapter.py").read_text(encoding="utf-8")
        for hook in ("compose", "authenticate_extensions", "family_validate"):
            with self.subTest(hook=hook):
                self.assertRegex(adapter, rf"(?m)^def {hook}\(")
        family = job_block("mod-compatibility-review.yml", "publish-evidence")
        self.assertIn("coverage-sha: ${{ github.sha }}", family)
        self.assertIn('if [[ "$COVERAGE_SHA" != "$GITHUB_SHA" ]]; then', family)

    def test_mod_compatibility_pages_publication_reuses_only_complete_clean_reports(self) -> None:
        review_workflow = (
            WORKFLOWS / "mod-compatibility-review.yml"
        ).read_text(encoding="utf-8")
        request = job_block("mod-compatibility-review.yml", "request-publication")
        publish = job_block("mod-compatibility-review.yml", "publish-evidence")
        notify = job_block("mod-compatibility-review.yml", "notify-family")

        self.assertIn("operation:", review_workflow)
        self.assertIn("- publish", review_workflow)
        self.assertIn("mod-compatibility-publication-requested", review_workflow)
        self.assertIn("needs.gate.outputs.complete == 'true'", request)
        self.assertIn("needs.enumerate.outputs.source_run_id", request)
        self.assertIn("mod-compatibility-publication-requested", request)
        self.assertIn("repos/$GITHUB_REPOSITORY/dispatches", request)
        self.assertIn("actions/checkout@", request)
        self.assertIn("persist-credentials: false", request)
        self.assertIn("source scripts/ci/github_api_retry.sh", request)
        self.assertIn("github_api_retry --method POST", request)
        self.assertIn('GITHUB_API_RETRY_ATTEMPTS: "10"', request)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', request)
        self.assertIn("inputs.source_run_id", publish)
        self.assertIn("github.event.client_payload.source_run_id", publish)
        self.assertIn("github.event.action == 'mod-compatibility-publication-requested'", publish)
        self.assertIn(
            "    permissions:\n      actions: read\n      contents: read\n", publish
        )
        self.assertNotIn("contents: write", publish)
        self.assertNotIn("actions: write", publish)
        self.assertIn("ref: ${{ github.sha }}", publish)
        self.assertIn("fetch-depth: 0", publish)
        self.assertIn("persist-credentials: false", publish)
        self.assertIn("scripts/pages/collect_compatibility.py", publish)
        self.assertIn("--source-run-id", publish)
        self.assertIn("--publication-run-id", publish)
        self.assertNotIn("visual_review_runner.py", publish)
        self.assertNotIn("ANTHROPIC", publish)
        # The pinned composite wraps, validates and uploads the family handoff; a separate
        # job holding only actions: write wakes the protected publisher with its exact identity.
        self.assertRegex(
            publish, r"uses: The-Plum-Team/mod-base/actions/publish-family@[0-9a-f]{40} # v\d+\.\d+\.\d+"
        )
        self.assertIn("bundle: public-compatibility/${{ steps.collect.outputs.bundle_key }}", publish)
        self.assertNotIn("actions/upload-artifact@", publish)
        self.assertNotIn("pages-compatibility-evidence-ready", publish)
        self.assertIn("needs: publish-evidence", notify)
        self.assertIn("    permissions:\n      actions: write\n", notify)
        self.assertNotIn("actions/checkout@", notify)
        self.assertRegex(
            notify, r"uses: The-Plum-Team/mod-base/actions/notify-pages@[0-9a-f]{40} # v\d+\.\d+\.\d+"
        )
        self.assertIn("operation: family", notify)
        self.assertIn("artifact-digest: ${{ needs.publish-evidence.outputs.artifact_digest }}", notify)

    def test_every_repository_dispatch_payload_fits_the_ten_property_github_limit(self):
        """GitHub rejects a repository_dispatch whose client_payload has more than ten keys."""
        seen = 0
        for path in workflow_paths():
            text = path.read_text(encoding="utf-8")
            for match in re.finditer(r"client_payload:\s*\{(.*?)\}\s*\}", text, re.S):
                keys = re.findall(r"(?:^|[,{\s])([a-z_]+):", match.group(1))
                seen += 1
                with self.subTest(workflow=path.name, keys=keys):
                    self.assertTrue(1 <= len(keys) <= 10)
                    self.assertEqual(len(keys), len(set(keys)))
            with self.subTest(workflow=path.name):
                # Pages wakes are workflow_dispatch inputs of the pinned notify-pages composite.
                self.assertNotIn("pages-evidence-ready", text)
                self.assertNotIn("pages-compatibility-evidence-ready", text)
        self.assertGreaterEqual(seen, 15)
        notify = job_block("mod-compatibility-review.yml", "notify-family")
        inputs = re.findall(r"(?m)^          ([a-z0-9-]+): ", notify.split("        with:\n", 1)[1])
        self.assertEqual(8, len(inputs))
        self.assertEqual(len(inputs), len(set(inputs)))
        self.assertNotIn("artifact-name", inputs)
        self.assertIn("bundle-key", inputs)

    def test_pages_evidence_rotation_is_post_success_bounded_and_exact(self) -> None:
        # Exact-ID rotation is kit code; the caller only requests it after a successful finalize
        # and runs it under its own lock, authenticated by the callee after completed/success.
        import test_mod_base_caller as caller

        workflow = caller.caller_text()
        request = caller.caller_job("request-rotation")
        rotate = caller.caller_job("rotate")
        handoff = job_block("on-demand-e2e.yml", "prepare-pages-evidence")
        sha, version, _references = caller.pin()

        self.assertFalse((WORKFLOWS / "rotate-pages-evidence.yml").exists())
        self.assertFalse((ROOT / "scripts" / "pages" / "rotate_artifacts.py").exists())
        self.assertIn("permissions: {}", workflow)
        self.assertIn(caller.ROTATION_LOCK, workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertNotIn("continue-on-error", request)
        self.assertEqual({"actions": "write"}, caller.job_permissions(request))
        self.assertNotIn("actions/checkout@", request)
        self.assertIn("needs.finalize.result == 'success'", request)
        self.assertIn("repos/$GH_REPO/actions/workflows/pages.yml/dispatches", request)
        self.assertIn('inputs: {operation: "rotate", run_id: $run_id, sha: $sha}', request)
        self.assertIn('--arg run_id "$GITHUB_RUN_ID" --arg sha "$GITHUB_SHA"', request)

        self.assertIn("github.event_name == 'workflow_dispatch' && inputs.operation == 'rotate'", rotate)
        self.assertEqual({"actions": "write", "contents": "read"}, caller.job_permissions(rotate))
        self.assertIn(f"uses: The-Plum-Team/mod-base/.github/workflows/rotate.yml@{sha} # {version}", rotate)
        self.assertIn("pages-run-id: ${{ inputs.run_id }}", rotate)
        self.assertIn("pages-run-sha: ${{ inputs.sha }}", rotate)
        self.assertNotIn("pages: write", rotate)
        self.assertNotIn("id-token: write", rotate)
        self.assertNotIn("continue-on-error", rotate)
        self.assertNotIn("implementation_sha", rotate)

        self.assertIn("actions: read", handoff)
        self.assertNotIn("actions: write", handoff)
        self.assertNotIn("pages-e2e-", handoff)
        self.assertNotIn("retention-days", handoff)
        self.assertIn("fromJSON(needs.pages-inventory.outputs.targets)", handoff)

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
        # The site artifact is uploaded by the pinned kit callee; only the caller deploys it.
        import test_mod_base_caller as caller

        workflow = caller.caller_text()
        self.assertIn(caller.DEPLOY_PAGES, caller.caller_job("deploy"))
        self.assertNotIn("actions/upload-pages-artifact@", workflow)
        for line in workflow.splitlines():
            match = re.match(r"\s*(?:-\s+)?uses:\s+(\S+)", line)
            if match is not None:
                with self.subTest(uses=match.group(1)):
                    self.assertRegex(match.group(1), r"^[^@]+@[0-9a-f]{40}$")

    def test_packaged_e2e_exposes_one_stable_required_context(self) -> None:
        required = job_block("on-demand-e2e.yml", "required-gate")
        self.assertIn("&& 'Packaged E2E deferred for draft' || 'Packaged E2E gate' }}", required)
        self.assertIn("always()", required)
        self.assertIn("needs.runtime-policy.result", required)
        self.assertIn("needs.runtime-policy.outputs.effective", required)
        self.assertIn("needs.build.result", required)
        self.assertIn("needs.e2e.result", required)
        self.assertIn("inputs.attest_run_id == ''", required)

    def test_draft_pull_requests_defer_both_gates_without_reporting_a_required_context(self) -> None:
        draft = "github.event.pull_request.draft && github.event.pull_request.base.ref == 'master'"
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        for workflow, root, gate, step, context, deferred in (
            ("build-gate.yml", "source", "build", "Require the complete compilation and policy jobs",
             "Build and verify", "Build deferred for draft"),
            ("on-demand-e2e.yml", "runtime-policy", "required-gate", "Require build and packaged behavior",
             "Packaged E2E gate", "Packaged E2E deferred for draft"),
        ):
            with self.subTest(workflow=workflow):
                text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
                self.assertIn("  pull_request:\n    # Converting a PR to draft starts a deferred run "
                              "that cancels its in-progress gate.\n    types: [opened, synchronize, "
                              "reopened, ready_for_review, converted_to_draft]\n", text)
                self.assertIn(f"!({draft})", job_block(workflow, root))
                block = job_block(workflow, gate)
                # A skipped job still reports its name, and GitHub leaves a skipped job's name
                # expression unevaluated; the running draft job therefore uses another context.
                self.assertIn(f"    name: ${{{{ {draft} && '{deferred}' || '{context}' }}}}\n", block)
                self.assertIn(f"      DRAFT_DEFERRED: ${{{{ {draft} }}}}\n", block)
                self.assertNotIn("if: github.event_name == 'pull_request'\n", block)
                self.assertEqual(2, block.count(
                    "if: github.event_name == 'pull_request' && env.DRAFT_DEFERRED != 'true'\n"))
                script = step_script(workflow, gate, step)
                root_result = "SOURCE_RESULT" if workflow == "build-gate.yml" else "POLICY_RESULT"
                summary = Path(temporary.name) / f"{gate}-summary.md"
                environment = {**os.environ, "DRAFT_DEFERRED": "true", root_result: "skipped",
                               "GITHUB_STEP_SUMMARY": str(summary)}
                self.assertEqual(0, subprocess.run(["bash", "-c", script], env=environment,
                                                   capture_output=True).returncode)
                self.assertIn("Draft PR:", summary.read_text(encoding="utf-8"))
                for result in ("success", "failure", ""):
                    self.assertNotEqual(0, subprocess.run(["bash", "-c", script],
                        env={**environment, root_result: result}, capture_output=True).returncode)

    def test_rate_limited_gate_notifications_use_protected_bounded_retry(self) -> None:
        e2e_notify = job_block("on-demand-e2e.yml", "notify-version-port")
        pages_notify = job_block("on-demand-e2e.yml", "notify-pages")
        build_notify = job_block("build-gate.yml", "notify-version-port")
        sync_discover = job_block("sync-version-branches.yml", "discover")

        # The Pages wake is the pinned pure-bash composite: it retries with the kit's bounded
        # helper (10 attempts, 60 s cap) and needs neither a checkout nor contents: write.
        self.assertRegex(
            pages_notify,
            r"uses: The-Plum-Team/mod-base/actions/notify-pages@[0-9a-f]{40} # v\d+\.\d+\.\d+",
        )
        self.assertIn("    permissions:\n      actions: write\n\n", pages_notify)
        self.assertNotIn("contents:", pages_notify)
        self.assertNotIn("actions/checkout@", pages_notify)

        for block in (e2e_notify, build_notify):
            with self.subTest(name=block.splitlines()[0]):
                self.assertIn("ref: master", block)
                self.assertIn("persist-credentials: false", block)
                self.assertIn("source scripts/ci/github_api_retry.sh", block)
                self.assertIn('GITHUB_API_RETRY_ATTEMPTS: "10"', block)
                self.assertIn("github_api_retry --method POST", block)

        for block in (e2e_notify, build_notify):
            self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', block)

        self.assertIn("source scripts/ci/github_api_retry.sh", sync_discover)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', sync_discover)
        self.assertIn("github_api_retry_to_file", sync_discover)
        self.assertIn(
            "github_cli_retry gh label create automated-version-sync",
            sync_discover,
        )

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
        inventory = job_block("on-demand-e2e.yml", "pages-inventory")
        self.assertIn("inputs.runtime_policy == 'full'", inventory)
        self.assertIn("      - pages-inventory", pages)
        self.assertIn("needs.pages-inventory.result == 'success'", pages)
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
        self.assertIn("github_api_retry --paginate --slurp", inspect)
        self.assertIn("[.[].jobs[]", inspect)
        self.assertIn("protected-github-api-retry.sh", inspect)
        self.assertIn("GITHUB_API_RETRY_MAX_WAIT_SECONDS", inspect)
        self.assertIn("github_cli_retry gh run list", inspect)
        self.assertIn(
            '"+refs/heads/master:refs/remotes/origin/master"', inspect
        )
        self.assertIn(
            'git merge-base --is-ancestor "$protected_sha" "$EXPECTED_SHA"',
            inspect,
        )
        self.assertIn("Ignoring superseded port result", inspect)
        self.assertIn('gh run view "$GATE_RUN_ID" --log-failed', inspect)
        self.assertIn("version_port_failure_policy.py", inspect)
        self.assertIn('disposition="$(jq -r .disposition', inspect)
        self.assertIn("Automated AI repair is intentionally disabled", inspect)
        self.assertIn('gh run rerun "$GATE_RUN_ID" --failed', inspect)
        self.assertIn('[[ "$run_attempt" == 1', inspect)
        self.assertIn("no AI repair was started", inspect)
        self.assertLess(
            inspect.index("version_port_failure_policy.py"),
            inspect.index("gh label create ai-repair-attempted"),
        )
        self.assertIn("-f runtime_policy=full", repair)

        self.assertIn("branches/master", merge)
        self.assertIn(
            'if ! git merge-base --is-ancestor "$protected_sha" "$head_sha"; then',
            merge,
        )
        self.assertIn("Protected master advanced beyond port", merge)
        self.assertIn('git show "$protected_sha:scripts/ci/e2e_impact.py"', merge)
        self.assertIn('git show "$protected_sha:release/release-matrix.json"', merge)
        self.assertIn('--base "$base_sha"', merge)
        self.assertIn('--head "$head_sha"', merge)
        self.assertIn("NOTIFYING_RUNTIME_POLICY", merge)
        self.assertIn("scripts/ci/e2e_job_graph.py", merge)
        self.assertIn("protected-github-api-retry.sh", merge)
        self.assertIn("github_cli_retry gh pr merge", merge)
        self.assertIn("github_api_retry --paginate --slurp", merge)
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
        self.assertIn("github_api_retry --paginate --slurp", merge)
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
        self.assertIn("github.event_name == 'push' && github.ref == 'refs/heads/master'", workflow)
        self.assertIn("--matrix release/release-matrix.json", workflow)
        self.assertNotIn("scripts/release/version_branches.py", workflow)
        self.assertNotIn("/branches?per_page=", workflow)

    def test_release_status_refresh_runs_the_policy_suites_without_write_credentials(self) -> None:
        # The complete suites include the mod-base kit tests, which fetch the pinned kit; they run
        # read-only and uncredentialed, and only their exact validated commit may be refreshed.
        workflow = (WORKFLOWS / "refresh-release-status.yml").read_text(encoding="utf-8")
        self.assertIn("\npermissions: {}\n", workflow)
        validate = job_block("refresh-release-status.yml", "validate")
        refresh = job_block("refresh-release-status.yml", "refresh")
        self.assertIn("    permissions:\n      contents: read\n    outputs:\n", validate)
        self.assertIn("persist-credentials: false", validate)
        self.assertNotIn("persist-credentials: true", validate)
        self.assertNotIn("GH_TOKEN", validate)
        self.assertIn('PYTHONDONTWRITEBYTECODE: "1"', validate)
        for suite in ("ci", "release"):
            self.assertIn(f"python3 scripts/ci/parallel_unittest.py -s scripts/{suite}/tests -p 'test_*.py' -v",
                          validate)
            self.assertNotIn(f"scripts/{suite}/tests", refresh)
        self.assertIn("    needs: validate\n", refresh)
        self.assertIn("ref: ${{ needs.validate.outputs.sha }}", refresh)
        self.assertIn("      actions: write\n      contents: write\n      pull-requests: write\n", refresh)

    def test_release_test_jobs_install_locked_pages_dependency(self) -> None:
        for workflow, job in (
            ("build-gate.yml", "policy-release"),
            ("refresh-release-status.yml", "validate"),
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
        ci_policy = job_block("build-gate.yml", "policy-ci")
        self.assertLess(ci_policy.index("scripts/pages/requirements.txt"),
                        ci_policy.index("scripts/ci/tests"))
        self.assertIn("--require-hashes", ci_policy)
        self.assertIn("--only-binary=:all:", ci_policy)

    def test_python_compilation_covers_the_entire_tooling_tree(self) -> None:
        for workflow, job in (
            ("build-gate.yml", "policy"),
            ("refresh-release-status.yml", "validate"),
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
        build = job_block("build-gate.yml", "policy")

        self.assertIn("Validate branch-specific README profile", build)
        self.assertIn("BASE_REF: ${{ github.base_ref }}", build)
        self.assertIn("REF_NAME: ${{ github.ref_name }}", build)
        self.assertIn("scripts/release/branch_readme.py", build)
        self.assertIn('--profile-branch "$profile_branch"', build)
        self.assertIn("--check", build)
        self.assertIn("scripts/release/workflow_guidance.py", build)
        self.assertIn("--guidance docs/ai/WORKFLOW.md", build)
        # The site front end is mod-base code checked by the kit's own CI.
        self.assertNotIn("node --check", build)
        self.assertIn("python3 scripts/ci/mod_base_kit.py verify --network\n", build)
        self.assertIn("python3 scripts/ci/mod_base_kit.py run template check --repo .\n", build)
        self.assertRegex(build, r"uses: The-Plum-Team/mod-base/actions/setup@[0-9a-f]{40} # v\d+\.\d+\.\d+")

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
                self.assertIn("ref: ${{ github.sha }}", block)
                self.assertNotIn("ref: ${{ steps.plan.outputs.source_sha }}", block)
                self.assertNotIn("ref: ${{ steps.plan.outputs.work_head_sha }}", block)
                self.assertIn("Prepare an empty isolated port workspace", block)
                self.assertIn('git fetch --no-tags origin "$WORK_HEAD_SHA"', block)
                self.assertIn("ACTIONS_RUNTIME_TOKEN ACTIONS_RUNTIME_URL", block)
                self.assertIn("ACTIONS_CACHE_URL ACTIONS_RESULTS_URL", block)
                self.assertIn(
                    '[[ "$(git -C ../controller rev-parse HEAD)" == "$SOURCE_SHA" ]]',
                    block,
                )
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

        dependency_install = validate.index(
            "Install hash-locked Pages image dependency"
        )
        materialize = validate.index('git fetch --no-tags origin "$WORK_HEAD_SHA"')
        self.assertLess(dependency_install, materialize)

    def test_version_sync_renders_and_revalidates_target_readme(self) -> None:
        propose = job_block("sync-version-branches.yml", "propose")
        validate = job_block("sync-version-branches.yml", "validate")
        publish = job_block("sync-version-branches.yml", "publish")

        for block in (propose, validate, publish):
            self.assertIn("--normalize-e2e-policy", block)
            self.assertIn("--retire-player-armor-stands", block)
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
        authorize = job_block("sync-version-branches.yml", "authorize")
        publish = job_block("sync-version-branches.yml", "publish")
        self.assertIn("needs.propose.result != 'cancelled'", publish)
        self.assertIn("needs.validate.result != 'cancelled'", publish)
        self.assertIn("needs.authorize.result == 'success'", publish)
        self.assertIn("Build one immutable settled validation index", authorize)
        self.assertIn("jobs?filter=latest&per_page=100", authorize)
        self.assertEqual(authorize.count("jobs?filter=latest&per_page=100"), 1)
        self.assertIn("for inventory_attempt in {1..12}", authorize)
        self.assertIn("if (( inventory_attempt < 12 ))", authorize)
        self.assertIn("inventory_settled=true", authorize)
        self.assertIn("inventory did not settle within 60s", authorize)
        self.assertEqual(authorize.count("sleep 5"), 1)
        self.assertGreaterEqual(authorize.count('.status == "completed"'), 2)
        self.assertIn(".conclusion == \"success\"", authorize)
        self.assertIn(".run_id == ($run_id | tonumber)", authorize)
        self.assertIn(".head_sha == $source_sha", authorize)
        self.assertIn(". <= ($run_attempt | tonumber)", authorize)
        self.assertIn("validation_index: ${{ steps.validation-index.outputs.value }}", authorize)
        self.assertIn("Require this target's centrally authorized validation", publish)
        self.assertIn("VALIDATION_INDEX: ${{ needs.authorize.outputs.validation_index }}", publish)
        self.assertIn('.run_id == $run_id', publish)
        self.assertIn("tonumber <= ($max_attempt | tonumber)", publish)
        self.assertIn('.source_sha == $source_sha', publish)
        self.assertIn("index($target) != null", publish)
        self.assertNotIn("jobs?filter=latest&per_page=100", publish)
        self.assertNotIn("validate_result=", publish)
        self.assertIn("Download the immutable validated proposal", publish)
        self.assertNotIn("actions/upload-artifact@", authorize)
        self.assertLess(
            publish.index("Require this target's centrally authorized validation"),
            publish.index("Download the immutable validated proposal"),
        )

    def test_port_authorizer_waits_for_the_rest_inventory_to_settle(self) -> None:
        authorize = job_block("sync-version-branches.yml", "authorize")
        marker = "        run: |\n"
        script_start = authorize.index(marker) + len(marker)
        script_lines: list[str] = []
        for line in authorize[script_start:].splitlines():
            if line.startswith("          "):
                script_lines.append(line[10:])
            elif not line:
                script_lines.append(line)
            else:
                break
        script = "sleep() { :; }\n" + "\n".join(script_lines)

        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            calls = temp / "inventory-calls"
            output = temp / "github-output"
            fake_gh = temp / "gh"
            fake_gh.write_text(
                "#!/usr/bin/env bash\n"
                + textwrap.dedent(
                    """
                    attempts=0
                    [[ -f "$INVENTORY_TEST_CALLS" ]] && \
                      attempts="$(<"$INVENTORY_TEST_CALLS")"
                    attempts=$((attempts + 1))
                    printf '%s' "$attempts" > "$INVENTORY_TEST_CALLS"
                    if (( attempts == 1 )); then
                      status=in_progress
                      conclusion=null
                    else
                      status=completed
                      conclusion='"success"'
                    fi
                    printf '[{"jobs":[{"name":"Validate port to fabric-and-neoforge-26.1 without credentials","status":"%s","conclusion":%s,"run_id":123,"head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","run_attempt":1}]}]\\n' \
                      "$status" "$conclusion"
                    """
                ),
                encoding="utf-8",
            )
            fake_gh.chmod(0o755)
            environment = os.environ.copy()
            environment.pop("BASH_ENV", None)
            environment.pop("ENV", None)
            environment.update(
                {
                    "EXPECTED_TARGETS": '["fabric-and-neoforge-26.1"]',
                    "GITHUB_OUTPUT": str(output),
                    "GITHUB_REPOSITORY": "The-Plum-Team/Quick-Skin-Mod",
                    "GITHUB_RUN_ATTEMPT": "1",
                    "GITHUB_RUN_ID": "123",
                    "GITHUB_SHA": "a" * 40,
                    "INVENTORY_TEST_CALLS": str(calls),
                    "PATH": f"{temp}{os.pathsep}{environment.get('PATH', '')}",
                    "RUNNER_TEMP": str(temp),
                }
            )
            completed = subprocess.run(
                ["bash", "-c", script],
                cwd=ROOT,
                env=environment,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(calls.read_text(encoding="utf-8"), "2")
            self.assertIn("inventory is not settled", completed.stderr)
            payload = output.read_text(encoding="utf-8").removeprefix("value=")
            index = json.loads(payload)
            self.assertEqual(
                index["successful_targets"], ["fabric-and-neoforge-26.1"]
            )

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

    def test_nonvisual_anchor_continuation_is_exact_and_model_free(self) -> None:
        handler = (WORKFLOWS / "handle-version-port-result.yml").read_text(
            encoding="utf-8"
        )
        sync_workflow = (WORKFLOWS / "sync-version-branches.yml").read_text(
            encoding="utf-8"
        )
        merge = job_block("handle-version-port-result.yml", "merge")
        sync = job_block("sync-version-branches.yml", "discover")
        visual = job_block("visual-review.yml", "authenticate")

        checkout_start = sync.index("- name: Check out trusted synchronization code")
        checkout_end = sync.index("\n      - name:", checkout_start + 1)
        checkout = sync[checkout_start:checkout_end]
        self.assertIn("fetch-depth: 0", checkout)
        self.assertIn(
            '[[ "$(git rev-parse --is-shallow-repository)" == false ]]', sync
        )
        self.assertLess(
            sync.index('[[ "$(git rev-parse --is-shallow-repository)" == false ]]'),
            sync.index('git rev-list --parents -n 1'),
        )
        self.assertEqual(
            1,
            sync_workflow.count(
                "fetch-depth: 0", 0, sync_workflow.index("  propose:")
            ),
        )

        for required in (
            "scripts/ci/visual_review_impact.py",
            "scripts/ci/visual_nonimpact_certification.py",
            "--scope replicated-port",
            '--base "$base_sha"',
            '--head "$head_sha"',
            '&& "$chain_complete" == true',
            '&& "$current_generation_bound" == true',
            "artifact_name=visual-anchor-nonimpact-%s",
            "--build-run-id",
            "--e2e-run-id",
        ):
            with self.subTest(boundary="merge", required=required):
                self.assertIn(required, merge)
        self.assertLess(
            merge.index("scripts/ci/visual_review_impact.py"),
            merge.index("artifact_name=visual-anchor-nonimpact-%s"),
        )
        self.assertIn("Upload the authenticated nonvisual anchor continuation", handler)
        self.assertIn("Release the nonvisual synchronization wave", handler)
        self.assertNotIn("CLAUDE_CODE_OAUTH_TOKEN", merge)

        for required in (
            "visual-anchor-nonimpact",
            ".github/workflows/handle-version-port-result.yml",
            "scripts/ci/visual_nonimpact_certification.py verify",
            "scripts/ci/visual_review_impact.py",
            '--base "$anchor_base_sha"',
            '--head "$anchor_source_sha"',
            '"${source_commit[2]}" == "$GITHUB_SHA"',
            '[[ "$chain_complete" == true ]]',
            ".github/workflows/build-gate.yml",
            ".github/workflows/on-demand-e2e.yml",
            '--exclude "$anchor_branch"',
        ):
            with self.subTest(boundary="sync", required=required):
                self.assertIn(required, sync)
        self.assertLess(
            sync.index("visual_nonimpact_certification.py verify"),
            sync.index('--exclude "$anchor_branch"'),
        )
        self.assertIn("--scope source-pr", visual)
        self.assertIn("Ignoring %s-only visual review sync PR", visual)

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
        self.assertIn("retention-days: 7", gate)
        source = job_block("on-demand-e2e.yml", "build-source")
        consumer = (ROOT / "scripts/ci/staged_build_bundle.py").read_text()
        self.assertIn("scripts/ci/staged_build_bundle.py --wait-seconds 5400", source)
        self.assertIn('WORKFLOW = ".github/workflows/build-gate.yml"', consumer)
        self.assertIn('run.get("head_sha") == source.head', consumer)
        self.assertIn('run.get("head_repository", {}).get("full_name") == source.head_repository', consumer)
        self.assertIn("artifact-ids: ${{ needs.build-source.outputs.artifact_id }}", build)
        self.assertIn("run-id: ${{ needs.build-source.outputs.run_id }}", build)
        self.assertIn("--verify-staged", build)
        self.assertNotIn("continue-on-error", source)
        self.assertNotIn("build_matrix.py", build)
        self.assertIn("needs.build-source.outputs.reused == 'false'",
                      job_block("on-demand-e2e.yml", "compile"))

    def test_isolated_compilation_keeps_the_complete_required_build_gate(self) -> None:
        target = job_block("build-matrix.yml", "target")
        assemble = job_block("build-matrix.yml", "assemble")
        gate = job_block("build-gate.yml", "build")
        # The validated plan alone decides the width, so a support change needs no workflow edit.
        self.assertNotIn("max-parallel:", target)
        self.assertIn('python scripts/release/build_matrix.py --clean "${target_args[@]}"', target)
        self.assertIn('python scripts/release/verify_release.py "${target_args[@]}"', target)
        self.assertIn("needs: target", assemble)
        self.assertIn("scripts/release/assemble_build.py", assemble)
        self.assertIn("needs: [source, compile, policy, policy-release, policy-ci]", gate)
        self.assertIn('[[ "$REUSED" == false ]]', gate)
        self.assertIn('"$COMPILE_RESULT" "$POLICY_RESULT" "$RELEASE_POLICY_RESULT" "$CI_POLICY_RESULT"', gate)
        self.assertIn("--verify-staged", gate)
        self.assertNotIn("max-parallel:", job_block("on-demand-e2e.yml", "e2e"))

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

    def test_gate_attestation_never_executes_the_release_checkout(self) -> None:
        verify = job_block("verify-gate-attestation.yml", "verify")

        self.assertIn("ref: master", verify)
        self.assertIn("path: controller", verify)
        self.assertNotIn("ref: ${{ inputs.target_sha }}", verify)
        self.assertIn("source controller/scripts/ci/github_api_retry.sh", verify)
        self.assertIn("git/commits/$TESTED_SHA", verify)
        self.assertIn("git/commits/$TARGET_SHA", verify)
        self.assertIn("compare/$TESTED_SHA...$TARGET_SHA", verify)
        self.assertIn('.merge_base_commit.sha == $tested', verify)
        self.assertIn('.commits[-1].sha == $target', verify)
        self.assertNotIn("python3 scripts/", verify)
        self.assertNotIn("git checkout", verify)
        self.assertIn('GITHUB_API_RETRY_MAX_WAIT_SECONDS: "3700"', verify)

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
        self.assertEqual(package["dependencies"]["@anthropic-ai/claude-code"], "2.1.280")
        locked = lock["packages"]["node_modules/@anthropic-ai/claude-code"]
        self.assertEqual(locked["version"], "2.1.280")
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
        capacity = job_block("visual-review-drain.yml", "capacity-probe")
        self.assertIn("TRUSTED_SHA: ${{ github.sha }}", sync)
        self.assertIn("branches/master", repair)
        self.assertIn("ref: ${{ needs.select.outputs.implementation_sha }}", visual)
        self.assertIn("npm ci --ignore-scripts", visual)
        self.assertIn(
            "node node_modules/@anthropic-ai/claude-code/install.cjs", visual
        )
        self.assertIn("node_modules/.bin/claude --version", visual)
        self.assertIn("ref: ${{ github.sha }}", capacity)
        self.assertIn("npm ci --ignore-scripts", capacity)
        self.assertIn(
            "node node_modules/@anthropic-ai/claude-code/install.cjs", capacity
        )
        self.assertIn("node_modules/.bin/claude --version", capacity)

    def test_marketplace_jobs_receive_only_the_selected_secret(self) -> None:
        for filename in ("release.yml", "release-recovery.yml"):
            with self.subTest(workflow=filename):
                workflow = (WORKFLOWS / filename).read_text(encoding="utf-8")
                self.assertEqual(
                    workflow.count(
                        "MODRINTH_TOKEN: ${{ matrix.marketplace == 'modrinth' "
                        "&& secrets.MODRINTH_TOKEN || '' }}"
                    ),
                    2,
                )
                # Only the upload action needs the CurseForge credential.
                self.assertNotIn("CURSEFORGE_TOKEN: ", workflow)
                self.assertEqual(
                    workflow.count("curseforge-token: ${{ secrets.CURSEFORGE_TOKEN }}"), 1
                )

    def test_release_queues_preserve_pending_work_and_only_writers_share_the_lock(self) -> None:
        release = (WORKFLOWS / "release.yml").read_text(encoding="utf-8")
        for filename in ("release.yml", "release-recovery.yml", "release-verify.yml"):
            text = (WORKFLOWS / filename).read_text(encoding="utf-8")
            self.assertEqual(text.count("concurrency:"), text.count("queue: max"))
            self.assertEqual(text.count("concurrency:"), text.count("cancel-in-progress: false"))
        for job in ("build", "runtime-behavior"):
            block = job_block("release.yml", job)
            self.assertNotIn("group: release-publish", block)
            self.assertIn("runs-on: ubuntu-24.04", block)
        self.assertIn("release_schedule.py", release)
        self.assertIn("release-build-${{ needs.admit.outputs.slot }}", release)
        self.assertIn("release-runtime-${{ needs.build.outputs.preparation_slot }}", release)
        for filename in ("release.yml", "release-recovery.yml"):
            for job in ("stage-github-release", "publish-marketplace", "publish-github-release"):
                block = job_block(filename, job)
                self.assertIn("group: release-publish", block)
                self.assertIn("environment: release", block)

    def test_upload_intent_precedes_external_write_and_moderation_has_no_poll_loop(self) -> None:
        for filename in ("release.yml", "release-recovery.yml"):
            workflow = (WORKFLOWS / filename).read_text(encoding="utf-8")
            block = job_block(filename, "publish-marketplace")
            self.assertLess(block.index("publication_state.py begin"), block.index("uses: Kira-NT/mc-publish@"))
            self.assertGreater(block.index("publication_state.py accept"), block.index("uses: Kira-NT/mc-publish@"))
            self.assertNotIn("--attempts", block)
            self.assertNotIn("--delay-seconds", block)
            self.assertIn("publication_state.py register", job_block(filename, "stage-github-release"))
            final = job_block(filename, "publish-github-release")
            self.assertIn("publication_state.py check", final)
            self.assertIn("if: ${{ steps.settled.outputs.ready == 'true' }}", final)
            self.assertIn("Rehearse publication and interrupted recovery", workflow)
            self.assertLess(workflow.index("rehearse_publication.py"), workflow.index("uses: actions/attest@"))

    def test_pending_verification_is_secretless_and_finalization_remains_protected(self) -> None:
        workflow = (WORKFLOWS / "release-verify.yml").read_text(encoding="utf-8")
        probe = job_block("release-verify.yml", "verify")
        final = job_block("release-verify.yml", "finalize")
        self.assertIn("workflow_run:", workflow)
        self.assertIn("schedule:", workflow)
        self.assertNotIn("secrets.", workflow)
        self.assertNotIn("mc-publish", workflow)
        self.assertNotIn("contents: write", probe)
        self.assertNotIn("environment:", probe)
        self.assertNotIn("--finalize", probe)
        self.assertIn("environment: release", final)
        self.assertIn("group: release-publish", final)
        self.assertIn("--finalize", final)
        for block in (probe, final):
            self.assertIn("ref: ${{ github.sha }}", block)
            self.assertIn("persist-credentials: false", block)
            self.assertIn("verify_pending_publications.py", block)

    def test_curseforge_upload_is_never_retried_inside_the_action(self) -> None:
        for filename in ("release.yml", "release-recovery.yml"):
            with self.subTest(workflow=filename):
                workflow = (WORKFLOWS / filename).read_text(encoding="utf-8")

                def publish_step(marketplace: str) -> str:
                    tail = workflow.split(
                        f"Publish exact verified artifact to {marketplace}", 1
                    )[1]
                    return tail.split("- name: ", 1)[0]

                # CurseForge approves asynchronously; only the reconciler may retry.
                self.assertIn("retry-attempts: 1", publish_step("CurseForge"))
                # Modrinth rejects duplicates synchronously.
                self.assertIn("retry-attempts: 3", publish_step("Modrinth"))

    def test_sbom_recovery_preserves_source_identity_and_publication_boundaries(self) -> None:
        workflow = (WORKFLOWS / "release-recovery.yml").read_text(encoding="utf-8")
        prepare = job_block("release-recovery.yml", "prepare")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("group: release", workflow)
        self.assertIn("cancel-in-progress: false", workflow)
        self.assertIn("recover_sbom_release.py prepare", prepare)
        self.assertIn("SOURCE_RUN_ID: ${{ inputs.source_run_id }}", prepare)
        self.assertIn("SOURCE_ARTIFACT_ID: ${{ inputs.source_artifact_id }}", prepare)
        self.assertNotIn("secrets.", prepare)
        self.assertIn("sbom-path: build/release/sbom/quick-skin.cdx.json", prepare)
        for name in ("stage-github-release", "publish-marketplace", "publish-github-release"):
            block = job_block("release-recovery.yml", name)
            self.assertIn("environment: release", block)
            self.assertIn("recover_sbom_release.py verify", block)
            self.assertIn("MANIFEST_SHA256: ${{ needs.prepare.outputs.manifest_sha256 }}", block)
            self.assertIn("fetch-tags: true", block)
            self.assertIn("persist-credentials: false", block)
        for name in ("stage-github-release", "publish-github-release"):
            block = job_block("release-recovery.yml", name)
            self.assertIn('--commit "${{ needs.prepare.outputs.source_sha }}"', block)
            self.assertNotIn('--commit "$GITHUB_SHA"', block)


if __name__ == "__main__":
    unittest.main()
