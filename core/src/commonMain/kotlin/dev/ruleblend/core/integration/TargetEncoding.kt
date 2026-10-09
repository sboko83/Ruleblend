package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block

/** Encoding of Ruleblend-managed content in one target file. */
interface TargetEncoding {
    fun installed(text: String): List<ManagedRegion>
    fun unmanaged(text: String): UnmanagedContent

    /**
     * The document split into hand-written text and managed runs, in file order. Read-only: it is
     * what [installed] and [unmanaged] already read, kept in the order the file has them, so a
     * preview can show where the managed region actually sits instead of assuming it comes last.
     * [library] resolves library blocks; a run whose body was edited outside Ruleblend uses the
     * untouched bodies as anchors to recover the edited one, exactly as [localChange] does. Without
     * a resolver such a run still lists its blocks, only without their text.
     */
    fun segments(text: String, library: (String) -> Block? = { null }): List<FileSegment>

    /**
     * Inserts or replaces [region]. When [force] is set, a wrapped run whose body was hand-edited is
     * overwritten from the library instead of raising a drift error — but only when its block
     * boundaries are still recoverable from the manifest's byte lengths. No-op for legacy markers,
     * which carry no run hash and so never drift.
     */
    fun upsert(text: String, region: ManagedRegion, force: Boolean = false): String

    /** Drops the region with [id]; [force] bypasses drift detection as in [upsert]. */
    fun remove(text: String, id: String, force: Boolean = false): String

    /** Multi-section adopt; [force] bypasses drift detection as in [upsert]. */
    fun adoptSections(text: String, plan: List<ManagedDocument.AdoptStep>, force: Boolean = false): String

    /**
     * Reorders the installed regions to match [order], changing nothing else: no body is rewritten and
     * no text outside the managed run is touched. Ids missing from [order] keep their relative sequence
     * after the ordered ones. There is no `force`: a run edited by hand has no trustworthy block
     * boundaries to reorder, so it is refused rather than rebuilt.
     */
    fun reorder(text: String, order: List<String>): String

    /**
     * The edited body of [blockId], but only when the edit can be attributed to that block alone.
     * [library] resolves library blocks, which is what the untouched neighbours are checked against.
     * Throws [WrappedRunDriftException] when another block of the same managed run was changed too:
     * refusing is safer than blessing or discarding someone else's edit.
     */
    fun localChange(text: String, blockId: String, library: (String) -> Block?): ManagedRegion {
        val installed = installed(text)
        val changedNeighbour = installed.firstOrNull { region ->
            region.id != blockId && library(region.id)?.let(::regionFor)?.content != region.content
        }
        if (changedNeighbour != null) {
            throw WrappedRunDriftException(
                "Cannot isolate $blockId: ${changedNeighbour.id} in the same managed run was also changed",
            )
        }
        return installed.firstOrNull { it.id == blockId }
            ?: throw WrappedRunDriftException("Rule $blockId is not installed in this file")
    }
}

/** Current and pre-1.4 marker format. It remains readable after ownership modes ship. */
object LegacyMarkers : TargetEncoding {
    override fun installed(text: String): List<ManagedRegion> = ManagedDocument.parse(text)
    override fun unmanaged(text: String): UnmanagedContent = ManagedDocument.unmanaged(text)
    override fun segments(text: String, library: (String) -> Block?): List<FileSegment> = ManagedDocument.segments(text)
    override fun upsert(text: String, region: ManagedRegion, force: Boolean): String = ManagedDocument.upsert(text, region)
    override fun remove(text: String, id: String, force: Boolean): String = ManagedDocument.remove(text, id)
    override fun adoptSections(text: String, plan: List<ManagedDocument.AdoptStep>, force: Boolean): String =
        ManagedDocument.adoptSections(text, plan)
    override fun reorder(text: String, order: List<String>): String = ManagedDocument.reorder(text, order)
}

/** Encoding selected from the file itself; no machine-local state participates in detection. */
fun targetEncoding(text: String): TargetEncoding = when (sniffOwnershipMode(text)) {
    TargetOwnershipMode.PARTIAL -> WrappedRun(TargetOwnershipMode.PARTIAL, hasPartialNotice(text))
    TargetOwnershipMode.OWNED -> WrappedRun(TargetOwnershipMode.OWNED, parseWrappedRunHeader(text.lineSequence().firstOrNull().orEmpty())?.notice != false)
    TargetOwnershipMode.NONE, TargetOwnershipMode.LEGACY -> LegacyMarkers
}
