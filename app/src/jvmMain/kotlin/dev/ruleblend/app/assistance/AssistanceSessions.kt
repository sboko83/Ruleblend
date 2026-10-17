package dev.ruleblend.app.assistance

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The terminal keeps its screen after exit; only explicit disposal releases it. */
interface AssistanceTerminal {
    val component: javax.swing.JComponent
    val isAlive: Boolean
    fun waitForExit(): Int
    fun focus()
    fun close()
}

data class AssistanceSession(
    val launch: AssistanceLaunch,
    val terminal: AssistanceTerminal,
    val exitCode: Int? = null,
)

sealed interface AssistanceAction {
    data object ClosePanel : AssistanceAction
    data object ExitApplication : AssistanceAction
    data class Replace(val launch: AssistanceLaunch) : AssistanceAction
}

/** Identity binds approval to the process and action shown in the dialog. */
class AssistanceConfirmation internal constructor(
    val session: AssistanceSession,
    val action: AssistanceAction,
)

/** What a click gets from [AssistanceSessions.begin]. */
sealed interface AssistanceBegin {
    /** A live session or the same target's finished one stays visible; the launch takes the usual path. */
    data object Current : AssistanceBegin
    /** Another launch is still starting; this click must not start a second process. */
    data object Busy : AssistanceBegin
}

/** The panel shown from the click until the session starts; identity ties it to one launch. */
class AssistanceStarting internal constructor(val target: AssistanceObject) : AssistanceBegin

data class AssistanceState(
    val session: AssistanceSession? = null,
    val confirmation: AssistanceConfirmation? = null,
    val starting: AssistanceStarting? = null,
)

internal fun logAssistanceTiming(step: String, startedAt: Long) =
    System.err.println("ruleblend assistance: $step ${(System.nanoTime() - startedAt) / 1_000_000} ms")

val LocalAssistanceSessions = staticCompositionLocalOf<AssistanceSessions?> { null }

/** One application-owned session. Navigation does not own or dispose this controller. */
class AssistanceSessions(
    private val startTerminal: (AssistanceLaunch) -> AssistanceTerminal = ::startAssistanceTerminal,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AssistanceState())
    val state = mutableState.asStateFlow()
    private var exitWatcher: Job? = null
    @Volatile private var disposed = false

    /**
     * Not suspending: the click shows the starting panel before the assistant refresh, which runs
     * ahead of [open] and would otherwise keep the panel waiting for the slow part.
     */
    fun begin(target: AssistanceObject): AssistanceBegin {
        while (true) {
            val current = mutableState.value
            if (current.starting != null) return AssistanceBegin.Busy
            val session = current.session
            if (disposed || session?.terminal?.isAlive == true ||
                session != null && session.launch.target.kind == target.kind && session.launch.target.id == target.id) {
                return AssistanceBegin.Current
            }
            val starting = AssistanceStarting(target)
            if (mutableState.compareAndSet(current, current.copy(starting = starting))) return starting
        }
    }

    /** Ends a launch that stops short of a session: configure, an assistant choice or a failure. */
    fun end(starting: AssistanceStarting) =
        mutableState.update { if (it.starting === starting) it.copy(starting = null) else it }

    /** The session, the focus or the replace confirmation supersedes the starting panel. */
    suspend fun open(launch: AssistanceLaunch) = operation {
        try {
            check(!disposed) { "Assistance sessions are disposed" }
            val current = mutableState.value.session
            if (current != null && current.launch.assistant.id == launch.assistant.id &&
                current.launch.target.kind == launch.target.kind && current.launch.target.id == launch.target.id) {
                mutableState.update { it.copy(confirmation = null) }
                current.terminal.focus()
            } else if (current?.terminal?.isAlive == true) {
                mutableState.update {
                    it.copy(confirmation = AssistanceConfirmation(current, AssistanceAction.Replace(launch)))
                }
            } else {
                release()
                start(launch)
            }
        } finally {
            mutableState.update { it.copy(starting = null) }
        }
    }

    /** True means the action completed; false means the UI must show the pending confirmation. */
    suspend fun requestClose(exitApplication: Boolean = false): Boolean = operation {
        val current = mutableState.value.session
        if (current?.terminal?.isAlive == true) {
            mutableState.update { it.copy(confirmation = AssistanceConfirmation(
                current, if (exitApplication) AssistanceAction.ExitApplication else AssistanceAction.ClosePanel,
            )) }
            false
        } else {
            release()
            true
        }
    }

    suspend fun cancel(confirmation: AssistanceConfirmation) = operation {
        mutableState.update { if (it.confirmation === confirmation) it.copy(confirmation = null) else it }
    }

    /** True only for an approved application exit. Stale dialogs cannot stop a newer session. */
    suspend fun confirm(confirmation: AssistanceConfirmation): Boolean = operation {
        if (mutableState.value.confirmation !== confirmation) return@operation false
        release()
        when (val action = confirmation.action) {
            AssistanceAction.ExitApplication -> true
            AssistanceAction.ClosePanel -> false
            is AssistanceAction.Replace -> { start(action.launch); false }
        }
    }

    /** Final owner teardown, after normal close approval or when the application is disposed. */
    suspend fun shutdown() = operation {
        release()
        disposed = true
        scope.cancel()
    }

    // Cancellation must not strand a just-created PTY or interrupt process reaping.
    private suspend fun <T> operation(block: () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun start(launch: AssistanceLaunch) {
        val startedAt = System.nanoTime()
        val terminal = startTerminal(launch)
        logAssistanceTiming("terminal start", startedAt)
        val session = AssistanceSession(launch, terminal)
        mutableState.value = AssistanceState(session)
        exitWatcher = scope.launch {
            val code = terminal.waitForExit()
            mutex.withLock {
                mutableState.update { current ->
                    if (current.session?.terminal !== terminal) current
                    else current.copy(session = current.session.copy(exitCode = code))
                }
            }
        }
        terminal.focus()
    }

    private fun release() {
        // Keep ownership if cleanup fails: the caller must not exit or start a second child.
        mutableState.value.session?.terminal?.close()
        exitWatcher?.cancel()
        exitWatcher = null
        mutableState.value = AssistanceState()
    }
}
