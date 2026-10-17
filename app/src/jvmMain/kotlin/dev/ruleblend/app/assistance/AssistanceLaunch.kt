package dev.ruleblend.app.assistance

import dev.ruleblend.app.i18n.Language
import java.nio.file.Path

/** Direct PTY launch data. The process inherits the user's environment and normal CLI profile. */
data class AssistanceLaunch(
    val assistant: ReadyAssistanceAssistant,
    val target: AssistanceObject,
    val directory: Path,
    val command: List<String>,
    val environmentOverrides: Map<String, String>,
    /** Captured from the composition at launch; a later theme change applies from the next session. */
    val colors: AssistanceTerminalColors,
)

/** Native prefill leaves the draft in Claude's own editor; no prompt argument or PTY input. */
fun prepareClaudeAssistanceLaunch(
    assistant: ReadyAssistanceAssistant,
    target: AssistanceObject,
    language: Language,
    directory: Path,
    colors: AssistanceTerminalColors,
): AssistanceLaunch {
    require(assistant.id == "claude-code") { "Native prefill is available only for Claude Code" }
    require(assistant.cli.path.isNotBlank() && '\u0000' !in assistant.cli.path) { "A CLI executable is required" }
    require(directory.isAbsolute) { "An absolute working directory is required" }
    return AssistanceLaunch(
        assistant, target, directory,
        listOf(assistant.cli.path, "--prefill", target.draft(language)),
        assistant.cli.searchPath?.let { mapOf("PATH" to it) }.orEmpty(),
        colors,
    )
}
