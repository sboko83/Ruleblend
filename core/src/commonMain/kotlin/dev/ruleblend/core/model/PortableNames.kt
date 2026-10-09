package dev.ruleblend.core.model

private val WINDOWS_DEVICE = Regex("(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[0-9¹²³]|lpt[0-9¹²³])(?:\\..*)?")

/** A component that can be stored unchanged on every supported filesystem. */
fun isPortableFileName(name: String): Boolean =
    name.isNotEmpty() && name != "." && name != ".." &&
        !name.endsWith('.') && !name.endsWith(' ') &&
        name.none { it.code < 32 || it in "<>:\"/\\|?*" } && !WINDOWS_DEVICE.matches(name)

/** Validates the full tree before a caller writes its first file. */
fun requirePortableFileTree(paths: List<String>) {
    val spellings = mutableMapOf<String, String>()
    val files = mutableSetOf<String>()
    val directories = mutableSetOf<String>()
    paths.forEach { path ->
        val parts = path.split('/')
        require(parts.all(::isPortableFileName)) { "Non-portable file path: $path" }
        parts.indices.forEach { index ->
            val prefix = parts.take(index + 1).joinToString("/")
            val key = prefix.lowercase()
            val previous = spellings.putIfAbsent(key, prefix)
            require(previous == null || previous == prefix) { "Case collision: $previous and $prefix" }
            if (index == parts.lastIndex) {
                require(key !in directories && files.add(key)) { "Conflicting file path: $path" }
            } else {
                require(key !in files) { "File is also a directory: $prefix" }
                directories.add(key)
            }
        }
    }
}
