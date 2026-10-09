package dev.ruleblend.core.usecase

import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.FileReadScope
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.skillTreeFingerprint
import dev.ruleblend.core.storage.subagentFingerprint
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Disposable persisted result of source checks, grouped by remote repository. */
@Serializable
data class SourcesState(
    val sources: List<SourceStateSource> = emptyList(),
)

/** The latest remote revision and upstream-tree fingerprints observed for one repository. */
@Serializable
data class SourceStateSource(
    val repository: String,
    val lastCheckedAt: String? = null,
    val remoteHead: String? = null,
    val upstreamTreeFingerprints: Map<String, String> = emptyMap(),
    /** Paths the check reached the repository for and did not find; they carry no fingerprint. */
    val missingPaths: List<String> = emptyList(),
    val upstreamSubagentFingerprints: Map<String, String> = emptyMap(),
    val missingSubagentPaths: List<String> = emptyList(),
    val unavailableSubagentPaths: List<String> = emptyList(),
)

/** A cached report reconstructed against the library as it exists now. */
data class CachedSourceCheck(
    val report: SourceCheckReport?,
    val lastCheckedAt: String?,
)

/** Captures one completed check in the disposable sidecar representation. */
fun SourceCheckReport.toSourcesState(checkedAt: String): SourcesState = SourcesState(
    sources = perRepo.map { source ->
        SourceStateSource(
            repository = source.repository,
            lastCheckedAt = checkedAt,
            remoteHead = source.remoteHead,
            upstreamTreeFingerprints = perSkill
                .asSequence()
                .filter { it.repository == source.repository }
                .mapNotNull { skill -> skill.upstreamTreeFingerprint?.let { skill.path to it } }
                .toMap(),
            missingPaths = perSkill
                .filter { it.repository == source.repository && it.status == SourceCheckSkillStatus.PATH_MISSING }
                .map { it.path },
            upstreamSubagentFingerprints = perSubagent.filter { it.repository == source.repository }
                .mapNotNull { agent -> agent.upstreamDefinitionFingerprint?.let { "${agent.assistant}:${agent.path}" to it } }.toMap(),
            missingSubagentPaths = perSubagent.filter {
                it.repository == source.repository && it.status == SourceCheckSkillStatus.PATH_MISSING
            }.map { "${it.assistant}:${it.path}" },
            unavailableSubagentPaths = perSubagent.filter {
                it.repository == source.repository && it.status == SourceCheckSkillStatus.UNAVAILABLE
            }.map { "${it.assistant}:${it.path}" },
        )
    },
)

/**
 * Replays cached upstream fingerprints against current library trees. Imported provenance remains
 * the source list, so removed and newly imported skills never linger in the disposable result.
 */
fun SourcesState.cachedSourceCheck(repository: LibraryRepository): CachedSourceCheck {
    val imported = repository.listSkills().filter { it.source != null }
    val agents = repository.listBlocks().filter { it.source != null }
    val stateByRepository = sources.associateBy { it.repository }
    val revisions = (imported.map { it.source!!.repository to it.source.revision } +
        agents.map { it.source!!.repository to it.source.revision }).groupBy({ it.first }, { it.second })
    val checkedRepositories = revisions.mapNotNull { (url, revisions) ->
        val state = stateByRepository[url]?.takeIf { it.lastCheckedAt != null } ?: return@mapNotNull null
        val status = when {
            state.remoteHead == null -> SourceCheckRepositoryStatus.FAILED
            revisions.all { it == state.remoteHead } -> SourceCheckRepositoryStatus.UNCHANGED
            else -> SourceCheckRepositoryStatus.CHECKED
        }
        SourceCheckRepository(url, state.remoteHead, status)
    }
    val checkedUrls = checkedRepositories.mapTo(mutableSetOf()) { it.repository }
    val checkedSkills = imported.filter { it.source!!.repository in checkedUrls }.mapNotNull { skill ->
        val source = requireNotNull(skill.source)
        val state = stateByRepository.getValue(source.repository)
        val upstreamFingerprint = state.upstreamTreeFingerprints[source.path]
        val status = when {
            state.remoteHead == null -> SourceCheckSkillStatus.UNAVAILABLE
            source.path in state.missingPaths -> SourceCheckSkillStatus.PATH_MISSING
            // Without a fingerprint and without a recorded miss the skill was imported after this
            // check ran. Invalidate the cached report rather than present a new skill as deleted.
            upstreamFingerprint == null -> return@mapNotNull null
            repository.loadSkillSnapshot(skill.id)?.files?.let(::skillTreeFingerprint) == upstreamFingerprint ->
                SourceCheckSkillStatus.CURRENT
            else -> SourceCheckSkillStatus.UPDATE_AVAILABLE
        }
        SourceCheckSkill(skill.id, source.repository, source.path, status, upstreamFingerprint)
    }
    val checkedAgents = agents.filter { it.source!!.repository in checkedUrls }.mapNotNull { agent ->
        val source = requireNotNull(agent.source)
        val state = stateByRepository.getValue(source.repository)
        val key = "${source.assistant}:${source.path}"
        val fingerprint = state.upstreamSubagentFingerprints[key]
        val status = when {
            state.remoteHead == null -> SourceCheckSkillStatus.UNAVAILABLE
            key in state.missingSubagentPaths -> SourceCheckSkillStatus.PATH_MISSING
            key in state.unavailableSubagentPaths -> SourceCheckSkillStatus.UNAVAILABLE
            fingerprint == null -> return@mapNotNull null
            subagentFingerprint(agent) == fingerprint -> SourceCheckSkillStatus.CURRENT
            else -> SourceCheckSkillStatus.UPDATE_AVAILABLE
        }
        SourceCheckSubagent(agent.id, source.repository, source.path, source.assistant, status, fingerprint)
    }
    val report = if (checkedRepositories.isNotEmpty() && checkedSkills.size == imported.size && checkedAgents.size == agents.size) {
        SourceCheckReport(checkedRepositories, checkedSkills, checkedAgents)
    } else {
        null
    }
    return CachedSourceCheck(
        report = report,
        lastCheckedAt = revisions.keys.mapNotNull { stateByRepository[it]?.lastCheckedAt }.maxOrNull(),
    )
}

/**
 * Disposable source-check sidecar. Absence or an unreadable older sidecar starts with no state,
 * because source provenance in the library remains the source of truth.
 */
class SourcesStateStore(
    dir: Path = Path.of(System.getProperty("user.home")).resolve(".ruleblend"),
) {
    val file: Path = dir.resolve("sources-state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): SourcesState = FileReadScope.cached(file to "sources-state") {
        FileReadScope.textOrNull(file)
            ?.let { text -> runCatching { json.decodeFromString<SourcesState>(text) }.getOrNull() }
            ?: SourcesState()
    }

    fun save(state: SourcesState) {
        AtomicWrite.write(file, json.encodeToString(state) + "\n")
    }
}
