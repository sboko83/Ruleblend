package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.FileIdentity
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/** Aggregated state of one library subagent across the supported agents in a target. */
data class SubagentInstallStatus(val status: InstallStatus, val conflict: Boolean)

/** One standalone definition file discovered at an agent directory Ruleblend manages. */
data class SubagentFileEntry(
    val agentId: String,
    val path: Path,
    val id: String,
    val meta: SubagentDraft?,
    val text: String,
)

/** A subagent entry is identified by its physical address, not by an agent-local id. */
fun subagentPlaceEntryKey(file: Path): String = "subagent:${file.pathIdentity()}"

/**
 * Owns standalone subagent-definition files, which cannot carry Ruleblend markers. The sidecar
 * stores the SHA-256 of exact rendered bytes, so a hand edit is distinguishable from a library
 * change without parsing agent-specific Markdown or TOML back into a block.
 */
class SubagentInstallService(
    private val state: SubagentInstallStateStore,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) {

    fun appliesTo(target: Target): Boolean = destinations(target, "placeholder").isNotEmpty()

    /** Agents covered by [target] but unable to host a safe file-backed definition. */
    fun unsupportedAgents(target: Target): List<String> = agentsOf(target)
        .filter { it.subagentSupport == SubagentSupport.UNSUPPORTED || subagentFile(target, it, "placeholder") == null }
        .map { it.id }

    /**
     * Writes the current render, refusing a foreign or manually edited file unless overridden.
     * A null [origin] updates a copy without re-owning it: the origin already recorded stays.
     */
    fun install(target: Target, subagent: Block, overwrite: Boolean = false, origin: String? = null) = mutate(target, subagent) {
        val key = skillTargetKey(target)
        val destinations = destinations(target, subagent.id)
        require(destinations.isNotEmpty()) { "Target '${target.name}' has no agent with subagent support" }

        val preflight = destinations.associateWith { destination ->
            val installed = fileFingerprint(destination.file)
            val record = state.find(key, destination.agent.id, subagent.id)
            val renderedFingerprint = subagentFingerprint(renderedBytes(destination.agent, subagent))
            val safe = installed == null || installed == renderedFingerprint || installed == record?.fingerprint
            require(overwrite || safe) {
                "${destination.file} was not written by Ruleblend or was edited after adoption"
            }
            installed
        }
        allOrNothing(destinations.map { it.file } + state.file) {
            destinations.forEach { destination ->
                val before = preflight.getValue(destination)
                val rendered = renderedBytes(destination.agent, subagent)
                val renderedFingerprint = subagentFingerprint(rendered)
                check(fileFingerprint(destination.file) == before) {
                    "${destination.file} was written outside Ruleblend while the subagent was being installed"
                }
                val identity = fileIdentity(destination.file)
                check(AtomicWrite.writeIfUnchanged(destination.file, rendered, identity)) {
                    "${destination.file} was written outside Ruleblend while the subagent was being installed"
                }
                val kept = origin ?: state.find(key, destination.agent.id, subagent.id)?.origin
                state.record(SubagentInstallRecord(key, destination.agent.id, subagent.id, renderedFingerprint, kept))
            }
        }
    }

    /** Removes only a file whose bytes are still the accepted copy. */
    fun remove(target: Target, subagent: Block) = mutate(target, subagent) {
        val key = skillTargetKey(target)
        val destinations = destinations(target, subagent.id)
        allOrNothing(destinations.map { it.file } + state.file) {
            destinations.forEach { destination ->
                val installed = fileFingerprint(destination.file)
                val record = state.find(key, destination.agent.id, subagent.id)
                val renderedFingerprint = subagentFingerprint(renderedBytes(destination.agent, subagent))
                if (installed != null) {
                    requireNotNull(record) { "${destination.file} is not managed by Ruleblend; Ruleblend left it in place" }
                    require(installed == renderedFingerprint || installed == record.fingerprint) {
                        "${destination.file} was edited after installation; Ruleblend left it in place"
                    }
                    Files.deleteIfExists(destination.file)
                }
                state.remove(key, destination.agent.id, subagent.id)
            }
        }
    }

    /**
     * Takes existing foreign definition files into ownership without replacing their bytes. Their
     * next status is SYNCED when they already say what the library render says, UPDATE_AVAILABLE
     * otherwise; a later manual edit is MODIFIED again.
     */
    fun adopt(target: Target, subagent: Block) = mutate(target, subagent) {
        requireSubagent(subagent)
        val key = skillTargetKey(target)
        val destinations = destinations(target, subagent.id)
        require(destinations.isNotEmpty()) { "Target '${target.name}' has no agent with subagent support" }
        allOrNothing(listOf(state.file)) {
            destinations.forEach { destination ->
                val fingerprint = requireNotNull(fileFingerprint(destination.file)) {
                    "${destination.file} does not exist to adopt"
                }
                require(!fingerprint.startsWith(UNSAFE_PREFIX)) {
                    "${destination.file} is not a regular file and cannot be adopted"
                }
                state.record(
                    SubagentInstallRecord(
                        key,
                        destination.agent.id,
                        subagent.id,
                        fingerprint,
                        equivalentRender = equivalentRender(destination.agent, subagent, destination.file),
                    ),
                )
            }
        }
    }

    /**
     * Records one discovered definition under an already saved library block without changing its
     * bytes. A foreign file may retain a different filename when its natural id is taken in the
     * library, so the sidecar keeps that physical stem beside the library id.
     */
    fun takeOwnership(target: Target, entry: SubagentFileEntry, subagent: Block) = coordinator.mutate(entry.path, state.file) {
        requireSubagent(subagent)
        requireNotNull(entry.meta) { "${entry.path} has no recognized subagent format" }
        val root = subagentDirectories(target).firstOrNull { candidate ->
            candidate.agent.id == entry.agentId && candidate.path == entry.path.parent?.normalize()
        } ?: error("${entry.path} is no longer a subagent definition of this target")
        val fingerprint = requireNotNull(fileFingerprint(entry.path)) { "${entry.path} does not exist to adopt" }
        require(!fingerprint.startsWith(UNSAFE_PREFIX)) { "${entry.path} is not a regular file and cannot be adopted" }
        state.record(
            SubagentInstallRecord(
                targetKey = skillTargetKey(target),
                agentId = root.agent.id,
                subagentId = subagent.id,
                fingerprint = fingerprint,
                fileName = entry.id,
                equivalentRender = equivalentRender(root.agent, subagent, entry.path),
            ),
        )
    }

    /**
     * Stops managing [subagentId] in [target]: its records go, its definition files stay byte for
     * byte and are foreign from then on. [takeOwnership] is the way back.
     */
    fun release(target: Target, subagentId: String) = mutate(target, subagentId) {
        val key = skillTargetKey(target)
        require(state.all().any { it.targetKey == key && it.subagentId == subagentId }) {
            "Subagent '$subagentId' is not managed in '${target.name}'"
        }
        state.removeAll(key, subagentId)
    }

    /**
     * Removes the definition of [subagentId] when the library block it came from is gone, so there
     * is nothing left to render and compare the file with. The recorded fingerprint is then the
     * only description of what Ruleblend wrote, and a file that still matches it may go.
     */
    fun removeOrphan(target: Target, subagentId: String): OrphanRemoval {
        val destinations = orphanDestinations(target, subagentId)
        return coordinator.mutate(*(destinations.map { it.file } + state.file).toTypedArray()) {
            val key = skillTargetKey(target)
            val files = destinations.map { destination ->
                Triple(destination, fileFingerprint(destination.file), state.find(key, destination.agent.id, subagentId))
            }
            if (files.any { (_, installed, record) -> installed != null && installed != record?.fingerprint }) {
                return@mutate OrphanRemoval.PROTECTED
            }
            allOrNothing(files.map { it.first.file } + state.file) {
                files.forEach { (destination, installed, _) ->
                    if (installed != null) Files.deleteIfExists(destination.file)
                    state.remove(key, destination.agent.id, subagentId)
                }
            }
            if (files.any { it.second != null }) OrphanRemoval.REMOVED else OrphanRemoval.ABSENT
        }
    }

    /** Worst status across supported agents, or null when no supported agent has the file. */
    fun status(target: Target, subagent: Block): SubagentInstallStatus? {
        requireSubagent(subagent)
        val destinations = destinations(target, subagent.id)
        if (destinations.isEmpty()) return null
        val key = skillTargetKey(target)
        val states = destinations.map { destination ->
            subagentFileStatus(
                installed = fileFingerprint(destination.file),
                recorded = state.find(key, destination.agent.id, subagent.id),
                renderedFingerprint = subagentFingerprint(renderedBytes(destination.agent, subagent)),
            )
        }
        val present = states.mapNotNull { it.status }
        if (present.isEmpty()) return null
        val status = if (present.size < destinations.size) InstallStatus.UPDATE_AVAILABLE else present.maxBy { it.severity() }
        return SubagentInstallStatus(status, conflict = states.any { it.conflict })
    }

    /** Definition files the agents of [target] read, in agent order. */
    fun files(target: Target, subagentId: String): List<Path> = destinations(target, subagentId).map { it.file }

    /** Definition directories the agents of [target] read, in agent order. */
    fun directories(target: Target): List<Path> = subagentDirectories(target).map { it.path }

    /** Lists direct definition files under every subagent directory [target]'s agents read. */
    fun entries(target: Target): List<SubagentFileEntry> = subagentDirectories(target).flatMap { root ->
        if (Files.isSymbolicLink(root.path) || !Files.isDirectory(root.path, NOFOLLOW_LINKS)) return@flatMap emptyList()
        val files = runCatching {
            Files.list(root.path).use { paths ->
                paths
                    .filter { file ->
                        !Files.isSymbolicLink(file) &&
                            Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                            file.fileName.toString().endsWith(".${root.format.extension}")
                    }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        files.mapNotNull { file ->
            val text = runCatching { Files.readString(file) }.getOrNull() ?: return@mapNotNull null
            val id = file.fileName.toString().removeSuffix(".${root.format.extension}")
            if (id.isEmpty()) return@mapNotNull null
            val parsed = runCatching { root.format.parse(text) }.getOrNull()
            SubagentFileEntry(
                agentId = root.agent.id,
                path = file,
                id = id,
                meta = parsed?.copy(name = parsed.name?.takeIf { it.isNotBlank() } ?: id),
                text = text,
            )
        }
    }

    /** Classifies every subagent definition [entries] finds for [target]. */
    fun classifiedEntries(
        target: Target,
        librarySubagentIds: Set<String>,
        ignoredEntryKeys: Set<String> = emptySet(),
    ): List<PlaceEntry<SubagentFileEntry, String>> {
        val key = skillTargetKey(target)
        val roots = subagentDirectories(target).associate { it.agent.id to it }
        val sidecarEntries = state.all()
            .asSequence()
            .filter { it.targetKey == key }
            .mapNotNull { record ->
                roots[record.agentId]?.let { root ->
                    PlaceSidecarEntry(
                        subagentPlaceEntryKey(root.path.resolve("${record.fileName ?: record.subagentId}.${root.format.extension}")),
                        record.subagentId,
                    )
                }
            }
            .toList()
        return classifyPlaceEntries(
            entries = entries(target),
            sidecarEntries = sidecarEntries,
            libraryIds = librarySubagentIds,
            ignoredKeys = ignoredEntryKeys,
            keyOf = { entry -> subagentPlaceEntryKey(entry.path) },
            libraryIdOf = SubagentFileEntry::id,
        )
    }

    /** Origins recorded for definitions Ruleblend owns in [target], including base (`null`) entries. */
    fun installedOrigins(target: Target): Map<String, String?> {
        val key = skillTargetKey(target)
        val agents = agentsOf(target).map { it.id }.toSet()
        return state.all()
            .filter { it.targetKey == key && it.agentId in agents }
            .associate { it.subagentId to it.origin }
    }

    private fun <T> mutate(target: Target, subagent: Block, body: () -> T): T {
        requireSubagent(subagent)
        return mutate(target, subagent.id, body)
    }

    private fun <T> mutate(target: Target, subagentId: String, body: () -> T): T =
        coordinator.mutate(*(destinations(target, subagentId).map { it.file } + state.file).toTypedArray(), body = body)

    private data class Destination(val agent: AgentAdapter, val file: Path)

    /** Follows the recorded filename, which can differ from the library id after adoption. */
    private fun orphanDestinations(target: Target, subagentId: String): List<Destination> {
        val roots = subagentDirectories(target).associateBy { it.agent.id }
        return state.all()
            .asSequence()
            .filter { it.targetKey == skillTargetKey(target) && it.subagentId == subagentId }
            .mapNotNull { record ->
                roots[record.agentId]?.let { root ->
                    Destination(root.agent, root.path.resolve("${record.fileName ?: record.subagentId}.${root.format.extension}"))
                }
            }
            .toList()
    }

    private data class SubagentDirectory(
        val agent: AgentAdapter,
        val path: Path,
        val format: SubagentFormat,
    )

    private fun subagentDirectories(target: Target): List<SubagentDirectory> = agentsOf(target).mapNotNull { agent ->
        if (agent.subagentSupport == SubagentSupport.UNSUPPORTED) return@mapNotNull null
        val directory = when (target) {
            is AgentGlobalTarget -> agent.globalSubagentDirectory()
            is ProjectTarget -> agent.projectSubagentDirectory(target.dir)
        } ?: return@mapNotNull null
        SubagentDirectory(agent, directory.normalize(), requireNotNull(agent.subagentFormat))
    }.distinctBy { it.path }

    private fun destinations(target: Target, subagentId: String): List<Destination> = agentsOf(target)
        .mapNotNull { agent -> subagentFile(target, agent, subagentId)?.let { Destination(agent, it.normalize()) } }
        .distinctBy { it.file }

    private fun subagentFile(target: Target, agent: AgentAdapter, subagentId: String): Path? {
        if (agent.subagentSupport == SubagentSupport.UNSUPPORTED) return null
        val fileName = state.find(skillTargetKey(target), agent.id, subagentId)?.fileName ?: subagentId
        return when (target) {
            is AgentGlobalTarget -> agent.globalSubagentFile(fileName)
            is ProjectTarget -> agent.projectSubagentFile(target.dir, fileName)
        }
    }

    private fun agentsOf(target: Target): List<AgentAdapter> = when (target) {
        is AgentGlobalTarget -> listOf(target.agent)
        is ProjectTarget -> target.agents
    }

    private fun allOrNothing(files: List<Path>, body: () -> Unit) {
        val rollback = FileRollback(files.distinct())
        runCatching(body).onFailure { failure ->
            val stranded = rollback.restoreAll()
            if (stranded.isEmpty()) throw failure
            throw IllegalStateException(
                "${failure.message ?: failure.toString()}; still changed in ${stranded.joinToString()}",
                failure,
            )
        }
    }
}

internal data class SubagentFileState(val status: InstallStatus?, val conflict: Boolean)

/** Comparison of bytes, never a parse of an agent-owned file. */
internal fun subagentFileStatus(
    installed: String?,
    recorded: SubagentInstallRecord?,
    renderedFingerprint: String,
): SubagentFileState = when {
    installed == null -> SubagentFileState(null, conflict = false)
    // The record is what makes a file ours: without one it is foreign, even when its bytes happen to
    // equal the render, so letting go of a definition cannot leave it looking managed.
    recorded == null -> SubagentFileState(InstallStatus.MODIFIED, conflict = true)
    installed == renderedFingerprint -> SubagentFileState(InstallStatus.SYNCED, conflict = false)
    installed == recorded.fingerprint && recorded.equivalentRender == renderedFingerprint ->
        SubagentFileState(InstallStatus.SYNCED, conflict = false)
    installed == recorded.fingerprint -> SubagentFileState(InstallStatus.UPDATE_AVAILABLE, conflict = false)
    else -> SubagentFileState(InstallStatus.MODIFIED, conflict = false)
}

/** SHA-256 of exactly the bytes [SubagentFormat.render] produced. */
internal fun subagentFingerprint(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun renderedBytes(agent: AgentAdapter, subagent: Block): ByteArray {
    requireSubagent(subagent)
    val format = requireNotNull(agent.subagentFormat) { "${agent.name} has no subagent format" }
    return format.render(subagent, subagent.variant(agent.id)).encodeToByteArray()
}

/**
 * The render fingerprint of [subagent] when [file] parses to the same definition — the one moment
 * an agent-owned file is compared by meaning, so a hand-written layout (field order, quoting) is not
 * reported as an update the user never asked for. Null when anything differs or fails to parse.
 */
private fun equivalentRender(agent: AgentAdapter, subagent: Block, file: Path): String? {
    val format = agent.subagentFormat ?: return null
    val rendered = renderedBytes(agent, subagent)
    val installed = runCatching { Files.readString(file) }.getOrNull() ?: return null
    val same = runCatching {
        val parsed = format.parse(installed)
        parsed != null && parsed == format.parse(rendered.decodeToString())
    }.getOrDefault(false)
    return if (same) subagentFingerprint(rendered) else null
}

private fun requireSubagent(block: Block) {
    require(block.type == BlockType.SUBAGENT) { "Expected a subagent block, got ${block.type}" }
}

private fun fileFingerprint(file: Path): String? {
    if (!file.exists()) return null
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) return "$UNSAFE_PREFIX${file.toAbsolutePath()}"
    return subagentFingerprint(file.readBytes())
}

private fun fileIdentity(file: Path): FileIdentity? = if (file.exists()) FileIdentity.of(file) else null

private fun InstallStatus.severity(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}

private const val UNSAFE_PREFIX = "unsafe:"
