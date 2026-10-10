package dev.ruleblend.core.integration

import dev.ruleblend.core.model.ModSnapshot

data class ModTarget(val assistant: String = "claude-code", val scope: String = "user")

enum class ModRuntimeState { ABSENT, OWNED, FOREIGN, DRIFT, PENDING, UNSUPPORTED }
enum class ModRuntimeError { UNSUPPORTED, CLI_UNAVAILABLE, INCOMPATIBLE, TIMEOUT, CANCELLED, OUTPUT_LIMIT, CLI_FAILED, INVALID_STATE, FOREIGN, DRIFT, PENDING, CONFIRMATION_REQUIRED }

class ModRuntimeException(val error: ModRuntimeError, message: String) : IllegalStateException(message)

data class ModRuntimeStatus(
    val libraryId: String,
    val state: ModRuntimeState,
    val pluginId: String? = null,
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val fingerprint: String? = null,
    /** CLI confirmation cannot prove that an already open session has reloaded. */
    val reloadRequired: Boolean = false,
)

/** Contains no option values or secrets. A fresh preview must still match at removal time. */
data class ModRemovalPreview(
    val status: ModRuntimeStatus,
    val scope: String,
    val keepData: Boolean = true,
    val lastInstallation: Boolean,
    val consequences: List<String>,
)

interface ModRuntime {
    fun probe(target: ModTarget, libraryId: String, manifestName: String? = null): ModRuntimeStatus
    fun install(target: ModTarget, snapshot: ModSnapshot): ModRuntimeStatus
    fun update(target: ModTarget, snapshot: ModSnapshot): ModRuntimeStatus
    fun enable(target: ModTarget, libraryId: String): ModRuntimeStatus
    fun disable(target: ModTarget, libraryId: String): ModRuntimeStatus
    fun previewRemove(target: ModTarget, libraryId: String): ModRemovalPreview
    fun remove(target: ModTarget, libraryId: String, accepted: ModRemovalPreview): ModRuntimeStatus
    fun recover(target: ModTarget): List<ModRuntimeStatus>
}
