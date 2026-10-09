package dev.ruleblend.core.storage

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupServiceTest {

    private lateinit var dir: Path
    private lateinit var backups: Path
    private lateinit var service: BackupService
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-backup")
        backups = dir.resolve("backups")
        service = BackupService(backups)
        file = dir.resolve("CLAUDE.md")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun recordIsNullWhenNoBackupWasTaken() {
        file.writeText("# Rules\n")

        assertNull(service.record(file))
    }

    @Test
    fun aliasesFindAndRestoreAnExistingSnapshotWithoutMovingItsBlob() {
        file.writeText("before")
        service.backup(file)
        val alias = if (java.io.File.separatorChar == '\\') Path.of(file.toString().uppercase().replace('\\', '/'))
            else file.parent.resolve("../${file.parent.fileName}/${file.fileName}")
        file.writeText("after")
        assertEquals(service.record(file), service.record(alias))
        service.restore(alias)
        assertEquals("before", file.readText())
        alias.writeText("new snapshot")
        service.backup(alias)
        assertEquals(1, Files.list(backups).use { paths -> paths.filter { it.fileName.toString().endsWith(".txt") }.count() }.toInt())
        file.writeText("changed again")
        service.restore(file)
        assertEquals("new snapshot", file.readText())
        service.forget(alias)
        assertNull(service.record(file))
    }

    @Test
    fun backupCapturesExactBytesAndRecordsTimestamp() {
        val original = "# My rules\n\nHand-written.\n"
        file.writeText(original)
        val before = System.currentTimeMillis()

        service.backup(file)

        val record = service.record(file)
        assertNotNull(record)
        assertTrue(record.timestampMillis >= before, "timestamp must not predate the call")
        assertEquals(original, blob(file).readText())
    }

    @Test
    fun restoreWritesTheSnapshotBackExactly() {
        val original = "# My rules\n\nHand-written.\n"
        file.writeText(original)
        service.backup(file)
        file.writeText("completely different content\nwith installed blocks\n")

        service.restore(file)

        assertEquals(original, file.readText())
    }

    @Test
    fun restoreIsNoOpWhenNoBackupExists() {
        file.writeText("# Untouched\n")

        service.restore(file)

        assertEquals("# Untouched\n", file.readText())
    }

    @Test
    fun aSecondBackupReplacesThePreviousSlot() {
        file.writeText("first\n")
        service.backup(file)
        val firstRecord = service.record(file)!!

        file.writeText("second\n")
        service.backup(file)

        val secondRecord = service.record(file)!!
        assertTrue(secondRecord.timestampMillis >= firstRecord.timestampMillis)
        assertEquals("second\n", blob(file).readText())
    }

    @Test
    fun backingUpTwoDistinctFilesKeepsBothSnapshots() {
        val other = dir.resolve("AGENTS.md")
        file.writeText("claude\n")
        other.writeText("agents\n")

        service.backup(file)
        service.backup(other)

        assertNotNull(service.record(file))
        assertNotNull(service.record(other))
        assertEquals("claude\n", blob(file).readText())
        assertEquals("agents\n", blob(other).readText())
    }

    @Test
    fun forgetClearsTheSlot() {
        file.writeText("rules\n")
        service.backup(file)
        assertNotNull(service.record(file))

        service.forget(file)

        assertNull(service.record(file))
        assertFalse(blob(file).exists())
    }

    @Test
    fun forgetClearsOnlyTheRequestedFilesSlot() {
        val other = dir.resolve("AGENTS.md")
        file.writeText("claude\n")
        other.writeText("agents\n")
        service.backup(file)
        service.backup(other)

        service.forget(file)

        assertNull(service.record(file))
        assertFalse(blob(file).exists())
        assertNotNull(service.record(other))
        assertTrue(blob(other).exists())
    }

    @Test
    fun forgetIsNoOpWhenNoSlotExists() {
        file.writeText("rules\n")

        service.forget(file)

        assertNull(service.record(file))
    }

    @Test
    fun restoreSurvivesAReloadByIndex() {
        // A fresh service instance must rediscover existing slots from the on-disk index.
        file.writeText("persistent\n")
        BackupService(backups).backup(file)

        val reloaded = BackupService(backups)
        assertNotNull(reloaded.record(file))
        file.writeText("changed\n")

        reloaded.restore(file)

        assertEquals("persistent\n", file.readText())
    }

    @Test
    fun aBrokenIndexCostsTheListButNotTheSnapshots() {
        file.writeText("persistent\n")
        BackupService(backups).backup(file)
        backups.resolve("index.json").writeText("{ not an index")

        val service = BackupService(backups)

        // The list is gone, and nothing throws where a screen only asked whether a slot exists.
        assertNull(service.record(file))
        // Taking a fresh snapshot writes a usable index again instead of failing on the broken one.
        file.writeText("second\n")
        service.backup(file)
        assertNotNull(service.record(file))
        file.writeText("changed\n")
        service.restore(file)
        assertEquals("second\n", file.readText())
    }

    private fun blob(path: Path): Path =
        java.security.MessageDigest.getInstance("SHA-1")
            .digest(path.toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .let { backups.resolve("$it.txt") }
}
