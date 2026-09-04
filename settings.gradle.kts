pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "FabricPluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("net\\.fabricmc(\\..*)?")
            }
        }
        maven("https://maven.architectury.dev/") {
            name = "ArchitecturyPluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("dev\\.architectury(\\..*)?")
                includeGroup("architectury-plugin")
                includeGroup("com.github.architectury")
            }
        }
        maven("https://libraries.minecraft.net/") {
            name = "MojangPluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("com\\.mojang(\\..*)?")
            }
        }
        maven("https://maven.minecraftforge.net/") {
            name = "ForgePluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("net\\.minecraftforge(\\..*)?")
                includeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
                includeGroupByRegex("org\\.spongepowered(\\..*)?")
                excludeGroupByRegex("net\\.minecraftforge\\.[0-9a-f]{64}")
            }
        }
        maven("https://maven.neoforged.net/releases") {
            name = "NeoForgePluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("net\\.neoforged(\\..*)?")
            }
        }
        maven("https://maven.kikugie.dev/releases") {
            name = "KikugiePluginRepository"
            mavenContent { releasesOnly() }
            content {
                includeGroupByRegex("dev\\.kikugie(\\..*)?")
            }
        }
        mavenCentral {
            content {
                excludeGroupByRegex("net\\.fabricmc(\\..*)?")
                excludeGroupByRegex("dev\\.architectury(\\..*)?")
                excludeGroup("architectury-plugin")
                excludeGroup("com.github.architectury")
                excludeGroupByRegex("com\\.mojang(\\..*)?")
                excludeGroupByRegex("net\\.minecraftforge(\\..*)?")
                excludeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
                excludeGroupByRegex("org\\.spongepowered(\\..*)?")
                excludeGroupByRegex("net\\.neoforged(\\..*)?")
                excludeGroupByRegex("dev\\.kikugie(\\..*)?")
            }
        }
        gradlePluginPortal {
            content {
                excludeGroupByRegex("net\\.fabricmc(\\..*)?")
                excludeGroupByRegex("dev\\.architectury(\\..*)?")
                excludeGroup("architectury-plugin")
                excludeGroup("com.github.architectury")
                excludeGroupByRegex("com\\.mojang(\\..*)?")
                excludeGroupByRegex("net\\.minecraftforge(\\..*)?")
                excludeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
                excludeGroupByRegex("org\\.spongepowered(\\..*)?")
                excludeGroupByRegex("net\\.neoforged(\\..*)?")
                excludeGroupByRegex("dev\\.kikugie(\\..*)?")
            }
        }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

apply(from = file("gradle/release-matrix.settings.gradle.kts"))

val matrixState = gradle.extensions.extraProperties
val releaseMatrixFile = matrixState["quickSkinReleaseMatrixFile"] as java.io.File
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val expectedLaneCount = (releaseMatrix["lane_count"] as? Number)?.toInt()
    ?: error("Missing lane_count in $releaseMatrixFile")
check(releaseArtifacts.size == expectedLaneCount) {
    "Release artifact inventory has ${releaseArtifacts.size} rows; expected lane_count=$expectedLaneCount"
}
val releaseLanes = releaseArtifacts.map { artifact ->
    val loader = artifact["loader"]?.toString() ?: error("Release artifact is missing loader")
    val version = artifact["artifact_version"]?.toString()
        ?: error("Release artifact is missing artifact_version")
    val node = artifact["artifact_node"]?.toString()
        ?: error("Release artifact is missing artifact_node")
    check(loader in setOf("fabric", "forge", "neoforge")) {
        "Unsupported release loader: $loader"
    }
    check(node == "$loader-$version") { "Release node $node does not match $loader $version" }
    loader to version
}
check(releaseLanes.distinct().size == releaseLanes.size) { "Duplicate lane in $releaseMatrixFile" }
val releaseLoaders = releaseLanes.map { it.first }.toSet()
check(releaseLoaders.isNotEmpty()) {
    "Release lanes must declare at least one active loader"
}
val releaseVersions = releaseLanes.map { it.second }.distinct().toTypedArray()

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        versions(*releaseVersions)

        branch("common") {
            versions(*releaseVersions)
        }
        releaseLoaders.sorted().forEach { loader ->
            branch(loader) {
                versions(
                    *releaseLanes
                        .filter { it.first == loader }
                        .map { it.second }
                        .toTypedArray()
                )
            }
        }
    }
}

rootProject.name = "quick-skin"
