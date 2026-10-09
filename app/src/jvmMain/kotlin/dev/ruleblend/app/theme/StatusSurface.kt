package dev.ruleblend.app.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * The one shape Ruleblend shows an object in — a rule, an MCP entry, a skill, a whole file — wherever
 * it shows one: an outer frame, a coloured mat inside it, and the object's own card on that mat.
 *
 * The mat is the point. Its colour is the object's state ([StatusMark.matColor]), so a screen full of
 * cards is read before it is read: green is in sync, amber has an update waiting, red was edited
 * behind the app's back, grey is nothing of ours, and the app's accent means the card is a document
 * rather than an object with a state at all. That is one vocabulary across every screen, which is why
 * no screen draws its own box around an object instead of calling this.
 *
 * [notice] is the line written on the mat above the card — what the mat means in words, for a reader
 * who is not going by colour. `null` leaves the mat bare, which is the right answer whenever the card
 * below already says it (a status badge on the card's own header line).
 */
@Composable
fun StatusSurface(
    mark: StatusMark?,
    notice: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.surfaceCornerRadius)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(mark.matColor())
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(9.dp),
    ) {
        notice?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                color = mark.matNoticeColor(),
            )
        }
        content()
    }
}

/**
 * The card the mat carries: the object itself, on the neutral surface every other readable thing in
 * the app sits on. It keeps its own hairline so the card is still a card where the mat happens to be
 * pale, and it never carries state colour of its own — the mat says the state once, and a card that
 * repeated it would leave the text sitting on a tint nobody chose for reading.
 */
@Composable
fun StatusCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.cardCornerRadius)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = CardPaddingH, vertical = CardPaddingV),
        content = content,
    )
}

/**
 * A [StatusCard] whose first line is its header — the name, the marks, the writes — and whose click
 * unfolds or folds the body below. The header is laid out edge to edge and takes the card's padding
 * inside itself, so hovering it lights the card up to its hairline instead of an inset strip that
 * stops short of the mat. [body] is `null` for a folded card: an empty body would still add its gap.
 */
@Composable
fun StatusCard(
    header: @Composable () -> Unit,
    onHeaderClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(RuleblendTheme.dimensions.cardCornerRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (onHeaderClick == null) Modifier else Modifier.clickable(onClick = onHeaderClick))
                .padding(horizontal = CardPaddingH, vertical = CardPaddingV),
        ) {
            header()
        }
        body?.let {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(start = CardPaddingH, end = CardPaddingH, bottom = CardPaddingV),
                content = it,
            )
        }
    }
}

private val CardPaddingH = 10.dp
private val CardPaddingV = 10.dp
