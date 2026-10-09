package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BlockHeadingTest {

    private val block = Block(id = "tests", name = "Tests", content = "- Cover the fix with a test.")

    @Test
    fun `taking back an edited region keeps the heading out of the body`() {
        val titled = block.copy(heading = "Tests", headingLevel = 3)

        val taken = titled.withRegionContent("### Tests\n\n- Cover the fix with a test.\n- And the regression.")

        assertEquals("Tests", taken.heading)
        assertEquals(3, taken.headingLevel)
        assertEquals("- Cover the fix with a test.\n- And the regression.", taken.content)
    }

    @Test
    fun `a heading rewritten in the file becomes the block heading`() {
        val titled = block.copy(heading = "Tests", headingLevel = 2)

        val taken = titled.withRegionContent("#### Testing rules\n\nbody")

        assertEquals("Testing rules", taken.heading)
        assertEquals(4, taken.headingLevel)
        assertEquals("body", taken.content)
    }

    @Test
    fun `a heading deleted in the file is dropped from the block`() {
        val titled = block.copy(heading = "Tests")

        val taken = titled.withRegionContent("- Cover the fix with a test.")

        assertEquals("", taken.heading)
        assertEquals("- Cover the fix with a test.", taken.content)
    }

    @Test
    fun `a block without a heading keeps a leading hash line as body text`() {
        val taken = block.withRegionContent("# Tests\n\nbody")

        assertEquals("", taken.heading)
        assertEquals("# Tests\n\nbody", taken.content)
    }
}
