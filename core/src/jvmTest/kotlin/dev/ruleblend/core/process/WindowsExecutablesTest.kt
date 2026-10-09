package dev.ruleblend.core.process

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsExecutablesTest {
    private val root = Files.createTempDirectory("rb-path")
    @AfterTest fun clean() { root.toFile().deleteRecursively() }

    private fun file(relative: String): Path = root.resolve(relative).also {
        Files.createDirectories(it.parent)
        Files.createFile(it)
    }

    @Test fun `PATH and PATHEXT are case insensitive and preserve search order`() {
        val cmd = file("first bin/agent.cmd")
        file("first bin/agent.exe")
        file("second/agent.cmd")
        val resolver = WindowsExecutables(mapOf(
            "Path" to "\"${cmd.parent}\";${root.resolve("second")}", "PathExt" to ".CMD;.EXE",
        ), root)
        assertEquals(cmd, resolver.find("agent"))
        assertEquals(cmd, resolver.find("agent.cmd"))
        assertEquals(cmd, resolver.find(cmd.toString()))
        assertEquals(root.resolve("first bin/agent.exe"), WindowsExecutables(
            mapOf("PATH" to cmd.parent.toString(), "PATHEXT" to ".CMD"), root,
        ).find("agent.exe"))
    }

    @Test fun `extensionless POSIX scripts directories and relative PATH entries are ignored`() {
        file("bin/agent")
        Files.createDirectories(root.resolve("bin/agent.exe"))
        val resolver = WindowsExecutables(mapOf("PATH" to ";.;relative;${root.resolve("bin")}",
            "PATHEXT" to ".PS1;.EXE;.CMD"), root)
        assertNull(resolver.find("agent"))
        assertNull(resolver.find("relative/agent.cmd"))
    }

    @Test fun `desktop lookup finds native user installs npm aliases and Git without shell PATH`() {
        val claude = file(".local/bin/claude.exe")
        val codex = file("roaming/npm/codex.cmd")
        val terminal = file("local/Microsoft/WindowsApps/wt.exe")
        val git = file("programs/Git/cmd/git.exe")
        val resolver = WindowsExecutables(mapOf("APPDATA" to root.resolve("roaming").toString(),
            "LOCALAPPDATA" to root.resolve("local").toString(), "ProgramFiles" to root.resolve("programs").toString()), root)
        assertEquals(claude, resolver.find("claude"))
        assertEquals(codex, resolver.find("codex"))
        assertEquals(terminal, resolver.find("wt.exe"))
        assertEquals(git, resolver.find("git.exe"))
    }
}
