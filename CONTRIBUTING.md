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
  authoritative instruction set for coding assistants.
- [VERSION-BRANCHES.md](VERSION-BRANCHES.md) explains how shared changes reach release branches.
- [RELEASING.md](RELEASING.md) explains immutable publication and repository governance.
- [DEPENDENCY-SECURITY.md](DEPENDENCY-SECURITY.md) explains repository routing, checksums, and locks.
- [e2e/README.md](e2e/README.md) describes the packaged Minecraft tests used by CI.

`CLAUDE.md` deliberately contains only `@AGENTS.md`, which in turn imports the focused files under
`docs/ai/`. This gives Claude the same modular rules as other agents without maintaining duplicate
copies. Do not put rules directly in either manifest.

## 1. Choose the correct base branch

The first decision is whether the change is shared or version-specific.

| Your change | Pull-request base |
|---|---|
| Shared behavior, security, tests, CI, build tooling, or general documentation | `master` |
| Only one Minecraft version or loader pair | That exact release branch |
| A new Minecraft version, loader, or release lane | Discuss it in an issue first; the release matrix must change first |

Release branches are named from their loader pair and Minecraft version, such as
`forge-and-fabric-1.20.1`. Check the current remote branch list instead of copying a version from
this guide:

```bash
git branch --remotes
```

Never work on `automation/sync/*`. GitHub Actions owns those temporary branches and deletes them
after their tested port PR is merged.

Do not edit either generated block in `README.md` by hand. Automation discovers release branches
and regenerates the release-status badges, while `scripts/release/branch_readme.py` derives the
current branch's identity, compatibility pins, and source-routing differences from its matrix. If
matrix-owned profile facts change, run that helper with `--profile-branch master` or the matrix's
exact `project.release_branch` and `--write`.

The marked profile in `e2e/README.md` is generated too. `scripts/release/e2e_readme.py` combines
lane facts from the active release matrix with suite facts from `e2e/scenario-contract.json`; the
version synchronizer regenerates it for each target branch. Edit the matrix or contract, not the
marked block.

The two active-common test commands in `docs/ai/WORKFLOW.md` are matrix-owned as well.
`scripts/release/workflow_guidance.py` renders their exact Minecraft version for each branch; the
synchronizer applies that protected renderer after any shared guidance merge.

Merge conflicts in protected version-owned files are not delegated to AI. The synchronizer uses a
reviewed merge controller and classifier to preserve the target matrix, keep inactive loader
modules absent, and merge shared runtime/guidance documents with source preference only at
conflicting hunks. It then regenerates all matrix-owned profiles. The complete proposal is opened
only in an alternate Git index; validator and writer independently reconstruct the original merge,
copy only recomputed AI-conflict entries, and require an exact final-tree match. Unknown protected
conflicts stop the port; Claude can see only the remaining unprotected conflict paths.

The current synchronizer attempts to port every new `master` change to every release branch. A
change described as “all versions except one” therefore needs an explicit design decision before
coding. Open an issue or draft PR stating the exclusion; do not let an AI hide the policy in many
scattered version conditions.

A shared change is delivered repository-wide only after one synchronization PR per discovered
release branch passes its exact-head Build and Packaged E2E gates, merges into that branch, and
receives successful final exact-tree attestations. Record every intentional exclusion and
outstanding port in the source pull request so an omitted branch is never mistaken for success.

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

Create a topic branch from the correct base. For a shared fix:

```bash
git switch --create fix/short-description upstream/master
```

For a version-only fix, keep the existing checkout where it is and create a separate ephemeral
worktree from the fetched release branch. The commands below use a POSIX-compatible shell on macOS,
Linux, or Git Bash; replace the placeholders first:

```bash
release_branch="<release-branch>"
topic_branch="fix/short-description"
git fetch upstream
qsm_worktree_root="$(mktemp -d "${TMPDIR:-/tmp}/quick-skin-worktree.XXXXXX")"
qsm_worktree_path="$qsm_worktree_root/checkout"
git worktree add -b "$topic_branch" "$qsm_worktree_path" \
  "upstream/$release_branch"
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
- whether the change should apply to every version or one release branch;
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
| Shared runtime and protocol behavior | `common/src/main` |
| Fabric integration | `fabric/src/main` |
| Forge or NeoForge integration | The active loader module's `src/main` |
| Version API replacements | Matrix-declared `src/legacy*` overlays |
| Loader-independent regression tests | `common/src/test` |
| Packaged Minecraft test mod | `common/src/e2e` and loader `src/e2e` |
| E2E loader/bootstrap integrity | `e2e/loader-bootstrap-contract.json` |
| Supported artifacts and E2E lanes | `release/release-matrix.json` |

Do not edit or commit anything below generated `versions/` trees, `build/`, `.gradle/`,
`.architectury-transformer/`, `e2e-out/`, or `build/release/`. Minecraft runtime directories,
screenshots, caches, staged jars, and IDE output also stay untracked.

When a class exists in both a canonical tree and an active overlay, changing only the canonical
file does not change the overlaid release. Search before editing:

```bash
rg "ClassName|methodName" --glob "*.java" .
```

Missing loader directories are normal on branches whose matrix does not support that loader.
Changing an active loader's `src/e2e` entrypoint, manifest, or complete `build.gradle.kts` also
requires an intentional digest update in `e2e/loader-bootstrap-contract.json` on `master`; do not
weaken the validator or treat the final convention-apply line alone as sufficient.

## 5. Run proportional checks

Run the smallest useful test while developing. On Unix-like systems:

```bash
./gradlew --no-daemon --no-parallel testStableLane
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
  --profile-branch "<master-or-exact-release-branch>" \
  --check
python scripts/release/workflow_guidance.py \
  --matrix release/release-matrix.json \
  --guidance docs/ai/WORKFLOW.md \
  --profile-branch "<master-or-exact-release-branch>" \
  --check
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

Do not run multiple Gradle commands at the same time. Architectury's transforms share JVM-global
state and this repository deliberately builds serially.

You normally do not need to launch packaged Minecraft E2E locally. The pull-request workflow
builds immutable jars and runs the declared scenarios on GitHub. Fork pull requests do not receive
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

Use the exact release branch instead of `master` for a version-only change. If the rebase becomes
confusing, return to the pre-rebase state safely:

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
Draft PRs are welcome when you want early help. Include concise logs as text or an attachment rather
than committing runtime directories.

CI is part of the review. A green compile alone is not enough for runtime-sensitive changes: wait
for the packaged E2E result. Do not work around a failure by weakening a gate. Explain the failure,
fix its cause, and push another commit.

## Getting help

Open an issue when the correct branch, source owner, or expected behavior is unclear. Include the
version, loader, reproduction steps, relevant logs, and whether you want to work on the fix. It is
better to ask before a large migration than to make an AI rewrite several source trees on an
incorrect assumption.
