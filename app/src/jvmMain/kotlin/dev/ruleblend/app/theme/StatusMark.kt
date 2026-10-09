package dev.ruleblend.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.core.integration.InstallStatus

/**
 * What a status dot can say. Declaration order is the importance order, and a dot paints the first
 * two marks it holds rather than a four-way pie nobody can decode at 7 dp.
 *
 * The ranking is what needs doing, worst first, because that is what the left half of every dot in a
 * column adds up to when the list is scanned. [MANAGED] ranks below the two problems on purpose:
 * almost every place that is set up at all holds something in sync, so leading with it would paint
 * the same green on nearly every dot and leave only the right half saying anything. [UNMANAGED] is
 * last — a file nobody adopted asks for nothing until the rest is in order.
 *
 * Each object contributes exactly one mark, and a file that holds nothing of Ruleblend's contributes
 * [UNMANAGED] — so an empty place is grey instead of borrowing the green of "nothing is out of sync".
 */
enum class StatusMark {
    /** A managed region was edited outside Ruleblend. */
    CONFLICT,

    /** The library holds a newer version of something installed here. */
    UPDATE,

    /** Something of the library is installed here and matches it. */
    MANAGED,

    /** A file that exists and carries no managed region at all. */
    UNMANAGED,
}

/** The two leading marks, most important first: exactly what a dot paints, left half then right. */
fun Set<StatusMark>.leading(): List<StatusMark> = StatusMark.entries.filter { it in this }.take(2)

/** The sync state of one object as the mark it contributes to every dot that sums it up. */
fun InstallStatus.mark(): StatusMark = when (this) {
    InstallStatus.SYNCED -> StatusMark.MANAGED
    InstallStatus.UPDATE_AVAILABLE -> StatusMark.UPDATE
    InstallStatus.MODIFIED -> StatusMark.CONFLICT
}

/** The one colour of a mark, so a hue means the same thing on every screen. */
@Composable
fun StatusMark.color(): Color {
    val extras = RuleblendTheme.extraColors
    return when (this) {
        StatusMark.MANAGED -> extras.success
        StatusMark.CONFLICT -> extras.danger
        StatusMark.UPDATE -> extras.warning
        StatusMark.UNMANAGED -> extras.unmanaged
    }
}

/**
 * The mat a state paints under a card — the band the whole status surface is read by, before a word
 * of it has been. `null` is not a state but the absence of one: a whole file is a document, not an
 * object that can be in sync or out of it, so it wears the app's own accent instead of borrowing a
 * verdict it has no business making.
 */
@Composable
fun StatusMark?.matColor(): Color {
    val extras = RuleblendTheme.extraColors
    return when (this) {
        StatusMark.MANAGED -> extras.matManaged
        StatusMark.UPDATE -> extras.matUpdate
        StatusMark.CONFLICT -> extras.matConflict
        StatusMark.UNMANAGED -> extras.matNeutral
        null -> extras.accentBackground
    }
}

/**
 * The colour of the line written on a mat. It is the state's own hue rather than the faint grey used
 * on the window: a mat is already tinted, and grey on a tint is the one pairing that reads as neither.
 */
@Composable
fun StatusMark?.matNoticeColor(): Color = when (this) {
    StatusMark.MANAGED, StatusMark.UPDATE, StatusMark.CONFLICT -> color()
    StatusMark.UNMANAGED, null -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** What a mark means in words: the tooltip of the colour, and what a screen reader reads. */
fun StatusMark.label(strings: Strings): String = when (this) {
    StatusMark.MANAGED -> strings.libStatusSynced
    StatusMark.CONFLICT -> strings.libStatusModified
    StatusMark.UPDATE -> strings.libStatusUpdate
    StatusMark.UNMANAGED -> strings.placeModeNone
}

/** The words of a whole dot: the marks it paints, in the order it paints them. */
fun Set<StatusMark>.label(strings: Strings): String =
    leading().joinToString(" · ") { it.label(strings) }
