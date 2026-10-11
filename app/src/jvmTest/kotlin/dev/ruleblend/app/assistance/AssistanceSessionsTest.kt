package dev.ruleblend.app.assistance

import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.util.CliProbe
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AssistanceSessionsTest {
    private fun launch(id: String = "commit", kind: AssistanceObject.Kind = AssistanceObject.Kind.RULE) =
        prepareClaudeAssistanceLaunch(
            ReadyAssistanceAssistant("claude-code", "Claude Code", CliProbe("/fake/claude", true, null)),
            AssistanceObject(kind, id, "Name $id"), Language.EN,
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(),
        )

    private class FakeTerminal : AssistanceTerminal {
        override val component = javax.swing.JPanel()
        private val exited = CountDownLatch(1)
        @Volatile private var code = 0
        var focusCount = 0
        var closeCount = 0
        var failClose = false
        override val isAlive get() = exited.count != 0L
        fun exit(code: Int) { this.code = code; exited.countDown() }
        override fun waitForExit(): Int {
            check(exited.await(10, TimeUnit.SECONDS)) { "Fake process leaked" }
            return code
        }
        override fun focus() { focusCount++ }
        override fun close() {
            if (failClose) error("Cleanup failed")
            closeCount++
            exit(143)
        }
    }

    @Test fun `same saved identity returns focus without restarting even after rename or exit`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            val initial = launch()
            sessions.open(initial)
            sessions.open(initial.copy(target = initial.target.copy(name = "Renamed")))
            assertEquals(1, terminals.size)
            assertEquals(2, terminals.single().focusCount)
            terminals.single().exit(7)
            withTimeout(2_000) { while (sessions.state.value.session?.exitCode == null) delay(5) }
            sessions.open(initial)
            assertEquals(1, terminals.size)
            assertEquals(7, sessions.state.value.session?.exitCode)
            assertEquals(0, terminals.single().closeCount)
            assertTrue(sessions.requestClose())
            assertNull(sessions.state.value.session)
            assertEquals(1, terminals.single().closeCount)
        } finally { sessions.shutdown() }
    }

    @Test fun `replacement waits for approval and cancellation preserves the original process`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            sessions.open(launch())
            // IDs alone are insufficient: a rule and skill can share an ID.
            val replacement = launch(kind = AssistanceObject.Kind.SKILL)
            sessions.open(replacement)
            val cancelled = assertNotNull(sessions.state.value.confirmation)
            assertEquals(1, terminals.size)
            sessions.cancel(cancelled)
            assertFalse(sessions.confirm(cancelled))
            assertTrue(terminals.single().isAlive)
            sessions.open(replacement)
            assertFalse(sessions.confirm(assertNotNull(sessions.state.value.confirmation)))
            assertEquals(2, terminals.size)
            assertFalse(terminals.first().isAlive)
            assertEquals(replacement, sessions.state.value.session?.launch)
            assertFalse(sessions.confirm(cancelled))
            assertTrue(terminals.last().isAlive)
        } finally { sessions.shutdown() }
    }

    @Test fun `application exit uses the same live confirmation and reaps the owned process`() = runBlocking {
        val terminal = FakeTerminal()
        val sessions = AssistanceSessions { terminal }
        try {
            sessions.open(launch())
            assertFalse(sessions.requestClose(exitApplication = true))
            val confirmation = assertNotNull(sessions.state.value.confirmation)
            assertSame(AssistanceAction.ExitApplication, confirmation.action)
            assertTrue(terminal.isAlive)
            assertTrue(sessions.confirm(confirmation))
            assertFalse(terminal.isAlive)
            assertNull(sessions.state.value.session)
        } finally { sessions.shutdown() }
    }

    @Test fun `late old exit and stale close cannot affect the replacement`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            sessions.open(launch())
            assertFalse(sessions.requestClose())
            val oldClose = assertNotNull(sessions.state.value.confirmation)
            sessions.open(launch("another"))
            sessions.confirm(assertNotNull(sessions.state.value.confirmation))
            assertFalse(sessions.confirm(oldClose))
            delay(30)
            assertNull(sessions.state.value.session?.exitCode)
            assertTrue(terminals.last().isAlive)
        } finally { sessions.shutdown() }
    }

    @Test fun `concurrent opens start only one child and shutdown forbids future launch`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            (1..12).map { async { sessions.open(launch()) } }.awaitAll()
            assertEquals(1, terminals.size)
            assertEquals(12, terminals.single().focusCount)
            sessions.shutdown()
            sessions.shutdown()
            assertFalse(terminals.single().isAlive)
            assertEquals(1, terminals.single().closeCount)
            assertFailsWith<IllegalStateException> { sessions.open(launch()) }
            Unit
        } finally { sessions.shutdown() }
    }

    @Test fun `failed launch stays closed and failed cleanup retains ownership`() = runBlocking {
        val terminal = FakeTerminal()
        var failLaunch = true
        val sessions = AssistanceSessions {
            if (failLaunch) error("Executable missing")
            terminal
        }
        try {
            assertFailsWith<IllegalStateException> { sessions.open(launch()) }
            assertNull(sessions.state.value.session)
            failLaunch = false
            sessions.open(launch())
            terminal.failClose = true
            sessions.requestClose(exitApplication = true)
            val pending = assertNotNull(sessions.state.value.confirmation)
            assertFailsWith<IllegalStateException> { sessions.confirm(pending) }
            assertSame(terminal, sessions.state.value.session?.terminal)
            assertSame(pending, sessions.state.value.confirmation)
            terminal.failClose = false
            assertTrue(sessions.confirm(pending))
        } finally { terminal.failClose = false; sessions.shutdown() }
    }
}
