package dev.ruleblend.core.translate

/** How much the translator is allowed to spend per request. */
@kotlinx.serialization.Serializable
enum class TranslationQuality {
    /** Apple's low-latency models: roughly ten times faster, and the default for reading rules. */
    LOW,

    /** Apple Intelligence high-fidelity translation, when the system offers it. */
    HIGH,
}

/** Whether a language pair can be translated on this machine right now. */
enum class TranslationStatus {
    /** Ready to use. */
    INSTALLED,

    /** The system supports the pair, but its language pack has not been downloaded. */
    SUPPORTED,

    /** Not offered by this system at all. */
    UNSUPPORTED,
}

/** Why a translation could not be produced. */
enum class TranslationError {
    /** The pair needs a language pack; only the user can download it, from System Settings. */
    PACK_MISSING,
    UNSUPPORTED,

    /** No translation backend on this machine — non-macOS, or the helper is missing from the bundle. */
    UNAVAILABLE,
    FAILED,
}

class TranslationException(
    val error: TranslationError,
    val detail: String? = null,
) : RuntimeException(detail ?: error.name)

/**
 * Translates plain-text fragments. Callers pass prose only — markdown structure is cut out by
 * [TranslationDocument] first, so nothing here has to understand markup.
 */
interface Translator {

    /** Whether this machine can translate at all; `false` means the UI hides the feature. */
    val available: Boolean

    suspend fun status(from: String, to: String, quality: TranslationQuality): TranslationStatus

    /** Language codes this system can translate, so Settings never carries its own list. */
    suspend fun languages(quality: TranslationQuality): List<String>

    /** Returns one translation per input, in the same order. Throws [TranslationException]. */
    suspend fun translate(
        texts: List<String>,
        from: String,
        to: String,
        quality: TranslationQuality,
    ): List<String>
}
