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
import kotlin.test.assertIs
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
            Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath(), testColors,
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

    @Test fun `project launches share the single session and replace only after approval`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
        fun project(name: String) = prepareClaudeAssistanceLaunch(
            ReadyAssistanceAssistant("claude-code", "Claude Code", CliProbe("/fake/claude", true, null)),
            AssistanceObject.fromProject(root.resolve(name)), Language.EN, root.resolve(name), testColors,
        )
        try {
            sessions.open(project("alpha"))
            sessions.open(project("alpha"))
            assertEquals(1, terminals.size)
            assertEquals(null, sessions.state.value.confirmation)
            sessions.open(project("beta"))
            val pending = assertNotNull(sessions.state.value.confirmation)
            assertEquals(1, terminals.size)
            assertTrue(terminals.single().isAlive)
            assertFalse(sessions.confirm(pending))
            assertEquals(2, terminals.size)
            assertFalse(terminals.first().isAlive)
            assertEquals("beta", sessions.state.value.session?.launch?.target?.name)
        } finally { sessions.shutdown() }
    }

    @Test fun `project help and profile picking are separate sessions`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        val root = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("alpha")
        fun launch(kind: AssistanceObject.Kind) = prepareClaudeAssistanceLaunch(
            ReadyAssistanceAssistant("claude-code", "Claude Code", CliProbe("/fake/claude", true, null)),
            AssistanceObject.fromProject(root, kind), Language.EN, root, testColors,
        )
        try {
            sessions.open(launch(AssistanceObject.Kind.PROJECT))
            sessions.open(launch(AssistanceObject.Kind.PROJECT))
            assertEquals(1, terminals.size)
            assertEquals(null, sessions.state.value.confirmation)
            sessions.open(launch(AssistanceObject.Kind.PROFILE))
            val pending = assertNotNull(sessions.state.value.confirmation)
            assertEquals(1, terminals.size)
            assertTrue(terminals.single().isAlive)
            assertFalse(sessions.confirm(pending))
            assertEquals(2, terminals.size)
            assertFalse(terminals.first().isAlive)
            assertEquals(AssistanceObject.Kind.PROFILE, sessions.state.value.session?.launch?.target?.kind)
        } finally { sessions.shutdown() }
    }

    @Test fun `starting panel appears on the click and the session replaces it`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            val initial = launch()
            val starting = assertIs<AssistanceStarting>(sessions.begin(initial.target))
            assertSame(starting, sessions.state.value.starting)
            // A second click while starting must not start a second process.
            assertSame(AssistanceBegin.Busy, sessions.begin(launch("another").target))
            var handedOff: ReadyAssistanceAssistant? = null
            launchAssistance(sessions, starting, select = { AssistanceSelection.Launch(initial.assistant) },
                onConfigure = { error("No configure") }, onLaunch = { handedOff = it },
                onChoose = { error("No choice") }, onFailure = { error(it) })
            assertEquals(initial.assistant, handedOff)
            assertSame(starting, sessions.state.value.starting)
            sessions.open(initial)
            assertNull(sessions.state.value.starting)
            assertSame(terminals.single(), sessions.state.value.session?.terminal)
        } finally { sessions.shutdown() }
    }

    @Test fun `starting panel clears on configure, a choice and failure without a session`() = runBlocking {
        val sessions = AssistanceSessions { FakeTerminal() }
        try {
            val target = launch().target
            var configured = false
            launchAssistance(sessions, assertIs<AssistanceStarting>(sessions.begin(target)),
                select = { AssistanceSelection.Configure }, onConfigure = { configured = true },
                onLaunch = { error("No launch") }, onChoose = { error("No choice") }, onFailure = { error(it) })
            assertTrue(configured)
            assertEquals(AssistanceState(), sessions.state.value)
            var choices = emptyList<ReadyAssistanceAssistant>()
            launchAssistance(sessions, assertIs<AssistanceStarting>(sessions.begin(target)),
                select = { AssistanceSelection.Choose(listOf(launch().assistant)) }, onConfigure = { error("No configure") },
                onLaunch = { error("No launch") }, onChoose = { choices = it }, onFailure = { error(it) })
            assertEquals(1, choices.size)
            assertEquals(AssistanceState(), sessions.state.value)
            var failure: String? = null
            launchAssistance(sessions, assertIs<AssistanceStarting>(sessions.begin(target)),
                select = { error("Refresh failed") }, onConfigure = { error("No configure") },
                onLaunch = { error("No launch") }, onChoose = { error("No choice") }, onFailure = { failure = it })
            assertEquals("Refresh failed", failure)
            assertEquals(AssistanceState(), sessions.state.value)
            // A refused launch reports through the same failure path.
            launchAssistance(sessions, assertIs<AssistanceStarting>(sessions.begin(target)),
                select = { AssistanceSelection.Launch(launch().assistant) }, onConfigure = { error("No configure") },
                onLaunch = { error("Launch refused") }, onChoose = { error("No choice") }, onFailure = { failure = it })
            assertEquals("Launch refused", failure)
            assertEquals(AssistanceState(), sessions.state.value)
            // A stale ending cannot clear a newer launch's panel.
            val stale = assertIs<AssistanceStarting>(sessions.begin(target))
            sessions.end(stale)
            val newer = assertIs<AssistanceStarting>(sessions.begin(target))
            sessions.end(stale)
            assertSame(newer, sessions.state.value.starting)
        } finally { sessions.shutdown() }
    }

    @Test fun `failed process start clears the starting panel`() = runBlocking {
        val sessions = AssistanceSessions { error("Executable missing") }
        try {
            val initial = launch()
            assertIs<AssistanceStarting>(sessions.begin(initial.target))
            assertFailsWith<IllegalStateException> { sessions.open(initial) }
            assertEquals(AssistanceState(), sessions.state.value)
        } finally { sessions.shutdown() }
    }

    @Test fun `live session keeps its terminal and asks to replace without a starting panel`() = runBlocking {
        val terminals = mutableListOf<FakeTerminal>()
        val sessions = AssistanceSessions { FakeTerminal().also(terminals::add) }
        try {
            val initial = launch()
            sessions.open(initial)
            val replacement = launch("another")
            assertSame(AssistanceBegin.Current, sessions.begin(replacement.target))
            assertNull(sessions.state.value.starting)
            sessions.open(replacement)
            assertNotNull(sessions.state.value.confirmation)
            assertSame(terminals.single(), sessions.state.value.session?.terminal)
            assertNull(sessions.state.value.starting)
            sessions.cancel(assertNotNull(sessions.state.value.confirmation))
            // A finished session of the same target is refocused, not restarted behind a panel.
            terminals.single().exit(0)
            withTimeout(2_000) { while (sessions.state.value.session?.exitCode == null) delay(5) }
            assertSame(AssistanceBegin.Current, sessions.begin(initial.target))
            assertIs<AssistanceStarting>(sessions.begin(replacement.target))
            Unit
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
