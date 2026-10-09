package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.AssistantPaths
import java.nio.file.Path

/**
 * Codex's Ruleblend workflow lives in its native `~/.codex/skills/<name>/SKILL.md` root. The
 * shared `.agents` tree is deliberately reserved for its own assistant integration.
 */
class CodexSkillInstaller(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : FileSkillInstaller(
    agentId = "codex",
    availabilityDirectory = paths.codex,
    skillFile = paths.codex.resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFile = paths.codex.resolve("skills").resolve("kitbash").resolve("SKILL.md"),
)
