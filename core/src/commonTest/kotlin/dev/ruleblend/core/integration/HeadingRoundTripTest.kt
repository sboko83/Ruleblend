package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Importing a whole file into the library and writing it straight back must not flatten it: the
 * headings that told the sections apart are what an agent greps for, and losing them turns a
 * structured rules file into a pile of paragraphs.
 */
class HeadingRoundTripTest {

    private val source =
        """
        # Working rules

        These rules are binding for every change.

        ## 1. Verification budget

        - Measure, do not look.

        ## 2. Tests

        - Bug fixes get a failing test first.
        """.trimIndent()

    private fun blocksOf(sections: List<Section>): List<Block> =
        sections.mapIndexed { index, section ->
            Block(
                id = "block-$index",
                name = section.displayTitle,
                content = section.body,
                heading = section.title,
                headingLevel = section.headingLevel.takeIf { it > 0 } ?: 2,
            )
        }

    @Test
    fun `adopting every section and writing it back keeps the file as it was`() {
        val sections = SectionExtractor.extract(source)
        val blocks = blocksOf(sections)
        val plan = sections.zip(blocks) { section, block ->
            ManagedDocument.AdoptStep.Replace(section.sourceText, regionFor(block))
        }

        val adopted = WrappedRun(TargetOwnershipMode.OWNED).adoptSections("", plan)

        val body = adopted.lines().filterNot { it.startsWith("<!--") }.joinToString("\n").trim()
        assertEquals(source, body)
    }

    @Test
    fun `every source heading survives the round trip`() {
        val sections = SectionExtractor.extract(source)
        val headings = source.lines().filter { it.startsWith("#") }

        val adopted = WrappedRun(TargetOwnershipMode.OWNED).adoptSections(
            "",
            sections.zip(blocksOf(sections)) { section, block ->
                ManagedDocument.AdoptStep.Replace(section.sourceText, regionFor(block))
            },
        )

        headings.forEach { heading ->
            assertTrue(adopted.lines().any { it == heading }, "lost heading '$heading' in:\n$adopted")
        }
    }
}
