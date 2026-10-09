package dev.ruleblend.core.translate

/** One paragraph of a document beside the same paragraph of its translation. */
data class TranslatedParagraph(
    val source: String,
    /** `null` while the translation is still on its way. */
    val target: String?,
)

/**
 * Pairs a document with its translation paragraph by paragraph.
 *
 * Reading a translated file means comparing one thought against one thought, not two walls of text
 * whose lines drift apart the moment one language is wordier than the other. Both sides are cut by
 * the same segmenter, so the cut points are the document's own blocks; a translation is assembled
 * from the source structure and keeps it, which is what makes the two lists line up.
 *
 * When they do not line up — a translation that folded blocks together, or no translation yet —
 * the whole text is returned as one pair rather than paragraphs paired with the wrong partner.
 */
fun alignParagraphs(original: String, translated: String?): List<TranslatedParagraph> {
    val source = paragraphs(original)
    if (translated == null) {
        return source.ifEmpty { listOf(original.trimEnd()) }.map { TranslatedParagraph(it, null) }
    }
    val target = paragraphs(translated)
    if (source.isEmpty() || source.size != target.size) {
        return listOf(TranslatedParagraph(original.trimEnd(), translated.trimEnd()))
    }
    return source.zip(target) { from, to -> TranslatedParagraph(from, to) }
}

/**
 * The blocks of [text] as a reader sees them: everything between two blank lines is one block, and
 * a blank line inside a code fence is not a boundary because the fence is one segment.
 */
internal fun paragraphs(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    fun close() {
        val block = current.toString().trimEnd()
        if (block.isNotEmpty()) out += block
        current.clear()
    }
    for (segment in TranslationDocument.segments(text)) {
        if (segment is Verbatim && segment.raw.isBlank()) close() else current.append(segment.raw)
    }
    close()
    return out
}
