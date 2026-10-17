package dev.ruleblend.mcp.install

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** ZCode's native `mcp.servers` area; all unrelated settings survive structural edits. */
object ZCodeMcpJson {
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
        else -> throw IllegalArgumentException("ZCode config '$key' must be an object")
    }

    private fun root(text: String): JsonObject =
        if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
}
