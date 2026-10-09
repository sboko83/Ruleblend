plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(libs.mcp.sdk.server)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // JGit logs via slf4j; a no-op binding keeps stderr quiet and stdout untouched —
            // in --mcp mode stdout belongs to the protocol.
            runtimeOnly(libs.slf4j.nop)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.jgit)
        }
    }
}
