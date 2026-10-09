package dev.ruleblend.app.navigation

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutsTest {
    @Test
    fun `digits follow the rail top to bottom`() {
        val keys = listOf(Key.One, Key.Two, Key.Three, Key.Four)
        keys.zip(AppRoute.primary).forEach { (key, route) ->
            assertEquals(AppShortcut.Go(route), shortcutFor(key, chord = true))
        }
    }

    @Test
    fun `settings and the palette have their own chords`() {
        assertEquals(AppShortcut.Go(AppRoute.SETTINGS), shortcutFor(Key.Comma, chord = true))
        assertEquals(AppShortcut.OpenCommands, shortcutFor(Key.K, chord = true))
    }

    @Test
    fun `without the modifier nothing is a shortcut`() {
        listOf(Key.One, Key.K, Key.Comma).forEach { key ->
            assertNull(shortcutFor(key, chord = false))
        }
    }

    @Test
    fun `keys outside the vocabulary are left to the focused screen`() {
        listOf(Key.Five, Key.A, Key.Enter, Key.Escape).forEach { key ->
            assertNull(shortcutFor(key, chord = true))
        }
    }

    @Test
    fun `every destination reachable by a chord advertises it`() {
        assertEquals((1..4).map { commandShortcut("$it") }, AppRoute.primary.map(::shortcutLabel))
        assertEquals(commandShortcut(","), shortcutLabel(AppRoute.SETTINGS))
        // Resolve is entered from a Home card, not from the rail, so it has nothing to advertise.
        assertNull(shortcutLabel(AppRoute.RESOLVE))
    }

    @Test
    fun `labels follow the host platform`() {
        for (key in listOf("K", "1", ",", "\\")) {
            assertEquals("⌘$key", commandShortcut(key, "Mac OS X"))
            assertEquals("Ctrl+$key", commandShortcut(key, "Windows 11"))
            assertEquals("Ctrl+$key", commandShortcut(key, "Linux"))
        }
    }
}
