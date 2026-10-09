package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import com.charleskorn.kaml.Yaml
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.captureToImage
import dev.ruleblend.app.App
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.SkillMeta
import dev.ruleblend.core.storage.BlockFile
import dev.ruleblend.core.storage.LibraryGit
import java.nio.file.Files
import java.nio.file.Path
import java.security.Permission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Y-01..Y-10 drive A through the root UI; B is an isolated external Git client. */
class Phase7Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val library = home.resolve(".ruleblend/library")
    private val remote = root.resolve("remote/library.git")
    private val second = root.resolve("remote/client-b")
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
    private var step = "startup"
    private var useFocusFixture = false
    private val focused = mutableStateOf(true)
    private val focusNow = AtomicLong(1_000_000L)
    private val windowInfo = object : WindowInfo {
        override val isWindowFocused: Boolean get() = focused.value
    }

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
        val appContent: @Composable () -> Unit = {
            RuleblendTheme(ThemeMode.LIGHT) {
                App(openConfigStore(), onThemeModeChanged = {}, createTranslator = { NoTranslation },
                    focusSyncNow = { focusNow.get() })
            }
        }
        compose.setContent {
            if (useFocusFixture) CompositionLocalProvider(LocalWindowInfo provides windowInfo) { appContent() }
            else appContent()
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

    private fun settings() {
        route("settings")
        compose.onNodeWithTag("settings-nav-library").performClick()
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun path(id: String) = library.resolve("blocks/$id.md")
    private fun content(id: String) = BlockFile.parse(id, path(id).readText())
    private fun remoteHead(target: Path = remote): String? = Git.open(target.toFile()).use {
        it.repository.resolve("refs/heads/main")?.name
    }

    private fun initRemote(target: Path = remote) {
        if (!Files.exists(target)) Git.init().setBare(true).setDirectory(target.toFile()).call().close()
    }

    private fun seedRemote(target: Path, id: String) {
        initRemote(target)
        val seed = root.resolve("upstream/seed-${target.fileName}")
        val git = LibraryGit(seed)
        git.init()
        val block = seed.resolve("blocks/$id.md")
        Files.createDirectories(block.parent)
        block.writeText(BlockFile.serialize(Block(id, id, version = 1, type = BlockType.RULE, content = "Seed $id")))
        git.commit("Seed $id", Path.of("blocks/$id.md"))
        git.sync(dev.ruleblend.core.config.RemoteGitConfig(target.toUri().toString(), "main", false))
        assertTrue(remoteHead(target) != null)
    }

    private fun create(id: String, body: String = "Body $id") {
        step = "create:$id"
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-rule").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-rule").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(path(id)) }
        change(id, body)
    }

    private fun createKind(kind: String, id: String) {
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
        val saved = when (kind) {
            "SKILL" -> library.resolve("skills/$id/files/SKILL.md")
            "GROUP" -> library.resolve("groups/$id.yaml")
            else -> library.resolve("profiles/$id.yaml")
        }
        compose.waitUntil(15_000) { Files.exists(saved) }
    }

    private fun edit(kind: String, id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "$kind:$id", "catalog", "select")
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
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

    private fun changeSkill(id: String, text: String) {
        edit("SKILL", id)
        compose.onNodeWithTag("library-editor-skill-content").performTextReplacement(text)
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("skills/$id/files/SKILL.md").readText().contains(text.substringAfterLast('\n')) }
    }

    private fun addMemberships() {
        edit("GROUP", "bundle")
        compose.onNodeWithTag("library-group-member:BLOCK:member").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("groups/bundle.yaml").readText().contains("member") }
        edit("PROFILE", "mode")
        compose.onNodeWithTag("library-profile-member:GROUP:bundle").performClick()
        compose.onNodeWithText(EnStrings.actionSave).performClick()
        compose.waitUntil(15_000) { library.resolve("profiles/mode.yaml").readText().contains("bundle") }
    }

    private fun addLocalMembership(id: String) {
        edit("GROUP", "bundle")
        compose.onNodeWithTag("library-group-member:BLOCK:$id").performClick()
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) { library.resolve("groups/bundle.yaml").readText().contains(id) }
        edit("PROFILE", "mode")
        compose.onNodeWithTag("library-profile-member:RULE:$id").performClick()
        compose.onNodeWithText(EnStrings.actionSave).performClick()
        compose.waitUntil(15_000) { library.resolve("profiles/mode.yaml").readText().contains(id) }
    }

    private fun change(id: String, body: String) {
        step = "change:$id"
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "RULE:$id", "catalog", "select")
        val inspector = uiTarget("library", "RULE:$id", "inspector", "selected")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(row).performClick(); false }
        }
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-edit"))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-edit").performClick(); false }
        }
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement(body)
        compose.onNodeWithTag("library-editor-save").performClick()
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
        compose.waitUntil(15_000) { content(id).content == body }
    }

    private fun remove(id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "RULE:$id", "catalog", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performClick()
        val inspector = uiTarget("library", "RULE:$id", "inspector", "selected")
        compose.onNodeWithTag(inspector).performScrollToNode(hasTestTag("library-inspector-delete"))
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("dialog-confirm").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-delete").performClick(); false }
        }
        compose.onNodeWithTag("dialog-confirm").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) { !Files.exists(path(id)) }
    }

    private fun configure(target: Path = remote, automatic: Boolean = false) {
        step = "configure:${target.fileName}"
        initRemote(target)
        settings()
        if (compose.onAllNodesWithTag("settings-remote-edit").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("settings-remote-edit").performClick()
        compose.onNodeWithTag("settings-remote-url").performTextReplacement(target.toUri().toString())
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("main")
        val toggle = compose.onNodeWithTag("settings-remote-automatic")
        if (runCatching { toggle.assertIsOn() }.isSuccess != automatic) toggle.performClick()
        if (automatic) toggle.assertIsOn() else toggle.assertIsOff()
        compose.onNodeWithTag("settings-remote-save").performClick()
        compose.waitUntil(30_000) {
            openConfigStore().load().remoteGit?.url == target.toUri().toString() &&
                compose.onAllNodesWithTag("settings-remote-target").fetchSemanticsNodes().isNotEmpty() &&
                remoteHead(target) != null
        }
    }

    private fun sync() {
        step = "sync"
        settings()
        compose.onNodeWithTag("settings-remote-sync").performClick()
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("settings-remote-sync").assertIsEnabled() }.isSuccess
        }
    }

    private fun localHead(): String? = Git.open(library.toFile()).use { it.repository.resolve("HEAD")?.name }

    /** Home and Settings render one sync result; both lines must carry the same state. */
    private fun assertSyncState(state: String) {
        val automatic = requireNotNull(openConfigStore().load().remoteGit).automatic
        route("home")
        val homeMode = if (automatic) EnStrings.homeSyncAutomatic else EnStrings.homeSyncManual
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("$state · ", substring = true) and
                hasText(" · $homeMode", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        settings()
        val settingsMode = if (automatic) EnStrings.remoteGitAutoShort else EnStrings.remoteGitManualShort
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("$state · ", substring = true) and
                hasText(" · $settingsMode", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun cloneB() {
        Git.cloneRepository().setURI(remote.toUri().toString()).setBranch("refs/heads/main")
            .setDirectory(second.toFile()).call().close()
        assertTrue(Files.exists(second.resolve(".git")))
    }

    private fun bSave(id: String, body: String) {
        val file = second.resolve("blocks/$id.md")
        Files.createDirectories(file.parent)
        val previous = if (Files.exists(file)) BlockFile.parse(id, file.readText())
            else Block(id, id, version = 0, type = BlockType.RULE, content = "")
        file.writeText(BlockFile.serialize(previous.copy(version = previous.version + 1, content = body)))
        bCommit("Update $id")
    }

    private fun bDelete(id: String) {
        Files.delete(second.resolve("blocks/$id.md"))
        bCommit("Delete $id")
    }

    private fun bCommit(message: String) {
        Git.open(second.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage(message).setAuthor("Fixture", "fixture@example.invalid")
                .setCommitter("Fixture", "fixture@example.invalid").call()
            git.push().setRemote("origin").add("refs/heads/main:refs/heads/main").call().toList().also {
                assertTrue(it.flatMap { result -> result.remoteUpdates }.all { update -> update.status.name == "OK" })
            }
        }
    }

    private fun projectRule(action: String) {
        val project = root.resolve("projects/Проект A")
        route("place")
        if (project.projectKey() !in openConfigStore().load().projects) {
            ports.nextDirectory = project
            compose.onNodeWithTag("place-add-project").performClick()
            compose.waitUntil(15_000) { project.projectKey() in openConfigStore().load().projects }
        }
        val recent = uiTarget("place", "project:${project.projectKey()}", "recent", "select")
        val row = if (compose.onAllNodesWithTag(recent).fetchSemanticsNodes().isNotEmpty()) recent
            else uiTarget("place", "project:${project.projectKey()}", "ungrouped", "select")
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(row))
        compose.onNodeWithTag(row).performClick()
        compose.onNodeWithTag("place-palette-search").performTextReplacement("portable-a")
        val target = uiTarget("palette", "RULE:portable-a", "project:${project.projectKey()}", action)
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("place-palette-list").performScrollToNode(hasTestTag(target)) }.isSuccess
        }
        compose.onNodeWithTag(target).performClick()
        compose.waitUntil(15_000) {
            val installed = project.resolve("AGENTS.md")
            Files.exists(installed) && installed.readText().contains(if (action == "install") "Body portable-a" else "A updated")
        }
    }

    private fun assertSettled() {
        compose.waitUntil(30_000) { remoteHead() == Git.open(library.toFile()).use { it.repository.resolve("HEAD")?.name } }
        assertFalse(Files.exists(library.resolve(".git/MERGE_HEAD")))
    }

    private fun returnFocus(atMillis: Long) {
        compose.runOnIdle { focused.value = false }
        compose.waitForIdle()
        compose.runOnIdle {
            focusNow.set(atMillis)
            focused.value = true
        }
        compose.waitForIdle()
    }

    @Test fun Y01_remoteDraftCancelSaveAndHomeSummary() {
        start()
        create("connection")
        initRemote()
        settings()
        compose.onNodeWithTag("settings-remote-url").performTextReplacement(remote.toUri().toString())
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("other")
        assertEquals(null, openConfigStore().load().remoteGit)
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("main")
        compose.onNodeWithTag("settings-remote-save").performClick()
        compose.waitUntil(30_000) { remoteHead() != null }
        assertEquals("main", openConfigStore().load().remoteGit?.branch)
        compose.onNodeWithTag("settings-remote-edit").performClick()
        compose.onNodeWithTag("settings-remote-url").performTextReplacement("file:///cancelled")
        compose.onNodeWithText(EnStrings.remoteGitCancel).performClick()
        assertEquals(remote.toUri().toString(), openConfigStore().load().remoteGit?.url)
        route("home")
        compose.onNodeWithText(EnStrings.homeSyncTitle).assertIsDisplayed()
        compose.onNodeWithText(EnStrings.homeSyncRemote(remote.toUri().toString(), "main")).assertIsDisplayed()
        assertSettled()
        assertSyncState(EnStrings.remoteGitPushed)
        sync()
        assertSyncState(EnStrings.remoteGitUpToDate)
        assertSettled()
    }

    @Test fun Y02_twoClientsTransferOnlyPortableLibrary() {
        start()
        create("portable-a")
        configure()
        assertSettled()
        cloneB()
        assertEquals(content("portable-a"), BlockFile.parse("portable-a", second.resolve("blocks/portable-a.md").readText()))
        projectRule("install")
        bSave("portable-b", "From B")
        sync()
        compose.waitUntil(30_000) { Files.exists(path("portable-b")) }
        assertEquals("From B", content("portable-b").content)
        change("portable-a", "A updated")
        assertTrue(root.resolve("projects/Проект A/AGENTS.md").readText().contains("Body portable-a"))
        projectRule("update")
        sync()
        assertSettled()
        Git.open(library.toFile()).use { git ->
            assertTrue(git.log().addPath("blocks/portable-b.md").call().any { it.fullMessage == "Update portable-b" })
        }
        Git.open(second.toFile()).use { git ->
            val pulled = git.pull().call()
            assertTrue(pulled.isSuccessful)
            assertEquals(MergeResult.MergeStatus.FAST_FORWARD, pulled.mergeResult.mergeStatus)
        }
        assertEquals("A updated", BlockFile.parse("portable-a", second.resolve("blocks/portable-a.md").readText()).content)
        val portable = setOf(".git", ".gitignore", "blocks", "groups", "profiles", "skills")
        Files.list(second).use { entries ->
            entries.forEach { assertTrue(it.fileName.toString() in portable, "Machine state in remote: $it") }
        }
    }

    @Test fun Y03_disjointSameObjectAndDeleteModifyConflicts() {
        start()
        create("shared")
        create("deleted")
        configure()
        cloneB()
        change("shared", "Local edit")
        remove("deleted")
        bSave("shared", "Remote edit")
        bSave("deleted", "Remote survivor")
        bSave("disjoint", "Remote only")
        val localVersion = content("shared").version
        val remoteVersion = BlockFile.parse("shared", second.resolve("blocks/shared.md").readText()).version
        val remoteEdit = Git.open(second.toFile()).use { requireNotNull(it.repository.resolve("HEAD")).name }
        sync()
        assertSettled()
        compose.waitUntil(30_000) { Files.exists(path("disjoint")) && Files.exists(path("deleted")) }
        assertEquals("Local edit", content("shared").content)
        assertEquals("Remote survivor", content("deleted").content)
        assertEquals("Remote only", content("disjoint").content)
        assertTrue(content("shared").version > maxOf(localVersion, remoteVersion))
        Git.open(library.toFile()).use { git -> assertTrue(git.log().call().any { it.name == remoteEdit }) }
        route("home")
        compose.onNodeWithText("shared").assertIsDisplayed()
        compose.onNodeWithTag(uiTarget("home", "BLOCK:deleted", "sync", "conflict")).assertExists()
        val conflict = uiTarget("home", "BLOCK:shared", "sync", "conflict")
        compose.onNode(hasText(EnStrings.homeSyncHistory) and hasAnyAncestor(hasTestTag(conflict)),
            useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("library", "RULE:shared", "inspector", "selected"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        route("home")
        compose.onNode(hasText(EnStrings.homeSyncCompare) and hasAnyAncestor(hasTestTag(conflict)),
            useUnmergedTree = true).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("compare-candidate-picker").fetchSemanticsNodes().isNotEmpty() }
        assertSettled()
    }

    @Test fun Y04_wholeSkillTreeGroupsProfilesAndDerivedAll() {
        start()
        create("member")
        createKind("SKILL", "synthetic")
        createKind("GROUP", "bundle")
        createKind("PROFILE", "mode")
        addMemberships()
        configure()
        cloneB()
        val skill = second.resolve("skills/synthetic")
        Files.createDirectories(skill.resolve("files/scripts"))
        skill.resolve("files/notes.md").writeText("Original notes\n")
        skill.resolve("files/scripts/run.sh").writeText("#!/bin/sh\nexit 0\n")
        skill.resolve("files/scripts/run.sh").toFile().setExecutable(true)
        val skillMetaPath = skill.resolve("meta.yaml")
        val skillMeta = Yaml.default.decodeFromString(SkillMeta.serializer(), skillMetaPath.readText())
        skillMetaPath.writeText(Yaml.default.encodeToString(SkillMeta.serializer(), skillMeta.copy(
            executableFiles = listOf("scripts/run.sh"),
        )) + "\n")
        bCommit("Expand portable skill")
        sync()
        compose.waitUntil(30_000) { Files.exists(library.resolve("skills/synthetic/files/scripts/run.sh")) }
        changeSkill("synthetic", "---\nname: synthetic\ndescription: Synthetic\n---\nLocal skill")
        create("local-extra")
        addLocalMembership("local-extra")
        skill.resolve("files/SKILL.md").writeText("---\nname: synthetic\ndescription: Synthetic\n---\nRemote skill\n")
        skill.resolve("files/notes.md").writeText("Remote notes\n")
        Files.delete(skill.resolve("files/scripts/run.sh"))
        skillMetaPath.writeText(Yaml.default.encodeToString(SkillMeta.serializer(), skillMeta) + "\n")
        bCommit("Replace skill tree")
        bSave("remote-extra", "Remote group member")
        val remoteGroup = second.resolve("groups/bundle.yaml")
        val group = Yaml.default.decodeFromString(Group.serializer(), remoteGroup.readText())
        remoteGroup.writeText(Yaml.default.encodeToString(Group.serializer(), group.copy(
            blockIds = group.blockIds + "remote-extra", version = group.version + 1,
        )) + "\n")
        val remoteProfile = second.resolve("profiles/mode.yaml")
        val profile = Yaml.default.decodeFromString(Profile.serializer(), remoteProfile.readText())
        remoteProfile.writeText(Yaml.default.encodeToString(Profile.serializer(), profile.copy(
            blockIds = profile.blockIds + "remote-extra", version = profile.version + 1,
        )) + "\n")
        bCommit("Change portable definitions")
        sync()
        assertSettled()
        compose.waitUntil(30_000) { library.resolve("skills/synthetic/files/SKILL.md").readText().contains("Local skill") }
        assertEquals("Original notes\n", library.resolve("skills/synthetic/files/notes.md").readText())
        val localSkill = library.resolve("skills/synthetic")
        assertEquals(listOf("scripts/run.sh"),
            Yaml.default.decodeFromString(SkillMeta.serializer(), localSkill.resolve("meta.yaml").readText()).executableFiles)
        if (Files.getFileStore(localSkill).supportsFileAttributeView("posix")) {
            assertTrue(Files.isExecutable(localSkill.resolve("files/scripts/run.sh")))
        }
        val mergedGroup = Yaml.default.decodeFromString(Group.serializer(), library.resolve("groups/bundle.yaml").readText())
        val mergedProfile = Yaml.default.decodeFromString(Profile.serializer(), library.resolve("profiles/mode.yaml").readText())
        assertTrue("local-extra" in mergedGroup.blockIds)
        assertTrue("local-extra" in mergedProfile.blockIds)
        assertTrue(mergedGroup.version > group.version + 1)
        assertTrue(mergedProfile.version > profile.version + 1)
        assertTrue(Files.exists(path("remote-extra")))
        (mergedGroup.blockIds + mergedProfile.blockIds).forEach { assertTrue(Files.exists(path(it)), it) }
        mergedProfile.groupIds.forEach { assertTrue(Files.exists(library.resolve("groups/$it.yaml")), it) }
        val allFile = library.resolve("groups/all.yaml")
        val all = Yaml.default.decodeFromString(Group.serializer(), allFile.readText())
        val blockIds = Files.list(library.resolve("blocks")).use { files ->
            files.map { it.fileName.toString().removeSuffix(".md") }.toList().toSet()
        }
        assertEquals(blockIds, all.blockIds.toSet())
        assertTrue("synthetic" in all.skillIds)
        val allText = allFile.readText()
        val head = localHead()
        sync()
        assertSettled()
        assertEquals(head, localHead())
        assertEquals(allText, allFile.readText())
    }

    @Test fun Y05_emptyCloneAndUnrelatedHistoryRequiresChoice() {
        seedRemote(remote, "existing")
        start()
        configure()
        compose.waitUntil(30_000) { Files.exists(path("existing")) }
        create("local")
        val foreign = root.resolve("remote/foreign.git")
        seedRemote(foreign, "foreign")
        configure(foreign)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(EnStrings.remoteGitUnrelatedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(EnStrings.remoteGitUnrelatedTitle).assertIsDisplayed()
        assertEquals("Body local", content("local").content)
        assertFalse(Files.exists(path("foreign")))
    }

    @Test fun Y06_cancelMergeAndReplaceAfterZip() {
        start()
        create("local")
        val foreign = root.resolve("remote/foreign.git")
        seedRemote(foreign, "foreign")
        configure(foreign)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(EnStrings.remoteGitUnrelatedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(EnStrings.remoteGitUnrelatedTitle).assertIsDisplayed()
        compose.onNodeWithText(EnStrings.actionCancel).performClick()
        assertFalse(Files.exists(path("foreign")))
        sync()
        compose.onNodeWithText(EnStrings.remoteGitMergeAsImport).performClick()
        compose.waitUntil(30_000) { Files.exists(path("foreign")) }
        assertTrue(Files.exists(path("local")))
        assertSettledFor(foreign)
        val other = root.resolve("remote/replacement.git")
        seedRemote(other, "replacement")
        configure(other)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(EnStrings.remoteGitUnrelatedTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(EnStrings.remoteGitReplaceAfterExport).performClick()
        assertFalse(Files.exists(path("replacement")))
        ports.nextArchive = root.resolve("archives/before-replace.zip")
        compose.onNodeWithText(EnStrings.remoteGitReplaceAfterExport).performClick()
        compose.waitUntil(30_000) { Files.exists(path("replacement")) }
        ZipFile(root.resolve("archives/before-replace.zip").toFile()).use { archive ->
            assertTrue(archive.entries().asSequence().any { it.name == "blocks/local.md" })
        }
        assertFalse(Files.exists(path("local")))
        assertSettledFor(other)
        settings()
        ports.nextArchive = root.resolve("archives/before-replace.zip")
        compose.onNodeWithText(EnStrings.libImport).performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(EnStrings.libImportTitle).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(path("local")) }
        assertEquals("Body local", content("local").content)
    }

    private fun assertSettledFor(target: Path) {
        compose.waitUntil(30_000) { remoteHead(target) == Git.open(library.toFile()).use { it.repository.resolve("HEAD")?.name } }
        assertFalse(Files.exists(library.resolve(".git/MERGE_HEAD")))
    }

    @Test fun Y07_failedTransportKeepsLocalCommitsAndEditing() {
        start()
        create("first")
        configure()
        val missing = root.resolve("remote/missing.git")
        settings()
        compose.onNodeWithTag("settings-remote-edit").performClick()
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("bad branch")
        compose.onNodeWithTag("settings-remote-save").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Invalid remote branch", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("main", openConfigStore().load().remoteGit?.branch)
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("main")
        compose.onNodeWithTag("settings-remote-url").performTextReplacement(missing.toUri().toString())
        compose.onNodeWithTag("settings-remote-save").performClick()
        compose.waitUntil(30_000) {
            openConfigStore().load().remoteGit?.url == missing.toUri().toString() &&
                compose.onAllNodesWithText(EnStrings.remoteGitFailed, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        create("second")
        assertEquals("Body second", content("second").content)
        configure()
        assertSettled()
        settings()
        @Suppress("DEPRECATION")
        val previous = requireNotNull(System.getSecurityManager())
        @Suppress("DEPRECATION")
        System.setSecurityManager(object : SecurityManager() {
            override fun checkPermission(permission: Permission) = previous.checkPermission(permission)
            override fun checkRead(file: String) {
                if (file.startsWith(remote.toString())) throw SecurityException("Synthetic authorization denied")
                previous.checkRead(file)
            }
            override fun checkWrite(file: String) = previous.checkWrite(file)
            override fun checkDelete(file: String) = previous.checkDelete(file)
            override fun checkExec(command: String) = previous.checkExec(command)
            override fun checkConnect(host: String, port: Int) = previous.checkConnect(host, port)
        })
        try {
            compose.onNodeWithTag("settings-remote-sync").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText("Synthetic authorization denied", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            @Suppress("DEPRECATION")
            System.setSecurityManager(previous)
        }
        create("third")
        assertEquals("Body third", content("third").content)
        Git.open(library.toFile()).use { git -> assertTrue(git.log().addPath("blocks/third.md").call().any()) }
        assertFalse(Files.exists(library.resolve(".git/MERGE_HEAD")))
    }

    @Test fun Y08_saveAndRepeatedSyncRemainResponsive() {
        start()
        create("before")
        configure()
        settings()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val held = AtomicBoolean(false)
        @Suppress("DEPRECATION") // The opt-in write sandbox deliberately uses JDK 17.
        val previous = requireNotNull(System.getSecurityManager())
        val caller = Thread.currentThread()
        @Suppress("DEPRECATION")
        System.setSecurityManager(object : SecurityManager() {
            override fun checkPermission(permission: Permission) = previous.checkPermission(permission)
            override fun checkRead(file: String) {
                previous.checkRead(file)
                if (Thread.currentThread() != caller && file.startsWith(remote.toString()) &&
                    held.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "Remote fixture was not released" }
                }
            }
            override fun checkWrite(file: String) = previous.checkWrite(file)
            override fun checkDelete(file: String) = previous.checkDelete(file)
            override fun checkExec(command: String) = previous.checkExec(command)
            override fun checkConnect(host: String, port: Int) = previous.checkConnect(host, port)
        })
        try {
            compose.waitUntil(15_000) {
                entered.count == 0L || run {
                    if (runCatching { compose.onNodeWithTag("settings-remote-sync").assertIsNotEnabled() }.isFailure)
                        compose.onNodeWithTag("settings-remote-sync").performClick()
                    false
                }
            }
            compose.onNodeWithTag("settings-remote-sync").assertIsNotEnabled()
            create("during")
            assertEquals("Body during", content("during").content)
        } finally {
            release.countDown()
            @Suppress("DEPRECATION")
            System.setSecurityManager(previous)
        }
        assertSettled()
    }

    @Test fun Y09_automaticSettingSurvivesRestartAndSave() {
        useFocusFixture = true
        if (stage == "first") {
            start()
            create("baseline")
            configure(automatic = false)
            assertSettled()
            val before = remoteHead()
            create("manual-only")
            assertEquals(before, remoteHead())
            settings()
            compose.onNodeWithTag("settings-remote-edit").performClick()
            compose.onNodeWithTag("settings-remote-automatic").performClick().assertIsOn()
            compose.onNodeWithTag("settings-remote-save").performClick()
            compose.waitUntil(30_000) { remoteHead() == Git.open(library.toFile()).use { it.repository.resolve("HEAD")?.name } }
            assertTrue(openConfigStore().load().remoteGit?.automatic == true)
            cloneB()
            bSave("launch-arrival", "From B before restart")
        } else {
            start()
            assertTrue(openConfigStore().load().remoteGit?.automatic == true)
            compose.waitUntil(30_000) { Files.exists(path("launch-arrival")) }
            bSave("focus-arrival", "From B before focus")
            returnFocus(1_000_000L)
            compose.waitUntil(30_000) { Files.exists(path("focus-arrival")) }
            bSave("throttled", "From B during throttle")
            returnFocus(1_060_000L)
            assertFalse(Files.exists(path("throttled")))
            returnFocus(1_301_000L)
            compose.waitUntil(30_000) { Files.exists(path("throttled")) }
            create("auto-second")
            assertSettled()
        }
    }

    @Test fun Y10_restartAfterFailureRemoveRemoteAndNoOpSync() {
        if (stage == "first") {
            start()
            create("persisted")
            configure()
            assertSettled()
            val before = remoteHead()
            val missing = root.resolve("remote/temporarily-missing.git")
            settings()
            compose.onNodeWithTag("settings-remote-edit").performClick()
            compose.onNodeWithTag("settings-remote-url").performTextReplacement(missing.toUri().toString())
            compose.onNodeWithTag("settings-remote-save").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithText(EnStrings.remoteGitFailed, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(before, remoteHead())
            root.resolve("artifacts/y10-head.txt").writeText(requireNotNull(before))
        } else {
            start()
            assertEquals("Body persisted", content("persisted").content)
            assertFalse(Files.exists(library.resolve(".git/MERGE_HEAD")))
            assertEquals(root.resolve("artifacts/y10-head.txt").readText(), remoteHead())
            configure()
            assertSettled()
            val before = remoteHead()
            sync()
            assertEquals(before, remoteHead())
            assertEquals(before, localHead())
            settings()
            compose.waitUntil(15_000) {
                openConfigStore().load().remoteGit == null ||
                    run { compose.onNodeWithText(EnStrings.remoteGitRemove).performClick(); false }
            }
            assertEquals("Body persisted", content("persisted").content)
            assertEquals(before, localHead())
        }
    }
}
