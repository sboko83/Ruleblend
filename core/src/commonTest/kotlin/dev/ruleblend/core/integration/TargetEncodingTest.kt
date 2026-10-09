package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TargetEncodingTest {

    @Test fun targetEncodingPicksLegacyMarkersForUnmanagedText() {
        val encoding = targetEncoding("# plain notes\n")

        assertIs<LegacyMarkers>(encoding)
    }

    @Test fun targetEncodingPicksLegacyMarkersForLegacyMarkerFiles() {
        val legacy = "# Notes\n\n<!-- kb old v1 ${hashContent("old")} -->\nold\n<!-- kb:end -->\n"

        // Guard the fixture itself: unmanaged text also maps to LegacyMarkers, so without this the
        // assertion below would pass on a malformed marker string.
        assertEquals(TargetOwnershipMode.LEGACY, sniffOwnershipMode(legacy))
        assertIs<LegacyMarkers>(targetEncoding(legacy))
    }

    @Test fun targetEncodingPicksWrappedPartialRun() {
        val text = WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", sampleRegion())

        val encoding = targetEncoding(text)

        assertIs<WrappedRun>(encoding)
        assertEquals(TargetOwnershipMode.PARTIAL, encodingMode(encoding))
    }

    @Test fun targetEncodingPicksWrappedOwnedRun() {
        val text = WrappedRun(TargetOwnershipMode.OWNED).upsert("", sampleRegion())

        val encoding = targetEncoding(text)

        assertIs<WrappedRun>(encoding)
        assertEquals(TargetOwnershipMode.OWNED, encodingMode(encoding))
    }

    @Test fun targetEncodingPropagatesPartialNoticeFlag() {
        // targetEncoding reads the notice flag from the presence of PARTIAL_NOTICE before the run,
        // so a later write through the selected encoding must keep (or keep omitting) the notice.
        val region = sampleRegion()
        val withNotice = WrappedRun(TargetOwnershipMode.PARTIAL, notice = true).upsert("# Notes\n", region)
        val withoutNotice = WrappedRun(TargetOwnershipMode.PARTIAL, notice = false).upsert("# Notes\n", region)
        assertTrue(withNotice.contains(PARTIAL_NOTICE))
        assertFalse(withoutNotice.contains(PARTIAL_NOTICE))

        val second = region.copy(id = "second", hash = hashContent("second body"), content = "second body")
        val rewrittenWith = (targetEncoding(withNotice) as WrappedRun).upsert(withNotice, second)
        val rewrittenWithout = (targetEncoding(withoutNotice) as WrappedRun).upsert(withoutNotice, second)

        assertTrue(rewrittenWith.contains(PARTIAL_NOTICE), "notice must be kept when present")
        assertFalse(rewrittenWithout.contains(PARTIAL_NOTICE), "notice must stay off when absent")
    }

    @Test fun legacyMarkersObjectDelegatesToManagedDocument() {
        // LegacyMarkers is a thin TargetEncoding adapter over ManagedDocument; force is a no-op there.
        val text = "# Notes\n\n<!-- kb first v1 ${hashContent("first")} -->\nfirst\n<!-- kb:end -->\n"

        val region = sampleRegion()
        val upserted = LegacyMarkers.upsert(text, region, force = true)
        assertEquals(listOf("first", region.id), LegacyMarkers.installed(upserted).map { it.id })

        val removed = LegacyMarkers.remove(upserted, region.id, force = true)
        assertEquals(listOf("first"), LegacyMarkers.installed(removed).map { it.id })

        assertEquals("# Notes", LegacyMarkers.unmanaged(text).text)
    }

    private fun sampleRegion() = ManagedRegion(
        id = "new",
        version = 1,
        group = null,
        hash = hashContent("new body"),
        content = "new body",
    )

    /**
     * Pulls the mode back out of a WrappedRun. The constructor stores it privately, so we recover it
     * behaviourally: an owned run has no rb:end terminator, a partial one does.
     */
    private fun encodingMode(encoding: TargetEncoding): TargetOwnershipMode {
        require(encoding is WrappedRun)
        // Round-trip a one-block render and check for the terminator to distinguish partial from owned.
        val probe = encoding.upsert("", sampleRegion())
        return if ("<!-- rb:end -->" in probe) TargetOwnershipMode.PARTIAL else TargetOwnershipMode.OWNED
    }
}
