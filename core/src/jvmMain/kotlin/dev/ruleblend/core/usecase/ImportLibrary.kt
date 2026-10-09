package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.ImportPlan
import dev.ruleblend.core.exchange.ArchiveSelection
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.GitSubagentSource
import dev.ruleblend.core.storage.RESERVED_BLOCK_IDS
import dev.ruleblend.core.model.nextId
import dev.ruleblend.core.storage.GitSkillFetcher
import dev.ruleblend.core.storage.GitSkillImportPlan
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import java.nio.file.Path

data class ImportedDefinitions(val skills: List<Skill>, val subagents: List<Block>)

/**
 * Bringing library objects in from outside — an exported archive, or a Git repository.
 *
 * Both sources answer the same two questions in the same order: what would this change, and then
 * apply exactly the part of it the user ticked. Nothing is written by [plan] or [fetch]; a facade
 * that shows the plan and never calls apply has changed nothing.
 */
class ImportLibrary(
    private val repository: LibraryRepository,
    private val archive: LibraryArchive,
    private val configStore: ConfigStore,
    private val gitSkills: GitSkillFetcher? = null,
) {

    /** Writes the whole library to [zip]. */
    fun export(zip: Path, selection: ArchiveSelection? = null, overwrite: Boolean = true) = archive.export(zip, selection, overwrite)

    /** Reads [zip] and returns what it would change. Nothing is written until [apply]. */
    fun plan(zip: Path): ImportPlan = archive.plan(zip)

    fun apply(
        plan: ImportPlan,
        blockIds: Set<String>,
        groupIds: Set<String>,
        skillIds: Set<String> = emptySet(),
        profileIds: Set<String> = emptySet(),
    ): LibrarySnapshot {
        archive.apply(plan, blockIds, groupIds, skillIds, profileIds)
        return synced()
    }

    /** Reads the repository's skills and subagents without writing anything. */
    fun fetch(repositoryUrl: String): GitSkillImportPlan = importer().fetch(repositoryUrl)

    /**
     * Writes the skills of [plan] whose source path is in [sourcePaths]. A skill already imported
     * from the same source keeps its local id, so groups and installed copies that reference it
     * survive the refresh; a new one gets an id that is free.
     */
    fun applyGitSkills(plan: GitSkillImportPlan, sourcePaths: Set<String>): LibrarySnapshot {
        return applyGit(plan, sourcePaths, emptyMap())
    }

    /** Subagent paths map to explicitly selected source assistants. Both types share one plan. */
    fun applyGit(
        plan: GitRepositoryImportPlan,
        skillPaths: Set<String>,
        subagents: Map<String, String>,
    ): LibrarySnapshot = repository.withLock {
        val usedIds = repository.listSkills().map { it.id }.toMutableList()
        require(skillPaths.all { path -> plan.skills.any { it.skill.source?.path == path } }) { "Unknown skill path" }
        val incoming = plan.skills.filter { it.skill.source?.path in skillPaths }.map { snapshot ->
            val source = requireNotNull(snapshot.skill.source)
            val id = repository.findSkill(source)?.id ?: nextId(snapshot.skill.name, usedIds).also(usedIds::add)
            snapshot.copy(skill = snapshot.skill.copy(id = id))
        }
        val usedBlockIds = (repository.listBlocks().map { it.id } + RESERVED_BLOCK_IDS).toMutableList()
        val blocks = subagents.map { (path, assistant) ->
            val candidate = plan.subagents.find { it.path == path } ?: error("Unknown subagent path '$path'")
            val source = GitSubagentSource(plan.repository, plan.revision, path, assistant)
            val draft = candidate.draftFor(assistant)
            val id = repository.findSubagent(source)?.id ?: nextId(requireNotNull(draft.name), usedBlockIds).also(usedBlockIds::add)
            importedSubagent(plan, path, assistant, id)
        }
        repository.validateImport(blocks = blocks, skills = incoming)
        incoming.forEach(repository::writeSkill)
        blocks.forEach(repository::writeImportedSubagent)
        synced()
    }

    /** Refreshes one imported skill while preserving the existing single-item facade. */
    fun updateSkill(source: Skill): LibraryChange<Skill> {
        val updated = updateSkills(setOf(source.id))
        return LibraryChange(updated.value.single(), updated.library)
    }

    /**
     * Refreshes the imported skills identified by [ids], cloning each source repository once. All
     * ids and upstream paths are resolved before the first write, so a missing selection cannot
     * leave an otherwise valid subset refreshed.
     */
    fun updateSkills(ids: Set<String>): LibraryChange<List<Skill>> {
        val updated = updateDefinitions(ids, emptySet())
        return LibraryChange(updated.value.skills, updated.library)
    }

    fun updateSubagents(ids: Set<String>): LibraryChange<List<Block>> {
        val updated = updateDefinitions(emptySet(), ids)
        return LibraryChange(updated.value.subagents, updated.library)
    }

    /** All selected paths are resolved before writes; a mixed batch fetches each repository once. */
    fun updateDefinitions(ids: Set<String>, subagentIds: Set<String>): LibraryChange<ImportedDefinitions> {
        val selected = repository.listSkills().filter { it.id in ids }
        val agents = repository.listBlocks().filter { it.id in subagentIds }
        require(agents.map { it.id }.toSet() == subagentIds) { "Unknown subagent ids" }
        agents.forEach { require(it.type == BlockType.SUBAGENT && it.source != null) { "Subagent '${it.id}' is not imported" } }
        val foundIds = selected.mapTo(mutableSetOf()) { it.id }
        val missingIds = ids - foundIds
        require(missingIds.isEmpty()) { "Unknown skill ids: ${missingIds.sorted().joinToString()}" }

        val byRepository = selected.groupBy { skill ->
            requireNotNull(skill.source) { "Skill '${skill.id}' was not imported from a repository" }.repository
        }
        val urls = byRepository.keys + agents.map { it.source!!.repository }
        val plans = urls.associateWith { importer().fetch(it) }
        val replacements = selected.map { current ->
            val source = requireNotNull(current.source)
            val incoming = plans.getValue(source.repository).skills
                .find { it.skill.source?.path == source.path }
                ?: error("Repository no longer contains skill '${source.path}'")
            incoming.copy(skill = incoming.skill.copy(id = current.id))
        }
        val blocks = agents.map { current ->
            val source = requireNotNull(current.source)
            importedSubagent(plans.getValue(source.repository), source.path, source.assistant, current.id)
        }
        val saved = repository.withLock {
            require(selected.all { repository.loadSkill(it.id) == it }) { "Skill changed while fetching its source" }
            require(agents.all { repository.loadBlock(it.id) == it }) { "Subagent changed while fetching its source" }
            repository.validateImport(blocks = blocks, skills = replacements)
            ImportedDefinitions(replacements.map(repository::writeSkill), blocks.map(repository::writeImportedSubagent))
        }
        return LibraryChange(saved, synced())
    }

    private fun importedSubagent(plan: GitRepositoryImportPlan, path: String, assistant: String, id: String): Block {
        val draft = (plan.subagents.find { it.path == path }
            ?: error("Repository no longer contains subagent '$path'")).draftFor(assistant)
        return Block(id = id, name = requireNotNull(draft.name), description = draft.description.orEmpty(),
            type = BlockType.SUBAGENT, content = draft.content, variants = mapOf(assistant to draft.toVariant()),
            source = GitSubagentSource(plan.repository, plan.revision, path, assistant))
    }

    private fun importer(): GitSkillFetcher =
        requireNotNull(gitSkills) { "No Git skill importer is wired in this facade" }

    private fun synced(): LibrarySnapshot {
        repository.syncAllGroup()
        return readLibrary(repository, configStore)
    }
}
