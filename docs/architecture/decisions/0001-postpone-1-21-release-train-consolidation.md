# ADR 0001: Postpone consolidation of the Minecraft 1.21 release branches

- Status: Superseded by [ADR 0003](0003-shared-source-with-independent-target-releases.md)
- Date: 2026-08-02
- Scope: `fabric-and-neoforge-1.21.1` and `fabric-and-neoforge-1.21.11`

## Context

Quick Skin keeps one exact Minecraft version per release branch while sharing the
implementation through Stonecutter. We tested whether the two active 1.21
branches should instead become one multi-version release train.

The evaluated revisions were:

- 1.21.1: `83a0cee4026f70597dc989aae352092d2512d6c3`
- 1.21.11: `585894caafecd6739c6d92b49c6e0dfa0e5cc4ba`
- merge base: `ede9a965a25ee2717a29d94a85055522eadea71c`

## Method

We compared both Git trees, then created clean detached baseline worktrees and
an isolated, uncommitted combined-matrix prototype. Every build used JDK 21 and
ran serially with:

```text
./gradlew --no-daemon --no-parallel --stacktrace clean buildAllLanes buildAllE2EHarnesses
```

For each baseline and the prototype, the release verifier and staged-artifact
verifier passed. An independent comparator checked both the complete-file
SHA-256 and a normalized ZIP digest over every sorted JAR entry, rejecting
duplicate entries and entry-set differences. Finally, packaged
`phase0-smoke` was run serially for all four version/loader rows.

## Evidence

The canonical Java source trees have identical Git tree hashes across the two
branches: 0 canonical implementation lines would be eliminated. The existing
source already contains 3,538 Stonecutter markers in 96 files, including 653
markers in 81 files that cross the 1.21.11 boundary. Consolidation would retain
four build lanes and four packaged-runtime lanes. It would co-locate 12 overlay
files (1,283 lines), not remove them.

Build results:

- 1.21.1 baseline: passed in 45 s; 43 actionable tasks (42 executed).
- 1.21.11 baseline: passed in 48 s; 41 actionable tasks (40 executed).
- Combined prototype: passed in 69 s; 83 actionable tasks (81 executed), with
  common JUnit tests executed against both versions.

All eight prototype JARs were byte-identical to their independent baselines:

| Artifact | SHA-256 |
| --- | --- |
| Fabric 1.21.1 production | `47a7d1d80328fa78c2965994d869f0d1ac0221250277f89bb5af7c273f17533a` |
| Fabric 1.21.1 E2E harness | `a3224305979623f3667cea2f3ddd4650b1f412ab4a1ae77e6574024511a2b1d8` |
| NeoForge 1.21.1 production | `e20370d732ed39f21facf8ce457076218bfa6c896a644241e5cd792a6580c653` |
| NeoForge 1.21.1 E2E harness | `022206a12c0fd1e55f28930f7b1415b4b14d69c34a5195b84d335f3f60d1813a` |
| Fabric 1.21.11 production | `c4266df60b5cce1ced6283afc2a6735febb1aa06c6d6a7ddf0d5f93842564bc8` |
| Fabric 1.21.11 E2E harness | `0bf4871391bf2fdb645baf6d8761d4ae0c51bd4584c7697a5333cf26cad4ec91` |
| NeoForge 1.21.11 production | `169ad0c617c4f15e7d9dec3bd5be29b7c55f15b943e22ffd52745ae434ba6dac` |
| NeoForge 1.21.11 E2E harness | `a66cc7eb54761aee1e980d169b7a15eb64a981325dba8d92492c99d37e702736` |

Packaged-runtime results:

| Lane | Result | Time | Visual changed fraction |
| --- | --- | ---: | ---: |
| Fabric 1.21.1 | Pass | 52.3 s | 0.0405341 |
| NeoForge 1.21.1 | Pass | 68.4 s | 0.0405239 |
| Fabric 1.21.11 | Pass | 46.1 s | 0.0454363 |
| NeoForge 1.21.11 | Pass | 54.0 s | 0.0452774 |

The first NeoForge 1.21.1 installation attempt encountered a transient
third-party LWJGL download/cache checksum mismatch. The downloaded file later
matched its expected checksum and ZIP structure; a contained retry of that lane
passed. No Quick Skin artifact or matrix change was involved.

The prototype also exposed a release-contract mismatch: matrix parsing can
represent both versions, but status-table validation correctly rejects a branch
named for exactly `1.21.11` when it declares both `1.21.1` and `1.21.11`.

## Decision

Do not consolidate these branches now.

The change is technically viable and would reduce the branch and synchronization
control plane from two to one. It would not reduce canonical implementation,
build lanes, packaged-runtime lanes, or overlays. In exchange, it would couple
release and hotfix cadence, make a version-specific change rebuild and republish
the unaffected version, let one lane block both releases, broaden the rollback
surface, and require migration of the deliberate exact-version branch contract.

The current design already obtains the most valuable consolidation: one
canonical source tree, small version overlays, content-addressed Git storage,
and automated synchronization while releases remain isolated.

## Revalidation on 2026-08-06

The repository now supports enough exact-version branches to meet the first reconsideration
threshold, so the premise was checked again before the E2E-platform refactor. The old
`codex/consolidation-pilot-1.21` reference is 53 `master` commits behind and is evidence only; it
must not be revived as an implementation branch. The current 1.21.1 and 1.21.11 release trees no
longer have identical relevant source/build inputs: 15 canonical, loader, metadata, build, or
packaged-runtime files differ, including mixins and `e2e/packaged_runtime.py`.

A train topology could reduce synchronization PRs, branch build jobs, final attestations, and
release administration. It would not remove the 32 loader/version runtime lanes or 128 scenario
executions across the current sixteen branches, which dominate measured propagation time, and it
would still couple hotfix, publication, and rollback boundaries. The decision therefore remains
postponed. A future evaluation must build a fresh neutral prototype for each candidate cohort
(not the stale pilot), prove byte-for-byte JAR parity, run every packaged scenario, and dry-run
Release and Pages before changing branch topology.

## Reconsideration thresholds

Reopen this decision when at least one of these conditions is measured:

1. Three or more concurrently supported versions in the same Minecraft API
   family use the same loader set and release cadence for two consecutive
   release cycles.
2. Cross-branch synchronization and release administration consume at least
   four engineer-hours per release for three consecutive releases.
3. Cross-branch drift causes at least two user-visible defects or missed fixes
   in one quarter.

Meeting a threshold starts a new evaluation; it does not automatically approve
consolidation. Adoption would additionally require a neutral train branch such
as `fabric-and-neoforge-1.21`, an explicit `project.release_track`, status rows
flattened per published artifact, updated branch/ruleset and release guidance,
and successful parity plus packaged-runtime validation for every lane.

## Consequences

The exact-version branches remain the release and rollback boundaries. The
prototype is evidence only and must not be merged as product code. Future
evaluations should repeat the baseline-versus-prototype artifact comparison and
all packaged-runtime lanes so that this decision stays evidence-driven.
