package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.AssistantPaths
import java.nio.file.Path

/**
 * Pi loads its own user skills from `~/.pi/agent/skills/<name>/SKILL.md`. Earlier versions wrote the
 * shared `~/.agents/skills` tree instead; a copy left there is retired on the next install.
 */
class PiSkillInstaller(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : FileSkillInstaller(
    agentId = "pi",
    availabilityDirectory = paths.pi,
    skillFile = paths.pi.resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFiles = paths.userHome.resolve(".agents").resolve("skills").let { shared ->
        listOf(shared.resolve(BundledSkill.NAME).resolve("SKILL.md"), shared.resolve("kitbash").resolve("SKILL.md"))
    },
)
