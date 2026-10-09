package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.storage.GitRepositoryFetcher
import dev.ruleblend.core.storage.GitRepositoryImporter
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.SourcesStateStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode

class GitSourceToolsTest {
    private lateinit var root: Path
    private lateinit var upstream: Path
    private lateinit var libraryRoot: Path
    private lateinit var repository: LibraryRepository
    private lateinit var config: ConfigStore
    private lateinit var imports: ImportLibrary
    private lateinit var state: SourcesStateStore
    private lateinit var tools: RuleblendTools
    private var rejectCommit = false
    private var fetches = 0
    private val skillPath = "skills/review"
    private val agentPath = ".codex/agents/reviewer.toml"
    private val binary = byteArrayOf(0, 1, -1, 7)
    private val url: String get() = upstream.toUri().toString()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-mcp-git-")
        upstream = root.resolve("upstream").createDirectories()
        Git.init().setDirectory(upstream.toFile()).call().close()
        write("$skillPath/SKILL.md", "---\nname: review\ndescription: Review code\n---\n# Review\n")
        write("$skillPath/run.sh", "#!/bin/sh\necho review\n")
        Files.write(upstream.resolve("$skillPath/image.bin"), binary)
        writeAgent("Original instructions")
        write("agents/ambiguous.md", "---\nname: helper\ndescription: Help\nmodel: inherit\n---\nHelp carefully.\n")
        write(".codex/agents/broken.toml", "name = \"broken\"\ndeveloper_instructions = \"Review\"\n[mcp_servers.browser]\ncommand = \"node\"\n")
        commit()
        // Set the index bit explicitly: Windows filesystems do not expose POSIX executable flags.
        Git.open(upstream.toFile()).use { git ->
            val index = git.repository.lockDirCache()
            try {
                val editor = index.editor()
                editor.add(object : DirCacheEditor.PathEdit("$skillPath/run.sh") {
                    override fun apply(entry: DirCacheEntry) { entry.fileMode = FileMode.EXECUTABLE_FILE }
                })
                assertTrue(editor.commit())
            } finally {
                index.unlock()
            }
            git.commit().setMessage("Executable script").setAuthor("Test", "test@example.com").call()
        }
        libraryRoot = root.resolve("library")
        val git = object : LibraryGit(libraryRoot) {
            override fun commit(message: String, relativePath: Path) {
                check(!rejectCommit) { "Injected commit failure" }
                super.commit(message, relativePath)
            }
        }
        repository = LibraryRepository(libraryRoot, git).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        imports = ImportLibrary(repository, LibraryArchive(libraryRoot, repository), config, GitRepositoryFetcher {
            fetches++
            GitRepositoryImporter().fetch(it)
        })
        state = SourcesStateStore(root)
        tools = RuleblendTools(repository, config, emptyList(), gitImports = imports, sourcesState = state)
    }

    @AfterTest
    fun tearDown() {
        val target = root.toAbsolutePath().normalize()
        require(target.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { it.toFile().setWritable(true); Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `preview isolates bad definitions and reads original and ambiguous formats without writing`() {
        val before = libraryHead()
        val preview = preview()
        assertEquals(url, preview.text("repository"))
        assertEquals(1, preview["skills"]!!.jsonArray.size)
        assertEquals(2, preview["subagents"]!!.jsonArray.size)
        val ambiguous = preview["subagents"]!!.jsonArray.map { it.jsonObject }.single { it.text("path") == "agents/ambiguous.md" }
        assertTrue(ambiguous["requires_assistant_selection"]!!.jsonPrimitive.boolean)
        assertEquals(setOf("claude-code", "kimi-code"), ambiguous["assistants"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val entry = entry(preview, "subagent", agentPath)
        assertTrue(entry.text("source_text").contains("Original instructions"))
        assertEquals("Original instructions", entry["definitions"]!!.jsonObject["codex"]!!.jsonObject.text("content"))
        val error = preview["errors"]!!.jsonArray.single().jsonObject
        assertTrue(entry(preview, "error", error.text("path")).text("source_text").contains("[mcp_servers.browser]"))
        assertEquals(3, entry(preview, "skill", skillPath)["files"]!!.jsonArray.size)
        assertTrue(repository.listSkills().isEmpty())
        assertTrue(repository.listBlocks().isEmpty())
        assertEquals(before, libraryHead())
    }

    @Test
    fun `invalid mixed selections and malformed arguments write nothing`() {
        val preview = preview()
        val before = libraryHead()
        val invalid = listOf(
            selection(preview, agent = ".codex/agents/broken.toml"),
            selection(preview, assistant = "claude-code"),
            selection(preview, agent = "../../outside"),
            args("preview_id" to preview["preview_id"]!!),
            args("preview_id" to preview["preview_id"]!!, "skill_paths" to JsonArray(listOf(JsonPrimitive(3)))),
            args("preview_id" to preview["preview_id"]!!, "subagents" to JsonPrimitive("codex")),
            args("preview_id" to JsonPrimitive("unknown"), "skill_paths" to array(skillPath)),
        )
        invalid.forEach { assertFailsWith<ToolError> { tools.applyGitImport(it) } }
        assertTrue(repository.listSkills().isEmpty())
        assertTrue(repository.listBlocks().isEmpty())
        assertEquals(before, libraryHead())
    }

    @Test
    fun `apply uses reviewed bytes and reimport retains ids memberships favorites and source metadata`() {
        val preview = preview()
        val reviewedRevision = preview.text("revision")
        writeAgent("New upstream instructions")
        commit()
        val imported = tools.applyGitImport(selection(preview)).jsonObject
        assertEquals(1, fetches, "Apply must not refetch a different snapshot")
        val skill = imported["skills"]!!.jsonArray.single().jsonObject
        val agent = imported["subagents"]!!.jsonArray.single().jsonObject
        assertEquals(reviewedRevision, agent["git_source"]!!.jsonObject.text("revision"))
        assertFalse(agent["editable"]!!.jsonPrimitive.boolean)
        val skillId = skill.text("id")
        val agentId = agent.text("id")
        repository.saveGroup(Group("team", "Team", blockIds = listOf(agentId), skillIds = listOf(skillId)))
        repository.saveProfile(Profile("work", "Work", subagentIds = listOf(agentId), skillIds = listOf(skillId)))
        repository.saveBlock(repository.loadBlock(agentId)!!.copy(favorite = true))
        assertEquals("Original instructions", repository.loadBlock(agentId)!!.content)
        tools.applyGitImport(selection(preview()))
        assertEquals(1, repository.listSkills().size)
        assertEquals(1, repository.listBlocks().size)
        assertEquals("New upstream instructions", repository.loadBlock(agentId)!!.content)
        assertTrue(repository.loadBlock(agentId)!!.favorite)
        assertEquals(listOf(agentId), repository.loadGroup("team")!!.blockIds)
        assertEquals(listOf(skillId), repository.loadProfile("work")!!.skillIds)
        val restarted = RuleblendTools(LibraryRepository(libraryRoot), config, emptyList())
        assertEquals(url, restarted.getSkill(args("id" to JsonPrimitive(skillId))).jsonObject["git_source"]!!.jsonObject.text("repository"))
        assertEquals("codex", restarted.getSubagent(args("id" to JsonPrimitive(agentId))).jsonObject["git_source"]!!.jsonObject.text("assistant"))
    }

    @Test
    fun `ambiguous Markdown imports only the selected assistant variant`() {
        val preview = preview()
        tools.applyGitImport(args("preview_id" to preview["preview_id"]!!, "subagents" to args("agents/ambiguous.md" to JsonPrimitive("kimi-code"))))
        val agent = repository.listBlocks().single()
        assertEquals("kimi-code", agent.source!!.assistant)
        assertEquals(setOf("kimi-code"), agent.variants.keys)
    }

    @Test
    fun `source checks cache shared results and selected updates fetch once and retain identity`() {
        tools.applyGitImport(selection(preview()))
        val skill = repository.listSkills().single()
        val agent = repository.listBlocks().single()
        assertEquals(1, tools.listSources(args()).jsonArray.size)
        assertStatus(tools.checkSources(args()), "current", "current")
        assertEquals(url, state.load().sources.single().repository)
        writeAgent("Updated instructions")
        write("$skillPath/image.bin", "changed bytes")
        commit()
        assertStatus(tools.checkSources(args()), "update_available", "update_available")
        assertEquals(state.load().sources.single().lastCheckedAt,
            tools.listSources(args()).jsonArray.single().jsonObject.text("last_checked_at"))
        assertEquals("Original instructions", repository.loadBlock(agent.id)!!.content)
        val fetchesBefore = fetches
        val updated = tools.updateFromSources(args("skill_ids" to array(skill.id), "subagent_ids" to array(agent.id))).jsonObject
        assertEquals(fetchesBefore + 1, fetches)
        assertEquals(skill.id, updated["skills"]!!.jsonArray.single().jsonObject.text("id"))
        assertEquals(agent.id, updated["subagents"]!!.jsonArray.single().jsonObject.text("id"))
        assertEquals("Updated instructions", repository.loadBlock(agent.id)!!.content)
        assertStatus(tools.checkSources(args()), "current", "current")
    }

    @Test
    fun `missing upstream definitions and invalid update ids never refresh a valid subset`() {
        tools.applyGitImport(selection(preview()))
        val skill = repository.listSkills().single()
        val agent = repository.listBlocks().single()
        val original = repository.loadSkillSnapshot(skill.id)!!
        write("$skillPath/image.bin", "new bytes")
        upstream.resolve(agentPath).deleteExisting()
        commit()
        assertStatus(tools.checkSources(args()), "update_available", "path_missing")
        assertFailsWith<ToolError> {
            tools.updateFromSources(args("skill_ids" to array(skill.id), "subagent_ids" to array(agent.id)))
        }
        assertEquals(original, repository.loadSkillSnapshot(skill.id))
        assertFailsWith<ToolError> { tools.updateFromSources(args("skill_ids" to array(skill.id, "missing"))) }
        assertEquals(original, repository.loadSkillSnapshot(skill.id))
        tools.updateFromSources(args("skill_ids" to array(skill.id)))
        assertNotEquals(original, repository.loadSkillSnapshot(skill.id))
    }

    @Test
    fun `unavailable repository reports separate failures for all its definitions`() {
        tools.applyGitImport(selection(preview()))
        upstream.resolve(".git/HEAD").deleteExisting()
        val report = tools.checkSources(args()).jsonObject
        assertStatus(report, "unavailable", "unavailable")
        val source = report["repositories"]!!.jsonArray.single().jsonObject
        assertEquals("failed", source.text("status"))
        assertTrue(source.text("error").isNotBlank())
        assertEquals(1, tools.listSources(args()).jsonArray.size)
    }

    @Test
    fun `forks preserve binary assets executable flags native fields and origin while enabling edits`() {
        tools.applyGitImport(selection(preview()))
        val skill = repository.listSkills().single()
        val agent = repository.listBlocks().single()
        assertFailsWith<ToolError> { tools.updateSkill(args("id" to JsonPrimitive(skill.id), "content" to JsonPrimitive("Edit"))) }
        assertFailsWith<ToolError> { tools.updateSubagent(args("id" to JsonPrimitive(agent.id), "content" to JsonPrimitive("Edit"))) }
        val fork = tools.forkSkill(args("id" to JsonPrimitive(skill.id))).jsonObject
        val forkId = fork.text("id")
        val snapshot = repository.loadSkillSnapshot(forkId)!!
        assertTrue(fork["editable"]!!.jsonPrimitive.boolean)
        assertNull(fork["git_source"])
        assertEquals(url, fork["forked_from"]!!.jsonObject.text("repository"))
        assertContentEquals(binary, snapshot.files.single { it.path == "image.bin" }.bytes)
        assertTrue(snapshot.files.single { it.path == "run.sh" }.executable)
        tools.updateSkill(args("id" to JsonPrimitive(forkId), "content" to JsonPrimitive("Edited locally")))
        assertContentEquals(binary, repository.loadSkillSnapshot(forkId)!!.files.single { it.path == "image.bin" }.bytes)
        val forkAgent = tools.forkSubagent(args("id" to JsonPrimitive(agent.id))).jsonObject
        val forkAgentId = forkAgent.text("id")
        assertEquals(1, repository.loadBlock(forkAgentId)!!.version)
        assertEquals(agent.variants, repository.loadBlock(forkAgentId)!!.variants)
        tools.updateSubagent(args("id" to JsonPrimitive(forkAgentId), "content" to JsonPrimitive("Local instructions")))
        assertEquals(agent.source, repository.loadBlock(forkAgentId)!!.forkedFrom)
        assertEquals(agent, repository.loadBlock(agent.id))
        assertEquals(skill, repository.loadSkill(skill.id))
        assertNotEquals(forkId, tools.forkSkill(args("id" to JsonPrimitive(skill.id))).jsonObject.text("id"))
        assertFailsWith<ToolError> { tools.forkSkill(args("id" to JsonPrimitive(forkId))) }
        assertFailsWith<ToolError> { tools.updateFromSources(args("skill_ids" to array(forkId))) }
    }

    @Test
    fun `expired and evicted previews require another review`() {
        var time = Instant.parse("2026-10-08T00:00:00Z")
        val git = GitSourceTools(repository, config, imports, state) { time }
        val first = git.preview(args("repository" to JsonPrimitive(url))).jsonObject
        val second = git.preview(args("repository" to JsonPrimitive(url))).jsonObject
        git.preview(args("repository" to JsonPrimitive(url)))
        assertFailsWith<ToolError> { git.apply(selection(first)) }
        assertTrue(git.entry(args("preview_id" to second["preview_id"]!!, "kind" to JsonPrimitive("skill"), "path" to JsonPrimitive(skillPath))).jsonObject.containsKey("content"))
        time = time.plusSeconds(600)
        assertFailsWith<ToolError> { git.apply(selection(second)) }
        assertTrue(repository.listSkills().isEmpty())
    }

    @Test
    fun `failed atomic write restores original tree and preview can be retried`() {
        val preview = preview()
        val before = libraryHead()
        val beforeFiles = changedFiles()
        rejectCommit = true
        assertFailsWith<ToolError> { tools.applyGitImport(selection(preview)) }
        assertTrue(repository.listSkills().isEmpty())
        assertTrue(repository.listBlocks().isEmpty())
        assertEquals(before, libraryHead())
        assertEquals(beforeFiles, changedFiles())
        rejectCommit = false
        tools.applyGitImport(selection(preview))
        assertEquals(1, repository.listSkills().size)
        assertEquals(1, repository.listBlocks().size)
    }

    @Test
    fun `a skill replaced while fetching is preserved and aborts the entire update selection`() {
        tools.applyGitImport(selection(preview()))
        val skill = repository.listSkills().single()
        val agent = repository.listBlocks().single()
        writeAgent("Updated upstream")
        commit()
        val concurrentImports = ImportLibrary(repository, LibraryArchive(libraryRoot, repository), config,
            GitRepositoryFetcher {
                val plan = GitRepositoryImporter().fetch(it)
                repository.deleteSkill(skill.id)
                repository.createSkill(skill.copy(source = null, content = "# Local replacement\n"))
                plan
            })
        val concurrentTools = RuleblendTools(repository, config, emptyList(), gitImports = concurrentImports)
        val error = assertFailsWith<ToolError> {
            concurrentTools.updateFromSources(args("skill_ids" to array(skill.id), "subagent_ids" to array(agent.id)))
        }
        assertTrue(error.message.orEmpty().contains("Skill changed"))
        assertEquals("# Local replacement\n", repository.loadSkill(skill.id)!!.content)
        assertNull(repository.loadSkill(skill.id)!!.source)
        assertEquals(agent, repository.loadBlock(agent.id))
    }

    @Test
    fun `unsafe repository URLs fail as tool errors without library writes`() {
        listOf("ssh://example.com/repo", "https://user:secret@example.com/repo").forEach { url ->
            assertFailsWith<ToolError> { tools.previewGitImport(args("repository" to JsonPrimitive(url))) }
        }
        assertTrue(repository.listSkills().isEmpty())
    }

    private fun preview(): JsonObject = tools.previewGitImport(args("repository" to JsonPrimitive(url))).jsonObject

    private fun entry(preview: JsonObject, kind: String, path: String): JsonObject = tools.getGitImportEntry(
        args("preview_id" to preview["preview_id"]!!, "kind" to JsonPrimitive(kind), "path" to JsonPrimitive(path)),
    ).jsonObject

    private fun selection(preview: JsonObject, agent: String = agentPath, assistant: String = "codex"): JsonObject = args(
        "preview_id" to preview["preview_id"]!!,
        "skill_paths" to array(skillPath),
        "subagents" to args(agent to JsonPrimitive(assistant)),
    )

    private fun assertStatus(report: JsonElement, skill: String, agent: String) {
        assertEquals(skill, report.jsonObject["skills"]!!.jsonArray.single().jsonObject.text("status"))
        assertEquals(agent, report.jsonObject["subagents"]!!.jsonArray.single().jsonObject.text("status"))
    }

    private fun writeAgent(content: String) = write(agentPath,
        "name = \"reviewer\"\ndescription = \"Review changes\"\nmodel = \"native-model\"\ndeveloper_instructions = \"$content\"\n")

    private fun write(path: String, text: String) {
        val file = upstream.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun commit() = Git.open(upstream.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
        git.commit().setMessage("Update fixture").setAuthor("Test", "test@example.com").call()
    }

    private fun libraryHead(): String? = Git.open(libraryRoot.toFile()).use { it.repository.resolve("HEAD")?.name }
    private fun changedFiles(): Set<String> = Git.open(libraryRoot.toFile()).use {
        val status = it.status().call()
        status.uncommittedChanges + status.untracked
    }
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun args(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }
    private fun array(vararg values: String): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
