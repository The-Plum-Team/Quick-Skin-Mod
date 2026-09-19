# Modular, multi-version rework

Status: stages 1-6 are complete; stage 7 and the follow-ups under
[Remaining work](#remaining-work) are open. This plan records the architecture, change-impact
contract, outcome and remaining work of the maintainer's 2026-09-05 request to reorganize Quick
Skin and its Minecraft compatibility API into separately compiled modules, consolidate version
development, and generate/review only the E2E captures affected by a change.

The dated progress log, with every intermediate command, test count, run ID and evidence hash, is
preserved in Git history; read it with
`git show 5a4a952880d5d97d81e6fb09cafeef7668923707:docs/architecture/MODULAR-REWORK.md`.

## Baseline

- Starting source `266d156165d9514499ef7f4cfc905532728a7849` built Fabric and Forge 1.20.1 from
  192 canonical Java files (46,717 lines, 3,554 Stonecutter markers) with 254 JUnit tests.
- Its scenario contract had ten scenarios; the `full` scenario alone had 69 ordered client steps,
  and execution/evidence assumed the complete scenario graph.
- The other supported targets were imported from 16 pinned release branches and their 32 artifact
  lanes. Those pins are historical migration inputs, not a support inventory.

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
- [x] **4. Selectable E2E graph.** Extend the versioned scenario contract and generated Java
  model with coverage and prerequisites, split stateful monolithic execution into reusable
  feature flows, and implement selection before screenshot generation. Verify selective
  and full modes in packaged Minecraft.
- [x] **5. Protected selective review and evidence.** Carry selection identity through CI,
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

Stages 4 and 5 were accepted on GitHub. `master` `d65c672b` issued the first complete healthy
baseline from 16 clean semantic reviews and 16 public `pages-full-baseline` archives. The HUD-only
PR #1935 was then admitted selectively (`affected-module-coverage`, owner `hud-preview`): all 32
packaged lanes passed with four actions and two captures per client, and the mean lane time fell
from 12.1 to 2.3 minutes (387 to 74 runner-minutes) compared with PR #1932's complete profile on
the same lanes.

## Outcome

- **Modules.** `architecture/modules.json` declares 37 separately compiled production modules plus
  the common assembly, with no inter-module cycles. Common keeps mixins, event registration and
  lifecycle composition; own-skin import and saved-appearance restoration moved to `skin-import`
  and `appearance-services`, shrinking `ClientEvents` from 523 to 276 lines. Client bootstrap
  injects CPM asset access, network actions, the CustomNPCs listener and the connection identity
  source, so features never depend on the mixed client/server transport.
- **Minecraft adapter.** `MinecraftCompat` selects one of four API-family implementations
  (immediate GUI, RenderType, RenderPipeline, GUI extraction); each target compiles exactly one.
  One `MinecraftTextureUploads` adapter serves the catalog, network and animation caches, the star
  background and the cape editor. The NeoForge `legacy26_1`
  overlay reaches only 26.1 and 26.1.1.
- **Matrix and build.** Release-matrix schema 3 builds all 16 imported targets through 26.2 (32
  production JARs and 32 harnesses) from one source; 26.3 was added afterwards directly from the
  shared source. `scripts/release/build_matrix.py` runs one serial Gradle
  process per target, because a single-JVM aggregate exhausted its heap during remapping, and
  `-PquickskinTarget` scopes a build to one target. A forced rebuild reproduced all 64 JARs
  byte-for-byte.
- **Selective evidence.** Scenario contract schema 3 declares module/binding coverage and
  prerequisites. Git-object admission binds exact base/head/policy identities, selected curation
  (proof schema 7) never certifies complete coverage (schema 8), and public evidence keeps each
  frame's original tested run, commit and JAR. Stage 4/5 acceptance is recorded above.
- **Shared-source CI.** Every target compiles on its own runner into one immutable bundle that
  Packaged E2E consumes; an identical-tree merge reuses its PR evidence instead of rerunning
  (the lesson of PR #1926); visual review, Pages and optional-mod waves are partitioned per target;
  and release tags select one target. `mc26.1`, `mc26.1.1`, `mc26.1.2` and `mc26.2` `v3.0.0` were
  published on 2026-09-12/13.
- **Runtime defects found by the GitHub matrix.** 1.21.5 moved `getItemBySlot` to `LivingEntity`
  (common and NeoForge hooks now follow it); `SkinManager.get` has two returns on 1.21.9-1.21.11;
  and animated capes are held on a fixed frame during deletion and cape-menu captures so frame
  timing cannot be judged as a defect. Each fix has a bytecode or runtime regression.
- **Optional-mod admission.** Per-target shared plans are recomputed by producer and consumer, the
  publisher accepts a reused `runtime_source`, and waves are admitted from module coverage
  ([ADR 0007](decisions/0007-admit-optional-mod-waves-from-module-coverage.md)).

## Remaining work

- Stage 7: final integrated acceptance and handoff, including a documented review of the complete
  diff against this plan.
- Finish ownership of the event-driven shell behavior, resources and loader/mixin bridges still in
  common, and narrow the remaining native API drift inside the Minecraft modules.
- Recorded as outstanding and not yet confirmed closed: migrating the source scanners and AI repair
  paths to the complete modular contract. Version-port automation is retired rather than migrated
  (see [VERSION-BRANCHES.md](../../VERSION-BRANCHES.md)).
- Declare the skin-menu navigations to the cape menu, settings, upload and import as bindings, so
  those modules can leave the optional-mod compatibility closure; when ADR 0007 was adopted, only
  `hud-preview` was outside it.
- Classify the cumulative diff since the published compatibility `coverage_sha`, so evidence from a
  wave that never completed is restored by the next merge rather than only by a covering change.
- Review the older `full` helper's state/setup before pruning its action-prefix prerequisites.
- Collect Replay playback evidence on GitHub; optional Replay runtime validation is still missing.

Working rules for this migration: run Gradle serially, since Architectury's transform state is
JVM-global; routine per-version image E2E runs on GitHub, with local Minecraft launches only for a
concrete runtime question; and no stage is complete merely because its design is documented.
