package dev.ruleblend.app.coverage

import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.library.LibraryObjectKind

internal fun CoverageColumn.title(strings: Strings): String = name ?: when (kind) {
    CoverageColumnKind.AGENT -> strings.intAgents
    CoverageColumnKind.SET, CoverageColumnKind.PLACE -> ""
    CoverageColumnKind.UNGROUPED -> strings.coverageBandUngrouped
}

internal data class CoverageBand(
    val id: String,
    val kind: CoverageColumnKind,
    val title: String,
    val suffix: String,
    val columns: List<CoverageColumn>,
    val open: Boolean,
    val toggleable: Boolean,
)

/** Group only neighbours: a band must occupy one continuous span of the matrix. */
internal fun List<CoverageColumn>.bands(strings: Strings): List<CoverageBand> {
    val groups = mutableListOf<MutableList<CoverageColumn>>()
    forEach { column ->
        val previous = groups.lastOrNull()?.first()
        if (previous == null || (previous.parentId ?: previous.id) != (column.parentId ?: column.id)) {
            groups += mutableListOf(column)
        } else {
            groups.last() += column
        }
    }
    return groups.map { columns ->
        val first = columns.first()
        val open = first.parentId != null
        val kind = first.parentKind ?: first.kind
        val title = when (kind) {
            CoverageColumnKind.AGENT -> if (columns.sumOf { it.places } == 1) {
                first.placeName ?: first.name ?: strings.intAgents
            } else strings.intAgents
            CoverageColumnKind.SET -> first.parentName ?: first.name.orEmpty()
            CoverageColumnKind.UNGROUPED -> strings.coverageBandUngrouped
            CoverageColumnKind.PLACE -> first.title(strings)
        }
        CoverageBand(
            id = first.parentId ?: first.id,
            kind = kind,
            title = title,
            suffix = when (kind) {
                CoverageColumnKind.AGENT -> strings.coverageAgentGlobal
                CoverageColumnKind.SET -> strings.coverageBandSet
                else -> ""
            },
            columns = columns,
            open = open,
            toggleable = open || first.expandable,
        )
    }
}

internal fun CoverageRow.title(strings: Strings): String = name ?: when (kind) {
    CoverageRowKind.ALL -> strings.coverageRowAll
    CoverageRowKind.TYPE -> objectKind?.label(strings).orEmpty()
    CoverageRowKind.GROUP, CoverageRowKind.OBJECT -> ""
}

internal fun LibraryObjectKind.label(strings: Strings): String = when (this) {
    LibraryObjectKind.RULE -> strings.libTypeRule
    LibraryObjectKind.SUBAGENT -> strings.libTypeSubagent
    LibraryObjectKind.SKILL -> strings.intSkills
    LibraryObjectKind.MCP -> strings.intMcpServers
    LibraryObjectKind.GROUP -> strings.libGroups
    LibraryObjectKind.PROFILE -> strings.libProfiles
}
