# Source-set architecture

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Canonical sources

These are the primary implementation trees:

- `modules/<module>/src/main`: independently compiled modules declared by
  `architecture/modules.json`. Java libraries own content/protocol values, stable loader and
  texture-reference APIs, appearance data, bounded image processing, configuration, transfer
  validation, server appearance storage, client infrastructure/preferences, texture state, and cape
  imports. Minecraft modules own adapters, networking, assets, services, previews, optional-mod
  integrations, and individual menus; they compile separately for each matrix target. Regression
  tests live in the owning module's `src/test`.
- `common/src/main`: Minecraft mixin/event bridges and client/server lifecycle composition.
  Feature implementations compile outside this assembly.
- `fabric/src/main`: canonical Fabric entry points and loader integration.
- `forge/src/main` and `neoforge/src/main`: the respective loader integrations.
- `common/src/e2e` plus each loader's `src/e2e`: the separate packaged-runtime test mod.
- `modules/<module>/src/test`: the existing JUnit regression tests redistributed by ownership,
  including pure Java tests and tests against each Minecraft module's version node.

`architecture/modules.json` is the module dependency authority. Its typed reader,
`scripts/architecture/module_graph.py`, rejects unknown/duplicate dependencies, cycles,
overlapping ownership, and client/server environment leaks. Gradle creates Java-library
projects from this graph and verifies their declared dependencies before compilation. External
libraries use the graph's pinned coordinate catalog and explicit scopes. Minecraft framework
dependencies come from the release matrix through `gradle/minecraft-module-framework.gradle.kts`;
module builds must not add hidden project, Maven, or file dependencies. Stable Java libraries
cannot depend on Minecraft modules. `api` exposes a dependency to consumers; `implementation`
keeps it out of their compile API.
Remapped Minecraft modules expose Loom's `namedElements`; official-namespace targets use the
ordinary Java API/runtime variants. The release matrix's `no_remap` policy selects the variant,
including the common assembly's internal bundle. Never remap an official-namespace module or
resolve another Minecraft target to satisfy its dependency.
`minecraft-assembly` identifies final composition projects; feature modules cannot import an
assembly. API providers/consumers and their composition roots are declared as `bindings` in the
same graph. A `propagate` binding carries provider changes to consumer modules; a `coverage`
binding requires the specific authored interaction checkpoints without treating every consumer
export as modified. A composition project merely being rebuilt does not modify its wiring.
The common JAR bundles the internal module closure before the existing Architectury and loader
transforms, preserving one production JAR per release artifact. It must reject duplicate entries
and must never absorb the harness. The common test task also runs its extracted libraries' tests.

The version-3 scenario contract declares each step's module/binding coverage, earlier action
prerequisites, and earlier captures consumed by assertions. Coordinated multiplayer scenarios
retain all clients and actions through `execution_scope: scenario`. `e2e/selection.py` computes
the separate execution/capture closures and comparison partners. Its CLI emits local path previews.
`scripts/ci/e2e_selection.py` independently derives an admission from immutable Git objects and
the executing protected policy. Runtime and visual consumers recompute that admission from caller
supplied commits and require its outer hash on every selected report. `project_contract` supplies
their shared exact view of the authored assertions/captures; its contract hash remains unchanged,
so selection identity must be checked separately. Unknown impact forces every scenario in the
profile. Selected curation emits a distinct scope proof and cannot certify a full semantic anchor.
CI still requires complete profiles while GitHub/baseline authentication, selective certification
and retained unaffected evidence are migrated. A consumer expecting full evidence must reject a
selected report, including a selected report that happens to contain all authored steps.

`PlatformHelper` is now a stable API in `platform-api`; Architectury binds its loader methods
after the modules are assembled. Its old rendering forwards belong to `MinecraftCompat` in
`minecraft-adapter`. That facade selects four implementations by Minecraft API family: immediate
GUI/model operations, RenderType GUI, RenderPipeline GUI and GUI extraction. Equivalent patch
versions share an implementation. Native identifier/model package changes remain inside this
adapter seam; each selected target compiles exactly one implementation. Consolidating this facade
does not yet consolidate all preview, networking, mixin or feature call sites across the matrix.
`QuickSkinInfo` owns diagnostics/identity without initializing either runtime.
`QuickSkin` retains public aliases for compatibility, but internal services use the API directly.
`PlayerAppearance` retains opaque `TextureReference` values; `MinecraftTextures` performs the
native conversion and preserves the allocation-free cached lookup. Server stores accept a world
`Path` supplied by `ServerRuntime`, without importing `MinecraftServer`.
`cape-import` receives a `GifDecoder` through its workflow constructor. Its API returns a bounded
ARGB atlas and animation metadata; `MinecraftGifDecoder` in the adapter owns native decoding,
channel conversion, and native-frame disposal. Keep native image objects out of import processing.

`ClientFeatureBindings` installs the process-owned links before catalog scans or network callbacks:
CPM consumes `CpmAssetAccess`, accepted packets target `RemoteAppearanceTarget`, and feature code
uses `ClientNetworkActions` without depending on the mixed client/server networking implementation.
The runtime supplies CustomNPCs' skin listener and the current connection identity supplier.
`TextureRequestCoordinator` compares connection identity with `==`, preserving session isolation;
its clock and connection source are injectable for bounded retry regression tests.
`PreviewAnimationState` belongs to client infrastructure instead of global event registration.
Settings reconstruct their parent through `RestylableScreen`; key handling receives an open-menu
callback from bootstrap. Neither mechanism introduces a settings-to-skin-menu compile dependency.
`menu-integration` owns injected title/pause controls, preview rotation/animation state and deferred
menu rendering. Its skin-menu action is another bootstrap callback, covered by
`vanilla-menu-navigation`; it does not compile against the skin menu. `hud-preview` owns its overlay,
mouse/tick/render callbacks and drag state independently of menu widgets. Bootstrap keeps CPM's
frame-boundary callback after HUD extraction, including when the HUD is hidden.
The harness's `TitleMenuSteps` and `HudPreviewSteps` prepare their own reference appearance from
`local_skin_apply`. HUD evidence compares a dedicated disabled control with the enabled overlay;
it must not reuse the full-suite baseline and inherit unrelated image-comparison dependencies.
Source-inspection policies resolve Java classes through `scripts/architecture/source_inventory.py`
and the module registry, including explicitly selected legacy replacements.

Shared-source release tags select exactly one matrix target. `release_identity.py --event-target`
resolves canonical tag pushes or requires an explicit manual target on the source branch. The
release workflow carries that target through both builds, staged verification, runtime rows and
publication. Complete build bundles remain non-publishable; target views retain the authoritative
full matrix hash. See `docs/architecture/RELEASING-FROM-SHARED-SOURCE.md`.

Module ownership is transitional while the rework proceeds: the remaining `common` tree is one
mixed runtime module, and loader/build/policy paths outside this graph have unknown ownership.
The graph alone does not authorize selective E2E. Until scenario prerequisites, module coverage,
and authenticated selection/evidence are implemented together, existing full-suite policies remain
in effect. Follow `docs/architecture/MODULAR-REWORK.md` for the recoverable migration state.

Stonecutter preprocesses common, loader, and Minecraft-module canonical `src/main` trees into
detached generated sources. Stable Java-library sources compile directly without preprocessing. Never edit
generated or staged output under `common/versions`, `fabric/versions`, `forge/versions`, any
`build/` directory, `.gradle/`, `.architectury-transformer/`, `e2e-out/`, or `build/release/`. Fix
the tracked canonical source or active overlay instead.
`gradle/minecraft-module-sources.gradle.kts` applies the matrix's common API-family routing to
Minecraft modules that own a `src/legacy*` tree. Same-path Java files replace their canonical source;
newer-only files carry whole-file Stonecutter guards. Mixin/resource overlays remain owned by the
common assembly until their resource ownership and loader contracts are migrated.
The shared NeoForge `legacy26_1` overlay contains the Architectury BreakEvent bridge and Screen
access transformer for exactly the matrix-routed 26.1 and 26.1.1 targets. Its `test/java` directory
runs only on that overlay and verifies the pinned upstream class shape and Screen hook calls.
The 26.1.2 and 26.2 artifacts must contain neither the bridge configuration nor its classes.

`gradle/e2e-harness-conventions.gradle.kts` owns the exact E2E source roots, classpaths, generated
contract source, and harness archive tasks for every active loader node. Loader build scripts may
only bind that protected convention and are authenticated byte-for-byte by
`e2e/loader-bootstrap-contract.json`, together with the exact loader entrypoint and manifest tree.
Its schema 3 pins one build implementation per loader; the release matrix selects the active
loaders and versions. Historical schema-2 snapshots retain their branch-specific seals.
Any deliberate edit below `<loader>/src/e2e` or to an active loader build script must
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

An overlay file therefore replaces the canonical file at the same relative path. Read active
routes from the release matrix; every Minecraft feature module may own a subset of its common
routes. Pure Java modules cannot own version overlays. A common assembly overlay may contain only
resources after its Java implementations have moved into their feature modules.

`validate_source_roots` reads the same typed module registry used by Gradle. It rejects undeclared
overlays, retired `src/v*` trees, linked sources and classes with multiple owners in one assembled
JAR. Loader source trees are mutually exclusive artifacts. Schema 3 permits several API-family
replacements within one owner, since each target selects only its declared family. Newer-only
canonical classes use whole-file Stonecutter guards, without per-version exclusion maps in Gradle.

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

The shared-source schema-3 matrix retires automatic version ports. `release_sources.py` validates
the complete matrix before resolving `master` as the only source branch and an empty port list.
The sync workflow exits before Git/GitHub work; delayed port results must pass a protected layout
job before candidate inspection or repair. Existing historical refs remain untouched and do not
declare active support. README status uses `status_table.py --matrix` directly. The following
controllers remain for historical schema-2 evidence and explicit recovery, not shared-source work.

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
  per clean lane, and reschedules only unfinished lanes after a provider pause. A secretless
  protected batcher first validates and merges those unfinished capsules, deduplicating exact image
  bytes and exposing cross-lane semantic equivalence to one globally packed runner. Protected
  admission fields directly bound its parallel calls and space their starts; a later secretless
  matrix splits the complete normalized result and publishes each lane independently. Sanitized
  telemetry records only model-process attempts, chunks, and retries. The producer suppresses a
  delayed stale wake, the direct consumer requires its source implementation to equal the protected
  current `master`, and both protected batch boundaries recheck live `master` before capsule or
  model admission.
  `scripts/ci/mod_compatibility_review_batch.py` owns the fail-closed merge and split codecs: it
  preserves each authenticated proof and manifest byte-for-byte, copies content-addressed images
  only once, binds every label to one lane, and reconstructs complete lane reports from a clean
  aggregate without exposing images or credentials to the publication matrix.
- `scripts/pages/collect_compatibility.py` is the protected post-review publisher. It authenticates
  the exact compatibility source plan, every source capsule, every complete normalized lane report,
  and the source completion marker before `scripts/pages/compatibility_evidence.py` projects the
  complete clean wave into a strict public bundle. That projection retains the two local
  `mod-compatibility` checkpoints and, for integrations that opt in through the lock, the two CPM
  `mod-compatibility-cpm-first-person` hand checkpoints, the two live
  `mod-compatibility-remote` observer checkpoints, plus the one sequential
  `mod-compatibility-late-join` checkpoint as paired clean/modded 1280x720 WebPs, source and
  derivative metrics, deterministic assertions, clean booleans, and provenance; ordinary-suite
  captures stay in the authenticated runtime artifact and never enter the model capsule. Raw
  provider text stays in short-lived private artifacts. Manual publication recovery consumes the
  same already-complete reports and never calls a model. Public schema v5 binds
  `reviewed_frame_count` to the mod-selective two-, five-, or seven-checkpoint product; the
  validator keeps schemas v1 through v4 readable for older complete-scenario, local-only,
  four-checkpoint, and five-checkpoint rolling caches.
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
  1280x720 model-only copies without altering the authenticated 1920x1080 evidence, and globally
  packs only concerns or confidence below high into concurrent bounded Opus verification after
  Haiku settles. A clean
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
  compact fallback. A branch-only cache name is migration fallback only. Under
  `--allow-continuation` it may additionally nominate the newest earlier head on the branch's
  bounded commit page that still owns an authenticated bundle; it never decides that the range is
  safe. Only the collector publishes such a nomination, and only after it independently proves strict
  ancestry from the comparison API and reclassifies that exact bounded file inventory through
  `scripts/ci/visual_review_impact.py`. It must never fetch the release branch: this job is
  privileged on the default branch, so untrusted history cannot enter a workspace that can write
  the Actions cache. `scripts/pages/evidence.py carry-forward` then records the
  reached head in the optional `provenance.coverage_sha` while the packaged provenance keeps naming
  the run and commit that produced the pixels.
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
