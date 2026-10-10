package dev.ruleblend.core.storage

import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.SkillSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.URIish

/** One checkout shared by skill, subagent and mod discovery. Fetch never writes library objects. */
data class GitRepositoryImportPlan(
    val repository: String,
    val revision: String,
    val version: String,
    val skills: List<SkillSnapshot>,
    val subagents: List<GitSubagentCandidate> = emptyList(),
    val errors: List<GitImportFileError> = emptyList(),
    val mods: List<ModImportCandidate> = emptyList(),
    /** Hidden in default package preview, retained for updating previously extracted definitions. */
    val bundledSkills: List<SkillSnapshot> = emptyList(),
    val bundledSubagents: List<GitSubagentCandidate> = emptyList(),
) {
    val allSkills: List<SkillSnapshot> get() = skills + bundledSkills
    val allSubagents: List<GitSubagentCandidate> get() = subagents + bundledSubagents
}

fun interface GitRepositoryFetcher {
    fun fetch(repositoryUrl: String): GitRepositoryImportPlan
}

typealias GitSkillImportPlan = GitRepositoryImportPlan
typealias GitSkillFetcher = GitRepositoryFetcher

private data class PluginManifest(
    val version: String? = null,
    val skills: String? = null,
    val agents: List<GitAgentRoot> = emptyList(),
)

/** Compatibility entry point for existing skill import and source-update callers. */
class GitSkillImporter : GitSkillFetcher by GitRepositoryImporter(extractModContents = true)

/** Discovers definitions and whole packages from the same bounded, temporary checkout. */
class GitRepositoryImporter(
    private val limits: GitImportLimits = GitImportLimits(),
    private val extractModContents: Boolean = false,
) : GitRepositoryFetcher {
    private val json = Json { ignoreUnknownKeys = true }

    override fun fetch(repositoryUrl: String): GitRepositoryImportPlan {
        val repository = validateGitRepositoryUrl(repositoryUrl)
        val checkout = Files.createTempDirectory("ruleblend-git-import-")
        try {
            return withGitCredentials { credentials ->
                Git.cloneRepository()
                    .setURI(repository)
                    .setDirectory(checkout.toFile())
                    .setDepth(1)
                    .setCloneSubmodules(false)
                    .setNoCheckout(true)
                    .setTimeout(20)
                    .setCredentialsProvider(credentials)
                    .call()
            }.use { git ->
                val paths = git.prepareImportCheckout(limits)
                val revision = git.repository.resolve("HEAD")?.name
                    ?: error("Repository has no HEAD revision")
                val mods = discoverGitMods(checkout, paths, repository, revision)
                val manifestErrors = mutableListOf<GitImportFileError>()
                val manifest = try { readManifest(checkout) } catch (error: Exception) {
                    if (mods.isEmpty()) throw error
                    manifestErrors += GitImportFileError(".claude-plugin/plugin.json", error.message ?: "Invalid manifest")
                    null
                }
                val version = manifest?.version?.takeIf { it.isNotBlank() } ?: revision.take(12)
                val skillsRoot = resolveSkillsRoot(checkout, manifest?.skills)
                val directories = discoverSkillDirectories(skillsRoot)
                val snapshots = directories.map { directory ->
                    captureSkill(checkout, directory, repository, revision, version)
                }
                val discovery = try { discoverGitSubagents(checkout, paths, manifest?.agents.orEmpty(), limits) } catch (error: Exception) {
                    if (mods.none { it.snapshot != null }) throw error
                    GitSubagentDiscovery(emptyList(), listOf(GitImportFileError("agents", error.message ?: "Invalid agents paths")))
                }
                require(snapshots.isNotEmpty() || discovery.candidates.isNotEmpty() || discovery.errors.isNotEmpty() || mods.isNotEmpty()) {
                    "No skills, subagents or mods found in the repository"
                }
                fun bundled(path: String): Boolean = !extractModContents && mods.any { candidate ->
                    candidate.snapshot != null && (candidate.path == "." || path.startsWith(candidate.path + "/"))
                }
                GitRepositoryImportPlan(repository, revision, version,
                    snapshots.filterNot { bundled(requireNotNull(it.skill.source).path) },
                    discovery.candidates.filterNot { bundled(it.path) }, discovery.errors + manifestErrors, mods,
                    snapshots.filter { bundled(requireNotNull(it.skill.source).path) },
                    discovery.candidates.filter { bundled(it.path) })
            }
        } finally {
            checkout.toFile().deleteRecursively()
        }
    }

    private fun readManifest(root: Path): PluginManifest? {
        fun decode(file: Path, assistant: String?): PluginManifest? {
            if (!file.isRegularFile()) return null
            require(Files.size(file) <= limits.maxSubagentBytes) { "Manifest is too large: ${root.relativize(file)}" }
            val node = json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Expected a plugin object")
            fun string(key: String): String? = node[key]?.let {
                require(it is JsonPrimitive && it.isString) { "Expected a string for plugin $key" }
                it.contentOrNull
            }
            val agents = if (assistant == null) emptyList() else when (val value = node["agents"]) {
                null -> emptyList()
                is JsonPrimitive -> {
                    require(value.isString) { "Expected an agents path" }
                    listOf(GitAgentRoot(value.content, assistant))
                }
                is JsonArray -> value.map {
                    require(it is JsonPrimitive && it.isString) { "Expected an agents path" }
                    GitAgentRoot(it.content, assistant)
                }
                else -> error("Expected an agents path or path list")
            }
            return PluginManifest(string("version"), if (assistant == "codex") string("skills") else null, agents)
        }
        val codex = decode(root.resolve(".codex-plugin/plugin.json"), "codex")
        val claude = decode(root.resolve(".claude-plugin/plugin.json"), "claude-code")
        val packageJson = decode(root.resolve("package.json"), null)
        if (codex == null && claude == null && packageJson == null) return null
        return PluginManifest(
            version = codex?.version ?: claude?.version ?: packageJson?.version,
            skills = codex?.skills,
            agents = codex?.agents.orEmpty() + claude?.agents.orEmpty(),
        )
    }

    private fun resolveSkillsRoot(root: Path, declared: String?): Path {
        val relative = (declared ?: "skills").removePrefix("./").removeSuffix("/")
        require(relative.isNotBlank()) { "Plugin skills path is empty" }
        val resolved = resolveGitImportPath(root, relative)
        require(declared == null || resolved.isDirectory()) { "Plugin skills directory does not exist: $relative" }
        return resolved
    }

    private fun discoverSkillDirectories(root: Path): List<Path> {
        if (!root.isDirectory()) return emptyList()
        if (root.resolve("SKILL.md").isRegularFile()) return listOf(root)
        return Files.list(root).use { children ->
            children.filter { it.isDirectory() && it.resolve("SKILL.md").isRegularFile() }
                .sorted()
                .toList()
        }
    }

    private fun captureSkill(
        repositoryRoot: Path,
        directory: Path,
        repository: String,
        revision: String,
        version: String,
    ): SkillSnapshot {
        val sourcePath = repositoryRoot.relativize(directory).joinToString("/")
        return LocalSkillCapture.capture(
            directory = directory,
            source = GitSkillSource(repository, revision, sourcePath),
            version = version,
            executableFiles = gitSkillExecutableFiles(repositoryRoot, directory),
        )
    }

}

/**
 * The only repository URLs Ruleblend contacts. Provenance can arrive from an imported library
 * archive as easily as from the import dialog, so every caller that reaches a remote validates the
 * URL again rather than trusting what is stored.
 */
fun validateGitRepositoryUrl(raw: String): String {
    val url = raw.trim().removeSuffix(".")
    require(url.isNotEmpty()) { "Repository URL is empty" }
    val uri = URIish(url)
    require(uri.user == null && uri.pass == null) {
        "Keep credentials in a Git credential helper, not in the repository URL"
    }
    require(uri.scheme == "https" || uri.scheme == "file" || (uri.scheme == null && uri.host == null)) {
        "Only HTTPS and local repository URLs are supported"
    }
    if (uri.scheme == null && uri.host == null) {
        require(Path.of(url).exists()) { "Local repository does not exist: $url" }
    }
    return url
}

/**
 * A skill directory may be the repository root itself, and the checkout's own `.git` is no part of
 * the skill. Capturing it would install clone bookkeeping as skill content and, because a fresh
 * clone writes different index and log files every time, would report an update that never ends.
 */
fun isGitInternalPath(root: Path, file: Path): Boolean =
    root.relativize(file).any { it.toString() == ".git" }
