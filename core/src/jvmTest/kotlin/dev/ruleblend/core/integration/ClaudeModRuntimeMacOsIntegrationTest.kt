package dev.ruleblend.core.integration

import dev.ruleblend.core.process.BoundedCliProcess
import dev.ruleblend.core.process.CliProcess
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.test.*

/** Invoked only by tools/claude_mod_runtime_gate.py after OS isolation is proven. */
class ClaudeModRuntimeMacOsIntegrationTest {
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
            if (failUpdate && args.take(2) == listOf("plugin", "update")) {
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
            if (failAfterDisable && args.take(2) == listOf("plugin", "disable")) {
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
        assertFalse(runtime.remove(target, "companion", runtime.previewRemove(target, "companion")).installed)
        assertEquals(0, Json.parseToJsonElement(process.run(listOf("plugin", "list", "--json")).stdout).jsonArray.size)
        // Marketplace removal is intentionally never part of per-plugin uninstall.
        assertEquals(1, Json.parseToJsonElement(process.run(listOf("plugin", "marketplace", "list", "--json")).stdout).jsonArray.size)
    }
}
