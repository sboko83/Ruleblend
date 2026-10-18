package dev.ruleblend.core.integration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiPackagesTest {
    private lateinit var home: Path

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-pi-packages")
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
    }

    private fun settings(json: String): Path =
        home.resolve(".pi/agent").createDirectories().resolve("settings.json").apply { writeText(json) }

    @Test
    fun `every source form resolves to the package's own name`() {
        listOf(
            "npm:pi-subagents",
            "npm:pi-subagents@1.4.0",
            "npm:@tintinweb/pi-subagents",
            "npm:@tintinweb/pi-subagents@0.9.1",
            "git:github.com/nicobailon/pi-subagents@v1",
            "https://github.com/nicobailon/pi-subagents.git",
            "git@github.com:nicobailon/pi-subagents",
            "./pi-subagents/",
            "C:\\packages\\pi-subagents",
        ).forEach { assertEquals("pi-subagents", PiPackages.packageName(it), it) }
        assertEquals("pi-subagents-extra", PiPackages.packageName("npm:pi-subagents-extra"))
    }

    @Test
    fun `a string or object declaration counts as installed`() {
        assertTrue(PiPackages.declares(settings("""{"packages": ["npm:@tintinweb/pi-subagents"]}"""), "pi-subagents"))
        assertTrue(
            PiPackages.declares(
                settings("""{"packages": [{"source": "npm:pi-subagents", "skills": []}]}"""),
                "pi-subagents",
            ),
        )
    }

    @Test
    fun `missing, broken, unrelated or extension-less declarations do not count`() {
        assertFalse(PiPackages.declares(home.resolve(".pi/agent/settings.json"), "pi-subagents"))
        assertFalse(PiPackages.declares(settings("{ not json"), "pi-subagents"))
        assertFalse(PiPackages.declares(settings("""{"theme": "dark"}"""), "pi-subagents"))
        assertFalse(PiPackages.declares(settings("""{"packages": ["npm:pi-subagents-extra"]}"""), "pi-subagents"))
        assertFalse(
            PiPackages.declares(
                settings("""{"packages": [{"source": "npm:pi-subagents", "extensions": []}]}"""),
                "pi-subagents",
            ),
        )
    }

    @Test
    fun `the Pi adapter reads its own settings file`() {
        val pi = PiAdapter(home)
        assertFalse(pi.hasSubagentExtension())
        settings("""{"packages": ["npm:pi-subagents"]}""")
        assertTrue(pi.hasSubagentExtension())
        assertTrue(ClaudeCodeAdapter(home).hasSubagentExtension())
    }
}
