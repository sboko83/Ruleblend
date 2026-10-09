package dev.ruleblend.app.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ruleblend.app.navigation.commandShortcut

enum class Language(val code: String, val label: String) {
    EN("en", "English"),
    RU("ru", "Русский"),
    ;

    companion object {
        fun fromCode(code: String): Language = entries.find { it.code == code } ?: EN
    }
}

/** Every user-facing string in the Compose UI. Native OS dialogs (folder picker) stay in the OS language. */
interface Strings {
    // Navigation
    val navHome: String
    val navPlace: String
    val navLibrary: String
    val navCoverage: String
    val navSettings: String
    val navCollapseSidebar: String
    val navExpandSidebar: String
    val navResolve: String
    val resolveOpenPlace: String

    // Command palette (⌘K)
    val commandPill: String
    val commandPlaceholder: String
    val commandNoMatches: String
    val commandScopeHint: String
    val commandTagAction: String
    val commandTagPlace: String
    val commandTagRule: String
    val commandTagSubagent: String
    val commandTagSkill: String
    val commandTagMcp: String
    val commandTagGroup: String
    val commandTagProfile: String
    val commandGoTo: (String) -> String

    // Resolve
    val resolveTitle: String
    val resolveClasses: String
    val resolveScanned: (Int) -> String
    val resolveOccurrences: (Int) -> String
    val resolveVersion: (Int) -> String
    val resolveDrift: (Int, Int) -> String
    val resolveLegacyMarkers: String
    val resolveAmbiguousRow: String
    val resolveLegacyRow: String
    val resolveClassHandEdited: String
    val resolveClassAmbiguous: String
    val resolveClassLegacy: String
    val resolveExplainHandEdited: String
    val resolveExplainAmbiguous: String
    val resolveExplainLegacy: String
    val resolveNextAmbiguous: String
    val resolveNextLegacy: String
    val resolveStrategy: String
    val resolveStrategyInherit: String
    val resolveStrategyRestore: String
    val resolveStrategyRestoreHint: String
    val resolveStrategySave: String
    val resolveStrategySaveHint: String
    val resolveStrategySkip: String
    val resolveStrategySkipHint: String
    val resolveSelectAll: String
    val resolveMarked: (Int) -> String
    val resolvePlanTitle: String
    val resolvePlanNothing: String
    val resolvePlanText: (Int, Int, Int) -> String
    val resolvePlanSkipped: (Int) -> String
    val resolveApply: (Int) -> String
    val resolveApplying: String
    val resolveSafety: String
    val resolveDone: String
    val resolveDoneRestored: (Int) -> String
    val resolveDoneSaved: (Int) -> String
    val resolveDoneSkipped: (Int) -> String
    val resolveDoneFailed: (String) -> String
    val resolveNotScannedTitle: String
    val resolveNotScannedText: String
    val resolveNothingTitle: String
    val resolveNothingText: String

    // Home
    val homeRescan: String
    val homeScanning: String
    val homeScanDuration: (Long) -> String
    val homeNeedsAttention: String
    val homeFleetHealth: String
    val homeProfiles: String
    val homeProfilesRestartHint: (String) -> String
    val homeProfilesSwitchFailed: (String) -> String
    val homeShortcuts: String
    val homeFleetSummary: (Int, Int, Int, Int) -> String
    val homeLibraryLine: (Int, Int, Int, Int) -> String

    /** Card headlines and their second line. Every class counts in its own unit — see AttentionCard. */
    val homeConflicts: (Int, Int) -> String
    val homeConflictsHint: String
    val homeUpdates: (Int, Int) -> String
    val homeUpdatesHint: (String) -> String
    val homeUnadopted: (Int) -> String
    val homeUnadoptedHint: (Int) -> String
    val homeLegacy: (Int, Int) -> String
    val homeLegacyHint: String
    val homeNewAgent: (String) -> String
    val homeNewAgentHint: (String, Int) -> String
    val homeRowModified: (Int) -> String
    val homeRowUpdates: (Int) -> String
    val homeRowLines: (Int) -> String
    val homeRowFiles: (Int) -> String
    val homeResolveAll: String
    val homeReview: String
    val homeOpenPlace: String
    val homeHideAgent: String
    val homeAllClearTitle: String
    val homeAllClearText: String
    val homeNotScannedTitle: String
    val homeNotScannedText: String
    val homeScanNow: String
    val homeEmptyLibraryTitle: String
    val homeEmptyLibraryText: String
    val homeOpenLibrary: String
    val homeNoPlacesTitle: String
    val homeNoPlacesText: String
    val homeSyncTitle: String
    val homeSyncExplains: String
    val homeSyncRemote: (String, String) -> String
    val homeSyncAutomatic: String
    val homeSyncManual: String
    val homeSyncBehind: (Int) -> String
    val homeSyncMerged: String
    val homeSyncMergedConflicts: (Int) -> String
    val homeSyncFailed: (String) -> String
    val homeSyncHistory: String
    val homeSyncCompare: String
    val homeSyncRestored: (Int) -> String
    val homeSyncUnrelated: String
    val homeSyncOpenSettings: String
    val homeSourceUpdatesTitle: String
    val homeSourceCheckNow: String
    val homeSourceChecking: (Int, Int) -> String
    val homeSourceLastChecked: (String) -> String
    val homeSourceNeverChecked: String
    val homeSourceNothing: String
    val homeSourceUpdateAvailable: String
    val homeSourcePathMissing: String
    val homeSourceUnavailable: String
    val homeSourceUpdate: String
    val homeSourceUpdateAll: String
    val homeSourceUpdating: String
    val homeSourceFailure: (String) -> String

    // Coverage
    val coverageNotScannedText: String
    val coverageLegendSynced: String
    val coverageLegendMissing: String
    val coverageLegendUpdate: String
    val coverageLegendModified: String
    val coverageLegendBar: String
    val coverageLegendFraction: String
    val coverageLegendOutOfScope: String
    val coverageLegendOutOfScopeShort: String
    val coverageChipConflicts: (Int, Int, Int) -> String
    val coverageChipUpdates: (Int, Int, Int) -> String
    val coverageCorner: (Int, Int, Int) -> String
    val coverageAgentsPanel: String
    val coverageAgentsNote: String
    val coverageAgentGlobal: String
    val coverageAgentNoSkills: String
    val coverageOnlyInstalled: String
    val coverageRowAll: String
    val coverageNoRowsTitle: String
    val coverageNoRowsText: String
    val coverageExpandRow: (String) -> String
    val coverageCollapseRow: (String) -> String
    val coverageExpandColumn: (String) -> String
    val coverageCollapseColumn: (String) -> String
    val coverageInstallHere: String
    val coverageRemoveHere: String
    val coverageWriting: String
    val coverageNoticeModified: String
    val coverageNoticeScope: String
    val coverageNoticeUnsupported: String
    val coverageNoticeFailed: String
    val coverageNoticeDismiss: String
    val coverageBulkSelected: (Int) -> String
    val coverageBulkInstall: String
    val coverageBulkRemove: String
    val coverageBulkUpdate: String
    val coverageBulkClear: String
    val coverageBulkInstallTitle: (String) -> String
    val coverageBulkRemoveTitle: (String) -> String
    val coverageBulkUpdateTitle: String
    val coverageBulkPlanText: (Int, Int, Int) -> String
    val coverageBulkPlanScope: (Int) -> String
    val coverageBulkPlanNothing: String
    val coverageBulkApply: String
    val coverageBulkDone: String
    val coverageBulkRefused: (String) -> String
    val coverageMarkRow: String

    val coverageRowsLabel: String

    val coverageColumnsLabel: String

    val coverageNeedsAttention: String

    val coverageExpandAllRows: String

    val coverageCollapseAllRows: String

    val coverageExpandAllColumns: String

    val coverageCollapseAllColumns: String

    val coverageBandSet: String

    val coverageBandUngrouped: String

    val coverageOpenResolve: String

    val coverageLegendLeafInstalled: String

    val coverageLegendLeafMissing: String

    val coverageAgentsSettings: String

    val coverageOpenPlaceNamed: (String) -> String

    val coverageColumnAgents: (Int) -> String

    val coverageColumnProjects: (Int) -> String

    val coverageRowCount: (Int) -> String

    val coverageCellObjects: (Int, Int) -> String

    val coverageCellPlaces: (Int, Int) -> String

    val coverageCellSplit: (Int, Int, Int) -> String

    // Common
    val actionSave: String
    val actionDuplicate: String
    val actionDelete: String
    val actionCancel: String
    /** Corner close of a dialog; also its tooltip and accessibility label. */
    val actionClose: String
    val actionCreate: String
    val actionUpdate: String
    val actionAdopt: String
    /** Integration "found in" row: snapshot the whole target file into its backup slot. */
    val actionOk: String

    /** Startup notice: the settings file could not be read and was moved aside. */
    val configBrokenTitle: String
    fun configBrokenText(path: String): String
    val fieldName: String
    val fieldDescription: String
    val actionFormatName: String
    val fieldType: String
    val fieldScope: String
    val fieldHeading: String
    val fieldHeadingHint: String

    // Errors
    val errorTitle: String
    val errAdoptNotReplaced: (String) -> String

    // Library
    val libAddToFavorites: String
    val libRemoveFromFavorites: String
    val libGlobalScope: String
    val libTypes: String
    val libRulesLabel: String
    val libGroups: String
    val libProfiles: String
    val libAllGroup: String
    val libSkills: String
    val libNewSkillTitle: String
    val libImportSkillsAction: String
    val libSkillNameHint: String
    val libGroupContents: String
    val libProfileContents: String
    val libSkillImportTitle: String
    val libSkillRepository: String
    val libSkillRepositoryHint: String
    val libSkillDiscover: String
    val libSkillImportSelected: String
    val libSkillFound: (Int, String) -> String
    val libGitFound: (Int, Int, Int) -> String
    val libGitChooseAssistant: String
    val libGitPreview: String
    val libGitReadOnly: String
    val setSourceSubagents: (Int) -> String
    val libSkillSource: String
    val libSkillCreateChanged: String
    val libSkillUpdateFromGit: String
    val libDeleteSkillConfirm: (String) -> String
    val libMcp: String
    val libExport: String
    val libImport: String
    val libSelectRule: String
    /** Placeholder of the catalog inspector while nothing is picked; the catalog holds more than rules. */
    val libSelectObject: String
    val libTypeRule: String
    val libTypeSubagent: String
    val libNewSubagentTitle: String
    val libSubagents: String
    val libSubagentModel: String
    val libSubagentModelHint: String
    val libSubagentVariants: String
    val libSubagentVariantsHint: String
    val libSubagentFieldTools: String
    val libSubagentFieldToolsHint: String
    val libSubagentFieldColor: String
    fun libSubagentFieldCustom(key: String): String
    val libVersion: String
    val libVersionHint: (Int) -> String
    val libInstruction: String

    // Compare
    val compareAction: String
    val compareTitle: String
    val compareLeft: String
    val compareRight: String
    val compareChoose: String
    val compareUndo: String
    val compareCopyToLeft: String
    val compareCopyToRight: String
    val compareSaveAsRule: String
    val compareSaveAsRuleTitle: String
    val compareSaveAsRuleBody: String
    val compareResetLeft: String
    val compareResetRight: String
    val compareSaveLeft: String
    val compareSaveRight: String
    val compareSaveBoth: String
    val compareNoCandidates: String
    val compareModeDiff: String
    val compareModeEdit: String
    val comparePrevDifference: String
    val compareNextDifference: String
    val compareHunkPosition: (Int, Int) -> String
    val compareDifferences: (Int) -> String
    val compareIdentical: String
    val compareFileOpen: String
    val compareFileTitle: (String) -> String
    val compareFileApply: String
    val compareFileNoCandidates: String

    // Translation panel
    val translateShow: String
    val translateHide: String
    val translateSwap: String
    val translateRefresh: String
    val translateReplace: String
    val translateWorking: String

    /** Headers of the split reading view: which column is the file and which one is the machine. */
    val translateOriginalTitle: String
    val translateColumnTitle: (String, String) -> String
    val translateStale: String
    val translateReadOnly: String
    val translatePackMissing: String
    val translateOpenSettings: String
    val translateUnsupported: String
    val translateUnavailable: String
    val translateFailed: String

    val libNewRuleTitle: String
    val libNewGroupTitle: String
    val libNewProfileTitle: String
    val libDeleteRuleConfirm: (String) -> String
    val libDeleteGroupConfirm: (String) -> String
    val libDeleteProfileConfirm: (String) -> String
    val libExportedTitle: String
    val libExportedText: (String) -> String
    val libExportFailedTitle: String
    val libImportFailedTitle: String
    val libImportFailedText: (String, String) -> String
    val libNothingToImportTitle: String
    val libNothingToImportText: String
    val libImportTitle: String
    val libNewMcpTitle: String
    val libSearchObjects: (Int) -> String
    val libSearchClear: String
    val libByGroup: String
    val libByType: String
    val libByArea: String
    val libProjectScoped: String
    val libSource: String
    val libLocalSkills: String
    val libImportedSkills: String
    val libUpdateAvailable: String
    val libNoMatches: String
    val libMembersCount: (Int) -> String
    val libEditFocus: String
    /** Opens a managed skill's source of truth instead of editing its installed copy. */
    val placeEditInLibrary: String
    val libNewObject: String
    val libUngrouped: String
    // Library inspector
    val libBody: String
    /** Section header of the "where is this installed" list; the count is 0 when nothing is. */
    val libInstalledIn: (Int) -> String
    val libNotInstalled: String
    val libHistory: String
    val libHistoryEmpty: String
    /** Title of the diff window: what changed in one object's history. */
    val libDiffTitle: (String) -> String
    /** Tooltip on a revision row and on the body block of the inspector. */
    val libDiffOpen: String
    val libDiffEmpty: String
    val libBodyOpenEditor: String
    val libGitSourceTitle: String
    val libGitImmutable: String
    val libForkToEdit: String
    val libForkBaseTitle: String
    val libForkUpstreamAhead: String
    val libCompareUpstream: String
    val libMoreCount: (Int) -> String
    val libUsage: String
    val libUsageInstalled: String
    val libUsageUnused: String
    /** Usage marker on a catalog row. */
    val libInPlaces: (Int) -> String
    val libUnusedMark: String
    val libStatusSynced: String
    val libStatusUpdate: String
    val libStatusModified: String
    val libPinnedTo: (String) -> String
    val libAutoMaintained: String
    /** Focus editor note: what saving does to the installs of this object. */
    val libSaveImpact: (Int, String) -> String
    val libSaveImpactUnused: (String) -> String
    // Library bulk actions
    val libSelectedCount: (Int) -> String
    val libBulkInstall: String
    val libBulkAddToGroup: String
    val libBulkExport: String
    val libBulkCombineRules: String
    val libCombinedRuleName: String
    val libBulkClear: String
    val libInstallConfirm: String
    val libAddConfirm: String
    val libInstallIntoTitle: String
    val libInstallIntoText: (Int) -> String
    val libInstallNoPlaces: String
    val libInstallReportTitle: String
    val libInstallWritten: (Int) -> String
    /** Refused writes, one line per reason; all three are omitted when the count is zero. */
    val libInstallSkippedScope: (Int) -> String
    val libInstallSkippedUnsupported: (Int) -> String
    val libInstallSkippedModified: (Int) -> String
    val libInstallFailedCount: (Int) -> String
    // Taking one object out of one place, from the editor's install list
    val libUninstallHint: String
    val libUninstallTitle: String
    val libUninstallText: (String, String) -> String
    val libUninstallConfirm: String
    val libUninstallReportTitle: String
    /** Group members a removal kept because nothing marks them as the group's. */
    val placeGroupUnmarked: (String) -> String
    val placeGroupRemoveUnmarked: String
    val placeGroupKeepUnmarked: String
    val libUninstallRemoved: (Int) -> String
    val libAddToGroupTitle: String
    val libAddToGroupText: (Int) -> String
    val libNoGroups: String
    val libAddedToGroupTitle: String
    val libAddedToGroupText: (Int, String) -> String
    val libAddedNothingText: String
    // MCP config form
    val mcpTransport: String
    val mcpTransportStdio: String
    val mcpTransportHttp: String
    val mcpCommand: String
    val mcpArgs: String
    val mcpEnv: String
    val mcpUrl: String
    val mcpHeaders: String
    val mcpAddRow: String
    /** Caption naming the server entry key agents will see, i.e. the block id. */
    val mcpServerNameHint: (String) -> String
    val mcpBrokenConfig: String
    val mcpResetConfig: String
    val mcpErrCommandRequired: String
    val mcpErrUrlInvalid: String
    val mcpErrBlankKey: String
    val mcpErrDuplicateKey: String
    val mcpErrReservedName: (String) -> String
    val changeNew: String
    val changeUpdate: String
    val changeUnchanged: String
    val changeConflict: String

    // Place list
    val placeFilter: String
    /** Empties the filter field, which is the only way back to the full list once it is typed in. */
    val placeFilterClear: String
    val placePinned: String
    val placeRecent: String
    val placeUngrouped: String
    val placePin: String
    val placeUnpin: String
    val placeNewSet: String
    val placeRenameSet: String
    val placeDeleteSet: String
    val placeMoveToSet: (String) -> String
    val placeRemoveFromSet: String

    // Place file view
    /** Ownership mode of a rules file, shown on its tab. */
    val placeModeNone: String
    val placeModeLegacy: String
    val placeModePartial: String
    val placeModeOwned: String
    /** Pointer tab badge: which file the managed import leads to. */
    val placePointerTo: (String) -> String
    val placeOwnedNotice: String
    val placePointerNotice: (String) -> String
    val placeNoBlocks: String
    val placeNoServers: String
    val placeNoSkills: String
    val placeNoSubagents: String
    /** Empty skills tab with entries Ruleblend did not install. */
    val placeNoOurSkills: (Int) -> String
    /** Empty subagents tab with definitions Ruleblend did not install. */
    val placeNoOurSubagents: (Int) -> String
    /** Empty MCP tab with entries Ruleblend did not install. */
    val placeNoOurServers: (Int) -> String
    /** Count in a Skills tab, separated by Ruleblend ownership: ours, then the rest. */
    val placeObjectCount: (Int, Int) -> String
    /** What the two numbers of [placeObjectCount] mean; the label itself stays two numbers. */
    val placeObjectCountHint: (Int, Int) -> String
    /** Shared heading for disk entries which Ruleblend does not manage. */
    val placeForeignEntries: String
    val placeBuiltInEntries: String
    val placeBuiltInManaged: String
    val placeBuiltInReadOnly: String
    val placeHideEntry: String
    val placeUnhideEntry: String
    val placeTakeOwnership: String
    /** Actions offered for a sidecar-owned entry whose library object was deleted. */
    val placeRestoreOrphan: String
    val placeRemoveOrphan: String
    val placeRemoveOrphanConfirm: (String) -> String
    /** Explicit confirmation for deleting a disk entry Ruleblend did not install. */
    val placeRemoveForeignConfirm: (String, String) -> String
    val placeSkillNameTaken: (String) -> String
    val placeSkillNameHint: String
    val placeSubagentNameTaken: (String) -> String
    val placeSubagentNameHint: String
    val placeMcpNameTaken: (String) -> String
    val placeMcpNameHint: String
    val placeShowHiddenEntries: (Int) -> String
    val placeHideHiddenEntries: (Int) -> String
    val placeFileMissing: String
    val placeProjectMissing: String
    val placeTabWrittenTo: (String) -> String
    val placeBackup: String
    val placeBackupUpdate: String
    val placeBackupHint: String
    val placeBackupUpdateHint: String
    val placeRestoreHint: String
    val placeDisownHint: String
    val placeShowNoticeHint: String
    val placeHideNoticeHint: String
    val placeRestore: String
    val placeRestoreConfirm: (String) -> String
    val placeMigrateLegacy: String
    val placeDisown: String
    val placeDisownConfirm: (String) -> String
    val placeUnlinkEntry: String
    val placeShowNotice: String
    val placeHideNotice: String
    val placeAssistants: String
    val placeAssistantsConnected: (Int, Int) -> String
    val placeAssistantsConfigure: String
    val placeAssistantsCollapse: String
    val placeAssistantsSearch: String
    val placeAssistantsSearchClear: String
    val placeAssistantsNoMatch: String
    val placeLaunch: String
    val placeProfiles: String
    val placeAddProfile: String
    val placeDetachProfile: (String) -> String
    val placeProfileToggleHint: (String) -> String
    val placeProfileReportTitle: String
    val placeProfileReportApplied: (Int, Int) -> String
    val placeProfileReportMissing: (String) -> String
    val placeAttachProfile: String
    val placeAttachMerge: String
    val placeAttachReplace: String
    val placeAttachNoProfiles: String
    val placeReplaceBaseHint: (Int) -> String
    val placeReplaceBaseEmpty: String

    /** Launch buttons in the project header: one per agent CLI found on this machine. */
    val placeLaunchNew: (String) -> String
    val placeLaunchResume: (String) -> String
    val placeLaunchHint: (String) -> String
    val placeLaunchHintResumable: (String) -> String
    val placeTabNothingIn: (String) -> String
    /** Row badge for an installed object that is no longer in the library. */
    val placeNotInLibrary: String
    /** Opens the legacy checklist with the write actions the new screen does not carry yet. */
    val placeInstall: String
    val placeRemove: String
    /** Settles a hand edit by leaving it in place: neither file nor library is written. */
    val placeKeep: String
    /** Explains what the row buttons of a hand-edited block decide. */
    val placeDriftNotice: String
    /** Replaces the "modified" badge once the edit is one the user answered for and chose to keep. */
    val placeDriftKept: String
    /** Explains an MCP entry whose agent copies were edited apart, so the row asks which one to keep. */
    val placeDriftApart: String
    val placeMoveTop: String
    val placeMoveBottom: String

    // Place palette
    /** Turns what is installed here into a library group — the app's one kind of bundle. */
    val paletteSaveAsGroup: String
    val paletteSaveAsGroupTitle: String
    /** Explains what saving captures: what is installed here right now, referenced by id. */
    val paletteSaveAsGroupHint: (Int) -> String
    val paletteRecommended: String
    /** "in 6 of 8 KMP apps": how much of the project's set already holds the object. */
    val paletteInSet: (Int, Int, String) -> String
    val paletteLibrary: String
    val paletteSearch: (Int) -> String
    val paletteSearchClear: String
    val paletteNoMatches: String
    val paletteShowMore: (Int) -> String
    /** Tooltip of the library header's "+" button: open every type section. */
    val paletteExpandAll: String
    /** Tooltip of the library header's "−" button: close every type section. */
    val paletteCollapseAll: String
    val paletteFound: String
    /** The same header for an agent's own global file: there is no project to name there. */
    val paletteFoundAgent: String
    val paletteFoundLines: (Int) -> String
    /** Caption of a row the place cannot hold: no agent here reads that kind of object. */
    val paletteUnsupported: String
    /** Right-click action on hand-written text of the file preview: take it into the library. */
    val placeSaveToLibrary: String

    /** What an unmanaged fragment is called when it opens with prose rather than a heading. */
    val placeHandText: String

    // Integration
    val intAgents: String
    val intProjects: String
    val intAddProject: String
    val intEmptySelection: String
    val intSkills: String

    /** Tooltip on the AGENTS.md chip: a single shared rules file for every agent in the project. */

    /** Tooltip on the CLAUDE.md chip: a managed @AGENTS.md import so Claude Code finds the rules. */
    val intAdoptTitle: (String) -> String
    val intAdoptReplaceLabel: String
    val intAdoptOrderBlocked: (String) -> String
    val intAdoptOrderAroundRun: String
    val intAdoptSizeWarning: (Int, Int) -> String
    /** Adopt dialog: label for the shared description field. */
    val fieldGeneralDescription: String
    /** Adopt dialog: pre-filled description naming the source target and file. */
    val intAdoptDescriptionDefault: (String, String) -> String
    /** Multi-section adopt: header above the sections checklist. */
    val intAdoptSections: (Int) -> String
    /** Per-section action menu and statuses. */
    val intAdoptSectionNew: String
    val intAdoptSectionDuplicate: String
    val intAdoptSectionSimilar: (String) -> String
    val intAdoptActionSkip: String
    val intAdoptActionOverwrite: String
    val intAdoptActionCreateNew: String
    val intAdoptSelectAll: String
    val intAdoptSelectNone: String
    val intAdoptScanSummary: (Int, Int) -> String
    /** Tooltip title / list header for the @imports finding. */
    val intAdoptScanImportsHeader: (Int) -> String
    /** Explanatory line beneath the imports list. */
    val intAdoptScanImportsHint: String
    /** Tooltip title / list header for the broken local links finding. */
    val intAdoptScanBrokenHeader: (Int) -> String
    /** Explanatory line beneath the broken links list. */
    val intAdoptScanBrokenHint: String
    val intTakeLibrary: String
    /** Save the local edit as the object's next library version, named by the version it will get. */
    val intAcceptLocal: (String?) -> String
    val ctxRemoveProject: String
    /** Remove-project confirm dialog body, naming the project. */
    val intRemoveProjectConfirm: (String) -> String
    /** Tooltip + edit icon in the "found in" list: open the file in an in-app editor. */
    val intEditFile: String
    /** Tooltip + trash icon in the "found in" list: delete the source file and its backup. */
    val intFileEditorTitle: (String) -> String
    /** Tooltip on the green check that marks a pure @import pointer as already configured. */
    val libNewRuleInGroupTitle: String
    // MCP install
    val intMcpServers: String
    /** Caption on a row whose config an agent cannot run, e.g. a remote server and Codex. */

    // Split
    /** Action label on the Split button (Library editor) and menu item (adopt dialog). */
    val splitAction: String
    /** Dialog title in Library mode, naming the source block. */
    val splitDialogTitleLibrary: (String) -> String
    /** Dialog title in adopt mode: no source name — the source is a section fragment. */
    val splitDialogTitleAdopt: String
    /** Header on the parts column, showing how many parts the current cut set produces. */
    val splitPartsCount: (Int) -> String
    /** Clears every cut in the dialog. */
    val splitReset: String
    /** Primary button label with the resulting parts count. */
    val splitConfirm: (Int) -> String
    /** Placeholder / field label for the per-part editable name. */
    val splitEmptyBody: String
    /** Inline warning when a part's name collides with an existing block id, showing which id. */
    val splitIdConflict: (String) -> String
    /** Inline warning when two parts in the same batch resolve to the same slug. */
    val splitIdDuplicate: String
    /** Tooltip on an inter-line gap (no cut yet). */
    val splitAddCut: String
    /** Tooltip on an existing cut chip. */
    val splitRemoveCut: String
    /** Placeholder in the source-preview pane when the source has fewer than 2 lines. */
    val splitTooShort: String
    /** Hint shown when no cuts have been placed yet. */
    val splitNoCuts: String

    // Settings
    val setOpenConfigFolder: String
    val setFooter: String
    val setSectionGeneral: String
    val setSectionLibrary: String
    val setSectionAgents: String
    val setSectionShortcuts: String
    val setSectionAbout: String
    val setSectionFolding: String
    val setFoldingSum: String
    val setGeneralSum: String
    val setTheme: String
    val setThemeHint: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val setLanguage: String
    val setLanguageHint: String
    val setGroupsExpansion: String
    val setGroupsExpansionHint: String
    val setGroupsExpanded: String
    val setGroupsCollapsed: String
    val setPlaceLibraryExpansion: String
    val setPlaceLibraryExpansionHint: String
    val setPlaceRulesExpansion: String
    val setPlaceRulesExpansionHint: String
    val setPlaceSkillsExpansion: String
    val setPlaceSkillsExpansionHint: String
    val setPlaceSubagentsExpansion: String
    val setPlaceSubagentsExpansionHint: String
    val setPlaceMcpExpansion: String
    val setPlaceMcpExpansionHint: String
    val setFileEditor: String
    val setFileEditorHint: String
    val setTranslation: String
    val setTranslationHint: String
    /** Label of the switch that takes translation out of the app entirely. */
    val setTranslationEnabled: String
    val translateQualityFast: String
    val translateQualityHigh: String
    val translatePackReady: String
    val fileEditorInApp: String
    val fileEditorExternal: String
    val fileEditorApplication: String
    val fileEditorSystemDefault: String
    val fileEditorChoose: String
    val fileEditorReset: String
    val setWorkspace: String
    val setWorkspaceHint: String
    val setResetColumns: String
    val setResetColumnsDone: String
    val setClearRecent: String
    val setClearRecentDone: String
    val setWorkspaceNote: String
    val setLibraryPath: String
    val setLibraryPathHint: String
    val setLibraryPathChange: String
    val setRevealInFinder: String
    val setRevealInFiles: String
    val setReveal: String
    val setStatsRules: (Int) -> String
    val setStatsSkills: (Int) -> String
    val setStatsMcp: (Int) -> String
    val setStatsGroups: (Int) -> String
    val setStatsCommits: (Int) -> String
    val setStatsLastChange: (String) -> String
    val agoJustNow: String
    val agoMinutes: (Long) -> String
    val agoHours: (Long) -> String
    val agoDays: (Long) -> String
    val setLibraryPathRestartHint: String
    val setUndo: String
    val setSourceCheck: String
    val setSourceCheckHint: String
    val sourceCheckManual: String
    val sourceCheckOnLaunch: String
    val setSources: String
    val setSourcesHint: String
    val setSourcesEmpty: String
    val setSourceSkills: (Int) -> String
    val setSourceHead: (String) -> String
    val setSourceReopenImport: String
    val setNameFormat: String
    val setNameFormatHint: String
    val setLineEnding: String
    val setLineEndingHint: String
    val nameFormatKebab: String
    val nameFormatCamel: String
    val nameFormatSnake: String
    val nameFormatFree: String
    val setRemoteGit: String
    val remoteGitHint: String
    val remoteGitUrl: String
    val remoteGitBranch: String
    val remoteGitAutomatic: String
    val remoteGitSaveAndSync: String
    val remoteGitCancel: String
    val remoteGitSyncNow: String
    val remoteGitEdit: String
    val remoteGitRemove: String
    val remoteGitSyncing: String
    val remoteGitPushed: String
    val remoteGitUpToDate: String
    val remoteGitFailed: String
    val remoteGitNotSynced: String
    val remoteGitPushedAgo: (String) -> String
    val remoteGitBehind: (Int) -> String
    val remoteGitMerged: String
    val remoteGitConflicts: (Int) -> String
    val remoteGitRestored: (Int) -> String
    val remoteGitAutoShort: String
    val remoteGitManualShort: String
    val remoteGitLocalKept: String
    val remoteGitUnrelatedTitle: String
    val remoteGitUnrelatedText: String
    val remoteGitMergeAsImport: String
    val remoteGitReplaceAfterExport: String
    val setLibraryData: String
    val setLibraryDataHint: String
    val setImportNote: String
    val setAgentSummary: (Int, Int, Int, Int) -> String
    val setColAgent: String
    val setColCanManage: String
    val setColVisible: String
    val setColMcp: String
    val setColCli: String
    val setCliArgumentsHint: String
    val setTerminal: String
    val setTerminalHint: String
    val setAgentNotDetected: String
    val setAgentRulesCapability: String
    val setAgentMcpCapability: String
    val setAgentSkillsCapability: String
    val setAgentSubagentsCapability: String
    val setCapabilityGlobalOnly: String
    val setCapabilityProjectOnly: String
    val setCapabilityUnavailable: String
    val setAgentVisible: String
    val setAgentHidden: String
    val setConnManual: String
    val connectAction: String
    val connectActionUpdate: String
    val disconnectAction: String
    val connectStatusConnected: String
    val connectStatusNotConnected: String
    val connectStatusStale: String
    val connectStatusSkillOutdated: String
    val connectStatusMcpOutdated: String
    val connectStatusSkillForeign: String
    val connectStatusMcpForeign: String
    /** Version of the bundled skill an agent holds against the one this app ships. */
    fun setSkillVersion(installed: Int, current: Int): String
    /** Version of the MCP entry an agent holds against the one this app writes. */
    fun setEntryVersion(installed: Int, current: Int): String
    val setUpdateAll: String
    fun setUpdateAllHint(count: Int): String
    val homeIntegrationTitle: String
    val homeIntegrationUpdateAll: String
    val homeIntegrationUpdate: String
    val homeIntegrationUpdating: String
    val homeIntegrationForeign: String
    val libBuiltIn: String
    val libBuiltInManaged: String
    val connectManualHint: String
    val setCopy: String
    val setCopied: String
    val setAgentsFoot: String
    val setShortcutsSum: String
    val setKeyPalette: String
    val setKeyClose: String
    val setKeyScoped: String
    val setShortcutsFoot: String
    val setAboutBuild: (String, String, String) -> String
    val setCopyDiagnostics: String
    val setDiagnosticsNote: String
    val setFiles: String
    val setFilesHint: String
    val setFileConfig: String
    val setFileLibrary: String
}

object EnStrings : Strings {
    override val navHome = "Home"
    override val navPlace = "Targets"
    override val navLibrary = "Library"
    override val navCoverage = "Coverage"
    override val navSettings = "Settings"
    override val navCollapseSidebar = "Collapse sidebar"
    override val navExpandSidebar = "Expand sidebar"
    override val navResolve = "Resolve"
    override val resolveOpenPlace = "Open Target"

    override val commandPill = "${commandShortcut("K")} — search, targets, actions"
    override val commandPlaceholder = "Search library and targets…"
    override val commandNoMatches = "No matches"
    override val commandScopeHint = "r: rules · s: skills · m: mcp · p: targets"
    override val commandTagAction = "go"
    override val commandTagPlace = "target"
    override val commandTagRule = "rule"
    override val commandTagSubagent = "subagent"
    override val commandTagSkill = "skill"
    override val commandTagMcp = "mcp"
    override val commandTagGroup = "group"
    override val commandTagProfile = "profile"
    override val commandGoTo = { name: String -> "Go to $name" }

    // Resolve
    override val resolveTitle = "Resolve conflicts"
    override val resolveClasses = "Conflict classes"
    override val resolveScanned = { total: Int -> "$total occurrences across the fleet" }
    override val resolveOccurrences = { count: Int -> if (count == 1) "1 occurrence" else "$count occurrences" }
    override val resolveVersion = { version: Int -> "v$version" }
    override val resolveDrift = { added: Int, removed: Int -> "+$added \u2212$removed lines" }
    override val resolveLegacyMarkers = "legacy markers"
    override val resolveAmbiguousRow = "needs a manual decision"
    override val resolveLegacyRow = "readable \u00b7 rewritten on next write"
    override val resolveClassHandEdited = "Hand-edited blocks"
    override val resolveClassAmbiguous = "Ambiguous runs"
    override val resolveClassLegacy = "Legacy-format files"
    override val resolveExplainHandEdited =
        "The body in the target file differs from the library version inside a managed region."
    override val resolveExplainAmbiguous =
        "Two blocks of the same managed run were both edited, so neither edit can be attributed to one rule."
    override val resolveExplainLegacy =
        "Old marker format. The file stays readable and is rewritten in the current format on its next write."
    override val resolveNextAmbiguous = "Ruleblend refuses to guess here: open each target and decide per block."
    override val resolveNextLegacy = "Nothing to decide."
    override val resolveStrategy = "Strategy"
    override val resolveStrategyInherit = "Inherit"
    override val resolveStrategyRestore = "Restore from library"
    override val resolveStrategyRestoreHint =
        "Rebuilds the managed run from the current library bodies. The hand edit is discarded."
    override val resolveStrategySave = "Save as new version"
    override val resolveStrategySaveHint =
        "The edit becomes the rule's next library version; every other target then shows an update."
    override val resolveStrategySkip = "Skip for now"
    override val resolveStrategySkipHint = "Leaves the file untouched. The conflict stays listed."
    override val resolveSelectAll = "Select all"
    override val resolveMarked = { count: Int -> "$count marked" }
    override val resolvePlanTitle = "Plan"
    override val resolvePlanNothing = "Nothing marked yet."
    override val resolvePlanText = { restores: Int, saves: Int, places: Int ->
        "$restores restore · $saves save as new · in $places target${if (places == 1) "" else "s"}"
    }
    override val resolvePlanSkipped = { count: Int -> "$count marked row${if (count == 1) "" else "s"} skipped" }
    override val resolveApply = { writes: Int -> "Apply to $writes block${if (writes == 1) "" else "s"}" }
    override val resolveApplying = "Applying…"
    override val resolveSafety = "Files are rewritten atomically. Content outside managed regions is never touched."
    override val resolveDone = "Batch finished"
    override val resolveDoneRestored = { count: Int -> "$count restored from the library" }
    override val resolveDoneSaved = { count: Int -> "$count saved as a new version" }
    override val resolveDoneSkipped = { count: Int -> "$count left as they were" }
    override val resolveDoneFailed = { places: String -> "not written in: $places" }
    override val resolveNotScannedTitle = "Not scanned yet"
    override val resolveNotScannedText =
        "Resolve classifies one reading of every managed file. Run a scan to see the conflicts of the fleet."
    override val resolveNothingTitle = "Nothing in this class"
    override val resolveNothingText = "No target of the fleet holds a conflict of this kind."
    override val homeRescan = "Rescan targets"
    override val homeScanning = "Scanning…"
    override val homeScanDuration = { millis: Long -> "scanned in $millis ms" }
    override val homeNeedsAttention = "Needs attention"
    override val homeFleetHealth = "Fleet health"
    override val homeProfiles = "Profiles"
    override val homeProfilesRestartHint = { agents: String ->
        "Rules and MCP servers apply after the $agents session is restarted; skills and subagents apply at once."
    }
    override val homeProfilesSwitchFailed = { reason: String -> "Profile switch failed: $reason" }
    override val homeShortcuts = "Pinned & recent"
    override val homeFleetSummary = { total: Int, clean: Int, updates: Int, conflicts: Int ->
        "$total targets · $clean clean · $updates with updates · $conflicts with conflicts"
    }
    override val homeLibraryLine = { rules: Int, skills: Int, mcp: Int, groups: Int ->
        "Library: $rules rules · $skills skills · $mcp MCP servers · $groups groups"
    }
    override val homeConflicts = { blocks: Int, places: Int ->
        "$blocks hand-edited blocks across $places targets"
    }
    override val homeConflictsHint = "Hand edits are overwritten on the next apply"
    override val homeUpdates = { updates: Int, places: Int -> "$updates updates available across $places targets" }
    override val homeUpdatesHint = { worst: String -> "Most behind: $worst" }
    override val homeUnadopted = { places: Int -> "Unadopted hand-written rules in $places targets" }
    override val homeUnadoptedHint = { lines: Int -> "$lines unmanaged lines could join the library" }
    override val homeLegacy = { files: Int, places: Int -> "$files files in the legacy marker format in $places targets" }
    override val homeLegacyHint = "Installing here converts the file to the current markers"
    override val homeNewAgent = { agent: String -> "New agent detected: $agent" }
    override val homeNewAgentHint = { file: String, projects: Int ->
        "Global file $file is not managed yet · $projects projects eligible"
    }
    override val homeRowModified = { count: Int -> "$count modified" }
    override val homeRowUpdates = { count: Int -> "$count updates" }
    override val homeRowLines = { count: Int -> "$count lines" }
    override val homeRowFiles = { count: Int -> "$count files" }
    override val homeResolveAll = "Resolve all"
    override val homeReview = "Review"
    override val homeOpenPlace = "Open target"
    override val homeHideAgent = "Hide agent"
    override val homeAllClearTitle = "Nothing needs attention"
    override val homeAllClearText = "All managed blocks are in sync."
    override val homeNotScannedTitle = "Targets have not been scanned yet"
    override val homeNotScannedText = "Home reads every managed file of every target. Run a scan to see what needs attention."
    override val homeScanNow = "Scan targets"
    override val homeEmptyLibraryTitle = "The library is empty"
    override val homeEmptyLibraryText = "There is nothing to install yet. Create a rule, a skill or an MCP server in Library."
    override val homeOpenLibrary = "Open Library"
    override val homeNoPlacesTitle = "No targets yet"
    override val homeNoPlacesText = "Add a project in Targets, or connect an agent in Settings, and Home will watch it."
    override val homeSyncTitle = "Library synchronization"
    override val homeSyncExplains =
        "Your rules, skills, MCP entries and profiles are kept in a Git repository so every machine you " +
            "work on sees the same library. This is not about the assistants installed on this machine."
    override val homeSyncRemote = { url: String, branch: String -> "$url · $branch" }
    override val homeSyncAutomatic = "runs automatically"
    override val homeSyncManual = "manual only"
    override val homeSyncBehind = { commits: Int -> "$commits incoming ${if (commits == 1) "commit" else "commits"}" }
    override val homeSyncMerged = "Merged"
    override val homeSyncMergedConflicts = { count: Int -> "Merged with $count ${if (count == 1) "conflict" else "conflicts"}" }
    override val homeSyncFailed = { reason: String -> "Sync failed: $reason" }
    override val homeSyncHistory = "History"
    override val homeSyncCompare = "Compare"
    override val homeSyncRestored = { count: Int -> "$count ${if (count == 1) "deletion" else "deletions"} restored" }
    override val homeSyncUnrelated = "The remote holds a different library. Choose how to join them."
    override val homeSyncOpenSettings = "Open settings"
    override val homeSourceUpdatesTitle = "Imported definition updates"
    override val homeSourceCheckNow = "Check now"
    override val homeSourceChecking = { done: Int, total: Int -> "Checking sources $done/$total" }
    override val homeSourceLastChecked = { time: String -> "Last checked: $time" }
    override val homeSourceNeverChecked = "Not checked yet"
    override val homeSourceNothing = "Nothing to update"
    override val homeSourceUpdateAvailable = "Update available"
    override val homeSourcePathMissing = "Upstream path is missing"
    override val homeSourceUnavailable = "Source is unavailable"
    override val homeSourceUpdate = "Update"
    override val homeSourceUpdateAll = "Update all"
    override val homeSourceUpdating = "Updating…"
    override val homeSourceFailure = { reason: String -> "Source action failed: $reason" }

    // Coverage
    override val coverageNotScannedText =
        "Coverage aggregates one reading of every managed file. Run a scan to see where each object sits."
    override val coverageLegendSynced = "synced"
    override val coverageLegendMissing = "not installed"
    override val coverageLegendUpdate = "update available"
    override val coverageLegendModified = "modified / conflict"
    override val coverageLegendBar = "installed / not installed"
    override val coverageLegendFraction = "installed of those that fit"
    override val coverageLegendOutOfScope = "can't go here: the rule is pinned to another project, or no AI assistant here supports this type"
    override val coverageLegendOutOfScopeShort = "can't go here"
    override val coverageChipConflicts = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count conflict${if (count == 1) "" else "s"}",
            coverageColumnAgents(agents).takeIf { agents > 0 },
            coverageColumnProjects(projects).takeIf { projects > 0 },
        ).joinToString(" · ")
    }
    override val coverageChipUpdates = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count update${if (count == 1) "" else "s"}",
            coverageColumnAgents(agents).takeIf { agents > 0 },
            coverageColumnProjects(projects).takeIf { projects > 0 },
        ).joinToString(" · ")
    }
    override val coverageCorner = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count library object${if (count == 1) "" else "s"}",
            coverageColumnAgents(agents),
            coverageColumnProjects(projects),
        ).joinToString(" · ")
    }
    override val coverageAgentsPanel = "AI assistants"
    override val coverageAgentsNote = "A hidden AI assistant disappears from these columns, from Home and from Targets."
    override val coverageAgentGlobal = "global"
    override val coverageAgentNoSkills = "no skills support"
    override val coverageOnlyInstalled = "Only installed"
    override val coverageRowAll = "All objects"
    override val coverageNoRowsTitle = "Nothing matches these filters"
    override val coverageNoRowsText = "Turn a type back on, or drop \"Only installed\" and \"Needs attention\"."
    override val coverageExpandRow = { name: String -> "Expand \"$name\"" }
    override val coverageCollapseRow = { name: String -> "Collapse \"$name\"" }
    override val coverageExpandColumn = { name: String -> "Expand \"$name\"" }
    override val coverageCollapseColumn = { name: String -> "Collapse \"$name\"" }
    override val coverageInstallHere = "Install here"
    override val coverageRemoveHere = "Remove from here"
    override val coverageWriting = "Writing…"
    override val coverageNoticeModified = "Edited by hand there — settle it in Targets, on the file."
    override val coverageNoticeScope = "That rule is pinned to another project."
    override val coverageNoticeUnsupported = "No AI assistant of that target supports this type."
    override val coverageNoticeFailed = "The write failed; the target is unchanged."
    override val coverageNoticeDismiss = "Dismiss"
    override val coverageBulkSelected = { count: Int -> "$count objects marked" }
    override val coverageBulkInstall = "Install into…"
    override val coverageBulkRemove = "Remove from…"
    override val coverageBulkUpdate = "Update everywhere"
    override val coverageBulkClear = "Clear"
    override val coverageBulkInstallTitle = { target: String -> "Install into $target" }
    override val coverageBulkRemoveTitle = { target: String -> "Remove from $target" }
    override val coverageBulkUpdateTitle = "Update everywhere"
    override val coverageBulkPlanText = { writes: Int, objects: Int, places: Int ->
        "$writes writes · $objects objects · $places targets"
    }
    override val coverageBulkPlanScope = { count: Int ->
        "$count pairs left out — pinned to another project, or no AI assistant there installs them."
    }
    override val coverageBulkPlanNothing = "Nothing to write: everything asked for is already so."
    override val coverageBulkApply = "Apply"
    override val coverageBulkDone = "Bulk action finished"
    override val coverageBulkRefused = { places: String -> "Nothing written in: $places" }
    override val coverageRowsLabel = "Rows:"
    override val coverageColumnsLabel = "Columns:"
    override val coverageNeedsAttention = "Needs attention"
    override val coverageExpandAllRows = "Expand all rows"
    override val coverageCollapseAllRows = "Collapse all rows"
    override val coverageExpandAllColumns = "Expand all columns"
    override val coverageCollapseAllColumns = "Collapse all columns"
    override val coverageBandSet = "set"
    override val coverageBandUngrouped = "Not in a set"
    override val coverageOpenResolve = "Resolve"
    override val coverageLegendLeafInstalled = "installed — click removes"
    override val coverageLegendLeafMissing = "not installed — click installs"
    override val coverageAgentsSettings = "AI assistant settings"
    override val coverageOpenPlaceNamed = { name: String -> "Open \"$name\"" }
    override val coverageColumnAgents = { count: Int -> "$count AI assistant${if (count == 1) "" else "s"}" }
    override val coverageColumnProjects = { count: Int -> "$count target${if (count == 1) "" else "s"}" }
    override val coverageRowCount = { count: Int -> "$count object${if (count == 1) "" else "s"} in this row" }
    override val coverageCellObjects = { installed: Int, fitting: Int -> "$installed of $fitting fitting objects installed" }
    override val coverageCellPlaces = { installed: Int, fitting: Int -> "Installed in $installed of $fitting fitting targets" }
    override val coverageCellSplit = { synced: Int, updates: Int, modified: Int ->
        listOfNotNull(
            "synced $synced".takeIf { synced > 0 },
            "update $updates".takeIf { updates > 0 },
            "modified $modified".takeIf { modified > 0 },
        ).joinToString(", ")
    }
    override val coverageMarkRow = "Mark for a bulk action"
    override val libAddToFavorites = "Add to favorites"
    override val libRemoveFromFavorites = "Remove from favorites"
    override val libGlobalScope = "Global"

    override val actionSave = "Save"
    override val actionDuplicate = "Duplicate"
    override val actionDelete = "Delete"
    override val actionCancel = "Cancel"
    override val actionClose = "Close"
    override val actionCreate = "Create"
    override val actionUpdate = "Update"
    override val actionAdopt = "Save to library"
    override val actionOk = "OK"
    override val configBrokenTitle = "Settings could not be read"
    override fun configBrokenText(path: String) =
        "The settings file could not be read, so Ruleblend started with its defaults. " +
            "Your file was kept, unchanged, at $path."
    override val fieldName = "Name"
    override val fieldDescription = "Description"
    override val actionFormatName = "Format"
    override val fieldType = "Type"
    override val fieldScope = "Scope"
    override val fieldHeading = "Heading"
    override val fieldHeadingHint = "No heading of its own"

    override val errorTitle = "Something went wrong"
    override val errAdoptNotReplaced = { cause: String ->
        "The rule was saved to the library, but the original file could not be rewritten, so its text is still there.\n\n$cause"
    }

    override val libTypes = "Types"
    override val libRulesLabel = "Rules"
    override val libGroups = "Groups"
    override val libProfiles = "Profiles"
    override val libAllGroup = "All"
    override val libSkills = "Skills"
    override val libNewSkillTitle = "New skill"
    override val libImportSkillsAction = "Import Git"
    override val libSkillNameHint = "Lowercase letters, numbers and hyphens; description is required."
    override val libGroupContents = "Group contents"
    override val libProfileContents = "Profile contents"
    override val libSkillImportTitle = "Import skills and subagents from Git"
    override val libSkillRepository = "Repository URL"
    override val libSkillRepositoryHint = "Ruleblend reads skills and native subagent definitions without executing repository code."
    override val libSkillDiscover = "Find definitions"
    override val libSkillImportSelected = "Import selected"
    override val libSkillFound = { count: Int, version: String -> "$count skills found · repository version $version" }
    override val libGitFound = { skills: Int, agents: Int, errors: Int -> "$skills skills · $agents subagents · $errors file errors" }
    override val libGitChooseAssistant = "Choose the source assistant before selecting this definition"
    override val libGitPreview = "Preview"
    override val libGitReadOnly = "Imported definitions are read-only. Create a changed copy to edit."
    override val setSourceSubagents = { n: Int -> "$n subagents" }
    override val libSkillSource = "Git source"
    override val libSkillCreateChanged = "Create changed copy"
    override val libSkillUpdateFromGit = "Update from Git"
    override val libDeleteSkillConfirm = { name: String -> "Delete skill \"$name\" from the library?" }
    override val libMcp = "MCP"
    override val libExport = "Export"
    override val libImport = "Import"
    override val libSelectRule = "Select a rule"
    override val libSelectObject = "Select an object"
    override val libTypeRule = "Rules"
    override val libTypeSubagent = "Subagent"
    override val libNewSubagentTitle = "New subagent"
    override val libSubagents = "Subagents"
    override val libSubagentModel = "Model preference"
    override val libSubagentModelHint = "Any model name, for example gpt-5.6-terra"
    override val libSubagentVariants = "Per-assistant settings"
    override val libSubagentVariantsHint = "Left empty, the assistant is installed without that field"
    override val libSubagentFieldTools = "Tools"
    override val libSubagentFieldToolsHint = "Comma-separated, for example Bash, Read, Grep"
    override val libSubagentFieldColor = "Color"
    override fun libSubagentFieldCustom(key: String) = key
    override val libVersion = "Version"
    override val libVersionHint = { v: Int -> "v$v · auto-increments on save" }
    override val libInstruction = "Instruction (markdown)"

    override val compareAction = "Compare"
    override val compareTitle = "Compare drafts"
    override val compareLeft = "Left draft"
    override val compareRight = "Right draft"
    override val compareChoose = "Compare with"
    override val compareUndo = "Undo"
    override val compareCopyToLeft = "Copy this change into the left draft"
    override val compareCopyToRight = "Copy this change into the right draft"
    override val compareSaveAsRule = "Save as rule…"
    override val compareSaveAsRuleTitle = "Save difference as rule"
    override val compareSaveAsRuleBody = "Text"
    override val compareResetLeft = "Reset left"
    override val compareResetRight = "Reset right"
    override val compareSaveLeft = "Save left"
    override val compareSaveRight = "Save right"
    override val compareSaveBoth = "Save both"
    override val compareNoCandidates = "No other objects of this type to compare."
    override val compareModeDiff = "Differences"
    override val compareModeEdit = "Edit"
    override val comparePrevDifference = "Previous difference"
    override val compareNextDifference = "Next difference"
    override val compareHunkPosition = { index: Int, total: Int -> "$index of $total" }
    override val compareDifferences = { count: Int -> "$count ${if (count == 1) "difference" else "differences"}" }
    override val compareIdentical = "The two bodies are identical."
    override val compareFileOpen = "Compare"
    override val compareFileTitle = { name: String -> "Compare $name" }
    override val compareFileApply = "Apply to the draft"
    override val compareFileNoCandidates = "No other instruction file to compare with."

    override val translateShow = "Translate"
    override val translateHide = "Hide translation"
    override val translateSwap = "Swap"
    override val translateRefresh = "Update"
    override val translateReplace = "Replace original"
    override val translateWorking = "Translating…"
    override val translateOriginalTitle = "Original"
    override val translateColumnTitle = { from: String, to: String -> "Translation $from → $to" }
    override val translateStale = "The text changed since this translation."
    override val translateReadOnly = "Reading mode: hide the translation to edit the text."
    override val translatePackMissing = "The language pack is not downloaded yet."
    override val translateOpenSettings = "Open System Settings"
    override val translateUnsupported = "This language pair is not offered by macOS."
    override val translateUnavailable = "Translation is available on macOS only."
    override val translateFailed = "Translation failed."

    override val libNewRuleTitle = "New rule"
    override val libNewGroupTitle = "New group"
    override val libNewProfileTitle = "New profile"
    override val libDeleteRuleConfirm = { name: String -> "Delete rule \"$name\"? This also removes it from groups." }
    override val libDeleteGroupConfirm = { name: String -> "Delete group \"$name\"? Rules are kept." }
    override val libDeleteProfileConfirm = { name: String -> "Delete profile \"$name\"? Library objects are kept." }
    override val libExportedTitle = "Exported"
    override val libExportedText = { zip: String -> "Library written to $zip" }
    override val libExportFailedTitle = "Export failed"
    override val libImportFailedTitle = "Import failed"
    override val libImportFailedText = { zip: String, msg: String -> "Could not read $zip: $msg" }
    override val libNothingToImportTitle = "Nothing to import"
    override val libNothingToImportText = "The archive matches the library."
    override val libImportTitle = "Import library"
    override val libNewMcpTitle = "New MCP server"
    override val libSearchObjects = { count: Int -> "Search $count ${if (count == 1) "object" else "objects"}…" }
    override val libSearchClear = "Clear search"
    override val libByGroup = "By group"
    override val libByType = "By type"
    override val libByArea = "By area"
    override val libProjectScoped = "Project-scoped"
    override val libSource = "Source · skills / subagents"
    override val libLocalSkills = "Local"
    override val libImportedSkills = "Imported from Git"
    override val libUpdateAvailable = "Update available"
    override val libNoMatches = "Nothing matches the current filters."
    override val libMembersCount = { count: Int -> "Members · $count" }
    override val libEditFocus = "Edit"
    override val placeEditInLibrary = "Edit in Library"
    override val libNewObject = "+ New"
    override val libUngrouped = "Ungrouped"
    override val libBody = "Body"
    override val libInstalledIn = { count: Int -> if (count == 0) "Installed in" else "Installed in · $count" }
    override val libNotInstalled = "Not installed anywhere."
    override val libHistory = "History"
    override val libHistoryEmpty = "No commits yet."
    override val libDiffTitle = { name: String -> "Changes: $name" }
    override val libDiffOpen = "Show changes"
    override val libDiffEmpty = "This revision changed nothing in this object."
    override val libBodyOpenEditor = "Open in the editor"
    override val libGitSourceTitle = "Imported from Git"
    override val libGitImmutable = "immutable — fork to edit"
    override val libForkToEdit = "Fork to edit"
    override val libForkBaseTitle = "Forked from"
    override val libForkUpstreamAhead = "Upstream has moved ahead"
    override val libCompareUpstream = "Compare with upstream"
    override val libMoreCount = { count: Int -> "+ $count more" }
    override val libUsage = "Usage"
    override val libUsageInstalled = "Installed somewhere"
    override val libUsageUnused = "Unused"
    override val libInPlaces = { count: Int -> "in $count ${if (count == 1) "target" else "targets"}" }
    override val libUnusedMark = "unused"
    override val libStatusSynced = "synced"
    override val libStatusUpdate = "update"
    override val libStatusModified = "modified"
    override val libPinnedTo = { project: String -> "pinned to $project" }
    override val libAutoMaintained = "auto-maintained"
    override val libSaveImpact = { count: Int, version: String ->
        "Installed in $count ${if (count == 1) "target" else "targets"}. " +
            "Saving bumps this to $version and marks every install update-available."
    }
    override val libSaveImpactUnused = { version: String ->
        "Not installed anywhere — saving only bumps the library version to $version."
    }
    override val libSelectedCount = { count: Int -> "$count selected" }
    override val libBulkInstall = "Install into…"
    override val libBulkAddToGroup = "Add to group…"
    override val libBulkExport = "Export"
    override val libBulkCombineRules = "Combine into one"
    override val libCombinedRuleName = "Combined rule"
    override val libBulkClear = "Clear"
    override val libInstallConfirm = "Install"
    override val libAddConfirm = "Add"
    override val libInstallIntoTitle = "Install into"
    override val libInstallIntoText = { count: Int ->
        "$count ${if (count == 1) "object" else "objects"} will be written into the ticked targets."
    }
    override val libInstallNoPlaces = "No targets are configured yet."
    override val libInstallReportTitle = "Install finished"
    override val libInstallWritten = { count: Int -> "Written: $count" }
    override val libInstallSkippedScope = { count: Int -> "Skipped — belongs to another project: $count" }
    override val libInstallSkippedUnsupported = { count: Int -> "Skipped — the target has no agent for it: $count" }
    override val libInstallSkippedModified = { count: Int -> "Skipped — edited by hand there: $count" }
    override val libInstallFailedCount = { count: Int -> "Failed: $count" }
    override val libUninstallHint = "Remove from this target"
    override val libUninstallTitle = "Remove from target"
    override val libUninstallText = { name: String, place: String ->
        "Remove \"$name\" from \"$place\"? The library keeps it. A copy edited by hand there has to be removed in that target."
    }
    override val libUninstallConfirm = "Remove"
    override val libUninstallReportTitle = "Removal finished"
    override val placeGroupUnmarked = { names: String ->
        "Kept, installed without this group's mark: $names. They were installed on their own, or by this group before Ruleblend recorded it."
    }
    override val placeGroupRemoveUnmarked = "Remove them too"
    override val placeGroupKeepUnmarked = "Keep"
    override val libUninstallRemoved = { count: Int -> "Removed: $count" }
    override val libAddToGroupTitle = "Add to group"
    override val libAddToGroupText = { count: Int ->
        "$count ${if (count == 1) "object" else "objects"} will join the chosen group. Groups do not nest."
    }
    override val libNoGroups = "No groups yet."
    override val libAddedToGroupTitle = "Added to group"
    override val libAddedToGroupText = { count: Int, group: String -> "$count joined \"$group\"." }
    override val libAddedNothingText = "Nothing to add: everything marked is already in the group."
    override val mcpTransport = "Transport"
    override val mcpTransportStdio = "stdio"
    override val mcpTransportHttp = "HTTP"
    override val mcpCommand = "Command"
    override val mcpArgs = "Arguments"
    override val mcpEnv = "Environment"
    override val mcpUrl = "URL"
    override val mcpHeaders = "Headers"
    override val mcpAddRow = "+ add"
    override val mcpServerNameHint = { id: String -> "Server name in agent configs: $id" }
    override val mcpBrokenConfig = "This block's config could not be parsed. Reset it to start over, or fix the file in the library."
    override val mcpResetConfig = "Reset config"
    override val mcpErrCommandRequired = "Command is required"
    override val mcpErrUrlInvalid = "URL must start with http:// or https://"
    override val mcpErrBlankKey = "A key is empty"
    override val mcpErrDuplicateKey = "Duplicate keys"
    override val mcpErrReservedName = { name: String -> "'$name' is reserved for Ruleblend itself" }
    override val changeNew = "new"
    override val changeUpdate = "update"
    override val changeUnchanged = "unchanged"
    override val changeConflict = "conflict — overwrites local"

    override val placeFilter = "Filter targets…"
    override val placeFilterClear = "Clear filter"
    override val placePinned = "Pinned"
    override val placeRecent = "Recent"
    override val placeUngrouped = "Projects"
    override val placePin = "Pin"
    override val placeUnpin = "Unpin"
    override val placeNewSet = "New set…"
    override val placeRenameSet = "Rename set…"
    override val placeDeleteSet = "Delete set"
    override val placeMoveToSet = { name: String -> "Move to \"$name\"" }
    override val placeRemoveFromSet = "Remove from set"

    override val placeModeNone = "not managed"
    override val placeModeLegacy = "legacy"
    override val placeModePartial = "partial"
    override val placeModeOwned = "owned"
    override val placePointerTo = { name: String -> "→ @$name" }
    override val placeOwnedNotice = "Owned file: Ruleblend renders the whole file, there is no hand-written text."
    override val placePointerNotice = { name: String -> "Pointer file: the agent reads $name through this import line. No blocks live here." }
    override val placeNoBlocks = "No blocks installed here yet."
    override val placeNoServers = "No MCP servers installed here yet."
    override val placeNoSkills = "No skills installed here yet."
    override val placeNoSubagents = "No subagents installed here yet."
    override val placeNoOurSkills = { count: Int ->
        "No Ruleblend skills installed here; $count other ${if (count == 1) "skill" else "skills"}."
    }
    override val placeNoOurSubagents = { count: Int ->
        "No Ruleblend subagents installed here; $count other ${if (count == 1) "subagent" else "subagents"}."
    }
    override val placeNoOurServers = { count: Int ->
        "No Ruleblend MCP servers installed here; $count other ${if (count == 1) "server" else "servers"}."
    }
    override val placeObjectCount = { ours: Int, foreign: Int -> "$ours/$foreign" }
    override val placeObjectCountHint = { ours: Int, foreign: Int -> "$ours ours · $foreign other" }
    override val placeForeignEntries = "Other entries"
    override val placeBuiltInEntries = "Built into Ruleblend"
    override val placeBuiltInManaged = "Built-in"
    override val placeBuiltInReadOnly = "Read-only here. Update or remove it in Settings."
    override val placeHideEntry = "Hide"
    override val placeUnhideEntry = "Unhide"
    override val placeTakeOwnership = "Link to library"
    override val placeRestoreOrphan = "Restore to Library"
    override val placeRemoveOrphan = "Uninstall"
    override val placeRemoveOrphanConfirm = { name: String -> "Uninstall orphaned '$name'?" }
    override val placeRemoveForeignConfirm = { name: String, path: String -> "Delete '$name' from $path?" }
    override val placeSkillNameTaken = { id: String -> "The library already has a skill named '$id'" }
    override val placeSkillNameHint = "Lowercase letters, digits and dashes; the name must be free."
    override val placeSubagentNameTaken = { id: String -> "The library already has a subagent named '$id'" }
    override val placeSubagentNameHint = "The id must be free."
    override val placeMcpNameTaken = { id: String -> "The library already has an MCP server named '$id'" }
    override val placeMcpNameHint = "The id must be free."
    override val placeShowHiddenEntries = { count: Int -> "Show hidden ($count)" }
    override val placeHideHiddenEntries = { count: Int -> "Hide hidden ($count)" }
    override val placeFileMissing = "This file does not exist yet."
    override val placeProjectMissing =
        "This project folder no longer exists. Nothing is written here until it is back or removed from the list."
    override val placeTabWrittenTo = { files: String -> "Written to: $files" }
    override val placeBackup = "Back up"
    override val placeBackupUpdate = "Update backup"
    override val placeBackupHint = "Save a backup of the entire file in its current state."
    override val placeBackupUpdateHint = "Replace the previous backup with the entire file in its current state."
    override val placeRestoreHint = "Replace the entire file with its saved backup. Changes made since that backup will be lost."
    override val placeDisownHint = "Stop managing this file in Ruleblend. Its text will remain, but Ruleblend will no longer update it."
    override val placeShowNoticeHint = "Add a warning inside the file that manual edits to managed text may be overwritten by Ruleblend."
    override val placeHideNoticeHint = "Remove the manual-edit warning from the file. Ruleblend will continue managing its text."
    override val placeRestore = "Restore"
    override val placeRestoreConfirm = { file: String ->
        "Replace $file with its backup? Changes made since that backup will be lost."
    }
    override val placeMigrateLegacy = "Migrate legacy markers"
    override val placeDisown = "Stop managing"
    override val placeDisownConfirm = { file: String ->
        "Stop managing $file? Its text stays in the file, but Ruleblend will no longer update it."
    }
    override val placeUnlinkEntry = "Unlink from library"
    override val placeShowNotice = "Show edit warning"
    override val placeHideNotice = "Hide edit warning"
    override val placeAssistants = "Project assistants"
    override val placeAssistantsConnected = { on: Int, total: Int -> "$on of $total connected" }
    override val placeAssistantsConfigure = "Configure"
    override val placeAssistantsCollapse = "Collapse"
    override val placeAssistantsSearch = "Search assistants"
    override val placeAssistantsSearchClear = "Clear search"
    override val placeAssistantsNoMatch = "No assistant matches."
    override val placeLaunch = "Launch"
    override val placeProfiles = "Profiles"
    override val placeAddProfile = "Add profile"
    override val placeDetachProfile = { name: String -> "Detach $name: its objects are removed, the base stays." }
    override val placeProfileToggleHint = { name: String -> "Turn $name on or off for this project." }
    override val placeProfileReportTitle = "Profile switch finished"
    override val placeProfileReportApplied = { written: Int, removed: Int -> "Installed: $written. Removed: $removed." }
    override val placeProfileReportMissing = { ids: String -> "Attached but no longer in the library: $ids." }
    override val placeAttachProfile = "Attach profile"
    override val placeAttachMerge = "Merge"
    override val placeAttachReplace = "Replace"
    override val placeAttachNoProfiles = "Every library profile is already attached to this project."
    override val placeReplaceBaseHint = { count: Int -> "Replace removes $count base object(s). Hand-edited copies stay in place." }
    override val placeReplaceBaseEmpty = "Replace has no base objects to remove."
    override val placeLaunchNew = { agent: String -> "New session in $agent" }
    override val placeLaunchResume = { agent: String -> "Resume the last $agent session" }
    override val placeLaunchHint = { agent: String -> "Open $agent in this project's folder." }
    override val placeLaunchHintResumable = { agent: String ->
        "Open $agent in this project's folder. Right-click to resume its last session."
    }
    override val placeTabNothingIn = { files: String -> "Nothing in: $files" }
    override val placeNotInLibrary = "not in library"
    override val placeInstall = "Install"
    override val placeRemove = "Remove"
    override val placeKeep = "Keep"
    override val placeDriftNotice = "Edited here. Restore the library version, save the edit as a new one, or keep it."
    override val placeDriftKept = "edit kept"
    override val placeDriftApart =
        "The agents here hold different edits. Save the copy that becomes the next library version; the others are written over."
    override val placeMoveTop = "Move to the top of the run"
    override val placeMoveBottom = "Move to the bottom of the run"

    override val paletteSaveAsGroup = "Create a group from this place"
    override val paletteSaveAsGroupTitle = "New group from this place"
    override val paletteSaveAsGroupHint = { count: Int ->
        "Saves the $count ${if (count == 1) "object" else "objects"} installed here as a library group. " +
            "An existing name updates that group."
    }
    override val paletteRecommended = "Recommended"
    override val paletteInSet = { installed: Int, total: Int, set: String -> "in $installed of $total $set" }
    override val paletteLibrary = "Library"
    override val paletteSearch = { count: Int -> "Search $count ${if (count == 1) "object" else "objects"}…" }
    override val paletteSearchClear = "Clear search"
    override val paletteNoMatches = "Nothing matches."
    override val paletteShowMore = { left: Int -> "Show more ($left left)" }
    override val paletteExpandAll = "Expand all"
    override val paletteCollapseAll = "Collapse all"
    override val paletteFound = "Found in project"
    override val paletteFoundAgent = "Found in agent"
    override val paletteFoundLines = { lines: Int -> "$lines ${if (lines == 1) "line" else "lines"} · hand-written" }
    override val paletteUnsupported = "not supported here"
    override val placeSaveToLibrary = "Save to library as…"
    override val placeHandText = "Hand-written text"

    override val intAgents = "AI assistants"
    override val intProjects = "Projects"
    override val intAddProject = "+ Add project"
    override val intEmptySelection = "Add a project or install an agent"
    override val intSkills = "Skills"
    override val intAdoptTitle = { file: String -> "Save $file to library" }
    override val intAdoptReplaceLabel = "Replace the original text with a managed region"
    override val intAdoptOrderBlocked = { names: String ->
        "Replacing would move text: $names sits between the chosen sections and would end up outside the managed region. Tick it too, or clear the replace option."
    }
    override val intAdoptOrderAroundRun =
        "This text sits on both sides of the managed region already in the file, so replacing it would move part of it. Save it without replacing."
    override val intAdoptSizeWarning = { lines: Int, kb: Int ->
        "Large rule: $lines lines, $kb KB. It will be copied in full into every target it is installed into."
    }
    override val fieldGeneralDescription = "General description"
    override val intAdoptDescriptionDefault = { target: String, file: String ->
        if (target.isBlank()) "Saved from $file" else "Saved from $target ($file)"
    }
    override val intAdoptSections = { count: Int -> "Sections ($count)" }
    override val intAdoptSectionNew = "new rule"
    override val intAdoptSectionDuplicate = "already in library"
    override val intAdoptSectionSimilar = { name: String -> "differs from \"$name\" — probably an update" }
    override val intAdoptActionSkip = "Skip"
    override val intAdoptActionOverwrite = "Overwrite"
    override val intAdoptActionCreateNew = "Create new"
    override val intAdoptSelectAll = "All"
    override val intAdoptSelectNone = "None"
    override val intAdoptScanSummary = { imports: Int, broken: Int ->
        val parts = buildList {
            if (imports > 0) add("$imports @import${if (imports == 1) "" else "s"}")
            if (broken > 0) add("$broken broken link${if (broken == 1) "" else "s"}")
        }
        "Heads up: ${parts.joinToString(", ")}. Hover ⚠ for details."
    }
    override val intAdoptScanImportsHeader = { n: Int -> "$n @import${if (n == 1) "" else "s"} found" }
    override val intAdoptScanImportsHint =
        "Only Claude Code expands @imports. Codex, Pi, Kimi Code and ZCode keep them as plain text."
    override val intAdoptScanBrokenHeader = { n: Int -> "$n unresolved link${if (n == 1) "" else "s"}" }
    override val intAdoptScanBrokenHint =
        "These paths do not exist relative to the source file, so they will not resolve when the block is installed elsewhere either."
    override val intTakeLibrary = "Restore from library"
    override val intAcceptLocal = { v: String? -> if (v == null) "Save new version" else "Save as v$v" }
    override val ctxRemoveProject = "Remove project"
    override val intRemoveProjectConfirm = { name: String -> "Remove project \"$name\" from Ruleblend?" }
    override val intEditFile = "Edit file"
    override val intFileEditorTitle = { name: String -> "Edit $name" }
    override val libNewRuleInGroupTitle = "New rule in this group"
    override val intMcpServers = "MCP servers"

    override val splitAction = "Split"
    override val splitDialogTitleLibrary = { name: String -> "Split \"$name\" into parts" }
    override val splitDialogTitleAdopt = "Split section into parts"
    override val splitPartsCount = { n: Int -> "$n part${if (n == 1) "" else "s"}" }
    override val splitReset = "Reset"
    override val splitConfirm = { n: Int -> "Split into $n parts" }
    override val splitEmptyBody = "empty body"
    override val splitIdConflict = { id: String -> "id already exists: $id" }
    override val splitIdDuplicate = "duplicate name in this split"
    override val splitAddCut = "Cut here"
    override val splitRemoveCut = "Remove cut"
    override val splitTooShort = "The block is a single line — nothing to split."
    override val splitNoCuts = "Click between lines on the left to add a cut."

    override val setOpenConfigFolder = "Open config folder"
    override val setFooter =
        "Settings are machine-local and never travel with export or Git sync. Save-as-you-go: there is no Apply button."
    override val setSectionGeneral = "General"
    override val setSectionLibrary = "Library"
    override val setSectionAgents = "AI assistants"
    override val setSectionShortcuts = "Shortcuts"
    override val setSectionAbout = "About"
    override val setSectionFolding = "Expanded sections"
    override val setFoldingSum = "how lists open on entry"
    override val setGeneralSum = "applies immediately"
    override val setTheme = "Appearance"
    override val setThemeHint = "System follows the OS; the choice is saved."
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val setLanguage = "Language"
    override val setLanguageHint = "Interface only. Library content is never translated."
    override val setGroupsExpansion = "Groups on entry"
    override val setGroupsExpansionHint = "Applies when a grouped screen opens."
    override val setGroupsExpanded = "Expanded"
    override val setGroupsCollapsed = "Collapsed"
    override val setPlaceLibraryExpansion = "Projects: library palette"
    override val setPlaceLibraryExpansionHint = "Type sections of the library in the right column of Projects."
    override val setPlaceRulesExpansion = "Projects: Rules"
    override val setPlaceRulesExpansionHint = "Bodies of rules in the Rules tab."
    override val setPlaceSkillsExpansion = "Projects: Skills"
    override val setPlaceSkillsExpansionHint = "Entries in the Skills tab."
    override val setPlaceSubagentsExpansion = "Projects: Subagents"
    override val setPlaceSubagentsExpansionHint = "Entries in the Subagents tab."
    override val setPlaceMcpExpansion = "Projects: MCP"
    override val setPlaceMcpExpansionHint = "Server definitions in the MCP tab."
    override val setFileEditor = "File editor"
    override val setFileEditorHint = "Where Edit file in Targets opens a managed file."
    override val setTranslation = "Translation"
    override val setTranslationHint =
        "Whether the app offers translation at all, and the language pair and quality it uses."
    override val setTranslationEnabled = "Offer translation"
    override val translateQualityFast = "Fast"
    override val translateQualityHigh = "Accurate"
    override val translatePackReady = "The language pack is installed."
    override val fileEditorInApp = "In Ruleblend"
    override val fileEditorExternal = "External"
    override val fileEditorApplication = "Application"
    override val fileEditorSystemDefault = "System default"
    override val fileEditorChoose = "Choose…"
    override val fileEditorReset = "Reset"
    override val setWorkspace = "Workspace"
    override val setWorkspaceHint = "Layout state kept on this machine."
    override val setResetColumns = "Reset column widths"
    override val setResetColumnsDone = "Layout reset"
    override val setClearRecent = "Clear recent targets"
    override val setClearRecentDone = "Cleared"
    override val setWorkspaceNote = "Pinned targets and project sets are kept."
    override val setLibraryPath = "Location"
    override val setLibraryPathHint = "A Git repository. Everything you export or sync lives here."
    override val setLibraryPathChange = "Change…"
    override val setRevealInFinder = "Reveal in Finder"
    override val setRevealInFiles = "Show in file manager"
    override val setReveal = "Reveal"
    override val setStatsRules = { n: Int -> "$n ${if (n == 1) "rule" else "rules"}" }
    override val setStatsSkills = { n: Int -> "$n ${if (n == 1) "skill" else "skills"}" }
    override val setStatsMcp = { n: Int -> "$n MCP ${if (n == 1) "server" else "servers"}" }
    override val setStatsGroups = { n: Int -> "$n ${if (n == 1) "group" else "groups"}" }
    override val setStatsCommits = { n: Int -> "$n ${if (n == 1) "commit" else "commits"}" }
    override val setStatsLastChange = { ago: String -> "last change $ago" }
    override val agoJustNow = "just now"
    override val agoMinutes = { n: Long -> "$n min ago" }
    override val agoHours = { n: Long -> "$n ${if (n == 1L) "hour" else "hours"} ago" }
    override val agoDays = { n: Long -> "$n ${if (n == 1L) "day" else "days"} ago" }
    override val setLibraryPathRestartHint = "Takes effect after restart."
    override val setUndo = "Undo"
    override val setSourceCheck = "Source updates"
    override val setSourceCheckHint = "When imported Git skill and subagent sources are checked for updates."
    override val sourceCheckManual = "Manual"
    override val sourceCheckOnLaunch = "On launch"
    override val setSources = "Sources"
    override val setSourcesHint = "Repositories imported by skills and subagents in your library."
    override val setSourcesEmpty = "No imported Git sources"
    override val setSourceSkills = { n: Int -> "$n ${if (n == 1) "skill" else "skills"}" }
    override val setSourceHead = { head: String -> "HEAD · $head" }
    override val setSourceReopenImport = "Reopen import"
    override val setNameFormat = "Name format"
    override val setLineEnding = "Line endings"
    override val setLineEndingHint = "LF is recommended on every platform. Applies to library definitions and managed rules; existing definitions are converted. Imported skill files stay unchanged."
    override val setNameFormatHint = "Applied whenever a rule or group is saved. Existing names are not rewritten."
    override val nameFormatKebab = "kebab-case"
    override val nameFormatCamel = "camelCase"
    override val nameFormatSnake = "snake_case"
    override val nameFormatFree = "free"
    override val setRemoteGit = "Remote Git backup"
    override val remoteGitHint =
        "Two-way library sync. HTTPS uses your Git credential helper (or ~/.netrc)."
    override val remoteGitUrl = "Repository URL"
    override val remoteGitBranch = "Branch"
    override val remoteGitAutomatic = "Sync after every local commit"
    override val remoteGitSaveAndSync = "Save and sync"
    override val remoteGitCancel = "Cancel"
    override val remoteGitSyncNow = "Sync now"
    override val remoteGitEdit = "Edit"
    override val remoteGitRemove = "Remove"
    override val remoteGitSyncing = "Syncing…"
    override val remoteGitPushed = "Pushed"
    override val remoteGitUpToDate = "Up to date"
    override val remoteGitFailed = "Sync failed"
    override val remoteGitNotSynced = "Not synced yet"
    override val remoteGitPushedAgo = { ago: String -> "pushed $ago" }
    override val remoteGitBehind = { commits: Int -> "$commits incoming" }
    override val remoteGitMerged = "merged"
    override val remoteGitConflicts = { count: Int -> "$count ${if (count == 1) "conflict" else "conflicts"}" }
    override val remoteGitRestored = { count: Int -> "$count ${if (count == 1) "deletion" else "deletions"} restored" }
    override val remoteGitAutoShort = "automatic"
    override val remoteGitManualShort = "manual"
    override val remoteGitLocalKept = "Local history is kept even when a push fails."
    override val remoteGitUnrelatedTitle = "Unrelated library histories"
    override val remoteGitUnrelatedText = "This remote is not based on the local library. Merge it with the normal import conflict policy, or export the local library to ZIP and replace it with the remote copy."
    override val remoteGitMergeAsImport = "Merge as import"
    override val remoteGitReplaceAfterExport = "Export ZIP and replace"
    override val setLibraryData = "Import & export"
    override val setLibraryDataHint = "The whole library as one archive. Selective export lives in Library."
    override val setImportNote = "Import merges by id and shows what would change before writing anything."
    override val setAgentSummary = { visible: Int, total: Int, connected: Int, mcp: Int ->
        "$visible of $total visible · $connected of $mcp connected to Ruleblend MCP"
    }
    override val setColAgent = "AI assistant"
    override val setColCanManage = "Can manage"
    override val setColVisible = "Visible"
    override val setColMcp = "Ruleblend MCP"
    override val setColCli = "CLI"
    override val setCliArgumentsHint = "Extra arguments"
    override val setTerminal = "Terminal"
    override val setTerminalHint = "Which application an agent's CLI opens in from a project header."
    override val setAgentNotDetected = "not detected"
    override val setAgentRulesCapability = "Rules"
    override val setAgentMcpCapability = "MCP"
    override val setAgentSkillsCapability = "Skills"
    override val setAgentSubagentsCapability = "Subagents"
    override val setCapabilityGlobalOnly = "global only"
    override val setCapabilityProjectOnly = "project only"
    override val setCapabilityUnavailable = "not supported"
    override val setAgentVisible = "Visible"
    override val setAgentHidden = "Hidden"
    override val setConnManual = "manual — see below"
    override val connectAction = "Connect"
    override val connectActionUpdate = "Update"
    override val disconnectAction = "Disconnect"
    override val connectStatusConnected = "Connected"
    override val connectStatusNotConnected = "Not connected"
    override val connectStatusStale = "Reconnect needed — the app was moved"
    override val connectStatusSkillOutdated = "Skill out of date"
    override val connectStatusMcpOutdated = "MCP entry out of date"
    override fun setSkillVersion(installed: Int, current: Int) =
        if (installed == current) "skill v$installed" else "skill v$installed → v$current"
    override fun setEntryVersion(installed: Int, current: Int) =
        if (installed == current) "entry v$installed" else "entry v$installed → v$current"
    override val setUpdateAll = "Update everywhere"
    override fun setUpdateAllHint(count: Int) = "$count assistant(s) hold an older copy"
    override val homeIntegrationTitle = "Assistants need Ruleblend updated"
    override val homeIntegrationUpdateAll = "Update everywhere"
    override val homeIntegrationUpdate = "Update"
    override val homeIntegrationUpdating = "Updating…"
    override val homeIntegrationForeign = "A skill named \"ruleblend\" is not ours — left untouched"
    override val libBuiltIn = "built-in"
    override val libBuiltInManaged =
        "Ships with the app. Connect, update or remove it per assistant in Settings; it cannot be edited or deleted here."
    override val connectStatusSkillForeign = "A skill named \"ruleblend\" already exists — remove it first"
    override val connectStatusMcpForeign = "An MCP server named \"ruleblend\" already exists — remove it first"
    override val connectManualHint = "Other agents — add a stdio MCP server named \"ruleblend\" running:"
    override val setCopy = "Copy"
    override val setCopied = "Copied"
    override val setAgentsFoot = "Hidden agents disappear from Home, Targets and Coverage; their files are left untouched."
    override val setShortcutsSum = "⌘ on macOS, Ctrl elsewhere — both are accepted"
    override val setKeyPalette = "Command palette"
    override val setKeyClose = "Close palette / dialog"
    override val setKeyScoped = "Scoped search in ${commandShortcut("K")}"
    override val setShortcutsFoot = "Shortcuts never write anything: they move between surfaces and open the palette."
    override val setAboutBuild = { build: String, os: String, jvm: String -> "build $build · $os · JVM $jvm" }
    override val setCopyDiagnostics = "Copy diagnostics"
    override val setDiagnosticsNote = "Version, OS, config and library paths, detected agents."
    override val setFiles = "Files"
    override val setFilesHint = "Where this machine keeps state."
    override val setFileConfig = "Config"
    override val setFileLibrary = "Library"
}

/**
 * Russian plural form for [n]: [one] for 1, 21, 31…, [few] for 2–4, 22–24…, [many] otherwise.
 * The teens (11–14) take [many] despite their last digit.
 */
private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    if (mod100 in 11..14) return many
    return when (n % 10) {
        1 -> one
        2, 3, 4 -> few
        else -> many
    }
}

object RuStrings : Strings {
    override val navHome = "Главная"
    override val navPlace = "Проекты"
    override val navLibrary = "Библиотека"
    override val navCoverage = "Покрытие"
    override val navSettings = "Настройки"
    override val navCollapseSidebar = "Свернуть меню"
    override val navExpandSidebar = "Развернуть меню"
    override val navResolve = "Конфликты"
    override val resolveOpenPlace = "Открыть проект"

    override val commandPill = "${commandShortcut("K")} — поиск, проекты, действия"
    override val commandPlaceholder = "Поиск по библиотеке и проектам…"
    override val commandNoMatches = "Ничего не найдено"
    override val commandScopeHint = "r: правила · s: навыки · m: mcp · p: проекты"
    override val commandTagAction = "экран"
    override val commandTagPlace = "проект"
    override val commandTagRule = "правило"
    override val commandTagSubagent = "субагент"
    override val commandTagSkill = "навык"
    override val commandTagMcp = "mcp"
    override val commandTagGroup = "группа"
    override val commandTagProfile = "профиль"
    override val commandGoTo = { name: String -> "Перейти: $name" }

    // Resolve
    override val resolveTitle = "Разрешение конфликтов"
    override val resolveClasses = "Классы конфликтов"
    override val resolveScanned = { total: Int -> "$total вхождений по всем проектам" }
    override val resolveOccurrences = { count: Int -> "$count вхожд." }
    override val resolveVersion = { version: Int -> "v$version" }
    override val resolveDrift = { added: Int, removed: Int -> "+$added \u2212$removed строк" }
    override val resolveLegacyMarkers = "старые маркеры"
    override val resolveAmbiguousRow = "нужно решение вручную"
    override val resolveLegacyRow = "читается \u00b7 будет перезаписан"
    override val resolveClassHandEdited = "Блоки, правленные вручную"
    override val resolveClassAmbiguous = "Неоднозначные блоки"
    override val resolveClassLegacy = "Файлы в старом формате"
    override val resolveExplainHandEdited =
        "Текст блока в файле отличается от библиотечной версии внутри управляемой области."
    override val resolveExplainAmbiguous =
        "В одном управляемом блоке изменены два правила сразу — ни одну правку нельзя отнести к одному правилу."
    override val resolveExplainLegacy =
        "Старый формат маркеров. Файл читается и будет перезаписан в текущем формате при следующей записи."
    override val resolveNextAmbiguous =
        "Здесь Ruleblend не угадывает: откройте каждый проект и решите по каждому блоку."
    override val resolveNextLegacy = "Решать ничего не нужно."
    override val resolveStrategy = "Стратегия"
    override val resolveStrategyInherit = "Как выбрано"
    override val resolveStrategyRestore = "Вернуть библиотечный текст"
    override val resolveStrategyRestoreHint =
        "Управляемая область собирается заново из текущих библиотечных версий. Правка теряется."
    override val resolveStrategySave = "Сохранить как новую версию"
    override val resolveStrategySaveHint =
        "Правка становится следующей версией правила; в остальных проектах появится обновление."
    override val resolveStrategySkip = "Пропустить"
    override val resolveStrategySkipHint = "Файл не трогаем. Конфликт остаётся в списке."
    override val resolveSelectAll = "Отметить все"
    override val resolveMarked = { count: Int -> "отмечено: $count" }
    override val resolvePlanTitle = "План"
    override val resolvePlanNothing = "Ничего не отмечено."
    override val resolvePlanText = { restores: Int, saves: Int, places: Int ->
        "вернуть: $restores · сохранить: $saves · проектов: $places"
    }
    override val resolvePlanSkipped = { count: Int -> "пропущено отмеченных строк: $count" }
    override val resolveApply = { writes: Int -> "Применить к $writes блокам" }
    override val resolveApplying = "Применяем…"
    override val resolveSafety =
        "Файлы перезаписываются атомарно. Текст вне управляемых областей не меняется."
    override val resolveDone = "Пакет выполнен"
    override val resolveDoneRestored = { count: Int -> "возвращено из библиотеки: $count" }
    override val resolveDoneSaved = { count: Int -> "сохранено новой версией: $count" }
    override val resolveDoneSkipped = { count: Int -> "оставлено как есть: $count" }
    override val resolveDoneFailed = { places: String -> "не записано в: $places" }
    override val resolveNotScannedTitle = "Скан ещё не выполнялся"
    override val resolveNotScannedText =
        "Раздел классифицирует одно чтение всех управляемых файлов. Запустите скан, чтобы увидеть конфликты."
    override val resolveNothingTitle = "В этом классе пусто"
    override val resolveNothingText = "Ни в одном проекте нет конфликтов этого вида."
    override val homeRescan = "Пересканировать проекты"
    override val homeScanning = "Сканирование…"
    override val homeScanDuration = { millis: Long -> "скан за $millis мс" }
    override val homeNeedsAttention = "Требует внимания"
    override val homeFleetHealth = "Состояние проектов"
    override val homeProfiles = "Профили"
    override val homeProfilesRestartHint = { agents: String ->
        "Правила и MCP применятся после перезапуска сессии $agents; скиллы и субагенты — сразу."
    }
    override val homeProfilesSwitchFailed = { reason: String -> "Не удалось переключить профиль: $reason" }
    override val homeShortcuts = "Закреплённые и недавние"
    override val homeFleetSummary = { total: Int, clean: Int, updates: Int, conflicts: Int ->
        "$total ${plural(total, "проект", "проекта", "проектов")} · $clean без замечаний · " +
            "$updates с обновлениями · $conflicts с правками"
    }
    override val homeLibraryLine = { rules: Int, skills: Int, mcp: Int, groups: Int ->
        "Библиотека: $rules ${plural(rules, "правило", "правила", "правил")} · $skills skills · " +
            "$mcp MCP · $groups ${plural(groups, "группа", "группы", "групп")}"
    }
    override val homeConflicts = { blocks: Int, places: Int ->
        "$blocks ${plural(blocks, "блок изменён", "блока изменены", "блоков изменены")} вручную в " +
            "$places ${plural(places, "проекте", "проектах", "проектах")}"
    }
    override val homeConflictsHint = "Рукописные правки будут затёрты при следующей установке"
    override val homeUpdates = { updates: Int, places: Int ->
        "$updates ${plural(updates, "обновление доступно", "обновления доступны", "обновлений доступны")} в " +
            "$places ${plural(places, "проекте", "проектах", "проектах")}"
    }
    override val homeUpdatesHint = { worst: String -> "Сильнее всего отстают: $worst" }
    override val homeUnadopted = { places: Int ->
        "Непринятые рукописные правила в $places ${plural(places, "проекте", "проектах", "проектах")}"
    }
    override val homeUnadoptedHint = { lines: Int ->
        "$lines ${plural(lines, "строка", "строки", "строк")} вне managed-региона можно принять в библиотеку"
    }
    override val homeLegacy = { files: Int, places: Int ->
        "$files ${plural(files, "файл", "файла", "файлов")} в старой разметке в " +
            "$places ${plural(places, "проекте", "проектах", "проектах")}"
    }
    override val homeLegacyHint = "Установка сюда переведёт файл на текущие маркеры"
    override val homeNewAgent = { agent: String -> "Обнаружен новый агент: $agent" }
    override val homeNewAgentHint = { file: String, projects: Int ->
        "Глобальный файл $file пока не под управлением · доступно $projects " +
            plural(projects, "проект", "проекта", "проектов")
    }
    override val homeRowModified = { count: Int -> "$count ${plural(count, "изменён", "изменено", "изменено")}" }
    override val homeRowUpdates = { count: Int -> "$count ${plural(count, "обновление", "обновления", "обновлений")}" }
    override val homeRowLines = { count: Int -> "$count ${plural(count, "строка", "строки", "строк")}" }
    override val homeRowFiles = { count: Int -> "$count ${plural(count, "файл", "файла", "файлов")}" }
    override val homeResolveAll = "Разрешить все"
    override val homeReview = "Разобрать"
    override val homeOpenPlace = "Открыть проект"
    override val homeHideAgent = "Скрыть агента"
    override val homeAllClearTitle = "Ничего не требует внимания"
    override val homeAllClearText = "Все управляемые блоки синхронны."
    override val homeNotScannedTitle = "Проекты ещё не просканированы"
    override val homeNotScannedText = "Главная читает все управляемые файлы всех проектов. Запустите скан, чтобы увидеть, что требует внимания."
    override val homeScanNow = "Сканировать проекты"
    override val homeEmptyLibraryTitle = "Библиотека пуста"
    override val homeEmptyLibraryText = "Устанавливать пока нечего. Создайте правило, skill или MCP-сервер в разделе «Библиотека»."
    override val homeOpenLibrary = "Открыть библиотеку"
    override val homeNoPlacesTitle = "Проектов пока нет"
    override val homeNoPlacesText = "Добавьте проект в разделе «Проекты» или подключите агента в настройках — и Главная начнёт следить за ними."
    override val homeSyncTitle = "Синхронизация библиотеки"
    override val homeSyncExplains =
        "Правила, скиллы, записи MCP и профили хранятся в Git-репозитории, чтобы библиотека была одинаковой " +
            "на всех ваших машинах. Это не про ассистентов, установленных на этой машине."
    override val homeSyncRemote = { url: String, branch: String -> "$url · $branch" }
    override val homeSyncAutomatic = "идёт автоматически"
    override val homeSyncManual = "только вручную"
    override val homeSyncBehind = { commits: Int -> "входящих коммитов: $commits" }
    override val homeSyncMerged = "Объединено"
    override val homeSyncMergedConflicts = { count: Int -> "Объединено с конфликтами: $count" }
    override val homeSyncFailed = { reason: String -> "Ошибка синхронизации: $reason" }
    override val homeSyncHistory = "История"
    override val homeSyncCompare = "Сравнить"
    override val homeSyncRestored = { count: Int -> "Восстановлено удалений: $count" }
    override val homeSyncUnrelated = "На удалённом сервере другая библиотека. Выберите, как их объединить."
    override val homeSyncOpenSettings = "Открыть настройки"
    override val homeSourceUpdatesTitle = "Обновления импортированных определений"
    override val homeSourceCheckNow = "Проверить"
    override val homeSourceChecking = { done: Int, total: Int -> "Проверяем источники $done/$total" }
    override val homeSourceLastChecked = { time: String -> "Последняя проверка: $time" }
    override val homeSourceNeverChecked = "Проверка ещё не выполнялась"
    override val homeSourceNothing = "Нечего синхронизировать"
    override val homeSourceUpdateAvailable = "Доступно обновление"
    override val homeSourcePathMissing = "Путь в upstream удалён"
    override val homeSourceUnavailable = "Источник недоступен"
    override val homeSourceUpdate = "Обновить"
    override val homeSourceUpdateAll = "Обновить все"
    override val homeSourceUpdating = "Обновляем…"
    override val homeSourceFailure = { reason: String -> "Ошибка работы с источником: $reason" }

    // Coverage
    override val coverageNotScannedText =
        "Покрытие агрегирует одно чтение всех управляемых файлов. Запустите скан, чтобы увидеть, где что установлено."
    override val coverageLegendSynced = "синхронно"
    override val coverageLegendMissing = "не установлено"
    override val coverageLegendUpdate = "есть обновление"
    override val coverageLegendModified = "изменено / конфликт"
    override val coverageLegendBar = "установлено / не установлено"
    override val coverageLegendFraction = "установлено из подходящих"
    override val coverageLegendOutOfScope = "сюда не ставится: правило закреплено за другим проектом или AI-ассистент не поддерживает этот тип"
    override val coverageLegendOutOfScopeShort = "сюда не ставится"
    override val coverageChipConflicts = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count ${plural(count, "конфликт", "конфликта", "конфликтов")}",
            coverageColumnAgents(agents).takeIf { agents > 0 },
            coverageColumnProjects(projects).takeIf { projects > 0 },
        ).joinToString(" · ")
    }
    override val coverageChipUpdates = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count ${plural(count, "обновление", "обновления", "обновлений")}",
            coverageColumnAgents(agents).takeIf { agents > 0 },
            coverageColumnProjects(projects).takeIf { projects > 0 },
        ).joinToString(" · ")
    }
    override val coverageCorner = { count: Int, agents: Int, projects: Int ->
        listOfNotNull(
            "$count ${plural(count, "объект", "объекта", "объектов")}",
            coverageColumnAgents(agents),
            coverageColumnProjects(projects),
        ).joinToString(" · ")
    }
    override val coverageAgentsPanel = "AI-ассистенты"
    override val coverageAgentsNote = "Скрытый AI-ассистент пропадает из колонок здесь, с «Главной» и из раздела «Проекты»."
    override val coverageAgentGlobal = "глобально"
    override val coverageAgentNoSkills = "не поддерживает skills"
    override val coverageOnlyInstalled = "Только установленное"
    override val coverageRowAll = "Все объекты"
    override val coverageNoRowsTitle = "Под эти фильтры ничего не подходит"
    override val coverageNoRowsText = "Верните тип объектов или снимите «Только установленное» и «Требует внимания»."
    override val coverageExpandRow = { name: String -> "Развернуть «$name»" }
    override val coverageCollapseRow = { name: String -> "Свернуть «$name»" }
    override val coverageExpandColumn = { name: String -> "Развернуть «$name»" }
    override val coverageCollapseColumn = { name: String -> "Свернуть «$name»" }
    override val coverageInstallHere = "Установить сюда"
    override val coverageRemoveHere = "Убрать отсюда"
    override val coverageWriting = "Запись…"
    override val coverageNoticeModified = "Там правили руками — решение принимается в разделе «Проекты», на файле."
    override val coverageNoticeScope = "Это правило закреплено за другим проектом."
    override val coverageNoticeUnsupported = "Ни один AI-ассистент этого проекта не поддерживает этот тип."
    override val coverageNoticeFailed = "Запись не удалась, проект остался прежним."
    override val coverageNoticeDismiss = "Скрыть"
    override val coverageBulkSelected = { count: Int -> "Отмечено объектов: $count" }
    override val coverageBulkInstall = "Установить в…"
    override val coverageBulkRemove = "Убрать из…"
    override val coverageBulkUpdate = "Обновить везде"
    override val coverageBulkClear = "Снять отметки"
    override val coverageBulkInstallTitle = { target: String -> "Установить в «$target»" }
    override val coverageBulkRemoveTitle = { target: String -> "Убрать из «$target»" }
    override val coverageBulkUpdateTitle = "Обновить везде"
    override val coverageBulkPlanText = { writes: Int, objects: Int, places: Int ->
        "Записей: $writes · объектов: $objects · проектов: $places"
    }
    override val coverageBulkPlanScope = { count: Int ->
        "Пар не войдёт: $count — закреплены за другим проектом или там нет подходящего AI-ассистента."
    }
    override val coverageBulkPlanNothing = "Записывать нечего: всё запрошенное уже в этом состоянии."
    override val coverageBulkApply = "Применить"
    override val coverageBulkDone = "Массовое действие завершено"
    override val coverageBulkRefused = { places: String -> "Ничего не записано в: $places" }
    override val coverageRowsLabel = "Строки:"
    override val coverageColumnsLabel = "Колонки:"
    override val coverageNeedsAttention = "Требует внимания"
    override val coverageExpandAllRows = "Развернуть все строки"
    override val coverageCollapseAllRows = "Свернуть все строки"
    override val coverageExpandAllColumns = "Развернуть все колонки"
    override val coverageCollapseAllColumns = "Свернуть все колонки"
    override val coverageBandSet = "набор"
    override val coverageBandUngrouped = "Вне наборов"
    override val coverageOpenResolve = "Разобрать"
    override val coverageLegendLeafInstalled = "установлено — клик убирает"
    override val coverageLegendLeafMissing = "не установлено — клик устанавливает"
    override val coverageAgentsSettings = "Настройки AI-ассистентов"
    override val coverageOpenPlaceNamed = { name: String -> "Открыть «$name»" }
    override val coverageColumnAgents = { count: Int -> "$count ${plural(count, "AI-ассистент", "AI-ассистента", "AI-ассистентов")}" }
    override val coverageColumnProjects = { count: Int -> "$count ${plural(count, "проект", "проекта", "проектов")}" }
    override val coverageRowCount = { count: Int -> "$count ${plural(count, "объект", "объекта", "объектов")} в строке" }
    override val coverageCellObjects = { installed: Int, fitting: Int -> "Установлено объектов: $installed из $fitting подходящих" }
    override val coverageCellPlaces = { installed: Int, fitting: Int -> "Установлен в $installed из $fitting подходящих мест" }
    override val coverageCellSplit = { synced: Int, updates: Int, modified: Int ->
        listOfNotNull(
            "синхронно: $synced".takeIf { synced > 0 },
            "обновление: $updates".takeIf { updates > 0 },
            "изменено: $modified".takeIf { modified > 0 },
        ).joinToString(", ")
    }
    override val coverageMarkRow = "Отметить для массового действия"
    override val libAddToFavorites = "Добавить в избранное"
    override val libRemoveFromFavorites = "Убрать из избранного"
    override val libGlobalScope = "Глобальные"

    override val actionSave = "Сохранить"
    override val actionDuplicate = "Дублировать"
    override val actionDelete = "Удалить"
    override val actionCancel = "Отмена"
    override val actionClose = "Закрыть"
    override val actionCreate = "Создать"
    override val actionUpdate = "Обновить"
    override val actionAdopt = "Сохранить в библиотеку"
    override val actionOk = "ОК"
    override val configBrokenTitle = "Не удалось прочитать настройки"
    override fun configBrokenText(path: String) =
        "Файл настроек не читается, поэтому Ruleblend запустился с настройками по умолчанию. " +
            "Ваш файл сохранён без изменений: $path."
    override val fieldName = "Название"
    override val fieldDescription = "Описание"
    override val actionFormatName = "Формат"
    override val fieldType = "Тип"
    override val fieldScope = "Область"
    override val fieldHeading = "Заголовок"
    override val fieldHeadingHint = "Без собственного заголовка"

    override val errorTitle = "Что-то пошло не так"
    override val errAdoptNotReplaced = { cause: String ->
        "Правило сохранено в библиотеку, но перезаписать исходный файл не удалось — его текст остался на месте.\n\n$cause"
    }

    override val libTypes = "Типы"
    override val libRulesLabel = "Правила"
    override val libGroups = "Группы"
    override val libProfiles = "Профили"
    override val libAllGroup = "Все"
    override val libSkills = "Навыки"
    override val libNewSkillTitle = "Новый skill"
    override val libImportSkillsAction = "Импорт Git"
    override val libSkillNameHint = "Строчные латинские буквы, цифры и дефисы; описание обязательно."
    override val libGroupContents = "Состав группы"
    override val libProfileContents = "Состав профиля"
    override val libSkillImportTitle = "Импорт skills и субагентов из Git"
    override val libSkillRepository = "URL репозитория"
    override val libSkillRepositoryHint = "Ruleblend читает skills и определения субагентов без выполнения кода репозитория."
    override val libSkillDiscover = "Найти определения"
    override val libSkillImportSelected = "Импортировать выбранные"
    override val libSkillFound = { count: Int, version: String -> "Найдено: $count · версия репозитория $version" }
    override val libGitFound = { skills: Int, agents: Int, errors: Int -> "$skills skills · $agents субагентов · $errors ошибок файлов" }
    override val libGitChooseAssistant = "Выберите исходного ассистента перед выбором определения"
    override val libGitPreview = "Предпросмотр"
    override val libGitReadOnly = "Импортированные определения доступны только для чтения. Для редактирования создайте изменённую копию."
    override val setSourceSubagents = { n: Int -> "$n ${plural(n, "субагент", "субагента", "субагентов")}" }
    override val libSkillSource = "Источник Git"
    override val libSkillCreateChanged = "Создать изменённую копию"
    override val libSkillUpdateFromGit = "Обновить из Git"
    override val libDeleteSkillConfirm = { name: String -> "Удалить skill «$name» из библиотеки?" }
    override val libMcp = "MCP"
    override val libExport = "Экспорт"
    override val libImport = "Импорт"
    override val libSelectRule = "Выберите правило"
    override val libSelectObject = "Выберите объект"
    override val libTypeRule = "Правила"
    override val libTypeSubagent = "Субагент"
    override val libNewSubagentTitle = "Новый субагент"
    override val libSubagents = "Субагенты"
    override val libSubagentModel = "Предпочтение модели"
    override val libSubagentModelHint = "Любое имя модели, например gpt-5.6-terra"
    override val libSubagentVariants = "Настройки по ассистентам"
    override val libSubagentVariantsHint = "Пустое поле — ассистент получит определение без него"
    override val libSubagentFieldTools = "Инструменты"
    override val libSubagentFieldToolsHint = "Через запятую, например Bash, Read, Grep"
    override val libSubagentFieldColor = "Цвет"
    override fun libSubagentFieldCustom(key: String) = key
    override val libVersion = "Версия"
    override val libVersionHint = { v: Int -> "v$v · увеличивается автоматически при сохранении" }
    override val libInstruction = "Инструкция (markdown)"

    override val compareAction = "Сравнить"
    override val compareTitle = "Сравнить черновики"
    override val compareLeft = "Левый черновик"
    override val compareRight = "Правый черновик"
    override val compareChoose = "Сравнить с"
    override val compareUndo = "Отменить"
    override val compareCopyToLeft = "Скопировать это изменение в левый черновик"
    override val compareCopyToRight = "Скопировать это изменение в правый черновик"
    override val compareSaveAsRule = "Сохранить как правило…"
    override val compareSaveAsRuleTitle = "Сохранить отличие как правило"
    override val compareSaveAsRuleBody = "Текст"
    override val compareResetLeft = "Сбросить левую"
    override val compareResetRight = "Сбросить правую"
    override val compareSaveLeft = "Сохранить левую"
    override val compareSaveRight = "Сохранить правую"
    override val compareSaveBoth = "Сохранить обе"
    override val compareNoCandidates = "Нет других объектов этого типа для сравнения."
    override val compareModeDiff = "Различия"
    override val compareModeEdit = "Правка"
    override val comparePrevDifference = "Предыдущее различие"
    override val compareNextDifference = "Следующее различие"
    override val compareHunkPosition = { index: Int, total: Int -> "$index из $total" }
    override val compareDifferences = { count: Int -> "различий: $count" }
    override val compareIdentical = "Тексты совпадают."
    override val compareFileOpen = "Сравнить"
    override val compareFileTitle = { name: String -> "Сравнение: $name" }
    override val compareFileApply = "Перенести в черновик"
    override val compareFileNoCandidates = "Нет другого файла инструкций для сравнения."

    override val translateShow = "Перевод"
    override val translateHide = "Скрыть перевод"
    override val translateSwap = "Направление"
    override val translateRefresh = "Обновить"
    override val translateReplace = "Заменить оригинал"
    override val translateWorking = "Перевожу…"
    override val translateOriginalTitle = "Оригинал"
    override val translateColumnTitle = { from: String, to: String -> "Перевод $from → $to" }
    override val translateStale = "Текст изменился после перевода."
    override val translateReadOnly = "Режим чтения: скройте перевод, чтобы править текст."
    override val translatePackMissing = "Языковой пакет ещё не скачан."
    override val translateOpenSettings = "Открыть Настройки системы"
    override val translateUnsupported = "macOS не поддерживает эту пару языков."
    override val translateUnavailable = "Перевод доступен только в macOS."
    override val translateFailed = "Не удалось перевести."

    override val libNewRuleTitle = "Новое правило"
    override val libNewGroupTitle = "Новая группа"
    override val libNewProfileTitle = "Новый профиль"
    override val libDeleteRuleConfirm = { name: String -> "Удалить правило «$name»? Оно также будет убрано из групп." }
    override val libDeleteGroupConfirm = { name: String -> "Удалить группу «$name»? Правила будут сохранены." }
    override val libDeleteProfileConfirm = { name: String -> "Удалить профиль «$name»? Объекты библиотеки будут сохранены." }
    override val libExportedTitle = "Экспортировано"
    override val libExportedText = { zip: String -> "Библиотека записана в $zip" }
    override val libExportFailedTitle = "Ошибка экспорта"
    override val libImportFailedTitle = "Ошибка импорта"
    override val libImportFailedText = { zip: String, msg: String -> "Не удалось прочитать $zip: $msg" }
    override val libNothingToImportTitle = "Нечего импортировать"
    override val libNothingToImportText = "Архив совпадает с библиотекой."
    override val libImportTitle = "Импорт библиотеки"
    override val libNewMcpTitle = "Новый MCP-сервер"
    override val libSearchObjects = { count: Int ->
        "Поиск по $count ${plural(count, "объекту", "объектам", "объектам")}…"
    }
    override val libSearchClear = "Сбросить поиск"
    override val libByGroup = "По группам"
    override val libByType = "По типам"
    override val libByArea = "По областям"
    override val libProjectScoped = "Проектные"
    override val libSource = "Источник · skills / субагенты"
    override val libLocalSkills = "Локальные"
    override val libImportedSkills = "Импортированы из Git"
    override val libUpdateAvailable = "Есть обновление"
    override val libNoMatches = "Нет объектов, подходящих под выбранные фильтры."
    override val libMembersCount = { count: Int -> "Состав · $count" }
    override val libEditFocus = "Редактировать"
    override val placeEditInLibrary = "Редактировать в библиотеке"
    override val libNewObject = "+ Создать"
    override val libUngrouped = "Без группы"
    override val libBody = "Содержимое"
    override val libInstalledIn = { count: Int -> if (count == 0) "Где установлено" else "Где установлено · $count" }
    override val libNotInstalled = "Нигде не установлено."
    override val libHistory = "История версий"
    override val libHistoryEmpty = "Коммитов пока нет."
    override val libDiffTitle = { name: String -> "Изменения: $name" }
    override val libDiffOpen = "Показать изменения"
    override val libDiffEmpty = "В этой ревизии объект не менялся."
    override val libBodyOpenEditor = "Открыть в редакторе"
    override val libGitSourceTitle = "Импортирован из Git"
    override val libGitImmutable = "неизменяемый — форкните для правки"
    override val libForkToEdit = "Форкнуть для правки"
    override val libForkBaseTitle = "Основа форка"
    override val libForkUpstreamAhead = "Upstream ушёл вперёд"
    override val libCompareUpstream = "Сравнить с upstream"
    override val libMoreCount = { count: Int -> "+ ещё $count" }
    override val libUsage = "Использование"
    override val libUsageInstalled = "Где-то установлены"
    override val libUsageUnused = "Не используются"
    override val libInPlaces = { count: Int -> "в $count ${plural(count, "проекте", "проектах", "проектах")}" }
    override val libUnusedMark = "не используется"
    override val libStatusSynced = "синхронизировано"
    override val libStatusUpdate = "обновление"
    override val libStatusModified = "изменено"
    override val libPinnedTo = { project: String -> "закреплено за $project" }
    override val libAutoMaintained = "обновляется автоматически"
    override val libSaveImpact = { count: Int, version: String ->
        "Установлено в $count ${plural(count, "проекте", "проектах", "проектах")}. " +
            "Сохранение поднимет версию до $version и пометит все установки как требующие обновления."
    }
    override val libSaveImpactUnused = { version: String ->
        "Нигде не установлено — сохранение только поднимет версию библиотеки до $version."
    }
    override val libSelectedCount = { count: Int -> "Выбрано: $count" }
    override val libBulkInstall = "Установить в…"
    override val libBulkAddToGroup = "Добавить в группу…"
    override val libBulkExport = "Экспорт"
    override val libBulkCombineRules = "Объединить в одно"
    override val libCombinedRuleName = "Объединённое правило"
    override val libBulkClear = "Снять выбор"
    override val libInstallConfirm = "Установить"
    override val libAddConfirm = "Добавить"
    override val libInstallIntoTitle = "Установить в проекты"
    override val libInstallIntoText = { count: Int ->
        "$count ${plural(count, "объект", "объекта", "объектов")} будут записаны в отмеченные проекты."
    }
    override val libInstallNoPlaces = "Проекты ещё не настроены."
    override val libInstallReportTitle = "Установка завершена"
    override val libInstallWritten = { count: Int -> "Записано: $count" }
    override val libInstallSkippedScope = { count: Int -> "Пропущено — принадлежит другому проекту: $count" }
    override val libInstallSkippedUnsupported = { count: Int -> "Пропущено — в проекте нет подходящего агента: $count" }
    override val libInstallSkippedModified = { count: Int -> "Пропущено — там правили вручную: $count" }
    override val libInstallFailedCount = { count: Int -> "Не удалось записать: $count" }
    override val libUninstallHint = "Убрать из этого проекта"
    override val libUninstallTitle = "Убрать из проекта"
    override val libUninstallText = { name: String, place: String ->
        "Убрать «$name» из «$place»? В библиотеке объект останется. Копию, изменённую вручную, придётся убирать в самом проекте."
    }
    override val libUninstallConfirm = "Убрать"
    override val libUninstallReportTitle = "Удаление завершено"
    override val placeGroupUnmarked = { names: String ->
        "Оставлены, установлены без отметки этой группы: $names. Их ставили отдельно или этой группой до того, как Ruleblend начал её записывать."
    }
    override val placeGroupRemoveUnmarked = "Убрать и их"
    override val placeGroupKeepUnmarked = "Оставить"
    override val libUninstallRemoved = { count: Int -> "Убрано: $count" }
    override val libAddToGroupTitle = "Добавить в группу"
    override val libAddToGroupText = { count: Int ->
        "$count ${plural(count, "объект", "объекта", "объектов")} войдут в выбранную группу. Группы не вкладываются."
    }
    override val libNoGroups = "Групп пока нет."
    override val libAddedToGroupTitle = "Добавлено в группу"
    override val libAddedToGroupText = { count: Int, group: String -> "$count добавлено в «$group»." }
    override val libAddedNothingText = "Добавлять нечего: всё отмеченное уже в группе."
    override val mcpTransport = "Транспорт"
    override val mcpTransportStdio = "stdio"
    override val mcpTransportHttp = "HTTP"
    override val mcpCommand = "Команда"
    override val mcpArgs = "Аргументы"
    override val mcpEnv = "Переменные окружения"
    override val mcpUrl = "URL"
    override val mcpHeaders = "Заголовки"
    override val mcpAddRow = "+ добавить"
    override val mcpServerNameHint = { id: String -> "Имя сервера в конфигах агентов: $id" }
    override val mcpBrokenConfig = "Конфиг блока не удалось разобрать. Сбросьте его или поправьте файл в библиотеке."
    override val mcpResetConfig = "Сбросить конфиг"
    override val mcpErrCommandRequired = "Команда обязательна"
    override val mcpErrUrlInvalid = "URL должен начинаться с http:// или https://"
    override val mcpErrBlankKey = "Пустой ключ"
    override val mcpErrDuplicateKey = "Ключи повторяются"
    override val mcpErrReservedName = { name: String -> "«$name» зарезервировано за самим Ruleblend" }
    override val changeNew = "новый"
    override val changeUpdate = "обновление"
    override val changeUnchanged = "без изменений"
    override val changeConflict = "конфликт — перезапишет локальные правки"

    override val placeFilter = "Фильтр проектов…"
    override val placeFilterClear = "Сбросить фильтр"
    override val placePinned = "Закреплённые"
    override val placeRecent = "Недавние"
    override val placeUngrouped = "Проекты"
    override val placePin = "Закрепить"
    override val placeUnpin = "Открепить"
    override val placeNewSet = "Новый набор…"
    override val placeRenameSet = "Переименовать набор…"
    override val placeDeleteSet = "Удалить набор"
    override val placeMoveToSet = { name: String -> "Перенести в «$name»" }
    override val placeRemoveFromSet = "Убрать из набора"

    override val placeModeNone = "без управления"
    override val placeModeLegacy = "старый формат"
    override val placeModePartial = "частично"
    override val placeModeOwned = "целиком"
    override val placePointerTo = { name: String -> "→ @$name" }
    override val placeOwnedNotice = "Файл целиком под управлением Ruleblend: рукописного текста в нём нет."
    override val placePointerNotice = { name: String -> "Файл-указатель: агент читает $name через эту строку импорта. Блоков здесь нет." }
    override val placeNoBlocks = "Блоки сюда ещё не установлены."
    override val placeNoServers = "MCP-серверы сюда ещё не установлены."
    override val placeNoSkills = "Skills сюда ещё не установлены."
    override val placeNoSubagents = "Субагенты сюда ещё не установлены."
    override val placeNoOurSkills = { count: Int ->
        "Наши skills сюда ещё не установлены; чужих — $count."
    }
    override val placeNoOurSubagents = { count: Int ->
        "Наши субагенты сюда ещё не установлены; чужих — $count."
    }
    override val placeNoOurServers = { count: Int ->
        "Наши MCP-серверы сюда ещё не установлены; чужих — $count."
    }
    override val placeObjectCount = { ours: Int, foreign: Int -> "$ours/$foreign" }
    override val placeObjectCountHint = { ours: Int, foreign: Int ->
        "$ours ${plural(ours, "наш", "наших", "наших")} · $foreign ${plural(foreign, "чужой", "чужих", "чужих")}"
    }
    override val placeForeignEntries = "Чужие записи"
    override val placeBuiltInEntries = "Встроено в Ruleblend"
    override val placeBuiltInManaged = "Встроенный"
    override val placeBuiltInReadOnly = "Здесь только просмотр. Обновление и удаление — в настройках."
    override val placeHideEntry = "Скрыть"
    override val placeUnhideEntry = "Вернуть"
    override val placeTakeOwnership = "Привязать к библиотеке"
    override val placeRestoreOrphan = "Вернуть в библиотеку"
    override val placeRemoveOrphan = "Снять"
    override val placeRemoveOrphanConfirm = { name: String -> "Снять осиротевшую запись «$name»?" }
    override val placeRemoveForeignConfirm = { name: String, path: String -> "Удалить «$name» из $path?" }
    override val placeSkillNameTaken = { id: String -> "Skill «$id» в библиотеке уже есть" }
    override val placeSkillNameHint = "Строчные буквы, цифры и дефисы; имя должно быть свободным."
    override val placeSubagentNameTaken = { id: String -> "Субагент «$id» в библиотеке уже есть" }
    override val placeSubagentNameHint = "Идентификатор должен быть свободным."
    override val placeMcpNameTaken = { id: String -> "MCP-сервер «$id» в библиотеке уже есть" }
    override val placeMcpNameHint = "Идентификатор должен быть свободным."
    override val placeShowHiddenEntries = { count: Int -> "Показать скрытые ($count)" }
    override val placeHideHiddenEntries = { count: Int -> "Снова скрыть ($count)" }
    override val placeFileMissing = "Файла пока нет."
    override val placeProjectMissing =
        "Папки проекта больше нет. Сюда ничего не записывается, пока она не вернётся или проект не убран из списка."
    override val placeTabWrittenTo = { files: String -> "Пишется в: $files" }
    override val placeBackup = "Сделать копию"
    override val placeBackupUpdate = "Обновить копию"
    override val placeBackupHint = "Сохранить резервную копию всего файла в его текущем состоянии."
    override val placeBackupUpdateHint = "Заменить предыдущую резервную копию текущим содержимым всего файла."
    override val placeRestoreHint = "Заменить весь файл сохранённой резервной копией. Изменения после создания копии будут потеряны."
    override val placeDisownHint = "Прекратить управление этим файлом в Ruleblend. Текст останется, но Ruleblend больше не будет его обновлять."
    override val placeShowNoticeHint = "Добавить в файл предупреждение: ручные изменения управляемого текста могут быть перезаписаны Ruleblend."
    override val placeHideNoticeHint = "Убрать из файла предупреждение о ручных изменениях. Ruleblend продолжит управлять текстом."
    override val placeRestore = "Восстановить"
    override val placeRestoreConfirm = { file: String ->
        "Заменить $file сохранённой копией? Изменения после создания копии будут потеряны."
    }
    override val placeMigrateLegacy = "Перенести старые маркеры"
    override val placeDisown = "Перестать управлять"
    override val placeDisownConfirm = { file: String ->
        "Перестать управлять $file? Текст останется в файле, но Ruleblend больше не будет его обновлять."
    }
    override val placeUnlinkEntry = "Отвязать от библиотеки"
    override val placeShowNotice = "Показать предупреждение"
    override val placeHideNotice = "Скрыть предупреждение"
    override val placeAssistants = "Ассистенты проекта"
    override val placeAssistantsConnected = { on: Int, total: Int -> "$on из $total подключено" }
    override val placeAssistantsConfigure = "Настроить"
    override val placeAssistantsCollapse = "Свернуть"
    override val placeAssistantsSearch = "Поиск ассистента"
    override val placeAssistantsSearchClear = "Сбросить поиск"
    override val placeAssistantsNoMatch = "Ни один ассистент не подходит."
    override val placeLaunch = "Запустить"
    override val placeProfiles = "Профили"
    override val placeAddProfile = "Добавить профиль"
    override val placeDetachProfile = { name: String -> "Отвязать «$name»: его объекты снимутся, база останется." }
    override val placeProfileToggleHint = { name: String -> "Включить или выключить «$name» для этого проекта." }
    override val placeProfileReportTitle = "Профиль переключён"
    override val placeProfileReportApplied = { written: Int, removed: Int -> "Установлено: $written. Снято: $removed." }
    override val placeProfileReportMissing = { ids: String -> "Привязаны, но в библиотеке их больше нет: $ids." }
    override val placeAttachProfile = "Привязать профиль"
    override val placeAttachMerge = "Слить"
    override val placeAttachReplace = "Заменить"
    override val placeAttachNoProfiles = "Все профили библиотеки уже привязаны к этому проекту."
    override val placeReplaceBaseHint = { count: Int -> "«Заменить» снимет $count базовых объект(а/ов). Ручные правки останутся на месте." }
    override val placeReplaceBaseEmpty = "Базовых объектов для снятия нет."
    override val placeLaunchNew = { agent: String -> "Новая сессия в $agent" }
    override val placeLaunchResume = { agent: String -> "Продолжить последнюю сессию $agent" }
    override val placeLaunchHint = { agent: String -> "Открыть $agent в папке этого проекта." }
    override val placeLaunchHintResumable = { agent: String ->
        "Открыть $agent в папке этого проекта. Правый клик — продолжить последнюю сессию."
    }
    override val placeTabNothingIn = { files: String -> "Пусто в: $files" }
    override val placeNotInLibrary = "нет в библиотеке"
    override val placeInstall = "Установить"
    override val placeRemove = "Удалить"
    override val placeKeep = "Оставить"
    override val placeDriftNotice =
        "Изменено здесь. Верните версию библиотеки, сохраните правку новой версией или оставьте как есть."
    override val placeDriftKept = "правка оставлена"
    override val placeDriftApart =
        "Агенты здесь хранят разные правки. Сохраните ту копию, которая станет новой версией библиотеки, — остальные будут перезаписаны."
    override val placeMoveTop = "Переместить в начало списка"
    override val placeMoveBottom = "Переместить в конец списка"

    override val paletteSaveAsGroup = "Создать группу из этого места"
    override val paletteSaveAsGroupTitle = "Новая группа из этого места"
    override val paletteSaveAsGroupHint = { count: Int ->
        "Сохранит $count ${plural(count, "объект", "объекта", "объектов")}, установленных здесь, группой библиотеки. " +
            "Существующее имя обновит ту группу."
    }
    override val paletteRecommended = "Рекомендуется"
    override val paletteInSet = { installed: Int, total: Int, set: String -> "в $installed из $total · $set" }
    override val paletteLibrary = "Библиотека"
    override val paletteSearch = { count: Int ->
        "Поиск по $count ${plural(count, "объекту", "объектам", "объектам")}…"
    }
    override val paletteSearchClear = "Сбросить поиск"
    override val paletteNoMatches = "Ничего не найдено."
    override val paletteShowMore = { left: Int -> "Показать ещё ($left)" }
    override val paletteExpandAll = "Развернуть все"
    override val paletteCollapseAll = "Свернуть все"
    override val paletteFound = "Найдено в проекте"
    override val paletteFoundAgent = "Найдено в агенте"
    override val paletteFoundLines = { lines: Int ->
        "$lines ${plural(lines, "строка", "строки", "строк")} · рукописное"
    }
    override val paletteUnsupported = "здесь не поддерживается"
    override val placeSaveToLibrary = "Сохранить в библиотеку как…"
    override val placeHandText = "Написано вручную"

    override val intAgents = "AI-ассистенты"
    override val intProjects = "Проекты"
    override val intAddProject = "+ Добавить проект"
    override val intEmptySelection = "Добавьте проект или установите агента"
    override val intSkills = "Skills"
    override val intAdoptTitle = { file: String -> "Сохранить $file в библиотеку" }
    override val intAdoptReplaceLabel = "Заменить исходный текст управляемым блоком"
    override val intAdoptOrderBlocked = { names: String ->
        "Замена переставила бы текст: $names стоит между выбранными секциями и оказался бы за пределами управляемого блока. Отметьте и его или снимите замену."
    }
    override val intAdoptOrderAroundRun =
        "Этот текст стоит по обе стороны от управляемого блока, который уже есть в файле, и замена переставила бы его часть. Сохраните без замены."
    override val intAdoptSizeWarning = { lines: Int, kb: Int ->
        "Большое правило: $lines строк, $kb КБ. Оно целиком скопируется в каждый целевой файл, куда будет установлено."
    }
    override val fieldGeneralDescription = "Общее описание"
    override val intAdoptDescriptionDefault = { target: String, file: String ->
        if (target.isBlank()) "Сохранено из $file" else "Сохранено из $target ($file)"
    }
    override val intAdoptSections = { count: Int -> "Секции ($count)" }
    override val intAdoptSectionNew = "новое правило"
    override val intAdoptSectionDuplicate = "уже в библиотеке"
    override val intAdoptSectionSimilar = { name: String -> "отличается от «$name» — видимо, обновление" }
    override val intAdoptActionSkip = "Пропустить"
    override val intAdoptActionOverwrite = "Перезаписать"
    override val intAdoptActionCreateNew = "Создать новый"
    override val intAdoptSelectAll = "Все"
    override val intAdoptSelectNone = "Ничего"
    override val intAdoptScanSummary = { imports: Int, broken: Int ->
        val parts = buildList {
            if (imports > 0) add("$imports @import${plural(imports, "", "а", "ов")}")
            if (broken > 0) add("$broken ${plural(broken, "битая ссылка", "битые ссылки", "битых ссылок")}")
        }
        "Внимание: ${parts.joinToString(", ")}. Наведите на ⚠ для деталей."
    }
    override val intAdoptScanImportsHeader = { n: Int -> "Найдено $n @import${plural(n, "", "а", "ов")}" }
    override val intAdoptScanImportsHint =
        "@imports раскрывает только Claude Code. Codex, Pi, Kimi Code и ZCode оставляют их как обычный текст."
    override val intAdoptScanBrokenHeader = { n: Int ->
        "$n ${plural(n, "нерезолвящаяся ссылка", "нерезолвящиеся ссылки", "нерезолвящихся ссылок")}"
    }
    override val intAdoptScanBrokenHint =
        "Эти пути не существуют относительно исходного файла — они не разрешатся и при установке блока в другой проект."
    override val intTakeLibrary = "Восстановить из библиотеки"
    override val intAcceptLocal = { v: String? -> if (v == null) "Сохранить новую версию" else "Сохранить как v$v" }
    override val ctxRemoveProject = "Убрать проект"
    override val intRemoveProjectConfirm = { name: String -> "Убрать проект «$name» из Ruleblend?" }
    override val intEditFile = "Редактировать файл"
    override val intFileEditorTitle = { name: String -> "Редактировать $name" }
    override val libNewRuleInGroupTitle = "Новое правило в этой группе"
    override val intMcpServers = "MCP-серверы"

    override val splitAction = "Разделить"
    override val splitDialogTitleLibrary = { name: String -> "Разделить «$name» на части" }
    override val splitDialogTitleAdopt = "Разделить секцию"
    override val splitPartsCount = { n: Int ->
        "$n ${plural(n, "часть", "части", "частей")}"
    }
    override val splitReset = "Сбросить"
    override val splitConfirm = { n: Int -> "Разделить на $n ${plural(n, "часть", "части", "частей")}" }
    override val splitEmptyBody = "пустое тело"
    override val splitIdConflict = { id: String -> "id уже существует: $id" }
    override val splitIdDuplicate = "имя повторяется в этом разбиении"
    override val splitAddCut = "Разрезать здесь"
    override val splitRemoveCut = "Убрать рез"
    override val splitTooShort = "Блок в одну строку — делить нечего."
    override val splitNoCuts = "Кликните между строк слева, чтобы поставить рез."

    override val setOpenConfigFolder = "Открыть папку конфига"
    override val setFooter =
        "Настройки живут на этой машине и не уходят с экспортом или Git-синхронизацией. Сохраняются сразу — кнопки «Применить» нет."
    override val setSectionGeneral = "Общие"
    override val setSectionLibrary = "Библиотека"
    override val setSectionAgents = "AI-ассистенты"
    override val setSectionShortcuts = "Сочетания клавиш"
    override val setSectionAbout = "О программе"
    override val setSectionFolding = "Развёрнутые области"
    override val setFoldingSum = "как списки открываются при входе"
    override val setGeneralSum = "применяется сразу"
    override val setTheme = "Оформление"
    override val setThemeHint = "«Как в системе» следует за ОС; выбор сохраняется."
    override val themeSystem = "Как в системе"
    override val themeLight = "Светлое"
    override val themeDark = "Тёмное"
    override val setLanguage = "Язык"
    override val setLanguageHint = "Только интерфейс. Содержимое библиотеки не переводится."
    override val setGroupsExpansion = "Группы при открытии"
    override val setGroupsExpansionHint = "Применяется при входе на экран с группами."
    override val setGroupsExpanded = "Развёрнуты"
    override val setGroupsCollapsed = "Свёрнуты"
    override val setPlaceLibraryExpansion = "Проекты: палитра библиотеки"
    override val setPlaceLibraryExpansionHint = "Разделы по типам в правой колонке экрана «Проекты»."
    override val setPlaceRulesExpansion = "Проекты: правила"
    override val setPlaceRulesExpansionHint = "Тела правил на вкладке «Правила»."
    override val setPlaceSkillsExpansion = "Проекты: навыки"
    override val setPlaceSkillsExpansionHint = "Элементы на вкладке «Навыки»."
    override val setPlaceSubagentsExpansion = "Проекты: субагенты"
    override val setPlaceSubagentsExpansionHint = "Элементы на вкладке «Субагенты»."
    override val setPlaceMcpExpansion = "Проекты: MCP"
    override val setPlaceMcpExpansionHint = "Определения серверов на вкладке MCP."
    override val setFileEditor = "Редактор файлов"
    override val setFileEditorHint = "Где «Редактировать файл» в разделе «Проекты» открывает управляемый файл."
    override val setTranslation = "Перевод"
    override val setTranslationHint =
        "Нужен ли перевод в приложении, а также пара языков и качество."
    override val setTranslationEnabled = "Предлагать перевод"
    override val translateQualityFast = "Быстро"
    override val translateQualityHigh = "Точно"
    override val translatePackReady = "Языковой пакет установлен."
    override val fileEditorInApp = "В Ruleblend"
    override val fileEditorExternal = "Внешний"
    override val fileEditorApplication = "Приложение"
    override val fileEditorSystemDefault = "По умолчанию в системе"
    override val fileEditorChoose = "Выбрать…"
    override val fileEditorReset = "Сбросить"
    override val setWorkspace = "Рабочее пространство"
    override val setWorkspaceHint = "Состояние раскладки на этой машине."
    override val setResetColumns = "Сбросить ширины колонок"
    override val setResetColumnsDone = "Раскладка сброшена"
    override val setClearRecent = "Очистить недавние проекты"
    override val setClearRecentDone = "Очищено"
    override val setWorkspaceNote = "Закреплённые проекты и их наборы сохраняются."
    override val setLibraryPath = "Расположение"
    override val setLibraryPathHint = "Git-репозиторий. Всё, что вы экспортируете или синхронизируете, лежит здесь."
    override val setLibraryPathChange = "Изменить…"
    override val setRevealInFinder = "Показать в Finder"
    override val setRevealInFiles = "Показать в файловом менеджере"
    override val setReveal = "Показать"
    override val setStatsRules = { n: Int -> "$n ${plural(n, "правило", "правила", "правил")}" }
    override val setStatsSkills = { n: Int -> "$n skills" }
    override val setStatsMcp = { n: Int -> "$n MCP" }
    override val setStatsGroups = { n: Int -> "$n ${plural(n, "группа", "группы", "групп")}" }
    override val setStatsCommits = { n: Int -> "$n ${plural(n, "коммит", "коммита", "коммитов")}" }
    override val setStatsLastChange = { ago: String -> "последнее изменение $ago" }
    override val agoJustNow = "только что"
    override val agoMinutes = { n: Long -> "$n мин назад" }
    override val agoHours = { n: Long -> "$n ${plural(n.toInt(), "час", "часа", "часов")} назад" }
    override val agoDays = { n: Long -> "$n ${plural(n.toInt(), "день", "дня", "дней")} назад" }
    override val setLibraryPathRestartHint = "Вступит в силу после перезапуска."
    override val setUndo = "Отменить"
    override val setSourceCheck = "Обновления источников"
    override val setSourceCheckHint = "Когда проверять обновления Git-источников skills и субагентов."
    override val sourceCheckManual = "Вручную"
    override val sourceCheckOnLaunch = "При запуске"
    override val setSources = "Источники"
    override val setSourcesHint = "Репозитории skills и субагентов, импортированных в библиотеку."
    override val setSourcesEmpty = "Нет импортированных Git-источников"
    override val setSourceSkills = { n: Int -> "$n ${plural(n, "скилл", "скилла", "скиллов")}" }
    override val setSourceHead = { head: String -> "HEAD · $head" }
    override val setSourceReopenImport = "Переоткрыть импорт"
    override val setNameFormat = "Формат названия"
    override val setLineEnding = "Окончания строк"
    override val setLineEndingHint = "LF рекомендуется на всех платформах. Применяется к определениям библиотеки и управляемым правилам; существующие определения преобразуются. Файлы импортированных скиллов сохраняются без изменений."
    override val setNameFormatHint = "Применяется при каждом сохранении правила или группы. Существующие названия не переписываются."
    override val nameFormatKebab = "kebab-case"
    override val nameFormatCamel = "camelCase"
    override val nameFormatSnake = "snake_case"
    override val nameFormatFree = "вольный"
    override val setRemoteGit = "Внешний Git-бэкап"
    override val remoteGitHint =
        "Двусторонняя синхронизация библиотеки. HTTPS использует Git credential helper (или ~/.netrc)."
    override val remoteGitUrl = "URL репозитория"
    override val remoteGitBranch = "Ветка"
    override val remoteGitAutomatic = "Синхронизировать после каждого локального коммита"
    override val remoteGitSaveAndSync = "Сохранить и синхронизировать"
    override val remoteGitCancel = "Отмена"
    override val remoteGitSyncNow = "Синхронизировать"
    override val remoteGitEdit = "Изменить"
    override val remoteGitRemove = "Удалить"
    override val remoteGitSyncing = "Синхронизация…"
    override val remoteGitPushed = "Отправлено"
    override val remoteGitUpToDate = "Актуально"
    override val remoteGitFailed = "Ошибка синхронизации"
    override val remoteGitNotSynced = "Ещё не синхронизировано"
    override val remoteGitPushedAgo = { ago: String -> "push $ago" }
    override val remoteGitBehind = { commits: Int -> "входящих: $commits" }
    override val remoteGitMerged = "объединено"
    override val remoteGitConflicts = { count: Int -> "конфликтов: $count" }
    override val remoteGitRestored = { count: Int -> "восстановлено: $count" }
    override val remoteGitAutoShort = "автоматически"
    override val remoteGitManualShort = "вручную"
    override val remoteGitLocalKept = "Локальная история сохраняется даже при ошибке push."
    override val remoteGitUnrelatedTitle = "Несвязанные истории библиотек"
    override val remoteGitUnrelatedText = "Этот remote не основан на локальной библиотеке. Объедините его по обычной политике конфликтов импорта либо сначала экспортируйте локальную библиотеку в ZIP и замените её удалённой копией."
    override val remoteGitMergeAsImport = "Объединить как импорт"
    override val remoteGitReplaceAfterExport = "Экспортировать ZIP и заменить"
    override val setLibraryData = "Импорт и экспорт"
    override val setLibraryDataHint = "Вся библиотека одним архивом. Выборочный экспорт — в разделе «Библиотека»."
    override val setImportNote = "Импорт сливает по id и показывает план изменений до записи."
    override val setAgentSummary = { visible: Int, total: Int, connected: Int, mcp: Int ->
        "$visible из $total видимы · $connected из $mcp подключены к Ruleblend MCP"
    }
    override val setColAgent = "AI-ассистент"
    override val setColCanManage = "Управляет"
    override val setColVisible = "Видимость"
    override val setColMcp = "Ruleblend MCP"
    override val setColCli = "CLI"
    override val setCliArgumentsHint = "Дополнительные аргументы"
    override val setTerminal = "Терминал"
    override val setTerminalHint = "В каком приложении открывается CLI агента из шапки проекта."
    override val setAgentNotDetected = "не найден"
    override val setAgentRulesCapability = "Правила"
    override val setAgentMcpCapability = "MCP"
    override val setAgentSkillsCapability = "Skills"
    override val setAgentSubagentsCapability = "Субагенты"
    override val setCapabilityGlobalOnly = "только global"
    override val setCapabilityProjectOnly = "только project"
    override val setCapabilityUnavailable = "не поддерживается"
    override val setAgentVisible = "Виден"
    override val setAgentHidden = "Скрыт"
    override val setConnManual = "вручную — см. ниже"
    override val connectAction = "Подключить"
    override val connectActionUpdate = "Обновить"
    override val disconnectAction = "Отключить"
    override val connectStatusConnected = "Подключён"
    override val connectStatusNotConnected = "Не подключён"
    override val connectStatusStale = "Нужно переподключить — приложение перемещено"
    override val connectStatusSkillOutdated = "Навык устарел"
    override val connectStatusMcpOutdated = "Запись MCP устарела"
    override fun setSkillVersion(installed: Int, current: Int) =
        if (installed == current) "навык v$installed" else "навык v$installed → v$current"
    override fun setEntryVersion(installed: Int, current: Int) =
        if (installed == current) "запись v$installed" else "запись v$installed → v$current"
    override val setUpdateAll = "Обновить везде"
    override fun setUpdateAllHint(count: Int) = "Устаревшая копия у ассистентов: $count"
    override val homeIntegrationTitle = "Ассистентам нужно обновить Ruleblend"
    override val homeIntegrationUpdateAll = "Обновить везде"
    override val homeIntegrationUpdate = "Обновить"
    override val homeIntegrationUpdating = "Обновляем…"
    override val homeIntegrationForeign = "Навык «ruleblend» написан не нами — не трогаем"
    override val libBuiltIn = "встроенный"
    override val libBuiltInManaged =
        "Поставляется с приложением. Подключение, обновление и удаление — в настройках, по каждому ассистенту; здесь его нельзя изменить или удалить."
    override val connectStatusSkillForeign = "Навык с именем «ruleblend» уже существует — удалите его"
    override val connectStatusMcpForeign = "MCP-сервер с именем «ruleblend» уже существует — удалите его"
    override val connectManualHint = "Другие агенты — добавьте stdio MCP-сервер с именем «ruleblend», запускающий:"
    override val setCopy = "Копировать"
    override val setCopied = "Скопировано"
    override val setAgentsFoot = "Скрытые агенты исчезают из разделов «Главная», «Проекты» и «Покрытие»; их файлы не трогаются."
    override val setShortcutsSum = "⌘ на macOS, Ctrl на остальных — принимаются оба"
    override val setKeyPalette = "Палитра команд"
    override val setKeyClose = "Закрыть палитру / диалог"
    override val setKeyScoped = "Поиск с префиксом в ${commandShortcut("K")}"
    override val setShortcutsFoot = "Сочетания ничего не записывают: они переключают экраны и открывают палитру."
    override val setAboutBuild = { build: String, os: String, jvm: String -> "сборка $build · $os · JVM $jvm" }
    override val setCopyDiagnostics = "Скопировать диагностику"
    override val setDiagnosticsNote = "Версия, ОС, пути к конфигу и библиотеке, найденные агенты."
    override val setFiles = "Файлы"
    override val setFilesHint = "Где эта машина хранит состояние."
    override val setFileConfig = "Конфиг"
    override val setFileLibrary = "Библиотека"
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }

fun stringsFor(language: Language): Strings = when (language) {
    Language.EN -> EnStrings
    Language.RU -> RuStrings
}
