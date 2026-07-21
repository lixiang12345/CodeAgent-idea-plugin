import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

val grpcVersion = "1.73.0"
val protobufVersion = "4.33.5"
val configuredVerifierIdePaths = providers.gradleProperty("codeagentVerifierIdePaths")
    .orNull
    ?.split(',', ';')
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    .orEmpty()
val supportedLocalIdeNames = setOf(
    "PyCharm.app",
    "WebStorm.app",
    "CLion.app",
    "GoLand.app",
    "PhpStorm.app",
    "Rider.app",
)

fun discoverLocalVerifierIdePaths(): List<String> {
    val home = System.getProperty("user.home")
    val roots = listOf(
        file("/Applications"),
        file("$home/Applications"),
        file("$home/Library/Application Support/JetBrains/Toolbox/apps"),
    )
    return roots
        .filter(File::isDirectory)
        .flatMap { root ->
            root.walkTopDown()
                .maxDepth(8)
                .filter { candidate -> candidate.isDirectory && candidate.name in supportedLocalIdeNames }
                .map(File::getAbsolutePath)
                .toList()
        }
}

val localVerifierIdePaths = (
    configuredVerifierIdePaths + discoverLocalVerifierIdePaths()
).distinct()

dependencies {
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("com.google.protobuf:protobuf-java:$protobufVersion")
    implementation("javax.annotation:javax.annotation-api:1.3.2")
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

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
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
    pluginVerification {
        ides {
            current()
            localVerifierIdePaths
                .map(::file)
                .filter { it.isDirectory }
                .forEach(::local)
        }
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
    inputs.file("src/main/proto/com/codeagent/plugin/context/context_engine.proto")
    outputs.files(
        "sidecar/dist/server.mjs",
        "sidecar/dist/grpc-server.mjs",
        "sidecar/dist/context-engine.proto",
    )
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
        from("sidecar/dist/grpc-server.mjs") {
            into("sidecar")
        }
        from("sidecar/dist/context-engine.proto") {
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
