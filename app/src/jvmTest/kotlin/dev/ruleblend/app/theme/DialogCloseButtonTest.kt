package dev.ruleblend.app.theme

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.core.config.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Every surface that is not the main window closes from its own corner. A form is left far more
 * often than it is filled in, and hunting for the Cancel button at the bottom of a full-window
 * editor — or knowing that Esc is bound — is not the way out a person looks for first.
 */
class DialogCloseButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings

    @Test
    fun `the corner close dismisses the dialog`() {
        var dismissed = 0
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    RuleblendDialog(
                        onDismiss = { dismissed++ },
                        title = "Edit AGENTS.md",
                        confirmLabel = strings.actionSave,
                        onConfirm = {},
                        dismissLabel = strings.actionCancel,
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(strings.actionClose).performClick()

        assertEquals(1, dismissed, "the corner close must dismiss the dialog it sits in")
    }

    @Test
    fun `the close is bigger than the mark that empties a field`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    RuleblendDialog(
                        onDismiss = {},
                        title = "Edit AGENTS.md",
                        confirmLabel = strings.actionSave,
                        onConfirm = {},
                        dismissLabel = strings.actionCancel,
                    )
                }
            }
        }

        // A control, not a hint inside a field: it has to be found without aiming for it.
        compose.onNodeWithContentDescription(strings.actionClose).assertHeightIsAtLeast(FieldClearSize + 4.dp)
        assertTrue(DialogCloseSize > FieldClearSize, "the dialog close must not be a field's clear mark")
    }

    @Test
    fun `an acknowledgement has no close, because its only button is the way out`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    RuleblendDialog(
                        onDismiss = {},
                        text = "The file was written.",
                        confirmLabel = strings.actionOk,
                        onConfirm = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription(strings.actionClose).assertDoesNotExist()
    }
}
