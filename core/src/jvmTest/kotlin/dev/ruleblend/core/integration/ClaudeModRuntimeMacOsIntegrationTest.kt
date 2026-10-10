package dev.ruleblend.core.integration

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.process.BoundedCliProcess
import dev.ruleblend.core.process.CliProcess
import dev.ruleblend.core.storage.*
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.ModLifecycle
import dev.ruleblend.core.usecase.MutateLibrary
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*
import org.eclipse.jgit.api.Git

/** Invoked only by tools/claude_mod_runtime_gate.py after OS isolation is proven. */
class ClaudeModRuntimeMacOsIntegrationTest {
    @Test fun `global lifecycle ignores project enabled overrides in the inherited working directory`() {
        val control = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(
            System.getenv("RULEBLEND_MOD_GATE_CONTROL")) { "Run tools/claude_mod_runtime_gate.py" }))).jsonObject
        val scratch = Path.of(control.getValue("root").jsonPrimitive.content)
        val workspace = scratch.resolve("shadowed workspace")
        val profile = scratch.resolve("shadowed profile")
        Files.createDirectories(workspace.resolve(".claude"))
        Files.createDirectories(profile)
        val projectSettings = """{"enabledPlugins":{"demo@ruleblend-mods":false}}"""
        Files.writeString(workspace.resolve(".claude/settings.json"), projectSettings)
        Files.writeString(profile.resolve(".claude.json"), buildJsonObject {
            put("hasCompletedOnboarding", true)
            put("projects", buildJsonObject { put(workspace.toString(), buildJsonObject {
                put("hasTrustDialogAccepted", true); put("hasCompletedProjectOnboarding", true)
            }) })
        }.toString())
        val environment = control.getValue("environment").jsonObject.mapValues { it.value.jsonPrimitive.content } +
            ("CLAUDE_CONFIG_DIR" to profile.toString())
        val command = control.getValue("command").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("--setting-sources", "user"), command.takeLast(2))
        // Remove the harness default so only the adapter can select its settings sources.
        val inherited = BoundedCliProcess(command.dropLast(2), workspace, environment, replaceEnvironment = true)
        val runtimeRoot = scratch.resolve("global state/mod-runtime")
        Files.createDirectories(runtimeRoot.parent)
        val process = BoundedCliProcess(command.dropLast(2), runtimeRoot.parent, environment, replaceEnvironment = true)
        val runtime = ClaudeModRuntime(runtimeRoot, process)
        val target = ModTarget()
        assertTrue(runtime.install(target, runtimeFixture()).enabled)
        val merged = Json.parseToJsonElement(inherited.run(listOf("plugin", "list", "--json")).stdout).jsonArray
        assertFalse(merged.single().jsonObject.getValue("enabled").jsonPrimitive.boolean)
        assertFalse(runtime.disable(target, "demo").enabled)
        assertTrue(runtime.enable(target, "demo").enabled)
        assertFalse(runtime.remove(target, "demo", runtime.previewRemove(target, "demo")).installed)
        assertEquals(projectSettings, Files.readString(workspace.resolve(".claude/settings.json")))
    }

    @Test fun `real CLI lifecycle and recovery preserve foreign state and persistent data`() {
        val controlPath = requireNotNull(System.getenv("RULEBLEND_MOD_GATE_CONTROL")) { "Run tools/claude_mod_runtime_gate.py" }
        val control = Json.parseToJsonElement(Files.readString(Path.of(controlPath))).jsonObject
        val command = control.getValue("command").jsonArray.map { it.jsonPrimitive.content }
        val env = control.getValue("environment").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val scratch = Path.of(control.getValue("root").jsonPrimitive.content)
        val root = scratch.resolve("runtime spaces Юникод")
        val process = BoundedCliProcess(command, scratch, env, replaceEnvironment = true)
        var failAfterDisable = false
        var failUpdate = false
        val boundary = CliProcess { args ->
            if (failUpdate && args.drop(2).take(2) == listOf("plugin", "update")) {
                // Exercise an actual CLI failure against a missing accepted source, then roll back.
                val catalog = root.resolve(".claude-plugin/marketplace.json")
                val valid = Files.readString(catalog)
                Files.writeString(catalog, valid.replace(Regex("\\./snapshots/[^\"]+"), "./snapshots/missing"))
                val failure = process.run(args)
                Files.writeString(catalog, valid)
                assertNotEquals(0, failure.exitCode)
                return@CliProcess failure
            }
            val result = process.run(args)
            if (failAfterDisable && args.drop(2).take(2) == listOf("plugin", "disable")) {
                assertEquals(0, result.exitCode)
                throw ModRuntimeException(ModRuntimeError.TIMEOUT, "Simulated lost CLI result")
            }
            result
        }
        fun adapter() = ClaudeModRuntime(root, boundary)
        val target = ModTarget()
        var runtime = adapter()
        assertEquals(ModRuntimeState.ABSENT, runtime.probe(target, "demo").state)
        val a = runtime.install(target, runtimeFixture())
        assertTrue(a.installed && a.enabled && a.reloadRequired)
        val companion = runtime.install(target, runtimeFixture("companion", "companion"))
        val oldPath = ModRuntimeStore(root).let { it.snapshotPath(it.load().records.getValue("demo")) }
        val oldBytes = Files.readAllBytes(oldPath.resolve("hooks/register.js"))
        val b = runtime.update(target, runtimeFixture(revision = "B"))
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertContentEquals(oldBytes, Files.readAllBytes(oldPath.resolve("hooks/register.js")))
        assertEquals(companion, runtime.probe(target, "companion"))
        assertEquals(b, runtime.update(target, runtimeFixture(revision = "B")))

        failUpdate = true
        assertEquals(ModRuntimeError.CLI_FAILED, assertFailsWith<ModRuntimeException> {
            runtime.update(target, runtimeFixture(revision = "C"))
        }.error)
        failUpdate = false
        runtime = adapter()
        assertEquals(b.fingerprint, runtime.recover(target).single { it.libraryId == "demo" }.fingerprint)

        failAfterDisable = true
        assertEquals(ModRuntimeError.TIMEOUT, assertFailsWith<ModRuntimeException> { runtime.disable(target, "demo") }.error)
        failAfterDisable = false
        runtime = adapter()
        assertFalse(runtime.recover(target).single { it.libraryId == "demo" }.enabled)
        assertTrue(runtime.enable(target, "demo").enabled)

        // Foreign CLI changes are detected and never overwritten by the adapter.
        assertEquals(0, process.run(listOf("plugin", "disable", "demo@ruleblend-mods", "--scope", "user", "--json")).exitCode)
        assertEquals(ModRuntimeState.DRIFT, runtime.probe(target, "demo").state)
        assertEquals(ModRuntimeError.DRIFT, assertFailsWith<ModRuntimeException> { runtime.previewRemove(target, "demo") }.error)
        assertEquals(0, process.run(listOf("plugin", "enable", "demo@ruleblend-mods", "--scope", "user", "--json")).exitCode)

        val profile = Path.of(env.getValue("CLAUDE_CONFIG_DIR"))
        val data = profile.resolve("plugins/data/demo-ruleblend-mods/sentinel")
        Files.createDirectories(data.parent)
        Files.writeString(data, "persistent fixture")
        // Only this isolated profile's fixture options are seeded. No real credentials are read.
        val settingsPath = profile.resolve("settings.json")
        val settings = Json.parseToJsonElement(Files.readString(settingsPath)).jsonObject
        val fixtureSettings = JsonObject(settings + mapOf(
            "fixtureSentinel" to JsonPrimitive("retain"),
            "pluginConfigs" to buildJsonObject { put("demo@ruleblend-mods", buildJsonObject {
                put("options", buildJsonObject { put("fixture", "local-only") })
            }) },
        ))
        Files.writeString(settingsPath, fixtureSettings.toString())
        val preview = runtime.previewRemove(target, "demo")
        assertTrue(preview.lastInstallation && preview.keepData)
        assertTrue(preview.consequences.any { "options and secrets" in it })
        assertFalse(runtime.remove(target, "demo", preview).installed)
        assertEquals("persistent fixture", Files.readString(data))
        val after = Json.parseToJsonElement(Files.readString(settingsPath)).jsonObject
        assertEquals("retain", after["fixtureSentinel"]?.jsonPrimitive?.content)
        assertFalse(after["pluginConfigs"]?.jsonObject?.containsKey("demo@ruleblend-mods") == true)
        assertEquals(companion, runtime.probe(target, "companion"))
        assertTrue(Files.exists(oldPath))
        val reinstalled = adapter().install(target, runtimeFixture(revision = "B"))
        assertTrue(reinstalled.installed && reinstalled.enabled)
        assertNull(ModRuntimeStore(root).load().pending)
        assertFalse(runtime.disable(target, "demo").enabled)
        assertFalse(runtime.update(target, runtimeFixture(revision = "C")).enabled)
        assertFalse(runtime.remove(target, "demo", runtime.previewRemove(target, "demo")).installed)
        assertFalse(runtime.remove(target, "companion", runtime.previewRemove(target, "companion")).installed)
        assertEquals(0, Json.parseToJsonElement(process.run(listOf("plugin", "list", "--json")).stdout).jsonArray.size)
        // Marketplace removal is intentionally never part of per-plugin uninstall.
        assertEquals(1, Json.parseToJsonElement(process.run(listOf("plugin", "marketplace", "list", "--json")).stdout).jsonArray.size)
    }

    @Test fun `two devices sync whole packages while real CLI runtime changes only explicitly`() {
        val control = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(
            System.getenv("RULEBLEND_MOD_GATE_CONTROL")) { "Run tools/claude_mod_runtime_gate.py" }))).jsonObject
        val scratch = Path.of(control.getValue("root").jsonPrimitive.content)
        val deviceA = scratch.resolve("device A Юникод")
        val deviceB = scratch.resolve("device B Юникод")
        val rootA = deviceA.resolve("library")
        val rootB = deviceB.resolve("library")
        val gitA = LibraryGit(rootA)
        var gitB = LibraryGit(rootB)
        val repoA = LibraryRepository(rootA, gitA).also { it.init() }
        var repoB = LibraryRepository(rootB, gitB).also { it.init() }
        val configA = ConfigStore(deviceA.resolve("config.json"))
        val configB = ConfigStore(deviceB.resolve("config.json"))
        val mutationsA = MutateLibrary(repoA, configA)
        val source = scratch.resolve("offline source")
        runtimeFixture().files.forEach { file ->
            val path = source.resolve(file.path)
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
            setSkillExecutable(path, file.executable)
        }
        val imports = ImportLibrary(repoA, LibraryArchive(rootA, repoA), configA)
        imports.applyMod(imports.previewMod(source))
        repoA.writeGroup(Group("mods", "Mods", modIds = listOf("demo")))
        val remote = scratch.resolve("transfer.git")
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
        val sync = RemoteGitConfig(remote.toUri().toString(), "main")
        gitA.sync(sync); gitB.sync(sync)
        val initial = requireNotNull(repoA.loadModSnapshot("demo"))
        assertEquals(initial, repoB.loadModSnapshot("demo"))
        assertEquals(listOf("demo"), repoB.loadGroup("mods")!!.modIds)

        val environment = control.getValue("environment").jsonObject.mapValues { it.value.jsonPrimitive.content } +
            ("CLAUDE_CONFIG_DIR" to deviceB.resolve("claude profile").toString())
        val process = BoundedCliProcess(control.getValue("command").jsonArray.map { it.jsonPrimitive.content },
            scratch, environment, replaceEnvironment = true)
        val runtimeRoot = deviceB.resolve("mod-runtime")
        var runtime = ClaudeModRuntime(runtimeRoot, process)
        fun lifecycle() = ModLifecycle(repoB, MutateLibrary(repoB, configB), runtime)
        assertEquals(ModRuntimeState.ABSENT, lifecycle().status("demo").state)

        val foreign = scratch.resolve("foreign marketplace")
        runtimeFixture().files.forEach { file ->
            val path = foreign.resolve("package").resolve(file.path)
            Files.createDirectories(path.parent)
            Files.write(path, file.bytes)
        }
        Files.createDirectories(foreign.resolve(".claude-plugin"))
        Files.writeString(foreign.resolve(".claude-plugin/marketplace.json"),
            """{"name":"foreign-fixture","owner":{"name":"Fixture"},"plugins":[{"name":"alias","source":"./package"}]}""")
        assertEquals(0, process.run(listOf("plugin", "marketplace", "add", foreign.toString(), "--scope", "user", "--json")).exitCode)
        assertEquals(0, process.run(listOf("plugin", "install", "alias@foreign-fixture", "--scope", "user", "--json")).exitCode)
        assertEquals(ModRuntimeState.FOREIGN, lifecycle().status("demo").state)
        assertEquals(ModRuntimeError.FOREIGN, assertFailsWith<ModRuntimeException> { lifecycle().install("demo") }.error)
        assertFalse(Files.exists(runtimeRoot.resolve("state.json")))
        assertEquals(0, process.run(listOf("plugin", "uninstall", "alias@foreign-fixture", "--scope", "user", "--keep-data", "--json")).exitCode)

        val installed = lifecycle().install("demo")
        val store = ModRuntimeStore(runtimeRoot)
        val initialPath = store.snapshotPath(store.load().records.getValue("demo"))
        val initialBytes = Files.readAllBytes(initialPath.resolve("hooks/register.js"))

        repoA.writeMod(runtimeFixture(revision = "B"))
        gitA.sync(sync); gitB.sync(sync)
        assertEquals(repoA.loadModSnapshot("demo"), repoB.loadModSnapshot("demo"))
        assertEquals(installed.fingerprint, lifecycle().status("demo").fingerprint)
        assertContentEquals(initialBytes, Files.readAllBytes(initialPath.resolve("hooks/register.js")))

        // Cold restart reconstructs both portable and local state without an implicit Apply.
        gitB = LibraryGit(rootB)
        repoB = LibraryRepository(rootB, gitB).also { it.init() }
        runtime = ClaudeModRuntime(runtimeRoot, process)
        assertEquals(installed, lifecycle().status("demo"))
        assertNotEquals(installed.fingerprint, lifecycle().update("demo").fingerprint)
        assertFalse(lifecycle().disable("demo").enabled)
        assertTrue(lifecycle().enable("demo").enabled)
        assertFalse(lifecycle().uninstall("demo", lifecycle().previewUninstall("demo")).installed)
        assertNotNull(repoA.loadMod("demo"))
        assertTrue(lifecycle().install("demo").enabled)

        // Whole-package merge conflicts also leave the accepted runtime revision untouched.
        val accepted = lifecycle().status("demo")
        repoA.writeMod(runtimeFixture(revision = "A conflict"))
        repoB.writeMod(runtimeFixture(revision = "B conflict"))
        gitA.sync(sync)
        assertEquals(listOf(RemoteSyncConflict("demo", RemoteSyncConflictKind.MOD)), gitB.sync(sync).conflicts)
        gitA.sync(sync)
        assertEquals(repoA.loadModSnapshot("demo"), repoB.loadModSnapshot("demo"))
        assertEquals(accepted, lifecycle().status("demo"))

        mutationsA.deleteMod("demo")
        gitA.sync(sync); gitB.sync(sync)
        assertNull(repoB.loadMod("demo"))
        assertTrue(repoB.loadGroup("mods")!!.modIds.isEmpty())
        assertEquals(accepted, lifecycle().status("demo"))
        assertEquals("demo", runtime.inventory(ModTarget()).single().libraryId)
        assertFalse(lifecycle().disable("demo").enabled)
        assertFalse(lifecycle().uninstall("demo", lifecycle().previewUninstall("demo")).installed)
        assertTrue(Files.exists(initialPath))
        assertFalse(Files.exists(deviceA.resolve("mod-runtime")))
        assertEquals(0, Json.parseToJsonElement(process.run(listOf("plugin", "list", "--json")).stdout).jsonArray.size)
    }
}
