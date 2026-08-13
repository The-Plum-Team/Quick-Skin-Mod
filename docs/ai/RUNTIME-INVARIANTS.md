# Runtime architecture invariants

This file is part of the repository-wide instruction set imported by `AGENTS.md`.

## Lifecycle and threading

- `ClientRuntime` and `ServerRuntime` are the composition roots for session-scoped state. Loader
  lifecycle hooks must enter and leave through them.
- Teardown must use exact connection/session identity. A delayed disconnect must never clear a
  replacement connection for the same UUID.
- Minecraft client and server state is committed on the appropriate main thread. File reads, image
  decoding, hashing, chunk assembly, and other bulk work belong on the bounded worker executors.
- Executors, queues, prepared handoffs, and pending transfers must remain bounded and must release
  leases on success, failure, cancellation, disconnect, and shutdown.

## Networking and texture identity

- Treat all packet fields, lengths, hashes, image data, metadata, and disk cache contents as
  untrusted. Validate before allocation or state mutation.
- Historical v1 packet identifiers, codecs, and bare 40-character SHA-1 content IDs are an
  immutable compatibility contract. Strong identities use separate v2 packet identifiers; never
  reinterpret a v1 channel with a different schema.
- Protocol authority belongs to the exact player UUID plus connection object, never to a UUID
  alone. Enable v2 only after that session's hello/ack exchange; a registered legacy channel is
  explicit v1 evidence, and absent Quick Skin channels remain local-only. Reject v2 traffic before
  successful negotiation and clear only the exact profile on disconnect or all profiles at
  shutdown.
- Admit protocol hellos through the exact-connection rate budget before queuing main-thread work,
  and bound cached ACK replay to the client's finite retries for that nonce. The authenticated
  hello itself is ACK-channel evidence where Forge/Architectury channel queries are unreliable.
- Before every S2C send, require authority from the recipient's exact connection profile. A
  valid v2 hello asserts that mandatory receivers were registered before it was emitted; its
  selected profile is the channel contract, and optional receivers additionally require their
  negotiated capability. A v1 profile may be established only by an exact loader-channel probe or
  an authenticated packet on the immutable v1 channel family; before that evidence, legacy peers
  still require the concrete probe. Never borrow evidence from another UUID-only or stale session.
- Translate SHA-1 and SHA-256 aliases only after the server has verified both against the same
  canonical PNG, and select the outgoing alias for each recipient's negotiated profile. Never
  derive or trust an alias from an unverified peer-provided string.
- SHA-256 is the authoritative server cache key. If multiple authenticated SHA-256 blobs share a
  SHA-1 alias, retain every strong entry but refuse to resolve or emit that ambiguous legacy alias.
- Peer-advertised texture and chunk limits may only reduce local hard caps. Codecs, assemblers,
  pacing, and caches continue to enforce the local bounds even after negotiation.
- Keep packet codecs, chunk assemblers, rate limiters, request maps, retry state, and caches bounded.
- Large texture bytes are demand-driven: advertise appearances/hashes, and send bytes only after a
  missing client requests them. Preserve the global per-tick response and upload pacing.
- Appearance snapshots and updates use bounded, session-identity-aware pacing plus exact completion
  acknowledgement. A dropped or superseded snapshot must converge through bounded retry.
- Network texture identity is the hash of the exact canonical transmitted PNG. Local cape asset IDs
  use `HashUtil.computeAssetContentId(..., "cape")` and are deliberately domain-separated from
  skin IDs. New local skin, cape, and CPM catalog primaries are canonical `sha256-...` IDs.
- Bare SHA-1 local IDs are read-only compatibility aliases. Publish and migrate an alias only after
  the complete bounded scan proves it resolves to one SHA-256 primary; an ambiguous alias must not
  resolve, select metadata, migrate a path, or be written back to configuration.
- Client caches are keyed by both hash and texture type. The same PNG bytes may validly exist as a
  skin and a cape; never collapse typed keys back to a hash-only cache or resource path.
- Renderer-confirmed skin/cape use receives only a short, bounded working-set preference. Cache
  entry, byte, and pixel caps remain hard even when every resident texture is visible.
- Animated canonical PNGs carry exact `qsMD` metadata through `PngAnimationIdentity`. Changing frame
  timing must change the transmitted identity even when pixels are unchanged.
- Animation slots are visibility/staleness based, not arrival ordered. A visible network animation
  without a slot must use its separately bounded first-frame texture (or render nothing while that
  frame is prepared); never expose the stacked atlas as a static cape fallback.
- Server animation metadata is immutable after the first accepted value for the lifetime of the
  backing cape. Delivery-cache eviction must not delete the persistent identity binding; backing
  texture deletion must remove metadata, authority, and identity together.
- Appearance and animation convergence depends on exact acknowledgements and bounded retry. Do not
  replace it with optimistic send-once synchronization.

## Files, images, and persistence

- Use `BoundedFileReader`, `SafeImageReader`, and the established GIF preflight path. Do not add
  production `ImageIO.read`, unbounded `Files.readAllBytes`/`readString`, or decode-before-dimension
  validation.
- Resolve content-addressed paths through the containment helpers. Reject invalid content IDs,
  symbolic-link targets, and paths outside the configured root.
- Persist mutable state using a temporary file plus atomic replace where supported. Keep the
  fallback contained and clean stale temporary files during initialization.
- Local identity migration must copy/write and validate the strong-ID destination before removing
  a legacy file or preference. Preserve the legacy source whenever destination verification fails
  or an existing strong destination differs.
- Keep cache accounting weighted by bytes/pixels where relevant, not only entry count. Active server
  appearance blobs remain pinned only within the hard global pinned-byte budget; reject an
  over-budget appearance gracefully instead of weakening the cap.
- Server cache deletion belongs on the bounded cache-I/O executor. Remove a cache entry from the
  live namespace before scheduling deletion so a concurrent replacement cannot be deleted.
- Cape atlases produced by the editor or custom import paths must keep the vanilla tapered alpha
  cutout in the outer Elytra face at every supported scale and in every animation frame.
  Opaque-fill, import fallback, local/network presentation, and animation processing must share
  the same structural mask; content-addressed source bytes remain immutable when only presentation
  requires normalization.

## Optional integrations

- CPM, Ears, CustomNPCs, and 3D Skin Layers are optional. Guard their entry points and preserve the
  normal skin/cape path when an optional mod or API is absent.
- Compatibility failures must degrade locally; they must not break base mod initialization or
  dedicated-server startup.

## Public E2E evidence

- Packaged offline clients use the server's standard `OfflinePlayer:<name>` UUID. Their fresh,
  disposable Quick Skin config disables the asynchronous own-skin importer and clears persisted
  selections before mod initialization. A default-skin capture then waits until the renderer has
  held the vanilla texture selected for that exact UUID and must show the complete local model in
  a stable third-person view. Modern built-in clothing palettes must remain recognizable as one
  intact skin instead of an ambiguous isolated sleeve; never accept an account import or the
  earlier generic fallback frame as stable baseline evidence. Restore first-person explicitly
  before any later checkpoint whose contract inspects the local arm.
- A public screenshot is valid only when a successful packaged `result.json` references it and its
  recorded SHA-256 and dimensions match the PNG. Do not infer scenario, role, or step from a
  filename, and do not let sets or duplicate labels collapse two frames into false coverage.
- `e2e/scenario-contract.json` is the only authored source for scenario ids, execution profiles,
  orchestration, roles, ordered steps, mandatory assertions, captures, expectations, review tiers,
  probes, and comparisons. Capture ids and all consumer views must be derived. Add or remove a
  semantic checkpoint in the contract and its Java action only; never create another catalog or
  workflow scenario list.
- The current contract is deliberately a cross-version parity contract: every supported loader and
  version publishes every checkpoint. Do not add a version-only capture without first extending
  the contract schema and protected validator with explicit applicability rules.
- AI review must pair every later-version candidate checkpoint with the identical semantic
  `capture_id` from authenticated current-head Fabric 1.20.1 evidence. A 1.20.1 review must instead
  contain no reference, require identical complete Fabric/Forge capture sets, and send both loaders'
  frames through independent semantic review even when their content-addressed path is shared. It
  must not fall back to filenames, ordinals, review tiers, a latest-version baseline, cross-loader
  agreement, or strict whole-pixel equality. Reject missing anchor coverage, a missing later-version
  pair, contract skew, aspect-ratio drift, stale reference head, or a mixed paired/unpaired capsule
  before the credential-bearing job starts.
- Java harness reports, packaged results, raw handoffs, compact caches, and public manifests must
  carry the exact validated contract SHA-256. Reject missing, extra, reordered, hash-mismatched, or
  assertion-free steps and reject a screenshot both when a capture is missing and when a
  non-capture step emits one. Keep independent fixed probe canaries; do not generate calibration
  fixtures from the oracle values under test.
- Animated-cape evidence must use a valid cape-frame atlas, hold explicitly selected distinct
  frames through screenshot settlement, and compare the rendered cape region by a material pixel
  threshold. A changing logical frame counter alone does not prove that Minecraft sampled the
  intended UVs or that the visible cape changed.
- Slim/classic model evidence must assert both Quick Skin's requested appearance and Minecraft's
  renderer-facing model selection. Capture it in a close, pinned rear view where the 3-pixel Alex
  and 4-pixel Steve arm widths are independently inspectable, restore the prior FOV afterwards, and
  compare the bounded player region by a material threshold. Stored intent or an ambiguous distant
  silhouette alone cannot certify model geometry.
- In the sequential propagation scenario, the observer baseline must wait for the remote subject,
  show the observer's complete UUID-selected vanilla model, and hold the subject decisively behind
  the third-person camera. The subject is allowed to have completed its custom apply before the
  observer launches; startup speed must never decide whether that custom subject leaks into a
  checkpoint whose contract says it is still outside the later inspection frame.
- Adjusted-cape parity evidence must show a non-standard padded source before cropping and the final
  aligned atlas as separate semantic checkpoints. The source checkpoint must expose all four
  padding bands; the aligned checkpoint must identify coloured pixels in auxiliary cape/elytra UV
  faces as valid atlas content rather than padding. Back both views with exact source, transform,
  preview, applied-atlas, and rendered-route assertions so model interpretation cannot replace the
  deterministic contract.
- Every orchestrator invocation writes into a fresh owned workspace and promotes only its bounded
  evidence snapshot to `current`. Replacing `current` may remove only a marker-authenticated prior
  snapshot; promotion to one target is serialized across processes and retains the workspace's
  device/inode identity through every rename and rollback. An interrupted swap must roll back or
  recover without touching a replacement or sibling path. Reap every launched Minecraft process,
  including after forced termination, before closing its log handle or freezing evidence; a kill
  request alone does not prove that an in-flight log write has quiesced. Runtime installation, game
  directories, and dependency caches never enter the promoted evidence tree.
- Reusable Minecraft installations and downloads belong to `RuntimeStore/v1`, not an evidence
  directory. Recipe identity includes schema, host OS/architecture, Java major, Minecraft and
  loader versions, exact installer hash, launcher-library revision, and normalizer revision.
  Publish verified immutable trees under a recipe lock, hold an OS-backed lease continuously from
  lookup/build through materialization, and materialize a fresh mutable copy. Collection
  non-blockingly probes the paired lock, preserves live cross-process builders/leases, and reaps
  abandoned staging after a crashed owner; timestamps alone never prove liveness. Cleanup first
  renames authenticated objects to identity-bearing quarantine, then performs retryable bounded
  deletion (including read-only files), so a partial delete cannot poison the active namespace.
  Treat stale/unknown/corrupt identity as a miss or fail-closed error. Garbage
  collection is bounded housekeeping, never a correctness mechanism. Dependency hashes come from
  the strict Gradle verification metadata; first-download trust is forbidden.
- An ingested runtime tree may contain a symbolic link only when it resolves inside that tree and
  to a regular file; real Java runtimes ship such links. Store the target's bytes, refuse escaping,
  dangling, and directory links, and never publish or materialize a link. Compare containment by
  path component against the resolved root, never by string prefix, which would admit a sibling
  whose name merely extends the root. Install a leased blob under its real artifact name, because
  loaders discover only `*.jar`; the store's digest name is not an installable identity.
- The shared Java harness must reference a drifting Minecraft type as a class literal so the
  harness jar's remapper rewrites it. Resolving a Minecraft name from a string resolves only on
  Mojang-mapped loaders and fails on Fabric's intermediary runtime, so a string lookup additionally
  requires an explicit intermediary fallback.
- Public evidence is bound to source run/branch/SHA and final run/branch/SHA. Pages may select a
  bundle only when its authenticated originating target run and manifest both match the current
  release-branch head; a later protected Pages run may only roll that already validated bundle
  into cache.
- Pages repository wakes and deploys use one shared publication concurrency group so a branch wave
  cannot fan out multiple collectors. Discovery may defer on active release attestations, but must
  not preselect every artifact and repeat selection in the collector. The collector owns exact
  current-head selection; retryable GitHub API and installation-rate-limit failures use bounded
  jittered backoff and remain distinguishable from authenticated evidence absence.
- Retention is current-state, not longitudinal history. Keep exactly one durable Pages cache per
  release branch and exactly one lossless raw handoff for the matrix-derived Fabric 1.20.1 visual
  anchor. Treat raw packaged-E2E uploads, every other `pages-e2e-<branch>`, Pages fan-in, and the
  deploy artifact as short-lived handoffs. Rotation happens in a separate protected workflow after
  the owning Pages run is `completed/success`; it must recheck run provenance, the release head,
  replacement artifact identity, and every deletion ID before retiring the exact ordinary consumed
  handoff, older lossless-anchor generations, Pages-run intermediates, and caches older than the
  successful replacement. It must revalidate the retained raw anchor before every deletion. Raw
  packaged-E2E proof expires after one day; do not delete it during promotion because a concurrent
  branch attestation may still consume it. A failed E2E, deployment, validation, or rotation must
  preserve the previous usable cache and raw anchor, and a delayed rotation must never delete a
  concurrent newer generation.
- Discovery records one protected `master` SHA for the Pages run. Every collection and render job
  checks out that exact implementation revision; an advancing `master` may affect only a later run.
- Treat downloaded artifacts and their JSON as untrusted. Require the exact curated tree, exact
  schemas, complete contract and comparison products, canonical identities, one loader per branch
  loader, and one JAR digest per artifact. Reject traversal, symlinks, unknown contract entries,
  duplicate identities, non-pass lanes, stale SHAs, invalid PNGs, arbitrary nested fields, and
  size-limit violations. Protected rendering must decode and recompute screenshot/comparison pixel
  metrics before publishing. Presentation code must use escaped/text DOM APIs and local assets.
- Secret-bearing visual review has the fixed boundary `authenticate -> curate without secrets ->
  durable queue -> artifact-scoped review in a fresh capsule -> exact-id cleanup`. Curating
  must authenticate every source artifact by numeric id, size, digest, run, protected matrix row,
  complete scenario product, and one JAR;
  it must import the authenticated source commit only as inert Git objects and never check out or
  execute source-head files in the privileged default-branch workflow;
  for the 1.20.1 anchor, require complete Fabric/Forge coverage and emit only unpaired frames; for
  later versions, authenticate the exact current-head lossless 1.20.1 Pages source and its run
  provenance; fully decode, dimension-normalize, and canonically re-encode bounded RGB PNGs without
  source metadata; and emit a source/implementation/candidate/reference-artifact-bound proof. Queue
  selection must authenticate protected owners, survive pending-run replacement, accept a curated
  capsule whose later wake step failed, and cool recent failed attempts without blocking other
  sources. The review runner accepts only that immutable handoff, never skips an unpaired anchor
  frame, skips byte-identical paths only in paired comparison mode,
  exposes only bounded manifests/images to concurrent loader-grouped Sonnet triage and selective
  concurrent Opus verification with a read-only tool surface, captures each verdict from stdout,
  and validates exact labels and semantic coherence after every call. A first Opus-confirmed defect
  cancels outstanding work, emits only an explicit fail-closed partial report, publishes a sanitized
  marker bound to the exact protected automatic generation and reauthenticated reviewer
  implementation, and best-effort cancels its sibling
  drains; authenticated queue selection must skip every remaining capsule for that generation. A
  blocked report can never certify an anchor. A paired cache hit must bind exact candidate/reference pixels, expectation,
  capture and loader identity, scenario contract, release matrix, protected code, prompts, models,
  mode, and chunk policy; never cache or reuse unpaired anchor semantics. It revalidates the
  capsule after the model exits, publishes only a bounded normalized report or sanitized attempt
  marker, never uploads raw provider text, and
  deletes only a completed or terminally invalid queue artifact. A transient failure retains the
  entry for cooldown and retry. Each exact artifact ID locks its complete protected drain, from
  exact selection through cleanup, so duplicate wakes cannot overlap while unrelated capsules run
  concurrently. Exact wakes must authenticate through ID-scoped capsule lookup and exact-name
  report, cooldown, and generation-block lookup rather than multiplying a full artifact-inventory
  scan across a parallel release wave; retryable GitHub API failures use bounded backoff and never
  become an image verdict. Once a normalized report or durable block makes a source ineligible, an explicit
  GitHub installation-rate-limit response may defer input cleanup or the redundant continuation
  wake without turning the completed review red. The normalized report or block must outlive its
  durable input so deferred cleanup can never make reviewed work eligible again; artifact retention
  and the scheduled sweep own eventual housekeeping/recovery, while every other API error remains visible. A separately locked
  scheduled/manual sweep never reviews directly: it converts
  one authenticated oldest-entry selection into an exact wake. Pending-run coalescing remains safe
  because queue state is durable and a settled drain dispatches its own continuation. The curator
  may suppress a replicated non-anchor
  automation sync only after authenticating one bot-owned associated PR and its complete
  server-side file list,
  and only when every current and previous rename path belongs to the visual-review workflows,
  protected CI tests, or documentation. Missing, oversized, multiple, or runtime-bearing diffs
  remain eligible for the ordinary review path. The automatic matrix-derived 1.20.1 anchor must
  never use this suppression, irrespective of its immediate file inventory.
- Every automatic `master` synchronization releases non-anchor version branches only after the
  protected drainer produces a semantic 1.20.1 certificate. The certificate must cover identical
  non-empty Fabric and Forge capture sets, contain no reference, report every frame semantically
  valid with no defect, belong to the exact synchronization merge parent, and bind the current
  exact-tree anchor merge head. The consuming synchronizer must reauthenticate the artifact id,
  digest, size, successful protected owner run, source E2E run, contract hash, Git parents, equal
  source/target trees, current `master`, and current anchor head. Stale or invalid dispatches are
  no-ops. Immediate-diff classification must never bypass the anchor: a non-runtime tip can include
  an older uncertified runtime change. The anchor's Packaged E2E policy is therefore always `full`.
  This scheduling gate must never replace or weaken either individual port's required Build or
  Packaged E2E checks.
- Optimized gallery images are derivatives, not the source proof. Publish separate source and
  derivative hashes/dimensions, and content-address each public image URL with the bytes actually
  served. Original PNGs may exist only in `pages-e2e-*` handoffs; all are one-day transients except
  the current matrix-derived Fabric 1.20.1 visual anchor, which is retained for 90 days and rotated
  only after a validated replacement. Protected conversion must revalidate source bytes and metrics
  before atomically producing the WebP-only fan-in/cache; every later cache/render read must
  revalidate the retained source record, derivative bytes, derivative metrics, and derivative
  comparisons. AI comparison must never use the lossy derivative as its baseline.
- Pages is an advisory, atomic publication surface. Failure must preserve the previous site and
  must not weaken or replace the required Build and Packaged E2E gates.
- A version port must classify the complete original unmerged path set before AI runs. Exact
  protected paths may use only their reviewed mechanical resolution: source-preferred three-way
  merge for shared guidance/runtime documents, target retention for the release matrix, or deletion
  of a build script whose loader is absent from that target matrix. Any unknown protected conflict
  or active-loader build conflict fails closed. Recompute the partition from the original paths and
  target matrix in every downstream trust boundary; never let AI receive a protected path.
- Treat a proposed version-port patch as untrusted even after policy validation. Apply it first to
  an isolated alternate index and authenticate its complete tree id. The credentialless validator
  and credentialed writer must each rerun the protected merge controller from the exact original
  parents, compare its stable evidence byte-for-byte, import only the recomputed AI-conflict paths
  from that index, rerun protected generators, and require the final real index tree to equal both
  the isolated candidate tree and the plan tree. Never apply the full patch to the real index.
- After a version-port validation matrix settles, one protected authorization job must read its
  complete latest job inventory once and bind the exact successful target set to the workflow run,
  protected source SHA, attempt, and discovered inventory. A writer may reconstruct only when its
  own target is present in that authenticated same-run output. Never poll the inventory per target:
  fan-out polling amplifies installation-API load across a parallel release wave without adding a
  stronger trust binding; the proposal artifact is already immutable within that run.
- A successful automated version port may publish the stable Packaged E2E status only after the
  protected evaluator sees exactly one successful control job, the exact target-branch PR-anchor
  lane set, and byte-identical protected workflow, attestation workflow, composite action, contract,
  Python controller, common Java E2E harness, Gradle bootstrap/wrapper, and contract-generation
  paths. Each active loader's entire `src/e2e` bootstrap and full loader build script must also
  match the exact protected digest selected for that release branch. A green subset or a final
  convention-apply line attached to an otherwise unknown build script is never sufficient.
