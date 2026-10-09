package dev.ruleblend.core.config

import dev.ruleblend.core.model.NameFormat
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.pathIdentity
import dev.ruleblend.core.translate.TranslationQuality
import dev.ruleblend.core.storage.InterProcessLock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Machine-local app state. Lives outside the library repo, so it never travels with export/sync. */
@Serializable
data class AppConfig(
    /** Absolute paths of project folders shown as integration targets. */
    val projects: List<String> = emptyList(),
    /** Profiles attached to each project; this machine-local state never travels with the library. */
    val projectProfiles: Map<String, List<ProfileBinding>> = emptyMap(),
    /**
     * Rule id to absolute project path. An absent id is global. Scope is machine-local because an
     * absolute checkout path is not portable library content; moving a rule between scopes never
     * changes its body version or the library's Git history.
     */
    val ruleScopes: Map<String, String> = emptyMap(),
    /**
     * Agent ids switched off per project path. Absent = every detected agent is enabled, so an agent
     * installed later is picked up without touching the config.
     */
    val disabledAgents: Map<String, List<String>> = emptyMap(),
    /** Overrides `~/.ruleblend/library`; `null` uses the default. Takes effect on next launch. */
    val libraryPath: String? = null,
    /** UI language: `"en"` or `"ru"`. */
    val language: String = "en",
    /** App appearance: follow the operating system, or force the light/dark theme. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Whether the navigation sidebar shows labels; `false` folds it to icons. */
    val sidebarExpanded: Boolean = true,
    /** Whether grouped lists open their sections when the corresponding screen is first entered. */
    val groupsExpandedByDefault: Boolean = true,
    /** When imported Git skill sources are checked for updates. */
    val sourceCheck: SourceCheckMode = SourceCheckMode.MANUAL,
    /** Whether the library section of the Place palette opens its type sections when a place is entered. */
    val placeLibraryExpandedByDefault: Boolean = true,
    /** Whether rule bodies in the Place Rules tab open when a place is entered. */
    val placeRulesExpandedByDefault: Boolean = false,
    /** Whether skill entries in the Place Skills tab open when a place is entered. */
    val placeSkillsExpandedByDefault: Boolean = false,
    /** Whether subagent entries in the Place Subagents tab open when a place is entered. */
    val placeSubagentsExpandedByDefault: Boolean = false,
    /** Whether MCP entries in the Place MCP tab open when a place is entered. */
    val placeMcpExpandedByDefault: Boolean = false,
    /** Agent ids hidden from the whole app, e.g. detected but never used with Ruleblend. */
    val hiddenAgents: List<String> = emptyList(),
    /** How rule and group names are normalized whenever they are saved. */
    val nameFormat: NameFormat = NameFormat.KEBAB,
    /** Output on this machine; portable library definitions remain LF in Git history. */
    val lineEnding: LineEnding = LineEnding.LF,
    /** Persisted split-pane dimensions (dp), restored on launch and across tab switches. */
    val columnWidths: ColumnWidths = ColumnWidths(),
    /** Optional two-way sync peer for the local library history. Credentials stay outside this file. */
    val remoteGit: RemoteGitConfig? = null,
    /** Whether Integration's edit actions should hand files to the OS-associated editor. */
    val useExternalEditor: Boolean = false,
    /** Place-list navigation state: pinned places, recently visited places, named project sets. */
    val places: PlaceBoard = PlaceBoard(),
    /** Selected external editor application/executable; `null` uses the operating-system default. */
    val externalEditorPath: String? = null,
    /** Terminal application a CLI agent is launched in; `null` uses the operating-system default. */
    val terminalAppPath: String? = null,
    /** Extra arguments appended to an agent's CLI launch, per agent id. */
    val agentCliArguments: Map<String, String> = emptyMap(),
    /** Last window size and position; `null` until the user first moves or resizes the window. */
    val window: WindowGeometry? = null,
    /** Language pair and quality used by the editor's translation panel. */
    val translation: TranslationConfig = TranslationConfig(),
    /**
     * Hand edits the user chose to leave in place, per place key and object id, keyed by a digest of
     * the edited copy. The answer is about one edit, not about the object: the file changing again
     * no longer matches the digest, so the row reports the new drift instead of hiding it behind an
     * answer that was given about something else. Machine-local, like every other target fact here.
     */
    val keptDrifts: Map<String, Map<String, String>> = emptyMap(),
    /** Disk entries hidden in one place. These local choices never travel with the library. */
    val ignoredEntries: Map<String, List<String>> = emptyMap(),
)

/** One portable profile's machine-local attachment and whether it currently contributes to a project. */
@Serializable
data class ProfileBinding(
    val id: String,
    val active: Boolean,
)

/**
 * Settings of the editor's translation panel. Machine-local like the rest of this file: which
 * language a person reads in is a property of the machine, not of the library.
 */
@Serializable
data class TranslationConfig(
    /**
     * Whether the app offers translation at all. Off hides every translation control instead of
     * disabling it: a person who does not translate should not be reading around the offer.
     */
    val enabled: Boolean = true,
    /** The language rules are written in. */
    val sourceLanguage: String = "en",
    /** The language they are read in. */
    val targetLanguage: String = "ru",
    val quality: TranslationQuality = TranslationQuality.LOW,
)

@Serializable
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Schedule for checking imported Git skill sources. */
@Serializable
enum class SourceCheckMode {
    @SerialName("manual")
    MANUAL,

    @SerialName("onLaunch")
    ON_LAUNCH,
}

/**
 * The one identity of a project: absolute and normalized.
 *
 * Everything keyed by project — the registered list, per-project disabled agents, rule scopes, place
 * sets, the MCP and skill install records — uses this and nothing else. A path spelled
 * `ledger/../ledger` names the same checkout as `ledger`, and two spellings of one project would
 * otherwise duplicate it in the list and lose the install records written under the other one.
 */
fun Path.projectKey(): String = pathIdentity()

/** Same normalization for a key already stored as text, so a config read once can be repaired. */
fun projectKeyOf(path: String): String = runCatching { Path.of(path).projectKey() }.getOrDefault(path)

/**
 * The config with every project key normalized: entries written by an older build, or by hand, are
 * repaired on the way in rather than being carried as a second identity of the same project. Keys
 * that collapse onto one another merge their settings without dropping separate attachments.
 */
fun AppConfig.normalizedKeys(): AppConfig {
    val projects = projects.map(::projectKeyOf).distinct()
    val groupedProjects = mutableSetOf<String>()
    val places = places.copy(
        pinned = places.pinned.map(::normalizedPlaceId).distinct(),
        recent = places.recent.map(::normalizedPlaceId).distinct(),
        sets = places.sets.map { set ->
            set.copy(projects = set.projects.map(::projectKeyOf).filter(groupedProjects::add))
        },
    )
    val normalized = copy(
        projects = projects,
        disabledAgents = disabledAgents.mergeKeys(::projectKeyOf) { a, b -> (a + b).distinct() },
        projectProfiles = projectProfiles.mergeKeys(::projectKeyOf) { a, b ->
            (a + b).groupBy { it.id }.map { (id, bindings) ->
                ProfileBinding(id, bindings.all { it.active })
            }
        },
        ruleScopes = ruleScopes.mapValues { (_, value) -> projectKeyOf(value) },
        ignoredEntries = ignoredEntries.mapValues { (_, entries) -> entries.map(::normalizedEntryKey).distinct() }
            .mergeKeys(::normalizedPlaceId) { a, b -> (a + b).distinct() },
        keptDrifts = keptDrifts.mergeKeys(::normalizedPlaceId) { a, b -> b + a },
        places = places,
    )
    return if (normalized == this) this else normalized
}

/** A place id is `agent:<id>` or `project:<key>`; only the second half of the latter is a path. */
fun normalizedPlaceId(placeId: String): String =
    placeId.takeIf { it.startsWith("project:") }?.removePrefix("project:")
        ?.takeIf { it.isNotEmpty() }
        ?.let { "project:${projectKeyOf(it)}" }
        ?: placeId

private fun <T> Map<String, T>.mergeKeys(key: (String) -> String, merge: (T, T) -> T): Map<String, T> =
    entries.fold(linkedMapOf()) { result, (raw, value) ->
        val normalized = key(raw)
        result[normalized] = result[normalized]?.let { merge(it, value) } ?: value
        result
    }

private fun normalizedEntryKey(key: String): String {
    val kind = key.substringBefore(':')
    val address = key.substringAfter(':', "")
    if (kind == "skill" || kind == "subagent") return "$kind:${projectKeyOf(address)}"
    if (kind == "mcp") {
        val start = if (address.getOrNull(1) == ':') 2 else 0
        val separator = address.indexOf(':', start)
        if (separator >= 0) return "mcp:${projectKeyOf(address.take(separator))}${address.substring(separator)}"
    }
    return key
}

/** Returns the project key for [blockId], or `null` when the rule is global. */
fun AppConfig.ruleScope(blockId: String): String? = ruleScopes[blockId]

/** Whether a rule is available globally or to the exact normalized [project] it belongs to. */
fun AppConfig.ruleAppliesTo(blockId: String, project: Path?): Boolean {
    val scope = ruleScope(blockId) ?: return true
    return project?.projectKey() == scope
}

/** Assigns [blockId] to one project, or makes it global when [project] is `null`. */
fun AppConfig.withRuleScope(blockId: String, project: Path?): AppConfig = copy(
    ruleScopes = if (project == null) ruleScopes - blockId else ruleScopes + (blockId to project.projectKey()),
)

/** Drops [profileId] from every project it was attached to; used when the profile itself is deleted. */
fun AppConfig.withoutProfile(profileId: String): AppConfig = copy(
    projectProfiles = projectProfiles
        .mapValues { (_, bindings) -> bindings.filterNot { it.id == profileId } }
        .filterValues { it.isNotEmpty() },
)

/** Whether the drift of [objectId] in [place] is the one the user already chose to keep. */
fun AppConfig.driftKept(place: String, objectId: String, digest: String): Boolean =
    keptDrifts[place]?.get(objectId) == digest

/** Records "keep this edit" for one object in one place, replacing any earlier answer about it. */
fun AppConfig.withKeptDrift(place: String, objectId: String, digest: String): AppConfig =
    copy(keptDrifts = keptDrifts + (place to (keptDrifts[place].orEmpty() + (objectId to digest))))

/**
 * Drops the kept answer for [objectId] in [place]. Every other resolution of a drift — restoring the
 * library copy, saving the edit as a version, taking the object out — ends the drift itself, so the
 * answer about it must not outlive it and silence the next one.
 */
fun AppConfig.withoutKeptDrift(place: String, objectId: String): AppConfig {
    val remaining = keptDrifts[place].orEmpty() - objectId
    return copy(keptDrifts = if (remaining.isEmpty()) keptDrifts - place else keptDrifts + (place to remaining))
}

/** Records that one foreign disk entry is hidden in [place]. */
fun AppConfig.withIgnoredEntry(place: String, entryKey: String): AppConfig = copy(
    ignoredEntries = ignoredEntries + (place to (ignoredEntries[place].orEmpty() + entryKey).distinct()),
)

/** Makes one previously hidden disk entry visible again. */
fun AppConfig.withoutIgnoredEntry(place: String, entryKey: String): AppConfig {
    val remaining = ignoredEntries[place].orEmpty() - entryKey
    return copy(ignoredEntries = if (remaining.isEmpty()) ignoredEntries - place else ignoredEntries + (place to remaining))
}

/**
 * Two-way sync peer for `library/.git`. The local repository remains authoritative; a remote
 * failure never rolls back a library save. HTTPS credentials stay in the user's Git credential
 * helper (with `.netrc` as a fallback).
 */
@Serializable
data class RemoteGitConfig(
    val url: String,
    val branch: String = "main",
    val automatic: Boolean = true,
)

/**
 * Window frame in dp, as the user last left it. Absent on a first run, where the app sizes itself
 * from the screen instead; [x] and [y] are absent while the window is still centred.
 */
@Serializable
data class WindowGeometry(
    val width: Int,
    val height: Int,
    val x: Int? = null,
    val y: Int? = null,
)

/** Resizable pane dimensions in dp. Defaults match the original fixed layout. */
@Serializable
data class ColumnWidths(
    val libraryFacets: Int = 220,
    val libraryInspector: Int = 320,
    val integrationSidebar: Int = 190,
    val integrationFoundPaneHeight: Int = 110,
    val placePalette: Int = 300,
)

/**
 * Reads and writes `~/.ruleblend/config.json`. [interProcessLock] guards [update]'s
 * read-modify-write against the other Ruleblend process (GUI app vs MCP server).
 */
class ConfigStore(private val file: Path, private val interProcessLock: InterProcessLock? = null) {

    /**
     * Forward- and backward-compatible on purpose: a config written by a newer build, or edited by
     * hand, must never stop the app from starting. `ignoreUnknownKeys` drops fields this build does
     * not know; `coerceInputValues` falls back to the property default for a value it cannot decode
     * (an unknown `nameFormat`, say). Every property therefore has to keep a default.
     */
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * The config as stored, or defaults when there is nothing readable there.
     *
     * A file this build cannot parse at all — hand-edited into invalid JSON, a field of the wrong
     * shape, a truncated copy restored from a backup — is moved aside under a diagnostic name rather
     * than parsed, overwritten or allowed to take the app down: losing the window is worse than
     * losing the settings, and the moved file keeps the user's own text recoverable. [quarantined]
     * names it so a surface can say what happened instead of the settings silently reverting.
     */
    fun load(): AppConfig {
        val text = if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        if (text == null) return AppConfig()
        return runCatching { json.decodeFromString(AppConfig.serializer(), text).normalizedKeys() }
            .getOrElse {
                quarantine()
                AppConfig()
            }
    }

    /** Where the last unreadable config was moved, or `null` when nothing was. */
    var quarantined: Path? = null
        private set

    /**
     * Moves the unreadable file next to itself as `config.json.broken`, keeping any earlier one by
     * numbering. Failing to move it is not worth failing the load over: the caller gets defaults
     * either way, and the next successful write replaces the file.
     */
    private fun quarantine() {
        runCatching {
            val name = file.fileName.toString()
            var candidate = file.resolveSibling("$name.broken")
            var index = 2
            while (candidate.exists()) {
                candidate = file.resolveSibling("$name.broken.$index")
                index++
            }
            Files.move(file, candidate)
            quarantined = candidate
        }
    }

    /**
     * Replaces the whole file with [config] — for bootstrap and tests, which own the file outright.
     * Production code changes the config through [update] instead: this app runs as two processes
     * (GUI and MCP server), and a snapshot read a moment ago no longer describes the file. Writing it
     * back whole would drop whatever the other process wrote in between. Held under the same lock as
     * [update] so the file itself is never interleaved.
     */
    fun save(config: AppConfig) {
        val write = {
            preserveBeforePathMigration()
            AtomicWrite.write(file, json.encodeToString(AppConfig.serializer(), config) + "\n")
        }
        interProcessLock?.withLock(write) ?: write()
    }

    private fun preserveBeforePathMigration() {
        val text = file.takeIf { it.exists() }?.readText() ?: return
        val stored = runCatching { json.decodeFromString<AppConfig>(text) }.getOrNull() ?: return
        if (stored == stored.normalizedKeys()) return
        var suffix = ""
        var number = 1
        while (true) {
            val backup = file.resolveSibling("${file.fileName}.before-path-migration$suffix")
            if (AtomicWrite.writeIfUnchanged(backup, text, expected = null)) return
            suffix = ".${++number}"
        }
    }

    /**
     * Atomically applies [transform] to the stored config and returns the result. The whole
     * read-modify-write happens under the lock, so a change made by the other process between the
     * read and the write is transformed rather than overwritten. Use the return value to update UI
     * state — a second [load] afterwards is a second race.
     */
    fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val apply = {
            val updated = transform(load())
            save(updated)
            updated
        }
        return interProcessLock?.withLock(apply) ?: apply()
    }
}
