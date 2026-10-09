package dev.ruleblend.mcp

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.CodexMcpInstaller
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

class TargetEntryToolsTest {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var config: ConfigStore
    private lateinit var tools: RuleblendTools

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-target-entries-")
        project = root.resolve("project").createDirectories()
        root.resolve(".claude").createDirectories()
        root.resolve(".codex").createDirectories()
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        tools = RuleblendTools(repository, config, listOf(ClaudeCodeAdapter(root), CodexAdapter(root)),
            mcpService = McpInstallService(listOf(ClaudeCodeMcpInstaller(root), CodexMcpInstaller(root)), McpStateStore(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), repository::loadSkillSnapshot, agentById = { null }),
            subagentService = SubagentInstallService(SubagentInstallStateStore(root)))
    }

    @AfterTest
    fun tearDown() {
        val path = root.toAbsolutePath().normalize()
        require(path.parent == Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            it.toFile().setWritable(true)
            Files.deleteIfExists(it)
        } }
    }

    @Test
    fun `discovery finds foreign native entries without registering or changing project`() {
        val skill = foreignSkill()
        val agent = foreignSubagent()
        val server = foreignMcp()
        project.resolve("AGENTS.md").writeText("# Personal rules\nLeave this alone.\n")
        val before = listOf(skill, agent, server, project.resolve("AGENTS.md")).associateWith { it.readText() }
        val rows = entries()
        assertEquals(setOf("rule", "skill", "subagent", "mcp"), rows.map { it.text("kind") }.toSet())
        assertTrue(rows.all { it.text("origin") == "foreign" })
        assertEquals(before, before.keys.associateWith { it.readText() })
        assertTrue(config.load().projects.isEmpty())
        val copy = read(row("rule"))
        assertEquals("# Personal rules\nLeave this alone.", copy.text("installed_content"))
    }

    @Test
    fun `managed rule diff and accepting a local change preserve neighbouring user text`() {
        project.resolve("AGENTS.md").writeText("Personal prefix\n")
        repository.saveBlock(Block("review", "Review", content = "Use original rules."))
        install("rule", "review")
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Use original rules.", "Use modified rules."))
        val entry = row("rule", "managed")
        val copy = read(entry)
        assertTrue(copy["differs"]!!.jsonPrimitive.boolean)
        assertEquals("Use original rules.", copy.text("library_content"))
        tools.acceptLocalChange(mutation(entry, copy))
        assertEquals("Use modified rules.", repository.loadBlock("review")!!.content)
        assertTrue(file.readText().contains("Personal prefix"))
        assertFalse(read(row("rule", "managed"))["differs"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `stale installed or library revisions refuse publishing without a write`() {
        repository.saveBlock(Block("review", "Review", content = "Original"))
        install("rule", "review")
        val entry = row("rule", "managed")
        val copy = read(entry)
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Original", "Modified"))
        assertFailsWith<ToolError> { tools.acceptLocalChange(mutation(entry, copy)) }
        val current = read(entry)
        repository.saveBlock(repository.loadBlock("review")!!.copy(content = "New library version"))
        assertFailsWith<ToolError> { tools.acceptLocalChange(mutation(entry, current)) }
        assertEquals("New library version", repository.loadBlock("review")!!.content)
        assertTrue(file.readText().contains("Modified"))
    }

    @Test
    fun `foreign rule copying and explicitly confirmed replacement are separate`() {
        val file = project.resolve("AGENTS.md")
        file.writeText("Personal instructions\n")
        val entry = row("rule")
        val copy = read(entry)
        tools.saveTargetEntry(mutation(entry, copy, "id" to "copied"))
        assertEquals("Personal instructions\n", file.readText())
        assertEquals(project.projectKey(), config.load().ruleScopes["copied"])
        assertTrue(repository.loadGroup("all")!!.blockIds.contains("copied"))
        assertFailsWith<ToolError> {
            tools.saveTargetEntry(mutation(entry, copy, "id" to "adopted", "replace" to true))
        }
        assertEquals(null, repository.loadBlock("adopted"))
        tools.saveTargetEntry(mutation(entry, copy, "id" to "adopted", "replace" to true, "confirmed" to true))
        assertTrue(entries().any { it.text("id") == "adopted" && it.text("origin") == "managed" })
        assertTrue(file.readText().contains("Personal instructions"))
    }

    @Test
    fun `foreign subagent and MCP adoption retain native files and metadata`() {
        val agent = foreignSubagent()
        val server = foreignMcp()
        val before = listOf(agent, server).associateWith { it.readText() }
        listOf("subagent", "mcp").forEach { kind ->
            val entry = row(kind)
            tools.saveTargetEntry(mutation(entry, read(entry)))
            assertEquals("managed", row(kind).text("origin"))
        }
        assertEquals(before, before.keys.associateWith { it.readText() })
        val saved = assertNotNull(repository.loadBlock("helper"))
        assertEquals("haiku", saved.variants["claude-code"]!!.fields["model"])
        assertEquals("Review changes.\n", saved.content)
    }

    @Test
    fun `foreign skill adoption retains binary files and release retains directory`() {
        val path = foreignSkill().parent
        path.resolve("asset.bin").writeBytes(byteArrayOf(0, -1, 1))
        val entry = row("skill")
        tools.saveTargetEntry(mutation(entry, read(entry)))
        assertTrue(repository.loadSkillSnapshot("review")!!.files.any { it.path == "asset.bin" })
        val owned = row("skill")
        tools.manageTargetEntry(mutation(owned, read(owned), "action" to "release"))
        assertEquals("foreign", row("skill").text("origin"))
        assertTrue(path.resolve("asset.bin").exists())
    }

    @Test
    fun `skill file diff includes additions deletions modes and binary reads`() {
        repository.createSkill(Skill("review", "review", content = "# Review\n"))
        repository.writeSkillFile("review", "old.txt", "old".toByteArray(), executable = true)
        install("skill", "review")
        val entry = row("skill", "managed")
        val path = Path.of(entry.text("file"))
        Files.delete(path.resolve("old.txt"))
        path.resolve("asset.bin").writeBytes(byteArrayOf(0, -1, 1))
        val copy = tools.readTargetEntry(args("entry_key" to entry.text("entry_key"), "path" to "asset.bin", "encoding" to "base64")).jsonObject
        assertEquals("AP8B", copy.text("content"))
        val changes = copy["files"]!!.jsonArray.map { it.jsonObject }.associate { it.text("path") to it.text("change") }
        assertEquals("missing", changes["old.txt"])
        assertEquals("added", changes["asset.bin"])
        assertEquals("same", changes["SKILL.md"])
        assertFailsWith<ToolError> { tools.readTargetEntry(args("entry_key" to entry.text("entry_key"), "path" to "asset.bin")) }
        assertFailsWith<ToolError> { tools.acceptLocalChange(mutation(entry, copy)) }
        path.resolve("asset.bin").writeBytes(byteArrayOf(1, 2))
        assertFailsWith<ToolError> { tools.manageTargetEntry(mutation(entry, copy, "action" to "release")) }
    }

    @Test
    fun `matching foreign entry requires explicit ownership rather than duplicate save`() {
        foreignSkill()
        repository.createSkill(Skill("review", "review", content = "Different instructions"))
        val entry = row("skill")
        val copy = read(entry)
        assertFailsWith<ToolError> { tools.saveTargetEntry(mutation(entry, copy, "id" to "duplicate")) }
        assertEquals(null, repository.loadSkill("duplicate"))
        tools.manageTargetEntry(mutation(entry, copy, "action" to "take_ownership"))
        assertEquals("managed", row("skill").text("origin"))
    }

    @Test
    fun `orphan restore recovers all four kinds and skill trees`() {
        repository.saveBlock(Block("rule", "Rule", content = "Rule text"))
        repository.saveBlock(Block("helper", "Helper", type = BlockType.SUBAGENT, description = "Review", content = "Subagent text"))
        tools.createMcpServer(args("name" to "server", "transport" to "stdio", "command" to "node"))
        repository.createSkill(Skill("review", "review", content = "# Review\n"))
        repository.writeSkillFile("review", "asset.bin", byteArrayOf(0, 1))
        listOf("rule" to "rule", "subagent" to "helper", "mcp" to "server", "skill" to "review").forEach { (kind, id) -> install(kind, id) }
        listOf("rule", "helper", "server").forEach(repository::deleteBlock)
        repository.deleteSkill("review")
        assertTrue(entries().all { it.text("origin") == "orphan" })
        listOf("rule", "subagent", "mcp", "skill").forEach { kind ->
            val entry = row(kind, "orphan")
            tools.saveTargetEntry(mutation(entry, read(entry)))
            assertTrue(entries().filter { it.text("kind") == kind }.all { it.text("origin") == "managed" })
        }
        assertNotNull(repository.loadSkillSnapshot("review")!!.files.find { it.path == "asset.bin" })
    }

    @Test
    fun `orphan deletion protects locally modified native copies`() {
        foreignSubagent()
        val foreign = row("subagent")
        tools.saveTargetEntry(mutation(foreign, read(foreign)))
        repository.deleteBlock("helper")
        val entry = row("subagent", "orphan")
        val file = Path.of(entry.text("file"))
        file.writeText(file.readText().replace("Review changes.", "Keep my changes."))
        assertFailsWith<ToolError> {
            tools.manageTargetEntry(mutation(entry, read(entry), "action" to "remove", "confirmed" to true))
        }
        assertTrue(file.readText().contains("Keep my changes."))
    }

    @Test
    fun `foreign deletion requires confirmation and preserves adjacent config entries`() {
        val file = foreignMcp()
        val entry = row("mcp")
        val copy = read(entry)
        assertFailsWith<ToolError> { tools.manageTargetEntry(mutation(entry, copy, "action" to "remove")) }
        assertTrue(file.readText().contains("server"))
        file.writeText("""{"mcpServers":{"server":{"command":"node"},"neighbour":{"command":"python"}}}""")
        val current = read(entry)
        tools.manageTargetEntry(mutation(entry, current, "action" to "remove", "confirmed" to true))
        assertFalse(file.readText().contains("\"server\""))
        assertTrue(file.readText().contains("neighbour"))
        val agent = foreignSubagent()
        val subagent = row("subagent")
        assertFailsWith<ToolError> { tools.manageTargetEntry(mutation(subagent, read(subagent), "action" to "remove", "confirmed" to true)) }
        assertTrue(agent.exists())
    }

    @Test
    fun `installed rules reorder with strict membership and preserve foreign text`() {
        val file = project.resolve("AGENTS.md")
        file.writeText("Personal prefix\n")
        listOf("first", "second").forEach { id -> repository.saveBlock(Block(id, id, content = "$id rule")); install("rule", id) }
        val entry = row("rule", "managed")
        val order = JsonArray(listOf("second", "first").map(::JsonPrimitive))
        val request = args("file" to entry.text("file"), "expected_revision" to entry.text("file_revision"), "order" to order)
        assertFailsWith<ToolError> { tools.reorderTargetRules(JsonObject(request + ("order" to JsonArray(listOf(JsonPrimitive("first")))))) }
        assertFailsWith<ToolError> { tools.reorderTargetRules(JsonObject(request + ("order" to JsonArray(listOf("first", "first").map(::JsonPrimitive))))) }
        tools.reorderTargetRules(request)
        assertEquals(listOf("second", "first"), IntegrationService().regions(file).map { it.id })
        assertTrue(file.readText().contains("Personal prefix"))
        assertFailsWith<ToolError> { tools.reorderTargetRules(request) }
    }

    @Test
    fun `MCP copy is read canonically and local changes can be accepted`() {
        tools.createMcpServer(args("name" to "server", "transport" to "stdio", "command" to "node"))
        install("mcp", "server")
        val entry = entries().first { it.text("kind") == "mcp" && it.text("agent_id") == "claude-code" }
        val file = Path.of(entry.text("file"))
        file.writeText(file.readText().replace("node", "python"))
        val copy = read(entry)
        assertTrue(copy["differs"]!!.jsonPrimitive.boolean)
        tools.acceptLocalChange(mutation(entry, copy))
        assertTrue(repository.loadBlock("server")!!.content.contains("python"))
        assertFalse(read(entry)["differs"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `preflight checks each kind and project scope without mutation`() {
        repository.saveBlock(Block("rule", "Rule", content = "Original"))
        repository.saveBlock(Block("helper", "Helper", type = BlockType.SUBAGENT, content = "Original"))
        tools.createMcpServer(args("name" to "server", "transport" to "stdio", "command" to "node"))
        repository.createSkill(Skill("review", "review", content = "Original"))
        listOf("rule" to "rule", "subagent" to "helper", "mcp" to "server", "skill" to "review").forEach { (kind, id) -> install(kind, id) }
        entries().forEach { entry ->
            val path = Path.of(entry.text("file")).let { if (entry.text("kind") == "skill") it.resolve("SKILL.md") else it }
            path.writeText(path.readText().replace("Original", "Modified").replace("node", "python"))
        }
        val conflicts = tools.checkTargetConflicts(args()).jsonArray.map { it.jsonObject }
        assertEquals(setOf("rule", "subagent", "mcp", "skill"), conflicts.map { it.text("kind") }.toSet())
        assertTrue(conflicts.all { it.text("reason") == "modified" })
        config.update { it.copy(ruleScopes = mapOf("rule" to root.toString())) }
        assertEquals("out-of-scope", tools.checkTargetConflicts(args()).jsonArray.map { it.jsonObject }.first { it.text("id") == "rule" }.text("reason"))
    }

    @Test
    fun `arbitrary paths and other targets cannot select or reorder entries`() {
        foreignSkill()
        val entry = row("skill")
        assertFailsWith<ToolError> { tools.readTargetEntry(args("entry_key" to root.resolve("config.json").toString())) }
        assertFailsWith<ToolError> { tools.readTargetEntry(args("target" to "agent:claude-code", "entry_key" to entry.text("entry_key"))) }
        assertFailsWith<ToolError> { tools.reorderTargetRules(args("file" to root.resolve("config.json").toString(), "expected_revision" to "bad", "order" to JsonArray(emptyList()))) }
    }

    @Test
    fun `unchanged orphan copies of all kinds can be removed safely`() {
        repository.saveBlock(Block("rule", "Rule", content = "Rule text"))
        repository.saveBlock(Block("helper", "Helper", type = BlockType.SUBAGENT, content = "Helper text"))
        tools.createMcpServer(args("name" to "server", "transport" to "stdio", "command" to "node"))
        repository.createSkill(Skill("review", "review", content = "Review"))
        listOf("rule" to "rule", "subagent" to "helper", "mcp" to "server", "skill" to "review").forEach { (kind, id) -> install(kind, id) }
        listOf("rule", "helper", "server").forEach(repository::deleteBlock)
        repository.deleteSkill("review")
        listOf("rule", "subagent", "mcp", "skill").forEach { kind ->
            val entry = row(kind, "orphan")
            tools.manageTargetEntry(mutation(entry, read(entry), "action" to "remove", "confirmed" to true))
            assertTrue(entries().none { it.text("kind") == kind })
        }
    }

    @Test
    fun `foreign skill deletion rejects stale confirmation and preserves other directories`() {
        val file = foreignSkill()
        val neighbour = file.parent.resolveSibling("neighbour").createDirectories().resolve("SKILL.md")
        neighbour.writeText("---\nname: neighbour\n---\nKeep me\n")
        val entry = entries().first { it.text("kind") == "skill" && it.text("id") == "review" }
        val copy = read(entry)
        file.parent.resolve("new.txt").writeText("New local work")
        assertFailsWith<ToolError> {
            tools.manageTargetEntry(mutation(entry, copy, "action" to "remove", "confirmed" to true))
        }
        assertTrue(file.exists())
        tools.manageTargetEntry(mutation(entry, read(entry), "action" to "remove", "confirmed" to true))
        assertFalse(file.parent.exists())
        assertTrue(neighbour.readText().contains("Keep me"))
    }

    @Test
    fun `discovery respects hidden addresses and excludes Ruleblend integration entries`() {
        foreignSkill()
        val entry = row("skill")
        config.update { it.copy(ignoredEntries = mapOf("project:${project.projectKey()}" to listOf(entry.text("entry_key")))) }
        project.resolve(".claude/skills/ruleblend").createDirectories().resolve("SKILL.md").writeText("---\nname: ruleblend\n---\nBuilt in\n")
        project.resolve(".mcp.json").writeText("""{"mcpServers":{"ruleblend":{"command":"Ruleblend"}}}""")
        assertEquals("ignored", row("skill").text("origin"))
        assertTrue(entries().none { it.text("id") == "ruleblend" })
    }

    @Test
    fun `referenced instruction files are discovered and saved only as copies`() {
        project.resolve("AGENTS.md").writeText("@docs/rules.md\n")
        val referenced = project.resolve("docs").createDirectories().resolve("rules.md")
        referenced.writeText("Referenced instructions\n")
        val entry = entries().first { it.text("file") == referenced.toString() }
        assertFalse(entry["replaceable"]!!.jsonPrimitive.boolean)
        val copy = read(entry)
        assertFailsWith<ToolError> {
            tools.saveTargetEntry(mutation(entry, copy, "id" to "replaced", "replace" to true, "confirmed" to true))
        }
        assertEquals(null, repository.loadBlock("replaced"))
        tools.saveTargetEntry(mutation(entry, copy, "id" to "copied"))
        assertEquals("Referenced instructions\n", referenced.readText())
        assertEquals("Referenced instructions", repository.loadBlock("copied")!!.content.trim())
    }

    @Test
    fun `foreign MCP names outside the library filename format can be inspected and adopted`() {
        val file = project.resolve(".mcp.json")
        file.writeText("""{"mcpServers":{"My server / v2":{"command":"node"}}}""")
        val entry = row("mcp")
        val copy = read(entry)
        tools.saveTargetEntry(mutation(entry, copy, "id" to "server"))
        assertEquals("managed", row("mcp").text("origin"))
        assertTrue(file.readText().contains("My server / v2"))
        assertNotNull(repository.loadBlock("server"))
    }

    @Test
    fun `failed ownership recording rolls back the new library skill`() {
        val file = foreignSkill()
        val failing = RuleblendTools(repository, config, listOf(ClaudeCodeAdapter(root)),
            skillService = SkillInstallService(SkillInstallStateStore(root), { null }, agentById = { null }))
        val entry = row("skill")
        val copy = read(entry)
        val before = file.readText()
        assertFailsWith<ToolError> { failing.saveTargetEntry(mutation(entry, copy)) }
        assertEquals(null, repository.loadSkill("review"))
        assertEquals(before, file.readText())
        assertEquals("foreign", row("skill").text("origin"))
        assertTrue(repository.loadGroup("all")!!.skillIds.isEmpty())
    }

    @Test
    fun `native release and ownership reuse do not rewrite subagent or MCP content`() {
        foreignSubagent()
        foreignMcp()
        listOf("subagent", "mcp").forEach { kind ->
            val foreign = row(kind)
            tools.saveTargetEntry(mutation(foreign, read(foreign)))
            val managed = row(kind)
            val before = Path.of(managed.text("file")).readText()
            tools.manageTargetEntry(mutation(managed, read(managed), "action" to "release"))
            val released = row(kind)
            assertEquals("foreign", released.text("origin"))
            tools.manageTargetEntry(mutation(released, read(released), "action" to "take_ownership"))
            assertEquals("managed", row(kind).text("origin"))
            assertEquals(before, Path.of(managed.text("file")).readText())
        }
    }

    private fun foreignSkill(): Path = project.resolve(".claude/skills/review").createDirectories().resolve("SKILL.md").also {
        it.writeText("---\nname: review\ndescription: Review code\n---\n# Review\n")
    }
    private fun foreignSubagent(): Path = project.resolve(".claude/agents").createDirectories().resolve("helper.md").also {
        it.writeText("---\nname: helper\ndescription: Review code\nmodel: haiku\n---\nReview changes.\n")
    }
    private fun foreignMcp(): Path = project.resolve(".mcp.json").also { it.writeText("""{"mcpServers":{"server":{"command":"node"}}}""") }
    private fun entries(): List<JsonObject> = tools.listTargetEntries(args()).jsonArray.map { it.jsonObject }
    private fun row(kind: String, origin: String? = null): JsonObject = entries().first {
        it.text("kind") == kind && (origin == null || it.text("origin") == origin)
    }
    private fun read(entry: JsonObject): JsonObject = tools.readTargetEntry(args("entry_key" to entry.text("entry_key"))).jsonObject
    private fun install(kind: String, id: String) { tools.install(args("${kind}_id" to id)) }
    private fun mutation(entry: JsonObject, copy: JsonObject, vararg extra: Pair<String, Any>): JsonObject = args(
        "entry_key" to entry.text("entry_key"), "expected_revision" to copy.text("revision"), *extra)
    private fun args(vararg values: Pair<String, Any>): JsonObject = buildJsonObject {
        put("target", project.toString())
        values.forEach { (key, value) -> when (value) {
            is JsonElement -> put(key, value)
            is Boolean -> put(key, value)
            else -> put(key, value.toString())
        } }
    }
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
