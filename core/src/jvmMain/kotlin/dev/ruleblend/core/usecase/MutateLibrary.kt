package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKeyOf
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.config.withoutProfile
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.formatName
import dev.ruleblend.core.model.localSkillDocument
import dev.ruleblend.core.model.nextId
import dev.ruleblend.core.model.updateSkillDocumentMetadata
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.RESERVED_BLOCK_IDS
import java.nio.file.Path

/** Everything the library holds, as one read. What a mutation hands back so a screen can re-render. */
data class LibrarySnapshot(
    val blocks: List<Block> = emptyList(),
    val groups: List<Group> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val skills: List<Skill> = emptyList(),
    /** Project key each rule is pinned to; a rule that is absent here is global. */
    val ruleScopes: Map<String, String> = emptyMap(),
)

/** What one mutation produced, and the library as it stands afterwards. */
data class LibraryChange<T>(val value: T, val library: LibrarySnapshot)

/** One part of a split: the name it gets and the body cut out of the source. */
data class LibraryPart(val name: String, val body: String)

/**
 * Every write to the library, as one transaction each.
 *
 * The steps that used to trail every mutation in the UI model — allocate an id that is free, apply
 * the configured name format, keep the rule's scope in the config, re-sync the "all" group, re-read
 * what changed — belong to the mutation, not to the screen that started it. A screen that forgets
 * one of them leaves the library inconsistent in a way no other screen can see, so none of them is
 * reachable on its own: a mutation returns the new snapshot and that is the only way to get one.
 */
class MutateLibrary(
    private val repository: LibraryRepository,
    private val configStore: ConfigStore,
    /** Read per call: the format is a setting, and a mutation must use the one in force right now. */
    private val nameFormat: () -> NameFormat = { configStore.load().nameFormat },
) {

    /** The library as it stands, without changing anything. */
    fun snapshot(): LibrarySnapshot = read()

    fun createBlock(name: String, type: BlockType, scope: String? = null): LibraryChange<Block> = change {
        val display = formatted(name)
        val block = Block(id = nextBlockId(display), name = display, type = type, version = 0)
        val saved = repository.saveBlock(block)
        if (type == BlockType.RULE) assignScope(saved.id, scope)
        saved
    }

    /**
     * Creates a rule that starts out with [content] — text lifted from elsewhere (a difference in a
     * comparison) is already a body worth a version, so it lands at v1 rather than as an empty v0.
     */
    fun createRule(name: String, content: String, scope: String? = null): LibraryChange<Block> = change {
        val display = formatted(name)
        val saved = repository.saveBlock(
            Block(id = nextBlockId(display), name = display, type = BlockType.RULE, content = content),
        )
        assignScope(saved.id, scope)
        saved
    }

    /**
     * Saves an edited block. The id is left alone: it identifies the file and every group and
     * installed region that references it — only the name is normalized on the way in, so what is
     * stored matches the setting no matter how it was typed.
     */
    fun saveBlock(block: Block, scope: String? = null): LibraryChange<Block> = change {
        val saved = repository.saveBlock(block.copy(name = formatted(block.name)))
        if (block.type == BlockType.RULE) assignScope(block.id, scope)
        saved
    }

    /** Copies [source]'s content into a new block at v1 — the sanctioned way to fork a rule. */
    fun duplicateBlock(source: Block): LibraryChange<Block> = change {
        if (source.source != null) {
            return@change repository.forkSubagent(source.id, nextBlockId("${source.name}-changed"),
                formatted("${source.name}-changed"))
        }
        val id = nextBlockId(source.name)
        val copy = source.copy(
            id = id,
            name = formatted("${source.name}-copy"),
            version = 1,
            favorite = false,
        )
        val saved = repository.saveBlock(copy)
        if (source.type == BlockType.RULE) assignScope(saved.id, scopeOf(source.id))
        saved
    }

    /**
     * Turns [source] into one new block per part. The source is left untouched: a target that has it
     * installed keeps its region until the user chooses to replace it. Every part inherits [source]'s
     * group memberships, so the split is transparent to group-level installs.
     */
    fun splitBlock(source: Block, parts: List<LibraryPart>, description: String): LibraryChange<List<Block>> = change {
        require(source.source == null) { "Imported subagents are read-only; create an editable copy first" }
        val ids = (repository.listBlocks().map { it.id } + RESERVED_BLOCK_IDS).toMutableList()
        val created = parts.map { part ->
            val display = formatted(part.name)
            val id = nextId(display, ids).also(ids::add)
            repository.saveBlock(
                Block(id = id, name = display, description = description, type = source.type, content = part.body),
            ).also { if (source.type == BlockType.RULE) assignScope(it.id, scopeOf(source.id)) }
        }
        repository.listGroups()
            .filter { source.id in it.blockIds && it.id != ALL_GROUP_ID }
            .forEach { group -> repository.saveGroup(group.copy(blockIds = group.blockIds + created.map { it.id })) }
        created
    }

    /** Deletes a block, clears its scope and takes it out of every group and profile that named it. */
    fun deleteBlock(id: String): LibraryChange<Unit> = change {
        repository.deleteBlock(id)
        configStore.update { it.withRuleScope(id, null) }
        repository.listGroups()
            .filter { id in it.blockIds && it.id != ALL_GROUP_ID }
            .forEach { repository.saveGroup(it.copy(blockIds = it.blockIds - id)) }
        forEachProfileNaming({ id in it.blockIds || id in it.subagentIds }) {
            it.copy(blockIds = it.blockIds - id, subagentIds = it.subagentIds - id)
        }
    }

    fun createGroup(name: String): LibraryChange<Group> = change {
        val display = formatted(name)
        val group = Group(id = nextId(display, repository.listGroups().map { it.id }), name = display)
        repository.saveGroup(group)
        group
    }

    /** Saves the group, normalizing its name the same way [saveBlock] does. */
    fun saveGroup(group: Group): LibraryChange<Group> = change {
        val saved = group.copy(name = formatted(group.name))
        repository.saveGroup(saved)
        requireNotNull(repository.loadGroup(saved.id))
    }

    /**
     * Deletes a group and takes it out of every profile that included it. The "all" group is
     * generated, not owned, so it is never deleted.
     */
    fun deleteGroup(id: String): LibraryChange<Unit> = change {
        if (id == ALL_GROUP_ID) return@change
        repository.deleteGroup(id)
        forEachProfileNaming({ id in it.groupIds }) { it.copy(groupIds = it.groupIds - id) }
    }

    /** Profiles are portable modes, not install items: their members are chosen in the Library. */
    fun createProfile(name: String): LibraryChange<Profile> = change {
        val display = formatted(name)
        val profile = Profile(id = nextId(display, repository.listProfiles().map { it.id }), name = display)
        repository.saveProfile(profile)
        profile
    }

    /** Saves a profile with the same configured display-name format as other library objects. */
    fun saveProfile(profile: Profile): LibraryChange<Profile> = change {
        val saved = repository.saveProfile(profile.copy(name = formatted(profile.name)))
        saved
    }

    /**
     * Deletes a profile and detaches it from every project. The binding is machine state that only
     * this profile explains, so leaving it behind would keep a chip no screen can reach any more.
     * Installed copies still carrying its `p=` tag are given up to the next reconciliation.
     */
    fun deleteProfile(id: String): LibraryChange<Unit> = change {
        repository.deleteProfile(id)
        configStore.update { it.withoutProfile(id) }
    }

    /** Creates a local skill with its structured metadata applied to the supplied `SKILL.md`. */
    fun createSkill(
        name: String,
        description: String,
        content: String? = null,
    ): LibraryChange<Skill> = change {
        val id = nextId(name, repository.listSkills().map { it.id })
        val trimmed = description.trim()
        val document = content ?: localSkillDocument(id, trimmed, name.trim())
        repository.createSkill(
            Skill(
                id = id,
                name = id,
                description = trimmed,
                content = updateSkillDocumentMetadata(document, id, trimmed),
            ),
        )
    }

    /** Saves the skill with its SKILL.md front matter brought back in line with name and description. */
    fun saveSkill(skill: Skill): LibraryChange<Skill> = change {
        repository.saveSkill(
            skill.copy(content = updateSkillDocumentMetadata(skill.content, skill.name, skill.description)),
        )
    }

    fun writeSkillFile(id: String, path: String, bytes: ByteArray, executable: Boolean? = null): LibraryChange<Skill> = change {
        repository.writeSkillFile(id, path, bytes, executable)
    }

    fun deleteSkillFile(id: String, path: String): LibraryChange<Skill> = change {
        repository.deleteSkillFile(id, path)
    }

    /** Forks an imported skill into a local variant, so the original can still be refreshed from Git. */
    fun forkSkill(source: Skill): LibraryChange<Skill> = change {
        val id = nextId("${source.id}-changed", repository.listSkills().map { it.id })
        repository.forkSkill(source.id, id, id, updateSkillDocumentMetadata(source.content, id, source.description))
    }

    /** Deletes a skill and takes it out of every group and profile that named it. */
    fun deleteSkill(id: String): LibraryChange<Unit> = change {
        repository.deleteSkill(id)
        repository.listGroups()
            .filter { id in it.skillIds && it.id != ALL_GROUP_ID }
            .forEach { repository.saveGroup(it.copy(skillIds = it.skillIds - id)) }
        forEachProfileNaming({ id in it.skillIds }) { it.copy(skillIds = it.skillIds - id) }
    }

    /**
     * Rewrites every profile [names] selects. Profiles hold the same member lists groups do, so a
     * deleted object has to leave them the same way — otherwise reconciliation keeps chasing an id
     * the library can no longer resolve.
     */
    private fun forEachProfileNaming(names: (Profile) -> Boolean, without: (Profile) -> Profile) {
        repository.listProfiles().filter(names).forEach { repository.saveProfile(without(it)) }
    }

    /** Applies [name] the configured format, for a manual "format now" action. */
    fun previewName(name: String): String = formatted(name)

    /**
     * Runs one mutation, re-syncs the generated "all" group and reads the library back. The re-sync
     * is here rather than in each mutation because every one of them can change what "all" holds.
     */
    private fun <T> change(body: () -> T): LibraryChange<T> = repository.withLock {
        val value = body()
        repository.syncAllGroup()
        LibraryChange(value, read())
    }

    private fun read(): LibrarySnapshot = readLibrary(repository, configStore)

    private fun assignScope(blockId: String, scope: String?) {
        configStore.update { it.withRuleScope(blockId, scope?.let(Path::of)) }
    }

    private fun scopeOf(blockId: String): String? = configStore.load().ruleScopes[blockId]

    private fun formatted(name: String): String = formatName(name, nameFormat())

    private fun nextBlockId(name: String): String =
        nextId(name, repository.listBlocks().map { it.id } + RESERVED_BLOCK_IDS)
}

/** Project keys that can be offered as a rule scope: every registered project plus every pinned one. */
fun AppConfig.knownProjectScopes(): List<String> =
    (projects.map(::projectKeyOf) + ruleScopes.values).distinct()
