# ADR 0003: Shared source with independent target releases

- Status: Accepted; implementation and final acceptance in progress
- Date: 2026-09-05
- Supersedes: [ADR 0001](0001-postpone-1-21-release-train-consolidation.md)

## Decision

Develop features and API-family adapters in separately compiled Gradle modules on shared source.
Use the complete release matrix to select Minecraft/loader targets, narrow source overlays and
runtime pins. Assemble one production JAR per target/loader and keep its harness separate. The
module registry owns compile dependencies and runtime bindings; the scenario contract owns feature
coverage and capture prerequisites.

Retain independent canonical target tags and marketplace identities. A tag chooses one target
and carries it through build, reproducibility, runtime validation and publication. The aggregate
build bundle cannot be published as a target release. Shared-source mode creates no version-port
work; existing branch refs remain historical inputs until separately authorized cleanup.

## Evidence and tradeoffs

ADR 0001 correctly identified that moving a matrix alone would leave the code coupled and make
release, status and evidence consumers inconsistent. This migration changes those contracts rather
than adopting that old combined prototype.

The pinned sixteen-target inventory builds 32 production JARs and 32 harnesses from shared source.
At the menu/HUD checkpoint, the graph has 37 production modules plus the common assembly; all
outputs stage and independently verify. The complete serial build takes 307 seconds. A real
1.21.8 rebuild reproduces its four JARs byte-for-byte. The stable gate has 264 JUnit tests and 39
architecture tests; 497 release-policy and 301 CI tests pass. Local execution of the release
identity step accepts every declared manual target and rejects missing, unknown, stale and tag
override cases. Full per-target reproducibility and new image execution remain pending.

One JVM for the full native matrix exhausted its heap during remapping. The matrix coordinator
therefore validates the inventory once and builds one target at a time in bounded serial Gradle
processes. Adding another supported target increases build/runtime obligations; shared source does
not remove the need to validate those targets. It removes repeated source-branch porting and keeps
API changes and their feature consumers reviewable together.

Feature selection already separates actions from captures. Local plans select two HUD captures or
five menu-integration captures against 90 in the complete PR profile. Protected CI continues full
coverage until authenticated baseline evidence, selective certification and reuse are integrated.
The maintainer has directed routine new image E2E to GitHub after imports; compilation and local
plans are not visual certification.

## Remaining migration gates

`MODULAR-REWORK.md` owns the unfinished work: remaining native/feature/resource boundaries,
protected selective review, release governance and Pages, complete reproducibility, and GitHub
runtime/visual acceptance. This architectural decision does not declare those gates complete or
authorize production publication or deletion of remote branches.
