package dev.ruleblend.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.foundation.layout.Box
import dev.ruleblend.core.config.ThemeMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The reset of a field sits on the same margin as the text it clears. Material's trailing slot does
 * not: it reserves a 48 dp square and centres the icon in it, which parks the mark three times the
 * field's own padding away from the border and reads as a stray glyph rather than as this field's
 * action. [RuleblendOutlinedTextField] lays the action out itself; this is what pins that.
 */
class FieldClearButtonTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the clear mark sits on the field's own end padding`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                RuleblendOutlinedTextField(
                    value = "ledger",
                    onValueChange = {},
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    placeholder = { Text("Filter") },
                    trailingIcon = { FieldClearButton("Clear") {} },
                    modifier = Modifier.fillMaxWidth().testTag("field"),
                )
            }
        }

        val field = compose.onNodeWithTag("field").getUnclippedBoundsInRoot()
        val clear = compose.onNodeWithContentDescription("Clear").getUnclippedBoundsInRoot()
        val inset = field.right - clear.right
        // The end half of RuleblendFieldContentPadding — the margin the text starts from.
        val expected = 8.dp
        assertTrue(
            abs((inset - expected).value) <= 1f,
            "clear mark sits $inset from the field's end, expected $expected",
        )
        assertTrue(clear.width <= 20.dp, "clear mark is ${clear.width} wide, expected a compact hit box")
    }

    /**
     * A "\u00d7" glyph is centred by its line box, not by its ink: the font's ascent and descent put
     * the mark about 2 dp below the middle of a 16 dp hit box, which reads as a misaligned control
     * beside a 36 dp field. The mark is drawn instead, and this measures the ink that reaches the
     * screen rather than the box that holds it.
     */
    @Test
    fun `the clear mark's ink is centred in its hit box`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.testTag("mark").background(Color.White).padding(4.dp)) {
                    FieldClearButton("Clear") {}
                }
            }
        }

        val image = compose.onNodeWithTag("mark").captureToImage().toAwtImage()
        val inked = (0 until image.height).filter { y ->
            (0 until image.width).any { x -> (image.getRGB(x, y) and 0xffffff) != 0xffffff }
        }
        assertTrue(inked.isNotEmpty(), "the clear mark drew nothing")
        val inkCentre = (inked.first() + inked.last()) / 2.0
        val boxCentre = image.height / 2.0 - 0.5
        assertTrue(
            abs(inkCentre - boxCentre) <= 1.0,
            "clear mark ink spans rows ${inked.first()}..${inked.last()}, centre $inkCentre, box centre $boxCentre",
        )
    }
}
