package dev.ruleblend.app.util

import androidx.compose.runtime.Composable
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.library.LibraryInstallReport
import dev.ruleblend.app.library.summary
import dev.ruleblend.app.theme.RuleblendDialog

/**
 * A failed action, held by a model until the screen shows it. Carries the exception message rather
 * than a finished sentence so the wording stays with the UI, in the user's language.
 */
data class Failure(
    val cause: String,
    val adoptedButNotReplaced: Boolean = false,
    /** What the rest of a multi-object write did, when the failure stopped only part of it. */
    val partial: LibraryInstallReport? = null,
    val removal: Boolean = false,
) {
    companion object {
        fun of(error: Throwable): Failure = when (error) {
            is PartialWriteException -> Failure(error.message ?: error.toString(), partial = error.report, removal = error.removal)
            else -> Failure(error.message ?: error.toString())
        }
    }
}

/**
 * A multi-object write in which at least one object failed while others landed or were refused.
 * The message is the first failure; [report] keeps the tallies so the dialog does not present a
 * partial write as if nothing had happened.
 */
class PartialWriteException(
    val report: LibraryInstallReport,
    val removal: Boolean,
    cause: Throwable,
) : RuntimeException(cause.message ?: cause.toString(), cause)

/** Acknowledge-only dialog for a [Failure]; the app keeps running with the state it reloaded. */
@Composable
fun FailureDialog(failure: Failure, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.errorTitle,
        text = listOfNotNull(
            if (failure.adoptedButNotReplaced) strings.errAdoptNotReplaced(failure.cause) else failure.cause,
            failure.partial?.summary(strings, removal = failure.removal),
        ).joinToString("\n\n"),
        confirmLabel = strings.actionOk,
        onConfirm = onDismiss,
    )
}
