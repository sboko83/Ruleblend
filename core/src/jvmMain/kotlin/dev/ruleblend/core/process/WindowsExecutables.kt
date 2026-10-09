package dev.ruleblend.core.process

import java.nio.file.Files
import java.nio.file.Path

/** Native Windows lookup also works when the desktop app has no login shell. */
class WindowsExecutables(
    private val environment: Map<String, String> = System.getenv(),
    private val home: Path = Path.of(System.getProperty("user.home")),
    private val isFile: (Path) -> Boolean = Files::isRegularFile,
) {
    private fun env(name: String): String? = environment.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun find(name: String): Path? {
        require(name.isNotBlank() && name.none { it == '\u0000' || it == '\r' || it == '\n' })
        val supported = setOf(".exe", ".com", ".cmd", ".bat")
        val extensions = (env("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD").split(';')
            .map { it.trim().lowercase() }.filter { it in supported }
        val names = if (supported.any { name.endsWith(it, ignoreCase = true) }) listOf(name)
            else extensions.map { name + it }
        val supplied = Path.of(name)
        if (supplied.isAbsolute) return names.asSequence().map(Path::of).firstOrNull(isFile)
        if (supplied.parent != null) return null
        val directories = buildList {
            // Never search the current project or relative PATH entries for executables.
            env("PATH")?.split(';')?.map { it.trim().trim('"') }?.filter(String::isNotBlank)
                ?.forEach { entry -> runCatching { Path.of(entry) }.getOrNull()?.let(::add) }
            add(home.resolve(".local/bin"))
            add(home.resolve("scoop/shims"))
            env("APPDATA")?.let { add(Path.of(it, "npm")) }
            env("LOCALAPPDATA")?.let {
                add(Path.of(it, "Microsoft/WindowsApps"))
                if (name.equals("git.exe", true) || name.equals("git", true)) add(Path.of(it, "Programs/Git/cmd"))
            }
            if (name.equals("git.exe", true) || name.equals("git", true)) {
                listOf("ProgramW6432", "ProgramFiles", "ProgramFiles(x86)").forEach { variable ->
                    env(variable)?.let { add(Path.of(it, "Git/cmd")) }
                }
            }
        }.filter(Path::isAbsolute).distinct()
        return directories.asSequence().flatMap { dir -> names.asSequence().map(dir::resolve) }
            .firstOrNull(isFile)?.toAbsolutePath()?.normalize()
    }

    fun systemFile(relative: String): Path = Path.of(env("SystemRoot") ?: "C:\\Windows", "System32", relative)
}
