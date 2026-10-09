package dev.ruleblend.app.place

import dev.ruleblend.app.library.KindMark
import dev.ruleblend.app.library.BlockContextMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.integration.McpCopy
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.NameTag
import dev.ruleblend.app.theme.Pill
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.StatusBadge
import dev.ruleblend.app.theme.StatusCard
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import dev.ruleblend.app.theme.SyncBadge
import dev.ruleblend.app.theme.label
import dev.ruleblend.app.theme.mark
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.PlaceEntryOrigin
import androidx.compose.foundation.layout.ColumnScope
import java.nio.file.Path

/** Drag handle of a block row: the grip of the mockup, drawn from the font rather than as an icon. */
private const val DRAG_GRIP = "⠿"

/** The two ends of a run, as the row buttons draw them: an arrow that stops at a bar. */
internal const val MOVE_TOP_GLYPH = "⤒"
internal const val MOVE_BOTTOM_GLYPH = "⤓"

/**
 * The one-click ends of a run. A drag answers "just above that one"; the two questions it answers
 * badly are "first" and "last" — a block added to a full file lands at the bottom, and dragging it
 * past a dozen rows is a dozen chances to drop it in the wrong place. Each end is `null` for the row
 * already at it, and both are `null` for a run whose order is not the file's to change.
 */
internal data class PlaceRowEnds(val toTop: (() -> Unit)?, val toBottom: (() -> Unit)?)
/**
 * The blocks of one managed run, draggable into another order when [onReorder] is given. The order
 * shown is the file's; a drag only moves it locally until the pointer is released, and the write that
 * follows re-reads the file, so what stays on screen is what actually landed.
 *
 * Rows are measured rather than assumed uniform — see [dropTargetIndex] — and the dragged row is
 * pulled back by the height of every row it passed, so it stays under the pointer.
 */
@Composable
internal fun ReorderableBlocks(
    rows: List<PlaceBlockRow>,
    onReorder: ((List<String>) -> Unit)?,
    row: @Composable (PlaceBlockRow, Modifier?, PlaceRowEnds?) -> Unit,
) {
    val spacing = 6.dp
    if (onReorder == null || rows.size < 2) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) { rows.forEach { row(it, null, null) } }
        return
    }
    val ids = rows.map { it.id }
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    var order by remember(ids) { mutableStateOf(ids) }
    val heights = remember(ids) { mutableStateMapOf<String, Float>() }
    var dragId by remember(ids) { mutableStateOf<String?>(null) }
    var dragOffset by remember(ids) { mutableStateOf(0f) }
    val byId = rows.associateBy { it.id }

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        order.forEachIndexed { index, id ->
            val item = byId[id] ?: return@forEachIndexed
            val dragging = id == dragId
            // Both ends write the whole run in one go, the same call the drag ends with: the order is
            // the file's, and a move is one write of it rather than a walk up the neighbours.
            val ends = PlaceRowEnds(
                toTop = if (index > 0) ({ onReorder(order.moveItem(index, 0)) }) else null,
                toBottom = if (index < order.lastIndex) ({ onReorder(order.moveItem(index, order.lastIndex)) }) else null,
            )
            // Keyed by id, not by position: the drag in progress belongs to the row, and reordering
            // the column must move its gesture and its measured height with it.
            key(id) {
                Box(
                    Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .onSizeChanged { size -> heights[id] = size.height.toFloat() + spacingPx },
                ) {
                    row(
                        item,
                        Modifier.pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { dragId = id },
                                onDragCancel = {
                                    dragId = null
                                    dragOffset = 0f
                                    order = ids
                                },
                                onDragEnd = {
                                    dragId = null
                                    dragOffset = 0f
                                    if (order != ids) onReorder(order)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val current = order.indexOf(id)
                                    val measured = order.map { heights[it] ?: 0f }
                                    val target = dropTargetIndex(measured, current, dragOffset)
                                    if (target != current) {
                                        dragOffset -= dragOffsetAfterMove(measured, current, target)
                                        order = order.moveItem(current, target)
                                    }
                                },
                            )
                        },
                        ends,
                    )
                }
            }
        }
    }
}

/**
 * One installed object as the file holds it, with the writes that apply to it. A hand edit gets its
 * own line of explanation and the three ways out; [settled] is the "keep" answer — the block stays
 * modified and the row stops asking, because nothing was written and nothing is pending.
 *
 * The row folds: closed it is the block's identity, open it is the text the agent reads. A file
 * holds a dozen blocks, so the whole text of every one of them cannot be the default state — but it
 * is the only thing here that answers "what does this rule actually say".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlockRow(
    row: PlaceBlockRow,
    kind: LibraryObjectKind,
    fileKey: String = "",
    strings: Strings,
    translation: TranslationModel? = null,
    settled: Boolean,
    handle: Modifier?,
    /** The one-click moves to either end of the run; `null` where the run has no order to change. */
    ends: PlaceRowEnds? = null,
    unfolded: Boolean,
    /**
     * The agent copies of this object when they were edited apart — an MCP entry only, and only while
     * the drift is open. A row with copies apart has no single "the local edit", so it does not offer
     * to save one: each copy is offered by name instead, in [CopyRow].
     */
    copies: List<McpCopy> = emptyList(),
    onToggleFold: () -> Unit,
    onAction: (PlaceAction) -> Unit,
    onSaveCopy: (String) -> Unit = {},
    /** Opens this managed library object in its structured editor; absent for foreign rows and orphans. */
    onEdit: (() -> Unit)? = null,
    editLabel: String = strings.libEditFocus,
    /** Opens this skill or subagent definition file; absent for rows without their own file. */
    onEditFile: (() -> Unit)? = null,
    /** Stops managing this copy and leaves it on disk as a foreign entry; absent where that has no meaning. */
    onRelease: (() -> Unit)? = null,
) {
    val drift = row.status == InstallStatus.MODIFIED && !settled
    val apart = if (drift) copies else emptyList()
    val actions = (if (settled) listOf(PlaceAction.REMOVE) else fileRowActions(kind, row.status))
        .filterNot { apart.isNotEmpty() && it == PlaceAction.SAVE_AS_VERSION }
    val text = row.content?.takeIf { it.isNotBlank() }
    // The block's own state as the mat it sits on, so a run of a dozen is read by colour before a
    // word of it is: green in sync, amber behind the library, red edited behind Ruleblend's back.
    // A kept drift goes grey rather than staying red — it is still an edit, but it is an answered
    // one, and a mat that keeps shouting is a mat asking a question that was already settled.
    val mark = when {
        row.status == null -> StatusMark.UNMANAGED
        settled -> StatusMark.UNMANAGED
        else -> row.status.mark()
    }
    val header = @Composable {
        // Identity and writes flow rather than share one line: a drifted rule offers four buttons,
        // and in a narrow window they used to run off the row instead of moving under the name.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    text?.let {
                        DisclosureArrow(unfolded)
                    }
                    handle?.let { drag ->
                        Text(
                            DRAG_GRIP,
                            style = MaterialTheme.typography.labelSmall,
                            color = RuleblendTheme.extraColors.faint,
                            modifier = drag.pointerHoverIcon(PointerIcon.Hand),
                        )
                    }
                    KindMark(kind)
                    Text(
                        row.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    row.version?.let { NameTag("v$it") }
                    // Beside the version rather than with the writes on the right: this moves the
                    // block inside its file, it does not change what the block says. The two ends are
                    // one control — they sit tight against each other and clear of the version, or
                    // else three equal gaps read as three unrelated marks.
                    ends?.takeIf { it.toTop != null || it.toBottom != null }?.let { moves ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 5.dp),
                        ) {
                            moves.toTop?.let { move ->
                                EndButton(MOVE_TOP_GLYPH, strings.placeMoveTop,
                                    Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, "move-top")), move)
                            }
                            moves.toBottom?.let { move ->
                                EndButton(MOVE_BOTTOM_GLYPH, strings.placeMoveBottom,
                                    Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, "move-bottom")), move)
                            }
                        }
                    }
                    row.group?.let { group ->
                        Pill("g:$group", RuleblendTheme.extraColors.accentBackground, MaterialTheme.colorScheme.primary)
                    }
                }
                row.path?.let { EntryPath(it) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                when (row.status) {
                    InstallStatus.SYNCED -> StatusBadge(SyncBadge.SYNCED, strings.libStatusSynced)
                    InstallStatus.UPDATE_AVAILABLE -> StatusBadge(SyncBadge.UPDATE, strings.libStatusUpdate)
                    // A kept edit is still an edit, but it is no longer an open question: saying
                    // "modified" at it forever is how the row asked again for something already answered.
                    InstallStatus.MODIFIED ->
                        if (settled) Pill(strings.placeDriftKept, MaterialTheme.colorScheme.surfaceVariant, RuleblendTheme.extraColors.faint)
                        else StatusBadge(SyncBadge.MODIFIED, strings.libStatusModified)
                    null -> Pill(strings.placeNotInLibrary, MaterialTheme.colorScheme.surfaceVariant, RuleblendTheme.extraColors.faint)
                }
                // The writes that change what the block says keep their words; opening and removing
                // it are the two moves every row has, and they read as pictures once seen twice.
                actions.filterNot { it == PlaceAction.REMOVE }.forEach { action ->
                    ActionButton(
                        action,
                        strings,
                        nextLibraryVersion(row.version),
                        modifier = Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, action.name.lowercase())),
                    ) { onAction(action) }
                }
                onEdit?.let { edit ->
                    IconActionButton(
                        RuleblendTheme.icons.edit,
                        editLabel,
                        edit,
                        Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, "edit")),
                    )
                }
                onEditFile?.let { edit ->
                    IconActionButton(RuleblendTheme.icons.file, strings.intEditFile, edit)
                }
                onRelease?.let { release ->
                    IconActionButton(
                        RuleblendTheme.icons.release,
                        strings.placeUnlinkEntry,
                        release,
                        Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, "release")),
                    )
                }
                if (PlaceAction.REMOVE in actions) {
                    IconActionButton(
                        RuleblendTheme.icons.delete,
                        PlaceAction.REMOVE.label(strings),
                        { onAction(PlaceAction.REMOVE) },
                        Modifier.testTag(uiTarget("place-file", "${kind.name}:${row.id}", fileKey, "remove")),
                    )
                }
            }
        }
    }
    val body: (@Composable ColumnScope.() -> Unit)? = if (drift || unfolded && (apart.isNotEmpty() || text != null)) {
        {
            if (drift) {
                Text(
                    if (apart.isEmpty()) strings.placeDriftNotice else strings.placeDriftApart,
                    style = MaterialTheme.typography.labelSmall,
                    color = RuleblendTheme.extraColors.faint,
                )
            }
            if (unfolded && apart.isNotEmpty()) {
                // One body per agent rather than one for the row: the row's own text is whichever copy
                // was picked to stand for it, and standing for the others is exactly what it cannot do.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    apart.forEach { copy ->
                        BlockContextMenu(copy.text, onEdit) {
                            CopyRow(copy, row.libraryContent, strings, nextLibraryVersion(row.version)) { onSaveCopy(copy.agentId) }
                        }
                    }
                }
            } else if (unfolded && text != null) {
                // A drifted body is read against the library one: "modified" on its own does not say what.
                // A drift is read as a diff, and a diff is about the lines that changed; translating it
                // would blur exactly what the reader came here to see.
                if (drift && row.libraryContent != null) DriftText(row.libraryContent, text)
                else Translated(translation, text) { fragment -> MonoText(fragment) }
            }
        }
    } else null
    val card = @Composable {
        StatusSurface(mark) {
            StatusCard(header, onHeaderClick = onToggleFold.takeIf { text != null }, body = body)
        }
    }
    if (unfolded && text != null) BlockContextMenu(text, onEdit, card) else card()
}

/**
 * Disk entries not installed by Ruleblend. Foreign rows can be saved into the library; an orphaned
 * row restores its former object or safely removes its recorded copy instead.
 */
@Composable
internal fun ForeignEntrySection(
    rows: List<PlaceBlockRow>,
    kind: LibraryObjectKind,
    strings: Strings,
    unfolded: (PlaceBlockRow) -> Boolean,
    onToggleFold: (PlaceBlockRow) -> Unit,
    onSaveToLibrary: ((PlaceBlockRow) -> Unit)? = null,
    /** Takes a row under the library object of its name; returns null for a row no such object has. */
    onTakeOwnership: ((PlaceBlockRow) -> (() -> Unit)?)? = null,
    canSaveToLibrary: (PlaceBlockRow) -> Boolean = { true },
    onRestoreOrphan: ((PlaceBlockRow) -> Unit)? = null,
    onRemoveOrphan: ((PlaceBlockRow) -> Unit)? = null,
    onRemoveForeign: ((PlaceBlockRow) -> Unit)? = null,
    onSetHidden: ((PlaceBlockRow, Boolean) -> Unit)? = null,
    onEditFile: ((PlaceBlockRow) -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            strings.placeForeignEntries,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.faint,
            modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp),
        )
        rows.forEach { row ->
            val take = onTakeOwnership?.takeIf { row.origin != PlaceEntryOrigin.ORPHAN }?.invoke(row)
            ForeignEntryRow(
                row,
                kind,
                strings,
                unfolded(row),
                { onToggleFold(row) },
                // An entry named like a library object is linked to it, never shelved as a second copy.
                onSaveToLibrary?.takeIf { take == null && row.origin != PlaceEntryOrigin.ORPHAN && canSaveToLibrary(row) }
                    ?.let { save -> { save(row) } },
                take,
                onRestoreOrphan?.takeIf { row.origin == PlaceEntryOrigin.ORPHAN }?.let { restore -> { restore(row) } },
                onRemoveOrphan?.takeIf { row.origin == PlaceEntryOrigin.ORPHAN }?.let { remove -> { remove(row) } },
                onRemoveForeign?.takeIf { row.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED) }
                    ?.let { remove -> { remove(row) } },
                onSetHidden?.let { setHidden -> { hidden -> setHidden(row, hidden) } },
                onEditFile?.takeIf { row.path != null }?.let { edit -> { edit(row) } },
            )
        }
    }
}

/** Bundled artifacts are readable here, while Settings remains their only update and removal path. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuiltInEntrySection(
    rows: List<PlaceBlockRow>,
    kind: LibraryObjectKind,
    strings: Strings,
    unfolded: (PlaceBlockRow) -> Boolean,
    onToggleFold: (PlaceBlockRow) -> Unit,
) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            strings.placeBuiltInEntries,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
            color = RuleblendTheme.extraColors.faint,
            modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp),
        )
        rows.forEach { row ->
            val text = row.content
            StatusSurface(StatusMark.MANAGED) {
                StatusCard(
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                itemVerticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                text?.let { DisclosureArrow(unfolded(row)) }
                                KindMark(kind)
                                Text(
                                    row.name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                row.version?.let {
                                    Text(
                                        "v$it",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                row.agentId?.let { agentId ->
                                    Pill(agentId, MaterialTheme.colorScheme.surfaceVariant, RuleblendTheme.extraColors.faint)
                                }
                                Pill(strings.placeBuiltInManaged, RuleblendTheme.extraColors.successBackground, MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                strings.placeBuiltInReadOnly,
                                style = MaterialTheme.typography.labelSmall,
                                color = RuleblendTheme.extraColors.faint,
                            )
                        }
                    },
                    onHeaderClick = if (text == null) null else { { onToggleFold(row) } },
                    modifier = Modifier.testTag("place-builtin:${row.entryKey}"),
                    body = if (unfolded(row) && text != null) {
                        { MonoText(text) }
                    } else null,
                )
            }
        }
    }
}

/**
 * One foreign entry, carrying only what discovery read from disk and its physical address. The head
 * is the identity line of a managed row — name, address, version — with the writes on the right, and
 * the entry's own description is left to the text it unfolds into: it is the first thing in there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForeignEntryRow(
    row: PlaceBlockRow,
    kind: LibraryObjectKind,
    strings: Strings,
    unfolded: Boolean,
    onToggleFold: () -> Unit,
    onSaveToLibrary: (() -> Unit)?,
    onTakeOwnership: (() -> Unit)?,
    onRestoreOrphan: (() -> Unit)?,
    onRemoveOrphan: (() -> Unit)?,
    onRemoveForeign: (() -> Unit)?,
    onSetHidden: ((Boolean) -> Unit)?,
    onEditFile: (() -> Unit)?,
) {
    // Discovery already read the entry; composition reads the model, never the disk.
    val text = row.content
    // Every move is a picture with its word as the tooltip, kept on the right as on a managed row:
    // the words used to wrap under the name and push the rows of a long list apart.
    val header = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // The description is in the text this row unfolds into, so on the line it is only a
            // hover: the line is an identity, and a second sentence on it is read instead of the name.
            val identity = @Composable {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        text?.let { DisclosureArrow(unfolded) }
                        KindMark(kind)
                        Text(
                            row.name,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        row.version?.let {
                            Text(
                                "v$it",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        row.agentId?.let { agentId ->
                            Pill(agentId, MaterialTheme.colorScheme.surfaceVariant, RuleblendTheme.extraColors.faint)
                        }
                    }
                    row.path?.let { EntryPath(it) }
                }
            }
            if (row.subtitle != null) RuleblendTooltip(row.subtitle, Modifier.weight(1f)) { identity() }
            else Box(Modifier.weight(1f)) { identity() }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onEditFile?.let { edit ->
                    IconActionButton(RuleblendTheme.icons.file, strings.intEditFile, edit)
                }
                onTakeOwnership?.let { take ->
                    IconActionButton(
                        RuleblendTheme.icons.takeOwnership,
                        strings.placeTakeOwnership,
                        take,
                        Modifier.testTag("place-foreign-take:${row.entryKey}"),
                    )
                }
                onSaveToLibrary?.let { save ->
                    IconActionButton(
                        RuleblendTheme.icons.saveToLibrary,
                        strings.actionAdopt,
                        save,
                        Modifier.testTag("place-foreign-save:${row.entryKey}"),
                    )
                }
                onRestoreOrphan?.let { restore ->
                    IconActionButton(
                        RuleblendTheme.icons.restore,
                        strings.placeRestoreOrphan,
                        restore,
                        Modifier.testTag("place-orphan-restore:${row.entryKey}"),
                    )
                }
                onSetHidden?.takeIf { row.origin != PlaceEntryOrigin.ORPHAN }?.let { setHidden ->
                    val hidden = row.origin == PlaceEntryOrigin.IGNORED
                    IconActionButton(
                        if (hidden) RuleblendTheme.icons.unhide else RuleblendTheme.icons.hide,
                        if (hidden) strings.placeUnhideEntry else strings.placeHideEntry,
                        { setHidden(!hidden) },
                        Modifier.testTag("place-foreign-hidden:${row.entryKey}"),
                    )
                }
                // Removal last, as on a managed row: the one move that takes the entry off the disk.
                onRemoveOrphan?.let { remove ->
                    IconActionButton(
                        RuleblendTheme.icons.delete,
                        strings.placeRemoveOrphan,
                        remove,
                        Modifier.testTag("place-orphan-remove:${row.entryKey}"),
                    )
                }
                onRemoveForeign?.let { remove ->
                    IconActionButton(
                        RuleblendTheme.icons.delete,
                        strings.placeRemove,
                        remove,
                        Modifier.testTag("place-foreign-remove:${row.entryKey}"),
                    )
                }
            }
        }
    }
    StatusSurface(StatusMark.UNMANAGED) {
        StatusCard(
            header,
            onHeaderClick = onToggleFold.takeIf { text != null },
            body = if (unfolded && text != null) {
                { MonoText(text) }
            } else null,
        )
    }
}

/** Where an entry sits on disk, under its name: the same file is read by two agents, and the path says which. */
@Composable
private fun EntryPath(path: Path) {
    Text(
        path.abbreviateHome(),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
        color = RuleblendTheme.extraColors.faint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A lone-glyph move button: the row is already a tight line, so the words are a tooltip. Its own
 * metrics rather than the compact button's — that one is padded to stand beside a label, and two of
 * them side by side left the pair further apart than the version they sit next to.
 */
@Composable
private fun EndButton(glyph: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    RuleblendTooltip(hint) {
        Box(
            modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = hint, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 3.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One agent's copy of an entry the agents were edited apart on: whose copy it is, what it changed
 * against the library, and the one move that copy alone can make — becoming the next library version.
 * Saving it writes it into the other agents too, which is what the notice above the copies says.
 */
@Composable
private fun CopyRow(
    copy: McpCopy,
    libraryContent: String?,
    strings: Strings,
    nextVersion: String?,
    onSave: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                copy.agentName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ActionButton(PlaceAction.SAVE_AS_VERSION, strings, nextVersion, onClick = onSave)
        }
        if (libraryContent != null) DriftText(libraryContent, copy.text)
        else MonoText(copy.text)
    }
}
