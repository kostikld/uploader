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
        intellijIdea("2026.2.0.1") {
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