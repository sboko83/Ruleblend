package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.skillTreeFingerprint
import dev.ruleblend.core.storage.skillExecutable
import dev.ruleblend.core.storage.setSkillExecutable
import dev.ruleblend.core.storage.LocalSkillCapture
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillFrontmatter
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.parseSkillFrontmatter
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.DirectoryReplace
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Aggregated state of one library skill across every supported agent in a target. [status] is the
 * worst state among the trees that exist; a tree an agent still lacks is not drift of the others, so
 * it is reported apart, in [missingAgents].
 */
data class SkillInstallStatus(
    val status: InstallStatus,
    val conflict: Boolean,
    val missingAgents: List<String> = emptyList(),
)

/** One skill directory an agent of a target reads; [status] is null while the directory is absent. */
data class SkillDestinationStatus(
    val agentId: String,
    val directory: Path,
    val status: InstallStatus?,
    val conflict: Boolean,
)

/** The aggregate of [destinations], or null when none of them holds a tree. */
fun skillInstallStatus(destinations: List<SkillDestinationStatus>): SkillInstallStatus? {
    val present = destinations.filter { it.status != null }
    if (present.isEmpty()) return null
    return SkillInstallStatus(
        status = present.mapNotNull { it.status }.maxBy { it.severity() },
        conflict = present.any { it.conflict },
        missingAgents = destinations.filter { it.status == null }.map { it.agentId },
    )
}

/** One skill directory discovered at an agent root Ruleblend manages. */
data class SkillDirEntry(
    val path: Path,
    val id: String,
    val meta: SkillFrontmatter,
    /** `SKILL.md` as it is on disk, read once during discovery so the UI never reads it itself. */
    val text: String = "",
)

/** Stable place-local key for a skill directory: the address itself. */
fun skillPlaceEntryKey(directory: Path): String = "skill:${directory.pathIdentity()}"

/** Installs complete library skill directories without overwriting unknown or hand-edited trees. */
class SkillInstallService(
    private val state: SkillInstallStateStore,
    private val snapshotResolver: (String) -> SkillSnapshot?,
    /**
     * Every known adapter by id. A record left by an agent outside the current target still owns
     * the tree its declared root resolves to, so ownership cannot rely on the target's agents alone.
     */
    private val agentById: (String) -> AgentAdapter?,
    /** Holds the preflight, the directory writes and the state records together. */
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    /** OS home whose `.agents/skills` held agent-global copies of earlier versions. */
    private val sharedHome: Path = Path.of(System.getProperty("user.home")),
) {

    fun appliesTo(target: Target): Boolean = destinations(target, "placeholder").isNotEmpty()

    fun unsupportedAgents(target: Target): List<String> {
        val agents = agentsOf(target)
        return agents.filter { agent -> rootOf(target, agent) == null }.map { it.id }
    }

    fun install(target: Target, skill: Skill, overwrite: Boolean = false, origin: String? = null) = mutate(target, skill.id) {
        val snapshot = snapshotResolver(skill.id) ?: error("No skill with id '${skill.id}'")
        val destinations = destinations(target, skill.id)
        require(destinations.isNotEmpty()) { "Target '${target.name}' has no agent with skills support" }
        val libraryFingerprint = skillTreeFingerprint(snapshot.files)
        val executableFiles = snapshot.files.filter { it.executable }.map { it.path }
        val key = skillTargetKey(target)

        // Preflight every destination before writing the first one, so a foreign tree cannot leave
        // a multi-agent project half-updated.
        val preflight = destinations.associateWith { destination ->
            val record = state.find(key, destination.agent.id, skill.id)
            val installedModes = recordedExecutableFiles(target, destination.path) ?: executableFiles
            val installed = directoryFingerprint(destination.path, installedModes)
            val safe = installed == null || installed == libraryFingerprint || installed == record?.fingerprint
            require(overwrite || safe) {
                "${destination.path} was not written by Ruleblend or was edited after installation"
            }
            // A null origin updates a copy without re-owning it: the recorded origin stays.
            Triple(installed, record?.origin, installedModes)
        }

        // A skill can land in several agents. Each replaced tree is kept aside until the last
        // destination has landed, so a failure halfway puts the earlier agents back instead of
        // leaving the project holding the new skill in one place and the old one in another.
        val stateBefore = FileRollback(listOf(state.file))
        val replaced = mutableListOf<Path>()
        try {
            destinations.forEach { destination ->
                // What the preflight cleared, re-checked against the tree as it stands: an install that
                // writes several agents leaves a window an external editor could still write into.
                val expected = preflight.getValue(destination).first
                val beforeMove = {
                    check(overwrite || directoryFingerprint(destination.path, preflight.getValue(destination).third) == expected) {
                        "${destination.path} was written outside Ruleblend while the skill was being installed"
                    }
                }
                replaceDirectory(destination.path, snapshot.files, beforeMove) {
                    state.record(
                        SkillInstallRecord(
                            key,
                            destination.agent.id,
                            skill.id,
                            libraryFingerprint,
                            directoryName = destination.path.name,
                            origin = origin ?: preflight.getValue(destination).second,
                            executableFiles = executableFiles,
                        ),
                    )
                }
                replaced.add(destination.path)
            }
        } catch (failure: Throwable) {
            val stranded = replaced.reversed().mapNotNull { path -> path.takeUnless { undo(it) } } +
                stateBefore.restoreAll()
            replaced.filterNot { it in stranded }.forEach { path ->
                runCatching { deleteTree(rollbackOf(path)) }.onFailure(failure::addSuppressed)
            }
            if (stranded.isEmpty()) throw failure
            throw IllegalStateException(
                "${failure.message ?: failure.toString()}; still changed in ${stranded.joinToString()}",
                failure,
            )
        }
        replaced.forEach { path -> deleteTree(rollbackOf(path)) }
    }

    /** Removes only an unchanged Ruleblend copy; foreign and hand-edited directories are retained. */
    fun remove(target: Target, skill: Skill) = mutate(target, skill.id) {
        val key = skillTargetKey(target)
        val files = snapshotResolver(skill.id)?.files
        val libraryFingerprint = files?.let(::skillTreeFingerprint)
        val executableFiles = files.orEmpty().filter { it.executable }.map { it.path }
        destinations(target, skill.id).forEach { destination ->
            val record = state.find(key, destination.agent.id, skill.id)
            val installed = directoryFingerprint(destination.path, recordedExecutableFiles(target, destination.path) ?: executableFiles)
            if (installed != null) {
                requireNotNull(record) { "${destination.path} is not managed by Ruleblend; Ruleblend left it in place" }
                require(installed == record.fingerprint || installed == libraryFingerprint) {
                    "${destination.path} was edited after installation; Ruleblend left it in place"
                }
                deleteTree(destination.path)
            }
            state.remove(key, destination.agent.id, skill.id)
        }
    }

    /**
     * Removes the copy of [skillId] when the library skill it came from is gone, so there is no
     * tree left to compare with. The recorded fingerprint is then the only description of what
     * Ruleblend wrote, and a directory that still matches it may go; anything else stays.
     */
    fun removeOrphan(target: Target, skillId: String): OrphanRemoval {
        val destinations = orphanDestinations(target, skillId)
        return coordinator.mutate(*destinations.map { it.path }.plusElement(state.file).toTypedArray()) {
            val key = skillTargetKey(target)
            val trees = destinations.map { destination ->
                val record = state.find(key, destination.agent.id, skillId)
                Triple(destination, directoryFingerprint(destination.path, recordedExecutableFiles(target, destination.path).orEmpty()), record)
            }
            if (trees.any { (_, installed, record) -> installed != null && installed != record?.fingerprint }) {
                return@mutate OrphanRemoval.PROTECTED
            }
            trees.forEach { (destination, installed, _) ->
                if (installed != null) deleteTree(destination.path)
                state.remove(key, destination.agent.id, skillId)
            }
            if (trees.any { it.second != null }) OrphanRemoval.REMOVED else OrphanRemoval.ABSENT
        }
    }

    /**
     * Deletes one directory discovered outside Ruleblend after the user explicitly confirmed it.
     * The address is checked against this target again, so an arbitrary path cannot be handed to
     * the tree removal path from the UI.
     */
    fun removeForeign(target: Target, entry: SkillDirEntry) = coordinator.mutate(entry.path) {
        val current = entries(target).firstOrNull {
            it.path.normalize() == entry.path.normalize() && it.id == entry.id
        }
        requireNotNull(current) { "${entry.path} is no longer a skill directory of this target" }
        deleteTree(current.path)
    }

    /** Worst status across the trees that exist, or `null` when the skill is installed nowhere. */
    fun status(target: Target, skill: Skill): SkillInstallStatus? =
        skillInstallStatus(destinationStatuses(target, skill))

    /** The state of each skill directory the agents of [target] read, in agent order. */
    fun destinationStatuses(target: Target, skill: Skill): List<SkillDestinationStatus> {
        val snapshot = snapshotResolver(skill.id) ?: return emptyList()
        val libraryFingerprint = skillTreeFingerprint(snapshot.files)
        val key = skillTargetKey(target)
        return destinations(target, skill.id).map { destination ->
            val record = state.find(key, destination.agent.id, skill.id)
            val executableFiles = recordedExecutableFiles(target, destination.path) ?: snapshot.files.filter { it.executable }.map { it.path }
            val tree = skillFileStatus(
                installed = directoryFingerprint(destination.path, executableFiles),
                recorded = record,
                libraryFingerprint = libraryFingerprint,
            )
            SkillDestinationStatus(destination.agent.id, destination.path, tree.status, tree.conflict)
        }
    }

    /** Captures an owned tree (including an orphan) without losing modes on a non-POSIX host. */
    fun capture(target: Target, entry: SkillDirEntry, id: String = entry.id): SkillSnapshot {
        val executableFiles = recordedExecutableFiles(target, entry.path).orEmpty().toSet()
        val captured = LocalSkillCapture.capture(entry.path, id = id)
        return captured.copy(files = captured.files.map { file ->
            file.copy(executable = skillExecutable(entry.path.resolve(file.path), file.path in executableFiles))
        })
    }

    /** Origins recorded for skills Ruleblend owns in [target], including base (`null`) entries. */
    fun installedOrigins(target: Target): Map<String, String?> {
        val key = skillTargetKey(target)
        val agents = agentsOf(target).map { it.id }.toSet()
        return state.all()
            .filter { it.targetKey == key && it.agentId in agents }
            .associate { it.skillId to it.origin }
    }

    /** Skill directories [target]'s agents read, in agent order; a directory is listed once. */
    fun directories(target: Target): List<Path> = roots(target)

    /**
     * Lists direct skill directories under every root [target]'s agents read. The traversal never
     * follows symbolic links, so an agent root cannot make this read outside its declared path.
     */
    fun entries(target: Target): List<SkillDirEntry> = roots(target).flatMap { root ->
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS)) return@flatMap emptyList()
        // A root someone else fills is read, never trusted: an unreadable directory, or a `SKILL.md`
        // that is not text, drops that one entry instead of taking the whole place down with it.
        val children = runCatching {
            Files.list(root).use { paths ->
                paths
                    .filter { directory ->
                        !Files.isSymbolicLink(directory) &&
                            Files.isDirectory(directory, NOFOLLOW_LINKS) &&
                            Files.isRegularFile(directory.resolve("SKILL.md"), NOFOLLOW_LINKS)
                    }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
        children.mapNotNull { directory ->
            val text = runCatching { Files.readString(directory.resolve("SKILL.md")) }.getOrNull()
                ?: return@mapNotNull null
            val id = directory.fileName.toString()
            val parsed = parseSkillFrontmatter(text)
            SkillDirEntry(
                path = directory,
                id = id,
                meta = parsed.copy(name = parsed.name?.takeIf { it.isNotBlank() } ?: id),
                text = text,
            )
        }
    }

    /**
     * Classifies every skill directory [entries] finds for [target]. The generic classifier keeps
     * the origin model reusable when MCP entries join the same place surface.
     */
    fun classifiedEntries(
        target: Target,
        librarySkillIds: Set<String>,
        ignoredEntryKeys: Set<String> = emptySet(),
    ): List<PlaceEntry<SkillDirEntry, String>> {
        val key = skillTargetKey(target)
        // A record names its root by agent; the address it stands for is what the disk entries are
        // keyed by, so it is resolved here rather than compared id to id.
        val roots = agentsOf(target).mapNotNull { agent -> rootOf(target, agent)?.let { agent.id to it } }.toMap()
        val sidecarEntries = state.all()
            .asSequence()
            .filter { it.targetKey == key }
            .mapNotNull { record ->
                roots[record.agentId]?.let { root ->
                    PlaceSidecarEntry(skillPlaceEntryKey(root.resolve(record.directoryName)), record.skillId)
                }
            }
            .toList()
        return classifyPlaceEntries(
            entries = entries(target),
            sidecarEntries = sidecarEntries,
            libraryIds = librarySkillIds,
            ignoredKeys = ignoredEntryKeys,
            keyOf = { entry -> skillPlaceEntryKey(entry.path) },
            libraryIdOf = SkillDirEntry::id,
        )
    }

    /**
     * Records a foreign directory under [skill] without writing a byte into it. [sameAsLibrary] holds
     * a tree captured into the library a moment ago to that capture; a tree taken under a skill the
     * library already had may differ, and then reads as an available update.
     */
    fun takeOwnership(
        target: Target,
        entry: SkillDirEntry,
        skill: Skill,
        sameAsLibrary: Boolean = true,
    ) = coordinator.mutate(entry.path, state.file) {
        val files = LocalSkillCapture.files(entry.path)
        require(!sameAsLibrary || files == snapshotResolver(skill.id)?.files) { "${entry.path} changed after it was saved to the library" }
        val fingerprint = skillTreeFingerprint(files)
        val key = skillTargetKey(target)
        val owners = agentsOf(target).filter { agent ->
            rootOf(target, agent)?.resolve(entry.id) == entry.path.normalize()
        }
        require(owners.isNotEmpty()) { "${entry.path} is no longer a skill directory of this target" }
        owners.forEach { agent ->
            state.record(SkillInstallRecord(
                key, agent.id, skill.id, fingerprint, directoryName = entry.id,
                executableFiles = files.filter { it.executable }.map { it.path },
            ))
        }
    }

    /**
     * Stops managing [skillId] in [target]: its records go, its directories stay as they are and
     * are foreign from then on. [takeOwnership] is the way back.
     */
    fun release(target: Target, skillId: String) = mutate(target, skillId) {
        val key = skillTargetKey(target)
        require(state.all().any { it.targetKey == key && it.skillId == skillId }) {
            "Skill '$skillId' is not managed in '${target.name}'"
        }
        state.removeAll(key, skillId)
    }

    /**
     * Clears the copies earlier versions wrote into [target]'s cross-agent `.agents/skills` tree.
     * An unchanged copy moves into its agent's own root when that root holds no copy of the skill
     * yet, so a skill installed for Pi stays installed; otherwise it is deleted. A copy edited since
     * it was written stays where it is and is no longer Ruleblend's. Returns the records cleared; a
     * record whose tree could not be moved or deleted stays for the next attempt.
     */
    fun retireSharedCopies(target: Target): Int {
        val key = skillTargetKey(target)
        // Copies that still need a native home go first: Kimi Code and ZCode wrote the same shared
        // tree Pi read, and deleting it for them would strand Pi.
        val records = state.legacy().filter { it.targetKey == key }
            .sortedBy { record -> state.find(key, record.agentId, record.skillId) != null }
        if (records.isEmpty()) return 0
        val shared = sharedRoot(target)
        val agents = agentsOf(target).associateBy { it.id }
        val nativePaths = records.mapNotNull { record ->
            agents[record.agentId]?.let { rootOf(target, it) }?.resolve(record.directoryName)
        }
        val sharedPaths = records.map { shared.resolve(it.directoryName).normalize() }
        return coordinator.mutate(*(sharedPaths + nativePaths + state.file).distinct().toTypedArray()) {
            records.count { record ->
                runCatching {
                    val legacyPath = shared.resolve(record.directoryName).normalize()
                    val installed = directoryFingerprint(legacyPath, record.executableFiles.orEmpty())
                    if (installed != null && installed == record.fingerprint) {
                        val root = agents[record.agentId]?.let { rootOf(target, it) }
                        val nativePath = root?.resolve(record.directoryName)
                        if (nativePath != null && !nativePath.exists() && state.find(key, record.agentId, record.skillId) == null) {
                            Files.createDirectories(root)
                            Files.move(legacyPath, nativePath)
                            state.record(record)
                        } else {
                            deleteTree(legacyPath)
                        }
                    }
                    state.removeLegacy(record)
                }.isSuccess
            }
        }
    }

    /**
     * One mutation over every directory [skill] occupies in [target], and over the state file
     * describing them. A preflight that decided a tree may be written must still hold when the
     * write lands, so the check, the write and its record share one lock.
     */
    private fun <T> mutate(target: Target, skillId: String, body: () -> T): T =
        coordinator.mutate(*destinations(target, skillId).map { it.path }.plusElement(state.file).toTypedArray(), body = body)

    private data class Destination(val agent: AgentAdapter, val path: Path)

    private fun recordedPath(target: Target, record: SkillInstallRecord): Path? {
        val agent = agentsOf(target).firstOrNull { it.id == record.agentId } ?: agentById(record.agentId) ?: return null
        return rootOf(target, agent)?.resolve(record.directoryName)?.normalize()
    }

    private fun recordedExecutableFiles(target: Target, path: Path): List<String>? =
        state.all().lastOrNull {
            it.targetKey == skillTargetKey(target) && recordedPath(target, it) == path.normalize()
        }?.executableFiles

    /**
     * An adopted directory can keep a physical name different from its library id. Orphan removal
     * must follow that sidecar address, never infer a new path from the now-missing library object.
     */
    private fun orphanDestinations(target: Target, skillId: String): List<Destination> {
        val roots = agentsOf(target).mapNotNull { agent -> rootOf(target, agent)?.let { agent.id to (agent to it) } }.toMap()
        return state.all()
            .asSequence()
            .filter { it.targetKey == skillTargetKey(target) && it.skillId == skillId }
            .mapNotNull { record ->
                roots[record.agentId]?.let { (agent, root) -> Destination(agent, root.resolve(record.directoryName)) }
            }
            .toList()
    }

    private fun roots(target: Target): List<Path> = agentsOf(target).mapNotNull { rootOf(target, it) }.distinct()

    private fun destinations(target: Target, skillId: String): List<Destination> =
        agentsOf(target).mapNotNull { agent ->
            rootOf(target, agent)?.let { root ->
                val name = state.find(skillTargetKey(target), agent.id, skillId)?.directoryName ?: skillId
                Destination(agent, root.resolve(name).normalize())
            }
        }

    /** The agent's own skill root at [target]'s scope; never the cross-agent `.agents/skills` tree. */
    private fun rootOf(target: Target, agent: AgentAdapter): Path? = when (target) {
        is AgentGlobalTarget -> agent.globalSkillsDirectory()
        is ProjectTarget -> agent.projectSkillsDirectory(target.dir)
    }?.normalize()

    /** Where earlier versions wrote the [SkillInstallRecord.isNative]-less copies of [target]. */
    private fun sharedRoot(target: Target): Path = when (target) {
        is AgentGlobalTarget -> sharedHome
        is ProjectTarget -> target.dir
    }.resolve(".agents").resolve("skills").normalize()

    private fun agentsOf(target: Target): List<AgentAdapter> = when (target) {
        is AgentGlobalTarget -> listOf(target.agent)
        is ProjectTarget -> target.agents
    }
}

internal data class SkillFileState(val status: InstallStatus?, val conflict: Boolean)

internal fun skillFileStatus(
    installed: String?,
    recorded: SkillInstallRecord?,
    libraryFingerprint: String,
): SkillFileState = when {
    installed == null -> SkillFileState(null, conflict = false)
    // Only a record makes a tree ours; an unrecorded one equal to the library is still foreign.
    recorded == null -> SkillFileState(InstallStatus.MODIFIED, conflict = true)
    installed == libraryFingerprint -> SkillFileState(InstallStatus.SYNCED, conflict = false)
    installed == recorded.fingerprint -> SkillFileState(InstallStatus.UPDATE_AVAILABLE, conflict = false)
    else -> SkillFileState(InstallStatus.MODIFIED, conflict = false)
}

private fun InstallStatus.severity(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}

/** Returns `null` for an absent tree and a non-library sentinel for any tree containing symlinks. */
private fun directoryFingerprint(root: Path, executableFiles: List<String> = emptyList()): String? =
    FileReadScope.cached(Triple(root, "skill-tree", executableFiles.toSet())) {
        Fingerprint(readDirectoryFingerprint(root, executableFiles.toSet()))
    }.value

/** Boxes the nullable answer so the scope can memoize "this tree is absent" too. */
private class Fingerprint(val value: String?)

private fun readDirectoryFingerprint(root: Path, executableFiles: Set<String>): String? {
    if (!root.exists()) return null
    if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) return "unsafe:${root.toAbsolutePath()}"
    val files = Files.walk(root).use { paths ->
        val entries = paths.filter { it != root }.sorted().toList()
        if (entries.any { Files.isSymbolicLink(it) }) return "unsafe:${root.toAbsolutePath()}"
        entries.filter { it.isRegularFile() }.map { file ->
            SkillFile(
                path = root.relativize(file).joinToString("/"),
                bytes = file.readBytes(),
                executable = skillExecutable(file, root.relativize(file).joinToString("/") in executableFiles),
            )
        }
    }
    return skillTreeFingerprint(files)
}

/**
 * Writes one complete skill directory; the directory stays in place throughout (see
 * [DirectoryReplace]). The tree that was there is kept in [rollbackOf] until the caller has landed
 * every destination, so it can [undo] the earlier ones; it then owns discarding it.
 */
private fun replaceDirectory(target: Path, files: List<SkillFile>, beforeMove: () -> Unit, afterMove: () -> Unit) {
    DirectoryReplace.replace(target, rollbackOf(target), SKILL_FILE, beforeApply = beforeMove) { staged ->
        files.forEach { file ->
            val destination = staged.resolve(file.path).normalize()
            require(destination.startsWith(staged)) { "Skill file escapes its directory: ${file.path}" }
            AtomicWrite.write(destination, file.bytes)
            setSkillExecutable(destination, file.executable)
        }
    }
    try {
        afterMove()
    } catch (error: Throwable) {
        runCatching { DirectoryReplace.restore(target, rollbackOf(target), SKILL_FILE) }.onFailure(error::addSuppressed)
        throw error
    }
}

private const val SKILL_FILE = "SKILL.md"

private fun rollbackOf(target: Path): Path = target.resolveSibling(".${target.name}.ruleblend-rollback")

/** Puts one destination back: what it held before returns in place, a new one is removed. */
private fun undo(target: Path): Boolean = runCatching {
    DirectoryReplace.restore(target, rollbackOf(target), SKILL_FILE)
}.isSuccess

private fun deleteTree(path: Path) = DirectoryReplace.deleteTree(path)
