package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetStatusTest {

    private fun region(id: String, group: String?) =
        ManagedRegion(id = id, version = 1, group = group, hash = hashContent("x"), content = "x")

    private fun group(id: String, vararg blockIds: String) =
        Group(id = id, name = id, blockIds = blockIds.toList())

    @Test
    fun `worst is synced when nothing is installed`() {
        assertEquals(InstallStatus.SYNCED, TargetStatus.worst(emptyList()))
    }

    @Test
    fun `worst prefers modified over update available`() {
        val statuses = listOf(InstallStatus.SYNCED, InstallStatus.UPDATE_AVAILABLE, InstallStatus.MODIFIED)
        assertEquals(InstallStatus.MODIFIED, TargetStatus.worst(statuses))
    }

    @Test
    fun `worst prefers update available over synced`() {
        val statuses = listOf(InstallStatus.SYNCED, InstallStatus.UPDATE_AVAILABLE)
        assertEquals(InstallStatus.UPDATE_AVAILABLE, TargetStatus.worst(statuses))
    }

    @Test
    fun `group is installed when every block carries its tag`() {
        val installed = mapOf("a" to region("a", "g"), "b" to region("b", "g"))
        assertTrue(TargetStatus.isGroupInstalled(group("g", "a", "b"), installed))
    }

    @Test
    fun `group is not installed when a block is missing`() {
        val installed = mapOf("a" to region("a", "g"))
        assertFalse(TargetStatus.isGroupInstalled(group("g", "a", "b"), installed))
    }

    @Test
    fun `group is not installed when a block carries another tag`() {
        val installed = mapOf("a" to region("a", "g"), "b" to region("b", "other"))
        assertFalse(TargetStatus.isGroupInstalled(group("g", "a", "b"), installed))
    }

    @Test
    fun `empty group is never installed`() {
        assertFalse(TargetStatus.isGroupInstalled(group("g"), emptyMap()))
    }

    @Test
    fun `removing a group keeps blocks installed on their own`() {
        val installed = mapOf(
            "a" to region("a", "g"),
            "b" to region("b", null),
            "c" to region("c", "other"),
        )
        assertEquals(listOf("a"), TargetStatus.groupBlocksToRemove(group("g", "a", "b", "c"), installed))
    }

    @Test
    fun `removing a group ignores blocks that are not installed`() {
        assertEquals(emptyList(), TargetStatus.groupBlocksToRemove(group("g", "a"), emptyMap()))
    }
}
