package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.ManagedDocument
import dev.ruleblend.core.integration.ManagedRegion
import dev.ruleblend.core.integration.McpBlockService
import dev.ruleblend.core.integration.McpFileEntry
import dev.ruleblend.core.integration.OrphanRemoval
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillDirEntry
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SubagentFileEntry
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.Target
import dev.ruleblend.core.integration.regionFor
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.RESERVED_MCP_SERVER_NAME
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.model.validSkillName
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.storage.skillTreeFingerprint
import dev.ruleblend.core.usecase.AcceptLocalChange
import dev.ruleblend.mcp.install.McpInstallService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Physical entries are selected from discovery, never by an arbitrary caller-supplied path. */
internal class TargetEntryTools(
    private val repository: LibraryRepository,
    private val config: ConfigStore,
    private val rules: IntegrationService,
    private val mcp: McpBlockService,
    private val skills: SkillInstallService?,
    private val subagents: SubagentInstallService?,
    private val resolve: (String) -> Target,
) {
    private data class Entry(
        val key: String,
        val kind: String,
        val id: String,
        val path: Path,
        val origin: PlaceEntryOrigin,
        val text: String,
        val region: ManagedRegion? = null,
        val skill: SkillDirEntry? = null,
        val subagent: SubagentFileEntry? = null,
        val server: McpFileEntry? = null,
    )

    fun list(args: JsonObject): JsonElement = operation {
        val target = target(args)
        FileReadScope.reading { buildJsonArray {
            entries(target).forEach { entry -> add(buildJsonObject {
                put("entry_key", entry.key)
                put("kind", entry.kind)
                put("id", entry.id)
                put("file", entry.path.toString())
                put("origin", entry.origin.name.lowercase())
                entry.region?.let {
                    put("installed_version", it.version)
                    it.origin?.let { origin -> put("installed_origin", origin) }
                }
                if (entry.kind == "rule" && entry.origin == PlaceEntryOrigin.FOREIGN) {
                    put("replaceable", entry.path in target.ownedFiles())
                }
                entry.server?.let { put("agent_id", it.agentId) }
                entry.subagent?.let { put("agent_id", it.agentId) }
                if (entry.kind == "rule") put("file_revision", fileRevision(entry.path))
            }) }
        } }
    }

    fun read(args: JsonObject): JsonElement = operation {
        val target = target(args)
        val entry = selected(target, args)
        val snapshot = entry.skill?.let { requireNotNull(skills).capture(target, it, entry.id) }
        val original = entry.skill?.let { librarySkillSnapshot(entry.id) }
        val library = if (entry.skill != null) original?.skill?.content else libraryText(target, entry)
        val reviewedRevision = if (snapshot != null) revision(entry, skillTreeFingerprint(snapshot.files),
            original?.let { skillTreeFingerprint(it.files) }) else revision(entry, entry.text, library)
        buildJsonObject {
            put("entry_key", entry.key)
            put("kind", entry.kind)
            put("id", entry.id)
            put("origin", entry.origin.name.lowercase())
            put("revision", reviewedRevision)
            put("installed_content", snapshot?.skill?.content ?: entry.text)
            entry.server?.config?.let { put("comparison_content", McpConfigCodec.serialize(it)) }
            library?.let { put("library_content", it) }
            if (entry.kind != "skill") put("differs", library != null && library != comparableText(entry))
            snapshot?.let {
                val local = it.files.associateBy { file -> file.path }
                val saved = original?.files.orEmpty().associateBy { file -> file.path }
                put("files", buildJsonArray {
                    (local.keys + saved.keys).sorted().forEach { path -> add(buildJsonObject {
                        val installed = local[path]
                        val stored = saved[path]
                        put("path", path)
                        put("change", when {
                            installed == null -> "missing"
                            stored == null -> "added"
                            installed != stored -> "modified"
                            else -> "same"
                        })
                        installed?.let { file ->
                            put("size_bytes", file.bytes.size)
                            put("executable", file.executable)
                        }
                    }) }
                })
                args["path"]?.let { value ->
                    val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: throw ToolError("'path' must be a string")
                    val file = local[path] ?: throw ToolError("No installed skill file '$path'")
                    val encoding = args.optionalText("encoding") ?: "utf8"
                    require(encoding in setOf("utf8", "base64")) { "encoding must be utf8 or base64" }
                    put("encoding", encoding)
                    put("content", if (encoding == "base64") Base64.getEncoder().encodeToString(file.bytes)
                        else file.bytes.decodeToString(throwOnInvalidSequence = true))
                    saved[path]?.let { stored -> put("library_file_content",
                        if (encoding == "base64") Base64.getEncoder().encodeToString(stored.bytes)
                        else stored.bytes.decodeToString(throwOnInvalidSequence = true)) }
                }
            }
        }
    }

    fun accept(args: JsonObject): JsonElement = mutation(args) { target, entry ->
        require(entry.origin == PlaceEntryOrigin.MANAGED) { "Only a managed entry can publish local changes" }
        require(entry.kind in setOf("rule", "mcp")) { "Only rules and MCP configs support accepting local changes" }
        val block = requireNotNull(repository.loadBlock(entry.id)) { "Library object disappeared" }
        val saved = AcceptLocalChange(rules, mcp, repository)(target, block, entry.server?.agentId)
        identity(saved.id)
    }

    fun save(args: JsonObject): JsonElement = mutation(args) { target, entry ->
        require(entry.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED, PlaceEntryOrigin.ORPHAN)) {
            "Save requires a foreign or orphaned entry"
        }
        val orphan = entry.origin == PlaceEntryOrigin.ORPHAN
        val id = args.optionalText("id") ?: entry.id
        require(id.isNotBlank()) { "A library id is required" }
        require(!args.flag("replace") || entry.kind == "rule" && !orphan) { "replace is only supported for foreign rules" }
        require(!args.flag("replace") || entry.path in target.ownedFiles()) { "Referenced instruction files are copy-only" }
        require(!orphan || id == entry.id) { "Restore an orphan under its recorded id" }
        if (entry.kind == "skill") {
            require(validSkillName(id)) { "Invalid skill id" }
            require(repository.loadSkill(id) == null) { "Skill '$id' exists; use take_ownership" }
            require(orphan || repository.listSkills().none { it.id == entry.skill!!.id }) { "The original skill id exists; use take_ownership" }
            val saved = repository.writeSkill(requireNotNull(skills).capture(target, requireNotNull(entry.skill), id))
            try {
                if (!orphan) rollbackSkill(saved.id) { skills.takeOwnership(target, entry.skill, saved) }
            } finally { repository.syncAllGroup() }
        } else {
            require(repository.loadBlock(id) == null) { "Block '$id' exists; use take_ownership" }
            require(orphan || repository.listBlocks().none { it.id == entry.id }) { "The original block id exists; use take_ownership" }
            val name = formatName(args.optionalText("name") ?: id, config.load().nameFormat)
            require(name.isNotBlank()) { "Name must not be blank" }
            val block = when (entry.kind) {
                "mcp" -> Block(id, name, type = BlockType.MCP, content = McpConfigCodec.serialize(
                    requireNotNull(entry.server?.config) { "Unsupported MCP config" }))
                "subagent" -> {
                    val source = requireNotNull(entry.subagent)
                    val draft = requireNotNull(source.meta) { "Unsupported subagent format" }
                    Block(id, formatName(draft.name ?: name, config.load().nameFormat),
                        description = draft.description.orEmpty(), type = BlockType.SUBAGENT,
                        content = draft.content, variants = mapOf(source.agentId to draft.toVariant()))
                }
                else -> Block(id, name, content = entry.text)
            }
            val saved = repository.saveBlock(block)
            // A failed target write leaves the committed library rule available, like GUI adoption.
            try {
                if (entry.kind == "rule") config.update { it.withRuleScope(saved.id, (target as? ProjectTarget)?.dir) }
                if (entry.kind == "rule" && !orphan && args.flag("replace")) {
                    rules.adoptSections(entry.path, listOf(ManagedDocument.AdoptStep.Replace(entry.text, regionFor(saved, null))))
                    rules.syncRedirects(target)
                } else if (!orphan && entry.kind != "rule") rollbackBlock(saved.id) {
                    require(entries(target).find { it.key == entry.key }?.text == entry.text) { "Entry changed before ownership could be recorded" }
                    when (entry.kind) {
                        "mcp" -> nativeMcp().takeOwnership(target, requireNotNull(entry.server), saved)
                        "subagent" -> requireNotNull(subagents).takeOwnership(target, requireNotNull(entry.subagent), saved)
                    }
                }
            } finally { repository.syncAllGroup() }
        }
        identity(id)
    }

    fun manage(args: JsonObject): JsonElement = mutation(args) { target, entry ->
        val action = args.text("action")
        when (action) {
            "take_ownership" -> {
                require(entry.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED)) { "Entry is already owned" }
                when (entry.kind) {
                    "skill" -> requireNotNull(skills).takeOwnership(target, requireNotNull(entry.skill),
                        requireNotNull(repository.loadSkill(entry.id)) { "No matching library skill" }, sameAsLibrary = false)
                    "mcp" -> nativeMcp().takeOwnership(target, requireNotNull(entry.server), matchingBlock(entry))
                    "subagent" -> requireNotNull(subagents).takeOwnership(target, requireNotNull(entry.subagent), matchingBlock(entry))
                    else -> throw ToolError("Use save_target_entry with replace for foreign rules")
                }
            }
            "release" -> {
                require(entry.origin in setOf(PlaceEntryOrigin.MANAGED, PlaceEntryOrigin.ORPHAN)) { "Entry is not owned" }
                when (entry.kind) {
                    "skill" -> requireNotNull(skills).release(target, entry.id)
                    "mcp" -> nativeMcp().release(target, entry.id)
                    "subagent" -> requireNotNull(subagents).release(target, entry.id)
                    else -> throw ToolError("Rule regions cannot be released individually")
                }
            }
            "remove" -> {
                require(args.flag("confirmed")) { "Deletion requires explicit user confirmation and confirmed: true" }
                if (entry.origin == PlaceEntryOrigin.ORPHAN) {
                    val outcome = when (entry.kind) {
                        "skill" -> requireNotNull(skills).removeOrphan(target, entry.id)
                        "mcp" -> mcp.removeOrphan(target, entry.id)
                        "subagent" -> requireNotNull(subagents).removeOrphan(target, entry.id)
                        else -> rules.removeOrphan(target, entry.id)
                    }
                    require(outcome != OrphanRemoval.PROTECTED) { "Orphan was edited; removal refused" }
                } else {
                    require(entry.origin in setOf(PlaceEntryOrigin.FOREIGN, PlaceEntryOrigin.IGNORED)) { "Use uninstall for managed entries" }
                    when (entry.kind) {
                        "skill" -> requireNotNull(skills).removeForeign(target, requireNotNull(entry.skill))
                        "mcp" -> nativeMcp().removeForeign(target, requireNotNull(entry.server))
                        else -> throw ToolError("Only foreign skills and MCP entries may be deleted")
                    }
                }
            }
            else -> throw ToolError("action must be take_ownership, release or remove")
        }
        identity(entry.id)
    }

    fun reorder(args: JsonObject): JsonElement = operation { repository.withLock {
        val target = target(args)
        val file = target.files().find { it.toString() == args.text("file") }
            ?: throw ToolError("file must be an exact rules file of this target")
        require(Files.isRegularFile(file)) { "Rules file does not exist" }
        require(fileRevision(file) == args.text("expected_revision")) { "File changed; list target entries again" }
        val order = (args["order"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw ToolError("order must contain strings")
        } ?: throw ToolError("order must be an array")
        val current = rules.regions(file).map { it.id }
        require(order.size == current.size && order.size == order.toSet().size && order.toSet() == current.toSet()) {
            "order must contain every installed rule exactly once"
        }
        rules.reorder(file, order)
        buildJsonObject { put("file_revision", fileRevision(file)) }
    } }

    private fun entries(target: Target): List<Entry> {
        val blocks = repository.listBlocks()
        val place = when (target) {
            is AgentGlobalTarget -> "agent:${target.agent.id}"
            is ProjectTarget -> "project:${target.dir.projectKey()}"
        }
        val ignored = config.load().ignoredEntries[place].orEmpty().toSet()
        return buildList {
            target.files().forEach { file -> rules.regions(file).forEach { region ->
                add(Entry("rule:${file.pathIdentity()}:${region.id}", "rule", region.id, file,
                    if (blocks.any { it.id == region.id && it.type == BlockType.RULE }) PlaceEntryOrigin.MANAGED else PlaceEntryOrigin.ORPHAN,
                    region.content, region = region))
            } }
            rules.unmanagedFiles(target).forEach { (file, content) ->
                add(Entry("unmanaged-rule:${file.pathIdentity()}", "rule", "", file, PlaceEntryOrigin.FOREIGN, content.text))
            }
            rules.referencedImports(target).forEach { file ->
                val content = rules.unmanaged(file)
                if (!content.isEmpty) add(Entry("unmanaged-rule:${file.pathIdentity()}", "rule", "", file, PlaceEntryOrigin.FOREIGN, content.text))
            }
            skills?.classifiedEntries(target, repository.listSkills().map { it.id }.toSet(), ignored)?.forEach {
                if (it.value.id != RESERVED_MCP_SERVER_NAME) add(Entry(it.key, "skill", it.libraryId, it.value.path, it.origin, it.value.text, skill = it.value))
            }
            subagents?.classifiedEntries(target, blocks.filter { it.type == BlockType.SUBAGENT }.map { it.id }.toSet(), ignored)?.forEach {
                add(Entry(it.key, "subagent", it.libraryId, it.value.path, it.origin, it.value.text, subagent = it.value))
            }
            (mcp as? McpInstallService)?.classifiedEntries(target, blocks.filter { it.type == BlockType.MCP }.map { it.id }.toSet(), ignored)?.forEach {
                if (it.value.name != RESERVED_MCP_SERVER_NAME) add(Entry(it.key, "mcp", it.libraryId, it.value.file, it.origin, it.value.text, server = it.value))
            }
        }
    }

    private fun libraryText(target: Target, entry: Entry): String? {
        if (entry.id.isEmpty()) return null
        if (entry.kind == "skill") return repository.listSkills().find { it.id == entry.id }?.content
        val block = repository.listBlocks().find { it.id == entry.id } ?: return null
        if (block.type.name.lowercase() != entry.kind) return null
        return when (entry.kind) {
            "rule" -> regionFor(block, null).content
            "mcp" -> block.content
            else -> {
                val agentId = requireNotNull(entry.subagent).agentId
                val agents = when (target) {
                    is AgentGlobalTarget -> listOf(target.agent)
                    is ProjectTarget -> target.agents
                }
                agents.find { it.id == agentId }?.subagentFormat?.render(block, block.variant(agentId))
            }
        }
    }

    private fun comparableText(entry: Entry): String = entry.server?.config?.let(McpConfigCodec::serialize) ?: entry.text

    private fun revision(target: Target, entry: Entry): String {
        val local = if (entry.skill != null) skillTreeFingerprint(requireNotNull(skills).capture(target, entry.skill, entry.id).files)
            else entry.text
        val library = if (entry.skill != null) librarySkillSnapshot(entry.id)?.let { skillTreeFingerprint(it.files) }
            else libraryText(target, entry)
        return revision(entry, local, library)
    }

    private fun revision(entry: Entry, local: String, library: String?): String = digest(
        listOf(entry.key, entry.origin.name, entry.id, local, library).joinToString("") { "${it?.length}:$it" })

    private fun matchingBlock(entry: Entry): Block = requireNotNull(repository.loadBlock(entry.id)) { "No matching library block" }.also {
        require(it.type == if (entry.kind == "mcp") BlockType.MCP else BlockType.SUBAGENT) { "Library object has another kind" }
    }

    private fun librarySkillSnapshot(id: String): SkillSnapshot? = repository.listSkills().find { it.id == id }
        ?.let { repository.loadSkillSnapshot(it.id) }

    private fun selected(target: Target, args: JsonObject): Entry = entries(target).find { it.key == args.text("entry_key") }
        ?: throw ToolError("Entry no longer exists in this target; list target entries again")

    private fun target(args: JsonObject): Target = resolve(args.text("target"))
    private fun nativeMcp(): McpInstallService = mcp as? McpInstallService ?: throw ToolError("Native MCP discovery is unavailable")
    private fun fileRevision(path: Path): String = digest(Files.readString(path))
    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun identity(id: String): JsonObject = buildJsonObject { put("id", id) }

    private fun mutation(args: JsonObject, body: (Target, Entry) -> JsonElement): JsonElement = operation { repository.withLock {
        val target = target(args)
        val entry = selected(target, args)
        require(revision(target, entry) == args.text("expected_revision")) { "Entry or library changed; read target entry again" }
        if (args.flag("replace")) require(args.flag("confirmed")) { "Replacing foreign text requires confirmed: true" }
        body(target, entry)
    } }

    private fun rollbackBlock(id: String, body: () -> Unit) {
        try { body() } catch (error: Exception) {
            runCatching { repository.deleteBlock(id) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
    private fun rollbackSkill(id: String, body: () -> Unit) {
        try { body() } catch (error: Exception) {
            runCatching { repository.deleteSkill(id) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
    private inline fun operation(body: () -> JsonElement): JsonElement = try { body() } catch (_: CharacterCodingException) {
        throw ToolError("Skill file is not UTF-8; read it with encoding: base64")
    } catch (error: IllegalArgumentException) {
        throw ToolError(error.message ?: "Invalid target entry operation")
    } catch (error: IllegalStateException) { throw ToolError(error.message ?: "Target entry operation failed") }
}

private fun JsonObject.optionalText(name: String): String? = this[name]?.let {
    (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: throw ToolError("'$name' must be a string")
}
private fun JsonObject.text(name: String): String = optionalText(name) ?: throw ToolError("Missing '$name'")
private fun JsonObject.flag(name: String): Boolean = this[name]?.let {
    (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull ?: throw ToolError("'$name' must be a boolean")
} ?: false
