package dev.ruleblend.app.home

import dev.ruleblend.app.fixturePath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.PlaceFile
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProjectSet
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.storage.RemoteSyncConflict
import dev.ruleblend.core.storage.RemoteSyncConflictKind
import dev.ruleblend.core.storage.RemoteSyncResult
import dev.ruleblend.core.storage.RemoteSyncStatus
import dev.ruleblend.core.usecase.SourceCheckReport
import dev.ruleblend.core.usecase.SourceCheckSkill
import dev.ruleblend.core.usecase.SourceCheckSubagent
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.mcp.BundledMcpState
import dev.ruleblend.mcp.BundledSkillState
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.skill.SkillStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule

/**
 * The Home screen against ready snapshots: the three empty states must stay distinguishable, and
 * every card must lead somewhere. Both themes are checked because the feed is the one surface a user
 * sees first, and a state that only reads in light is a state that does not exist at night.
 */
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings
    private var openedPlace: String? = null
    private var openedResolve = 0
    private var openedLibrary = 0
    private var hiddenAgent: String? = null
    private var rescans = 0

    @Test fun `an unscanned Home offers a scan instead of claiming everything is fine`() {
        inBothThemes(HomeSnapshot.EMPTY) {
            compose.onNodeWithText(strings.homeNotScannedTitle).assertIsDisplayed()
            compose.onNodeWithText(strings.homeScanNow).assertIsDisplayed()
        }
        compose.onNodeWithText(strings.homeScanNow).performClick()
        compose.runOnIdle { assertEquals(1, rescans) }
    }

    @Test fun `a scanned fleet with nothing to do reads as all clear, not as an empty app`() {
        val snapshot = homeSnapshot(
            scanned = listOf(place("ledger-kmp", installs = mapOf(rule("a") to synced))),
            library = LibraryTotals(rules = 4, skills = 2, mcpServers = 1, groups = 3),
        )
        inBothThemes(snapshot) {
            compose.onNodeWithText(strings.homeAllClearTitle).assertIsDisplayed()
            compose.onNodeWithText(strings.homeLibraryLine(4, 2, 1, 3)).assertIsDisplayed()
        }
    }

    @Test fun `an empty library is its own state, not all clear`() {
        val snapshot = homeSnapshot(listOf(place("ledger-kmp")), library = LibraryTotals())
        inBothThemes(snapshot) {
            compose.onNodeWithText(strings.homeEmptyLibraryTitle).assertIsDisplayed()
            compose.onNodeWithText(strings.homeOpenLibrary).assertIsDisplayed()
        }
        compose.onNodeWithText(strings.homeOpenLibrary).performClick()
        compose.runOnIdle { assertEquals(1, openedLibrary) }
    }

    @Test fun `each class of problem gets one card, counted in its own unit`() {
        inBothThemes(problems()) {
            compose.onNodeWithText(strings.homeConflicts(3, 2)).assertIsDisplayed()
            compose.onNodeWithText(strings.homeUpdates(2, 2)).assertIsDisplayed()
            compose.onNodeWithText(strings.homeUnadopted(1)).assertIsDisplayed()
            compose.onNodeWithText(strings.homeUnadoptedHint(12)).assertIsDisplayed()
        }
    }

    @Test fun `the conflicts card leads to Resolve, a card row leads to its place`() {
        show(problems(), ThemeMode.LIGHT)

        compose.onNodeWithText(strings.homeResolveAll).performClick()
        compose.runOnIdle { assertEquals(1, openedResolve) }

        compose.onNodeWithText(strings.homeConflicts(3, 2)).performClick()
        compose.onNodeWithText(strings.homeRowModified(2)).performClick()
        compose.runOnIdle { assertEquals("project:${fixturePath("/tmp/transit-ios")}", openedPlace) }
    }

    @Test fun `a detected agent can be opened as a place or hidden for good`() {
        val agent = DetectedAgent("kimi-code", "Kimi Code", "agent:kimi-code", "~/.kimi/KIMI.md", 25)
        show(homeSnapshot(listOf(place("ledger-kmp")), agents = listOf(agent)), ThemeMode.DARK)

        compose.onNodeWithText(strings.homeNewAgent("Kimi Code")).assertIsDisplayed()
        compose.onNodeWithText(strings.homeOpenPlace).performClick()
        compose.runOnIdle { assertEquals("agent:kimi-code", openedPlace) }

        compose.onNodeWithText(strings.homeHideAgent).performClick()
        compose.runOnIdle { assertEquals("kimi-code", hiddenAgent) }
    }

    @Test fun `remote merge conflicts lead to their history and compare, and restored deletions stay explicit`() {
        val conflict = RemoteSyncConflict("swift-style", RemoteSyncConflictKind.BLOCK, restored = true)
        var history: RemoteSyncConflict? = null
        var compare: RemoteSyncConflict? = null
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                HomeScreen(
                    snapshot = HomeSnapshot.EMPTY,
                    scanning = false,
                    lastScanMillis = null,
                    onRescan = {},
                    onHideAgent = {},
                    onOpenPlace = {},
                    onOpenResolve = {},
                    onOpenLibrary = {},
                    remoteConfigured = true,
                    remoteSync = RemoteSyncResult(
                        RemoteSyncStatus.UP_TO_DATE,
                        behind = 2,
                        merged = true,
                        conflicts = listOf(conflict),
                    ),
                    onOpenHistory = { history = it },
                    onOpenCompare = { compare = it },
                )
            }
        }

        compose.onNodeWithText(strings.homeSyncTitle).assertIsDisplayed()
        // The state now shares its line with freshness and mode, so the state itself is a substring.
        compose.onNodeWithText(strings.homeSyncMergedConflicts(1), substring = true).assertIsDisplayed()
        compose.onNodeWithText("swift-style").assertIsDisplayed()
        compose.onNodeWithText(strings.homeSyncRestored(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.homeSyncHistory).performClick()
        compose.onNodeWithText(strings.homeSyncCompare).performClick()
        compose.runOnIdle {
            assertEquals(conflict, history)
            assertEquals(conflict, compare)
        }
    }

    private fun syncCard(remote: RemoteGitConfig, result: RemoteSyncResult?) {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                HomeScreen(
                    snapshot = HomeSnapshot.EMPTY,
                    scanning = false,
                    lastScanMillis = null,
                    onRescan = {},
                    onHideAgent = {},
                    onOpenPlace = {},
                    onOpenResolve = {},
                    onOpenLibrary = {},
                    remoteConfigured = true,
                    remoteGit = remote,
                    remoteSync = result,
                )
            }
        }
    }

    @Test fun `a settled automatic sync says what it syncs and offers nothing to press`() {
        syncCard(
            RemoteGitConfig(url = "git@example.com:me/library.git", branch = "main", automatic = true),
            RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE),
        )

        compose.onNodeWithText(strings.homeSyncExplains).assertIsDisplayed()
        compose.onNodeWithText("git@example.com:me/library.git", substring = true).assertIsDisplayed()
        compose.onNodeWithText(strings.homeSyncAutomatic, substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HomeSyncNowTag).assertDoesNotExist()
    }

    @Test fun `a manual remote keeps its button even when nothing is behind`() {
        syncCard(
            RemoteGitConfig(url = "git@example.com:me/library.git", branch = "main", automatic = false),
            RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE),
        )

        compose.onNodeWithText(strings.homeSyncManual, substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HomeSyncNowTag).assertExists()
    }

    @Test fun `an automatic remote that has never synced still offers the button`() {
        syncCard(RemoteGitConfig(url = "git@example.com:me/library.git", automatic = true), result = null)

        compose.onNodeWithText(strings.remoteGitNotSynced, substring = true).assertIsDisplayed()
        compose.onNodeWithTag(HomeSyncNowTag).assertExists()
    }

    @Test fun `the integration card names the versions behind and updates one agent or all of them`() {
        var updated = emptySet<String>()
        val state = HomeIntegrationUpdates(
            states = listOf(
                AgentIntegrationState(
                    agentId = "claude-code",
                    mcp = BundledMcpState(McpRegistration.OUTDATED, installed = 0, current = 1),
                    skill = BundledSkillState(SkillStatus.OUTDATED, installed = 2, current = 5),
                ),
                AgentIntegrationState(
                    agentId = "codex",
                    mcp = BundledMcpState(McpRegistration.REGISTERED, installed = 1, current = 1),
                    skill = BundledSkillState(SkillStatus.OUTDATED, installed = 4, current = 5),
                ),
                AgentIntegrationState(
                    agentId = "zcode",
                    skill = BundledSkillState(SkillStatus.FOREIGN, installed = null, current = 5),
                ),
            ),
            agentNames = mapOf("claude-code" to "Claude Code", "codex" to "Codex", "zcode" to "ZCode"),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { IntegrationScreen(state, onUpdate = { updated = it }) }
        }

        compose.onNodeWithText(strings.homeIntegrationTitle).assertIsDisplayed()
        compose.onNodeWithText("${strings.setSkillVersion(2, 5)} · ${strings.setEntryVersion(0, 1)}").assertIsDisplayed()
        compose.onNodeWithText(strings.homeIntegrationForeign).assertIsDisplayed()
        // A foreign skill is reported, never repaired: it has no Update beside it and no place in
        // the batch the header button sends.
        compose.onNodeWithTag("home-integration-update-zcode").assertDoesNotExist()
        compose.onNodeWithTag("home-integration-update-codex").performClick()
        compose.runOnIdle { assertEquals(setOf("codex"), updated) }
        compose.onNodeWithTag("home-integration-update-all").performClick()
        compose.runOnIdle { assertEquals(setOf("claude-code", "codex"), updated) }
    }

    @Test fun `nothing outdated keeps the integration card off the screen`() {
        val state = HomeIntegrationUpdates(
            states = listOf(
                AgentIntegrationState(
                    agentId = "claude-code",
                    mcp = BundledMcpState(McpRegistration.REGISTERED, installed = 1, current = 1),
                    skill = BundledSkillState(SkillStatus.INSTALLED, installed = 5, current = 5),
                ),
            ),
        )
        compose.setContent { RuleblendTheme(ThemeMode.LIGHT) { IntegrationScreen(state) } }

        compose.onNodeWithText(strings.homeIntegrationTitle).assertDoesNotExist()
    }

    @Test fun `source update card shows check progress and disables competing actions`() {
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                SourceUpdatesScreen(
                    HomeSourceUpdates(
                        skillNames = mapOf("review" to "Review"),
                        checking = true,
                        progressDone = 2,
                        progressTotal = 5,
                    ),
                )
            }
        }

        compose.onNodeWithText(strings.homeSourceUpdatesTitle).assertIsDisplayed()
        compose.onNodeWithText(strings.homeSourceChecking(2, 5)).assertIsDisplayed()
        compose.onNodeWithTag("home-source-check").assertIsNotEnabled()
    }

    @Test fun `source update rows offer one update and the whole checked batch`() {
        var updated = emptySet<String>()
        val state = HomeSourceUpdates(
            skillNames = mapOf("review" to "Review", "format" to "Format", "testing" to "Testing"),
            subagentNames = mapOf("review" to "Review agent", "broken" to "Broken agent"),
            report = SourceCheckReport(
                perRepo = emptyList(),
                perSkill = listOf(
                    SourceCheckSkill("review", "repo", "review", SourceCheckSkillStatus.UPDATE_AVAILABLE),
                    SourceCheckSkill("format", "repo", "format", SourceCheckSkillStatus.UPDATE_AVAILABLE),
                    SourceCheckSkill("testing", "repo", "testing", SourceCheckSkillStatus.PATH_MISSING),
                ),
                perSubagent = listOf(
                    SourceCheckSubagent("review", "repo", "agents/review.toml", "codex", SourceCheckSkillStatus.UPDATE_AVAILABLE),
                    SourceCheckSubagent("broken", "repo", "agents/broken.toml", "codex", SourceCheckSkillStatus.UNAVAILABLE),
                ),
            ),
            lastCheckedAt = "2026-09-05T12:00:00Z",
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                SourceUpdatesScreen(state, onUpdate = { updated = it })
            }
        }

        compose.onNodeWithText("Review").assertIsDisplayed()
        compose.onNodeWithText("Review agent").assertIsDisplayed()
        compose.onNodeWithText(strings.homeSourceUnavailable).assertIsDisplayed()
        compose.onNodeWithText("Testing").assertIsDisplayed()
        compose.onNodeWithText(strings.homeSourcePathMissing).assertIsDisplayed()
        compose.onNodeWithText("Last checked", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("home-source-update-review").performClick()
        compose.runOnIdle { assertEquals(setOf("review"), updated) }
        compose.onNodeWithTag("home-source-update-subagent:review").performClick()
        compose.runOnIdle { assertEquals(setOf("subagent:review"), updated) }
        compose.onNodeWithTag("home-source-update-all").performClick()
        compose.runOnIdle { assertEquals(setOf("review", "format", "subagent:review"), updated) }
    }

    @Test fun `checked source card says when every imported skill is current`() {
        var checks = 0
        val state = HomeSourceUpdates(
            skillNames = mapOf("review" to "Review"),
            report = SourceCheckReport(
                perRepo = emptyList(),
                perSkill = listOf(SourceCheckSkill("review", "repo", "review", SourceCheckSkillStatus.CURRENT)),
            ),
            lastCheckedAt = "2026-09-05T12:00:00Z",
        )

        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) { SourceUpdatesScreen(state, onCheck = { checks++ }) }
        }

        compose.onNodeWithText(strings.homeSourceNothing).assertIsDisplayed()
        compose.onNodeWithTag("home-source-check").performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun `fleet health keeps the place list grouping and the pinned shortcuts`() {
        val board = PlaceBoard(
            pinned = listOf("project:${fixturePath("/tmp/ledger-kmp")}"),
            recent = listOf("project:${fixturePath("/tmp/relay-cli")}", "project:${fixturePath("/tmp/ledger-kmp")}"),
            sets = listOf(ProjectSet("KMP apps", listOf(fixturePath("/tmp/ledger-kmp")))),
        )
        val snapshot = homeSnapshot(
            scanned = listOf(
                place("ledger-kmp", installs = mapOf(rule("a") to synced)),
                place("relay-cli", installs = mapOf(rule("a") to update)),
            ),
            board = board,
            library = LibraryTotals(rules = 1),
        )

        inBothThemes(snapshot) {
            compose.onNodeWithText("KMP APPS · 1").assertIsDisplayed()
            compose.onNodeWithText(strings.placeUngrouped.uppercase() + " · 1").assertIsDisplayed()
            compose.onNodeWithText(strings.homeFleetSummary(2, 1, 1, 0)).assertIsDisplayed()
        }
        // Pinned first, then recent minus the pinned: two chips and two pills, four nodes per name.
        compose.onNodeWithText(strings.homeShortcuts.uppercase()).assertIsDisplayed()
    }

    @Test fun `profiles panel switches an attached profile and names the required restart`() {
        var switched: Triple<String, String, Boolean>? = null
        val project = HomeProfileProject(
            projectKey = fixturePath("/tmp/ledger-kmp"),
            name = "ledger-kmp",
            profiles = listOf(
                HomeProfileBinding("review", "Review", active = false),
                HomeProfileBinding("testing", "Testing", active = true),
            ),
            restartAgents = listOf("Claude Code"),
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                HomeScreen(
                    snapshot = HomeSnapshot(profileProjects = listOf(project), scanned = true),
                    scanning = false,
                    lastScanMillis = null,
                    onRescan = {},
                    onHideAgent = {},
                    onOpenPlace = {},
                    onOpenResolve = {},
                    onOpenLibrary = {},
                    profileChange = HomeProfileChange(fixturePath("/tmp/ledger-kmp"), "review"),
                    canSwitchProfiles = true,
                    onSetProfileActive = { projectKey, profileId, active ->
                        switched = Triple(projectKey, profileId, active)
                    },
                )
            }
        }

        compose.onNodeWithText(strings.homeProfiles.uppercase()).assertIsDisplayed()
        compose.onNodeWithTag("home-profile-review").performClick()
        compose.onNodeWithText(strings.homeProfilesRestartHint("Claude Code")).assertIsDisplayed()
        compose.runOnIdle { assertEquals(Triple(fixturePath("/tmp/ledger-kmp"), "review", true), switched) }
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
    ) = LibraryPlaceUsage("project:${fixturePath("/tmp/$name")}", name, LibraryPlaceKind.PROJECT, installs, files)

    /** Three modified blocks in two places, two updates in one, twelve hand-written lines in one. */
    private fun problems(): HomeSnapshot = homeSnapshot(
        scanned = listOf(
            place(
                "transit-ios",
                installs = mapOf(rule("a") to modified, rule("b") to modified, rule("c") to update),
            ),
            place(
                "ledger-kmp",
                installs = mapOf(rule("a") to modified, rule("b") to update),
                files = listOf(
                    PlaceFile(Path.of("AGENTS.md"), "AGENTS.md", exists = true, mode = TargetOwnershipMode.PARTIAL, unmanagedLines = 12, drift = false),
                ),
            ),
        ),
        library = LibraryTotals(rules = 3),
    )

    private fun show(snapshot: HomeSnapshot, theme: ThemeMode) {
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
    }

    private fun inBothThemes(snapshot: HomeSnapshot, assertions: () -> Unit) {
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent { RuleblendTheme(theme) { Screen(snapshot) } }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode ->
            compose.runOnIdle { theme = mode }
            assertions()
        }
    }

    @Composable
    private fun Screen(snapshot: HomeSnapshot) {
        HomeScreen(
            snapshot = snapshot,
            scanning = false,
            lastScanMillis = null,
            onRescan = { rescans++ },
            onHideAgent = { hiddenAgent = it },
            onOpenPlace = { openedPlace = it },
            onOpenResolve = { openedResolve++ },
            onOpenLibrary = { openedLibrary++ },
        )
    }

    @Composable
    private fun IntegrationScreen(
        state: HomeIntegrationUpdates,
        onUpdate: (Set<String>) -> Unit = {},
    ) {
        HomeScreen(
            snapshot = HomeSnapshot.EMPTY,
            scanning = false,
            lastScanMillis = null,
            onRescan = {},
            onHideAgent = {},
            onOpenPlace = {},
            onOpenResolve = {},
            onOpenLibrary = {},
            integrationUpdates = state,
            onUpdateIntegration = onUpdate,
        )
    }

    @Composable
    private fun SourceUpdatesScreen(
        state: HomeSourceUpdates,
        onCheck: () -> Unit = {},
        onUpdate: (Set<String>) -> Unit = {},
    ) {
        HomeScreen(
            snapshot = HomeSnapshot.EMPTY,
            scanning = false,
            lastScanMillis = null,
            onRescan = {},
            onHideAgent = {},
            onOpenPlace = {},
            onOpenResolve = {},
            onOpenLibrary = {},
            sourceUpdates = state,
            onCheckSources = onCheck,
            onUpdateSources = onUpdate,
        )
    }
}
