package dev.ruleblend.mcp.skill

import dev.ruleblend.core.model.parseSkillFrontmatter
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundledSkillTest {

    @Test
    fun `the bundled text states its own version`() {
        assertTrue(BundledSkill.version > 0)
        assertEquals(BundledSkill.version, BundledSkill.versionIn(BundledSkill.text))
    }

    @Test
    fun `a copy written before versions existed is ours, at version zero`() {
        val text = "# Anything\n\n<!-- ruleblend-managed skill -->\n"

        assertTrue(BundledSkill.isOwned(text))
        assertEquals(0, BundledSkill.versionIn(text))
    }

    @Test
    fun `an unreadable version still marks the copy as ours`() {
        val text = "# Anything\n\n<!-- ruleblend-managed skill vNext -->\n"

        assertTrue(BundledSkill.isOwned(text))
        assertEquals(0, BundledSkill.versionIn(text))
    }

    @Test
    fun `a file without the marker belongs to someone else`() {
        val text = "---\nname: ruleblend\n---\n\n# Someone else's skill\n"

        assertFalse(BundledSkill.isOwned(text))
        assertNull(BundledSkill.versionIn(text))
    }

    @Test
    fun `the last marker wins, so a quoted one in the body cannot lower the version`() {
        val text = "Docs quote `<!-- ruleblend-managed skill v1 -->`.\n\n<!-- ruleblend-managed skill v7 -->\n"

        assertEquals(7, BundledSkill.versionIn(text))
    }

    @Test
    fun `the frontmatter description is what the library shows`() {
        val frontmatter = parseSkillFrontmatter(BundledSkill.text)
        assertEquals(BundledSkill.NAME, frontmatter.name)
        assertEquals(BundledSkill.description, frontmatter.description)
        assertTrue(BundledSkill.description.startsWith("Manage the user's persistent agent rules"))
    }

    /**
     * The version is what tells every agent its copy is stale, and nothing derives it from the text.
     * This pins the two together: editing `SKILL.md` fails here until the marker version is raised
     * and this digest is replaced with the one the failure prints.
     */
    @Test
    fun `the bundled text matches the version it ships under`() {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(BundledSkill.text.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertEquals(
            EXPECTED_DIGEST,
            digest,
            "SKILL.md changed: raise the version in its marker line and put this digest here — $digest",
        )
    }

    private companion object {
        const val EXPECTED_DIGEST = "6995f4a15eec9a7d9c651687752c94324508352d9a6bf53633fbc18d3b079d78"
    }
}
