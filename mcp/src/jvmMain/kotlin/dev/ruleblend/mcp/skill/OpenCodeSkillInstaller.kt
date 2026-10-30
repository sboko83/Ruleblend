package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.AssistantPaths
import java.nio.file.Path

/** OpenCode loads its native user skill from `<config root>/skills/<name>/SKILL.md`. */
class OpenCodeSkillInstaller(
    home: Path? = null,
    paths: AssistantPaths = AssistantPaths.forHome(home),
) : FileSkillInstaller(
    agentId = "opencode",
    availabilityDirectory = paths.openCode,
    skillFile = paths.openCode.resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
)
