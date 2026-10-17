package dev.ruleblend.mcp.skill

import dev.ruleblend.core.storage.AtomicWrite
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Shared ownership and atomic-write behavior for agents that load one `SKILL.md` from a known
 * directory. The availability directory is agent-specific and need not contain the skill itself.
 * [legacySkillFiles] are earlier addresses of the same skill; a copy Ruleblend wrote there reads as
 * outdated and is removed on the next install or uninstall.
 */
abstract class FileSkillInstaller(
    final override val agentId: String,
    private val availabilityDirectory: Path,
    private val skillFile: Path,
    private val legacySkillFiles: List<Path> = emptyList(),
) : SkillInstaller {

    override fun isAvailable(): Boolean = availabilityDirectory.exists()

    override fun status(): SkillStatus {
        if (!skillFile.exists()) {
            return if (ownedLegacySkills().isNotEmpty()) SkillStatus.OUTDATED else SkillStatus.NOT_INSTALLED
        }
        val text = skillFile.readText()
        return when {
            text == BundledSkill.text -> SkillStatus.INSTALLED
            BundledSkill.isOwned(text) -> SkillStatus.OUTDATED
            else -> SkillStatus.FOREIGN
        }
    }

    override fun installedVersion(): Int? {
        val text = skillFile.takeIf { it.exists() }?.readText()
            ?: return ownedLegacySkills().firstOrNull()?.let { BundledSkill.versionIn(it.readText()) ?: 0 }
        return BundledSkill.versionIn(text)
    }

    override fun install() {
        if (status() == SkillStatus.FOREIGN) {
            error("$skillFile was not written by Ruleblend — remove or rename it first")
        }
        AtomicWrite.write(skillFile, BundledSkill.text)
        removeOwnedLegacySkill()
    }

    override fun uninstall() {
        if (skillFile.exists()) {
            val text = skillFile.readText()
            if (BundledSkill.isOwned(text)) removeSkillFile(skillFile)
        }
        removeOwnedLegacySkill()
    }

    private fun ownedLegacySkills(): List<Path> = legacySkillFiles.filter { file ->
        val text = file.takeIf { it.exists() }?.readText() ?: return@filter false
        text.contains(BundledSkill.LEGACY_MARKER) || BundledSkill.isOwned(text)
    }

    private fun removeOwnedLegacySkill() {
        ownedLegacySkills().forEach(::removeSkillFile)
    }

    private fun removeSkillFile(file: Path) {
        file.deleteIfExists()
        val skillDirectory = file.parent
        if (Files.newDirectoryStream(skillDirectory).use { !it.iterator().hasNext() }) skillDirectory.deleteIfExists()
    }
}
