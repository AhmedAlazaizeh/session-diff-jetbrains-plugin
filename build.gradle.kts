plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

dependencies {
    intellijPlatform {
        intellijIdea("2026.1")
    }
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "261"
            untilBuild = "262.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}
