package dev.ruleblend.core.integration

import dev.ruleblend.core.model.Block
import dev.ruleblend.core.model.LineEnding
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class IntegrationLineEndingsTest {
    @Test fun outputChoiceChangesOnlyTheManagedRunAndKeepsItsManifestValid() {
        val directory = Files.createTempDirectory("ruleblend-managed-eol")
        try {
            val file = directory.resolve("rules.md")
            val before = "# User\r\nKeep these bytes\n\r\n"
            val after = "\r\n# Tail\nKeep these too\r\n"
            val block = Block("one", "One", content = "first\nsecond\n")
            val initial = WrappedRun(TargetOwnershipMode.PARTIAL).upsert("", regionFor(block))
            for (ending in LineEnding.entries) {
                Files.writeString(file, before + initial + after)
                val service = IntegrationService(lineEnding = { ending })
                service.install(file, block.copy(version = 2, content = "changed\nsecond\n"))
                val text = Files.readString(file)
                val run = text.substring(before.length, text.length - after.length)
                assertEquals(before, text.take(before.length))
                assertEquals(after, text.takeLast(after.length))
                assertEquals(ending.apply(run), run)
                assertFalse(service.ownershipDrift(file))
                assertEquals("changed\nsecond", service.regions(file).single().content)
                service.remove(file, block.id)
                assertEquals(before + after, Files.readString(file))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun ruleMutationsPreserveUserBytesOnDisk() {
        val directory = Files.createTempDirectory("ruleblend-line-endings")
        try {
            val file = directory.resolve("rules.md")
            val service = IntegrationService()
            val first = Block(id = "first", name = "First", content = "Первое\nправило 🪟")
            val second = Block(id = "second", name = "Second", content = "Second\nrule")
            val updated = first.copy(version = 2, content = "Updated\nправило")
            val encoding = WrappedRun(TargetOwnershipMode.PARTIAL)
            for (separators in listOf(listOf("\n"), listOf("\r\n"), listOf("\r\n", "\n"))) {
                fun convert(text: String): String = text.split('\n').mapIndexed { index, line ->
                    if (index == 0) line else separators[(index - 1) % separators.size] + line
                }.joinToString("")
                val before = convert("# Заметки 🪟  \n\t \n")
                val after = convert("\n \t\n# Tail  \n\t ")
                Files.write(file, before.encodeToByteArray())
                service.install(file, first)
                assertContentEquals(before.encodeToByteArray(), Files.readAllBytes(file).take(before.encodeToByteArray().size).toByteArray())
                val run = convert(encoding.upsert("", regionFor(first)))
                Files.write(file, (before + run + after).encodeToByteArray())
                fun assertOutside() {
                    val text = Files.readString(file)
                    assertContentEquals(before.encodeToByteArray(), text.substringBefore(PARTIAL_NOTICE).encodeToByteArray())
                    assertContentEquals(after.encodeToByteArray(), text.substringAfter("<!-- rb:end -->\n").encodeToByteArray())
                    assertFalse(service.ownershipDrift(file))
                }
                service.install(file, second)
                assertOutside()
                service.install(file, updated)
                assertOutside()
                service.reorder(file, listOf(second.id, first.id))
                assertOutside()
                assertEquals(listOf(second.id, first.id), service.regions(file).map { it.id })
                service.remove(file, first.id)
                assertOutside()
                service.remove(file, second.id)
                assertContentEquals((before + after).encodeToByteArray(), Files.readAllBytes(file))

                val legacy = convert(LegacyMarkers.upsert("", regionFor(first)))
                Files.write(file, (before + legacy + after).encodeToByteArray())
                service.migrateLegacy(file, TargetOwnershipMode.PARTIAL)
                val migrated = Files.readString(file)
                assertContentEquals((before + after).encodeToByteArray(), migrated.take(before.length + after.length).encodeToByteArray())
                assertEquals(listOf(regionFor(first)), service.regions(file))
                service.disown(file)
                assertEquals(TargetOwnershipMode.NONE, service.ownershipMode(file))
                assertContentEquals((before + after).encodeToByteArray(), Files.readAllBytes(file).take((before + after).encodeToByteArray().size).toByteArray())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
