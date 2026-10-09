package dev.ruleblend.core.integration

/** Adopted text above this many bytes is worth warning about. */
const val ADOPT_WARN_BYTES: Int = 50 * 1024

/** Adopted text above this many lines is worth warning about. */
const val ADOPT_WARN_LINES: Int = 1000

/** Size of an oversized adopt candidate, as measured for the warning. */
data class AdoptSize(val lines: Int, val bytes: Int)

/**
 * Measures [text] when it is large enough to be worth a warning, `null` otherwise. Advisory only:
 * a big block is unwieldy in the editor and is duplicated in full into every target it is installed
 * into, but adopting it is still the user's call.
 */
fun adoptSizeWarning(text: String): AdoptSize? {
    val bytes = text.encodeToByteArray().size
    val lines = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    return if (bytes > ADOPT_WARN_BYTES || lines > ADOPT_WARN_LINES) AdoptSize(lines, bytes) else null
}

/**
 * Portable results of the pre-save adopt scan: `@import` directives (Claude Code expands them,
 * every other agent Ruleblend targets treats them as plain text), and markdown link/image targets
 * that look like local paths. Path existence is not decided here — the caller resolves each entry
 * of [localLinks] against the source file's directory to know which ones are broken.
 */
data class AdoptScan(
    /** `@path` directives as they appear in the text, without the leading `@`. */
    val imports: List<String>,
    /** Bare relative paths from markdown links/images that look like local files, not URLs/anchors. */
    val localLinks: List<String>,
)

/**
 * Extracts `@import` directives Claude Code understands. A directive is a token starting with `@`,
 * preceded by start-of-line or whitespace/`(`/`,`, and whose body looks path-shaped: either it
 * contains a `/` or ends in a file extension. This filters out email addresses
 * (`user@example.com` — the `@` is mid-token, not at a boundary), Kotlin annotations and Python
 * decorators (`@Test`, `@Composable` — no slash, no extension), and stray `@` mentions.
 *
 * Matches are returned in file order without the leading `@`. Duplicates are kept — how often a
 * path appears is meaningful. Fenced code blocks are stripped before scanning: an `@import` shown
 * as an example inside a fence must not surface as a real finding.
 */
fun findImports(text: String): List<String> {
    val body = stripFencedCode(text)
    val pattern = "(?:^|[\\s(,;])@([A-Za-z0-9_./\\-]+)".toRegex(RegexOption.MULTILINE)
    return pattern.findAll(body)
        .map { it.groupValues[1].trimEnd('.', ',', ';', ')') }
        .filter { looksLikePath(it) }
        .toList()
}

private fun looksLikePath(token: String): Boolean {
    if (token.isEmpty()) return false
    if (token.contains('/')) return true
    val dot = token.lastIndexOf('.')
    return dot > 0 && dot < token.length - 1
}

/**
 * Extracts markdown link/image targets that look like local paths: `[text](path)` and `![alt](path)`
 * whose `path` is neither an absolute URL (`http:`, `https:`, `mailto:`, `data:`, `//host`), nor an
 * anchor (`#section`), nor an absolute filesystem path (`/…`). Query and fragment tails are stripped
 * so the caller can resolve the returned string against a base directory directly.
 *
 * Fenced code blocks are stripped first: link syntax inside a sample must not be flagged.
 */
fun findLocalLinkPaths(text: String): List<String> {
    val body = stripFencedCode(text)
    val link = "!?\\[[^\\]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)".toRegex()
    return link.findAll(body)
        .map { it.groupValues[1] }
        .filter { isLocalPath(it) }
        .map { it.substringBefore('#').substringBefore('?') }
        .filter { it.isNotEmpty() }
        .toList()
}

private fun isLocalPath(target: String): Boolean {
    if (target.isEmpty()) return false
    if (target.startsWith('#')) return false
    if (target.startsWith("//")) return false
    if (target.startsWith('/')) return false
    // A leading `<scheme>:` — http, https, mailto, data, tel, file — means a URL, not a local file.
    val schemeEnd = target.indexOf(':')
    if (schemeEnd in 1 until target.length &&
        target.substring(0, schemeEnd).all { it.isLetter() || it.isDigit() || it == '+' || it == '-' || it == '.' }
    ) return false
    return true
}

/**
 * Removes ` ``` ` and `~~~` fenced blocks so link syntax and `@` tokens shown as examples inside
 * code do not surface as findings. A cheap textual sweep — good enough for what Ruleblend sees; a
 * full markdown parser would be overkill here.
 */
private fun stripFencedCode(text: String): String {
    val out = StringBuilder()
    var fence: String? = null
    for (line in text.lines()) {
        val trimmed = line.trimStart()
        val marker = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (fence == null) {
            if (marker != null) {
                fence = marker
                out.append('\n')
                continue
            }
            out.append(line).append('\n')
        } else {
            if (marker == fence) fence = null
            out.append('\n')
        }
    }
    return out.toString()
}
