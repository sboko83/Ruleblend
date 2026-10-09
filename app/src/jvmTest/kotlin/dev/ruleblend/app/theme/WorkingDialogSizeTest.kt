package dev.ruleblend.app.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import dev.ruleblend.core.config.ThemeMode
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * A dialog that carries a working surface — the splitter, the file editor, the adopt sheet — is
 * measured off the window, and never shrinks past the point where two panes stop being readable.
 */
class WorkingDialogSizeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a working dialog never opens narrower than the floor, whatever share it asks for`() {
        var share by mutableStateOf(0.9f)
        var size = DpSize.Zero
        var window = DpSize.Zero
        compose.setContent {
            RuleblendTheme(ThemeMode.DARK) {
                val container = LocalWindowInfo.current.containerSize
                window = with(LocalDensity.current) { DpSize(container.width.toDp(), container.height.toDp()) }
                size = workingDialogSize(share, share)
            }
        }

        compose.runOnIdle {
            assertTrue(size.width > window.width * 0.85f, "a 0.9 share gave ${size.width} of ${window.width}")
        }
        // Asking for a strip is not honoured: the floor is what makes a two-pane dialog readable.
        compose.runOnIdle { share = 0.2f }
        compose.runOnIdle {
            assertTrue(
                size.width >= window.width * 0.7f,
                "${size.width} is under 70% of a ${window.width} window",
            )
            assertTrue(
                size.height >= window.height * 0.8f,
                "${size.height} is under 80% of a ${window.height} window",
            )
        }
    }
}
