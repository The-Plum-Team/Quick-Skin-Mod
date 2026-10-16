# Dependency security policy

Quick Skin treats build plugins and dependencies as executable supply-chain inputs.
`gradle.properties` explicitly selects Gradle's strict verification mode for every matrix target;
do not pass `--dependency-verification lenient` or `off` in development, CI, or release automation.
CI never writes verification metadata or locks: a missing or changed checksum fails the build and
is fixed by a reviewed pull request.

Upstream publishers occasionally replace an artifact under an existing version coordinate. On
2026-09-02 Fabric API republished its complete 1.21.1 module set, and enforcement was turned off
until the shared Build gate required it again. A republication is now handled as a reviewed
checksum change, never by relaxing the mode: see
[Upstream republication](#upstream-republication).

## Enforcement layers

- `gradle/wrapper/gradle-wrapper.properties` pins the Gradle distribution with SHA-256, and the
  repository also records the wrapper JAR and distribution checksums.
- `settings.gradle.kts` routes plugin groups only to their expected Fabric, Architectury, Mojang,
  Forge, NeoForge, Kikugie, Maven Central, or Gradle Plugin Portal repositories. Central and the
  Plugin Portal explicitly reject ecosystem groups owned by the specialist repositories.
- `gradle/repository-policy.gradle.kts` applies to every buildable common/loader node. It limits
  each remote repository to its owned groups, rejects unknown remote hosts, and prevents generated
  Loom namespaces from ever resolving over the network. It also pins the `fabric-loader:+` that
  Architectury Plugin adds to `architecturyTransformerClasspath` to one reviewed version and makes
  Gradle refuse any dynamic or changing selector in that classpath, so a new Fabric Loader release
  cannot break strict verification. The build declares no dynamic versions anywhere else either.
- `gradle/maven-mirror.gradle.kts` puts The Plum Team's
  [Maven mirror](https://github.com/The-Plum-Team/maven-mirror) first in every project that applies
  the policy or the Java module conventions, and `settings.gradle.kts` does the same for plugins.
  It is limited to exact Fabric, Architectury, Stonecutter, NeoForge and Forge groups, serves only
  openly licensed files whose SHA-256 matched the mods' own verification metadata, and answers 404
  for everything else, so Gradle falls back to the upstream repositories unchanged. Strict
  verification checks every file the mirror serves against `gradle/verification-metadata.xml`,
  exactly as it checks files from the upstream repositories. `-Pplum.mavenMirror=false` (or
  `ORG_GRADLE_PROJECT_plum.mavenMirror=false` in the environment) is the emergency switch: it
  leaves the mirror out of both places, so every group resolves from its upstream repositories.
- `gradle/verification-metadata.xml` verifies both artifacts and Maven/Gradle metadata with
  SHA-256. It covers settings and build plugins plus the resolvable common, test, Fabric, Forge,
  NeoForge, Minecraft, mappings, transform, runtime, native, and E2E classpaths of every target in
  `release/release-matrix.json`. `e2e/packaged_runtime.py` also resolves from this file the exact
  primary SHA-256 it pins for each packaged-runtime download.
- `gradle/dependency-locks/` strictly locks only `shadowBundle`, the external graph physically
  embedded in each release JAR. Fabric merges it into the mod JAR; Forge and NeoForge nest each
  library unmodified under `META-INF/jarjar/`, because FML loads the mod JAR as one Java module
  and a merged library would collide with another mod's copy. Locking Loom's generated
  configurations is deliberately avoided; their external inputs remain pinned by
  coordinate-specific verification metadata.
- `scripts/release/generate_sbom.py` converts that exact per-lane embedded graph into one
  deterministic CycloneDX document. Every production JAR is represented by its staged hashes and
  depends only on coordinates present in its strict lock; every listed library carries the exact
  upstream JAR SHA-256 from verification metadata. Its `serialNumber` is a UUIDv5 derived from the
  SHA-256 of the canonical document without that field, so identical inputs keep identical bytes;
  validation rejects a missing or stale serial. A missing lane, lock, component, JAR checksum,
  or manifest binding stops staging and all later publication jobs.

## Actions cache writers

An Actions cache is executable supply-chain input too: an entry written from a `master` context is
restored by every later run of the repository, pull requests included. Only a job that builds and
runs exclusively protected-`master` code may write one. A job that launches bytes built anywhere
else (a pull request's JARs, a candidate checkout, another run's artifact) restores read-only, even
when a trusted trigger such as `repository_dispatch`, `workflow_run`, `schedule` or
`pull_request_target` gives it a `master`-scoped token. Steps that execute such bytes also unset
`ACTIONS_RUNTIME_TOKEN`, `ACTIONS_CACHE_URL` and `ACTIONS_RESULTS_URL`. That is defence in depth
only: a process with the runner user's privileges can still read the token from the runner itself.
Keeping every save out of such a job stops the workflow from writing; only running those bytes in a
separate unprivileged account, as the mod-base kit's fenced Build does, would take the capability
away from them.

- Gradle home is written only by the native Build's target jobs (`build-matrix.yml`, called by
  `build-gate.yml` with `cache-writer: true`), and `scripts/ci/gradle_cache_policy.py` admits only
  a `push` or `workflow_dispatch` on protected `master`. Those jobs check out `github.sha`, compile
  it from source and launch nothing downloaded. Every other context restores read-only: pull
  requests (whose writes GitHub would scope to the pull request's own ref anyway), release
  branches, Packaged E2E's own compile, which does not pass `cache-writer`, and Release.
- The installed Forge/NeoForge server (`runtime-store-v1|…`) is the other written family. The
  packaged-runtime action saves it only when its job passes `runtime-store-writer: "true"` and
  `scripts/ci/runtime_store_cache_policy.py` approves the run: a `workflow_dispatch` on protected
  `master`. Packaged E2E and the release rehearsal are the declared writers; both launch a bundle
  their own run built from `master` (a `master` dispatch of Packaged E2E takes the push Build of the
  same SHA, never a pull request's). The optional-mod wave is a `repository_dispatch` that launches
  the original pull request's JARs whenever the merge reused that pull request, so it restores
  read-only.
- `scripts/ci/tests/test_workflow_security.py` holds every workflow to this rule: the set of jobs
  that can save a cache equals its reviewed list of trusted writers, and no job that launches a
  downloaded bundle or checks out a ref other than `github.sha` can save one.

## Offline CycloneDX validation boundary

The current SBOM validator deliberately checks the deterministic CycloneDX 1.6 subset emitted by
Quick Skin; it is not a complete implementation of the upstream JSON Schema. Release staging still
fails closed on the local matrix, manifest identity, dependency locks, verification checksums, and
the actual staged JAR and SBOM bytes, and it never downloads a schema while publishing.

Complete schema validation should be added only as an offline, reviewable input: vendor the exact
CycloneDX 1.6 schema and every referenced schema, record their reviewed SHA-256 values, pin and
verify the validator dependency like the rest of the build graph, and configure its resolver to
reject all network URLs. The release gate must fail if a vendored checksum, reference, or validation
result disagrees; fetching a newer schema at runtime is not an acceptable fallback.

## Narrow local-output exception

Loom exposes some generated outputs through file-backed Maven repositories. Their JAR byte layout
is not portable across clean worktrees, even when their external inputs and coordinates are the
same, so recording their generated SHA-256 values would make a clean build fail for the wrong
reason. Exactly five trusted-artifact rules cover those local outputs:

| Group rule | Name rule | Owner |
|---|---|---|
| `^remapped[.].+$` | any | Loom-remapped mod/API modules |
| `^loom$` | `^mappings$` | Loom layered mappings |
| `^net[.]minecraft$` | exact Loom merged Minecraft, Forge, or NeoForge name shapes, optionally ending in `-deobf` or in Loom's 10-hex project-processing hash | Loom merged game modules |
| `^net[.]minecraftforge[.][0-9a-f]{64}$` | `^fmlloader$` | Loom transformed Forge loader |
| `^net[.]neoforged[.]fancymodloader[.][0-9a-f]{64}$` | `^loader$` | Loom transformed NeoForge loader |

This is not permission to trust similarly named downloads. The project repository policy excludes
these namespaces from Maven Central, the transformed Forge namespace from Forge's remote
repository and the transformed NeoForge namespace from NeoForge's remote repository; other
approved remote repositories have positive group allowlists that cannot match them. Only Loom's
local file repositories can supply these coordinates. The original Loom, Minecraft, loader, API,
mappings source, and transform-tool inputs remain SHA-256 verified.
The optional `-deobf` suffix is required by Loom's unobfuscated NeoForge path: those merged JARs
are rebuilt locally and are intentionally nondeterministic, so recording a generated checksum
would make identical clean CI runs disagree. The trust rule remains confined to the synthetic
`net.minecraft` coordinate shape and does not cover any publisher artifact.
The optional lowercase 10-hex suffix (`-[0-9a-f]{10}`, never combined with `-deobf`) is Loom's
project-processed Forge/NeoForge game JAR, for example
`neoforge-26.1.1.15-beta-minecraft-merged-df325f964f`. Loom writes it only into the project's own
`.gradle/loom-cache/minecraftMaven` file repository (`LoomLocalMinecraft`) when it applies the
project's `META-INF/accesstransformer.cfg`. The NeoForge 26.1 and 26.1.1 overlay ships that file
into generated resources, so a clean first build still resolves the shared merged JAR while the
release workflow's `--rerun-tasks` rebuild of the same checkout resolves the processed one. It is
the same suffix shape the Fabric `minecraft-merged-<hash>` alternative already accepts, and the
`-beta` loader versions were already covered by the version part of the rule.

Gradle dependency verification does not cover the wrapper download or arbitrary downloads made
outside Gradle's dependency engine. The wrapper has its separate checksum. Packaged-E2E installer
downloads are independently pinned in `release/release-matrix.json`. Any new custom downloader must
add its own reviewed checksum before it is allowed in CI or release paths.

Optional-mod E2E JARs use a separate reviewed lock,
`e2e/mod-compatibility-contract.json`. Runtime jobs accept only its exact HTTPS CDN URL, filename,
published size, SHA-256, and SHA-512 and never call a project/version API. The explicit
`e2e/update_mod_compatibility_lock.py` maintainer command is the sole newest-version selector; its
output is code-reviewed like any other executable dependency update. Adding an optional mod also
requires an activation probe and explicit applicability or `not_applicable` rows.

## Updating dependencies

Start from a trusted checkout and intentionally change the declared version first. Then record the
graph of every target the change reaches, one serialized invocation per target, using the same
tasks as `scripts/release/build_matrix.py` so build, test, transform and harness classpaths are
all resolved (`<minecraft>` is a matrix `artifact_version`):

```bash
./gradlew --no-daemon --no-parallel \
  --write-verification-metadata sha256 -PquickskinTarget=<minecraft> \
  clean buildTargetLanes buildTargetE2EHarnesses
```

Add `--write-locks` only when a shaded `shadowBundle` dependency changed (see
[gradle/dependency-locks/README.md](gradle/dependency-locks/README.md)). Writing metadata adds
entries but never removes them, and it is a maintainer action: CI and release automation never run
it. Gradle also rewrites the whole file in its own order and may raise the schema version, which
buries the new values in a reordering diff. Commit only the added or changed entries in the
committed file's order and schema, and check that the result trusts exactly the values of the file
Gradle wrote.

Review every metadata and lockfile diff. Confirm new coordinates are expected, compare critical
checksums with an independent publisher source when one exists, remove obsolete components, and
never add a broad trusted group to make a failure disappear. `origin="Generated by Gradle"` is an
honest bootstrap marker, not proof of publisher authenticity; repository routing and human review
remain part of the trust decision.

Run the policy regression tests and then `python scripts/release/build_matrix.py --clean --target
<minecraft>` for each affected target in strict mode, from a Gradle user home that did not take part
in writing the metadata. A dependency-verification failure after an unrelated change is a security
review event, not a cache problem to bypass.

## Upstream republication

When a publisher replaces bytes under an existing coordinate, strict mode fails every target that
resolves it. Do not switch the mode to `lenient` or `off`, and do not trust the group. Instead:

1. Download the served artifact outside Gradle and compute its SHA-256.
2. Compare it with an independent publisher source, such as the repository's own `.sha256` or
   `.sha512` sidecar, and inspect that the artifact still contains only the expected content.
3. Record the new value as an `<also-trust>` child of the existing `<sha256>` entry, as the Fabric
   API 1.21.1 modules already do, in a reviewed pull request that names the evidence. The
   exception is a JAR the packaged-runtime store downloads (the aggregate `fabric-api` JAR and
   the Architectury JARs of the matrix): the store pins only the primary value, so there the new
   value becomes the primary one and the old value moves to `<also-trust>`.
