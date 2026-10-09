plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kaml)
        }
        jvmMain.dependencies {
            implementation(libs.jgit)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Tests that need the macOS Translation framework, a locally built helper and an installed language
// pack. None of that can be provisioned from a test, so they are kept out of the unit gate: there
// they would be flaky, and their skips would count as passes.
val macOsIntegrationSuffix = "MacOsIntegrationTest"

tasks.named<Test>("jvmTest") {
    filter {
        excludeTestsMatching("*$macOsIntegrationSuffix")
        isFailOnNoMatchingTests = false
    }
}

tasks.register<Test>("macOsIntegrationTest") {
    group = "verification"
    description = "Runs the tests that talk to the real macOS translation helper."
    val unitTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = unitTest.testClassesDirs
    classpath = unitTest.classpath
    dependsOn("jvmTestClasses")
    filter {
        includeTestsMatching("*$macOsIntegrationSuffix")
        isFailOnNoMatchingTests = false
    }
}
