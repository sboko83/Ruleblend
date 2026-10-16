package dev.ruleblend.app.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.*
import dev.ruleblend.core.model.*
import dev.ruleblend.core.storage.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import org.junit.Rule

class ModsUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var library: LibraryModel
    private lateinit var runtime: MemoryRuntime

    @BeforeTest fun setup() {
        ModsFeature.enabled = true
        root = Files.createTempDirectory("ruleblend-mod-ui-")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        runtime = MemoryRuntime()
        library = LibraryModel(repository, LibraryArchive(root.resolve("library"), repository),
            ConfigStore(root.resolve("config.json")), modRuntime = runtime)
    }
    @AfterTest fun teardown() { ModsFeature.enabled = false; root.toFile().deleteRecursively() }

    private fun snapshot(body: String = "A", source: ModSource? = null): ModSnapshot = ModImport.validate(listOf(
        SkillFile(".claude-plugin/plugin.json", """{"name":"demo","version":"1.0","description":"Demo"}""".encodeToByteArray()),
        SkillFile("hooks/hooks.json", """{"modules":["./register.js"]}""".encodeToByteArray()),
        SkillFile("hooks/register.js", "export function register(on) {} // $body".encodeToByteArray()),
        SkillFile("commands/info.md", "Info".encodeToByteArray()),
    ), source).let { it.copy(mod = it.mod.copy(id = "demo")) }

    private fun seed(source: ModSource? = null) {
        repository.writeMod(snapshot(source = source)); repository.syncAllGroup()
        runBlocking { library.load(); library.modUi.refresh() }
    }
    private fun panel() = compose.setContent {
        RuleblendTheme(ThemeMode.LIGHT) { Column(Modifier.width(800.dp)) { ModPanel(library.modUi, "demo") } }
    }

    @Test fun `frozen mods stay in storage but leave the catalog and never reach the CLI`() {
        ModsFeature.enabled = false
        repository.writeMod(snapshot()); repository.syncAllGroup()
        runBlocking { library.load(); library.refreshUsage() }
        assertTrue(repository.listMods().any { it.id == "demo" })
        assertTrue(library.catalog.objects.none { it.key.kind == LibraryObjectKind.MOD })
        assertEquals(0, runtime.reads)
    }

    @Test fun `library revisions stay separate from installed runtime and lifecycle actions require clicks`() {
        seed(); panel()
        compose.onNodeWithTag("mod-install:demo").performClick()
        assertEquals(0, runtime.writes)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { runtime.writes == 1 && !library.modUi.busy }
        compose.onNodeWithTag("mod-status:demo").assertTextEquals("Installed · enabled")
        compose.onNodeWithTag("mod-reload:demo").assertExists()
        val original = runtime.records.getValue("demo").fingerprint
        repository.writeMod(snapshot("B"))
        compose.runOnIdle { runBlocking { library.load(); library.modUi.refresh() } }
        assertEquals(original, runtime.records.getValue("demo").fingerprint)
        compose.onNodeWithTag("mod-update:demo").performClick()
        compose.waitUntil(5_000) { runtime.writes == 2 && !library.modUi.busy }
        assertNotEquals(original, runtime.records.getValue("demo").fingerprint)
        compose.onNodeWithTag("mod-toggle:demo").performClick()
        compose.waitUntil(5_000) { runtime.writes == 3 && !library.modUi.busy }
        compose.onNodeWithTag("mod-status:demo").assertTextEquals("Installed · disabled")
        compose.onNodeWithTag("mod-toggle:demo").performClick()
        compose.waitUntil(5_000) { runtime.writes == 4 && !library.modUi.busy }
        compose.onNodeWithTag("mod-uninstall:demo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("dialog-confirm").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(4, runtime.writes)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { runtime.writes == 5 && !library.modUi.busy }
        assertFalse(runtime.records.getValue("demo").installed)
        assertNotNull(repository.loadMod("demo"))
    }

    @Test fun `deleting from Library retains an orphan with lifecycle controls`() {
        seed(); runBlocking { library.modUi.install("demo") }; panel()
        compose.onNodeWithTag("mod-delete:demo").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mod-delete-uninstall:demo").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mod-delete-keep:demo").assertIsNotEnabled()
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { library.mods.isEmpty() && !library.modUi.busy }
        assertNull(repository.loadMod("demo")); assertTrue(runtime.records.getValue("demo").installed)
        compose.onNodeWithTag("mod-toggle:demo").assertExists()
        compose.onNodeWithTag("mod-delete:demo").assertDoesNotExist()
    }

    @Test fun `local preview reports whole package and import never installs`() {
        val folder = root.resolve("local package")
        snapshot().files.forEach { file -> folder.resolve(file.path).let { path ->
            Files.createDirectories(path.parent); Files.write(path, file.bytes)
        } }
        var dismissed = false
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) {
            if (!dismissed) ModAddDialog(library.modUi, { dismissed = true }, initialFolder = folder.toString())
        } }
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("mod-import-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("hooks/register.js", substring = true).assertExists()
        assertTrue(repository.listMods().isEmpty()); assertEquals(0, runtime.writes)
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { dismissed }
        assertEquals(1, library.mods.size); assertEquals(0, runtime.writes)
        assertTrue(library.catalog.objects.any { it.key.kind == LibraryObjectKind.MOD })
    }

    @Test fun `group bulk install only writes Claude user scope and never applies library updates`() {
        seed()
        repository.saveGroup(Group("tools", "Tools", modIds = listOf("demo")))
        runBlocking { library.load() }
        library.toggleMark(LibraryObjectKey(LibraryObjectKind.GROUP, "tools"))
        val first = runBlocking { library.installMarked(setOf("agent:claude-code", "agent:codex", "project:/code/demo")) }
        assertEquals(1, first.written); assertEquals(2, first.skippedTotal); assertEquals(1, runtime.writes)
        val installed = runtime.records.getValue("demo").fingerprint
        repository.writeMod(snapshot("B"))
        runBlocking { library.load(); library.installMarked(setOf("agent:claude-code")) }
        assertEquals(installed, runtime.records.getValue("demo").fingerprint); assertEquals(1, runtime.writes)
        val mod = library.catalog.objects.single { it.key.kind == LibraryObjectKind.MOD }
        assertTrue("tools" in mod.groupIds)
        assertEquals(listOf("demo"), groupWithAdded(Group("extra", "Extra"), setOf(mod.key)).modIds)
        val zip = root.resolve("selected.zip")
        library.exportMarked(zip)
        val peer = LibraryRepository(root.resolve("peer")).also { it.init() }
        val plan = LibraryArchive(root.resolve("peer"), peer).plan(zip)
        assertEquals("demo", plan.mods.single().incoming.mod.id)
    }

    @Test fun `foreign and built-in entries are read only and unavailable CLI blocks runtime actions`() {
        seed()
        runtime.foreign = listOf(ModRuntimeEntry("external@vendor", "user", true, installPath = root.toString()),
            ModRuntimeEntry("builtin@builtin", "user", true, builtIn = true))
        runtime.records["demo"] = ModRuntimeStatus("demo", ModRuntimeState.FOREIGN, installed = true, enabled = true)
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { ModGlobalPanel(library.modUi) } }
        compose.waitUntil(5_000) { library.modUi.entries.size == 3 && !library.modUi.busy }
        compose.onNodeWithTag("mod-import-installed:external@vendor").assertExists()
        compose.onNodeWithTag("mod-import-installed:builtin@builtin").assertDoesNotExist()
        assertEquals(0, runtime.writes)
    }

    @Test fun `unavailable CLI does not prevent local package preview or misreport an installation`() {
        seed(); runtime.unavailable = true; panel()
        compose.waitUntil(5_000) { library.modUi.error != null && !library.modUi.busy }
        compose.onNodeWithTag("mod-error:demo").assertTextEquals("CLI unavailable")
        compose.onNodeWithTag("mod-install:demo").assertDoesNotExist()
        assertNotNull(runBlocking { library.modUi.preview(root.resolve("library/mods/demo/files")) }.snapshot)
    }

    @Test fun `Git rediscovery keeps existing mods unticked and unsupported candidates visible`() {
        val source = ModSource("https://example.com/mods.git", "a".repeat(40), "mods/demo")
        seed(source)
        val plan = GitRepositoryImportPlan(source.repository, "A", "A", emptyList(), mods = listOf(
            ModImportCandidate(source.path, snapshot(source = source)), ModImportCandidate("mods/broken", error = "Plugin dependencies are unsupported")))
        assertTrue(GitImportSelection.initial(plan, emptyList(), emptyList(), library.mods).mods.isEmpty())
        assertEquals(setOf(source.path), GitImportSelection.initial(plan, emptyList(), emptyList()).mods)
        val fetched = LibraryModel(repository, LibraryArchive(root.resolve("library"), repository),
            ConfigStore(root.resolve("config.json")), GitRepositoryFetcher { plan }, modRuntime = runtime)
        runBlocking { fetched.load() }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { ImportSkillsDialog(fetched, source.repository) {} } }
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("library-git-import-mod:${source.path}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-git-import-mod:${source.path}").assertIsOff()
        compose.onNodeWithText("mods/broken: Plugin dependencies are unsupported").assertExists()
        compose.onNodeWithTag("dialog-confirm").assertIsNotEnabled()
        assertEquals(0, runtime.writes)
    }

    @Test fun `Global Claude Mods tab switches palette and is absent from projects and other assistants`() {
        seed()
        val home = root.resolve("home")
        Files.createDirectories(home.resolve(".claude")); Files.createDirectories(home.resolve(".codex"))
        val project = Files.createDirectories(root.resolve("project"))
        val config = ConfigStore(root.resolve("config.json"))
        config.save(dev.ruleblend.core.config.AppConfig(projects = listOf(project.toString())))
        val model = dev.ruleblend.app.integration.IntegrationModel(repository, config,
            listOf(ClaudeCodeAdapter(home), CodexAdapter(home)), BackupService(root.resolve("backups")),
            mcpService = dev.ruleblend.mcp.install.McpInstallService(installers = emptyList(),
                state = dev.ruleblend.mcp.install.McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
            cliProbe = { null })
        runBlocking { model.load(); model.select(model.agentTargets.single { it.agent.id == "claude-code" }) }
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) {
            dev.ruleblend.app.place.PlaceScreen(model, modUi = library.modUi)
        } }
        compose.onNodeWithTag("place-tab-mods").performClick()
        compose.onNodeWithTag("place-mods").assertExists()
        compose.onNodeWithTag("place-mod-palette").assertExists()
        compose.onNodeWithTag("place-tab-rules").performClick()
        compose.onNodeWithTag("place-mod-palette").assertDoesNotExist()
        compose.runOnIdle { runBlocking { model.select(model.projectTargets.single()) } }
        compose.onNodeWithTag("place-tab-mods").assertDoesNotExist()
        compose.runOnIdle { runBlocking { model.select(model.agentTargets.single { it.agent.id == "codex" }) } }
        compose.onNodeWithTag("place-tab-mods").assertDoesNotExist()
        assertEquals(0, runtime.writes)
    }

    @Test fun `synced incompatible packages show their reason before runtime installation`() {
        val accepted = snapshot()
        val unsupported = accepted.copy(files = accepted.files.map { file ->
            if (file.path == ".claude-plugin/plugin.json") file.copy(bytes =
                """{"name":"demo","version":"1.0","dependencies":["remote-plugin"]}""".encodeToByteArray()) else file
        })
        repository.writeMod(unsupported)
        runBlocking { library.load(); library.modUi.refresh() }
        panel()
        compose.onNodeWithTag("mod-compatibility:demo").assertTextEquals("Plugin dependencies are unsupported")
        compose.onNodeWithTag("mod-install:demo").assertIsNotEnabled()
        assertEquals(0, runtime.writes)
    }

    @Test fun `a package changed since preview is not installed without another review`() {
        seed()
        repository.writeMod(snapshot("Unseen replacement"))
        assertFalse(runBlocking { library.modUi.install("demo") })
        assertEquals(0, runtime.writes)
        assertTrue(library.modUi.error.orEmpty().contains("changed after the installation preview"))
        assertTrue(runBlocking { library.modUi.install("demo") })
        assertEquals(modTreeFingerprint(snapshot("Unseen replacement").files), runtime.records.getValue("demo").fingerprint)
    }

    private class MemoryRuntime : ModRuntime {
        val records = mutableMapOf<String, ModRuntimeStatus>()
        var foreign = emptyList<ModRuntimeEntry>()
        var writes = 0
        var reads = 0
        var unavailable = false
        override fun inventory(target: ModTarget): List<ModRuntimeEntry> {
            reads++
            check(!unavailable) { "CLI unavailable" }
            return records.filterValues { it.installed }.map { (id, status) -> ModRuntimeEntry(status.pluginId ?: id,
                "user", status.enabled, id) } + foreign
        }
        override fun probe(target: ModTarget, libraryId: String, manifestName: String?) = records[libraryId]
            ?: ModRuntimeStatus(libraryId, ModRuntimeState.ABSENT)
        override fun install(target: ModTarget, snapshot: ModSnapshot): ModRuntimeStatus {
            writes++
            return ModRuntimeStatus(snapshot.mod.id, ModRuntimeState.OWNED, "${snapshot.mod.manifestName}@ruleblend-mods",
                installed = true, enabled = true, fingerprint = modTreeFingerprint(snapshot.files), reloadRequired = true)
                .also { records[snapshot.mod.id] = it }
        }
        override fun update(target: ModTarget, snapshot: ModSnapshot) = install(target, snapshot)
        override fun enable(target: ModTarget, libraryId: String) = toggle(libraryId, true)
        override fun disable(target: ModTarget, libraryId: String) = toggle(libraryId, false)
        private fun toggle(id: String, enabled: Boolean): ModRuntimeStatus {
            writes++; return records.getValue(id).copy(enabled = enabled).also { records[id] = it }
        }
        override fun previewRemove(target: ModTarget, libraryId: String) = ModRemovalPreview(records.getValue(libraryId), "user",
            lastInstallation = true, consequences = listOf("Options and secrets are removed"))
        override fun remove(target: ModTarget, libraryId: String, accepted: ModRemovalPreview): ModRuntimeStatus {
            require(accepted == previewRemove(target, libraryId)); writes++
            return records.getValue(libraryId).copy(installed = false, enabled = false).also { records[libraryId] = it }
        }
        override fun recover(target: ModTarget) = records.values.toList()
    }
}
