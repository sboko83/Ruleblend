package dev.ruleblend.mcp

import dev.ruleblend.mcp.skill.BundledSkill
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git

/** Runs in a child JVM; the server branch exercises the real stdio transport. */
object McpProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if ("--server" in args) runRuleblendServer("test", Path.of(args.last()))
        else println("probe:" + args.joinToString("|"))
    }
}

class McpLaunchProcessTest {
    private val tempDir = Files.createTempDirectory("ruleblend Пробел & % # ' ")
    private val script = tempDir.resolve("bin/ruleblend-mcp")

    @AfterTest
    fun cleanUp() { tempDir.toFile().deleteRecursively() }

    @Test
    fun `dev launcher forwards arguments and loads classes from Unicode paths`() {
        val classes = tempDir.resolve("Классы приложения")
        val name = McpProcessProbe::class.java.name.replace('.', '/') + ".class"
        val target = classes.resolve(name)
        Files.createDirectories(target.parent)
        McpProcessProbe::class.java.classLoader.getResourceAsStream(name)!!.use { Files.copy(it, target) }
        val classpath = (listOf(classes.toString(), System.getProperty("java.class.path")) +
            List(500) { tempDir.resolve("nonexistent-library-$it.jar").toString() }).joinToString(File.pathSeparator)
        val launch = McpLauncher(script, McpProcessProbe::class.java.name, appPath = null, classPath = classpath).resolve()
        assertTrue(launch.fromDevBuild)
        if (System.getProperty("os.name").startsWith("Windows")) {
            assertEquals("ruleblend-mcp.exe", launch.command.fileName.toString())
        } else assertTrue(Files.isExecutable(script))
        val arguments = listOf("путь с пробелом", "quote'inside", "dollar${'$'} & % !", "trailing\\")
        runProcess(listOf(launch.command.toString()) + launch.args + arguments) { process ->
            assertEquals("probe:" + (listOf("--mcp") + arguments).joinToString("|"),
                process.inputStream.bufferedReader(Charsets.UTF_8).readLine())
        }
    }

    @Test
    fun `launcher preparation failure is propagated`() {
        val file = tempDir.resolve("file")
        Files.writeString(file, "not a directory")
        assertFailsWith<Exception> {
            McpLauncher(file.resolve("launcher"), McpProcessProbe::class.java.name, appPath = null).resolve()
        }
    }

    @Test
    fun `dev MCP initializes lists and calls tools and exits on EOF with clean stdout`() {
        val upstream = tempDir.resolve("Git source")
        Files.createDirectories(upstream.resolve("skills/review"))
        Files.writeString(upstream.resolve("skills/review/SKILL.md"), "# Review\n")
        Files.createDirectories(upstream.resolve(".codex/agents"))
        Files.writeString(upstream.resolve(".codex/agents/reviewer.toml"),
            "name = \"reviewer\"\ndescription = \"Review code\"\ndeveloper_instructions = \"Review carefully.\"\n")
        Git.init().setDirectory(upstream.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Initial definitions").setAuthor("Test", "test@example.com").call()
        }
        val launcher = McpLauncher(script, McpProcessProbe::class.java.name, appPath = null)
        val launch = launcher.resolve()
        val home = tempDir.resolve("Изолированный home")
        Files.createDirectories(home)
        val command = listOf(launch.command.toString()) + launch.args +
            listOf("--server", home.resolve("library").toString())
        runProcess(command, home) { process ->
            val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
            writer.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""" + "\n")
            writer.flush()
            val initialized = Json.parseToJsonElement(reader.readLine()).jsonObject
            assertEquals("1", initialized["id"]?.jsonPrimitive?.content)
            assertTrue("serverInfo" in initialized["result"]!!.jsonObject)
            assertEquals(launch, launcher.resolve(), "Preparing another app launch must not replace a running executable")
            assertEquals(launch, McpLauncher(script, McpProcessProbe::class.java.name, appPath = null,
                classPath = System.getProperty("java.class.path") + File.pathSeparator + tempDir.resolve("new.jar")).resolve())
            writer.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
            writer.write("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""" + "\n")
            writer.flush()
            val catalog = Json.parseToJsonElement(reader.readLine()).jsonObject
            assertEquals("2", catalog["id"]?.jsonPrimitive?.content)
            val registered = catalog["result"]!!.jsonObject["tools"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            val documented = Regex("`([a-z_]+)`")
                .findAll(BundledSkill.text.substringAfter("## Tools").substringBefore("\n## "))
                .map { it.groupValues[1] }.toSet()
            assertEquals(registered, documented, "The bundled skill catalog must match the public tools/list contract")
            writer.write("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"list_rules","arguments":{}}}""" + "\n")
            writer.flush()
            val response = Json.parseToJsonElement(reader.readLine()).jsonObject
            assertEquals("3", response["id"]?.jsonPrimitive?.content)
            assertTrue("content" in response["result"]!!.jsonObject)
            assertFalse(response["result"]!!.jsonObject["isError"]?.jsonPrimitive?.content == "true")

            fun call(id: Int, name: String, arguments: JsonObject = JsonObject(emptyMap()), error: Boolean = false): JsonElement {
                writer.write(buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", "tools/call")
                    put("params", buildJsonObject { put("name", name); put("arguments", arguments) })
                }.toString() + "\n")
                writer.flush()
                val reply = Json.parseToJsonElement(reader.readLine()).jsonObject
                assertEquals(id.toString(), reply["id"]?.jsonPrimitive?.content)
                val result = reply["result"]!!.jsonObject
                assertEquals(error, result["isError"]?.jsonPrimitive?.content == "true", result.toString())
                val text = result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                return if (error) JsonPrimitive(text) else Json.parseToJsonElement(text)
            }

            val preview = call(4, "preview_git_import", buildJsonObject { put("repository", upstream.toUri().toString()) }).jsonObject
            val previewId = preview["preview_id"]!!
            val entry = call(5, "get_git_import_entry", buildJsonObject {
                put("preview_id", previewId); put("kind", "subagent"); put("path", ".codex/agents/reviewer.toml")
            }).jsonObject
            assertTrue(entry["source_text"]!!.jsonPrimitive.content.contains("Review carefully."))
            val imported = call(6, "apply_git_import", buildJsonObject {
                put("preview_id", previewId)
                put("skill_paths", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("skills/review"))))
                put("subagents", buildJsonObject { put(".codex/agents/reviewer.toml", "codex") })
            }).jsonObject
            val skillId = imported["skills"]!!.jsonArray.single().jsonObject["id"]!!
            val agentId = imported["subagents"]!!.jsonArray.single().jsonObject["id"]!!
            assertEquals(1, call(7, "list_sources").jsonArray.size)
            assertEquals("current", call(8, "check_sources").jsonObject["subagents"]!!.jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)
            assertTrue(call(9, "fork_skill", buildJsonObject { put("id", skillId) }).jsonObject["editable"]!!.jsonPrimitive.content == "true")
            assertTrue(call(10, "fork_subagent", buildJsonObject { put("id", agentId) }).jsonObject["editable"]!!.jsonPrimitive.content == "true")
            call(11, "update_from_sources", buildJsonObject {
                put("skill_ids", kotlinx.serialization.json.JsonArray(listOf(skillId)))
                put("subagent_ids", kotlinx.serialization.json.JsonArray(listOf(agentId)))
            })
            assertTrue(call(12, "apply_git_import", buildJsonObject { put("preview_id", "unknown") }, error = true)
                .jsonPrimitive.content.contains("expired preview"))
            assertTrue(call(13, "list_skill_files", buildJsonObject { put("id", skillId) })
                .jsonObject["files"]!!.jsonArray.any { it.jsonObject["path"]!!.jsonPrimitive.content == "SKILL.md" })
            assertTrue(call(14, "read_skill_file", buildJsonObject { put("id", skillId); put("path", "SKILL.md") })
                .jsonObject["content"]!!.jsonPrimitive.content.contains("Review"))
            call(15, "write_skill_file", buildJsonObject {
                put("id", skillId); put("path", "run.sh"); put("content", "echo bad")
            }, error = true)
            val fork = call(16, "fork_skill", buildJsonObject { put("id", skillId) }).jsonObject["id"]!!
            call(17, "write_skill_file", buildJsonObject {
                put("id", fork); put("path", "assets/image.bin"); put("encoding", "base64"); put("content", "AAH/")
                put("executable", true)
            })
            val file = call(18, "read_skill_file", buildJsonObject {
                put("id", fork); put("path", "assets/image.bin"); put("encoding", "base64")
            }).jsonObject
            assertEquals("AAH/", file["content"]!!.jsonPrimitive.content)
            assertEquals("true", file["executable"]!!.jsonPrimitive.content)
            call(19, "delete_skill_file", buildJsonObject { put("id", fork); put("path", "assets/image.bin") })
            call(20, "read_skill_file", buildJsonObject { put("id", fork); put("path", "assets/image.bin") }, error = true)
            writer.close()
            assertEquals("", reader.readText())
        }
    }

    private fun runProcess(command: List<String>, home: Path? = null, check: (Process) -> Unit) {
        val stderr = tempDir.resolve("stderr.log")
        val builder = ProcessBuilder(command).redirectError(stderr.toFile())
        if (home != null) {
            builder.environment()["JAVA_TOOL_OPTIONS"] = "-Duser.home=\"$home\""
            builder.environment().remove("RULEBLEND_LIBRARY")
        }
        val process = builder.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { check(process) }.get(30, TimeUnit.SECONDS)
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "child did not exit")
            assertEquals(0, process.exitValue(), stderr.readText())
        } catch (failure: Throwable) {
            throw AssertionError("Child process failed: ${stderr.readText()}", failure)
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
