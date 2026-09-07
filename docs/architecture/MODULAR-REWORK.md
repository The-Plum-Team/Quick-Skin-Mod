# Modular, multi-version rework

Status: in progress. This is the recoverable implementation plan for the maintainer's
2026-09-05 request to reorganize both Quick Skin and its Minecraft compatibility API into
separately compiled modules, consolidate version development, and generate/review only
E2E captures affected by a change.

## Workspace and baseline

- Implementation branch: `refactor/modular-multiversion-architecture`.
- Worktree: `/Users/neb/Documents/Quick-Skin-Mod-modular-rework`.
- Starting integration commit: `266d15616` (resolve through Git for the complete identity).
- Durable local evidence: `/Users/neb/Documents/Quick-Skin-Mod-modular-rework-evidence`.
  `owner.json` binds this workspace; `baseline/` preserves the original artifacts across
  Gradle cleans; `version-inputs.json` pins the discovered release heads and their matrices.
- The user's original checkout remains on `master`; its unrelated change to
  `scripts/release/tests/test_dependency_security.py` belongs to the user.
- Starting canonical Java inventory: 192 files, 46,717 lines, 3,554 Stonecutter markers.
- The starting matrix builds Fabric and Forge 1.20.1. Other supported targets must be
  discovered from release branches and imported from their exact matrices; this document
  is not another support inventory.
- The starting scenario contract has ten scenarios. The `full` scenario alone has 69
  ordered client steps. Existing helpers already separate cape-editor, cape-menu,
  catalog, settings, and skin-fidelity actions, but execution/evidence still assumes
  the complete scenario graph.

## Intended architecture

Use Gradle Java-library subprojects as the equivalent of Unity assembly definitions:
each production module owns source/resources, declares direct API/implementation
dependencies, compiles separately, and has an explicit runtime role. Package boundaries
alone are insufficient. Avoid Java Platform Module System descriptors in the mod loader's
classpath; the enforced boundary is the Gradle compile classpath and architecture checks.

Keep the compatibility API in this repository during migration so changes to interfaces,
adapters, feature consumers, and tests are atomic. Stable interfaces expose application
operations and owned value types. Version-sensitive Minecraft types stay inside selected
adapters and loader/mixin bridges. Split client and server responsibilities explicitly.
Choose adapters at build time from the authoritative release matrix. Package the selected
modules into one production JAR per Minecraft/loader target; keep harness JARs separate.

Extract dependency leaves first, then introduce interfaces to break the measured cycles.
Expected ownership areas include bounded I/O/content identity, appearance/protocol values,
configuration, textures, catalogs, skin selection, capes/animation, cape editing, shared
GUI/preview rendering, synchronization, optional integrations, and lifecycle composition.
The measured dependency graph determines the final boundaries; these names are a design
sketch, not a second module registry.

The module dependency graph must be authoritative for both Gradle and change-impact
selection. Scenario, step, capture, prerequisite, and module-coverage declarations belong
to `e2e/scenario-contract.json`. Build consumers must not maintain a separate feature,
scenario, or Minecraft-version list.

## Change-impact and evidence contract

1. Authenticate the exact base/head and inspect the complete diff, including both sides
   of moves/deletions. Resolve source, resources, adapters, mixins, and runtime wiring to
   their owning modules. Compare the old and new graphs when ownership changes.
2. Compute affected consumers transitively. Explicit runtime wiring/resource relationships
   augment compile dependencies; reflection is never treated as proof of independence.
   Provider bindings must propagate adapter changes to consumers of the implemented API even
   when those consumers never import the concrete adapter. Represent those impact relationships
   separately from the compilation DAG; do not introduce reverse compile dependencies. Pure
   composition modules must not make every unrelated feature depend on every other feature.
3. Select deterministic assertions and visual checkpoints from the scenario contract.
   Close over setup/state prerequisites, multiplayer handshakes, screenshot-comparison
   partners, visual probes, and cleanup. Executing a prerequisite does not automatically
   require a screenshot or an AI call for that prerequisite.
4. Give the harness an authenticated selection manifest before execution. Unselected
   captures are not generated. Reports, validators, curation, and AI review authenticate
   exactly the selected graph, its dependency/contract hashes, and the packaged JAR.
5. Preserve evidence for unaffected features only after checking their complete dependency
   fingerprints and ancestry. Keep original tested provenance and explicit coverage
   provenance distinct; a partial run must never masquerade as a full run.
6. Unknown ownership, changed selection policy/graph, invalid/incomplete diffs, missing
   baseline, or unproven coupling select full coverage. Keep full integrated validation
   for migration acceptance and an explicit full-run mode for releases/recovery.

Example: an isolated cape-editor change selects editor assertions/captures plus required
apply/render integration; a shared texture/render API change reaches every consuming feature
and the applicable adapters/integrations. Feature directories do not justify skipping
dependent behavior.

## Implementation stages and exit criteria

- [x] **1. Inventory and recoverability.** Record exact branch inputs, source/resource
  ownership, dependency cycles, existing scenario prerequisites, and baseline commands.
  Add deterministic inventory/graph validation with mutation tests for unsafe omissions.
- [x] **2. Compiled module foundation.** Introduce one authoritative module graph and
  shared Gradle conventions. Extract independently compilable leaves and their tests;
  verify artifact class/resource parity and production/harness separation.
- [x] **3. Feature and compatibility boundaries.** Migrate remaining production code to
  feature modules, isolate Minecraft adapters and loader/mixin bridges, remove dependency
  cycles, and wire services at the client/server composition roots. Compile and test
  every affected target throughout migration.
- [ ] **4. Selectable E2E graph.** Extend the versioned scenario contract and generated Java
  model with coverage and prerequisites, split stateful monolithic execution into reusable
  feature flows, and implement selection before screenshot generation. Verify selective
  and full modes in packaged Minecraft.
- [ ] **5. Protected selective review and evidence.** Carry selection identity through CI,
  exact result validation, anchor certification, optional-mod review, caches, and Pages.
  Prove stale/forged/incomplete selections cannot skip coverage. Show that independent
  feature changes actually produce fewer captures and AI inputs.
- [x] **6. Multi-version source consolidation.** Reconcile the latest exact release trees,
  import matrix-owned adapter/build inputs, and build all supported artifacts from one
  source revision. Preserve per-target publication/hotfix/rollback identity; replace
  branch-discovery consumers with the single release matrix. Supersede ADR 0001 with
  measured evidence and migrate release/governance/status/Pages contracts together.
- [ ] **7. Acceptance and handoff.** Run full integrated deterministic and packaged visual
  validation across the supported matrix, verify reproducible packaging and release/Pages
  dry runs, document measured selective examples, update contributor/agent guidance, and
  inspect the complete reviewable diff. Production publication and remote branch removal
  are separate external actions, not prerequisites for a locally reviewable implementation.

## Verification and progress log

Current selective-execution milestone: the graph now describes API provider/consumer bindings
and the final Minecraft assembly. Contract schema 3 declares module/binding checkpoints, action
prerequisites, image prerequisites and atomic multiplayer orchestration. The Python selector
recomputes the entire plan, and the Java harness validates the complete executable contract before
filtering actions/captures. Selected assertions and required setup assertions remain mandatory;
comparison partners and assertions that read previous images retain those images.

The path-preview example for `CapeAdjustScreen.java` selects `cape-editor`, `cape-menu` and
`skin-menu`, plus their actual navigation callbacks. With contract
`34af3e6772e956a9ac8f2797e7de017e2412e46fa67c7ca47fd151d10002d7dd`, this means 35
target assertions, 71 executed assertions including conservative legacy prerequisites, and 45
review captures versus 88 for the complete PR profile. Private assertion probe images are
additional runtime diagnostics, not additional review checkpoints. The new navigation scenario
exercises the registered key and reconstructs both parent menus after changing button style;
its setup is independent for each step. The older `full` helper still needs a more granular
state/setup review before safely pruning its action-prefix prerequisites.

Local packaged selection passed on Fabric (157.9s + 29.6s) and Forge (168.7s + 38.8s),
with 68 assertions/42 captures in the selected `full` role and three navigation assertions/captures.
Both production JARs retain their client-features milestone hashes. The final Fabric harness
includes cursor positioning to avoid incidental hover tooltips; the Forge selection above used
the preceding harness, with final navigation verification recorded separately in the checkpoint.
Final Forge navigation passed in 43.3s. With CPM installed, activation and navigation passed in
33.9s and 29.4s; the protected model fixture remains outside tracked source and exported evidence.
`testStableLane` passed with 264 JUnit tests and 35 architecture/selection tests, including compiled
Java rejection canaries. The 462 release-policy and 289 CI tests pass with the new complete
scenario inventory and updated gallery fixtures. Existing full-profile visual consumers explicitly
reject selection-labelled results, even if their listed steps happen to be complete.

The Git-object admission and selective curation primitives now exist. The admission binds exact
base/head/policy commits and trees, the complete raw diff and all executing selection-policy bytes.
Its hash, including that provenance, travels through launcher properties and reports. Runtime and
curation callers independently recompute it; the nested local path-plan hash cannot substitute for
it. A shared contract projection preserves required actions, images and comparisons. Disabled
admissions require the entire profile, including when a caller supplies a shorter scenario list.
Selected row proofs use a distinct schema and selected curation cannot certify a complete semantic
anchor. These capabilities do not yet authorize reduced workflow coverage: GitHub event/run
authentication, verified baseline evidence, protected job/capsule/certificate consumers,
compatibility clean-reference obligations and reuse of unaffected evidence remain pending.

Local curation of the preserved Fabric/Forge editor runs produced 90 review entries, backed by
66 distinct normalized PNGs, against 176 entries for their complete PR profiles. The model-input
validator accepts the exact curated directory. This is a local preview with the original local
selection hash, not Git-admitted CI evidence or an AI verdict. Missing coverage and unknown
source/resource/assembly changes still fall back to full. Shell/resource extraction and the
remaining version/loader imports remain part of the active goal.

Run Gradle serially; Architectury's transform state is JVM-global. Start with targeted
checks and reuse successful evidence until another change invalidates it. Preserve wire
identity, bounded queues/I/O, session lifetime, optional-mod degradation, and client/server
isolation throughout the refactor.

No stage is complete merely because its design is documented. Update this checklist and
record exact commands/results below as implementation proceeds. Keep the active goal open
until implementation and required validation are complete.

The maintainer has approved deferring routine per-version visual E2E execution to GitHub after
the version imports are complete. Keep compilation, unit/policy tests and artifact verification
local during migration; launch Minecraft locally only to investigate a concrete runtime concern.
Prepare the complete matrix-driven visual gate and clearly distinguish prepared CI from an
executed, passing GitHub run. Preserve the already completed four-lane pilot evidence.

- 2026-09-05: Created the active goal and isolated named worktree; read the worktree's
  instruction set and active release matrix. No production or CI behavior changed yet.
- Baseline: `./gradlew --no-daemon --no-parallel :common:1.20.1:test buildAllLanes
  buildAllE2EHarnesses` passed in 27 seconds: 254 JUnit tests, no failures/skips.
  `verify_release.py` staged and independently verified both production JARs and both harnesses
  under `build/rework/baseline`. The baseline source is
  `266d156165d9514499ef7f4cfc905532728a7849`.
- Module foundation: added the typed shared module DAG and Gradle library/bundle conventions.
  `content-core` now separately compiles six unchanged production classes and its four existing
  test classes. Its build and all 254 redistributed JUnit tests passed; all four output JAR
  SHA-256 values remain identical to the baseline. Eleven new graph-policy mutation tests pass.
- Baseline repository checks: all 462 release-policy tests passed. The CI suite exposed six
  pre-existing local-isolation failures: inherited `BASH_ENV` installed a real `gh` function
  ahead of a PATH-based fake CLI. A label-create attempt was rejected because the label already
  existed. The test helper now supplies an isolated environment with system utilities, its
  owned fake CLI/config directory, no inherited hooks/credentials, and strict shell failure
  propagation. All eight retry-helper tests, including an inherited-hook regression, pass.
- `protocol-core` now separately compiles nine unchanged production classes and its three
  existing test classes. `buildAllLanes buildAllE2EHarnesses` passed in 13 seconds; all four
  JARs still have the original baseline hashes. Java tests are now 221 in common, 14 in
  content-core, and 19 in protocol-core: the same total of 254, all passing. The common
  source JAR contains every extracted source exactly once with unchanged bytes.
- After the test-isolation fix, all 288 CI-policy tests pass. Generated E2E/workflow profiles,
  the nine repository-guidance checks, Python compilation, and `git diff --check` pass.
- A separate Gradle init-script probe deliberately injected an undeclared
  `content-core -> protocol-core` dependency. `verifyModuleDependencies` rejected it with
  the expected graph-mismatch error; the probe changed no module sources or build scripts.
- Discovered and pinned 16 release branches and their 32 artifact lanes in the local evidence
  snapshot. It is historical migration input, not a runtime support-discovery replacement.
- Compiled-bytecode inventory (`jdeps -verbose:class -filter:none`) includes all same-package
  references: 168 effective 1.20.1 top-level classes, 687 dependency edges, and strongly
  connected groups of 67, 4, 2, and 2 classes. See `compiled-dependencies-1.20.1.json` and
  its raw `.txt` beside the preserved baseline. Class cycles inside a single coherent module
  are permitted; cycles between independently compiled modules must be broken. Reflection,
  resources, and newer-version APIs still require explicit runtime coverage.
- Scenario prerequisite baseline is the complete ordered role graph in the existing contract,
  including all prior stateful steps and its authored multiplayer orchestration. Selective
  execution remains disabled until stage 4 replaces implicit state dependencies explicitly.
- Extracted `platform-api`, `appearance-data`, `image-core`, and `configuration` with their
  existing tests. The graph now declares pinned external libraries and dependency scopes as well
  as internal modules. `QuickSkinInfo` removes service-to-bootstrap logging dependencies;
  `PlatformHelper` no longer exposes Minecraft rendering types. The largest measured compiled
  cycle decreased from 67 to 32 classes; a separate seven-class asset/CPM cycle remains.
- Added a stable `TextureReference` interface, retaining native identifier caching in the Minecraft
  adapter. Appearance data now compiles without Minecraft. The animation-duration null/empty
  guard previously restricted to 1.21+ is shared across versions; valid animation timing and JSON
  round trips are covered by an added regression test.
- Extracted `transfer-core` and all server data/storage/executor classes into `server-appearance`.
  The lifecycle root supplies the world path; stores no longer import Minecraft. A new persistence
  test switches between two world directories and confirms no texture or appearance state leaks,
  then reopens the original world and verifies its saved data. All 258 redistributed JUnit tests
  and the active production/harness builds pass.
- Packaged smoke on both Fabric and Forge 1.20.1 passes with exact 1920x1080 screenshots. The first
  macOS run exposed Retina logical-vs-pixel sizing (3840x1840); the harness now establishes the
  actual framebuffer size with bounded adjustments and settled render frames before building the
  scenario. Evidence images and their strict validators are unchanged. A transient Fabric installer
  Mojang metadata timeout passed on retry. These local runs are development evidence, not Linux CI
  release certification. See `build/rework/server-appearance*-runtime` and the associated logs.
- The first versioned Minecraft module is being introduced as `minecraft-adapter`. A standalone
  Stonecutter probe verified that module directories registered before tree expansion receive
  separate generated sources and compile tasks. Framework classpaths do not borrow common
  compilation output; the common assembly consumes named module JARs before loader remapping.
- The separately compiled Minecraft adapter now passes both active builds and all unit tests.
  Distinct component groups prevent Stonecutter's identically named version nodes from claiming
  the same Gradle capability. Matrix-authored framework inputs remain checked; Loom's derived
  mapped configurations are distinguished from module-authored dependencies. `compiled_dependencies.py`
  records the assembled named-JAR graph (including same-package edges) and confirms no module
  cycles. Internal class cycles still exist in the transitional common implementation.
- Packaged Fabric 1.20.1 `full` passed all 69 steps and produced 68 exact 1920x1080 captures after
  the adapter extraction. Evidence is in `build/rework/minecraft-adapter-full-runtime/current`.
- Extracted `client-foundation` (bounded I/O, internal events, appearance repository) and
  `cape-import` (batch workflow and file processing). `GifDecoder` supplies an owned ARGB atlas
  and unchanged timings; `MinecraftGifDecoder` owns native channel conversion and releases
  native frames before returning. `StbGifLoader` moved into the adapter and its result implements
  `AutoCloseable`. Two boundary tests cover decoder injection/stream closure and malformed atlas
  rejection. All 260 JUnit tests pass; active JAR builds and the combined source JAR pass with
  no duplicate source paths. All 462 release-policy tests pass after these extractions.
- Packaged `full` now passes all 69 steps on both Fabric (164.3 seconds) and Forge
  (174.1 seconds) with the extracted cape import workflow and native GIF adapter. Each run
  produced 68 exact 1920x1080 captures; animated frames were also visually inspected.
  The 288 CI-policy tests and nine repository-guidance tests pass. Two independent Gradle
  mutation probes reject an undeclared external library and an adapter-to-common dependency.
  The latter prevents accidentally recreating a module cycle through the composition root.
  These are local development runs; other Minecraft versions and Linux release certification
  remain pending. The recoverable patch, module graph, JARs, logs, and runtime reports are
  preserved outside Gradle build directories in the sibling `Quick-Skin-Mod-modular-rework-evidence`.

- The client feature extraction now has 35 separately compiled modules plus the common assembly.
  It includes asset catalog, client texture/animation state, client transfer policy, preferences,
  appearance services, native networking, rendering, player previews, shared UI, input, skin
  import/upload, cape import/editor/menu, skin menu, settings, and separate optional integrations.
  Common retains 23 canonical Java files for mixins, event registration, and lifecycle composition.
  The named JAR has 182 effective classes and 728 internal edges. There are no inter-module cycles;
  the two largest seven-class cycles are internal to the skin-menu and networking implementations.
- Client bootstrap now supplies the CPM catalog/cache access, accepted-appearance effects,
  CustomNPCs listener, connection identity source, and feature-facing network actions. The latter
  prevents client features from depending on the mixed client/server transport implementation.
  Shared preview animation state no longer points at global event registration. Settings ask
  their parent to recreate itself; key handling receives an open-menu callback. Preferences
  persist a supplied selection instead of querying the catalog from storage.
- Four new JUnit regressions cover equal-but-distinct server connections, texture-type-specific
  retry/fulfilment, failed submission retry, and persisted preference migration after the boundary
  change. All 264 JUnit tests pass. `buildAllLanes buildAllE2EHarnesses :common:1.20.1:sourcesJar`
  passes; the combined source JAR has 350 entries and no duplicate paths.
- Packaged `full` passes on Fabric (169.4 seconds) and Forge (176.1 seconds), with 69 steps and
  68 full-size captures on each. CPM compatibility and first-person scenarios pass on current
  Fabric artifacts (31.9 and 39.3 seconds). The first CPM attempt lacked its required protected
  fixture; the exact documented model was found in Downloads and verified against its reviewed
  SHA-256 before retrying. The fixture was not copied into the repository or preserved artifacts.
  Current runtime evidence is under `build/rework/client-features*-runtime`.
- The 462 release-policy, 289 CI-policy, 15 graph/source-inventory, and nine guidance tests pass.
  Source-policy checks resolve moved classes through the module registry, reject missing or
  ambiguous ownership, and inspect all authored GUI trees. Historical version-port tests preserve
  their exact previously audited policy in a fixture: the old merge exception intentionally
  rejects the new module-aware policy, whose source resolver is absent from old target branches.
  A regression confirms that rejection; the protected production merge policy was not widened.
- Current packaged propagation-live and session scenarios pass on Fabric (50.6 and 33.4 seconds)
  and Forge (59.8 and 42.7 seconds). They exercise remote appearance application through the
  injected API and connection teardown, beyond the single-player full scenario: Alice/Bob pass
  nine/eight live-propagation steps and the session scenario passes five steps on each loader.

## Next implementation checkpoint

Continue from the shared-matrix pilot in this worktree. Preserve the compiled modules, provider
bindings, selectable scenario graph, and local/Git admission separation. Finish ownership of
event-driven shell behavior, resources, and loader/mixin bridges; narrow remaining native API drift
inside Minecraft modules. Existing source scanners, AI repair paths, release workflows, protected
evidence consumers and version-port automation still need the complete modular contract migration;
passing their current tests does not certify those stages.

The active matrix now imports all sixteen targets through 26.2 from the pinned migration inputs.
Every target has compiled its full mod and packaged harnesses in isolation. Integrated validation
of the final tree and the publication/governance/Pages migration remain separate obligations;
compilation is not runtime evidence. Keep target publication independent while migrating
branch-discovery consumers to the central matrix.
The complete build now uses `scripts/release/build_matrix.py`: a separate sequential Gradle
process per target bounds both project configuration and remapping memory. The target-specific
tasks do not configure or resolve unrelated Minecraft module/version nodes.

- Git selection admission now passes twelve bounded Git-fixture tests, including a runtime
  integration test that binds the checkout, launch properties and report to the outer admission
  hash. Temporary fixture commits live only in disposable bare test repositories. The implementation
  worktree remains uncommitted. Selected evidence/curation has 42 passing tests; the complete release
  suite has 465 passing tests, and the architecture/selector suite has 37. The CI suite exercised
  301 tests: its new integration fixture initially used an unlocked player name; after correction,
  all twelve admission tests pass, including the added full-profile fallback check. The other 300
  CI tests passed in the aggregate run. No production or harness bytecode changed in this milestone.

- The image/GUI compatibility facade now selects four API-family implementations. The immediate
  implementation is shared by 1.20.1 and 1.21.1; RenderType, RenderPipeline and GUI extraction own
  their respective API changes. The 1.21.2 model generic transition and 26.1 GUI-extraction boundary
  were reconciled from the pinned release trees and actual Minecraft APIs. This removes the missing
  per-version implementation references in the facade without importing whole version trees.
  An isolated Gradle/Stonecutter probe compiled the facade and exactly one implementation against
  all sixteen mapped Minecraft API JARs, using the matrix-declared Java 17, 21 or 25. Its inputs,
  Minecraft JAR hashes, generated-source hashes and class inventory are recorded under
  `build/rework/adapter-family-api-probe`; this proves the facade's API compilation, not compilation
  or runtime compatibility of the entire mod on those versions. The active two-loader production
  and harness build passes, with all 264 JUnit and 37 architecture/selection tests. A final rebuild
  after the boundary correction retains all four staged JAR hashes. Thirty affected source-policy
  tests also pass. Packaged runtime results are recorded with the adapter-family checkpoint.
- The shared immediate adapter passes all 69 `full` assertions and 62 contract captures on
  Forge (179.7s) and Fabric (175.8s). Fabric's first attempt stopped on a dependency-download
  network error; its retry used the existing Gradle artifact after verification against the
  repository's dependency hash. The exact runtime inputs have production SHA-256 values
  `52408ebfd7567ea60902343d0703a649c0b385ca6b61c47526b67369a8a13105` (Fabric) and
  `3c4f0d784d149466f5e89101d438225d9238393477435b6dd8c33d945d6b17a4` (Forge).
  The Forge editor frame was inspected visually. These remain macOS development runs.

- The first shared-source pilot uses release-matrix schema 3 with four artifact lanes: the
  previous Fabric/Forge 1.20.1 and imported Fabric/NeoForge 1.21.1. The latter inputs come from
  pinned commit `32e91aeb118a81b62922610804eb1a67b92f85ad`, including exact dependency locks and
  reviewed dependency hashes. Loader bootstraps now seal one build implementation per loader,
  independent of the number of matrix targets. Schema-2 snapshots keep their historical reader.
  Source ownership validation permits a shared API-family root and resources-only overlays while
  rejecting duplicate assembled classes, undeclared roots, symlinks and inactive version trees.
- The immediate preview renderer is shared by both pilot versions. Typed payload networking and
  Replay integration move into their existing owning modules. The imported Replay watcher now
  advances once per client tick, expires startup after 600 ticks, and unsubscribes on teardown;
  it no longer recursively queues main-thread tasks. Optional Replay runtime validation remains
  outstanding. Loader implementations expose only the stable platform API. Whole-file version
  guards replace the pilot's duplicate build-script exclusion tables.
- The packaged world fixture now materializes the version's actual function-directory and
  game-rule identifiers from the runtime row. The canonical template stays shared. A regression
  checks both sides of the 1.21 directory and 1.21.11 game-rule transitions, including idempotence.
- All four production JARs and four separate harnesses build and pass staged metadata/class/SBOM
  verification. The aggregate identity is `build-v3.0.0`, which publication explicitly rejects.
  `--target 1.21.1` stages and validates only that target's two production JARs, harnesses and
  CycloneDX SBOM under `mc1.21.1-v3.0.0`; its provenance retains the complete canonical matrix hash.
  E2E planning and reproducibility comparison consume that explicit scope. Tests reject partial
  bundles presented as complete, mismatched targets, duplicate rebuild nodes, and invalid rows
  outside the requested target. This is local packaging support, not completed release automation.
- The final pilot runs pass `full` (69 assertions, 62 contract captures) and `feature-navigation`
  (three assertions/captures) on all four loaders/targets. Forge 1.20.1 took 175.7s/39.7s,
  Fabric 1.21.1 took 167.3s/29.4s, and NeoForge 1.21.1 took 175.1s/39.6s. Fabric 1.20.1
  passed on retry in 166.2s/29.4s; preserve its first connection-timeout failure and separate
  installer network-timeout failure as diagnostics. Every successful lane produced 71 full-size
  PNGs including six private assertion probes. The NeoForge cape editor frame was visually
  inspected. These macOS runs exercise two scenarios, not the complete release profile or Linux
  certification. All 481 release-policy and 301 CI-policy tests pass for this pilot.
- An independent `--rerun-tasks` rebuild executed all 387 tasks in 33 seconds and reproduced
  all eight production/harness JAR SHA-256 values exactly. Target-scoped comparison also passes.
  The final guidance check exposed an old single-version assumption in the workflow renderer;
  schema 3 now takes its test task from `unit_test_version`, independently of artifact ordering.
- `-PquickskinTarget=<minecraft>` now validates the complete matrix before registering only that
  target's Minecraft projects. Explicit `buildTargetLanes`, `buildTargetE2EHarnesses` and
  `testTargetLane` tasks preserve the meaning of full-build tasks: a scoped invocation of
  `buildAllLanes` fails. Five actual Gradle probes cover complete/scoped project registration,
  unknown targets, missing targets and partial-as-complete rejection. In the four-lane pilot,
  registration decreased from 91 to 65 projects, with only 1.21.1 version nodes in the scoped
  result. The scope validation JSON and logs live under `build/rework/build-scope-*`.
- Imported the four 1.21.2 through 1.21.5 targets and all eight loader lanes from their pinned
  input matrices, including dependency locks and 357 additional verified dependency components.
  Shared immediate previews and payload transport replace identical per-version copies. The
  render-state cape/hand bridge is shared across this family; the chest-equipment field changes
  at 1.21.4. NeoForge's older player-info bridges and mixin resources are shared, with resources-only
  roots supported. Native GUI, texture-registration, player-model and HttpTexture boundaries were
  merged through the existing module ownership, preserving the new API seams.
- All twelve production JARs and twelve harnesses build and pass staged artifact/SBOM verification.
  The new version builds passed in 43s, 58s, 35s and 36s respectively; regressions of 1.20.1 and
  1.21.1 each passed in 17s. The 482-test release-policy run found only five subtest errors in one
  static inspector that did not understand bounded API-family conditions. Its five tests now pass,
  including boundary selection and rejection of unsupported syntax. Ten protected bootstrap tests
  pass. New visual E2E runs are deferred to GitHub under the maintainer's instruction.
- The pinned 1.21.5 Replay improvement now crosses a typed `NetworkAppearanceAppliedEvent` in the
  client foundation. Client composition connects that event to Replay; appearance services do not
  import the optional integration. The watcher remembers the authoritative subject before entity
  spawn, re-applies its complete look once present, and waits for its actual texture. It runs once
  per tick, expires after a bounded appearance/startup budget, preserves cached optional reflection,
  and suppresses its own re-application when counting recorded payloads. Session reset clears the
  subject and counters; the client lifecycle owns the event subscription. All six target builds
  and all 483 release-policy tests pass after this change. The twelve production JARs and twelve
  harnesses are staged and independently verified again. Replay playback evidence remains a
  GitHub acceptance obligation.
- Imported 1.21.6, 1.21.7 and 1.21.8 from their pinned matrices, dependency locks and native
  source trees. They share the pipeline-family preview backend and the existing payload transport;
  no per-version source overlay was added. Dependency verification gains 173 portable components.
  Three imported Loom-transformed loader records were omitted because those local outputs already
  belong to the exact locally generated namespace policy; their upstream inputs remain verified.
- Reconciled older API boundaries against the previously built sources: player/cape model generics,
  render-state lookup and preview identity, PostChain and background overrides, blend calls,
  scrollbar methods, resource reload signatures and CPM's degraded embedded-PNG capability.
  Texture construction now has one client-only `MinecraftTextureUploads` adapter, shared by the
  catalog, network and animation caches, star background and cape editor. All eight call sites
  preserve their image ownership and exact texture identities. Settings modal pose push/pop now
  use the same immediate-rendering boundary.
- Pinned vanilla and NeoForge bytecode confirms different skin-lookup multiplicities: the pipeline
  family's synchronous method has two returns in vanilla and one in NeoForge. The mixin handlers
  preserve that loader distinction. NeoForge's event-subscriber bus attribute is also bounded at
  1.21.6. The late session-user bootstrap and per-candidate vanilla Elytra fallback from the 1.21.8
  input are retained; Replay keeps the shared subject event and bounded watcher already integrated.
- All nine targets build together with `buildAllLanes buildAllE2EHarnesses --no-parallel`: the final
  build succeeds in 114 seconds across 1,376 tasks (314 executed). All eighteen production JARs and
  eighteen harnesses are staged and independently verified with their SBOM. The stable unit gate
  retains 264 passing JUnit tests; 483 release-policy tests and 24 affected GUI/CPM policy tests
  pass. Earlier failed port/build attempts and bytecode audits remain in the checkpoint diagnostics.
  No image E2E was launched for this family; the full GitHub visual gate remains outstanding.
- Imported 1.21.9, 1.21.10 and 1.21.11 from pinned source, runtime and dependency inputs, adding
  232 portable dependency components. All three share one render-state preview backend and no new
  source overlays. Native skin/cape lookups use the actual ClientAsset boundary at 1.21.9; the
  texture-file compatibility bridge remains present in the feature API and returns no file where
  that legacy bridge is unavailable. NeoForge's native collector already renders translucent
  hands; only the older native helper implementations require the redundant redirect.
- The twelve-target single-JVM aggregate exhausted its 2 GiB heap during remapping, after the
  individual targets had compiled successfully. That failed diagnostic is retained. The full build
  entry point now validates the complete matrix, starts each target sequentially with `--no-daemon
  --no-parallel`, records every exit and expected production/harness hash, and verifies earlier
  outputs survived later targets. Any failure invalidates the aggregate result. `--clean` and
  `--rerun-tasks` reach every target; `--target` explicitly reports partial coverage. Native Gradle
  aggregate tasks reject a multi-target matrix with the coordinator command. Build, on-demand E2E
  and release workflow build steps use it; protected publication/governance migration remains
  unfinished. Nine coordinator tests cover complete/partial coverage, invalid unselected lanes,
  failures, missing outputs, matrix mutation and output replacement. The complete release-policy
  suite passes 493 tests and the CI-policy suite passes 301 tests.
- The collector checkpoint preserves all twelve successful target builds (202 seconds combined),
  24 verified production JARs and 24 verified harnesses. A real clean rebuild of 1.20.1 passes in
  24 seconds; a subsequent complete output comparison confirms every staged hash still matches.
  This is a clean-scope check, not a second rebuild of the other eleven targets. The checkpoint
  retains the failed single-JVM build and the final serial build separately.
- Imported 26.1, 26.1.1, 26.1.2 and 26.2 with their pinned matrices, locks and 271 portable
  dependency components. The GUI extraction boundary starts at 26.1, and the three 26.1 targets
  share one preview backend. Their existing NeoForge handler package/return-site boundaries and
  profile Elytra ownership are preserved. API-family module dependencies now select Java variants
  when the matrix declares `no_remap`, rather than requesting an absent Loom `namedElements`.
- One NeoForge overlay, routed only to 26.1 and 26.1.1, contains the exact upstream BreakEvent
  compatibility shim, mixin registration and Screen access transformer. The eight original
  bytecode/access tests pass on each of those targets, including rejection of malformed frames
  and unexpected handlers; no compatibility code or test is selected for 26.1.2 or 26.2.
  Module compilation also exposed unused preview-widget helpers in generic dialogs/settings;
  those helpers were removed instead of introducing feature dependency cycles. Three old blur
  override blocks are now wholly bounded before GUI extraction. All four imported targets
  compile production and harnesses independently; the complete final-tree gate follows.
- The complete sixteen-target serial gate passes in 311 seconds. All 32 production JARs and
  32 harnesses are staged and independently verified with their SBOM. A packaged-class audit
  proves that each production JAR contains exactly its one native API adapter and one preview
  backend, and that the Architectury shim/configuration/access transformer exist only in NeoForge
  26.1 and 26.1.1. The stable module unit gate has 264 passing tests; the shim adds eight passing
  tests on each affected target. The complete policy suites pass 493 release and 301 CI tests.
  Ten controller tests also pass after sealing the new build coordinator as protected CI input.
  A real invocation of the retired aggregate command, including `clean`, is rejected before
  project configuration or any task; all 64 build-output hashes remain unchanged afterward.
  No new Minecraft/image E2E was launched. Full reproducibility, remaining feature/API ownership,
  selective CI/evidence, publication/governance/Pages and the prepared GitHub visual gate remain
  outstanding.
- Extracted `menu-integration` and `hud-preview`, bringing the compiled production graph to
  37 modules plus the common assembly. Title/pause controls receive a skin-menu callback instead
  of importing the menu. HUD input/render callbacks and overlay code have their own owner;
  lifecycle composition preserves callback order and session resets. All 32 production JARs
  contain each moved class once, add only the two feature entry classes, lose no production
  classes and contain no harness code.
- Isolated title and HUD harness setup. The title probe keeps its two-frame splash control and
  now requires only the reference-skin import. HUD evidence has its own disabled/enabled pair
  on the same reference scene, avoiding the old baseline's unrelated comparison closure. The
  new vanilla-menu button step invokes the registered callback and checks parent identity,
  including Essential's icon alternative. Contract
  `800ef4a3c35873d2ccf24d9304eed7b5ecf6916cdbee6630541c1463863479c0` declares 128
  steps and 97 captures across all profiles; the complete PR profile has 90 captures. Local
  path previews select two HUD captures, five menu-integration captures, and 45 editor captures.
  These revised flows are compiled for every target; their image execution is deferred to GitHub.
- The complete matrix builds in 307 seconds and all 64 production/harness outputs stage and
  verify independently. `testStableLane` passes 264 JUnit tests and 39 architecture tests. A real
  `--rerun-tasks` rebuild of 1.21.8 passes in 57 seconds and reproduces its four staged JARs
  byte-for-byte; all 64 matrix output hashes still match afterward. This does not claim a second
  rebuild of the other fifteen targets.
- The release workflow now resolves one target from a canonical tag or an explicit manual
  dispatch and carries it through build/rebuild, staging, runtime rows and each publication
  verification. It preserves the full matrix identity and the aggregate bundle's publication
  rejection. The actual identity-step shell passes local fixtures for all sixteen manual
  targets and rejects five missing/unknown/stale/override cases; a staged 1.21.8 runtime plan
  contains only its selected lanes, and verification under 1.21.7 is rejected. No publication
  or GitHub workflow was executed. Final policy suites pass 497 release and 301 CI tests.
  Protected selective review/baseline evidence, remaining API/feature boundaries, release
  governance/status/Pages and complete final reproducibility/visual acceptance remain pending.
- Shared-source status and governance now consume the validated matrix. README status lists
  every target with its canonical tag and uses the shared source's checks; the old branch-port
  producer and result handler stop before legacy discovery or candidate checkout on schema 3.
  The release environment plan retains human approval, admits canonical target tags, and retires
  only the explicitly declared legacy branch deployment pattern. Historical branch rulesets are
  not managed or deleted. A stateful local API fixture proves plan/apply convergence, exact source
  SHA pinning and rejection of unknown or ambiguous policies. No remote governance was applied.
  All 507 release-policy and 303 CI-policy tests pass. This control-plane checkpoint reuses the
  preceding 64 verified JARs: no Java, resource, matrix or build input changed. Pages and protected
  selective review still require their shared-source migration.
- Public evidence now separates the Minecraft bundle key from its real source branch. Raw
  schema 3, compact schema 4 and optional-mod schema 6 bind the complete matrix digest and one
  tested source commit; historical schemas remain readable. Target planning covers every matrix
  version, including locked optional-mod applicability. The ordinary selector requires an exact
  current-head handoff or SHA-namespaced compact cache and rejects an unproved continuation.
  Two-target synthetic fixtures exercise 360 ordinary captures through WebP conversion and site
  rendering, alongside optional-mod publication and identity-tampering rejection. These are
  tooling tests, not new Minecraft captures. The on-demand producer now derives per-target jobs
  and downloads only their packaged artifacts. Its real inventory shell admits the source branch
  and rejects target keys, feature refs and retired release branches. Pages consumer/controller
  and selective healthy-baseline migration remain unfinished; see
  [the target-evidence contract](PUBLIC-EVIDENCE-TARGETS.md).
  The complete checkpoint passes 517 release-policy and 305 CI-policy tests; all fourteen
  workflow/action YAML files parse. No Minecraft runtime or image E2E was launched, and no
  publication, governance or repository mutation was sent to GitHub.
- Pages wake, discovery, collection, cache replacement and artifact rotation now share the
  target inventory. Each deployment pins one source commit and rechecks it before rendering and
  deployment; all ordinary target handoffs must exist with exact run ownership. Cache and bundle
  keys never enter Git branch API calls. Rotation preserves the current lossless reference and
  waits for the replacement owner's successful completion before deleting consumed artifacts.
  Local tests cover complete and incomplete handoffs, advancing heads, independent target keys
  and delayed cache replacement. The full suites pass 519 release-policy and 306 CI-policy tests;
  all fourteen workflow/action YAML files parse. This checkpoint reuses the unchanged compiled
  artifacts. Optional-mod wave admission and protected selective visual review remain pending.
- The maintainer authorized the remaining integration and publication work while unavailable,
  including necessary commits and GitHub operations. Existing user changes remain protected;
  routine Minecraft image execution stays delegated to GitHub after the migration is ready.
- Protected visual review now partitions the complete authenticated run into independent target
  capsules. Each curator recomputes its exact partition after validating the full lane graph;
  shared proof schema 6 binds the complete matrix digest, target key and current source commit.
  The drainer checks that proof against its queue target before model admission. Raw reference
  selection uses `mc1.20.1` while authenticating the real source branch; shared compact WebP
  evidence is rejected as an AI baseline. Existing per-capsule image and archive limits remain.
  Queue reports, cooldowns and newest-capsule selection now distinguish targets from the same
  source run, and completed siblings can wake independently after a partial curator failure.
  Actual curator/drainer shell fixtures reject substituted targets, incomplete inventories,
  stale matrix hashes and foreign proofs. All 520 release-policy and 318 CI-policy tests pass;
  fourteen workflow/action YAML files parse. This tooling checkpoint reuses the preceding 64
  compiled outputs. Shared post-merge scheduling, optional-mod wave admission and protected
  selective healthy-baseline coverage remain unfinished; no new Minecraft or model execution
  is claimed by the local fixtures.
- 2026-09-06: Published the preserved implementation checkpoints as draft PR #1925 from
  `7b8f29ea25c5f5a666ad6c8bea595daa4ae0fbf7`. GitHub Build and Packaged E2E are running the
  complete matrix. Local forced rebuilding initially exhausted the disk after six targets;
  `RuntimeStore.gc` removed only unleased downloadable runtime cache records and recovered
  about 3 GB. The remaining ten targets were then rebuilt sequentially with `--rerun-tasks`.
  All 64 current production/harness hashes match the preserved menu/HUD checkpoint exactly.
  `shared-complete-rebuild-recovered.json` records both phases and the final rehash;
  `shared-complete-reproducibility.log` records the independent complete comparison.
  No new local Minecraft/image run was needed.
- Shared-source post-merge scheduling now requests Packaged E2E only after a successful current
  `master` Build, suppresses existing active/successful generations and checks the live head
  again immediately before dispatch. The child authenticates and reuses the exact successful
  push build when available. Shared PR model deferral accepts large refactors before the
  separate bounded release-PR diff reader. Eight local shell/API fixture tests and all 321
  CI-policy tests pass; fourteen workflow/action YAML files parse. The running GitHub checks
  still belong to the previous checkpoint, so they do not yet validate this scheduler change.
- Added the explicit shared-source visual-review wake at the settled end of Packaged E2E.
  The notifier checks the protected checkout and live `master` SHA; its consumer reauthenticates
  the source run and rejects stale or foreign shared dispatches before curation. All ten
  shared-source retirement, scheduling, notification and Pages policy tests pass.
- Implemented full-baseline validation and Git dependency fingerprints in `feature_coverage.py`.
  The validator derives every target/loader/checkpoint obligation from the existing matrix and
  scenario contract, checks the exact successful source jobs and protected review owner, and
  requires clean complete semantic reports. It rejects native-only/selected evidence, missing
  captures, duplicate identities, altered expectations/regions and substituted source metadata.
  Module fingerprints include compile/runtime dependencies, API providers and composition source
  while preserving independence between unrelated features. Ten baseline/fingerprint tests pass;
  the aggregate CI suite passes 333 tests. These primitives do not yet publish a trusted baseline
  or reduce any required GitHub execution. Baseline publication, workflow admission and selective
  evidence reuse remain part of stage 5.
- Added the protected full-baseline publisher and explicit reviewer wake. It first authenticates
  every target's normalized report owner and the complete successful PR-profile source graph,
  then downloads only bounded JSON archives by immutable ID and digest. Missing or ambiguous
  reports, foreign owners, incomplete source jobs, extra archive files and a live source advance
  cannot publish a baseline. The certificate records its issuer and original tested provenance;
  its fixed `healthy-e2e-baseline` artifact name supports bounded discovery. Manual recovery uses
  existing reports without another model call. All 346 CI tests pass and fifteen workflow/action
  YAML files parse. The collector is prepared locally; no protected baseline has been issued yet.
  Baseline consumption, selected workflow/AI admission and reuse of unaffected public frames remain.
- GitHub Build gate run `33996911210` completed successfully for PR #1925. Its checkout log proves
  the tested PR merge commit was `14497892869bc9010421e244059a6205d3554285`, merging head
  `7b8f29ea25c5f5a666ad6c8bea595daa4ae0fbf7` into base
  `266d156165d9514499ef7f4cfc905532728a7849`. The Actions API's `head_sha` is the PR source head,
  so future selection admission must independently bind the tested merge and both parents.
  Packaged E2E run `33996911180` has built/staged all targets and is executing the complete
  runtime matrix; the first five lanes have passed at this checkpoint. These remote results
  belong to the previously published source, not the subsequent CI-only commits.
- Implemented the protected baseline consumer and independent evidence revalidation. It binds
  the immutable publisher artifact and successful issuer job, exact complete target/capture
  inventory, original source jobs, executing controller policy and Git dependency fingerprints.
  It derives cumulative impact from the last complete healthy baseline, checks every unaffected
  fingerprint, and preserves that original tested provenance alongside the selection hash.
  Master execution identity and PR merge identity are authenticated separately; the latter binds
  the live PR base/head and both Git parents. Missing, expired, tampered, foreign, partial or
  stale evidence falls back to full execution. The consumer's fixtures exercise 45 editor captures,
  cumulative editor/HUD changes and independent immutable-ID revalidation before curation.
  All 357 CI tests pass; the final source-run guard also passes its focused revalidation test.
  Required workflow selection, AI admission and public-frame reuse remain unconnected.
- GitHub's first full matrix found the same startup failure on Fabric and NeoForge 1.21.5:
  `PreviewEquipmentMixin` still targeted `Player.getItemBySlot`, whose concrete implementation
  moved to `LivingEntity` in 1.21.5. The canonical bridge now selects the declaring class at that
  API boundary and retains the existing thread/subject-scoped read policy. A new common JUnit
  test reads the active packaged mixin configuration and its selected vanilla bytecode without
  initializing Minecraft. It failed against the original 1.21.5 target and passes after the fix;
  the 1.20.1, 1.21.4 and 1.21.6 boundary checks also pass, along with six mixin-policy tests.
  The original failing GitHub logs/artifacts and local red/green reports are preserved in the
  evidence directory. The remote matrix is still testing the preceding published source.
- The 1.21.5 repair also passes the target build coordinator: common/module tests, both
  production JARs and both packaged harnesses build successfully in one serial target build.
  Other production outputs still belong to the previous complete build; a fresh final full build
  and reproducibility comparison remain required after the runtime repairs settle.
- Connected protected baseline admission to the packaged workflow. The selector checks out the
  protected base separately from inert candidate history; missing pre-migration policy, failed
  admission or failed artifact publication resolve to complete captures. Runtime jobs verify the
  admission digest, pass independent base/policy identities to the existing Python/Java selector,
  and retain the coverage provenance beside their results. All matrix lanes remain required.
  `capture_coverage=full` provides explicit manual recovery; schedules, releases, compatibility
  lanes and historical ports retain their complete profiles. Six real-shell boundary fixtures,
  all 363 CI tests and fifteen parsed workflow/action files pass. Selected AI capsules and public
  evidence reuse must be connected before this migration is integrated; no protected healthy
  baseline or reduced GitHub runtime has yet been produced.
- Connected selected image curation and protected model admission. The curator authenticates
  the complete healthy baseline, actual successful source jobs, cumulative Git selection and
  every selected target artifact before decoding images. Paired review uses the selected Fabric
  reference from that same runtime generation; the anchor target receives semantic review.
  Coverage is separate from judgment mode: schema-7 proofs preserve their partial admission and
  cannot issue a complete semantic or healthy-baseline certificate. The drainer independently
  repeats admission before model access. Full generations keep their existing path, and selected
  generations retain the preceding complete baseline. All 372 CI tests and fifteen workflow/action
  YAML files pass, including real-shell routing and bounded selected-capsule mutation fixtures.
  Public evidence composition remains the next stage; no reduced GitHub run has occurred yet.
- GitHub also exposed an incorrect `SkinManager.get` return-count bound on both loaders in
  Minecraft 1.21.9–1.21.11. The mapped vanilla methods have two returns, as does the 26.1 family;
  both loader bridges now declare that exact bound. `SkinManagerReturnCountTest` reads actual
  dependency bytecode and the compiled injection annotation without booting Minecraft. It
  reproduced the original 1.21.9 failure, passes after the repair, and passes the 1.21.8, 1.21.10,
  1.21.11 and 26.1 boundary checks. Six mixin-policy tests also pass. The complete serial build
  is running with both runtime repairs; remote runtime validation still belongs to head `7b8f29ea`.
- Both runtime repairs now pass a complete serial build, full production/harness staging and a
  second forced build. All 64 output SHA-256 values match. The evidence directory records the
  exact first manifest, both build reports and the reproducibility result for production source
  `4e5467a4dc1fbb08c2351f84eb102603c9c4cdaf`.
  GitHub run `33996911180` has finished on the earlier head: 24 runtime lanes pass and eight fail
  at the two repaired mixin boundaries; the required gate fails accordingly. The fixed source
  still requires its next complete GitHub execution.
- Connected public feature evidence to the same authenticated baseline. Selected producers retain
  their Git admission and coverage sidecar; the collector reauthenticates the complete successful
  source, baseline issuer, all public baseline identities, successful deployment and exact archive
  digest before composing images. Complete compact generations have a separately named 90-day
  artifact, and baseline issuance waits for all of them. Admission rechecks their availability,
  so expiration or replacement restores full runtime captures before another partial run starts.
  Public schemas 5/6 describe selected raw/compact evidence; schema 7 retains one complete schema-4
  baseline and one current cumulative update. Each displayed frame keeps its tested run, commit and
  JAR while recording current coverage; cross-generation comparisons and recursive compositions
  are rejected. The HUD fixture updates four loader captures and retains 176 original frames.
  All 379 aggregate CI tests, 101 Pages tests and the subsequent 12-test consumer suite (including
  the new public-expiration regression) pass. Nine real-shell routing tests and fifteen parsed
  workflow/action files also pass. No reduced GitHub generation or protected baseline has been
  issued yet; optional-mod source admission and final ownership/documentation audits remain.

### Shared complete review and optional-mod admission

Shared complete reviews now curate the target and Fabric reference directly from the same
successful runtime generation, with an independently reauthenticated schema-8 proof. Selected
schema-7 capsules remain ineligible for full baseline or optional-mod admission. Native scheduled
runs keep their complete profiles without trying to replace the manual public baseline.

Optional-mod workflows now accept per-target current-master reviews and schema-2 shared plans.
Both the runtime producer and protected AI consumer recompute the complete runnable/N/A inventory
from the release matrix and external-mod lock. The target key travels inside the exact normalized
report name so GitHub's dispatch payload remains within its ten-property limit. Concurrency is
per target and generation; one Minecraft target cannot replace another target's pending wave.
The existing complete optional profiles and compact public compatibility evidence remain intact.

### Appearance lifecycle ownership

The remaining own-skin import and saved-appearance restoration moved out of `ClientEvents` into
`skin-import` and `appearance-services`. The assembly shrank from 523 to 276 lines; the existing
import/cancellation guards, native-main-thread commit, persisted identity, Replay subject routing,
and version-specific restore behavior are preserved. The catalog harness now inspects the real
import owner instead of silently accepting a missing old field. Module graph and Java/Python
selection checks pass, as do common tests and both production/harness builds on 1.20.1 and 26.2.
The full matrix is the next acceptance step.

### Shared maintainer workflow and final acceptance

Contributor and imported agent instructions now route all new fixes to `master`, including one
Minecraft target. Matrix-derived unit commands explicitly scope Gradle with `quickskinTarget`;
historical schema-2 command rendering remains supported. The external-mod lock updater reads
schema-3 targets from the matrix without requiring remote version branches. The first complete
shared E2E used four isolated hosted runners while local Gradle remained strictly serial; the
subsequent CI latency checkpoint below revises hosted scheduling.

The final module graph still has 37 production modules plus the common assembly. Current local
selection previews use 2 HUD captures, 5 menu-integration captures, 45 editor captures, and 28
skin-import captures from the 90-capture PR profile; prerequisite actions remain included. These
are derived previews, not new Minecraft acceptance results. The final source builds every one of
the 16 targets. Full GitHub runtime/visual acceptance and protected baseline activation remain
open under stages 4, 5, and 7.

A read-only live governance audit has one pending change: retire the old release environment's
`*-and-*-*` branch deployment policy. Readiness correctly refuses application while remote
`master` still has its historical schema-2 matrix. No governance policy or branch has been removed.

The final local acceptance gate passes: 391 CI tests, 525 release/Pages tests, 39 architecture
checks, all generated profiles, and all 15 workflow/action YAML documents. All 16 targets build
and stage 32 production plus 32 harness JARs. A second forced serial build reproduces every one
of those 64 JARs byte-for-byte. The durable local evidence records the exact source commit,
matrix/module/scenario hashes, staged manifest hash, and command reports. Routine Minecraft
image acceptance remains delegated to GitHub, as requested by the maintainer.

### Baseline completion order

The complete-baseline issuer now accepts authenticated completion from either the AI reviewer or
the successful Pages publisher. Pages explicitly wakes it after cache retention and rotation
scheduling have settled, so a fast AI review cannot strand a baseline that was still waiting for
public artifacts. The collector derives one complete source generation from all matrix target
archives; partial, mixed, failed, stale, and intermediate Pages wakes cannot certify coverage.
Targeted validation passes 20 publisher tests, 12 executable workflow-routing tests, and 49
workflow-security tests. This changes orchestration only; the verified production/harness bytes
remain the same.

### Superseded runtime cancellation

The final GitHub queue exposed an existing orchestration problem: an `always()` runtime matrix
kept executing after ordinary cancellation and held the PR concurrency group. The obsolete owned
run was force-cancelled after checking its repository, branch, workflow and commit. Runtime and
public-evidence producer jobs now also require `!cancelled()`; the required result gate and
completion notifications retain their failure-reporting behavior. Workflow-security checks pass
all 49 tests, and all 15 workflow/action files parse. No production or harness bytes changed.

### Shared Build gate duration

GitHub built, staged and uploaded the complete immutable E2E input bundle for PR source
`60c75ad36` in run `34011487053`. Its separate Build gate reached the inherited one-hour job
limit after passing repository-policy validation and while compiling the same serial matrix;
GitHub's annotation explicitly identifies that timeout. The Build job now allows 90 minutes,
matching the runtime input builder while accommodating its additional policy suites and staging.
The 49 workflow-security tests and all 15 YAML documents pass. Runtime acceptance remains pending;
this scheduling correction changes no production or harness source.

### Shared CI latency

The first successful final-source GitHub Build (`34014354343`, PR head `d00fdbe6`) took about
66 minutes. Its separately compiled E2E input bundle took 51 minutes, and 32 runtime rows were
limited to four runners. The unified source had exposed serial work and duplicated PR compilation;
fewer feature screenshots alone could not remove those costs.

The compiler now derives every target from the existing validated local plan and builds on up to
eight isolated hosted runners. Local Gradle remains serial. Complete assembly reverifies every
target's commit, matrix, JARs, harnesses and SBOM before it creates an aggregate bundle; a missing,
foreign, overlapping or altered target cannot pass. Repository-policy suites run alongside the
compiler and remain required by the stable Build gate. PR E2E waits for that exact successful Build
and consumes its immutable artifact, checking the manifest against the distinct tested merge SHA.
Standalone runs without an available bundle use the same compiler. Sixteen isolated runtime
runners retain the full required lane inventory while reducing scheduling waves. GitHub timing
and complete runtime acceptance for this revised orchestration are still pending.

Local validation passes all 408 CI tests and 532 release/Pages tests, the generated profile checks,
all 16 workflow/action YAML documents, and actionlint on the three changed workflows. A real
assembly round trip partitions and reverifies the previously accepted 64 JARs and their SBOMs,
reconstructs the complete bundle, and preserves every JAR hash without starting Gradle or
Minecraft. A read-only live probe authenticates the successful PR Build and its immutable bundle
while preserving the distinction between PR head and tested merge SHA.

### NeoForge equipment hook coverage

The first isolated-build runtime wave exposed an omitted loader-specific adaptation: NeoForge
1.21.5 still targeted `Player.getItemBySlot`, although the common hook already targeted its new
concrete owner, `LivingEntity`. The NeoForge hook now follows that boundary and queries the preview
scope by object identity, so unrelated living entities require no `Player` cast. A NeoForge-owned
bytecode test reads the active mixin configuration and compiled Minecraft classes, requires one
concrete injection target, and rejects that unsafe cast. It reproduces the original failure on
1.21.5 and passes after the correction; adjacent 1.21.4 and 1.21.6 checks also pass. The corrected
1.21.5 production and harness JARs build successfully. Full GitHub runtime confirmation remains
pending, and the ongoing preceding wave is retained long enough to collect other failures.

### Reuse after an identical-tree merge

PR #1926 passed all 16 compilation targets and 32 packaged runtime lanes, then its identical
merged tree started another Build and runtime matrix. The duplicate runtime was cancelled before
semantic AI review. The fix records the actual tested PR merge, its parents and Git tree at each
passing gate. Protected post-merge jobs authenticate those records, the merged PR, all original
jobs and immutable artifacts before skipping compilation and Minecraft. They retain small source
references; they do not copy or relabel the original JARs and captures.

Complete and selected review, Pages composition, complete-baseline publication and optional-mod
baselines resolve the original evidence through that reference. Selected admissions still name
the actual PR merge and protected base that authorized their scope. Original artifact identities
remain separate from the protected coverage generation. Duplicate visual producer notifications
skip targets with a trusted existing capsule or report. Missing/expired evidence or a changed tree
requires fresh work; pending checks and uncertain API responses cannot start parallel replacements.
Nightly and explicit full recovery remain available.

Local regression coverage includes real Git merge objects, bounded descriptor archives, the full
job graph, source substitution, expiry, pending executions, complete/selected visual curation,
and public provenance through compaction/composition. GitHub rollout is accepted only after the
protected merge is observed to allocate no duplicate compilation or Minecraft workers.

### Optional-mod publication of a reused runtime

The first post-merge generation that reused its PR runtime (`5fe67066`) ran its 1.21.6 optional-mod
wave cleanly, but the protected publisher rejected the wave with "compatibility plan fields are
invalid": its exact-field codecs for the schema-2 plan, the curation proofs and the public manifest
did not admit the `runtime_source` reference that the reuse rollout had already added to the
producer side. The publisher now validates that optional reference wherever it appears, requires
the plan, every lane proof and the manifest to carry the same reference bound to the covered
source commit and repository, reauthenticates the base runtime through the shared reuse validator
for every schema-2 wave, binds each lane's base artifact record to the unique authenticated
packaged-E2E artifact of the original execution, and links the gallery to the run that actually
produced the pixels. Historical schema-1 bundles cannot carry the field, and carry-forward keeps it
intact. Every authentication failure exits through the classified publication error. A local replay
of the real seven-lane 1.21.6 wave authenticated and compacted in about one minute with no new
runtime, model call or GitHub write.

### Deterministic animated-cape frame in deletion evidence

The same generation's semantic review of the 1.21.11 lanes reported the `cape_delete_local`
checkpoint as defective on both loaders: the surviving animated GIF cape showed its blue frame
while the 1.20.1 reference showed the red one, and the reviewer read that as a wrongly coloured
contrast cape. The deleted contrast cape was verifiably absent in every lane, so this was a
wall-clock frame lottery rather than a product defect, and the generation-bound block stopped the
remaining model calls before more capacity was spent. The deletion step now holds the GIF on its
first frame with playback paused until the deleted message settles, records the pinned frame and
speed in its passed assertion, and restores default playback after the capture. The scenario
contract is deliberately unchanged, so only that capture's runtime evidence changes and every other
cached verdict remains reusable. The later `cape_none_selected` and `cape_menu_hidden_builtin`
captures still show that tile unpinned; their expectations name no colour, and pinning a deselected
animation was not runtime-validated locally, so that remains a follow-up. The corrected generation
still has to complete Build, Packaged E2E, semantic review and Pages before it can seed the healthy
baseline that the pending HUD-only acceptance requires.

### Selective feature acceptance on GitHub

With the complete healthy baseline issued for `master` `d65c672b` (16 clean semantic reviews,
16 public `pages-full-baseline` archives), the HUD-only refactor PR #1935 was admitted
selectively by the protected `Select affected feature coverage` job: reason
`affected-module-coverage`, direct owner `hud-preview`, the `full` scenario reduced to the
`baseline`, `local_skin_apply`, `hud_preview_disabled` and `hud_preview_overlay` actions with the
two HUD captures per client. All 32 packaged lanes passed with that scope. Against the complete
profile of PR #1932 on the same lanes, the mean lane time fell from 12.1 to 2.3 minutes and the
total from 387 to 74 runner-minutes; each lane report carries the selection hash and exactly the
four steps and two screenshots.

### Optional-mod wave admission for shared generations

The first workflow-only merge after that baseline (`ce78a6cb`) exposed two orchestration gaps.
The compact compatibility publisher's Pages wake carried eleven `client_payload` properties,
which GitHub rejects; the artifact name is now derived from the bundle key on both sides and a
policy test caps every dispatch payload at ten properties. All sixteen clean waves of `d65c672b`
were then republished through the `publish` recovery operation without model calls and carried
forward to the descendant. Separately, the curator's schema-3 route never classified the shared
generation's diff and published the fail-closed default manifest, so every complete master
generation released all sixteen optional-mod waves; the redundant waves of `ce78a6cb` were
cancelled by hand. The route now classifies the complete first-parent diff of the exact source
commit with the existing fail-closed classifier, so documentation, policy and workflow-only merges
no longer start the wave while product, build, harness and compatibility-policy changes still do.
The review queue also paused on a provider quota at 01:59 UTC and resumed only when GitHub
delivered the next scheduled sweep at 06:06 UTC; the 30-minute cron is delivered every two to
five hours in this repository, so a quota pause currently costs several hours of idle queue.
