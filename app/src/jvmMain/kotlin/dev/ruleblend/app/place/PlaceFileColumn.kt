package dev.ruleblend.app.place

import dev.ruleblend.app.library.KindMark
import dev.ruleblend.app.library.LibraryObjectKind

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ruleblend.app.util.tabSemantics
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.integration.AdoptDialog
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.integration.ProfileAttachMode
import dev.ruleblend.app.integration.UnmanagedFile
import dev.ruleblend.app.library.CompactContextMenu
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.app.library.translationErrorMessage
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.FoldButtons
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.CompactPillButton
import dev.ruleblend.app.theme.RoomyPillPadding
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.NameTag
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.RuleblendConfirmDialog
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.app.theme.StatusCard
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import dev.ruleblend.app.theme.label
import dev.ruleblend.app.theme.mark
import dev.ruleblend.app.util.abbreviateHome
import dev.ruleblend.core.integration.AgentLaunchMode
import dev.ruleblend.core.integration.McpFileEntry
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SubagentFileEntry
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.nextId
import dev.ruleblend.core.model.validSkillName
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * Centre of the Place surface: the files of one place as tabs, and a preview of the selected one.
 * The file is the unit of writing in the architecture, so it is also the unit shown — the whole
 * file readable in one colour, managed regions highlighted with their blocks.
 *
 * Rows inside a managed region carry the writes that belong to a block already here: update it to
 * the library version, resolve a hand edit, or take it out. Installing and adopting hand-written
 * text run from the palette.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceFileColumn(
    model: IntegrationModel,
    focusChanges: Boolean = false,
    rulesExpandedByDefault: Boolean = false,
    skillsExpandedByDefault: Boolean = false,
    subagentsExpandedByDefault: Boolean = false,
    mcpExpandedByDefault: Boolean = false,
    /** Hands a file of this place to the editor. `null` where there is none to hand it to. */
    onEditFile: ((Path) -> Unit)? = null,
    /** Opens a managed library object in its structured editor. */
    onEditBlock: ((LibraryObjectKey) -> Unit)? = null,
    /** `null` where translation is not available, which also keeps UI tests off the system helper. */
    translation: TranslationModel? = null,
    onTabSelected: (PlaceTabKind) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    // Writes read and rewrite files, so they run in a coroutine: a click returns before the disk does.
    val scope = rememberCoroutineScope()
    val target = model.selected ?: return
    val modes = model.ownershipFiles.associate { it.path to it.mode }
    val files = placeFileTabs(
        target = target,
        ownership = { modes[it] ?: TargetOwnershipMode.NONE },
        exists = model.existingFiles::contains,
        mcpFiles = model.mcpFiles,
        skillDirectories = model.skillDirectories,
        subagentDirectories = model.subagentDirectories,
    )
    val tabs = placeTabs(files)
    var selectedKind by remember(target) {
        mutableStateOf(initialPlaceTab(tabs, focusChanges) { file ->
            placeFileMarks(file, placePreviewParts(model, file))
        })
    }
    val tab = tabs.find { it.kind == selectedKind } ?: tabs.firstOrNull() ?: return
    LaunchedEffect(target, tab.kind) { onTabSelected(tab.kind) }
    val tabExpandedByDefault = when (tab.kind) {
        PlaceTabKind.RULES -> rulesExpandedByDefault
        PlaceTabKind.SKILLS -> skillsExpandedByDefault
        PlaceTabKind.SUBAGENTS -> subagentsExpandedByDefault
        PlaceTabKind.MCP -> mcpExpandedByDefault
    }
    // "Keep" writes nothing, so it lives here: the block stays modified, only the prompt is settled.
    // Rows folded against the default, keyed by file and id: the same block can sit in two files of
    // one place, and reading it open in one of them says nothing about the other. Overrides rather
    // than open rows, so the setting decides what a place looks like the moment it is entered.
    var foldOverrides by remember(
        target,
        rulesExpandedByDefault,
        skillsExpandedByDefault,
        subagentsExpandedByDefault,
        mcpExpandedByDefault,
    ) { mutableStateOf(emptySet<String>()) }
    // The two header controls answer about the whole tab, so their answer is kept separately from
    // per-row overrides. A subsequent row click turns that answer into the row's ordinary override.
    var forcedTabUnfolded by remember(target, tab.kind) { mutableStateOf<Boolean?>(null) }
    // Hand-written text taken into the library from its right-click menu, through the same dialog
    // the palette's Found rows use — the text is in front of the user here, so the offer is too.
    var adopting by remember(target) { mutableStateOf<UnmanagedFile?>(null) }
    var restoring by remember(target) { mutableStateOf<PlaceFileTab?>(null) }
    var disowning by remember(target) { mutableStateOf<dev.ruleblend.app.integration.OwnershipFile?>(null) }
    // A foreign skill whose directory name the library already uses: the name is the user's to pick.
    var namingSkill by remember(target) { mutableStateOf<dev.ruleblend.core.integration.SkillDirEntry?>(null) }
    var namingSubagent by remember(target) { mutableStateOf<SubagentFileEntry?>(null) }
    var namingMcp by remember(target) { mutableStateOf<McpFileEntry?>(null) }
    var removingOrphan by remember(target) { mutableStateOf<OrphanRemovalRequest?>(null) }
    var removingForeign by remember(target) { mutableStateOf<ForeignRemovalRequest?>(null) }
    var showHidden by remember(target) { mutableStateOf(false) }
    // The preview is a reading surface first: a file in a language the reader does not write in is
    // read here, so the translation sits beside the text rather than behind an editor. The switch
    // belongs to a file rather than to the tab: a place holds several addresses, and a reader opens
    // the one they are actually reading.
    var translating by remember(target) { mutableStateOf(emptySet<String>()) }
    val handFiles = model.unmanagedFiles + model.referencedFiles
    val sections = tab.sections.map { file -> file to placePreviewParts(model, file, showHidden) }
    val shown = sections.filter { (file, parts) -> placeSectionHasContent(file, parts) }
    val quiet = sections.filterNot { (file, parts) -> placeSectionHasContent(file, parts) }.map { it.first }

    Column(modifier.fillMaxSize()) {
        // Which place is being read. The list on the left answers this only while the open place is
        // in it, and a filter can leave it out; the file view names the place itself.
        PlaceHeader(model, target)
        // Tabs wrap rather than scroll away: a place holds a handful of kinds and each of them
        // must be reachable by eye, which a row cut off at the window edge is not.
        // The active tab's underline lands on the hairline below the strip: the strip and the rule
        // are one control, with nothing between them.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
        ) {
            tabs.forEach { candidate ->
                val holdings = candidate.sections.map { file -> file to placePreviewParts(model, file) }
                TypeTab(
                    kind = candidate.kind,
                    selected = candidate.kind == tab.kind,
                    // The place's dot in the list sums up everything it holds; per tab it says which
                    // kind earned those colours, and the section header says which file.
                    marks = holdings.flatMapTo(mutableSetOf()) { (file, held) -> placeFileMarks(file, held) },
                    count = holdings.fold(PlaceObjectCount(ours = 0, foreign = 0)) { total, (_, held) ->
                        val count = placeObjectCount(held)
                        PlaceObjectCount(total.ours + count.ours, total.foreign + count.foreign)
                    },
                    strings = strings,
                    onClick = { selectedKind = candidate.kind },
                )
            }
        }
        // The tab strip is a control, the text below it is the file: a hairline says where one ends.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // The switch belongs to the tab it hides entries of: a place counts its hidden entries across
        // every kind, and a Skills tab must not offer to unhide an MCP server.
        val hiddenHere = tab.sections.sumOf { file -> placeHiddenCount(placePreviewParts(model, file, showHidden = true)) }
        if (hiddenHere > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
            ) {
                CompactPillButton(
                    onClick = { showHidden = !showHidden },
                    modifier = Modifier.testTag("place-show-hidden"),
                ) {
                    Text(
                        if (showHidden) strings.placeHideHiddenEntries(hiddenHere)
                        else strings.placeShowHiddenEntries(hiddenHere),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        // Keyed by place: the column is the same composable for every place, and carrying one
        // place's scroll offset into the next opens the new file halfway down a text nobody has
        // read yet. A new place is read from its first line.
        val listState = remember(target) { LazyListState() }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp).testTag("place-file-list"),
                // The first row must not touch the tab strip and the last must not sit on the window
                // edge: the file ends, the column does not.
                contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // A kind with nothing anywhere is one answer, not one empty block per address: what
                // the reader needs is that there is nothing, and where it goes when there is something.
                if (shown.isEmpty()) {
                    item("empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PlaceNote(emptyNote(tab.kind, strings))
                            PlaceNote(strings.placeTabWrittenTo(tab.sections.joinToString(", ") { it.title }))
                        }
                    }
                }
                shown.forEachIndexed { fileIndex, (file, fileParts) ->
                    // Only a text has a translation. An MCP entry and a skill directory are read as
                    // what they are — a config and a folder — and offering to translate them would
                    // put a second column beside JSON.
                    val translatable = translation?.takeIf { it.available && file.kind.isText() }
                    val fileTranslator = translatable?.takeIf { file.key in translating }
                    // A title is only a relative label. Different configured roots can each be
                    // shown as `skills/`, so the physical path identifies the LazyColumn row.
                    item("section:${file.key}") {
                        val ownership = model.ownershipFiles.find { it.path == file.path }
                        SectionHeader(
                            file = file,
                            onEdit = onEditFile?.takeIf {
                                file.exists && file.kind in setOf(PlaceFileKind.RULES, PlaceFileKind.MCP)
                            }?.let { edit -> { edit(file.path) } },
                            onExpandAll = {
                                forcedTabUnfolded = true
                            },
                            onCollapseAll = {
                                forcedTabUnfolded = false
                            },
                            // Every address after the first opens with a rule across the column: two
                            // files of one kind are two texts, not one run of blocks.
                            divider = fileIndex > 0,
                            marks = placeFileMarks(file, fileParts),
                            strings = strings,
                            translation = translatable,
                            translating = file.key in translating,
                            onToggleTranslate = {
                                translating =
                                    if (file.key in translating) translating - file.key
                                    else translating + file.key
                            },
                            management = {
                                FileManagementActions(
                                    file = file,
                                    ownership = ownership,
                                    hasBackup = file.path in model.backupRecords,
                                    strings = strings,
                                    onBackup = { scope.launch { model.backupFile(file.path) } },
                                    onRestore = { restoring = file },
                                    onMigrate = ownership?.let { owned ->
                                        { scope.launch { model.migrateOwnership(owned) } }
                                    },
                                    onDisown = ownership?.let { owned -> { disowning = owned } },
                                    onToggleNotice = ownership?.let { owned ->
                                        { scope.launch { model.setOwnershipNotice(owned, !owned.notice) } }
                                    },
                                )
                            },
                        )
                    }
                    // Keyed by position: one file can hold several hand fragments with the same text.
                    fileParts.forEachIndexed { index, part ->
                        item("part:${file.key}:$index") {
                            when (part) {
                                is PlaceFilePart.Hand -> HandText(
                                    text = part.text,
                                    translation = fileTranslator,
                                    // Only a file the model actually read has text to adopt; a preview
                                    // built from segments alone has nothing to hand the dialog.
                                    onSaveToLibrary = handFiles.find { it.path == file.path }
                                        ?.let { found -> { adopting = found } },
                                )
                                // A run Ruleblend wrote but cannot break into blocks is one object:
                                // the whole region is what is in sync, so the whole region gets a mat.
                                // The link this file exists for. Ruleblend wrote the line but does not
                                // own it; the tab's footer already says what this file is.
                                is PlaceFilePart.Pointer -> StatusSurface(mark = StatusMark.MANAGED) {
                                    StatusCard {
                                        MonoText(part.text)
                                    }
                                }
                                is PlaceFilePart.ManagedText -> StatusSurface(mark = StatusMark.MANAGED) {
                                    StatusCard {
                                        Translated(fileTranslator, part.text) { fragment -> MonoText(fragment) }
                                    }
                                }
                                // No mat around the run: each block below carries its own, because
                                // each of them is separately in sync, behind, or edited by hand.
                                is PlaceFilePart.Managed -> Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    val builtInRows = part.additionalRows.filter { it.managedByRuleblend }
                                    val foreignRows = part.additionalRows.filter {
                                        !it.managedByRuleblend && (it.origin == PlaceEntryOrigin.FOREIGN ||
                                            it.origin == PlaceEntryOrigin.IGNORED ||
                                            it.origin == PlaceEntryOrigin.ORPHAN)
                                    }
                                    if (part.rows.isEmpty() && builtInRows.isEmpty()) {
                                        PlaceNote(
                                            if (foreignRows.isNotEmpty()) {
                                                when (file.kind) {
                                                    PlaceFileKind.MCP -> strings.placeNoOurServers(foreignRows.size)
                                                    PlaceFileKind.SKILLS -> strings.placeNoOurSkills(foreignRows.size)
                                                    PlaceFileKind.SUBAGENTS -> strings.placeNoOurSubagents(foreignRows.size)
                                                    PlaceFileKind.RULES, PlaceFileKind.POINTER -> emptyNote(file.kind.tabKind(), strings)
                                                }
                                            } else {
                                                emptyNote(file.kind.tabKind(), strings)
                                            },
                                        )
                                    }
                                    else ReorderableBlocks(
                                        rows = part.rows,
                                        // Only a markdown run has an order to change: MCP entries and skill
                                        // directories are keyed collections, and a pointer holds no blocks.
                                        onReorder = if (file.kind == PlaceFileKind.RULES) {
                                            { order -> scope.launch { model.reorderBlocks(file.path, order) } }
                                        } else null,
                                    ) { row, handle, ends ->
                                        val foldKey = "${file.key}:${row.id}"
                                        BlockRow(
                                            row = row,
                                            kind = file.kind.objectKind(),
                                            fileKey = file.key,
                                            strings = strings,
                                            translation = fileTranslator,
                                            settled = model.driftKept(row.id, row.content.orEmpty()),
                                            handle = handle,
                                            ends = ends,
                                            unfolded = forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides)),
                                            copies = if (file.kind == PlaceFileKind.MCP) model.mcpDriftedCopies(row.id) else emptyList(),
                                            onToggleFold = {
                                                val desired = !(forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides)))
                                                forcedTabUnfolded = null
                                                foldOverrides = if (tabExpandedByDefault != desired) foldOverrides + foldKey else foldOverrides - foldKey
                                            },
                                            onAction = { action ->
                                                scope.launch {
                                                    if (action == PlaceAction.KEEP) model.keepDrift(row.id, row.content.orEmpty())
                                                    else model.performPlaceAction(action, LibraryObjectKey(file.kind.objectKind(), row.id))
                                                }
                                            },
                                            onSaveCopy = { agentId ->
                                                scope.launch {
                                                    model.performPlaceAction(
                                                        PlaceAction.SAVE_AS_VERSION,
                                                        LibraryObjectKey(file.kind.objectKind(), row.id),
                                                        agentId,
                                                    )
                                                }
                                            },
                                            onEdit = onEditBlock
                                                ?.takeIf {
                                                    when (file.kind) {
                                                        PlaceFileKind.RULES -> model.blocks.any { block -> block.id == row.id }
                                                        PlaceFileKind.SKILLS -> model.skills.any { skill -> skill.id == row.id }
                                                        PlaceFileKind.SUBAGENTS -> model.blocks.any { block -> block.id == row.id && block.type == BlockType.SUBAGENT }
                                                        PlaceFileKind.MCP -> model.blocks.any { block -> block.id == row.id && block.type == BlockType.MCP }
                                                        else -> false
                                                    }
                                                }
                                                ?.let { edit ->
                                                    { edit(LibraryObjectKey(file.kind.objectKind(), row.id)) }
                                                },
                                            onRelease = row.takeIf { it.origin == PlaceEntryOrigin.MANAGED }?.let { managed ->
                                                when (file.kind) {
                                                    PlaceFileKind.SKILLS -> model.skills.find { it.id == managed.id }
                                                        ?.let { skill -> { scope.launch { model.releaseSkill(skill) } } }
                                                    PlaceFileKind.SUBAGENTS -> model.blocks.find { it.id == managed.id && it.type == BlockType.SUBAGENT }
                                                        ?.let { subagent -> { scope.launch { model.releaseSubagent(subagent) } } }
                                                    PlaceFileKind.MCP -> model.blocks.find { it.id == managed.id && it.type == BlockType.MCP }
                                                        ?.let { block -> { scope.launch { model.releaseMcp(block) } } }
                                                    else -> null
                                                }
                                            },
                                            editLabel = if (file.kind == PlaceFileKind.SKILLS) {
                                                strings.placeEditInLibrary
                                            } else {
                                                strings.libEditFocus
                                            },
                                            onEditFile = onEditFile
                                                ?.takeIf { file.kind == PlaceFileKind.SUBAGENTS }
                                                ?.takeIf { row.path != null }
                                                ?.let { edit ->
                                                    {
                                                        edit(
                                                            if (file.kind == PlaceFileKind.SKILLS) row.path!!.resolve("SKILL.md")
                                                            else row.path!!,
                                                        )
                                                    }
                                                },
                                        )
                                    }
                                    BuiltInEntrySection(
                                        kind = file.kind.objectKind(),
                                        rows = builtInRows,
                                        strings = strings,
                                        unfolded = { row ->
                                            val foldKey = "${file.key}:builtin:${row.foldId()}"
                                            forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides))
                                        },
                                        onToggleFold = { row ->
                                            val foldKey = "${file.key}:builtin:${row.foldId()}"
                                            val desired = !(forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides)))
                                            forcedTabUnfolded = null
                                            foldOverrides = if (tabExpandedByDefault != desired) foldOverrides + foldKey else foldOverrides - foldKey
                                        },
                                    )
                                    ForeignEntrySection(
                                        kind = file.kind.objectKind(),
                                        rows = foreignRows,
                                        strings = strings,
                                        unfolded = { row ->
                                            val foldKey = "${file.key}:foreign:${row.foldId()}"
                                            forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides))
                                        },
                                        onToggleFold = { row ->
                                            val foldKey = "${file.key}:foreign:${row.foldId()}"
                                            val desired = !(forcedTabUnfolded ?: (tabExpandedByDefault != (foldKey in foldOverrides)))
                                            forcedTabUnfolded = null
                                            foldOverrides = if (tabExpandedByDefault != desired) foldOverrides + foldKey else foldOverrides - foldKey
                                        },
                                        onSaveToLibrary = when (file.kind) {
                                            PlaceFileKind.SKILLS -> {
                                            { row ->
                                                row.path?.let(model::foreignSkillEntry)?.let { entry ->
                                                    // A free name is saved as it is; a taken one is asked
                                                    // about, because a silent rename shelves the skill in
                                                    // the library under a name nobody chose.
                                                    if (validSkillName(entry.id) && model.skills.none { it.id == entry.id }) {
                                                        scope.launch { model.adoptForeignSkill(target, entry, entry.id) }
                                                    } else {
                                                        namingSkill = entry
                                                    }
                                                }
                                            }
                                            }
                                            PlaceFileKind.SUBAGENTS -> {
                                                { row ->
                                                    row.path?.let(model::foreignSubagentEntry)?.takeIf { it.meta != null }?.let { entry ->
                                                        if (model.blocks.none { it.id == entry.id }) {
                                                            scope.launch { model.adoptForeignSubagent(target, entry, entry.id) }
                                                        } else {
                                                            namingSubagent = entry
                                                        }
                                                    }
                                                }
                                            }
                                            PlaceFileKind.MCP -> {
                                                { row ->
                                                    row.path?.let { path -> model.foreignMcpEntry(path, row.name) }
                                                        ?.takeIf { it.config != null }
                                                        ?.let { entry ->
                                                            if (model.blocks.none { it.id == entry.name }) {
                                                                scope.launch { model.adoptForeignMcp(target, entry, entry.name) }
                                                            } else {
                                                                namingMcp = entry
                                                            }
                                                        }
                                                }
                                            }
                                            else -> null
                                        },
                                        // A foreign entry named like a library object is taken under that
                                        // object as it is; the section then hides Save, which would only
                                        // shelve a second copy.
                                        onTakeOwnership = when (file.kind) {
                                            PlaceFileKind.SKILLS -> { row ->
                                                val entry = row.path?.let(model::foreignSkillEntry)
                                                val skill = model.skills.find { it.id == row.id }
                                                if (entry != null && skill != null) {
                                                    { scope.launch { model.takeForeignSkill(target, entry, skill) } }
                                                } else null
                                            }
                                            PlaceFileKind.SUBAGENTS -> { row ->
                                                val entry = row.path?.let(model::foreignSubagentEntry)?.takeIf { it.meta != null }
                                                val subagent = model.blocks.find { it.id == row.id && it.type == BlockType.SUBAGENT }
                                                if (entry != null && subagent != null) {
                                                    { scope.launch { model.takeForeignSubagent(target, entry, subagent) } }
                                                } else null
                                            }
                                            PlaceFileKind.MCP -> { row ->
                                                val entry = row.path?.let { path -> model.foreignMcpEntry(path, row.name) }?.takeIf { it.config != null }
                                                val block = model.blocks.find { it.id == row.id && it.type == BlockType.MCP }
                                                if (entry != null && block != null) {
                                                    { scope.launch { model.takeForeignMcp(target, entry, block) } }
                                                } else null
                                            }
                                            else -> null
                                        },
                                        canSaveToLibrary = { row ->
                                            when (file.kind) {
                                                PlaceFileKind.SUBAGENTS -> row.path?.let(model::foreignSubagentEntry)?.meta != null
                                                PlaceFileKind.MCP -> row.path?.let { model.foreignMcpEntry(it, row.name) }?.config != null
                                                else -> true
                                            }
                                        },
                                        onRestoreOrphan = when (file.kind) {
                                            PlaceFileKind.SKILLS -> { row ->
                                                row.path?.let(model::orphanSkillEntry)?.let { entry ->
                                                    scope.launch { model.restoreOrphanSkill(entry) }
                                                }
                                            }
                                            PlaceFileKind.SUBAGENTS -> { row ->
                                                row.path?.let(model::orphanSubagentEntry)?.let { entry ->
                                                    scope.launch { model.restoreOrphanSubagent(entry) }
                                                }
                                            }
                                            PlaceFileKind.MCP -> { row ->
                                                row.path?.let { path -> model.orphanMcpEntry(path, row.name) }?.let { entry ->
                                                    scope.launch { model.restoreOrphanMcp(entry) }
                                                }
                                            }
                                            else -> null
                                        },
                                        onRemoveOrphan = { row ->
                                            row.entryKey?.let { key ->
                                                removingOrphan = OrphanRemovalRequest(file.kind.objectKind(), key, row.name)
                                            }
                                        },
                                        onRemoveForeign = if (file.kind in setOf(PlaceFileKind.SKILLS, PlaceFileKind.MCP)) {
                                            { row ->
                                                row.entryKey?.let { key ->
                                                    removingForeign = ForeignRemovalRequest(
                                                        file.kind.objectKind(),
                                                        key,
                                                        row.name,
                                                        row.path?.abbreviateHome().orEmpty(),
                                                    )
                                                }
                                            }
                                        } else null,
                                        onSetHidden = if (file.kind != PlaceFileKind.RULES && file.kind != PlaceFileKind.POINTER) {
                                            { row, hidden ->
                                                row.entryKey?.let { key ->
                                                    scope.launch {
                                                        if (hidden) model.hideForeignEntry(key) else model.showForeignEntry(key)
                                                    }
                                                }
                                            }
                                        } else null,
                                        onEditFile = onEditFile
                                            ?.takeIf { file.kind in setOf(PlaceFileKind.SKILLS, PlaceFileKind.SUBAGENTS) }
                                            ?.let { edit ->
                                                { row ->
                                                    row.path?.let { path ->
                                                        edit(if (file.kind == PlaceFileKind.SKILLS) path.resolve("SKILL.md") else path)
                                                    }
                                                }
                                            },
                                    )
                                }
                            }
                        }
                    }
                    footerNote(file, strings)?.let { note ->
                        item("footer:${file.title}") { PlaceNote(note) }
                    }
                }
                // The addresses that hold nothing keep their line: they are where the next install
                // lands, and a reader who cannot see them cannot tell an empty file from no file.
                if (shown.isNotEmpty() && quiet.isNotEmpty()) {
                    item("quiet") {
                        PlaceNote(strings.placeTabNothingIn(quiet.joinToString(", ") { it.title }))
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.padding(vertical = 2.dp),
                style = defaultScrollbarStyle(),
            )
        }
    }

    adopting?.let { file ->
        // Read once: `nameFormat` parses the config file, and the dialog recomposes on every keystroke.
        val nameFormat = remember(file) { model.nameFormat }
        AdoptDialog(
            file = file,
            targetName = target.name,
            libraryBlocks = model.allLibraryBlocks.filter { it.type == dev.ruleblend.core.model.BlockType.RULE },
            nameFormat = nameFormat,
            scan = { text -> model.scanAdopt(file, text) },
            translation = translation,
            projectScopes = model.projectScopes,
            initialScope = model.selectedProjectScope(),
            onDismiss = { adopting = null },
            onAdopt = { name, description, replace, adoptScope ->
                scope.launch { model.adopt(file, name, description, replace, adoptScope) }
            },
            onBatchAdopt = { candidates, description, replace, adoptScope ->
                scope.launch { model.batchAdopt(file, candidates, description, replace, adoptScope) }
            },
        )
    }

    restoring?.let { file ->
        RuleblendConfirmDialog(
            text = strings.placeRestoreConfirm(file.title),
            confirmLabel = strings.placeRestore,
            dismissLabel = strings.actionCancel,
            onDismiss = { restoring = null },
            onConfirm = { scope.launch { model.restoreFile(file.path) } },
        )
    }

    disowning?.let { file ->
        RuleblendConfirmDialog(
            text = strings.placeDisownConfirm(file.name),
            confirmLabel = strings.placeDisown,
            dismissLabel = strings.actionCancel,
            onDismiss = { disowning = null },
            onConfirm = { scope.launch { model.disown(file) } },
            // The text stays where it is; only the markers go.
            destructive = false,
        )
    }

    namingSkill?.let { entry ->
        SkillNameDialog(
            entry = entry,
            taken = model.skills.mapTo(mutableSetOf()) { it.id },
            onDismiss = { namingSkill = null },
            onConfirm = { id ->
                namingSkill = null
                scope.launch { model.adoptForeignSkill(target, entry, id) }
            },
        )
    }

    namingSubagent?.let { entry ->
        SubagentNameDialog(
            entry = entry,
            taken = model.blocks.mapTo(mutableSetOf()) { it.id },
            onDismiss = { namingSubagent = null },
            onConfirm = { id ->
                namingSubagent = null
                scope.launch { model.adoptForeignSubagent(target, entry, id) }
            },
        )
    }

    namingMcp?.let { entry ->
        McpNameDialog(
            entry = entry,
            taken = model.blocks.mapTo(mutableSetOf()) { it.id },
            onDismiss = { namingMcp = null },
            onConfirm = { id ->
                namingMcp = null
                scope.launch { model.adoptForeignMcp(target, entry, id) }
            },
        )
    }

    removingOrphan?.let { request ->
        RuleblendConfirmDialog(
            text = strings.placeRemoveOrphanConfirm(request.name),
            confirmLabel = strings.placeRemoveOrphan,
            dismissLabel = strings.actionCancel,
            onDismiss = { removingOrphan = null },
            onConfirm = {
                scope.launch { model.removeOrphan(request.kind, request.entryKey) }
                removingOrphan = null
            },
        )
    }

    removingForeign?.let { request ->
        RuleblendConfirmDialog(
            text = strings.placeRemoveForeignConfirm(request.name, request.path),
            confirmLabel = strings.placeRemove,
            dismissLabel = strings.actionCancel,
            onDismiss = { removingForeign = null },
            onConfirm = {
                scope.launch { model.removeForeign(request.kind, request.entryKey) }
                removingForeign = null
            },
        )
    }
}

private data class OrphanRemovalRequest(
    val kind: dev.ruleblend.app.library.LibraryObjectKind,
    val entryKey: String,
    val name: String,
)

private data class ForeignRemovalRequest(
    val kind: dev.ruleblend.app.library.LibraryObjectKind,
    val entryKey: String,
    val name: String,
    val path: String,
)

/**
 * The name a foreign skill will carry in the library. Only a taken name gets here: the suggestion is
 * the next free one, and the field is offered so the object is shelved under a name its owner chose.
 */
@Composable
private fun SkillNameDialog(
    entry: dev.ruleblend.core.integration.SkillDirEntry,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember(entry.path) { mutableStateOf(nextId(entry.id, taken.toList())) }
    val free = validSkillName(name) && name !in taken
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.placeSkillNameTaken(entry.id),
        confirmLabel = strings.actionAdopt,
        confirmEnabled = free,
        dismissLabel = strings.actionCancel,
        onConfirm = { onConfirm(name) },
    ) {
        RuleblendOutlinedTextField(
            value = name,
            onValueChange = { name = it.trim() },
            singleLine = true,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().testTag("place-skill-name"),
        )
        if (!free) {
            Text(
                strings.placeSkillNameHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubagentNameDialog(
    entry: SubagentFileEntry,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var id by remember(entry.path) { mutableStateOf(nextId(entry.id, taken.toList())) }
    val free = id.isNotBlank() && id !in taken
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.placeSubagentNameTaken(entry.id),
        confirmLabel = strings.actionAdopt,
        confirmEnabled = free,
        dismissLabel = strings.actionCancel,
        onConfirm = { onConfirm(id) },
    ) {
        RuleblendOutlinedTextField(
            value = id,
            onValueChange = { id = it.trim() },
            singleLine = true,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().testTag("place-subagent-name"),
        )
        if (!free) {
            Text(
                strings.placeSubagentNameHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun McpNameDialog(
    entry: McpFileEntry,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var id by remember(entry.file, entry.name) { mutableStateOf(nextId(entry.name, taken.toList())) }
    val free = id.isNotBlank() && id !in taken
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.placeMcpNameTaken(entry.name),
        confirmLabel = strings.actionAdopt,
        confirmEnabled = free,
        dismissLabel = strings.actionCancel,
        onConfirm = { onConfirm(id) },
    ) {
        RuleblendOutlinedTextField(
            value = id,
            onValueChange = { id = it.trim() },
            singleLine = true,
            colors = ruleblendFieldColors(),
            contentPadding = RuleblendFieldContentPadding,
            modifier = Modifier.fillMaxWidth().testTag("place-mcp-name"),
        )
        if (!free) {
            Text(
                strings.placeMcpNameHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Names the open place above its tabs: the name the list on the left shows, and under it the path
 * that place actually is. The left column can be filtered down to something else, and a place read
 * without knowing which one it is is a place edited by accident.
 *
 * A project also carries its agents here, on the same line as its name: this screen is where a
 * checkout is set up, and the next thing done with a set-up checkout is work in it.
 */
@Composable
private fun PlaceHeader(model: IntegrationModel, target: Target) {
    var attachingProfile by remember(target) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        ) {
            // The place is what the whole column is about, so its name is the page title and its
            // address the line under it rather than two lines of the same weight.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                placeLocation(target)?.let { location ->
                    Text(
                        location,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        color = RuleblendTheme.extraColors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (target is ProjectTarget) {
                // Rechecked on every rescan; writes refuse a missing folder, this only says why.
                val missing = remember(target, model.existingFiles) { !Files.isDirectory(target.dir) }
                if (missing) {
                    Text(
                        LocalStrings.current.placeProjectMissing,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("place-project-missing"),
                    )
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ProjectAssistants(model, target)
                    AgentLaunchers(model)
                    ProjectProfileChips(model, target, onAttach = { attachingProfile = true })
                }
            }
        }
    }
    if (attachingProfile && target is ProjectTarget) {
        ProfileAttachDialog(
            model,
            target,
            onAttach = { profile, mode -> scope.launch { model.attachProfile(target, profile, mode) } },
            onDismiss = { attachingProfile = false },
        )
    }
}

/** Attached project modes belong beside the project name, not in the install palette. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectProfileChips(model: IntegrationModel, target: ProjectTarget, onAttach: () -> Unit) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val attached = model.profileBindings.mapNotNull { binding ->
        model.profiles.find { it.id == binding.id }?.let { profile -> profile to binding.active }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        HeaderRowLabel(strings.placeProfiles)
        attached.forEach { (profile, active) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RuleblendTooltip(strings.placeProfileToggleHint(profile.name)) {
                    CompactPillButton(
                        onClick = { scope.launch { model.setProfileActive(target, profile.id, !active) } },
                        contentPadding = RoomyPillPadding,
                        modifier = Modifier.testTag("place-profile-${profile.id}"),
                    ) {
                        KindMark(LibraryObjectKind.PROFILE)
                        Spacer(Modifier.width(6.dp))
                        Text((if (active) "✓ " else "") + profile.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                // Attaching must not be a one-way door: without this the chip can only be toggled,
                // and a project keeps every profile it was ever given.
                RuleblendTooltip(strings.placeDetachProfile(profile.name)) {
                    CompactPillButton(
                        onClick = { scope.launch { model.detachProfile(target, profile.id) } },
                        contentPadding = RoomyPillPadding,
                        modifier = Modifier.testTag("place-profile-detach-${profile.id}"),
                    ) {
                        Text("×", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        CompactPillButton(onClick = onAttach, contentPadding = RoomyPillPadding, modifier = Modifier.testTag("place-profile-add")) {
            Text("+ ${strings.placeAddProfile}", style = MaterialTheme.typography.labelMedium)
        }
    }
    // The switches are here, so the reread warning belongs here too, not only on Home.
    val restartAgents = if (attached.isEmpty()) emptyList() else model.profileRestartAgents(target)
    if (restartAgents.isNotEmpty()) {
        Text(
            strings.homeProfilesRestartHint(restartAgents.joinToString(", ")),
            style = MaterialTheme.typography.labelSmall,
            color = RuleblendTheme.extraColors.faint,
            modifier = Modifier.fillMaxWidth().testTag("place-profile-restart-hint"),
        )
    }
}

/** Selects an unattached profile, then makes the base-preserving or base-replacing choice explicit. */
@Composable
private fun ProfileAttachDialog(
    model: IntegrationModel,
    target: ProjectTarget,
    onAttach: (Profile, ProfileAttachMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val profiles = model.attachableProfiles(target)
    var selected by remember(target, profiles) { mutableStateOf<Profile?>(profiles.firstOrNull()) }
    val base = model.baseProfileItems(target)
    RuleblendDialog(
        onDismiss = onDismiss,
        title = strings.placeAttachProfile,
        text = if (profiles.isEmpty()) strings.placeAttachNoProfiles else null,
        confirmLabel = strings.placeAttachReplace,
        alternativeLabel = strings.placeAttachMerge,
        confirmEnabled = selected != null,
        destructive = true,
        dismissLabel = strings.actionCancel,
        onAlternative = {
            selected?.let { profile -> onAttach(profile, ProfileAttachMode.MERGE) }
            onDismiss()
        },
        onConfirm = {
            selected?.let { profile -> onAttach(profile, ProfileAttachMode.REPLACE) }
            onDismiss()
        },
    ) {
        if (profiles.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.forEach { profile ->
                    CompactPillButton(
                        onClick = { selected = profile },
                        modifier = Modifier.testTag("place-profile-choice-${profile.id}"),
                    ) {
                        KindMark(LibraryObjectKind.PROFILE)
                        Spacer(Modifier.width(6.dp))
                        Text((if (selected?.id == profile.id) "✓ " else "") + profile.name)
                    }
                }
            }
            Text(
                if (base.isEmpty()) strings.placeReplaceBaseEmpty else strings.placeReplaceBaseHint(base.size),
                style = MaterialTheme.typography.bodySmall,
            )
            if (base.isNotEmpty()) {
                Text(base.joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall, color = RuleblendTheme.extraColors.faint)
            }
        }
    }
}

/**
 * One button per agent that can actually be started on this machine, in the folder of the open
 * project. A click starts a fresh session; the right-click menu offers the agent's own way back
 * into the last one, for the agents that have one.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentLaunchers(model: IntegrationModel) {
    val strings = LocalStrings.current
    // Probing every CLI spawns a login shell, so it happens once per run and off the UI thread.
    LaunchedEffect(Unit) { model.refreshCliAgents() }
    if (model.cliAgents.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderRowLabel(strings.placeLaunch)
        model.cliAgents.forEach { agent ->
            val menu = buildList {
                add(strings.placeLaunchNew(agent.name) to { model.launchCli(agent.id, AgentLaunchMode.NEW) })
                if (agent.resumable) {
                    add(strings.placeLaunchResume(agent.name) to { model.launchCli(agent.id, AgentLaunchMode.RESUME) })
                }
            }
            CompactContextMenu(menu) {
                RuleblendTooltip(
                    if (agent.resumable) strings.placeLaunchHintResumable(agent.name) else strings.placeLaunchHint(agent.name),
                ) {
                    CompactPillButton(
                        onClick = { model.launchCli(agent.id, AgentLaunchMode.NEW) },
                        contentPadding = RoomyPillPadding,
                        modifier = Modifier.testTag("place-launch-${agent.id}"),
                    ) {
                        Text(agent.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Names a row of header pills, set back so the pills are what the eye lands on. */
@Composable
private fun HeaderRowLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = RuleblendTheme.extraColors.faint,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** Where a place is on disk: a project is its folder, an agent global is its assistant directory. */
internal fun placeLocation(target: Target): String? = when (target) {
    is ProjectTarget -> target.dir.abbreviateHome()
    else -> target.files().firstOrNull()?.let { file ->
        (file.parent ?: file).abbreviateHome()
    }
}

@Composable
private fun TypeTab(
    kind: PlaceTabKind,
    selected: Boolean,
    marks: Set<StatusMark>,
    count: PlaceObjectCount,
    strings: Strings,
    onClick: () -> Unit,
) {
    // The underline marks the open tab; the label only brightens, so the accent is said once.
    val foreground =
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    val split = kind == PlaceTabKind.SKILLS || kind == PlaceTabKind.SUBAGENTS || kind == PlaceTabKind.MCP
    val countLabel = if (split) strings.placeObjectCount(count.ours, count.foreign) else count.total.toString()
    val description = buildList {
        add(kind.label(strings))
        add(countLabel)
        if (split) add(strings.placeObjectCountHint(count.ours, count.foreign))
        if (marks.isNotEmpty()) add(marks.label(strings))
    }.joinToString(", ")
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .testTag("place-tab-${kind.name.lowercase()}")
            .clickable(role = Role.Tab, onClick = onClick)
            .tabSemantics(description, selected)
            .padding(top = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            if (marks.isNotEmpty()) {
                val label = marks.label(strings)
                RuleblendTooltip(label) {
                    StatusDot(marks)
                }
            }
            Text(
                kind.label(strings),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                ),
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The count answers the tab before it is opened, zero included: "nothing here" is the answer
            // an empty tab used to cost a click to get. A tab that also lists what Ruleblend did not
            // install says both numbers — "ours/the rest" — and spells them out in the tooltip: the strip
            // is read at a glance, and two words per tab is a sentence nobody reads twice.
            val counter = @Composable {
                Text(
                    countLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = RuleblendTheme.extraColors.faint,
                )
            }
            if (split) RuleblendTooltip(strings.placeObjectCountHint(count.ours, count.foreign)) { counter() }
            else counter()
        }
        if (selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/**
 * The head of one address inside a tab: which file this text is in, how much of it Ruleblend owns,
 * and the status of what it holds. This is where the file name lives now — it is read while tracing
 * an edit or deciding where an install lands, not while asking what a place has.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SectionHeader(
    file: PlaceFileTab,
    marks: Set<StatusMark>,
    strings: Strings,
    /** Opens this file in the editor; `null` when there is no file on disk to open yet. */
    onEdit: (() -> Unit)? = null,
    /** Opens every folded entry of this tab. */
    onExpandAll: (() -> Unit)? = null,
    /** Folds every entry of this tab. */
    onCollapseAll: (() -> Unit)? = null,
    /** A hairline above the header, for every address but the first one of the tab. */
    divider: Boolean = false,
    translation: TranslationModel? = null,
    translating: Boolean = false,
    onToggleTranslate: () -> Unit = {},
    management: (@Composable () -> Unit)? = null,
) {
    if (divider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    val icons = RuleblendTheme.icons
    val separateTitle = file.kind.tabKind() == PlaceTabKind.RULES
    val title: @Composable () -> Unit = {
        // The file heads everything below it, so it reads as a heading: its state, its name in the
        // page's own type rather than as a path, and how much of it Ruleblend owns.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = if (separateTitle) Modifier.fillMaxWidth() else Modifier,
        ) {
            if (marks.isNotEmpty()) {
                val label = marks.label(strings)
                RuleblendTooltip(label) {
                    StatusDot(marks, Modifier.semantics { contentDescription = label })
                }
            }
            Text(
                file.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            fileBadge(file, strings)?.let { label -> NameTag(label) }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = if (divider) 8.dp else 0.dp),
    ) {
        if (separateTitle) title()
        // Rules keep their management links below the title. Other tabs put the title beside the
        // actions, which wrap onto another line when the column is too narrow.
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().testTag("place-file-controls:${file.path}"),
        ) {
            Box {
                if (separateTitle) management?.invoke() else title()
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                // The two folds are one control and sit tighter to each other than to the writes.
                if (onExpandAll != null || onCollapseAll != null) {
                    FoldButtons(onExpandAll, onCollapseAll, strings.paletteExpandAll,
                        strings.paletteCollapseAll, "place")
                }
                // Every address of a place is a file on disk, whoever owns what is in it. The palette
                // offers an editor only for text still left to adopt, so a place whose every file is
                // managed had no way to a manual fix at all — this is that way, and it is on the file.
                onEdit?.let { edit ->
                    val extras = RuleblendTheme.extraColors
                    IconActionButton(
                        icon = icons.file,
                        label = strings.intEditFile,
                        onClick = edit,
                        modifier = Modifier.testTag("place-file-edit:${file.path}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = extras.actionPrimary,
                            contentColor = extras.onActionPrimary,
                        ),
                    )
                }
                // On while the second column is open: the picture stays the same, so the colour
                // is what says the switch is on.
                translation?.let {
                    IconActionButton(
                        icon = icons.translate,
                        label = if (translating) strings.translateHide else strings.translateShow,
                        onClick = onToggleTranslate,
                        modifier = Modifier.testTag("place-file-translate:${file.path}"),
                        contentColor = if (translating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        // Without this the fragments keep saying "Translating..." forever: the failure is reported
        // per request and there is no panel here to carry it.
        if (translating) {
            translation?.error?.let { failure ->
                Text(
                    translationErrorMessage(failure, strings),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Actions that operate on the physical text file, kept on that file's own header. */
@Composable
private fun FileManagementActions(
    file: PlaceFileTab,
    ownership: dev.ruleblend.app.integration.OwnershipFile?,
    hasBackup: Boolean,
    strings: Strings,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onMigrate: (() -> Unit)?,
    onDisown: (() -> Unit)?,
    onToggleNotice: (() -> Unit)?,
) {
    // Directories and MCP configs use their own ownership/state services. This task restores only
    // the whole text-file operations that core already exposes for an instruction file.
    if (!file.kind.isText()) return
    val links = buildList {
        if (file.exists) {
            add(FileLink(
                if (hasBackup) strings.placeBackupUpdate else strings.placeBackup,
                "place-backup-${file.key}",
                onBackup,
                if (hasBackup) strings.placeBackupUpdateHint else strings.placeBackupHint,
            ))
        }
        if (hasBackup) add(FileLink(strings.placeRestore, "place-restore-${file.key}", onRestore, strings.placeRestoreHint))
        when (ownership?.mode) {
            TargetOwnershipMode.LEGACY -> onMigrate?.let { add(FileLink(strings.placeMigrateLegacy, "place-migrate-${file.key}", it)) }
            TargetOwnershipMode.PARTIAL, TargetOwnershipMode.OWNED -> {
                onToggleNotice?.let {
                    add(FileLink(
                        if (ownership.notice) strings.placeHideNotice else strings.placeShowNotice,
                        "place-notice-${file.key}",
                        it,
                        if (ownership.notice) strings.placeHideNoticeHint else strings.placeShowNoticeHint,
                    ))
                }
                onDisown?.let { add(FileLink(strings.placeDisown, "place-disown-${file.key}", it, strings.placeDisownHint)) }
            }
            null, TargetOwnershipMode.NONE -> Unit
        }
    }
    if (links.isEmpty()) return
    // Links rather than buttons: none of them touches the text being read, and a hairline between
    // them keeps four quiet words from running together into one phrase.
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEachIndexed { index, link ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .width(1.dp)
                        .height(14.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            RuleblendTooltip(link.hint) {
                CompactGhostButton(onClick = link.onClick, modifier = Modifier.testTag(link.tag)) {
                    Text(link.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private class FileLink(val label: String, val tag: String, val onClick: () -> Unit, val hint: String = "")
