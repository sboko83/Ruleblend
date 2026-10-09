package dev.ruleblend.app.integration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryInstallReport
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.library.LibraryUsageIndex
import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.app.library.marks
import dev.ruleblend.app.library.toLibraryReport
import dev.ruleblend.app.place.ConfiguredTargets
import dev.ruleblend.app.place.PlaceMembers
import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.place.placeId
import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.mark
import dev.ruleblend.app.util.CliLaunch
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.app.util.Failure
import dev.ruleblend.app.util.PartialWriteException
import dev.ruleblend.app.util.defaultLauncherDirectory
import dev.ruleblend.app.util.launchAgentCli
import dev.ruleblend.app.util.probeCli
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.PlaceBoard
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.projectKeyOf
import dev.ruleblend.core.config.withIgnoredEntry
import dev.ruleblend.core.config.withoutIgnoredEntry
import dev.ruleblend.core.config.ruleAppliesTo
import dev.ruleblend.core.config.withKeptDrift
import dev.ruleblend.core.config.withPinToggled
import dev.ruleblend.core.config.withProjectInSet
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.config.withSet
import dev.ruleblend.core.config.withSetRenamed
import dev.ruleblend.core.config.withVisited
import dev.ruleblend.core.config.withoutKeptDrift
import dev.ruleblend.core.config.withoutPlace
import dev.ruleblend.core.config.withoutSet
import dev.ruleblend.core.integration.AdoptScanResult
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.AgentLaunchMode
import dev.ruleblend.core.integration.FileSegment
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ManagedDocument
import dev.ruleblend.core.integration.ManagedRegion
import dev.ruleblend.core.integration.McpFileEntry
import dev.ruleblend.core.integration.McpStatus
import dev.ruleblend.core.integration.OrphanRemoval
import dev.ruleblend.core.integration.PlaceEntry
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SectionAction
import dev.ruleblend.core.integration.SectionCandidate
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStatus
import dev.ruleblend.core.integration.SkillDirEntry
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStatus
import dev.ruleblend.core.integration.SubagentFileEntry
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.UnmanagedContent
import dev.ruleblend.core.integration.agentCommand
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.DEFAULT_HEADING_LEVEL
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.storage.LocalSkillCapture
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.nextId
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.BackupRecord
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.AcceptLocalChange
import dev.ruleblend.core.usecase.InstallCommand
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.InstallObject
import dev.ruleblend.core.usecase.InstallPolicy
import dev.ruleblend.core.usecase.MutateLibrary
import dev.ruleblend.core.usecase.ProfileReconcileReport
import dev.ruleblend.core.usecase.RemoveObject
import dev.ruleblend.core.usecase.WriteReport
import dev.ruleblend.core.usecase.ReconcileProfiles
import dev.ruleblend.mcp.McpConnector
import dev.ruleblend.mcp.McpLaunch
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.targetKey
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A target file shown in the "found in" tree. [content] holds its hand-written text when there is
 * any to adopt; [relative] is the path as shown under the target's root. [backup] is the snapshot
 * slot, when one has been taken — a file that has been backed up stays listed (with Restore only)
 * even after it becomes fully managed, so a rollback is always one click away.
 */
data class UnmanagedFile(
    val path: Path,
    val content: UnmanagedContent,
    val relative: String,
    val backup: BackupRecord? = null,
    /**
     * True when this file is a pure `@<target>` import pointer (e.g. a `CLAUDE.md` that only imports
     * `@AGENTS.md`): it is already doing its job, so adoption/backup actions are replaced by a green
     * checkmark in the UI. Computed in [IntegrationModel] from the target's redirect map.
     */
    val isPointer: Boolean = false,
) {
    val name: String get() = path.fileName?.toString() ?: path.toString()
    /** True when there is hand-written text to adopt; false when the row exists only for Restore. */
    val canAdopt: Boolean get() = !content.isEmpty
}

/** Ownership state for one physical instruction file of the selected target. */
data class OwnershipFile(val path: Path, val mode: TargetOwnershipMode, val notice: Boolean, val canOwn: Boolean, val drifted: Boolean) {
    val name: String get() = path.fileName?.toString() ?: path.toString()
}

/** One detected assistant and whether this project currently writes its files. */
data class ProjectAssistant(val id: String, val name: String, val enabled: Boolean)

/** An agent that can be started in the open project, as the header offers it. */
data class CliAgent(val id: String, val name: String, val resumable: Boolean)

/** One agent's copy of an installed MCP entry, named the way the user knows that agent. */
data class McpCopy(val agentId: String, val agentName: String, val text: String)

/** How a profile starts governing a project that already has installed objects. */
enum class ProfileAttachMode { MERGE, REPLACE }

/** A base-owned object which a replacement attachment will try to remove. */
data class ProfileBaseItem(val id: String, val name: String, internal val item: InstallItem)

/** UI state for the Integration tab: targets, the library checklist and install statuses. */
class IntegrationModel(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val backupService: BackupService,
    private val service: IntegrationService = IntegrationService(blockResolver = repository::loadBlock),
    private val mcpService: McpInstallService,
    private val skillService: SkillInstallService,
    private val subagentService: SubagentInstallService? = null,
    private val connectors: List<McpConnector> = emptyList(),
    private val mcpLaunch: McpLaunch? = null,
    private val usageSource: LibraryUsageSource = LibraryUsageSource.None,
    /** Where the generated terminal launchers go; disposable, rewritten on every launch. */
    private val launcherDirectory: Path = defaultLauncherDirectory(),
    /** Injected so a test can describe a machine's CLIs instead of spawning shells. */
    private val cliProbe: (String) -> CliProbe? = ::probeCli,
    /** Injected so a test can assert the launch instead of opening a terminal. */
    private val cliLauncher: (CliLaunch) -> Unit = ::launchAgentCli,
) {
    private val acceptLocalChangeUseCase = AcceptLocalChange(service, mcpService, repository)
    private val installPolicy = InstallPolicy(service, mcpService, skillService, { configStore.load() }, subagentService)
    private val library = MutateLibrary(repository, configStore)
    private val installObject = InstallObject(service, mcpService, skillService, installPolicy, subagentService)
    private val removeObject = RemoveObject(service, mcpService, skillService, installPolicy, subagentService)
    private val reconcileProfiles = ReconcileProfiles(
        repository = repository,
        configStore = configStore,
        rules = service,
        policy = installPolicy,
        mcp = mcpService,
        skills = skillService,
        subagents = subagentService,
    )

    var agentTargets by mutableStateOf(emptyList<AgentGlobalTarget>())
        private set

    var projectTargets by mutableStateOf(emptyList<ProjectTarget>())
        private set

    /** Cached with the place list: composition must not parse config.json to render a toggle. */
    private var visibleAgents by mutableStateOf(emptyList<AgentAdapter>())
    private var disabledAgentsByProject by mutableStateOf(emptyMap<String, List<String>>())

    var selected by mutableStateOf<Target?>(null)
        private set

    var blocks by mutableStateOf(emptyList<Block>())
        private set

    /** Adoption compares against the whole library, including rules scoped to another place. */
    var allLibraryBlocks by mutableStateOf(emptyList<Block>())
        private set

    var groups by mutableStateOf(emptyList<Group>())
        private set

    var skills by mutableStateOf(emptyList<Skill>())
        private set

    /** Portable profiles and their project-local attachments, cached with the selected Place. */
    var profiles by mutableStateOf(emptyList<Profile>())
        private set

    var profileBindings by mutableStateOf(emptyList<ProfileBinding>())
        private set

    /** Cached replacement preview; dialog composition never reads target files. */
    var profileBaseItems by mutableStateOf(emptyList<ProfileBaseItem>())
        private set

    /** Installed region per block id in [selected]; absent = not installed. */
    var installed by mutableStateOf(emptyMap<String, ManagedRegion>())
        private set

    var statuses by mutableStateOf(emptyMap<String, InstallStatus>())
        private set

    /** MCP install status per block id in [selected]; absent = not installed. */
    var mcpStatuses by mutableStateOf(emptyMap<String, McpStatus>())
        private set

    /** Every MCP entry at this place's configured agent addresses, classified from its sidecar. */
    var mcpEntries by mutableStateOf(emptyList<PlaceEntry<McpFileEntry, String>>())
        private set

    var skillStatuses by mutableStateOf(emptyMap<String, SkillInstallStatus>())
        private set

    /** Every skill directory found under this place's known roots, classified from its sidecar. */
    var skillEntries by mutableStateOf(emptyList<PlaceEntry<SkillDirEntry, String>>())
        private set

    var subagentStatuses by mutableStateOf(emptyMap<String, SubagentInstallStatus>())
        private set

    /** Every subagent definition found under this place's known roots, classified from its sidecar. */
    var subagentEntries by mutableStateOf(emptyList<PlaceEntry<SubagentFileEntry, String>>())
        private set

    /**
     * Drifts the user chose to leave alone here: object id to a digest of the copy that was kept.
     * Read through [driftKept] — a bare id is not the answer, because the answer was about one
     * version of the edit and the file can drift again.
     */
    var keptDrifts by mutableStateOf(emptyMap<String, String>())
        private set

    /** Foreign disk-entry keys hidden in this place. They remain on disk and can be shown again. */
    var ignoredEntries by mutableStateOf(emptySet<String>())
        private set

    /**
     * The MCP entry [selected]'s config files hold per block id, serialized the way the library
     * stores it. A row that says "modified" is read against this — the badge alone never says what.
     */
    var mcpInstalledText by mutableStateOf(emptyMap<String, String>())
        private set

    /**
     * Every agent copy of an MCP entry in [selected], per block id. A project place can cover several
     * agents, and an editor can have touched one of them only: the copies are what says so, and
     * [mcpDriftedCopies] is how a row asks whether they came apart.
     */
    var mcpInstalledCopies by mutableStateOf(emptyMap<String, List<McpCopy>>())
        private set

    /** True when [selected] covers at least one agent with an MCP installer. */
    var mcpApplies by mutableStateOf(false)
        private set

    /** True when [selected] covers at least one agent with a native skill directory. */
    var skillApplies by mutableStateOf(false)
        private set

    /**
     * What each target holds, for the sidebar dots: one mark per installed object plus the mark of
     * every file that carries nothing of Ruleblend's. Computed in [refresh] rather than on read:
     * every entry costs a pass over the target's files, which must not happen during recomposition.
     */
    private var dotMarks by mutableStateOf(emptyMap<String, Set<StatusMark>>())

    /** Message of the last failed action, until the screen shows and clears it. */
    var failure by mutableStateOf<Failure?>(null)
        private set

    /** What the last group action wrote and what it left alone, until the screen shows it. */
    var groupReport by mutableStateOf<GroupActionReport?>(null)
        private set

    /**
     * What the last profile switch reconciled. Attaching, toggling and detaching all write through
     * [ReconcileProfiles], which protects a hand-edited copy instead of overwriting it — and a copy
     * left alone has no other voice, exactly as a skipped group member has none.
     */
    var profileReport by mutableStateOf<ProfileReconcileReport?>(null)
        private set

    /** Files of [selected] with hand-written content outside the markers. */
    var unmanagedFiles by mutableStateOf(emptyList<UnmanagedFile>())
        private set

    /**
     * Files [selected] reaches through `@import` directives (transitively): the full set the agent
     * actually reads, beyond its owned files. Listed alongside [unmanagedFiles] and share the same
     * Back up / Restore / Save to library actions.
     */
    var referencedFiles by mutableStateOf(emptyList<UnmanagedFile>())
        private set

    /** Directory [unmanagedFiles] are listed under: the project dir, or the agent's config dir. */
    var unmanagedRoot by mutableStateOf<Path?>(null)
        private set

    /** Per-file ownership states. Targets may have an AGENTS.md and a redirect file. */
    var ownershipFiles by mutableStateOf(emptyList<OwnershipFile>())
        private set

    /** Whole-file snapshots available from the selected Place's file tabs. */
    var backupRecords by mutableStateOf(emptyMap<Path, BackupRecord>())
        private set

    /**
     * Hand-written text and managed runs per owned file of [selected], in file order — what the file
     * preview renders. Read in [refresh] like the statuses: parsing a file during recomposition would
     * put disk reads on every frame.
     */
    var fileSegments by mutableStateOf(emptyMap<Path, List<FileSegment>>())
        private set

    /** MCP config files of [selected], for its file tabs; empty when no agent has an MCP installer. */
    var mcpFiles by mutableStateOf(emptyList<Path>())
        private set

    /** Skill directories of [selected], for its file tabs. */
    var skillDirectories by mutableStateOf(emptyList<Path>())
        private set

    /** Subagent directories of [selected], for its file tabs. */
    var subagentDirectories by mutableStateOf(emptyList<Path>())
        private set

    /** Which of the files above exist on disk, so a tab can say "not created yet" without a read. */
    var existingFiles by mutableStateOf(emptySet<Path>())
        private set

    /**
     * The library as the palette of this place offers it: the same read-model Library builds, over
     * the objects visible here — a project-scoped rule of another project is not on offer. Rebuilt in
     * [refresh] rather than read as a getter: composing it per frame would rebuild 304 objects a frame.
     */
    var catalog by mutableStateOf(LibraryCatalog.build(emptyList(), emptyList(), emptyList(), emptyMap()))
        private set

    /**
     * Where library objects sit across all places, for the palette's set recommendations. Empty until
     * [refreshUsage] finishes its first scan, and an empty index means "not scanned", not "nowhere".
     */
    var usage by mutableStateOf(LibraryUsageIndex.EMPTY)
        private set

    /**
     * Re-reads every place off the UI thread. Read-only: a failing scan keeps the previous answer
     * instead of interrupting work in this place with a dialog about someone else's file.
     */
    suspend fun refreshUsage() {
        val scanned = withContext(Dispatchers.IO) {
            // This may be the first work Place starts, before [load] has populated the selected
            // place's filtered palette. Status dots describe the whole fleet, so they must scan the
            // persisted library snapshot rather than whichever selected-place state happened to land
            // first.
            runCatching {
                FileReadScope.reading { usageSource.scan(repository.listBlocks(), repository.listSkills()) }
            }.getOrNull()
        }
            ?: return
        usage = LibraryUsageIndex.build(scanned, catalog.objects.filter { it.key.kind == LibraryObjectKind.GROUP })
        // The same scan answers "where is it installed" and every status dot in the place list, so
        // selecting a place never re-reads the fleet it is one of.
        dotMarks = scanned.associate { it.id to it.marks() }
    }

    /**
     * The configured name format, read on demand. Callers that need it during composition should
     * capture it once — every read parses the config file.
     */
    val nameFormat: NameFormat
        get() = configStore.load().nameFormat

    /**
     * Re-reads the config and the fleet it describes. Disk work runs on [Dispatchers.IO]; Compose
     * state is assigned on the caller's thread, which is the main one.
     */
    suspend fun load() {
        val config = io {
            repository.init()
            configStore.load()
        }
        columnWidths = config.columnWidths
        board = config.places
        visibleAgents = agents.filter { it.isAvailable() && it.id !in config.hiddenAgents }
        disabledAgentsByProject = config.disabledAgents
        val targets = configuredTargets(config, agents)
        agentTargets = targets.agents
        projectTargets = targets.projects
        if (selected == null) selected = agentTargets.firstOrNull() ?: projectTargets.firstOrNull()
        selected?.let { syncRedirects(it) }
        refreshSafely()
    }

    /** Surfaces a UI-side action failure through the same dialog as integration write failures. */
    fun reportFailure(error: Throwable) {
        failure = Failure.of(error)
    }

    /**
     * The project card deliberately lists every detected, visible assistant, including ones currently
     * disabled for this project. A disabled assistant would otherwise have no way back into Place.
     */
    fun projectAssistants(target: ProjectTarget): List<ProjectAssistant> {
        val disabled = disabledAgentsByProject[target.dir.projectKey()].orEmpty().toSet()
        return visibleAgents
            .map { ProjectAssistant(it.id, it.name, it.id !in disabled) }
    }

    /**
     * Assistants of [target] whose running session has to be restarted before a profile switch is
     * visible to them. Read off the adapters rather than stated once in the UI: which writes an
     * agent picks up live is the agent's behaviour, and Home answers the same question the same way.
     */
    fun profileRestartAgents(target: ProjectTarget): List<String> {
        val disabled = disabledAgentsByProject[target.dir.projectKey()].orEmpty().toSet()
        return visibleAgents.filter { it.id !in disabled && it.profileSwitchRequiresRestart }.map { it.name }
    }

    /** Changes one project-local assistant switch; existing files are intentionally left untouched. */
    suspend fun setProjectAssistantEnabled(target: ProjectTarget, agentId: String, enabled: Boolean) {
        val path = target.dir.projectKey()
        runCatching {
            io {
                configStore.update { config ->
                    val disabled = config.disabledAgents[path].orEmpty().toSet()
                    val updated = if (enabled) disabled - agentId else disabled + agentId
                    config.copy(
                        disabledAgents = if (updated.isEmpty()) config.disabledAgents - path
                        else config.disabledAgents + (path to updated.sorted()),
                    )
                }
            }
            val config = io { configStore.load() }
            val targets = configuredTargets(config, agents)
            visibleAgents = agents.filter { it.isAvailable() && it.id !in config.hiddenAgents }
            disabledAgentsByProject = config.disabledAgents
            agentTargets = targets.agents
            projectTargets = targets.projects
            selected = projectTargets.firstOrNull { it.dir.projectKey() == path }
            refreshSafely()
        }.onFailure { failure = Failure.of(it) }
    }

    /** Profiles not attached to this project yet; an inactive attachment is still attached. */
    fun attachableProfiles(target: ProjectTarget): List<Profile> {
        if (target != selected) return emptyList()
        val attached = profileBindings.mapTo(mutableSetOf()) { it.id }
        return profiles.filterNot { it.id in attached }
    }

    /** Base-owned objects which Replace will remove if their local copy is still safe to remove. */
    fun baseProfileItems(target: ProjectTarget): List<ProfileBaseItem> {
        if (target != selected) return emptyList()
        return profileBaseItems
    }

    private fun readProfileBaseItems(
        target: ProjectTarget,
        visibleBlocks: List<Block>,
        visibleSkills: List<Skill>,
    ): List<ProfileBaseItem> {
        val blocksById = visibleBlocks.associateBy { it.id }
        val skillsById = visibleSkills.associateBy { it.id }
        fun isBase(origin: String?) = origin?.startsWith("p=") != true
        return buildList {
            service.installedOrigins(target).filterValues(::isBase).keys.forEach { id ->
                blocksById[id]?.let { add(ProfileBaseItem(id, it.name, InstallItem.Rule(it))) }
            }
            mcpService.installedOrigins(target).filterValues(::isBase).keys.forEach { id ->
                blocksById[id]?.let { add(ProfileBaseItem(id, it.name, InstallItem.Mcp(it))) }
            }
            skillService.installedOrigins(target).filterValues(::isBase).keys.forEach { id ->
                skillsById[id]?.let { add(ProfileBaseItem(id, it.name, InstallItem.SkillCopy(it))) }
            }
            subagentService?.installedOrigins(target)?.filterValues(::isBase)?.keys?.forEach { id ->
                blocksById[id]?.let { add(ProfileBaseItem(id, it.name, InstallItem.Subagent(it))) }
            }
        }.distinctBy { it.item::class to it.id }.sortedBy { it.name }
    }

    /** Attaches a profile and uses the existing reconciler for every profile-controlled write. */
    suspend fun attachProfile(target: ProjectTarget, profile: Profile, mode: ProfileAttachMode) = guard {
        profileReport = null
        require(profile.id !in profileBindings.map { it.id }) { "Profile '${profile.id}' is already attached" }
        if (mode == ProfileAttachMode.REPLACE) {
            val base = readProfileBaseItems(target, blocks, skills)
            removeObject(InstallCommand(listOf(target), base.map { it.item }))
        }
        configStore.update { config ->
            val key = target.dir.projectKey()
            config.copy(projectProfiles = config.projectProfiles + (key to (config.projectProfiles[key].orEmpty() + ProfileBinding(profile.id, active = true))))
        }
        profileReport = reconcileProfiles(target)
    }

    /** Enables or disables an attached profile, then lets [ReconcileProfiles] do the minimal writes. */
    suspend fun setProfileActive(target: ProjectTarget, profileId: String, active: Boolean) = guard {
        profileReport = null
        setBindingActive(target, profileId, active)
        profileReport = reconcileProfiles(target)
    }

    /**
     * Takes a profile off the project. Attaching is not a one-way door: the members are dropped by
     * deactivating the binding and reconciling first, so detaching leaves exactly what disabling
     * would, and only then does the binding itself go. A copy the reconciler protected stays and is
     * reported — the same answer switching the profile off gives.
     */
    suspend fun detachProfile(target: ProjectTarget, profileId: String) = guard {
        profileReport = null
        setBindingActive(target, profileId, active = false)
        profileReport = reconcileProfiles(target)
        configStore.update { config ->
            val key = target.dir.projectKey()
            val remaining = config.projectProfiles[key].orEmpty().filterNot { it.id == profileId }
            config.copy(
                projectProfiles = if (remaining.isEmpty()) config.projectProfiles - key else config.projectProfiles + (key to remaining),
            )
        }
    }

    private fun setBindingActive(target: ProjectTarget, profileId: String, active: Boolean) {
        configStore.update { config ->
            val key = target.dir.projectKey()
            val updated = config.projectProfiles[key].orEmpty().map { binding ->
                if (binding.id == profileId) binding.copy(active = active) else binding
            }
            config.copy(projectProfiles = config.projectProfiles + (key to updated))
        }
    }

    /** Home addresses a project by its stable config key instead of borrowing Place's selection. */
    suspend fun setProfileActive(projectKey: String, profileId: String, active: Boolean) {
        val target = io {
            configuredTargets(configStore.load(), agents).projects
                .firstOrNull { it.dir.projectKey() == projectKeyOf(projectKey) }
                ?: error("Project '$projectKey' is not configured")
        }
        setProfileActive(target, profileId, active)
    }

    /**
     * Agents that can be started in a project from this machine: an adapter with a CLI whose
     * executable the user's shell actually resolves. An agent Ruleblend knows but that is not
     * installed gets no button — an offer that fails on click is worse than no offer.
     */
    var cliAgents by mutableStateOf(emptyList<CliAgent>())
        private set

    /** Where each CLI was found and in which shell mode, so a launch repeats what the probe did. */
    private var cliProbes = emptyMap<String, CliProbe>()

    /**
     * Looks up every candidate CLI once per app run. Each probe spawns a login shell, which is far
     * too slow for composition, so this runs off the UI thread and the buttons appear when it lands.
     */
    suspend fun refreshCliAgents() {
        if (cliAgents.isNotEmpty()) return
        val hidden = configStore.load().hiddenAgents.toSet()
        val candidates = agents.filter { it.cli != null && it.id !in hidden }
        val found = withContext(Dispatchers.IO) {
            candidates.mapNotNull { agent ->
                agent.cli?.executable?.let { executable -> runCatching { cliProbe(executable) }.getOrNull() }
                    ?.let { agent to it }
            }
        }
        cliProbes = found.associate { (agent, probe) -> agent.id to probe }
        cliAgents = found.map { (agent, _) ->
            CliAgent(id = agent.id, name = agent.name, resumable = agent.cli?.resumeArguments != null)
        }
    }

    /**
     * Opens [agentId] in a terminal, in the selected project's folder. Only a project has a folder
     * to work in — an agent global is a file, not a place work happens in — so anything else is a
     * no-op rather than a launch in some fallback directory.
     */
    fun launchCli(agentId: String, mode: AgentLaunchMode) {
        val project = selected as? ProjectTarget ?: return
        val agent = agents.find { it.id == agentId } ?: return
        val cli = agent.cli ?: return
        val probe = cliProbes[agentId] ?: return
        val config = configStore.load()
        runCatching {
            cliLauncher(
                CliLaunch(
                    launcherDirectory = launcherDirectory,
                    agentId = agentId,
                    directory = project.dir,
                    command = agentCommand(cli, mode, config.agentCliArguments[agentId].orEmpty()),
                    interactive = probe.interactive,
                    terminalApp = config.terminalAppPath?.let(Path::of),
                ),
            )
        }.onFailure { failure = Failure.of(it) }
    }

    /** Persisted column widths (dp) for the Integration tab. */
    var columnWidths by mutableStateOf(dev.ruleblend.core.config.ColumnWidths())
        private set

    /** Persists the Integration sidebar width; clamped by the caller before reaching here. */
    fun saveSidebarWidth(dp: Int) {
        configStore.update { it.copy(columnWidths = it.columnWidths.copy(integrationSidebar = dp)) }
        columnWidths = configStore.load().columnWidths
    }

    /** Persists the width of the Place palette column; clamped by the caller before reaching here. */
    fun savePaletteWidth(dp: Int) {
        configStore.update { it.copy(columnWidths = it.columnWidths.copy(placePalette = dp)) }
        columnWidths = configStore.load().columnWidths
    }

    /** Pins, recents and project sets behind the place list. Persisted, so the list survives a restart. */
    var board by mutableStateOf(PlaceBoard())
        private set

    /** The configured places, as one value for the list read-model. */
    val configuredPlaces: ConfiguredTargets get() = ConfiguredTargets(agentTargets, projectTargets)

    /**
     * Board edits touch navigation state only, so they skip the target refresh [guard] does: the list
     * re-renders from [board], and re-reading the selected target's files on every pin would be waste.
     */
    private fun updateBoard(transform: (PlaceBoard) -> PlaceBoard) {
        runCatching { board = configStore.update { it.copy(places = transform(it.places)) }.places }
            .onFailure { failure = Failure.of(it) }
    }

    fun togglePin(target: Target) = updateBoard { it.withPinToggled(placeId(target)) }

    /**
     * Saves what this place holds as a library group. A group is the only bundle this app has, so a
     * captured place becomes one: it lands in the library, travels with an export and Git history,
     * carries a version, and is offered from every list a group is offered from.
     *
     * An existing name updates that group instead of doubling it, the way re-saving a rule does;
     * [LibraryRepository.saveGroup] raises the version only when membership actually changed.
     */
    suspend fun saveAsGroup(name: String, members: PlaceMembers) = guard {
        val display = formatName(name.trim(), nameFormat)
        if (display.isBlank() || members.isEmpty) return@guard
        val existing = repository.listGroups()
        val group = existing.find { it.name.equals(display, ignoreCase = true) }
            ?: Group(id = nextId(display, existing.map { it.id }), name = display)
        repository.saveGroup(group.copy(name = display, blockIds = members.blockIds, skillIds = members.skillIds))
    }

    fun createSet(name: String) = updateBoard { it.withSet(name.trim()) }

    fun renameSet(from: String, to: String) = updateBoard { it.withSetRenamed(from, to.trim()) }

    fun deleteSet(name: String) = updateBoard { it.withoutSet(name) }

    /** Moves a project into [setName], or out of every set when it is `null`. */
    fun assignToSet(target: ProjectTarget, setName: String?) =
        updateBoard { it.withProjectInSet(target.dir.projectKey(), setName) }

    /**
     * Opens a place and records the visit. Only an explicit selection counts as one: the fallback
     * pick [load] makes when nothing is selected must not fill the recent list with places the user
     * never opened.
     */
    suspend fun select(target: Target) {
        selected = target
        updateBoard { it.withVisited(placeId(target)) }
        syncRedirects(target)
        refresh()
    }

    /**
     * Opens the place behind [id], as produced by [placeId]. A place removed from the config since
     * the Library read it is simply ignored — the current selection stays valid.
     */
    suspend fun selectPlace(id: String) {
        (agentTargets + projectTargets).find { placeId(it) == id }?.let { select(it) }
    }

    /**
     * Keeps the target's pointer files right, and migrates one written by an older Ruleblend: blocks
     * that still sit in a project's `CLAUDE.md` move into `AGENTS.md`. Done on selection because a
     * migration the user never triggers would otherwise leave two copies of every block on disk.
     */
    private suspend fun syncRedirects(target: Target) {
        runCatching { io { service.syncRedirects(target) } }.onFailure { failure = Failure.of(it) }
    }

    suspend fun addProject(dir: Path) {
        val path = dir.projectKey()
        runCatching {
            io {
                configStore.update { config ->
                    if (path in config.projects) config else config.copy(projects = config.projects + path)
                }
            }
        }.onFailure { failure = Failure.of(it) }
        writeRevision++
        load()
        projectTargets.find { it.dir.projectKey() == path }?.let { select(it) }
    }

    suspend fun removeProject(target: ProjectTarget) {
        val path = target.dir.projectKey()
        // A pin, a recent entry or a set membership pointing at a place that no longer exists would
        // linger in the config forever, so the board is cleaned in the same write.
        io {
            configStore.update { config ->
                config.copy(
                    projects = config.projects - path,
                    disabledAgents = config.disabledAgents - path,
                    projectProfiles = config.projectProfiles - path,
                    ignoredEntries = config.ignoredEntries - placeId(target),
                    places = config.places
                        .withProjectInSet(target.dir.projectKey(), null)
                        .withoutPlace(placeId(target)),
                )
            }
        }
        if (selected == target) selected = null
        writeRevision++
        load()
    }

    /** Takes a whole-file snapshot before a manual migration, edit or install. */
    suspend fun backupFile(path: Path) = guard { backupService.backup(path) }

    /** Restores the selected file's snapshot verbatim; the snapshot remains available afterwards. */
    suspend fun restoreFile(path: Path) = guard { backupService.restore(path) }

    /** Converts legacy markers using the core's safe full-versus-partial ownership decision. */
    suspend fun migrateOwnership(file: OwnershipFile) = guard {
        service.migrateLegacy(file.path, if (file.canOwn) TargetOwnershipMode.OWNED else TargetOwnershipMode.PARTIAL)
    }

    /** Leaves the rendered text in place while removing Ruleblend's ownership markers. */
    suspend fun disown(file: OwnershipFile) = guard { service.disown(file.path) }

    /** Shows or hides only the warning notice; ownership mode and content are preserved. */
    suspend fun setOwnershipNotice(file: OwnershipFile, enabled: Boolean) = guard {
        service.setNotice(file.path, enabled)
    }

    fun statusOf(blockId: String): InstallStatus? = statuses[blockId]

    /** False when no agent of [selected] can run [block] (invalid config, or e.g. http on Codex only). */
    fun mcpInstallable(block: Block): Boolean = selected?.let { mcpService.installable(it, block) } ?: false

    /** False when no agent of [selected] reads a skills directory, so nothing can be installed there. */
    fun skillInstallable(): Boolean = selected?.let { skillService.appliesTo(it) } ?: false

    fun subagentInstallable(): Boolean = selected?.let { subagentService?.appliesTo(it) } == true

    /** Everything [target] holds, as dot marks; empty means nothing has been read for it yet. */
    fun dotMarks(target: Target): Set<StatusMark> = dotMarks[placeId(target)].orEmpty()

    /**
     * True when every applicable member of [group] is installed in the target. Unsupported MCP
     * servers and skills are left out, the same way installing the group skips them.
     */
    fun isGroupInstalled(group: Group): Boolean {
        val target = selected ?: return false
        val members = group.blockIds.mapNotNull { id -> blocks.find { it.id == id } }
        val groupSkills = group.skillIds.mapNotNull { id -> skills.find { it.id == id } }
        val (mcp, nonMcp) = members.partition { it.type == BlockType.MCP }
        val (subagents, rules) = nonMcp.partition { it.type == BlockType.SUBAGENT }
        val relevantMcp = mcp.filter { mcpService.installable(target, it) }
        val relevantSkills = if (skillService.appliesTo(target)) groupSkills else emptyList()
        val relevantSubagents = subagents.filter { subagentService?.appliesTo(target) == true }
        if (rules.isEmpty() && relevantMcp.isEmpty() && relevantSubagents.isEmpty() && relevantSkills.isEmpty()) return false
        // A conflicting entry (same name, foreign content) is not ours, so it does not count.
        return rules.all { installed[it.id]?.group == group.id } &&
            relevantMcp.all { mcpStatuses[it.id]?.conflict == false } &&
            relevantSubagents.all { subagentStatuses[it.id]?.conflict == false } &&
            relevantSkills.all { skill ->
                skillStatuses[skill.id]?.conflict == false && skillService.installedEverywhere(target, skill)
            }
    }

    fun groupInstallable(group: Group): Boolean {
        val target = selected ?: return false
        return group.blockIds.any { id ->
            blocks.find { it.id == id }?.let {
                it.type != BlockType.MCP && it.type != BlockType.SUBAGENT ||
                    it.type == BlockType.MCP && mcpService.installable(target, it) ||
                    it.type == BlockType.SUBAGENT && subagentService?.appliesTo(target) == true
            } == true
        } || (skillService.appliesTo(target) && group.skillIds.any { id -> skills.any { it.id == id } })
    }

    suspend fun install(block: Block, group: String? = null, force: Boolean = false) = guard {
        val target = selected ?: return@guard
        forgetDrift(block.id)
        if (block.type == BlockType.MCP) {
            if (force) mcpService.forceInstall(target, block) else mcpService.install(target, block)
        } else if (block.type == BlockType.SUBAGENT) {
            requireNotNull(subagentService) { "No subagent installer is wired in this surface" }.install(target, block, force)
        } else {
            // A target can span several agents: a write that did not reach all of them is reported
            // with the file it stopped at, rather than looking like it went in everywhere.
            service.install(target, block, group, force).problem()?.let { throw it }
        }
    }

    /**
     * Writes a rule saved through the Library editor back into the place that opened that editor.
     * The installed region supplies its exact origin, so editing a group/profile member changes its
     * version and body without silently turning it into a base installation. A target changed while
     * the editor was open, or a block no longer installed there, is left alone.
     */
    suspend fun updateEditedRule(block: Block, sourcePlaceId: String) {
        if (block.type != BlockType.RULE) return
        val target = selected?.takeIf { placeId(it) == sourcePlaceId } ?: return
        val current = installed[block.id] ?: return
        guard {
            service.install(
                target = target,
                block = block,
                group = current.group,
                origin = current.origin,
            ).problem()?.let { throw it }
        }
    }

    /**
     * Replaces the managed skill copy of the place that opened the Library editor. Other places keep
     * their current copy and surface the ordinary update action, so saving one skill never rewrites
     * a target the user did not choose.
     */
    suspend fun updateEditedSkill(skill: Skill, sourcePlaceId: String) {
        val target = selected?.takeIf { placeId(it) == sourcePlaceId } ?: return
        guard { skillService.install(target, skill) }
    }

    /**
     * Answers "leave this edit alone" for [blockId] in the selected place. Nothing is written to the
     * target — that is the point of the answer — but it outlives the screen: a decision the app
     * forgets the moment the user looks somewhere else is not a decision, it is a fold.
     *
     * The answer is tied to [localText], the copy that was on screen when it was given. A later edit
     * of the same object is a new drift and is reported again.
     */
    suspend fun keepDrift(blockId: String, localText: String) {
        val target = selected ?: return
        io { configStore.update { it.withKeptDrift(targetKey(target), blockId, driftDigest(localText)) } }
        refreshSafely()
    }

    /** Whether [localText] is exactly the drift already kept here, and so has nothing left to ask. */
    fun driftKept(blockId: String, localText: String): Boolean =
        keptDrifts[blockId] == driftDigest(localText)

    /**
     * The copies of [blockId] that came apart, or none when the place's agents agree. A row asks this
     * before it offers to save a local MCP edit: with the copies apart there is no single "the local
     * edit", so the row shows them one per agent and each is saved by name.
     */
    fun mcpDriftedCopies(blockId: String): List<McpCopy> {
        val copies = mcpInstalledCopies[blockId].orEmpty()
        return if (copies.distinctBy { it.text }.size > 1) copies else emptyList()
    }

    /** Ends a kept answer because the drift it was about is over. */
    private fun forgetDrift(blockId: String) {
        val target = selected ?: return
        if (keptDrifts[blockId] == null) return
        configStore.update { it.withoutKeptDrift(targetKey(target), blockId) }
    }

    /**
     * Saves one external edit as the object's next library version, and ends the kept-drift answer
     * that was about it. The write itself is [AcceptLocalChange]; what belongs here is only that the
     * answer the user gave about this drift no longer applies once the drift is gone.
     */
    suspend fun acceptLocalChange(block: Block, agentId: String? = null) = guard {
        val target = selected ?: return@guard
        forgetDrift(block.id)
        acceptLocalChangeUseCase(target, block, agentId)
    }

    /**
     * Takes the object out of the selected place. [mcp] says which file holds it; it is read off the
     * library block by default, and passed in for a block the library no longer has — an installed
     * object outlives its library copy, and taking it out must not depend on that copy being there.
     */
    suspend fun remove(blockId: String, mcp: Boolean = blocks.find { it.id == blockId }?.type == BlockType.MCP) = guard {
        val target = selected ?: return@guard
        forgetDrift(blockId)
        if (mcp) mcpService.forceRemove(target, blockId)
        else service.remove(target, blockId).problem()?.let { throw it }
    }

    /**
     * Puts the blocks of [file] in [order]. Reordering reports nothing of its own — a refused drifted
     * run surfaces as the same failure dialog as any other write — so it goes through [guard].
     */
    suspend fun reorderBlocks(file: Path, order: List<String>) = guard { service.reorder(file, order) }

    suspend fun installSkill(skill: Skill, force: Boolean = false) = guard {
        val target = selected ?: return@guard
        skillService.install(target, skill, overwrite = force)
    }

    suspend fun removeSkill(skill: Skill) = guard {
        val target = selected ?: return@guard
        skillService.remove(target, skill)
    }

    suspend fun removeSubagent(subagent: Block) = guard {
        val target = selected ?: return@guard
        forgetDrift(subagent.id)
        requireNotNull(subagentService) { "No subagent installer is wired in this surface" }.remove(target, subagent)
    }

    /**
     * Adds members of the group without retagging copies installed on their own or by another group.
     * Which objects the bundle names is decided here; whether each may be written is [InstallObject] —
     * the same use case the Library's bulk install and the MCP facade write through. A member foreign
     * to this place or edited by hand after Ruleblend wrote it is reported rather than overwritten.
     * [groupReport] carries what was
     * left out and why; the per-row action in Place is where a local edit is decided about.
     */
    suspend fun installGroup(group: Group) = guard {
        groupReport = null
        val target = selected ?: return@guard
        val origins = groupOrigins(target)
        val items = groupItems(group).filter { item ->
            val installed = origins[itemKind(item)]
            installed?.containsKey(item.id) != true || installed[item.id] == "g=${group.id}"
        }
        groupReport = write(installObject(InstallCommand(listOf(target), items)), removal = false)
    }

    /**
     * Removes only copies carrying this group's origin, preserving standalone copies of every kind.
     * An absent member has nothing to take out; a foreign conflict is still reported as skipped.
     * A member edited by hand stays and is reported — taking a bundle out is not a decision to
     * discard a local edit, the same way round as [installGroup].
     *
     * A member without an origin may be a standalone install or a group copy an older build left
     * untagged: before build 165 a group wrote MCP, skill and subagent members without one, and
     * before build 169 updating any group copy dropped it. Nothing tells these apart, so they stay
     * and are named in [groupReport]; [removeUnmarkedGroupMembers] takes them out only on an
     * explicit confirmation.
     */
    suspend fun removeGroup(group: Group) = guard {
        groupReport = null
        val target = selected ?: return@guard
        val origins = groupOrigins(target)
        val unmarked = mutableListOf<InstallItem>()
        val items = groupItems(group).filter { item ->
            val kindOrigins = origins[itemKind(item)].orEmpty()
            val ownedByGroup = kindOrigins[item.id] == "g=${group.id}"
            val base = item.id in kindOrigins && kindOrigins[item.id] == null
            when (item) {
                is InstallItem.Rule -> (item.id in installed).also { present ->
                    if (present && base) unmarked += item
                } && ownedByGroup
                is InstallItem.Mcp -> mcpService.status(target, item.block)?.let {
                    if (!it.conflict && base) unmarked += item
                    it.conflict || ownedByGroup
                } == true
                is InstallItem.SkillCopy -> skillService.status(target, item.skill)?.let {
                    if (!it.conflict && base) unmarked += item
                    it.conflict || ownedByGroup
                } == true
                is InstallItem.Subagent -> subagentService?.status(target, item.block)?.let {
                    if (!it.conflict && base) unmarked += item
                    it.conflict || ownedByGroup
                } == true
            }
        }
        groupReport = write(removeObject(InstallCommand(listOf(target), items)), removal = true)
            .copy(unmarked = unmarked)
    }

    /** The confirmed second half of [removeGroup]: the members it kept because nothing marked them. */
    suspend fun removeUnmarkedGroupMembers() = guard {
        val items = groupReport?.unmarked.orEmpty()
        groupReport = null
        val target = selected ?: return@guard
        if (items.isNotEmpty()) groupReport = write(removeObject(InstallCommand(listOf(target), items)), removal = true)
    }

    private fun groupOrigins(target: Target): Map<LibraryObjectKind, Map<String, String?>> = mapOf(
        LibraryObjectKind.RULE to service.installedOrigins(target),
        LibraryObjectKind.MCP to mcpService.installedOrigins(target),
        LibraryObjectKind.SKILL to skillService.installedOrigins(target),
        LibraryObjectKind.SUBAGENT to (subagentService?.installedOrigins(target) ?: emptyMap()),
    )

    private fun itemKind(item: InstallItem): LibraryObjectKind = when (item) {
        is InstallItem.Rule -> LibraryObjectKind.RULE
        is InstallItem.Mcp -> LibraryObjectKind.MCP
        is InstallItem.SkillCopy -> LibraryObjectKind.SKILL
        is InstallItem.Subagent -> LibraryObjectKind.SUBAGENT
    }

    /** The group's members as single writes, each tagged so a later group removal knows it. */
    private fun groupItems(group: Group): List<InstallItem> =
        group.blockIds.mapNotNull { id -> blocks.find { it.id == id } }
            .map { block ->
                when (block.type) {
                    BlockType.MCP -> InstallItem.Mcp(block, group.id)
                    BlockType.SUBAGENT -> InstallItem.Subagent(block, group.id)
                    BlockType.RULE -> InstallItem.Rule(block, group.id)
                }
            } +
            group.skillIds.mapNotNull { id -> skills.find { it.id == id }?.let { InstallItem.SkillCopy(it, group.id) } }

    /**
     * The report the group dialog reads. A write that failed outright is raised instead: the counts
     * cannot say what went wrong with the file, and [guard] turns it into the failure dialog every
     * other write already shows.
     */
    private fun write(report: WriteReport, removal: Boolean): GroupActionReport {
        report.firstError()?.let { error ->
            // Other members may have landed: the failure alone would read as "nothing happened".
            if (report.written > 0 || report.skippedTotal > 0 || report.failed > 1) {
                throw PartialWriteException(report.toLibraryReport(), removal, error)
            }
            throw error
        }
        return GroupActionReport(report.toLibraryReport(), removal = removal)
    }

    /**
     * Turns the hand-written content of [file] into a library block. The library commit lands first;
     * with [replace] the text is then cut from the target file and written back as a managed region.
     */
    /**
     * Scans [text] for `@imports` and markdown links whose paths do not resolve next to [file].
     * Advisory: the dialog surfaces the findings, the user decides whether to save regardless.
     */
    fun scanAdopt(file: UnmanagedFile, text: String): AdoptScanResult {
        val baseDir = file.path.parent ?: file.path
        return service.scanAdopt(baseDir, text)
    }

    /**
     * [scope] is the project key the saved rule is pinned to, or `null` for a global rule. The dialog
     * picks it; the project of the open target is only what it opens on.
     */
    suspend fun adopt(
        file: UnmanagedFile,
        name: String,
        description: String,
        replace: Boolean,
        scope: String? = selectedProjectScope(),
    ): Unit = adopting {
        val display = formatName(name, nameFormat)
        val block = runCatching {
            io {
                repository.saveBlock(
                    Block(
                        id = nextId(display, repository.listBlocks().map { it.id }),
                        name = display,
                        description = description,
                        content = file.content.text,
                    ),
                ).also { saved -> assignScope(saved.id, scope) }
            }
        }.getOrElse {
            report(Failure.of(it))
            return@adopting
        }
        // Only the second half can fail on its own, and then the block is already in the library —
        // say so, or the user cannot tell whether anything was saved.
        if (replace) {
            runCatching {
                io {
                    service.adopt(file.path, block)
                    // Adopting a pointer file leaves the region there; syncing moves it into AGENTS.md.
                    selected?.let { service.syncRedirects(it) }
                }
            }.onFailure {
                report(Failure.of(it).copy(adoptedButNotReplaced = true))
                return@adopting
            }
        }
        refreshSafely()
    }

    /**
     * Saves text lifted from a comparison as a new library rule. Only the library is written: the
     * file the text came from stays exactly as it is.
     */
    suspend fun saveAsRule(name: String, content: String, scope: String? = selectedProjectScope()) {
        runCatching { io { library.createRule(name, content, scope) } }.onFailure { failure = Failure.of(it) }
        refreshSafely()
    }

    /**
     * Captures a foreign skill into the library and starts managing its directory, as one step: the
     * place is refreshed once both halves landed, so a library skill sitting unowned on top of its own
     * source is never shown. If ownership cannot be recorded, the captured copy is taken back out.
     */
    suspend fun adoptForeignSkill(target: Target, entry: SkillDirEntry, id: String) = guard {
        require(dev.ruleblend.core.model.validSkillName(id)) { "Skill name must be lowercase kebab-case and at most 64 characters" }
        require(skills.none { it.id == id }) { "Skill '$id' already exists in the library" }
        val saved = repository.writeSkill(LocalSkillCapture.capture(entry.path, id = id))
        try {
            skillService.takeOwnership(target, entry, saved)
        } catch (error: Exception) {
            runCatching { repository.deleteSkill(saved.id) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Manages a foreign directory as the library skill of the same name, without reinstalling it. */
    suspend fun takeForeignSkill(target: Target, entry: SkillDirEntry, skill: Skill) = guard {
        skillService.takeOwnership(target, entry, skill, sameAsLibrary = false)
    }

    /** Stops managing [skill] here; its directories stay and become foreign entries. */
    suspend fun releaseSkill(skill: Skill) = guard {
        val target = selected ?: return@guard
        skillService.release(target, skill.id)
    }

    fun foreignSkillEntry(path: java.nio.file.Path): SkillDirEntry? =
        skillEntries.firstOrNull { it.value.path == path }?.value

    /** Restores an orphaned skill under the id its sidecar still owns; its directory is left intact. */
    suspend fun restoreOrphanSkill(entry: PlaceEntry<SkillDirEntry, String>) = guard {
        require(entry.origin == PlaceEntryOrigin.ORPHAN) { "Only an orphaned skill can be restored" }
        require(repository.loadSkill(entry.libraryId) == null) { "Skill '${entry.libraryId}' already exists in the library" }
        repository.writeSkill(skillService.capture(requireNotNull(selected), entry.value, id = entry.libraryId))
    }

    fun orphanSkillEntry(path: java.nio.file.Path): PlaceEntry<SkillDirEntry, String>? =
        skillEntries.firstOrNull { it.value.path == path && it.origin == PlaceEntryOrigin.ORPHAN }

    /**
     * Captures a parsed foreign subagent into the library and starts managing its file, as one step:
     * between the two a library block would sit unowned on top of its own source and read as a
     * conflict. If ownership cannot be recorded, the saved block is taken back out.
     */
    suspend fun adoptForeignSubagent(target: Target, entry: SubagentFileEntry, id: String) = guard {
        val subagents = requireNotNull(subagentService) { "Subagent support is unavailable" }
        val draft = requireNotNull(entry.meta) { "${entry.path} has no recognized subagent format" }
        require(blocks.none { it.id == id }) { "Block '$id' already exists in the library" }
        val saved = repository.saveBlock(
            Block(
                id = id,
                name = formatName(draft.name ?: entry.id, nameFormat),
                description = draft.description.orEmpty(),
                type = BlockType.SUBAGENT,
                variants = mapOf(entry.agentId to draft.toVariant()),
                content = draft.content,
            ),
        )
        try {
            subagents.takeOwnership(target, entry, saved)
        } catch (error: Exception) {
            runCatching { repository.deleteBlock(saved.id) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Manages a foreign definition as the library subagent of the same name, without rewriting it. */
    suspend fun takeForeignSubagent(target: Target, entry: SubagentFileEntry, subagent: Block) = guard {
        requireNotNull(subagentService) { "Subagent support is unavailable" }.takeOwnership(target, entry, subagent)
    }

    /** Stops managing [subagent] here; its definition files stay and become foreign entries. */
    suspend fun releaseSubagent(subagent: Block) = guard {
        val target = selected ?: return@guard
        requireNotNull(subagentService) { "Subagent support is unavailable" }.release(target, subagent.id)
    }

    fun foreignSubagentEntry(path: java.nio.file.Path): SubagentFileEntry? =
        subagentEntries.firstOrNull { it.value.path == path }?.value

    /** Restores an orphaned definition under its previous library id without rewriting the file. */
    suspend fun restoreOrphanSubagent(entry: PlaceEntry<SubagentFileEntry, String>) = guard {
        require(entry.origin == PlaceEntryOrigin.ORPHAN) { "Only an orphaned subagent can be restored" }
        require(repository.loadBlock(entry.libraryId) == null) { "Block '${entry.libraryId}' already exists in the library" }
        val draft = requireNotNull(entry.value.meta) { "${entry.value.path} has no recognized subagent format" }
        repository.saveBlock(
            Block(
                id = entry.libraryId,
                name = formatName(draft.name ?: entry.value.id, nameFormat),
                description = draft.description.orEmpty(),
                type = BlockType.SUBAGENT,
                variants = mapOf(entry.value.agentId to draft.toVariant()),
                content = draft.content,
            ),
        )
    }

    fun orphanSubagentEntry(path: java.nio.file.Path): PlaceEntry<SubagentFileEntry, String>? =
        subagentEntries.firstOrNull { it.value.path == path && it.origin == PlaceEntryOrigin.ORPHAN }

    /**
     * Saves a supported foreign MCP entry as a library block and starts managing that entry, as one
     * step, so the block never sits unowned beside its own source. If ownership cannot be recorded,
     * the saved block is taken back out.
     */
    suspend fun adoptForeignMcp(target: Target, entry: McpFileEntry, id: String) = guard {
        val config = requireNotNull(entry.config) { "${entry.name} has no supported MCP config" }
        require(id.isNotBlank()) { "MCP server id must not be blank" }
        require(blocks.none { it.id == id }) { "Block '$id' already exists in the library" }
        val saved = repository.saveBlock(
            Block(
                id = id,
                name = formatName(entry.name, nameFormat),
                type = BlockType.MCP,
                content = McpConfigCodec.serialize(config),
            ),
        )
        try {
            mcpService.takeOwnership(target, entry, saved)
        } catch (error: Exception) {
            runCatching { repository.deleteBlock(saved.id) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    /** Manages a foreign config entry as the library MCP server of the same name, without changing it. */
    suspend fun takeForeignMcp(target: Target, entry: McpFileEntry, block: Block) = guard {
        mcpService.takeOwnership(target, entry, block)
    }

    /** Stops managing [block] here; its config entries stay and become foreign entries. */
    suspend fun releaseMcp(block: Block) = guard {
        val target = selected ?: return@guard
        mcpService.release(target, block.id)
    }

    fun foreignMcpEntry(path: java.nio.file.Path, name: String): McpFileEntry? =
        mcpEntries.firstOrNull { it.value.file == path && it.value.name == name }?.value

    /** Restores a supported orphaned MCP server under its previous library id without changing config. */
    suspend fun restoreOrphanMcp(entry: PlaceEntry<McpFileEntry, String>) = guard {
        require(entry.origin == PlaceEntryOrigin.ORPHAN) { "Only an orphaned MCP server can be restored" }
        require(repository.loadBlock(entry.libraryId) == null) { "Block '${entry.libraryId}' already exists in the library" }
        val config = requireNotNull(entry.value.config) { "${entry.value.name} has no supported MCP config" }
        repository.saveBlock(
            Block(
                id = entry.libraryId,
                name = formatName(entry.value.name, nameFormat),
                type = BlockType.MCP,
                content = McpConfigCodec.serialize(config),
            ),
        )
    }

    fun orphanMcpEntry(path: java.nio.file.Path, name: String): PlaceEntry<McpFileEntry, String>? =
        mcpEntries.firstOrNull { it.value.file == path && it.value.name == name && it.origin == PlaceEntryOrigin.ORPHAN }

    /** Removes only the sidecar-owned orphan whose physical entry is still unchanged. */
    suspend fun removeOrphan(kind: LibraryObjectKind, entryKey: String) = guard {
        val target = requireNotNull(selected) { "No place selected" }
        val orphanId = when (kind) {
            LibraryObjectKind.SKILL -> skillEntries
            LibraryObjectKind.SUBAGENT -> subagentEntries
            LibraryObjectKind.MCP -> mcpEntries
            LibraryObjectKind.RULE, LibraryObjectKind.GROUP, LibraryObjectKind.PROFILE -> emptyList()
        }.firstOrNull { it.key == entryKey && it.origin == PlaceEntryOrigin.ORPHAN }?.libraryId
            ?: error("Only an orphaned entry can be removed")
        val outcome = when (kind) {
            LibraryObjectKind.SKILL -> skillService.removeOrphan(target, orphanId)
            LibraryObjectKind.SUBAGENT -> requireNotNull(subagentService) { "Subagent support is unavailable" }.removeOrphan(target, orphanId)
            LibraryObjectKind.MCP -> mcpService.removeOrphan(target, orphanId)
            LibraryObjectKind.RULE, LibraryObjectKind.GROUP, LibraryObjectKind.PROFILE -> error("This kind does not have disk-entry orphans")
        }
        require(outcome != OrphanRemoval.PROTECTED) { "The orphaned entry was edited after Ruleblend installed it" }
    }

    /** Deletes a disk entry Ruleblend does not own after its per-row confirmation. */
    suspend fun removeForeign(kind: LibraryObjectKind, entryKey: String) = guard {
        val target = requireNotNull(selected) { "No place selected" }
        when (kind) {
            LibraryObjectKind.SKILL -> skillEntries.firstOrNull {
                it.key == entryKey && it.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED)
            }?.value?.let { skillService.removeForeign(target, it) }
            LibraryObjectKind.MCP -> mcpEntries.firstOrNull {
                it.key == entryKey && it.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED)
            }?.value?.let { mcpService.removeForeign(target, it) }
            else -> null
        } ?: error("Only a foreign skill or MCP server can be removed")
    }

    /** Hides one foreign disk entry locally; it is neither changed on disk nor removed from discovery. */
    suspend fun hideForeignEntry(entryKey: String) = guard {
        val target = requireNotNull(selected) { "No place selected" }
        val foreign = skillEntries.any { it.key == entryKey && it.origin == PlaceEntryOrigin.FOREIGN } ||
            subagentEntries.any { it.key == entryKey && it.origin == PlaceEntryOrigin.FOREIGN } ||
            mcpEntries.any { it.key == entryKey && it.origin == PlaceEntryOrigin.FOREIGN }
        require(foreign) { "Only a foreign entry can be hidden" }
        configStore.update { it.withIgnoredEntry(placeId(target), entryKey) }
    }

    /** Restores a locally hidden foreign entry to the visible list. */
    suspend fun showForeignEntry(entryKey: String) = guard {
        val target = requireNotNull(selected) { "No place selected" }
        require(entryKey in ignoredEntries) { "Entry is not hidden" }
        configStore.update { it.withoutIgnoredEntry(placeId(target), entryKey) }
    }

    /**
     * Splits [file]'s unmanaged text into sections and turns each included [SectionCandidate] into a
     * library block — new or overwriting its matched target — in one pass. Library commits land first;
     * with [replace] the adopted sections are then written back to the target file in one atomic write:
     * an rb1 file holds one run, so the adopted sections must sit together and the run takes their place;
 * a plan that would move a skipped section is refused (the dialog says so before saving).
 * Existing single-block [adopt] is the one-section special case.
     */
    suspend fun batchAdopt(
        file: UnmanagedFile,
        candidates: List<SectionCandidate>,
        description: String,
        replace: Boolean,
        scope: String? = selectedProjectScope(),
    ): Unit = adopting {
        val included = candidates.filter { it.included && it.action != SectionAction.SKIP }
        if (included.isEmpty() && !replace) {
            refreshSafely()
            return@adopting
        }

        val existingIds = io { repository.listBlocks().map { it.id }.toMutableList() }
        val format = nameFormat
        // Per candidate in file order: the saved block, or null when skipped/failed. Drives the plan.
        val savedByCandidate = LinkedHashMap<SectionCandidate, Block?>()
        val errors = mutableListOf<String>()

        for (candidate in candidates) {
            val section = candidate.section
            if (!candidate.included || candidate.action == SectionAction.SKIP) {
                savedByCandidate[candidate] = null
                continue
            }
            val outcome = runCatching {
                io {
                    when (candidate.action) {
                        SectionAction.SKIP -> null
                        SectionAction.CREATE_NEW -> {
                            val name = savedName(section, format)
                            val id = nextId(slugOrFallback(section), existingIds)
                            existingIds += id
                            repository.saveBlock(
                                Block(
                                    id = id,
                                    name = name,
                                    description = description,
                                    content = section.body,
                                    heading = section.title,
                                    headingLevel = section.headingLevel.takeIf { it > 0 } ?: DEFAULT_HEADING_LEVEL,
                                ),
                            ).also { saved -> assignScope(saved.id, scope) }
                        }
                        SectionAction.OVERWRITE -> {
                            val target = candidate.match.target
                            if (target == null) null
                            else repository.saveBlock(
                                target.copy(
                                    content = section.body,
                                    heading = section.title,
                                    headingLevel = section.headingLevel.takeIf { it > 0 } ?: target.headingLevel,
                                ),
                            )
                        }
                    }
                }
            }.getOrElse {
                errors += it.message ?: it.javaClass.simpleName
                null
            }
            savedByCandidate[candidate] = outcome
        }

        var notReplaced = false
        if (replace) {
            val plan = savedByCandidate.entries.map { (candidate, block) ->
                // The file gets the section as it appeared in the source (heading + body), so a
                // skipped section is byte-identical; the encoding decides where the regions land.
                val sourceText = candidate.section.sourceText
                if (block != null) {
                    ManagedDocument.AdoptStep.Replace(sourceText, regionFor(block))
                } else {
                    ManagedDocument.AdoptStep.Keep(sourceText)
                }
            }
            runCatching {
                io {
                    service.adoptSections(file.path, plan)
                    // Adopting a pointer file leaves the regions there; syncing moves them into AGENTS.md.
                    selected?.let { service.syncRedirects(it) }
                }
            }.onFailure { notReplaced = true }
        }

        if (errors.isEmpty() && !notReplaced) failure = null
        else failure = Failure(
            cause = buildString {
                if (notReplaced) append("Saved but could not rewrite the target file. ")
                errors.forEach { append(it).append("; ") }
            }.trimEnd().ifEmpty { "Some sections could not be saved." },
            adoptedButNotReplaced = notReplaced,
        )
        refreshSafely()
    }

    /** Slug for new-block id resolution; falls back to "section" when the title has no usable chars. */
    private fun slugOrFallback(section: dev.ruleblend.core.integration.Section): String =
        section.slug.ifEmpty { "section" }

    fun clearFailure() {
        failure = null
    }

    fun clearGroupReport() {
        groupReport = null
    }

    fun clearProfileReport() {
        profileReport = null
    }

    /** Overwrites any Integration target file through an atomic temp+rename. */
    suspend fun editFile(path: Path, text: String) = guard {
        AtomicWrite.write(path, text)
    }

    /**
     * Runs [action], surfacing an I/O failure (read-only project dir, disk full) as a dialog instead
     * of letting it escape into Compose. The model is reloaded either way, so what is shown is what
     * actually landed on disk.
     */
    private suspend fun guard(action: () -> Unit) {
        io { runCatching { requireSelectedFolder(); action() } }.onFailure { failure = Failure.of(it) }
        writeRevision++
        refreshSafely()
    }

    /**
     * A registered project whose folder was moved or deleted is never written to: every writer
     * creates missing parents, so a write would rebuild the path around one instruction file.
     */
    private fun requireSelectedFolder() {
        val project = selected as? ProjectTarget ?: return
        if (!Files.isDirectory(project.dir)) {
            throw NoSuchFileException(project.dir.toString(), null, "project folder is missing")
        }
    }

    /** Runs disk work off the UI thread; every [guard]ed write goes through it. */
    private suspend fun <T> io(work: () -> T): T = withContext(Dispatchers.IO) { work() }

    /**
     * Adopt reports its own failures (the library half can land while the file half does not), so it
     * cannot go through [guard] — but it writes, and with `replace` it writes to the file. This marks
     * the write so the set aggregate is rescanned, whichever half of the adopt actually landed.
     */
    private inline fun adopting(action: () -> Unit) {
        try {
            action()
        } finally {
            writeRevision++
        }
    }

    /**
     * Counts write attempts in this place. [refresh] repairs the statuses shown here, but the set
     * aggregate ("installed in 6 of 8") is a cross-place scan and stays stale until [refreshUsage]
     * runs again — this is what the screen watches to schedule it, off the UI thread.
     *
     * Bumped even on a failed write: a write that threw may still have changed the file.
     */
    var writeRevision by mutableStateOf(0)
        private set

    private suspend fun report(reported: Failure) {
        failure = reported
        refreshSafely()
    }

    /** A refresh that cannot itself take the app down; a broken read leaves the previous state. */
    private suspend fun refreshSafely() {
        runCatching { refresh() }.onFailure { if (failure == null) failure = Failure.of(it) }
    }

    /**
     * Everything one refresh of the open place computes. Assembled off the UI thread and assigned in
     * one go, so a place is never shown half-read and a large fleet never holds a frame.
     */
    private class PlaceState(
        val allLibraryBlocks: List<Block> = emptyList(),
        val blocks: List<Block> = emptyList(),
        val groups: List<Group> = emptyList(),
        val skills: List<Skill> = emptyList(),
        val profiles: List<Profile> = emptyList(),
        val profileBindings: List<ProfileBinding> = emptyList(),
        val profileBaseItems: List<ProfileBaseItem> = emptyList(),
        val installed: Map<String, ManagedRegion> = emptyMap(),
        val statuses: Map<String, InstallStatus> = emptyMap(),
        val mcpStatuses: Map<String, McpStatus> = emptyMap(),
        val mcpEntries: List<PlaceEntry<McpFileEntry, String>> = emptyList(),
        val mcpInstalledText: Map<String, String> = emptyMap(),
        val mcpInstalledCopies: Map<String, List<McpCopy>> = emptyMap(),
        val skillStatuses: Map<String, SkillInstallStatus> = emptyMap(),
        val skillEntries: List<PlaceEntry<SkillDirEntry, String>> = emptyList(),
        val subagentStatuses: Map<String, SubagentInstallStatus> = emptyMap(),
        val subagentEntries: List<PlaceEntry<SubagentFileEntry, String>> = emptyList(),
        val keptDrifts: Map<String, String> = emptyMap(),
        val ignoredEntries: Set<String> = emptySet(),
        val mcpApplies: Boolean = false,
        val skillApplies: Boolean = false,
        val unmanagedFiles: List<UnmanagedFile> = emptyList(),
        val referencedFiles: List<UnmanagedFile> = emptyList(),
        val unmanagedRoot: Path? = null,
        val ownershipFiles: List<OwnershipFile> = emptyList(),
        val backupRecords: Map<Path, BackupRecord> = emptyMap(),
        val fileSegments: Map<Path, List<FileSegment>> = emptyMap(),
        val mcpFiles: List<Path> = emptyList(),
        val skillDirectories: List<Path> = emptyList(),
        val subagentDirectories: List<Path> = emptyList(),
        val existingFiles: Set<Path> = emptySet(),
        val catalog: LibraryCatalog = LibraryCatalog.build(emptyList(), emptyList(), emptyList(), emptyMap()),
    )

    private suspend fun refresh() {
        val target = selected
        apply(withContext(Dispatchers.IO) { FileReadScope.reading { readPlace(target) } })
    }

    /**
     * Reads library, config and disk for [target] only. The other places of the fleet are not touched
     * here: their dots come from the cross-place scan [refreshUsage] runs, which Home, Coverage and
     * the Library read as well. Touches no Compose state — it runs on IO.
     */
    private fun readPlace(target: Target?): PlaceState {
        val libraryBlocks = repository.listBlocks()
        val librarySkills = repository.listSkills()
        val profiles = repository.listProfiles()
        val config = configStore.load()
        val mcpBlocks = libraryBlocks.filter { it.type == BlockType.MCP }
        val subagentBlocks = libraryBlocks.filter { it.type == BlockType.SUBAGENT }
        if (target == null) return PlaceState()

        val installedRuleIds = target.files().flatMap { service.regions(it) }.mapTo(mutableSetOf()) { it.id }
        val blocks = libraryBlocks.filter { block ->
            block.type != BlockType.RULE || block.id in installedRuleIds ||
                config.ruleAppliesTo(block.id, (target as? ProjectTarget)?.dir)
        }
        val visibleIds = blocks.mapTo(mutableSetOf()) { it.id }
        val visibleSkillIds = librarySkills.mapTo(mutableSetOf()) { it.id }
        val groups = repository.listGroups().filter { group ->
            group.blockIds.any { it in visibleIds } || group.skillIds.any { it in visibleSkillIds }
        }
        val mcpApplies = mcpService.appliesTo(target)
        val skillApplies = skillService.appliesTo(target)
        val root = rootOf(target)
        val ownershipFiles = target.files().map { path ->
            val mode = service.ownershipMode(path)
            val notice = path.takeIf { it.exists() }
                ?.let { runCatching { it.readText().contains("Ruleblend-managed") || it.readText().contains("managed by Ruleblend") }.getOrDefault(true) }
                ?: true
            OwnershipFile(path, mode, notice, service.unmanaged(path).isEmpty, service.ownershipDrift(path))
        }
        // Backups are looked up across owned AND referenced files, so a referenced file that was
        // backed up still shows Restore — and gets its slot cleared along with the rest on a refresh.
        val ownedPaths = target.ownedFiles().toSet()
        val referencedPaths = service.referencedImports(target).toSet()
        val backups = (ownedPaths + referencedPaths)
            .mapNotNull { path -> backupService.record(path)?.let { r -> path to r } }
            .toMap()
        // A file is listed when it still has hand-written content to adopt, OR when it has a backup
        // slot — the second case keeps a fully-managed file reachable for Restore.
        val pointers = target.redirects().associate { it.from to it.to }
        val mcpFiles = if (mcpApplies) mcpService.configFiles(target) else emptyList()
        val skillDirectories = if (skillApplies) skillService.directories(target) else emptyList()
        val applicableSubagentService = subagentService?.takeIf { it.appliesTo(target) }
        val subagentDirectories = applicableSubagentService?.directories(target).orEmpty()
        return PlaceState(
            allLibraryBlocks = libraryBlocks,
            blocks = blocks,
            groups = groups,
            skills = librarySkills,
            profiles = profiles,
            profileBindings = (target as? ProjectTarget)?.let { config.projectProfiles[it.dir.projectKey()].orEmpty() }.orEmpty(),
            profileBaseItems = (target as? ProjectTarget)?.let { readProfileBaseItems(it, blocks, librarySkills) }.orEmpty(),
            installed = blocks.mapNotNull { block -> service.regionOf(target, block.id)?.let { block.id to it } }.toMap(),
            statuses = blocks.mapNotNull { block -> service.status(target, block)?.let { block.id to it } }.toMap(),
            mcpStatuses =
                if (mcpApplies) mcpBlocks.mapNotNull { block -> mcpService.status(target, block)?.let { block.id to it } }.toMap()
                else emptyMap(),
            mcpEntries =
                if (mcpApplies) {
                    mcpService.classifiedEntries(
                        target,
                        mcpBlocks.mapTo(mutableSetOf()) { it.id },
                        config.ignoredEntries[placeId(target)].orEmpty().toSet(),
                    )
                } else emptyList(),
            mcpInstalledText =
                if (mcpApplies) mcpBlocks.mapNotNull { block -> mcpService.installedText(target, block)?.let { block.id to it } }.toMap()
                else emptyMap(),
            mcpInstalledCopies =
                if (mcpApplies) mcpBlocks.associate { block -> block.id to copiesOf(target, block) }.filterValues { it.isNotEmpty() }
                else emptyMap(),
            skillStatuses =
                if (skillApplies) librarySkills.mapNotNull { skill -> skillService.status(target, skill)?.let { skill.id to it } }.toMap()
                else emptyMap(),
            skillEntries =
                if (skillApplies) {
                    skillService.classifiedEntries(
                        target,
                        librarySkills.mapTo(mutableSetOf()) { it.id },
                        config.ignoredEntries[placeId(target)].orEmpty().toSet(),
                    )
                }
                else emptyList(),
            subagentStatuses =
                applicableSubagentService?.let { subagents ->
                    subagentBlocks.mapNotNull { block -> subagents.status(target, block)?.let { block.id to it } }.toMap()
                }.orEmpty(),
            subagentEntries =
                applicableSubagentService?.classifiedEntries(
                    target,
                    subagentBlocks.mapTo(mutableSetOf()) { it.id },
                    config.ignoredEntries[placeId(target)].orEmpty().toSet(),
                ).orEmpty(),
            keptDrifts = config.keptDrifts[targetKey(target)].orEmpty(),
            ignoredEntries = config.ignoredEntries[placeId(target)].orEmpty().toSet(),
            mcpApplies = mcpApplies,
            skillApplies = skillApplies,
            unmanagedFiles = ownedFilesRows(target, root, backups, pointers),
            referencedFiles = referencedPaths.mapNotNull { path ->
                val content = service.unmanaged(path)
                val backup = backups[path]
                if (content.isEmpty && backup == null) return@mapNotNull null
                UnmanagedFile(path, content, relativeTo(root, path), backup, service.isPurePointer(content, pointers[path]))
            },
            unmanagedRoot = root,
            ownershipFiles = ownershipFiles,
            backupRecords = backups,
            fileSegments = target.ownedFiles().associateWith { service.segments(it) },
            mcpFiles = mcpFiles,
            skillDirectories = skillDirectories,
            subagentDirectories = subagentDirectories,
            existingFiles = (target.ownedFiles() + mcpFiles + skillDirectories + subagentDirectories).filterTo(mutableSetOf()) { it.exists() },
            catalog = LibraryCatalog.build(blocks, groups, librarySkills, config.ruleScopes),
        )
    }

    /** The agent copies of one MCP block, named for the reader; empty when it is installed nowhere. */
    private fun copiesOf(target: Target, block: Block): List<McpCopy> =
        mcpService.installedCopies(target, block).map { copy ->
            McpCopy(copy.agentId, agents.find { it.id == copy.agentId }?.name ?: copy.agentId, copy.text)
        }

    private fun apply(state: PlaceState) {
        allLibraryBlocks = state.allLibraryBlocks
        blocks = state.blocks
        groups = state.groups
        skills = state.skills
        profiles = state.profiles
        profileBindings = state.profileBindings
        profileBaseItems = state.profileBaseItems
        installed = state.installed
        statuses = state.statuses
        mcpStatuses = state.mcpStatuses
        mcpEntries = state.mcpEntries
        mcpInstalledText = state.mcpInstalledText
        mcpInstalledCopies = state.mcpInstalledCopies
        skillStatuses = state.skillStatuses
        skillEntries = state.skillEntries
        subagentStatuses = state.subagentStatuses
        subagentEntries = state.subagentEntries
        keptDrifts = state.keptDrifts
        ignoredEntries = state.ignoredEntries
        mcpApplies = state.mcpApplies
        skillApplies = state.skillApplies
        unmanagedFiles = state.unmanagedFiles
        referencedFiles = state.referencedFiles
        unmanagedRoot = state.unmanagedRoot
        ownershipFiles = state.ownershipFiles
        backupRecords = state.backupRecords
        fileSegments = state.fileSegments
        mcpFiles = state.mcpFiles
        skillDirectories = state.skillDirectories
        subagentDirectories = state.subagentDirectories
        existingFiles = state.existingFiles
        catalog = state.catalog
    }

    /** Newly adopted project text starts in that project's scope; agent-global adoption stays global. */
    /** Project keys a rule can be pinned to: every project Ruleblend knows about, in board order. */
    val projectScopes: List<String>
        get() = projectTargets.map { it.dir.projectKey() }.distinct()

    /** The scope an adopt form opens on: the project of the open target, global for an agent target. */
    fun selectedProjectScope(): String? = (selected as? ProjectTarget)?.dir?.projectKey()

    private fun assignScope(blockId: String, scope: String?) {
        configStore.update { it.withRuleScope(blockId, scope?.let(Path::of)) }
    }

    private fun ownedFilesRows(
        target: Target,
        root: Path?,
        backups: Map<Path, BackupRecord>,
        pointers: Map<Path, Path>,
    ): List<UnmanagedFile> = target.ownedFiles().mapNotNull { path ->
        val content = service.unmanaged(path)
        val backup = backups[path]
        if (content.isEmpty && backup == null) return@mapNotNull null
        UnmanagedFile(path, content, relativeTo(root, path), backup, service.isPurePointer(content, pointers[path]))
    }
}

/** The directory a target's files are listed under: same as the `@import` resolution root. */
private fun rootOf(target: Target): Path? = target.importRoot()

/**
 * [path] as shown under [root]. Falls back to the bare file name: a target file is always inside its
 * root today, but a relativize across roots would yield a wall of `../` rather than fail loudly.
 */
private fun relativeTo(root: Path?, path: Path): String {
    val name = path.fileName?.toString() ?: path.toString()
    if (root == null) return name
    return runCatching { root.relativize(path).toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
        ?: name
}

/**
 * The name a section lands in the library under: its title (or the first non-blank body line when
 * untitled), normalized to [format]. Shared by the adopt dialog (preview) and
 * [IntegrationModel.batchAdopt] (save), so the row shows exactly what will be saved.
 */
internal fun savedName(section: dev.ruleblend.core.integration.Section, format: NameFormat): String =
    formatName(displayName(section), format)

private fun displayName(section: dev.ruleblend.core.integration.Section): String =
    section.displayTitle.ifBlank { "Untitled" }

/**
 * What a kept drift is remembered by. A digest rather than the text itself: the config is a settings
 * file a person can open, and a whole hand-edited rule pasted into it would drown everything else.
 */
private fun driftDigest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/**
 * The outcome of one group action, with the half the screen needs to word it. [unmarked] are members
 * a removal left because they carry no origin: standalone, or written by a group before build 165.
 */
data class GroupActionReport(
    val report: LibraryInstallReport,
    val removal: Boolean,
    val unmarked: List<InstallItem> = emptyList(),
)

/** What a report calls an install item: the library name, as the catalog shows it. */
val InstallItem.displayName: String
    get() = when (this) {
        is InstallItem.Rule -> block.name
        is InstallItem.Mcp -> block.name
        is InstallItem.Subagent -> block.name
        is InstallItem.SkillCopy -> skill.name
    }
