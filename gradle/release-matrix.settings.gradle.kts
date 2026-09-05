import groovy.json.JsonSlurper
import java.util.function.BiFunction


val matrixFile = settingsDir.resolve("release/release-matrix.json")
check(matrixFile.isFile) { "Missing central release matrix: $matrixFile" }
// Validate the complete inventory, including unselected targets, before registering projects.
val defaultPython = if (System.getProperty("os.name").startsWith("Windows", true))
    "python" else "python3"
val matrixJson = providers.exec {
    workingDir(settingsDir)
    commandLine(
        providers.environmentVariable("QUICKSKIN_PYTHON").orElse(defaultPython).get(),
        "scripts/release/matrix.py", "--matrix", matrixFile.absolutePath,
    )
}.standardOutput.asText.get()
val matrix = JsonSlurper().parseText(matrixJson) as? Map<*, *>
    ?: error("Central release matrix root must be an object: $matrixFile")
val artifacts = (matrix["artifacts"] as? List<*>)
    ?.map { artifact -> artifact as? Map<*, *> ?: error("Invalid artifact row in $matrixFile") }
    ?: error("Missing artifact inventory in $matrixFile")
val runtimes = (matrix["runtimes"] as? List<*>)
    ?.map { runtime -> runtime as? Map<*, *> ?: error("Invalid runtime row in $matrixFile") }
    ?: error("Missing runtime inventory in $matrixFile")
val requireString = BiFunction<Map<*, *>, String, String> { values, key ->
    requireNotNull(values[key]) { "Missing '$key' in $matrixFile" }.toString()
}
val buildTarget = providers.gradleProperty("quickskinTarget").orNull
val buildArtifacts = if (buildTarget == null) artifacts else {
    check(matrix["schema_version"] == 3) { "quickskinTarget requires release-matrix schema 3" }
    artifacts.filter { it["artifact_version"] == buildTarget }.also {
        check(it.isNotEmpty()) { "Unknown quickskinTarget '$buildTarget' in $matrixFile" }
    }
}

gradle.extensions.extraProperties.apply {
    set("quickSkinReleaseMatrixFile", matrixFile)
    set("quickSkinReleaseMatrix", matrix)
    set("quickSkinReleaseArtifacts", artifacts)
    set("quickSkinReleaseRuntimes", runtimes)
    set("quickSkinBuildArtifacts", buildArtifacts)
    set("quickSkinMatrixString", requireString)
}
