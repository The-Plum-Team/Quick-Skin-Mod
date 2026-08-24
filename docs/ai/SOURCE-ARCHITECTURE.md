# Source-set architecture

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Canonical sources

These are the primary implementation trees:

- `common/src/main`: shared client, server, networking, storage, and compatibility code.
- `fabric/src/main`: canonical Fabric entry points and loader integration.
- `neoforge/src/main`: canonical NeoForge entry points and loader integration.
- `common/src/e2e` plus each loader's `src/e2e`: the separate packaged-runtime test mod.
- `common/src/test`: loader-independent JUnit regression tests compiled against the common 1.21.3
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
| common | 1.21.3 | `common/src/legacy1_21_3` |
| fabric | 1.21.3 | none; canonical output |
| neoforge | 1.21.3 | `neoforge/src/legacy1_21_3` |

The NeoForge whole-file replacements are genuine 1.21.3 rewrites of `CapeLayerMixin`,
`PlayerRendererMixin`, `PlayerInfoMixin`, `MixinAbstractClientPlayer`, and the thin NeoForge 21.3
`PlatformHelperImpl` loader adapter. `SkinManagerMixin` deliberately remains canonical so the
1.21.3 lane retains its `HttpTexture` bridge and `CompletableFuture<PlayerSkin>` contract.
Common overlay Java files are additive compatibility classes, the exact 1.21.3 cape render-state
adapter, or thin render/network/platform backends.

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
  the target version, a build script may be deleted only when its loader is inactive in that
  target matrix, and a path below a legacy overlay may be deleted only when that exact overlay root
  is absent from the target matrix. The one reviewed datapack-layout migration moves the protected
  `functions` files and tags to 1.21+'s singular `function` paths, rewrites the three renamed game
  rules from the target matrix's single runtime version, and removes every obsolete plural path.
  Unknown protected paths, active-loader build conflicts, and
  active-overlay conflicts abort the port; only unprotected residual conflicts may reach AI.
- `scripts/release/branch_readme.py`, `scripts/release/e2e_readme.py`, and
  `scripts/release/workflow_guidance.py` are the protected renderers for matrix-owned branch
  profiles. The synchronizer runs them after conflict resolution, stages their exact outputs, and
  reruns them in both the credentialless validator and the narrow writer. Do not hand-maintain
  their marked blocks or version-specific test-task anchors.

## Visual evidence and static-site sources

- `e2e/scenario-contract.json` is the sole packaged-suite control-plane source. It owns execution
  profiles, scenario orchestration, roles, ordered steps, mandatory assertions, the fixed
  1920x1080 screenshot size, screenshot checkpoints, authored review regions, semantic probes, and
  comparisons. Capture identity is derived as
  scenario + client role + report step; filenames and ordinals are payload details only.
- `e2e/scenario_contract.py` is the fail-closed typed Python reader.
  `e2e/generate_contract_java.py` reuses that exact parser when Gradle generates typed Java ids and
  expected graphs under `build/generated`; generated Java is never tracked. Do not add a second
  partial JSON parser to Gradle.
- `e2e/runtime_store.py` separates immutable reusable runtime blobs/trees from mutable run state.
  Its content-addressed recipes include every compatibility input, and callers materialize a fresh
  copy before launch. `RuntimeStore` is never uploaded as evidence.
- `e2e/visual_evidence.py` reads successful `result.json` reports, verifies the scenario-contract
  hash and exact graph, bounded printable passed-assertion messages, PNG containment, full decode,
  dimensions, SHA-256, probes, and comparisons, and exposes the shared evidence model used by the
  AI review and public site.
- `e2e/mod-compatibility-contract.json` is the reviewed optional-mod artifact lock. It owns the
  supported integration ids, applicability rules, authored loader/version exclusions with reasons,
  Modrinth project identities, and every immutable
  external JAR URL/filename/size/SHA-256/SHA-512 tuple. `e2e/mod_compatibility.py` is its fail-closed
  runtime reader, planner, and materializer. `e2e/update_mod_compatibility_lock.py` is the only code
  allowed to query Modrinth or select a newest upstream release; it is an explicit maintainer tool,
  never part of an E2E run.
- `e2e/mod_compatibility_visual.py` authenticates one complete modded result and its clean
  same-version/loader packaged baseline, verifies complete release-plus-compatibility scenario
  coverage, then pairs exactly the compatibility-profile captures by semantic identity. It emits
  only content-addressed metadata-free images plus an exact source/implementation/contract/artifact
  proof. `.github/workflows/mod-compatibility-e2e.yml` owns admission, the fully parallel
  artifact-by-mod runtime matrix, and per-successful-lane secretless curation; one failed matrix
  sibling never suppresses capsules already produced by successful lanes.
  `.github/workflows/mod-compatibility-review.yml` is the separate credential-bearing consumer; it
  downloads only curated capsules, inherits exact authored-region matches, groups exact-equivalent
  pairs behind one representative, sends every remaining group to Haiku, escalates only a concern
  or confidence below high to Opus, and publishes a durable source-wave block before
  cancelling siblings after a confirmed defect. Its authenticated source queue shares the global
  Claude capacity circuit, requires a fresh probe for each source, preserves one completion marker
  per clean lane, and reschedules only unfinished lanes after a provider pause. Its matrix remains
  lane-parallel, while protected admission fields bound aggregate nested model calls and ramp their
  starts. The producer suppresses a delayed stale wake, the
  direct consumer requires its source implementation to equal the protected current `master`, and
  every parallel lane rechecks live `master` before capsule download or model admission.
- `scripts/pages/collect_compatibility.py` is the protected post-review publisher. It authenticates
  the exact compatibility source plan, every source capsule, every complete normalized lane report,
  and the source completion marker before `scripts/pages/compatibility_evidence.py` projects the
  complete clean wave into a strict public bundle. That projection retains only the two
  `mod-compatibility` checkpoints as paired clean/modded 1280x720 WebPs, source and derivative
  metrics, deterministic assertions, clean booleans, and provenance; ordinary-suite captures stay
  in the authenticated runtime artifact and never enter the model capsule. Raw provider text stays
  in short-lived private artifacts. Manual publication recovery consumes the same already-complete
  reports and never calls a model. Public schema v2 binds `reviewed_frame_count` to the two reviewed
  checkpoints; the validator keeps schema v1 readable for older rolling caches whose count covered
  the complete scenario contract.
- `scripts/pages/select_compatibility_artifact.py` selects either that short-lived handoff or the
  newest successful protected Pages cache. Pages may carry its `coverage_sha` to a current release
  descendant only when `scripts/ci/mod_compatibility_impact.py` proves the complete intervening diff
  cannot affect optional-mod compatibility. A cache whose scenario or compatibility contract has
  been superseded is omitted as unavailable; every other validation failure remains fatal.
  `scripts/pages/build_site.py` validates and renders the optional bundle beside ordinary release
  evidence; `scripts/pages/rotate_artifacts.py` retains one
  current compatibility cache per covered branch and retires older caches, consumed handoffs, and
  fan-in artifacts only after a successful atomic deployment.
- `e2e/visual_review.py` binds each raw artifact to exactly one protected matrix row and its complete
  scenario product, requires one production JAR digest, derives the stable Fabric 1.20.1 reference
  identity from protected `master`, and pairs every later-version candidate with the same semantic
  capture from authenticated lossless raw Pages handoff evidence. For a 1.20.1 source it instead
  requires complete, identical Fabric/Forge capture-id sets and exposes each frame without any
  reference. It requires both sides to remain exactly 1920x1080 and atomically re-encodes candidates
  and references as metadata-free RGB PNGs without resizing. `e2e/visual_similarity.py` computes
  exact decoded-RGB fingerprints for contract-authored regions plus non-authoritative perceptual
  routing metrics. `e2e/check_visual_review.py` recomputes those values while validating the
  all-single or all-paired bounded capsule,
  including each capture's exact passed assertion as `runtime_evidence`, keeps `semantic_valid`
  independent from nullable `matches_reference`, and normalizes bounded model output.
  `e2e/visual_review_runner.py` sends every uncached unpaired anchor frame through semantic Haiku
  triage, inherits exact paired region matches, shares one model verdict across exact-equivalent
  paired versions, runs independent loader-grouped chunks concurrently, creates deterministic
  1280x720 model-only copies without altering the authenticated 1920x1080 evidence, and pipelines
  only concerns or confidence below high into concurrent bounded Opus verification. A clean
  high-confidence Haiku result is final; perceptual metrics never create or route a verdict. The
  runner cancels outstanding calls after the first
  confirmed defect, publishes a protected exact-generation block, cancels sibling drains, and keeps
  raw provider output private.
  `e2e/visual_review_cache.py` validates and combines bounded immutable exact-policy verdict
  cache shards. A paired hit binds candidate/reference semantic fingerprints; an anchor hit binds
  the canonical full-image digest, semantic fingerprint, and exact lane label so loaders never
  certify each other. Both modes bind authored region scope, expectation, runtime evidence,
  capture identity, scenario contract, release matrix, reviewer and similarity code, prompts,
  models, mode, and chunk policy. Paired artifact labels and loaders need not match when the entire
  reusable semantic identity does. Protected ancestor shards survive unrelated `master` merges only when their
  cache-producing workflow blob is byte-identical; the current codec and policy still validate
  every entry before use. Parallel drains may briefly publish sibling shards;
  a later protected successor combines and retires every authenticated shard it consumed.
- `scripts/ci/visual_anchor_certification.py` is the fail-closed certificate codec. It accepts only
  an unpaired, loader-complete, completely clean 1.20.1 report and binds its source/proof/manifest/
  report digests to exact Git identities supplied by protected workflow checks. The version
  synchronizer accepts the resulting artifact only from a successful protected drain run, for the
  exact current `master` SHA and exact current merged anchor head.
- `scripts/ci/visual_nonimpact_certification.py` is the distinct model-free continuation codec.
  The protected port merge controller may create it only after exact Build and full anchor E2E
  pass and `scripts/ci/visual_review_impact.py` classifies the complete first-parent-to-port-head
  diff as nonvisual. The consuming synchronizer authenticates the handler artifact and owner,
  independently recomputes that exact diff with current protected policy, verifies both gate runs,
  the current `master` second parent, current anchor head, and equal merged trees, then releases the
  remaining ports without minting a semantic certificate or starting optional-mod compatibility.
  `scripts/ci/visual_review_queue.py` also authenticates that protected artifact name and owner
  before suppressing a duplicate scheduled or automatic review of the exact generation; it never
  applies this shortcut to an ordinary feature-PR semantic review.
- `scripts/pages/evidence.py` creates and validates a small branch-scoped raw handoff, then
  atomically compacts a validated bundle to protected WebP derivatives. It may copy only contracted
  screenshots, structured provenance, and each capture's bounded printable passed-assertion
  message—never runtime logs or arbitrary HTML. That assertion message is validated when present
  but stays optional in `OPTIONAL_FRAME_FIELDS` until every release branch has republished its
  evidence, so an older rolling cache still validates. The compact schema
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
  the oldest eligible source except that a completed certifiable automatic 1.20.1 anchor preempts
  advisory work. The curator rejects a capsule whose authenticated generation differs from its
  protected implementation before image work, while the drain rechecks every capsule against the
  live `master` SHA immediately before model admission. An exact wake may select only its requested
  authenticated artifact and queries only that immutable capsule plus its exact report, cooldown,
  generation-block, and current-generation identities; it does not rescan the repository-wide
  queue. Transient GitHub API and installation-rate-limit responses
  receive bounded backoff before the durable wake is allowed to fail visibly. Queue state
  lives in Actions artifacts rather than pending workflow runs, so GitHub concurrency coalescing
  cannot lose a review. Exact artifact IDs define drain concurrency groups: duplicate wakes cannot
  overlap, while distinct capsules run in parallel. Scheduled/manual recovery sweeps share a
  separate lock and only redispatch the selected exact identity, so they never review a capsule
  concurrently with its direct wake. Queue selection also authenticates generation-block artifacts
  from failed/in-progress protected drains and skips only inputs carrying the exact blocked master
  generation; the marker's owner still binds it to its exact protected reviewer implementation.
- `scripts/ci/visual_review_impact.py` is the narrow fail-closed cost and domain filter. PRs to
  `master` defer model work to their post-merge anchor; its `source-pr` scope protects direct
  release-branch PRs, where that automatic second stage is absent. `replicated-port` recognizes
  protected visual/Pages/synchronization orchestration. `post-anchor-port` additionally recognizes
  prompts, reviewer code, and Claude admission policy already exercised by the exact certified
  anchor, but only after the synchronization run is authenticated as a certificate-driven
  `repository_dispatch`; manual targets remain strict. Protected automation supplies either a
  complete bounded GitHub PR inventory or an exact no-renames Git diff; current and previous rename
  paths must both be safe. Product, packaged-E2E, scenario, malformed, incomplete, and unknown
  paths remain reviewable. The matrix-derived 1.20.1 port may use a nonvisual result only through
  the separately authenticated continuation after Build and full Packaged E2E; it never becomes a
  semantic certificate.
- `scripts/ci/mod_compatibility_impact.py` independently classifies the complete server-side
  synchronization PR inventory. It binds a normalized manifest into the visual curation proof and
  permits the optional-mod wave only for product, build, runtime-harness, compatibility-policy, or
  unknown impact. Review-only workflows/prompts, publication, documentation, and policy tests skip
  that expensive wave. Renames classify both old and new paths and malformed or incomplete
  inventories fail closed.
- `scripts/ci/github_api_retry.sh` is the protected Pages-side wrapper for read-only GitHub API
  calls after checkout. It keeps response bytes isolated on stdout and retries only classified
  rate-limit, transport, and server failures with bounded run-skewed backoff; provenance and exact
  identity checks remain in each caller.
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
  validated evidence discovered from release branches. Its `gallery-data.json` publishes the
  complete per-capture validation record—contract identity and expectation, the passed assertion
  message, source and published pixel metrics, the required pixel comparisons, the packaged lane
  with its JAR digest, and both provenance runs—so the gallery never has to restate a fact the
  validated bundle does not carry.
- `_site/`, `public-evidence/`, and downloaded Actions artifacts are generated output. Do not commit
  them or edit them as source.
