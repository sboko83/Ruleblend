package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.setSkillExecutable
import dev.ruleblend.core.storage.skillExecutable
import dev.ruleblend.core.storage.skillTreeFingerprint
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PortableSkillInstallTest {
    private val home = Files.createTempDirectory("ruleblend-portable-install")
    private val agent = ClaudeCodeAdapter(home)
    private val target = AgentGlobalTarget(agent)
    private val state = SkillInstallStateStore(home.resolve("state"))
    private val skill = Skill("review", "review", content = "# Review\n")
    private var snapshot: SkillSnapshot? = SkillSnapshot(skill, listOf(
        SkillFile("SKILL.md", skill.content.encodeToByteArray()),
        SkillFile("scripts/check.sh", "#!/bin/sh\nexit 0\n".encodeToByteArray(), executable = true),
        SkillFile("assets/data.bin", byteArrayOf(0, -1, 42)),
    ))
    private val installed = home.resolve(".claude/skills/review")

    private fun service() = SkillInstallService(
        SkillInstallStateStore(home.resolve("state")), { snapshot }, { agent },
    )

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `portable modes survive restart update and removal`() {
        service().install(target, skill)
        assertEquals(InstallStatus.SYNCED, service().status(target, skill)?.status)
        assertEquals(listOf("scripts/check.sh"), state.all().single().executableFiles)
        requireNotNull(snapshot).files.forEach { assertEquals(it.bytes.toList(), installed.resolve(it.path).readBytes().toList()) }

        snapshot = requireNotNull(snapshot).let { original ->
            original.copy(files = original.files.map { it.copy(executable = false) })
        }
        assertEquals(InstallStatus.UPDATE_AVAILABLE, service().status(target, skill)?.status)
        service().install(target, skill)
        assertEquals(InstallStatus.SYNCED, service().status(target, skill)?.status)
        assertEquals(emptyList(), state.all().single().executableFiles)
        assertFalse(skillExecutable(installed.resolve("scripts/check.sh")))
        service().remove(target, skill)
        assertFalse(installed.exists())
    }

    @Test
    fun `content updates do not require overwrite`() {
        service().install(target, skill)
        snapshot = requireNotNull(snapshot).let { original ->
            original.copy(files = original.files.map { if (it.path.endsWith(".sh")) it.copy(bytes = "changed\n".encodeToByteArray()) else it })
        }
        assertEquals(InstallStatus.UPDATE_AVAILABLE, service().status(target, skill)?.status)
        service().install(target, skill)
        assertEquals(InstallStatus.SYNCED, service().status(target, skill)?.status)
        service().remove(target, skill)
        assertFalse(installed.exists())
    }

    @Test
    fun `real edits additions and deletions remain protected`() {
        val changes: List<(Path) -> Unit> = listOf(
            { it.resolve("scripts/check.sh").writeBytes("manual edit".encodeToByteArray()) },
            { it.resolve("new.txt").writeBytes(byteArrayOf(1)) },
            { Files.delete(it.resolve("assets/data.bin")) },
        )
        changes.forEach { change ->
            service().install(target, skill, overwrite = true)
            change(installed)
            assertEquals(InstallStatus.MODIFIED, service().status(target, skill)?.status)
            assertFailsWith<IllegalArgumentException> { service().install(target, skill) }
            assertFailsWith<IllegalArgumentException> { service().remove(target, skill) }
            assertEquals(OrphanRemoval.PROTECTED, service().removeOrphan(target, skill.id))
        }
    }

    @Test
    fun `orphan removal uses recorded modes without the library`() {
        service().install(target, skill)
        snapshot = null
        assertEquals(OrphanRemoval.REMOVED, service().removeOrphan(target, skill.id))
        assertFalse(installed.exists())
    }

    @Test
    fun `restoring an orphan retains recorded executable files`() {
        service().install(target, skill)
        val expected = requireNotNull(snapshot).files.toSet()
        snapshot = null
        val entry = service().entries(target).single()
        assertEquals(expected, service().capture(target, entry).files.toSet())
    }

    @Test
    fun `shared roots use the modes most recently installed by any owner`() {
        val pi = PiAdapter(home)
        val kimi = KimiCodeAdapter(home)
        val adapters = listOf(pi, kimi)
        val service = SkillInstallService(state, { snapshot }, { id -> adapters.firstOrNull { it.id == id } })
        val piTarget = AgentGlobalTarget(pi)
        val kimiTarget = AgentGlobalTarget(kimi)
        service.install(piTarget, skill)
        service.install(kimiTarget, skill)
        snapshot = requireNotNull(snapshot).let { original ->
            original.copy(files = original.files.map { it.copy(executable = false) })
        }
        service.install(kimiTarget, skill)
        assertEquals(InstallStatus.SYNCED, service.status(piTarget, skill)?.status)
        val expected = requireNotNull(snapshot).files.toSet()
        snapshot = null
        assertEquals(expected, service.capture(piTarget, service.entries(piTarget).single()).files.toSet())
    }

    @Test
    fun `legacy record can match the library but cannot hide content edits`() {
        service().install(target, skill)
        state.record(state.all().single().copy(executableFiles = null))
        assertEquals(InstallStatus.SYNCED, service().status(target, skill)?.status)
        installed.resolve("scripts/check.sh").writeBytes("manual edit".encodeToByteArray())
        assertEquals(InstallStatus.MODIFIED, service().status(target, skill)?.status)
        assertFailsWith<IllegalArgumentException> { service().install(target, skill) }
    }

    @Test
    fun `mode drift is observable only on a POSIX filesystem`() {
        service().install(target, skill)
        val script = installed.resolve("scripts/check.sh")
        setSkillExecutable(script, false)
        val supportsPosix = Files.getFileAttributeView(script, PosixFileAttributeView::class.java) != null
        assertEquals(if (supportsPosix) InstallStatus.MODIFIED else InstallStatus.SYNCED, service().status(target, skill)?.status)
        if (supportsPosix) assertFailsWith<IllegalArgumentException> { service().remove(target, skill) }
    }

    @Test
    fun `missing ownership stays foreign despite an identical tree`() {
        service().install(target, skill)
        state.removeAll("agent", skill.id)
        assertEquals(SkillInstallStatus(InstallStatus.MODIFIED, true), service().status(target, skill))
        assertFailsWith<IllegalArgumentException> { service().remove(target, skill) }
    }

    @Test
    fun `portable fingerprint includes executable intent and ignores file ordering`() {
        val files = requireNotNull(snapshot).files
        assertEquals(skillTreeFingerprint(files), skillTreeFingerprint(files.reversed()))
        assertFalse(skillTreeFingerprint(files) == skillTreeFingerprint(files.map { it.copy(executable = false) }))
    }
}
