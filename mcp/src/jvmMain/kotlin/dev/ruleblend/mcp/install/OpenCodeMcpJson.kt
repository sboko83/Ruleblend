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
 * OpenCode v2's native `mcp.servers` area of `opencode.json`. `mcp.timeout` is a sibling of
 * `servers`, so every edit rebuilds only the server list; all other settings survive. A sibling
 * `opencode.jsonc` is never parsed or rewritten.
 */
object OpenCodeMcpJson {
    private val json = Json { prettyPrint = true }

    fun names(text: String): List<String> = runCatching { servers(root(text)).keys.toList() }
        .getOrDefault(emptyList())

    fun entryText(text: String, name: String): String? = runCatching {
        servers(root(text))[name]?.let { json.encodeToString(JsonElement.serializer(), it) }
    }.getOrNull()

    fun read(text: String, name: String): JsonObject? = servers(root(text))[name] as? JsonObject

    fun upsert(text: String, name: String, entry: JsonObject): String {
        val root = root(text)
        return encode(root, JsonObject(servers(root) + (name to entry)))
    }

    fun remove(text: String, name: String): String {
        val root = root(text)
        val servers = servers(root)
        if (name !in servers) return text
        return encode(root, JsonObject(servers - name))
    }

    /** A local entry carries its executable and arguments in one array; entries are enabled by default. */
    fun renderEntry(config: McpServerConfig): JsonObject = when (config) {
        is McpServerConfig.Stdio -> buildJsonObject {
            put("type", "local")
            putJsonArray("command") {
                add(JsonPrimitive(config.command))
                config.args.forEach { add(JsonPrimitive(it)) }
            }
            if (config.env.isNotEmpty()) {
                putJsonObject("environment") { config.env.forEach { (key, value) -> put(key, value) } }
            }
        }
        is McpServerConfig.Http -> buildJsonObject {
            put("type", "remote")
            put("url", config.url)
            if (config.headers.isNotEmpty()) {
                putJsonObject("headers") { config.headers.forEach { (key, value) -> put(key, value) } }
            }
        }
    }

    /** Inverse of [renderEntry]; null for a shape Ruleblend cannot compare, such as a v1 entry. */
    fun parseEntry(entry: JsonObject): McpServerConfig? {
        val type = entry["type"]?.jsonPrimitive?.content
        return when {
            type == "remote" || (type == null && entry["url"] != null) -> {
                val url = entry["url"]?.jsonPrimitive?.content ?: return null
                McpServerConfig.Http(url = url, headers = stringMap(entry["headers"]))
            }
            type == "local" || (type == null && entry["command"] is JsonArray) -> {
                val command = (entry["command"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                val executable = command.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                McpServerConfig.Stdio(
                    command = executable,
                    args = command.drop(1),
                    env = stringMap(entry["environment"]),
                )
            }
            else -> null
        }
    }

    private fun stringMap(element: JsonElement?): Map<String, String> =
        (element as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    private fun encode(root: JsonObject, servers: JsonObject): String {
        val mcp = objectAt(root, "mcp")
        val updated = JsonObject(root + ("mcp" to JsonObject(mcp + ("servers" to servers))))
        return json.encodeToString(JsonObject.serializer(), updated) + "\n"
    }

    private fun servers(root: JsonObject): JsonObject = objectAt(objectAt(root, "mcp"), "servers")

    // A malformed section is never treated as empty: replacing it would discard user settings.
    private fun objectAt(parent: JsonObject, key: String): JsonObject = when (val value = parent[key]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> throw IllegalArgumentException("OpenCode config '$key' must be an object")
    }

    private fun root(text: String): JsonObject =
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
}
