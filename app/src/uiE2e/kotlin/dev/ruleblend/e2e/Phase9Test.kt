package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.rightClick
import dev.ruleblend.app.App
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.ClipboardEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.ColumnWidths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** N-01..N-07 and N-11 drive the real root UI in a private home. Native N-08..N-10 use AX. */
class Phase9Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val library = home.resolve(".ruleblend/library")
    private val activeLibrary = home.resolve("Другая библиотека")
    private val config = home.resolve(".ruleblend/config.json")
    private val project = Path.of(root.resolve(if (stage == "setup")
        "projects/Очень длинное название проекта для узкого окна" else "projects/Проект A").projectKey())
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
    private val focusEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var theme by mutableStateOf(ThemeMode.SYSTEM)
    private var copied = ""
    private var step = "startup"

    private val environment = TestRule { base, _ -> object : Statement() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun evaluate() {
            assertEquals(home.toString(), System.getProperty("user.home"))
            assertEquals(home.toString(), System.getenv("HOME"))
            assertEquals(root.resolve("work").toRealPath(), Path.of("").toRealPath())
            assertEquals(root.fileName.toString(), root.resolve(".ruleblend-e2e").readText().trim())
            val security = System.getSecurityManager()
            val actions = DesktopEnvironment.actions
            val clipboard = ClipboardEnvironment.copy
            System.setSecurityManager(SandboxWrites(root, Path.of("/usr/bin/sandbox-exec")))
            DesktopEnvironment.actions = ports
            ClipboardEnvironment.copy = { copied = it; true }
            try { base.evaluate() } finally {
                DesktopEnvironment.actions = actions
                ClipboardEnvironment.copy = clipboard
                System.setSecurityManager(security)
            }
        }
    } }
    private val diagnostics = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                artifacts.resolve("failure.txt").writeText("$step\n${failure.stackTraceToString()}")
                artifacts.resolve("actual-config.txt").writeText(if (Files.exists(config)) config.readText() else "MISSING")
                runCatching {
                    val roots = compose.onAllNodes(isRoot())
                    artifacts.resolve("semantics.txt").writeText(
                        roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() },
                    )
                    val bitmap = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()
                    Image.makeFromBitmap(bitmap).use { image ->
                        requireNotNull(image.encodeToData()).use { Files.write(artifacts.resolve("failure.png"), it.bytes) }
                    }
                }
                throw failure
            }
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose).around(diagnostics)

    private fun start() {
        artifacts.resolve("pid.txt").writeText(ProcessHandle.current().pid().toString())
        migrateLegacyHome()
        theme = openConfigStore().load().themeMode
        compose.setContent {
            RuleblendTheme(theme) {
                App(openConfigStore(), onThemeModeChanged = { theme = it },
                    createTranslator = { NoTranslation }, focusEvents = focusEvents)
            }
        }
        present("nav-settings")
    }

    private fun saveEditor() {
        compose.onNodeWithTag("library-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
    }

    private fun present(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun fieldText(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    private fun route(name: String) {
        step = "route:$name"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun section(name: String) {
        route("settings")
        compose.onNodeWithTag("settings-nav-$name").performClick()
        present("settings-panel-$name")
    }

    private fun screenshot(name: String) {
        val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()
        Image.makeFromBitmap(bitmap).use { image ->
            requireNotNull(image.encodeToData()).use { Files.write(artifacts.resolve("$name.png"), it.bytes) }
        }
    }

    private fun addProject() {
        route("place")
        ports.nextDirectory = project
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { project.projectKey() in openConfigStore().load().projects }
    }

    private fun selectProject() {
        route("place")
        val recent = uiTarget("place", "project:${project.projectKey()}", "recent", "select")
        val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        present("place-palette-search")
    }

    private fun installRuleInProject(id: String) {
        selectProject()
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val install = uiTarget("palette", "RULE:$id", "project:${project.projectKey()}", "install")
        present(install)
        compose.onNodeWithTag(install).performClick()
        val installed = project.resolve("AGENTS.md")
        compose.waitUntil(15_000) { Files.exists(installed) && installed.readText().contains(id) }
    }

    private fun createRule(name: String, expectedLibrary: Path? = null, content: String? = null): Path {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-rule").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-rule").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(name)
        compose.onNodeWithTag("dialog-confirm").performClick()
        val id = name.lowercase().replace(' ', '-')
        val file = (expectedLibrary ?: openConfigStore().load().libraryPath?.let(Path::of) ?: library)
            .resolve("blocks/$id.md")
        compose.waitUntil(15_000) { Files.exists(file) }
        if (content != null) {
            editRule(id)
            compose.onNodeWithTag("library-editor-block-content").performTextReplacement(content)
            compose.onNodeWithTag("library-editor-save").performClick()
            compose.waitUntil(15_000) { file.readText().contains(content) }
        }
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        present(uiTarget("library", "RULE:$id", "catalog", "select"))
        return file
    }

    private fun editRule(id: String) {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "RULE:$id", "catalog", "select")
        val inspector = uiTarget("library", "RULE:$id", "inspector", "selected")
        present(row)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(row).performClick(); false }
        }
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-edit"))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-edit").performClick(); false }
        }
    }

    @Test fun N01_localesThemesAndFoldingSurviveRestart() {
        start()
        if (stage == "first") {
            addProject()
            createRule("English Smoke", content = "English smoke instruction.")
            installRuleInProject("english-smoke")
            section("general")
            for ((language, label) in listOf("en" to "Settings", "ru" to "Настройки")) {
                step = "locale:$language"
                compose.onNodeWithTag("settings-language-$language").performClick()
                compose.onNodeWithTag("nav-settings").assertTextContains(label, substring = true)
                assertEquals(language, openConfigStore().load().language)
                screenshot("locale-$language")
                if (language == "ru") {
                    createRule("Russian Smoke", content = "Russian smoke instruction.")
                    installRuleInProject("russian-smoke")
                    assertTrue(project.resolve("AGENTS.md").readText().contains("english-smoke"))
                    section("general")
                }
            }
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM, ThemeMode.DARK)) {
                step = "theme:$mode"
                compose.onNodeWithTag("settings-theme-${mode.name.lowercase()}").performClick()
                compose.waitUntil(15_000) { theme == mode && openConfigStore().load().themeMode == mode }
                screenshot("theme-${mode.name.lowercase()}")
            }
            section("folding")
            for (tag in listOf("settings-groups-collapsed", "settings-place-library-collapsed",
                "settings-place-rules-expanded", "settings-place-skills-expanded",
                "settings-place-subagents-expanded", "settings-place-mcp-expanded")) {
                compose.onNodeWithTag(tag).performClick()
            }
            assertFalse(openConfigStore().load().groupsExpandedByDefault)
            assertFalse(openConfigStore().load().placeLibraryExpandedByDefault)
            assertTrue(openConfigStore().load().placeRulesExpandedByDefault)
        } else {
            val saved = openConfigStore().load()
            assertEquals("ru", saved.language)
            assertEquals(ThemeMode.DARK, saved.themeMode)
            assertEquals(ThemeMode.DARK, theme)
            assertFalse(saved.groupsExpandedByDefault)
            assertFalse(saved.placeLibraryExpandedByDefault)
            assertTrue(saved.placeRulesExpandedByDefault && saved.placeSkillsExpandedByDefault &&
                saved.placeSubagentsExpandedByDefault && saved.placeMcpExpandedByDefault)
            for (route in listOf("home", "place", "library", "coverage", "settings")) route(route)
            screenshot("restarted-ru-dark")
        }
    }

    @Test fun N02_libraryPathUndoThenColdRestart() {
        start()
        val next = activeLibrary
        if (stage == "first") {
            val old = createRule("old rule")
            section("library")
            ports.nextDirectory = next
            compose.onNodeWithTag("settings-library-change").performClick()
            present("settings-library-undo")
            compose.onNodeWithText(EnStrings.setLibraryPathRestartHint).assertIsDisplayed()
            assertEquals(next.toString(), openConfigStore().load().libraryPath)
            compose.onNodeWithTag("settings-library-undo").performClick()
            assertEquals(null, openConfigStore().load().libraryPath)
            ports.nextDirectory = next
            compose.onNodeWithTag("settings-library-change").performClick()
            present("settings-library-undo")
            assertTrue(Files.exists(old))
            assertFalse(Files.exists(next.resolve("blocks/old-rule.md")))
            createRule("still old", expectedLibrary = library)
            assertTrue(Files.exists(library.resolve("blocks/still-old.md")))
            assertFalse(Files.exists(next.resolve("blocks/still-old.md")))
        } else {
            assertEquals(next.toString(), openConfigStore().load().libraryPath)
            route("library")
            compose.onNodeWithTag("library-search").performTextReplacement("old-rule")
            compose.onNodeWithTag(uiTarget("library", "RULE:old-rule", "catalog", "select")).assertDoesNotExist()
            val current = createRule("new rule")
            assertEquals(next.resolve("blocks/new-rule.md"), current)
            assertTrue(Files.exists(current))
            assertTrue(Files.exists(library.resolve("blocks/old-rule.md")))
        }
    }

    @Test fun N03_nameFormatColumnsAndRecentDoNotRewriteOtherData() {
        config.writeText("""{"columnWidths":{"libraryFacets":310,"libraryInspector":410,"integrationSidebar":260,"integrationFoundPaneHeight":170,"placePalette":390}}""")
        start()
        addProject()
        selectProject()
        val projectRow = uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag(projectRow).performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placePin).performClick()
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "pinned", "select")).performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placeNewSet).performClick()
        compose.onNodeWithTag("place-set-name").performTextInput("Saved set")
        compose.onNodeWithTag("dialog-confirm").performClick()
        val beforePlaces = openConfigStore().load().places
        assertTrue(beforePlaces.recent.isNotEmpty() && beforePlaces.pinned.isNotEmpty() && beforePlaces.sets.isNotEmpty())
        val file = createRule("First Rule")
        route("library")
        compose.onNodeWithTag("library-search").performTextReplacement("")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-group").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-group").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput("First Group")
        compose.onNodeWithTag("dialog-confirm").performClick()
        val group = library.resolve("groups/first-group.yaml")
        compose.waitUntil(15_000) { Files.exists(group) }
        val before = file.readText()
        val groupBefore = group.readText()
        section("library")
        compose.onNodeWithTag("settings-name-format-snake").performClick()
        assertEquals(before, file.readText())
        assertEquals(groupBefore, group.readText())
        editRule("first-rule")
        compose.onNodeWithTag("library-editor-block-name").performTextReplacement("First Rule")
        saveEditor()
        compose.waitUntil(15_000) { file.readText().contains("first_rule") }
        assertTrue(Files.exists(file))
        compose.onNodeWithTag("library-editor-back").performClick()
        route("library")
        compose.onNodeWithTag("library-search").performTextReplacement("first-group")
        val groupRow = uiTarget("library", "GROUP:first-group", "catalog", "select")
        present(groupRow)
        compose.onNodeWithTag(groupRow).performSemanticsAction(SemanticsActions.OnClick)
        present("library-inspector-edit")
        compose.onNodeWithTag("library-inspector-edit").performSemanticsAction(SemanticsActions.OnClick)
        present("library-editor-group-name")
        compose.onNodeWithTag("library-editor-group-name").performTextReplacement("First Group")
        saveEditor()
        compose.waitUntil(15_000) { group.readText().contains("first_group") }
        assertTrue(Files.exists(group))
        assertEquals("SNAKE", openConfigStore().load().nameFormat.name)
        val beforeProjects = openConfigStore().load().projects
        section("general")
        compose.onNodeWithTag("settings-reset-columns").performClick()
        compose.onNodeWithTag("settings-clear-recent").performClick()
        val after = openConfigStore().load()
        assertEquals(beforeProjects, after.projects)
        assertTrue(after.places.recent.isEmpty())
        assertEquals(beforePlaces.pinned, after.places.pinned)
        assertEquals(beforePlaces.sets, after.places.sets)
        assertEquals(ColumnWidths(), after.columnWidths)
    }

    @Test fun N04_connectDisconnectSkillsAndUpdateAllPreserveForeignSkill() {
        val foreign = home.resolve(".claude/skills/ruleblend/SKILL.md")
        Files.createDirectories(foreign.parent)
        foreign.writeText("---\nname: ruleblend\ndescription: My own skill\n---\nForeign body\n")
        start()
        section("agents")
        val codexConfig = home.resolve(".codex/config.toml")
        val codexSkill = home.resolve(".codex/skills/ruleblend/SKILL.md")
        val piSkill = home.resolve(".agents/skills/ruleblend/SKILL.md")
        if (stage == "first") {
            compose.onNodeWithText(EnStrings.connectStatusSkillForeign).assertIsDisplayed()
            compose.onNodeWithTag("settings-connect-codex").performClick()
            compose.waitUntil(15_000) {
                Files.exists(codexConfig) && codexConfig.readText().contains("RULEBLEND_ENTRY") &&
                    Files.exists(codexSkill)
            }
            compose.onNodeWithTag("settings-connect-pi").performClick()
            compose.waitUntil(15_000) { Files.exists(piSkill) }
            assertTrue(foreign.readText().contains("Foreign body"))
            // An external app update leaves both owned copies old while the GUI process exits.
            codexSkill.writeText(codexSkill.readText() + "\nold local footer\n")
            piSkill.writeText(piSkill.readText() + "\nold local footer\n")
        } else {
            present("settings-update-all")
            compose.onNodeWithTag("settings-update-all").performClick()
            compose.waitUntil(15_000) {
                !codexSkill.readText().contains("old local footer") && !piSkill.readText().contains("old local footer")
            }
            assertTrue(codexConfig.readText().contains("RULEBLEND_ENTRY"))
            assertTrue(foreign.readText().contains("Foreign body"))
            compose.onNodeWithTag("settings-disconnect-codex").performClick()
            compose.waitUntil(15_000) {
                !codexConfig.readText().contains("[mcp_servers.ruleblend]") && !Files.exists(codexSkill)
            }
            compose.onNodeWithTag("settings-disconnect-pi").performClick()
            compose.waitUntil(15_000) { !Files.exists(piSkill) }
            assertTrue(Files.exists(foreign))
        }
    }

    @Test fun N05_reconnectStaleRegistrationAndKeepForeignServer() {
        val foreign = home.resolve(".claude/.claude.json")
        val foreignBody = """{"mcpServers":{"ruleblend":{"type":"stdio","command":"/bin/foreign","args":["--serve"]}}}"""
        if (stage == "first") foreign.writeText(foreignBody)
        start()
        section("agents")
        val codexConfig = home.resolve(".codex/config.toml")
        if (stage == "first") {
            compose.onNodeWithText(EnStrings.connectStatusMcpForeign).assertIsDisplayed()
            compose.onNodeWithTag("settings-connect-claude-code").assertDoesNotExist()
            compose.onNodeWithTag("settings-disconnect-claude-code").assertDoesNotExist()
            assertEquals(foreignBody, foreign.readText())
            compose.onNodeWithTag("settings-connect-codex").performClick()
            compose.waitUntil(15_000) { Files.exists(codexConfig) && codexConfig.readText().contains("RULEBLEND_ENTRY") }
            val original = codexConfig.readText()
            // External fixture edit: the entry keeps its ownership stamp but points at an old app.
            codexConfig.writeText(original.replace("ruleblend-mcp", "old-ruleblend-mcp"))
            assertTrue(codexConfig.readText().contains("old-ruleblend-mcp"))
        } else if (stage == "restart") {
            compose.onNodeWithText(EnStrings.connectStatusStale).assertIsDisplayed()
            present("settings-connect-codex")
            compose.onNodeWithTag("settings-connect-codex").performClick()
            compose.waitUntil(15_000) { !codexConfig.readText().contains("old-ruleblend-mcp") }
            assertTrue(codexConfig.readText().contains("RULEBLEND_ENTRY"))
            assertEquals(foreignBody, foreign.readText())
            root.resolve("artifacts/repaired-registration.txt").writeText(codexConfig.readText())
            route("library")
            compose.onNodeWithTag("library-search").performTextReplacement("ruleblend")
            val builtin = uiTarget("library", "MCP:builtin:mcp", "catalog", "select")
            compose.onNodeWithTag(builtin).performClick()
            compose.onNodeWithTag("library-inspector-edit").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("settings-connect-codex").assertDoesNotExist()
            assertEquals(root.resolve("artifacts/repaired-registration.txt").readText(), codexConfig.readText())
            assertEquals(foreignBody, foreign.readText())
        }
    }

    @Test fun N06_projectLaunchEditorTerminalFinderAndDiagnosticsUseSandboxPorts() {
        start()
        addProject()
        createRule("launch rule", content = "Launch the configured assistant.")
        val editor = Files.createDirectories(root.resolve("apps/Test Editor.app"))
        val terminal = Files.createDirectories(root.resolve("apps/Test Terminal.app"))
        section("general")
        compose.onNodeWithTag("settings-editor-external").performClick()
        ports.nextEditor = editor
        compose.onNodeWithTag("settings-editor-choose").performClick()
        ports.nextEditor = terminal
        compose.onNodeWithTag("settings-terminal-choose").performClick()
        section("agents")
        compose.onNodeWithTag("settings-cli-codex").performTextReplacement("--synthetic")
        installRuleInProject("launch-rule")
        val installed = project.resolve("AGENTS.md")
        compose.waitUntil(15_000) { Files.exists(installed) }
        route("library")
        route("place")
        selectProject()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("place-tab-rules").fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithTag("place-tab-rules").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription]
                    .joinToString().contains("1")
        }
        compose.onNodeWithTag("place-tab-rules").performClick()
        val edit = "place-file-edit:$installed"
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(edit))
        compose.onNodeWithTag(edit).performClick()
        assertTrue(ports.calls.contains("edit:$installed:$editor"))
        present("place-launch-codex")
        compose.onNodeWithTag("place-launch-codex").performClick()
        compose.waitUntil(15_000) { ports.calls.any { it.startsWith("launch:codex:$project:") } }
        assertTrue(ports.calls.any { it.contains("--synthetic") && it.contains(terminal.toString()) })
        route("settings")
        section("about")
        compose.onNodeWithTag("settings-copy-diagnostics").performClick()
        assertTrue(copied.contains("Ruleblend") && copied.contains(home.toString()))
        compose.onNodeWithTag("settings-reveal-config").performClick()
        assertTrue(ports.calls.contains("reveal:$config"))
        assertEquals(editor.toString(), openConfigStore().load().externalEditorPath)
        assertEquals(terminal.toString(), openConfigStore().load().terminalAppPath)
    }

    @Test fun N07_paletteChordsAndRailStayReadOnly() {
        start()
        addProject()
        val file = createRule("palette rule")
        val before = file.readText()
        val beforeConfig = config.readText()
        route("library")
        compose.onNodeWithTag("library-search").requestFocus()
        compose.onNodeWithTag("library-search").assertIsFocused()
        compose.onNodeWithTag("app-shell").performKeyInput {
            keyDown(Key.MetaLeft); pressKey(Key.K); keyUp(Key.MetaLeft)
        }
        present("command-input")
        for ((query, id) in listOf("r:palette" to "RULE:palette-rule",
            "s:ruleblend" to "SKILL:builtin:skill", "m:ruleblend" to "MCP:builtin:mcp",
            "p:Проект" to "project:${project.projectKey()}")) {
            compose.onNodeWithTag("command-input").performTextReplacement(query)
            present("command-item-$id")
        }
        compose.onNodeWithTag("command-input").performTextReplacement("settings")
        present("command-item-route:SETTINGS")
        compose.onNodeWithTag("command-input").performKeyInput {
            pressKey(Key.DirectionDown); pressKey(Key.DirectionUp); pressKey(Key.Enter)
        }
        route("settings")
        compose.onNodeWithTag("nav-settings").requestFocus()
        compose.onNodeWithTag("nav-settings").performKeyInput {
            keyDown(Key.MetaLeft); pressKey(Key.K); keyUp(Key.MetaLeft)
        }
        present("command-input")
        compose.onNodeWithTag("command-input").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("command-palette").assertDoesNotExist()
        var currentRoute = "settings"
        for ((key, route) in listOf(Key.One to "home", Key.Two to "place", Key.Three to "library",
            Key.Four to "coverage", Key.Comma to "settings")) {
            step = "shortcut:$route"
            compose.onNodeWithTag("nav-$currentRoute").requestFocus()
            compose.onNodeWithTag("nav-$currentRoute").performKeyInput {
                keyDown(Key.CtrlLeft); pressKey(key); keyUp(Key.CtrlLeft)
            }
            compose.onNodeWithTag("nav-$route").assertIsSelected()
            currentRoute = route
        }
        compose.onNodeWithTag("nav-library").requestFocus()
        compose.onNodeWithTag("nav-library").performKeyInput { pressKey(Key.Enter) }
        route("library")
        compose.onNodeWithTag("nav-library").requestFocus()
        compose.onNodeWithTag("nav-library").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithTag("nav-coverage").assertIsFocused()
        compose.onNodeWithTag("nav-coverage").performKeyInput { pressKey(Key.Enter) }
        route("coverage")
        // ⌘1–⌘4 and ⌘, with the caret in a text field: they navigate and never type the key.
        for ((key, route) in listOf(Key.One to "home", Key.Two to "place", Key.Three to "library",
            Key.Four to "coverage", Key.Comma to "settings")) {
            step = "field-shortcut:$route"
            route("library")
            compose.onNodeWithTag("library-search").performTextReplacement("typed")
            compose.onNodeWithTag("library-search").requestFocus()
            compose.onNodeWithTag("library-search").performKeyInput {
                keyDown(Key.MetaLeft); pressKey(key); keyUp(Key.MetaLeft)
            }
            compose.onNodeWithTag("nav-$route").assertIsSelected()
            if (route == "library") {
                compose.onNodeWithTag("library-search").assertIsFocused()
                assertEquals("typed", fieldText("library-search"))
            }
        }
        // Typing into the editor and a chord on top of it write nothing until Save.
        step = "editor-draft"
        editRule("palette-rule")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Unsaved draft")
        compose.onNodeWithTag("library-editor-block-content").performKeyInput {
            keyDown(Key.MetaLeft); pressKey(Key.K); keyUp(Key.MetaLeft)
        }
        present("command-input")
        compose.onNodeWithTag("command-input").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("command-palette").assertDoesNotExist()
        assertEquals("Unsaved draft", fieldText("library-editor-block-content"))
        assertEquals(before, file.readText())
        assertEquals(beforeConfig, config.readText())
    }

    @Test fun N09_setupProjectWithModifiedAndForeignObjects() {
        assertEquals("setup", stage)
        start()
        addProject()
        createRule("Long Window Rule")
        editRule("long-window-rule")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Original window instruction.")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("blocks/long-window-rule.md").readText().contains("Original window instruction.") }
        installRuleInProject("long-window-rule")
        val installed = project.resolve("AGENTS.md")
        val original = installed.readText()
        installed.writeText(original.replace("Original window instruction.", "Hand-edited window instruction."))
        assertTrue(installed.readText().contains("Hand-edited window instruction."))
    }

    @Test fun N11_mcpProcessChangesVisibleAfterFocusAndConcurrentUiSaveSurvives() {
        start()
        route("library")
        val binary = Path.of(requireNotNull(System.getenv("RULEBLEND_E2E_MCP_BINARY")))
        assertTrue(Files.isExecutable(binary))
        val profile = root.resolve("mcp.sb")
        val realHome = requireNotNull(System.getenv("RULEBLEND_E2E_REAL_HOME"))
        profile.writeText("(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n" +
            "(allow file-write* (subpath \"$root\") (literal \"/dev/null\"))\n" +
            "(deny file-read* (subpath \"$realHome\"))\n")
        val process = ProcessBuilder("/usr/bin/sandbox-exec", "-f", profile.toString(),
            binary.toString(), "--mcp")
            .directory(root.resolve("work").toFile())
            .redirectError(root.resolve("artifacts/$stage/mcp-stderr.txt").toFile())
            .apply {
                environment()["JAVA_TOOL_OPTIONS"] = "-Duser.home=$home -Djava.io.tmpdir=${root.resolve("tmp")}"
            }.start()
        try {
            val input = process.outputStream.bufferedWriter()
            val output = process.inputStream.bufferedReader()
            fun rpc(id: Int, method: String, params: String): String {
                input.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
                input.newLine()
                input.flush()
                val line = CompletableFuture.supplyAsync { output.readLine() }.get(20, TimeUnit.SECONDS)
                    ?: error("MCP process closed before $method replied")
                assertTrue(Regex("\\\"id\\\"\\s*:\\s*$id\\b").containsMatchIn(line), line)
                assertFalse(line.contains("\"error\""), line)
                return line
            }
            rpc(1, "initialize", """{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e","version":"1"}}""")
            input.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            input.newLine(); input.flush()
            val created = rpc(2, "tools/call",
                """{"name":"create_rule","arguments":{"name":"mcp outside","content":"MCP first body"}}""")
            assertTrue(created.contains("mcp-outside"), created)
            val mcpFile = library.resolve("blocks/mcp-outside.md")
            compose.waitUntil(15_000) { Files.exists(mcpFile) }
            assertTrue(focusEvents.tryEmit(Unit))
            present(uiTarget("library", "RULE:mcp-outside", "catalog", "select"))
            input.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"update_rule","arguments":{"id":"mcp-outside","content":"MCP second body"}}}""")
            input.newLine(); input.flush()
            val uiFile = createRule("ui concurrent")
            val updated = CompletableFuture.supplyAsync { output.readLine() }.get(20, TimeUnit.SECONDS)
                ?: error("MCP update did not reply")
            assertFalse(updated.contains("\"error\""), updated)
            assertTrue(focusEvents.tryEmit(Unit))
            compose.waitUntil(15_000) { mcpFile.readText().contains("MCP second body") }
            assertTrue(uiFile.readText().contains("ui-concurrent"))
            assertTrue(mcpFile.readText().contains("version: 2"))
            // The re-read reaches the screen: the inspector shows the MCP process's second version.
            step = "mcp-version-visible"
            route("library")
            if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("library-editor-back").performClick()
            compose.onNodeWithTag("library-search").performTextReplacement("mcp-outside")
            val row = uiTarget("library", "RULE:mcp-outside", "catalog", "select")
            present(row)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("MCP second body", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                    run { compose.onNodeWithTag(row).performClick(); false }
            }
            assertTrue(compose.onAllNodesWithText("v2").fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithText("MCP first body", substring = true).fetchSemanticsNodes().isEmpty())
            input.close()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
