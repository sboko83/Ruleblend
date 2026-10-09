package dev.ruleblend.app.util

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class HomePathTest {
    @Test
    fun `assistant path shown by the UI keeps native home abbreviation`() {
        val home = Path.of(System.getProperty("user.home"))
        assertEquals("~${File.separator}.codex", home.resolve(".codex").abbreviateHome(home))
    }

    @Test
    fun `normalized home does not duplicate the address prefix`() {
        val home = Path.of(System.getProperty("user.home"))
        assertEquals("~${File.separator}.claude",
            home.resolve(".claude").abbreviateHome(home.resolve("unused/..")))
    }
}
