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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdeaCommunity("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}

val buildFrontend = tasks.register<Exec>("buildFrontend") {
    workingDir(layout.projectDirectory.dir("frontend"))
    commandLine("npm", "run", "build")
    inputs.files(fileTree("frontend/src"), file("frontend/index.html"), file("frontend/package.json"), file("frontend/package-lock.json"), file("frontend/vite.config.ts"))
    outputs.file("frontend/dist/index.html")
}

tasks {
    processResources {
        dependsOn(buildFrontend)
        from("frontend/dist") {
            into("web")
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
