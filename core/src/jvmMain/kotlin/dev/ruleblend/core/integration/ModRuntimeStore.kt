package dev.ruleblend.core.integration

import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.storage.AtomicMove
import dev.ruleblend.core.storage.AtomicWrite
import dev.ruleblend.core.storage.FileIdentity
import dev.ruleblend.core.storage.ModImport
import dev.ruleblend.core.storage.modTreeFingerprint
import dev.ruleblend.core.storage.checkedLibraryId
import dev.ruleblend.core.storage.setSkillExecutable
import dev.ruleblend.core.storage.skillExecutable
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
internal data class OwnedMod(
    val libraryId: String,
    val pluginId: String,
    val marketplacePath: String,
    val fingerprint: String,
    val executableFiles: List<String>,
    val scope: String = "user",
    val installed: Boolean = true,
    val enabled: Boolean = true,
    val reloadRequired: Boolean = true,
)

@Serializable
internal data class PendingMod(
    val action: String,
    val before: OwnedMod?,
    val desired: OwnedMod,
    val catalogBefore: String?,
    val catalogAfter: String?,
)

@Serializable
internal data class ModRuntimeJournal(
    val formatVersion: Int = 1,
    val records: Map<String, OwnedMod> = emptyMap(),
    val catalog: String? = null,
    val marketplaceOwned: Boolean = false,
    val pending: PendingMod? = null,
)

/** Only machine-local owned files. Never writes Claude registries, caches, options or data. */
internal class ModRuntimeStore(val root: Path) {
    val stateFile = root.resolve("state.json")
    val catalogFile = root.resolve(".claude-plugin/marketplace.json")
    private val json = Json { prettyPrint = true }
    private var stateIdentity: FileIdentity? = null

    fun checked(path: Path): Path {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            require(!Files.isSymbolicLink(current)) { "Runtime path contains a symbolic link" }
            current = current.parent
        }
        return path
    }

    fun text(path: Path): String? {
        checked(path)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 2 * 1024 * 1024) { "Invalid runtime file" }
        return Files.readString(path)
    }

    fun load(): ModRuntimeJournal {
        val raw = text(stateFile)
        stateIdentity = raw?.let { FileIdentity.of(it.encodeToByteArray()) }
        return raw?.let {
            json.decodeFromString<ModRuntimeJournal>(it).also { state ->
                require(state.formatVersion == 1 && state.records.size <= 1000)
                state.records.forEach { (id, record) -> require(id == record.libraryId); validate(record) }
                require(state.records.keys.map(String::lowercase).distinct().size == state.records.size)
                require(state.records.values.map { it.pluginId }.distinct().size == state.records.size)
                validateCatalog(state.catalog, state.records)
                state.pending?.let { pending ->
                    validate(pending.desired)
                    pending.before?.let(::validate)
                    require(pending.action in setOf("install", "update", "enable", "disable", "uninstall"))
                    require(pending.before == state.records[pending.desired.libraryId] && pending.catalogBefore == state.catalog)
                    validateCatalog(pending.catalogAfter, state.records + (pending.desired.libraryId to pending.desired))
                }
            }
        } ?: ModRuntimeJournal()
    }

    fun save(state: ModRuntimeJournal) {
        checked(stateFile)
        val next = json.encodeToString(state) + "\n"
        require(AtomicWrite.writeIfUnchanged(stateFile, next, stateIdentity)) { "Runtime ownership state changed outside Ruleblend" }
        stateIdentity = FileIdentity.of(next.encodeToByteArray())
    }

    fun publish(expected: String?, next: String?) {
        val identity = FileIdentity.of(checked(catalogFile))
        require(text(catalogFile) == expected) { "Runtime catalogue changed outside Ruleblend" }
        if (next == expected) return
        if (next == null) Files.deleteIfExists(catalogFile)
        else require(AtomicWrite.writeIfUnchanged(catalogFile, next, identity)) { "Runtime catalogue changed during publication" }
    }

    fun snapshotPath(record: OwnedMod): Path = root.resolve("snapshots/${record.libraryId}-${record.fingerprint}")

    fun materialize(snapshot: ModSnapshot, record: OwnedMod) {
        validate(record)
        val target = checked(snapshotPath(record))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            require(snapshotMatches(record)) { "Immutable runtime snapshot changed" }
            return
        }
        checked(root.resolve("snapshots"))
        Files.createDirectories(target.parent)
        val staging = Files.createTempDirectory(target.parent, ".publish-")
        try {
            snapshot.files.forEach { file ->
                val destination = staging.resolve(file.path)
                Files.createDirectories(destination.parent)
                Files.write(destination, file.bytes)
                setSkillExecutable(destination, file.executable)
            }
            require(modTreeFingerprint(ModImport.capture(staging, executableFiles = record.executableFiles.toSet()).files) == record.fingerprint)
            AtomicMove.move(staging, target)
        } finally {
            // The staging directory is newly allocated by this call, never an old snapshot.
            if (Files.exists(staging)) Files.walk(staging).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    fun snapshotMatches(record: OwnedMod): Boolean = runCatching {
        val path = checked(snapshotPath(record))
        val files = ModImport.capture(path, executableFiles = record.executableFiles.toSet()).files
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            require(files.all { skillExecutable(path.resolve(it.path)) == it.executable })
        modTreeFingerprint(files) == record.fingerprint
    }.getOrDefault(false)

    private fun validate(record: OwnedMod) {
        checkedLibraryId(record.libraryId)
        require(record.fingerprint.matches(Regex("[a-f0-9]{64}")))
        require(record.scope == "user" && record.marketplacePath == root.toAbsolutePath().normalize().toString())
        require(record.pluginId.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*@ruleblend-mods")))
    }

    private fun validateCatalog(text: String?, records: Map<String, OwnedMod>) {
        if (text == null) { require(records.isEmpty()); return }
        require(text.length <= 1024 * 1024)
        val catalog = Json.parseToJsonElement(text).jsonObject
        require(catalog["name"]?.jsonPrimitive?.content == "ruleblend-mods")
        require(catalog["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "Ruleblend")
        val entries = catalog.getValue("plugins").jsonArray
        require(entries.size == records.size)
        val byName = records.values.associateBy { it.pluginId.substringBefore('@') }
        require(entries.map { it.jsonObject.getValue("name").jsonPrimitive.content }.distinct().size == entries.size)
        entries.forEach { item ->
            val entry = item.jsonObject
            require(entry.keys == setOf("name", "source"))
            val record = requireNotNull(byName[entry.getValue("name").jsonPrimitive.content])
            require(entry["source"]?.jsonPrimitive?.content == "./snapshots/${record.libraryId}-${record.fingerprint}")
        }
    }
}
