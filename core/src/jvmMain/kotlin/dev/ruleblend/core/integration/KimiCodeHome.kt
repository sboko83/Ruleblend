package dev.ruleblend.core.integration

import java.nio.file.Path

/**
 * Where Kimi Code keeps its files. Resolved once, here, for the adapter, the MCP installer, the
 * connector and the skill installer, so the `KIMI_CODE_HOME` rule is written in one place.
 */
class KimiCodeHome private constructor(
    /** Kimi's own directory: `$KIMI_CODE_HOME` when set, else `.kimi-code` under the OS home. */
    val directory: Path,
    /** The operating-system home; `.agents/skills` under it is the skill directory Kimi shares. */
    val userHome: Path,
) {
    companion object {
        /** The user's real Kimi directory, honouring `KIMI_CODE_HOME`. */
        fun current(paths: AssistantPaths = AssistantPaths.forHome()): KimiCodeHome =
            KimiCodeHome(paths.kimi, paths.userHome)

        /**
         * Kimi's directory as `.kimi-code` under [home], ignoring the variable: a test or an
         * integration pointing at a temporary home must never reach the user's real Kimi files.
         */
        fun under(home: Path): KimiCodeHome = KimiCodeHome(home.resolve(".kimi-code"), home)
    }
}
