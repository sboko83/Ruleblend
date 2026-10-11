pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ruleblend"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        exclusiveContent {
            forRepository { maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") }
            filter { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

include(":core")
include(":mcp")
include(":app")
