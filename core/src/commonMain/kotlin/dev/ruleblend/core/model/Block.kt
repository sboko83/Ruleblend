package dev.ruleblend.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BlockType {
    @SerialName("rule")
    RULE,

    /** An MCP server definition; the block body is the canonical config JSON ([McpConfigCodec]). */
    @SerialName("mcp")
    MCP,

    /** A Markdown subagent definition; [Block.variants] hold its per-assistant fields. */
    @SerialName("subagent")
    SUBAGENT,
}

/**
 * Instruction block. [id] is the slug and the file name in `blocks/<id>.md`.
 * [version] increments on every content change.
 */
data class Block(
    val id: String,
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val type: BlockType = BlockType.RULE,
    val content: String = "",
    val favorite: Boolean = false,
    /**
     * Per-assistant fields of a [BlockType.SUBAGENT], keyed by agent id. An assistant without an
     * entry here is installed with the portable definition alone: no model or tool preference is
     * invented for it, because a value tuned for one assistant is rarely valid in another.
     */
    val variants: Map<String, SubagentVariant> = emptyMap(),
    /**
     * Markdown heading rendered above [content] when the block is written into a target file, text
     * only (no `#` markers). Empty means the block carries no heading of its
     * own — the right choice for a block meant to blend into a surrounding section.
     */
    val heading: String = "",
    /** Depth of [heading], 1..6. Ignored when [heading] is empty. */
    val headingLevel: Int = DEFAULT_HEADING_LEVEL,
    val source: GitSubagentSource? = null,
    val forkedFrom: GitSubagentSource? = null,
)

/** The assistant's own fields for this subagent, empty when it has none of its own. */
fun Block.variant(agentId: String): SubagentVariant = variants[agentId] ?: SubagentVariant.EMPTY

/** This block with [variant] replacing the entry for [agentId]; an empty variant is dropped. */
fun Block.withVariant(agentId: String, variant: SubagentVariant): Block {
    val updated = LinkedHashMap(variants)
    if (variant.isEmpty) updated.remove(agentId) else updated[agentId] = variant
    return copy(variants = updated)
}

/** Heading depth used when a block gains a heading without one being stated. */
const val DEFAULT_HEADING_LEVEL: Int = 2

/**
 * The heading line as it is written into a target file, or empty when [Block.heading] is blank.
 * The level is clamped to markdown's 1..6 so a malformed block file cannot emit `####### x`.
 */
fun Block.headingLine(): String =
    if (heading.isBlank()) "" else "#".repeat(headingLevel.coerceIn(1, 6)) + " " + heading.trim()

private val HEADING_LINE = Regex("""^(#{1,6})\s+(.*?)\s*$""")

/**
 * The block as it should be stored when the text of its installed region comes back edited: for a
 * block that carries a heading, a leading heading line is taken as the heading again and the rest
 * as the content — otherwise accepting a local edit would fold the rendered heading into the body
 * and render it twice on the next install. A heading the user deleted in the file is dropped from
 * the block too. Blocks without a heading are untouched: there a leading `#` line is body text.
 */
fun Block.withRegionContent(regionContent: String): Block {
    val normalized = regionContent.replace("\r\n", "\n")
    if (heading.isBlank()) return copy(content = normalized)
    val lines = normalized.split("\n")
    val match = HEADING_LINE.matchEntire(lines.first())
        ?: return copy(heading = "", content = normalized)
    return copy(
        heading = match.groupValues[2],
        headingLevel = match.groupValues[1].length,
        content = lines.drop(1).joinToString("\n").trimStart('\n'),
    )
}

/** Frontmatter part of a block file; [Block.content] is the file body. */
@Serializable
data class BlockMeta(
    val name: String,
    val description: String = "",
    val version: Int = 1,
    val type: BlockType = BlockType.RULE,
    val favorite: Boolean = false,
    /** Pre-variant libraries stored one shared preference here; [BlockFile] migrates it and never writes it back. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val model: String? = null,
    /** Written only for a subagent that has per-assistant fields, so other blocks stay unchanged. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val variants: Map<String, Map<String, String>> = emptyMap(),
    val heading: String = "",
    val headingLevel: Int = DEFAULT_HEADING_LEVEL,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val source: GitSubagentSource? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val forkedFrom: GitSubagentSource? = null,
)
