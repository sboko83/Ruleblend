package dev.ruleblend.app.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class StringsTest {
    @Test fun `coverage counters distinguish assistants and projects and omit empty status parts`() {
        assertEquals("128 объектов · 3 AI-ассистента · 25 проектов", RuStrings.coverageCorner(128, 3, 25))
        assertEquals("4 конфликта · 1 AI-ассистент · 2 проекта", RuStrings.coverageChipConflicts(4, 1, 2))
        assertEquals("7 обновлений · 5 проектов", RuStrings.coverageChipUpdates(7, 0, 5))
        assertEquals("1 conflict · 1 AI assistant", EnStrings.coverageChipConflicts(1, 1, 0))
        assertEquals("2 updates · 2 targets", EnStrings.coverageChipUpdates(2, 0, 2))
        assertEquals("21 AI-ассистент", RuStrings.coverageColumnAgents(21))
        assertEquals("11 проектов", RuStrings.coverageColumnProjects(11))
        assertEquals("синхронно: 2, обновление: 1", RuStrings.coverageCellSplit(2, 1, 0))
        assertEquals("modified 3", EnStrings.coverageCellSplit(0, 0, 3))
        assertEquals("", EnStrings.coverageCellSplit(0, 0, 0))
        assertEquals("Развернуть «Правила»", RuStrings.coverageExpandRow("Правила"))
        assertEquals("Collapse \"Codex\"", EnStrings.coverageCollapseColumn("Codex"))
    }

    @Test fun `standalone agent labels identify AI assistants`() {
        assertEquals("AI assistants", EnStrings.intAgents)
        assertEquals("AI assistants", EnStrings.coverageAgentsPanel)
        assertEquals("AI assistants", EnStrings.setSectionAgents)
        assertEquals("AI assistant", EnStrings.setColAgent)

        assertEquals("AI-ассистенты", RuStrings.intAgents)
        assertEquals("AI-ассистенты", RuStrings.coverageAgentsPanel)
        assertEquals("AI-ассистенты", RuStrings.setSectionAgents)
        assertEquals("AI-ассистент", RuStrings.setColAgent)
    }
}
