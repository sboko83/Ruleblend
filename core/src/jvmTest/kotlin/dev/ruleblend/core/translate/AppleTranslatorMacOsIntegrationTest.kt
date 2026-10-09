package dev.ruleblend.core.translate

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Exercises the real helper and the macOS Translation framework behind it — the wire format between
 * Kotlin and Swift is only ever proven by running both, and the protocol half is covered without a
 * framework in [AppleTranslatorWireTest].
 *
 * Not part of the unit gate: it needs a locally built helper, a macOS version that offers the
 * framework and a downloaded language pack, none of which a test can provision. Run it with
 * `./gradlew :core:macOsIntegrationTest`. A missing prerequisite makes a test skipped rather than
 * passed, so a green run cannot be mistaken for a checked integration.
 */
class AppleTranslatorMacOsIntegrationTest {

    // The packaged app finds the helper through Compose's resource dir; a test run has no bundle,
    // so it uses the binary left by `./gradlew :app:buildTranslateHelper`.
    private val builtHelper: Path? = Path.of(System.getProperty("user.dir"))
        .resolveSibling("tools/translate-helper/.build/release/rb-translate")
        .takeIf { it.exists() }

    private val translator = AppleTranslator(helper = builtHelper ?: TranslateHelper.locate())

    private fun requireHelper() =
        assumeTrue("no translate helper on this machine", translator.available)

    private fun requirePack() {
        requireHelper()
        val status = runBlocking { translator.status("en", "ru", TranslationQuality.LOW) }
        assumeTrue("en->ru language pack is not installed", status == TranslationStatus.INSTALLED)
    }

    @Test
    fun `a helper built in this checkout is picked up`() {
        assumeTrue("nothing built in this checkout", builtHelper != null)
        assertTrue(translator.available, "built helper at $builtHelper should be usable")
    }

    @Test
    fun `reports the status of a supported pair`() {
        requireHelper()
        runBlocking {
            val status = translator.status("en", "ru", TranslationQuality.LOW)
            assertTrue(
                status == TranslationStatus.INSTALLED || status == TranslationStatus.SUPPORTED,
                "en->ru should be at least supported, got $status",
            )
            translator.close()
        }
    }

    @Test
    fun `reports an unknown pair as unsupported`() {
        requireHelper()
        runBlocking {
            assertEquals(TranslationStatus.UNSUPPORTED, translator.status("en", "zz", TranslationQuality.LOW))
            translator.close()
        }
    }

    @Test
    fun `a pair the system reports as installed translates, or says the pack went away`() {
        requirePack()
        runBlocking {
            // The framework may still answer packMissing right after reporting the pair installed:
            // the pack is system state that can change between the two calls, not a precondition
            // this test can hold. Both outcomes are the contract; a different error is not.
            val result = runCatching {
                translator.translate(listOf("Rules are written in English."), "en", "ru", TranslationQuality.LOW)
            }
            result.onSuccess { translated ->
                assertEquals(1, translated.size)
                assertTrue(translated.single().isNotBlank())
            }.onFailure { failure ->
                assertTrue(failure is TranslationException, "expected TranslationException, got $failure")
                assertEquals(TranslationError.PACK_MISSING, failure.error)
            }
            translator.close()
        }
    }

    @Test
    fun `blank inputs come back untouched and keep their positions`() {
        requirePack()
        runBlocking {
            val translated = translator.translate(listOf("", "Rules matter.", ""), "en", "ru", TranslationQuality.LOW)
            assertEquals(3, translated.size)
            assertEquals("", translated.first())
            assertEquals("", translated.last())
            translator.close()
        }
    }
}
