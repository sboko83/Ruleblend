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
 */
abstract class FileSkillInstaller(
    final override val agentId: String,
    private val availabilityDirectory: Path,
    private val skillFile: Path,
    private val legacySkillFile: Path? = null,
) : SkillInstaller {

    override fun isAvailable(): Boolean = availabilityDirectory.exists()

    override fun status(): SkillStatus {
        if (!skillFile.exists()) {
            return if (legacySkillFile.isOwnedLegacySkill()) SkillStatus.OUTDATED else SkillStatus.NOT_INSTALLED
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
            ?: return if (legacySkillFile.isOwnedLegacySkill()) 0 else null
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

    private fun Path?.isOwnedLegacySkill(): Boolean =
        this?.takeIf { it.exists() }?.readText()?.contains(BundledSkill.LEGACY_MARKER) == true

    private fun removeOwnedLegacySkill() {
        if (legacySkillFile.isOwnedLegacySkill()) removeSkillFile(requireNotNull(legacySkillFile))
    }

    private fun removeSkillFile(file: Path) {
        file.deleteIfExists()
        val skillDirectory = file.parent
        if (Files.newDirectoryStream(skillDirectory).use { !it.iterator().hasNext() }) skillDirectory.deleteIfExists()
    }
}
