package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** X-01..X-05 and S-01..S-06 use the real root UI and isolated file systems. */
class Phase6Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val library = home.resolve(".ruleblend/library")
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
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("all")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("library", "GROUP:all", "catalog", "select")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun create(kind: String, id: String) {
        catalog()
        val tag = "library-new-${kind.lowercase()}"
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        if (kind == "SKILL") compose.onNodeWithTag("library-create-description").performTextInput("Synthetic skill")
        compose.onNodeWithTag("dialog-confirm").performClick()
        val path = when (kind) {
            "PROFILE" -> library.resolve("profiles/$id.yaml")
            "GROUP" -> library.resolve("groups/$id.yaml")
            "SKILL" -> library.resolve("skills/$id/files/SKILL.md")
            else -> library.resolve("blocks/$id.md")
        }
        compose.waitUntil(15_000) { Files.exists(path) }
    }

    private fun settingsLibrary() {
        route("settings")
        compose.onNodeWithTag("settings-nav-library").performClick()
    }

    private fun select(kind: String, id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "$kind:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("library", "$kind:$id", "inspector", "selected"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun edit(kind: String, id: String) {
        select(kind, id)
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-edit"))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-edit").performClick(); false }
        }
    }

    private fun changeRule(id: String, body: String) {
        edit("RULE", id)
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement(body)
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("blocks/$id.md").readText().contains(body) }
    }

    private fun delete(kind: String, id: String) {
        select(kind, id)
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-delete"))
        compose.onNodeWithTag("library-inspector-delete").performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        val path = library.resolve("blocks/$id.md")
        compose.waitUntil(15_000) { !Files.exists(path) }
    }

    private fun export(zip: Path) {
        settingsLibrary()
        ports.nextArchive = zip
        compose.onNodeWithText(EnStrings.libExport).performScrollTo().performClick()
        compose.waitUntil(15_000) { Files.exists(zip) }
        compose.onNodeWithTag("dialog-confirm").performClick()
    }

    private fun import(zip: Path) {
        settingsLibrary()
        ports.nextArchive = zip
        compose.onNodeWithText(EnStrings.libImport).performScrollTo().performClick()
        compose.onNodeWithText(EnStrings.libImportTitle).assertIsDisplayed()
    }

    private fun mark(kind: String, id: String) {
        select(kind, id)
        val row = uiTarget("library", "$kind:$id", "catalog", "select")
        compose.onNode(hasTestTag("library-row-mark") and hasAnyAncestor(hasTestTag(row)))
            .performSemanticsAction(SemanticsActions.OnClick).assertIsOn()
    }

    private fun selectiveExport(zip: Path) {
        ports.nextArchive = zip
        compose.onNodeWithContentDescription(EnStrings.libBulkExport).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { Files.exists(zip) }
        compose.onNodeWithTag("dialog-confirm").performClick()
    }

    private fun fixtureZip(name: String, entries: List<Pair<String, ByteArray>>): Path {
        val zip = root.resolve("archives/$name.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { output ->
            entries.forEach { (path, bytes) ->
                output.putNextEntry(ZipEntry(path))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return zip
    }

    private fun importGit(vararg selectedPaths: String) {
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-import-git").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-import-git").performClick()
        compose.onNodeWithTag("library-import-repository").performTextInput(root.resolve("upstream").toString())
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.libSkillImportSelected).fetchSemanticsNodes().isNotEmpty()
        }
        for (path in listOf("skills/first-skill", "skills/second-skill")) {
            val checkbox = compose.onNodeWithTag("library-git-import:$path")
            if (path in selectedPaths) checkbox.assertIsOn() else checkbox.performClick().assertIsOff()
        }
        compose.onNodeWithTag("dialog-confirm").performClick()
        selectedPaths.forEach { path ->
            compose.waitUntil(15_000) { Files.exists(library.resolve("${path}/files/SKILL.md")) }
        }
    }

    private fun checkSources() {
        route("home")
        val state = home.resolve(".ruleblend/sources-state.json")
        compose.waitUntil(30_000) {
            Files.exists(state) || run {
                runCatching { compose.onNodeWithTag("home-source-check").performClick() }
                false
            }
        }
    }

    private fun addProjectAndInstallSkill(id: String) {
        val project = root.resolve("projects/Проект A")
        route("place")
        ports.nextDirectory = project
        compose.onNodeWithTag("place-add-project").performClick()
        compose.waitUntil(15_000) { project.projectKey() in openConfigStore().load().projects }
        paletteAction(project, id, "install").performClick()
        compose.waitUntil(15_000) { Files.exists(project.resolve(".claude/skills/$id/SKILL.md")) }
    }

    private fun paletteAction(project: Path, id: String, action: String) = run {
        route("place")
        val recent = uiTarget("place", "project:${project.projectKey()}", "recent", "select")
        val ungrouped = uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")
        compose.waitUntil(15_000) {
            listOf(recent, ungrouped).any { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        }
        val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else ungrouped
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        val target = uiTarget("palette", "SKILL:$id", "project:${project.projectKey()}", action)
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(target)) }.isSuccess
        }
        compose.onNodeWithTag(target)
    }

    @Test fun X01_fullLibraryRoundTripIncludesProfile() {
        start()
        val zip = root.resolve("archives/full-library.zip")
        if (stage == "first") {
            val project = root.resolve("projects/Проект A")
            route("place")
            ports.nextDirectory = project
            compose.onNodeWithTag("place-add-project").performClick()
            compose.waitUntil(15_000) { project.projectKey() in openConfigStore().load().projects }
            create("RULE", "archive-rule")
            create("MCP", "archive-server")
            create("SUBAGENT", "archive-agent")
            create("SKILL", "archive-skill")
            importGit("skills/first-skill")
            create("GROUP", "archive-group")
            create("PROFILE", "archive-profile")
            export(zip)
            ZipFile(zip.toFile()).use { archive ->
                for (entry in listOf("blocks/archive-rule.md", "blocks/archive-server.md",
                    "blocks/archive-agent.md", "skills/archive-skill/files/SKILL.md",
                    "skills/first-skill/files/references/guide.txt",
                    "groups/archive-group.yaml", "profiles/archive-profile.yaml")) {
                    assertTrue(archive.getEntry(entry) != null, entry)
                }
                assertFalse(archive.entries().asSequence().any { it.name.startsWith(".git/") })
                assertFalse(archive.entries().asSequence().any { it.name.contains("config.json") || it.name.contains(project.toString()) })
            }
        } else {
            assertFalse(Files.exists(library.resolve("profiles/archive-profile.yaml")))
            assertTrue(openConfigStore().load().projects.isEmpty())
            import(zip)
            compose.onNodeWithTag("library-archive-import-list")
                .performScrollToNode(hasTestTag("library-archive-import:PROFILE:archive-profile"))
            compose.onNodeWithTag("library-archive-import:PROFILE:archive-profile").assertIsDisplayed()
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) { Files.exists(library.resolve("profiles/archive-profile.yaml")) }
            for (restored in listOf("blocks/archive-rule.md", "blocks/archive-server.md", "blocks/archive-agent.md",
                "skills/archive-skill/files/SKILL.md", "groups/archive-group.yaml")) {
                assertTrue(Files.exists(library.resolve(restored)), restored)
            }
            assertEquals("first-skill reference v1\n", library.resolve("skills/first-skill/files/references/guide.txt").readText())
            assertTrue(Files.isExecutable(library.resolve("skills/first-skill/files/scripts/run.sh")))
            assertTrue(openConfigStore().load().projects.isEmpty())
            catalog()
            compose.onNodeWithTag("library-search").performTextReplacement("archive-profile")
            compose.onNodeWithTag(uiTarget("library", "PROFILE:archive-profile", "catalog", "select"))
                .assertIsDisplayed()
        }
    }

    @Test fun X02_selectiveProfileExportIncludesDependencyClosure() {
        start()
        val zip = root.resolve("archives/selective-profile.zip")
        if (stage == "first") {
            create("RULE", "profile-rule")
            create("RULE", "unrelated-rule")
            importGit("skills/first-skill")
            create("GROUP", "profile-group")
            edit("GROUP", "profile-group")
            compose.onNodeWithTag("library-group-member:BLOCK:profile-rule").performClick()
            compose.onNodeWithTag("library-group-member-list")
                .performScrollToNode(hasTestTag("library-group-member:SKILL:first-skill"))
            compose.onNodeWithTag("library-group-member:SKILL:first-skill").performClick()
            compose.onNodeWithTag("library-editor-save").performClick()
            compose.waitUntil(15_000) { library.resolve("groups/profile-group.yaml").readText().contains("profile-rule") }
            create("PROFILE", "portable-profile")
            edit("PROFILE", "portable-profile")
            compose.onNodeWithTag("library-profile-member-list")
                .performScrollToNode(hasTestTag("library-profile-member:GROUP:profile-group"))
            compose.onNodeWithTag("library-profile-member:GROUP:profile-group").performClick()
            compose.onNodeWithText(EnStrings.actionSave).performClick()
            compose.waitUntil(15_000) { library.resolve("profiles/portable-profile.yaml").readText().contains("profile-group") }
            mark("PROFILE", "portable-profile")
            selectiveExport(zip)
            ZipFile(zip.toFile()).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toSet()
                assertTrue("profiles/portable-profile.yaml" in entries)
                assertTrue("groups/profile-group.yaml" in entries)
                assertTrue("blocks/profile-rule.md" in entries)
                assertTrue("skills/first-skill/files/references/guide.txt" in entries)
                assertFalse("blocks/unrelated-rule.md" in entries)
            }
        } else {
            import(zip)
            compose.onNodeWithTag("library-archive-import-list")
                .performScrollToNode(hasTestTag("library-archive-import:PROFILE:portable-profile"))
            compose.onNodeWithTag("library-archive-import:PROFILE:portable-profile").assertIsDisplayed()
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) { Files.exists(library.resolve("profiles/portable-profile.yaml")) }
            assertTrue(Files.exists(library.resolve("groups/profile-group.yaml")))
            assertTrue(Files.exists(library.resolve("blocks/profile-rule.md")))
            assertTrue(Files.exists(library.resolve("skills/first-skill/files/references/guide.txt")))
            assertFalse(Files.exists(library.resolve("blocks/unrelated-rule.md")))
        }
    }

    @Test fun X03_previewDefaultsCancellationAndPartialImport() {
        start()
        create("RULE", "conflict-rule")
        create("RULE", "new-rule")
        create("RULE", "same-rule")
        val zip = root.resolve("archives/selection.zip")
        export(zip)
        delete("RULE", "new-rule")
        changeRule("conflict-rule", "Local version two")
        val local = library.resolve("blocks/conflict-rule.md").readText()
        import(zip)
        compose.onNodeWithTag("library-archive-import:BLOCK:new-rule").assertIsOn()
        compose.onNodeWithTag("library-archive-import:BLOCK:conflict-rule").assertIsOff()
        compose.onNodeWithTag("library-archive-import:BLOCK:same-rule").assertDoesNotExist()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        assertFalse(Files.exists(library.resolve("blocks/new-rule.md")))
        assertEquals(local, library.resolve("blocks/conflict-rule.md").readText())
        import(zip)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(library.resolve("blocks/new-rule.md")) }
        assertEquals(local, library.resolve("blocks/conflict-rule.md").readText())

        create("RULE", "update-rule")
        val oldZip = root.resolve("archives/old-version.zip")
        export(oldZip)
        changeRule("update-rule", "Newer archive version")
        val newZip = root.resolve("archives/new-version.zip")
        export(newZip)
        import(oldZip)
        compose.onNodeWithTag("library-archive-import:BLOCK:update-rule").assertIsOff().performClick()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { !library.resolve("blocks/update-rule.md").readText().contains("Newer archive version") }
        import(newZip)
        compose.onNodeWithTag("library-archive-import:BLOCK:update-rule").assertIsOn()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { library.resolve("blocks/update-rule.md").readText().contains("Newer archive version") }
    }

    @Test fun X05_pickerCancellationAndExportFailureDoNotReportSuccess() {
        start()
        create("RULE", "safe-rule")
        val before = library.resolve("blocks/safe-rule.md").readText()
        settingsLibrary()
        compose.onNodeWithText(EnStrings.libImport).performScrollTo().performClick()
        assertTrue(ports.calls.any { it.startsWith("archive:") })
        compose.onNodeWithText(EnStrings.libImportTitle).assertDoesNotExist()
        val pickerCalls = ports.calls.count { it.startsWith("archive:") }
        compose.onNodeWithText(EnStrings.libExport).performClick()
        assertEquals(pickerCalls + 1, ports.calls.count { it.startsWith("archive:") })
        compose.onNodeWithText(EnStrings.libExportedTitle).assertDoesNotExist()
        compose.onNodeWithText(EnStrings.libExportFailedTitle).assertDoesNotExist()
        val denied = Files.createDirectory(root.resolve("archives/denied.zip"))
        ports.nextArchive = denied // Existing directory, not a writable ZIP file.
        compose.onNodeWithText(EnStrings.libExport).performClick()
        compose.onNodeWithText(EnStrings.libExportFailedTitle).assertIsDisplayed()
        compose.onNodeWithText(EnStrings.libExportedTitle).assertDoesNotExist()
        assertEquals(before, library.resolve("blocks/safe-rule.md").readText())
        assertTrue(Files.isDirectory(denied))
    }

    @Test fun X04_invalidArchivesShowErrorsWithoutPartialWrites() {
        start()
        create("RULE", "safe-rule")
        val safe = library.resolve("blocks/safe-rule.md").readText()
        val candidate = "blocks/unwanted-rule.md" to safe.encodeToByteArray()
        val archives = listOf(
            root.resolve("archives/broken.zip").also { Files.write(it, byteArrayOf(1, 2, 3, 4)) },
            fixtureZip("traversal", listOf(candidate, "../outside.md" to "escape".encodeToByteArray())),
            fixtureZip("incomplete-skill", listOf(candidate, "skills/broken/files/tool.txt" to byteArrayOf(1))),
            fixtureZip("duplicate", listOf(candidate,
                "blocks\\unwanted-rule.md" to safe.encodeToByteArray())),
            fixtureZip("oversized", listOf(candidate, "blocks/huge.md" to ByteArray(10 * 1024 * 1024 + 1) { 65 })),
        )
        settingsLibrary()
        archives.forEach { zip ->
            step = "invalid-archive:${zip.fileName}"
            ports.nextArchive = zip
            compose.onNodeWithText(EnStrings.libImport).performScrollTo().performClick()
            compose.onNodeWithText(EnStrings.libImportFailedTitle).assertIsDisplayed()
            compose.onNodeWithTag("dialog-confirm").performClick()
            assertFalse(Files.exists(library.resolve("blocks/unwanted-rule.md")))
            assertFalse(Files.exists(library.resolve("skills/broken")))
            assertEquals(safe, library.resolve("blocks/safe-rule.md").readText())
            assertFalse(Files.exists(library.resolveSibling("outside.md")))
            assertFalse(Files.exists(root.resolve("outside.md")))
        }
    }

    @Test fun S01_selectiveGitImportPreservesTreesAndProvenance() {
        start()
        importGit("skills/first-skill")
        val skill = library.resolve("skills/first-skill")
        assertTrue(skill.resolve("files/SKILL.md").readText().contains("first-skill v1"))
        assertEquals("first-skill reference v1\n", skill.resolve("files/references/guide.txt").readText())
        val meta = com.charleskorn.kaml.Yaml.default.decodeFromString(
            dev.ruleblend.core.model.SkillMeta.serializer(), skill.resolve("meta.yaml").readText(),
        )
        assertTrue("scripts/run.sh" in meta.executableFiles)
        if (Files.getFileStore(skill).supportsFileAttributeView("posix")) {
            assertTrue(Files.isExecutable(skill.resolve("files/scripts/run.sh")))
        }
        assertEquals(root.resolve("upstream").toString(), meta.source?.repository)
        assertEquals("skills/first-skill", meta.source?.path)
        assertTrue(Regex("[a-f0-9]{40}").matches(requireNotNull(meta.source).revision))
        assertFalse(Files.exists(library.resolve("skills/second-skill")))
    }

    @Test fun S02_repeatImportAndForkKeepOriginalImmutable() {
        start()
        importGit("skills/first-skill", "skills/second-skill")
        val imported = library.resolve("skills/first-skill/files/SKILL.md").readText()
        catalog()
        compose.onNodeWithTag("library-new").performClick()
        compose.onNodeWithTag("library-import-git").performClick()
        compose.onNodeWithTag("library-import-repository").performTextInput(root.resolve("upstream").toString())
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(EnStrings.libSkillImportSelected).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-git-import:skills/first-skill").assertIsOff()
        compose.onNodeWithTag("library-git-import:skills/second-skill").assertIsOff()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-dismiss").performClick()
        edit("SKILL", "first-skill")
        val content = compose.onNodeWithTag("library-editor-skill-content")
        runCatching { content.performTextReplacement("Tampered upstream") }
        content.assert(hasText("first-skill v1", substring = true))
        compose.onNodeWithText(EnStrings.libSkillCreateChanged).performClick()
        val fork = library.resolve("skills/first-skill-changed")
        compose.waitUntil(15_000) { Files.exists(fork.resolve("files/SKILL.md")) }
        assertTrue(fork.resolve("files/SKILL.md").readText().contains("first-skill v1"))
        assertEquals("first-skill reference v1\n", fork.resolve("files/references/guide.txt").readText())
        assertTrue(Files.isExecutable(fork.resolve("files/scripts/run.sh")))
        assertTrue(fork.resolve("meta.yaml").readText().contains("forkedFrom"))
        compose.onNodeWithTag("library-editor-skill-content").performTextReplacement("---\nname: first-skill-changed\ndescription: Local copy\n---\nChanged copy\n")
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { fork.resolve("files/SKILL.md").readText().contains("Changed copy") }
        assertEquals(imported, library.resolve("skills/first-skill/files/SKILL.md").readText())
    }

    @Test fun S03_sourceUpdatesPreserveInstalledOldCopyUntilExplicitRefresh() {
        start()
        val installed = root.resolve("projects/Проект A/.claude/skills/first-skill/SKILL.md")
        if (stage == "first") {
            importGit("skills/first-skill", "skills/second-skill")
            addProjectAndInstallSkill("first-skill")
            assertTrue(installed.readText().contains("first-skill v1"))
        } else {
            checkSources()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("home-source-update-first-skill").fetchSemanticsNodes().isNotEmpty() }
            val upstreamHead = Git.open(root.resolve("upstream").toFile()).use {
                requireNotNull(it.repository.resolve("HEAD")).name
            }
            settingsLibrary()
            compose.onNode(hasText(upstreamHead.take(12), substring = true) and
                hasAnyAncestor(hasTestTag("settings-source-0")), useUnmergedTree = true).assertExists()
            route("home")
            compose.onNodeWithTag("home-source-update-first-skill").performClick()
            compose.waitUntil(30_000) { library.resolve("skills/first-skill/files/SKILL.md").readText().contains("v2") }
            assertEquals("first-skill reference v2\n", library.resolve("skills/first-skill/files/references/guide.txt").readText())
            assertTrue(Files.isExecutable(library.resolve("skills/first-skill/files/scripts/run.sh")))
            assertTrue(library.resolve("skills/second-skill/files/SKILL.md").readText().contains("v1"))
            compose.waitUntil(30_000) {
                library.resolve("skills/second-skill/files/SKILL.md").readText().contains("v2") || run {
                    runCatching { compose.onNodeWithTag("home-source-update-all").performClick() }
                    false
                }
            }
            assertTrue(installed.readText().contains("first-skill v1"))
            paletteAction(root.resolve("projects/Проект A"), "first-skill", "update").assertIsDisplayed()
            assertTrue(installed.readText().contains("first-skill v1"))
        }
    }

    @Test fun S04_missingOrUnavailableSourceKeepsSnapshotsAndEqualTreesCurrent() {
        start()
        if (stage == "first") {
            importGit("skills/first-skill", "skills/second-skill")
        } else {
            val first = library.resolve("skills/first-skill/files/SKILL.md").readText()
            val second = library.resolve("skills/second-skill/files/SKILL.md").readText()
            checkSources()
            // The state file lands before Home re-reads it; wait for the rendered row, not the file.
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(EnStrings.homeSourcePathMissing).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(EnStrings.homeSourcePathMissing).assertIsDisplayed()
            compose.onNodeWithTag("home-source-update-all").assertDoesNotExist()
            assertEquals(first, library.resolve("skills/first-skill/files/SKILL.md").readText())
            assertEquals(second, library.resolve("skills/second-skill/files/SKILL.md").readText())
            Files.move(root.resolve("upstream"), root.resolve("archives/offline-upstream"))
            compose.onNodeWithTag("home-source-check").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(EnStrings.homeSourceUnavailable, substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(first, library.resolve("skills/first-skill/files/SKILL.md").readText())
            assertEquals(second, library.resolve("skills/second-skill/files/SKILL.md").readText())
        }
    }

    @Test fun S05_manualAndOnLaunchPoliciesPersistAndSettingsReopensSource() {
        start()
        if (stage == "first") {
            importGit("skills/first-skill")
            settingsLibrary()
            compose.onNodeWithTag("settings-source-check-manual").assertIsDisplayed()
            assertEquals(dev.ruleblend.core.config.SourceCheckMode.MANUAL, openConfigStore().load().sourceCheck)
        } else if (stage == "manual-restart") {
            assertFalse(Files.exists(home.resolve(".ruleblend/sources-state.json")))
            settingsLibrary()
            compose.onNodeWithTag("settings-source-check-manual").assertIsDisplayed()
            assertEquals(dev.ruleblend.core.config.SourceCheckMode.MANUAL, openConfigStore().load().sourceCheck)
            compose.onNodeWithTag("settings-source-check-on-launch").performClick()
            assertEquals(dev.ruleblend.core.config.SourceCheckMode.ON_LAUNCH, openConfigStore().load().sourceCheck)
        } else {
            compose.waitUntil(30_000) { Files.exists(home.resolve(".ruleblend/sources-state.json")) }
            route("home")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("home-source-update-first-skill").fetchSemanticsNodes().isNotEmpty() }
            settingsLibrary()
            compose.onNodeWithTag("settings-source-check-on-launch").assertIsDisplayed()
            assertEquals(dev.ruleblend.core.config.SourceCheckMode.ON_LAUNCH, openConfigStore().load().sourceCheck)
            compose.onNodeWithTag("settings-source-reopen-0").performClick()
            compose.onNodeWithTag("library-import-repository").assertIsDisplayed()
            assertTrue(compose.onNodeWithTag("library-import-repository").printToString().contains(root.resolve("upstream").toString()))
        }
    }

    @Test fun S06_mixedSubagentImportForkInstallAndUpdateSurviveRestart() {
        val upstream = root.resolve("upstream")
        val sourcePath = "agents/reviewer.toml"
        val importedFile = library.resolve("blocks/reviewer.md")
        val forkFile = library.resolve("blocks/reviewer-changed.md")
        val installedFile = home.resolve(".codex/agents/reviewer.toml")
        fun advance(body: String) {
            upstream.resolve(sourcePath).writeText(
                "name = \"reviewer\"\ndescription = \"Reviews code\"\nmodel = \"native-model\"\n" +
                    "model_reasoning_effort = \"high\"\ndeveloper_instructions = \"$body\"\n",
            )
            Git.open(upstream.toFile()).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Update definitions").setAuthor("Fixture", "fixture@example.invalid").call()
            }
        }
        if (stage == "first") {
            Files.createDirectories(upstream.resolve("agents"))
            Files.createDirectories(upstream.resolve("skills/reviewer"))
            upstream.resolve("skills/reviewer/SKILL.md").writeText(
                "---\nname: reviewer\ndescription: Review skill\n---\nSkill instructions\n",
            )
            Git.init().setDirectory(upstream.toFile()).call().close()
            advance("Upstream instructions v1")
        }
        start()
        if (stage == "first") {
            step = "mixed Git import"
            catalog()
            compose.onNodeWithTag("library-new").performClick()
            compose.onNodeWithTag("library-import-git").performClick()
            compose.onNodeWithTag("library-import-repository").performTextInput(upstream.toString())
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithTag("library-git-import-subagent:$sourcePath").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("library-git-import:skills/reviewer").assertIsOn()
            compose.onNodeWithTag("library-git-import-subagent:$sourcePath").assertIsOn()
            compose.onNodeWithTag("dialog-confirm").performClick()
            compose.waitUntil(15_000) { Files.exists(importedFile) }
            assertTrue(library.resolve("skills/reviewer/files/SKILL.md").readText().contains("Skill instructions"))

            step = "read-only definition and editable fork"
            edit("SUBAGENT", "reviewer")
            compose.onNodeWithTag("library-editor-block-name").assertIsNotEnabled()
            compose.onNodeWithTag("library-editor-block-description").assertIsNotEnabled()
            compose.onNodeWithTag("subagent-field-codex-model").assertIsNotEnabled()
            compose.onNodeWithTag("library-editor-save").assertDoesNotExist()
            compose.onNodeWithTag("library-editor-block-content").assertIsDisplayed()
            compose.onNodeWithTag("library-editor-subagent-fork").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { Files.exists(forkFile) }
            compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Independent local instructions")
            compose.onNodeWithTag("library-editor-save").performClick()
            compose.waitUntil(15_000) { forkFile.readText().contains("Independent local instructions") }
            assertTrue(importedFile.readText().contains("Upstream instructions v1"))

            step = "install imported Codex definition"
            route("place")
            val global = uiTarget("place", "agent:codex", "agents", "select")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(global).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(global))
            compose.onNodeWithTag(global).performClick()
            compose.onNodeWithTag("place-palette-search").performTextReplacement("reviewer")
            val install = uiTarget("palette", "SUBAGENT:reviewer", "agent:codex", "install")
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(install).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(install))
            compose.onNodeWithTag(install).performClick()
            compose.waitUntil(15_000) { Files.exists(installedFile) }
            assertTrue(installedFile.readText().contains("native-model"))
            assertTrue(installedFile.readText().contains("Upstream instructions v1"))

            step = "export imported definition and changed copy"
            val zip = root.resolve("archives/subagents.zip")
            export(zip)
            ZipFile(zip.toFile()).use { archive ->
                for (id in listOf("reviewer", "reviewer-changed")) {
                    val entry = requireNotNull(archive.getEntry("blocks/$id.md"))
                    assertEquals(library.resolve("blocks/$id.md").readText(),
                        archive.getInputStream(entry).bufferedReader().use { it.readText() })
                }
            }
            advance("Upstream instructions v2")
        } else {
            step = "cold restart and source update"
            val installedBefore = installedFile.readText()
            val forkBefore = forkFile.readText()
            assertTrue(forkBefore.contains("Independent local instructions"))
            checkSources()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("home-source-update-subagent:reviewer").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("home-source-update-subagent:reviewer").performClick()
            compose.waitUntil(30_000) { importedFile.readText().contains("Upstream instructions v2") }
            assertEquals(forkBefore, forkFile.readText())
            assertEquals(installedBefore, installedFile.readText())
            val definition = dev.ruleblend.core.storage.BlockFile.parse("reviewer", importedFile.readText())
            assertEquals(2, definition.version)
            assertEquals(sourcePath, definition.source?.path)
            assertEquals("codex", definition.source?.assistant)
            edit("SUBAGENT", "reviewer")
            compose.onNodeWithTag("library-editor-block-content").assert(hasText("Upstream instructions v2"))
            compose.onNodeWithTag("subagent-field-codex-model").assert(hasText("native-model"))
        }
    }
}
