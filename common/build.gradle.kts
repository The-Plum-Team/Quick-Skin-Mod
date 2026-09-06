import dev.architectury.plugin.ArchitectPluginExtension
import dev.architectury.plugin.TransformingTask
import dev.architectury.plugin.loom.LoomInterface117
import dev.architectury.transformer.shadowed.impl.com.google.gson.JsonObject
import dev.architectury.transformer.transformers.RemapInjectables
import dev.architectury.transformer.transformers.TransformExpectPlatform
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker
import net.fabricmc.loom.util.gradle.SourceSetHelper
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Sync
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val matrixState = gradle.extensions.extraProperties
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val canonicalVersions = releaseArtifacts
    .map { it["artifact_version"].toString() }
    .toSet()
check(minecraftVersion in canonicalVersions) {
    "Common node $minecraftVersion has no release-matrix artifact"
}
val versionArtifacts = releaseArtifacts.filter { it["artifact_version"].toString() == minecraftVersion }
val noRemapPolicies = versionArtifacts.map { artifact ->
    artifact["no_remap"] as? Boolean
        ?: error("Release artifact ${artifact["artifact_node"]} is missing boolean no_remap")
}.distinct()
check(noRemapPolicies.size == 1) {
    "Release lanes disagree on no_remap for Minecraft $minecraftVersion"
}
val isNoRemap = noRemapPolicies.single()
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedLegacyJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val consolidatedLegacyResources = layout.buildDirectory.dir("generated/consolidated/main/resources")
val legacyCommonJar = rootProject.file(
    "common/build/${if (isNoRemap) "libs" else "devlibs"}/" +
        "Quick Skin - common - $minecraftVersion-${rootProject.property("mod_version")}-dev.jar"
).toPath()
// Architectury embeds this value in generated class names. Hash the stable, repository-relative
// artifact identity so identical sources produce the same classes on every checkout and OS.
val legacyArtifactIdentity = rootProject.projectDir.toPath()
    .relativize(legacyCommonJar)
    .toString()
    .replace(File.separatorChar, '/')
val legacyArtifactHash = MessageDigest.getInstance("SHA-256")
    .digest(legacyArtifactIdentity.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
val legacyArchitecturyPackage = (
    "architectury_inject_quickskin_common_${rootProject.property("architectury_inject_id")}_" +
        "$legacyArtifactHash${legacyCommonJar.fileName}"
).filter { Character.isJavaIdentifierPart(it) }

apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
apply(plugin = "architectury-plugin")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - common - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    common(
        releaseArtifacts
            .filter { it["artifact_version"].toString() == minecraftVersion }
            .map { it["loader"].toString() }
    )
}

repositories {
    mavenCentral()
}
apply(from = rootProject.file("gradle/repository-policy.gradle.kts"))

val sourceOverlays = releaseMatrix["source_overlays"] as Map<*, *>
val commonOverlayRoutes = sourceOverlays["common"] as Map<*, *>
val declaredLegacyDirectories = commonOverlayRoutes.values.mapTo(linkedSetOf()) { it.toString() }
val actualLegacyDirectories = rootProject.file("common/src").listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.name.startsWith("legacy") }
    .mapTo(linkedSetOf()) { it.name }
check(actualLegacyDirectories == declaredLegacyDirectories) {
    "Common legacy source roots disagree with routing: declared=$declaredLegacyDirectories, " +
        "actual=$actualLegacyDirectories"
}

val legacyOverlay = commonOverlayRoutes[minecraftVersion]?.toString()?.let { overlayDirectory ->
    Pair(
        rootProject.file("common/src/$overlayDirectory/java"),
        rootProject.file("common/src/$overlayDirectory/resources"),
    )
}

if (legacyOverlay != null) {
    val (legacyJavaRoot, legacyResourcesRoot) = legacyOverlay
    check(legacyJavaRoot.isDirectory || legacyResourcesRoot.isDirectory) {
        "Missing common overlay sources: ${legacyJavaRoot.parentFile}"
    }
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
        from(rootProject.file("common/src/main/resources")) {
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

// Every exposed common:<version>:test task runs the loader-independent suite against that lane's
// generated source set. Behavioral parity for overlay-replaced classes requires dedicated tests;
// compilation alone is not treated as drift detection.
sourceSets.test {
    java.setSrcDirs(listOf(rootProject.file("common/src/test/java")))
    resources.setSrcDirs(listOf(rootProject.file("common/src/test/resources")))
}

extensions.configure<LoomGradleExtensionAPI>("loom") {
    if (isNoRemap) {
        val awFile = rootProject.file("common/src/main/resources/quick-skin.accesswidener")
        if (awFile.exists()) accessWidenerPath.set(awFile)
    }
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    if (!isNoRemap) {
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }

    val modImpl = if (isNoRemap) "implementation" else "modImplementation"
    modImpl("net.fabricmc:fabric-loader:${versionProp("fabric_loader_version")}")
    modImpl("dev.architectury:architectury:${versionProp("architectury_api_version")}")

    "implementation"("org.sejda.imageio:webp-imageio:0.1.6")

    "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.4")
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

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

apply(from = rootProject.file("gradle/java-module-bundle-conventions.gradle.kts"))

// Keep the production transform identity independent from checkout paths. Loader branches share
// this transform seam; NeoForge does not consume classic refmap/SRG properties.
gradle.projectsEvaluated {
    tasks.withType<TransformingTask>().configureEach {
        val targetPlatform = when {
            name.endsWith("NeoForge") -> "neoforge"
            name.endsWith("Forge") -> "forge"
            else -> "fabric"
        }

        properties.set(providers.provider {
            val architecturyExtension = project.extensions.getByType<ArchitectPluginExtension>()
            val loomInterface = LoomInterface117(project)
            val transformerClasspath =
                (project.configurations.findByName("architecturyTransformerClasspath")
                    ?: project.configurations.getByName("compileClasspath"))

            val result = linkedMapOf(
                "architectury.inject.injectables" to architecturyExtension.injectInjectables.toString(),
                "architectury.unique.identifier" to legacyArchitecturyPackage,
                "architectury.classpath" to transformerClasspath.files.joinToString(File.pathSeparator),
                "architectury.platform.name" to targetPlatform,
                "architectury.mcmeta.version" to "4",
            )

            if (targetPlatform != "neoforge") {
                if (targetPlatform == "forge" && !loomInterface.addRefmapForForge) {
                    result["architectury.forge.fix_mixins"] = "false"
                } else if (loomInterface.legacyMixinApEnabled) {
                    result["architectury.refmap.name"] = loomInterface.refmapName
                }

                if (!loomInterface.disableObfuscation) {
                    result["architectury.srg.mappings"] = loomInterface.tinyMappingsWithSrg.toString()
                }
            }

            if (!loomInterface.disableObfuscation) {
                val currentMappings = LoomGradleExtension.get(project).mappingConfiguration.mappingsIdentifier
                val mixinMappings = mutableListOf<File>()

                rootProject.allprojects
                    .filter {
                        it.pluginManager.hasPlugin("dev.architectury.loom") &&
                            !it.pluginManager.hasPlugin("dev.architectury.loom-no-remap")
                    }
                    .forEach { loomProject ->
                        val loomExtension = LoomGradleExtension.get(loomProject)
                        if (loomExtension.mappingConfiguration.mappingsIdentifier == currentMappings) {
                            SourceSetHelper.getSourceSets(loomProject).forEach { sourceSet ->
                                val mappingFile = AnnotationProcessorInvoker.getMixinMappingsForSourceSet(
                                    loomProject,
                                    sourceSet,
                                )
                                if (mappingFile.exists()) mixinMappings += mappingFile
                            }
                        }
                    }

                result["architectury.mixin.mappings"] = mixinMappings.joinToString(File.pathSeparator)
            }

            result
        })
        properties.disallowChanges()
        val transformTask = this
        gradle.taskGraph.whenReady {
            if (hasTask(transformTask)) {
                val configuredTransformers = transformTask.transformers.get()
                val identity = JsonObject().apply {
                    addProperty(
                        "architectury.unique.identifier",
                        legacyArchitecturyPackage,
                    )
                }
                configuredTransformers.forEach {
                    if (it is TransformExpectPlatform || it is RemapInjectables) {
                        it.supplyProperties(identity)
                    }
                }
                transformTask.transformers.set(configuredTransformers)
            }
        }
    }
}

extensions.configure<PublishingExtension>("publishing") {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = extensions.getByType<BasePluginExtension>().archivesName.get()
            from(components["java"])
        }
    }
}
