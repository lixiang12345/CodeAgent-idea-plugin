import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "CodeAgent"

pluginManagement {
    plugins {
        id("com.google.protobuf") version "0.9.5"
        id("org.jetbrains.kotlin.jvm") version "2.1.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
