package dev.ruleblend.app.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.home.HomeModel
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.RuStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.library.LibraryModel
import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ColumnWidths
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.config.SourceCheckMode
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentCli
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.integration.SubagentDraft
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import dev.ruleblend.core.storage.GitSubagentCandidate
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.RemoteSyncStatus
import dev.ruleblend.core.usecase.SourceCheckReport
import dev.ruleblend.core.usecase.SourceCheckRepository
import dev.ruleblend.core.usecase.SourceCheckRepositoryStatus
import dev.ruleblend.core.usecase.SourceCheckSkill
import dev.ruleblend.core.usecase.SourceCheckSubagent
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.mcp.MCP_ENTRY_VERSION
import dev.ruleblend.mcp.McpServing
import dev.ruleblend.mcp.McpServingBuild
import dev.ruleblend.mcp.skill.BundledSkill
import dev.ruleblend.mcp.skill.ClaudeCodeSkillInstaller
import dev.ruleblend.mcp.McpConnector
import dev.ruleblend.mcp.McpLaunch
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.PiConnector
import dev.ruleblend.mcp.skill.PiSkillInstaller
import dev.ruleblend.mcp.skill.SkillInstaller
import dev.ruleblend.mcp.skill.SkillStatus
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * Settings against a real config file: every control writes as it is touched, so each test asserts
 * on what landed in the model rather than on a pending form. Both themes are checked because the
 * screen is where the theme itself is chosen.
 */
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = EnStrings
    private lateinit var root: Path
    private lateinit var configStore: ConfigStore
    private lateinit var repository: LibraryRepository
    private lateinit var library: LibraryModel
    private lateinit var claude: FakeAgent
    private lateinit var connector: FakeConnector

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-settings-screen")
        configStore = ConfigStore(root.resolve("config.json"))
        repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.writeBlock(Block("swift-style", "Swift Style", content = "Use Swift."))
        repository.writeBlock(Block("ruleblend-mcp", "Ruleblend MCP", type = BlockType.MCP, content = "{}"))
        library = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = configStore,
        ).also { m -> runBlocking { m.load() } }
        claude = FakeAgent("claude-code", "Claude Code", root)
        connector = FakeConnector("claude-code", McpRegistration.NOT_REGISTERED)
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test fun `every section is reachable from the navigation, one at a time`() {
        val model = show()
        SettingsSection.entries.forEach { section ->
            compose.onNodeWithTag("settings-nav-${section.name.lowercase()}").assertIsDisplayed()
        }
        goTo(SettingsSection.ABOUT)
        compose.onNodeWithTag("settings-panel-about").assertIsDisplayed()
        // Only the selected section is on screen; the one left behind is gone, not scrolled away.
        compose.onNodeWithTag("settings-panel-general").assertDoesNotExist()
        // The About row reads the same paths the model reports.
        compose.onNodeWithText(strings.setFiles).assertIsDisplayed()
        assertEquals(root.resolve("config.json"), model.configPath)
    }

    @Test fun `appearance and name format write straight through, with a live example`() {
        val model = show()
        compose.onNodeWithTag("settings-theme-dark").performClick()
        compose.runOnIdle {
            assertEquals(ThemeMode.DARK, model.themeMode)
            assertEquals(ThemeMode.DARK, configStore.load().themeMode)
        }
        compose.onNodeWithTag(SettingsShowRecentSwitchTag).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(false, model.showRecentPlaces)
            assertEquals(false, configStore.load().showRecentPlaces)
        }
        goTo(SettingsSection.FOLDING)
        compose.onNodeWithTag("settings-groups-collapsed").performClick()
        compose.runOnIdle {
            assertEquals(false, model.groupsExpandedByDefault)
            assertEquals(false, configStore.load().groupsExpandedByDefault)
        }
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithText(strings.nameFormatSnake).performClick()
        compose.runOnIdle { assertEquals(NameFormat.SNAKE, configStore.load().nameFormat) }
        compose.onNodeWithText("ios_review_checklist").assertIsDisplayed()
    }

    @Test fun `line ending choice persists and converts existing library definitions`() {
        repository.saveBlock(Block("ending", "Ending", content = "first\nsecond\n"))
        val model = show()
        compose.onNodeWithTag("settings-line-ending-crlf").performClick()
        compose.waitUntil(5_000) { !model.lineEndingBusy && model.lineEnding == LineEnding.CRLF }
        assertEquals(LineEnding.CRLF, configStore.load().lineEnding)
        val file = root.resolve("library/blocks/ending.md")
        assertTrue(file.readText().contains("\r\n"))
        compose.onNodeWithTag("settings-line-ending-lf").performClick()
        compose.waitUntil(5_000) { !model.lineEndingBusy && model.lineEnding == LineEnding.LF }
        assertEquals(LineEnding.LF, configStore.load().lineEnding)
        assertEquals(false, file.readText().contains('\r'))
        assertEquals(null, model.lineEndingFailure)
    }

    @Test fun `the place defaults are written from their own section`() {
        val model = show()
        goTo(SettingsSection.FOLDING)
        compose.onNodeWithTag("settings-place-library-collapsed").performClick()
        compose.runOnIdle {
            assertEquals(false, model.placeLibraryExpandedByDefault)
            assertEquals(false, configStore.load().placeLibraryExpandedByDefault)
        }
        compose.onNodeWithTag("settings-place-rules-expanded").performClick()
        compose.runOnIdle {
            assertEquals(true, model.placeRulesExpandedByDefault)
            assertEquals(true, configStore.load().placeRulesExpandedByDefault)
        }
        compose.onNodeWithTag("settings-place-skills-expanded").performClick()
        compose.runOnIdle {
            assertEquals(true, model.placeSkillsExpandedByDefault)
            assertEquals(true, configStore.load().placeSkillsExpandedByDefault)
        }
        compose.onNodeWithTag("settings-place-subagents-expanded").performClick()
        compose.runOnIdle {
            assertEquals(true, model.placeSubagentsExpandedByDefault)
            assertEquals(true, configStore.load().placeSubagentsExpandedByDefault)
        }
        compose.onNodeWithTag("settings-place-mcp-expanded").performClick()
        compose.runOnIdle {
            assertEquals(true, model.placeMcpExpandedByDefault)
            assertEquals(true, configStore.load().placeMcpExpandedByDefault)
        }
    }

    @Test fun `workspace actions clear only what they name`() {
        show()
        configStore.update { it.copy(places = it.places.copy(recent = listOf("project:/tmp/a"))) }
        compose.onNodeWithText(strings.setClearRecent).performClick()
        compose.runOnIdle {
            assertTrue(configStore.load().places.recent.isEmpty())
            // Pins and sets are a different setting and stay where they were.
            assertEquals(emptyList(), configStore.load().places.pinned)
        }
        configStore.update { it.copy(columnWidths = it.columnWidths.copy(placePalette = 420)) }
        compose.onNodeWithText(strings.setResetColumns).performClick()
        compose.runOnIdle { assertEquals(ColumnWidths(), configStore.load().columnWidths) }
    }

    @Test fun `a changed library path is a promise until restart, and can be taken back`() {
        val other = Files.createDirectory(root.resolve("other-library"))
        val model = show()
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithText(strings.setLibraryPathRestartHint).assertDoesNotExist()
        compose.runOnIdle { model.changeLibraryPath(other) }
        compose.onNodeWithText(strings.setLibraryPathRestartHint).assertIsDisplayed()
        compose.onNodeWithTag("settings-library-undo").performClick()
        compose.runOnIdle { assertEquals(root.resolve("library"), model.libraryPath) }
        compose.onNodeWithText(strings.setLibraryPathRestartHint).assertDoesNotExist()
    }

    @Test fun `library statistics count what the repository holds`() {
        show()
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithText(strings.setStatsRules(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.setStatsMcp(1)).assertIsDisplayed()
    }

    @Test fun `source check schedule is written from Library settings`() {
        val model = show()
        goTo(SettingsSection.LIBRARY)

        compose.onNodeWithTag("settings-source-check-on-launch").performClick()

        compose.runOnIdle {
            assertEquals(SourceCheckMode.ON_LAUNCH, model.sourceCheck)
            assertEquals(SourceCheckMode.ON_LAUNCH, configStore.load().sourceCheck)
        }
    }

    @Test fun `sources show persisted metadata, check through Home and reopen their import`() {
        val source = "https://example.com/skills.git"
        repository.writeSkill(
            SkillSnapshot(
                skill = Skill(
                    id = "review",
                    name = "Review",
                    content = "# Review\n",
                    source = GitSkillSource(source, "old-head", "skills/review"),
                ),
                files = listOf(SkillFile("SKILL.md", "# Review\n".encodeToByteArray())),
            ),
        )
        ImportLibrary(repository, LibraryArchive(root.resolve("library"), repository), configStore).applyGit(
            GitRepositoryImportPlan(source, "old-head", "old-head", emptyList(), listOf(
                GitSubagentCandidate("agents/review.toml", "codex", mapOf(
                    "codex" to SubagentDraft("Review agent", content = "Review")), "name = 'Review agent'"))),
            emptySet(), mapOf("agents/review.toml" to "codex"))
        runBlocking { library.load() }
        var checks = 0
        val checkedAt = Instant.parse("2026-09-05T12:00:00Z")
        val home = HomeModel(
            repository = repository,
            configStore = configStore,
            agents = emptyList(),
            usageSource = LibraryUsageSource.None,
            checkImportedSkillSources = {
                checks++
                SourceCheckReport(
                    perRepo = listOf(
                        SourceCheckRepository(source, "abcdef1234567890", SourceCheckRepositoryStatus.CHECKED),
                    ),
                    perSkill = listOf(
                        SourceCheckSkill(
                            skillId = "review",
                            repository = source,
                            path = "skills/review",
                            status = SourceCheckSkillStatus.UPDATE_AVAILABLE,
                            upstreamTreeFingerprint = "new-tree",
                        ),
                    ),
                    perSubagent = listOf(SourceCheckSubagent("review-agent", source, "agents/review.toml", "codex",
                        SourceCheckSkillStatus.UPDATE_AVAILABLE)),
                )
            },
            sourceStateStore = SourcesStateStore(root),
            now = { checkedAt },
        ).also { runBlocking { it.refreshSourceUpdates() } }

        show(home = home)
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithText(source).assertIsDisplayed()
        compose.onAllNodesWithText(strings.setSourceSkills(1)).onLast().assertIsDisplayed()
        compose.onNodeWithText(strings.setSourceSubagents(1)).assertIsDisplayed()
        compose.onNodeWithText(strings.homeSourceNeverChecked).assertIsDisplayed()

        compose.onNodeWithTag("settings-sources-check").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { checks == 1 && !home.sourceUpdates.checking }
        compose.onNodeWithText(strings.setSourceHead("abcdef123456")).assertIsDisplayed()
        compose.onNodeWithText(strings.homeSourceLastChecked(checkedAt.toString())).assertIsDisplayed()

        compose.onNodeWithTag("settings-source-reopen-0").performClick()
        compose.onNodeWithTag("library-import-repository").assertTextEquals(source)
    }

    @Test fun `a configured remote shows its target instead of the form, and Edit brings it back`() {
        configStore.update { it.copy(remoteGit = RemoteGitConfig("git@example.com:me/lib.git", "main", true)) }
        show()
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithTag("settings-remote-sync").assertIsDisplayed()
        compose.onNodeWithText(strings.remoteGitNotSynced, substring = true).assertIsDisplayed()
        compose.onNodeWithTag("settings-remote-edit").performClick()
        compose.onNodeWithTag("settings-remote-url").assertIsDisplayed()
        // Cancel drops the reopened form without touching what is stored.
        compose.onNodeWithText(strings.remoteGitCancel).performClick()
        compose.onNodeWithTag("settings-remote-sync").assertIsDisplayed()
    }

    @Test fun `saving a remote completes sync after the form leaves composition`() {
        val remote = root.resolve("remote")
        LibraryGit(remote).init()
        repository.saveBlock(Block("sync-rule", "Sync rule", content = "Keep this revision"))
        val git = LibraryGit(root.resolve("library"))
        val model = show(libraryGit = git)
        goTo(SettingsSection.LIBRARY)
        compose.onNodeWithTag("settings-remote-url").performTextReplacement(remote.toUri().toString())
        compose.onNodeWithTag("settings-remote-branch").performTextReplacement("main")
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread {
            git.withLock {
                locked.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag("settings-remote-save").performClick()
            compose.waitUntil(5_000) { !model.remoteEditing }
            compose.onNodeWithTag("settings-remote-sync").assertIsDisplayed()
            goTo(SettingsSection.GENERAL)
        } finally {
            release.countDown()
            holder.join(5_000)
        }
        compose.waitUntil(15_000) { !model.remoteSyncing }
        assertEquals(RemoteSyncStatus.PUSHED, model.remoteSyncStatus, model.remoteSyncFailure)
        assertTrue(Files.exists(remote.resolve(".git/refs/heads/main")))
    }

    @Test fun `failed sync remains visible in Library status and About diagnostics`() {
        configStore.update { it.copy(remoteGit = RemoteGitConfig(root.resolve("missing-remote").toUri().toString(), "main", true)) }
        val model = show()
        goTo(SettingsSection.LIBRARY)
        runBlocking { model.syncNow() }

        compose.onNodeWithText(strings.remoteGitFailed, substring = true).assertIsDisplayed()
        goTo(SettingsSection.ABOUT)
        assertTrue(model.diagnostics().contains("Remote sync: FAILED"))
        assertTrue(model.diagnostics().contains("failure="))
    }

    @Test fun `a link into the assistants section opens Settings there`() {
        show(initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agent-claude-code").assertIsDisplayed()
    }

    @Test fun `an agent row carries visibility, capabilities and its connection at once`() {
        val model = show()
        goTo(SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agent-claude-code").assertIsDisplayed()
        compose.onNodeWithTag("settings-capability-rules").assertDoesNotExist()
        compose.onNodeWithTag("settings-details-claude-code").performClick()
        compose.onNodeWithTag("settings-capability-rules").assertIsDisplayed()
        compose.onNodeWithTag("settings-capability-subagents").assertIsDisplayed()
        compose.onAllNodesWithText(strings.connectStatusNotConnected).onLast().assertIsDisplayed()
        compose.onNodeWithTag("settings-connect-claude-code").performClick()
        compose.runOnIdle { assertEquals(ConnectState.CONNECTED, model.connectStates["claude-code"]) }
        compose.onNodeWithTag("settings-visible-claude-code").performClick()
        compose.runOnIdle {
            assertEquals(setOf("claude-code"), model.hiddenAgents)
            assertEquals(listOf("claude-code"), configStore.load().hiddenAgents)
        }
    }

    @Test fun `the ZCode row reports subagents as a supported capability`() {
        show(agents = listOf(claude, ZCodeAdapter(root)))
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-agent-zcode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-details-zcode").performClick()
        compose.onNodeWithTag("settings-capability-subagents").assertTextContains("✓", substring = true)
    }

    @Test fun `the Pi row marks subagents as needing the pi-subagents extension`() {
        show(agents = listOf(claude, PiAdapter(root)))
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-agent-pi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-agent-extension-pi").assertIsDisplayed()
        compose.onNodeWithTag("settings-agent-extension-claude-code").assertDoesNotExist()
        compose.onNodeWithTag("settings-details-pi").performClick()
        compose.onNodeWithTag("settings-capability-subagents").assertTextContains("◐ Subagents*")
        compose.onNodeWithTag("settings-subagent-extension").assertIsDisplayed()
        compose.onNodeWithText("pi install npm:pi-subagents").assertIsDisplayed()
    }

    @Test fun `closing details does not cancel an in-progress connection refresh`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        connector = FakeConnector("claude-code", McpRegistration.NOT_REGISTERED, entered, release)
        val model = show(initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-details-claude-code").performClick()
        compose.onNodeWithTag("settings-connect-claude-code").performClick()
        try {
            compose.waitUntil(5_000) { entered.count == 0L }
            compose.onNodeWithTag("settings-details-claude-code").performClick()
        } finally {
            release.countDown()
        }
        compose.waitUntil(5_000) { model.connectStates["claude-code"] == ConnectState.CONNECTED }
        compose.onNodeWithTag("settings-agent-details-claude-code").assertDoesNotExist()
    }

    @Test fun `a foreign same-name MCP entry has no connect or disconnect action`() {
        connector = FakeConnector("claude-code", McpRegistration.FOREIGN)
        val model = show()
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-details-claude-code").performClick()
        compose.onNodeWithText(strings.connectStatusMcpForeign).assertIsDisplayed()
        compose.onNodeWithTag("settings-connect-claude-code").assertDoesNotExist()
        compose.onNodeWithTag("settings-disconnect-claude-code").assertDoesNotExist()
        assertEquals(ConnectState.MCP_FOREIGN, model.connectStates["claude-code"])
    }

    @Test fun `visibility changed on Home is refreshed before Settings toggles it`() {
        val model = show()
        configStore.update { it.copy(hiddenAgents = listOf("claude-code")) }
        runBlocking { model.refreshAgentVisibility() }
        goTo(SettingsSection.AGENTS)

        compose.runOnIdle { assertEquals(setOf("claude-code"), model.hiddenAgents) }
        compose.onNodeWithTag("settings-visible-claude-code").performClick()
        compose.runOnIdle {
            assertEquals(emptySet(), model.hiddenAgents)
            assertEquals(emptyList(), configStore.load().hiddenAgents)
        }
    }

    @Test fun `an agent without a connector is told how to wire itself by hand`() {
        show(agents = listOf(claude, FakeAgent("pi", "Pi", root)))
        goTo(SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agent-pi").assertIsDisplayed()
        compose.onNodeWithText(strings.setConnManual).assertIsDisplayed()
        val lastRow = compose.onNodeWithTag("settings-agent-pi").fetchSemanticsNode().boundsInRoot
        val manual = compose.onNodeWithTag("settings-manual-toggle").fetchSemanticsNode().boundsInRoot
        assertTrue(manual.top >= lastRow.bottom, "Manual setup must follow the assistant list")
        compose.onNodeWithTag("settings-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithText(strings.connectManualHint).performScrollTo().assertIsDisplayed()
    }

    @Test fun `Pi connects through native MCP without installing packages`() {
        root.resolve(".pi/agent").createDirectories()
        val model = show(
            agents = listOf(claude, PiAdapter(root)),
            connectors = listOf(connector, PiConnector(root)),
            skillInstallers = listOf(PiSkillInstaller(root)),
        )
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-details-pi").performClick()
        compose.onNodeWithTag("settings-connect-pi").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) { model.connectStates["pi"] == ConnectState.CONNECTED }
        compose.runOnIdle {
            assertEquals(ConnectState.CONNECTED, model.connectStates["pi"])
            assertEquals(SkillStatus.INSTALLED, model.skillStates["pi"])
        }
        assertTrue("\"ruleblend\"" in root.resolve(".pi/agent/mcp.json").readText())
    }

    @Test fun `a foreign skill blocks Connect even before an MCP entry exists`() {
        val file = root.resolve(".claude/skills/ruleblend/SKILL.md")
        file.parent.createDirectories()
        file.writeText("---\nname: ruleblend\n---\nMy own skill\n")
        val model = show(skillInstallers = listOf(ClaudeCodeSkillInstaller(root)))
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-details-claude-code").performClick()
        compose.onNodeWithText(strings.connectStatusSkillForeign).assertIsDisplayed()
        compose.onNodeWithTag("settings-connect-claude-code").assertDoesNotExist()
        assertEquals(ConnectState.SKILL_FOREIGN, model.connectStates["claude-code"])
        assertEquals("---\nname: ruleblend\n---\nMy own skill\n", file.readText())
    }

    @Test fun `an outdated bundled skill shows its versions and one button repairs every agent`() {
        val skillFile = root.resolve(".claude/skills/ruleblend/SKILL.md")
        skillFile.parent.createDirectories()
        skillFile.writeText("# Old\n\n${BundledSkill.MARKER_PREFIX} v0 -->\n")
        val model = show(skillInstallers = listOf(ClaudeCodeSkillInstaller(root)))
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-details-claude-code").performClick()
        compose.onNodeWithTag("settings-versions-claude-code")
            .assertTextEquals(strings.setSkillVersion(0, BundledSkill.version))

        compose.onNodeWithTag("settings-update-all").performClick()

        compose.runOnIdle {
            assertEquals(SkillStatus.INSTALLED, model.skillStates["claude-code"])
            assertEquals(emptyList(), model.outdatedAgentIds)
        }
    }

    @Test fun `assistant catalog puts detected agents first and pages without changing preferences`() {
        val agents = (1..20).map { FakeAgent("agent-$it", "Assistant $it", root, available = it > 12) }
        val model = show(agents = agents, connectors = emptyList(), initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agent-agent-13").assertExists()
        compose.onNodeWithTag("settings-agent-agent-1").assertDoesNotExist()
        compose.onNodeWithTag("settings-agents-count").assertTextEquals(strings.setAgentRange(1, 8, 20))
        compose.onNodeWithTag("settings-agents-page-2").performScrollTo().performClick()
        compose.onNodeWithTag("settings-agent-agent-1").assertExists()
        compose.onNodeWithTag("settings-agent-agent-13").assertDoesNotExist()
        compose.onNodeWithTag("settings-assistance-agent-1").assertIsNotEnabled()
        compose.onNodeWithTag("settings-agents-search").performScrollTo().performTextReplacement("Assistant 20")
        compose.onNodeWithTag("settings-agent-agent-20").assertExists()
        compose.onNodeWithTag("settings-agents-count").assertTextEquals(strings.setAgentRange(1, 1, 1))
        compose.onNodeWithTag("settings-agents-pagination").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptySet(), model.hiddenAgents)
            assertEquals(emptySet(), model.assistanceAssistants)
        }
    }

    @Test fun `changing pages keeps the latest CLI draft after an earlier save`() {
        val command = FakeAgent("command", "Command", root, cli = AgentCli("fixture-cli"))
        val others = (1..8).map { FakeAgent("other-$it", "Other $it", root) }
        val model = show(agents = listOf(command) + others, connectors = emptyList(), initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-details-command").performClick()
        compose.onNodeWithTag("settings-cli-command").performScrollTo().performTextReplacement("--first")
        compose.onNodeWithTag("settings-agents-search").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("--first", model.agentCliArguments["command"]) }
        compose.onNodeWithTag("settings-cli-command").performScrollTo().performTextReplacement("--latest")
        compose.onNodeWithTag("settings-agents-page-2").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("--latest", model.agentCliArguments["command"])
            assertEquals("--latest", configStore.load().agentCliArguments["command"])
        }
        compose.onNodeWithTag("settings-agents-page-1").performScrollTo().performClick()
        compose.onNodeWithTag("settings-cli-command").assertTextContains("--latest")
    }

    @Test fun `assistant filters reset paging and explain empty results`() {
        val agents = (1..12).map { FakeAgent("agent-$it", "Assistant $it", root, available = it > 8) }
        show(agents = agents, connectors = emptyList(), initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agents-page-2").performScrollTo().performClick()
        compose.onNodeWithTag("settings-agents-filter-detected").performScrollTo().performClick()
        compose.onNodeWithTag("settings-agents-count").assertTextEquals(strings.setAgentRange(1, 4, 4))
        compose.onNodeWithTag("settings-agents-pagination").assertDoesNotExist()
        compose.onNodeWithTag("settings-agents-filter-attention").performClick()
        compose.onNodeWithTag("settings-agents-empty").assertIsDisplayed()
        compose.onNodeWithText(strings.libResetFilters).performClick()
        compose.onNodeWithTag("settings-agents-count").assertTextEquals(strings.setAgentRange(1, 8, 12))
    }

    @Test fun `unavailable AI help is disabled independently of visibility`() {
        val model = show(initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-assistance-claude-code").assertIsNotEnabled()
        compose.onNodeWithTag("settings-visible-claude-code").performClick()
        compose.runOnIdle {
            assertEquals(setOf("claude-code"), model.hiddenAgents)
            assertEquals(emptySet(), model.assistanceAssistants)
        }
        compose.onNodeWithTag("settings-agent-claude-code").assertIsDisplayed()
    }

    @Test fun `skill conflicts enter the attention filter even without an MCP connector`() {
        val file = root.resolve(".claude/skills/ruleblend/SKILL.md")
        file.parent.createDirectories()
        file.writeText("My own skill")
        show(connectors = emptyList(), skillInstallers = listOf(ClaudeCodeSkillInstaller(root)), initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-agents-filter-attention").performClick()
        compose.onNodeWithTag("settings-agent-claude-code").assertIsDisplayed()
        compose.onNodeWithText(strings.setAgentConflict).assertIsDisplayed()
        assertEquals("My own skill", file.readText())
    }

    @Test fun `a narrow Russian catalog reflows long names and switches at double font scale`() {
        show(
            agents = listOf(FakeAgent("long-name", "Очень длинное имя ассистента ".repeat(8), root)),
            connectors = emptyList(), size = DpSize(560.dp, 1200.dp),
            initialSection = SettingsSection.AGENTS, uiStrings = RuStrings, fontScale = 2f,
        )
        compose.onNodeWithTag("settings-assistance-long-name").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("settings-visible-long-name").assertExists()
        listOf("settings-assistance-long-name", "settings-visible-long-name", "settings-details-long-name").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            val row = compose.onNodeWithTag("settings-agent-long-name").fetchSemanticsNode().boundsInRoot
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= row.left && bounds.right <= row.right, "$tag overflows $row: $bounds")
        }
    }

    @Test fun `Russian details labels stay on one line at double font scale`() {
        show(initialSection = SettingsSection.AGENTS, uiStrings = RuStrings, fontScale = 2f,
            size = DpSize(1600.dp, 1200.dp))
        val button = compose.onNodeWithTag("settings-details-claude-code")
        val collapsedBounds = button.performScrollTo().fetchSemanticsNode().boundsInRoot
        button.performClick()
        compose.onNodeWithText(RuStrings.setAgentCollapse).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(RuStrings.setAgentCollapse)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.lineCount == 1 && !it.hasVisualOverflow },
            layouts.joinToString { "size=${it.size}, lines=${it.lineCount}, width=${it.multiParagraph.width}, right=${it.getLineRight(0)}" })
        val expandedBounds = button.fetchSemanticsNode().boundsInRoot
        assertEquals(collapsedBounds.width, expandedBounds.width)
        assertEquals(collapsedBounds.height, expandedBounds.height)
        button.performClick()
        compose.onNodeWithText(RuStrings.setAgentDetails).assertIsDisplayed()
    }

    @Test fun `nothing to update means no update-everywhere button`() {
        show()
        goTo(SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-update-all").assertDoesNotExist()
    }

    @Test fun `the shortcut reference lists the chords the shell actually binds`() {
        show()
        goTo(SettingsSection.SHORTCUTS)
        compose.onNodeWithText(dev.ruleblend.app.navigation.commandShortcut("K")).assertIsDisplayed()
        compose.onNodeWithText(dev.ruleblend.app.navigation.commandShortcut("1")).assertIsDisplayed()
        compose.onNodeWithText("Esc").assertIsDisplayed()
    }

    @Test fun `a narrow window keeps the navigation, stacked above the section`() {
        show(size = DpSize(560.dp, 800.dp))
        SettingsSection.entries.forEach { section ->
            compose.onNodeWithTag("settings-nav-${section.name.lowercase()}").assertIsDisplayed()
        }
        compose.onNodeWithTag("settings-panel-general").assertIsDisplayed()
    }

    @Test fun `manual setup shows the launcher command without app paths and can switch builds`() {
        val shared = root.resolve("bin/ruleblend-mcp")
        val dev = McpLaunch(shared, fromDevBuild = false,
            serving = McpServing(McpServingBuild.DEV, root.resolve("work").toString(), thisBuild = false))
        val claimed = McpLaunch(shared, fromDevBuild = false,
            serving = McpServing(McpServingBuild.INSTALLED, "/Applications/Ruleblend.app/Contents/MacOS/Ruleblend", thisBuild = true))
        var claims = 0
        val model = show(launch = dev, claimLaunch = { claims++; claimed }, initialSection = SettingsSection.AGENTS)

        compose.onNodeWithTag("settings-mcp-serving").assertDoesNotExist()
        compose.onNodeWithTag("settings-manual-toggle").performScrollTo().performClick()
        compose.onNodeWithText(dev.shellCommand()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(dev.serving!!.source, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("settings-mcp-serving").assertDoesNotExist()
        compose.onNodeWithTag("settings-mcp-serve-here").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.mcpServing == claimed.serving }

        assertEquals(1, claims)
        compose.onNodeWithText(claimed.serving!!.source, substring = true).assertDoesNotExist()
        compose.onNodeWithText(dev.shellCommand()).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("settings-mcp-serve-here").assertCountEquals(0)
    }

    // ---- harness ----

    @Test fun `launcher preparation failure is visible in settings`() {
        val error = "Windows MCP launcher preparation failed: access denied"
        show(launch = null, launchFailure = error, initialSection = SettingsSection.AGENTS)
        compose.onNodeWithTag("settings-launch-error").assertTextEquals(error).assertIsDisplayed()
        compose.onNodeWithText(strings.connectManualHint).assertDoesNotExist()
    }

    /** The section navigation is the only way to a section: one is on screen at a time. */
    private fun goTo(section: SettingsSection) {
        compose.onNodeWithTag("settings-nav-${section.name.lowercase()}").performClick()
        compose.waitForIdle()
    }

    private fun show(
        agents: List<AgentAdapter> = listOf(claude),
        size: DpSize = DpSize(1280.dp, 900.dp),
        skillInstallers: List<SkillInstaller> = emptyList(),
        home: HomeModel? = null,
        connectors: List<McpConnector> = listOf(connector),
        initialSection: SettingsSection = SettingsSection.GENERAL,
        launch: McpLaunch? = McpLaunch(root.resolve("ruleblend"), fromDevBuild = true),
        launchFailure: String? = null,
        claimLaunch: (() -> McpLaunch)? = null,
        libraryGit: LibraryGit = LibraryGit(root.resolve("library")),
        uiStrings: Strings = strings,
        fontScale: Float = 1f,
    ): SettingsModel {
        val model = SettingsModel(
            configStore = configStore,
            defaultLibraryPath = root.resolve("library"),
            libraryGit = libraryGit,
            activeLibraryPath = root.resolve("library"),
            configPath = root.resolve("config.json"),
            agents = agents,
            connectors = connectors,
            skillInstallers = skillInstallers,
            launch = launch,
            launchFailure = launchFailure,
            claimLaunch = claimLaunch,
        ).also { m -> runBlocking { m.load() } }
        var theme by mutableStateOf(ThemeMode.LIGHT)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalStrings provides uiStrings, LocalDensity provides Density(density.density, fontScale)) {
                RuleblendTheme(theme) { Sized(size) { SettingsScreen(model, library, home = home, initialSection = initialSection) } }
            }
        }
        listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { mode -> compose.runOnIdle { theme = mode } }
        compose.runOnIdle { theme = ThemeMode.LIGHT }
        return model
    }

    @Composable
    private fun Sized(size: DpSize, content: @Composable () -> Unit) {
        Box(Modifier.size(size)) { content() }
    }

    private class FakeAgent(
        override val id: String,
        override val name: String,
        private val home: Path,
        private val available: Boolean = true,
        override val cli: AgentCli? = null,
    ) : AgentAdapter {
        override fun isAvailable(): Boolean = available
        override fun globalFile(): Path = home.resolve(".$id").resolve("AGENTS.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    private class FakeConnector(
        override val agentId: String,
        private var registration: McpRegistration,
        private val entered: CountDownLatch? = null,
        private val release: CountDownLatch? = null,
    ) : McpConnector {
        override fun isAvailable(): Boolean = true
        override fun register(binary: Path) {
            entered?.countDown()
            check(release?.await(5, TimeUnit.SECONDS) != false)
            registration = McpRegistration.REGISTERED
        }

        override fun unregister() {
            registration = McpRegistration.NOT_REGISTERED
        }

        override fun status(binary: Path): McpRegistration = registration

        override fun installedEntryVersion(): Int? =
            MCP_ENTRY_VERSION.takeIf { registration != McpRegistration.NOT_REGISTERED && registration != McpRegistration.FOREIGN }
    }
}
