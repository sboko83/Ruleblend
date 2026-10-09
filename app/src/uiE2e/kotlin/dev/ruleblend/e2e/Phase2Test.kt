package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.rightClick
import dev.ruleblend.app.App
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** L-01..L-12 each run in a separate owned sandbox through the real App route. */
class Phase2Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val library = home.resolve(".ruleblend/library")
    private val config = home.resolve(".ruleblend/config.json")
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
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
            System.setSecurityManager(SandboxWrites(root))
            DesktopEnvironment.actions = ports
            try { base.evaluate() } finally {
                DesktopEnvironment.actions = actions
                System.setSecurityManager(security)
            }
        }
    } }
    private val diagnostics = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                artifacts.resolve("failure.txt").writeText("$step\n" + failure.stackTraceToString())
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
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                App(openConfigStore(), onThemeModeChanged = {}, createTranslator = { NoTranslation })
            }
        }
        compose.onNodeWithTag("nav-place").assertIsDisplayed()
    }

    private fun route(name: String) {
        step = "navigate:$name"
        compose.onNodeWithTag("nav-$name").performClick()
        // A synthetic click can land on the layer of a tooltip that is still closing; retry within the bound.
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-editor-back").performClick()
        }
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(row("GROUP", "all")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun row(kind: String, id: String) = uiTarget("library", "$kind:$id", "catalog", "select")
    private fun inspector(kind: String, id: String) = uiTarget("library", "$kind:$id", "inspector", "selected")
    private fun file(kind: String, id: String) =
        if (kind == "SKILL") library.resolve("skills/$id/files/SKILL.md") else library.resolve("blocks/$id.md")

    private fun select(kind: String, id: String, query: String = id) {
        step = "select:$kind:$id"
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(if (id.startsWith("builtin:")) "ruleblend" else query)
        val target = row(kind, id)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(target).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(target).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("library", "$kind:$id", "inspector", "selected"))
                .fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(target).performClick(); false }
        }
    }

    private fun edit(kind: String, id: String, query: String = id) {
        select(kind, id, query)
        compose.waitForIdle()
        compose.onNodeWithTag("library-inspector-edit").performClick()
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("library-inspector-edit").performClick()
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Opens the New menu and picks [item]; a click lost while the catalog settles is retried. */
    private fun openNew(item: String) = openMenu("library-new-$item")

    private fun openMenu(entry: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(entry).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag(entry).performClick()
    }

    private fun create(kind: String, name: String, content: String = "", description: String = "Synthetic description"): String {
        step = "create:$kind:$name"
        catalog()
        openNew(kind)
        compose.onNodeWithTag("library-create-name").performTextInput(name)
        if (kind == "skill") compose.onNodeWithTag("library-create-description").performTextInput(description)
        compose.onNodeWithTag("dialog-confirm").performClick()
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val saved = file(kind.uppercase(), id)
        if (kind == "group" || kind == "profile") {
            val folder = if (kind == "group") "groups" else "profiles"
            compose.waitUntil(15_000) { Files.exists(library.resolve("$folder/$id.yaml")) }
            return id
        }
        compose.waitUntil(15_000) { Files.exists(saved) }
        if (content.isNotEmpty()) {
            edit(kind.uppercase(), id)
            val tag = when (kind) {
                "skill" -> "library-editor-skill-content"
                "mcp" -> "mcp-command"
                else -> "library-editor-block-content"
            }
            compose.onNodeWithTag(tag).performTextReplacement(content)
            saveEditor()
            val marker = if (kind == "skill") content.substringAfterLast('\n') else content
            compose.waitUntil(15_000) { saved.readText().contains(marker) }
        }
        return id
    }

    private fun saveEditor() {
        compose.onNodeWithTag("library-editor-save").assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
    }

    private fun save(kind: String, id: String, tag: String, value: String) {
        step = "save:$kind:$id"
        edit(kind, id)
        compose.onNodeWithTag(tag).performTextReplacement(value)
        saveEditor()
        compose.waitUntil(15_000) { file(kind, id).readText().contains(value) }
    }

    private fun facet(id: String) {
        val tag = "library-facet:$id"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("library-facets").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
        if (id.startsWith("type:")) {
            compose.onNodeWithTag(tag).performMouseInput { rightClick() }
        } else {
            compose.onNodeWithTag(tag).performClick()
        }
    }

    private fun addProject() {
        route("place")
        ports.nextDirectory = project
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { Files.exists(config) && config.readText().contains(project.projectJsonText()) }
    }

    private fun install(kind: String, id: String) {
        route("place")
        val place = "project:${project.projectKey()}"
        val recent = uiTarget("place", place, "recent", "select")
        val projectRow = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", place, "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(projectRow))
        compose.onNodeWithTag(projectRow).performClick()
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val install = uiTarget("palette", "$kind:$id", place, "install")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(install).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(install).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("palette", "$kind:$id", place, "remove"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun L01_catalogFiltersSearchAndReset() {
        start(); addProject()
        create("rule", "alpha-rule", "Alpha body")
        create("skill", "alpha-skill", "---\nname: alpha-skill\ndescription: Synthetic description\n---\nAlpha skill")
        install("RULE", "alpha-rule")
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement("alpha")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertIsDisplayed()
        compose.onNodeWithTag(row("SKILL", "alpha-skill")).assertIsDisplayed()
        compose.onNodeWithTag(row("RULE", "alpha-rule")).performClick()
        compose.onNodeWithTag(inspector("RULE", "alpha-rule")).assertIsDisplayed()
        compose.onNodeWithTag("library-facet:type:SKILL").assertTextContains("2")
        facet("type:SKILL")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertDoesNotExist()
        compose.onNodeWithTag(row("SKILL", "alpha-skill")).assertIsDisplayed()
        compose.onNodeWithText(EnStrings.libSelectObject).assertIsDisplayed()
        facet("type:SKILL")
        // A facet empties the inspector for good: turning it off does not bring the object back.
        compose.onNodeWithText(EnStrings.libSelectObject).assertIsDisplayed()
        // A query keeps the selected object while it is still listed and drops it once hidden.
        compose.onNodeWithTag(row("RULE", "alpha-rule")).performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("alpha-r")
        compose.onNodeWithTag(inspector("RULE", "alpha-rule")).assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextReplacement("alpha-skill")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertDoesNotExist()
        compose.onNodeWithTag(inspector("RULE", "alpha-rule")).assertDoesNotExist()
        compose.onNodeWithText(EnStrings.libSelectObject).assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextReplacement("alpha")
        compose.onNodeWithText(EnStrings.libSelectObject).assertIsDisplayed()
        facet("usage:installed")
        compose.onNodeWithTag("library-facet:usage:installed").assertTextContains("2")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertIsDisplayed()
        compose.onNodeWithTag(row("SKILL", "alpha-skill")).assertDoesNotExist()
        facet("usage:installed")
        facet("source:local")
        compose.onNodeWithTag(row("SKILL", "alpha-skill")).assertIsDisplayed()
        facet("source:local")
        create("group", "alpha-group")
        edit("GROUP", "alpha-group")
        compose.onNodeWithTag("library-group-member:BLOCK:alpha-rule").performClick()
        saveEditor()
        compose.waitUntil(15_000) { library.resolve("groups/alpha-group.yaml").readText().contains("alpha-rule") }
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement("alpha")
        facet("group:alpha-group")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertIsDisplayed()
        compose.onNodeWithTag(row("SKILL", "alpha-skill")).assertDoesNotExist()
        facet("group:alpha-group")
        create("rule", "scoped-rule", "Scoped body")
        edit("RULE", "scoped-rule")
        compose.onNodeWithTag("library-scope-picker").performClick()
        compose.onNodeWithTag("library-scope:$project").performClick()
        saveEditor()
        compose.waitUntil(15_000) { config.readText().contains("scoped-rule") && config.readText().contains(project.projectJsonText()) }
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement("scoped-rule")
        facet("scope:global")
        compose.onNodeWithTag(row("RULE", "scoped-rule")).assertDoesNotExist()
        facet("scope:global")
        facet("scope:project")
        compose.onNodeWithTag(row("RULE", "scoped-rule")).assertIsDisplayed()
        facet("scope:project")
        compose.onNodeWithTag("library-search").performTextReplacement("no-such-object")
        compose.onNodeWithText(EnStrings.libNoMatches).assertIsDisplayed()
        compose.onNodeWithTag("library-search").performTextReplacement("")
        compose.onNodeWithTag(row("RULE", "alpha-rule")).assertIsDisplayed()
        assertTrue(file("RULE", "alpha-rule").readText().contains("Alpha body"))
    }

    @Test fun L02_creationValidationCollisionAndCancel() {
        start(); catalog()
        openNew("rule")
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("library-create-name").performTextInput("Canceled")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertFalse(Files.exists(file("RULE", "canceled")))
        create("rule", "A / B")
        assertTrue(Files.exists(file("RULE", "a-b")))
        catalog()
        openNew("rule")
        compose.onNodeWithTag("library-create-name").performTextInput("A / B")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(file("RULE", "a-b-2")) }
        assertTrue(file("RULE", "a-b").readText().contains("a-b"))
        catalog()
        openNew("skill")
        compose.onNodeWithTag("library-create-name").performTextInput("a".repeat(65))
        compose.onNodeWithTag("library-create-description").performTextInput("Synthetic")
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertFalse(Files.exists(library.resolve("skills/" + "a".repeat(65))))
    }

    @Test fun L03_editorsPersistFieldsMarkdownAndUnicode() {
        start()
        create("rule", "edit-rule", "First body")
        edit("RULE", "edit-rule")
        compose.onNodeWithTag("library-editor-block-name").performTextReplacement("Edited rule")
        compose.onNodeWithTag("library-editor-block-description").performTextReplacement("Описание α")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("## Rule\nТекст **жирный**")
        saveEditor()
        compose.waitUntil(15_000) { file("RULE", "edit-rule").readText().contains("Текст **жирный**") }
        create("skill", "edit-skill", "---\nname: edit-skill\ndescription: Synthetic description\n---\nOld")
        edit("SKILL", "edit-skill")
        compose.onNodeWithTag("library-editor-skill-description").performTextReplacement("Описание навыка")
        compose.onNodeWithTag("library-editor-skill-content").performTextReplacement("---\nname: edit-skill\ndescription: Описание навыка\n---\n# Привет\nUnicode Ω")
        saveEditor()
        compose.waitUntil(15_000) { file("SKILL", "edit-skill").readText().contains("Unicode Ω") }
        create("subagent", "edit-agent", "Old agent")
        edit("SUBAGENT", "edit-agent")
        compose.onNodeWithTag("library-editor-block-description").performTextReplacement("Описание агента")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Do **work** Ω")
        compose.onNodeWithTag("subagent-field-claude-code-model").performTextInput("sonnet")
        saveEditor()
        compose.waitUntil(15_000) { file("SUBAGENT", "edit-agent").readText().contains("Do **work** Ω") }
        assertTrue(file("SUBAGENT", "edit-agent").readText().contains("sonnet"))
        edit("RULE", "edit-rule", "edited-rule")
        compose.onNodeWithTag("library-editor-block-description").assertTextContains("Описание α")
        compose.onNodeWithTag("library-editor-block-content").assertTextContains("Текст **жирный**", substring = true)
        edit("SKILL", "edit-skill")
        compose.onNodeWithTag("library-editor-skill-content").assertTextContains("Unicode Ω", substring = true)
        edit("SUBAGENT", "edit-agent")
        compose.onNodeWithTag("library-editor-block-content").assertTextContains("Do **work** Ω", substring = true)
    }

    @Test fun L04_unsavedDraftNeverReachesDisk() {
        start()
        create("rule", "draft-one", "Saved one")
        create("rule", "draft-two", "Saved two")
        edit("RULE", "draft-one")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Unsaved one")
        compose.onNodeWithTag("library-editor-back").performClick()
        assertFalse(file("RULE", "draft-one").readText().contains("Unsaved one"))
        select("RULE", "draft-two")
        edit("RULE", "draft-two")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Unsaved two")
        route("place")
        assertFalse(file("RULE", "draft-two").readText().contains("Unsaved two"))
        edit("RULE", "draft-one")
        compose.onNodeWithTag("library-editor-block-content").assertTextContains("Saved one")
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
    }

    @Test fun L05_mcpValidationAndTransportDrafts() {
        start()
        create("mcp", "validation-server", "/bin/echo")
        edit("MCP", "validation-server")
        val original = file("MCP", "validation-server").readText()
        compose.onNodeWithTag("mcp-command").performTextReplacement("")
        compose.onNodeWithText(EnStrings.mcpErrCommandRequired).assertIsDisplayed()
        compose.onNodeWithTag("library-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-command").performTextReplacement("/bin/printf")
        compose.onNodeWithTag("mcp-add-env").performClick()
        compose.onNodeWithTag("mcp-env-key-0").performTextInput("DUP")
        compose.onNodeWithTag("mcp-env-value-0").performTextInput("first")
        compose.onNodeWithTag("mcp-add-env").performClick()
        compose.onNodeWithTag("mcp-env-key-1").performTextInput("DUP")
        compose.onNodeWithTag("mcp-env-value-1").performTextInput("second")
        compose.onNodeWithText(EnStrings.mcpErrDuplicateKey).assertIsDisplayed()
        compose.onNodeWithTag("library-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-env-key-1").performTextReplacement("UNIQUE")
        compose.onNodeWithTag("mcp-http").performClick()
        compose.onNodeWithTag("mcp-url").performTextInput("invalid")
        compose.onNodeWithText(EnStrings.mcpErrUrlInvalid).assertIsDisplayed()
        compose.onNodeWithTag("library-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-url").performTextReplacement("https://example.invalid/mcp")
        compose.onNodeWithTag("mcp-add-headers").performClick()
        compose.onNodeWithTag("mcp-headers-key-0").performTextInput("X-Dup")
        compose.onNodeWithTag("mcp-headers-value-0").performTextInput("first")
        compose.onNodeWithTag("mcp-add-headers").performClick()
        compose.onNodeWithTag("mcp-headers-key-1").performTextInput("X-Dup")
        compose.onNodeWithTag("mcp-headers-value-1").performTextInput("second")
        compose.onNodeWithText(EnStrings.mcpErrDuplicateKey).assertIsDisplayed()
        compose.onNodeWithTag("library-editor-save").assertIsNotEnabled()
        compose.onNodeWithTag("mcp-stdio").performClick()
        compose.onNodeWithTag("mcp-command").assertTextContains("/bin/printf")
        compose.onNodeWithTag("mcp-http").performClick()
        compose.onNodeWithTag("mcp-url").assertTextContains("https://example.invalid/mcp")
        compose.onNodeWithTag("mcp-headers-key-1").assertTextContains("X-Dup")
        assertEquals(original, file("MCP", "validation-server").readText())
        compose.onNodeWithTag("library-editor-back").performClick()
        catalog()
        openNew("mcp")
        compose.onNodeWithTag("library-create-name").performTextInput("ruleblend")
        compose.onNodeWithTag("dialog-confirm").performClick()
        edit("MCP", "ruleblend")
        compose.onNodeWithTag("mcp-command").performTextInput("/bin/echo")
        compose.onNodeWithTag("library-editor-save").assertIsNotEnabled()
    }

    @Test fun L06_duplicateSplitCancelAndDirtyGuard() {
        start()
        val body = "# First\nFirst body\n# Second\nSecond body"
        create("rule", "split-source", body)
        edit("RULE", "split-source")
        compose.onNodeWithTag("library-editor-duplicate").performClick()
        compose.waitUntil(15_000) { Files.exists(file("RULE", "split-source-2")) }
        assertTrue(file("RULE", "split-source-2").readText().contains("Second body"))
        compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-inspector-edit").assertIsDisplayed()
        edit("RULE", "split-source")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Dirty draft")
        compose.onNodeWithTag("library-editor-split").assertIsNotEnabled()
        compose.onNodeWithTag("library-editor-back").performClick()
        edit("RULE", "split-source")
        compose.onNodeWithTag("library-editor-split").performClick()
        compose.onNodeWithTag("split-cut-2").performClick()
        compose.onNodeWithTag("split-confirm").assertIsEnabled()
        compose.onNodeWithTag("split-cancel").performClick()
        assertFalse(Files.exists(file("RULE", "first")))
        compose.onNodeWithTag("library-editor-split").performClick()
        compose.onNodeWithTag("split-cut-2").performClick()
        compose.onNodeWithTag("split-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(file("RULE", "first")) && Files.exists(file("RULE", "second")) }
        assertTrue(file("RULE", "first").readText().contains("First body"))
        assertTrue(file("RULE", "second").readText().contains("Second body"))
        assertTrue(file("RULE", "split-source").readText().contains("Second body"))
    }

    @Test fun L07_revisionHistoryShowsRealChangesWithoutWriting() {
        start()
        create("rule", "history-rule", "Version one")
        save("RULE", "history-rule", "library-editor-block-content", "Version two")
        save("RULE", "history-rule", "library-editor-block-content", "Version three")
        val original = file("RULE", "history-rule").readText()
        val newest = org.eclipse.jgit.api.Git.open(library.toFile()).use {
            it.repository.resolve("HEAD").name.take(7)
        }
        select("RULE", "history-rule")
        val inspector = uiTarget("library", "RULE:history-rule", "inspector", "selected")
        // Selection can briefly retain the history loaded before the last save.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-revision-row:$newest").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-revision-row:$newest"))
        compose.onNodeWithTag("library-revision-row:$newest").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("revision-diff-body").fetchSemanticsNodes().isNotEmpty()
        }
        // The newest revision is the second save: it replaced "two" with "three".
        val diff = compose.onNodeWithTag("revision-diff-body").printToString()
        assertTrue(diff.contains("Version three") && diff.contains("Version two"), diff)
        assertFalse(diff.contains("Version one"), diff)
        val dialogSemantics = compose.onAllNodes(isRoot()).let { roots ->
            roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
        }
        val dialogIds = Regex("revision-dialog-row:([0-9a-f]{7,40})")
            .findAll(dialogSemantics).map { it.groupValues[1] }.distinct().toList()
        // Newest first: two saves, the first body, and the empty object the create dialog made.
        assertEquals(4, dialogIds.size, dialogSemantics)
        compose.onNodeWithTag("revision-dialog-row:" + dialogIds[2]).performClick()
        compose.waitUntil(15_000) {
            compose.onNodeWithTag("revision-diff-body").printToString().contains("Version one")
        }
        val first = compose.onNodeWithTag("revision-diff-body").printToString()
        assertFalse(first.contains("Version two") || first.contains("Version three"), first)
        assertEquals(original, file("RULE", "history-rule").readText())
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(original, file("RULE", "history-rule").readText())
    }

    private fun compare(kind: String, id: String) {
        select(kind, id)
        compose.onNodeWithTag(row(kind, id)).performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.compareAction).performClick()
        compose.onNodeWithTag("compare-candidate-picker").assertIsDisplayed()
    }

    @Test fun L08_compareHunksUndoAndResetStayInDrafts() {
        start()
        val shared = (1..8).joinToString("\n") { "Shared $it" }
        create("rule", "compare-left", "Head\nLeft first\n$shared\nLeft second\nTail")
        create("rule", "compare-right", "Head\nRight first\n$shared\nRight second\nTail")
        val left = file("RULE", "compare-left").readText()
        val right = file("RULE", "compare-right").readText()
        compare("RULE", "compare-left")
        compose.onNodeWithText(EnStrings.compareHunkPosition(1, 2)).assertIsDisplayed()
        compose.onNodeWithTag("compare-next-hunk").performClick()
        compose.onNodeWithText(EnStrings.compareHunkPosition(2, 2)).assertIsDisplayed()
        compose.onNodeWithTag("compare-prev-hunk").performClick()
        compose.onNodeWithText(EnStrings.compareHunkPosition(1, 2)).assertIsDisplayed()
        // First hunk right-to-left; the second becomes hunk 0 and goes left-to-right.
        compose.onNodeWithTag("compare-copy-right-to-left-0").performClick()
        compose.onNodeWithTag("compare-copy-left-to-right-0").performClick()
        compose.onNodeWithTag("compare-mode-edit").performClick()
        compose.onNodeWithTag("compare-left-draft").assertTextContains("Right first", substring = true)
        compose.onNodeWithTag("compare-left-draft").assertTextContains("Left second", substring = true)
        compose.onNodeWithTag("compare-right-draft").assertTextContains("Left second", substring = true)
        compose.onNodeWithTag("compare-right-draft").assertTextContains("Right first", substring = true)
        compose.onNodeWithTag("compare-undo").performClick()
        compose.onNodeWithTag("compare-right-draft").assertTextContains("Right second", substring = true)
        compose.onNodeWithTag("compare-undo").performClick()
        compose.onNodeWithTag("compare-left-draft").assertTextContains("Left first", substring = true)
        compose.onNodeWithTag("compare-left-draft").performTextReplacement("Draft only")
        compose.onNodeWithTag("compare-reset-left").performClick()
        compose.onNodeWithTag("compare-left-draft").assertTextContains("Left first", substring = true)
        compose.onNodeWithTag("compare-save-left").assertIsNotEnabled()
        assertEquals(left, file("RULE", "compare-left").readText())
        assertEquals(right, file("RULE", "compare-right").readText())
        compose.onNodeWithTag("dialog-confirm").performClick()
    }

    @Test fun L09_compareCandidateSwitchAndSeparateSaves() {
        start()
        create("rule", "left-rule", "Left original")
        create("rule", "right-rule", "Right original")
        create("rule", "third-rule", "Third original")
        compare("RULE", "left-rule")
        compose.onNodeWithTag("compare-candidate-picker").performClick()
        compose.onNodeWithTag("compare-candidate-right-rule").performClick()
        compose.onNodeWithTag("compare-mode-edit").performClick()
        compose.onNodeWithTag("compare-left-draft").performTextReplacement("Left draft")
        compose.onNodeWithTag("compare-right-draft").performTextReplacement("Right draft")
        compose.onNodeWithTag("compare-candidate-picker").performClick()
        compose.onNodeWithTag("compare-candidate-third-rule").performClick()
        compose.onNodeWithTag("compare-left-draft").assertTextContains("Left draft")
        compose.onNodeWithTag("compare-right-draft").assertTextContains("Third original")
        assertFalse(file("RULE", "left-rule").readText().contains("Left draft"))
        assertFalse(file("RULE", "right-rule").readText().contains("Right draft"))
        compose.onNodeWithTag("compare-save-left").performClick()
        compose.waitUntil(15_000) { file("RULE", "left-rule").readText().contains("Left draft") }
        compose.onNodeWithTag("compare-right-draft").performTextReplacement("Third saved")
        compose.onNodeWithTag("compare-save-right").performClick()
        compose.waitUntil(15_000) { file("RULE", "third-rule").readText().contains("Third saved") }
        compose.onNodeWithTag("compare-left-draft").performTextReplacement("Left both")
        compose.onNodeWithTag("compare-right-draft").performTextReplacement("Third both")
        compose.onNodeWithTag("compare-save-both").performClick()
        compose.waitUntil(15_000) {
            file("RULE", "left-rule").readText().contains("Left both") &&
                file("RULE", "third-rule").readText().contains("Third both")
        }
        compose.onNodeWithTag("compare-left-draft").performTextReplacement("Discard me")
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertFalse(file("RULE", "left-rule").readText().contains("Discard me"))
        assertFalse(file("RULE", "right-rule").readText().contains("Right draft"))
        select("RULE", "left-rule")
        compose.onNodeWithTag("library-inspector-body").assertTextContains("Left both")
    }

    @Test fun L10_gitSkillReadOnlyAndProjectFileCompareIsDraftOnly() {
        start(); addProject()
        catalog()
        openMenu("library-import-git")
        compose.onNodeWithTag("library-import-repository").performTextInput(root.resolve("upstream").toString())
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.libSkillImportSelected).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dialog-confirm").performClick()
        val imported = library.resolve("skills/imported-skill/files/SKILL.md")
        compose.waitUntil(15_000) { Files.exists(imported) }
        assertTrue(imported.readText().contains("Upstream body"))
        assertEquals("upstream resource\n", library.resolve("skills/imported-skill/files/tool.txt").readText())
        edit("SKILL", "imported-skill")
        compose.onNodeWithTag("library-editor-skill-content").assertTextContains("Upstream body", substring = true)
        compose.onNodeWithTag("library-editor-save").assertDoesNotExist()
        val original = imported.readText()
        create("skill", "local-skill", "---\nname: local-skill\ndescription: Synthetic description\n---\nLocal body")
        compare("SKILL", "imported-skill")
        compose.onNodeWithTag("compare-save-left").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(original, imported.readText())

        create("rule", "file-rule", "Project text")
        install("RULE", "file-rule")
        // The second project's file is fixture data, never an app write.
        val second = root.resolve("projects/B")
        second.resolve("AGENTS.md").writeText(
            project.resolve("AGENTS.md").readText().replace("Project text", "Compared text"),
        )
        route("place")
        ports.nextDirectory = second
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { config.readText().contains(second.projectJsonText()) }
        val recent = uiTarget("place", "project:${project.projectKey()}", "recent", "select")
        val projectRow = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(projectRow))
        compose.onNodeWithTag(projectRow).performClick()
        val path = project.resolve("AGENTS.md")
        val before = path.readText()
        compose.onNodeWithTag("place-tab-rules").performClick()
        val edit = "place-file-edit:$path"
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(edit))
        compose.onNodeWithTag(edit).performClick()
        compose.onNodeWithTag("file-edit-compare").performClick()
        compose.onNodeWithTag("file-compare-picker").assertIsDisplayed()
        compose.onNodeWithTag("file-compare-picker").performClick()
        compose.onAllNodesWithText("${Path.of(second.projectKey()).fileName} · AGENTS.md").onLast().performClick()
        compose.onNodeWithTag("compare-copy-right-to-left-0").performClick()
        compose.onNodeWithTag("file-compare-apply").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(before, path.readText())
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals(before, path.readText())
        // The same transfer lands on disk only through the editor's own Save.
        compose.onNodeWithTag(edit).performClick()
        compose.onNodeWithTag("file-edit-compare").performClick()
        compose.onNodeWithTag("file-compare-picker").performClick()
        compose.onAllNodesWithText("${Path.of(second.projectKey()).fileName} · AGENTS.md").onLast().performClick()
        compose.onNodeWithTag("compare-copy-right-to-left-0").performClick()
        compose.onNodeWithTag("file-compare-apply").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("file-compare-apply").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(before, path.readText())
        compose.onNodeWithTag("dialog-confirm").assertTextContains(EnStrings.actionSave)
            .assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { path.readText().contains("Compared text") }
        compose.onNodeWithTag("file-edit-compare").assertDoesNotExist()
    }

    @Test fun L11_deleteConfirmationClearsMembershipButKeepsCopy() {
        start(); addProject()
        create("rule", "member-rule", "Member body")
        install("RULE", "member-rule")
        create("group", "member-group")
        edit("GROUP", "member-group")
        compose.onNodeWithTag("library-group-member:BLOCK:member-rule").performClick()
        saveEditor()
        compose.waitUntil(15_000) { library.resolve("groups/member-group.yaml").readText().contains("member-rule") }
        create("profile", "member-profile")
        edit("PROFILE", "member-profile")
        compose.onNodeWithTag("library-profile-member:GROUP:member-group").performClick()
        compose.onNodeWithTag("library-profile-member:RULE:member-rule").performClick()
        compose.onNodeWithText(EnStrings.actionSave).performClick()
        compose.waitUntil(15_000) {
            val profile = library.resolve("profiles/member-profile.yaml").readText()
            profile.contains("member-group") && profile.contains("member-rule")
        }
        val installed = project.resolve("AGENTS.md").readText()
        select("RULE", "member-rule")
        compose.onNodeWithTag(uiTarget("library", "RULE:member-rule", "inspector", "selected"))
            .performScrollToNode(hasTestTag("library-inspector-delete"))
        compose.onNodeWithTag("library-inspector-delete").performClick()
        // The dialog titles the question and shows the consequence under it.
        compose.onNodeWithText(EnStrings.libDeleteRuleConfirm("member-rule").substringBefore('?') + "?").assertIsDisplayed()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertTrue(Files.exists(file("RULE", "member-rule")))
        compose.onNodeWithTag("library-inspector-delete").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !Files.exists(file("RULE", "member-rule")) }
        assertFalse(library.resolve("groups/member-group.yaml").readText().contains("member-rule"))
        val profile = library.resolve("profiles/member-profile.yaml").readText()
        assertTrue(profile.contains("member-group"), profile)
        assertFalse(profile.contains("member-rule"), profile)
        assertEquals(installed, project.resolve("AGENTS.md").readText())
    }

    @Test fun L12_builtInsReadOnlyInCatalogAndProject() {
        Files.createDirectories(home.resolve(".claude"))
        start(); catalog()
        for ((kind, id) in listOf("SKILL" to "builtin:skill", "MCP" to "builtin:mcp")) {
            select(kind, id)
            compose.onNodeWithTag("library-inspector-body").assertIsDisplayed()
            compose.onNodeWithText(EnStrings.libBuiltInManaged).assertIsDisplayed()
            compose.onNodeWithTag("library-inspector-edit").assertDoesNotExist()
            compose.onNodeWithTag("library-inspector-delete").assertDoesNotExist()
        }
        route("settings")
        compose.onNodeWithTag("settings-nav-agents").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("settings-connect-claude-code").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("settings-connect-claude-code").performClick()
        compose.waitUntil(15_000) {
            Files.exists(home.resolve(".claude/skills/ruleblend/SKILL.md"))
        }
        route("place")
        val global = uiTarget("place", "agent:claude-code", "agents", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(global))
        compose.onNodeWithTag(global).performClick()
        compose.onNodeWithTag("place-tab-skills").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("place-builtin:skill:${home.resolve(".claude/skills/ruleblend").projectKey()}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(Files.exists(home.resolve(".claude/skills/ruleblend/SKILL.md")))
        compose.onNodeWithText(EnStrings.placeBuiltInReadOnly).assertIsDisplayed()
        compose.onNodeWithTag("place-tab-mcp").performClick()
        val mcpSemantics = compose.onAllNodes(isRoot()).let { roots ->
            roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
        }
        assertTrue(mcpSemantics.contains("place-builtin:"), mcpSemantics)
        assertTrue(home.resolve(".claude/.claude.json").readText().contains("ruleblend"))
    }
}
