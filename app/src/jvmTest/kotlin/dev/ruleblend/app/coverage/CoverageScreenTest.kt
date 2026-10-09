package dev.ruleblend.app.coverage

import dev.ruleblend.app.fixturePath
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performMouseInput
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * The Coverage screen against ready snapshots: a cell must read as a fraction, an impossible cell as
 * a dash, and both must stay legible in either theme — a matrix that only reads in light is a matrix
 * that answers nothing at night.
 */
class CoverageScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings
    private var filters = CoverageFilters()
    private var visibility: Pair<String, Boolean>? = null
    private var rescans = 0
    private var openedLibrary = 0
    private var openedResolve = 0
    private var openedSettings = 0
    private var expansion by mutableStateOf(CoverageExpansion(columns = setOf("agents")))
    private var notice by mutableStateOf<CoverageNotice?>(null)
    private var writing by mutableStateOf(false)
    private var scanning by mutableStateOf(false)
    private var openedPlace: String? = null
    private val writes = mutableListOf<Triple<LibraryObjectKey, String, Boolean>>()
    private var marked by mutableStateOf(emptySet<String>())
    private var report by mutableStateOf<CoverageBulkReport?>(null)
    private val applied = mutableListOf<CoverageBulkPlan>()

    @Test fun `toolbar controls wrap without overlapping at a narrow width`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                Column(Modifier.width(360.dp).testTag("toolbar-container")) {
                    Toolbar(
                        fleet(), filters, false, false, null, RuStrings, fleet().fold(expansion),
                        onFilters = {}, onRescan = {}, onAgentVisible = { _, _ -> },
                        onOpenResolve = {}, onOpenAgentSettings = {}, onExpandAllRows = {},
                        onCollapseAllRows = {}, onExpandAllColumns = {}, onCollapseAllColumns = {},
                    )
                }
            }
        }
        val container = compose.onNodeWithTag("toolbar-container").getUnclippedBoundsInRoot()
        val controls = listOf("rule", "subagent", "skill", "mcp", "installed", "attention").map {
            compose.onNodeWithTag("coverage-filter-$it")
        } + listOf(
            compose.onNodeWithTag("coverage-agents-panel"),
            compose.onNodeWithContentDescription(RuStrings.homeRescan),
        ) + listOf("rows", "columns").flatMap { group ->
            listOf("expand", "collapse").map { compose.onNodeWithTag("coverage-$group-$it-all") }
        }
        val bounds = controls.map { it.assertIsDisplayed().getUnclippedBoundsInRoot() }
        bounds.forEachIndexed { index, rect ->
            assertTrue(rect.left >= container.left && rect.right <= container.right)
            bounds.drop(index + 1).forEach { other ->
                assertTrue(rect.right <= other.left || other.right <= rect.left ||
                    rect.bottom <= other.top || other.bottom <= rect.top)
            }
        }
        listOf("rows", "columns").forEach { group ->
            val expand = compose.onNodeWithTag("coverage-$group-expand-all").getUnclippedBoundsInRoot()
            val collapse = compose.onNodeWithTag("coverage-$group-collapse-all").getUnclippedBoundsInRoot()
            assertEquals(expand.top, collapse.top)
            assertEquals(expand.bottom, collapse.bottom)
        }
    }

    @Test fun `icon actions stay disabled while coverage is writing`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Column {
                    Toolbar(
                        fleet(), filters, false, true, null, strings, fleet().fold(expansion),
                        onFilters = {}, onRescan = {}, onAgentVisible = { _, _ -> },
                        onOpenResolve = {}, onOpenAgentSettings = {}, onExpandAllRows = {},
                        onCollapseAllRows = {}, onExpandAllColumns = {}, onCollapseAllColumns = {},
                    )
                    BulkBar(fleet(), setOf("type:RULE"), strings, true, onClearMarks = {}, onPlan = { _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription(strings.homeRescan).assertIsNotEnabled()
        listOf("install", "remove", "update").forEach {
            compose.onNodeWithTag(coverageBulkTag(it)).assertIsNotEnabled()
        }
    }

    @Test fun `an unscanned matrix offers a scan instead of an empty grid`() {
        inBothThemes(CoverageSnapshot.EMPTY) {
            compose.onNodeWithText(strings.homeNotScannedTitle.uppercase()).assertIsDisplayed()
            compose.onNodeWithText(strings.coverageNotScannedText).assertIsDisplayed()
        }
        compose.onNodeWithText(strings.homeScanNow).performClick()
        compose.runOnIdle { assertEquals(1, rescans) }
    }

    @Test fun `an empty library is its own state, not a matrix of zeros`() {
        val snapshot = coverageMatrix(LibraryCatalog.build(emptyList(), emptyList(), emptyList(), emptyMap()), places())
        inBothThemes(snapshot) {
            compose.onNodeWithText(strings.homeEmptyLibraryTitle.uppercase()).assertIsDisplayed()
        }
        compose.onNodeWithText(strings.homeOpenLibrary).performClick()
        compose.runOnIdle { assertEquals(1, openedLibrary) }
    }

    @Test fun `a cell reads as installed over members, an impossible one as a dash`() {
        inBothThemes(fleet()) {
            compose.onNodeWithText("Claude Code").assertIsDisplayed()
            compose.onNodeWithText("KMP apps · ${strings.coverageBandSet}").assertIsDisplayed()
            compose.onNodeWithText(strings.coverageRowAll).assertIsDisplayed()
            // "a" of four objects in the agent, "a" and "b" of four in the set.
            compose.onNodeWithText("1/4").assertIsDisplayed()
            compose.onNodeWithText("2/4").assertIsDisplayed()
        }
        // The impossible skills cell and the permanently visible legend each print a dash.
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    @Test fun `the legend and the corner say what the matrix is made of`() {
        inBothThemes(fleet()) {
            compose.onNodeWithText(strings.coverageCorner(4, 2, 1)).assertIsDisplayed()
            compose.onNodeWithText(strings.coverageLegendOutOfScopeShort).assertIsDisplayed()
            compose.onNodeWithText(strings.coverageChipConflicts(1, 0, 1)).assertIsDisplayed()
            compose.onNodeWithText(strings.coverageChipUpdates(1, 0, 1)).assertIsDisplayed()
        }
    }

    @Test fun `attention and navigation controls dispatch their own actions`() {
        show(fleet(), ThemeMode.LIGHT)
        compose.onNodeWithTag("coverage-open-resolve").performClick()
        compose.onNodeWithTag("coverage-updates").performClick()
        compose.runOnIdle {
            assertEquals(1, openedResolve)
            assertTrue(filters.needsAttention)
        }
        compose.onNodeWithTag("coverage-agents-panel").performClick()
        compose.onNodeWithTag("coverage-agent-settings").performClick()
        compose.runOnIdle { assertEquals(1, openedSettings) }
        compose.onNodeWithTag("coverage-agent-settings").assertDoesNotExist()
        compose.onNodeWithTag("coverage-filter-group-agents").assertDoesNotExist()
        compose.onNodeWithTag("coverage-legend").assertIsDisplayed()
    }

    @Test fun `fold pairs follow the visible rows and columns`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag("coverage-rows-expand-all").assertIsEnabled().performClick()
        compose.onNodeWithTag("coverage-rows-expand-all").assertIsNotEnabled()
        compose.onNodeWithTag("coverage-rows-collapse-all").assertIsEnabled().performClick()
        compose.onNodeWithTag("coverage-rows-collapse-all").assertIsNotEnabled()
        compose.onNodeWithTag("coverage-columns-expand-all").assertIsEnabled().performClick()
        compose.onNodeWithTag("coverage-columns-expand-all").assertIsNotEnabled()
        compose.onNodeWithTag("coverage-columns-collapse-all").assertIsEnabled().performClick()
        compose.onNodeWithTag("coverage-columns-collapse-all").assertIsNotEnabled()
    }

    @Test fun `empty coverage disables both fold pairs`() {
        show(CoverageSnapshot.EMPTY, ThemeMode.LIGHT)
        listOf("rows", "columns").forEach { group ->
            listOf("expand", "collapse").forEach {
                compose.onNodeWithTag("coverage-$group-$it-all").assertIsNotEnabled()
            }
        }
    }

    @Test fun `a type chip asks for a narrower matrix, it does not redraw one itself`() {
        show(fleet(), ThemeMode.LIGHT)

        // The chip and the type row share a label; the chip is the toolbar one, drawn first.
        compose.onAllNodesWithText(strings.intSkills).onFirst().performClick()

        compose.runOnIdle {
            assertEquals(setOf(LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP), filters.kinds)
        }
    }

    @Test fun `the agents panel is the way back for a hidden agent`() {
        show(fleet(), ThemeMode.DARK)

        compose.onNodeWithTag("coverage-agents-panel").performClick()
        compose.onNodeWithText(strings.coverageAgentsNote).assertIsDisplayed()
        compose.onNodeWithTag(coverageAgentTag("zcode")).performClick()
        compose.runOnIdle { assertEquals("zcode" to true, visibility) }

        compose.onNodeWithTag(coverageAgentTag("claude-code")).performClick()
        compose.runOnIdle { assertEquals("claude-code" to false, visibility) }
    }

    @Test fun `an opened row shows its objects, and an object in one place shows a mark`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()

        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onNodeWithText("a").assertIsDisplayed()
            compose.onNodeWithText("c").assertIsDisplayed()
            // Three rules × two agent columns are the six cells one click can write; only "a" in
            // Claude Code holds anything, so one mark to remove and five invitations to install.
            compose.onAllNodesWithContentDescription(
                leafHint(strings.coverageLegendSynced, strings.coverageRemoveHere),
            ).assertCountEquals(1)
            compose.onAllNodesWithContentDescription(leafHint(strings.coverageLegendMissing, strings.coverageInstallHere)).assertCountEquals(5)
        }
        compose.onAllNodesWithContentDescription(
            strings.coverageCellObjects(2, 3) + "\n" + strings.coverageCellSplit(0, 1, 1),
        ).onFirst().assertIsDisplayed()
        compose.onNodeWithTag(coverageColumnTag("set:KMP apps")).performClick()
        compose.onAllNodesWithContentDescription(
            leafHint(strings.coverageLegendModified, strings.coverageRemoveHere),
        ).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(
            leafHint(strings.coverageLegendUpdate, strings.coverageRemoveHere),
        ).assertCountEquals(1)
    }

    @Test fun `a click on one object in one place asks for that one write`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()

        compose.onNodeWithTag(cell("type:RULE/RULE:c", "place:agent:claude-code")).performClick()
        compose.onNodeWithTag(cell("type:RULE/RULE:a", "place:agent:claude-code")).performClick()

        compose.runOnIdle {
            assertEquals(
                listOf(
                    Triple(rule("c"), "agent:claude-code", true),
                    Triple(rule("a"), "agent:claude-code", false),
                ),
                writes,
            )
        }
    }

    @Test fun `writes and scans disable leaf cells until the operation finishes`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()
        val leaf = compose.onNodeWithTag(cell("type:RULE/RULE:c", "place:agent:claude-code"))
        compose.runOnIdle { writing = true }
        leaf.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()); writing = false; scanning = true }
        leaf.assertIsNotEnabled().performClick()
        compose.runOnIdle { assertTrue(writes.isEmpty()); scanning = false }
        leaf.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, writes.size) }
    }

    @Test fun `aggregate tracks persist at zero and explain objects versus places`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()
        val empty = compose.onNodeWithTag(cell("type:RULE", "place:agent:zcode"))
        empty.assertContentDescriptionEquals(strings.coverageCellObjects(0, 3))
        empty.onChildren().filter(hasTestTag("coverage-cell-track")).assertCountEquals(1)
        compose.onNodeWithTag(cell("type:RULE/RULE:c", "set:KMP apps"))
            .assertContentDescriptionEquals(strings.coverageCellPlaces(0, 2))
        compose.onNodeWithText(strings.coverageLegendLeafInstalled).assertIsDisplayed()
        compose.onNodeWithText(strings.coverageLegendLeafMissing).assertIsDisplayed()
    }

    @Test fun `row titles use stable slots and omit absent versions`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()
        val ruleTitle = compose.onNodeWithTag("coverage-title-type:RULE").getUnclippedBoundsInRoot()
        val skillTitle = compose.onNodeWithTag("coverage-title-type:SKILL").getUnclippedBoundsInRoot()
        val objectTitle = compose.onNodeWithTag("coverage-title-type:RULE/RULE:a").getUnclippedBoundsInRoot()
        assertEquals(ruleTitle.left, skillTitle.left)
        assertEquals(ruleTitle.left, compose.onNodeWithTag("coverage-title-all").getUnclippedBoundsInRoot().left)
        assertEquals(ruleTitle.left + 18.dp, objectTitle.left)
        val objectCell = compose.onNodeWithTag(cell("type:RULE/RULE:a", "place:agent:claude-code"))
            .getUnclippedBoundsInRoot()
        assertEquals(40.dp, objectCell.bottom - objectCell.top)
        compose.onAllNodesWithText("@").assertCountEquals(0)
    }

    @Test fun `missing and blank versions do not leave an at sign`() {
        val expanded = fleet(CoverageExpansion(rows = setOf("type:RULE"), columns = setOf("agents")))
        show(expanded.copy(rows = expanded.rows.map {
            when (it.key?.id) {
                "a" -> it.copy(version = null)
                "b" -> it.copy(version = "")
                "c" -> it.copy(version = "1.2.0")
                else -> it
            }
        }), ThemeMode.LIGHT)
        compose.onAllNodesWithText("@").assertCountEquals(0)
        compose.onNodeWithText("@1.2.0").assertIsDisplayed()
    }

    @Test fun `hovering a missing leaf shows its status and install action`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:RULE")).performClick()
        val hint = leafHint(strings.coverageLegendMissing, strings.coverageInstallHere)
        compose.onNodeWithTag(cell("type:RULE/RULE:c", "place:agent:claude-code"))
            .performMouseInput { enter(center) }
        compose.waitUntil(2_000) { compose.onAllNodesWithText(hint).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(hint).assertIsDisplayed()
    }

    @Test fun `an impossible cell is not a button, and an aggregate cell is not one either`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageRowTag("type:SKILL")).performClick()

        // The agent has no skills directory: the drill-down draws the same dash the aggregate does.
        compose.onNodeWithTag(cell("type:SKILL/SKILL:pdf", "place:agent:zcode")).performClick()
        // A set column stands for several places, and the type row for several objects.
        compose.onNodeWithTag(cell("type:SKILL/SKILL:pdf", "set:KMP apps")).performClick()
        compose.onNodeWithTag(cell("type:SKILL", "place:agent:claude-code")).performClick()

        compose.runOnIdle { assertEquals(emptyList(), writes) }
    }

    @Test fun `a column opened down to one place leads into it`() {
        showLive(ThemeMode.DARK)
        compose.onNodeWithTag(coverageColumnTag("set:KMP apps")).performClick()

        // Opening the set does not open the place; the header of the place does.
        compose.runOnIdle { assertEquals(null, openedPlace) }
        compose.onNodeWithTag(coveragePlaceTag("place:project:${fixturePath("/tmp/ledger-kmp")}")).performClick()
        compose.runOnIdle { assertEquals("project:${fixturePath("/tmp/ledger-kmp")}", openedPlace) }
    }

    @Test fun `one band toggle survives opening and collapsing a group`() {
        showLive(ThemeMode.LIGHT)
        val tag = coverageColumnTag("set:KMP apps")
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithContentDescription(strings.coverageExpandColumn("KMP apps")).performClick()
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(coverageColumnTag("place:project:${fixturePath("/tmp/ledger-kmp")}")).assertDoesNotExist()
            compose.onNodeWithContentDescription(strings.coverageOpenPlaceNamed("ledger-kmp")).assertIsDisplayed()
            compose.onNodeWithContentDescription(strings.coverageCollapseColumn("KMP apps")).performClick()
            compose.onNodeWithTag(coveragePlaceTag("place:project:${fixturePath("/tmp/ledger-kmp")}")).assertDoesNotExist()
        }
    }

    @Test fun `single project heading names the destination instead of its group`() {
        show(fleet(), ThemeMode.LIGHT)
        compose.onNodeWithTag(coveragePlaceTag("set:KMP apps")).performClick()
        compose.runOnIdle { assertEquals("project:${fixturePath("/tmp/ledger-kmp")}", openedPlace) }
        compose.onNodeWithContentDescription(strings.coverageOpenPlaceNamed("ledger-kmp")).assertIsDisplayed()
    }

    @Test fun `a refused write says which rule refused it`() {
        notice = CoverageNotice.MODIFIED
        showLive(ThemeMode.LIGHT)

        compose.onNodeWithText(strings.coverageNoticeModified).assertIsDisplayed()
        compose.onNodeWithText(strings.coverageNoticeDismiss).performClick()
        compose.runOnIdle { assertEquals(null, notice) }
    }

    @Test fun `a marked row raises the bulk bar, and the whole library has no box to tick`() {
        inBothThemes(fleet()) {
            compose.onNodeWithTag(coverageMarkTag("all")).assertDoesNotExist()
            compose.onNodeWithTag(coverageMarkTag("type:RULE")).assertIsDisplayed()
        }
        compose.onNodeWithTag(coverageMarkTag("type:RULE")).performClick()
        // Three rules stand behind the ticked row; the bar counts objects, not rows.
        compose.onNodeWithText(strings.coverageBulkSelected(3)).assertIsDisplayed()
        compose.onNodeWithTag(coverageBulkTag("clear")).performClick()
        compose.onNodeWithText(strings.coverageBulkSelected(3)).assertDoesNotExist()
    }

    @Test fun `a target is chosen, the plan is shown, and only then is anything written`() {
        show(fleet(), ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageMarkTag("type:RULE")).performClick()

        compose.onNodeWithTag(coverageBulkTag("install")).performClick()
        compose.onNodeWithTag(coverageBulkTag("target-set:KMP apps")).performClick()

        compose.onNodeWithText(strings.coverageBulkInstallTitle("ledger-kmp")).assertIsDisplayed()
        // "a" and "b" already sit in the one project of the set, so one write is left.
        compose.onNodeWithText(strings.coverageBulkPlanText(1, 3, 1)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, applied.size) }
        compose.onNodeWithText(strings.coverageBulkApply).performClick()
        compose.runOnIdle {
            assertEquals(1, applied.size)
            assertEquals(listOf("project:${fixturePath("/tmp/ledger-kmp")}" to 1), applied.first().steps.map { it.placeId to it.keys.size })
        }
    }

    @Test fun `bulk menus group aggregates and keep their localized counts separate`() {
        val snapshot = openable(CoverageExpansion())
        var selected: Pair<CoverageBulkAction, String?>? = null
        compose.setContent {
            RuleblendTheme(theme) {
                BulkBar(snapshot, setOf("type:RULE"), RuStrings, false, {},
                    { action, target -> selected = action to target })
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            listOf("install" to CoverageBulkAction.INSTALL, "remove" to CoverageBulkAction.REMOVE).forEach { (button, action) ->
                compose.onNodeWithTag(coverageBulkTag(button)).performClick()
                val bands = snapshot.columns.bands(RuStrings)
                bands.forEach { band ->
                    compose.onNodeWithTag(coverageBulkTag("band-${band.id}")).assertIsNotEnabled()
                    compose.onNodeWithTag(coverageBulkTag("target-${band.columns.single().id}")).assertIsEnabled()
                }
                compose.onNodeWithText(RuStrings.coverageColumnAgents(2)).assertIsDisplayed()
                compose.onNodeWithText(RuStrings.coverageColumnProjects(2)).assertIsDisplayed()
                val header = compose.onNodeWithTag(coverageBulkTag("band-set:KMP apps")).getUnclippedBoundsInRoot()
                val target = compose.onNodeWithTag(coverageBulkTag("target-set:KMP apps"))
                assertTrue(header.bottom <= target.getUnclippedBoundsInRoot().top)
                target.performClick()
                compose.runOnIdle { assertEquals(action to "set:KMP apps", selected) }
            }
        }
    }

    @Test fun `expanded bulk targets keep group headings but omit single place counts`() {
        expansion = CoverageExpansion(columns = setOf("agents", "set:KMP apps"))
        showLive(ThemeMode.DARK)
        compose.onNodeWithTag(coverageMarkTag("type:RULE")).performClick()
        compose.onNodeWithTag(coverageBulkTag("remove")).performClick()
        compose.onNodeWithTag(coverageBulkTag("band-agents")).assertIsNotEnabled()
        compose.onNodeWithTag(coverageBulkTag("band-set:KMP apps")).assertIsNotEnabled()
        compose.onNodeWithTag(coverageBulkTag("target-place:agent:claude-code")).assertIsEnabled()
        compose.onNodeWithText(strings.coverageColumnAgents(1)).assertDoesNotExist()
        compose.onNodeWithText(strings.coverageColumnProjects(1)).assertDoesNotExist()
        compose.onNodeWithTag(coverageBulkTag("target-place:project:${fixturePath("/tmp/ledger-kmp")}")).performClick()
        compose.onNodeWithText(strings.coverageBulkRemoveTitle("ledger-kmp")).assertIsDisplayed()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun `aggregate bulk confirmation names the selected group`() {
        showLive(ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageMarkTag("type:RULE")).performClick()
        compose.onNodeWithTag(coverageBulkTag("install")).performClick()
        compose.onNodeWithTag(coverageBulkTag("target-set:KMP apps")).performClick()
        compose.onNodeWithText(strings.coverageBulkInstallTitle("KMP apps")).assertIsDisplayed()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
    }

    @Test fun `update everywhere needs no target and plans only the outdated copies`() {
        show(fleet(), ThemeMode.LIGHT)
        compose.onNodeWithTag(coverageMarkTag("type:RULE")).performClick()

        compose.onNodeWithTag(coverageBulkTag("update")).performClick()

        // No target was asked for; the plan is on screen straight away.
        compose.onNodeWithText(strings.coverageBulkPlanText(1, 3, 1)).assertIsDisplayed()
        compose.onNodeWithText(strings.coverageBulkApply).performClick()
        // Only "b" in ledger-kmp is out of date; the modified copy of "a" is not offered.
        compose.runOnIdle {
            assertEquals(listOf("project:${fixturePath("/tmp/ledger-kmp")}" to listOf(rule("b"))), applied.first().steps.map { it.placeId to it.keys })
        }
    }

    @Test fun `a partly refused batch names the places that took nothing`() {
        report = CoverageBulkReport(
            action = CoverageBulkAction.INSTALL,
            written = 2,
            failed = 1,
            refused = listOf("ledger-kmp"),
        )
        inBothThemes(fleet()) {
            compose.onNodeWithTag(coverageBulkTag("report")).assertIsDisplayed()
            compose.onNodeWithText(strings.libInstallWritten(2), substring = true).assertIsDisplayed()
            compose.onNodeWithText(strings.libInstallFailedCount(1), substring = true).assertIsDisplayed()
            compose.onNodeWithText(strings.coverageBulkRefused("ledger-kmp"), substring = true).assertIsDisplayed()
        }
        compose.onAllNodesWithText(strings.coverageNoticeDismiss).onFirst().performClick()
        compose.runOnIdle { assertEquals(null, report) }
    }

    // ---- harness ----

    private val synced = LibraryInstall(InstallStatus.SYNCED)
    private val update = LibraryInstall(InstallStatus.UPDATE_AVAILABLE)
    private val modified = LibraryInstall(InstallStatus.MODIFIED)

    private fun rule(id: String) = LibraryObjectKey(LibraryObjectKind.RULE, id)

    private fun catalog() = LibraryCatalog.build(
        blocks = listOf("a", "b", "c").map { Block(id = it, name = it) },
        groups = emptyList(),
        skills = listOf(Skill(id = "pdf", name = "pdf")),
        scopes = emptyMap(),
    )

    private fun places() = listOf(
        LibraryPlaceUsage("agent:claude-code", "Claude Code", LibraryPlaceKind.AGENT, mapOf(rule("a") to synced)),
        LibraryPlaceUsage("agent:zcode", "ZCode", LibraryPlaceKind.AGENT, emptyMap(), supportsSkills = false),
        LibraryPlaceUsage(
            "project:${fixturePath("/tmp/ledger-kmp")}",
            "ledger-kmp",
            LibraryPlaceKind.PROJECT,
            mapOf(rule("a") to modified, rule("b") to update),
        ),
    )

    /** Two agents and a one-project set: three places, four objects, one conflict, one update. */
    private fun fleet(expansion: CoverageExpansion = CoverageExpansion(columns = setOf("agents"))) = coverageMatrix(
        catalog = catalog(),
        scanned = places(),
        board = PlaceBoard(sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/tmp/ledger-kmp"))))),
        agents = listOf(
            CoverageAgent("claude-code", "Claude Code", visible = true, skills = true),
            CoverageAgent("zcode", "ZCode", visible = false, skills = false),
        ),
        expansion = expansion,
    )

    /**
     * The same fleet with a second project in the set, so the set column aggregates places instead of
     * standing for one: a drill-down over a one-project set would have nothing to open.
     */
    private fun openable(expansion: CoverageExpansion) = coverageMatrix(
        catalog = catalog(),
        scanned = places() + LibraryPlaceUsage(
            "project:${fixturePath("/tmp/mesh-sync")}",
            "mesh-sync",
            LibraryPlaceKind.PROJECT,
            emptyMap(),
        ),
        board = PlaceBoard(sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/tmp/ledger-kmp"), fixturePath("/tmp/mesh-sync"))))),
        agents = listOf(
            CoverageAgent("claude-code", "Claude Code", visible = true, skills = true),
            CoverageAgent("zcode", "ZCode", visible = false, skills = false),
        ),
        expansion = expansion,
    )

    private var theme by mutableStateOf(ThemeMode.LIGHT)

    private fun cell(rowId: String, columnId: String) = coverageCellTag(rowId, columnId)

    private fun show(snapshot: CoverageSnapshot, theme: ThemeMode) {
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
    }

    /** The drill-down re-cuts the matrix, so the screen is shown over a snapshot that follows it. */
    private fun showLive(mode: ThemeMode) {
        theme = mode
        compose.setContent { RuleblendTheme(theme) { Screen(openable(expansion)) } }
    }

    private fun inBothThemes(snapshot: CoverageSnapshot, assertions: () -> Unit) {
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            assertions()
        }
    }

    @Composable
    private fun Screen(snapshot: CoverageSnapshot) {
        CoverageScreen(
            snapshot = snapshot,
            filters = filters,
            scanning = scanning,
            lastScanMillis = null,
            onFilters = { filters = it },
            onRescan = { rescans++ },
            onAgentVisible = { id, visible -> visibility = id to visible },
            onOpenLibrary = { openedLibrary++ },
            expansion = expansion,
            notice = notice,
            writing = writing,
            onToggleRow = { expansion = expansion.toggleRow(it) },
            onToggleColumn = { expansion = expansion.toggleColumn(it) },
            onSetInstalled = { key, placeId, install -> writes += Triple(key, placeId, install) },
            onDismissNotice = { notice = null },
            onOpenPlace = { openedPlace = it },
            onOpenResolve = { openedResolve++ },
            onOpenAgentSettings = { openedSettings++ },
            onExpandAllRows = { expansion = expansion.copy(rows = snapshot.rows.filter { it.expandable }.map { it.id }.toSet()) },
            onCollapseAllRows = { expansion = expansion.copy(rows = emptySet()) },
            onExpandAllColumns = { expansion = expansion.copy(columns = expansion.columns + snapshot.columns.filter { it.expandable }.map { it.id }) },
            onCollapseAllColumns = { expansion = expansion.copy(columns = emptySet()) },
            marked = marked,
            onToggleMark = { marked = if (it in marked) marked - it else marked + it },
            onClearMarks = { marked = emptySet() },
            // The screen builds no plan of its own: it asks for one over the very snapshot it draws.
            planOf = { action, columnId ->
                coverageBulkPlan(
                    action,
                    coverageMarkedKeys(snapshot.rows, marked),
                    snapshot.columns.find { it.id == columnId },
                    catalog(),
                    places(),
                )
            },
            onApplyBulk = { applied += it },
            report = report,
            onDismissReport = { report = null },
        )
    }
}
