package dev.ruleblend.app.settings

import dev.ruleblend.core.integration.AgentAdapter
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsAgentCatalogTest {
    private class Agent(override val id: String, override val name: String = id) : AgentAdapter {
        override fun isAvailable() = error("Catalog rendering must use the loaded detection snapshot")
        override fun globalFile(): Path = Path.of("/unused")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    }

    @Test fun `detection groups are stable and hidden agents remain in the catalog`() {
        val agents = listOf(Agent("absent-a"), Agent("found-b"), Agent("absent-c"), Agent("found-d"))
        val result = agentCatalogPage(agents, setOf("found-b", "found-d"), emptySet(), "", AgentCatalogFilter.ALL, 1)
        assertEquals(listOf("found-b", "found-d", "absent-a", "absent-c"), result.agents.map { it.id })
    }

    @Test fun `page boundaries clamp after the result set shrinks`() {
        val agents = (1..20).map { Agent("$it") }
        val last = agentCatalogPage(agents, emptySet(), emptySet(), "", AgentCatalogFilter.ALL, 99)
        assertEquals(listOf("17", "18", "19", "20"), last.agents.map { it.id })
        assertEquals(3, last.page)
        assertEquals(17, last.first)
        assertEquals(20, last.last)
        val shrunk = agentCatalogPage(agents, emptySet(), emptySet(), "20", AgentCatalogFilter.ALL, 3)
        assertEquals(1, shrunk.page)
        assertEquals(1, shrunk.total)
    }

    @Test fun `attention excludes absent assistants and search ignores case and outer spaces`() {
        val agents = listOf(Agent("a", "Claude Code"), Agent("b", "Codex"), Agent("c", "Another Claude"))
        val result = agentCatalogPage(agents, setOf("a", "b"), setOf("a", "c"), " CLAUDE ", AgentCatalogFilter.ATTENTION, 1)
        assertEquals(listOf("a"), result.agents.map { it.id })
        val detected = agentCatalogPage(agents, setOf("a", "b"), emptySet(), "", AgentCatalogFilter.DETECTED, 1)
        assertEquals(listOf("a", "b"), detected.agents.map { it.id })
    }

    @Test fun `an empty result keeps a valid page and zero range`() {
        val result = agentCatalogPage(emptyList(), emptySet(), emptySet(), "missing", AgentCatalogFilter.ALL, -1)
        assertEquals(AgentCatalogPage(emptyList(), 0, 1, 1, 0, 0), result)
    }
}
