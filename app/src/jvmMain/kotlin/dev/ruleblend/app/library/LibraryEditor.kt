package dev.ruleblend.app.library

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import dev.ruleblend.app.theme.RuleblendOutlinedTextField
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.StatusSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.IconActionButton
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.RuleblendTooltip
import dev.ruleblend.app.theme.MonoFontFamily
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.SquareCheckbox
import dev.ruleblend.app.theme.VersionPill
import dev.ruleblend.app.theme.RuleblendFieldContentPadding
import dev.ruleblend.app.theme.ruleblendFieldColors
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.withVariant
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.model.SUBAGENT_FIELD_TOOLS
import dev.ruleblend.core.model.SUBAGENT_FIELD_MODEL
import dev.ruleblend.core.integration.SubagentFieldSpec
import dev.ruleblend.core.integration.SubagentFieldKind
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import kotlinx.coroutines.launch

@Composable
internal fun FieldLabel(text: String) {
    Text(text.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(bottom = EditorFieldGap))
}

private val EditorFieldGap = 4.dp

/**
 * The right pane. A selected rule owns it; otherwise an editable group shows its form there, so
 * groups are edited in place like rules instead of in a dialog. Everything else gets a hint.
 */
@Composable
internal fun EditorPane(
    model: LibraryModel,
    onDeleteBlock: (Block) -> Unit,
    onDeleteGroup: (Group) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onSplitBlock: (Block) -> Unit,
    onCompare: (CompareSubject) -> Unit,
    modifier: Modifier = Modifier,
    translation: TranslationModel? = null,
    onBlockSaved: suspend (Block) -> Unit = {},
    onSkillSaved: suspend (Skill) -> Unit = {},
) {
    val draft = model.draft
    val skillDraft = model.skillDraft
    val groupDraft = model.groupDraft
    val profileDraft = model.profileDraft
    // A translation belongs to the text it was made from; moving to another object clears it.
    LaunchedEffect(draft?.id, skillDraft?.id, groupDraft?.id, profileDraft?.id) { translation?.reset() }
    when {
        skillDraft != null -> SkillEditor(model, skillDraft, onCompare, modifier, translation, onSkillSaved)
        draft != null -> BlockEditor(model, draft, onDeleteBlock, onSplitBlock, onCompare, modifier, translation, onBlockSaved)
        groupDraft != null -> GroupEditor(model, groupDraft, onDeleteGroup, modifier)
        profileDraft != null -> ProfileEditor(model, profileDraft, onDeleteProfile, modifier)
        else -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                LocalStrings.current.libSelectRule,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkillEditor(
    model: LibraryModel,
    draft: Skill,
    onCompare: (CompareSubject) -> Unit,
    modifier: Modifier = Modifier,
    translation: TranslationModel? = null,
    onSkillSaved: suspend (Skill) -> Unit = {},
) {
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    val imported = draft.source != null
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(draft.name.ifBlank { draft.id }, style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel(strings.fieldName)
                RuleblendOutlinedTextField(
                    value = draft.name,
                    onValueChange = { name -> model.editSkillDraft { it.copy(name = name) } },
                    readOnly = imported,
                    enabled = !imported,
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth().testTag("library-editor-skill-name"),
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(strings.libVersion)
                VersionPill(draft.version)
            }
        }

        Column {
            FieldLabel(strings.fieldDescription)
            RuleblendOutlinedTextField(
                value = draft.description,
                onValueChange = { description -> model.editSkillDraft { it.copy(description = description) } },
                readOnly = imported,
                enabled = !imported,
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("library-editor-skill-description"),
            )
        }

        if (!imported) {
            Text(
                strings.libSkillNameHint,
                style = MaterialTheme.typography.labelSmall,
                color = if (model.skillDraftValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }

        draft.source?.let { source ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FieldLabel(strings.libSkillSource)
                Text(source.repository, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "${source.path} @ ${source.revision.take(12)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = MonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = EditorFieldGap),
            ) {
                FieldLabel("SKILL.md")
                Spacer(Modifier.weight(1f))
                translation?.let {
                    TranslationControls(
                        model = it,
                        content = draft.content,
                        // An imported skill stays byte-identical to its source, so it is read-only.
                        canReplace = !imported,
                        onReplace = { text -> model.editSkillDraft { skill -> skill.copy(content = text) } },
                    )
                }
                IconActionButton(
                    icon = RuleblendTheme.icons.compare,
                    label = strings.compareAction,
                    onClick = { onCompare(CompareSubject.from(draft)) },
                )
            }
            // A skill in the library is a skill under Ruleblend's control, whether or not it was
            // imported: the green mat says that the same way it does on the Places screen.
            ContentWithTranslation(translation, draft.content, StatusMark.MANAGED) { fieldModifier ->
                RuleblendOutlinedTextField(
                    value = draft.content,
                    onValueChange = { content -> model.editSkillDraft { it.copy(content = content) } },
                    readOnly = imported,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = fieldModifier.testTag("library-editor-skill-content"),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (imported) {
                CompactOutlinedButton(onClick = { model.forkSkill(draft) }) { Text(strings.libSkillCreateChanged) }
                Spacer(Modifier.weight(1f))
                CompactButton(onClick = { coroutineScope.launch { model.updateSkill(draft) } }) {
                    Text(strings.libSkillUpdateFromGit)
                }
            } else {
                Spacer(Modifier.weight(1f))
                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            val saved = model.saveSkillDraft()
                            if (saved != null) onSkillSaved(saved)
                        }
                    },
                    enabled = model.skillDirty && model.skillDraftValid,
                    modifier = Modifier.testTag("library-editor-save"),
                ) {
                    Text(strings.actionSave)
                }
            }
        }
    }
}

@Composable
private fun BlockEditor(
    model: LibraryModel,
    draft: Block,
    onDeleteBlock: (Block) -> Unit,
    onSplitBlock: (Block) -> Unit,
    onCompare: (CompareSubject) -> Unit,
    modifier: Modifier = Modifier,
    translation: TranslationModel? = null,
    onSaved: suspend (Block) -> Unit = {},
) {
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    val imported = draft.source != null
    var updating by remember(draft.id) { mutableStateOf(false) }
    var mcpFormValid by remember(draft.id) { mutableStateOf(true) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(draft.name.ifBlank { draft.id }, style = MaterialTheme.typography.titleMedium)
            if (draft.type == BlockType.RULE) {
                FavoriteButton(
                    favorite = draft.favorite,
                    onClick = { model.editDraft { it.copy(favorite = !it.favorite) } },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                FieldLabel(strings.fieldName)
                RuleblendOutlinedTextField(
                    value = draft.name,
                    readOnly = imported,
                    enabled = !imported,
                    onValueChange = { name -> model.editDraft { it.copy(name = name) } },
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth().testTag("library-editor-block-name"),
                )
            }
            Column(Modifier.weight(1f)) {
                FieldLabel(strings.fieldType)
                RuleblendOutlinedTextField(
                    value = draft.type.label(strings),
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    singleLine = true,
                    colors = ruleblendFieldColors(),
                    contentPadding = RuleblendFieldContentPadding,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Column {
            FieldLabel(strings.fieldDescription)
            RuleblendOutlinedTextField(
                value = draft.description,
                readOnly = imported,
                enabled = !imported,
                onValueChange = { description -> model.editDraft { it.copy(description = description) } },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("library-editor-block-description"),
            )
        }

        if (draft.type == BlockType.RULE) {
            HeadingField(
                heading = draft.heading,
                level = draft.headingLevel,
                onHeading = { heading -> model.editDraft { it.copy(heading = heading) } },
                onLevel = { level -> model.editDraft { it.copy(headingLevel = level) } },
                resetKey = draft.id,
            )
            ScopePicker(model, draft)
        }

        if (draft.type == BlockType.SUBAGENT) {
            draft.source?.let { source ->
                Column(Modifier.testTag("library-editor-subagent-source")) {
                    FieldLabel(strings.libSkillSource)
                    Text(source.repository, style = MaterialTheme.typography.bodySmall)
                    Text("${source.assistant} · ${source.path} @ ${source.revision.take(12)}",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (imported) Text(strings.libGitReadOnly, style = MaterialTheme.typography.labelSmall)
            val variantsScroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                SubagentVariantsForm(model, draft,
                    Modifier.padding(end = 12.dp).verticalScroll(variantsScroll))
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(variantsScroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }

        Column {
            FieldLabel(strings.libVersion)
            VersionPill(strings.libVersionHint(draft.version))
        }

        if (draft.type == BlockType.MCP) {
            StatusSurface(StatusMark.MANAGED, modifier = Modifier.weight(1f)) {
                McpConfigForm(
                    draft,
                    model::editDraft,
                    Modifier.fillMaxWidth().weight(1f, fill = false),
                    onFormValidChange = { mcpFormValid = it },
                )
            }
        } else {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = EditorFieldGap),
                ) {
                    FieldLabel(strings.libInstruction)
                    Spacer(Modifier.weight(1f))
                    translation?.let {
                        TranslationControls(
                            model = it,
                            content = draft.content,
                            canReplace = !imported,
                            onReplace = { text -> model.editDraft { block -> block.copy(content = text) } },
                        )
                    }
                    IconActionButton(
                        icon = RuleblendTheme.icons.compare,
                        label = strings.compareAction,
                        onClick = { onCompare(CompareSubject.from(draft)) },
                    )
                }
                ContentWithTranslation(translation, draft.content, StatusMark.MANAGED) { fieldModifier ->
                    RuleblendOutlinedTextField(
                        value = draft.content,
                        readOnly = imported,
                        onValueChange = { content -> model.editDraft { it.copy(content = content) } },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        colors = ruleblendFieldColors(),
                        contentPadding = RuleblendFieldContentPadding,
                        modifier = fieldModifier.testTag("library-editor-block-content"),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CompactOutlinedButton(
                onClick = { onDeleteBlock(draft) },
                contentColor = MaterialTheme.colorScheme.error,
            ) { Text(strings.actionDelete) }
            Spacer(Modifier.weight(1f))
            if (imported) {
                CompactOutlinedButton(
                    onClick = { model.duplicateBlock(draft) },
                    enabled = !updating,
                    modifier = Modifier.testTag("library-editor-subagent-fork"),
                ) { Text(strings.libSkillCreateChanged) }
                CompactButton(
                    onClick = {
                        updating = true
                        coroutineScope.launch {
                            try { model.updateSubagent(draft) } finally { updating = false }
                        }
                    },
                    enabled = !updating,
                    modifier = Modifier.testTag("library-editor-subagent-update"),
                ) { Text(strings.libSkillUpdateFromGit) }
            } else {
                if (draft.type == BlockType.RULE) {
                    // Split reads the saved content, not the draft — a dirty editor would split stale text.
                    CompactOutlinedButton(
                        onClick = { onSplitBlock(draft) },
                        enabled = !model.dirty && draft.content.isNotBlank(),
                        modifier = Modifier.testTag("library-editor-split"),
                    ) { Text(strings.splitAction) }
                }
                CompactOutlinedButton(onClick = { model.duplicateBlock(draft) },
                    modifier = Modifier.testTag("library-editor-duplicate")) { Text(strings.actionDuplicate) }
                val saveEnabled = !model.savingDraft && model.dirty &&
                    (draft.type != BlockType.MCP || mcpDraftValid(draft) && mcpFormValid)
                CompactButton(
                    onClick = {
                        coroutineScope.launch {
                            model.saveDraft()?.let { saved -> onSaved(saved) }
                        }
                    },
                    enabled = saveEnabled,
                    modifier = Modifier.testTag("library-editor-save"),
                ) {
                    if (model.savingDraft) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(strings.actionSave)
                    }
                }
            }
        }
    }
}

/** Same shape as [BlockEditor], for a group: its fields, its membership, then the action row. */
/**
 * Per-assistant fields of one subagent. The definition itself stays portable; only these values are
 * asked for separately, because a model name or a tool list valid in one assistant rarely is in
 * another. An assistant left blank is installed without those fields rather than with a guess.
 */
@Composable
private fun SubagentVariantsForm(model: LibraryModel, draft: Block, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    if (model.subagentAgents.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            FieldLabel(strings.libSubagentVariants)
            Text(
                strings.libSubagentVariantsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        model.subagentAgents.forEach { agent ->
            val variant = draft.variant(agent.agentId)
            val declared = agent.fields.map { it.id }.toSet()
            // A field the assistant's file carried but Ruleblend does not model stays editable
            // under its own name, so adopting a foreign definition never hides part of it.
            val specs = agent.fields + variant.fields.keys.filter { it !in declared }.map { SubagentFieldSpec(it) }
            Column(verticalArrangement = Arrangement.spacedBy(EditorFieldGap)) {
                Text(
                    agent.agentName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                specs.forEach { spec ->
                    RuleblendOutlinedTextField(
                        value = variant[spec.id].orEmpty(),
                        readOnly = draft.source != null,
                        enabled = draft.source == null,
                        onValueChange = { value ->
                            model.editDraft { block ->
                                block.withVariant(agent.agentId, block.variant(agent.agentId).with(spec.id, value))
                            }
                        },
                        placeholder = { Text(spec.placeholder(strings)) },
                        singleLine = true,
                        colors = ruleblendFieldColors(),
                        contentPadding = RuleblendFieldContentPadding,
                        modifier = Modifier.fillMaxWidth().testTag(subagentFieldTag(agent.agentId, spec.id)),
                    )
                }
            }
        }
    }
}

/** Test tag of one per-assistant field: which assistant it tunes and which of its fields. */
internal fun subagentFieldTag(agentId: String, fieldId: String): String = "subagent-field-$agentId-$fieldId"

/** The field's own prompt: its name plus the shape its agent expects, since there is no label slot. */
private fun SubagentFieldSpec.placeholder(strings: Strings): String = when {
    id == SUBAGENT_FIELD_MODEL -> "${strings.libSubagentModel} — ${strings.libSubagentModelHint}"
    id == SUBAGENT_FIELD_TOOLS -> "${strings.libSubagentFieldTools} — ${strings.libSubagentFieldToolsHint}"
    id == "color" -> strings.libSubagentFieldColor
    kind == SubagentFieldKind.LIST -> "${strings.libSubagentFieldCustom(id)} — ${strings.libSubagentFieldToolsHint}"
    else -> strings.libSubagentFieldCustom(id)
}

@Composable
private fun GroupEditor(
    model: LibraryModel,
    draft: Group,
    onDeleteGroup: (Group) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(draft.name.ifBlank { draft.id }, style = MaterialTheme.typography.titleMedium)
        }

        Column {
            FieldLabel(strings.fieldName)
            RuleblendOutlinedTextField(
                value = draft.name,
                onValueChange = { name -> model.editGroupDraft { it.copy(name = name) } },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth().testTag("library-editor-group-name"),
            )
        }

        Column {
            FieldLabel(strings.fieldDescription)
            RuleblendOutlinedTextField(
                value = draft.description,
                onValueChange = { description -> model.editGroupDraft { it.copy(description = description) } },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Column(Modifier.weight(1f)) {
            FieldLabel(strings.libGroupContents)
            // The whole library is listed here, so it has to scroll; the visible bar says so.
            val selectedBlocks = model.blocksInSelectedScope.filter { it.id in draft.blockIds }.sortedByDescending { it.favorite }
            val unselectedBlocks = model.blocksInSelectedScope.filter { it.id !in draft.blockIds }.sortedByDescending { it.favorite }
            val selectedSkills = model.skills.filter { it.id in draft.skillIds }
            val unselectedSkills = model.skills.filter { it.id !in draft.skillIds }
            val listState = rememberLazyListState()
            LaunchedEffect(draft.id) {
                listState.scrollToItem(0)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).testTag("library-group-member-list"),
                ) {
                    item { Text(strings.libRulesLabel.uppercase(), style = SectionHeaderStyle) }
                    items(
                        selectedBlocks.filter { it.type != BlockType.SUBAGENT } +
                            unselectedBlocks.filter { it.type != BlockType.SUBAGENT },
                        key = { it.id },
                    ) { block ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SquareCheckbox(
                                checked = block.id in draft.blockIds,
                                modifier = Modifier.testTag("library-group-member:BLOCK:${block.id}"),
                                onCheckedChange = { checked ->
                                    model.editGroupDraft {
                                        it.copy(
                                            blockIds = if (checked) it.blockIds + block.id else it.blockIds - block.id,
                                        )
                                    }
                                },
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                KindMark(block.type.objectKind())
                                Text(
                                    block.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (block.type == BlockType.RULE && block.favorite) EditorFavoriteMark()
                            }
                        }
                    }
                    item { Text(strings.libSubagents.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(selectedBlocks.filter { it.type == BlockType.SUBAGENT } + unselectedBlocks.filter { it.type == BlockType.SUBAGENT }, key = { "subagent-${it.id}" }) { block ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SquareCheckbox(
                                checked = block.id in draft.blockIds,
                                modifier = Modifier.testTag("library-group-member:SUBAGENT:${block.id}"),
                                onCheckedChange = { checked ->
                                    model.editGroupDraft {
                                        it.copy(blockIds = if (checked) it.blockIds + block.id else it.blockIds - block.id)
                                    }
                                },
                            )
                            KindMark(LibraryObjectKind.SUBAGENT)
                            Text(block.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item { Text(strings.libSkills.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(selectedSkills + unselectedSkills, key = { "skill-${it.id}" }) { skill ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            SquareCheckbox(
                                checked = skill.id in draft.skillIds,
                                modifier = Modifier.testTag("library-group-member:SKILL:${skill.id}"),
                                onCheckedChange = { checked ->
                                    model.editGroupDraft {
                                        it.copy(skillIds = if (checked) it.skillIds + skill.id else it.skillIds - skill.id)
                                    }
                                },
                            )
                            KindMark(LibraryObjectKind.SKILL)
                            Text(
                                skill.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.padding(start = 6.dp).width(6.dp),
                    style = defaultScrollbarStyle(),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CompactOutlinedButton(
                onClick = { onDeleteGroup(draft) },
                contentColor = MaterialTheme.colorScheme.error,
            ) { Text(strings.actionDelete) }
            Spacer(Modifier.weight(1f))
            CompactButton(
                onClick = model::saveGroupDraft,
                enabled = model.groupDirty && draft.name.isNotBlank(),
                modifier = Modifier.testTag("library-editor-save"),
            ) { Text(strings.actionSave) }
        }
    }
}

/**
 * A profile is edited where a group is, but its final section deliberately references groups rather
 * than expanding them. This keeps profiles the one allowed nesting level and makes their saved
 * version describe direct composition rather than a flattened snapshot.
 */
@Composable
private fun ProfileEditor(
    model: LibraryModel,
    draft: Profile,
    onDeleteProfile: (Profile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val listState = rememberLazyListState()
    val directBlocks = model.blocks.filter { it.type != BlockType.SUBAGENT }
    val subagents = model.blocks.filter { it.type == BlockType.SUBAGENT }
    val groups = model.groups.filter { it.id != dev.ruleblend.core.model.ALL_GROUP_ID }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(draft.name.ifBlank { draft.id }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            VersionPill("v${draft.version}")
        }
        Column {
            FieldLabel(strings.fieldName)
            RuleblendOutlinedTextField(
                value = draft.name,
                onValueChange = { name -> model.editProfileDraft { it.copy(name = name) } },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column {
            FieldLabel(strings.fieldDescription)
            RuleblendOutlinedTextField(
                value = draft.description,
                onValueChange = { description -> model.editProfileDraft { it.copy(description = description) } },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(Modifier.weight(1f)) {
            FieldLabel(strings.libProfileContents)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).testTag("library-profile-member-list"),
                ) {
                    item { Text(strings.libRulesLabel.uppercase(), style = SectionHeaderStyle) }
                    items(directBlocks.filter { it.type == BlockType.RULE }, key = { "rule-${it.id}" }) { block ->
                        ProfileMemberRow(block.name, block.id in draft.blockIds, "RULE:${block.id}", LibraryObjectKind.RULE) { checked ->
                            model.editProfileDraft {
                                it.copy(blockIds = if (checked) it.blockIds + block.id else it.blockIds - block.id)
                            }
                        }
                    }
                    item { Text(strings.libMcp.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(directBlocks.filter { it.type == BlockType.MCP }, key = { "mcp-${it.id}" }) { block ->
                        ProfileMemberRow(block.name, block.id in draft.blockIds, "MCP:${block.id}", LibraryObjectKind.MCP) { checked ->
                            model.editProfileDraft {
                                it.copy(blockIds = if (checked) it.blockIds + block.id else it.blockIds - block.id)
                            }
                        }
                    }
                    item { Text(strings.libSubagents.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(subagents, key = { "subagent-${it.id}" }) { block ->
                        ProfileMemberRow(block.name, block.id in draft.subagentIds, "SUBAGENT:${block.id}", LibraryObjectKind.SUBAGENT) { checked ->
                            model.editProfileDraft {
                                it.copy(subagentIds = if (checked) it.subagentIds + block.id else it.subagentIds - block.id)
                            }
                        }
                    }
                    item { Text(strings.libSkills.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(model.skills, key = { "skill-${it.id}" }) { skill ->
                        ProfileMemberRow(skill.name, skill.id in draft.skillIds, "SKILL:${skill.id}", LibraryObjectKind.SKILL) { checked ->
                            model.editProfileDraft {
                                it.copy(skillIds = if (checked) it.skillIds + skill.id else it.skillIds - skill.id)
                            }
                        }
                    }
                    item { Text(strings.libGroups.uppercase(), style = SectionHeaderStyle, modifier = Modifier.padding(top = 6.dp)) }
                    items(groups, key = { "group-${it.id}" }) { group ->
                        ProfileMemberRow(group.name, group.id in draft.groupIds, "GROUP:${group.id}", LibraryObjectKind.GROUP) { checked ->
                            model.editProfileDraft {
                                it.copy(groupIds = if (checked) it.groupIds + group.id else it.groupIds - group.id)
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.padding(start = 6.dp).width(6.dp),
                    style = defaultScrollbarStyle(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CompactOutlinedButton(
                onClick = { onDeleteProfile(draft) },
                contentColor = MaterialTheme.colorScheme.error,
            ) { Text(strings.actionDelete) }
            Spacer(Modifier.weight(1f))
            CompactButton(
                onClick = model::saveProfileDraft,
                enabled = model.profileDirty && draft.name.isNotBlank(),
            ) { Text(strings.actionSave) }
        }
    }
}

@Composable
private fun ProfileMemberRow(
    name: String,
    checked: Boolean,
    id: String,
    kind: LibraryObjectKind,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SquareCheckbox(checked = checked, onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("library-profile-member:$id"))
        KindMark(kind)
        Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ScopePicker(model: LibraryModel, draft: Block) {
    ScopePicker(
        scope = model.draftScope,
        scopes = model.projectScopes,
        onSelect = model::editDraftScope,
        resetKey = draft.id,
    )
}

/** A rule can be pinned without changing its group membership or installation order. */
@Composable
private fun FavoriteButton(favorite: Boolean, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val color = androidx.compose.ui.graphics.Color(0xFFFFC107)
    RuleblendTooltip(if (favorite) strings.libRemoveFromFavorites else strings.libAddToFavorites) {
        Text(
            if (favorite) "★" else "☆",
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp),
        )
    }
}

/** Small marker used in rule lists; the editor keeps the full-size toggle beside the title. */
@Composable
private fun EditorFavoriteMark() {
    Text(
        "★",
        style = MaterialTheme.typography.bodySmall,
        color = androidx.compose.ui.graphics.Color(0xFFFFC107),
    )
}

private fun BlockType.label(strings: Strings): String = when (this) {
    BlockType.RULE -> strings.libTypeRule
    BlockType.MCP -> strings.libMcp
    BlockType.SUBAGENT -> strings.libTypeSubagent
}

/**
 * The markdown heading a rule carries into every file it is installed in. Empty means the rule has
 * none and blends into whatever section surrounds it; the level travels with the text so the same
 * rule renders at the depth its author chose.
 */
@Composable
private fun HeadingField(
    heading: String,
    level: Int,
    onHeading: (String) -> Unit,
    onLevel: (Int) -> Unit,
    resetKey: Any?,
) {
    val strings = LocalStrings.current
    var expanded by remember(resetKey) { mutableStateOf(false) }
    Column {
        FieldLabel(strings.fieldHeading)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RuleblendOutlinedTextField(
                value = heading,
                onValueChange = onHeading,
                placeholder = { Text(strings.fieldHeadingHint) },
                singleLine = true,
                colors = ruleblendFieldColors(),
                contentPadding = RuleblendFieldContentPadding,
                modifier = Modifier.weight(1f),
            )
            Box {
                CompactOutlinedButton(
                    onClick = { expanded = true },
                    enabled = heading.isNotBlank(),
                    modifier = Modifier.width(72.dp),
                ) {
                    Text("H$level", modifier = Modifier.weight(1f))
                    Text("▾")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    (1..6).forEach { depth ->
                        DropdownMenuItem(
                            text = { Text("H$depth") },
                            onClick = { onLevel(depth); expanded = false },
                            contentPadding = CompareMenuItemPadding,
                            modifier = Modifier.height(CompareMenuItemHeight),
                        )
                    }
                }
            }
        }
    }
}
