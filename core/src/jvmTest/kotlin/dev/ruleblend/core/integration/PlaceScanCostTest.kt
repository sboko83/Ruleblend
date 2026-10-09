package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.FileReadScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What one refresh of a place costs in file opens.
 *
 * A screen full of answers about a place — every block's status, the ownership of every file, the
 * hand-written leftovers, the run in file order — used to open the same two files once per question
 * asked. The budget here is the invariant that replaced it: a scan reads each physical file once,
 * whatever the library holds. A time-based test would only print milliseconds; this one fails when
 * the read pattern regresses.
 */
class PlaceScanCostTest {

    private lateinit var dir: Path
    private lateinit var project: Path
    private val service = IntegrationService()
    private val blocks = (1..50).map { Block(id = "rule-$it", name = "Rule $it", content = "Line $it.") }

    private fun agent(home: Path) = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = home.resolve("CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-scan-cost")
        project = dir.resolve("ledger").also { it.createDirectories() }
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a place with fifty installed rules is read once per file`() {
        val target = ProjectTarget(project, listOf(agent(dir)))
        blocks.forEach { service.install(target, it) }
        val files = target.files().filter { it.exists() }
        assertTrue(files.isNotEmpty())

        FileReadScope.reading {
            // Warm the scan the way a refresh starts it, then empty the files on disk. Every answer
            // after this must come from that one read: a question that opens the file again would
            // see an empty file and say so, and the assertions below would fail.
            files.forEach { service.regions(it) }
            files.forEach { it.writeText("") }

            blocks.forEach { assertEquals(InstallStatus.SYNCED, service.status(target, it), it.id) }
            blocks.forEach { assertNotNull(service.regionOf(target, it.id), it.id) }
            files.forEach { file ->
                assertTrue(service.ownershipMode(file) != TargetOwnershipMode.NONE, "the scan still sees a managed file")
                assertTrue(service.regions(file).isNotEmpty())
                service.ownershipDrift(file)
                service.unmanaged(file)
            }
            target.ownedFiles().filter { it in files }.forEach { assertTrue(service.segments(it).isNotEmpty()) }

            assertEquals(files.size, FileReadScope.reads, "a refresh must open each file of the place once")
        }
    }

    @Test
    fun `the same questions outside a scan still see the file as it is now`() {
        val target = ProjectTarget(project, listOf(agent(dir)))
        service.install(target, blocks.first())
        assertEquals(InstallStatus.SYNCED, service.status(target, blocks.first()))

        // No scope, no cache: an edit between two questions is visible to the second one, which is
        // what every write path depends on.
        service.remove(target, blocks.first().id)
        assertEquals(null, service.status(target, blocks.first()))
    }
}
