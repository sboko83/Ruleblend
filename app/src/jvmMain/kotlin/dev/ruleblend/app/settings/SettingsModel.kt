package dev.ruleblend.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.build.BuildInfo
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.integration.AssistantPaths
import dev.ruleblend.core.config.ColumnWidths
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.config.SourceCheckMode
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.TranslationConfig
import dev.ruleblend.core.translate.TranslationStatus
import dev.ruleblend.core.translate.Translator
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryHistory
import dev.ruleblend.core.storage.RemoteSyncResult
import dev.ruleblend.core.storage.RemoteSyncStatus
import dev.ruleblend.core.storage.UnrelatedLibraryHistories
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.mcp.BundledIntegration
import dev.ruleblend.mcp.McpConnector
import dev.ruleblend.mcp.McpLaunch
import dev.ruleblend.mcp.McpRegistration
import dev.ruleblend.mcp.skill.SkillInstaller
import dev.ruleblend.mcp.skill.SkillStatus
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How far one agent is from using Ruleblend: connecting writes both the MCP server entry and — where
 * the agent supports skills — the bundled skill, so the row reports the pair, not either half.
 */
enum class ConnectState {
    CONNECTED,
    NOT_CONNECTED,

    /** The MCP entry points at a binary that has moved. */
    STALE,

    /** Connected, but the installed skill is from an older app version. */
    SKILL_OUTDATED,

    /** Connected, but the MCP entry was written by an app that shipped an older one. */
    MCP_OUTDATED,

    /** A foreign skill holds the `ruleblend` name; the user has to clear it. */
    SKILL_FOREIGN,

    /** Another server already owns the `ruleblend` MCP name. */
    MCP_FOREIGN,
}

/**
 * UI state for Settings: everything machine-local in `config.json` plus the two things Settings
 * reads but never owns — the library repository's history and each agent's MCP connection.
 * Every change is saved as it is made; there is no Apply.
 */
class SettingsModel(
    private val configStore: ConfigStore,
    private val defaultLibraryPath: Path,
    private val libraryGit: LibraryGit,
    /** The library this process opened at launch; a different configured path needs a restart. */
    private val activeLibraryPath: Path = defaultLibraryPath,
    /** Where `config.json` lives, for About and diagnostics; `null` in tests. */
    val configPath: Path? = null,
    val agents: List<AgentAdapter>,
    /** MCP registration per agent; empty when no connectors are wired (tests). */
    val connectors: List<McpConnector> = emptyList(),
    /** Bundled-skill installers for the supported assistants. */
    private val skillInstallers: List<SkillInstaller> = emptyList(),
    /** What agents have to launch — the installed binary or the dev wrapper; `null` in tests. */
    val launch: McpLaunch? = null,
    val launchFailure: String? = null,
    /** Applies appearance immediately at the app root after the setting is persisted. */
    private val onThemeModeChanged: (ThemeMode) -> Unit = {},
    /** Backend of the editor's translation panel; `null` where translation is unavailable (tests). */
    private val translator: Translator? = null,
) {
    /** The one reader of what agents hold of ours; Home asks the same object its own questions. */
    private val integration = BundledIntegration(connectors, skillInstallers, launch?.command)

    var libraryPath by mutableStateOf(defaultLibraryPath)
        private set

    /** The configured path differs from the one in use: only a restart switches libraries. */
    val libraryPathChanged: Boolean
        get() = libraryPath != activeLibraryPath

    /** Commit count and age of the library repository; `null` until loaded or when unreadable. */
    var libraryHistory by mutableStateOf<LibraryHistory?>(null)
        private set

    /** Kept until the user explicitly imports the foreign history or replaces this library. */
    var unrelatedHistories by mutableStateOf(false)
        private set

    var language by mutableStateOf(Language.EN)
        private set

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    var groupsExpandedByDefault by mutableStateOf(true)
        private set

    var sourceCheck by mutableStateOf(SourceCheckMode.MANUAL)
        private set

    var placeLibraryExpandedByDefault by mutableStateOf(true)
        private set

    var placeRulesExpandedByDefault by mutableStateOf(false)
        private set

    var placeSkillsExpandedByDefault by mutableStateOf(false)
        private set

    var placeSubagentsExpandedByDefault by mutableStateOf(false)
        private set

    var placeMcpExpandedByDefault by mutableStateOf(false)
        private set

    var hiddenAgents by mutableStateOf(emptySet<String>())
        private set

    var nameFormat by mutableStateOf(NameFormat.KEBAB)
        private set

    var lineEnding by mutableStateOf(LineEnding.LF)
        private set
    var lineEndingBusy by mutableStateOf(false)
        private set
    var lineEndingFailure by mutableStateOf<String?>(null)
        private set

    var remoteUrl by mutableStateOf("")
        private set

    var remoteBranch by mutableStateOf("main")
        private set

    var remoteAutomatic by mutableStateOf(true)
        private set

    var remoteConfigured by mutableStateOf(false)
        private set

    var remoteSyncStatus by mutableStateOf<RemoteSyncStatus?>(null)
        private set

    /** Full result behind the short Library status and the About diagnostics. */
    var lastRemoteSync by mutableStateOf<RemoteSyncResult?>(null)
        private set

    /** When the last push finished, epoch millis; `null` before the first push. */
    var remoteSyncAtMillis by mutableStateOf<Long?>(null)
        private set

    var remoteSyncFailure by mutableStateOf<String?>(null)
        private set

    var remoteSyncing by mutableStateOf(false)
        private set

    /**
     * The remote row shows a form while there is nothing configured or the user asked to edit, and
     * the connected summary otherwise. Saving closes the form; Remove reopens it empty.
     */
    var remoteEditing by mutableStateOf(true)
        private set

    var useExternalEditor by mutableStateOf(false)
        private set

    var externalEditorPath by mutableStateOf<Path?>(null)
        private set

    /** Terminal application an agent CLI opens in; `null` uses the system default for `.command`. */
    var terminalAppPath by mutableStateOf<Path?>(null)
        private set

    /** Extra CLI arguments per agent id, as typed. */
    var agentCliArguments by mutableStateOf(emptyMap<String, String>())
        private set

    /** Language pair and quality of the editor's translation panel. */
    var translation by mutableStateOf(TranslationConfig())
        private set

    /** Whether this machine can translate at all; the row is hidden when it cannot. */
    val translationAvailable: Boolean get() = translator?.available == true

    /** Language codes the system offers, filled in on load; empty until the helper answers. */
    var translationLanguages by mutableStateOf(emptyList<String>())
        private set

    /** Whether the selected pair is ready, needs a download, or is not offered at all. */
    var translationStatus by mutableStateOf<TranslationStatus?>(null)
        private set

    /** What Ruleblend installed into every available assistant, and whether it is still current. */
    var integrationStates by mutableStateOf(emptyList<AgentIntegrationState>())
        private set

    /** Connection state per connector [McpConnector.agentId]; only available agents appear. */
    val connectStates: Map<String, ConnectState>
        get() = integrationStates.mapNotNull { state -> state.mcp?.let { state.agentId to connectState(state) } }.toMap()

    /** Bundled-skill state per available assistant, including assistants without MCP. */
    val skillStates: Map<String, SkillStatus>
        get() = integrationStates.mapNotNull { state -> state.skill?.let { state.agentId to it.status } }.toMap()

    /** Agents whose copy an app update left behind; what "Update everywhere" acts on. */
    val outdatedAgentIds: List<String> get() = integrationStates.filter { it.outdated }.map { it.agentId }

    /** Message of the last failed Connect action, until the screen shows and clears it. */
    var connectFailure by mutableStateOf<String?>(null)
        private set

    /** One read of everything Settings shows; assembled off the UI thread. */
    private class Loaded(
        val config: AppConfig,
        val history: LibraryHistory?,
        val sync: RemoteSyncResult?,
        val integrationStates: List<AgentIntegrationState>,
    )

    /**
     * Re-reads the config, the library repository and every connector. All of it is disk and process
     * work, so it runs on [Dispatchers.IO] and lands as one snapshot on the caller's thread.
     */
    suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            Loaded(
                config = configStore.load(),
                // A broken repository must not take Settings down with it: the row simply shows no numbers.
                history = runCatching { libraryGit.history() }.getOrNull(),
                sync = libraryGit.lastRemoteSync,
                integrationStates = integration.states(),
            )
        }
        val config = loaded.config
        libraryPath = config.libraryPath?.let(Path::of) ?: defaultLibraryPath
        language = Language.fromCode(config.language)
        themeMode = config.themeMode
        groupsExpandedByDefault = config.groupsExpandedByDefault
        sourceCheck = config.sourceCheck
        placeLibraryExpandedByDefault = config.placeLibraryExpandedByDefault
        placeRulesExpandedByDefault = config.placeRulesExpandedByDefault
        placeSkillsExpandedByDefault = config.placeSkillsExpandedByDefault
        placeSubagentsExpandedByDefault = config.placeSubagentsExpandedByDefault
        placeMcpExpandedByDefault = config.placeMcpExpandedByDefault
        hiddenAgents = config.hiddenAgents.toSet()
        nameFormat = config.nameFormat
        lineEnding = config.lineEnding
        loadRemote(config.remoteGit)
        remoteEditing = config.remoteGit == null
        applySync(loaded.sync)
        useExternalEditor = config.useExternalEditor
        externalEditorPath = config.externalEditorPath?.let(Path::of)
        terminalAppPath = config.terminalAppPath?.let(Path::of)
        agentCliArguments = config.agentCliArguments
        translation = config.translation
        libraryHistory = loaded.history
        integrationStates = loaded.integrationStates
    }

    private fun loadRemote(config: RemoteGitConfig?) {
        remoteUrl = config?.url.orEmpty()
        remoteBranch = config?.branch ?: "main"
        remoteAutomatic = config?.automatic ?: true
        remoteConfigured = config != null
    }

    private fun applySync(result: RemoteSyncResult?) {
        lastRemoteSync = result
        remoteSyncStatus = result?.status
        remoteSyncAtMillis = result?.atEpochMillis
        remoteSyncFailure = result?.message
    }

    /** Refreshes the in-memory result after an automatic sync completed outside Settings. */
    fun refreshRemoteSync() = applySync(libraryGit.lastRemoteSync)

    /** Home and Coverage can change visibility while Settings is closed. */
    suspend fun refreshAgentVisibility() {
        hiddenAgents = withContext(Dispatchers.IO) { configStore.load().hiddenAgents.toSet() }
    }

    fun changeRemoteUrl(value: String) {
        remoteUrl = value
        remoteSyncFailure = null
    }

    fun changeRemoteBranch(value: String) {
        remoteBranch = value
        remoteSyncFailure = null
    }

    fun changeRemoteAutomatic(value: Boolean) {
        remoteAutomatic = value
    }

    /** Saves the target and immediately verifies it with a push. */
    suspend fun saveAndSyncRemote() {
        remoteSyncing = true
        try {
            val config = libraryGit.validateRemoteConfig(
                RemoteGitConfig(remoteUrl, remoteBranch, remoteAutomatic),
            )
            withContext(Dispatchers.IO) { configStore.update { it.copy(remoteGit = config) } }
            remoteConfigured = true
            remoteEditing = false
            remoteUrl = config.url
            remoteBranch = config.branch
            push(config)
        } catch (_: UnrelatedLibraryHistories) {
            unrelatedHistories = true
        } catch (e: Exception) {
            failSync(e)
        } finally {
            remoteSyncing = false
        }
    }

    /** Pushes the configured remote again — the connected row's Sync now. */
    suspend fun syncNow() {
        val config = configStore.load().remoteGit ?: return
        remoteSyncing = true
        try {
            push(config)
        } catch (_: UnrelatedLibraryHistories) {
            unrelatedHistories = true
        } catch (e: Exception) {
            failSync(e)
        } finally {
            remoteSyncing = false
        }
    }

    private suspend fun push(config: RemoteGitConfig, allowUnrelatedHistories: Boolean = false) {
        applySync(withContext(Dispatchers.IO) { libraryGit.syncRemote(config, allowUnrelatedHistories) })
        remoteSyncFailure = null
    }

    /** Merges a foreign branch with the ordinary object conflict policy, but only after consent. */
    suspend fun mergeUnrelatedHistories() {
        val config = configStore.load().remoteGit ?: return
        remoteSyncing = true
        try {
            push(config, allowUnrelatedHistories = true)
            unrelatedHistories = false
        } catch (e: Exception) {
            failSync(e)
        } finally {
            remoteSyncing = false
        }
    }

    /** [export] must complete before the atomic library-root replacement begins. */
    suspend fun replaceUnrelatedLibrary(export: () -> Unit) {
        val config = configStore.load().remoteGit ?: return
        remoteSyncing = true
        try {
            withContext(Dispatchers.IO) {
                export()
                libraryGit.replaceWithRemote(config)
            }
            applySync(RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE))
            unrelatedHistories = false
        } catch (e: Exception) {
            failSync(e)
        } finally {
            remoteSyncing = false
        }
    }

    fun dismissUnrelatedHistories() {
        unrelatedHistories = false
    }

    private fun failSync(e: Exception) {
        val message = e.message ?: e.toString()
        applySync(
            libraryGit.lastRemoteSync?.takeIf { it.status == RemoteSyncStatus.FAILED }
                ?: RemoteSyncResult(RemoteSyncStatus.FAILED, message = message, failed = message),
        )
    }

    /** Reopens the form over the saved target; nothing is written until Save and sync. */
    fun editRemote() {
        remoteEditing = true
    }

    /** Drops unsaved edits and shows the saved target again. */
    fun cancelRemoteEdit() {
        loadRemote(configStore.load().remoteGit)
        remoteSyncFailure = null
        remoteEditing = !remoteConfigured
    }

    fun removeRemote() {
        configStore.update { it.copy(remoteGit = null) }
        loadRemote(null)
        remoteEditing = true
        applySync(null)
    }

    fun changeExternalEditor(value: Boolean) {
        configStore.update { it.copy(useExternalEditor = value) }
        useExternalEditor = value
    }

    fun changeExternalEditorPath(value: Path?) {
        configStore.update { it.copy(externalEditorPath = value?.toAbsolutePath()?.normalize()?.toString()) }
        externalEditorPath = value?.toAbsolutePath()?.normalize()
    }

    /** Terminal an agent CLI is launched in; `null` leaves it to whatever owns `.command` files. */
    fun changeTerminalApp(value: Path?) {
        configStore.update { it.copy(terminalAppPath = value?.toAbsolutePath()?.normalize()?.toString()) }
        terminalAppPath = value?.toAbsolutePath()?.normalize()
    }

    /**
     * Extra arguments for one agent's CLI. An empty line is removed rather than stored: the absence
     * of a setting and a setting that is blank are the same thing, and only one of them survives an
     * export of the config to a fresh machine as "nothing was configured here".
     */
    fun changeAgentCliArguments(agentId: String, value: String) {
        val updated = configStore.update {
            val arguments = if (value.isBlank()) it.agentCliArguments - agentId else it.agentCliArguments + (agentId to value)
            it.copy(agentCliArguments = arguments)
        }
        agentCliArguments = updated.agentCliArguments
    }

    fun changeTranslation(config: TranslationConfig) {
        configStore.update { it.copy(translation = config) }
        translation = config
        translationStatus = null
    }

    /**
     * Asks the helper what it can do. Kept out of [load] because it spawns a process: Settings has
     * to open instantly, and an unreachable helper must not delay it.
     */
    suspend fun refreshTranslation() {
        val backend = translator?.takeIf { it.available } ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                translationLanguages = backend.languages(translation.quality)
                translationStatus = backend.status(
                    translation.sourceLanguage,
                    translation.targetLanguage,
                    translation.quality,
                )
            }
        }
    }

    /** Registers the MCP server and installs the skill; both are what "connected" means. */
    suspend fun connect(connector: McpConnector) {
        val binary = launch?.command ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                connector.register(binary)
                skillInstallerFor(connector.agentId)?.install()
            }
        }.onFailure { connectFailure = it.message ?: it.toString() }
        refreshConnectStates()
    }

    /** Removes the MCP registration and any Ruleblend-owned skill for this agent. */
    suspend fun disconnect(connector: McpConnector) {
        withContext(Dispatchers.IO) {
            runCatching {
                connector.unregister()
                skillInstallerFor(connector.agentId)?.uninstall()
            }
        }.onFailure { connectFailure = it.message ?: it.toString() }
        refreshConnectStates()
    }

    /** Rewrites whatever an app update left behind for one assistant. */
    suspend fun updateAgent(agentId: String) {
        withContext(Dispatchers.IO) { runCatching { integration.update(agentId) } }
            .onFailure { connectFailure = it.message ?: it.toString() }
        refreshConnectStates()
    }

    /** The same for every assistant at once; a foreign skill is reported, never overwritten. */
    suspend fun updateAllIntegrations() {
        withContext(Dispatchers.IO) { runCatching { integration.updateAll() } }
            .onFailure { connectFailure = it.message ?: it.toString() }
        refreshConnectStates()
    }

    fun clearConnectFailure() {
        connectFailure = null
    }

    private fun skillInstallerFor(agentId: String): SkillInstaller? =
        skillInstallers.find { it.agentId == agentId && it.isAvailable() }

    private suspend fun refreshConnectStates() {
        integrationStates = withContext(Dispatchers.IO) { integration.states() }
    }

    private fun connectState(state: AgentIntegrationState): ConnectState {
        val registration = state.mcp?.registration ?: return ConnectState.NOT_CONNECTED
        val skill = state.skill
        return when {
            registration == McpRegistration.FOREIGN -> ConnectState.MCP_FOREIGN
            skill?.status == SkillStatus.FOREIGN -> ConnectState.SKILL_FOREIGN
            registration == McpRegistration.STALE_PATH -> ConnectState.STALE
            registration == McpRegistration.NOT_REGISTERED -> ConnectState.NOT_CONNECTED
            skill == null -> if (registration == McpRegistration.OUTDATED) ConnectState.MCP_OUTDATED else ConnectState.CONNECTED
            // An outdated skill leads: it is the longer of the two texts to fall behind, and the
            // one action beside the row rewrites whichever of the pair is stale anyway.
            skill.status == SkillStatus.OUTDATED -> ConnectState.SKILL_OUTDATED
            skill.status == SkillStatus.NOT_INSTALLED -> ConnectState.NOT_CONNECTED
            registration == McpRegistration.OUTDATED -> ConnectState.MCP_OUTDATED
            else -> ConnectState.CONNECTED
        }
    }

    fun changeLibraryPath(path: Path) {
        configStore.update { it.copy(libraryPath = path.toString()) }
        libraryPath = path
    }

    /** Points the config back at the library this process is using, so no restart is pending. */
    fun undoLibraryPathChange() {
        val stored = activeLibraryPath.takeIf { it != defaultLibraryPath }?.toString()
        configStore.update { it.copy(libraryPath = stored) }
        libraryPath = activeLibraryPath
    }

    /** Forgets every persisted pane width; the screens pick the defaults up on their next load. */
    fun resetColumnWidths() {
        configStore.update { it.copy(columnWidths = ColumnWidths()) }
    }

    /** Clears the recently opened places; pins and project sets are untouched. */
    fun clearRecentPlaces() {
        configStore.update { it.copy(places = it.places.copy(recent = emptyList())) }
    }

    /**
     * What a bug report needs and nothing personal beyond paths: version, platform, where state
     * lives, which agents were found and how each is connected.
     */
    fun diagnostics(): String = buildString {
        appendLine("Ruleblend ${BuildInfo.VERSION} (build ${BuildInfo.BUILD})")
        appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
        appendLine("JVM: ${System.getProperty("java.vm.version")}")
        appendLine("Config: ${configPath ?: "-"}")
        appendLine("Library: $libraryPath")
        appendLine("Launch: ${launch?.shellCommand() ?: "-"}")
        launchFailure?.let { appendLine("MCP launch error: $it") }
        lastRemoteSync?.let { sync ->
            appendLine(
                "Remote sync: ${sync.status}; behind=${sync.behind}; ahead=${sync.ahead}; " +
                    "merged=${sync.merged}; conflicts=${sync.conflicts.joinToString { it.kind.name.lowercase() + ":" + it.id }}; " +
                    "restored=${sync.conflicts.count { it.restored }}; failure=${sync.failed ?: "-"}",
            )
        } ?: appendLine("Remote sync: not run")
        agents.forEach { agent ->
            val parts = mutableListOf(if (agent.isAvailable()) "detected" else "not detected")
            if (agent.id in hiddenAgents) parts += "hidden"
            connectStates[agent.id]?.let { parts += "mcp ${it.name.lowercase()}" }
            appendLine("Agent ${agent.id}: ${parts.joinToString(", ")}")
        }
        AssistantPaths.forHome().rejectedOverrides.forEach { appendLine("Assistant home: $it") }
    }

    fun changeLanguage(language: Language) {
        configStore.update { it.copy(language = language.code) }
        this.language = language
    }

    fun changeThemeMode(mode: ThemeMode) {
        configStore.update { it.copy(themeMode = mode) }
        themeMode = mode
        onThemeModeChanged(mode)
    }

    fun changeGroupsExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(groupsExpandedByDefault = value) }
        groupsExpandedByDefault = value
    }

    fun changeSourceCheck(mode: SourceCheckMode) {
        configStore.update { it.copy(sourceCheck = mode) }
        sourceCheck = mode
    }

    fun changePlaceLibraryExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(placeLibraryExpandedByDefault = value) }
        placeLibraryExpandedByDefault = value
    }

    fun changePlaceRulesExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(placeRulesExpandedByDefault = value) }
        placeRulesExpandedByDefault = value
    }

    fun changePlaceSkillsExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(placeSkillsExpandedByDefault = value) }
        placeSkillsExpandedByDefault = value
    }

    fun changePlaceSubagentsExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(placeSubagentsExpandedByDefault = value) }
        placeSubagentsExpandedByDefault = value
    }

    fun changePlaceMcpExpandedByDefault(value: Boolean) {
        configStore.update { it.copy(placeMcpExpandedByDefault = value) }
        placeMcpExpandedByDefault = value
    }

    fun changeNameFormat(format: NameFormat) {
        configStore.update { it.copy(nameFormat = format) }
        nameFormat = format
    }

    suspend fun changeLineEnding(ending: LineEnding) {
        if (lineEndingBusy || ending == lineEnding) return
        lineEndingBusy = true
        lineEndingFailure = null
        try {
            withContext(Dispatchers.IO) {
                configStore.update { it.copy(lineEnding = ending) }
                libraryGit.configureLineEndings(ending)
            }
            lineEnding = ending
        } catch (failure: Exception) {
            lineEndingFailure = failure.message ?: failure.javaClass.simpleName
            lineEnding = configStore.load().lineEnding
        } finally {
            lineEndingBusy = false
        }
    }

    fun setAgentHidden(agent: AgentAdapter, hidden: Boolean) {
        val updated = configStore.update { config ->
            val agents = if (hidden) (config.hiddenAgents + agent.id).distinct() else config.hiddenAgents - agent.id
            config.copy(hiddenAgents = agents)
        }
        hiddenAgents = updated.hiddenAgents.toSet()
    }
}
