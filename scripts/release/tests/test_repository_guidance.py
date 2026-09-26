from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
LOCAL_LINK = re.compile(r"\[[^\]]+\]\((?!https?://)([^)#]+)(?:#[^)]+)?\)")
# The mod-base kit manages the two shared documents and requires them first (mod-base SPEC 8.3);
# the remaining imports are site/mod-base.json template.agents_local, in order.
SHARED_AGENT_IMPORTS = (
    "docs/ai/shared/REPOSITORY.md",
    "docs/ai/shared/PUBLIC-EVIDENCE.md",
)
LOCAL_AGENT_IMPORTS = (
    "docs/ai/PROJECT.md",
    "docs/ai/SOURCE-ARCHITECTURE.md",
    "docs/ai/RUNTIME-INVARIANTS.md",
    "docs/ai/WORKFLOW.md",
)
AGENT_IMPORTS = (*SHARED_AGENT_IMPORTS, *LOCAL_AGENT_IMPORTS)
MOD_BASE_DEPENDENCY = "The-Plum-Team/mod-base*"
CODE_OWNER = "@AkaNebur"
CODE_OWNED_PATHS = (
    "/.github/",
    "/site/",
    "/scripts/pages/",
    "/scripts/ci/",
    "/AGENTS.md",
    "/docs/ai/",
)
# Generic Pages code that mod-base replaced (ADR 0010); local guidance must not describe it. The
# module names are matched without their directory so a shortened reference is caught too.
RETIRED_PAGES_REFERENCES = (
    "build_site.py",
    "scripts/pages/evidence.py",
    "select_artifact.py",
    "select_compatibility_artifact.py",
    "rotate_artifacts.py",
    "publication_progress.py",
    "site/assets/",
    "--allow-continuation",
)
# The Build `policy` job runs these; both verification guides must keep them runnable.
MOD_BASE_VERIFICATION_COMMANDS = (
    "python scripts/ci/mod_base_kit.py verify --network",
    "python scripts/ci/mod_base_kit.py run template check --repo .",
)
MOD_BASE_BUMP = "scripts/ci/mod_base_kit.py bump --to vX.Y.Z"
FENCED_BLOCK = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)


def instruction_set() -> str:
    return "".join(
        (ROOT / path).read_text(encoding="utf-8") for path in AGENT_IMPORTS
    )


class RepositoryGuidanceTest(unittest.TestCase):
    def test_no_claude_file_shadows_the_agents_manifest(self) -> None:
        # Claude Code reads AGENTS.md itself only while none of these files exists.
        for name in ("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md"):
            with self.subTest(name=name):
                self.assertFalse((ROOT / name).exists())

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
            "python scripts/ci/parallel_unittest.py -s scripts/release/tests",
            build_gate,
        )
        self.assertIn("scripts/ci/parallel_unittest.py", contributing)

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
        normalized_handler = re.sub(r"[ \t]*\\\n[ \t]*", " ", handler)
        self.assertIn(
            'gh workflow run build-gate.yml --ref "$target_branch"',
            normalized_handler,
        )
        self.assertIn(
            'gh workflow run on-demand-e2e.yml --ref "$target_branch"',
            normalized_handler,
        )
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

        instructions = instruction_set()

        self.assertIn("New work targets `master`", project)
        self.assertIn("architecture/modules.json", project)
        self.assertIn("complete required target/loader gate", project)
        # The worktree rules are generic and live in the managed shared contract.
        self.assertIn("separate ephemeral Git worktree", instructions)
        self.assertIn("never use `--force`", instructions)
        self.assertIn("shared/REPOSITORY.md", workflow)
        self.assertIn("scripts/release/branch_readme.py", workflow)
        for command in (
            "mktemp -d",
            "git worktree add --detach",
            "git worktree add -b",
            'git worktree remove "$qsm_worktree_path"',
        ):
            with self.subTest(command=command):
                self.assertIn(command, version_branches)
        self.assertIn("All new changes target `master`", contributing)
        self.assertIn("including a fix for one Minecraft version or loader", contributing)
        self.assertRegex(contributing, r"separate ephemeral\s+worktree")

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
        state = (ROOT / "scripts" / "release" / "publication_state.py").read_text(encoding="utf-8")
        self.assertIn("publication_state.py begin", workflow)
        self.assertIn("publication_state.py accept", workflow)
        self.assertIn("publication_state.py check", workflow)
        self.assertIn("from reconcile_publication import", state)
        self.assertIn("rehearse_publication.py", workflow)
        self.assertIn("verify_reproducibility.py", workflow)
        self.assertIn("--rerun-tasks", workflow)
        self.assertIn("validate_changelog", release_identity)
        attest_pin = "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6"
        self.assertEqual(workflow.count(attest_pin), 2)
        self.assertIn("sbom-path: build/release/sbom/quick-skin.cdx.json", workflow)
        combined = workflow + release_helper + state
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

    def test_dependabot_leaves_the_mod_base_pin_to_the_kit_bump(self) -> None:
        # The kit is pinned by SHA and moved only by `mod_base_kit.py bump`, which also
        # resynchronizes the managed files; mod-base `template check` requires this ignore.
        dependabot = (ROOT / ".github" / "dependabot.yml").read_text(
            encoding="utf-8"
        )
        updates = dependabot.split("\n  - package-ecosystem: ")[1:]
        actions = [update for update in updates if update.startswith("github-actions\n")]
        self.assertEqual(len(actions), 1)
        # Every other action pinned inside the managed region of pages.yml is kit-owned too: a
        # Dependabot bump of it would be byte drift that `template check` rejects.
        caller = (ROOT / ".github" / "workflows" / "pages.yml").read_text(encoding="utf-8")
        managed = caller.split("# <<< mod-base managed\n", 1)[0]
        managed_actions = sorted(
            set(re.findall(r"^\s*(?:-\s+)?uses:\s+([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:/[^@\s]*)?@",
                           managed, re.MULTILINE))
            - {"The-Plum-Team/mod-base"}
        )
        self.assertIn("actions/deploy-pages", managed_actions)
        self.assertIn(
            "\n    ignore:\n"
            f'      - dependency-name: "{MOD_BASE_DEPENDENCY}"\n'
            + "".join(f'      - dependency-name: "{name}"\n' for name in managed_actions)
            + "    groups:\n",
            actions[0],
        )
        self.assertEqual(dependabot.count(MOD_BASE_DEPENDENCY), 1)

    def test_protected_paths_keep_a_code_owner(self) -> None:
        rules: dict[str, list[str]] = {}
        codeowners = (ROOT / ".github" / "CODEOWNERS").read_text(encoding="utf-8")
        for line in codeowners.splitlines():
            tokens = line.split("#", 1)[0].split()
            if not tokens:
                continue
            with self.subTest(rule=tokens[0]):
                # An owner-less rule silently removes ownership from every path it matches.
                self.assertGreater(len(tokens), 1)
                self.assertNotIn(tokens[0], rules)
            rules[tokens[0]] = tokens[1:]
        for pattern in CODE_OWNED_PATHS:
            with self.subTest(pattern=pattern):
                self.assertEqual(rules.get(pattern), [CODE_OWNER])

    def test_verification_guides_keep_the_mod_base_checks_and_bump_route(self) -> None:
        for guide in (ROOT / "docs" / "ai" / "WORKFLOW.md", ROOT / "CONTRIBUTING.md"):
            text = guide.read_text(encoding="utf-8")
            commands = {
                line.strip()
                for block in FENCED_BLOCK.findall(text)
                for line in block.splitlines()
            }
            for command in MOD_BASE_VERIFICATION_COMMANDS:
                with self.subTest(guide=str(guide.relative_to(ROOT)), command=command):
                    self.assertIn(command, commands)
            with self.subTest(guide=str(guide.relative_to(ROOT)), route="bump"):
                self.assertIn(MOD_BASE_BUMP, text)

    def test_local_guidance_names_no_retired_pages_code(self) -> None:
        # Decision records keep their history, and the kit owns docs/ai/shared/*; every other
        # document under docs/ is Quick Skin's current guidance.
        records = ROOT / "docs" / "architecture" / "decisions"
        managed = ROOT / "docs" / "ai" / "shared"
        documents = (
            ROOT / "README.md",
            ROOT / "CONTRIBUTING.md",
            ROOT / "e2e" / "README.md",
            *(
                document
                for document in sorted((ROOT / "docs").rglob("*.md"))
                if records not in document.parents and managed not in document.parents
            ),
        )
        for path in LOCAL_AGENT_IMPORTS:
            self.assertIn(ROOT / path, documents)
        self.assertIn(ROOT / "docs" / "architecture" / "PUBLIC-EVIDENCE-TARGETS.md", documents)
        self.assertIn(ROOT / "docs" / "ci" / "PAGES-PUBLICATION-PROGRESS.md", documents)
        for document in documents:
            text = document.read_text(encoding="utf-8")
            for retired in RETIRED_PAGES_REFERENCES:
                with self.subTest(
                    document=str(document.relative_to(ROOT)), retired=retired
                ):
                    self.assertNotIn(retired, text)

    def test_new_guidance_has_no_broken_local_links(self) -> None:
        # Every document under docs/ is scanned, including the managed docs/ai/shared/*.md and
        # the docs/ci pointers that older records still link to.
        documents = tuple(
            dict.fromkeys(
                (
                    ROOT / "README.md",
                    ROOT / "CONTRIBUTING.md",
                    ROOT / "VERSION-BRANCHES.md",
                    ROOT / ".github" / "pull_request_template.md",
                    ROOT / "e2e" / "README.md",
                    *(ROOT / path for path in AGENT_IMPORTS),
                    *sorted((ROOT / "docs" / "ai" / "shared").glob("*.md")),
                    *sorted((ROOT / "docs").rglob("*.md")),
                )
            )
        )
        self.assertGreaterEqual(
            len(tuple((ROOT / "docs" / "ai" / "shared").glob("*.md"))),
            len(SHARED_AGENT_IMPORTS),
        )
        for document in documents:
            text = document.read_text(encoding="utf-8")
            for target in LOCAL_LINK.findall(text):
                with self.subTest(
                    document=str(document.relative_to(ROOT)), target=target
                ):
                    self.assertTrue((document.parent / target).resolve().is_file())


if __name__ == "__main__":
    unittest.main()
