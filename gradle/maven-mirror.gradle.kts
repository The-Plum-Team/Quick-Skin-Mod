// The Plum Team's hash-checked Maven mirror (https://github.com/The-Plum-Team/maven-mirror) serves
// the openly licensed Fabric, Architectury, Stonecutter, NeoForge and Forge artifacts this build
// resolves. It is searched first, for exactly the groups below, so an outage of those hosts does
// not break the build. Whenever the mirror answers 404, Gradle continues with the upstream
// repositories declared after it, unchanged. Keep this list equal to the one in
// settings.gradle.kts and gradle/repository-policy.gradle.kts.
val plumMirrorGroups = listOf(
    "net.fabricmc", "net.fabricmc.fabric-api", "net.fabricmc.unpick",
    "dev.architectury", "dev.architectury.loom", "dev.architectury.loom-no-remap",
    "architectury-plugin", "dev.kikugie", "dev.kikugie.stonecutter",
    "net.neoforged", "net.neoforged.fancymodloader", "net.neoforged.installertools",
    "net.neoforged.accesstransformers", "cpw.mods", "net.minecraftforge",
)
val plumMirror = repositories.findByName("PlumMavenMirror")
    ?: repositories.maven("https://the-plum-team.github.io/maven-mirror/") {
        name = "PlumMavenMirror"
        // The mirror holds exactly the files in the verification metadata: a module's .module
        // file, without the .pom that only points to it.
        metadataSources { gradleMetadata(); mavenPom() }
        mavenContent { releasesOnly() }
        content { plumMirrorGroups.forEach { includeGroup(it) } }
    }
// Repositories added before this script (Loom's and the build script's own) stay in their order
// behind the mirror; later additions are appended after them.
repositories.remove(plumMirror)
repositories.addFirst(plumMirror)
