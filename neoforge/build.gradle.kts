import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.Sync

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedLegacyJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val consolidatedLegacyResources = layout.buildDirectory.dir("generated/consolidated/main/resources")
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

val matrixState = gradle.extensions.extraProperties
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val releaseArtifact = releaseArtifacts
    .single { it["artifact_node"] == "neoforge-$minecraftVersion" }
val isNoRemap = releaseArtifact["no_remap"] as? Boolean
    ?: error("Release artifact neoforge-$minecraftVersion is missing boolean no_remap")

apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String
extensions.extraProperties["quickSkinFmlMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/fml-metadata-conventions.gradle.kts"))

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - NeoForge - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    platformSetupLoomIde()
    neoForge()
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
}
apply(from = rootProject.file("gradle/repository-policy.gradle.kts"))

val sourceOverlays = releaseMatrix["source_overlays"] as Map<*, *>
val neoForgeOverlayRoutes = sourceOverlays["neoforge"] as Map<*, *>
val declaredLegacyDirectories = neoForgeOverlayRoutes.values.mapTo(linkedSetOf()) { it.toString() }
val actualLegacyDirectories = rootProject.file("neoforge/src").listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.name.startsWith("legacy") }
    .mapTo(linkedSetOf()) { it.name }
check(actualLegacyDirectories == declaredLegacyDirectories) {
    "NeoForge legacy source roots disagree with routing: declared=$declaredLegacyDirectories, " +
        "actual=$actualLegacyDirectories"
}

val overlayDirectory = neoForgeOverlayRoutes[minecraftVersion]?.toString()
if (overlayDirectory != null) {
    val legacyJavaRoot = rootProject.file("neoforge/src/$overlayDirectory/java")
    check(legacyJavaRoot.isDirectory) { "Missing NeoForge overlay Java root: $legacyJavaRoot" }
    val legacyResourcesRoot = legacyJavaRoot.parentFile.resolve("resources")
    val legacyOverrides = fileTree(legacyJavaRoot) {
        include("**/*.java")
    }.files.mapTo(linkedSetOf()) {
        it.relativeTo(legacyJavaRoot).invariantSeparatorsPath
    }
    val resourceOverrides = if (legacyResourcesRoot.isDirectory) {
        fileTree(legacyResourcesRoot).files.mapTo(linkedSetOf()) {
            it.relativeTo(legacyResourcesRoot).invariantSeparatorsPath
        }
    } else {
        emptySet()
    }
    val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(generatedStonecutterJava) {
            exclude(legacyOverrides)
        }
        from(legacyJavaRoot)
        into(consolidatedLegacyJava)
    }
    val prepareConsolidatedResources = tasks.register<Sync>("prepareConsolidatedResources") {
        from(rootProject.file("neoforge/src/main/resources")) {
            exclude(resourceOverrides)
        }
        if (legacyResourcesRoot.isDirectory) from(legacyResourcesRoot)
        into(consolidatedLegacyResources)
    }

    sourceSets {
        main {
            java.setSrcDirs(listOf(consolidatedLegacyJava))
            resources.setSrcDirs(listOf(consolidatedLegacyResources))
        }
    }

    tasks.named("compileJava") {
        dependsOn(prepareConsolidatedJava)
    }
    tasks.matching { it.name == "sourcesJar" }.configureEach {
        dependsOn(prepareConsolidatedJava, prepareConsolidatedResources)
    }
    tasks.named("processResources") {
        dependsOn(prepareConsolidatedResources)
    }
}

// The harness is a separate client-only test mod. It compiles against this node's named output but
// packages only `src/e2e`; the exact production Quick Skin JAR is installed independently at runtime.
val mainSourceSet = sourceSets.named("main").get()
val e2eSourceSet = sourceSets.create("e2e") {
    java.setSrcDirs(
        listOf(
            rootProject.file("neoforge/src/e2e/java"),
            rootProject.file("common/src/e2e/java"),
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("neoforge/src/e2e/resources"),
            rootProject.file("common/src/e2e/resources"),
        )
    )
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath
}

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    findByName("developmentNeoForge")?.extendsFrom(configurations["common"])
}

// Only the dependency physically shaded into the release JAR is version-locked. Loom's generated
// Minecraft, mappings, remap, and development configurations remain checksum-verified without
// brittle lock state tied to generated Stonecutter project directories.
dependencyLocking {
    lockMode = LockMode.STRICT
    lockFile = rootProject.file("gradle/dependency-locks/neoforge-$minecraftVersion.lockfile")
}
configurations.named("shadowBundle") {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    if (!isNoRemap) {
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    "neoForge"("net.neoforged:neoforge:${versionProp("neoforge_version")}")

    val modImpl = if (isNoRemap) "implementation" else "modImplementation"
    modImpl("dev.architectury:architectury-neoforge:${versionProp("architectury_api_version")}")

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionNeoForge")))
    "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
}

val javaVersion = versionProp("java_version").toInt()
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}

if (isNoRemap) {
    apply(from = rootProject.file("gradle/no-remap-shadow-conventions.gradle.kts"))
} else {
    tasks.named<ShadowJar>("shadowJar") {
        configurations = listOf(project.configurations["shadowBundle"])
        archiveClassifier.set("dev-shadow")
    }

    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        dependsOn(tasks.named("shadowJar"))
        val shadowJar = tasks.named<ShadowJar>("shadowJar")
        mustRunAfter(shadowJar)
        inputFile.set(shadowJar.get().archiveFile)
    }
}

extensions.extraProperties["quickSkinE2ESourceSet"] = e2eSourceSet
extensions.extraProperties["quickSkinE2ELoaderLabel"] = "NeoForge"
extensions.extraProperties["quickSkinE2ENoRemap"] = isNoRemap
extensions.extraProperties["quickSkinE2EMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/e2e-harness-conventions.gradle.kts"))
