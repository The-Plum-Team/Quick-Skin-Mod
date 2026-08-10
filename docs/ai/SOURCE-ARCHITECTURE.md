# Source-set architecture

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Canonical sources

These are the primary implementation trees:

- `common/src/main`: shared client, server, networking, storage, and compatibility code.
- `fabric/src/main`: canonical Fabric entry points and loader integration.
- `neoforge/src/main`: canonical NeoForge entry points and loader integration.
- `common/src/e2e` plus each loader's `src/e2e`: the separate packaged-runtime test mod.
- `common/src/test`: loader-independent JUnit regression tests compiled against the common 1.21.6
  node.

Stonecutter preprocesses each canonical `src/main` tree into detached generated sources. Never edit
generated or staged output under `common/versions`, `fabric/versions`, `neoforge/versions`, any
`build/` directory, `.gradle/`, `.architectury-transformer/`, `e2e-out/`, or `build/release/`. Fix
the tracked canonical source or active overlay instead.

`gradle/e2e-harness-conventions.gradle.kts` owns the exact E2E source roots, classpaths, generated
contract source, and harness archive tasks for every active loader node. Loader build scripts may
only bind that protected convention and are authenticated byte-for-byte for their release branch by
`e2e/loader-bootstrap-contract.json`, together with the exact loader entrypoint and manifest tree.
The contract is an integrity allowlist selected by the branch's matrix, not a support-discovery
inventory. Any deliberate edit below `<loader>/src/e2e` or to an active loader build script must
update its protected digest contract and mutation tests on `master` in the same change.

## Active `legacy*` overlays

`legacy` means an active era-specific compatibility overlay, not dead or unsupported code. For an
overlay lane, Gradle performs this operation:

```text
canonical src/main
  -> Stonecutter-generated sources for the selected version
  -> remove generated files whose relative paths exist in the overlay
  -> copy the overlay files
  -> compile generated/consolidated/main/java

canonical src/main/resources
  -> remove resources whose relative paths exist in the overlay
  -> copy the overlay resources
  -> process generated/consolidated/main/resources
```

An overlay file therefore replaces the canonical file at the same relative path. The active
overlays are:

| Module | Minecraft | Active overlay |
|---|---|---|
| common | 1.21.6 | `common/src/legacy1_21_6` |
| fabric | 1.21.6 | none; canonical output |
| neoforge | 1.21.6 | none; canonical output |

The common overlay contains only the additive 1.21.6 picture-in-picture render backend, payload
network transport, and Minecraft platform adapter.
Fabric and NeoForge use their Stonecutter-generated canonical sources directly on this branch.

Keep overlays narrow. Prefer a small adapter or a Stonecutter version branch over copying an entire
service, screen, or handler. When a class exists in an active overlay:

1. Make the intended behavior clear in the canonical implementation first when possible.
2. Find every active overlay of the same relative path.
3. Apply the equivalent behavior using that era's Minecraft API.
4. Compile and test every affected version/loader lane.

Changing only `src/main` does not fix a lane whose overlay replaces that file.

## Retired `src/v*` snapshots

The copy-based `src/v*` migration snapshots are retired and must not be restored. Their final state
is preserved by the `pre-scalability-oracle-retirement` Git tag. Consult that tag only as a parity
reference, then make the effective change in canonical sources or an active overlay. Matrix
validation rejects reintroduced `src/v*` content and live Java classes with more than two copies.
See `ORACLE-RETIREMENT.md` for the retirement gate and resource-routing details.

## Version-port control plane

- `scripts/ci/version_port_merge.py` is the sole protected owner of version-port Git merge
  semantics. Given exact clean target/source commits, it runs a hook-free no-commit merge,
  authenticates `MERGE_HEAD`, snapshots the complete original index, applies the classifier's
  mechanical policies, and emits stable evidence. For an AI resolution it accepts an external
  candidate index only with its exact tree id and copies only the recomputed `ai_paths`; it never
  imports another candidate entry.
- `scripts/ci/version_port_conflicts.py` is the pure, fail-closed classifier for the original Git
  conflict set. It may assign a protected path only to an exact reviewed mechanical policy. Shared
  guidance and runtime documents use a source-preferred three-way merge, the release matrix uses
  the target version, and a build script may be deleted only when its loader is inactive in that
  target matrix. Unknown protected paths and active-loader build conflicts abort the port; only
  unprotected residual conflicts may reach AI.
- `scripts/release/branch_readme.py`, `scripts/release/e2e_readme.py`, and
  `scripts/release/workflow_guidance.py` are the protected renderers for matrix-owned branch
  profiles. The synchronizer runs them after conflict resolution, stages their exact outputs, and
  reruns them in both the credentialless validator and the narrow writer. Do not hand-maintain
  their marked blocks or version-specific test-task anchors.

## Visual evidence and static-site sources

- `e2e/scenario-contract.json` is the sole packaged-suite control-plane source. It owns execution
  profiles, scenario orchestration, roles, ordered steps, mandatory assertions, screenshot
  checkpoints, review metadata, semantic probes, and comparisons. Capture identity is derived as
  scenario + client role + report step; filenames and ordinals are payload details only.
- `e2e/scenario_contract.py` is the fail-closed typed Python reader.
  `e2e/generate_contract_java.py` reuses that exact parser when Gradle generates typed Java ids and
  expected graphs under `build/generated`; generated Java is never tracked. Do not add a second
  partial JSON parser to Gradle.
- `e2e/runtime_store.py` separates immutable reusable runtime blobs/trees from mutable run state.
  Its content-addressed recipes include every compatibility input, and callers materialize a fresh
  copy before launch. `RuntimeStore` is never uploaded as evidence.
- `e2e/visual_evidence.py` reads successful `result.json` reports, verifies the scenario-contract
  hash and exact graph, PNG containment, full decode, dimensions, SHA-256, probes, and comparisons,
  and exposes the shared evidence model used by the AI review and public site.
- `e2e/visual_review.py` binds each raw artifact to exactly one protected matrix row and its complete
  scenario product, requires one production JAR digest, derives the stable Fabric 1.20.1 reference
  identity from protected `master`, and pairs every candidate with the same semantic capture from
  authenticated lossless raw Pages handoff evidence. It atomically re-encodes both sides as
  same-sized, metadata-free RGB PNGs. `e2e/check_visual_review.py` validates the all-single or
  all-paired bounded capsule and normalizes bounded model output. `e2e/visual_review_runner.py`
  removes byte-identical pairs, runs bounded Sonnet triage chunks, selectively escalates bounded
  Opus verification chunks, and keeps raw provider output private.
- `scripts/pages/evidence.py` creates and validates a small branch-scoped raw handoff, then
  atomically compacts a validated bundle to protected WebP derivatives. It may copy only contracted
  screenshots and structured provenance—never runtime logs or arbitrary HTML. The compact schema
  preserves separate source and derivative identities, hashes, dimensions, pixel metrics, and
  comparison metrics. Raw PNG bytes normally stop at the one-day E2E handoff; the single
  matrix-derived Fabric 1.20.1 visual anchor is retained losslessly and rotated as current state.
- `scripts/pages/select_artifact.py` authenticates exact-current E2E handoffs and SHA-bound rolling
  caches, then selects the newest valid source. Its AI mode requires a raw handoff and refuses a
  compact fallback. A branch-only cache name is migration fallback only.
- `scripts/pages/rotate_artifacts.py` owns post-deployment retention. It may delete only exact
  Actions artifact IDs whose protected run provenance, branch, SHA, age, and successful replacement
  have all been revalidated, including Pages-run intermediates; it never implements screenshot or
  version discovery itself. It preserves exactly the current validated raw visual-anchor handoff
  and retires only its older generations. Raw packaged-E2E artifacts remain retention-bound inputs
  for concurrent attestations and are outside rotation ownership.
- `scripts/ci/visual_review_queue.py` authenticates queued capsules, completed reports, and
  sanitized attempt markers from protected workflow owners, applies retry cooldowns, and selects
  the oldest eligible source. Queue state lives in Actions artifacts rather than pending workflow
  runs, so GitHub concurrency coalescing cannot lose a review. The fixed drain concurrency group
  covers selection through exact-id cleanup; overlapping wakes therefore cannot reserve the same
  oldest capsule before either reaches the model job.
- `scripts/ci/visual_review_impact.py` is the narrow fail-open cost filter for replicated version
  ports. Protected automation supplies a complete, bounded GitHub PR file inventory; only the two
  visual-review workflows, the classifier itself, CI tests, and documentation may skip another
  model review. Unknown paths, malformed inventories, and unsafe rename origins remain reviewable.
- `scripts/ci/gradle_cache_policy.py` is the fail-closed writer policy for Gradle state. It permits
  writes only from protected `master`; release branches, packaged E2E, and release jobs remain
  read-only.
- `scripts/ci/prune_actions_caches.py` owns bounded cache hygiene. It discovers branches, active
  runs, exact successful Build jobs, and caches from paginated GitHub APIs. It revalidates each
  immutable cache before deleting by exact ID. Absent-branch caches are disposable; on a live
  branch, only superseded SHA-bearing Gradle-home generations are eligible after preserving the
  latest successful generation for each OS/job/cache-version restore family. A family with no proven
  successful generation and unknown keys are retained. Any potentially cache-consuming active run
  protects the complete repository cache inventory because topic runs may restore `master` and pull
  requests may restore their base branch. Only the protected pruner's own run is ignored, because
  that workflow never configures Gradle; an unrecognized workflow fails closed as a potential
  consumer.
- `scripts/pages/build_site.py` combines exact compact branch bundles and copies their already
  content-addressed WebP assets while rendering the tracked assets under `site/`. `site/` contains
  presentation code, not a support/version inventory; supported versions always come from
  validated evidence discovered from release branches.
- `_site/`, `public-evidence/`, and downloaded Actions artifacts are generated output. Do not commit
  them or edit them as source.
