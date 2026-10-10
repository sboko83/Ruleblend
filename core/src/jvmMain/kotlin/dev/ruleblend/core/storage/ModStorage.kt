package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.model.ModMeta
import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.SkillFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** Called under the library lock for reads as well as writes: readers never observe a mixed tree. */
internal class ModStorage(private val root: Path, private val git: LibraryGit) {
    private val directory = root.resolve("mods")

    fun path(id: String): Path {
        val checked = checkedLibraryId(id)
        require(!Files.isSymbolicLink(directory)) { "Mods root cannot be a symbolic link" }
        if (directory.exists()) require(directory.listDirectoryEntries().none {
            it.name != checked && it.name.equals(checked, ignoreCase = true)
        }) { "Mod '$id' collides with an existing name" }
        return directory.resolve(checked)
    }

    fun list(): List<ModSnapshot> = if (!directory.exists()) emptyList() else directory.listDirectoryEntries()
        .filter { !it.name.startsWith('.') }
        .mapNotNull { load(it.name) }.sortedWith(compareBy({ it.mod.name }, { it.mod.id }))

    fun load(id: String): ModSnapshot? {
        val target = path(id)
        check(!rollback(id).exists()) { "Mod recovery required at ${rollback(id)}" }
        if (!target.exists(NOFOLLOW_LINKS)) return null
        require(Files.isDirectory(target, NOFOLLOW_LINKS)) { "Mod must be a real directory" }
        val metaPath = target.resolve("meta.yaml")
        require(Files.isRegularFile(metaPath, NOFOLLOW_LINKS) && Files.size(metaPath) <= SkillTreeLimits.MAX_FILE_BYTES) {
            "Invalid mod metadata"
        }
        val meta = Yaml.default.decodeFromString(ModMeta.serializer(), metaPath.readText())
        require(meta.formatVersion == 1 && meta.mod.id == id) { "Unsupported or mismatched mod metadata" }
        val filesRoot = target.resolve("files")
        require(Files.isDirectory(filesRoot, NOFOLLOW_LINKS)) { "Invalid mod payload root" }
        val paths = Files.walk(filesRoot).use { entries ->
            entries.peek { file ->
                require(!Files.isSymbolicLink(file) &&
                    (Files.isDirectory(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))) {
                    "Unsupported mod entry: $file"
                }
            }.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.limit(SkillTreeLimits.MAX_FILES + 1L).toList()
        }
        require(paths.size <= SkillTreeLimits.MAX_FILES) { "Too many mod files" }
        val sizes = paths.map(Files::size)
        require(sizes.all { it <= SkillTreeLimits.MAX_FILE_BYTES } && sizes.sum() <= SkillTreeLimits.MAX_BYTES) {
            "Mod payload exceeds tree limits"
        }
        val files = paths.map { file ->
            val relative = filesRoot.relativize(file).joinToString("/")
            SkillFile(relative, Files.readAllBytes(file), relative in meta.executableFiles)
        }.sortedBy { it.path }
        val snapshot = ModSnapshot(meta.mod, files)
        validateModSnapshot(snapshot)
        require(meta.executableFiles.distinct().size == meta.executableFiles.size &&
            meta.executableFiles.all { executable -> files.any { it.path == executable } }) { "Invalid executable metadata" }
        require(meta.fingerprint == modTreeFingerprint(files)) { "Mod fingerprint mismatch" }
        return snapshot
    }

    fun write(snapshot: ModSnapshot) {
        validateModSnapshot(snapshot)
        val target = path(snapshot.mod.id)
        val before = load(snapshot.mod.id)
        if (before == snapshot.copy(files = snapshot.files.sortedBy { it.path })) return
        directory.createDirectories()
        val backup = rollback(snapshot.mod.id)
        val staged = Files.createTempDirectory(directory, ".${snapshot.mod.id}.import-")
        try {
            val files = snapshot.files.sortedBy { it.path }
            files.forEach { file ->
                val destination = staged.resolve("files").resolve(file.path)
                AtomicWrite.write(destination, file.bytes)
                setSkillExecutable(destination, file.executable)
            }
            val meta = ModMeta(snapshot.mod, modTreeFingerprint(files), files.filter { it.executable }.map { it.path })
            AtomicWrite.write(staged.resolve("meta.yaml"), Yaml.default.encodeToString(ModMeta.serializer(), meta) + "\n")
            if (before != null) AtomicMove.move(target, backup)
            try {
                AtomicMove.move(staged, target)
                git.commit("Save mod ${snapshot.mod.id}", root.relativize(target))
            } catch (error: Exception) {
                runCatching {
                    remove(target)
                    if (before != null) AtomicMove.move(backup, target)
                }.onFailure(error::addSuppressed)
                runCatching { git.restoreIndex(root.relativize(target)) }.onFailure(error::addSuppressed)
                throw error
            }
            remove(backup)
        } finally {
            remove(staged)
        }
    }

    fun delete(id: String) {
        val target = path(id)
        if (load(id) == null) return
        val backup = rollback(id)
        AtomicMove.move(target, backup)
        try {
            git.commit("Delete mod $id", root.relativize(target))
        } catch (error: Exception) {
            runCatching { AtomicMove.move(backup, target) }.onFailure(error::addSuppressed)
            runCatching { git.restoreIndex(root.relativize(target)) }.onFailure(error::addSuppressed)
            throw error
        }
        remove(backup)
    }

    private fun rollback(id: String): Path = directory.resolve(".${checkedLibraryId(id)}.rollback")

    private fun remove(path: Path) {
        if (!path.exists(NOFOLLOW_LINKS)) return
        Files.walk(path).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
