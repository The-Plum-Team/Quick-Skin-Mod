# Editing and verification workflow

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Editing workflow

- Read `CONTRIBUTING.md` when preparing a human-facing branch, commit, or pull request.
- Read `release/release-matrix.json` and the relevant module `build.gradle.kts` before changing
  versions, loaders, source roots, resources, artifact tasks, or E2E coverage.
- Search canonical sources and all active overlays before changing a cross-version class or method.
- Preserve unrelated working-tree changes. Do not rewrite or delete user work to simplify a patch.
- Never switch or repurpose a user's existing checkout merely to inspect or edit a different branch.
  Fetch that remote branch and create a separate ephemeral Git worktree; inside it, reread
  `AGENTS.md`, every imported instruction, and that branch's release matrix before acting.
- Remove an ephemeral worktree only after `git status --short` is empty and every valuable change
  belongs to a named branch and is committed, pushed, or otherwise exported. A detached-HEAD commit
  alone is not preserved. Let `git worktree remove` refuse dirty trees; never use `--force` to erase
  a dirty or user-owned worktree. Discard work only with the user's explicit authorization.
- Do not commit generated JARs, staged release files, Minecraft runtime directories, screenshots,
  caches, or IDE output.
- Keep production and E2E JARs physically separate. The E2E harness may compile against main output
  but must never package Quick Skin production classes.
- Do not run multiple Gradle invocations concurrently. Architectury uses JVM-global transform state,
  and this repository intentionally disables parallel Gradle execution for aggregate builds.
- A workflow step that receives an AI credential must run the pinned CLI with safe mode, no session
  persistence or prompt history, `dontAsk`, an explicit shell-free `--tools` set, and scoped
  `Read`/`Edit`/`Write` permission rules. Install that CLI only from package and lock files
  materialized from the protected workflow SHA, with lifecycle scripts disabled and only the
  reviewed pinned installer invoked explicitly; never load project hooks, MCP, agent configuration,
  or package metadata from the release/topic checkout that supplies logs or source for analysis.
- Treat AI failure evidence as an adversarial payload. Authenticate the source run, cap its log,
  select only named artifacts by immutable numeric id, bound their count and compressed size, and
  extract them with the protected traversal/link/entry/expanded-byte validator. Grant the model
  read-only access to that evidence path; repair writes are positively limited to production
  `src/main` paths and cannot persist agent configuration.
- Never place raw visual artifacts in a credential-bearing job. Authenticate them, validate the
  protected lane graph, extract them with aggregate budgets, enforce exact matrix-row/scenario/JAR
  coverage, and canonicalize selected images in a prior secretless job. The fresh review capsule
  may expose only bounded manifest chunks and its curated image directory through a read-only model
  tool surface; the shell captures one private raw JSON result envelope per bounded call from model
  stdout. The pinned CLI must validate supported structural types, required keys, and
  manifest-bound label values against a protected schema derived from the exact chunk. Protected
  code must independently extract every result and enforce exact count, labels, bounds, and
  coherence before it emits the only normalized report eligible for upload. Durable queue state
  must not depend on a pending workflow run: a sanitized marker may cool a failed entry, raw
  provider text must never be uploaded, and a final `actions: write` job may delete only a
  completed handoff reauthenticated by exact id. A repository-wide capacity circuit may serialize
  only its tool-free preflight and marker publication; it must preserve parallel capsule review
  after a fresh ready marker, fail closed on unknown/permanent probe failures, and retain every
  capsule while an authenticated quota-pause marker is live. A successful tool-free call carrying
  `allowed` or `allowed_warning` is capacity-ready even when its optional coarse utilization field
  reports at least 95%; pause only for an explicit rejection or a failed probe classified as a
  transient quota/provider condition. A later rejected review remains fail-closed and retryable.
  Each optional-mod source must obtain a fresh serialized probe instead of spending a ready marker
  produced for an earlier source. Its deterministic lane matrix stays concurrent, while a
  secretless post-curation batch deduplicates exact images and semantic representatives across the
  unfinished lanes. Protected admission must bound the source-wide runner's parallel calls and
  apply short bounded call spacing; a secretless matrix then republishes complete clean lane
  reports independently.
  Upload only bounded normalized status/type/band evidence, never raw provider text or exact
  account-usage details. If a capsule disappears after its exact metadata guard but before its
  download, settle only that coalesced wake without starting a model; keep every other download or
  validation failure visible. Reject a capsule whose authenticated generation differs from the
  protected curator implementation before image decoding, and recheck every queued capsule against
  the live `master` SHA before model admission, including artifact-scoped exact wakes.
- Optional-mod review must suppress a producer wake that settles after `master` advances, reject a
  direct request whose source SHA differs from the protected current implementation, and recheck
  live `master` independently at both the secretless batch boundary and credential-bearing model
  boundary before capsule download or model admission.
- Keep deterministic E2E applicability and model applicability separate. A PR to `master` runs
  deterministic Build and Packaged E2E but defers semantic model work to the cumulative post-merge
  1.20.1 anchor; a direct release-branch PR retains fail-closed source-PR review because no such
  anchor is guaranteed. The 1.20.1 synchronization
  anchor always runs exact Build and full packaged E2E, but a complete allowlisted nonvisual port
  diff may continue without Claude only through the protected nonimpact certificate. Its consumer
  must reauthenticate the handler artifact, both gate runs, current Git parents/heads and equal
  trees, then independently recompute the same protected impact manifest. Certificate-driven later
  ports may skip repeated prompt/reviewer-policy judgment only after authenticating the exact
  synchronization run as a post-anchor repository dispatch; manual recovery remains strict.
  A separate protected compatibility-impact manifest must be true before optional-mod E2E can be
  released. A nonvisual continuation and a visual-policy-only diff must not launch optional-mod
  compatibility.
- Version-port and repair validation must check out candidate code with credentials disabled.
  For a version port, the complete patch goes only into an alternate index; the protected merge
  controller reconstructs the original merge and copies only recomputed AI-conflict entries from
  that authenticated candidate tree. Candidate compilation/tests finish and the reconstructed
  staged tree is revalidated in a credentialless job. A dependent writer runs on a fresh runner,
  repeats the reconstruction and exact-tree comparison using only protected policy, creates commits
  with explicit bot identity through `git commit-tree` (never hooks), rechecks ancestry/remote
  identity, and only then configures GitHub authentication. It must never execute candidate scripts.
- Keep each commit to one reviewable concern. Use an imperative conventional subject consistent
  with repository history: `feat:`, `fix:`, `refactor:`, `test:`, `build:`, `docs:`, `ci:`, or
  `chore:`.
- Before committing, inspect the staged diff, run `git diff --check` and
  `git diff --cached --check`, and confirm that no generated or unrelated files are staged. Commit,
  amend, rebase, push, force-push, open a PR, or merge only when explicitly requested.
- Never rewrite commits that may belong to the user or another contributor. Updating an unshared
  topic branch may use rebase when requested; updating a shared branch must use a non-destructive
  merge or a fresh topic branch.
- A pull request targets `master` for shared changes and the exact release branch for version-only
  changes. Its title follows the same conventional format, and its body records scope, validation,
  risks, generated-output status, and material AI assistance.

## Verification

Use the smallest relevant check while iterating, then run the proportional aggregate gate before
handoff. On Windows, use `gradlew.bat`; on Unix-like systems, use `./gradlew`.

Fast stable unit lane:

```powershell
.\gradlew.bat --no-parallel testStableLane
```

The active common test lane:

```powershell
.\gradlew.bat --no-daemon --no-parallel `
  :common:1.21.3:test
```

Full production and packaged-harness gate:

```powershell
.\gradlew.bat --no-daemon --no-parallel clean `
  :common:1.21.3:test `
  buildAllLanes buildAllE2EHarnesses
```

Stage and verify the exact release outputs:

```powershell
python scripts/release/verify_release.py `
  --matrix release/release-matrix.json `
  --manifest build/release/artifacts.json `
  --stage build/release

python scripts/release/verify_release.py `
  --matrix release/release-matrix.json `
  --manifest build/release/artifacts.json `
  --stage build/release `
  --verify-staged
```

Also run:

```powershell
git diff --check
python -m compileall -q e2e scripts
python scripts/release/e2e_readme.py `
  --matrix release/release-matrix.json `
  --contract e2e/scenario-contract.json `
  --readme e2e/README.md `
  --profile-branch "<master-or-exact-release-branch>" `
  --check
python scripts/release/workflow_guidance.py `
  --matrix release/release-matrix.json `
  --guidance docs/ai/WORKFLOW.md `
  --profile-branch "<master-or-exact-release-branch>" `
  --check
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

Packaged Minecraft runtime scenarios require a display and the matrix-declared Java toolchain. Use Xvfb on
headless Linux and in CI; on a desktop session, macOS included, run the orchestrator directly.
Follow `e2e/README.md` for what is verified on which platform, and do not substitute Loom
development runs for packaged-JAR E2E evidence. Gradle and Stonecutter must themselves start on
JDK 21 or newer; shared CI installs JDK 17, JDK 21, and JDK 25 so each version branch can select
its matrix-declared toolchain.

Release automation always rebuilds `buildAllLanes buildAllE2EHarnesses` with `--rerun-tasks` and
requires every production and harness SHA-256 to equal the first build. When determinism is in
scope locally, use `scripts/release/verify_reproducibility.py` against the first staged manifest.

## Documentation maintenance

- Keep the active support table and user build instructions in `README.md` synchronized with the
  release matrix.
- Keep oracle preservation and post-retirement resource routing in `ORACLE-RETIREMENT.md`.
- Keep packaged-runtime behavior in `e2e/README.md`.
- Keep scenario execution and screenshot semantics in `e2e/scenario-contract.json` and public-site
  behavior under `scripts/pages/` plus `site/`; never hand-maintain scenario or version lists in
  consumers.
- Keep the synchronization and thin-branch contract in `VERSION-BRANCHES.md`.
- Keep immutable release identity, retry semantics, provenance, and protected-environment operation
  in `RELEASING.md`.
- Keep the marked README branch profile aligned through `scripts/release/branch_readme.py`. It
  renders `master` as integration-only and derives each release branch's Minecraft version,
  loaders, Java target, runtime pins, and overlay routing from that branch's matrix; do not edit the
  generated block by hand.
- Keep the marked packaged-E2E profile aligned through `scripts/release/e2e_readme.py`. It derives
  scenario facts from the contract and lane/version/Java facts from the active matrix; the
  synchronizer regenerates both marked profiles for every release branch.
- Keep the two active-common test task anchors in this imported guide aligned through
  `scripts/release/workflow_guidance.py`; their Minecraft version comes from the branch matrix.
- Keep the generated README status block aligned through `scripts/release/status_table.py`; never
  hand-maintain its version rows.
- When user-visible behavior, build commands, source layout, or compatibility facts change, adapt
  the non-generated README text on every affected branch. For shared changes, verify that one
  synchronization PR per discovered release branch passes both exact-head gates, merges, and gains
  its final exact-tree attestations; document any deliberate exclusion and any outstanding port.
- Keep the newcomer and AI-assisted contribution path in `CONTRIBUTING.md`, and keep
  `.github/pull_request_template.md` aligned with it.
- Keep root `AGENTS.md` limited to one `@path.md` import per line and keep root `CLAUDE.md`
  byte-for-byte equivalent to `@AGENTS.md` followed by one newline.
- Update the appropriate imported file whenever source-set routing, overlay ownership, lifecycle
  composition roots, security boundaries, or mandatory verification commands change.
- When a packaged scenario adds, renames, or removes a step, edit the scenario contract and its Java
  executable action together, update independent probe canaries where intentional, and let derived
  gallery/reviewer/README consumers follow the contract. Deliver that shared change to every
  affected release branch.

When matrix-owned profile facts change, regenerate the marked README block instead of editing it:

```powershell
python scripts/release/branch_readme.py `
  --matrix release/release-matrix.json `
  --readme README.md `
  --profile-branch "<master-or-exact-release-branch>" `
  --write
```
