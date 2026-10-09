package dev.ruleblend.mcp.skill

/** State of the Ruleblend skill in one agent's skills directory. */
enum class SkillStatus {
    NOT_INSTALLED,

    /** The copy on disk matches the bundled text. */
    INSTALLED,

    /** Ours (the marker is there) but from an older app version — reinstall replaces it. */
    OUTDATED,

    /** A skill of the same name written by someone else. Ruleblend never touches it. */
    FOREIGN,
}

/**
 * Installs the bundled skill into one agent's skills directory, teaching it the Ruleblend workflow.
 * The [dev.ruleblend.mcp.McpConnector] makes the tools reachable; this makes the agent use them.
 *
 * Unlike the connector there is no binary path involved, so no stale-path state: the file either
 * matches the bundled text or does not.
 */
interface SkillInstaller {
    val agentId: String

    /** True when the agent is present on this machine (its config directory exists). */
    fun isAvailable(): Boolean

    /** Writes the bundled skill, replacing an outdated copy. Fails on [SkillStatus.FOREIGN]. */
    fun install()

    /** Removes a Ruleblend-owned skill. A foreign skill with the same name is left untouched. */
    fun uninstall()

    fun status(): SkillStatus

    /**
     * Version of the copy on disk, so the user reads "v3 → v5" rather than a bare "outdated".
     * `null` when there is nothing of ours there: not installed, or someone else's file.
     */
    fun installedVersion(): Int?
}
