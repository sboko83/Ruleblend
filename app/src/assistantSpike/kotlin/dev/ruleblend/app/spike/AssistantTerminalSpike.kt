package dev.ruleblend.app.spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.io.path.readText

private const val DRAFT = "Обсудим правило Commit.\nСначала уточним требования."

private class FixtureConnector(private val child: PtyProcess) : ProcessTtyConnector(child, Charsets.UTF_8) {
    override fun getName(): String = "Ruleblend offline fixture"
    override fun resize(termSize: TermSize) {
        if (child.isAlive) child.winSize = WinSize(termSize.columns, termSize.rows)
    }
}

private class TerminalFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("ruleblend terminal-Юникод-")
    val events: Path = root.resolve("events.jsonl")
    val child: PtyProcess
    val connector: FixtureConnector
    val widget: JediTermWidget

    init {
        val command = arrayOf(
            System.getProperty("ruleblend.spike.python", "python3"),
            requireNotNull(System.getProperty("ruleblend.spike.fixture")), events.toString(),
        )
        child = PtyProcessBuilder(command)
            .setDirectory(root.toString())
            .setEnvironment(mapOf("PATH" to "/usr/bin:/bin:/opt/homebrew/bin", "TERM" to "xterm-256color", "LANG" to "en_US.UTF-8"))
            .setInitialColumns(90).setInitialRows(24).setRedirectErrorStream(true).start()
        connector = FixtureConnector(child)
        var created: JediTermWidget? = null
        SwingUtilities.invokeAndWait {
            created = JediTermWidget(90, 24, object : DefaultSettingsProvider() {
                override fun getBufferMaxLinesCount(): Int = 500
            }).apply { createTerminalSession(connector); start() }
        }
        widget = requireNotNull(created)
    }

    fun eventText(): String = if (Files.exists(events)) events.readText() else ""

    fun screen(): String {
        val buffer = widget.terminalTextBuffer
        buffer.lock()
        return try { buffer.getScreenLines() } finally { buffer.unlock() }
    }

    fun paste() = connector.write("\u001b[200~$DRAFT\u001b[201~")

    fun focus() = SwingUtilities.invokeLater {
        widget.terminalPanel.requestFocusInWindow()
    }

    override fun close() {
        if (child.isAlive) {
            runCatching { connector.write(byteArrayOf(4)) }
            if (!child.waitFor(2, TimeUnit.SECONDS)) {
                child.destroy()
                if (!child.waitFor(2, TimeUnit.SECONDS)) {
                    child.destroyForcibly()
                    check(child.waitFor(2, TimeUnit.SECONDS)) { "Fixture did not exit" }
                }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) widget.close()
        else SwingUtilities.invokeAndWait { widget.close() }
        check(!child.isAlive) { "Fixture child leaked" }
    }
}

private fun awaitCondition(label: String, predicate: () -> Boolean) {
    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
    while (!predicate()) {
        check(System.nanoTime() < end) { "Timed out: $label" }
        Thread.sleep(20)
    }
}

private fun runChecks() {
    TerminalFixture().use { fixture ->
        awaitCondition("TTY and emulator") { "READY" in fixture.screen() }
        check("\"tty\": true" in fixture.eventText())
        val unicodeScreen = fixture.screen()
        // Wide CJK cells have emulator padding between glyphs.
        check("Привет" in unicodeScreen && "日本語".all { it in unicodeScreen } && "😀" in unicodeScreen) {
            "Unicode rendering failed: $unicodeScreen"
        }
        fixture.paste()
        awaitCondition("Unicode multiline paste") { "\"event\": \"paste\"" in fixture.eventText() }
        check("Обсудим правило Commit.\\nСначала уточним требования." in fixture.eventText())
        check("\"event\": \"submit\"" !in fixture.eventText()) { "Paste submitted a message" }
        fixture.connector.resize(TermSize(72, 18))
        awaitCondition("PTY resize") { "\"columns\": 72, \"rows\": 18" in fixture.eventText() }
        fixture.connector.write(byteArrayOf(3))
        awaitCondition("Ctrl+C") { "\"event\": \"interrupt\"" in fixture.eventText() }
        fixture.connector.write(byteArrayOf(12))
        awaitCondition("bounded output") { "\"event\": \"flood\"" in fixture.eventText() }
        awaitCondition("rendered output") { "output 2999" in fixture.screen() }
        check(fixture.widget.terminalTextBuffer.historyLinesCount <= 500)
        fixture.connector.write(byteArrayOf(4))
        check(fixture.child.waitFor(3, TimeUnit.SECONDS)) { "EOF did not stop the fixture" }
        check(fixture.child.exitValue() == 0)
        println("PASS: PTY, UTF-8 emulator, multiline paste without submit, resize, Ctrl+C, output bound, EOF")
        println("Evidence: ${fixture.root}")
    }
    println("PASS: terminal workers disposed; child process reaped")
}

fun main(args: Array<String>) {
    // Without blending Swing obscures Compose menus and confirmation dialogs.
    System.setProperty("compose.interop.blending", "true")
    if ("--check" in args) {
        try { runChecks() } catch (failure: Throwable) {
            failure.printStackTrace()
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }
    // This separate harness is deliberately not the product UI or a model-backed session.
    val fixture = TerminalFixture()
    application {
        var confirm by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var height by remember { mutableStateOf(330f) }
        fun closeSession() {
            if (fixture.child.isAlive) confirm = true else exitApplication()
        }
        Window(onCloseRequest = ::closeSession, title = "Ruleblend terminal spike — offline fixture") {
            MaterialTheme {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Offline feasibility harness · no AI requests")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            fixture.paste()
                            fixture.focus()
                        }) { Text("Paste draft") }
                        Button(onClick = { menu = true }) { Text("Overlay menu") }
                        Button(onClick = ::closeSession) { Text("Close session") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Compose over terminal") }, onClick = { menu = false })
                        }
                    }
                    Slider(value = height, onValueChange = { height = it }, valueRange = 160f..600f)
                    SwingPanel(factory = { fixture.widget }, modifier = Modifier.fillMaxWidth().height(height.dp))
                }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Close offline session?") },
                    text = { Text("The fixture process will stop.") },
                    confirmButton = { TextButton(onClick = { exitApplication() }) { Text("Close") } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
                DisposableEffect(Unit) { onDispose { fixture.close() } }
            }
        }
    }
}
