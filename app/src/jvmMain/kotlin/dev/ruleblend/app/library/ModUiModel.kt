package dev.ruleblend.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.*
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.storage.*
import dev.ruleblend.core.usecase.*
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Portable packages and local runtime state are read separately; sync never calls an action. */
class ModUiModel(
    private val repository: LibraryRepository,
    config: ConfigStore,
    private val runtime: ModRuntime?,
    private val imports: ImportLibrary,
    private val onLibraryChanged: (LibrarySnapshot) -> Unit = {},
    private val onRuntimeChanged: () -> Unit = {},
) {
    private val lifecycle = runtime?.let { ModLifecycle(repository, MutateLibrary(repository, config), it) }
    var mods by mutableStateOf(emptyList<Mod>())
        private set
    var packages by mutableStateOf(emptyMap<String, ModImportCandidate>())
        private set
    var fingerprints by mutableStateOf(emptyMap<String, String>())
        private set
    var statuses by mutableStateOf(emptyMap<String, ModRuntimeStatus>())
        private set
    var entries by mutableStateOf(emptyList<ModRuntimeEntry>())
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    internal fun land(mods: List<Mod>) { this.mods = mods }

    private data class Read(
        val mods: List<Mod>, val packages: Map<String, ModImportCandidate>, val fingerprints: Map<String, String>,
        val statuses: Map<String, ModRuntimeStatus>, val entries: List<ModRuntimeEntry>, val error: String?,
    )

    private fun read(): Read {
        val snapshots = repository.withLock { repository.listMods().mapNotNull { repository.loadModSnapshot(it.id) } }
        val packages = snapshots.associate { snapshot ->
            val compatibility = runCatching { ModImport.validate(snapshot.files, snapshot.mod.source) }.exceptionOrNull()
                ?.let { it.message ?: it.toString() }
            snapshot.mod.id to ModImportCandidate(".", snapshot, compatibility)
        }
        val fingerprints = snapshots.associate { it.mod.id to modTreeFingerprint(it.files) }
        return try {
            val adapter = requireNotNull(runtime) { "Claude Code CLI is unavailable" }
            val entries = adapter.inventory(ModTarget())
            val ids = snapshots.map { it.mod.id }.toSet() + entries.mapNotNull { it.libraryId }
            val statuses = ids.associateWith { id -> adapter.probe(ModTarget(), id, snapshots.find { it.mod.id == id }?.mod?.manifestName) }
            Read(snapshots.map { it.mod }, packages, fingerprints, statuses, entries, null)
        } catch (failure: Exception) {
            Read(snapshots.map { it.mod }, packages, fingerprints, emptyMap(), emptyList(), failure.message ?: failure.toString())
        }
    }

    private fun land(read: Read) {
        mods = read.mods; packages = read.packages; fingerprints = read.fingerprints
        statuses = read.statuses; entries = read.entries; error = read.error
        onRuntimeChanged()
    }

    suspend fun refresh() {
        if (busy) return
        busy = true
        try { land(withContext(Dispatchers.IO) { read() }) }
        catch (failure: Exception) { error = failure.message ?: failure.toString() }
        finally { busy = false }
    }

    private suspend fun action(block: () -> Unit): Boolean {
        if (busy) return false
        busy = true; error = null
        return try {
            val read = withContext(Dispatchers.IO) { block(); read() }
            land(read)
            true
        } catch (failure: Exception) {
            // A CLI failure can leave a journal pending: re-probe before offering another write.
            runCatching { withContext(Dispatchers.IO) { read() } }.onSuccess(::land)
            error = failure.message ?: failure.toString()
            false
        } finally { busy = false }
    }

    suspend fun install(id: String): Boolean {
        val accepted = fingerprints[id] ?: return false
        return action { requireNotNull(lifecycle).install(id, accepted) }
    }
    suspend fun update(id: String): Boolean {
        val accepted = fingerprints[id] ?: return false
        return action { requireNotNull(lifecycle).update(id, accepted) }
    }
    suspend fun toggle(id: String, enabled: Boolean) = action {
        if (enabled) requireNotNull(lifecycle).enable(id) else requireNotNull(lifecycle).disable(id)
    }
    suspend fun recover() = action { requireNotNull(lifecycle).recover() }

    suspend fun preview(directory: Path): ModImportCandidate = withContext(Dispatchers.IO) { imports.previewMod(directory) }
    suspend fun fetch(source: String): GitRepositoryImportPlan = withContext(Dispatchers.IO) { imports.fetch(source) }
    suspend fun importGit(plan: GitRepositoryImportPlan, paths: Set<String>): Boolean {
        var changed: LibrarySnapshot? = null
        val ok = action { changed = imports.applyGit(plan, emptySet(), emptyMap(), paths) }
        changed?.let(onLibraryChanged)
        return ok
    }
    suspend fun import(preview: ModImportCandidate, existingId: String? = null): Boolean {
        var changed: LibrarySnapshot? = null
        val ok = action { changed = imports.applyMod(preview, existingId).library }
        changed?.let(onLibraryChanged)
        return ok
    }
    suspend fun updateSource(id: String): Boolean {
        var changed: LibrarySnapshot? = null
        val ok = action { changed = imports.updateMods(setOf(id)).library }
        changed?.let(onLibraryChanged)
        return ok
    }
    suspend fun previewUninstall(id: String): ModRemovalPreview = withContext(Dispatchers.IO) {
        requireNotNull(lifecycle).previewUninstall(id)
    }
    suspend fun uninstall(id: String, preview: ModRemovalPreview) = action { requireNotNull(lifecycle).uninstall(id, preview) }
    suspend fun previewDelete(id: String): ModLibraryRemovalPreview = withContext(Dispatchers.IO) {
        requireNotNull(lifecycle).previewLibraryRemoval(id)
    }
    suspend fun delete(id: String, preview: ModLibraryRemovalPreview, choice: ModLibraryRemovalChoice): Boolean {
        var changed: LibrarySnapshot? = null
        val ok = action { changed = requireNotNull(lifecycle).removeFromLibrary(id, choice, preview).library }
        changed?.let(onLibraryChanged)
        return ok
    }

    fun updateAvailable(id: String): Boolean = statuses[id]?.let {
        it.state == ModRuntimeState.OWNED && it.installed && it.fingerprint != fingerprints[id] && fingerprints[id] != null
    } == true

    fun tabCount() = dev.ruleblend.app.place.PlaceObjectCount(
        ours = statuses.values.count { it.installed && it.state == ModRuntimeState.OWNED },
        foreign = entries.count { it.libraryId == null } + statuses.values.count { status ->
            status.installed && status.state != ModRuntimeState.OWNED &&
                entries.none { it.libraryId == null && it.pluginId == status.pluginId }
        },
    )

    fun tabMarks(): Set<dev.ruleblend.app.theme.StatusMark> = buildSet {
        statuses.values.filter { it.installed }.forEach { status -> add(when {
            status.state == ModRuntimeState.DRIFT || status.state == ModRuntimeState.PENDING -> dev.ruleblend.app.theme.StatusMark.CONFLICT
            status.state != ModRuntimeState.OWNED -> dev.ruleblend.app.theme.StatusMark.UNMANAGED
            updateAvailable(status.libraryId) -> dev.ruleblend.app.theme.StatusMark.UPDATE
            else -> dev.ruleblend.app.theme.StatusMark.MANAGED
        }) }
        if (entries.any { it.libraryId == null }) add(dev.ruleblend.app.theme.StatusMark.UNMANAGED)
    }

    suspend fun installInPlaces(ids: Set<String>, placeIds: Set<String>, accepted: Map<String, String> = fingerprints): LibraryInstallReport {
        if (ids.isEmpty()) return LibraryInstallReport()
        val unsupported = ids.size * placeIds.count { it != "agent:claude-code" }
        var written = 0; var failed = 0
        if ("agent:claude-code" in placeIds) for (id in ids) {
            // Installing a group must not apply an unaccepted library update to an existing runtime.
            val ok = action {
                val status = requireNotNull(lifecycle).status(id)
                if (!status.installed) lifecycle.install(id, requireNotNull(accepted[id]) { "Refresh and review the package before installing" })
                else require(status.state == ModRuntimeState.OWNED) { "Installation is not owned by Ruleblend" }
            }
            if (ok) written++ else failed++
        }
        return LibraryInstallReport(written, if (unsupported > 0) mapOf(SkipReason.UNSUPPORTED to unsupported) else emptyMap(), failed)
    }
}
