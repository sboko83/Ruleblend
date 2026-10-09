package dev.ruleblend.app.command

import dev.ruleblend.app.fixturePath
import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.navigation.AppRoute
import dev.ruleblend.app.place.placeSections
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The palette against the design scale: 304 library objects and 30 places. What is checked is the
 * answer order, not that a search "finds something" — at this size a match that ranks tenth is the
 * same as no match.
 */
class CommandPaletteTest {

    private val claude = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = Path.of("/home/user/.claude/CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    private val projects = (0 until 25).map { fixturePath("/code/project-$it") }
    private val config = AppConfig(projects = projects)
    private val board = PlaceBoard(
        pinned = listOf("project:${fixturePath("/code/project-9")}"),
        recent = listOf("project:${fixturePath("/code/project-4")}"),
        sets = listOf(ProjectSet("KMP apps", projects.take(8))),
    )

    private val catalog = LibraryCatalog.build(
        blocks = (0 until 200).map { Block("rule-$it", "Rule $it", description = "Rule description $it") } +
            (0 until 30).map { Block("mcp-$it", "MCP $it", type = BlockType.MCP, content = "{}") },
        groups = listOf(Group(ALL_GROUP_ID, "all")) +
            (0 until 13).map { Group("group-$it", "Group $it") },
        skills = (0 until 60).map { Skill("skill-$it", "Skill $it") },
        scopes = emptyMap(),
    )

    private val actions = AppRoute.entries.map {
        CommandItem("route:${it.name}", CommandKind.ACTION, "Go to ${it.name.lowercase()}", target = CommandTarget.Surface(it))
    }

    private val index = commandIndex(
        catalog = catalog,
        places = placeSections(configuredTargets(config, listOf(claude)), board),
        actions = actions,
    )

    @Test
    fun `the index covers the whole design scale and lists a place once`() {
        assertEquals(304, index.count { it.target is CommandTarget.Library })
        // 25 projects + one agent global, even though the board shows two of them twice.
        assertEquals(26, index.count { it.target is CommandTarget.Place })
        assertEquals(actions.size, index.count { it.kind == CommandKind.ACTION })
    }

    @Test
    fun `an empty query answers with actions and the shortcuts, not with the library`() {
        val rows = rankCommands(index, "")

        assertEquals(AppRoute.entries.size, rows.count { it.kind == CommandKind.ACTION })
        assertEquals(listOf("project-9", "project-4"), rows.filter { it.kind == CommandKind.PLACE }.map { it.title })
        assertTrue(rows.none { it.kind == CommandKind.RULE })
    }

    @Test
    fun `a scope prefix answers with that kind alone, actions included`() {
        assertEquals(CommandQuery(CommandKind.RULE, "7"), parseCommandQuery("r: 7"))
        assertEquals(CommandQuery(CommandKind.PLACE, ""), parseCommandQuery("P:"))
        // Only a single letter is a scope: dropping "cp" would search for something else entirely.
        assertEquals(CommandQuery(null, "mcp: 3"), parseCommandQuery("mcp: 3"))

        assertTrue(rankCommands(index, "s:", limit = 60).all { it.kind == CommandKind.SKILL })
        assertTrue(rankCommands(index, "m: 1").all { it.kind == CommandKind.MCP })
        val places = rankCommands(index, "p:project-1")
        assertEquals("project-1", places.first().title)
        assertTrue(places.all { it.kind == CommandKind.PLACE })
        assertTrue(rankCommands(index, "p: go").isEmpty())
    }

    @Test
    fun `an exact name beats a prefix, a prefix beats a fragment, a title beats a description`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("a", "kotlin", description = "the plain name"),
                Block("b", "kotlin style", description = "starts with it"),
                Block("c", "kmp kotlin rules", description = "word start inside"),
                Block("d", "refactoring", description = "kotlin in the description"),
                Block("e", "sekotlinish", description = "a fragment inside a word"),
            ),
            groups = emptyList(),
            skills = emptyList(),
            scopes = emptyMap(),
        )

        assertEquals(
            listOf("kotlin", "kotlin style", "kmp kotlin rules", "sekotlinish", "refactoring"),
            rankCommands(commandIndex(catalog, emptyList()), "kotlin").map { it.title },
        )
    }

    @Test
    fun `equally good matches are offered rarest kind first`() {
        val tied = CommandKind.entries.reversed().map {
            CommandItem(it.name, it, "audit", target = CommandTarget.Surface(AppRoute.HOME))
        }

        assertEquals(CommandKind.entries, rankCommands(tied, "audit", limit = 10).map { it.kind })
    }

    @Test
    fun `a row carries the selection its surface needs`() {
        val rule = rankCommands(index, "Rule 42").first()
        assertEquals(CommandTarget.Library(LibraryObjectKey(LibraryObjectKind.RULE, "rule-42")), rule.target)

        val place = rankCommands(index, "p:project-9").first()
        assertEquals(CommandTarget.Place("project:${fixturePath("/code/project-9")}"), place.target)
    }
}
