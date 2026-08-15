# Project and release contract

This file is part of the repository-wide instruction set imported by `AGENTS.md`. Together, the
documents listed there are the source of truth for coding agents working anywhere below the
repository root.

## Documentation map

- `AGENTS.md` is the import-only manifest for the complete coding-agent instruction set.
- `CLAUDE.md` is only a compatibility redirect to that manifest.
- `CONTRIBUTING.md` is the human-facing path from an unfamiliar checkout to a reviewed pull
  request, including an AI-assisted workflow.
- `README.md` is for users and builders; focused architecture documents own their subjects.
- `RELEASING.md` owns immutable identity, publication, recovery, provenance, and GitHub governance.
- `e2e/README.md`, `e2e/scenario-contract.json`, and `scripts/pages/` own packaged-scenario and
  public visual-evidence identity, validation, rendering, and GitHub Pages publication.
- [`docs/architecture/decisions/`](../architecture/decisions/README.md)
  records evidence-backed architectural decisions that must survive individual worktrees.

Do not put operational rules directly in `AGENTS.md` or `CLAUDE.md`, and do not create another root
instruction file that restates this contract. Add a nested `AGENTS.md` only when a directory
genuinely needs narrower rules, and keep it limited to imports for those local deltas.

Quick Skin is a client-and-server Minecraft mod built from one Stonecutter-managed source tree. The
central release inventory is `release/release-matrix.json`. It is authoritative for supported
versions, loaders, Java versions, remap policy, source-overlay routing, Gradle artifact tasks,
runtime dependencies, loader ranges, and FML pack formats. The versioned
`e2e/scenario-contract.json` is separately authoritative for scenario ids, execution profiles,
orchestration, steps, assertions, captures, probes, and comparisons. Do not duplicate either
inventory in Gradle, Python, workflows, or documentation.

The active production matrix on this branch contains exactly two artifacts:

| Minecraft | Loaders | Java |
|---|---|---:|
| 1.20.1 | Fabric, Forge | 17 |

Every artifact targets exactly the Minecraft version in its filename and metadata. A support or
loader change starts in the release matrix and must pass its validation and mutation tests.

The matrix also names its one canonical release branch. `scripts/release/release_identity.py`
derives the only valid tag and publication prefix from the sorted Minecraft versions plus the
logical mod version. A publishing run must be the exact head of that branch. Manual release runs
are validation-only; only the canonical tag can publish. See `RELEASING.md` for the recoverable,
immutable workflow and governance activation contract.

## Version branch model

- `master` is the shared integration branch. Release branches use the naming form
  `<loader>-and-<loader>-<minecraft>`, for example `forge-and-fabric-1.20.1`.
- A release branch is a normal descendant of `master`, not an orphan patch branch. Unchanged Git
  blobs are shared; the branch-specific commits contain only its matrix, loader/API adapters,
  overlays, metadata, and documentation differences.
- `.github/workflows/sync-version-branches.yml` discovers release branches from their names. It must
  not contain a Minecraft-version list. The matrix checked into each target remains authoritative.
- A trusted push to `master` creates a target-specific synchronization branch and PR. Clean merges
  are mechanical. For a conflicted merge, protected code partitions the original conflict set
  before any model runs: exact shared guidance/runtime documents use a source-preferred three-way
  merge, the target matrix remains authoritative, an inactive loader build file remains absent,
  and files below an overlay root not activated by that target matrix remain absent. Unknown
  protected conflicts fail closed; Claude receives only the remaining unprotected paths and may
  make one bounded repair after a failed gate. AI jobs have read-only GitHub
  permissions, check out without persisted credentials, and emit only bounded patch artifacts. A
  protected merge controller owns Git's no-commit merge, the original index classification, exact
  mechanical resolutions, and stable evidence. Both the credentialless validator and the fresh
  writer rebuild that merge from the authenticated parents. They apply the complete proposal only
  to an alternate index, authenticate its full tree, import only classifier-approved AI paths, run
  protected profile renderers, and require the reconstructed tree to equal the proposal exactly.
  Candidate scripts never run in the writer.
- GITHUB_TOKEN-created PRs and child runs do not recursively start ordinary PR or completion
  workflows, so synchronization explicitly dispatches `build-gate.yml` and `on-demand-e2e.yml`.
  Each gate reports completion through a trusted `repository_dispatch`; the result handler merges
  only when the latest exact-head run of both gates succeeds. After revalidating the PR identity,
  base, head, ancestry, and both run records, the handler binds those results to the exact head with
  the two stable commit-status contexts required by the release ruleset. These statuses are a
  ruleset bridge, never substitute test executions. An open synchronization PR is updated in place
  when newer shared commits arrive.
- After merging, the controller publishes lightweight Build and Packaged E2E attestations on the
  final release branch. They must verify the original trusted run IDs, exact tested commit, ancestry,
  and identical Git trees; never rerun Minecraft merely to populate a badge or attest a changed tree.
- A port may report packaged Minecraft as not applicable only when protected automation computes
  an exact documentation/site/administration-only diff. Production, loader, overlay, harness,
  contract, visual oracle, matrix, Gradle, workflow, classifier, mixed, malformed, or unknown
  changes always execute the complete contract-selected suite. The dispatched workflow and result
  handler independently revalidate that decision before publishing the stable status context. The
  automatic 1.20.1 anchor port is always `full`, even when its immediate diff would otherwise be
  eligible for N/A, because it certifies the cumulative current `master` generation.
- The marked README release-status table is generated from discovered release branches and each
  branch's matrix. Its workflow updates one idempotent automation PR and never pushes directly to
  `master`. Do not edit its rows manually or add a branch/version list to its workflow.
- Each successful release-branch E2E tree may produce one transient curated
  `pages-e2e-<branch>` handoff. The Pages workflow must discover the same release branches, require
  evidence for every exact current head, validate each source PNG, convert it to a protected WebP
  derivative before fan-in, render with protected `master` code, and deploy the whole site
  atomically. The producer sends an authenticated explicit wake-up because token-created runs do
  not reliably create a recursive completion event. `collected-pages-*` and `pages-cache-*`
  contain only compact derivatives plus the
  source and derivative proof records, never original PNG bytes. Only after that Pages run reaches
  `completed/success` may protected automation replace the branch's single rolling cache and
  retire older caches plus the consumed handoff.
  Never delete the fallback before its replacement succeeds, introduce a second version list,
  publish logs/crash reports, or make Pages a protected release check.
- Every automatic push to `master` propagates in two waves. The synchronizer first targets only
  the matrix-derived Minecraft 1.20.1 release branch. Its Fabric and Forge screenshots are curated
  without any reference image and reviewed independently against every contract expectation, so a
  defect shared by both loaders cannot certify itself. The protected report records semantic
  validity separately from reference similarity. Only a completely clean, loader-complete report
  for the exact bot-owned synchronization commit may produce a seven-day semantic certificate,
  and only after its exact-tree merge is the current 1.20.1 branch head. The certificate binds the
  `master` source SHA, tested and merged anchor SHAs, source run, protected implementation,
  scenario contract, manifest, and normalized report. A protected `repository_dispatch`
  reauthenticates that artifact and releases every other discovered version branch. A stale,
  malformed, incomplete, defective, unavailable, or superseded certificate releases nothing.
  Queue admission also treats that exact authenticated certificate as terminal for the same
  automatic or scheduled anchor generation, and discards superseded anchor generations and closed
  pull-request evidence before any model call. This is identity-level deduplication, not reuse of
  an unpaired semantic verdict.
  No immediate-diff exception may fan out directly: a documentation-only tip can contain an older
  runtime change whose certification is still pending or failed. An explicit manual exact target
  remains an operator recovery path.
- Credential-bearing AI visual judgment pins the exact workflow `github.sha`, authenticates the
  complete protected job graph, and curates raw artifacts on a secretless runner. After the anchor
  is certified, every later-version candidate is paired by semantic `capture_id` with authenticated
  current-head lossless Fabric 1.20.1 Pages evidence; the compact WebP cache is never an AI oracle.
  Only canonical content-addressed RGB PNGs, a bounded manifest, and provenance enter the durable
  queue; raw E2E ZIPs never share a runner with the credential. Protected drains are locked by
  exact queue artifact, so different capsules run concurrently while duplicate wakes coalesce;
  generic recovery sweeps only redispatch an authenticated exact wake. Before those independent
  drains fan out, one global serialized capacity section reuses only a fresh marker from the exact
  protected workflow. Its single tool-free Sonnet probe either opens a short shared ready window or
  records a sanitized rejected/near-limit pause; paused capsules remain durable and the scheduled
  sweep probes again later. A fresh ready probe enumerates and redispatches every authenticated
  pending artifact independently, so coalescing probe contenders never serializes the actual
  reviews. It consumes the bounded headless rate-limit status and optional utilization when
  present, without pretending Claude provides a reliable Pro/Max percentage to headless CI. A
  certifiable anchor is prioritized before the cross-version wave. Each drain triages independent
  loader-grouped chunks
  concurrently with Sonnet and sends suspicious or uncertain frames to concurrent bounded Opus
  verification. The first Opus-confirmed defect publishes a protected generation-bound marker,
  cancels sibling drains and keeps later queue selection from spending more model calls on that
  automatic wave. Each drain also cancels its own outstanding calls on confirmation and reuses paired
  verdicts only under an exact content/expectation/loader/contract/matrix/reviewer/prompt/model
  policy key;
  unpaired anchor semantics are never cached. It keeps provider output private and uploads only the
  protected normalized report or a sanitized retry marker, and deletes a settled queue entry by
  exact artifact id. Build and
  Packaged E2E remain the required exact-head checks for every individual port, and their
  conclusions never depend on model output. The semantic certificate gates only scheduling of the
  cross-version wave: provider failure or a semantic defect deliberately delays that wave instead
  of blessing an unverified baseline.
- A clean semantic review of an exact synchronized release tree starts a separate optional-mod
  compatibility wave. Protected planning derives every `version x loader x mod` lane from the
  release matrix and `e2e/mod-compatibility-contract.json`, including explicit N/A rows. Applicable
  lanes run concurrently, prove that the selected integration activated, and execute both the
  compatibility scenario and the complete base suite with only immutable size/SHA-256/SHA-512
  verified external JARs. Secretless curation pairs the full modded result with the clean
  same-version/loader result; Sonnet semantically reviews every pair, including identical pixels,
  and Opus verifies every non-high-clean result. The first confirmed defect records a durable wave
  block and cancels sibling reviews. Each successful runtime lane curates its own capsule before
  the matrix settles, so a failed sibling keeps the deterministic runtime gate red without erasing
  successful lanes or preventing their concurrent AI review. Authored loader/version exclusions
  remain explicit N/A records and survive lock refreshes. This post-validation signal does not
  replace or weaken Build,
  Packaged E2E, or the independent 1.20.1 semantic certification gate.
- Pages wake and deploy events share one coalescing lock. A parallel release-attestation burst
  retains only the running publication member and the newest pending survivor; the survivor
  rediscovers every exact current release head. Discovery defers while a release attestation is
  active and never duplicates per-branch artifact selection: the bounded collector owns the single
  authoritative selection/provenance pass. Its GitHub API client applies bounded jittered backoff
  to installation-rate-limit and transient transport/server responses; those responses are never
  reclassified as missing evidence.
- Actions artifacts are handoffs, not an archive. Every ordinary upload is retained for one day;
  named seven-day exceptions are automatic-sync packaged evidence/input bundles needed by delayed
  compatibility review, compatibility plans/evidence/capsules/reports/block markers, queued visual
  review capsules, semantic certificates, and rolling exact-policy visual verdict caches. Three
  90-day current-state artifacts remain: the
  SHA-bound Pages cache, the matrix-derived lossless
  Fabric 1.20.1 anchor handoff, and the immutable `release-<release-id>` bundle. The release bundle
  spans protected environment
  approvals and provides bounded recovery for an interrupted publication. After a successful Pages
  replacement, protected rotation deletes by exact artifact ID the superseded cache, ordinary
  consumed `pages-e2e-<branch>` handoffs, older lossless anchor generations, Pages fan-in artifacts,
  and the deploy artifact while preserving the current lossless anchor. Ordinary raw packaged-E2E
  proof retains its one-day window because a concurrent branch attestation may still consume it;
  an automatic synchronization source retains seven days for the post-semantic compatibility wave.
  A completed or terminally invalid AI queue entry is deleted immediately, while a transiently
  failed entry remains bounded for retry. A
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
- Shared behavior changes start on `master`. A version-only fix starts on its release branch and
  must be reflected in canonical `master` sources when the same behavior applies elsewhere.
- A shared change is not repository-wide delivery merely because it reached `master`. The
  synchronizer must create one port PR for every discovered release branch; each PR must pass its
  exact-head Build and Packaged E2E gates, merge into its target, and receive successful final
  exact-tree attestations. Until that is true for every target, report the outstanding ports rather
  than calling the change delivered everywhere. Name every intentional branch exclusion in the
  issue or source pull request; never let an omitted port become an implicit support policy.

## Task routing

Choose the target before editing:

| Change scope | Start from | Expected destination |
|---|---|---|
| Shared behavior, security, tests, automation, or general documentation | `master` | Workflow-owned port PRs to release branches |
| One exact Minecraft version or loader pair | That release branch | Only that release branch |
| Version/loader support inventory | `master`, matrix first | New or updated release branch after matrix validation |
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

The current synchronizer attempts every release branch for a new `master` change. If intended
scope excludes a version, make that exception explicit before editing. Do not silently spread broad
Stonecutter conditionals or create a second branch inventory; choose a narrow adapter/overlay,
version-branch change, or explicit synchronization-policy change and document the decision.
