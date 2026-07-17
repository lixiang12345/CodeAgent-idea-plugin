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

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("252")
        }
        changeNotes.set(
            """
            <h2>${project.version}</h2>
            <ul>
              <li>Added durable tool cards, cloud conversation recovery, and live subagent output.</li>
              <li>Added managed MCP tools, slash commands, lifecycle hooks, and declarative plugin contributions.</li>
              <li>Added context-first Agent policies plus multi-root incremental ContextEngine indexing.</li>
              <li>Improved reliability with persistent notifications, activity timeline telemetry, and lazy Mermaid loading.</li>
            </ul>
            """.trimIndent(),
        )
    }
    signing {
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
        channels.set(
            providers.environmentVariable("PUBLISH_CHANNEL")
                .map { listOf(it) }
                .orElse(listOf("default")),
        )
    }
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    workingDir(layout.projectDirectory.dir("frontend"))
    commandLine("npm", "run", "build")
    inputs.files(
        fileTree("frontend/src"),
        file("frontend/index.html"),
        file("frontend/package.json"),
        file("frontend/package-lock.json"),
        file("frontend/vite.config.ts"),
        file("frontend/scripts/jcef-postbuild.mjs"),
    )
    outputs.dir("frontend/dist")
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
