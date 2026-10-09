package dev.ruleblend.core.integration

/** LF text for manifest lengths and hashes, with offsets translated back before editing the source. */
internal class NormalizedText(private val source: String) {
    val text: String = source.replace("\r\n", "\n")

    fun sourceOffset(offset: Int): Int {
        require(offset in 0..text.length)
        var original = 0
        repeat(offset) {
            if (source[original] == '\r' && source.getOrNull(original + 1) == '\n') original++
            original++
        }
        return original
    }
}
