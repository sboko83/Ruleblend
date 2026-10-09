package dev.ruleblend.app

import dev.ruleblend.core.config.WindowGeometry
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit

/** AWT screen bounds and Compose window positions use logical desktop coordinates. */
internal fun usableScreens(): List<Rectangle> = runCatching {
    GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        val config = device.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        Rectangle(config.bounds).apply {
            x += insets.left
            y += insets.top
            width -= insets.left + insets.right
            height -= insets.top + insets.bottom
        }
    }
}.getOrDefault(emptyList())

/** Keep the frame on its surviving monitor; recover disconnected or resized displays. */
internal fun restoreWindow(saved: WindowGeometry, screens: List<Rectangle>): WindowGeometry {
    val width = saved.width.coerceAtLeast(MinWindowWidth.value.toInt())
    val height = saved.height.coerceAtLeast(MinWindowHeight.value.toInt())
    val frame = Rectangle(saved.x ?: 0, saved.y ?: 0, width, height)
    val screen = screens.filter { it.width > 0 && it.height > 0 }.maxByOrNull {
        val intersection = it.intersection(frame)
        intersection.width.coerceAtLeast(0).toLong() * intersection.height.coerceAtLeast(0)
    } ?: return saved.copy(width = width, height = height, x = null, y = null)
    // On small logical desktops keep the layout floor, but always expose the title bar.
    val fittedWidth = width.coerceAtMost(screen.width).coerceAtLeast(MinWindowWidth.value.toInt())
    val fittedHeight = height.coerceAtMost(screen.height).coerceAtLeast(MinWindowHeight.value.toInt())
    val x = (saved.x ?: screen.x).coerceIn(screen.x, screen.x + (screen.width - fittedWidth).coerceAtLeast(0))
    val y = (saved.y ?: screen.y).coerceIn(screen.y, screen.y + (screen.height - fittedHeight).coerceAtLeast(0))
    return WindowGeometry(fittedWidth, fittedHeight, x, y)
}
