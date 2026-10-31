package dev.ruleblend.app.assistance

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.model.StyleState
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JediTerminal resets its style state on SGR 0 and then paints with the default style the widget
 * built, so the theme colors have to come out of [AssistanceTerminalWidget.createDefaultStyle].
 * Without that override the widget keeps JediTerm's own black-on-white and the text loses the theme
 * after the first reset; this reads the widget's own state, not the style helper.
 */
class AssistanceTerminalWidgetTest {
    private fun styleAfterReset(background: Int, foreground: Int): StyleState {
        val settings = AssistanceTerminalSettings(assistanceTerminalColors(background, foreground))
        val widget = AtomicReference<AssistanceTerminalWidget>()
        SwingUtilities.invokeAndWait { widget.set(AssistanceTerminalWidget(settings)) }

        return widget.get().terminal.styleState.apply { reset() }
    }

    @Test fun `the widget default style is the light theme after an SGR reset`() {
        val style = styleAfterReset(background = 0xFFFBFE, foreground = 0x1C1B1F)

        assertEquals(TerminalColor.rgb(0x1C, 0x1B, 0x1F), style.current.foreground)
        assertEquals(TerminalColor.rgb(0xFF, 0xFB, 0xFE), style.current.background)
    }

    @Test fun `the widget default style is the dark theme after an SGR reset`() {
        val style = styleAfterReset(background = 0x141218, foreground = 0xE6E0E9)

        assertEquals(TerminalColor.rgb(0xE6, 0xE0, 0xE9), style.current.foreground)
        assertEquals(TerminalColor.rgb(0x14, 0x12, 0x18), style.current.background)
    }
}
