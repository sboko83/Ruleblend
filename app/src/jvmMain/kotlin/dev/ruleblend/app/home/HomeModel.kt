package dev.ruleblend.app.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.place.placeId
import dev.ruleblend.app.library.LibraryPlaceUsage
import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.sourceUpdateId
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.config.SourceCheckMode
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.RemoteSyncResult
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.mcp.BundledIntegration
import dev.ruleblend.core.usecase.SourceCheckReport
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.usecase.cachedSourceCheck
import dev.ruleblend.core.usecase.toSourcesState
import java.time.Instant
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.io.path.exists

internal data class SourceUpdateRow(
    val key: LibraryObjectKey,
    val repository: String,
    val status: SourceCheckSkillStatus,
) {
    val updateId: String get() = sourceUpdateId(key.kind, key.id)
}

data class HomeSourceUpdates(
    val skillNames: Map<String, String> = emptyMap(),
    val sources: List<HomeSkillSource> = emptyList(),
    val report: SourceCheckReport? = null,
    val lastCheckedAt: String? = null,
    val checking: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val updatingSkillIds: Set<String> = emptySet(),
    val failure: String? = null,
    val subagentNames: Map<String, String> = emptyMap(),
) {
    val visible: Boolean get() = skillNames.isNotEmpty() || subagentNames.isNotEmpty()
    internal val rows: List<SourceUpdateRow>
        get() = report?.perSkill.orEmpty().map {
            SourceUpdateRow(LibraryObjectKey(LibraryObjectKind.SKILL, it.skillId), it.repository, it.status)
        } + report?.perSubagent.orEmpty().map {
            SourceUpdateRow(LibraryObjectKey(LibraryObjectKind.SUBAGENT, it.subagentId), it.repository, it.status)
        }
    val updateIds: Set<String>
        get() = rows
            .filter { it.status == SourceCheckSkillStatus.UPDATE_AVAILABLE }
            .mapTo(linkedSetOf()) { it.updateId }
    val busy: Boolean get() = checking || updatingSkillIds.isNotEmpty()
}

/**
 * The bundled skill and the MCP registration across every assistant on this machine. Home shows it
 * because an app update leaves the copies behind and nothing else would ever say so.
 */
data class HomeIntegrationUpdates(
    val states: List<AgentIntegrationState> = emptyList(),
    val agentNames: Map<String, String> = emptyMap(),
    val updatingAgentIds: Set<String> = emptySet(),
    val failure: String? = null,
) {
    val outdated: List<AgentIntegrationState> get() = states.filter { it.outdated }
    val blocked: List<AgentIntegrationState> get() = states.filter { it.blocked }

    /** Nothing to say while everything is current: the card is attention, not a status board. */
    val visible: Boolean get() = outdated.isNotEmpty() || blocked.isNotEmpty()
    val busy: Boolean get() = updatingAgentIds.isNotEmpty()
}

/** One imported repository as Settings presents it, rebuilt from library provenance and the sidecar. */
data class HomeSkillSource(
    val repository: String,
    val skillIds: Set<String>,
    val remoteHead: String?,
    val lastCheckedAt: String?,
    val subagentIds: Set<String> = emptySet(),
)

/**
 * State of the Home surface: one attention snapshot, refreshed by an explicit scan.
 *
 * Home reads and never writes. The scan is the expensive part — every file of every place — so it
 * runs on request ([rescan]) and its result is held until the next one; counters are computed once
 * per scan, not per frame. A failed scan keeps the previous answer: a place that became unreadable
 * must not blank out what is known about the other twenty-nine.
 */
class HomeModel(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    private val libraryGit: LibraryGit? = null,
    private val agents: List<AgentAdapter>,
    private val usageSource: LibraryUsageSource,
    private val checkImportedSkillSources: (((Int, Int) -> Unit) -> SourceCheckReport)? = null,
    private val sourceStateStore: SourcesStateStore? = null,
    private val updateImportedSkills: ((Set<String>) -> Unit)? = null,
    /** The same reader Settings uses; `null` in tests that do not care about assistants. */
    private val integration: BundledIntegration? = null,
    /** Home delegates writes to the project coordinator; it never reimplements reconciliation. */
    private val setProfileActive: (suspend (String, String, Boolean) -> Unit)? = null,
    private val now: () -> Instant = Instant::now,
    private val updateImportedDefinitions: ((Set<String>, Set<String>) -> Unit)? = null,
) {
    var snapshot by mutableStateOf(HomeSnapshot.EMPTY)
        private set

    /** True while a scan is running, so the screen can disable Rescan instead of queueing scans. */
    var scanning by mutableStateOf(false)
        private set

    /** Wall-clock duration of the last scan, in milliseconds; `null` until one finishes. */
    var lastScanMillis by mutableStateOf<Long?>(null)
        private set

    /** Latest completed remote operation; it remains in [LibraryGit] until the process closes. */
    var lastRemoteSync by mutableStateOf<RemoteSyncResult?>(null)
        private set

    var remoteConfigured by mutableStateOf(false)
        private set

    /** The peer itself, so the card can name what it syncs with and whether it runs on its own. */
    var remoteGit by mutableStateOf<RemoteGitConfig?>(null)
        private set

    var remoteSyncing by mutableStateOf(false)
        private set

    var sourceUpdates by mutableStateOf(HomeSourceUpdates())
        private set

    var integrationUpdates by mutableStateOf(HomeIntegrationUpdates())
        private set

    var switchingProfile by mutableStateOf<Pair<String, String>?>(null)
        private set

    var lastProfileChange by mutableStateOf<HomeProfileChange?>(null)
        private set

    var profileFailure by mutableStateOf<String?>(null)
        private set

    val canSwitchProfiles: Boolean get() = setProfileActive != null

    /** Rebuilds cached source results against the current library without touching the network. */
    suspend fun refreshSourceUpdates() {
        // A check or an update in flight already owns this state; replaying the sidecar underneath it
        // would put the previous result back on screen for as long as the network call lasts.
        if (sourceUpdates.busy) return
        val loaded = withContext(Dispatchers.IO) {
            val imported = repository.listSkills().filter { it.source != null }
            val subagents = repository.listBlocks().filter { it.source != null }
            val state = sourceStateStore?.load()
            val cached = state?.cachedSourceCheck(repository)
            val byRepository = state?.sources.orEmpty().associateBy { it.repository }
            SourceUpdatesLoaded(
                names = imported.associate { it.id to it.name },
                subagentNames = subagents.associate { it.id to it.name },
                sources = (imported.map { it.source!!.repository } + subagents.map { it.source!!.repository })
                    .toSortedSet().map { url ->
                    val saved = byRepository[url]
                    HomeSkillSource(
                        repository = url,
                        skillIds = imported.filter { it.source!!.repository == url }.mapTo(linkedSetOf()) { it.id },
                        subagentIds = subagents.filter { it.source!!.repository == url }.mapTo(linkedSetOf()) { it.id },
                        remoteHead = saved?.remoteHead,
                        lastCheckedAt = saved?.lastCheckedAt,
                    )
                },
                report = cached?.report,
                lastCheckedAt = cached?.lastCheckedAt,
            )
        }
        sourceUpdates = sourceUpdates.copy(
            skillNames = loaded.names,
            subagentNames = loaded.subagentNames,
            sources = loaded.sources,
            report = loaded.report,
            lastCheckedAt = loaded.lastCheckedAt,
        )
    }

    /** Restores the cached result and, when configured, checks sources once for this app launch. */
    suspend fun checkSkillSourcesOnLaunch() {
        val mode = withContext(Dispatchers.IO) { configStore.load().sourceCheck }
        if (mode != SourceCheckMode.ON_LAUNCH) return
        refreshSourceUpdates()
        checkSkillSources()
    }

    /** Runs the explicit source check and persists its reusable result for the next launch. */
    suspend fun checkSkillSources() {
        val check = checkImportedSkillSources ?: return
        val store = sourceStateStore ?: return
        if (sourceUpdates.busy) return
        sourceUpdates = sourceUpdates.copy(checking = true, progressDone = 0, progressTotal = 0, failure = null)
        try {
            val report = coroutineScope {
                val progress = Channel<Pair<Int, Int>>(Channel.UNLIMITED)
                val task = async(Dispatchers.IO) {
                    try {
                        check { done, total -> progress.trySend(done to total) }
                    } finally {
                        progress.close()
                    }
                }
                for ((done, total) in progress) {
                    sourceUpdates = sourceUpdates.copy(progressDone = done, progressTotal = total)
                }
                task.await()
            }
            val checkedAt = now().toString()
            withContext(Dispatchers.IO) { store.save(report.toSourcesState(checkedAt)) }
            val names = withContext(Dispatchers.IO) {
                repository.listSkills().filter { it.source != null }.associate { it.id to it.name }
            }
            val subagentNames = withContext(Dispatchers.IO) {
                repository.listBlocks().filter { it.source != null }.associate { it.id to it.name }
            }
            sourceUpdates = sourceUpdates.copy(
                skillNames = names,
                subagentNames = subagentNames,
                sources = report.perRepo.map { source ->
                    HomeSkillSource(
                        repository = source.repository,
                        skillIds = report.perSkill.filter { it.repository == source.repository }
                            .mapTo(linkedSetOf()) { it.skillId },
                        subagentIds = report.perSubagent.filter { it.repository == source.repository }
                            .mapTo(linkedSetOf()) { it.subagentId },
                        remoteHead = source.remoteHead,
                        lastCheckedAt = checkedAt,
                    )
                },
                report = report,
                lastCheckedAt = checkedAt,
            )
        } catch (error: Exception) {
            sourceUpdates = sourceUpdates.copy(failure = error.message ?: error.toString())
        } finally {
            sourceUpdates = sourceUpdates.copy(checking = false)
        }
    }

    /** Updates only rows confirmed as changed by the latest check, then replays the cached result. */
    suspend fun updateSourceSkills(ids: Set<String>) {
        if (updateImportedDefinitions == null && updateImportedSkills == null) return
        val selected = ids intersect sourceUpdates.updateIds
        if (selected.isEmpty() || sourceUpdates.busy) return
        val skills = selected.filterNot { it.startsWith("subagent:") }.toSet()
        val subagents = selected.filter { it.startsWith("subagent:") }.map { it.removePrefix("subagent:") }.toSet()
        if (subagents.isNotEmpty() && updateImportedDefinitions == null) return
        sourceUpdates = sourceUpdates.copy(updatingSkillIds = selected, failure = null)
        try {
            withContext(Dispatchers.IO) {
                updateImportedDefinitions?.invoke(skills, subagents) ?: updateImportedSkills?.invoke(skills)
            }
            val names = withContext(Dispatchers.IO) {
                repository.listSkills().filter { it.source != null }.associate { it.id to it.name }
            }
            sourceUpdates = sourceUpdates.copy(
                skillNames = names,
                subagentNames = withContext(Dispatchers.IO) {
                    repository.listBlocks().filter { it.source != null }.associate { it.id to it.name }
                },
                report = sourceUpdates.report?.let { report ->
                    report.copy(
                        perSkill = report.perSkill.map { skill ->
                            if (skill.skillId in skills) skill.copy(status = SourceCheckSkillStatus.CURRENT) else skill
                        },
                        perSubagent = report.perSubagent.map { agent ->
                            if (agent.subagentId in subagents) agent.copy(status = SourceCheckSkillStatus.CURRENT) else agent
                        },
                    )
                },
            )
            rescan()
        } catch (error: Exception) {
            sourceUpdates = sourceUpdates.copy(failure = error.message ?: error.toString())
        } finally {
            sourceUpdates = sourceUpdates.copy(updatingSkillIds = emptySet())
        }
    }

    /** Re-reads what every assistant holds. Cheap: four config files and four skill files. */
    suspend fun refreshIntegration() {
        val service = integration ?: return
        if (integrationUpdates.busy) return
        val states = withContext(Dispatchers.IO) { runCatching { service.states() } }
        states
            .onSuccess {
                integrationUpdates = integrationUpdates.copy(
                    states = it,
                    agentNames = agents.associate { agent -> agent.id to agent.name },
                    failure = null,
                )
            }
            .onFailure { integrationUpdates = integrationUpdates.copy(failure = it.message ?: it.toString()) }
    }

    /**
     * Rewrites the outdated copies for [agentIds]. A skill someone else wrote is never among them:
     * [BundledIntegration] leaves it alone and the card keeps reporting it.
     */
    suspend fun updateIntegration(agentIds: Set<String>) {
        val service = integration ?: return
        val selected = agentIds intersect integrationUpdates.outdated.map { it.agentId }.toSet()
        if (selected.isEmpty() || integrationUpdates.busy) return
        integrationUpdates = integrationUpdates.copy(updatingAgentIds = selected, failure = null)
        try {
            withContext(Dispatchers.IO) { selected.forEach(service::update) }
        } catch (error: Exception) {
            integrationUpdates = integrationUpdates.copy(failure = error.message ?: error.toString())
        } finally {
            integrationUpdates = integrationUpdates.copy(updatingAgentIds = emptySet())
        }
        refreshIntegration()
    }

    suspend fun refreshRemoteSync() {
        val snapshot = withContext(Dispatchers.IO) {
            configStore.load().remoteGit to libraryGit?.lastRemoteSync
        }
        remoteGit = snapshot.first
        remoteConfigured = snapshot.first != null
        lastRemoteSync = snapshot.second
    }

    private data class SourceUpdatesLoaded(
        val names: Map<String, String>,
        val subagentNames: Map<String, String>,
        val sources: List<HomeSkillSource>,
        val report: SourceCheckReport?,
        val lastCheckedAt: String?,
    )

    /** Home can retry a configured remote without taking responsibility for its configuration form. */
    suspend fun syncNow() {
        val git = libraryGit ?: return
        val config = configStore.load().remoteGit ?: return
        remoteSyncing = true
        try {
            withContext(Dispatchers.IO) { runCatching { git.syncRemote(config) } }
        } finally {
            remoteSyncing = false
            refreshRemoteSync()
        }
    }

    suspend fun rescan() {
        if (scanning) return
        scanning = true
        try {
            val started = System.nanoTime()
            val scan = withContext(Dispatchers.IO) { runCatching { FileReadScope.reading { scan() } }.getOrNull() } ?: return
            lastScanMillis = (System.nanoTime() - started) / 1_000_000
            snapshot = homeSnapshot(
                scanned = scan.places,
                agents = scan.agents,
                board = scan.board,
                library = scan.library,
                profileProjects = scan.profileProjects,
            )
        } finally {
            scanning = false
        }
    }

    /** Switches an attached profile through Place's reconciler, then reads the fleet back from disk. */
    suspend fun setProfileActive(projectKey: String, profileId: String, active: Boolean) {
        val change = setProfileActive ?: return
        if (switchingProfile != null) return
        switchingProfile = projectKey to profileId
        profileFailure = null
        try {
            change(projectKey, profileId, active)
            lastProfileChange = HomeProfileChange(projectKey, profileId)
            rescan()
        } catch (error: Exception) {
            profileFailure = error.message ?: error.toString()
        } finally {
            switchingProfile = null
        }
    }

    /**
     * Drops an agent Ruleblend does not manage out of the fleet for good. A hidden agent stops being
     * a place at all, so the card cannot come back — no per-card dismissal state is needed.
     */
    suspend fun hideAgent(agentId: String) {
        withContext(Dispatchers.IO) {
            configStore.update { it.copy(hiddenAgents = (it.hiddenAgents + agentId).distinct()) }
        }
        rescan()
    }

    private data class Scan(
        val places: List<LibraryPlaceUsage>,
        val agents: List<DetectedAgent>,
        val board: PlaceBoard,
        val library: LibraryTotals,
        val profileProjects: List<HomeProfileProject>,
    )

    private fun scan(): Scan {
        val blocks = repository.listBlocks()
        val skills = repository.listSkills()
        val profiles = repository.listProfiles().associateBy { it.id }
        val scanned = usageSource.scan(blocks, skills)
        val config = configStore.load()
        return Scan(
            places = scanned,
            agents = detectedAgents(config, scanned),
            board = config.places,
            library = LibraryTotals(
                rules = blocks.count { it.type == BlockType.RULE },
                skills = skills.size,
                mcpServers = blocks.count { it.type == BlockType.MCP },
                groups = repository.listGroups().count { it.id != ALL_GROUP_ID },
            ),
            profileProjects = config.projectProfiles
                .asSequence()
                .filter { (projectKey, bindings) -> projectKey in config.projects && bindings.size >= 2 }
                .map { (projectKey, bindings) ->
                    HomeProfileProject(
                        projectKey = projectKey,
                        name = Path.of(projectKey).fileName?.toString() ?: projectKey,
                        profiles = bindings.map { binding ->
                            HomeProfileBinding(
                                id = binding.id,
                                name = profiles[binding.id]?.name ?: binding.id,
                                active = binding.active,
                            )
                        },
                        restartAgents = agents
                            .filter { agent -> agent.isAvailable() && agent.id !in config.hiddenAgents }
                            .filter { agent -> agent.id !in config.disabledAgents[projectKey].orEmpty() }
                            .filter { it.profileSwitchRequiresRestart }
                            .map { it.name },
                    )
                }
                .sortedBy { it.name.lowercase() }
                .toList(),
        )
    }

    /**
     * Agents present on this machine that Ruleblend does not manage yet: their global file exists,
     * but nothing from the library sits in it. Hidden agents never reach here — they are not places
     * at all — which is what makes the card's "Hide agent" action stick without new config.
     */
    private fun detectedAgents(config: AppConfig, scanned: List<LibraryPlaceUsage>): List<DetectedAgent> {
        val byId = scanned.associateBy { it.id }
        return agents
            .filter { it.isAvailable() && it.id !in config.hiddenAgents && it.globalFile().exists() }
            .filter { byId[placeId(AgentGlobalTarget(it))]?.installs.orEmpty().isEmpty() }
            .map {
                DetectedAgent(
                    id = it.id,
                    name = it.name,
                    placeId = placeId(AgentGlobalTarget(it)),
                    globalFile = it.globalFile().toString(),
                    eligibleProjects = config.projects.size,
                )
            }
    }
}
