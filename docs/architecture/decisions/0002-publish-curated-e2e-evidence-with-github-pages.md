# ADR 0002: Publish curated E2E evidence with GitHub Pages

- Status: Accepted — active
- Date: 2026-08-02
- Amended by: ADR 0004 on 2026-08-10 for the single lossless AI anchor
- Scope: project landing page and cross-version packaged-E2E evidence

## Context

Quick Skin already exercises the packaged production JARs in real Minecraft clients and records
screenshots. Raw Actions artifacts are useful for diagnosis, but they expire, require several
downloads, and do not align the same interaction across Minecraft versions and loaders. The
project also needs one discoverable location for Modrinth, CurseForge, source, and verified visual
evidence.

The publication surface must remain advisory. A presentation failure must not turn a passing
release into a failure, and presentation code must never be able to bless fabricated or stale
evidence. The version inventory must continue to come from release branches and each branch's
matrix rather than a second hand-maintained list.

## Evidence

The external systems establish the general pattern:

- GitHub Pages supports a custom Actions workflow that uploads and deploys a static site artifact.
- Playwright emits a self-contained HTML report that can be served as a web page.
- BackstopJS exposes screenshot comparisons through a browser visual report.
- Storybook documents building UI documentation as a static web application for publication.
- The WAI-ARIA Authoring Practices define the roles, state, and keyboard behavior for version tabs.

These sources support a static Pages site and browser-based visual report. They do not prescribe
Quick Skin's exact-head, provenance, or fail-closed policy; those are project-specific controls.

The validated prototype rendered three release branches, 216 catalogued captures, and 36 semantic
checkpoints into a 13,373,250-byte site. This is well below GitHub Pages' documented 1 GB published
site limit. The same semantic checkpoint could be displayed across versions and loaders without
inferring identity from screenshot filenames.

## Decision

Publish one static project site through a custom GitHub Pages workflow:

1. A successful release-branch Packaged E2E run, or a trusted exact-tree attestation of that run,
   may produce one short-lived curated `pages-e2e-<branch>` handoff. The bundle contains only
   catalogued PNGs and an exact-schema manifest; it never includes logs, crash reports, runtime
   directories, or AI-authored HTML.
2. Screenshot identity is the semantic tuple of artifact, scenario, client role, and step. The
   protected catalog supplies ordering, titles, expectations, and comparison coverage.
3. The Pages workflow discovers all release branches from GitHub and requires valid evidence for
   every exact current branch head. Downloaded artifacts and manifests are hostile input: validate
   their run provenance, branch and commit identity, tree shape, schemas, paths, hashes, decoded
   pixels, metrics, comparisons, and size bounds. Before `collected-pages-*` fan-in, protected code
   atomically converts a validated raw bundle to a WebP-only schema that retains separate source
   and derivative identities, hashes, dimensions, pixel metrics, and comparisons.
4. Discovery pins one protected `master` SHA for the complete Pages run. Only that exact code may
   validate or render the downloaded evidence. Deploy the complete set of versions atomically so a
   partial or stale site never replaces the previous successful deployment.
5. Restrict the `github-pages` deployment environment to `master`. Keep `pages: write` and
   `id-token: write` only in the deployment job, which consumes the already rendered immutable
   Pages artifact and checks out no repository code.
6. After a successful deployment, retain exactly one validated compact exact-head cache per release
   branch and refresh unchanged evidence monthly. Rotation runs only after the owning Pages workflow
   is `completed/success`: validate the replacement and current release head again, then delete caches
   older than that replacement and the ordinary exact handoff it consumed. For the matrix-derived
   Fabric 1.20.1 AI anchor, retain the exact current raw handoff and retire only older validated raw
   generations. The same authenticated promotion retires by immutable artifact ID the successful
   Pages run's fan-in and deploy artifacts. Raw packaged-E2E proof instead expires after one day so
   a concurrent branch attestation can finish consuming it. Preserve a concurrent newer handoff,
   the current raw anchor, and the previous cache whenever E2E, deployment, validation, or rotation
   fails. Separately, a protected scheduled sweep discovers
   live branches and deletes by exact cache ID only Actions caches scoped to branch refs that no
   longer exist. It does not treat non-branch refs or branches with active runs as orphans. A changed
   release branch still requires new exact-head evidence; Pages never relaunches Minecraft merely
   to refresh presentation.
7. Keep original PNGs only in `pages-e2e-*` handoffs. Ordinary handoffs expire after one day; the
   current matrix-derived Fabric 1.20.1 handoff is a 90-day lossless AI anchor. Publish bounded WebP
   derivatives for browsing while recording and revalidating source-PNG and published-image hashes,
   dimensions, and pixel contracts separately. `collected-pages-*` and the 90-day `pages-cache-*`
   contain no original PNG bytes. Content-address public image URLs by the bytes actually served.
8. Provide a landing/link page and a gallery with an all-versions view, one accessible tab per
   discovered version, filters, and semantic cross-version/loader comparison. Unsupported loaders
   are explicit `not applicable` cells, not missing-test claims.

Pages deployment and optional AI visual review remain advisory. They do not replace or weaken the
required Build and Packaged E2E gates.

## Alternatives rejected

- **Link only to raw Actions artifacts.** This preserves source evidence but is temporary and makes
  multi-version review slow and difficult to discover.
- **Commit screenshots or generated HTML to `master` or `gh-pages`.** This adds binary churn and a
  second mutable publication branch without improving evidence identity.
- **Rerun Minecraft in the Pages workflow.** That is expensive and could publish evidence for a
  different tree than the release gate already tested.
- **Adopt a hosted visual-regression SaaS or dynamic service.** It adds credentials, cost, retention
  policy, and an external trust boundary for a report that can be generated statically.
- **Publish an unmodified generic test report.** Playwright and BackstopJS establish useful report
  patterns, but neither understands Quick Skin's release branches, loader applicability, packaged
  JAR provenance, or semantic Minecraft checkpoints without project-specific validation.

## Consequences

The repository owns a small static renderer plus a strict public-evidence validator. That increases
workflow and schema maintenance, but keeps hosting dependency-free and makes the published claim
auditable. New or renamed screenshots must update the runtime contract, visual catalog, validators,
tests, and every affected release branch together.

Publication fails closed when any release head lacks current evidence, while the previous atomic
deployment remains available. The site does not create a second supported-version list. Artifact
storage is bounded to one compact durable gallery generation per release branch plus one lossless
current AI anchor; ordinary handoffs, fan-in, and deploy artifacts overlap only until their
successful deployment has been promoted. Raw E2E proof may overlap for its one-day consumer-safety
window. Ordinary Actions uploads expire after one day.
GitHub Pages limits and artifact retention must still be monitored as the number of versions or
captures grows.

Repository activation is deliberately separate from accepting this decision: the implementation
must first reach `master` and the release branches, after which an administrator enables GitHub
Actions as the Pages source and restricts the `github-pages` environment to `master`.

## Reconsideration thresholds

Reopen this decision if any of the following becomes true:

1. The rendered site approaches GitHub Pages' size, deployment-time, or bandwidth limits.
2. Evidence retention or longitudinal history becomes a product requirement rather than a view of
   current supported release heads.
3. Review requires interactive golden-image approval or richer pixel-diff tooling that cannot be
   represented safely in a static report.
4. Operating the custom validator and renderer costs more than an evaluated external service while
   preserving the same provenance and access controls.

## External evidence

- [Using custom workflows with GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages)
- [Configuring a publishing source for GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)
- [GitHub Pages limits](https://docs.github.com/en/pages/getting-started-with-github-pages/github-pages-limits)
- [GitHub Actions artifacts REST API](https://docs.github.com/en/rest/actions/artifacts)
- [Playwright HTML reporter](https://playwright.dev/docs/test-reporters)
- [BackstopJS visual regression reports](https://github.com/garris/backstopjs)
- [Publishing Storybook as a static web application](https://storybook.js.org/docs/9/sharing/publish-storybook)
- [WAI-ARIA tabs pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/)
