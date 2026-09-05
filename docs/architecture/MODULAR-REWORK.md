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
- [ ] **3. Feature and compatibility boundaries.** Migrate remaining production code to
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
- [ ] **6. Multi-version source consolidation.** Reconcile the latest exact release trees,
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

Run Gradle serially; Architectury's transform state is JVM-global. Start with targeted
checks and reuse successful evidence until another change invalidates it. Preserve wire
identity, bounded queues/I/O, session lifetime, optional-mod degradation, and client/server
isolation throughout the refactor.

No stage is complete merely because its design is documented. Update this checklist and
record exact commands/results below as implementation proceeds. Keep the active goal open
until implementation and required validation are complete.

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

Continue stage 3 from this worktree, preserving the module extraction, stable API boundaries,
world-storage test, test-isolation fix, and exact framebuffer setup. Preserve the current multiplayer
and session results alongside the full/CPM reports. Finish ownership of
event-driven shell behavior, resources, and loader/mixin bridges; narrow remaining native API drift
inside Minecraft modules. Introduce explicit provider bindings in the impact graph before enabling
selection: compile dependencies alone miss the injected reverse relationships. Keep integration
coverage for both ends without introducing compile cycles or coupling every consumer through the
composition root.

Next annotate scenario coverage and state prerequisites in the canonical contract and implement
capture selection throughout the harness and authenticated evidence pipeline together. Existing
source scanners, AI repair paths, release validation, and version-port consumers still need the
complete modular contract migration; passing their current tests does not certify that stage.
All 16 pinned release branches still require source/matrix/CI consolidation and validation. The
active 1.20.1 matrix and local development runs are not evidence for the other 30 artifact lanes.
Measure Gradle configuration cost when all target versions are registered; target-specific CI
tasks should not eagerly configure or resolve every unrelated Minecraft module/version node.
