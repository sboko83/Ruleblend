package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.withRegionContent
import dev.ruleblend.core.storage.InterProcessLock
import dev.ruleblend.core.storage.TargetMutationCoordinator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrationServiceTest {

    private lateinit var dir: Path
    private lateinit var file: Path
    private val service = IntegrationService()
    private val block = Block(id = "git-no-commit", name = "Git No Commit", version = 1, content = "Never commit.")

    private fun target(): AgentGlobalTarget = AgentGlobalTarget(object : AgentAdapter {
        override val id = "test"
        override val name = "Test"
        override fun isAvailable() = true
        override fun globalFile(): Path = file
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
    })

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ruleblend-integration")
        file = dir.resolve("CLAUDE.md")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun installCreatesOwnedFileWithRegion() {
        service.install(file, block)

        assertTrue(file.exists())
        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))
        assertEquals(1, Regex("""(?m)^<!--\s*rb1\b""").findAll(file.readText()).count())
        assertEquals(0, Regex("""(?m)^<!--\s*rb:end\s*-->""").findAll(file.readText()).count())
        val region = service.regions(file).single()
        assertEquals("git-no-commit", region.id)
        assertEquals(InstallStatus.SYNCED, statusOf(region, block))
    }

    @Test
    fun partialFileWithNoHandWrittenTextIsEffectivelyOwned() {
        file.writeText(
            "<!-- kb1 ${hashRun(block.content)} ${block.id}@1:${block.content.encodeToByteArray().size} -->\n" +
                "${block.content}\n<!-- kb:end -->\n",
        )

        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))
    }

    @Test
    fun writingAnEffectivelyOwnedPartialFilePromotesItToOwned() {
        file.writeText(WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", ManagedRegion(
            block.id,
            block.version,
            null,
            hashContent(block.content),
            block.content,
        )))
        service.install(file, block.copy(version = 2, content = "Updated body."))
        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))
        assertTrue(file.readText().contains("<!-- rb1 owned "))
        assertFalse(file.readText().contains("<!-- rb:end -->"))
    }

    @Test
    fun writingAKb1FileMigratesItsFormatAndNoticeToRb1() {
        val old = block.copy(id = "old", content = "Old body.")
        file.writeText(
            "# Notes\n\n$LEGACY_PARTIAL_NOTICE\n" +
                "<!-- kb1 ${hashRun(old.content)} old@1:${old.content.encodeToByteArray().size} -->\n" +
                "${old.content}\n<!-- kb:end -->\n",
        )

        service.install(file, block)

        val text = file.readText()
        assertTrue("<!-- rb1 " in text)
        assertTrue("<!-- rb:end -->" in text)
        assertTrue(PARTIAL_NOTICE in text)
        assertFalse("<!-- kb1 " in text)
        assertFalse("<!-- kb:end -->" in text)
        assertFalse(LEGACY_PARTIAL_NOTICE in text)
        assertEquals(listOf("old", block.id), service.regions(file).map { it.id })
        assertEquals("# Notes", service.unmanaged(file).text)
    }

    @Test
    fun migrateLegacyConvertsToPartialAndCanRollBack() {
        file.writeText("# Notes\n\n<!-- kb git-no-commit v1 12345678 -->\nNever commit.\n<!-- kb:end -->\n")

        service.migrateLegacy(file, TargetOwnershipMode.PARTIAL, notice = false)

        assertEquals(TargetOwnershipMode.PARTIAL, service.ownershipMode(file))
        assertEquals(listOf(block.id), service.regions(file).map { it.id })
        assertEquals("# Notes", service.unmanaged(file).text)
        assertFalse(file.readText().contains(PARTIAL_NOTICE))

        service.revertToLegacy(file)

        assertEquals(TargetOwnershipMode.LEGACY, service.ownershipMode(file))
        assertEquals(listOf(block.id), service.regions(file).map { it.id })
    }

    @Test
    fun ownedMigrationRejectsHandWrittenContentAndDisownPreservesBody() {
        file.writeText("<!-- kb git-no-commit v1 12345678 -->\nNever commit.\n<!-- kb:end -->\n")
        service.migrateLegacy(file, TargetOwnershipMode.OWNED)
        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))

        service.disown(file)

        assertEquals(TargetOwnershipMode.NONE, service.ownershipMode(file))
        assertEquals("Never commit.\n", file.readText())

        file.writeText("# Notes\n\n<!-- kb git-no-commit v1 12345678 -->\nNever commit.\n<!-- kb:end -->\n")
        assertFailsWith<IllegalArgumentException> { service.migrateLegacy(file, TargetOwnershipMode.OWNED) }
    }

    @Test
    fun noticeCanBeToggledWithoutRewritingRegions() {
        file.writeText("<!-- kb git-no-commit v1 12345678 -->\nNever commit.\n<!-- kb:end -->\n")
        service.migrateLegacy(file, TargetOwnershipMode.PARTIAL)

        service.setNotice(file, false)

        assertFalse(file.readText().contains(PARTIAL_NOTICE))
        assertEquals(listOf(block.id), service.regions(file).map { it.id })
    }

    @Test
    fun partialDisownKeepsHandWrittenPrefixAndRenderedBlocks() {
        file.writeText("# Notes\n\n<!-- kb git-no-commit v1 12345678 -->\nNever commit.\n<!-- kb:end -->\n")
        service.migrateLegacy(file, TargetOwnershipMode.PARTIAL)

        service.disown(file)

        assertEquals("# Notes\n\nNever commit.\n", file.readText())
    }

    @Test
    fun installPreservesHandWrittenContent() {
        file.writeText("# My rules\n\nHand-written.\n")

        service.install(file, block)

        val text = file.readText()
        assertTrue(text.startsWith("# My rules\n\nHand-written.\n"))
        assertEquals(TargetOwnershipMode.PARTIAL, service.ownershipMode(file))
        assertEquals(listOf("git-no-commit"), service.regions(file).map { it.id })
    }

    @Test
    fun installTagsRegionWithItsGroup() {
        service.install(file, block, group = "ios-projects")

        assertEquals("ios-projects", service.regions(file).single().group)
    }

    @Test
    fun reinstallUpdatesInPlaceAndReportsSynced() {
        service.install(file, block)
        val updated = block.copy(version = 2, content = "Never commit without asking.")

        service.install(file, updated)

        val region = service.regions(file).single()
        assertEquals(2, region.version)
        assertEquals(InstallStatus.SYNCED, statusOf(region, updated))
    }

    @Test
    fun manualEditIsReportedAsModified() {
        service.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Never ever commit."))

        assertEquals(InstallStatus.MODIFIED, statusOf(service.regions(file).single(), block))
    }

    @Test
    fun sameLengthManualEditIsReportedAsModified() {
        service.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Never commits"))

        assertEquals(InstallStatus.MODIFIED, statusOf(service.regions(file).single(), block))
    }

    @Test
    fun libraryChangeIsReportedAsUpdateAvailable() {
        service.install(file, block)

        val newer = block.copy(version = 2, content = "Never commit without asking.")
        assertEquals(InstallStatus.UPDATE_AVAILABLE, statusOf(service.regions(file).single(), newer))
    }

    @Test
    fun removeKeepsHandWrittenContent() {
        file.writeText("# My rules\n")
        service.install(file, block)
        val outside = file.readText().substringBefore(PARTIAL_NOTICE)

        service.remove(file, block.id)

        assertEquals(outside, file.readText())
        assertEquals(emptyList(), service.regions(file))
    }

    @Test
    fun regionsOfMissingFileIsEmpty() {
        assertEquals(emptyList(), service.regions(dir.resolve("absent.md")))
    }

    @Test
    fun unmanagedListsOnlyFilesWithHandWrittenContent() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md"), agent("AGENTS.md")))
        dir.resolve("CLAUDE.md").writeText("# My rules\n\nHand-written.\n")
        service.install(dir.resolve("AGENTS.md"), block)

        val unmanaged = service.unmanagedFiles(target)

        assertEquals(listOf(dir.resolve("CLAUDE.md")), unmanaged.map { it.first })
        assertEquals(3, unmanaged.single().second.lineCount)
    }

    @Test
    fun adoptTurnsHandWrittenContentIntoASyncedRegion() {
        val original = "# My rules\n\nHand-written.\n"
        file.writeText(original)
        val adopted = block.copy(id = "my-rules", content = service.unmanaged(file).text)

        service.adopt(file, adopted)

        assertEquals(InstallStatus.SYNCED, statusOf(service.regions(file).single(), adopted))
        assertTrue(service.unmanaged(file).isEmpty)
        assertEquals(original.trimEnd('\n'), service.regions(file).single().content)
    }

    @Test
    fun adoptedBlockReinstallsIntoAnEquivalentFile() {
        file.writeText("# My rules\n\nHand-written.\n")
        val adopted = block.copy(id = "my-rules", content = service.unmanaged(file).text)
        service.adopt(file, adopted)

        val other = dir.resolve("AGENTS.md")
        service.install(other, adopted)

        assertEquals(service.regions(file).single().content, service.regions(other).single().content)
    }

    @Test
    fun adoptOfMissingFileIsNoOp() {
        val absent = dir.resolve("absent.md")

        service.adopt(absent, block)

        assertTrue(!absent.exists())
    }

    @Test
    fun adoptSectionsWritesAllChosenBlocksAsRegions() {
        file.writeText("# First\nfirst body\n\n# Second\nsecond body\n")
        val first = block.copy(id = "first", content = "first body")
        val second = block.copy(id = "second", content = "second body")
        val plan = listOf(
            ManagedDocument.AdoptStep.Replace("# First\nfirst body", regionFor(first)),
            ManagedDocument.AdoptStep.Replace("# Second\nsecond body", regionFor(second)),
        )

        service.adoptSections(file, plan)

        assertEquals(listOf("first", "second"), service.regions(file).map { it.id })
        assertTrue(service.unmanaged(file).isEmpty, "no unmanaged text should remain")
    }

    @Test
    fun adoptSectionsKeepsSkippedSectionsAsUnmanaged() {
        file.writeText("# First\nfirst body\n\n# Second\nsecond body\n\n# Third\nthird body\n")
        val first = block.copy(id = "first", content = "first body")
        val plan = listOf(
            ManagedDocument.AdoptStep.Replace("# First\nfirst body", regionFor(first)),
            ManagedDocument.AdoptStep.Keep("# Second\nsecond body"),
            ManagedDocument.AdoptStep.Keep("# Third\nthird body"),
        )

        service.adoptSections(file, plan)

        val regions = service.regions(file)
        assertEquals(listOf("first"), regions.map { it.id })
        val leftover = service.unmanaged(file).text
        assertTrue("second body" in leftover, "skipped section must stay: $leftover")
        assertTrue("third body" in leftover, "skipped section must stay: $leftover")
    }

    @Test
    fun adoptSectionsIsNoOpForMissingFile() {
        val absent = dir.resolve("absent.md")

        service.adoptSections(absent, listOf(ManagedDocument.AdoptStep.Replace("body", regionFor(block))))

        assertTrue(!absent.exists())
    }

    @Test
    fun adoptSectionsIsNoOpForEmptyPlan() {
        file.writeText("# Rules\nhand-written\n")

        service.adoptSections(file, emptyList())

        assertEquals("# Rules\nhand-written\n", file.readText())
    }

    private fun regionFor(block: Block, group: String? = null) =
        dev.ruleblend.core.integration.regionFor(block, group)

    private fun agent(fileName: String, agentId: String = fileName) = object : AgentAdapter {
        override val id = agentId
        override val name = agentId
        override fun isAvailable() = true
        override fun globalFile() = dir.resolve("global-$fileName")
        override fun projectFile(projectDir: Path) = projectDir.resolve(fileName)
    }

    @Test
    fun statusIsNullWhenBlockIsNotInstalledInTarget() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md")))
        assertEquals(null, service.status(target, block))
    }

    @Test
    fun installWritesEveryAgentFileOfTheTarget() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md"), agent("AGENTS.md")))

        service.install(target, block, group = "ios-projects")

        assertTrue(dir.resolve("CLAUDE.md").exists())
        assertTrue(dir.resolve("AGENTS.md").exists())
        assertEquals(InstallStatus.SYNCED, service.status(target, block))
        assertEquals("ios-projects", service.groupOf(target, block.id))
    }

    @Test
    fun targetStatusReportsUpdateWhenOnlySomeAgentFilesHaveTheBlock() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md"), agent("AGENTS.md")))
        service.install(dir.resolve("CLAUDE.md"), block)

        assertEquals(InstallStatus.UPDATE_AVAILABLE, service.status(target, block))
    }

    @Test
    fun targetStatusReportsWorstOfItsFiles() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md"), agent("AGENTS.md")))
        service.install(target, block)
        val edited = dir.resolve("AGENTS.md")
        edited.writeText(edited.readText().replace("Never commit.", "Never ever commit."))

        assertEquals(InstallStatus.MODIFIED, service.status(target, block))
    }

    @Test
    fun removeClearsEveryAgentFileOfTheTarget() {
        val target = ProjectTarget(dir, listOf(agent("CLAUDE.md"), agent("AGENTS.md")))
        service.install(target, block)

        service.remove(target, block.id)

        assertEquals(null, service.status(target, block))
    }

    @Test
    fun agentsSharingOneFileGetTheRegionWrittenOnce() {
        // Codex and Pi both read AGENTS.md; installing must not write the region twice.
        val target = ProjectTarget(dir, listOf(agent("AGENTS.md", "codex"), agent("AGENTS.md", "pi")))

        service.install(target, block)

        val shared = dir.resolve("AGENTS.md")
        assertEquals(1, service.regions(shared).size)
        assertEquals(1, shared.readText().lines().count { it.startsWith("<!-- rb1 ") })
        assertEquals(InstallStatus.SYNCED, service.status(target, block))

        service.remove(target, block.id)

        assertTrue(service.regions(shared).isEmpty())
    }

    @Test
    fun installAddsTheImportToThePointerFileAsAPlainLine() {
        val target = redirectingTarget()

        service.install(target, block)

        val pointer = dir.resolve("CLAUDE.md")
        assertEquals("@AGENTS.md\n", pointer.readText(), "the pointer is the line, without markers")
        assertTrue(service.regions(pointer).isEmpty(), "no managed region belongs in a pointer file")
        // The block itself lives in AGENTS.md only — the pointer never holds a copy.
        assertEquals(listOf(block.id), service.regions(dir.resolve("AGENTS.md")).map { it.id })
    }

    @Test
    fun removingTheLastBlockRemovesTheImport() {
        val target = redirectingTarget()
        service.install(target, block)

        service.remove(target, block.id)

        assertEquals("", dir.resolve("CLAUDE.md").readText())
    }

    @Test
    fun syncConvertsAManagedImportRegionIntoAPlainLine() {
        // What earlier versions wrote: three lines of markers around one line of import.
        val pointer = dir.resolve("CLAUDE.md")
        val target = redirectingTarget()
        service.install(dir.resolve("AGENTS.md"), block)
        pointer.writeText(
            "<!-- rb1 owned c13755e0 $IMPORT_REGION_ID@1:10 — managed by Ruleblend; edits are overwritten -->\n" +
                "@AGENTS.md\n",
        )

        service.syncRedirects(target)

        assertEquals("@AGENTS.md\n", pointer.readText())
        assertTrue(service.regions(pointer).isEmpty())
    }

    @Test
    fun syncKeepsThePointerLineWhenTheTargetHoldsHandWrittenTextOnly() {
        // Every block is gone from AGENTS.md but the user's own text is not: the import still matters.
        val pointer = dir.resolve("CLAUDE.md")
        val shared = dir.resolve("AGENTS.md")
        shared.writeText("# Project rules\n\nWritten by hand.\n")
        pointer.writeText("@AGENTS.md\n")

        service.syncRedirects(redirectingTarget())

        assertEquals("@AGENTS.md\n", pointer.readText())
    }

    @Test
    fun syncKeepsThePointerLineBesideHandWrittenTextOfThePointerItself() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("# My notes\n\n@AGENTS.md\n")

        service.syncRedirects(redirectingTarget())

        assertEquals("# My notes\n\n@AGENTS.md\n", pointer.readText(), "only a bare pointer may be removed")
    }

    @Test
    fun theImportDoesNotDisturbHandWrittenContentOfThePointerFile() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("# My notes\n\nKeep me.\n")
        val target = redirectingTarget()

        service.install(target, block)
        service.remove(target, block.id)

        // The notes are untouched, and the import line stays: outside a managed region Ruleblend
        // cannot tell the line it wrote from one the user typed, so it removes neither.
        assertTrue(pointer.readText().startsWith("# My notes\n\nKeep me.\n"))
        assertTrue("@AGENTS.md" in pointer.readText())
    }

    @Test
    fun syncMovesBlocksLeftInThePointerFileIntoTheSharedFile() {
        // A project written by an older Ruleblend: the block sits in CLAUDE.md, AGENTS.md does not exist.
        val pointer = dir.resolve("CLAUDE.md")
        val shared = dir.resolve("AGENTS.md")
        pointer.writeText("# Kept\n\n")
        service.install(pointer, block, group = "ios-projects")

        service.syncRedirects(redirectingTarget())

        assertEquals(listOf(block.id), service.regions(shared).map { it.id })
        assertEquals("ios-projects", service.regions(shared).single().group)
        assertTrue("@AGENTS.md" in pointer.readText(), "the pointer must import the file it moved to")
        assertTrue("# Kept" in pointer.readText(), "hand-written text must survive the migration")
    }

    @Test
    fun syncReplacesTheImportWrittenUnderTheFormerName() {
        // A project written by the app under its former name: the pointer's import carries the old id,
        // and reading it as a rule moved it into AGENTS.md, leaving that file importing itself.
        val pointer = dir.resolve("CLAUDE.md")
        val shared = dir.resolve("AGENTS.md")
        service.install(pointer, Block(id = "kitbash-import", name = "Import", content = "@AGENTS.md"))
        service.install(shared, block)

        service.syncRedirects(redirectingTarget())

        assertEquals(listOf(block.id), service.regions(shared).map { it.id })
        assertEquals("@AGENTS.md\n", pointer.readText())
    }

    @Test
    fun syncKeepsBlocksAlreadyInTheSharedFile() {
        val target = redirectingTarget()
        service.install(target, block)

        service.syncRedirects(target)
        service.syncRedirects(target)

        assertEquals(listOf(block.id), service.regions(dir.resolve("AGENTS.md")).map { it.id })
        assertEquals("@AGENTS.md\n", dir.resolve("CLAUDE.md").readText(), "syncing twice must not double the line")
    }

    @Test
    fun syncDoesNotCreateAPointerFileWithNothingToPointAt() {
        service.syncRedirects(redirectingTarget())

        assertTrue(!dir.resolve("CLAUDE.md").exists())
    }

    @Test
    fun syncSkipsTheManagedImportWhenThePointerFileAlreadyContainsTheImportLine() {
        // This is what this project's CLAUDE.md looks like: one hand-written @AGENTS.md import.
        // Ruleblend must not add a second, managed copy alongside it.
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n")
        val target = redirectingTarget()

        service.install(target, block)

        val regions = service.regions(pointer)
        assertTrue(regions.none { it.id == IMPORT_REGION_ID }, "managed import must not be added when line already exists: ${regions.map { it.id }}")
        assertEquals("@AGENTS.md\n", pointer.readText(), "existing content must be unchanged")
        assertTrue(regions.isEmpty(), "a pointer file carries no managed region at all")
    }

    @Test
    fun syncRemovesTheManagedImportWhenThePointerFileAlreadyContainsTheImportLine() {
        // A pointer written by an earlier version, with a hand-written import added beside it later:
        // the region goes, the hand-written line stays, and the file does not import twice.
        val pointer = dir.resolve("CLAUDE.md")
        val target = redirectingTarget()
        service.install(dir.resolve("AGENTS.md"), block)
        pointer.writeText(
            "@AGENTS.md\n" +
                "<!-- rb1 owned c13755e0 $IMPORT_REGION_ID@1:10 — managed by Ruleblend; edits are overwritten -->\n" +
                "@AGENTS.md\n",
        )

        service.syncRedirects(target)

        assertTrue(service.regions(pointer).none { it.id == IMPORT_REGION_ID }, "managed import should be removed when redundant")
        assertEquals(1, pointer.readText().lines().count { it.trim() == "@AGENTS.md" }, "the file must import once")
    }

    @Test
    fun thePointerFileIsOfferedForAdoption() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("# Hand-written\n\nAdopt me.\n")

        val offered = service.unmanagedFiles(redirectingTarget()).map { it.first }

        assertEquals(listOf(pointer), offered)
    }

    @Test
    fun aPureImportPointerIsNotOfferedForAdoption() {
        // The one hand-written `@AGENTS.md` line is exactly what Ruleblend would have written itself;
        // there is nothing to adopt, so the pointer must not clutter the found list.
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n")

        val offered = service.unmanagedFiles(redirectingTarget()).map { it.first }

        assertTrue(pointer !in offered, "pure import pointer must not be offered: $offered")
    }

    @Test
    fun aPointerWithImportLineAndOtherContentIsStillOffered() {
        // Import line plus other hand-written content: the rest is genuinely adoptable.
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n\n# My notes\n\nAdopt me.\n")

        val offered = service.unmanagedFiles(redirectingTarget()).map { it.first }

        assertEquals(listOf(pointer), offered)
    }

    @Test
    fun installIntoALegacyFormatFileMigratesItsMarkers() {
        val other = block.copy(id = "old-block", content = "Old body.")
        file.writeText(
            "# Kept\n\n<!-- ruleblend:begin id=old-block v=1 hash=${hashContent("Old body.")} -->\n" +
                "Old body.\n<!-- ruleblend:end id=old-block -->\n",
        )

        service.install(file, block)

        val text = file.readText()
        assertTrue("ruleblend:begin" !in text, "legacy markers must be rewritten: $text")
        assertEquals(TargetOwnershipMode.PARTIAL, service.ownershipMode(file))
        assertTrue(text.contains("<!-- rb1 "), "legacy run must become an rb1 run: $text")
        assertTrue("# Kept" in text)
        assertEquals(listOf("old-block", "git-no-commit"), service.regions(file).map { it.id })
        assertEquals(InstallStatus.SYNCED, statusOf(service.regions(file).first(), other))
    }

    @Test
    fun syncMigratesALegacyFormatPointerFile() {
        val pointer = dir.resolve("CLAUDE.md")
        val target = redirectingTarget()
        service.install(target, block)
        val migrated = pointer.readText()
        pointer.writeText(
            "<!-- ruleblend:begin id=$IMPORT_REGION_ID v=0 hash=${hashContent("@AGENTS.md")} -->\n" +
                "@AGENTS.md\n<!-- ruleblend:end id=$IMPORT_REGION_ID -->\n",
        )

        service.syncRedirects(target)

        assertEquals(migrated, pointer.readText())
        assertEquals("@AGENTS.md\n", pointer.readText(), "a legacy import region becomes the plain line")
        assertTrue("ruleblend:begin" !in pointer.readText())
        assertEquals(TargetOwnershipMode.NONE, service.ownershipMode(pointer))
    }

    @Test
    fun adoptAndRemoveAlsoStopWritingLegacyMarkers() {
        file.writeText("# Notes\n\n<!-- kb old v1 ${hashContent("old")} -->\nold\n<!-- kb:end -->\n")

        service.adopt(file, block)

        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))
        assertTrue("<!-- kb old" !in file.readText())

        service.remove(file, "old")

        assertEquals(TargetOwnershipMode.OWNED, service.ownershipMode(file))
        assertTrue(file.readText().contains("<!-- rb1 "))

        service.remove(file, block.id)

        assertEquals(TargetOwnershipMode.NONE, service.ownershipMode(file))
        assertEquals("", file.readText())
    }

    @Test
    fun removingAnAbsentLegacyRegionDoesNotRewriteTheFile() {
        val legacy = "<!-- kb old v1 ${hashContent("old")} -->\nold\n<!-- kb:end -->\n"
        file.writeText(legacy)

        service.remove(file, "absent")

        assertEquals(legacy, file.readText())
        assertEquals(TargetOwnershipMode.LEGACY, service.ownershipMode(file))
    }

    @Test
    fun scanAdoptFlagsImportsAndUnresolvedLinks() {
        dir.resolve("nearby.md").writeText("here")
        val text = """
            See @AGENTS.md and @docs/rules.md.
            Also [ok](nearby.md) and [gone](missing/page.md).
            [remote](https://example.com) is fine.
        """.trimIndent()

        val scan = service.scanAdopt(dir, text)

        assertEquals(listOf("AGENTS.md", "docs/rules.md"), scan.imports)
        assertEquals(listOf("missing/page.md"), scan.brokenLinks)
    }

    @Test
    fun scanAdoptReturnsEmptyForCleanText() {
        val scan = service.scanAdopt(dir, "Just prose, no directives, no links.")
        assertTrue(scan.isEmpty)
    }

    @Test
    fun referencedImportsSurfacesDirectImport() {
        dir.resolve("AGENTS.md").writeText("See @docs/rules.md for details.\n")
        Files.createDirectories(dir.resolve("docs"))
        dir.resolve("docs").resolve("rules.md").writeText("No further imports.\n")
        val target = ProjectTarget(dir, listOf(agent("AGENTS.md")))

        val refs = service.referencedImports(target)

        assertEquals(listOf(dir.resolve("docs/rules.md")), refs)
    }

    @Test
    fun referencedImportsFollowsTransitively() {
        dir.resolve("AGENTS.md").writeText("@docs/rules.md\n")
        Files.createDirectories(dir.resolve("docs"))
        dir.resolve("docs").resolve("rules.md").writeText("@docs/sub/deep.md\n")
        Files.createDirectories(dir.resolve("docs").resolve("sub"))
        dir.resolve("docs").resolve("sub").resolve("deep.md").writeText("leaf\n")
        val target = ProjectTarget(dir, listOf(agent("AGENTS.md")))

        val refs = service.referencedImports(target)

        // A -> B -> C: both B and C are reachable, in discovery order.
        assertEquals(
            listOf(dir.resolve("docs/rules.md"), dir.resolve("docs/sub/deep.md")),
            refs,
        )
    }

    @Test
    fun referencedImportsTerminatesOnCycle() {
        dir.resolve("a.md").writeText("@b.md\n")
        dir.resolve("b.md").writeText("@a.md\n")
        val target = ProjectTarget(dir, listOf(agent("a.md")))

        val refs = service.referencedImports(target)

        assertEquals(listOf(dir.resolve("b.md")), refs)
    }

    @Test
    fun referencedImportsSkipsBrokenImport() {
        dir.resolve("AGENTS.md").writeText("@missing.md\n")
        val target = ProjectTarget(dir, listOf(agent("AGENTS.md")))

        assertTrue(service.referencedImports(target).isEmpty())
    }

    @Test
    fun referencedImportsExcludesOwnedFileReachedByImport() {
        // AGENTS.md imports CLAUDE.md; CLAUDE.md is an owned file (a redirect source). It must not
        // reappear in the referenced set — owned files are listed separately in the UI.
        dir.resolve("AGENTS.md").writeText("@CLAUDE.md\n")
        dir.resolve("CLAUDE.md").writeText("hand-written\n")
        val claude = redirectingAgent()
        val target = ProjectTarget(dir, listOf(claude, agent("AGENTS.md", "codex")))

        assertTrue(service.referencedImports(target).isEmpty())
    }

    @Test
    fun referencedImportsSkipsDirectoryTarget() {
        dir.resolve("AGENTS.md").writeText("@docs\n")
        dir.resolve("docs").toFile().mkdirs()
        val target = ProjectTarget(dir, listOf(agent("AGENTS.md")))

        assertTrue(service.referencedImports(target).isEmpty())
    }

    @Test
    fun isPurePointerTrueForSingleBareImportLine() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n")
        assertTrue(service.isPurePointer(service.unmanaged(pointer), dir.resolve("AGENTS.md")))
    }

    @Test
    fun isPurePointerIgnoresSurroundingBlankLinesAndIndentation() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("\n  @AGENTS.md  \n\n")
        assertTrue(service.isPurePointer(service.unmanaged(pointer), dir.resolve("AGENTS.md")))
    }

    @Test
    fun isPurePointerFalseWhenExtraContentPresent() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n# notes\n")
        assertFalse(service.isPurePointer(service.unmanaged(pointer), dir.resolve("AGENTS.md")))
    }

    @Test
    fun isPurePointerFalseForWrongTarget() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@CLAUDE.md\n")
        assertFalse(service.isPurePointer(service.unmanaged(pointer), dir.resolve("AGENTS.md")))
    }

    @Test
    fun isPurePointerFalseForEmptyContent() {
        assertFalse(service.isPurePointer(UnmanagedContent("", 0), dir.resolve("AGENTS.md")))
    }

    @Test
    fun isPurePointerFalseForNullTarget() {
        val pointer = dir.resolve("CLAUDE.md")
        pointer.writeText("@AGENTS.md\n")
        assertFalse(service.isPurePointer(service.unmanaged(pointer), null))
    }

    // --- ownership drift + force-overwrite -----------------------------------

    @Test
    fun ownershipDriftIsFalseBeforeAnyEdit() {
        service.install(file, block)
        assertFalse(service.ownershipDrift(file), "a freshly installed run is intact")
    }

    @Test
    fun ownershipDriftDetectsManualEdits() {
        service.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Never ever commit."))

        assertTrue(service.ownershipDrift(file), "a hand edit must show as drift")
        assertEquals(InstallStatus.MODIFIED, statusOf(service.regions(file).single(), block))
    }

    @Test
    fun forcedInstallOverwritesAModifiedRegionFromTheLibrary() {
        val library = mutableMapOf(block.id to block)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited body."))

        resolver.install(file, block, force = true)

        assertEquals(InstallStatus.SYNCED, statusOf(service.regions(file).single(), block))
        assertFalse(service.ownershipDrift(file))
        assertTrue(file.readText().contains("Never commit."), "the hand edit is replaced by the library body")
    }

    @Test
    fun forcedInstallRebuildsTheWholeRunKeepingOtherBlocksAtLibraryBodies() {
        // Two blocks in one wrapped run. A length-changing edit to one makes the manifest's byte
        // boundaries unrecoverable, so force must rebuild the whole run from the library.
        val other = block.copy(id = "swift-style", content = "Use SwiftFormat.")
        val library = mutableMapOf(block.id to block, other.id to other)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        resolver.install(file, other)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited."))

        resolver.install(file, block, force = true)

        val regions = service.regions(file)
        assertEquals(listOf("git-no-commit", "swift-style"), regions.map { it.id })
        assertEquals(InstallStatus.SYNCED, statusOf(regions.first { it.id == block.id }, block))
        assertEquals(InstallStatus.SYNCED, statusOf(regions.first { it.id == other.id }, other))
    }

    @Test
    fun forcedInstallRebuildsTheRunWhenABlockDisappearedFromTheLibrary() {
        // Force-overwrite rebuilds from the library, so a block with no library entry left is dropped.
        val other = block.copy(id = "swift-style", content = "Use SwiftFormat.")
        val library = mutableMapOf(block.id to block) // 'other' is gone
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        resolver.install(file, other)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited."))

        resolver.install(file, block, force = true)

        val regions = service.regions(file)
        assertEquals(listOf("git-no-commit"), regions.map { it.id }, "a vanished block is dropped on rebuild")
    }

    @Test
    fun forcedInstallLandsARuleThatIsNotYetInTheRun() {
        // Overwrite is passed for every rule of a batch, including ones the run has never held.
        // The rebuild must append them instead of silently dropping them on the floor.
        val other = block.copy(id = "swift-style", content = "Use SwiftFormat.")
        val library = mutableMapOf(block.id to block, other.id to other)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited."))

        resolver.install(file, other, force = true)

        val regions = service.regions(file)
        assertEquals(listOf("git-no-commit", "swift-style"), regions.map { it.id })
        assertEquals(InstallStatus.SYNCED, statusOf(regions.first { it.id == other.id }, other))
    }

    @Test
    fun forcedInstallResetsANeighbourEvenWhenTheEditKeptItsByteLength() {
        // A same-length edit still parses, so the run reaches the rebuild only because force always
        // takes that path. Otherwise the neighbour's edit would survive and be blessed by a fresh
        // run hash — the behaviour would silently depend on how many bytes the user typed.
        val other = block.copy(id = "swift-style", content = "Use SwiftFormat.")
        val library = mutableMapOf(block.id to block, other.id to other)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        resolver.install(file, other)
        file.writeText(file.readText().replace("Use SwiftFormat.", "Use swiftformat!"))

        resolver.install(file, block, force = true)

        val regions = service.regions(file)
        assertEquals(InstallStatus.SYNCED, statusOf(regions.first { it.id == other.id }, other))
        assertTrue(file.readText().contains("Use SwiftFormat."), "the neighbour is taken from the library too")
    }

    @Test
    fun localChangeIsSavedAsANewLibraryVersionAndResynced() {
        val library = mutableMapOf(block.id to block)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Never commit without review."))

        val local = resolver.localChange(target(), block.id)
        val saved = block.copy(version = 2, content = local.content).also { library[it.id] = it }
        resolver.acceptLocalChange(target(), saved, local)

        assertEquals("Never commit without review.", saved.content)
        assertEquals(InstallStatus.SYNCED, resolver.status(target(), saved))
        assertEquals(2, resolver.regions(file).single().version)
    }

    @Test
    fun localChangeOfAHeadedRuleKeepsTheHeadingOutOfTheBody() {
        val headed = block.copy(heading = "Git", headingLevel = 3)
        val library = mutableMapOf(headed.id to headed)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, headed)
        assertTrue(file.readText().contains("### Git"), "the heading is written into the file")
        file.writeText(file.readText().replace("Never commit.", "Never commit without review."))

        val local = resolver.localChange(target(), headed.id)
        val saved = headed.withRegionContent(local.content).copy(version = 2).also { library[it.id] = it }
        resolver.acceptLocalChange(target(), saved, local)

        assertEquals("Git", saved.heading)
        assertEquals(3, saved.headingLevel)
        assertEquals("Never commit without review.", saved.content)
        assertEquals(InstallStatus.SYNCED, resolver.status(target(), saved))
        assertEquals(1, file.readText().lines().count { it == "### Git" }, "the heading is not doubled")
    }

    @Test
    fun localChangeRefusesToDiscardAnotherEditedRule() {
        val other = block.copy(id = "swift-style", content = "Use SwiftFormat.")
        val library = mutableMapOf(block.id to block, other.id to other)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        resolver.install(file, other)
        file.writeText(
            file.readText()
                .replace("Never commit.", "Never commit without review.")
                .replace("Use SwiftFormat.", "Use swiftformat!"),
        )

        assertFailsWith<WrappedRunDriftException> { resolver.localChange(target(), block.id) }
    }

    @Test
    fun forcedInstallIntoAFileWithoutARunIsAnOrdinaryInstall() {
        file.writeText("# Notes\n")
        val resolver = IntegrationService(blockResolver = { id -> block.takeIf { id == block.id } })

        resolver.install(file, block, force = true)

        assertEquals(listOf(block.id), service.regions(file).map { it.id })
        assertTrue(file.readText().contains("# Notes"), "hand-written text survives")
    }

    @Test
    fun forcedInstallWithoutResolverReThrowsUnrecoverableDrift() {
        // A length-changing edit: parsed() itself raises, and no resolver means the rebuild path is
        // unavailable, so the drift propagates instead of silently losing data.
        service.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited body, different length."))

        assertFailsWith<WrappedRunDriftException> { service.install(file, block, force = true) }
    }

    @Test
    fun unmanagedOwnedFileRemainsReadableAfterLengthChangingEdit() {
        service.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited managed body."))

        assertTrue(service.unmanaged(file).isEmpty)
        assertTrue(service.ownershipDrift(file))
    }

    @Test
    fun forcedRemoveDropsAModifiedRegion() {
        val library = mutableMapOf(block.id to block)
        val resolver = IntegrationService(blockResolver = { library[it] })
        resolver.install(file, block)
        file.writeText(file.readText().replace("Never commit.", "Hand-edited body."))

        resolver.remove(file, block.id, force = true)

        assertEquals(TargetOwnershipMode.NONE, service.ownershipMode(file))
        assertEquals("", file.readText())
    }

    @Test
    fun reorderSwapsBlocksInTheFileAndRepeatsWithoutChangingIt() {
        val second = Block(id = "style", name = "Style", version = 1, content = "Prefer data classes.")
        file.writeText("# Notes\n")
        service.install(file, block)
        service.install(file, second)

        service.reorder(file, listOf(second.id, block.id))
        val once = file.readText()
        service.reorder(file, listOf(second.id, block.id))

        assertEquals(listOf(second.id, block.id), service.regions(file).map { it.id })
        assertEquals(once, file.readText())
        assertEquals("# Notes", service.unmanaged(file).text)
        assertTrue(block.content in once && second.content in once)
    }

    @Test
    fun reorderThatChangesNothingLeavesALegacyFileAsItIs() {
        val legacy = LegacyMarkers.upsert("", regionFor(block))
        file.writeText(legacy)

        service.reorder(file, listOf(block.id))

        assertEquals(legacy, file.readText())
        assertEquals(TargetOwnershipMode.LEGACY, service.ownershipMode(file))
    }

    /** Claude's shape: reads AGENTS.md through a CLAUDE.md pointer, alongside an agent that reads it directly. */
    private fun redirectingTarget() = ProjectTarget(dir, listOf(redirectingAgent(), agent("AGENTS.md", "codex")))

    private fun redirectingAgent() = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile() = dir.resolve("global-CLAUDE.md")
        override fun projectFile(projectDir: Path) = projectDir.resolve("AGENTS.md")
        override fun projectRedirectFile(projectDir: Path) = projectDir.resolve("CLAUDE.md")
    }

    @Test
    fun `an external edit during a write is recomputed into rather than overwritten`() {
        val library = mutableMapOf(block.id to block)
        // The resolver runs between the read and the rename of a forced install: the one place a
        // test can put another editor's save inside that window.
        var editing = true
        val resolver = IntegrationService(blockResolver = { id ->
            if (editing) {
                editing = false
                file.writeText(file.readText() + "\nWritten by hand mid-install.\n")
            }
            library[id]
        })
        // A file whose hand-written text is its own: an owned file gives a forced rebuild the whole
        // file, so there would be nothing outside the run for the other editor to keep.
        file.writeText("Project notes.\n")
        resolver.install(file, block)
        val second = Block(id = "style", name = "Style", version = 1, content = "Prefer data classes.")
        library[second.id] = second

        resolver.install(file, second, force = true)

        assertTrue(file.readText().contains("Written by hand mid-install."), "the hand edit must survive")
        assertEquals(setOf(block.id, second.id), service.regions(file).map { it.id }.toSet())
    }

    /**
     * The file and its index entry are one change: a reader that holds the lock must never find the
     * index describing a file that has since changed, however install and remove interleave.
     */
    @Test
    fun `a concurrent install and remove keep the index in step with the file`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
        val index = TargetIndex(dir.resolve("targets.index"), coordinator)
        val locked = IntegrationService(index, coordinator = coordinator)
        val other = Block(id = "style", name = "Style", version = 1, content = "Prefer data classes.")
        locked.install(file, block)
        val writing = AtomicBoolean(true)
        val mismatches = AtomicInteger()
        val reader = thread {
            while (writing.get()) {
                coordinator.mutate(file, index.file) {
                    val inFile = locked.regions(file).map { it.id }.toSet()
                    val indexed = index.load().single().blocks.map { it.id }.toSet()
                    if (inFile != indexed) mismatches.incrementAndGet()
                }
            }
        }
        val start = CyclicBarrier(2)
        listOf(
            thread { start.await(); repeat(50) { locked.install(file, other) } },
            thread { start.await(); repeat(50) { locked.remove(file, other.id) } },
        ).forEach { it.join() }
        writing.set(false)
        reader.join()

        assertEquals(0, mismatches.get(), "the index must never describe a file that has since changed")
    }

    @Test
    fun `concurrent installs into one file all survive`() {
        val coordinator = TargetMutationCoordinator(InterProcessLock(dir.resolve("ruleblend.lock")))
        val locked = IntegrationService(coordinator = coordinator)
        val blocks = (1..4).map { Block(id = "rule-$it", name = "Rule $it", version = 1, content = "Rule $it body.") }
        val start = CyclicBarrier(blocks.size)
        blocks.map { block ->
            thread {
                start.await()
                repeat(10) { locked.install(file, block) }
            }
        }.forEach { it.join() }
        assertEquals(blocks.map { it.id }.toSet(), service.regions(file).map { it.id }.toSet())
    }
}
