package dev.ruleblend.core.integration

import dev.ruleblend.core.model.slugify

/**
 * A contiguous slice of unmanaged text identified by a markdown heading.
 *
 * - [title]: heading text with the leading `#` markers stripped; empty for text that sits before
 *   the first heading.
 * - [slug]: `slugify` of the effective name — [title], or the first non-blank body line when
 *   untitled. Used for dedup matching and new-block ids; empty only when there is nothing to name.
 * - [heading]: the raw heading line as it stood in the source (e.g. `## Coding`), empty for the
 *   untitled prefix. Kept apart from [body] so the block content is not headed by its own name.
 * - [body]: everything up to the next heading at the same or shallower level, trimmed. The heading
 *   itself is NOT included — it already lives in [title]/[heading]. This is what becomes a block's
 *   content, and what dedup matches against. May be blank (a heading with no content of its own):
 *   such sections are kept so write-back stays lossless, but are never offered for import.
 * - [lineCount]: line count of [body], for size warnings.
 */
data class Section(
    val title: String,
    val slug: String,
    val heading: String,
    val body: String,
    val lineCount: Int,
) {
    /** The section as it appeared in the source file: [heading] plus [body], for write-back. */
    val sourceText: String
        get() = if (heading.isEmpty()) body else if (body.isEmpty()) heading else "$heading\n$body"

    /** Depth of [heading] (1..6), or 0 when the section has none. */
    val headingLevel: Int
        get() = heading.takeWhile { it == '#' }.length

    /** [title], or the first non-blank body line (capped) when untitled; the block-name source. */
    val displayTitle: String
        get() {
            if (title.isNotBlank()) return title
            val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
            return firstLine.take(60).trim()
        }

    /**
     * Whether the section is worth offering as a library block. Blank bodies and bodies that are
     * nothing but HTML comments (generator banners like `<!-- GENERATED ... -->`) carry no rule
     * text; they stay in the section list only so file write-back keeps every source line.
     */
    val offerable: Boolean
        get() = body.replace(HTML_COMMENT, "").isNotBlank()

    private companion object {
        val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    }
}

/**
 * Splits unmanaged text into [Section]s along markdown headings.
 *
 * Splits at the shallowest heading level present, so a parent section's text is never mixed with
 * content from elsewhere in the file. When that level yields a single section that only wraps
 * deeper headings (a lone `# Title` over `##` subsections), the split descends into it — the
 * title keeps the intro text as its own section, the subsections become sections of their own.
 * Once a level yields ≥ 2 sections, deeper headings stay inside their section's body.
 *
 * Lossless: every non-blank source line lands in exactly one section ([Section.sourceText]s in
 * list order reproduce the input up to blank-line trimming). Heading-only sections are kept with a
 * blank body; flat text with no headings becomes one untitled section spanning the whole input.
 *
 * Pure: text in, sections out. No I/O.
 */
object SectionExtractor {

    private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")

    /**
     * Splits [text] into one or more sections. A blank input returns a single empty section so
     * callers can treat the result uniformly.
     */
    fun extract(text: String): List<Section> {
        if (text.isBlank()) return listOf(section("", "", ""))
        return split(text.lines())
    }

    private fun split(lines: List<String>): List<Section> {
        val level = lines.mapNotNull { HEADING.matchEntire(it)?.groupValues?.get(1)?.length }.minOrNull()
            ?: return listOf(untitled(lines))
        val sections = splitAtLevel(lines, level)
        val single = sections.singleOrNull() ?: return sections
        if (single.heading.isEmpty() || !containsHeading(single.body)) return sections
        return descend(single)
    }

    /**
     * Opens a single wrapper section: its intro (text before the first subheading) stays as the
     * parent's own section, the subsections become sections via a recursive [split].
     */
    private fun descend(parent: Section): List<Section> {
        val sub = split(parent.body.lines()).toMutableList()
        val head = sub.first()
        if (head.heading.isEmpty()) {
            sub[0] = section(parent.title, parent.heading, head.body)
        } else {
            sub.add(0, section(parent.title, parent.heading, ""))
        }
        return sub
    }

    /**
     * Splits [lines] at headings of exactly [level]; deeper headings stay inside bodies as text.
     * Text before the first [level] heading is split recursively (it can only contain deeper
     * headings), so a file that opens below the shallowest level still keeps its structure.
     */
    private fun splitAtLevel(lines: List<String>, level: Int): List<Section> {
        val result = mutableListOf<Section>()
        var headingLine: String? = null
        var body = mutableListOf<String>()

        fun flush() {
            val heading = headingLine
            if (heading == null) {
                if (body.any { it.isNotBlank() }) {
                    if (body.any { HEADING.matches(it) }) result += split(body) else result += untitled(body)
                }
            } else {
                val title = HEADING.matchEntire(heading)!!.groupValues[2].trim()
                result += section(title, heading, body.joinToString("\n").trim())
            }
            body = mutableListOf()
        }

        for (line in lines) {
            val match = HEADING.matchEntire(line)
            if (match != null && match.groupValues[1].length == level) {
                flush()
                headingLine = line
            } else {
                body += line
            }
        }
        flush()
        return result
    }

    private fun containsHeading(text: String): Boolean = text.lineSequence().any { HEADING.matches(it) }

    private fun untitled(lines: List<String>): Section = section("", "", lines.joinToString("\n").trim())

    private fun section(title: String, heading: String, body: String): Section {
        val trimmed = body.trim()
        val stub = Section(title, "", heading, trimmed, trimmed.lineCount())
        return stub.copy(slug = slugify(stub.displayTitle))
    }

    private fun String.lineCount(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
}
