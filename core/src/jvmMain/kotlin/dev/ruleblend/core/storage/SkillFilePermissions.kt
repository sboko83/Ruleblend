package dev.ruleblend.core.storage

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

private val executePermissions = setOf(
    PosixFilePermission.OWNER_EXECUTE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/** Windows ACL execution access is not a portable file mode. Use recorded intent without POSIX. */
internal fun skillExecutable(path: Path, fallback: Boolean = false): Boolean {
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?: return fallback
    return view.readAttributes().permissions().any { it in executePermissions }
}

/** Apply portable intent where supported; permission failures on POSIX must abort the write. */
internal fun setSkillExecutable(path: Path, executable: Boolean) {
    val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) ?: return
    val permissions = view.readAttributes().permissions().toMutableSet()
    if (executable) permissions.addAll(executePermissions) else permissions.removeAll(executePermissions)
    view.setPermissions(permissions)
}
