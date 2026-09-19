# 0009. Validate draft pull requests in batches

Date: 2026-09-18

## Status

Accepted.

## Context

Every pull request to `master` runs the complete required gate. Build compiles every matrix
version on its own hosted runner beside three policy suites, then Packaged E2E runs one isolated
job per version/loader lane. With the current matrix that is 16 compile jobs and 32 Minecraft jobs
for each PR. The default-branch ruleset requires `Build and verify` and `Packaged E2E gate`, with
strict up-to-date branches and no bypass actors.

Merging does not repeat that work only when `ci_reuse.py` can prove that the merged tree is
identical to the tested PR merge. A maintainer landing several small changes does not get that
benefit. Each merge advances `master`, the strict rule makes every other open PR stale, and
updating a PR reruns its complete gate. A set of N small changes therefore costs at least N
complete gates plus their reruns, and each one has to be waited for before the next can merge.

GitHub's merge queue was evaluated and rejected for this problem. It creates one cumulative merge
group per queued PR (the first PR, the first two, and so on) and dispatches a complete CI run for
each group, up to its build-concurrency limit. Its merge limits only decide how many groups that
have already been built merge together. It keeps `master` green without manual rebases, but N PRs
still cost about N complete runs. Adopting it would also require `merge_group` support throughout
the authenticated reuse, bundle transport and review admission, which currently identify runs by
their `pull_request`, `push` or `workflow_dispatch` event.

GitHub reports a job skipped by its `if:` condition as passing for a required context, and it does
not evaluate a skipped job's name expression. If a draft run skipped a job named
`Build and verify`, that skipped check would satisfy the ruleset for the same head after the PR is
marked ready, until the new run created its own check.

## Decision

- A draft PR targeting `master` starts no Build or Packaged E2E work. Both workflows also trigger
  on `ready_for_review` and `converted_to_draft`. The existing per-PR concurrency group makes a
  conversion to draft cancel the in-progress gate.
- In a draft run, each required gate job runs only a summary step, under a distinct name:
  `Build deferred for draft` and `Packaged E2E deferred for draft`. No draft run reports a required
  context, seals a tested-source record or publishes a staged bundle. Ready PRs, PRs to other
  bases, pushes and dispatches keep their current jobs and names.
- Marking a draft ready runs the complete gate for its head through the existing path.
- `scripts/ci/pr_batch.py prepare` squashes the selected open same-repository PRs, in order, onto
  current `master` in a temporary worktree. It makes one commit per PR, titled with the PR title
  and number, pushes the result to a new `batch/<name>` branch and opens one ordinary PR. That PR
  runs the unchanged complete gate once for the combined tree. Its body pins each included PR's
  head commit.
- After the batch PR merges, `pr_batch.py settle` closes the included PRs whose head still equals
  the pinned commit. Any PR that received later commits stays open.
- Fork PRs are refused. Their code must never enter a same-repository branch, whose PR runs are
  trusted.
- When a draft is marked ready, both workflows start together. The E2E bundle consumer therefore
  waits past the head's deferred draft Build run instead of treating it as a missing gate.
- Governance readiness pins the non-draft fallback of both required context names.

## Consequences

- A set of N drafts costs one complete Build and Packaged E2E run for the batch, plus a trivial
  deferred run per draft push, instead of N complete runs and their strict-update reruns.
- `master` remains gated by the same ruleset. A draft cannot be merged, and the batch PR passes the
  same required checks as any other PR. Post-merge reuse authenticates the batch PR as the merged
  PR without any change.
- A failing batch does not identify the failing PR by itself. Each PR is one squashed commit, so
  bisecting the batch branch or preparing a smaller batch isolates it. Squashing keeps one
  reviewable commit per PR on `master`, but it drops each PR's internal commit boundaries. Those
  commit lists are recorded in the squashed message and remain on the closed PR.
- The included PRs are closed, not marked merged, because their own head commits never reach
  `master`. Comments from `prepare` and `settle` link each PR to its batch. Keeping those heads out
  of `master` also leaves `ci_reuse.py` exactly one merged PR per merge commit.
- Draft PRs to `master` receive no Build or Minecraft feedback. A contributor who wants it marks
  the PR ready. `prepare --dry-run` checks locally that a set of PRs squashes cleanly, but it runs
  neither Gradle nor Minecraft.
- The two required gate jobs are now named by expressions. Every consumer of those names reads
  non-draft runs, where the expressions evaluate to the unchanged contexts. A gate skipped in an
  attestation dispatch now shows its unevaluated expression, and no consumer reads that name.
- The rulesets are unchanged.
