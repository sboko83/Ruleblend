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

data class AssistanceState(
    val session: AssistanceSession? = null,
    val confirmation: AssistanceConfirmation? = null,
)

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
    private var disposed = false

    suspend fun open(launch: AssistanceLaunch) = operation {
        check(!disposed) { "Assistance sessions are disposed" }
        val current = mutableState.value.session
        if (current != null && current.launch.assistant.id == launch.assistant.id &&
            current.launch.target.kind == launch.target.kind && current.launch.target.id == launch.target.id) {
            mutableState.value = mutableState.value.copy(confirmation = null)
            current.terminal.focus()
        } else if (current?.terminal?.isAlive == true) {
            mutableState.value = mutableState.value.copy(
                confirmation = AssistanceConfirmation(current, AssistanceAction.Replace(launch)),
            )
        } else {
            release()
            start(launch)
        }
    }

    /** True means the action completed; false means the UI must show the pending confirmation. */
    suspend fun requestClose(exitApplication: Boolean = false): Boolean = operation {
        val current = mutableState.value.session
        if (current?.terminal?.isAlive == true) {
            mutableState.value = mutableState.value.copy(confirmation = AssistanceConfirmation(
                current, if (exitApplication) AssistanceAction.ExitApplication else AssistanceAction.ClosePanel,
            ))
            false
        } else {
            release()
            true
        }
    }

    suspend fun cancel(confirmation: AssistanceConfirmation) = operation {
        if (mutableState.value.confirmation === confirmation) {
            mutableState.value = mutableState.value.copy(confirmation = null)
        }
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
        val terminal = startTerminal(launch)
        val session = AssistanceSession(launch, terminal)
        mutableState.value = AssistanceState(session)
        exitWatcher = scope.launch {
            val code = terminal.waitForExit()
            mutex.withLock {
                val current = mutableState.value
                if (current.session?.terminal === terminal) {
                    mutableState.value = current.copy(session = current.session.copy(exitCode = code))
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
