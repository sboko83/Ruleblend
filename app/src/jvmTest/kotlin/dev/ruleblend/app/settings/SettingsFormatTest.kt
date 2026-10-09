package dev.ruleblend.app.settings

import dev.ruleblend.app.i18n.EnStrings
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ages Settings prints next to a push and a commit: coarse, and never negative. */
class SettingsFormatTest {
    private val now = 1_700_000_000_000L

    @Test fun `an age is rounded to the largest unit that still fits`() {
        assertEquals("just now", ago(now - 30_000, EnStrings, now))
        assertEquals("18 min ago", ago(now - 18 * 60_000, EnStrings, now))
        assertEquals("1 hour ago", ago(now - 60 * 60_000, EnStrings, now))
        assertEquals("3 days ago", ago(now - 3 * 24 * 60 * 60_000L, EnStrings, now))
    }

    @Test fun `a moment in the future reads as just now instead of a negative age`() {
        assertEquals("just now", ago(now + 60_000, EnStrings, now))
    }
}
