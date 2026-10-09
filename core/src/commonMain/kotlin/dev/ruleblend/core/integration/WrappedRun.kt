package dev.ruleblend.core.integration

import dev.ruleblend.core.model.LineEnding

/** Thrown before a wrapped run would overwrite text edited outside Ruleblend. */
class WrappedRunDriftException(message: String) : IllegalStateException(message)

/**
 * The rb1 encoding used by both partial and owned target files. Legacy kb1 runs remain readable.
 * Block boundaries are recovered
 * from the UTF-8 lengths in the manifest, so the target file remains self-contained.
 */
class WrappedRun(private val mode: TargetOwnershipMode, private val notice: Boolean = true) : TargetEncoding {
    init { require(mode == TargetOwnershipMode.PARTIAL || mode == TargetOwnershipMode.OWNED) }

    override fun installed(text: String): List<ManagedRegion> = try {
        parsed(text)?.regions ?: emptyList()
    } catch (_: WrappedRunDriftException) {
        driftedRegions(text)
    }

    override fun unmanaged(text: String): UnmanagedContent {
        if (mode == TargetOwnershipMode.OWNED) return UnmanagedContent("", 0)
        val normalized = text.replace("\r\n", "\n")
        val run = try {
            parsed(normalized)
        } catch (_: WrappedRunDriftException) {
            null
        }
        if (run == null) {
            val header = headerOf(normalized)
                ?: return UnmanagedContent(normalized.trimBoundary(), normalized.trimBoundary().lineCount())
            val range = runRange(normalized, header)
            return unmanagedOutside(normalized, range?.first ?: headerStart(normalized), range?.second)
        }
        return unmanagedOutside(normalized, run.start, run.end)
    }

    /**
     * Hand-written text and the wrapped run in the order the file holds them. A partial file may
     * carry text on either side of the run, so the preview must not assume the run comes last.
     * A drifted run still yields its blocks — the manifest is what the status model already trusts.
     */
    override fun segments(text: String, library: (String) -> dev.ruleblend.core.model.Block?): List<FileSegment> {
        val normalized = text.replace("\r\n", "\n")
        val header = headerOf(normalized)
        val range = header?.let { runRange(normalized, it) }
            ?: return listOf(FileSegment.Hand(normalized.trimBoundary())).filter { it.text.isNotEmpty() }
        val (start, endExclusive) = range
        val before = normalized.substring(0, noticeStartBefore(normalized, start) ?: start).trimBoundary()
        val after = normalized.substring(endExclusive).trimBoundary()
        return buildList {
            if (before.isNotEmpty()) add(FileSegment.Hand(before))
            add(FileSegment.Run(previewRegions(normalized, library)))
            if (after.isNotEmpty()) add(FileSegment.Hand(after))
        }
    }

    /**
     * Bodies for the preview. An edit made outside Ruleblend is exactly when reading a block matters,
     * so a drifted run is read out rather than left blank: with one block in the manifest the whole
     * run body is that block's text, and with several the untouched library bodies act as anchors
     * around the edited one — the attribution [localChange] already trusts. Recovered bodies keep
     * the empty hash of a drifted region, so nothing downstream mistakes them for an intact run.
     */
    private fun previewRegions(text: String, library: (String) -> dev.ruleblend.core.model.Block?): List<ManagedRegion> = try {
        parsed(text)?.regions ?: emptyList()
    } catch (_: WrappedRunDriftException) {
        recoveredRegions(text, library)
    }

    /**
     * A drifted run's blocks with whatever text can be attributed to them. Bodies stay empty when the
     * run holds several blocks and the anchors do not hold — a missing library body, two blocks edited
     * at once, or a split that more than one block could explain. Guessing there would show one rule's
     * text under another rule's name, which is worse than showing none.
     */
    private fun recoveredRegions(text: String, library: (String) -> dev.ruleblend.core.model.Block?): List<ManagedRegion> {
        val regions = driftedRegions(text)
        val header = headerOf(text) ?: return regions
        val body = runCatching { rawBody(text, header) }.getOrNull() ?: return regions
        if (regions.size == 1) return listOf(regions.single().copy(content = body))
        val anchors = regions.map { region -> library(region.id)?.let(::regionFor)?.content ?: return regions }
        val edited = regions.indices.mapNotNull { index ->
            val prefix = anchors.take(index).joinToString("\n\n").let { if (it.isEmpty()) "" else it + "\n\n" }
            val suffix = anchors.drop(index + 1).joinToString("\n\n").let { if (it.isEmpty()) "" else "\n\n" + it }
            if (prefix.length + suffix.length > body.length) return@mapNotNull null
            if (!body.startsWith(prefix) || !body.endsWith(suffix)) return@mapNotNull null
            index to body.substring(prefix.length, body.length - suffix.length)
        }.singleOrNull() ?: return regions
        return regions.mapIndexed { index, region ->
            region.copy(content = if (index == edited.first) edited.second else anchors[index])
        }
    }

    private fun unmanagedOutside(text: String, start: Int, end: Int?): UnmanagedContent {
        val before = text.substring(0, noticeStartBefore(text, start) ?: start).trimBoundary()
        val after = end?.let(text::substring).orEmpty().trimBoundary()
        val unmanaged = listOf(before, after).filter(String::isNotEmpty).joinToString("\n\n")
        return UnmanagedContent(unmanaged, unmanaged.lineCount(), before.contentLines())
    }

    private fun headerStart(text: String): Int =
        WRAPPED_HEADER_LINE.find(text)?.range?.first ?: text.length

    override fun upsert(text: String, region: ManagedRegion, force: Boolean): String {
        val current = parsed(text)
        if (current != null && !force) current.requireIntact()
        // An update stays where it was: the run's order is the user's (see reorder), not install time.
        val existing = current?.regions.orEmpty()
        val regions = if (existing.any { it.id == region.id }) existing.map { if (it.id == region.id) region else it }
        else existing + region
        return if (current == null) append(text, render(regions))
        else replaceRun(text, current.start, current.end, render(regions, current.header.notice), current.header.notice)
    }

    override fun remove(text: String, id: String, force: Boolean): String {
        val current = parsed(text) ?: return text
        if (!force) current.requireIntact()
        val regions = current.regions.filter { it.id != id }
        if (regions.size == current.regions.size) return text
        if (regions.isNotEmpty()) {
            return replaceRun(text, current.start, current.end, render(regions, current.header.notice), current.header.notice)
        }
        return if (mode == TargetOwnershipMode.PARTIAL) {
            text.substring(0, noticeStartBefore(text, current.start) ?: current.start) + text.substring(current.end)
        } else ""
    }

    override fun adoptSections(text: String, plan: List<ManagedDocument.AdoptStep>, force: Boolean): String {
        if (plan.isEmpty()) return text
        if (mode == TargetOwnershipMode.OWNED && plan.any { it is ManagedDocument.AdoptStep.Keep }) {
            throw IllegalArgumentException("An owned target cannot retain hand-written sections")
        }
        if (plan.none { it is ManagedDocument.AdoptStep.Replace }) return text
        val normalized = text.replace("\r\n", "\n")
        val current = parsed(normalized)
        if (current != null && !force) current.requireIntact()
        val showNotice = current?.header?.notice ?: notice
        fun region(index: Int) = (plan[index] as ManagedDocument.AdoptStep.Replace).region
        if (mode == TargetOwnershipMode.OWNED) {
            return render(current?.regions.orEmpty() + plan.indices.filter { plan[it] is ManagedDocument.AdoptStep.Replace }.map(::region), showNotice)
        }
        // The run stays one stretch of the file: sections replaced above it join its head, those below
        // its tail, and a kept section that would end up on the wrong side refuses the whole plan.
        val beforeRun = current?.let { normalized.substring(0, noticeStartBefore(normalized, it.start) ?: it.start).contentLines() }
        val texts = plan.map { it.keptBody }
        // The file is rebuilt from the plan's text, so a plan read before the file last changed would
        // drop or repeat lines: it must still cover exactly the text outside the run.
        if (texts.joinToString("\n").textLines() != unmanaged(normalized).text.textLines()) {
            throw IllegalStateException("The file changed since its sections were read")
        }
        val layout = requireNotNull(AdoptPlacement.layout(texts, plan.map { it is ManagedDocument.AdoptStep.Replace }, beforeRun))
        if (layout.blockers.isNotEmpty()) throw AdoptOrderException(layout.blockers)
        val regions = layout.ahead.map(::region) + current?.regions.orEmpty() + layout.behind.map(::region)
        val wrapped = render(regions, showNotice).trimEnd('\n')
        val run = listOfNotNull(PARTIAL_NOTICE.takeIf { current?.header?.notice == true }, wrapped).joinToString("\n")
        fun kept(indices: List<Int>) = indices.joinToString("\n\n") { texts[it] }.trimBoundary()
        return listOf(kept(layout.before), run, kept(layout.after)).filter(String::isNotEmpty).joinToString("\n\n") + "\n"
    }

    /**
     * Re-renders the run with its regions in [order]. The bodies are the ones the file already holds,
     * so only the manifest sequence and the body order change; the notice and the text around the run
     * are kept as they are. A drifted run is refused: its byte lengths no longer say where one block
     * ends, and a reorder that guessed would corrupt what it moved.
     */
    override fun reorder(text: String, order: List<String>): String {
        val current = parsed(text) ?: return text
        current.requireIntact()
        val regions = current.regions.inOrderOf(order)
        if (regions.map { it.id } == current.regions.map { it.id }) return text
        return replaceRun(text, current.start, current.end, render(regions, current.header.notice), current.header.notice)
    }

    private fun append(text: String, wrapped: String): String = when {
        mode == TargetOwnershipMode.OWNED -> {
            if (text.isNotBlank()) throw IllegalArgumentException("Cannot create an owned target with hand-written content")
            wrapped
        }
        else -> {
            val separator = when {
                text.isEmpty() || text.endsWith("\n\n") || text.endsWith("\r\n\r\n") -> ""
                text.endsWith('\n') -> "\n"
                else -> "\n\n"
            }
            text + separator + listOfNotNull(PARTIAL_NOTICE.takeIf { notice }, wrapped).joinToString("\n")
        }
    }

    private fun render(regions: List<ManagedRegion>, showNotice: Boolean = notice): String {
        val body = regions.joinToString("\n\n") { it.content }
        val header = WrappedRunHeader(
            mode = mode,
            hash = hashRun(body),
            blocks = regions.map { WrappedRunBlock(it.id, it.version, it.content.encodeToByteArray().size, it.group, it.origin) },
            notice = showNotice,
        )
        return if (mode == TargetOwnershipMode.PARTIAL) "${renderWrappedRunHeader(header)}\n$body\n<!-- rb:end -->\n"
        else "${renderWrappedRunHeader(header)}\n$body\n"
    }

    private fun parsed(text: String): Parsed? {
        val source = NormalizedText(text)
        val normalized = source.text
        val headerMatch = WRAPPED_HEADER_LINE.find(normalized) ?: return null
        val parsedHeader = parseWrappedRunHeader(headerMatch.value) ?: return null
        val header = if (mode == TargetOwnershipMode.PARTIAL) {
            parsedHeader.copy(notice = noticeStartBefore(normalized, headerMatch.range.first) != null)
        } else parsedHeader
        if (header.mode != mode || header.blocks.any { it.length == null }) return null
        val bodyStart = headerMatch.range.last + 2
        val end = if (mode == TargetOwnershipMode.PARTIAL) {
            val terminator = findWrappedTerminator(normalized, header.format, bodyStart)
                ?: throw WrappedRunDriftException("${header.format.marker} run: missing ${header.format.terminator}")
            terminator.range.first
        } else normalized.length
        val body = normalized.substring(bodyStart, end).removeSuffix("\n")
        val bytes = body.encodeToByteArray()
        var offset = 0
        val regions = header.blocks.mapIndexed { index, block ->
            val length = requireNotNull(block.length)
            if (offset + length > bytes.size) throw WrappedRunDriftException("wrapped run body no longer matches its manifest")
            val content = bytes.copyOfRange(offset, offset + length).decodeToString()
            offset += length
            if (index < header.blocks.lastIndex) {
                if (offset + 2 > bytes.size || bytes.copyOfRange(offset, offset + 2).decodeToString() != "\n\n") {
                    throw WrappedRunDriftException("wrapped run body no longer matches its manifest")
                }
                offset += 2
            }
            ManagedRegion(block.id, block.version, block.group, hashContent(content), content, block.origin)
        }
        if (offset != bytes.size) throw WrappedRunDriftException("wrapped run body no longer matches its manifest")
        val runEnd = if (mode == TargetOwnershipMode.PARTIAL) {
            requireNotNull(findWrappedTerminator(normalized, header.format, bodyStart)).range.last + 1
        } else normalized.length
        return Parsed(
            header, body, regions,
            start = source.sourceOffset(headerMatch.range.first),
            end = source.sourceOffset(runEnd),
            bodyStart = source.sourceOffset(bodyStart),
            bodyEnd = source.sourceOffset(end),
        )
    }

    private data class Parsed(
        val header: WrappedRunHeader,
        val body: String,
        val regions: List<ManagedRegion>,
        val start: Int,
        val end: Int,
        val bodyStart: Int,
        val bodyEnd: Int,
    ) {
        fun requireIntact() {
            if (header.hash != hashRun(body)) throw WrappedRunDriftException("wrapped run was edited outside Ruleblend")
        }
    }

    /**
     * The manifest of the wrapped run in [text], if any, without parsing block bodies. Safe on a drifted
     * run whose byte lengths no longer match the body: it yields stable ids/versions/groups so a
     * force-overwrite can rebuild the run from the library instead of trusting the edited bodies.
     * Returns null when there is no supported wrapped header for this [mode].
     */
    fun headerOf(text: String): WrappedRunHeader? {
        val normalized = text.replace("\r\n", "\n")
        val headerMatch = WRAPPED_HEADER_LINE.find(normalized) ?: return null
        return parseWrappedRunHeader(headerMatch.value)?.takeIf { it.mode == mode }
    }

    /**
     * Replaces the whole wrapped run with a fresh rb1 render of [regions], ignoring any drift in the
     * existing bodies. The caller supplies every region the run should hold afterwards (typically
     * the library bodies of the manifest's blocks, with the target block swapped in). Hand-written
     * text outside the run is preserved. Works on a drifted run whose byte lengths no longer match
     * the body — it reads only the header and run position, never the bodies. Throws when there is
     * no wrapped run to replace.
     */
    fun forceReplace(text: String, regions: List<ManagedRegion>): String {
        val header = headerOf(text) ?: throw WrappedRunDriftException("No wrapped-run header to rebuild from")
        val (start, endExclusive) = runRange(text, header) ?: throw WrappedRunDriftException("No wrapped run to force-replace")
        return forceReplaceInRange(text, regions, header, start, endExclusive)
    }

    /**
     * Recovers the edited body of [blockId] without trusting a drifted run as a whole. When the
     * manifest still parses, every neighbouring body must still match the library. When a
     * length-changing edit broke the manifest offsets, the unchanged library bodies on both sides
     * act as anchors. Refusing an ambiguous run is safer than blessing or overwriting another rule's
     * external edit.
     */
    override fun localChange(
        text: String,
        blockId: String,
        library: (String) -> dev.ruleblend.core.model.Block?,
    ): ManagedRegion {
        val header = headerOf(text) ?: throw WrappedRunDriftException("No wrapped-run header to read local changes from")
        val targetIndex = header.blocks.indexOfFirst { it.id == blockId }
        require(targetIndex >= 0) { "Rule $blockId is not installed in this file" }

        val intactOffsets = runCatching { parsed(text) }.getOrNull()
        if (intactOffsets != null) {
            requireLibraryNeighbours(intactOffsets.regions, blockId, library)
            return intactOffsets.regions.first { it.id == blockId }
        }

        val body = rawBody(text, header)
        val before = header.blocks.take(targetIndex).map { block ->
            library(block.id)?.let(::regionFor)?.content
                ?: throw WrappedRunDriftException("Cannot recover $blockId: ${block.id} is missing from the library")
        }
        val after = header.blocks.drop(targetIndex + 1).map { block ->
            library(block.id)?.let(::regionFor)?.content
                ?: throw WrappedRunDriftException("Cannot recover $blockId: ${block.id} is missing from the library")
        }
        val prefix = before.joinToString("\n\n").let { if (it.isEmpty()) "" else "$it\n\n" }
        val suffix = after.joinToString("\n\n").let { if (it.isEmpty()) "" else "\n\n$it" }
        if (!body.startsWith(prefix) || !body.endsWith(suffix) || prefix.length + suffix.length > body.length) {
            throw WrappedRunDriftException(
                "Cannot isolate $blockId: another rule in the same managed run was also changed",
            )
        }
        val descriptor = header.blocks[targetIndex]
        val content = body.substring(prefix.length, body.length - suffix.length)
        return ManagedRegion(descriptor.id, descriptor.version, descriptor.group, hashContent(content), content, descriptor.origin)
    }

    private fun requireLibraryNeighbours(
        regions: List<ManagedRegion>,
        blockId: String,
        blockResolver: (String) -> dev.ruleblend.core.model.Block?,
    ) {
        val changedNeighbour = regions.firstOrNull { region ->
            region.id != blockId && blockResolver(region.id)?.let(::regionFor)?.content != region.content
        }
        if (changedNeighbour != null) {
            throw WrappedRunDriftException(
                "Cannot isolate $blockId: ${changedNeighbour.id} in the same managed run was also changed",
            )
        }
    }

    private fun rawBody(text: String, header: WrappedRunHeader): String {
        val normalized = text.replace("\r\n", "\n")
        val headerMatch = WRAPPED_HEADER_LINE.find(normalized)
            ?: throw WrappedRunDriftException("No wrapped-run header to read local changes from")
        val bodyStart = headerMatch.range.last + 2
        val bodyEnd = if (header.mode == TargetOwnershipMode.PARTIAL) {
            findWrappedTerminator(normalized, header.format, bodyStart)?.range?.first
                ?: throw WrappedRunDriftException("${header.format.marker} run: missing ${header.format.terminator}")
        } else {
            normalized.length
        }
        return normalized.substring(bodyStart, bodyEnd).removeSuffix("\n")
    }

    /** [runEndExclusive] is the index just past the run (its terminator's trailing newline included). */
    private fun forceReplaceInRange(
        text: String,
        regions: List<ManagedRegion>,
        header: WrappedRunHeader,
        runStart: Int,
        runEndExclusive: Int,
    ): String {
        val rendered = render(regions, header.notice)
        return when {
            regions.isEmpty() && mode == TargetOwnershipMode.PARTIAL -> {
                text.substring(0, noticeStartBefore(text, runStart) ?: runStart) + text.substring(runEndExclusive)
            }
            regions.isEmpty() -> text.substring(0, runStart) + text.substring(runEndExclusive)
            else -> replaceRun(text, runStart, runEndExclusive, rendered, header.notice)
        }
    }

    /** Start and past-the-end index of the wrapped run carrying [header], or null when its terminator is missing. */
    private fun runRange(text: String, header: WrappedRunHeader): Pair<Int, Int>? {
        val source = NormalizedText(text)
        val normalized = source.text
        val headerMatch = WRAPPED_HEADER_LINE.find(normalized) ?: return null
        val start = headerMatch.range.first
        val endExclusive = if (mode == TargetOwnershipMode.PARTIAL) {
            val terminator = findWrappedTerminator(normalized, header.format, headerMatch.range.last + 2)
                ?: return null
            terminator.range.last + 1
        } else normalized.length
        return source.sourceOffset(start) to source.sourceOffset(endExclusive)
    }

    /**
     * Keeps a drifted run visible to status checks. The manifest still gives us stable ids and
     * versions, but no per-block bodies can be trusted after a byte-length mismatch, so every
     * returned region is deliberately marked modified.
     */
    private fun driftedRegions(text: String): List<ManagedRegion> {
        val normalized = text.replace("\r\n", "\n")
        val headerMatch = WRAPPED_HEADER_LINE.find(normalized) ?: return emptyList()
        val header = parseWrappedRunHeader(headerMatch.value)?.takeIf { it.mode == mode } ?: return emptyList()
        return header.blocks.map { block ->
            ManagedRegion(block.id, block.version, block.group, hash = "", content = "", origin = block.origin)
        }
    }

    /** Converts a legacy marker document without changing its hand-written text. */
    fun migrateLegacy(text: String): String {
        require(sniffOwnershipMode(text) == TargetOwnershipMode.LEGACY) { "Only legacy marker files can be migrated" }
        val regions = LegacyMarkers.installed(text)
        val unmanaged = ManagedDocument.withoutManagedRegions(text)
        require(mode != TargetOwnershipMode.OWNED || unmanaged.isBlank()) {
            "Cannot migrate hand-written content into an owned target"
        }
        val wrapped = render(regions)
        return if (mode == TargetOwnershipMode.OWNED) wrapped else append(unmanaged, wrapped)
    }

    /** Removes only Ruleblend's wrapper, preserving the rendered content verbatim. */
    fun disown(text: String): String {
        val current = parsed(text) ?: return text
        current.requireIntact()
        return text.substring(0, noticeStartBefore(text, current.start) ?: current.start) +
            text.substring(current.bodyStart, current.bodyEnd) + text.substring(current.end)
    }

    /** Changes the warning without changing the rendered bodies. */
    fun withNotice(text: String, enabled: Boolean): String {
        val current = parsed(text) ?: return text
        current.requireIntact()
        return replaceRun(text, current.start, current.end, render(current.regions, enabled), enabled)
    }

    /** True when the manifest still matches the rendered body. */
    fun hasDrift(text: String): Boolean = runCatching { parsed(text)?.requireIntact() }.isFailure

    /** Re-encodes this wrapped target with legacy markers; used as a migration rollback. */
    fun toLegacy(text: String): String {
        val current = parsed(text) ?: return text
        current.requireIntact()
        val legacy = current.regions.fold("") { result, region -> LegacyMarkers.upsert(result, region) }
        return text.replaceRange(noticeStartBefore(text, current.start) ?: current.start, current.end, legacy)
    }

    private fun replaceRun(text: String, start: Int, end: Int, rendered: String, showNotice: Boolean): String {
        if (mode == TargetOwnershipMode.OWNED) return text.replaceRange(start, end, rendered)
        val replacementStart = noticeStartBefore(text, start) ?: start
        val replacement = listOfNotNull(PARTIAL_NOTICE.takeIf { showNotice }, rendered).joinToString("\n")
        return text.replaceRange(replacementStart, end, replacement)
    }

    /** Changes only this intact run and its notice, keeping all hand-written bytes outside it. */
    fun withLineEnding(text: String, ending: LineEnding): String {
        val current = parsed(text) ?: return text
        current.requireIntact()
        val start = noticeStartBefore(text, current.start) ?: current.start
        return text.replaceRange(start, current.end, ending.apply(text.substring(start, current.end)))
    }

    private fun noticeStartBefore(text: String, start: Int): Int? {
        val prefix = text.substring(0, start)
        return listOf(PARTIAL_NOTICE, LEGACY_PARTIAL_NOTICE).firstNotNullOfOrNull { notice ->
            listOf("\r\n", "\n").firstNotNullOfOrNull { ending ->
                val line = notice + ending
                if (prefix.endsWith(line)) start - line.length else null
            }
        }
    }
}

private fun String.trimBoundary(): String = lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
private fun String.lineCount(): Int = if (isEmpty()) 0 else lines().size
