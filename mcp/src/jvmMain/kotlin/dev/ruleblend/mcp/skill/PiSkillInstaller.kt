package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.AssistantPaths
import java.nio.file.Path

/** Pi reads shared user skills from `~/.agents/skills/<name>/SKILL.md`. */
class PiSkillInstaller(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : FileSkillInstaller(
    agentId = "pi",
    availabilityDirectory = paths.pi,
    skillFile = paths.userHome.resolve(".agents").resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFile = paths.userHome.resolve(".agents").resolve("skills").resolve("kitbash").resolve("SKILL.md"),
)
