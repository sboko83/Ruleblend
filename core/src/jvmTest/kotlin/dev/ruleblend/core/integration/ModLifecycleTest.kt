package dev.ruleblend.core.integration

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.usecase.ModLifecycle
import dev.ruleblend.core.usecase.ModLibraryRemovalChoice
import dev.ruleblend.core.usecase.MutateLibrary
import java.nio.file.Files
import kotlin.test.*

class ModLifecycleTest {
    private val scratch = Files.createTempDirectory("ruleblend-mod-lifecycle").toRealPath()
    private val root = scratch.resolve("runtime")
    private val libraryRoot = scratch.resolve("library")
    private val repository = LibraryRepository(libraryRoot,
        LibraryGit(libraryRoot, InterProcessLock(scratch.resolve("ruleblend.lock")))).also { it.init() }
    private val cli = FakeModCli(root)
    private val runtime = ClaudeModRuntime(root, cli)
    private val mutations = MutateLibrary(repository, ConfigStore(scratch.resolve("config.json")))
    private val lifecycle = ModLifecycle(repository, mutations, runtime)
    @AfterTest fun cleanup() = deleteFixtureTree(scratch)

    private fun imported() {
        repository.writeMod(runtimeFixture())
        repository.writeGroup(Group("selected", "Selected", modIds = listOf("demo")))
        lifecycle.install("demo")
    }

    @Test fun `installed library removal requires a fresh preview and explicit choice`() {
        imported()
        val preview = lifecycle.previewLibraryRemoval("demo")
        assertEquals(ModRuntimeError.CONFIRMATION_REQUIRED, assertFailsWith<ModRuntimeException> {
            lifecycle.removeFromLibrary("demo", null, preview)
        }.error)
        lifecycle.disable("demo")
        assertEquals(ModRuntimeError.CONFIRMATION_REQUIRED, assertFailsWith<ModRuntimeException> {
            lifecycle.removeFromLibrary("demo", ModLibraryRemovalChoice.KEEP_INSTALLATION, preview)
        }.error)
        assertNotNull(repository.loadMod("demo"))
    }

    @Test fun `keep installation removes memberships but retains runtime snapshot and actions`() {
        imported()
        val before = lifecycle.status("demo")
        lifecycle.removeFromLibrary("demo", ModLibraryRemovalChoice.KEEP_INSTALLATION, lifecycle.previewLibraryRemoval("demo"))
        assertNull(repository.loadMod("demo"))
        assertTrue(repository.loadGroup("selected")!!.modIds.isEmpty())
        assertEquals(before, lifecycle.status("demo"))
        assertFalse(lifecycle.disable("demo").enabled)
        assertEquals(1, cli.plugins.size)
    }

    @Test fun `explicit uninstall is confirmed before deleting the library object`() {
        imported()
        lifecycle.removeFromLibrary("demo", ModLibraryRemovalChoice.UNINSTALL, lifecycle.previewLibraryRemoval("demo"))
        assertNull(repository.loadMod("demo")); assertFalse(lifecycle.status("demo").installed)
        val store = ModRuntimeStore(root)
        assertTrue(Files.exists(store.snapshotPath(store.load().records.getValue("demo"))))
    }

    @Test fun `failed uninstall preserves the library object and runtime journal`() {
        imported()
        cli.failCommand = "uninstall"
        assertFailsWith<ModRuntimeException> {
            lifecycle.removeFromLibrary("demo", ModLibraryRemovalChoice.UNINSTALL, lifecycle.previewLibraryRemoval("demo"))
        }
        assertNotNull(repository.loadMod("demo")); assertNotNull(ModRuntimeStore(root).load().pending)
    }

    @Test fun `synced library deletion never invokes CLI or removes an installation`() {
        imported()
        val calls = cli.calls.size
        repository.deleteMod("demo")
        assertEquals(calls, cli.calls.size)
        assertTrue(lifecycle.status("demo").installed)
        assertTrue(lifecycle.disable("demo").reloadRequired)
    }

    @Test fun `changed library snapshot invalidates deletion approval`() {
        imported()
        val accepted = lifecycle.previewLibraryRemoval("demo")
        repository.writeMod(runtimeFixture(revision = "B"))
        assertEquals(ModRuntimeError.CONFIRMATION_REQUIRED, assertFailsWith<ModRuntimeException> {
            lifecycle.removeFromLibrary("demo", ModLibraryRemovalChoice.UNINSTALL, accepted)
        }.error)
        assertNotNull(repository.loadMod("demo")); assertEquals(1, cli.plugins.size)
    }
}
