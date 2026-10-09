package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectoryReplaceTest {
    private lateinit var dir: Path
    private lateinit var target: Path
    private lateinit var backup: Path

    private val old = mapOf("SKILL.md" to "old", "references/a.md" to "a1", "extra.md" to "gone")
    private val new = mapOf("SKILL.md" to "new", "references/a.md" to "a2", "references/b.md" to "b")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-directory-replace")
        target = dir.resolve("review")
        backup = dir.resolve(".review.rollback")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the directory and its main file are there after every step of an update`() {
        write(target, old)
        var steps = 0

        replace(new) {
            steps++
            assertPresent()
            // The main file lands last: whoever reads the new text finds every new file beside it.
            if (target.resolve("SKILL.md").readText() == "new") assertTrue(target.resolve("references/b.md").exists())
        }

        assertTrue(steps > 0)
        assertEquals(new, tree(target))
        assertEquals(old, tree(backup), "the previous tree is kept for the caller")
        assertEquals(listOf(target, backup).sorted(), dir.listDirectoryEntries().sorted(), "no staging left behind")
    }

    @Test
    fun `a failure halfway puts the previous tree back without the directory going missing`() {
        write(target, old)
        var steps = 0

        assertFailsWith<IllegalStateException> {
            replace(new) {
                assertPresent()
                check(++steps != 3) { "disk full" }
            }
        }

        assertTrue(steps > 3, "the restore runs through the same observed steps")
        assertEquals(old, tree(target))
        assertFalse(backup.exists(), "the restore consumes the kept tree")
        assertEquals(listOf(target), dir.listDirectoryEntries())
    }

    @Test
    fun `a caller can put the previous tree back after the update landed`() {
        write(target, old)
        replace(new)

        DirectoryReplace.restore(target, backup, "SKILL.md") { assertPresent() }

        assertEquals(old, tree(target))
        assertFalse(backup.exists())
    }

    @Test
    fun `a refusal before the first change leaves the directory untouched`() {
        write(target, old)

        assertFailsWith<IllegalStateException> {
            DirectoryReplace.replace(target, backup, "SKILL.md", beforeApply = { error("edited meanwhile") }) { write(it, new) }
        }

        assertEquals(old, tree(target))
        assertEquals(listOf(target), dir.listDirectoryEntries())
    }

    @Test
    fun `an existing rollback is preserved and blocks a new replacement`() {
        write(target, old)
        write(backup, mapOf("SKILL.md" to "recover me"))

        assertFailsWith<IllegalStateException> { replace(new) }

        assertEquals(old, tree(target))
        assertEquals(mapOf("SKILL.md" to "recover me"), tree(backup))
    }

    @Test
    fun `a new directory appears whole and goes again on restore`() {
        replace(new)
        assertEquals(new, tree(target))
        assertFalse(backup.exists())

        DirectoryReplace.restore(target, backup)

        assertFalse(Files.exists(target, NOFOLLOW_LINKS))
    }

    @Test
    fun `a linked subdirectory is replaced as an entry, never written through`() {
        val outside = dir.resolve("outside").createDirectories()
        outside.resolve("a.md").writeText("outside")
        write(target, mapOf("SKILL.md" to "old"))
        Files.createSymbolicLink(target.resolve("references"), outside)

        replace(new)

        assertEquals(new, tree(target))
        assertEquals("outside", outside.resolve("a.md").readText())
        assertEquals(listOf(outside.resolve("a.md")), outside.listDirectoryEntries())

        DirectoryReplace.restore(target, backup, "SKILL.md")

        assertTrue(Files.isSymbolicLink(target.resolve("references")), "the link itself comes back")
        assertEquals("outside", outside.resolve("a.md").readText())
    }

    @Test
    fun `a target that is itself a link is set aside whole`() {
        val outside = dir.resolve("outside").createDirectories()
        outside.resolve("SKILL.md").writeText("outside")
        Files.createSymbolicLink(target, outside)

        replace(new)

        assertFalse(Files.isSymbolicLink(target))
        assertEquals(new, tree(target))
        assertEquals(listOf(outside.resolve("SKILL.md")), outside.listDirectoryEntries())

        DirectoryReplace.restore(target, backup, "SKILL.md")

        assertTrue(Files.isSymbolicLink(target))
        assertEquals("outside", outside.resolve("SKILL.md").readText())
    }

    private fun replace(files: Map<String, String>, onChange: () -> Unit = {}) =
        DirectoryReplace.replace(target, backup, "SKILL.md", onChange = onChange) { write(it, files) }

    private fun assertPresent() {
        assertTrue(Files.isDirectory(target, NOFOLLOW_LINKS), "the directory is there")
        assertTrue(target.resolve("SKILL.md").exists(), "its SKILL.md is there")
    }

    private fun write(root: Path, files: Map<String, String>) = files.forEach { (path, text) ->
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun tree(root: Path): Map<String, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList()
    }.associate { root.relativize(it).joinToString("/") to it.readText() }
}
