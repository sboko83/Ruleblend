package dev.ruleblend.app.place

import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.WrappedRun
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What a Place row offers and what pressing it does. The offers are decided without a model; the
 * writes run against real files, because the point of the phase is that a status changes on disk.
 */
class PlaceWriteTest {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private val rule = Block(id = "kotlin-style", name = "Kotlin style", content = "Prefer data classes.")

    private fun agent(home: Path, skills: Boolean = false) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
        override fun projectSkillsDirectory(projectDir: Path): Path? =
            if (skills) projectDir.resolve(".claude").resolve("skills") else null
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-place-write").toRealPath()
        project = root.resolve("ledger-kmp").also { it.createDirectories() }
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(rule)
        configStore = ConfigStore(root.resolve("config.json"))
        configStore.save(AppConfig(projects = listOf(project.toString())))
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun model(skills: Boolean = false): IntegrationModel {
        val agent = agent(root, skills)
        val service = IntegrationService(blockResolver = repository::loadBlock)
        val mcpService = McpInstallService(installers = emptyList(), state = McpStateStore(root))
        val skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null })
        return IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = listOf(agent),
            backupService = BackupService(root.resolve("backups")),
            service = service,
            mcpService = mcpService,
            skillService = skillService,
            usageSource = LibraryUsageScanner(configStore, listOf(agent), service, mcpService, skillService),
        ).also { model ->
            runBlocking { model.load() }
            model.projectTargets.first { it.dir == project }.let{ arg -> runBlocking { model.select(arg) } }
        }
    }

    private val ruleKey = LibraryObjectKey(LibraryObjectKind.RULE, rule.id)

    @Test fun `an object that is not here is offered, one that is synced is only removable`() {
        assertEquals(listOf(PlaceAction.INSTALL), paletteActions(LibraryObjectKind.RULE, null))
        assertEquals(listOf(PlaceAction.REMOVE), paletteActions(LibraryObjectKind.RULE, InstallStatus.SYNCED))
        assertEquals(
            listOf(PlaceAction.UPDATE, PlaceAction.REMOVE),
            paletteActions(LibraryObjectKind.RULE, InstallStatus.UPDATE_AVAILABLE),
        )
    }

    @Test fun `the palette never offers to install over a hand edit`() {
        assertEquals(listOf(PlaceAction.REMOVE), paletteActions(LibraryObjectKind.RULE, InstallStatus.MODIFIED))
    }

    @Test fun `an object the place cannot hold is offered nothing`() {
        assertEquals(emptyList(), paletteActions(LibraryObjectKind.MCP, null, installable = false))
        assertEquals(emptyList(), paletteActions(LibraryObjectKind.SKILL, InstallStatus.SYNCED, installable = false))
    }

    @Test fun `a partly installed group keeps offering the install that completes it`() {
        assertEquals(
            listOf(PlaceAction.INSTALL, PlaceAction.REMOVE),
            paletteActions(LibraryObjectKind.GROUP, InstallStatus.SYNCED, groupComplete = false),
        )
        assertEquals(
            listOf(PlaceAction.REMOVE),
            paletteActions(LibraryObjectKind.GROUP, InstallStatus.SYNCED, groupComplete = true),
        )
    }

    @Test fun `a hand-edited rule resolves on the file with restore, save as version or keep`() {
        assertEquals(
            listOf(PlaceAction.RESTORE, PlaceAction.SAVE_AS_VERSION, PlaceAction.KEEP, PlaceAction.REMOVE),
            fileRowActions(LibraryObjectKind.RULE, InstallStatus.MODIFIED),
        )
    }

    @Test fun `only rules and MCP can turn local edits into library versions`() {
        assertTrue(PlaceAction.SAVE_AS_VERSION in fileRowActions(LibraryObjectKind.MCP, InstallStatus.MODIFIED))
        assertFalse(PlaceAction.SAVE_AS_VERSION in fileRowActions(LibraryObjectKind.SKILL, InstallStatus.MODIFIED))
        assertFalse(PlaceAction.SAVE_AS_VERSION in fileRowActions(LibraryObjectKind.SUBAGENT, InstallStatus.MODIFIED))
    }

    @Test fun `an installed object the library no longer knows can only be taken out`() {
        assertEquals(listOf(PlaceAction.REMOVE), fileRowActions(LibraryObjectKind.RULE, null))
    }

    @Test fun `installing from the palette writes the block and reports it synced`() {
        val model = model()
        assertEquals(null, model.statuses[rule.id])

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) })

        assertEquals(InstallStatus.SYNCED, model.statuses[rule.id])
        assertTrue(rule.content in project.resolve("AGENTS.md").readText())
    }

    @Test fun `removing from the palette takes the block out of the file`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.REMOVE, ruleKey) })

        assertEquals(null, model.statuses[rule.id])
        assertFalse(rule.content in project.resolve("AGENTS.md").readText())
    }

    @Test fun `removing an object the library no longer knows still takes it out of the file`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }
        repository.deleteBlock(rule.id)
        runBlocking { model.load() }
        val file = project.resolve("AGENTS.md")
        assertTrue(rule.content in file.readText())

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.REMOVE, ruleKey) })

        assertFalse(rule.content in file.readText())
    }

    @Test fun `updating a stale block brings the library version back to synced`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }
        repository.saveBlock(rule.copy(content = "Prefer data classes and value classes."))
        runBlocking { model.load() }
        assertEquals(InstallStatus.UPDATE_AVAILABLE, model.statuses[rule.id])

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.UPDATE, ruleKey) })

        assertEquals(InstallStatus.SYNCED, model.statuses[rule.id])
        assertTrue("value classes" in project.resolve("AGENTS.md").readText())
    }

    @Test fun `saving from the library editor updates the source place and keeps group and profile origins`() {
        val model = model()
        val current = model.blocks.single { it.id == rule.id }
        runBlocking { model.install(current, group = "mobile") }
        val saved = repository.saveBlock(current.copy(content = "Prefer immutable data."))

        runBlocking { model.updateEditedRule(saved, placeId(requireNotNull(model.selected))) }

        val region = IntegrationService(blockResolver = repository::loadBlock)
            .regions(project.resolve("AGENTS.md"))
            .single { it.id == rule.id }
        assertEquals(saved.version, region.version)
        assertEquals("Prefer immutable data.", region.content)
        assertEquals("g=mobile", region.origin)
        assertEquals(InstallStatus.SYNCED, model.statuses[rule.id])

        project.resolve("AGENTS.md").writeText(
            WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", regionFor(saved, origin = "p=focused")),
        )
        runBlocking { model.load() }
        val profileSaved = repository.saveBlock(saved.copy(content = "Prefer persistent data."))
        runBlocking { model.updateEditedRule(profileSaved, placeId(requireNotNull(model.selected))) }

        val profileRegion = IntegrationService(blockResolver = repository::loadBlock)
            .regions(project.resolve("AGENTS.md"))
            .single { it.id == rule.id }
        assertEquals("p=focused", profileRegion.origin)
        assertEquals("Prefer persistent data.", profileRegion.content)
    }

    @Test fun `saving a skill in the library updates the source place without creating drift`() {
        val skill = repository.createSkill(
            Skill("community", "Community guidance", version = "1", content = "---\nname: community\n---\n# Version one\n"),
        )
        val model = model(skills = true)
        runBlocking { model.installSkill(skill) }
        val saved = repository.saveSkill(skill.copy(content = "---\nname: community\n---\n# Version two\n"))

        runBlocking { model.updateEditedSkill(saved, placeId(requireNotNull(model.selected))) }
        runBlocking { model.load() }

        assertEquals(InstallStatus.SYNCED, model.skillStatuses.getValue(skill.id).status)
        assertTrue("# Version two" in project.resolve(".claude/skills/community/SKILL.md").readText())
    }

    @Test fun `restoring a hand-edited block puts the library text back`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace(rule.content, "Edited by hand."))
        runBlocking { model.load() }
        assertEquals(InstallStatus.MODIFIED, model.statuses[rule.id])

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.RESTORE, ruleKey) })

        assertEquals(InstallStatus.SYNCED, model.statuses[rule.id])
        assertTrue(rule.content in file.readText())
    }

    @Test fun `saving a hand edit as a version keeps the text and lifts the drift`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace(rule.content, "Edited by hand."))
        runBlocking { model.load() }

        assertTrue(runBlocking { model.performPlaceAction(PlaceAction.SAVE_AS_VERSION, ruleKey) })

        assertEquals(InstallStatus.SYNCED, model.statuses[rule.id])
        assertEquals("Edited by hand.", repository.loadBlock(rule.id)?.content?.trim())
    }

    @Test fun `keeping a hand edit writes nothing`() {
        val model = model()
        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace(rule.content, "Edited by hand."))
        runBlocking { model.load() }
        val before = file.readText()

        assertFalse(runBlocking { model.performPlaceAction(PlaceAction.KEEP, ruleKey) })

        assertEquals(before, file.readText())
        assertEquals(InstallStatus.MODIFIED, model.statuses[rule.id])
        assertEquals(rule.content, repository.loadBlock(rule.id)?.content?.trim())
    }

    @Test fun `every write bumps the revision the set aggregate is refreshed on`() {
        val model = model()
        val before = model.writeRevision

        runBlocking { model.performPlaceAction(PlaceAction.INSTALL, ruleKey) }

        assertTrue(model.writeRevision > before)
    }
}
