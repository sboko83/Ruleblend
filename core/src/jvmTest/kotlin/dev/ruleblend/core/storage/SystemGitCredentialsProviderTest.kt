package dev.ruleblend.core.storage

import java.io.IOException
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.Base64
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish

class SystemGitCredentialsProviderTest {
    private val uri = URIish("https://alice@git.example.com:8443/project/alice/library-backup.git")

    @Test
    fun `missing Git does not fall back to an executable in the current project`() {
        val provider = SystemGitCredentialsProvider(executable = null) { error("Unexpected process") }
        assertFalse(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
    }

    @Test
    fun `real Git exchanges UTF8 credentials through isolated helper configuration`() {
        val root = Files.createTempDirectory("rb-git-credentials")
        try {
            val log = root.resolve("actions")
            val provider = SystemGitCredentialsProvider { builder ->
                builder.directory(root.toFile())
                builder.environment().putAll(mapOf(
                    "GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to root.resolve("absent").toString(),
                    "GIT_CONFIG_COUNT" to "1", "GIT_CONFIG_KEY_0" to "credential.helper",
                    "GIT_CONFIG_VALUE_0" to """!f() { printf '%s\n' "${'$'}1" >> "${'$'}RB_GIT_HELPER_LOG"; if [ "${'$'}1" = get ]; then printf '%s\n' 'username=fixture' 'password=пароль'; fi; }; f""",
                    "RB_GIT_HELPER_LOG" to log.toString(),
                ))
                builder.start()
            }
            withGitCredentials(provider) { credentials ->
                val username = CredentialItem.Username()
                val password = CredentialItem.Password()
                assertTrue(credentials.get(URIish("https://example.invalid/private.git"), username, password))
                assertEquals("fixture", username.value)
                assertEquals("пароль", String(password.value))
            }
            assertEquals(listOf("get", "store"), Files.readAllLines(log))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `sign-in can take longer than the old five second limit`() {
        val helper = Helper("delayed")
        val username = CredentialItem.Username()
        val password = CredentialItem.Password()

        assertTrue(helper.provider.get(uri, username, password))
        assertEquals("alice", username.value)
        assertEquals("пароль=with=equals", String(password.value))
        assertEquals(1, helper.calls.size)
    }

    @Test
    fun `accepted credentials are approved with the description returned by Git`() {
        val helper = Helper()
        withGitCredentials(helper.provider) { provider ->
            assertTrue(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
            // More than one HTTP request must not reopen the dialog in one operation.
            assertTrue(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
        }
        assertEquals(listOf("fill", "approve"), helper.calls)
        helper.assertProcessesSucceeded()
    }

    @Test
    fun `authentication retry rejects the old secret and asks again`() {
        val helper = Helper()
        withGitCredentials(helper.provider) { provider ->
            assertTrue(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
            provider.reset(uri)
            assertTrue(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
        }
        assertEquals(listOf("fill", "reject", "fill", "approve"), helper.calls)
        helper.assertProcessesSucceeded()
    }

    @Test
    fun `failed operation does not approve or retain the pending secret`() {
        val helper = Helper()
        assertFailsWith<IOException> {
            withGitCredentials(helper.provider) { provider ->
                assertTrue(provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
                throw IOException("network unavailable")
            }
        }
        assertEquals(listOf("fill"), helper.calls)
        assertTrue(helper.provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
        assertEquals(listOf("fill", "fill"), helper.calls)
    }

    @Test
    fun `cancelled sign-in does not supply or approve credentials`() {
        val helper = Helper("cancel")
        val password = CredentialItem.Password()
        assertFalse(helper.provider.get(uri, CredentialItem.Username(), password))
        helper.provider.approve()
        assertEquals(listOf("fill"), helper.calls)
        assertEquals(null, password.value)
    }

    @Test
    fun `helper stdout is drained while the process is running`() {
        val helper = Helper("large")
        assertTrue(helper.provider.get(uri, CredentialItem.Username(), CredentialItem.Password()))
        helper.assertProcessesSucceeded()
    }

    @Test
    fun `expired sign-in stops the process and reports a timeout`() {
        val helper = Helper("delayed", timeoutMillis = 300)
        val error = assertFailsWith<IOException> {
            helper.provider.get(uri, CredentialItem.Username(), CredentialItem.Password())
        }
        assertContains(error.message.orEmpty(), "timed out")
        assertTrue(helper.processes.single().waitFor(5, TimeUnit.SECONDS))
        assertFalse(helper.processes.single().isAlive)
    }

    @Test
    fun `non HTTPS requests and unsupported items never invoke Git`() {
        val helper = Helper()
        assertFalse(helper.provider.get(URIish("http://example.com/repo"), CredentialItem.Password()))
        assertFalse(helper.provider.get(uri, CredentialItem.YesNoType("Trust?")))
        assertTrue(helper.calls.isEmpty())
    }

    private class Helper(scenario: String = "success", timeoutMillis: Long = 120_000) {
        val calls = mutableListOf<String>()
        val processes = mutableListOf<Process>()
        val provider = SystemGitCredentialsProvider(promptTimeoutMillis = timeoutMillis) { builder ->
            val command = builder.command()
            val action = if ("-EncodedCommand" in command) {
                val script = String(Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)
                listOf("fill", "approve", "reject").single { "\"$it\"" in script }
            } else command.last()
            calls += action
            assertEquals("0", builder.environment()["GIT_TERMINAL_PROMPT"])
            // A real child JVM exercises Windows pipes, UTF-8 and process termination without
            // touching the user's credential store or needing their private repository password.
            val java = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            builder.command(java.toString(), "-cp", System.getProperty("java.class.path"),
                CredentialHelperFixture::class.java.name, action, scenario)
            builder.start().also(processes::add)
        }

        fun assertProcessesSucceeded() {
            processes.forEach { process ->
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
            }
        }
    }
}

/** Subprocess fixture; exits unsuccessfully if the credential protocol is wrong. */
object CredentialHelperFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = System.`in`.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.takeWhile(String::isNotEmpty).map { line ->
                val separator = line.indexOf('=')
                line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        }
        check(input["protocol"] == "https")
        check(input["host"] == "git.example.com:8443")
        check(input["username"] == "alice")
        if (args[0] != "fill") {
            // Git strips path by default; approval/rejection must use its returned context.
            check("path" !in input)
            check(input["password"] == "пароль=with=equals")
            return
        }
        check(input["path"] == "project/alice/library-backup.git")
        when (args[1]) {
            "delayed" -> Thread.sleep(5_200)
            "cancel" -> exitProcess(1)
        }
        System.out.writer(Charsets.UTF_8).use { output ->
            if (args[1] == "large") repeat(3_000) { output.appendLine("extra${it}=${"x".repeat(100)}") }
            output.appendLine("protocol=https")
            output.appendLine("host=git.example.com:8443")
            output.appendLine("username=alice")
            output.appendLine("password=пароль=with=equals")
            output.appendLine()
        }
    }
}
