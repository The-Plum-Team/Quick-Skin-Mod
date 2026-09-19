# Batch pull requests

A draft pull request to `master` starts no Build or Packaged E2E work. Its checks show only
`Build deferred for draft` and `Packaged E2E deferred for draft`, and it cannot be merged. Land
drafts either by marking one ready, which runs the complete gate for that PR as before, or by
validating several together through one batch PR. [ADR 0009](../architecture/decisions/0009-validate-draft-pull-requests-in-batches.md)
records why.

[`pr_batch.py`](../../scripts/ci/pr_batch.py) builds and settles batches. It needs `git`, an
authenticated `gh` CLI, and push access to the repository. It never merges, never changes a ruleset
and never runs Gradle or Minecraft.

## Workflow

1. Open each change as a draft PR to `master`:

   ```sh
   gh pr create --draft --base master --title "fix: describe the change"
   ```

   Push to it as often as needed. Each push costs two short deferred jobs.

2. Optionally check that a set squashes cleanly, without pushing or writing to GitHub:

   ```sh
   python3 scripts/ci/pr_batch.py prepare --dry-run 1971 1972 1973
   ```

3. Create the batch PR. PR numbers are applied in the order given. `--drafts` appends every other
   open same-repository draft to `master`, oldest first:

   ```sh
   python3 scripts/ci/pr_batch.py prepare 1971 1972 1973
   python3 scripts/ci/pr_batch.py prepare --drafts
   ```

   The tool squashes each PR onto current `master` as one commit titled `<PR title> (#N)`, pushes
   a new `batch/<UTC timestamp>` branch (`--name` chooses another suffix), opens one ready PR and
   comments on every included PR. The batch PR runs the complete required gate once.

4. When the batch PR is green, merge it like any other PR.

5. Close the included PRs:

   ```sh
   python3 scripts/ci/pr_batch.py settle 1980
   python3 scripts/ci/pr_batch.py settle 1980 --delete-branches
   ```

   `settle` refuses a batch PR that has not merged. It closes each included PR whose head is still
   the batched commit and leaves open any PR that received later commits, because those commits
   never reached `master`. `--delete-branches` also deletes a closed PR's branch, but only while it
   still points at the batched commit.

## When a batch fails

A failed batch changes nothing on `master`. Find the failing PR from the failed lane or log. The
batch branch has one commit per PR, so `git bisect` over it, or a smaller batch, narrows the
failure down. Fix that PR on its own branch; it stays a draft. Close the failed batch PR, delete
its branch, and prepare a new batch.

`prepare` stops before pushing when an entry conflicts with `master` or with an earlier entry, and
names the PR and files. Resolve the conflict on that PR's branch, or leave that PR out.

## Rules the tool enforces

- Only open PRs from this repository to `master` are accepted. Fork PRs are refused, because their
  code must not enter a same-repository branch; review them and mark them ready individually.
- A PR whose changes are already contained in `master` and earlier entries is refused.
- An existing `batch/*` branch is never overwritten.
- The batch PR body carries a machine-readable marker pinning every included head. `settle` trusts
  only a well-formed marker for the batch PR's own branch.

Like any PR, a batch PR must be up to date with `master` before it merges. If `master` moves first,
update it, which reruns its gate, or prepare a new batch.
