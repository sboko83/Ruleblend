package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig

/**
 * Pure line-based edits of `[mcp_servers.<name>]` tables in a Codex `config.toml`. The named
 * section (up to the next `[` header) is replaced or removed; everything else, comments included,
 * is preserved verbatim — same approach as the ruleblend self-registration connector.
 */
object CodexMcpTable {

    /** Names in Codex's MCP table area. Other TOML sections are deliberately ignored. */
    fun names(text: String): List<String> = text.lines().mapNotNull(::nameOfHeader)

    /** Native text of one table, including an unsupported shape that [read] cannot parse. */
    fun entryText(text: String, name: String): String? {
        val lines = text.lines()
        val range = sectionRange(lines, name) ?: return null
        return lines.subList(range.first, range.last).joinToString("\n")
    }

    fun upsert(text: String, name: String, config: McpServerConfig): String {
        val section = buildList {
            add(header(name))
            when (config) {
                is McpServerConfig.Stdio -> {
                    add("command = ${tomlString(config.command)}")
                    if (config.args.isNotEmpty()) {
                        add("args = [${config.args.joinToString(", ") { tomlString(it) }}]")
                    }
                    if (config.env.isNotEmpty()) {
                        add("env = ${tomlStringMap(config.env)}")
                    }
                }
                is McpServerConfig.Http -> {
                    add("url = ${tomlString(config.url)}")
                    if (config.headers.isNotEmpty()) {
                        add("http_headers = ${tomlStringMap(config.headers)}")
                    }
                }
            }
        }
        val lines = text.lines()
        val range = sectionRange(lines, name)
        val updated = if (range == null) {
            // Append; a table header captures every key after it, so ours must go last anyway.
            lines.dropLastWhile { it.isBlank() } +
                (if (lines.any { it.isNotBlank() }) listOf("") else emptyList()) + section
        } else {
            lines.subList(0, range.first) + section + lines.subList(range.last, lines.size)
        }
        return updated.joinToString("\n") + "\n"
    }

    fun remove(text: String, name: String): String {
        val lines = text.lines()
        val range = sectionRange(lines, name) ?: return text
        val start = lines.subList(0, range.first).dropLastWhile { it.isBlank() }
        val rest = lines.subList(range.last, lines.size).dropWhile { it.isBlank() }
        val updated = if (start.isEmpty() || rest.isEmpty()) start + rest else start + "" + rest
        return if (updated.isEmpty()) "" else updated.joinToString("\n") + "\n"
    }

    /** Parses back only what [upsert] writes; null when the section is absent or unsupported. */
    fun read(text: String, name: String): McpServerConfig? {
        val lines = text.lines()
        val range = sectionRange(lines, name) ?: return null
        // Keys of the entry itself: [upsert] writes no sub-tables, and a hand-written one is a shape
        // this parser does not claim to understand — its keys must not be read as the entry's own.
        val body = lines.subList(range.first + 1, range.last)
            .map { it.trim() }
            .takeWhile { !it.startsWith("[") }
        val url = value(body, "url")?.let { parseTomlString(it) }
        if (url != null) {
            return McpServerConfig.Http(
                url = url,
                headers = value(body, "http_headers")?.let(::parseTomlStringMap) ?: emptyMap(),
            )
        }
        val command = value(body, "command")?.let { parseTomlString(it) } ?: return null
        val args = value(body, "args")
            ?.removeSurrounding("[", "]")
            ?.let { splitTopLevel(it) }
            ?.map { parseTomlString(it.trim()) }
            ?: emptyList()
        val env = value(body, "env")?.let(::parseTomlStringMap) ?: emptyMap()
        return McpServerConfig.Stdio(command = command, args = args, env = env)
    }

    private fun header(name: String) = "[mcp_servers.$name]"

    /**
     * The server a table header names, or `null` for anything else. A dotted tail is a sub-table of
     * a server — `[mcp_servers.foo.env]` holds `foo`'s environment — and naming it would list one
     * server twice, the second time under a name no config entry has.
     */
    private fun nameOfHeader(line: String): String? = line.trim()
        .takeIf { it.startsWith("[mcp_servers.") && it.endsWith("]") }
        ?.removePrefix("[mcp_servers.")
        ?.removeSuffix("]")
        ?.takeIf { it.isNotBlank() && '.' !in it }

    /**
     * Line range of the section: header index until (exclusive) the next table header that is not
     * one of its own sub-tables, or EOF. A sub-table belongs to the server it is written under, so
     * replacing or removing the entry takes it along instead of leaving keys for a server that the
     * file no longer describes.
     */
    private fun sectionRange(lines: List<String>, name: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == header(name) }
        if (start == -1) return null
        val subPrefix = "[mcp_servers.$name."
        val end = lines.drop(start + 1)
            .indexOfFirst { line -> line.trim().let { it.startsWith("[") && !it.startsWith(subPrefix) } }
            .let { if (it == -1) lines.size else start + 1 + it }
        return start..end
    }

    private fun value(body: List<String>, key: String): String? = body
        .firstOrNull { it.startsWith(key) && it.substringAfter(key).trimStart().startsWith("=") }
        ?.substringAfter("=")?.trim()

    /** Splits on commas that sit outside quoted strings. */
    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                escaped -> { current.append(ch); escaped = false }
                ch == '\\' && inString -> { current.append(ch); escaped = true }
                ch == '"' -> { current.append(ch); inString = !inString }
                ch == ',' && !inString -> { parts.add(current.toString()); current.clear() }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }

    /** A TOML basic string: quoted, with backslashes and quotes escaped. */
    fun tomlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun parseTomlString(value: String): String {
        val inner = value.trim().removeSurrounding("\"")
        val out = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length) {
                out.append(inner[i + 1])
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun tomlStringMap(values: Map<String, String>): String =
        "{ ${values.entries.joinToString(", ") { (key, value) -> "${tomlKey(key)} = ${tomlString(value)}" }} }"

    private fun parseTomlStringMap(value: String): Map<String, String> = value
        .removeSurrounding("{", "}")
        .let(::splitTopLevel)
        .filter { it.isNotBlank() }
        .associate { pair ->
            val key = pair.substringBefore("=").trim().removeSurrounding("\"")
            key to parseTomlString(pair.substringAfter("=").trim())
        }

    /** Bare key when possible, quoted otherwise. */
    private fun tomlKey(key: String): String =
        if (key.matches(Regex("[A-Za-z0-9_-]+"))) key else tomlString(key)
}
