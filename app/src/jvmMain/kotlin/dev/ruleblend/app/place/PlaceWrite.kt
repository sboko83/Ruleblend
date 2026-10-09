package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.core.integration.InstallStatus

/** One write a Place row offers. [KEEP] is the exception: it settles a drift by leaving it alone. */
enum class PlaceAction { INSTALL, UPDATE, REMOVE, RESTORE, SAVE_AS_VERSION, KEEP }

/**
 * What the palette offers for an object of [kind] in the state [status] reports. `null` means nothing
 * of the object sits here, so the only move is to put it there.
 *
 * A hand-edited object is never offered an install: overwriting a local edit is a decision about the
 * file, and it is taken on the file, where the edit is visible — see [fileRowActions].
 *
 * [installable] is false when the place cannot hold this object at all (an MCP config no agent here
 * can run, a skill no agent here reads); such a row offers nothing rather than failing on click.
 *
 * A group has no status of its own — it reports the worst of its members — so a group with some
 * members here keeps offering the install that completes it. [groupComplete] is what closes that
 * offer, and it is the model's answer, not something a status can express.
 */
fun paletteActions(
    kind: LibraryObjectKind,
    status: InstallStatus?,
    installable: Boolean = true,
    groupComplete: Boolean = false,
): List<PlaceAction> = when {
    !installable -> emptyList()
    status == null -> listOf(PlaceAction.INSTALL)
    kind == LibraryObjectKind.GROUP ->
        if (groupComplete) listOf(PlaceAction.REMOVE) else listOf(PlaceAction.INSTALL, PlaceAction.REMOVE)
    status == InstallStatus.UPDATE_AVAILABLE -> listOf(PlaceAction.UPDATE, PlaceAction.REMOVE)
    else -> listOf(PlaceAction.REMOVE)
}

/**
 * What a row inside a managed region offers. Same moves as the palette plus the drift resolution:
 * take the library version back, keep the edit as the next library version, or keep it here only.
 *
 * Skill and subagent copies resolve drift by restoring or keeping; a rule and an MCP entry can
 * also hand their local text back to the library as a new version.
 *
 * [status] is `null` for an object the file still holds but the library no longer knows: there is
 * nothing to compare it against, so the only honest offer is to take it out.
 */
fun fileRowActions(kind: LibraryObjectKind, status: InstallStatus?): List<PlaceAction> = when (status) {
    null -> listOf(PlaceAction.REMOVE)
    InstallStatus.SYNCED -> listOf(PlaceAction.REMOVE)
    InstallStatus.UPDATE_AVAILABLE -> listOf(PlaceAction.UPDATE, PlaceAction.REMOVE)
    InstallStatus.MODIFIED -> buildList {
        add(PlaceAction.RESTORE)
        if (kind == LibraryObjectKind.RULE || kind == LibraryObjectKind.MCP) add(PlaceAction.SAVE_AS_VERSION)
        add(PlaceAction.KEEP)
        add(PlaceAction.REMOVE)
    }
}

/**
 * Runs [action] on [key] in the selected place through the model's existing write paths — the same
 * `IntegrationService` / `McpInstallService` / `SkillInstallService` calls the checklist and the
 * Library bulk install use. No new write path, so atomicity and region boundaries are unchanged.
 *
 * Returns false when nothing was written: [PlaceAction.KEEP] settles a drift without touching disk,
 * and an object that left the library while the column was open has nothing to install — removing it
 * still works, because the file, not the library, is what the removal reads.
 *
 * [agentId] names the copy a [PlaceAction.SAVE_AS_VERSION] is read from, for an MCP entry whose agent
 * copies were edited apart; elsewhere there is one copy and the parameter stays null.
 */
suspend fun IntegrationModel.performPlaceAction(
    action: PlaceAction,
    key: LibraryObjectKey,
    agentId: String? = null,
): Boolean {
    if (action == PlaceAction.KEEP) return false
    return when (key.kind) {
        LibraryObjectKind.RULE, LibraryObjectKind.MCP -> {
            // An object the library no longer knows still sits in the file, and taking it out is the
            // only move the row offers: it needs the id, not a library copy to compare against.
            val block = blocks.find { it.id == key.id }
                ?: return if (action == PlaceAction.REMOVE) {
                    remove(key.id, mcp = key.kind == LibraryObjectKind.MCP)
                    true
                } else {
                    false
                }
            // The group tag travels with the block: re-installing must not orphan a group member.
            val group = installed[block.id]?.group
            when (action) {
                PlaceAction.INSTALL, PlaceAction.UPDATE -> install(block, group)
                PlaceAction.RESTORE -> install(block, group, force = true)
                PlaceAction.SAVE_AS_VERSION -> acceptLocalChange(block, agentId)
                PlaceAction.REMOVE -> remove(block.id)
                PlaceAction.KEEP -> return false
            }
            true
        }
        LibraryObjectKind.SUBAGENT -> {
            val subagent = blocks.find { it.id == key.id } ?: return false
            when (action) {
                PlaceAction.INSTALL, PlaceAction.UPDATE -> install(subagent)
                PlaceAction.RESTORE -> install(subagent, force = true)
                PlaceAction.REMOVE -> removeSubagent(subagent)
                PlaceAction.SAVE_AS_VERSION, PlaceAction.KEEP -> return false
            }
            true
        }
        LibraryObjectKind.SKILL -> {
            val skill = skills.find { it.id == key.id } ?: return false
            when (action) {
                PlaceAction.INSTALL, PlaceAction.UPDATE -> installSkill(skill)
                PlaceAction.RESTORE -> installSkill(skill, force = true)
                PlaceAction.REMOVE -> removeSkill(skill)
                PlaceAction.SAVE_AS_VERSION, PlaceAction.KEEP -> return false
            }
            true
        }
        LibraryObjectKind.GROUP -> {
            val group = groups.find { it.id == key.id } ?: return false
            when (action) {
                PlaceAction.INSTALL, PlaceAction.UPDATE, PlaceAction.RESTORE -> installGroup(group)
                PlaceAction.REMOVE -> removeGroup(group)
                PlaceAction.SAVE_AS_VERSION, PlaceAction.KEEP -> return false
            }
            true
        }
        LibraryObjectKind.PROFILE -> false
    }
}

/** True when every applicable member of the group is already here, so there is nothing left to add. */
fun IntegrationModel.groupComplete(id: String): Boolean =
    groups.find { it.id == id }?.let { isGroupInstalled(it) } ?: false

/** True when the selected place can hold the object at all; false hides the offer instead of failing it. */
fun IntegrationModel.placeSupports(key: LibraryObjectKey): Boolean = when (key.kind) {
    LibraryObjectKind.MCP -> blocks.find { it.id == key.id }?.let { mcpInstallable(it) } ?: false
    LibraryObjectKind.SUBAGENT -> subagentInstallable()
    LibraryObjectKind.SKILL -> skillInstallable()
    LibraryObjectKind.GROUP -> groups.find { it.id == key.id }?.let { groupInstallable(it) } ?: false
    LibraryObjectKind.RULE -> true
    LibraryObjectKind.PROFILE -> false
}
