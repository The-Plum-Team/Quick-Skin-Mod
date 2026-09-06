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
- Do not run multiple Gradle invocations concurrently on one machine or checkout. Architectury uses
  JVM-global transform state, and aggregate local builds remain serial. GitHub may compile separate
  targets concurrently only on isolated hosted runners, each with its own checkout and serial JVM.
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
  Build and Packaged E2E and defers semantic model work to the cumulative protected post-merge
  generation. Shared source builds every matrix target; the baseline consumer may reduce only
  the authored execution/capture scope after authenticating exact Git ancestry, unchanged module
  fingerprints, complete runtime/review evidence, and available full public baseline artifacts.
  Missing or expired baseline evidence, changed policy/graph, or unknown ownership selects full
  profiles. A selected capsule cannot certify complete coverage.
- Shared complete and selected visual capsules use the same run's matrix-derived Fabric anchor.
  Authenticate the complete source job graph and immutable artifact partition before image reads
  and reauthenticate before model access. A complete target review may request an optional-mod
  wave only when its protected compatibility-impact manifest is true. Both runtime and AI
  consumers independently recompute the per-target plan from the shared matrix and external-mod
  lock. Optional-mod and nightly profiles remain complete integration checks.
- Historical schema-2 port/anchor certificates remain available only for historical recovery;
  schema 3 retires automatic version ports. Never reuse a partial feature proof as either a
  complete shared baseline or a historical full-anchor certificate.
- Repair and historical version-port validation must check out candidate code with credentials disabled.
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
- New pull requests target `master`, including version-only fixes; only historical schema-2
  recovery uses an old release branch. Its title follows the same conventional format, and its body records scope, validation,
  risks, generated-output status, and material AI assistance.

## Verification

Use the smallest relevant check while iterating, then run the proportional aggregate gate before
handoff. On Windows, use `gradlew.bat`; on Unix-like systems, use `./gradlew`.

Fast stable unit lane:

```powershell
.\gradlew.bat --no-parallel -PquickskinTarget=1.20.1 testStableLane
```

The active common test lane:

```powershell
.\gradlew.bat --no-daemon --no-parallel `
  -PquickskinTarget=1.20.1 :common:1.20.1:test
```

Full production and packaged-harness gate:

```powershell
python scripts/release/build_matrix.py --clean
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
  --profile-branch master `
  --check
python scripts/release/workflow_guidance.py `
  --matrix release/release-matrix.json `
  --guidance docs/ai/WORKFLOW.md `
  --profile-branch master `
  --check
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

Packaged Minecraft runtime scenarios require a display and the matrix-declared Java toolchain. Use Xvfb on
headless Linux and in CI; on a desktop session, macOS included, run the orchestrator directly.
Follow `e2e/README.md` for what is verified on which platform, and do not substitute Loom
development runs for packaged-JAR E2E evidence. Gradle and Stonecutter must themselves start on
JDK 21 or newer; shared CI installs JDK 17, JDK 21, and JDK 25 so each matrix target can select
its matrix-declared toolchain.

The full build coordinator runs one Gradle process per matrix target sequentially; it includes
that target's unit tests, production JARs, and packaged harnesses. Never nest Gradle processes or
combine every Minecraft target's remapping tasks in one JVM. Use `--target <minecraft>` for an
explicitly partial build. A successful build report does not replace artifact staging or E2E.

GitHub's reusable `build-matrix.yml` derives all target jobs from that same validated plan and
allows eight isolated runners. `assemble_build.py` independently reverifies every target manifest,
commit, matrix, production/harness hash and SBOM before constructing the complete bundle.
Repository-policy tests run alongside compilation; both must pass the stable `Build and verify`
gate. A PR's Packaged E2E waits for that exact source Build and downloads its immutable artifact
by ID, then reverifies it against the tested merge commit. It never starts a second PR compilation.
Standalone runs without an available bundle use the same complete isolated compiler. Runtime
coverage uses up to sixteen isolated runners and retains every required target/loader job.

Release automation always rebuilds `scripts/release/build_matrix.py` with `--rerun-tasks` and
requires every production and harness SHA-256 to equal the first build. When determinism is in
scope locally, use `scripts/release/verify_reproducibility.py` against the first staged manifest.

Shared-source feature selection must use the protected complete-baseline consumer, including
current availability of every retained public baseline. Keep every matrix runtime job required;
reduce only the scenario actions and captures justified by the authenticated module graph.
The `capture_coverage=full` manual input restores complete coverage. A selected AI proof or composed
Pages bundle must retain its separate coverage identity and may never issue a complete baseline.
Public frames keep their original tested commit/run/JAR when unaffected dependencies permit reuse.

## Documentation maintenance

- Keep the active support table and user build instructions in `README.md` synchronized with the
  release matrix.
- Keep oracle preservation and post-retirement resource routing in `ORACLE-RETIREMENT.md`.
- Keep packaged-runtime behavior in `e2e/README.md`.
- Keep scenario execution and screenshot semantics in `e2e/scenario-contract.json` and public-site
  behavior under `scripts/pages/` plus `site/`; never hand-maintain scenario or version lists in
  consumers.
- Keep shared-source development and historical branch recovery in `VERSION-BRANCHES.md`.
- Keep immutable release identity, retry semantics, provenance, and protected-environment operation
  in `RELEASING.md`.
- Keep the marked README branch profile aligned through `scripts/release/branch_readme.py`. It
  derives the current shared build and per-target versions, loaders, Java toolchains, runtime pins,
  and overlay routing from the matrix; do not edit the generated block by hand.
- Keep the marked packaged-E2E profile aligned through `scripts/release/e2e_readme.py`. It derives
  scenario facts from the contract and lane/version/Java facts from the active matrix; the
  current shared checkout renders both marked profiles from that one matrix.
- Keep the active-common test task anchor in this imported guide aligned through
  `scripts/release/workflow_guidance.py`; its Minecraft version comes from the matrix unit lane.
- Keep the generated README status block aligned through `scripts/release/status_table.py`; never
  hand-maintain its version rows.
- When user-visible behavior, build commands, source layout, or compatibility facts change, adapt
  the non-generated README text for the shared source. Verify the complete required target/loader
  gate and every affected feature interaction; document intentional exclusions and outstanding
  runtime or publication evidence.
- Keep the newcomer and AI-assisted contribution path in `CONTRIBUTING.md`, and keep
  `.github/pull_request_template.md` aligned with it.
- Keep root `AGENTS.md` limited to one `@path.md` import per line and keep root `CLAUDE.md`
  byte-for-byte equivalent to `@AGENTS.md` followed by one newline.
- Update the appropriate imported file whenever source-set routing, overlay ownership, lifecycle
  composition roots, security boundaries, or mandatory verification commands change.
- When a packaged scenario adds, renames, or removes a step, edit the scenario contract and its Java
  executable action together, update independent probe canaries where intentional, and let derived
  gallery/reviewer/README consumers follow the contract. Verify every affected matrix target from
  that shared source revision.

When matrix-owned profile facts change, regenerate the marked README block instead of editing it:

```powershell
python scripts/release/branch_readme.py `
  --matrix release/release-matrix.json `
  --readme README.md `
  --profile-branch master `
  --write
```
