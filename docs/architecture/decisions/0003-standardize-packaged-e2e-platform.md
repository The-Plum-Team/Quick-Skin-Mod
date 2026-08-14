# ADR 0003: Standardize the packaged E2E platform

- Status: Accepted
- Date: 2026-08-06
- Amended by: ADR 0004 on 2026-08-10 for queued read-only visual review
- Scope: shared packaged-runtime, visual-evidence, Pages, and version-port automation

## Context

Quick Skin exercises every supported release branch with real packaged Minecraft clients. That
gate caught cross-version renderer, menu, networking, and loader regressions that unit tests could
not see, but its control plane accumulated several independent descriptions of the same suite:

- Java scenario classes owned the executable actions and step order;
- Python repeated expected steps, captures, comparisons, probes, client count, and launch order;
- a visual JSON catalog repeated capture identities;
- the release matrix and workflows repeated the four scenario names;
- release and pull-request workflows repeated the packaged-runtime bootstrap.

The old runtime cache mixed mutable run evidence with downloaded dependencies and installed client
trees. It was transactional only at the final client-directory rename, had an incomplete recipe
identity, accepted unpinned Maven downloads after first use, and had no bounded local collection.
Stale local profiles could also enter a later gallery because evidence discovery scanned every
profile below one long-lived output directory.

GitHub-hosted jobs did not persist that directory across runs, so the observed poisoned install was
a partial tree reused by later scenarios in the same job, not a cross-run Actions cache. The fix for
that incident made installation transactional, but deletion remained a correctness mechanism rather
than housekeeping.

The visual AI job was described as advisory but lived inside the required workflow. The port
controller authenticated the conclusion of the complete run, so waiting for that advisory job was
still necessary. In addition, evidence uploaded by `GITHUB_TOKEN`-created final attestations did not
recursively trigger the `workflow_run` Pages listener. The last good site remained available, but a
successful sixteen-branch generation could finish without publishing its new evidence.

One measured sixteen-branch propagation used roughly 12.5 runner-minutes in the controller, 49.1 in
Build, 304.7 in exact-head packaged E2E, and 41.3 in advisory visual review. Minecraft scenarios,
not tool bootstrap, dominated the runtime. At the same time, live non-expired artifacts occupied
about 3.44 GiB, mostly short-lived packaged evidence and Pages handoffs awaiting consumption.

## Decision

Adopt one versioned scenario contract at `e2e/scenario-contract.json`.

The contract owns scenario ids, execution profiles, orchestration mode, roles, ordered step ids,
mandatory assertions, capture metadata, semantic probes, and directed comparison profiles. Capture
ids are derived from scenario, role, and step and are never stored as another input. Python
consumers expose derived views from this contract. Gradle invokes the same fail-closed Python parser
and generator to emit typed Java scenario data under `build/generated`; the harness validates the
executable Java graph against it before the first step runs. Java remains the owner of actions,
readiness predicates, and private programmatic assertions.

Remove scenario names and suite selection from each release matrix. A deterministic port-time
normalizer deletes only those legacy fields while preserving every version/loader fact; matrix
outputs obtain their scenario lists from the contract's execution profiles.

Bind the canonical contract SHA-256 to harness reports, packaged results, and public evidence. Every
consumer rejects missing, extra, reordered, or hash-mismatched steps, captures, roles, comparisons,
and probes. In particular, a non-capture step emitting a screenshot is an error. Fixed independent
canaries and mutation tests protect oracle calibration; oracle fixtures are not generated from the
thresholds they are intended to test.

Separate mutable execution state from reusable runtime material:

- `RunWorkspace` creates a fresh, explicit evidence namespace for each orchestrator invocation;
- `RuntimeStore/v1` stores verified blobs and trees by SHA-256;
- a recipe includes schema, OS, architecture, Java major, Minecraft and loader versions, installer
  SHA-256, launcher-library version, and normalization revision;
- construction uses a per-recipe lock, same-filesystem staging, verified tree manifests, atomic
  promotion, and OS-backed leases held continuously through materialization; crashed owners are
  detected by their released lock rather than an unsafe timestamp heuristic; active names are
  atomically retired to identity-bearing quarantine before retryable bounded deletion;
- age/size collection never removes a leased object and is only housekeeping: an invalid or old
  recipe is always a cache miss, whether or not collection has run;
- dependency JARs use the existing strict Gradle verification metadata as their SHA-256 authority,
  avoiding a second checksum inventory.

Do not upload `RuntimeStore` from GitHub-hosted jobs. It is job-local there and persistent only for
developers or explicitly managed self-hosted runners. Upload only the current run's bounded evidence.

Move Minecraft API drift behind `VanillaShim` (or a later typed E2E driver) and enforce that known
drift seams do not return to scenario classes. Quick Skin private-state probes may remain reflective
because they test mod-owned implementation facts rather than Minecraft compatibility.

Extract the credentialless packaged-runtime bootstrap into one local composite action shared by
pull-request and release workflows. Move AI image judgment to a separately authenticated advisory
workflow, so the required workflow can finish and be attested without weakening the required gate.
Automated ports authenticate the complete target-branch lane set and require the gate's controller,
attestation workflow, contract, common Java harness, Gradle bootstrap/wrapper, and generation paths
to be byte-identical to protected `master` before publishing a required status. A protected loader
bootstrap contract additionally binds each active loader's exact `src/e2e` tree and the complete
release-branch loader build script; merely applying a convention last is not an authority boundary.

Split visual judgment into authentication, secretless curation, a durable capsule queue, fresh
credential-bearing review, and exact-id cleanup. Curation proves the protected job graph and
matrix-row/scenario/JAR product, fully decodes source images, and serves only bounded metadata-free
RGB PNGs named by their new byte hash plus an auditable proof. The model can read only bounded
manifest/image inputs; the shell privately captures its structured stdout. Protected code
revalidates the capsule, bounds and normalizes the report, never uploads raw provider output, and
retains a failed queue entry only for bounded retry. AI repair/conflict
jobs install their pinned CLI only from protected package metadata and run in safe mode with an
explicit shell-free tool set, scoped file permissions, no candidate configuration, and no persisted
session/history. Failure logs and artifacts are identity-bound, size/count bounded, and extracted by
protected link/traversal/zip-bomb policy. Candidate port and repair code is compiled and tested
without write credentials in one job; a fresh narrow writer reproduces the exact validated tree
using protected policy and hook-free Git plumbing before it authenticates and pushes.

Partition version-port conflicts before invoking AI. A pure protected classifier receives the
complete original conflict set and the target matrix. It permits only four exact mechanical
policies for protected divergence: source-preferred three-way merge for named shared
guidance/runtime documents, target retention for the release matrix, and deletion of a loader build
script only when that loader is inactive, or deletion below a legacy overlay root only when that
exact root is inactive. Unknown protected conflicts, active-loader build conflicts, and
active-overlay conflicts fail closed. Only unprotected residual paths may enter the model capsule.
Afterward, protected renderers normalize the matrix-owned README and workflow-guide profiles; the
credentialless validator and narrow writer independently rerun a protected no-commit merge
controller from the exact parents and compare its stable evidence. The full proposed patch is
applied only to an alternate index whose complete tree id is authenticated; the controller copies
only recomputed AI-conflict entries into the real merge. Protected renderers then run, and both jobs
require the resulting tree to match the alternate-index and plan trees exactly. The writer never
executes candidate code.

After a release branch publishes a validated Pages handoff, send an explicit authenticated event to
the protected Pages workflow. That workflow pins one protected `master` implementation SHA, validates
the event and current branch inventory, coalesces concurrent wake-ups, deploys atomically only when
all exact heads have usable evidence, and then invokes the existing exact-id rotation. A failed or
premature wake-up preserves the previous site and caches.

Permit a packaged-Minecraft “not applicable” result only through a deterministic protected
allowlist. Documentation, static-site presentation, and isolated administration policies may skip
Minecraft after their normal Build/unit/security checks. Production code, loaders, overlays,
harness, scenario contract, visual oracle, matrix, Gradle, workflows, the classifier itself, mixed
or unknown changes always run the complete suite. AI never selects tests, versions, thresholds, or
classification.

Keep exact-version release branches. This change removes duplicated E2E control-plane knowledge and
unnecessary runs; it does not change release, hotfix, rollback, or marketplace boundaries.

## Consequences

Adding or changing a scenario is a contract-first change followed by executable Java behavior. A
consumer cannot silently invent another scenario list or infer multiplayer behavior from an id
prefix. Contract, run, cache, and comparison identities become visible in failure evidence and job
summaries.

Old runtime-store schemas and changed recipes become automatic misses. Local disk use is bounded,
and current-run evidence cannot be contaminated by an older profile. The additional hashing cost is
small compared with launching Minecraft and buys deterministic reuse.

Advisory visual review and Pages can fail without delaying or weakening a required version-port
gate. Their source run, code SHA, branch SHA, and artifact identity remain authenticated. Pages may
receive several cheap wake-ups while a generation converges, but concurrency and exact-head
validation allow only a complete current inventory to replace the site.

The impact allowlist is intentionally narrower than the set of changes that might theoretically be
safe. A false negative costs runner time; a false positive could hide a runtime regression, so an
unrecognized path must run Minecraft.
