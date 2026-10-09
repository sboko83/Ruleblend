package dev.ruleblend.core.integration

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launcher is a shell script written from a path the user chose and an argument line the user
 * typed, so quoting is the whole correctness story: a folder named with a space or a quote must end
 * up as the agent's working directory, not as two arguments or a broken command.
 */
class AgentCliTest {

    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-agent-cli")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test fun `the script runs the command through the user's login shell`() {
        val directory = Path.of("/Users/dev/ledger")
        val script = AgentLauncherScript.render(
            shell = "/bin/zsh",
            directory = directory,
            command = listOf("claude", "--continue"),
            interactive = false,
        )

        assertTrue(script.startsWith("#!/bin/sh\n"), script)
        assertTrue(script.contains("exec '/bin/zsh' -lc "), script)
        // The inner command is one single-quoted argument to the shell, so its own quotes are escaped.
        assertTrue(script.contains("""cd '\''$directory'\'' && exec '\''claude'\'' '\''--continue'\''"""), script)
    }

    @Test fun `an interactive probe produces an interactive launch`() {
        val script = AgentLauncherScript.render(
            shell = "/bin/zsh",
            directory = Path.of("/Users/dev/ledger"),
            command = listOf("codex"),
            interactive = true,
        )

        assertTrue(script.contains("exec '/bin/zsh' -ilc "), script)
    }

    @Test fun `a folder with a space and a quote in its name is still the working directory`() {
        val directory = root.resolve("my project's code").also { it.createDirectories() }
        val marker = directory.resolve("where.txt")
        val script = root.resolve("launch.command")
        val windows = File.separatorChar == '\\'
        // Git for Windows supplies the POSIX shell this launcher targets.
        val shell = if (windows) Path.of(System.getenv("ProgramFiles"), "Git", "bin", "sh.exe")
            else Path.of("/bin/sh")
        assertTrue(Files.isExecutable(shell), "The launcher test requires a POSIX shell: $shell")
        script.writeText(
            AgentLauncherScript.render(
                shell = shell.toString().replace('\\', '/'),
                directory = directory.fileName,
                // `pwd` stands in for the agent: what is asserted is where it was started.
                command = listOf("sh", "-c", "pwd ${if (windows) "-W " else ""}> where.txt"),
                interactive = false,
            ),
        )
        val process = ProcessBuilder(shell.toString(), script.toString())
            .directory(root.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        // `pwd` answers with the logical path; on macOS a temp directory is a symlink to /private.
        assertEquals(directory.toRealPath(), Path.of(Files.readString(marker).trim()).toRealPath())
    }

    @Test fun `a launcher is named after its project and stays stable for it`() {
        val first = AgentLauncherScript.fileName("claude-code", Path.of("/Users/dev/Ledger KMP"))
        val second = AgentLauncherScript.fileName("claude-code", Path.of("/Users/dev/Ledger KMP"))
        val elsewhere = AgentLauncherScript.fileName("claude-code", Path.of("/Users/dev/other/Ledger KMP"))

        assertEquals(first, second)
        assertTrue(first.startsWith("ledger-kmp-claude-code-"), first)
        assertTrue(first.endsWith(".command"), first)
        // Two checkouts of the same name are two launchers, so one click never opens the other one.
        assertTrue(first != elsewhere, "$first collides with $elsewhere")
    }

    @Test fun `a typed argument line is split the way a shell would split it`() {
        assertEquals(listOf("--model", "opus"), splitArguments("  --model   opus "))
        assertEquals(listOf("--append-system-prompt", "be brief"), splitArguments("""--append-system-prompt "be brief""""))
        assertEquals(listOf("--prompt", "it's fine"), splitArguments("--prompt 'it'\"'\"'s fine'"))
        assertEquals(emptyList(), splitArguments("   "))
        // An empty quoted string is an argument the user meant to pass.
        assertEquals(listOf("--prefix", ""), splitArguments("""--prefix """""))
    }

    @Test fun `resume uses the agent's own way back, and extra arguments come last`() {
        val claude = AgentCli("claude", resumeArguments = listOf("--continue"))

        assertEquals(listOf("claude", "--model", "opus"), agentCommand(claude, AgentLaunchMode.NEW, "--model opus"))
        assertEquals(listOf("claude", "--continue"), agentCommand(claude, AgentLaunchMode.RESUME, ""))
        // An agent with no resume of its own starts a new session rather than a broken command.
        assertEquals(listOf("pi"), agentCommand(AgentCli("pi"), AgentLaunchMode.RESUME, ""))
    }

    @Test fun `every shipped adapter that can be launched names an executable`() {
        val adapters = listOf(ClaudeCodeAdapter(), CodexAdapter(), PiAdapter(), KimiCodeAdapter(), ZCodeAdapter())

        adapters.forEach { adapter ->
            val cli = adapter.cli
            assertTrue(cli != null && cli.executable.isNotBlank(), "${adapter.id} has no CLI")
            assertTrue(cli.resumeArguments?.isEmpty() != true, "${adapter.id} has an empty resume")
        }
        assertNull(object : AgentAdapter {
            override val id = "none"
            override val name = "None"
            override fun isAvailable() = false
            override fun globalFile(): Path = root
            override fun projectFile(projectDir: Path): Path = projectDir
        }.cli)
    }
}
