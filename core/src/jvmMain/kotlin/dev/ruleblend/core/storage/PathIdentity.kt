package dev.ruleblend.core.storage

import java.nio.file.Path
import java.util.Locale

/** Resolves aliases through the nearest existing ancestor, including Windows junctions. */
fun Path.pathIdentity(): String = FileReadScope.cached(this to "path-identity") {
    val absolute = toAbsolutePath().normalize()
    var ancestor: Path? = absolute
    var resolved = absolute
    while (ancestor != null) {
        val real = runCatching { ancestor.toRealPath() }.getOrNull()
        if (real != null) {
            resolved = real.resolve(ancestor.relativize(absolute))
            break
        }
        ancestor = ancestor.parent
    }
    val text = resolved.toString()
    if (java.io.File.separatorChar == '\\') text.lowercase(Locale.ROOT) else text
}
