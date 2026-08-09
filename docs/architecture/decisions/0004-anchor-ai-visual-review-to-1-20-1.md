# ADR 0004: Anchor AI visual review to Minecraft 1.20.1

- Status: Accepted
- Date: 2026-08-09
- Scope: advisory packaged-E2E image review across release branches

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

Use Fabric 1.20.1 as the stable semantic visual anchor for advisory AI review.

The protected reviewer derives the anchor artifact and release branch from the protected `master`
release matrix and additionally requires the resolved version to be exactly `1.20.1` and the loader
to be `fabric`. The secretless curator selects the newest authenticated Pages handoff or compact
cache for the exact current head of that branch, downloads it by numeric artifact id, checks its
size and digest, validates its source and target run provenance, and converts it to the validated
compact evidence schema when necessary.

For every candidate capture in every packaged lane, the curator requires exactly one reference
frame with the same contract-derived `capture_id`. It selects all captures, including those whose
review tier is `all`. Both images are fully decoded, their hashes and pixel identities are
recomputed, and the candidate is normalized to the reference dimensions only when the aspect ratio
matches. The model receives only content-addressed metadata-free RGB PNG pairs and must inspect both
sides of every manifest entry.

The comparison is semantic, not strict pixel equality. Minecraft-version or loader chrome, camera
position, lighting, framing, and other expected Vanilla differences may vary. Quick Skin-owned
panels, outlines, grids, labels, textures, and controls should retain the reference's visual
sharpness and compositing behavior; only the world behind an overlay may intentionally blur.

Keep the workflow advisory. Programmatic probes and Packaged E2E remain the required gate. Scope
model concurrency by authenticated source run so one version port cannot cancel another pending
review. Capture the model's JSON from stdout with a read-only tool surface, validate and normalize
it with protected code, and never upload the raw response. This read-only stdout boundary
supersedes ADR 0003's narrower implementation detail that granted the model a raw-report write
tool; the rest of ADR 0003 remains active.

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

Each visual-review run processes more images and consumes more model capacity because every capture
is paired. In return, subtle cross-version regressions have a stable comparison point and omitted
review tiers can no longer create silent holes. A missing or stale 1.20.1 reference prevents the
advisory review from starting but never weakens or delays the required Build and Packaged E2E gates.

The 1.20.1 branch must continue publishing evidence for every checkpoint in the parity contract.
If the project ever retires that branch or intentionally changes the visual design, this decision
must be revisited explicitly rather than allowing the baseline to drift automatically.
