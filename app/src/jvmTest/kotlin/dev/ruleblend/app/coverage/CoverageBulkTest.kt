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
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a marked set of rows resolves into before anything is written. The plan is the whole safety of
 * a mass action: it is where "install this group into that set" becomes a countable list of the same
 * single writes a cell click performs, and where the pairs that must not be written are dropped.
 */
class CoverageBulkTest {

    @Test fun `a group and one of its members count that member once`() {
        val snapshot = snapshot(expanded = setOf("group:kmp"))
        val group = snapshot.rows.first { it.id == "group:kmp" }
        val member = snapshot.rows.first { it.id == "group:kmp/RULE:style" }

        val keys = coverageMarkedKeys(snapshot.rows, setOf(group.id, member.id))

        assertEquals(listOf(rule("style"), rule("scoped")), keys)
    }

    @Test fun `a collapsed row means what it means opened`() {
        val collapsed = snapshot()
        val opened = snapshot(expanded = setOf("group:kmp"))

        assertEquals(
            coverageMarkedKeys(collapsed.rows, setOf("group:kmp")),
            coverageMarkedKeys(opened.rows, setOf("group:kmp")),
        )
    }

    @Test fun `the row standing for the whole library cannot be marked`() {
        val snapshot = snapshot()

        assertTrue(snapshot.rows.first { it.id == "all" }.markable.not())
        assertTrue(snapshot.rows.first { it.id == "group:kmp" }.markable)
    }

    @Test fun `installing plans only the places missing the object`() {
        val snapshot = snapshot()

        val plan = plan(CoverageBulkAction.INSTALL, listOf(rule("style")), snapshot.column("set:KMP"))

        // ledger already holds it in sync; rewriting it would be a write that changed nothing.
        assertEquals(listOf("project:${fixturePath("/tmp/mesh")}" to listOf(rule("style"))), plan.pairs())
        assertEquals(1, plan.writes)
        assertEquals(1, plan.places)
        assertEquals(0, plan.outOfScope)
    }

    @Test fun `a pinned rule is counted out of the plan, not written and refused later`() {
        val snapshot = snapshot()

        val plan = plan(
            CoverageBulkAction.INSTALL,
            listOf(rule("style"), rule("scoped")),
            snapshot.column("set:KMP"),
        )

        // "scoped" belongs to ledger alone: one place of the set can take it, the other cannot.
        assertEquals(
            listOf(
                "project:${fixturePath("/tmp/ledger")}" to listOf(rule("scoped")),
                "project:${fixturePath("/tmp/mesh")}" to listOf(rule("style")),
            ),
            plan.pairs(),
        )
        assertEquals(1, plan.outOfScope)
    }

    @Test fun `a skill is left out of a place with no skills directory`() {
        val snapshot = snapshot()

        val plan = plan(CoverageBulkAction.INSTALL, listOf(skill("bsv-docs")), snapshot.column("place:agent:plain"))

        assertTrue(plan.empty)
        assertEquals(1, plan.outOfScope)
    }

    @Test fun `removing takes what is there and does not ask about scope`() {
        val snapshot = snapshot()

        val plan = plan(
            CoverageBulkAction.REMOVE,
            listOf(rule("style"), rule("scoped")),
            snapshot.column("set:KMP"),
        )

        // "scoped" sits in mesh though it is pinned to ledger — it landed there before it was pinned,
        // and taking it out narrows the claim rather than widening it.
        assertEquals(
            listOf(
                "project:${fixturePath("/tmp/ledger")}" to listOf(rule("style")),
                "project:${fixturePath("/tmp/mesh")}" to listOf(rule("scoped")),
            ),
            plan.pairs(),
        )
        assertEquals(0, plan.outOfScope)
    }

    @Test fun `update reaches the whole fleet but only the outdated copies`() {
        val plan = plan(CoverageBulkAction.UPDATE, listOf(rule("style"), rule("scoped")), column = null)

        // No column was chosen and none is needed: "everywhere" is every place holding an old copy.
        assertEquals(listOf("agent:claude" to listOf(rule("style"))), plan.pairs())
        assertNull(plan.target)
    }

    @Test fun `update never plans a copy edited by hand`() {
        val edited = places().map { place ->
            if (place.id != "agent:claude") place
            else place.copy(installs = mapOf(rule("style") to LibraryInstall(InstallStatus.MODIFIED)))
        }

        val plan = coverageBulkPlan(CoverageBulkAction.UPDATE, listOf(rule("style")), null, catalog(), edited)

        // The installer would refuse it as modified; offering it in the plan would promise otherwise.
        assertTrue(plan.empty)
    }

    @Test fun `a plan with nothing left to do is empty rather than a batch of no-ops`() {
        val snapshot = snapshot()

        // Claude holds an outdated copy: installing again is update's job, not install's.
        val plan = plan(CoverageBulkAction.INSTALL, listOf(rule("style")), snapshot.column("place:agent:claude"))

        assertTrue(plan.empty)
        assertEquals(0, plan.writes)
    }

    // ---- fixture ----

    private fun plan(action: CoverageBulkAction, keys: List<LibraryObjectKey>, column: CoverageColumn?) =
        coverageBulkPlan(action, keys, column, catalog(), places())

    private fun CoverageBulkPlan.pairs(): List<Pair<String, List<LibraryObjectKey>>> =
        steps.map { it.placeId to it.keys }

    private fun snapshot(expanded: Set<String> = emptySet()) = coverageMatrix(
        catalog = catalog(),
        scanned = places(),
        board = PlaceBoard(sets = listOf(ProjectSet("KMP", listOf(fixturePath("/tmp/ledger"), fixturePath("/tmp/mesh"))))),
        expansion = CoverageExpansion(rows = expanded, columns = setOf("agents")),
    )

    private fun CoverageSnapshot.column(id: String) = columns.first { it.id == id }

    private fun catalog() = LibraryCatalog.build(
        blocks = listOf(Block(id = "style", name = "style"), Block(id = "scoped", name = "scoped")),
        groups = listOf(Group(id = "kmp", name = "KMP", blockIds = listOf("style", "scoped"))),
        skills = listOf(Skill(id = "bsv-docs", name = "bsv-docs")),
        scopes = mapOf("scoped" to fixturePath("/tmp/ledger")),
    )

    /**
     * Two projects of one set, an agent holding an outdated copy, and an agent that reads no skills
     * directory — one place per rule the plan has to respect.
     */
    private fun places() = listOf(
        LibraryPlaceUsage(
            id = "agent:claude",
            name = "Claude",
            kind = LibraryPlaceKind.AGENT,
            installs = mapOf(rule("style") to LibraryInstall(InstallStatus.UPDATE_AVAILABLE)),
        ),
        LibraryPlaceUsage(
            id = "agent:plain",
            name = "Plain",
            kind = LibraryPlaceKind.AGENT,
            installs = emptyMap(),
            supportsSkills = false,
        ),
        LibraryPlaceUsage(
            id = "project:${fixturePath("/tmp/ledger")}",
            name = "ledger",
            kind = LibraryPlaceKind.PROJECT,
            installs = mapOf(rule("style") to LibraryInstall(InstallStatus.SYNCED)),
        ),
        LibraryPlaceUsage(
            id = "project:${fixturePath("/tmp/mesh")}",
            name = "mesh",
            kind = LibraryPlaceKind.PROJECT,
            installs = mapOf(rule("scoped") to LibraryInstall(InstallStatus.SYNCED)),
        ),
    )

    private fun rule(id: String) = LibraryObjectKey(LibraryObjectKind.RULE, id)

    private fun skill(id: String) = LibraryObjectKey(LibraryObjectKind.SKILL, id)
}
