package dev.ruleblend.app.library

import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.mark
import dev.ruleblend.core.integration.BlockConflict
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import java.nio.file.Path

enum class LibraryPlaceKind { AGENT, PROJECT }

/** One installed copy of a library object: its status plus the "two agents disagree" marker. */
data class LibraryInstall(val status: InstallStatus, val conflict: Boolean = false)

/**
 * State of one instruction file of a place, as the cross-place scan read it. [unmanagedLines] counts
 * hand-written lines outside the managed region — what adopting would take into the library; a pure
 * `@import` pointer reports 0, because it has nothing to adopt.
 */
data class PlaceFile(
    val path: Path,
    val name: String,
    /** False for an address the place could write to but has nothing at yet. */
    val exists: Boolean,
    val mode: TargetOwnershipMode,
    val unmanagedLines: Int,
    val drift: Boolean,
    /** True when this is the agent redirect's bare `@target` line, not user-authored content. */
    val isPointer: Boolean = false,
    /** Hand-edited blocks of this file, as core classified them; the input Resolve works from. */
    val conflicts: List<BlockConflict> = emptyList(),
)

/**
 * Everything one place holds from the library, plus the state of its files. Produced by a scan,
 * never by the UI: this is the single cross-place reading of disk, shared by Library usage and the
 * Home attention feed, so the two can never disagree about what a place contains.
 */
data class LibraryPlaceUsage(
    val id: String,
    val name: String,
    val kind: LibraryPlaceKind,
    val installs: Map<LibraryObjectKey, LibraryInstall>,
    val files: List<PlaceFile> = emptyList(),
    /**
     * Whether any agent of this place reads a skills directory. A place without one can never hold a
     * skill, which is not the same as holding none: Coverage shows it as out of scope, not as a gap.
     */
    val supportsSkills: Boolean = true,
    /** MCP entries are out of scope when no enabled agent of this place reads an MCP configuration. */
    val supportsMcp: Boolean = true,
    /** A global-only subagent adapter does not make project definitions applicable. */
    val supportsSubagents: Boolean = true,
)

/** One row of the inspector's "installed in" list. */
data class LibraryInstallSite(
    val placeId: String,
    val placeName: String,
    val kind: LibraryPlaceKind,
    val install: LibraryInstall,
)

/**
 * Immutable answer to "where is this installed". Built from a completed scan, so a screen reading
 * it never touches disk; an empty index means "not scanned yet", not "installed nowhere".
 */
class LibraryUsageIndex private constructor(
    private val sites: Map<LibraryObjectKey, List<LibraryInstallSite>>,
    val places: Int,
) {
    /** True until the first scan finishes; usage-dependent UI stays hidden while it holds. */
    val isEmpty: Boolean get() = places == 0

    fun sitesOf(key: LibraryObjectKey): List<LibraryInstallSite> = sites[key].orEmpty()

    fun installCount(key: LibraryObjectKey): Int = sitesOf(key).size

    fun isInstalled(key: LibraryObjectKey): Boolean = sites.containsKey(key)

    /** Per-status totals of one object, for badges and the aggregate bar. */
    fun statusCounts(key: LibraryObjectKey): Map<InstallStatus, Int> =
        sitesOf(key).groupingBy { it.install.status }.eachCount()

    companion object {
        val EMPTY = LibraryUsageIndex(emptyMap(), places = 0)

        /**
         * Inverts the per-place scan into a per-object index. A group is installed wherever any of
         * its members is: the worst member status wins, the same way a target reports one status
         * for a file holding several regions.
         */
        fun build(scanned: List<LibraryPlaceUsage>, groups: List<LibraryObject> = emptyList()): LibraryUsageIndex {
            val sites = mutableMapOf<LibraryObjectKey, MutableList<LibraryInstallSite>>()
            scanned.forEach { place ->
                place.installs.forEach { (key, install) ->
                    sites.getOrPut(key, ::mutableListOf).add(place.site(install))
                }
                groups.forEach { group ->
                    val members = group.memberKeys.mapNotNull { place.installs[it] }
                    if (members.isEmpty()) return@forEach
                    val worst = LibraryInstall(
                        status = members.maxBy { it.status.severity() }.status,
                        conflict = members.any { it.conflict },
                    )
                    sites.getOrPut(group.key, ::mutableListOf).add(place.site(worst))
                }
            }
            return LibraryUsageIndex(sites.mapValues { (_, value) -> value.toList() }, scanned.size)
        }

        private fun LibraryPlaceUsage.site(install: LibraryInstall) =
            LibraryInstallSite(id, name, kind, install)
    }
}

/** A hand edit must never be hidden behind a pending update, and neither behind a synced sibling. */
private fun InstallStatus.severity(): Int = when (this) {
    InstallStatus.SYNCED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.MODIFIED -> 2
}

/** Produces [LibraryPlaceUsage] for the configured places. Runs off the UI thread, reads only. */
fun interface LibraryUsageSource {
    fun scan(blocks: List<Block>, skills: List<Skill>): List<LibraryPlaceUsage>

    companion object {
        /** Used where usage is out of scope: catalog tests, headless tooling. */
        val None = LibraryUsageSource { _, _ -> emptyList() }
    }
}

/**
 * The status dot of one place, read off the cross-place scan rather than off disk. Every installed
 * object contributes its own mark, and a file that exists while holding no managed region adds
 * [StatusMark.UNMANAGED] — otherwise a place nothing was ever installed into would wear the green of
 * "nothing is out of sync", which is exactly the reading it must not give. A pure redirect pointer
 * is excluded: it carries no independent content and routes the agent to the managed project file.
 */
fun LibraryPlaceUsage.marks(): Set<StatusMark> {
    val marks = installs.values.mapTo(mutableSetOf()) { it.status.mark() }
    if (files.any { it.exists && !it.isPointer && it.mode == TargetOwnershipMode.NONE }) marks += StatusMark.UNMANAGED
    return marks
}
