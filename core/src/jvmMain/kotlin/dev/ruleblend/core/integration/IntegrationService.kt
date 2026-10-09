package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** Installs and removes managed regions in agent instruction files. */
class IntegrationService(
    private val targetIndex: TargetIndex? = null,
    /**
     * Loads a library block by id. Set so that a force-overwrite can rebuild a wrapped run from current
     * library bodies instead of trusting hand-edited text. Null leaves force with nothing to rebuild
     * from, so it can only skip the drift check — a run whose bodies no longer match its manifest
     * then raises rather than losing data.
     */
    private val blockResolver: ((String) -> Block?)? = null,
    /**
     * Serializes target-file mutations against the other Ruleblend writers. The default coordinator
     * synchronizes nothing, which is enough for a test or a throwaway instance; every wiring that
     * shares files with another process must pass the process-wide one.
     */
    private val coordinator: TargetMutationCoordinator = TargetMutationCoordinator(),
    private val lineEnding: () -> LineEnding = { LineEnding.LF },
) {

    /** Central encoding selector. Ownership is determined solely by target-file text. */
    private fun encodingFor(text: String): TargetEncoding = targetEncoding(text)

    /**
     * Selects the encoding for a content-changing write. Legacy markers remain readable forever,
     * but no regular workflow writes them: a first write in an empty file creates an owned rb1
     * run, while a file with hand-written text gets a partial run. A legacy file is folded into a
     * partial run before it changes.
     */
    private fun writableEncoding(text: String): Pair<String, TargetEncoding> = when (sniffOwnershipMode(text)) {
        TargetOwnershipMode.NONE -> text to WrappedRun(
            if (text.isBlank()) TargetOwnershipMode.OWNED else TargetOwnershipMode.PARTIAL,
        )
        TargetOwnershipMode.LEGACY -> WrappedRun(TargetOwnershipMode.PARTIAL).migrateLegacy(text) to
            WrappedRun(TargetOwnershipMode.PARTIAL)
        TargetOwnershipMode.PARTIAL -> {
            val partial = WrappedRun(TargetOwnershipMode.PARTIAL, hasPartialNotice(text))
            if (partial.unmanaged(text).isEmpty && !partial.hasDrift(text)) {
                val owned = WrappedRun(TargetOwnershipMode.OWNED, hasPartialNotice(text))
                val converted = partial.installed(text).fold("") { result, region -> owned.upsert(result, region) }
                converted to owned
            } else {
                text to partial
            }
        }
        TargetOwnershipMode.OWNED -> text to encodingFor(text)
    }

    private fun upsert(text: String, region: ManagedRegion, force: Boolean = false): String {
        val (source, encoding) = writableEncoding(text)
        val placed = region.keepingOrigin(encoding.installed(source))
        val (run, header) = forcedRun(force, encoding, source) ?: return encoding.upsert(source, placed, force)
        return run.forceReplace(source, rebuildRegions(header, { it.id == region.id }, replacement = placed))
    }

    /**
     * An untagged write updates the copy, it does not re-own it: a group's or profile's region keeps
     * that origin, so a later group removal or profile switch still recognises it.
     */
    private fun ManagedRegion.keepingOrigin(installed: List<ManagedRegion>): ManagedRegion {
        if (origin != null) return this
        val current = installed.find { it.id == id } ?: return this
        return copy(group = current.group, origin = current.origin)
    }

    private fun remove(text: String, id: String, force: Boolean = false): String {
        if (encodingFor(text).installed(text).none { it.id == id }) return text
        val (source, encoding) = writableEncoding(text)
        val (run, header) = forcedRun(force, encoding, source) ?: return encoding.remove(source, id, force)
        return run.forceReplace(source, rebuildRegions(header, { it.id == id }, replacement = null))
    }

    /**
     * The wrapped run to rebuild wholesale, with its manifest, when [force] asks for "take library".
     * A forced write always rewrites the whole run rather than editing it in place, so the result
     * does not depend on whether the hand edit happened to preserve the manifest's byte lengths.
     * Null when the ordinary path applies instead: no [force], no wrapped run yet (a first install into
     * an empty or hand-written file), or no [blockResolver] to read the other bodies from.
     */
    private fun forcedRun(force: Boolean, encoding: TargetEncoding, source: String): Pair<WrappedRun, WrappedRunHeader>? {
        if (!force || blockResolver == null) return null
        val wrapped = encoding as? WrappedRun ?: return null
        return wrapped.headerOf(source)?.let { wrapped to it }
    }

    /**
     * The blocks of [header] at their current library bodies, in manifest order. [isChanging] marks
     * the one being swapped out; its slot is filled by [replacement] when non-null (upsert) or
     * dropped (remove). A [replacement] whose id is absent from the manifest is appended, so a
     * forced install of a not-yet-installed rule still lands. Blocks that left the library are
     * dropped. Force-overwrite is "take library", run-wide, so a hand-edited neighbour loses its edits.
     */
    private fun rebuildRegions(
        header: WrappedRunHeader,
        isChanging: (WrappedRunBlock) -> Boolean,
        replacement: ManagedRegion?,
    ): List<ManagedRegion> {
        val resolver = blockResolver ?: throw WrappedRunDriftException("No block resolver: cannot force-overwrite a drifted run")
        val rebuilt = header.blocks.flatMap { block ->
            when {
                isChanging(block) && replacement != null -> listOf(replacement)
                isChanging(block) -> emptyList()
                else -> resolver(block.id)?.let { listOf(regionFor(it, block.group, block.origin)) }.orEmpty()
            }
        }
        return if (replacement != null && header.blocks.none(isChanging)) rebuilt + replacement else rebuilt
    }

    /** Regions currently installed in [file]; empty when the file does not exist. */
    fun regions(file: Path): List<ManagedRegion> =
        FileReadScope.textOrNull(file)?.let { text -> encodingFor(text).installed(text) } ?: emptyList()

    /** Origins of every managed rule in [target], including base (`null`) and group-owned entries. */
    fun installedOrigins(target: Target): Map<String, String?> = target.files()
        .flatMap(::regions)
        .associate { it.id to it.origin }

    /** [file] split into hand-written text and managed runs, in file order; empty when it is absent. */
    fun segments(file: Path): List<FileSegment> =
        FileReadScope.textOrNull(file)
            ?.let { text -> encodingFor(text).segments(text, blockResolver ?: { null }) }
            ?: emptyList()

    /**
     * Installs or updates [block] in [file], tagging it with [group] when it came from one.
     * [force] overwrites a region edited by hand: the whole managed run is rebuilt from the current
     * library bodies, so hand edits to neighbouring blocks are discarded as well.
     */
    fun install(
        file: Path,
        block: Block,
        group: String? = null,
        force: Boolean = false,
        origin: String? = group?.let { "g=$it" },
    ) = mutate(file, missing = "") { text -> upsert(text, regionFor(block, group, origin), force) }

    /** Removes the region for [blockId]; a no-op when the file has no such region. */
    fun remove(file: Path, blockId: String, force: Boolean = false) =
        mutate(file) { text -> remove(text, blockId, force).takeIf { it != text } }

    /**
     * Reorders the blocks installed in [file] to match [order] — the one write that changes neither a
     * block body nor anything outside the managed run. Nothing is written when the order already
     * matches: a no-op reorder must not drag the legacy-to-rb1 conversion of [writableEncoding] along
     * with it.
     */
    fun reorder(file: Path, order: List<String>) = mutate(file) { text ->
        val (source, encoding) = writableEncoding(text)
        encoding.reorder(source, order).takeIf { it != source }
    }

    /**
     * The one write path for a target file: read, transform, replace, keep the index in step. The
     * cycle runs guarded by [coordinator], so neither another Ruleblend writer nor an external
     * editor can have its text dropped by it. A null from [transform] leaves the file untouched;
     * [missing] is what a file that does not exist yet starts from, and null there means an absent
     * file has nothing to change.
     */
    private fun mutate(file: Path, missing: String? = null, formatManaged: Boolean = true, transform: (String) -> String?): String? =
        mutateWithIndex(listOf(file)) {
            val updated = coordinator.rewrite(file, missing) { source ->
                transform(source)?.let { changed ->
                    val encoding = encodingFor(changed)
                    if (formatManaged && encoding is WrappedRun) encoding.withLineEnding(changed, lineEnding()) else changed
                }
            }
            if (updated != null) targetIndex?.update(file, updated)
            updated
        }

    /**
     * Holds [files] and the index entries derived from them as one: the entry is written from the
     * text that just landed in the file, so a writer that comes in between cannot leave the index
     * describing a file that has since changed.
     */
    private fun <T> mutateWithIndex(files: List<Path>, body: () -> T): T =
        coordinator.mutate(*(files + listOfNotNull(targetIndex?.file)).toTypedArray(), body = body)

    /** [mutate] for the writes that need a wrapped run: [transform] never sees a legacy or plain file. */
    private fun mutateWrapped(file: Path, transform: (TargetOwnershipMode, String) -> String) = mutate(file) { text ->
        val mode = sniffOwnershipMode(text)
        if (mode == TargetOwnershipMode.PARTIAL || mode == TargetOwnershipMode.OWNED) transform(mode, text) else null
    }

    /** Ownership mode detected from [file], or [TargetOwnershipMode.NONE] when it is absent. */
    fun ownershipMode(file: Path): TargetOwnershipMode =
        FileReadScope.textOrNull(file)?.let(::ownershipModeOf) ?: TargetOwnershipMode.NONE

    private fun ownershipModeOf(text: String): TargetOwnershipMode {
        if (sniffOwnershipMode(text) != TargetOwnershipMode.PARTIAL) return sniffOwnershipMode(text)
        val partial = WrappedRun(TargetOwnershipMode.PARTIAL, hasPartialNotice(text))
        return if (partial.unmanaged(text).isEmpty && !partial.hasDrift(text)) {
            TargetOwnershipMode.OWNED
        } else {
            TargetOwnershipMode.PARTIAL
        }
    }

    /** Whether a wrapped target body was edited outside Ruleblend. */
    fun ownershipDrift(file: Path): Boolean = FileReadScope.textOrNull(file)?.let(::driftOf) ?: false

    private fun driftOf(text: String): Boolean = when (val mode = sniffOwnershipMode(text)) {
        TargetOwnershipMode.PARTIAL, TargetOwnershipMode.OWNED -> WrappedRun(mode).hasDrift(text)
        TargetOwnershipMode.NONE, TargetOwnershipMode.LEGACY -> false
    }

    /**
     * Everything one file can answer about itself, from a single read: the cross-place scan asks all
     * of it about every file of every place, and asking through [regions], [ownershipMode] and
     * [unmanaged] separately would re-read the same file three times at that scale.
     */
    fun scanFile(file: Path, library: ((String) -> Block?)? = null): FileScan {
        val text = FileReadScope.textOrNull(file) ?: return FileScan(file, exists = false)
        val encoding = encodingFor(text)
        return FileScan(
            file = file,
            exists = true,
            mode = ownershipModeOf(text),
            regions = encoding.installed(text),
            unmanaged = encoding.unmanaged(text),
            drift = driftOf(text),
            conflicts = library?.let { classifyConflicts(text, it) }.orEmpty(),
        )
    }

    /**
     * Converts legacy marker runs to one rb1 run. An owned conversion is allowed only when the
     * source has no hand-written text. Repeating the same conversion is a no-op.
     */
    fun migrateLegacy(file: Path, mode: TargetOwnershipMode, notice: Boolean = true) {
        require(mode == TargetOwnershipMode.PARTIAL || mode == TargetOwnershipMode.OWNED)
        mutate(file) { text ->
            if (sniffOwnershipMode(text) == TargetOwnershipMode.LEGACY) WrappedRun(mode, notice).migrateLegacy(text) else null
        }
    }

    /** Converts a wrapped target back to the readable legacy marker format. */
    fun revertToLegacy(file: Path) = mutateWrapped(file) { mode, text -> WrappedRun(mode).toLegacy(text) }

    /** Drops Ruleblend ownership markers but leaves rendered content in place. */
    fun disown(file: Path) = mutateWrapped(file) { mode, text -> WrappedRun(mode).disown(text) }

    /** Rewrites only the optional warning notice while retaining the selected ownership mode. */
    fun setNotice(file: Path, enabled: Boolean) =
        mutateWrapped(file) { mode, text -> WrappedRun(mode).withNotice(text, enabled) }

    /**
     * Installs [block] into every file of [target] as one operation, and reports what became of each
     * of them. A project can span several agents; if a later file refuses the write, the files that
     * already took it are put back the way they were, because half a target is not an answer the user
     * asked for. Restoring a file can itself fail — a directory that went read-only mid-write — and
     * that file is reported as still changed rather than quietly counted as undone.
     */
    fun install(
        target: Target,
        block: Block,
        group: String? = null,
        force: Boolean = false,
        origin: String? = group?.let { "g=$it" },
    ): TargetWriteResult = writeEveryFile(target) { file -> install(file, block, group, force, origin) }

    /**
     * Restores existing copies only, refusing a run whose neighbours no longer match the library.
     * Unlike an explicit run-wide force install, a row in Resolve authorizes discarding only that
     * rule's edit. Each check shares the write lock; a later refusal rolls earlier files back.
     */
    fun restoreLocalChange(target: Target, block: Block): TargetWriteResult = mutate(target) {
        require(target.files().any { file -> regions(file).any { it.id == block.id } }) {
            "Rule ${block.id} is no longer installed in this target"
        }
        writeEveryFile(target) { file ->
            mutate(file) update@{ text ->
                val encoding = encodingFor(text)
                if (encoding.installed(text).none { it.id == block.id }) return@update null
                val resolver = requireNotNull(blockResolver) { "No block resolver: cannot validate neighbouring rules" }
                val local = encoding.localChange(text, block.id, resolver)
                upsert(text, regionFor(block, local.group, local.origin), force = true)
            }
        }
    }

    /** Removes [blockId] from every file of [target], under the same all-or-nothing rule as [install]. */
    fun remove(target: Target, blockId: String, force: Boolean = false): TargetWriteResult =
        writeEveryFile(target) { file -> remove(file, blockId, force) }

    /**
     * Removes [blockId] when its library block is gone, so there is no body to compare the region
     * with. A managed region carries its own hash, which makes the file its own record: a region
     * that still hashes to its manifest entry is untouched Ruleblend text and may go, while one
     * that does not is a hand edit and stays. The read and the write share the lock, so the region
     * cannot change between deciding and deleting.
     */
    fun removeOrphan(target: Target, blockId: String): OrphanRemoval = coordinator.mutate(*target.files().toTypedArray()) {
        val installed = target.files().mapNotNull { file -> regions(file).find { it.id == blockId } }
        when {
            installed.isEmpty() -> OrphanRemoval.ABSENT
            installed.any { it.hash != hashContent(it.content) } -> OrphanRemoval.PROTECTED
            else -> {
                remove(target, blockId).problem()?.let { throw it }
                OrphanRemoval.REMOVED
            }
        }
    }

    /**
     * One write per file of [target], with the whole set held under one lock: [write] returns the new
     * text of a file it changed, or `null` for a file that already said the same thing.
     */
    private fun writeEveryFile(target: Target, write: (Path) -> String?): TargetWriteResult = mutate(target) {
        val files = target.files()
        val before = touchedFiles(target).associateWith { FileReadScope.textOrNull(it) }
        val writes = mutableListOf<TargetWrite>()
        fun undo(done: TargetWrite) =
            if (done.outcome != TargetWriteOutcome.WRITTEN) done
            else if (restore(done.file, before[done.file])) done.copy(outcome = TargetWriteOutcome.ROLLED_BACK)
            else done.copy(outcome = TargetWriteOutcome.STRANDED)
        fun failed(file: Path, failure: Throwable) =
            TargetWrite(file, TargetWriteOutcome.FAILED, failure.message ?: failure.toString())
        for ((index, file) in files.withIndex()) {
            val attempt = runCatching { write(file) }
            attempt.onSuccess { updated ->
                // "Written" means the file says something else now: a re-install of the same text
                // rewrites the file byte for byte, and reporting that as a change would make every
                // no-op look like one.
                val changed = updated != null && updated != before[file]
                writes += TargetWrite(file, if (changed) TargetWriteOutcome.WRITTEN else TargetWriteOutcome.UNCHANGED)
            }
            attempt.onFailure { failure ->
                val untouched = files.drop(index + 1).map { TargetWrite(it, TargetWriteOutcome.UNCHANGED) }
                return@mutate TargetWriteResult(writes.map(::undo) + failed(file, failure) + untouched)
            }
        }
        // A pointer is part of the same write: a rule that landed while its agent's pointer could not
        // be written is half an install, so it is undone like any other file of the target. An
        // earlier pointer sync may have moved regions too, so every touched file is compared with
        // what it held before, not only the ones the loop wrote.
        for (redirect in target.redirects()) {
            val failure = runCatching { syncRedirect(redirect.from, redirect.to) }.exceptionOrNull() ?: continue
            val settled = touchedFiles(target).mapNotNull { file ->
                when {
                    FileReadScope.textOrNull(file) != before[file] -> undo(TargetWrite(file, TargetWriteOutcome.WRITTEN))
                    file == redirect.from -> null
                    else -> TargetWrite(file, TargetWriteOutcome.UNCHANGED)
                }
            }
            return@mutate TargetWriteResult(settled + failed(redirect.from, failure))
        }
        TargetWriteResult(writes)
    }

    /** Puts one file back the way the operation found it. `false` when even that could not be done. */
    private fun restore(file: Path, text: String?): Boolean = runCatching {
        if (text == null) {
            Files.deleteIfExists(file)
            targetIndex?.remove(file)
        } else {
            mutate(file, missing = "", formatManaged = false) { current -> text.takeIf { it != current } }
        }
    }.isSuccess

    /**
     * One mutation over every file [target] can touch. Whole-target work holds the lock from its
     * first read to its last write, so a target is never published half-updated, and the nested
     * per-file [mutate] calls re-enter the same lock.
     */
    private fun <T> mutate(target: Target, body: () -> T): T = mutateWithIndex(touchedFiles(target), body)

    private fun touchedFiles(target: Target): List<Path> =
        (target.files() + target.redirects().flatMap { (from, to) -> listOf(from, to) }).distinct()

    /**
     * Brings every pointer file of [target] in line with the file it points at, and migrates targets
     * written by older versions: blocks that still sit in a pointer file are moved into the file it
     * points at, so the content lives in one place. The import itself exists exactly while there is
     * something to import — removing the last block also removes the pointer.
     */
    fun syncRedirects(target: Target) = mutate(target) {
        target.redirects().forEach { (from, to) -> syncRedirect(from, to) }
    }

    /**
     * The import is a plain `@AGENTS.md` line, not a managed region. A pointer file is one line long,
     * and a marker pair around it is three times the text it guards, saying nothing the line does not
     * already say — the line a person would have written is the whole file. Regions written by earlier
     * versions are converted here, so a project only carries one form of it.
     *
     * The cost of dropping ownership is that Ruleblend cannot tell its own line from a hand-written
     * one, so it removes the line in one case only: the pointer is nothing but that line and there is
     * nothing left to point at. A pointer beside hand-written text, or one whose target still holds
     * text of its own, is left alone.
     */
    private fun syncRedirect(from: Path, to: Path) {
        val stranded = regions(from).filter { it.id !in IMPORT_REGION_IDS }
        if (stranded.isNotEmpty()) {
            coordinator.rewrite(to, missing = "") { text -> stranded.fold(text) { acc, region -> upsert(acc, region) } }
            coordinator.rewrite(from) { text -> stranded.fold(text) { acc, region -> remove(acc, region.id) } }
        }

        val importLine = "@${to.fileName}"
        // Written for what Ruleblend installed, dropped only when nothing is left there at all: a
        // target that still holds the user's own text is worth importing even with no blocks in it.
        val hasBlocks = regions(to).isNotEmpty()
        val empty = !hasBlocks && unmanaged(to).text.isBlank()
        coordinator.rewrite(from, missing = "") { text ->
            // Managed and legacy import regions both go: the import is a plain line now, and a
            // leftover region beside it would import the target twice.
            val withoutManaged = IMPORT_REGION_IDS.fold(text) { acc, id -> remove(acc, id) }
            val hasImportLine = withoutManaged.lines().any { it.trim() == importLine }
            val updated = when {
                hasBlocks && !hasImportLine -> withImportLine(withoutManaged, importLine)
                empty && isOnly(withoutManaged, importLine) -> ""
                else -> withoutManaged
            }
            updated.takeIf { it != text }
        }
    }

    /** Appends the import as its own line, keeping whatever the file already holds. */
    private fun withImportLine(text: String, importLine: String): String = when {
        text.isBlank() -> "$importLine\n"
        text.endsWith("\n\n") -> "$text$importLine\n"
        text.endsWith("\n") -> "$text\n$importLine\n"
        else -> "$text\n\n$importLine\n"
    }

    /** True when [text] holds [line] and nothing else — the pointer file is only the pointer. */
    private fun isOnly(text: String, line: String): Boolean =
        text.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) } == listOf(line)

    /**
     * Status of [block] across every file of [target], or `null` when it is installed nowhere.
     * The worst status wins, so a hand edit in any one file is never hidden by the others.
     */
    fun status(target: Target, block: Block): InstallStatus? {
        val statuses = target.files().mapNotNull { file ->
            regions(file).find { it.id == block.id }?.let { statusOf(it, block) }
        }
        return when {
            statuses.isEmpty() -> null
            statuses.size < target.files().size -> InstallStatus.UPDATE_AVAILABLE
            else -> statuses.minByOrNull { it.severity() }
        }
    }

    /** Hand-written content of [file]; empty when the file does not exist. */
    fun unmanaged(file: Path): UnmanagedContent =
        FileReadScope.textOrNull(file)?.let { text -> encodingFor(text).unmanaged(text) } ?: UnmanagedContent("", 0)

    /**
     * Files of [target] that still hold hand-written content, in target file order. Pointer files are
     * scanned too: a project's hand-written `CLAUDE.md` is exactly what adoption exists for.
     *
     * A pointer file whose only hand-written line is the bare `@<target>` import is excluded: it is
     * already doing its job, there is nothing to adopt, and showing it would be noise. A pointer that
     * also carries other hand-written content is still offered — that content is genuinely adoptable.
     */
    fun unmanagedFiles(target: Target): List<Pair<Path, UnmanagedContent>> {
        val pointers = target.redirects().associate { it.from to it.to }
        return target.ownedFiles()
            .map { it to unmanaged(it) }
            .filter { (path, content) -> !content.isEmpty && !isPureImportPointer(content, pointers[path]) }
    }

    /** True when [content] is exactly the one-line `@<target>` import a pointer carries. */
    private fun isPureImportPointer(content: UnmanagedContent, target: Path?): Boolean =
        isPurePointer(content, target)

    /**
     * True when [content] is nothing but the bare `@<target>` import a pointer file carries — i.e. the
     * file is already doing its job. Exposed so the UI can mark such files instead of offering actions
     * that would undo the redirect.
     */
    fun isPurePointer(content: UnmanagedContent, target: Path?): Boolean {
        if (target == null) return false
        val importLine = "@${target.fileName}"
        val nonBlank = content.text.lines().mapNotNull { it.trim().takeIf(String::isNotBlank) }
        return nonBlank.size == 1 && nonBlank.single() == importLine
    }

    /**
     * Files reachable from [target]'s owned files by following `@import` directives transitively
     * (breadth-first). Imports are resolved relative to [target]'s root — the directory the agent
     * expands them against (Claude Code anchors every `@path` at the project root, regardless of the
     * file that mentions it); a target without a single root (an agent global target whose file sits
     * alone in its config dir) anchors imports at that file's parent. Only the hand-written
     * (unmanaged) text of each file is scanned, so imports inside installed ruleblend regions — which
     * belong to the library, not this project — are not followed. Directives whose target does not
     * exist or is not a regular file are skipped (they stay flagged by the adopt scan). Owned files
     * are seeded into the visited set and excluded from the result. Cycle-safe and deduped;
     * discovery order.
     */
    fun referencedImports(target: Target): List<Path> {
        val root = target.importRoot() ?: return emptyList()
        val seen = target.ownedFiles().toMutableSet()
        val result = mutableListOf<Path>()
        val queue = ArrayDeque(target.ownedFiles())
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!current.exists()) continue
            for (raw in findImports(unmanaged(current).text)) {
                val resolved = root.resolve(raw).normalize()
                if (!resolved.exists() || !Files.isRegularFile(resolved)) continue
                if (seen.add(resolved)) {
                    result.add(resolved)
                    queue.addLast(resolved)
                }
            }
        }
        return result
    }

    /**
     * Replaces the unmanaged content of [file] with a managed region for [block], in one atomic write.
     * The block must already be in the library: the text exists in git before it leaves the target file.
     */
    fun adopt(file: Path, block: Block, group: String? = null) = mutate(file) { text ->
        val plan = listOf(ManagedDocument.AdoptStep.Replace(encodingFor(text).unmanaged(text).text, regionFor(block, group)))
        val (source, encoding) = writableEncoding(text)
        encoding.adoptSections(source, plan)
    }

    /**
     * Multi-section variant of [adopt]: writes [plan] back into [file] in one atomic write. Each
     * `Replace` step becomes a managed region in place of its section; each `Keep` step stays
     * hand-written. Blocks referenced by `Replace` steps must already be in the library.
     */
    fun adoptSections(file: Path, plan: List<ManagedDocument.AdoptStep>) {
        if (plan.isEmpty()) return
        mutate(file) { text ->
            val (source, encoding) = writableEncoding(text)
            encoding.adoptSections(source, plan)
        }
    }

    /**
     * Advisory pre-save scan of adopted [text], anchored at [baseDir] (the source file's directory).
     * `@imports` are listed verbatim — only Claude Code expands them; other agents Ruleblend targets
     * treat them as plain text. Local markdown links/images that do not resolve against [baseDir]
     * are listed as broken references — a saved block installed elsewhere will not find them either.
     * Nothing is written; the caller surfaces the findings and lets the user save regardless.
     */
    fun scanAdopt(baseDir: Path, text: String): AdoptScanResult {
        val imports = findImports(text)
        val broken = findLocalLinkPaths(text).filter { !baseDir.resolve(it).exists() }
        return AdoptScanResult(imports, broken)
    }

    /** The installed region for [blockId] in [target], or `null` when it is installed nowhere. */
    fun regionOf(target: Target, blockId: String): ManagedRegion? = target.files()
        .firstNotNullOfOrNull { file -> regions(file).find { it.id == blockId } }

    /** Edited target body that can be safely attributed to one library rule. */
    fun localChange(target: Target, blockId: String): ManagedRegion {
        val resolver = blockResolver
            ?: throw WrappedRunDriftException("No block resolver: cannot save a target change to the library")
        val changes = target.files().mapNotNull { file ->
            val text = FileReadScope.textOrNull(file) ?: return@mapNotNull null
            val encoding = encodingFor(text)
            if (encoding.installed(text).none { it.id == blockId }) return@mapNotNull null
            encoding.localChange(text, blockId, resolver)
        }
        require(changes.isNotEmpty()) { "Rule $blockId is not installed in this target" }
        require(changes.map { it.content }.distinct().size == 1) {
            "Rule $blockId has different local changes in this target's files"
        }
        return changes.first()
    }

    /**
     * Re-checks [expected] and rewrites the managed run with [saved] after the library commit. This
     * closes the race where an external editor could save again between reading and legalizing the
     * change. The force path is safe here because [localChange] verified every neighbour.
     */
    fun acceptLocalChange(target: Target, saved: Block, expected: ManagedRegion): Unit = mutate(target) {
        val current = localChange(target, saved.id)
        require(current.content == expected.content) {
            "The target changed again before the local edit could be saved"
        }
        restoreLocalChange(target, saved).problem()?.let { throw it }
    }

    /** The group tag [block] carries in [target], if it was installed as part of one. */
    fun groupOf(target: Target, blockId: String): String? = regionOf(target, blockId)?.group
}

/**
 * One read of one instruction file. An absent file is a valid answer, not an error: it reports
 * [exists] `false` with the same neutral state a fresh file would have.
 */
data class FileScan(
    val file: Path,
    val exists: Boolean,
    val mode: TargetOwnershipMode = TargetOwnershipMode.NONE,
    val regions: List<ManagedRegion> = emptyList(),
    val unmanaged: UnmanagedContent = UnmanagedContent("", 0),
    val drift: Boolean = false,
    /**
     * Hand-edited blocks of this file, classified. Empty unless [IntegrationService.scanFile] was
     * given a library resolver: a scan that has no library in hand cannot tell an edit from an update.
     */
    val conflicts: List<BlockConflict> = emptyList(),
) {
    val regionsById: Map<String, ManagedRegion> get() = regions.associateBy { it.id }
}

/** Result of [IntegrationService.scanAdopt]: findings for the adopt dialog to surface. */
data class AdoptScanResult(val imports: List<String>, val brokenLinks: List<String>) {
    val isEmpty: Boolean get() = imports.isEmpty() && brokenLinks.isEmpty()
}

/**
 * Id of the import region pointer files carried before the import became a plain line. Still
 * reserved: no library block may use it, or its region would be read as a stale import and dropped.
 */
const val IMPORT_REGION_ID: String = "ruleblend-import"

/**
 * Import ids written by older versions, under the app's former name. They are still imports, not
 * rules: read as rules they are moved into the file they point at, which is how a file ends up
 * importing itself.
 */
val LEGACY_IMPORT_REGION_IDS: Set<String> = setOf("kitbash-import")

/** Every id a pointer file's import region has carried. */
val IMPORT_REGION_IDS: Set<String> = LEGACY_IMPORT_REGION_IDS + IMPORT_REGION_ID

/** Lower is worse; used to pick the status to show for a multi-file target. */
private fun InstallStatus.severity(): Int = when (this) {
    InstallStatus.MODIFIED -> 0
    InstallStatus.UPDATE_AVAILABLE -> 1
    InstallStatus.SYNCED -> 2
}
