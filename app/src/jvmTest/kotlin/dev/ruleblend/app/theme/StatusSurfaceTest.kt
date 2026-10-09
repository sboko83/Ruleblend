package dev.ruleblend.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The mat is what a status surface is read by before a word of it is, so every state has to own a
 * colour nothing else uses — including the card it carries and the window behind it. Both themes,
 * because the design contract treats them as equals.
 */
class StatusSurfaceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun `every state owns a mat, distinct from the others and from the card it carries`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var mats = emptyList<Color>()
        var card = Color.Unspecified
        var window = Color.Unspecified
        compose.setContent {
            RuleblendTheme(theme) {
                card = MaterialTheme.colorScheme.surface
                window = MaterialTheme.colorScheme.background
                // `null` is a state of its own here: a whole file, which has no sync verdict to wear.
                mats = (StatusMark.entries + null).map { it.matColor() }
            }
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.runOnIdle {
                assertEquals(mats.size, mats.distinct().size, "$mode reuses a mat across states")
                mats.forEach { mat ->
                    assertTrue(mat != card, "$mode paints a mat the colour of the card on it")
                    assertTrue(mat != window, "$mode paints a mat the colour of the window behind it")
                }
            }
        }
    }

    @Test fun `the line written on a mat is not the grey used on the window`() {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        var notices = emptyList<Color>()
        var faint = Color.Unspecified
        compose.setContent {
            RuleblendTheme(theme) {
                faint = RuleblendTheme.extraColors.faint
                // The three tinted mats say their state in its own hue; grey on a tint reads as
                // neither the tint nor the text.
                notices = listOf(StatusMark.MANAGED, StatusMark.UPDATE, StatusMark.CONFLICT)
                    .map { it.matNoticeColor() }
            }
        }

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.runOnIdle {
                notices.forEach { assertTrue(it != faint, "$mode writes a mat notice in window grey") }
            }
        }
    }
}
