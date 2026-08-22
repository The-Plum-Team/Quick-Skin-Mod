# ADR 0004: Anchor AI visual review to Minecraft 1.20.1

- Status: Superseded in part by ADR 0005
- Date: 2026-08-09
- Updated: 2026-08-22
- Scope: advisory packaged-E2E image review across release branches

ADR 0005 supersedes this record's cross-loader anchor-establishment and all-at-once propagation
rules. Its lossless semantic `capture_id` comparison, bounded queue, and selective-review mechanics
remain active for versions reviewed after the anchor is independently certified.

## Context

The packaged E2E suite captured the UI regression that blurred Quick Skin's custom panels on
Fabric and NeoForge 1.21.1, but the advisory model did not reliably report it. Two independent
coverage gaps allowed that outcome:

- the affected checkpoints had review tier `all`, while the Actions invocation selected only the
  smaller `key` subset;
- visual-review jobs from different version ports shared one concurrency group, so GitHub could
  discard an older pending review when another port arrived.

The existing prompt judged each screenshot mainly against a textual expectation. That is useful for
missing or obviously corrupt UI, but weak for a cross-version rendering regression where every
control is present and only the compositing or sharpness differs. Quick Skin already publishes
authenticated, exact-head Pages evidence for every supported release branch, and the scenario
contract gives each checkpoint a stable identity independent of filenames.

## Decision

Use Minecraft 1.20.1 as the stable semantic visual anchor for advisory AI review. Establish that
anchor by reviewing Fabric and Forge against each other; use its Fabric lane as the stable
cross-version reference.

The protected reviewer derives the anchor artifact and release branch from the protected `master`
release matrix and additionally requires the resolved version to be exactly `1.20.1` and the loader
to be `fabric`. The secretless curator selects only the newest authenticated lossless
`pages-e2e-<branch>` handoff for the exact current head of that branch, downloads it by numeric
artifact id, checks its size and digest, and validates its source and target run provenance. The
lossy WebP Pages cache remains suitable for the public gallery but is never an AI comparison
oracle. Protected retention preserves the current raw anchor for 90 days and replaces it only after
a new current-head raw handoff and compact Pages cache have both been authenticated.

For every candidate capture in every packaged lane, the curator requires exactly one reference
frame with the same contract-derived `capture_id`. Later versions use the authenticated lossless
Fabric 1.20.1 Pages frame. A 1.20.1 source run is paired across loaders in both directions: Fabric's
reference is Forge and Forge's reference is Fabric from that same authenticated run. Missing either
loader fails curation. It selects all captures, including those whose review tier is `all`. Both
images are fully decoded, must remain exactly 1920x1080, and have their hashes and pixel identities
recomputed; curation never resizes either side. Protected code derives exact decoded-RGB
fingerprints for the checkpoint's contract-authored regions. Models receive only bounded,
content-addressed, metadata-free RGB PNG pairs that were not already proven reusable.

The comparison is semantic, not strict pixel equality. Minecraft-version or loader chrome, camera
position, lighting, framing, and other expected Vanilla differences may vary. Quick Skin-owned
panels, outlines, grids, labels, textures, and controls should retain the reference's visual
sharpness and compositing behavior; only the world behind an overlay may intentionally blur.

Keep the workflow advisory. Programmatic probes and Packaged E2E remain the required gate. Curated
capsules form a seven-day durable artifact queue; a protected scheduled/dispatch drainer selects
the oldest authenticated entry and a repository-wide concurrency group serializes model access.
Replacing a pending GitHub run cannot discard the capsule, and a sanitized failure marker applies
a cooldown before retry while allowing other entries to progress.

Before any model call, exact authored-region fingerprints eliminate paired candidate/reference
matches. Exact-equivalent non-matching pairs with the same checkpoint semantics share one reviewed
representative, and an exact paired verdict cache can reuse that result across artifact labels and
loaders. Unpaired Fabric/Forge 1.20.1 anchor frames always reach the model because loader parity is
not proof of semantic correctness. The remaining representatives are triaged from deterministic
1280x720 copies in chunks of at most eight by Haiku. Only a reported concern or a decision below
high confidence is escalated to an independent Opus pass in chunks of at most four; a clean
high-confidence Haiku result is final. Perceptual similarity is model context only and never
produces a passing verdict or forces escalation.
Each call has bounded retries and pacing. Capture the model's JSON from stdout with a read-only tool
surface, validate label coverage and semantic coherence after every call, normalize the final
report with protected code, and never upload provider-authored raw output. This read-only stdout
boundary supersedes ADR 0003's narrower implementation detail that granted the model a raw-report
write tool; the rest of ADR 0003 remains active.

## Alternatives rejected

- **Continue judging only selected key frames against text.** This cannot reliably distinguish a
  present-but-blurred custom panel and knowingly omits contracted checkpoints.
- **Use strict whole-image pixel diffs.** World state, camera, lighting, Minecraft chrome, and
  renderer changes make whole-frame equality noisy and would obscure the UI regression of interest.
- **Use each candidate's immediately preceding Minecraft version.** That makes the oracle drift and
  can normalize a regression after it appears in one release.
- **Maintain committed golden screenshots.** This duplicates already authenticated Pages evidence,
  adds binary churn, and creates another approval and retention surface.
- **Make AI review a required release gate.** Model availability and judgment are not deterministic
  enough to replace the programmatic runtime contract.

## Consequences

Every contracted functional capture is still produced. Exact relevant pixels consume no model
capacity outside the unpaired 1.20.1 anchor, equivalent versions share one representative, and the
expensive verifier sees only ambiguous, suspicious, or subtly non-exact pairs. Bounded chunks reduce
context dilution and make retry cost proportional to one small chunk instead of the complete
matrix. In return, subtle cross-version
regressions have a stable lossless comparison point and omitted review tiers can no longer create
silent holes. A missing or stale 1.20.1 reference prevents the advisory review from starting but
never weakens or delays the required Build and Packaged E2E gates.

The 1.20.1 branch must continue publishing evidence for every checkpoint in the parity contract.
If the project ever retires that branch or intentionally changes the visual design, this decision
must be revisited explicitly rather than allowing the baseline to drift automatically.

When a shared contract change is fanned out, `master` can become authoritative shortly before the
1.20.1 release branch and its raw handoff finish rotating. The protected curator therefore polls
for a bounded period until both the authenticated current-head artifact and the release branch's
scenario contract match protected `master`. It still fails closed if parity never arrives, while
normal version-port fan-out no longer drops candidates during that expected rotation window.

The curator checkout and its recorded implementation identity come directly from the workflow
run's protected `github.sha`. Candidate metadata never supplies or transits the executable checkout
ref between jobs; candidate commits remain inert Git objects read only through bounded `git show`
operations.
