import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    compilerOptions {
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    implementation(libs.jsch)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2023.3.3") {
            useInstaller = false
        }
        jetbrainsRuntime()
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    publishing {
        token = System.getenv("JB_MARKETPLACE_TOKEN")
    }

    // Plugin Marketplace downloads (incl. recommended IDEs for the verifier) can be blocked in
    // some regions (HTTP 451). Point the verifier at a locally installed IDE instead: pass
    // -PverifierIde=/path/to/IDE.app/Contents, or rely on the default install location below.
    pluginVerification {
        val verifierIde = ((providers.gradleProperty("verifierIde").orNull
            ?: (System.getProperty("user.home") + "/Applications/IntelliJ IDEA.app/Contents")))
        if (file(verifierIde).exists()) {
            ides {
                local(file(verifierIde))
            }
        }
    }
}

// Integration tests live in a dedicated source set (src/integrationTest/kotlin). They spin up
// real containers and are therefore NOT part of the default `test` task; run them manually with
// a local container engine (Docker/Podman):
//   ./gradlew integrationTest
// The container image is used as-is when present locally, otherwise built from a Dockerfile:
//   -PrsyncImage=...        (default "alpine_rsync_ssh:1.0,alpine_rsync_ssh:latest")
//   -PrsyncDockerfile=...   (default docker/alpine_rsync_ssh/Dockerfile)
val integration = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations.named("testImplementation")) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations.named("testRuntimeOnly")) }
}

dependencies {
    add("integrationTestImplementation", libs.testcontainers)
    add("integrationTestImplementation", kotlin("stdlib"))
}

val integrationTest = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Manual integration tests backed by a Docker/Podman container."

    testClassesDirs = integration.output.classesDirs
    classpath = integration.runtimeClasspath

    systemProperty(
        "rsync.dockerfile",
        providers.gradleProperty("rsyncDockerfile")
            .getOrElse(rootProject.file("docker/alpine_rsync_ssh/Dockerfile").absolutePath),
    )
    systemProperty(
        "rsync.image",
        providers.gradleProperty("rsyncImage").getOrElse("alpine_rsync_ssh:1.0,alpine_rsync_ssh:latest"),
    )

    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}