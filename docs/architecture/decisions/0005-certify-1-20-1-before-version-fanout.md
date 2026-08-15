# ADR 0005: Certify Minecraft 1.20.1 before version fan-out

- Status: Accepted
- Date: 2026-08-11
- Scope: semantic visual baseline and master-to-version synchronization order

## Context

ADR 0004 made Minecraft 1.20.1 the stable lossless comparison point and established it by comparing
Fabric with Forge in both directions. That detects loader divergence, but agreement is not proof of
correctness. The cape/elytra regression demonstrated the blind spot: both loaders can render the
same invalid state, and a reviewer encouraged to trust their similarity can clear the shared bug.
Every later version can then match a baseline that was never semantically valid.

The existing `master` synchronizer also launched every version port concurrently. By the time a
problem in 1.20.1 was noticed, the same shared change had already consumed Build, packaged
Minecraft, and visual-review capacity across the whole branch inventory. A comparison oracle should
be certified before dependent work starts, not while that work is already running.

AI judgment is probabilistic and provider availability is external. It must not replace the
deterministic Build and Packaged E2E checks or become a GitHub required status on an individual
release PR. It can still be used as a fail-closed scheduling decision: uncertainty should postpone
dependent version work rather than silently bless an unchecked visual baseline.

## Decision

Propagate every automatic `master` generation in two waves.

1. Derive the anchor branch and lanes from protected `release/release-matrix.json`; retain the exact
   requirement that the visual anchor is Minecraft 1.20.1 with Fabric and Forge.
2. On the initial trusted `master` push, create a synchronization port only for that anchor branch.
   Force that port's runtime policy to `full` even when its immediate diff contains only
   documentation, site, or administration paths. A later non-runtime tip may cumulatively contain
   an older runtime change whose certificate is pending or failed, so the latest push range is not
   a sound authorization boundary. An explicit manual exact target remains the operator recovery
   path.
3. Curate every 1.20.1 Fabric and Forge capture without a reference image. Require identical,
   non-empty semantic `capture_id` sets across loaders and send every frame to semantic review even
   when the bytes are identical. Judge each screenshot only against its scenario-contract
   expectation.
4. Represent the final judgment with independent `semantic_valid` and `matches_reference` fields.
   The latter is `null` for anchor certification and boolean for later paired comparison. A verdict
   is defective if semantic validity is false or, in paired mode, reference matching is false. A
   reference match can therefore never mask a semantic defect.
5. Emit a semantic certificate only when every anchor verdict is clean and only after the unique
   bot-owned anchor PR has merged as the exact current anchor head with the same Git tree as the
   tested source. Bind the certificate to the exact `master` source SHA, tested source and merged
   target SHAs, source branch and run, protected reviewer implementation, scenario-contract hash,
   proof hash, manifest hash, report hash, frame count, capture count, version, and loaders.
6. Upload that small certificate for seven days and dispatch only its immutable artifact identity.
   The consuming synchronizer reauthenticates the artifact id, name, size, digest, successful
   protected drain owner, source E2E run, strict certificate schema, contract hash, Git parents,
   equal tested/merged trees, exact current `master`, and exact current anchor head. It then discovers
   every version branch again and starts only the non-anchor wave. A certificate for an older
   `master` generation is an expected no-op.

The anchor PR still merges only from its required deterministic Build and Packaged E2E gates. Its
first E2E-completion review request is deferred; after the merge, the trusted result handler sends
another source-run-bound request and only then can semantic review mint a certificate. The semantic
certificate neither changes the required gate conclusions nor creates a protected release check.
It controls only whether the dependent cross-version wave may be scheduled. A provider outage,
ambiguous result, semantic defect, stale head, or incomplete loader product deliberately leaves
that wave unopened.

Later versions retain ADR 0004's lossless, semantic `capture_id` comparison against current-head
Fabric 1.20.1 evidence. Byte-identical paths may be skipped only in this paired comparison mode;
unpaired anchor frames are never skipped.

## Alternatives rejected

- **Keep Fabric-versus-Forge as anchor certification.** It proves parity, not correctness, and
  cannot detect a defect shared by both loaders.
- **Use only 1:1 comparison with 1.20.1.** This detects divergence but necessarily inherits every
  error already present in the reference.
- **Launch every port and inspect 1.20.1 later.** This preserves throughput at the cost of spending
  the entire matrix on a change whose oracle may already be invalid.
- **Let a documentation-only tip fan out immediately.** The immediate push diff says nothing about
  uncertified runtime commits already contained in that tip. Without a separately authenticated
  carry-forward certificate, this would let a harmless later commit release a failed generation.
- **Make AI a required status before merging every release PR.** Provider availability and model
  judgment are not deterministic enough to replace exact programmatic gates. Scheduling dependent
  work is the narrower fail-closed boundary.
- **Commit golden screenshots.** This creates another binary lifecycle and approval surface while
  still requiring semantic validation of the goldens themselves.
- **Accept any previous green anchor certificate.** A certificate must bind the exact shared source
  generation and exact current anchor head; otherwise a stale success could release unrelated code.

## Consequences

Every automatic generation now pays one serialized anchor port and semantic review before the rest
of the matrix starts. The additional latency is intentional: a bad or unavailable baseline stops
dependent work early and visibly. This includes documentation-only tips because there is no safe
immediate-diff shortcut across an uncertified cumulative history. A human can dispatch one exact
target for recovery without weakening the automatic default.

The anchor branch merges before its automatic semantic certification begins because its own required
gates remain deterministic. If review reports a defect, no automatic rollback occurs and no other
version wave is created; the anchor problem remains visible for a corrective `master` change, which
begins a fresh generation. A newer `master` push also invalidates an older pending certificate
naturally.

An authenticated certificate for the exact current generation also makes later automatic or
scheduled anchor capsules redundant. Queue admission drops those identities before model access;
it does not cache or replay their unpaired frame verdicts. Pending anchors for an older `master`
generation and evidence tied to a closed or superseded pull request are likewise ineligible.

The certificate is an authenticated handoff, not historical storage. Seven-day retention covers
normal fan-out and bounded recovery. Source reports remain bounded and provider-authored raw output
remains private. The system adds GitHub orchestration and model latency, but removes the more
expensive failure mode in which all supported versions validate against a semantically broken
reference.
