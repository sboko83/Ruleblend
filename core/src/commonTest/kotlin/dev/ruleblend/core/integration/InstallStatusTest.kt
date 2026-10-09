package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals

class InstallStatusTest {

    private val block = Block(id = "a", name = "A", version = 2, content = "body v2")

    @Test
    fun syncedWhenRegionMatchesLibrary() {
        assertEquals(InstallStatus.SYNCED, statusOf(regionFor(block), block))
    }

    @Test
    fun updateAvailableWhenLibraryVersionIsNewer() {
        val installed = regionFor(block.copy(version = 1, content = "body v1"))
        assertEquals(InstallStatus.UPDATE_AVAILABLE, statusOf(installed, block))
    }

    @Test
    fun modifiedWhenRegionContentNoLongerMatchesItsHash() {
        val installed = regionFor(block).copy(content = "edited by hand")
        assertEquals(InstallStatus.MODIFIED, statusOf(installed, block))
    }

    @Test
    fun modifiedWhenContentDiffersAtTheSameVersion() {
        val installed = regionFor(block.copy(content = "edited by hand"))
        assertEquals(InstallStatus.MODIFIED, statusOf(installed, block))
    }

    @Test
    fun modifiedWinsOverPendingUpdate() {
        val installed = regionFor(block.copy(version = 1)).copy(content = "edited by hand")
        assertEquals(InstallStatus.MODIFIED, statusOf(installed, block))
    }

    @Test
    fun trailingBlankLinesOfTheBlockBodyAreNotWrittenToTheTarget() {
        val region = regionFor(block.copy(content = "body v2\n\n"))

        assertEquals("body v2", region.content)
        assertEquals(InstallStatus.SYNCED, statusOf(region, block.copy(content = "body v2\n\n")))
    }

    @Test
    fun `region carries the block heading above its body`() {
        val titled = block.copy(heading = "Verification budget", headingLevel = 3)

        assertEquals("### Verification budget\n\nbody v2", regionFor(titled).content)
    }

    @Test
    fun `a block without a heading renders its body alone`() {
        assertEquals("body v2", regionFor(block).content)
    }

    @Test
    fun `an out of range heading level is clamped to markdown depth`() {
        val titled = block.copy(heading = "Deep", headingLevel = 9)

        assertEquals("###### Deep\n\nbody v2", regionFor(titled).content)
    }

    @Test
    fun `an empty body leaves the heading without a trailing blank line`() {
        val titled = block.copy(heading = "Placeholder", content = "")

        assertEquals("## Placeholder", regionFor(titled).content)
    }

    @Test
    fun `adding a heading is an update, not a hand edit`() {
        val installed = regionFor(block)
        val titled = block.copy(version = 3, heading = "Verification budget")

        assertEquals(InstallStatus.UPDATE_AVAILABLE, statusOf(installed, titled))
    }
}
