package dev.ruleblend.core.integration

import java.nio.file.Path

/** Native assistant homes shared by detection, rules, MCP and bundled skill installation. */
class AssistantPaths(
    val userHome: Path,
    private val environment: Map<String, String> = emptyMap(),
) {
    private val rejected = mutableListOf<String>()

    /**
     * Overrides that do not name a native directory. Each is ignored rather than failing the whole
     * set: one stray variable must not keep Ruleblend or its MCP server from starting.
     */
    val rejectedOverrides: List<String> get() = rejected

    val claude: Path = override("CLAUDE_CONFIG_DIR") ?: userHome.resolve(".claude")
    val claudeConfig: Path = override("CLAUDE_CONFIG_DIR", report = false)?.resolve(".claude.json")
        ?: userHome.resolve(".claude.json")
    val codex: Path = override("CODEX_HOME") ?: userHome.resolve(".codex")
    val pi: Path = override("PI_CODING_AGENT_DIR") ?: userHome.resolve(".pi").resolve("agent")
    val kimi: Path = override("KIMI_CODE_HOME") ?: userHome.resolve(".kimi-code")

    private fun override(name: String, report: Boolean = true): Path? {
        val value = environment[name]?.takeIf { it.isNotBlank() } ?: return null
        val expanded = when {
            value == "~" -> userHome
            value.startsWith("~/") || value.startsWith("~\\") -> userHome.resolve(value.substring(2))
            else -> runCatching { Path.of(value) }.getOrNull()
        }
        val native = expanded?.toString()?.replace('\\', '/')?.lowercase().orEmpty()
        // A Linux home inherited from WSL must not become C:\home or a relative Windows path.
        val problem = when {
            expanded == null || !expanded.isAbsolute -> "must name an absolute native path"
            native.startsWith("//wsl$/") || native.startsWith("//wsl.localhost/") -> "points to WSL"
            else -> return expanded.normalize()
        }
        if (report) {
            val message = "$name $problem and is ignored: $value"
            rejected += message
            // Every adapter resolves its own paths; stderr keeps MCP stdout clean and says it once.
            if (reported.add(message)) System.err.println("Ruleblend: $message")
        }
        return null
    }

    companion object {
        private val reported: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** An explicit home is isolated from the process environment, including in tests. */
        fun forHome(home: Path? = null): AssistantPaths = if (home != null) AssistantPaths(home)
        else AssistantPaths(Path.of(System.getProperty("user.home")), System.getenv())
    }
}
