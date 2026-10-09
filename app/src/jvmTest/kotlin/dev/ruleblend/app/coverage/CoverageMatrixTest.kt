package dev.ruleblend.app.coverage

import dev.ruleblend.app.fixturePath
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Coverage aggregation: what a cell counts, what it refuses to count, and what the whole matrix
 * costs at the design scale. Everything here is pure — no place is written and no file is read, so a
 * disagreement between this and Place would be an aggregation bug, never a scanning one.
 */
class CoverageMatrixTest {

    @Test fun `attention filters both aggregates and their opened objects`() {
        val catalog = catalog(
            rules = listOf("synced", "modified", "update", "missing"), skills = listOf("pdf"),
            groups = listOf(Group("mixed", "Mixed", blockIds = listOf("synced", "modified", "update", "missing"))),
        )
        val places = listOf(project("one", mapOf(
            rule("synced") to synced, rule("modified") to modified, rule("update") to update,
        )))
        for (onlyInstalled in listOf(false, true)) {
            val snapshot = coverageMatrix(catalog, places,
                filters = CoverageFilters(onlyInstalled = onlyInstalled, needsAttention = true),
                expansion = CoverageExpansion(rows = setOf("type:RULE", "group:mixed")),
            )
            assertEquals(listOf("all", "type:RULE", "type:RULE/RULE:modified", "type:RULE/RULE:update",
                "group:mixed", "group:mixed/RULE:modified", "group:mixed/RULE:update"), snapshot.rows.map { it.id })
            assertTrue(snapshot.rows.all { row -> row.cells.any { it.modified > 0 || it.updates > 0 } })
        }
        assertTrue(coverageMatrix(catalog, places,
            filters = CoverageFilters(kinds = setOf(LibraryObjectKind.SKILL), needsAttention = true),
        ).rows.isEmpty())
    }

    @Test fun `fold buttons follow visible groups and ignore stale expansion ids`() {
        val catalog = catalog(rules = listOf("a"), skills = listOf("pdf"))
        val places = listOf(agent("one", "One"), agent("two", "Two"), project("one"), project("two"))
        val closed = coverageMatrix(catalog, places)
        assertEquals(CoverageFold(true, false, true, false), closed.fold(CoverageExpansion()))
        val partial = CoverageExpansion(rows = setOf("type:RULE"), columns = setOf("agents"))
        val opened = coverageMatrix(catalog, places, expansion = partial)
        assertEquals(CoverageFold(true, true, true, true), opened.fold(partial))
        assertEquals(listOf(CoverageColumnKind.AGENT, CoverageColumnKind.AGENT),
            opened.columns.filter { it.parentId == "agents" }.map { it.parentKind })
        val full = CoverageExpansion(rows = setOf("all", "type:RULE", "type:SKILL"), columns = setOf("agents", "ungrouped"))
        assertEquals(CoverageFold(false, true, false, true), coverageMatrix(catalog, places, expansion = full).fold(full))
        assertEquals(CoverageFold(false, false, true, true), coverageMatrix(catalog, places,
            filters = CoverageFilters(needsAttention = true), expansion = partial).fold(partial))
        assertEquals(CoverageFold(false, false, false, false), CoverageSnapshot.EMPTY.fold(full))
        assertTrue(!closed.rows.first().expandable)
        val single = coverageMatrix(catalog, listOf(agent("one", "One")), expansion = full)
        assertEquals("agents", single.columns.single().id)
        assertEquals("One", single.columns.single().name)
        assertTrue(!single.columns.single().expandable)
        assertEquals(false, single.fold(full).columnsCollapsible)
    }

    @Test fun `fleet counters distinguish assistant globals from projects`() {
        val snapshot = coverageMatrix(
            catalog(rules = listOf("a", "b", "c")),
            listOf(
                agent("codex", "Codex", installs = mapOf(rule("a") to modified, rule("b") to modified, rule("c") to update)),
                agent("claude", "Claude", installs = mapOf(rule("a") to synced)),
                project("one", mapOf(rule("a") to modified, rule("b") to update)),
                project("two", mapOf(rule("a") to update)),
            ),
        )
        assertEquals(2, snapshot.agentPlaces)
        assertEquals(2, snapshot.projectPlaces)
        assertEquals(4, snapshot.places)
        assertEquals(CoverageTally(count = 3, agents = 1, projects = 1), snapshot.conflicts)
        assertEquals(CoverageTally(count = 3, agents = 1, projects = 2), snapshot.updates)
        assertEquals(3, snapshot.updates.places)
    }

    @Test fun `a set column counts objects, not installations`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a", "b", "c")),
            scanned = listOf(
                project("ledger-kmp", mapOf(rule("a") to synced, rule("b") to synced)),
                project("mesh-sync", mapOf(rule("a") to synced)),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync"),
        )

        val cell = snapshot.cell("all", "set:KMP apps")
        // Two projects hold "a", but "a" is one object: the fraction answers "how much of the
        // library does this set know", not "how many files were written".
        assertEquals(2, cell.installed)
        assertEquals(3, cell.members)
        assertEquals(1, cell.missing)
    }

    @Test fun `the worst place of a column decides the status of an object`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a", "b")),
            scanned = listOf(
                project("ledger-kmp", mapOf(rule("a") to synced, rule("b") to synced)),
                project("mesh-sync", mapOf(rule("a") to modified, rule("b") to update)),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync"),
        )

        val cell = snapshot.cell("all", "set:KMP apps")
        assertEquals(0, cell.synced)
        assertEquals(1, cell.updates)
        assertEquals(1, cell.modified)
        assertEquals(InstallStatus.MODIFIED, cell.status)
    }

    @Test fun `a rule pinned to a project is out of scope elsewhere, not missing`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a"), scopes = mapOf("a" to fixturePath("/tmp/ledger-kmp"))),
            scanned = listOf(
                project("ledger-kmp", mapOf(rule("a") to synced)),
                project("mesh-sync"),
                agent("claude-code", "Claude Code"),
            ),
            board = board("KMP apps", "ledger-kmp"),
        )

        assertEquals(CoverageCell(installed = 1, members = 1, synced = 1), snapshot.cell("all", "set:KMP apps"))
        // The projects outside the pin have nowhere to put the rule at all: a dash, not "0/1".
        assertTrue(snapshot.cell("all", "ungrouped").outOfScope)
        assertTrue(snapshot.cell("all", "agents").outOfScope)
    }

    @Test fun `a skill is out of scope where the agent has no skills directory`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a"), skills = listOf("pdf")),
            scanned = listOf(
                agent("claude-code", "Claude Code", supportsSkills = true),
                agent("zcode", "ZCode", supportsSkills = false),
            ),
            expansion = CoverageExpansion(columns = setOf("agents")),
        )

        assertTrue(snapshot.cell("type:SKILL", "place:agent:zcode").outOfScope)
        assertEquals(1, snapshot.cell("type:SKILL", "place:agent:claude-code").members)
        // The rule still fits everywhere, so the "all" row keeps that agent in the denominator.
        assertEquals(1, snapshot.cell("all", "place:agent:zcode").members)
    }

    @Test fun `MCP and subagent destinations are out of scope, not missing`() {
        val snapshot = coverageMatrix(
            catalog = catalog(mcp = listOf("github"), subagents = listOf("review")),
            scanned = listOf(
                agent("pi", "Pi", supportsMcp = false, supportsSubagents = false),
                agent("codex", "Codex", supportsMcp = true, supportsSubagents = true),
            ),
            expansion = CoverageExpansion(columns = setOf("agents")),
        )

        assertTrue(snapshot.cell("type:MCP", "place:agent:pi").outOfScope)
        assertTrue(snapshot.cell("type:SUBAGENT", "place:agent:pi").outOfScope)
        assertEquals(1, snapshot.cell("type:MCP", "place:agent:codex").members)
        assertEquals(1, snapshot.cell("type:SUBAGENT", "place:agent:codex").members)
    }

    @Test fun `rows go from every object to types to the library groups`() {
        val snapshot = coverageMatrix(
            catalog = catalog(
                rules = listOf("a", "b"),
                skills = listOf("pdf"),
                mcp = listOf("github"),
                groups = listOf(Group("style", "Style", blockIds = listOf("a"), skillIds = listOf("pdf"))),
            ),
            scanned = listOf(project("ledger-kmp", mapOf(rule("a") to synced))),
        )

        assertEquals(
            listOf("all", "type:RULE", "type:SKILL", "type:MCP", "group:style"),
            snapshot.rows.map { it.id },
        )
        assertEquals(4, snapshot.rows.first().total)
        assertEquals(2, snapshot.row("group:style").total)
    }

    @Test fun `filters re-cut the same scan, they never hide a place`() {
        val scanned = listOf(project("ledger-kmp", mapOf(rule("a") to synced)))
        val catalog = catalog(rules = listOf("a", "b"), skills = listOf("pdf"), mcp = listOf("github"))

        val rulesOnly = coverageMatrix(catalog, scanned, filters = CoverageFilters(kinds = setOf(LibraryObjectKind.RULE)))
        assertEquals(listOf("all", "type:RULE"), rulesOnly.rows.map { it.id })
        assertEquals(2, rulesOnly.cell("all", "ungrouped").members)
        assertEquals(1, rulesOnly.columns.size)

        val installed = coverageMatrix(catalog, scanned, filters = CoverageFilters(onlyInstalled = true))
        assertEquals(listOf("all", "type:RULE"), installed.rows.map { it.id })
    }

    @Test fun `grouping agents merges their columns without merging the sets`() {
        val scanned = listOf(
            agent("claude-code", "Claude Code", installs = mapOf(rule("a") to synced)),
            agent("codex", "Codex"),
            project("ledger-kmp", mapOf(rule("b") to synced)),
        )
        val catalog = catalog(rules = listOf("a", "b"))
        val board = board("KMP apps", "ledger-kmp")

        val perAgent = coverageMatrix(catalog, scanned, board, expansion = CoverageExpansion(columns = setOf("agents")))
        assertEquals(listOf("place:agent:claude-code", "place:agent:codex", "set:KMP apps"), perAgent.columns.map { it.id })

        val merged = coverageMatrix(catalog, scanned, board, expansion = CoverageExpansion())
        assertEquals(listOf("agents", "set:KMP apps"), merged.columns.map { it.id })
        assertEquals(2, merged.columns.first().places)
        assertEquals(1, merged.cell("all", "agents").installed)
    }

    @Test fun `an unscanned matrix is not an empty one`() {
        assertEquals(false, CoverageSnapshot.EMPTY.scanned)
        assertNull(CoverageCell().status)
        assertTrue(coverageMatrix(catalog(rules = listOf("a")), scanned = emptyList()).scanned)
    }

    /** The design's scale: 304 objects × 30 places, re-aggregated on every filter click. */
    @Test fun `the matrix of the design scale is aggregated in one pass`() {
        val rules = (1..200).map { "rule-$it" }
        val skills = (1..60).map { "skill-$it" }
        val mcp = (1..44).map { "mcp-$it" }
        val groups = (1..14).map { index ->
            Group("group-$index", "Group $index", blockIds = rules.filterIndexed { position, _ -> position % 14 == index % 14 })
        }
        val catalog = catalog(rules, skills, mcp, groups = groups)
        val installed = (rules.take(12) + skills.take(4)).associate {
            LibraryObjectKey(if (it.startsWith("rule")) LibraryObjectKind.RULE else LibraryObjectKind.SKILL, it) to synced
        }
        val scanned = (1..5).map { agent("agent-$it", "Agent $it", installs = installed) } +
            (1..25).map { project("project-$it", installed) }
        val board = PlaceBoard(
            sets = (0..4).map { index ->
                ProjectSet("Set $index", (1..5).map { fixturePath("/tmp/project-${index * 5 + it}") })
            },
        )

        val started = System.nanoTime()
        val snapshot = coverageMatrix(catalog, scanned, board)
        val millis = (System.nanoTime() - started) / 1_000_000

        assertEquals(30, snapshot.places)
        assertEquals(304, snapshot.objects)
        assertEquals(6, snapshot.columns.size)
        assertEquals(18, snapshot.rows.size)
        println("Coverage matrix of 304 objects × 30 places: $millis ms")
        assertTrue(millis < 500, "aggregating the matrix took $millis ms")
    }

    @Test fun `opening a column replaces it with its places, in its own position`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a")),
            scanned = listOf(
                agent("claude-code", "Claude Code"),
                project("ledger-kmp", mapOf(rule("a") to synced)),
                project("mesh-sync"),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync"),
            expansion = CoverageExpansion(columns = setOf("agents", "set:KMP apps")),
        )

        assertEquals(
            listOf("agents", "place:project:${fixturePath("/tmp/ledger-kmp")}", "place:project:${fixturePath("/tmp/mesh-sync")}"),
            snapshot.columns.map { it.id },
        )
        val opened = snapshot.columns.last()
        assertEquals("mesh-sync", opened.name)
        assertEquals("set:KMP apps", opened.parentId)
        assertEquals("project:${fixturePath("/tmp/mesh-sync")}", opened.placeId)
        // A single place aggregates nothing, so there is nothing left in it to open.
        assertEquals(false, opened.expandable)
    }

    @Test fun `opening a row lists its objects right under it`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a", "b"), skills = listOf("pdf")),
            scanned = listOf(project("ledger-kmp", mapOf(rule("a") to synced))),
            expansion = CoverageExpansion(rows = setOf("type:RULE")),
        )

        assertEquals(
            listOf("all", "type:RULE", "type:RULE/RULE:a", "type:RULE/RULE:b", "type:SKILL"),
            snapshot.rows.map { it.id },
        )
        val opened = snapshot.row("type:RULE/RULE:a")
        assertEquals(rule("a"), opened.key)
        assertEquals("type:RULE", opened.parentId)
        assertEquals(false, opened.expandable)
    }

    @Test fun `an opened object counts places, while the row above it counts objects`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a", "b")),
            scanned = listOf(
                project("ledger-kmp", mapOf(rule("a") to synced, rule("b") to modified)),
                project("mesh-sync", mapOf(rule("a") to update)),
                project("atlas"),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync", "atlas"),
            expansion = CoverageExpansion(rows = setOf("type:RULE", "type:SKILL")),
        )

        // The aggregate: both objects live somewhere in the set.
        assertEquals(CoverageCell(installed = 2, members = 2, updates = 1, modified = 1), snapshot.cell("all", "set:KMP apps"))
        // The object: two of the three projects hold it, and the mix is per place, not per object.
        assertEquals(
            CoverageCell(installed = 2, members = 3, synced = 1, updates = 1),
            snapshot.cell("type:RULE/RULE:a", "set:KMP apps"),
        )
    }

    @Test fun `a dash in the drill-down is the same rule as the dash above it`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a"), skills = listOf("pdf"), scopes = mapOf("a" to fixturePath("/tmp/ledger-kmp"))),
            scanned = listOf(
                agent("claude-code", "Claude Code", supportsSkills = true),
                agent("zcode", "ZCode", supportsSkills = false),
                project("ledger-kmp", mapOf(rule("a") to synced)),
                project("mesh-sync"),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync"),
            expansion = CoverageExpansion(rows = setOf("type:RULE", "type:SKILL"), columns = setOf("agents", "set:KMP apps")),
        )

        // The pinned rule can only live in its own project; the other place of the set is not a gap.
        assertEquals(1, snapshot.cell("type:RULE/RULE:a", "place:project:${fixturePath("/tmp/ledger-kmp")}").members)
        assertTrue(snapshot.cell("type:RULE/RULE:a", "place:project:${fixturePath("/tmp/mesh-sync")}").outOfScope)
        assertTrue(snapshot.cell("type:RULE/RULE:a", "place:agent:claude-code").outOfScope)
        // A skill needs an agent that reads a skills directory, at every level of the drill-down.
        assertTrue(snapshot.cell("type:SKILL/SKILL:pdf", "place:agent:zcode").outOfScope)
        assertEquals(1, snapshot.cell("type:SKILL/SKILL:pdf", "place:agent:claude-code").members)
    }

    @Test fun `only one object in one place is a writable cell`() {
        val snapshot = coverageMatrix(
            catalog = catalog(rules = listOf("a"), scopes = mapOf("a" to fixturePath("/tmp/ledger-kmp"))),
            scanned = listOf(
                project("ledger-kmp", mapOf(rule("a") to synced)),
                project("mesh-sync"),
            ),
            board = board("KMP apps", "ledger-kmp", "mesh-sync"),
            expansion = CoverageExpansion(rows = setOf("type:RULE", "type:SKILL"), columns = setOf("agents", "set:KMP apps")),
        )

        assertTrue(snapshot.writable("type:RULE/RULE:a", "place:project:${fixturePath("/tmp/ledger-kmp")}"))
        // Out of scope: the model refuses the click instead of letting the write path reject it.
        assertEquals(false, snapshot.writable("type:RULE/RULE:a", "place:project:${fixturePath("/tmp/mesh-sync")}"))
        // An aggregate stands for several objects or several places; neither is one write.
        assertEquals(false, snapshot.writable("all", "place:project:${fixturePath("/tmp/ledger-kmp")}"))
    }

    @Test fun `an opened row of the design scale stays one pass`() {
        val rules = (1..200).map { "rule-$it" }
        val catalog = catalog(rules)
        val scanned = (1..25).map { project("project-$it", emptyMap()) }

        val started = System.nanoTime()
        val snapshot = coverageMatrix(
            catalog = catalog,
            scanned = scanned,
            expansion = CoverageExpansion(rows = setOf("all", "type:RULE")),
        )
        val millis = (System.nanoTime() - started) / 1_000_000

        // All objects stays a summary even if its id is supplied in the expansion.
        assertEquals(202, snapshot.rows.size)
        println("Coverage drill-down of 200 objects × 25 places: $millis ms")
        assertTrue(millis < 500, "aggregating the opened matrix took $millis ms")
    }

    // ---- harness ----

    private val synced = LibraryInstall(InstallStatus.SYNCED)
    private val update = LibraryInstall(InstallStatus.UPDATE_AVAILABLE)
    private val modified = LibraryInstall(InstallStatus.MODIFIED)

    private fun rule(id: String) = LibraryObjectKey(LibraryObjectKind.RULE, id)

    private fun catalog(
        rules: List<String> = emptyList(),
        skills: List<String> = emptyList(),
        mcp: List<String> = emptyList(),
        subagents: List<String> = emptyList(),
        groups: List<Group> = emptyList(),
        scopes: Map<String, String> = emptyMap(),
    ) = LibraryCatalog.build(
        blocks = rules.map { Block(id = it, name = it) } +
            mcp.map { Block(id = it, name = it, type = BlockType.MCP) } +
            subagents.map { Block(id = it, name = it, type = BlockType.SUBAGENT, content = "Review code.") },
        groups = groups,
        skills = skills.map { Skill(id = it, name = it) },
        scopes = scopes,
    )

    private fun project(name: String, installs: Map<LibraryObjectKey, LibraryInstall> = emptyMap()) =
        LibraryPlaceUsage("project:${fixturePath("/tmp/$name")}", name, LibraryPlaceKind.PROJECT, installs)

    private fun agent(
        id: String,
        name: String,
        installs: Map<LibraryObjectKey, LibraryInstall> = emptyMap(),
        supportsSkills: Boolean = true,
        supportsMcp: Boolean = true,
        supportsSubagents: Boolean = true,
    ) = LibraryPlaceUsage(
        "agent:$id", name, LibraryPlaceKind.AGENT, installs,
        supportsSkills = supportsSkills,
        supportsMcp = supportsMcp,
        supportsSubagents = supportsSubagents,
    )

    private fun board(name: String, vararg projects: String) =
        PlaceBoard(sets = listOf(ProjectSet(name, projects.map { fixturePath("/tmp/$it") })))

    private fun CoverageSnapshot.row(id: String) = rows.first { it.id == id }

    private fun CoverageSnapshot.cell(rowId: String, columnId: String) =
        row(rowId).cells[columns.indexOfFirst { it.id == columnId }]

    private fun CoverageSnapshot.writable(rowId: String, columnId: String): Boolean {
        val column = columns.first { it.id == columnId }
        return row(rowId).writable(column, cell(rowId, columnId))
    }
}
