package dev.ruleblend.core.integration

import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class TargetIndexTest {
    @Test fun indexIsDerivedAndCanBeRebuilt() {
        val dir = Files.createTempDirectory("ruleblend-index")
        try {
            val index = TargetIndex(dir.resolve("targets.index"))
            val file = dir.resolve("AGENTS.md")
            val text = WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", ManagedRegion("rule", 1, null, hashContent("body"), "body"))
            index.update(file, text)
            assertEquals(TargetOwnershipMode.PARTIAL, index.load().single().mode)
            assertEquals("rule", index.load().single().blocks.single().id)
            index.remove(file)
            assertEquals(emptyList(), index.load())
        } finally { dir.toFile().deleteRecursively() }
    }

    /** One index file holds every target, so two targets written at once share a read-modify-write. */
    @Test fun concurrentUpdatesOfTwoTargetsKeepBothEntries() {
        val dir = Files.createTempDirectory("ruleblend-index")
        try {
            val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
            val index = TargetIndex(dir.resolve("targets.index"), coordinator)
            val start = CyclicBarrier(2)
            // Each target is recorded once, so an entry another thread's write dropped stays dropped.
            val targets = listOf("first", "second").associateWith { id -> (1..20).map { "$id-$it" } }
            targets.map { (id, names) ->
                thread {
                    val region = ManagedRegion(id, 1, null, hashContent("body"), "body")
                    val text = WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", region)
                    start.await()
                    names.forEach { name -> index.update(dir.resolve("$name.md"), text) }
                }
            }.forEach { it.join() }

            assertEquals(targets.values.flatten().size, index.load().size, "no entry may be lost")
        } finally { dir.toFile().deleteRecursively() }
    }
}
