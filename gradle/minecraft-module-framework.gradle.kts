// Only the target's framework enters this classpath. Feature code is reachable solely through
// the declared module dependencies; no common compilation output is supplied as a shortcut.
@Suppress("UNCHECKED_CAST")
val artifacts = gradle.extensions.extraProperties["quickSkinReleaseArtifacts"] as List<Map<*, *>>
val versionArtifacts = artifacts.filter { it["artifact_version"] == project.name }
check(versionArtifacts.isNotEmpty()) { "Minecraft module ${project.path} has no matrix target" }
val noRemap = versionArtifacts.map { it["no_remap"] as Boolean }.distinct().single()
val suffix = project.name.replace('.', '_')

check(configurations.toList().all { it.dependencies.isEmpty() }) {
    "Module dependencies must be declared through architecture/modules.json"
}
val moduleConfigurations = configurations.names.toSet()
apply(plugin = if (noRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
repositories { mavenCentral() }
apply(from = rootProject.file("gradle/repository-policy.gradle.kts"))
dependencies {
    add("minecraft", "net.minecraft:minecraft:${project.name}")
    if (!noRemap) {
        // Applied Kotlin scripts have an isolated compile classpath. Invoke Loom's public
        // extension method without loading a second copy/version of the plugin into this script.
        val loom = project.extensions.getByName("loom")
        add("mappings", loom.javaClass.getMethod("officialMojangMappings").invoke(loom))
    }
    // Environment annotations and Architectury's stable GUI/event API are platform inputs.
    val modConfiguration = if (noRemap) "implementation" else "modImplementation"
    add(modConfiguration, "net.fabricmc:fabric-loader:${rootProject.property("fabric_loader_version_$suffix")}")
    add(modConfiguration, "dev.architectury:architectury:${rootProject.property("architectury_api_version_$suffix")}")
}
val frameworkDependencies = configurations.associate { it.name to it.dependencies.toList() }
extensions.extraProperties.set("quickSkinFrameworkDependencies", frameworkDependencies)
// Loom derives mapped Minecraft jars and loader libraries lazily at resolution time. Their
// generated configurations are framework-owned; their authored input versions stay sealed.
// All first-party project dependencies are still checked, including in framework configurations.
val frameworkInputs = setOf("minecraft", "mappings", "modApi", "modImplementation",
    "modCompileOnly", "modCompileOnlyApi", "modRuntimeOnly", "modLocalRuntime", "include", "namedElements")
val mappedSourceConfigurations = extensions.getByType(SourceSetContainer::class.java).flatMap { source ->
    val name = source.name.replaceFirstChar { it.uppercaseChar() }
    listOf("modCompileClasspath${name}Mapped", "modRuntimeClasspath${name}Mapped")
}
extensions.extraProperties.set("quickSkinDerivedFrameworkConfigurations",
    (configurations.names - moduleConfigurations - frameworkInputs) +
        setOf("minecraftNamedCompile", "minecraftNamedRuntime") + mappedSourceConfigurations)
