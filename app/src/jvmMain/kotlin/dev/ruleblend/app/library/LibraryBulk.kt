package dev.ruleblend.app.library

import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.SkipReason
import dev.ruleblend.core.usecase.WriteReport

/** A place a bulk install can write into; identified exactly as the usage scan identifies it. */
data class LibraryPlaceRef(val id: String, val name: String, val kind: LibraryPlaceKind)

/**
 * The catalog key of one resolved write, so a surface can line what was written up against what was
 * marked. Lives here rather than on the use-case type: which kinds the catalog shows is a Library
 * concern, and the use case only ever needs the object's id.
 */
val InstallItem.key: LibraryObjectKey
    get() = when (this) {
        is InstallItem.Rule -> LibraryObjectKey(LibraryObjectKind.RULE, block.id)
        is InstallItem.Mcp -> LibraryObjectKey(LibraryObjectKind.MCP, block.id)
        is InstallItem.Subagent -> LibraryObjectKey(LibraryObjectKind.SUBAGENT, block.id)
        is InstallItem.SkillCopy -> LibraryObjectKey(LibraryObjectKind.SKILL, skill.id)
    }

/** Result of one bulk install: what landed, what was refused and why, and what failed to write. */
data class LibraryInstallReport(
    val written: Int = 0,
    val skipped: Map<SkipReason, Int> = emptyMap(),
    val failed: Int = 0,
) {
    val skippedTotal: Int get() = skipped.values.sum()
}

/**
 * The counts this surface shows, out of the use case's per-pair result. The detail stays available
 * on [WriteReport] for a facade that needs per-id lists; a summary line needs only the tallies.
 */
fun WriteReport.toLibraryReport(): LibraryInstallReport =
    LibraryInstallReport(written = written, skipped = skipped, failed = failed)

/** One line per outcome; a reason that did not occur is left out rather than printed as zero. */
fun LibraryInstallReport.summary(strings: Strings, removal: Boolean = false): String = buildList {
    add(if (removal) strings.libUninstallRemoved(written) else strings.libInstallWritten(written))
    skipped[SkipReason.OUT_OF_SCOPE]?.let { add(strings.libInstallSkippedScope(it)) }
    skipped[SkipReason.UNSUPPORTED]?.let { add(strings.libInstallSkippedUnsupported(it)) }
    skipped[SkipReason.MODIFIED]?.let { add(strings.libInstallSkippedModified(it)) }
    if (failed > 0) add(strings.libInstallFailedCount(failed))
}.joinToString("\n")

/** Writes library objects into places through the same services the Place surface uses. */
interface LibraryPlaceInstaller {
    fun places(): List<LibraryPlaceRef>

    fun install(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport

    /**
     * Takes the objects back out of the places. Reported the same way an install is, and refusing the
     * same hand-edited copies: dropping a local edit is a decision taken in Place, on the file.
     */
    fun remove(items: List<InstallItem>, placeIds: Set<String>): LibraryInstallReport

    companion object {
        /** Used where installing is out of scope: catalog tests, headless tooling. */
        val None = object : LibraryPlaceInstaller {
            override fun places(): List<LibraryPlaceRef> = emptyList()

            override fun install(items: List<InstallItem>, placeIds: Set<String>) = LibraryInstallReport()

            override fun remove(items: List<InstallItem>, placeIds: Set<String>) = LibraryInstallReport()
        }
    }
}

/**
 * Flattens marked objects into the single writes a bulk install performs. A marked group contributes
 * its members carrying the group tag; an object marked both on its own and through a group is written
 * once, untagged — a standalone install is the wider claim and must not be removed with the group.
 * Over a copy already in place an untagged write is an update and keeps the origin that copy has.
 */
fun libraryInstallItems(
    keys: Set<LibraryObjectKey>,
    catalog: LibraryCatalog,
    blocks: List<Block>,
    skills: List<Skill>,
): List<InstallItem> {
    val marked = catalog.objects.filter { it.key in keys }
    val items = LinkedHashMap<LibraryObjectKey, InstallItem>()
    fun add(key: LibraryObjectKey, group: String?) {
        if (items.containsKey(key)) return
        val item = when (key.kind) {
            LibraryObjectKind.RULE -> blocks.find { it.id == key.id }?.let { InstallItem.Rule(it, group) }
            LibraryObjectKind.MCP -> blocks.find { it.id == key.id }?.let { InstallItem.Mcp(it) }
            LibraryObjectKind.SUBAGENT -> blocks.find { it.id == key.id }?.let { InstallItem.Subagent(it) }
            LibraryObjectKind.SKILL -> skills.find { it.id == key.id }?.let { InstallItem.SkillCopy(it) }
            LibraryObjectKind.GROUP -> null
            // Attaching and reconciling a profile belongs to the following Place step; it is not a
            // standalone target write like a marked rule or group.
            LibraryObjectKind.PROFILE -> null
        }
        if (item != null) items[key] = item
    }
    marked.filter { it.key.kind != LibraryObjectKind.GROUP && it.key.kind != LibraryObjectKind.PROFILE }
        .forEach { add(it.key, group = null) }
    marked.filter { it.key.kind == LibraryObjectKind.GROUP }.forEach { group ->
        group.memberKeys.forEach { add(it, group = group.key.id) }
    }
    return items.values.toList()
}

/**
 * [group] after the marked objects join it. Groups do not nest, so a marked group contributes
 * nothing; ids already present keep their position — joining a group must not reorder it.
 */
fun groupWithAdded(group: Group, keys: Set<LibraryObjectKey>): Group {
    if (group.id == ALL_GROUP_ID) return group
    val blockIds = keys.filter {
        it.kind == LibraryObjectKind.RULE || it.kind == LibraryObjectKind.MCP || it.kind == LibraryObjectKind.SUBAGENT
    }
        .map { it.id }
        .filterNot { it in group.blockIds }
    val skillIds = keys.filter { it.kind == LibraryObjectKind.SKILL }
        .map { it.id }
        .filterNot { it in group.skillIds }
    return group.copy(blockIds = group.blockIds + blockIds, skillIds = group.skillIds + skillIds)
}
