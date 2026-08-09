# Releasing Quick Skin

Quick Skin publishes one immutable release identity for every branch-owned Minecraft era. The
identity is derived from the exact Minecraft versions in `release/release-matrix.json` and the
logical `mod_version`; it is not typed independently into a workflow. For this branch the identity
is `mc1.21.9-v3.0.0`, and the matrix binds it to `fabric-and-neoforge-1.21.9`.

## Preconditions

Before creating a release tag:

1. land the version, matrix, source, and workflow changes through reviewed PRs, and replace the
   current changelog heading's `unreleased` marker with its ISO release date;
2. let both required checks, `Build and verify` and `Packaged E2E gate`, pass on the exact release
   branch head;
3. confirm the working tree is clean and the branch head has not moved;
4. run the release workflow manually from that exact branch if a validation-only rehearsal is
   useful; `workflow_dispatch` never publishes;
5. derive and inspect the only accepted identity:

   ```bash
   python scripts/release/release_identity.py
   ```

The release workflow rejects a stale checkout, a tag with another name, a commit that is not the
exact configured release-branch head, and a manual run from another branch.

## Publish

Create the derived tag at the already-tested release-branch head and push only that new tag. Do not
move, reuse, or delete a release tag.

```bash
git fetch origin --tags
git switch fabric-and-neoforge-1.21.9
git pull --ff-only origin fabric-and-neoforge-1.21.9
python scripts/release/release_identity.py
git tag --sign mc1.21.9-v3.0.0
git push origin refs/tags/mc1.21.9-v3.0.0
```

Replace the example identity and branch with the exact values printed from that branch. The
protected `release` environment requires a human approval before publication jobs receive their
credentials.

The workflow then performs this fixed sequence:

1. build production and packaged-E2E JARs twice from the tagged commit and require identical
   SHA-256 bytes for every production and harness JAR;
2. record source identity plus SHA-1, SHA-256, and SHA-512 for every production artifact, then
   generate a deterministic CycloneDX SBOM from those records, the matrix, each lane's strict
   `shadowBundle` lock, and the matching SHA-256 entries in Gradle verification metadata;
3. attest the production JARs twice with the same pinned GitHub action: once for build provenance
   and once with the exact staged CycloneDX document as the SBOM predicate;
4. run all matrix-declared packaged Minecraft scenarios against those staged bytes;
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
`--verify-staged` regenerates it and requires byte-for-byte equality before any publication step.
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
gh attestation verify "Quick Skin - Fabric - 1.21.9-3.0.0.jar" \
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

`readiness` checks the live default branch and every matching release branch. It must pass before
activation so a required status check cannot strand an older branch whose base workflow does not
yet define that check. Once all workflow changes have propagated, an administrator can converge
the declared state explicitly:

```bash
python scripts/release/github_governance.py apply \
  --confirm The-Plum-Team/Quick-Skin-Mod
```

The helper enables immutable releases, creates no-bypass branch and tag rulesets, requires PRs and
strict stable checks, blocks deletion and force-pushes, and configures the human-reviewed `release`
environment. It never deletes unknown rulesets or deployment policies.

## GitHub Pages activation

The project site is a separate advisory publication and is not part of release governance or the
required branch checks. After `.github/workflows/pages.yml`, `site/`, and the evidence tooling have
reached `master` and every release branch, an administrator performs the one-time repository setup:

1. Open **Settings → Environments**, create `github-pages`, choose **Selected branches and tags**
   for deployment branches, and allow only the `master` branch. This environment rule is the
   non-bypassable boundary that prevents a manually dispatched workflow from another ref from
   receiving `pages: write`.
2. Open **Settings → Pages** and set **Build and deployment → Source** to **GitHub Actions**.
3. Run `Project site` manually from `master` after every release branch has produced an exact-head
   `pages-e2e-<branch>` artifact.

The expected project URL is <https://the-plum-team.github.io/Quick-Skin-Mod/>. Later successful
release-branch Packaged E2E runs wake the site workflow automatically. The workflow executes the
generator from protected `master`, validates all current release heads, and deploys through the
`github-pages` environment. If Pages is not enabled or any branch lacks current evidence, the
workflow fails without replacing the previously deployed site.
Successful deployments refresh one protected cache for each exact-head evidence bundle. After the
owning Pages run reaches `completed/success`, a separate protected rotation workflow validates the
new compact WebP cache, deletes only older caches for that branch, and retires by exact artifact ID
the consumed `pages-e2e-<branch>` handoff plus the successful Pages run's fan-in and deploy
artifacts. Raw E2E proof expires after one day rather than being deleted during promotion because a
concurrent branch
attestation may still be consuming it. Rotation never removes the previous fallback before a
replacement is usable, nor does it delete a concurrent newer handoff. The monthly Pages schedule
revalidates and rolls the single caches forward without rerunning packaged Minecraft; an updated
branch still requires a new exact-head E2E/attestation artifact before it can appear.
