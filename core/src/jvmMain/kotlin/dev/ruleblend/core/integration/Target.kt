package dev.ruleblend.core.integration

import java.nio.file.Path

/** A pointer file [from] whose only managed content is an import of [to]. */
data class Redirect(val from: Path, val to: Path)

/** A place blocks can be installed into: an agent's global file, or a project folder. */
sealed interface Target {
    val name: String

    /** Instruction files this target writes blocks into — one per agent it covers. */
    fun files(): List<Path>

    /** Pointer files that make an agent find [files]; empty when every agent reads them directly. */
    fun redirects(): List<Redirect> = emptyList()

    /** Every file this target owns: the ones holding blocks, plus the pointers to them. */
    fun ownedFiles(): List<Path> = (files() + redirects().map { it.from }).distinct()

    /**
     * The directory `@import` directives are resolved against. Claude Code anchors every `@path` at
     * the project root regardless of which file mentions it; a project target's root is its
     * [ProjectTarget.dir], an agent global target's is its file's parent.
     */
    fun importRoot(): Path?
}

/** The agent's own global instruction file, applied to every project. */
data class AgentGlobalTarget(val agent: AgentAdapter) : Target {
    override val name: String get() = agent.name

    override fun files(): List<Path> = listOf(agent.globalFile())

    override fun importRoot(): Path? = agent.globalFile().parent
}

/**
 * A project folder. Every enabled agent reads `AGENTS.md`, so blocks land in that one file; Claude
 * Code reads it through a managed `@AGENTS.md` import in `CLAUDE.md`. One managed file per project,
 * no duplicated content and no second copy to keep in sync.
 */
data class ProjectTarget(val dir: Path, val agents: List<AgentAdapter>) : Target {
    override val name: String get() = dir.fileName?.toString() ?: dir.toString()

    override fun files(): List<Path> = agents.map { it.projectFile(dir) }.distinct()

    override fun redirects(): List<Redirect> = agents
        .mapNotNull { agent -> agent.projectRedirectFile(dir)?.let { Redirect(it, agent.projectFile(dir)) } }
        .distinct()

    override fun importRoot(): Path = dir
}
