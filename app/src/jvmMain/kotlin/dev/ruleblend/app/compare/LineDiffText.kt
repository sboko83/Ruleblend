package dev.ruleblend.app.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.compare.LineDiff
import dev.ruleblend.core.compare.WordDiffKind
import dev.ruleblend.core.compare.WordDiffSegment

/** A display line produced from [LineDiff], including its word-level change spans. */
internal data class RenderedDiffLine(
    val kind: RenderedDiffKind,
    val segments: List<RenderedDiffSegment>,
)

internal enum class RenderedDiffKind { CONTEXT, ADDED, REMOVED }

internal data class RenderedDiffSegment(val text: String, val changed: Boolean)

/**
 * The one read-only view of a text difference used outside the editable Compare pane. Context and
 * line mats make the shape of the change scannable; changed words receive a second, stronger mat.
 */
@Composable
internal fun LineDiffText(
    source: String,
    target: String,
    modifier: Modifier = Modifier,
    contextColor: Color = MaterialTheme.colorScheme.onSurface,
    singleLine: Boolean = false,
) {
    val lines = remember(source, target) { renderedDiffLines(source, target) }
    val extras = RuleblendTheme.extraColors
    Column(modifier) {
        lines.forEach { line ->
            val (color, mat, changedMat) = when (line.kind) {
                RenderedDiffKind.ADDED -> Triple(extras.success, extras.successBackground, extras.success.copy(alpha = 0.18f))
                RenderedDiffKind.REMOVED -> Triple(extras.danger, extras.dangerBackground, extras.danger.copy(alpha = 0.18f))
                RenderedDiffKind.CONTEXT -> Triple(contextColor, Color.Transparent, Color.Transparent)
            }
            Text(
                annotatedLine(line, changedMat),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                color = color,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                softWrap = !singleLine,
                modifier = Modifier.fillMaxWidth().background(mat),
            )
        }
    }
}

/** Converts two complete bodies to presentation rows while retaining unchanged context. */
internal fun renderedDiffLines(source: String, target: String): List<RenderedDiffLine> {
    val sourceLines = diffDisplayLines(source)
    val lines = mutableListOf<RenderedDiffLine>()
    var sourceIndex = 0
    LineDiff.between(source, target).forEach { hunk ->
        while (sourceIndex < hunk.source.start) {
            lines += context(sourceLines[sourceIndex])
            sourceIndex++
        }
        hunk.changes.forEach { change ->
            when {
                change.source != null && change.target != null -> {
                    lines += changed(change.words, RenderedDiffKind.REMOVED, WordDiffKind.ADDED)
                    lines += changed(change.words, RenderedDiffKind.ADDED, WordDiffKind.REMOVED)
                    sourceIndex++
                }
                change.source != null -> {
                    val sourceLine = requireNotNull(change.source)
                    lines += RenderedDiffLine(RenderedDiffKind.REMOVED, listOf(RenderedDiffSegment(sourceLine, false)))
                    sourceIndex++
                }
                change.target != null -> {
                    val targetLine = requireNotNull(change.target)
                    lines += RenderedDiffLine(RenderedDiffKind.ADDED, listOf(RenderedDiffSegment(targetLine, false)))
                }
            }
        }
    }
    while (sourceIndex < sourceLines.size) {
        lines += context(sourceLines[sourceIndex])
        sourceIndex++
    }
    return lines
}

private fun context(text: String) = RenderedDiffLine(RenderedDiffKind.CONTEXT, listOf(RenderedDiffSegment(text, false)))

private fun changed(
    words: List<WordDiffSegment>,
    kind: RenderedDiffKind,
    excluded: WordDiffKind,
) = RenderedDiffLine(
    kind,
    words.filter { it.kind != excluded }.map { word ->
        RenderedDiffSegment(word.text, word.kind != WordDiffKind.CONTEXT)
    },
)

private fun annotatedLine(line: RenderedDiffLine, changedMat: Color): AnnotatedString = buildAnnotatedString {
    append(
        when (line.kind) {
            RenderedDiffKind.ADDED -> "+ "
            RenderedDiffKind.REMOVED -> "− "
            RenderedDiffKind.CONTEXT -> "  "
        },
    )
    line.segments.forEach { segment ->
        if (segment.changed) pushStyle(SpanStyle(background = changedMat))
        append(segment.text)
        if (segment.changed) pop()
    }
}
