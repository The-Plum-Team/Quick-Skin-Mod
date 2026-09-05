import org.gradle.jvm.tasks.Jar

@Suppress("UNCHECKED_CAST")
val definitions = (gradle.extensions.extraProperties["quickSkinModules"]
    as List<Map<String, Any>>).associateBy { it["id"].toString() }
val owner = project.path.split(':').first { it.isNotEmpty() }
val definition = definitions.getValue(owner)
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = gradle.extensions.extraProperties["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val noRemap = releaseArtifacts.filter { it["artifact_version"] == project.name }
    .map { it["no_remap"] as Boolean }.distinct().single()
val dependencyKinds = mapOf(
    "api" to "implementation", "implementation" to "implementation", "runtime_only" to "runtimeOnly",
)
val bundledIds = linkedSetOf<String>()
fun modulePath(moduleId: String): String =
    if (definitions.getValue(moduleId)["kind"] == "java-library") ":modules:$moduleId"
    else ":$moduleId:${project.name}"
fun moduleConfiguration(moduleId: String): String =
    if (definitions.getValue(moduleId)["kind"] == "java-library" || noRemap) "runtimeElements"
    else "namedElements"
fun collectDependencies(moduleId: String) {
    dependencyKinds.keys.forEach { key ->
        (definitions.getValue(moduleId)[key] as List<*>).forEach { dependency ->
            val dependencyId = dependency.toString()
            if (bundledIds.add(dependencyId)) collectDependencies(dependencyId)
        }
    }
}
collectDependencies(owner)

dependencyKinds.forEach { (key, configuration) ->
    (definition[key] as List<*>).forEach { dependency ->
        val moduleId = dependency.toString()
        val selected = if (definitions.getValue(moduleId)["kind"] == "minecraft" && !noRemap) {
            dependencies.project(mapOf("path" to modulePath(moduleId), "configuration" to "namedElements"))
        } else project(modulePath(moduleId))
        dependencies.add(configuration, selected)
    }
}
// Package only the internal module closure. External libraries remain owned by the
// loader's existing bundle policy rather than being pulled into the mod transitively.
val moduleBundle = configurations.create("internalModuleBundle") {
    isCanBeConsumed = false
    isTransitive = false
}
val moduleSources = configurations.create("internalModuleSources") {
    isCanBeConsumed = false
    isTransitive = false
}
bundledIds.sorted().forEach { moduleId ->
    dependencies.add(moduleBundle.name, dependencies.project(mapOf(
        "path" to modulePath(moduleId), "configuration" to moduleConfiguration(moduleId),
    )))
    dependencies.add(moduleSources.name, dependencies.project(mapOf(
        "path" to modulePath(moduleId), "configuration" to "sourcesElements",
    )))
}
tasks.named<Jar>("jar") {
    dependsOn(moduleBundle)
    from({ moduleBundle.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
tasks.named<Jar>("sourcesJar") {
    dependsOn(moduleSources)
    from({ moduleSources.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
}
tasks.named("test") {
    dependsOn(bundledIds.map { "${modulePath(it)}:test" })
}
