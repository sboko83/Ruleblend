package dev.ruleblend.core.integration

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantPathsTest {
    private val home = Files.createTempDirectory("ruleblend-homes-")
    @AfterTest fun cleanup() { home.toFile().deleteRecursively() }

    @Test fun overridesAreSharedByRulesSkillsSubagentsAndDetection() {
        val paths = AssistantPaths(home, mapOf(
            "CODEX_HOME" to home.resolve("Кодекс с пробелом").toString(),
            "CLAUDE_CONFIG_DIR" to home.resolve("Клод").toString(),
            "PI_CODING_AGENT_DIR" to "~/pi-custom",
            "KIMI_CODE_HOME" to home.resolve("kimi-custom").toString(),
            "HOME" to "/home/wsl-user", "APPDATA" to home.resolve("Roaming").toString(),
        ))
        listOf(paths.codex, paths.claude, paths.pi, paths.kimi).forEach(Files::createDirectories)
        val codex = CodexAdapter(paths = paths)
        val claude = ClaudeCodeAdapter(paths = paths)
        val pi = PiAdapter(paths = paths)
        assertTrue(codex.isAvailable())
        assertTrue(claude.isAvailable())
        assertTrue(pi.isAvailable())
        assertEquals(paths.codex.resolve("AGENTS.md"), codex.globalFile())
        assertEquals(paths.codex.resolve("skills"), codex.globalSkillDirectories().single().path)
        assertEquals(paths.codex.resolve("agents"), codex.globalSubagentDirectory())
        assertEquals(paths.claude.resolve("CLAUDE.md"), claude.globalFile())
        assertEquals(paths.claude.resolve("skills"), claude.globalSkillsDirectory())
        assertEquals(paths.claude.resolve("agents"), claude.globalSubagentDirectory())
        assertEquals(paths.claude.resolve(".claude.json"), paths.claudeConfig)
        assertEquals(home.resolve("pi-custom/AGENTS.md"), pi.globalFile())
        assertEquals(home.resolve(".agents/skills"), pi.globalSkillsDirectory())
        assertEquals(home.resolve("project/.codex/skills"), codex.projectSkillDirectories(home.resolve("project")).single().path)
    }

    @Test fun explicitHomesUseDefaultsAndIgnoreUnrelatedWindowsVariables() {
        val paths = AssistantPaths(home, mapOf("HOME" to "/home/wsl", "APPDATA" to "other", "USERPROFILE" to "other"))
        assertEquals(home.resolve(".codex"), paths.codex)
        assertEquals(home.resolve(".claude.json"), paths.claudeConfig)
        assertEquals(home.resolve(".pi/agent"), paths.pi)
        assertEquals(home.resolve(".kimi-code"), paths.kimi)
        assertEquals(home.resolve(".codex/AGENTS.md"), CodexAdapter(home).globalFile())
    }

    @Test fun foreignOrRelativeOverridesAreIgnoredWithoutAffectingOtherAssistants() {
        val invalid = mutableListOf("relative/path")
        if (File.separatorChar == '\\') invalid += listOf("/home/user/.codex", "\\\\wsl$\\Ubuntu\\home\\user", "\\\\wsl.localhost\\Ubuntu\\home\\user")
        val claude = home.resolve("claude-custom")
        invalid.forEach { value ->
            val paths = AssistantPaths(home, mapOf("CODEX_HOME" to value, "CLAUDE_CONFIG_DIR" to claude.toString()))
            assertEquals(home.resolve(".codex"), paths.codex, value)
            assertEquals(claude, paths.claude, value)
            assertEquals(1, paths.rejectedOverrides.size, value)
            assertTrue(paths.rejectedOverrides.single().startsWith("CODEX_HOME "), value)
        }
    }
}
