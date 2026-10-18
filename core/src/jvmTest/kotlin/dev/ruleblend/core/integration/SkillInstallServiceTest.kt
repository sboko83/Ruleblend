package dev.ruleblend.core.integration

import dev.ruleblend.core.blockFileReplacement
import dev.ruleblend.core.model.Skill
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.model.SkillSnapshot
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.LocalSkillCapture
import dev.ruleblend.core.storage.LockTimeoutException
import dev.ruleblend.core.storage.TargetMutationCoordinator
import dev.ruleblend.core.storage.skillTreeFingerprint
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SkillInstallServiceTest {
    private lateinit var home: Path
    private lateinit var project: Path
    private lateinit var snapshots: MutableMap<String, SkillSnapshot>
    private lateinit var state: SkillInstallStateStore
    private lateinit var service: SkillInstallService

    private val skill get() = requireNotNull(snapshots["review"]).skill
    private val globalClaude get() = AgentGlobalTarget(ClaudeCodeAdapter(home))
    private val globalCodex get() = AgentGlobalTarget(CodexAdapter(home))
    private val globalPi get() = AgentGlobalTarget(PiAdapter(home))
    private val globalKimi get() = AgentGlobalTarget(KimiCodeAdapter(home))
    private val kimiProjectTarget get() = ProjectTarget(project, listOf(KimiCodeAdapter(home)))
    private val globalZCode get() = AgentGlobalTarget(ZCodeAdapter(home))
    private val zCodeProjectTarget get() = ProjectTarget(project, listOf(ZCodeAdapter(home)))
    private val projectTarget get() = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), CodexAdapter(home), PiAdapter(home)))

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("ruleblend-skill-home")
        project = Files.createTempDirectory("ruleblend-skill-project")
        home.resolve(".claude").createDirectories()
        home.resolve(".codex").createDirectories()
        home.resolve(".kimi-code").createDirectories()
        home.resolve(".zcode").createDirectories()
        home.resolve(".pi/agent").createDirectories()
        snapshots = mutableMapOf("review" to snapshot("# Review\n", version = "1"))
        state = SkillInstallStateStore(home.resolve(".ruleblend"))
        val agents = listOf(ClaudeCodeAdapter(home), CodexAdapter(home), PiAdapter(home), KimiCodeAdapter(home), ZCodeAdapter(home))
        service = SkillInstallService(state, { snapshots[it] }, agentById = { id -> agents.firstOrNull { it.id == id } }, sharedHome = home)
    }

    @AfterTest
    fun tearDown() {
        home.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `every agent installs skills only in its own root`() {
        listOf(globalCodex, globalPi, globalKimi, globalZCode).forEach { service.install(it, skill) }
        listOf(kimiProjectTarget, zCodeProjectTarget, ProjectTarget(project, listOf(PiAdapter(home)))).forEach { service.install(it, skill) }

        listOf(".codex/skills", ".pi/agent/skills", ".kimi-code/skills", ".zcode/skills").forEach { root ->
            assertEquals("# Review\n", home.resolve("$root/review/SKILL.md").readText(), root)
        }
        listOf(".pi/skills", ".kimi-code/skills", ".zcode/skills").forEach { root ->
            assertEquals("# Review\n", project.resolve("$root/review/SKILL.md").readText(), root)
        }
        assertFalse(home.resolve(".agents").exists())
        assertFalse(project.resolve(".agents").exists())
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(globalPi, skill))
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(zCodeProjectTarget, skill))
    }

    @Test
    fun `a Pi copy left in the shared root moves into Pi's own root`() {
        val shared = legacyCopy(home.resolve(".agents/skills/review"), skillTargetKey(globalPi), "pi", directoryId = null)

        assertEquals(1, service.retireSharedCopies(globalPi))

        assertFalse(shared.exists())
        assertEquals("# Review\n", home.resolve(".pi/agent/skills/review/SKILL.md").readText())
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(globalPi, skill))
        assertTrue(state.legacy().isEmpty(), state.legacy().toString())
    }

    @Test
    fun `a shared project copy moves home even when other agents recorded it too`() {
        val target = ProjectTarget(project, listOf(PiAdapter(home), ZCodeAdapter(home)))
        service.install(zCodeProjectTarget, skill)
        val shared = legacyCopy(project.resolve(".agents/skills/review"), skillTargetKey(target), "zcode", "shared")
        legacyCopy(shared, skillTargetKey(target), "pi", directoryId = null)

        assertEquals(2, service.retireSharedCopies(target))

        assertFalse(shared.exists())
        assertEquals("# Review\n", project.resolve(".pi/skills/review/SKILL.md").readText())
        assertEquals("# Review\n", project.resolve(".zcode/skills/review/SKILL.md").readText())
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(target, skill))
        assertTrue(state.legacy().isEmpty(), state.legacy().toString())
    }

    @Test
    fun `a shared copy beside a native one is deleted`() {
        service.install(globalKimi, skill)
        val shared = legacyCopy(home.resolve(".agents/skills/review"), skillTargetKey(globalKimi), "kimi-code", "shared")

        assertEquals(1, service.retireSharedCopies(globalKimi))

        assertFalse(shared.exists())
        assertEquals("# Review\n", home.resolve(".kimi-code/skills/review/SKILL.md").readText())
        assertTrue(state.legacy().isEmpty())
    }

    @Test
    fun `an edited shared copy stays and is no longer managed`() {
        val shared = legacyCopy(home.resolve(".agents/skills/review"), skillTargetKey(globalPi), "pi", directoryId = null)
        shared.resolve("SKILL.md").writeText("# Edited\n")

        assertEquals(1, service.retireSharedCopies(globalPi))

        assertEquals("# Edited\n", shared.resolve("SKILL.md").readText())
        assertFalse(home.resolve(".pi/agent/skills/review").exists())
        assertNull(service.status(globalPi, skill))
        assertTrue(state.legacy().isEmpty())
    }

    @Test
    fun `a shared copy never replaces a directory already in the native root`() {
        val shared = legacyCopy(home.resolve(".agents/skills/review"), skillTargetKey(globalPi), "pi", directoryId = null)
        val foreign = home.resolve(".pi/agent/skills/review").createDirectories().resolve("SKILL.md")
        foreign.writeText("# Mine\n")

        service.retireSharedCopies(globalPi)

        assertFalse(shared.exists())
        assertEquals("# Mine\n", foreign.readText())
    }

    /** A path is an `Iterable<Path>`: the lock set must name the state file, not its name components. */
    @Test
    fun `lock timeout names only the skill tree and the state file`() {
        val lock = InterProcessLock(home.resolve("ruleblend.lock"), timeoutMs = 100)
        val agents = listOf(CodexAdapter(home))
        val locked = SkillInstallService(state, { snapshots[it] }, agentById = { id -> agents.firstOrNull { it.id == id } }, TargetMutationCoordinator(lock))
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread { lock.withLock { held.countDown(); release.await() } }
        held.await()
        try {
            val failure = assertFailsWith<LockTimeoutException> { locked.install(globalCodex, skill) }
            assertTrue(failure.message.orEmpty().endsWith("; review, skill-state.json left unchanged"), failure.message)
        } finally {
            release.countDown()
            holder.join()
        }
    }

    @Test
    fun `Kimi installs skills in its global and project directories`() {
        service.install(globalKimi, skill)
        service.install(kimiProjectTarget, skill)

        assertEquals("# Review\n", home.resolve(".kimi-code/skills/review/SKILL.md").readText())
        assertEquals("# Review\n", project.resolve(".kimi-code/skills/review/SKILL.md").readText())
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(kimiProjectTarget, skill))
    }

    @Test
    fun `project install writes every agent skills tree once`() {
        service.install(projectTarget, skill)

        assertTrue(project.resolve(".claude/skills/review/SKILL.md").exists())
        assertTrue(project.resolve(".codex/skills/review/SKILL.md").exists())
        assertTrue(project.resolve(".pi/skills/review/references/checklist.md").exists())
        assertEquals(emptyList(), service.unsupportedAgents(projectTarget))
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(projectTarget, skill))
    }

    @Test
    fun `an assistant connected after the install leaves the existing copies synced`() {
        service.install(ProjectTarget(project, listOf(ClaudeCodeAdapter(home))), skill)
        val target = ProjectTarget(project, listOf(ClaudeCodeAdapter(home), ZCodeAdapter(home)))

        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false, listOf("zcode")), service.status(target, skill))
        assertEquals(
            listOf(InstallStatus.SYNCED, null),
            service.destinationStatuses(target, skill).map { it.status },
        )
        snapshots["review"] = snapshot("# Review twice\n", version = "2")
        assertEquals(SkillInstallStatus(InstallStatus.UPDATE_AVAILABLE, false, listOf("zcode")), service.status(target, skill))

        service.install(target, skill)

        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(target, skill))
        assertTrue(project.resolve(".zcode/skills/review/SKILL.md").exists())
    }

    @Test
    fun `library edit is update available and reinstall replaces complete tree`() {
        service.install(globalClaude, skill)
        snapshots["review"] = snapshot("# Updated\n", version = "2", includeReference = false)

        assertEquals(InstallStatus.UPDATE_AVAILABLE, service.status(globalClaude, skill)?.status)
        service.install(globalClaude, skill)

        assertEquals("# Updated\n", home.resolve(".claude/skills/review/SKILL.md").readText())
        assertFalse(home.resolve(".claude/skills/review/references/checklist.md").exists())
    }

    @Test
    fun `foreign directory needs explicit overwrite`() {
        val file = home.resolve(".claude/skills/review/SKILL.md")
        file.parent.createDirectories()
        file.writeText("# Foreign\n")

        assertEquals(SkillInstallStatus(InstallStatus.MODIFIED, true), service.status(globalClaude, skill))
        assertFailsWith<IllegalArgumentException> { service.install(globalClaude, skill) }

        service.install(globalClaude, skill, overwrite = true)
        assertEquals("# Review\n", file.readText())
    }

    @Test
    fun `hand edited installed directory is never removed`() {
        service.install(globalClaude, skill)
        val file = home.resolve(".claude/skills/review/SKILL.md")
        file.writeText("# Hand edit\n")

        assertEquals(InstallStatus.MODIFIED, service.status(globalClaude, skill)?.status)
        assertFailsWith<IllegalArgumentException> { service.remove(globalClaude, skill) }
        assertTrue(file.exists())
    }

    @Test
    fun `absent skill has no status`() {
        assertNull(service.status(globalClaude, skill))
    }

    @Test
    fun `entries list direct skill directories and fall back to their directory names`() {
        val root = home.resolve(".claude/skills")
        root.resolve("without-skill-file").createDirectories()
        root.resolve("review").createDirectories()
        root.resolve("review/SKILL.md").writeText("---\n---\n\n# Review\n")
        root.resolve("format").createDirectories()
        root.resolve("format/SKILL.md").writeText(
            "---\nname: formatter\ndescription: Formats Kotlin\nversion: 2.1\n---\n\n# Format\n",
        )

        assertEquals(
            listOf(
                SkillDirEntry(
                    root.resolve("format"),
                    "format",
                    dev.ruleblend.core.model.SkillFrontmatter("formatter", "Formats Kotlin", "2.1"),
                    "---\nname: formatter\ndescription: Formats Kotlin\nversion: 2.1\n---\n\n# Format\n",
                ),
                SkillDirEntry(
                    root.resolve("review"),
                    "review",
                    dev.ruleblend.core.model.SkillFrontmatter("review"),
                    "---\n---\n\n# Review\n",
                ),
            ),
            service.entries(globalClaude),
        )
    }

    @Test
    fun `entries do not follow symbolic linked skill directories`() {
        val root = home.resolve(".claude/skills")
        val outside = project.resolve("outside")
        outside.createDirectories()
        outside.resolve("SKILL.md").writeText("---\nname: outside\n---\n")
        root.createDirectories()
        Files.createSymbolicLink(root.resolve("linked"), outside)

        assertEquals(emptyList(), service.entries(globalClaude))
    }

    @Test
    fun `an unreadable entry drops itself instead of the rest of the root`() {
        val root = home.resolve(".claude/skills")
        root.resolve("binary").createDirectories()
        Files.write(root.resolve("binary/SKILL.md"), byteArrayOf(-1, -2, -3))
        root.resolve("review").createDirectories()
        root.resolve("review/SKILL.md").writeText("---\nname: review\n---\n")

        assertEquals(listOf("review"), service.entries(globalClaude).map { it.id })
    }

    @Test
    fun `two agents naming their roots alike keep separate entries`() {
        val target = ProjectTarget(project, listOf(CodexAdapter(home), KimiCodeAdapter(home)))
        val codex = project.resolve(".codex/skills/review")
        val kimi = project.resolve(".kimi-code/skills/review")
        listOf(codex, kimi).forEach { directory ->
            directory.createDirectories()
            directory.resolve("SKILL.md").writeText("---\nname: review\n---\n")
        }
        state.record(SkillInstallRecord(skillTargetKey(target), CodexAdapter(home).id, "review", "fingerprint"))

        val entries = service.classifiedEntries(target, librarySkillIds = setOf("review"))

        assertEquals(2, entries.map { it.key }.distinct().size)
        assertEquals(PlaceEntryOrigin.MANAGED, entries.single { it.value.path == codex }.origin)
        assertEquals(PlaceEntryOrigin.FOREIGN, entries.single { it.value.path == kimi }.origin)
    }

    @Test
    fun `classified entries cover every origin from disk and sidecar state`() {
        val root = home.resolve(".claude/skills")
        listOf("managed", "orphan", "foreign", "ignored").forEach { id ->
            root.resolve(id).createDirectories()
            root.resolve("$id/SKILL.md").writeText("---\nname: $id\n---\n")
        }
        state.record(SkillInstallRecord("agent", globalClaude.agent.id, "managed", "managed-fingerprint"))
        state.record(SkillInstallRecord("agent", globalClaude.agent.id, "orphan", "orphan-fingerprint"))

        val origins = service.classifiedEntries(
            globalClaude,
            librarySkillIds = setOf("managed"),
            ignoredEntryKeys = setOf(skillPlaceEntryKey(root.resolve("ignored"))),
        ).associate { it.value.id to it.origin }

        assertEquals(
            mapOf(
                "foreign" to PlaceEntryOrigin.FOREIGN,
                "ignored" to PlaceEntryOrigin.IGNORED,
                "managed" to PlaceEntryOrigin.MANAGED,
                "orphan" to PlaceEntryOrigin.ORPHAN,
            ),
            origins,
        )
    }

    @Test
    fun `deleting a library skill leaves its managed directory as orphan`() {
        val root = home.resolve(".claude/skills")
        root.resolve("review").createDirectories()
        root.resolve("review/SKILL.md").writeText("---\nname: review\n---\n")
        state.record(SkillInstallRecord("agent", globalClaude.agent.id, "review", "fingerprint"))

        val managed = service.classifiedEntries(globalClaude, snapshots.keys).single()
        snapshots.remove("review")
        val orphan = service.classifiedEntries(globalClaude, snapshots.keys).single()

        assertEquals(PlaceEntryOrigin.MANAGED, managed.origin)
        assertEquals(PlaceEntryOrigin.ORPHAN, orphan.origin)
        assertEquals(managed.value, orphan.value)
        assertEquals(managed.key, orphan.key)
    }

    @Test
    fun `captured foreign directory becomes managed without being rewritten`() {
        val directory = home.resolve(".claude/skills/community").also { it.createDirectories() }
        val script = directory.resolve("scripts/check.sh").also { it.parent.createDirectories() }
        directory.resolve("SKILL.md").writeText("---\nname: community\n---\n")
        script.writeText("#!/bin/sh\nexit 0\n")
        script.toFile().setExecutable(true)
        val captured = LocalSkillCapture.capture(directory)
        snapshots["community"] = captured

        service.takeOwnership(globalClaude, service.entries(globalClaude).single(), captured.skill)

        val supportsPosix = Files.getFileAttributeView(script, java.nio.file.attribute.PosixFileAttributeView::class.java) != null
        assertEquals(supportsPosix, captured.files.single { it.path == "scripts/check.sh" }.executable)
        assertEquals("#!/bin/sh\nexit 0\n", script.readText())
        assertEquals(SkillInstallStatus(InstallStatus.SYNCED, false), service.status(globalClaude, captured.skill))
        assertEquals(PlaceEntryOrigin.MANAGED, service.classifiedEntries(globalClaude, setOf("community")).single().origin)
    }

    @Test
    fun `releasing keeps the tree foreign and a differing tree is taken back as an update`() {
        val snapshot = snapshot("---\nname: review\n---\n", "1")
        snapshots["review"] = snapshot
        service.install(globalClaude, snapshot.skill)
        val directory = home.resolve(".claude/skills/review")

        service.release(globalClaude, "review")

        assertTrue(directory.resolve("SKILL.md").exists())
        assertEquals(SkillInstallStatus(InstallStatus.MODIFIED, true), service.status(globalClaude, snapshot.skill))
        assertFailsWith<IllegalArgumentException> { service.remove(globalClaude, snapshot.skill) }
        assertTrue(directory.resolve("SKILL.md").exists())

        directory.resolve("SKILL.md").writeText("---\nname: review\n---\nEdited.\n")
        val entry = service.entries(globalClaude).single()
        assertFailsWith<IllegalArgumentException> { service.takeOwnership(globalClaude, entry, snapshot.skill) }
        service.takeOwnership(globalClaude, entry, snapshot.skill, sameAsLibrary = false)
        assertEquals(SkillInstallStatus(InstallStatus.UPDATE_AVAILABLE, false), service.status(globalClaude, snapshot.skill))
    }

    /**
     * A copy an earlier version wrote into a shared `.agents/skills` tree: the files of the current
     * review snapshot at [directory], and a record under the retired root id ([directoryId] `null`
     * stands for a record that predates the field, as Pi's did).
     */
    private fun legacyCopy(directory: Path, targetKey: String, agentId: String, directoryId: String?): Path {
        val files = requireNotNull(snapshots["review"]).files
        files.forEach { file ->
            directory.resolve(file.path).also { it.parent.createDirectories() }.writeBytes(file.bytes)
        }
        val stateFile = home.resolve(".ruleblend/skill-state.json")
        val existing = if (stateFile.exists()) Json.parseToJsonElement(stateFile.readText()).jsonArray.toList() else emptyList()
        val record = buildJsonObject {
            put("targetKey", targetKey)
            put("agentId", agentId)
            put("skillId", "review")
            put("fingerprint", skillTreeFingerprint(files))
            directoryId?.let { put("directoryId", it) }
        }
        stateFile.parent.createDirectories()
        stateFile.writeText(JsonArray(existing + record).toString())
        return directory
    }

    private fun snapshot(content: String, version: String, includeReference: Boolean = true): SkillSnapshot {
        val model = Skill("review", "review", version = version, content = content)
        val files = buildList {
            add(SkillFile("SKILL.md", content.encodeToByteArray()))
            if (includeReference) add(SkillFile("references/checklist.md", "Check tests.\n".encodeToByteArray()))
        }
        return SkillSnapshot(model, files)
    }

    @Test
    fun `a destination that cannot be written puts the earlier agent back`() {
        // A locked second destination must roll the first agent back to its previous version.
        service.install(projectTarget, skill)
        snapshots["review"] = snapshot("# Review, revised\n", version = "2")
        val second = project.resolve(".pi/skills/review/SKILL.md")
        blockFileReplacement(second).use {
            val outcome = runCatching { service.install(projectTarget, skill) }

            assertTrue(outcome.isFailure, "the blocked destination must fail the install")
            assertEquals(
                "# Review\n",
                project.resolve(".claude/skills/review/SKILL.md").readText(),
                "the first agent goes back to the version the operation found",
            )
        }
    }

    @Test
    fun `an assistant reading the skill during an update always finds it`() {
        // A reader polls the installed copy while the skill is updated over and over: the directory
        // and its SKILL.md must be there on every read, never briefly missing between two renames.
        service.install(projectTarget, skill)
        val watched = project.resolve(".claude/skills/review")
        val stop = AtomicBoolean(false)
        val misses = AtomicInteger()
        val reads = AtomicInteger()
        val readerFailure = AtomicReference<Throwable?>()
        val watcher = thread {
            while (!stop.get()) {
                try {
                    watched.resolve("SKILL.md").readBytes()
                    if (!Files.isDirectory(watched)) misses.incrementAndGet()
                    reads.incrementAndGet()
                    // Leave a sharing window between reads, as a real editor does.
                    Thread.sleep(1)
                } catch (_: NoSuchFileException) {
                    misses.incrementAndGet()
                } catch (error: Throwable) {
                    readerFailure.set(error)
                    break
                }
            }
        }
        try {
            repeat(300) { round ->
                snapshots["review"] = snapshot("# Review $round\n", version = "${round + 2}", includeReference = round % 2 == 0)
                service.install(projectTarget, skill)
            }
        } finally {
            stop.set(true)
            watcher.join()
        }

        assertNull(readerFailure.get(), "the concurrent reader must finish without errors")
        assertTrue(reads.get() > 0, "the reader must have run alongside the updates")
        assertEquals(0, misses.get(), "reads that found no skill out of ${reads.get() + misses.get()}")
    }

    @Test
    fun `a completed install leaves no rollback directories behind`() {
        service.install(projectTarget, skill)
        snapshots["review"] = snapshot("# Review, revised\n", version = "2")
        service.install(projectTarget, skill)

        val leftovers = Files.walk(project).use { paths ->
            paths.filter { it.fileName?.toString()?.contains("ruleblend-rollback") == true }.toList()
        }
        assertTrue(leftovers.isEmpty(), "kept trees are dropped once the whole set has landed: $leftovers")
    }

    @Test
    fun `a blocked later destination restores earlier trees and installation records`() {
        service.install(projectTarget, skill)
        val before = state.all()
        snapshots["review"] = snapshot("# Review, revised\n", version = "2")
        val blockedRollback = project.resolve(".codex/skills/.review.ruleblend-rollback")
        blockedRollback.createDirectories().resolve("SKILL.md").writeText("recover me")

        assertFailsWith<IllegalStateException> { service.install(projectTarget, skill) }

        assertEquals("# Review\n", project.resolve(".claude/skills/review/SKILL.md").readText())
        assertEquals("# Review\n", project.resolve(".codex/skills/review/SKILL.md").readText())
        assertEquals(before, state.all())
        assertEquals("recover me", blockedRollback.resolve("SKILL.md").readText())
    }

    @Test
    fun `an update without an origin keeps the one the copy was installed with`() {
        service.install(globalClaude, skill, origin = "g=bundle")
        service.install(globalClaude, skill)
        assertEquals("g=bundle", service.installedOrigins(globalClaude)["review"])

        service.install(globalClaude, skill, origin = "p=focus")
        assertEquals("p=focus", service.installedOrigins(globalClaude)["review"])
    }

    @Test
    fun `an orphaned copy goes when the library skill it came from is deleted`() {
        service.install(globalClaude, skill)
        snapshots.remove("review")

        val outcome = service.removeOrphan(globalClaude, "review")

        assertEquals(OrphanRemoval.REMOVED, outcome)
        assertFalse(home.resolve(".claude/skills/review").exists())
        assertTrue(service.installedOrigins(globalClaude).isEmpty())
    }

    @Test
    fun `a hand-edited orphan stays and is reported`() {
        service.install(globalClaude, skill)
        home.resolve(".claude/skills/review/SKILL.md").writeText("# Edited by hand\n")
        snapshots.remove("review")

        val outcome = service.removeOrphan(globalClaude, "review")

        assertEquals(OrphanRemoval.PROTECTED, outcome)
        assertEquals("# Edited by hand\n", home.resolve(".claude/skills/review/SKILL.md").readText())
    }

    @Test
    fun `removing a renamed orphan leaves a foreign directory with its library id`() {
        val target = globalClaude
        val orphan = home.resolve(".claude/skills/community").also { it.createDirectories() }
        orphan.resolve("SKILL.md").writeText("# Saved review\n")
        val foreign = home.resolve(".claude/skills/saved-review").also { it.createDirectories() }
        foreign.resolve("SKILL.md").writeText("# Foreign review\n")
        state.record(
            SkillInstallRecord(
                skillTargetKey(target),
                "claude-code",
                "saved-review",
                skillTreeFingerprint(LocalSkillCapture.files(orphan)),
                directoryName = "community",
            ),
        )

        assertEquals(OrphanRemoval.REMOVED, service.removeOrphan(target, "saved-review"))
        assertFalse(orphan.exists())
        assertEquals("# Foreign review\n", foreign.resolve("SKILL.md").readText())
    }
}
