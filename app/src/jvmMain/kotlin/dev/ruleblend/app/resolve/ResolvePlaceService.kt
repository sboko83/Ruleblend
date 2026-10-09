package dev.ruleblend.app.resolve

import dev.ruleblend.app.place.configuredTargets
import dev.ruleblend.app.place.placeId
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.withRegionContent
import dev.ruleblend.core.storage.LibraryRepository

/**
 * Applies conflict strategies through core's guarded restoration and acceptance paths. Only
 * existing copies are rewritten, and neighbours are checked again before a run is rebuilt.
 *
 * A place that refuses its write costs the batch that place and nothing more. Unwinding the places
 * that already succeeded would need a third kind of write — one that puts a hand edit back — and that
 * text no longer exists anywhere once its file has been rewritten.
 */
class ResolvePlaceService(
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val service: IntegrationService,
    private val repository: LibraryRepository,
) : ResolveApplier {

    override fun apply(plan: ResolvePlan): ResolveReport {
        val targets = configuredTargets(configStore.load(), agents).all.associateBy { placeId(it) }
        var restored = 0
        var saved = 0
        val failed = mutableListOf<String>()
        plan.places.forEach { place ->
            val target = targets[place.placeId]
            if (target == null) {
                failed += place.placeName
                return@forEach
            }
            runCatching { applyPlace(target, place) }.fold(
                onSuccess = { done ->
                    restored += done.first
                    saved += done.second
                },
                onFailure = { failed += place.placeName },
            )
        }
        return ResolveReport(restored = restored, saved = saved, failed = failed, skipped = plan.skipped)
    }

    /**
     * One place, in the only order that keeps every answer of it intact.
     *
     * A forced write rebuilds the whole managed run from current library bodies, so an edit that has
     * not reached the library yet is gone the moment any neighbour is written. Every edit to be kept is
     * therefore read and committed as a library version first, and only then is anything written to
     * disk: after that, rebuilding the run reproduces the kept edits and drops exactly the ones the
     * plan asked to restore.
     *
     * Anything unexpected — a rule that left the library, an edit core refuses to attribute, a file
     * that changed again meanwhile — throws, and the caller reports the place as failed. Library
     * versions committed before a later target failure remain available.
     */
    private fun applyPlace(target: Target, place: ResolvePlaceStep): Pair<Int, Int> {
        val library = repository.listBlocks().associateBy { it.id }
        val saves = place.steps.filter { it.strategy == ResolveStrategy.SAVE_AS_VERSION }
        val restores = place.steps.filter { it.strategy == ResolveStrategy.RESTORE }
        val edits = saves.map { step ->
            val block = library[step.blockId] ?: error("Rule ${step.blockId} is no longer in the library")
            block to service.localChange(target, step.blockId)
        }
        val committed = edits.map { (block, local) ->
            repository.saveBlock(block.withRegionContent(local.content)) to local
        }
        // Re-checks the file and rewrites the run; the check is what closes the race with an editor
        // saving again between the read above and this write.
        committed.forEach { (block, local) -> service.acceptLocalChange(target, block, local) }
        restores.forEach { step ->
            val block = repository.loadBlock(step.blockId) ?: error("Rule ${step.blockId} is no longer in the library")
            service.restoreLocalChange(target, block)
                .problem()?.let { throw it }
        }
        return restores.size to saves.size
    }
}
