package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyHomeMigrationTest {

    private lateinit var root: Path
    private val kitbashHome get() = root.resolve(".kitbash")
    private val ruleblendHome get() = root.resolve(".ruleblend")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-home-migration")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `missing legacy home does nothing`() {
        assertEquals(
            LegacyHomeMigrationResult.SOURCE_MISSING,
            LegacyHomeMigration.migrate(kitbashHome, ruleblendHome),
        )
        assertFalse(ruleblendHome.exists())
    }

    @Test
    fun `existing Ruleblend home wins without merging legacy data`() {
        kitbashHome.createDirectories()
        kitbashHome.resolve("legacy.txt").writeText("legacy")
        ruleblendHome.createDirectories()
        ruleblendHome.resolve("current.txt").writeText("current")

        assertEquals(
            LegacyHomeMigrationResult.TARGET_EXISTS,
            LegacyHomeMigration.migrate(kitbashHome, ruleblendHome),
        )
        assertEquals("current", ruleblendHome.resolve("current.txt").readText())
        assertFalse(ruleblendHome.resolve("legacy.txt").exists())
    }

    @Test
    fun `legacy state is copied without mutating its source`() {
        kitbashHome.resolve("library/.git/objects").createDirectories()
        kitbashHome.resolve("library/.git/objects/data").writeText("history")
        kitbashHome.resolve("config.json").writeText("{\"language\":\"ru\"}")
        kitbashHome.resolve("targets.index").writeText("targets")

        assertEquals(
            LegacyHomeMigrationResult.MIGRATED,
            LegacyHomeMigration.migrate(kitbashHome, ruleblendHome),
        )

        assertEquals("history", ruleblendHome.resolve("library/.git/objects/data").readText())
        assertEquals("{\"language\":\"ru\"}", ruleblendHome.resolve("config.json").readText())
        assertEquals("targets", ruleblendHome.resolve("targets.index").readText())
        assertEquals("history", kitbashHome.resolve("library/.git/objects/data").readText())
        assertTrue(kitbashHome.exists())
    }

    @Test
    fun `legacy lock and generated launcher are not copied`() {
        kitbashHome.resolve("bin").createDirectories()
        kitbashHome.resolve("kitbash.lock").writeText("lock")
        kitbashHome.resolve("bin/kitbash-mcp").writeText("launcher")
        kitbashHome.resolve("bin/keep").writeText("keep")

        LegacyHomeMigration.migrate(kitbashHome, ruleblendHome)

        assertFalse(ruleblendHome.resolve("kitbash.lock").exists())
        assertFalse(ruleblendHome.resolve("bin/kitbash-mcp").exists())
        assertEquals("keep", ruleblendHome.resolve("bin/keep").readText())
    }
}
