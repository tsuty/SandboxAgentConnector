plugins {
    kotlin("jvm") version "2.4.10"
}

group = "com.github.tsuty.remote-agent-connector.jetbrains"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(26)
}

tasks.test {
    useJUnitPlatform()
}