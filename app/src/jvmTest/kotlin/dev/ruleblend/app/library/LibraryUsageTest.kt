package dev.ruleblend.app.library

import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryUsageTest {
    private val ruleA = LibraryObjectKey(LibraryObjectKind.RULE, "rule-a")
    private val ruleB = LibraryObjectKey(LibraryObjectKind.RULE, "rule-b")
    private val skill = LibraryObjectKey(LibraryObjectKind.SKILL, "skill-a")
    private val group = LibraryObjectKey(LibraryObjectKind.GROUP, "mobile")

    private val catalog = LibraryCatalog.build(
        blocks = listOf(Block("rule-a", "Rule A"), Block("rule-b", "Rule B")),
        groups = listOf(Group(ALL_GROUP_ID, "all"), Group("mobile", "Mobile", blockIds = listOf("rule-a", "rule-b"))),
        skills = emptyList(),
        scopes = emptyMap(),
    )

    private val groups = catalog.objects.filter { it.key.kind == LibraryObjectKind.GROUP }

    private val places = listOf(
        LibraryPlaceUsage(
            id = "agent:claude-code",
            name = "Claude Code",
            kind = LibraryPlaceKind.AGENT,
            installs = mapOf(
                ruleA to LibraryInstall(InstallStatus.SYNCED),
                ruleB to LibraryInstall(InstallStatus.MODIFIED),
            ),
        ),
        LibraryPlaceUsage(
            id = "project:/code/atlas",
            name = "atlas",
            kind = LibraryPlaceKind.PROJECT,
            installs = mapOf(ruleA to LibraryInstall(InstallStatus.UPDATE_AVAILABLE)),
        ),
        LibraryPlaceUsage(
            id = "project:/code/orbit",
            name = "orbit",
            kind = LibraryPlaceKind.PROJECT,
            installs = emptyMap(),
        ),
    )

    @Test
    fun `index reports every place holding an object and the status of each`() {
        val usage = LibraryUsageIndex.build(places, groups)

        assertEquals(2, usage.installCount(ruleA))
        assertEquals(listOf("Claude Code", "atlas"), usage.sitesOf(ruleA).map { it.placeName })
        assertEquals(
            mapOf(InstallStatus.SYNCED to 1, InstallStatus.UPDATE_AVAILABLE to 1),
            usage.statusCounts(ruleA),
        )
        assertEquals(LibraryPlaceKind.AGENT, usage.sitesOf(ruleB).single().kind)
        assertFalse(usage.isInstalled(skill))
        assertEquals(0, usage.installCount(skill))
    }

    @Test
    fun `a group is installed wherever a member is, with the worst member status`() {
        val usage = LibraryUsageIndex.build(places, groups)

        assertEquals(2, usage.installCount(group))
        // Claude Code holds a synced and a hand-edited member: the hand edit must not be hidden.
        assertEquals(InstallStatus.MODIFIED, usage.sitesOf(group).first { it.placeId == "agent:claude-code" }.install.status)
        assertEquals(InstallStatus.UPDATE_AVAILABLE, usage.sitesOf(group).first { it.placeId == "project:/code/atlas" }.install.status)
        assertTrue(usage.sitesOf(group).none { it.placeId == "project:/code/orbit" })
    }

    @Test
    fun `an unscanned index is not the same answer as installed nowhere`() {
        assertTrue(LibraryUsageIndex.EMPTY.isEmpty)
        assertFalse(LibraryUsageIndex.build(places, groups).isEmpty)
        // A place with nothing from the library still counts as scanned.
        assertFalse(LibraryUsageIndex.build(listOf(places.last())).isEmpty)
    }

    @Test
    fun `a pure project redirect does not turn a managed place grey`() {
        val place = LibraryPlaceUsage(
            id = "project:/code/atlas",
            name = "atlas",
            kind = LibraryPlaceKind.PROJECT,
            installs = mapOf(ruleA to LibraryInstall(InstallStatus.SYNCED)),
            files = listOf(
                PlaceFile(
                    path = Path.of("CLAUDE.md"),
                    name = "CLAUDE.md",
                    exists = true,
                    mode = TargetOwnershipMode.NONE,
                    unmanagedLines = 0,
                    drift = false,
                    isPointer = true,
                ),
            ),
        )

        assertEquals(setOf(dev.ruleblend.app.theme.StatusMark.MANAGED), place.marks())
    }

    @Test
    fun `the usage facet filters real installs and stays inert before the first scan`() {
        val usage = LibraryUsageIndex.build(places, groups)
        val installed = LibraryFilters(usage = LibraryUsageFilter.INSTALLED)
        val unused = LibraryFilters(usage = LibraryUsageFilter.UNUSED)

        assertEquals(
            listOf("Rule A", "Rule B", "Mobile"),
            catalog.filtered(installed, usage).map { it.name },
        )
        assertEquals(listOf("all"), catalog.filtered(unused, usage).map { it.name })
        assertEquals(catalog.objects.size, catalog.filtered(unused).size)
    }
}
