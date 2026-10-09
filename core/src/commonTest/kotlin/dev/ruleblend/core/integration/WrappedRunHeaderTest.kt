package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WrappedRunHeaderTest {
    @Test
    fun `partial header round trips`() {
        val header = WrappedRunHeader(
            mode = TargetOwnershipMode.PARTIAL,
            hash = "8f2a1c04",
            blocks = listOf(WrappedRunBlock("docs-auto", 1), WrappedRunBlock("planning", 2, group = "root-rules")),
        )

        val rendered = renderWrappedRunHeader(header)

        assertEquals("<!-- rb1 8f2a1c04 docs-auto@1 planning@2:g=root-rules -->", rendered)
        assertEquals(header, parseWrappedRunHeader(rendered))
    }

    @Test
    fun `profile origin header round trips`() {
        val header = WrappedRunHeader(
            mode = TargetOwnershipMode.PARTIAL,
            hash = "8f2a1c04",
            blocks = listOf(WrappedRunBlock("review", 3, length = 42, origin = "p=testing")),
        )

        val rendered = renderWrappedRunHeader(header)

        assertEquals("<!-- rb1 8f2a1c04 review@3:42:p=testing -->", rendered)
        assertEquals(header, parseWrappedRunHeader(rendered))
    }

    @Test
    fun `bare legacy origin reads as a group and re-renders canonically`() {
        val parsed = parseWrappedRunHeader("<!-- rb1 8f2a1c04 review@3:42:quality -->")

        assertEquals(WrappedRunBlock("review", 3, 42, group = "quality"), parsed?.blocks?.single())
        assertEquals("<!-- rb1 8f2a1c04 review@3:42:g=quality -->", renderWrappedRunHeader(requireNotNull(parsed)))
    }

    @Test
    fun `owned header round trips with notice`() {
        val header = WrappedRunHeader(TargetOwnershipMode.OWNED, "8f2a1c04", listOf(WrappedRunBlock("planning", 1)))

        assertEquals(header, parseWrappedRunHeader(renderWrappedRunHeader(header)))
    }

    @Test
    fun `owned header without notice remains readable`() {
        assertEquals(
            WrappedRunHeader(TargetOwnershipMode.OWNED, "8f2a1c04", listOf(WrappedRunBlock("planning", 1)), notice = false),
            parseWrappedRunHeader("<!-- rb1 owned 8f2a1c04 planning@1 -->"),
        )
    }

    @Test
    fun `owned Kitbash header remains readable after rename`() {
        assertEquals(
            WrappedRunHeader(
                TargetOwnershipMode.OWNED,
                "8f2a1c04",
                listOf(WrappedRunBlock("planning", 1)),
                format = WrappedRunFormat.KB1,
            ),
            parseWrappedRunHeader(
                "<!-- kb1 owned 8f2a1c04 planning@1 — managed by Kitbash; edits are overwritten -->",
            ),
        )
    }

    @Test
    fun `malformed headers are rejected`() {
        assertNull(parseWrappedRunHeader("<!-- rb1 not-a-hash planning@1 -->"))
        assertNull(parseWrappedRunHeader("<!-- rb1 8f2a1c04 planning@x -->"))
        assertNull(parseWrappedRunHeader("<!-- rb1 8f2a1c04 planning@1: -->"))
        assertNull(parseWrappedRunHeader("<!-- rb1 8f2a1c04 planning@1:p= -->"))
        assertNull(parseWrappedRunHeader("<!-- rb1 8f2a1c04 planning@1:g=team:extra -->"))
    }

    @Test
    fun `hash normalizes windows line endings`() {
        assertEquals(hashRun("first\nsecond\n"), hashRun("first\r\nsecond\r\n"))
    }

    @Test
    fun `sniffs wrapped legacy and unmanaged files`() {
        assertEquals(TargetOwnershipMode.PARTIAL, sniffOwnershipMode("$PARTIAL_NOTICE\n<!-- rb1 8f2a1c04 rule@1 -->\nbody\n<!-- rb:end -->\n"))
        assertEquals(TargetOwnershipMode.OWNED, sniffOwnershipMode("<!-- rb1 owned 8f2a1c04 rule@1 -->\nbody\n"))
        assertEquals(TargetOwnershipMode.PARTIAL, sniffOwnershipMode("$LEGACY_PARTIAL_NOTICE\n<!-- kb1 8f2a1c04 rule@1 -->\nbody\n<!-- kb:end -->\n"))
        assertEquals(TargetOwnershipMode.OWNED, sniffOwnershipMode("<!-- kb1 owned 8f2a1c04 rule@1 -->\nbody\n"))
        assertEquals(TargetOwnershipMode.LEGACY, sniffOwnershipMode("<!-- kb rule v1 deadbeef -->\nbody\n<!-- kb:end -->\n"))
        assertEquals(TargetOwnershipMode.LEGACY, sniffOwnershipMode("<!-- kitbash:begin id=rule v=1 hash=deadbeef -->\nbody\n<!-- kitbash:end id=rule -->\n"))
        assertEquals(TargetOwnershipMode.NONE, sniffOwnershipMode("# Notes\n"))
    }
}
