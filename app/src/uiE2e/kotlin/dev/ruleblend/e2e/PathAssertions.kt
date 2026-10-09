package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey
import java.nio.file.Path
import kotlinx.serialization.json.JsonPrimitive

/** JSON string contents for assertions against persisted paths, without surrounding quotes. */
internal fun Path.jsonPathText(): String = JsonPrimitive(toString()).toString().drop(1).dropLast(1)

/** Project keys are canonicalized before persistence, unlike general file paths. */
internal fun Path.projectJsonText(): String = JsonPrimitive(projectKey()).toString().drop(1).dropLast(1)
