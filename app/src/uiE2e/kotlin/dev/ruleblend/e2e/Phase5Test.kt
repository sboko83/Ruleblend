package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import dev.ruleblend.app.App
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.hashContent
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

/** F-01..F-09 go through the root UI and inspect the isolated files after each write. */
class Phase5Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val other = Path.of(root.resolve("projects/B").projectKey())
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
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                App(openConfigStore(), onThemeModeChanged = {}, createTranslator = { NoTranslation })
            }
        }
        present("nav-place")
    }

    private fun present(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun route(name: String) {
        step = "navigate:$name"
        // A synthetic click can land on the layer of a tooltip that is still closing; retry within the bound.
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun add(path: Path) {
        route("place")
        ports.nextDirectory = path
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { path.projectKey() in openConfigStore().load().projects }
    }

    private fun select(path: Path) {
        route("place")
        val recent = uiTarget("place", "project:${path.projectKey()}", "recent", "select")
        val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${path.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        present("place-palette-search")
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        present(row("GROUP", "all"))
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun row(kind: String, id: String) = uiTarget("library", "$kind:$id", "catalog", "select")
    private fun inspector(kind: String, id: String) = uiTarget("library", "$kind:$id", "inspector", "selected")

    private fun create(id: String, body: String = "Body $id v1") {
        step = "create:$id"
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-rule").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-rule").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/$id.md")) }
        inspect(id)
        compose.onNodeWithTag(inspector("RULE", id)).performScrollToNode(hasTestTag("library-inspector-edit"))
        compose.onNodeWithTag("library-inspector-edit").performClick()
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement(body)
        saveEditor()
        compose.waitUntil(15_000) { library.resolve("blocks/$id.md").readText().contains(body) }
    }

    private fun saveEditor() {
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
    }

    private fun inspect(id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        present(row("RULE", id))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector("RULE", id)).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(row("RULE", id)).performClick(); false }
        }
    }

    private fun createSkill(id: String) {
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-skill").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-skill").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        compose.onNodeWithTag("library-create-description").performTextInput("Synthetic skill")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("skills/$id/files/SKILL.md")) }
    }

    private fun createMcp(id: String) {
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-mcp").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-mcp").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/$id.md")) }
    }

    private fun install(id: String, path: Path = project) {
        select(path)
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val tag = uiTarget("palette", "RULE:$id", "project:${path.projectKey()}", "install")
        compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitUntil(15_000) {
            val file = path.resolve("AGENTS.md")
            Files.exists(file) && file.readText().contains("Body $id")
        }
    }

    private fun homeScan() {
        route("home")
        compose.onNodeWithText(EnStrings.homeRescan).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.homeScanning).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun resolve() {
        homeScan()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.homeResolveAll).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(EnStrings.homeResolveAll).performClick()
        present("route-resolve")
    }

    private fun coverage() {
        route("coverage")
        present("coverage-row-type:RULE")
    }

    private fun markRule(id: String) {
        if (compose.onAllNodesWithTag("coverage-mark-type:RULE/RULE:$id").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("coverage-row-type:RULE").performClick()
            present("coverage-mark-type:RULE/RULE:$id")
        }
        val mark = compose.onNodeWithTag("coverage-mark-type:RULE/RULE:$id")
        if (runCatching { mark.assertIsOff() }.isSuccess) mark.performSemanticsAction(SemanticsActions.OnClick)
        mark.assertIsOn()
    }

    private fun coveragePlan(action: String, target: String? = null) {
        compose.onNodeWithTag("coverage-bulk-$action").performClick()
        if (target != null) compose.onNodeWithTag("coverage-bulk-target-$target").performClick()
        present("coverage-bulk-plan")
    }

    private fun modified(id: String, path: Path = project, text: String = "Local $id") {
        val file = path.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Body $id v1", text))
    }

    private fun content(path: Path): String = path.resolve("AGENTS.md").readText()

    @Test fun F01_homeStatesAndCountsMatchDisk() {
        start()
        route("home")
        compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.homeEmptyLibraryTitle).fetchSemanticsNodes().isNotEmpty() }
        add(project)
        create("one")
        install("one")
        homeScan()
        compose.onNodeWithText(EnStrings.homeAllClearTitle).assertIsDisplayed()
        assertTrue(content(project).contains("Body one v1"))
        inspect("one")
        compose.onNodeWithTag("library-inspector-edit").performClick()
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Body one v2")
        saveEditor()
        homeScan()
        compose.onNodeWithText(EnStrings.homeUpdates(1, 1)).assertIsDisplayed()
        modified("one")
        homeScan()
        compose.onNodeWithText(EnStrings.homeConflicts(1, 1)).assertIsDisplayed()
        assertTrue(content(project).contains("Local one"))
        other.resolve("AGENTS.md").writeText("# Foreign project rule\n")
        add(other)
        homeScan()
        compose.onNodeWithText(EnStrings.homeUnadopted(1)).assertIsDisplayed()
        assertEquals("# Foreign project rule\n", content(other))
    }

    @Test fun F02_crossSurfaceNavigationKeepsCorrectPlace() {
        start(); add(project); add(other); create("one"); install("one"); install("one", other)
        modified("one", other)
        homeScan()
        compose.onNodeWithText(EnStrings.homeConflicts(1, 1)).performClick()
        compose.onNodeWithText(EnStrings.homeRowModified(1)).performClick()
        present("route-place")
        compose.onNodeWithTag("route-place").assertIsDisplayed()
        val otherRestore = uiTarget("place-file", "RULE:one", other.resolve("AGENTS.md").toString(), "restore")
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(otherRestore))
        compose.onNodeWithTag(otherRestore).assertIsDisplayed()
        assertTrue(content(other).contains("Local one"))
        inspect("one")
        compose.onNodeWithTag(inspector("RULE", "one"))
            .performScrollToNode(hasTestTag("library-usage:project:${project.projectKey()}"))
        compose.onNodeWithTag("library-usage:project:${project.projectKey()}").performClick()
        present("route-place")
        compose.onNodeWithTag("route-place").assertIsDisplayed()
        val projectEdit = uiTarget("place-file", "RULE:one", project.resolve("AGENTS.md").toString(), "edit")
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(projectEdit))
        compose.onNodeWithTag(projectEdit).assertIsDisplayed()
        coverage()
        compose.onNodeWithTag("coverage-open-resolve").performClick()
        present("route-resolve")
        coverage()
        compose.onNodeWithTag("coverage-agents-panel").performClick()
        compose.onNodeWithTag("coverage-agent-settings").performClick()
        present("route-settings")
        coverage()
        compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        compose.onNodeWithTag("coverage-place-place:project:${other.projectKey()}").performClick()
        // Opening a place reloads the fleet on IO before switching the route.
        present("route-place")
        compose.onNodeWithTag("route-place").assertIsDisplayed()
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(otherRestore))
        compose.onNodeWithTag(otherRestore).assertIsDisplayed()
    }

    @Test fun F03_matrixAggregatesFiltersAndScope() {
        start(); add(project); add(other); create("global"); create("scoped")
        createSkill("available-skill"); createMcp("available-mcp")
        install("global")
        inspect("scoped")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-edit").performClick(); false }
        }
        compose.onNodeWithTag("library-scope-picker").performClick()
        compose.onNodeWithTag("library-scope:$project").performClick()
        saveEditor()
        compose.waitUntil(15_000) { openConfigStore().load().ruleScopes["scoped"] == project.projectKey() }
        coverage()
        compose.onNodeWithTag("coverage-row-type:RULE").performClick()
        compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        compose.onNodeWithTag("coverage-cell-type:RULE/RULE:scoped@place:project:${other.projectKey()}").assertIsDisplayed()
        assertTrue(
            compose.onNodeWithTag("coverage-cell-type:RULE/RULE:scoped@place:project:${other.projectKey()}")
                .printToString().contains("—"),
        )
        compose.onNodeWithTag("coverage-row-type:MCP").performClick()
        compose.onNodeWithTag("coverage-column-agents").performClick()
        val piMcp = "coverage-cell-type:MCP/MCP:available-mcp@place:agent:pi"
        compose.onNodeWithTag(piMcp).assertIsDisplayed()
        assertTrue(compose.onNodeWithTag(piMcp).printToString().contains("Install here", ignoreCase = true))
        compose.onNodeWithTag("coverage-column-agents").performClick()
        compose.onNodeWithTag("coverage-column-agents").assertIsDisplayed()
        compose.onNodeWithTag("coverage-filter-rule").performClick()
        compose.onNodeWithTag("coverage-row-type:RULE").assertDoesNotExist()
        compose.onNodeWithTag("coverage-filter-rule").performClick()
        compose.onNodeWithTag("coverage-filter-installed").performClick()
        compose.onNodeWithTag("coverage-cell-type:RULE/RULE:global@place:project:${project.projectKey()}").assertIsDisplayed()
        compose.onNodeWithTag("coverage-filter-installed").performClick()
        compose.onNodeWithTag("coverage-column-agents").performClick()
        compose.onNodeWithTag("coverage-column-agents").assertIsDisplayed()
        compose.onNodeWithTag(piMcp).assertIsDisplayed()
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
    }

    @Test fun F04_aggregateDoesNotWriteButLeafCellDoes() {
        start(); add(project); add(other); create("one"); coverage()
        val file = project.resolve("AGENTS.md")
        val otherFile = other.resolve("AGENTS.md")
        compose.onNodeWithTag("coverage-cell-type:RULE@ungrouped").performClick()
        compose.onNodeWithTag("coverage-row-type:RULE").performClick()
        // One object over two places is still an aggregate: a click must not write to both.
        compose.onNodeWithTag("coverage-cell-type:RULE/RULE:one@ungrouped").performClick()
        compose.waitForIdle()
        assertFalse(Files.exists(file) || Files.exists(otherFile))
        val leaf = "coverage-cell-type:RULE/RULE:one@place:project:${project.projectKey()}"
        compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        compose.onNodeWithTag(leaf).performClick()
        compose.waitUntil(15_000) { Files.exists(file) && file.readText().contains("Body one v1") }
        // Wait for the refreshed status before leaving the screen and cancelling its action scope.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(leaf) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithTag(leaf).printToString().contains(EnStrings.coverageRemoveHere, ignoreCase = true)
        }
        assertFalse(Files.exists(otherFile))
        select(project)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("one")
        present(uiTarget("palette", "RULE:one", "project:${project.projectKey()}", "remove"))
        coverage()
        if (compose.onAllNodesWithTag("coverage-mark-type:RULE/RULE:one").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("coverage-row-type:RULE").performClick()
        if (compose.onAllNodesWithTag(leaf).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        // The first write refreshes the fleet after the file appears; wait for its UI lock to clear.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(leaf) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(leaf).performClick()
        compose.waitUntil(15_000) { !Files.exists(file) || !file.readText().contains("Body one v1") }
        select(project)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("one")
        present(uiTarget("palette", "RULE:one", "project:${project.projectKey()}", "install"))
        assertFalse(Files.exists(otherFile))
    }

    @Test fun F05_bulkPreviewCancelApplyAndLibraryEntry() {
        start(); add(project); add(other); create("one")
        coverage(); markRule("one")
        coveragePlan("install", "ungrouped")
        compose.onNodeWithText(EnStrings.coverageBulkPlanText(2, 1, 2)).assertIsDisplayed()
        assertFalse(Files.exists(project.resolve("AGENTS.md")))
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertFalse(Files.exists(project.resolve("AGENTS.md")))
        coveragePlan("install", "ungrouped")
        compose.onNodeWithTag("dialog-confirm").performClick()
        present("coverage-bulk-report")
        compose.waitUntil(15_000) { content(project).contains("Body one v1") && content(other).contains("Body one v1") }
        val coverageFiles = listOf(project, other).associateWith { Files.readAllBytes(it.resolve("AGENTS.md")).toList() }
        markRule("one")
        coveragePlan("remove", "ungrouped")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) {
            listOf(project, other).all { path ->
                val file = path.resolve("AGENTS.md")
                !Files.exists(file) || !file.readText().contains("Body one v1")
            }
        }
        inspect("one")
        compose.onNode(hasTestTag("library-row-mark") and hasAnyAncestor(hasTestTag(row("RULE", "one"))))
            .performClick()
        compose.onNodeWithTag("library-bulk-install").performClick()
        compose.onNodeWithTag("library-install-place:project:${project.projectKey()}").performClick()
        compose.onNodeWithTag("library-install-place:project:${other.projectKey()}").performClick()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertFalse(Files.exists(project.resolve("AGENTS.md")) && content(project).contains("Body one v1"))
        compose.onNodeWithTag("library-bulk-install").performClick()
        compose.onNodeWithTag("library-install-place:project:${project.projectKey()}").performClick()
        compose.onNodeWithTag("library-install-place:project:${other.projectKey()}").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { content(project).contains("Body one v1") && content(other).contains("Body one v1") }
        assertEquals(coverageFiles, listOf(project, other).associateWith { Files.readAllBytes(it.resolve("AGENTS.md")).toList() })
        inspect("one")
        compose.onNodeWithTag("library-inspector-edit").performClick()
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Body one v2")
        saveEditor()
        coverage()
        compose.onNodeWithContentDescription(EnStrings.homeRescan).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.coverageChipUpdates(2, 0, 2)).fetchSemanticsNodes().isNotEmpty()
        }
        markRule("one")
        coveragePlan("update")
        compose.onNodeWithText(EnStrings.coverageBulkPlanText(2, 1, 2)).assertIsDisplayed()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { content(project).contains("Body one v2") && content(other).contains("Body one v2") }
        other.resolve("AGENTS.md").writeText(content(other).replace("Body one v2", "Local one"))
        compose.onNodeWithContentDescription(EnStrings.homeRescan).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.coverageChipConflicts(1, 0, 1)).fetchSemanticsNodes().isNotEmpty()
        }
        markRule("one")
        coveragePlan("remove", "ungrouped")
        compose.onNodeWithTag("dialog-confirm").performClick()
        present("coverage-bulk-report")
        compose.waitUntil(15_000) {
            val file = project.resolve("AGENTS.md")
            !Files.exists(file) || !file.readText().contains("Body one v2")
        }
        assertTrue(content(other).contains("Local one"))
        compose.onNodeWithText(EnStrings.libInstallSkippedModified(1), substring = true).assertIsDisplayed()
    }

    @Test fun F06_resolveSeparatesEditedAmbiguousAndLegacy() {
        start(); add(project); add(other); create("one"); create("two")
        install("one"); install("two")
        install("one", other)
        modified("one", project)
        modified("two", project)
        modified("one", other)
        val legacy = other.resolve("CLAUDE.md")
        legacy.writeText("<!-- kb old v1 ${hashContent("Legacy body")} -->\nLegacy body\n<!-- kb:end -->\n")
        resolve()
        present("resolve-class-hand_edited")
        present("resolve-row-project:${other.projectKey()}@AGENTS.md#one")
        compose.onNodeWithTag("resolve-mark-all").assertIsDisplayed()
        compose.onNodeWithTag("resolve-class-ambiguous").performClick()
        present("resolve-row-project:${project.projectKey()}@AGENTS.md#one")
        compose.onNodeWithTag("resolve-mark-all").assertDoesNotExist()
        compose.onNodeWithTag("resolve-class-legacy").performClick()
        present("resolve-row-project:${other.projectKey()}@CLAUDE.md")
        compose.onNodeWithTag("resolve-mark-all").assertDoesNotExist()
        assertTrue(legacy.readText().contains("Legacy body"))
    }

    @Test fun F07_resolveBaseOverrideAndSkip() {
        val third = root.resolve("projects/C")
        Files.createDirectories(third)
        start(); add(project); add(other); add(third); create("one")
        install("one"); install("one", other); install("one", third)
        modified("one", project, "Local project")
        modified("one", other, "Local other")
        modified("one", third, "Local third")
        resolve()
        val first = "resolve-mark-project:${project.projectKey()}#one"
        val second = "resolve-mark-project:${other.projectKey()}#one"
        present(first); present(second)
        compose.onNodeWithTag("resolve-plan").assertIsDisplayed()
        for (mark in listOf(first, second, "resolve-mark-project:${third.projectKey()}#one")) compose.onNodeWithTag(mark).assertIsOff()
        compose.onNodeWithTag("resolve-mark-all").performClick()
        for (mark in listOf(first, second, "resolve-mark-project:${third.projectKey()}#one")) compose.onNodeWithTag(mark).assertIsOn()
        compose.onNodeWithTag("resolve-strategy-save_as_version").performClick()
        compose.onNodeWithTag("resolve-override-project:${project.projectKey()}#one").performClick()
        compose.onNodeWithTag("resolve-override-project:${project.projectKey()}#one-restore").performClick()
        compose.onNodeWithTag("resolve-override-project:${other.projectKey()}#one").performClick()
        compose.onNodeWithTag("resolve-override-project:${other.projectKey()}#one-skip").performClick()
        compose.onNodeWithText(EnStrings.resolvePlanSkipped(1)).assertIsDisplayed()
        compose.onNodeWithTag("resolve-apply").performClick()
        present("resolve-report")
        compose.waitUntil(15_000) { content(project).contains("Body one v1") }
        assertTrue(content(other).contains("Local other"))
        assertTrue(library.resolve("blocks/one.md").readText().contains("Local third"))
        assertFalse(library.resolve("blocks/one.md").readText().contains("Local other"))
    }

    @Test fun F08_partialBatchReportsFailedPlaceAndRescan() {
        start(); add(project); add(other); create("one")
        coverage(); markRule("one"); coveragePlan("install", "ungrouped")
        val blocked = Files.createDirectory(other.resolve("AGENTS.md"))
        val sentinel = blocked.resolve("occupied")
        sentinel.writeText("Block atomic replacement on every platform")
        try {
            compose.onNodeWithTag("dialog-confirm").performClick()
            present("coverage-bulk-report")
            compose.onNodeWithText(EnStrings.libInstallFailedCount(1), substring = true).assertIsDisplayed()
        } finally {
            Files.delete(sentinel)
            Files.delete(blocked)
        }
        compose.waitUntil(15_000) { content(project).contains("Body one v1") }
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
        assertFalse(Files.exists(other.resolve("AGENTS.md")))
        compose.onNodeWithContentDescription(EnStrings.homeRescan).performClick()
        // The failed place stays in the matrix as not installed, so the same batch can finish it.
        val failed = "coverage-cell-type:RULE/RULE:one@place:project:${other.projectKey()}"
        if (compose.onAllNodesWithTag("coverage-mark-type:RULE/RULE:one").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("coverage-row-type:RULE").performClick()
        if (compose.onAllNodesWithTag(failed).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(failed).fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithTag(failed).printToString().contains(EnStrings.coverageInstallHere, ignoreCase = true)
        }
        assertTrue(compose.onNodeWithTag("coverage-cell-type:RULE/RULE:one@place:project:${project.projectKey()}")
            .printToString().contains(EnStrings.coverageRemoveHere, ignoreCase = true))
        markRule("one"); coveragePlan("install", "place:project:${other.projectKey()}")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(other.resolve("AGENTS.md")) && content(other).contains("Body one v1") }
        assertEquals(1, Regex("Body one v1").findAll(content(project)).count())
    }

    @Test fun F09_hideAgentAcrossHomeSettingsAndCoverage() {
        val globalFile = home.resolve(".claude/CLAUDE.md")
        if (stage == "first") globalFile.writeText("# Foreign agent rule\n")
        start()
        if (stage == "first") {
            add(project); create("one"); install("one")
            homeScan()
            compose.onNodeWithText(EnStrings.homeHideAgent).performClick()
            compose.waitUntil(15_000) { "claude-code" in openConfigStore().load().hiddenAgents }
            compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.homeHideAgent).fetchSemanticsNodes().isEmpty() }
            route("settings")
            compose.onNodeWithTag("settings-nav-agents").performClick()
            compose.onNodeWithTag("settings-visible-claude-code").performClick()
            compose.waitUntil(15_000) { "claude-code" !in openConfigStore().load().hiddenAgents }
            compose.onNodeWithTag("settings-visible-claude-code").performClick()
            compose.waitUntil(15_000) { "claude-code" in openConfigStore().load().hiddenAgents }
        } else {
            assertTrue("claude-code" in openConfigStore().load().hiddenAgents)
            val before = Files.readAllBytes(project.resolve("AGENTS.md")).toList()
            coverage()
            compose.onNodeWithTag("coverage-agents-panel").performClick()
            compose.onNodeWithTag("coverage-agent-claude-code").performClick()
            compose.waitUntil(15_000) { "claude-code" !in openConfigStore().load().hiddenAgents }
            homeScan()
            // Home offers to hide the returned assistant again: every surface reads the same list.
            compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.homeHideAgent).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, Files.readAllBytes(project.resolve("AGENTS.md")).toList())
            assertEquals("# Foreign agent rule\n", globalFile.readText())
        }
    }
}
