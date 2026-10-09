package dev.ruleblend.core.integration

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WrappedRunLineEndingsTest {
    private val first = region("first", "Первая 🪟\nSecond line")
    private val second = region("second", "Other\nbody")
    private val updated = region("first", "Updated\nправило", 2)
    private val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)

    private enum class Endings {
        LF, CRLF, MIXED;

        fun apply(text: String): String = text.split('\n').mapIndexed { index, line ->
            if (index == 0) line else (if (this == CRLF || this == MIXED && index % 2 == 1) "\r\n" else "\n") + line
        }.joinToString("")
    }

    private data class Fixture(val before: String, val run: String, val after: String) {
        val text: String get() = before + run + after
    }

    private fun fixture(endings: Endings, notice: Boolean = true): Fixture {
        val writer = WrappedRun(TargetOwnershipMode.PARTIAL, notice)
        val run = writer.upsert(writer.upsert("", first), second)
        return Fixture(
            endings.apply("  \n# Заметки 🪟  \n\t\n"),
            endings.apply(run),
            endings.apply("\n \t\n# Tail\nKeep trailing spaces  \n\t "),
        )
    }

    private fun assertOutside(fixture: Fixture, actual: String) {
        val header = actual.indexOf("<!-- rb1 ")
        val start = actual.indexOf(PARTIAL_NOTICE).takeIf { it >= 0 } ?: header
        assertTrue(start >= 0, "Missing managed header")
        val markerEnd = actual.indexOf("<!-- rb:end -->", header) + "<!-- rb:end -->".length
        assertTrue(markerEnd >= "<!-- rb:end -->".length, "Missing terminator")
        val end = markerEnd + when {
            actual.startsWith("\r\n", markerEnd) -> 2
            actual.startsWith("\n", markerEnd) -> 1
            else -> 0
        }
        assertBytes(fixture.before, actual.substring(0, start))
        assertBytes(fixture.after, actual.substring(end))
        assertFalse(encoding.hasDrift(actual))
    }

    @Test fun crlfUpdateUsesOriginalOffsets() {
        val fixture = fixture(Endings.CRLF)
        val result = encoding.upsert(fixture.text, updated)
        assertOutside(fixture, result)
        assertEquals(listOf(updated, second), encoding.installed(result))
    }

    @Test fun installPreservesEveryExistingByte() {
        for (endings in Endings.entries) {
            for (original in listOf("# Notes  \n\t \n", "# Notes  ", " \n\t \n")) {
                val text = endings.apply(original)
                val result = encoding.upsert(text, first)
                assertBytes(text, result.take(text.length))
                assertEquals(listOf(first), encoding.installed(result))
            }
        }
    }

    @Test fun upsertPreservesOutsideBytesWithEveryLineEnding() {
        for (endings in Endings.entries) for (notice in listOf(false, true)) {
            val fixture = fixture(endings, notice)
            for (region in listOf(updated, region("third", "Third\nbody"))) {
                val result = encoding.upsert(fixture.text, region)
                assertOutside(fixture, result)
                assertEquals(region, encoding.installed(result).single { it.id == region.id })
            }
        }
    }

    @Test fun removePreservesOutsideBytesIncludingLastRegion() {
        for (endings in Endings.entries) {
            val fixture = fixture(endings)
            val result = encoding.remove(fixture.text, first.id)
            assertOutside(fixture, result)
            assertEquals(listOf(second), encoding.installed(result))
            assertBytes(fixture.before + fixture.after, encoding.remove(result, second.id))
            assertBytes(fixture.text, encoding.remove(fixture.text, "missing"))
        }
    }

    @Test fun reorderPreservesOutsideBytesAndNoOpIsByteEqual() {
        for (endings in Endings.entries) {
            val fixture = fixture(endings)
            assertBytes(fixture.text, encoding.reorder(fixture.text, listOf(first.id, second.id)))
            val result = encoding.reorder(fixture.text, listOf(second.id, first.id))
            assertOutside(fixture, result)
            assertEquals(listOf(second, first), encoding.installed(result))
        }
    }

    @Test fun forceReplacePreservesOutsideBytesOnLengthChangingDrift() {
        for (endings in Endings.entries) {
            val fixture = fixture(endings)
            val drifted = fixture.text.replace("Первая", "Hand edited and longer")
            assertTrue(encoding.hasDrift(drifted))
            val result = encoding.forceReplace(drifted, listOf(updated))
            assertOutside(fixture, result)
            assertEquals(listOf(updated), encoding.installed(result))
            assertBytes(fixture.before + fixture.after, encoding.forceReplace(drifted, emptyList()))
        }
    }

    @Test fun disownPreservesRawBodiesAndOutsideBytes() {
        for (endings in Endings.entries) for (mode in listOf(TargetOwnershipMode.PARTIAL, TargetOwnershipMode.OWNED)) {
            val writer = WrappedRun(mode)
            val raw = endings.apply(writer.upsert(writer.upsert("", first), second))
            val bodyStart = raw.indexOf('\n', raw.indexOf("<!-- rb1 ")) + 1
            val bodyEnd = if (mode == TargetOwnershipMode.PARTIAL) raw.indexOf("<!-- rb:end -->") else raw.length
            val before = if (mode == TargetOwnershipMode.PARTIAL) endings.apply("# Before  \n\n") else ""
            val after = if (mode == TargetOwnershipMode.PARTIAL) endings.apply("\n# After  \n\t ") else ""
            assertBytes(before + raw.substring(bodyStart, bodyEnd) + after, writer.disown(before + raw + after))
        }
    }

    @Test fun noticeToggleAndRollbackPreserveOutsideBytes() {
        for (endings in Endings.entries) {
            val fixture = fixture(endings)
            val without = encoding.withNotice(fixture.text, false)
            assertOutside(fixture, without)
            assertFalse(PARTIAL_NOTICE in without)
            assertOutside(fixture, encoding.withNotice(without, true))
            val legacy = encoding.toLegacy(fixture.text)
            assertBytes(fixture.before, legacy.substringBefore("<!-- kb "))
            assertBytes(fixture.after, legacy.substringAfter("<!-- kb:end -->\n"))
            assertEquals(listOf(first, second), LegacyMarkers.installed(legacy))
        }
    }

    @Test fun migrationPreservesRawTextBetweenLegacyRuns() {
        for (endings in Endings.entries) for (oldPairs in listOf(false, true)) {
            val before = endings.apply(" \n# Before  \n\n")
            val between = endings.apply("\n\t\n# Between 🪟\n\n")
            val after = endings.apply("\n# After  \n\t ")
            fun legacy(region: ManagedRegion): String = endings.apply(if (oldPairs) {
                "<!-- ruleblend:begin id=${region.id} v=${region.version} hash=${region.hash} -->\n" +
                    region.content + "\n<!-- ruleblend:end id=${region.id} -->\n"
            } else LegacyMarkers.upsert("", region))
            val migrated = encoding.migrateLegacy(before + legacy(first) + between + legacy(second) + after)
            val outside = before + between + after
            assertBytes(outside, migrated.take(outside.length))
            assertEquals(listOf(first, second), encoding.installed(migrated))
            assertFalse(encoding.hasDrift(migrated))
            assertFalse("<!-- kb " in migrated || "<!-- ruleblend:begin" in migrated)
        }
    }

    @Test fun oldWrappedFormatMigratesWithoutChangingOutsideBytes() {
        for (endings in Endings.entries) {
            val fixture = fixture(endings)
            val legacy = fixture.text.replace("<!-- rb1 ", "<!-- kb1 ")
                .replace("<!-- rb:end -->", "<!-- kb:end -->")
                .replace(PARTIAL_NOTICE, LEGACY_PARTIAL_NOTICE)
            val result = encoding.upsert(legacy, updated)
            assertOutside(fixture, result)
            assertEquals(listOf(updated, second), encoding.installed(result))
            assertFalse("<!-- kb" in result || LEGACY_PARTIAL_NOTICE in result)
        }
    }

    @Test fun ownedMutationsAndTerminatorAtEofRemainParseable() {
        for (endings in Endings.entries) for (mode in listOf(TargetOwnershipMode.PARTIAL, TargetOwnershipMode.OWNED)) {
            val writer = WrappedRun(mode)
            val text = endings.apply(writer.upsert(writer.upsert("", first), second)).trimEnd('\r', '\n')
            val changed = writer.upsert(text, updated)
            assertEquals(listOf(updated, second), writer.installed(changed))
            assertFalse(writer.hasDrift(changed))
            val reordered = writer.reorder(text, listOf(second.id, first.id))
            assertEquals(listOf(second, first), writer.installed(reordered))
            assertEquals(listOf(second), writer.installed(writer.remove(text, first.id)))
        }
    }

    private fun assertBytes(expected: String, actual: String) =
        assertContentEquals(expected.encodeToByteArray(), actual.encodeToByteArray())

    private fun region(id: String, body: String, version: Int = 1) =
        ManagedRegion(id, version, null, hashContent(body), body)
}
