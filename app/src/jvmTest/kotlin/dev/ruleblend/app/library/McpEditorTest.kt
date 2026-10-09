package dev.ruleblend.app.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.BlockType
import dev.ruleblend.core.model.McpConfigCodec
import dev.ruleblend.core.model.McpServerConfig
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

class McpEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun duplicateKeysBlockOnlyTheActiveTransport() {
        var draft by mutableStateOf(
            Block(
                id = "server",
                name = "Server",
                type = BlockType.MCP,
                content = McpConfigCodec.serialize(McpServerConfig.Stdio(command = "/bin/echo")),
            ),
        )
        var valid = false
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                McpConfigForm(draft, { edit -> draft = edit(draft) }, onFormValidChange = { valid = it })
            }
        }
        compose.waitForIdle()
        assertTrue(valid)
        compose.onNodeWithTag("mcp-add-env").performClick()
        compose.onNodeWithTag("mcp-env-key-0").performTextInput("KEY")
        compose.onNodeWithTag("mcp-env-value-0").performTextInput("one")
        compose.onNodeWithTag("mcp-add-env").performClick()
        compose.onNodeWithTag("mcp-env-key-1").performTextInput("KEY")
        compose.onNodeWithTag("mcp-env-value-1").performTextInput("two")
        compose.waitForIdle()
        assertFalse(valid)

        compose.onNodeWithTag("mcp-http").performClick()
        compose.onNodeWithTag("mcp-url").performTextInput("https://example.invalid/mcp")
        compose.waitForIdle()
        assertTrue(valid)
        compose.onNodeWithTag("mcp-add-headers").performClick()
        compose.onNodeWithTag("mcp-headers-key-0").performTextInput("X-Key")
        compose.onNodeWithTag("mcp-headers-value-0").performTextInput("one")
        compose.onNodeWithTag("mcp-add-headers").performClick()
        compose.onNodeWithTag("mcp-headers-key-1").performTextInput("X-Key")
        compose.onNodeWithTag("mcp-headers-value-1").performTextInput("two")
        compose.waitForIdle()
        assertFalse(valid)

        compose.onNodeWithTag("mcp-stdio").performClick()
        compose.waitForIdle()
        assertFalse(valid)
        compose.onNodeWithTag("mcp-env-key-1").performTextReplacement("OTHER")
        compose.waitForIdle()
        assertTrue(valid)
    }
}
