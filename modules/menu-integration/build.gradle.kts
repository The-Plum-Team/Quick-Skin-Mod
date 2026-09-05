plugins {
    `java-library`
    id("dev.architectury.loom") apply false
    id("dev.architectury.loom-no-remap") apply false
}

apply(from = rootProject.file("gradle/minecraft-module-framework.gradle.kts"))
apply(from = rootProject.file("gradle/java-module-conventions.gradle.kts"))
