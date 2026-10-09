package dev.ruleblend.core.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceBoardTest {

    private lateinit var dir: Path
    private lateinit var store: ConfigStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-places")
        store = ConfigStore(dir.resolve("config.json"))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun boardSurvivesSaveAndLoad() {
        val atlas = dir.resolve("atlas").projectKey()
        val orbit = dir.resolve("orbit").projectKey()
        val board = PlaceBoard(
            pinned = listOf("agent:claude-code", "project:$atlas"),
            recent = listOf("project:$orbit"),
            sets = listOf(ProjectSet("KMP apps", listOf(atlas, orbit))),
        )

        store.save(AppConfig(places = board))

        assertEquals(board, store.load().places)
    }

    @Test
    fun configWrittenBeforeSetsExistedStillLoads() {
        dir.resolve("config.json").writeText("""{"projects":["/a"]}""")

        assertEquals(PlaceBoard(), store.load().places)
    }

    @Test
    fun pinTogglesBothWays() {
        val pinned = PlaceBoard().withPinToggled("project:/a")

        assertEquals(listOf("project:/a"), pinned.pinned)
        assertEquals(emptyList(), pinned.withPinToggled("project:/a").pinned)
    }

    @Test
    fun recentKeepsLastVisitFirstWithoutDuplicates() {
        val board = PlaceBoard()
            .withVisited("a").withVisited("b").withVisited("a")

        assertEquals(listOf("a", "b"), board.recent)
    }

    @Test
    fun recentIsCappedAndDropsTheOldest() {
        val board = (1..RECENT_PLACES_LIMIT + 2).fold(PlaceBoard()) { acc, i -> acc.withVisited("p$i") }

        assertEquals(RECENT_PLACES_LIMIT, board.recent.size)
        assertEquals("p${RECENT_PLACES_LIMIT + 2}", board.recent.first())
        assertEquals(false, "p1" in board.recent)
    }

    @Test
    fun projectBelongsToOneSetAtATime() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP"), ProjectSet("iOS")))
            .withProjectInSet("/code/atlas", "KMP")
            .withProjectInSet("/code/atlas", "iOS")

        assertEquals(emptyList(), board.sets.first { it.name == "KMP" }.projects)
        assertEquals(listOf("/code/atlas"), board.sets.first { it.name == "iOS" }.projects)
        assertEquals("iOS", board.setOf("/code/atlas"))
    }

    @Test
    fun removingFromEverySetLeavesTheProjectUngrouped() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas"))))
            .withProjectInSet("/code/atlas", null)

        assertNull(board.setOf("/code/atlas"))
    }

    @Test
    fun deletingASetKeepsItsProjects() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/code/atlas")))).withoutSet("KMP")

        assertEquals(emptyList(), board.sets)
        assertNull(board.setOf("/code/atlas"))
    }

    @Test
    fun duplicateOrBlankSetNamesAreRefused() {
        val board = PlaceBoard().withSet("KMP").withSet("KMP").withSet(" ")

        assertEquals(listOf("KMP"), board.sets.map { it.name })
    }

    @Test
    fun renameKeepsMembersAndRefusesATakenName() {
        val board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf("/a")), ProjectSet("iOS")))
            .withSetRenamed("KMP", "KMP apps")

        assertEquals(listOf("KMP apps", "iOS"), board.sets.map { it.name })
        assertEquals(listOf("/a"), board.sets.first().projects)
        assertEquals(board, board.withSetRenamed("KMP apps", "iOS"))
    }

    @Test
    fun forgettingAPlaceClearsPinAndRecent() {
        val board = PlaceBoard(pinned = listOf("project:/a"), recent = listOf("project:/a", "project:/b"))
            .withoutPlace("project:/a")

        assertEquals(emptyList(), board.pinned)
        assertEquals(listOf("project:/b"), board.recent)
    }
}
