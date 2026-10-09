package dev.ruleblend.core.integration

/**
 * What removing an installed copy by id alone did, when the library object it came from is gone.
 *
 * Deleting an object from the library leaves its copies standing in every project that held one,
 * and the ordinary remove path cannot take them out: each service decides whether it may touch a
 * file by comparing it with the library body, and there is none left to compare with. The copy
 * Ruleblend recorded when it installed is the remaining reference, so an id-only removal decides
 * protection against that record instead — the guarantee is unchanged, only its yardstick moves.
 */
enum class OrphanRemoval {
    /** The copy still matched what Ruleblend recorded, and is gone together with that record. */
    REMOVED,

    /** Nothing of that id was installed here; a record left describing it, if any, was cleared. */
    ABSENT,

    /** The copy was hand-edited or foreign, and stays where it is. */
    PROTECTED,
}
