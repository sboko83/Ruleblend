package dev.ruleblend.app.theme

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ruleblend.core.config.ThemeMode
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ScrolledFieldSelectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `drag after scrolling starts at the visible line`() {
        val text = (1..100).joinToString("\n") { "Line %03d sample text for selection".format(it) }
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                RuleblendOutlinedTextField(
                    value = text,
                    onValueChange = {},
                    modifier = Modifier.size(400.dp, 200.dp).testTag("editor"),
                )
            }
        }
        val editor = compose.onNodeWithTag("editor")
        editor.performMouseInput { moveTo(center); scroll(900f) }
        compose.waitForIdle()
        editor.performMouseInput {
            moveTo(Offset(45f, 95f))
            press()
        }
        compose.waitForIdle()
        editor.performMouseInput {
            moveTo(Offset(145f, 95f), delayMillis = 100)
            release()
        }
        val selection = editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertTrue(selection.min > 500, "Selection jumped to the beginning: $selection")
        assertTrue(selection.length in 1..30, "Selection extended outside the dragged text: $selection")
    }

    @Test fun `typing and external replacements stay synchronized`() {
        val draft = mutableStateOf("Original")
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                RuleblendOutlinedTextField(
                    value = draft.value,
                    onValueChange = { draft.value = it },
                    modifier = Modifier.size(400.dp, 200.dp).testTag("editor"),
                )
            }
        }
        val editor = compose.onNodeWithTag("editor")
        editor.performTextReplacement("Edited")
        compose.runOnIdle { assertEquals("Edited", draft.value) }
        compose.runOnIdle { draft.value = "Another rule" }
        editor.assertTextEquals("Another rule")
        editor.performTextInput("!")
        compose.runOnIdle { assertEquals("Another rule!", draft.value) }
    }
}
