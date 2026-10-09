package dev.ruleblend.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Canonical MCP server definition stored in the body of a [BlockType.MCP] block. The server entry
 * key in agent configs is the block id.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("transport")
sealed interface McpServerConfig {

    /** Local server the agent launches as a child process. */
    @Serializable
    @SerialName("stdio")
    data class Stdio(
        val command: String = "",
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
    ) : McpServerConfig

    /** Remote server reached over streamable HTTP. */
    @Serializable
    @SerialName("http")
    data class Http(
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) : McpServerConfig
}

/** Reserved for Ruleblend's own server registration ([Connect agents] in Settings). */
const val RESERVED_MCP_SERVER_NAME: String = "ruleblend"

/**
 * Owns the block-body format. Serialization is byte-stable: the UI must round-trip through this
 * codec so the repository's version auto-increment fires only on real config changes.
 */
object McpConfigCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun parse(body: String): Result<McpServerConfig> = runCatching {
        json.decodeFromString<McpServerConfig>(body)
    }

    fun serialize(config: McpServerConfig): String = json.encodeToString<McpServerConfig>(config)
}

/** Problems that make a config uninstallable; empty means valid. Keys are message ids for i18n. */
fun McpServerConfig.validationErrors(): List<McpConfigError> = when (this) {
    is McpServerConfig.Stdio -> buildList {
        if (command.isBlank()) add(McpConfigError.COMMAND_REQUIRED)
        if (env.keys.any { it.isBlank() }) add(McpConfigError.BLANK_KEY)
    }
    is McpServerConfig.Http -> buildList {
        if (!url.startsWith("http://") && !url.startsWith("https://")) add(McpConfigError.URL_INVALID)
        if (headers.keys.any { it.isBlank() }) add(McpConfigError.BLANK_KEY)
    }
}

enum class McpConfigError {
    COMMAND_REQUIRED,
    URL_INVALID,
    BLANK_KEY,
}
