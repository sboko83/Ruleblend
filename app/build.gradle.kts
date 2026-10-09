import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.nio.file.Files
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Single source of truth for the displayed version. These feed both the native distribution
// below and the generated BuildInfo.kt consumed at runtime (e.g. the Settings tab footer).
val appVersion = "0.5.0"
val appBuild = "261"

// MSI compares only three numeric fields. Keep builds increasing across patch releases.
val windowsVersion = appVersion.split('.').take(2).plus(appBuild).joinToString(".")

val generatedBuildInfoDir = layout.buildDirectory.dir("generated/buildInfo/kotlin")

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val version = appVersion
    val build = appBuild
    val outputDir = generatedBuildInfoDir
    inputs.property("appVersion", version)
    inputs.property("appBuild", build)
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile.resolve("dev/ruleblend/app/build/BuildInfo.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            |package dev.ruleblend.app.build
            |
            |/** Generated from app/build.gradle.kts — do not edit by hand. */
            |object BuildInfo {
            |    const val VERSION = "$version"
            |    const val BUILD = "$build"
            |}
            """.trimMargin()
        )
    }
}

// The translation panel talks to the system Translation framework through a small Swift helper.
// It is built from source and shipped inside the bundle so nothing has to be resolved from PATH,
// which the app does not inherit when it is launched from Finder.
val isMacOs = System.getProperty("os.name").startsWith("Mac")
val helperDir = rootProject.layout.projectDirectory.dir("tools/translate-helper")
val helperBinary = helperDir.file(".build/release/rb-translate")
val appResourcesDir = layout.buildDirectory.dir("appResources")
val copyLicenseNotices = tasks.register<Sync>("copyLicenseNotices") {
    from(rootProject.layout.projectDirectory) {
        include("LICENSE", "NOTICE", "THIRD-PARTY.md", "licenses/**")
    }
    from(System.getProperty("java.home")) {
        include("NOTICE", "release")
        into("java-runtime")
    }
    into(appResourcesDir.map { it.dir("common/legal") })
}

val writeRuntimeDependencyManifest = tasks.register("writeRuntimeDependencyManifest") {
    group = "verification"
    description = "Lists resolved third-party runtime coordinates for the license audit."
    val runtime = configurations.named("jvmRuntimeClasspath")
    val report = layout.buildDirectory.file("reports/runtime-dependencies.txt")
    inputs.files(runtime)
    outputs.file(report)
    doLast {
        val coordinates = runtime.get().incoming.artifacts.artifacts.mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
                "${it.group}:${it.module}:${it.version}"
            }
        }.distinct().sorted()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(coordinates.joinToString("\n", postfix = "\n"))
        }
    }
}

val macResourceDir = if (System.getProperty("os.arch") == "x86_64") "macos-x64" else "macos-arm64"

val buildTranslateHelper = tasks.register<Exec>("buildTranslateHelper") {
    onlyIf { isMacOs }
    workingDir = helperDir.asFile
    commandLine("swift", "build", "-c", "release")
    inputs.dir(helperDir.dir("Sources"))
    inputs.file(helperDir.file("Package.swift"))
    outputs.file(helperBinary)
}

val copyTranslateHelper = tasks.register<Copy>("copyTranslateHelper") {
    onlyIf { isMacOs }
    dependsOn(buildTranslateHelper)
    from(helperBinary)
    into(appResourcesDir.map { it.dir(macResourceDir) })
    filePermissions { unix("755") }
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    val utf8LauncherScript = rootProject.file("mcp/src/jvmMain/resources/dev/ruleblend/mcp/EnableUtf8Launcher.ps1")
    inputs.file(utf8LauncherScript)
    inputs.dir(appResourcesDir)
    doLast {
        val variant = if (name == "createReleaseDistributable") "main-release" else "main"
        if (isMacOs) {
            val bundled = layout.buildDirectory.file("compose/binaries/$variant/app/Ruleblend.app/Contents/app/resources/rb-translate").get().asFile
            require(bundled.exists()) { "Packaged translation helper is missing" }
            require(bundled.setExecutable(true, false)) { "Packaged translation helper is not executable" }
        }
        if (System.getProperty("os.name").startsWith("Windows")) {
            // jpackage otherwise converts paths and arguments through the machine's ANSI code page.
            val binary = layout.buildDirectory.file("compose/binaries/$variant/app/Ruleblend/Ruleblend.exe").get().asFile
            check(binary.setWritable(true)) { "Packaged Windows launcher is not writable" }
            val powershell = File(System.getenv("SystemRoot"), "System32/WindowsPowerShell/v1.0/powershell.exe")
            val log = layout.buildDirectory.file("windows-launcher-$variant.log").get().asFile
            val process = ProcessBuilder(powershell.path, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-File", utf8LauncherScript.path, "-Path", binary.path)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(30, TimeUnit.SECONDS)) { "Windows launcher preparation timed out" }
                check(process.exitValue() == 0) { "Windows launcher preparation failed: ${log.readText()}" }
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(copyTranslateHelper, copyLicenseNotices) }

if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.withType<AbstractJPackageTask>().matching {
        it.name == "packageMsi" || it.name == "packageReleaseMsi"
    }.configureEach {
        val release = name == "packageReleaseMsi"
        val imageTask = tasks.named(if (release) "createReleaseDistributable" else "createDistributable")
        dependsOn(imageTask)
        // Compose otherwise builds a fresh launcher, losing the UTF-8 manifest prepared above.
        appImage.set(layout.buildDirectory.dir("compose/binaries/${if (release) "main-release" else "main"}/app/Ruleblend"))
    }
}

kotlin {
    jvmToolchain(17)

    jvm {
        val main = compilations.getByName("main")
        val uiE2e = compilations.create("uiE2e") {
            associateWith(main)
            defaultSourceSet.kotlin.srcDir("src/uiE2e/kotlin")
            defaultSourceSet.dependencies {
                implementation(libs.compose.ui.test.junit4)
                implementation(libs.jgit)
                implementation(libs.kaml)
                implementation(libs.kotlinx.serialization.json)
                implementation(kotlin("test"))
            }
        }
        // Deliberately not registered as a Kotlin test run: allTests/check must never discover it.
        tasks.register<Test>("uiE2e") {
            description = "Opt-in root UI scenarios; use tools/ui_e2e.py for isolation and reports"
            group = "verification"
            testClassesDirs = uiE2e.output.classesDirs
            classpath = uiE2e.output.allOutputs + uiE2e.runtimeDependencyFiles
            javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
            filter.isFailOnNoMatchingTests = true
            maxParallelForks = 1
            forkEvery = 1
            outputs.upToDateWhen { false }
            val reportRoot = providers.gradleProperty("e2eRoot")
            val reportStage = providers.gradleProperty("e2eStage").orElse("first")
            reports.junitXml.outputLocation.set(layout.dir(reportRoot.zip(reportStage) { root, stage -> file("$root/results/$stage") }))
            reports.html.outputLocation.set(layout.dir(reportRoot.zip(reportStage) { root, stage -> file("$root/reports/$stage") }))
            binaryResultsDirectory.set(layout.dir(reportRoot.zip(reportStage) { root, stage -> file("$root/results/$stage/binary") }))
            doFirst {
                val root = file(providers.gradleProperty("e2eRoot").get()).canonicalFile
                require(root.name.startsWith("ruleblend-e2e-") &&
                    !Files.isSymbolicLink(root.resolve(".ruleblend-e2e").toPath()) &&
                    root.resolve(".ruleblend-e2e").readText().trim() == root.name) { "Unowned E2E root" }
                val stage = providers.gradleProperty("e2eStage").getOrElse("first")
                require(stage in listOf("first", "restart", "settled", "manual-restart", "auto-restart", "setup")) { "Unknown E2E stage" }
                val home = root.resolve("home")
                val windows = System.getProperty("os.name").startsWith("Windows")
                val scriptSuffix = if (windows) ".cmd" else ""
                workingDir(root.resolve("work"))
                // Replace, do not merge: inherited credentials and shell overrides are not fixtures.
                val isolatedEnvironment = mapOf(
                    "HOME" to home.path,
                    "PATH" to root.resolve("bin").path,
                    "SHELL" to root.resolve("bin/shell$scriptSuffix").path,
                    "TMPDIR" to root.resolve("tmp").path,
                    "CLAUDE_CONFIG_DIR" to home.resolve(".claude").path,
                    "CODEX_HOME" to home.resolve(".codex").path,
                    "KIMI_CODE_HOME" to home.resolve(".kimi").path,
                    "PI_CODING_AGENT_DIR" to home.resolve(".pi/agent").path,
                    "RULEBLEND_LIBRARY" to home.resolve(".ruleblend/library").path,
                    "RULEBLEND_TRANSLATE_HELPER" to root.resolve("bin/rb-translate$scriptSuffix").path,
                    "XDG_CONFIG_HOME" to home.resolve(".config").path,
                    "GIT_CONFIG_GLOBAL" to home.resolve(".gitconfig").path,
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "GIT_TERMINAL_PROMPT" to "0",
                    "LANG" to "en_US.UTF-8",
                ) + listOf(
                    "RULEBLEND_E2E_MCP_BINARY" to "e2eMcpBinary",
                    "RULEBLEND_E2E_REAL_HOME" to "e2eRealHome",
                )
                    .mapNotNull { (key, property) -> providers.gradleProperty(property).orNull?.let { key to it } }
                    .toMap()
                val windowsEnvironment = if (windows) {
                    listOf("SystemRoot", "SystemDrive", "WINDIR", "COMSPEC", "PATHEXT")
                        .mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap() + mapOf(
                            "USERPROFILE" to home.path,
                            "APPDATA" to home.resolve("AppData/Roaming").path,
                            "LOCALAPPDATA" to home.resolve("AppData/Local").path,
                            "TEMP" to root.resolve("tmp").path,
                            "TMP" to root.resolve("tmp").path,
                        )
                } else emptyMap()
                setEnvironment(isolatedEnvironment + windowsEnvironment)
                systemProperty("user.home", home.path)
                systemProperty("java.io.tmpdir", root.resolve("tmp").path)
                systemProperty("ruleblend.e2e.root", root.path)
                systemProperty("ruleblend.e2e.stage", stage)
                // Registration scenarios use a fixture; native launcher execution has its own tests.
                if (windows) systemProperty("jpackage.app-path", root.resolve("bin/ruleblend-mcp.cmd").path)
                systemProperty("java.awt.headless", "true")
                systemProperty("skiko.renderApi", "SOFTWARE")
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.components.resources)
        }

        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(project(":mcp"))
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }

        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.ui.test.junit4)
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "dev.ruleblend.app.generated.resources"
}

afterEvaluate {
    kotlin.sourceSets.getByName("jvmMain").kotlin.srcDir(generatedBuildInfoDir)
    tasks.named("compileKotlinJvm").configure { dependsOn(generateBuildInfo) }
}

compose.desktop {
    application {
        mainClass = "dev.ruleblend.app.MainKt"
        // Direct main-class runs are bare Java processes. Keep their best-effort identity flags, but
        // use the app-bundle-backed development task below for the reliable LaunchServices identity.
        jvmArgs += "-Dapple.awt.application.name=Ruleblend"

        nativeDistributions {
            // Resolved at runtime through the `compose.application.resources.dir` system property,
            // which Compose sets both for `run` and for the packaged app.
            appResourcesRootDir.set(appResourcesDir)
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Ruleblend"
            packageVersion = appVersion
            modules("java.management")
            if (System.getProperty("os.name").startsWith("Windows")) {
                modules("java.instrument", "java.security.jgss", "java.sql", "jdk.unsupported")
            }

            windows {
                packageVersion = windowsVersion
                // Keep the install path and UpgradeCode stable across every release.
                upgradeUuid = "13b57462-4c83-4b36-982a-cbe5166e6fba"
                installationPath = "Ruleblend"
                dirChooser = false
                perUserInstall = true
                menu = true
                menuGroup = "Ruleblend"
                shortcut = true
                iconFile.set(project.file("src/jvmMain/resources/icons/AppIcon.ico"))
            }

            macOS {
                // jpackage rejects a leading-zero app version before it reads the supplied plist.
                // Feed it the numeric build, then let the later duplicate plist key restore the
                // user-facing pre-1.0 version; macOS resolves duplicate dictionary keys to the last value.
                packageVersion = appBuild
                packageBuildVersion = appBuild
                dockName = "Ruleblend"
                iconFile.set(project.file("src/jvmMain/resources/icons/AppIcon.icns"))
                bundleID = "dev.ruleblend.app"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleShortVersionString</key>
                        <string>$appVersion</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// macOS derives the Dock/app-switcher identity from an application bundle. Make the ordinary
// development entrypoint launch that bundle too; a direct JavaExec remains named "java" on current
// macOS even when -Xdock:name is present.
if (isMacOs) {
    tasks.matching { it.name == "run" }.configureEach {
        enabled = false
        dependsOn("runDistributable")
    }
}
