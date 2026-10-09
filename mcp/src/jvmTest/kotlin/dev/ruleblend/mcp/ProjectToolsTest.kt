package dev.ruleblend.mcp

import dev.ruleblend.core.config.*
import dev.ruleblend.core.integration.*
import dev.ruleblend.core.model.*
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.serialization.json.*

class ProjectToolsTest {
    private lateinit var root: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var config: ConfigStore
    private lateinit var tools: RuleblendTools

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-project-tools-")
        project = root.resolve("project").createDirectories()
        root.resolve(".claude").createDirectories()
        root.resolve(".codex").createDirectories()
        repository = LibraryRepository(root.resolve("library")).also { it.init() }
        config = ConfigStore(root.resolve("config.json"))
        tools = RuleblendTools(repository, config, listOf(ClaudeCodeAdapter(root), CodexAdapter(root), PiAdapter(root)),
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
    fun `reading bindings never registers a project or creates its files`() {
        repository.saveProfile(Profile("review", "Review"))
        config.save(AppConfig(projectProfiles = mapOf(project.projectKey() to listOf(ProfileBinding("review", false), ProfileBinding("missing", true)))))
        val before = config.load()
        val result = tools.getProjectProfiles(args()).jsonObject
        assertFalse(result["registered"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("review", "missing"), result["bindings"]!!.jsonArray.map { it.jsonObject["profile_id"]!!.jsonPrimitive.content })
        assertTrue(result["bindings"]!!.jsonArray.last().jsonObject["missing"]!!.jsonPrimitive.boolean)
        assertEquals(before, config.load())
        assertFalse(project.resolve("AGENTS.md").exists())
    }

    @Test
    fun `merge and toggles preserve base and overlapping profile objects`() {
        rule("base")
        rule("shared")
        rule("review-only")
        repository.saveGroup(Group("checks", "Checks", blockIds = listOf("shared", "review-only")))
        repository.saveProfile(Profile("review", "Review", blockIds = listOf("base"), groupIds = listOf("checks")))
        repository.saveProfile(Profile("debug", "Debug", blockIds = listOf("shared")))
        tools.install(args("rule_id" to "base"))
        tools.attachProfile(args("profile_id" to "review"))
        tools.attachProfile(args("profile_id" to "debug"))
        tools.setProfileActive(args("profile_id" to "review", "active" to false))
        assertEquals(setOf("base", "shared"), regions().map { it.id }.toSet())
        assertEquals("p=debug", regions().single { it.id == "shared" }.origin)
        assertEquals(null, regions().single { it.id == "base" }.origin)
        assertEquals(listOf(ProfileBinding("review", false), ProfileBinding("debug", true)), config.load().projectProfiles[project.projectKey()])
        tools.setProfileActive(args("profile_id" to "review", "active" to true))
        assertEquals(setOf("base", "shared", "review-only"), regions().map { it.id }.toSet())
        tools.uninstall(args("profile_id" to "review"))
        assertEquals(listOf(ProfileBinding("debug", true)), config.load().projectProfiles[project.projectKey()])
        assertEquals(setOf("base", "shared"), regions().map { it.id }.toSet())
    }

    @Test
    fun `disable reports and preserves locally edited profile rules`() {
        rule("review")
        repository.saveProfile(Profile("review", "Review", blockIds = listOf("review")))
        tools.attachProfile(args("profile_id" to "review"))
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Follow review.", "Keep local edit."))
        val result = tools.setProfileActive(args("profile_id" to "review", "active" to false)).jsonObject
        assertEquals(listOf("review"), result.ids("skipped_modified"))
        assertTrue(file.readText().contains("Keep local edit."))
        assertEquals(listOf(ProfileBinding("review", false)), config.load().projectProfiles[project.projectKey()])
        assertTrue(result.ids("restart_required_agents").isNotEmpty())
    }

    @Test
    fun `replace removes safe base of every kind and preserves foreign content`() {
        baseGroup()
        rule("new")
        repository.saveProfile(Profile("new", "New", blockIds = listOf("new")))
        project.resolve("AGENTS.md").writeText("User instructions.\n")
        tools.install(args("group_id" to "base"))
        val preview = tools.getProjectProfiles(args()).jsonObject
        assertEquals(setOf("rule", "mcp", "skill", "subagent"), preview["base_items"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }.toSet())
        val result = tools.attachProfile(args("profile_id" to "new", "mode" to "replace", "expected_revision" to preview["revision"]!!.jsonPrimitive.content)).jsonObject
        assertEquals(4, result["base_removal"]!!.jsonArray.size)
        assertTrue(result["base_removal"]!!.jsonArray.all { it.jsonObject["status"]!!.jsonPrimitive.content == "removed" })
        assertEquals(listOf("new"), regions().map { it.id })
        assertTrue(project.resolve("AGENTS.md").readText().startsWith("User instructions."))
        assertFalse(project.resolve(".claude/skills/helper").exists())
        assertFalse(project.resolve(".claude/agents/helper-agent.md").exists())
        assertTrue(tools.targetStatus(args()).jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == "server" })
    }

    @Test
    fun `replace protects modified native and rule base copies and reports each refusal`() {
        baseGroup()
        repository.saveProfile(Profile("empty", "Empty"))
        tools.install(args("group_id" to "base"))
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Follow base-rule.", "Keep local edit."))
        val skill = project.resolve(".claude/skills/helper/SKILL.md")
        skill.writeText(skill.readText() + "\nLocal skill edit.\n")
        val agent = project.resolve(".claude/agents/helper-agent.md")
        agent.writeText(agent.readText() + "\nLocal agent edit.\n")
        val server = project.resolve(".mcp.json")
        server.writeText(server.readText().replace("example-command", "local-command"))
        val preview = tools.getProjectProfiles(args()).jsonObject
        val result = tools.attachProfile(args("profile_id" to "empty", "mode" to "replace", "expected_revision" to preview["revision"]!!.jsonPrimitive.content)).jsonObject
        assertEquals(4, result["base_removal"]!!.jsonArray.size)
        assertTrue(result["base_removal"]!!.jsonArray.all { it.jsonObject["reason"]!!.jsonPrimitive.content == "modified" })
        assertTrue(file.readText().contains("Keep local edit."))
        assertTrue(skill.readText().contains("Local skill edit."))
        assertTrue(agent.readText().contains("Local agent edit."))
        assertTrue(server.readText().contains("local-command"))
    }

    @Test
    fun `replace refuses stale installed and library content before any mutation`() {
        rule("base")
        repository.saveProfile(Profile("empty", "Empty"))
        tools.install(args("rule_id" to "base"))
        var preview = tools.getProjectProfiles(args()).jsonObject
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("Follow base.", "Local edit."))
        val before = config.load()
        assertFailsWith<ToolError> { tools.attachProfile(args("profile_id" to "empty", "mode" to "replace", "expected_revision" to preview["revision"]!!.jsonPrimitive.content)) }
        assertEquals(before, config.load())
        assertTrue(file.readText().contains("Local edit."))
        preview = tools.getProjectProfiles(args()).jsonObject
        repository.saveBlock(repository.loadBlock("base")!!.copy(content = "Changed library content."))
        assertFailsWith<ToolError> { tools.attachProfile(args("profile_id" to "empty", "mode" to "replace", "expected_revision" to preview["revision"]!!.jsonPrimitive.content)) }
        assertEquals(before, config.load())
    }

    @Test
    fun `replace requires revision and a new binding`() {
        repository.saveProfile(Profile("empty", "Empty"))
        assertFailsWith<ToolError> { tools.attachProfile(args("profile_id" to "empty", "mode" to "replace")) }
        assertTrue(config.load().projects.isEmpty())
        tools.attachProfile(args("profile_id" to "empty"))
        val revision = tools.getProjectProfiles(args()).jsonObject["revision"]!!.jsonPrimitive.content
        assertFailsWith<ToolError> { tools.attachProfile(args("profile_id" to "empty", "mode" to "replace", "expected_revision" to revision)) }
    }

    @Test
    fun `profile writes report scope and unsupported refusals`() {
        rule("scoped")
        repository.saveBlock(Block("agent", "Agent", type = BlockType.SUBAGENT, content = "Review."))
        repository.saveProfile(Profile("review", "Review", blockIds = listOf("scoped"), subagentIds = listOf("agent")))
        val other = root.resolve("other").createDirectories()
        config.save(AppConfig(projects = listOf(project.projectKey()), ruleScopes = mapOf("scoped" to other.projectKey())))
        tools.setProjectAgents(args("agent_ids" to listOf("codex")))
        val result = tools.attachProfile(args("profile_id" to "review")).jsonObject
        assertEquals(listOf("scoped"), result.ids("skipped_out_of_scope"))
        assertEquals(listOf("agent"), result.ids("skipped_unsupported_subagents"))
        assertTrue(regions().isEmpty())
    }

    @Test
    fun `register and assistant switches work with none enabled and preserve installed files`() {
        rule("base")
        tools.install(args("rule_id" to "base"))
        val file = project.resolve("AGENTS.md")
        val content = file.readText()
        tools.setProjectAgents(args("agent_ids" to emptyList<String>()))
        tools.registerProject(args("target" to project.resolve("../project").toString()))
        assertEquals(listOf(project.projectKey()), config.load().projects)
        assertTrue(tools.getProjectProfiles(args()).jsonObject.ids("agent_ids").isEmpty())
        tools.setProjectAgents(args("agent_ids" to listOf("codex")))
        tools.setAssistantVisibility(args("agent_id" to "claude-code", "hidden" to true))
        tools.setProjectAgents(args("agent_ids" to emptyList<String>()))
        tools.setAssistantVisibility(args("agent_id" to "claude-code", "hidden" to false))
        assertTrue(tools.getProjectProfiles(args()).jsonObject.ids("agent_ids").isEmpty())
        assertEquals(content, file.readText())
        val listed = tools.listAssistants(args()).jsonArray
        assertFalse(listed.single { it.jsonObject["id"]!!.jsonPrimitive.content == "pi" }.jsonObject["available"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `invalid assistant sets and invalid profile operations leave config unchanged`() {
        tools.registerProject(args())
        val before = config.load()
        for (ids in listOf(listOf("unknown"), listOf("codex", "codex"), listOf("pi"))) {
            assertFailsWith<ToolError> { tools.setProjectAgents(args("agent_ids" to ids)) }
        }
        assertFailsWith<ToolError> { tools.setProfileActive(args("profile_id" to "missing", "active" to false)) }
        assertFailsWith<ToolError> { tools.attachProfile(args("target" to "agent:codex", "profile_id" to "missing")) }
        assertFailsWith<ToolError> { tools.registerProject(args("target" to "relative")) }
        assertEquals(before, config.load())
    }

    @Test
    fun `string booleans cannot change profile activation or assistant visibility`() {
        rule("review-rule")
        repository.saveProfile(Profile("review", "Review", blockIds = listOf("review-rule")))
        tools.attachProfile(args("profile_id" to "review"))
        val before = config.load()
        val file = project.resolve("AGENTS.md")
        val content = file.readText()
        for (value in listOf("true", "false")) {
            assertFailsWith<ToolError> { tools.setProfileActive(args("profile_id" to "review", "active" to value)) }
            assertFailsWith<ToolError> { tools.setAssistantVisibility(args("agent_id" to "codex", "hidden" to value)) }
            assertEquals(before, config.load())
            assertEquals(content, file.readText())
        }
    }

    @Test
    fun `unregister forgets navigation and bindings but preserves files and library scopes`() {
        rule("base")
        tools.install(args("rule_id" to "base"))
        val key = project.projectKey()
        val place = "project:$key"
        config.update { it.copy(projectProfiles = mapOf(key to listOf(ProfileBinding("missing", false))),
            disabledAgents = mapOf(key to listOf("codex")), ignoredEntries = mapOf(place to listOf("entry")),
            ruleScopes = mapOf("base" to key), places = PlaceBoard(listOf(place), listOf(place), listOf(ProjectSet("Apps", listOf(key))))) }
        val content = project.resolve("AGENTS.md").readText()
        tools.unregisterProject(args())
        val after = config.load()
        assertTrue(after.projects.isEmpty())
        assertTrue(after.projectProfiles.isEmpty())
        assertTrue(after.disabledAgents.isEmpty())
        assertTrue(after.ignoredEntries.isEmpty())
        assertTrue(after.places.pinned.isEmpty())
        assertTrue(after.places.recent.isEmpty())
        assertTrue(after.places.sets.single().projects.isEmpty())
        assertEquals(key, after.ruleScopes["base"])
        assertEquals(content, project.resolve("AGENTS.md").readText())
        assertNotNull(repository.loadBlock("base"))
        val missing = root.resolve("missing-project").createDirectories()
        tools.registerProject(args("target" to missing.toString()))
        Files.delete(missing)
        tools.unregisterProject(args("target" to missing.toString()))
        assertTrue(config.load().projects.isEmpty())
    }

    @Test
    fun `group reorder preserves mixed membership and versions only actual order changes`() {
        rule("first")
        rule("second")
        repository.createSkill(Skill("skill", "Skill", content = "Skill instructions."))
        repository.saveBlock(Block("agent", "Agent", type = BlockType.SUBAGENT, content = "Review."))
        repository.saveGroup(Group("group", "Group", blockIds = listOf("first", "agent", "second"), skillIds = listOf("skill")))
        val version = repository.loadGroup("group")!!.version
        val result = tools.reorderGroup(args("id" to "group", "block_ids" to listOf("second", "agent", "first"))).jsonObject
        assertEquals(listOf("second", "agent", "first"), result.ids("block_ids"))
        assertEquals(listOf("skill"), result.ids("skill_ids"))
        assertEquals(version + 1, result["version"]!!.jsonPrimitive.int)
        tools.reorderGroup(args("id" to "group", "block_ids" to listOf("second", "agent", "first")))
        assertEquals(version + 1, repository.loadGroup("group")!!.version)
        assertFailsWith<ToolError> { tools.reorderGroup(args("id" to "group", "block_ids" to listOf("second", "second", "first"))) }
        assertFailsWith<ToolError> { tools.reorderGroup(args("id" to "group", "skill_ids" to emptyList<String>())) }
        assertFailsWith<ToolError> { tools.reorderGroup(args("id" to ALL_GROUP_ID, "block_ids" to emptyList<String>())) }
        assertEquals(version + 1, repository.loadGroup("group")!!.version)
    }

    private fun rule(id: String) = repository.saveBlock(Block(id, id, content = "Follow $id."))
    private fun regions() = IntegrationService().regions(project.resolve("AGENTS.md"))

    private fun baseGroup() {
        rule("base-rule")
        repository.createSkill(Skill("helper", "helper", content = "# Helper\n"))
        repository.saveBlock(Block("helper-agent", "Helper agent", type = BlockType.SUBAGENT, content = "Review."))
        tools.createMcpServer(buildJsonObject { put("name", "server"); put("transport", "stdio"); put("command", "example-command") })
        repository.saveGroup(Group("base", "Base", blockIds = listOf("base-rule", "helper-agent", "server"), skillIds = listOf("helper")))
    }

    private fun args(vararg values: Pair<String, Any>): JsonObject = buildJsonObject {
        put("target", project.toString())
        values.forEach { (key, value) -> when (value) {
            is Boolean -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it as String) }))
            else -> put(key, value.toString())
        } }
    }

    private fun JsonObject.ids(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }
}
