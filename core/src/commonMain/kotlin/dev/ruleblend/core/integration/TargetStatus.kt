package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Group

/**
 * Rollups over the regions already read from a target, for the UI to render without touching disk.
 * Pure by design: callers pass the installed regions and statuses they loaded once.
 */
object TargetStatus {

    /**
     * The status to show for a whole target: the worst one wins, so a hand edit is never hidden by
     * blocks that happen to be in sync. `SYNCED` when nothing is installed.
     */
    fun worst(statuses: Collection<InstallStatus>): InstallStatus = when {
        InstallStatus.MODIFIED in statuses -> InstallStatus.MODIFIED
        InstallStatus.UPDATE_AVAILABLE in statuses -> InstallStatus.UPDATE_AVAILABLE
        else -> InstallStatus.SYNCED
    }

    /**
     * True when every block of [group] is installed under this group's tag. An empty group counts as
     * not installed — there is nothing to remove.
     */
    fun isGroupInstalled(group: Group, installed: Map<String, ManagedRegion>): Boolean =
        group.blockIds.isNotEmpty() && group.blockIds.all { installed[it]?.group == group.id }

    /**
     * Blocks of [group] that removing the group should take out: only those carrying this group's
     * tag. A block installed on its own, or as part of another group, stays put.
     */
    fun groupBlocksToRemove(group: Group, installed: Map<String, ManagedRegion>): List<String> =
        group.blockIds.filter { installed[it]?.group == group.id }
}
