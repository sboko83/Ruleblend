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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ClaudeCodeSkillInstallerTest {

    private lateinit var home: Path
    private lateinit var installer: ClaudeCodeSkillInstaller

    private val skillFile: Path get() = home.resolve(".claude/skills/ruleblend/SKILL.md")
    private val legacySkillFile: Path get() = home.resolve(".claude/skills/kitbash/SKILL.md")

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-skill-home")
        installer = ClaudeCodeSkillInstaller(home)
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `is available only when Claude Code is`() {
        assertFalse(installer.isAvailable())

        home.resolve(".claude").createDirectories()
        assertEquals(true, installer.isAvailable())
    }

    @Test
    fun `install creates the skills directory and reports installed afterwards`() {
        home.resolve(".claude").createDirectories()
        assertEquals(SkillStatus.NOT_INSTALLED, installer.status())

        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertEquals(SkillStatus.INSTALLED, installer.status())
        // Re-running is a no-op, not a duplicate.
        installer.install()
        assertEquals(SkillStatus.INSTALLED, installer.status())
    }

    @Test
    fun `a copy from an older version is outdated and gets replaced`() {
        home.resolve(".claude").createDirectories()
        skillFile.parent.createDirectories()
        skillFile.writeText("# Old text\n\n${BundledSkill.MARKER_PREFIX} v0 -->\n")

        assertEquals(SkillStatus.OUTDATED, installer.status())

        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertEquals(SkillStatus.INSTALLED, installer.status())
    }

    @Test
    fun `install migrates an owned Kitbash skill`() {
        home.resolve(".claude").createDirectories()
        legacySkillFile.parent.createDirectories()
        legacySkillFile.writeText("# Kitbash\n\n${BundledSkill.LEGACY_MARKER}\n")

        assertEquals(SkillStatus.OUTDATED, installer.status())
        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertFalse(legacySkillFile.exists())
        assertFalse(legacySkillFile.parent.exists())
    }

    @Test
    fun `install preserves a foreign skill using the old name`() {
        home.resolve(".claude").createDirectories()
        legacySkillFile.parent.createDirectories()
        legacySkillFile.writeText("# Unrelated Kitbash skill\n")

        installer.install()

        assertEquals(BundledSkill.text, skillFile.readText())
        assertEquals("# Unrelated Kitbash skill\n", legacySkillFile.readText())
    }

    @Test
    fun `a skill written by someone else is never touched`() {
        home.resolve(".claude").createDirectories()
        skillFile.parent.createDirectories()
        val foreign = "---\nname: ruleblend\n---\n\nHand-written.\n"
        skillFile.writeText(foreign)

        assertEquals(SkillStatus.FOREIGN, installer.status())
        assertFailsWith<IllegalStateException> { installer.install() }
        assertEquals(foreign, skillFile.readText())
    }

    @Test
    fun `uninstall removes a ruleblend owned skill and its empty directory`() {
        home.resolve(".claude").createDirectories()
        installer.install()

        installer.uninstall()

        assertFalse(skillFile.exists())
        assertFalse(skillFile.parent.exists())
        assertEquals(SkillStatus.NOT_INSTALLED, installer.status())
    }

    @Test
    fun `uninstall preserves a foreign skill`() {
        home.resolve(".claude").createDirectories()
        skillFile.parent.createDirectories()
        val foreign = "---\nname: ruleblend\n---\n\nHand-written.\n"
        skillFile.writeText(foreign)

        installer.uninstall()

        assertEquals(foreign, skillFile.readText())
    }

    @Test
    fun `the bundled skill carries the marker and a frontmatter name`() {
        val text = BundledSkill.text

        assertEquals(listOf("---", "name: ${BundledSkill.NAME}"), text.lineSequence().take(2).toList())
        assertEquals(true, text.trimEnd().endsWith("${BundledSkill.MARKER_PREFIX} v${BundledSkill.version} -->"))
        assertContains(text, "`agent:claude-code`")
        assertContains(text, "`agent:codex`")
        assertFalse(skillFile.exists())
    }
}
