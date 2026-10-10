package dev.ruleblend.core.model

import kotlinx.serialization.Serializable

/** Exact source of a portable Claude Code plugin package; no machine-local installation state. */
@Serializable
data class ModSource(val repository: String, val revision: String, val path: String)

/** Upstream versions are opaque identifiers, never Ruleblend revision counters. */
@Serializable
data class Mod(
    val id: String,
    val name: String,
    val manifestName: String,
    val version: String? = null,
    val description: String = "",
    val source: ModSource? = null,
)

/** Portable file bytes and execution intent use the same representation as skill payloads. */
data class ModSnapshot(val mod: Mod, val files: List<SkillFile>)

@Serializable
data class ModMeta(
    val mod: Mod,
    val fingerprint: String,
    val executableFiles: List<String> = emptyList(),
    val formatVersion: Int = 1,
)
