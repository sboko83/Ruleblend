package dev.ruleblend.app.resolve

import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.PlaceFile
import dev.ruleblend.core.integration.BlockConflict
import dev.ruleblend.core.integration.ConflictKind
import dev.ruleblend.core.integration.ConflictLine
import dev.ruleblend.core.integration.ConflictLineKind
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.Block
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resolve as a pure function of one scan: which class an occurrence lands in, how the classes are
 * counted and what a row carries. The classification itself belongs to core and is covered there;
 * nothing here touches a file.
 */
class ResolveSnapshotTest {

    @Test fun `an unscanned snapshot still lists every class, all at zero`() {
        val snapshot = ResolveSnapshot.EMPTY
        assertFalse(snapshot.scanned)
        assertEquals(ResolveClass.entries, snapshot.classes.map { it.cls })
        assertEquals(0, snapshot.total)
    }

    @Test fun `each class counts its occurrences and the places they sit in`() {
        val snapshot = resolveSnapshot(
            listOf(
                place("ledger-kmp", file(conflicts = listOf(handEdited("git-commit-style"), handEdited("api-naming")))),
                place("transit-ios", file(conflicts = listOf(handEdited("git-commit-style")))),
                place("quartz-kmp", file(conflicts = listOf(ambiguous("api-naming"), ambiguous("error-handling")))),
                place("nimbus-api", file(mode = TargetOwnershipMode.LEGACY)),
            ),
            blocks,
        )

        assertTrue(snapshot.scanned)
        assertEquals(ResolveClassInfo(ResolveClass.HAND_EDITED, total = 3, places = 2), snapshot.info(ResolveClass.HAND_EDITED))
        assertEquals(ResolveClassInfo(ResolveClass.AMBIGUOUS, total = 2, places = 1), snapshot.info(ResolveClass.AMBIGUOUS))
        assertEquals(ResolveClassInfo(ResolveClass.LEGACY, total = 1, places = 1), snapshot.info(ResolveClass.LEGACY))
        assertEquals(6, snapshot.total)
    }

    @Test fun `occurrences are grouped by place, which is where a fix is applied`() {
        val snapshot = resolveSnapshot(
            listOf(
                place("ledger-kmp", file(conflicts = listOf(handEdited("git-commit-style"), handEdited("api-naming")))),
                place("transit-ios", file(conflicts = listOf(handEdited("git-commit-style")))),
            ),
            blocks,
        )

        val groups = snapshot.groups(ResolveClass.HAND_EDITED)
        assertEquals(listOf("ledger-kmp", "transit-ios"), groups.map { it.placeName })
        assertEquals(listOf(2, 1), groups.map { it.rows.size })
        assertEquals(emptyList(), snapshot.groups(ResolveClass.AMBIGUOUS))
    }

    @Test fun `a hand-edited row carries the block name, its version and the drift counts`() {
        val snapshot = resolveSnapshot(listOf(place("ledger-kmp", file(conflicts = listOf(handEdited("git-commit-style"))))), blocks)

        val row = snapshot.groups(ResolveClass.HAND_EDITED).single().rows.single()
        assertEquals("Commit style", row.blockName)
        assertEquals("AGENTS.md", row.file)
        assertEquals(3, row.version)
        assertEquals(1, row.added)
        assertEquals(1, row.removed)
        assertTrue(row.hasBlock)
    }

    @Test fun `an ambiguous run is listed without a diff to act on`() {
        val snapshot = resolveSnapshot(listOf(place("quartz-kmp", file(conflicts = listOf(ambiguous("api-naming"))))), blocks)

        val row = snapshot.groups(ResolveClass.AMBIGUOUS).single().rows.single()
        assertEquals("api-naming", row.blockId)
        assertEquals(emptyList(), row.diff)
        assertEquals(0, snapshot.info(ResolveClass.HAND_EDITED).total)
    }

    @Test fun `a legacy row names the file and no block, because the format is the problem`() {
        val snapshot = resolveSnapshot(
            listOf(place("nimbus-api", file(name = "CLAUDE.md", mode = TargetOwnershipMode.LEGACY))),
            blocks,
        )

        val row = snapshot.groups(ResolveClass.LEGACY).single().rows.single()
        assertEquals("CLAUDE.md", row.file)
        assertFalse(row.hasBlock)
    }

    @Test fun `a block missing from the library falls back to its id`() {
        val snapshot = resolveSnapshot(listOf(place("relay-cli", file(conflicts = listOf(handEdited("shell-safety"))))), blocks)

        assertEquals("shell-safety", snapshot.groups(ResolveClass.HAND_EDITED).single().rows.single().blockName)
    }

    // ---- harness ----

    private val blocks = listOf(
        Block(id = "git-commit-style", name = "Commit style"),
        Block(id = "api-naming", name = "API naming"),
        Block(id = "error-handling", name = "Error handling"),
    )

    private fun handEdited(id: String) = BlockConflict(
        blockId = id,
        version = 3,
        kind = ConflictKind.HAND_EDITED,
        diff = listOf(
            ConflictLine(ConflictLineKind.REMOVED, "library line"),
            ConflictLine(ConflictLineKind.ADDED, "local line"),
        ),
    )

    private fun ambiguous(id: String) = BlockConflict(id, version = 2, kind = ConflictKind.AMBIGUOUS_RUN)

    private fun file(
        name: String = "AGENTS.md",
        mode: TargetOwnershipMode = TargetOwnershipMode.PARTIAL,
        conflicts: List<BlockConflict> = emptyList(),
    ) = PlaceFile(Path.of(name), name, exists = true, mode = mode, unmanagedLines = 0, drift = conflicts.isNotEmpty(), conflicts = conflicts)

    private fun place(name: String, vararg files: PlaceFile) = LibraryPlaceUsage(
        id = "project:/tmp/$name",
        name = name,
        kind = LibraryPlaceKind.PROJECT,
        installs = emptyMap(),
        files = files.toList(),
    )
}
