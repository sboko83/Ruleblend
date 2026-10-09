package dev.ruleblend.app.command

import dev.ruleblend.app.library.LibraryCatalog
import dev.ruleblend.app.library.LibraryObjectKey
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.navigation.AppRoute
import dev.ruleblend.app.place.PlaceSection

/**
 * What a palette row is. The declaration order is also the tie-break order between rows that matched
 * a query equally well: an action is what the user asked for literally, a place is one of thirty,
 * and a library object is one of three hundred, so the rarer answer is offered first.
 */
enum class CommandKind { ACTION, PLACE, RULE, SUBAGENT, SKILL, MCP, GROUP, PROFILE }

/** Where a row leads. The palette never writes: every target is a selection on an existing surface. */
sealed interface CommandTarget {
    data class Library(val key: LibraryObjectKey) : CommandTarget

    data class Place(val id: String) : CommandTarget

    data class Surface(val route: AppRoute) : CommandTarget
}

data class CommandItem(
    val id: String,
    val kind: CommandKind,
    val title: String,
    val subtitle: String = "",
    val target: CommandTarget,
)

/** A query split into its optional scope prefix and the rest of the text. */
data class CommandQuery(val scope: CommandKind?, val text: String)

private val SCOPE_PREFIXES = mapOf(
    'r' to CommandKind.RULE,
    'a' to CommandKind.SUBAGENT,
    's' to CommandKind.SKILL,
    'm' to CommandKind.MCP,
    'p' to CommandKind.PLACE,
)

/** Characters after which a match still counts as the start of a word, not a fragment inside one. */
private const val WORD_SEPARATORS = " -_./:"

/**
 * `r:` rules, `s:` skills, `m:` mcp, `p:` places. Only a single letter directly followed by a colon
 * is a scope: `mcp: foo` is a plain search, because dropping the `cp` would silently search for
 * something the user did not type.
 */
fun parseCommandQuery(raw: String): CommandQuery {
    val trimmed = raw.trimStart()
    val scope = trimmed.getOrNull(1)?.takeIf { it == ':' }?.let { SCOPE_PREFIXES[trimmed[0].lowercaseChar()] }
    return if (scope == null) CommandQuery(null, raw.trim()) else CommandQuery(scope, trimmed.drop(2).trim())
}

/**
 * The searchable index, built from read-models the surfaces already produce — the palette adds no
 * scan and no aggregate of its own.
 *
 * [actions] come first and are localized by the caller. Places keep the order of the Place list, so
 * an empty query answers with pinned and recent places instead of an alphabetical wall; a place
 * listed twice there (pinned and again in its set) is one row here.
 */
fun commandIndex(
    catalog: LibraryCatalog,
    places: List<PlaceSection>,
    actions: List<CommandItem> = emptyList(),
): List<CommandItem> = buildList {
    addAll(actions)
    val seen = mutableSetOf<String>()
    places.flatMap { it.places }.forEach { place ->
        if (seen.add(place.id)) {
            add(CommandItem(place.id, CommandKind.PLACE, place.name, place.id, CommandTarget.Place(place.id)))
        }
    }
    catalog.objects.filter { it.key.kind != LibraryObjectKind.PROFILE }.forEach { item ->
        add(
            CommandItem(
                id = "${item.key.kind}:${item.key.id}",
                kind = when (item.key.kind) {
                    LibraryObjectKind.RULE -> CommandKind.RULE
                    LibraryObjectKind.SUBAGENT -> CommandKind.SUBAGENT
                    LibraryObjectKind.SKILL -> CommandKind.SKILL
                    LibraryObjectKind.MCP -> CommandKind.MCP
                    LibraryObjectKind.GROUP -> CommandKind.GROUP
                    LibraryObjectKind.PROFILE -> CommandKind.PROFILE
                },
                title = item.name,
                subtitle = item.description,
                target = CommandTarget.Library(item.key),
            ),
        )
    }
}

/**
 * How well [item] answers [text]: lower is better, `null` means it does not answer it at all. The
 * title outranks the description, and the start of a word outranks a fragment inside one — typing
 * `git` should reach the rule named "git safety" before every rule that merely mentions git.
 */
private fun score(item: CommandItem, text: String): Int? {
    val title = item.title.lowercase()
    return when {
        title == text -> 0
        title.startsWith(text) -> 1
        title.indices.any { it > 0 && title[it - 1] in WORD_SEPARATORS && title.startsWith(text, it) } -> 2
        text in title -> 3
        text in item.subtitle.lowercase() -> 4
        else -> null
    }
}

/**
 * The visible rows for [raw]. A scope drops everything of another kind, actions included: `p:` is a
 * request for places and nothing else. An empty query is not a search but the resting state of the
 * palette, so it shows the head of the index rather than nothing.
 */
fun rankCommands(index: List<CommandItem>, raw: String, limit: Int = 8): List<CommandItem> {
    val query = parseCommandQuery(raw)
    val pool = if (query.scope == null) index else index.filter { it.kind == query.scope }
    val text = query.text.lowercase()
    if (text.isEmpty()) return pool.take(limit)
    return pool.mapNotNull { item -> score(item, text)?.let { it to item } }
        .sortedWith(compareBy({ (score, _) -> score }, { (_, item) -> item.kind.ordinal }))
        .take(limit)
        .map { (_, item) -> item }
}
