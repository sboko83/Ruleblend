package dev.ruleblend.app.navigation

import androidx.compose.ui.input.key.Key

/**
 * What a chord asks the shell to do. Shortcuts are shell-level only: they move between surfaces and
 * open the command palette. Nothing here writes, so a mistyped chord costs a glance, not an undo.
 */
sealed interface AppShortcut {
    /** Open ⌘K. */
    data object OpenCommands : AppShortcut

    /** Switch to a top-level surface. */
    data class Go(val route: AppRoute) : AppShortcut

    /** Collapse the sidebar to icons, or expand it back. */
    data object ToggleSidebar : AppShortcut
}

/**
 * Resolves a key press into a shell action. [chord] is the platform command modifier — ⌘ on macOS,
 * Ctrl elsewhere; both are accepted everywhere so a chord learnt on one machine works on the other.
 *
 * The digits follow the rail top to bottom, so the shortcut is readable off the screen rather than
 * memorised from a list; `,` opens Settings the way every desktop app does, and `\` folds the
 * sidebar the way editors fold theirs.
 */
fun shortcutFor(key: Key, chord: Boolean): AppShortcut? {
    if (!chord) return null
    return when (key) {
        Key.K -> AppShortcut.OpenCommands
        Key.Comma -> AppShortcut.Go(AppRoute.SETTINGS)
        Key.Backslash -> AppShortcut.ToggleSidebar
        else -> digitRoutes[key]?.let(AppShortcut::Go)
    }
}

/** The chord printed next to a destination, or null for a destination without one. */
fun shortcutLabel(route: AppRoute): String? = when (route) {
    AppRoute.SETTINGS -> commandShortcut(",")
    else -> AppRoute.primary.indexOf(route).takeIf { it >= 0 }?.let { commandShortcut("${it + 1}") }
}

/** Display the platform's usual modifier while accepting both modifiers in the shell. */
fun commandShortcut(key: String, osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Mac")) "⌘$key" else "Ctrl+$key"

/** The chord printed next to the sidebar toggle. */
val ToggleSidebarShortcutLabel: String = commandShortcut("\\")

private val digitRoutes: Map<Key, AppRoute> =
    listOf(Key.One, Key.Two, Key.Three, Key.Four)
        .zip(AppRoute.primary)
        .toMap()
