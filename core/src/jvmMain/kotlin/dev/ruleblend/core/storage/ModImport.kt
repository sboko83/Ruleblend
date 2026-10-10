package dev.ruleblend.core.storage

import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.ModSource
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.nextId
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.readBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A detached preview: accepted candidates carry the complete payload and its component inventory. */
data class ModImportCandidate(
    val path: String,
    val snapshot: ModSnapshot? = null,
    val error: String? = null,
) {
    val files: List<String> get() = snapshot?.files?.map { it.path }.orEmpty()
    val components: Set<String> get() {
        val accepted = snapshot ?: return emptySet()
        val manifest = Json.parseToJsonElement(accepted.files.single { it.path == ".claude-plugin/plugin.json" }.bytes.decodeToString()) as JsonObject
        val paths = files
        return buildSet {
            add("hooks")
            for ((key, conventional) in mapOf("skills" to "skills/", "agents" to "agents/", "commands" to "commands/",
                "mcpServers" to ".mcp.json", "lspServers" to ".lsp.json", "outputStyles" to "output-styles/", "workflows" to "workflows/")) {
                if (key in manifest || paths.any { it == conventional || it.startsWith(conventional) }) add(key)
            }
            (manifest["experimental"] as? JsonObject)?.keys?.let(::addAll)
        }
    }
}

/** Shared structural validator for Git, a local root and a selected external installation. */
object ModImport {
    fun preview(directory: Path): ModImportCandidate = try {
        ModImportCandidate(".", capture(directory))
    } catch (error: Exception) {
        ModImportCandidate(".", error = error.message ?: "Invalid mod package")
    }

    internal fun capture(directory: Path, source: ModSource? = null, executableFiles: Set<String>? = null): ModSnapshot {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) { "Mod root must be a real directory" }
        var total = 0L
        var entries = 0
        val files = mutableListOf<SkillFile>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            private fun checkEntry(path: Path) {
                require(++entries <= 20_000) { "Mod contains too many directory entries" }
                require(directory.relativize(path).count() <= 16) { "Mod path exceeds 16 components" }
                require(!Files.isSymbolicLink(path)) { "Mod contains a symbolic link: ${directory.relativize(path)}" }
                val parts = directory.relativize(path).map { it.toString() }
                require(parts.drop(1).none { it.equals(".git", ignoreCase = true) }) { "Nested Git repositories/submodules are unsupported" }
            }

            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkEntry(path)
                return if (directory.relativize(path).firstOrNull()?.toString() == ".git") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                checkEntry(path)
                if (directory.relativize(path).firstOrNull()?.toString() == ".git") return FileVisitResult.CONTINUE
                require(attributes.isRegularFile) { "Mod contains an unsupported file: $path" }
                require(files.size < SkillTreeLimits.MAX_FILES) { "Mod contains too many files" }
                val relative = directory.relativize(path).joinToString("/")
                val size = attributes.size()
                total += size
                require(size <= SkillTreeLimits.MAX_FILE_BYTES && total <= SkillTreeLimits.MAX_BYTES) { "Mod exceeds payload limits" }
                files += SkillFile(relative, path.readBytes(), executableFiles?.contains(relative) ?: skillExecutable(path))
                return FileVisitResult.CONTINUE
            }
        })
        SkillTreeLimits.validate(files)
        return validate(files.sortedBy { it.path }, source)
    }

    fun validate(files: List<SkillFile>, source: ModSource? = null): ModSnapshot {
        SkillTreeLimits.validate(files)
        val byPath = files.associateBy { it.path }
        fun objectAt(path: String): JsonObject {
            val file = requireNotNull(byPath[path]) { "Missing package file: $path" }
            require(file.bytes.size <= 1024 * 1024) { "Manifest/hooks file is too large: $path" }
            return Json.parseToJsonElement(file.bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
                ?: error("Expected a JSON object: $path")
        }
        val manifest = objectAt(".claude-plugin/plugin.json")
        fun text(key: String): String? = manifest[key]?.let {
            require(it is JsonPrimitive && it.isString) { "Expected a string for plugin $key" }
            it.content
        }
        val name = requireNotNull(text("name")) { "Plugin manifest has no name" }
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "Plugin name must use lowercase kebab-case" }
        require(manifest["dependencies"] == null || manifest["dependencies"] == JsonArray(emptyList())) {
            "Plugin dependencies are unsupported"
        }
        files.filter { it.path.substringAfterLast('/') == "package.json" }.forEach { file ->
            val pkg = objectAt(file.path)
            for (key in listOf("dependencies", "optionalDependencies", "peerDependencies", "scripts")) {
                require(pkg[key] == null || pkg[key] == JsonObject(emptyMap())) { "Packages requiring dependency installation or scripts are unsupported: ${file.path} ($key)" }
            }
        }
        fun resolve(base: String, raw: String): String {
            require(!raw.contains('\\') && !Path.of(raw).isAbsolute) { "Package path must be relative: $raw" }
            val resolved = Path.of(base).resolve(raw).normalize()
            val path = resolved.joinToString("/")
            require(path.isNotBlank() && !path.startsWith("../") && path != "..") { "Package path escapes plugin root: $raw" }
            dev.ruleblend.core.model.requirePortableFileTree(listOf(path))
            return path
        }
        fun componentPath(raw: JsonElement) {
            require(raw is JsonPrimitive && raw.isString) { "Expected a component path" }
            val path = raw.content
            require(path.startsWith("./") || path == ".") { "Component paths must be local and start with ./" }
            require(path.split('/').none { it == ".." }) { "Component path contains ..: $path" }
            if (path == "." || path == "./") return
            val resolved = resolve("", path)
            require(resolved in byPath || byPath.keys.any { it.startsWith("$resolved/") }) { "Missing component path: $resolved" }
        }
        fun component(value: JsonElement, inline: Boolean) {
            when (value) {
                is JsonPrimitive -> componentPath(value)
                is JsonArray -> value.forEach { component(it, inline) }
                is JsonObject -> require(inline) { "Expected a component path or path list" }
            }
        }
        for (key in listOf("skills", "agents", "outputStyles", "workflows", "types")) {
            manifest[key]?.let { component(it, false) }
        }
        for (key in listOf("mcpServers", "lspServers")) manifest[key]?.let { component(it, true) }
        manifest["commands"]?.let { value ->
            if (value is JsonObject) value.values.forEach { entry ->
                require(entry is JsonObject && (("source" in entry) xor ("content" in entry))) { "Commands require exactly one source or content" }
                entry["source"]?.let(::componentPath)
            } else component(value, false)
        }
        (manifest["experimental"] as? JsonObject)?.let { experimental ->
            experimental["themes"]?.let { component(it, false) }
            experimental["monitors"]?.let { component(it, true) }
        }
        val hooks = mutableSetOf<String>()
        if ("hooks/hooks.json" in byPath) hooks += "hooks/hooks.json"
        fun declared(value: JsonElement) {
            when (value) {
                is JsonPrimitive -> {
                    require(value.isString) { "Expected a hooks file path" }
                    componentPath(value)
                    hooks += resolve("", value.content)
                }
                is JsonArray -> value.forEach(::declared)
                is JsonObject -> require("modules" !in value) { "Inline hooks.modules is incompatible with Claude Code 2.1.287; use a hooks file" }
            }
        }
        manifest["hooks"]?.let(::declared)
        val modules = mutableListOf<String>()
        hooks.forEach { path ->
            val node = objectAt(path)
            node["modules"]?.let { value ->
                require(value is JsonArray && value.size == 1) { "hooks.modules must name exactly one hooks module: $path" }
                val module = value.single()
                require(module is JsonPrimitive && module.isString) { "Expected a module path: $path" }
                val resolved = resolve(path.substringBeforeLast('/', ""), module.content)
                require(resolved in byPath) { "Missing hooks module: $resolved" }
                require(resolved.substringAfterLast('.') in setOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")) { "Unsupported hooks module extension: $resolved" }
                require(byPath.getValue(resolved).bytes.isNotEmpty()) { "Empty hooks module: $resolved" }
                modules += resolved
            }
        }
        require(modules.size == 1) { "A mod requires exactly one hooks module" }
        val snapshot = ModSnapshot(Mod(nextId(name, emptyList()), name, name, text("version"), text("description").orEmpty(), source), files)
        validateModSnapshot(snapshot)
        return snapshot
    }
}

internal fun discoverGitMods(root: Path, paths: List<String>, repository: String, revision: String): List<ModImportCandidate> {
    val candidates = linkedMapOf<String, ModImportCandidate>()
    val pluginRoots = paths.filter { it == ".claude-plugin/plugin.json" || it.endsWith("/.claude-plugin/plugin.json") }
        .map { it.removeSuffix(".claude-plugin/plugin.json").removeSuffix("/").ifBlank { "." } }
    fun capture(path: String) {
        if (path in candidates) return
        candidates[path] = try {
            val directory = resolveGitImportPath(root, path)
            ModImportCandidate(path, ModImport.capture(directory, ModSource(repository, revision, path), gitSkillExecutableFiles(root, directory)))
        } catch (error: Exception) {
            ModImportCandidate(path, error = error.message ?: "Invalid mod package")
        }
    }
    pluginRoots.forEach(::capture)
    paths.filter { it == ".claude-plugin/marketplace.json" || it.endsWith("/.claude-plugin/marketplace.json") }.forEach { path ->
        try {
            val file = root.resolve(path)
            require(Files.size(file) <= 1024 * 1024) { "Marketplace manifest is too large" }
            val marketplace = Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: error("Expected a marketplace object")
            val entries = marketplace["plugins"] as? JsonArray ?: error("Marketplace has no plugins array")
            entries.forEachIndexed { index, element ->
                val key = "$path#$index"
                try {
                    val entry = element as? JsonObject ?: error("Expected a marketplace plugin object")
                    val source = entry["source"]
                    require(source is JsonPrimitive && source.isString && source.content.startsWith("./")) { "Remote or command marketplace entries are unsupported; no source was fetched" }
                    require(source.content.split('/').none { it == ".." } && !source.content.contains('\\')) { "Marketplace source path is not portable" }
                    val base = root.resolve(path).parent.parent
                    val directory = resolveGitImportPath(base, source.content)
                    val relative = root.relativize(directory).joinToString("/").ifBlank { "." }
                    capture(relative)
                } catch (error: Exception) {
                    candidates[key] = ModImportCandidate(key, error = error.message ?: "Unsupported marketplace entry")
                }
            }
        } catch (error: Exception) {
            candidates[path] = ModImportCandidate(path, error = error.message ?: "Invalid marketplace manifest")
        }
    }
    return candidates.values.toList()
}
