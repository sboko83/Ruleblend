package dev.ruleblend.app.place

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.ButtonLabel
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RoomyButtonPadding
import dev.ruleblend.app.theme.RuleblendTheme

/**
 * The one button shape every Place write uses, so an install in the palette and an update on the
 * file preview read as the same kind of move. Labels come from the strings the app already has for
 * these operations: the wording of "restore from library" must not fork per surface.
 */
@Composable
internal fun ActionButton(
    action: PlaceAction,
    strings: Strings,
    nextVersion: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Only removal gets a picture: it is the one move on the row that takes the object away, and
    // the rest are told apart by their words.
    val icon = if (action == PlaceAction.REMOVE) RuleblendTheme.icons.delete else null
    CompactOutlinedButton(onClick = onClick, modifier = modifier, contentPadding = RoomyButtonPadding) {
        ButtonLabel(action.label(strings, nextVersion), icon)
    }
}

/** A Place-row action which is navigation rather than a target write. */
@Composable
internal fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    CompactOutlinedButton(onClick = onClick, modifier = modifier, contentPadding = RoomyButtonPadding) {
        ButtonLabel(label)
    }
}

/**
 * The version an accepted edit would be saved as: the version the file holds, one up — the library
 * increments on every content change. `null` when the row states no countable version, and the
 * label falls back to naming the move without its number.
 */
internal fun nextLibraryVersion(version: String?): String? = version?.toIntOrNull()?.plus(1)?.toString()

internal fun PlaceAction.label(strings: Strings, nextVersion: String? = null): String = when (this) {
    PlaceAction.INSTALL -> strings.placeInstall
    PlaceAction.UPDATE -> strings.actionUpdate
    PlaceAction.REMOVE -> strings.placeRemove
    PlaceAction.RESTORE -> strings.intTakeLibrary
    PlaceAction.SAVE_AS_VERSION -> strings.intAcceptLocal(nextVersion)
    PlaceAction.KEEP -> strings.placeKeep
}
