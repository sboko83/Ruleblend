package dev.ruleblend.core.integration

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.process.CliProcess
import dev.ruleblend.core.process.CliProcessResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun runtimeFixture(id: String = "demo", name: String = "demo", revision: String = "A") = ModSnapshot(
    Mod(id, name, name, "unchanged-version"), listOf(
        SkillFile(".claude-plugin/plugin.json", """{"name":"$name","version":"unchanged-version"}""".encodeToByteArray()),
        SkillFile("hooks/hooks.json", """{"modules":["./register.js"]}""".encodeToByteArray()),
        SkillFile("hooks/register.js", "export function register(on) {} // $revision\r\n".encodeToByteArray()),
        SkillFile("bin/tool", "fixture-$revision\n".encodeToByteArray(), executable = true),
    ))

internal class FakeModCli(private val root: Path) : CliProcess {
    val calls = mutableListOf<List<String>>()
    var marketplace: JsonObject? = null
    val plugins = mutableListOf<JsonObject>()
    var version = "2.1.287 (Claude Code)"
    var beforeCommand: ((List<String>) -> Unit)? = null
    var afterCommand: ((List<String>) -> Unit)? = null
    var failCommand: String? = null
    var suppressEffect: String? = null

    fun plugin(id: String = "demo@ruleblend-mods", scope: String = "user", enabled: Boolean = true) = buildJsonObject {
        put("id", id); put("scope", scope); put("enabled", enabled)
        put("installPath", "stale-cache-path")
    }

    override fun run(arguments: List<String>): CliProcessResult {
        calls += arguments
        if (arguments == listOf("--version")) return CliProcessResult(0, version)
        if (arguments.last() == "--help") return CliProcessResult(0, "--scope --json --keep-data")
        val args = arguments.drop(1)
        if (args == listOf("list", "--json")) return CliProcessResult(0, JsonArray(plugins).toString())
        if (args == listOf("marketplace", "list", "--json")) return CliProcessResult(0, JsonArray(listOfNotNull(marketplace)).toString())
        beforeCommand?.invoke(args)
        if (failCommand == args.first()) return CliProcessResult(1, """{"outcome":"failed"}""")
        if (suppressEffect != args.first()) when (args.first()) {
            "marketplace" -> marketplace = buildJsonObject {
                put("name", "ruleblend-mods"); put("source", "directory")
                put("path", root.toString()); put("installLocation", root.toString()); put("autoUpdate", false)
            }
            "install" -> plugins += plugin(args[1])
            "update" -> Unit // Equal upstream version: cached installPath remains stale.
            "enable", "disable" -> {
                val index = plugins.indexOfFirst { it["id"]?.jsonPrimitive?.content == args[1] && it["scope"]?.jsonPrimitive?.content == "user" }
                plugins[index] = plugin(args[1], enabled = args.first() == "enable")
            }
            "uninstall" -> plugins.removeAll { it["id"]?.jsonPrimitive?.content == args[1] && it["scope"]?.jsonPrimitive?.content == "user" }
            else -> error("Unexpected command: $arguments")
        }
        afterCommand?.invoke(args)
        return CliProcessResult(0, "progress\n" + """{"outcome":"ok","updateOutcome":"up_to_date"}""")
    }
}

class ClaudeModRuntimeTest {
    private val scratch = Files.createTempDirectory("ruleblend-runtime-test").toRealPath()
    private val root = scratch.resolve("runtime")
    private val cli = FakeModCli(root)
    private val target = ModTarget()
    private fun adapter() = ClaudeModRuntime(root, cli)
    private val runtime = adapter()
    private val store = ModRuntimeStore(root)
    @AfterTest fun cleanup() = deleteFixtureTree(scratch)

    private fun error(expected: ModRuntimeError, action: () -> Unit) {
        assertEquals(expected, assertFailsWith<ModRuntimeException> { action() }.error)
    }

    @Test fun `unsupported assistant and scope perform no process or filesystem writes`() {
        assertEquals(ModRuntimeState.UNSUPPORTED, runtime.probe(ModTarget("codex"), "demo").state)
        for (unsupported in listOf(ModTarget("codex"), ModTarget(scope = "managed"), ModTarget(scope = "project"))) {
            error(ModRuntimeError.UNSUPPORTED) { runtime.install(unsupported, runtimeFixture()) }
            error(ModRuntimeError.UNSUPPORTED) { runtime.disable(unsupported, "demo") }
            error(ModRuntimeError.UNSUPPORTED) { runtime.recover(unsupported) }
        }
        assertTrue(cli.calls.isEmpty()); assertFalse(Files.exists(root))
    }

    @Test fun `install update and repeated apply preserve immutable trees and companions`() {
        val a = runtime.install(target, runtimeFixture())
        assertEquals(ModRuntimeState.OWNED, a.state); assertTrue(a.reloadRequired)
        val companion = runtime.install(target, runtimeFixture("companion", "companion"))
        val oldPath = store.snapshotPath(store.load().records.getValue("demo"))
        val oldBytes = Files.readAllBytes(oldPath.resolve("hooks/register.js"))
        val b = runtime.update(target, runtimeFixture(revision = "B"))
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertContentEquals(oldBytes, Files.readAllBytes(oldPath.resolve("hooks/register.js")))
        assertEquals(companion, runtime.probe(target, "companion"))
        val mutations = cli.calls.count { it.getOrNull(1) in listOf("install", "update") }
        assertEquals(b, runtime.update(target, runtimeFixture(revision = "B")))
        assertEquals(mutations, cli.calls.count { it.getOrNull(1) in listOf("install", "update") })
        assertNull(store.load().pending)
    }

    @Test fun `toggle and uninstall require actual confirmation and retain snapshots`() {
        runtime.install(target, runtimeFixture())
        assertFalse(runtime.disable(target, "demo").enabled)
        val stale = runtime.previewRemove(target, "demo")
        runtime.enable(target, "demo")
        error(ModRuntimeError.CONFIRMATION_REQUIRED) { runtime.remove(target, "demo", stale) }
        val preview = runtime.previewRemove(target, "demo")
        assertTrue(preview.lastInstallation); assertTrue(preview.consequences.any { "options and secrets" in it })
        val result = runtime.remove(target, "demo", preview)
        assertFalse(result.installed); assertTrue(Files.exists(store.snapshotPath(store.load().records.getValue("demo"))))
        assertEquals(result, runtime.remove(target, "demo", runtime.previewRemove(target, "demo")))
        val removal = cli.calls.single { it.getOrNull(1) == "uninstall" && it.last() == "--json" }
        assertEquals(listOf("plugin", "uninstall", "demo@ruleblend-mods", "--scope", "user", "--keep-data", "--json"), removal)
        assertTrue(cli.calls.none { "--prune" in it || "remove" in it })
    }

    @Test fun `last uninstall preview accounts for other scopes without reading secrets`() {
        runtime.install(target, runtimeFixture())
        cli.plugins += cli.plugin(scope = "project")
        val preview = runtime.previewRemove(target, "demo")
        assertFalse(preview.lastInstallation)
        runtime.remove(target, "demo", preview)
        assertEquals("project", cli.plugins.single()["scope"]?.jsonPrimitive?.content)
    }

    @Test fun `foreign builtin and same-name origins cannot be claimed`() {
        for (id in listOf("demo@skills-dir", "demo@inline", "demo@synced", "demo@another", "demo@ruleblend-mods")) {
            cli.plugins.clear(); cli.plugins += cli.plugin(id)
            error(ModRuntimeError.FOREIGN) { runtime.install(target, runtimeFixture()) }
            assertFalse(Files.exists(store.stateFile))
        }
    }

    @Test fun `foreign probe resolves manifest identity independently of library id`() {
        cli.plugins += cli.plugin("demo@skills-dir")
        val status = runtime.probe(target, "imported-library-id", "demo")
        assertEquals(ModRuntimeState.FOREIGN, status.state)
        assertEquals("demo@skills-dir", status.pluginId)
        assertTrue(status.installed)
    }

    @Test fun `recovery permits retry after failed initial install without removing its marketplace`() {
        cli.failCommand = "install"
        error(ModRuntimeError.CLI_FAILED) { runtime.install(target, runtimeFixture()) }
        cli.failCommand = null
        val recovered = adapter()
        assertTrue(recovered.recover(target).isEmpty())
        assertTrue(recovered.install(target, runtimeFixture()).installed)
        assertTrue(cli.calls.none { it.take(3) == listOf("plugin", "marketplace", "remove") })
    }

    @Test fun `duplicate manifest names are rejected without changing catalogue`() {
        runtime.install(target, runtimeFixture())
        val before = store.text(store.catalogFile)
        error(ModRuntimeError.FOREIGN) { runtime.install(target, runtimeFixture(id = "other")) }
        assertEquals(before, store.text(store.catalogFile))
    }

    @Test fun `foreign marketplace matching the path is not ownership`() {
        cli.marketplace = buildJsonObject {
            put("name", "ruleblend-mods"); put("source", "directory"); put("path", root.toString()); put("installLocation", root.toString())
        }
        error(ModRuntimeError.FOREIGN) { runtime.install(target, runtimeFixture()) }
        assertFalse(Files.exists(store.stateFile))
    }

    @Test fun `sidecar loss and corruption cannot cause an uninstall`() {
        runtime.install(target, runtimeFixture())
        Files.delete(store.stateFile)
        error(ModRuntimeError.FOREIGN) { adapter().previewRemove(target, "demo") }
        Files.writeString(store.stateFile, "broken")
        error(ModRuntimeError.INVALID_STATE) { adapter().disable(target, "demo") }
        assertEquals(1, cli.plugins.size)
    }

    @Test fun `external enabled drift refuses mutations`() {
        runtime.install(target, runtimeFixture())
        cli.plugins[0] = cli.plugin(enabled = false)
        assertEquals(ModRuntimeState.DRIFT, runtime.probe(target, "demo").state)
        error(ModRuntimeError.DRIFT) { runtime.enable(target, "demo") }
        error(ModRuntimeError.DRIFT) { runtime.previewRemove(target, "demo") }
    }

    @Test fun `snapshot and catalogue drift refuse overwrite and deletion`() {
        runtime.install(target, runtimeFixture())
        val path = store.snapshotPath(store.load().records.getValue("demo"))
        Files.writeString(path.resolve("hooks/register.js"), "foreign")
        error(ModRuntimeError.DRIFT) { runtime.update(target, runtimeFixture(revision = "B")) }
        Files.writeString(store.catalogFile, "{}")
        error(ModRuntimeError.DRIFT) { runtime.disable(target, "demo") }
        assertEquals("foreign", Files.readString(path.resolve("hooks/register.js")))
    }

    @Test fun `failed update restores only its catalogue and recovery rechecks actual state`() {
        runtime.install(target, runtimeFixture())
        val before = store.text(store.catalogFile)
        cli.failCommand = "update"
        error(ModRuntimeError.CLI_FAILED) { runtime.update(target, runtimeFixture(revision = "B")) }
        assertEquals(before, store.text(store.catalogFile)); assertNotNull(store.load().pending)
        error(ModRuntimeError.PENDING) { runtime.disable(target, "demo") }
        assertEquals(ModRuntimeState.OWNED, adapter().recover(target).single().state)
        assertNull(store.load().pending)
    }

    @Test fun `successful exit without CLI effect stays pending`() {
        cli.suppressEffect = "install"
        error(ModRuntimeError.PENDING) { runtime.install(target, runtimeFixture()) }
        assertNotNull(store.load().pending)
        assertTrue(adapter().recover(target).isEmpty())
        assertNull(store.load().pending)
    }

    @Test fun `interrupted post-CLI confirmation reconciles after restart`() {
        runtime.install(target, runtimeFixture())
        cli.afterCommand = { args -> if (args.first() == "disable") throw ModRuntimeException(ModRuntimeError.CANCELLED, "Fixture cancellation") }
        error(ModRuntimeError.CANCELLED) { runtime.disable(target, "demo") }
        cli.afterCommand = null
        val recovered = adapter().recover(target).single()
        assertFalse(recovered.enabled); assertEquals(ModRuntimeState.OWNED, recovered.state)
        assertNull(store.load().pending)
    }

    @Test fun `restart after publication and CLI success confirms journaled revision`() {
        runtime.install(target, runtimeFixture())
        val journal = store.load()
        val before = journal.records.getValue("demo")
        val b = runtimeFixture(revision = "B")
        val desired = before.copy(fingerprint = dev.ruleblend.core.storage.modTreeFingerprint(b.files))
        store.materialize(b, desired)
        val after = journal.catalog!!.replace(before.fingerprint, desired.fingerprint)
        store.save(journal.copy(pending = PendingMod("update", before, desired, journal.catalog, after)))
        store.publish(journal.catalog, after)
        assertEquals(desired.fingerprint, adapter().recover(target).single().fingerprint)
    }

    @Test fun `partial install failure retains pending and never rolls back a foreign CLI entry`() {
        cli.afterCommand = { args -> if (args.first() == "install") throw ModRuntimeException(ModRuntimeError.TIMEOUT, "Fixture timeout") }
        error(ModRuntimeError.TIMEOUT) { runtime.install(target, runtimeFixture()) }
        assertNotNull(store.load().pending); assertEquals(1, cli.plugins.size)
        cli.afterCommand = null
        assertEquals(ModRuntimeState.PENDING, adapter().recover(target).single().state)
        assertTrue(cli.calls.none { it.getOrNull(1) == "uninstall" && it.last() == "--json" })
    }

    @Test fun `foreign catalogue edit during failed CLI is never overwritten`() {
        runtime.install(target, runtimeFixture())
        cli.beforeCommand = { args -> if (args.first() == "update") {
            Files.writeString(store.catalogFile, "foreign catalogue")
            throw ModRuntimeException(ModRuntimeError.CLI_FAILED, "Fixture failure")
        } }
        error(ModRuntimeError.CLI_FAILED) { runtime.update(target, runtimeFixture(revision = "B")) }
        assertEquals("foreign catalogue", store.text(store.catalogFile))
        cli.beforeCommand = null
        assertEquals(ModRuntimeState.PENDING, adapter().recover(target).single().state)
    }

    @Test fun `old CLI and absent required flags refuse writes`() {
        cli.version = "2.1.286"
        error(ModRuntimeError.INCOMPATIBLE) { runtime.install(target, runtimeFixture()) }
        assertFalse(Files.exists(store.stateFile))
        val noFlags = ClaudeModRuntime(root, CliProcess { args -> CliProcessResult(0, if (args == listOf("--version")) "2.1.287" else "--json") })
        error(ModRuntimeError.INCOMPATIBLE) { noFlags.install(target, runtimeFixture()) }
    }

    @Test fun `symbolic link runtime paths are rejected before CLI lookup`() {
        val outside = Files.createDirectory(scratch.resolve("outside"))
        Files.createSymbolicLink(root, outside)
        error(ModRuntimeError.INVALID_STATE) { runtime.install(target, runtimeFixture()) }
        assertTrue(cli.calls.isEmpty()); assertEquals(0L, Files.list(outside).use { it.count() })
    }
}
