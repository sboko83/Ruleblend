package dev.ruleblend.core.exchange

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportMergeTest {

    private fun block(id: String, version: Int = 1, content: String = "text") =
        Block(id = id, name = id, version = version, content = content)

    private fun planOf(incoming: List<Block>, local: List<Block>) =
        ImportMerge.plan(incoming, emptyList(), local, emptyList())

    private fun skill(content: String = "# Review") = SkillSnapshot(
        Skill("review", "Review", content = content),
        listOf(SkillFile("SKILL.md", content.encodeToByteArray())),
    )

    @Test
    fun blockMissingLocallyIsNew() {
        val plan = planOf(listOf(block("a")), emptyList())

        assertEquals(ChangeKind.NEW, plan.blocks.single().kind)
    }

    @Test
    fun identicalBlockIsSame() {
        val plan = planOf(listOf(block("a")), listOf(block("a")))

        assertEquals(ChangeKind.SAME, plan.blocks.single().kind)
    }

    @Test
    fun newerVersionIsUpdate() {
        val plan = planOf(listOf(block("a", version = 3, content = "new")), listOf(block("a", version = 2)))

        assertEquals(ChangeKind.UPDATE, plan.blocks.single().kind)
    }

    @Test
    fun olderVersionIsConflict() {
        val plan = planOf(listOf(block("a", version = 1, content = "old")), listOf(block("a", version = 5)))

        assertEquals(ChangeKind.CONFLICT, plan.blocks.single().kind)
    }

    @Test
    fun sameVersionWithDifferentContentIsConflict() {
        val plan = planOf(listOf(block("a", version = 2, content = "theirs")), listOf(block("a", version = 2, content = "ours")))

        assertEquals(ChangeKind.CONFLICT, plan.blocks.single().kind)
    }

    @Test
    fun sameVersionWithDifferentNameIsConflict() {
        val incoming = block("a", version = 2).copy(name = "Renamed")
        val plan = planOf(listOf(incoming), listOf(block("a", version = 2)))

        assertEquals(ChangeKind.CONFLICT, plan.blocks.single().kind)
    }

    @Test
    fun changeCarriesBothSides() {
        val incoming = block("a", version = 3, content = "new")
        val local = block("a", version = 2)
        val change = planOf(listOf(incoming), listOf(local)).blocks.single()

        assertEquals(incoming, change.incoming)
        assertEquals(local, change.local)
    }

    @Test
    fun groupMissingLocallyIsNew() {
        val group = Group(id = "g", name = "G", blockIds = listOf("a"))
        val plan = ImportMerge.plan(emptyList(), listOf(group), emptyList(), emptyList())

        assertEquals(ChangeKind.NEW, plan.groups.single().kind)
    }

    @Test
    fun identicalGroupIsSame() {
        val group = Group(id = "g", name = "G", blockIds = listOf("a"))
        val plan = ImportMerge.plan(emptyList(), listOf(group), emptyList(), listOf(group))

        assertEquals(ChangeKind.SAME, plan.groups.single().kind)
    }

    @Test
    fun groupWithDifferentMembershipIsConflict() {
        val incoming = Group(id = "g", name = "G", blockIds = listOf("a", "b"))
        val local = Group(id = "g", name = "G", blockIds = listOf("a"))
        val plan = ImportMerge.plan(emptyList(), listOf(incoming), emptyList(), listOf(local))

        assertEquals(ChangeKind.CONFLICT, plan.groups.single().kind)
    }

    @Test
    fun groupWithAHigherArchiveVersionIsAnUpdate() {
        val incoming = Group(id = "g", name = "G", blockIds = listOf("a", "b"), version = 10)
        val local = Group(id = "g", name = "G", blockIds = listOf("a"), version = 3)
        val plan = ImportMerge.plan(emptyList(), listOf(incoming), emptyList(), listOf(local))

        assertEquals(ChangeKind.UPDATE, plan.groups.single().kind)
    }

    @Test
    fun groupWithAnOlderArchiveVersionIsAConflict() {
        val incoming = Group(id = "g", name = "G", blockIds = listOf("a"), version = 2)
        val local = Group(id = "g", name = "G", blockIds = listOf("a", "b"), version = 6)
        val plan = ImportMerge.plan(emptyList(), listOf(incoming), emptyList(), listOf(local))

        assertEquals(ChangeKind.CONFLICT, plan.groups.single().kind)
    }

    @Test
    fun groupOfTheSameVersionWithOtherMembersIsAConflict() {
        // Same revision number, different members: neither side is behind, so only the user decides.
        val incoming = Group(id = "g", name = "G", blockIds = listOf("a", "b"), version = 4)
        val local = Group(id = "g", name = "G", blockIds = listOf("c"), version = 4)
        val plan = ImportMerge.plan(emptyList(), listOf(incoming), emptyList(), listOf(local))

        assertEquals(ChangeKind.CONFLICT, plan.groups.single().kind)
    }

    @Test
    fun sameItemsAreHiddenFromTheActionableList() {
        val plan = planOf(listOf(block("a"), block("b", version = 2, content = "new")), listOf(block("a"), block("b")))

        assertEquals(listOf("b"), plan.actionableBlocks.map { it.incoming.id })
        assertFalse(plan.isEmpty)
    }

    @Test
    fun archiveMatchingTheLibraryIsAnEmptyPlan() {
        val group = Group(id = "g", name = "G", blockIds = listOf("a"))
        val plan = ImportMerge.plan(listOf(block("a")), listOf(group), listOf(block("a")), listOf(group))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun skillSnapshotsAreNewSameOrConflictWithoutOrderingOpaqueVersions() {
        val incoming = skill()
        val newPlan = ImportMerge.plan(emptyList(), emptyList(), emptyList(), emptyList(), listOf(incoming), emptyList())
        val samePlan = ImportMerge.plan(emptyList(), emptyList(), emptyList(), emptyList(), listOf(incoming), listOf(incoming))
        val conflictPlan = ImportMerge.plan(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(incoming),
            listOf(skill("# Locally changed")),
        )

        assertEquals(ChangeKind.NEW, newPlan.skills.single().kind)
        assertEquals(ChangeKind.SAME, samePlan.skills.single().kind)
        assertEquals(ChangeKind.CONFLICT, conflictPlan.skills.single().kind)
    }

    @Test
    fun profilesAreNewSameUpdateOrConflictByVersion() {
        val incoming = Profile(id = "review", name = "Review", blockIds = listOf("new"), version = 2)
        val same = ImportMerge.plan(
            emptyList(), emptyList(), emptyList(), emptyList(),
            incomingProfiles = listOf(incoming),
            localProfiles = listOf(incoming),
        )
        val update = ImportMerge.plan(
            emptyList(), emptyList(), emptyList(), emptyList(),
            incomingProfiles = listOf(incoming),
            localProfiles = listOf(incoming.copy(blockIds = listOf("old"), version = 1)),
        )
        val conflict = ImportMerge.plan(
            emptyList(), emptyList(), emptyList(), emptyList(),
            incomingProfiles = listOf(incoming),
            localProfiles = listOf(incoming.copy(blockIds = listOf("other"))),
        )
        val new = ImportMerge.plan(
            emptyList(), emptyList(), emptyList(), emptyList(),
            incomingProfiles = listOf(incoming),
        )

        assertEquals(ChangeKind.SAME, same.profiles.single().kind)
        assertEquals(ChangeKind.UPDATE, update.profiles.single().kind)
        assertEquals(ChangeKind.CONFLICT, conflict.profiles.single().kind)
        assertEquals(ChangeKind.NEW, new.profiles.single().kind)
    }
}
