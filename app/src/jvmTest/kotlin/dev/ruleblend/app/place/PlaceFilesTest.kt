package dev.ruleblend.app.place

import dev.ruleblend.app.theme.StatusMark
import dev.ruleblend.app.theme.leading
import dev.ruleblend.core.integration.AgentAdapter
import dev.ruleblend.core.integration.AgentGlobalTarget
import dev.ruleblend.core.integration.FileSegment
import dev.ruleblend.core.integration.InstallStatus
import dev.ruleblend.core.integration.ManagedRegion
import dev.ruleblend.core.integration.PlaceEntry
import dev.ruleblend.core.integration.PlaceEntryOrigin
import dev.ruleblend.core.integration.ProjectTarget
import dev.ruleblend.core.integration.SkillDirEntry
import dev.ruleblend.core.integration.TargetOwnershipMode
import dev.ruleblend.core.integration.hashContent
import dev.ruleblend.core.model.SkillFrontmatter
import java.nio.file.Path
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceFilesTest {
    private val project: Path = Path.of("/tmp/ledger-kmp")

    private val claude = object : AgentAdapter {
        override val id = "claude-code"
        override val name = "Claude Code"
        override fun isAvailable() = true
        override fun globalFile(): Path = Path.of("/home/.claude/CLAUDE.md")
        override fun projectFile(projectDir: Path): Path = projectDir.resolve("AGENTS.md")
        override fun projectRedirectFile(projectDir: Path): Path = projectDir.resolve("CLAUDE.md")
    }

    private fun region(id: String, version: Int = 1, group: String? = null, body: String = "body") =
        ManagedRegion(id, version, group, hashContent(body), body)

    @Test fun `home opens the first changed tab in display order for every combination of changes`() {
        val files = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.PARTIAL },
            exists = { true },
            mcpFiles = listOf(project.resolve(".mcp.json")),
            skillDirectories = listOf(project.resolve(".claude/skills")),
            subagentDirectories = listOf(project.resolve(".claude/agents")),
        )
        val tabs = placeTabs(files)
        for (status in listOf(StatusMark.UPDATE, StatusMark.CONFLICT)) {
            for (mask in 0 until (1 shl tabs.size)) {
                val changed = tabs.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val changedFiles = changed.map { it.sections.last() }.toSet()
                val marks: (PlaceFileTab) -> Set<StatusMark> = {
                    if (it in changedFiles) setOf(status) else setOf(StatusMark.MANAGED, StatusMark.UNMANAGED)
                }
                assertEquals(
                    changed.firstOrNull()?.kind ?: PlaceTabKind.RULES,
                    initialPlaceTab(tabs, focusChanges = true, marks),
                    "status=$status, mask=$mask",
                )
                assertEquals(PlaceTabKind.RULES, initialPlaceTab(tabs, focusChanges = false, marks))
            }
        }
        assertNull(initialPlaceTab(emptyList(), focusChanges = true) { emptySet() })
    }

    @Test fun `a skill root outside an agent global folder keeps its full home-qualified address`() {
        val home = Path.of(System.getProperty("user.home"))

        assertEquals("~${File.separator}.agents${File.separator}skills",
            displayPath(home.resolve(".codex"), home.resolve(".agents/skills")))
    }

    @Test fun `a project shows its rules file, pointer, mcp config and skills directory as one row of tabs`() {
        val tabs = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.PARTIAL },
            exists = { true },
            mcpFiles = listOf(project.resolve(".mcp.json")),
            skillDirectories = listOf(project.resolve(".claude/skills")),
            subagentDirectories = listOf(project.resolve(".claude/agents")),
        )

        assertEquals(listOf("AGENTS.md", "CLAUDE.md", ".mcp.json",
            ".claude${File.separator}skills${File.separator}",
            ".claude${File.separator}agents${File.separator}"), tabs.map { it.title })
        assertEquals(
            listOf(PlaceFileKind.RULES, PlaceFileKind.POINTER, PlaceFileKind.MCP, PlaceFileKind.SKILLS, PlaceFileKind.SUBAGENTS),
            tabs.map { it.kind },
        )
        assertEquals(TargetOwnershipMode.PARTIAL, tabs[0].mode, "ownership is a property of the rules file")
        assertNull(tabs[1].mode, "a pointer has no ownership mode of its own")
        assertEquals("AGENTS.md", tabs[1].importedName)
    }

    @Test fun `files group into one tab per kind, and a pointer sits under the rules it leads to`() {
        val files = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.PARTIAL },
            exists = { true },
            mcpFiles = listOf(project.resolve(".mcp.json")),
            skillDirectories = listOf(project.resolve(".claude/skills")),
            subagentDirectories = listOf(project.resolve(".claude/agents")),
        )

        val tabs = placeTabs(files)

        assertEquals(listOf(PlaceTabKind.RULES, PlaceTabKind.SKILLS, PlaceTabKind.SUBAGENTS, PlaceTabKind.MCP), tabs.map { it.kind })
        assertEquals(
            listOf("AGENTS.md", "CLAUDE.md"),
            tabs.first().sections.map { it.title },
            "a pointer is how rules are reached, not a kind of its own",
        )
    }

    @Test fun `a kind with no address at all is not offered as a tab`() {
        val files = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.NONE },
            exists = { true },
        )

        assertEquals(listOf(PlaceTabKind.RULES), placeTabs(files).map { it.kind })
    }

    @Test fun `a file counts what it holds, and says when it holds nothing`() {
        val file = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.PARTIAL },
            exists = { true },
        ).first()
        val filled = placeFileParts(
            listOf(FileSegment.Hand("# ledger-kmp"), FileSegment.Run(listOf(region("kotlin-style")))),
        )
        val empty = listOf(PlaceFilePart.Managed(emptyList()))

        assertEquals(PlaceObjectCount(1, 0), placeObjectCount(filled))
        assertEquals(PlaceObjectCount(0, 0), placeObjectCount(empty))
        assertEquals(true, placeSectionHasContent(file, filled))
        assertEquals(false, placeSectionHasContent(file, empty), "an empty run is not content")
        assertEquals(
            false,
            placeSectionHasContent(file.copy(exists = false), filled),
            "a file that is not there yet has nothing to show, whatever the preview holds",
        )
    }

    @Test fun `an agent global place shows its own file, named without a project root`() {
        val tabs = placeFileTabs(
            target = AgentGlobalTarget(claude),
            ownership = { TargetOwnershipMode.OWNED },
            exists = { false },
        )

        assertEquals(listOf("CLAUDE.md"), tabs.map { it.title })
        assertEquals(false, tabs.single().exists, "a place can be configured before its file exists")
    }

    @Test fun `an agent global place shows its assistant directory under the name`() {
        assertEquals(claude.globalFile().parent.toAbsolutePath().toString(),
            placeLocation(AgentGlobalTarget(claude)))
    }

    @Test fun `the preview keeps the file's order and attaches library names and statuses`() {
        val segments = listOf(
            FileSegment.Hand("# ledger-kmp"),
            FileSegment.Run(listOf(region("kotlin-style", version = 2, group = "kmp"), region("gone"))),
            FileSegment.Hand("Trailing note."),
        )

        val parts = placeFileParts(
            segments = segments,
            name = { id -> "Kotlin style".takeIf { id == "kotlin-style" } },
            status = { id -> InstallStatus.UPDATE_AVAILABLE.takeIf { id == "kotlin-style" } },
        )

        assertEquals(3, parts.size)
        assertEquals(PlaceFilePart.Hand("# ledger-kmp"), parts[0])
        assertEquals(PlaceFilePart.Hand("Trailing note."), parts[2])
        val rows = (parts[1] as PlaceFilePart.Managed).rows
        assertEquals(
            listOf(PlaceBlockRow("kotlin-style", "Kotlin style", "2", "kmp", InstallStatus.UPDATE_AVAILABLE, "body")),
            rows.take(1),
        )
        // Installed but no longer in the library: the id is all we honestly have, and no status.
        assertEquals(PlaceBlockRow("gone", "gone", "1", null, null, "body"), rows[1])
    }

    @Test fun `a pointer's managed run is shown as the import line it holds`() {
        val parts = placePointerParts(listOf(FileSegment.Run(listOf(region("ruleblend-import", body = "@AGENTS.md")))))

        assertEquals(listOf(PlaceFilePart.ManagedText("@AGENTS.md")), parts)
    }

    @Test fun `a file's marks name every state it holds, and grey means nothing of Ruleblend's`() {
        val file = placeFileTabs(
            target = ProjectTarget(project, listOf(claude)),
            ownership = { TargetOwnershipMode.PARTIAL },
            exists = { true },
        ).first()
        val mixed = listOf(
            PlaceFilePart.Managed(
                listOf(
                    PlaceBlockRow("a", "A", "1", null, InstallStatus.SYNCED),
                    PlaceBlockRow("b", "B", "1", null, InstallStatus.MODIFIED),
                ),
            ),
        )
        val handWritten = listOf(PlaceFilePart.Hand("# ledger-kmp"))

        val foreignOnly = listOf(
            PlaceFilePart.Managed(
                rows = emptyList(),
                additionalRows = listOf(
                    PlaceBlockRow("community", "Community", "1", null, null, origin = PlaceEntryOrigin.FOREIGN),
                ),
            ),
        )

        assertEquals(setOf(StatusMark.MANAGED, StatusMark.CONFLICT), placeFileMarks(file, mixed))
        assertEquals(setOf(StatusMark.UNMANAGED), placeFileMarks(file, handWritten))
        assertEquals(
            setOf(StatusMark.UNMANAGED),
            placeFileMarks(file, foreignOnly),
            "an address holding only foreign entries has nothing of Ruleblend's in it",
        )
        assertEquals(
            setOf(StatusMark.UNMANAGED),
            placeFileMarks(file, listOf(PlaceFilePart.Managed(emptyList()))),
            "an empty managed run is not a region: nothing is installed here",
        )
        assertEquals(
            emptySet(),
            placeFileMarks(file.copy(exists = false), handWritten),
            "an address with no file at it has no state to report",
        )
    }

    @Test fun `a dot paints the two most important marks it holds, in that order`() {
        val all = setOf(StatusMark.UNMANAGED, StatusMark.UPDATE, StatusMark.CONFLICT, StatusMark.MANAGED)

        assertEquals(listOf(StatusMark.CONFLICT, StatusMark.UPDATE), all.leading())
        assertEquals(
            listOf(StatusMark.MANAGED, StatusMark.UNMANAGED),
            setOf(StatusMark.UNMANAGED, StatusMark.MANAGED).leading(),
            "with nothing to fix, what is set up leads and what is not follows",
        )
        assertEquals(listOf(StatusMark.UNMANAGED), setOf(StatusMark.UNMANAGED).leading())
    }

    @Test fun `mcp keeps its managed rows while disk enumeration is empty`() {
        val rows = installedRows(
            items = listOf(Triple("github", "GitHub", "2"), Triple("postgres", "Postgres", "1")),
            statuses = mapOf("github" to InstallStatus.SYNCED),
        )

        assertEquals(
            listOf(PlaceBlockRow("github", "GitHub", "2", null, InstallStatus.SYNCED)),
            placeEntries(rows, emptyList()),
        )
    }

    @Test fun `skill entries deduplicate physical keys and order ours orphaned then foreign`() {
        fun entry(
            id: String,
            key: String,
            origin: PlaceEntryOrigin,
            description: String? = null,
        ) = PlaceEntry(
            value = SkillDirEntry(
                directoryId = "default",
                path = Path.of("/skills/$id"),
                id = id,
                meta = SkillFrontmatter(name = "$id skill", description = description, version = "7"),
            ),
            key = key,
            libraryId = id,
            origin = origin,
        )

        val rows = placeEntries(
            managedRows = listOf(PlaceBlockRow("managed", "Managed", "3", null, InstallStatus.SYNCED)),
            entries = listOf(
                entry("foreign", "skill:default:foreign", PlaceEntryOrigin.FOREIGN, "Foreign summary"),
                entry("managed", "skill:default:managed", PlaceEntryOrigin.MANAGED),
                entry("orphan", "skill:default:orphan", PlaceEntryOrigin.ORPHAN),
                entry("duplicate", "skill:default:foreign", PlaceEntryOrigin.FOREIGN),
            ),
        )

        assertEquals(listOf("managed", "orphan", "foreign"), rows.map { it.id })
        assertEquals(
            listOf(PlaceEntryOrigin.MANAGED, PlaceEntryOrigin.ORPHAN, PlaceEntryOrigin.FOREIGN),
            rows.map { it.origin },
        )
        assertEquals(Path.of("/skills/managed"), rows.first().path)
        assertEquals("Foreign summary", rows.last().subtitle)
    }

    @Test fun `an installed skill missing from this root keeps its row`() {
        val rows = placeEntries(
            managedRows = listOf(
                PlaceBlockRow("review", "Review", "3", null, InstallStatus.UPDATE_AVAILABLE),
                PlaceBlockRow("format", "Format", "1", null, InstallStatus.SYNCED),
            ),
            entries = listOf(
                PlaceEntry(
                    value = SkillDirEntry("native", Path.of("/skills/format"), "format", SkillFrontmatter(name = "Format")),
                    key = "skill:/skills/format",
                    libraryId = "format",
                    origin = PlaceEntryOrigin.MANAGED,
                ),
            ),
        )

        assertEquals(listOf("format", "review"), rows.map { it.id })
        assertEquals(Path.of("/skills/format"), rows.first().path)
        assertEquals(null, rows.last().path)
        assertEquals(InstallStatus.UPDATE_AVAILABLE, rows.last().status)
    }

    @Test fun `a skill tab reads only the root it is the tab of`() {
        fun entry(root: String, id: String) = PlaceEntry(
            value = SkillDirEntry("native", Path.of("$root/$id"), id, SkillFrontmatter(name = id)),
            key = "skill:$root/$id",
            libraryId = id,
            origin = PlaceEntryOrigin.FOREIGN,
        )

        val entries = listOf(entry("/project/.codex/skills", "review"), entry("/project/.agents/skills", "format"))

        assertEquals(
            listOf("review"),
            skillEntriesIn(entries, Path.of("/project/.codex/skills")).map { it.value.id },
        )
        assertEquals(emptyList(), skillEntriesIn(entries, Path.of("/project/.claude/skills")))
    }

    @Test fun `foreign skill entries make a section non-empty and remain separate from ours`() {
        val file = PlaceFileTab("skills", "skills/", PlaceFileKind.SKILLS, Path.of("/skills"), exists = true)
        val parts = listOf(
            PlaceFilePart.Managed(
                rows = listOf(PlaceBlockRow("managed", "Managed", "1", null, InstallStatus.SYNCED)),
                additionalRows = listOf(
                    PlaceBlockRow("orphan", "Orphan", "1", null, null, origin = PlaceEntryOrigin.ORPHAN),
                    PlaceBlockRow("foreign", "Foreign", "1", null, null, origin = PlaceEntryOrigin.FOREIGN),
                ),
            ),
        )

        // An orphaned copy is still one Ruleblend installed; only its library object is gone, and the
        // tab shows its row, so the count it is labelled with has to include it.
        assertEquals(PlaceObjectCount(2, 1), placeObjectCount(parts))
        assertEquals(true, placeSectionHasContent(file, parts))
    }

    @Test fun `a hidden entry is counted only by the switch that brings it back`() {
        val parts = listOf(
            PlaceFilePart.Managed(
                rows = listOf(PlaceBlockRow("managed", "Managed", "1", null, InstallStatus.SYNCED)),
                additionalRows = listOf(
                    PlaceBlockRow("foreign", "Foreign", "1", null, null, origin = PlaceEntryOrigin.FOREIGN),
                    PlaceBlockRow("hidden", "Hidden", "1", null, null, origin = PlaceEntryOrigin.IGNORED),
                ),
            ),
        )

        assertEquals(PlaceObjectCount(1, 1), placeObjectCount(parts))
        assertEquals(1, placeHiddenCount(parts))
    }

    @Test fun `two config entries of one file fold apart`() {
        fun row(name: String) = PlaceBlockRow(
            id = name,
            name = name,
            version = null,
            group = null,
            status = null,
            origin = PlaceEntryOrigin.FOREIGN,
            path = Path.of("/project/.mcp.json"),
            entryKey = "mcp:/project/.mcp.json:$name",
        )

        assertEquals(listOf("mcp:/project/.mcp.json:first", "mcp:/project/.mcp.json:second"), listOf(row("first"), row("second")).map { it.foldId() })
    }
}
