package dev.ruleblend.app.spike

import dev.ruleblend.app.assistance.AssistanceObject
import dev.ruleblend.app.assistance.AssistanceSessions
import dev.ruleblend.app.assistance.PtyAssistanceTerminal
import dev.ruleblend.app.assistance.ReadyAssistanceAssistant
import dev.ruleblend.app.assistance.assistanceTerminalColors
import dev.ruleblend.app.assistance.prepareClaudeAssistanceLaunch
import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.util.CliProbe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private suspend fun awaitLifecycle(label: String, predicate: () -> Boolean) {
    withTimeout(8_000) { while (!predicate()) delay(20) }
    println("PASS: $label")
}

fun main() = runBlocking {
    System.setProperty("compose.interop.blending", "true")
    val root = Files.createTempDirectory("ruleblend lifecycle space-Юникод-")
    val executable = root.resolve("fake claude")
    val python = requireNotNull(System.getProperty("ruleblend.lifecycle.python"))
    val fixture = Path.of(requireNotNull(System.getProperty("ruleblend.lifecycle.fixture")))
    Files.writeString(executable, "#!$python\n" + Files.readString(fixture).substringAfter('\n'))
    Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    val assistant = ReadyAssistanceAssistant("claude-code", "Offline Claude", CliProbe(executable.toString(), true, System.getenv("PATH")))
    val sessions = AssistanceSessions()
    fun launch(id: String) = prepareClaudeAssistanceLaunch(
        assistant, AssistanceObject(AssistanceObject.Kind.RULE, id, "Commit 'quotes' $(literal)"), Language.RU, root, assistanceTerminalColors(0xFFFFFF, 0x000000),
    )
    fun events() = root.resolve("events.jsonl").let { if (Files.exists(it)) Files.readString(it) else "" }
    fun terminal() = requireNotNull(sessions.state.value.session).terminal as PtyAssistanceTerminal
    fun screen(): String {
        val buffer = terminal().widget.terminalTextBuffer
        buffer.lock()
        return try { buffer.getScreenLines() } finally { buffer.unlock() }
    }
    try {
        val initial = launch("commit")
        sessions.open(initial)
        awaitLifecycle("real PTY launch and rendering") { "READY" in screen() && "\"tty\": true" in events() }
        check("--prefill" in events() && "(ID: commit)" in events())
        check("\"term\": \"xterm-256color\"" in events())
        check("\"inherited\": \"preserved\"" in events())
        check("\"input\"" !in events()) { "Launch wrote to the PTY" }
        val original = terminal()
        sessions.open(initial)
        check(terminal() === original)
        check(!sessions.requestClose())
        sessions.cancel(requireNotNull(sessions.state.value.confirmation))
        check(original.isAlive)
        // Local fixture input, not keyboard/native acceptance and not an AI submission.
        original.widget.ttyConnector.write(byteArrayOf(4))
        awaitLifecycle("natural process exit") { sessions.state.value.session?.exitCode == 0 }
        check(screen().contains("native editable draft")) { "Exited terminal lost its screen" }
        check(sessions.requestClose())
        sessions.open(launch("stubborn"))
        awaitLifecycle("stubborn fixture started") { "(ID: stubborn)" in events() }
        check(!sessions.requestClose(exitApplication = true))
        val running = terminal()
        check(sessions.confirm(requireNotNull(sessions.state.value.confirmation)))
        check(!running.isAlive && sessions.state.value.session == null)
        println("PASS: same-object focus, cancelled close, retained output, approved application exit and forced reaping")
        println("Evidence: $root")
    } finally { sessions.shutdown() }
}
