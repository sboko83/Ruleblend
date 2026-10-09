package dev.ruleblend.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.split.SplitResult
import dev.ruleblend.app.util.Failure
import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.integration.SubagentAgentFields
import dev.ruleblend.core.config.ColumnWidths
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.ArchiveSelection
import dev.ruleblend.core.exchange.ImportPlan
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.headingLine
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.validSkillName
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.GitSkillImportPlan
import dev.ruleblend.core.storage.GitSkillImporter
import dev.ruleblend.core.storage.GitRepositoryFetcher
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.LibraryRevision
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.LibraryPart
import dev.ruleblend.core.usecase.LibrarySnapshot
import dev.ruleblend.core.usecase.MutateLibrary
import dev.ruleblend.core.usecase.knownProjectScopes
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sidebar selection: a block type or a group. */
sealed interface Selection {
    data class Type(val type: BlockType) : Selection

    data class GroupRef(val id: String) : Selection

    data class ProfileRef(val id: String) : Selection

    data object Skills : Selection
}

/** UI state over [LibraryRepository]. Holds the editor draft and the current selection. */
class LibraryModel(
    private val repository: LibraryRepository,
    private val archive: LibraryArchive,
    private val configStore: ConfigStore,
    private val gitSkillImporter: GitRepositoryFetcher = GitSkillImporter(),
    private val usageSource: LibraryUsageSource = LibraryUsageSource.None,
    private val installer: LibraryPlaceInstaller = LibraryPlaceInstaller.None,
    /**
     * The skill and the MCP entry the app ships. Listed beside the library so the user can read what
     * an assistant was given; empty in tests that only care about the user's own objects.
     */
    private val builtIns: List<LibraryObject> = emptyList(),
    /**
     * Assistants a subagent can be tuned for, with the fields each of them understands. Empty in
     * tests and wherever no agent list is at hand: the editor then shows no per-assistant section.
     */
    val subagentAgents: List<SubagentAgentFields> = emptyList(),
) {
    /** Every write to the library goes through these two; this model only decides what to show after. */
    private val library = MutateLibrary(repository, configStore)

    private val imports = ImportLibrary(repository, archive, configStore, gitSkillImporter)


    var blocks by mutableStateOf(emptyList<Block>())
        private set

    var groups by mutableStateOf(emptyList<Group>())
        private set

    var profiles by mutableStateOf(emptyList<Profile>())
        private set

    var skills by mutableStateOf(emptyList<Skill>())
        private set

    /** Project keys available as rule-library filters. `null` is the global scope. */
    var projectScopes by mutableStateOf(emptyList<String>())
        private set

    private var ruleScopes: Map<String, String> = emptyMap()

    val catalog: LibraryCatalog
        get() = LibraryCatalog.build(blocks, groups, skills, ruleScopes, profiles, builtIns)

    var selectedCatalogKey by mutableStateOf<LibraryObjectKey?>(null)
        private set

    var selectedScope by mutableStateOf<String?>(null)
        private set

    var selection by mutableStateOf<Selection>(Selection.Type(BlockType.RULE))
        private set

    /** Editor draft of the selected block; `null` when no block is selected. */
    var draft by mutableStateOf<Block?>(null)
        private set

    /** Scope edited alongside [draft] and persisted by [saveDraft]. */
    var draftScope by mutableStateOf<String?>(null)
        private set

    /** Editor draft of the selected group; `null` unless an editable (non-"all") group is selected. */
    var groupDraft by mutableStateOf<Group?>(null)
        private set

    /** Editor draft of the selected profile; profiles are edited in the same Library pane as groups. */
    var profileDraft by mutableStateOf<Profile?>(null)
        private set

    var skillDraft by mutableStateOf<Skill?>(null)
        private set

    /** Message of the last failed action, until the screen shows and clears it. */
    var failure by mutableStateOf<Failure?>(null)
        private set

    /** True while a rule save (including an optional remote Git push) is running. */
    var savingDraft by mutableStateOf(false)
        private set

    val dirty: Boolean
        get() = draft?.let { d ->
            d != blocks.find { it.id == d.id } || (d.type == BlockType.RULE && draftScope != scopeOf(d.id))
        } ?: false

    val groupDirty: Boolean
        get() = groupDraft?.let { d -> d != groups.find { it.id == d.id } } ?: false

    val profileDirty: Boolean
        get() = profileDraft?.let { d -> d != profiles.find { it.id == d.id } } ?: false

    val skillDirty: Boolean
        get() = skillDraft?.let { d -> d != skills.find { it.id == d.id } } ?: false

    val skillDraftValid: Boolean
        get() = skillDraft?.let { validSkillName(it.name) && it.description.isNotBlank() && it.content.isNotBlank() } == true

    val selectedGroup: Group?
        get() = (selection as? Selection.GroupRef)?.let { ref -> groups.find { it.id == ref.id } }

    val selectedProfile: Profile?
        get() = (selection as? Selection.ProfileRef)?.let { ref -> profiles.find { it.id == ref.id } }

    /** Blocks shown in the list pane for the current [selection]. */
    val visibleBlocks: List<Block>
        get() = when (val s = selection) {
            is Selection.Type -> blocksInSelectedScope.filter { it.type == s.type }.favoritesFirst()
            // Prefer the in-flight draft: its blockIds reflect unsaved reorder/add/remove, so the list
            // matches what the up/down arrows operate on. Falls back to the saved group otherwise.
            is Selection.GroupRef -> (groupDraft ?: selectedGroup)
                ?.blockIds
                ?.mapNotNull { id -> blocksInSelectedScope.find { it.id == id } }
                .orEmpty()
                .favoritesFirst()
            is Selection.ProfileRef -> emptyList()
            Selection.Skills -> emptyList()
        }

    /** Every block belonging to [selectedScope]; MCP definitions are global-only. */
    val blocksInSelectedScope: List<Block>
        get() = blocks.filter { block ->
            if (block.type != BlockType.RULE) selectedScope == null else scopeOf(block.id) == selectedScope
        }

    private fun List<Block>.favoritesFirst(): List<Block> = sortedByDescending { it.favorite }

    /**
     * What one read of the library and the config yields; assembled off the UI thread. [stamp] is
     * [listsStamp] when the read began.
     */
    internal class Loaded(
        val blocks: List<Block>,
        val groups: List<Group>,
        val profiles: List<Profile>,
        val skills: List<Skill>,
        val config: AppConfig,
        val stamp: Long,
    )

    /**
     * Bumped whenever the library lists are assigned. A read that began before a save lands after it
     * holding the older text; assigning it would let the next edit open — and save — that older text.
     */
    private var listsStamp = 0L

    /**
     * Re-reads the library. The reading happens on [Dispatchers.IO] and lands as one snapshot: a
     * library of any size must not hold a frame, and state is only ever assigned on the caller's
     * thread, which is Compose's main.
     */
    suspend fun load() = land(read())

    internal suspend fun read(): Loaded {
        val stamp = listsStamp
        return withContext(Dispatchers.IO) {
            repository.init()
            val read = repository.listBlocks()
            repository.syncAllGroup()
            Loaded(read, repository.listGroups(), repository.listProfiles(), repository.listSkills(), configStore.load(), stamp)
        }
    }

    /** Assigns [loaded], unless a write has already landed a newer library since that read began. */
    internal fun land(loaded: Loaded) {
        val config = loaded.config
        columnWidths = config.columnWidths
        if (loaded.stamp == listsStamp) {
            listsStamp++
            blocks = loaded.blocks
            groups = loaded.groups
            profiles = loaded.profiles
            skills = loaded.skills
            ruleScopes = config.ruleScopes
            projectScopes = config.knownProjectScopes()
        }
        normalizeCatalogSelection()
        // Keep an in-flight edit of the selected group across a tab switch, the way the block draft
        // survives one — reloading is not a reason to silently drop what the user typed.
        groupDraft = groupDraft?.takeIf { it.id == editableGroup()?.id } ?: editableGroup()
        profileDraft = profileDraft?.takeIf { it.id == selectedProfile?.id } ?: selectedProfile
    }

    /** Where library objects are installed. Empty until [refreshUsage] finishes its first scan. */
    var usage by mutableStateOf(LibraryUsageIndex.EMPTY)
        private set

    /** Git history of [selectedCatalogKey], newest first; empty for a library without commits. */
    var history by mutableStateOf(emptyList<LibraryRevision>())
        private set

    /**
     * Re-reads every place. Read-only and off the UI thread; a failing scan leaves the previous
     * answer in place instead of interrupting library work with a dialog about someone else's file.
     */
    suspend fun refreshUsage() {
        val scanned = withContext(Dispatchers.IO) {
            runCatching { FileReadScope.reading { usageSource.scan(blocks, skills) } }.getOrNull()
        }
            ?: return
        usage = LibraryUsageIndex.build(scanned, catalog.objects.filter { it.key.kind == LibraryObjectKind.GROUP })
    }

    /** Revision whose diff is open, or `null` when the diff window is closed. */
    var openDiff by mutableStateOf<LibraryRevision?>(null)
        private set

    /** Unified diff of [openDiff]; empty while it loads or when git has nothing to show. */
    var openDiffText by mutableStateOf("")
        private set

    /**
     * Opens the diff of [revision] for the selected object. The text is read off the UI thread —
     * a diff is a git call, and the window is opened by a click that must not wait for it.
     */
    suspend fun showDiff(revision: LibraryRevision) {
        val key = selectedCatalogKey ?: return
        openDiff = revision
        openDiffText = ""
        val text = withContext(Dispatchers.IO) {
            runCatching {
                when (key.kind) {
                    LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP -> repository.blockDiff(key.id, revision.id)
                    LibraryObjectKind.SKILL -> repository.skillDiff(key.id, revision.id)
                    LibraryObjectKind.GROUP -> repository.groupDiff(key.id, revision.id)
                    LibraryObjectKind.PROFILE -> repository.profileDiff(key.id, revision.id)
                }
            }.getOrDefault("")
        }
        // The user may have closed the window, or moved on to another revision, while git worked.
        if (openDiff?.id == revision.id) openDiffText = text
    }

    fun closeDiff() {
        openDiff = null
        openDiffText = ""
    }

    /** Loads the history shown in the inspector; `null` clears it. */
    suspend fun loadHistory(key: LibraryObjectKey?) {
        closeDiff()
        history = if (key == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                when (key.kind) {
                    LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP -> repository.blockHistory(key.id)
                    LibraryObjectKind.SKILL -> repository.skillHistory(key.id)
                    LibraryObjectKind.GROUP -> repository.groupHistory(key.id)
                    LibraryObjectKind.PROFILE -> repository.profileHistory(key.id)
                }
            }
        }
    }

    fun selectCatalogObject(key: LibraryObjectKey) {
        selectedCatalogKey = key
        // A built-in has no library file behind it: there is nothing to open a draft over, and
        // asking for one by id would land on whatever object happens to be selected already.
        if (key.id == BUILTIN_SKILL_ID || key.id == BUILTIN_MCP_ID) return
        when (key.kind) {
            LibraryObjectKind.RULE -> {
                select(Selection.Type(BlockType.RULE))
                selectBlock(key.id)
            }
            LibraryObjectKind.MCP -> {
                select(Selection.Type(BlockType.MCP))
                selectBlock(key.id)
            }
            LibraryObjectKind.SUBAGENT -> {
                select(Selection.Type(BlockType.SUBAGENT))
                selectBlock(key.id)
            }
            LibraryObjectKind.SKILL -> {
                select(Selection.Skills)
                selectSkill(key.id)
            }
            LibraryObjectKind.GROUP -> select(Selection.GroupRef(key.id))
            LibraryObjectKind.PROFILE -> select(Selection.ProfileRef(key.id))
        }
    }

    /** Rows ticked for a bulk action. Independent of [selectedCatalogKey]: the inspector keeps its own object. */
    var marked by mutableStateOf(emptySet<LibraryObjectKey>())
        private set

    fun toggleMark(key: LibraryObjectKey) {
        marked = if (key in marked) marked - key else marked + key
    }

    fun clearMarks() {
        marked = emptySet()
    }

    val markedObjects: List<LibraryObject>
        get() = catalog.objects.filter { it.key in marked }

    /** Opens a new rule draft made from the marked rules without changing any source rule. */
    fun createCombinedRule(name: String): LibraryObjectKey? {
        if (marked.size < 2 || marked.any { it.kind != LibraryObjectKind.RULE }) return null
        val ids = marked.mapTo(mutableSetOf()) { it.id }
        val sources = blocks.filter { it.type == BlockType.RULE && it.id in ids }
        if (sources.size != marked.size) return null

        val sourceScopes = sources.map { scopeOf(it.id) }.distinct()
        val scope = sourceScopes.singleOrNull()
        val content = sources.joinToString("\n\n---\n\n") { source ->
            val heading = source.headingLine().ifBlank { "## ${source.name}" }
            if (source.content.isEmpty()) heading else "$heading\n\n${source.content}"
        }
        var createdKey: LibraryObjectKey? = null
        guard {
            val created = library.createBlock(name, BlockType.RULE, scope)
            apply(created.library)
            draft = created.value.copy(content = content)
            selection = Selection.Type(BlockType.RULE)
            selectedScope = scope
            draftScope = scope
            skillDraft = null
            selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.RULE, created.value.id)
            createdKey = selectedCatalogKey
            clearMarks()
        }
        return createdKey
    }

    /** Places a bulk install can write into, straight from the configured targets. */
    fun installPlaces(): List<LibraryPlaceRef> = installer.places()

    /**
     * Installs every marked object into [placeIds]. Writing runs off the UI thread; the usage index
     * is rebuilt afterwards, so the catalog answers "where installed" from disk and not from hope.
     */
    suspend fun installMarked(placeIds: Set<String>): LibraryInstallReport {
        val items = libraryInstallItems(marked, catalog, blocks, skills)
        if (items.isEmpty() || placeIds.isEmpty()) return LibraryInstallReport()
        val report = withContext(Dispatchers.IO) { installer.install(items, placeIds) }
        refreshUsage()
        return report
    }

    /**
     * Takes one object out of one place. Goes through the same installer a bulk removal uses, so a
     * copy edited by hand there is refused here too — dropping that edit is a decision taken in Place.
     */
    suspend fun uninstallFrom(key: LibraryObjectKey, placeId: String): LibraryInstallReport {
        val items = libraryInstallItems(setOf(key), catalog, blocks, skills)
        if (items.isEmpty()) return LibraryInstallReport()
        val report = withContext(Dispatchers.IO) { installer.remove(items, setOf(placeId)) }
        refreshUsage()
        return report
    }

    /** Adds the marked objects to [groupId]; returns how many joined. Marked groups do not nest. */
    fun addMarkedToGroup(groupId: String): Int {
        val group = groups.find { it.id == groupId } ?: return 0
        val updated = groupWithAdded(group, marked)
        val added = updated.blockIds.size - group.blockIds.size + updated.skillIds.size - group.skillIds.size
        if (added == 0) return 0
        guard {
            repository.saveGroup(updated)
            listsStamp++
            groups = repository.listGroups()
            groupDraft = editableGroup()
        }
        return added
    }

    /** Writes only the marked objects to [zip]; a marked group takes its members along. */
    fun exportMarked(zip: Path) {
        archive.export(
            zip,
            ArchiveSelection(
                blockIds = marked.filter {
                    it.kind == LibraryObjectKind.RULE || it.kind == LibraryObjectKind.SUBAGENT || it.kind == LibraryObjectKind.MCP
                }
                    .mapTo(mutableSetOf()) { it.id },
                groupIds = marked.filter { it.kind == LibraryObjectKind.GROUP }.mapTo(mutableSetOf()) { it.id },
                profileIds = marked.filter { it.kind == LibraryObjectKind.PROFILE }.mapTo(mutableSetOf()) { it.id },
                skillIds = marked.filter { it.kind == LibraryObjectKind.SKILL }.mapTo(mutableSetOf()) { it.id },
            ),
        )
    }

    fun prepareCreate(kind: LibraryObjectKind) {
        when (kind) {
            LibraryObjectKind.RULE -> select(Selection.Type(BlockType.RULE))
            LibraryObjectKind.SUBAGENT -> select(Selection.Type(BlockType.SUBAGENT))
            LibraryObjectKind.MCP -> select(Selection.Type(BlockType.MCP))
            LibraryObjectKind.SKILL -> select(Selection.Skills)
            LibraryObjectKind.GROUP -> Unit
            LibraryObjectKind.PROFILE -> Unit
        }
    }

    fun select(selection: Selection) {
        if (selection == Selection.Type(BlockType.MCP) || selection == Selection.Skills || selection is Selection.ProfileRef) selectedScope = null
        this.selection = selection
        if (selection is Selection.GroupRef) {
            selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.GROUP, selection.id)
        }
        if (selection is Selection.ProfileRef) {
            selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.PROFILE, selection.id)
        }
        draft = null
        skillDraft = null
        draftScope = null
        groupDraft = editableGroup()
        profileDraft = selectedProfile
    }

    /** Switches the library between global rules and one project's rules. */
    fun selectScope(project: String?) {
        selectedScope = project
        if (project != null && selection == Selection.Type(BlockType.MCP)) selection = Selection.Type(BlockType.RULE)
        if (project != null && selection == Selection.Skills) selection = Selection.Type(BlockType.RULE)
        draft = null
        skillDraft = null
        draftScope = null
        groupDraft = editableGroup()
        profileDraft = selectedProfile
    }

    fun selectBlock(id: String) {
        draft = blocks.find { it.id == id }
        skillDraft = null
        draftScope = draft?.takeIf { it.type == BlockType.RULE }?.let { scopeOf(it.id) }
        draft?.let { block ->
            selectedCatalogKey = LibraryObjectKey(
                block.type.objectKind(),
                block.id,
            )
        }
    }

    fun selectSkill(id: String) {
        skillDraft = skills.find { it.id == id }
        draft = null
        skillDraft?.let { selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.SKILL, it.id) }
    }

    /**
     * Drops the inspected object. Nothing is selected until the reader picks a row: an object shown
     * before that is one the reader never asked for, and the first one of the catalog is the
     * reserved group — a fact about the library, not about anything they came here to read.
     */
    fun clearCatalogSelection() {
        selectedCatalogKey = null
        draft = null
        skillDraft = null
        draftScope = null
        groupDraft = null
        profileDraft = null
    }

    private fun normalizeCatalogSelection() {
        val keys = catalog.objects.map { it.key }.toSet()
        // A selection that lost its object is cleared rather than moved onto a neighbour: what the
        // reader was looking at is gone, and the inspector says so instead of naming something else.
        selectedCatalogKey = selectedCatalogKey?.takeIf { it in keys }
        // A deleted or renamed object must not stay ticked: the bulk bar would then promise a write
        // that has nothing behind it.
        marked = marked.filterTo(LinkedHashSet()) { it in keys }
    }

    fun editSkillDraft(edit: (Skill) -> Skill) {
        skillDraft = skillDraft?.let(edit)
    }

    /** Saves a compare-side body as the object's next library revision. */
    /** Answers whether [content] reached the library, so a compare side can move its saved body. */
    suspend fun saveComparedContent(key: LibraryObjectKey, content: String): Boolean {
        val block = when (key.kind) {
            LibraryObjectKind.RULE, LibraryObjectKind.SUBAGENT, LibraryObjectKind.MCP ->
                draft?.takeIf { it.id == key.id } ?: blocks.find { it.id == key.id }
            else -> null
        }
        val skill = if (key.kind == LibraryObjectKind.SKILL) {
            skillDraft?.takeIf { it.id == key.id } ?: skills.find { it.id == key.id }
        } else {
            null
        }
        if (block?.source != null || block == null && (skill == null || skill.source != null)) return false

        return try {
            val snapshot = withContext(Dispatchers.IO) {
                when {
                    block != null -> library.saveBlock(block.copy(content = content), scopeOf(block.id)).library
                    skill != null -> library.saveSkill(skill.copy(content = content)).library
                    else -> error("A compare side must be a block or a local skill.")
                }
            }
            apply(snapshot)
            if (draft?.id == key.id) draft = blocks.find { it.id == key.id }
            if (skillDraft?.id == key.id) skillDraft = skills.find { it.id == key.id }
            true
        } catch (error: Exception) {
            failure = Failure.of(error)
            false
        }
    }

    /** Returns an edited group to its own form after either a block or a skill was selected. */
    fun clearItemSelection() {
        draft = null
        skillDraft = null
    }

    fun editDraft(edit: (Block) -> Block) {
        if (draft?.source == null) draft = draft?.let(edit)
    }

    fun editDraftScope(project: String?) {
        if (draft?.type == BlockType.RULE) draftScope = project
    }

    fun editGroupDraft(edit: (Group) -> Group) {
        groupDraft = groupDraft?.let(edit)
    }

    fun editProfileDraft(edit: (Profile) -> Profile) {
        profileDraft = profileDraft?.let(edit)
    }

    /**
     * Swaps [id] with its neighbour in the edited group's [Group.blockIds]. [delta] is `-1` for up,
     * `+1` for down; out-of-range moves are ignored. Lives in [groupDraft] until [saveGroupDraft]
     * commits it — the same transient lifetime as every other group edit.
     */
    fun moveBlockInGroup(id: String, delta: Int) {
        editGroupDraft { group ->
            val ids = group.blockIds
            val visibleIds = ids.filter { candidate -> blocksInSelectedScope.any { it.id == candidate } }
            val visibleIndex = visibleIds.indexOf(id)
            if (visibleIndex < 0) return@editGroupDraft group
            val targetVisibleIndex = visibleIndex + delta
            if (targetVisibleIndex !in visibleIds.indices) return@editGroupDraft group
            val index = ids.indexOf(id)
            val target = ids.indexOf(visibleIds[targetVisibleIndex])
            val swapped = ids.toMutableList()
            java.util.Collections.swap(swapped, index, target)
            group.copy(blockIds = swapped)
        }
    }

    /** The selected group when it can be edited; the reserved "all" group is maintained by Ruleblend. */
    private fun editableGroup(): Group? = selectedGroup?.takeIf { it.id != ALL_GROUP_ID }

    /** The configured [dev.ruleblend.core.model.NameFormat], read on demand from disk. */
    val nameFormat: dev.ruleblend.core.model.NameFormat
        get() = configStore.load().nameFormat

    /** Persisted column widths (dp) for the Library tab; reloaded on [load]. */
    var columnWidths by mutableStateOf(ColumnWidths())
        private set

    /** Persists a single column width; clamped by the caller before reaching here. */
    fun saveColumnWidth(which: LibraryColumn, dp: Int) {
        configStore.update { config ->
            val widths = config.columnWidths
            config.copy(
                columnWidths = when (which) {
                    LibraryColumn.FACETS -> widths.copy(libraryFacets = dp)
                    LibraryColumn.INSPECTOR -> widths.copy(libraryInspector = dp)
                },
            )
        }
        columnWidths = configStore.load().columnWidths
    }

    /**
     * Creates a block named [name] in the current type; ids collide-proofed with a suffix. The name
     * is normalized per the configured [dev.ruleblend.core.model.NameFormat]. Starts at version 0 (an
     * empty body), so the first saved body becomes version 1.
     */
    fun createBlock(name: String, type: BlockType) = guard {
        val created = library.createBlock(name, type, selectedScope)
        apply(created.library)
        draft = created.value
        if (type == BlockType.RULE) draftScope = selectedScope
        selection = Selection.Type(type)
        selectedCatalogKey = LibraryObjectKey(kindOf(type), created.value.id)
    }

    /**
     * Saves [content] as a new rule without touching the selection or the draft: it is called from a
     * comparison, which stays on the pair it was showing.
     */
    fun createRuleFromText(name: String, content: String) = guard {
        apply(library.createRule(name, content, selectedScope).library)
    }

    /**
     * Creates a rule and immediately adds it to the group being edited, without leaving the group
     * context — the counterpart of [createBlock] for the group editor's "+" button. Unlike
     * [createBlock], selection stays on the group so the editor pane keeps the group form.
     */
    fun createBlockInGroup(name: String) = guard {
        val current = groupDraft ?: return@guard
        val created = library.createBlock(name, BlockType.RULE, selectedScope)
        apply(created.library)
        draftScope = selectedScope
        groupDraft = groups.find { it.id == current.id }?.copy(blockIds = current.blockIds + created.value.id)
    }

    /**
     * Saves the editor draft. The name is normalized per the configured
     * [dev.ruleblend.core.model.NameFormat] on the way in, so what is stored matches the setting no
     * matter how it was typed. The id is left alone: it identifies the file and every group and
     * installed region that references it.
     */
    suspend fun saveDraft(): Block? {
        if (savingDraft) return null
        val current = draft ?: return null
        if (current.source != null) return null
        val scope = draftScope
        savingDraft = true
        return try {
            val saved = withContext(Dispatchers.IO) { library.saveBlock(current, scope) }
            draft = saved.value
            apply(saved.library)
            if (current.type == BlockType.RULE) {
                selectedScope = scope
                draftScope = scope
            }
            saved.value
        } catch (error: Exception) {
            failure = Failure.of(error)
            withContext(Dispatchers.IO) { runCatching { library.snapshot() } }.getOrNull()?.let(::apply)
            null
        } finally {
            savingDraft = false
        }
    }

    /**
     * Turns [source] into N new blocks from [result], one per part. The source block itself is left
     * untouched: any target that has it installed keeps its region until the user chooses to
     * replace it. Every new part inherits [source]'s group memberships, so the split is transparent
     * to group-level installs.
     */
    fun splitBlock(source: Block, result: SplitResult) = guard {
        val sourceScope = scopeOf(source.id)
        val split = library.splitBlock(
            source,
            result.parts.map { LibraryPart(it.name, it.body) },
            result.description,
        )
        apply(split.library)
        if (source.type == BlockType.RULE) draftScope = sourceScope
        split.value.firstOrNull()?.let { part ->
            draft = blocks.find { it.id == part.id }
            selectedCatalogKey = LibraryObjectKey(kindOf(part.type), part.id)
        }
        selection = Selection.Type(source.type)
    }

    /** Copies [source]'s content into a new block at v1 — the sanctioned way to fork a rule into a variant. */
    fun duplicateBlock(source: Block) = guard {
        val sourceScope = scopeOf(source.id)
        val copy = library.duplicateBlock(source)
        apply(copy.library)
        draft = copy.value
        if (source.type == BlockType.RULE) draftScope = sourceScope
        selection = Selection.Type(source.type)
        selectedCatalogKey = LibraryObjectKey(kindOf(source.type), copy.value.id)
    }

    suspend fun fetchGitSkills(repository: String): GitSkillImportPlan =
        withContext(Dispatchers.IO) { imports.fetch(repository) }

    fun applyGitSkillImport(plan: GitSkillImportPlan, sourcePaths: Set<String>) = guard {
        apply(imports.applyGitSkills(plan, sourcePaths))
    }

    internal suspend fun applyGitImport(plan: GitSkillImportPlan, selection: GitImportSelection): Boolean = try {
        val snapshot = withContext(Dispatchers.IO) {
            imports.applyGit(plan, selection.skills, selection.selectedSubagents)
        }
        apply(snapshot)
        true
    } catch (error: Exception) {
        failure = Failure.of(error)
        false
    }

    suspend fun updateSubagent(source: Block) {
        if (source.source == null) return
        runCatching { withContext(Dispatchers.IO) { imports.updateSubagents(setOf(source.id)) } }
            .onSuccess { updated ->
                apply(updated.library)
                draft = blocks.find { it.id == source.id }
            }
            .onFailure { error -> failure = Failure.of(error) }
    }

    /** Refreshes one imported skill from its repository, preserving its stable local id. */
    suspend fun updateSkill(source: Skill) {
        if (source.source == null) return
        runCatching { withContext(Dispatchers.IO) { imports.updateSkill(source) } }
            .onSuccess { updated ->
                apply(updated.library)
                skillDraft = skills.find { it.id == updated.value.id }
            }
            .onFailure { error -> failure = Failure.of(error) }
    }

    fun forkSkill(source: Skill) = guard {
        val forked = library.forkSkill(source)
        apply(forked.library)
        skillDraft = skills.find { it.id == forked.value.id }
        selection = Selection.Skills
        selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.SKILL, forked.value.id)
    }

    /** Creates a local skill and opens its structured editor. */
    fun createSkill(name: String, description: String) = guard {
        val created = library.createSkill(name, description)
        apply(created.library)
        skillDraft = skills.find { it.id == created.value.id }
        selection = Selection.Skills
        selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.SKILL, created.value.id)
    }

    /** Saves the local skill and returns the exact new version, or `null` when the save failed. */
    fun saveSkillDraft(): Skill? {
        var savedSkill: Skill? = null
        guard {
            val current = skillDraft ?: return@guard
            require(skillDraftValid) { "Skill name, description and SKILL.md are required" }
            val saved = library.saveSkill(current)
            apply(saved.library)
            skillDraft = skills.find { it.id == saved.value.id }
            savedSkill = saved.value
        }
        return savedSkill
    }

    fun deleteSkill(id: String) = guard {
        apply(library.deleteSkill(id).library)
        if (skillDraft?.id == id) skillDraft = null
        normalizeCatalogSelection()
    }

    fun deleteBlock(id: String) = guard {
        apply(library.deleteBlock(id).library)
        if (draft?.id == id) draft = null
        groupDraft = editableGroup()
        normalizeCatalogSelection()
    }

    /** Applies the configured [dev.ruleblend.core.model.NameFormat] to [name], for a manual "format now" action. */
    fun previewName(name: String): String = library.previewName(name)

    fun createGroup(name: String) = guard {
        val created = library.createGroup(name)
        apply(created.library)
        select(Selection.GroupRef(created.value.id))
        selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.GROUP, created.value.id)
    }

    /** Saves the group editor draft, normalizing its name the same way [saveDraft] does. */
    fun saveGroupDraft() = guard {
        val current = groupDraft ?: return@guard
        apply(library.saveGroup(current).library)
        groupDraft = editableGroup()
    }

    fun deleteGroup(id: String) = guard {
        if (id == ALL_GROUP_ID) return@guard
        apply(library.deleteGroup(id).library)
        if ((selection as? Selection.GroupRef)?.id == id) select(Selection.Type(BlockType.RULE))
        normalizeCatalogSelection()
    }

    fun createProfile(name: String) = guard {
        val created = library.createProfile(name)
        apply(created.library)
        select(Selection.ProfileRef(created.value.id))
        selectedCatalogKey = LibraryObjectKey(LibraryObjectKind.PROFILE, created.value.id)
    }

    fun saveProfileDraft() = guard {
        val current = profileDraft ?: return@guard
        apply(library.saveProfile(current).library)
        profileDraft = selectedProfile
    }

    fun deleteProfile(id: String) = guard {
        apply(library.deleteProfile(id).library)
        if ((selection as? Selection.ProfileRef)?.id == id) select(Selection.Type(BlockType.RULE))
        normalizeCatalogSelection()
    }

    fun export(zip: Path) {
        imports.export(zip)
    }

    /** Reads [zip] and returns what it would change. Nothing is written until [applyImport]. */
    fun planImport(zip: Path): ImportPlan = imports.plan(zip)

    fun applyImport(
        plan: ImportPlan,
        blockIds: Set<String>,
        groupIds: Set<String>,
        skillIds: Set<String>,
        profileIds: Set<String>,
    ) = guard {
        apply(imports.apply(plan, blockIds, groupIds, skillIds, profileIds))
        draft = draft?.let { current -> blocks.find { it.id == current.id } }
        groupDraft = editableGroup()
        profileDraft = selectedProfile
        normalizeCatalogSelection()
    }

    fun clearFailure() {
        failure = null
    }

    fun scopeOf(blockId: String): String? = ruleScopes[blockId]

    /** Takes the library back as the use case read it; every mutation of this model ends here. */
    private fun apply(snapshot: LibrarySnapshot) {
        listsStamp++
        blocks = snapshot.blocks
        groups = snapshot.groups
        profiles = snapshot.profiles
        skills = snapshot.skills
        ruleScopes = snapshot.ruleScopes
        projectScopes = configStore.load().knownProjectScopes()
    }

    private fun kindOf(type: BlockType): LibraryObjectKind =
        type.objectKind()

    /**
     * Runs [action], surfacing an I/O failure (read-only library dir, disk full, a broken git repo)
     * as a dialog instead of letting it escape into Compose. The lists are re-read from disk either
     * way, so what is shown is what actually landed.
     */
    private fun guard(action: () -> Unit) {
        runCatching(action).onFailure { error ->
            failure = Failure.of(error)
            runCatching {
                listsStamp++
                blocks = repository.listBlocks()
                groups = repository.listGroups()
                profiles = repository.listProfiles()
                skills = repository.listSkills()
            }
        }
    }
}

/** Resizable columns of the Library tab. */
/** The two draggable columns of the Library surface: facets on the left, inspector on the right. */
enum class LibraryColumn { FACETS, INSPECTOR }
