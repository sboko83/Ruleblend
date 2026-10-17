package dev.ruleblend.app.library

/**
 * Claude Code mods are frozen (docs/plans/P002-claude-code-mods.md). Core keeps storing, syncing and
 * archiving them, but the app shows no mod surface and never runs `claude plugin`.
 * Tests of the frozen UI switch it on.
 */
internal object ModsFeature {
    @Volatile var enabled: Boolean = false
}
