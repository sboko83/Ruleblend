package dev.ruleblend.app.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.ruleblend.app.i18n.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

private val DialogShape = RoundedCornerShape(10.dp)
private val DialogPadding = 24.dp

/** Dialog buttons are the answer to the question, not a toolbar: a size up from the compact ones. */
private val DialogButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * The floor for a dialog that is a working surface — a splitter, a file editor, an adopt sheet.
 * Below this a two-pane layout stops being readable: the text wraps every third word and the pane
 * beside it turns into a column of single letters. Question dialogs are exempt; they size to their
 * question, and stretching one across the window only makes it harder to answer.
 */
private const val WorkingDialogMinWidth = 0.7f
private const val WorkingDialogMinHeight = 0.8f

/**
 * The size of a working dialog as a share of the window, never below the floor above. Taken from the
 * window rather than the content because these dialogs hold as much as they are given, and the
 * platform default width would cap them at a strip regardless of what is in them.
 */
@Composable
fun workingDialogSize(widthFraction: Float = 0.9f, heightFraction: Float = 0.9f): DpSize {
    val containerSize = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        DpSize(
            containerSize.width.toDp() * widthFraction.coerceAtLeast(WorkingDialogMinWidth),
            containerSize.height.toDp() * heightFraction.coerceAtLeast(WorkingDialogMinHeight),
        )
    }
}

/**
 * The app's only dialog shell. Material's `AlertDialog` brings 28dp corners, a tonal container and
 * `TextButton` actions, none of which match the flat compact look the rest of the UI uses — so every
 * dialog goes through here instead.
 *
 * A dialog reads as a question and its answer: [title] is the question in large type, [text] under
 * it the consequence in muted type, then whatever [content] the answer needs, then the buttons. A
 * dialog without a title carries its message in [text] at reading size instead. A null
 * [dismissLabel] drops the secondary button entirely (acknowledge-only dialogs).
 *
 * The confirm is the accent colour unless [destructive]: red is kept for a click that loses
 * something — a delete, an overwrite — so it still means "look again" where it appears. Saving,
 * adopting or letting go of an object is not that, however serious the dialog sounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleblendDialog(
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    title: String? = null,
    text: String? = null,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    dismissLabel: String? = null,
    /**
     * The corner close. On by default for everything that can be walked away from; a dialog with no
     * title and no dismiss is an acknowledgement, where the single button already is the way out.
     */
    closable: Boolean = dismissLabel != null || title != null,
    alternativeLabel: String? = null,
    onAlternative: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    minWidth: Dp = 360.dp,
    maxWidth: Dp = 480.dp,
    /**
     * Off for a dialog that sizes itself from the window: the platform default width caps the
     * dialog long before [maxWidth] does, so a widened editor would still open as a narrow strip.
     */
    usePlatformDefaultWidth: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        // Material puts its own box around the content and caps it at 560dp, below this app's floor
        // for a working surface. Turning the platform default off is not enough — the cap is
        // Material's, not the platform's — so a dialog that sized itself off the window has to carry
        // that width out to the box as well, or it opens as a strip whatever the Surface below asks
        // for.
        modifier = if (usePlatformDefaultWidth) Modifier
        else Modifier.widthIn(min = minWidth, max = maxWidth),
        properties = DialogProperties(usePlatformDefaultWidth = usePlatformDefaultWidth),
    ) {
        Surface(
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 12.dp,
            modifier = modifier.widthIn(min = minWidth, max = maxWidth),
        ) {
            Column(Modifier.padding(DialogPadding)) {
                // Question and close share the top line: a close mark on a line of its own pushes
                // every dialog down by a control's height for a control that is not part of the form.
                if (closable || title != null || text != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                            text?.let {
                                if (title != null) {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(it, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp))
                                }
                            }
                        }
                        if (closable) {
                            DialogCloseButton(LocalStrings.current.actionClose, onDismiss)
                        }
                    }
                }
                content?.let {
                    Column(
                        Modifier.padding(top = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = it,
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    dismissLabel?.let {
                        CompactOutlinedButton(
                            onClick = onDismiss,
                            contentPadding = DialogButtonPadding,
                            modifier = Modifier.testTag("dialog-dismiss"),
                        ) { DialogButtonText(it) }
                    }
                    if (alternativeLabel != null && onAlternative != null) {
                        CompactOutlinedButton(onClick = onAlternative, contentPadding = DialogButtonPadding) {
                            DialogButtonText(alternativeLabel)
                        }
                    }
                    CompactButton(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        contentPadding = DialogButtonPadding,
                        modifier = Modifier.testTag("dialog-confirm"),
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) { DialogButtonText(confirmLabel) }
                }
            }
        }
    }
}

@Composable
private fun DialogButtonText(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
}

/**
 * A yes-or-no question built on [RuleblendDialog]. The confirm runs before the dialog dismisses, so
 * the caller does not have to wire the two together.
 *
 * [text] is the whole prompt as the strings carry it — "Delete X? The rules are kept." — and is shown
 * split at its first question mark: the question as the title, what follows as the consequence under
 * it. Red by default, since most of these remove something; pass [destructive] false for a question
 * whose answer keeps everything in place.
 */
@Composable
fun RuleblendConfirmDialog(
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = true,
) {
    val (question, detail) = splitQuestion(text)
    RuleblendDialog(
        onDismiss = onDismiss,
        title = question,
        text = detail,
        confirmLabel = confirmLabel,
        destructive = destructive,
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        dismissLabel = dismissLabel,
    )
}

/**
 * The question of a prompt and the explanation after it. A prompt with no question mark, or with
 * nothing after it, is all question.
 */
internal fun splitQuestion(prompt: String): Pair<String, String?> {
    val end = prompt.indexOf('?')
    if (end < 0) return prompt.trim() to null
    val detail = prompt.substring(end + 1).trim()
    return prompt.substring(0, end + 1).trim() to detail.ifEmpty { null }
}
