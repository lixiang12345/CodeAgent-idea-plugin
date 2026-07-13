import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    workingDir(layout.projectDirectory.dir("frontend"))
    commandLine("npm", "run", "build")
    inputs.files(fileTree("frontend/src"), file("frontend/index.html"), file("frontend/package.json"), file("frontend/package-lock.json"), file("frontend/vite.config.ts"))
    outputs.file("frontend/dist/index.html")
}

val buildSidecar = tasks.register<Exec>("buildSidecar") {
    workingDir(layout.projectDirectory.dir("sidecar"))
    commandLine("npm", "run", "build")
    inputs.files(fileTree("sidecar/src"), file("sidecar/package.json"), file("sidecar/package-lock.json"), file("sidecar/build.mjs"))
    inputs.files(fileTree("vendor/context-engine/src"), file("vendor/context-engine/package.json"))
    outputs.file("sidecar/dist/server.mjs")
}

tasks {
    processResources {
        dependsOn(buildFrontend, buildSidecar)
        from("frontend/dist") {
            into("web")
        }
        from("sidecar/dist/server.mjs") {
            into("sidecar")
        }
        from("vendor/context-engine/LICENSE") {
            into("third-party")
            rename { "ContextEngine-LICENSE.txt" }
        }
    }

    patchPluginXml {
        sinceBuild.set("252")
        untilBuild.set("261.*")
    }

    withType<JavaCompile>().configureEach {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    test {
        useJUnitPlatform()
    }
}

kotlin {
    jvmToolchain(21)
}
