package dev.ruleblend.app.navigation

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule

class AppShellTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `sidebar navigates to every primary route in both themes`() {
        var route by mutableStateOf(AppRoute.HOME)
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            RuleblendTheme(theme) {
                AppShell(
                    route = route,
                    labelFor = AppRoute::name,
                    onNavigate = { route = it },
                ) {
                    Text("content-${route.name}")
                }
            }
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            (AppRoute.primary + AppRoute.SETTINGS).forEach { destination ->
                compose.onNodeWithContentDescription(destination.name).performClick()
                compose.onNodeWithText("content-${destination.name}").assertExists()
                compose.runOnIdle { assertEquals(destination, route) }
            }
        }
    }

    @Test
    fun `sidebar collapses to icons and expands back`() {
        var route by mutableStateOf(AppRoute.HOME)
        var expanded by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides RuStrings) {
                RuleblendTheme(ThemeMode.DARK) {
                    AppShell(
                        route = route,
                        labelFor = { destination ->
                            when (destination) {
                                AppRoute.HOME -> RuStrings.navHome
                                AppRoute.PLACE -> RuStrings.navPlace
                                AppRoute.LIBRARY -> RuStrings.navLibrary
                                AppRoute.COVERAGE -> RuStrings.navCoverage
                                AppRoute.SETTINGS -> RuStrings.navSettings
                                AppRoute.RESOLVE -> RuStrings.navResolve
                            }
                        },
                        onNavigate = { route = it },
                        sidebarExpanded = expanded,
                        onSidebarExpandedChange = { expanded = it },
                    ) {
                        Text("content")
                    }
                }
            }
        }

        compose.onNodeWithTag("nav-sidebar").assertWidthIsEqualTo(204.dp)
        compose.onNodeWithText("Ruleblend", substring = false, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Главная", substring = false, useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithContentDescription(RuStrings.navCollapseSidebar).performClick()

        compose.runOnIdle { assertFalse(expanded) }
        compose.onNodeWithTag("nav-sidebar").assertWidthIsEqualTo(68.dp)
        compose.onNodeWithText("Ruleblend", substring = false, useUnmergedTree = true).assertDoesNotExist()
        compose.onAllNodesWithText("Главная", substring = false, useUnmergedTree = true).assertCountEquals(1)
        // Folded, every destination is a square target around its icon.
        (AppRoute.primary + AppRoute.SETTINGS).forEach { destination ->
            compose.onNodeWithTag("nav-${destination.name.lowercase()}")
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
        }
        compose.onNodeWithTag("nav-library").performClick()
        compose.runOnIdle { assertEquals(AppRoute.LIBRARY, route) }

        compose.onNodeWithContentDescription(RuStrings.navExpandSidebar).performClick()
        compose.runOnIdle { assertTrue(expanded) }
        compose.onNodeWithTag("nav-sidebar").assertWidthIsEqualTo(204.dp)
        compose.onNodeWithText("Ruleblend", substring = false, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Библиотека", substring = false, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `the sidebar chord folds and unfolds the sidebar`() {
        var expanded by mutableStateOf(true)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(
                    route = AppRoute.HOME,
                    labelFor = AppRoute::name,
                    onNavigate = {},
                    sidebarExpanded = expanded,
                    onSidebarExpandedChange = { expanded = it },
                ) {
                    Text("content")
                }
            }
        }

        repeat(2) { step ->
            compose.onNodeWithTag("app-shell").performKeyInput {
                keyDown(Key.MetaLeft)
                pressKey(Key.Backslash)
                keyUp(Key.MetaLeft)
            }
            compose.runOnIdle { assertEquals(step == 1, expanded) }
        }
    }

    @Test
    fun `sidebar targets sit at equal distance from the item above and below`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(route = AppRoute.HOME, labelFor = AppRoute::name, onNavigate = {}) { Text("content") }
            }
        }

        // Hover and focus paint this node, so the gap it leaves above and below is what the eye
        // reads as the spacing of the sidebar. Neighbours have to leave the same one.
        val targets = AppRoute.primary.map { compose.onNodeWithContentDescription(it.name).fetchSemanticsNode() }
        val gaps = targets.zipWithNext { above, below ->
            below.boundsInRoot.top - above.boundsInRoot.bottom
        }
        gaps.forEach { assertEquals(gaps.first(), it, 0.5f) }
        assertTrue(gaps.first() > 0f)
    }

    @Test
    fun `the palette opens from the shortcut and from the pill that advertises it`() {
        var opened = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(
                    route = AppRoute.HOME,
                    labelFor = AppRoute::name,
                    onNavigate = {},
                    onOpenCommands = { opened++ },
                ) {
                    Text("content")
                }
            }
        }

        compose.onNodeWithTag("command-pill").performClick()
        compose.runOnIdle { assertEquals(1, opened) }

        compose.onNodeWithTag("app-shell").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.K)
            keyUp(Key.MetaLeft)
        }
        compose.runOnIdle { assertEquals(2, opened) }
    }

    @Test
    fun `chords reach every rail destination and keep working after the rail takes focus`() {
        var route by mutableStateOf(AppRoute.HOME)
        var opened = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(
                    route = route,
                    labelFor = AppRoute::name,
                    onNavigate = { route = it },
                    onOpenCommands = { opened++ },
                ) {
                    Text("content")
                }
            }
        }

        val chords = listOf(
            Key.One to AppRoute.HOME,
            Key.Two to AppRoute.PLACE,
            Key.Three to AppRoute.LIBRARY,
            Key.Four to AppRoute.COVERAGE,
            Key.Comma to AppRoute.SETTINGS,
        )
        chords.forEach { (key, destination) ->
            compose.onNodeWithTag("app-shell").performKeyInput {
                keyDown(Key.CtrlLeft)
                pressKey(key)
                keyUp(Key.CtrlLeft)
            }
            compose.runOnIdle { assertEquals(destination, route) }
        }

        // Focus moves into the surface as soon as anything is clicked; the preview pass has to keep
        // the chords alive, otherwise they only work on a freshly started window.
        compose.onNodeWithContentDescription(AppRoute.LIBRARY.name).requestFocus()
        compose.onNodeWithContentDescription(AppRoute.LIBRARY.name).assertIsFocused()
        compose.onNodeWithTag("app-shell").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.K)
            keyUp(Key.MetaLeft)
        }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `chords keep working after the focused node on a surface leaves composition`() {
        var field by mutableStateOf(true)
        var opened = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(route = AppRoute.HOME, labelFor = AppRoute::name, onNavigate = {}, onOpenCommands = { opened++ }) {
                    if (field) Box(Modifier.size(20.dp).focusable().testTag("field"))
                }
            }
        }

        compose.onNodeWithTag("field").requestFocus()
        compose.onNodeWithTag("field").assertIsFocused()
        // Leaving composition clears focus from the whole window, not just from the field.
        compose.runOnIdle { field = false }
        compose.onNodeWithTag("app-shell").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.K)
            keyUp(Key.MetaLeft)
        }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `chords keep working after the overlay that held focus closes`() {
        var overlay by mutableStateOf(true)
        var opened = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(
                    route = AppRoute.HOME,
                    labelFor = AppRoute::name,
                    onNavigate = {},
                    onOpenCommands = { opened++ },
                    overlay = if (!overlay) null else {
                        { Box(Modifier.size(20.dp).focusable().testTag("overlay")) }
                    },
                ) {
                    Text("content")
                }
            }
        }

        compose.onNodeWithTag("overlay").requestFocus()
        compose.onNodeWithTag("overlay").assertIsFocused()
        compose.runOnIdle { overlay = false }
        compose.onNodeWithTag("app-shell").performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.K)
            keyUp(Key.MetaLeft)
        }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `a focused sidebar item is activated from the keyboard`() {
        var route by mutableStateOf(AppRoute.HOME)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                AppShell(route = route, labelFor = AppRoute::name, onNavigate = { route = it }) {
                    Text("content")
                }
            }
        }

        compose.onNodeWithContentDescription(AppRoute.COVERAGE.name).requestFocus()
        compose.onNodeWithContentDescription(AppRoute.COVERAGE.name).performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(AppRoute.COVERAGE, route) }
    }
}
