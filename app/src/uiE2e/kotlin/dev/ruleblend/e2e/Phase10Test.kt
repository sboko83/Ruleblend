package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import dev.ruleblend.core.integration.hashRun
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Isolated recovery cases; each stage is a separate JVM using the production root UI. */
class Phase10Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val ruleblendHome = home.resolve(".ruleblend")
    private val config = ruleblendHome.resolve("config.json")
    private val library = ruleblendHome.resolve("library")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val other = Path.of(root.resolve("projects/B").projectKey())
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
    private var step = "startup"
    private val javaBinary = Path.of(ProcessHandle.current().info().command().orElseThrow())

    private val environment = TestRule { base, _ -> object : Statement() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun evaluate() {
            assertEquals(home.toString(), System.getProperty("user.home"))
            assertEquals(home.toString(), System.getenv("HOME"))
            assertEquals(root.resolve("work").toRealPath(), Path.of("").toRealPath())
            assertEquals(root.fileName.toString(), root.resolve(".ruleblend-e2e").readText().trim())
            val security = System.getSecurityManager()
            val actions = DesktopEnvironment.actions
            System.setSecurityManager(SandboxWrites(root, javaBinary))
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

    private fun present(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun route(name: String) {
        step = "route:$name"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun addProject(path: Path) {
        route("place")
        ports.nextDirectory = path
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { path.projectKey() in openConfigStore().load().projects }
    }

    private fun projectRow(path: Path): String {
        val recent = uiTarget("place", "project:${path.projectKey()}", "recent", "select")
        val ungrouped = uiTarget("place", "project:${path.projectKey()}", "ungrouped", "select")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(ungrouped).fetchSemanticsNodes().isNotEmpty()
        }
        return if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else ungrouped
    }

    private fun createRule(name: String): Path {
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
        val saved = library.resolve("blocks/${name.lowercase().replace(' ', '-')}.md")
        compose.waitUntil(15_000) { Files.exists(saved) }
        return saved
    }

    @Test fun R01_brokenConfigSidecarUnknownFieldsAndMissingProject() {
        val broken = config.resolveSibling("config.json.broken")
        val sidecar = ruleblendHome.resolve("skill-state.json")
        if (stage == "first") {
            val invalid = "{invalid synthetic config"
            config.writeText(invalid)
            sidecar.writeText("{invalid synthetic sidecar")
            start()
            compose.onNodeWithText(EnStrings.configBrokenTitle).assertIsDisplayed()
            assertEquals(invalid, broken.readText())
            compose.onNodeWithTag("dialog-confirm").performClick()
            addProject(project)
            assertEquals("{invalid synthetic sidecar", sidecar.readText())
            assertTrue(project.projectKey() in openConfigStore().load().projects)
            // Fixture simulates a folder removed outside the app after it was registered.
            Files.delete(project)
        } else {
            val original = config.readText()
            config.writeText(original.trimEnd().dropLast(1) + ",\n\"futureField\": {\"value\": 1}\n}\n")
            start()
            route("place")
            assertEquals("{invalid synthetic config", broken.readText())
            assertEquals("{invalid synthetic sidecar", sidecar.readText())
            assertTrue(project.projectKey() in openConfigStore().load().projects)
            assertFalse(Files.exists(project))
            assertTrue(Files.exists(other))
            createRule("Missing target")
            route("place")
            val row = projectRow(project)
            compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
            compose.onNodeWithTag(row).performClick()
            present("place-project-missing")
            compose.onNodeWithText(EnStrings.placeProjectMissing).assertIsDisplayed()
            compose.onNodeWithTag("place-palette-search").performTextReplacement("missing-target")
            val install = uiTarget("palette", "RULE:missing-target", "project:${project.projectKey()}", "install")
            present(install)
            step = "install-into-missing-project"
            compose.onNodeWithTag(install).performClick()
            present("dialog-confirm")
            compose.onNodeWithText(EnStrings.errorTitle).assertIsDisplayed()
            compose.onNodeWithTag("dialog-confirm").performClick()
            assertFalse(Files.exists(project))
        }
    }

    @Test fun R02_writeFailureAndHeldLockPreserveTarget() {
        interruptAtomicWriter()
        start()
        route("library")
        compose.onNodeWithTag("library-search").performTextReplacement("interrupted")
        present(uiTarget("library", "RULE:interrupted", "catalog", "select"))
        addProject(project)
        val saved = createRule("Locked rule")
        val original = saved.readText()
        route("place")
        val row = projectRow(project)
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        present("place-palette-search")
        compose.onNodeWithTag("place-palette-search").performTextReplacement("locked-rule")
        val install = uiTarget("palette", "RULE:locked-rule", "project:${project.projectKey()}", "install")
        present(install)
        // The fixture replaces the destination with a directory immediately before the write.
        val blockedTarget = Files.createDirectory(project.resolve("AGENTS.md"))
        step = "denied-target-write"
        compose.onNodeWithTag(install).performClick()
        present("dialog-confirm")
        compose.onNodeWithText(EnStrings.errorTitle).assertIsDisplayed()
        assertTrue(Files.isDirectory(blockedTarget))
        assertEquals(original, saved.readText())
        compose.onNodeWithTag("dialog-confirm").performClick()
        Files.delete(blockedTarget)
        present(install)
        val holder = ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
            LockHolder::class.java.name,
            ruleblendHome.resolve("ruleblend.lock").toString())
            .directory(root.resolve("work").toFile())
            .redirectError(root.resolve("artifacts/$stage/lock-holder-error.txt").toFile())
            .start()
        try {
            val ready = java.util.concurrent.CompletableFuture.supplyAsync {
                holder.inputStream.bufferedReader().readLine()
            }.get(5, TimeUnit.SECONDS)
            assertEquals("ready", ready)
            artifacts.resolve("second-process-pid.txt").writeText(holder.pid().toString())
            assertTrue(holder.pid() != ProcessHandle.current().pid())
            step = "locked-project-write"
            compose.onNodeWithTag(install).performClick()
            present("dialog-confirm")
            compose.onNodeWithText(EnStrings.errorTitle).assertIsDisplayed()
            assertEquals(original, saved.readText())
            assertFalse(Files.exists(project.resolve("AGENTS.md")))
        } finally {
            holder.outputStream.close()
            assertTrue(holder.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, holder.exitValue())
        }
    }

    /**
     * A second JVM rewrites a rule through the production atomic writer and is killed before rename.
     * The file on disk must be one whole version, never a torn mix, before the UI ever reads it.
     */
    private fun interruptAtomicWriter() {
        step = "interrupted-writer"
        val target = library.resolve("blocks/interrupted.md")
        Files.createDirectories(target.parent)
        val whole = setOf(InterruptedWriter.version('a'), InterruptedWriter.version('b'))
        fun temps() = Files.list(target.parent).use { files ->
            files.filter { it.fileName.toString().endsWith(".tmp") }.count()
        }
        // A kill between two writes proves nothing, so retry until one lands inside a write.
        var attempts = 0
        while (temps() == 0L && attempts < 5) {
            attempts++
            val writer = ProcessBuilder(javaBinary.toString(), "-cp", System.getProperty("java.class.path"),
                InterruptedWriter::class.java.name, target.toString())
                .directory(root.resolve("work").toFile())
                .redirectError(artifacts.resolve("interrupted-writer-error.txt").toFile())
                .start()
            try {
                val output = writer.inputStream.bufferedReader()
                java.util.concurrent.CompletableFuture.supplyAsync {
                    generateSequence { output.readLine() }.first { it == "inside-write" }
                }.get(60, TimeUnit.SECONDS)
                // Forced termination: no shutdown hook or finally block of the writer runs.
                writer.destroyForcibly()
                assertTrue(writer.waitFor(5, TimeUnit.SECONDS))
                assertTrue(writer.exitValue() != 0)
            } finally {
                writer.destroyForcibly()
            }
            assertTrue(target.readText() in whole, "torn write: ${target.readText().length} chars")
        }
        artifacts.resolve("interrupted-writer.txt").writeText("attempts=$attempts leftoverTemps=${temps()}\n")
        assertTrue(temps() > 0, "no kill landed inside a write in $attempts attempts")
    }

    @Test fun R04_largeCatalogAndFastNavigationKeepSelectionLocal() {
        start()
        addProject(project)
        addProject(other)
        for (index in 1..16) createRule("stress rule $index")
        route("place")
        selectProject(project)
        compose.onNodeWithTag("place-palette-search").performTextReplacement("stress-rule-1")
        val installA = uiTarget("palette", "RULE:stress-rule-1", "project:${project.projectKey()}", "install")
        present(installA)
        compose.onNodeWithTag(installA).performClick()
        present(uiTarget("palette", "RULE:stress-rule-1", "project:${project.projectKey()}", "remove"))
        // Save and leave in the same frame: the write must finish without the editor on screen.
        route("library")
        compose.onNodeWithTag("library-search").performTextReplacement("stress-rule-2")
        val row = uiTarget("library", "RULE:stress-rule-2", "catalog", "select")
        present(row)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() || run {
                if (compose.onAllNodesWithTag("library-inspector-edit").fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag(row).performClick()
                else compose.onNodeWithTag("library-inspector-edit").performClick()
                false
            }
        }
        step = "save-then-navigate"
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Saved while leaving.")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.onNodeWithTag("nav-place").performClick()
        // Two selections back to back: A's scan may still run when B is chosen.
        step = "switch-during-scan"
        compose.onNodeWithTag(projectRow(project)).performClick()
        compose.onNodeWithTag(projectRow(other)).performClick()
        compose.onNodeWithTag("place-palette-search").performTextReplacement("stress-rule-1")
        present(uiTarget("palette", "RULE:stress-rule-1", "project:${other.projectKey()}", "install"))
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(uiTarget("palette", "RULE:stress-rule-1", "project:${project.projectKey()}", "remove"))
            .fetchSemanticsNodes().isEmpty(), "project A result leaked into project B")
        compose.waitUntil(15_000) {
            library.resolve("blocks/stress-rule-2.md").readText().contains("Saved while leaving.")
        }
        assertFalse(Files.exists(other.resolve("AGENTS.md")))
        assertTrue(project.resolve("AGENTS.md").readText().contains("stress-rule-1"))
        for (index in 1..8) {
            route(if (index % 2 == 0) "library" else "place")
            route(if (index % 2 == 0) "place" else "library")
        }
        route("library")
        compose.onNodeWithTag("library-search").performTextReplacement("stress-rule-16")
        present(uiTarget("library", "RULE:stress-rule-16", "catalog", "select"))
        assertEquals(16, Files.list(library.resolve("blocks")).use { it.count() })
        assertEquals(setOf(project.projectKey(), other.projectKey()), openConfigStore().load().projects.toSet())
    }

    private fun selectProject(path: Path) {
        val row = projectRow(path)
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        present("place-palette-search")
    }

    @Test fun R06_legacyHomeCopiesOnceAndCurrentHomeWins() {
        val oldHome = home.resolve(".kitbash")
        val oldConfig = oldHome.resolve("config.json")
        if (stage == "first") {
            // The runner leaves this home absent so the real startup migration can publish it.
            assertFalse(Files.exists(ruleblendHome))
            Files.createDirectories(oldHome.resolve("library/blocks"))
            val projectsJson = Json.encodeToString(listOf(project.toString(), other.toString()))
            oldConfig.writeText("{\"projects\":$projectsJson,\"language\":\"en\"}\n")
            oldHome.resolve("library/blocks/legacy.md").writeText(
                "---\nname: Legacy\ndescription: ''\nversion: 1\ntype: rule\nfavorite: false\nheading: ''\nheadingLevel: 2\n---\nLegacy body\n")
            project.resolve("AGENTS.md").writeText(
                "Handwritten preface.\n\n<!-- kb legacy v1 ${hashContent("Legacy body")} -->\nLegacy body\n<!-- kb:end -->\n")
            other.resolve("AGENTS.md").writeText(
                "Handwritten middle.\n\n<!-- kb1 ${hashRun("Second body")} second@1:11 -->\nSecond body\n<!-- kb:end -->\n")
            home.resolve(".codex/config.toml").writeText(
                "[mcp_servers.kitbash]\ncommand = \"/legacy/kitbash-mcp\"\nargs = [\"--mcp\"]\n")
            home.resolve(".claude/.claude.json").writeText(
                "{\"mcpServers\":{\"kitbash\":{\"command\":\"npx\",\"args\":[\"kitbash\"]}}}")
        }
        val oldBytes = oldConfig.readText()
        start()
        route("place")
        assertEquals(oldBytes, oldConfig.readText())
        assertTrue(Files.exists(library.resolve("blocks/legacy.md")))
        assertEquals(setOf(project.projectKey(), other.projectKey()), openConfigStore().load().projects.toSet())
        if (stage == "first") {
            assertEquals(oldBytes, config.readText())
            val ruleFile = project.resolve("AGENTS.md")
            assertTrue(ruleFile.readText().contains("<!-- kb legacy"))
            val row = projectRow(project)
            compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
            compose.onNodeWithTag(row).performClick()
            present("place-migrate-$ruleFile")
            compose.onNodeWithTag("place-migrate-$ruleFile").performClick()
            compose.waitUntil(15_000) { ruleFile.readText().contains("<!-- rb1 ") }
            assertTrue(ruleFile.readText().contains("Handwritten preface."))
            assertFalse(ruleFile.readText().contains("<!-- kb "))
            val wrapped = other.resolve("AGENTS.md")
            assertTrue(wrapped.readText().contains("<!-- kb1 "))
            val otherRow = projectRow(other)
            compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(otherRow))
            compose.onNodeWithTag(otherRow).performClick()
            present("place-palette-search")
            compose.onNodeWithTag("place-palette-search").performTextReplacement("legacy")
            val install = uiTarget("palette", "RULE:legacy", "project:${other.projectKey()}", "install")
            present(install)
            compose.onNodeWithTag(install).performClick()
            compose.waitUntil(15_000) { wrapped.readText().contains("<!-- rb1 ") }
            assertTrue(wrapped.readText().contains("Handwritten middle."))
            assertFalse(wrapped.readText().contains("<!-- kb1 "))
            route("settings")
            compose.onNodeWithTag("settings-nav-agents").performClick()
            compose.onNodeWithText(EnStrings.connectStatusStale).assertIsDisplayed()
            compose.onNodeWithTag("settings-connect-codex").performClick()
            val codex = home.resolve(".codex/config.toml")
            compose.waitUntil(15_000) {
                codex.readText().contains("[mcp_servers.ruleblend]") &&
                    !codex.readText().contains("[mcp_servers.kitbash]")
            }
            assertTrue(home.resolve(".claude/.claude.json").readText().contains("\"command\":\"npx\""))
            // A later legacy edit must not be merged over the current home on restart.
            val laterProjectsJson = Json.encodeToString(listOf(other.toString()))
            oldConfig.writeText("{\"projects\":$laterProjectsJson,\"language\":\"ru\"}\n")
        } else {
            assertEquals("en", openConfigStore().load().language)
            assertEquals(setOf(project.projectKey(), other.projectKey()), openConfigStore().load().projects.toSet())
            assertTrue(oldConfig.readText().contains(other.jsonPathText()))
            assertTrue(project.resolve("AGENTS.md").readText().contains("<!-- rb1 "))
            assertTrue(other.resolve("AGENTS.md").readText().contains("<!-- rb1 "))
            assertTrue(home.resolve(".codex/config.toml").readText().contains("[mcp_servers.ruleblend]"))
            assertTrue(home.resolve(".claude/.claude.json").readText().contains("\"command\":\"npx\""))
        }
    }
}
