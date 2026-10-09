package dev.ruleblend.core.storage

import dev.ruleblend.core.integration.IMPORT_REGION_ID
import dev.ruleblend.core.integration.LEGACY_IMPORT_REGION_IDS
import dev.ruleblend.core.model.isPortableFileName

private val LIBRARY_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]*")

internal val RESERVED_BLOCK_IDS = LEGACY_IMPORT_REGION_IDS + IMPORT_REGION_ID

/** IDs are single portable file names, even when supplied by an archive or an MCP caller. */
internal fun checkedLibraryId(id: String): String {
    require(LIBRARY_ID.matches(id) && isPortableFileName(id)) { "Invalid library object id: $id" }
    return id
}

internal fun checkedWritableBlockId(id: String): String {
    checkedLibraryId(id)
    require(RESERVED_BLOCK_IDS.none { it.equals(id, ignoreCase = true) }) { "Block id '$id' is reserved for instruction imports" }
    return id
}
