package dev.ruleblend.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.translate.TranslationDocument
import dev.ruleblend.core.translate.TranslationError
import dev.ruleblend.core.translate.TranslationException
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State of the editor's translation panel.
 *
 * Translation runs only when asked: editing the text marks the panel stale instead of firing a
 * request per keystroke. Results are cached per (text, direction, quality), so reopening the panel
 * or switching between rules costs nothing.
 */
class TranslationModel(
    private val translator: Translator,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
    private val transientRetryDelayMillis: Long = 350,
) {

    /**
     * Whether the panel can be offered at all: macOS with the helper in place, and translation left
     * switched on in Settings. Every translation control in the app asks this first, so turning it
     * off takes the feature out of sight rather than greying it out.
     */
    val available: Boolean get() = translator.available && config.enabled

    var visible by mutableStateOf(false)
        private set

    /** `false` translates source → target (reading), `true` target → source (writing). */
    var reversed by mutableStateOf(false)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<TranslationError?>(null)
        private set

    /** The assembled translation, or `null` before the first successful run. */
    var translation by mutableStateOf<String?>(null)
        private set

    /** The exact text [translation] was produced from, so the panel can tell when it went stale. */
    private var translatedFrom by mutableStateOf<String?>(null)

    // Snapshot state: the file preview asks for many fragments at once and renders whatever has
    // arrived, so a landing translation has to recompose the row that asked for it.
    private val cache = mutableStateMapOf<Key, String>()

    /** Fragments already being translated, so a recomposition does not ask for the same text twice. */
    private val inFlight = mutableSetOf<Key>()
    private var requestGeneration = 0L

    // Snapshot state: [available] is read while composing, so Settings switching translation off has
    // to reach the controls that ask.
    private var config by mutableStateOf(configStore.load().translation)

    val sourceLanguage: String get() = if (reversed) config.targetLanguage else config.sourceLanguage
    val targetLanguage: String get() = if (reversed) config.sourceLanguage else config.targetLanguage

    /** True when the text moved on and the shown translation belongs to an older version of it. */
    fun stale(content: String): Boolean = translation != null && translatedFrom != content

    /** Opens the panel and translates, or closes it. */
    fun toggle(content: String) {
        visible = !visible
        if (!visible) {
            requestGeneration++
            loading = false
            error = null
            return
        }
        reloadConfig() // Settings may have changed the pair or the quality while the panel was closed
        translate(content)
    }

    /** Drops per-rule state when the editor moves to another object. */
    fun reset() {
        requestGeneration++
        visible = false
        loading = false
        error = null
        translation = null
        translatedFrom = null
    }

    fun swapDirection(content: String) {
        requestGeneration++
        loading = false
        reversed = !reversed
        translation = null
        translatedFrom = null
        translate(content)
    }

    /** Re-reads settings; called when Settings may have changed the pair or the quality. */
    fun reloadConfig() {
        val updated = configStore.load().translation
        if (updated == config) return
        requestGeneration++
        config = updated
        cache.clear()
        loading = false
        error = null
        translation = null
        translatedFrom = null
        // An open panel outlives the switch that turned translation off; close it with the feature.
        if (!updated.enabled) visible = false
    }

    /**
     * The translation of [content] once it is known, `null` while it is on its way. For a surface
     * that shows a whole file — many short fragments at once — rather than one text with a panel:
     * there is no single "the translation" there, so each fragment answers for itself.
     */
    fun fragment(content: String): String? = cache[keyOf(content)]

    /**
     * Starts translating [content] unless it is already known or already running. Call it from an
     * effect, not from composition: it launches work.
     */
    fun requestFragment(content: String) {
        if (!available) return
        val key = keyOf(content)
        if (cache.containsKey(key)) return
        if (content.isBlank()) {
            cache[key] = content
            return
        }
        val document = TranslationDocument.of(content)
        if (document.requests.isEmpty()) {
            // Code-only text has no prose; showing the original back is the honest answer.
            cache[key] = content
            return
        }
        if (!inFlight.add(key)) return
        val generation = requestGeneration
        scope.launch {
            try {
                val assembled = document.assemble(translateWithStartupRetry(document.requests, key))
                cache[key] = assembled
                // A surface without a panel reports the failure once, beside its button; a fragment
                // that did arrive has to take that report back down.
                if (generation == requestGeneration) error = null
            } catch (e: TranslationException) {
                if (generation == requestGeneration) error = e.error
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private fun keyOf(content: String) = Key(content, sourceLanguage, targetLanguage, config.quality)

    fun translate(content: String) {
        if (loading) return
        error = null
        if (content.isBlank()) {
            translation = ""
            translatedFrom = content
            return
        }

        val key = keyOf(content)
        cache[key]?.let {
            translation = it
            translatedFrom = content
            return
        }

        val document = TranslationDocument.of(content)
        if (document.requests.isEmpty()) {
            // Code-only rules have no prose; showing the original back is the honest answer.
            translation = content
            translatedFrom = content
            return
        }

        loading = true
        val generation = requestGeneration
        scope.launch {
            try {
                val texts = translateWithStartupRetry(document.requests, key)
                if (generation != requestGeneration) return@launch
                val assembled = document.assemble(texts)
                cache[key] = assembled
                translation = assembled
                translatedFrom = content
            } catch (e: TranslationException) {
                if (generation == requestGeneration) {
                    error = e.error
                    translation = null
                    translatedFrom = null
                }
            } finally {
                if (generation == requestGeneration) loading = false
            }
        }
    }

    /** The system framework can reject its first request while its translation engine is warming up. */
    private suspend fun translateWithStartupRetry(requests: List<String>, key: Key): List<String> {
        return try {
            translator.translate(requests, key.from, key.to, key.quality)
        } catch (first: TranslationException) {
            if (first.error != TranslationError.FAILED) throw first
            delay(transientRetryDelayMillis)
            translator.translate(requests, key.from, key.to, key.quality)
        }
    }

    private data class Key(
        val content: String,
        val from: String,
        val to: String,
        val quality: TranslationQuality,
    )
}
