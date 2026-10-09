package dev.ruleblend.app.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The divider must move the pane while the button is held. It used to move only on release from the
 * second drag on: the gesture loop keeps the callbacks it captured when it started, and the pane's
 * live width is a new state object after every persisted width change.
 */
class SplitPaneTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the pane follows the pointer on every drag, not only the first`() {
        var persisted by mutableStateOf(200)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                SplitPane(width = persisted, onWidthChange = { persisted = it }, max = 480) {
                    Box(Modifier.fillMaxSize().testTag("pane"))
                }
            }
        }

        repeat(3) { pass ->
            val before = paneWidth()
            compose.onRoot().performMouseInput {
                moveTo(Offset(before - 3f, 40f))
                press()
                moveBy(Offset(40f, 0f))
            }
            compose.waitForIdle()
            val held = paneWidth()
            compose.onRoot().performMouseInput { release() }
            compose.waitForIdle()

            assertTrue(held > before + 30f, "pass $pass: pane stayed at $held while dragged from $before")
        }
    }

    private fun paneWidth(): Float =
        compose.onNodeWithTag("pane").fetchSemanticsNode().size.width.toFloat()
}
