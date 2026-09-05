import dev.kikugie.stonecutter.controller.flag.StonecutterFlag

plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.480" apply false
    id("dev.architectury.loom-no-remap") version "1.17.480" apply false
    id("architectury-plugin") version "3.5.167" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
}

// Detached mode preprocesses each branch's canonical src/main tree into every node's generated
// sources without rewriting tracked files. Active nodes consume that output, with only the narrow
// src/legacy* era overlays declared by the release matrix. Copy-based src/v* snapshots are retired
// and rejected by release-matrix validation.
stonecutter active null

stonecutter {
    flags {
        set(StonecutterFlag.GENERATE_MANIFEST, false)
    }
}

val matrixState = gradle.extensions.extraProperties
val releaseMatrixFile = matrixState["quickSkinReleaseMatrixFile"] as java.io.File
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val releaseLaneCount = (releaseMatrix["lane_count"] as? Number)?.toInt()
    ?: error("Missing lane_count in $releaseMatrixFile")
val unitTestVersion = releaseMatrix["unit_test_version"]?.toString()
    ?: error("Missing unit_test_version in $releaseMatrixFile")
check(releaseArtifacts.size == releaseLaneCount) {
    "Release artifact inventory has ${releaseArtifacts.size} rows; expected lane_count=$releaseLaneCount"
}
val releaseNodeNames = releaseArtifacts.map { artifact ->
    val loader = artifact["loader"]?.toString() ?: error("Release artifact is missing loader")
    val version = artifact["artifact_version"]?.toString()
        ?: error("Release artifact is missing artifact_version")
    val node = artifact["artifact_node"]?.toString()
        ?: error("Release artifact is missing artifact_node")
    check(node == "$loader-$version") { "Release node $node does not match $loader $version" }
    val propertySuffix = version.replace(".", "_")
    check(rootProject.property("minecraft_version_$propertySuffix").toString() == version) {
        "Minecraft Gradle property disagrees with release lane $node"
    }
    check(rootProject.property("java_version_$propertySuffix").toString() == artifact["java"].toString()) {
        "Java Gradle property disagrees with release lane $node"
    }
    node
}
check(releaseNodeNames.distinct().size == releaseNodeNames.size) {
    "Duplicate release artifact node in $releaseMatrixFile"
}
releaseArtifacts.groupBy { it["artifact_version"].toString() }.forEach { (version, rows) ->
    val policies = rows.map { row ->
        val noRemap = row["no_remap"] as? Boolean
            ?: error("Release artifact ${row["artifact_node"]} is missing boolean no_remap")
        row["java"].toString() to noRemap
    }.distinct()
    check(policies.size == 1) {
        "Release lanes disagree on Java/no_remap policy for Minecraft $version"
    }
}
check(releaseArtifacts.any { it["artifact_version"].toString() == unitTestVersion }) {
    "Unit-test version $unitTestVersion is not a release lane"
}
val releaseArtifactTasks = releaseArtifacts.map { artifact ->
    artifact["gradle_task"]?.toString() ?: error("Release artifact is missing gradle_task")
}
val releaseHarnessTasks = releaseArtifacts.map { artifact ->
    artifact["harness_task"]?.toString() ?: error("Release artifact is missing harness_task")
}
releaseArtifacts.forEachIndexed { index, artifact ->
    val loader = artifact["loader"].toString()
    val version = artifact["artifact_version"].toString()
    val noRemap = artifact["no_remap"] as? Boolean
        ?: error("Release artifact ${artifact["artifact_node"]} is missing boolean no_remap")
    val prefix = ":$loader:$version:"
    val expectedProductionTask = prefix + if (noRemap) "shadowJar" else "remapJar"
    val expectedHarnessTask =
        prefix + if (noRemap) "e2eHarnessJar" else "remapE2EHarnessJar"
    check(releaseArtifactTasks[index] == expectedProductionTask) {
        "Release production task ${releaseArtifactTasks[index]} must be $expectedProductionTask"
    }
    check(releaseHarnessTasks[index] == expectedHarnessTask) {
        "Release harness task ${releaseHarnessTasks[index]} must be $expectedHarnessTask"
    }
}

val validateReleaseLaneInventory = tasks.register("validateReleaseLaneInventory") {
    group = "verification"
    description = "Checks that every central release-matrix lane resolves to real Gradle tasks."
    inputs.file(releaseMatrixFile)
    doLast {
        (releaseArtifactTasks + releaseHarnessTasks).forEach { taskPath ->
            tasks.getByPath(taskPath)
        }
    }
}

val testModuleGraph = tasks.register<Exec>("testModuleGraph") {
    group = "verification"
    description = "Verifies module-boundary and transitive-impact policy."
    workingDir(rootProject.projectDir)
    val defaultPython = if (System.getProperty("os.name").startsWith("Windows", true))
        "python" else "python3"
    commandLine(
        providers.environmentVariable("QUICKSKIN_PYTHON").orElse(defaultPython).get(),
        "-m", "unittest", "discover", "-s", "scripts/architecture/tests", "-p", "test_*.py",
    )
}

val testStableLane = tasks.register("testStableLane") {
    group = "verification"
    description = "Runs loader-independent JUnit tests on common $unitTestVersion."
    dependsOn(testModuleGraph, ":common:$unitTestVersion:test")
}

tasks.register("check") {
    group = "verification"
    description = "Runs the central lane and loader-independent verification suite."
    dependsOn(validateReleaseLaneInventory, testStableLane)
}

tasks.register("buildAllLanes") {
    group = "build"
    description = "Builds all $releaseLaneCount production artifacts from the release matrix."
    dependsOn(validateReleaseLaneInventory, testStableLane, releaseArtifactTasks)
}

tasks.register("buildAllE2EHarnesses") {
    group = "verification"
    description = "Builds all $releaseLaneCount packaged-runtime E2E harnesses from the release matrix."
    dependsOn(validateReleaseLaneInventory, releaseHarnessTasks)
}
