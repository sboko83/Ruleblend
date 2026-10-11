package dev.ruleblend.app.assistance

import dev.ruleblend.app.library.LibraryModel
import dev.ruleblend.app.library.LibraryObjectKind

/** Only saved user-owned objects; built-ins, package mods, groups and profiles have no launch here. */
fun LibraryModel.assistanceObject(): AssistanceObject? {
    val key = selectedCatalogKey ?: return null
    if (catalog.objects.none { it.key == key && !it.builtIn }) return null
    return when (key.kind) {
        LibraryObjectKind.RULE, LibraryObjectKind.MCP, LibraryObjectKind.SUBAGENT ->
            blocks.find { it.id == key.id }?.let(AssistanceObject::fromSaved)
        LibraryObjectKind.SKILL -> skills.find { it.id == key.id }?.let(AssistanceObject::fromSaved)
        else -> null
    }
}

fun LibraryModel.assistanceDraftDirty(): Boolean = when (selectedCatalogKey?.kind) {
    LibraryObjectKind.RULE, LibraryObjectKind.MCP, LibraryObjectKind.SUBAGENT -> dirty
    LibraryObjectKind.SKILL -> skillDirty
    else -> false
}
