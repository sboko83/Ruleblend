package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-only split a file preview renders. It must report the file's own order: the preview is
 * a claim about what the agent reads, so a managed run shown above text that actually follows it
 * would be a lie about the file.
 */
class FileSegmentTest {
    private val first = ManagedRegion("first", 1, null, hashContent("first body"), "first body")
    private val second = ManagedRegion("second", 2, "team", hashContent("second body"), "second body")
    private val third = ManagedRegion("third", 1, null, hashContent("third body"), "third body")

    /** The library the anchors are read from: the bodies a drifted run's untouched blocks still hold. */
    private val library: (String) -> Block? = { id ->
        listOf(
            Block(id = "first", name = "First", version = 1, content = "first body"),
            Block(id = "second", name = "Second", version = 2, content = "second body"),
            Block(id = "third", name = "Third", version = 1, content = "third body"),
        ).find { it.id == id }
    }

    @Test fun anUnmanagedFileIsOneHandFragment() {
        val segments = targetEncoding("# Notes\n\nBuild with make.\n").segments("# Notes\n\nBuild with make.\n")

        assertEquals(listOf(FileSegment.Hand("# Notes\n\nBuild with make.")), segments)
    }

    @Test fun anEmptyFileHasNoSegments() {
        assertEquals(emptyList(), targetEncoding("").segments(""))
    }

    @Test fun legacyMarkersKeepTextAndRunsInFileOrder() {
        val text = LegacyMarkers.upsert(LegacyMarkers.upsert("# Notes\n", first), second) + "\nTrailing note.\n"

        val segments = LegacyMarkers.segments(text)

        assertEquals(
            listOf("Hand:# Notes", "Run:first,second", "Hand:Trailing note."),
            segments.map(::describe),
        )
    }

    @Test fun aPartialRunReportsTextOnBothSidesOfIt() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert("# Notes\n", first) + "\nTrailing note.\n"

        val segments = encoding.segments(text)

        assertEquals(listOf("Hand:# Notes", "Run:first", "Hand:Trailing note."), segments.map(::describe))
    }

    @Test fun theEditWarningBelongsToTheRunAndNotToTheHandWrittenText() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert("# Notes\n", first)

        val hand = encoding.segments(text).filterIsInstance<FileSegment.Hand>().single()

        assertEquals("# Notes", hand.text)
        assertTrue(PARTIAL_NOTICE in text, "the file does carry the warning")
    }

    @Test fun anOwnedFileIsOneRunWithNoHandWrittenText() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        val text = encoding.upsert("", first)

        assertEquals(listOf("Run:first"), encoding.segments(text).map(::describe))
    }

    @Test fun aDriftedRunStillListsItsBlocks() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        // A length-changing hand edit: the manifest no longer matches the body, and the preview must
        // still show which blocks the run claims — that is what the status model reports against.
        val drifted = encoding.upsert("# Notes\n", first).replace("first body", "hand-edited body!")

        val segments = encoding.segments(drifted)

        assertEquals(listOf("Hand:# Notes", "Run:first"), segments.map(::describe))
    }

    @Test fun aDriftedRunOfOneBlockPreviewsTheTextTheFileActuallyHolds() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        // An agent's global file is the one most likely to be edited by hand, and a single rule
        // owns the whole run there: the body is that rule's text, drift or no drift.
        val drifted = encoding.upsert("", first).replace("first body", "first body, edited by hand")

        val region = encoding.segments(drifted).filterIsInstance<FileSegment.Run>().single().regions.single()

        assertEquals("first body, edited by hand", region.content)
        assertEquals("", region.hash, "the recovered body is still not blessed as intact")
    }

    @Test fun aDriftedPartialRunOfOneBlockKeepsTheTextAroundIt() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = (encoding.upsert("# Notes\n", first) + "\nTrailing note.\n")
            .replace("first body", "hand-edited body!")

        val segments = encoding.segments(drifted)

        assertEquals(listOf("Hand:# Notes", "Run:first", "Hand:Trailing note."), segments.map(::describe))
        assertEquals(
            "hand-edited body!",
            segments.filterIsInstance<FileSegment.Run>().single().regions.single().content,
        )
    }

    @Test fun aDriftedRunOfSeveralBlocksShowsNoBodiesWithoutTheLibraryToAnchorThem() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert(encoding.upsert("", first), second).replace("first body", "hand-edited body!")

        val regions = encoding.segments(drifted).filterIsInstance<FileSegment.Run>().single().regions

        assertEquals(listOf("first", "second"), regions.map { it.id })
        assertEquals(listOf("", ""), regions.map { it.content })
    }

    @Test fun aDriftedRunOfSeveralBlocksRecoversTheEditedOneBetweenLibraryAnchors() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val intact = encoding.upsert(encoding.upsert(encoding.upsert("", first), second), third)
        val drifted = intact.replace("second body", "second body, edited by hand")

        val regions = encoding.segments(drifted, library).filterIsInstance<FileSegment.Run>().single().regions

        assertEquals(listOf("first body", "second body, edited by hand", "third body"), regions.map { it.content })
        assertEquals(listOf("", "", ""), regions.map { it.hash }, "recovered bodies are still not intact")
    }

    @Test fun aDriftedRunRecoversAnEditedFirstAndLastBlockToo() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val intact = encoding.upsert(encoding.upsert(encoding.upsert("", first), second), third)

        val head = encoding.segments(intact.replace("first body", "first body, longer"), library)
        val tail = encoding.segments(intact.replace("third body", "third body, longer"), library)

        assertEquals(
            listOf("first body, longer", "second body", "third body"),
            head.filterIsInstance<FileSegment.Run>().single().regions.map { it.content },
        )
        assertEquals(
            listOf("first body", "second body", "third body, longer"),
            tail.filterIsInstance<FileSegment.Run>().single().regions.map { it.content },
        )
    }

    @Test fun twoBlocksEditedAtOnceLeaveEveryBodyEmpty() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert(encoding.upsert(encoding.upsert("", first), second), third)
            .replace("first body", "first body, longer")
            .replace("third body", "third body, longer")

        val regions = encoding.segments(drifted, library).filterIsInstance<FileSegment.Run>().single().regions

        assertEquals(listOf("", "", ""), regions.map { it.content }, "an ambiguous run says nothing")
    }

    @Test fun aBlockMissingFromTheLibraryLeavesEveryBodyEmpty() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert(encoding.upsert("", first), second).replace("first body", "first body, longer")

        val regions = encoding.segments(drifted) { id -> library(id)?.takeIf { it.id != "second" } }
            .filterIsInstance<FileSegment.Run>().single().regions

        assertEquals(listOf("", ""), regions.map { it.content })
    }

    private fun describe(segment: FileSegment): String = when (segment) {
        is FileSegment.Hand -> "Hand:${segment.text}"
        is FileSegment.Run -> "Run:${segment.regions.joinToString(",") { it.id }}"
    }
}
