package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryInstall
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryPlaceKind
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Where a place's status dot comes from. Selecting a place reads that place; the dots of the whole
 * list come from the cross-place scan Home, Coverage and the Library share, so opening one project
 * never re-reads the others.
 */
class PlaceDotMarksTest {

    private lateinit var root: Path
    private lateinit var managed: Path
    private lateinit var untouched: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val rule = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
        override fun projectRedirectFile(projectDir: Path): Path = projectDir.resolve("CLAUDE.md")
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-dot-marks").toRealPath()
        managed = root.resolve("ledger").also { it.createDirectories() }
        untouched = root.resolve("orbit").also { it.createDirectories() }
        // A hand-written file with nothing of Ruleblend's in it: the grey mark, not the green one.
        untouched.resolve("AGENTS.md").writeText("# Orbit\n\nWritten by hand.\n")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(rule)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(managed.toString(), untouched.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(): IntegrationModel {
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root))
        val skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null })
        return IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent(root)),
            backupService = BackupService(root.resolve("backups")),
            service = service,
            mcpService = mcpService,
            skillService = skillService,
            usageSource = LibraryUsageScanner(configStore, listOf(agent(root)), service, mcpService, skillService),
        )
    }

    @Test
    fun `the dots of every place come from one scan, whichever place is open`() = runBlocking {
        val model = model()
        model.load()
        val ledger = model.projectTargets.first { it.dir == managed }
        val orbit = model.projectTargets.first { it.dir == untouched }
        model.select(ledger)
        model.install(rule)

        model.refreshUsage()

        assertEquals(setOf(StatusMark.MANAGED), model.dotMarks(ledger))
        // The place that is not open is described by the same scan, without being opened.
        assertEquals(setOf(StatusMark.UNMANAGED), model.dotMarks(orbit))
    }

    @Test
    fun `a hand edit turns the dot of the place holding it, not of its neighbour`() = runBlocking {
        val model = model()
        model.load()
        val ledger = model.projectTargets.first { it.dir == managed }
        model.select(ledger)
        model.install(rule)
        val file = managed.resolve("AGENTS.md")
        file.writeText(file.toFile().readText().replace("Prefer data classes.", "Prefer sealed classes."))

        model.refreshUsage()

        assertEquals(setOf(StatusMark.CONFLICT), model.dotMarks(ledger))
    }

    @Test
    fun `before the first scan a place claims nothing rather than claiming to be in sync`() = runBlocking {
        val model = model()
        model.load()
        assertTrue(model.dotMarks(model.projectTargets.first { it.dir == managed }).isEmpty())
    }

    @Test
    fun `the first scan reads the library before a place has loaded`() = runBlocking {
        var scannedBlocks = emptyList<Block>()
        val model = IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent(root)),
            backupService = BackupService(root.resolve("backups")),
            mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
            usageSource = { blocks, _ ->
                scannedBlocks = blocks
                listOf(
                    LibraryPlaceUsage(
                        id = projectPlaceId(managed.projectKey()),
                        name = "ledger",
                        kind = LibraryPlaceKind.PROJECT,
                        installs = mapOf(
                            LibraryObjectKey(LibraryObjectKind.RULE, rule.id) to LibraryInstall(InstallStatus.SYNCED),
                        ),
                    ),
                )
            },
        )

        model.refreshUsage()

        assertEquals(listOf(rule), scannedBlocks)
        assertEquals(setOf(StatusMark.MANAGED), model.dotMarks(ProjectTarget(managed, listOf(agent(root)))))
    }
}
