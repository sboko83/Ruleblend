package dev.ruleblend.core.integration

/** Ownership mode detectable from a target file without machine-local state. */
enum class TargetOwnershipMode { NONE, LEGACY, PARTIAL, OWNED }

/** Wrapped-run marker generation. New writes use [RB1]; [KB1] remains readable for migration. */
enum class WrappedRunFormat(val marker: String, val terminator: String) {
    RB1("rb1", "rb:end"),
    KB1("kb1", "kb:end"),
}

/** A library block listed in a wrapped-run manifest. */
data class WrappedRunBlock(
    val id: String,
    val version: Int,
    /** UTF-8 byte length of this block's rendered body. */
    val length: Int? = null,
    /**
     * Legacy group id, retained for callers that predate profiles. New wrapped headers render it
     * as an [origin] tag and readers restore it from either a bare legacy tag or `g=`.
     */
    val group: String? = null,
    /** `g=<group-id>` or `p=<profile-id>`; absent means the target's base installation. */
    val origin: String? = group?.let { "g=$it" },
)

/** Parsed rb1 or legacy kb1 manifest, which occupies the opening line of a managed run. */
data class WrappedRunHeader(
    val mode: TargetOwnershipMode,
    val hash: String,
    val blocks: List<WrappedRunBlock>,
    /** Whether the human-facing warning is rendered for this target. */
    val notice: Boolean = true,
    val format: WrappedRunFormat = WrappedRunFormat.RB1,
) {
    init {
        require(mode == TargetOwnershipMode.PARTIAL || mode == TargetOwnershipMode.OWNED) {
            "A wrapped-run header must be partial or owned"
        }
    }
}

private const val OWNED = "owned"
private const val OWNED_NOTICE = " — managed by Ruleblend; edits are overwritten"
private val OWNED_NOTICE_SUFFIXES = setOf(
    "— managed by Ruleblend; edits are overwritten",
    "— managed by Kitbash; edits are overwritten",
)
private val WRAPPED_HEADER = Regex("""^<!--\s*(rb1|kb1)\s+(.+?)\s*-->[ \t]*$""")
// Trailing blanks only: `\s*` would run on through the newlines of an empty body and shift it.
internal val WRAPPED_HEADER_LINE = Regex("""(?m)^<!--\s*(?:rb1|kb1)\s+.*?-->[ \t]*$""")
private val LEGACY_MARKER = Regex("""^<!--\s*(?:kb\s+|(?:kitbash|ruleblend):begin\b)""")
private val HASH = Regex("""^[0-9a-f]{8}$""")
private val BLOCK = Regex("""^(\S+)@(\d+)(?::([^:\s]+))?(?::([^:\s]+))?$""")

/** Default notice written above a partial wrapped run. */
const val PARTIAL_NOTICE: String = "<!-- Ruleblend-managed. Do not edit below; changes are overwritten. -->"
internal const val LEGACY_PARTIAL_NOTICE: String = "<!-- Kitbash-managed. Do not edit below; changes are overwritten. -->"

internal fun hasPartialNotice(text: String): Boolean =
    PARTIAL_NOTICE in text || LEGACY_PARTIAL_NOTICE in text

internal fun findWrappedTerminator(text: String, format: WrappedRunFormat, startIndex: Int): MatchResult? =
    Regex("(?m)^<!--[ \\t]*${Regex.escape(format.terminator)}[ \\t]*-->[ \\t]*(?:\\r?\\n|$)").find(text, startIndex)

/** Hash of the exact rendered run body, normalized to LF line endings. */
fun hashRun(body: String): String = hashContent(body.replace("\r\n", "\n"))

/** Renders a manifest line. The optional partial-mode notice is not part of this line. */
fun renderWrappedRunHeader(header: WrappedRunHeader): String {
    val ownership = if (header.mode == TargetOwnershipMode.OWNED) "$OWNED " else ""
    val blocks = header.blocks.joinToString(" ") { block ->
        "${block.id}@${block.version}" + block.length?.let { ":$it" }.orEmpty() +
            (block.origin ?: block.group?.let { "g=$it" })?.let { ":$it" }.orEmpty()
    }
    val notice = if (header.mode == TargetOwnershipMode.OWNED && header.notice) OWNED_NOTICE else ""
    return "<!-- ${header.format.marker} $ownership${header.hash}" + if (blocks.isEmpty()) "" else " $blocks" + "$notice -->"
}

/** Parses an rb1 or legacy kb1 manifest line, or returns null for another format. */
fun parseWrappedRunHeader(line: String): WrappedRunHeader? {
    val match = WRAPPED_HEADER.matchEntire(line) ?: return null
    val format = WrappedRunFormat.entries.firstOrNull { it.marker == match.groupValues[1] } ?: return null
    val tokens = match.groupValues[2].trim().split(Regex("\\s+"))
    if (tokens.isEmpty()) return null

    var index = 0
    val mode = if (tokens.getOrNull(index) == OWNED) {
        index++
        TargetOwnershipMode.OWNED
    } else TargetOwnershipMode.PARTIAL
    val hash = tokens.getOrNull(index++) ?: return null
    if (!HASH.matches(hash)) return null

    val remaining = tokens.drop(index)
    val hasNotice = mode == TargetOwnershipMode.OWNED &&
        remaining.takeLast(7).joinToString(" ") in OWNED_NOTICE_SUFFIXES
    val blockTokens = if (hasNotice) {
        remaining.dropLast(7)
    } else remaining
    val blocks = blockTokens.map { token ->
        val block = BLOCK.matchEntire(token) ?: return null
        val firstSuffix = block.groupValues[3].takeIf { it.isNotEmpty() }
        val secondSuffix = block.groupValues[4].takeIf { it.isNotEmpty() }
        val numericLength = firstSuffix?.all(Char::isDigit) == true
        val length = if (numericLength) requireNotNull(firstSuffix).toIntOrNull() ?: return null else null
        if (!numericLength && secondSuffix != null) return null
        val originToken = if (numericLength) secondSuffix else firstSuffix
        val origin = originToken?.let(::parseOrigin)
        if (originToken != null && origin == null) return null
        WrappedRunBlock(
            id = block.groupValues[1],
            version = block.groupValues[2].toIntOrNull() ?: return null,
            length = length,
            group = origin?.takeIf { it.startsWith("g=") }?.removePrefix("g="),
            origin = origin,
        )
    }
    return WrappedRunHeader(mode, hash, blocks, notice = hasNotice || mode == TargetOwnershipMode.PARTIAL, format = format)
}

/** Normalizes a tag from a manifest; a bare legacy value is a group origin. */
private fun parseOrigin(token: String): String? = when {
    token.startsWith("g=") && token.length > 2 -> token
    token.startsWith("p=") && token.length > 2 -> token
    '=' !in token -> "g=$token"
    else -> null
}

/** Detects an ownership mode solely from target-file text. */
fun sniffOwnershipMode(text: String): TargetOwnershipMode {
    val normalized = text.replace("\r\n", "\n")
    normalized.lineSequence().firstNotNullOfOrNull(::parseWrappedRunHeader)?.let { return it.mode }
    return if (normalized.lineSequence().any { LEGACY_MARKER.containsMatchIn(it) }) TargetOwnershipMode.LEGACY else TargetOwnershipMode.NONE
}
