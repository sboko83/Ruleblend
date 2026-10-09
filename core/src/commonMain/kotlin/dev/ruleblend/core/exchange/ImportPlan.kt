package dev.ruleblend.core.exchange

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.SkillSnapshot

/** What importing an item would do to the library. */
enum class ChangeKind {
    /** No item with this id locally. */
    NEW,

    /** Local item exists and the archive carries a newer version. */
    UPDATE,

    /** Local item is identical; importing changes nothing. */
    SAME,

    /** Contents differ but the archive is not newer — only the user can decide. */
    CONFLICT,
}

/** One block the archive offers, paired with the local block it would land on. */
data class BlockChange(val incoming: Block, val local: Block?, val kind: ChangeKind)

/** One group the archive offers, paired with the local group it would land on. */
data class GroupChange(val incoming: Group, val local: Group?, val kind: ChangeKind)

/** One profile the archive offers, paired with the local profile it would land on. */
data class ProfileChange(val incoming: Profile, val local: Profile?, val kind: ChangeKind)

/** One complete skill directory from an archive. Differing snapshots are conflicts: versions are opaque strings. */
data class SkillChange(val incoming: SkillSnapshot, val local: SkillSnapshot?, val kind: ChangeKind)

/** Everything an archive would change. Nothing is written until the user picks. */
data class ImportPlan(
    val blocks: List<BlockChange>,
    val groups: List<GroupChange>,
    val skills: List<SkillChange> = emptyList(),
    val profiles: List<ProfileChange> = emptyList(),
) {

    /** Items worth showing: [ChangeKind.SAME] entries are noise — importing them is a no-op. */
    val actionableBlocks: List<BlockChange> get() = blocks.filter { it.kind != ChangeKind.SAME }
    val actionableGroups: List<GroupChange> get() = groups.filter { it.kind != ChangeKind.SAME }
    val actionableSkills: List<SkillChange> get() = skills.filter { it.kind != ChangeKind.SAME }
    val actionableProfiles: List<ProfileChange> get() = profiles.filter { it.kind != ChangeKind.SAME }

    val isEmpty: Boolean get() =
        actionableBlocks.isEmpty() && actionableGroups.isEmpty() && actionableSkills.isEmpty() && actionableProfiles.isEmpty()
}

/** Compares an archive against the current library. Pure: no I/O, no writes. */
object ImportMerge {

    fun plan(
        incomingBlocks: List<Block>,
        incomingGroups: List<Group>,
        localBlocks: List<Block>,
        localGroups: List<Group>,
        incomingSkills: List<SkillSnapshot> = emptyList(),
        localSkills: List<SkillSnapshot> = emptyList(),
        incomingProfiles: List<Profile> = emptyList(),
        localProfiles: List<Profile> = emptyList(),
    ): ImportPlan {
        val localBlocksById = localBlocks.associateBy { it.id }
        val localGroupsById = localGroups.associateBy { it.id }
        return ImportPlan(
            blocks = incomingBlocks.map { incoming ->
                BlockChange(incoming, localBlocksById[incoming.id], blockKind(incoming, localBlocksById[incoming.id]))
            },
            groups = incomingGroups.map { incoming ->
                GroupChange(incoming, localGroupsById[incoming.id], groupKind(incoming, localGroupsById[incoming.id]))
            },
            skills = incomingSkills.map { incoming ->
                val local = localSkills.find { it.skill.id == incoming.skill.id }
                SkillChange(incoming, local, itemKind(incoming, local))
            },
            profiles = incomingProfiles.map { incoming ->
                val local = localProfiles.find { it.id == incoming.id }
                ProfileChange(incoming, local, profileKind(incoming, local))
            },
        )
    }

    private fun blockKind(incoming: Block, local: Block?): ChangeKind = when {
        local == null -> ChangeKind.NEW
        local == incoming -> ChangeKind.SAME
        incoming.version > local.version -> ChangeKind.UPDATE
        else -> ChangeKind.CONFLICT
    }

    /**
      * A group is versioned library content like a block: an archive that carries a newer version of
      * a group the library already holds is an update, and anything else that differs is a decision
      * for the user. Membership alone cannot decide it — two libraries can hold the same group with
      * different members and neither is behind the other.
      */
    private fun groupKind(incoming: Group, local: Group?): ChangeKind = when {
        local == null -> ChangeKind.NEW
        local == incoming -> ChangeKind.SAME
        incoming.version > local.version -> ChangeKind.UPDATE
        else -> ChangeKind.CONFLICT
    }

    private fun itemKind(incoming: SkillSnapshot, local: SkillSnapshot?): ChangeKind = when {
        local == null -> ChangeKind.NEW
        local == incoming -> ChangeKind.SAME
        else -> ChangeKind.CONFLICT
    }

    private fun profileKind(incoming: Profile, local: Profile?): ChangeKind = when {
        local == null -> ChangeKind.NEW
        local == incoming -> ChangeKind.SAME
        incoming.version > local.version -> ChangeKind.UPDATE
        else -> ChangeKind.CONFLICT
    }
}
