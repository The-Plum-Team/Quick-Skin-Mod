// Shared Minecraft modules retain the matrix's common API-family overlay routing. Each module
// may own only a subset of those overlays; a same-path source replaces its canonical version.
@Suppress("UNCHECKED_CAST")
val definitions = gradle.extensions.extraProperties["quickSkinModules"] as List<Map<String, Any>>
val definition = definitions.single { it["id"] == project.path.split(':')[1] }
val moduleSources = rootProject.file("${definition["path"]}/src")
val matrix = gradle.extensions.extraProperties["quickSkinReleaseMatrix"] as Map<*, *>
val routes = (matrix["source_overlays"] as Map<*, *>)["common"] as Map<*, *>
val declaredDirectories = routes.values.map { it.toString() }.toSet()
val actualDirectories = moduleSources.listFiles().orEmpty()
    .filter { it.isDirectory && it.name.startsWith("legacy") }.map { it.name }.toSet()
check(declaredDirectories.containsAll(actualDirectories)) {
    "${project.path} has an overlay absent from the matrix: ${actualDirectories - declaredDirectories}"
}
val overlayName = routes[project.name]?.toString()
val overlay = overlayName?.let { moduleSources.resolve(it) }?.takeIf { it.isDirectory }
if (overlay != null) {
    val javaRoot = overlay.resolve("java")
    check(javaRoot.isDirectory) { "Missing module overlay Java root: $javaRoot" }
    val overrides = fileTree(javaRoot).matching { include("**/*.java") }.files
        .map { it.relativeTo(javaRoot).invariantSeparatorsPath }.toSet()
    val consolidated = layout.buildDirectory.dir("generated/consolidated/main/java")
    val prepareJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(layout.buildDirectory.dir("generated/stonecutter/main/java")) { exclude(overrides) }
        from(javaRoot)
        into(consolidated)
    }
    extensions.getByType(SourceSetContainer::class.java).named("main") {
        java.setSrcDirs(listOf(consolidated))
    }
    tasks.named("compileJava") { dependsOn(prepareJava) }
    tasks.matching { it.name == "sourcesJar" }.configureEach { dependsOn(prepareJava) }
    // Resource overlays remain at the assembly until a feature takes explicit ownership.
    check(!overlay.resolve("resources").exists()) {
        "Module resource overlays require an explicit resource ownership contract: $overlay"
    }
}
