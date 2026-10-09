package dev.ruleblend.app.compare

import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedDiffSectionsTest {
    @Test fun keepsChangedFilesSeparateAndRetainsTheirContext() {
        val diff = """
            diff --git a/blocks/one.md b/blocks/one.md
            index 1111111..2222222 100644
            --- a/blocks/one.md
            +++ b/blocks/one.md
            @@ -1,2 +1,2 @@
             keep
            -old blue
            +new green
            diff --git a/blocks/two.md b/blocks/two.md
            new file mode 100644
            --- /dev/null
            +++ b/blocks/two.md
            @@ -0,0 +1 @@
            +second file
        """.trimIndent()

        assertEquals(
            listOf(
                UnifiedDiffSection(source = "keep\nold blue", target = "keep\nnew green"),
                UnifiedDiffSection(source = "", target = "second file"),
            ),
            unifiedDiffSections(diff),
        )
    }
}
