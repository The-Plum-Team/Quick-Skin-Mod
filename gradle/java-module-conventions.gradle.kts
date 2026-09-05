import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

@Suppress("UNCHECKED_CAST")
val definitions = gradle.extensions.extraProperties["quickSkinModules"]
    as List<Map<String, Any>>
val definition = definitions.single {
    it["id"] == if (project.path.startsWith(":modules:")) project.name else project.path.split(':')[1]
}
val isMinecraftModule = definition["kind"] == "minecraft"
val moduleVersion = if (isMinecraftModule) project.name else null
@Suppress("UNCHECKED_CAST")
val graph = gradle.extensions.extraProperties["quickSkinModuleGraph"] as Map<String, Any>
@Suppress("UNCHECKED_CAST")
val libraries = graph["libraries"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = gradle.extensions.extraProperties["quickSkinReleaseArtifacts"]
    as List<Map<*, *>>
// A version-independent module uses the lowest supported bytecode level.
val javaVersion = if (isMinecraftModule) {
    releaseArtifacts.filter { it["artifact_version"] == moduleVersion }
        .map { (it["java"] as Number).toInt() }.distinct().single()
} else releaseArtifacts.minOf { (it["java"] as Number).toInt() }

@Suppress("UNCHECKED_CAST")
val frameworkDependencies = if (isMinecraftModule) {
    extensions.extraProperties["quickSkinFrameworkDependencies"]
        as Map<String, List<org.gradle.api.artifacts.Dependency>>
} else emptyMap()
@Suppress("UNCHECKED_CAST")
val derivedFrameworkConfigurations = if (isMinecraftModule) {
    extensions.extraProperties["quickSkinDerivedFrameworkConfigurations"] as Set<String>
} else emptySet()
check(configurations.toList().all {
    it.dependencies.toList() == frameworkDependencies[it.name].orEmpty()
}) {
    "Module dependencies must be declared through architecture/modules.json"
}
val byId = definitions.associateBy { it["id"].toString() }
fun modulePath(moduleId: String): String =
    if (byId.getValue(moduleId)["kind"] == "java-library") ":modules:$moduleId"
    else ":$moduleId:$moduleVersion"

val baseGroup = rootProject.property("maven_group") as String
// Stonecutter names every version node after Minecraft. Distinct modules must not publish the
// same implicit Gradle capability (group:name:version), or resolution can substitute common
// for its own dependency and create a compileJava -> jar -> compileJava task cycle.
group = if (isMinecraftModule) "$baseGroup.modules.${definition["id"]}" else baseGroup
version = rootProject.property("mod_version") as String
extensions.configure<BasePluginExtension> {
    archivesName.set(definition["id"].toString() + (moduleVersion?.let { "-$it" } ?: ""))
}
repositories {
    mavenCentral()
    maven("https://maven.architectury.dev/") {
        content { includeModule("dev.architectury", "architectury-injectables") }
    }
    maven("https://maven.fabricmc.net/") {
        content { includeModule("net.fabricmc", "fabric-loader") }
    }
}
apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    withSourcesJar()
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
}
val dependencyConfigurations = mapOf(
    "api" to "api", "implementation" to "implementation", "runtime_only" to "runtimeOnly",
)
dependencyConfigurations
    .forEach { (key, configuration) ->
        (definition[key] as List<*>).forEach { dependency ->
            val moduleId = dependency.toString()
            val selected = if (byId.getValue(moduleId)["kind"] == "minecraft") {
                dependencies.project(mapOf("path" to modulePath(moduleId), "configuration" to "namedElements"))
            } else project(modulePath(moduleId))
            dependencies.add(configuration, selected)
        }
    }
val libraryConfigurations = mapOf(
    "api" to "api", "implementation" to "implementation",
    "compile_only_api" to "compileOnlyApi", "compile_only" to "compileOnly",
    "runtime_only" to "runtimeOnly", "test_implementation" to "testImplementation",
    "test_runtime_only" to "testRuntimeOnly",
)
@Suppress("UNCHECKED_CAST")
val libraryDependencies = definition["libraries"] as Map<String, List<String>>
val expectedLibraries = mutableMapOf(
    "testImplementation" to mutableListOf("org.junit.jupiter:junit-jupiter:5.13.4"),
    "testRuntimeOnly" to mutableListOf("org.junit.platform:junit-platform-launcher:1.13.4"),
)
libraryDependencies.forEach { (scope, ids) ->
    val configuration = libraryConfigurations.getValue(scope)
    ids.forEach { id ->
        expectedLibraries.getOrPut(configuration) { mutableListOf() }.add(libraries.getValue(id))
    }
}
expectedLibraries.forEach { (configuration, coordinates) ->
    coordinates.forEach { coordinate -> dependencies.add(configuration, coordinate) }
}
val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    description = "Rejects dependencies declared outside the shared module graph and test convention."
    doLast {
        val expected = dependencyConfigurations.mapValues { (key, _) ->
            (definition[key] as List<*>).map { modulePath(it.toString()) }.sorted()
        }
        configurations.forEach { configuration ->
            val declared = configuration.dependencies.withType(ProjectDependency::class.java)
                .map { it.path }.sorted()
            val definitionKey = dependencyConfigurations.entries
                .singleOrNull { it.value == configuration.name }?.key
            val allowed = definitionKey?.let { expected.getValue(it) }.orEmpty()
            check(declared == allowed) {
                "${project.path}:${configuration.name} project dependencies disagree with " +
                    "architecture/modules.json: expected=$allowed, declared=$declared"
            }
            val declaredLibraries = configuration.dependencies
                .withType(ExternalModuleDependency::class.java)
                .map { "${it.group}:${it.name}:${it.version}" }.sorted()
            val framework = frameworkDependencies[configuration.name].orEmpty()
            val allowedLibraries = (expectedLibraries[configuration.name].orEmpty() +
                framework.filterIsInstance<ExternalModuleDependency>()
                    .map { "${it.group}:${it.name}:${it.version}" }).sorted()
            check(configuration.name in derivedFrameworkConfigurations || declaredLibraries == allowedLibraries) {
                "${project.path}:${configuration.name} external dependencies disagree with " +
                    "architecture/modules.json: expected=$allowedLibraries, declared=$declaredLibraries"
            }
            check(configuration.dependencies.toList().all {
                it is ProjectDependency || it is ExternalModuleDependency || it in framework
                    || configuration.name in derivedFrameworkConfigurations
            }) {
                "${project.path}:${configuration.name} cannot add untracked file dependencies"
            }
        }
    }
}
tasks.named("compileJava") { dependsOn(verifyModuleDependencies) }
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
