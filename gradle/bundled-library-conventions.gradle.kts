import org.gradle.api.tasks.bundling.Jar

// A release JAR holds only Quick Skin's own packages. FML loads a mod JAR as one Java module, and
// the module layer fails to resolve as soon as two modules export the same package, so a library
// kept under its upstream package collides with every other mod that ships it, merged into that
// mod or nested as its own module. On Fabric the two copies would share one class path instead.
// Every external `shadowBundle` library is therefore pure Java and relocated below Quick Skin's
// package. Quick Skin calls it directly, so its ImageIO service registrations, Maven metadata and
// unused top-level resources stay out of the JAR and nothing is registered globally.
// `scripts/release/verify_release.py` rejects a production JAR that holds anything else.
val relocations = mapOf("com.twelvemonkeys" to "com.quickskin.mod.shadow.twelvemonkeys")

tasks.named<Jar>("shadowJar") {
    relocations.forEach { (library, relocated) -> withGroovyBuilder { "relocate"(library, relocated) } }
    exclude("META-INF/maven/**", "META-INF/services/javax.imageio.spi.*", "profiles/**")
    from(rootProject.file("licenses")) {
        include("*-LICENSE.txt")
        into("META-INF/licenses")
    }
}
