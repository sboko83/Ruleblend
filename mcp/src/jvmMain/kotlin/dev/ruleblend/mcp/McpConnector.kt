package dev.ruleblend.mcp

import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.mcp.install.McpServersJson
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Whether an agent's config already launches this Ruleblend binary as an MCP server. */
enum class McpRegistration {
    NOT_REGISTERED,

    REGISTERED,

    /** The name is occupied by an entry that Ruleblend did not write. */
    FOREIGN,

    /** An entry exists but points at another binary — e.g. the app was moved. Re-register. */
    STALE_PATH,

    /** Ours, at the right binary, but written by an app that shipped an older entry. Re-register. */
    OUTDATED,
}

/**
 * Writes the `ruleblend` MCP server entry into one agent's own config format. Implementations edit
 * the config directly instead of shelling out to the agent's CLI: a GUI app on macOS gets a minimal
 * `PATH`, so finding the CLI is the fragile path, not the file edit.
 *
 * The agent may rewrite its config concurrently; the edit is atomic (temp + rename), so the worst
 * case is a lost entry — re-register by clicking Connect again.
 */
interface McpConnector {
    val agentId: String

    /** True when the agent is present on this machine (its config directory exists). */
    fun isAvailable(): Boolean

    /** Adds or replaces the `ruleblend` server entry launching `[binary] --mcp`. */
    fun register(binary: Path)

    /** Removes the `ruleblend` server entry while preserving the rest of the agent config. */
    fun unregister()

    fun status(binary: Path): McpRegistration

    /**
     * Version stamped into the entry on disk, or `null` when there is no entry of ours. `0` is a
     * registration written before entries carried a version.
     */
    fun installedEntryVersion(): Int?
}

/** Name of the server entry every connector writes. */
const val MCP_SERVER_NAME: String = "ruleblend"

/** What the server is, for the surfaces that list it beside the user's own MCP entries. */
const val MCP_SERVER_DESCRIPTION: String =
    "Ruleblend's own MCP server: the tools an assistant uses to read and change the library " +
        "instead of hand-editing installed files. Connected from Settings, replaced by an app update."

/**
 * Shape of the registration Ruleblend writes: the command line, its arguments and this stamp.
 * Raised by hand whenever an entry a previous build wrote would no longer be what this build
 * writes — not per app release, or every update would report four agents as stale for nothing.
 */
const val MCP_ENTRY_VERSION: Int = 3

/**
 * Where the version is stamped. It rides in the entry's own `env`, which every config format
 * Ruleblend writes reads back, so a registration states its version without a sidecar to lose.
 */
const val MCP_ENTRY_ENV_KEY: String = "RULEBLEND_ENTRY"

/** The `env` map every connector writes into its entry. */
internal fun mcpEntryEnv(): Map<String, String> = mapOf(MCP_ENTRY_ENV_KEY to MCP_ENTRY_VERSION.toString())

/** Version of an entry Ruleblend wrote; one without the stamp predates versioning. */
internal fun McpServerConfig.Stdio.entryVersion(): Int = env[MCP_ENTRY_ENV_KEY]?.toIntOrNull() ?: 0

/**
 * Whether this is a self-registration written by Ruleblend, independent of its launcher path.
 * The marker is part of the entry, so read-only surfaces do not need sidecar state to identify it.
 */
fun McpServerConfig.isRuleblendRegistration(): Boolean =
    this is McpServerConfig.Stdio &&
        args == listOf("--mcp") &&
        env[MCP_ENTRY_ENV_KEY]?.toIntOrNull() != null

/** Also recognizes the unversioned registration shipped before the ownership stamp existed. */
internal fun McpServerConfig.isOwnedRuleblendEntry(): Boolean =
    isRuleblendRegistration() || (this is McpServerConfig.Stdio && args == listOf("--mcp") &&
        command.replace('\\', '/').lowercase().let { path ->
            path.endsWith("/.ruleblend/bin/ruleblend-mcp") ||
                path.endsWith("/ruleblend.app/contents/macos/ruleblend")
        })

internal fun requireWritableRuleblendEntry(entry: McpServerConfig?, present: Boolean) {
    require(!present || entry?.isOwnedRuleblendEntry() == true) {
        "An unrelated MCP server named ruleblend already exists"
    }
}

/**
 * The verdict on an entry that exists, shared by every connector: the four config formats differ in
 * how the entry is read, not in what its contents mean.
 */
internal fun registrationOf(entry: McpServerConfig?, binary: Path): McpRegistration = when {
    entry != null && !entry.isOwnedRuleblendEntry() -> McpRegistration.FOREIGN
    entry !is McpServerConfig.Stdio || entry.command != binary.toString() -> McpRegistration.STALE_PATH
    entry.entryVersion() < MCP_ENTRY_VERSION -> McpRegistration.OUTDATED
    else -> McpRegistration.REGISTERED
}

/** The Ruleblend entry every connector writes, once the launcher path is known. */
fun ruleblendEntry(binary: Path): McpServerConfig.Stdio =
    McpServerConfig.Stdio(command = binary.toString(), args = listOf("--mcp"), env = mcpEntryEnv())

private val entryJson = Json { prettyPrint = true }

/**
 * The entry as canonical JSON, for surfaces that show it rather than write it. [command] stands in
 * for the launcher when a run could not resolve one, so the text still reads as a real entry.
 */
fun ruleblendEntryJson(command: String): String {
    val entry = McpServersJson.renderEntry(
        McpServerConfig.Stdio(command = command, args = listOf("--mcp"), env = mcpEntryEnv()),
    )
    return entryJson.encodeToString(JsonObject.serializer(), entry)
}

/** Server name written before the product rename. */
internal const val LEGACY_MCP_SERVER_NAME: String = "kitbash"

/** Distinguishes our pre-rename registration from an unrelated server reusing the old name. */
internal fun McpServerConfig.Stdio.isLegacyRuleblendRegistration(): Boolean {
    val normalizedCommand = command.replace('\\', '/').lowercase()
    val executable = normalizedCommand.substringAfterLast('/')
    return args == listOf("--mcp") &&
        (executable == "kitbash" || executable == "kitbash-mcp" ||
            "/kitbash.app/contents/macos/kitbash" in normalizedCommand)
}
