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
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode

class GitSkillImporterTest {
    private lateinit var root: Path
    private lateinit var upstream: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-git-skills")
        upstream = root.resolve("upstream")
        Git.init().setDirectory(upstream.toFile()).call().use { git ->
            upstream.resolve(".codex-plugin").createDirectories()
            upstream.resolve(".codex-plugin/plugin.json").writeText(
                """{"name":"fixture","version":"2.4.0","skills":"./agent-skills/"}""",
            )
            upstream.resolve("agent-skills/review/references").createDirectories()
            upstream.resolve("agent-skills/review/SKILL.md").writeText(
                "---\nname: code-review\ndescription: Review changes safely\n---\n\n# Review\n",
            )
            upstream.resolve("agent-skills/review/references/checklist.md").writeText("Check tests.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.com").call()
        }
    }

    @AfterTest
    fun tearDown() {
        deleteFixtureTree(root)
    }

    @Test
    fun `discovers codex skills and captures repository version and revision`() {
        val plan = GitSkillImporter().fetch(upstream.toString())

        assertEquals("2.4.0", plan.version)
        assertEquals(40, plan.revision.length)
        val snapshot = plan.skills.single()
        assertEquals("code-review", snapshot.skill.name)
        assertEquals("Review changes safely", snapshot.skill.description)
        assertEquals("agent-skills/review", snapshot.skill.source?.path)
        assertEquals(setOf("SKILL.md", "references/checklist.md"), snapshot.files.map { it.path }.toSet())
    }

    @Test
    fun `captures executable intent from Git even on a non POSIX checkout`() {
        Git.open(upstream.toFile()).use { git ->
            commitGitMode(git, "agent-skills/review/references/checklist.md", FileMode.EXECUTABLE_FILE)
        }
        val files = GitSkillImporter().fetch(upstream.toString()).skills.single().files
        assertEquals(setOf("references/checklist.md"), files.filter { it.executable }.map { it.path }.toSet())
        assertEquals("Check tests.\n", files.single { it.path == "references/checklist.md" }.bytes.decodeToString())
    }

    @Test
    fun `rejects a Git symlink even if the checkout materializes it as a plain file`() {
        Git.open(upstream.toFile()).use { git ->
            commitGitMode(git, "agent-skills/review/references/checklist.md", FileMode.SYMLINK)
        }
        assertFailsWith<IllegalArgumentException> { GitSkillImporter().fetch(upstream.toString()) }
    }

    @Test
    fun `rejects a manifest path outside the repository`() {
        upstream.resolve(".codex-plugin/plugin.json").writeText(
            """{"name":"fixture","version":"2.4.0","skills":"../outside"}""",
        )
        Git.open(upstream.toFile()).use { git ->
            git.add().addFilepattern(".").call()
            git.commit().setMessage("bad path").setAuthor("Test", "test@example.com").call()
        }

        assertFailsWith<IllegalArgumentException> { GitSkillImporter().fetch(upstream.toString()) }
    }

    @Test
    fun `rejects credentials embedded in repository url`() {
        assertFailsWith<IllegalArgumentException> {
            GitSkillImporter().fetch("https://token@github.com/owner/repository")
        }
    }

    @Test
    fun `falls back to short git revision when no manifest declares a version`() {
        upstream.resolve(".codex-plugin/plugin.json").toFile().delete()
        upstream.resolve("skills/basic").createDirectories()
        upstream.resolve("skills/basic/SKILL.md").writeText("# Basic\n")
        Git.open(upstream.toFile()).use { git ->
            git.rm().addFilepattern(".codex-plugin/plugin.json").call()
            git.add().addFilepattern("skills").call()
            git.commit().setMessage("manifestless").setAuthor("Test", "test@example.com").call()
        }

        val plan = GitSkillImporter().fetch(upstream.toString())

        assertEquals(plan.revision.take(12), plan.version)
        assertTrue(plan.skills.any { it.skill.name == "basic" })
    }

    @Test
    fun `a version declared in SKILL md outranks the manifest and the revision`() {
        upstream.resolve("skills/top").createDirectories()
        upstream.resolve("skills/top/SKILL.md").writeText("---\nname: top\nversion: 3.1.0\n---\n# Top\n")
        upstream.resolve("skills/meta").createDirectories()
        upstream.resolve("skills/meta/SKILL.md").writeText("---\nname: meta\nmetadata:\n  author: me\n  version: \"1.2\"\n---\n# Meta\n")
        upstream.resolve("skills/plain").createDirectories()
        upstream.resolve("skills/plain/SKILL.md").writeText("---\nname: plain\n---\n# Plain\n")
        upstream.resolve(".codex-plugin/plugin.json").toFile().delete()
        Git.open(upstream.toFile()).use { git ->
            git.rm().addFilepattern(".codex-plugin/plugin.json").call()
            git.add().addFilepattern("skills").call()
            git.commit().setMessage("declared versions").setAuthor("Test", "test@example.com").call()
        }

        val plan = GitSkillImporter().fetch(upstream.toString())
        val versions = plan.skills.associate { it.skill.name to it.skill.version }

        assertEquals("3.1.0", versions["top"])
        assertEquals("1.2", versions["meta"])
        assertEquals(plan.revision.take(12), versions["plain"])
    }

    @Test
    fun `uses claude manifest version with the conventional skills directory`() {
        upstream.resolve(".codex-plugin/plugin.json").toFile().delete()
        upstream.resolve(".claude-plugin").createDirectories()
        upstream.resolve(".claude-plugin/plugin.json").writeText("""{"name":"fixture","version":"3.1.0"}""")
        upstream.resolve("skills/claude-skill").createDirectories()
        upstream.resolve("skills/claude-skill/SKILL.md").writeText("# Claude skill\n")
        Git.open(upstream.toFile()).use { git ->
            git.rm().addFilepattern(".codex-plugin/plugin.json").call()
            git.add().addFilepattern(".").call()
            git.commit().setMessage("claude layout").setAuthor("Test", "test@example.com").call()
        }

        val plan = GitSkillImporter().fetch(upstream.toString())

        assertEquals("3.1.0", plan.version)
        assertTrue(plan.skills.any { it.skill.source?.path == "skills/claude-skill" })
    }
}
