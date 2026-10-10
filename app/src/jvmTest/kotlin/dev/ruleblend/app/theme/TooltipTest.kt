package dev.ruleblend.app.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

class TooltipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a visible tooltip stays above and right of the pointer and leaves the trigger clickable`() {
        var clicks = 0
        var pointer = Offset.Zero
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.size(600.dp, 400.dp)) {
                    RuleblendTooltip("Settings hint", Modifier.offset(100.dp, 160.dp)) {
                        Box(Modifier.size(120.dp, 48.dp).testTag("trigger")
                            .onGloballyPositioned { pointer = it.boundsInWindow().center }
                            .clickable { clicks++ })
                    }
                }
            }
        }
        compose.onNodeWithTag("trigger").performMouseInput { moveTo(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Settings hint").assertIsDisplayed()
        val tooltip = compose.onNodeWithText("Settings hint").fetchSemanticsNode().boundsInWindow
        assertTrue(tooltip.left > pointer.x, "Tooltip must be right of the pointer: $tooltip vs $pointer")
        assertTrue(tooltip.bottom < pointer.y, "Tooltip must be above the pointer: $tooltip vs $pointer")

        compose.onNodeWithTag("trigger").performMouseInput { press(); release() }
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
