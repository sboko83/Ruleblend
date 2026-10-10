package dev.ruleblend.core.usecase

import dev.ruleblend.core.integration.ModRemovalPreview
import dev.ruleblend.core.integration.ModRuntime
import dev.ruleblend.core.integration.ModRuntimeException
import dev.ruleblend.core.integration.ModRuntimeError
import dev.ruleblend.core.integration.ModRuntimeState
import dev.ruleblend.core.integration.ModRuntimeStatus
import dev.ruleblend.core.integration.ModTarget
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.modTreeFingerprint

enum class ModLibraryRemovalChoice { KEEP_INSTALLATION, UNINSTALL }

data class ModLibraryRemovalPreview(val runtime: ModRuntimeStatus, val uninstall: ModRemovalPreview?, val libraryFingerprint: String?)

/** Explicit local actions. Git sync deletion never calls this use case or uninstalls a plugin. */
class ModLifecycle(
    private val repository: LibraryRepository,
    private val library: MutateLibrary,
    private val runtime: ModRuntime,
    private val target: ModTarget = ModTarget(),
) {
    fun install(id: String, acceptedFingerprint: String? = null) = runtime.install(target, acceptedSnapshot(id, acceptedFingerprint))
    fun update(id: String, acceptedFingerprint: String? = null) = runtime.update(target, acceptedSnapshot(id, acceptedFingerprint))

    private fun acceptedSnapshot(id: String, acceptedFingerprint: String?): dev.ruleblend.core.model.ModSnapshot {
        val snapshot = requireNotNull(repository.loadModSnapshot(id))
        if (acceptedFingerprint != null && modTreeFingerprint(snapshot.files) != acceptedFingerprint)
            throw ModRuntimeException(ModRuntimeError.CONFIRMATION_REQUIRED, "Library package changed after the installation preview; refresh and review it again")
        return snapshot
    }
    fun enable(id: String) = runtime.enable(target, id)
    fun disable(id: String) = runtime.disable(target, id)
    fun status(id: String) = runtime.probe(target, id, repository.loadMod(id)?.manifestName)
    fun recover() = runtime.recover(target)
    fun previewUninstall(id: String) = runtime.previewRemove(target, id)
    fun uninstall(id: String, accepted: ModRemovalPreview) = runtime.remove(target, id, accepted)

    fun previewLibraryRemoval(id: String): ModLibraryRemovalPreview {
        val status = status(id)
        return ModLibraryRemovalPreview(status, if (status.state == ModRuntimeState.OWNED && status.installed)
            previewUninstall(id) else null, repository.loadModSnapshot(id)?.let { modTreeFingerprint(it.files) })
    }

    /** KEEP_INSTALLATION also handles orphaned/foreign/drifted runtime state without touching it. */
    fun removeFromLibrary(id: String, choice: ModLibraryRemovalChoice?, accepted: ModLibraryRemovalPreview): LibraryChange<Unit> {
        val current = previewLibraryRemoval(id)
        if (current != accepted || (current.runtime.state != ModRuntimeState.ABSENT && choice == null))
            throw ModRuntimeException(ModRuntimeError.CONFIRMATION_REQUIRED, "Choose whether to keep the existing runtime installation")
        if (choice == ModLibraryRemovalChoice.UNINSTALL) {
            if (current.runtime.state !in setOf(ModRuntimeState.OWNED, ModRuntimeState.ABSENT))
                throw ModRuntimeException(ModRuntimeError.FOREIGN, "Runtime installation cannot be removed without confirmed ownership")
            if (current.runtime.installed) uninstall(id, requireNotNull(current.uninstall))
        }
        // Immutable runtime snapshots and sidecar remain outside the library, including after sync.
        // Do not hold a library lock across the CLI boundary: callers may use separate lock instances.
        return repository.withLock {
            if (repository.loadModSnapshot(id)?.let { modTreeFingerprint(it.files) } != accepted.libraryFingerprint)
                throw ModRuntimeException(ModRuntimeError.CONFIRMATION_REQUIRED, "Library package changed after the deletion preview")
            library.deleteMod(id)
        }
    }
}
