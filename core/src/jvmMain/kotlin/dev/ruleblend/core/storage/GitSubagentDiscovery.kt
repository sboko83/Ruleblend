package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import dev.ruleblend.core.integration.ClaudeCodeSubagentFormat
import dev.ruleblend.core.integration.CodexSubagentFormat
import dev.ruleblend.core.integration.KimiCodeSubagentFormat
import dev.ruleblend.core.integration.SubagentDraft
import dev.ruleblend.core.integration.parseSubagentToml
import dev.ruleblend.core.model.splitYamlFrontmatter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/** Ambiguous Markdown retains both interpretations until the user selects its source assistant. */
data class GitSubagentCandidate(
    val path: String,
    val sourceAssistant: String?,
    val drafts: Map<String, SubagentDraft>,
    val sourceText: String,
) {
    val requiresAssistantSelection: Boolean get() = sourceAssistant == null

    fun draftFor(assistant: String): SubagentDraft =
        drafts[assistant] ?: error("Assistant '$assistant' is not supported for $path")
}

/** A bad definition never hides other candidates, and its original text remains available. */
data class GitImportFileError(val path: String, val message: String, val sourceText: String? = null)

internal data class GitAgentRoot(val path: String, val assistant: String?)
internal data class GitSubagentDiscovery(
    val candidates: List<GitSubagentCandidate>,
    val errors: List<GitImportFileError>,
)

internal fun discoverGitSubagents(
    checkout: Path,
    paths: List<String>,
    declared: List<GitAgentRoot>,
    limits: GitImportLimits,
): GitSubagentDiscovery {
    val roots = declared.map { root ->
        val resolved = resolveGitImportPath(checkout, root.path)
        require(resolved.exists()) { "Plugin agents path does not exist: ${root.path}" }
        root.copy(path = checkout.relativize(resolved).joinToString("/"))
    } + listOf(
        GitAgentRoot(".codex/agents", "codex"),
        GitAgentRoot(".claude/agents", "claude-code"),
        GitAgentRoot(".kimi-code/agents", "kimi-code"),
        GitAgentRoot("agents", null),
        GitAgentRoot("subagents", null),
        GitAgentRoot("categories", null),
    )
    val candidates = mutableListOf<GitSubagentCandidate>()
    val errors = mutableListOf<GitImportFileError>()
    for (path in paths.sorted()) {
        val matching = roots.filter { it.path.isEmpty() || path == it.path || path.startsWith("${it.path}/") }
        if (matching.isEmpty()) continue
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in setOf("toml", "md")) continue
        if (path.substringAfterLast('/').substringBeforeLast('.').uppercase() in DOCUMENT_NAMES) continue
        var source: String? = null
        try {
            val file = checkout.resolve(path)
            require(Files.size(file) <= limits.maxSubagentBytes) { "Subagent exceeds ${limits.maxSubagentBytes} bytes" }
            source = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
            val assistants = matching.mapNotNull { it.assistant }.distinct()
            require(assistants.size <= 1) { "Conflicting assistant declarations" }
            val hint = assistants.singleOrNull()
            if (extension == "toml") {
                require(hint == null || hint == "codex") { "TOML is only supported for Codex subagents" }
                // Parse strictly to expose the specific error rather than the nullable adapter API.
                parseSubagentToml(source)
                val draft = CodexSubagentFormat.parse(source) ?: error("Missing Codex instructions")
                requireDefinition(draft)
                candidates.add(GitSubagentCandidate(path, "codex", mapOf("codex" to draft), source))
            } else {
                // Documentation, including frontmatter with title/tags, is not an agent definition.
                if (!looksLikeMarkdownAgent(source)) continue
                validateMarkdownFields(source)
                require(hint == null || hint in MARKDOWN_FORMATS) { "Markdown is not a Codex subagent format" }
                val formats = if (hint == null) MARKDOWN_FORMATS else MARKDOWN_FORMATS.filterKeys { it == hint }
                val drafts = formats.mapValues { (_, format) ->
                    val draft = format.parse(source) ?: error("Invalid Markdown subagent")
                    requireDefinition(draft)
                    draft
                }
                candidates.add(GitSubagentCandidate(path, hint, drafts, source))
            }
        } catch (e: Exception) {
            errors.add(GitImportFileError(path, e.message ?: "Invalid subagent", source))
        }
    }
    return GitSubagentDiscovery(candidates, errors)
}

private fun requireDefinition(draft: SubagentDraft) {
    require(!draft.name.isNullOrBlank()) { "Missing subagent name" }
    require(!draft.description.isNullOrBlank()) { "Missing subagent description" }
    require(draft.content.isNotBlank()) { "Missing subagent instructions" }
}

private fun looksLikeMarkdownAgent(text: String): Boolean {
    if (text.lineSequence().firstOrNull()?.trim() != "---") return false
    val header = splitYamlFrontmatter(text)?.meta ?: text.lineSequence().drop(1).take(200).joinToString("\n")
    return Regex("(?m)^name\\s*:").containsMatchIn(header) && Regex("(?m)^description\\s*:").containsMatchIn(header)
}

private fun validateMarkdownFields(text: String) {
    val split = splitYamlFrontmatter(text) ?: error("Missing or unterminated YAML frontmatter")
    val header = Yaml.default.parseToYamlNode(split.meta) as? YamlMap ?: error("Expected YAML fields")
    for ((key, value) in header.entries) {
        require(Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(key.content)) { "Unsupported YAML field name: ${key.content}" }
        require(value is YamlScalar || (value is YamlList && value.items.all { it is YamlScalar })) {
            "Unsupported YAML value for '${key.content}'; expected a scalar or scalar list"
        }
        if (key.content in setOf("name", "description")) {
            require(value is YamlScalar) { "Expected a scalar for '${key.content}'" }
        }
    }
    require(header.entries.keys.none { it.content == "model" } || header.entries.keys.none { it.content == "modelPreference" }) {
        "Conflicting model and modelPreference fields"
    }
}

private val MARKDOWN_FORMATS = linkedMapOf("claude-code" to ClaudeCodeSubagentFormat, "kimi-code" to KimiCodeSubagentFormat)
private val DOCUMENT_NAMES = setOf("README", "SKILL", "AGENTS", "CLAUDE", "CONTRIBUTING", "CHANGELOG", "LICENSE")
