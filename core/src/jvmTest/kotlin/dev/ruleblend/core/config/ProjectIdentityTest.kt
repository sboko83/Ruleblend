package dev.ruleblend.core.config

import dev.ruleblend.core.integration.SkillInstallRecord
import dev.ruleblend.core.integration.SkillInstallStateStore
import dev.ruleblend.core.integration.SubagentInstallRecord
import dev.ruleblend.core.integration.SubagentInstallStateStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectIdentityTest {
    private val root = Files.createTempDirectory("ruleblend-identity-")
    private val project = Files.createDirectories(root.resolve("Проект с пробелом"))
    private val alias: String get() = if (File.separatorChar == '\\') project.toString().uppercase().replace('\\', '/')
        else project.resolve("../${project.fileName}").toString()

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun mergesEveryProjectSettingAndPersistsWithoutDroppingAttachments() {
        val file = root.resolve("config.json")
        val original = AppConfig(
            projects = listOf(project.toString(), alias),
            projectProfiles = linkedMapOf(project.toString() to listOf(ProfileBinding("first", true)),
                alias to listOf(ProfileBinding("second", true), ProfileBinding("first", false))),
            disabledAgents = linkedMapOf(project.toString() to listOf("codex"), alias to listOf("pi")),
            ruleScopes = mapOf("rule" to alias),
            places = PlaceBoard(pinned = listOf("project:$alias", "project:$project"),
                recent = listOf("project:$alias"), sets = listOf(
                    ProjectSet("work", listOf(alias, project.toString())), ProjectSet("duplicate", listOf(alias)))),
            keptDrifts = linkedMapOf("project:$project" to mapOf("rule-a" to "a"), "project:$alias" to mapOf("rule-b" to "b")),
            ignoredEntries = linkedMapOf("project:$project" to listOf("skill:$project/skill"),
                "project:$alias" to listOf("subagent:$alias/agent.md", "mcp:$alias/config.toml:server:name")),
        )
        file.writeText(Json.encodeToString(original))
        val store = ConfigStore(file)
        val migrated = store.load()
        val key = project.projectKey()
        assertEquals(listOf(key), migrated.projects)
        assertEquals(listOf(ProfileBinding("first", false), ProfileBinding("second", true)), migrated.projectProfiles[key])
        assertEquals(listOf("codex", "pi"), migrated.disabledAgents[key])
        assertEquals(key, migrated.ruleScopes["rule"])
        assertEquals(listOf("project:$key"), migrated.places.pinned)
        assertEquals(listOf("project:$key"), migrated.places.recent)
        assertEquals(listOf(listOf(key), emptyList()), migrated.places.sets.map { it.projects })
        assertEquals(setOf("rule-a", "rule-b"), migrated.keptDrifts["project:$key"]?.keys)
        assertEquals(listOf("skill:${project.resolve("skill").projectKey()}",
            "subagent:${project.resolve("agent.md").projectKey()}",
            "mcp:${project.resolve("config.toml").projectKey()}:server:name"), migrated.ignoredEntries["project:$key"])
        store.update { it }
        assertEquals(migrated, store.load())
        assertEquals(migrated, Json.decodeFromString<AppConfig>(file.readText()))
        assertEquals(original, Json.decodeFromString<AppConfig>(root.resolve("config.json.before-path-migration").readText()))
    }

    @Test fun migratesSkillAndSubagentOwnershipBeforeNextWrite() {
        val skills = SkillInstallStateStore(root)
        val subagents = SubagentInstallStateStore(root)
        skills.file.writeText(Json.encodeToString(listOf(SkillInstallRecord("project:$alias", "codex", "s", "fp", origin = "p=first"))))
        subagents.file.writeText(Json.encodeToString(listOf(SubagentInstallRecord("project:$alias", "codex", "a", "fp", origin = "p=second"))))
        val key = "project:${project.projectKey()}"
        assertEquals("p=first", skills.find(key, "codex", "s")?.origin)
        assertEquals("p=second", subagents.find(key, "codex", "a")?.origin)
        skills.record(SkillInstallRecord("agent", "pi", "other", "fp"))
        subagents.record(SubagentInstallRecord("agent", "pi", "other", "fp"))
        assertEquals(key, Json.decodeFromString<List<SkillInstallRecord>>(skills.file.readText()).first().targetKey)
        assertEquals(key, Json.decodeFromString<List<SubagentInstallRecord>>(subagents.file.readText()).first().targetKey)
        skills.removeAll("project:$alias", "s")
        subagents.removeAll("project:$alias", "a")
        assertNull(skills.find(key, "codex", "s"))
        assertNull(subagents.find(key, "codex", "a"))
    }

    @Test fun conflictingOwnershipIsRetainedWithoutGrantingOverwritePermission() {
        val skills = SkillInstallStateStore(root)
        skills.file.writeText(Json.encodeToString(listOf(
            SkillInstallRecord("project:$project", "codex", "s", "one"),
            SkillInstallRecord("project:$alias", "codex", "s", "two"),
        )))
        assertEquals(2, skills.all().size)
        assertNull(skills.find("project:${project.projectKey()}", "codex", "s"))
    }

    @Test fun junctionOrSymlinkUsesThePhysicalProjectIncludingMissingChildren() {
        val link = root.resolve("alias")
        if (File.separatorChar == '\\') {
            val command = "New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''") +
                "' -Target '" + project.toString().replace("'", "''") + "' | Out-Null"
            val shell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
            val process = ProcessBuilder(shell.toString(), "-NoProfile", "-NonInteractive", "-Command", command)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
        } else Files.createSymbolicLink(link, project)
        try {
            assertEquals(project.projectKey(), link.projectKey())
            assertEquals(project.resolve("missing/child").projectKey(), link.resolve("missing/child").projectKey())
        } finally { Files.delete(link) }
        assertTrue(Files.isDirectory(project))
    }

    @Test fun longNativePathsAndSeparatorsKeepOneIdentity() {
        var long = project
        repeat(14) { long = long.resolve("длинное имя каталога") }
        Files.createDirectories(long)
        assertTrue(long.toString().length > 260)
        val variant = if (File.separatorChar == '\\') long.toString().uppercase().replace('\\', '/') else long.toString()
        assertEquals(long.projectKey(), projectKeyOf(variant))
        assertFalse(long.projectKey().endsWith(File.separator))
    }

    @Test fun unavailableUncPathsKeepTheirShareRoot() {
        if (File.separatorChar == '\\') {
            val path = Path.of("\\\\localhost\\ruleblend-missing-share\\Каталог\\..\\Проект")
            assertEquals("\\\\localhost\\ruleblend-missing-share\\проект", path.projectKey())
            assertEquals(path.projectKey(), projectKeyOf("//LOCALHOST/ruleblend-missing-share/Проект/"))
        } else {
            assertEquals(project.projectKey(), projectKeyOf("/$project"))
        }
    }
}
