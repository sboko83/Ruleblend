package dev.ruleblend.app.resolve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The plan a batch says before it runs. Everything here is about what will and will not be written:
 * a conflict batch can discard text somebody typed by hand in thirty files, so the difference between
 * a marked row, a skipped row and a row that has no strategy at all has to hold in the model.
 */
class ResolveApplyTest {

    @Test fun `only marked rows enter the plan`() {
        val plan = resolvePlan(groups(), marked = setOf(key("ledger", "git-style")), base = ResolveStrategy.RESTORE)

        assertEquals(listOf("ledger"), plan.places.map { it.placeId })
        assertEquals(listOf("git-style"), plan.places.single().steps.map { it.blockId })
        assertEquals(1, plan.restores)
        assertEquals(0, plan.saves)
        assertEquals(0, plan.skipped)
    }

    @Test fun `an override wins over the base strategy and the rest keeps inheriting it`() {
        val plan = resolvePlan(
            groups(),
            marked = allKeys(),
            base = ResolveStrategy.RESTORE,
            overrides = mapOf(key("ledger", "git-style") to ResolveStrategy.SAVE_AS_VERSION),
        )

        assertEquals(1, plan.saves)
        assertEquals(2, plan.restores)
        assertEquals(3, plan.writes)
        assertEquals(2, plan.places.size)
    }

    @Test fun `a skipped row is counted and written nowhere`() {
        val plan = resolvePlan(
            groups(),
            marked = allKeys(),
            base = ResolveStrategy.SKIP,
            overrides = mapOf(key("nimbus", "kotlin-style") to ResolveStrategy.RESTORE),
        )

        assertEquals(2, plan.skipped)
        assertEquals(listOf("nimbus"), plan.places.map { it.placeId })
        assertEquals(1, plan.writes)
    }

    @Test fun `a place is dropped when every row of it is skipped`() {
        val plan = resolvePlan(groups(), marked = allKeys(), base = ResolveStrategy.SKIP)

        assertTrue(plan.empty, "a plan of nothing but skips has nothing to write")
        assertEquals(3, plan.skipped)
        assertEquals(0, plan.writes)
    }

    @Test fun `one block of one place is one write however many files hold it`() {
        val place = ResolveGroup(
            "ledger",
            "ledger-kmp",
            listOf(
                row("ledger", "AGENTS.md", "git-style"),
                row("ledger", "CLAUDE.md", "git-style"),
            ),
        )

        val plan = resolvePlan(listOf(place), marked = setOf(key("ledger", "git-style")), base = ResolveStrategy.RESTORE)

        assertEquals(1, plan.writes, "the write covers every file of the place, so the rows are one decision")
    }

    @Test fun `select-all offers the hand-edited rows and nothing else`() {
        assertEquals(
            setOf(key("ledger", "git-style"), key("ledger", "api-naming"), key("nimbus", "kotlin-style")),
            resolveKeys(groups()),
        )
        assertEquals(emptySet(), resolveKeys(listOf(ResolveGroup("nimbus", "nimbus-api", listOf(legacyRow())))))
    }

    @Test fun `the default applier writes nothing and still reports what was left alone`() {
        val plan = resolvePlan(groups(), marked = allKeys(), base = ResolveStrategy.SKIP)

        assertEquals(ResolveReport(skipped = 3), ResolveApplier.None.apply(plan))
    }

    // ---- harness ----

    private fun key(place: String, block: String) = "$place#$block"

    private fun allKeys() = resolveKeys(groups())

    private fun row(place: String, file: String, block: String) =
        ResolveRow(placeId = place, placeName = place, file = file, blockId = block, blockName = block, version = 2)

    private fun legacyRow() = ResolveRow(placeId = "nimbus", placeName = "nimbus-api", file = "AGENTS.md")

    private fun groups() = listOf(
        ResolveGroup(
            "ledger",
            "ledger-kmp",
            listOf(row("ledger", "AGENTS.md", "git-style"), row("ledger", "CODEX.md", "api-naming")),
        ),
        ResolveGroup("nimbus", "nimbus-api", listOf(row("nimbus", "AGENTS.md", "kotlin-style"))),
    )
}
