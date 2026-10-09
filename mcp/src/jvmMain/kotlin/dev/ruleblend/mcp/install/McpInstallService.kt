package dev.ruleblend.mcp.install

import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.integration.FileRollback
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpFileEntry
import dev.ruleblend.core.integration.McpStatus
import dev.ruleblend.core.integration.McpWrite
import dev.ruleblend.core.integration.OrphanRemoval
import dev.ruleblend.core.integration.PlaceEntry
import dev.ruleblend.core.integration.PlaceSidecarEntry
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.classifyPlaceEntries
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.model.RESERVED_MCP_SERVER_NAME
import dev.ruleblend.core.model.validationErrors
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path

/** One agent's copy of an entry, exactly as that agent's config file holds it. */
data class McpInstalledCopy(val agentId: String, val config: McpServerConfig) {
    /** The copy in the library's own serialization, so it and the library body read against each other. */
    val text: String get() = McpConfigCodec.serialize(config)
}

/** Installs library MCP blocks into agent MCP configs and reads their state back. */
class McpInstallService(
    private val installers: List<McpAgentInstaller>,
    private val state: McpStateStore,
    /** Holds an entry write and its state record together; see [TargetMutationCoordinator]. */
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpBlockService {

    /** True when [target] covers at least one agent with an MCP installer. */
    override fun appliesTo(target: Target): Boolean = installersFor(target).isNotEmpty()

    /**
     * Lists native entries without changing either config or sidecar state. A shared config file is
     * read once and represented once, under the first agent that reads it.
     */
    override fun entries(target: Target): List<McpFileEntry> = FileReadScope.reading {
        installersFor(target)
            .mapNotNull { installer -> installer.configFile(target)?.let { file -> installer to file } }
            .distinctBy { (_, file) -> file.normalize() }
            .flatMap { (installer, file) ->
                val source = FileReadScope.text(file)
                runCatching { installer.names(source) }.getOrDefault(emptyList()).map { name ->
                    McpFileEntry(
                        agentId = installer.agentId,
                        file = file,
                        name = name,
                        config = runCatching { installer.installed(target, name) }.getOrNull(),
                        text = runCatching { installer.entryText(source, name) }.getOrNull().orEmpty(),
                    )
                }
            }
    }

    /**
     * Classifies entries by their physical config address. A server name alone is not an address:
     * one project can give two agents differently named config files containing the same server.
     */
    fun classifiedEntries(
        target: Target,
        libraryMcpIds: Set<String>,
        ignoredEntryKeys: Set<String> = emptySet(),
    ): List<PlaceEntry<McpFileEntry, String>> {
        val key = targetKey(target)
        val filesByAgent = installersFor(target)
            .mapNotNull { installer -> installer.configFile(target)?.let { installer.agentId to it } }
            .toMap()
        val sidecarEntries = state.all()
            .asSequence()
            .filter { it.targetKey == key }
            .mapNotNull { record ->
                filesByAgent[record.agentId]?.let { file ->
                    PlaceSidecarEntry(mcpPlaceEntryKey(file, record.entryName ?: record.blockId), record.blockId)
                }
            }
            .toList()
        return classifyPlaceEntries(
            entries = entries(target),
            sidecarEntries = sidecarEntries,
            libraryIds = libraryMcpIds,
            ignoredKeys = ignoredEntryKeys,
            keyOf = { entry -> mcpPlaceEntryKey(entry.file, entry.name) },
            libraryIdOf = McpFileEntry::name,
        )
    }

    /** MCP config files [target]'s agents read, in agent order; a file is listed once. */
    fun configFiles(target: Target): List<Path> =
        installersFor(target).mapNotNull { it.configFile(target) }.distinct()

    /** Agent ids of [target] that have no MCP installer and are skipped entirely. */
    fun unsupportedAgents(target: Target): List<String> =
        agentIds(target) - installersFor(target).map { it.agentId }.toSet()

    /** Agent ids of a project [target] that read their project MCP config only when trusted. */
    fun agentsRequiringTrust(target: Target): List<String> =
        if (target is ProjectTarget) installersFor(target).filter { it.requiresProjectTrust }.map { it.agentId }
        else emptyList()

    /** Installers of [target] that cannot run [block]'s config. */
    fun unsupportedFor(target: Target, block: Block): List<String> {
        val config = McpConfigCodec.parse(block.content).getOrNull() ?: return emptyList()
        return installersFor(target).filterNot { it.supports(config) }.map { it.agentId }
    }

    /** True when at least one agent of [target] can run [block]'s config. */
    override fun installable(target: Target, block: Block): Boolean {
        val config = McpConfigCodec.parse(block.content).getOrNull() ?: return false
        return config.validationErrors().isEmpty() &&
            block.id != RESERVED_MCP_SERVER_NAME &&
            installersFor(target).any { it.supports(config) }
    }

    /**
     * Installs [block] only when the target holds nothing of this name, or holds Ruleblend's own
     * unedited copy. A foreign or hand-edited entry is reported, never overwritten: a decision about
     * a bundle is not a decision to throw away that particular edit. [forceInstall] is the other half.
     */
    override fun install(target: Target, block: Block): McpWrite = install(target, block, origin = null)

    override fun install(target: Target, block: Block, origin: String?): McpWrite = mutate(target) {
        if (!installable(target, block)) return@mutate McpWrite.NOT_APPLICABLE
        protectedBy(target, block)?.let { return@mutate it }
        forceInstall(target, block, origin)
        McpWrite.DONE
    }

    /**
     * Removes [block] under the same protection as [install]: an entry someone edited by hand stays,
     * because the user asked to take the bundle out, not to discard that edit.
     */
    override fun remove(target: Target, block: Block): McpWrite = mutate(target) {
        if (status(target, block) == null) return@mutate McpWrite.NOT_APPLICABLE
        protectedBy(target, block)?.let { return@mutate it }
        forceRemove(target, block.id)
        McpWrite.DONE
    }

    /** Null when the target's copy may be written over, the reason it may not otherwise. */
    private fun protectedBy(target: Target, block: Block): McpWrite? {
        val status = status(target, block) ?: return null
        return when {
            status.conflict -> McpWrite.FOREIGN
            status.status == InstallStatus.MODIFIED -> McpWrite.MODIFIED
            else -> null
        }
    }

    /**
     * Writes over whatever the target holds. Only for a path that carries the user's decision about
     * this entry — the per-row action in Place, or taking its local edit into the library.
     */
    override fun forceInstall(target: Target, block: Block) = forceInstall(target, block, origin = null)

    override fun forceInstall(target: Target, block: Block, origin: String?): Unit = mutate(target) {
        val config = McpConfigCodec.parse(block.content).getOrElse {
            throw IllegalArgumentException("Block ${block.id} has no valid MCP config", it)
        }
        require(config.validationErrors().isEmpty()) { "Block ${block.id} has an invalid MCP config" }
        require(block.id != RESERVED_MCP_SERVER_NAME) { "'$RESERVED_MCP_SERVER_NAME' is reserved" }
        val key = targetKey(target)
        allOrNothing(target) {
            installersFor(target).filter { it.supports(config) }.forEach { installer ->
                val entryName = entryName(key, installer, block.id)
                installer.install(target, entryName, config)
                state.record(
                    McpInstallRecord(
                        key,
                        installer.agentId,
                        block.id,
                        block.version,
                        config,
                        origin ?: state.find(key, installer.agentId, block.id)?.origin,
                        entryName.takeIf { it != block.id },
                    ),
                )
            }
        }
    }

    /**
     * Records one discovered server as managed without rewriting its native config entry. A library
     * id can differ from the server key after a collision, so that physical key stays in the sidecar.
     */
    fun takeOwnership(target: Target, entry: McpFileEntry, block: Block) = coordinator.mutate(entry.file, state.file) {
        require(block.type == BlockType.MCP) { "Block ${block.id} is not an MCP server" }
        val config = requireNotNull(entry.config) { "${entry.name} in ${entry.file} has no supported MCP config" }
        val current = entries(target).firstOrNull {
            it.agentId == entry.agentId && it.file.normalize() == entry.file.normalize() && it.name == entry.name
        }
        requireNotNull(current) { "${entry.name} is no longer an MCP entry of this target" }
        require(current.config == config) { "${entry.name} changed before it could be taken under management" }
        // Every agent reading this file holds the entry, and an install would record all of them.
        // Recording one would leave the others reading a server they own as one they do not.
        val owners = installersFor(target).filter { it.configFile(target)?.normalize() == entry.file.normalize() }
        owners.forEach { installer ->
            state.record(
                McpInstallRecord(
                    targetKey = targetKey(target),
                    agentId = installer.agentId,
                    blockId = block.id,
                    version = block.version,
                    config = config,
                    entryName = entry.name.takeIf { it != block.id },
                ),
            )
        }
    }

    /**
     * Stops managing [blockId] in [target]: its records go, the config entries stay as written and
     * are foreign from then on. [takeOwnership] is the way back.
     */
    fun release(target: Target, blockId: String) = mutate(target) {
        val key = targetKey(target)
        require(state.all().any { it.targetKey == key && it.blockId == blockId }) {
            "MCP server '$blockId' is not managed in '${target.name}'"
        }
        state.remove(key, blockId)
    }

    /**
     * Deletes one discovered entry after the user explicitly confirmed its name and config file.
     * Its agent and address are rechecked so a row cannot remove a same-named server elsewhere.
     */
    fun removeForeign(target: Target, entry: McpFileEntry) = coordinator.mutate(entry.file) {
        val installer = installersFor(target).firstOrNull {
            it.agentId == entry.agentId && it.configFile(target)?.normalize() == entry.file.normalize()
        }
        requireNotNull(installer) { "${entry.file} is no longer an MCP config of this target" }
        require(entries(target).any {
            it.agentId == entry.agentId && it.file.normalize() == entry.file.normalize() && it.name == entry.name
        }) { "${entry.name} is no longer an MCP entry of this target" }
        installer.remove(target, entry.name)
    }

    /**
     * One mutation over every config file of [target] and the state file describing them. The
     * status read that decides whether a write is allowed, the write itself and the state record
     * all sit inside it, so no concurrent writer can slip between them and no record is left
     * describing an entry that has since changed.
     */
    private fun <T> mutate(target: Target, body: () -> T): T =
        coordinator.mutate(*(configFiles(target) + state.file).toTypedArray(), body = body)

    /**
     * One entry across every agent of [target], or none of them. The agents are separate config
     * files written one after another; a failure on the second would otherwise leave the first
     * holding a server the operation did not finish installing. The state file is part of the
     * snapshot, so a rolled-back write leaves no record claiming it landed. What could not be put
     * back is named in the error rather than passed off as undone.
     */
    private fun allOrNothing(target: Target, body: () -> Unit) {
        val rollback = FileRollback(configFiles(target) + state.file)
        runCatching(body).onFailure { failure ->
            val stranded = rollback.restoreAll()
            if (stranded.isEmpty()) throw failure
            throw IllegalStateException(
                "${failure.message ?: failure.toString()}; still changed in ${stranded.joinToString()}",
                failure,
            )
        }
    }

    /** Removes by id, whatever the entry holds; the caller has already decided about the local copy. */
    override fun forceRemove(target: Target, blockId: String): Unit = mutate(target) {
        allOrNothing(target) {
            val key = targetKey(target)
            installersFor(target).forEach { installer -> installer.remove(target, entryName(key, installer, blockId)) }
            state.remove(targetKey(target), blockId)
        }
    }

    /**
     * Removes [blockId] when its library block is gone, so [status] has nothing to compare against.
     * The recorded config is what Ruleblend put in the file, and it takes the library body's place
     * as the yardstick: an entry that still equals it is ours and goes, an entry that differs — or
     * that no record describes at all — is someone else's edit and stays.
     */
    override fun removeOrphan(target: Target, blockId: String): OrphanRemoval = mutate(target) {
        val key = targetKey(target)
        val entries = installersFor(target).map { installer ->
            val record = state.find(key, installer.agentId, blockId)
            installer.installed(target, record?.entryName ?: blockId) to record
        }
        when {
            entries.any { (installed, record) -> installed != null && installed != record?.config } ->
                OrphanRemoval.PROTECTED
            entries.all { (installed, _) -> installed == null } -> {
                allOrNothing(target) { state.remove(key, blockId) }
                OrphanRemoval.ABSENT
            }
            else -> {
                forceRemove(target, blockId)
                OrphanRemoval.REMOVED
            }
        }
    }

    /** Worst status across the target's agents, or null when installed nowhere. */
    override fun status(target: Target, block: Block): McpStatus? {
        val config = McpConfigCodec.parse(block.content).getOrNull() ?: return null
        val applicable = installersFor(target).filter { it.supports(config) }
        if (applicable.isEmpty()) return null
        val key = targetKey(target)
        val perAgent = applicable.map { installer ->
            val record = state.find(key, installer.agentId, block.id)
            fileStatus(
                installed = installer.installed(target, record?.entryName ?: block.id),
                recorded = record,
                library = config,
                libraryVersion = block.version,
            )
        }
        val statuses = perAgent.mapNotNull { it.status }
        if (statuses.isEmpty()) return null
        val aggregated = when {
            statuses.size < applicable.size -> InstallStatus.UPDATE_AVAILABLE
            else -> statuses.maxBy { it.severity() }
        }
        return McpStatus(aggregated, conflict = perAgent.any { it.conflict })
    }

    override fun installedOrigins(target: Target): Map<String, String?> {
        val key = targetKey(target)
        val agents = agentIds(target).toSet()
        return state.all()
            .filter { it.targetKey == key && it.agentId in agents }
            .associate { it.blockId to it.origin }
    }

    /**
     * Every copy of [block] the target's agents hold, one per agent that has it. A project target can
     * cover several agents, and they can hold different texts — which is a fact the caller has to see,
     * not one an aggregate may hide.
     */
    fun installedCopies(target: Target, block: Block): List<McpInstalledCopy> {
        val config = McpConfigCodec.parse(block.content).getOrNull() ?: return emptyList()
        return installersFor(target)
            .filter { it.supports(config) }
            .mapNotNull { installer ->
                val name = entryName(targetKey(target), installer, block.id)
                installer.installed(target, name)?.let { McpInstalledCopy(installer.agentId, it) }
            }
    }

    /**
     * The entry as the target's config files actually hold it, in the library's own serialization so
     * the two can be read against each other. A drifted copy wins over a matching one: that is the
     * copy the badge is about, and the one the reader came to see.
     */
    fun installedText(target: Target, block: Block): String? {
        val config = McpConfigCodec.parse(block.content).getOrNull() ?: return null
        val copies = installedCopies(target, block)
        val shown = copies.firstOrNull { it.config != config } ?: copies.firstOrNull() ?: return null
        return shown.text
    }

    /**
     * The one text every agent of [target] holds for [block], for taking a local edit into the
     * library. Refuses when the agents hold different texts: the library keeps one body per block, so
     * accepting one copy would publish it over the others and erase the second edit without a word.
     * `IntegrationService.localChange` holds the same contract for rules. Passing an agent id is how
     * that refusal is answered — by naming which copy to publish: the text that agent holds becomes
     * the library's next version and the others are written over, which is what picking a source means.
     */
    override fun localChange(target: Target, block: Block, agentId: String?): String {
        if (agentId != null) {
            val copy = installedCopies(target, block).firstOrNull { it.agentId == agentId }
            requireNotNull(copy) { "MCP server '${block.id}' is not installed in $agentId" }
            return copy.text
        }
        val copies = installedCopies(target, block)
        require(copies.isNotEmpty()) { "MCP server '${block.id}' is not installed in this target" }
        val texts = copies.map { it.text }
        require(texts.distinct().size == 1) {
            "MCP server '${block.id}' was edited differently in ${copies.joinToString { it.agentId }}"
        }
        return texts.first()
    }

    /**
     * Re-reads the target and writes [saved] into every applicable agent config. The re-read closes
     * the race where an editor saves again between [localChange] and the library commit. [agentId]
     * says which copy the edit was read from, and is required whenever the agents hold different
     * texts — without it the re-read refuses a drifted-apart target rather than picking for the user.
     */
    override fun acceptLocalChange(target: Target, saved: Block, expected: String, agentId: String?): Unit = mutate(target) {
        val current = localChange(target, saved, agentId)
        require(current == expected) {
            "The target changed again before the local edit could be saved"
        }
        forceInstall(target, saved)
    }

    private fun installersFor(target: Target): List<McpAgentInstaller> {
        val ids = agentIds(target)
        return installers.filter { it.agentId in ids }
    }

    private fun entryName(targetKey: String, installer: McpAgentInstaller, blockId: String): String =
        state.find(targetKey, installer.agentId, blockId)?.entryName ?: blockId

    private fun agentIds(target: Target): List<String> = when (target) {
        is AgentGlobalTarget -> listOf(target.agent.id)
        is ProjectTarget -> target.agents.map { it.id }
    }
}

internal data class FileStatus(val status: InstallStatus?, val conflict: Boolean)

/**
 * Status of one config entry. The record decides ownership: an entry without one is foreign even when
 * it equals the library, so a released entry does not read as installed. With a record, matching the
 * library is SYNCED, matching the recorded render while the library moved on is UPDATE_AVAILABLE, and
 * anything else was edited outside Ruleblend.
 */
internal fun fileStatus(
    installed: McpServerConfig?,
    recorded: McpInstallRecord?,
    library: McpServerConfig,
    libraryVersion: Int,
): FileStatus = when {
    installed == null -> FileStatus(null, conflict = false)
    recorded == null -> FileStatus(InstallStatus.MODIFIED, conflict = true)
    installed == library -> FileStatus(InstallStatus.SYNCED, conflict = false)
    installed == recorded.config && libraryVersion != recorded.version ->
        FileStatus(InstallStatus.UPDATE_AVAILABLE, conflict = false)
    else -> FileStatus(InstallStatus.MODIFIED, conflict = false)
}

/** Higher is worse; the aggregated badge must never hide a hand edit. */
private fun InstallStatus.severity(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}

/** Stable local preference key for one entry in one MCP config file. */
fun mcpPlaceEntryKey(file: Path, name: String): String = "mcp:${file.pathIdentity()}:$name"
