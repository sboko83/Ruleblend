package dev.ruleblend.app.util

import java.nio.file.Path
import dev.ruleblend.core.integration.PathDisplay

private val USER_HOME: Path = Path.of(System.getProperty("user.home"))

/** Home-abbreviated address with the current platform's native separators. */
fun Path.abbreviateHome(home: Path = USER_HOME): String = PathDisplay.shorten(this, home)
