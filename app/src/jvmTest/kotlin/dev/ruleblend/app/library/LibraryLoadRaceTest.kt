package dev.ruleblend.app.library

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
