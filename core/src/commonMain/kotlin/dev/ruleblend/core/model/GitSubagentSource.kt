package dev.ruleblend.core.model

import kotlinx.serialization.Serializable

/** Exact upstream definition and the assistant whose native fields it carries. */
@Serializable
data class GitSubagentSource(
    val repository: String,
    val revision: String,
    val path: String,
    val assistant: String,
)
