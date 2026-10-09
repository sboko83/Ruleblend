package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
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
import java.util.zip.ZipFile
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

/** G-01..G-09 exercise the real root UI in separate, owned homes. */
class Phase4Test {
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
        compose.onNodeWithTag("nav-place").assertIsDisplayed()
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
        compose.waitUntil(15_000) { openConfigStore().load().projects.contains(path.projectKey()) }
    }

    private fun select(path: Path) {
        route("place")
        val recent = uiTarget("place", "project:${path.projectKey()}", "recent", "select")
        val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${path.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("place-profile-add").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row("GROUP", "all")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun row(kind: String, id: String) = uiTarget("library", "$kind:$id", "catalog", "select")
    private fun saved(kind: String, id: String) = library.resolve(
        when (kind) {
            "GROUP" -> "groups/$id.yaml"
            "PROFILE" -> "profiles/$id.yaml"
            "SKILL" -> "skills/$id/files/SKILL.md"
            else -> "blocks/$id.md"
        },
    )

    private fun create(kind: String, id: String, body: String = "") {
        step = "create:$kind:$id"
        catalog()
        val menu = "library-new-${kind.lowercase()}"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(menu).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag(menu).performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        if (kind == "SKILL") compose.onNodeWithTag("library-create-description").performTextInput("Synthetic skill")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(saved(kind, id)) }
        if (body.isNotEmpty()) change(kind, id, body)
    }

    private fun change(kind: String, id: String, body: String) {
        edit(kind, id)
        val field = when (kind) {
            "SKILL" -> "library-editor-skill-content"
            "MCP" -> "mcp-command"
            else -> "library-editor-block-content"
        }
        compose.onNodeWithTag(field).performTextReplacement(body)
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
        compose.waitUntil(15_000) { saved(kind, id).readText().contains(body.substringAfterLast('\n')) }
    }

    private fun inspect(kind: String, id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val item = row(kind, id)
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(item).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(item).performClick(); false }
        }
    }

    private fun edit(kind: String, id: String) {
        inspect(kind, id)
        compose.onNodeWithTag(uiTarget("library", "$kind:$id", "inspector", "selected"))
            .performScrollToNode(hasTestTag("library-inspector-edit"))
        // The click can be lost like any synthetic one; repeat it until the editor is open.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { runCatching { compose.onNodeWithTag("library-inspector-edit").performClick() }; false }
        }
    }

    private fun member(kind: String, id: String, profile: Boolean = false) {
        val tag = if (profile) "library-profile-member:$kind:$id" else "library-group-member:BLOCK:$id"
        compose.onNodeWithTag(tag).performClick()
    }

    private fun saveGroup(id: String, vararg members: String) {
        edit("GROUP", id)
        members.forEach { member("GROUP", it) }
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { members.all(saved("GROUP", id).readText()::contains) }
    }

    private fun saveProfile(id: String, vararg members: Pair<String, String>) {
        edit("PROFILE", id)
        members.forEach { (kind, name) -> member(kind, name, profile = true) }
        compose.onNodeWithText(EnStrings.actionSave).performClick()
        compose.waitUntil(15_000) { members.all { (_, name) -> saved("PROFILE", id).readText().contains(name) } }
    }

    private fun palette(kind: String, id: String, action: String, path: Path = project) {
        select(path)
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val tag = uiTarget("palette", "$kind:$id", "project:${path.projectKey()}", action)
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun attach(id: String, mode: String = "merge") {
        step = "attach:$id:$mode"
        select(project)
        compose.onNodeWithTag("place-profile-add").performClick()
        compose.onNodeWithTag("place-profile-choice-$id").performClick()
        if (mode == "merge") compose.onNodeWithText(EnStrings.placeAttachMerge).performClick()
        else compose.onNodeWithTag(if (mode == "replace") "dialog-confirm" else "dialog-dismiss").performClick()
        if (mode != "cancel") compose.waitUntil(15_000) {
            openConfigStore().load().projectProfiles[project.projectKey()].orEmpty().any { it.id == id }
        }
    }

    private fun binding(id: String): Boolean? = openConfigStore().load().projectProfiles[project.projectKey()]
        ?.firstOrNull { it.id == id }?.active

    /** The binding lands in config before reconcile writes files; the chip changes only after both. */
    private fun toggle(id: String, active: Boolean) {
        select(project)
        compose.onNodeWithTag("place-profile-$id").performClick()
        compose.waitUntil(15_000) {
            binding(id) == active && compose.onNodeWithTag("place-profile-$id").printToString().contains("✓") == active
        }
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")

    private fun delete(kind: String, id: String) {
        inspect(kind, id)
        compose.onNodeWithTag(uiTarget("library", "$kind:$id", "inspector", "selected"))
            .performScrollToNode(hasTestTag("library-inspector-delete"))
        compose.onNodeWithTag("library-inspector-delete").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !Files.exists(saved(kind, id)) }
    }

    @Test fun G01_groupMembershipOrderAndComputedAll() {
        start()
        create("RULE", "first-rule", "First body")
        create("MCP", "first-server", "/bin/echo")
        create("SUBAGENT", "first-agent", "First agent body")
        create("SKILL", "first-skill", "---\nname: first-skill\ndescription: Synthetic skill\n---\nFirst skill body")
        create("GROUP", "mixed-group")
        edit("GROUP", "mixed-group")
        member("GROUP", "first-server")
        member("GROUP", "first-rule")
        compose.onNodeWithTag("library-group-member:SUBAGENT:first-agent").performClick()
        compose.onNodeWithTag("library-group-member:SKILL:first-skill").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { saved("GROUP", "mixed-group").readText().contains("first-skill") }
        val before = saved("GROUP", "mixed-group").readText()
        assertTrue(before.indexOf("first-server") < before.indexOf("first-rule"), before)
        edit("GROUP", "mixed-group")
        member("GROUP", "first-server")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { !saved("GROUP", "mixed-group").readText().contains("first-server") }
        val all = saved("GROUP", "all").readText()
        assertTrue(all.contains("first-server") && all.contains("first-rule") && all.contains("first-skill"), all)
        inspect("GROUP", "all")
        compose.onNodeWithTag("library-inspector-edit").assertDoesNotExist()
    }

    @Test fun G02_groupInstallRemovalPreservesBaseAndCapturesProject() {
        start(); add(project)
        create("RULE", "base-rule", "Base body")
        create("RULE", "group-rule", "Group body")
        create("MCP", "base-server", "/bin/echo")
        create("MCP", "group-server", "/bin/date")
        create("SUBAGENT", "base-agent", "Base agent body")
        create("SUBAGENT", "group-agent", "Group agent body")
        create("SKILL", "base-skill", "---\nname: base-skill\ndescription: Synthetic skill\n---\nBase skill body")
        create("SKILL", "group-skill", "---\nname: group-skill\ndescription: Synthetic skill\n---\nGroup skill body")
        for ((kind, id) in listOf("RULE" to "base-rule", "MCP" to "base-server",
            "SUBAGENT" to "base-agent", "SKILL" to "base-skill")) palette(kind, id, "install")
        create("GROUP", "installed-group")
        edit("GROUP", "installed-group")
        for (id in listOf("base-rule", "group-rule", "base-server", "group-server")) member("GROUP", id)
        for (id in listOf("base-agent", "group-agent"))
            compose.onNodeWithTag("library-group-member:SUBAGENT:$id").performClick()
        for (id in listOf("base-skill", "group-skill"))
            compose.onNodeWithTag("library-group-member:SKILL:$id").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { saved("GROUP", "installed-group").readText().contains("group-skill") }
        palette("GROUP", "installed-group", "install")
        compose.waitUntil(15_000) {
            project.resolve("AGENTS.md").readText().contains("Group body") &&
                Files.exists(project.resolve(".claude/skills/group-skill/SKILL.md")) &&
                Files.exists(project.resolve(".claude/agents/group-agent.md")) &&
                project.resolve(".mcp.json").readText().contains("group-server")
        }
        // A member updated on its own row still belongs to the group: the update must not re-own it.
        change("RULE", "group-rule", "Group body v2")
        change("MCP", "group-server", "/bin/ls")
        change("SUBAGENT", "group-agent", "Group agent body v2")
        change("SKILL", "group-skill", "---\nname: group-skill\ndescription: Synthetic skill\n---\nGroup skill body v2")
        for ((kind, id) in listOf("RULE" to "group-rule", "MCP" to "group-server",
            "SUBAGENT" to "group-agent", "SKILL" to "group-skill")) palette(kind, id, "update")
        compose.waitUntil(15_000) {
            project.resolve("AGENTS.md").readText().contains("Group body v2") &&
                project.resolve(".claude/skills/group-skill/SKILL.md").readText().contains("Group skill body v2") &&
                project.resolve(".claude/agents/group-agent.md").readText().contains("Group agent body v2") &&
                project.resolve(".mcp.json").readText().contains("/bin/ls")
        }
        palette("GROUP", "installed-group", "remove")
        compose.waitUntil(15_000) {
            !project.resolve("AGENTS.md").readText().contains("Group body") &&
                !Files.exists(project.resolve(".claude/skills/group-skill")) &&
                !Files.exists(project.resolve(".claude/agents/group-agent.md")) &&
                !project.resolve(".mcp.json").readText().contains("group-server")
        }
        // Standalone members carry no origin, like group copies written before build 165: named, kept by default.
        compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.placeGroupKeepUnmarked).fetchSemanticsNodes().isNotEmpty() }
        val notice = compose.onNodeWithText(EnStrings.placeGroupUnmarked("\u0000").substringBefore('\u0000'), substring = true)
        val noticeText = notice.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
        assertTrue(listOf("base-server", "base-agent", "base-skill").all(noticeText::contains), noticeText)
        assertFalse(listOf("group-server", "group-agent", "group-skill").any(noticeText::contains), noticeText)
        compose.onNodeWithText(EnStrings.placeGroupKeepUnmarked).performClick()
        assertTrue(project.resolve("AGENTS.md").readText().contains("Base body"))
        assertTrue(project.resolve(".mcp.json").readText().contains("base-server"))
        assertTrue(Files.exists(project.resolve(".claude/agents/base-agent.md")))
        assertTrue(Files.exists(project.resolve(".claude/skills/base-skill/SKILL.md")))
        select(project)
        compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag("place-save-as-group"))
        compose.onNodeWithTag("place-save-as-group").performClick()
        compose.onNodeWithTag("place-group-name").performTextInput("Captured Group")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(saved("GROUP", "captured-group")) }
        val captured = saved("GROUP", "captured-group").readText()
        assertTrue(listOf("base-rule", "base-server", "base-agent", "base-skill").all(captured::contains), captured)
    }

    @Test fun G03_profileCompositionVersionsAndHistory() {
        start()
        create("RULE", "shared-rule", "Shared body")
        create("RULE", "private-rule", "Private body")
        create("GROUP", "shared-group")
        saveGroup("shared-group", "shared-rule")
        create("PROFILE", "first-profile")
        saveProfile("first-profile", "RULE" to "shared-rule", "GROUP" to "shared-group")
        create("PROFILE", "second-profile")
        saveProfile("second-profile", "RULE" to "shared-rule", "RULE" to "private-rule", "GROUP" to "shared-group")
        val first = saved("PROFILE", "first-profile").readText()
        val second = saved("PROFILE", "second-profile").readText()
        assertTrue(first.contains("shared-group") && second.contains("shared-group"))
        assertFalse(first.contains("second-profile") || second.contains("first-profile"))
        assertFalse(Files.exists(saved("PROFILE", "all")))
        edit("PROFILE", "first-profile")
        compose.onNodeWithTag("library-profile-member:PROFILE:second-profile").assertDoesNotExist()
        compose.onNodeWithTag("library-profile-member:RULE:private-rule").performClick()
        compose.onNodeWithText(EnStrings.actionSave).performClick()
        compose.waitUntil(15_000) { saved("PROFILE", "first-profile").readText().contains("private-rule") }
        assertTrue(saved("PROFILE", "first-profile").readText().contains("version: 3"))
        inspect("PROFILE", "first-profile")
        val inspector = uiTarget("library", "PROFILE:first-profile", "inspector", "selected")
        compose.onNodeWithTag(inspector).performScrollToNode(
            androidx.compose.ui.test.SemanticsMatcher("revision row") {
                runCatching { it.config[SemanticsProperties.TestTag] }.getOrNull()
                    ?.startsWith("library-revision-row:") == true
            },
        )
        val history = compose.onNodeWithTag(inspector).printToString()
        assertTrue(history.contains("library-revision-row:"), history)
    }

    @Test fun G04_mergeReplacePreviewAndCancel() {
        start(); add(project)
        create("RULE", "base-rule", "Base body")
        create("RULE", "merge-rule", "Merge body")
        create("RULE", "replace-rule", "Replace body")
        palette("RULE", "base-rule", "install")
        create("PROFILE", "merge-profile"); saveProfile("merge-profile", "RULE" to "merge-rule")
        create("PROFILE", "replace-profile"); saveProfile("replace-profile", "RULE" to "replace-rule")
        attach("merge-profile")
        compose.waitUntil(15_000) { project.resolve("AGENTS.md").readText().contains("Merge body") }
        assertTrue(project.resolve("AGENTS.md").readText().contains("Base body"))
        select(project)
        compose.onNodeWithTag("place-profile-add").performClick()
        compose.onNodeWithTag("place-profile-choice-replace-profile").performClick()
        val roots = compose.onAllNodes(isRoot())
        assertTrue(roots[roots.fetchSemanticsNodes().lastIndex].printToString().contains("base-rule"))
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertEquals(null, binding("replace-profile"))
        assertTrue(project.resolve("AGENTS.md").readText().contains("Base body"))
        attach("replace-profile", "replace")
        compose.waitUntil(15_000) { project.resolve("AGENTS.md").readText().contains("Replace body") }
        assertFalse(project.resolve("AGENTS.md").readText().contains("Base body"))
    }

    @Test fun G05_overlappingProfilesKeepOneCopyAndBase() {
        start(); add(project)
        create("RULE", "shared-rule", "Shared body")
        create("RULE", "base-rule", "Base body")
        palette("RULE", "base-rule", "install")
        create("PROFILE", "first-profile"); saveProfile("first-profile", "RULE" to "shared-rule", "RULE" to "base-rule")
        create("PROFILE", "second-profile"); saveProfile("second-profile", "RULE" to "shared-rule")
        attach("first-profile"); attach("second-profile")
        val file = project.resolve("AGENTS.md")
        compose.waitUntil(15_000) { file.readText().contains("Shared body") }
        // Updating the shared copy on its row must leave it owned by the profiles, not turn it into base.
        change("RULE", "shared-rule", "Shared body v2")
        palette("RULE", "shared-rule", "update")
        compose.waitUntil(15_000) { file.readText().contains("Shared body v2") }
        assertEquals(1, Regex("Shared body").findAll(file.readText()).count())
        toggle("first-profile", false)
        assertEquals(1, Regex("Shared body").findAll(file.readText()).count())
        assertTrue(file.readText().contains("Base body"))
        toggle("first-profile", true)
        // Detaching an active owner must leave the copy the other active profile still owns.
        select(project)
        compose.onNodeWithTag("place-profile-detach-first-profile").performClick()
        compose.waitUntil(15_000) {
            binding("first-profile") == null &&
                compose.onAllNodesWithTag("place-profile-detach-first-profile").fetchSemanticsNodes().isEmpty()
        }
        assertEquals(1, Regex("Shared body").findAll(file.readText()).count())
        assertTrue(file.readText().contains("Base body"))
        toggle("second-profile", false)
        compose.waitUntil(15_000) { !file.readText().contains("Shared body") }
        assertTrue(file.readText().contains("Base body"))
    }

    @Test fun G06_modifiedCopyAndDeletedMemberAreProtectedOrRemoved() {
        start(); add(project)
        create("RULE", "edited-rule", "Original body")
        create("SKILL", "orphan-skill", "---\nname: orphan-skill\ndescription: Synthetic skill\n---\nOrphan body")
        create("SKILL", "base-skill", "---\nname: base-skill\ndescription: Synthetic skill\n---\nBase body")
        palette("SKILL", "base-skill", "install")
        create("PROFILE", "review-profile")
        saveProfile("review-profile", "RULE" to "edited-rule", "SKILL" to "orphan-skill")
        attach("review-profile")
        val file = project.resolve("AGENTS.md")
        val orphan = project.resolve(".claude/skills/orphan-skill/SKILL.md")
        val base = project.resolve(".claude/skills/base-skill/SKILL.md")
        compose.waitUntil(15_000) { Files.exists(file) && Files.exists(orphan) }
        file.writeText(file.readText().replace("Original body", "Hand edited body"))
        delete("SKILL", "orphan-skill")
        delete("SKILL", "base-skill")
        toggle("review-profile", false)
        compose.waitUntil(15_000) { !Files.exists(orphan) }
        assertTrue(file.readText().contains("Hand edited body"))
        assertTrue(Files.exists(base) && base.readText().contains("Base body"))
    }

    @Test fun G07_homeSwitchDeletionAndColdRestart() {
        start()
        if (stage == "first") {
            add(project)
            create("RULE", "one-rule", "One body")
            create("RULE", "two-rule", "Two body")
            create("PROFILE", "one-profile"); saveProfile("one-profile", "RULE" to "one-rule")
            create("PROFILE", "two-profile"); saveProfile("two-profile", "RULE" to "two-rule")
            attach("one-profile"); attach("two-profile")
            assertEquals(2, openConfigStore().load().projectProfiles[project.projectKey()]?.size)
        } else {
            assertEquals(2, openConfigStore().load().projectProfiles[project.projectKey()]?.size)
            val file = project.resolve("AGENTS.md")
            route("home")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("home-profile-one-profile").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-profile-one-profile").performClick()
            val hintPrefix = EnStrings.homeProfilesRestartHint("\u0000").substringBefore('\u0000')
            val homeHint = hasText(hintPrefix, substring = true)
            compose.waitUntil(15_000) { compose.onAllNodes(homeHint).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(false, binding("one-profile"))
            val fromHome = file.readText()
            assertFalse(fromHome.contains("One body"), fromHome)
            assertTrue(fromHome.contains("Two body"), fromHome)
            val homeText = compose.onNode(homeHint).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("")
            assertTrue(homeText.contains("Claude Code"), homeText)
            select(project)
            assertEquals(homeText, text("place-profile-restart-hint"))
            // The same switch from Projects must land on the same bytes.
            toggle("one-profile", true)
            assertTrue(file.readText().contains("One body"))
            toggle("one-profile", false)
            assertEquals(fromHome, file.readText())
            delete("PROFILE", "one-profile")
            compose.waitUntil(15_000) { binding("one-profile") == null }
            assertEquals(true, binding("two-profile"))
            select(project)
            val recent = uiTarget("place", "project:${project.projectKey()}", "recent", "select")
            compose.onNodeWithTag(recent).performMouseInput { rightClick() }
            compose.onNodeWithText(EnStrings.ctxRemoveProject).performClick()
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) { project.projectKey() !in openConfigStore().load().projects }
            assertFalse(openConfigStore().load().projectProfiles.containsKey(project.projectKey()))
            assertTrue(Files.isDirectory(project))
        }
    }

    @Test fun G08_ruleScopeDoesNotTravelIntoExport() {
        start(); add(project); add(other)
        create("RULE", "scoped-rule", "Scoped body")
        val version = saved("RULE", "scoped-rule").readText()
        edit("RULE", "scoped-rule")
        compose.onNodeWithTag("library-scope-picker").performClick()
        compose.onNodeWithTag("library-scope:$project").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { openConfigStore().load().ruleScopes["scoped-rule"] == project.projectKey() }
        assertEquals(version, saved("RULE", "scoped-rule").readText())
        route("settings")
        compose.onNodeWithTag("settings-nav-library").performClick()
        val archive = root.resolve("archives/scoped-library.zip")
        ports.nextArchive = archive
        compose.onNodeWithText(EnStrings.libExport).performScrollTo().performClick()
        compose.waitUntil(15_000) { Files.exists(archive) }
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertTrue(entries.any { it.name == "blocks/scoped-rule.md" })
            assertFalse(entries.any { it.name.contains(project.toString()) || it.name.contains(other.toString()) })
            assertFalse(entries.any { entry ->
                !entry.isDirectory && zip.getInputStream(entry).bufferedReader().use { it.readText() }.contains(project.toString())
            })
        }
        compose.onNodeWithTag("dialog-confirm").performClick()
        select(other)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("scoped-rule")
        compose.onNodeWithTag(uiTarget("palette", "RULE:scoped-rule", "project:${other.projectKey()}", "install")).assertDoesNotExist()
        edit("RULE", "scoped-rule")
        compose.onNodeWithTag("library-scope-picker").performClick()
        compose.onNodeWithTag("library-scope:global").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { !openConfigStore().load().ruleScopes.containsKey("scoped-rule") }
        assertEquals(version, saved("RULE", "scoped-rule").readText())
    }

    @Test fun G09_projectAssistantSwitchLeavesFilesUntouched() {
        start(); add(project); add(other)
        create("RULE", "switch-rule", "Switch body")
        palette("RULE", "switch-rule", "install")
        val file = project.resolve("AGENTS.md")
        compose.waitUntil(15_000) { Files.exists(file) && file.readText().contains("Switch body") }
        val before = Files.readAllBytes(file).toList()
        select(project)
        val assistant = "claude-code"
        compose.onNodeWithTag("place-assistants-summary").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("place-assistant-$assistant").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("place-assistant-$assistant").performClick()
        compose.waitUntil(15_000) { assistant in openConfigStore().load().disabledAgents[project.projectKey()].orEmpty() }
        assertEquals(before, Files.readAllBytes(file).toList())
        compose.onNodeWithTag("place-assistant-$assistant").assertIsOff()
        create("SKILL", "new-skill", "---\nname: new-skill\ndescription: Synthetic skill\n---\nNew skill body")
        palette("SKILL", "new-skill", "install")
        compose.waitUntil(15_000) { Files.exists(project.resolve(".codex/skills/new-skill/SKILL.md")) }
        assertFalse(Files.exists(project.resolve(".claude/skills/new-skill/SKILL.md")))
        assertEquals(before, Files.readAllBytes(file).toList())
        // The switch belongs to A only: B still installs for Claude Code.
        assertTrue(openConfigStore().load().disabledAgents[other.projectKey()].isNullOrEmpty())
        palette("SKILL", "new-skill", "install", other)
        compose.waitUntil(15_000) { Files.exists(other.resolve(".claude/skills/new-skill/SKILL.md")) }
        route("coverage")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("coverage-row-type:SKILL").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("coverage-row-type:SKILL").performClick()
        compose.onNodeWithTag("coverage-column-ungrouped").performClick()
        for (place in listOf(project, other)) {
            val cell = "coverage-cell-type:SKILL/SKILL:new-skill@place:project:${place.projectKey()}"
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(cell).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onNodeWithTag(cell).printToString().contains(EnStrings.coverageRemoveHere, ignoreCase = true), place.toString())
        }
        compose.onNodeWithTag("coverage-place-place:project:${project.projectKey()}")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("route-place").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("route-place").assertIsDisplayed()
        select(project)
        compose.onNodeWithTag("place-assistants-summary").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("place-assistant-$assistant").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("place-assistant-$assistant").performClick()
        compose.waitUntil(15_000) { assistant !in openConfigStore().load().disabledAgents[project.projectKey()].orEmpty() }
        assertEquals(before, Files.readAllBytes(file).toList())
        assertFalse(Files.exists(project.resolve(".claude/skills/new-skill/SKILL.md")))
        compose.onNodeWithTag("place-assistant-$assistant").assertIsOn()
    }
}
