plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
    }
}

kotlin {
    jvmToolchain(21)
}
