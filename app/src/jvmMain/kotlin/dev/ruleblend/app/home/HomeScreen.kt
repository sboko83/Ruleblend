package dev.ruleblend.app.home

import dev.ruleblend.app.library.KindMark
import dev.ruleblend.app.library.LibraryObjectKind

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.mcp.AgentIntegrationState
import dev.ruleblend.app.theme.CompactButton
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.theme.CompactOutlinedButton
import dev.ruleblend.app.theme.DisclosureArrow
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.theme.SectionHeaderStyle
import dev.ruleblend.app.theme.StatusBadge
import dev.ruleblend.app.theme.StatusDot
import dev.ruleblend.app.theme.color
import dev.ruleblend.app.theme.leading
import dev.ruleblend.app.theme.SyncBadge
import dev.ruleblend.app.settings.ago
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.storage.RemoteSyncConflict
import dev.ruleblend.core.storage.RemoteSyncResult
import dev.ruleblend.core.storage.RemoteSyncStatus
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/** How wide the feed grows before it stops following the window; a long line is not a readable one. */
private val ContentWidth = 980.dp

/**
 * The Home surface: what needs attention, how the whole fleet is doing, and the way back into the
 * places visited last.
 *
 * The screen draws [HomeModel.snapshot] and nothing else — no file is read here, no counter is
 * recomputed per frame. Every action either navigates or asks the model; the only write Home owns is
 * hiding an agent, which removes a place instead of dismissing a card.
 */
@Composable
fun HomeScreen(
    model: HomeModel,
    onOpenPlace: (String) -> Unit,
    onOpenResolve: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenHistory: (RemoteSyncConflict) -> Unit = {},
    onOpenCompare: (RemoteSyncConflict) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    HomeScreen(
        snapshot = model.snapshot,
        scanning = model.scanning,
        lastScanMillis = model.lastScanMillis,
        onRescan = { scope.launch { model.rescan() } },
        onHideAgent = { agentId -> scope.launch { model.hideAgent(agentId) } },
        onOpenPlace = onOpenPlace,
        onOpenResolve = onOpenResolve,
        onOpenLibrary = onOpenLibrary,
        remoteConfigured = model.remoteConfigured,
        remoteGit = model.remoteGit,
        remoteSync = model.lastRemoteSync,
        remoteSyncing = model.remoteSyncing,
        onSyncNow = { scope.launch { model.syncNow() } },
        sourceUpdates = model.sourceUpdates,
        onCheckSources = { scope.launch { model.checkSkillSources() } },
        onUpdateSources = { ids -> scope.launch { model.updateSourceSkills(ids) } },
        integrationUpdates = model.integrationUpdates,
        onUpdateIntegration = { ids -> scope.launch { model.updateIntegration(ids) } },
        onOpenHistory = onOpenHistory,
        onOpenCompare = onOpenCompare,
        onOpenSettings = onOpenSettings,
        profileChange = model.lastProfileChange,
        profileSwitching = model.switchingProfile,
        profileFailure = model.profileFailure,
        canSwitchProfiles = model.canSwitchProfiles,
        onSetProfileActive = { projectKey, profileId, active ->
            scope.launch { model.setProfileActive(projectKey, profileId, active) }
        },
        modifier = modifier,
    )
}

/** Stable handle for the one action the sync card can offer. */
internal const val HomeSyncNowTag = "home-sync-now"

/** The rendering half, free of the model: one snapshot in, navigation and two requests out. */
@Composable
fun HomeScreen(
    snapshot: HomeSnapshot,
    scanning: Boolean,
    lastScanMillis: Long?,
    onRescan: () -> Unit,
    onHideAgent: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenResolve: () -> Unit,
    onOpenLibrary: () -> Unit,
    remoteConfigured: Boolean = false,
    remoteGit: RemoteGitConfig? = null,
    remoteSync: RemoteSyncResult? = null,
    remoteSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    sourceUpdates: HomeSourceUpdates = HomeSourceUpdates(),
    onCheckSources: () -> Unit = {},
    onUpdateSources: (Set<String>) -> Unit = {},
    integrationUpdates: HomeIntegrationUpdates = HomeIntegrationUpdates(),
    onUpdateIntegration: (Set<String>) -> Unit = {},
    onOpenHistory: (RemoteSyncConflict) -> Unit = {},
    onOpenCompare: (RemoteSyncConflict) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    profileChange: HomeProfileChange? = null,
    profileSwitching: Pair<String, String>? = null,
    profileFailure: String? = null,
    canSwitchProfiles: Boolean = false,
    onSetProfileActive: (String, String, Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val scrollState = rememberScrollState()

    Row(modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Column {
                    SectionHeader(
                        title = strings.homeNeedsAttention,
                        trailing = {
                            ScanControls(
                                scanning = scanning,
                                lastScanMillis = lastScanMillis,
                                strings = strings,
                                onRescan = onRescan,
                            )
                        },
                    )
                    AttentionFeed(
                        snapshot = snapshot,
                        strings = strings,
                        onOpenPlace = onOpenPlace,
                        onOpenResolve = onOpenResolve,
                        onOpenLibrary = onOpenLibrary,
                        onScan = onRescan,
                        onHideAgent = onHideAgent,
                    )
                }
                if (integrationUpdates.visible) {
                    IntegrationUpdatesCard(
                        state = integrationUpdates,
                        strings = strings,
                        onUpdate = onUpdateIntegration,
                    )
                }
                if (sourceUpdates.visible) {
                    SourceUpdatesCard(
                        state = sourceUpdates,
                        strings = strings,
                        onCheck = onCheckSources,
                        onUpdate = onUpdateSources,
                    )
                }
                if (remoteConfigured) {
                    SyncCard(
                        result = remoteSync,
                        remote = remoteGit,
                        syncing = remoteSyncing,
                        strings = strings,
                        onSyncNow = onSyncNow,
                        onOpenHistory = onOpenHistory,
                        onOpenCompare = onOpenCompare,
                        onOpenSettings = onOpenSettings,
                    )
                }
                if (snapshot.profileProjects.isNotEmpty()) {
                    ProfilesPanel(
                        projects = snapshot.profileProjects,
                        change = profileChange,
                        switching = profileSwitching,
                        failure = profileFailure,
                        canSwitch = canSwitchProfiles,
                        strings = strings,
                        onSetProfileActive = onSetProfileActive,
                    )
                }
                if (snapshot.fleet.total > 0) {
                    Column {
                        SectionHeader(
                            title = strings.homeFleetHealth,
                            trailing = {
                                val fleet = snapshot.fleet
                                Text(
                                    strings.homeFleetSummary(
                                        fleet.total,
                                        fleet.clean,
                                        fleet.withUpdates,
                                        fleet.withConflicts,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        snapshot.fleet.groups.forEach { group ->
                            FleetGroupRow(group, strings, onOpenPlace)
                        }
                    }
                }
                if (snapshot.shortcuts.isNotEmpty()) {
                    Column {
                        SectionHeader(strings.homeShortcuts)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            snapshot.shortcuts.forEach { place ->
                                ShortcutPill(place) { onOpenPlace(place.id) }
                            }
                        }
                    }
                }
                if (snapshot.scanned) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    val library = snapshot.library
                    Text(
                        strings.homeLibraryLine(
                            library.rules,
                            library.skills,
                            library.mcpServers,
                            library.groups,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.padding(vertical = 2.dp),
            style = defaultScrollbarStyle(),
        )
    }
}

private val SOURCE_CHECK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())

private fun checkedAtLabel(value: String): String = runCatching {
    SOURCE_CHECK_TIME.format(Instant.parse(value))
}.getOrDefault(value)

/** Repository updates are a different decision from synchronizing the library's own Git history. */
@Composable
private fun SourceUpdatesCard(
    state: HomeSourceUpdates,
    strings: Strings,
    onCheck: () -> Unit,
    onUpdate: (Set<String>) -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    val actionable = state.rows.filter { it.status != SourceCheckSkillStatus.CURRENT }
    val hasProblems = actionable.any {
        it.status == SourceCheckSkillStatus.PATH_MISSING || it.status == SourceCheckSkillStatus.UNAVAILABLE
    } || state.failure != null
    val accent = when {
        hasProblems -> extras.danger
        state.updateIds.isNotEmpty() -> extras.warning
        state.report != null -> extras.success
        else -> extras.faint
    }
    CardFrame(accent = accent) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(accent)
                Text(strings.homeSourceUpdatesTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (state.updateIds.isNotEmpty()) {
                    CompactOutlinedButton(
                        enabled = !state.busy,
                        onClick = { onUpdate(state.updateIds) },
                        modifier = Modifier.testTag("home-source-update-all"),
                    ) {
                        Text(strings.homeSourceUpdateAll, style = MaterialTheme.typography.labelMedium)
                    }
                }
                CompactOutlinedButton(
                    enabled = !state.busy,
                    onClick = onCheck,
                    modifier = Modifier.testTag("home-source-check"),
                ) {
                    Text(strings.homeSourceCheckNow, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                when {
                    state.checking -> strings.homeSourceChecking(state.progressDone, state.progressTotal)
                    state.lastCheckedAt != null -> strings.homeSourceLastChecked(checkedAtLabel(state.lastCheckedAt))
                    else -> strings.homeSourceNeverChecked
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.failure?.let {
                Text(strings.homeSourceFailure(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.report != null && actionable.isEmpty()) {
                Text(strings.homeSourceNothing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actionable.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val subagent = skill.key.kind == LibraryObjectKind.SUBAGENT
                    KindMark(skill.key.kind)
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (subagent) state.subagentNames[skill.key.id]
                            else state.skillNames[skill.key.id]) ?: skill.key.id,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when (skill.status) {
                                SourceCheckSkillStatus.UPDATE_AVAILABLE -> strings.homeSourceUpdateAvailable
                                SourceCheckSkillStatus.PATH_MISSING -> strings.homeSourcePathMissing
                                // An unreachable repository is the one row whose cause is not
                                // obvious from the status alone, and the check already recorded it.
                                SourceCheckSkillStatus.UNAVAILABLE -> state.report?.perRepo
                                    ?.find { it.repository == skill.repository }?.error
                                    ?.let { "${strings.homeSourceUnavailable} — $it" }
                                    ?: strings.homeSourceUnavailable
                                SourceCheckSkillStatus.CURRENT -> strings.homeSourceNothing
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (skill.status == SourceCheckSkillStatus.UPDATE_AVAILABLE) {
                                extras.warning
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (skill.status == SourceCheckSkillStatus.UPDATE_AVAILABLE) {
                        CompactGhostButton(
                            enabled = !state.busy,
                            onClick = { onUpdate(setOf(skill.updateId)) },
                            modifier = Modifier.testTag("home-source-update-${skill.updateId}"),
                        ) {
                            Text(
                                if (skill.updateId in state.updatingSkillIds) strings.homeSourceUpdating else strings.homeSourceUpdate,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The bundled skill and the MCP entry, per assistant. Same frame as the source card next to it: an
 * update is an update, whether the text came from a Git repository or from this app's own resources.
 */
@Composable
private fun IntegrationUpdatesCard(
    state: HomeIntegrationUpdates,
    strings: Strings,
    onUpdate: (Set<String>) -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    val outdated = state.outdated
    val accent = when {
        state.blocked.isNotEmpty() || state.failure != null -> extras.danger
        outdated.isNotEmpty() -> extras.warning
        else -> extras.success
    }
    CardFrame(accent = accent) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(accent)
                Text(strings.homeIntegrationTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (outdated.isNotEmpty()) {
                    CompactOutlinedButton(
                        enabled = !state.busy,
                        onClick = { onUpdate(outdated.mapTo(mutableSetOf()) { it.agentId }) },
                        modifier = Modifier.testTag("home-integration-update-all"),
                    ) {
                        Text(strings.homeIntegrationUpdateAll, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            state.failure?.let {
                Text(strings.homeSourceFailure(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            (outdated + state.blocked).forEach { agent ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.agentNames[agent.agentId] ?: agent.agentId,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            agent.detail(strings),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (agent.blocked) MaterialTheme.colorScheme.error else extras.warning,
                        )
                    }
                    if (agent.outdated) {
                        CompactGhostButton(
                            enabled = !state.busy,
                            onClick = { onUpdate(setOf(agent.agentId)) },
                            modifier = Modifier.testTag("home-integration-update-${agent.agentId}"),
                        ) {
                            Text(
                                if (agent.agentId in state.updatingAgentIds) {
                                    strings.homeIntegrationUpdating
                                } else {
                                    strings.homeIntegrationUpdate
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What is behind, in versions rather than in adjectives; a foreign skill says so instead. */
private fun AgentIntegrationState.detail(strings: Strings): String {
    if (blocked) return strings.homeIntegrationForeign
    return listOfNotNull(
        this.skill?.takeIf { it.outdated }?.let { strings.setSkillVersion(it.installed ?: 0, it.current) },
        this.mcp?.takeIf { it.outdated }?.let { strings.setEntryVersion(it.installed ?: 0, it.current) },
    ).joinToString(" · ")
}

/**
 * The remote result is separate from target scans: a successful merge can still need review.
 *
 * The card says what it synchronises before it says how it went — "Synchronization" alone reads like
 * it could be about the assistants on the screen above it, and it is not: it is the library's own Git
 * peer. The button appears only where pressing it would do something; a settled automatic sync has
 * nothing to press, and the manual one still lives in Settings.
 */
@Composable
private fun SyncCard(
    result: RemoteSyncResult?,
    remote: RemoteGitConfig?,
    syncing: Boolean,
    strings: Strings,
    onSyncNow: () -> Unit,
    onOpenHistory: (RemoteSyncConflict) -> Unit,
    onOpenCompare: (RemoteSyncConflict) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    val failed = result?.status == RemoteSyncStatus.FAILED
    val conflicts = result?.conflicts.orEmpty()
    val accent = if (failed) extras.danger else if (conflicts.isNotEmpty()) extras.warning else extras.success
    val automatic = remote?.automatic ?: true
    val settled = result != null && !failed && !result.unrelatedHistories &&
        conflicts.isEmpty() && result.behind == 0
    val showSyncButton = syncing || !settled || !automatic
    CardFrame(accent = accent) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(accent)
                Text(strings.homeSyncTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (showSyncButton) {
                    CompactOutlinedButton(
                        enabled = !syncing,
                        onClick = onSyncNow,
                        modifier = Modifier.testTag(HomeSyncNowTag),
                    ) {
                        Text(if (syncing) strings.remoteGitSyncing else strings.remoteGitSyncNow, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Text(
                strings.homeSyncExplains,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            remote?.let {
                Text(
                    strings.homeSyncRemote(it.url, it.branch),
                    style = MaterialTheme.typography.labelSmall,
                    color = extras.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                remoteSyncLine(result, automatic, strings),
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Unrelated histories are not a transport failure and retrying can never clear them:
            // merging or replacing is the user's call, and Settings is where that choice lives.
            if (result?.unrelatedHistories == true) {
                CompactOutlinedButton(onClick = onOpenSettings) {
                    Text(strings.homeSyncOpenSettings, style = MaterialTheme.typography.labelMedium)
                }
            }
            result?.conflicts.orEmpty().forEach { conflict ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag(uiTarget("home", "${conflict.kind}:${conflict.id}", "sync", "conflict")),
                ) {
                    Text(conflict.id, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    CompactGhostButton(onClick = { onOpenHistory(conflict) }) { Text(strings.homeSyncHistory) }
                    CompactGhostButton(onClick = { onOpenCompare(conflict) }) { Text(strings.homeSyncCompare) }
                }
            }
            result?.conflicts?.count { it.restored }?.takeIf { it > 0 }?.let {
                Text(strings.homeSyncRestored(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** State, freshness and mode on one line — the same reading Settings gives, so they cannot disagree. */
private fun remoteSyncLine(result: RemoteSyncResult?, automatic: Boolean, strings: Strings): String {
    val parts = mutableListOf(remoteSyncText(result, strings))
    result?.takeIf { it.status != RemoteSyncStatus.FAILED }
        ?.let { parts += strings.remoteGitPushedAgo(ago(it.atEpochMillis, strings)) }
    parts += if (automatic) strings.homeSyncAutomatic else strings.homeSyncManual
    return parts.joinToString(" · ")
}

private fun remoteSyncText(result: RemoteSyncResult?, strings: Strings): String = when {
    result == null -> strings.remoteGitNotSynced
    result.unrelatedHistories -> strings.homeSyncUnrelated
    result.status == RemoteSyncStatus.FAILED -> strings.homeSyncFailed(result.failed ?: result.message.orEmpty())
    result.merged && result.conflicts.isNotEmpty() -> strings.homeSyncMergedConflicts(result.conflicts.size)
    result.merged -> strings.homeSyncMerged
    result.behind > 0 -> strings.homeSyncBehind(result.behind)
    result.status == RemoteSyncStatus.PUSHED -> strings.remoteGitPushed
    else -> strings.remoteGitUpToDate
}

/**
 * Home intentionally limits this panel to projects that need a choice: a single attached profile
 * is context, not a switchboard. The write still belongs to the existing project reconciler.
 */
@Composable
private fun ProfilesPanel(
    projects: List<HomeProfileProject>,
    change: HomeProfileChange?,
    switching: Pair<String, String>?,
    failure: String?,
    canSwitch: Boolean,
    strings: Strings,
    onSetProfileActive: (String, String, Boolean) -> Unit,
) {
    Column {
        SectionHeader(strings.homeProfiles)
        CardFrame(accent = MaterialTheme.colorScheme.primary) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                failure?.let { reason ->
                    Text(
                        strings.homeProfilesSwitchFailed(reason),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                projects.forEach { project ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        project.profiles.forEach { profile ->
                            val isSwitching = switching == project.projectKey to profile.id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = profile.active,
                                    enabled = canSwitch && switching == null,
                                    onCheckedChange = { active ->
                                        onSetProfileActive(project.projectKey, profile.id, active)
                                    },
                                    modifier = Modifier.testTag("home-profile-${profile.id}"),
                                )
                                KindMark(LibraryObjectKind.PROFILE)
                                Spacer(Modifier.width(6.dp))
                                Text(profile.name, style = MaterialTheme.typography.bodySmall)
                                if (isSwitching) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(strings.homeScanning, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (change?.projectKey == project.projectKey && project.restartAgents.isNotEmpty()) {
                            Text(
                                strings.homeProfilesRestartHint(project.restartAgents.joinToString()),
                                style = MaterialTheme.typography.labelMedium,
                                color = RuleblendTheme.extraColors.warning,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The feed, or the one state that replaces it. The three empty states are different answers and are
 * never collapsed into one: "not scanned yet" is ignorance, "the library is empty" is nothing to
 * install, and "nothing needs attention" is a scanned fleet in sync.
 */
@Composable
private fun AttentionFeed(
    snapshot: HomeSnapshot,
    strings: Strings,
    onOpenPlace: (String) -> Unit,
    onOpenResolve: () -> Unit,
    onOpenLibrary: () -> Unit,
    onScan: () -> Unit,
    onHideAgent: (String) -> Unit,
) {
    val extras = RuleblendTheme.extraColors
    when {
        !snapshot.scanned -> StateCard(
            accent = extras.faint,
            title = strings.homeNotScannedTitle,
            text = strings.homeNotScannedText,
            actionLabel = strings.homeScanNow,
            onAction = onScan,
        )

        snapshot.cards.isNotEmpty() -> snapshot.cards.forEach { card ->
            AttentionCardView(card, strings, onOpenPlace, onOpenResolve, onHideAgent)
        }

        snapshot.library.isEmpty -> StateCard(
            accent = MaterialTheme.colorScheme.primary,
            title = strings.homeEmptyLibraryTitle,
            text = strings.homeEmptyLibraryText,
            actionLabel = strings.homeOpenLibrary,
            onAction = onOpenLibrary,
        )

        snapshot.fleet.total == 0 -> StateCard(
            accent = extras.faint,
            title = strings.homeNoPlacesTitle,
            text = strings.homeNoPlacesText,
        )

        else -> StateCard(
            accent = extras.success,
            title = strings.homeAllClearTitle,
            text = strings.homeAllClearText + " " + strings.homeFleetSummary(
                snapshot.fleet.total,
                snapshot.fleet.clean,
                snapshot.fleet.withUpdates,
                snapshot.fleet.withConflicts,
            ),
        )
    }
}

/**
 * One class of problem. The headline carries the class total, the rows carry the places — a card
 * folded shut is still the whole answer, opening it only says where to start.
 */
@Composable
private fun AttentionCardView(
    card: AttentionCard,
    strings: Strings,
    onOpenPlace: (String) -> Unit,
    onOpenResolve: () -> Unit,
    onHideAgent: (String) -> Unit,
) {
    var open by remember(card.kind, card.agent?.id) { mutableStateOf(false) }
    CardFrame(accent = card.kind.accent()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (card.rows.isEmpty()) Modifier else Modifier.clickable { open = !open })
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        card.headline(strings),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        card.hint(strings),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CardActions(card, strings, onOpenPlace, onOpenResolve, onHideAgent)
                if (card.rows.isNotEmpty()) {
                    DisclosureArrow(open)
                }
            }
            if (open && card.rows.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(vertical = 4.dp)) {
                    card.rows.forEach { row -> AttentionRowView(card.kind, row, strings, onOpenPlace) }
                }
            }
        }
    }
}

@Composable
private fun CardActions(
    card: AttentionCard,
    strings: Strings,
    onOpenPlace: (String) -> Unit,
    onOpenResolve: () -> Unit,
    onHideAgent: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (card.kind) {
            // Bulk resolution is phase 8; until then the card leads to the surface that will own it.
            AttentionKind.CONFLICTS -> CompactButton(onClick = onOpenResolve) {
                Text(strings.homeResolveAll, style = MaterialTheme.typography.labelMedium)
            }

            AttentionKind.NEW_AGENT -> card.agent?.let { agent ->
                CompactButton(onClick = { onOpenPlace(agent.placeId) }) {
                    Text(strings.homeOpenPlace, style = MaterialTheme.typography.labelMedium)
                }
                CompactOutlinedButton(onClick = { onHideAgent(agent.id) }) {
                    Text(strings.homeHideAgent, style = MaterialTheme.typography.labelMedium)
                }
            }

            // Everything else is fixed one place at a time, so the action opens the worst place.
            else -> card.rows.firstOrNull()?.let { row ->
                CompactOutlinedButton(onClick = { onOpenPlace(row.placeId) }) {
                    Text(strings.homeReview, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AttentionRowView(
    kind: AttentionKind,
    row: AttentionRow,
    strings: Strings,
    onOpenPlace: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPlace(row.placeId) }
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 130.dp),
        )
        StatusBadge(kind.badge(), kind.unit(row.count, strings))
        if (row.files.isNotEmpty()) {
            Text(
                row.files.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FleetGroupRow(group: FleetGroup, strings: Strings, onOpenPlace: (String) -> Unit) {
    val title = when (group.kind) {
        FleetGroupKind.AGENTS -> strings.intAgents
        FleetGroupKind.SET -> group.name.orEmpty()
        FleetGroupKind.UNGROUPED -> strings.placeUngrouped
    }
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "${title.uppercase()} · ${group.places.size}",
            style = SectionHeaderStyle,
            color = RuleblendTheme.extraColors.faint,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.places.forEach { place -> FleetChip(place) { onOpenPlace(place.id) } }
        }
    }
}

/** One place as a chip: its name and how its installed objects split across the three statuses. */
@Composable
private fun FleetChip(place: FleetPlace, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(112.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, place.marks.leading().first().color()),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                place.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MiniBar(place)
        }
    }
}

/** The place's status mix at a glance; an empty place gets a flat neutral bar, not a green one. */
@Composable
private fun MiniBar(place: FleetPlace) {
    val extras = RuleblendTheme.extraColors
    Row(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (place.total == 0) {
            Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
            return@Row
        }
        listOf(
            place.synced to extras.badgeOkFg,
            place.updates to extras.badgeUpdFg,
            place.modified to extras.badgeModFg,
        ).filter { it.first > 0 }.forEach { (count, color) ->
            Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun ShortcutPill(place: FleetPlace, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(place.marks)
            Text(place.name, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ScanControls(
    scanning: Boolean,
    lastScanMillis: Long?,
    strings: Strings,
    onRescan: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val note = when {
            scanning -> strings.homeScanning
            lastScanMillis != null -> strings.homeScanDuration(lastScanMillis)
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                color = RuleblendTheme.extraColors.faint,
            )
        }
        CompactOutlinedButton(onClick = onRescan, enabled = !scanning) {
            Text(strings.homeRescan, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = SectionHeaderStyle)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Card body used by both the feed and the states that replace it: same frame, same accent stripe. */
@Composable
private fun CardFrame(accent: Color, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun StateCard(
    accent: Color,
    title: String,
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    CardFrame(accent) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Row(Modifier.padding(top = 6.dp)) {
                    CompactOutlinedButton(onClick = onAction) {
                        Text(actionLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ---- wording and colour of a class ----

private fun AttentionCard.headline(strings: Strings): String = when (kind) {
    AttentionKind.CONFLICTS -> strings.homeConflicts(total, places)
    AttentionKind.UPDATES -> strings.homeUpdates(total, places)
    AttentionKind.UNADOPTED -> strings.homeUnadopted(places)
    AttentionKind.LEGACY -> strings.homeLegacy(total, places)
    AttentionKind.NEW_AGENT -> strings.homeNewAgent(agent?.name.orEmpty())
}

private fun AttentionCard.hint(strings: Strings): String = when (kind) {
    AttentionKind.CONFLICTS -> strings.homeConflictsHint
    AttentionKind.UPDATES -> strings.homeUpdatesHint(worst())
    AttentionKind.UNADOPTED -> strings.homeUnadoptedHint(total)
    AttentionKind.LEGACY -> strings.homeLegacyHint
    AttentionKind.NEW_AGENT -> agent
        ?.let { strings.homeNewAgentHint(it.globalFile, it.eligibleProjects) }
        .orEmpty()
}

/** Rows arrive worst first, so the hint is the head of the same list, not a second ranking. */
private fun AttentionCard.worst(limit: Int = 3): String =
    rows.take(limit).joinToString(", ") { "${it.name} (${it.count})" }

@Composable
private fun AttentionKind.accent(): Color {
    val extras = RuleblendTheme.extraColors
    return when (this) {
        AttentionKind.CONFLICTS -> extras.badgeModFg
        AttentionKind.UPDATES -> extras.badgeUpdFg
        AttentionKind.UNADOPTED -> MaterialTheme.colorScheme.primary
        AttentionKind.LEGACY -> extras.warning
        AttentionKind.NEW_AGENT -> extras.faint
    }
}

private fun AttentionKind.badge(): SyncBadge = when (this) {
    AttentionKind.CONFLICTS -> SyncBadge.MODIFIED
    AttentionKind.UPDATES -> SyncBadge.UPDATE
    else -> SyncBadge.SYNCED
}

private fun AttentionKind.unit(count: Int, strings: Strings): String = when (this) {
    AttentionKind.CONFLICTS -> strings.homeRowModified(count)
    AttentionKind.UPDATES -> strings.homeRowUpdates(count)
    AttentionKind.UNADOPTED -> strings.homeRowLines(count)
    AttentionKind.LEGACY -> strings.homeRowFiles(count)
    AttentionKind.NEW_AGENT -> count.toString()
}
