package dev.ruleblend.app.resolve

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ruleblend.app.library.LibraryUsageSource
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * State of the Resolve surface: one classified snapshot of every conflict in the fleet, and the batch
 * that settles the hand-edited ones.
 *
 * The scan is the same cross-place read Home and Coverage run, so entering Resolve costs one scan and
 * holds its answer until an explicit [rescan]; a failed scan keeps the previous one rather than
 * reporting an empty fleet. After a batch the fleet is read again once — not once per row: the new
 * state is an answer to be looked up, not one to be inferred from what was asked for.
 */
class ResolveModel(
    private val repository: LibraryRepository,
    private val usageSource: LibraryUsageSource,
    private val applier: ResolveApplier = ResolveApplier.None,
) {
    var snapshot by mutableStateOf(ResolveSnapshot.EMPTY)
        private set

    var scanning by mutableStateOf(false)
        private set

    /** Wall-clock duration of the last scan, in milliseconds; `null` until one finishes. */
    var lastScanMillis by mutableStateOf<Long?>(null)
        private set

    /** Which class the table shows. Selection survives a rescan: the class is the user's place here. */
    var selected by mutableStateOf(ResolveClass.HAND_EDITED)
        private set

    var writing by mutableStateOf(false)
        private set

    /**
     * Rows ticked for the batch, by [ResolveRow.key]. Empty after every scan, deliberately: the batch
     * can discard hand-written text in dozens of files, so it starts from nothing selected and the
     * fleet-wide answer is one press of "select all" away rather than the default.
     */
    var marked by mutableStateOf(emptySet<String>())
        private set

    /** The strategy every marked row inherits unless [overrides] says otherwise. */
    var base by mutableStateOf(ResolveStrategy.RESTORE)
        private set

    /** Per-row exceptions to [base]; cleared with the selection once a batch has run. */
    var overrides by mutableStateOf(emptyMap<String, ResolveStrategy>())
        private set

    /** What the last batch did, kept until dismissed: a partial batch has to stay readable. */
    var report by mutableStateOf<ResolveReport?>(null)
        private set

    /** The hand-edited class, which is the only one a strategy applies to. */
    private val resolvable: List<ResolveGroup> get() = snapshot.groups(ResolveClass.HAND_EDITED)

    /** What applying would do, resolved against the snapshot in memory; reads and writes nothing. */
    val plan: ResolvePlan get() = resolvePlan(resolvable, marked, base, overrides)

    fun select(cls: ResolveClass) {
        selected = cls
    }

    fun toggleMark(key: String) {
        marked = if (key in marked) marked - key else marked + key
    }

    /** Ticks or clears every hand-edited row; the ambiguous and legacy classes have nothing to tick. */
    fun markAll(marked: Boolean) {
        this.marked = if (marked) resolveKeys(resolvable) else emptySet()
    }

    fun chooseBase(strategy: ResolveStrategy) {
        base = strategy
    }

    /** Sets one row's exception, or drops it back to inheriting [base] when [strategy] is null. */
    fun setOverride(key: String, strategy: ResolveStrategy?) {
        overrides = if (strategy == null) overrides - key else overrides + (key to strategy)
    }

    fun dismissReport() {
        report = null
    }

    suspend fun rescan() {
        if (scanning) return
        scanning = true
        try {
            val started = System.nanoTime()
            val next = withContext(Dispatchers.IO) { runCatching { FileReadScope.reading { scan() } }.getOrNull() } ?: return
            lastScanMillis = (System.nanoTime() - started) / 1_000_000
            snapshot = next
            marked = emptySet()
            overrides = emptyMap()
        } finally {
            scanning = false
        }
    }

    /**
     * Runs the batch place by place. Each place is the same pair of writes one drifted block gets in
     * Place, so a place that refuses costs the batch that place alone.
     */
    suspend fun apply() {
        val plan = plan
        if (writing || scanning || plan.empty) return
        writing = true
        report = null
        try {
            val result = withContext(Dispatchers.IO) { runCatching { applier.apply(plan) }.getOrNull() }
                ?: ResolveReport(failed = plan.places.map { it.placeName }, skipped = plan.skipped)
            rescan()
            report = result
        } finally {
            writing = false
        }
    }

    private fun scan(): ResolveSnapshot {
        val blocks = repository.listBlocks()
        return resolveSnapshot(usageSource.scan(blocks, repository.listSkills()), blocks)
    }
}
