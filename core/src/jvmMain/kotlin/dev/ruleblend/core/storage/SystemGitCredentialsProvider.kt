package dev.ruleblend.core.storage

import java.io.IOException
import dev.ruleblend.core.process.WindowsExecutables
import dev.ruleblend.core.process.WindowsCommand
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import org.eclipse.jgit.errors.UnsupportedCredentialItem
import org.eclipse.jgit.transport.ChainingCredentialsProvider
import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.NetRCCredentialsProvider
import org.eclipse.jgit.transport.URIish

/**
 * Bridges JGit to the user's standard Git credential helpers (macOS Keychain, Git Credential
 * Manager, libsecret, and others). Secrets are passed through the credential protocol over pipes;
 * Ruleblend never persists or logs them.
 */
internal class SystemGitCredentialsProvider(
    private val executable: String? = defaultGitExecutable(),
    private val promptTimeoutMillis: Long = 120_000,
    private val startProcess: (ProcessBuilder) -> Process = { it.start() },
) : CredentialsProvider() {

    private val pending = mutableMapOf<URIish, Map<String, String>>()

    override fun isInteractive(): Boolean = false

    override fun supports(vararg items: CredentialItem): Boolean = items.all {
        it is CredentialItem.Username ||
            it is CredentialItem.Password ||
            (it is CredentialItem.StringType && it.promptText == "Password: ")
    }

    override fun get(uri: URIish, vararg items: CredentialItem): Boolean {
        if (!supports(*items) || uri.scheme != "https") return false
        val credentials = pending[uri] ?: credentialCommand("fill", description(uri), promptTimeoutMillis)
            ?: return false
        val username = credentials["username"] ?: return false
        val password = credentials["password"] ?: return false
        pending[uri] = credentials
        items.forEach { item ->
            when (item) {
                is CredentialItem.Username -> item.value = username
                is CredentialItem.Password -> item.value = password.toCharArray()
                is CredentialItem.StringType -> item.value = password
                else -> throw UnsupportedCredentialItem(uri, item.promptText)
            }
        }
        return true
    }

    override fun reset(uri: URIish) {
        val credentials = pending.remove(uri) ?: return
        runCatching { credentialCommand("reject", credentials, 5_000) }
    }

    fun approve() {
        pending.values.forEach { credentials ->
            runCatching { credentialCommand("approve", credentials, 5_000) }
        }
    }

    fun clear() = pending.clear()

    private fun description(uri: URIish): Map<String, String> = buildMap {
        put("protocol", "https")
        put("host", uri.host + if (uri.port > 0) ":${uri.port}" else "")
        uri.path?.trimStart('/')?.takeIf { it.isNotEmpty() }?.let { put("path", it) }
        uri.user?.takeIf { it.isNotEmpty() }?.let { put("username", it) }
    }

    private fun credentialCommand(
        action: String,
        description: Map<String, String>,
        timeoutMillis: Long,
    ): Map<String, String>? {
        require(description.all { (key, value) ->
            key.none { it == '\n' || it == '\r' || it == '\u0000' || it == '=' } &&
                value.none { it == '\n' || it == '\r' || it == '\u0000' }
        }) { "Invalid Git credential description" }
        val process = runCatching {
            val command = listOf(executable ?: return null, "credential", action)
            val nativeCommand = if (System.getProperty("os.name").startsWith("Windows")) {
                WindowsCommand.powershell(WindowsCommand.script(command))
            } else command
            startProcess(ProcessBuilder(nativeCommand)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" })
        }.getOrNull() ?: return null
        // Drain while waiting: a helper must not block on a full stdout pipe. UTF-8 is also
        // independent of the Windows system code page (including non-ASCII passwords).
        val output = FutureTask {
            process.inputReader(Charsets.UTF_8).useLines { lines ->
                lines.takeWhile { it.isNotEmpty() }.mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
                }.toMap()
            }
        }
        thread(name = "ruleblend-git-credentials", isDaemon = true) { output.run() }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            process.outputWriter(Charsets.UTF_8).use { input ->
                description.forEach { (key, value) -> input.appendLine("$key=$value") }
                input.appendLine()
            }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw TimeoutException()
            if (process.exitValue() != 0) return null
            return output.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            throw IOException("Git credential helper timed out. Complete sign-in within two minutes and retry sync.")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Git sign-in was interrupted", interrupted)
        } finally {
            // Killing only `git credential` leaves its helper and Windows login dialog orphaned.
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
            output.cancel(true)
        }
    }

    companion object {
        private fun defaultGitExecutable(): String? {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return WindowsExecutables().find("git.exe")?.toString()
            }
            val macOsGit = Path.of("/usr/bin/git")
            return if (Files.isExecutable(macOsGit)) macOsGit.toString() else "git"
        }
    }
}

/** Git stores a new secret only after the remote operation accepted it. */
internal fun <T> withGitCredentials(
    system: SystemGitCredentialsProvider = SystemGitCredentialsProvider(),
    operation: (CredentialsProvider) -> T,
): T {
    // JGit's chain does not forward reset, so explicitly propagate an authentication rejection.
    val provider = object : ChainingCredentialsProvider(system, NetRCCredentialsProvider()) {
        override fun reset(uri: URIish) = system.reset(uri)
    }
    try {
        return operation(provider).also { system.approve() }
    } finally {
        system.clear()
    }
}
