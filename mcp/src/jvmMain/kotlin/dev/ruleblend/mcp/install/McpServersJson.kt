package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure edits of any config whose top level holds an `mcpServers` object — Claude Code's
 * `~/.claude.json` and a project's `.mcp.json` share the shape. Everything outside the edited
 * entry is preserved structurally.
 */
object McpServersJson {

    private const val KEY = "mcpServers"

    private val json = Json { prettyPrint = true }

    /** Names in the top-level MCP area only; malformed JSON is simply not enumerable. */
    fun names(text: String): List<String> = runCatching {
        (parseRoot(text)[KEY] as? JsonObject)?.keys?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /** Native text of one entry, including an unknown shape that cannot be parsed as a config. */
    fun entryText(text: String, name: String): String? = runCatching {
        (parseRoot(text)[KEY] as? JsonObject)?.get(name)?.let { entry ->
            json.encodeToString(JsonElement.serializer(), entry)
        }
    }.getOrNull()

    fun upsert(text: String, name: String, entry: JsonObject): String {
        val root = parseRoot(text)
        val servers = root[KEY] as? JsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(root + (KEY to JsonObject(servers + (name to entry))))
        return json.encodeToString(JsonObject.serializer(), updated) + "\n"
    }

    fun remove(text: String, name: String): String {
        val root = parseRoot(text)
        val servers = root[KEY] as? JsonObject ?: return text
        if (name !in servers) return text
        val updated = JsonObject(root + (KEY to JsonObject(servers - name)))
        return json.encodeToString(JsonObject.serializer(), updated) + "\n"
    }

    fun read(text: String, name: String): JsonObject? =
        (parseRoot(text)[KEY] as? JsonObject)?.get(name) as? JsonObject

    /** Entry with an explicit `type` — valid in both the global and the project file. */
    fun renderEntry(config: McpServerConfig): JsonObject = when (config) {
        is McpServerConfig.Stdio -> buildJsonObject {
            put("type", "stdio")
            put("command", config.command)
            if (config.args.isNotEmpty()) putJsonArray("args") { config.args.forEach { add(JsonPrimitive(it)) } }
            if (config.env.isNotEmpty()) putJsonObject("env") { config.env.forEach { (k, v) -> put(k, v) } }
        }
        is McpServerConfig.Http -> buildJsonObject {
            put("type", "http")
            put("url", config.url)
            if (config.headers.isNotEmpty()) putJsonObject("headers") { config.headers.forEach { (k, v) -> put(k, v) } }
        }
    }

    /** Inverse of [renderEntry] for status comparison; null when the shape is not comparable. */
    fun parseEntry(entry: JsonObject): McpServerConfig? {
        val type = entry["type"]?.jsonPrimitive?.content
        return when {
            type == "http" || type == "sse" || (type == null && entry["url"] != null) -> {
                val url = entry["url"]?.jsonPrimitive?.content ?: return null
                McpServerConfig.Http(url = url, headers = stringMap(entry["headers"]))
            }
            type == "stdio" || type == null -> {
                val command = entry["command"]?.jsonPrimitive?.content ?: return null
                val args = (entry["args"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
                McpServerConfig.Stdio(command = command, args = args, env = stringMap(entry["env"]))
            }
            else -> null
        }
    }

    private fun stringMap(element: JsonElement?): Map<String, String> =
        (element as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    private fun parseRoot(text: String): JsonObject =
        if (text.isBlank()) JsonObject(emptyMap())
        else Json.parseToJsonElement(text).jsonObject
}
