package dev.ruleblend.core.integration

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.SUBAGENT_FIELD_MODEL
import dev.ruleblend.core.model.SUBAGENT_FIELD_TOOLS
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.splitYamlFrontmatter

/** How a subagent field is edited; the stored value is text either way. */
enum class SubagentFieldKind {
    TEXT,

    /** A comma-separated list, the syntax the agents that have such a field already use. */
    LIST,
}

/**
 * One tunable an assistant's subagent definition understands. [id] is canonical across assistants,
 * so the same preference keeps its identity when a definition is copied from one to another, while
 * [nativeKey] is what that assistant's own file calls it.
 */
data class SubagentFieldSpec(
    val id: String,
    val nativeKey: String = id,
    val kind: SubagentFieldKind = SubagentFieldKind.TEXT,
    /** Known values offered as completions. Never a closed set: agents add models constantly. */
    val suggestions: List<String> = emptyList(),
)

/** Parsed fields of one native subagent definition before it receives a library id. */
data class SubagentDraft(
    val name: String? = null,
    val description: String? = null,
    /** Canonical ids for fields this format declares, native keys for the ones it does not. */
    val fields: Map<String, String> = emptyMap(),
    val content: String,
) {
    val model: String? get() = fields[SUBAGENT_FIELD_MODEL]

    fun toVariant(): SubagentVariant = SubagentVariant(fields)
}

/** Reads and renders one portable library subagent in the exact file syntax an agent loads. */
interface SubagentFormat {
    val extension: String

    /** Fields this agent understands, in the order its file writes them. */
    val fields: List<SubagentFieldSpec>

    fun parse(text: String): SubagentDraft?

    /** Renders [subagent] with the values [variant] holds for this agent, and nothing invented. */
    fun render(subagent: Block, variant: SubagentVariant = SubagentVariant.EMPTY): String
}

/** Model names every current assistant accepts as an alias rather than a full model id. */
private val MODEL_SUGGESTIONS = listOf("inherit", "haiku", "sonnet", "opus")

/** Claude Code reads Markdown agents with `model`, `tools` and `color` in their frontmatter. */
object ClaudeCodeSubagentFormat : SubagentFormat by MarkdownSubagentFormat(
    listOf(
        SubagentFieldSpec(SUBAGENT_FIELD_MODEL, suggestions = MODEL_SUGGESTIONS),
        SubagentFieldSpec(SUBAGENT_FIELD_TOOLS, kind = SubagentFieldKind.LIST),
        SubagentFieldSpec(
            "color",
            suggestions = listOf("red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan"),
        ),
    ),
)

/** Kimi Code reads Markdown agents; its model preference is `modelPreference`. */
object KimiCodeSubagentFormat : SubagentFormat by MarkdownSubagentFormat(
    listOf(SubagentFieldSpec(SUBAGENT_FIELD_MODEL, nativeKey = "modelPreference", suggestions = MODEL_SUGGESTIONS)),
)

/** Codex reads TOML agents; their Markdown body becomes `developer_instructions`. */
object CodexSubagentFormat : SubagentFormat {
    override val extension: String = "toml"

    override val fields: List<SubagentFieldSpec> = listOf(SubagentFieldSpec(SUBAGENT_FIELD_MODEL))

    override fun parse(text: String): SubagentDraft? {
        val values = runCatching { parseSubagentToml(text) }.getOrNull() ?: return null
        val content = values.remove("developer_instructions") ?: return null
        val name = values.remove("name")
        val description = values.remove("description")
        return SubagentDraft(
            name = name,
            description = description,
            fields = values.canonicalize(fields),
            content = content,
        )
    }

    override fun render(subagent: Block, variant: SubagentVariant): String {
        subagent.requireSubagent()
        return buildString {
            append("name = ").append(tomlString(subagent.name)).appendLine()
            append("description = ").append(tomlString(subagent.description)).appendLine()
            variant.nativeEntries(fields).forEach { (key, value) ->
                append(key).append(" = ").append(tomlString(value)).appendLine()
            }
            append("developer_instructions = ").append(tomlString(subagent.content)).appendLine()
        }
    }
}

private class MarkdownSubagentFormat(
    override val fields: List<SubagentFieldSpec>,
) : SubagentFormat {
    override val extension: String = "md"

    override fun parse(text: String): SubagentDraft? {
        val split = splitYamlFrontmatter(text) ?: return null
        val header = split.meta.ifBlank { return SubagentDraft(content = split.body) }
        val node = runCatching { Yaml.default.parseToYamlNode(header) }.getOrNull() as? YamlMap ?: return null
        val values = linkedMapOf<String, String>()
        node.entries.forEach { (key, value) ->
            value.flatText()?.let { values[key.content] = it }
        }
        return SubagentDraft(
            name = values.remove("name"),
            description = values.remove("description"),
            fields = values.canonicalize(fields),
            content = split.body,
        )
    }

    override fun render(subagent: Block, variant: SubagentVariant): String {
        subagent.requireSubagent()
        return buildString {
            appendLine("---")
            append("name: ").append(yamlString(subagent.name)).appendLine()
            append("description: ").append(yamlString(subagent.description)).appendLine()
            variant.nativeEntries(fields).forEach { (key, value) ->
                append(key).append(": ").append(yamlString(value)).appendLine()
            }
            appendLine("---")
            appendLine()
            append(subagent.content)
        }
    }
}

/**
 * A scalar as text, a list of scalars as the comma-separated form its agents also accept, and
 * `null` for anything nested — a structure this flat model would rewrite into something else.
 */
private fun YamlNode.flatText(): String? = when (this) {
    is YamlScalar -> content
    is YamlNull -> null
    is YamlList -> items.map { (it as? YamlScalar)?.content ?: return null }.joinToString(", ")
    else -> null
}

/** Native keys replaced by the canonical ids [specs] give them; unknown keys keep their own name. */
private fun Map<String, String>.canonicalize(specs: List<SubagentFieldSpec>): Map<String, String> {
    val byNativeKey = specs.associateBy { it.nativeKey }
    return entries.associateTo(linkedMapOf()) { (key, value) -> (byNativeKey[key]?.id ?: key) to value }
}

/**
 * The variant's fields under the keys this format writes: declared fields first, in the order the
 * format lists them, then the ones it does not model, which are written back under the names they
 * arrived with. Fields another assistant owns never appear here, because a variant is per agent.
 */
private fun SubagentVariant.nativeEntries(specs: List<SubagentFieldSpec>): List<Pair<String, String>> {
    val declared = specs.mapNotNull { spec -> fields[spec.id]?.let { spec.nativeKey to it } }
    val declaredIds = specs.map { it.id }.toSet()
    val extra = fields.filterKeys { it !in declaredIds }.map { (key, value) -> key to value }
    return declared + extra
}

private fun Block.requireSubagent() {
    require(type == BlockType.SUBAGENT) { "Expected a subagent block, got $type" }
}

/** JSON double-quoted strings are valid YAML scalars and give this small header stable escaping. */
private fun yamlString(value: String): String = tomlString(value)

private fun tomlString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000C' -> append("\\f")
            '\r' -> append("\\r")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

/** One assistant that can host a subagent, with the fields its own definition file understands. */
data class SubagentAgentFields(
    val agentId: String,
    val agentName: String,
    val fields: List<SubagentFieldSpec>,
)

/** The assistants a subagent can be tuned for, in the order the caller lists its agents. */
fun List<AgentAdapter>.subagentAgentFields(): List<SubagentAgentFields> = mapNotNull { agent ->
    if (agent.subagentSupport != SubagentSupport.SUPPORTED) return@mapNotNull null
    val format = agent.subagentFormat ?: return@mapNotNull null
    SubagentAgentFields(agent.id, agent.name, format.fields)
}
