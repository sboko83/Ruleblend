package dev.ruleblend.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ruleblend.core.config.ThemeMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The triangle must sit on the same line as the label it opens. It used to be a glyph, whose ink
 * sits below the middle of its line box, so every collapsible header in the app carried an arrow a
 * pixel low and a row taller than its text.
 */
class DisclosureArrowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the triangle is centred on its label in both states`() {
        listOf(true, false).forEach { open ->
            compose.setContent {
                RuleblendTheme(ThemeMode.LIGHT) {
                    Box(Modifier.background(Color.White).padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DisclosureArrow(open, modifier = Modifier.testTag("arrow"), color = Color.Black)
                            Text("AGENTS", style = SectionHeaderStyle, modifier = Modifier.testTag("label"))
                        }
                    }
                }
            }
            compose.waitForIdle()

            val arrow = inkCentre("arrow")
            val label = inkCentre("label")
            assertTrue(
                abs(arrow.first - arrow.second) <= 0.5f,
                "open=$open: the triangle sits at ${arrow.first} in a node centred on ${arrow.second}",
            )
            assertTrue(
                abs(arrow.first - label.first) <= 1f,
                "open=$open: the triangle sits at ${arrow.first}, its label at ${label.first}",
            )
        }
    }

    /**
     * Vertical centre of the node's ink and of the node itself, in the row's own pixels. The
     * threshold is generous on purpose: a triangle's apex and a glyph's stem ends are antialiased,
     * and dropping them would bias the measured centre away from the shape's real one.
     */
    private fun inkCentre(tag: String): Pair<Float, Float> {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val image = compose.onNodeWithTag(tag).captureToImage().toAwtImage()
        var top = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val lum = ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
                if (lum < 240) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
        }
        val offset = node.positionInRoot.y
        return (offset + (top + bottom) / 2f) to (offset + image.height / 2f)
    }
}
