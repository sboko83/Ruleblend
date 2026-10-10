package dev.ruleblend.core.integration

import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.process.CliProcess
import dev.ruleblend.core.process.resolvedCliProcess
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.ModImport
import dev.ruleblend.core.storage.modTreeFingerprint
import dev.ruleblend.core.storage.validateModSnapshot
import java.nio.file.Path
import kotlinx.serialization.json.*

/** User-scope only. Library import/sync and this runtime never share writable package trees. */
class ClaudeModRuntime(
    root: Path = Path.of(System.getProperty("user.home"), ".ruleblend", "mod-runtime"),
    private val process: CliProcess = resolvedCliProcess("claude"),
    private val lock: InterProcessLock = InterProcessLock(root.toAbsolutePath().parent.resolve("ruleblend.lock")),
) : ModRuntime {
    private val store = ModRuntimeStore(root.toAbsolutePath().normalize())
    private var compatible = false

    override fun probe(target: ModTarget, libraryId: String, manifestName: String?): ModRuntimeStatus {
        if (!supports(target)) return ModRuntimeStatus(libraryId, ModRuntimeState.UNSUPPORTED)
        return guarded { locked {
            capabilities()
            val journal = store.load()
            val actual = observe()
            journal.pending?.let { pending ->
                if (pending.desired.libraryId == libraryId)
                    return@locked actualStatus(pending.desired, ModRuntimeState.PENDING, actual)
            }
            val record = journal.records[libraryId] ?: run {
                val foreign = actual.plugins.firstOrNull { it.id.substringBefore('@') == (manifestName ?: libraryId) }
                return@locked ModRuntimeStatus(libraryId,
                    if (foreign != null || (journal.catalog == null && store.text(store.catalogFile) != null))
                        ModRuntimeState.FOREIGN else ModRuntimeState.ABSENT,
                    pluginId = foreign?.id, installed = foreign != null, enabled = foreign?.enabled ?: false)
            }
            classify(journal, record, actual)
        } }
    }

    override fun install(target: ModTarget, snapshot: ModSnapshot) = apply(target, snapshot, update = false)
    override fun update(target: ModTarget, snapshot: ModSnapshot) = apply(target, snapshot, update = true)

    private fun apply(target: ModTarget, snapshot: ModSnapshot, update: Boolean): ModRuntimeStatus {
        supported(target)
        return guarded { locked {
            capabilities()
            validateModSnapshot(snapshot)
            ModImport.validate(snapshot.files)
            val journal = ready()
            val actual = observe()
            val before = journal.records[snapshot.mod.id]
            val name = snapshot.mod.manifestName
            val pluginId = "$name@ruleblend-mods"
            if (before != null) {
                owned(classify(journal, before, actual))
                if (before.pluginId != pluginId) fail(ModRuntimeError.DRIFT)
            }
            if (update && before?.installed != true) fail(ModRuntimeError.INVALID_STATE)
            if (journal.records.values.any { it.libraryId != snapshot.mod.id && it.pluginId == pluginId }) fail(ModRuntimeError.FOREIGN)
            if (journal.records.keys.any { it != snapshot.mod.id && it.equals(snapshot.mod.id, ignoreCase = true) }) fail(ModRuntimeError.FOREIGN)
            if (actual.plugins.any { plugin -> plugin.id.substringBefore('@') == name &&
                    (before?.installed != true || plugin.id != pluginId || plugin.scope != "user") }) fail(ModRuntimeError.FOREIGN)
            marketplaceSafe(journal, actual)
            val desired = OwnedMod(snapshot.mod.id, pluginId, store.root.toString(), modTreeFingerprint(snapshot.files),
                snapshot.files.filter { it.executable }.map { it.path }, enabled = before?.enabled ?: true)
            if (before?.installed == true && before.fingerprint == desired.fingerprint) return@locked status(before)
            store.materialize(snapshot, desired)
            val after = catalogue(journal.catalog, desired)
            transact(journal, PendingMod(if (before?.installed == true) "update" else "install", before, desired, journal.catalog, after)) {
                if (actual.marketplace == null) mutation(listOf("marketplace", "add", store.root.toString(), "--scope", "user", "--json"))
                mutation(listOf(if (before?.installed == true) "update" else "install", pluginId, "--scope", "user", "--json"))
            }
        } }
    }

    override fun enable(target: ModTarget, libraryId: String) = toggle(target, libraryId, true)
    override fun disable(target: ModTarget, libraryId: String) = toggle(target, libraryId, false)

    private fun toggle(target: ModTarget, id: String, enabled: Boolean): ModRuntimeStatus {
        supported(target)
        return guarded { locked {
            capabilities()
            val journal = ready()
            val record = requireOwned(journal, id, observe())
            if (!record.installed) fail(ModRuntimeError.INVALID_STATE)
            if (record.enabled == enabled) return@locked status(record)
            val desired = record.copy(enabled = enabled, reloadRequired = true)
            val action = if (enabled) "enable" else "disable"
            transact(journal, PendingMod(action, record, desired, journal.catalog, journal.catalog)) {
                mutation(listOf(action, record.pluginId, "--scope", "user", "--json"))
            }
        } }
    }

    override fun previewRemove(target: ModTarget, libraryId: String): ModRemovalPreview {
        supported(target)
        return guarded { locked {
            capabilities()
            val journal = ready()
            removal(journal, libraryId, observe())
        } }
    }

    override fun remove(target: ModTarget, libraryId: String, accepted: ModRemovalPreview): ModRuntimeStatus {
        supported(target)
        return guarded { locked {
            capabilities()
            val journal = ready()
            val actual = observe()
            val current = removal(journal, libraryId, actual)
            if (current != accepted) fail(ModRuntimeError.CONFIRMATION_REQUIRED)
            val record = requireOwned(journal, libraryId, actual)
            if (!record.installed) return@locked status(record)
            val desired = record.copy(installed = false, enabled = false, reloadRequired = true)
            transact(journal, PendingMod("uninstall", record, desired, journal.catalog, journal.catalog)) {
                mutation(listOf("uninstall", record.pluginId, "--scope", target.scope, "--keep-data", "--json"))
            }
        } }
    }

    override fun recover(target: ModTarget): List<ModRuntimeStatus> {
        supported(target)
        return guarded { locked {
            capabilities()
            var journal = store.load()
            journal.pending?.let { pending ->
                val actual = observe()
                if (matches(pending.desired, pending.catalogAfter, actual)) {
                    journal = commit(journal, pending)
                } else if (store.text(store.catalogFile) in listOf(pending.catalogBefore, pending.catalogAfter) &&
                    (pending.before?.let { matches(it, pending.catalogBefore, actual, checkCatalog = false) }
                        ?: (actual.plugins.none { it.id == pending.desired.pluginId } && marketplaceOriginSafe(actual)))) {
                    store.publish(store.text(store.catalogFile), pending.catalogBefore)
                    journal = journal.copy(pending = null, marketplaceOwned = journal.marketplaceOwned || actual.marketplace != null)
                    store.save(journal)
                }
            }
            val actual = observe()
            journal.records.values.map { classify(journal, it, actual) }.toMutableList().also { statuses ->
                journal.pending?.let { pending ->
                    statuses.removeAll { it.libraryId == pending.desired.libraryId }
                    statuses += actualStatus(pending.desired, ModRuntimeState.PENDING, actual)
                }
            }
        } }
    }

    private fun removal(journal: ModRuntimeJournal, id: String, actual: Actual): ModRemovalPreview {
        val record = requireOwned(journal, id, actual)
        val last = actual.plugins.count { it.id == record.pluginId } <= 1
        return ModRemovalPreview(status(record), record.scope, lastInstallation = last,
            consequences = if (!record.installed) emptyList() else buildList {
                add("Remove ${record.pluginId} from user scope; preserve persistent data with --keep-data.")
                if (last) add("The last uninstall deletes stored plugin options and secrets. Disable instead to retain them.")
                add("Open sessions require /reload-plugins or restart. Runtime snapshots are retained.")
            })
    }

    private fun transact(journal: ModRuntimeJournal, pending: PendingMod, commands: () -> Unit): ModRuntimeStatus {
        // Persist the CLI boundary before either publication or a child process can change state.
        store.save(journal.copy(pending = pending))
        try {
            store.publish(pending.catalogBefore, pending.catalogAfter)
            commands()
            if (!matches(pending.desired, pending.catalogAfter, observe())) fail(ModRuntimeError.PENDING)
            commit(journal, pending)
            return status(pending.desired)
        } catch (error: Exception) {
            // Never undo CLI writes or delete snapshots. Keep the journal for explicit reconciliation.
            runCatching { store.publish(pending.catalogAfter, pending.catalogBefore) }.onFailure(error::addSuppressed)
            runCatching { observe() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    private fun commit(journal: ModRuntimeJournal, pending: PendingMod): ModRuntimeJournal =
        journal.copy(records = journal.records + (pending.desired.libraryId to pending.desired),
            catalog = pending.catalogAfter, marketplaceOwned = true, pending = null).also(store::save)

    private fun catalogue(before: String?, record: OwnedMod): String {
        val previous = before?.let { Json.parseToJsonElement(it).jsonObject }
        val entries = previous?.get("plugins")?.jsonArray.orEmpty().filter { it.jsonObject["name"]?.jsonPrimitive?.content != record.pluginId.substringBefore('@') }
        val entry = buildJsonObject {
            put("name", record.pluginId.substringBefore('@'))
            put("source", "./snapshots/${record.libraryId}-${record.fingerprint}")
        }
        return buildJsonObject {
            put("name", "ruleblend-mods")
            put("owner", buildJsonObject { put("name", "Ruleblend") })
            put("plugins", JsonArray(entries + entry))
        }.toString() + "\n"
    }

    private data class Plugin(val id: String, val scope: String, val enabled: Boolean)
    private data class Actual(val plugins: List<Plugin>, val marketplace: JsonObject?)

    private fun observe(): Actual {
        val plugins = array(listOf("list", "--json")).map { item ->
            val obj = item.jsonObject
            Plugin(obj.getValue("id").jsonPrimitive.content, obj.getValue("scope").jsonPrimitive.content,
                obj.getValue("enabled").jsonPrimitive.boolean)
        }
        val markets = array(listOf("marketplace", "list", "--json")).map { it.jsonObject }
            .filter { it["name"]?.jsonPrimitive?.content == "ruleblend-mods" }
        if (markets.size > 1) fail(ModRuntimeError.DRIFT)
        return Actual(plugins, markets.singleOrNull())
    }

    private fun marketplaceOriginSafe(actual: Actual): Boolean = actual.marketplace?.let { market ->
        market["source"]?.jsonPrimitive?.content == "directory" &&
            market["path"]?.jsonPrimitive?.content?.let { Path.of(it).toAbsolutePath().normalize() == store.root } == true &&
            market["installLocation"]?.jsonPrimitive?.content?.let { Path.of(it).toAbsolutePath().normalize() == store.root } == true &&
            market["autoUpdate"]?.jsonPrimitive?.booleanOrNull != true
    } ?: true

    private fun marketplaceSafe(journal: ModRuntimeJournal, actual: Actual) {
        if (!marketplaceOriginSafe(actual) || (!journal.marketplaceOwned && actual.marketplace != null)) fail(ModRuntimeError.FOREIGN)
        if (store.text(store.catalogFile) != journal.catalog) fail(ModRuntimeError.DRIFT)
    }

    private fun matches(record: OwnedMod, catalog: String?, actual: Actual, checkCatalog: Boolean = true): Boolean {
        if (!marketplaceOriginSafe(actual) || (record.installed && actual.marketplace == null)) return false
        if (checkCatalog && store.text(store.catalogFile) != catalog) return false
        val entries = catalog?.let { Json.parseToJsonElement(it).jsonObject["plugins"]?.jsonArray }.orEmpty()
        val entry = entries.singleOrNull { it.jsonObject["name"]?.jsonPrimitive?.content == record.pluginId.substringBefore('@') }
        if (entry?.jsonObject?.get("source")?.jsonPrimitive?.content != "./snapshots/${record.libraryId}-${record.fingerprint}" || !store.snapshotMatches(record)) return false
        val installed = actual.plugins.filter { it.id == record.pluginId && it.scope == record.scope }
        if (actual.plugins.any { it.id.substringBefore('@') == record.pluginId.substringBefore('@') && it.id != record.pluginId }) return false
        return if (!record.installed) installed.isEmpty() else installed.size == 1 && installed.single().enabled == record.enabled
    }

    private fun classify(journal: ModRuntimeJournal, record: OwnedMod, actual: Actual): ModRuntimeStatus {
        if (journal.pending?.desired?.libraryId == record.libraryId) return status(record, ModRuntimeState.PENDING)
        return actualStatus(record, if (matches(record, journal.catalog, actual)) ModRuntimeState.OWNED else ModRuntimeState.DRIFT, actual)
    }

    private fun actualStatus(record: OwnedMod, state: ModRuntimeState, actual: Actual): ModRuntimeStatus {
        val installed = actual.plugins.singleOrNull { it.id == record.pluginId && it.scope == record.scope }
        return status(record, state).copy(installed = installed != null, enabled = installed?.enabled ?: false)
    }

    private fun status(record: OwnedMod, state: ModRuntimeState = ModRuntimeState.OWNED) = ModRuntimeStatus(
        record.libraryId, state, record.pluginId, record.installed, record.enabled, record.fingerprint, record.reloadRequired)

    private fun requireOwned(journal: ModRuntimeJournal, id: String, actual: Actual): OwnedMod {
        val record = journal.records[id] ?: fail(ModRuntimeError.FOREIGN)
        owned(classify(journal, record, actual))
        return record
    }

    private fun owned(status: ModRuntimeStatus) {
        if (status.state != ModRuntimeState.OWNED) fail(if (status.state == ModRuntimeState.PENDING) ModRuntimeError.PENDING else ModRuntimeError.DRIFT)
    }

    private fun ready(): ModRuntimeJournal = store.load().also { if (it.pending != null) fail(ModRuntimeError.PENDING) }
    private fun <T> locked(body: () -> T): T { store.checked(store.root); return lock.withLock(body) }
    private fun <T> guarded(body: () -> T): T = try { body() }
        catch (error: ModRuntimeException) { throw error }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); fail(ModRuntimeError.CANCELLED) }
        catch (_: Exception) { fail(ModRuntimeError.INVALID_STATE) }
    private fun supports(target: ModTarget) = target.assistant == "claude-code" && target.scope == "user"
    private fun supported(target: ModTarget) { if (!supports(target)) fail(ModRuntimeError.UNSUPPORTED) }

    private fun capabilities() {
        if (compatible) return
        val version = run(listOf("--version")).stdout
        val parts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)").find(version.trim())?.groupValues?.drop(1)?.map(String::toInt)
            ?: fail(ModRuntimeError.INCOMPATIBLE)
        if (parts[0] < 2 || (parts[0] == 2 && (parts[1] < 1 || (parts[1] == 1 && parts[2] < 287)))) fail(ModRuntimeError.INCOMPATIBLE)
        val requirements = mapOf(
            listOf("install") to listOf("--scope", "--json"), listOf("update") to listOf("--scope", "--json"),
            listOf("enable") to listOf("--scope", "--json"), listOf("disable") to listOf("--scope", "--json"),
            listOf("uninstall") to listOf("--scope", "--json", "--keep-data"), listOf("list") to listOf("--json"),
            listOf("marketplace", "add") to listOf("--scope", "--json"), listOf("marketplace", "list") to listOf("--json"),
        )
        requirements.forEach { (args, flags) ->
            val help = run(listOf("plugin") + args + "--help").stdout
            if (!flags.all { it in help }) fail(ModRuntimeError.INCOMPATIBLE)
        }
        compatible = true
    }

    private fun run(args: List<String>) = process.run(args).also { if (it.exitCode != 0) fail(ModRuntimeError.CLI_FAILED) }
    private fun array(args: List<String>): JsonArray = Json.parseToJsonElement(run(listOf("plugin") + args).stdout) as? JsonArray
        ?: fail(ModRuntimeError.INVALID_STATE)
    private fun mutation(args: List<String>) {
        val result = run(listOf("plugin") + args)
        val json = Json.parseToJsonElement(result.stdout.lineSequence().filter(String::isNotBlank).last()).jsonObject
        if (json["outcome"]?.jsonPrimitive?.content != "ok") fail(ModRuntimeError.CLI_FAILED)
    }

    companion object {
        private fun fail(error: ModRuntimeError): Nothing = throw ModRuntimeException(error, "Claude Code mods: $error")
    }
}
