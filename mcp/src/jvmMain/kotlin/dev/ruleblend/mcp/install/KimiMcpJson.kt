package dev.ruleblend.mcp.install

import dev.ruleblend.core.model.McpServerConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure edits of Kimi Code's `mcp.json`. Kimi infers transport from `command` or `url`, unlike
 * Claude Code's JSON files, so Ruleblend must not write an extra `type` discriminator here.
 */
object KimiMcpJson {
    fun names(text: String): List<String> = McpServersJson.names(text)

    fun entryText(text: String, name: String): String? = McpServersJson.entryText(text, name)

    fun upsert(text: String, name: String, config: McpServerConfig): String =
        McpServersJson.upsert(text, name, renderEntry(config))

    fun remove(text: String, name: String): String = McpServersJson.remove(text, name)

    fun read(text: String, name: String): McpServerConfig? =
        McpServersJson.read(text, name)?.let(McpServersJson::parseEntry)

    fun renderEntry(config: McpServerConfig): JsonObject = when (config) {
        is McpServerConfig.Stdio -> buildJsonObject {
            put("command", config.command)
            if (config.args.isNotEmpty()) putJsonArray("args") { config.args.forEach { add(JsonPrimitive(it)) } }
            if (config.env.isNotEmpty()) putJsonObject("env") { config.env.forEach { (key, value) -> put(key, value) } }
        }
        is McpServerConfig.Http -> buildJsonObject {
            put("url", config.url)
            if (config.headers.isNotEmpty()) {
                putJsonObject("headers") { config.headers.forEach { (key, value) -> put(key, value) } }
            }
        }
    }
}
