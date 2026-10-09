package dev.ruleblend.core.usecase

import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.withRegionContent
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository

/**
 * Saves an edit made in a target file as the object's next library version.
 *
 * A rule carries the edit as the text inside its region; an MCP entry carries it as the config the
 * agent's file actually holds, read back in the library's own serialization. Either way the library
 * takes the local copy first and the target is written again from it, so the record matches the new
 * version and the drift is over.
 */
class AcceptLocalChange(
    private val rules: IntegrationService,
    private val mcp: McpBlockService = McpBlockService.None,
    private val repository: LibraryRepository,
) {

    /**
     * [agentId] names which copy to publish. It is required only where the target's agents were
     * edited apart: one library body cannot carry two different local edits, and picking one
     * silently would publish it over the other — so without a name that case is refused, not guessed.
     *
     * Returns the saved library version.
     */
    operator fun invoke(target: Target, block: Block, agentId: String? = null): Block {
        require(block.type == BlockType.RULE || block.type == BlockType.MCP) {
            "Only rule and MCP changes can be saved from target files"
        }
        if (block.type == BlockType.MCP) {
            val local = mcp.localChange(target, block, agentId)
            val saved = repository.saveBlock(block.copy(content = local))
            mcp.acceptLocalChange(target, saved, local, agentId)
            return saved
        }
        require(agentId == null) { "A rule region is the same text for every agent of a place" }
        val local = rules.localChange(target, block.id)
        val saved = repository.saveBlock(block.withRegionContent(local.content))
        rules.acceptLocalChange(target, saved, local)
        return saved
    }
}
