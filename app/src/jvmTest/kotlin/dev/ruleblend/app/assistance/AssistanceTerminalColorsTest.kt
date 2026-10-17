package dev.ruleblend.app.assistance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Shared by the assistance tests that build a launch and do not care about colors. */
internal val testColors = assistanceTerminalColors(0xFFFFFF, 0x1C1B1F)

class AssistanceTerminalColorsTest {
    private fun contrast(a: Int, b: Int): Double {
        fun lum(rgb: Int): Double {
            fun lin(shift: Int) = ((rgb shr shift) and 0xFF) / 255.0
            fun f(c: Double) = if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            return 0.2126 * f(lin(16)) + 0.7152 * f(lin(8)) + 0.0722 * f(lin(0))
        }
        val (hi, lo) = listOf(lum(a), lum(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun `light surface gives the light palette with the app colors`() {
        val colors = assistanceTerminalColors(0xFFFBFE, 0x1C1B1F)
        assertFalse(colors.dark)
        assertEquals(0xFFFBFE, colors.background)
        assertEquals(0x1C1B1F, colors.foreground)
        assertTrue(colors.ansi.all { contrast(it, colors.background) >= 3.0 }, "ANSI colors unreadable on light")
        assertTrue(contrast(colors.foreground, colors.selection) >= 4.5)
    }

    @Test fun `dark surface gives the dark palette with the app colors`() {
        val colors = assistanceTerminalColors(0x141218, 0xE6E0E9)
        assertTrue(colors.dark)
        assertEquals(0x141218, colors.background)
        assertEquals(0xE6E0E9, colors.foreground)
        assertTrue(colors.ansi.drop(1).all { contrast(it, colors.background) >= 3.0 }, "ANSI colors unreadable on dark")
        assertTrue(contrast(colors.foreground, colors.selection) >= 4.5)
    }

    @Test fun `alpha is dropped and selection differs from the background`() {
        val colors = assistanceTerminalColors(0xFF141218.toInt(), 0xFFE6E0E9.toInt())
        assertEquals(0x141218, colors.background)
        assertNotEquals(colors.background, colors.selection)
        assertEquals(16, colors.ansi.size)
    }

    @Test fun `palette serves the 16 ANSI indexes`() {
        val colors = assistanceTerminalColors(0x141218, 0xE6E0E9)
        val palette = colors.palette()
        val red = palette.getForeground(com.jediterm.terminal.TerminalColor.index(1))
        assertEquals(colors.ansi[1], (red.red shl 16) or (red.green shl 8) or red.blue)
    }
}
