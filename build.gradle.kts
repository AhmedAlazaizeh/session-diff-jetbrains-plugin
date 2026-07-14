plugins {
    kotlin("jvm") version "2.1.20"
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
