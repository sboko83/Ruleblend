package dev.ruleblend.mcp

import java.nio.file.Files
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

    /** Stands in for the jpackage binary: prints what it was started with. */
    private val app = tempDir.resolve("Ruleblend.app/Contents/MacOS/Ruleblend").also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "#!/bin/sh\necho \"app:$*\"\n")
        it.toFile().setExecutable(true)
    }

    private fun installed() = McpLauncher(script, mainClass = "dev.ruleblend.app.MainKt", appPath = app.toString(), windows = false)

    private fun dev(classPath: String = System.getProperty("java.class.path"), workingDir: String = "/work/ruleblend") =
        McpLauncher(script, mainClass = LauncherProbe::class.java.name, appPath = null, classPath = classPath,
            workingDir = workingDir, windows = false)

    private fun run(launch: McpLaunch): String {
        val process = ProcessBuilder(listOf(launch.command.toString()) + launch.args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "script failed: $output")
        return output
    }

    @Test
    fun `installed app hands agents the shared script, which re-launches the bundle`() {
        val launch = installed().resolve()

        assertEquals(script, launch.command)
        assertFalse(launch.fromDevBuild)
        assertEquals(McpServing(McpServingBuild.INSTALLED, app.toString(), thisBuild = true), launch.serving)
        assertContains(run(launch), "app:--mcp")
    }

    @Test
    fun `a dev-owned script survives an installed start until the installed app claims it`() {
        dev().resolve()

        val started = installed().resolve()
        assertEquals(McpServing(McpServingBuild.DEV, "/work/ruleblend", thisBuild = false), started.serving)
        assertContains(run(started), "probe:--mcp")

        val claimed = installed().claim()
        assertEquals(started.command, claimed.command, "Switching builds must not change what agents hold")
        assertEquals(McpServing(McpServingBuild.INSTALLED, app.toString(), thisBuild = true), claimed.serving)
        assertContains(run(claimed), "app:--mcp")
    }

    @Test
    fun `an installed-owned script survives a dev start`() {
        installed().resolve()

        val started = dev().resolve()
        assertEquals(McpServing(McpServingBuild.INSTALLED, app.toString(), thisBuild = false), started.serving)
        assertContains(run(started), "app:--mcp")
    }

    @Test
    fun `a dev-owned script follows every dev start`() {
        dev().resolve()

        val rebuilt = dev(workingDir = "/work/worktree").resolve()
        assertEquals(McpServing(McpServingBuild.DEV, "/work/worktree", thisBuild = true), rebuilt.serving)
    }

    @Test
    fun `a dev build whose files vanished is taken over on the next installed start`() {
        val gone = tempDir.resolve("gone.jar").also { Files.writeString(it, "") }
        dev(classPath = "$gone${java.io.File.pathSeparator}${System.getProperty("java.class.path")}").resolve()
        Files.delete(gone)

        assertEquals(McpServingBuild.INSTALLED, installed().resolve().serving?.build)
    }

    @Test
    fun `a script written before ownership existed is taken over`() {
        Files.createDirectories(script.parent)
        Files.writeString(script, "#!/bin/sh\nexec '/old/java' -cp '/old' Main \"$@\"\n")

        assertEquals(McpServing(McpServingBuild.INSTALLED, app.toString(), thisBuild = true), installed().resolve().serving)
    }

    @Test
    fun `dev run writes an executable script that re-launches this build with the arguments`() {
        val launch = dev().resolve()

        assertTrue(launch.fromDevBuild)
        assertTrue(launch.command.isExecutable())
        assertEquals(McpServing(McpServingBuild.DEV, "/work/ruleblend", thisBuild = true), launch.serving)
        assertContains(run(launch), "probe:--mcp")
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
