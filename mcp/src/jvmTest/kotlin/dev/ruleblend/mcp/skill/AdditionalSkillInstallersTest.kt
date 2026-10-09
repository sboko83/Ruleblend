package dev.ruleblend.mcp.skill

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AdditionalSkillInstallersTest {
    private val home: Path = Files.createTempDirectory("ruleblend-additional-skill-home")

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `Kimi Code installs the bundled skill in its native directory`() {
        home.resolve(".kimi-code").createDirectories()
        val installer = KimiCodeSkillInstaller(home)

        installer.install()

        val skill = home.resolve(".kimi-code/skills/ruleblend/SKILL.md")
        assertEquals(BundledSkill.text, skill.readText())
        assertEquals(SkillStatus.INSTALLED, installer.status())
    }

    @Test
    fun `ZCode installs the bundled skill in its native directory`() {
        home.resolve(".zcode").createDirectories()
        val installer = ZCodeSkillInstaller(home)

        installer.install()

        val skill = home.resolve(".zcode/skills/ruleblend/SKILL.md")
        assertEquals(BundledSkill.text, skill.readText())
        assertEquals(SkillStatus.INSTALLED, installer.status())
    }

    @Test
    fun `Pi installs the bundled skill in its shared native directory`() {
        home.resolve(".pi/agent").createDirectories()
        val installer = PiSkillInstaller(home)

        installer.install()

        val skill = home.resolve(".agents/skills/ruleblend/SKILL.md")
        assertEquals(BundledSkill.text, skill.readText())
        installer.uninstall()
        assertFalse(skill.exists())
    }
}
