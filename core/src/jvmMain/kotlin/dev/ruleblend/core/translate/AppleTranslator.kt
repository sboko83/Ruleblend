package dev.ruleblend.core.translate

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Speaks the `rb-translate` protocol: one request line per call, one response line back.
 *
 * The peer is a [TranslateChannel] — the bundled helper process in the app, anything a test cares
 * to answer with elsewhere. Everything above the channel is plain wire handling: ids, timeouts,
 * one retry for a dead peer, and the mapping of helper errors onto [TranslationError].
 */
class AppleTranslator(
    private val channel: TranslateChannel,
    private val timeoutMillis: Long = 30_000,
    private val log: (String) -> Unit = { System.err.println("rb-translate: $it") },
) : Translator {

    constructor(
        helper: Path? = TranslateHelper.locate(),
        timeoutMillis: Long = 30_000,
        log: (String) -> Unit = { System.err.println("rb-translate: $it") },
    ) : this(HelperProcessChannel(helper, log), timeoutMillis, log)

    override val available: Boolean get() = channel.available

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val ids = AtomicInteger(0)

    override suspend fun status(from: String, to: String, quality: TranslationQuality): TranslationStatus {
        val response = exchange(Request(id = ids.incrementAndGet(), op = "status", from = from, to = to, quality = quality.wire()))
        if (!response.ok) throw TranslationException(response.error.toError(), response.detail)
        return when (response.status) {
            "installed" -> TranslationStatus.INSTALLED
            "supported" -> TranslationStatus.SUPPORTED
            else -> TranslationStatus.UNSUPPORTED
        }
    }

    override suspend fun languages(quality: TranslationQuality): List<String> {
        val response = exchange(
            Request(id = ids.incrementAndGet(), op = "languages", from = null, to = null, quality = quality.wire())
        )
        if (!response.ok) throw TranslationException(response.error.toError(), response.detail)
        return response.languages.orEmpty()
    }

    override suspend fun translate(
        texts: List<String>,
        from: String,
        to: String,
        quality: TranslationQuality,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        val response = exchange(
            Request(
                id = ids.incrementAndGet(),
                op = "translate",
                from = from,
                to = to,
                quality = quality.wire(),
                texts = texts,
            )
        )
        if (!response.ok) throw TranslationException(response.error.toError(), response.detail)
        val translated = response.texts ?: throw TranslationException(TranslationError.FAILED, "empty response")
        if (translated.size != texts.size) {
            throw TranslationException(TranslationError.FAILED, "expected ${texts.size} texts, got ${translated.size}")
        }
        return translated
    }

    /** Frees the peer. Safe to call more than once. */
    fun close() {
        channel.reset()
    }

    // ------------------------------------------------------------ exchange

    private suspend fun exchange(request: Request): Response = mutex.withLock {
        if (!channel.available) throw TranslationException(TranslationError.UNAVAILABLE, "helper not found")
        val line = json.encodeToString(request)

        val response = withTimeoutOrNull(timeoutMillis) {
            withContext(Dispatchers.IO) {
                runCatching { channel.send(line) }
                    .recoverCatching {
                        // A dead peer is worth exactly one retry: the second failure is real.
                        log("restarting after ${it.message}")
                        channel.reset()
                        channel.send(line)
                    }
                    .mapCatching { json.decodeFromString<Response>(it) }
                    .getOrElse { throw TranslationException(TranslationError.FAILED, it.message) }
            }
        }
        if (response == null) {
            channel.reset() // a hung peer never answers; the next request gets a fresh one
            throw TranslationException(TranslationError.FAILED, "timed out after ${timeoutMillis}ms")
        }
        if (response.id != request.id) {
            channel.reset()
            throw TranslationException(TranslationError.FAILED, "response id ${response.id} does not match ${request.id}")
        }
        response
    }

    // ------------------------------------------------------------ wire format

    @Serializable
    private data class Request(
        val id: Int,
        val op: String,
        val from: String?,
        val to: String?,
        val quality: String,
        val texts: List<String>? = null,
    )

    @Serializable
    private data class Response(
        val id: Int,
        val ok: Boolean,
        val status: String? = null,
        val texts: List<String>? = null,
        val languages: List<String>? = null,
        @SerialName("error") val error: String? = null,
        val detail: String? = null,
    )

    private fun TranslationQuality.wire(): String = if (this == TranslationQuality.HIGH) "high" else "low"

    private fun String?.toError(): TranslationError = when (this) {
        "packMissing" -> TranslationError.PACK_MISSING
        "unsupported" -> TranslationError.UNSUPPORTED
        else -> TranslationError.FAILED
    }
}
