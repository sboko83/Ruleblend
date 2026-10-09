package dev.ruleblend.app.util

import dev.ruleblend.core.process.WindowsExecutables
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class WindowsLaunchTest {
    private val root = Files.createTempDirectory("rb-launch")
    @AfterTest fun clean() { root.toFile().deleteRecursively() }

    private fun resolver(): WindowsExecutables = WindowsExecutables(mapOf("PATH" to root.toString()), root)
    private fun launch(terminal: Path? = null): CliLaunch {
        if (!Files.exists(root.resolve("agent.exe"))) Files.createFile(root.resolve("agent.exe"))
        return CliLaunch(root, "agent", root, listOf("agent", "resume", "--last", "a; b"), false, terminal)
    }
    private fun decode(command: List<String>): String = String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)

    @Test fun `automatic terminal receives an encoded payload and failure opens the fallback console`() {
        Files.createFile(root.resolve("wt.exe"))
        val attempts = mutableListOf<List<String>>()
        launchWindowsAgent(launch(), resolver()) { command ->
            attempts += command
            if (attempts.size == 1) error("Alias unavailable")
        }
        assertEquals(2, attempts.size)
        assertContains(decode(attempts[0]), "new-tab")
        assertContains(decode(attempts[1]), "UseShellExecute = \$true")
        assertContains(decode(attempts[1]), "powershell.exe")
    }

    @Test fun `an explicit terminal is honored without silently changing the choice`() {
        val terminal = Files.createFile(root.resolve("pwsh.exe"))
        val attempts = mutableListOf<List<String>>()
        launchWindowsAgent(launch(terminal), resolver()) { attempts += it }
        assertEquals(1, attempts.size)
        assertContains(decode(attempts.single()), "pwsh.exe")
        assertFailsWith<IllegalStateException> { launchWindowsAgent(launch(terminal), resolver()) { error("Failed") } }
    }

    @Test fun `missing executable and missing project fail before terminal startup`() {
        val request = launch()
        Files.delete(root.resolve("agent.exe"))
        assertFailsWith<IllegalStateException> { launchWindowsAgent(request, resolver()) { error("Unexpected start") } }
        assertFailsWith<IllegalArgumentException> {
            launchWindowsAgent(request.copy(directory = root.resolve("missing")), resolver()) { error("Unexpected start") }
        }
    }
}
