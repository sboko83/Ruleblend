package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.exchange.ArchiveSelection
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.*
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.usecase.ImportLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec

class LibraryExchangeToolsTest {
    private lateinit var root: Path
    private lateinit var library: Path
    private lateinit var repository: LibraryRepository
    private lateinit var git: LibraryGit
    private lateinit var config: ConfigStore
    private lateinit var exchange: LibraryExchangeTools
    private lateinit var tools: RuleblendTools
    private var instant = Instant.parse("2026-10-08T10:00:00Z")

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("ruleblend-mcp-exchange-")
        library = root.resolve("library")
        git = LibraryGit(library)
        repository = LibraryRepository(library, git).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        val imports = ImportLibrary(repository, LibraryArchive(library, repository), config)
        exchange = LibraryExchangeTools(repository, config, imports, git, library) { instant }
        tools = RuleblendTools(repository, config, emptyList(), gitImports = imports, libraryGit = git, libraryRoot = library)
    }

    @AfterTest
    fun cleanup() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            Files.getFileAttributeView(it, java.nio.file.attribute.DosFileAttributeView::class.java)?.setReadOnly(false)
            Files.deleteIfExists(it)
        } }
    }

    @Test
    fun `selective export expands profiles and protects existing files`() {
        repository.saveBlock(Block("rule", "Rule", content = "Rule content"))
        repository.saveBlock(Block("other", "Other", content = "Excluded"))
        repository.saveGroup(Group("bundle", "Bundle", blockIds = listOf("rule")))
        repository.saveProfile(Profile("work", "Work", groupIds = listOf("bundle")))
        val zip = root.resolve("snapshot.zip")
        val args = args("path" to zip.toString(), "profile_ids" to listOf("work"))
        tools.exportLibrary(args)
        val contents = LibraryArchive(library, repository).read(zip)
        assertEquals(listOf("rule"), contents.blocks.map { it.id })
        assertEquals(listOf("bundle"), contents.groups.map { it.id })
        assertEquals(listOf("work"), contents.profiles.map { it.id })
        val before = Files.readAllBytes(zip)
        assertFailsWith<ToolError> { tools.exportLibrary(args) }
        assertContentEquals(before, Files.readAllBytes(zip))
        tools.exportLibrary(args("path" to zip.toString(), "overwrite" to true, "block_ids" to listOf("other")))
        assertEquals(listOf("other"), LibraryArchive(library, repository).read(zip).blocks.map { it.id })
        assertFailsWith<ToolError> { tools.exportLibrary(args("path" to library.resolve("snapshot.zip").toString())) }
        assertFailsWith<ToolError> { tools.exportLibrary(args("path" to zip.toString(), "overwrite" to true, "skill_ids" to listOf("unknown"))) }
        assertEquals(listOf("other"), LibraryArchive(library, repository).read(zip).blocks.map { it.id })
    }

    @Test
    fun `preview is read only and apply uses reviewed bytes and explicit conflicts`() {
        repository.saveBlock(Block("existing", "Existing", content = "Local"))
        val zip = incoming(blocks = listOf(Block("existing", "Existing", content = "Incoming"), Block("new", "New", content = "New content")))
        val preview = tools.previewArchiveImport(args("path" to zip.toString())).jsonObject
        val id = preview.getValue("preview_id").jsonPrimitive.content
        assertEquals("Local", repository.loadBlock("existing")?.content)
        assertNull(repository.loadBlock("new"))
        val conflict = preview.getValue("entries").jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.content == "existing" }.jsonObject
        assertEquals("conflict", conflict["change"]?.jsonPrimitive?.content)
        assertFalse(conflict.getValue("selected_by_default").jsonPrimitive.boolean)
        val entry = tools.getArchiveImportEntry(args("preview_id" to id, "kind" to "block", "id" to "existing")).jsonObject
        assertEquals("Incoming", entry.getValue("incoming").jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("Local", entry.getValue("local").jsonObject["content"]?.jsonPrimitive?.content)
        Files.write(zip, byteArrayOf(1, 2, 3))
        assertFailsWith<ToolError> { tools.applyArchiveImport(args("preview_id" to id, "block_ids" to listOf("new", "missing"))) }
        assertNull(repository.loadBlock("new"))
        tools.applyArchiveImport(args("preview_id" to id, "block_ids" to listOf("new")))
        assertEquals("New content", repository.loadBlock("new")?.content)
        assertEquals("Local", repository.loadBlock("existing")?.content)
        assertFailsWith<ToolError> { tools.applyArchiveImport(args("preview_id" to id, "block_ids" to listOf("existing"))) }
    }

    @Test
    fun `stale selected entries reject entire import while unrelated edits survive`() {
        repository.saveBlock(Block("existing", "Existing", content = "Before"))
        val zip = incoming(blocks = listOf(Block("existing", "Existing", version = 5, content = "Incoming"), Block("new", "New")))
        val preview = exchange.preview(args("path" to zip.toString())).jsonObject
        val id = preview.getValue("preview_id").jsonPrimitive.content
        repository.saveBlock(repository.loadBlock("existing")!!.copy(content = "Concurrent"))
        assertFailsWith<ToolError> { exchange.apply(args("preview_id" to id, "block_ids" to listOf("existing", "new"))) }
        assertNull(repository.loadBlock("new"))
        assertEquals("Concurrent", repository.loadBlock("existing")?.content)
        exchange.apply(args("preview_id" to id, "block_ids" to listOf("new")))
        assertNotNull(repository.loadBlock("new"))
    }

    @Test
    fun `archive snapshots expire and oldest preview is evicted`() {
        val zip = incoming(blocks = listOf(Block("new", "New")))
        fun preview() = exchange.preview(args("path" to zip.toString())).jsonObject.getValue("preview_id").jsonPrimitive.content
        val first = preview()
        preview()
        val third = preview()
        assertFailsWith<ToolError> { exchange.entry(args("preview_id" to first, "kind" to "block", "id" to "new")) }
        instant = instant.plusSeconds(601)
        assertFailsWith<ToolError> { exchange.apply(args("preview_id" to third, "block_ids" to listOf("new"))) }
        assertNull(repository.loadBlock("new"))
    }

    @Test
    fun `skills preserve binary files modes versions and selected groups and profiles`() {
        val content = localSkillDocument("review", "Review")
        val binary = byteArrayOf(0, -1, 1, 2)
        val skill = SkillSnapshot(Skill("review", "review", "Review", "abc123", content), listOf(
            SkillFile("SKILL.md", content.encodeToByteArray()), SkillFile("run.bin", binary, executable = true),
        ))
        val zip = incoming(skills = listOf(skill), groups = listOf(Group("bundle", "Bundle", version = 5, skillIds = listOf("review"))),
            profiles = listOf(Profile("work", "Work", version = 7, groupIds = listOf("bundle"))))
        val id = exchange.preview(args("path" to zip.toString())).jsonObject.getValue("preview_id").jsonPrimitive.content
        assertEquals(5, exchange.entry(args("preview_id" to id, "kind" to "group", "id" to "bundle")).jsonObject
            .getValue("incoming").jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(7, exchange.entry(args("preview_id" to id, "kind" to "profile", "id" to "work")).jsonObject
            .getValue("incoming").jsonObject.getValue("version").jsonPrimitive.int)
        val file = exchange.entry(args("preview_id" to id, "kind" to "skill", "id" to "review", "path" to "run.bin")).jsonObject
        assertContentEquals(binary, Base64.getDecoder().decode(file.getValue("incoming").jsonObject.getValue("content").jsonPrimitive.content))
        exchange.apply(args("preview_id" to id, "skill_ids" to listOf("review"), "group_ids" to listOf("bundle"), "profile_ids" to listOf("work")))
        assertEquals("abc123", repository.loadSkill("review")?.version)
        assertEquals(5, repository.loadGroup("bundle")?.version)
        assertEquals(7, repository.loadProfile("work")?.version)
        assertContentEquals(binary, repository.loadSkillSnapshot("review")!!.files.first { it.path == "run.bin" }.bytes)
        assertTrue(repository.loadSkillSnapshot("review")!!.files.first { it.path == "run.bin" }.executable)
    }

    @Test
    fun `archive import preserves local rule scope and reviewed conflict version`() {
        repository.saveBlock(Block("existing", "Existing", content = "Local"))
        config.update { it.copy(ruleScopes = mapOf("existing" to root.toString())) }
        val zip = incoming(blocks = listOf(Block("existing", "Existing", content = "Conflict")))
        val id = exchange.preview(args("path" to zip.toString())).jsonObject.getValue("preview_id").jsonPrimitive.content
        exchange.apply(args("preview_id" to id, "block_ids" to listOf("existing")))
        assertEquals("Conflict", repository.loadBlock("existing")?.content)
        assertEquals(1, repository.loadBlock("existing")?.version)
        assertEquals(root.projectKey(), config.load().ruleScopes["existing"])
    }

    @Test
    fun `malicious archives and invalid selections cannot write`() {
        val zip = root.resolve("unsafe.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {
            it.putNextEntry(ZipEntry("../outside.md")); it.write("Unsafe".encodeToByteArray()); it.closeEntry()
        }
        assertFailsWith<ToolError> { exchange.preview(args("path" to zip.toString())) }
        assertFalse(Files.exists(root.resolve("outside.md")))
        assertFailsWith<ToolError> { exchange.export(args("path" to "relative.zip")) }
        assertFailsWith<ToolError> { exchange.history(args("kind" to "block", "id" to "../outside")) }
        assertFailsWith<ToolError> { exchange.history(args("kind" to "block", "id" to "safe", "limit" to 101)) }
        assertFailsWith<ToolError> { exchange.diff(args("kind" to "block", "id" to "safe", "revision" to "HEAD")) }
    }

    @Test
    fun `history includes creation and deleted objects and full skill tree differences`() {
        repository.saveBlock(Block("rule", "Rule", content = "Before"))
        repository.saveBlock(repository.loadBlock("rule")!!.copy(content = "After"))
        val revisions = tools.libraryHistory(args("kind" to "block", "id" to "rule")).jsonArray
        assertEquals(2, revisions.size)
        val newest = revisions.first().jsonObject.getValue("revision").jsonPrimitive.content
        val diff = tools.libraryDiff(args("kind" to "block", "id" to "rule", "revision" to newest)).jsonObject
        assertTrue(diff.getValue("diff").jsonPrimitive.content.contains("+After"))
        repository.deleteBlock("rule")
        assertEquals(3, tools.libraryHistory(args("kind" to "block", "id" to "rule")).jsonArray.size)
        val content = localSkillDocument("review")
        repository.writeSkill(SkillSnapshot(Skill("review", "review", content = content), listOf(
            SkillFile("SKILL.md", content.encodeToByteArray()), SkillFile("extra.txt", "Extra file".encodeToByteArray()),
        )))
        val creation = exchange.history(args("kind" to "skill", "id" to "review")).jsonArray.first().jsonObject.getValue("revision").jsonPrimitive.content
        val skillDiff = exchange.diff(args("kind" to "skill", "id" to "review", "revision" to creation)).jsonObject.getValue("diff").jsonPrimitive.content
        assertTrue(skillDiff.contains("extra.txt"))
        assertTrue(skillDiff.contains("+Extra file"))
    }

    @Test
    fun `MCP previews and old patches hide credentials even without a type header in patch`() {
        fun server(secret: String) = Block("server", "Server", type = BlockType.MCP,
            content = "{\n\"command\": \"node\",\n\"args\": [],\n\"env\": {\n\"TOKEN\": \"$secret\"\n}\n}")
        repository.saveBlock(server("old-secret"))
        repository.saveBlock(server("new-secret"))
        val revision = repository.blockHistory("server").first().id
        assertFalse(repository.blockDiff("server", revision).contains("type: mcp"))
        val zip = incoming(blocks = listOf(server("archive-secret")))
        val id = exchange.preview(args("path" to zip.toString())).jsonObject.getValue("preview_id").jsonPrimitive.content
        val preview = exchange.entry(args("preview_id" to id, "kind" to "block", "id" to "server"))
        assertFalse(preview.toString().contains("secret"))
        repository.saveBlock(Block("server", "Server", content = "Now a rule"))
        val hidden = exchange.diff(args("kind" to "block", "id" to "server", "revision" to revision)).jsonObject
        assertTrue(hidden.getValue("redacted").jsonPrimitive.boolean)
        assertNull(hidden["diff"])
    }

    @Test
    fun `status is read only and hides remote secrets and raw failures`() {
        config.update { it.copy(remoteGit = RemoteGitConfig("https://user:secret@example.invalid/private.git", automatic = false)) }
        val result = tools.librarySyncStatus(args()).jsonObject
        assertTrue(result.getValue("configured").jsonPrimitive.boolean)
        assertFalse(result.toString().contains("secret"))
        assertFalse(result.toString().contains("example.invalid"))
        assertFailsWith<ToolError> { tools.syncLibrary(args()) }
        assertFailsWith<ToolError> { tools.syncLibrary(args("confirm" to "true")) }
        assertFalse(Files.exists(library.resolve(".git/refs/remotes")))
        config.update { it.copy(remoteGit = RemoteGitConfig(root.resolve("secret-missing.git").toUri().toString(), automatic = false)) }
        val failure = tools.syncLibrary(args("confirm" to true)).jsonObject
        assertEquals("failed", failure["status"]?.jsonPrimitive?.content)
        assertFalse(failure.toString().contains("secret"))
        assertFalse(tools.librarySyncStatus(args()).toString().contains("secret"))
        assertFailsWith<ToolError> { tools.syncLibrary(args("confirm" to true, "allow_unrelated_histories" to "true")) }
    }

    @Test
    fun `explicit sync reports conflicts and unrelated histories without exposing URLs`() {
        val remote = root.resolve("remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote.toFile()).call().close()
        repository.saveBlock(Block("rule", "Rule", content = "Local"))
        config.update { it.copy(remoteGit = RemoteGitConfig(remote.toUri().toString(), automatic = false)) }
        assertEquals("pushed", tools.syncLibrary(args("confirm" to true)).jsonObject["status"]?.jsonPrimitive?.content)
        val other = root.resolve("other")
        Git.cloneRepository().setURI(remote.toUri().toString()).setDirectory(other.toFile()).call().close()
        val otherRepo = LibraryRepository(other).also { it.init() }
        otherRepo.saveBlock(otherRepo.loadBlock("rule")!!.copy(content = "Remote change"))
        Git.open(other.toFile()).use { it.push().call() }
        repository.saveBlock(repository.loadBlock("rule")!!.copy(content = "Concurrent local change"))
        val merged = tools.syncLibrary(args("confirm" to true)).jsonObject
        assertTrue(merged.getValue("merged").jsonPrimitive.boolean)
        assertTrue(merged.getValue("conflicts").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "rule" })
        assertEquals("Concurrent local change", repository.loadBlock("rule")?.content)
        val foreign = root.resolve("foreign.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(foreign.toFile()).call().close()
        val foreignLibrary = root.resolve("foreign-library")
        LibraryRepository(foreignLibrary).also { it.init(); it.saveBlock(Block("foreign", "Foreign")) }
        Git.open(foreignLibrary.toFile()).use { it.push().setRemote(foreign.toUri().toString()).setRefSpecs(RefSpec("HEAD:refs/heads/main")).call() }
        config.update { it.copy(remoteGit = RemoteGitConfig(foreign.toUri().toString(), automatic = false)) }
        val refused = tools.syncLibrary(args("confirm" to true)).jsonObject
        assertEquals("failed", refused["status"]?.jsonPrimitive?.content)
        assertTrue(refused.getValue("unrelated_histories").jsonPrimitive.boolean)
        assertFalse(refused.toString().contains(foreign.toString()))
        assertNull(repository.loadBlock("foreign"))
        val accepted = tools.syncLibrary(args("confirm" to true, "allow_unrelated_histories" to true)).jsonObject
        assertEquals("pushed", accepted["status"]?.jsonPrimitive?.content)
        assertTrue(accepted.getValue("merged").jsonPrimitive.boolean)
        assertNotNull(repository.loadBlock("foreign"))
    }

    private fun incoming(blocks: List<Block> = emptyList(), groups: List<Group> = emptyList(),
        skills: List<SkillSnapshot> = emptyList(), profiles: List<Profile> = emptyList()): Path {
        val path = Files.createTempDirectory(root, "incoming-")
        val repo = LibraryRepository(path).also { it.init() }
        blocks.forEach(repo::writeBlock); groups.forEach(repo::writeGroup)
        skills.forEach(repo::writeSkill); profiles.forEach(repo::writeProfile)
        val zip = root.resolve("${path.fileName}.zip")
        LibraryArchive(path, repo).export(zip, ArchiveSelection(blocks.map { it.id }.toSet(), groups.map { it.id }.toSet(),
            skills.map { it.skill.id }.toSet(), profiles.map { it.id }.toSet()))
        return zip
    }

    private fun args(vararg values: Pair<String, Any>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> when (value) {
            is String -> put(key, value)
            is Boolean -> put(key, value)
            is Int -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
            else -> error("Unsupported fixture argument")
        } }
    }
}
