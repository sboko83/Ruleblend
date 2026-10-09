package dev.ruleblend.app.place

import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.mcp.MCP_SERVER_NAME
import dev.ruleblend.mcp.isRuleblendRegistration
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.SubagentFileEntry
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig

/** The preview parts of [file]: parsed segments for files, installed entries for MCP and skills. */
internal fun placePreviewParts(
    model: IntegrationModel,
    file: PlaceFileTab,
    showHidden: Boolean = false,
): List<PlaceFilePart> {
    val segments = model.fileSegments[file.path].orEmpty()
    return when (file.kind) {
        PlaceFileKind.POINTER -> placePointerParts(segments, file.importedName?.let { "@$it" })
        PlaceFileKind.RULES -> placeFileParts(
            segments = segments,
            name = { id -> model.blocks.find { it.id == id }?.name },
            status = { id -> model.statuses[id] },
            libraryContent = { id -> model.blocks.find { it.id == id }?.let { regionFor(it).content } },
        ).withManagedRun()
        PlaceFileKind.MCP -> {
            val rows = placeEntries(
                managedRows = installedRows(
                    items = model.blocks.filter { it.type == BlockType.MCP }.map { Triple(it.id, it.name, it.version.toString()) },
                    statuses = model.mcpStatuses.mapValues { it.value.status },
                    // A server entry is a text like any other, so it unfolds like one — and a drifted
                    // entry is read as a diff against the library instead of a bare "modified".
                    content = { id -> model.mcpInstalledText[id] },
                    libraryContent = { id -> model.blocks.find { it.id == id }?.let(::mcpLibraryText) },
                ),
                entries = mcpEntriesIn(model.mcpEntries, file.path),
            ) { entry ->
                PlaceDiskEntry(
                    name = entry.name,
                    version = null,
                    path = entry.file,
                    subtitle = when (val config = entry.config) {
                        is McpServerConfig.Stdio -> config.command
                        is McpServerConfig.Http -> config.url
                        null -> null
                    },
                    text = entry.text.takeIf(String::isNotBlank),
                    agentId = entry.agentId.takeIf { model.mcpFiles.size > 1 },
                    managedByRuleblend = entry.name == MCP_SERVER_NAME && entry.config?.isRuleblendRegistration() == true,
                )
            }
            listOf(
                PlaceFilePart.Managed(
                    rows = rows.filter { it.origin == PlaceEntryOrigin.MANAGED },
                    additionalRows = rows.filter { it.origin != PlaceEntryOrigin.MANAGED && (showHidden || it.origin != PlaceEntryOrigin.IGNORED) },
                ),
            )
        }
        PlaceFileKind.SKILLS -> {
            val rows = placeEntries(
                managedRows = installedRows(
                    items = model.skills.map { Triple(it.id, it.name, it.version) },
                    statuses = model.skillStatuses.mapValues { it.value.status },
                ),
                entries = skillEntriesIn(model.skillEntries, file.path),
            )
            listOf(
                PlaceFilePart.Managed(
                    rows = rows.filter { it.origin == PlaceEntryOrigin.MANAGED },
                    additionalRows = rows.filter { it.origin != PlaceEntryOrigin.MANAGED && (showHidden || it.origin != PlaceEntryOrigin.IGNORED) },
                ),
            )
        }
        PlaceFileKind.SUBAGENTS -> {
            val rows = placeEntries(
                managedRows = installedRows(
                    items = model.blocks.filter { it.type == BlockType.SUBAGENT }.map { Triple(it.id, it.name, it.version.toString()) },
                    statuses = model.subagentStatuses.mapValues { it.value.status },
                ),
                entries = subagentEntriesIn(model.subagentEntries, file.path),
            ) { subagent: SubagentFileEntry ->
                PlaceDiskEntry(
                    name = subagent.meta?.name ?: subagent.id,
                    version = null,
                    path = subagent.path,
                    subtitle = subagent.meta?.description?.takeIf(String::isNotBlank),
                    text = subagent.text.takeIf(String::isNotBlank),
                )
            }
            listOf(
                PlaceFilePart.Managed(
                    rows = rows.filter { it.origin == PlaceEntryOrigin.MANAGED },
                    additionalRows = rows.filter { it.origin != PlaceEntryOrigin.MANAGED && (showHidden || it.origin != PlaceEntryOrigin.IGNORED) },
                ),
            )
        }
    }
}

/**
 * The library's own text for an MCP block, put through the codec so it is compared against the
 * installed entry in one formatting rather than two — a diff of indentation says nothing.
 */
private fun mcpLibraryText(block: Block): String =
    McpConfigCodec.parse(block.content).getOrNull()?.let(McpConfigCodec::serialize) ?: block.content

/** A file with no managed run still shows an empty one: "nothing installed here" is an answer too. */
private fun List<PlaceFilePart>.withManagedRun(): List<PlaceFilePart> =
    if (any { it is PlaceFilePart.Managed }) this else this + PlaceFilePart.Managed(emptyList())

/**
 * The library kind a tab's rows are. A pointer run holds an import line rather than objects, so it
 * has no rows to act on and falls back to the rules kind it points at.
 */
internal fun PlaceFileKind.objectKind(): LibraryObjectKind = when (this) {
    PlaceFileKind.MCP -> LibraryObjectKind.MCP
    PlaceFileKind.SKILLS -> LibraryObjectKind.SKILL
    PlaceFileKind.SUBAGENTS -> LibraryObjectKind.SUBAGENT
    PlaceFileKind.RULES, PlaceFileKind.POINTER -> LibraryObjectKind.RULE
}

internal fun emptyNote(kind: PlaceTabKind, strings: Strings): String = when (kind) {
    PlaceTabKind.MCP -> strings.placeNoServers
    PlaceTabKind.SKILLS -> strings.placeNoSkills
    PlaceTabKind.SUBAGENTS -> strings.placeNoSubagents
    PlaceTabKind.RULES -> strings.placeNoBlocks
}

internal fun footerNote(file: PlaceFileTab, strings: Strings): String? = when {
    file.kind == PlaceFileKind.POINTER -> strings.placePointerNotice(file.importedName.orEmpty())
    file.kind == PlaceFileKind.RULES && file.mode == TargetOwnershipMode.OWNED -> strings.placeOwnedNotice
    else -> null
}
/** A file whose body is prose the reader may not write in; a config or a folder listing is not. */
internal fun PlaceFileKind.isText(): Boolean =
    this == PlaceFileKind.RULES || this == PlaceFileKind.POINTER

/** What a tab is called: the library's own words, so a place is read in the same terms it is filled from. */
internal fun PlaceTabKind.label(strings: Strings): String = when (this) {
    PlaceTabKind.RULES -> strings.libRulesLabel
    PlaceTabKind.MCP -> strings.libMcp
    PlaceTabKind.SKILLS -> strings.libSkills
    PlaceTabKind.SUBAGENTS -> strings.libSubagents
}

/** Ownership travels on the file, so the mode is read where the file is, beside its name. */
internal fun fileBadge(file: PlaceFileTab, strings: Strings): String? = when (file.kind) {
    PlaceFileKind.POINTER -> strings.placePointerTo(file.importedName.orEmpty())
    PlaceFileKind.RULES -> when (file.mode) {
        TargetOwnershipMode.NONE -> strings.placeModeNone
        TargetOwnershipMode.LEGACY -> strings.placeModeLegacy
        TargetOwnershipMode.PARTIAL -> strings.placeModePartial
        TargetOwnershipMode.OWNED -> strings.placeModeOwned
        null -> null
    }
    else -> null
}
