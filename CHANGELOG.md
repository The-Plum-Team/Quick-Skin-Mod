# Changelog

## 3.0.0 (2026-08-09)

### Added

- Added an application-level v2 networking handshake with explicit capabilities, exact-connection
  session identity, bounded replays, and a compatible legacy-v1 fallback.
- Added canonical SHA-256 content identifiers for local catalogs, persisted preferences, wire
  traffic, and server caches while retaining type-scoped authenticated SHA-1 migration aliases.
- Added deterministic CycloneDX 1.6 SBOM generation, artifact attestations, double-build
  reproducibility checks, and recoverable GitHub/Modrinth/CurseForge publication receipts.
- Added strict Gradle dependency verification, selective dependency locking, repository content
  filters, and reviewed Dependabot update pull requests.
- Added declarative GitHub ruleset/release-environment governance with a fail-closed readiness
  audit, plus a documented 1.21 release-train consolidation experiment.
- Restored `.cpmmodel` discovery, import, preview, selection, and CPM lifecycle integration on both
  Minecraft 1.21.4 loader lanes.
- Restored optional 3D Skin Layers preview integration for the supported 1.21.4 render path.
- Added packaged-artifact E2E coverage for the two release files and their two exact runtime
  combinations.
- Added high-resolution cape support, together with a cape editor that repositions, scales, and
  zooms a cape, previews it on the front, the back, and the elytra, and can flatten cape
  transparency onto a colour chosen with red, green, and blue sliders or a hex field.
- Skin and cape menus now pick up files copied into `quickskin/uploads/` from outside the game, without a client restart.
- Added build, scheduled E2E, release-gate, and dual-marketplace publishing workflows.

### Changed

- Bounded upload, cache, decode, executor, protocol-control, and local-asset refresh work; stale
  connection and scan results can no longer mutate a replacement session or newer catalog.
- Split required and optional Mixin failure policies, declared injection-count expectations, and
  enabled packaged-runtime application-count diagnostics.
- Isolated AI-assisted CI steps as read-only patch producers; deterministic jobs now revalidate and
  apply only narrow allowlisted changes without sharing model credentials.
- Made release tags and marketplace versions include the Minecraft era, and bound every publish to
  the exact branch head, changelog version, staged bytes, checksums, SBOM, and immutable release.
- Unified release, SBOM, reproducibility, and packaged-E2E artifact validation behind one strict
  schema-2 manifest contract with contained paths and byte-level digest verification.
- Made the exact negotiated connection profile authoritative for mandatory S2C channels, avoiding
  false-negative Forge loader probes while keeping pre-classification and optional channels
  fail-closed.
- Consolidated active development into one Stonecutter-managed source tree with narrow era overlays.
- Made the release matrix the source of truth for artifact paths, runtime coordinates, metadata ranges, and marketplace versions.
- Corrected loader metadata ranges, project links, and the All Rights Reserved license declaration.
- Isolated the active Minecraft 1.21.4 Fabric and NeoForge release lanes on their own thin branch.
- Quick Skin's source code is now published at <https://github.com/The-Plum-Team/Quick-Skin-Mod>.

## 2.6.2.5

### Fixed

- Fixed an `IndexOutOfBoundsException` when rendering the skin-list drop zone with one or two skins loaded.
