# 0007. Admit optional-mod compatibility waves from module coverage

Date: 2026-09-07

## Status

Accepted.

## Context

Optional-mod compatibility evidence was admitted by a path allowlist: any product path requested
the sixteen `version x mod` waves, and Pages could carry existing compatibility evidence forward
only when the complete intervening diff was outside the product. The first selective feature
merge after the shared-source rework (HUD refactor `760d39ed`) exposed the gap: a selected
generation runs no compatibility wave by design, and because the HUD source is a product path the
classifier also refused to carry the previous evidence forward, so the public compatibility
gallery became empty although nothing that reaches an optional-mod integration had changed.

The same classifier decided every complete generation, so a workflow-only merge released all
waves until the shared route classified its first-parent diff (PR #1936), and an unattended
nightly complete run re-executed the 32 packaged lanes every day for evidence that no consumer
published.

The scenario contract already declares, per step, the modules and API bindings a checkpoint
exercises, and `e2e/selection.py` already derives affected checkpoints from the module graph's
reverse dependency closure. The compatibility scenarios, however, declared only the assembly.

## Decision

- The compatibility scenarios declare in `covers.modules` the product modules their harness
  exercises in that lane: the integrations the lane can actually run, the appearance, catalog,
  image and texture services behind an applied skin, the skin menu, previews and menu integration
  for the local lane, and networking plus the server-side delivery path for the remote lanes. The
  CPM first-person captures also declare the `cpm-assets` binding they render through. Their clean
  reference captures already carry their own coverage.
- `selection.compatibility_affected` decides, for a change made only of module-owned source
  files, whether the reverse dependency closure reaches a compatibility step or a reference
  capture. `scripts/ci/mod_compatibility_impact.py` requires the wave exactly then; a module change
  proven outside that closure carries the published compatibility evidence forward. Assembly,
  loader, harness, resource, policy, and unknown paths remain fail-closed.
- `e2e/selection.py` keeps a generation on the complete packaged profile with reason
  `compatibility-coverage` whenever that closure is reached, so the wave always has the complete
  clean runtime it pairs against and missing evidence is regenerated rather than left missing.
- The unattended nightly Packaged E2E schedule is retired: it re-executed the 32 packaged lanes
  every day for a generation no publication consumed. Complete coverage now comes from the
  generations that need it, namely an unproven or compatibility-affecting merge, the release
  profile, and an explicit complete-capture manual run.

## Consequences

- A feature change confined to modules such as `hud-preview` stays selective and keeps the
  compatibility gallery published from the last covering generation.
- A change to textures, appearance services, networking, the skin menu or an integration module
  runs the complete profile and the compatibility waves; that cost is paid only when the
  evidence can actually change.
- Measured on the current graph, `hud-preview` is the only feature module outside the
  compatibility closure. The 3D Skin Layers lane captures the skin menu with its preview, and
  `skin-menu` compiles against `cape-menu`, `settings-ui`, `skin-upload` and `skin-import` (and
  `cape-menu` against `cape-editor` and `cape-import`), so a change to any of those UI modules also
  keeps the complete profile until the skin menu's navigation into those screens is replaced by
  declared bindings, the pattern already used for the title and settings menus. That refactor is
  the follow-up that restores their selectivity without weakening this policy.
- Changing the compatibility scenarios' coverage changes the scenario-contract hash, so the first
  generation after this decision re-reviews every frame and regenerates every compatibility
  bundle once.
- The wave is admitted from the generation's own first-parent diff, while Pages carries evidence
  forward across the cumulative diff since the published `coverage_sha`. A wave that never
  completed, after its own durable retries, therefore leaves the gallery empty until the next
  change that reaches the closure or a manual complete run. Classifying the cumulative diff since
  that published `coverage_sha`, so the two decisions share one base, is the recorded follow-up.
- Daily unattended runtime is gone; a stale optional-mod lock or upstream regression is caught by
  the next covering change or a manual complete run, not by a nightly.
