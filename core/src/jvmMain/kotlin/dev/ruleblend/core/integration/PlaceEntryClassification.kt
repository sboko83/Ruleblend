package dev.ruleblend.core.integration

/** Whether an entry found at a place belongs to Ruleblend and still has a library object. */
enum class PlaceEntryOrigin {
    MANAGED,
    ORPHAN,
    FOREIGN,
    IGNORED,
}

/** The sidecar's ownership link for one entry at a place. */
data class PlaceSidecarEntry<Key>(
    val key: Key,
    val libraryId: String,
)

/** A disk entry together with the ownership state derived from its sidecar and library object. */
data class PlaceEntry<Value, Key>(
    val value: Value,
    val key: Key,
    val libraryId: String,
    val origin: PlaceEntryOrigin,
)

/**
 * Classifies entries discovered on disk without knowing whether they are skills, MCP servers, or
 * another place object. A sidecar link takes precedence over an ignored key: ignored entries have
 * no Ruleblend ownership record, while an existing record remains managed or orphaned.
 */
fun <Value, Key> classifyPlaceEntries(
    entries: Iterable<Value>,
    sidecarEntries: Iterable<PlaceSidecarEntry<Key>>,
    libraryIds: Set<String>,
    ignoredKeys: Set<Key> = emptySet(),
    keyOf: (Value) -> Key,
    libraryIdOf: (Value) -> String,
): List<PlaceEntry<Value, Key>> {
    val sidecarsByKey = sidecarEntries.associateBy { it.key }
    return entries.map { value ->
        val key = keyOf(value)
        val sidecar = sidecarsByKey[key]
        val libraryId = sidecar?.libraryId ?: libraryIdOf(value)
        val origin = when {
            sidecar != null && libraryId in libraryIds -> PlaceEntryOrigin.MANAGED
            sidecar != null -> PlaceEntryOrigin.ORPHAN
            key in ignoredKeys -> PlaceEntryOrigin.IGNORED
            else -> PlaceEntryOrigin.FOREIGN
        }
        PlaceEntry(value, key, libraryId, origin)
    }
}
