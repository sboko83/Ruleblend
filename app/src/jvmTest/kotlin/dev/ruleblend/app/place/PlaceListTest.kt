package dev.ruleblend.app.place

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.normalizedKeys
import dev.ruleblend.core.config.normalizedPlaceId
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.core.integration.Target
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceListTest {

    private val claude = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = Path.of("/home/user/.claude/CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    private val config = AppConfig(projects = listOf("/code/atlas", "/code/orbit", "/code/relay"))
    private val targets = configuredTargets(config, listOf(claude))

    private fun sections(
        board: PlaceBoard,
        query: String = "",
        keep: String? = null,
        marks: (Target) -> Set<StatusMark> = { setOf(StatusMark.MANAGED) },
    ) = placeSections(targets, AppConfig(places = board).normalizedKeys().places, query, keep?.let(::normalizedPlaceId), marks)

    private fun keys(board: PlaceBoard, query: String = "") = sections(board, query).map { it.key }

    private fun namesOf(board: PlaceBoard, key: String, query: String = "") =
        sections(board, query).first { it.key == key }.places.map { it.name }

    @Test
    fun `every configured place lands in a canonical section`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas"))))

        assertEquals(listOf("Claude Code"), namesOf(board, "agents"))
        assertEquals(listOf("atlas"), namesOf(board, "set:KMP"))
        assertEquals(listOf("orbit", "relay"), namesOf(board, "ungrouped"))
    }

    @Test
    fun `pinned and recent are shortcuts, and a pin is not repeated under recent`() {
        val board = PlaceBoard(
            pinned = listOf("project:/code/atlas"),
            recent = listOf("project:/code/atlas", "project:/code/orbit"),
        )

        assertEquals(listOf("atlas"), namesOf(board, "pinned"))
        assertEquals(listOf("orbit"), namesOf(board, "recent"))
        // The shortcut does not remove the place from the section that owns it.
        assertEquals(listOf("atlas", "orbit", "relay"), namesOf(board, "ungrouped"))
    }

    @Test
    fun `a pin left over from a place that is gone is skipped`() {
        val board = PlaceBoard(pinned = listOf("project:/code/deleted"), recent = listOf("agent:codex"))

        assertTrue("pinned" !in keys(board))
        assertTrue("recent" !in keys(board))
    }

    @Test
    fun `an empty set stays visible until a query hides it`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("Sandbox")))

        assertTrue("set:Sandbox" in keys(board))
        assertTrue("set:Sandbox" !in keys(board, query = "atlas"))
    }

    @Test
    fun `the query filters rows and drops sections it empties`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas"))))

        assertEquals(listOf("set:KMP"), keys(board, query = "atl"))
        assertEquals(listOf("atlas"), namesOf(board, "set:KMP", query = "ATL"))
    }

    @Test
    fun `the open place stays in the list even when the query does not match it`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas", "/code/orbit"))))

        // Filtering to another project must not leave the reader without the place they are editing.
        val filtered = sections(board, query = "orbit", keep = "project:/code/atlas")
        assertEquals(listOf("atlas", "orbit"), filtered.first { it.key == "set:KMP" }.places.map { it.name })
        // Nothing open: the query is the only thing deciding what is listed.
        assertEquals(listOf("orbit"), namesOf(board, "set:KMP", query = "orbit"))
    }

    @Test
    fun `a collapsed set still reports its real size`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas", "/code/orbit"))))

        val set = sections(board, query = "atlas").first { it.key == "set:KMP" }
        assertEquals(1, set.places.size)
        assertEquals(2, set.total)
    }

    @Test
    fun `a set member that is no longer a configured project disappears from the set`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas", "/code/gone"))))

        assertEquals(listOf("atlas"), namesOf(board, "set:KMP"))
    }

    @Test
    fun `the row carries the place marks`() {
        val board = PlaceBoard()
        val marks = { target: Target ->
            if (target.name == "orbit") setOf(StatusMark.MANAGED, StatusMark.CONFLICT) else emptySet()
        }

        val rows = sections(board, marks = marks).first { it.key == "ungrouped" }.places
        assertEquals(setOf(StatusMark.MANAGED, StatusMark.CONFLICT), rows.first { it.name == "orbit" }.marks)
        assertEquals(emptySet(), rows.first { it.name == "atlas" }.marks)
    }
}
