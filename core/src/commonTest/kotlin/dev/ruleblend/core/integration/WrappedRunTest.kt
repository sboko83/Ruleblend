package dev.ruleblend.core.integration

import dev.ruleblend.core.integration.ManagedDocument.AdoptStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WrappedRunTest {
    private val first = ManagedRegion("first", 1, null, hashContent("first body"), "first body")
    private val second = ManagedRegion("second", 2, "team", hashContent("второй"), "второй")

    @Test fun partialRoundTripsUnicodeBodiesAndPreservesOutsideText() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert("# Notes\n", first)
        val updated = encoding.upsert(text, second)
        assertEquals(listOf(first, second), encoding.installed(updated))
        assertEquals("# Notes", encoding.unmanaged(updated).text)
        assertTrue("first@1:10" in updated)
    }

    /** An empty rule is a real library object; its run must parse, not index past the file. */
    @Test fun emptyBodyRoundTripsInBothModes() {
        val empty = ManagedRegion("empty", 1, null, hashContent(""), "")
        for (mode in listOf(TargetOwnershipMode.OWNED, TargetOwnershipMode.PARTIAL)) {
            val encoding = WrappedRun(mode)
            val text = encoding.upsert("", empty)
            assertEquals(listOf(empty), encoding.installed(text), "$mode")
            assertEquals(listOf(empty, first), encoding.installed(encoding.upsert(text, first)), "$mode")
            assertFalse(encoding.hasDrift(text), "$mode")
        }
        val partial = WrappedRun(TargetOwnershipMode.PARTIAL)
        val withNotes = partial.upsert("# Notes\n", empty)
        assertEquals(listOf(empty), partial.installed(withNotes))
        assertEquals("# Notes", partial.unmanaged(withNotes).text)
    }

    @Test fun partialRefusesToOverwriteDrift() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert("", first).replace("first body", "edited body")
        assertFailsWith<WrappedRunDriftException> { encoding.upsert(text, second) }
    }

    @Test fun ownedRefusesHandWrittenCreationAndReportsNoUnmanagedText() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        assertFailsWith<IllegalArgumentException> { encoding.upsert("notes", first) }
        val text = encoding.upsert("", first)
        assertEquals(TargetOwnershipMode.OWNED, sniffOwnershipMode(text))
        assertTrue(encoding.unmanaged(text).isEmpty)
    }

    // --- force-overwrite -----------------------------------------------------

    @Test fun forceOverwritesRecoverableDrift() {
        // Same-length edit: parse still succeeds, so only the run hash mismatches. Force lets the
        // caller write past the drift guard — it does NOT repair the already-edited body (that is
        // the run-wide rebuild path); it just stops the drift check from blocking the new region.
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert("", first).replace("first body", "xxxxxxxxxx")

        val rewritten = encoding.upsert(drifted, second, force = true)

        val regions = encoding.installed(rewritten)
        assertEquals(listOf("first", "second"), regions.map { it.id })
        assertEquals("xxxxxxxxxx", regions[0].content, "the drifted body is left as-is by force")
        assertEquals("второй", regions[1].content)
        assertFalse(encoding.hasDrift(rewritten), "re-rendering the run recomputes its hash")
    }

    @Test fun forceReplaceRebuildsARunWhoseBodiesAreUnrecoverable() {
        // Length-changing edit makes the manifest's byte boundaries unrecoverable, so upsert(force)
        // cannot repair the run on its own. The encoding-level escape hatch is forceReplace, which
        // takes the full region list and re-renders the run position-only.
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val original = encoding.upsert("# Notes\n", first)
        val drifted = original.replace("first body", "shorter")

        val rebuilt = encoding.forceReplace(drifted, listOf(second))

        assertEquals(listOf(second), encoding.installed(rebuilt))
        assertEquals("# Notes", encoding.unmanaged(rebuilt).text)
        assertFalse(encoding.hasDrift(rebuilt))
    }

    @Test fun forceReplacePreservesOwnedModeAndDropsTerminator() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        val text = encoding.upsert("", first)
        val drifted = text.replace("first body", "edited")

        val rebuilt = encoding.forceReplace(drifted, listOf(second))

        assertEquals(TargetOwnershipMode.OWNED, sniffOwnershipMode(rebuilt))
        assertFalse("rb:end" in rebuilt, "owned files carry no terminator")
        assertEquals(listOf(second), encoding.installed(rebuilt))
    }

    @Test fun forceReplaceThrowsWhenNoKb1Run() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        assertFailsWith<WrappedRunDriftException> { encoding.forceReplace("# just notes\n", listOf(first)) }
    }

    @Test fun removeWithForceBypassesTheDriftHashCheck() {
        // Same-length edit keeps the manifest's byte boundaries valid, so the run still parses; the
        // only thing blocking a normal remove is the drift hash check, which force skips.
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert("# Notes\n", first).replace("first body", "xxxxxxxxxx")

        val cleared = encoding.remove(drifted, first.id, force = true)

        assertEquals(drifted.substringBefore(PARTIAL_NOTICE), cleared)
    }

    @Test fun removeRebuildsRunViaForceReplaceWhenBodiesAreUnrecoverable() {
        // A length-changing edit breaks the byte boundaries: parsed() itself raises, so even a
        // forced remove at the encoding level cannot recover the run. The escape hatch is
        // forceReplace, which rebuilds from a caller-supplied region list.
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert("# Notes\n", first).replace("first body", "shorter")

        // Caller drops the region by rebuilding the run without it.
        val cleared = encoding.forceReplace(drifted, emptyList())

        assertEquals(drifted.substringBefore(PARTIAL_NOTICE), cleared)
    }

    // --- headerOf / hasDrift -------------------------------------------------

    @Test fun headerOfRecoversManifestFromDriftedBody() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert(encoding.upsert("", first), second).replace("first body", "edited")

        val header = encoding.headerOf(drifted)

        assertEquals(TargetOwnershipMode.PARTIAL, header?.mode)
        assertEquals(listOf("first", "second"), header?.blocks?.map { it.id })
    }

    @Test fun headerOfIsNullForNonKb1Text() {
        assertNull(WrappedRun(TargetOwnershipMode.PARTIAL).headerOf("# plain notes\n"))
    }

    @Test fun hasDriftReflectsManualEdits() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val intact = encoding.upsert("", first)
        assertFalse(encoding.hasDrift(intact))

        val edited = intact.replace("first body", "edited body")
        assertTrue(encoding.hasDrift(edited))
    }

    // --- installed drift fallback --------------------------------------------

    @Test fun installedFallsBackToDriftedRegionsWithNoBodies() {
        // When the byte lengths no longer line up, bodies cannot be trusted; installed() still
        // returns the manifest's ids/versions so status stays visible, with empty bodies.
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val drifted = encoding.upsert("", first).replace("first body", "edited body that is longer")

        val regions = encoding.installed(drifted)

        assertEquals(listOf("first"), regions.map { it.id })
        assertEquals(listOf(""), regions.map { it.content })
        assertEquals(listOf(""), regions.map { it.hash })
    }

    @Test fun partialUnmanagedPreservesOutsideTextAfterLengthChangingEdit() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val intact = encoding.upsert("# Before\n", first) + "# After\n"
        val drifted = intact.replace("first body", "edited managed body")

        assertEquals("# Before\n\n# After", encoding.unmanaged(drifted).text)
        assertTrue(encoding.hasDrift(drifted))
    }

    // --- migrateLegacy / disown / withNotice / toLegacy ----------------------

    @Test fun migrateLegacyKeepsUnmanagedTextAndRendersRb1() {
        val legacy = "# Notes\n\n<!-- kb old v1 ${hashContent("old")} -->\nold\n<!-- kb:end -->\n"
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)

        val migrated = encoding.migrateLegacy(legacy)

        assertEquals(TargetOwnershipMode.PARTIAL, sniffOwnershipMode(migrated))
        assertEquals("# Notes", encoding.unmanaged(migrated).text)
        assertEquals(listOf("old"), encoding.installed(migrated).map { it.id })
        assertTrue(migrated.contains("<!-- rb1 "))
        assertTrue(migrated.contains("<!-- rb:end -->"))
    }

    @Test fun kb1RemainsReadableAndUpsertMigratesItToRb1() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val kb1 = "$LEGACY_PARTIAL_NOTICE\n<!-- kb1 ${hashRun(first.content)} first@1:10 -->\nfirst body\n<!-- kb:end -->\n"

        assertEquals(listOf(first), encoding.installed(kb1))

        val migrated = encoding.upsert(kb1, second)

        assertEquals(listOf(first, second), encoding.installed(migrated))
        assertTrue("<!-- rb1 " in migrated)
        assertTrue("<!-- rb:end -->" in migrated)
        assertTrue(PARTIAL_NOTICE in migrated)
        assertFalse("<!-- kb1 " in migrated)
        assertFalse("<!-- kb:end -->" in migrated)
        assertFalse(LEGACY_PARTIAL_NOTICE in migrated)
    }

    @Test fun migrateLegacyRejectsOwnedWhenUnmanagedTextRemains() {
        val legacy = "# Notes\n\n<!-- kb old v1 ${hashContent("old")} -->\nold\n<!-- kb:end -->\n"
        // The mode guard in migrateLegacy raises the same exception type, so pin the fixture first:
        // the failure below must come from the hand-written text, not from a malformed marker.
        assertEquals(TargetOwnershipMode.LEGACY, sniffOwnershipMode(legacy))

        assertFailsWith<IllegalArgumentException> {
            WrappedRun(TargetOwnershipMode.OWNED).migrateLegacy(legacy)
        }
    }

    @Test fun disownDropsMarkersAndKeepsBodies() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = "# Notes\n\n" + encoding.upsert("", first)

        val bare = encoding.disown(text)

        assertEquals(TargetOwnershipMode.NONE, sniffOwnershipMode(bare))
        assertTrue("first body" in bare, "rendered body must survive disown: $bare")
        assertTrue("# Notes" in bare)
    }

    @Test fun ownedDisownLeavesOnlyTheBody() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        val text = encoding.upsert("", first)

        assertEquals("first body\n", encoding.disown(text))
    }

    @Test fun withNoticeTogglesWithoutTouchingBodies() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val withNotice = encoding.upsert("", first)

        val without = encoding.withNotice(withNotice, enabled = false)
        assertFalse(without.contains(PARTIAL_NOTICE))
        assertEquals(listOf(first), encoding.installed(without))

        val restored = encoding.withNotice(without, enabled = true)
        assertTrue(restored.contains(PARTIAL_NOTICE))
        assertEquals(listOf(first), encoding.installed(restored))
    }

    @Test fun toLegacyReEncodesIntoMarkerPairs() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = "# Notes\n\n" + encoding.upsert("", first)

        val legacy = encoding.toLegacy(text)

        assertEquals(TargetOwnershipMode.LEGACY, sniffOwnershipMode(legacy))
        assertEquals(listOf("first"), ManagedDocument.parse(legacy).map { it.id })
        assertTrue("# Notes" in legacy)
    }

    // --- reorder -------------------------------------------------------------

    @Test fun upsertUpdatesARegionWhereItStands() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert(encoding.upsert("# Notes\n", first), second)
        val newer = first.copy(version = 2, hash = hashContent("first body v2"), content = "first body v2")

        val updated = encoding.upsert(text, newer)

        assertEquals(listOf(newer, second), encoding.installed(updated))
        assertTrue(updated.indexOf("first body v2") < updated.indexOf("второй"))
        assertFalse(encoding.hasDrift(updated))
    }

    @Test fun reorderRewritesTheManifestAndKeepsBodiesAndOutsideText() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert(encoding.upsert("# Notes\n", first), second)

        val updated = encoding.reorder(text, listOf("second", "first"))

        assertEquals(listOf(second, first), encoding.installed(updated))
        assertEquals("# Notes", encoding.unmanaged(updated).text)
        assertFalse(encoding.hasDrift(updated))
        assertTrue(updated.indexOf("второй") < updated.indexOf("first body"))
    }

    @Test fun reorderIsIdempotentAndLeavesAnUnchangedOrderByteEqual() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        val text = encoding.upsert(encoding.upsert("", first), second)
        val order = listOf("second", "first")

        val once = encoding.reorder(text, order)
        assertEquals(once, encoding.reorder(once, order))
        assertEquals(text, encoding.reorder(text, listOf("first", "second")))
    }

    @Test fun reorderRefusesADriftedRun() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert(encoding.upsert("", first), second).replace("first body", "edited body")

        assertFailsWith<WrappedRunDriftException> { encoding.reorder(text, listOf("second", "first")) }
    }

    @Test fun reorderKeepsABlockTheOrderDoesNotMention() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = encoding.upsert(encoding.upsert("", first), second)

        val updated = encoding.reorder(text, listOf("second"))

        assertEquals(listOf("second", "first"), encoding.installed(updated).map { it.id })
    }

    // --- adoptSections guards -----------------------------------------------

    @Test fun adoptSectionsRejectsKeepOnOwnedTarget() {
        val encoding = WrappedRun(TargetOwnershipMode.OWNED)
        assertFailsWith<IllegalArgumentException> {
            encoding.adoptSections("", listOf(AdoptStep.Keep("kept")))
        }
    }

    private fun region(id: String, body: String) = ManagedRegion(id, 1, null, hashContent(body), body)

    @Test fun adoptSectionsPutsTheRunWhereTheReplacedSectionsWere() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val plan = listOf(
            AdoptStep.Keep("Intro."),
            AdoptStep.Replace("# Gamma\nG.", region("gamma", "# Gamma\n\nG.")),
            AdoptStep.Replace("# Delta\nD.", region("delta", "# Delta\n\nD.")),
            AdoptStep.Keep("# Outro\nO."),
        )

        val adopted = encoding.adoptSections("Intro.\n\n# Gamma\nG.\n\n# Delta\nD.\n\n# Outro\nO.\n", plan)

        assertEquals(listOf("gamma", "delta"), encoding.installed(adopted).map { it.id })
        assertTrue(adopted.indexOf("Intro.") < adopted.indexOf("<!-- rb1"))
        assertTrue(adopted.indexOf("<!-- rb:end -->") < adopted.indexOf("# Outro"))
        assertEquals("Intro.\n\n# Outro\nO.", encoding.unmanaged(adopted).text)
    }

    @Test fun adoptSectionsRefusesToMoveAKeptSectionOutFromBetweenReplacedOnes() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val text = "Intro.\n\n# Gamma\nG.\n\n# Note\nN.\n\n# Delta\nD.\n"
        val plan = listOf(
            AdoptStep.Keep("Intro."),
            AdoptStep.Replace("# Gamma\nG.", region("gamma", "# Gamma\n\nG.")),
            AdoptStep.Keep("# Note\nN."),
            AdoptStep.Replace("# Delta\nD.", region("delta", "# Delta\n\nD.")),
        )

        val refused = assertFailsWith<AdoptOrderException> { encoding.adoptSections(text, plan) }

        assertEquals(listOf(2), refused.blockers)
    }

    @Test fun adoptSectionsJoinsTheExistingRunOnTheSideTheSectionWasOn() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val withRun = encoding.upsert("# Head\nH.\n", first) + "\n# Tail\nT.\n"
        val unmanaged = encoding.unmanaged(withRun)
        assertEquals("# Head\nH.\n\n# Tail\nT.", unmanaged.text)
        assertEquals(2, unmanaged.linesBeforeRun)

        val adopted = encoding.adoptSections(
            withRun,
            listOf(AdoptStep.Replace("# Head\nH.", region("head", "# Head\n\nH.")), AdoptStep.Keep("# Tail\nT.")),
        )

        assertEquals(listOf("head", "first"), encoding.installed(adopted).map { it.id })
        assertTrue(adopted.indexOf("<!-- rb:end -->") < adopted.indexOf("# Tail"))
        assertEquals("# Tail\nT.", encoding.unmanaged(adopted).text)
    }

    @Test fun aSectionOnTheFarSideOfTheExistingRunIsRefused() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val withRun = encoding.upsert("# Head\nH.\n", first) + "\n# Middle\nM.\n\n# Tail\nT.\n"
        val plan = listOf(
            AdoptStep.Keep("# Head\nH."),
            AdoptStep.Keep("# Middle\nM."),
            AdoptStep.Replace("# Tail\nT.", region("tail", "# Tail\n\nT.")),
        )

        assertEquals(listOf(1), assertFailsWith<AdoptOrderException> { encoding.adoptSections(withRun, plan) }.blockers)
    }

    @Test fun adoptSectionsRefusesAPlanReadBeforeTheFileChanged() {
        val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
        val stale = listOf(
            AdoptStep.Replace("# Gamma\nG.", region("gamma", "# Gamma\n\nG.")),
            AdoptStep.Keep("# Note\nN."),
        )

        assertFailsWith<IllegalStateException> { encoding.adoptSections("# Gamma\nG. edited\n\n# Note\nN.\n", stale) }
    }

    @Test fun placementNamesASectionTheRunSplitsAndIgnoresBlankOnes() {
        // Text after the run with no heading of its own joins the section above it.
        assertEquals(listOf(0), AdoptPlacement.blockers(listOf("# A\na\nafter", "# B\nb"), listOf(false, true), 2))
        assertEquals(emptyList(), AdoptPlacement.blockers(listOf("# A\na", "  ", "# B\nb"), listOf(true, false, true), null))
        assertEquals(emptyList(), AdoptPlacement.blockers(listOf("# A\na", "# B\nb"), listOf(false, false), 0))
    }
}
