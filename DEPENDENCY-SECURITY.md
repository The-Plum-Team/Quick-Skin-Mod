# Dependency security policy

Quick Skin treats build plugins and dependencies as executable supply-chain inputs.
`gradle.properties` explicitly selects Gradle's strict verification mode; do not pass
`--dependency-verification lenient` or `off` in development, CI, or release automation.

## Enforcement layers

- `gradle/wrapper/gradle-wrapper.properties` pins the Gradle distribution with SHA-256, and the
  repository also records the wrapper JAR and distribution checksums.
- `settings.gradle.kts` routes plugin groups only to their expected Fabric, Architectury, Mojang,
  Forge, NeoForge, Kikugie, Maven Central, or Gradle Plugin Portal repositories. Central and the
  Plugin Portal explicitly reject ecosystem groups owned by the specialist repositories.
- `gradle/repository-policy.gradle.kts` applies to every buildable common/loader node. It limits
  each remote repository to its owned groups, rejects unknown remote hosts, and prevents generated
  Loom namespaces from ever resolving over the network.
- `gradle/verification-metadata.xml` verifies both artifacts and Maven/Gradle metadata with
  SHA-256. It covers settings and build plugins plus the resolvable common, test, Fabric, NeoForge,
  Minecraft, mappings, transform, runtime, native, and E2E classpaths for the active 1.21.10 graph.
- `gradle/dependency-locks/` strictly locks only `shadowBundle`, the external graph physically
  embedded in each release JAR. Locking Loom's generated configurations is deliberately avoided;
  their external inputs remain pinned by coordinate-specific verification metadata.
- `scripts/release/generate_sbom.py` converts that exact per-lane embedded graph into one
  deterministic CycloneDX document. Every production JAR is represented by its staged hashes and
  depends only on coordinates present in its strict lock; every listed library carries the exact
  upstream JAR SHA-256 from verification metadata. A missing lane, lock, component, JAR checksum,
  or manifest binding stops staging and all later publication jobs.

## Mojang-patched LWJGL classifier

Minecraft 1.21.10's version manifest selects
`org.lwjgl:lwjgl-freetype:3.3.3:natives-macos-patch`. Mojang publishes that classifier at
`libraries.minecraft.net`, while Maven Central publishes the module and ordinary classifiers but
not the patched JAR. Because Gradle does not mix artifacts for one component across repositories,
the repository policy exclusively routes the exact `org.lwjgl:lwjgl-freetype:3.3.3` component to
Mojang. The exception is deliberately version- and module-specific; it does not grant Mojang's
repository the broader `org.lwjgl` group.

The reviewed patched artifact has Mojang-manifest SHA-1
`806d869f37ce0df388a24e17aaaf5ca0894d851b` and SHA-256
`87e9c8490ebd7bd1bf8853401a0f2046494c1762cbfec59248173740e71d6878`. The SHA-256 remains enforced
by `gradle/verification-metadata.xml`; the manifest SHA-1 is recorded here only as independent
evidence that the reviewed bytes are the artifact selected by Minecraft.

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
| `^net[.]minecraft$` | exact Loom merged Minecraft, Forge, or NeoForge name shapes, optionally ending in `-deobf` | Loom merged game modules |
| `^net[.]minecraftforge[.][0-9a-f]{64}$` | `^fmlloader$` | Loom transformed Forge loader |

This is not permission to trust similarly named downloads. The project repository policy excludes
all four namespaces from Maven Central and excludes the transformed Forge namespace from Forge's
remote repository; other approved remote repositories have positive group allowlists that cannot
match them. Only Loom's local file repositories can supply these coordinates. The original Loom,
Minecraft, loader, API, mappings source, and transform-tool inputs remain SHA-256 verified.
The optional `-deobf` suffix is required by Loom's unobfuscated NeoForge path: those merged JARs
are rebuilt locally and are intentionally nondeterministic, so recording a generated checksum
would make identical clean CI runs disagree. The trust rule remains confined to the synthetic
`net.minecraft` coordinate shape and does not cover any publisher artifact.

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

Start from a trusted checkout and intentionally change the declared version first. Generate the
active graph from an empty, task-specific `GRADLE_USER_HOME`; a previously resolved Gradle graph
can avoid re-reading POM or module metadata and hide checksums that a clean CI runner will require.
Then regenerate the metadata and selective locks in one serialized invocation:

```bash
./gradlew --no-daemon --no-parallel \
  --write-verification-metadata sha256 --write-locks \
  :common:1.21.10:dependencies \
  :fabric:1.21.10:dependencies \
  :neoforge:1.21.10:dependencies
```

Review every metadata and lockfile diff. Confirm new coordinates are expected, compare critical
checksums with an independent publisher source when one exists, remove obsolete components, and
never add a broad trusted group to make a failure disappear. `origin="Generated by Gradle"` is an
honest bootstrap marker, not proof of publisher authenticity; repository routing and human review
remain part of the trust decision.

Run the policy regression tests and then the proportional Gradle build gate in strict mode. Finally,
configure the build once from a second empty `GRADLE_USER_HOME` without
`--write-verification-metadata`; this proves the reviewed file is sufficient on its own. A
dependency-verification failure after an unrelated change is a security review event, not a cache
problem to bypass.
