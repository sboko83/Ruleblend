package dev.ruleblend.app.library

import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.SubagentVariant
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.Skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryCatalogTest {
    @Test
    fun `catalog keeps the design scale filterable without a flat entry point`() {
        val rules = (0 until 200).map { Block("rule-$it", "Rule $it", description = "Rule description $it") }
        val mcp = (0 until 30).map { Block("mcp-$it", "MCP $it", type = BlockType.MCP, content = "{}") }
        val skills = (0 until 60).map { Skill("skill-$it", "Skill $it", source = if (it % 2 == 0) null else gitSource) }
        val groups = listOf(Group(ALL_GROUP_ID, "all")) + (0 until 13).map { index ->
            Group("group-$index", "Group $index", blockIds = listOf("rule-$index"), skillIds = listOf("skill-$index"))
        }
        val scopedKey = LibraryObjectKey(LibraryObjectKind.RULE, "rule-7")
        val catalog = LibraryCatalog.build(
            blocks = rules + mcp,
            groups = groups,
            skills = skills,
            scopes = mapOf("rule-7" to "/project/seven"),
        )

        assertEquals(304, catalog.objects.size)
        assertEquals(200, catalog.count(LibraryObjectKind.RULE))
        assertEquals(listOf(scopedKey), catalog.filtered(LibraryFilters(scope = LibraryScopeFilter.PROJECT)).map { it.key })
        assertEquals(30, catalog.filtered(LibraryFilters(kinds = setOf(LibraryObjectKind.MCP))).size)
        assertEquals(30, catalog.filtered(LibraryFilters(source = LibrarySourceFilter.GIT)).size)
        assertTrue(catalog.categories(LibraryGrouping.GROUP, LibraryFilters()).any { it.key == "group:group-7" && scopedKey in it.items.map(LibraryObject::key) })
    }

    @Test
    fun `by area, the list is one section per project and one for everything global`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("m24-style", "M24 style", description = "one"),
                Block("m24-tests", "M24 tests", description = "two"),
                Block("atlas-style", "Atlas style", description = "three"),
                Block("global-style", "Global style", description = "four"),
            ),
            groups = emptyList(),
            skills = emptyList(),
            scopes = mapOf(
                "m24-style" to "/work/m24",
                "m24-tests" to "/work/m24",
                "atlas-style" to "/work/atlas",
            ),
        )

        val scoped = catalog.categories(
            LibraryGrouping.AREA,
            LibraryFilters(scope = LibraryScopeFilter.PROJECT),
        )

        assertEquals(listOf("atlas", "m24"), scoped.map { it.name })
        assertEquals(listOf("scope:/work/atlas", "scope:/work/m24"), scoped.map { it.key })
        assertEquals(
            listOf("m24-style", "m24-tests"),
            scoped.single { it.name == "m24" }.items.map { it.key.id },
        )
        // The global rule is out by the facet; without it the areas keep their order and everything
        // global follows them as one section.
        assertTrue(scoped.none { category -> category.items.any { it.key.id == "global-style" } })
        val everything = catalog.categories(LibraryGrouping.AREA, LibraryFilters())
        assertEquals(listOf("scope:/work/atlas", "scope:/work/m24", GLOBAL_SCOPE_CATEGORY), everything.map { it.key })
        assertEquals(listOf("global-style"), everything.last().items.map { it.key.id })
        // The other groupings are untouched by any of it.
        assertEquals(
            (LibraryObjectKind.entries - LibraryObjectKind.MOD).map { "type:${it.name}" },
            catalog.categories(LibraryGrouping.TYPE, LibraryFilters()).map { it.key },
        )
    }

    @Test
    fun `search and combined facets narrow name and description`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("swift-style", "Swift style", description = "iOS conventions"),
                Block("kotlin-style", "Kotlin style", description = "KMP conventions"),
            ),
            groups = emptyList(),
            skills = listOf(Skill("swift-review", "Review", description = "Swift checks")),
            scopes = emptyMap(),
        )

        assertEquals(
            listOf("swift-style"),
            catalog.filtered(
                LibraryFilters(query = "iOS", kinds = setOf(LibraryObjectKind.RULE)),
            ).map { it.key.id },
        )
        assertEquals(listOf("swift-review"), catalog.filtered(LibraryFilters(query = "swift", source = LibrarySourceFilter.LOCAL)).map { it.key.id })
    }

    @Test
    fun `available update facet keeps only skills confirmed changed by the source check`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(Block("swift-style", "Swift style")),
            groups = emptyList(),
            skills = listOf(
                Skill("current", "Current", source = gitSource),
                Skill("changed", "Changed", source = gitSource.copy(path = "skills/changed")),
                Skill("local", "Local"),
            ),
            scopes = emptyMap(),
        )

        assertEquals(
            listOf("changed"),
            catalog.filtered(
                LibraryFilters(updatesAvailable = true),
                updateSkillIds = setOf("changed"),
            ).map { it.key.id },
        )
    }

    @Test
    fun `a starred rule leads the listing and reports the star`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("plain", "Plain rule"),
                Block("starred", "Starred rule", favorite = true),
            ),
            groups = emptyList(),
            skills = emptyList(),
            scopes = emptyMap(),
        )

        val listed = catalog.filtered(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE)))
        assertEquals(listOf("starred", "plain"), listed.map { it.key.id })
        assertTrue(listed.first().favorite)
        assertTrue(!listed.last().favorite)
    }

    @Test
    fun `subagents are indexed separately and keep group membership`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block(
                    "review",
                    "Review",
                    type = BlockType.SUBAGENT,
                    variants = mapOf("claude-code" to SubagentVariant(mapOf("model" to "gpt-5.6-terra"))),
                    content = "Review changes.",
                ),
            ),
            groups = listOf(Group("quality", "Quality", blockIds = listOf("review"))),
            skills = emptyList(),
            scopes = emptyMap(),
        )

        val key = LibraryObjectKey(LibraryObjectKind.SUBAGENT, "review")
        assertEquals(listOf(key), catalog.filtered(LibraryFilters(kinds = setOf(LibraryObjectKind.SUBAGENT))).map { it.key })
        assertEquals(setOf("quality"), catalog.objects.single { it.key == key }.groupIds)
        assertEquals(listOf(key), catalog.objects.single { it.key.id == "quality" }.memberKeys)
    }

    @Test
    fun `profiles are first-class catalog objects and retain direct members plus groups`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("rule", "Rule"),
                Block("agent", "Agent", type = BlockType.SUBAGENT),
                Block("server", "Server", type = BlockType.MCP, content = "{}"),
            ),
            groups = listOf(Group("quality", "Quality", blockIds = listOf("rule"))),
            skills = listOf(Skill("review", "Review")),
            scopes = emptyMap(),
            profiles = listOf(
                Profile(
                    id = "testing",
                    name = "Testing",
                    blockIds = listOf("rule", "server"),
                    skillIds = listOf("review"),
                    subagentIds = listOf("agent"),
                    groupIds = listOf("quality"),
                ),
            ),
        )

        val profile = catalog.objects.single { it.key == LibraryObjectKey(LibraryObjectKind.PROFILE, "testing") }
        assertEquals(
            listOf(
                LibraryObjectKey(LibraryObjectKind.RULE, "rule"),
                LibraryObjectKey(LibraryObjectKind.MCP, "server"),
                LibraryObjectKey(LibraryObjectKind.SKILL, "review"),
                LibraryObjectKey(LibraryObjectKind.SUBAGENT, "agent"),
                LibraryObjectKey(LibraryObjectKind.GROUP, "quality"),
            ),
            profile.memberKeys,
        )
        assertEquals(listOf(profile.key), catalog.filtered(LibraryFilters(kinds = setOf(LibraryObjectKind.PROFILE))).map { it.key })
        assertTrue(catalog.categories(LibraryGrouping.GROUP, LibraryFilters()).any { it.key == "profiles" && profile in it.items })
    }

    @Test
    fun `facet counts answer for the facet's own value, not for the whole library`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("swift-style", "Swift style", description = "iOS conventions"),
                Block("kotlin-style", "Kotlin style", description = "KMP conventions"),
            ),
            groups = emptyList(),
            skills = listOf(
                Skill("review", "Review", source = gitSource),
                Skill("local-check", "Local check"),
            ),
            scopes = mapOf("swift-style" to "/work/atlas"),
        )

        // The combination that reported zero only after the click: rules plus local skills.
        assertEquals(0, catalog.listedCount(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), source = LibrarySourceFilter.LOCAL)))
        assertEquals(1, catalog.listedCount(LibraryFilters(kinds = setOf(LibraryObjectKind.SKILL), source = LibrarySourceFilter.LOCAL)))
        assertEquals(1, catalog.listedCount(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), scope = LibraryScopeFilter.PROJECT)))
        // Every other filter stays in play, the query included.
        assertEquals(
            1,
            catalog.listedCount(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), query = "kotlin")),
        )
        assertEquals(
            0,
            catalog.listedCount(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), query = "kotlin", scope = LibraryScopeFilter.PROJECT)),
        )
    }

    @Test
    fun `the source facet shows itself only for types it can speak about`() {
        assertTrue(sourceFacetApplies(emptySet(), typesAreDefault = false))
        assertTrue(sourceFacetApplies(setOf(LibraryObjectKind.RULE, LibraryObjectKind.SKILL), typesAreDefault = false))
        assertTrue(sourceFacetApplies(setOf(LibraryObjectKind.SUBAGENT), typesAreDefault = false))
        // The opening "rules" preset is not a choice, so the facet waits visible until one is made.
        assertTrue(sourceFacetApplies(setOf(LibraryObjectKind.RULE), typesAreDefault = true))
        assertFalse(sourceFacetApplies(setOf(LibraryObjectKind.RULE), typesAreDefault = false))
        assertFalse(sourceFacetApplies(setOf(LibraryObjectKind.MCP), typesAreDefault = false))
        assertFalse(sourceFacetApplies(setOf(LibraryObjectKind.GROUP), typesAreDefault = false))
    }

    @Test
    fun `an empty listing names the filter to lift and what lifting it restores`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(
                Block("swift-style", "Swift style"),
                Block("kotlin-style", "Kotlin style"),
            ),
            groups = emptyList(),
            skills = listOf(
                Skill("review", "Review", source = gitSource),
                Skill("local-check", "Local check"),
            ),
            scopes = emptyMap(),
        )

        val filters = LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), source = LibrarySourceFilter.LOCAL)
        assertTrue(catalog.filtered(filters).isEmpty())

        val release = catalog.widestRelease(filters)
        requireNotNull(release)
        assertEquals(LibraryFilterFacet.SOURCE, release.facet)
        assertEquals(2, release.restored)
        assertEquals(filters.copy(source = null), release.filters)
    }

    @Test
    fun `the release restores the most and prefers a facet to the query on a tie`() {
        val catalog = LibraryCatalog.build(
            blocks = listOf(Block("swift-style", "Swift style"), Block("kotlin-style", "Kotlin style")),
            groups = emptyList(),
            skills = listOf(Skill("review", "Review", source = gitSource)),
            scopes = emptyMap(),
        )

        // Only the query mentions the skill, so lifting kinds restores less than lifting the query.
        val queryWins = catalog.widestRelease(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), query = "review"))
        requireNotNull(queryWins)
        assertEquals(LibraryFilterFacet.QUERY, queryWins.facet)
        assertEquals(2, queryWins.restored)

        // A tie between the rule facet and the query: dropping the facet keeps what was typed.
        val tie = catalog.widestRelease(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), query = "style"))
        requireNotNull(tie)
        assertEquals(LibraryFilterFacet.KIND, tie.facet)
        assertEquals(LibraryObjectKind.RULE, tie.kind)
        assertEquals(2, tie.restored)
    }

    @Test
    fun `no release when no single filter lift restores anything`() {
        val empty = LibraryCatalog.build(blocks = emptyList(), groups = emptyList(), skills = emptyList(), scopes = emptyMap())
        assertNull(empty.widestRelease(LibraryFilters(kinds = setOf(LibraryObjectKind.RULE))))

        // Two filters that only clear together: lifting either one alone still leaves nothing.
        val catalog = LibraryCatalog.build(
            blocks = listOf(Block("rule", "Rule")),
            groups = emptyList(),
            skills = emptyList(),
            scopes = emptyMap(),
        )
        assertNull(
            catalog.widestRelease(
                LibraryFilters(kinds = setOf(LibraryObjectKind.RULE), scope = LibraryScopeFilter.PROJECT, source = LibrarySourceFilter.LOCAL),
            ),
        )
    }

    private val gitSource = dev.ruleblend.core.model.GitSkillSource("https://example.com/skills", "abc", "skills/example")
}
