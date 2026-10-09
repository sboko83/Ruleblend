package dev.ruleblend.core.integration

/**
 * A block installed into a target file, delimited by managed-region markers: a `<!-- kb ... -->`
 * line per block, consecutive blocks forming a run closed by a single `<!-- kb:end -->`.
 * [hash] is the hash of the content as written by Ruleblend; it stops matching [content]
 * once the user edits the region by hand.
 */
data class ManagedRegion(
    val id: String,
    val version: Int,
    val group: String?,
    val hash: String,
    val content: String,
    /** `g=<group-id>` or `p=<profile-id>` when the install has a non-base origin. */
    val origin: String? = group?.let { "g=$it" },
)

/**
 * Hand-written content of a target file: everything outside the managed regions.
 * [lineCount] counts the lines of [text] — what adopting would actually take into a block — not the
 * raw file lines outside the markers: blank-only gaps between regions are dropped, and each fragment
 * is trimmed of surrounding blank lines. So the number can be lower than an editor would show.
 */
/**
 * Hand-written text of a file. [linesBeforeRun] counts the lines with text that precede the managed run
 * once the file is written as `rb1`; null when the file has no run yet. It is what lets a section
 * split of [text] know on which side of the run each section lies (see [AdoptPlacement]).
 */
data class UnmanagedContent(val text: String, val lineCount: Int, val linesBeforeRun: Int? = null) {
    val isEmpty: Boolean get() = lineCount == 0
}

// One line per block: id, version, optional g:<group>, optional hash. Each begin implicitly ends
// the previous block; a run of consecutive blocks is closed by a single END line.
private val BEGIN = Regex("""^<!--\s*kb\s+(\S+)\s+v(\d+)(?:\s+g:(\S+))?(?:\s+([0-9a-f]+))?\s*-->\s*$""")
private val END = Regex("""^<!--\s*kb:end\s*-->\s*$""")

// Pre-1.4 per-block marker pairs. Still parsed; any write re-renders in the current format.
private val LEGACY_BEGIN = Regex("""^<!--\s*(?:kitbash|ruleblend):begin\s+(.*?)\s*-->\s*$""")
private val LEGACY_END = Regex("""^<!--\s*(?:kitbash|ruleblend):end\s+id=(\S+)\s*-->\s*$""")

/** FNV-1a over UTF-8 bytes; short and stable across platforms. */
fun hashContent(content: String): String {
    var hash = 2166136261u
    for (byte in content.encodeToByteArray()) {
        hash = hash xor byte.toUByte().toUInt()
        hash *= 16777619u
    }
    return hash.toString(16).padStart(8, '0')
}

/**
 * The regions sorted into [order] by id. Ids [order] does not mention keep their relative sequence
 * after the ordered ones: a stale order list may not silently drop or shuffle a block that was
 * installed while the screen was open.
 */
internal fun List<ManagedRegion>.inOrderOf(order: List<String>): List<ManagedRegion> =
    sortedBy { region -> order.indexOf(region.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }

/**
 * Reads and edits managed regions in a markdown document.
 * Content outside the markers is never touched, save for the blank line separating it from them.
 */
object ManagedDocument {

    fun parse(text: String): List<ManagedRegion> =
        parseSegments(text).filterIsInstance<Segment.Run>().flatMap { it.regions }

    /** Removes only marker-delimited spans for migration; preview text is normalized and cannot be used for writes. */
    internal fun withoutManagedRegions(text: String): String {
        val source = NormalizedText(text)
        val lineStarts = buildList {
            add(0)
            source.text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        val ranges = mutableListOf<Pair<Int, Int>>()
        parseSegments(text) { startLine, endLine ->
            ranges += source.sourceOffset(lineStarts[startLine]) to
                source.sourceOffset(lineStarts.getOrElse(endLine) { source.text.length })
        }
        return buildString {
            var cursor = 0
            for ((start, end) in ranges) {
                append(text, cursor, start)
                cursor = end
            }
            append(text, cursor, text.length)
        }
    }

    /** The document in file order: hand-written fragments and runs of managed regions. */
    fun segments(text: String): List<FileSegment> = parseSegments(text).map { segment ->
        when (segment) {
            is Segment.Text -> FileSegment.Hand(segment.lines.joinToString("\n"))
            is Segment.Run -> FileSegment.Run(segment.regions)
        }
    }

    /** Replaces the region with [region]'s id in place, or appends it when absent. */
    fun upsert(text: String, region: ManagedRegion): String {
        val segments = parseSegments(text).toMutableList()
        val index = segments.indexOfFirst { it is Segment.Run && it.regions.any { r -> r.id == region.id } }
        if (index >= 0) {
            val run = segments[index] as Segment.Run
            segments[index] = Segment.Run(run.regions.map { if (it.id == region.id) region else it })
        } else {
            when (val last = segments.lastOrNull()) {
                is Segment.Run -> segments[segments.size - 1] = Segment.Run(last.regions + region)
                else -> segments += Segment.Run(listOf(region))
            }
        }
        return render(segments)
    }

    /** Drops the region with [id]; a no-op when it is not installed. */
    fun remove(text: String, id: String): String {
        val segments = parseSegments(text)
        if (segments.none { it is Segment.Run && it.regions.any { r -> r.id == id } }) return text
        val kept = segments.mapNotNull { segment ->
            when (segment) {
                is Segment.Text -> segment
                is Segment.Run -> segment.regions.filter { it.id != id }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Segment.Run(it) }
            }
        }
        return render(mergeRuns(kept))
    }

    /**
     * Reorders the managed regions to match [order]. Nothing else moves: hand-written fragments keep
     * their places, and each region is rendered back with the body the file already held, so this is
     * a write about sequence only. Regions are reordered inside their own run — a run is a contiguous
     * stretch of the file, and moving a block across the hand-written text between two runs would be
     * a different edit. Returns [text] unchanged when the order already matches.
     */
    fun reorder(text: String, order: List<String>): String {
        val segments = parseSegments(text)
        val reordered = segments.map { segment ->
            if (segment is Segment.Run) Segment.Run(segment.regions.inOrderOf(order)) else segment
        }
        if (reordered == segments) return text
        return render(reordered)
    }

    /**
     * Content outside the markers, fragments joined in file order with a blank line.
     * Blank-only fragments are ignored: whitespace between regions is not hand-written content.
     */
    fun unmanaged(text: String): UnmanagedContent {
        val segments = parseSegments(text)
        val fragments = segments.filterIsInstance<Segment.Text>()
        val unmanaged = fragments.joinToString("\n\n") { it.lines.joinToString("\n") }
        return UnmanagedContent(
            text = unmanaged,
            lineCount = fragments.sumOf { it.lines.size },
            // Migrating to rb1 gathers every legacy run into one after the hand-written text.
            linesBeforeRun = unmanaged.contentLines().takeIf { segments.any { it is Segment.Run } },
        )
    }

    /**
     * Cuts the unmanaged content and writes [region] back at the position of the first cut fragment.
     * All unmanaged fragments are consumed: this is the whole-file-as-one-block path. The only write
     * Ruleblend makes outside markers; user-invoked, one file at a time.
     */
    fun adopt(text: String, region: ManagedRegion): String =
        spliceAtFirstFragment(text, listOf(Segment.Run(listOf(region)))) ?: upsert(text, region)

    /**
     * One step of a multi-section adopt: splice a managed region for an adopted section, or leave
     * the body as hand-written text for a skipped one. The plan is walked in file order; the bodies
     * together cover the unmanaged text being replaced.
     */
    sealed interface AdoptStep {
        /** The section body that this step replaces or keeps. */
        val keptBody: String

        /** Splice [region] in place of this section's body. */
        data class Replace(override val keptBody: String, val region: ManagedRegion) : AdoptStep
        /** Leave this section's body as-is in the file. */
        data class Keep(override val keptBody: String) : AdoptStep
    }

    /**
     * Multi-section adopt. Replaces the unmanaged content with the rendered output of [plan] in file
     * order: each [AdoptStep.Replace] becomes a managed region, each [AdoptStep.Keep] stays as plain
     * text. Skipped sections therefore remain hand-written, while chosen sections become managed;
     * consecutive replaced sections share one run.
     */
    fun adoptSections(text: String, plan: List<AdoptStep>): String {
        if (plan.isEmpty()) return text
        val replaceSteps = plan.filterIsInstance<AdoptStep.Replace>()
        if (replaceSteps.isEmpty()) return text

        val rendered = mutableListOf<Segment>()
        plan.forEach { step ->
            when (step) {
                is AdoptStep.Replace -> when (val last = rendered.lastOrNull()) {
                    is Segment.Run -> rendered[rendered.size - 1] = Segment.Run(last.regions + step.region)
                    else -> rendered += Segment.Run(listOf(step.region))
                }
                is AdoptStep.Keep -> {
                    val lines = step.keptBody.lines().trimBoundaryBlanks()
                    if (lines.isNotEmpty()) rendered += Segment.Text(lines)
                }
            }
        }
        // No unmanaged text to splice into — fold the first region through upsert, ignore the rest.
        return spliceAtFirstFragment(text, rendered) ?: upsert(text, replaceSteps.first().region)
    }

    private sealed interface Segment {
        /** Hand-written lines, trimmed of surrounding blank lines; interior blanks kept verbatim. */
        data class Text(val lines: List<String>) : Segment
        /** Consecutive managed regions rendered under one terminator. */
        data class Run(val regions: List<ManagedRegion>) : Segment
    }

    /** Replaces all unmanaged fragments with [replacement] at the first fragment's position, or null when there are none. */
    private fun spliceAtFirstFragment(text: String, replacement: List<Segment>): String? {
        val segments = parseSegments(text)
        if (segments.none { it is Segment.Text }) return null
        val kept = buildList {
            var placed = false
            segments.forEach { segment ->
                when (segment) {
                    is Segment.Text -> if (!placed) {
                        addAll(replacement)
                        placed = true
                    }
                    is Segment.Run -> add(segment)
                }
            }
        }
        return render(mergeRuns(kept))
    }

    private fun mergeRuns(segments: List<Segment>): List<Segment> = buildList {
        segments.forEach { segment ->
            val last = lastOrNull()
            if (segment is Segment.Run && last is Segment.Run) {
                set(size - 1, Segment.Run(last.regions + segment.regions))
            } else {
                add(segment)
            }
        }
    }

    /**
     * Splits the document into hand-written text and runs of managed regions. Regions separated
     * only by blank lines fall into one run; leading, trailing, and separating blank lines around
     * regions belong to the markers, not to the text.
     */
    private fun parseSegments(text: String, onManagedSpan: (Int, Int) -> Unit = { _, _ -> }): List<Segment> {
        val lines = normalize(text).lines().map { it.trimEnd('\r') }
        val seen = mutableSetOf<String>()
        val segments = mutableListOf<Segment>()
        val pending = mutableListOf<String>()

        fun addRegion(region: ManagedRegion, beginLine: Int) {
            require(seen.add(region.id)) { "Marker '${region.id}' at line ${beginLine + 1}: duplicate id" }
            val blankOnly = pending.all { it.isBlank() }
            if (!blankOnly) segments += Segment.Text(pending.trimBoundaryBlanks())
            pending.clear()
            val last = segments.lastOrNull()
            if (blankOnly && last is Segment.Run) {
                segments[segments.size - 1] = Segment.Run(last.regions + region)
            } else {
                segments += Segment.Run(listOf(region))
            }
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val legacy = LEGACY_BEGIN.find(line)
            if (legacy != null) {
                val attributes = parseAttributes(legacy.groupValues[1])
                val id = requireNotNull(attributes["id"]) { "Marker at line ${index + 1}: missing id" }
                val end = (index + 1 until lines.size).firstOrNull { at ->
                    LEGACY_END.find(lines[at])?.groupValues?.get(1) == id
                }
                require(end != null) { "Marker '$id' at line ${index + 1}: missing ruleblend:end" }
                addRegion(
                    ManagedRegion(
                        id = id,
                        version = attributes["v"]?.toIntOrNull() ?: 0,
                        group = attributes["group"],
                        hash = attributes["hash"].orEmpty(),
                        content = lines.subList(index + 1, end).joinToString("\n"),
                    ),
                    beginLine = index,
                )
                onManagedSpan(index, end + 1)
                index = end + 1
                continue
            }
            if (BEGIN.containsMatchIn(line)) {
                val end = scanRun(lines, index, ::addRegion)
                onManagedSpan(index, end)
                index = end
                continue
            }
            pending += line
            index++
        }
        if (pending.any { it.isNotBlank() }) segments += Segment.Text(pending.trimBoundaryBlanks())
        return segments
    }

    /** Consumes one run of new-format blocks starting at [start]; returns the index after its terminator. */
    private fun scanRun(lines: List<String>, start: Int, addRegion: (ManagedRegion, Int) -> Unit): Int {
        var beginIndex = start
        while (true) {
            val begin = BEGIN.find(lines[beginIndex])!!
            var index = beginIndex + 1
            while (index < lines.size && !END.matches(lines[index]) && !BEGIN.containsMatchIn(lines[index])) {
                require(!LEGACY_BEGIN.containsMatchIn(lines[index])) {
                    "Marker at line ${index + 1}: ruleblend:begin inside an open kb run"
                }
                index++
            }
            require(index < lines.size) { "Marker '${begin.groupValues[1]}' at line ${beginIndex + 1}: missing kb:end" }
            addRegion(
                ManagedRegion(
                    id = begin.groupValues[1],
                    version = begin.groupValues[2].toInt(),
                    group = begin.groupValues[3].takeIf { it.isNotEmpty() },
                    hash = begin.groupValues[4],
                    content = lines.subList(beginIndex + 1, index).joinToString("\n"),
                ),
                beginIndex,
            )
            if (END.matches(lines[index])) return index + 1
            beginIndex = index
        }
    }

    private fun parseAttributes(text: String): Map<String, String> = text
        .split(Regex("\\s+"))
        .filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Segment bodies joined with a blank line; a written document always ends with one newline. */
    private fun render(segments: List<Segment>): String {
        if (segments.isEmpty()) return ""
        return segments.joinToString("\n\n") { segment ->
            when (segment) {
                is Segment.Text -> segment.lines.joinToString("\n")
                is Segment.Run -> renderRun(segment.regions).joinToString("\n")
            }
        } + "\n"
    }

    private fun renderRun(regions: List<ManagedRegion>): List<String> = buildList {
        regions.forEach { region ->
            val group = region.group?.let { " g:$it" }.orEmpty()
            val hash = region.hash.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            add("<!-- kb ${region.id} v${region.version}$group$hash -->")
            addAll(region.content.lines())
        }
        add("<!-- kb:end -->")
    }

    private fun List<String>.trimBoundaryBlanks(): List<String> =
        dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }

    private fun normalize(text: String): String = text.replace("\r\n", "\n")
}
