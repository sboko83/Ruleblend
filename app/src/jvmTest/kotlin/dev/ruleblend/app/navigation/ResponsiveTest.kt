package dev.ruleblend.app.navigation

import androidx.compose.ui.unit.dp
import dev.ruleblend.app.MinWindowWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponsiveTest {
    // The rail is outside the surface, so a Place or Library column layout gets the window minus it.
    private val rail = 52.dp
    private val placeList = 240.dp
    private val palette = 300.dp

    @Test
    fun `the minimum window still shows all three columns at their asked widths`() {
        val fit = fitPanes(available = 960.dp - rail, leading = placeList, trailing = palette)
        assertEquals(PaneFit(placeList, palette), fit)
    }

    @Test
    fun `the window cannot be dragged below a layout that still reads`() {
        // The rail is real here: the floor is a property of the window, not of the surface inside it.
        val available = MinWindowWidth - 92.dp
        val fit = fitPanes(available, leading = placeList, trailing = palette)
        val leading = requireNotNull(fit.leading, { "the place list was dropped at the minimum window" })
        val trailing = requireNotNull(fit.trailing, { "the palette was dropped at the minimum window" })
        assertTrue(leading >= PaneMinWidth && trailing >= PaneMinWidth, "a pane fell under its minimum")
        assertTrue(available - leading - trailing >= CentreMinWidth, "the centre lost its minimum")
    }

    @Test
    fun `a narrow window shrinks both panes instead of dropping one`() {
        val available = 800.dp - rail
        val fit = fitPanes(available, leading = placeList, trailing = palette)
        val leading = requireNotNull(fit.leading)
        val trailing = requireNotNull(fit.trailing)
        assertTrue(leading < placeList && leading >= PaneMinWidth, "leading was $leading")
        assertTrue(trailing < palette && trailing >= PaneMinWidth, "trailing was $trailing")
        assertTrue(available - leading - trailing >= CentreMinWidth, "the centre lost its minimum")
    }

    @Test
    fun `when only one pane fits the leading one goes`() {
        val fit = fitPanes(available = 560.dp, leading = placeList, trailing = palette)
        assertNull(fit.leading)
        assertEquals(200.dp, fit.trailing)
    }

    @Test
    fun `a window with no room for a pane keeps the centre alone`() {
        assertEquals(PaneFit(null, null), fitPanes(available = 420.dp, leading = placeList, trailing = palette))
    }

    @Test
    fun `a surface asking for one pane is not made to share`() {
        assertEquals(PaneFit(placeList, null), fitPanes(available = 900.dp, leading = placeList, trailing = 0.dp))
        assertEquals(PaneFit(null, palette), fitPanes(available = 900.dp, leading = 0.dp, trailing = palette))
        assertEquals(PaneFit(null, null), fitPanes(available = 900.dp, leading = 0.dp, trailing = 0.dp))
    }

    @Test
    fun `panes are never widened past what the surface asked for`() {
        val fit = fitPanes(available = 2400.dp, leading = placeList, trailing = palette)
        assertEquals(PaneFit(placeList, palette), fit)
    }

    @Test
    fun `a drag cannot push the centre out of the window`() {
        assertEquals(548.dp - palette, paneMaxWidth(available = 960.dp - rail, other = palette))
        // Even in a window that fits nothing, the handle reports the pane's own minimum, not a
        // negative width the caller would have to guard against.
        assertEquals(PaneMinWidth, paneMaxWidth(available = 400.dp, other = palette))
    }
}
