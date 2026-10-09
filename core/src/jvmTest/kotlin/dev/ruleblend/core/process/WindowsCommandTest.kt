package dev.ruleblend.core.process

import dev.ruleblend.core.integration.AgentCli
import dev.ruleblend.core.integration.AgentLaunchMode
import dev.ruleblend.core.integration.agentCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WindowsCommandTest {
    @Test fun `native and batch launches preserve cwd session arguments and hostile literals`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("rb запуск & %PATH% ! ; '")
        try {
            val executable = root.resolve("probe.exe")
            val output = root.resolve("args.txt")
            val source = """
                using System;
                using System.IO;
                using System.Text;
                public class Probe {
                    public static int Main(string[] args) {
                        using (var w = new StreamWriter(args[0], false, new UTF8Encoding(false))) {
                            w.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(Environment.CurrentDirectory)));
                            for (int i = 1; i < args.Length; i++)
                                w.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(args[i])));
                        }
                        return 23;
                    }
                }
            """.trimIndent()
            run(WindowsCommand.powershell("Add-Type -TypeDefinition @'\n$source\n'@ -OutputAssembly '${executable.toString().replace("'", "''")}' -OutputType ConsoleApplication"), 0)
            val shim = root.resolve("probe.cmd")
            // npm-style forwarding, including the ENDLOCAL command separator.
            Files.writeString(shim, "@echo off\r\nSETLOCAL\r\nENDLOCAL & \"%~dp0probe.exe\" %*\r\n")
            val arguments = listOf("", "space аргумент", "a\"b", "tail\\", "tail\\\"", "%PATH%", "!PATH!", "^&|<>()",
                "\"&echo injected>injected.txt&\"", "a;b", "apostrophe'", "\"%PATH%\"")
            for (program in listOf(executable, shim)) {
                for (mode in AgentLaunchMode.entries) {
                    val session = agentCommand(AgentCli(program.toString(), listOf("resume", "--last")), mode, "--model 'some model'")
                    val expected = session.drop(1) + arguments
                    val command = listOf(program.toString(), output.toString()) + expected
                    run(WindowsCommand.powershell(WindowsCommand.script(command, root)), 23)
                    val actual = Files.readAllLines(output).map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                    assertEquals(root.toRealPath(), Path.of(actual.first()).toRealPath())
                    assertEquals(expected, actual.drop(1), "${program.fileName} $mode")
                    assertFalse(Files.exists(root.resolve("injected.txt")))
                }
            }
            run(WindowsCommand.powershell(WindowsCommand.script(listOf(executable.toString(), output.toString(), "line\r\nbreak"), root)), 23)
            assertEquals("line\r\nbreak", String(Base64.getDecoder().decode(Files.readAllLines(output).last()), Charsets.UTF_8))
            Files.delete(output)
            run(WindowsCommand.powershell(WindowsCommand.commandPrompt(
                WindowsCommand.script(listOf(executable.toString(), output.toString()) + arguments, root),
                WindowsExecutables().systemFile("cmd.exe"), root,
            )), 23)
            assertTrue(Files.exists(output), Files.list(root).use { it.toList().toString() })
            assertEquals(arguments, Files.readAllLines(output).drop(1).map {
                String(Base64.getDecoder().decode(it), Charsets.UTF_8)
            })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `batch line breaks and all NUL arguments fail before spawning`() {
        for (value in listOf("a\nb", "a\rb", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException> { WindowsCommand.script(listOf("agent.cmd", value)) }
        }
        assertFailsWith<IllegalArgumentException> { WindowsCommand.script(listOf("agent.exe", "\u0000")) }
    }

    private fun run(command: List<String>, exitCode: Int) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.outputStream.close()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Process did not finish")
            assertEquals(exitCode, process.exitValue(), process.inputStream.bufferedReader().readText())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
