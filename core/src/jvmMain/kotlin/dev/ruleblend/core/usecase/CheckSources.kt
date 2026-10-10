package dev.ruleblend.core.usecase

import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.ModSource
import dev.ruleblend.core.storage.ModImport
import dev.ruleblend.core.storage.modTreeFingerprint
import dev.ruleblend.core.storage.GitAgentRoot
import dev.ruleblend.core.storage.GitImportLimits
import dev.ruleblend.core.storage.discoverGitSubagents
import dev.ruleblend.core.storage.prepareImportCheckout
import dev.ruleblend.core.storage.resolveGitImportPath
import dev.ruleblend.core.storage.subagentFingerprint
import dev.ruleblend.core.storage.LocalSkillCapture
import dev.ruleblend.core.storage.gitSkillExecutableFiles
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.withGitCredentials
import dev.ruleblend.core.storage.validateGitRepositoryUrl
import dev.ruleblend.core.storage.skillTreeFingerprint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.eclipse.jgit.api.Git

/** Result of checking every imported definition's source currently present in the library. */
data class SourceCheckReport(
    val perRepo: List<SourceCheckRepository>,
    val perSkill: List<SourceCheckSkill>,
    val perSubagent: List<SourceCheckSubagent> = emptyList(),
    val perMod: List<SourceCheckMod> = emptyList(),
)

data class SourceCheckRepository(
    val repository: String,
    val remoteHead: String?,
    val status: SourceCheckRepositoryStatus,
    val error: String? = null,
)

enum class SourceCheckRepositoryStatus { UNCHANGED, CHECKED, FAILED }

data class SourceCheckSkill(
    val skillId: String,
    val repository: String,
    val path: String,
    val status: SourceCheckSkillStatus,
    /** Fingerprint of the checked upstream tree; absent when the path or repository was unavailable. */
    val upstreamTreeFingerprint: String? = null,
)

enum class SourceCheckSkillStatus { CURRENT, UPDATE_AVAILABLE, PATH_MISSING, UNAVAILABLE }

data class SourceCheckMod(
    val modId: String,
    val repository: String,
    val path: String,
    val status: SourceCheckSkillStatus,
    val upstreamTreeFingerprint: String? = null,
    val error: String? = null,
) {
    val key: String get() = "mod:$modId"
}

data class SourceCheckSubagent(
    val subagentId: String,
    val repository: String,
    val path: String,
    val assistant: String,
    val status: SourceCheckSkillStatus,
    val upstreamDefinitionFingerprint: String? = null,
)

/**
 * Checks imported definitions repository by repository. A remote whose HEAD still equals every stored
 * source revision is never cloned; otherwise one shallow clone serves all of that repository's
 * imported paths.
 */
class CheckSources(private val repository: LibraryRepository) {
    fun check(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): SourceCheckReport {
        val sources = repository.listSkills().filter { it.source != null }.groupBy { it.source!!.repository }
        val agentSources = repository.listBlocks().filter { it.source != null }.groupBy { it.source!!.repository }
        val modSources = repository.listMods().filter { it.source != null }.groupBy { it.source!!.repository }
        val urls = (sources.keys + agentSources.keys + modSources.keys).sorted()
        val total = urls.size
        onProgress(0, total)
        val repos = mutableListOf<SourceCheckRepository>()
        val skills = mutableListOf<SourceCheckSkill>()
        val subagents = mutableListOf<SourceCheckSubagent>()
        val mods = mutableListOf<SourceCheckMod>()

        urls.forEachIndexed { index, repositoryUrl ->
            val importedSkills = sources[repositoryUrl].orEmpty()
            val importedAgents = agentSources[repositoryUrl].orEmpty()
            val importedMods = modSources[repositoryUrl].orEmpty()
            try {
                val remoteHead = remoteHead(validateGitRepositoryUrl(repositoryUrl))
                if (importedSkills.all { it.source!!.revision == remoteHead } && importedAgents.all { it.source!!.revision == remoteHead } &&
                    importedMods.all { it.source!!.revision == remoteHead }) {
                    repos += SourceCheckRepository(repositoryUrl, remoteHead, SourceCheckRepositoryStatus.UNCHANGED)
                    skills += importedSkills.map { skill ->
                        val local = repository.loadSkillSnapshot(skill.id)
                            ?: error("Imported skill '${skill.id}' disappeared")
                        SourceCheckSkill(
                            skillId = skill.id,
                            repository = repositoryUrl,
                            path = skill.source!!.path,
                            status = SourceCheckSkillStatus.CURRENT,
                            upstreamTreeFingerprint = skillTreeFingerprint(local.files),
                        )
                    }
                    subagents += importedAgents.map { block -> agentResult(block, SourceCheckSkillStatus.CURRENT, subagentFingerprint(block)) }
                    mods += importedMods.map { mod ->
                        val source = requireNotNull(mod.source)
                        val local = requireNotNull(repository.loadModSnapshot(mod.id))
                        SourceCheckMod(mod.id, repositoryUrl, source.path, SourceCheckSkillStatus.CURRENT, modTreeFingerprint(local.files))
                    }
                } else {
                    val checkout = clone(validateGitRepositoryUrl(repositoryUrl))
                    try {
                        val checkedHead = Git.open(checkout.toFile()).use { it.repository.resolve("HEAD").name }
                        repos += SourceCheckRepository(repositoryUrl, checkedHead, SourceCheckRepositoryStatus.CHECKED)
                        skills += importedSkills.map { skill -> checkSkill(checkout, skill) }
                        subagents += importedAgents.map { block -> checkSubagent(checkout, block) }
                        mods += importedMods.map { mod -> checkMod(checkout, mod, checkedHead) }
                    } finally {
                        checkout.toFile().deleteRecursively()
                    }
                }
            } catch (error: Exception) {
                repos.removeAll { it.repository == repositoryUrl }
                skills.removeAll { it.repository == repositoryUrl }
                subagents.removeAll { it.repository == repositoryUrl }
                mods.removeAll { it.repository == repositoryUrl }
                repos += SourceCheckRepository(
                    repositoryUrl,
                    remoteHead = null,
                    status = SourceCheckRepositoryStatus.FAILED,
                    error = error.message ?: error::class.simpleName,
                )
                skills += importedSkills.map { skill ->
                    SourceCheckSkill(skill.id, repositoryUrl, skill.source!!.path, SourceCheckSkillStatus.UNAVAILABLE)
                }
                subagents += importedAgents.map { agentResult(it, SourceCheckSkillStatus.UNAVAILABLE) }
                mods += importedMods.map { SourceCheckMod(it.id, repositoryUrl, it.source!!.path, SourceCheckSkillStatus.UNAVAILABLE,
                    error = error.message) }
            } finally {
                onProgress(index + 1, total)
            }
        }
        return SourceCheckReport(repos, skills, subagents, mods)
    }

    private fun remoteHead(repositoryUrl: String): String = withGitCredentials { credentials ->
        Git.lsRemoteRepository()
            .setRemote(repositoryUrl)
            .setHeads(false)
            .setTags(false)
            .setTimeout(20)
            .setCredentialsProvider(credentials)
            .callAsMap()["HEAD"]?.objectId?.name
            ?: error("Remote repository has no HEAD revision")
    }

    private fun clone(repositoryUrl: String): Path {
        val checkout = Files.createTempDirectory("ruleblend-source-check-")
        try {
            withGitCredentials { credentials ->
                Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(checkout.toFile())
                    .setDepth(1)
                    .setCloneSubmodules(false)
                    .setNoCheckout(true)
                    .setTimeout(20)
                    .setCredentialsProvider(credentials)
                    .call().use { it.prepareImportCheckout(GitImportLimits()) }
            }
            return checkout
        } catch (error: Throwable) {
            checkout.toFile().deleteRecursively()
            throw error
        }
    }

    private fun checkSkill(checkout: Path, skill: Skill): SourceCheckSkill {
        val source = requireNotNull(skill.source)
        val directory = checkout.resolve(source.path).normalize()
        if (!directory.startsWith(checkout) || !directory.isDirectory()) {
            return SourceCheckSkill(skill.id, source.repository, source.path, SourceCheckSkillStatus.PATH_MISSING)
        }
        val upstreamFiles = LocalSkillCapture.files(directory, gitSkillExecutableFiles(checkout, directory))
        val local = repository.loadSkillSnapshot(skill.id) ?: error("Imported skill '${skill.id}' disappeared")
        val upstreamTreeFingerprint = skillTreeFingerprint(upstreamFiles)
        val status = if (skillTreeFingerprint(local.files) == upstreamTreeFingerprint) {
            SourceCheckSkillStatus.CURRENT
        } else {
            SourceCheckSkillStatus.UPDATE_AVAILABLE
        }
        return SourceCheckSkill(
            skillId = skill.id,
            repository = source.repository,
            path = source.path,
            status = status,
            upstreamTreeFingerprint = upstreamTreeFingerprint,
        )
    }

    private fun checkSubagent(checkout: Path, block: Block): SourceCheckSubagent {
        val source = requireNotNull(block.source)
        return try {
            val file = resolveGitImportPath(checkout, source.path)
            if (!Files.isRegularFile(file)) return agentResult(block, SourceCheckSkillStatus.PATH_MISSING)
            val discovery = discoverGitSubagents(checkout, listOf(source.path),
                listOf(GitAgentRoot(source.path, source.assistant)), GitImportLimits())
            val draft = discovery.candidates.singleOrNull()?.draftFor(source.assistant)
                ?: return agentResult(block, SourceCheckSkillStatus.UNAVAILABLE)
            val upstream = block.copy(name = requireNotNull(draft.name), description = draft.description.orEmpty(),
                content = draft.content, variants = mapOf(source.assistant to draft.toVariant()))
            val fingerprint = subagentFingerprint(upstream)
            agentResult(block, if (fingerprint == subagentFingerprint(block)) SourceCheckSkillStatus.CURRENT
                else SourceCheckSkillStatus.UPDATE_AVAILABLE, fingerprint)
        } catch (_: Exception) {
            agentResult(block, SourceCheckSkillStatus.UNAVAILABLE)
        }
    }

    private fun agentResult(block: Block, status: SourceCheckSkillStatus, fingerprint: String? = null): SourceCheckSubagent {
        val source = requireNotNull(block.source)
        return SourceCheckSubagent(block.id, source.repository, source.path, source.assistant, status, fingerprint)
    }

    private fun checkMod(checkout: Path, mod: Mod, revision: String): SourceCheckMod {
        val source = requireNotNull(mod.source)
        return try {
            val directory = resolveGitImportPath(checkout, source.path)
            if (!Files.isDirectory(directory) || !Files.isRegularFile(directory.resolve(".claude-plugin/plugin.json"))) {
                return SourceCheckMod(mod.id, source.repository, source.path, SourceCheckSkillStatus.PATH_MISSING)
            }
            val upstream = ModImport.capture(directory, ModSource(source.repository, revision, source.path), gitSkillExecutableFiles(checkout, directory))
            val fingerprint = modTreeFingerprint(upstream.files)
            val local = requireNotNull(repository.loadModSnapshot(mod.id))
            SourceCheckMod(mod.id, source.repository, source.path,
                if (modTreeFingerprint(local.files) == fingerprint) SourceCheckSkillStatus.CURRENT else SourceCheckSkillStatus.UPDATE_AVAILABLE,
                fingerprint)
        } catch (error: Exception) {
            SourceCheckMod(mod.id, source.repository, source.path, SourceCheckSkillStatus.UNAVAILABLE, error = error.message)
        }
    }
}
