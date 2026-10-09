package dev.ruleblend.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp

/** Pill-shaped label, used for non-synced status badges, agent toggles and counts. */
@Composable
fun Pill(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

enum class SyncBadge { SYNCED, UPDATE, MODIFIED }

@Composable
fun StatusBadge(kind: SyncBadge, text: String, modifier: Modifier = Modifier) {
    val extras = RuleblendTheme.extraColors
    when (kind) {
        SyncBadge.SYNCED -> {
            val style = MaterialTheme.typography.labelMedium
            // Centring the dot on the line box puts it above a lowercase word, whose visual middle
            // is half an x-height over the baseline, not the middle of ascent and descent. The dot
            // is hung from the baseline instead.
            val halfXHeight = with(LocalDensity.current) { (style.fontSize.toPx() * XHeightRatio / 2).roundToInt() }
            Row(
                modifier = modifier.padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(extras.success, Modifier.alignBy { it.measuredHeight / 2 + halfXHeight })
                Text(text, style = style, color = extras.success, modifier = Modifier.alignByBaseline())
            }
        }
        SyncBadge.UPDATE, SyncBadge.MODIFIED -> {
            val (bg, fg) = if (kind == SyncBadge.UPDATE) {
                extras.badgeUpdBg to extras.badgeUpdFg
            } else {
                extras.badgeModBg to extras.badgeModFg
            }
            Pill(text, bg, fg, modifier)
        }
    }
}

/** x-height over font size of the system sans, Latin and Cyrillic alike. */
private const val XHeightRatio = 0.54f

/** Version pill, e.g. block list `vN` / editor version badge. */
@Composable
fun VersionPill(text: String, modifier: Modifier = Modifier) {
    Pill(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, modifier)
}

/**
 * A quiet rectangular tag beside a name — a file's ownership mode, a block's version. It describes
 * the thing it sits next to rather than a state, so it never takes a status colour.
 */
@Composable
fun NameTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
