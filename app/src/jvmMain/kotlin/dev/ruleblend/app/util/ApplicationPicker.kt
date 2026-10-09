package dev.ruleblend.app.util

import java.nio.file.Path
import javax.swing.JFileChooser

/** Selects a macOS application bundle or an editor executable on other desktop platforms. */
fun pickExternalEditor(title: String): Path? = DesktopEnvironment.actions.editor(title)

internal fun nativePickExternalEditor(title: String): Path? {
    if (!System.getProperty("os.name").startsWith("Mac")) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.FILES_ONLY
            dialogTitle = title
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
        } else {
            null
        }
    }

    val process = ProcessBuilder(
        "/usr/bin/osascript",
        "-l",
        "JavaScript",
        "-e",
        """
            function run(argv) {
                const app = Application.currentApplication();
                app.includeStandardAdditions = true;
                return app.chooseApplication({withPrompt: argv[0], as: "alias"}).toString();
            }
        """.trimIndent(),
        title,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }

    return when (process.waitFor()) {
        0 -> output.takeIf(String::isNotEmpty)?.let(Path::of)
        // AppleScript reports cancelling the picker as error -128.
        else -> if (output.contains("(-128)")) null else error("Could not choose an external editor: $output")
    }
}
