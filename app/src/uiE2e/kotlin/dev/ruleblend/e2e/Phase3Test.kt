package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.semantics.SemanticsActions
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

/** Conflict and foreign-entry scenarios use the full root UI and a fresh owned home per method. */
class Phase3Test {
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
                runCatching { snapshot("semantics.txt") }
                runCatching {
                    val roots = compose.onAllNodes(isRoot())
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

    private fun snapshot(name: String) {
        val roots = compose.onAllNodes(isRoot())
        artifacts.resolve(name).writeText(
            roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() },
        )
    }

    private fun route(name: String) {
        step = "navigate:$name"
        compose.onNodeWithTag("nav-$name").performClick()
        // A synthetic click can land on the layer of a tooltip that is still closing; retry within the bound.
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("nav-$name").assertIsSelected()
                compose.onNodeWithTag("route-$name").assertIsDisplayed()
            }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun add(path: Path) {
        route("place")
        ports.nextDirectory = path
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { Files.exists(config) && config.readText().contains(path.projectJsonText()) }
    }

    private fun select(path: Path) {
        route("place")
        val recent = uiTarget("place", "project:${path.projectKey()}", "recent", "select")
        val tag = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${path.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(path.projectKey()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun rescan() {
        // Selecting another place and returning reloads the disk through the same UI path as a user.
        select(other)
        select(project)
    }

    private fun chooseGlobal(agent: String) {
        route("place")
        val tag = uiTarget("place", "agent:$agent", "agents", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun scopeEntries(): String = Regex("\"ruleScopes\"\\s*:\\s*\\{([^}]*)}")
        .find(config.readText())?.groupValues?.get(1).orEmpty()

    private fun blockFiles(): Set<String> =
        Files.list(library.resolve("blocks")).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".md") }.toList().toSet() }

    private fun target(id: String, file: Path, action: String) = uiTarget("place-file", "RULE:$id", file.toString(), action)

    private fun present(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Opens the New menu and picks [item]; a click lost while the catalog settles is retried. */
    private fun openNew(item: String) {
        val entry = "library-new-$item"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(entry).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag(entry).performClick()
    }

    private fun create(kind: String, id: String, content: String) {
        step = "create:$kind:$id"
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-editor-back").performClick()
        }
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        val all = uiTarget("library", "GROUP:all", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(all).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-search").performTextReplacement("")
        openNew(kind)
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        if (kind == "skill") compose.onNodeWithTag("library-create-description").performTextInput("Synthetic skill")
        compose.onNodeWithTag("dialog-confirm").performClick()
        val saved = if (kind == "skill") library.resolve("skills/$id/files/SKILL.md") else library.resolve("blocks/$id.md")
        compose.waitUntil(15_000) { Files.exists(saved) }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-create-name").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "${kind.uppercase()}:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performClick()
        compose.onNodeWithTag("library-inspector-edit").performClick()
        when (kind) {
            "skill" -> compose.onNodeWithTag("library-editor-skill-content").performTextReplacement(content)
            "mcp" -> compose.onNodeWithTag("mcp-command").performTextInput(content)
            else -> compose.onNodeWithTag("library-editor-block-content").performTextInput(content)
        }
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { saved.readText().contains(content.substringAfterLast('\n').ifBlank { content }) }
    }

    private fun install(kind: String, id: String, path: Path = project) {
        select(path)
        val place = "project:${path.projectKey()}"
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val tag = uiTarget("palette", "${kind.uppercase()}:$id", place, "install")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        val remove = uiTarget("palette", "${kind.uppercase()}:$id", place, "remove")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(remove).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun action(kind: String, id: String, file: Path, action: String) {
        step = "$action:$kind:$id:$file"
        val tag = uiTarget("place-file", "${kind.uppercase()}:$id", file.toString(), action)
        // A tab click can be lost like any synthetic click, and a tab exposes no selected state: reopen it.
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(tag)) }.isSuccess ||
                run { tab?.let { compose.onNodeWithTag("place-tab-$it").performClick() }; false }
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private var tab: String? = null

    private fun showTab(tab: String) {
        this.tab = tab
        compose.onNodeWithTag("place-tab-$tab").performClick()
    }

    private fun foreign(tag: String) {
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
        compose.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitManagedEntry(kind: String, id: String, entryKey: String) {
        compose.waitUntil(15_000) {
            val state = home.resolve(".ruleblend/$kind-state.json")
            Files.exists(state) && state.readText().contains(id)
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("place-foreign-save:$entryKey").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun clickTag(tag: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun deleteLibrary(kind: String, id: String) {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-editor-back").performClick()
        }
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "${kind.uppercase()}:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        val inspector = uiTarget("library", "${kind.uppercase()}:$id", "inspector", "selected")
        // A click that lands while the filtered list is still settling can be lost; retry until selected.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(row).performClick(); false }
        }
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-delete"))
        clickTag("library-inspector-delete")
        clickTag("dialog-confirm")
        val saved = if (kind == "skill") library.resolve("skills/$id") else library.resolve("blocks/$id.md")
        compose.waitUntil(15_000) { !Files.exists(saved) }
    }

    private fun setupRule(): Path {
        add(project); add(other)
        create("rule", "e2e-rule", "E2E rule v1")
        install("rule", "e2e-rule")
        return project.resolve("AGENTS.md")
    }

    @Test fun O01_externalEditsAreShownAndProtected() {
        start(); add(project); add(other)
        create("rule", "e2e-rule", "E2E rule v1")
        create("skill", "e2e-skill", "---\nname: e2e-skill\ndescription: Synthetic skill\n---\nE2E skill v1")
        create("subagent", "e2e-agent", "E2E agent v1")
        create("mcp", "e2e-server", "/bin/echo")
        for ((kind, id) in listOf("rule" to "e2e-rule", "skill" to "e2e-skill",
            "subagent" to "e2e-agent", "mcp" to "e2e-server")) install(kind, id)
        val rule = project.resolve("AGENTS.md")
        val skill = project.resolve(".claude/skills/e2e-skill/SKILL.md")
        val subagent = project.resolve(".claude/agents/e2e-agent.md")
        val mcp = project.resolve(".mcp.json")
        rule.writeText(rule.readText().replace("E2E rule v1", "Local rule edit"))
        skill.writeText(skill.readText().replace("E2E skill v1", "Local skill edit"))
        subagent.writeText(subagent.readText().replace("E2E agent v1", "Local subagent edit"))
        mcp.writeText(mcp.readText().replace("/bin/echo", "/bin/date"))
        val bytes = listOf(rule, skill, subagent, mcp).associateWith { Files.readAllBytes(it).toList() }
        rescan()
        for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill",
            "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
            compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
            compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "update")).assertDoesNotExist()
        }
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-rule")
        compose.onNodeWithTag(uiTarget("palette", "RULE:e2e-rule", "project:${project.projectKey()}", "remove")).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.errorTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(bytes, bytes.keys.associateWith { Files.readAllBytes(it).toList() })
        action("rule", "e2e-rule", rule, "keep")
        assertEquals(bytes, bytes.keys.associateWith { Files.readAllBytes(it).toList() })
        showTab("skills")
        action("skill", "e2e-skill", project.resolve(".claude/skills"), "keep")
        showTab("subagents")
        action("subagent", "e2e-agent", project.resolve(".claude/agents"), "keep")
        showTab("mcp")
        action("mcp", "e2e-server", mcp, "keep")
        assertEquals(bytes, bytes.keys.associateWith { Files.readAllBytes(it).toList() })
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
    }

    @Test fun O02_ruleStrategiesKeepRestoreAndSaveDifferentResults() {
        start(); val rule = setupRule()
        rule.writeText(rule.readText().replace("E2E rule v1", "Local rule v2"))
        rescan()
        action("rule", "e2e-rule", rule, "keep")
        assertTrue(rule.readText().contains("Local rule v2"))
        assertTrue(library.resolve("blocks/e2e-rule.md").readText().contains("E2E rule v1"))
        rule.writeText(rule.readText().replace("Local rule v2", "Local rule v3"))
        rescan()
        action("rule", "e2e-rule", rule, "save_as_version")
        compose.waitUntil(15_000) { library.resolve("blocks/e2e-rule.md").readText().contains("Local rule v3") }
        assertTrue(library.resolve("blocks/e2e-rule.md").readText().contains("version: 2"))
        rule.writeText(rule.readText().replace("Local rule v3", "Local rule v4"))
        rescan()
        action("rule", "e2e-rule", rule, "restore")
        compose.waitUntil(15_000) { rule.readText().contains("Local rule v3") }
        assertFalse(rule.readText().contains("Local rule v4"))

        create("mcp", "e2e-server", "/bin/echo")
        create("skill", "e2e-skill", "---\nname: e2e-skill\ndescription: Synthetic skill\n---\nE2E skill v1")
        create("subagent", "e2e-agent", "E2E agent v1")
        for ((kind, id) in listOf("mcp" to "e2e-server", "skill" to "e2e-skill", "subagent" to "e2e-agent")) {
            install(kind, id)
        }
        val mcp = project.resolve(".mcp.json")
        val mcpCopies = listOf(mcp, project.resolve(".codex/config.toml"), project.resolve(".kimi-code/mcp.json"))
        val skill = project.resolve(".claude/skills/e2e-skill/SKILL.md")
        val subagent = project.resolve(".claude/agents/e2e-agent.md")
        mcpCopies.forEach { it.writeText(it.readText().replace("/bin/echo", "/bin/date")) }
        skill.writeText(skill.readText().replace("E2E skill v1", "Local skill v2"))
        subagent.writeText(subagent.readText().replace("E2E agent v1", "Local agent v2"))
        rescan(); showTab("skills")
        val skillRoot = project.resolve(".claude/skills")
        val skillRestore = uiTarget("place-file", "SKILL:e2e-skill", skillRoot.toString(), "restore")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(skillRestore)) }.isSuccess
        }
        compose.onNodeWithTag(uiTarget("place-file", "SKILL:e2e-skill", skillRoot.toString(), "save_as_version")).assertDoesNotExist()
        action("skill", "e2e-skill", skillRoot, "restore")
        compose.waitUntil(15_000) { skill.readText().contains("E2E skill v1") }
        showTab("subagents")
        val agentRoot = project.resolve(".claude/agents")
        val agentRestore = uiTarget("place-file", "SUBAGENT:e2e-agent", agentRoot.toString(), "restore")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(agentRestore)) }.isSuccess
        }
        compose.onNodeWithTag(uiTarget("place-file", "SUBAGENT:e2e-agent", agentRoot.toString(), "save_as_version")).assertDoesNotExist()
        action("subagent", "e2e-agent", agentRoot, "restore")
        compose.waitUntil(15_000) { Files.exists(subagent) && subagent.readText().contains("E2E agent v1") }
        showTab("mcp")
        action("mcp", "e2e-server", mcp, "save_as_version")
        compose.waitUntil(15_000) { library.resolve("blocks/e2e-server.md").readText().contains("/bin/date") }
        mcpCopies.forEach { it.writeText(it.readText().replace("/bin/date", "/bin/false")) }
        rescan(); showTab("mcp")
        action("mcp", "e2e-server", mcp, "restore")
        compose.waitUntil(15_000) { mcp.readText().contains("/bin/date") }
        assertFalse(mcp.readText().contains("/bin/false"))
    }

    @Test fun O03_editInLibraryUpdatesSourcePlaceOnly() {
        start(); val rule = setupRule()
        install("rule", "e2e-rule", other)
        create("skill", "e2e-skill", "---\nname: e2e-skill\ndescription: Synthetic skill\n---\nE2E skill v1")
        install("skill", "e2e-skill")
        install("skill", "e2e-skill", other)
        select(project)
        action("rule", "e2e-rule", rule, "edit")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-block-content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("E2E rule v2")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { rule.readText().contains("E2E rule v2") }
        assertTrue(other.resolve("AGENTS.md").readText().contains("E2E rule v1"))
        select(other)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-rule")
        val ruleUpdate = uiTarget("palette", "RULE:e2e-rule", "project:${other.projectKey()}", "update")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(ruleUpdate).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ruleUpdate).assertIsDisplayed()
        select(project); showTab("skills")
        action("skill", "e2e-skill", project.resolve(".claude/skills"), "edit")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-skill-content").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-editor-skill-content").performTextReplacement(
            "---\nname: e2e-skill\ndescription: Synthetic skill\n---\nE2E skill v2",
        )
        compose.onNodeWithTag("library-editor-save").performClick()
        val currentSkill = project.resolve(".claude/skills/e2e-skill/SKILL.md")
        compose.waitUntil(15_000) { currentSkill.readText().contains("E2E skill v2") }
        assertTrue(other.resolve(".claude/skills/e2e-skill/SKILL.md").readText().contains("E2E skill v1"))
        select(other)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-skill")
        val skillUpdate = uiTarget("palette", "SKILL:e2e-skill", "project:${other.projectKey()}", "update")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(skillUpdate).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(skillUpdate).assertIsDisplayed()
    }

    @Test fun O04_foreignObjectsSaveAndAdoptWithoutRewritingFiles() {
        val rule = project.resolve("AGENTS.md")
        rule.writeText("# Foreign rule\nKeep the original rule.\n")
        val skill = project.resolve(".claude/skills/community")
        Files.createDirectories(skill.resolve("assets"))
        skill.resolve("SKILL.md").writeText("---\nname: community\ndescription: Foreign skill\n---\n# Community\n")
        skill.resolve("assets/example.txt").writeText("resource bytes\n")
        val subagent = project.resolve(".claude/agents/reviewer.md")
        Files.createDirectories(subagent.parent)
        subagent.writeText("---\nname: Review assistant\ndescription: Foreign reviewer\n---\nCheck all changes.\n")
        val mcp = project.resolve(".mcp.json")
        mcp.writeText("""{"mcpServers":{"context7":{"type":"stdio","command":"/bin/echo","args":["synthetic"]}}}""")
        val originals = listOf(rule, skill.resolve("SKILL.md"), skill.resolve("assets/example.txt"), subagent, mcp)
            .associateWith { Files.readAllBytes(it).toList() }
        start(); add(project); select(project)
        val found = "place-found-adopt:$rule"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(found)) }.isSuccess
        }
        compose.onNodeWithTag(found).performClick()
        compose.onNodeWithTag("adopt-name").performTextReplacement("foreign-rule")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) {
            val saved = library.resolve("blocks/foreign-rule.md")
            Files.exists(saved) && saved.readText().contains("Keep the original rule.")
        }
        showTab("skills")
        foreign("place-foreign-save:skill:$skill")
        compose.waitUntil(15_000) { Files.exists(library.resolve("skills/community/files/assets/example.txt")) }
        waitManagedEntry("skill", "community", "skill:$skill")
        showTab("subagents")
        foreign("place-foreign-save:subagent:$subagent")
        compose.waitUntil(15_000) {
            val saved = library.resolve("blocks/reviewer.md")
            Files.exists(saved) && saved.readText().contains("Check all changes.")
        }
        waitManagedEntry("subagent", "reviewer", "subagent:$subagent")
        showTab("mcp")
        foreign("place-foreign-save:mcp:$mcp:context7")
        compose.waitUntil(15_000) {
            val saved = library.resolve("blocks/context7.md")
            Files.exists(saved) && saved.readText().contains("/bin/echo")
        }
        waitManagedEntry("mcp", "context7", "mcp:$mcp:context7")
        assertEquals(originals, originals.keys.associateWith { Files.readAllBytes(it).toList() })
        assertEquals("resource bytes\n", library.resolve("skills/community/files/assets/example.txt").readText())
        assertTrue(library.resolve("blocks/reviewer.md").readText().contains("Foreign reviewer"))
        assertTrue(library.resolve("blocks/context7.md").readText().contains("synthetic"))
    }

    @Test fun O05_cancelledReplaceAndSaveAdoptionRespectSource() {
        val rule = project.resolve("AGENTS.md")
        rule.writeText("# Original instructions\nKeep this exact text.\n")
        val skill = project.resolve(".claude/skills/community")
        Files.createDirectories(skill)
        val skillFile = skill.resolve("SKILL.md")
        skillFile.writeText("---\nname: community\ndescription: Synthetic\n---\n# Keep me\n")
        val mcp = project.resolve(".mcp.json")
        mcp.writeText("""{"mcpServers":{"community":{"type":"stdio","command":"/bin/echo"}}}""")
        start(); add(project); select(project)
        val found = "place-found-adopt:$rule"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(found)) }.isSuccess
        }
        compose.onNodeWithTag(found).performClick()
        compose.onNodeWithTag("adopt-name").performTextReplacement("original-instructions")
        compose.onNodeWithTag("adopt-replace").performClick()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals("# Original instructions\nKeep this exact text.\n", rule.readText())
        assertFalse(Files.exists(library.resolve("blocks/original-instructions.md")))
        showTab("skills")
        val before = skillFile.readText()
        foreign("place-foreign-save:skill:$skill")
        compose.waitUntil(15_000) { Files.exists(library.resolve("skills/community")) }
        waitManagedEntry("skill", "community", "skill:$skill")
        assertEquals(before, skillFile.readText())
        compose.onNodeWithTag("place-foreign-save:skill:$skill").assertDoesNotExist()
        showTab("mcp")
        val mcpBefore = mcp.readText()
        foreign("place-foreign-save:mcp:$mcp:community")
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/community.md")) }
        waitManagedEntry("mcp", "community", "mcp:$mcp:community")
        assertEquals(mcpBefore, mcp.readText())
        // Taken over means managed at library version 1. Update stays offered: only Claude's root
        // holds the copy, and the other agents of the project can still take it.
        for ((kind, id) in listOf("SKILL" to "community", "MCP" to "community")) {
            compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
            val remove = uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "remove")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(remove).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(library.resolve("blocks/community.md").readText().contains("version: 1"))
    }

    @Test fun O06_hideShowAndDeleteTargetOnlyOneForeignEntry() {
        val mcp = project.resolve(".mcp.json")
        mcp.writeText("""{"mcpServers":{"first":{"type":"stdio","command":"/bin/echo"},"second":{"type":"stdio","command":"/bin/date"}}}""")
        val skill = project.resolve(".claude/skills/first")
        val neighborSkill = project.resolve(".claude/skills/second")
        for (directory in listOf(skill, neighborSkill)) {
            Files.createDirectories(directory)
            directory.resolve("SKILL.md").writeText("---\nname: ${directory.fileName}\ndescription: Synthetic\n---\n# Keep\n")
        }
        start(); add(project); select(project); showTab("mcp")
        val first = "mcp:$mcp:first"
        val second = "mcp:$mcp:second"
        foreign("place-foreign-hidden:$first")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("place-show-hidden").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(mcp.readText().contains("first"))
        assertTrue(mcp.readText().contains("second"))
        compose.onNodeWithTag("place-show-hidden").performClick()
        foreign("place-foreign-hidden:$first")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("place-show-hidden").fetchSemanticsNodes().isEmpty() }
        foreign("place-foreign-remove:$first")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertTrue(mcp.readText().contains("first"))
        foreign("place-foreign-remove:$first")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !mcp.readText().contains("\"first\"") }
        assertTrue(mcp.readText().contains("\"second\""))
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
        compose.onNodeWithTag("place-foreign-hidden:$second").assertIsDisplayed()
        showTab("skills")
        val key = "skill:$skill"
        foreign("place-foreign-hidden:$key")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("place-foreign-hidden:$key").fetchSemanticsNodes().isNotEmpty() }
        foreign("place-foreign-hidden:$key")
        foreign("place-foreign-remove:$key")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertTrue(Files.exists(skill.resolve("SKILL.md")))
        foreign("place-foreign-remove:$key")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !Files.exists(skill) }
        assertTrue(Files.exists(neighborSkill.resolve("SKILL.md")))
    }

    @Test fun O07_orphanRestoresFromRecordedCopy() {
        start(); add(project); add(other)
        create("skill", "orphan-skill", "---\nname: orphan-skill\ndescription: Synthetic skill\n---\n# Saved skill")
        install("skill", "orphan-skill")
        val installed = project.resolve(".claude/skills/orphan-skill")
        val original = installed.resolve("SKILL.md").readText()
        deleteLibrary("skill", "orphan-skill")
        select(project); showTab("skills")
        foreign("place-orphan-restore:skill:$installed")
        compose.waitUntil(15_000) { Files.exists(library.resolve("skills/orphan-skill/files/SKILL.md")) }
        assertEquals(original, installed.resolve("SKILL.md").readText())
        assertTrue(home.resolve(".ruleblend/skill-state.json").readText().contains("orphan-skill"))

        create("subagent", "orphan-agent", "Saved agent body")
        create("mcp", "orphan-server", "/bin/echo")
        install("subagent", "orphan-agent")
        install("mcp", "orphan-server")
        val agent = project.resolve(".claude/agents/orphan-agent.md")
        val mcp = project.resolve(".mcp.json")
        val agentBefore = agent.readText()
        val mcpBefore = mcp.readText()
        deleteLibrary("subagent", "orphan-agent")
        deleteLibrary("mcp", "orphan-server")
        select(project); showTab("subagents")
        foreign("place-orphan-restore:subagent:$agent")
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/orphan-agent.md")) }
        showTab("mcp")
        foreign("place-orphan-restore:mcp:$mcp:orphan-server")
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/orphan-server.md")) }
        assertEquals(agentBefore, agent.readText())
        assertEquals(mcpBefore, mcp.readText())
        val unrelated = project.resolve(".claude/skills/foreign")
        Files.createDirectories(unrelated)
        unrelated.resolve("SKILL.md").writeText("# Foreign\n")
        rescan(); showTab("skills")
        compose.onNodeWithTag("place-foreign-save:skill:$unrelated").assertIsDisplayed()
        deleteLibrary("skill", "orphan-skill")
        select(project); showTab("skills")
        foreign("place-orphan-remove:skill:$installed")
        present("dialog-confirm")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !Files.exists(installed) }
        assertTrue(Files.exists(unrelated.resolve("SKILL.md")))

        // A sidecar record whose copy is gone claims nothing: the object reads as not installed and
        // installing it again is not refused by the stale record.
        create("skill", "stale-skill", "---\nname: stale-skill\ndescription: Synthetic skill\n---\n# Stale")
        install("skill", "stale-skill")
        val stale = project.resolve(".claude/skills/stale-skill")
        val copies = Files.walk(project).use { paths ->
            paths.filter { it.fileName.toString() == "stale-skill" && it.parent.fileName.toString() == "skills" }.toList()
        }
        assertTrue(stale in copies, copies.toString())
        copies.forEach { it.toFile().deleteRecursively() }
        assertTrue(home.resolve(".ruleblend/skill-state.json").readText().contains("stale-skill"))
        rescan(); showTab("skills")
        compose.onNodeWithTag("place-orphan-restore:skill:$stale").assertDoesNotExist()
        compose.onNodeWithTag("place-foreign-save:skill:$stale").assertDoesNotExist()
        compose.onNodeWithTag("place-palette-search").performTextReplacement("stale-skill")
        val reinstall = uiTarget("palette", "SKILL:stale-skill", "project:${project.projectKey()}", "install")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(reinstall).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(reinstall).performClick()
        compose.waitUntil(15_000) { Files.exists(stale.resolve("SKILL.md")) }
    }

    @Test fun O08_adoptSectionsCopyAndMovePreserveUnselectedText() {
        val copy = project.resolve("AGENTS.md")
        copy.writeText("Hand preface.\n\n# Alpha\nFirst synthetic rule.\n\n# Beta\nSecond synthetic rule.\n")
        val move = other.resolve("AGENTS.md")
        move.writeText("Keep this preface.\n\n# Gamma\nMove this rule.\n\n# Hand note\nStays hand-written.\n\n# Delta\nAnother moved rule.\n")
        val global = home.resolve(".claude/CLAUDE.md")
        global.writeText("# Alpha\nFirst synthetic rule revised.\n\n# Beta\nSecond synthetic rule.\n\n# Global\nA global rule.\n")
        start(); add(project); add(other); select(project)
        val copyTag = "place-found-adopt:$copy"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(copyTag)) }.isSuccess
        }
        compose.onNodeWithTag(copyTag).performClick()
        compose.onNodeWithTag("adopt-section:hand-preface").performClick()
        compose.onNodeWithTag("adopt-section-name:alpha").assertTextEquals("alpha")
        compose.onNodeWithTag("adopt-section-name:beta").assertTextEquals("beta")
        compose.onNodeWithTag("adopt-section-status:alpha").assertTextEquals(EnStrings.intAdoptSectionNew)
        compose.onNodeWithTag("adopt-section-status:beta").assertTextEquals(EnStrings.intAdoptSectionNew)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/alpha.md")) && Files.exists(library.resolve("blocks/beta.md")) }
        assertEquals(setOf("alpha.md", "beta.md"), blockFiles())
        assertEquals("Hand preface.\n\n# Alpha\nFirst synthetic rule.\n\n# Beta\nSecond synthetic rule.\n", copy.readText())
        assertTrue(Regex("\"alpha\"\\s*:\\s*\"${Regex.escape(project.projectJsonText())}\"").containsMatchIn(scopeEntries()))
        select(other)
        val moveTag = "place-found-adopt:$move"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(moveTag)) }.isSuccess
        }
        compose.onNodeWithTag(moveTag).performClick()
        compose.onNodeWithTag("adopt-section:keep-this-preface").performClick()
        compose.onNodeWithTag("adopt-section:hand-note").performClick()
        compose.onNodeWithTag("adopt-replace").performClick()
        // An rb1 file holds one run: Gamma and Delta around a kept note cannot both become managed
        // without moving the note, so the dialog refuses before anything reaches the library.
        present("adopt-order-blocked")
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("adopt-section:delta").performClick()
        compose.onNodeWithTag("adopt-order-blocked").assertDoesNotExist()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/gamma.md")) }
        val beta = library.resolve("blocks/beta.md").readText()
        compose.waitUntil(15_000) { move.readText().contains("<!-- rb1 ") }
        val adopted = move.readText()
        assertEquals(1, Regex("<!-- rb1 ").findAll(adopted).count())
        assertFalse(Files.exists(library.resolve("blocks/delta.md")))
        // The run takes Gamma's place; every other line keeps its position.
        val order = listOf("Keep this preface.", "<!-- rb1 ", "Move this rule.", "<!-- rb:end -->", "Stays hand-written.", "Another moved rule.")
            .map(adopted::indexOf)
        assertTrue(order.all { it >= 0 } && order == order.sorted(), adopted)
        // Delta now sits beyond the kept note from the run: replacing it is refused, saving a copy is not.
        // Reselecting reloads the file, so the dialog opens on what the adoption left.
        select(project)
        select(other)
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(moveTag)) }.isSuccess
        }
        compose.onNodeWithTag(moveTag).performClick()
        compose.onNodeWithTag("adopt-section:gamma").assertDoesNotExist()
        compose.onNodeWithTag("adopt-section:keep-this-preface").performClick()
        compose.onNodeWithTag("adopt-section:hand-note").performClick()
        compose.onNodeWithTag("adopt-replace").performClick()
        present("adopt-order-blocked")
        compose.onNodeWithTag("adopt-replace").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/delta.md")) }
        assertEquals(adopted, move.readText())
        assertTrue(Regex("\"alpha\"\\s*:\\s*\"${Regex.escape(project.projectJsonText())}\"").containsMatchIn(scopeEntries()))
        chooseGlobal("claude-code")
        val globalTag = "place-found-adopt:$global"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(globalTag)) }.isSuccess
        }
        compose.onNodeWithTag(globalTag).performClick()
        compose.onNodeWithTag("adopt-section-status:alpha").assertTextEquals(EnStrings.intAdoptSectionSimilar("alpha"))
        compose.onNodeWithTag("adopt-section-action:alpha").assertTextEquals(EnStrings.intAdoptActionOverwrite)
        compose.onNodeWithTag("adopt-section-status:beta").assertTextEquals(EnStrings.intAdoptSectionDuplicate)
        compose.onNodeWithTag("adopt-section-status:global").assertTextEquals(EnStrings.intAdoptSectionNew)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/global.md")) }
        compose.waitUntil(15_000) { library.resolve("blocks/alpha.md").readText().contains("First synthetic rule revised.") }
        assertTrue(Regex("\"alpha\"\\s*:\\s*\"${Regex.escape(project.projectJsonText())}\"").containsMatchIn(scopeEntries()))
        assertFalse(scopeEntries().contains("\"global\""))
        assertEquals(setOf("alpha.md", "beta.md", "delta.md", "gamma.md", "global.md"), blockFiles())
        assertEquals(beta, library.resolve("blocks/beta.md").readText())
        assertTrue(global.readText().contains("A global rule."))
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
    }

    @Test fun O09_reorderAndLegacyMigrationPreserveHandwrittenGaps() {
        val legacy = other.resolve("AGENTS.md")
        legacy.writeText("# Handwritten\n\n<!-- kb old v1 ${hashContent("Legacy body")} -->\nLegacy body\n<!-- kb:end -->\n\nBetween fragments.\n\n<!-- kb second v1 ${hashContent("Second legacy body")} -->\nSecond legacy body\n<!-- kb:end -->\n\nTail note.\n")
        project.resolve("AGENTS.md").writeText("Handwritten preface.\n")
        start(); add(project); add(other)
        create("rule", "first-rule", "First body")
        create("rule", "second-rule", "Second body")
        install("rule", "first-rule")
        install("rule", "second-rule")
        val rule = project.resolve("AGENTS.md")
        val pointer = project.resolve("CLAUDE.md")
        assertTrue(pointer.readText().contains("@AGENTS.md"))
        assertFalse(pointer.readText().contains("First body"))
        assertTrue(rule.readText().indexOf("First body") < rule.readText().indexOf("Second body"))
        action("rule", "second-rule", rule, "move-top")
        compose.waitUntil(15_000) { rule.readText().indexOf("Second body") < rule.readText().indexOf("First body") }
        rule.writeText(rule.readText() + "\nHandwritten middle.\n")
        rescan()
        action("rule", "second-rule", rule, "move-bottom")
        compose.waitUntil(15_000) { rule.readText().indexOf("First body") < rule.readText().indexOf("Second body") }
        val moved = rule.readText()
        assertTrue(moved.contains("Handwritten preface."))
        assertTrue(moved.indexOf("Second body") < moved.indexOf("Handwritten middle."))
        assertEquals(1, Regex("<!-- rb1 ").findAll(moved).count())
        assertTrue(pointer.readText().contains("@AGENTS.md"))
        select(other)
        clickTag("place-migrate-$legacy")
        compose.waitUntil(15_000) { legacy.readText().contains("<!-- rb1 ") }
        assertTrue(legacy.readText().contains("# Handwritten"))
        assertTrue(legacy.readText().contains("Between fragments."))
        assertTrue(legacy.readText().indexOf("Legacy body") < legacy.readText().indexOf("Second legacy body"))
        assertTrue(legacy.readText().contains("Tail note."))
        assertFalse(legacy.readText().contains("<!-- kb old"))
    }

    @Test fun O10_backupRestoreCancelNoticeAndDisown() {
        start(); add(project); add(other)
        val rule = project.resolve("AGENTS.md")
        rule.writeText("Hand-written note.\n")
        create("rule", "e2e-rule", "E2E rule v1")
        install("rule", "e2e-rule")
        clickTag("place-backup-$rule")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("place-restore-$rule").fetchSemanticsNodes().isNotEmpty() }
        val original = rule.readText()
        rule.writeText(rule.readText().replace("E2E rule v1", "External change"))
        rescan()
        clickTag("place-restore-$rule")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertTrue(rule.readText().contains("External change"))
        clickTag("place-restore-$rule")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { rule.readText() == original }
        val beforeNotice = rule.readText()
        clickTag("place-notice-$rule")
        compose.waitUntil(15_000) { rule.readText() != beforeNotice }
        assertTrue(rule.readText().contains("E2E rule v1"))
        clickTag("place-disown-$rule")
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertTrue(rule.readText().contains("<!-- rb1 "))
        clickTag("place-disown-$rule")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !rule.readText().contains("<!-- rb1 ") }
        assertTrue(rule.readText().contains("E2E rule v1"))
    }

    @Test fun O11_sameNamesAtDifferentAddressesDoNotAlias() {
        val nativeSkill = project.resolve(".claude/skills/duplicate")
        val sharedSkill = project.resolve(".agents/skills/duplicate")
        for (path in listOf(nativeSkill, sharedSkill)) {
            Files.createDirectories(path)
            path.resolve("SKILL.md").writeText("---\nname: duplicate\ndescription: ${path.parent}\n---\n# Duplicate\n")
        }
        val nativeMcp = project.resolve(".mcp.json")
        nativeMcp.writeText("""{"mcpServers":{"duplicate":{"type":"stdio","command":"/bin/echo"}}}""")
        val otherMcp = project.resolve(".codex/config.toml")
        Files.createDirectories(otherMcp.parent)
        otherMcp.writeText("[mcp_servers.duplicate]\ncommand = \"/bin/date\"\n")
        start(); add(project); select(project); showTab("skills")
        val nativeKey = "skill:$nativeSkill"
        val sharedKey = "skill:$sharedSkill"
        foreign("place-foreign-hidden:$nativeKey")
        assertTrue(nativeSkill.resolve("SKILL.md").readText().contains("Duplicate"))
        foreign("place-foreign-save:$sharedKey")
        compose.waitUntil(15_000) { Files.exists(library.resolve("skills/duplicate/files/SKILL.md")) }
        waitManagedEntry("skill", "duplicate", sharedKey)
        showTab("mcp")
        foreign("place-foreign-remove:mcp:$nativeMcp:duplicate")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !nativeMcp.readText().contains("\"duplicate\"") }
        assertTrue(otherMcp.readText().contains("mcp_servers.duplicate"))
        assertTrue(sharedSkill.resolve("SKILL.md").readText().contains("Duplicate"))
    }

    @Test fun O12_groupWriteFailureIsReportedWithoutClaimingFullSuccess() {
        start(); add(project); add(other)
        create("rule", "bundle-rule", "Synthetic bundle rule")
        create("skill", "bundle-skill", "---\nname: bundle-skill\ndescription: Synthetic skill\n---\n# Bundle skill")
        install("rule", "bundle-rule")
        install("skill", "bundle-skill")
        val save = "place-save-as-group"
        compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(save))
        compose.onNodeWithTag(save).performClick()
        compose.onNodeWithTag("place-group-name").performTextInput("e2e-bundle")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("groups/e2e-bundle.yaml")) }
        val blocked = other.resolve("CLAUDE.md")
        Files.createSymbolicLink(blocked, Path.of("/dev/null"))
        select(other)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-bundle")
        val install = uiTarget("palette", "GROUP:e2e-bundle", "project:${other.projectKey()}", "install")
        compose.onNodeWithTag(install).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dialog-confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(EnStrings.errorTitle).assertIsDisplayed()
        val message = compose.onAllNodes(isRoot()).let { roots ->
            roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString() }
        }
        assertTrue(Files.isSymbolicLink(blocked))
        assertEquals(Path.of("/dev/null"), Files.readSymbolicLink(blocked))
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
        assertTrue(message.contains(blocked.toString()), message)
        // The rule could not reach Claude's pointer, so its AGENTS.md write is undone and named as
        // such; the skill landed, and the dialog says one member was written and one failed.
        val rule = other.resolve("AGENTS.md")
        assertFalse(Files.exists(rule) && rule.readText().contains("Synthetic bundle rule"))
        assertTrue(message.contains("undone in") && message.contains(rule.toString()), message)
        assertTrue(message.contains(EnStrings.libInstallWritten(1)), message)
        assertTrue(message.contains(EnStrings.libInstallFailedCount(1)), message)
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertTrue(Files.exists(other.resolve(".claude/skills/bundle-skill/SKILL.md")))
        select(project); select(other)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("bundle-skill")
        compose.onNodeWithTag(uiTarget("palette", "SKILL:bundle-skill", "project:${other.projectKey()}", "remove")).assertIsDisplayed()
        compose.onNodeWithTag("place-palette-search").performTextReplacement("bundle-rule")
        compose.onNodeWithTag(uiTarget("palette", "RULE:bundle-rule", "project:${other.projectKey()}", "install")).assertIsDisplayed()
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-bundle")
        // Half a bundle keeps offering the install that would complete it.
        compose.onNodeWithTag(uiTarget("palette", "GROUP:e2e-bundle", "project:${other.projectKey()}", "install")).assertIsDisplayed()
    }
}
