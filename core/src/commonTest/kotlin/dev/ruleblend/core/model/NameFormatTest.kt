package dev.ruleblend.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class NameFormatTest {

    @Test
    fun `kebab lowercases and dashes words`() {
        assertEquals("my-rule-name", formatName("My Rule Name", NameFormat.KEBAB))
        assertEquals("git-no-commit", formatName("Git: no  commit!", NameFormat.KEBAB))
    }

    @Test
    fun `camel joins words with lower first`() {
        assertEquals("myRuleName", formatName("My Rule Name", NameFormat.CAMEL))
        assertEquals("gitNoCommit", formatName("git no commit", NameFormat.CAMEL))
    }

    @Test
    fun `snake lowercases and underscores words`() {
        assertEquals("my_rule_name", formatName("My Rule Name", NameFormat.SNAKE))
        assertEquals("git_no_commit", formatName("Git: no  commit!", NameFormat.SNAKE))
    }

    @Test
    fun `free only trims`() {
        assertEquals("My Rule Name", formatName("  My Rule Name  ", NameFormat.FREE))
    }

    @Test
    fun `keeps non-ascii letters`() {
        assertEquals("мои-правила", formatName("Мои правила", NameFormat.KEBAB))
    }

    @Test
    fun `name without usable characters falls back to trimmed input`() {
        assertEquals("---", formatName("---", NameFormat.KEBAB))
        assertEquals("", formatName("   ", NameFormat.CAMEL))
    }
}
