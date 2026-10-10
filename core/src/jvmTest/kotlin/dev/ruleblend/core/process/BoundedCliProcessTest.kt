package dev.ruleblend.core.process

import dev.ruleblend.core.integration.ModRuntimeError
import dev.ruleblend.core.integration.ModRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedCliProcessTest {
    private fun error(expected: ModRuntimeError, process: BoundedCliProcess, arguments: List<String>) {
        assertEquals(expected, assertFailsWith<ModRuntimeException> { process.run(arguments) }.error)
    }

    @Test fun `argv remains literal through the fixed positional shell program`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val payload = "spaces; $(exit 99) `exit 99` 'quote' \"double\" — Юникод"
        val result = BoundedCliProcess(listOf("/bin/sh", "-c", "exec \"${'$'}@\"", "sh", "/usr/bin/printf"))
            .run(listOf("%s", payload))
        assertEquals(0, result.exitCode); assertEquals(payload, result.stdout)
    }

    @Test fun `timeout does not block on unread stdout`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        val started = System.nanoTime()
        error(ModRuntimeError.TIMEOUT, BoundedCliProcess(listOf("/bin/sh"), timeoutMs = 100), listOf("-c", "sleep 10"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3000)
    }

    @Test fun `stderr is bounded independently and drains without deadlock`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        error(ModRuntimeError.OUTPUT_LIMIT, BoundedCliProcess(listOf("/bin/sh"), maxOutputBytes = 512),
            listOf("-c", "while :; do printf 'noisy stderr output\\n' >&2; done"))
    }

    @Test fun `cancellation prevents process launch`() {
        error(ModRuntimeError.CANCELLED, BoundedCliProcess(listOf("nonexistent"), cancelled = { true }), emptyList())
    }

    @Test fun `cancellation stops running process`() {
        if (System.getProperty("os.name").startsWith("Windows")) return
        var polls = 0
        error(ModRuntimeError.CANCELLED, BoundedCliProcess(listOf("/bin/sh"), cancelled = { ++polls > 3 }),
            listOf("-c", "sleep 10"))
    }

    @Test fun `missing executable reports typed error`() {
        error(ModRuntimeError.CLI_UNAVAILABLE, BoundedCliProcess(listOf("/definitely/nonexistent/cli")), emptyList())
    }
}
