package dev.ruleblend.mcp.skill

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CodexSkillInstallerTest {

    private lateinit var home: Path
    private lateinit var installer: CodexSkillInstaller

    private val skillFile: Path get() = home.resolve(".codex/skills/ruleblend/SKILL.md")
    private val legacySkillFile: Path get() = home.resolve(".codex/skills/kitbash/SKILL.md")

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-codex-skill-home")
        installer = CodexSkillInstaller(home)
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `is available only when Codex is`() {
        assertFalse(installer.isAvailable())

        home.resolve(".codex").createDirectories()

        assertEquals(true, installer.isAvailable())
        assertEquals("codex", installer.agentId)
    }

    @Test
    fun `install uses the Codex user skill location`() {
        home.resolve(".codex").createDirectories()

        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertEquals(SkillStatus.INSTALLED, installer.status())
        assertFalse(home.resolve(".agents/skills/ruleblend/SKILL.md").exists())
    }

    @Test
    fun `install migrates an owned Kitbash skill`() {
        home.resolve(".codex").createDirectories()
        legacySkillFile.parent.createDirectories()
        legacySkillFile.writeText("# Kitbash\n\n${BundledSkill.LEGACY_MARKER}\n")

        assertEquals(SkillStatus.OUTDATED, installer.status())
        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertFalse(legacySkillFile.exists())
    }

    @Test
    fun `disconnect removes only the Ruleblend owned skill`() {
        home.resolve(".codex").createDirectories()
        installer.install()

        installer.uninstall()

        assertFalse(skillFile.exists())
        assertFalse(skillFile.parent.exists())
    }
}
