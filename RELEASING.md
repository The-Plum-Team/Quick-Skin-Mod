# Releasing Quick Skin

The shared-source rework uses release-matrix schema 3. The complete artifact bundle has the
non-publishable identity `build-v<mod_version>`. Passing `--target <minecraft>` to
`scripts/release/release_identity.py` derives an independent `mc<minecraft>-v<mod_version>`
publication identity from the same `master` source revision. The target set comes from the matrix's
artifact rows. Selecting a target never rewrites that authoritative matrix.

The target-specific release workflow and declared governance now use shared source. See
[the shared-source command guide](docs/architecture/RELEASING-FROM-SHARED-SOURCE.md) for local
build/stage/rebuild examples. Protected selective review and Pages use the shared contracts;
live rollout and final GitHub image acceptance are tracked in the
[migration plan](docs/architecture/MODULAR-REWORK.md). The identity
validator rejects attempts to publish the aggregate bundle.

## Preconditions

Before creating a release tag:

1. land the version, matrix, source, and workflow changes through reviewed PRs, and replace the
   current changelog heading's `unreleased` marker with its ISO release date;
2. let both required checks, `Build and verify` and `Packaged E2E gate`, pass on the exact release
   source branch (`master`) head;
3. confirm the working tree is clean and the branch head has not moved;
4. run the release workflow manually from `master` for each intended `minecraft_target` and
   require a successful validation-only rehearsal, including the offline publication/recovery
   simulation against its staged bundle; a manual run of `release.yml` never publishes;
5. derive and inspect the only accepted identity:

   ```bash
   python scripts/release/release_identity.py --target 1.20.1
   ```

The release workflow rejects a stale checkout, a tag with another name, a commit that is not the
exact configured release-branch head, and a manual run from another branch.

## Publish

Create the derived target tag at the already-tested `master` head and push only that new tag. Do not
move, reuse, or delete a release tag.

```bash
git fetch origin --tags
git switch master
git pull --ff-only origin master
python scripts/release/release_identity.py --target 1.20.1
git tag --sign mc1.20.1-v3.0.0
git push origin refs/tags/mc1.20.1-v3.0.0
```

Replace the example Minecraft target and identity with the exact values derived from the matrix. The
protected `release` environment requires a human approval before publication jobs receive their
credentials.

The workflow then performs this fixed sequence:

1. build the selected target's production and packaged-E2E JARs twice from the tagged commit and require identical
   SHA-256 bytes for every production and harness JAR;
2. record source identity plus SHA-1, SHA-256, and SHA-512 for every production artifact, then
   generate a deterministic CycloneDX SBOM from those records, the matrix, each lane's strict
   `shadowBundle` lock, and the matching SHA-256 entries in Gradle verification metadata.
   The `serialNumber` required by the attestation action is derived from the document content, so
   identical inputs retain identical SBOM bytes;
3. rehearse the publication protocol offline with those exact staged bytes: validate the SBOM
   attestation contract, recover an interrupted GitHub asset upload with normalized filenames,
   fence an accepted but unindexed marketplace upload after a simulated restart, and finalize
   only after every simulated file is approved and verified;
4. attest the production JARs twice with the same pinned GitHub action: once for build provenance
   and once with the exact staged CycloneDX document as the SBOM predicate;
5. run all release-profile scenarios for that target's matrix-declared runtimes against the staged bytes;
6. create or reconcile an exact draft GitHub Release without overwriting assets and persist its
   publication ledger in a hidden comment at the end of the release notes;
7. publish every artifact independently to Modrinth and CurseForge, reconciling the remote
   publication ID, filename, size, and bytes before and after each upload. Modrinth is reconciled
   by SHA-512 through its own API. CurseForge publishes no hash on any endpoint its author token
   can reach, so reconciliation locates the file through the unauthenticated first-party listing
   and then proves byte equality by downloading the published copy and hashing it locally against
   the staged SHA-1 and SHA-256. Persist upload intent before sending the file, then persist
   acceptance before checking visibility once. An accepted or unapproved file remains pending
   and never authorizes another upload;
8. publish the GitHub Release only after every marketplace row is verified. Otherwise finish
   the upload workflow with an explicit pending summary and leave the release as a draft.

Preparation uses four matrix-derived build slots on isolated hosted runners, plus at most four
Fabric and four Forge/NeoForge runtime jobs. Gradle remains serial inside each checkout.
Different targets can prepare concurrently. Only the short jobs that stage assets, write the
publication ledger, upload marketplace files or finalize GitHub share `release-publish`.
Workflow and job queues use `queue: max` with cancellation disabled; GitHub retains up to 100
pending entries per group. This is a bounded queue, not an unlimited capacity guarantee.

The GitHub Release contains the production JARs, `artifacts.json`, `quick-skin.cdx.json`, and
deterministic `SHA256SUMS`. The artifact manifest binds the SBOM's path, size, and SHA-256;
`--target <minecraft> --verify-staged` regenerates it and requires byte-for-byte equality before any publication step.
Published releases are immutable at the repository level.

GitHub replaces spaces in uploaded asset filenames with periods. GitHub reconciliation maps
those remote names to the original manifest records and still downloads and verifies every
asset's SHA-256. The manifest and `SHA256SUMS` retain the original filenames used by the staged
bundle and marketplaces; restore those names when checking GitHub downloads with `SHA256SUMS`.
Names that collide after GitHub's normalization are rejected before creating or uploading assets.

## Recovery and verification

Publication is retryable, not rollback-based. If a marketplace or GitHub API fails, rerun the
failed jobs from GitHub Actions. Exact existing uploads are accepted; unstarted uploads resume;
an identity or byte conflict fails closed. Never delete the tag or release, use an asset-clobber
flag, or invent a second version ID to hide a partial release. A genuine byte conflict requires a
new logical version and therefore a new immutable identity.

`Verify pending releases` wakes after a release/recovery run and on a five-minute schedule
(GitHub may delay scheduled runs). Its read-only probe checks each pending draft once using
public marketplace APIs, without upload secrets or a publication lock. It authenticates the
original producer, protected source history, immutable tag, successful preparation and release
E2E jobs, archive ID/digest, manifest and every staged file. Old source metadata is read as inert
data by the current protected implementation. A failed upload job does not invalidate already
successful preparation; reused jobs from failed-jobs-only reruns retain their exact run identity.

When every file is verified, final publication still requires the protected `release` environment's
human approval. The final job repeats authentication and byte checks under `release-publish`.
One active verifier avoids duplicate approval requests; a human approval can hold that verifier's
queue, while pending moderation itself ends the probe without waiting. The original bundle must
remain available within its 90-day retention window. An expired or conflicting bundle fails closed.

The durable row states are `unstarted`, `uploading`, `pending`, and `verified`. A green upload
workflow with pending rows means the upload phase finished, not that the release is public.
Both that workflow and the verifier write the individual states to their job summaries. The
final immutable release retains the ledger, including each verified marketplace file ID.

If a runner loses the upload response, `uploading` deliberately remains fenced even if the
public API lists no file. Check the provider's author dashboard and original upload logs before
any operator recovery; absence from the public listing cannot prove rejection. Only after
confirming that the provider never accepted the file may an operator restore that single row
to `unstarted`, under the publication lock. Never erase the ledger or reset accepted rows.
Ordinary pending approval needs no rerun or reset. A manual probe is available with:

```bash
gh workflow run release-verify.yml --ref master -f release_tag=mc26.1-v3.0.0
```

The offline rehearsal runs in the release-policy tests before tagging and on each actual staged
target bundle before attestation. It checks our publication contracts and recovery behavior;
it cannot predict live provider outages or moderation time. Re-run it locally on an existing
verified bundle with `python3 scripts/release/rehearse_publication.py --stage build/release`.

### Recover an unpublished release with a missing SBOM serial number

When tag publication failed because its CycloneDX SBOM omitted `serialNumber`, retain the tag,
mod version and exact production/harness bytes. Merge the generator repair through the required
PR gates. The separate `release-recovery.yml` workflow accepts an existing canonical tag plus the
numeric run and artifact IDs of its successful validation-only release rehearsal:

```bash
gh workflow run release-recovery.yml --ref master \
  -f release_tag=mc26.1-v3.0.0 \
  -f source_run_id=34712044977 \
  -f source_artifact_id=10303697152
```

The recovery authenticates the exact protected implementation and immutable tag, the tagged source's
required gates, every rehearsal release-runtime job, and the archive's owner, size and digest.
It verifies the original tag-push provenance for each production JAR. Every tracked input outside
the explicit metadata-repair allowlist must still equal the tagged source. It accepts only the
addition of the deterministic `serialNumber`; changes to dependency records, JARs, harnesses,
version, matrix, game code or build inputs fail closed. No tagged code executes in this recovery.

The corrected bundle retains the original source SHA and receives a new SBOM attestation; the
original production provenance and packaged E2E evidence remain authoritative for its unchanged
JARs. Publication jobs independently reauthenticate the tag and protected implementation, require
the exact prepared manifest hash, and reverify every staged artifact before the ordinary immutable
GitHub/marketplace reconciliation. Recovery uses the same durable publication ledger and short
`release-publish` write lock; pending approval is verified by `release-verify.yml`.

The declared `release` environment permits protected `master` for this explicit recovery as well
as canonical tag runs, with the same required human reviewer. Apply the reviewed governance change
before dispatching recovery. Ordinary `release.yml` dispatches remain validation-only. Recovery
cannot move a tag, create another version identity, replace an existing conflicting asset, or
claim that its current orchestration commit produced the original JARs. An advancing `master`,
unavailable source archive, changed non-repair input or byte conflict stops recovery.

Actions storage follows the same recovery boundary. Ordinary build, diagnostics, packaged-E2E,
review, publication-receipt, synchronization, and Pages handoff artifacts are transient and expire
after one day. Source PNGs exist only in the `pages-e2e-*` handoff; protected Pages code validates
and replaces them with WebP derivatives before fan-in. Each branch's single compact SHA-bound Pages
cache is retained for 90 days, with successful rotation deleting the previous generation. The
immutable `release-<release-id>` bundle is the other 90-day exception so the same verified bytes
survive protected-environment approvals and can resume an interrupted GitHub Release or marketplace
publication. Release, Packaged E2E, and every release-branch Build restore Gradle state read-only;
only a trusted Build gate push or manual run on protected `master` may publish a Gradle cache.
Existing branch-scoped release caches remain useful read-only fallbacks. A protected daily cleanup
discovers live branches directly. It deletes absent-branch caches and superseded, unambiguously
SHA-bound Gradle-home generations by exact cache ID, while retaining the newest generation per
OS/job/cache-version restore family whose SHA completed the real `Build and verify` job successfully.
A family without that proof is not pruned. Unknown cache formats and non-branch refs are preserved.
Packaged runtime keeps the second cache family, the installed Forge/NeoForge server, under its
exact recipe digest rather than a commit; only a protected `master` dispatch may publish one, and
every other context restores read-only with no prefix fallback. It is deliberately outside that
cleanup: a recipe that leaves the release matrix is never restored again and expires under the
platform's unused-entry policy, and teaching the pruner to read the matrix would give it the
version inventory it must never infer.
Because any workflow may restore the default branch and pull-request workflows may restore their
base branch, any potentially cache-consuming active run preserves the complete cache inventory for
that invocation. The protected cleanup run itself is ignored because it does not configure Gradle;
unknown workflow paths remain protective.

The automatic cache-pruning boundary is deliberately mechanical:

- The only live-branch key shape it recognizes is
  `gradle-home-v<positive>|<platform>|<job>[<32 lowercase hex>]-<40 lowercase hex>`.
  The restore family is the protocol, platform, job prefix, and GitHub cache `version` (the paths and
  compression compatibility hash); content-addressed dependency, transform, wrapper, DSL, and any
  future/unknown cache keys are not live-branch deletion targets.
- A SHA is successful only when the exact `build-gate.yml` query returns a completed successful
  `push` or `workflow_dispatch` run for the same branch, SHA, and repository and that run contains
  the completed successful `Build and verify` job. Pull-request, other read-only, and successful
  attestation-only runs are not sufficient.
- Initial discovery and every exact-cache lookup are paginated. An invocation accepts at most 100
  pages per inventory, 1,000 active runs per status filter, 100 successful Build runs per exact SHA,
  and 100 jobs per run; duplicates, malformed payloads, larger searches, or API errors abort the
  invocation before it can continue deleting.
- Before planning, before bounding the apply batch, and immediately before each deletion, the
  repository-wide active-run state is checked. The candidate, branch existence, and exact compatible
  protected replacement are also revalidated per ID. Any mismatch preserves the candidate. Deletes
  use only the immutable numeric cache ID.
- The protected workflow applies at most 75 IDs and 10 GiB per invocation, serially, with one second
  between deletes. The script remains dry-run unless `--apply` is explicit.

Downloaders can verify checksums with `SHA256SUMS`. Maintainers can additionally verify GitHub's
provenance for a downloaded JAR:

```bash
gh attestation verify "Quick Skin - Fabric - 1.20.1-3.0.0.jar" \
  --repo The-Plum-Team/Quick-Skin-Mod
```

Publication receipts and packaged-runtime diagnostics remain attached to the workflow run for one
day. Durable audit comes from the immutable GitHub Release assets, checksums, attestations, and the
marketplaces themselves; transient Actions output is not the system of record.

## Repository governance rollout

The intended rulesets and protected release environment live in
`release/github-governance.json`. The helper is read-only by default:

```bash
python scripts/release/github_governance.py audit
python scripts/release/github_governance.py readiness
```

`readiness` pins the current default-branch commit, validates its complete schema-3 release matrix,
and checks the shared build, runtime, release and retirement workflows at that same commit. It
requires no historical version branches. Once those changes are present on protected source, an
administrator can converge the declared state explicitly:

```bash
python scripts/release/github_governance.py apply \
  --confirm The-Plum-Team/Quick-Skin-Mod
```

The helper enables immutable releases, creates no-bypass branch and tag rulesets, requires PRs and
strict stable checks, blocks deletion and force-pushes, and configures the human-reviewed `release`
environment. Shared publication accepts canonical release tags only. The schema-2 governance
configuration explicitly retires the old `*-and-*-*` environment deployment policy; the helper
shows that deletion in its plan and addresses only its independently read numeric policy ID.
Unknown or ambiguous policies stop the apply operation. Historical release-branch rulesets are
left untouched, and no branch or tag is removed. This migration has only been exercised with local
API fixtures; no remote governance changes have been applied.

## GitHub Pages activation

Pages is a separate advisory publication. Its shared-source fan-in, retained feature evidence and
selective-review consumers remain under migration. Existing deployed evidence stays available;
the new pipeline must authenticate the shared source commit, complete matrix and exact selected
coverage before replacing it. The older branch-based fan-in is documented in
[the historical delivery guide](VERSION-BRANCHES.md).

The repository-level deployment boundary remains the `github-pages` environment, limited to
`master`, with GitHub Pages configured to use GitHub Actions. Preparing that environment does not
certify new evidence. Final activation and a verified deployment are separate from the local
modular rework; follow its acceptance record before using the new pipeline.
