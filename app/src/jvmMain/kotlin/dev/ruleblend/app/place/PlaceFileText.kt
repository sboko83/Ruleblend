package dev.ruleblend.app.place

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.library.CompactContextMenu
import dev.ruleblend.app.library.ParagraphSplit
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.Pill
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.StatusCard
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import dev.ruleblend.app.compare.LineDiffText

/**
 * Text in the file that is nobody's but the person who typed it: a grey mat, and on the card's own
 * header line the one thing that can be done with it. The right-click menu stays, but a menu is
 * something you have to know is there — the offer to take this into the library is the reason a
 * reader is looking at an unmanaged fragment at all, so it is on the surface where it can be seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HandText(
    text: String,
    translation: TranslationModel? = null,
    onSaveToLibrary: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val body: @Composable () -> Unit = {
        StatusSurface(StatusMark.UNMANAGED) {
            StatusCard {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        handTitle(text, strings.placeHandText),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Pill(
                            strings.placeNotInLibrary,
                            MaterialTheme.colorScheme.surfaceVariant,
                            RuleblendTheme.extraColors.faint,
                        )
                        onSaveToLibrary?.let { save ->
                            IconActionButton(RuleblendTheme.icons.saveToLibrary, strings.placeSaveToLibrary, save)
                        }
                    }
                }
                Translated(translation, text) { fragment ->
                    Text(
                        fragment,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        // Reading colour, not ownership colour: who wrote the file is said by the mat
                        // around the card, and dimming the body only makes the file harder to read
                        // than its own translation.
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    if (onSaveToLibrary == null) body()
    else CompactContextMenu(items = listOf(strings.placeSaveToLibrary to onSaveToLibrary)) { body() }
}

/**
 * What to call a fragment nobody named. Its first heading if it has one, otherwise its first line:
 * a hand-written block is found again in a file by what it opens with, so that is what the card
 * calls it. A fragment that opens with plain prose is not named at all, only labelled: the file it
 * lives in is already named by the section header above.
 */
private fun handTitle(text: String, fallback: String): String {
    val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    // Only a heading is a name. Any other opening line is simply where the text starts, and printing
    // it above the text it starts says the same words twice for no reader's benefit.
    if (!first.startsWith("#")) return fallback
    return first.trimStart('#', ' ').trim().take(80).ifEmpty { fallback }
}

/**
 * A fragment of the file beside its translation. Only what the reader asked to see is translated —
 * the request goes out per fragment, so a long file costs what is on screen and nothing more; the
 * paragraph pairing itself is the same split the editors use.
 */
@Composable
internal fun Translated(
    translation: TranslationModel?,
    text: String,
    original: @Composable (String) -> Unit,
) {
    if (translation == null) {
        original(text)
        return
    }
    val strings = LocalStrings.current
    LaunchedEffect(translation, text) { translation.requestFragment(text) }
    ParagraphSplit(
        original = text,
        translated = translation.fragment(text),
        direction = strings.translateColumnTitle(
            translation.sourceLanguage.uppercase(),
            translation.targetLanguage.uppercase(),
        ),
        pending = strings.translateWorking,
        source = original,
    )
}

@Composable
internal fun MonoText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The unfolded body of a drifted block, library version against the file's. It shares the compare
 * engine with Resolve and revision history, including word-level changes in replacement lines.
 */
@Composable
internal fun DriftText(libraryContent: String, fileContent: String) =
    LineDiffText(libraryContent, fileContent)

@Composable
internal fun PlaceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
