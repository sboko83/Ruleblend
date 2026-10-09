package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.rightClick
import dev.ruleblend.app.App
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.theme.RuleblendTheme
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** P-01..P-12 run one method per sandbox; P-03, P-07 and P-10 add a cold restart in a second JVM. */
class Phase1Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val other = Path.of(root.resolve("projects/B").projectKey())
    private val config = home.resolve(".ruleblend/config.json")
    private val library = home.resolve(".ruleblend/library")
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
    private var step = "startup"
    private var createdSkill = ""

    private val environment = TestRule { base, _ -> object : Statement() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun evaluate() {
            assertEquals(home.toString(), System.getProperty("user.home"))
            assertEquals(home.toString(), System.getenv("HOME"))
            assertEquals(root.resolve("work").toRealPath(), Path.of("").toRealPath())
            assertEquals(root.fileName.toString(), root.resolve(".ruleblend-e2e").readText().trim())
            val security = System.getSecurityManager()
            val actions = dev.ruleblend.app.util.DesktopEnvironment.actions
            System.setSecurityManager(SandboxWrites(root))
            dev.ruleblend.app.util.DesktopEnvironment.actions = ports
            try { base.evaluate() } finally {
                dev.ruleblend.app.util.DesktopEnvironment.actions = actions
                System.setSecurityManager(security)
            }
        }
    } }
    private val diagnostics = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                artifacts.resolve("failure.txt").writeText("$step\n${failure.stackTraceToString()}")
                artifacts.resolve("actual-config.txt").writeText(if (Files.exists(config)) config.readText() else "MISSING")
                runCatching { artifacts.resolve("semantics.txt").writeText(compose.onRoot().printToString()) }
                runCatching {
                    val bitmap = compose.onRoot().captureToImage().asSkiaBitmap()
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
            runCatching {
                compose.onNodeWithTag("nav-$name").assertIsSelected()
                compose.onNodeWithTag("route-$name").assertIsDisplayed()
            }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun add(path: Path) {
        step = "add:$path"
        route("place")
        ports.nextDirectory = path
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { path.projectKey() in projectPaths() }
    }

    // Read the wire format independently of ConfigStore, including escaped Windows paths.
    private fun configStrings(key: String): List<String> = if (!Files.exists(config)) emptyList() else {
        val tree = Json.parseToJsonElement(config.readText()).jsonObject
        val value = if (key == "projects") tree[key] else tree["places"]?.jsonObject?.get(key)
        value?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    }

    private fun projectPaths(): List<String> = configStrings("projects")

    private fun select(path: Path, section: String = "ungrouped") {
        route("place")
        val tag = uiTarget("place", "project:${path.projectKey()}", section, "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun chooseGlobal(agent: String) {
        route("place")
        val tag = uiTarget("place", "agent:$agent", "agents", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun newObject(kind: String, name: String, description: String, body: String, variant: String? = null) {
        step = "create:$kind:$name"
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val saved = if (kind == "skill") library.resolve("skills/$id/files/SKILL.md") else library.resolve("blocks/$id.md")
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-editor-back").performClick()
        }
        // The first Library load runs on IO; wait for its generated all group before creating.
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        val all = uiTarget("library", "GROUP:all", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(all).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-search").performTextReplacement("")
        // The menu can miss a click that lands while a tooltip is still closing; reopen within the bound.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-$kind").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-$kind").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(name)
        if (kind == "skill") compose.onNodeWithTag("library-create-description").performTextInput(description)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(saved) }
        if (kind == "skill") createdSkill = saved.readText()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-create-name").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag("library-search").performTextReplacement(name)
        val row = uiTarget("library", "${kind.uppercase()}:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performClick()
        compose.onNodeWithTag("library-inspector-edit").performClick()
        if (kind == "skill") {
            compose.onNodeWithTag("library-editor-skill-content").performTextReplacement(body)
        } else {
            compose.onNodeWithTag("library-editor-block-description").performTextInput(description)
            when (kind) {
                "mcp" -> if (variant == "http") {
                    compose.onNodeWithTag("mcp-http").performClick()
                    compose.onNodeWithTag("mcp-url").performTextInput(body)
                } else compose.onNodeWithTag("mcp-command").performTextInput(body)
                else -> compose.onNodeWithTag("library-editor-block-content").performTextInput(body)
            }
            if (kind == "subagent" && variant != null) {
                compose.onNodeWithTag("subagent-field-claude-code-model").performTextInput(variant)
            }
        }
        step = "save:$kind:$name"
        compose.onNodeWithTag("library-editor-save").assertIsEnabled().performClick()
        val persisted = if (kind == "skill") "E2E skill v1" else body
        compose.waitUntil(15_000) { Files.exists(saved) && saved.readText().contains(persisted) }
        if (kind != "skill") compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
    }

    private fun createFour() {
        newObject("rule", "e2e-rule", "Rule description", "E2E rule v1")
        newObject("skill", "e2e-skill", "Skill description", "---\nname: e2e-skill\ndescription: Skill description\n---\nE2E skill v1\n")
        newObject("subagent", "e2e-agent", "Agent description", "E2E agent v1", "sonnet")
        newObject("mcp", "e2e-server", "Server description", "/bin/echo")
    }

    private fun editExisting(kind: String, id: String, field: String, value: String) {
        step = "edit:$kind:$id"
        route("library")
        step = "edit:$kind:$id"
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "$kind:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performClick()
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        val selected = runCatching {
            compose.waitUntil(2_000) { compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() }
        }.isSuccess
        if (!selected) compose.onNodeWithTag(row).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-inspector-body").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(field).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(field).performTextReplacement(value)
        compose.onNodeWithTag("library-editor-save").assertIsEnabled().performClick()
        val saved = if (kind == "SKILL") library.resolve("skills/$id/files/SKILL.md") else library.resolve("blocks/$id.md")
        val persisted = if (kind == "SKILL") "E2E skill v2" else value
        compose.waitUntil(15_000) { saved.readText().contains(persisted) }
    }

    private fun install(kind: String, id: String, place: String) {
        step = "install:$place:$kind:$id"
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val remove = uiTarget("palette", "$kind:$id", place, "remove")
        val update = uiTarget("palette", "$kind:$id", place, "update")
        if (compose.onAllNodesWithTag(update).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag(update).performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag(remove).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithTag(update).fetchSemanticsNodes().isEmpty()
            }
            return
        }
        if (compose.onAllNodesWithTag(remove).fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag(uiTarget("palette", "$kind:$id", place, "install")).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(remove).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun changePlaceCopy(kind: String, id: String, place: String, action: String, after: String) {
        step = "$action:$place:$kind:$id"
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        if (action == "update" && kind == "SKILL" && place in listOf("agent:zcode", "agent:pi") &&
            compose.onAllNodesWithTag(uiTarget("palette", "$kind:$id", place, "update")).fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag(uiTarget("palette", "$kind:$id", place, "remove")).assertIsDisplayed()
            return
        }
        compose.onNodeWithTag(uiTarget("palette", "$kind:$id", place, action)).assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("palette", "$kind:$id", place, after)).fetchSemanticsNodes().isNotEmpty() &&
                (action != "update" || compose.onAllNodesWithTag(uiTarget("palette", "$kind:$id", place, "update")).fetchSemanticsNodes().isEmpty())
        }
    }

    private fun fourIntoProject() {
        add(project)
        // Controlled foreign text is fixture state, never written through the model.
        project.resolve("AGENTS.md").writeText("# Foreign instructions\nKeep this line.\n")
        project.resolve("notes.txt").writeText("untouched project note\n")
        createFour()
        select(project)
        val place = "project:${project.projectKey()}"
        for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
            install(kind, id, place)
        }
    }

    private fun assertProjectFiles() {
        assertTrue(project.resolve("AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(project.resolve("AGENTS.md").readText().contains("Keep this line."))
        assertEquals("untouched project note\n", project.resolve("notes.txt").readText())
        assertEquals(1, "E2E rule v1".toRegex().findAll(project.resolve("AGENTS.md").readText()).count())
        assertTrue(project.resolve("CLAUDE.md").readText().contains("@AGENTS.md"))
        assertFalse(project.resolve("CLAUDE.md").readText().contains("E2E rule v1"))
        assertTrue(Files.exists(project.resolve(".claude/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(project.resolve(".claude/agents/e2e-agent.md")))
        assertTrue(project.resolve(".mcp.json").readText().contains("e2e-server"))
        assertTrue(Files.exists(project.resolve(".codex/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(project.resolve(".kimi-code/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(project.resolve(".zcode/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(project.resolve(".agents/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(project.resolve(".kimi-code/agents/e2e-agent.md")))
        assertFalse(Files.exists(project.resolve(".codex/agents/e2e-agent.toml")))
        assertTrue(project.resolve(".codex/config.toml").readText().contains("e2e-server"))
        assertTrue(project.resolve(".kimi-code/mcp.json").readText().contains("e2e-server"))
        assertTrue(home.resolve(".ruleblend/skill-state.json").readText().contains("e2e-skill"))
        assertTrue(home.resolve(".ruleblend/subagent-state.json").readText().contains("e2e-agent"))
        assertTrue(home.resolve(".ruleblend/mcp-state.json").readText().contains("e2e-server"))
        assertEquals("unchanged synthetic neighbor\n", root.resolve("neighbor.txt").readText())
    }

    @Test fun P01_projectSelectionNormalizesAndCancelDoesNothing() {
        start(); route("place")
        compose.onNodeWithTag("place-add-project").performClick()
        assertEquals(emptyList(), projectPaths())
        add(project)
        add(project)
        add(root.resolve("projects/B/../Проект A"))
        assertEquals(listOf(project.projectKey()), projectPaths())
        assertTrue(Files.isDirectory(project))
        assertTrue(Files.isDirectory(other))
    }

    @Test fun P02_projectFilterPinAndSets() {
        start(); add(project); add(other); select(project)
        compose.waitUntil(15_000) { "project:${project.projectKey()}" in configStrings("recent") }
        compose.onNodeWithTag("place-filter").performTextInput("Проект")
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")).assertIsDisplayed()
        compose.onNodeWithTag(uiTarget("place", "project:${other.projectKey()}", "ungrouped", "select")).assertDoesNotExist()
        compose.onNodeWithTag("place-filter").performTextReplacement("")
        val row = compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select"))
        row.performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placePin).performClick()
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "pinned", "select")).assertIsDisplayed()
        compose.waitUntil(15_000) { configStrings("pinned") == listOf("project:${project.projectKey()}") }
        row.performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placeNewSet).performClick()
        compose.onNodeWithTag("place-set-name").performTextInput("My set")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "set:My set", "select")).assertIsDisplayed()
        compose.waitUntil(15_000) { config.readText().contains("\"My set\"") }
        compose.onNodeWithTag(uiTarget("place-set", "set:My set", "header", "select")).performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placeRenameSet).performClick()
        compose.onNodeWithTag("place-set-name").performTextReplacement("Renamed set")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "set:Renamed set", "select")).assertIsDisplayed()
        compose.onNodeWithTag(uiTarget("place-set", "set:Renamed set", "header", "select")).performMouseInput { rightClick() }
        compose.onNodeWithText(EnStrings.placeDeleteSet).performClick()
        compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")).assertIsDisplayed()
        compose.waitUntil(15_000) { !config.readText().contains("Renamed set") }
        assertEquals(listOf(project.projectKey(), other.projectKey()), projectPaths())
        assertEquals(listOf("project:${project.projectKey()}"), configStrings("pinned"))
    }

    @Test fun P03_rulePersistsThroughRestart() {
        start()
        if (stage == "first") newObject("rule", "e2e-rule", "Rule description", "E2E rule v1")
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("library-editor-back").performClick()
        }
        compose.onNodeWithTag("library-search").performTextReplacement("e2e-rule")
        compose.onNodeWithTag(uiTarget("library", "RULE:e2e-rule", "catalog", "select")).performClick()
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("library-inspector-body").assertTextContains("E2E rule v1") }.isSuccess
        }
        assertTrue(library.resolve("blocks/e2e-rule.md").readText().contains("version: 1"))
        assertTrue(library.resolve("blocks/e2e-rule.md").readText().contains("E2E rule v1"))
    }

    @Test fun P04_skillFrontmatterAndTree() {
        start()
        newObject("skill", "e2e-skill", "Skill description", "---\nname: e2e-skill\ndescription: Skill description\n---\nE2E skill v1\n")
        assertTrue(createdSkill.startsWith("---\n"), createdSkill)
        assertTrue(Regex("""(?m)^name: "?e2e-skill"?$""").containsMatchIn(createdSkill), createdSkill)
        assertTrue(Regex("""(?m)^description: "?Skill description"?$""").containsMatchIn(createdSkill), createdSkill)
        val text = library.resolve("skills/e2e-skill/files/SKILL.md").readText()
        assertTrue(text.startsWith("---\n"))
        assertTrue(Regex("""(?m)^name: "?e2e-skill"?$""").containsMatchIn(text))
        assertTrue(Regex("""(?m)^description: "?Skill description"?$""").containsMatchIn(text))
        assertTrue(Files.exists(library.resolve("skills/e2e-skill/meta.yaml")))
    }

    @Test fun P05_subagentFieldsAndNativeFile() {
        start(); add(project)
        newObject("subagent", "e2e-agent", "Agent description", "E2E agent v1", "sonnet")
        select(project); install("SUBAGENT", "e2e-agent", "project:${project.projectKey()}")
        val native = project.resolve(".claude/agents/e2e-agent.md").readText()
        assertTrue(native.contains("E2E agent v1"))
        assertTrue(Regex("""(?m)^model: "?sonnet"?$""").containsMatchIn(native))
    }

    @Test fun P06_mcpStdioAndHttpTransports() {
        start(); add(project)
        newObject("mcp", "e2e-server", "Stdio server", "/bin/echo")
        route("library")
        compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("e2e-server")
        compose.onNodeWithTag(uiTarget("library", "MCP:e2e-server", "catalog", "select")).performClick()
        compose.onNodeWithTag("library-inspector-edit").performClick()
        compose.onNodeWithTag("mcp-add-arg").performClick()
        compose.onNodeWithTag("mcp-arg-0").performTextInput("--help")
        compose.onNodeWithTag("mcp-add-env").performClick()
        compose.onNodeWithTag("mcp-env-key-0").performTextInput("E2E_FLAG")
        compose.onNodeWithTag("mcp-env-value-0").performTextInput("synthetic")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("blocks/e2e-server.md").readText().contains("E2E_FLAG") }
        newObject("mcp", "e2e-http", "HTTP server", "https://example.invalid/mcp", "http")
        compose.onNodeWithTag("mcp-add-headers").performClick()
        compose.onNodeWithTag("mcp-headers-key-0").performTextInput("X-Test")
        compose.onNodeWithTag("mcp-headers-value-0").performTextInput("synthetic")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("blocks/e2e-http.md").readText().contains("example.invalid") }
        select(project); install("MCP", "e2e-server", "project:${project.projectKey()}")
        install("MCP", "e2e-http", "project:${project.projectKey()}")
        assertTrue(project.resolve(".mcp.json").readText().contains("/bin/echo"))
        assertTrue(project.resolve(".mcp.json").readText().contains("--help"))
        assertTrue(project.resolve(".mcp.json").readText().contains("E2E_FLAG"))
        assertTrue(project.resolve(".mcp.json").readText().contains("X-Test"))
        assertTrue(project.resolve(".mcp.json").readText().contains("https://example.invalid/mcp"))
        assertTrue(project.resolve(".codex/config.toml").readText().contains("https://example.invalid/mcp"))
    }

    @Test fun P07_fourTypesInstallAndSidecars() {
        start()
        if (stage == "first") fourIntoProject() else select(project)
        assertProjectFiles()
        for ((tab, count) in listOf("rules" to "1", "skills" to "5/0", "subagents" to "2/0", "mcp" to "3/0")) {
            compose.onNodeWithTag("place-tab-$tab").assertIsDisplayed().performClick()
            compose.onNodeWithTag("place-tab-$tab").assertTextContains(count, substring = true)
        }
        for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
            compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
            compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "remove")).assertIsDisplayed()
        }
    }

    @Test fun P08_updatesMarkOldCopiesAndSync() {
        start(); fourIntoProject()
        val place = "project:${project.projectKey()}"
        val updates = listOf(
            Triple("RULE:e2e-rule", "library-editor-block-content", "E2E rule v2"),
            Triple("SKILL:e2e-skill", "library-editor-skill-content", "---\nname: e2e-skill\ndescription: Skill description\n---\nE2E skill v2\n"),
            Triple("SUBAGENT:e2e-agent", "library-editor-block-content", "E2E agent v2"),
            Triple("MCP:e2e-server", "mcp-command", "/bin/date"),
        )
        for ((objectKey, field, value) in updates) {
            val (kind, id) = objectKey.split(':')
            editExisting(kind, id, field, value)
            select(project)
            compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
            compose.onNodeWithTag(uiTarget("palette", objectKey, place, "update")).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag(uiTarget("palette", objectKey, place, "update")).fetchSemanticsNodes().isEmpty()
            }
        }
        assertTrue(project.resolve("AGENTS.md").readText().contains("E2E rule v2"))
        assertFalse(project.resolve("AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(project.resolve(".claude/skills/e2e-skill/SKILL.md").readText().contains("E2E skill v2"))
        assertTrue(project.resolve(".claude/agents/e2e-agent.md").readText().contains("E2E agent v2"))
        assertTrue(project.resolve(".mcp.json").readText().contains("/bin/date"))
        assertTrue(library.resolve("blocks/e2e-rule.md").readText().contains("version: 2"))
    }

    @Test fun P09_repeatingInstallLeavesBytesAndVersionsStable() {
        start(); fourIntoProject()
        val files = listOf(project.resolve("AGENTS.md"), project.resolve("CLAUDE.md"), project.resolve(".mcp.json"),
            project.resolve(".claude/skills/e2e-skill/SKILL.md"), project.resolve(".claude/agents/e2e-agent.md"),
            library.resolve("blocks/e2e-rule.md"), library.resolve("blocks/e2e-agent.md"), library.resolve("blocks/e2e-server.md"),
            home.resolve(".ruleblend/mcp-state.json"), home.resolve(".ruleblend/skill-state.json"), home.resolve(".ruleblend/subagent-state.json"),
            project.resolve(".codex/config.toml"), project.resolve(".kimi-code/mcp.json"), project.resolve(".kimi-code/agents/e2e-agent.md"),
            project.resolve(".codex/skills/e2e-skill/SKILL.md"), project.resolve(".kimi-code/skills/e2e-skill/SKILL.md"),
            project.resolve(".zcode/skills/e2e-skill/SKILL.md"), project.resolve(".agents/skills/e2e-skill/SKILL.md"),
            library.resolve("skills/e2e-skill/files/SKILL.md"), library.resolve("skills/e2e-skill/meta.yaml"))
        val before = files.associateWith { Files.readAllBytes(it).toList() }
        route("library")
        for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
            compose.onNodeWithTag("library-search").performTextReplacement(id)
            val row = uiTarget("library", "$kind:$id", "catalog", "select")
            compose.onNode(hasTestTag("library-row-mark") and hasAnyAncestor(hasTestTag(row))).performClick()
        }
        compose.onNodeWithTag("library-bulk-install").performClick()
        compose.onNodeWithTag("library-install-place:project:${project.projectKey()}").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("library-bulk-install").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("dialog-confirm").performClick()
        assertEquals(before, files.associateWith { Files.readAllBytes(it).toList() })
        assertEquals(1, "E2E rule v1".toRegex().findAll(project.resolve("AGENTS.md").readText()).count())
    }

    @Test fun P10_coldRestartRemovalKeepsProjectFolder() {
        start()
        if (stage == "first") {
            fourIntoProject()
            assertProjectFiles()
        } else {
            assertProjectFiles()
            select(project)
            for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
                compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
                compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "remove")).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "install")).fetchSemanticsNodes().isNotEmpty()
                }
            }
            compose.waitUntil(15_000) { !project.resolve("AGENTS.md").readText().contains("E2E rule v1") }
            // The separator before the former managed region is outside it and remains intact.
            assertEquals("# Foreign instructions\nKeep this line.\n\n", project.resolve("AGENTS.md").readText())
            assertEquals("untouched project note\n", project.resolve("notes.txt").readText())
            for (copy in listOf(".claude/skills/e2e-skill", ".codex/skills/e2e-skill", ".kimi-code/skills/e2e-skill",
                    ".zcode/skills/e2e-skill", ".agents/skills/e2e-skill", ".claude/agents/e2e-agent.md", ".kimi-code/agents/e2e-agent.md")) {
                assertFalse(Files.exists(project.resolve(copy)), copy)
            }
            for (file in listOf(".mcp.json", ".codex/config.toml", ".kimi-code/mcp.json")) {
                assertFalse(Files.exists(project.resolve(file)) && project.resolve(file).readText().contains("e2e-server"), file)
            }
            compose.onNodeWithTag(uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")).performMouseInput { rightClick() }
            compose.onNodeWithText(EnStrings.ctxRemoveProject).performClick()
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) { projectPaths().isEmpty() }
            assertTrue(Files.isDirectory(project))
            assertTrue(projectPaths().isEmpty())
        }
    }

    @Test fun P11_similarNamesAndUnicodePathsStayDistinct() {
        start()
        val sameA = Files.createDirectories(root.resolve("projects/one/Same Name"))
        val sameB = Files.createDirectories(root.resolve("projects/two/Same Name"))
        val unicode = Files.createDirectories(root.resolve("projects/Очень длинное имя проекта с пробелами"))
        listOf(sameA, sameB, unicode).forEach(::add)
        assertEquals(3, projectPaths().size)
        compose.onNodeWithTag("place-filter").performTextInput(Path.of("two", "Same Name").toString())
        compose.onNodeWithTag(uiTarget("place", "project:${sameA.projectKey()}", "ungrouped", "select")).assertDoesNotExist()
        compose.onNodeWithTag(uiTarget("place", "project:${sameB.projectKey()}", "ungrouped", "select")).performClick()
        newObject("rule", "e2e-rule", "Path identity", "Only second Same Name")
        select(sameB)
        install("RULE", "e2e-rule", "project:${sameB.projectKey()}")
        assertTrue(sameB.resolve("AGENTS.md").readText().contains("Only second Same Name"))
        assertFalse(Files.exists(sameA.resolve("AGENTS.md")))
        assertEquals(3, projectPaths().size)
        assertTrue(Files.isDirectory(sameA) && Files.isDirectory(sameB) && Files.isDirectory(unicode))
    }

    // A listed project with no installs must keep offering installation while global copies exist.
    private fun assertProjectUntouchedByGlobal() {
        select(project)
        for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
            compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
            compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "install")).assertIsDisplayed()
            compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "project:${project.projectKey()}", "remove")).assertDoesNotExist()
        }
    }

    @Test fun P12_globalTargetsAndUnsupportedActions() {
        start(); add(project); createFour()
        val agents = listOf("claude-code", "codex", "pi", "kimi-code", "zcode")
        for (agent in agents) {
            chooseGlobal(agent)
            install("RULE", "e2e-rule", "agent:$agent")
            install("SKILL", "e2e-skill", "agent:$agent")
            install("MCP", "e2e-server", "agent:$agent")
            if (agent in listOf("claude-code", "codex", "kimi-code")) {
                install("SUBAGENT", "e2e-agent", "agent:$agent")
            } else {
                compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-agent")
                compose.onNodeWithTag(uiTarget("palette", "SUBAGENT:e2e-agent", "agent:$agent", "install")).assertDoesNotExist()
            }
        }
        assertTrue(home.resolve(".claude/CLAUDE.md").readText().contains("E2E rule v1"))
        assertTrue(Files.exists(home.resolve(".claude/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(home.resolve(".claude/agents/e2e-agent.md")))
        assertTrue(home.resolve(".codex/AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(Files.exists(home.resolve(".codex/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(home.resolve(".codex/agents/e2e-agent.toml")))
        assertTrue(home.resolve(".kimi/AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(Files.exists(home.resolve(".kimi/skills/e2e-skill/SKILL.md")))
        assertTrue(Files.exists(home.resolve(".kimi/agents/e2e-agent.md")))
        assertTrue(home.resolve(".zcode/AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(home.resolve(".pi/agent/AGENTS.md").readText().contains("E2E rule v1"))
        assertTrue(Files.exists(home.resolve(".agents/skills/e2e-skill/SKILL.md")))
        for (file in listOf(".claude/.claude.json", ".codex/config.toml", ".kimi/mcp.json", ".agents/mcp.json", ".pi/agent/mcp.json")) {
            assertTrue(home.resolve(file).readText().contains("e2e-server"), file)
        }
        assertFalse(Files.exists(project.resolve("AGENTS.md")))
        assertFalse(Files.exists(other.resolve("AGENTS.md")))
        assertProjectUntouchedByGlobal()
        val revisions = listOf(
            Triple("RULE:e2e-rule", "library-editor-block-content", "E2E rule v2"),
            Triple("SKILL:e2e-skill", "library-editor-skill-content", "---\nname: e2e-skill\ndescription: Skill description\n---\nE2E skill v2\n"),
            Triple("SUBAGENT:e2e-agent", "library-editor-block-content", "E2E agent v2"),
            Triple("MCP:e2e-server", "mcp-command", "/bin/date"),
        )
        for ((objectKey, field, value) in revisions) {
            val (kind, id) = objectKey.split(':')
            editExisting(kind, id, field, value)
            for (agent in agents) {
                if (kind == "SUBAGENT" && agent in listOf("zcode", "pi")) continue
                chooseGlobal(agent)
                changePlaceCopy(kind, id, "agent:$agent", "update", "remove")
            }
        }
        assertTrue(home.resolve(".claude/CLAUDE.md").readText().contains("E2E rule v2"))
        assertTrue(home.resolve(".codex/AGENTS.md").readText().contains("E2E rule v2"))
        for (agent in agents) {
            chooseGlobal(agent)
            for ((kind, id) in listOf("RULE" to "e2e-rule", "SKILL" to "e2e-skill", "SUBAGENT" to "e2e-agent", "MCP" to "e2e-server")) {
                if (kind == "SUBAGENT" && agent in listOf("zcode", "pi")) continue
                if (kind == "SKILL" && agent in listOf("pi", "kimi-code")) {
                    compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
                    compose.onNodeWithTag(uiTarget("palette", "$kind:$id", "agent:$agent", "remove")).performClick()
                    compose.waitUntil(15_000) {
                        !home.resolve(".ruleblend/skill-state.json").readText().contains("\"agentId\": \"$agent\"")
                    }
                    assertTrue(Files.exists(home.resolve(".agents/skills/e2e-skill/SKILL.md")))
                } else changePlaceCopy(kind, id, "agent:$agent", "remove", "install")
            }
            if (agent == "kimi-code") assertTrue(Files.exists(home.resolve(".agents/skills/e2e-skill/SKILL.md")))
        }
        chooseGlobal("pi")
        compose.onNodeWithTag("place-palette-search").performTextReplacement("e2e-skill")
        compose.onNodeWithTag(uiTarget("palette", "SKILL:e2e-skill", "agent:pi", "install")).assertIsDisplayed()
        assertFalse(home.resolve(".claude/CLAUDE.md").readText().contains("E2E rule v2"))
        assertFalse(home.resolve(".codex/AGENTS.md").readText().contains("E2E rule v2"))
        // The last recorded owner of the shared tree removes it; no global copy survives.
        for (copy in listOf(".agents/skills/e2e-skill", ".claude/skills/e2e-skill", ".codex/skills/e2e-skill",
                ".kimi/skills/e2e-skill", ".zcode/skills/e2e-skill", ".claude/agents/e2e-agent.md",
                ".codex/agents/e2e-agent.toml", ".kimi/agents/e2e-agent.md")) {
            assertFalse(Files.exists(home.resolve(copy)), copy)
        }
        for (file in listOf(".claude/.claude.json", ".codex/config.toml", ".kimi/mcp.json", ".agents/mcp.json", ".pi/agent/mcp.json")) {
            assertFalse(Files.exists(home.resolve(file)) && home.resolve(file).readText().contains("e2e-server"), file)
        }
        assertFalse(Files.exists(project.resolve("AGENTS.md")))
        assertFalse(Files.exists(other.resolve("AGENTS.md")))
        assertProjectUntouchedByGlobal()
    }
}

internal object NoTranslation : Translator {
    override val available = false
    override suspend fun status(from: String, to: String, quality: TranslationQuality) = TranslationStatus.UNSUPPORTED
    override suspend fun languages(quality: TranslationQuality) = emptyList<String>()
    override suspend fun translate(texts: List<String>, from: String, to: String, quality: TranslationQuality) = texts
}
