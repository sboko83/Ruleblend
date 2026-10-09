package dev.ruleblend.mcp

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isExecutable
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Re-launched by the generated script, to prove it starts a JVM and forwards its arguments. */
object LauncherProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("probe:" + args.joinToString(","))
    }
}

class McpLauncherTest {

    private val tempDir = Files.createTempDirectory("ruleblend-launcher")
    private val script = tempDir.resolve("bin").resolve("ruleblend-mcp")

    @AfterTest
    fun cleanUp() { tempDir.toFile().deleteRecursively() }

    @Test
    fun `installed app hands over the jpackage binary and writes no script`() {
        val launch = McpLauncher(script, mainClass = "dev.ruleblend.app.MainKt", appPath = "/Applications/Ruleblend.app/Contents/MacOS/Ruleblend").resolve()

        assertEquals(java.nio.file.Path.of("/Applications/Ruleblend.app/Contents/MacOS/Ruleblend"), launch.command)
        assertFalse(launch.fromDevBuild)
        assertFalse(script.exists())
    }

    @Test
    fun `dev run writes an executable script that re-launches this build with the arguments`() {
        val launch = McpLauncher(script, mainClass = LauncherProbe::class.java.name, appPath = null).resolve()

        assertTrue(launch.fromDevBuild)
        assertTrue(launch.command.isExecutable())

        val process = ProcessBuilder(listOf(launch.command.toString()) + launch.args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "script failed: $output")
        assertContains(output, "probe:--mcp")
    }

    @Test
    fun `a classpath entry with a quote or space stays one argument`() {
        val awkward = "/tmp/dir with space/a'b.jar"
        val launch = McpLauncher(
            script,
            mainClass = LauncherProbe::class.java.name,
            appPath = null,
            classPath = "$awkward${java.io.File.pathSeparator}${System.getProperty("java.class.path")}",
        ).resolve()

        val process = ProcessBuilder(listOf(launch.command.toString()) + launch.args.dropLast(1) + "x").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "script failed: $output")
        assertContains(output, "probe:x")
    }
}
