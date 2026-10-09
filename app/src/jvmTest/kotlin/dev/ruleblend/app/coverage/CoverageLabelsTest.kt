package dev.ruleblend.app.coverage

import dev.ruleblend.app.fixturePath
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoverageLabelsTest {
    private val places = listOf(
        LibraryPlaceUsage("agent:claude-code", "Claude Code", LibraryPlaceKind.AGENT, emptyMap()),
        LibraryPlaceUsage("agent:codex", "Codex", LibraryPlaceKind.AGENT, emptyMap()),
        LibraryPlaceUsage("project:${fixturePath("/one")}", "First project", LibraryPlaceKind.PROJECT, emptyMap()),
        LibraryPlaceUsage("project:${fixturePath("/two")}", "Second project", LibraryPlaceKind.PROJECT, emptyMap()),
        LibraryPlaceUsage("project:${fixturePath("/three")}", "Third project", LibraryPlaceKind.PROJECT, emptyMap()),
    )
    private val catalog = LibraryCatalog.build(emptyList(), emptyList(), emptyList(), emptyMap())
    private val board = PlaceBoard(sets = listOf(ProjectSet("Apps", listOf(fixturePath("/one"), fixturePath("/two")))))

    @Test fun `bands preserve group titles and order when opened`() {
        for (strings in listOf(EnStrings, RuStrings)) {
            val closed = coverageMatrix(catalog, places, board).columns.bands(strings)
            val opened = coverageMatrix(catalog, places, board,
                expansion = CoverageExpansion(columns = setOf("agents", "set:Apps", "ungrouped")),
            ).columns.bands(strings)
            assertEquals(listOf("agents", "set:Apps", "ungrouped"), opened.map { it.id })
            assertEquals(closed.map { it.title }, opened.map { it.title })
            assertEquals(listOf(strings.intAgents, "Apps", strings.coverageBandUngrouped), opened.map { it.title })
            assertEquals(listOf(strings.coverageAgentGlobal, strings.coverageBandSet, ""), opened.map { it.suffix })
            assertEquals(listOf(CoverageColumnKind.AGENT, CoverageColumnKind.SET, CoverageColumnKind.UNGROUPED), opened.map { it.kind })
            assertEquals(listOf(2, 2, 1), opened.map { it.columns.size })
            assertEquals(listOf(true, true, false), opened.map { it.open })
            assertEquals(listOf(true, true, false), closed.map { it.toggleable })
            assertEquals(listOf(true, true, false), opened.map { it.toggleable })
            assertTrue(closed.none { it.open })
        }
    }

    @Test fun `one place retains its own name below a differently named group`() {
        val snapshot = coverageMatrix(catalog, places.filter { it.id != "agent:codex" },
            PlaceBoard(sets = listOf(ProjectSet("Apps", listOf(fixturePath("/one"))), ProjectSet("Tools", listOf(fixturePath("/two")))))
        )
        val bands = snapshot.columns.bands(EnStrings)
        assertEquals(listOf("Claude Code", "Apps", "Tools", EnStrings.coverageBandUngrouped), bands.map { it.title })
        assertEquals(listOf("Claude Code", "First project", "Second project", "Third project"),
            snapshot.columns.map { it.placeName })
        assertTrue(bands.none { it.open || it.toggleable })
    }

    @Test fun `only adjacent columns merge into a band`() {
        val first = CoverageColumn("place:one", CoverageColumnKind.PLACE, "One", listOf("one"),
            parentId = "set:Apps", parentName = "Apps", parentKind = CoverageColumnKind.SET)
        val middle = CoverageColumn("ungrouped", CoverageColumnKind.UNGROUPED, null, listOf("two"))
        val last = first.copy(id = "place:three", name = "Three", placeIds = listOf("three"))
        val bands = listOf(first, middle, last).bands(EnStrings)
        assertEquals(listOf("set:Apps", "ungrouped", "set:Apps"), bands.map { it.id })
        assertEquals(listOf(first, middle, last), bands.flatMap { it.columns })
        assertFalse(bands[1].toggleable)
        assertTrue(emptyList<CoverageColumn>().bands(EnStrings).isEmpty())
    }
}
