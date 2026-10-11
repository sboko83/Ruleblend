package dev.ruleblend.app.spike

import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.util.CliProbe
import dev.ruleblend.app.util.DesktopActions
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.ClaudeCodeConnector
import dev.ruleblend.mcp.McpLauncher
import dev.ruleblend.mcp.skill.ClaudeCodeSkillInstaller
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.StandardOpenOption

/** Real application window and terminal, isolated profiles, offline executable. No AI requests. */
fun main() {
    val root = Path.of(requireNotNull(System.getProperty("ruleblend.native.root")))
    val home = root.resolve("home")
    check(System.getProperty("user.home") == home.toString())
    Files.createDirectories(home.resolve(".claude"))
    val library = home.resolve(".ruleblend/library")
    LibraryRepository(library).apply {
        init()
        saveBlock(Block("native-one", "Native terminal check", content = "Synthetic saved rule"))
        saveBlock(Block("native-two", "Second saved rule", content = "Synthetic second rule"))
    }
    val fixture = Path.of(requireNotNull(System.getProperty("ruleblend.lifecycle.fixture")))
    val fakeCli = root.resolve("fake claude")
    Files.writeString(fakeCli, "#!/usr/bin/python3\n" + Files.readString(fixture).substringAfter('\n'))
    Files.setPosixFilePermissions(fakeCli, PosixFilePermissions.fromString("rwx------"))
    val launch = McpLauncher(home.resolve(".ruleblend/bin/ruleblend-mcp"), "dev.ruleblend.app.MainKt").resolve()
    ClaudeCodeConnector(home).register(launch.command)
    ClaudeCodeSkillInstaller(home).install()
    openConfigStore().update { it.copy(assistanceAssistants = listOf("claude-code"), language = "en") }
    fun diagnostic(text: String) {
        Files.writeString(root.resolve("focus.log"), "$text\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    var observedFocusManager: java.awt.KeyboardFocusManager? = null
    javax.swing.Timer(1_000) {
        // Compose installs its own focus manager during window creation.
        val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        if (observedFocusManager !== manager) {
            observedFocusManager = manager
            diagnostic("manager: ${manager.javaClass.name} owner=${manager.focusOwner?.javaClass?.name}")
            manager.addPropertyChangeListener("focusOwner") { event ->
                diagnostic("focus: ${event.oldValue?.javaClass?.name} -> ${event.newValue?.javaClass?.name}")
            }
            manager.addKeyEventDispatcher { event ->
                diagnostic("key: ${event.id}/${event.keyCode} owner=${manager.focusOwner?.javaClass?.name}")
                false
            }
        }
    }.start()
    val desktop = DesktopEnvironment.actions
    DesktopEnvironment.actions = object : DesktopActions by desktop {
        override fun probe(executable: String): CliProbe? = if (executable == "claude")
            CliProbe(fakeCli.toString(), true, "/usr/bin:/bin") else null
    }
    println("Offline native acceptance root: $root")
    try { dev.ruleblend.app.main(emptyArray()) } finally { DesktopEnvironment.actions = desktop }
}
