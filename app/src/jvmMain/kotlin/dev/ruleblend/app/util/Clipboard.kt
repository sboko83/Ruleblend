package dev.ruleblend.app.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Puts [text] on the system clipboard. Goes through AWT directly — the same surface the app already
 * uses for pickers — so it works the same in a window and in a headless test, where it is a no-op.
 */
object ClipboardEnvironment {
    @Volatile
    var copy: (String) -> Boolean = { text ->
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }.isSuccess
    }
}

fun copyToClipboard(text: String): Boolean = ClipboardEnvironment.copy(text)
