package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.Block
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryMembershipTest {
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-membership")
        repository = LibraryRepository(root).also { it.init() }
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
    }

    @Test
    fun `blocks sharing a display name are ordered by id, not by directory order`() {
        listOf("zeta", "alpha", "mid").forEach { id ->
            repository.saveBlock(Block(id = id, name = "Same", content = id))
        }
        repository.saveBlock(Block(id = "first", name = "Aaa", content = "first"))

        assertEquals(listOf("first", "alpha", "mid", "zeta"), LibraryMembership.blockIds(root))
    }
}
