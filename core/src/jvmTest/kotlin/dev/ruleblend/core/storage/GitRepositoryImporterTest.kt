package dev.ruleblend.core.storage

import dev.ruleblend.core.deleteFixtureTree
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode

class GitRepositoryImporterTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() { root = Files.createTempDirectory("ruleblend-git-agents-test-") }

    @AfterTest
    fun tearDown() { deleteFixtureTree(root) }

    @Test
    fun `VoltAgent layout without skills captures multiline body and every native field`() {
        val upstream = repository(mapOf(
            "categories/01-core-development/backend-developer.toml" to codex(),
            "README.md" to "# Collection\n",
        ))
        val plan = GitRepositoryImporter().fetch(upstream.toString())
        assertTrue(plan.skills.isEmpty())
        assertTrue(plan.errors.isEmpty())
        assertEquals(40, plan.revision.length)
        assertEquals(plan.revision.take(12), plan.version)
        val candidate = plan.subagents.single()
        assertEquals("categories/01-core-development/backend-developer.toml", candidate.path)
        assertEquals("codex", candidate.sourceAssistant)
        assertFalse(candidate.requiresAssistantSelection)
        val draft = candidate.draftFor("codex")
        assertEquals("backend-developer", draft.name)
        assertEquals("Implement backend changes.\nCheck data integrity.\n", draft.content)
        assertEquals(mapOf("model" to "gpt-fixture", "model_reasoning_effort" to "high", "sandbox_mode" to "workspace-write"), draft.fields)
    }

    @Test
    fun `Markdown collection requires an explicit assistant and keeps both interpretations`() {
        val upstream = repository(mapOf("categories/review/reviewer.md" to markdown("modelPreference: kimi-test\ncustom: retained\n")))
        val candidate = GitRepositoryImporter().fetch(upstream.toString()).subagents.single()
        assertTrue(candidate.requiresAssistantSelection)
        assertEquals(setOf("claude-code", "kimi-code"), candidate.drafts.keys)
        assertEquals("kimi-test", candidate.draftFor("kimi-code").model)
        assertEquals("kimi-test", candidate.draftFor("claude-code").fields["modelPreference"])
        assertEquals("retained", candidate.draftFor("kimi-code").fields["custom"])
        assertFailsWith<IllegalStateException> { candidate.draftFor("codex") }
    }

    @Test
    fun `mixed repository shares exact revision and still captures complete skills`() {
        val upstream = repository(mapOf(
            "skills/review/SKILL.md" to "---\nname: review\ndescription: Reviews\n---\nReview.\n",
            "skills/review/references/check.md" to "Check tests.\n",
            ".codex/agents/reviewer.toml" to codex(),
            ".claude/agents/reviewer.md" to markdown("model: haiku\n"),
            ".kimi-code/agents/reviewer.md" to markdown("modelPreference: kimi-test\n"),
        ))
        val plan = GitSkillImporter().fetch(upstream.toString())
        assertEquals(plan.revision, plan.skills.single().skill.source?.revision)
        assertEquals(setOf("SKILL.md", "references/check.md"), plan.skills.single().files.map { it.path }.toSet())
        assertEquals(setOf("codex", "claude-code", "kimi-code"), plan.subagents.map { it.sourceAssistant }.toSet())
        assertTrue(plan.subagents.none { it.requiresAssistantSelection })
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `declared agent files and directories keep their source assistant and deduplicate`() {
        val upstream = repository(mapOf(
            ".codex-plugin/plugin.json" to """{"version":"1.2.3","agents":"./definitions/codex"}""",
            ".claude-plugin/plugin.json" to """{"agents":["./definitions/reviewer.md","./.claude/agents"]}""",
            "definitions/codex/nested/reviewer.toml" to codex(),
            "definitions/reviewer.md" to markdown(),
            ".claude/agents/reviewer.md" to markdown(),
        ))
        val plan = GitRepositoryImporter().fetch(upstream.toString())
        assertEquals("1.2.3", plan.version)
        assertEquals(3, plan.subagents.size)
        assertEquals(setOf("claude-code", "codex"), plan.subagents.map { it.sourceAssistant }.toSet())
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `documentation and arbitrary Markdown never become agents`() {
        val upstream = repository(mapOf(
            "categories/reviewer.toml" to codex(),
            "categories/README.md" to markdown(),
            "categories/docs.md" to "---\ntitle: Guide\n---\nname: sample\ndescription: sample\n",
            "categories/plain.md" to "# Documentation\n",
            "docs/reviewer.md" to markdown(),
            "random.md" to markdown(),
        ))
        val plan = GitRepositoryImporter().fetch(upstream.toString())
        assertEquals(1, plan.subagents.size)
        assertTrue(plan.errors.isEmpty())
    }

    @Test
    fun `bad definitions report individual paths and retain source without hiding good candidates`() {
        val invalid = mapOf(
            "agents/number.toml" to codex() + "max_depth = 2\n",
            "agents/nested.md" to markdown("permissions:\n  edit: ask\n"),
            "agents/duplicate.toml" to codex() + "model = \"other\"\n",
            "agents/unterminated.toml" to "name = \"broken\n",
            "agents/invalid.md" to markdown("tools: [\n"),
            "agents/null.md" to markdown("tools: null\n"),
        )
        val upstream = repository(invalid + ("agents/good.toml" to codex()))
        val plan = GitRepositoryImporter().fetch(upstream.toString())
        assertEquals(1, plan.subagents.size)
        assertEquals(invalid.keys, plan.errors.map { it.path }.toSet())
        plan.errors.forEach { assertEquals(invalid[it.path], it.sourceText) }
    }

    @Test
    fun `only bad definitions return reviewable file errors`() {
        val upstream = repository(mapOf("agents/bad.toml" to "model = 3\n"))
        val plan = GitRepositoryImporter().fetch(upstream.toString())
        assertTrue(plan.subagents.isEmpty())
        assertEquals("agents/bad.toml", plan.errors.single().path)
    }

    @Test
    fun `declared traversal and absolute paths are rejected before reading outside checkout`() {
        for ((index, path) in listOf("../outside", "/tmp/outside").withIndex()) {
            val upstream = repository(mapOf(
                ".claude-plugin/plugin.json" to """{"agents":"$path"}""",
                "agents/good.toml" to codex(),
            ), "upstream-$index")
            assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(upstream.toString()) }
        }
    }

    @Test
    fun `symlink Git modes including manifests are rejected before checkout`() {
        for ((index, path) in listOf(".claude-plugin/plugin.json", "agents/reviewer.toml").withIndex()) {
            val upstream = repository(mapOf(path to codex()), "upstream-$index")
            Git.open(upstream.toFile()).use { commitGitMode(it, path, FileMode.SYMLINK) }
            assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(upstream.toString()) }
        }
    }

    @Test
    fun `file count depth individual bytes and total bytes are bounded before checkout`() {
        val upstream = repository(mapOf("agents/nested/a.toml" to codex(), "README.md" to "readme"))
        val cases = listOf(
            GitImportLimits(maxFiles = 1),
            GitImportLimits(maxDepth = 2),
            GitImportLimits(maxFileBytes = 10),
            GitImportLimits(maxRepositoryBytes = codex().toByteArray().size.toLong()),
        )
        cases.forEach { limits ->
            assertFailsWith<IllegalArgumentException> { GitRepositoryImporter(limits).fetch(upstream.toString()) }
        }
    }

    @Test
    fun `oversized agent is a file error while skills remain usable`() {
        val upstream = repository(mapOf("agents/reviewer.toml" to codex(), "skills/simple/SKILL.md" to "# Simple\n"))
        val plan = GitRepositoryImporter(GitImportLimits(maxSubagentBytes = 40)).fetch(upstream.toString())
        assertEquals(1, plan.skills.size)
        assertTrue(plan.subagents.isEmpty())
        assertTrue(plan.errors.single().message.contains("40 bytes"))
    }

    @Test
    fun `URL credentials and remote schemes remain restricted`() {
        listOf("https://token@example.com/repo", "ssh://example.com/repo", "http://example.com/repo", "git@example.com:repo").forEach {
            assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(it) }
        }
    }

    @Test
    fun `repository without library definitions is rejected`() {
        val upstream = repository(mapOf("README.md" to "# Documentation\n"))
        assertFailsWith<IllegalArgumentException> { GitRepositoryImporter().fetch(upstream.toString()) }
    }

    private fun repository(files: Map<String, String>, name: String = "upstream"): Path {
        val upstream = root.resolve(name)
        Git.init().setDirectory(upstream.toFile()).call().use { git ->
            files.forEach { (path, content) ->
                val file = upstream.resolve(path)
                file.parent.createDirectories()
                file.writeText(content)
            }
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.com").call()
        }
        return upstream
    }

    private fun markdown(fields: String = ""): String =
        "---\nname: reviewer\ndescription: Reviews changes\n${fields}---\n\nReview safely.\n"

    private fun codex(): String = "name = \"backend-developer\"\n" +
        "description = \"Implement backend changes\"\nmodel = \"gpt-fixture\"\n" +
        "model_reasoning_effort = \"high\"\nsandbox_mode = \"workspace-write\"\n" +
        "developer_instructions = \"\"\"\nImplement backend changes.\nCheck data integrity.\n\"\"\"\n"
}
