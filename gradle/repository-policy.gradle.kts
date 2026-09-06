import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository

// Loom owns the file-backed remap and Minecraft repositories. They contain generated or
// previously verified local artifacts, so this policy only constrains network repositories.
repositories.withType<MavenArtifactRepository>().configureEach {
    val repositoryScheme = url.scheme?.lowercase()
    val repositoryHost = url.host?.lowercase()
    when {
        repositoryScheme == "file" -> Unit
        repositoryScheme != "https" -> throw GradleException(
            "Unapproved dependency repository scheme for $path: $url. " +
                "Only local file repositories and reviewed HTTPS hosts are allowed."
        )
        repositoryHost == "maven.architectury.dev" -> content {
            includeGroupByRegex("dev\\.architectury(\\..*)?")
        }
        repositoryHost == "maven.fabricmc.net" -> content {
            includeGroupByRegex("net\\.fabricmc(\\..*)?")
        }
        repositoryHost == "libraries.minecraft.net" -> content {
            includeGroupByRegex("com\\.mojang(\\..*)?")
            // Mojang's 1.21.x library metadata declares a patched macOS classifier that is not
            // published by LWJGL on Maven Central. Keep the exception module-scoped.
            includeModule("org.lwjgl", "lwjgl-freetype")
        }
        repositoryHost == "maven.minecraftforge.net" -> content {
            includeGroupByRegex("net\\.minecraftforge(\\..*)?")
            includeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
            includeGroupByRegex("org\\.spongepowered(\\..*)?")
            excludeGroupByRegex("net\\.minecraftforge\\.[0-9a-f]{64}")
        }
        repositoryHost == "maven.neoforged.net" -> content {
            includeGroupByRegex("net\\.neoforged(\\..*)?")
            includeGroupByRegex("cpw\\.mods(\\..*)?")
            excludeGroupByRegex("net\\.neoforged\\.fancymodloader\\.[0-9a-f]{64}")
        }
        repositoryHost == "repo.maven.apache.org" -> content {
            excludeGroupByRegex("dev\\.architectury(\\..*)?")
            excludeGroupByRegex("net\\.fabricmc(\\..*)?")
            excludeGroupByRegex("com\\.mojang(\\..*)?")
            excludeGroupByRegex("net\\.minecraftforge(\\..*)?")
            excludeGroupByRegex("de\\.oceanlabs\\.mcp(\\..*)?")
            excludeGroupByRegex("org\\.spongepowered(\\..*)?")
            excludeGroupByRegex("net\\.neoforged(\\..*)?")
            excludeGroupByRegex("remapped\\..+")
            excludeGroup("loom")
            excludeGroup("net.minecraft")
        }
        else -> throw GradleException(
            "Unapproved remote dependency repository for $path: $url. " +
                "Review its ownership and add a narrow content filter before using it."
        )
    }
}
