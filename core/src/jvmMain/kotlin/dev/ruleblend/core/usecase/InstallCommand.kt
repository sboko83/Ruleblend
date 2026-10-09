package dev.ruleblend.core.usecase

import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Skill

/** One resolved write. Groups are expanded before a command is built. */
sealed interface InstallItem {
    /** Library id of the object; the same id its region, entry or skill directory is keyed by. */
    val id: String

    /** [group] identifies provenance in the rule marker or another object's sidecar. */
    data class Rule(val block: Block, val group: String? = null) : InstallItem {
        override val id: String get() = block.id
    }

    data class Mcp(val block: Block, val group: String? = null) : InstallItem {
        override val id: String get() = block.id
    }

    /** A standalone definition rendered by each supported assistant's native subagent format. */
    data class Subagent(val block: Block, val group: String? = null) : InstallItem {
        override val id: String get() = block.id
    }

    data class SkillCopy(val skill: Skill, val group: String? = null) : InstallItem {
        override val id: String get() = skill.id
    }
}

/** Whether a command puts objects into targets or takes them back out. */
enum class WriteMode { INSTALL, REMOVE }

/**
 * Why one object was not written into one target. Reported rather than thrown: silently doing less
 * than asked is the failure mode that makes bulk operations untrustworthy.
 */
enum class SkipReason {
    /** A project-scoped rule offered to a target it does not belong to. */
    OUT_OF_SCOPE,

    /** No agent of that target installs this kind of object. */
    UNSUPPORTED,

    /** Installed and edited by hand there: overwriting needs an explicit decision on that copy. */
    MODIFIED,
}

/** What happened to one object in one target. */
sealed interface WriteOutcome {
    data object Written : WriteOutcome

    data class Skipped(val reason: SkipReason) : WriteOutcome

    data class Failed(val error: Throwable) : WriteOutcome
}

/** One cell of the command's target × object matrix. */
data class WriteEntry(val target: Target, val item: InstallItem, val outcome: WriteOutcome)

/**
 * Typed result of one install or removal. Every pair the command covered is here with its outcome,
 * so a facade can render counts, per-id lists or a per-file breakdown from the same value instead of
 * each keeping its own tally.
 */
data class WriteReport(val entries: List<WriteEntry> = emptyList()) {

    val written: Int get() = entries.count { it.outcome is WriteOutcome.Written }

    val failed: Int get() = entries.count { it.outcome is WriteOutcome.Failed }

    /** How many objects were refused, by reason; a reason that did not occur is absent, not zero. */
    val skipped: Map<SkipReason, Int>
        get() = entries.mapNotNull { (it.outcome as? WriteOutcome.Skipped)?.reason }
            .groupingBy { it }
            .eachCount()

    val skippedTotal: Int get() = entries.count { it.outcome is WriteOutcome.Skipped }

    /** Ids that were written, in command order and each listed once however many targets took it. */
    fun writtenIds(): List<String> = idsOf { it is WriteOutcome.Written }

    /** Ids refused for [reason], each listed once. */
    fun skippedIds(reason: SkipReason): List<String> =
        idsOf { it is WriteOutcome.Skipped && it.reason == reason }

    /** The first error the command hit, or null when nothing failed. */
    fun firstError(): Throwable? = entries.firstNotNullOfOrNull { (it.outcome as? WriteOutcome.Failed)?.error }

    private fun idsOf(match: (WriteOutcome) -> Boolean): List<String> =
        entries.filter { match(it.outcome) }.map { it.item.id }.distinct()
}

/**
 * One install or removal of [items] across [targets].
 *
 * [force] is the caller saying it has already decided about the local copy: a hand-edited object is
 * written over instead of refused. It never widens scope — a project-scoped rule stays out of a
 * target it does not belong to however the command was built.
 */
data class InstallCommand(
    val targets: List<Target>,
    val items: List<InstallItem>,
    val mode: WriteMode = WriteMode.INSTALL,
    val force: Boolean = false,
)
