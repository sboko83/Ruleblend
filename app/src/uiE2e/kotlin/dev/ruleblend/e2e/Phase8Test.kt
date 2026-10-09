package dev.ruleblend.e2e

import dev.ruleblend.core.config.projectKey

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import dev.ruleblend.app.App
import dev.ruleblend.app.i18n.EnStrings
import dev.ruleblend.app.migrateLegacyHome
import dev.ruleblend.app.openConfigStore
import dev.ruleblend.app.theme.RuleblendTheme
import dev.ruleblend.app.util.DesktopEnvironment
import dev.ruleblend.app.util.uiTarget
import dev.ruleblend.core.config.ThemeMode
import dev.ruleblend.core.storage.BlockFile
import dev.ruleblend.core.translate.TranslationError
import dev.ruleblend.core.translate.TranslationException
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.translate.TranslationStatus
import dev.ruleblend.core.translate.Translator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** T-01..T-07 use the real root UI and files, with only the system translator replaced. */
class Phase8Test {
    private val root = Path.of(requireNotNull(System.getProperty("ruleblend.e2e.root")))
    private val stage = requireNotNull(System.getProperty("ruleblend.e2e.stage"))
    private val home = root.resolve("home")
    private val library = home.resolve(".ruleblend/library")
    private val project = Path.of(root.resolve("projects/Проект A").projectKey())
    private val artifacts = Files.createDirectories(root.resolve("artifacts/$stage"))
    private val compose = createComposeRule()
    private val ports = RecordingDesktop(root)
    private val translator = ScriptedTranslator()
    private var step = "startup"

    private val environment = TestRule { base, _ -> object : Statement() {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun evaluate() {
            assertEquals(home.toString(), System.getProperty("user.home"))
            assertEquals(home.toString(), System.getenv("HOME"))
            assertEquals(root.resolve("work").toRealPath(), Path.of("").toRealPath())
            assertEquals(root.fileName.toString(), root.resolve(".ruleblend-e2e").readText().trim())
            val previousSecurity = System.getSecurityManager()
            val previousActions = DesktopEnvironment.actions
            System.setSecurityManager(SandboxWrites(root))
            DesktopEnvironment.actions = ports
            try { base.evaluate() } finally {
                DesktopEnvironment.actions = previousActions
                System.setSecurityManager(previousSecurity)
            }
        }
    } }
    private val diagnostics = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            try { base.evaluate() } catch (failure: Throwable) {
                artifacts.resolve("failure.txt").writeText("$step\n${failure.stackTraceToString()}")
                runCatching {
                    val roots = compose.onAllNodes(isRoot())
                    artifacts.resolve("semantics.txt").writeText(
                        roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() },
                    )
                    val bitmap = roots[roots.fetchSemanticsNodes().lastIndex].captureToImage().asSkiaBitmap()
                    Image.makeFromBitmap(bitmap).use { image ->
                        requireNotNull(image.encodeToData()).use { Files.write(artifacts.resolve("failure.png"), it.bytes) }
                    }
                }
                throw failure
            }
        }
    } }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(environment).around(compose).around(diagnostics)

    private fun start() {
        artifacts.resolve("pid.txt").writeText(ProcessHandle.current().pid().toString())
        migrateLegacyHome()
        compose.setContent {
            RuleblendTheme(ThemeMode.LIGHT) {
                App(openConfigStore(), onThemeModeChanged = {}, createTranslator = { translator })
            }
        }
        compose.onNodeWithTag("nav-library").assertIsDisplayed()
    }

    private fun fieldText(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    private fun route(name: String) {
        step = "route:$name"
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("route-$name").assertIsDisplayed() }.isSuccess ||
                run { compose.onNodeWithTag("nav-$name").performClick(); false }
        }
    }

    private fun catalog() {
        route("library")
        if (compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("library-editor-back").performClick()
        compose.onNodeWithTag("library-search").performTextReplacement("")
    }

    private fun file(kind: String, id: String): Path = if (kind == "SKILL")
        library.resolve("skills/$id/files/SKILL.md") else library.resolve("blocks/$id.md")

    private fun edit(kind: String, id: String) {
        catalog()
        compose.onNodeWithTag("library-search").performTextReplacement(id)
        val row = uiTarget("library", "$kind:$id", "catalog", "select")
        val inspector = uiTarget("library", "$kind:$id", "inspector", "selected")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(inspector).fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag(row).performClick(); false }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-editor-back").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-inspector-edit").performClick(); false }
        }
    }

    private fun create(kind: String, id: String, body: String) {
        step = "create:$kind:$id"
        catalog()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("library-new-${kind.lowercase()}").fetchSemanticsNodes().isNotEmpty() ||
                run { compose.onNodeWithTag("library-new").performClick(); false }
        }
        compose.onNodeWithTag("library-new-${kind.lowercase()}").performClick()
        compose.onNodeWithTag("library-create-name").performTextInput(id)
        if (kind == "SKILL") compose.onNodeWithTag("library-create-description").performTextInput("Synthetic skill")
        compose.onNodeWithTag("dialog-confirm").performClick()
        compose.waitUntil(15_000) { Files.exists(file(kind, id)) }
        edit(kind, id)
        compose.onNodeWithTag(if (kind == "SKILL") "library-editor-skill-content" else "library-editor-block-content")
            .performTextReplacement(body)
        saveEditor()
        compose.waitUntil(15_000) { file(kind, id).readText().contains(body.substringAfterLast('\n')) }
    }

    private fun saveEditor() {
        compose.onNodeWithTag("library-editor-save").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNodeWithTag("library-editor-save")
                    .assertIsNotEnabled().assertTextContains(EnStrings.actionSave)
            }.isSuccess
        }
    }

    private fun showTranslation(expected: String) {
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun installRuleInProject(id: String, prose: String): Path {
        route("place")
        ports.nextDirectory = project
        compose.onNodeWithTag("place-add-project").performClick()
        val place = "project:${project.projectKey()}"
        val projectRow = uiTarget("place", place, "ungrouped", "select")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(projectRow).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("place-list").performScrollToNode(hasTestTag(projectRow))
        compose.onNodeWithTag(projectRow).performClick()
        compose.onNodeWithTag("place-palette-search").performTextReplacement(id)
        compose.onNodeWithTag(uiTarget("palette", "RULE:$id", place, "install")).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("palette", "RULE:$id", place, "remove"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        return Files.walk(project).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.readText().contains(prose) }.findFirst().orElseThrow()
        }
    }

    @Test fun T01_settingsPersistAndInterfaceLanguageDoesNotTranslateLibrary() {
        start(); create("RULE", "t01", "Keep the original rule.")
        val before = file("RULE", "t01").readText()
        route("settings")
        compose.onNodeWithTag("settings-translation-enabled").performClick()
        compose.waitUntil(15_000) { home.resolve(".ruleblend/config.json").readText().contains("\"enabled\": false") }
        route("library"); edit("RULE", "t01")
        compose.onNodeWithTag("translation-toggle").assertDoesNotExist()
        route("settings")
        compose.onNodeWithTag("settings-translation-enabled").performClick()
        compose.onNodeWithTag("settings-translation-quality-high").performClick()
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("settings-translation-target-language").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithTag("settings-translation-target-language").performClick()
        compose.onNodeWithTag("settings-translation-target-option-en").performClick()
        compose.onNodeWithTag("settings-translation-source-language").performClick()
        compose.onNodeWithTag("settings-translation-source-option-ru").performClick()
        compose.onNodeWithTag("settings-language-ru").performClick()
        compose.waitUntil(15_000) {
            home.resolve(".ruleblend/config.json").readText().let {
                it.contains("\"quality\": \"HIGH\"") &&
                    it.contains("\"sourceLanguage\": \"ru\"") && it.contains("\"targetLanguage\": \"en\"")
            }
        }
        assertEquals(before, file("RULE", "t01").readText())
        assertEquals(0, translator.requests.get())
    }

    @Test fun T02_libraryRuleAndSkillTranslateOnlyAfterClick() {
        start(); create("RULE", "t02-rule", "Rule prose.")
        create("SKILL", "t02-skill", "---\nname: t02-skill\ndescription: Synthetic skill\n---\nSkill prose.")
        assertEquals(0, translator.requests.get())
        val ruleBefore = file("RULE", "t02-rule").readText()
        edit("RULE", "t02-rule")
        showTranslation("Перевод: Rule prose.")
        compose.onNodeWithText("Original").assertIsDisplayed()
        compose.onNodeWithText("Translation EN → RU").assertIsDisplayed()
        assertEquals(ruleBefore, file("RULE", "t02-rule").readText())
        val skillBefore = file("SKILL", "t02-skill").readText()
        edit("SKILL", "t02-skill")
        showTranslation("Перевод: Skill prose.")
        assertEquals(skillBefore, file("SKILL", "t02-skill").readText())
        assertTrue(translator.requests.get() >= 2)
        val installed = installRuleInProject("t02-rule", "Rule prose.")
        compose.onNodeWithTag("place-tab-rules").performClick()
        val installedBefore = installed.readText()
        val translateTag = "place-file-translate:$installed"
        compose.onNodeWithTag("place-file-list").performScrollToNode(hasTestTag(translateTag))
        compose.onNodeWithTag(translateTag).performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodesWithTag("place-expand-all").onFirst().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Перевод: Rule prose.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(installedBefore, installed.readText())
    }

    @Test fun T03_editMakesOldTranslationStaleAndSwapChangesDirection() {
        start(); create("RULE", "t03", "Old prose.")
        edit("RULE", "t03"); showTranslation("Перевод: Old prose.")
        compose.onNodeWithTag("library-editor-block-content").assertDoesNotExist()
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("New prose.")
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Перевод: New prose.", substring = true).assertIsDisplayed() }.isSuccess }
        compose.onNodeWithText("Перевод: Old prose.", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("translation-swap").performClick()
        compose.waitUntil(15_000) { translator.lastPair == "ru->en" }
        assertEquals("Old prose.", BlockFile.parse("t03", file("RULE", "t03").readText()).content)
    }

    @Test fun T04_replaceChangesDraftUntilSave() {
        start(); create("RULE", "t04", "Original prose.")
        val installed = installRuleInProject("t04", "Original prose.")
        val installedBefore = installed.readText()
        edit("RULE", "t04")
        val before = file("RULE", "t04").readText()
        showTranslation("Перевод: Original prose.")
        compose.onNodeWithTag("translation-replace").performClick()
        assertEquals(before, file("RULE", "t04").readText())
        compose.onNodeWithTag("library-editor-back").performClick()
        assertEquals(before, file("RULE", "t04").readText())
        edit("RULE", "t04"); showTranslation("Перевод: Original prose.")
        compose.onNodeWithTag("translation-replace").performClick()
        saveEditor()
        compose.waitUntil(15_000) { file("RULE", "t04").readText().contains("Перевод: Original prose.") }
        assertEquals(BlockFile.parse("t04", before).version + 1,
            BlockFile.parse("t04", file("RULE", "t04").readText()).version)
        assertEquals(installedBefore, installed.readText())
        route("place")
        compose.onNodeWithTag("place-palette-search").performTextReplacement("t04")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(uiTarget("palette", "RULE:t04", "project:${project.projectKey()}", "update"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun T05_markdownProtectedFragmentsSurviveUiTranslation() {
        val body = "# Heading\n\nA list with `inline()` and https://example.invalid/a.\n\n- Run --safe at /tmp/demo\n\n```kotlin\nval x = 1\n```\n\n| A | B |\n|---|---|\n| x | y |\n\n<!-- rb1 id=\"x\" version=\"1\" -->"
        start(); create("RULE", "t05", body)
        translator.mangle = true
        edit("RULE", "t05"); showTranslation("Перевод: HEADING")
        compose.onNodeWithTag("translation-replace").performClick()
        saveEditor()
        compose.waitUntil(15_000) { file("RULE", "t05").readText().contains("Перевод:") }
        val saved = BlockFile.parse("t05", file("RULE", "t05").readText()).content
        for (fragment in listOf("`inline()`", "https://example.invalid/a", "--safe", "/tmp/demo",
            "```kotlin\nval x = 1\n```", "| A | B |", "<!-- rb1 id=\"x\" version=\"1\" -->")) {
            assertTrue(fragment in saved, "protected fragment changed: $fragment")
        }
        assertTrue(saved.startsWith("# Перевод: HEADING"))
        assertTrue("- Перевод: RUN --safe AT /tmp/demo" in saved, saved)
        assertTrue("| x | y |" in saved, "table reached the translator")
        assertEquals(body.trimEnd().lines().size, saved.trimEnd().lines().size, "paragraph layout changed")
        translator.mangle = false
        create("SKILL", "t05-skill", "---\nname: \"t05-skill\"\ndescription: \"Synthetic skill\"\n---\n\nSkill prose.\n")
        val skillBefore = file("SKILL", "t05-skill").readText()
        edit("SKILL", "t05-skill"); showTranslation("Перевод: Skill prose.")
        compose.onNodeWithTag("translation-replace").performClick()
        saveEditor()
        compose.waitUntil(15_000) { file("SKILL", "t05-skill").readText().contains("Перевод: Skill prose.") }
        val frontmatter = skillBefore.substringBefore("\n---\n") + "\n---\n"
        assertTrue(file("SKILL", "t05-skill").readText().startsWith(frontmatter))
    }

    @Test fun T06_lateAnswerCannotEnterAnotherObject() {
        start(); create("RULE", "t06-first", "First prose.")
        create("RULE", "t06-second", "Second prose.")
        translator.gate = CompletableDeferred()
        edit("RULE", "t06-first")
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { translator.requests.get() > 0 }
        edit("RULE", "t06-second")
        compose.waitUntil(15_000) { fieldText("library-editor-block-content") == "Second prose." }
        translator.gate?.complete(Unit)
        compose.waitUntil(15_000) { translator.completed.get() >= 1 }
        compose.waitForIdle()
        compose.onNodeWithTag("translation-toggle").assertIsDisplayed()
        compose.onNodeWithText("Перевод: First prose.", substring = true).assertDoesNotExist()
        assertEquals("Second prose.", fieldText("library-editor-block-content"))
        showTranslation("Перевод: Second prose.")
        compose.onNodeWithText("Перевод: First prose.", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("translation-toggle").performClick()
        val beforeEdits = translator.requests.get()
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Second edit.")
        compose.onNodeWithTag("library-editor-block-content").performTextReplacement("Second edited prose.")
        assertEquals(beforeEdits, translator.requests.get())
        translator.gate = CompletableDeferred()
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { translator.requests.get() > beforeEdits }
        compose.onNodeWithTag("translation-toggle").performClick()
        val answered = translator.completed.get()
        translator.gate?.complete(Unit)
        compose.waitUntil(15_000) { translator.completed.get() > answered }
        compose.waitForIdle()
        assertEquals("Second edited prose.", fieldText("library-editor-block-content"))
        compose.onNodeWithText("Перевод: Second edited prose.", substring = true).assertDoesNotExist()
        showTranslation("Перевод: Second edited prose.")
        assertEquals("Second prose.", BlockFile.parse("t06-second", file("RULE", "t06-second").readText()).content)
    }

    @Test fun T07_helperErrorsLeaveSourceUntouched() {
        start(); create("RULE", "t07", "Safe prose with `--flag`.")
        val original = file("RULE", "t07").readText()
        edit("RULE", "t07")
        translator.failure = TranslationError.PACK_MISSING
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("The language pack is not downloaded yet.").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("translation-toggle").performClick()
        translator.failure = TranslationError.UNSUPPORTED
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("This language pair is not offered by macOS.").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("translation-toggle").performClick()
        translator.failure = TranslationError.FAILED
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Translation failed.").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("translation-toggle").performClick()
        translator.failure = null
        translator.corruptPlaceholders = true
        compose.onNodeWithTag("translation-toggle").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("translation-replace").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, compose.onAllNodesWithText("Safe prose with `--flag`.", substring = true)
            .fetchSemanticsNodes().size)
        compose.onNodeWithTag("translation-toggle").performClick()
        translator.available = false
        route("settings"); edit("RULE", "t07")
        compose.onNodeWithTag("translation-toggle").assertDoesNotExist()
        assertEquals(original, file("RULE", "t07").readText())
    }

    private class ScriptedTranslator : Translator {
        @Volatile override var available = true
        val requests = AtomicInteger()
        /** Answers that got past the gate; a late answer counts only once it has really arrived. */
        val completed = AtomicInteger()
        /** Upper-cases everything outside placeholders, so an unmasked fragment cannot survive. */
        @Volatile var mangle = false
        @Volatile var lastPair = ""
        @Volatile var failure: TranslationError? = null
        @Volatile var corruptPlaceholders = false
        @Volatile var gate: CompletableDeferred<Unit>? = null
        override suspend fun status(from: String, to: String, quality: TranslationQuality) = TranslationStatus.INSTALLED
        override suspend fun languages(quality: TranslationQuality) = listOf("en", "ru")
        override suspend fun translate(texts: List<String>, from: String, to: String, quality: TranslationQuality): List<String> {
            requests.incrementAndGet()
            lastPair = "$from->$to"
            gate?.await()
            completed.incrementAndGet()
            failure?.let { throw TranslationException(it) }
            return texts.map { text ->
                when {
                    corruptPlaceholders -> text.replace(Regex("RB\\d+Z"), "")
                    mangle -> "Перевод: " + shout(text)
                    else -> "Перевод: $text"
                }
            }
        }

        private fun shout(text: String): String {
            val token = Regex("RB\\d+Z")
            val kept = token.findAll(text).map { it.value }.toList()
            val parts = text.split(token).map { it.uppercase() }
            return parts.zip(kept + "").joinToString("") { (part, placeholder) -> part + placeholder }
        }
    }
}
