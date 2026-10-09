package dev.ruleblend.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * The protocol half of the translator, with the macOS framework left out: what goes on the wire,
 * what comes back, and what a caller is told when the peer misbehaves. The framework half lives in
 * [AppleTranslatorMacOsIntegrationTest], which needs a machine that has it.
 */
class AppleTranslatorWireTest {

    /** Answers request lines from a script; records what it was asked and how often it was reset. */
    private class ScriptedChannel(
        override val available: Boolean = true,
        val answer: (JsonObject) -> String,
    ) : TranslateChannel {
        val requests = mutableListOf<JsonObject>()
        var resets = 0

        override fun send(line: String): String {
            val request = Json.parseToJsonElement(line) as JsonObject
            requests += request
            return answer(request)
        }

        override fun reset() {
            resets++
        }
    }

    private fun JsonObject.id(): Int = getValue("id").jsonPrimitive.int

    private fun ok(request: JsonObject, body: String): String = """{"id":${request.id()},"ok":true,$body}"""

    @Test
    fun `status maps every answer the helper can give`() = runBlocking {
        val answers = mapOf(
            "installed" to TranslationStatus.INSTALLED,
            "supported" to TranslationStatus.SUPPORTED,
            "unsupported" to TranslationStatus.UNSUPPORTED,
        )
        answers.forEach { (wire, expected) ->
            val channel = ScriptedChannel { ok(it, """"status":"$wire"""") }
            assertEquals(expected, AppleTranslator(channel).status("en", "ru", TranslationQuality.LOW))
            assertEquals("status", channel.requests.single().getValue("op").jsonPrimitive.content)
        }
    }

    @Test
    fun `quality travels as the word the helper expects`() = runBlocking {
        val channel = ScriptedChannel { ok(it, """"status":"installed"""") }
        val translator = AppleTranslator(channel)
        translator.status("en", "ru", TranslationQuality.LOW)
        translator.status("en", "ru", TranslationQuality.HIGH)
        assertEquals(listOf("low", "high"), channel.requests.map { it.getValue("quality").jsonPrimitive.content })
    }

    @Test
    fun `languages come back as the helper listed them`() = runBlocking {
        val channel = ScriptedChannel { ok(it, """"languages":["en","ru","de"]""") }
        assertEquals(listOf("en", "ru", "de"), AppleTranslator(channel).languages(TranslationQuality.LOW))
    }

    @Test
    fun `a translation carries its texts out and back in order`() = runBlocking {
        val channel = ScriptedChannel { ok(it, """"texts":["Один","","Два"]""") }
        val translated = AppleTranslator(channel).translate(listOf("One", "", "Two"), "en", "ru", TranslationQuality.LOW)
        assertEquals(listOf("Один", "", "Два"), translated)
        assertEquals(3, (channel.requests.single().getValue("texts") as List<*>).size)
    }

    @Test
    fun `nothing to translate never reaches the helper`() = runBlocking {
        val channel = ScriptedChannel { error("must not be asked") }
        assertEquals(emptyList(), AppleTranslator(channel).translate(emptyList(), "en", "ru", TranslationQuality.LOW))
        assertTrue(channel.requests.isEmpty())
    }

    @Test
    fun `a missing language pack is reported as such, not as a generic failure`() = runBlocking {
        val channel = ScriptedChannel { """{"id":${it.id()},"ok":false,"error":"packMissing","detail":"en-ru"}""" }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(channel).translate(listOf("Rules."), "en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.PACK_MISSING, failure.error)
        assertEquals("en-ru", failure.detail)
    }

    @Test
    fun `an error word the helper invents later still fails cleanly`() = runBlocking {
        val channel = ScriptedChannel { """{"id":${it.id()},"ok":false,"error":"somethingNew"}""" }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(channel).status("en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.FAILED, failure.error)
    }

    @Test
    fun `a short answer is refused instead of being lined up with the wrong text`() = runBlocking {
        val channel = ScriptedChannel { ok(it, """"texts":["Один"]""") }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(channel).translate(listOf("One", "Two"), "en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.FAILED, failure.error)
        assertTrue(failure.detail.orEmpty().contains("expected 2"))
    }

    @Test
    fun `an answer to another request drops the peer rather than being used`() = runBlocking {
        val channel = ScriptedChannel { """{"id":${it.id() + 1},"ok":true,"status":"installed"}""" }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(channel).status("en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.FAILED, failure.error)
        assertEquals(1, channel.resets)
    }

    @Test
    fun `a peer that dies once is restarted, and its second failure is real`() = runBlocking {
        var attempts = 0
        val flaky = ScriptedChannel {
            attempts++
            if (attempts == 1) error("helper closed its output") else ok(it, """"status":"installed"""")
        }
        assertEquals(TranslationStatus.INSTALLED, AppleTranslator(flaky, log = {}).status("en", "ru", TranslationQuality.LOW))
        assertEquals(1, flaky.resets)

        val dead = ScriptedChannel { error("helper closed its output") }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(dead, log = {}).status("en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.FAILED, failure.error)
    }

    @Test
    fun `a peer that never answers times out and is dropped`() = runBlocking {
        val channel = ScriptedChannel {
            Thread.sleep(2_000)
            ok(it, """"status":"installed"""")
        }
        val failure = assertFailsWith<TranslationException> {
            AppleTranslator(channel, timeoutMillis = 100).status("en", "ru", TranslationQuality.LOW)
        }
        assertEquals(TranslationError.FAILED, failure.error)
        assertTrue(failure.detail.orEmpty().contains("timed out"))
        assertEquals(1, channel.resets)
    }

    @Test
    fun `no peer at all is unavailable, not a failure to explain`() = runBlocking {
        val channel = ScriptedChannel(available = false) { error("must not be asked") }
        val translator = AppleTranslator(channel)
        assertTrue(!translator.available)
        val failure = assertFailsWith<TranslationException> { translator.status("en", "ru", TranslationQuality.LOW) }
        assertEquals(TranslationError.UNAVAILABLE, failure.error)
        assertTrue(channel.requests.isEmpty())
    }
}
