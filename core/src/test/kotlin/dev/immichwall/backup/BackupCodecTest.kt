package dev.immichwall.backup

import dev.immichwall.schedule.Schedule
import dev.immichwall.schedule.ScheduleEntry
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCodecTest {
    private val ann = "00000000-0000-4000-8000-000000000001"
    private val album = "00000000-0000-4000-8000-0000000000a1"

    private val specs = listOf(
        SourceSpec.People(ids = listOf(ann), names = listOf("Ann"), requireAll = true),
        SourceSpec.Album(albumId = album, albumName = "Trips"),
        SourceSpec.SmartQuery(query = "beach", personIds = listOf(ann), personNames = listOf("Ann")),
        SourceSpec.Location(city = "Springfield"),
        SourceSpec.Favorites,
        SourceSpec.Memories(windowDays = 5, yearsAgoMin = 2, yearsAgoMax = 0),
        SourceSpec.EverythingRandom,
        SourceSpec.Custom(personIds = listOf(ann), query = "dog", city = "Springfield", takenAfter = "2020-01-01"),
    )
    private val cycles = specs.mapIndexed { i, spec ->
        SavedCycle(id = "00000000-0000-4000-8000-00000000c0$i", name = "Cycle $i", spec = spec, peoplePreference = "require")
    }
    private val schedule = Schedule(
        enabled = true,
        defaultCycleId = cycles[0].id,
        entries = listOf(ScheduleEntry("e1", "Winter", cycles[1].id, "11-26", "01-01")),
    )
    private val options = BackupOptions(
        targetCacheCount = 120,
        refreshIntervalHours = 12,
        rotationMinIntervalMinutes = 30,
        qualityFilterEnabled = false,
        syncOverCellular = true,
        deriveThemeFromPhoto = true,
    )
    private val server = BackupServer(serverUrl = "https://photos.example.test", awayUrl = "https://away.example.test", apiKey = "SECRET-KEY")
    private val backup = Backup(
        exportedAt = "2026-10-07T12:00:00Z",
        cycles = cycles,
        activeCycleId = cycles[2].id,
        schedule = schedule,
        options = options,
        server = server,
    )

    @Test fun `round trip keeps every source variant and a year-wrapping entry`() {
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        assertIs<BackupDecodeResult.Ok>(result)
        assertEquals(backup, result.backup)
        assertTrue(result.backup.hasServer)
        assertEquals(8, result.backup.cycleCount)
        assertEquals(1, result.backup.scheduleEntryCount)
    }

    @Test fun `no server block means the api key field is absent from the text`() {
        val text = BackupCodec.encode(backup.copy(server = null))
        assertFalse(text.contains("apiKey"))
        assertFalse(text.contains("server"))
    }

    @Test fun `garbage, empty object and array are not backups`() {
        for (text in listOf("not json at all", "{}", "[]", "[1,2]", "")) {
            assertEquals(BackupDecodeResult.NotABackup, BackupCodec.decode(text), text)
        }
    }

    @Test fun `zero cycles is not a backup`() {
        val text = BackupCodec.encode(backup.copy(cycles = emptyList()))
        assertEquals(BackupDecodeResult.NotABackup, BackupCodec.decode(text))
    }

    @Test fun `a higher format number is newer format even when the body has other shapes`() {
        val text = """{"marker":"${BackupCodec.MARKER}","format":${BackupCodec.FORMAT + 1},"cycles":"changed shape"}"""
        val result = BackupCodec.decode(text)
        assertIs<BackupDecodeResult.NewerFormat>(result)
        assertEquals(BackupCodec.FORMAT + 1, result.format)
    }

    @Test fun `marker with a missing or non-numeric format is not a backup`() {
        assertEquals(BackupDecodeResult.NotABackup, BackupCodec.decode("""{"marker":"${BackupCodec.MARKER}"}"""))
        assertEquals(BackupDecodeResult.NotABackup, BackupCodec.decode("""{"marker":"${BackupCodec.MARKER}","format":"x"}"""))
        assertEquals(BackupDecodeResult.NotABackup, BackupCodec.decode("""{"marker":"${BackupCodec.MARKER}","format":1,"cycles":5}"""))
    }

    @Test fun `unknown fields are ignored`() {
        val text = BackupCodec.encode(backup).replaceFirst("{", """{"futureThing": {"a": 1},""")
        val result = BackupCodec.decode(text)
        assertIs<BackupDecodeResult.Ok>(result)
        assertEquals(backup, result.backup)
    }

    @Test fun `a dangling active id is repaired to the first cycle`() {
        val text = BackupCodec.encode(backup.copy(activeCycleId = "no-such-cycle"))
        val result = BackupCodec.decode(text)
        assertIs<BackupDecodeResult.Ok>(result)
        assertEquals(cycles[0].id, result.backup.activeCycleId)
    }

    @Test fun `schedule entries naming a missing cycle are kept`() {
        val orphan = ScheduleEntry("e2", "Gone", "no-such-cycle", "03-01", "03-02")
        val text = BackupCodec.encode(backup.copy(schedule = schedule.copy(entries = schedule.entries + orphan)))
        val result = BackupCodec.decode(text)
        assertIs<BackupDecodeResult.Ok>(result)
        assertEquals(2, result.backup.scheduleEntryCount)
    }

    @Test fun `a file with no options block decodes with options absent`() {
        val text = BackupCodec.encode(backup.copy(options = null))
        assertFalse(text.contains("targetCacheCount"))
        val result = BackupCodec.decode(text)
        assertIs<BackupDecodeResult.Ok>(result)
        assertNull(result.backup.options)
    }
}
