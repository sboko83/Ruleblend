package dev.ruleblend.app.resolve

import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.core.integration.ConflictKind
import dev.ruleblend.core.integration.ConflictLine
import dev.ruleblend.core.integration.ConflictLineKind
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.Block

/**
 * A class of conflict, not a single occurrence: the left column of Resolve is this list, and every
 * row of the table belongs to exactly one of them.
 *
 * [HAND_EDITED] is the resolvable class — 8B applies strategies to it. [AMBIGUOUS] is core's refusal
 * made visible: two blocks of one managed run changed, so no bulk answer exists and the row can only
 * be opened in Place. [LEGACY] is not a conflict of content at all but of format, and it needs no
 * decision: the next write rewrites the file as rb1.
 */
enum class ResolveClass { HAND_EDITED, AMBIGUOUS, LEGACY }

/**
 * One occurrence: a block of a file of a place, or — for [ResolveClass.LEGACY] — the file itself,
 * which has no block. The table shape is the same for all three classes, so the columns a class does
 * not fill stay empty rather than becoming a second table.
 */
data class ResolveRow(
    val placeId: String,
    val placeName: String,
    val file: String,
    val blockId: String = "",
    val blockName: String = "",
    val version: Int = 0,
    val diff: List<ConflictLine> = emptyList(),
) {
    val added: Int get() = diff.count { it.kind == ConflictLineKind.ADDED }
    val removed: Int get() = diff.count { it.kind == ConflictLineKind.REMOVED }

    /** A row without a block carries no diff to open — the legacy class is a list of files. */
    val hasBlock: Boolean get() = blockId.isNotEmpty()

    /**
     * What a marked row and a chosen strategy are keyed by. The file is deliberately not part of it:
     * one write settles a block in every file of its place, so two rows of the same block there are
     * one decision and cannot be answered differently.
     */
    val key: String get() = "$placeId#$blockId"
}

/** Rows of one place, kept together: a conflict is fixed per file, so the place is the unit of work. */
data class ResolveGroup(val placeId: String, val placeName: String, val rows: List<ResolveRow>)

/** One entry of the class list: how much of the fleet the class covers. */
data class ResolveClassInfo(val cls: ResolveClass, val total: Int, val places: Int)

/**
 * What Resolve shows after one scan. All three classes are always listed, empty ones included: the
 * list is the vocabulary of the screen, and a class that disappears when it reaches zero would make
 * "no ambiguous runs" indistinguishable from "ambiguity is not a thing here".
 */
data class ResolveSnapshot(
    val classes: List<ResolveClassInfo> = ResolveClass.entries.map { ResolveClassInfo(it, 0, 0) },
    private val groups: Map<ResolveClass, List<ResolveGroup>> = emptyMap(),
    /** False until the first scan finishes; an empty snapshot before that means "unknown". */
    val scanned: Boolean = false,
) {
    fun groups(cls: ResolveClass): List<ResolveGroup> = groups[cls].orEmpty()

    fun info(cls: ResolveClass): ResolveClassInfo = classes.first { it.cls == cls }

    /** Every occurrence of every class: the "nothing to resolve" state is this being zero. */
    val total: Int get() = classes.sumOf { it.total }

    companion object {
        val EMPTY = ResolveSnapshot()
    }
}

/**
 * Turns one cross-place scan into the three classes. Pure: the classification itself was done in core
 * during the scan, so this only groups what is already decided — Resolve can never disagree with the
 * status Home and Coverage show for the same block.
 *
 * [blocks] supplies display names only. A block missing from the library produces no conflict at all,
 * because a region with nothing to compare against is not modified.
 */
fun resolveSnapshot(
    scanned: List<LibraryPlaceUsage>,
    blocks: List<Block> = emptyList(),
): ResolveSnapshot {
    val names = blocks.associate { it.id to it.name }
    val groups = ResolveClass.entries.associateWith { cls ->
        scanned.mapNotNull { place ->
            val rows = place.rows(cls, names)
            if (rows.isEmpty()) null else ResolveGroup(place.id, place.name, rows)
        }
    }
    return ResolveSnapshot(
        classes = ResolveClass.entries.map { cls ->
            val places = groups[cls].orEmpty()
            ResolveClassInfo(cls, places.sumOf { it.rows.size }, places.size)
        },
        groups = groups,
        scanned = true,
    )
}

private fun LibraryPlaceUsage.rows(cls: ResolveClass, names: Map<String, String>): List<ResolveRow> =
    files.flatMap { file ->
        when (cls) {
            ResolveClass.LEGACY ->
                if (file.mode == TargetOwnershipMode.LEGACY) listOf(ResolveRow(id, name, file.name)) else emptyList()
            ResolveClass.HAND_EDITED, ResolveClass.AMBIGUOUS ->
                file.conflicts.filter { it.kind == cls.conflictKind() }.map { conflict ->
                    ResolveRow(
                        placeId = id,
                        placeName = name,
                        file = file.name,
                        blockId = conflict.blockId,
                        blockName = names[conflict.blockId] ?: conflict.blockId,
                        version = conflict.version,
                        diff = conflict.diff,
                    )
                }
        }
    }

private fun ResolveClass.conflictKind(): ConflictKind? = when (this) {
    ResolveClass.HAND_EDITED -> ConflictKind.HAND_EDITED
    ResolveClass.AMBIGUOUS -> ConflictKind.AMBIGUOUS_RUN
    ResolveClass.LEGACY -> null
}
