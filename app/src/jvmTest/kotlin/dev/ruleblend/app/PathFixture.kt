package dev.ruleblend.app

import dev.ruleblend.core.config.projectKey
import java.nio.file.Path

/** Synthetic project addresses use the same native identity as persisted project settings. */
internal fun fixturePath(path: String): String = Path.of(path).projectKey()
