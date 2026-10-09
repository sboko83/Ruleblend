package dev.ruleblend.e2e

import dev.ruleblend.core.storage.AtomicWrite
import java.nio.file.Path
import java.nio.file.Files
import java.security.Permission

/**
 * R-02 fixture process: pauses the production writer after creating its temporary file.
 * The parent kills this JVM before replacement; the previous complete version must survive.
 */
object InterruptedWriter {
    fun version(tag: Char): String =
        "---\nname: Interrupted\ndescription: ''\nversion: 1\ntype: rule\nfavorite: false\nheading: ''\n" +
            "headingLevel: 2\n---\n" + tag.toString().repeat(16 shl 20) + "\n"

    @JvmStatic
    @Suppress("DEPRECATION") // The opt-in E2E fixtures run on JDK 17.
    fun main(args: Array<String>) {
        val target = Path.of(args.single())
        AtomicWrite.write(target, version('a'))
        System.setSecurityManager(object : SecurityManager() {
            override fun checkPermission(permission: Permission) = Unit
            override fun checkWrite(file: String) {
                if (file.endsWith(".tmp") && Files.exists(Path.of(file))) {
                    println("inside-write")
                    System.out.flush()
                    System.`in`.read()
                }
            }
        })
        AtomicWrite.write(target, version('b'))
    }
}
