import groovy.json.JsonSlurper

// One typed reader validates the dependency DAG for both Gradle and E2E impact planning.
val defaultPython = if (System.getProperty("os.name").startsWith("Windows", true))
    "python" else "python3"
val graphJson = providers.exec {
    workingDir(settingsDir)
    commandLine(
        providers.environmentVariable("QUICKSKIN_PYTHON").orElse(defaultPython).get(),
        "scripts/architecture/module_graph.py", "--repository", settingsDir.absolutePath,
    )
}.standardOutput.asText.get()
val moduleGraph = JsonSlurper().parseText(graphJson) as Map<*, *>
@Suppress("UNCHECKED_CAST")
val moduleDefinitions = moduleGraph["modules"] as List<Map<String, Any>>
gradle.extensions.extraProperties.set("quickSkinModuleGraph", moduleGraph)
gradle.extensions.extraProperties.set("quickSkinModules", moduleDefinitions)
moduleDefinitions.filter { it["kind"] == "java-library" }.forEach { definition ->
    val projectPath = ":modules:${definition["id"]}"
    include(projectPath)
    project(projectPath).projectDir = settingsDir.resolve(definition["path"].toString())
}
// Register each module's directory before Stonecutter expands its version nodes.
moduleDefinitions.filter { it["kind"] == "minecraft" && it["id"] != "common" }
    .forEach { definition ->
        val projectPath = ":${definition["id"]}"
        include(projectPath)
        project(projectPath).projectDir = settingsDir.resolve(definition["path"].toString())
    }
