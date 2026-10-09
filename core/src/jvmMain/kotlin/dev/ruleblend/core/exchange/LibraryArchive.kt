package dev.ruleblend.core.exchange

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillMeta
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.requirePortableFileTree
import dev.ruleblend.core.storage.BlockFile
import dev.ruleblend.core.storage.AtomicMove
import dev.ruleblend.core.storage.FileIdentity
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.checkedLibraryId
import dev.ruleblend.core.storage.checkedWritableBlockId
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream

/** Contents of an exported library, read into memory. */
data class ArchiveContents(
    val blocks: List<Block>,
    val groups: List<Group>,
    val skills: List<SkillSnapshot>,
    val profiles: List<Profile> = emptyList(),
)

/**
 * Subset of the library an export writes. A selected group carries its members; a selected profile
 * carries its direct objects, groups and their members. Membership is expanded here rather than
 * trusted to the caller, so the archive never contains a broken portable object.
 */
data class ArchiveSelection(
    val blockIds: Set<String> = emptySet(),
    val groupIds: Set<String> = emptySet(),
    val skillIds: Set<String> = emptySet(),
    val profileIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = blockIds.isEmpty() && groupIds.isEmpty() && skillIds.isEmpty() && profileIds.isEmpty()
}

/** Per-entry decompressed size cap — blocks/groups are small text files; guards against zip-bomb archives. */
private const val MAX_ENTRY_BYTES = 10 * 1024 * 1024
private const val MAX_ARCHIVED_SKILL_FILES = 500
private const val MAX_ARCHIVED_SKILL_BYTES = 25 * 1024 * 1024
private val OS_METADATA_FILES = setOf(".DS_Store", "Thumbs.db", "desktop.ini")

/**
 * Export and import of the library as a zip. The archive holds `blocks/`, `groups/`, `profiles/`
 * and `skills/` — `.git` is machine state and history, and would defeat the point of a portable
 * snapshot.
 */
class LibraryArchive(private val root: Path, private val repository: LibraryRepository) {

    /**
     * Writes the library to [zip], skipping `.git` and the auto-maintained "all" group — it is
     * recomputed locally from the block list on load, not a portable piece of the library.
     * A non-null [selection] narrows the archive to the chosen objects and their group members.
     */
    @JvmOverloads
    fun export(zip: Path, selection: ArchiveSelection? = null, overwrite: Boolean = true) = repository.withLock {
        val expected = FileIdentity.of(zip)
        require(overwrite || expected == null) { "Archive already exists" }
        val temp = Files.createTempFile(zip.toAbsolutePath().parent, ".ruleblend-export-", ".zip")
        try {
            writeArchive(temp, selection)
            AtomicMove.move(temp, zip, replace = true) {
                check(FileIdentity.of(zip) == expected) { "Archive changed during export" }
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun writeArchive(zip: Path, selection: ArchiveSelection?) {
        val wanted = selection?.let(::expanded)
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            Files.walk(root).use { paths ->
                paths.filter {
                    it.isRegularFile() &&
                        isPortableEntry(root.relativize(it).map(Path::toString)) &&
                        wanted.holds(it)
                }
                    .sorted()
                    .forEach { file ->
                        out.putNextEntry(ZipEntry(root.relativize(file).joinToString("/")))
                        Files.copy(file, out)
                        out.closeEntry()
                    }
            }
        }
    }

    /** Expands selected groups and profiles to their portable dependency closure. */
    private fun expanded(selection: ArchiveSelection): ArchiveSelection {
        val profiles = repository.listProfiles().filter { it.id in selection.profileIds }
        val groupIds = selection.groupIds + profiles.flatMap { it.groupIds }
        val groups = repository.listGroups().filter { it.id in groupIds && it.id != ALL_GROUP_ID }
        return ArchiveSelection(
            blockIds = selection.blockIds + profiles.flatMap { it.blockIds + it.subagentIds } + groups.flatMap { it.blockIds },
            groupIds = groups.mapTo(mutableSetOf()) { it.id },
            skillIds = selection.skillIds + profiles.flatMap { it.skillIds } + groups.flatMap { it.skillIds },
            profileIds = profiles.mapTo(mutableSetOf()) { it.id },
        )
    }

    /** Whether [file] belongs to the selection; a null selection is the whole library. */
    private fun ArchiveSelection?.holds(file: Path): Boolean {
        if (this == null) return true
        val parts = root.relativize(file).map { it.toString() }
        return when (parts.firstOrNull()) {
            "blocks" -> parts.size == 2 && parts[1].removeSuffix(".md") in blockIds
            "groups" -> parts.size == 2 && parts[1].removeSuffix(".yaml") in groupIds
            "skills" -> parts.size > 1 && parts[1] in skillIds
            "profiles" -> parts.size == 2 && parts[1].removeSuffix(".yaml") in profileIds
            else -> false
        }
    }

    /**
     * Reads [zip] into memory. Entry names contribute only their file name — an archive cannot
     * point outside a recognized library directory, so a crafted zip cannot reach the rest of the
     * disk.
     */
    fun read(zip: Path): ArchiveContents {
        // ZipInputStream treats arbitrary non-ZIP bytes as an empty archive. Validate the central
        // directory first so a damaged file cannot be reported as a successful empty import.
        ZipFile(zip.toFile()).use { }
        val blocks = mutableListOf<Block>()
        val groups = mutableListOf<Group>()
        val profiles = mutableListOf<Profile>()
        val seenPaths = mutableSetOf<String>()
        data class MutableSkill(val meta: SkillMeta? = null, val files: MutableList<SkillFile> = mutableListOf())
        val skills = linkedMapOf<String, MutableSkill>()
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val normalizedPath = entry.name.replace('\\', '/').removeSuffix("/")
                val parts = normalizedPath.split('/')
                require(normalizedPath.isNotBlank() && !normalizedPath.startsWith('/') &&
                    parts.none { it.isBlank() || it == "." || it == ".." || it.contains(':') }) {
                    "Invalid archive path: ${entry.name}"
                }
                if (entry.isDirectory || isOsMetadata(parts)) continue
                require(seenPaths.add(normalizedPath)) { "Duplicate archive path: ${entry.name}" }
                val name = parts.lastOrNull().orEmpty()
                val dir = parts.dropLast(1).joinToString("/")
                val bytes = input.readBounded(entry.name)
                when {
                    dir == "blocks" && name.endsWith(".md") ->
                        blocks += BlockFile.parse(checkedWritableBlockId(name.removeSuffix(".md")), bytes.decodeToString())

                    dir == "groups" && name.endsWith(".yaml") && name.removeSuffix(".yaml") != ALL_GROUP_ID -> {
                        val group = Yaml.default.decodeFromString(Group.serializer(), bytes.decodeToString())
                        require(checkedLibraryId(group.id) == name.removeSuffix(".yaml")) {
                            "Group id '${group.id}' does not match archive path '${entry.name}'"
                        }
                        groups += group
                    }

                    dir == "groups" && name == "$ALL_GROUP_ID.yaml" -> Unit

                    dir == "profiles" && name.endsWith(".yaml") -> {
                        val profile = Yaml.default.decodeFromString(Profile.serializer(), bytes.decodeToString())
                        require(checkedLibraryId(profile.id) == name.removeSuffix(".yaml")) {
                            "Profile id '${profile.id}' does not match archive path '${entry.name}'"
                        }
                        profiles += profile
                    }

                    parts.size == 3 && parts[0] == "skills" && parts[2] == "meta.yaml" -> {
                        val id = checkedSkillId(parts[1])
                        val meta = Yaml.default.decodeFromString(SkillMeta.serializer(), bytes.decodeToString())
                        skills[id] = (skills[id] ?: MutableSkill()).copy(meta = meta)
                    }

                    parts.size >= 4 && parts[0] == "skills" && parts[2] == "files" -> {
                        val id = checkedSkillId(parts[1])
                        val relative = parts.drop(3).joinToString("/")
                        require(relative.isNotBlank() && parts.drop(3).none { it == ".." }) {
                            "Invalid archived skill path: ${entry.name}"
                        }
                        val captured = skills.getOrPut(id, ::MutableSkill)
                        require(captured.files.none { it.path == relative }) { "Duplicate archived skill path: ${entry.name}" }
                        require(captured.files.size < MAX_ARCHIVED_SKILL_FILES) {
                            "Archived skill '$id' contains more than $MAX_ARCHIVED_SKILL_FILES files"
                        }
                        require(captured.files.sumOf { it.bytes.size.toLong() } + bytes.size <= MAX_ARCHIVED_SKILL_BYTES) {
                            "Archived skill '$id' exceeds $MAX_ARCHIVED_SKILL_BYTES bytes"
                        }
                        captured.files += SkillFile(relative, bytes)
                    }

                    else -> throw IllegalArgumentException("Unexpected archive entry: ${entry.name}")
                }
            }
        }
        val snapshots = skills.map { (id, captured) ->
            val meta = requireNotNull(captured.meta) { "Archived skill '$id' has no meta.yaml" }
            val skillFile = captured.files.find { it.path == "SKILL.md" }
                ?: error("Archived skill '$id' has no SKILL.md")
            SkillSnapshot(
                Skill(
                    id = id,
                    name = meta.name,
                    description = meta.description,
                    version = meta.version,
                    content = skillFile.bytes.decodeToString(),
                    source = meta.source,
                    forkedFrom = meta.forkedFrom,
                ),
                captured.files.map { file -> file.copy(executable = file.path in meta.executableFiles) },
            )
        }
        requirePortableFileTree(seenPaths.toList())
        return ArchiveContents(blocks, groups, snapshots, profiles)
    }

    /** Compares [zip] against the current library without writing anything. */
    fun plan(zip: Path): ImportPlan {
        val contents = read(zip)
        repository.validateImport(contents.blocks, contents.groups, contents.skills, contents.profiles)
        return ImportMerge.plan(
            contents.blocks,
            contents.groups,
            repository.listBlocks(),
            repository.listGroups(),
            contents.skills,
            repository.listSkills().mapNotNull { repository.loadSkillSnapshot(it.id) },
            contents.profiles,
            repository.listProfiles(),
        )
    }

    /**
     * Applies the picked changes. Blocks and groups keep the version they carry in the archive —
     * importing reproduces what the archive holds, it does not author a new revision of it; conflicts
     * are only written when the user explicitly picked them. The whole batch holds the library write
     * lock, so another writer cannot slip between two items of one import.
     */
    fun apply(
        plan: ImportPlan,
        blockIds: Set<String>,
        groupIds: Set<String>,
        skillIds: Set<String> = emptySet(),
        profileIds: Set<String> = emptySet(),
    ) = repository.withLock {
        repository.validateImport(
            plan.blocks.filter { it.incoming.id in blockIds }.map { it.incoming },
            plan.groups.filter { it.incoming.id in groupIds }.map { it.incoming },
            plan.skills.filter { it.incoming.skill.id in skillIds }.map { it.incoming },
            plan.profiles.filter { it.incoming.id in profileIds }.map { it.incoming },
        )
        plan.blocks.filter { it.incoming.id in blockIds }.forEach { repository.writeArchiveBlock(it.incoming) }
        plan.groups.filter { it.incoming.id in groupIds }.forEach { repository.writeGroup(it.incoming) }
        plan.skills.filter { it.incoming.skill.id in skillIds }.forEach { repository.writeSkill(it.incoming) }
        plan.profiles.filter { it.incoming.id in profileIds }.forEach { repository.writeProfile(it.incoming) }
    }

    /** Reads the current zip entry, aborting once decompressed output exceeds [MAX_ENTRY_BYTES]. */
    private fun ZipInputStream.readBounded(entryName: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            require(total <= MAX_ENTRY_BYTES) { "Archive entry '$entryName' exceeds $MAX_ENTRY_BYTES bytes decompressed" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The portable layout [read] accepts. Export writes nothing else, so a stray file in the library
     * folder (Finder's `.DS_Store`, an editor backup) cannot make the library's own archive unreadable.
     */
    private fun isPortableEntry(parts: List<String>): Boolean = when {
        parts.size == 2 && parts[0] == "blocks" -> parts[1].endsWith(".md")
        parts.size == 2 && parts[0] == "groups" -> parts[1].endsWith(".yaml") && parts[1] != "$ALL_GROUP_ID.yaml"
        parts.size == 2 && parts[0] == "profiles" -> parts[1].endsWith(".yaml")
        parts.size == 3 && parts[0] == "skills" -> parts[2] == "meta.yaml"
        parts.size >= 4 && parts[0] == "skills" -> parts[2] == "files"
        else -> false
    }

    /**
     * Folder metadata that macOS and Windows add around a library and its ZIP. Earlier exports
     * copied it verbatim; a skill's own files are kept byte-exact and never filtered.
     */
    private fun isOsMetadata(parts: List<String>): Boolean {
        if (parts.size >= 4 && parts[0] == "skills" && parts[2] == "files") return false
        return parts[0] == "__MACOSX" || parts.last() in OS_METADATA_FILES
    }

    private fun checkedSkillId(id: String): String {
        checkedLibraryId(id)
        return id
    }
}
