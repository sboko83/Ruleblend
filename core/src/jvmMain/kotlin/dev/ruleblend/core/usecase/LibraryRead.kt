package dev.ruleblend.core.usecase

import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.storage.LibraryRepository

/**
 * The one read of the library every use case hands back. Shared so a snapshot always carries the
 * same library state: a use case that omitted one collection would have a caller overwrite state with a
 * field that was never read.
 */
internal fun readLibrary(repository: LibraryRepository, configStore: ConfigStore): LibrarySnapshot =
    LibrarySnapshot(
        blocks = repository.listBlocks(),
        groups = repository.listGroups(),
        profiles = repository.listProfiles(),
        skills = repository.listSkills(),
        ruleScopes = configStore.load().ruleScopes,
    )
