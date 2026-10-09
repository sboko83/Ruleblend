package dev.ruleblend.app.command

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.navigation.AppRoute
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule

/**
 * The palette as a surface: it must be usable without the mouse, because a shortcut that then needs
 * a click is not a shortcut. Both themes are checked — the highlighted row is the only thing telling
 * the user what Enter will do.
 */
class CommandPaletteScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings
    private var query by mutableStateOf("")
    private var selected: CommandItem? = null
    private var dismissed = 0

    private val index = listOf(
        CommandItem("route:LIBRARY", CommandKind.ACTION, "Go to Library", target = CommandTarget.Surface(AppRoute.LIBRARY)),
        CommandItem("project:/code/atlas", CommandKind.PLACE, "atlas", "project:/code/atlas", CommandTarget.Place("project:/code/atlas")),
        CommandItem(
            "RULE:git-safety",
            CommandKind.RULE,
            "Git safety",
            "never force-push",
            CommandTarget.Library(LibraryObjectKey(LibraryObjectKind.RULE, "git-safety")),
        ),
    )

    @Test fun `rows carry their kind and are reachable in both themes`() {
        inBothThemes {
            compose.onNodeWithTag("command-palette").assertIsDisplayed()
            compose.onNodeWithText("Git safety").assertIsDisplayed()
            compose.onNodeWithText(strings.commandTagPlace).assertIsDisplayed()
            compose.onNodeWithText(strings.commandScopeHint).assertIsDisplayed()
        }
    }

    @Test fun `typing narrows the list and an empty result says so instead of showing stale rows`() {
        content()
        compose.onNodeWithTag("command-input").performTextReplacement("git")
        compose.onNodeWithText("Git safety").assertIsDisplayed()
        compose.onNodeWithText("atlas").assertDoesNotExist()

        compose.onNodeWithTag("command-input").performTextReplacement("nothing here")
        compose.onNodeWithText(strings.commandNoMatches).assertIsDisplayed()
    }

    @Test fun `arrows move the highlight and Enter takes the highlighted row`() {
        content()
        compose.onNodeWithTag("command-input").performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        compose.runOnIdle { assertEquals("RULE:git-safety", selected?.id) }
    }

    @Test fun `Escape and the scrim close the palette without choosing anything`() {
        content()
        compose.onNodeWithTag("command-input").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithTag("command-scrim").performClick()
        compose.runOnIdle {
            assertEquals(2, dismissed)
            assertNull(selected)
        }
    }

    @Test fun `clicking the palette itself is not a dismissal`() {
        content()
        compose.onNodeWithTag("command-palette").performClick()
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    private fun content(mode: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent { Palette(mode) }
    }

    @Composable
    private fun Palette(mode: ThemeMode) {
        RuleblendTheme(mode) {
            CompositionLocalProvider(LocalStrings provides strings) {
                CommandPalette(
                    items = rankCommands(index, query),
                    query = query,
                    onQueryChange = { query = it },
                    onSelect = { selected = it },
                    onDismiss = { dismissed++ },
                )
            }
        }
    }

    private fun inBothThemes(assertions: () -> Unit) {
        var mode by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { Palette(mode) }
        assertions()
        compose.runOnIdle { mode = ThemeMode.DARK }
        compose.waitForIdle()
        assertions()
    }
}
