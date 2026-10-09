package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.KimiCodeHome
import java.nio.file.Path

/** Kimi Code loads its user skill from `$KIMI_CODE_HOME/skills/<name>/SKILL.md`. */
class KimiCodeSkillInstaller(home: KimiCodeHome = KimiCodeHome.current()) : FileSkillInstaller(
    agentId = "kimi-code",
    availabilityDirectory = home.directory,
    skillFile = home.directory.resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFile = home.directory.resolve("skills").resolve("kitbash").resolve("SKILL.md"),
) {
    /** [home] is the operating-system home, see [KimiCodeHome.under]. */
    constructor(home: Path) : this(KimiCodeHome.under(home))
}
