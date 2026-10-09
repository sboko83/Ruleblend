package dev.ruleblend.app.util

/** Length-prefixed identity prevents delimiters in paths/names from aliasing another UI target. */
internal fun uiTarget(kind: String, id: String, place: String, action: String): String =
    listOf(kind, id, place, action).joinToString("|") { "${it.length}:$it" }
