package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import dev.ruleblend.app.App
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.CliLaunch
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.app.util.DesktopActions
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.TranslationStatus
import dev.ruleblend.core.translate.Translator
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each invocation is a fresh worker JVM. The runner invokes first/restart with one owned root. */
class RootSmokeTest {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private var step = "startup"
    private val ports = RecordingDesktop(root)

    private val environment = TestRule { base, _ ->
        object : Statement() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun evaluate() {
                assertEquals(home.toString(), System.getProperty("user.home"))
                assertEquals(home.toString(), System.getenv("HOME"))
                assertEquals(root.resolve("work").toRealPath(), Path.of("").toRealPath())
                assertEquals(root.fileName.toString(), root.resolve(".ruleblend-e2e").readText().trim())
                val previous = System.getSecurityManager()
                val previousPorts = DesktopEnvironment.actions
                // The worker is pinned to JDK 17. Guard every JVM write, including third-party code.
                System.setSecurityManager(SandboxWrites(root))
                DesktopEnvironment.actions = ports
                try {
                    base.evaluate()
                } finally {
                    DesktopEnvironment.actions = previousPorts
                    System.setSecurityManager(previous)
                }
            }
        }
    }
    // Diagnostics run inside Compose's lifetime, before its rule disposes the scene.
    private val diagnostics = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    artifacts.resolve("failure.txt").writeText("H-01/H-02 step=$step\n${failure.stackTraceToString()}")
                    val config = home.resolve(".ruleblend/config.json")
                    artifacts.resolve("actual-config.txt").writeText(if (Files.exists(config)) config.readText() else "MISSING")
                    runCatching { artifacts.resolve("semantics.txt").writeText(compose.onRoot().printToString()) }
                        .onFailure { artifacts.resolve("semantics-error.txt").writeText(it.toString()) }
                    runCatching { screenshot("failure") }
                        .onFailure { artifacts.resolve("screenshot-error.txt").writeText(it.toString()) }
                    throw failure
                }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(environment).around(compose).around(diagnostics)

    @Test
    fun H01_H02_rootNavigationAndColdRestart() {
        migrateLegacyHome()
        val config = home.resolve(".ruleblend/config.json")
        artifacts.resolve("expected-state.txt").writeText("Stage: $stage\nProject in config and UI: $project\nNeighbor unchanged\n")
        artifacts.resolve("pid.txt").writeText(ProcessHandle.current().pid().toString())
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                App(openConfigStore(), onThemeModeChanged = {}, createTranslator = { FixtureTranslator })
            }
        }
        for (route in listOf("home", "place", "library", "coverage", "settings")) {
            step = "navigate:$route"
            compose.onNodeWithTag("nav-$route").performClick()
            compose.onNodeWithTag("nav-$route").assertIsSelected()
            compose.onNodeWithTag("route-$route").assertIsDisplayed()
            screenshot(route)
        }
        step = "reveal-config-port"
        compose.onNodeWithTag("settings-reveal-config").performClick()
        assertTrue(ports.calls.contains("reveal:$config"))
        compose.onNodeWithTag("nav-place").performClick()
        if (stage == "first") {
            step = "cancel-folder-picker"
            compose.onNodeWithTag("place-add-project").performClick()
            assertTrue(!Files.exists(config) || !config.readText().contains(project.projectJsonText()))
            step = "add-project"
            ports.nextDirectory = project
            compose.onNodeWithTag("place-add-project").performClick()
            compose.waitUntil(15_000) { Files.exists(config) && config.readText().contains(project.projectJsonText()) }
            assertEquals(listOf("directory:Add project", "directory:Add project"), ports.calls.filter { it.startsWith("directory:") })
        }
        step = "persisted-project"
        // Raw JSON checked by the outer runner as well; no renderer is reused for expectations.
        assertTrue(config.readText().contains(project.projectJsonText()))
        compose.onNodeWithTag("place-filter").performTextInput("Проект A")
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")).assertIsDisplayed()
        screenshot("projects")
        artifacts.resolve("semantics.txt").writeText(compose.onRoot().printToString())
        artifacts.resolve("ports.txt").writeText(ports.calls.joinToString("\n"))
        artifacts.resolve("config.json").writeText(config.readText())
        step = "completed"
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            val data = requireNotNull(image.encodeToData())
            data.use { Files.write(artifacts.resolve("$name.png"), it.bytes) }
        }
    }
}

internal class RecordingDesktop(private val root: Path) : DesktopActions {
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    var nextDirectory: Path? = null
    var nextArchive: Path? = null
    var nextEditor: Path? = null
    private fun checked(path: Path?): Path? = path?.also { SandboxWrites.requireInside(root, it) }
    override fun directory(title: String, initialDirectory: Path?): Path? {
        calls += "directory:$title"
        checked(initialDirectory)
        return checked(nextDirectory).also { nextDirectory = null }
    }
    override fun archive(mode: Int, title: String, defaultName: String?): Path? {
        calls += "archive:$mode:$title:$defaultName"
        return checked(nextArchive).also { nextArchive = null }
    }
    override fun editor(title: String): Path? {
        calls += "editor:$title"
        return checked(nextEditor).also { nextEditor = null }
    }
    override fun reveal(path: Path) { checked(path); calls += "reveal:$path" }
    override fun openUrl(url: String) { calls += "url:$url" }
    override fun edit(path: Path, editor: Path?) { checked(path); checked(editor); calls += "edit:$path:$editor" }
    override fun probe(executable: String): CliProbe {
        calls += "probe:$executable"
        return CliProbe(root.resolve("bin/$executable").toString(), false)
    }
    override fun launch(request: CliLaunch) {
        checked(request.directory); checked(request.launcherDirectory); checked(request.terminalApp)
        calls += "launch:${request.agentId}:${request.directory}:${request.command}:${request.terminalApp}"
    }
}

private object FixtureTranslator : Translator {
    override val available = true
    override suspend fun status(from: String, to: String, quality: TranslationQuality) = TranslationStatus.INSTALLED
    override suspend fun languages(quality: TranslationQuality) = listOf("en", "ru")
    override suspend fun translate(texts: List<String>, from: String, to: String, quality: TranslationQuality) =
        texts.map { "fixture:$it" }
}
