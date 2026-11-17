// The Plum Team's hash-checked Maven mirror (https://github.com/The-Plum-Team/maven-mirror), on the
// team's own host, serves the openly licensed artifacts this build resolves: the Fabric,
// Architectury, Stonecutter, NeoForge and Forge ones and the libraries Maven Central serves. It is
// searched first, for everything but what it never holds (Mojang's and Minecraft's files and the
// namespaces Loom generates), so an outage or a rate limit of those hosts does not break the build.
// Whenever the mirror answers 404, Gradle continues with the upstream repositories declared after
// it, unchanged. settings.gradle.kts declares it the same way for plugins.
// Emergency switch: -Pplum.mavenMirror=false leaves the mirror out, so every group resolves from
// its upstream repositories only (for example while the mirror is down or serves a bad file).
// settings.gradle.kts reads the same property for plugins.
val plumMirrorSwitch = providers.gradleProperty("plum.mavenMirror").orNull
require(plumMirrorSwitch == null || plumMirrorSwitch == "true" || plumMirrorSwitch == "false") {
    "plum.mavenMirror must be true or false, not '$plumMirrorSwitch'"
}
if (plumMirrorSwitch != "false") {
    val plumMirror = repositories.findByName("PlumMavenMirror")
        ?: repositories.maven("https://maven.quickskin.net/") {
            name = "PlumMavenMirror"
            // The mirror holds exactly the files in the verification metadata: a module's .module
            // file, without the .pom that only points to it.
            metadataSources { gradleMetadata(); mavenPom() }
            mavenContent { releasesOnly() }
            content {
                excludeGroupByRegex("com\\.mojang(\\..*)?")
                excludeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
                excludeGroup("loom")
                excludeGroup("net.minecraft")
                excludeGroupByRegex("net\\.minecraftforge\\.[0-9a-f]{64}")
                excludeGroupByRegex("net\\.neoforged\\.fancymodloader\\.[0-9a-f]{64}")
                excludeGroupByRegex("remapped\\..+")
            }
        }
    // Repositories added before this script (Loom's and the build script's own) stay in their
    // order behind the mirror; later additions are appended after them.
    repositories.remove(plumMirror)
    repositories.addFirst(plumMirror)
}
