# Editing and verification workflow

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Editing workflow

The generic editing, worktree, commit, pull-request, workflow-security and AI-credential rules live
in the managed [shared repository contract](shared/REPOSITORY.md); this section adds Quick Skin's
deltas.

- Read `release/release-matrix.json` and the relevant module `build.gradle.kts` before changing
  versions, loaders, source roots, resources, artifact tasks, or E2E coverage.
- Search canonical sources and all active overlays before changing a cross-version class or method.
- AI repair writes are positively limited to the `common`, `fabric`, `forge` and `neoforge`
  production `src/main` paths; `scripts/ci/ai_patch_policy.py` is that boundary.
- Never place raw visual artifacts in a credential-bearing job. Authenticate them, validate the
  protected lane graph, extract them with aggregate budgets, enforce exact matrix-row/scenario/JAR
  coverage, and canonicalize selected images in a prior secretless job. The fresh review capsule
  budgets candidate/reference visits at the contract's fixed resolution, counting a reused
  reference for each pair; retained image bytes have a separate aggregate limit. Revalidate the
  exact source/generation/target tuple before queue API access, and scope completed reports and
  retry markers to that same target so sibling reviews cannot suppress one another. The capsule
  may expose only bounded manifest chunks and its curated image directory through a read-only model
  tool surface; the shell captures one private raw JSON result envelope per bounded call from model
  stdout. The pinned CLI must validate supported structural types, required keys, and
  manifest-bound label values against a protected schema derived from the exact chunk. Protected
  code must independently extract every result and enforce exact count, labels, bounds, and
  coherence before it emits the only normalized report eligible for upload. Durable queue state
  must not depend on a pending workflow run: a sanitized marker may cool a failed entry, raw
  provider text must never be uploaded. The final `actions: write` cleanup job must retain fresh
  and already-reviewed inputs through seven-day expiry, succeeding without deletion so a later
  cancelled report owner can recover the original capsule. Only missing/terminally invalid inputs
  enter exact-ID cleanup; authenticated reports suppress completed-input redispatch. A repository-wide capacity circuit may serialize
  its tool-free preflight and marker publication independently from the model/cache job. The latter
  serializes ordinary capsules globally so subsequent targets reuse the latest verdicts, with
  at most 32 concurrent calls across ordinary reviewers. Two secretless preparation slots may
  overlap that owner, handing off only the original capsule ZIP by exact run/attempt/id/digest.
  The model owner reauthenticates source freshness and independently validates the same bytes.
  Its `queue: max` preserves pending jobs and its independent model chunks remain concurrent.
  Publish the complete normalized report before cache rotation; after cancellation, authenticate
  its exact successful validation/upload steps and unchanged capsule before republishing without
  a model. Valid old cancelled capsules are skipped when the current authenticated capsule differs.
  Exact successful validation/build/upload steps commit the immutable cache union independently
  of later cancellation or failure; bind it to the attempt's upload window and fresh metadata
  before reuse. Predecessor retirement must not make earlier completed keys unrecoverable.
  Capacity admission must fail closed on unknown/permanent probe failures and retain every
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
- Keep deterministic E2E applicability and model applicability separate. A ready PR to `master`
  runs Build and Packaged E2E and defers semantic model work to the cumulative protected post-merge
  generation. A draft PR to `master` runs neither gate: its required jobs report distinct deferred
  names, and it never seals a tested-source record or publishes a staged bundle. Never let a draft,
  skipped or deferred job report a required context name. Shared source builds every matrix target; the baseline consumer may reduce only
  the authored execution/capture scope after authenticating exact Git ancestry, unchanged module
  fingerprints, complete runtime/review evidence, and available full public baseline artifacts.
  Missing or expired baseline evidence, changed policy/graph, or unknown ownership selects full
  profiles. A selected capsule cannot certify complete coverage.
- Shared complete and selected visual capsules use the same run's matrix-derived Fabric anchor.
  Authenticate the complete source job graph and immutable artifact partition before image reads
  and reauthenticate before model access. A complete target review may request an optional-mod
  wave only when its protected compatibility-impact manifest is true. Both runtime and AI
  consumers independently recompute the per-target plan from the shared matrix and external-mod
  lock. Optional-mod profiles remain complete integration checks; a change whose module closure
  reaches the compatibility scenarios keeps the complete packaged profile so those lanes can pair
  against it, while a change proven outside that closure carries the published evidence forward.
- A protected merge may reuse its original PR Build and Packaged E2E only through
  `scripts/ci/ci_reuse.py`. Require the actual merged PR, the sealed tested merge and both parents,
  identical complete Git trees, the exact successful original job graphs and available immutable
  artifacts. Never use a lightweight reuse job as evidence that Minecraft executed there. Preserve
  the original commit/run/artifact identities through review, optional-mod baselines and Pages;
  the protected merge is a separate coverage generation. Missing or expired evidence permits
  fresh execution; an API failure, pending original execution or malformed proof stops admission.
  Repeated producer wakes must skip targets with an authenticated existing capsule or report.
- Target Build/E2E badges are advisory snapshots, not live acceptance or baseline certificates.
  `.github/workflows/target-ci-status.yml` refreshes the complete matrix on `master` pushes and
  gate transitions, with explicit E2E advisory wakes for token-created runs plus manual and hourly
  recovery. Each invocation collects bounded gate data once; it never polls per target or launches
  compilation, Minecraft, Pages or a model. Use protected `master` code and authenticate exact run
  attempts, current coverage and any original execution reused through `ci_reuse.py`.
  Publish validated JSON, Markdown and SVG only to `automation/ci-status`, serially without
  cancellation during a push. Recheck `master` and the previous data-branch head before the atomic
  fast-forward update; a race aborts rather than overwriting another publisher. Never execute
  data-branch content, treat a skipped job as success without an authenticated reference, or reuse
  old green when current gate data is unknown. Badge caches may delay display: inspect the covered
  SHA, observation time and exact run links in the details, and use the required global gates for
  acceptance.
- Budget GitHub API work by its exact consumer. A direct compatibility source wake must not
  start a full recovery sweep after settling; only the scheduled/manual recovery chain continues
  that sweep. Baseline requests follow all review-owner tail jobs. The collector first checks
  availability of every exact target report and public archive, stopping at the first gap before
  expensive runtime and owner admission. A complete certificate still requires every original
  provenance, job, archive and report check; API failures never count as missing evidence.
  Before issuing a duplicate baseline for the same source attempt, authenticate the existing
  issuer and complete certificate against the current policy, verify its public artifacts remain
  available, reauthenticate its runtime and job graph, and recheck the live source head. Later
  baseline consumers still require their own availability checks. Observe the affected Actions
  token's recorded quota counters rather than
  inferring its budget or reset from a developer token.
  Review tails and the Pages caller's `ext-feature-coverage` extension job share the separate
  short baseline-request lock. They may dispatch
  only after exact complete readiness and terminal sibling owners, retaining the lock until
  the new source/attempt collector is observable. The collector still authenticates every
  runtime, report and public artifact; cancelled-owner report recovery must not be blocked by
  a preserved old cancelled report. Failed-owner workflow-run/manual/hourly recovery uses the
  same gate; successful completion echoes allocate no extra runner. A lost/failed tail in an
  otherwise-successful owner converges through hourly/manual recovery, not an immediate echo.
  Report scheduled recovery overhead separately from suppressed collector starts; see
  [baseline request coalescing](../ci/BASELINE-REQUESTS.md).
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
- New pull requests target `master`, including version-only fixes; only historical schema-2
  recovery uses an old release branch.
- Several changes can be validated together: open them as drafts, which start no Build or
  Minecraft work, then land them through one batch PR created by `scripts/ci/pr_batch.py` and
  close them with its `settle` command. A batch squashes one commit per PR onto current `master`
  and accepts only same-repository PRs; never place fork code on a same-repository branch. See
  [PR batches](../ci/PR-BATCHES.md).
- A mod-base kit bump is Quick Skin's control-plane route for managed files: on a fresh
  `chore/mod-base-vX.Y.Z` branch run `python scripts/ci/mod_base_kit.py bump --to vX.Y.Z`,
  review the complete diff, open it as a draft and land it through a batch PR. It edits
  `.github/workflows/*`, so it runs the complete Build, every Packaged E2E lane and the optional-mod
  wave its impact classifiers select; there is no selection-policy exception
  ([ADR 0010](../architecture/decisions/0010-delegate-public-evidence-to-mod-base.md)). Never edit
  a managed file by hand, and close any Dependabot pull request that touches a kit reference or
  the managed part of `pages.yml` (Dependabot ignores the kit and `actions/deploy-pages`).

## Verification

Use the smallest relevant check while iterating, then run the proportional aggregate gate before
handoff. On Windows, use `gradlew.bat`; on Unix-like systems, use `./gradlew`.

Before publishing an infrastructure repair or starting its expensive gate, use the
[repair preflight](../ci/REPAIR-PREFLIGHT.md) to record the exact head/tree, reviewed scope,
canonical impact, observed evidence identities, and any missing complete-recovery action. Its
offline real-Git and archive-digest regressions do not replace the full required gates. Regenerate
the plan after scope or head changes and retain which old proof is invalidated. An evidence
observation still requires the canonical live verifier; routine non-impacting work must not add
a full-validation marker merely because earlier optional evidence is unavailable.

For long-running acceptance, use the [recoverable local observer](../ci/ACCEPTANCE-OBSERVER.md).
One coordinator owns the bounded exact run/attempt/SHA inventory and persists its last successful
snapshot; other observers read local status. Read errors and a stale heartbeat must remain visible,
and restarting observation must not dispatch new work or grant merge/publication authority.

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
python scripts/ci/mod_base_kit.py verify --network
python scripts/ci/mod_base_kit.py run template check --repo .
python -m unittest discover -s scripts/release/tests -p "test_*.py" -v
python -m unittest discover -s scripts/ci/tests -p "test_*.py" -v
```

`scripts/ci/parallel_unittest.py` accepts the same `-s`/`-p` arguments and is the faster
equivalent of those last two commands; the build gate runs both suites through it.

`verify --network` proves that the single mod-base pin is a released tag reachable from mod-base
`main`; `template check` proves that the managed files are byte-identical to that pin and that the
fragment files, `AGENTS.md` and `.github/CODEOWNERS` keep their required shape. The build gate's
`policy` job runs both. The suites that exercise the adapter and the managed files find the kit
only through `scripts/ci/mod_base_kit.py` and fail, never skip, when it is unavailable; the first
local run fetches the pinned kit into the user cache (see "Kit availability" in the
[shared repository contract](shared/REPOSITORY.md)). After changing the adapter,
`site/mod-base.json` or a scenario, also run the kit's conformance check against the real matrix
and contract:

```powershell
python scripts/ci/mod_base_kit.py run conformance --repo . --keys mc1.20.1,mc26.3 --families
```

Packaged Minecraft runtime scenarios require a display and the matrix-declared Java toolchain. Use Xvfb on
headless Linux and in CI; on a desktop session, macOS included, run the orchestrator directly.
Minecraft 26.3's SDL window needs an sRGB OpenGL framebuffer that Xvfb's GLX lacks, so headless
Linux runs export `SDL_VIDEO_FORCE_EGL=1`; the packaged E2E action sets it for every lane.
Follow `e2e/README.md` for what is verified on which platform, and do not substitute Loom
development runs for packaged-JAR E2E evidence. Gradle and Stonecutter must themselves start on
JDK 21 or newer; shared CI installs JDK 17, JDK 21, and JDK 25 so each matrix target can select
its matrix-declared toolchain.

The full build coordinator runs one Gradle process per matrix target sequentially; it includes
that target's unit tests, production JARs, and packaged harnesses. Never nest Gradle processes or
combine every Minecraft target's remapping tasks in one JVM. Use `--target <minecraft>` for an
explicitly partial build. A successful build report does not replace artifact staging or E2E.

GitHub's reusable `build-matrix.yml` derives all target jobs from that same validated plan and
gives every matrix version its own isolated runner, so the complete matrix compiles in one wave.
`assemble_build.py` independently reverifies every target manifest, commit, matrix,
production/harness hash and SBOM before constructing the complete bundle.
Repository validation and the complete release-policy and CI-policy suites run alongside compilation.
Each suite has its own hosted runner, checkout of the same tested SHA, temporary files, discovered
test-count summary, and retained diagnostics. `parallel_unittest.py` discovers the suite once, as
`unittest discover -s` would, and runs it across the runner's cores: a class with class-level
fixtures stays whole in one worker, and each worker has its own pre-created temporary directory.
It fails closed on a discovery error, failure, error, unexpected success, dead worker, zero tests,
or any unit that does not run exactly its discovered number of tests. All three policy jobs and compilation must pass the
stable `Build and verify` gate; only independently authenticated protected reuse permits their
explicit skips. A PR's Packaged E2E waits for that exact source Build and downloads its immutable artifact
by ID, then reverifies it against the tested merge commit. It never starts a second PR compilation.
Standalone runs without an available bundle use the same complete isolated compiler. Runtime
coverage gives every version/loader lane its own isolated runner, so the complete matrix runs in
one wave, and retains every required target/loader job. Neither matrix sets a `max-parallel`
bound: the validated plan alone decides its width, so a support change needs no workflow edit.

After an identical-tree PR merge, Build and Packaged E2E independently authenticate the original
PR records and skip their compilation and Minecraft workers. The post-merge generation contains
small `reused-source-build` / `reused-source-e2e` references rather than copied JARs or screenshots.
The original passing PR gates emit `tested-source-build` / `tested-source-e2e`. These JSON records
are retained for 90 days; source Build/E2E bundles and raw runtime evidence are retained for seven
days. Review and Pages reauthenticate the source reference before consuming its original bytes.
Shared proof schemas keep an optional `runtime_source` binding; public mod-base evidence records
it as the `quick-skin.runtime_source` extension with delegated reuse, which the adapter's
`authenticate_extensions` hook verifies. Selected admissions retain their original PR merge and
policy base. These references never chain. Explicit full recovery requests bypass runtime reuse
and may rebuild if no current source bundle is available.

Release automation always rebuilds `scripts/release/build_matrix.py` with `--rerun-tasks` and
requires every production and harness SHA-256 to equal the first build. When determinism is in
scope locally, use `scripts/release/verify_reproducibility.py` against the first staged manifest.

Release preparation is bounded to four isolated build runners and four runtime slots per loader
family (Fabric or Forge/NeoForge); every checkout still runs Gradle serially. Preserve pending
entries with `queue: max` and `cancel-in-progress: false`. Only remote publication writers share
the short `release-publish` lock. Never hold it while polling marketplace moderation.

Before tagging, require the manual target rehearsal on the exact protected source. The offline
publication simulation also runs in policy tests and on the real staged bundle before attestation.
Preserve the durable draft-body ledger: save `uploading` before the upload, `pending` after accepted
submission, and `verified` only after identity/hash reconciliation. Missing public listings never
reset upload intent. The secretless scheduled verifier reads original source inputs as inert data,
authenticates the original release evidence, and requests protected-environment finalization only
after every row is verified. An upload workflow can succeed with pending moderation; report that
state explicitly and retain the original tag, version, bundle and publication identities.

The separate SBOM recovery workflow may retain a canonical tag's already tested JARs only after
authenticating its full release rehearsal and original tag-push provenance. Its bounded metadata
repair preserves the original source SHA and requires the protected release environment's human
review. See `RELEASING.md`; ordinary release dispatches remain validation-only.

Shared-source feature selection must use the protected complete-baseline consumer, including
current availability of every retained public baseline. Keep every matrix runtime job required;
reduce only the scenario actions and captures justified by the authenticated module graph.
The `capture_coverage=full` manual input restores complete coverage. A selected AI proof or composed
Pages bundle must retain its separate coverage identity and may never issue a complete baseline.
Public frames keep their original tested commit/run/JAR when unaffected dependencies permit reuse.

## Documentation maintenance

The shared repository contract owns the generic documentation rules; Quick Skin's documents and
generators are:

- Keep the active support table and user build instructions in `README.md` synchronized with the
  release matrix.
- Keep oracle preservation and post-retirement resource routing in `ORACLE-RETIREMENT.md`.
- Keep packaged-runtime behavior in `e2e/README.md`.
- Keep scenario execution and screenshot semantics in `e2e/scenario-contract.json`. Public-site
  behavior belongs to the pinned mod-base kit; Quick Skin owns only its configuration
  `site/mod-base.json` and its adapter `scripts/pages/mod_base_adapter.py`. Never hand-maintain
  scenario or version lists in consumers.
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
  hand-maintain its version rows. Shared target badges link to the generated data branch; their
  published snapshots belong to `target_status.py`, `target_status_render.py` and
  `target_status_publish.py`, with no second inventory of supported versions.
- When user-visible behavior, build commands, source layout, or compatibility facts change, adapt
  the non-generated README text for the shared source. Verify the complete required target/loader
  gate and every affected feature interaction; document intentional exclusions and outstanding
  runtime or publication evidence.
- Keep `AGENTS.md` as the two managed shared imports followed by `site/mod-base.json`
  `template.agents_local` (`PROJECT`, `SOURCE-ARCHITECTURE`, `RUNTIME-INVARIANTS`, `WORKFLOW`, in
  that order). Change `docs/ai/shared/*` only through a kit bump; put Quick Skin rules in the four
  local documents.
- When a packaged scenario adds, renames, or removes a step, its executable action is the Java
  harness step; verify every affected matrix target from that shared source revision.

When matrix-owned profile facts change, regenerate the marked README block instead of editing it:

```powershell
python scripts/release/branch_readme.py `
  --matrix release/release-matrix.json `
  --readme README.md `
  --profile-branch master `
  --write
```
