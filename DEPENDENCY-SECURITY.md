# Dependency security policy

Quick Skin treats build plugins and dependencies as executable supply-chain inputs.

**Gradle's artifact verification is recorded but not enforced.** `gradle.properties` selects
`org.gradle.dependency.verification=off` deliberately. Upstream publishers occasionally replace an
artifact under an existing version coordinate: on 2026-09-02 Fabric API republished its complete
1.21.1 module set, and every build and packaged run on that branch failed until the lock was
rewritten. Because that recurs on each upstream republication, the maintainer accepted the trade
and turned enforcement off. `gradle/verification-metadata.xml` remains the recorded hash inventory
and is still consumed by the SBOM and by the packaged-runtime store; it simply no longer fails a
build. Every other layer below stays in force.

## Enforcement layers

- `gradle/wrapper/gradle-wrapper.properties` pins the Gradle distribution with SHA-256, and the
  repository also records the wrapper JAR and distribution checksums.
- `settings.gradle.kts` routes plugin groups only to their expected Fabric, Architectury, Mojang,
  Forge, NeoForge, Kikugie, Maven Central, or Gradle Plugin Portal repositories. Central and the
  Plugin Portal explicitly reject ecosystem groups owned by the specialist repositories.
- `gradle/repository-policy.gradle.kts` applies to every buildable common/loader node. It limits
  each remote repository to its owned groups, rejects unknown remote hosts, and prevents generated
  Loom namespaces from ever resolving over the network. Mojang's library host has one additional
  module-scoped exception for `org.lwjgl:lwjgl-freetype`, because Minecraft 1.21.x declares its
  Mojang-patched macOS classifier there while ordinary LWJGL artifacts remain available centrally.
  NeoForge's repository also owns the `cpw.mods` launcher components required by its userdev graph.
- `gradle/verification-metadata.xml` records SHA-256 for both artifacts and Maven/Gradle metadata.
  It covers settings and build plugins plus the resolvable common, test, Fabric, NeoForge,
  Minecraft, mappings, transform, runtime, native, and E2E classpaths for the active 1.21.3 graph.
  Gradle no longer rejects a mismatch, but `e2e/packaged_runtime.py` still resolves the exact
  SHA-256 it pins for each packaged-runtime download from this file, so keep it accurate.
- `gradle/dependency-locks/` strictly locks only `shadowBundle`, the external graph physically
  embedded in each release JAR. Locking Loom's generated configurations is deliberately avoided;
  their external inputs remain pinned by coordinate-specific verification metadata.
- `scripts/release/generate_sbom.py` converts that exact per-lane embedded graph into one
  deterministic CycloneDX document. Every production JAR is represented by its staged hashes and
  depends only on coordinates present in its strict lock; every listed library carries the exact
  upstream JAR SHA-256 from verification metadata. A missing lane, lock, component, JAR checksum,
  or manifest binding stops staging and all later publication jobs.

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
reason. Exactly four trusted-artifact rules cover those local outputs:

| Group rule | Name rule | Owner |
|---|---|---|
| `^remapped[.].+$` | any | Loom-remapped mod/API modules |
| `^loom$` | `^mappings$` | Loom layered mappings |
| `^net[.]minecraft$` | merged Minecraft/NeoForge names only | Loom merged game modules |
| `^net[.]neoforged[.]fancymodloader[.][0-9a-f]{64}$` | `^loader$` | Loom transformed NeoForge loader |

This is not permission to trust similarly named downloads. The project repository policy excludes
all four namespaces from Maven Central and excludes the transformed loader namespace from
NeoForge's remote repository; other approved remote repositories have positive group allowlists
that cannot match them. Only Loom's local file repositories can supply these coordinates. The
original Loom, Minecraft, loader, API, mappings source, and transform-tool inputs remain SHA-256
verified.

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

Start from a trusted checkout and intentionally change the declared version first. Then regenerate
the active graph and selective locks in one serialized invocation:

```bash
./gradlew --no-daemon --no-parallel \
  --write-verification-metadata sha256 --write-locks \
  :common:1.21.3:dependencies \
  :fabric:1.21.3:dependencies \
  :neoforge:1.21.3:dependencies
```

Review every metadata and lockfile diff. Confirm new coordinates are expected, compare critical
checksums with an independent publisher source when one exists, remove obsolete components, and
never add a broad trusted group to make a failure disappear. `origin="Generated by Gradle"` is an
honest bootstrap marker, not proof of publisher authenticity; repository routing and human review
remain part of the trust decision.

Run the policy regression tests and then the proportional Gradle build gate in strict mode. A
dependency-verification failure after an unrelated change is a security review event, not a cache
problem to bypass.
