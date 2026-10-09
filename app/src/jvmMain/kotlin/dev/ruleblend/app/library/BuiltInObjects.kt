package dev.ruleblend.app.library

import dev.ruleblend.mcp.MCP_ENTRY_VERSION
import dev.ruleblend.mcp.MCP_SERVER_DESCRIPTION
import dev.ruleblend.mcp.MCP_SERVER_NAME
import dev.ruleblend.mcp.ruleblendEntryJson
import dev.ruleblend.mcp.skill.BundledSkill
import java.nio.file.Path

/**
 * The two things Ruleblend installs that the library does not hold: the bundled workflow skill and
 * Ruleblend's own MCP registration. They are listed so the user can read what an assistant was
 * given; they are marked [LibraryObject.builtIn] so nothing offers to edit, delete or install them.
 *
 * [binary] is the launcher an entry points at — `null` in a run that could not resolve one, where
 * the command is shown as the placeholder Settings already prints beside the manual command.
 */
fun builtInLibraryObjects(binary: Path?): List<LibraryObject> = listOf(
    LibraryObject(
        key = LibraryObjectKey(LibraryObjectKind.SKILL, BUILTIN_SKILL_ID),
        name = BundledSkill.NAME,
        description = BundledSkill.description,
        version = BundledSkill.version.toString(),
        content = BundledSkill.text,
        builtIn = true,
    ),
    LibraryObject(
        key = LibraryObjectKey(LibraryObjectKind.MCP, BUILTIN_MCP_ID),
        name = MCP_SERVER_NAME,
        description = MCP_SERVER_DESCRIPTION,
        version = MCP_ENTRY_VERSION.toString(),
        content = builtInMcpEntry(binary),
        builtIn = true,
    ),
)

/** The entry as the connectors write it, rendered in the canonical JSON the MCP editor shows. */
private fun builtInMcpEntry(binary: Path?): String =
    ruleblendEntryJson(binary?.toString() ?: BUILTIN_MCP_COMMAND_PLACEHOLDER)

private const val BUILTIN_MCP_COMMAND_PLACEHOLDER = "<Ruleblend.app>/Contents/MacOS/Ruleblend"
