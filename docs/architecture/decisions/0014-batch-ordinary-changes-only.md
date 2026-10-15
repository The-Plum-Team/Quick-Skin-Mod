# 0014. Batch ordinary changes only

Date: 2026-10-11

## Status

Proposed. Amends [ADR 0009](0009-validate-draft-pull-requests-in-batches.md). Step Q8 of
mod-base's shared Build and packaged E2E (`docs/BUILD-E2E-DESIGN.md` of the kit), first part.

## Context

ADR 0009 lets `scripts/ci/pr_batch.py` squash any open same-repository pull request into a batch.
The batch pull request then runs the complete gates once. Since ADR 0013 those gates are the
protected shared ones, published by the gate App, and their trust rests on controllers, adapter
and policy files, the kit pin and config, the release matrix and the dependency policy that a
pull request must not change unnoticed.

The kit's design says that "protected controller upgrades, restricted transitions, kit bumps and
matrix changes never hide inside a batch", that Quick Skin's rule that kit bumps land through
batches changes, and that settlement checks the merged batch's provenance before closing members.
Its generic constructor `ci batch-prepare` takes the paths a batch may change as a list "written by
the mod's protected policy". Its `ci batch-settle` rebuilds the stack from the marker before
closing anything, because anyone who can edit a pull request body can edit the marker.

`pr_batch.py settle` trusted the marker in the body as long as it was well formed and named its own
branch. An edited marker could close a pull request that never merged.

Of the twelve batches from #1985 to #2072, eight had at least one member that changed a path the
list below refuses, mostly native workflows under `.github/`, `e2e/scenario-contract.json`,
`gradle.properties`, and the Gradle wrapper and dependency locks.

## Decision

- `scripts/ci/mod-base-batch-paths.json` lists the paths a batch may change, in the format of the
  kit's `--allowed-paths`: canonical repository paths in strictly ascending order, each admitting
  itself and everything below it.
- `pr_batch.py` derives the list from the tracked tree minus `BATCH_PROTECTED_PATHS`:
  `.gitattributes`, `.github/`, `docs/ai/shared/`, `e2e/scenario-contract.json`,
  `e2e/scenario_contract.py`, `gradle.properties`, the Gradle wrapper, dependency locks,
  repository policy and verification metadata, `release/`, the files `scripts/ci/mod-base-build.json`
  pins, `scripts/ci/mod_base_kit.py`, `scripts/pages/mod_base_adapter.py`, `site/`, and
  `pr_batch.py` with its own list. `pr_batch.py paths --write` regenerates the list, and a test
  requires the committed list to equal it.
- `prepare` reads the list from the `master` commit it builds on, never from a member, and refuses
  a member whose own patch (from its merge base with `master` to its head) changes a path outside
  it, a submodule entry or an entry of another mode than file, executable or symbolic link.
  `master` without a valid list cannot be batched.
- Kit bumps and every other change to a protected path take their own pull request, marked ready
  on its own.
- `settle` closes members only when the merged batch pull request's own commits match the marker:
  the same commits in order on the marker's base, ending at the merged head, each with its member's
  number in the title and the batched head in its message. Commit ids cover messages and parents,
  so editing the marker cannot change which pull requests close.

## Consequences

- A batch can no longer hide a control-plane change behind the review of the members around it.
  Such a change costs one complete gate of its own, as a kit bump already did in practice.
- A new file in a directory that holds a protected path (for example a new script under
  `scripts/ci/`) cannot be batched until the list is regenerated. A test fails until it is.
- A batch branch is never updated with `master`. An updated branch has commits its marker does not
  list, so `settle` refuses it, as the kit's `ci batch-settle` does. When `master` moves before the
  batch merges, a new batch is prepared.
- The list is the input the kit's `ci batch-prepare` will take when Quick Skin moves its batches to
  the kit (the rest of Q8). `pr_batch.py` then retires (Q10).
- Settlement still does not read the gate evidence of the batch. The merge itself required both
  App contexts on the batch head. The kit's `ci batch-settle` adds that proof once it can find the
  evidence on its own.
