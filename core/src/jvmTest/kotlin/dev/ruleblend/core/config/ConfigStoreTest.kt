package dev.ruleblend.core.config

import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.storage.InterProcessLock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigStoreTest {

    private lateinit var dir: Path
    private lateinit var file: Path
    private lateinit var store: ConfigStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-config")
        file = dir.resolve("config.json")
        store = ConfigStore(file)
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun missingFileGivesDefaults() {
        assertEquals(AppConfig(), store.load())
    }

    @Test
    fun roundTrip() {
        val config = AppConfig(
            projects = listOf("/a", "/b"),
            ruleScopes = mapOf("project-rule" to "/a"),
            disabledAgents = mapOf("/a" to listOf("codex")),
        )
        store.save(config)

        assertEquals(config.normalizedKeys(), store.load())
    }

    @Test
    fun windowGeometrySurvivesARoundTripAndIsAbsentUntilItIsSet() {
        assertEquals(null, store.load().window)

        store.save(AppConfig(window = WindowGeometry(width = 1280, height = 800, x = 40, y = 60)))

        assertEquals(WindowGeometry(1280, 800, 40, 60), store.load().window)
    }

    @Test
    fun configWithoutDisabledAgentsStillLoads() {
        file.writeText("""{"projects":["/a"]}""")

        assertEquals(AppConfig(projects = listOf(projectKeyOf("/a"))), store.load())
    }

    @Test
    fun configBeforeProjectProfilesMigratesToNoProfileBindings() {
        file.writeText("""{"projects":["/a"]}""")

        assertEquals(emptyMap(), store.load().projectProfiles)
    }

    @Test
    fun projectProfileBindingsRoundTripWithNormalizedProjectKeys() {
        val project = dir.resolve("project")
        store.save(
            AppConfig(
                projectProfiles = mapOf(
                    project.resolve("..").resolve("project").toString() to listOf(
                        ProfileBinding(id = "review", active = true),
                        ProfileBinding(id = "focus", active = false),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(ProfileBinding(id = "review", active = true), ProfileBinding(id = "focus", active = false)),
            store.load().projectProfiles[project.projectKey()],
        )
    }

    @Test
    fun configWithoutRuleScopesTreatsEveryRuleAsGlobal() {
        file.writeText("""{"projects":["/a"]}""")

        assertEquals(emptyMap(), store.load().ruleScopes)
        assertEquals(null, store.load().ruleScope("rule"))
    }

    @Test
    fun ruleScopeCanMoveBetweenProjectAndGlobal() {
        val project = dir.resolve("project")

        val scoped = AppConfig().withRuleScope("rule", project)
        assertEquals(project.projectKey(), scoped.ruleScope("rule"))

        assertEquals(null, scoped.withRuleScope("rule", null).ruleScope("rule"))
    }

    @Test
    fun projectRuleAppliesOnlyToItsProject() {
        val project = dir.resolve("project")
        val other = dir.resolve("other")
        val config = AppConfig().withRuleScope("scoped", project)

        assertEquals(true, config.ruleAppliesTo("global", null))
        assertEquals(true, config.ruleAppliesTo("global", other))
        assertEquals(true, config.ruleAppliesTo("scoped", project))
        assertEquals(false, config.ruleAppliesTo("scoped", other))
        assertEquals(false, config.ruleAppliesTo("scoped", null))
    }

    @Test
    fun configFromANewerBuildStillLoads() {
        file.writeText("""{"projects":["/a"],"futureSetting":{"nested":true}}""")

        assertEquals(AppConfig(projects = listOf(projectKeyOf("/a"))), store.load())
    }

    @Test
    fun unknownNameFormatFallsBackToTheDefault() {
        file.writeText("""{"nameFormat":"bogus"}""")

        assertEquals(NameFormat.KEBAB, store.load().nameFormat)
    }

    @Test
    fun themeModeRoundTrips() {
        store.save(AppConfig(themeMode = ThemeMode.DARK))

        assertEquals(ThemeMode.DARK, store.load().themeMode)
    }

    @Test
    fun groupExpansionDefaultsToExpandedAndRoundTrips() {
        assertEquals(true, store.load().groupsExpandedByDefault)

        store.save(AppConfig(groupsExpandedByDefault = false))

        assertEquals(false, store.load().groupsExpandedByDefault)
    }

    @Test
    fun sourceCheckDefaultsToManualAndRoundTripsItsConfigValue() {
        assertEquals(SourceCheckMode.MANUAL, store.load().sourceCheck)

        store.save(AppConfig(sourceCheck = SourceCheckMode.ON_LAUNCH))

        assertEquals(SourceCheckMode.ON_LAUNCH, store.load().sourceCheck)
        assertTrue(file.readText().contains("\"sourceCheck\": \"onLaunch\""))
    }

    @Test
    fun unknownSourceCheckFallsBackToManual() {
        file.writeText("""{"sourceCheck":"daily"}""")

        assertEquals(SourceCheckMode.MANUAL, store.load().sourceCheck)
    }

    @Test
    fun unknownThemeModeFallsBackToSystem() {
        file.writeText("""{"themeMode":"SEPIA"}""")

        assertEquals(ThemeMode.SYSTEM, store.load().themeMode)
    }

    @Test
    fun updateTransformsAndPersists() {
        store.save(AppConfig(projects = listOf("/a")))

        val updated = store.update { it.copy(projects = it.projects + projectKeyOf("/b")) }

        assertEquals(listOf("/a", "/b").map(::projectKeyOf), updated.projects)
        assertEquals(updated, store.load())
    }

    @Test
    fun updateWithLockRoundTrips() {
        val locked = ConfigStore(file, InterProcessLock(dir.resolve("ruleblend.lock")))

        val updated = locked.update { it.copy(language = "ru") }

        assertEquals("ru", updated.language)
        assertEquals(updated, locked.load())
    }

    @Test
    fun columnWidthsDefaultToOriginalLayout() {
        assertEquals(ColumnWidths(), store.load().columnWidths)
    }

    @Test
    fun columnWidthsRoundTrip() {
        store.save(
            AppConfig(
                columnWidths = ColumnWidths(
                    libraryFacets = 240,
                    libraryInspector = 260,
                    integrationSidebar = 170,
                    integrationFoundPaneHeight = 160,
                ),
            ),
        )

        assertEquals(
            ColumnWidths(libraryFacets = 240, libraryInspector = 260, integrationSidebar = 170, integrationFoundPaneHeight = 160),
            store.load().columnWidths,
        )
    }

    @Test
    fun configWithoutColumnWidthsFallsBackToDefaults() {
        file.writeText("""{"projects":["/a"]}""")

        assertEquals(ColumnWidths(), store.load().columnWidths)
    }

    @Test
    fun remoteGitRoundTripsWithoutCredentials() {
        val remote = RemoteGitConfig(
            url = "https://git.example.com/alice/ruleblend-backup.git",
            branch = "main",
            automatic = true,
        )

        store.save(AppConfig(remoteGit = remote))

        assertEquals(remote, store.load().remoteGit)
        val serialized = file.toFile().readText()
        assertEquals(false, serialized.contains("password", ignoreCase = true))
        assertEquals(false, serialized.contains("token", ignoreCase = true))
    }

    @Test
    fun externalEditorSettingRoundTrips() {
        store.save(AppConfig(useExternalEditor = true, externalEditorPath = "/Applications/Zed.app"))

        assertEquals(true, store.load().useExternalEditor)
        assertEquals("/Applications/Zed.app", store.load().externalEditorPath)
    }

    /** A config written by an older build keeps working: an absent key is its default, not a failure. */
    @Test
    fun configWithoutTheNewerKeysStillLoads() {
        file.writeText("""{"projects":["/a"],"places":{"pinned":["project:/a"]}}""")

        assertEquals(listOf("project:${projectKeyOf("/a")}"), store.load().places.pinned)
        assertEquals(true, store.load().placeLibraryExpandedByDefault)
        assertEquals(false, store.load().placeRulesExpandedByDefault)
        assertEquals(false, store.load().placeSkillsExpandedByDefault)
        assertEquals(false, store.load().placeSubagentsExpandedByDefault)
        assertEquals(false, store.load().placeMcpExpandedByDefault)
    }

    /** A key this build dropped, from the build that wrote it, must not stop the config from loading. */
    @Test
    fun configWithAKeyThisBuildNoLongerKnowsStillLoads() {
        file.writeText("""{"projects":["/a"],"presets":[{"name":"Base","items":[{"kind":"RULE","id":"x"}]}]}""")

        assertEquals(listOf(projectKeyOf("/a")), store.load().projects)
    }

    @Test
    fun twoStoresSharingALockDoNotLoseEachOthersChanges() {
        // Two ConfigStore instances stand in for the two processes the app runs as: the GUI and the
        // MCP server, each holding its own store over one file.
        val lock = { InterProcessLock(dir.resolve("ruleblend.lock")) }
        val gui = ConfigStore(file, lock())
        val server = ConfigStore(file, lock())
        val writers = 4
        val perWriter = 25

        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until writers).map { writer ->
            Thread {
                start.await()
                val store = if (writer % 2 == 0) gui else server
                repeat(perWriter) { round ->
                    store.update { it.copy(projects = it.projects + "/p/$writer-$round") }
                }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(writers * perWriter, gui.load().projects.size)
        assertEquals(writers * perWriter, gui.load().projects.distinct().size)
    }

    @Test
    fun anUpdateSeesWhatTheOtherStoreWroteInBetween() {
        val lock = InterProcessLock(dir.resolve("ruleblend.lock"))
        val gui = ConfigStore(file, lock)
        val server = ConfigStore(file, lock)

        gui.update { it.copy(language = "ru") }
        val afterServer = server.update { it.copy(projects = it.projects + projectKeyOf("/p/one")) }

        assertEquals("ru", afterServer.language)
        assertEquals(listOf(projectKeyOf("/p/one")), afterServer.projects)
        assertEquals(afterServer, gui.load())
    }

    @Test
    fun `a config that is not JSON at all is moved aside and the app gets defaults`() {
        file.writeText("{ this is not json")

        val config = store.load()

        assertEquals(AppConfig(), config)
        val moved = store.quarantined
        assertNotNull(moved, "the unreadable file must be kept, not dropped")
        assertEquals("{ this is not json", moved.readText())
        assertFalse(file.exists(), "the unreadable file is out of the way, not left to fail again")
    }

    @Test
    fun `a field of the wrong shape is treated the same way`() {
        // `projects` is a list; a string there is not something coerceInputValues can rescue.
        file.writeText("""{"projects":"/Users/me/ledger"}""")

        assertEquals(AppConfig(), store.load())
        assertNotNull(store.quarantined)
    }

    @Test
    fun `a truncated write is not parsed as a smaller config`() {
        store.save(AppConfig(projects = listOf("/Users/me/ledger"), language = "ru"))
        val whole = file.readText()
        file.writeText(whole.substring(0, whole.length / 2))

        assertEquals(AppConfig(), store.load())
        assertNotNull(store.quarantined)
    }

    @Test
    fun `a second broken config is kept beside the first instead of replacing it`() {
        file.writeText("first broken")
        store.load()
        val first = store.quarantined
        assertNotNull(first)

        file.writeText("second broken")
        store.load()
        val second = store.quarantined
        assertNotNull(second)

        assertNotEquals(first, second)
        assertEquals("first broken", first.readText())
        assertEquals("second broken", second.readText())
    }

    @Test
    fun `an unknown field or an unknown value still loads, and nothing is moved`() {
        file.writeText("""{"projects":["/Users/me/ledger"],"somethingNewer":42,"nameFormat":"SHOUTING"}""")

        val config = store.load()

        assertEquals(listOf(projectKeyOf("/Users/me/ledger")), config.projects)
        assertNull(store.quarantined, "a config this build can still read is not a broken one")
    }

    @Test
    fun `settings written after a broken config replace it cleanly`() {
        file.writeText("{ broken")
        store.load()

        val updated = store.update { it.copy(language = "ru") }

        assertEquals("ru", updated.language)
        assertEquals("ru", ConfigStore(file).load().language)
    }

    @Test
    fun `a project written under an unnormalized path is read under its one key`() {
        val ledger = dir.resolve("ledger")
        val roundabout = dir.resolve("ledger/../ledger").toString()
        store.save(
            AppConfig(
                projects = listOf(roundabout),
                ruleScopes = mapOf("kotlin-style" to roundabout),
                disabledAgents = mapOf(roundabout to listOf("codex")),
            ),
        )

        val config = ConfigStore(file).load()

        assertEquals(listOf(ledger.projectKey()), config.projects)
        assertEquals(ledger.projectKey(), config.ruleScopes.getValue("kotlin-style"))
        assertEquals(listOf("codex"), config.disabledAgents.getValue(ledger.projectKey()))
    }

    @Test
    fun `two spellings of one project collapse into one entry`() {
        val ledger = dir.resolve("ledger")
        store.save(AppConfig(projects = listOf(ledger.toString(), dir.resolve("ledger/../ledger").toString())))

        assertEquals(listOf(ledger.projectKey()), ConfigStore(file).load().projects)
    }

    @Test
    fun `pins, recents and sets follow the same key`() {
        val ledger = dir.resolve("ledger")
        val roundabout = dir.resolve("ledger/../ledger").toString()
        store.save(
            AppConfig(
                projects = listOf(roundabout),
                places = PlaceBoard(
                    pinned = listOf("project:$roundabout", "agent:claude-code"),
                    recent = listOf("project:$roundabout"),
                    sets = listOf(ProjectSet("Work", listOf(roundabout))),
                ),
            ),
        )

        val board = ConfigStore(file).load().places

        assertEquals(listOf("project:${ledger.projectKey()}", "agent:claude-code"), board.pinned)
        assertEquals(listOf("project:${ledger.projectKey()}"), board.recent)
        assertEquals(listOf(ledger.projectKey()), board.sets.single().projects)
    }
}
