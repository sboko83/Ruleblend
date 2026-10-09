package dev.ruleblend.core.integration

/**
 * The string-valued subset that can round-trip through a subagent variant. Unsupported TOML is
 * rejected as a whole: accepting only the lines we recognise would silently remove native fields.
 */
internal fun parseSubagentToml(source: String): LinkedHashMap<String, String> =
    SubagentTomlReader(source.replace("\r\n", "\n")).read()

private class SubagentTomlReader(private val text: String) {
    private var position = 0

    fun read(): LinkedHashMap<String, String> {
        val values = linkedMapOf<String, String>()
        var instructionsTable = false
        while (true) {
            skipSpaceAndComments()
            if (position == text.length) return values
            if (text[position] == '[') {
                require(!instructionsTable && text.startsWith("[instructions]", position)) {
                    "Only the instructions table is supported"
                }
                instructionsTable = true
                position += "[instructions]".length
                finishLine()
                continue
            }
            val start = position
            while (position < text.length && text[position] in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-") position++
            require(position > start) { "Expected a TOML field" }
            val nativeKey = text.substring(start, position)
            require(!instructionsTable || nativeKey == "text") { "Unsupported instructions field: $nativeKey" }
            val key = if (instructionsTable) "developer_instructions" else nativeKey
            horizontalSpace()
            require(position < text.length && text[position++] == '=') { "Expected '=' after $nativeKey" }
            horizontalSpace()
            require(key !in values) { "Duplicate TOML field: $key" }
            values[key] = string()
            finishLine()
        }
    }

    private fun string(): String {
        require(position < text.length && text[position] in "\"'") { "Only string-valued TOML fields are supported" }
        val quote = text[position]
        val delimiter = quote.toString().repeat(3)
        val multiline = text.startsWith(delimiter, position)
        position += if (multiline) 3 else 1
        if (multiline && position < text.length && text[position] == '\n') position++
        val result = StringBuilder()
        while (position < text.length) {
            val char = text[position++]
            if (char == quote) {
                if (!multiline) return result.toString()
                if (text.startsWith(quote.toString().repeat(2), position)) {
                    position += 2
                    var extra = 0
                    while (position < text.length && text[position] == quote && extra < 2) {
                        result.append(quote)
                        position++
                        extra++
                    }
                    return result.toString()
                }
            }
            if (char == '\\' && quote == '"') {
                require(position < text.length) { "Unterminated TOML escape" }
                if (multiline) {
                    val continuation = position
                    horizontalSpace()
                    if (position < text.length && text[position] == '\n') {
                        while (position < text.length && text[position] in " \t\n") position++
                        continue
                    }
                    position = continuation
                }
                when (val escaped = text[position++]) {
                    '"', '\\' -> result.append(escaped)
                    'b' -> result.append('\b')
                    't' -> result.append('\t')
                    'n' -> result.append('\n')
                    'f' -> result.append('\u000C')
                    'r' -> result.append('\r')
                    'u', 'U' -> {
                        val count = if (escaped == 'u') 4 else 8
                        require(position + count <= text.length) { "Incomplete Unicode escape" }
                        val digits = text.substring(position, position + count)
                        require(digits.all { it in "0123456789abcdefABCDEF" }) { "Invalid Unicode escape" }
                        val code = digits.toLong(16)
                        require(code <= 0x10FFFF && code !in 0xD800..0xDFFF) { "Invalid Unicode scalar" }
                        result.appendCodePoint(code.toInt())
                        position += count
                    }
                    else -> error("Unsupported TOML escape: $escaped")
                }
            } else {
                require(char == '\t' || (multiline && char == '\n') || (char.code >= 0x20 && char.code != 0x7F)) {
                    "Invalid control character in TOML string"
                }
                result.append(char)
            }
        }
        error("Unterminated TOML string")
    }

    private fun horizontalSpace() {
        while (position < text.length && text[position] in " \t") position++
    }

    private fun finishLine() {
        horizontalSpace()
        if (position < text.length && text[position] == '#') {
            while (position < text.length && text[position] != '\n') position++
        }
        require(position == text.length || text[position++] == '\n') { "Unexpected text after TOML value" }
    }

    private fun skipSpaceAndComments() {
        while (position < text.length) {
            if (text[position] in " \t\n") position++
            else if (text[position] == '#') {
                while (position < text.length && text[position] != '\n') position++
            } else return
        }
    }
}
