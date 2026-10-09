package dev.ruleblend.core.storage

import com.charleskorn.kaml.Yaml
import dev.ruleblend.core.config.RemoteGitConfig
import dev.ruleblend.core.model.ALL_GROUP_ID
import dev.ruleblend.core.model.Group
import dev.ruleblend.core.model.Profile
import dev.ruleblend.core.model.LineEnding
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.RevWalkUtils
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.io.AutoCRLFOutputStream

/** Git-backed history of the library. One commit per save. [open] so tests can inject a failing commit. */
open class LibraryGit(
    private val root: Path,
    private val interProcessLock: InterProcessLock? = null,
    private val remoteConfig: () -> RemoteGitConfig? = { null },
    private val hostname: () -> String = ::localHostname,
    private val lineEnding: () -> LineEnding = { LineEnding.LF },
) {
    private val lineEndings = LibraryLineEndings(root)

    fun formatText(text: String): String = lineEnding().apply(text)

    /** Applies the local output choice and migrates portable definitions without changing versions. */
    fun configureLineEndings(ending: LineEnding = lineEnding()) = withLock {
        open().use { prepareLineEndings(it, ending) }
    }

    /**
     * The library's single write lock. Git is the serialization point — staging and committing are
     * two steps over a shared index, so concurrent writers could commit each other's staged paths.
     * [LibraryRepository] takes the same lock around its read-modify-write cycles, which makes
     * version bumps atomic too. Reentrant, so repository → git nesting is fine.
     *
     * [interProcessLock] extends the guarantee across processes (GUI app + MCP server); `null`
     * keeps the historical in-process-only behavior for tests.
     */
    private val lock = ReentrantLock()

    /**
     * Where automatic pushes run. A remote can be slow or unreachable, and a save must not wait for
     * it: the local commit is the operation the user asked for, the push is a backup that follows.
     * One thread, so pushes never overlap; daemon, so a pending push cannot hold the app open —
     * whatever it would have carried is carried by the next one.
     */
    private val remoteSyncWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ruleblend-remote-sync").apply { isDaemon = true }
    }

    /**
     * Serializes whole sync operations. The worker runs one at a time, but Home and Settings call
     * [sync] straight from their own threads, and two merges over one index corrupt each other.
     */
    private val remoteLock = ReentrantLock()

    /** True while a push is queued but not started; a second one would push the same `HEAD` twice. */
    private val remoteSyncQueued = AtomicBoolean(false)

    /** The focus trigger has its own throttle; a save and an app launch must not consume it. */
    private val focusSyncLock = Any()
    private var lastFocusSyncRequestAtMillis = Long.MIN_VALUE

    fun <T> withLock(body: () -> T): T = lock.withLock {
        interProcessLock?.withLock(body) ?: body()
    }

    fun init() {
        withLock {
            if (!root.resolve(".git").exists()) {
                Git.init().setDirectory(root.toFile()).call().close()
            }
            val gitignore = root.resolve(GITIGNORE_FILE)
            if (!gitignore.exists()) AtomicWrite.write(gitignore, GITIGNORE_CONTENT)
            open().use { prepareLineEndings(it) }
        }
    }

    /** Stages [relativePath] (added, modified or deleted) and commits it. */
    open fun commit(message: String, relativePath: Path): Unit = withLock {
        open().use { git ->
            prepareLineEndings(git)
            val pattern = relativePath.joinToString("/") { it.toString() }
            if (root.resolve(relativePath).exists()) {
                git.add().addFilepattern(pattern).call()
                // `add` does not stage files removed from an existing directory. Imported skills
                // replace complete trees, so update the same path to record upstream deletions too.
                git.add().setUpdate(true).addFilepattern(pattern).call()
            } else {
                git.rm().addFilepattern(pattern).call()
            }
            if (git.status().addPath(pattern).call().isClean) return@withLock
            stageManagedGitignore(git)
            val device = "Ruleblend @ ${hostname()}"
            git.commit()
                .setMessage(message)
                .setAuthor(device, COMMIT_EMAIL)
                .setCommitter(device, COMMIT_EMAIL)
                .setAllowEmpty(false)
                .call()
        }
        scheduleAutomaticSyncAfterSave()
    }

    /** Downloads [config]'s branch into the private tracking ref used by [syncDown]. */
    fun fetch(config: RemoteGitConfig) {
        val normalized = validateRemoteConfig(config)
        try {
            open().use { git ->
                withGitCredentials { credentials ->
                    git.fetch()
                        .setRemote(normalized.url)
                        .setRefSpecs(
                            RefSpec(
                                "+refs/heads/${normalized.branch}:refs/remotes/$REMOTE_NAME/${normalized.branch}",
                            ),
                        )
                        .setCredentialsProvider(credentials)
                        .setTimeout(10)
                        .call()
                }
            }
        } catch (error: TransportException) {
            // A just-created bare remote has no branch yet. It is not a network failure and the
            // following push establishes the branch; every other transport failure still reaches UI.
            if (!error.message.orEmpty().contains("does not have refs/heads/${normalized.branch} available for fetch")) throw error
        }
    }

    /**
     * Brings the configured branch into the local library before a push. Fast-forwards and clean
     * merges keep Git's result. A content conflict keeps the local revision as the next numeric
     * revision; a delete-vs-modify conflict keeps the surviving edit. The reserved `all` group is
     * rebuilt from the resulting object set instead of being text-merged.
     *
     * This deliberately runs on the remote worker, outside [withLock]: local saves retain their
     * ordinary short write transaction and a slow remote cannot block them.
     */
    fun syncDown(config: RemoteGitConfig, allowUnrelatedHistories: Boolean = false): RemoteSyncResult {
        val normalized = validateRemoteConfig(config)
        return remoteLock.withLock {
            if (cloneEmptyLibrary(normalized)) return@withLock RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE)
            val configured = root.resolve(".git").exists() && Git.open(root.toFile()).use {
                lineEndings.isConfigured(it, lineEnding())
            }
            if (!configured) init()
            fetch(normalized)
            mergeRemote(normalized, allowUnrelatedHistories)
        }
    }

    /**
     * Decides whether anything has to be merged at all before taking any lock. Reading refs is cheap
     * and touches nothing, so the ordinary sync — one that has only local work to push — never
     * competes with a save for the write lock.
     */
    private fun mergeRemote(config: RemoteGitConfig, allowUnrelatedHistories: Boolean): RemoteSyncResult {
        val settled = open().use { git ->
            val repository = git.repository
            if (repository.repositoryState != RepositoryState.SAFE) return@use null
            val local = repository.resolve("HEAD") ?: return@use RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE)
            val remote = repository.resolve(remoteRef(config)) ?: return@use RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE)
            syncProgress(repository, local, remote).takeIf { isAncestor(repository, remote, local) }?.result()
        }
        if (settled != null) return settled
        // Resolving remote work into the index and the working tree does need the write lock: a save
        // landing mid-merge would commit the half-resolved index under its own message, and git
        // refuses to commit at all while conflicts are still staged. Only this local step is locked —
        // the fetch above and the push after it stay outside, so no save ever waits on the network.
        return withLock { mergeLocked(config, allowUnrelatedHistories) }
    }

    private fun mergeLocked(config: RemoteGitConfig, allowUnrelatedHistories: Boolean): RemoteSyncResult =
        open().use { git ->
            val repository = git.repository
            discardIncompleteMerge(git)
            prepareLineEndings(git)
            val local = repository.resolve("HEAD") ?: return RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE)
            val remote = repository.resolve(remoteRef(config)) ?: return RemoteSyncResult(RemoteSyncStatus.UP_TO_DATE)
            val progress = syncProgress(repository, local, remote)
            if (isAncestor(repository, remote, local)) return progress.result()

            val base = commonMergeBase(repository, local, remote)
            if (base == null && !allowUnrelatedHistories) throw UnrelatedLibraryHistories(config)
            val divergent = !isAncestor(repository, local, remote)
            try {
                val merge = git.merge().include(remote).apply {
                    if (divergent) {
                        setFastForward(MergeCommand.FastForwardMode.NO_FF)
                        setCommit(false)
                    }
                }.call()
                check(merge.mergeStatus != org.eclipse.jgit.api.MergeResult.MergeStatus.FAILED) {
                    "Could not merge the library remote: ${merge.mergeStatus}"
                }
                val conflicts = buildSet {
                    if (merge.conflicts != null) addAll(resolveConflicts(git, local, remote, merge.conflicts.keys))
                    addAll(reconcileObjectConflicts(git, base, local, remote))
                }
                rebuildAllGroup()
                stageAllLibraryContent(git)
                if (!git.status().call().isClean || repository.repositoryState != RepositoryState.SAFE) {
                    git.commit()
                        .setMessage("Sync library from ${config.branch}")
                        .setAuthor(deviceIdentity())
                        .setCommitter(deviceIdentity())
                        .setAllowEmpty(true)
                        .call()
                }
                lineEndings.normalizeCleanDefinitions(git, lineEnding()).forEach { path ->
                    git.add().addFilepattern(path).call()
                }
                return progress.copy(merged = divergent, conflicts = conflicts.sortedWith(remoteConflictOrder)).result()
            } catch (failure: Exception) {
                // Whatever went wrong, the library must stay writable: an abandoned MERGE_HEAD makes
                // git reject every later save and every later sync until someone runs git by hand.
                discardIncompleteMerge(git)
                throw failure
            }
        }

    private fun prepareLineEndings(git: Git, ending: LineEnding = lineEnding()) {
        val repository = git.repository
        if (repository.repositoryState != RepositoryState.SAFE) return
        val head = repository.resolve("HEAD")
        val attributesPath = root.resolve(LibraryLineEndings.ATTRIBUTES_FILE)
        val previousStatus = git.status().addPath(LibraryLineEndings.ATTRIBUTES_FILE).call()
        val preserveAttributeEdit = head != null && attributesPath.exists() && !previousStatus.isClean &&
            attributesPath.toFile().readText() != LibraryLineEndings.MANAGED_ATTRIBUTES
        val localAttributes = repository.directory.toPath().resolve("info/attributes")
        val needsLegacyRepair = repository.config.getString("core", null, "autocrlf") == "true" ||
            !localAttributes.takeIf { it.exists() }?.toFile()?.readText().orEmpty()
                .contains(LibraryLineEndings.MANAGED_ATTRIBUTES)
        val repaired = if (needsLegacyRepair && head != null) normalizeLegacyCheckout(git, head) else emptyList()
        lineEndings.configure(git, ending)
        val paths = (lineEndings.normalizeCleanDefinitions(git, ending) + repaired +
            listOfNotNull(LibraryLineEndings.ATTRIBUTES_FILE.takeUnless { preserveAttributeEdit })).distinct()
        paths.forEach { git.add().addFilepattern(it).call() }
        if (head == null) return
        val status = git.status().call()
        val changed = paths.filter { it in status.changed || it in status.added }
        if (changed.isEmpty()) return
        git.commit().apply { changed.forEach(::setOnly) }
            .setMessage("Normalize library line endings")
            .setAuthor(deviceIdentity())
            .setCommitter(deviceIdentity())
            .call()
    }

    /** Undo old Git-for-Windows checkout conversion only when the committed bytes prove it safe. */
    private fun normalizeLegacyCheckout(git: Git, local: ObjectId): List<String> {
        val repository = git.repository
        val index = repository.readDirCache()
        val repaired = mutableListOf<String>()
        RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { tree ->
                tree.addTree(revisions.parseCommit(local).tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val path = tree.pathString
                    when (path.substringBefore('/')) {
                        "blocks", "groups", "profiles", "skills", GITIGNORE_FILE -> Unit
                        else -> continue
                    }
                    val mode = tree.getFileMode(0)
                    if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                    val blobId = tree.getObjectId(0)
                    if (index.getEntry(path)?.objectId != blobId) continue
                    val target = root.resolve(path)
                    if (!Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                    val blob = repository.open(blobId)
                    if (Files.size(target) <= blob.size) continue
                    val committed = blob.bytes
                    if (path == GITIGNORE_FILE && !committed.contentEquals(GITIGNORE_CONTENT.encodeToByteArray())) continue
                    val converted = ByteArrayOutputStream().also { output ->
                        AutoCRLFOutputStream(output, true).use { it.write(committed) }
                    }.toByteArray()
                    if (converted.contentEquals(committed)) continue
                    val current = Files.readAllBytes(target)
                    if (current.contentEquals(converted)) {
                        if (AtomicWrite.writeIfUnchanged(target, committed, FileIdentity.of(current))) repaired += path
                    }
                }
            }
        }
        return repaired
    }

    /**
     * Returns the repository to a state where a merge can start and a save can commit. Called
     * before merging as well, so a library left mid-merge by an older failure repairs itself.
     */
    private fun discardIncompleteMerge(git: Git) {
        if (git.repository.repositoryState == RepositoryState.SAFE) return
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
    }

    /** Fetches and merges first, then pushes; a rejected push gets one fresh down-sync and retry. */
    fun sync(config: RemoteGitConfig, allowUnrelatedHistories: Boolean = false): RemoteSyncResult = remoteLock.withLock {
        syncLocked(config, allowUnrelatedHistories)
    }

    private fun syncLocked(config: RemoteGitConfig, allowUnrelatedHistories: Boolean): RemoteSyncResult = try {
        val firstDown = syncDown(config, allowUnrelatedHistories)
        val result = try {
            pushHead(config).withProgress(firstDown)
        } catch (_: RetryablePush) {
            val retryDown = syncDown(config, allowUnrelatedHistories)
            pushHead(config).withProgress(firstDown.combine(retryDown))
        }
        lastRemoteSync = result
        result
    } catch (failure: Exception) {
        lastRemoteSync = remoteSyncFailed(failure)
        throw failure
    }

    /** Compatibility entry point for Settings and existing callers. */
    fun syncRemote(config: RemoteGitConfig, allowUnrelatedHistories: Boolean = false): RemoteSyncResult = sync(config, allowUnrelatedHistories)

    /** Starts the configured automatic sync once the desktop app has opened. */
    fun scheduleAutomaticSyncOnLaunch(): Boolean = scheduleAutomaticSync()

    /**
     * Starts an automatic sync after the window regains focus, at most once per five minutes.
     * The result says whether this focus event actually queued work, which keeps the throttle
     * observable without making callers inspect the worker.
     */
    fun scheduleAutomaticSyncOnWindowFocus(now: Long = System.currentTimeMillis()): Boolean = synchronized(focusSyncLock) {
        if (
            lastFocusSyncRequestAtMillis != Long.MIN_VALUE &&
            now - lastFocusSyncRequestAtMillis < FocusSyncIntervalMillis
        ) return@synchronized false
        scheduleAutomaticSync().also { scheduled ->
            if (scheduled) lastFocusSyncRequestAtMillis = now
        }
    }

    /**
     * Replaces this library with [config]'s branch. The caller must export first: this method
     * deliberately knows nothing about archive destinations, so storage never chooses where a
     * user's recovery copy is written.
     */
    fun replaceWithRemote(config: RemoteGitConfig) {
        val normalized = validateRemoteConfig(config)
        withLock { cloneRemoteIntoRoot(normalized, replaceExisting = true) }
    }

    /**
     * The push itself, deliberately outside [withLock]: it reads local refs and objects and writes
     * nothing here, so a save made while it runs simply lands in the next push instead of queueing
     * behind this one.
     */
    private fun pushHead(config: RemoteGitConfig): RemoteSyncResult {
        val normalized = validateRemoteConfig(config)
        return open().use { git ->
            val destination = "refs/heads/${normalized.branch}"
            val results = withGitCredentials { credentials ->
                git.push()
                    .setRemote(normalized.url)
                    .setRefSpecs(RefSpec("HEAD:$destination"))
                    .setCredentialsProvider(credentials)
                    .setTimeout(10)
                    .call()
            }.flatMap { it.remoteUpdates }.toList()
            val rejected = results.firstOrNull {
                it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE
            }
            if (rejected != null) {
                val message = rejected.message ?: "Remote rejected ${rejected.remoteName}: ${rejected.status}"
                if (isRetryablePushFailure(rejected.status, rejected.message)) {
                    throw RetryablePush(message)
                }
                error(message)
            }
            val changed = results.any { it.status == RemoteRefUpdate.Status.OK }
            RemoteSyncResult(if (changed) RemoteSyncStatus.PUSHED else RemoteSyncStatus.UP_TO_DATE)
        }
    }

    /**
     * Hands a complete down-sync and push to [remoteSyncWorker] and returns. At most one sync waits
     * at a time: it carries every commit made before it starts, so a second queued sync would repeat it.
     */
    private fun scheduleAutomaticSyncAfterSave() {
        scheduleAutomaticSync()
    }

    private fun scheduleAutomaticSync(): Boolean {
        val config = remoteConfig()?.takeIf { it.automatic } ?: return false
        return scheduleRemoteSync(config)
    }

    private fun scheduleRemoteSync(config: RemoteGitConfig): Boolean {
        if (!remoteSyncQueued.compareAndSet(false, true)) return false
        return try {
            remoteSyncWorker.execute {
                remoteSyncQueued.set(false)
                trySyncRemote(config)
            }
            true
        } catch (_: Exception) {
            remoteSyncQueued.set(false)
            false
        }
    }

    /** Automatic sync is best-effort: the local commit has already succeeded and stays committed. */
    private fun trySyncRemote(config: RemoteGitConfig) {
        runCatching { sync(config) }
    }

    /** Strict barrier for tests which assert what reached a remote. */
    fun awaitRemoteSync(timeoutMillis: Long = 30_000) {
        remoteSyncWorker.submit { }.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /**
     * UI-only wait for the latest status. Timing out leaves the best-effort background sync running;
     * it must never turn an already completed local save into an application failure.
     */
    fun awaitRemoteSyncSafely(timeoutMillis: Long = 30_000): Boolean =
        remoteSyncWorker.submit { }.completesWithin(timeoutMillis)

    @Volatile
    var lastRemoteSync: RemoteSyncResult? = null
        private set

    /** Commits touching [relativePath], newest first. */
    fun log(relativePath: Path): List<RevCommit> = withLock {
        open().use { git ->
            git.log().addPath(relativePath.joinToString("/") { it.toString() }).call().toList()
        }
    }

    /**
     * Unified diff of what [commitId] did to [relativePath], against the commit's first parent. The
     * first commit of a path has no parent to compare against, so it diffs against the empty tree —
     * which is what "created here" looks like, and the only honest answer for it.
     *
     * A path is matched with everything under it, so a skill's directory diffs as one change set.
     */
    open fun diff(commitId: String, relativePath: Path): String = withLock {
        open().use { git ->
            val repository = git.repository
            val path = relativePath.joinToString("/") { it.toString() }
            RevWalk(repository).use { walk ->
                val commit = walk.parseCommit(repository.resolve(commitId))
                val parentTree = commit.parents.firstOrNull()?.let { walk.parseCommit(it).tree }
                val output = ByteArrayOutputStream()
                DiffFormatter(output).use { formatter ->
                    formatter.setRepository(repository)
                    formatter.pathFilter = PathFilter.create(path)
                    val reader = repository.newObjectReader()
                    val oldTree = parentTree?.let { CanonicalTreeParser(null, reader, it) } ?: EmptyTreeIterator()
                    val newTree = CanonicalTreeParser(null, reader, commit.tree)
                    formatter.format(formatter.scan(oldTree, newTree))
                }
                output.toString(Charsets.UTF_8)
            }
        }
    }

    /** Exact versions of a file on both sides of a revision, including deleted files. */
    fun fileVersions(commitId: String, relativePath: Path): List<String> = withLock {
        open().use { git ->
            RevWalk(git.repository).use { walk ->
                val commit = walk.parseCommit(git.repository.resolve(commitId))
                val path = relativePath.joinToString("/") { it.toString() }
                (listOf(commit) + commit.parents.take(1).map { walk.parseCommit(it) }).mapNotNull {
                    treeText(git.repository, it.id, path)
                }
            }
        }
    }

    /** Whole-library statistics; an unborn repository has zero commits. */
    fun history(): LibraryHistory = withLock {
        if (!root.resolve(".git").exists()) return@withLock LibraryHistory(0, null)
        open().use { git ->
            if (git.repository.resolve("HEAD") == null) return@use LibraryHistory(0, null)
            var commits = 0
            var newest: Long? = null
            git.log().call().forEach { commit ->
                commits++
                if (newest == null) newest = commit.commitTime * 1000L
            }
            LibraryHistory(commits, newest)
        }
    }

    private fun open(): Git = Git.open(root.toFile())

    /** A remote with content is the source of a fresh library, not an upstream to push over. */
    private fun cloneEmptyLibrary(config: RemoteGitConfig): Boolean {
        if (!isEmptyLibrary()) return false
        return withLock {
            if (!isEmptyLibrary()) return@withLock false
            if (!remoteBranchExists(config)) return@withLock false
            cloneRemoteIntoRoot(config, replaceExisting = root.exists())
            true
        }
    }

    private fun isEmptyLibrary(): Boolean {
        if (!root.exists()) return true
        if (!root.resolve(".git").exists()) return false
        val files = Files.list(root).use { entries ->
            entries.toList().filter { it.fileName.toString() != ".git" }.flatMap { entry ->
                Files.walk(entry).use { descendants -> descendants.filter(Files::isRegularFile).toList() }
            }
        }
        if (files.any { root.relativize(it).toString().replace('\\', '/') !in
                setOf(GITIGNORE_FILE, LibraryLineEndings.ATTRIBUTES_FILE, "groups/all.yaml") }) return false
        val attributes = root.resolve(LibraryLineEndings.ATTRIBUTES_FILE)
        if (attributes.exists() && attributes.toFile().readText() != LibraryLineEndings.MANAGED_ATTRIBUTES) return false
        return open().use { git ->
            if (git.repository.resolve("HEAD") == null) return@use true
            if (!git.status().call().isClean) return@use false
            // Opening Settings can commit the derived empty `all` group before the first sync.
            // No user object exists and this sole bootstrap commit is safe to replace by the peer.
            files.any { root.relativize(it).toString().replace('\\', '/') == "groups/all.yaml" } &&
                git.log().setMaxCount(2).call().count() == 1
        }
    }

    private fun remoteBranchExists(config: RemoteGitConfig): Boolean = withGitCredentials { credentials ->
        Git.lsRemoteRepository()
            .setRemote(config.url)
            .setHeads(true)
            .setCredentialsProvider(credentials)
            .setTimeout(10)
            .call()
            .any { it.name == "refs/heads/${config.branch}" }
    }

    private fun cloneRemoteIntoRoot(config: RemoteGitConfig, replaceExisting: Boolean) {
        val parent = root.parent ?: error("Library path has no parent: $root")
        Files.createDirectories(parent)
        val staging = parent.resolve(".${root.fileName}.clone-${UUID.randomUUID()}")
        try {
            withGitCredentials { credentials ->
                Git.cloneRepository()
                    .setURI(config.url)
                    .setBranch("refs/heads/${config.branch}")
                    .setDirectory(staging.toFile())
                    .setCredentialsProvider(credentials)
                    .setTimeout(10)
                    .setNoCheckout(true)
                    .call()
                    .use { it.checkoutPortableTree() }
            }
            if (!replaceExisting) {
                AtomicMove.move(staging, root)
                open().use { prepareLineEndings(it) }
                return
            }
            val previous = parent.resolve(".${root.fileName}.replace-${UUID.randomUUID()}")
            AtomicMove.move(root, previous)
            try {
                AtomicMove.move(staging, root)
            } catch (failure: Exception) {
                runCatching { AtomicMove.move(previous, root) }.onFailure(failure::addSuppressed)
                if (previous.exists()) {
                    throw IllegalStateException("${failure.message}; previous library remains at $previous", failure)
                }
                throw failure
            }
            previous.toFile().deleteRecursively()
            open().use { prepareLineEndings(it) }
        } finally {
            if (staging.exists()) staging.toFile().deleteRecursively()
        }
    }

    private fun remoteRef(config: RemoteGitConfig) = "refs/remotes/$REMOTE_NAME/${config.branch}"

    private fun deviceIdentity() = org.eclipse.jgit.lib.PersonIdent(
        "Ruleblend @ ${hostname()}",
        COMMIT_EMAIL,
    )

    /** Commits each side carried before the merge changes either ref. */
    private fun syncProgress(
        repository: Repository,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
    ): RemoteSyncProgress = RemoteSyncProgress(
        behind = countCommits(repository, remote, local),
        ahead = countCommits(repository, local, remote),
    )

    private fun countCommits(
        repository: Repository,
        start: org.eclipse.jgit.lib.ObjectId,
        uninteresting: org.eclipse.jgit.lib.ObjectId,
    ): Int = RevWalk(repository).use { walk ->
        RevWalkUtils.count(walk, walk.parseCommit(start), walk.parseCommit(uninteresting))
    }

    private fun isAncestor(repository: Repository, ancestor: org.eclipse.jgit.lib.ObjectId, descendant: org.eclipse.jgit.lib.ObjectId): Boolean =
        RevWalk(repository).use { walk -> walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant)) }

    private fun resolveConflicts(
        git: Git,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        paths: Set<String>,
    ): Set<RemoteSyncConflict> {
        val conflicts = mutableSetOf<RemoteSyncConflict>()
        val base = commonMergeBase(git.repository, local, remote)
        val blockPaths = paths.filter { it.startsWith("blocks/") && it.endsWith(".md") }
        blockPaths.forEach { path ->
            if (!resolveFormattingConflict(git, base, local, remote, path)) {
                resolveBlockConflict(git, local, remote, path)?.let(conflicts::add)
            }
        }

        val groupPaths = paths.filter { it.startsWith("groups/") && it.endsWith(".yaml") && it != ALL_GROUP_PATH }
        groupPaths.forEach { path ->
            if (!resolveFormattingConflict(git, base, local, remote, path)) {
                resolveGroupConflict(git, local, remote, path)?.let(conflicts::add)
            }
        }

        val profilePaths = paths.filter { it.startsWith("profiles/") && it.endsWith(".yaml") }
        profilePaths.forEach { path ->
            if (!resolveFormattingConflict(git, base, local, remote, path)) {
                resolveProfileConflict(git, local, remote, path)?.let(conflicts::add)
            }
        }

        // Skill files only get their index entry cleared here; the object-level pass below decides
        // which complete skill tree survives, so a skill never ends up as a handful of its files.
        val skillPaths = paths.filter { it.startsWith("skills/") }
        skillPaths.forEach { path ->
            if (!resolveFormattingConflict(git, base, local, remote, path)) resolveRawConflict(git, local, remote, path)
        }

        // Ruleblend writes `.gitignore` itself, but a user may still have edited it on both devices.
        // It follows the same last-sync-wins rule as an object rather than blocking the whole sync.
        val managedPaths = paths.filter { it == GITIGNORE_FILE }
        managedPaths.forEach { path -> resolveRawConflict(git, local, remote, path) }

        val unsupported = paths - blockPaths.toSet() - groupPaths.toSet() - profilePaths.toSet() - skillPaths.toSet() -
            managedPaths.toSet() - ALL_GROUP_PATH
        check(unsupported.isEmpty()) { "Cannot safely merge non-library paths: ${unsupported.sorted().joinToString()}" }
        return conflicts
    }

    /** A normalization commit must never replace a real edit made by a device on the old policy. */
    private fun resolveFormattingConflict(git: Git, base: ObjectId?, local: ObjectId, remote: ObjectId, path: String): Boolean {
        if (!LibraryLineEndings.isDefinition(path)) return false
        val original = treeText(git.repository, base, path)?.let(LineEnding.LF::apply) ?: return false
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        val winner = when {
            localText?.let(LineEnding.LF::apply) == original -> remoteText
            remoteText?.let(LineEnding.LF::apply) == original -> localText
            else -> return false
        }
        writeConflictResolution(git, path, winner)
        return true
    }

    private fun resolveBlockConflict(
        git: Git,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".md")
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentBlock(id, localText, remoteText) -> localText
            else -> {
                val localBlock = BlockFile.parse(id, normalizeText(localText))
                val remoteBlock = BlockFile.parse(id, normalizeText(remoteText))
                BlockFile.serialize(localBlock.copy(version = maxOf(localBlock.version, remoteBlock.version) + 1))
            }
        }
        writeConflictResolution(git, path, resolved)
        return blockConflict(id, localText, remoteText)
    }

    private fun resolveGroupConflict(
        git: Git,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".yaml")
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentGroup(localText, remoteText) -> localText
            else -> {
                val localGroup = Yaml.default.decodeFromString(Group.serializer(), normalizeText(localText))
                val remoteGroup = Yaml.default.decodeFromString(Group.serializer(), normalizeText(remoteText))
                Yaml.default.encodeToString(
                    Group.serializer(),
                    localGroup.copy(version = maxOf(localGroup.version, remoteGroup.version) + 1),
                ) + "\n"
            }
        }
        writeConflictResolution(git, path, resolved)
        return groupConflict(id, localText, remoteText)
    }

    private fun resolveProfileConflict(
        git: Git,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".yaml")
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentProfile(localText, remoteText) -> localText
            else -> {
                val localProfile = Yaml.default.decodeFromString(Profile.serializer(), normalizeText(localText))
                val remoteProfile = Yaml.default.decodeFromString(Profile.serializer(), normalizeText(remoteText))
                Yaml.default.encodeToString(
                    Profile.serializer(),
                    localProfile.copy(version = maxOf(localProfile.version, remoteProfile.version) + 1),
                ) + "\n"
            }
        }
        writeConflictResolution(git, path, resolved)
        return profileConflict(id, localText, remoteText)
    }

    /** Clears one unmerged index entry with the last-sync-wins rule, byte for byte. */
    private fun resolveRawConflict(
        git: Git,
        local: ObjectId,
        remote: ObjectId,
        path: String,
    ) {
        val winner = treeEntry(git.repository, local, path) ?: treeEntry(git.repository, remote, path)
        writeBlobResolution(git, path, winner)
    }

    private fun reconcileObjectConflicts(
        git: Git,
        base: org.eclipse.jgit.lib.ObjectId?,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
    ): Set<RemoteSyncConflict> {
        val conflicts = mutableSetOf<RemoteSyncConflict>()
        val repository = git.repository
        treePaths(repository, base, "blocks/")
            .plus(treePaths(repository, local, "blocks/"))
            .plus(treePaths(repository, remote, "blocks/"))
            .distinct()
            .filter { it.endsWith(".md") }
            .forEach { path -> reconcileBlock(git, base, local, remote, path)?.let(conflicts::add) }
        treePaths(repository, base, "groups/")
            .plus(treePaths(repository, local, "groups/"))
            .plus(treePaths(repository, remote, "groups/"))
            .distinct()
            .filter { it.endsWith(".yaml") && it != ALL_GROUP_PATH }
            .forEach { path -> reconcileGroup(git, base, local, remote, path)?.let(conflicts::add) }
        treePaths(repository, base, "profiles/")
            .plus(treePaths(repository, local, "profiles/"))
            .plus(treePaths(repository, remote, "profiles/"))
            .distinct()
            .filter { it.endsWith(".yaml") }
            .forEach { path -> reconcileProfile(git, base, local, remote, path)?.let(conflicts::add) }
        skillIds(repository, base)
            .plus(skillIds(repository, local))
            .plus(skillIds(repository, remote))
            .distinct()
            .forEach { id -> reconcileSkill(git, base, local, remote, id)?.let(conflicts::add) }
        return conflicts
    }

    /**
     * A skill is a directory, so resolving it file by file is not enough: when one device deleted the
     * skill and the other edited a single file inside it, git keeps that one file and drops the rest,
     * leaving a directory without `meta.yaml` that the library no longer recognises as a skill. Every
     * skill both sides touched is therefore resolved as one object — the winning tree replaces
     * whatever the file-level merge left behind.
     */
    private fun reconcileSkill(
        git: Git,
        base: ObjectId?,
        local: ObjectId,
        remote: ObjectId,
        id: String,
    ): RemoteSyncConflict? {
        val prefix = "skills/$id/"
        val repository = git.repository
        // Identical blob ids mean the side did not touch the skill at all, and Git's own merge already
        // did the right thing. Deciding that without opening a single blob keeps an ordinary merge
        // from reading every file of every skill three times over.
        val originalIds = treeIds(repository, base, prefix)
        if (originalIds == treeIds(repository, local, prefix) || originalIds == treeIds(repository, remote, prefix)) return null
        val original = treeEntries(repository, base, prefix)
        val localTree = treeEntries(repository, local, prefix)
        val remoteTree = treeEntries(repository, remote, prefix)
        // Differing ids are not yet a change: a rewrite that only changed line endings is not one.
        if (!changedTree(original, localTree) || !changedTree(original, remoteTree)) return null
        val resolved = if (localTree.isEmpty()) remoteTree else localTree
        (localTree.keys + remoteTree.keys + original.keys).forEach { path ->
            writeBlobResolution(git, path, resolved[path])
        }
        if (equivalentTree(localTree, remoteTree)) return null
        return RemoteSyncConflict(
            id,
            RemoteSyncConflictKind.SKILL,
            restored = localTree.isEmpty() || remoteTree.isEmpty(),
        )
    }

    private fun skillIds(repository: Repository, commit: ObjectId?): List<String> =
        treePaths(repository, commit, "skills/")
            .map { it.removePrefix("skills/").substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Line endings aside, two trees with the same files and the same content are the same object. */
    private fun equivalentTree(left: Map<String, BlobEntry>, right: Map<String, BlobEntry>): Boolean =
        left.keys == right.keys && left.all { (path, entry) -> entry.equivalentTo(right.getValue(path)) }

    private fun changedTree(before: Map<String, BlobEntry>, after: Map<String, BlobEntry>): Boolean =
        !equivalentTree(before, after)

    private fun reconcileBlock(
        git: Git,
        base: org.eclipse.jgit.lib.ObjectId?,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".md")
        val original = treeText(git.repository, base, path)
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        if (!changedBlock(id, original, localText) || !changedBlock(id, original, remoteText)) return null
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentBlock(id, localText, remoteText) -> localText
            else -> {
                val localBlock = BlockFile.parse(id, normalizeText(localText))
                val remoteBlock = BlockFile.parse(id, normalizeText(remoteText))
                BlockFile.serialize(localBlock.copy(version = maxOf(localBlock.version, remoteBlock.version) + 1))
            }
        }
        writeConflictResolution(git, path, resolved)
        return blockConflict(id, localText, remoteText)
    }

    private fun reconcileGroup(
        git: Git,
        base: org.eclipse.jgit.lib.ObjectId?,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".yaml")
        val original = treeText(git.repository, base, path)
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        if (!changedGroup(original, localText) || !changedGroup(original, remoteText)) return null
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentGroup(localText, remoteText) -> localText
            else -> {
                val localGroup = Yaml.default.decodeFromString(Group.serializer(), normalizeText(localText))
                val remoteGroup = Yaml.default.decodeFromString(Group.serializer(), normalizeText(remoteText))
                Yaml.default.encodeToString(
                    Group.serializer(),
                    localGroup.copy(version = maxOf(localGroup.version, remoteGroup.version) + 1),
                ) + "\n"
            }
        }
        writeConflictResolution(git, path, resolved)
        return groupConflict(id, localText, remoteText)
    }

    private fun reconcileProfile(
        git: Git,
        base: org.eclipse.jgit.lib.ObjectId?,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
        path: String,
    ): RemoteSyncConflict? {
        val id = Path.of(path).fileName.toString().removeSuffix(".yaml")
        val original = treeText(git.repository, base, path)
        val localText = treeText(git.repository, local, path)
        val remoteText = treeText(git.repository, remote, path)
        if (!changedProfile(original, localText) || !changedProfile(original, remoteText)) return null
        val resolved = when {
            localText == null -> remoteText
            remoteText == null -> localText
            equivalentProfile(localText, remoteText) -> localText
            else -> {
                val localProfile = Yaml.default.decodeFromString(Profile.serializer(), normalizeText(localText))
                val remoteProfile = Yaml.default.decodeFromString(Profile.serializer(), normalizeText(remoteText))
                Yaml.default.encodeToString(
                    Profile.serializer(),
                    localProfile.copy(version = maxOf(localProfile.version, remoteProfile.version) + 1),
                ) + "\n"
            }
        }
        writeConflictResolution(git, path, resolved)
        return profileConflict(id, localText, remoteText)
    }

    private fun writeConflictResolution(git: Git, path: String, content: String?) {
        val target = root.resolve(path)
        if (content == null) {
            Files.deleteIfExists(target)
            git.rm().addFilepattern(path).call()
        } else {
            AtomicWrite.write(target, content)
            git.add().addFilepattern(path).call()
        }
    }

    /**
     * Writes one path's resolution, or removes it when the winning tree has no such file. Skills may
     * carry images and scripts, so the blob travels as bytes and keeps its executable mode — reading
     * it back as text would corrupt every non-UTF-8 file in the library.
     */
    private fun writeBlobResolution(git: Git, path: String, entry: BlobEntry?) {
        val target = root.resolve(path)
        if (entry == null) {
            if (Files.exists(target)) {
                Files.delete(target)
                git.rm().addFilepattern(path).call()
            }
            return
        }
        target.parent?.createDirectories()
        AtomicWrite.write(target, entry.bytes)
        target.toFile().setExecutable(entry.executable, false)
        git.add().addFilepattern(path).call()
    }

    private fun treeEntry(repository: Repository, commit: ObjectId?, path: String): BlobEntry? {
        if (commit == null) return null
        return RevWalk(repository).use { revisions ->
            TreeWalk.forPath(repository, path, revisions.parseCommit(commit).tree)?.use { walk ->
                BlobEntry(
                    repository.open(walk.getObjectId(0)).bytes,
                    walk.getFileMode(0) == FileMode.EXECUTABLE_FILE,
                )
            }
        }
    }

    private fun treeIds(repository: Repository, commit: ObjectId?, prefix: String): Map<String, ObjectId> {
        if (commit == null) return emptyMap()
        return RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { walk ->
                walk.addTree(revisions.parseCommit(commit).tree)
                walk.isRecursive = true
                buildMap {
                    while (walk.next()) if (walk.pathString.startsWith(prefix)) put(walk.pathString, walk.getObjectId(0))
                }
            }
        }
    }

    private fun treeEntries(repository: Repository, commit: ObjectId?, prefix: String): Map<String, BlobEntry> =
        treePaths(repository, commit, prefix).associateWith { path ->
            checkNotNull(treeEntry(repository, commit, path)) { "Missing $path in the merged tree" }
        }

    private fun treeText(repository: Repository, commit: org.eclipse.jgit.lib.ObjectId?, path: String): String? {
        if (commit == null) return null
        return RevWalk(repository).use { revisions ->
            TreeWalk.forPath(repository, path, revisions.parseCommit(commit).tree)?.use { walk ->
                repository.open(walk.getObjectId(0)).bytes.toString(Charsets.UTF_8)
            }
        }
    }

    private fun treePaths(repository: Repository, commit: org.eclipse.jgit.lib.ObjectId?, prefix: String): List<String> {
        if (commit == null) return emptyList()
        return RevWalk(repository).use { revisions ->
            TreeWalk(repository).use { walk ->
                walk.addTree(revisions.parseCommit(commit).tree)
                walk.isRecursive = true
                buildList {
                    while (walk.next()) if (walk.pathString.startsWith(prefix)) add(walk.pathString)
                }
            }
        }
    }

    private fun commonMergeBase(
        repository: Repository,
        local: org.eclipse.jgit.lib.ObjectId,
        remote: org.eclipse.jgit.lib.ObjectId,
    ): org.eclipse.jgit.lib.ObjectId? = RevWalk(repository).use { walk ->
        walk.revFilter = org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(local))
        walk.markStart(walk.parseCommit(remote))
        walk.next()
    }

    private fun equivalentBlock(id: String, left: String, right: String): Boolean =
        BlockFile.parse(id, normalizeText(left)) == BlockFile.parse(id, normalizeText(right))

    private fun equivalentGroup(left: String, right: String): Boolean =
        Yaml.default.decodeFromString(Group.serializer(), normalizeText(left)) ==
            Yaml.default.decodeFromString(Group.serializer(), normalizeText(right))

    private fun equivalentProfile(left: String, right: String): Boolean =
        Yaml.default.decodeFromString(Profile.serializer(), normalizeText(left)) ==
            Yaml.default.decodeFromString(Profile.serializer(), normalizeText(right))

    private fun blockConflict(id: String, local: String?, remote: String?): RemoteSyncConflict? = when {
        local == null || remote == null -> RemoteSyncConflict(id, RemoteSyncConflictKind.BLOCK, restored = true)
        equivalentBlock(id, local, remote) -> null
        else -> RemoteSyncConflict(id, RemoteSyncConflictKind.BLOCK)
    }

    private fun groupConflict(id: String, local: String?, remote: String?): RemoteSyncConflict? = when {
        local == null || remote == null -> RemoteSyncConflict(id, RemoteSyncConflictKind.GROUP, restored = true)
        equivalentGroup(local, remote) -> null
        else -> RemoteSyncConflict(id, RemoteSyncConflictKind.GROUP)
    }

    private fun profileConflict(id: String, local: String?, remote: String?): RemoteSyncConflict? = when {
        local == null || remote == null -> RemoteSyncConflict(id, RemoteSyncConflictKind.PROFILE, restored = true)
        equivalentProfile(local, remote) -> null
        else -> RemoteSyncConflict(id, RemoteSyncConflictKind.PROFILE)
    }

    private fun changedBlock(id: String, before: String?, after: String?): Boolean = when {
        before == null || after == null -> before != after
        else -> !equivalentBlock(id, before, after)
    }

    private fun changedGroup(before: String?, after: String?): Boolean = when {
        before == null || after == null -> before != after
        else -> !equivalentGroup(before, after)
    }

    private fun changedProfile(before: String?, after: String?): Boolean = when {
        before == null || after == null -> before != after
        else -> !equivalentProfile(before, after)
    }

    private fun normalizeText(value: String): String = value.replace("\r\n", "\n").replace("\r", "\n")

    private fun rebuildAllGroup() {
        val blocks = LibraryMembership.blockIds(root)
        val skills = LibraryMembership.skillIds(root)
        val target = root.resolve(ALL_GROUP_PATH)
        val previous = target.takeIf { it.exists() }?.toFile()?.readText()
            ?.let { text -> runCatching { Yaml.default.decodeFromString(Group.serializer(), text) }.getOrNull() }
        val rebuilt = (previous ?: Group(id = ALL_GROUP_ID, name = ALL_GROUP_ID)).copy(
            version = if (previous == null || previous.blockIds != blocks || previous.skillIds != skills) (previous?.version ?: 0) + 1 else previous.version,
            blockIds = blocks,
            skillIds = skills,
        )
        AtomicWrite.write(target, formatText(Yaml.default.encodeToString(Group.serializer(), rebuilt) + "\n"))
    }

    private fun stageAllLibraryContent(git: Git) {
        git.add().addFilepattern("blocks").call()
        git.add().addFilepattern("groups").call()
        git.add().addFilepattern("profiles").call()
        git.add().addFilepattern("skills").call()
        git.add().setUpdate(true).addFilepattern("blocks").call()
        git.add().setUpdate(true).addFilepattern("groups").call()
        git.add().setUpdate(true).addFilepattern("profiles").call()
        git.add().setUpdate(true).addFilepattern("skills").call()
    }

    /** Adds the file Ruleblend created itself to the first content commit, never a user's variant. */
    private fun stageManagedGitignore(git: Git) {
        val gitignore = root.resolve(GITIGNORE_FILE)
        if (gitignore.exists() && gitignore.toFile().readText() == GITIGNORE_CONTENT) {
            git.add().addFilepattern(GITIGNORE_FILE).call()
        }
    }

    fun validateRemoteConfig(config: RemoteGitConfig): RemoteGitConfig {
        val url = config.url.trim()
        val branch = config.branch.trim()
        require(url.isNotEmpty()) { "Remote repository URL is empty" }
        val uri = URIish(url)
        require(uri.pass == null) { "Keep credentials in a Git credential helper, not in the repository URL" }
        require(uri.scheme == "https" || uri.scheme == "file" || (uri.scheme == null && uri.host == null)) {
            "Only HTTPS and local repository URLs are supported"
        }
        require(Repository.isValidRefName("refs/heads/$branch")) { "Invalid remote branch: $branch" }
        return config.copy(url = url, branch = branch)
    }
}

/** A UI wait is observational: an unfinished worker must not escape as a fatal timeout. */
internal fun Future<*>.completesWithin(timeoutMillis: Long): Boolean = try {
    get(timeoutMillis, TimeUnit.MILLISECONDS)
    true
} catch (_: TimeoutException) {
    false
}

private const val GITIGNORE_FILE = ".gitignore"
private const val GITIGNORE_CONTENT = ".DS_Store\n.*.rollback\n.*.import-*\n"
private const val COMMIT_EMAIL = "ruleblend@localhost"
private const val REMOTE_NAME = "ruleblend-sync"
private const val ALL_GROUP_PATH = "groups/$ALL_GROUP_ID.yaml"

/** A peer advanced the branch or temporarily holds its ref lock; both require one fresh fetch. */
internal fun isRetryablePushFailure(status: RemoteRefUpdate.Status, message: String?): Boolean =
    status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD ||
        (status == RemoteRefUpdate.Status.REJECTED_OTHER_REASON && message?.contains("failed to lock", ignoreCase = true) == true)

private class RetryablePush(message: String) : IllegalStateException(message)

/** A non-empty local library and the configured branch have no common commit. */
/** One file of a library object as it was committed: content plus the only mode git records for it. */
private class BlobEntry(val bytes: ByteArray, val executable: Boolean) {
    fun equivalentTo(other: BlobEntry): Boolean =
        executable == other.executable && normalized().contentEquals(other.normalized())

    private fun normalized(): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        // A byte array that is not text round-trips through the replacement character, so only
        // compare normalized line endings when the content really is text.
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) return bytes
        return text.replace("\r\n", "\n").replace("\r", "\n").toByteArray(Charsets.UTF_8)
    }
}

class UnrelatedLibraryHistories(val remote: RemoteGitConfig) : IllegalStateException(
    "The local library and ${remote.url} have unrelated histories",
)

private fun localHostname(): String = runCatching { InetAddress.getLocalHost().hostName }
    .getOrElse { "unknown-host" }

private const val FocusSyncIntervalMillis = 5 * 60 * 1_000L

enum class RemoteSyncStatus { PUSHED, UP_TO_DATE, FAILED }

/** A library object whose concurrent changes were resolved during a remote sync. */
data class RemoteSyncConflict(
    val id: String,
    val kind: RemoteSyncConflictKind,
    /** A delete-versus-modify resolution put the surviving object back in the library. */
    val restored: Boolean = false,
)

enum class RemoteSyncConflictKind { BLOCK, GROUP, PROFILE, SKILL }

private data class RemoteSyncProgress(
    val behind: Int = 0,
    val ahead: Int = 0,
    val merged: Boolean = false,
    val conflicts: List<RemoteSyncConflict> = emptyList(),
) {
    fun result(status: RemoteSyncStatus = RemoteSyncStatus.UP_TO_DATE): RemoteSyncResult =
        RemoteSyncResult(status, behind = behind, ahead = ahead, merged = merged, conflicts = conflicts)
}

/** A rejected push repeats fetch; retain the largest observed divergence rather than double-counting its common commits. */
private fun RemoteSyncResult.combine(other: RemoteSyncResult): RemoteSyncResult = copy(
    behind = maxOf(behind, other.behind),
    ahead = maxOf(ahead, other.ahead),
    merged = merged || other.merged,
    conflicts = (conflicts + other.conflicts).distinct().sortedWith(remoteConflictOrder),
)

private fun RemoteSyncResult.withProgress(progress: RemoteSyncResult): RemoteSyncResult = copy(
    behind = progress.behind,
    ahead = progress.ahead,
    merged = progress.merged,
    conflicts = progress.conflicts,
)

private val remoteConflictOrder = compareBy<RemoteSyncConflict>({ it.kind.ordinal }, { it.id }, { it.restored })

/** Outcome of the last push; divergence describes commits observed before this sync changed either ref. */
data class RemoteSyncResult(
    val status: RemoteSyncStatus,
    val message: String? = null,
    val atEpochMillis: Long = System.currentTimeMillis(),
    val behind: Int = 0,
    val ahead: Int = 0,
    val merged: Boolean = false,
    val conflicts: List<RemoteSyncConflict> = emptyList(),
    val failed: String? = null,
    /** The remote is not broken, it is a foreign library — only the user can choose merge or replace. */
    val unrelatedHistories: Boolean = false,
)

private fun remoteSyncFailed(failure: Exception): RemoteSyncResult {
    val message = failure.message ?: failure.toString()
    return RemoteSyncResult(
        RemoteSyncStatus.FAILED,
        message = message,
        failed = message,
        unrelatedHistories = failure is UnrelatedLibraryHistories,
    )
}

/** Whole-repository statistics: commit count and the newest commit's time, `null` when unborn. */
data class LibraryHistory(val commits: Int, val lastCommitEpochMillis: Long?)
