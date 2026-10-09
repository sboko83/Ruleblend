package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockMeta
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.SUBAGENT_FIELD_MODEL
import dev.ruleblend.core.model.SubagentVariant

private const val DELIMITER = "---"

/**
 * How far into the file the closing frontmatter delimiter is looked for. Bounds the YAML handed to
 * the parser: block files are Ruleblend-authored and their frontmatter is a handful of lines, so a
 * file claiming megabytes of it is malformed (or came from an untrusted archive), not just large.
 */
private const val MAX_FRONTMATTER_LINES = 200

/**
 * Assistants that could host a subagent before per-assistant variants existed. A library written
 * back then holds one shared `model`, which is spread over exactly these ids on the next read so
 * that installing the subagent keeps writing the value it used to write. The list is frozen: a
 * newer assistant never appears in a file that predates it.
 */
private val LEGACY_VARIANT_AGENT_IDS = listOf("claude-code", "codex", "kimi-code")

/** Serializes/parses block files: YAML frontmatter + markdown body. */
object BlockFile {

    fun serialize(block: Block): String = buildString {
        val meta = BlockMeta(
            name = block.name,
            description = block.description,
            version = block.version,
            type = block.type,
            favorite = block.favorite,
            variants = block.variants.mapValues { (_, variant) -> variant.fields },
            heading = block.heading,
            headingLevel = block.headingLevel,
            source = block.source,
            forkedFrom = block.forkedFrom,
        )
        appendLine(DELIMITER)
        appendLine(Yaml.default.encodeToString(BlockMeta.serializer(), meta).trimEnd())
        appendLine(DELIMITER)
        appendLine()
        append(block.content)
    }

    fun parse(id: String, text: String): Block {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split("\n")
        require(lines.firstOrNull()?.trim() == DELIMITER) { "Block '$id': missing frontmatter" }
        val end = lines.drop(1).take(MAX_FRONTMATTER_LINES).indexOfFirst { it.trim() == DELIMITER }
        require(end >= 0) { "Block '$id': unterminated frontmatter" }

        val meta = Yaml.default.decodeFromString(
            BlockMeta.serializer(),
            lines.subList(1, end + 1).joinToString("\n"),
        )
        val body = lines.drop(end + 2).joinToString("\n").removePrefix("\n")
        return Block(
            id = id,
            name = meta.name,
            description = meta.description,
            version = meta.version,
            type = meta.type,
            content = body,
            favorite = meta.favorite,
            variants = meta.resolveVariants(),
            heading = meta.heading,
            headingLevel = meta.headingLevel,
            source = meta.source,
            forkedFrom = meta.forkedFrom,
        )
    }
}

/**
 * Stored variants, or the pre-variant `model` spread over the assistants that could have consumed
 * it. A file carrying both is already migrated: the explicit variants win.
 */
private fun BlockMeta.resolveVariants(): Map<String, SubagentVariant> {
    if (variants.isNotEmpty()) return variants.mapValues { (_, fields) -> SubagentVariant(fields) }
    val legacy = model?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
    if (type != BlockType.SUBAGENT) return emptyMap()
    return LEGACY_VARIANT_AGENT_IDS.associateWith { SubagentVariant(mapOf(SUBAGENT_FIELD_MODEL to legacy)) }
}
