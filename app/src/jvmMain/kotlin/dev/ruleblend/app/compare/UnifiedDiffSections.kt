package dev.ruleblend.app.compare

/** A hunk-oriented source/target pair reconstructed from one file in a Git unified diff. */
internal data class UnifiedDiffSection(val source: String, val target: String)

/**
 * Keeps the source and target text of every changed file separate, so a revision can reuse
 * [LineDiff] without treating the end of one file and the start of another as one replacement.
 * Unified diff context is intentionally retained: it is exactly the surrounding text Git showed.
 */
internal fun unifiedDiffSections(diff: String): List<UnifiedDiffSection> {
    val sections = mutableListOf<UnifiedDiffSection>()
    var source = mutableListOf<String>()
    var target = mutableListOf<String>()
    var inHunk = false

    fun finishFile() {
        if (source.isNotEmpty() || target.isNotEmpty()) {
            sections += UnifiedDiffSection(source.joinToString("\n"), target.joinToString("\n"))
        }
        source = mutableListOf()
        target = mutableListOf()
        inHunk = false
    }

    diff.lineSequence().forEach { line ->
        when {
            line.startsWith("diff --git ") -> finishFile()
            line.startsWith("@@ ") -> inHunk = true
            !inHunk || line.startsWith("\\ No newline at end of file") -> Unit
            line.startsWith("+") -> target += line.drop(1)
            line.startsWith("-") -> source += line.drop(1)
            line.startsWith(" ") -> {
                val text = line.drop(1)
                source += text
                target += text
            }
        }
    }
    finishFile()
    return sections
}
