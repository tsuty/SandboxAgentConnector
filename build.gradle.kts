import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )
        // Ships `org.intellij.markdown`, the parser used to render the agent answers (see MarkdownView).
        bundledPlugin("org.intellij.plugins.markdown")
        // Optional at runtime: opens the sandbox terminal (see SandboxTerminal).
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        pluginVerifier()
    }

    // kotlinx-coroutines is bundled with the IntelliJ Platform, so it must not be added here.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    testImplementation(kotlin("test"))
    // The platform's JUnit 5 session listener (TestFrameworkType.Platform) needs JUnit 4's
    // `junit.framework.TestCase`: without it no test starts on 2025.3 (NoClassDefFoundError).
    testRuntimeOnly("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    compilerOptions {
        // The Kotlin stdlib comes from the IDE at runtime (2.2.20 for 2025.3, see pluginSinceBuild):
        // newer API levels emit coroutine debug metadata it rejects ("Debug metadata version
        // mismatch"), which broke coroutine resumption in the tests once the platform's coroutines
        // debug agent is attached.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // The defaults of the Gradle plugin minus INTERNAL_API_USAGES: the plugin still relies on a
        // few internal APIs (SVGLoader, InternalDecoratorImpl, stubs Kotlin generates for
        // ToolWindowFactory), which the verifier keeps reporting without failing the build.
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            // Lower bound: matches platformVersion / pluginSinceBuild=253. Support covers the IDE
            // releases of the last year. IU: from 2026.1 on IntelliJ IDEA has no IC distribution.
            create("IU", "2025.3.6.1")
            // Upper bound: latest release.
            create("IU", "2026.2.3")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    // Internal mode: what the debug only entries of the plugin are gated on, e.g. `Show Page
    // Source` in the transcript context menu (see JcefTranscriptView).
    systemProperty("idea.is.internal", "true")
}
