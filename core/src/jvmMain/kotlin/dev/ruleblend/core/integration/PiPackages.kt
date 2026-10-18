package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.FileReadScope
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The packages a Pi `settings.json` declares. Pi names an npm package by its package name, a git or
 * URL package by its repository and a local one by its path; every form ends in the package's own
 * name, so a declaration is matched by that last segment with any version or ref dropped. That also
 * lets a scoped fork (`@tintinweb/pi-subagents`) count as the package it replaces.
 */
internal object PiPackages {

    /** Whether [settings] declares [name] with its extensions loaded. A missing or broken file declares nothing. */
    fun declares(settings: Path, name: String): Boolean {
        val text = FileReadScope.textOrNull(settings) ?: return false
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return false
        val packages = root["packages"] as? JsonArray ?: return false
        return packages.any { entry ->
            val source = when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                // `"extensions": []` keeps the package but loads none of its extensions.
                is JsonObject -> (entry["source"] as? JsonPrimitive)?.contentOrNull
                    ?.takeUnless { (entry["extensions"] as? JsonArray)?.isEmpty() == true }
                else -> null
            }
            source != null && packageName(source) == name
        }
    }

    /** `npm:@scope/pi-x@1.0.0`, `git:github.com/o/pi-x@v1`, `https://github.com/o/pi-x.git`, `./pi-x` → `pi-x`. */
    fun packageName(source: String): String = source.trimEnd('/', '\\')
        .substringAfterLast('/').substringAfterLast('\\').substringAfterLast(':')
        .substringBefore('@').removeSuffix(".git")
}
