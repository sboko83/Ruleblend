package dev.ruleblend.core.integration

import java.nio.file.Path
import kotlin.io.path.exists

/** One skill root an agent reads at a scope; [id] keeps its ownership record distinct. */
data class SkillDirectory(val id: String, val path: Path)

/** Whether an agent has a safe, file-backed subagent definition surface. */
enum class SubagentSupport {
    SUPPORTED,
    UNSUPPORTED,
}

/** An agent Ruleblend can install blocks into: where it lives and which files it reads. */
interface AgentAdapter {
    val id: String
    val name: String

    /**
     * Whether a running session must be restarted after project-profile files are rewritten.
     * Kept on the adapter because reload behaviour belongs to the agent, not to Home's UI.
     */
    val profileSwitchRequiresRestart: Boolean get() = true

    /** True when the agent is present on this machine. */
    fun isAvailable(): Boolean

    /**
     * How this agent is started from a terminal, or `null` when Ruleblend has no CLI for it. The
     * executable is a name rather than a path: it is resolved against the user's login shell, which
     * is the only PATH that knows about nvm, mise or Homebrew installs.
     */
    val cli: AgentCli? get() = null

    /** The agent's own instruction file, applied to every project. */
    fun globalFile(): Path

    /**
     * The instruction file the agent reads inside [projectDir]. Every agent Ruleblend supports reads
     * `AGENTS.md`, directly or through a [projectRedirectFile] — so a project holds one managed file.
     */
    fun projectFile(projectDir: Path): Path

    /**
     * The file the agent actually opens in [projectDir] when it does not read [projectFile] itself.
     * Ruleblend keeps a managed one-line import there pointing at [projectFile]; `null` means the
     * agent reads [projectFile] directly.
     */
    fun projectRedirectFile(projectDir: Path): Path? = null

    /** User-level skill root, or `null` when Ruleblend has no native skill integration. */
    fun globalSkillsDirectory(): Path? = null

    /** Project-level skill root, or `null` when Ruleblend has no native skill integration. */
    fun projectSkillsDirectory(projectDir: Path): Path? = null

    /** Whether Ruleblend can manage this agent's subagent definitions. */
    val subagentSupport: SubagentSupport get() = SubagentSupport.UNSUPPORTED

    /** Syntax used by the agent's subagent files, or `null` when [subagentSupport] is unsupported. */
    val subagentFormat: SubagentFormat? get() = null

    /** User-level directory of file-backed subagent definitions. */
    fun globalSubagentDirectory(): Path? = null

    /** Project-level directory of file-backed subagent definitions. */
    fun projectSubagentDirectory(projectDir: Path): Path? = null

    /** File Ruleblend will use for [id] at agent-global scope, if the agent supports subagents. */
    fun globalSubagentFile(id: String): Path? = subagentFile(globalSubagentDirectory(), id)

    /** File Ruleblend will use for [id] at project scope, if the agent supports subagents. */
    fun projectSubagentFile(projectDir: Path, id: String): Path? = subagentFile(projectSubagentDirectory(projectDir), id)

    /**
     * Skill roots read globally. Most agents have one; agents that combine native and shared skill
     * roots override this rather than dropping one of the surfaces they load.
     */
    fun globalSkillDirectories(): List<SkillDirectory> =
        globalSkillsDirectory()?.let { listOf(SkillDirectory("default", it)) } ?: emptyList()

    /** Skill roots read from one project or workspace. See [globalSkillDirectories]. */
    fun projectSkillDirectories(projectDir: Path): List<SkillDirectory> =
        projectSkillsDirectory(projectDir)?.let { listOf(SkillDirectory("default", it)) } ?: emptyList()

    private fun subagentFile(directory: Path?, id: String): Path? = directory?.let {
        requireNotNull(subagentFormat) { "$name declares a subagent directory without a format" }
            .let { format -> it.resolve("$id.${format.extension}") }
    }
}

/**
 * Claude Code is the only agent that does not read `AGENTS.md`, so in a project it gets a `CLAUDE.md`
 * holding nothing but a managed `@AGENTS.md` import. Globally there is no shared file to point at —
 * every agent has its own config directory — so `~/.claude/CLAUDE.md` is written directly.
 */
class ClaudeCodeAdapter(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : AgentAdapter {
    override val id: String = "claude-code"
    override val name: String = "Claude Code"
    override val cli = AgentCli("claude", resumeArguments = listOf("--continue"))

    override fun isAvailable(): Boolean = paths.claude.exists()

    override fun globalFile(): Path = paths.claude.resolve("CLAUDE.md")

    override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")

    override fun projectRedirectFile(projectDir: Path): Path = projectDir.resolve("CLAUDE.md")

    override fun globalSkillsDirectory(): Path = paths.claude.resolve("skills")

    override fun projectSkillsDirectory(projectDir: Path): Path = projectDir.resolve(".claude").resolve("skills")

    override val subagentSupport = SubagentSupport.SUPPORTED
    override val subagentFormat = ClaudeCodeSubagentFormat

    override fun globalSubagentDirectory(): Path = paths.claude.resolve("agents")

    override fun projectSubagentDirectory(projectDir: Path): Path = projectDir.resolve(".claude").resolve("agents")
}

class CodexAdapter(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : AgentAdapter {
    override val id: String = "codex"
    override val name: String = "Codex"
    override val cli = AgentCli("codex", resumeArguments = listOf("resume", "--last"))

    override fun isAvailable(): Boolean = paths.codex.exists()

    override fun globalFile(): Path = paths.codex.resolve("AGENTS.md")

    override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")

    override fun globalSkillDirectories(): List<SkillDirectory> = listOf(
        SkillDirectory("native", paths.codex.resolve("skills")),
    )

    override fun projectSkillDirectories(projectDir: Path): List<SkillDirectory> = listOf(
        SkillDirectory("native", projectDir.resolve(".codex").resolve("skills")),
    )

    override val subagentSupport = SubagentSupport.SUPPORTED
    override val subagentFormat = CodexSubagentFormat

    override fun globalSubagentDirectory(): Path = paths.codex.resolve("agents")
}

class KimiCodeAdapter(home: KimiCodeHome = KimiCodeHome.current()) : AgentAdapter {
    /** [home] is the OS home; Kimi's directory is `.kimi-code` under it, see [KimiCodeHome.under]. */
    constructor(home: Path) : this(KimiCodeHome.under(home))

    private val kimiHome: Path = home.directory
    private val sharedHome: Path = home.userHome

    override val id: String = "kimi-code"
    override val name: String = "Kimi Code"
    override val cli = AgentCli("kimi", resumeArguments = listOf("--continue"))

    override fun isAvailable(): Boolean = kimiHome.exists()

    override fun globalFile(): Path = kimiHome.resolve("AGENTS.md")

    override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")

    override fun globalSkillsDirectory(): Path = kimiHome.resolve("skills")

    override fun projectSkillsDirectory(projectDir: Path): Path = projectDir.resolve(".kimi-code").resolve("skills")

    override fun globalSkillDirectories(): List<SkillDirectory> = listOf(
        SkillDirectory("native", kimiHome.resolve("skills")),
        SkillDirectory("shared", sharedHome.resolve(".agents").resolve("skills")),
    )

    override fun projectSkillDirectories(projectDir: Path): List<SkillDirectory> = listOf(
        SkillDirectory("native", projectDir.resolve(".kimi-code").resolve("skills")),
        SkillDirectory("shared", projectDir.resolve(".agents").resolve("skills")),
    )

    override val subagentSupport = SubagentSupport.SUPPORTED
    override val subagentFormat = KimiCodeSubagentFormat

    override fun globalSubagentDirectory(): Path = kimiHome.resolve("agents")

    override fun projectSubagentDirectory(projectDir: Path): Path = projectDir.resolve(".kimi-code").resolve("agents")
}

class ZCodeAdapter(
    private val home: Path = Path.of(System.getProperty("user.home")),
) : AgentAdapter {
    override val id: String = "zcode"
    override val name: String = "ZCode"
    override val cli = AgentCli("zcode")

    override fun isAvailable(): Boolean = home.resolve(".zcode").exists()

    override fun globalFile(): Path = home.resolve(".zcode").resolve("AGENTS.md")

    override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")

    override fun globalSkillDirectories(): List<SkillDirectory> = listOf(
        SkillDirectory("native", home.resolve(".zcode").resolve("skills")),
        SkillDirectory("shared", home.resolve(".agents").resolve("skills")),
    )

    override fun projectSkillDirectories(projectDir: Path): List<SkillDirectory> = listOf(
        SkillDirectory("native", projectDir.resolve(".zcode").resolve("skills")),
        SkillDirectory("shared", projectDir.resolve(".agents").resolve("skills")),
    )
}

/**
 * Pi reads `~/.pi/agent/AGENTS.md` globally and `AGENTS.md` (or `CLAUDE.md`) per project — so in a
 * project it shares a file with Codex. Detection is by config dir: an unconfigured Pi has no
 * `AGENTS.md` yet, which is exactly the case Ruleblend is here to fix.
 */
class PiAdapter(
    home: Path? = null,
    private val paths: AssistantPaths = AssistantPaths.forHome(home),
) : AgentAdapter {
    override val id: String = "pi"
    override val name: String = "Pi"
    override val cli = AgentCli("pi")

    override fun isAvailable(): Boolean = paths.pi.exists()

    override fun globalFile(): Path = paths.pi.resolve("AGENTS.md")

    override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")

    override fun globalSkillsDirectory(): Path = paths.userHome.resolve(".agents").resolve("skills")

    override fun projectSkillsDirectory(projectDir: Path): Path = projectDir.resolve(".agents").resolve("skills")
}
