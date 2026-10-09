package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableNamesTest {
    @Test
    fun rejectsWindowsDevicesAndInvalidComponents() {
        listOf("CON", "con.txt", "Aux", "NUL.md", "PRN", "COM1", "LPT9.log", "COM¹", "LPT².txt", "COM0", "lpt0.md", "CONIN$", "conout$.txt",
            "tail.", "tail ", "a:b", "a?b", "a*b", "a\u0000b", "a/b", "a\\b", ".", "..", "")
            .forEach { assertFalse(isPortableFileName(it), it) }
        listOf("console", "com10", ".hidden", "SKILL.md", "С пробелом.txt", "a..b")
            .forEach { assertTrue(isPortableFileName(it), it) }
    }

    @Test
    fun rejectsCaseAliasesAndFileDirectoryCollisions() {
        listOf(
            listOf("SKILL.md", "skill.md"),
            listOf("Scripts/a.sh", "scripts/b.sh"),
            listOf("scripts", "scripts/a.sh"),
            listOf("scripts/a.sh", "scripts"),
            listOf("a", "a"),
            listOf("a//b"), listOf("../b"), listOf("a/CON.txt"), listOf("a\\b"),
        ).forEach { paths -> assertFailsWith<IllegalArgumentException>(paths.toString()) { requirePortableFileTree(paths) } }
        requirePortableFileTree(listOf("SKILL.md", "scripts/a.sh", "scripts/b.sh", "Документы/Текст.txt"))
    }

    @Test
    fun generatedIdsAvoidDevicesAndCaseCollisions() {
        assertEquals("con-2", nextId("CON", emptyList()))
        assertEquals("review-3", nextId("Review", listOf("REVIEW", "Review-2")))
        assertEquals("untitled", nextId("", emptyList()))
        assertFailsWith<IllegalArgumentException> { uniqueId("bad/path", emptyList()) }
    }
}
