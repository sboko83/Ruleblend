package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.ArchiveSelection
import dev.ruleblend.core.exchange.ChangeKind
import dev.ruleblend.core.exchange.ImportPlan
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.ImportLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

/** Reviewed archive snapshots and read-only history; remote traffic is always explicitly requested. */
internal class LibraryExchangeTools(
    private val repository: LibraryRepository,
    private val config: ConfigStore,
    private val imports: ImportLibrary?,
    private val git: LibraryGit?,
    private val libraryRoot: Path?,
    private val now: () -> Instant = Instant::now,
) {
    private data class Preview(val plan: ImportPlan, val expiresAt: Instant)
    private val previews = linkedMapOf<String, Preview>()

    fun export(args: JsonObject): JsonElement = operation {
        val zip = archivePath(args, writing = true)
        val selection = if (SELECTION_KEYS.any { it in args }) selection(args) else null
        repository.withLock {
            selection?.let {
                known(it.blockIds, repository.listBlocks().map { block -> block.id }, "block_ids")
                known(it.groupIds, repository.listGroups().filter { group -> group.id != ALL_GROUP_ID }.map { group -> group.id }, "group_ids")
                known(it.skillIds, repository.listSkills().map { skill -> skill.id }, "skill_ids")
                known(it.profileIds, repository.listProfiles().map { profile -> profile.id }, "profile_ids")
            }
            if (Files.exists(zip) && !args.flag("overwrite")) throw ToolError("Archive exists; explicitly request overwrite")
            importer().export(zip, selection, overwrite = args.flag("overwrite"))
        }
        buildJsonObject { put("path", zip.toString()); put("bytes", Files.size(zip)) }
    }

    fun preview(args: JsonObject): JsonElement = operation {
        val plan = repository.withLock { importer().plan(archivePath(args)) }
        val id = UUID.randomUUID().toString()
        val expires = now().plusSeconds(600)
        synchronized(previews) {
            previews.entries.removeIf { !it.value.expiresAt.isAfter(now()) }
            while (previews.size >= 2) previews.remove(previews.keys.first())
            previews[id] = Preview(plan, expires)
        }
        buildJsonObject {
            put("preview_id", id)
            put("expires_at", expires.toString())
            put("entries", buildJsonArray {
                plan.blocks.forEach { add(summary("block", it.incoming.id, it.incoming.name, it.kind)) }
                plan.groups.forEach { add(summary("group", it.incoming.id, it.incoming.name, it.kind)) }
                plan.skills.forEach { add(summary("skill", it.incoming.skill.id, it.incoming.skill.name, it.kind)) }
                plan.profiles.forEach { add(summary("profile", it.incoming.id, it.incoming.name, it.kind)) }
            })
        }
    }

    fun entry(args: JsonObject): JsonElement = operation {
        val plan = reviewed(args)
        val id = args.text("id")
        when (args.text("kind")) {
            "block" -> {
                val item = plan.blocks.find { it.incoming.id == id } ?: throw ToolError("Unknown preview block")
                // Both sides are hidden if either side is MCP: an archive can change an object's kind.
                val secret = item.incoming.type == BlockType.MCP || item.local?.type == BlockType.MCP
                buildJsonObject {
                    put("incoming", block(item.incoming, secret))
                    item.local?.let { put("local", block(it, secret)) }
                    put("change", item.kind.name.lowercase())
                    put("redacted", secret)
                }
            }
            "group" -> {
                val item = plan.groups.find { it.incoming.id == id } ?: throw ToolError("Unknown preview group")
                buildJsonObject {
                    put("incoming", Json.encodeToJsonElement(item.incoming))
                    item.local?.let { put("local", Json.encodeToJsonElement(it)) }
                    put("change", item.kind.name.lowercase())
                }
            }
            "profile" -> {
                val item = plan.profiles.find { it.incoming.id == id } ?: throw ToolError("Unknown preview profile")
                buildJsonObject {
                    put("incoming", Json.encodeToJsonElement(item.incoming))
                    item.local?.let { put("local", Json.encodeToJsonElement(it)) }
                    put("change", item.kind.name.lowercase())
                }
            }
            "skill" -> {
                val item = plan.skills.find { it.incoming.skill.id == id } ?: throw ToolError("Unknown preview skill")
                fun snapshot(value: dev.ruleblend.core.model.SkillSnapshot): JsonObject = buildJsonObject {
                    put("name", value.skill.name)
                    put("description", value.skill.description)
                    put("version", value.skill.version)
                    value.skill.source?.let { put("source", Json.encodeToJsonElement(it)) }
                    value.skill.forkedFrom?.let { put("forked_from", Json.encodeToJsonElement(it)) }
                    val path = args["path"]?.let { args.text("path") }
                    put("files", buildJsonArray {
                        value.files.forEach { add(buildJsonObject {
                            put("path", it.path); put("bytes", it.bytes.size); put("executable", it.executable)
                        }) }
                    })
                    if (path != null) {
                        val file = value.files.find { it.path == path }
                        put("file_exists", file != null)
                        file?.let {
                            put("encoding", "base64")
                            put("content", java.util.Base64.getEncoder().encodeToString(it.bytes))
                        }
                    }
                }
                buildJsonObject {
                    put("incoming", snapshot(item.incoming))
                    item.local?.let { put("local", snapshot(it)) }
                    put("change", item.kind.name.lowercase())
                }
            }
            else -> throw ToolError("kind must be block, group, profile or skill")
        }
    }

    fun apply(args: JsonObject): JsonElement = operation {
        val plan = reviewed(args)
        val selected = selection(args)
        if (selected.isEmpty) throw ToolError("Select at least one archive entry")
        known(selected.blockIds, plan.blocks.map { it.incoming.id }, "block_ids")
        known(selected.groupIds, plan.groups.map { it.incoming.id }, "group_ids")
        known(selected.skillIds, plan.skills.map { it.incoming.skill.id }, "skill_ids")
        known(selected.profileIds, plan.profiles.map { it.incoming.id }, "profile_ids")
        repository.withLock {
            fun unchanged(matches: Boolean) {
                if (!matches) throw ToolError("Selected library content changed; create a new archive preview")
            }
            plan.blocks.filter { it.incoming.id in selected.blockIds }.forEach { unchanged(repository.loadBlock(it.incoming.id) == it.local) }
            plan.groups.filter { it.incoming.id in selected.groupIds }.forEach { unchanged(repository.loadGroup(it.incoming.id) == it.local) }
            plan.skills.filter { it.incoming.skill.id in selected.skillIds }.forEach { unchanged(repository.loadSkillSnapshot(it.incoming.skill.id) == it.local) }
            plan.profiles.filter { it.incoming.id in selected.profileIds }.forEach { unchanged(repository.loadProfile(it.incoming.id) == it.local) }
            importer().apply(plan, selected.blockIds, selected.groupIds, selected.skillIds, selected.profileIds)
        }
        synchronized(previews) { previews.remove(args.text("preview_id")) }
        buildJsonObject { put("applied", true) }
    }

    fun history(args: JsonObject): JsonElement = operation {
        val id = args.text("id")
        val limit = args["limit"]?.let {
            (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.intOrNull
                ?: throw ToolError("limit must be an integer")
        } ?: 20
        if (limit !in 1..100) throw ToolError("limit must be between 1 and 100")
        val revisions = when (args.text("kind")) {
            "block" -> repository.blockHistory(id, limit)
            "group" -> repository.groupHistory(id, limit)
            "profile" -> repository.profileHistory(id, limit)
            "skill" -> repository.skillHistory(id, limit)
            else -> throw ToolError("kind must be block, group, profile or skill")
        }
        buildJsonArray {
            revisions.forEach { add(buildJsonObject {
                put("revision", it.id); put("message", it.message); put("time", it.time.toString())
                put("added", it.added); put("removed", it.removed)
            }) }
        }
    }

    fun diff(args: JsonObject): JsonElement = operation {
        val id = args.text("id")
        val revision = args.text("revision")
        if (!revision.matches(Regex("[0-9a-f]{7,40}"))) throw ToolError("revision must be a commit hash from library history")
        val kind = args.text("kind")
        // A block may have been an MCP config in any previous revision, including a deleted one.
        // Returning raw block patches would disclose old env/header values and command arguments.
        val redacted = kind == "block" && repository.blockDiffContainsMcp(id, revision)
        val patch = when (kind) {
            "block" -> repository.blockDiff(id, revision)
            "group" -> repository.groupDiff(id, revision)
            "profile" -> repository.profileDiff(id, revision)
            "skill" -> repository.skillDiff(id, revision)
            else -> throw ToolError("kind must be block, group, profile or skill")
        }
        buildJsonObject {
            put("revision", revision)
            put("redacted", redacted)
            if (redacted) put("reason", "MCP configuration patches may contain credentials; inspect them in the GUI")
            else put("diff", patch)
        }
    }

    fun syncStatus(args: JsonObject): JsonElement = operation {
        val remote = config.load().remoteGit
        val statistics = libraryGit().history()
        buildJsonObject {
            put("configured", remote != null)
            remote?.let { put("branch", it.branch); put("automatic", it.automatic) }
            put("commits", statistics.commits)
            statistics.lastCommitEpochMillis?.let { put("last_commit_at", it) }
            libraryGit().lastRemoteSync?.let { put("last_sync", syncResult(it)) }
        }
    }

    fun sync(args: JsonObject): JsonElement = operation {
        if (!args.flag("confirm")) throw ToolError("Git sync fetches, merges and pushes; explicit confirm: true is required")
        val allowUnrelated = args.flag("allow_unrelated_histories")
        val remote = config.load().remoteGit ?: throw ToolError("Configure the library remote in Settings first")
        val git = libraryGit()
        try {
            syncResult(git.syncRemote(remote, allowUnrelated))
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            // JGit errors can include credential-bearing URLs. Only return typed status fields.
            git.lastRemoteSync?.let(::syncResult) ?: throw ToolError("Library Git sync failed; inspect Settings")
        }
    }

    private fun syncResult(result: dev.ruleblend.core.storage.RemoteSyncResult): JsonObject = buildJsonObject {
        put("status", result.status.name.lowercase())
        put("at", result.atEpochMillis)
        put("behind", result.behind); put("ahead", result.ahead); put("merged", result.merged)
        put("unrelated_histories", result.unrelatedHistories)
        put("conflicts", buildJsonArray {
            result.conflicts.forEach { add(buildJsonObject {
                put("kind", it.kind.name.lowercase()); put("id", it.id); put("restored", it.restored)
            }) }
        })
        if (result.status == dev.ruleblend.core.storage.RemoteSyncStatus.FAILED) {
            put("message", if (result.unrelatedHistories) "Unrelated histories; review and explicitly request merging" else "Git sync failed; inspect Settings")
        }
    }

    private fun archivePath(args: JsonObject, writing: Boolean = false): Path {
        val path = Path.of(args.text("path"))
        if (!path.isAbsolute || !path.fileName.toString().endsWith(".zip", ignoreCase = true)) {
            throw ToolError("path must be an absolute ZIP path")
        }
        if (!writing) {
            if (!Files.isRegularFile(path)) throw ToolError("Archive does not exist")
            return path.toRealPath()
        }
        val parent = path.toAbsolutePath().normalize().parent
        if (!Files.isDirectory(parent)) throw ToolError("Archive parent directory must exist")
        val destination = parent.toRealPath().resolve(path.fileName)
        val root = libraryRoot?.toRealPath() ?: throw ToolError("Library archive destination validation is not configured")
        if (destination.startsWith(root) || Files.isSymbolicLink(destination) || Files.isDirectory(destination)) {
            throw ToolError("Export outside the library to a regular ZIP file")
        }
        return destination
    }

    private fun reviewed(args: JsonObject): ImportPlan = synchronized(previews) {
        val preview = previews[args.text("preview_id")] ?: throw ToolError("Unknown or expired archive preview")
        if (!preview.expiresAt.isAfter(now())) {
            previews.remove(args.text("preview_id"))
            throw ToolError("Archive preview expired; create a new preview")
        }
        preview.plan
    }

    private fun selection(args: JsonObject) = ArchiveSelection(
        args.strings("block_ids"), args.strings("group_ids"), args.strings("skill_ids"), args.strings("profile_ids"),
    )

    private fun known(selected: Set<String>, available: List<String>, key: String) {
        if (!available.containsAll(selected)) throw ToolError("Unknown selection in $key")
    }

    private fun block(value: dev.ruleblend.core.model.Block, secret: Boolean): JsonElement =
        if (secret) buildJsonObject { put("id", value.id); put("name", value.name); put("type", value.type.name.lowercase()); put("version", value.version) }
        else buildJsonObject {
            put("id", value.id); put("name", value.name); put("description", value.description)
            put("type", value.type.name.lowercase()); put("version", value.version); put("content", value.content)
            put("heading", value.heading); put("heading_level", value.headingLevel)
            put("variants", Json.encodeToJsonElement(value.variants.mapValues { it.value.fields }))
            value.source?.let { put("source", Json.encodeToJsonElement(it)) }
            value.forkedFrom?.let { put("forked_from", Json.encodeToJsonElement(it)) }
        }

    private fun summary(kind: String, id: String, name: String, change: ChangeKind) = buildJsonObject {
        put("kind", kind); put("id", id); put("name", name); put("change", change.name.lowercase())
        put("selected_by_default", change == ChangeKind.NEW || change == ChangeKind.UPDATE)
    }

    private fun importer() = imports ?: throw ToolError("Library exchange is not configured")
    private fun libraryGit() = git ?: throw ToolError("Library Git is not configured")

    private companion object {
        val SELECTION_KEYS = setOf("block_ids", "group_ids", "skill_ids", "profile_ids")
    }
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
        ?: throw ToolError("$key must be a non-empty string")

private fun JsonObject.flag(key: String): Boolean = this[key]?.let {
    (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.booleanOrNull
        ?: throw ToolError("$key must be a boolean")
} ?: false

private fun JsonObject.strings(key: String): Set<String> = this[key]?.let {
    val values = it as? JsonArray ?: throw ToolError("$key must be an array of strings")
    values.map { value ->
        (value as? JsonPrimitive)?.takeIf { item -> item.isString && item.content.isNotBlank() }?.content
            ?: throw ToolError("$key must contain non-empty strings")
    }.toSet()
} ?: emptySet()

private inline fun <T> operation(action: () -> T): T = try {
    action()
} catch (e: ToolError) {
    throw e
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
} catch (_: Exception) {
    throw ToolError("Library operation failed; inspect the archive or library in the GUI before retrying")
}
