package dev.ruleblend.app.library

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.app.assistance.assistanceObject
import dev.ruleblend.app.assistance.assistanceDraftDirty
import dev.ruleblend.core.storage.LibraryRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class LibraryLoadRaceTest {
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var model: LibraryModel

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-library-load")
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        repository.saveBlock(Block(id = "scoped", name = "scoped", content = ""))
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = ConfigStore(root.resolve("config.json")),
        ).also { runBlocking { it.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a read that began before a save does not bring the older text back`() {
        val stale = runBlocking { model.read() }
        model.selectBlock("scoped")
        model.editDraft { it.copy(content = "Scoped body") }
        runBlocking { model.saveDraft() }

        model.land(stale)
        model.selectBlock("scoped")

        assertEquals("Scoped body", model.blocks.single { it.id == "scoped" }.content)
        assertEquals("Scoped body", model.draft?.content)
    }

    @Test
    fun `a read that began after the last save lands`() {
        repository.saveBlock(Block(id = "outside", name = "outside", content = "Written elsewhere"))

        model.land(runBlocking { model.read() })

        assertEquals("Written elsewhere", model.blocks.single { it.id == "outside" }.content)
    }

    @Test fun `external writes update clean editors and preserve local drafts`() = runBlocking {
        model.selectBlock("scoped")
        repository.saveBlock(Block(id = "scoped", name = "Renamed by MCP", content = "External one"))
        model.load()
        assertEquals("External one", model.draft?.content)
        assertFalse(model.dirty)
        assertEquals("Renamed by MCP", model.assistanceObject()?.name)
        model.editDraft { it.copy(content = "Unsaved local") }
        repository.saveBlock(Block(id = "scoped", name = "Renamed again", content = "External two"))
        model.load()
        assertEquals("External two", model.blocks.single().content)
        assertEquals("Unsaved local", model.draft?.content)
        assertTrue(model.assistanceDraftDirty())
    }

    @Test fun `skill drafts follow the same external update contract`() = runBlocking {
        repository.writeSkill(SkillSnapshot(Skill(id = "scoped", name = "Skill", content = "Initial"),
            listOf(SkillFile("SKILL.md", "Initial".toByteArray()))))
        model.load()
        model.selectSkill("scoped")
        repository.saveSkill(Skill(id = "scoped", name = "Skill", content = "External"))
        model.load()
        assertEquals("External", model.skillDraft?.content)
        assertFalse(model.skillDirty)
        model.editSkillDraft { it.copy(content = "Local" ) }
        repository.saveSkill(Skill(id = "scoped", name = "Skill", content = "External two"))
        model.load()
        assertEquals("Local", model.skillDraft?.content)
        assertTrue(model.skillDirty)
        model.clearCatalogSelection()
        assertNull(model.assistanceObject())
    }
}
