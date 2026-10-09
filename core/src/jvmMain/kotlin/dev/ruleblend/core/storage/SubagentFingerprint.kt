package dev.ruleblend.core.storage

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.SubagentVariant
import java.security.MessageDigest

/** Semantic definition only: upstream revision and local identity cannot announce a content update. */
fun subagentFingerprint(block: Block): String {
    require(block.type == BlockType.SUBAGENT)
    val definition = Block("definition", block.name, block.description, type = BlockType.SUBAGENT,
        content = LineEnding.LF.apply(block.content),
        variants = block.variants.filterValues { !it.isEmpty }.toSortedMap()
            .mapValues { SubagentVariant(it.value.fields.toSortedMap()) })
    return MessageDigest.getInstance("SHA-256").digest(BlockFile.serialize(definition).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
}
