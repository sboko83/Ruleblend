package dev.ruleblend.core.model

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.DeserializationStrategy

private const val MAX_YAML_FRONTMATTER_LINES = 200

/** A decoded YAML header and the document body that follows it. */
data class YamlFrontmatter<T>(
    val meta: T,
    val body: String,
)

/**
 * Splits a bounded YAML frontmatter header from the body that follows it, without interpreting the
 * header. Missing or unterminated frontmatter returns `null`.
 */
fun splitYamlFrontmatter(text: String): YamlFrontmatter<String>? {
    val lines = text.replace("\r\n", "\n").split('\n')
    if (lines.firstOrNull()?.trim() != "---") return null
    val endOffset = lines.drop(1).take(MAX_YAML_FRONTMATTER_LINES).indexOfFirst { it.trim() == "---" }
    if (endOffset < 0) return null

    val closingLine = endOffset + 1
    val header = lines.subList(1, closingLine).joinToString("\n")
    val bodyLines = lines.drop(closingLine + 1).let { body ->
        if (body.firstOrNull()?.isBlank() == true) body.drop(1) else body
    }
    return YamlFrontmatter(header, bodyLines.joinToString("\n"))
}

/**
 * Decodes a bounded YAML frontmatter header. Missing or unterminated frontmatter returns `null`;
 * invalid YAML remains a parse error for the caller to handle according to its input contract.
 */
fun <T> parseYamlFrontmatter(
    text: String,
    deserializer: DeserializationStrategy<T>,
): YamlFrontmatter<T>? {
    val split = splitYamlFrontmatter(text) ?: return null
    val meta = Yaml(configuration = YamlConfiguration(strictMode = false)).decodeFromString(
        deserializer,
        split.meta.ifBlank { "{}" },
    )
    return YamlFrontmatter(meta, split.body)
}
