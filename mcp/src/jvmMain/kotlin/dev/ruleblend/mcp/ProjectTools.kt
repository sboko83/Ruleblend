package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.withProjectInSet
import dev.ruleblend.core.config.withoutPlace
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.InstallCommand
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.ProfileReconcileReport
import dev.ruleblend.core.usecase.ReconcileProfiles
import dev.ruleblend.core.usecase.RemoveObject
import dev.ruleblend.core.usecase.WriteOutcome
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.*

/** Machine-local projects and bindings; all target writes use the shared install policy. */
internal class ProjectTools(
    private val repository: LibraryRepository,
    private val config: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val rules: IntegrationService,
    private val mcp: McpBlockService,
    private val skills: SkillInstallService?,
    private val subagents: SubagentInstallService?,
    private val remove: RemoveObject,
    private val reconcile: ReconcileProfiles,
    private val report: (String, Boolean, Boolean, ProfileReconcileReport, Boolean) -> JsonObject,
) {
    private val entries = TargetEntryTools(repository, config, rules, mcp, skills, subagents) { project(it) }

    fun listAssistants(): JsonElement = buildJsonArray {
        val state = config.load()
        agents.forEach { agent -> add(buildJsonObject {
            put("id", agent.id)
            put("name", agent.name)
            put("available", agent.isAvailable())
            put("hidden", agent.id in state.hiddenAgents)
            put("profile_switch_requires_restart", agent.profileSwitchRequiresRestart)
        }) }
    }

    fun setAssistantVisibility(args: JsonObject): JsonElement {
        val id = args.string("agent_id")
        if (agents.none { it.id == id }) throw ToolError("Unknown assistant '$id'")
        val hidden = args.boolean("hidden")
        config.update { state -> state.copy(hiddenAgents =
            if (hidden) (state.hiddenAgents + id).distinct() else state.hiddenAgents - id) }
        return listAssistants()
    }

    fun register(args: JsonObject): JsonElement {
        val target = project(args.string("target"))
        val key = target.dir.projectKey()
        config.update { state -> state.copy(projects = (state.projects + key).distinct()) }
        return projectJson(target)
    }

    fun unregister(args: JsonObject): JsonElement {
        // A missing directory must still be removable from the navigation list.
        val key = path(args.string("target")).projectKey()
        config.update { state ->
            if (key !in state.projects) throw ToolError("Project '$key' is not registered")
            state.copy(
                projects = state.projects - key,
                disabledAgents = state.disabledAgents - key,
                projectProfiles = state.projectProfiles - key,
                ignoredEntries = state.ignoredEntries - "project:$key",
                places = state.places.withProjectInSet(key, null).withoutPlace("project:$key"),
            )
        }
        return buildJsonObject { put("target", key); put("registered", false); put("files_preserved", true) }
    }

    fun setAgents(args: JsonObject): JsonElement {
        val target = project(args.string("target"))
        val key = target.dir.projectKey()
        val enabled = args.strings("agent_ids")
        config.update { state ->
            if (key !in state.projects) throw ToolError("Register project '$key' first")
            val available = agents.filter { it.isAvailable() && it.id !in state.hiddenAgents }.map { it.id }
            if (!available.containsAll(enabled)) throw ToolError("agent_ids must name available, non-hidden assistants")
            // Keep disabled hidden/unavailable assistants so showing one later cannot enable it silently.
            val disabled = (state.disabledAgents[key].orEmpty().filterNot { it in available } + (available - enabled.toSet())).distinct()
            state.copy(disabledAgents = if (disabled.isEmpty()) state.disabledAgents - key
                else state.disabledAgents + (key to disabled))
        }
        return projectJson(project(key))
    }

    fun bindings(args: JsonObject): JsonElement = repository.withLock { snapshot(project(args.string("target"))) }

    fun attach(args: JsonObject): JsonElement = repository.withLock {
        val target = project(args.string("target"), requireAgents = true)
        val id = args.string("profile_id")
        if (repository.loadProfile(id) == null) throw ToolError("No profile with id '$id'")
        val mode = if ("mode" in args) args.string("mode") else "merge"
        if (mode !in setOf("merge", "replace")) throw ToolError("mode must be merge or replace")
        val key = target.dir.projectKey()
        if (mode == "replace" && config.load().projectProfiles[key].orEmpty().any { it.id == id }) {
            throw ToolError("Replace is only available when attaching a new profile")
        }
        val removal = if (mode == "replace") {
            val current = snapshot(target)
            if (args.string("expected_revision") != current["revision"]!!.jsonPrimitive.content) {
                throw ToolError("Project changed; read get_project_profiles and review the base again")
            }
            remove(InstallCommand(listOf(target), base(target)))
        } else null
        var registered = false
        config.update { state ->
            registered = key !in state.projects
            val bindings = state.projectProfiles[key].orEmpty()
            val updated = if (bindings.any { it.id == id }) bindings.map { if (it.id == id) it.copy(active = true) else it }
                else bindings + ProfileBinding(id, active = true)
            state.copy(projects = (state.projects + key).distinct(), projectProfiles = state.projectProfiles + (key to updated))
        }
        val result = report(id, true, true, reconcile(target), registered)
        buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("restart_required_agents", restartAgents(target))
            if (removal != null) put("base_removal", buildJsonArray {
                removal.entries.forEach { entry -> add(buildJsonObject {
                    put("id", entry.item.id)
                    put("kind", kind(entry.item))
                    when (val outcome = entry.outcome) {
                        WriteOutcome.Written -> put("status", "removed")
                        is WriteOutcome.Skipped -> { put("status", "skipped"); put("reason", outcome.reason.name.lowercase()) }
                        is WriteOutcome.Failed -> { put("status", "failed"); put("error", outcome.error.message) }
                    }
                }) }
            })
        }
    }

    fun setActive(args: JsonObject): JsonElement = repository.withLock {
        val target = project(args.string("target"), requireAgents = true)
        val id = args.string("profile_id")
        val active = args.boolean("active")
        config.update { state ->
            val key = target.dir.projectKey()
            val bindings = state.projectProfiles[key].orEmpty()
            if (bindings.none { it.id == id }) throw ToolError("Profile '$id' is not attached")
            if (active && repository.loadProfile(id) == null) throw ToolError("No profile with id '$id'")
            state.copy(projectProfiles = state.projectProfiles + (key to bindings.map { if (it.id == id) it.copy(active = active) else it }))
        }
        buildJsonObject {
            report(id, true, active, reconcile(target), false).forEach { (key, value) -> put(key, value) }
            put("restart_required_agents", restartAgents(target))
        }
    }

    private fun snapshot(target: ProjectTarget): JsonObject {
        val state = config.load()
        val public = buildJsonObject {
            projectJson(target).forEach { (key, value) -> put(key, value) }
            put("bindings", buildJsonArray { state.projectProfiles[target.dir.projectKey()].orEmpty().forEach { binding ->
                add(buildJsonObject { put("profile_id", binding.id); put("active", binding.active)
                    put("missing", repository.loadProfile(binding.id) == null) })
            } })
            put("base_items", buildJsonArray { base(target).forEach { item ->
                add(buildJsonObject { put("id", item.id); put("kind", kind(item)) })
            } })
            put("restart_required_agents", restartAgents(target))
        }
        // Include reviewed physical copies and library definitions, not just object ids.
        val revisions = if (target.agents.isEmpty()) JsonArray(emptyList()) else buildJsonArray {
            val request = buildJsonObject { put("target", target.dir.toString()) }
            val baseIds = base(target).map { kind(it) to it.id }.toSet()
            (entries.list(request) as JsonArray).filter { entry ->
                (entry.jsonObject["kind"]!!.jsonPrimitive.content to entry.jsonObject["id"]!!.jsonPrimitive.content) in baseIds
            }.forEach { entry ->
                val read = entries.read(buildJsonObject {
                    put("target", target.dir.toString()); put("entry_key", entry.jsonObject["entry_key"]!!)
                })
                add(read.jsonObject["revision"]!!)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(
            (public.toString() + revisions + repository.listProfiles() + repository.listGroups() + state.ruleScopes).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        return buildJsonObject { public.forEach { (key, value) -> put(key, value) }; put("revision", digest) }
    }

    private fun base(target: ProjectTarget): List<InstallItem> = buildList {
        rules.installedOrigins(target).filterValues(::isBase).keys.forEach { id ->
            repository.loadBlock(id)?.takeIf { it.type == BlockType.RULE }?.let { add(InstallItem.Rule(it)) }
        }
        mcp.installedOrigins(target).filterValues(::isBase).keys.forEach { id ->
            repository.loadBlock(id)?.takeIf { it.type == BlockType.MCP }?.let { add(InstallItem.Mcp(it)) }
        }
        skills?.installedOrigins(target)?.filterValues(::isBase)?.keys?.forEach { id ->
            repository.loadSkill(id)?.let { add(InstallItem.SkillCopy(it)) }
        }
        subagents?.installedOrigins(target)?.filterValues(::isBase)?.keys?.forEach { id ->
            repository.loadBlock(id)?.takeIf { it.type == BlockType.SUBAGENT }?.let { add(InstallItem.Subagent(it)) }
        }
    }

    private fun isBase(origin: String?): Boolean = origin?.startsWith("p=") != true
    private fun kind(item: InstallItem): String = when (item) {
        is InstallItem.Rule -> "rule"
        is InstallItem.Mcp -> "mcp"
        is InstallItem.SkillCopy -> "skill"
        is InstallItem.Subagent -> "subagent"
    }

    private fun path(raw: String): Path {
        val path = runCatching { Path.of(raw) }.getOrElse { throw ToolError("Invalid project path '$raw'") }
        if (!path.isAbsolute) throw ToolError("Project target must be an absolute path")
        return path.toAbsolutePath().normalize()
    }

    private fun project(raw: String, requireAgents: Boolean = false): ProjectTarget {
        val dir = path(raw)
        if (!dir.isDirectory()) throw ToolError("Project target is not an existing directory: '$raw'")
        val state = config.load()
        val disabled = state.disabledAgents[dir.projectKey()].orEmpty()
        val target = ProjectTarget(dir, agents.filter { it.isAvailable() && it.id !in state.hiddenAgents && it.id !in disabled })
        if (requireAgents && target.agents.isEmpty()) throw ToolError("No enabled agents for '$raw'")
        return target
    }

    private fun projectJson(target: ProjectTarget): JsonObject = buildJsonObject {
        put("target", target.dir.projectKey())
        put("registered", target.dir.projectKey() in config.load().projects)
        put("agent_ids", JsonArray(target.agents.map { JsonPrimitive(it.id) }))
    }

    private fun restartAgents(target: ProjectTarget): JsonArray =
        JsonArray(target.agents.filter { it.profileSwitchRequiresRestart }.map { JsonPrimitive(it.id) })

    private fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: throw ToolError("Pass $name as a non-empty string")

    private fun JsonObject.boolean(name: String): Boolean = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw ToolError("Pass $name as a boolean")

    private fun JsonObject.strings(name: String): List<String> {
        val array = this[name] as? JsonArray ?: throw ToolError("Pass $name as a string array")
        val values = array.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolError("$name must contain strings") }
        if (values.distinct().size != values.size) throw ToolError("$name must not contain duplicates")
        return values
    }
}
