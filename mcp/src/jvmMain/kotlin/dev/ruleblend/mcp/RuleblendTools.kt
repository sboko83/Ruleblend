package dev.ruleblend.mcp

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.ruleAppliesTo
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.SubagentSupport
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ManagedRegion
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.TargetStatus
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.SUBAGENT_FIELD_MODEL
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.DEFAULT_HEADING_LEVEL
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.model.RESERVED_MCP_SERVER_NAME
import dev.ruleblend.core.model.validationErrors
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.nextId
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.usecase.InstallCommand
import dev.ruleblend.core.usecase.InstallItem
import dev.ruleblend.core.usecase.InstallObject
import dev.ruleblend.core.usecase.InstallPolicy
import dev.ruleblend.core.usecase.MutateLibrary
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.usecase.RemoveObject
import dev.ruleblend.core.usecase.ProfileObjectKind
import dev.ruleblend.core.usecase.ProfileReconcileOutcome
import dev.ruleblend.core.usecase.ProfileReconcileReport
import dev.ruleblend.core.usecase.ReconcileProfiles
import dev.ruleblend.core.usecase.SkipReason
import dev.ruleblend.core.usecase.WriteOutcome
import dev.ruleblend.core.usecase.WriteReport
import dev.ruleblend.core.usecase.WriteMode
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A user-facing tool failure: reported to the agent as an error result, not a protocol fault. */
class ToolError(message: String) : Exception(message)

/** Prefix marking a target id as an agent's global instruction file rather than a project path. */
const val AGENT_TARGET_PREFIX: String = "agent:"

/**
 * The MCP tool handlers over `core`, free of MCP SDK types so they are unit-testable without a
 * transport. Every mutation goes through [repository]/[configStore], which hold the inter-process
 * lock — the GUI app may be running at the same time.
 */
class RuleblendTools(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    private val agents: List<AgentAdapter>,
    private val service: IntegrationService = IntegrationService(blockResolver = repository::loadBlock),
    private val mcpService: McpBlockService = McpBlockService.None,
    private val skillService: SkillInstallService? = null,
    private val subagentService: SubagentInstallService? = null,
    gitImports: ImportLibrary? = null,
    sourcesState: SourcesStateStore? = null,
    libraryGit: LibraryGit? = null,
    libraryRoot: Path? = null,
) {
    private val exchange = LibraryExchangeTools(repository, configStore, gitImports, libraryGit, libraryRoot)
    fun exportLibrary(args: JsonObject): JsonElement = exchange.export(args)
    fun previewArchiveImport(args: JsonObject): JsonElement = exchange.preview(args)
    fun getArchiveImportEntry(args: JsonObject): JsonElement = exchange.entry(args)
    fun applyArchiveImport(args: JsonObject): JsonElement = exchange.apply(args)
    fun libraryHistory(args: JsonObject): JsonElement = exchange.history(args)
    fun libraryDiff(args: JsonObject): JsonElement = exchange.diff(args)
    fun librarySyncStatus(args: JsonObject): JsonElement = exchange.syncStatus(args)
    fun syncLibrary(args: JsonObject): JsonElement = exchange.sync(args)
    private val gitSources = GitSourceTools(repository, configStore, gitImports, sourcesState)
    private val skillFiles = SkillFileTools(repository, configStore)
    private val targetEntries = TargetEntryTools(repository, configStore, service, mcpService, skillService,
        subagentService) { resolveTarget(it, register = false).first }

    fun listTargetEntries(args: JsonObject): JsonElement = targetEntries.list(args)
    fun readTargetEntry(args: JsonObject): JsonElement = targetEntries.read(args)
    fun saveTargetEntry(args: JsonObject): JsonElement = targetEntries.save(args)
    fun acceptLocalChange(args: JsonObject): JsonElement = targetEntries.accept(args)
    fun manageTargetEntry(args: JsonObject): JsonElement = targetEntries.manage(args)
    fun reorderTargetRules(args: JsonObject): JsonElement = targetEntries.reorder(args)

    fun checkTargetConflicts(args: JsonObject): JsonElement {
        val target = resolveTarget(args.targetArg(), register = false).first
        val items = repository.listBlocks().map { block -> when (block.type) {
            BlockType.RULE -> InstallItem.Rule(block)
            BlockType.MCP -> InstallItem.Mcp(block)
            BlockType.SUBAGENT -> InstallItem.Subagent(block)
        } } + repository.listSkills().map { InstallItem.SkillCopy(it) }
        return FileReadScope.reading { buildJsonArray { items.forEach { item ->
            val refusal = policy.refusal(item, target, WriteMode.INSTALL, force = false)
            if (refusal != null) add(buildJsonObject {
                put("id", item.id)
                put("kind", when (item) {
                    is InstallItem.Rule -> "rule"
                    is InstallItem.Mcp -> "mcp"
                    is InstallItem.Subagent -> "subagent"
                    is InstallItem.SkillCopy -> "skill"
                })
                put("reason", refusal.name.lowercase().replace('_', '-'))
            })
        } } }
    }

    fun listSkillFiles(args: JsonObject): JsonElement = skillFiles.list(args)
    fun readSkillFile(args: JsonObject): JsonElement = skillFiles.read(args)
    fun writeSkillFile(args: JsonObject): JsonElement = skillFiles.write(args)
    fun deleteSkillFile(args: JsonObject): JsonElement = skillFiles.delete(args)

    fun previewGitImport(args: JsonObject): JsonElement = gitSources.preview(args)
    fun getGitImportEntry(args: JsonObject): JsonElement = gitSources.entry(args)
    fun applyGitImport(args: JsonObject): JsonElement = gitSources.apply(args)
    fun listSources(args: JsonObject): JsonElement = gitSources.list()
    fun checkSources(args: JsonObject): JsonElement = gitSources.check()
    fun updateFromSources(args: JsonObject): JsonElement = gitSources.update(args)
    fun forkSkill(args: JsonObject): JsonElement = gitSources.forkSkill(args)
    fun forkSubagent(args: JsonObject): JsonElement = gitSources.forkSubagent(args)

    private val policy = InstallPolicy(
        service,
        mcp = mcpService,
        skills = skillService,
        config = { configStore.load() },
        subagents = subagentService,
    )
    private val installObject = InstallObject(
        service,
        mcp = mcpService,
        skills = skillService,
        policy = policy,
        subagents = subagentService,
    )
    private val removeObject = RemoveObject(
        service,
        mcp = mcpService,
        skills = skillService,
        policy = policy,
        subagents = subagentService,
    )
    private val reconcileProfiles = ReconcileProfiles(
        repository = repository,
        configStore = configStore,
        rules = service,
        policy = policy,
        mcp = mcpService,
        skills = skillService,
        subagents = subagentService,
    )
    private val mutateLibrary = MutateLibrary(repository, configStore)
    private val projects = ProjectTools(repository, configStore, agents, service, mcpService, skillService,
        subagentService, removeObject, reconcileProfiles) { id, attached, active, report, registered ->
        reconcileProfiles(id, attached, active, report, registered)
    }

    fun listAssistants(args: JsonObject): JsonElement = projects.listAssistants()
    fun setAssistantVisibility(args: JsonObject): JsonElement = projects.setAssistantVisibility(args)
    fun registerProject(args: JsonObject): JsonElement = projects.register(args)
    fun unregisterProject(args: JsonObject): JsonElement = projects.unregister(args)
    fun setProjectAgents(args: JsonObject): JsonElement = projects.setAgents(args)
    fun getProjectProfiles(args: JsonObject): JsonElement = projects.bindings(args)
    fun attachProfile(args: JsonObject): JsonElement = projects.attach(args)
    fun setProfileActive(args: JsonObject): JsonElement = projects.setActive(args)

    fun listRules(args: JsonObject): JsonElement {
        val query = args.optionalString("query")?.lowercase()
        val target = args.optionalString("target")?.let { resolveTarget(it, register = false).first }
        val config = configStore.load()
        val blocks = repository.listBlocks().filter { block ->
            block.type == BlockType.RULE &&
                config.ruleAppliesTo(block.id, (target as? ProjectTarget)?.dir) &&
                (query == null || listOf(block.id, block.name, block.description, block.content)
                    .any { query in it.lowercase() })
        }
        return buildJsonArray {
            blocks.forEach { add(it.toSummaryJson(config.ruleScopes)) }
        }
    }

    fun getRule(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        val block = loadRule(id)
        return buildJsonObject {
            put("id", block.id)
            put("name", block.name)
            put("description", block.description)
            put("version", block.version)
            put("content", block.content)
            if (block.heading.isNotBlank()) {
                put("heading", block.heading)
                put("heading_level", block.headingLevel)
            }
            put("scope", configStore.load().ruleScopes[block.id] ?: "global")
        }
    }

    fun listSubagents(args: JsonObject): JsonElement {
        val query = args.optionalString("query")?.lowercase()
        return buildJsonArray {
            repository.listBlocks().filter { subagent ->
                subagent.type == BlockType.SUBAGENT &&
                    (query == null || listOf(
                        subagent.id,
                        subagent.name,
                        subagent.description,
                        subagent.variantValues(),
                        subagent.content,
                    ).any { query in it.lowercase() })
            }.forEach { subagent -> add(subagent.toSubagentSummaryJson()) }
        }
    }

    fun getSubagent(args: JsonObject): JsonElement {
        val subagent = loadSubagent(args.requiredString("id"))
        return subagent.toSubagentJson()
    }

    fun listSkills(args: JsonObject): JsonElement {
        val query = args.optionalString("query")?.lowercase()
        return buildJsonArray {
            repository.listSkills().filter { skill ->
                query == null || listOf(skill.id, skill.name, skill.description, skill.content).any { query in it.lowercase() }
            }.forEach { skill ->
                add(
                    buildJsonObject {
                        put("id", skill.id)
                        put("name", skill.name)
                        put("description", skill.description)
                        put("version", skill.version)
                        put("source", skill.source?.repository ?: "local")
                        putProvenance(skill)
                    },
                )
            }
        }
    }

    fun getSkill(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        val skill = repository.loadSkill(id) ?: throw ToolError("No skill with id '$id'")
        return buildJsonObject {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("version", skill.version)
            put("content", skill.content)
            put("source", skill.source?.repository ?: "local")
            putProvenance(skill)
        }
    }

    /** Portable profiles are listed directly because installing one binds it to a project. */
    fun listProfiles(args: JsonObject): JsonElement {
        val query = args.optionalString("query")?.lowercase()
        return buildJsonArray {
            repository.listProfiles().filter { profile ->
                query == null || listOf(profile.id, profile.name, profile.description).any { query in it.lowercase() }
            }.forEach { profile -> add(profile.toProfileSummaryJson()) }
        }
    }

    fun listGroups(args: JsonObject): JsonElement {
        repository.syncAllGroup()
        val target = args.optionalString("target")?.let { resolveTarget(it, register = false).first }
        val config = configStore.load()
        return buildJsonArray {
            repository.listGroups().forEach { group ->
                val members = group.blockIds.mapNotNull { repository.loadBlock(it) }
                val ruleIds = members
                    .filter { it.type == BlockType.RULE && config.ruleAppliesTo(it.id, (target as? ProjectTarget)?.dir) }
                    .map { it.id }
                // Listed separately from rules because these are server configs, not instruction text.
                val mcpIds = members.filter { it.type == BlockType.MCP }.map { it.id }
                val subagentIds = members.filter { it.type == BlockType.SUBAGENT }.map { it.id }
                val skillIds = group.skillIds.filter { repository.loadSkill(it) != null }
                if (target != null && ruleIds.isEmpty() && mcpIds.isEmpty() && subagentIds.isEmpty() && skillIds.isEmpty()) return@forEach
                add(
                    buildJsonObject {
                        put("id", group.id)
                        put("name", group.name)
                        put("description", group.description)
                        put("version", group.version)
                        put("rule_ids", buildJsonArray { ruleIds.forEach { add(JsonPrimitive(it)) } })
                        put("mcp_ids", buildJsonArray { mcpIds.forEach { add(JsonPrimitive(it)) } })
                        put("subagent_ids", buildJsonArray { subagentIds.forEach { add(JsonPrimitive(it)) } })
                        put("skill_ids", buildJsonArray { skillIds.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
    }

    fun deleteRule(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        loadRule(id)
        mutateLibrary.deleteBlock(id)
        return deletion(id)
    }

    fun listMcpServers(args: JsonObject): JsonElement {
        val query = args.optionalString("query")?.lowercase()
        return buildJsonArray {
            repository.listBlocks().filter { it.type == BlockType.MCP &&
                (query == null || listOf(it.id, it.name, it.description).any { field -> query in field.lowercase() })
            }.forEach { add(it.toMcpJson(includeConfig = false)) }
        }
    }

    fun getMcpServer(args: JsonObject): JsonElement = loadMcp(args.requiredString("id")).toMcpJson(includeConfig = true)

    fun createMcpServer(args: JsonObject): JsonElement {
        val name = formatName(args.requiredString("name"), configStore.load().nameFormat)
        val config = args.mcpConfig(null)
        val saved = repository.withLock {
            val id = nextId(name, repository.listBlocks().map { it.id })
            if (id == RESERVED_MCP_SERVER_NAME) throw ToolError("'$id' is reserved for Ruleblend's own MCP server")
            repository.saveBlock(Block(id = id, name = name, description = args.optionalString("description").orEmpty(),
                type = BlockType.MCP, content = McpConfigCodec.serialize(config))).also { repository.syncAllGroup() }
        }
        return identity(saved.id, saved.version)
    }

    fun updateMcpServer(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        if (id == RESERVED_MCP_SERVER_NAME) throw ToolError("'$id' is reserved and read-only")
        val saved = repository.withLock {
            val existing = loadMcp(id)
            val config = args.mcpConfig(McpConfigCodec.parse(existing.content).getOrElse {
                throw ToolError("MCP server config '$id' cannot be parsed: ${it.message}")
            })
            repository.saveBlock(existing.copy(
                name = args.optionalNonBlankString("name")?.let { formatName(it, configStore.load().nameFormat) } ?: existing.name,
                description = args.optionalString("description") ?: existing.description,
                content = McpConfigCodec.serialize(config),
            ))
        }
        return identity(saved.id, saved.version)
    }

    fun deleteMcpServer(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        if (id == RESERVED_MCP_SERVER_NAME) throw ToolError("'$id' is reserved and read-only")
        loadMcp(id)
        mutateLibrary.deleteBlock(id)
        return deletion(id)
    }

    fun getGroup(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        return (repository.loadGroup(id) ?: throw ToolError("No group with id '$id'")).toGroupJson()
    }

    fun createGroup(args: JsonObject): JsonElement {
        val group = mutateLibrary.createGroup(args.requiredString("name")).value
        return identity(group.id, group.version)
    }

    fun updateGroup(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        args.validateMembershipMaps(setOf("rule_ids", "mcp_ids", "subagent_ids", "skill_ids"))
        val saved = repository.withLock {
            val old = loadEditableGroup(id)
            val changed = old.copy(
                name = args.optionalNonBlankString("name") ?: old.name,
                description = args.optionalString("description") ?: old.description,
                blockIds = old.blockIds.editBlockMembers(args, listOf(BlockType.RULE, BlockType.MCP, BlockType.SUBAGENT)),
                skillIds = old.skillIds.editMembers(args, "skill_ids") { member -> repository.loadSkill(member) != null },
            )
            mutateLibrary.saveGroup(changed).value.let { repository.loadGroup(it.id)!! }
        }
        return saved.toGroupJson()
    }

    fun deleteGroup(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        loadEditableGroup(id)
        mutateLibrary.deleteGroup(id)
        return deletion(id)
    }

    fun reorderGroup(args: JsonObject): JsonElement = repository.withLock {
        val old = loadEditableGroup(args.requiredString("id"))
        val blocks = args.stringArray("block_ids")
        val skills = args.stringArray("skill_ids")
        if (blocks == null && skills == null) throw ToolError("Pass block_ids or skill_ids")
        fun ordered(requested: List<String>?, current: List<String>): List<String> {
            if (requested == null) return current
            if (requested.size != current.size || requested.toSet() != current.toSet()) {
                throw ToolError("Order must contain every current member exactly once")
            }
            return requested
        }
        mutateLibrary.saveGroup(old.copy(blockIds = ordered(blocks, old.blockIds),
            skillIds = ordered(skills, old.skillIds))).value.toGroupJson()
    }

    fun getProfile(args: JsonObject): JsonElement = loadProfile(args.requiredString("id")).toProfileSummaryJson()

    fun createProfile(args: JsonObject): JsonElement {
        val profile = mutateLibrary.createProfile(args.requiredString("name")).value
        return identity(profile.id, profile.version)
    }

    fun updateProfile(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        args.validateMembershipMaps(setOf("rule_ids", "mcp_ids", "subagent_ids", "skill_ids", "group_ids"))
        val saved = repository.withLock {
            val old = loadProfile(id)
            mutateLibrary.saveProfile(old.copy(
                name = args.optionalNonBlankString("name") ?: old.name,
                description = args.optionalString("description") ?: old.description,
                blockIds = old.blockIds.editBlockMembers(args, listOf(BlockType.RULE, BlockType.MCP)),
                subagentIds = old.subagentIds.editMembers(args, "subagent_ids") { member -> repository.loadBlock(member)?.type == BlockType.SUBAGENT },
                skillIds = old.skillIds.editMembers(args, "skill_ids") { member -> repository.loadSkill(member) != null },
                groupIds = old.groupIds.editMembers(args, "group_ids") { member -> member != ALL_GROUP_ID && repository.loadGroup(member) != null },
            )).value
        }
        return saved.toProfileSummaryJson()
    }

    fun deleteProfile(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        loadProfile(id)
        mutateLibrary.deleteProfile(id)
        return deletion(id)
    }

    /**
     * Every target rules can be installed into: each available agent's global file, plus the projects
     * the app knows about. A project the agent is working in may be missing here — [install]
     * registers it on first use, so the list is a starting point, not the only allowed set.
     */
    fun listTargets(@Suppress("UNUSED_PARAMETER") args: JsonObject): JsonElement {
        val config = configStore.load()
        return buildJsonArray {
            availableAgents(config).forEach { agent ->
                add(targetJson(AGENT_TARGET_PREFIX + agent.id, "agent", agent.name, AgentGlobalTarget(agent)))
            }
            config.projects.forEach { path ->
                val target = projectTarget(Path.of(path), config)
                add(targetJson(path, "project", target.name, target))
            }
        }
    }

    fun createRule(args: JsonObject): JsonElement {
        val content = args.requiredString("content")
        val name = formatName(args.requiredString("name"), configStore.load().nameFormat)
        val scope = args.optionalString("scope")?.let(::parseScope)
        val saved = repository.withLock {
            val block = Block(
                id = nextId(name, repository.listBlocks().map { it.id }),
                name = name,
                description = args.optionalString("description").orEmpty(),
                content = content,
                heading = args.optionalString("heading").orEmpty().trim(),
                headingLevel = args.optionalHeadingLevel() ?: DEFAULT_HEADING_LEVEL,
            )
            repository.saveBlock(block).also { repository.syncAllGroup() }
        }
        if (scope != null) assignScope(saved.id, scope)
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun updateRule(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        // Every argument is checked before the block is written: a bad scope must not leave the
        // library holding the new text while the tool reports a failure.
        val scope = args.optionalString("scope")?.let { it to parseScope(it) }
        val name = args.optionalNonBlankString("name")?.let { formatName(it, configStore.load().nameFormat) }
        val content = args.optionalNonBlankString("content")
        val saved = repository.withLock {
            val existing = loadRule(id)
            val updated = existing.copy(
                name = name ?: existing.name,
                description = args.optionalString("description") ?: existing.description,
                content = content ?: existing.content,
                heading = args.optionalString("heading")?.trim() ?: existing.heading,
                headingLevel = args.optionalHeadingLevel() ?: existing.headingLevel,
            )
            repository.saveBlock(updated)
        }
        scope?.let { (_, project) -> assignScope(saved.id, project) }
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun createSubagent(args: JsonObject): JsonElement {
        val content = args.requiredString("content")
        val name = formatName(args.requiredString("name"), configStore.load().nameFormat)
        val saved = repository.withLock {
            val subagent = Block(
                id = nextId(name, repository.listBlocks().map { it.id }),
                name = name,
                description = args.optionalString("description").orEmpty(),
                type = BlockType.SUBAGENT,
                content = content,
                variants = args.subagentVariants(emptyMap()),
            )
            repository.saveBlock(subagent).also { repository.syncAllGroup() }
        }
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun updateSubagent(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        val saved = repository.withLock {
            val existing = loadSubagent(id)
            if (existing.source != null) {
                throw ToolError("Imported subagent '$id' is read-only; use fork_subagent before editing")
            }
            val updated = existing.copy(
                name = args.optionalNonBlankString("name")
                    ?.let { formatName(it, configStore.load().nameFormat) } ?: existing.name,
                description = args.optionalString("description") ?: existing.description,
                content = args.optionalNonBlankString("content") ?: existing.content,
                variants = args.subagentVariants(existing.variants),
            )
            repository.saveBlock(updated)
        }
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun deleteSubagent(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        loadSubagent(id)
        mutateLibrary.deleteBlock(id)
        return buildJsonObject {
            put("id", id)
            put("deleted", true)
        }
    }

    fun createSkill(args: JsonObject): JsonElement {
        val name = args.requiredString("name")
        val description = args.requiredString("description")
        val content = args.requiredString("content")
        val saved = mutateLibrary.createSkill(name, description, content).value
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun updateSkill(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        val existing = repository.loadSkill(id) ?: throw ToolError("No skill with id '$id'")
        if (existing.source != null) {
            throw ToolError("Imported skill '$id' is read-only; use fork_skill before editing")
        }
        val saved = mutateLibrary.saveSkill(
            existing.copy(
                description = args.optionalString("description") ?: existing.description,
                content = args.optionalString("content") ?: existing.content,
            ),
        ).value
        return buildJsonObject {
            put("id", saved.id)
            put("version", saved.version)
        }
    }

    fun deleteSkill(args: JsonObject): JsonElement {
        val id = args.requiredString("id")
        if (repository.loadSkill(id) == null) throw ToolError("No skill with id '$id'")
        mutateLibrary.deleteSkill(id)
        return buildJsonObject {
            put("id", id)
            put("deleted", true)
        }
    }

    /**
     * Puts the named rule, skill or group into one target. Every write and every refusal behind it is
     * [InstallObject], the use case the app writes through as well: one safety policy, so this facade
     * cannot drift from the app on what counts as a hand-edited copy.
     */
    fun install(args: JsonObject): JsonElement {
        args.optionalString("profile_id")?.let { profileId ->
            requireSingleSelector(
                args.optionalString("rule_id"),
                args.optionalString("mcp_id"),
                args.optionalString("group_id"),
                args.optionalString("skill_id"),
                args.optionalString("subagent_id"),
                profileId,
            )
            return installProfile(args.targetArg(), profileId)
        }
        val overwrite = args.optionalBoolean("overwrite") ?: false
        val rawTarget = args.targetArg()
        val (candidate, _) = resolveTarget(rawTarget, register = false)
        val resolved = resolveItems(args)
        // Scope is the one refusal asked about before writing: it decides whether the call has
        // anything to do, and a call that has nothing to do is an error here rather than an empty
        // report. The answer comes from the same policy the write uses.
        val (blocks, skippedScope) = resolved.rules.partition { !policy.outOfScope(InstallItem.Rule(it), candidate) }
        if (resolved.groupId == null && skippedScope.isNotEmpty()) {
            val skipped = skippedScope.single()
            throw ToolError(
                "Rule '${skipped.id}' belongs to '${configStore.load().ruleScopes[skipped.id]}' " +
                    "and cannot be installed into '$rawTarget'",
            )
        }
        if (blocks.isEmpty() && resolved.mcpBlocks.isEmpty() && resolved.subagents.isEmpty() && resolved.skills.isEmpty()) {
            throw ToolError("No installable items in '${resolved.groupId}' apply to '$rawTarget'")
        }
        val (target, registered) =
            if (candidate is ProjectTarget) resolveTarget(rawTarget, register = true) else candidate to false

        val items = blocks.map { InstallItem.Rule(it, resolved.groupId) } +
            resolved.mcpBlocks.map(InstallItem::Mcp) +
            resolved.subagents.map(InstallItem::Subagent) +
            resolved.skills.map(InstallItem::SkillCopy)
        val report = installObject(InstallCommand(listOf(target), items, force = overwrite))
        report.firstError()?.let { throw it }
        val skipped = report.skipped<InstallItem.Rule>(SkipReason.MODIFIED)
        val skippedSkills = report.skipped<InstallItem.SkillCopy>(SkipReason.MODIFIED)
        return buildJsonObject {
            put("installed", report.written<InstallItem.Rule>().toJsonArray())
            put("installed_mcp", report.written<InstallItem.Mcp>().toJsonArray())
            put("installed_subagents", report.written<InstallItem.Subagent>().toJsonArray())
            put("installed_skills", report.written<InstallItem.SkillCopy>().toJsonArray())
            put("skipped_modified", skipped.toJsonArray())
            put("skipped_modified_mcp", report.skipped<InstallItem.Mcp>(SkipReason.MODIFIED).toJsonArray())
            put("skipped_modified_subagents", report.skipped<InstallItem.Subagent>(SkipReason.MODIFIED).toJsonArray())
            put("skipped_modified_skills", skippedSkills.toJsonArray())
            put("skipped_unsupported_mcp", report.skipped<InstallItem.Mcp>(SkipReason.UNSUPPORTED).toJsonArray())
            put("skipped_unsupported_subagents", report.skipped<InstallItem.Subagent>(SkipReason.UNSUPPORTED).toJsonArray())
            put("skipped_unsupported_skills", report.skipped<InstallItem.SkillCopy>(SkipReason.UNSUPPORTED).toJsonArray())
            put("skipped_scope", skippedScope.map { it.id }.toJsonArray())
            if (skipped.isNotEmpty() || skippedSkills.isNotEmpty() ||
                report.skipped<InstallItem.Mcp>(SkipReason.MODIFIED).isNotEmpty() ||
                report.skipped<InstallItem.Subagent>(SkipReason.MODIFIED).isNotEmpty()
            ) {
                put("hint", "Skipped items were changed in the target; resolve in the Ruleblend app or pass overwrite: true")
            }
            put("files", buildJsonArray {
                (target.ownedFiles() + resolved.subagents.flatMap { subagent ->
                    subagentService?.files(target, subagent.id).orEmpty()
                }).distinct().forEach { add(JsonPrimitive(it.toString())) }
            })
            if (registered) put("registered_project", true)
        }
    }

    /**
     * Takes the named rule, skill or group out of one target, through the same [RemoveObject] the app
     * removes with. Only what is installed here is named, and a copy edited by hand after Ruleblend
     * wrote it stays: the caller asked for the object to be gone, not for that edit to be thrown
     * away — that decision is taken on the file, in the app.
     */
    fun uninstall(args: JsonObject): JsonElement {
        args.optionalString("profile_id")?.let { profileId ->
            requireSingleSelector(
                args.optionalString("rule_id"),
                args.optionalString("mcp_id"),
                args.optionalString("group_id"),
                args.optionalString("skill_id"),
                args.optionalString("subagent_id"),
                profileId,
            )
            return uninstallProfile(args.targetArg(), profileId)
        }
        val (target, _) = resolveTarget(args.targetArg(), register = false)
        val ruleId = args.optionalString("rule_id")
        val mcpId = args.optionalString("mcp_id")
        val groupId = args.optionalString("group_id")
        val skillId = args.optionalString("skill_id")
        val subagentId = args.optionalString("subagent_id")
        requireSingleSelector(ruleId, mcpId, groupId, skillId, subagentId)
        val installedRegions = installedRegions(target)
        val ruleIds = when {
            ruleId != null -> listOf(ruleId).filter { it in installedRegions }
            groupId != null -> {
                val group = repository.loadGroup(groupId) ?: throw ToolError("No group with id '$groupId'")
                TargetStatus.groupBlocksToRemove(group, installedRegions)
            }
            else -> emptyList()
        }
        val namedSkills = when {
            skillId != null -> listOf(repository.loadSkill(skillId) ?: throw ToolError("No skill with id '$skillId'"))
            groupId != null -> repository.loadGroup(groupId).orThrow(groupId).skillIds.mapNotNull(repository::loadSkill)
            else -> emptyList()
        }
        val namedMcp = when {
            mcpId != null -> listOf(loadMcp(mcpId))
            groupId != null -> repository.loadGroup(groupId).orThrow(groupId).blockIds
                .mapNotNull(repository::loadBlock)
                .filter { it.type == BlockType.MCP }
            else -> emptyList()
        }
        val namedSubagents = when {
            subagentId != null -> listOf(loadSubagent(subagentId))
            groupId != null -> repository.loadGroup(groupId).orThrow(groupId).blockIds
                .mapNotNull(repository::loadBlock)
                .filter { it.type == BlockType.SUBAGENT }
            else -> emptyList()
        }
        val installer = skillService
        val items = ruleIds.map { ruleItem(it, installedRegions) } +
            namedMcp.map(InstallItem::Mcp) +
            namedSubagents.map(InstallItem::Subagent) +
            namedSkills.filter { installer?.status(target, it) != null }.map(InstallItem::SkillCopy)
        val report = removeObject(InstallCommand(listOf(target), items))
        report.firstError()?.let { throw it }
        val skipped = report.skipped<InstallItem.Rule>(SkipReason.MODIFIED)
        val skippedSkills = report.skipped<InstallItem.SkillCopy>(SkipReason.MODIFIED)
        return buildJsonObject {
            put("removed", report.written<InstallItem.Rule>().toJsonArray())
            put("removed_mcp", report.written<InstallItem.Mcp>().toJsonArray())
            put("removed_subagents", report.written<InstallItem.Subagent>().toJsonArray())
            put("removed_skills", report.written<InstallItem.SkillCopy>().toJsonArray())
            put("skipped_modified", skipped.toJsonArray())
            put("skipped_modified_mcp", report.skipped<InstallItem.Mcp>(SkipReason.MODIFIED).toJsonArray())
            put("skipped_modified_subagents", report.skipped<InstallItem.Subagent>(SkipReason.MODIFIED).toJsonArray())
            put("skipped_modified_skills", skippedSkills.toJsonArray())
            put("skipped_unsupported_subagents", report.skipped<InstallItem.Subagent>(SkipReason.UNSUPPORTED).toJsonArray())
            if (skipped.isNotEmpty() || skippedSkills.isNotEmpty() ||
                report.skipped<InstallItem.Mcp>(SkipReason.MODIFIED).isNotEmpty() ||
                report.skipped<InstallItem.Subagent>(SkipReason.MODIFIED).isNotEmpty()
            ) {
                put("hint", "Items changed in the target were kept; remove them from the Ruleblend app")
            }
        }
    }

    fun targetStatus(args: JsonObject): JsonElement {
        val (target, _) = resolveTarget(args.targetArg(), register = false)
        return buildJsonArray {
            installedRegions(target).forEach { (id, region) ->
                val block = repository.loadBlock(id)
                add(
                    buildJsonObject {
                        put("id", id)
                        put("installed_version", region.version)
                        put(
                            "status",
                            when {
                                block == null -> "not-in-library"
                                else -> when (service.status(target, block)) {
                                    InstallStatus.SYNCED -> "synced"
                                    InstallStatus.UPDATE_AVAILABLE -> "update-available"
                                    InstallStatus.MODIFIED -> "modified"
                                    null -> "not-installed"
                                }
                            },
                        )
                    },
                )
            }
            repository.listBlocks().filter { it.type == BlockType.MCP }.forEach { block ->
                val status = mcpService.status(target, block) ?: return@forEach
                add(nativeStatusJson(block.id, "mcp", JsonPrimitive(block.version), status.status))
            }
            repository.listBlocks().filter { it.type == BlockType.SUBAGENT }.forEach { block ->
                val status = subagentService?.status(target, block) ?: return@forEach
                add(nativeStatusJson(block.id, "subagent", JsonPrimitive(block.version), status.status))
            }
            repository.listSkills().forEach { skill ->
                val status = skillService?.status(target, skill) ?: return@forEach
                add(nativeStatusJson(skill.id, "skill", JsonPrimitive(skill.version), status.status))
            }
        }
    }

    /** One `target_status` row for an item that lives in a native config or directory, not a managed region. */
    private fun nativeStatusJson(id: String, kind: String, libraryVersion: JsonPrimitive, status: InstallStatus): JsonObject =
        buildJsonObject {
            put("id", id)
            put("kind", kind)
            put("library_version", libraryVersion)
            put("status", status.name.lowercase().replace('_', '-'))
        }

    /**
     * The target a tool call names: `agent:<id>` for an agent's global instruction file, anything
     * else is a project directory. The second component reports whether an unknown project was
     * registered — agent targets are never registered, they exist as long as the agent does.
     */
    private fun resolveTarget(raw: String, register: Boolean): Pair<Target, Boolean> {
        if (raw.startsWith(AGENT_TARGET_PREFIX)) return resolveAgentTarget(raw) to false
        return resolveProjectTarget(raw, register)
    }

    private fun resolveAgentTarget(raw: String): AgentGlobalTarget {
        val id = raw.removePrefix(AGENT_TARGET_PREFIX)
        val available = availableAgents(configStore.load())
        val agent = available.find { it.id == id } ?: throw ToolError(
            "No agent target '$raw'. Available: " +
                available.joinToString { AGENT_TARGET_PREFIX + it.id }.ifEmpty { "none" },
        )
        return AgentGlobalTarget(agent)
    }

    /**
     * The project as the GUI would build it: available agents minus the ones disabled for this
     * project (mirrors IntegrationModel.load). With [register], an unknown path is added to the
     * config so the project shows up in the app's sidebar.
     */
    private fun resolveProjectTarget(rawPath: String, register: Boolean): Pair<ProjectTarget, Boolean> {
        val dir = Path.of(rawPath)
        if (!dir.isAbsolute) throw ToolError("target must be '$AGENT_TARGET_PREFIX<agent>' or an absolute path: '$rawPath'")
        if (!dir.isDirectory()) throw ToolError("target is not an existing directory: '$rawPath'")
        val path = dir.toAbsolutePath().normalize()
        val key = path.projectKey()

        var registered = false
        val config = if (register) {
            configStore.update { current ->
                if (key in current.projects) current
                else current.copy(projects = current.projects + key).also { registered = true }
            }
        } else {
            configStore.load()
        }

        val target = projectTarget(path, config)
        if (target.agents.isEmpty()) throw ToolError("No enabled agents for '$path' — nothing to write to")
        return target to registered
    }

    private fun projectTarget(path: Path, config: AppConfig): ProjectTarget {
        val disabled = config.disabledAgents[path.projectKey()].orEmpty()
        return ProjectTarget(path, availableAgents(config).filter { it.id !in disabled })
    }

    /** Agents present on this machine and not hidden in Settings — the GUI's own filter. */
    private fun availableAgents(config: AppConfig): List<AgentAdapter> =
        agents.filter { it.isAvailable() && it.id !in config.hiddenAgents }

    /**
     * Rule blocks, MCP blocks and skills are kept apart on purpose: a single untyped list is how an
     * MCP server config would reach [IntegrationService] and end up as JSON inside an instruction
     * file.
     */
    private data class ResolvedItems(
        val rules: List<Block>,
        val mcpBlocks: List<Block>,
        val subagents: List<Block>,
        val skills: List<Skill>,
        val groupId: String?,
    )

    /** The single rule/skill, or every item of the group, that the install call names. */
    private fun resolveItems(args: JsonObject): ResolvedItems {
        val ruleId = args.optionalString("rule_id")
        val mcpId = args.optionalString("mcp_id")
        val groupId = args.optionalString("group_id")
        val skillId = args.optionalString("skill_id")
        val subagentId = args.optionalString("subagent_id")
        requireSingleSelector(ruleId, mcpId, groupId, skillId, subagentId, args.optionalString("profile_id"))
        return when {
            ruleId != null -> ResolvedItems(listOf(loadRule(ruleId)), emptyList(), emptyList(), emptyList(), null)
            mcpId != null -> ResolvedItems(emptyList(), listOf(loadMcp(mcpId)), emptyList(), emptyList(), null)
            subagentId != null -> ResolvedItems(emptyList(), emptyList(), listOf(loadSubagent(subagentId)), emptyList(), null)
            skillId != null -> {
                val skill = repository.loadSkill(skillId) ?: throw ToolError("No skill with id '$skillId'")
                ResolvedItems(emptyList(), emptyList(), emptyList(), listOf(skill), null)
            }
            groupId != null -> {
                val group = repository.loadGroup(groupId) ?: throw ToolError("No group with id '$groupId'")
                val members = group.blockIds.mapNotNull { repository.loadBlock(it) }
                val rules = members.filter { it.type == BlockType.RULE }
                val mcpBlocks = members.filter { it.type == BlockType.MCP }
                val subagents = members.filter { it.type == BlockType.SUBAGENT }
                val skills = group.skillIds.mapNotNull { repository.loadSkill(it) }
                if (rules.isEmpty() && mcpBlocks.isEmpty() && subagents.isEmpty() && skills.isEmpty()) {
                    throw ToolError("Group '$groupId' has no items")
                }
                ResolvedItems(rules, mcpBlocks, subagents, skills, groupId)
            }
            else -> throw ToolError("Pass rule_id, mcp_id, subagent_id, skill_id or group_id")
        }
    }

    /**
     * The rule tools speak about [BlockType.RULE] only. An MCP block lives in the same library but
     * is a different object: its body is a server config, not instruction text, so writing it into
     * an instruction file would leave JSON inside a managed region and change no MCP config at all.
     */
    private fun loadRule(id: String): Block {
        val block = repository.loadBlock(id) ?: throw ToolError("No rule with id '$id'")
        if (block.type != BlockType.RULE) {
            throw ToolError("'$id' is a ${block.type.name.lowercase()}, not a rule")
        }
        return block
    }

    private fun loadMcp(id: String): Block {
        val block = repository.loadBlock(id) ?: throw ToolError("No MCP server config with id '$id'")
        if (block.type != BlockType.MCP) {
            throw ToolError("'$id' is a ${block.type.name.lowercase()}, not an MCP server config")
        }
        return block
    }

    private fun loadSubagent(id: String): Block {
        val block = repository.loadBlock(id) ?: throw ToolError("No subagent with id '$id'")
        if (block.type != BlockType.SUBAGENT) {
            throw ToolError("'$id' is a ${block.type.name.lowercase()}, not a subagent")
        }
        return block
    }

    private fun requireSingleSelector(
        ruleId: String?,
        mcpId: String?,
        groupId: String?,
        skillId: String?,
        subagentId: String?,
        profileId: String? = null,
    ) {
        if (listOfNotNull(ruleId, mcpId, groupId, skillId, subagentId, profileId).size != 1) {
            throw ToolError("Pass exactly one of rule_id, mcp_id, subagent_id, skill_id, group_id or profile_id")
        }
    }

    /** Attaches a portable profile to a project, enables it, and reconciles its managed objects. */
    private fun installProfile(rawTarget: String, profileId: String): JsonElement {
        return projects.attach(buildJsonObject { put("target", rawTarget); put("profile_id", profileId) })
    }

    /** Disables and detaches a profile before reconciling the project back to its remaining bindings. */
    private fun uninstallProfile(rawTarget: String, profileId: String): JsonElement {
        val (target, _) = resolveTarget(rawTarget, register = false)
        if (target !is ProjectTarget) throw ToolError("Profiles can only be uninstalled from a project target")
        configStore.update { config ->
            val key = target.dir.projectKey()
            val bindings = config.projectProfiles[key].orEmpty()
            if (profileId !in bindings.map { it.id }) throw ToolError("Profile '$profileId' is not attached to '$rawTarget'")
            config.copy(projectProfiles = config.projectProfiles + (key to bindings.filterNot { it.id == profileId }))
        }
        return reconcileProfiles(profileId, attached = false, active = false, report = reconcileProfiles(target))
    }

    /** Profile reconciliation reports native changes under the same per-kind keys as install/uninstall. */
    private fun reconcileProfiles(
        profileId: String,
        attached: Boolean,
        active: Boolean,
        report: ProfileReconcileReport,
        registered: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("profile_id", profileId)
        put("attached", attached)
        put("active", active)
        profileOutcome("installed", ProfileReconcileOutcome.Installed, report)
        profileOutcome("removed", ProfileReconcileOutcome.Removed, report)
        profileOutcome("retagged", ProfileReconcileOutcome.Retagged, report)
        profileSkipped("skipped_modified", report)
        profileSkipped("skipped_out_of_scope", report, SkipReason.OUT_OF_SCOPE)
        profileSkipped("skipped_unsupported", report, SkipReason.UNSUPPORTED)
        put("missing_profiles", report.missingProfiles.toJsonArray())
        put("failed", buildJsonArray {
            report.entries.filter { it.outcome is ProfileReconcileOutcome.Failed }.forEach { entry ->
                add(
                    buildJsonObject {
                        put("id", entry.item.id)
                        put("kind", entry.item.kind.name.lowercase())
                        put("error", (entry.outcome as ProfileReconcileOutcome.Failed).error.message ?: "Unknown error")
                    },
                )
            }
        })
        if (registered) put("registered_project", true)
    }

    private fun JsonObjectBuilder.profileOutcome(
        prefix: String,
        outcome: ProfileReconcileOutcome,
        report: ProfileReconcileReport,
    ) {
        ProfileObjectKind.entries.forEach { kind ->
            val key = when (kind) {
                ProfileObjectKind.RULE -> prefix
                ProfileObjectKind.MCP -> "${prefix}_mcp"
                ProfileObjectKind.SKILL -> "${prefix}_skills"
                ProfileObjectKind.SUBAGENT -> "${prefix}_subagents"
            }
            put(key, report.entries.filter { it.item.kind == kind && it.outcome == outcome }.map { it.item.id }.toJsonArray())
        }
    }

    private fun JsonObjectBuilder.profileSkipped(prefix: String, report: ProfileReconcileReport, reason: SkipReason = SkipReason.MODIFIED) {
        ProfileObjectKind.entries.forEach { kind ->
            val key = when (kind) {
                ProfileObjectKind.RULE -> prefix
                ProfileObjectKind.MCP -> "${prefix}_mcp"
                ProfileObjectKind.SKILL -> "${prefix}_skills"
                ProfileObjectKind.SUBAGENT -> "${prefix}_subagents"
            }
            put(
                key,
                report.entries.filter {
                    it.item.kind == kind && (it.outcome as? ProfileReconcileOutcome.Skipped)?.reason == reason
                }.map { it.item.id }.toJsonArray(),
            )
        }
    }

    private fun dev.ruleblend.core.model.Group?.orThrow(id: String): dev.ruleblend.core.model.Group =
        this ?: throw ToolError("No group with id '$id'")

    private fun installedRegions(target: Target): Map<String, ManagedRegion> =
        target.files().flatMap { service.regions(it) }.associateBy { it.id }

    /**
     * One installed rule as the write item for taking it out. A rule the library no longer holds
     * stands for its own region: there is nothing left to compare it against, and a hand edit is
     * still recognised by the region's own hash.
     */
    private fun ruleItem(id: String, regions: Map<String, ManagedRegion>): InstallItem.Rule {
        val region = regions.getValue(id)
        val block = repository.loadBlock(id)
            ?: Block(id = id, name = id, version = region.version, content = region.content)
        return InstallItem.Rule(block, region.group)
    }

    /** `global` clears a project assignment; every other value must name an existing project. */
    private fun parseScope(raw: String): Path? {
        if (raw == "global") return null
        val project = Path.of(raw)
        if (!project.isAbsolute || !project.isDirectory()) {
            throw ToolError("scope must be 'global' or an absolute existing project directory: '$raw'")
        }
        return project.toAbsolutePath().normalize()
    }

    private fun assignScope(blockId: String, project: Path?) {
        configStore.update { current ->
            val scoped = current.withRuleScope(blockId, project)
            if (project == null || project.projectKey() in scoped.projects) scoped
            else scoped.copy(projects = scoped.projects + project.projectKey())
        }
    }

    private fun Block.toSummaryJson(scopes: Map<String, String>): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("scope", scopes[id] ?: "global")
    }

    private fun Block.toSubagentSummaryJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        putVariants(this@toSubagentSummaryJson)
        putProvenance(this@toSubagentSummaryJson)
    }

    private fun Block.toSubagentJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("content", content)
        putVariants(this@toSubagentJson)
        putProvenance(this@toSubagentJson)
    }

    /** Per-assistant fields, the only part of a subagent that differs between assistants. */
    private fun JsonObjectBuilder.putVariants(subagent: Block) {
        if (subagent.variants.isEmpty()) return
        put(
            "variants",
            buildJsonObject {
                subagent.variants.forEach { (agentId, variant) ->
                    put(agentId, buildJsonObject { variant.fields.forEach { (key, value) -> put(key, value) } })
                }
            },
        )
    }

    /** Ids of the agents that can host a subagent at all; the only keys `variants` accepts. */
    private fun subagentAgentIds(): List<String> =
        agents.filter { it.subagentSupport == SubagentSupport.SUPPORTED }.map { it.id }

    /**
     * The stored variants updated by this call: `variants` replaces one assistant's fields outright
     * (an empty object clears them), while `model` is a shortcut that sets the same preference for
     * every assistant, applied first so an explicit variant still wins.
     */
    private fun JsonObject.subagentVariants(existing: Map<String, SubagentVariant>): Map<String, SubagentVariant> {
        val supported = subagentAgentIds()
        var result = LinkedHashMap(existing)
        if ("model" in this) {
            val model = optionalModel()
            supported.forEach { agentId ->
                val updated = (result[agentId] ?: SubagentVariant.EMPTY).with(SUBAGENT_FIELD_MODEL, model)
                if (updated.isEmpty) result.remove(agentId) else result[agentId] = updated
            }
        }
        val requested = this["variants"] ?: return result
        val byAgent = requested as? JsonObject
            ?: throw ToolError("'variants' must be an object mapping an agent id to its fields")
        byAgent.forEach { (agentId, fields) ->
            if (agentId !in supported) {
                throw ToolError("Agent '$agentId' has no subagent support; use one of ${supported.joinToString()}")
            }
            val values = fields as? JsonObject
                ?: throw ToolError("Fields of '$agentId' must be an object of field name to text value")
            val variant = SubagentVariant(
                values.entries.associateTo(linkedMapOf()) { (key, value) ->
                    key to ((value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw ToolError("Field '$key' of '$agentId' must be a string"))
                },
            )
            if (variant.isEmpty) result.remove(agentId) else result[agentId] = variant
        }
        return result
    }

    private fun Profile.toProfileSummaryJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("block_ids", blockIds.toJsonArray())
        put("rule_ids", blockIds.filter { repository.loadBlock(it)?.type == BlockType.RULE }.toJsonArray())
        put("mcp_ids", blockIds.filter { repository.loadBlock(it)?.type == BlockType.MCP }.toJsonArray())
        put("skill_ids", skillIds.toJsonArray())
        put("subagent_ids", subagentIds.toJsonArray())
        put("group_ids", groupIds.toJsonArray())
    }

    private fun Block.toMcpJson(includeConfig: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        val config = McpConfigCodec.parse(content).getOrNull()
        if (config == null) {
            put("valid", false)
        } else {
            put("transport", if (config is McpServerConfig.Stdio) "stdio" else "http")
            if (includeConfig) when (config) {
                is McpServerConfig.Stdio -> {
                    put("command", config.command)
                    put("args", config.args.toJsonArray())
                    put("env", config.env.redacted())
                }
                is McpServerConfig.Http -> {
                    put("url", config.url)
                    put("headers", config.headers.redacted())
                }
            }
        }
    }

    private fun Map<String, String>.redacted(): JsonObject = buildJsonObject {
        keys.forEach { put(it, "***") }
    }

    private fun JsonObject.mcpConfig(existing: McpServerConfig?): McpServerConfig {
        val transport = optionalString("transport") ?: when (existing) {
            is McpServerConfig.Stdio -> "stdio"
            is McpServerConfig.Http -> "http"
            null -> throw ToolError("Missing required argument 'transport'")
        }
        val config = when (transport) {
            "stdio" -> {
                if ("url" in this || "headers" in this) throw ToolError("url and headers require http transport")
                val old = existing as? McpServerConfig.Stdio
                McpServerConfig.Stdio(
                    command = optionalString("command") ?: old?.command.orEmpty(),
                    args = stringArray("args") ?: old?.args.orEmpty(),
                    env = stringMap("env") ?: old?.env.orEmpty(),
                )
            }
            "http" -> {
                if ("command" in this || "args" in this || "env" in this) throw ToolError("command, args and env require stdio transport")
                val old = existing as? McpServerConfig.Http
                McpServerConfig.Http(
                    url = optionalString("url") ?: old?.url.orEmpty(),
                    headers = stringMap("headers") ?: old?.headers.orEmpty(),
                )
            }
            else -> throw ToolError("transport must be 'stdio' or 'http'")
        }
        val errors = config.validationErrors()
        if (errors.isNotEmpty()) throw ToolError("Invalid MCP server config: ${errors.joinToString()}")
        return config
    }

    private fun Group.toGroupJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("version", version)
        put("block_ids", blockIds.toJsonArray())
        put("rule_ids", blockIds.filter { repository.loadBlock(it)?.type == BlockType.RULE }.toJsonArray())
        put("mcp_ids", blockIds.filter { repository.loadBlock(it)?.type == BlockType.MCP }.toJsonArray())
        put("subagent_ids", blockIds.filter { repository.loadBlock(it)?.type == BlockType.SUBAGENT }.toJsonArray())
        put("skill_ids", skillIds.toJsonArray())
    }

    private fun loadEditableGroup(id: String): Group {
        if (id == ALL_GROUP_ID) throw ToolError("Group '$id' is generated and read-only")
        return repository.loadGroup(id) ?: throw ToolError("No group with id '$id'")
    }

    private fun loadProfile(id: String): Profile =
        repository.loadProfile(id) ?: throw ToolError("No profile with id '$id'")

    private fun List<String>.editMembers(args: JsonObject, key: String, exists: (String) -> Boolean): List<String> {
        val add = (args["add"] as? JsonObject)?.stringArray(key).orEmpty()
        val remove = (args["remove"] as? JsonObject)?.stringArray(key).orEmpty()
        add.forEach { if (!exists(it)) throw ToolError("No eligible $key member with id '$it'") }
        remove.forEach { if (it !in this || !exists(it)) throw ToolError("No eligible $key member with id '$it' in this object") }
        return (this.filterNot { it in remove } + add).distinct()
    }

    private fun JsonObject.validateMembershipMaps(allowed: Set<String>) {
        listOf("add", "remove").forEach { operation ->
            val value = this[operation] ?: return@forEach
            val changes = value as? JsonObject ?: throw ToolError("'$operation' must be an object of typed id arrays")
            changes.keys.forEach { key ->
                if (key !in allowed) throw ToolError("Unknown member type '$key' in '$operation'")
                changes.stringArray(key)
            }
        }
    }

    private fun List<String>.editBlockMembers(args: JsonObject, types: List<BlockType>): List<String> {
        var result = this
        types.forEach { type ->
            val key = when (type) {
                BlockType.RULE -> "rule_ids"
                BlockType.MCP -> "mcp_ids"
                BlockType.SUBAGENT -> "subagent_ids"
            }
            result = result.editMembers(args, key) { member -> repository.loadBlock(member)?.type == type }
        }
        return result
    }

    private fun identity(id: String, version: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("version", version)
    }

    private fun deletion(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("deleted", true)
    }

    private fun targetJson(id: String, kind: String, name: String, target: Target): JsonObject = buildJsonObject {
        put("id", id)
        put("kind", kind)
        put("name", name)
        put("files", buildJsonArray { target.ownedFiles().forEach { add(JsonPrimitive(it.toString())) } })
    }
}

private fun JsonObject.optionalString(name: String): String? = this[name]?.let {
    (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
        ?: throw ToolError("Argument '$name' must be a string")
}

/**
 * An update argument that was given but blank: refused, the same way a create refuses it. Absent
 * means "keep what is there"; blank would otherwise wipe the name or the text of a rule.
 */
private fun JsonObject.optionalNonBlankString(name: String): String? = optionalString(name)?.also {
    if (it.isBlank()) throw ToolError("Argument '$name' must not be blank")
}

private fun JsonObject.requiredString(name: String): String =
    optionalString(name)?.takeIf { it.isNotBlank() } ?: throw ToolError("Missing required argument '$name'")

/** `project_path` is the pre-1.11 name of `target`; accepted so older callers keep working. */
private fun JsonObject.targetArg(): String =
    (optionalString("target") ?: optionalString("project_path"))?.takeIf { it.isNotBlank() }
        ?: throw ToolError("Missing required argument 'target'")

private fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.let {
    (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
        ?: throw ToolError("Argument '$name' must be a boolean")
}

private fun JsonObject.stringArray(name: String): List<String>? {
    val value = this[name] ?: return null
    val array = value as? JsonArray ?: throw ToolError("'$name' must be an array of strings")
    return array.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw ToolError("'$name' must contain only strings") }
}

private fun JsonObject.stringMap(name: String): Map<String, String>? {
    val value = this[name] ?: return null
    val obj = value as? JsonObject ?: throw ToolError("'$name' must be an object of string values")
    return obj.mapValues { (key, item) -> (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw ToolError("'$name.$key' must be a string") }
}

/** Heading depth as the caller gave it, refused outside markdown's 1..6 rather than silently clamped. */
private fun JsonObject.optionalHeadingLevel(): Int? {
    val value = this["heading_level"] ?: return null
    val level = (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        ?: throw ToolError("heading_level must be an integer")
    if (level !in 1..6) throw ToolError("heading_level must be between 1 and 6")
    return level
}

private fun JsonObject.optionalModel(): String? = optionalString("model")?.takeIf { it.isNotBlank() }

/** Every per-assistant value, so a search for a model name still finds the subagent carrying it. */
private fun Block.variantValues(): String = variants.values.flatMap { it.fields.values }.joinToString(" ")

/**
 * The ids of one kind of object the report says were written. The tool answers per kind — a caller
 * asked for rules or for skills — while the use case reports per pair, so the split happens here and
 * the write decision stays in one place.
 */
private inline fun <reified T : InstallItem> WriteReport.written(): List<String> =
    idsOf<T> { it is WriteOutcome.Written }

/** The ids of one kind refused for [reason]; a reason that did not occur yields an empty list. */
private inline fun <reified T : InstallItem> WriteReport.skipped(reason: SkipReason): List<String> =
    idsOf<T> { it is WriteOutcome.Skipped && it.reason == reason }

private inline fun <reified T : InstallItem> WriteReport.idsOf(match: (WriteOutcome) -> Boolean): List<String> =
    entries.filter { it.item is T && match(it.outcome) }.map { it.item.id }.distinct()

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
