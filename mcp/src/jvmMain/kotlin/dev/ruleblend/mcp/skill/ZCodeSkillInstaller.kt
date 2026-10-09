package dev.ruleblend.mcp.skill

import java.nio.file.Path

/** ZCode loads its native user skill from `~/.zcode/skills/<name>/SKILL.md`. */
class ZCodeSkillInstaller(
    home: Path = Path.of(System.getProperty("user.home")),
) : FileSkillInstaller(
    agentId = "zcode",
    availabilityDirectory = home.resolve(".zcode"),
    skillFile = home.resolve(".zcode").resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFile = home.resolve(".zcode").resolve("skills").resolve("kitbash").resolve("SKILL.md"),
)
