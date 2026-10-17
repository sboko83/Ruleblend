package dev.ruleblend.app.assistance

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.theme.DialogCloseButton
import java.awt.Cursor

/** Deterministic UI tests render an offline terminal view without native interop. */
internal val LocalAssistanceTerminalView = staticCompositionLocalOf<(@Composable (AssistanceTerminal, Modifier) -> Unit)?> { null }

/** The shell keeps one dock below the current route, so navigation never recreates the PTY. */
@Composable
fun AssistanceLayout(
    session: AssistanceSession?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown from the click until [session] starts; it hides an older finished session meanwhile. */
    starting: AssistanceStarting? = null,
    content: @Composable () -> Unit,
) {
    var requestedHeight by remember { mutableStateOf(300f) }
    val density = LocalDensity.current
    val strings = LocalStrings.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val maximum = (maxHeight.value - 120f).coerceAtLeast(100f)
        val height = requestedHeight.coerceIn(100f, maximum)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            if (session != null || starting != null) {
                Box(Modifier.fillMaxWidth().height(6.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag("assistance-resize")
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
                    .pointerInput(maximum, density) {
                        detectVerticalDragGestures { change, amount ->
                            change.consume()
                            requestedHeight = (requestedHeight.coerceIn(100f, maximum) - amount / density.density).coerceIn(100f, maximum)
                        }
                    })
                Column(Modifier.fillMaxWidth().height(height.dp).testTag("assistance-panel")) {
                    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val title = starting?.target?.name
                            ?: session?.let { "${it.launch.target.name} · ${it.launch.assistant.name}" }.orEmpty()
                        Text(title,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        // Nothing to close yet: the launch ends on its own with a session or a failure.
                        if (starting == null) DialogCloseButton(strings.actionClose, onClose, Modifier.testTag("assistance-close"))
                    }
                    if (session == null || starting != null) {
                        Column(Modifier.fillMaxWidth().weight(1f).testTag("assistance-starting"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Text(strings.assistanceStarting, style = MaterialTheme.typography.bodyMedium)
                        }
                        return@Column
                    }
                    val terminal = session.terminal
                    val terminalModifier = Modifier.fillMaxWidth().weight(1f).testTag("assistance-terminal")
                    val testView = LocalAssistanceTerminalView.current
                    if (testView != null) testView(terminal, terminalModifier)
                    else SwingPanel(factory = { terminal.component }, modifier = terminalModifier)
                    LaunchedEffect(terminal) { terminal.focus() }
                }
            }
        }
    }
}
