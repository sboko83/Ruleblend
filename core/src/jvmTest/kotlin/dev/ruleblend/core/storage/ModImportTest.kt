package dev.ruleblend.core.storage

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.usecase.CheckSources
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.usecase.MutateLibrary
import dev.ruleblend.core.usecase.SourceCheckSkillStatus
import dev.ruleblend.core.usecase.SourcesStateStore
import dev.ruleblend.core.usecase.cachedSourceCheck
import dev.ruleblend.core.usecase.toSourcesState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode

/** Offline source gate; fixture code is captured as bytes and never evaluated. */
class ModImportTest {
    private val scratch = Files.createTempDirectory("ruleblend-mod-import")

    @AfterTest
    fun cleanup() = deleteFixtureTree(scratch)

    private fun files(name: String = "demo", custom: Boolean = false, body: String = "export function register(on) {}\r\n"): Map<String, String> {
        val hooks = if (custom) "config/custom.json" else "hooks/hooks.json"
        val module = hooks.substringBeforeLast('/') + "/register.js"
        return mapOf(
            ".claude-plugin/plugin.json" to """{"name":"$name","version":"opaque-dev","description":"Fixture"${if (custom) ",\"hooks\":[\"./config/custom.json\"]" else ""}}""",
            hooks to """{"modules":["./register.js"]}""",
            module to body,
            "skills/review/SKILL.md" to "---\nname: review\ndescription: Review\n---\nReview.\n",
            "agents/reviewer.md" to "---\nname: reviewer\ndescription: Reviews\n---\nReview.\n",
            "commands/status.md" to "Status.\n",
            ".mcp.json" to """{"fixture":{"command":"inert-fixture"}}""",
        )
    }

    private fun write(root: Path, files: Map<String, String>) {
        files.forEach { (path, text) -> root.resolve(path).also { it.parent.createDirectories() }.writeText(text) }
    }

    private fun upstream(name: String, files: Map<String, String>): Path {
        val path = scratch.resolve(name).createDirectories()
        write(path, files)
        Git.init().setDirectory(path.toFile()).call().use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Fixture").setAuthor("Test", "test@example.com").call()
        }
        return path
    }

    private fun commit(path: Path) = Git.open(path.toFile()).use { git ->
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
        git.commit().setMessage("Update fixture").setAuthor("Test", "test@example.com").call()
    }

    private fun library(): LibraryRepository = LibraryRepository(scratch.resolve("library")).also { it.init() }
    private fun imports(repo: LibraryRepository, fetcher: GitRepositoryFetcher = GitSkillImporter()): ImportLibrary =
        ImportLibrary(repo, LibraryArchive(scratch.resolve("library"), repo), ConfigStore(scratch.resolve("config")), fetcher)

    @Test
    fun `root package preview keeps every component and exact Git mode without duplicate definitions`() {
        val source = upstream("upstream", files())
        Git.open(source.toFile()).use { git ->
            val index = git.repository.lockDirCache()
            index.getEntry("hooks/register.js").fileMode = FileMode.EXECUTABLE_FILE
            index.write()
            index.commit()
            git.commit().setMessage("Make executable").setAuthor("Test", "test@example.com").call()
        }
        val plan = GitRepositoryImporter().fetch(source.toString())
        val candidate = plan.mods.single()
        val snapshot = assertNotNull(candidate.snapshot)
        assertEquals(".", candidate.path)
        assertEquals(40, snapshot.mod.source?.revision?.length)
        assertEquals(plan.revision, snapshot.mod.source?.revision)
        assertEquals(files().keys, candidate.files.toSet())
        assertEquals(setOf("hooks", "skills", "agents", "commands", "mcpServers"), candidate.components)
        assertTrue(snapshot.files.single { it.path == "hooks/register.js" }.executable)
        assertTrue(snapshot.files.single { it.path == "hooks/register.js" }.bytes.decodeToString().endsWith("\r\n"))
        assertTrue(plan.skills.isEmpty())
        assertTrue(plan.subagents.isEmpty())
        val extraction = GitSkillImporter().fetch(source.toString())
        assertEquals(1, extraction.skills.size)
        assertEquals(1, extraction.subagents.size)
    }

    @Test
    fun `nested roots and marketplace entries deduplicate while unsupported sources remain explained`() {
        val nested = files(custom = true).mapKeys { "plugins/demo/${it.key}" } +
            files("second").mapKeys { "collection/second/${it.key}" } + mapOf(
                ".claude-plugin/marketplace.json" to """{"name":"fixture","plugins":[
                    {"name":"alias","source":"./plugins/demo"},
                    {"name":"remote","source":{"source":"url","url":"https://example.com/unfetched.git"}},
                    {"name":"escape","source":"./../escape"},
                    {"name":"missing","source":"./missing"}
                ]}""",
            )
        val source = upstream("nested", nested)
        val plan = GitRepositoryImporter().fetch(source.toString())
        assertEquals(setOf("plugins/demo", "collection/second"), plan.mods.filter { it.snapshot != null }.map { it.path }.toSet())
        assertEquals(3, plan.mods.count { it.error != null })
        assertTrue(plan.mods.any { it.error?.contains("no source was fetched") == true })
        assertEquals("demo", plan.mods.first { it.path == "plugins/demo" }.snapshot?.mod?.manifestName)
    }

    @Test
    fun `malformed hooks dependencies inline modules and escaping components produce detached errors`() {
        val cases = listOf(
            files() + ("hooks/hooks.json" to """{"modules":"./register.js"}"""),
            files() + ("hooks/hooks.json" to """{"modules":[4]}"""),
            files() + ("hooks/hooks.json" to """{"modules":["./register.js","./other.js"]}"""),
            files() + ("hooks/hooks.json" to """{"modules":["../../outside.js"]}"""),
            files() + ("hooks/hooks.json" to """{"modules":["./missing.js"]}"""),
            files() + ("hooks/hooks.json" to """{"hooks":{"PostToolUse":[]}}"""),
            files() + ("hooks/register.js" to ""),
            files() + ("hooks/hooks.json" to """{"modules":["./register.txt"]}""") + ("hooks/register.txt" to "text"),
            files() + (".claude-plugin/plugin.json" to """{"name":"demo","dependencies":["other"]}"""),
            files() + (".claude-plugin/plugin.json" to """{"name":"demo","hooks":{"modules":["./register.js"]}}"""),
            files() + (".claude-plugin/plugin.json" to """{"name":"demo","skills":"./../outside"}"""),
            files() + ("package.json" to """{"scripts":{"postinstall":"touch should-never-exist"}}"""),
            files() + ("package.json" to """{"dependencies":{"unavailable":"*"}}"""),
            files() + (".claude-plugin/plugin.json" to "{broken"),
        )
        val repo = library()
        cases.forEachIndexed { index, contents ->
            val root = scratch.resolve("invalid-$index").createDirectories()
            write(root, contents)
            val preview = imports(repo).previewMod(root)
            assertNull(preview.snapshot, "Case $index")
            assertNotNull(preview.error, "Case $index")
            assertFailsWith<IllegalArgumentException> { imports(repo).applyMod(preview) }
        }
        assertTrue(repo.listMods().isEmpty())
        assertFalse(Files.exists(scratch.resolve("should-never-exist")))
    }

    @Test
    fun `a malformed package does not hide its valid sibling`() {
        val contents = files().mapKeys { "good/${it.key}" } + mapOf("bad/.claude-plugin/plugin.json" to "{broken")
        val plan = GitRepositoryImporter().fetch(upstream("mixed", contents).toString())
        assertNotNull(plan.mods.first { it.path == "good" }.snapshot)
        assertNotNull(plan.mods.first { it.path == "bad" }.error)
    }

    @Test
    fun `local snapshots and selected external roots preserve references and remove vanished files`() {
        val repo = library()
        val service = imports(repo)
        val local = scratch.resolve("selected-installed-root").createDirectories()
        write(local, files(custom = true))
        val preview = service.previewMod(local)
        assertTrue(repo.listMods().isEmpty())
        val saved = service.applyMod(preview).value
        repo.saveGroup(Group("chosen", "Chosen", modIds = listOf(saved.id)))
        Files.delete(local.resolve("commands/status.md"))
        local.resolve("config/register.js").writeText("export function register(on) { /* updated */ }\n")
        assertEquals(saved.id, service.applyMod(service.previewMod(local), saved.id).value.id)
        assertEquals(listOf(saved.id), repo.loadGroup("chosen")?.modIds)
        assertFalse(repo.loadModSnapshot(saved.id)!!.files.any { it.path == "commands/status.md" })
        assertFailsWith<IllegalArgumentException> {
            MutateLibrary(repo, ConfigStore(scratch.resolve("config"))).writeMod(repo.loadModSnapshot(saved.id)!!)
        }
        assertEquals(1, repo.listMods().size)
    }

    @Test
    fun `Git updates fetch once for mixed types preserve memberships and never auto apply`() {
        val source = upstream("upstream", files())
        val repo = library()
        var fetches = 0
        val service = imports(repo, GitRepositoryFetcher { fetches++; GitRepositoryImporter().fetch(it) })
        val extraction = imports(repo)
        val plan = extraction.fetch(source.toString())
        extraction.applyGit(plan, setOf("skills/review"), mapOf("agents/reviewer.md" to "claude-code"), setOf("."))
        val saved = repo.listMods().single()
        repo.saveGroup(Group("chosen", "Chosen", modIds = listOf(saved.id)))
        val old = repo.loadModSnapshot(saved.id)!!
        source.resolve("hooks/register.js").writeText("export function register(on) { /* new */ }\n")
        Files.delete(source.resolve("commands/status.md"))
        commit(source)
        val check = CheckSources(repo).check()
        assertEquals(1, check.perRepo.size)
        assertEquals(SourceCheckSkillStatus.UPDATE_AVAILABLE, check.perMod.single().status)
        assertEquals(old.files, repo.loadModSnapshot(saved.id)?.files)
        fetches = 0
        service.updateDefinitions(repo.listSkills().map { it.id }.toSet(), repo.listBlocks().map { it.id }.toSet(), setOf(saved.id))
        assertEquals(1, fetches)
        assertEquals(saved.id, repo.listMods().single().id)
        assertEquals(listOf(saved.id), repo.loadGroup("chosen")?.modIds)
        assertNotEquals(old.mod.source?.revision, repo.loadMod(saved.id)?.source?.revision)
        assertFalse(repo.loadModSnapshot(saved.id)!!.files.any { it.path == "commands/status.md" })
        assertEquals(SourceCheckSkillStatus.CURRENT, CheckSources(repo).check().perMod.single().status)
    }

    @Test
    fun `same upstream identity reimport keeps id and colliding names get separate objects`() {
        val source = upstream("upstream", files().mapKeys { "a/${it.key}" } + files().mapKeys { "b/${it.key}" })
        val repo = library()
        val service = imports(repo)
        val plan = service.fetch(source.toString())
        service.applyGit(plan, emptySet(), emptyMap(), setOf("a", "b"))
        val ids = repo.listMods().map { it.id }.toSet()
        assertEquals(2, ids.size)
        repo.saveGroup(Group("chosen", "Chosen", modIds = ids.toList()))
        service.applyGit(service.fetch(source.toString()), emptySet(), emptyMap(), setOf("a", "b"))
        assertEquals(ids, repo.listMods().map { it.id }.toSet())
        assertEquals(ids, repo.loadGroup("chosen")?.modIds?.toSet())
    }

    @Test
    fun `missing or malformed upstream invalidates update before any selected package is written`() {
        val source = upstream("upstream", files().mapKeys { "a/${it.key}" } + files("second").mapKeys { "b/${it.key}" })
        val repo = library()
        val service = imports(repo)
        service.applyGit(service.fetch(source.toString()), emptySet(), emptyMap(), setOf("a", "b"))
        val before = repo.listMods().associate { it.id to repo.loadModSnapshot(it.id)!!.files }
        source.resolve("a/hooks/register.js").writeText("export function register(on) { /* updated */ }\n")
        source.resolve("b/hooks/hooks.json").writeText("{malformed")
        commit(source)
        val check = CheckSources(repo).check()
        assertEquals(setOf(SourceCheckSkillStatus.UPDATE_AVAILABLE, SourceCheckSkillStatus.UNAVAILABLE), check.perMod.map { it.status }.toSet())
        assertFailsWith<IllegalArgumentException> { service.updateMods(before.keys) }
        assertEquals(before, repo.listMods().associate { it.id to repo.loadModSnapshot(it.id)!!.files })
        Files.delete(source.resolve("b/.claude-plugin/plugin.json"))
        commit(source)
        assertTrue(CheckSources(repo).check().perMod.any { it.status == SourceCheckSkillStatus.PATH_MISSING })
    }

    @Test
    fun `source cache uses mod namespace replays failures and ignores new identities`() {
        val source = upstream("upstream", files().mapKeys { "plugins/demo/${it.key}" })
        val repo = library()
        val service = imports(repo)
        service.applyGit(service.fetch(source.toString()), emptySet(), emptyMap(), setOf("plugins/demo"))
        val mod = repo.listMods().single()
        write(source, mapOf("unrelated.txt" to "An unrelated commit\n"))
        commit(source)
        val report = CheckSources(repo).check()
        assertEquals(SourceCheckSkillStatus.CURRENT, report.perMod.single().status)
        val store = SourcesStateStore(scratch.resolve("sidecar"))
        store.save(report.toSourcesState("2026-10-10T12:00:00Z"))
        assertEquals(setOf("mod:${mod.id}"), store.load().sources.single().upstreamModFingerprints.keys)
        assertEquals(report.perMod, store.load().cachedSourceCheck(repo).report?.perMod)
        source.resolve("plugins/demo/hooks/hooks.json").writeText("{}")
        commit(source)
        val invalid = CheckSources(repo).check()
        assertNotNull(invalid.perMod.single().error)
        assertEquals(invalid.perMod, invalid.toSourcesState("now").cachedSourceCheck(repo).report?.perMod)
        val copy = repo.loadModSnapshot(mod.id)!!
        repo.writeMod(copy.copy(mod = copy.mod.copy(id = "new-object")))
        assertNull(store.load().cachedSourceCheck(repo).report)
    }

    @Test
    fun `local capture rejects symlinks nested repositories case collisions and nonportable files`() {
        val root = scratch.resolve("unsafe").createDirectories()
        write(root, files())
        Files.createSymbolicLink(root.resolve("directory-link"), scratch)
        assertNotNull(ModImport.preview(root).error)
        Files.delete(root.resolve("directory-link"))
        root.resolve("nested/.git").also { it.parent.createDirectories() }.writeText("gitdir: /outside")
        assertNotNull(ModImport.preview(root).error)
        Files.delete(root.resolve("nested/.git"))
        val snapshot = ModImport.preview(root).snapshot!!
        assertFailsWith<IllegalArgumentException> { ModImport.validate(snapshot.files + SkillFile("HOOKS/register.js", byteArrayOf(1))) }
        assertFailsWith<IllegalArgumentException> { ModImport.validate(snapshot.files + SkillFile("assets/CON", byteArrayOf(1))) }
    }

    @Test
    fun `Git links and submodules are rejected before capture`() {
        val source = upstream("unsafe", files())
        Files.createSymbolicLink(source.resolve("escape"), scratch)
        commit(source)
        assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(source.toString()) }
        Files.delete(source.resolve("escape"))
        commit(source)
        source.resolve("dependency").writeText("fixture")
        commit(source)
        Git.open(source.toFile()).use { git -> commitGitMode(git, "dependency", FileMode.GITLINK) }
        assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(source.toString()) }
    }

    @Test
    fun `missing and unreachable sources survive cache replay without modifying packages`() {
        val source = upstream("upstream", files().mapKeys { "plugins/demo/${it.key}" })
        val repo = library()
        val service = imports(repo)
        service.applyGit(service.fetch(source.toString()), emptySet(), emptyMap(), setOf("plugins/demo"))
        val mod = repo.listMods().single()
        Files.delete(source.resolve("plugins/demo/.claude-plugin/plugin.json"))
        commit(source)
        val missing = CheckSources(repo).check()
        assertEquals(SourceCheckSkillStatus.PATH_MISSING, missing.perMod.single().status)
        assertEquals(missing.perMod, missing.toSourcesState("missing").cachedSourceCheck(repo).report?.perMod)
        val before = repo.loadModSnapshot(mod.id)!!
        deleteFixtureTree(source)
        val failed = CheckSources(repo).check()
        assertEquals(SourceCheckSkillStatus.UNAVAILABLE, failed.perMod.single().status)
        assertEquals(failed.perMod, failed.toSourcesState("failed").cachedSourceCheck(repo).report?.perMod)
        assertEquals(before.files, repo.loadModSnapshot(mod.id)?.files)
    }

    @Test
    fun `mixed import resolves every mod selection before writing skills`() {
        val source = upstream("upstream", files())
        val repo = library()
        val service = imports(repo)
        val plan = service.fetch(source.toString())
        assertFailsWith<IllegalArgumentException> {
            service.applyGit(plan, setOf("skills/review"), emptyMap(), setOf("unknown"))
        }
        assertTrue(repo.listSkills().isEmpty())
        assertTrue(repo.listMods().isEmpty())
    }

    @Test
    fun `concurrent payload edits abort upstream replacement even when metadata is unchanged`() {
        val source = upstream("upstream", files())
        val repo = library()
        val service = imports(repo)
        service.applyGit(service.fetch(source.toString()), emptySet(), emptyMap(), setOf("."))
        val mod = repo.listMods().single()
        val concurrent = imports(repo, GitRepositoryFetcher { url ->
            val plan = GitSkillImporter().fetch(url)
            val current = repo.loadModSnapshot(mod.id)!!
            repo.writeMod(current.copy(files = current.files + SkillFile("concurrent.txt", "Concurrent edit".encodeToByteArray())))
            plan
        })
        assertFailsWith<IllegalArgumentException> { concurrent.updateMods(setOf(mod.id)) }
        assertTrue(repo.loadModSnapshot(mod.id)!!.files.any { it.path == "concurrent.txt" })
    }
}
