package dev.ruleblend.mcp.install

import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.model.McpServerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class McpStatusTest {

    private val library = McpServerConfig.Stdio(command = "npx", args = listOf("-y", "ctx7"))
    private val edited = library.copy(command = "bunx")

    private fun record(config: McpServerConfig, version: Int) =
        McpInstallRecord("agent", "claude-code", "ctx7", version, config)

    @Test
    fun `absent entry has no status`() {
        assertEquals(FileStatus(null, conflict = false), fileStatus(null, null, library, 1))
        assertEquals(FileStatus(null, conflict = false), fileStatus(null, record(library, 1), library, 2))
    }

    @Test
    fun `entry matching library is synced only with a record`() {
        assertEquals(FileStatus(InstallStatus.MODIFIED, conflict = true), fileStatus(library, null, library, 1))
        assertEquals(
            FileStatus(InstallStatus.SYNCED, conflict = false),
            fileStatus(library, record(library, 1), library, 1),
        )
    }

    @Test
    fun `entry matching the recorded render while the library moved on is update available`() {
        assertEquals(
            FileStatus(InstallStatus.UPDATE_AVAILABLE, conflict = false),
            fileStatus(library, record(library, 1), edited, 2),
        )
    }

    @Test
    fun `entry differing from both renders is modified`() {
        val handEdited = library.copy(env = mapOf("KEY" to "user-value"))
        assertEquals(
            FileStatus(InstallStatus.MODIFIED, conflict = false),
            fileStatus(handEdited, record(library, 1), library, 1),
        )
    }

    @Test
    fun `unrecorded differing entry is a conflict`() {
        assertEquals(
            FileStatus(InstallStatus.MODIFIED, conflict = true),
            fileStatus(edited, null, library, 1),
        )
    }
}
