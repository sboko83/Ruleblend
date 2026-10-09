package dev.ruleblend.core.model

/**
 * Per-assistant tuning of one subagent, keyed by canonical field id. The subagent itself is one
 * portable object; only these values differ between assistants, because neither a model name nor a
 * tool name means the same thing in two of them.
 *
 * A field an assistant carries but Ruleblend does not model is kept here under its native key, so
 * adopting a foreign definition and reinstalling it does not silently drop it.
 */
data class SubagentVariant(val fields: Map<String, String> = emptyMap()) {

    val isEmpty: Boolean get() = fields.isEmpty()

    operator fun get(field: String): String? = fields[field]

    /** This variant with [field] set, or without it when [value] is null or blank. */
    fun with(field: String, value: String?): SubagentVariant {
        val cleaned = value?.trim()?.takeIf { it.isNotEmpty() }
        val updated = LinkedHashMap(fields)
        if (cleaned == null) updated.remove(field) else updated[field] = cleaned
        return SubagentVariant(updated)
    }

    companion object {
        val EMPTY = SubagentVariant()
    }
}

/** Canonical id of the model preference; each format maps it to its own native key. */
const val SUBAGENT_FIELD_MODEL: String = "model"

/** Canonical id of the tool allow-list, written as the comma-separated list its agents read. */
const val SUBAGENT_FIELD_TOOLS: String = "tools"
