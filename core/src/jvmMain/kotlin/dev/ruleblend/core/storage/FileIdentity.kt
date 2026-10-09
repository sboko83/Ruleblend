package dev.ruleblend.core.storage

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * What a file looked like when Ruleblend read it. Compared against the file again right before the
 * replacing rename, it separates the file Ruleblend read from one an external editor rewrote in the
 * meantime — the loss the process lock cannot prevent, because that editor never takes it.
 *
 * Content decides, not the timestamp: a same-size edit slips past a coarse filesystem mtime, while
 * `touch` alone reports a change that is not one. Size only spares the hash when it already differs.
 */
data class FileIdentity(val size: Long, val digest: String) {

    companion object {

        /** Identity of [path] as it is now, or null when the file does not exist. */
        fun of(path: Path): FileIdentity? = if (path.exists()) of(path.readBytes()) else null

        /** Identity of bytes already in hand, so a file is never read twice to describe it. */
        fun of(bytes: ByteArray): FileIdentity = FileIdentity(
            size = bytes.size.toLong(),
            digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }
}
