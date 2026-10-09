package dev.ruleblend.app.home

import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.PlaceFile
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.TargetOwnershipMode
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The attention feed as a pure function of one scan: which classes of problem exist, how they are
 * counted and in what order they are offered. Reading disk is the scanner's job and is covered by
 * [HomeScanTest]; nothing here touches a file.
 */
class HomeAttentionTest {

    @Test fun `each class of problem produces one card, worst first`() {
        val snapshot = homeSnapshot(
            listOf(
                place("ledger-kmp", installs = mapOf(rule("a") to modified, rule("b") to update)),
                place("transit-ios", files = listOf(handwritten(lines = 12), legacy())),
            ),
            agents = listOf(DetectedAgent("kimi-code", "Kimi Code", "agent:kimi-code", "~/.kimi/AGENTS.md", eligibleProjects = 25)),
        )

        assertEquals(
            listOf(
                AttentionKind.CONFLICTS,
                AttentionKind.UPDATES,
                AttentionKind.UNADOPTED,
                AttentionKind.LEGACY,
                AttentionKind.NEW_AGENT,
            ),
            snapshot.cards.map { it.kind },
        )
    }

    @Test fun `a card counts occurrences and the places they sit in`() {
        val snapshot = homeSnapshot(
            listOf(
                place("ledger-kmp", installs = mapOf(rule("a") to modified, rule("b") to modified)),
                place("transit-ios", installs = mapOf(rule("a") to modified)),
                place("relay-cli", installs = mapOf(rule("a") to synced)),
            ),
        )

        val card = snapshot.card(AttentionKind.CONFLICTS)!!
        assertEquals(3, card.total)
        assertEquals(2, card.places)
        assertEquals(listOf("ledger-kmp", "transit-ios"), card.rows.map { it.name })
        assertNull(snapshot.card(AttentionKind.UPDATES))
    }

    @Test fun `unadopted lines are summed per place and name their files`() {
        val snapshot = homeSnapshot(
            listOf(
                place("mesh-sync", files = listOf(handwritten("AGENTS.md", 12), handwritten("CLAUDE.md", 7))),
                place("relay-cli", files = listOf(handwritten("AGENTS.md", 0))),
            ),
        )

        val card = snapshot.card(AttentionKind.UNADOPTED)!!
        assertEquals(19, card.total)
        assertEquals(1, card.places)
        assertEquals(listOf("AGENTS.md", "CLAUDE.md"), card.rows.single().files)
    }

    @Test fun `legacy markup is counted in files, not in lines`() {
        val snapshot = homeSnapshot(
            listOf(
                place("dune-sandbox", files = listOf(legacy("AGENTS.md"), legacy("CLAUDE.md"))),
                place("ledger-kmp", files = listOf(handwritten(lines = 40))),
            ),
        )

        val card = snapshot.card(AttentionKind.LEGACY)!!
        assertEquals(2, card.total)
        assertEquals(listOf("dune-sandbox"), card.rows.map { it.name })
    }

    @Test fun `every detected agent gets its own card`() {
        val snapshot = homeSnapshot(
            emptyList(),
            agents = listOf(
                DetectedAgent("kimi-code", "Kimi Code", "agent:kimi-code", "~/.kimi/AGENTS.md", 25),
                DetectedAgent("zcode", "ZCode", "agent:zcode", "~/.zcode/AGENTS.md", 25),
            ),
        )

        val cards = snapshot.cards.filter { it.kind == AttentionKind.NEW_AGENT }
        assertEquals(listOf("Kimi Code", "ZCode"), cards.map { it.agent?.name })
        assertEquals(25, cards.first().total)
    }

    @Test fun `a scanned fleet with nothing to do is all clear, an unscanned one is not`() {
        val snapshot = homeSnapshot(listOf(place("relay-cli", installs = mapOf(rule("a") to synced))))

        assertTrue(snapshot.allClear)
        assertEquals(emptyList(), snapshot.cards)
        assertFalse(HomeSnapshot.EMPTY.allClear)
    }

    @Test fun `fleet health splits places by their worst status`() {
        val snapshot = homeSnapshot(
            listOf(
                place("ledger-kmp", installs = mapOf(rule("a") to synced, rule("b") to modified, rule("c") to update)),
                place("relay-cli", installs = mapOf(rule("a") to update)),
                place("quartz-kmp", installs = mapOf(rule("a") to synced)),
            ),
        )

        val fleet = snapshot.fleet
        assertEquals(3, fleet.total)
        assertEquals(1, fleet.clean)
        assertEquals(2, fleet.withUpdates)
        assertEquals(1, fleet.withConflicts)
        assertEquals(setOf(StatusMark.MANAGED, StatusMark.CONFLICT, StatusMark.UPDATE), fleet.places.first().marks)
        assertEquals(3, fleet.places.first().total)
    }

    // ---- harness ----

    private val synced = LibraryInstall(InstallStatus.SYNCED)
    private val update = LibraryInstall(InstallStatus.UPDATE_AVAILABLE)
    private val modified = LibraryInstall(InstallStatus.MODIFIED)

    private fun rule(id: String) = LibraryObjectKey(LibraryObjectKind.RULE, id)

    private fun place(
        name: String,
        installs: Map<LibraryObjectKey, LibraryInstall> = emptyMap(),
        files: List<PlaceFile> = emptyList(),
    ) = LibraryPlaceUsage("project:/tmp/$name", name, LibraryPlaceKind.PROJECT, installs, files)

    private fun handwritten(name: String = "AGENTS.md", lines: Int) =
        PlaceFile(Path.of(name), name, exists = true, mode = TargetOwnershipMode.PARTIAL, unmanagedLines = lines, drift = false)

    private fun legacy(name: String = "AGENTS.md") =
        PlaceFile(Path.of(name), name, exists = true, mode = TargetOwnershipMode.LEGACY, unmanagedLines = 0, drift = false)
}
