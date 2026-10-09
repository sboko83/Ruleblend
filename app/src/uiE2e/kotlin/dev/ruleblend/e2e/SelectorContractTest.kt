package dev.ruleblend.e2e

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Driver contract fixture, deliberately not a replacement for the root application scenarios. */
class SelectorContractTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun H04_duplicateLabelsOffscreenRowsAndDialogStayDistinct() {
        val recent = uiTarget("rule", "shared", "recent", "select")
        val canonical = uiTarget("rule", "shared", "library", "select")
        val confirm = uiTarget("rule", "shared", "dialog", "confirm")
        var selected = ""
        var dialog by mutableStateOf(false)
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                Column {
                    Text("Same", Modifier.testTag(recent).clickable { selected = "recent" })
                    LazyColumn(Modifier.height(120.dp).testTag("selector-list")) {
                        items((0..60).toList()) { index ->
                            val tag = if (index == 60) canonical else uiTarget("rule", "$index", "library", "select")
                            Text("Same", Modifier.height(32.dp).testTag(tag).clickable {
                                selected = "library:$index"
                                dialog = true
                            })
                        }
                    }
                    if (dialog) Dialog(onDismissRequest = { dialog = false }) {
                        Text("Same", Modifier.testTag(confirm).clickable { selected = "confirmed"; dialog = false })
                    }
                }
            }
        }
        assertFailsWith<AssertionError> { compose.onNodeWithText("Same").performClick() }
        assertEquals("", selected)
        compose.onNodeWithTag(recent).performClick()
        assertEquals("recent", selected)
        compose.onNodeWithTag("selector-list").performScrollToNode(hasTestTag(canonical))
        compose.onNodeWithTag(canonical).assertIsDisplayed().performClick()
        assertEquals("library:60", selected)
        compose.onNodeWithTag(confirm).performClick()
        assertEquals("confirmed", selected)
        assertNotEquals(uiTarget("rule", "a|b", "c", "select"), uiTarget("rule", "a", "b|c", "select"))
    }
}
