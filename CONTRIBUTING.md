# Contributing to Quick Skin

You do not need to know the whole project before contributing. This guide takes you from a fresh
fork to a pull request and shows how to use a coding assistant without letting it guess the
project's rules.

Quick Skin is source-available, not open source. You may fork it to submit pull requests, but you
may not redistribute the source or publish modified builds. Read [LICENSE](LICENSE) before
contributing; submitting a pull request accepts its contribution terms.

## The instruction documents to know

- [README.md](README.md) explains what the mod does and how to build it.
- [AGENTS.md](AGENTS.md) is an import-only manifest. Every `@path.md` listed there is part of the
  authoritative instruction set for coding assistants. Its first two imports, under
  `docs/ai/shared/`, are shared with the team's other mods through the
  [mod-base](https://github.com/The-Plum-Team/mod-base) kit; the four `docs/ai/` files after them
  hold Quick Skin's own rules.
- [VERSION-BRANCHES.md](VERSION-BRANCHES.md) explains shared-source development and historical branch recovery.
- [RELEASING.md](RELEASING.md) explains immutable publication and repository governance.
- [DEPENDENCY-SECURITY.md](DEPENDENCY-SECURITY.md) explains repository routing, checksums, and locks.
- [e2e/README.md](e2e/README.md) describes the packaged Minecraft tests used by CI.

The repository deliberately has no `CLAUDE.md`. Claude Code 2.1.277 or later reads `AGENTS.md`
directly, including its imports of the focused files under `docs/ai/`, so Claude gets the same
modular rules as other agents without a duplicate copy. Adding a `CLAUDE.md`, `.claude/CLAUDE.md` or
`CLAUDE.local.md` would stop Claude from reading `AGENTS.md`. Where Claude cannot read `AGENTS.md`
directly (for example on Amazon Bedrock or with telemetry disabled), import it from a personal
`~/.claude/CLAUDE.md` instead. Do not put rules directly in `AGENTS.md`.

## 1. Choose the correct base branch

All new changes target `master`, including a fix for one Minecraft version or loader. The
schema-3 release matrix builds every supported target from that source; publication remains
independent for each Minecraft target.

| Your change | Owning definition |
|---|---|
| Feature behavior | Its compiled module in `architecture/modules.json` |
| Minecraft API difference | The selected API-family implementation in `minecraft-adapter`, or the owning loader/mixin bridge |
| Feature interaction or dependency | The module graph's dependency/binding and scenario coverage declarations |
| New Minecraft version or loader | `release/release-matrix.json` and the required adapter/bootstrap inputs |

Add a version by extending the matrix and selecting or adapting its API family. Keep differences
inside the smallest owning boundary. Existing version branches and `automation/sync/*` are
historical schema-2 inputs; the shared-source controller creates no new port branches.

The README support/status and profile blocks, the E2E README profile, and the imported workflow
commands are generated from the matrix and scenario contract. Update those definitions, then run
`scripts/release/branch_readme.py`, `e2e_readme.py`, and `workflow_guidance.py` with
`--profile-branch master --write` as appropriate. Never edit a generated block by hand.

A change is validated across its affected module consumers and runtime bindings. Unknown impact,
a changed selection policy, or unavailable complete baseline evidence requires the full profile.
CI still builds every target and keeps the complete required lane gate; a valid feature selection
reduces executed assertions, generated captures, and AI inputs within those lanes.

Merging an already tested PR does not repeat its compilation or Minecraft matrix when the final
Git tree is identical. Small post-merge jobs authenticate the original successful gates, all
required lanes, and available artifacts, then pass references to visual review and Pages.
Repeated review notifications continue only with targets that have no existing capsule or report.
The original tested commit, run and JAR hashes remain attached to the evidence; the merge commit
is recorded separately as the covered generation. A changed tree or expired evidence requires
fresh work. An API failure or a still-running PR check stops admission instead of starting a
parallel replacement. Explicit `capture_coverage=full` requests still run the complete profile,
and so does any change whose modules can reach the optional-mod compatibility scenarios.

## 2. Prepare a checkout

Install these prerequisites:

- Git;
- JDK 21 or newer to launch Gradle and Stonecutter;
- Python 3.13 recommended for the release and E2E helper scripts.

The Gradle wrapper downloads Gradle itself, and Gradle toolchains select the Java version declared
by the active release matrix. A normal build does not require a globally installed Gradle.

Fork the repository on GitHub, then replace `YOUR-GITHUB-USER` below:

```bash
git clone https://github.com/YOUR-GITHUB-USER/Quick-Skin-Mod.git
cd Quick-Skin-Mod
git remote add upstream https://github.com/The-Plum-Team/Quick-Skin-Mod.git
git fetch upstream
```

Create a topic branch from `master`:

```bash
git switch --create fix/short-description upstream/master
```

If another task owns the current checkout, create a separate ephemeral worktree from fetched
`master`, including for a version-only fix. The commands below use a POSIX-compatible shell on macOS,
Linux, or Git Bash; replace the placeholders first:

```bash
topic_branch="fix/short-description"
git fetch upstream
qsm_worktree_root="$(mktemp -d "${TMPDIR:-/tmp}/quick-skin-worktree.XXXXXX")"
qsm_worktree_path="$qsm_worktree_root/checkout"
git worktree add -b "$topic_branch" "$qsm_worktree_path" \
  "upstream/master"
cd "$qsm_worktree_path"
```

Read `AGENTS.md`, all of its imports, and the active matrix again inside the worktree. Keep it while
the topic branch is in progress. After every valuable change belongs to that named branch and is
committed, pushed, or otherwise exported, return to the original checkout, require
`git status --short` in the worktree to be empty, then run
`git worktree remove "$qsm_worktree_path"` and `rmdir "$qsm_worktree_root"`. Never pass `--force`
to erase a dirty or unfamiliar worktree; a detached-HEAD commit alone is not preservation.

Use your own short branch description. Do not commit directly to `master` or a release branch in
your fork, and do not run Gradle concurrently in multiple worktrees.

## 3. Give an AI enough context

Coding agents should discover `AGENTS.md` automatically, but import behavior differs between tools.
Make the requirement explicit. A good starter prompt is:

```text
Open AGENTS.md and read every @-imported Markdown file completely, then read CONTRIBUTING.md and
the focused documentation relevant to this task. Inspect git status, the active release matrix,
canonical sources, and every active overlay before editing. Explain the intended
branch/version/loader scope, make the smallest coherent change, preserve unrelated work, do not
edit generated output, and run proportional checks. Do not commit, push, open a PR, weaken tests,
or change the support matrix unless I ask. If another release branch must be inspected or edited,
use a separate ephemeral worktree and never repurpose an existing checkout.

Task: <describe one concrete bug or feature, including how to reproduce it>
```

Help the agent by including:

- what you expected and what happened instead;
- the Minecraft version and loader;
- reproduction steps and the smallest relevant log excerpt;
- whether the change should apply to every matrix target or one Minecraft API family;
- screenshots when the problem is visual.

AI output is not automatically correct. Before accepting it:

- read the diff and ask the agent to explain unfamiliar code;
- reject fixes that delete tests, relax assertions, remove bounds, or hide exceptions;
- check that it changed canonical sources or active overlays, not generated output;
- never paste tokens, account credentials, signing keys, or private user data into a prompt;
- keep one issue per branch so failures and reviews remain understandable.

If an agent says that a generated file must be edited, stop and point it back to the source-set
architecture document imported by `AGENTS.md`.

## 4. Know where changes belong

| Area | Tracked source |
|---|---|
| Pure domain, protocol, and feature behavior | Its `modules/<owner>/src/main` tree |
| Minecraft API-family operations | `modules/minecraft-adapter/src/main` |
| Runtime assembly, event wiring, and mixin bridges | `common/src/main` |
| Dependencies, API providers, and feature ownership | `architecture/modules.json` |
| Fabric integration | `fabric/src/main` |
| Forge or NeoForge integration | The active loader module's `src/main` |
| Version API replacements | Matrix-declared `src/legacy*` overlays |
| Regression tests | The owning module's `src/test`, with integrated Minecraft checks in `common/src/test` |
| Packaged Minecraft test mod | `common/src/e2e` and loader `src/e2e` |
| E2E loader/bootstrap integrity | `e2e/loader-bootstrap-contract.json` |
| Supported artifacts and E2E lanes | `release/release-matrix.json` |
| Public-evidence adapter and site settings | `scripts/pages/mod_base_adapter.py` and `site/mod-base.json` |
| Managed mod-base files | Nowhere by hand: `scripts/ci/mod_base_kit.py bump` or `template sync` |

Do not edit or commit anything below generated `versions/` trees, `build/`, `.gradle/`,
`.architectury-transformer/`, `e2e-out/`, or `build/release/`. Minecraft runtime directories,
screenshots, caches, staged jars, and IDE output also stay untracked.

The managed mod-base files are byte-identical copies of the pinned kit:
`scripts/ci/mod_base_kit.py`, `.gitattributes`, `docs/ai/shared/REPOSITORY.md`,
`docs/ai/shared/PUBLIC-EVIDENCE.md`, and the marked managed region of `.github/workflows/pages.yml`.
A local edit fails `template check`; propose the change in mod-base instead.

When a class exists in both a canonical tree and an active overlay, changing only the canonical
file does not change the overlaid release. Search before editing:

```bash
rg "ClassName|methodName" --glob "*.java" .
```

Only loaders and overlays selected by a matrix target belong in that target's compiled output.
Changing an active loader's `src/e2e` entrypoint, manifest, or complete `build.gradle.kts` also
requires an intentional digest update in `e2e/loader-bootstrap-contract.json` on `master`; do not
weaken the validator or treat the final convention-apply line alone as sufficient.

## 5. Run proportional checks

Run the smallest useful test while developing. On Unix-like systems:

```bash
./gradlew --no-daemon --no-parallel -PquickskinTarget=1.20.1 testStableLane
```

On Windows, replace `./gradlew` with `.\gradlew.bat`.

Changes to build routing, loaders, resources, overlays, networking boundaries, or release output
need the aggregate gate:

```bash
python3 scripts/release/build_matrix.py --clean
```

The unit suites can be entirely green while a packaged scenario is broken, because they stand in
for the filesystem and loader contracts that only a real remapped Minecraft run exercises. For
harness, runtime-store, or dependency-installation changes, treat the packaged gate as the
authority rather than evidence that the Python tests pass.

Run the repository-level checks before handing work off:

```bash
git diff --check
python -m compileall -q e2e scripts
python scripts/release/e2e_readme.py \
  --matrix release/release-matrix.json \
  --contract e2e/scenario-contract.json \
  --readme e2e/README.md \
  --profile-branch master \
  --check
python scripts/release/workflow_guidance.py \
  --matrix release/release-matrix.json \
  --guidance docs/ai/WORKFLOW.md \
  --profile-branch master \
  --check
python scripts/ci/mod_base_kit.py verify --network
python scripts/ci/mod_base_kit.py run template check --repo .
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

The build gate runs those two suites through `scripts/ci/parallel_unittest.py`, which takes the
same `-s`/`-p` arguments, spreads one discovery across every core, and fails if any discovered
test is lost, fails, errors or unexpectedly succeeds. Use it locally for a faster equivalent run.

`verify --network` checks that every mod-base reference carries the one pin and that the pin is a
released kit tag reachable from mod-base `main`; `template check` checks the managed files and the
required lines of `AGENTS.md`, `.github/CODEOWNERS`, `.github/dependabot.yml`, `.gitignore` and the
pull-request template. Tests that need the kit fail rather than skip when it is unavailable: the
first run fetches the pinned kit into your user cache, which needs anonymous HTTPS access to
GitHub once.

Kit bumps are their own draft pull request. On a fresh branch run
`python scripts/ci/mod_base_kit.py bump --to vX.Y.Z`, review the whole diff, commit it as
`ci: bump mod-base to vX.Y.Z` and open it as a draft to `master`; maintainers land it through a
batch pull request. A bump runs every Build and Packaged E2E lane. Dependabot is configured to
ignore the kit and `actions/deploy-pages`, which the managed `pages.yml` pins; close any Dependabot
pull request that touches a mod-base reference or the managed part of `pages.yml`.

Do not run multiple Gradle commands at the same time on one machine. Architectury's transforms share
JVM-global state, so local aggregate builds remain serial. GitHub compiles separate targets on
isolated runners, then verifies the complete set before passing Build. Packaged E2E reuses that
exact compilation; changes within one feature can then reduce its authenticated scenario scope.

You normally do not need to launch packaged Minecraft E2E locally. Use the `capture_coverage=full`
manual recovery option when complete coverage is needed; optional-mod profiles remain complete
integration checks. Once a pull request is ready for review, its workflow
builds immutable jars and runs the declared scenarios on GitHub. A draft pull request to `master`
runs neither, so it gives no build or runtime feedback until it is marked ready. Fork pull requests do not receive
repository secrets, so secret-dependent AI review may be skipped while programmatic checks still
run.

## 6. Review and commit

Keep one logical concern per commit. Subjects use the conventional form already present in the
history:

| Prefix | Use it for |
|---|---|
| `feat:` | User-visible capability |
| `fix:` | Bug or regression |
| `refactor:` | Behavior-preserving code structure |
| `test:` | Test-only changes |
| `build:` | Gradle, matrix, dependency, or artifact routing |
| `ci:` | GitHub Actions and automation |
| `docs:` | Documentation only |
| `chore:` | Maintenance that fits no category above |

Use an imperative, specific subject, for example:

```text
fix: retain typed cape cache identity
docs: explain AI-assisted contribution flow
```

Review exactly what will be committed:

```bash
git status --short
git diff --check
git diff
git add path/to/intended-file
git diff --cached --check
git diff --cached
git commit -m "fix: describe the actual change"
```

Ask an AI to commit only after you have reviewed the staged diff. Do not ask it to force-push,
rewrite someone else's commits, or bundle unrelated cleanup into your change.

## 7. Update your branch safely

Before the first push, update an unpublished shared-change branch with:

```bash
git fetch upstream
git rebase upstream/master
```

Version-only fixes also use `master`. If the rebase becomes confusing, return to the pre-rebase
state safely:

```bash
git rebase --abort
```

After a PR is already open, avoid rewriting its history if you are unfamiliar with force pushes.
Use GitHub's **Update branch** button when available, or merge the base branch normally:

```bash
git fetch upstream
git merge --no-edit upstream/master
git push origin HEAD
```

Again, substitute the PR's release base when it does not target `master`.

## 8. Open the pull request

Push the topic branch to your fork and open a PR against the base chosen in step 1:

```bash
git push --set-upstream origin HEAD
```

Use the pull-request template. A reviewer should be able to determine:

- why the change is needed;
- which versions and loaders it affects;
- whether canonical and overlay implementations agree;
- which commands you ran and their outcomes;
- what you did not test;
- whether AI materially authored or reviewed the change;
- whether the release matrix or generated output changed.

Use a conventional PR title such as `fix: reject an oversized texture before allocation`.
Draft PRs are welcome when you want early help. A draft to `master` starts no Build or packaged
E2E work, and it cannot be merged; mark it ready for review to run the complete gate. Maintainers
can instead validate several drafts together through one batch PR, as described in
[PR batches](docs/ci/PR-BATCHES.md). Include concise logs as text or an attachment rather than
committing runtime directories.

CI is part of the review. A green compile alone is not enough for runtime-sensitive changes: wait
for the packaged E2E result. Do not work around a failure by weakening a gate. Explain the failure,
fix its cause, and push another commit.

## Getting help

Open an issue when the correct branch, source owner, or expected behavior is unclear. Include the
version, loader, reproduction steps, relevant logs, and whether you want to work on the fix. It is
better to ask before a large migration than to make an AI rewrite several source trees on an
incorrect assumption.
