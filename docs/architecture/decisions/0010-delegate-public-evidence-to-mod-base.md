# 0010. Delegate public evidence to mod-base

Date: 2026-09-25

## Status

Accepted. Partially supersedes [ADR 0002](0002-publish-curated-e2e-evidence-with-github-pages.md):
its evidence-handling, publication, retention and rendering mechanics now belong to the pinned
mod-base kit, and its implementation-identity consequence is replaced as described below. ADR
0002's decision to publish one advisory static site through GitHub Pages, its restriction of the
`github-pages` environment to `master`, its deploy-only `pages: write`/`id-token: write` grant and
its landing page and gallery stand.

It also partially supersedes [ADR 0004](0004-anchor-ai-visual-review-to-1-20-1.md): the reviewer
no longer selects a lossless `pages-e2e-<branch>` handoff as its reference, and the kit's
`mb-anchor--` artifact replaces the protected 90-day raw-anchor retention. It amends
[ADR 0003](0003-standardize-packaged-e2e-platform.md)'s Pages paragraph: the wake event, the
publication identity and the rotation now follow the decisions below.

## Context

Quick Skin publishes packaged-E2E screenshots, their provenance and the optional-mod compatibility
pairs through its own pipeline. At `fd8e7dbf1` that pipeline was 4,793 lines of privileged Python
under `scripts/pages/` (`build_site`, `evidence`, `select_artifact`,
`select_compatibility_artifact`, `rotate_artifacts`, `publication_progress`), 2,294 lines of
static front end under `site/`, a 1,496-line `pages.yml`, and 4,567 lines of tests for them. Block
Pops, the team's other Minecraft mod, maintains a separate implementation of the same pipeline
with different schemas, trust rules and front end. A security or correctness fix had to be found,
written and reviewed twice, and the two implementations had already diverged: Block Pops rebuilt
raw manifests from `result.json` in protected code and checked its exact `GITHUB_WORKFLOW_REF`,
while Quick Skin re-measured comparisons and had the stricter runtime-evidence rule.

Quick Skin also carried historical weight that its schema-3 shared source no longer uses: readers
for evidence schemas 1 to 7 and compatibility schemas 1 to 5, the `pages-cache-<branch>` fallback,
the `--allow-continuation` carry-forward of ordinary evidence, and a visual-review reference
comparison that consumed `pages-e2e-*` handoffs but is unreachable under schema 3 because shared
curation exits before it. Its Pages wakes were `repository_dispatch` events, which need
`contents: write` in the producing job.

## Decision

- The generic pipeline moves to one public kit, `The-Plum-Team/mod-base`, which Quick Skin runs at
  one pinned commit and never vendors. Every mod-base reference is
  `The-Plum-Team/mod-base/<path>@<40-hex> # vX.Y.Z`, all with the same SHA and version.
- Quick Skin keeps what only it knows: the adapter `scripts/pages/mod_base_adapter.py` and its
  configuration `site/mod-base.json`, which project the scenario contract, the release matrix, the
  delegated runtime reuse, the feature selection and the compatibility bundle onto the kit's
  schemas. The kit runs the adapter in isolated child processes and re-verifies every result.
- **Two-part identity.** Only the code fixed by the protected mod commit may validate or render
  public evidence: the adapter and configuration at `github.sha`, plus the mod-base commit that
  `github.sha` pins and that is reachable from mod-base `main`. The caller-owned `verify-kit` job
  derives the executing kit commit from the run's `referenced_workflows`, requires it to equal every
  pin in `pages.yml` at `github.sha` and to be reachable from mod-base `main`, and every kit job
  rechecks the kit tree digest. Every manifest and `_site/build.json` record both identities.
- **Deploy stays caller-owned.** `pages.yml` is a managed, byte-identical caller. Its `deploy`
  job is the only holder of `pages: write` and `id-token: write`, checks out nothing, rechecks
  the live head and deploys the artifact the kit's `publish` workflow built. `verify-kit` and
  `request-rotation` are caller-owned as well. The only local part of the caller is the
  `ext-feature-coverage` extension job, which keeps the existing feature-coverage request.
- **Wakes.** Producers wake Pages with `workflow_dispatch` from jobs that hold only
  `actions: write` and check out nothing: `notify-pages` in `on-demand-e2e.yml` and `notify-family`
  in `mod-compatibility-review.yml`. The `pages-evidence-ready` and
  `pages-compatibility-evidence-ready` repository dispatches are retired, and `publish-evidence`
  drops `contents: write`. An hourly schedule and `operation=manual` remain the recovery paths.
- **Generations.** Evidence uses new `mb-*` artifact names (`mb-handoff--`, `mb-anchor--`,
  `mb-cache--`, `mb-family-handoff--`, `mb-family-cache--`, `mb-baseline--`) and new
  `mod-base.*` schemas. There are no converters: the kit never reads or deletes a legacy
  `pages-*` artifact, which expires under its own retention, and the first post-merge generation
  regenerates everything. Until every key has v1 evidence, admission keeps the previous site.
- **Schema evolution.** A kit release reads schema versions N and N-1 and writes N; within one
  version only optional fields are added. The previous rolling-cache compatibility rule is
  replaced by this N/N-1 rule.
- **`runtime_evidence` is mandatory** on every published frame: 1 to 4096 characters, non-empty
  after trimming, with no control character. This is Quick Skin's existing bounded
  passed-assertion rule, which was optional only for older rolling caches.
- **Retired:** `--allow-continuation` and the ordinary carry-forward of evidence to a newer head;
  the historical schema-2 reference comparison in `visual-review.yml` and
  `visual-review-drain.yml` (ADR 0004's `pages-e2e-<branch>` reference), which now fails closed;
  and the legacy evidence, compatibility and cache readers. Tag `pre-mod-base-gallery`, on the
  `master` commit just before this change, keeps them auditable for historical recovery. The compatibility family keeps its own carry-forward:
  the adapter decides the impact and the kit proves the ancestry itself.
- **Pre-merge pin check.** The Build `policy` job runs
  `python3 scripts/ci/mod_base_kit.py verify --network` and `run template check --repo .`. That
  check runs as pull-request code, at the same trust level as every other Quick Skin policy test;
  review and the rulesets remain the defense against a malicious pull request, and `verify-kit` is
  the runtime defense against an impostor kit commit.
- **Kit bumps pay the full gates.** A bump is `mod_base_kit.py bump --to vX.Y.Z` on a draft pull
  request landed through a batch. It edits `.github/workflows/*`, which the schema-3 selection
  treats as unowned, so it runs the complete Build, every Packaged E2E lane and the optional-mod
  wave its classifiers select. No selection-policy exception exists for pin-only diffs, because
  the producer path itself changes with every bump. Dependabot ignores `The-Plum-Team/mod-base*`
  and `actions/deploy-pages`, which the managed caller pins, so both move only with a kit bump.
- The managed files are the bootstrap `scripts/ci/mod_base_kit.py`, `.gitattributes`,
  `docs/ai/shared/REPOSITORY.md`, `docs/ai/shared/PUBLIC-EVIDENCE.md` and the managed region of
  `pages.yml`; `template check` fails on any byte drift. `AGENTS.md` imports the two shared
  documents before Quick Skin's four local ones.

## Consequences

- ADR 0002's consequence that the repository owns a small static renderer and a strict validator,
  and that only that exact code may validate or render, is replaced by the two-part identity above.
  Changing privileged publication code now takes two protected merges: one to mod-base `main`,
  released as an immutable tag, and one pin bump here.
- Quick Skin gains the stronger half of each lineage: the collector re-derives every manifest by
  running the adapter's `collect` over the exact handoff, every kit job checks the exact
  `GITHUB_WORKFLOW_REF`, derivatives must be byte-identical to the collector's own encoding, and
  every bundle carries an exact file inventory.
- Quick Skin deletes its six generic Pages modules, its front end and their tests; the kit's tests
  and its conformance run against Quick Skin's real contract and matrix replace them. The
  compatibility validator, the feature composition, the target inventory and the review pipeline
  stay in Quick Skin.
- The front end is the kit's, configured by `site/mod-base.json`; the public data becomes
  `mod-base.gallery` and `mod-base.site` v1, so an external reader of the old
  `gallery-data.json` shape must follow.
- Every kit bump costs a complete Build and all 34 Packaged E2E lanes, and normally a
  compatibility wave and AI review. Bumps should therefore be infrequent and batched.
- After the merge the site stays at its previous deployment until the first complete post-merge
  generation publishes, and compatibility pairs return only after the next compatibility wave.
- Selective generations keep publishing as they did before the adoption. Quick Skin selections
  re-capture individual checkpoints (the `hud-preview` selection re-captures 2 of the 63 `full`
  ones), and the kit composes them per frame, as Quick Skin's schema-7 view did: every frame keeps
  the epoch, tested run and JAR its pixels came from; a partly re-captured lane is the selected
  execution's record and records the baseline execution of its older frames as `baseline_run`;
  and R3 re-verifies both epochs against their sources. This needs kit `v0.9.2` or later, because
  earlier releases refused a lane that mixed the two epochs. The adoption pins `v1.0.1`, whose
  conformance run also composes a real Quick Skin selection with its certified baseline.
- Rollback is a revert of the adoption: the kit never touched the old artifacts, so the old
  pipeline needs only one fresh E2E dispatch. Disabling `pages.yml` is the kill switch; the
  deployed site stays online and producers keep working.
- The recovery tag is an owner step, pushed before the adoption merges, on the `master` commit it
  replaces:

  ```bash
  git tag -a pre-mod-base-gallery <master-sha-before-the-merge> -m "Last master before mod-base"
  git push origin refs/tags/pre-mod-base-gallery
  ```
- Platform behaviour that only GitHub Actions can show (cross-repository `referenced_workflows`,
  callee job names, deploying a callee-built artifact) was proven by the kit's canary repository
  at `v0.9.3` and again at `v1.0.0`. The pinned `v1.0.1` changes no action or managed file: only
  the conformance simulation, the template manifest checks and the tree-digest literal of the
  kit's reusable workflows. A platform failure would still keep the previous site.
