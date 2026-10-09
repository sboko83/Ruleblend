package dev.ruleblend.e2e

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Holds the same native file lock as the app until the runner closes stdin. */
object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Path.of(args.single()), CREATE, WRITE).use { channel ->
            channel.lock().use {
                println("ready")
                System.out.flush()
                while (System.`in`.read() != -1) { /* Wait for EOF. */ }
            }
        }
    }
}
