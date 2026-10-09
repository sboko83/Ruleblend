package dev.ruleblend.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a user-entered display name is normalized on create. */
@Serializable
enum class NameFormat {
    @SerialName("kebab")
    KEBAB,

    @SerialName("camel")
    CAMEL,

    @SerialName("snake")
    SNAKE,

    @SerialName("free")
    FREE,
}

private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

/**
 * Formats a display [name] to [format]. Unicode-aware (keeps non-ASCII letters), so it shapes the
 * shown name — the file id is still derived from it via [slugify]. [NameFormat.FREE] only trims.
 */
fun formatName(name: String, format: NameFormat): String {
    if (format == NameFormat.FREE) return name.trim()
    val words = name.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
    if (words.isEmpty()) return name.trim()
    return when (format) {
        NameFormat.KEBAB -> words.joinToString("-") { it.lowercase() }
        NameFormat.CAMEL -> words
            .mapIndexed { i, w -> if (i == 0) w.lowercase() else w.lowercase().replaceFirstChar(Char::uppercase) }
            .joinToString("")
        NameFormat.SNAKE -> words.joinToString("_") { it.lowercase() }
        NameFormat.FREE -> error("unreachable: handled by the early return above")
    }
}
