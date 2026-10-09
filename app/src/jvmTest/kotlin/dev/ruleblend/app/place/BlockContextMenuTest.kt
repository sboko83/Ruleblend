package dev.ruleblend.app.place

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.library.LibraryObjectKind
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.ClipboardEnvironment
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.integration.InstallStatus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockContextMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `expanded installed text copies local content and edits its library object`() {
        val text = "## Local heading\n\nLocally edited body."
        var edits = 0
        var copied: String? = null
        val previousCopy = ClipboardEnvironment.copy
        ClipboardEnvironment.copy = { copied = it; true }
        try {
            compose.setContent {
                RuleblendTheme(ThemeMode.LIGHT) {
                    BlockRow(
                        row = PlaceBlockRow("rule", "Rule", "1", null, InstallStatus.SYNCED,
                            content = text, libraryContent = "Different library body."),
                        kind = LibraryObjectKind.RULE,
                        strings = EnStrings,
                        settled = false,
                        handle = null,
                        unfolded = true,
                        onToggleFold = {},
                        onAction = {},
                        onEdit = { edits++ },
                    )
                }
            }
            compose.onNodeWithText(text).performMouseInput { rightClick() }
            compose.onNodeWithText(EnStrings.setCopy).performClick()
            assertEquals(text, copied)
            compose.onNodeWithText(text).performMouseInput { rightClick() }
            compose.onNodeWithText(EnStrings.libEditFocus).performClick()
            assertEquals(1, edits)
        } finally {
            ClipboardEnvironment.copy = previousCopy
        }
    }
}
