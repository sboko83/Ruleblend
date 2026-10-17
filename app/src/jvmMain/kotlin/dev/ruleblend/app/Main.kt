package dev.ruleblend.app

import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Taskbar
import javax.imageio.ImageIO
import dev.ruleblend.app.i18n.LocalStrings
import dev.ruleblend.app.i18n.Language
import dev.ruleblend.app.assistance.AssistanceSessions
import dev.ruleblend.app.assistance.AssistanceAction
import dev.ruleblend.app.assistance.AssistanceState
import dev.ruleblend.app.assistance.AssistanceButton
import dev.ruleblend.app.assistance.assistanceObject
import dev.ruleblend.app.assistance.assistanceDraftDirty
import dev.ruleblend.app.assistance.prepareClaudeAssistanceLaunch
import dev.ruleblend.app.assistance.LocalAssistanceSessions
import dev.ruleblend.app.i18n.Strings
import dev.ruleblend.app.i18n.stringsFor
import dev.ruleblend.app.command.CommandItem
import dev.ruleblend.app.command.CommandKind
import dev.ruleblend.app.command.CommandPalette
import dev.ruleblend.app.command.CommandTarget
import dev.ruleblend.app.command.commandIndex
import dev.ruleblend.app.command.rankCommands
import dev.ruleblend.app.coverage.CoverageModel
import dev.ruleblend.app.coverage.CoverageScreen
import dev.ruleblend.app.home.HomeModel
import dev.ruleblend.app.home.HomeScreen
import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.integration.IntegrationModel
import dev.ruleblend.app.library.builtInLibraryObjects
import dev.ruleblend.app.library.LibraryModel
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryScreen
import dev.ruleblend.app.library.LibraryPlaceInstallService
import dev.ruleblend.app.library.LibraryUsageScanner
import dev.ruleblend.app.library.TranslationModel
import dev.ruleblend.core.translate.AppleTranslator
import dev.ruleblend.core.translate.Translator
import dev.ruleblend.app.navigation.AppRoute
import dev.ruleblend.app.navigation.AppShell
import dev.ruleblend.app.place.PlaceScreen
import dev.ruleblend.app.place.placeId
import dev.ruleblend.app.place.placeSections
import dev.ruleblend.app.resolve.ResolveModel
import dev.ruleblend.app.resolve.ResolvePlaceService
import dev.ruleblend.app.resolve.ResolveScreen
import dev.ruleblend.app.settings.SettingsModel
import dev.ruleblend.app.settings.SettingsScreen
import dev.ruleblend.app.settings.SettingsSection
import dev.ruleblend.app.theme.CompactGhostButton
import dev.ruleblend.app.util.revealInFileManager
import dev.ruleblend.app.theme.RuleblendDialog
import dev.ruleblend.app.theme.RuleblendContextMenuRepresentation
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.WindowGeometry
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.subagentAgentFields
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.KimiCodeAdapter
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.integration.TargetIndex
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.app.build.BuildInfo
import dev.ruleblend.core.storage.BackupService
import dev.ruleblend.core.storage.GitSkillImporter
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.core.usecase.CheckSources
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.storage.LegacyHomeMigration
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.RemoteSyncConflict
import dev.ruleblend.core.storage.RemoteSyncConflictKind
import dev.ruleblend.mcp.ClaudeCodeConnector
import dev.ruleblend.mcp.CodexConnector
import dev.ruleblend.mcp.KimiCodeConnector
import dev.ruleblend.mcp.PiConnector
import dev.ruleblend.mcp.ZCodeConnector
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.CodexMcpInstaller
import dev.ruleblend.mcp.install.KimiCodeMcpInstaller
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.PiMcpInstaller
import dev.ruleblend.mcp.install.ZCodeMcpInstaller
import dev.ruleblend.mcp.BundledIntegration
import dev.ruleblend.mcp.McpLauncher
import dev.ruleblend.mcp.install.McpStateStore
import dev.ruleblend.mcp.runRuleblendServer
import dev.ruleblend.mcp.skill.ClaudeCodeSkillInstaller
import dev.ruleblend.mcp.skill.CodexSkillInstaller
import dev.ruleblend.mcp.skill.KimiCodeSkillInstaller
import dev.ruleblend.mcp.skill.PiSkillInstaller
import dev.ruleblend.mcp.skill.ZCodeSkillInstaller
import java.nio.file.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val USER_HOME: Path = Path.of(System.getProperty("user.home"))
private val LEGACY_KITBASH_HOME: Path = USER_HOME.resolve(".kitbash")
private val RULEBLEND_HOME: Path = USER_HOME.resolve(".ruleblend")
private val DEFAULT_LIBRARY_PATH: Path = RULEBLEND_HOME.resolve("library")
private val CONFIG_PATH: Path = RULEBLEND_HOME.resolve("config.json")
private val BACKUPS_PATH: Path = RULEBLEND_HOME.resolve("backups")

/** Launcher the agents run on macOS and Linux, shared by installed and dev builds; see [McpLauncher]. */
private val MCP_LAUNCHER_PATH: Path = RULEBLEND_HOME.resolve("bin").resolve("ruleblend-mcp")

/** Generated terminal launchers for agent CLIs; disposable, rewritten on every launch. */
private val LAUNCHERS_PATH: Path = RULEBLEND_HOME.resolve("launchers")

/** Class the dev wrapper re-launches. Keep in sync with `mainClass` in app/build.gradle.kts. */
private const val MAIN_CLASS: String = "dev.ruleblend.app.MainKt"

/**
 * Serializes library and config mutations against the other Ruleblend process — the GUI app and the
 * MCP server may run at the same time.
 */
private val interProcessLock = InterProcessLock(RULEBLEND_HOME.resolve("ruleblend.lock"))

/** The same lock over target files, agent MCP configs and their sidecar state. */
private val targetCoordinator = TargetMutationCoordinator(interProcessLock)

/** Startup steps shared by the window and the opt-in root UI suite, so both use one service graph. */
internal fun migrateLegacyHome() = LegacyHomeMigration.migrate(LEGACY_KITBASH_HOME, RULEBLEND_HOME)

internal fun openConfigStore(): ConfigStore = ConfigStore(CONFIG_PATH, interProcessLock)

// Lazy on purpose: `--mcp` mode must never touch AWT/ImageIO.
private val appIconImage by lazy {
    runCatching {
        ImageIO.read(object {}.javaClass.getResourceAsStream("/icons/AppIcon.png"))
    }.getOrNull()
}
private val appIcon by lazy { appIconImage?.let { BitmapPainter(it.toComposeImageBitmap()) } }

/** Sets the macOS menu bar app name and dock icon; matters when running unbundled (e.g. from an IDE). */
private fun applyMacOsIdentity() {
    System.setProperty("apple.awt.application.name", "Ruleblend")
    // Without this, AWT/Swing windows (e.g. the native folder-picker FileDialog) default to light
    // appearance regardless of the system theme.
    System.setProperty("apple.awt.application.appearance", "system")
    if (Taskbar.isTaskbarSupported()) {
        val taskbar = Taskbar.getTaskbar()
        if (appIconImage != null && taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = appIconImage
    }
}

fun main(args: Array<String>) {
    migrateLegacyHome()
    // MCP mode: the same binary is the stdio MCP server. Must branch before any AWT/Compose touch —
    // stdout belongs to the protocol and no window may open.
    if ("--mcp" in args) {
        val libraryArg = args.indexOf("--library").takeIf { it >= 0 }
        val libraryPath = libraryArg?.let { index ->
            require(index + 1 < args.size && args[index + 1].isNotBlank()) { "--library requires a path" }
            Path.of(args[index + 1])
        }
        runRuleblendServer(BuildInfo.VERSION, libraryPath)
        return
    }
    applyMacOsIdentity()
    System.setProperty("compose.interop.blending", "true")
    application {
        val configStore = remember { openConfigStore() }
        val assistance = remember { AssistanceSessions() }
        val assistanceState by assistance.state.collectAsState()
        val assistanceScope = rememberCoroutineScope()
        var assistanceCloseError by remember { mutableStateOf<String?>(null) }
        fun closeApplication() {
            assistanceScope.launch {
                runCatching {
                    if (assistance.requestClose(exitApplication = true)) {
                        assistance.shutdown()
                        exitApplication()
                    }
                }.onFailure { assistanceCloseError = it.message ?: it.toString() }
            }
        }
        DisposableEffect(Unit) {
            val desktop = if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() else null
            val handlesQuit = desktop?.isSupported(java.awt.Desktop.Action.APP_QUIT_HANDLER) == true
            if (handlesQuit) desktop.setQuitHandler { _, response ->
                response.cancelQuit()
                closeApplication()
            }
            onDispose { if (handlesQuit) desktop.setQuitHandler(null) }
        }
        var themeMode by remember { mutableStateOf(configStore.load().themeMode) }
        // The frame is the user's, once they have set one: only a first run is sized from the screen.
        val savedWindow = remember { configStore.load().window?.let { restoreWindow(it, usableScreens()) } }
        val windowState = rememberWindowState(
            position = savedWindow?.takeIf { it.x != null && it.y != null }
                ?.let { WindowPosition.Absolute(it.x!!.dp, it.y!!.dp) }
                ?: WindowPosition.Aligned(Alignment.Center),
            // A frame stored by an older build — or by a screen that is no longer attached — can be
            // narrower than the layout needs, so it is raised to the floor before it is shown.
            size = savedWindow?.let {
                DpSize(it.width.dp.coerceAtLeast(MinWindowWidth), it.height.dp.coerceAtLeast(MinWindowHeight))
            } ?: defaultWindowSize(),
        )
        RememberWindowFrame(windowState, configStore)
        Window(onCloseRequest = ::closeApplication, title = "Ruleblend", icon = appIcon, state = windowState) {
            // The floor is enforced by the window manager rather than by the layout: below it the
            // centre column is under its own minimum and both side panes are already at theirs, so
            // there is nothing left for the layout to give up.
            LaunchedEffect(window) {
                window.minimumSize = Dimension(MinWindowWidth.value.toInt(), MinWindowHeight.value.toInt())
            }
            RuleblendTheme(themeMode) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalAssistanceSessions provides assistance) {
                        App(configStore = configStore, onThemeModeChanged = { themeMode = it })
                    }
                }
                val strings = stringsFor(Language.fromCode(configStore.load().language))
                assistanceState.confirmation?.takeIf { it.action == AssistanceAction.ExitApplication }?.let { confirmation ->
                    RuleblendDialog(
                        title = strings.assistanceCloseConfirm(confirmation.session.launch.assistant.name),
                        confirmLabel = strings.actionClose,
                        dismissLabel = strings.actionCancel,
                        destructive = true,
                        onDismiss = { assistanceScope.launch { assistance.cancel(confirmation) } },
                        onConfirm = {
                            assistanceScope.launch {
                                runCatching {
                                    if (assistance.confirm(confirmation)) {
                                        assistance.shutdown()
                                        exitApplication()
                                    }
                                }.onFailure { assistanceCloseError = it.message ?: it.toString() }
                            }
                        },
                    )
                }
                assistanceCloseError?.let { error ->
                    RuleblendDialog(
                        onDismiss = { assistanceCloseError = null },
                        title = strings.assistanceCloseFailed,
                        text = error,
                        confirmLabel = strings.actionOk,
                        onConfirm = { assistanceCloseError = null },
                    )
                }
            }
        }
    }
}

/**
 * First run has no stored frame: 70% of the usable screen area, which keeps the app large enough for
 * the wide surfaces (Coverage, Place) without covering the desktop.
 */
private fun defaultWindowSize(): DpSize {
    val bounds = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
        .getOrNull() ?: return DpSize(MinWindowWidth, MinWindowHeight)
    return DpSize(
        (bounds.width * FirstRunScreenFraction).dp.coerceAtLeast(MinWindowWidth),
        (bounds.height * FirstRunScreenFraction).dp.coerceAtLeast(MinWindowHeight),
    )
}

private const val FirstRunScreenFraction = 0.7

/**
 * The narrowest window the three-column surfaces still read in: the rail, a centre column at
 * [dev.ruleblend.app.navigation.CentreMinWidth], and both side panes at
 * [dev.ruleblend.app.navigation.PaneMinWidth], with a little left over so a pane is not pinned to
 * its minimum the moment the window is resized.
 */
internal val MinWindowWidth = 900.dp
internal val MinWindowHeight = 600.dp

/** Debounce: a drag or a resize is a stream of states, and only the one it settles on is worth a write. */
private const val WindowSaveDelayMillis = 400L

/** The managed library object and place that handed it to the Library editor. */
private data class PlaceLibraryEdit(val key: LibraryObjectKey, val placeId: String)

/** Stores the window frame as the user leaves it, so the next launch opens where the last one closed. */
@Composable
private fun RememberWindowFrame(windowState: WindowState, configStore: ConfigStore) {
    LaunchedEffect(windowState, configStore) {
        snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
            .drop(1)
            .collectLatest { (size, position, placement) ->
                // A maximized or minimized window is a mode, not a frame the user chose.
                if (placement != WindowPlacement.Floating || windowState.isMinimized) return@collectLatest
                if (!size.isSpecified) return@collectLatest
                delay(WindowSaveDelayMillis)
                val absolute = position as? WindowPosition.Absolute
                configStore.update {
                    it.copy(
                        window = WindowGeometry(
                            width = size.width.value.roundToInt(),
                            height = size.height.value.roundToInt(),
                            x = absolute?.x?.value?.roundToInt(),
                            y = absolute?.y?.value?.roundToInt(),
                        ),
                    )
                }
            }
    }
}

@Composable
internal fun App(
    configStore: ConfigStore,
    onThemeModeChanged: (ThemeMode) -> Unit,
    createTranslator: () -> Translator = { AppleTranslator() },
    focusSyncNow: () -> Long = System::currentTimeMillis,
    focusEvents: Flow<Unit> = emptyFlow(),
) {
    val assistance = LocalAssistanceSessions.current
    val assistanceState = assistance?.state?.collectAsState()?.value ?: AssistanceState()
    var assistanceFailure by remember { mutableStateOf<String?>(null) }
    val libraryPath = remember { configStore.load().libraryPath?.let(Path::of) ?: DEFAULT_LIBRARY_PATH }
    val libraryGit = remember {
        LibraryGit(libraryPath, interProcessLock, remoteConfig = { configStore.load().remoteGit },
            lineEnding = { configStore.load().lineEnding })
    }
    val repository = remember { LibraryRepository(libraryPath, libraryGit) }
    val archive = remember { LibraryArchive(libraryPath, repository) }
    val gitSkillImporter = remember { GitSkillImporter() }
    val importedSkills = remember {
        ImportLibrary(repository, archive, configStore, gitSkillImporter)
    }
    val agents = remember { listOf(ClaudeCodeAdapter(), CodexAdapter(), PiAdapter(), KimiCodeAdapter(), ZCodeAdapter()) }
    val connectors = remember {
        listOf(
            ClaudeCodeConnector(coordinator = targetCoordinator),
            CodexConnector(coordinator = targetCoordinator),
            KimiCodeConnector(coordinator = targetCoordinator),
            ZCodeConnector(coordinator = targetCoordinator),
            PiConnector(coordinator = targetCoordinator),
        )
    }
    val skillInstallers = remember {
        listOf(
            ClaudeCodeSkillInstaller(),
            CodexSkillInstaller(),
            KimiCodeSkillInstaller(),
            ZCodeSkillInstaller(),
            PiSkillInstaller(),
        )
    }
    val mcpLauncher = remember { McpLauncher(MCP_LAUNCHER_PATH, MAIN_CLASS) }
    val mcpLaunchResult = remember { runCatching { mcpLauncher.resolve() } }
    val mcpLaunch = mcpLaunchResult.getOrNull()
    // What every assistant holds of ours. Home reports it, Settings repairs it; one reader for both.
    val bundledIntegration = remember(mcpLaunch) {
        BundledIntegration(connectors, skillInstallers, mcpLaunch?.command)
    }
    // One service instance per concern, shared by Place and by the Library usage scan: both must
    // read the same managed regions and the same install records.
    val integrationService = remember {
        IntegrationService(
            TargetIndex(RULEBLEND_HOME.resolve("targets.index"), targetCoordinator),
            repository::loadBlock,
            targetCoordinator,
            lineEnding = { configStore.load().lineEnding },
        )
    }
    val mcpService = remember {
        McpInstallService(
            installers = listOf(
                ClaudeCodeMcpInstaller(coordinator = targetCoordinator),
                CodexMcpInstaller(coordinator = targetCoordinator),
                KimiCodeMcpInstaller(coordinator = targetCoordinator),
                ZCodeMcpInstaller(coordinator = targetCoordinator),
                PiMcpInstaller(coordinator = targetCoordinator),
            ),
            state = McpStateStore(coordinator = targetCoordinator),
            coordinator = targetCoordinator,
        )
    }
    val skillService = remember {
        SkillInstallService(
            SkillInstallStateStore(coordinator = targetCoordinator),
            repository::loadSkillSnapshot,
            agentById = { id -> agents.firstOrNull { it.id == id } },
            coordinator = targetCoordinator,
        )
    }
    val subagentService = remember {
        SubagentInstallService(SubagentInstallStateStore(coordinator = targetCoordinator), targetCoordinator)
    }
    val library = remember {
        LibraryModel(
            repository = repository,
            archive = archive,
            configStore = configStore,
            gitSkillImporter = gitSkillImporter,
            usageSource = LibraryUsageScanner(configStore, agents, integrationService, mcpService, skillService, subagentService),
            installer = LibraryPlaceInstallService(configStore, agents, integrationService, mcpService, skillService, subagentService),
            builtIns = builtInLibraryObjects(mcpLaunch?.command),
            subagentAgents = agents.subagentAgentFields(),
            modRuntime = dev.ruleblend.core.integration.ClaudeModRuntime(RULEBLEND_HOME.resolve("mod-runtime")),
        )
    }
    // macOS-only: elsewhere the translator reports itself unavailable and the panel never appears.
    val translator = remember { createTranslator() }
    val translationScope = rememberCoroutineScope()
    val translation = remember { TranslationModel(translator, configStore, translationScope) }
    DisposableEffect(translator) { onDispose { (translator as? AppleTranslator)?.close() } }
    val integration = remember {
        IntegrationModel(
            repository = repository,
            configStore = configStore,
            agents = agents,
            backupService = BackupService(BACKUPS_PATH),
            service = integrationService,
            mcpService = mcpService,
            skillService = skillService,
            subagentService = subagentService,
            connectors = connectors,
            mcpLaunch = mcpLaunch,
            usageSource = LibraryUsageScanner(configStore, agents, integrationService, mcpService, skillService, subagentService),
            launcherDirectory = LAUNCHERS_PATH,
        )
    }
    val coverage = remember {
        CoverageModel(
            repository = repository,
            configStore = configStore,
            agents = agents,
            usageSource = LibraryUsageScanner(configStore, agents, integrationService, mcpService, skillService, subagentService),
            installer = LibraryPlaceInstallService(configStore, agents, integrationService, mcpService, skillService, subagentService),
        )
    }
    val resolve = remember {
        ResolveModel(
            repository = repository,
            usageSource = LibraryUsageScanner(configStore, agents, integrationService, mcpService, skillService, subagentService),
            applier = ResolvePlaceService(configStore, agents, integrationService, repository),
        )
    }
    val home = remember {
        HomeModel(
            repository = repository,
            configStore = configStore,
            libraryGit = libraryGit,
            agents = agents,
            usageSource = LibraryUsageScanner(configStore, agents, integrationService, mcpService, skillService, subagentService),
            checkImportedSkillSources = CheckSources(repository)::check,
            sourceStateStore = SourcesStateStore(RULEBLEND_HOME),
            updateImportedPackages = { skills, subagents, mods -> importedSkills.updateDefinitions(skills, subagents, mods) },
            integration = bundledIntegration,
            setProfileActive = { projectKey, profileId, active ->
                integration.setProfileActive(projectKey, profileId, active)
            },
        )
    }
    var compareRemoteConflict by remember { mutableStateOf<dev.ruleblend.app.library.LibraryObjectKey?>(null) }
    // Home can name a conflicting object before Library has ever read the catalog, so the request
    // waits here and the Library route applies it once the objects it has to match against exist.
    var pendingRemoteConflict by remember { mutableStateOf<Pair<RemoteSyncConflict, Boolean>?>(null) }
    fun remoteConflictKey(conflict: RemoteSyncConflict) = library.catalog.objects.firstOrNull { item ->
        item.key.id == conflict.id && when (conflict.kind) {
            RemoteSyncConflictKind.BLOCK -> item.key.kind in setOf(
                dev.ruleblend.app.library.LibraryObjectKind.RULE,
                dev.ruleblend.app.library.LibraryObjectKind.MCP,
                dev.ruleblend.app.library.LibraryObjectKind.SUBAGENT,
            )
            RemoteSyncConflictKind.GROUP -> item.key.kind == dev.ruleblend.app.library.LibraryObjectKind.GROUP
            RemoteSyncConflictKind.PROFILE, RemoteSyncConflictKind.MOD -> false
            RemoteSyncConflictKind.SKILL -> item.key.kind == dev.ruleblend.app.library.LibraryObjectKind.SKILL
        }
    }?.key
    val settings = remember {
        SettingsModel(
            configStore = configStore,
            defaultLibraryPath = DEFAULT_LIBRARY_PATH,
            libraryGit = libraryGit,
            // The library this process actually opened, so a changed path can say "after restart".
            activeLibraryPath = libraryPath,
            configPath = CONFIG_PATH,
            agents = agents,
            connectors = connectors,
            skillInstallers = skillInstallers,
            // Installed binary or a generated dev launcher; preparation failures are shown in Settings.
            launch = mcpLaunch,
            launchFailure = mcpLaunchResult.exceptionOrNull()?.let { it.message ?: it.toString() },
            claimLaunch = mcpLauncher::claim.takeIf { mcpLaunch?.serving != null },
            translator = translator,
            onThemeModeChanged = onThemeModeChanged,
        )
    }
    var route by remember { mutableStateOf(AppRoute.HOME) }
    // One-shot: a link into a Settings section opens it once; any later visit starts at the top.
    var settingsSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    var placeLibraryEdit by remember { mutableStateOf<PlaceLibraryEdit?>(null) }
    val appScope = rememberCoroutineScope()
    // Every way into a place goes through here: the fleet is re-read from disk, so the click returns
    // at once and the reading happens in a coroutine instead of on the frame that handled it.
    var focusChangesForPlace by remember { mutableStateOf<String?>(null) }
    fun openPlace(place: String, focusChanges: Boolean = false) {
        appScope.launch {
            integration.load()
            integration.selectPlace(place)
            focusChangesForPlace = place.takeIf { focusChanges }
            route = AppRoute.PLACE
        }
    }
    var commandsOpen by remember { mutableStateOf(false) }
    var commandQuery by remember { mutableStateOf("") }
    // A config the build cannot parse is moved aside on the first read rather than taking the window
    // down. Saying so once is the whole notice: the settings are back at their defaults and the
    // user's own file is still on disk under a name the message names.
    var brokenConfig by remember { mutableStateOf(configStore.quarantined) }

    LaunchedEffect(Unit) {
        settings.load()
        home.checkSkillSourcesOnLaunch()
    }
    // The remote worker is best-effort and never holds the first frame: an automatic remote is
    // checked once per launch before any screen decides what it needs to render.
    LaunchedEffect(libraryGit) {
        if (libraryGit.scheduleAutomaticSyncOnLaunch()) {
            withContext(Dispatchers.IO) { libraryGit.awaitRemoteSyncSafely() }
            home.refreshRemoteSync()
            settings.refreshRemoteSync()
        }
    }
    // Settings owns the translation config; the panel keeps its own copy so it does not parse the
    // config file per frame. Re-read it whenever Settings writes, so switching translation off — or
    // changing the pair — reaches the editors without a restart.
    LaunchedEffect(settings.translation) { translation.reloadConfig() }
    // The palette searches the library, so it needs the library read: opening ⌘K is the trigger, the
    // same load Library itself performs and nothing more.
    LaunchedEffect(commandsOpen) { if (commandsOpen) library.load() }
    LaunchedEffect(route) {
        if (route != AppRoute.LIBRARY) placeLibraryEdit = null
        when (route) {
            // The scan is the expensive part, so Home runs it on entry and then only on request.
            AppRoute.HOME -> {
                home.refreshRemoteSync()
                home.refreshSourceUpdates()
                home.refreshIntegration()
                if (!home.snapshot.scanned) home.rescan()
            }
            AppRoute.LIBRARY -> {
                library.load()
                library.refreshUsage()
                home.refreshSourceUpdates()
                pendingRemoteConflict?.let { (conflict, compare) ->
                    pendingRemoteConflict = null
                    remoteConflictKey(conflict)?.let { key ->
                        library.selectCatalogObject(key)
                        if (compare) compareRemoteConflict = key
                    }
                }
            }
            AppRoute.PLACE -> integration.load()
            // The matrix reads the same fleet Home does, and just as expensively: scan on entry once.
            AppRoute.COVERAGE -> if (!coverage.snapshot.scanned) coverage.rescan()
            // Resolve reads the same fleet: one scan on entry, then only on request.
            AppRoute.RESOLVE -> if (!resolve.snapshot.scanned) resolve.rescan()
            // Settings reports the library's size next to its path — the same read Library does.
            AppRoute.SETTINGS -> {
                library.load()
                settings.refreshAssistanceAssistants()
                settings.refreshRemoteSync()
                settings.refreshAgentVisibility()
                home.refreshSourceUpdates()
            }
        }
    }

    // The MCP server may have changed the library or config while the window was in the background;
    // both models re-read disk on load, so regaining focus is the cheap moment to catch up.
    val windowInfo = LocalWindowInfo.current
    // An embedded CLI writes through MCP without changing window focus. Reuse the normal disk load
    // while Library and its terminal are visible; clean editors follow disk, dirty drafts survive.
    LaunchedEffect(route, assistanceState.session?.terminal) {
        val terminal = assistanceState.session?.terminal ?: return@LaunchedEffect
        if (route != AppRoute.LIBRARY) return@LaunchedEffect
        while (terminal.isAlive) {
            kotlinx.coroutines.delay(1_000)
            val beforeBlocks = library.blocks
            val beforeSkills = library.skills
            library.load()
            if (beforeBlocks != library.blocks || beforeSkills != library.skills) library.refreshUsage()
        }
        library.load()
    }
    LaunchedEffect(Unit) {
        merge(snapshotFlow { windowInfo.isWindowFocused }.drop(1).filter { it }.map { Unit }, focusEvents)
            .collect {
                if (libraryGit.scheduleAutomaticSyncOnWindowFocus(focusSyncNow())) {
                    appScope.launch {
                        withContext(Dispatchers.IO) { libraryGit.awaitRemoteSyncSafely() }
                        home.refreshRemoteSync()
                        settings.refreshRemoteSync()
                    }
                }
                when (route) {
                    AppRoute.HOME -> {
                        home.refreshRemoteSync()
                        home.refreshSourceUpdates()
                        home.rescan()
                    }
                    AppRoute.LIBRARY -> {
                        library.load()
                        library.refreshUsage()
                        home.refreshSourceUpdates()
                    }
                    AppRoute.PLACE -> integration.load()
                    AppRoute.COVERAGE -> coverage.rescan()
                    AppRoute.RESOLVE -> resolve.rescan()
                    AppRoute.SETTINGS -> {
                        settings.refreshRemoteSync()
                        home.refreshSourceUpdates()
                    }
                }
            }
    }

    val strings = stringsFor(settings.language)
    val commandActions = remember(strings) {
        listOf(AppRoute.HOME, AppRoute.PLACE, AppRoute.LIBRARY, AppRoute.COVERAGE, AppRoute.RESOLVE, AppRoute.SETTINGS)
            .map { destination ->
                CommandItem(
                    id = "route:${destination.name}",
                    kind = CommandKind.ACTION,
                    title = strings.commandGoTo(destination.label(strings)),
                    target = CommandTarget.Surface(destination),
                )
            }
    }
    // Rebuilt while the palette is open and dropped when it closes: the index is a view over the
    // library and the place board, not a third copy of them kept alive in the background.
    val commandItems = remember(commandsOpen, commandActions, library.blocks, library.skills, library.groups) {
        if (!commandsOpen) {
            emptyList()
        } else {
            val config = configStore.load()
            commandIndex(
                catalog = library.catalog,
                places = placeSections(configuredTargets(config, agents), config.places),
                actions = commandActions,
            )
        }
    }
    val commandRows = remember(commandItems, commandQuery) { rankCommands(commandItems, commandQuery) }
    val onCommandSelect: (CommandItem) -> Unit = { item ->
        commandsOpen = false
        when (val target = item.target) {
            is CommandTarget.Surface -> route = target.route
            is CommandTarget.Place -> openPlace(target.id)
            // Selection first, route second: entering Library re-reads the library but keeps a
            // selection that still exists.
            is CommandTarget.Library -> {
                library.selectCatalogObject(target.key)
                route = AppRoute.LIBRARY
            }
        }
    }

    var sidebarExpanded by remember { mutableStateOf(configStore.load().sidebarExpanded) }

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalContextMenuRepresentation provides RuleblendContextMenuRepresentation(),
    ) {
        Box(Modifier.fillMaxSize()) {
            AppShell(
                route = route,
                labelFor = { it.label(strings) },
                // A chord pressed over the palette is an answer to it: the palette gives way.
                onNavigate = {
                    focusChangesForPlace = null
                    route = it
                    commandsOpen = false
                },
                sidebarExpanded = sidebarExpanded,
                onSidebarExpandedChange = { expanded ->
                    sidebarExpanded = expanded
                    configStore.update { it.copy(sidebarExpanded = expanded) }
                },
                onOpenCommands = {
                    commandQuery = ""
                    commandsOpen = true
                },
                // Settings is the only surface whose subject is a folder on this machine.
                assistanceSession = assistanceState.session,
                onCloseAssistance = {
                    appScope.launch {
                        runCatching { assistance?.requestClose() }
                            .onFailure { assistanceFailure = it.message ?: it.toString() }
                    }
                },
                topBarAction = if (route == AppRoute.LIBRARY && assistance != null) {
                    {
                        AssistanceButton(
                            target = library.assistanceObject(),
                            dirty = library.assistanceDraftDirty(),
                            settings = settings,
                            onConfigure = { settingsSection = SettingsSection.AGENTS; route = AppRoute.SETTINGS },
                            onLaunch = { target, assistant ->
                                appScope.launch {
                                    runCatching {
                                        assistance.open(prepareClaudeAssistanceLaunch(
                                            assistant, target, settings.language, libraryPath.toAbsolutePath(),
                                        ))
                                    }.onFailure { assistanceFailure = it.message ?: it.toString() }
                                }
                            },
                            onFailure = { assistanceFailure = it },
                        )
                    }
                } else if (route != AppRoute.SETTINGS) null else {
                    {
                        CompactGhostButton(
                            onClick = { revealInFileManager(CONFIG_PATH) },
                            modifier = Modifier.testTag("settings-reveal-config"),
                        ) {
                            Text(strings.setOpenConfigFolder, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
                overlay = if (!commandsOpen) null else {
                    {
                        CommandPalette(
                            items = commandRows,
                            query = commandQuery,
                            onQueryChange = { commandQuery = it },
                            onSelect = onCommandSelect,
                            onDismiss = { commandsOpen = false },
                        )
                    }
                },
            ) {
                when (route) {
                    AppRoute.HOME -> HomeScreen(
                        model = home,
                        // Every card row is a place; the feed is only an answer if it leads into one.
                        onOpenPlace = { place -> openPlace(place, focusChanges = true) },
                        onOpenResolve = { route = AppRoute.RESOLVE },
                        onOpenLibrary = { route = AppRoute.LIBRARY },
                        onOpenHistory = { conflict ->
                            pendingRemoteConflict = conflict to false
                            route = AppRoute.LIBRARY
                        },
                        onOpenCompare = { conflict ->
                            pendingRemoteConflict = conflict to true
                            route = AppRoute.LIBRARY
                        },
                        onOpenSettings = { route = AppRoute.SETTINGS },
                        onOpenAssistantSettings = {
                            settingsSection = SettingsSection.AGENTS
                            route = AppRoute.SETTINGS
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppRoute.PLACE -> PlaceScreen(
                        model = integration,
                        modUi = library.modUi,
                        focusChanges = focusChangesForPlace != null &&
                            integration.selected?.let(::placeId) == focusChangesForPlace,
                        onEditBlock = { key ->
                            integration.selected?.let { target ->
                                appScope.launch {
                                    library.load()
                                    library.selectCatalogObject(key)
                                    placeLibraryEdit = PlaceLibraryEdit(key, placeId(target))
                                    route = AppRoute.LIBRARY
                                }
                            }
                        },
                        useExternalEditor = settings.useExternalEditor,
                        externalEditor = settings.externalEditorPath,
                        groupsExpandedByDefault = settings.groupsExpandedByDefault,
                        showRecentPlaces = settings.showRecentPlaces,
                        placeLibraryExpandedByDefault = settings.placeLibraryExpandedByDefault,
                        placeRulesExpandedByDefault = settings.placeRulesExpandedByDefault,
                        placeSkillsExpandedByDefault = settings.placeSkillsExpandedByDefault,
                        placeSubagentsExpandedByDefault = settings.placeSubagentsExpandedByDefault,
                        placeMcpExpandedByDefault = settings.placeMcpExpandedByDefault,
                        translation = translation,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppRoute.LIBRARY -> LibraryScreen(
                        model = library,
                        openEditorKey = placeLibraryEdit?.key,
                        editorBackLabel = placeLibraryEdit?.let { strings.navPlace },
                        onEditorBack = placeLibraryEdit?.let {
                            {
                                placeLibraryEdit = null
                                route = AppRoute.PLACE
                            }
                        },
                        onBlockSaved = { saved ->
                            placeLibraryEdit
                                ?.takeIf { it.key.id == saved.id }
                                ?.let { source -> integration.updateEditedRule(saved, source.placeId) }
                        },
                        onSkillSaved = { saved ->
                            placeLibraryEdit
                                ?.takeIf { it.key == LibraryObjectKey(dev.ruleblend.app.library.LibraryObjectKind.SKILL, saved.id) }
                                ?.let { source -> integration.updateEditedSkill(saved, source.placeId) }
                        },
                        compareKey = compareRemoteConflict,
                        onCompareOpened = { compareRemoteConflict = null },
                        modifier = Modifier.fillMaxSize(),
                        translation = translation,
                        groupsExpandedByDefault = settings.groupsExpandedByDefault,
                        sourceUpdateIds = home.sourceUpdates.updateIds,
                        sourceUpdatesChecked = home.sourceUpdates.report != null,
                        // "Where is it installed" is only an answer if it leads to the place itself.
                        onOpenPlace = { place -> openPlace(place) },
                    )
                    AppRoute.COVERAGE -> CoverageScreen(
                        model = coverage,
                        onOpenResolve = { route = AppRoute.RESOLVE },
                        onOpenAgentSettings = {
                            settingsSection = SettingsSection.AGENTS
                            route = AppRoute.SETTINGS
                        },
                        onOpenLibrary = { route = AppRoute.LIBRARY },
                        // A column opened down to one place is that place: its header leads into it.
                        onOpenPlace = { place -> openPlace(place) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppRoute.SETTINGS -> {
                        SettingsScreen(
                            model = settings,
                            library = library,
                            modifier = Modifier.fillMaxSize(),
                            home = home,
                            initialSection = settingsSection,
                            operationScope = appScope,
                        )
                        LaunchedEffect(Unit) { settingsSection = SettingsSection.GENERAL }
                    }
                    AppRoute.RESOLVE -> ResolveScreen(
                        model = resolve,
                        // A conflict is fixed in the file that holds it: every row leads to its place.
                        onOpenPlace = { place -> openPlace(place) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            assistanceState.confirmation?.takeIf { it.action != AssistanceAction.ExitApplication }?.let { confirmation ->
                RuleblendDialog(
                    title = when (val action = confirmation.action) {
                        is AssistanceAction.Replace -> strings.assistanceReplaceConfirm(action.launch.target.name)
                        else -> strings.assistanceCloseConfirm(confirmation.session.launch.assistant.name)
                    },
                    confirmLabel = strings.actionClose,
                    dismissLabel = strings.actionCancel,
                    destructive = true,
                    onDismiss = { appScope.launch { assistance?.cancel(confirmation) } },
                    onConfirm = {
                        appScope.launch {
                            runCatching { assistance?.confirm(confirmation) }
                                .onFailure { assistanceFailure = it.message ?: it.toString() }
                        }
                    },
                )
            }
            assistanceFailure?.let { error ->
                RuleblendDialog(
                    title = strings.assistanceLaunchFailed, text = error,
                    confirmLabel = strings.actionOk,
                    onDismiss = { assistanceFailure = null }, onConfirm = { assistanceFailure = null },
                )
            }
            brokenConfig?.let { moved ->
                RuleblendDialog(
                    onDismiss = { brokenConfig = null },
                    title = strings.configBrokenTitle,
                    text = strings.configBrokenText(moved.toString()),
                    confirmLabel = strings.actionOk,
                    onConfirm = { brokenConfig = null },
                )
            }
        }
    }
}

private fun AppRoute.label(strings: Strings): String = when (this) {
    AppRoute.HOME -> strings.navHome
    AppRoute.PLACE -> strings.navPlace
    AppRoute.LIBRARY -> strings.navLibrary
    AppRoute.COVERAGE -> strings.navCoverage
    AppRoute.SETTINGS -> strings.navSettings
    AppRoute.RESOLVE -> strings.navResolve
}
