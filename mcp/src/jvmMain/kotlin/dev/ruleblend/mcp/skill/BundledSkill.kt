package dev.ruleblend.mcp.skill

/**
 * The `SKILL.md` shipped inside the app — the single source every agent's copy is written from.
 * It is not a library block: the user cannot edit or delete it, and an app update replaces the
 * copies on disk. Kept as a resource rather than a Kotlin string so it reads and diffs as markdown.
 */
object BundledSkill {

    /** Skill name, and the directory name under an agent's skills root. */
    const val NAME: String = "ruleblend"

    /**
     * Start of the last line of the bundled text; its presence marks a copy on disk as ours to
     * overwrite. The version follows it, so a copy states which app wrote it without a sidecar.
     */
    const val MARKER_PREFIX: String = "<!-- ruleblend-managed skill"

    /** Ownership marker written by Kitbash before the rename. */
    const val LEGACY_MARKER: String = "<!-- kitbash-managed skill -->"

    val text: String by lazy {
        val resource = BundledSkill::class.java.getResourceAsStream("SKILL.md")
            ?: error("Bundled SKILL.md is missing from the app resources")
        resource.use { it.readBytes().decodeToString() }
    }

    /**
     * Version of the bundled text. Raised by hand in `SKILL.md` whenever the skill changes; a guard
     * test pins the text to this number so an edit cannot ship under the old version.
     */
    val version: Int by lazy {
        val parsed = versionIn(text)
        require(parsed != null && parsed > 0) { "Bundled SKILL.md must end in `$MARKER_PREFIX v<n> -->`" }
        parsed
    }

    /** The skill's own one-line summary, read from the frontmatter it ships with. */
    val description: String by lazy {
        val line = text.lineSequence().take(FRONTMATTER_SCAN_LINES).firstOrNull { it.startsWith("description:") }
        line?.removePrefix("description:")?.trim().orEmpty()
    }

    /** Frontmatter is the first block of the file; scanning past it would read the instructions. */
    private const val FRONTMATTER_SCAN_LINES = 20

    /** Whether [text] is a copy Ruleblend wrote, whatever version it carries. */
    fun isOwned(text: String): Boolean = markerLine(text) != null

    /**
     * Version of a copy, or `null` when it is not ours. A marker written before versions existed —
     * or one whose number cannot be read — answers `0`: the copy is ours and predates every
     * numbered one, so it is replaced rather than mistaken for someone else's file.
     */
    fun versionIn(text: String): Int? {
        val line = markerLine(text) ?: return null
        val rest = line.removePrefix(MARKER_PREFIX).removeSuffix("-->").trim().removePrefix("v")
        return rest.toIntOrNull() ?: 0
    }

    private fun markerLine(text: String): String? =
        text.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(MARKER_PREFIX) }
}
