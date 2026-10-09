package dev.ruleblend.core.integration

import dev.ruleblend.core.model.slugify

/**
 * Splits a body of text into [Section]s at caller-chosen inter-line cut points.
 *
 * Complementary to [SectionExtractor], which splits at markdown headings: the splitter is a
 * heading-free tool for cutting a large block into smaller ones when the file has no headings or
 * has them at the wrong grain. Pure: text plus cuts in, sections out. No I/O.
 */
object Splitter {

    /**
     * Splits [text] at [cuts]: each cut is a line index in `1..lines.size-1` meaning "cut between
     * line index-1 and index". Duplicate and out-of-range cuts are dropped silently. Zero cuts →
     * single section spanning the whole text.
     *
     * Every returned section has empty [Section.title] and [Section.heading]; the caller decides
     * whether to reattach a heading (see [splitSection]). Bodies are trimmed at both ends; a part
     * that trims to blank is still returned so callers can flag it.
     */
    fun split(text: String, cuts: Collection<Int>): List<Section> {
        val lines = text.lines()
        val sorted = cuts.filter { it in 1 until lines.size }.toSortedSet().toList()
        if (sorted.isEmpty()) return listOf(untitled(text))
        val boundaries = listOf(0) + sorted + lines.size
        return boundaries.zipWithNext { start, end ->
            untitled(lines.subList(start, end).joinToString("\n"))
        }
    }

    /**
     * Splits [section]'s body at [cuts]. The first sub-section inherits [section]'s heading (and its
     * title/slug when present), so `sub.map { it.sourceText }` concatenates back into the original
     * section's `sourceText` up to blank-line trimming — this keeps a `Skip` write-back honest for
     * any sub-part after an adopt-time split.
     */
    fun splitSection(section: Section, cuts: Collection<Int>): List<Section> {
        val parts = split(section.body, cuts)
        if (section.heading.isEmpty()) return parts
        val first = parts.first()
        val displayTitle = section.title.ifBlank { first.displayTitle }
        val restored = first.copy(
            title = section.title,
            heading = section.heading,
            slug = slugify(displayTitle),
        )
        return listOf(restored) + parts.drop(1)
    }

    private fun untitled(text: String): Section {
        val trimmed = text.trim()
        val stub = Section(title = "", slug = "", heading = "", body = trimmed, lineCount = trimmed.lineCount())
        return stub.copy(slug = slugify(stub.displayTitle))
    }

    private fun String.lineCount(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
}
