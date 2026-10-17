package dev.ruleblend.app.assistance

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.jediterm.core.Color
import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette

/** Terminal colors as plain RGB data, so the PTY start needs nothing from the composition. */
data class AssistanceTerminalColors(
    val dark: Boolean,
    val background: Int,
    val foreground: Int,
    val selection: Int,
    /** ANSI colors 0..15: normal 0..7, then bright 8..15. */
    val ansi: List<Int>,
) {
    init { require(ansi.size == 16) { "A 16-color palette is required" } }
}

// Order: black, red, green, yellow, blue, magenta, cyan, white; then the bright row.
private val LIGHT_ANSI = listOf(
    0x1F2328, 0xB3261E, 0x1A7F37, 0x8A5A00, 0x0550AE, 0x8250DF, 0x0A7B83, 0x57606A,
    0x424A53, 0xD1242F, 0x116329, 0x7A4E00, 0x0969DA, 0x6639BA, 0x1B7C83, 0x24292F,
)
private val DARK_ANSI = listOf(
    0x484F58, 0xFF7B72, 0x56D364, 0xE3B341, 0x79C0FF, 0xD2A8FF, 0x56D4DD, 0xC9D1D9,
    0x6E7681, 0xFFA198, 0x7EE787, 0xF2CC60, 0xA5D6FF, 0xE2C5FF, 0x76E3EA, 0xF0F6FC,
)

/** Takes the app's resolved scheme, so `ThemeMode.SYSTEM` is already light or dark. */
fun assistanceTerminalColors(scheme: ColorScheme): AssistanceTerminalColors =
    assistanceTerminalColors(scheme.surface.toArgb(), scheme.onSurface.toArgb())

fun assistanceTerminalColors(surface: Int, onSurface: Int): AssistanceTerminalColors {
    val dark = luminance(surface) < 0.5
    // The selection is the foreground tinted over the background, so text stays readable on it.
    return AssistanceTerminalColors(
        dark, surface and 0xFFFFFF, onSurface and 0xFFFFFF,
        blend(surface, onSurface, if (dark) 0.28f else 0.20f),
        if (dark) DARK_ANSI else LIGHT_ANSI,
    )
}

private fun channel(rgb: Int, shift: Int) = (rgb shr shift) and 0xFF

private fun luminance(rgb: Int): Double {
    fun lin(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    return 0.2126 * lin(channel(rgb, 16)) + 0.7152 * lin(channel(rgb, 8)) + 0.0722 * lin(channel(rgb, 0))
}

private fun blend(base: Int, top: Int, amount: Float): Int = listOf(16, 8, 0).fold(0) { acc, shift ->
    val mixed = (channel(base, shift) * (1 - amount) + channel(top, shift) * amount).toInt()
    acc or (mixed shl shift)
}

private fun terminalColor(rgb: Int) = TerminalColor(Color(channel(rgb, 16), channel(rgb, 8), channel(rgb, 0)))

internal fun AssistanceTerminalColors.defaultStyle() = TextStyle(terminalColor(foreground), terminalColor(background))

internal fun AssistanceTerminalColors.selectionStyle() = TextStyle(terminalColor(foreground), terminalColor(selection))

// JediTerm resolves the 256-color cube and grays itself; the palette covers only indexes 0..15.
internal fun AssistanceTerminalColors.palette(): ColorPalette = object : ColorPalette() {
    private fun color(index: Int) = ansi[index].let { Color(channel(it, 16), channel(it, 8), channel(it, 0)) }
    override fun getForegroundByColorIndex(colorIndex: Int) = color(colorIndex)
    override fun getBackgroundByColorIndex(colorIndex: Int) = color(colorIndex)
}
