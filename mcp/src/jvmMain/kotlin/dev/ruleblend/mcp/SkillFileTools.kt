package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.requirePortableFileTree
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.SkillTreeLimits
import dev.ruleblend.core.usecase.MutateLibrary
import java.nio.charset.CharacterCodingException
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded file operations over complete portable library trees; no caller-supplied disk paths. */
internal class SkillFileTools(private val repository: LibraryRepository, config: ConfigStore) {
    private val mutations = MutateLibrary(repository, config)

    fun list(args: JsonObject): JsonElement = operation {
        val snapshot = snapshot(args)
        buildJsonObject {
            put("id", snapshot.skill.id)
            put("version", snapshot.skill.version)
            put("editable", snapshot.skill.source == null)
            put("files", buildJsonArray {
                snapshot.files.sortedBy { it.path }.forEach { file -> add(buildJsonObject {
                    put("path", file.path)
                    put("size_bytes", file.bytes.size)
                    put("executable", file.executable)
                }) }
            })
        }
    }

    fun read(args: JsonObject): JsonElement = operation {
        val path = args.path()
        val encoding = args.encoding()
        val snapshot = snapshot(args)
        val file = snapshot.files.find { it.path == path } ?: throw ToolError("No skill file '$path'")
        val content = when (encoding) {
            "base64" -> Base64.getEncoder().encodeToString(file.bytes)
            else -> try {
                file.bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw ToolError("Skill file '$path' is not UTF-8; read it with encoding: base64")
            }
        }
        buildJsonObject {
            put("id", snapshot.skill.id)
            put("version", snapshot.skill.version)
            put("path", path)
            put("size_bytes", file.bytes.size)
            put("executable", file.executable)
            put("encoding", encoding)
            put("content", content)
        }
    }

    fun write(args: JsonObject): JsonElement = operation {
        val id = args.text("id")
        val path = args.path()
        val encoding = args.encoding()
        val content = args.text("content")
        // Bound the encoded input before allocating its decoded byte array.
        val maxEncoded = if (encoding == "base64") ((SkillTreeLimits.MAX_FILE_BYTES + 2) / 3) * 4
            else SkillTreeLimits.MAX_FILE_BYTES
        require(content.length <= maxEncoded) { "Skill file exceeds ${SkillTreeLimits.MAX_FILE_BYTES} bytes" }
        val bytes = if (encoding == "base64") Base64.getDecoder().decode(content)
            else content.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= SkillTreeLimits.MAX_FILE_BYTES) { "Skill file exceeds ${SkillTreeLimits.MAX_FILE_BYTES} bytes" }
        val executable = args["executable"]?.let { value ->
            (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: throw ToolError("'executable' must be a boolean")
        }
        mutations.writeSkillFile(id, path, bytes, executable).value.result(path)
    }

    fun delete(args: JsonObject): JsonElement = operation {
        val path = args.path()
        mutations.deleteSkillFile(args.text("id"), path).value.result(path)
    }

    private fun snapshot(args: JsonObject): SkillSnapshot = repository.withLock {
        val id = args.text("id")
        repository.loadSkillSnapshot(id) ?: throw ToolError("No skill with id '$id'")
    }

    private fun Skill.result(path: String): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
        put("path", path)
    }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolError("'$key' must be a string")

private fun JsonObject.path(): String = text("path").also { requirePortableFileTree(listOf(it)) }

private fun JsonObject.encoding(): String = (if ("encoding" in this) text("encoding") else "utf8").also {
    require(it == "utf8" || it == "base64") { "Encoding must be utf8 or base64" }
}

private inline fun <T> operation(action: () -> T): T = try {
    action()
} catch (e: ToolError) {
    throw e
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
} catch (e: Exception) {
    throw ToolError(e.message ?: "Skill file operation failed; read the library before retrying")
}
