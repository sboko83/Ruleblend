package dev.ruleblend.core.model

import kotlinx.serialization.Serializable

/**
 * A portable project mode stored in `profiles/<id>.yaml`. Profiles may include flat groups, but
 * never other profiles; [version] changes only when the effective membership changes.
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val description: String = "",
    val blockIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val subagentIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val version: Int = 1,
)
