package dev.ruleblend.app.assistance

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

private class AssistanceConnector(
    private val child: PtyProcess,
    private val spawnedAt: Long,
) : ProcessTtyConnector(child, Charsets.UTF_8) {
    @Volatile private var output = false
    override fun getName(): String = "Ruleblend assistant"
    override fun read(buf: CharArray, offset: Int, length: Int): Int = super.read(buf, offset, length).also { count ->
        if (count > 0 && !output) { output = true; logAssistanceTiming("first output after spawn", spawnedAt) }
    }
    override fun resize(termSize: TermSize) {
        if (child.isAlive) child.winSize = WinSize(termSize.columns, termSize.rows)
    }
}

/** Swing work stays on EDT; creation, wait and termination run on the owner's IO dispatcher. */
class PtyAssistanceTerminal internal constructor(
    private val child: PtyProcess,
    val widget: JediTermWidget,
) : AssistanceTerminal {
    override val component get() = widget
    override val isAlive: Boolean get() = child.isAlive
    override fun waitForExit(): Int = child.waitFor()
    override fun focus() = SwingUtilities.invokeLater { widget.terminalPanel.requestFocusInWindow() }

    override fun close() {
        check(!SwingUtilities.isEventDispatchThread()) { "Process cleanup must run off EDT" }
        stopAssistanceProcess(child)
        SwingUtilities.invokeAndWait { widget.close() }
    }
}

fun startAssistanceTerminal(launch: AssistanceLaunch): PtyAssistanceTerminal {
    check(!SwingUtilities.isEventDispatchThread()) { "PTY launch must run off EDT" }
    val environment = System.getenv().toMutableMap().apply {
        putAll(launch.environmentOverrides)
        put("TERM", "xterm-256color")
    }
    val spawnedAt = System.nanoTime()
    val child = PtyProcessBuilder(launch.command.toTypedArray())
        .setDirectory(launch.directory.toString()).setEnvironment(environment)
        .setInitialColumns(90).setInitialRows(24).setRedirectErrorStream(true).start()
    var widget: JediTermWidget? = null
    try {
        SwingUtilities.invokeAndWait {
            widget = AssistanceTerminalWidget(AssistanceTerminalSettings(launch.colors)).apply {
                createTerminalSession(AssistanceConnector(child, spawnedAt))
                start()
            }
        }
        return PtyAssistanceTerminal(child, requireNotNull(widget))
    } catch (failure: Throwable) {
        runCatching { stopAssistanceProcess(child) }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { SwingUtilities.invokeAndWait { widget?.close() } }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/**
 * The theme values jediterm reads while it builds the widget. `getDefaultStyle()` stays uninherited:
 * it is deprecated, and the widget takes the terminal's default style from [AssistanceTerminalColors.styleState].
 */
private class AssistanceTerminalSettings(
    private val colors: AssistanceTerminalColors,
) : DefaultSettingsProvider() {
    // JediTerm reads these when the widget is created; a later change does not repaint it.
    override fun getBufferMaxLinesCount(): Int = 2_000
    override fun getDefaultForeground() = requireNotNull(colors.defaultStyle().foreground)
    override fun getDefaultBackground() = requireNotNull(colors.defaultStyle().background)
    override fun getSelectionColor() = colors.selectionStyle()
    override fun getTerminalColorPalette() = colors.palette()

    fun defaultStyleState(): StyleState = colors.styleState()
}

/**
 * `JediTerminal` resets its style state on SGR 0, and the inherited default is black-on-white, so the
 * theme style comes from the one hook the widget offers for it.
 */
private class AssistanceTerminalWidget(
    settings: AssistanceTerminalSettings,
) : JediTermWidget(90, 24, settings) {
    // The superclass stores the settings provider before it asks for the style state.
    override fun createDefaultStyle(): StyleState =
        (mySettingsProvider as AssistanceTerminalSettings).defaultStyleState()
}

private fun stopAssistanceProcess(child: PtyProcess) {
    // Pty4J does not implement Process.toHandle(); resolve its native pid instead.
    val descendants = if (child.isAlive) ProcessHandle.of(child.pid()).orElse(null)?.descendants()?.use {
        it.toList().asReversed()
    }.orEmpty() else emptyList()
    descendants.filter { it.isAlive }.forEach { it.destroy() }
    if (child.isAlive) child.destroy()
    if (!child.waitFor(2, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        check(child.waitFor(2, TimeUnit.SECONDS)) { "Assistant process did not stop" }
    }
    descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (descendants.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(20)
    check(descendants.none { it.isAlive }) { "Assistant child processes did not stop" }
    child.waitFor()
}
