package dev.ruleblend.app.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.StatusCard
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.TRANSLATION_SETTINGS_URL
import dev.ruleblend.app.util.openUrl
import dev.ruleblend.core.translate.TranslationError
import dev.ruleblend.core.translate.alignParagraphs

/**
 * The controls that live next to the "Instruction" label: open or close the panel, flip the
 * direction, refresh a stale translation, and push the translation back into the editor.
 */
@Composable
internal fun TranslationControls(
    model: TranslationModel,
    content: String,
    canReplace: Boolean,
    onReplace: (String) -> Unit,
) {
    if (!model.available) return
    val strings = LocalStrings.current

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (model.visible) {
            Text(
                "${model.sourceLanguage.uppercase()} → ${model.targetLanguage.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactGhostButton(onClick = { model.swapDirection(content) }, enabled = !model.loading,
                modifier = Modifier.testTag("translation-swap")) {
                Text(strings.translateSwap, style = MaterialTheme.typography.labelMedium)
            }
            if (model.stale(content)) {
                CompactGhostButton(onClick = { model.translate(content) }, enabled = !model.loading,
                    modifier = Modifier.testTag("translation-refresh")) {
                    Text(strings.translateRefresh, style = MaterialTheme.typography.labelMedium)
                }
            }
            model.translation?.takeIf { canReplace && it.isNotBlank() && !model.stale(content) }?.let { translated ->
                CompactGhostButton(onClick = { onReplace(translated) }, modifier = Modifier.testTag("translation-replace")) {
                    Text(strings.translateReplace, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        IconActionButton(
            icon = RuleblendTheme.icons.translate,
            label = if (model.visible) strings.translateHide else strings.translateShow,
            onClick = { model.toggle(content) },
            modifier = Modifier.testTag("translation-toggle"),
            contentColor = if (model.visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Lays out the editor field, alone or beside its translation.
 *
 * An open translation turns the field into a reading surface: text and translation are cut into the
 * same paragraphs and put side by side in one scroll, the way a file reads on the Places screen.
 * A paragraph pair only holds while nothing moves under it, so editing goes back through
 * "Hide translation" — the field is what the reader was comparing against.
 */
@Composable
internal fun ContentWithTranslation(
    translation: TranslationModel?,
    content: String,
    /** The state of what is being edited, as the mat under it: green in the library, grey for a
     *  file nobody adopted, the app's accent for a whole file. */
    mark: StatusMark? = null,
    editor: @Composable (Modifier) -> Unit,
) {
    val strings = LocalStrings.current
    // Both halves of this — a plain field and a field beside its translation — are the same object
    // being shown, so both get the same mat. Drawing it here rather than at the call site is what
    // keeps turning the translation on from changing what surface the reader is looking at.
    if (translation == null || !translation.visible) {
        StatusSurface(mark) { editor(Modifier.fillMaxWidth().fillMaxHeight()) }
        return
    }
    StatusSurface(
        mark = mark,
        notice = when {
            translation.error != null -> null
            translation.stale(content) -> strings.translateStale
            else -> strings.translateReadOnly
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (translation.error != null) TranslationFailure(translation)
        // The notice stays put while the text moves: on a long instruction the reason the field
        // cannot be typed into would otherwise scroll away.
        StatusCard(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ParagraphSplit(
                original = content,
                // A landing translation belongs to the text it was made from; an older one would pair
                // the reader's paragraphs with someone else's.
                translated = translation.translation?.takeIf { !translation.loading && !translation.stale(content) },
                direction = strings.translateColumnTitle(
                    translation.sourceLanguage.uppercase(),
                    translation.targetLanguage.uppercase(),
                ),
                // A failed translation has nothing on its way; saying so once, above, is enough.
                pending = if (translation.error == null) strings.translateWorking else "",
            ) { paragraph -> ParagraphText(paragraph, MaterialTheme.colorScheme.onSurface) }
        }
    }
}

/**
 * A text beside its translation, paragraph against paragraph, growing with its content so both
 * halves live in the caller's single scroll.
 *
 * Reading a translation means comparing one thought against one thought, not two walls of text that
 * drift apart the moment one language is wordier. Both sides are cut by the same segmenter, so a
 * heading sits next to its heading; [source] draws the left half, which lets the caller keep its own
 * styling and context menu there.
 */
@Composable
internal fun ParagraphSplit(
    original: String,
    translated: String?,
    direction: String,
    pending: String,
    modifier: Modifier = Modifier,
    source: @Composable (String) -> Unit,
) {
    val strings = LocalStrings.current
    val paragraphs = remember(original, translated) { alignParagraphs(original, translated) }
    Column(modifier.fillMaxWidth()) {
        SplitRow(
            left = { ColumnTitle(strings.translateOriginalTitle) },
            right = { ColumnTitle(direction) },
        )
        paragraphs.forEachIndexed { index, paragraph ->
            SplitRow(
                left = { source(paragraph.source) },
                right = {
                    // Said once, at the top: a wait repeated against every paragraph reads as an
                    // error rather than as work in progress.
                    ParagraphText(
                        paragraph.target ?: if (index == 0) pending else "",
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }
}

/**
 * One line of the split view. The rule between the halves is part of the row and every row is built
 * the same way, so the separator reads as one line running down the whole text.
 */
@Composable
internal fun SplitRow(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    gutter: @Composable () -> Unit = {
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    },
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.weight(1f).padding(end = 10.dp, bottom = 8.dp)) { left() }
        Box(Modifier.fillMaxHeight()) { gutter() }
        Box(Modifier.weight(1f).padding(start = 10.dp, bottom = 8.dp)) { right() }
    }
}

/** Which side is which, so the reader never has to guess which column is the translation. */
@Composable
private fun ColumnTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = RuleblendTheme.extraColors.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

/** One paragraph of either half: the same face and metrics on both sides, so the lines stay comparable. */
@Composable
private fun ParagraphText(text: String, color: Color) {
    SelectionContainer {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Why a translation did not arrive, in the user's words. Shared with surfaces that have no panel. */
internal fun translationErrorMessage(error: TranslationError, strings: Strings): String = when (error) {
    TranslationError.PACK_MISSING -> strings.translatePackMissing
    TranslationError.UNSUPPORTED -> strings.translateUnsupported
    TranslationError.UNAVAILABLE -> strings.translateUnavailable
    TranslationError.FAILED -> strings.translateFailed
}

/** A missing language pack is fixable by the user, so it gets a way out; the rest just explain. */
@Composable
private fun TranslationFailure(model: TranslationModel) {
    val strings = LocalStrings.current
    val error = model.error ?: return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            translationErrorMessage(error, strings),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (error == TranslationError.PACK_MISSING) {
            Box {
                CompactOutlinedButton(onClick = { openUrl(TRANSLATION_SETTINGS_URL) }) {
                    Text(strings.translateOpenSettings, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
