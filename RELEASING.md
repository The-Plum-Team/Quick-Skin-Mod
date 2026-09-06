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
4. run the release workflow manually from `master` with an explicit `minecraft_target` if a
   validation-only rehearsal is useful; `workflow_dispatch` never publishes;
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
   `shadowBundle` lock, and the matching SHA-256 entries in Gradle verification metadata;
3. attest the production JARs twice with the same pinned GitHub action: once for build provenance
   and once with the exact staged CycloneDX document as the SBOM predicate;
4. run all release-profile scenarios for that target's matrix-declared runtimes against the staged bytes;
5. create or reconcile an exact draft GitHub Release without overwriting assets;
6. publish every artifact independently to Modrinth and CurseForge, reconciling the remote
   publication ID, filename, size, and bytes before and after each upload. Modrinth is reconciled
   by SHA-512 through its own API. CurseForge publishes no hash on any endpoint its author token
   can reach, so reconciliation locates the file through the unauthenticated first-party listing
   and then proves byte equality by downloading the published copy and hashing it locally against
   the staged SHA-1 and SHA-256. A same-named file that is not yet approved fails closed rather
   than racing an upload that is still settling;
7. publish the GitHub Release only after every marketplace row is verified.

The GitHub Release contains the production JARs, `artifacts.json`, `quick-skin.cdx.json`, and
deterministic `SHA256SUMS`. The artifact manifest binds the SBOM's path, size, and SHA-256;
`--target <minecraft> --verify-staged` regenerates it and requires byte-for-byte equality before any publication step.
Published releases are immutable at the repository level.

## Recovery and verification

Publication is retryable, not rollback-based. If a marketplace or GitHub API fails, rerun the
failed workflow from GitHub Actions. Exact existing uploads are accepted; missing uploads resume;
an identity or byte conflict fails closed. Never delete the tag or release, use an asset-clobber
flag, or invent a second version ID to hide a partial release. A genuine byte conflict requires a
new logical version and therefore a new immutable identity.

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
