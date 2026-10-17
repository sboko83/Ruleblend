package dev.ruleblend.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.App
import dev.ruleblend.app.assistance.AssistanceLaunch
import dev.ruleblend.app.assistance.AssistanceObject
import dev.ruleblend.app.assistance.AssistanceSessions
import dev.ruleblend.app.assistance.AssistanceTerminal
import dev.ruleblend.app.assistance.LocalAssistanceSessions
import dev.ruleblend.app.assistance.LocalAssistanceTerminalView
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.app.util.DesktopActions
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JPanel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Full root flow, isolated profiles and a controllable offline terminal; native input is separate. */
class AssistanceUiTest {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val artifacts = Files.createDirectories(root.resolve("artifacts/assistance-${System.getProperty("ruleblend.e2e.stage")}"))
    private val compose = createComposeRule()
    private val children = CopyOnWriteArrayList<FakeTerminal>()
    private val launches = CopyOnWriteArrayList<AssistanceLaunch>()
    @Volatile private var nextDirectory: Path? = null
    /** Holds the next terminal start, so the starting panel stays observable until it opens. */
    @Volatile private var startGate: CountDownLatch? = null
    private var theme by mutableStateOf(ThemeMode.LIGHT)
    @Volatile private var surface = 0
    private val sessions = AssistanceSessions { launch ->
        launches += launch
        startGate?.let { gate -> startGate = null; check(gate.await(30, TimeUnit.SECONDS)) }
        FakeTerminal().also(children::add)
    }
    private class FakeTerminal : AssistanceTerminal {
        override val component = JPanel()
        private val exit = CountDownLatch(1)
        override val isAlive get() = exit.count != 0L
        override fun waitForExit(): Int { check(exit.await(60, TimeUnit.SECONDS)); return 0 }
        override fun focus() = Unit
        override fun close() { exit.countDown() }
        fun finish() { exit.countDown() }
    }
    private val environment = TestRule { base, _ -> object : Statement() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun evaluate() {
            val security = System.getSecurityManager()
            val actions = DesktopEnvironment.actions
            System.setSecurityManager(SandboxWrites(root))
            DesktopEnvironment.actions = object : DesktopActions by actions {
                override fun probe(executable: String) = CliProbe(root.resolve("bin/$executable").toString(), true, root.resolve("bin").toString())
                override fun directory(title: String, initialDirectory: Path?) = nextDirectory.also { nextDirectory = null }
            }
            try { base.evaluate() } finally {
                runBlocking { sessions.shutdown() }
                DesktopEnvironment.actions = actions
                System.setSecurityManager(security)
            }
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose)

    private fun present(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    private fun select(id: String) {
        compose.onNodeWithTag("nav-library").performClick()
        present("library-search")
        compose.onNodeWithTag("library-search").performTextReplacement("")
        val row = uiTarget("library", "RULE:$id", "catalog", "select")
        present(row)
        compose.onNodeWithTag(row).performClick()
        present(uiTarget("library", "RULE:$id", "inspector", "selected"))
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onAllNodes(isRoot()).onFirst().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            image.encodeToData()!!.use { Files.write(artifacts.resolve("$name.png"), it.bytes) }
        }
    }
    private fun settings(language: Language, mode: ThemeMode) {
        compose.onNodeWithTag("nav-settings").performClick()
        compose.onNodeWithTag("settings-nav-general").performClick()
        present("settings-panel-general")
        compose.onNodeWithTag("settings-language-${language.name.lowercase()}").performClick()
        compose.onNodeWithTag("settings-theme-${mode.name.lowercase()}").performClick()
        compose.waitUntil(15_000) {
            theme == mode && openConfigStore().load().let { it.themeMode == mode && it.language == language.name.lowercase() }
        }
    }

    @Test fun A01_settingsLaunchNavigationReloadAndConfirmedClose() {
        val config = openConfigStore()
        val restart = System.getProperty("ruleblend.e2e.stage") == "restart"
        if (!restart) config.update { it.copy(assistanceAssistants = emptyList(), hiddenAgents = emptyList(), language = "en") }
        val library = root.resolve("home/.ruleblend/library")
        val repository = LibraryRepository(library).also { it.init() }
        repository.saveBlock(Block(id = "assistance-one", name = "Assistance one", content = "Initial saved body"))
        repository.saveBlock(Block(id = "assistance-two", name = "Assistance two", content = "Second body"))
        compose.setContent {
            RuleblendTheme(theme) {
                val scheme = MaterialTheme.colorScheme
                SideEffect { surface = scheme.surface.toArgb() and 0xFFFFFF }
                CompositionLocalProvider(
                    LocalAssistanceSessions provides sessions,
                    LocalAssistanceTerminalView provides { _: AssistanceTerminal, modifier: Modifier ->
                        Box(modifier.fillMaxSize()) { Text("Offline terminal · editable CLI draft") }
                    },
                ) { App(config, onThemeModeChanged = { theme = it }) }
            }
        }
        present("nav-library")
        select("assistance-one")
        if (restart) {
            assertEquals(listOf("claude-code"), config.load().assistanceAssistants)
            compose.onNodeWithTag("assistance-open").performClick()
            present("assistance-terminal")
            assertEquals(1, launches.size)
            children.single().finish()
            compose.waitUntil(5_000) { sessions.state.value.session?.exitCode == 0 }
            compose.onNodeWithTag("assistance-close").performClick()
            compose.waitUntil(5_000) { sessions.state.value.session == null }
            return
        }
        compose.onNodeWithTag("assistance-open").performClick()
        present("settings-panel-agents")
        val toggle = compose.onNodeWithTag("settings-assistance-claude-code")
        toggle.performScrollTo()
        toggle.assertIsNotEnabled()
        assertTrue(config.load().assistanceAssistants.isEmpty())
        compose.onNodeWithTag("settings-connect-claude-code").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            runCatching { toggle.assertIsNotEnabled() }.isFailure
        }
        toggle.performScrollTo().performClick()
        compose.waitUntil(5_000) { config.load().assistanceAssistants == listOf("claude-code") }
        select("assistance-one")
        compose.onNodeWithTag("assistance-open").performClick()
        present("assistance-terminal")
        assertEquals(1, launches.size)
        assertEquals(listOf(launches.single().assistant.cli.path, "--prefill", launches.single().target.draft(Language.EN)), launches.single().command)
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("assistance-panel").assertIsDisplayed()
        select("assistance-one")
        compose.onNodeWithTag("assistance-open").performClick()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        repository.saveBlock(Block(id = "assistance-one", name = "Assistance updated", content = "Written by external MCP"))
        compose.waitUntil(8_000) {
            compose.onAllNodesWithTag(uiTarget("library", "RULE:assistance-one", "inspector", "selected")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(androidx.compose.ui.test.hasText("Assistance updated")).fetchSemanticsNodes().isNotEmpty()
        }
        select("assistance-two")
        compose.onNodeWithTag("assistance-open").performClick()
        present("dialog-confirm")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        compose.waitForIdle()
        assertEquals(1, launches.size)
        assertTrue(children.single().isAlive)
        compose.onNodeWithTag("assistance-open").performClick()
        present("dialog-confirm")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { launches.size == 2 }
        assertFalse(children.first().isAlive)
        compose.onNodeWithTag("assistance-panel").assertIsDisplayed()
        screenshot("assistance-panel")
        compose.onNodeWithTag("assistance-close").performClick()
        present("dialog-confirm")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        compose.waitForIdle()
        assertTrue(children.last().isAlive)
        children.last().finish()
        compose.waitUntil(5_000) { sessions.state.value.session?.exitCode == 0 }
        compose.onNodeWithTag("assistance-close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("assistance-panel").fetchSemanticsNodes().isEmpty() }
        assertTrue(children.none { it.isAlive })

        // Place entry point: each menu item starts one session with its starting text in the UI language,
        // and the terminal takes the app theme of the click; one start is held to observe the starting panel.
        val project = Files.createDirectories(root.resolve("work/orbit-api")).toRealPath()
        compose.onNodeWithTag("nav-place").performClick()
        nextDirectory = project
        present("place-add-project")
        compose.onNodeWithTag("place-add-project").performClick()
        val place = "project:${project.projectKey()}"
        val recent = uiTarget("place", place, "recent", "select")
        val ungrouped = uiTarget("place", place, "ungrouped", "select")
        val drafts = mapOf(
            Language.EN to listOf(
                "Project orbit-api ($project). I need help with ",
                "Let's pick a profile for the project orbit-api ($project). " +
                    "Use Ruleblend MCP and the ruleblend skill. Let's discuss first.",
            ),
            Language.RU to listOf(
                "Проект orbit-api ($project). Нужна помощь с ",
                "Подберём профиль для проекта orbit-api ($project). " +
                    "Используй Ruleblend MCP и skill ruleblend. Сначала обсудим.",
            ),
        )
        val backgrounds = mutableMapOf<Boolean, Int>()
        for ((language, mode) in listOf(Language.EN to ThemeMode.LIGHT, Language.RU to ThemeMode.DARK)) {
            settings(language, mode)
            compose.onNodeWithTag("nav-place").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag(ungrouped).fetchSemanticsNodes().isNotEmpty()
            }
            val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent else ungrouped
            compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
            compose.onNodeWithTag(row).performClick()
            present("assistance-open")
            // The header wraps the AI help button instead of squeezing the assistants summary.
            val summary = compose.onNodeWithTag("place-assistants-summary").getUnclippedBoundsInRoot()
            val configure = compose.onNodeWithTag("place-assistants-configure").getUnclippedBoundsInRoot()
            assertTrue(configure.width > 40.dp, "configure button squeezed to ${configure.width}")
            assertTrue(summary.height < 60.dp, "assistants summary wrapped to ${summary.height}")
            for ((index, item) in listOf("assistance-ask", "assistance-profile").withIndex()) {
                val held = mode == ThemeMode.DARK && index == 1
                val gate = CountDownLatch(1).takeIf { held }
                startGate = gate
                val before = launches.size
                compose.onNodeWithTag("assistance-open").performClick()
                present(item)
                if (index == 0) screenshot("place-menu-${language.name.lowercase()}")
                compose.onNodeWithTag(item).performClick()
                if (gate != null) {
                    present("assistance-starting")
                    compose.waitUntil(5_000) { launches.size == before + 1 }
                    assertTrue(compose.onAllNodesWithTag("assistance-terminal").fetchSemanticsNodes().isEmpty())
                    screenshot("assistance-starting")
                    gate.countDown()
                }
                compose.waitUntil(5_000) { launches.size == before + 1 }
                present("assistance-terminal")
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("assistance-starting").fetchSemanticsNodes().isEmpty() }
                val launch = launches.last()
                assertEquals(listOf(launch.assistant.cli.path, "--prefill", drafts.getValue(language)[index]), launch.command)
                assertEquals(project, launch.directory.toRealPath())
                assertEquals(if (index == 0) AssistanceObject.Kind.PROJECT else AssistanceObject.Kind.PROFILE, launch.target.kind)
                assertEquals(mode == ThemeMode.DARK, launch.colors.dark)
                assertEquals(surface, launch.colors.background)
                backgrounds[launch.colors.dark] = launch.colors.background
                if (held) screenshot("assistance-dark")
                assertTrue(children.last().isAlive)
                children.last().finish()
                compose.waitUntil(5_000) { sessions.state.value.session?.exitCode == 0 }
                compose.onNodeWithTag("assistance-close").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("assistance-panel").fetchSemanticsNodes().isEmpty() }
            }
        }
        assertNotEquals(backgrounds.getValue(false), backgrounds.getValue(true))
        settings(Language.EN, ThemeMode.LIGHT)
    }
}
