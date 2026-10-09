package dev.ruleblend.mcp

import dev.ruleblend.core.config.AppConfig
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ProfileBinding
import dev.ruleblend.core.config.projectKey
import dev.ruleblend.core.config.withRuleScope
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.integration.SubagentDraft
import dev.ruleblend.core.storage.GitRepositoryFetcher
import dev.ruleblend.core.storage.GitRepositoryImportPlan
import dev.ruleblend.core.storage.GitSubagentCandidate
import dev.ruleblend.core.usecase.ImportLibrary
import dev.ruleblend.core.integration.ClaudeCodeAdapter
import dev.ruleblend.core.integration.CodexAdapter
import dev.ruleblend.core.integration.KimiCodeAdapter
import dev.ruleblend.core.integration.IntegrationService
import dev.ruleblend.core.integration.PiAdapter
import dev.ruleblend.core.integration.SkillInstallService
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SubagentInstallService
import dev.ruleblend.core.integration.SubagentInstallStateStore
import dev.ruleblend.core.integration.ZCodeAdapter
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.GitSkillSource
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.mcp.install.ClaudeCodeMcpInstaller
import dev.ruleblend.mcp.install.CodexMcpInstaller
import dev.ruleblend.mcp.install.KimiCodeMcpInstaller
import dev.ruleblend.mcp.install.McpInstallService
import dev.ruleblend.mcp.install.McpStateStore
import dev.ruleblend.mcp.install.PiMcpInstaller
import dev.ruleblend.mcp.install.ZCodeMcpInstaller
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RuleblendToolsTest {

    private lateinit var home: Path
    private lateinit var project: Path
    private lateinit var repository: LibraryRepository
    private lateinit var configStore: ConfigStore
    private lateinit var tools: RuleblendTools

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-mcp-home")
        project = Files.createTempDirectory("ruleblend-mcp-project")
        // Claude Code, Codex, Kimi Code + ZCode "installed" in the fake home.
        home.resolve(".claude").createDirectories()
        home.resolve(".codex").createDirectories()
        home.resolve(".kimi-code").createDirectories()
        home.resolve(".zcode").createDirectories()
        val libraryPath = home.resolve(".ruleblend").resolve("library")
        repository = LibraryRepository(libraryPath)
        repository.init()
        configStore = ConfigStore(home.resolve(".ruleblend").resolve("config.json"))
        tools = RuleblendTools(
            repository = repository,
            configStore = configStore,
            // Pi is wired but not installed in this home — the "known adapter, absent agent" case.
            agents = listOf(ClaudeCodeAdapter(home), CodexAdapter(home), KimiCodeAdapter(home), ZCodeAdapter(home), PiAdapter(home)),
            mcpService = McpInstallService(
                installers = listOf(ClaudeCodeMcpInstaller(home), CodexMcpInstaller(home), KimiCodeMcpInstaller(home), ZCodeMcpInstaller(home), PiMcpInstaller(home)),
                state = McpStateStore(home.resolve(".ruleblend")),
            ),
            skillService = SkillInstallService(
                SkillInstallStateStore(home.resolve(".ruleblend")),
                repository::loadSkillSnapshot,
                agentById = { null },
            ),
            subagentService = SubagentInstallService(SubagentInstallStateStore(home.resolve(".ruleblend"))),
        )
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    private val block = Block(
        id = "git-no-commit",
        name = "git-no-commit",
        description = "Never push",
        content = "- Do not push.\n",
    )

    private val subagent = Block(
        id = "reviewer",
        name = "reviewer",
        description = "Review changes",
        type = BlockType.SUBAGENT,
        content = "Review the change for regressions.\n",
        variants = mapOf("claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra"))),
    )

    @Test
    fun `delete rule clears membership and leaves installed region visible`() {
        repository.saveBlock(block)
        repository.saveGroup(Group("review", "Review", blockIds = listOf(block.id)))
        repository.saveProfile(Profile("review", "Review", blockIds = listOf(block.id)))
        tools.install(args("target" to project.toString(), "rule_id" to block.id))

        tools.deleteRule(args("id" to block.id))

        assertEquals(null, repository.loadBlock(block.id))
        assertTrue(repository.loadGroup("review")!!.blockIds.isEmpty())
        assertTrue(repository.loadProfile("review")!!.blockIds.isEmpty())
        val status = (tools.targetStatus(args("target" to project.toString())) as JsonArray)
            .single { it.jsonObject["id"]?.jsonPrimitive?.content == block.id }.jsonObject
        assertEquals("not-in-library", status["status"]?.jsonPrimitive?.content)
        assertFailsWith<ToolError> { tools.deleteRule(args("id" to "missing")) }
        assertFailsWith<ToolError> { tools.deleteRule(args("id" to subagent.id)) }
    }

    @Test
    fun `mcp server CRUD masks secrets and validates transport`() {
        val env = buildJsonObject { put("TOKEN", "top-secret") }
        val created = tools.createMcpServer(args("name" to "Remote helper", "transport" to "stdio",
            "command" to "npx", "env" to env)).jsonObject
        val id = created["id"]!!.jsonPrimitive.content

        assertEquals("***", tools.getMcpServer(args("id" to id)).jsonObject["env"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content)
        assertFalse("top-secret" in tools.listMcpServers(args()).toString())
        tools.updateMcpServer(args("id" to id, "command" to "node"))
        assertEquals("top-secret", (McpConfigCodec.parse(repository.loadBlock(id)!!.content).getOrThrow()
            as McpServerConfig.Stdio).env["TOKEN"])
        assertFailsWith<ToolError> { tools.updateMcpServer(args("id" to id, "url" to "https://example.com")) }
        assertFailsWith<ToolError> { tools.createMcpServer(args("name" to "Bad", "transport" to "http", "url" to "file://bad")) }
        repository.saveGroup(Group("review", "Review", blockIds = listOf(id)))
        tools.deleteMcpServer(args("id" to id))
        assertEquals(null, repository.loadBlock(id))
        assertTrue(repository.loadGroup("review")!!.blockIds.isEmpty())
        assertFailsWith<ToolError> { tools.getMcpServer(args("id" to id)) }

        val headers = buildJsonObject { put("Authorization", "Bearer private") }
        val http = tools.createMcpServer(args("name" to "Web helper", "transport" to "http",
            "url" to "https://example.com/mcp", "headers" to headers)).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("***", tools.getMcpServer(args("id" to http)).jsonObject["headers"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertFalse("Bearer private" in tools.listMcpServers(args()).toString())
        tools.deleteMcpServer(args("id" to http))
    }

    @Test
    fun `groups and profiles mutate typed members and protect generated group`() {
        repository.saveBlock(block)
        repository.saveBlock(subagent)
        val group = tools.createGroup(args("name" to "Review set")).jsonObject["id"]!!.jsonPrimitive.content
        val profile = tools.createProfile(args("name" to "Review mode")).jsonObject["id"]!!.jsonPrimitive.content
        val addGroup = buildJsonObject { put("rule_ids", buildJsonArray { add(JsonPrimitive(block.id)) }) }
        val addProfile = buildJsonObject {
            put("group_ids", buildJsonArray { add(JsonPrimitive(group)) })
            put("subagent_ids", buildJsonArray { add(JsonPrimitive(subagent.id)) })
        }
        tools.updateGroup(args("id" to group, "add" to addGroup))
        tools.updateProfile(args("id" to profile, "add" to addProfile))
        assertEquals(listOf(block.id), tools.getGroup(args("id" to group)).jsonObject["rule_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(group), tools.getProfile(args("id" to profile)).jsonObject["group_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue((tools.listGroups(args()) as JsonArray).any { it.jsonObject["id"]?.jsonPrimitive?.content == group })
        assertEquals(listOf(block.id), repository.loadGroup(group)!!.blockIds)
        assertEquals(listOf(group), repository.loadProfile(profile)!!.groupIds)
        tools.install(args("target" to project.toString(), "profile_id" to profile))
        assertTrue(configStore.load().projectProfiles[project.projectKey()].orEmpty().any { it.id == profile })
        assertFailsWith<ToolError> { tools.updateGroup(args("id" to group, "add" to
            buildJsonObject { put("mcp_ids", buildJsonArray { add(JsonPrimitive(block.id)) }) })) }
        assertFailsWith<ToolError> { tools.deleteGroup(args("id" to ALL_GROUP_ID)) }
        tools.updateGroup(args("id" to group, "remove" to addGroup))
        assertTrue(repository.loadGroup(group)!!.blockIds.isEmpty())
        tools.deleteGroup(args("id" to group))
        assertTrue(repository.loadProfile(profile)!!.groupIds.isEmpty())
        tools.deleteProfile(args("id" to profile))
        assertEquals(null, repository.loadProfile(profile))
        assertTrue(configStore.load().projectProfiles[project.projectKey()].orEmpty().none { it.id == profile })
    }

    // --- list / get ---

    @Test
    fun `list_rules returns summaries without content`() {
        repository.saveBlock(block)

        val result = tools.listRules(args()) as JsonArray

        assertEquals(1, result.size)
        val entry = result[0].jsonObject
        assertEquals("git-no-commit", entry["id"]?.jsonPrimitive?.content)
        assertEquals(1, entry["version"]?.jsonPrimitive?.int)
        assertFalse("content" in entry)
    }

    @Test
    fun `list_rules filters by query over content`() {
        repository.saveBlock(block)
        repository.saveBlock(block.copy(id = "swift-style", name = "swift-style", content = "- Use SwiftFormat.\n"))

        val result = tools.listRules(args("query" to "swiftformat")) as JsonArray

        assertEquals(listOf("swift-style"), result.map { it.jsonObject["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `list_rules hides project rules unless their target is requested`() {
        val projectBlock = block.copy(id = "project-rule", name = "project-rule")
        repository.saveBlock(block)
        repository.saveBlock(projectBlock)
        configStore.save(AppConfig().withRuleScope(projectBlock.id, project))

        val global = tools.listRules(args()) as JsonArray
        val forProject = tools.listRules(args("target" to project.toString())) as JsonArray

        assertEquals(listOf(block.id), global.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertEquals(
            listOf(block.id, projectBlock.id),
            forProject.map { it.jsonObject["id"]?.jsonPrimitive?.content },
        )
        assertEquals(project.projectKey(), forProject[1].jsonObject["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get_rule returns full content and fails on unknown id`() {
        repository.saveBlock(block)

        val result = tools.getRule(args("id" to block.id)) as JsonObject
        assertEquals(block.content, result["content"]?.jsonPrimitive?.content)

        assertFailsWith<ToolError> { tools.getRule(args("id" to "nope")) }
    }

    @Test
    fun `list and get subagents expose their portable definition and per assistant fields`() {
        repository.saveBlock(subagent)

        val listed = tools.listSubagents(args("query" to "terra")) as JsonArray
        val loaded = tools.getSubagent(args("id" to subagent.id)) as JsonObject

        assertEquals(listOf(subagent.id), listed.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertFalse("content" in listed.single().jsonObject)
        assertEquals(
            "gpt-5.6-terra",
            listed.single().jsonObject["variants"]?.jsonObject?.get("claude-code")?.jsonObject?.get("model")?.jsonPrimitive?.content,
        )
        assertEquals(subagent.content, loaded["content"]?.jsonPrimitive?.content)
        assertEquals(
            "gpt-5.6-terra",
            loaded["variants"]?.jsonObject?.get("claude-code")?.jsonObject?.get("model")?.jsonPrimitive?.content,
        )
        assertFailsWith<ToolError> { tools.getSubagent(args("id" to block.id)) }
    }

    @Test
    fun `list and get skills expose local library entries`() {
        repository.createSkill(Skill("review", "review", description = "Review safely", content = "# Review\n"))

        val listed = tools.listSkills(args("query" to "safely")) as JsonArray
        val loaded = tools.getSkill(args("id" to "review")) as JsonObject

        assertEquals(listOf("review"), listed.map { it.jsonObject["id"]?.jsonPrimitive?.content })
        assertEquals("# Review\n", loaded["content"]?.jsonPrimitive?.content)
        assertEquals("local", loaded["source"]?.jsonPrimitive?.content)
    }

    @Test
    fun `list_profiles exposes portable profile membership`() {
        repository.saveProfile(
            Profile(
                id = "review",
                name = "Review",
                description = "Review changes",
                blockIds = listOf(block.id),
                skillIds = listOf("review-skill"),
                subagentIds = listOf(subagent.id),
                groupIds = listOf("ios"),
            ),
        )

        val profile = (tools.listProfiles(args("query" to "changes")) as JsonArray).single().jsonObject

        assertEquals("review", profile["id"]?.jsonPrimitive?.content)
        assertEquals(listOf(block.id), (profile["block_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("review-skill"), (profile["skill_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(subagent.id), (profile["subagent_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf("ios"), (profile["group_ids"] as JsonArray).map { it.jsonPrimitive.content })
    }

    // --- create / update ---

    @Test
    fun `create_rule slugs the name and syncs the all group`() {
        val result = tools.createRule(args("name" to "Git No Push", "content" to "- Never push.\n")) as JsonObject

        assertEquals("git-no-push", result["id"]?.jsonPrimitive?.content)
        assertEquals(1, result["version"]?.jsonPrimitive?.int)
        assertEquals(listOf("git-no-push"), repository.loadGroup(ALL_GROUP_ID)?.blockIds)
    }

    @Test
    fun `create_rule deduplicates colliding ids`() {
        repository.saveBlock(block)

        val result = tools.createRule(args("name" to "Git no commit", "content" to "x")) as JsonObject

        assertEquals("git-no-commit-2", result["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create and update rule move scope without changing content version`() {
        val created = tools.createRule(
            args("name" to "Project Rule", "content" to "x", "scope" to project.toString()),
        ) as JsonObject
        val id = created["id"]?.jsonPrimitive?.content ?: error("missing id")

        assertEquals(project.projectKey(), configStore.load().ruleScopes[id])
        assertEquals(1, created["version"]?.jsonPrimitive?.int)
        assertTrue(project.projectKey() in configStore.load().projects)

        val updated = tools.updateRule(args("id" to id, "scope" to "global")) as JsonObject
        assertEquals(null, configStore.load().ruleScopes[id])
        assertEquals(1, updated["version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `update_rule with a bad scope or a blank field writes nothing`() {
        repository.saveBlock(block)

        assertFailsWith<ToolError> { tools.updateRule(args("id" to block.id, "content" to "changed", "scope" to "relative/dir")) }
        assertFailsWith<ToolError> { tools.updateRule(args("id" to block.id, "name" to "  ")) }
        assertFailsWith<ToolError> { tools.updateRule(args("id" to block.id, "content" to "")) }

        assertEquals(block, repository.loadBlock(block.id))
    }

    @Test
    fun `rule string arguments reject other JSON types before writing`() {
        val invalid = listOf(JsonPrimitive(42), JsonPrimitive(true), JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()))
        val initialConfig = configStore.load()
        for (field in listOf("name", "content", "description", "heading", "scope")) {
            for (value in invalid) {
                val input = JsonObject(args("name" to "New rule", "content" to "Instructions") + (field to value))
                assertFailsWith<ToolError>("create $field: $value") { tools.createRule(input) }
                assertTrue(repository.listBlocks().isEmpty())
                assertEquals(initialConfig, configStore.load())
            }
        }
        repository.saveBlock(block)
        for (field in listOf("id", "name", "content", "description", "heading", "scope")) {
            for (value in invalid) {
                val input = JsonObject(args("id" to block.id, "content" to "Changed instructions") + (field to value))
                assertFailsWith<ToolError>("update $field: $value") { tools.updateRule(input) }
                assertEquals(block, repository.loadBlock(block.id))
                assertEquals(initialConfig, configStore.load())
            }
        }
    }

    @Test
    fun `invalid heading levels reject the whole rule mutation`() {
        val invalid = listOf(JsonPrimitive("2"), JsonPrimitive("bad"), JsonPrimitive(2.5), JsonPrimitive(true),
            JsonNull, JsonArray(emptyList()), JsonObject(emptyMap()), JsonPrimitive(0), JsonPrimitive(7))
        for (value in invalid) {
            assertFailsWith<ToolError>("create heading_level: $value") {
                tools.createRule(args("name" to "New rule", "content" to "Instructions", "heading_level" to value))
            }
            assertTrue(repository.listBlocks().isEmpty())
        }
        repository.saveBlock(block)
        for (value in invalid) {
            assertFailsWith<ToolError>("update heading_level: $value") {
                tools.updateRule(args("id" to block.id, "content" to "Changed instructions", "heading_level" to value))
            }
            assertEquals(block, repository.loadBlock(block.id))
        }
        tools.updateRule(args("id" to block.id, "heading_level" to JsonPrimitive(2)))
        assertEquals(2, repository.loadBlock(block.id)?.headingLevel)
    }

    @Test
    fun `update_rule bumps version only on content change`() {
        repository.saveBlock(block)

        val renamed = tools.updateRule(args("id" to block.id, "description" to "new")) as JsonObject
        assertEquals(1, renamed["version"]?.jsonPrimitive?.int)

        val edited = tools.updateRule(args("id" to block.id, "content" to "- Ask first.\n")) as JsonObject
        assertEquals(2, edited["version"]?.jsonPrimitive?.int)
    }

    @Test
    fun `create and update subagent preserve its type and version its definition`() {
        val created = tools.createSubagent(
            args(
                "name" to "Regression Reviewer",
                "description" to "Review regressions",
                "content" to "Check edge cases.\n",
                "model" to "gpt-5.6-terra",
            ),
        ) as JsonObject
        val id = created["id"]?.jsonPrimitive?.content ?: error("missing id")

        assertEquals("regression-reviewer", id)
        assertEquals(BlockType.SUBAGENT, repository.loadBlock(id)?.type)
        // The 'model' shortcut reaches every assistant that can host a subagent, and no other.
        assertEquals(
            mapOf(
                "claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
                "codex" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
                "kimi-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra")),
            ),
            repository.loadBlock(id)?.variants,
        )
        assertEquals(listOf(id), repository.loadGroup(ALL_GROUP_ID)?.blockIds)

        val updated = tools.updateSubagent(args("id" to id, "model" to "", "content" to "Check all edge cases.\n")) as JsonObject
        assertEquals(2, updated["version"]?.jsonPrimitive?.int)
        assertEquals(emptyMap(), repository.loadBlock(id)?.variants)
    }

    @Test
    fun `delete subagent removes its library memberships`() {
        repository.saveBlock(subagent)
        repository.saveGroup(Group("review", "Review", blockIds = listOf(subagent.id)))
        repository.saveProfile(Profile("quality", "Quality", subagentIds = listOf(subagent.id)))

        val result = tools.deleteSubagent(args("id" to subagent.id)) as JsonObject

        assertTrue(result["deleted"]?.jsonPrimitive?.boolean == true)
        assertEquals(null, repository.loadBlock(subagent.id))
        assertEquals(emptyList(), repository.loadGroup("review")?.blockIds)
        assertEquals(emptyList(), repository.loadProfile("quality")?.subagentIds)
    }

    @Test
    fun `create update and delete local skill maintain its library memberships`() {
        val created = tools.createSkill(
            args(
                "name" to "review",
                "description" to "Reviews changes",
                "content" to "# Review\n\nCheck regressions.\n",
            ),
        ) as JsonObject
        val id = created["id"]?.jsonPrimitive?.content ?: error("missing id")
        assertEquals("review", id)
        assertTrue(repository.loadSkill(id)?.content?.contains("name: \"review\"") == true)
        assertEquals(listOf(id), repository.loadGroup(ALL_GROUP_ID)?.skillIds)

        val updated = tools.updateSkill(
            args("id" to id, "description" to "Reviews changes safely", "content" to "# Review\n\nCheck every regression.\n"),
        ) as JsonObject
        assertEquals("2", updated["version"]?.jsonPrimitive?.content)
        assertTrue(repository.loadSkill(id)?.content?.contains("description: \"Reviews changes safely\"") == true)

        repository.saveGroup(Group("quality", "Quality", skillIds = listOf(id)))
        repository.saveProfile(Profile("quality-profile", "Quality", skillIds = listOf(id)))
        val deleted = tools.deleteSkill(args("id" to id)) as JsonObject

        assertTrue(deleted["deleted"]?.jsonPrimitive?.boolean == true)
        assertEquals(null, repository.loadSkill(id))
        assertEquals(emptyList(), repository.loadGroup("quality")?.skillIds)
        assertEquals(emptyList(), repository.loadProfile("quality-profile")?.skillIds)
    }

    @Test
    fun `update skill refuses an imported skill`() {
        val imported = Skill(
            id = "upstream-review",
            name = "upstream-review",
            content = "# Review\n",
            source = GitSkillSource("https://example.invalid/skills.git", "abc123", "review"),
        )
        repository.writeSkill(SkillSnapshot(imported, listOf(SkillFile("SKILL.md", imported.content.encodeToByteArray()))))

        val error = assertFailsWith<ToolError> {
            tools.updateSkill(args("id" to imported.id, "content" to "# Changed\n"))
        }

        assertTrue(error.message.orEmpty().contains("read-only"))
        assertEquals(imported.content, repository.loadSkill(imported.id)?.content)
    }

    @Test
    fun `subagent variants are stored per assistant and replaced one assistant at a time`() {
        val id = (
            tools.createSubagent(
                args(
                    "name" to "Reviewer",
                    "content" to "Check edge cases.\n",
                    "model" to "sonnet",
                    "variants" to buildJsonObject {
                        put(
                            "claude-code",
                            buildJsonObject {
                                put("model", "haiku")
                                put("tools", "Bash, Read")
                            },
                        )
                    },
                ),
            ) as JsonObject
            )["id"]?.jsonPrimitive?.content ?: error("missing id")

        // An explicit variant wins over the shortcut; the assistants it does not name keep it.
        assertEquals(
            mapOf(
                "claude-code" to SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")),
                "codex" to SubagentVariant(mapOf("model" to "sonnet")),
                "kimi-code" to SubagentVariant(mapOf("model" to "sonnet")),
            ),
            repository.loadBlock(id)?.variants,
        )

        tools.updateSubagent(
            args(
                "id" to id,
                "variants" to buildJsonObject { put("codex", buildJsonObject { put("model", "gpt-5-codex") }) },
            ),
        )

        assertEquals(
            SubagentVariant(mapOf("model" to "gpt-5-codex")),
            repository.loadBlock(id)?.variants?.get("codex"),
        )
        assertEquals(
            SubagentVariant(mapOf("model" to "haiku", "tools" to "Bash, Read")),
            repository.loadBlock(id)?.variants?.get("claude-code"),
        )

        tools.updateSubagent(args("id" to id, "variants" to buildJsonObject { put("kimi-code", buildJsonObject { }) }))
        assertEquals(null, repository.loadBlock(id)?.variants?.get("kimi-code"))
    }

    @Test
    fun `a variant for an assistant without subagent support is refused`() {
        repository.saveBlock(subagent)

        assertFailsWith<ToolError> {
            tools.updateSubagent(
                args(
                    "id" to subagent.id,
                    "variants" to buildJsonObject { put("pi", buildJsonObject { put("model", "x") }) },
                ),
            )
        }
    }

    // --- install ---

    @Test
    fun `install writes markers to AGENTS md and pointer to CLAUDE md`() {
        repository.saveBlock(block)

        val result = tools.install(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(listOf(block.id), (result["installed"] as JsonArray).map { it.jsonPrimitive.content })
        val agentsText = project.resolve("AGENTS.md").readText()
        assertEquals(listOf(block.id), IntegrationService().regions(project.resolve("AGENTS.md")).map { it.id })
        assertTrue(agentsText.contains("<!-- rb1 "), "AGENTS.md should carry an rb1 run: $agentsText")
        assertTrue(project.resolve("CLAUDE.md").readText().contains("@AGENTS.md"))
    }

    @Test
    fun `install auto-registers the project in config`() {
        repository.saveBlock(block)

        val result = tools.install(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(true, result["registered_project"]?.jsonPrimitive?.boolean)
        assertTrue(project.projectKey() in configStore.load().projects)

        // Another spelling of the same project does not re-register.
        val alias = if (java.io.File.separatorChar == '\\') project.toString().uppercase().replace('\\', '/')
            else project.resolve("../${project.fileName}").toString()
        val again = tools.install(args("target" to alias, "rule_id" to block.id)) as JsonObject
        assertFalse("registered_project" in again)
        assertEquals(1, configStore.load().projects.size)
        configStore.update { it.copy(disabledAgents = mapOf(project.projectKey() to listOf("claude-code", "codex", "kimi-code", "zcode"))) }
        val error = assertFailsWith<ToolError> { tools.install(args("target" to alias, "rule_id" to block.id)) }
        assertTrue(error.message.orEmpty().contains("No enabled agents"))
    }

    @Test
    fun `profile install attaches enables and uninstall detaches through reconciliation`() {
        val skill = repository.createSkill(Skill("review-skill", "review-skill", content = "# Review\n"))
        repository.saveBlock(block)
        repository.saveProfile(Profile("review", "Review", blockIds = listOf(block.id), skillIds = listOf(skill.id)))
        configStore.save(
            AppConfig(
                projectProfiles = mapOf(project.projectKey() to listOf(ProfileBinding("review", active = false))),
            ),
        )

        val installed = tools.install(args("target" to project.toString(), "profile_id" to "review")) as JsonObject

        assertEquals("review", installed["profile_id"]?.jsonPrimitive?.content)
        assertEquals(true, installed["attached"]?.jsonPrimitive?.boolean)
        assertEquals(listOf(block.id), (installed["installed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(skill.id), (installed["installed_skills"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(ProfileBinding("review", active = true)), configStore.load().projectProfiles[project.projectKey()])
        assertTrue(project.resolve("AGENTS.md").readText().contains("p=review"))
        assertTrue(project.resolve(".claude/skills/review-skill/SKILL.md").exists())

        val removed = tools.uninstall(args("target" to project.toString(), "profile_id" to "review")) as JsonObject

        assertEquals(false, removed["attached"]?.jsonPrimitive?.boolean)
        assertEquals(listOf(block.id), (removed["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(skill.id), (removed["removed_skills"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(emptyList(), configStore.load().projectProfiles[project.projectKey()])
        assertTrue(IntegrationService().regions(project.resolve("AGENTS.md")).isEmpty())
        assertFalse(project.resolve(".claude/skills/review-skill").exists())
    }

    @Test
    fun `profiles reject agent targets and mixed selectors`() {
        repository.saveProfile(Profile("review", "Review"))

        assertFailsWith<ToolError> { tools.install(args("target" to "agent:claude-code", "profile_id" to "review")) }
        assertFailsWith<ToolError> {
            tools.install(args("target" to project.toString(), "profile_id" to "review", "rule_id" to block.id))
        }
    }

    @Test
    fun `install skips hand-edited regions unless overwrite`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("- Do not push.", "- Hand edit."))

        val skipped = tools.install(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject
        assertEquals(listOf(block.id), (skipped["skipped_modified"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(file.readText().contains("- Hand edit."))

        val forced = tools.install(
            args("target" to project.toString(), "rule_id" to block.id, "overwrite" to true),
        ) as JsonObject
        assertEquals(listOf(block.id), (forced["installed"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(file.readText().contains("- Do not push."))
    }

    @Test
    fun `invalid overwrite flags never replace locally edited rules`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))
        val file = project.resolve("AGENTS.md")
        val edited = file.readText().replace("- Do not push.", "- Hand edit.")
        file.writeText(edited)
        val initialConfig = configStore.load()
        val invalid = listOf(JsonPrimitive("true"), JsonPrimitive("false"), JsonPrimitive(1), JsonNull,
            JsonArray(emptyList()), JsonObject(emptyMap()))
        for (value in invalid) {
            assertFailsWith<ToolError>("overwrite: $value") {
                tools.install(args("target" to project.toString(), "rule_id" to block.id, "overwrite" to value))
            }
            assertEquals(edited, file.readText())
            assertEquals(initialConfig, configStore.load())
        }
    }

    @Test
    fun `project-scoped rule installs only into its project`() {
        val other = Files.createTempDirectory("ruleblend-mcp-other")
        try {
            repository.saveBlock(block)
            configStore.save(AppConfig().withRuleScope(block.id, project))

            tools.install(args("target" to project.toString(), "rule_id" to block.id))

            assertFailsWith<ToolError> {
                tools.install(args("target" to other.toString(), "rule_id" to block.id))
            }
            assertFalse(other.projectKey() in configStore.load().projects)
            assertFailsWith<ToolError> {
                tools.install(args("target" to "agent:codex", "rule_id" to block.id))
            }
        } finally {
            other.toFile().deleteRecursively()
        }
    }

    @Test
    fun `group install and uninstall keep individually installed rules`() {
        val second = block.copy(id = "swift-style", name = "swift-style", content = "- Style.\n")
        repository.saveBlock(block)
        repository.saveBlock(second)
        repository.saveGroup(Group("ios", "ios", blockIds = listOf(block.id, second.id)))

        // block is installed individually first, then the group on top.
        tools.install(args("target" to project.toString(), "rule_id" to block.id))
        tools.install(args("target" to project.toString(), "group_id" to "ios"))

        val removed = tools.uninstall(args("target" to project.toString(), "group_id" to "ios")) as JsonObject
        // Both carried the group tag after the group install, so both go.
        assertEquals(
            listOf(block.id, second.id),
            (removed["removed"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue(IntegrationService().regions(project.resolve("AGENTS.md")).isEmpty())
    }

    @Test
    fun `group installs and uninstalls its skill members`() {
        val skill = repository.createSkill(Skill("review", "review", content = "# Review\n"))
        repository.saveGroup(Group("ios", "ios", skillIds = listOf(skill.id)))

        val installed = tools.install(args("target" to project.toString(), "group_id" to "ios")) as JsonObject
        assertEquals(listOf(skill.id), (installed["installed_skills"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(project.resolve(".claude/skills/review/SKILL.md").exists())
        assertTrue(project.resolve(".agents/skills/review/SKILL.md").exists())

        val removed = tools.uninstall(args("target" to project.toString(), "group_id" to "ios")) as JsonObject
        assertEquals(listOf(skill.id), (removed["removed_skills"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(project.resolve(".claude/skills/review").exists())
        assertFalse(project.resolve(".agents/skills/review").exists())
    }

    @Test
    fun `install and uninstall a subagent id use native definition files`() {
        repository.saveBlock(subagent)

        val installed = tools.install(args("target" to project.toString(), "subagent_id" to subagent.id)) as JsonObject
        assertEquals(listOf(subagent.id), (installed["installed_subagents"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(project.resolve(".claude/agents/reviewer.md").exists())
        assertTrue(project.resolve(".kimi-code/agents/reviewer.md").exists())
        assertTrue(
            project.resolve(".claude/agents/reviewer.md").readText().contains("Review the change for regressions."),
        )

        val removed = tools.uninstall(args("target" to project.toString(), "subagent_id" to subagent.id)) as JsonObject
        assertEquals(listOf(subagent.id), (removed["removed_subagents"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(project.resolve(".claude/agents/reviewer.md").exists())
        assertFalse(project.resolve(".kimi-code/agents/reviewer.md").exists())
    }

    @Test
    fun `MCP cannot edit an imported subagent and its local fork stays editable`() {
        val libraryRoot = home.resolve(".ruleblend/library")
        val url = "https://example.com/agents.git"
        val path = "agents/reviewer.toml"
        val draft = SubagentDraft("reviewer", "Reviews code", mapOf("model" to "native-model"), "Review instructions")
        val plan = GitRepositoryImportPlan(url, "abc123", "1", emptyList(),
            listOf(GitSubagentCandidate(path, "codex", mapOf("codex" to draft), "")))
        val importer = ImportLibrary(repository, LibraryArchive(libraryRoot, repository), configStore, GitRepositoryFetcher { plan })
        importer.applyGit(plan, emptySet(), mapOf(path to "codex"))
        val original = repository.listBlocks().single()

        val error = assertFailsWith<ToolError> { tools.updateSubagent(args("id" to original.id, "content" to "Edited")) }
        assertTrue(error.message.orEmpty().contains("read-only"))
        assertEquals(original, repository.loadBlock(original.id))
        val fork = repository.forkSubagent(original.id, "reviewer-changed", "Reviewer changed")
        tools.updateSubagent(args("id" to fork.id, "content" to "Local instructions"))
        assertEquals("Local instructions", repository.loadBlock(fork.id)?.content)
        assertEquals(original.source, repository.loadBlock(fork.id)?.forkedFrom)
    }

    @Test
    fun `subagent MCP install and uninstall keep a hand-edited definition`() {
        repository.saveBlock(subagent)
        tools.install(args("target" to project.toString(), "subagent_id" to subagent.id))
        val file = project.resolve(".claude/agents/reviewer.md")
        file.writeText("manual definition\n")

        val installed = tools.install(args("target" to project.toString(), "subagent_id" to subagent.id)) as JsonObject
        assertEquals(
            listOf(subagent.id),
            (installed["skipped_modified_subagents"] as JsonArray).map { it.jsonPrimitive.content },
        )
        val removed = tools.uninstall(args("target" to project.toString(), "subagent_id" to subagent.id)) as JsonObject
        assertEquals(
            listOf(subagent.id),
            (removed["skipped_modified_subagents"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals("manual definition\n", file.readText())
    }

    @Test
    fun `groups report and install their subagent members`() {
        repository.saveBlock(block)
        repository.saveBlock(subagent)
        repository.saveGroup(Group(id = "review", name = "Review", blockIds = listOf(block.id, subagent.id)))

        val group = (tools.listGroups(args()) as JsonArray).map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == "review" }
        assertEquals(listOf(block.id), (group["rule_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(subagent.id), (group["subagent_ids"] as JsonArray).map { it.jsonPrimitive.content })

        val installed = tools.install(args("target" to project.toString(), "group_id" to "review")) as JsonObject
        assertEquals(listOf(subagent.id), (installed["installed_subagents"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(project.resolve(".claude/agents/reviewer.md").exists())
    }

    @Test
    fun `ZCode and Pi remain unsupported subagent targets`() {
        repository.saveBlock(subagent)

        val zcode = tools.install(args("target" to "agent:zcode", "subagent_id" to subagent.id)) as JsonObject
        assertEquals(
            listOf(subagent.id),
            (zcode["skipped_unsupported_subagents"] as JsonArray).map { it.jsonPrimitive.content },
        )
        val removed = tools.uninstall(args("target" to "agent:zcode", "subagent_id" to subagent.id)) as JsonObject
        assertEquals(
            listOf(subagent.id),
            (removed["skipped_unsupported_subagents"] as JsonArray).map { it.jsonPrimitive.content },
        )
        // Pi absent from this home is a missing target, not an unsupported one.
        assertFailsWith<ToolError> { tools.install(args("target" to "agent:pi", "subagent_id" to subagent.id)) }

        home.resolve(".pi/agent").createDirectories()
        val pi = tools.install(args("target" to "agent:pi", "subagent_id" to subagent.id)) as JsonObject
        assertEquals(
            listOf(subagent.id),
            (pi["skipped_unsupported_subagents"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue(tools.targetStatus(args("target" to "agent:pi")).jsonArray.isEmpty())
    }

    @Test
    fun `Pi installs and removes MCP servers through its native configuration`() {
        repository.saveBlock(mcpBlock)
        home.resolve(".pi/agent").createDirectories()

        val result = tools.install(args("target" to "agent:pi", "mcp_id" to mcpBlock.id)) as JsonObject

        assertEquals(listOf(mcpBlock.id), (result["installed_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(emptyList(), (result["skipped_unsupported_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        val file = home.resolve(".pi/agent/mcp.json")
        assertTrue(mcpBlock.id in file.readText())
        assertEquals(1, tools.targetStatus(args("target" to "agent:pi")).jsonArray.size)

        tools.uninstall(args("target" to "agent:pi", "mcp_id" to mcpBlock.id))
        assertFalse(mcpBlock.id in file.readText())
        assertTrue(tools.targetStatus(args("target" to "agent:pi")).jsonArray.isEmpty())
    }

    @Test
    fun `uninstall keeps a hand-edited rule and reports it`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("- Do not push.", "- Hand edit."))

        val result = tools.uninstall(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(emptyList(), (result["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(block.id), (result["skipped_modified"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue("- Hand edit." in file.readText())
    }

    @Test
    fun `group uninstall reports the whole hand-edited run as kept`() {
        val second = block.copy(id = "swift-style", name = "swift-style", content = "- Style.\n")
        repository.saveBlock(block)
        repository.saveBlock(second)
        repository.saveGroup(Group("ios", "ios", blockIds = listOf(block.id, second.id)))
        tools.install(args("target" to project.toString(), "group_id" to "ios"))
        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("- Do not push.", "- Hand edit."))

        val result = tools.uninstall(args("target" to project.toString(), "group_id" to "ios")) as JsonObject

        // A hand edit anywhere in a managed run puts every region of that file out of reach of an
        // unforced write, so the answer is "nothing removed", reported instead of thrown.
        assertEquals(emptyList(), (result["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(
            listOf(block.id, second.id),
            (result["skipped_modified"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertEquals(listOf(block.id, second.id), IntegrationService().regions(file).map { it.id })
    }

    @Test
    fun `uninstall takes out a rule the library no longer holds`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))
        repository.deleteBlock(block.id)

        val result = tools.uninstall(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(listOf(block.id), (result["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(IntegrationService().regions(project.resolve("AGENTS.md")).isEmpty())
    }

    @Test
    fun `uninstall removes a single rule`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))

        val removed = tools.uninstall(args("target" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(listOf(block.id), (removed["removed"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(IntegrationService().regions(project.resolve("AGENTS.md")).isEmpty())
        // Pointer goes with the last region.
        assertFalse(project.resolve("CLAUDE.md").exists() && project.resolve("CLAUDE.md").readText().contains("@AGENTS.md"))
    }

    // --- agent global targets ---

    @Test
    fun `install writes into the agent's global file`() {
        repository.saveBlock(block)

        val result = tools.install(args("target" to "agent:claude-code", "rule_id" to block.id)) as JsonObject

        assertEquals(listOf(block.id), (result["installed"] as JsonArray).map { it.jsonPrimitive.content })
        val globalFile = ClaudeCodeAdapter(home).globalFile()
        assertEquals(listOf(block.id), IntegrationService().regions(globalFile).map { it.id })
        assertTrue(globalFile.readText().contains("- Do not push."))
        // An agent target is not a project: nothing lands in the project list.
        assertFalse("registered_project" in result)
        assertTrue(configStore.load().projects.isEmpty())
    }

    @Test
    fun `agent target reports status and uninstalls`() {
        repository.saveBlock(block)
        tools.install(args("target" to "agent:codex", "rule_id" to block.id))

        val status = (tools.targetStatus(args("target" to "agent:codex")) as JsonArray)[0].jsonObject
        assertEquals(block.id, status["id"]?.jsonPrimitive?.content)
        assertEquals("synced", status["status"]?.jsonPrimitive?.content)

        tools.uninstall(args("target" to "agent:codex", "rule_id" to block.id))
        assertTrue(IntegrationService().regions(CodexAdapter(home).globalFile()).isEmpty())
    }

    @Test
    fun `agent target rejects unknown, unavailable and hidden agents`() {
        repository.saveBlock(block)

        // Not an agent at all.
        assertFailsWith<ToolError> { tools.install(args("target" to "agent:nope", "rule_id" to block.id)) }
        // Present in the adapter list, but not installed in this home.
        assertFailsWith<ToolError> { tools.install(args("target" to "agent:pi", "rule_id" to block.id)) }

        configStore.save(configStore.load().copy(hiddenAgents = listOf("claude-code")))
        assertFailsWith<ToolError> { tools.install(args("target" to "agent:claude-code", "rule_id" to block.id)) }
    }

    @Test
    fun `list_targets lists available agents and known projects`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))

        val targets = (tools.listTargets(args()) as JsonArray).map { it.jsonObject }

        assertEquals(
            listOf("agent:claude-code", "agent:codex", "agent:kimi-code", "agent:zcode", project.projectKey()),
            targets.map { it["id"]?.jsonPrimitive?.content },
        )
        assertEquals(listOf("agent", "agent", "agent", "agent", "project"), targets.map { it["kind"]?.jsonPrimitive?.content })
        assertEquals(
            listOf(ClaudeCodeAdapter(home).globalFile().toString()),
            (targets[0]["files"] as JsonArray).map { it.jsonPrimitive.content },
        )
        // A hidden agent drops out, exactly as it does in the GUI.
        configStore.save(configStore.load().copy(hiddenAgents = listOf("codex")))
        assertFalse("agent:codex" in (tools.listTargets(args()) as JsonArray).map { it.jsonObject["id"]?.jsonPrimitive?.content })
    }

    // --- status ---

    @Test
    fun `target_status reports synced update and modified`() {
        repository.saveBlock(block)
        tools.install(args("target" to project.toString(), "rule_id" to block.id))

        fun statusOf(id: String): String? = (tools.targetStatus(args("target" to project.toString())) as JsonArray)
            .map { it.jsonObject }
            .find { it["id"]?.jsonPrimitive?.content == id }
            ?.get("status")?.jsonPrimitive?.content

        assertEquals("synced", statusOf(block.id))

        repository.saveBlock(block.copy(content = "- Newer.\n"))
        assertEquals("update-available", statusOf(block.id))

        val file = project.resolve("AGENTS.md")
        file.writeText(file.readText().replace("- Do not push.", "- Hand edit."))
        assertEquals("modified", statusOf(block.id))
    }

    @Test
    fun `target_status reports MCP entries and subagents by kind`() {
        repository.saveBlock(mcpBlock)
        repository.saveBlock(subagent)
        tools.install(args("target" to project.toString(), "mcp_id" to mcpBlock.id))
        tools.install(args("target" to project.toString(), "subagent_id" to subagent.id))

        fun row(id: String): JsonObject? = (tools.targetStatus(args("target" to project.toString())) as JsonArray)
            .map { it.jsonObject }
            .find { it["id"]?.jsonPrimitive?.content == id }

        assertEquals("mcp", row(mcpBlock.id)?.get("kind")?.jsonPrimitive?.content)
        assertEquals("synced", row(mcpBlock.id)?.get("status")?.jsonPrimitive?.content)
        assertEquals("subagent", row(subagent.id)?.get("kind")?.jsonPrimitive?.content)
        assertEquals("synced", row(subagent.id)?.get("status")?.jsonPrimitive?.content)

        repository.saveBlock(subagent.copy(content = "Review harder.\n"))
        assertEquals("update-available", row(subagent.id)?.get("status")?.jsonPrimitive?.content)

        tools.uninstall(args("target" to project.toString(), "mcp_id" to mcpBlock.id))
        assertEquals(null, row(mcpBlock.id))
    }

    // --- errors ---

    @Test
    fun `install validates arguments`() {
        repository.saveBlock(block)

        assertFailsWith<ToolError> { tools.install(args("target" to "relative/path", "rule_id" to block.id)) }
        assertFailsWith<ToolError> {
            tools.install(args("target" to project.resolve("missing").toString(), "rule_id" to block.id))
        }
        assertFailsWith<ToolError> { tools.install(args("target" to project.toString())) }
        assertFailsWith<ToolError> {
            tools.install(args("target" to project.toString(), "rule_id" to block.id, "group_id" to "g"))
        }
        assertFailsWith<ToolError> { tools.install(args("target" to project.toString(), "rule_id" to "nope")) }
    }

    @Test
    fun `project_path still works as an alias for target`() {
        repository.saveBlock(block)

        val result = tools.install(args("project_path" to project.toString(), "rule_id" to block.id)) as JsonObject

        assertEquals(listOf(block.id), (result["installed"] as JsonArray).map { it.jsonPrimitive.content })
    }

    // --- type routing: MCP blocks are never rules ---

    private val mcpBlock = Block(
        id = "context7",
        name = "context7",
        description = "Docs server",
        content = McpConfigCodec.serialize(
            McpServerConfig.Stdio(command = "npx", args = listOf("-y", "@upstash/context7-mcp")),
        ),
        type = BlockType.MCP,
    )

    @Test
    fun `list_rules hides MCP blocks`() {
        repository.saveBlock(block)
        repository.saveBlock(mcpBlock)

        val result = tools.listRules(args()) as JsonArray

        assertEquals(listOf(block.id), result.map { it.jsonObject["id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `get_rule and update_rule reject an MCP block`() {
        repository.saveBlock(mcpBlock)

        assertFailsWith<ToolError> { tools.getRule(args("id" to mcpBlock.id)) }
        assertFailsWith<ToolError> { tools.updateRule(args("id" to mcpBlock.id, "content" to "broken")) }
        assertEquals(mcpBlock.content, repository.loadBlock(mcpBlock.id)?.content)
    }

    @Test
    fun `install rejects an MCP block named as a rule`() {
        repository.saveBlock(mcpBlock)

        assertFailsWith<ToolError> {
            tools.install(args("target" to project.toString(), "rule_id" to mcpBlock.id))
        }
        assertFalse(project.resolve("AGENTS.md").exists())
    }

    @Test
    fun `installing a group writes its MCP members outside the instruction file`() {
        repository.saveBlock(block)
        repository.saveBlock(mcpBlock)
        val skill = Skill(id = "review", name = "review", description = "Review", content = "Do a review.\n")
        repository.createSkill(skill)
        repository.saveGroup(
            Group(id = "starter", name = "Starter", blockIds = listOf(block.id, mcpBlock.id), skillIds = listOf(skill.id)),
        )

        val result = tools.install(args("target" to project.toString(), "group_id" to "starter")) as JsonObject

        assertEquals(listOf(block.id), (result["installed"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(mcpBlock.id), (result["installed_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse("skipped_mcp" in result)
        val agents = project.resolve("AGENTS.md").readText()
        assertTrue(block.id in agents)
        assertFalse("@upstash/context7-mcp" in agents)
        assertTrue(mcpBlock.id in project.resolve(".mcp.json").readText())
        assertTrue(mcpBlock.id in project.resolve(".codex/config.toml").readText())
        assertTrue(mcpBlock.id in project.resolve(".kimi-code/mcp.json").readText())
    }

    @Test
    fun `list_groups reports MCP members apart from rules`() {
        repository.saveBlock(block)
        repository.saveBlock(mcpBlock)
        repository.saveGroup(Group(id = "starter", name = "Starter", blockIds = listOf(block.id, mcpBlock.id)))

        val group = (tools.listGroups(args()) as JsonArray)
            .map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == "starter" }

        assertEquals(listOf(block.id), (group["rule_ids"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(listOf(mcpBlock.id), (group["mcp_ids"] as JsonArray).map { it.jsonPrimitive.content })
    }

    @Test
    fun `installing an MCP-only group writes native configs only`() {
        repository.saveBlock(mcpBlock)
        repository.saveGroup(Group(id = "servers", name = "Servers", blockIds = listOf(mcpBlock.id)))

        val result = tools.install(args("target" to project.toString(), "group_id" to "servers")) as JsonObject

        assertEquals(listOf(mcpBlock.id), (result["installed_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(project.resolve("AGENTS.md").exists())
        assertTrue(mcpBlock.id in project.resolve(".mcp.json").readText())
    }

    @Test
    fun `install and uninstall an MCP id write native configs`() {
        repository.saveBlock(mcpBlock)

        val installed = tools.install(args("target" to project.toString(), "mcp_id" to mcpBlock.id)) as JsonObject
        assertEquals(listOf(mcpBlock.id), (installed["installed_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        assertTrue(mcpBlock.id in project.resolve(".mcp.json").readText())
        assertTrue(mcpBlock.id in project.resolve(".codex/config.toml").readText())
        assertTrue(mcpBlock.id in project.resolve(".kimi-code/mcp.json").readText())

        val removed = tools.uninstall(args("target" to project.toString(), "mcp_id" to mcpBlock.id)) as JsonObject
        assertEquals(listOf(mcpBlock.id), (removed["removed_mcp"] as JsonArray).map { it.jsonPrimitive.content })
        assertFalse(mcpBlock.id in project.resolve(".mcp.json").readText())
        assertFalse(mcpBlock.id in project.resolve(".codex/config.toml").readText())
        assertFalse(mcpBlock.id in project.resolve(".kimi-code/mcp.json").readText())
    }
}
