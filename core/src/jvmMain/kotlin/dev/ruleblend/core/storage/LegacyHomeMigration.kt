package dev.ruleblend.core.storage

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/** Result of copying a pre-rename Kitbash home into Ruleblend's default location. */
enum class LegacyHomeMigrationResult {
    SOURCE_MISSING,
    TARGET_EXISTS,
    MIGRATED,
}

/**
 * Preserves the pre-rename `~/.kitbash` state on the first Ruleblend launch.
 *
 * The old directory remains untouched so a failed or abandoned rename can still be rolled back.
 * A complete copy is assembled beside [ruleblendHome] and published with one atomic directory
 * rename, so Ruleblend never observes a partial library or config. Generated launchers and the old
 * process lock are deliberately not copied.
 */
object LegacyHomeMigration {

    private val volatilePaths = setOf(
        Path.of("kitbash.lock"),
        Path.of("bin", "kitbash-mcp"),
    )

    fun migrate(kitbashHome: Path, ruleblendHome: Path): LegacyHomeMigrationResult {
        if (ruleblendHome.exists()) return LegacyHomeMigrationResult.TARGET_EXISTS
        if (!kitbashHome.exists()) return LegacyHomeMigrationResult.SOURCE_MISSING
        require(kitbashHome.parent == ruleblendHome.parent) {
            "Legacy and current homes must share a parent for an atomic migration"
        }

        return InterProcessLock(kitbashHome.resolve("kitbash.lock")).withLock {
            when {
                ruleblendHome.exists() -> LegacyHomeMigrationResult.TARGET_EXISTS
                !kitbashHome.exists() -> LegacyHomeMigrationResult.SOURCE_MISSING
                else -> {
                    copyAtomically(kitbashHome, ruleblendHome)
                    LegacyHomeMigrationResult.MIGRATED
                }
            }
        }
    }

    private fun copyAtomically(source: Path, target: Path) {
        val staging = Files.createTempDirectory(target.parent, ".${target.fileName}-migration-")
        try {
            Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = source.relativize(dir)
                    if (relative in volatilePaths) return FileVisitResult.SKIP_SUBTREE
                    if (relative.toString().isNotEmpty()) Files.createDirectory(staging.resolve(relative))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = source.relativize(file)
                    if (relative !in volatilePaths) {
                        Files.copy(
                            file,
                            staging.resolve(relative),
                            COPY_ATTRIBUTES,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS,
                        )
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            AtomicMove.move(staging, target)
        } catch (failure: Throwable) {
            deleteRecursively(staging)
            throw failure
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                file.deleteIfExists()
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                dir.deleteIfExists()
                return FileVisitResult.CONTINUE
            }
        })
    }
}
