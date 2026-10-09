package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.McpServerConfig
import java.nio.file.Path

/**
 * Aggregated status of an MCP block in a target. [conflict] flags an entry with our name that
 * Ruleblend never recorded and whose content differs — likely user-owned; installing over it needs
 * an explicit confirmation.
 */
data class McpStatus(val status: InstallStatus, val conflict: Boolean)

/** One MCP entry discovered in an agent config file Ruleblend manages. */
data class McpFileEntry(
    val agentId: String,
    val file: Path,
    val name: String,
    /** Null when the config format is not one Ruleblend can take under management. */
    val config: McpServerConfig?,
    /** The entry text as it appears in the agent's native config format. */
    val text: String,
)

/**
 * What a safe write did, or why it did nothing. Returned instead of thrown: a bundle-level action
 * touches several entries, and one protected entry must not abort the rest or pass unreported.
 */
enum class McpWrite {
    /** The target's config files were changed. */
    DONE,

    /** Nothing to change: no agent of the target runs this config, or the entry is not there. */
    NOT_APPLICABLE,

    /** An entry with this name is there and Ruleblend never wrote it. */
    FOREIGN,

    /** Ruleblend wrote it and it was edited by hand afterwards. */
    MODIFIED,
}

/**
 * Writing MCP blocks into agent config files, as the use-case layer needs it.
 *
 * The implementation lives in `mcp` together with the per-agent connectors, but the use cases are
 * `core`: this interface is what lets them decide about an MCP object without `core` learning how
 * an agent's config file is shaped. A facade with no MCP support at all passes [None].
 */
interface McpBlockService {

    /** True when [target] covers at least one agent with an MCP installer. */
    fun appliesTo(target: Target): Boolean

    /** MCP entries found at the exact config addresses this target's agents read. */
    fun entries(target: Target): List<McpFileEntry> = emptyList()

    /** False when no agent of [target] can run [block] — invalid config, or e.g. http on Codex only. */
    fun installable(target: Target, block: Block): Boolean

    /** Worst status across the target's agents, or null when installed nowhere. */
    fun status(target: Target, block: Block): McpStatus?

    /** Writes [block] unless a foreign or hand-edited copy protects it. */
    fun install(target: Target, block: Block): McpWrite

    /** The same guarded write with an explicit group or profile origin. */
    fun install(target: Target, block: Block, origin: String?): McpWrite = install(target, block)

    /** Takes [block] out under the same protection [install] writes under. */
    fun remove(target: Target, block: Block): McpWrite

    /** Writes [block] over whatever is there; the caller has already decided about the local copy. */
    fun forceInstall(target: Target, block: Block)

    /**
     * Same forced write, recording where the entry came from for group removal and profile
     * reconciliation. A null [origin] updates the entry without re-owning it: the recorded one stays.
     */
    fun forceInstall(target: Target, block: Block, origin: String?) = forceInstall(target, block)

    /** Removes by id, whatever the entry holds; the caller has already decided about the local copy. */
    fun forceRemove(target: Target, blockId: String)

    /**
     * Removes [blockId] when the library block it came from is gone, deciding against the config
     * Ruleblend recorded rather than against a library body that no longer exists.
     *
     * The default refuses: an implementation that keeps no record of what it wrote cannot tell its
     * own entry from someone else's, and leaving it in place is the only answer that loses nothing.
     */
    fun removeOrphan(target: Target, blockId: String): OrphanRemoval = OrphanRemoval.PROTECTED

    /**
     * The target's own copy of [block] in the library's serialization. Without [agentId] a target
     * whose agents drifted apart is refused rather than picked for: one library body cannot carry
     * two different local edits.
     */
    fun localChange(target: Target, block: Block, agentId: String? = null): String

    /** Re-reads the local copy, checks it still equals [expected], then writes [saved] back. */
    fun acceptLocalChange(target: Target, saved: Block, expected: String, agentId: String? = null)

    /** Origins recorded for entries Ruleblend owns in [target], including base (`null`) entries. */
    fun installedOrigins(target: Target): Map<String, String?> = emptyMap()

    companion object {
        /** Used where MCP is out of scope: the MCP facade itself, headless tooling, catalog tests. */
        val None: McpBlockService = object : McpBlockService {
            override fun appliesTo(target: Target) = false

            override fun installable(target: Target, block: Block) = false

            override fun status(target: Target, block: Block): McpStatus? = null

            override fun install(target: Target, block: Block) = McpWrite.NOT_APPLICABLE

            override fun remove(target: Target, block: Block) = McpWrite.NOT_APPLICABLE

            override fun forceInstall(target: Target, block: Block) = unsupported()

            override fun forceRemove(target: Target, blockId: String) = unsupported()

            override fun localChange(target: Target, block: Block, agentId: String?) = unsupported()

            override fun acceptLocalChange(target: Target, saved: Block, expected: String, agentId: String?) =
                unsupported()

            private fun unsupported(): Nothing =
                throw UnsupportedOperationException("MCP configs are installed from the Ruleblend app")
        }
    }
}
