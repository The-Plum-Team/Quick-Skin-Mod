# Project and release contract

This file is part of the repository-wide instruction set imported by `AGENTS.md`. Together, the
documents listed there are the source of truth for coding agents working anywhere below the
repository root.

## Documentation map

- `AGENTS.md` is the import-only manifest for the complete coding-agent instruction set.
- There is deliberately no `CLAUDE.md`. Claude Code 2.1.277 or later reads `AGENTS.md` and expands
  its imports only while no `CLAUDE.md`, `.claude/CLAUDE.md` or `CLAUDE.local.md` exists in the
  working directory or above it.
- `CONTRIBUTING.md` is the human-facing path from an unfamiliar checkout to a reviewed pull
  request, including an AI-assisted workflow.
- `README.md` is for users and builders; focused architecture documents own their subjects.
- `RELEASING.md` owns immutable identity, publication, recovery, provenance, and GitHub governance.
- `e2e/README.md`, `e2e/scenario-contract.json`, and `scripts/pages/` own packaged-scenario and
  public visual-evidence identity, validation, rendering, and GitHub Pages publication.
- `docs/ci/` holds bounded operational guides (repair preflight, acceptance observation, and the
  measured review, curation, baseline and Pages pipelines); `docs/ai` links to them rather than
  restating their procedures.
- [`docs/architecture/decisions/`](../architecture/decisions/README.md)
  records evidence-backed architectural decisions that must survive individual worktrees.

Do not put operational rules directly in `AGENTS.md`, do not add any of those `CLAUDE.md` files, and
do not create another root instruction file that restates this contract. Add a nested `AGENTS.md` only when a directory
genuinely needs narrower rules, and keep it limited to imports for those local deltas.

Quick Skin is a client-and-server Minecraft mod built from one Stonecutter-managed source tree. The
central release inventory is `release/release-matrix.json`. It is authoritative for supported
versions, loaders, Java versions, remap policy, source-overlay routing, Gradle artifact tasks,
runtime dependencies, loader ranges, and FML pack formats. The versioned
`e2e/scenario-contract.json` is separately authoritative for scenario ids, execution profiles,
orchestration, steps, assertions, captures, probes, and comparisons. Do not duplicate either
inventory in Gradle, Python, workflows, or documentation.

This checkout uses release-matrix schema 3: one shared source branch, with separately
compiled feature/API modules and a per-Minecraft/loader JAR. The generated README profile displays
the active matrix; do not maintain a second version table in agent guidance. The recoverable
[migration plan](../architecture/MODULAR-REWORK.md) records imported targets and remaining work.

Every artifact targets exactly the Minecraft version in its filename and metadata. A support or
loader change starts in the release matrix and must pass its validation and mutation tests.

The matrix names `master` as the shared source branch. `scripts/release/release_identity.py`
derives a non-publishable `build-v<mod_version>` identity for the complete schema-3 bundle;
`--target <minecraft>` derives that target's independent `mc<minecraft>-v<mod_version>` identity.
A publishing run must still bind the exact source head, pass its manual target rehearsal and
receive protected `release` environment approval; see `RELEASING.md`. Historical
schema-2 snapshots retain their original branch/tag validation contract.

## Shared validation and feature evidence

- New work targets `master`, including a fix for one Minecraft version or loader. The matrix
  chooses supported artifacts; historical branch names never extend that inventory.
- `architecture/modules.json` owns separately compiled modules, dependencies, API bindings, and
  their composition roots. Runtime integration edges supplement compile dependencies. Features
  cannot import the common assembly. Selected module classes/resources enter each production JAR
  exactly once, before its normal Architectury/loader remapping; the harness stays separate.
- Build and Packaged E2E retain the complete required target/loader gate. A protected baseline
  consumer may select fewer authored actions and captures only after proving the cumulative Git
  diff, exact policy/contract/matrix, complete healthy source evidence, available full public
  archives, and unchanged transitive module fingerprints. Unknown or unavailable proof selects
  full profiles. PRs targeting `master` defer semantic AI review to the protected post-merge run.
- A draft PR to `master` starts no Build or Packaged E2E work; its required gate jobs run under
  distinct deferred names so no draft run can satisfy a required context. It lands only after it
  is marked ready or through one `batch/*` PR from `scripts/ci/pr_batch.py`, which runs the
  unchanged complete gate once for the squashed set
  ([ADR 0009](../architecture/decisions/0009-validate-draft-pull-requests-in-batches.md)).
- Both full and partial shared visual reviews use their own runtime generation's Fabric anchor.
  Secretless curation and model admission authenticate complete source jobs and immutable
  artifacts independently. Full proof schema 8 and selected proof schema 7 remain distinct;
  partial coverage cannot seed a complete healthy baseline or authorize optional-mod testing.
- Full optional-mod waves are per Minecraft target and current source SHA. Their producer and
  AI consumer independently recompute runnable/N/A lanes from the shared matrix and mod lock.
  There is no unattended scheduled runtime. A protected post-merge generation is complete only
  when its own diff is unproven or reaches the optional-mod coverage closure; the release profile
  and an explicit complete-capture manual run are always complete.
- Pages publishes one atomic matrix-derived site. Selected evidence can cover unchanged features
  only through independently authenticated full and selected components. Every reused image
  retains the original tested commit/run/JAR and its separate coverage provenance.
- Schema 3 disables automatic version ports and retains independent immutable target release
  identities. Historical schema-2 controllers and evidence remain available for auditing old
  releases. See `RELEASING.md` and the migration plan for activation and acceptance evidence.

## Artifact, cache, and recovery retention

These rules apply to the shared-source workflows and to the historical automation that remains in
the repository.

- Actions artifacts are handoffs, not an archive. Every ordinary upload is retained for one day;
  named seven-day exceptions are automatic-sync packaged evidence/input bundles needed by delayed
  compatibility review, compatibility plans/evidence/capsules/reports/block markers, queued visual
  review capsules with their normalized reports and generation-block markers, compatibility Pages
  handoffs, and rolling exact-policy visual verdict caches.
  Named 90-day artifact classes include the semantic certificate, the
  SHA-bound Pages cache, the matrix-derived lossless
  Fabric 1.20.1 anchor handoff, the per-covered-branch mod-compatibility Pages cache, the
  immutable `release-<release-id>` bundle, and its SBOM-recovery counterpart
  `release-recovery-<release-id>`. Shared-source feature coverage additionally retains its
  complete healthy certificate and exact target/source/run compact Pages baselines for at most
  90 days. Only a successfully deployed complete generation may create those public baselines;
  partial generations reuse them without extending their lifetime. Missing or expired baseline
  artifacts restore complete runtime coverage. The release bundle
  spans protected environment approvals, provides bounded recovery for an interrupted publication,
  and is the only source from which the pending-release verifier finalizes. After a successful Pages
  replacement, protected rotation deletes by exact artifact ID the superseded cache, ordinary
  consumed `pages-e2e-<branch>` and compatibility handoffs, older lossless anchor generations,
  Pages fan-in artifacts, and the deploy artifact while preserving the current lossless anchor.
  Ordinary raw packaged-E2E
  proof retains its one-day window because a concurrent branch attestation may still consume it;
  an automatic synchronization source retains seven days for the post-semantic compatibility wave.
  Completed and already-reviewed AI queue entries retain seven days so late publication-tail
  cancellation can recover them; authenticated reports suppress duplicate review while available.
  Terminally invalid entries retain exact-ID cleanup, while transient failures remain for retry.
  Retention is bounded per input, not by an aggregate storage cap; the
  [visual-review pipeline](../ci/VISUAL-REVIEW-PIPELINE.md) document quantifies its storage and
  scheduled owner-read tradeoff. A
  protected schedule also deletes by exact cache ID Actions caches scoped to branch
  refs that no longer exist. On live branches it recognizes only SHA-bearing `setup-gradle` home
  keys, preserves the newest restorable generation per OS/job/cache-version family that has a
  successful Build job, and protects the complete cache inventory while any potentially
  cache-consuming run is active anywhere in the repository. It deletes only superseded generations
  after exact candidate, compatible-replacement, branch, and repository-wide run revalidation. The
  protected cleanup run itself is the sole exclusion because it never configures Gradle; unknown
  workflows remain protective.
  Without a proven successful replacement it preserves the whole family. It discovers live
  branches directly and must never infer a supported-version inventory.
- Build gate owns Gradle cache writes, and only a trusted push or manual Build run on protected
  `master` may write. Release branches restore their last known branch cache read-only; pull
  requests, ephemeral branches, Packaged E2E, and Release are also read-only. This bounds immutable
  generations without making the release-branch inventory another cache-policy input.
- Packaged runtime owns the second cache family, the installed Forge/NeoForge server, keyed by its
  exact recipe digest rather than a commit. Only a protected `master` dispatch may publish an
  entry; every other context restores read-only, with no prefix fallback, and a restore that fails
  the store's own validation is a cache miss and a fresh install. Retention is the platform's
  unused-entry expiry: a recipe that leaves the matrix is never restored again. The cache pruner
  stays narrow and must never read the release matrix.
- `e2e/full-validation-baseline.json` is an intentionally runtime-impacting marker for an explicitly
  requested complete recovery. The 2026-09-09 recovery validates the full workload after GitHub API,
  cape-expectation, and reviewer-checkout fixes; its preceding generations did not finish that
  workload. The earlier 2026-08-17 marker covered the quota-paused cape/Elytra rollout. Routine
  policy-only changes leave this marker untouched; an exceptional refresh records the unfinished
  validation it restores and retains every ordinary admission and coverage check.

## Historical schema-2 version ports

Schema 3 retires automatic version ports, release-branch synchronization, and the 1.20.1
anchor-certified fan-out. Their controllers remain only to audit old releases and to perform an
explicit historical recovery. Their complete delivery contract and invariants live in
[VERSION-BRANCHES.md](../../VERSION-BRANCHES.md#historical-automation-invariants). Read it
before touching `sync-version-branches.yml`, `handle-version-port-result.yml`, the version-port
or anchor-certification scripts, or a schema-2 release branch; never apply it to shared-source
work.

## Task routing

Choose the target before editing:

| Change scope | Start from | Expected destination |
|---|---|---|
| Shared behavior, security, tests, automation, or general documentation | `master` | One shared source with affected matrix target/feature validation |
| One exact Minecraft version or loader pair | `master` | Its owning API family, overlay, or loader module |
| Version/loader support inventory | `master`, matrix first | A validated target built from the same shared source |
| Generated output or staged artifacts | Nowhere | Fix the tracked input instead |

At the start of every task:

1. Inspect `git status --short --branch` and preserve existing work.
2. Read the active `release/release-matrix.json`; never infer support from directory names alone.
3. Read the relevant focused document and module build file.
4. Search canonical sources and every active overlay for the affected path or symbol.
5. State the intended scope and run the smallest check that can disprove the change while
   iterating.

Never develop directly on `automation/sync/*`; those branches are disposable workflow-owned PR
heads. Human contributors start with `CONTRIBUTING.md` and use a separate topic branch.

If intended behavior excludes a target, make the exception explicit in its owning module or
API-family adapter and document the decision. Do not spread broad version conditions across
unrelated features or create a second version inventory.
