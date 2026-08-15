from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
LOCAL_LINK = re.compile(r"\[[^\]]+\]\((?!https?://)([^)#]+)(?:#[^)]+)?\)")
AGENT_IMPORTS = (
    "docs/ai/PROJECT.md",
    "docs/ai/SOURCE-ARCHITECTURE.md",
    "docs/ai/RUNTIME-INVARIANTS.md",
    "docs/ai/WORKFLOW.md",
)


class RepositoryGuidanceTest(unittest.TestCase):
    def test_claude_is_only_the_agents_redirect(self) -> None:
        self.assertEqual(
            (ROOT / "CLAUDE.md").read_text(encoding="utf-8"),
            "@AGENTS.md\n",
        )

    def test_agents_is_only_a_complete_import_manifest(self) -> None:
        agents = (ROOT / "AGENTS.md").read_text(encoding="utf-8")
        self.assertEqual(
            agents,
            "".join(f"@{path}\n" for path in AGENT_IMPORTS),
        )
        for path in AGENT_IMPORTS:
            with self.subTest(path=path):
                self.assertTrue((ROOT / path).is_file())

    def test_human_and_agent_entry_points_are_linked(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        contributing = (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8")
        build_gate = (ROOT / ".github" / "workflows" / "build-gate.yml").read_text(
            encoding="utf-8"
        )
        pull_request_template = (
            ROOT / ".github" / "pull_request_template.md"
        ).read_text(encoding="utf-8")

        self.assertIn("[CONTRIBUTING.md](CONTRIBUTING.md)", readme)
        self.assertIn("[AGENTS.md](AGENTS.md)", contributing)
        self.assertIn("CONTRIBUTING.md", pull_request_template)
        self.assertIn("AGENTS.md", pull_request_template)
        self.assertIn(
            "python -m unittest discover -s scripts/release/tests",
            build_gate,
        )

    def test_release_badges_are_backed_by_exact_tree_attestations(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        build_gate = (ROOT / ".github" / "workflows" / "build-gate.yml").read_text(
            encoding="utf-8"
        )
        e2e_gate = (
            ROOT / ".github" / "workflows" / "on-demand-e2e.yml"
        ).read_text(encoding="utf-8")
        handler = (
            ROOT / ".github" / "workflows" / "handle-version-port-result.yml"
        ).read_text(encoding="utf-8")
        attestation = (
            ROOT / ".github" / "workflows" / "verify-gate-attestation.yml"
        ).read_text(encoding="utf-8")
        refresh = (
            ROOT / ".github" / "workflows" / "refresh-release-status.yml"
        ).read_text(encoding="utf-8")

        self.assertEqual(readme.count("<!-- branch-profile:start -->"), 1)
        self.assertEqual(readme.count("<!-- branch-profile:end -->"), 1)
        self.assertEqual(readme.count("<!-- release-status:start -->"), 1)
        self.assertEqual(readme.count("<!-- release-status:end -->"), 1)
        self.assertIn("uses: ./.github/workflows/verify-gate-attestation.yml", build_gate)
        self.assertIn("uses: ./.github/workflows/verify-gate-attestation.yml", e2e_gate)
        self.assertIn("gh workflow run build-gate.yml --ref \"$target_branch\"", handler)
        self.assertIn("gh workflow run on-demand-e2e.yml --ref \"$target_branch\"", handler)
        self.assertIn("git/commits/$TESTED_SHA", attestation)
        self.assertIn("git/commits/$TARGET_SHA", attestation)
        self.assertIn("compare/$TESTED_SHA...$TARGET_SHA", attestation)
        self.assertNotIn("ref: ${{ inputs.target_sha }}", attestation)
        self.assertIn("scripts/release/status_table.py", refresh)

    def test_shared_delivery_and_ephemeral_worktrees_are_explicit(self) -> None:
        project = (ROOT / "docs" / "ai" / "PROJECT.md").read_text(
            encoding="utf-8"
        )
        workflow = (ROOT / "docs" / "ai" / "WORKFLOW.md").read_text(
            encoding="utf-8"
        )
        version_branches = (ROOT / "VERSION-BRANCHES.md").read_text(
            encoding="utf-8"
        )
        contributing = (ROOT / "CONTRIBUTING.md").read_text(encoding="utf-8")

        self.assertIn("every discovered release branch", project)
        self.assertIn("exact-head Build", project)
        self.assertIn("intentional branch exclusion", project)
        self.assertIn("separate ephemeral Git worktree", workflow)
        self.assertIn("never use `--force`", workflow)
        self.assertIn("scripts/release/branch_readme.py", workflow)
        for command in (
            "mktemp -d",
            "git worktree add --detach",
            "git worktree add -b",
            'git worktree remove "$qsm_worktree_path"',
        ):
            with self.subTest(command=command):
                self.assertIn(command, version_branches)
        self.assertRegex(contributing, r"per\s+discovered\s+release branch")
        self.assertRegex(contributing, r"separate ephemeral\s+worktree")

    def test_gui_compositing_producer_and_consumer_share_the_vanilla_boundary(self) -> None:
        player_widget = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "client"
            / "gui"
            / "widget"
            / "PlayerWidget.java"
        ).read_text(encoding="utf-8")
        client_events = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "event"
            / "ClientEvents.java"
        ).read_text(encoding="utf-8")

        self.assertRegex(
            player_widget,
            r"//\? if <1\.21\.6 \{\s+"
            r"private static final PreviewCompositeOrder\.Pipeline GUI_PIPELINE",
        )
        self.assertRegex(
            client_events,
            r"//\? if <1\.21\.6 \{\s+//\?\} else \{\s+"
            r"ClientGuiEvent\.RENDER_POST\.register",
        )

    def test_legacy_preview_equipment_hooks_the_method_owner(self) -> None:
        mixins = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "mixin"
            / "PreviewEquipmentMixin.java",
            ROOT
            / "neoforge"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "neoforge"
            / "mixin"
            / "PreviewEquipmentMixin.java",
        )

        for path in mixins:
            with self.subTest(path=path.relative_to(ROOT).as_posix()):
                source = path.read_text(encoding="utf-8")
                self.assertNotIn(
                    "import net.minecraft.world.entity.LivingEntity;", source
                )
                self.assertIn("@Mixin(Player.class)", source)
                self.assertRegex(source, r"\(Player\) \(Object\) this")
                self.assertRegex(
                    source,
                    r"cancellable = true,\s+require = 0,\s+expect = 1,\s+allow = 1",
                )

    def test_background_layers_follow_the_gui_batching_boundary(self) -> None:
        source = (
            ROOT
            / "common"
            / "src"
            / "main"
            / "java"
            / "com"
            / "quickskin"
            / "mod"
            / "client"
            / "gui"
            / "util"
            / "BackgroundRenderer.java"
        ).read_text(encoding="utf-8")

        self.assertRegex(
            source,
            r"//\? if <1\.21\.2 \{\s+"
            r"RenderSystem\.enableBlend\(\);[\s\S]*?"
            r"//\?\} else if <1\.21\.11 \{\s+"
            r"// GuiGraphics is batched from 1\.21\.2 onward, so keep the tint "
            r"on each queued vertex\.\s+"
            r"int vignetteColor = 0xBF000000;\s+"
            r"graphics\.blit\(RenderType::guiTextured, VIGNETTE_LOCATION,",
        )
        self.assertRegex(
            source,
            r"//\? if <1\.21\.2 \{\s+"
            r"var pose = graphics\.pose\(\);[\s\S]*?"
            r"//\?\} else if <1\.21\.11 \{\s+"
            r"// Keep the black fill, stars, and vignette in GuiGraphics' ordered buffer\.\s+"
            r"graphics\.blit\(RenderType::guiTextured, starTexture,",
        )

    def test_release_publication_is_recoverable_and_non_destructive(self) -> None:
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(
            encoding="utf-8"
        )
        release_helper = (
            ROOT / "scripts" / "release" / "github_release.py"
        ).read_text(encoding="utf-8")
        release_identity = (
            ROOT / "scripts" / "release" / "release_identity.py"
        ).read_text(encoding="utf-8")

        self.assertIn('tags:\n      - "mc*-v*"', workflow)
        self.assertIn("--kind publications", workflow)
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("github_release.py stage", workflow)
        self.assertIn("github_release.py publish", workflow)
        self.assertIn("reconcile_publication.py", workflow)
        self.assertIn("verify_reproducibility.py", workflow)
        self.assertIn("--rerun-tasks", workflow)
        self.assertIn("validate_changelog", release_identity)
        attest_pin = "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6"
        self.assertEqual(workflow.count(attest_pin), 2)
        self.assertIn("sbom-path: build/release/sbom/quick-skin.cdx.json", workflow)
        combined = workflow + release_helper
        self.assertNotIn("gh release delete", combined)
        self.assertNotIn("git push --delete", combined)
        self.assertNotIn("gh release upload --clobber", combined)

    def test_github_governance_is_declarative_and_fail_closed(self) -> None:
        config = (
            ROOT / "release" / "github-governance.json"
        ).read_text(encoding="utf-8")
        helper = (
            ROOT / "scripts" / "release" / "github_governance.py"
        ).read_text(encoding="utf-8")

        self.assertIn('"Build and verify"', config)
        self.assertIn('"Packaged E2E gate"', config)
        self.assertIn('"prevent_self_review": false', config)
        self.assertIn('"deployment_policies"', config)
        self.assertIn("refusing to activate governance", helper)
        self.assertIn("contains unmanaged deployment policies", helper)
        self.assertNotIn("rulesets/{ruleset_id}", helper)
        self.assertNotIn("--method DELETE", helper)

    def test_dependency_updates_are_reviewed_pull_requests(self) -> None:
        dependabot = (ROOT / ".github" / "dependabot.yml").read_text(
            encoding="utf-8"
        )
        for ecosystem in ("github-actions", "gradle", "npm"):
            with self.subTest(ecosystem=ecosystem):
                self.assertIn(f"package-ecosystem: {ecosystem}", dependabot)
        self.assertIn("directory: /.github/claude", dependabot)
        self.assertIn("interval: monthly", dependabot)

    def test_new_guidance_has_no_broken_local_links(self) -> None:
        decision_documents = tuple(
            sorted((ROOT / "docs" / "architecture" / "decisions").glob("*.md"))
        )
        documents = (
            ROOT / "README.md",
            ROOT / "CONTRIBUTING.md",
            ROOT / "VERSION-BRANCHES.md",
            ROOT / ".github" / "pull_request_template.md",
            *(ROOT / path for path in AGENT_IMPORTS),
            *decision_documents,
        )
        for document in documents:
            text = document.read_text(encoding="utf-8")
            for target in LOCAL_LINK.findall(text):
                with self.subTest(document=document.name, target=target):
                    self.assertTrue((document.parent / target).resolve().is_file())


if __name__ == "__main__":
    unittest.main()
