package dev.immichwall.backup

import dev.immichwall.schedule.Schedule
import dev.immichwall.source.SavedCycle
import dev.immichwall.source.SourceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackupFrozenKeyTest {
    private val album = "00000000-0000-4000-8000-0000000000a1"
    private val tripsKey = "0bbe8fe84c11645afb7e1c4e512774cac32069d239296d8e52a152ae7a419e94"

    /** The text v1.1.0 wrote for one album cycle: no `frozenKey` anywhere. */
    private val v110 = """
        {
            "marker": "immich-wallpaper-backup",
            "format": 1,
            "exportedAt": "2026-10-06T12:00:00Z",
            "cycles": [
                {
                    "id": "c1",
                    "name": "Album: Trips",
                    "spec": {
                        "mode": "album",
                        "albumId": "$album",
                        "albumName": "Trips"
                    },
                    "peoplePreference": "require"
                }
            ],
            "activeCycleId": "c1",
            "schedule": {
                "enabled": false,
                "defaultCycleId": "",
                "entries": []
            }
        }
    """.trimIndent()

    @Test fun `a backup written before cycles had a frozen key still decodes, with the key it had`() {
        val result = BackupCodec.decode(v110)
        assertIs<BackupDecodeResult.Ok>(result)
        val cycle = result.backup.cycles.single()
        assertEquals(SavedCycle("c1", "Album: Trips", SourceSpec.Album(album, "Trips"), "require"), cycle)
        assertEquals("", cycle.frozenKey)
        assertEquals(tripsKey, cycle.keyBase("2026-10-07"))
    }

    @Test fun `a round trip keeps a frozen key`() {
        val renamed = SavedCycle("c1", "Album: Journeys", SourceSpec.Album(album, "Journeys"), frozenKey = tripsKey)
        val backup = Backup("2026-10-07T12:00:00Z", listOf(renamed), "c1", Schedule())
        val result = BackupCodec.decode(BackupCodec.encode(backup))
        assertIs<BackupDecodeResult.Ok>(result)
        assertEquals(tripsKey, result.backup.cycles.single().frozenKey)
        assertEquals(tripsKey, result.backup.cycles.single().keyBase("2026-10-07"))
    }
}
