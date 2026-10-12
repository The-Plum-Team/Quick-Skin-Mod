# 0013. Require the gate App for the default branch

Date: 2026-10-11

## Status

Accepted. Amends [ADR 0009](0009-validate-draft-pull-requests-in-batches.md).

## Context

The default-branch ruleset required `Build and verify` and `Packaged E2E gate` from GitHub Actions
(integration 15368): the gate jobs of `build-gate.yml` and `on-demand-e2e.yml`, which run on
`pull_request` from the candidate's own workflow files. mod-base's shared Build and packaged E2E
(`docs/BUILD-E2E-DESIGN.md` of the kit, steps Q1 to Q7) moves the authority over those names to
protected controllers on `master`: the managed callers run on `pull_request_target`, execute the
candidate only in a disposable credentialless account, and the managed `mod-base-gate-status.yml`
publishes the two contexts as commit statuses through a statuses-only GitHub App after an
independent evaluation of the complete generation.

Quick Skin ran the shared gates in `shadow` (Q4) beside the native ones, then in
`shared-build-and-e2e` with the required names (Q5), where the App published `Build and verify`
and `Packaged E2E gate` on every pull-request head next to the native checks of the same names.
The owner then switched the expected source of both names in the default-branch ruleset from
GitHub Actions to the gate App (Q6), after successful App contexts within GitHub's seven-day
window. From then on a native check of a required name can neither satisfy nor stand in for the
App's status, and keeping that name on a native job only makes the two writers easy to confuse.

## Decision

- The default-branch ruleset requires both contexts from the gate App;
  `required_check_integration_id` of `release/github-governance.json` names it, so a governance
  update keeps that source instead of writing a rule any writer of the same name could satisfy.
- On a pull request to `master` the native gate jobs are named `Native Build and verify` and
  `Native Packaged E2E gate` when ready, and keep their draft names from ADR 0009 as drafts. They
  still run, because Pages, feature coverage, visual review, post-merge reuse and releases read
  their artifacts; retiring them is a later step (Q9, Q10), not part of this one.
- Pushes, dispatches and pull requests to other bases keep `Build and verify` and `Packaged E2E
  gate`: their readers (release recovery, target status, the canonical wake) and the release
  branches' own ruleset expect those names from GitHub Actions.
- Every reader of a native run that may be a pull-request run (the staged Build bundle, the E2E
  job graph, post-merge reuse and visual review) accepts exactly one job named by either name and
  rejects a run with both.
- Batches (ADR 0009) are unchanged: the batch pull request is an ordinary ready pull request and
  gets the App's statuses; its draft members start no Build or Minecraft work.

## Consequences

- A pull request to `master` merges only on the App's statuses, which only the protected
  evaluator on `master` can produce. The native jobs remain visible and their failure still fails
  the native run, but they no longer gate the merge.
- Returning to native authority is a separate owner governance decision: the source of the
  required names goes back to GitHub Actions in the ruleset, and a protected pull request through
  the App-gated route restores the old job names and this file's source together (the kit's
  `reviewed-rollback` mode). Nothing weakens on its own when the App or its key is unavailable:
  the statuses stay pending or fail, and nothing merges.
- `github_governance.py apply` never switches or drops the live source of a required check. The
  owner switches it on the ruleset (Q6) before this file records it, and restores it the same
  way, so an apply from `master` in between refuses instead of handing the names back.
- If the App cannot publish, nothing merges, the rollback pull request included. The owner's
  restoration of the GitHub Actions source then comes first, and that pull request is gated by
  `build-gate.yml` and `on-demand-e2e.yml` dispatched on its branch, which keep the historical
  names.
- A pull request opened by `GITHUB_TOKEN` (the release status refresh of
  `refresh-release-status.yml`) starts no `pull_request_target` run, and its dispatched native
  gates no longer count: a maintainer closes and reopens it, or pushes to it, so that the shared
  gates run and the App publishes.
- Each pull-request generation still runs both pipelines until Q10, as in the shadow period:
  roughly 400 GitHub requests for the shared generation on top of the native traffic.
