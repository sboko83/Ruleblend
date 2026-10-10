package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.config.ConfigStore
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.deleteFixtureTree
import dev.ruleblend.core.exchange.ArchiveSelection
import dev.ruleblend.core.exchange.ChangeKind
import dev.ruleblend.core.exchange.LibraryArchive
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.LineEnding
import dev.ruleblend.core.model.Mod
import dev.ruleblend.core.model.ModMeta
import dev.ruleblend.core.model.ModSnapshot
import dev.ruleblend.core.model.ModSource
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.SkillFile
import dev.ruleblend.core.usecase.MutateLibrary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk

/** Offline phase-2 gate: portable snapshots only, with real bare-remote peers and ZIP bytes. */
class ModTransportTest {
    private val scratch = Files.createTempDirectory("ruleblend-mod-transport")

    @AfterTest
    fun cleanup() = deleteFixtureTree(scratch)

    private fun repository(name: String, ending: LineEnding = LineEnding.LF): Pair<LibraryRepository, LibraryGit> {
        val root = scratch.resolve(name)
        val git = LibraryGit(root, lineEnding = { ending })
        return LibraryRepository(root, git).also { it.init() } to git
    }

    private fun snapshot(id: String = "demo", version: String = "opaque-dev", body: String = "old\r\n"): ModSnapshot = ModSnapshot(
        Mod(id, "Demo", "demo", version, source = ModSource("https://example.com/mods.git", "a".repeat(40), "plugins/demo")),
        listOf(
            SkillFile(".claude-plugin/plugin.json", "{\"name\":\"demo\",\"version\":\"$version\"}\r\n".encodeToByteArray()),
            SkillFile("hooks/hooks.json", "{\"modules\":[\"./run.js\"]}\r\n".encodeToByteArray()),
            SkillFile("hooks/run.js", body.encodeToByteArray(), executable = true),
            SkillFile("assets/blob.bin", byteArrayOf(0, -1, 13, 10, -128)),
            SkillFile(".gitattributes", "*.js text eol=lf\n".encodeToByteArray()),
        ),
    )

    private fun assertSnapshot(expected: ModSnapshot, actual: ModSnapshot?) {
        requireNotNull(actual)
        assertEquals(expected.mod, actual.mod)
        assertEquals(expected.files.sortedBy { it.path }, actual.files.sortedBy { it.path })
        assertEquals(modTreeFingerprint(expected.files), modTreeFingerprint(actual.files))
    }

    @Test
    fun `storage preserves bytes modes and opaque version through replace and delete`() {
        val (repo, git) = repository("storage", LineEnding.CRLF)
        val initial = snapshot()
        repo.writeMod(initial)
        assertSnapshot(initial, repo.loadModSnapshot("demo"))
        assertFalse(scratch.resolve("storage/mods/demo/meta.yaml").readText().contains('\r'))
        val changed = snapshot(version = "not-numeric", body = "new\n").let {
            it.copy(files = it.files.filterNot { file -> file.path == "assets/blob.bin" })
        }
        repo.writeMod(changed)
        assertSnapshot(changed, repo.loadModSnapshot("demo"))
        assertFalse(scratch.resolve("storage/mods/demo/files/assets/blob.bin").exists())
        assertEquals(2, repo.modHistory("demo").size)
        assertTrue(repo.modDiff("demo", repo.modHistory("demo").first().id).contains("hooks/run.js"))
        repo.deleteMod("demo")
        assertNull(repo.loadMod("demo"))
        Git.open(scratch.resolve("storage").toFile()).use { assertTrue(it.status().call().isClean) }
        assertEquals(3, git.log(Path.of("mods/demo")).size)
    }

    @Test
    fun `old library loads defaults and mutation cleans mod membership without altering profiles`() {
        val (repo) = repository("legacy")
        val groupPath = scratch.resolve("legacy/groups/legacy.yaml")
        groupPath.writeText("id: legacy\nname: Legacy\nblockIds: []\nskillIds: []\nversion: 7\n")
        assertEquals(emptyList(), repo.loadGroup("legacy")!!.modIds)
        val mutation = MutateLibrary(repo, ConfigStore(scratch.resolve("config.json")))
        val saved = mutation.writeMod(snapshot())
        assertEquals(listOf("demo"), saved.library.mods.map { it.id })
        assertEquals(listOf("demo"), repo.loadGroup(ALL_GROUP_ID)!!.modIds)
        repo.saveGroup(repo.loadGroup("legacy")!!.copy(modIds = listOf("demo")))
        assertEquals(8, repo.loadGroup("legacy")!!.version)
        val profile = Profile("mode", "Mode", groupIds = listOf("legacy"))
        repo.saveProfile(profile)
        mutation.deleteMod("demo")
        assertTrue(repo.loadGroup("legacy")!!.modIds.isEmpty())
        assertTrue(repo.loadGroup(ALL_GROUP_ID)!!.modIds.isEmpty())
        assertEquals(9, repo.loadGroup("legacy")!!.version)
        assertEquals(profile, repo.loadProfile("mode"))
    }

    @Test
    fun `storage rejects unsafe ids paths modes links and oversized files before writing`() {
        val (repo) = repository("unsafe")
        listOf("../escape", "CON", "demo.", "a/b").forEach { id ->
            assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot(id)) }
            assertFailsWith<IllegalArgumentException> { repo.loadMod(id) }
        }
        listOf("../escape", "a\\b", "CON", ".git/config", "a/".repeat(17) + "b").forEach { path ->
            assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot().let { it.copy(files = it.files + SkillFile(path, byteArrayOf(1))) }) }
        }
        val duplicate = snapshot().let { it.copy(files = it.files + SkillFile("Hooks/run.js", byteArrayOf(2))) }
        assertFailsWith<IllegalArgumentException> { repo.writeMod(duplicate) }
        assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot().let {
            it.copy(files = it.files + SkillFile("big", ByteArray(SkillTreeLimits.MAX_FILE_BYTES + 1)))
        }) }
        assertTrue(repo.listMods().isEmpty())
        repo.writeMod(snapshot())
        assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot("DEMO")) }
        val file = scratch.resolve("unsafe/mods/demo/files/hooks/run.js")
        Files.delete(file)
        Files.createSymbolicLink(file, scratch.resolve("config.json"))
        assertFailsWith<IllegalArgumentException> { repo.loadModSnapshot("demo") }
    }

    @Test
    fun `metadata fingerprint manifest identity and execution intent are validated`() {
        val (repo) = repository("metadata")
        assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot().let { it.copy(mod = it.mod.copy(manifestName = "other")) }) }
        assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot().let { it.copy(mod = it.mod.copy(version = "other")) }) }
        assertFailsWith<IllegalArgumentException> { repo.writeMod(snapshot().let {
            it.copy(mod = it.mod.copy(source = it.mod.source!!.copy(revision = "HEAD")))
        }) }
        repo.writeMod(snapshot())
        val metaPath = scratch.resolve("metadata/mods/demo/meta.yaml")
        val meta = Yaml.default.decodeFromString(ModMeta.serializer(), metaPath.readText())
        metaPath.writeText(Yaml.default.encodeToString(ModMeta.serializer(), meta.copy(executableFiles = listOf("missing"))))
        assertFailsWith<IllegalArgumentException> { repo.loadModSnapshot("demo") }
        metaPath.writeText(Yaml.default.encodeToString(ModMeta.serializer(), meta))
        scratch.resolve("metadata/mods/demo/files/hooks/run.js").writeText("tampered")
        assertFailsWith<IllegalArgumentException> { repo.loadModSnapshot("demo") }
    }

    @Test
    fun `failed create update and deletion restore whole tree and index`() {
        val (repo) = repository("failure")
        val root = scratch.resolve("failure")
        val failing = object : LibraryGit(root) {
            override fun commit(message: String, relativePath: Path) {
                Git.open(root.toFile()).use { git ->
                    if (root.resolve(relativePath).exists()) git.add().addFilepattern(relativePath.joinToString("/")).call()
                    else git.rm().addFilepattern(relativePath.joinToString("/")).call()
                }
                throw IOException("Injected failure after staging")
            }
        }
        val broken = LibraryRepository(root, failing)
        assertFailsWith<IOException> { broken.writeMod(snapshot()) }
        assertNull(repo.loadMod("demo"))
        repo.writeMod(snapshot())
        assertFailsWith<IOException> { broken.writeMod(snapshot(body = "changed")) }
        assertSnapshot(snapshot(), repo.loadModSnapshot("demo"))
        assertFailsWith<IOException> { broken.deleteMod("demo") }
        assertSnapshot(snapshot(), repo.loadModSnapshot("demo"))
        Git.open(root.toFile()).use { assertTrue(it.status().call().isClean) }
        assertFalse(root.resolve("mods/.demo.rollback").exists())
    }

    @Test
    fun `locked readers see only complete snapshots during replacements`() {
        val (repo) = repository("readers")
        val old = snapshot()
        val new = snapshot(version = "next", body = "changed\n")
        repo.writeMod(old)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reader = pool.submit {
                repeat(40) {
                    val read = repo.loadModSnapshot("demo")!!
                    assertTrue(read == old.copy(files = old.files.sortedBy { it.path }) || read == new.copy(files = new.files.sortedBy { it.path }))
                }
            }
            val writer = pool.submit { repeat(12) { repo.writeMod(if (it % 2 == 0) new else old) } }
            reader.get(30, TimeUnit.SECONDS)
            writer.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `fingerprint framing and UTF8 sorting match published v1 contract`() {
        // This vector was generated independently with Python hashlib and struct.pack('>Q').
        val files = listOf(SkillFile("z", byteArrayOf(0, -1), true), SkillFile("a", "x\r\n".encodeToByteArray()))
        assertEquals("9e1b9dccf6572f6034e688b95fcfa9029f0c2bdde37d0ca751ae8b88e01cdf3b", modTreeFingerprint(files))
        assertEquals(modTreeFingerprint(files), modTreeFingerprint(files.reversed()))
        assertNotEquals(modTreeFingerprint(files), modTreeFingerprint(files.map { it.copy(executable = !it.executable) }))
        assertNotEquals(modTreeFingerprint(files), modTreeFingerprint(files.map { it.copy(bytes = it.bytes + byteArrayOf(0)) }))
        val unicode = listOf(SkillFile("\uD800\uDC00", byteArrayOf(1)), SkillFile("\uE000", byteArrayOf(2)))
        assertEquals("9adbbf1910868088ef73f78b445a2ae1299c14bb2197e87e48da8696b96b9b5e", modTreeFingerprint(unicode))
    }

    @Test
    fun `ZIP roundtrip selective group and profile closure exclude machine state`() {
        val (repo) = repository("archive")
        val root = scratch.resolve("archive")
        repo.writeMod(snapshot())
        repo.writeMod(snapshot("unselected"))
        repo.saveGroup(Group("bundle", "Bundle", modIds = listOf("demo")))
        repo.saveProfile(Profile("profile", "Profile", groupIds = listOf("bundle")))
        repo.syncAllGroup()
        listOf("config.json", "mod-state.json", "mod-runtime/secret.json", "mods/demo/options.json").forEach { path ->
            root.resolve(path).parent?.let(Files::createDirectories)
            root.resolve(path).writeText("machine secret")
        }
        val zip = scratch.resolve("mods.zip")
        val archive = LibraryArchive(root, repo)
        archive.export(zip, ArchiveSelection(profileIds = setOf("profile")))
        ZipFile(zip.toFile()).use { input ->
            val names = input.entries().asSequence().map { it.name }.toList()
            assertTrue(names.all { it.startsWith("mods/demo/") || it == "groups/bundle.yaml" || it == "profiles/profile.yaml" })
            assertFalse(names.any { it.contains("secret") || it.contains("options") || it.contains(".git/") || it.contains("all.yaml") })
        }
        val (target) = repository("import")
        val incoming = LibraryArchive(scratch.resolve("import"), target)
        val plan = incoming.plan(zip)
        assertEquals(ChangeKind.NEW, plan.mods.single().kind)
        incoming.apply(plan, emptySet(), setOf("bundle"), profileIds = setOf("profile"), modIds = setOf("demo"))
        assertSnapshot(snapshot(), target.loadModSnapshot("demo"))
        assertEquals(listOf("demo"), target.loadGroup("bundle")!!.modIds)
        assertEquals(ChangeKind.SAME, incoming.plan(zip).mods.single().kind)
        target.writeMod(snapshot(body = "local"))
        assertEquals(ChangeKind.CONFLICT, incoming.plan(zip).mods.single().kind)
        archive.export(zip, ArchiveSelection(modIds = setOf("demo")))
        assertEquals(1, archive.read(zip).mods.size)
    }

    @Test
    fun `ZIP rejects unexpected runtime escaping case collisions corrupt fingerprints and mismatched ids`() {
        val (repo) = repository("zip-rejection")
        val archive = LibraryArchive(scratch.resolve("zip-rejection"), repo)
        val initial = snapshot()
        val meta = ModMeta(initial.mod, modTreeFingerprint(initial.files), listOf("hooks/run.js"))
        val entries = listOf("mods/demo/meta.yaml" to Yaml.default.encodeToString(ModMeta.serializer(), meta).encodeToByteArray()) +
            initial.files.map { "mods/demo/files/${it.path}" to it.bytes }
        val invalid = listOf(
            entries + ("mods/demo/options.json" to byteArrayOf(1)),
            entries + ("mod-runtime/secret" to byteArrayOf(1)),
            entries + ("mods/demo/files/../escape" to byteArrayOf(1)),
            entries + ("mods/demo/files/Hooks/other.js" to byteArrayOf(1)),
            entries.map { (name, bytes) -> name to if (name.endsWith("run.js")) byteArrayOf(2) else bytes },
            entries.map { (name, bytes) -> name.replace("mods/demo/", "mods/other/") to bytes },
            entries.filterNot { it.first.endsWith("meta.yaml") },
            entries + ("mods/demo/files/CON" to byteArrayOf(1)),
        )
        invalid.forEachIndexed { index, contents ->
            val zip = scratch.resolve("bad-$index.zip")
            writeZip(zip, contents)
            assertFailsWith<IllegalArgumentException> { archive.plan(zip) }
            assertTrue(repo.listMods().isEmpty())
        }
    }

    @Test
    fun `two bare remote peers exchange add update delete byte exact snapshots and LF metadata`() {
        val peer = peers()
        peer.first.writeMod(snapshot())
        peer.first.syncAllGroup()
        peer.firstGit.sync(peer.config)
        peer.secondGit.sync(peer.config)
        assertSnapshot(snapshot(), peer.second.loadModSnapshot("demo"))
        val changed = snapshot(version = "new", body = "new\r\n").let { it.copy(files = it.files.filterNot { file -> file.path == "assets/blob.bin" }) }
        peer.second.writeMod(changed)
        peer.secondGit.sync(peer.config)
        peer.firstGit.sync(peer.config)
        assertSnapshot(changed, peer.first.loadModSnapshot("demo"))
        assertEquals(scratch.resolve("first/mods/demo/meta.yaml").readBytes().toList(), scratch.resolve("second/mods/demo/meta.yaml").readBytes().toList())
        assertFalse(scratch.resolve("second/mods/demo/meta.yaml").readText().contains('\r'))
        peer.second.deleteMod("demo")
        peer.second.syncAllGroup()
        peer.secondGit.sync(peer.config)
        peer.firstGit.sync(peer.config)
        assertNull(peer.first.loadMod("demo"))
        assertTrue(peer.first.loadGroup(ALL_GROUP_ID)!!.modIds.isEmpty())
    }

    @Test
    fun `concurrent disjoint file updates choose a whole local package and retain remote alternative`() {
        val peer = peers(seed = snapshot())
        val local = snapshot(body = "local\r\n")
        val remote = snapshot().let { it.copy(files = it.files.map { file ->
            if (file.path == "assets/blob.bin") file.copy(bytes = byteArrayOf(42)) else file
        }) }
        peer.first.writeMod(local)
        peer.second.writeMod(remote)
        peer.secondGit.sync(peer.config)
        val result = peer.firstGit.sync(peer.config)
        assertEquals(listOf(RemoteSyncConflict("demo", RemoteSyncConflictKind.MOD)), result.conflicts)
        assertSnapshot(local, peer.first.loadModSnapshot("demo"))
        peer.secondGit.sync(peer.config)
        assertSnapshot(local, peer.second.loadModSnapshot("demo"))
        assertAlternativeInParent(remote)
    }

    @Test
    fun `peers add separate packages and rebuild stable all membership without conflicts`() {
        val peer = peers()
        peer.first.writeMod(snapshot("alpha"))
        peer.second.writeMod(snapshot("beta"))
        peer.first.syncAllGroup()
        peer.second.syncAllGroup()
        listOf("config.json", "mod-state.json", "mod-runtime/secret.json", "mods/alpha/options.json").forEach { path ->
            val file = scratch.resolve("first").resolve(path)
            Files.createDirectories(file.parent)
            file.writeText("machine secret")
        }
        peer.secondGit.sync(peer.config)
        assertTrue(peer.firstGit.sync(peer.config).conflicts.isEmpty())
        peer.secondGit.sync(peer.config)
        assertEquals(listOf("alpha", "beta"), peer.first.loadGroup(ALL_GROUP_ID)!!.modIds)
        assertEquals(peer.first.loadGroup(ALL_GROUP_ID), peer.second.loadGroup(ALL_GROUP_ID))
        assertSnapshot(snapshot("alpha"), peer.second.loadModSnapshot("alpha"))
        assertSnapshot(snapshot("beta"), peer.first.loadModSnapshot("beta"))
        assertEquals(RemoteSyncStatus.UP_TO_DATE, peer.firstGit.sync(peer.config).status)
        assertEquals(RemoteSyncStatus.UP_TO_DATE, peer.secondGit.sync(peer.config).status)
        Git.open(scratch.resolve("remote.git").toFile()).use { git ->
            RevWalk(git.repository).use { revisions ->
                val head = revisions.parseCommit(git.repository.resolve("refs/heads/main"))
                TreeWalk(git.repository).use { tree ->
                    tree.addTree(head.tree)
                    tree.isRecursive = true
                    while (tree.next()) assertFalse(tree.pathString.contains("options.json") ||
                        tree.pathString.contains("secret") || tree.pathString == "config.json" || tree.pathString == "mod-state.json")
                }
            }
        }
    }

    @Test
    fun `modify delete restores complete survivor in either direction`() {
        val peer = peers(seed = snapshot())
        val modified = snapshot(body = "survives\n")
        peer.first.deleteMod("demo")
        peer.second.writeMod(modified)
        peer.secondGit.sync(peer.config)
        val result = peer.firstGit.sync(peer.config)
        assertTrue(result.conflicts.any { it.kind == RemoteSyncConflictKind.MOD && it.restored })
        assertSnapshot(modified, peer.first.loadModSnapshot("demo"))
        peer.secondGit.sync(peer.config)
        peer.first.writeMod(snapshot(body = "survives again"))
        peer.second.deleteMod("demo")
        peer.secondGit.sync(peer.config)
        val other = peer.firstGit.sync(peer.config)
        assertTrue(other.conflicts.any { it.kind == RemoteSyncConflictKind.MOD && it.restored })
        peer.secondGit.sync(peer.config)
        assertSnapshot(snapshot(body = "survives again"), peer.second.loadModSnapshot("demo"))
    }

    @Test
    fun `payload line endings are real conflicting edits and package path type changes stay whole`() {
        val peer = peers(seed = snapshot())
        val local = snapshot(body = "old\n").let { it.copy(files = it.files.filterNot { file -> file.path == "assets/blob.bin" } + SkillFile("assets", byteArrayOf(3))) }
        val remote = snapshot(body = "remote\n")
        peer.first.writeMod(local)
        peer.second.writeMod(remote)
        peer.secondGit.sync(peer.config)
        assertTrue(peer.firstGit.sync(peer.config).conflicts.any { it.kind == RemoteSyncConflictKind.MOD })
        assertSnapshot(local, peer.first.loadModSnapshot("demo"))
        peer.secondGit.sync(peer.config)
        assertSnapshot(local, peer.second.loadModSnapshot("demo"))
    }

    private data class Peers(val first: LibraryRepository, val firstGit: LibraryGit, val second: LibraryRepository, val secondGit: LibraryGit, val config: RemoteGitConfig)

    private fun peers(seed: ModSnapshot? = null): Peers {
        val remote = scratch.resolve("remote.git")
        Git.init().setBare(true).setDirectory(remote.toFile()).call().close()
        val config = RemoteGitConfig(remote.toUri().toString(), "main")
        val (first, firstGit) = repository("first")
        if (seed != null) first.writeMod(seed)
        first.syncAllGroup()
        firstGit.sync(config)
        val (second, secondGit) = repository("second", LineEnding.CRLF)
        secondGit.sync(config)
        return Peers(first, firstGit, second, secondGit, config)
    }

    private fun assertAlternativeInParent(expected: ModSnapshot) {
        Git.open(scratch.resolve("first").toFile()).use { git ->
            RevWalk(git.repository).use { revisions ->
                val merge = revisions.parseCommit(git.repository.resolve("HEAD"))
                assertEquals(2, merge.parentCount)
                val found = merge.parents.any { parent ->
                    val tree = revisions.parseCommit(parent).tree
                    val meta = TreeWalk.forPath(git.repository, "mods/demo/meta.yaml", tree)?.use { walk ->
                        Yaml.default.decodeFromString(ModMeta.serializer(), git.repository.open(walk.getObjectId(0)).bytes.decodeToString())
                    }
                    meta?.mod == expected.mod && meta.fingerprint == modTreeFingerprint(expected.files) &&
                        meta.executableFiles.toSet() == expected.files.filter { it.executable }.map { it.path }.toSet() &&
                        expected.files.all { file ->
                        TreeWalk.forPath(git.repository, "mods/demo/files/${file.path}", tree)?.use { walk ->
                            git.repository.open(walk.getObjectId(0)).bytes.contentEquals(file.bytes)
                        } == true
                    }
                }
                assertTrue(found, "Remote alternative remains complete in a merge parent")
            }
        }
    }

    private fun writeZip(zip: Path, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(Files.newOutputStream(zip)).use { out -> entries.forEach { (name, bytes) ->
            out.putNextEntry(ZipEntry(name))
            out.write(bytes)
            out.closeEntry()
        } }
    }
}
