# Public evidence from shared source

The release matrix owns the supported Minecraft targets. Public evidence uses an explicit
target key, such as `mc1.21.8`, to keep its directory and artifact identity separate from the
real Git source branch. `scripts/pages/evidence_target.py` validates the complete matrix,
properties and module routing before deriving any target view. The target's manifest retains
the SHA-256 of the complete matrix, rather than hashing the projected view.

Ordinary handoff schema 3 and compact schema 4 keep the existing exact PR-profile capture,
assertion, comparison, image and JAR checks. Each bundle contains only one target's complete
loader/scenario product. Both tested provenance records name the same source branch and commit;
the target key is never passed to GitHub as a branch. Raw-to-WebP conversion remains atomic,
retains both source and derivative proofs, and never puts source PNGs into a compact cache.
The current frame, byte and extraction bounds still apply to each target independently.

An aggregate run may be projected by artifact node after bounded result-metadata validation.
Only selected images are decoded, and missing or extra selected lanes still fail validation.
The GitHub producer derives separate jobs and artifact download patterns from this inventory;
a regression checks that they partition the actual packaged-artifact names exactly. The
matrix's unit-test target supplies the long-lived lossless reference. Other raw handoffs stay
short-lived, while validated compact caches retain their existing bounded retention.

Optional-mod plans accept an explicit `--minecraft-target`, producing plan schema 2 with the
full matrix digest. Public compatibility schema 6 uses the same target key and source-commit
separation while retaining mod-specific capture counts, exact locked applicability, normalized
review proofs and opportunistic availability. Target selection validates the entire support
matrix, including unselected lanes. The collector keeps the actual source branch for API and
ancestry checks and uses the target key only for bundle paths and artifact names.

Historical ordinary schemas 1/2 and compatibility schemas 1 through 5 remain readable with
their original version-branch identity. A shared target never falls back to a legacy cache
name. Ordinary shared-target selection currently requires exact-current-head evidence; it
rejects the old branch-continuation shortcut. Protected feature coverage and baseline reuse
must be connected before selected runs can replace a complete public snapshot.

The local tests exercise matrix-wide identity and optional-mod planning, actual producer shell
admission, raw/compact conversion, two-target site rendering and identity-tampering rejection.
Their PNGs are synthetic fixtures. They are not Minecraft image acceptance evidence.

The shared-target producer is prepared in `on-demand-e2e.yml`. Pages authenticates the complete
target handoff inventory, discovers keys from the matrix and pins one real source commit for all
collectors. It rechecks that commit before rendering and deployment. Cache replacement and
artifact rotation use target keys while run ownership and live-head checks use the real source
branch; lossless reference retention still follows the matrix's unit-test target. An incomplete
handoff, advanced source commit or unproved ordinary continuation cannot replace the site.

Visual review now partitions the authenticated complete run into separate target capsules. Each
curator independently checks the complete lane graph, recomputes its matrix-owned partition and
downloads only that target's artifacts. The existing per-capsule image, archive and frame limits
remain unchanged. Shared curation proof schema 6 binds the target, complete matrix digest and
current protected source commit; the drainer independently verifies that scope against its queue
entry before model admission. The reference comes from the matrix's lossless 1.20.1 bundle;
shared WebP caches cannot supply an AI baseline.

Queue identities include the target. A clean report or retry cooldown settles only that target,
while a confirmed defect can still stop its source generation. Exact wakes query the target's
marker names and retain numeric-ID cleanup. Each successful curator sibling can publish its own
wake after the matrix settles, including when another target failed to produce a capsule.

After a successful shared-source `master` push, Build gate requests one `workflow_dispatch`
Packaged E2E generation. The scheduler checks the live source twice and suppresses a duplicate
active or successful exact-source run. The child can reuse that successful push's staged build
bundle after its existing source, matrix and byte checks. Historical source layouts retain
their original scheduler. Shared-source PRs defer model review before the bounded direct-release
PR file reader, including PRs with more than 100 changed files.

The completed runtime generation also sends an explicit visual-review wake after its local
handoff jobs settle. Both notifier and protected consumer bind the real `master` commit; an
advanced head or a foreign dispatch cannot start curation. This supplies the token-created
workflow path without relying only on a recursive completion event.

These producer, consumer, rotation, review and scheduling paths pass local API/shell fixtures.
Optional-mod wave admission and selective healthy-baseline reuse still require migration. No
successful GitHub deployment, model review or new Minecraft visual acceptance is claimed by
these fixtures.
