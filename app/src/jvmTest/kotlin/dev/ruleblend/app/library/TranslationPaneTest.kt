package dev.ruleblend.app.library

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.config.TranslationConfig
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.Block
import dev.ruleblend.core.storage.LibraryRepository
import dev.ruleblend.core.translate.TranslationError
import dev.ruleblend.core.translate.TranslationException
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.TranslationStatus
import dev.ruleblend.core.translate.Translator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import kotlinx.coroutines.runBlocking

/**
 * The panel against a scripted translator: no helper process, no language packs, so the behaviour
 * under test is the app's own — when the panel appears, what it shows, and what Replace writes.
 */
class TranslationPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: Path
    private lateinit var model: LibraryModel
    private lateinit var configStore: ConfigStore

    /** Uppercases prose so a translation is recognisable, or fails on demand. */
    private class FakeTranslator(
        override val available: Boolean = true,
        val failWith: TranslationError? = null,
        private var transientFailures: Int = 0,
    ) : Translator {
        var seen: List<String> = emptyList()
        var attempts: Int = 0

        override suspend fun status(from: String, to: String, quality: TranslationQuality) =
            TranslationStatus.INSTALLED

        override suspend fun languages(quality: TranslationQuality) = listOf("en", "ru")

        override suspend fun translate(
            texts: List<String>,
            from: String,
            to: String,
            quality: TranslationQuality,
        ): List<String> {
            attempts++
            if (transientFailures > 0) {
                transientFailures--
                throw TranslationException(TranslationError.FAILED)
            }
            failWith?.let { throw TranslationException(it) }
            seen = texts
            return texts.map { it.uppercase() }
        }
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("ruleblend-translation-pane")
        val repository = LibraryRepository(root.resolve("library"))
        repository.init()
        repository.writeBlock(
            Block(
                id = "swift-style",
                name = "Swift Style",
                description = "iOS conventions",
                content = "Prefer value types.\n\n```swift\nlet x = 1\n```\n",
            )
        )
        repository.syncAllGroup()
        configStore = ConfigStore(root.resolve("config.json"))
        model = LibraryModel(
            repository = repository,
            archive = LibraryArchive(root.resolve("library"), repository),
            configStore = configStore,
            usageSource = { _, _ -> emptyList() },
        ).also { m -> runBlocking { m.load() } }
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun openEditor(translator: Translator): TranslationModel {
        // Unconfined keeps the scripted translation synchronous; the app supplies the UI scope.
        val translation = TranslationModel(
            translator,
            configStore,
            CoroutineScope(Dispatchers.Unconfined),
            transientRetryDelayMillis = 0,
        )
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                LibraryScreen(model, translation = translation)
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Swift")
        // The name also appears in the search suggestions, so the catalog row is picked explicitly.
        compose.onAllNodesWithText("Swift Style").onFirst().performClick()
        compose.onNodeWithContentDescription("Edit").performClick()
        return translation
    }

    @Test
    fun `translation switched off in Settings offers nothing in the editor`() {
        configStore.save(configStore.load().copy(translation = TranslationConfig(enabled = false)))
        openEditor(FakeTranslator())

        compose.onAllNodesWithContentDescription("Translate").assertCountEquals(0)
    }

    @Test
    fun `the panel translates prose and leaves code alone`() {
        val translator = FakeTranslator()
        openEditor(translator)

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Prefer value types."), translator.seen, "only prose should be sent")
        compose.onNodeWithText("PREFER VALUE TYPES.", substring = true).assertExists()
        // Once in the editor, once in the translation: the fence came back verbatim on both sides.
        compose.onAllNodesWithText("let x = 1", substring = true).assertCountEquals(2)
    }

    @Test
    fun `the editor reads paragraph against paragraph while the translation is open`() {
        openEditor(FakeTranslator())

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Original").assertExists()
        compose.onNodeWithText("Translation EN → RU").assertExists()
        // The field gave way to the reading surface, so the paragraph pairing cannot drift under a
        // keystroke; the note says where editing went.
        compose.onNodeWithText("Reading mode: hide the translation to edit the text.").assertExists()
        compose.onNodeWithText("Prefer value types.").assertExists()
    }

    @Test
    fun `hiding the translation gives the field back`() {
        openEditor(FakeTranslator())

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide translation").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Original").assertDoesNotExist()
        compose.onNodeWithText("Prefer value types.", substring = true).performTextInput("x")
        compose.waitForIdle()
        assertEquals(true, model.draft?.content?.startsWith("x"), "the field takes typing again")
    }

    @Test
    fun `Replace original writes the translation into the draft`() {
        openEditor(FakeTranslator())

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Replace original").performClick()
        compose.waitForIdle()

        assertEquals(
            "PREFER VALUE TYPES.\n\n```swift\nlet x = 1\n```\n",
            model.draft?.content,
            "code must survive the round trip untouched",
        )
    }

    @Test
    fun `a missing language pack is explained instead of failing silently`() {
        openEditor(FakeTranslator(failWith = TranslationError.PACK_MISSING))

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The language pack is not downloaded yet.").assertExists()
        compose.onNodeWithText("Open System Settings").assertExists()
    }

    @Test
    fun `a transient first failure is retried before the panel shows an error`() {
        val translator = FakeTranslator(transientFailures = 1)
        openEditor(translator)

        compose.onNodeWithContentDescription("Translate").performClick()
        compose.waitForIdle()

        assertEquals(2, translator.attempts)
        compose.onNodeWithText("PREFER VALUE TYPES.", substring = true).assertExists()
        compose.onNodeWithText("Could not translate.").assertDoesNotExist()
    }

    @Test
    fun `no translator means no panel`() {
        openEditor(FakeTranslator(available = false))

        compose.onNodeWithContentDescription("Translate").assertDoesNotExist()
    }
}
