package dev.ruleblend.app.coverage

import dev.ruleblend.app.fixturePath
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Measure the actual desktop layout, including the shared horizontal scroll, at both window sizes. */
@OptIn(ExperimentalTestApi::class)
class CoverageLayoutTest {
    @Test fun `legend fits two lines in both languages at supported widths`() = runSkikoComposeUiTest(size = Size(1440f, 300f)) {
        var width by mutableStateOf(900)
        var russian by mutableStateOf(false)
        setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(width.dp)) { Legend(if (russian) RuStrings else EnStrings) }
            }
        }
        for (size in listOf(900, 1440)) {
            for (ru in listOf(false, true)) {
                runOnIdle { width = size; russian = ru }
                val legend = onNodeWithTag("coverage-legend").getUnclippedBoundsInRoot()
                assertTrue(legend.bottom - legend.top <= 54.dp, "Legend at $size dp, russian=$ru: $legend")
            }
        }
    }

    @Test fun `bands align with cells at wide and narrow widths for small and large fleets`() = runSkikoComposeUiTest(size = Size(1440f, 1000f)) {
        val compose = this
        var windowWidth by mutableStateOf(900)
        var assistants by mutableStateOf(1)
        var mode by mutableStateOf(ThemeMode.LIGHT)
        var expansion by mutableStateOf(CoverageExpansion(columns = setOf("agents", "set:Applications")))
        compose.setContent {
            RuleblendTheme(mode) {
                Box(Modifier.width(windowWidth.dp).fillMaxHeight()) {
                    key(assistants, mode, windowWidth) {
                        CoverageScreen(
                            snapshot = fleet(assistants, expansion), filters = CoverageFilters(),
                            scanning = false, lastScanMillis = 1200, onFilters = {}, onRescan = {},
                            onAgentVisible = { _, _ -> }, onOpenLibrary = {}, expansion = expansion,
                        )
                    }
                }
            }
        }
        for (width in listOf(900, 1440)) {
            compose.runOnIdle { windowWidth = width }
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                for (count in listOf(1, 6)) {
                    compose.runOnIdle { assistants = count; mode = theme }
                    compose.onNodeWithTag("coverage-legend").assertIsDisplayed()
                    compose.onAllNodesWithTag(coverageColumnTag("agents")).assertCountEquals(if (count == 1) 0 else 1)
                    compose.onAllNodesWithTag(coverageColumnTag("set:Applications")).assertCountEquals(1)
                    val band = compose.onNodeWithTag(coverageColumnTag("set:Applications")).getUnclippedBoundsInRoot()
                    assertEquals(34.dp, band.bottom - band.top)
                    assertEquals(120.dp * 14, band.right - band.left)
                    compose.onNodeWithTag("coverage-scroll-vertical").assertIsDisplayed()
                    val scrollbar = compose.onNodeWithTag("coverage-scroll-horizontal").getUnclippedBoundsInRoot()
                    assertEquals((width.dp * 0.28f).coerceIn(240.dp, 340.dp), scrollbar.left)
                    listOf(0, 13).forEach { index ->
                        val id = "place:project:${fixturePath("/project-$index")}"
                        val heading = compose.onNodeWithTag(coveragePlaceTag(id))
                        heading.performScrollTo().assertIsDisplayed()
                        val head = heading.getUnclippedBoundsInRoot()
                        val cell = compose.onNodeWithTag(coverageCellTag("all", id)).getUnclippedBoundsInRoot()
                        assertEquals(cell.left, head.left)
                        assertEquals(cell.right, head.right)
                        assertEquals(120.dp, cell.right - cell.left)
                        assertEquals(52.dp, cell.bottom - cell.top)
                        assertTrue(head.bottom <= cell.top)
                        if (index == 13) {
                            val layouts = mutableListOf<TextLayoutResult>()
                            heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                            assertEquals(2, layouts.single().lineCount)
                        }
                    }
                    val legend = compose.onNodeWithTag("coverage-legend").getUnclippedBoundsInRoot()
                    val cell = compose.onNodeWithTag(coverageCellTag("all", "place:project:${fixturePath("/project-13")}")).getUnclippedBoundsInRoot()
                    assertTrue(legend.top >= cell.bottom)
                    listOf("rows", "columns").forEach { group ->
                        val expand = compose.onNodeWithTag("coverage-$group-expand-all").getUnclippedBoundsInRoot()
                        val collapse = compose.onNodeWithTag("coverage-$group-collapse-all").getUnclippedBoundsInRoot()
                        assertEquals(expand.top, collapse.top)
                        assertEquals(width.dp - 14.dp, collapse.right)
                    }
                    // At supported window widths each filter group remains on one line.
                    val types = listOf("rule", "subagent", "skill", "mcp").map {
                        compose.onNodeWithTag("coverage-filter-$it").getUnclippedBoundsInRoot()
                    }
                    assertEquals(1, types.map { it.top }.distinct().size)
                    val installed = compose.onNodeWithTag("coverage-filter-installed").getUnclippedBoundsInRoot()
                    val attention = compose.onNodeWithTag("coverage-filter-attention").getUnclippedBoundsInRoot()
                    assertEquals(installed.top, attention.top)
                    if (width == 1440) {
                        assertEquals(types.first().top, installed.top)
                        val fold = compose.onNodeWithTag("coverage-rows-expand-all").getUnclippedBoundsInRoot()
                        assertEquals((fold.top + fold.bottom) / 2, (installed.top + installed.bottom) / 2)
                    }
                }
            }
        }
        compose.runOnIdle { windowWidth = 800; assistants = 6; expansion = CoverageExpansion() }
        val aggregate = compose.onNodeWithTag(coverageCellTag("all", "agents")).getUnclippedBoundsInRoot()
        val foldedBand = compose.onNodeWithTag(coverageColumnTag("agents")).getUnclippedBoundsInRoot()
        assertEquals(240.dp, aggregate.left)
        assertEquals(144.dp, aggregate.right - aggregate.left)
        assertEquals(aggregate.left, foldedBand.left)
        assertEquals(aggregate.right, foldedBand.right)
    }

    @Test fun `vertical scrolling keeps headers and legend fixed and assistant menu does not move grid`() = runSkikoComposeUiTest(size = Size(1440f, 700f)) {
        var windowWidth by mutableStateOf(900)
        val expansion = CoverageExpansion(rows = setOf("type:RULE"), columns = setOf("agents", "set:Applications"))
        setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(windowWidth.dp).fillMaxHeight()) {
                    key(windowWidth) {
                        CoverageScreen(
                            snapshot = fleet(6, expansion, ruleCount = 40), filters = CoverageFilters(),
                            scanning = false, lastScanMillis = 1200, onFilters = {}, onRescan = {},
                            onAgentVisible = { _, _ -> }, onOpenLibrary = {}, expansion = expansion,
                        )
                    }
                }
            }
        }
        for (width in listOf(900, 1440)) {
            runOnIdle { windowWidth = width }
            val heading = onNodeWithTag(coveragePlaceTag("place:project:${fixturePath("/project-13")}"))
            heading.performScrollTo().assertIsDisplayed()
            val headerBounds = heading.getUnclippedBoundsInRoot()
            val legendBounds = onNodeWithTag("coverage-legend").getUnclippedBoundsInRoot()
            val lastRow = "type:RULE/RULE:rule-39"
            onNodeWithTag("coverage-title-$lastRow").assertDoesNotExist()
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("coverage-title-$lastRow"))
            onNodeWithTag("coverage-title-$lastRow").assertIsDisplayed()
            val cell = onNodeWithTag(coverageCellTag(lastRow, "place:project:${fixturePath("/project-13")}"))
            cell.assertIsDisplayed()
            val cellBounds = cell.getUnclippedBoundsInRoot()
            assertEquals(headerBounds.left, cellBounds.left)
            assertEquals(headerBounds.right, cellBounds.right)
            assertEquals(headerBounds, heading.getUnclippedBoundsInRoot())
            assertEquals(legendBounds, onNodeWithTag("coverage-legend").getUnclippedBoundsInRoot())
            onNodeWithTag("coverage-agents-panel").performClick()
            onNodeWithTag("coverage-agent-settings").assertIsDisplayed()
            assertEquals(headerBounds, heading.getUnclippedBoundsInRoot())
            assertEquals(cellBounds, cell.getUnclippedBoundsInRoot())
            assertEquals(legendBounds, onNodeWithTag("coverage-legend").getUnclippedBoundsInRoot())
            onNodeWithTag("coverage-agent-settings").performClick()
            onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("coverage-title-all"))
            onNodeWithTag("coverage-title-all").assertIsDisplayed()
            onNodeWithTag("coverage-legend").assertIsDisplayed()
        }
    }

    private fun fleet(count: Int, expansion: CoverageExpansion, ruleCount: Int = 1): CoverageSnapshot {
        val catalog = LibraryCatalog.build((0 until ruleCount).map {
            Block(id = "rule-$it", name = "Rule ${it.toString().padStart(2, '0')}")
        }, emptyList(), emptyList(), emptyMap())
        val places = (0 until count).map {
            LibraryPlaceUsage("agent:assistant-$it", "Assistant $it", LibraryPlaceKind.AGENT, emptyMap())
        } + (0 until 14).map {
            LibraryPlaceUsage("project:${fixturePath("/project-$it")}",
                if (it == 13) "A very long project name that needs two lines" else "Project $it",
                LibraryPlaceKind.PROJECT, emptyMap())
        }
        return coverageMatrix(catalog, places,
            PlaceBoard(sets = listOf(ProjectSet("Applications", (0 until 14).map { fixturePath("/project-$it") }))),
            agents = (0 until count).map { CoverageAgent("assistant-$it", "Assistant $it", visible = true, skills = true) },
            expansion = expansion)
    }
}
