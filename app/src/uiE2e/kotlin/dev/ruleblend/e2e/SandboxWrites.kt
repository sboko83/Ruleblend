@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package dev.ruleblend.e2e

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.Permission

/** JVM 17 worker only; production never installs a security manager. Child execution is opt-in. */
internal class SandboxWrites(private val root: Path, private vararg val allowedExec: Path) : SecurityManager() {
    override fun checkPermission(perm: Permission) = Unit
    override fun checkWrite(file: String) = requireInside(root, Path.of(file))
    // Deleting a link removes the link itself; checking its parent keeps escapes blocked.
    override fun checkDelete(file: String) = requireInside(root, requireNotNull(Path.of(file).parent))
    override fun checkExec(cmd: String) {
        if (Path.of(cmd) in allowedExec) return
        // Files.isExecutable delegates its permission check here without starting a process.
        if (Thread.currentThread().stackTrace.any {
                it.className == "java.nio.file.Files" && it.methodName == "isExecutable"
            }) {
            return
        }
        throw SecurityException("Unexpected E2E process: $cmd")
    }
    override fun checkConnect(host: String, port: Int): Unit = throw SecurityException("Unexpected E2E network: $host:$port")

    companion object {
        fun requireInside(root: Path, path: Path) {
            val boundary = root.toRealPath()
            val absolute = path.toAbsolutePath().normalize()
            require(absolute.startsWith(boundary)) { "Outside E2E root: $path" }
            // Resolve every existing ancestor, including dangling links (which fail toRealPath).
            var ancestor = absolute
            while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
            require(ancestor.toRealPath().startsWith(boundary)) { "Symlink escapes E2E root: $path" }
        }
    }
}
