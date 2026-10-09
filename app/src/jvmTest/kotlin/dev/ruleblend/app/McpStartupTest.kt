package dev.ruleblend.app

import dev.ruleblend.mcp.McpLauncher
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class McpStartupTest {
    @Test
    fun `IDE launcher starts the actual app as a headless MCP server`() {
        val root = Files.createTempDirectory("ruleblend-ide-mcp")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val launch = McpLauncher(root.resolve("Команда с пробелами/ruleblend-mcp"),
                "dev.ruleblend.app.MainKt", appPath = null).resolve()
            val home = Files.createDirectories(root.resolve("Пользователь"))
            val stderr = root.resolve("stderr.log")
            val builder = ProcessBuilder(listOf(launch.command.toString()) + launch.args +
                listOf("--library", home.resolve("Библиотека правил").toString())).redirectError(stderr.toFile())
            builder.environment().apply {
                remove("JDK_JAVA_OPTIONS")
                remove("_JAVA_OPTIONS")
                remove("RULEBLEND_LIBRARY")
                put("JAVA_TOOL_OPTIONS", "-Duser.home=\"$home\" -Djava.awt.headless=true")
            }
            val process = builder.start()
            try {
                executor.submit {
                    val input = process.inputStream.bufferedReader(Charsets.UTF_8)
                    val output = process.outputStream.bufferedWriter(Charsets.UTF_8)
                    output.write("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""" + "\n")
                    output.flush()
                    val initialized = Json.parseToJsonElement(input.readLine()).jsonObject
                    assertEquals("ruleblend", initialized["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
                    output.write("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
                    output.write("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_rules","arguments":{}}}""" + "\n")
                    output.flush()
                    val response = Json.parseToJsonElement(input.readLine()).jsonObject
                    assertEquals("2", response["id"]!!.jsonPrimitive.content)
                    val result = response["result"]!!.jsonObject
                    assertTrue("content" in result)
                    assertFalse(result["isError"]?.jsonPrimitive?.content == "true")
                    output.close()
                    assertEquals("", input.readText())
                }.get(30, TimeUnit.SECONDS)
                assertTrue(process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), Files.readString(stderr))
                assertTrue(Files.isDirectory(home.resolve(".ruleblend")))
            } catch (failure: Throwable) {
                throw AssertionError("IDE MCP failed: ${Files.readString(stderr)}", failure)
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        } finally {
            executor.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }
}
