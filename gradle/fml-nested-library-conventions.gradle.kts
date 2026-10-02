import groovy.json.JsonOutput
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.bundling.AbstractArchiveTask

// FML loads a mod JAR as one Java module. A library merged into it puts that library's packages in
// the `quickskin` module, and the module layer fails to resolve as soon as another mod ships the
// same library as its own module. Forge and NeoForge therefore carry the external `shadowBundle`
// libraries as unmodified JarJar-nested JARs, which FML selects once per Maven identity.
//
// Loom's `include` cannot do this here: it stamps the nested JAR with the wall clock, which breaks
// the reproducible release build, and Shadow would unpack that JAR again on the no-remap lanes.
// The production task named by the release matrix appends the entries itself instead. Each loader
// script must exclude from Shadow exactly the libraries nested here.
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = gradle.extensions.extraProperties["quickSkinReleaseArtifacts"]
    as List<Map<*, *>>
val productionTask = releaseArtifacts
    .map { it["gradle_task"].toString() }
    .singleOrNull { it.substringBeforeLast(':') == project.path }
    ?.substringAfterLast(':')
    ?: error("Release matrix has no unique production task for ${project.path}")
val nestedLibraries = configurations["shadowBundle"].incoming.artifactView {
    componentFilter { it is ModuleComponentIdentifier }
}.artifacts
val nestedDirectory = "META-INF/jarjar/"

tasks.named<AbstractArchiveTask>(productionTask) {
    inputs.files(nestedLibraries.artifactFiles)
    doLast {
        val target = archiveFile.get().asFile
        val additions = linkedMapOf(nestedDirectory to ByteArray(0))
        val jars = nestedLibraries.artifacts.sortedBy { it.file.name }.map { artifact ->
            val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
            val path = nestedDirectory + artifact.file.name
            additions[path] = artifact.file.readBytes()
            mapOf(
                "identifier" to mapOf("group" to id.group, "artifact" to id.module),
                "version" to mapOf(
                    "range" to "[${id.version},)",
                    "artifactVersion" to id.version,
                ),
                "path" to path,
            )
        }
        check(jars.isNotEmpty()) { "No external library to nest into ${target.name}" }
        additions[nestedDirectory + "metadata.json"] =
            JsonOutput.toJson(mapOf("jars" to jars)).toByteArray(Charsets.UTF_8)

        // Copy the produced entries in order and append stored entries with Gradle's constant
        // archive time, so the result depends only on the input JAR and the nested bytes.
        val rewritten = File(temporaryDir, target.name)
        ZipFile(target).use { source ->
            ZipOutputStream(rewritten.outputStream().buffered()).use { output ->
                for (entry in source.entries()) {
                    check(entry.name !in additions) {
                        "${target.name} already contains ${entry.name}"
                    }
                    val copy = ZipEntry(entry)
                    if (copy.method == ZipEntry.DEFLATED) copy.compressedSize = -1
                    output.putNextEntry(copy)
                    if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
                for ((path, bytes) in additions) {
                    val entry = ZipEntry(path)
                    entry.setTimeLocal(LocalDateTime.of(1980, 2, 1, 0, 0))
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(bytes) }.value
                    output.putNextEntry(entry)
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }
        Files.move(rewritten.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
