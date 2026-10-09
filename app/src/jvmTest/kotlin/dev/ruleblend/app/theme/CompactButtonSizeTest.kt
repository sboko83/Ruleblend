package dev.ruleblend.app.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The `design2` contract for a button is `padding: 4px 10px` around a 12px label — a control that
 * hugs its text. Material's own `Button` disagrees twice over: a 58 x 40 dp minimum on the content
 * row and a 48 dp minimum interactive size on the surface, neither of which `contentPadding` can
 * shrink. These bounds are what keeps the compact buttons compact.
 */
class CompactButtonSizeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `compact buttons hug their label`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                Column {
                    CompactButton(onClick = {}, modifier = Modifier.testTag("filled")) { Text("Ok") }
                    CompactOutlinedButton(onClick = {}, modifier = Modifier.testTag("outlined")) { Text("No") }
                }
            }
        }

        listOf("filled", "outlined").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.height <= 26.dp, "$tag height ${bounds.height} exceeds 26.dp")
            assertTrue(bounds.width <= 44.dp, "$tag width ${bounds.width} exceeds 44.dp")
        }
    }

    /**
     * The mockups' only full-round element, `.pill`, is `padding: 4px 12px` — bigger than the
     * chip-shaped controls used to be, not smaller. This pins the padding so a future "make it
     * compact" pass does not shrink it back below the design.
     */
    @Test
    fun `pill controls match the design padding`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                Column {
                    CompactPillButton(onClick = {}, modifier = Modifier.testTag("pillButton")) {
                        Text("Ok", style = MaterialTheme.typography.labelMedium)
                    }
                    ToggleChip(label = "Ok", on = false, onClick = {}, modifier = Modifier.testTag("toggleChip"))
                }
            }
        }

        listOf("pillButton", "toggleChip").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.height in 24.dp..28.dp, "$tag height ${bounds.height} outside [24.dp, 28.dp]")
            assertTrue(bounds.width in 36.dp..44.dp, "$tag width ${bounds.width} outside [36.dp, 44.dp]")
        }
    }
}
