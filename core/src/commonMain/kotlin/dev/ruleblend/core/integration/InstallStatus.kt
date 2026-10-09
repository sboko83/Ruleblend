package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.headingLine

/** State of an installed block relative to the library version. */
enum class InstallStatus {
    /** Content matches the library block. */
    SYNCED,

    /** The library holds a different version; the region is untouched and can be updated. */
    UPDATE_AVAILABLE,

    /** The region was edited outside Ruleblend; never overwrite without asking. */
    MODIFIED,
}

/**
 * The region Ruleblend writes for [block], optionally tagged with [group].
 * Trailing blank lines of the block body are dropped: the markers already delimit the region,
 * so keeping them would only pad the target file.
 *
 * A block with a heading renders it as the region's first line, blank line, then the body — the
 * heading is part of the region's content, so its bytes are covered by the hash and by the run
 * manifest's lengths like any other text.
 */
fun regionFor(block: Block, group: String? = null, origin: String? = group?.let { "g=$it" }): ManagedRegion {
    val body = block.content.trimEnd('\n')
    val heading = block.headingLine()
    val content = when {
        heading.isEmpty() -> body
        body.isBlank() -> heading
        else -> "$heading\n\n${body.trimStart('\n')}"
    }
    return ManagedRegion(
        id = block.id,
        version = block.version,
        group = group,
        hash = hashContent(content),
        content = content,
        origin = origin,
    )
}

/** Compares an installed [region] against the library [block]. */
fun statusOf(region: ManagedRegion, block: Block): InstallStatus {
    val expected = regionFor(block, region.group, region.origin)
    return when {
        region.hash != hashContent(region.content) -> InstallStatus.MODIFIED
        region.version == block.version && region.content != expected.content -> InstallStatus.MODIFIED
        region.version != block.version -> InstallStatus.UPDATE_AVAILABLE
        region.content != expected.content -> InstallStatus.UPDATE_AVAILABLE
        else -> InstallStatus.SYNCED
    }
}
