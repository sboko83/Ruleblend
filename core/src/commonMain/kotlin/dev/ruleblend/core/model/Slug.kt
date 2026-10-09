package dev.ruleblend.core.model

/**
 * Derives a file-name-safe id from a display [name]:
 * lowercase, non-alphanumerics collapsed into single dashes.
 * Returns an empty string when [name] has no usable characters.
 */
fun slugify(name: String): String = name
    .lowercase()
    .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
    .joinToString("")
    .split('-')
    .filter { it.isNotEmpty() }
    .joinToString("-")

/** [base] itself when free, otherwise the first `base-N` that is not [taken]. Empty [base] becomes `untitled`. */
fun uniqueId(base: String, taken: List<String>): String {
    val seed = base.ifEmpty { "untitled" }
    require(isPortableFileName("${seed.trimEnd('.', ' ')}-2")) { "Invalid id seed: $base" }
    val occupied = taken.map { it.lowercase() }.toSet()
    if (isPortableFileName(seed) && seed.lowercase() !in occupied) return seed
    return generateSequence(2) { it + 1 }.map { "${seed.trimEnd('.', ' ')}-$it" }
        .first { isPortableFileName(it) && it.lowercase() !in occupied }
}

/** Derives a collision-free id from a display [name]: [slugify] then de-duplicate against [taken]. */
fun nextId(name: String, taken: List<String>): String = uniqueId(slugify(name), taken)
