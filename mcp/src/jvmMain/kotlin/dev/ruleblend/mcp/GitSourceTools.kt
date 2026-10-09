package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.GitSubagentSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.CheckSources
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.MutateLibrary
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.usecase.toSourcesState
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Session-local reviewed snapshots; callers cannot supply trees or provenance to the apply step. */
internal class GitSourceTools(
    private val repository: LibraryRepository,
    configStore: ConfigStore,
    private val imports: ImportLibrary?,
    private val sourcesState: SourcesStateStore?,
    private val now: () -> Instant = Instant::now,
) {
    private data class Preview(val plan: GitRepositoryImportPlan, val expiresAt: Instant)
    private val previews = linkedMapOf<String, Preview>()
    private val mutations = MutateLibrary(repository, configStore)

    fun preview(args: JsonObject): JsonElement = gitOperation {
        val plan = importer().fetch(args.text("repository"))
        val id = UUID.randomUUID().toString()
        val expiresAt = now().plusSeconds(600)
        synchronized(previews) {
            previews.entries.removeIf { !it.value.expiresAt.isAfter(now()) }
            while (previews.size >= 2) previews.remove(previews.keys.first())
            previews[id] = Preview(plan, expiresAt)
        }
        buildJsonObject {
            put("preview_id", id)
            put("expires_at", expiresAt.toString())
            put("repository", plan.repository)
            put("revision", plan.revision)
            put("version", plan.version)
            put("skills", buildJsonArray {
                plan.skills.forEach { snapshot -> add(buildJsonObject {
                    put("path", requireNotNull(snapshot.skill.source).path)
                    put("name", snapshot.skill.name)
                    put("description", snapshot.skill.description)
                    put("file_count", snapshot.files.size)
                }) }
            })
            put("subagents", buildJsonArray {
                plan.subagents.forEach { candidate -> add(buildJsonObject {
                    put("path", candidate.path)
                    candidate.sourceAssistant?.let { put("source_assistant", it) }
                    put("requires_assistant_selection", candidate.requiresAssistantSelection)
                    put("assistants", JsonArray(candidate.drafts.keys.map(::JsonPrimitive)))
                    put("names", buildJsonObject {
                        candidate.drafts.forEach { (assistant, draft) -> put(assistant, draft.name) }
                    })
                }) }
            })
            put("errors", buildJsonArray {
                plan.errors.forEach { error -> add(buildJsonObject {
                    put("path", error.path)
                    put("message", error.message)
                    put("has_source_text", error.sourceText != null)
                }) }
            })
        }
    }

    fun entry(args: JsonObject): JsonElement = gitOperation {
        val plan = previewPlan(args)
        val path = args.text("path", allowEmpty = true)
        when (args.text("kind")) {
            "skill" -> {
                val snapshot = plan.skills.find { it.skill.source?.path == path }
                    ?: throw ToolError("No skill at '$path' in this preview")
                buildJsonObject {
                    put("path", path)
                    put("name", snapshot.skill.name)
                    put("description", snapshot.skill.description)
                    put("content", snapshot.skill.content)
                    put("files", buildJsonArray {
                        snapshot.files.forEach { file -> add(buildJsonObject {
                            put("path", file.path)
                            put("size", file.bytes.size)
                            put("executable", file.executable)
                        }) }
                    })
                }
            }
            "subagent" -> {
                val candidate = plan.subagents.find { it.path == path }
                    ?: throw ToolError("No subagent at '$path' in this preview")
                buildJsonObject {
                    put("path", path)
                    put("source_text", candidate.sourceText)
                    put("definitions", buildJsonObject {
                        candidate.drafts.forEach { (assistant, draft) -> put(assistant, buildJsonObject {
                            put("name", draft.name)
                            put("description", draft.description)
                            put("content", draft.content)
                            put("fields", buildJsonObject { draft.fields.forEach { (key, value) -> put(key, value) } })
                        }) }
                    })
                }
            }
            "error" -> {
                val error = plan.errors.find { it.path == path }
                    ?: throw ToolError("No error at '$path' in this preview")
                buildJsonObject {
                    put("path", path)
                    put("message", error.message)
                    error.sourceText?.let { put("source_text", it) }
                }
            }
            else -> throw ToolError("'kind' must be skill, subagent or error")
        }
    }

    fun apply(args: JsonObject): JsonElement = gitOperation {
        val plan = previewPlan(args)
        val paths = args.strings("skill_paths")
        val selected = args["subagents"]?.let { value ->
            val obj = value as? JsonObject ?: throw ToolError("'subagents' must map paths to source assistants")
            obj.mapValues { (_, assistant) -> assistant.stringValue("subagents") }
        }.orEmpty()
        if (paths.isEmpty() && selected.isEmpty()) throw ToolError("Select at least one skill path or subagent")
        repository.withLock {
            importer().applyGit(plan, paths, selected)
            buildJsonObject {
                put("repository", plan.repository)
                put("revision", plan.revision)
                put("skills", buildJsonArray {
                    repository.listSkills().filter {
                        it.source?.let { source -> source.repository == plan.repository && source.path in paths } == true
                    }.forEach { add(it.sourceSummary()) }
                })
                put("subagents", buildJsonArray {
                    repository.listBlocks().filter {
                        it.source?.let { source -> source.repository == plan.repository && selected[source.path] == source.assistant } == true
                    }.forEach { add(it.sourceSummary()) }
                })
            }
        }
    }

    fun list(): JsonElement = buildJsonArray {
        val skills = repository.listSkills().filter { it.source != null }
        val subagents = repository.listBlocks().filter { it.source != null }
        val cached = sourcesState?.load()?.sources.orEmpty().associateBy { it.repository }
        (skills.map { it.source!!.repository } + subagents.map { it.source!!.repository }).distinct().sorted().forEach { url ->
            add(buildJsonObject {
                put("repository", url)
                cached[url]?.lastCheckedAt?.let { put("last_checked_at", it) }
                cached[url]?.remoteHead?.let { put("remote_head", it) }
                put("skills", buildJsonArray { skills.filter { it.source!!.repository == url }.forEach { add(it.sourceSummary()) } })
                put("subagents", buildJsonArray { subagents.filter { it.source!!.repository == url }.forEach { add(it.sourceSummary()) } })
            })
        }
    }

    fun check(): JsonElement = gitOperation {
        val report = CheckSources(repository).check()
        val checkedAt = now().toString()
        sourcesState?.save(report.toSourcesState(checkedAt))
        buildJsonObject {
            put("checked_at", checkedAt)
            put("repositories", buildJsonArray {
                report.perRepo.forEach { source -> add(buildJsonObject {
                    put("repository", source.repository)
                    put("status", source.status.name.lowercase())
                    source.remoteHead?.let { put("remote_head", it) }
                    source.error?.let { put("error", it) }
                }) }
            })
            put("skills", buildJsonArray {
                report.perSkill.forEach { source -> add(buildJsonObject {
                    put("id", source.skillId)
                    put("repository", source.repository)
                    put("path", source.path)
                    put("status", source.status.name.lowercase())
                }) }
            })
            put("subagents", buildJsonArray {
                report.perSubagent.forEach { source -> add(buildJsonObject {
                    put("id", source.subagentId)
                    put("repository", source.repository)
                    put("path", source.path)
                    put("assistant", source.assistant)
                    put("status", source.status.name.lowercase())
                }) }
            })
        }
    }

    fun update(args: JsonObject): JsonElement = gitOperation {
        val skills = args.strings("skill_ids")
        val subagents = args.strings("subagent_ids")
        if (skills.isEmpty() && subagents.isEmpty()) throw ToolError("Select at least one skill or subagent id")
        val updated = importer().updateDefinitions(skills, subagents).value
        buildJsonObject {
            put("skills", buildJsonArray { updated.skills.forEach { add(it.sourceSummary()) } })
            put("subagents", buildJsonArray { updated.subagents.forEach { add(it.sourceSummary()) } })
        }
    }

    fun forkSkill(args: JsonObject): JsonElement = gitOperation {
        val id = args.text("id")
        repository.withLock {
            val source = repository.loadSkill(id) ?: throw ToolError("No skill with id '$id'")
            if (source.source == null) throw ToolError("Skill '$id' is not imported")
            mutations.forkSkill(source).value.sourceSummary()
        }
    }

    fun forkSubagent(args: JsonObject): JsonElement = gitOperation {
        val id = args.text("id")
        repository.withLock {
            val source = repository.loadBlock(id) ?: throw ToolError("No subagent with id '$id'")
            if (source.source == null) throw ToolError("Subagent '$id' is not imported")
            mutations.duplicateBlock(source).value.sourceSummary()
        }
    }

    private fun importer(): ImportLibrary = imports ?: throw ToolError("Git import is not configured")

    private fun previewPlan(args: JsonObject): GitRepositoryImportPlan {
        val id = args.text("preview_id")
        return synchronized(previews) {
            previews.entries.removeIf { !it.value.expiresAt.isAfter(now()) }
            previews[id]?.plan ?: throw ToolError("Unknown or expired preview; call preview_git_import again")
        }
    }
}

internal fun JsonObjectBuilder.putProvenance(skill: Skill) {
    put("editable", skill.source == null)
    skill.source?.let { put("git_source", it.toSourceJson()) }
    skill.forkedFrom?.let { put("forked_from", it.toSourceJson()) }
}

internal fun JsonObjectBuilder.putProvenance(block: Block) {
    put("editable", block.source == null)
    put("source", block.source?.repository ?: "local")
    block.source?.let { put("git_source", it.toSourceJson()) }
    block.forkedFrom?.let { put("forked_from", it.toSourceJson()) }
}

private fun GitSkillSource.toSourceJson(): JsonObject = buildJsonObject {
    put("repository", repository)
    put("revision", revision)
    put("path", path)
}

private fun GitSubagentSource.toSourceJson(): JsonObject = buildJsonObject {
    put("repository", repository)
    put("revision", revision)
    put("path", path)
    put("assistant", assistant)
}

private fun Skill.sourceSummary(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("version", version)
    putProvenance(this@sourceSummary)
}

private fun Block.sourceSummary(): JsonObject = buildJsonObject {
    put("id", id)
    put("name", name)
    put("version", version)
    putProvenance(this@sourceSummary)
}

private fun JsonObject.text(key: String, allowEmpty: Boolean = false): String {
    val value = this[key]?.stringValue(key) ?: throw ToolError("Missing required argument '$key'")
    if (!allowEmpty && value.isBlank()) throw ToolError("Argument '$key' must not be blank")
    return value
}

private fun JsonElement.stringValue(key: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolError("'$key' must be a string")

private fun JsonObject.strings(key: String): Set<String> {
    val value = this[key] ?: return emptySet()
    val array = value as? JsonArray ?: throw ToolError("'$key' must be an array of strings")
    return array.map { it.stringValue(key) }.toSet()
}

private inline fun <T> gitOperation(action: () -> T): T = try {
    action()
} catch (e: ToolError) {
    throw e
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
} catch (e: Exception) {
    throw ToolError(e.message ?: "Git source operation failed; read the library before retrying")
}
