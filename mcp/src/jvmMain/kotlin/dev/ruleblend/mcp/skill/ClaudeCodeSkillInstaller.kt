package dev.ruleblend.mcp.skill

import dev.ruleblend.core.integration.AssistantPaths
import java.nio.file.Path

/**
 * Claude Code reads user skills from `~/.claude/skills/<name>/SKILL.md`. The whole directory is
 * Ruleblend's own, so the file is written in full rather than as a managed region — but only after
 * the marker confirms the copy on disk is ours.
 */
class ClaudeCodeSkillInstaller(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : FileSkillInstaller(
    agentId = "claude-code",
    availabilityDirectory = paths.claude,
    skillFile = paths.claude.resolve("skills").resolve(BundledSkill.NAME).resolve("SKILL.md"),
    legacySkillFile = paths.claude.resolve("skills").resolve("kitbash").resolve("SKILL.md"),
)
