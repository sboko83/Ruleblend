plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * Warnings are a gate on CI, not a local nuisance. What the compiler warns about here is mostly code
 * a future Kotlin will refuse outright — a non-local return from an expression body, an exhaustive
 * `when` with a leftover `else` — so a branch that adds one has to say so before it lands. Locally
 * the build stays warning-tolerant: run `./gradlew -PwarningsAsErrors=true build` to see the gate.
 */
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(
            providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(false),
        )
    }
}
