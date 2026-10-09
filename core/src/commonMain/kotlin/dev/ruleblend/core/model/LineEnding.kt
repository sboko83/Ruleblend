package dev.ruleblend.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Machine-local output format; portable text in Git always uses LF. */
@Serializable
enum class LineEnding(val separator: String, val gitValue: String) {
    @SerialName("lf")
    LF("\n", "lf"),
    @SerialName("crlf")
    CRLF("\r\n", "crlf");

    fun apply(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (this == LF) normalized else normalized.replace("\n", separator)
    }
}
