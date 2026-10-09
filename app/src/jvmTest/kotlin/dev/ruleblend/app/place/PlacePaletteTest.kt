package dev.ruleblend.app.place

import dev.ruleblend.app.fixturePath
import dev.ruleblend.app.integration.UnmanagedFile
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.LibraryUsageIndex
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.UnmanagedContent
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacePaletteTest {

    private val claude = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = Path.of("/home/user/.claude/CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    private val catalog = LibraryCatalog.build(
        blocks = listOf(
            Block(id = "kotlin-style", name = "Kotlin style"),
            Block(id = "git-flow", name = "Git flow"),
            Block(id = "context7", name = "Context7", type = BlockType.MCP),
        ),
        groups = listOf(Group(id = "base", name = "Base", blockIds = listOf("kotlin-style", "git-flow"))),
        skills = listOf(Skill(id = "docs", name = "Docs skill")),
        scopes = emptyMap(),
    )

    private val statuses = mapOf(
        LibraryObjectKey(LibraryObjectKind.RULE, "kotlin-style") to InstallStatus.SYNCED,
        LibraryObjectKey(LibraryObjectKind.SKILL, "docs") to InstallStatus.MODIFIED,
    )

    private val status = { key: LibraryObjectKey -> statuses[key] }

    private fun project(name: String) = ProjectTarget(Path.of(fixturePath("/code/$name")), listOf(claude))

    private fun rows(kind: LibraryObjectKind, query: String = "") =
        paletteSections(catalog, query, status).first { it.kind == kind }.rows

    @Test
    fun `sections carry every type and report installed status of this place`() {
        val sections = paletteSections(catalog, status = status)

        assertEquals(LibraryObjectKind.entries, sections.map { it.kind })
        assertEquals(listOf("Git flow", "Kotlin style"), rows(LibraryObjectKind.RULE).map { it.name })
        assertEquals(InstallStatus.SYNCED, rows(LibraryObjectKind.RULE).first { it.name == "Kotlin style" }.status)
        // Not installed here: the row offers the object rather than reporting it.
        assertNull(rows(LibraryObjectKind.RULE).first { it.name == "Git flow" }.status)
        assertEquals(InstallStatus.MODIFIED, rows(LibraryObjectKind.SKILL).single().status)
        assertNull(rows(LibraryObjectKind.MCP).single().status)
    }

    @Test
    fun `favorites lead their type and the rest follow by name`() {
        val starred = LibraryCatalog.build(
            blocks = listOf(
                Block(id = "alpha", name = "Alpha"),
                Block(id = "zulu", name = "Zulu", favorite = true),
                Block(id = "beta", name = "Beta"),
                Block(id = "mike", name = "Mike", favorite = true),
            ),
            groups = emptyList(),
            skills = emptyList(),
            scopes = emptyMap(),
        )

        val rows = paletteSections(starred).first { it.kind == LibraryObjectKind.RULE }.rows

        assertEquals(listOf("Mike", "Zulu", "Alpha", "Beta"), rows.map { it.name })
        assertEquals(listOf(true, true, false, false), rows.map { it.favorite })
    }

    @Test
    fun `a group takes the worst status of its installed members`() {
        val group = rows(LibraryObjectKind.GROUP).single()

        assertEquals("Base", group.name)
        assertEquals(InstallStatus.SYNCED, group.status)
    }

    @Test
    fun `the query filters rows and the section still counts what it shows`() {
        val section = paletteSections(catalog, query = "kotlin", status = status).first { it.kind == LibraryObjectKind.RULE }

        assertEquals(listOf("Kotlin style"), section.rows.map { it.name })
        assertEquals(1, section.total)
        assertTrue(paletteSections(catalog, query = "nothing").all { it.rows.isEmpty() })
    }

    @Test
    fun `recommendations count the siblings of the project's set and skip what is installed here`() {
        val board = PlaceBoard(
            sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/code/atlas"), fixturePath("/code/orbit"), fixturePath("/code/relay")))),
        )
        val usage = LibraryUsageIndex.build(
            listOf(
                place("project:${fixturePath("/code/orbit")}", "git-flow", "kotlin-style"),
                place("project:${fixturePath("/code/relay")}", "git-flow"),
                // Another set: its installs must not raise the count of this one.
                place("project:${fixturePath("/code/outside")}", "git-flow"),
            ),
        )

        val recommendations = paletteRecommendations(catalog, board, project("atlas"), usage, status)

        assertEquals(1, recommendations.size)
        val first = recommendations.single()
        assertEquals("Git flow", first.name)
        assertEquals(2, first.installed)
        // "of 3" is the whole set, this project included — the phrasing of the mock-up.
        assertEquals(3, first.total)
        assertEquals("KMP apps", first.setName)
    }

    @Test
    fun `a place with no peers gets no recommendations`() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/code/atlas")))))
        val usage = LibraryUsageIndex.build(listOf(place("project:${fixturePath("/code/orbit")}", "git-flow")))
        val agent = AgentGlobalTarget(claude)

        assertTrue(paletteRecommendations(catalog, board, project("atlas"), usage, status).isEmpty())
        assertTrue(paletteRecommendations(catalog, PlaceBoard(), project("atlas"), usage, status).isEmpty())
        assertTrue(paletteRecommendations(catalog, board, agent, usage, status).isEmpty())
    }

    @Test
    fun `recommendations rank by how much of the set holds the object and stop at the limit`() {
        val board = PlaceBoard(
            sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/code/atlas"), fixturePath("/code/orbit"), fixturePath("/code/relay")))),
        )
        val usage = LibraryUsageIndex.build(
            listOf(
                place("project:${fixturePath("/code/orbit")}", "git-flow", "context7", "docs"),
                place("project:${fixturePath("/code/relay")}", "git-flow", "docs"),
            ),
        )

        val ranked = paletteRecommendations(catalog, board, project("atlas"), usage, status, limit = 2)

        // "docs" is installed in this place already, so a sibling holding it is not a recommendation.
        assertEquals(listOf("Git flow", "Context7"), ranked.map { it.name })
    }

    @Test
    fun `found files list hand-written text once, pointers and empty ones aside`() {
        val text = UnmanagedContent("hand written", lineCount = 12)
        val owned = found(fixturePath("/code/atlas/AGENTS.md"), "AGENTS.md", text)
        val files = listOf(
            owned,
            found(fixturePath("/code/atlas/CLAUDE.md"), "CLAUDE.md", text, pointer = true),
            found(fixturePath("/code/atlas/docs/EMPTY.md"), "docs/EMPTY.md", UnmanagedContent("", 0)),
            // The same file reached both as owned and through an import must not be listed twice.
            owned,
        )

        assertEquals(listOf(PaletteFoundFile("AGENTS.md", 12, files.first().path)), paletteFoundFiles(files))
    }

    private fun place(id: String, vararg ids: String) = LibraryPlaceUsage(
        id = id,
        name = id.substringAfterLast('/'),
        kind = LibraryPlaceKind.PROJECT,
        installs = ids.associate { objectId ->
            val kind = when (objectId) {
                "context7" -> LibraryObjectKind.MCP
                "docs" -> LibraryObjectKind.SKILL
                else -> LibraryObjectKind.RULE
            }
            LibraryObjectKey(kind, objectId) to LibraryInstall(InstallStatus.SYNCED)
        },
    )

    private fun found(path: String, relative: String, content: UnmanagedContent, pointer: Boolean = false) =
        UnmanagedFile(Path.of(path), content, relative, isPointer = pointer)
}
