package dev.ruleblend.core.storage

import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.requirePortableFileTree

/** Shared bounds for captured, imported and locally edited skill trees. */
object SkillTreeLimits {
    const val MAX_FILES = 500
    const val MAX_FILE_BYTES = 10 * 1024 * 1024
    const val MAX_BYTES = 25 * 1024 * 1024

    fun validate(files: List<SkillFile>) {
        require(files.size <= MAX_FILES) { "Skill contains more than $MAX_FILES files" }
        require(files.sumOf { it.bytes.size.toLong() } <= MAX_BYTES) { "Skill exceeds $MAX_BYTES bytes" }
        requirePortableFileTree(files.map { it.path })
        files.forEach { file ->
            require(file.bytes.size <= MAX_FILE_BYTES) { "Skill file '${file.path}' exceeds $MAX_FILE_BYTES bytes" }
            require(file.path.split('/').none { it.equals(".git", ignoreCase = true) }) {
                "Git bookkeeping is not skill content: ${file.path}"
            }
        }
    }
}
