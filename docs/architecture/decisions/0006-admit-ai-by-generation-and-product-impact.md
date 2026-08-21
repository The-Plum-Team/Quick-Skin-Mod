# ADR 0006: Admit AI review by generation and product impact

- Status: Accepted
- Date: 2026-08-17
- Scope: Claude admission for base visual and optional-mod compatibility E2E

## Context

Build and Packaged E2E are deterministic checks, but semantic screenshot judgment consumes a
limited external subscription. A visual PR targeting `master` could previously be reviewed once
from its pull-request evidence and again after the same change entered the cumulative 1.20.1
anchor. Changes to the visual-review prompt or runner could then invalidate the exact verdict cache
and repeat that policy validation across every later release. Finally, every clean synchronized
release tree could open optional-mod compatibility even when its diff changed only review
orchestration or documentation. These paths preserved coverage but spent model capacity without a
new packaged-product hypothesis.

Skipping screenshots through an approximate or reduced “fast pass” is not acceptable. The system
must keep semantic 1.20.1 certification, full deterministic evidence, exact cache keys, Opus
confirmation, and fail-closed treatment of unknown changes.

## Decision

Use the post-merge automatic generation as the single Claude admission point for PRs targeting
`master`.

1. Such PRs still run Build and Packaged E2E, but their completed evidence never enters the visual
   model queue. If merged, the cumulative 1.20.1 synchronization anchor performs the semantic
   review. Direct release-branch PRs retain fail-closed source review because no automatic
   post-merge anchor is guaranteed.
2. The 1.20.1 anchor keeps the strict `replicated-port` classifier. After that exact generation is
   certified, a later port may use `post-anchor-port`, which additionally allows review prompts,
   reviewer code, and Claude-admission policy already exercised by the anchor. Admission requires
   the port branch to name an authenticated `Sync version branches` run whose event is the exact
   certificate-driven `repository_dispatch`; a manual recovery target remains strict.
3. Optional-mod compatibility has an independent fail-closed impact classifier. Protected code
   reads the complete server-side synchronization-PR inventory, classifies both sides of renames,
   and binds the normalized manifest into the curated visual proof. Only product, build,
   runtime-harness, compatibility-policy, malformed, or unknown impact can release compatibility.
   Visual-review policy, publication, documentation, and policy-test changes cannot.
4. No coverage is replaced with heuristic similarity. Product/scenario changes still reach every
   applicable semantic review, paired exact-policy verdict caching remains content-addressed, and
   all required deterministic gates remain unchanged.
5. An exact-policy cache shard may cross an unrelated `master` merge only if its protected owner
   commit remains an ancestor and its cache-producing workflow blob is byte-identical. The current
   codec still validates every candidate/reference digest, expectation, runtime evidence, loader,
   contract, matrix, prompt, model, and chunk-policy key before accepting a hit.
6. `e2e/full-validation-baseline.json` intentionally forces one complete visual and compatibility
   wave while adopting this policy. It carries the cape/Elytra fixes whose previous generation was
   paused by provider quota; it remains unchanged in later policy-only diffs.

## Alternatives rejected

- **Review both the PR and post-merge anchor.** This pays twice for one source change and the PR
  verdict cannot certify the final synchronized release tree.
- **Run a cheap or partial model pass first.** It reduces cost by weakening the exact semantic
  coverage that caught shared-loader and rectangular-Elytra defects.
- **Treat every clean visual report as compatibility impact.** Review policy and documentation do
  not alter the packaged game or an optional-mod integration.
- **Trust branch names without authenticating the synchronization run.** A manual or forged target
  could then claim that an anchor had already exercised its policy.
- **Drop the quota-paused generation during migration.** That would optimize future work by silently
  abandoning the full validation already promised for the cape/Elytra fixes.

## Consequences

Merging into `master` no longer means that Claude necessarily runs. A nonvisual cumulative anchor
can continue through its existing exact certificate without a model. A visual-policy change pays
for one semantic anchor review and does not automatically repeat compatibility. Product changes
retain the anchor, version, and product-relevant compatibility coverage. Unknown or unverifiable
provenance remains on the expensive fail-closed path.

The transition intentionally incurs one complete baseline wave after provider capacity returns.
Afterward, routine review-orchestration changes no longer create the pre-/post-merge duplicate or
the full later-version and optional-mod amplification.
