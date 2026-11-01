package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.SkillFile
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Debris in the mods root — partial copies, empty trees, stray files — must not break listing. */
class ModStorageListTest {
    private val scratch = Files.createTempDirectory("ruleblend-mod-list")

    @AfterTest
    fun cleanup() = deleteFixtureTree(scratch)

    private fun library(): LibraryRepository = LibraryRepository(scratch.resolve("library")).also { it.init() }

    private fun demoSnapshot() = ModSnapshot(
        mod = Mod(id = "demo", name = "Demo", manifestName = "demo"),
        files = listOf(
            SkillFile(".claude-plugin/plugin.json", """{"name":"demo"}""".encodeToByteArray(), false),
        ),
    )

    @Test
    fun listSkipsDirectoriesWithoutMeta() {
        val repo = library()
        val debris = scratch.resolve("library").resolve("mods").resolve("half-copied")
        debris.resolve("files/hooks").createDirectories()
        debris.resolve("files/hooks/register.js").writeText("export {}\n")
        assertTrue(repo.listMods().isEmpty())
    }

    @Test
    fun listSkipsStrayFilesAndEmptyDirectories() {
        val repo = library()
        repo.writeMod(demoSnapshot())
        val modsRoot = scratch.resolve("library").resolve("mods")
        modsRoot.resolve("stray.txt").writeText("not a mod\n")
        modsRoot.resolve("empty").createDirectories()
        assertEquals(listOf("demo"), repo.listMods().map { it.id })
    }
}
