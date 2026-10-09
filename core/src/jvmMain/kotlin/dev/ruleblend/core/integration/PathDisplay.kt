package dev.ruleblend.core.integration

import java.io.File
import java.nio.file.Path
import kotlin.io.path.pathString

/**
 * Formats a path for display by replacing the user's [home] prefix with `~`, using native
 * separators on every platform (for example, `~\\.codex` on Windows and `~/.codex` on macOS).
 *
 *     `/Users/example/.claude/CLAUDE.md` → `~/.claude/CLAUDE.md`
 *     `/Users/example/Developer/ruleblend`  → `~/Developer/ruleblend`
 *     `/etc/hosts`                    → `/etc/hosts`
 */
object PathDisplay {
    fun shorten(path: Path, home: Path = Path.of(System.getProperty("user.home"))): String {
        val absolute = path.toAbsolutePath().normalize()
        val normalizedHome = home.toAbsolutePath().normalize()
        if (absolute == normalizedHome) return "~"
        if (absolute.startsWith(normalizedHome)) {
            return "~${File.separator}${normalizedHome.relativize(absolute)}"
        }
        return absolute.pathString
    }
}
