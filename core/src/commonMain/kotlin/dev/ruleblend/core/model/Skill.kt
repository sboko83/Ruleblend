package dev.ruleblend.core.model

import kotlinx.serialization.Serializable

/**
 * Discovery metadata an agent reads from a skill's `SKILL.md` frontmatter. It deliberately keeps
 * only Ruleblend's common fields; agents may add arbitrary host-specific fields alongside them.
 */
@Serializable
data class SkillFrontmatter(
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
)

/**
 * Reads the common discovery fields from a skill document without requiring frontmatter. A missing
 * or unterminated header is ordinary agent content, not an invalid skill.
 */
fun parseSkillFrontmatter(text: String): SkillFrontmatter {
    return parseYamlFrontmatter(text, SkillFrontmatter.serializer())?.meta ?: SkillFrontmatter()
}

/** Exact upstream location of an imported skill. */
@Serializable
data class GitSkillSource(
    val repository: String,
    val revision: String,
    val path: String,
)

/**
 * A complete agent skill. [content] is `SKILL.md`; sibling assets live beside it in the skill's
 * library directory and are moved together by [dev.ruleblend.core.storage.LibraryRepository].
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String = "",
    /** Plugin/package version, or the short Git revision when the repository declares no version. */
    val version: String = "1",
    val content: String = "",
    /** Null for a local editable copy. Imported skills remain immutable and updateable. */
    val source: GitSkillSource? = null,
    /** Upstream revision an editable fork was created from; null for original local skills. */
    val forkedFrom: GitSkillSource? = null,
)

/** Metadata stored separately from the imported files so Ruleblend data is never installed as skill content. */
@Serializable
data class SkillMeta(
    val name: String,
    val description: String = "",
    val version: String = "1",
    val source: GitSkillSource? = null,
    /** Authoritative portable modes, independent of filesystem permissions and Git checkout modes. */
    val executableFiles: List<String> = emptyList(),
    /** Preserved provenance of a local fork without making the fork immutable. */
    val forkedFrom: GitSkillSource? = null,
)

/** One file captured from a skill directory. Paths are relative and use `/`. */
data class SkillFile(
    val path: String,
    val bytes: ByteArray,
    val executable: Boolean = false,
) {
    override fun equals(other: Any?): Boolean =
        other is SkillFile && path == other.path && bytes.contentEquals(other.bytes) && executable == other.executable

    override fun hashCode(): Int = 31 * (31 * path.hashCode() + bytes.contentHashCode()) + executable.hashCode()
}

/** Files and metadata ready to be saved as one atomic library mutation. */
data class SkillSnapshot(val skill: Skill, val files: List<SkillFile>)

/** Agent skill names are portable only in lowercase kebab-case. */
fun validSkillName(name: String): Boolean =
    name.length in 1..64 && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))

/** Creates a complete editable `SKILL.md`; the structured fields remain its source of truth. */
fun localSkillDocument(name: String, description: String = "", title: String = name): String =
    updateSkillDocumentMetadata("# $title\n", name, description)

/**
 * Updates only the standard discovery fields in a local `SKILL.md`, retaining its instructions and
 * any additional frontmatter keys. Imported skills never pass through this function.
 */
fun updateSkillDocumentMetadata(content: String, name: String, description: String): String {
    require(validSkillName(name)) { "Skill name must be lowercase kebab-case and at most 64 characters" }
    val normalized = content.replace("\r\n", "\n")
    val lines = normalized.lines()
    val closing = if (lines.firstOrNull() == "---") lines.drop(1).indexOfFirst { it == "---" } + 1 else 0
    val metadata = mutableListOf<String>()
    val body: String
    if (closing > 0) {
        var sawName = false
        var sawDescription = false
        lines.subList(1, closing).forEach { line ->
            when {
                line.startsWith("name:") -> {
                    metadata += "name: ${yamlQuoted(name)}"
                    sawName = true
                }
                line.startsWith("description:") -> {
                    metadata += "description: ${yamlQuoted(description)}"
                    sawDescription = true
                }
                else -> metadata += line
            }
        }
        if (!sawName) metadata.add(0, "name: ${yamlQuoted(name)}")
        if (!sawDescription) metadata.add(if (metadata.isEmpty()) 0 else 1, "description: ${yamlQuoted(description)}")
        body = lines.drop(closing + 1).joinToString("\n").trimStart('\n')
    } else {
        metadata += "name: ${yamlQuoted(name)}"
        metadata += "description: ${yamlQuoted(description)}"
        body = normalized.trimStart('\n')
    }
    return buildString {
        append("---\n")
        metadata.forEach { append(it).append('\n') }
        append("---\n\n")
        append(body)
        if (isNotEmpty() && last() != '\n') append('\n')
    }
}

private fun yamlQuoted(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}
