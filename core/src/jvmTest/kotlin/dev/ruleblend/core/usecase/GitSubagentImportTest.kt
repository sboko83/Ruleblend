package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.variant
import dev.ruleblend.core.storage.BlockFile
import dev.ruleblend.core.storage.GitRepositoryFetcher
import dev.ruleblend.core.storage.GitRepositoryImporter
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git

class GitSubagentImportTest {
    private lateinit var root: Path
    private lateinit var upstream: Path
    private lateinit var library: LibraryRepository
    private lateinit var imports: ImportLibrary
    private val agentPath = ".codex/agents/reviewer.toml"
    private val fetched = mutableListOf<String>()
    private var rejectCommit = false
    private val url: String get() = upstream.toUri().toString()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-git-subagents-")
        upstream = root.resolve("upstream").createDirectories()
        Git.init().setDirectory(upstream.toFile()).call().close()
        definition("Review carefully.")
        upstream.resolve("skills/review").createDirectories().resolve("SKILL.md").writeText("# Review\n")
        commit()
        val libraryRoot = root.resolve("library")
        val git = object : LibraryGit(libraryRoot) {
            override fun commit(message: String, relativePath: Path) {
                check(!rejectCommit) { "Injected commit failure" }
                super.commit(message, relativePath)
            }
        }
        library = LibraryRepository(libraryRoot, git).also { it.init() }
        imports = ImportLibrary(library, LibraryArchive(libraryRoot, library), ConfigStore(root.resolve("config")),
            GitRepositoryFetcher { fetched += it; GitRepositoryImporter().fetch(it) })
    }

    @AfterTest
    fun tearDown() = deleteFixtureTree(root)

    @Test
    fun `reimport retains identity memberships favorites and installed references`() {
        val original = importAgent()
        library.saveGroup(Group("reviewers", "Reviewers", blockIds = listOf(original.id)))
        library.saveProfile(Profile("review", "Review", subagentIds = listOf(original.id)))
        library.saveBlock(original.copy(favorite = true))
        val home = root.resolve("home").createDirectories()
        val adapter = CodexAdapter(home)
        val state = SubagentInstallStateStore(root.resolve("state"))
        val installer = SubagentInstallService(state)
        val target = AgentGlobalTarget(adapter)
        installer.install(target, original)
        val records = state.all()
        val installed = requireNotNull(adapter.globalSubagentFile(original.id)).readText()

        definition("Review harder.", "new-model")
        commit()
        val updated = importAgent()

        assertEquals(original.id, updated.id)
        assertEquals(2, updated.version)
        assertTrue(updated.favorite)
        assertEquals(listOf(original.id), library.loadGroup("reviewers")?.blockIds)
        assertEquals(listOf(original.id), library.loadProfile("review")?.subagentIds)
        assertEquals(records, state.all())
        assertEquals(installed, requireNotNull(adapter.globalSubagentFile(original.id)).readText())
        assertEquals(InstallStatus.UPDATE_AVAILABLE, installer.status(target, updated)?.status)
    }

    @Test
    fun `unrelated upstream commit advances provenance without definition version`() {
        val original = importAgent()
        upstream.resolve("README.md").writeText("Other change")
        commit()
        val updated = importAgent()
        assertNotEquals(original.source?.revision, updated.source?.revision)
        assertEquals(original.version, updated.version)
        assertEquals(original.source?.path, updated.source?.path)
        assertEquals("codex", updated.source?.assistant)
    }

    @Test
    fun `native fields and names update without changing local identity`() {
        val original = importAgent()
        definition("Review carefully.", "new-model")
        upstream.resolve(agentPath).writeText(upstream.resolve(agentPath).readText()
            .replace("name = \"reviewer\"", "name = \"renamed-reviewer\"") + "sandbox_mode = \"read-only\"\n")
        commit()
        val updated = imports.updateSubagents(setOf(original.id)).value.single()
        assertEquals(original.id, updated.id)
        assertEquals("renamed-reviewer", updated.name)
        assertEquals(original.version + 1, updated.version)
        assertEquals("read-only", updated.variant("codex")["sandbox_mode"])
        assertEquals(updated, importAgent())
    }

    @Test
    fun `mixed batch fetches once and keeps assistant fields isolated`() {
        val plan = imports.fetch(url)
        imports.applyGit(plan, setOf("skills/review"), mapOf(agentPath to "codex"))
        val agent = library.listBlocks().single()
        val skill = library.listSkills().single()
        definition("Updated.", "native-model")
        upstream.resolve("skills/review/SKILL.md").writeText("# New review\n")
        commit()
        fetched.clear()
        val updated = imports.updateDefinitions(setOf(skill.id), setOf(agent.id)).value
        assertEquals(listOf(url), fetched)
        assertEquals(agent.id, updated.subagents.single().id)
        assertEquals(skill.id, updated.skills.single().id)
        assertEquals("native-model", updated.subagents.single().variant("codex")["model"])
        assertTrue(updated.subagents.single().variant("claude-code").isEmpty)
        assertTrue(updated.subagents.single().variant("kimi-code").isEmpty)
        val home = root.resolve("other-home").createDirectories()
        val claude = ClaudeCodeAdapter(home)
        SubagentInstallService(SubagentInstallStateStore(root.resolve("other-state")))
            .install(AgentGlobalTarget(claude), updated.subagents.single())
        assertTrue("native-model" !in requireNotNull(claude.globalSubagentFile(agent.id)).readText())
    }

    @Test
    fun `missing batch path prevents all selected writes`() {
        val plan = imports.fetch(url)
        imports.applyGit(plan, setOf("skills/review"), mapOf(agentPath to "codex"))
        val agent = library.listBlocks().single()
        val skill = library.listSkills().single()
        Files.delete(upstream.resolve(agentPath))
        upstream.resolve("skills/review/SKILL.md").writeText("# New\n")
        commit()
        assertFailsWith<IllegalStateException> { imports.updateDefinitions(setOf(skill.id), setOf(agent.id)) }
        assertEquals(agent, library.loadBlock(agent.id))
        assertEquals(skill, library.loadSkill(skill.id))
    }

    @Test
    fun `unknown and local subagents are rejected before fetching`() {
        library.saveBlock(Block("local", "Local", type = BlockType.SUBAGENT, content = "Local"))
        assertFailsWith<IllegalArgumentException> { imports.updateSubagents(setOf("missing")) }
        assertFailsWith<IllegalArgumentException> { imports.updateSubagents(setOf("local")) }
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `all ordinary saves protect imported definitions and provenance`() {
        val original = importAgent()
        assertFailsWith<IllegalArgumentException> { library.saveBlock(original.copy(content = "Edited")) }
        assertFailsWith<IllegalArgumentException> { library.writeBlock(original.copy(content = "Edited")) }
        assertFailsWith<IllegalArgumentException> { library.saveBlock(original.copy(source = null)) }
        assertFailsWith<IllegalArgumentException> { library.writeBlock(original.copy(type = BlockType.RULE)) }
        assertFailsWith<IllegalArgumentException> { library.saveBlock(original.copy(name = "Changed")) }
        assertFailsWith<IllegalArgumentException> { library.saveBlock(original.copy(variants = emptyMap())) }
        assertEquals(original, library.loadBlock(original.id))
    }

    @Test
    fun `editable fork keeps provenance and stays independent of updates`() {
        val original = importAgent()
        val mutations = MutateLibrary(library, ConfigStore(root.resolve("config")))
        val fork = mutations.duplicateBlock(original).value
        assertNull(fork.source)
        assertEquals(original.source, fork.forkedFrom)
        assertEquals(1, fork.version)
        assertTrue(fork.name.endsWith("-changed"))
        val edited = library.saveBlock(fork.copy(content = "Local instructions"))
        definition("New upstream.")
        commit()
        imports.updateSubagents(setOf(original.id))
        assertEquals(edited, library.loadBlock(fork.id))
        assertNotEquals(original.source?.revision, library.loadBlock(original.id)?.source?.revision)
        val another = mutations.duplicateBlock(original).value
        assertNotEquals(fork.id, another.id)
    }

    @Test
    fun `ZIP retains imported and fork metadata and old blocks still load`() {
        val original = importAgent()
        val fork = library.forkSubagent(original.id, "changed", "Changed")
        library.saveBlock(Block("old", "Old", type = BlockType.SUBAGENT, content = "Old instructions"))
        val zip = root.resolve("library.zip")
        imports.export(zip)
        val targetRoot = root.resolve("target")
        val target = LibraryRepository(targetRoot).also { it.init() }
        val archive = LibraryArchive(targetRoot, target)
        val plan = archive.plan(zip)
        archive.apply(plan, plan.blocks.map { it.incoming.id }.toSet(), emptySet())
        assertEquals(original, target.loadBlock(original.id))
        assertEquals(fork, target.loadBlock(fork.id))
        assertNull(target.loadBlock("old")?.source)
        assertFailsWith<IllegalArgumentException> { target.saveBlock(original.copy(content = "Edit")) }
        assertEquals("Old instructions", BlockFile.parse("legacy", "---\nname: Legacy\ntype: subagent\n---\n\nOld instructions").content)
    }

    @Test
    fun `commit failure restores imported definition bytes and provenance`() {
        val original = importAgent()
        val file = root.resolve("library/blocks/${original.id}.md")
        val before = file.readText()
        definition("Updated.")
        commit()
        rejectCommit = true
        assertFailsWith<IllegalStateException> { imports.updateSubagents(setOf(original.id)) }
        assertEquals(before, file.readText())
        assertEquals(original, library.loadBlock(original.id))
    }

    @Test
    fun `failed initial import leaves no definition and failed fork retains original`() {
        val plan = imports.fetch(url)
        rejectCommit = true
        assertFailsWith<IllegalStateException> { imports.applyGit(plan, emptySet(), mapOf(agentPath to "codex")) }
        assertTrue(library.listBlocks().isEmpty())
        rejectCommit = false
        val original = importAgent()
        rejectCommit = true
        assertFailsWith<IllegalStateException> { library.forkSubagent(original.id, "copy", "Copy") }
        assertNull(library.loadBlock("copy"))
        assertEquals(original, library.loadBlock(original.id))
    }

    @Test
    fun `source check separates unrelated revisions definition updates and deleted paths`() {
        val original = importAgent()
        val checker = CheckSources(library)
        assertEquals(SourceCheckRepositoryStatus.UNCHANGED, checker.check().perRepo.single().status)
        upstream.resolve("README.md").writeText("Unrelated")
        commit()
        val unchanged = checker.check()
        assertEquals(SourceCheckRepositoryStatus.CHECKED, unchanged.perRepo.single().status)
        assertEquals(SourceCheckSkillStatus.CURRENT, unchanged.perSubagent.single().status)
        definition("Changed instructions.")
        commit()
        val changed = checker.check()
        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, changed.perSubagent.single().status)
        val cache = changed.toSourcesState("2026-10-08T12:00:00Z")
        val store = SourcesStateStore(root.resolve("cache"))
        store.save(cache)
        assertEquals(changed.perSubagent, store.load().cachedSourceCheck(library).report?.perSubagent)
        imports.updateSubagents(setOf(original.id))
        assertEquals(SourceCheckSkillStatus.CURRENT, cache.cachedSourceCheck(library).report?.perSubagent?.single()?.status)
        Files.delete(upstream.resolve(agentPath))
        commit()
        val deleted = checker.check()
        assertEquals(SourceCheckSkillStatus.PATH_MISSING, deleted.perSubagent.single().status)
        assertEquals(deleted.perSubagent, deleted.toSourcesState("now").cachedSourceCheck(library).report?.perSubagent)
    }

    @Test
    fun `source check shares a repository result for both item types`() {
        val plan = imports.fetch(url)
        imports.applyGit(plan, setOf("skills/review"), mapOf(agentPath to "codex"))
        definition("Updated.")
        commit()
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = CheckSources(library).check { done, total -> progress += done to total }
        assertEquals(1, result.perRepo.size)
        assertEquals(1, result.perSkill.size)
        assertEquals(1, result.perSubagent.size)
        assertEquals(listOf(0 to 1, 1 to 1), progress)
        assertEquals(SourceCheckSkillStatus.CURRENT, result.perSkill.single().status)
        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, result.perSubagent.single().status)
    }

    @Test
    fun `unavailable repository and malformed definition do not change local data`() {
        val original = importAgent()
        upstream.resolve(agentPath).writeText("not = [valid")
        commit()
        val malformed = CheckSources(library).check()
        assertEquals(SourceCheckSkillStatus.UNAVAILABLE, malformed.perSubagent.single().status)
        assertEquals(malformed.perSubagent, malformed.toSourcesState("now").cachedSourceCheck(library).report?.perSubagent)
        assertFailsWith<IllegalStateException> { imports.updateSubagents(setOf(original.id)) }
        assertEquals(original, library.loadBlock(original.id))
        deleteFixtureTree(upstream)
        val failed = CheckSources(library).check()
        assertEquals(SourceCheckRepositoryStatus.FAILED, failed.perRepo.single().status)
        assertEquals(SourceCheckSkillStatus.UNAVAILABLE, failed.perSubagent.single().status)
        assertEquals(original, library.loadBlock(original.id))
    }

    @Test
    fun `ambiguous Markdown requires an explicit supported assistant`() {
        upstream.resolve("agents").createDirectories().resolve("reader.md")
            .writeText("---\nname: reader\ndescription: Reads code\nmodel: fast\n---\n\nRead it.\n")
        commit()
        val plan = imports.fetch(url)
        assertFailsWith<IllegalStateException> { imports.applyGit(plan, emptySet(), mapOf("agents/reader.md" to "codex")) }
        assertTrue(library.listBlocks().isEmpty())
        imports.applyGit(plan, emptySet(), mapOf("agents/reader.md" to "kimi-code"))
        val agent = library.listBlocks().single()
        assertEquals("kimi-code", agent.source?.assistant)
        assertEquals("fast", agent.variant("kimi-code")["model"])
        assertTrue(agent.variant("claude-code").isEmpty)
    }

    private fun importAgent(): Block {
        imports.applyGit(imports.fetch(url), emptySet(), mapOf(agentPath to "codex"))
        return library.listBlocks().single { it.source?.path == agentPath }
    }

    private fun definition(instructions: String, model: String = "native-model") {
        upstream.resolve(agentPath).parent.createDirectories()
        upstream.resolve(agentPath).writeText("name = \"reviewer\"\ndescription = \"Reviews code\"\nmodel = \"$model\"\ndeveloper_instructions = \"$instructions\"\n")
    }

    private fun commit() {
        Git.open(upstream.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit().setMessage("Update source").setAuthor("Test", "test@example.com").call()
        }
    }
}
