package dev.ruleblend.app.resolve

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.PlaceFile
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.BlockConflict
import dev.ruleblend.core.integration.ConflictKind
import dev.ruleblend.core.integration.ConflictLine
import dev.ruleblend.core.integration.ConflictLineKind
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.model.Block
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * The Resolve screen against ready snapshots: the classes must stay tellable apart, a hand edit must
 * be readable as a diff, and core's refusal on an ambiguous run must reach the user instead of being
 * quietly resolved. Both themes are checked — a diff that only reads in light is not a diff.
 */
class ResolveScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings
    private var openedPlace: String? = null
    private var rescans = 0
    private var selected by mutableStateOf(ResolveClass.HAND_EDITED)
    private var marked by mutableStateOf(emptySet<String>())
    private var base by mutableStateOf(ResolveStrategy.RESTORE)
    private var overrides by mutableStateOf(emptyMap<String, ResolveStrategy>())
    private var report by mutableStateOf<ResolveReport?>(null)
    private var applied: ResolvePlan? = null

    @Test fun `an unscanned surface says so instead of claiming there are no conflicts`() {
        inBothThemes(ResolveSnapshot.EMPTY) {
            compose.onNodeWithText(strings.resolveNotScannedTitle).assertIsDisplayed()
        }
        compose.onNodeWithTag("resolve-rescan").performClick()
        compose.runOnIdle { assertEquals(1, rescans) }
    }

    @Test fun `every class is listed with its count, empty ones included`() {
        inBothThemes(snapshot()) {
            compose.onNodeWithText(strings.resolveClassHandEdited).assertIsDisplayed()
            compose.onNodeWithText(strings.resolveClassAmbiguous).assertIsDisplayed()
            compose.onNodeWithText(strings.resolveClassLegacy).assertIsDisplayed()
            compose.onNodeWithText(strings.resolveScanned(4)).assertIsDisplayed()
        }
    }

    @Test fun `a hand-edited row shows its drift and opens the diff on click`() {
        show(snapshot(), ThemeMode.LIGHT)

        val row = snapshot().groups(ResolveClass.HAND_EDITED).first().rows.first()
        compose.onNodeWithText(strings.resolveDrift(1, 1)).assertIsDisplayed()
        compose.onNodeWithTag(resolveDiffTag(row)).assertDoesNotExist()
        // Clicked on the file name, not on the row's centre: the strategy control sits there now.
        compose.onNodeWithText(row.file, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(resolveDiffTag(row)).assertIsDisplayed()
        compose.onNodeWithText("+ local line").assertIsDisplayed()
    }

    @Test fun `an ambiguous run is shown as needing a person, with no diff and no strategy`() {
        selected = ResolveClass.AMBIGUOUS
        inBothThemes(snapshot()) {
            compose.onAllNodesWithText(strings.resolveAmbiguousRow).assertCountEquals(2)
            compose.onNodeWithText(strings.resolveNextAmbiguous).assertIsDisplayed()
        }
        val row = snapshot().groups(ResolveClass.AMBIGUOUS).first().rows.first()
        compose.onNodeWithTag(resolveRowTag(row)).performClick()
        compose.onNodeWithTag(resolveDiffTag(row)).assertDoesNotExist()
    }

    @Test fun `an empty class reads as empty, not as an unscanned surface`() {
        selected = ResolveClass.LEGACY
        show(snapshot(legacy = false), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.resolveNothingTitle).assertIsDisplayed()
    }

    @Test fun `selecting a class switches the table`() {
        show(snapshot(), ThemeMode.LIGHT)

        compose.onNodeWithTag(resolveClassTag(ResolveClass.LEGACY)).performClick()
        compose.runOnIdle { assertEquals(ResolveClass.LEGACY, selected) }
        compose.onNodeWithText(strings.resolveLegacyMarkers).assertIsDisplayed()
    }

    @Test fun `a row leads into the place that holds the file`() {
        show(snapshot(), ThemeMode.LIGHT)

        compose.onAllNodesWithText(strings.resolveOpenPlace)[0].performClick()
        compose.runOnIdle { assertEquals("project:/tmp/ledger-kmp", openedPlace) }
    }

    @Test fun `nothing is marked by default, so the batch cannot run`() {
        show(snapshot(), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.resolvePlanNothing).assertIsDisplayed()
        compose.onNodeWithTag("resolve-apply").performClick()
        compose.runOnIdle { assertEquals(null, applied) }
    }

    @Test fun `marking a row states the plan and applying hands it over`() {
        show(snapshot(), ThemeMode.LIGHT)
        val row = snapshot().groups(ResolveClass.HAND_EDITED).first().rows.first()

        compose.onNodeWithTag(resolveMarkTag(row)).performClick()
        compose.onNodeWithText(strings.resolvePlanText(1, 0, 1)).assertIsDisplayed()
        compose.onNodeWithTag("resolve-apply").performClick()

        compose.runOnIdle {
            assertEquals(listOf("git-commit-style"), applied!!.places.single().steps.map { it.blockId })
            assertEquals(ResolveStrategy.RESTORE, applied!!.places.single().steps.single().strategy)
        }
    }

    @Test fun `a per-row exception overrules the panel and is counted as skipped`() {
        show(snapshot(), ThemeMode.LIGHT)
        val row = snapshot().groups(ResolveClass.HAND_EDITED).first().rows.first()

        compose.onNodeWithTag(resolveMarkTag(row)).performClick()
        compose.onNodeWithTag(resolveStrategyTag(ResolveStrategy.SAVE_AS_VERSION)).performClick()
        compose.onNodeWithTag(resolveOverrideTag(row)).performClick()
        compose.onNodeWithTag("${resolveOverrideTag(row)}-skip").performClick()

        compose.onNodeWithText(strings.resolvePlanSkipped(1)).assertIsDisplayed()
        compose.onNodeWithTag("resolve-apply").performClick()
        compose.runOnIdle { assertEquals(true, applied == null) }
    }

    @Test fun `a batch that failed somewhere says where`() {
        report = ResolveReport(restored = 2, failed = listOf("nimbus-api"))
        inBothThemes(snapshot()) {
            compose.onNodeWithTag("resolve-report").assertIsDisplayed()
            compose.onNodeWithText(strings.resolveDoneRestored(2)).assertIsDisplayed()
            compose.onNodeWithText(strings.resolveDoneFailed("nimbus-api")).assertIsDisplayed()
        }
    }

    @Test fun `the classes without a strategy offer no batch at all`() {
        selected = ResolveClass.AMBIGUOUS
        show(snapshot(), ThemeMode.LIGHT)

        compose.onNodeWithTag("resolve-apply").assertDoesNotExist()
        compose.onNodeWithTag("resolve-mark-all").assertDoesNotExist()
    }

    // ---- harness ----

    private fun snapshot(legacy: Boolean = true) = resolveSnapshot(
        listOfNotNull(
            place("ledger-kmp", file(conflicts = listOf(handEdited("git-commit-style"), ambiguousPair()).flatten())),
            if (legacy) place("nimbus-api", file(mode = TargetOwnershipMode.LEGACY)) else null,
        ),
        listOf(Block(id = "git-commit-style", name = "Commit style")),
    )

    private fun handEdited(id: String) = listOf(
        BlockConflict(
            blockId = id,
            version = 3,
            kind = ConflictKind.HAND_EDITED,
            diff = listOf(
                ConflictLine(ConflictLineKind.REMOVED, "library line"),
                ConflictLine(ConflictLineKind.ADDED, "local line"),
            ),
        ),
    )

    private fun ambiguousPair() = listOf(
        BlockConflict("api-naming", 2, ConflictKind.AMBIGUOUS_RUN),
        BlockConflict("error-handling", 4, ConflictKind.AMBIGUOUS_RUN),
    )

    private fun file(
        name: String = "AGENTS.md",
        mode: TargetOwnershipMode = TargetOwnershipMode.PARTIAL,
        conflicts: List<BlockConflict> = emptyList(),
    ) = PlaceFile(Path.of(name), name, exists = true, mode = mode, unmanagedLines = 0, drift = conflicts.isNotEmpty(), conflicts = conflicts)

    private fun place(name: String, vararg files: PlaceFile) = LibraryPlaceUsage(
        id = "project:/tmp/$name",
        name = name,
        kind = LibraryPlaceKind.PROJECT,
        installs = emptyMap(),
        files = files.toList(),
    )

    private fun show(snapshot: ResolveSnapshot, theme: ThemeMode) {
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
    }

    private fun inBothThemes(snapshot: ResolveSnapshot, assertions: () -> Unit) {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            assertions()
        }
    }

    @Composable
    private fun Screen(snapshot: ResolveSnapshot) {
        ResolveScreen(
            snapshot = snapshot,
            selected = selected,
            scanning = false,
            lastScanMillis = null,
            onSelect = { selected = it },
            onRescan = { rescans++ },
            onOpenPlace = { openedPlace = it },
            marked = marked,
            base = base,
            overrides = overrides,
            plan = resolvePlan(snapshot.groups(ResolveClass.HAND_EDITED), marked, base, overrides),
            report = report,
            onToggleMark = { key -> marked = if (key in marked) marked - key else marked + key },
            onMarkAll = { all -> marked = if (all) resolveKeys(snapshot.groups(ResolveClass.HAND_EDITED)) else emptySet() },
            onBase = { base = it },
            onOverride = { key, strategy -> overrides = if (strategy == null) overrides - key else overrides + (key to strategy) },
            onApply = { applied = resolvePlan(snapshot.groups(ResolveClass.HAND_EDITED), marked, base, overrides) },
        )
    }
}
