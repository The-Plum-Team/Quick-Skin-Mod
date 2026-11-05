# Releasing Quick Skin

Quick Skin publishes through mod-base's shared release (kit v1.8.0 and later): the managed caller
`.github/workflows/mod-base-release.yml`, with Quick Skin's data in
`scripts/ci/mod-base-release.json`. The kit's [release
protocol](https://github.com/The-Plum-Team/mod-base/blob/main/docs/RELEASE-PROTOCOL.md) is the
reference for its jobs, documents and recovery. A release publishes every production JAR of the
tested generation of `master`'s head to GitHub Releases, Modrinth and CurseForge after one approval,
and compiles nothing: the bytes are those the protected shared Build built and the shared packaged
E2E ran.

Release-matrix schema 3 keeps one immutable identity per target. The complete artifact bundle has
the non-publishable identity `build-v<mod_version>`; each matrix target is published as
`mc<minecraft>-v<mod_version>` (`scripts/release/release_identity.py --target <minecraft>` derives
it), one GitHub Release per target holding that target's Fabric and Forge or NeoForge JARs and its
SBOM. One dispatch publishes every target of the version. Modrinth version numbers are
`<tag>-<loader>` (`mc1.21.10-v4.0.1-neoforge`), within Modrinth's 32 characters. See
[the shared-source command guide](docs/architecture/RELEASING-FROM-SHARED-SOURCE.md) for local
build and stage commands.

## Preconditions

1. Land the version bump (`mod_version` in `gradle.properties`) and its changelog section through a
   ready PR. The first `## ` heading of `CHANGELOG.md` must read `## <version> (<YYYY-MM-DD>)`; that
   section alone becomes the marketplace and GitHub Release notes. Tests derive the expected
   identities from the checked-in version and never hardcode it.
2. Wait until the shared `Build and verify` and `Packaged E2E gate` of that exact `master` head have
   passed (a post-merge reuse of the PR's generation counts). The release needs that head's tested
   generation within the seven days of its Build bundle.
3. Rehearse:

   ```bash
   gh workflow run mod-base-release.yml --ref master -f publish=false
   ```

   The plan job authenticates the head's tested generation, derives every tag, file name and
   marketplace text, and seals the exact files. It holds no credential and publishes nothing.

## Publish

```bash
gh workflow run mod-base-release.yml --ref master -f publish=true
```

Approve the `release` environment once when GitHub asks. Nothing may merge to `master` between the
dispatch and the approval: `release admit` refuses a moved head, and the dispatch is simply
repeated from the new head once its gates pass.

The publish job then:

1. admits the sealed plan of its own run, its environment and the protected release config;
2. creates one draft GitHub Release per target with its files and the sealed plan
   (`mod-base-release-plan.json`), and a publication ledger in a hidden block at the end of the
   draft's notes;
3. uploads every lane to Modrinth and to CurseForge, four at a time, each step holding only its own
   token; upload intent is saved in the ledger before a file is sent;
4. verifies every file by content: Modrinth by its hashes, CurseForge through its public listing
   and a download of the published copy;
5. publishes each GitHub Release whose rows are all `verified` and only then creates its tag
   `mc<minecraft>-v<mod_version>` at the released commit. The highest Minecraft target becomes the
   latest release unless a newer one already is.

CurseForge moderation can keep files pending after the job ends; the caller's hourly schedule
(minute 29) finishes those drafts without any secret and publishes them once every row verifies.
A release is finished only when every target's GitHub Release is published.

GitHub replaces spaces in asset names with periods, and since late October 2026 CurseForge turns
every run of spaces and hyphens that holds a space into one hyphen
(`Quick Skin - Fabric - 1.20.1-4.0.0.jar` is stored as `Quick-Skin-Fabric-1.20.1-4.0.0.jar`, and its
CDN answers 403 for the original name). The kit reconciles both and still checks every byte.

## Recovery

Publication is retryable, not rollback-based. To finish a partial release, dispatch with
`publish=true` again while `master` is still at the released commit: files already published are
recognized by content and never sent twice, and the drafts' ledgers say which rows are left. A
re-dispatch from a new generation of the same commit changes the plan's digest and is refused by
the existing drafts. No tag exists until its release is published, so a release abandoned before
then leaves only drafts.

The ledger row states are `unstarted`, `uploading`, `pending` and `verified`. A row left
`uploading` by a lost response stays fenced: check the provider's author dashboard before any
operator reset, because absence from a public listing cannot prove rejection. Never delete a tag or
a published release, or invent a second version to hide a partial one; a genuine byte conflict needs
a new version.

Published tags `mc*-v*` are immutable by the `Quick Skin immutable release tags` ruleset.

## History

Until kit v1.8.0, Quick Skin released through its own `release.yml` (one tag push per target, which
rebuilt and re-ran the release E2E), `release-verify.yml` (the pending-release verifier) and
`release-recovery.yml` (SBOM and ledger recovery). Quick Skin 4.0.0 (17 targets, 31 October 2026)
took about five hours that way, 105 minutes of them in 102 serial `release` approvals. Those
workflows and their scripts were retired; releases up to 4.0.0 keep their published assets,
`SHA256SUMS`, attestations (`gh attestation verify <jar> --repo The-Plum-Team/Quick-Skin-Mod`) and
ledgers. Their history is in Git.

## Actions storage

Ordinary build, diagnostics, packaged-E2E, review, synchronization, and Pages handoff artifacts are
transient and expire after one day. The pinned mod-base kit owns the Pages artifacts (ADR 0010):
source PNGs exist only in the one-day `mb-handoff--<key>--a<attempt>` handoff, which protected Pages
code validates and replaces with WebP derivatives before fan-in, plus the lossless
`mb-anchor--mc1.20.1--…` visual anchor. Each matrix key keeps one compact
`mb-cache--<key>--<coverage_sha>` for 90 days, and the separately locked rotation deletes the
previous generation only after a successful publication. A shared release keeps no bundle of its
own: its seven-day `mb-rel-plan--<run>--a<attempt>` artifact holds the sealed plan and files of one
dispatch, and the plan published with each release records every file's digest. Older
`release-<release-id>` and `release-recovery-<release-id>` bundles expire under their own 90-day
retention. Packaged E2E and every release-branch Build restore Gradle state read-only; only a
trusted Build gate push or manual run on protected `master` may publish a Gradle cache.
Existing branch-scoped release caches remain useful read-only fallbacks. A protected daily cleanup
discovers live branches directly. It deletes absent-branch caches and superseded, unambiguously
SHA-bound Gradle-home generations by exact cache ID, while retaining the newest generation per
OS/job-instance/cache-version restore family whose SHA completed the real `Build and verify` job successfully.
A family without that proof is not pruned. Unknown cache formats and non-branch refs are preserved.
Packaged runtime keeps the second cache family, the installed Forge/NeoForge server, under its
exact recipe digest rather than a commit; only a declared writer job of a `workflow_dispatch` on
protected `master` may publish one, and every other context, the optional-mod wave included,
restores read-only with no prefix fallback
([Actions cache writers](DEPENDENCY-SECURITY.md#actions-cache-writers)). It is deliberately outside that
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

## Repository governance rollout

The intended rulesets and protected release environment live in
`release/github-governance.json`. The helper is read-only by default:

```bash
python scripts/release/github_governance.py audit
python scripts/release/github_governance.py readiness
```

`readiness` pins the current default-branch commit, validates its complete schema-3 release matrix,
and checks the shared build, runtime, shared release and retirement workflows at that same commit.
It requires no historical version branches. Once those changes are present on protected source, an
administrator can converge the declared state explicitly:

```bash
python scripts/release/github_governance.py apply \
  --confirm The-Plum-Team/Quick-Skin-Mod
```

The helper enables immutable releases, creates no-bypass branch and tag rulesets, requires PRs and
strict stable checks, blocks deletion and force-pushes, and configures the human-reviewed `release`
environment. The shared release's publish job deploys from `master`, which the declared
`shared_release_workflow` admits, and waits for that human reviewer; the canonical tag policy
`mc*-v*` stays declared, though no workflow deploys from a tag any more. The schema-2 governance
configuration explicitly retires the old `*-and-*-*` environment deployment policy; the helper
shows that deletion in its plan and addresses only its independently read numeric policy ID.
Unknown or ambiguous policies stop the apply operation. Historical release-branch rulesets are
left untouched, and no branch or tag is removed. The declared state, including the `master` policy
and the retired `*-and-*-*` policy, has been applied remotely; rerun `audit` before any further
`apply`.

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
