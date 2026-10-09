package dev.ruleblend.core.translate

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * End to end against the real system translator: segmenter, helper and assembly together. Skipped,
 * not passed, unless a helper is built and the language pack is installed, since neither can be
 * provisioned from a test. Run with `./gradlew :core:macOsIntegrationTest`.
 */
class TranslationRoundTripMacOsIntegrationTest {

    private val helper: Path? = Path.of(System.getProperty("user.dir"))
        .resolveSibling("tools/translate-helper/.build/release/rb-translate")
        .takeIf { it.exists() }

    private val translator = AppleTranslator(helper = helper)

    private val rule = """
        # Documentation

        Docs record decisions, not diffs and not the code. Update the affected doc in
        the same change; a behavior-neutral refactor needs no doc change at all.

        - Never run `--dangerously-skip-permissions` without asking first.
        - Keep the library under /Users/example/.ruleblend/library.

        ```kotlin
        fun main() = println("untouched")
        ```

        <!-- rb1 id="x" version="2" -->
    """.trimIndent()

    @Test
    fun `a real translation keeps the document's shape`() {
        assumeTrue("no translate helper on this machine", translator.available)
        val status = runBlocking { translator.status("en", "ru", TranslationQuality.LOW) }
        assumeTrue("en->ru language pack is not installed", status == TranslationStatus.INSTALLED)

        val document = TranslationDocument.of(rule)
        val translated = runBlocking {
            val texts = document.assemble(
                translator.translate(document.requests, "en", "ru", TranslationQuality.LOW)
            )
            translator.close()
            texts
        }

        assertTrue(translated.contains("```kotlin"), "the fence must survive")
        assertTrue(translated.contains("fun main() = println(\"untouched\")"), "code must survive")
        assertTrue(translated.contains("`--dangerously-skip-permissions`"), "the flag must survive")
        assertTrue(translated.contains("/Users/example/.ruleblend/library"), "the path must survive")
        assertTrue(translated.contains("<!-- rb1 id=\"x\" version=\"2\" -->"), "the marker must survive")

        // The paragraph is two wrapped lines; a translator-invented break would show up as a blank line.
        val paragraph = translated.substringAfter("\n\n").substringBefore("\n\n")
        assertFalse(paragraph.contains("\n\n"), "no blank line inside a paragraph: $paragraph")

        // Blank lines separate the same blocks as before: heading, paragraph, list, fence, marker.
        assertEquals(
            rule.split("\n").count { it.isBlank() },
            translated.split("\n").count { it.isBlank() },
            "block structure changed:\n$translated",
        )
    }
}
