plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2026.1")
        bundledPlugin("com.intellij.diff")
    }
}

kotlin {
    jvmToolchain(21)
}
