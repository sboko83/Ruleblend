package dev.ruleblend.core.storage

import dev.ruleblend.core.model.SkillFile
import java.security.MessageDigest

/** Stable digest of a complete skill tree, including paths, bytes and executable bits. */
fun skillTreeFingerprint(files: List<SkillFile>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.path }.forEach { file ->
        digest.update(file.path.encodeToByteArray())
        digest.update(byteArrayOf(0))
        digest.update(byteArrayOf(if (file.executable) 1 else 0))
        digest.update(file.bytes)
        digest.update(byteArrayOf(0))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
