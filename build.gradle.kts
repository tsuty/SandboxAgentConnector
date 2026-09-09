plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.10.1"
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
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
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
        ides {
            // Lower bound: matches platformVersion / pluginSinceBuild=242.
            create("IC", "2024.2.5")
            // Upper bound: latest release available from printProductsReleases.
            create("IC", "2025.2.6")
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
