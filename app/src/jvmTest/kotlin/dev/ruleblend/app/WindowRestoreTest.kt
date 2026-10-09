package dev.ruleblend.app

import dev.ruleblend.core.config.WindowGeometry
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowRestoreTest {
    private val primary = Rectangle(0, 0, 1920, 1040)

    @Test fun `disconnected monitor returns the frame to a visible desktop`() {
        assertEquals(WindowGeometry(1000, 700, 920, 200),
            restoreWindow(WindowGeometry(1000, 700, 2300, 200), listOf(primary)))
    }

    @Test fun `negative monitor coordinates are preserved`() {
        val saved = WindowGeometry(1000, 700, -1400, 100)
        assertEquals(saved, restoreWindow(saved, listOf(primary, Rectangle(-1600, 0, 1600, 900))))
    }

    @Test fun `resized desktop fits the frame inside its work area`() {
        assertEquals(WindowGeometry(1200, 760, 0, 40),
            restoreWindow(WindowGeometry(1600, 1000, 300, -50), listOf(Rectangle(0, 40, 1200, 760))))
    }

    @Test fun `tiny desktops keep the layout floor and expose the title bar`() {
        assertEquals(WindowGeometry(900, 600, 0, 30),
            restoreWindow(WindowGeometry(400, 300, 200, 100), listOf(Rectangle(0, 30, 800, 500))))
    }

    @Test fun `missing screen information uses centered placement`() {
        assertEquals(WindowGeometry(1000, 700, null, null),
            restoreWindow(WindowGeometry(1000, 700, 3000, 100), emptyList()))
    }
}
