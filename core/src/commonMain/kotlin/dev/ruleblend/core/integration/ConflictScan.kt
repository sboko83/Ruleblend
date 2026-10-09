package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block

/** How one diff line relates the library body to the body the file holds. */
enum class ConflictLineKind { CONTEXT, ADDED, REMOVED }

/** One line of a body diff. [text] carries no marker: the sign belongs to the rendering. */
data class ConflictLine(val kind: ConflictLineKind, val text: String)

/**
 * Why a modified block conflicts, in the only two ways core can tell apart.
 *
 * [HAND_EDITED] is resolvable: the edit is attributable to this block alone, so restoring the library
 * body or saving the edit as a new version are both meaningful. [AMBIGUOUS_RUN] is the refusal
 * [TargetEncoding.localChange] makes — another block of the same managed run changed too, so no
 * automatic answer can be trusted and the case needs a person.
 */
enum class ConflictKind { HAND_EDITED, AMBIGUOUS_RUN }

/** One conflicting managed block of one file, with the diff that explains it. */
data class BlockConflict(
    val blockId: String,
    /** Version recorded in the file, which is what a restore or a new version starts from. */
    val version: Int,
    val kind: ConflictKind,
    /** Library body → file body. Empty for [ConflictKind.AMBIGUOUS_RUN]: there is no isolated body. */
    val diff: List<ConflictLine> = emptyList(),
) {
    val added: Int get() = diff.count { it.kind == ConflictLineKind.ADDED }
    val removed: Int get() = diff.count { it.kind == ConflictLineKind.REMOVED }
}

/**
 * Classifies every hand-edited managed block of one file, from the text already in hand.
 *
 * Reads and decides nothing else: the set of conflicts is exactly the regions [statusOf] calls
 * [InstallStatus.MODIFIED], and the split between the two kinds is core's own attribution rule, not a
 * second opinion about it — a block is [ConflictKind.HAND_EDITED] precisely when
 * [TargetEncoding.localChange] would hand its edited body over, and ambiguous when it refuses.
 *
 * [library] must answer from memory: at fleet scale this runs per file, and a resolver that reads
 * disk per block would turn one scan into thousands of reads.
 */
fun classifyConflicts(text: String, library: (String) -> Block?): List<BlockConflict> {
    val encoding = targetEncoding(text)
    val candidates = encoding.installed(text).mapNotNull { region ->
        val block = library(region.id) ?: return@mapNotNull null
        if (statusOf(region, block) != InstallStatus.MODIFIED) null else region to block
    }
    val edits = candidates.mapNotNull { (region, block) ->
        val expected = regionFor(block, region.group).content
        val local = try {
            encoding.localChange(text, region.id, library)
        } catch (_: WrappedRunDriftException) {
            return@mapNotNull null
        }
        if (local.content == expected) null else BlockConflict(
            blockId = region.id,
            version = region.version,
            kind = ConflictKind.HAND_EDITED,
            diff = diffLines(expected, local.content),
        )
    }
    // One isolated edit proves the rest of the run still matches the library — that is what isolation
    // checks — so an attributable edit and an ambiguous run never coexist in the same file. When
    // nothing could be isolated, every candidate is ambiguous: an edit that broke the manifest
    // offsets leaves its untouched neighbours unreadable too, and guessing which is which is exactly
    // what core refuses to do.
    if (edits.isNotEmpty()) return edits
    return candidates.map { (region, _) -> BlockConflict(region.id, region.version, ConflictKind.AMBIGUOUS_RUN) }
}

/** Above this many lines on either side the diff stops being read line by line anyway. */
private const val DIFF_LINE_LIMIT = 400

/**
 * Line diff of [from] against [to], longest common subsequence, unabridged: block bodies are short
 * enough that a reader wants the whole thing rather than a window around each change. Bodies past
 * [DIFF_LINE_LIMIT] lines are reported as a wholesale replacement instead of paying the quadratic cost.
 */
fun diffLines(from: String, to: String): List<ConflictLine> {
    val old = from.lines()
    val new = to.lines()
    if (old == new) return old.map { ConflictLine(ConflictLineKind.CONTEXT, it) }
    if (old.size > DIFF_LINE_LIMIT || new.size > DIFF_LINE_LIMIT) {
        return old.map { ConflictLine(ConflictLineKind.REMOVED, it) } +
            new.map { ConflictLine(ConflictLineKind.ADDED, it) }
    }
    // lcs[i][j] — length of the longest common subsequence of old.drop(i) and new.drop(j).
    val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) {
        for (j in new.indices.reversed()) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    val lines = mutableListOf<ConflictLine>()
    var i = 0
    var j = 0
    while (i < old.size && j < new.size) {
        when {
            old[i] == new[j] -> {
                lines += ConflictLine(ConflictLineKind.CONTEXT, old[i])
                i++
                j++
            }
            lcs[i + 1][j] >= lcs[i][j + 1] -> lines += ConflictLine(ConflictLineKind.REMOVED, old[i++])
            else -> lines += ConflictLine(ConflictLineKind.ADDED, new[j++])
        }
    }
    while (i < old.size) lines += ConflictLine(ConflictLineKind.REMOVED, old[i++])
    while (j < new.size) lines += ConflictLine(ConflictLineKind.ADDED, new[j++])
    return lines
}
