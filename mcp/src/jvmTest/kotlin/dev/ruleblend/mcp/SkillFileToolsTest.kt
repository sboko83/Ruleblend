package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.model.parseSkillFrontmatter
import dev.ruleblend.core.storage.LibraryGit
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.storage.SkillTreeLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SkillFileToolsTest {
    private lateinit var root: Path
    private lateinit var repository: LibraryRepository
    private lateinit var tools: RuleblendTools
    private var rejectCommit = false
    private val id = "review"
    private val binary = byteArrayOf(0, 1, -1, 13, 10)

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-skill-files-")
        root.resolve(".claude").createDirectories()
        root.resolve(".codex").createDirectories()
        val library = root.resolve("library")
        repository = LibraryRepository(library, object : LibraryGit(library) {
            override fun commit(message: String, relativePath: Path) {
                check(!rejectCommit) { "Injected commit failure" }
                super.commit(message, relativePath)
            }
        }).also { it.init() }
        tools = RuleblendTools(repository, ConfigStore(root.resolve("config.json")),
            listOf(ClaudeCodeAdapter(root), CodexAdapter(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }))
        tools.createSkill(args("name" to id, "description" to "Review code", "content" to "# Review\n"))
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
    fun `text and binary files round trip with portable modes and survive reopening`() {
        write("scripts/run.sh", "#!/bin/sh\necho review\n", executable = true)
        write("assets/image.bin", Base64.getEncoder().encodeToString(binary), "base64")
        write("empty.txt", "")
        val files = tools.listSkillFiles(args("id" to id)).jsonObject["files"]!!.jsonArray
        assertEquals(listOf("SKILL.md", "assets/image.bin", "empty.txt", "scripts/run.sh"),
            files.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertTrue(files.last().jsonObject["executable"]!!.jsonPrimitive.boolean)
        assertEquals("#!/bin/sh\necho review\n", read("scripts/run.sh")["content"]!!.jsonPrimitive.content)
        assertContentEquals(binary, Base64.getDecoder().decode(read("assets/image.bin", "base64")["content"]!!.jsonPrimitive.content))
        val error = assertFailsWith<ToolError> { read("assets/image.bin") }
        assertTrue(error.message!!.contains("base64"))
        val reopened = LibraryRepository(root.resolve("library")).loadSkillSnapshot(id)!!
        assertEquals("4", reopened.skill.version)
        assertContentEquals(binary, reopened.files.single { it.path == "assets/image.bin" }.bytes)
        assertTrue(reopened.files.single { it.path == "scripts/run.sh" }.executable)
    }

    @Test
    fun `identical writes are no ops while executable only edits change revision`() {
        write("run.sh", "echo first", executable = true)
        val history = repository.skillHistory(id)
        write("run.sh", "echo first")
        assertEquals(history, repository.skillHistory(id))
        assertEquals("2", repository.loadSkill(id)!!.version)
        assertTrue(read("run.sh")["executable"]!!.jsonPrimitive.boolean)
        write("run.sh", "echo first", executable = false)
        assertEquals("3", repository.loadSkill(id)!!.version)
        assertFalse(read("run.sh")["executable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `instruction edits retain sibling bytes modes and maintained metadata`() {
        write("run.sh", "echo review", executable = true)
        write("image.bin", Base64.getEncoder().encodeToString(binary), "base64")
        write("SKILL.md", "---\nname: other\ndescription: wrong\n---\n# Changed\n")
        val metadata = parseSkillFrontmatter(read("SKILL.md")["content"]!!.jsonPrimitive.content)
        assertEquals(id, metadata.name)
        assertEquals("Review code", metadata.description)
        tools.updateSkill(args("id" to id, "description" to "Updated description"))
        assertTrue(read("SKILL.md")["content"]!!.jsonPrimitive.content.contains("Updated description"))
        assertTrue(read("run.sh")["executable"]!!.jsonPrimitive.boolean)
        assertContentEquals(binary, repository.loadSkillSnapshot(id)!!.files.single { it.path == "image.bin" }.bytes)
    }

    @Test
    fun `deletion removes only an exact file and protects SKILL md`() {
        write("references/a.md", "A")
        write("references/b.md", "B")
        tools.deleteSkillFile(args("id" to id, "path" to "references/a.md"))
        val before = repository.loadSkillSnapshot(id)
        listOf("SKILL.md", "references", "missing.md").forEach { path ->
            assertFailsWith<ToolError> { tools.deleteSkillFile(args("id" to id, "path" to path)) }
        }
        assertEquals(before, repository.loadSkillSnapshot(id))
        assertEquals("B", read("references/b.md")["content"]!!.jsonPrimitive.content)
        assertEquals("4", before!!.skill.version)
    }

    @Test
    fun `non portable paths and tree collisions reject the complete mutation`() {
        write("References/a.md", "A")
        write("file", "B")
        val before = repository.loadSkillSnapshot(id)
        listOf("../outside", "/absolute", "C:/absolute", "a\\b", "a//b", "./file", "a/../file",
            "NUL.txt", "trailing.", "trailing ", ".git/config", "nested/.GIT/config", "References",
            "references/b.md", "file/child", "skill.md", "").forEach { path ->
            assertFailsWith<ToolError>(path) { write(path, "bad") }
            assertEquals(before, repository.loadSkillSnapshot(id), path)
        }
        listOf("../outside", "/absolute", "a\\b").forEach { path ->
            assertFailsWith<ToolError> { read(path) }
            assertFailsWith<ToolError> { tools.deleteSkillFile(args("id" to id, "path" to path)) }
        }
    }

    @Test
    fun `malformed encodings argument types and binary instructions write nothing`() {
        val before = repository.loadSkillSnapshot(id)
        listOf(
            args("id" to id, "path" to "a", "content" to "!", "encoding" to "base64"),
            args("id" to id, "path" to "a", "content" to "A", "encoding" to "hex"),
            args("id" to id, "path" to "a", "content" to JsonPrimitive(42)),
            args("id" to id, "path" to "a", "content" to "A", "executable" to "true"),
            args("id" to id, "path" to "a", "content" to "A", "executable" to JsonPrimitive(1)),
            args("id" to id, "path" to "SKILL.md", "content" to "/w==", "encoding" to "base64"),
            args("id" to id, "path" to "a", "content" to "\uD800"),
        ).forEach { input ->
            assertFailsWith<ToolError> { tools.writeSkillFile(input) }
            assertEquals(before, repository.loadSkillSnapshot(id))
        }
        assertFailsWith<ToolError> { tools.listSkillFiles(args("id" to "../outside")) }
        assertFailsWith<ToolError> { tools.readSkillFile(args("id" to "missing", "path" to "SKILL.md")) }
    }

    @Test
    fun `imports can be read and forked but never edited in place`() {
        val original = repository.loadSkill(id)!!.copy(id = "imported", source = GitSkillSource("https://example.com/skills.git", "abc", "review"))
        repository.writeSkill(SkillSnapshot(original, listOf(SkillFile("SKILL.md", original.content.encodeToByteArray()), SkillFile("run.sh", binary, true))))
        assertFalse(tools.listSkillFiles(args("id" to "imported")).jsonObject["editable"]!!.jsonPrimitive.boolean)
        assertEquals(Base64.getEncoder().encodeToString(binary), tools.readSkillFile(args("id" to "imported", "path" to "run.sh", "encoding" to "base64")).jsonObject["content"]!!.jsonPrimitive.content)
        val before = repository.loadSkillSnapshot("imported")
        assertFailsWith<ToolError> { tools.writeSkillFile(args("id" to "imported", "path" to "run.sh", "content" to "bad")) }
        assertFailsWith<ToolError> { tools.deleteSkillFile(args("id" to "imported", "path" to "run.sh")) }
        assertEquals(before, repository.loadSkillSnapshot("imported"))
        val forkId = tools.forkSkill(args("id" to "imported")).jsonObject["id"]!!.jsonPrimitive.content
        tools.writeSkillFile(args("id" to forkId, "path" to "run.sh", "content" to "changed"))
        assertTrue(repository.loadSkillSnapshot(forkId)!!.files.single { it.path == "run.sh" }.executable)
        assertEquals(before, repository.loadSkillSnapshot("imported"))
    }

    @Test
    fun `file and aggregate bounds reject before committing`() {
        val before = repository.loadSkillSnapshot(id)!!
        assertFailsWith<ToolError> { write("huge.txt", "x".repeat(SkillTreeLimits.MAX_FILE_BYTES + 1)) }
        assertFailsWith<ToolError> { write("unicode.txt", "я".repeat(SkillTreeLimits.MAX_FILE_BYTES / 2 + 1)) }
        val oversizedTrees = listOf(
            (1..SkillTreeLimits.MAX_FILES).map { SkillFile("$it.txt", byteArrayOf()) },
            listOf(SkillFile("large.bin", ByteArray(SkillTreeLimits.MAX_FILE_BYTES + 1))),
            (1..3).map { SkillFile("$it.bin", ByteArray(9 * 1024 * 1024)) },
        )
        oversizedTrees.forEach { files ->
            assertFailsWith<IllegalArgumentException> { repository.writeSkill(SkillSnapshot(before.skill, before.files + files)) }
            assertEquals(before, repository.loadSkillSnapshot(id))
        }
        SkillTreeLimits.validate((1..SkillTreeLimits.MAX_FILES).map { SkillFile("$it", byteArrayOf()) })
        SkillTreeLimits.validate(listOf(SkillFile("a", ByteArray(SkillTreeLimits.MAX_FILE_BYTES)),
            SkillFile("b", ByteArray(SkillTreeLimits.MAX_FILE_BYTES)), SkillFile("c", ByteArray(5 * 1024 * 1024))))
    }

    @Test
    fun `failed create replace and delete restore the complete prior tree`() {
        write("run.sh", "original", executable = true)
        write("asset.bin", Base64.getEncoder().encodeToString(binary), "base64")
        val before = repository.loadSkillSnapshot(id)
        val history = repository.skillHistory(id)
        rejectCommit = true
        assertFailsWith<ToolError> { write("new/file", "new") }
        assertEquals(before, repository.loadSkillSnapshot(id))
        assertFailsWith<ToolError> { write("run.sh", "replacement", executable = false) }
        assertEquals(before, repository.loadSkillSnapshot(id))
        assertFailsWith<ToolError> { tools.deleteSkillFile(args("id" to id, "path" to "asset.bin")) }
        assertEquals(before, repository.loadSkillSnapshot(id))
        assertEquals(history, repository.skillHistory(id))
    }

    @Test
    fun `symbolic links in library trees cannot expose or modify outside content`() {
        val outside = root.resolve("outside").createDirectories()
        outside.resolve("secret.txt").writeText("private")
        val files = root.resolve("library/skills/$id/files")
        val before = repository.loadSkillSnapshot(id)
        listOf(outside.resolve("secret.txt"), outside).forEach { target ->
            val link = files.resolve("linked")
            Files.createSymbolicLink(link, target)
            assertFailsWith<ToolError> { tools.listSkillFiles(args("id" to id)) }
            assertFailsWith<ToolError> { read("linked") }
            assertFailsWith<ToolError> { write("linked", "bad") }
            Files.delete(link)
            assertEquals(before, repository.loadSkillSnapshot(id))
            assertEquals("private", outside.resolve("secret.txt").readBytes().decodeToString())
        }
    }

    @Test
    fun `concurrent edits preserve both files and allocate successive revisions`() {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf("first", "second").map { path -> executor.submit {
                check(start.await(10, TimeUnit.SECONDS))
                write(path, path)
            } }
            start.countDown()
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(setOf("SKILL.md", "first", "second"), repository.loadSkillSnapshot(id)!!.files.map { it.path }.toSet())
        assertEquals("3", repository.loadSkill(id)!!.version)
    }

    @Test
    fun `install applies complete edited tree and protects locally modified copies`() {
        write("run.sh", "echo review", executable = true)
        write("asset.bin", Base64.getEncoder().encodeToString(binary), "base64")
        val target = root.resolve("project").createDirectories().toString()
        val selector = args("target" to target, "skill_id" to id)
        val installed = tools.install(selector).jsonObject
        assertTrue(installed["installed_skills"]!!.jsonArray.isNotEmpty())
        listOf(".claude", ".codex").forEach { agent ->
            assertContentEquals(binary, root.resolve("project/$agent/skills/$id/asset.bin").readBytes())
        }
        assertEquals(2, SkillInstallStateStore(root).all().size)
        assertTrue(SkillInstallStateStore(root).all().all { it.executableFiles == listOf("run.sh") })
        tools.deleteSkillFile(args("id" to id, "path" to "asset.bin"))
        write("new.txt", "new")
        tools.install(selector)
        val claudeTree = root.resolve("project/.claude/skills/$id")
        assertFalse(claudeTree.resolve("asset.bin").exists())
        assertEquals("new", claudeTree.resolve("new.txt").readBytes().decodeToString())
        claudeTree.resolve("run.sh").writeText("local edit")
        write("run.sh", "library update")
        val protected = tools.install(selector).jsonObject
        assertTrue(protected["skipped_modified_skills"]!!.jsonArray.isNotEmpty())
        assertEquals("local edit", claudeTree.resolve("run.sh").readBytes().decodeToString())
        tools.install(args("target" to target, "skill_id" to id, "overwrite" to true))
        assertEquals("library update", claudeTree.resolve("run.sh").readBytes().decodeToString())
        // Windows has no POSIX modes; mode intent is still recorded and survives reinstall.
        val snapshot = repository.loadSkillSnapshot(id)!!
        assertTrue(snapshot.files.single { it.path == "run.sh" }.executable)
    }

    private fun write(path: String, content: String, encoding: String = "utf8", executable: Boolean? = null): JsonElement =
        tools.writeSkillFile(buildJsonObject {
            put("id", id); put("path", path); put("content", content); put("encoding", encoding)
            executable?.let { put("executable", it) }
        })

    private fun read(path: String, encoding: String = "utf8"): JsonObject =
        tools.readSkillFile(args("id" to id, "path" to path, "encoding" to encoding)).jsonObject

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> when (value) {
            is JsonElement -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        } }
    }
}
