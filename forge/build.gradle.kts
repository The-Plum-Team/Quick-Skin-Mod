import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.artifacts.dsl.LockMode

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

apply(plugin = "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String
extensions.extraProperties["quickSkinFmlMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/fml-metadata-conventions.gradle.kts"))

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - Forge - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    platformSetupLoomIde()
    forge()
}

repositories {
    mavenCentral()
}
apply(from = rootProject.file("gradle/repository-policy.gradle.kts"))

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("forge/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("forge/src/main/resources")))
    }
}

extensions.configure<LoomGradleExtensionAPI>("loom") {
    forge {
        mixinConfig("quickskin.mixins.json")
        mixinConfig("quickskin-ears.mixins.json")
    }
}

configurations {
    create("common") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    create("shadowBundle") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    named("developmentForge") {
        extendsFrom(configurations["common"])
    }
}

// Only the dependency physically embedded in the release JAR is version-locked. Loom's generated
// Minecraft, mappings, remap, and development configurations remain checksum-verified without
// brittle lock state tied to generated Stonecutter project directories.
dependencyLocking {
    lockMode = LockMode.STRICT
    lockFile = rootProject.file("gradle/dependency-locks/forge-$minecraftVersion.lockfile")
}
configurations.named("shadowBundle") {
    resolutionStrategy.activateDependencyLocking()
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    "forge"("net.minecraftforge:forge:${versionProp("forge_version")}")
    "modImplementation"(
        "dev.architectury:architectury-forge:${versionProp("architectury_api_version")}"
    )

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionForge")))
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

tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations["shadowBundle"])
    archiveClassifier.set("dev-shadow")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    dependsOn(tasks.named("shadowJar"))
    val shadowJar = tasks.named<ShadowJar>("shadowJar")
    inputFile.set(shadowJar.get().archiveFile)
}

// FML gives the mod JAR one Java module, so the external library is nested, never merged into it.
tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        exclude(dependency("org.sejda.imageio:webp-imageio"))
    }
}
apply(from = rootProject.file("gradle/fml-nested-library-conventions.gradle.kts"))

apply(from = rootProject.file("gradle/e2e-harness-conventions.gradle.kts"))
