package dev.ruleblend.core.storage

import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.parseSkillFrontmatter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/** Captures a portable skill tree from disk without copying Git bookkeeping into the library. */
object LocalSkillCapture {
    fun capture(
        directory: Path,
        id: String = directory.name,
        source: GitSkillSource? = null,
        version: String = "1",
        executableFiles: Set<String>? = null,
    ): SkillSnapshot {
        val skillText = directory.resolve("SKILL.md").readText()
        val header = parseSkillFrontmatter(skillText)
        val files = files(directory, executableFiles)
        return SkillSnapshot(
            Skill(
                id = id,
                name = header.name?.takeIf { it.isNotBlank() } ?: directory.name,
                description = header.description.orEmpty(),
                version = version,
                content = skillText,
                source = source,
            ),
            files,
        )
    }

    fun files(directory: Path, executableFiles: Set<String>? = null): List<SkillFile> {
        val files = Files.walk(directory).use { paths ->
            paths.filter { it.isRegularFile() && !isGitInternalPath(directory, it) }.sorted().map { file ->
                require(!Files.isSymbolicLink(file)) { "Skill contains a symbolic link: ${directory.relativize(file)}" }
                val size = Files.size(file)
                require(size <= SkillTreeLimits.MAX_FILE_BYTES) { "Skill file '${directory.relativize(file)}' exceeds ${SkillTreeLimits.MAX_FILE_BYTES} bytes" }
                val relative = directory.relativize(file).joinToString("/")
                SkillFile(relative, file.readBytes(), executableFiles?.contains(relative) ?: skillExecutable(file))
            }.toList()
        }
        SkillTreeLimits.validate(files)
        return files
    }
}
