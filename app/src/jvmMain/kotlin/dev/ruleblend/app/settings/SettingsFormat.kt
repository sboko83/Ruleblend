package dev.ruleblend.app.settings

import dev.ruleblend.app.i18n.Strings

/**
 * Coarse age of a moment in the past — "just now", "18 min ago", "3 days ago". Settings answers
 * "is this fresh?", not "when exactly?", so the answer is rounded and needs no clock behind it.
 */
fun ago(atEpochMillis: Long, strings: Strings, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - atEpochMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> strings.agoJustNow
        minutes < 60 -> strings.agoMinutes(minutes)
        minutes < 60 * 24 -> strings.agoHours(minutes / 60)
        else -> strings.agoDays(minutes / (60 * 24))
    }
}

/** The file manager is named after the platform's own: Finder on macOS, "file manager" elsewhere. */
fun revealLabel(strings: Strings, osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Mac")) strings.setRevealInFinder else strings.setRevealInFiles
