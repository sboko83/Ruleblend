package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.GitSubagentSource
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillMeta
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.requirePortableFileTree
import dev.ruleblend.core.model.updateSkillDocumentMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.readBytes
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * One commit of a library object, as shown in the version history. [added] and [removed] are the
 * line counts of that commit's diff, so a revision row can say how big the change was before it is
 * opened.
 */
data class LibraryRevision(
    val id: String,
    val message: String,
    val time: Instant,
    val added: Int = 0,
    val removed: Int = 0,
)

private const val HISTORY_LIMIT = 20

/** Lands after the rest of a library skill tree, so a reader seeing the new text finds its files. */
private const val LIBRARY_SKILL_FILE = "files/SKILL.md"

/**
 * Library on disk: `blocks/<id>.md`, `groups/<id>.yaml`, `profiles/<id>.yaml`, backed by a git repo.
 * Every mutation is committed.
 *
 * Disk and git history are one journal — a version *is* a commit — and each mutation keeps them in
 * lockstep on its own object: a failed commit puts the file back the way git still has it, whether
 * the mutation created, rewrote or deleted it. An operation spanning several objects is therefore an
 * honest partial result and never a divergence: the objects committed before the failure stand, the
 * one that failed is untouched, and none of them is left on disk without a commit behind it.
 */
class LibraryRepository(
    private val root: Path,
    private val git: LibraryGit = LibraryGit(root),
) {
    private val blocksDir: Path = root.resolve("blocks")
    private val groupsDir: Path = root.resolve("groups")
    private val profilesDir: Path = root.resolve("profiles")
    private val skillsDir: Path = root.resolve("skills")

    /**
     * Runs [body] holding the library write lock, so a multi-step mutation (an import batch, a
     * read-modify-write) is not interleaved with another writer. See [LibraryGit.withLock].
     */
    fun <T> withLock(body: () -> T): T = git.withLock(body)

    fun init() {
        blocksDir.createDirectories()
        groupsDir.createDirectories()
        profilesDir.createDirectories()
        skillsDir.createDirectories()
        git.init()
    }

    fun listBlocks(): List<Block> = listFiles(blocksDir, "md")
        .map { BlockFile.parse(it.nameWithoutExtension, it.readText()) }
        .sortedBy { it.name }

    fun loadBlock(id: String): Block? = blockPath(id)
        .takeIf { it.exists() }
        ?.let { BlockFile.parse(id, it.readText()) }

    /**
     * Writes [block], auto-incrementing its version when its installable definition changed
     * relative to the stored file. Returns the saved block.
     */
    fun saveBlock(block: Block): Block = withLock {
        val block = block.copy(content = LineEnding.LF.apply(block.content))
        val existing = loadBlock(block.id)
        val saved = when {
            existing == null -> block
            existing.content != block.content || existing.type != block.type ||
                existing.heading != block.heading ||
                (block.heading.isNotBlank() && existing.headingLevel != block.headingLevel) ||
                (existing.type == BlockType.SUBAGENT &&
                    (existing.name != block.name || existing.description != block.description ||
                        existing.variants != block.variants)) ->
                block.copy(version = existing.version + 1)
            else -> block.copy(version = existing.version)
        }
        if (saved == existing) return@withLock saved
        writeBlock(saved)
        saved
    }

    /**
     * Writes a local [block] exactly as given, keeping its version. Imported definitions may only
     * change their favorite flag; explicit Git and archive replacements have separate entry points.
     */
    fun writeBlock(block: Block): Unit = withLock {
        val current = loadBlock(block.id)
        require(block.source == current?.source &&
            (current?.source == null || block.copy(favorite = current.favorite) == current)) {
            "Imported subagents are read-only; create an editable copy first"
        }
        persistBlock(block, current)
    }

    /** Explicit archive replacement, preserving the archive's numeric definition version. */
    internal fun writeArchiveBlock(block: Block): Unit = withLock {
        require(block.source == null || block.type == BlockType.SUBAGENT) { "Only subagents have Git provenance" }
        persistBlock(block, loadBlock(block.id))
    }

    /** Only Git import/update may replace a protected definition through this entry point. */
    internal fun writeImportedSubagent(block: Block): Block = withLock {
        require(block.type == BlockType.SUBAGENT && block.source != null) { "Expected an imported subagent" }
        val current = loadBlock(block.id)
        require(current == null || current.source?.let {
            it.repository == block.source.repository && it.path == block.source.path && it.assistant == block.source.assistant
        } == true) { "Cannot replace a different library object with an imported subagent" }
        val normalized = block.copy(content = LineEnding.LF.apply(block.content))
        val changed = current != null && (current.name != normalized.name || current.description != normalized.description ||
            current.content != normalized.content || current.variants != normalized.variants)
        val saved = normalized.copy(version = if (current == null) 1 else current.version + if (changed) 1 else 0,
            favorite = current?.favorite ?: normalized.favorite)
        persistBlock(saved, current)
        saved
    }

    fun findSubagent(source: GitSubagentSource): Block? = listBlocks().find {
        it.source?.let { stored -> stored.repository == source.repository && stored.path == source.path &&
            stored.assistant == source.assistant } == true
    }

    fun forkSubagent(sourceId: String, newId: String, newName: String): Block = withLock {
        val original = loadBlock(sourceId) ?: error("No subagent with id '$sourceId'")
        val upstream = requireNotNull(original.source) { "Only an imported subagent can be forked" }
        require(loadBlock(newId) == null) { "Block '$newId' already exists" }
        saveBlock(original.copy(id = newId, name = newName, version = 1, favorite = false,
            source = null, forkedFrom = upstream))
    }

    private fun persistBlock(block: Block, existing: Block?) {
        checkedWritableBlockId(block.id)
        if (existing == block) return
        val path = blockPath(block.id)
        val before = path.textOrNull()
        AtomicWrite.write(path, git.formatText(BlockFile.serialize(block)))
        commitFile(path, "Save block ${block.id} v${block.version}", before)
    }

    fun deleteBlock(id: String): Unit = withLock {
        val path = blockPath(id)
        val before = path.textOrNull() ?: return@withLock
        path.deleteIfExists()
        commitFile(path, "Delete block $id", before)
    }

    fun listGroups(): List<Group> = listFiles(groupsDir, "yaml")
        .map { Yaml.default.decodeFromString(Group.serializer(), it.readText()) }
        .sortedBy { it.name }

    fun loadGroup(id: String): Group? = groupPath(id)
        .takeIf { it.exists() }
        ?.let { Yaml.default.decodeFromString(Group.serializer(), it.readText()) }

    /**
     * Writes [group], auto-incrementing its version when either membership list changed relative to
     * the stored file (mirrors [saveBlock]'s content-change check).
     */
    fun saveGroup(group: Group): Unit = withLock {
        val existing = loadGroup(group.id)
        val saved = when {
            existing == null -> group
            existing.blockIds != group.blockIds || existing.skillIds != group.skillIds ->
                group.copy(version = existing.version + 1)
            else -> group.copy(version = existing.version)
        }
        if (saved == existing) return@withLock
        val path = groupPath(group.id)
        val before = path.textOrNull()
        AtomicWrite.write(path, git.formatText(Yaml.default.encodeToString(Group.serializer(), saved) + "\n"))
        commitFile(path, "Save group ${saved.id} v${saved.version}", before)
    }

    /**
     * Writes [group] exactly as given, keeping its version. Used by import, where the version comes
     * from the archive: [saveGroup] raises the version because a local edit is a new revision of a
     * local group, whereas an imported group is already a revision someone else made.
     */
    fun writeGroup(group: Group): Unit = withLock {
        val existing = loadGroup(group.id)
        if (existing == group) return@withLock
        val path = groupPath(group.id)
        val before = path.textOrNull()
        AtomicWrite.write(path, git.formatText(Yaml.default.encodeToString(Group.serializer(), group) + "\n"))
        commitFile(path, "Save group ${group.id} v${group.version}", before)
    }

    fun deleteGroup(id: String): Unit = withLock {
        val path = groupPath(id)
        val before = path.textOrNull() ?: return@withLock
        path.deleteIfExists()
        commitFile(path, "Delete group $id", before)
    }

    fun listProfiles(): List<Profile> = listFiles(profilesDir, "yaml")
        .map { Yaml.default.decodeFromString(Profile.serializer(), it.readText()) }
        .sortedBy { it.name }

    fun loadProfile(id: String): Profile? = profilePath(id)
        .takeIf { it.exists() }
        ?.let { Yaml.default.decodeFromString(Profile.serializer(), it.readText()) }

    /**
     * Writes [profile], incrementing its version when any direct or grouped membership changed.
     * Name and description are presentation metadata and do not change what applying the profile
     * installs, so editing them keeps the current version.
     */
    fun saveProfile(profile: Profile): Profile = withLock {
        val existing = loadProfile(profile.id)
        val saved = when {
            existing == null -> profile
            existing.membership != profile.membership -> profile.copy(version = existing.version + 1)
            else -> profile.copy(version = existing.version)
        }
        if (saved == existing) return@withLock saved
        writeProfile(saved, existing)
        saved
    }

    /** Writes [profile] exactly as given, preserving the version supplied by an archive. */
    fun writeProfile(profile: Profile, existing: Profile? = loadProfile(profile.id)): Unit = withLock {
        if (existing == profile) return@withLock
        val path = profilePath(profile.id)
        val before = path.textOrNull()
        AtomicWrite.write(path, git.formatText(Yaml.default.encodeToString(Profile.serializer(), profile) + "\n"))
        commitFile(path, "Save profile ${profile.id} v${profile.version}", before)
    }

    fun deleteProfile(id: String): Unit = withLock {
        val path = profilePath(id)
        val before = path.textOrNull() ?: return@withLock
        path.deleteIfExists()
        commitFile(path, "Delete profile $id", before)
    }

    fun listSkills(): List<Skill> =
        if (!skillsDir.exists()) emptyList()
        else skillsDir.listDirectoryEntries()
            .filter { !it.name.startsWith(".") && it.isDirectory() && it.resolve("meta.yaml").isRegularFile() }
            .mapNotNull { loadSkill(it.name) }
            .sortedBy { it.name }

    fun loadSkill(id: String): Skill? {
        val directory = skillPath(id)
        val metaFile = directory.resolve("meta.yaml")
        val skillFile = directory.resolve("files/SKILL.md")
        if (!metaFile.isRegularFile() || !skillFile.isRegularFile()) return null
        val meta = Yaml.default.decodeFromString(SkillMeta.serializer(), metaFile.readText())
        return Skill(
            id = id,
            name = meta.name,
            description = meta.description,
            version = meta.version,
            content = skillFile.readText(),
            source = meta.source,
            forkedFrom = meta.forkedFrom,
        )
    }

    fun loadSkillSnapshot(id: String): SkillSnapshot? =
        loadSkill(id)?.let { skill -> SkillSnapshot(skill, readSkillFiles(id)) }

    /** Creates a local editable skill consisting of its initial `SKILL.md`. */
    fun createSkill(skill: Skill): Skill = withLock {
        require(skill.source == null) { "A local skill cannot have Git provenance" }
        require(loadSkill(skill.id) == null) { "Skill '${skill.id}' already exists" }
        val formatted = skill.copy(content = git.formatText(skill.content))
        writeSkill(SkillSnapshot(formatted, listOf(SkillFile("SKILL.md", formatted.content.encodeToByteArray()))))
    }

    /** Imports or updates one complete upstream skill directory as a single library commit. */
    fun writeSkill(snapshot: SkillSnapshot): Skill = withLock {
        validateSkillSnapshot(snapshot)
        val existing = loadSkill(snapshot.skill.id)
        replaceSkillDirectory(snapshot)
        try {
            git.commit(
                when {
                    existing == null && snapshot.skill.source == null ->
                        "Create skill ${snapshot.skill.id} v${snapshot.skill.version}"
                    existing == null -> "Import skill ${snapshot.skill.id} ${snapshot.skill.version}"
                    snapshot.skill.source == null -> "Save skill ${snapshot.skill.id} v${snapshot.skill.version}"
                    else -> "Update skill ${snapshot.skill.id} ${snapshot.skill.version}"
                },
                root.relativize(skillPath(snapshot.skill.id)),
            )
        } catch (error: Exception) {
            // replaceSkillDirectory keeps the previous tree in a sibling rollback directory until
            // commit succeeds. The helper restores it here and then rethrows.
            runCatching { restoreSkillRollback(snapshot.skill.id) }.onFailure(error::addSuppressed)
            if (skillRollbackPath(snapshot.skill.id).exists()) {
                throw IllegalStateException("${error.message}; rollback remains at ${skillRollbackPath(snapshot.skill.id)}", error)
            }
            throw error
        }
        deleteSkillRollback(snapshot.skill.id)
        loadSkill(snapshot.skill.id) ?: error("Saved skill '${snapshot.skill.id}' cannot be read back")
    }

    /**
     * Forks an imported skill into an editable local skill. Every sibling file is retained; source
     * metadata is removed so future upstream refreshes can never overwrite the user's changes.
     */
    fun forkSkill(sourceId: String, newId: String, newName: String, newContent: String? = null): Skill = withLock {
        val source = loadSkill(sourceId) ?: error("No skill with id '$sourceId'")
        val upstream = requireNotNull(source.source) { "Only an imported skill can be forked" }
        require(loadSkill(newId) == null) { "Skill '$newId' already exists" }
        val content = newContent ?: source.content
        val files = readSkillFiles(sourceId).map { file ->
            if (file.path == "SKILL.md") file.copy(bytes = content.encodeToByteArray()) else file
        }
        writeSkill(
            SkillSnapshot(
                source.copy(
                    id = newId,
                    name = newName,
                    version = "1",
                    content = content,
                    source = null,
                    forkedFrom = upstream,
                ),
                files,
            ),
        )
    }

    /** Saves only a local copy's SKILL.md and increments its numeric revision. */
    fun saveSkill(skill: Skill): Skill = withLock {
        val existing = loadSkill(skill.id) ?: error("No skill with id '${skill.id}'")
        require(existing.source == null) { "Imported skills are read-only; create an editable copy first" }
        val content = git.formatText(skill.content)
        val version = if (LineEnding.LF.apply(existing.content) == LineEnding.LF.apply(content)) existing.version
            else ((existing.version.toIntOrNull() ?: 0) + 1).toString()
        val files = readSkillFiles(skill.id).map { file ->
            if (file.path == "SKILL.md") file.copy(bytes = content.encodeToByteArray()) else file
        }
        return@withLock writeSkill(SkillSnapshot(skill.copy(version = version, content = content, source = null), files))
    }

    fun deleteSkill(id: String): Unit = withLock {
        val path = skillPath(id)
        if (!path.exists()) return@withLock
        val rollback = skillRollbackPath(id)
        check(!rollback.exists()) { "Previous skill rollback remains at $rollback; restore it before deleting $path" }
        AtomicMove.move(path, rollback)
        try {
            git.commit("Delete skill $id", root.relativize(path))
        } catch (error: Exception) {
            runCatching { AtomicMove.move(rollback, path) }.onFailure(error::addSuppressed)
            throw error
        }
        deleteRecursively(rollback)
    }

    /** Edits one local tree under the same lock as its read, revision and atomic replacement. */
    fun writeSkillFile(id: String, path: String, bytes: ByteArray, executable: Boolean? = null): Skill = withLock {
        requirePortableFileTree(listOf(path))
        val snapshot = loadSkillSnapshot(id) ?: error("No skill with id '$id'")
        require(snapshot.skill.source == null) { "Imported skills are read-only; use fork_skill before editing" }
        val previous = snapshot.files.find { it.path == path }
        val content = if (path == "SKILL.md") git.formatText(
            updateSkillDocumentMetadata(
                bytes.decodeToString(throwOnInvalidSequence = true), snapshot.skill.name, snapshot.skill.description,
            ),
        ) else snapshot.skill.content
        val file = SkillFile(
            path, if (path == "SKILL.md") content.encodeToByteArray() else bytes,
            executable ?: previous?.executable ?: false,
        )
        val files = (snapshot.files.filterNot { it.path == path } + file).sortedBy { it.path }
        SkillTreeLimits.validate(files)
        if (files == snapshot.files.sortedBy { it.path }) return@withLock snapshot.skill
        writeSkill(SkillSnapshot(snapshot.skill.copy(content = content, version = nextSkillVersion(snapshot.skill)), files))
    }

    fun deleteSkillFile(id: String, path: String): Skill = withLock {
        requirePortableFileTree(listOf(path))
        require(path != "SKILL.md") { "SKILL.md cannot be deleted; use delete_skill to remove the skill" }
        val snapshot = loadSkillSnapshot(id) ?: error("No skill with id '$id'")
        require(snapshot.skill.source == null) { "Imported skills are read-only; use fork_skill before editing" }
        require(snapshot.files.any { it.path == path }) { "No skill file '$path'" }
        writeSkill(SkillSnapshot(snapshot.skill.copy(version = nextSkillVersion(snapshot.skill)), snapshot.files.filterNot { it.path == path }))
    }

    private fun nextSkillVersion(skill: Skill): String = ((skill.version.toIntOrNull() ?: 0) + 1).toString()

    fun findSkill(source: GitSkillSource): Skill? = listSkills().find {
        it.source?.repository == source.repository && it.source.path == source.path
    }

    /**
     * Keeps the reserved "all" group's membership equal to every block and skill id. A no-op (no write,
     * no version bump) when membership already matches. Must run after any block create/delete —
     * every facade (GUI, MCP) mutating blocks calls it. The order comes from [LibraryMembership],
     * which a remote merge rebuilds the same group with.
     */
    fun syncAllGroup(): Unit = withLock {
        val ids = LibraryMembership.blockIds(root)
        val skillIds = LibraryMembership.skillIds(root)
        val existing = loadGroup(ALL_GROUP_ID)
        if (existing?.blockIds == ids && existing.skillIds == skillIds) return@withLock
        saveGroup(
            (existing ?: Group(id = ALL_GROUP_ID, name = ALL_GROUP_ID)).copy(
                blockIds = ids,
                skillIds = skillIds,
            ),
        )
    }

    /** Commits touching the block file, newest first; empty when the library has no history yet. */
    fun blockHistory(id: String, limit: Int = HISTORY_LIMIT): List<LibraryRevision> =
        history(blockPath(id), limit)

    /** Commits touching the group file, newest first. */
    fun groupHistory(id: String, limit: Int = HISTORY_LIMIT): List<LibraryRevision> =
        history(groupPath(id), limit)

    /** Commits touching the portable profile definition, newest first. */
    fun profileHistory(id: String, limit: Int = HISTORY_LIMIT): List<LibraryRevision> =
        history(profilePath(id), limit)

    /** Commits touching any file of the skill directory, newest first. */
    fun skillHistory(id: String, limit: Int = HISTORY_LIMIT): List<LibraryRevision> =
        history(skillPath(id), limit)

    /** Unified diff of [revision] of the block file; empty when git cannot produce one. */
    fun blockDiff(id: String, revision: String): String = diff(blockPath(id), revision)

    /** Inspect full historical documents: patch context can omit an unchanged MCP type header. */
    fun blockDiffContainsMcp(id: String, revision: String): Boolean = withLock {
        git.fileVersions(revision, root.relativize(blockPath(id))).any {
            BlockFile.parse(id, it).type == BlockType.MCP
        }
    }

    /** Unified diff of [revision] of the group file. */
    fun groupDiff(id: String, revision: String): String = diff(groupPath(id), revision)

    /** Unified diff of [revision] of the profile definition. */
    fun profileDiff(id: String, revision: String): String = diff(profilePath(id), revision)

    /** Unified diff of [revision] across every file of the skill directory. */
    fun skillDiff(id: String, revision: String): String = diff(skillPath(id), revision)

    private fun diff(path: Path, revision: String): String =
        runCatching { git.diff(revision, root.relativize(path)) }.getOrDefault("")

    /**
     * History is a read-only convenience: a library without commits (a fresh checkout, a git
     * failure) has none to show, and that must never break the screen asking for it.
     *
     * Each revision carries its own line counts, which costs one diff per commit — bounded by
     * [limit], over files a library keeps small, and it is what lets the list say what a commit did
     * without opening it.
     */
    private fun history(path: Path, limit: Int): List<LibraryRevision> =
        runCatching {
            val relative = root.relativize(path)
            git.log(relative).take(limit).map { commit ->
                val id = commit.name
                val counts = runCatching { countChanges(git.diff(id, relative)) }.getOrDefault(0 to 0)
                LibraryRevision(
                    id = id.take(7),
                    message = commit.shortMessage,
                    time = Instant.ofEpochSecond(commit.commitTime.toLong()),
                    added = counts.first,
                    removed = counts.second,
                )
            }
        }.getOrDefault(emptyList())

    /** Added and removed line counts of a unified diff, ignoring its `+++`/`---` file headers. */
    private fun countChanges(diff: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        diff.lineSequence().forEach { line ->
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return added to removed
    }

    /**
     * Commits [path] and puts the file back the way [before] found it when the commit fails. Disk and
     * git history are one journal — a version *is* a commit — so a file git never recorded must not
     * survive the failure, and one git never dropped must not stay deleted. [before] is the raw text,
     * not a re-serialized model: the restored file is byte-for-byte the one git still has.
     */
    private fun commitFile(path: Path, message: String, before: String?) {
        try {
            git.commit(message, root.relativize(path))
        } catch (error: Exception) {
            if (before == null) path.deleteIfExists() else AtomicWrite.write(path, before)
            throw error
        }
    }

    private fun Path.textOrNull(): String? = takeIf { it.exists() }?.readText()

    private fun blockPath(id: String): Path = objectPath(blocksDir, id, ".md")

    private fun groupPath(id: String): Path = objectPath(groupsDir, id, ".yaml")

    private fun profilePath(id: String): Path = objectPath(profilesDir, id, ".yaml")

    private fun skillPath(id: String): Path = objectPath(skillsDir, id, "")

    private fun objectPath(directory: Path, id: String, extension: String): Path {
        val name = checkedLibraryId(id) + extension
        if (directory.exists()) {
            require(directory.listDirectoryEntries().none {
                it.name != name && it.name.equals(name, ignoreCase = true)
            }) { "Library object '$id' collides with an existing name in $directory" }
        }
        return directory.resolve(name)
    }

    /** Entire incoming batches are checked before the first write, including against current ids. */
    fun validateImport(
        blocks: List<Block> = emptyList(),
        groups: List<Group> = emptyList(),
        skills: List<SkillSnapshot> = emptyList(),
        profiles: List<Profile> = emptyList(),
    ) {
        fun ids(values: List<String>, path: (String) -> Path) {
            require(values.map { it.lowercase() }.distinct().size == values.size) { "Duplicate library ids" }
            values.forEach { path(it) }
        }
        blocks.forEach { checkedWritableBlockId(it.id) }
        ids(blocks.map { it.id }, ::blockPath)
        ids(groups.map { it.id }, ::groupPath)
        ids(profiles.map { it.id }, ::profilePath)
        ids(skills.map { it.skill.id }, ::skillPath)
        skills.forEach(::validateSkillSnapshot)
    }

    private fun skillRollbackPath(id: String): Path = skillsDir.resolve(".${checkedLibraryId(id)}.rollback")

    private fun replaceSkillDirectory(snapshot: SkillSnapshot) {
        val id = snapshot.skill.id
        DirectoryReplace.replace(skillPath(id), skillRollbackPath(id), LIBRARY_SKILL_FILE) { staged ->
            val meta = SkillMeta(
                name = snapshot.skill.name,
                description = snapshot.skill.description,
                version = snapshot.skill.version,
                source = snapshot.skill.source,
                executableFiles = snapshot.files.filter { it.executable }.map { it.path },
                forkedFrom = snapshot.skill.forkedFrom,
            )
            AtomicWrite.write(staged.resolve("meta.yaml"), git.formatText(Yaml.default.encodeToString(SkillMeta.serializer(), meta) + "\n"))
            snapshot.files.forEach { file ->
                val destination = staged.resolve("files").resolve(file.path).normalize()
                require(destination.startsWith(staged.resolve("files"))) { "Skill file escapes its directory: ${file.path}" }
                AtomicWrite.write(destination, file.bytes)
                setSkillExecutable(destination, file.executable)
            }
        }
    }

    private fun restoreSkillRollback(id: String) =
        DirectoryReplace.restore(skillPath(id), skillRollbackPath(id), LIBRARY_SKILL_FILE)

    private fun deleteSkillRollback(id: String) = deleteRecursively(skillRollbackPath(id))

    private fun readSkillFiles(id: String): List<SkillFile> {
        val filesRoot = skillPath(id).resolve("files")
        require(!Files.isSymbolicLink(skillPath(id)) && !Files.isSymbolicLink(filesRoot)) { "Skill tree cannot be a symbolic link" }
        val executableFiles = Yaml.default.decodeFromString(
            SkillMeta.serializer(), skillPath(id).resolve("meta.yaml").readText(),
        ).executableFiles.toSet()
        val paths = Files.walk(filesRoot).use { paths ->
            paths.peek { path ->
                require(!Files.isSymbolicLink(path)) { "Skill contains a symbolic link: ${filesRoot.relativize(path)}" }
            }.filter { it.isRegularFile() }.limit(SkillTreeLimits.MAX_FILES + 1L).toList()
        }
        require(paths.size <= SkillTreeLimits.MAX_FILES) { "Skill contains more than ${SkillTreeLimits.MAX_FILES} files" }
        val sizes = paths.map(Files::size)
        require(sizes.all { it <= SkillTreeLimits.MAX_FILE_BYTES }) { "Skill file exceeds ${SkillTreeLimits.MAX_FILE_BYTES} bytes" }
        require(sizes.sum() <= SkillTreeLimits.MAX_BYTES) { "Skill exceeds ${SkillTreeLimits.MAX_BYTES} bytes" }
        return paths.sorted().map { file ->
            SkillFile(
                filesRoot.relativize(file).joinToString("/"), file.readBytes(),
                filesRoot.relativize(file).joinToString("/") in executableFiles,
            )
        }.also(SkillTreeLimits::validate)
    }

    private fun validateSkillSnapshot(snapshot: SkillSnapshot) {
        checkedLibraryId(snapshot.skill.id)
        SkillTreeLimits.validate(snapshot.files)
        require(snapshot.files.any { it.path == "SKILL.md" }) { "Skill '${snapshot.skill.id}' has no SKILL.md" }
        require(snapshot.files.single { it.path == "SKILL.md" }.bytes.decodeToString() == snapshot.skill.content) {
            "Skill '${snapshot.skill.id}' metadata does not match its SKILL.md"
        }
        require(snapshot.files.map { it.path }.distinct().size == snapshot.files.size) { "Skill '${snapshot.skill.id}' has duplicate files" }
        snapshot.files.forEach { file ->
            val path = Path.of(file.path)
            require(!path.isAbsolute && file.path.isNotBlank() && path.none { it.toString() == ".." }) {
                "Invalid skill file path: ${file.path}"
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    private fun listFiles(dir: Path, extension: String): List<Path> =
        if (!dir.exists()) emptyList()
        else dir.listDirectoryEntries().filter { Files.isRegularFile(it) && it.extension == extension }
}

private val Profile.membership: List<List<String>>
    get() = listOf(blockIds, skillIds, subagentIds, groupIds)
