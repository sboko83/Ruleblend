package dev.ruleblend.core.model

import kotlinx.serialization.Serializable

/** Reserved id for the auto-maintained group containing every block. */
const val ALL_GROUP_ID = "all"

/**
 * Ordered set of blocks and skills, stored in `groups/<id>.yaml`. [version] increments when either
 * membership list changes, so a target can tell whether an installed group is stale.
 */
@Serializable
data class Group(
    val id: String,
    val name: String,
    val description: String = "",
    val blockIds: List<String> = emptyList(),
    val skillIds: List<String> = emptyList(),
    val version: Int = 1,
)
