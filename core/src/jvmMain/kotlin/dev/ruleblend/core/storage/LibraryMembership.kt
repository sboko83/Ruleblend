package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.model.SkillMeta
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Membership of the reserved `all` group, read straight from the library tree.
 *
 * Two places write that group: [LibraryRepository.syncAllGroup] after an ordinary mutation and
 * [LibraryGit] after a remote merge. They have to agree on the order as well as on the contents,
 * because the group's version bumps whenever the stored list differs — with two orderings each
 * device would renumber the other's `all` on every sync forever, and a target would keep
 * reinstalling an unchanged group.
 */
internal object LibraryMembership {

    /** Block ids ordered the way the library presents blocks: by display name, then id. */
    fun blockIds(root: Path): List<String> = root.resolve("blocks")
        .entries()
        .filter { it.isRegularFile() && it.name.endsWith(".md") }
        .mapNotNull { file ->
            val id = file.name.removeSuffix(".md")
            runCatching { BlockFile.parse(id, file.readText()).name }.getOrNull()?.let { id to it }
        }
        .sortedWith(byNameThenId)
        .map { it.first }

    /** Skill ids ordered by display name, then id; a directory without both `meta.yaml` and `SKILL.md` is not a skill yet. */
    fun skillIds(root: Path): List<String> = root.resolve("skills")
        .entries()
        .filter { it.isDirectory() && !it.name.startsWith(".") }
        .mapNotNull { directory ->
            val meta = directory.resolve("meta.yaml")
            if (!meta.isRegularFile() || !directory.resolve("files/SKILL.md").isRegularFile()) return@mapNotNull null
            runCatching { Yaml.default.decodeFromString(SkillMeta.serializer(), meta.readText()).name }
                .getOrNull()
                ?.let { directory.name to it }
        }
        .sortedWith(byNameThenId)
        .map { it.first }

    /**
     * Two objects may share a display name, and a name-only sort would then fall back to directory
     * order — which differs between filesystems, and so between the devices sharing one library.
     */
    private val byNameThenId = compareBy<Pair<String, String>>({ it.second }, { it.first })
    private fun Path.entries(): List<Path> = if (exists()) listDirectoryEntries() else emptyList()
}
