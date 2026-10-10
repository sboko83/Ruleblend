package dev.ruleblend.core.storage

import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.requirePortableFileTree
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fingerprint v1 sorts unsigned UTF-8 path bytes and frames every variable-length value. */
fun modTreeFingerprint(files: List<SkillFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedWith { left, right ->
        java.util.Arrays.compareUnsigned(left.path.encodeToByteArray(), right.path.encodeToByteArray())
    }.forEach { file ->
        val path = file.path.encodeToByteArray()
        digest.update(ByteBuffer.allocate(8).putLong(path.size.toLong()).array())
        digest.update(path)
        digest.update(byteArrayOf(if (file.executable) 1 else 0))
        digest.update(ByteBuffer.allocate(8).putLong(file.bytes.size.toLong()).array())
        digest.update(file.bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Storage validation only; discovery and compatibility checks belong to the source importer. */
internal fun validateModSnapshot(snapshot: ModSnapshot) {
    checkedLibraryId(snapshot.mod.id)
    SkillTreeLimits.validate(snapshot.files)
    require(snapshot.files.all { it.path.split('/').size <= 16 }) { "Mod path exceeds 16 components" }
    snapshot.mod.source?.let { source ->
        require(source.repository.isNotBlank() && source.revision.matches(Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})"))) {
            "Mod source requires a repository and exact commit revision"
        }
        if (source.path != ".") requirePortableFileTree(listOf(source.path))
    }
    val manifest = requireNotNull(snapshot.files.singleOrNull { it.path == ".claude-plugin/plugin.json" }) {
        "Mod '${snapshot.mod.id}' has no .claude-plugin/plugin.json"
    }
    val json = Json.parseToJsonElement(manifest.bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
    val name = json["name"]?.jsonPrimitive
    val version = json["version"]?.jsonPrimitive
    require(name?.isString == true && name.content == snapshot.mod.manifestName && snapshot.mod.manifestName.isNotBlank()) {
        "Mod manifest name does not match metadata"
    }
    require((version == null || version.isString) && version?.content == snapshot.mod.version) {
        "Mod manifest version does not match metadata"
    }
}
