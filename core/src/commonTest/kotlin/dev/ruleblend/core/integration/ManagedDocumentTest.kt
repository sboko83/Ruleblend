package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManagedDocumentTest {

    private fun region(
        id: String = "git-no-commit",
        version: Int = 1,
        group: String? = null,
        content: String = "Never commit without asking.",
    ) = ManagedRegion(id, version, group, hashContent(content), content)

    private fun block(content: String) = Block(id = "rules", name = "Rules", content = content)

    @Test
    fun parsesRegionWrittenByUpsert() {
        val text = ManagedDocument.upsert("", region(version = 3, group = "ios-projects"))
        val parsed = ManagedDocument.parse(text)

        assertEquals(1, parsed.size)
        assertEquals("git-no-commit", parsed[0].id)
        assertEquals(3, parsed[0].version)
        assertEquals("ios-projects", parsed[0].group)
        assertEquals("Never commit without asking.", parsed[0].content)
        assertEquals(hashContent("Never commit without asking."), parsed[0].hash)
    }

    @Test
    fun keepsForeignContentAroundRegions() {
        val original =
            """
            # Project rules

            Hand-written intro.

            <!-- ruleblend:begin id=a v=1 hash=${hashContent("old")} -->
            old
            <!-- ruleblend:end id=a -->

            Hand-written outro.
            """.trimIndent()

        val updated = ManagedDocument.upsert(original, region(id = "a", version = 2, content = "new"))

        assertTrue(updated.contains("# Project rules"))
        assertTrue(updated.contains("Hand-written intro."))
        assertTrue(updated.contains("Hand-written outro."))
        assertEquals("new", ManagedDocument.parse(updated).single().content)
        assertEquals(2, ManagedDocument.parse(updated).single().version)
    }

    @Test
    fun appendsNewRegionAfterExistingContent() {
        val updated = ManagedDocument.upsert("Intro.\n", region(id = "b"))

        assertTrue(updated.startsWith("Intro.\n"))
        assertEquals(listOf("b"), ManagedDocument.parse(updated).map { it.id })
    }

    @Test
    fun upsertLeavesOtherRegionsUntouched() {
        var text = ManagedDocument.upsert("", region(id = "a", content = "a body"))
        text = ManagedDocument.upsert(text, region(id = "b", content = "b body"))
        text = ManagedDocument.upsert(text, region(id = "a", version = 2, content = "a body v2"))

        val parsed = ManagedDocument.parse(text)
        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals("a body v2", parsed.first { it.id == "a" }.content)
        assertEquals("b body", parsed.first { it.id == "b" }.content)
    }

    @Test
    fun removeDropsOnlyItsOwnRegion() {
        var text = ManagedDocument.upsert("Intro.\n", region(id = "a", content = "a body"))
        text = ManagedDocument.upsert(text, region(id = "b", content = "b body"))

        val removed = ManagedDocument.remove(text, "a")

        assertTrue(removed.startsWith("Intro.\n"))
        assertEquals(listOf("b"), ManagedDocument.parse(removed).map { it.id })
    }

    @Test
    fun removeOfAbsentIdIsNoOp() {
        val text = ManagedDocument.upsert("", region(id = "a"))
        assertEquals(text, ManagedDocument.remove(text, "missing"))
    }

    @Test
    fun detectsManuallyEditedContentByHash() {
        val text = ManagedDocument.upsert("", region(content = "original"))
        val edited = text.replace("original", "edited by hand")

        val parsed = ManagedDocument.parse(edited).single()
        assertTrue(parsed.hash != hashContent(parsed.content))
    }

    @Test
    fun parsesDocumentWithoutMarkers() {
        assertEquals(emptyList(), ManagedDocument.parse("# Just notes\n"))
    }

    @Test
    fun toleratesCrlfLineEndings() {
        val text = ManagedDocument.upsert("", region(content = "line one\nline two")).replace("\n", "\r\n")
        assertEquals("line one\nline two", ManagedDocument.parse(text).single().content)
    }

    @Test
    fun rejectsUnterminatedRegion() {
        val text = "<!-- ruleblend:begin id=a v=1 hash=deadbeef -->\nbody\n"
        assertFailsWith<IllegalArgumentException> { ManagedDocument.parse(text) }
    }

    @Test
    fun unmanagedJoinsFragmentsAroundRegions() {
        val text =
            """
            # Project rules

            Hand-written intro.

            <!-- ruleblend:begin id=a v=1 hash=${hashContent("managed")} -->
            managed
            <!-- ruleblend:end id=a -->

            Hand-written outro.
            """.trimIndent()

        val unmanaged = ManagedDocument.unmanaged(text)

        assertEquals("# Project rules\n\nHand-written intro.\n\nHand-written outro.", unmanaged.text)
        assertEquals(4, unmanaged.lineCount)
    }

    @Test
    fun unmanagedIgnoresWhitespaceBetweenRegions() {
        var text = ManagedDocument.upsert("", region(id = "a", content = "a body"))
        text = ManagedDocument.upsert(text, region(id = "b", content = "b body"))

        assertTrue(ManagedDocument.unmanaged(text).isEmpty)
    }

    @Test
    fun importDirectivesTravelAsText() {
        val unmanaged = ManagedDocument.unmanaged("@AGENTS.md\n")

        assertEquals("@AGENTS.md", unmanaged.text)
        assertEquals(1, unmanaged.lineCount)
    }

    @Test
    fun adoptReplacesUnmanagedTextAtItsFirstPosition() {
        val original =
            """
            Hand-written intro.

            <!-- ruleblend:begin id=a v=1 hash=${hashContent("managed")} -->
            managed
            <!-- ruleblend:end id=a -->

            Hand-written outro.
            """.trimIndent()
        val adopted = ManagedDocument.unmanaged(original).text

        val updated = ManagedDocument.adopt(original, region(id = "project-rules", content = adopted))

        assertEquals(listOf("project-rules", "a"), ManagedDocument.parse(updated).map { it.id })
        assertEquals(adopted, ManagedDocument.parse(updated).first().content)
        assertTrue(ManagedDocument.unmanaged(updated).isEmpty)
        assertEquals("managed", ManagedDocument.parse(updated).first { it.id == "a" }.content)
    }

    @Test
    fun adoptKeepsEveryUnmanagedLineInTheBlock() {
        val original = "# Rules\n\nAlways ask first.\n"

        val body = ManagedDocument.unmanaged(original).text
        val updated = ManagedDocument.adopt(original, region(id = "rules", content = body))

        assertEquals(original.trimEnd('\n'), ManagedDocument.parse(updated).single().content)
        assertEquals(InstallStatus.SYNCED, statusOf(ManagedDocument.parse(updated).single(), block(body)))
    }

    @Test
    fun adoptOfFileWithoutUnmanagedContentJustUpserts() {
        val text = ManagedDocument.upsert("", region(id = "a", content = "a body"))

        val updated = ManagedDocument.adopt(text, region(id = "b", content = "b body"))

        assertEquals(listOf("a", "b"), ManagedDocument.parse(updated).map { it.id })
    }

    @Test
    fun adoptOfTheIdAlreadyManagedUpdatesInPlace() {
        val text = ManagedDocument.upsert("", region(id = "a", version = 1, content = "old"))

        val updated = ManagedDocument.adopt(text, region(id = "a", version = 2, content = "new"))

        val regions = ManagedDocument.parse(updated)
        assertEquals(listOf("a"), regions.map { it.id })
        assertEquals("new", regions.single().content)
        assertEquals(2, regions.single().version)
    }

    @Test
    fun adoptSectionsReplacesAllChosenBodiesAndKeepsSkippedOnes() {
        val original =
            """
            # First
            first body

            # Second
            second body

            # Third
            third body
            """.trimIndent()

        val updated = ManagedDocument.adoptSections(
            original,
            listOf(
                ManagedDocument.AdoptStep.Replace("# First\nfirst body", region(id = "first", content = "first body")),
                ManagedDocument.AdoptStep.Keep("# Second\nsecond body"),
                ManagedDocument.AdoptStep.Replace("# Third\nthird body", region(id = "third", content = "third body")),
            ),
        )

        assertEquals(listOf("first", "third"), ManagedDocument.parse(updated).map { it.id })
        val leftover = ManagedDocument.unmanaged(updated).text
        assertTrue("second body" in leftover, "skipped section must stay: $leftover")
        assertTrue(!("first body" in leftover && "third body" in leftover),
            "adopted bodies must be gone: $leftover")
    }

    @Test
    fun adoptSectionsAllReplaceLeavesNoUnmanagedText() {
        val original =
            """
            # First
            first body

            # Second
            second body
            """.trimIndent()

        val updated = ManagedDocument.adoptSections(
            original,
            listOf(
                ManagedDocument.AdoptStep.Replace("# First\nfirst body", region(id = "first", content = "first body")),
                ManagedDocument.AdoptStep.Replace("# Second\nsecond body", region(id = "second", content = "second body")),
            ),
        )

        assertEquals(listOf("first", "second"), ManagedDocument.parse(updated).map { it.id })
        assertTrue(ManagedDocument.unmanaged(updated).isEmpty, "no unmanaged text should remain")
    }

    @Test
    fun adoptSectionsWithEmptyPlanReturnsTextUnchanged() {
        val original = "Some hand-written text.\n"
        assertEquals(original, ManagedDocument.adoptSections(original, emptyList()))
    }

    @Test
    fun adoptSectionsWithOnlyKeepStepsIsNoOp() {
        val original = "# Rules\nhand-written\n"
        val plan = listOf<ManagedDocument.AdoptStep>(ManagedDocument.AdoptStep.Keep(original))
        assertEquals(original, ManagedDocument.adoptSections(original, plan))
    }

    @Test
    fun unmanagedLineCountMatchesWhatWouldBeAdopted() {
        // The count is of the adopted text, not of the raw lines outside the markers: the blank gap
        // between the two fragments is dropped and each fragment is trimmed.
        val text = "\n\nfirst\n\n\n<!-- ruleblend:begin id=a v=1 hash=${hashContent("m")} -->\nm\n" +
            "<!-- ruleblend:end id=a -->\n\n\nsecond\n\n"

        val unmanaged = ManagedDocument.unmanaged(text)

        assertEquals("first\n\nsecond", unmanaged.text)
        assertEquals(2, unmanaged.lineCount)
        // Not even the blank line joining the two fragments counts.
        assertEquals(3, unmanaged.text.lines().size)
    }

    @Test
    fun rejectsDuplicateMarkerId() {
        val text =
            """
            <!-- ruleblend:begin id=a v=1 hash=deadbeef -->
            first
            <!-- ruleblend:end id=a -->

            <!-- ruleblend:begin id=a v=1 hash=deadbeef -->
            second
            <!-- ruleblend:end id=a -->
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { ManagedDocument.parse(text) }
    }

    @Test
    fun groupIsNullWhenAbsent() {
        val text = ManagedDocument.upsert("", region(group = null))
        assertNull(ManagedDocument.parse(text).single().group)
    }

    @Test
    fun hashContentIsStableForAKnownInput() {
        // Pins the FNV-1a output so an accidental algorithm change is caught, not just round-trips.
        assertEquals("7da004a5", hashContent("Never commit without asking."))
    }

    // --- Run-marker format ---

    @Test
    fun upsertRendersTheExactRunMarkers() {
        val text = ManagedDocument.upsert("", region(version = 3, group = "ios-projects"))

        assertEquals(
            "<!-- kb git-no-commit v3 g:ios-projects 7da004a5 -->\n" +
                "Never commit without asking.\n" +
                "<!-- kb:end -->\n",
            text,
        )
    }

    @Test
    fun consecutiveRegionsShareOneRun() {
        var text = ManagedDocument.upsert("", region(id = "a", content = "a body"))
        text = ManagedDocument.upsert(text, region(id = "b", content = "b body"))

        assertEquals(3, text.lines().count { it.startsWith("<!-- kb") })
        assertEquals(1, text.lines().count { it == "<!-- kb:end -->" })
        assertEquals(listOf("a", "b"), ManagedDocument.parse(text).map { it.id })
    }

    @Test
    fun upsertStartsANewRunWhenTextFollowsTheLastOne() {
        val text = ManagedDocument.upsert("", region(id = "a", content = "a body")) + "\nOutro.\n"

        val updated = ManagedDocument.upsert(text, region(id = "b", content = "b body"))

        assertEquals(2, updated.lines().count { it == "<!-- kb:end -->" })
        assertEquals(listOf("a", "b"), ManagedDocument.parse(updated).map { it.id })
        assertEquals("Outro.", ManagedDocument.unmanaged(updated).text)
    }

    @Test
    fun parsesMultipleRunsAroundHandWrittenText() {
        val text =
            """
            <!-- kb a v1 ${hashContent("a body")} -->
            a body
            <!-- kb:end -->

            Hand-written.

            <!-- kb b v2 ${hashContent("b body")} -->
            b body
            <!-- kb c v1 g:ios ${hashContent("c body")} -->
            c body
            <!-- kb:end -->
            """.trimIndent()

        val parsed = ManagedDocument.parse(text)

        assertEquals(listOf("a", "b", "c"), parsed.map { it.id })
        assertEquals(listOf(1, 2, 1), parsed.map { it.version })
        assertEquals(listOf(null, null, "ios"), parsed.map { it.group })
        assertEquals("c body", parsed.last().content)
        assertEquals("Hand-written.", ManagedDocument.unmanaged(text).text)
    }

    @Test
    fun removeOfAMiddleRegionKeepsTheRunTerminator() {
        var text = ManagedDocument.upsert("", region(id = "a", content = "a body"))
        text = ManagedDocument.upsert(text, region(id = "b", content = "b body"))
        text = ManagedDocument.upsert(text, region(id = "c", content = "c body"))

        val removed = ManagedDocument.remove(text, "b")

        assertEquals(listOf("a", "c"), ManagedDocument.parse(removed).map { it.id })
        assertEquals(1, removed.lines().count { it == "<!-- kb:end -->" })
    }

    @Test
    fun removeOfTheOnlyRegionDropsTheRun() {
        val text = ManagedDocument.upsert("Intro.\n", region(id = "a"))

        assertEquals("Intro.\n", ManagedDocument.remove(text, "a"))
    }

    @Test
    fun rejectsRunWithoutTerminator() {
        val text = "<!-- kb a v1 deadbeef -->\nbody\n"
        val failure = assertFailsWith<IllegalArgumentException> { ManagedDocument.parse(text) }
        assertTrue("missing kb:end" in failure.message.orEmpty())
    }

    @Test
    fun rejectsDuplicateIdAcrossFormats() {
        val text =
            """
            <!-- ruleblend:begin id=a v=1 hash=deadbeef -->
            first
            <!-- ruleblend:end id=a -->

            <!-- kb a v1 deadbeef -->
            second
            <!-- kb:end -->
            """.trimIndent()
        assertFailsWith<IllegalArgumentException> { ManagedDocument.parse(text) }
    }

    @Test
    fun strayTerminatorIsInertText() {
        val text = "Notes.\n\n<!-- kb:end -->\n"

        assertEquals(emptyList(), ManagedDocument.parse(text))
        assertTrue("<!-- kb:end -->" in ManagedDocument.unmanaged(text).text)
    }

    @Test
    fun blankHashRegionRendersParseably() {
        // Legacy regions parsed without a hash must re-render into a line the parser accepts.
        val rendered = ManagedDocument.upsert("", ManagedRegion("a", 1, null, "", "body"))

        val parsed = ManagedDocument.parse(rendered).single()
        assertEquals("", parsed.hash)
        assertEquals("body", parsed.content)
    }

    // --- Legacy-format migration ---

    @Test
    fun writeMigratesLegacyMarkersAndPreservesInterleavedText() {
        val original =
            """
            Intro.

            <!-- ruleblend:begin id=a v=1 hash=${hashContent("a body")} -->
            a body
            <!-- ruleblend:end id=a -->

            Between.

            <!-- ruleblend:begin id=b v=1 hash=${hashContent("b body")} -->
            b body
            <!-- ruleblend:end id=b -->
            """.trimIndent()

        val updated = ManagedDocument.upsert(original, region(id = "c", content = "c body"))

        assertTrue("ruleblend:begin" !in updated)
        assertEquals(listOf("a", "b", "c"), ManagedDocument.parse(updated).map { it.id })
        assertEquals("Intro.\n\nBetween.", ManagedDocument.unmanaged(updated).text)
        // b and the appended c share the trailing run; a is fenced off by "Between.".
        assertEquals(2, updated.lines().count { it == "<!-- kb:end -->" })
    }

    @Test
    fun legacyRegionsSeparatedByBlanksMigrateIntoOneRun() {
        var legacy = ""
        legacy = legacyUpsert(legacy, "a", "a body")
        legacy += "\n" + legacyUpsert("", "b", "b body")

        val updated = ManagedDocument.remove(legacy, "b")

        assertTrue("ruleblend:begin" !in updated)
        assertEquals(listOf("a"), ManagedDocument.parse(updated).map { it.id })
    }

    @Test
    fun mixedFormatsParseInFileOrder() {
        val text =
            """
            <!-- kb a v1 ${hashContent("a body")} -->
            a body
            <!-- kb:end -->

            <!-- ruleblend:begin id=b v=2 group=ios hash=${hashContent("b body")} -->
            b body
            <!-- ruleblend:end id=b -->
            """.trimIndent()

        val parsed = ManagedDocument.parse(text)

        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals("ios", parsed.last().group)
        assertTrue(ManagedDocument.unmanaged(text).isEmpty)
    }

    @Test
    fun adoptSectionsRendersConsecutiveReplacesAsOneRun() {
        val original = "# First\nfirst body\n\n# Second\nsecond body\n"

        val updated = ManagedDocument.adoptSections(
            original,
            listOf(
                ManagedDocument.AdoptStep.Replace("# First\nfirst body", region(id = "first", content = "first body")),
                ManagedDocument.AdoptStep.Replace("# Second\nsecond body", region(id = "second", content = "second body")),
            ),
        )

        assertEquals(1, updated.lines().count { it == "<!-- kb:end -->" })
        assertEquals(listOf("first", "second"), ManagedDocument.parse(updated).map { it.id })
    }

    // --- reorder -------------------------------------------------------------

    @Test
    fun reorderMovesBlocksAndLeavesHandWrittenTextAlone() {
        val original = ManagedDocument.upsert(
            ManagedDocument.upsert("# Notes\nkeep me\n", region(id = "first", content = "first body")),
            region(id = "second", content = "second body"),
        )

        val updated = ManagedDocument.reorder(original, listOf("second", "first"))

        assertEquals(listOf("second", "first"), ManagedDocument.parse(updated).map { it.id })
        assertEquals(
            listOf("first body", "second body"),
            ManagedDocument.parse(updated).map { it.content }.sorted(),
        )
        assertEquals("# Notes\nkeep me", ManagedDocument.unmanaged(updated).text)
    }

    @Test
    fun reorderIsIdempotentAndAnUnchangedOrderIsNotRewritten() {
        val original = ManagedDocument.upsert(
            ManagedDocument.upsert("", region(id = "first", content = "first body")),
            region(id = "second", content = "second body"),
        )
        val order = listOf("second", "first")

        val once = ManagedDocument.reorder(original, order)
        val twice = ManagedDocument.reorder(once, order)

        assertEquals(once, twice)
        assertEquals(original, ManagedDocument.reorder(original, listOf("first", "second")))
    }

    @Test
    fun reorderKeepsAnIdMissingFromTheOrderAndDoesNotCrossRuns() {
        val original = ManagedDocument.upsert(
            "${ManagedDocument.upsert("", region(id = "first", content = "first body"))}\nprose\n",
            region(id = "second", content = "second body"),
        )
        val withThird = ManagedDocument.upsert(original, region(id = "third", content = "third body"))

        // "third" landed in the second run; asking for it first cannot pull it across the prose.
        val updated = ManagedDocument.reorder(withThird, listOf("third"))

        assertEquals(listOf("first", "third", "second"), ManagedDocument.parse(updated).map { it.id })
        assertEquals("prose", ManagedDocument.unmanaged(updated).text)
    }

    private fun legacyUpsert(text: String, id: String, content: String): String {
        val marker = "<!-- ruleblend:begin id=$id v=1 hash=${hashContent(content)} -->\n$content\n<!-- ruleblend:end id=$id -->\n"
        return if (text.isBlank()) marker else text.trimEnd('\n') + "\n\n" + marker
    }
}
