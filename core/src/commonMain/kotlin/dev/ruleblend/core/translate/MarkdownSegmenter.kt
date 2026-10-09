package dev.ruleblend.core.translate

/**
 * Splits markdown into pieces the machine translator may see and pieces it must never see.
 *
 * The system translator works on plain text and knows nothing about markdown, so structure is cut
 * here instead of being described in a prompt: code fences, Ruleblend markers and tables are never
 * handed over, and inline spans that must survive byte-for-byte (`code`, URLs, flags) are swapped
 * for placeholders before the request and swapped back after it. A segment whose placeholders do
 * not come back intact falls back to its original text — an untranslated paragraph beats a mangled
 * `--dangerously-skip-permissions`.
 */
sealed interface Segment {
    /** The segment exactly as it appeared in the source, line separators included. */
    val raw: String
}

/** Copied through untouched: code, markers, tables, blank lines. */
data class Verbatim(override val raw: String) : Segment

/**
 * Prose the translator may see. [prefix] is the markdown scaffolding (`## `, `- `, `> `, indent)
 * that stays literal; [body] is the text handed over. `raw == prefix + body + suffix`.
 */
data class Translatable(
    val prefix: String,
    val body: String,
    /** Trailing line separators, kept so assembly is exact. */
    val suffix: String,
) : Segment {
    override val raw: String get() = prefix + body + suffix
}

/**
 * A markdown document prepared for translation: [requests] goes to the translator, and the results
 * come back through [assemble] in the same order.
 */
class TranslationDocument private constructor(private val segments: List<Segment>) {

    private val masks: Map<Int, Mask> = segments.withIndex()
        .filter { it.value is Translatable }
        .associate { (index, segment) -> index to Mask.of(flatten((segment as Translatable).body)) }

    /** Indices into [segments] for the entries [requests] corresponds to. */
    private val order: List<Int> = masks.keys.sorted()

    /** Masked bodies, in document order. Empty when the document has no prose at all. */
    val requests: List<String> get() = order.map { masks.getValue(it).masked }

    /**
     * Rebuilds the document from [translations], which must line up with [requests]. A translation
     * that lost or duplicated a placeholder is dropped in favour of the original text.
     */
    fun assemble(translations: List<String>): String {
        require(translations.size == order.size) {
            "expected ${order.size} translations, got ${translations.size}"
        }
        val restored = order.withIndex().associate { (slot, index) ->
            index to masks.getValue(index).restore(translations[slot])
        }
        return segments.withIndex().joinToString("") { (index, segment) ->
            when (segment) {
                is Verbatim -> segment.raw
                is Translatable -> {
                    val body = restored[index]?.let { reflow(it, segment.body) } ?: segment.body
                    segment.prefix + body + segment.suffix
                }
            }
        }
    }

    /** The source document, unchanged. Used to verify segmentation is lossless. */
    fun source(): String = segments.joinToString("") { it.raw }

    companion object {
        fun of(text: String): TranslationDocument = TranslationDocument(segment(text))

        internal fun segments(text: String): List<Segment> = segment(text)
    }
}

// ---------------------------------------------------------------- line handling

/**
 * Joins a hand-wrapped paragraph into one line before it is translated.
 *
 * The system translator reads a line break as a paragraph boundary and answers with a blank line,
 * so a paragraph wrapped at 78 columns comes back with a blank line after every one of them. It
 * never sees the wrapping now; [reflow] puts it back.
 */
internal fun flatten(body: String): String =
    body.split("\n").joinToString(" ") { it.trim() }.trim()

/**
 * Re-wraps [translated] the way [original] was wrapped, and returns the original untouched when the
 * translation changed nothing — which keeps segmentation byte-exact for text that round-trips.
 */
internal fun reflow(translated: String, original: String): String {
    if (translated == flatten(original)) return original
    // Whatever the translator did to line breaks, the paragraph is one paragraph.
    val text = flatten(translated)
    val lines = original.split("\n")
    if (lines.size < 2) return text

    val width = lines.maxOf { it.length }
    val out = StringBuilder()
    var column = 0
    for (word in text.split(" ").filter { it.isNotEmpty() }) {
        when {
            column == 0 -> {
                out.append(word)
                column = word.length
            }

            column + 1 + word.length <= width -> {
                out.append(' ').append(word)
                column += 1 + word.length
            }

            else -> {
                out.append('\n').append(word)
                column = word.length
            }
        }
    }
    return out.toString()
}

// ---------------------------------------------------------------- segmentation

private val FENCE = Regex("^\\s{0,3}(`{3,}|~{3,})")
private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(\\[[ xX]]\\s+)?")
private val QUOTE = Regex("^(\\s{0,3}>\\s?)+")
private val THEMATIC_BREAK = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_ROW = Regex("^\\s*\\|")
private val INDENTED_CODE = Regex("^(\\t| {4})")

/**
 * One pass over the lines. Block kinds are decided by their opening line; anything the parser is
 * unsure about stays verbatim, because shipping markup to the translator is the expensive mistake.
 */
private fun segment(text: String): List<Segment> {
    if (text.isEmpty()) return emptyList()
    val lines = text.splitKeepingSeparators()
    val out = mutableListOf<Segment>()
    var i = 0

    // YAML frontmatter, but only when it opens the document.
    if (lines.first().content.trim() == "---") {
        val close = lines.indexOfFirst { it.content.trim() == "---" && it !== lines.first() }
        if (close > 0) {
            out += Verbatim(lines.subList(0, close + 1).joinToString("") { it.raw })
            i = close + 1
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val content = line.content

        when {
            content.isBlank() -> {
                out += Verbatim(line.raw)
                i++
            }

            FENCE.containsMatchIn(content) -> {
                val marker = FENCE.find(content)!!.groupValues[1].take(3)
                val start = i
                i++
                while (i < lines.size && !lines[i].content.trimStart().startsWith(marker)) i++
                if (i < lines.size) i++ // closing fence
                out += Verbatim(lines.subList(start, i).joinToString("") { it.raw })
            }

            content.trimStart().startsWith("<!--") -> {
                val start = i
                while (i < lines.size && !lines[i].content.contains("-->")) i++
                if (i < lines.size) i++
                out += Verbatim(lines.subList(start, i).joinToString("") { it.raw })
            }

            THEMATIC_BREAK.matches(content) || TABLE_ROW.containsMatchIn(content) -> {
                out += Verbatim(line.raw)
                i++
            }

            // Indented code only counts when it starts a block, so wrapped list items are unaffected.
            INDENTED_CODE.containsMatchIn(content) && out.lastOrNull().endsBlock() -> {
                val start = i
                while (i < lines.size && (lines[i].content.isBlank() || INDENTED_CODE.containsMatchIn(lines[i].content))) i++
                out += Verbatim(lines.subList(start, i).joinToString("") { it.raw })
            }

            HEADING.containsMatchIn(content) -> {
                out += line.translatable(HEADING.find(content)!!.value)
                i++
            }

            LIST_ITEM.containsMatchIn(content) -> {
                out += line.translatable(LIST_ITEM.find(content)!!.value)
                i++
            }

            QUOTE.containsMatchIn(content) -> {
                out += line.translatable(QUOTE.find(content)!!.value)
                i++
            }

            else -> {
                // A paragraph runs until a blank line or any line that opens a block of its own.
                val start = i
                i++
                while (i < lines.size && lines[i].content.isNotBlank() && lines[i].startsPlainLine()) i++
                val block = lines.subList(start, i)
                val raw = block.joinToString("") { it.raw }
                val suffix = raw.takeLastWhile { it == '\n' || it == '\r' }
                out += Translatable(prefix = "", body = raw.dropLast(suffix.length), suffix = suffix)
            }
        }
    }
    return out
}

/** Whether a paragraph may swallow this line instead of it opening a new block. */
private fun Line.startsPlainLine(): Boolean =
    !FENCE.containsMatchIn(content) &&
        !HEADING.containsMatchIn(content) &&
        !LIST_ITEM.containsMatchIn(content) &&
        !QUOTE.containsMatchIn(content) &&
        !TABLE_ROW.containsMatchIn(content) &&
        !THEMATIC_BREAK.matches(content) &&
        !content.trimStart().startsWith("<!--")

/** Indented code needs a block boundary in front of it; a plain paragraph continuation is not one. */
private fun Segment?.endsBlock(): Boolean = this == null || (this is Verbatim && raw.isBlank())

private fun Line.translatable(prefix: String): Translatable {
    val suffix = raw.takeLastWhile { it == '\n' || it == '\r' }
    val body = content.removePrefix(prefix)
    return Translatable(prefix = prefix, body = body, suffix = suffix)
}

/** A source line kept together with its separator so reassembly is byte-exact. */
private class Line(val raw: String) {
    val content: String = raw.trimEnd('\n', '\r')
}

private fun String.splitKeepingSeparators(): List<Line> {
    val out = mutableListOf<Line>()
    var start = 0
    var i = 0
    while (i < length) {
        if (this[i] == '\n') {
            out += Line(substring(start, i + 1))
            start = i + 1
        }
        i++
    }
    if (start < length) out += Line(substring(start))
    return out
}

// ---------------------------------------------------------------- masking

/**
 * Inline spans that must survive translation untouched, replaced by short tokens before the request.
 * The token shape is deliberately alphanumeric: translators tend to drop or "translate" bracketed
 * symbols, while a bare `RB0Z` reads as a name and comes back intact.
 */
internal class Mask private constructor(val masked: String, private val spans: List<String>) {

    /** Returns the translated text with the spans put back, or `null` when a token went missing. */
    fun restore(translated: String): String? {
        var out = translated
        spans.forEachIndexed { index, span ->
            val token = token(index)
            if (out.indexOf(token) != out.lastIndexOf(token)) return null // duplicated
            if (!out.contains(token)) return null // dropped
            out = out.replace(token, span)
        }
        return out
    }

    companion object {
        /** Longest-first so a URL inside inline code is masked once, as part of the code span. */
        private val PROTECTED = listOf(
            Regex("`[^`\\n]+`"),                        // inline code
            Regex("<!--.*?-->"),                        // Ruleblend and HTML markers
            Regex("\\]\\([^)\\s]+\\)"),                 // link target, so link text stays translatable
            Regex("https?://\\S+"),                     // bare URLs
            Regex("(?<![\\w-])(~|\\.{1,2})?/[\\w.@/\\-]+"), // paths
            Regex("(?<![\\w-])--?[a-zA-Z][\\w-]*"),     // CLI flags
            Regex("\\{[A-Za-z_][\\w.]*}"),              // {placeholders}
        )

        private fun token(index: Int) = "RB${index}Z"

        fun of(body: String): Mask {
            val spans = mutableListOf<String>()
            var out = body
            for (pattern in PROTECTED) {
                out = pattern.replace(out) { match ->
                    spans += match.value
                    token(spans.size - 1)
                }
            }
            return Mask(out, spans)
        }
    }
}
