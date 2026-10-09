package dev.ruleblend.mcp.install

import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.KimiCodeHome
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Writes MCP server entries into one agent's config format, at global or project scope. */
interface McpAgentInstaller {
    val agentId: String

    /** True when the agent reads its project MCP config only for trusted projects (UI hint). */
    val requiresProjectTrust: Boolean get() = false

    /** False when the agent cannot run this config at all. */
    fun supports(config: McpServerConfig): Boolean

    /** The config file this agent reads for [target]; null when the scope does not apply. */
    fun configFile(target: Target): Path?

    /** Names in this installer's writable MCP area of [text]. */
    fun names(text: String): List<String>

    /** Native text of one named entry, retained even when its config cannot be parsed. */
    fun entryText(text: String, name: String): String?

    /** All entry names at this agent's config address. */
    fun installedNames(target: Target): List<String> =
        configFile(target)?.let { file -> names(FileReadScope.text(file)) }.orEmpty()

    fun install(target: Target, name: String, config: McpServerConfig)

    fun remove(target: Target, name: String)

    /** The entry currently in the config, canonicalized for comparison; null when absent. */
    fun installed(target: Target, name: String): McpServerConfig?
}

/** Global `~/.claude.json`, project `.mcp.json` — both hold a top-level `mcpServers` object. */
class ClaudeCodeMcpInstaller(
    home: Path? = null,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpAgentInstaller {

    override val agentId: String = "claude-code"

    override fun supports(config: McpServerConfig): Boolean = true

    override fun configFile(target: Target): Path? = when (target) {
        is AgentGlobalTarget -> paths.claudeConfig
        is ProjectTarget -> target.dir.resolve(".mcp.json")
    }

    override fun names(text: String): List<String> = McpServersJson.names(text)

    override fun entryText(text: String, name: String): String? = McpServersJson.entryText(text, name)

    override fun install(target: Target, name: String, config: McpServerConfig) {
        val file = configFile(target) ?: return
        coordinator.rewrite(file, missing = "") { text ->
            McpServersJson.upsert(text, name, McpServersJson.renderEntry(config))
        }
    }

    override fun remove(target: Target, name: String) {
        val file = configFile(target) ?: return
        coordinator.rewrite(file) { text -> McpServersJson.remove(text, name).takeIf { it != text } }
    }

    override fun installed(target: Target, name: String): McpServerConfig? {
        val file = configFile(target) ?: return null
        return McpServersJson.read(load(file), name)?.let { McpServersJson.parseEntry(it) }
    }

    private fun load(file: Path): String = FileReadScope.text(file)
}

/**
 * Global `~/.codex/config.toml`, project `.codex/config.toml` (Codex loads the latter only for
 * trusted projects). Codex reads both stdio and streamable HTTP entries from this table.
 */
class CodexMcpInstaller(
    home: Path? = null,
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpAgentInstaller {

    override val agentId: String = "codex"

    override val requiresProjectTrust: Boolean = true

    override fun supports(config: McpServerConfig): Boolean = true

    override fun configFile(target: Target): Path? = when (target) {
        is AgentGlobalTarget -> paths.codex.resolve("config.toml")
        is ProjectTarget -> target.dir.resolve(".codex").resolve("config.toml")
    }

    override fun names(text: String): List<String> = CodexMcpTable.names(text)

    override fun entryText(text: String, name: String): String? = CodexMcpTable.entryText(text, name)

    override fun install(target: Target, name: String, config: McpServerConfig) {
        val file = configFile(target) ?: return
        coordinator.rewrite(file, missing = "") { text -> CodexMcpTable.upsert(text, name, config) }
    }

    override fun remove(target: Target, name: String) {
        val file = configFile(target) ?: return
        coordinator.rewrite(file) { text -> CodexMcpTable.remove(text, name).takeIf { it != text } }
    }

    override fun installed(target: Target, name: String): McpServerConfig? {
        val file = configFile(target) ?: return null
        return CodexMcpTable.read(load(file), name)
    }

    private fun load(file: Path): String = FileReadScope.text(file)
}

/**
 * Kimi Code's global `$KIMI_CODE_HOME/mcp.json` and its native project-local
 * `<cwd>/.kimi-code/mcp.json`. A [ProjectTarget] is the directory Kimi is launched from; the
 * separate project-root `.mcp.json` remains the shared Claude-compatible layer.
 */
class KimiCodeMcpInstaller(
    home: KimiCodeHome = KimiCodeHome.current(),
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpAgentInstaller {
    /** [home] is the operating-system home, see [KimiCodeHome.under]. */
    constructor(home: Path, coordinator: TargetMutationCoordinator = TargetMutationCoordinator()) :
        this(KimiCodeHome.under(home), coordinator)

    private val kimiHome: Path = home.directory

    override val agentId: String = "kimi-code"

    override fun supports(config: McpServerConfig): Boolean = true

    override fun configFile(target: Target): Path = when (target) {
        is AgentGlobalTarget -> kimiHome.resolve("mcp.json")
        is ProjectTarget -> target.dir.resolve(".kimi-code").resolve("mcp.json")
    }

    override fun names(text: String): List<String> = KimiMcpJson.names(text)

    override fun entryText(text: String, name: String): String? = KimiMcpJson.entryText(text, name)

    override fun install(target: Target, name: String, config: McpServerConfig) {
        val file = configFile(target)
        coordinator.rewrite(file, missing = "") { text -> KimiMcpJson.upsert(text, name, config) }
    }

    override fun remove(target: Target, name: String) {
        val file = configFile(target)
        coordinator.rewrite(file) { text -> KimiMcpJson.remove(text, name).takeIf { it != text } }
    }

    override fun installed(target: Target, name: String): McpServerConfig? =
        KimiMcpJson.read(FileReadScope.text(configFile(target)), name)
}

/** ZCode reads the Claude-compatible shared MCP table at global and project scope. */
class ZCodeMcpInstaller(
    home: Path = Path.of(System.getProperty("user.home")),
    coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
) : McpServersJsonInstaller("zcode", home.resolve(".agents").resolve("mcp.json"), coordinator)

/** Pi 1.0+ reads native MCP files; project servers require project trust. */
class PiMcpInstaller(
    home: Path? = null,
    coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : McpServersJsonInstaller("pi", paths.pi.resolve("mcp.json"), coordinator) {
    override val requiresProjectTrust: Boolean = true

    override fun configFile(target: Target): Path = when (target) {
        is AgentGlobalTarget -> super.configFile(target)
        is ProjectTarget -> target.dir.resolve(".pi").resolve("mcp.json")
    }
}

/** A Claude-compatible `mcpServers` table: [globalFile] globally, `.mcp.json` in a project. */
open class McpServersJsonInstaller(
    override val agentId: String,
    private val globalFile: Path,
    private val coordinator: TargetMutationCoordinator,
) : McpAgentInstaller {

    override fun supports(config: McpServerConfig): Boolean = true

    override fun configFile(target: Target): Path = when (target) {
        is AgentGlobalTarget -> globalFile
        is ProjectTarget -> target.dir.resolve(".mcp.json")
    }

    override fun names(text: String): List<String> = McpServersJson.names(text)

    override fun entryText(text: String, name: String): String? = McpServersJson.entryText(text, name)

    override fun install(target: Target, name: String, config: McpServerConfig) {
        val file = configFile(target)
        coordinator.rewrite(file, missing = "") { text ->
            McpServersJson.upsert(text, name, McpServersJson.renderEntry(config))
        }
    }

    override fun remove(target: Target, name: String) {
        val file = configFile(target)
        coordinator.rewrite(file) { text -> McpServersJson.remove(text, name).takeIf { it != text } }
    }

    override fun installed(target: Target, name: String): McpServerConfig? =
        McpServersJson.read(FileReadScope.text(configFile(target)), name)?.let(McpServersJson::parseEntry)
}
